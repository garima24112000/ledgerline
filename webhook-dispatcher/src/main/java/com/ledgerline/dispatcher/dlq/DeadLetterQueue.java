package com.ledgerline.dispatcher.dlq;

import com.ledgerline.dispatcher.config.DispatcherProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The dead-letter topic as a queue an operator can drain. Progress is the committed offset of a
 * dedicated consumer group ({@value #REPLAY_GROUP}): everything after it has not been replayed yet.
 * That keeps the dispatcher stateless (no database) and makes {@code dlq_size} a simple subtraction.
 */
@Component
public class DeadLetterQueue {

    static final String REPLAY_GROUP = "webhook-dispatcher-dlq-replay";

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueue.class);

    private final ConsumerFactory<String, String> consumerFactory;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaAdmin kafkaAdmin;
    private final String mainTopic;
    private final String deadLetterTopic;
    private final AtomicLong size = new AtomicLong();

    public DeadLetterQueue(ConsumerFactory<String, String> consumerFactory, KafkaTemplate<String, String> kafkaTemplate,
                           KafkaAdmin kafkaAdmin, DispatcherProperties properties, MeterRegistry meterRegistry) {
        this.consumerFactory = consumerFactory;
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaAdmin = kafkaAdmin;
        this.mainTopic = properties.topic();
        this.deadLetterTopic = properties.deadLetterTopic();
        Gauge.builder("dlq.size", size, AtomicLong::get)
                .description("Dead-lettered webhooks not replayed yet")
                .register(meterRegistry);
    }

    /**
     * Re-publishes every dead letter present right now to the main topic, then commits. The copy
     * carries the original key and body but none of the retry headers, so it starts again with a
     * full set of attempts; the event id is unchanged, so a merchant that got it after all dedupes it.
     * {@code synchronized}: two replays at once in this instance would publish the same letters twice.
     *
     * @return how many events were replayed
     */
    public synchronized int replay() throws ExecutionException, InterruptedException {
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = consumerFactory.createConsumer(REPLAY_GROUP, null, null, overrides)) {
            List<TopicPartition> partitions = consumer.partitionsFor(deadLetterTopic).stream()
                    .map(p -> new TopicPartition(deadLetterTopic, p.partition()))
                    .toList();
            consumer.assign(partitions);
            // Snapshot: letters arriving while we replay wait for the next replay.
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);

            int replayed = 0;
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                for (ConsumerRecord<String, String> letter : consumer.poll(Duration.ofMillis(500))) {
                    TopicPartition tp = new TopicPartition(letter.topic(), letter.partition());
                    if (letter.offset() < end.get(tp)) {
                        kafkaTemplate.send(new ProducerRecord<>(mainTopic, letter.key(), letter.value())).get();
                        replayed++;
                    }
                }
            }
            Map<TopicPartition, OffsetAndMetadata> done = new HashMap<>();
            end.forEach((tp, offset) -> done.put(tp, new OffsetAndMetadata(offset)));
            consumer.commitSync(done);
            log.info("Replayed {} dead-lettered webhook(s)", replayed);
            refreshSize();
            return replayed;
        }
    }

    /**
     * Recomputes {@code dlq_size} = sum over DLT partitions of (end offset - replay group's committed
     * offset). On a schedule rather than per scrape, so a slow or down Kafka never stalls /actuator/prometheus.
     */
    @Scheduled(fixedDelayString = "${dispatcher.dlq-size-refresh}")
    public void refreshSize() {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            List<TopicPartition> partitions = admin.describeTopics(List.of(deadLetterTopic)).allTopicNames().get()
                    .get(deadLetterTopic).partitions().stream()
                    .map(p -> new TopicPartition(deadLetterTopic, p.partition()))
                    .toList();
            Map<TopicPartition, ListOffsetsResultInfo> latest = offsets(admin, partitions, OffsetSpec.latest());
            Map<TopicPartition, ListOffsetsResultInfo> earliest = offsets(admin, partitions, OffsetSpec.earliest());
            Map<TopicPartition, OffsetAndMetadata> replayed =
                    admin.listConsumerGroupOffsets(REPLAY_GROUP).partitionsToOffsetAndMetadata().get();

            long total = 0;
            for (TopicPartition tp : partitions) {
                OffsetAndMetadata committed = replayed.get(tp);
                long from = committed != null ? committed.offset() : earliest.get(tp).offset();
                total += Math.max(0, latest.get(tp).offset() - from);
            }
            size.set(total);
        } catch (ExecutionException e) {
            log.warn("Could not compute dlq_size; keeping the last value", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long size() {
        return size.get();
    }

    private static Map<TopicPartition, ListOffsetsResultInfo> offsets(AdminClient admin, List<TopicPartition> partitions,
                                                                      OffsetSpec spec) throws ExecutionException, InterruptedException {
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        partitions.forEach(tp -> request.put(tp, spec));
        return admin.listOffsets(request).all().get();
    }
}
