package com.ledgerline.gateway.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final String TOPIC = "payment.events";

    @Mock
    private OutboxRepository repository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(call ->
                call.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        relay = new OutboxRelay(repository, kafkaTemplate, transactionTemplate, objectMapper,
                new OutboxProperties(true, Duration.ofMillis(200), 100, TOPIC, 6, (short) 1, Duration.ofSeconds(5)));
    }

    @Test
    void publishesKeyedByMerchantAndMarksAckedRowsPublished() throws Exception {
        OutboxEvent event = event(42);
        when(repository.lockUnpublished(100)).thenReturn(List.of(event));
        when(kafkaTemplate.send(eq(TOPIC), eq("42"), anyString())).thenReturn(acked());

        assertThat(relay.relayOnce()).containsExactly(event.id());

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq("42"), value.capture());
        JsonNode envelope = objectMapper.readTree(value.getValue());
        assertThat(envelope.get("id").asText()).isEqualTo(event.id().toString());
        assertThat(envelope.get("type").asText()).isEqualTo("payment.captured");
        assertThat(envelope.get("merchantId").asLong()).isEqualTo(42);
        assertThat(envelope.get("createdAt").asText()).isEqualTo("2026-09-28T10:00:00Z");
        assertThat(envelope.get("data").get("amount").asLong()).isEqualTo(500);
        verify(repository).markPublished(List.of(event.id()));
        verify(repository).incrementAttempts(List.of());
    }

    @Test
    void failedSendsAreLeftUnpublishedWithAnAttemptCounted() {
        OutboxEvent ok = event(1);
        OutboxEvent nacked = event(2);
        OutboxEvent threw = event(3);
        when(repository.lockUnpublished(100)).thenReturn(List.of(ok, nacked, threw));
        when(kafkaTemplate.send(eq(TOPIC), eq("1"), anyString())).thenReturn(acked());
        when(kafkaTemplate.send(eq(TOPIC), eq("2"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("not enough replicas")));
        when(kafkaTemplate.send(eq(TOPIC), eq("3"), anyString())).thenThrow(new KafkaException("no metadata"));

        assertThat(relay.relayOnce()).containsExactly(ok.id());

        verify(repository).markPublished(List.of(ok.id()));
        verify(repository).incrementAttempts(List.of(nacked.id(), threw.id()));
    }

    @Test
    void nothingToPublishSendsNothing() {
        when(repository.lockUnpublished(100)).thenReturn(List.of());

        assertThat(relay.relayOnce()).isEmpty();

        verifyNoInteractions(kafkaTemplate);
        verify(repository, never()).markPublished(any());
    }

    private static OutboxEvent event(long merchantId) {
        return new OutboxEvent(UUID.randomUUID(), merchantId, UUID.randomUUID(), "payment.captured",
                "{\"amount\": 500}", Instant.parse("2026-09-28T10:00:00Z"));
    }

    private static CompletableFuture<SendResult<String, String>> acked() {
        return CompletableFuture.completedFuture(null);
    }
}
