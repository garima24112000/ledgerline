package com.ledgerline.dispatcher.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.dispatcher.config.DispatcherProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Consumes payment events and delivers them. A failed delivery is not retried in place (that would
 * block the partition, and every other merchant on it, for the whole backoff). Instead Spring Kafka
 * forwards the record to the next retry topic ({@code payment.events-retry-<delay>}), whose consumer
 * waits until the record is due. After the last attempt it goes to {@code payment.events-dlt}.
 *
 * <p>Every log line written while handling a record carries the event's ids in the MDC (see
 * {@link EventLogContext}).
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final WebhookDeliveryService deliveryService;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final String mainTopic;
    private final Counter deadLettered;

    public PaymentEventListener(WebhookDeliveryService deliveryService, MeterRegistry meterRegistry,
                                ObjectMapper objectMapper, DispatcherProperties properties) {
        this.deliveryService = deliveryService;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.mainTopic = properties.topic();
        this.deadLettered = Counter.builder("webhook.dead.lettered")
                .description("Webhooks that used up every attempt (or can't be retried) and landed in the DLT")
                .register(meterRegistry);
    }

    @RetryableTopic(
            attempts = "${dispatcher.retry.max-attempts}",
            backoff = @Backoff(
                    delayExpression = "${dispatcher.retry.initial-delay-ms}",
                    multiplierExpression = "${dispatcher.retry.multiplier}",
                    maxDelayExpression = "${dispatcher.retry.max-delay-ms}"),
            exclude = {UnknownMerchantException.class, InvalidEventException.class}, // retrying can't help
            numPartitions = "${dispatcher.partitions}",
            replicationFactor = "${dispatcher.replicas}")
    @KafkaListener(topics = "${dispatcher.topic}")
    public void onEvent(ConsumerRecord<String, String> record) {
        if (!record.topic().equals(mainTopic)) {
            meterRegistry.counter("webhook.retry").increment();
        }
        try (EventLogContext ignored = EventLogContext.of(objectMapper, record.value())) {
            try {
                deliveryService.deliver(record.value());
            } catch (RuntimeException e) {
                // Logged here, while the event's ids are still in the MDC; Spring Kafka then routes the record.
                log.warn("Webhook delivery from {} failed: {}", record.topic(), e.toString());
                throw e;
            }
        }
    }

    /** Kept in the DLT for {@code POST /admin/dlq/replay}; this only records that it arrived. */
    @DltHandler
    public void onDeadLetter(ConsumerRecord<String, String> letter) {
        deadLettered.increment();
        Header error = letter.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        try (EventLogContext ignored = EventLogContext.of(objectMapper, letter.value())) {
            log.error("Webhook dead-lettered: {} (last error: {})", letter.value(),
                    error == null ? "unknown" : new String(error.value(), StandardCharsets.UTF_8));
        }
    }
}
