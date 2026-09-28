package com.ledgerline.gateway.idempotency;

import java.time.Duration;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * idempotency_keys in plain SQL. Every lookup is by (merchant_id, key), which is the unique index.
 * Times come from Postgres' now(), so all app instances share one clock for lock expiry.
 */
@Repository
public class IdempotencyRepository {

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the key. ON CONFLICT DO NOTHING instead of catching the unique violation: a failed
     * INSERT would abort the surrounding Postgres transaction, while this just reports 0 rows.
     * If another transaction has inserted the same key but not committed yet, this waits for it.
     *
     * @return true if this call created the row
     */
    public boolean insertInProgress(long merchantId, String key, String requestHash, Duration lockTtl) {
        return jdbc.sql("""
                        INSERT INTO idempotency_keys (merchant_id, key, request_hash, status, locked_until)
                        VALUES (:merchantId, :key, :hash, 'IN_PROGRESS', now() + make_interval(secs => :ttl))
                        ON CONFLICT (merchant_id, key) DO NOTHING""")
                .param("merchantId", merchantId)
                .param("key", key)
                .param("hash", requestHash)
                .param("ttl", seconds(lockTtl))
                .update() == 1;
    }

    public Optional<IdempotencyRecord> find(long merchantId, String key) {
        return jdbc.sql("""
                        SELECT request_hash, status, response_code, response_body::text AS response_body,
                               locked_until <= now() AS lock_expired
                        FROM idempotency_keys
                        WHERE merchant_id = :merchantId AND key = :key""")
                .param("merchantId", merchantId)
                .param("key", key)
                .query((rs, row) -> new IdempotencyRecord(
                        rs.getString("request_hash"),
                        IdempotencyStatus.valueOf(rs.getString("status")),
                        rs.getObject("response_code", Integer.class),
                        rs.getString("response_body"),
                        rs.getBoolean("lock_expired")))
                .optional();
    }

    /**
     * Takes the lock on an IN_PROGRESS key whose lock has expired or was released. The WHERE clause
     * makes it atomic: of two callers racing for the same key, exactly one updates the row.
     */
    public boolean tryLock(long merchantId, String key, Duration lockTtl) {
        return jdbc.sql("""
                        UPDATE idempotency_keys SET locked_until = now() + make_interval(secs => :ttl)
                        WHERE merchant_id = :merchantId AND key = :key
                          AND status = 'IN_PROGRESS' AND locked_until <= now()""")
                .param("merchantId", merchantId)
                .param("key", key)
                .param("ttl", seconds(lockTtl))
                .update() == 1;
    }

    public void complete(long merchantId, String key, int responseCode, String responseJson) {
        int updated = jdbc.sql("""
                        UPDATE idempotency_keys
                        SET status = 'COMPLETED', response_code = :code, response_body = CAST(:body AS jsonb),
                            locked_until = now()
                        WHERE merchant_id = :merchantId AND key = :key AND status = 'IN_PROGRESS'""")
                .param("merchantId", merchantId)
                .param("key", key)
                .param("code", responseCode)
                .param("body", responseJson)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("idempotency key " + key + " of merchant " + merchantId + " is not in progress");
        }
    }

    /** Gives up the lock but keeps the key IN_PROGRESS, so a retry with the same key can take over. */
    public void release(long merchantId, String key) {
        jdbc.sql("""
                        UPDATE idempotency_keys SET locked_until = now()
                        WHERE merchant_id = :merchantId AND key = :key AND status = 'IN_PROGRESS'""")
                .param("merchantId", merchantId)
                .param("key", key)
                .update();
    }

    /** Forgets a request that failed before it changed anything. */
    public void delete(long merchantId, String key) {
        jdbc.sql("DELETE FROM idempotency_keys WHERE merchant_id = :merchantId AND key = :key AND status = 'IN_PROGRESS'")
                .param("merchantId", merchantId)
                .param("key", key)
                .update();
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }
}
