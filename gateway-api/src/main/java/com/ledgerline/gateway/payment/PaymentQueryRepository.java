package com.ledgerline.gateway.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read queries written as SQL, so the statement is exactly the one measured with EXPLAIN in DESIGN.md. */
@Repository
public class PaymentQueryRepository {

    private final JdbcClient jdbc;

    public PaymentQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One page of a merchant's payments, newest first. Served by
     * {@code payments_merchant_created_idx (merchant_id, created_at DESC, id DESC)}: the row
     * comparison {@code (created_at, id) < (...)} becomes an index condition, so a page deep in the
     * list costs the same as the first (no OFFSET scanning past skipped rows).
     */
    public List<PaymentResponse> findPage(long merchantId, PaymentStatus status, Instant from, Instant to,
                                          PaymentCursor after, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, status, amount, currency, refunded_amount, merchant_order_id, decline_reason,
                       created_at, updated_at
                FROM payments
                WHERE merchant_id = :merchantId""");
        Map<String, Object> params = new HashMap<>();
        params.put("merchantId", merchantId);
        if (status != null) {
            sql.append(" AND status = :status");
            params.put("status", status.name());
        }
        if (from != null) {
            sql.append(" AND created_at >= :from");
            params.put("from", timestamp(from));
        }
        if (to != null) {
            sql.append(" AND created_at < :to");
            params.put("to", timestamp(to));
        }
        if (after != null) {
            sql.append(" AND (created_at, id) < (:afterCreatedAt, :afterId)");
            params.put("afterCreatedAt", timestamp(after.createdAt()));
            params.put("afterId", after.id());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        params.put("limit", limit);

        return jdbc.sql(sql.toString()).params(params).query(PaymentQueryRepository::toResponse).list();
    }

    /**
     * Unresolved payments for the reconciler, oldest first. The status list is a literal, not a bind
     * parameter, so the planner can prove it matches the partial index {@code payments_unresolved_idx}.
     */
    public List<UUID> findUnresolvedIds(Instant updatedBefore, int limit) {
        return jdbc.sql("""
                        SELECT id FROM payments
                        WHERE status IN ('PENDING', 'UNKNOWN') AND updated_at <= :updatedBefore
                        ORDER BY updated_at
                        LIMIT :limit""")
                .param("updatedBefore", timestamp(updatedBefore))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    private static PaymentResponse toResponse(ResultSet rs, int row) throws SQLException {
        return new PaymentResponse(
                rs.getObject("id", UUID.class),
                PaymentStatus.valueOf(rs.getString("status")),
                rs.getLong("amount"),
                rs.getString("currency"),
                rs.getLong("refunded_amount"),
                rs.getString("merchant_order_id"),
                rs.getString("decline_reason"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
