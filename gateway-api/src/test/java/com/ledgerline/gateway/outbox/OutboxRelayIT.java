package com.ledgerline.gateway.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.gateway.AbstractGatewayIT;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxRelayIT extends AbstractGatewayIT {

    private static final String TOPIC = "payment.events";

    @Autowired
    private OutboxRelay relay;
    @Autowired
    private OutboxRepository repository;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OutboxProperties properties;

    @BeforeEach
    void drainLeftoversFromOtherTests() {
        while (!relay.relayOnce().isEmpty()) {
            // keep going
        }
    }

    @Test
    void capturedPaymentIsPublishedKeyedByMerchant() throws Exception {
        TestMerchant merchant = createMerchant();
        stubCharge("APPROVED", 0);
        String body = createPayment(merchant, "k-" + UUID.randomUUID(), 10_000, "tok_visa").getBody();
        UUID paymentId = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        UUID eventId = jdbc.sql("SELECT id FROM outbox_events WHERE aggregate_id = ?").param(paymentId)
                .query(UUID.class).single();

        assertThat(relay.relayOnce()).containsExactly(eventId);

        assertThat(jdbc.sql("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?").param(eventId)
                .query(Boolean.class).single()).isTrue();
        ConsumerRecord<String, String> record = readTopicToEnd().stream()
                .filter(r -> r.value().contains(eventId.toString())).findFirst().orElseThrow();
        assertThat(record.key()).isEqualTo(String.valueOf(merchant.id()));
        JsonNode envelope = objectMapper.readTree(record.value());
        assertThat(envelope.get("id").asText()).isEqualTo(eventId.toString());
        assertThat(envelope.get("type").asText()).isEqualTo("payment.captured");
        assertThat(envelope.get("merchantId").asLong()).isEqualTo(merchant.id());
        assertThat(envelope.get("data").get("id").asText()).isEqualTo(paymentId.toString());
    }

    /**
     * Two relay "replicas" (separate instances, as if in two pods) drain 300 rows at the same time,
     * in small batches so they collide many times. SKIP LOCKED must hand each row to exactly one.
     */
    @Test
    void twoRelayInstancesNeverPublishTheSameRow() throws Exception {
        long merchantId = createMerchant().id();
        Set<UUID> inserted = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            inserted.add(insertOutboxRow(merchantId));
        }
        OutboxProperties smallBatches = new OutboxProperties(true, Duration.ofMillis(200), 10, TOPIC, 6, (short) 1);
        OutboxRelay relayA = new OutboxRelay(repository, kafkaTemplate, transactionTemplate, objectMapper, smallBatches);
        OutboxRelay relayB = new OutboxRelay(repository, kafkaTemplate, transactionTemplate, objectMapper, smallBatches);

        CountDownLatch start = new CountDownLatch(1);
        List<UUID> publishedByA;
        List<UUID> publishedByB;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<List<UUID>> a = pool.submit(() -> drain(relayA, start));
            Future<List<UUID>> b = pool.submit(() -> drain(relayB, start));
            start.countDown();
            publishedByA = a.get();
            publishedByB = b.get();
        }

        assertThat(publishedByA).isNotEmpty().doesNotHaveDuplicates();
        assertThat(publishedByB).isNotEmpty().doesNotHaveDuplicates();
        assertThat(publishedByA).doesNotContainAnyElementsOf(publishedByB);
        List<UUID> all = new ArrayList<>(publishedByA);
        all.addAll(publishedByB);
        assertThat(all).containsAll(inserted);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE merchant_id = ? AND published_at IS NULL")
                .param(merchantId).query(Long.class).single()).isZero();

        Map<String, Integer> copiesOnTopic = new HashMap<>();
        for (ConsumerRecord<String, String> record : readTopicToEnd()) {
            String id = objectMapper.readTree(record.value()).get("id").asText();
            copiesOnTopic.merge(id, 1, Integer::sum);
        }
        for (UUID id : inserted) {
            assertThat(copiesOnTopic.get(id.toString())).as("copies of %s on the topic", id).isEqualTo(1);
        }
    }

    @Test
    void unreachableBrokerLeavesTheRowUnpublishedWithAnAttemptCounted() {
        UUID eventId = insertOutboxRow(createMerchant().id());
        OutboxRelay deadRelay = new OutboxRelay(repository, deadBrokerTemplate(), transactionTemplate, objectMapper,
                properties);

        assertThat(deadRelay.relayOnce()).isEmpty();

        assertThat(jdbc.sql("SELECT attempts FROM outbox_events WHERE id = ? AND published_at IS NULL")
                .param(eventId).query(Integer.class).single()).isEqualTo(1);
        assertThat(relay.relayOnce()).contains(eventId); // a working relay picks it up next time
    }

    private static List<UUID> drain(OutboxRelay relay, CountDownLatch start) throws InterruptedException {
        start.await();
        List<UUID> published = new ArrayList<>();
        List<UUID> batch;
        while (!(batch = relay.relayOnce()).isEmpty()) {
            published.addAll(batch);
        }
        return published;
    }

    private UUID insertOutboxRow(long merchantId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO outbox_events (id, merchant_id, aggregate_id, event_type, payload)
                        VALUES (?, ?, ?, 'payment.captured', '{"amount": 100}')""")
                .params(id, merchantId, UUID.randomUUID())
                .update();
        return id;
    }

    /** Everything on the topic right now: reads every partition from the start up to its end offset. */
    private static List<ConsumerRecord<String, String>> readTopicToEnd() {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                    .map(p -> new TopicPartition(TOPIC, p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            while (!reachedEnd(consumer, end)) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }

    private static boolean reachedEnd(KafkaConsumer<?, ?> consumer, Map<TopicPartition, Long> end) {
        return end.entrySet().stream().allMatch(e -> consumer.position(e.getKey()) >= e.getValue());
    }

    private static KafkaTemplate<String, String> deadBrokerTemplate() {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 500);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }
}
