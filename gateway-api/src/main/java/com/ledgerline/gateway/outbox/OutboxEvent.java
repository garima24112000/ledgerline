package com.ledgerline.gateway.outbox;

import java.time.Instant;
import java.util.UUID;

/** An outbox row as the relay reads it. {@code payload} is the JSON text of the event's data. */
public record OutboxEvent(UUID id, long merchantId, UUID aggregateId, String eventType, String payload, Instant createdAt) {
}
