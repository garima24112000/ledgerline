package com.ledgerline.gateway.outbox;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, long merchantId, UUID aggregateId, EventType type, String payloadJson) {
        jdbc.sql("""
                        INSERT INTO outbox_events (id, merchant_id, aggregate_id, event_type, payload)
                        VALUES (:id, :merchantId, :aggregateId, :eventType, CAST(:payload AS jsonb))""")
                .param("id", id)
                .param("merchantId", merchantId)
                .param("aggregateId", aggregateId)
                .param("eventType", type.wireName())
                .param("payload", payloadJson)
                .update();
    }

    /**
     * Locks up to {@code limit} unpublished events, oldest first. {@code SKIP LOCKED} passes over rows
     * another relay has locked instead of waiting for them, so concurrent relays get disjoint batches.
     * Served by the partial index {@code outbox_events_unpublished_idx}.
     */
    public List<OutboxEvent> lockUnpublished(int limit) {
        return jdbc.sql("""
                        SELECT id, merchant_id, aggregate_id, event_type, payload::text AS payload, created_at
                        FROM outbox_events
                        WHERE published_at IS NULL
                        ORDER BY created_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED""")
                .param("limit", limit)
                .query((rs, row) -> new OutboxEvent(
                        rs.getObject("id", UUID.class),
                        rs.getLong("merchant_id"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public void markPublished(Collection<UUID> ids) {
        if (!ids.isEmpty()) {
            jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id IN (:ids)").param("ids", ids).update();
        }
    }

    public void incrementAttempts(Collection<UUID> ids) {
        if (!ids.isEmpty()) {
            jdbc.sql("UPDATE outbox_events SET attempts = attempts + 1 WHERE id IN (:ids)").param("ids", ids).update();
        }
    }
}
