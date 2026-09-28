package com.ledgerline.dispatcher.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Puts an event's ids ({@code eventId}, {@code eventType}, {@code merchantId}, {@code paymentId}) in the
 * MDC for a try-with-resources block, and removes them on close.
 *
 * <p>Events arrive through the outbox and Kafka, so there is no HTTP trace to continue: these ids are
 * what ties a dispatcher log line to the gateway's lines for the same payment. An event that doesn't
 * parse gets no ids; delivery reports that error itself.
 */
public final class EventLogContext implements AutoCloseable {

    private final Map<String, String> entries;

    private EventLogContext(Map<String, String> entries) {
        this.entries = entries;
        entries.forEach(MDC::put);
    }

    public static EventLogContext of(ObjectMapper objectMapper, String eventJson) {
        Map<String, String> entries = new LinkedHashMap<>();
        try {
            WebhookEvent event = objectMapper.readValue(eventJson, WebhookEvent.class);
            putIfPresent(entries, "eventId", event.id());
            putIfPresent(entries, "eventType", event.type());
            putIfPresent(entries, "merchantId", event.merchantId() > 0 ? String.valueOf(event.merchantId()) : null);
            putIfPresent(entries, "paymentId", event.paymentId());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // Nothing to correlate on.
        }
        return new EventLogContext(entries);
    }

    @Override
    public void close() {
        entries.keySet().forEach(MDC::remove);
    }

    private static void putIfPresent(Map<String, String> entries, String key, String value) {
        if (value != null) {
            entries.put(key, value);
        }
    }
}
