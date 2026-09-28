package com.ledgerline.gateway.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param relayEnabled turn the schedule off (tests call {@link OutboxRelay#relayOnce()} directly)
 * @param interval     pause between relay runs
 * @param batchSize    rows published per run
 * @param topic        Kafka topic the events go to
 * @param partitions   partitions of {@code topic} when the app creates it
 * @param replicas     replication factor of {@code topic} when the app creates it
 * @param lagRefresh   how often the outbox lag gauges are recomputed
 */
@ConfigurationProperties("gateway.outbox")
public record OutboxProperties(boolean relayEnabled, Duration interval, int batchSize, String topic,
                               int partitions, short replicas, Duration lagRefresh) {
}
