package com.ledgerline.dispatcher.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retry settings ({@code dispatcher.retry.*}) are read by {@code @RetryableTopic} placeholders directly.
 *
 * @param topic               the topic gateway-api publishes payment events to
 * @param partitions          partitions for the retry topics and DLT this app creates
 * @param replicas            replication factor for the topics this app creates
 * @param httpTimeout         hard limit on one webhook call
 * @param bulkheadMaxInFlight webhook calls one merchant may have in flight at once, per instance
 * @param merchantCacheTtl    how long a merchant's webhook URL and secret are cached
 * @param dlqSizeRefresh      how often the {@code dlq_size} gauge is recomputed
 */
@ConfigurationProperties("dispatcher")
public record DispatcherProperties(String topic, int partitions, short replicas, Duration httpTimeout,
                                   int bulkheadMaxInFlight, Duration merchantCacheTtl, Duration dlqSizeRefresh,
                                   Gateway gateway, Admin admin) {

    public record Gateway(String baseUrl, String serviceToken) {
    }

    public record Admin(String username, String password) {
    }

    public String deadLetterTopic() {
        return topic + "-dlt"; // @RetryableTopic's default DLT suffix
    }
}
