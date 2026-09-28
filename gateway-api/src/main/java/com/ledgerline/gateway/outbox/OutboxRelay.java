package com.ledgerline.gateway.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows to Kafka. Each run is one transaction:
 * <ol>
 *   <li>lock a batch of unpublished rows ({@code FOR UPDATE SKIP LOCKED});</li>
 *   <li>send them all, keyed by merchant id, and wait for every ack ({@code acks=all});</li>
 *   <li>mark the acked rows published and count an attempt on the rest; commit.</li>
 * </ol>
 * Safe with any number of replicas: a row locked by one relay is skipped by the others until that
 * relay commits, and by then it is published. Delivery is at-least-once: if Kafka acks and the commit
 * then fails, the row is sent again, which is why every event carries an id to dedupe on.
 *
 * <p>Uses a {@link TransactionTemplate} rather than {@code @Transactional}, so a plain
 * {@code new OutboxRelay(...)} (a second "replica" in a test) is transactional too.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                       TransactionTemplate transactionTemplate, ObjectMapper objectMapper, OutboxProperties properties) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${gateway.outbox.interval}", initialDelayString = "${gateway.outbox.interval}")
    void scheduledRun() {
        if (properties.relayEnabled()) {
            relayOnce();
        }
    }

    /** @return ids of the events published by this run */
    public List<UUID> relayOnce() {
        return transactionTemplate.execute(status -> {
            List<OutboxEvent> events = repository.lockUnpublished(properties.batchSize());
            if (events.isEmpty()) {
                return List.of();
            }
            // Send the whole batch first, then wait: the producer batches them into few requests.
            List<CompletableFuture<SendResult<String, String>>> sends = events.stream().map(this::send).toList();

            List<UUID> published = new ArrayList<>();
            List<UUID> failed = new ArrayList<>();
            for (int i = 0; i < events.size(); i++) {
                UUID id = events.get(i).id();
                if (awaitAck(sends.get(i), id)) {
                    published.add(id);
                } else {
                    failed.add(id);
                }
            }
            repository.markPublished(published);
            repository.incrementAttempts(failed);
            if (!failed.isEmpty()) {
                log.warn("Outbox relay: {} published, {} failed and will be retried", published.size(), failed.size());
            }
            return published;
        });
    }

    private CompletableFuture<SendResult<String, String>> send(OutboxEvent event) {
        try {
            return kafkaTemplate.send(properties.topic(), String.valueOf(event.merchantId()), envelope(event));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e); // e.g. no broker metadata within max.block.ms
        }
    }

    /** No timeout needed here: the producer fails the send itself after delivery.timeout.ms. */
    private static boolean awaitAck(CompletableFuture<SendResult<String, String>> send, UUID id) {
        try {
            send.get();
            return true;
        } catch (ExecutionException e) {
            log.warn("Could not publish outbox event {}", id, e.getCause());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The message merchants receive as the webhook body: {@code {id, type, merchantId, createdAt, data}}. */
    private String envelope(OutboxEvent event) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", event.id().toString());
            node.put("type", event.eventType());
            node.put("merchantId", event.merchantId());
            node.put("createdAt", event.createdAt().toString());
            node.set("data", objectMapper.readTree(event.payload()));
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("outbox event " + event.id() + " has invalid JSON", e);
        }
    }
}
