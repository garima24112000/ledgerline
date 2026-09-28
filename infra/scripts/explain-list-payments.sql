-- EXPLAIN ANALYZE for GET /v1/payments, with and without payments_merchant_created_idx.
-- Run after seed-payments.sql:
--
--   docker compose -f infra/docker-compose.yml exec -T postgres \
--     psql -U ledgerline -d ledgerline -f - < infra/scripts/explain-list-payments.sql
--
-- The "without" half drops the index inside a transaction and rolls back, so nothing changes.
-- Each query runs once untimed first, so both halves are measured with a warm cache.

SELECT id AS merchant_id FROM merchants WHERE name = 'Chai Point' \gset

-- A cursor 100,000 rows deep into Chai Point's list (page 5,001 at limit 20).
SELECT created_at AS cursor_created_at, id AS cursor_id
FROM payments WHERE merchant_id = :merchant_id
ORDER BY created_at DESC, id DESC OFFSET 100000 LIMIT 1 \gset

\echo '=================== WITHOUT payments_merchant_created_idx ==================='
BEGIN;
DROP INDEX payments_merchant_created_idx;

\echo '--- first page'
SELECT count(*) FROM (SELECT id FROM payments WHERE merchant_id = :merchant_id
    ORDER BY created_at DESC, id DESC LIMIT 21) warm;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id, status, amount, currency, refunded_amount, merchant_order_id, decline_reason, created_at, updated_at
FROM payments
WHERE merchant_id = :merchant_id
ORDER BY created_at DESC, id DESC LIMIT 21;

\echo '--- page 5,001 (cursor 100,000 rows deep)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id, status, amount, currency, refunded_amount, merchant_order_id, decline_reason, created_at, updated_at
FROM payments
WHERE merchant_id = :merchant_id AND (created_at, id) < (:'cursor_created_at', :'cursor_id')
ORDER BY created_at DESC, id DESC LIMIT 21;

\echo '--- status=FAILED, first page'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id, status, amount, currency, refunded_amount, merchant_order_id, decline_reason, created_at, updated_at
FROM payments
WHERE merchant_id = :merchant_id AND status = 'FAILED'
ORDER BY created_at DESC, id DESC LIMIT 21;

ROLLBACK;

\echo '==================== WITH payments_merchant_created_idx ===================='

\echo '--- first page'
SELECT count(*) FROM (SELECT id FROM payments WHERE merchant_id = :merchant_id
    ORDER BY created_at DESC, id DESC LIMIT 21) warm;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id, status, amount, currency, refunded_amount, merchant_order_id, decline_reason, created_at, updated_at
FROM payments
WHERE merchant_id = :merchant_id
ORDER BY created_at DESC, id DESC LIMIT 21;

\echo '--- page 5,001 (cursor 100,000 rows deep)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id, status, amount, currency, refunded_amount, merchant_order_id, decline_reason, created_at, updated_at
FROM payments
WHERE merchant_id = :merchant_id AND (created_at, id) < (:'cursor_created_at', :'cursor_id')
ORDER BY created_at DESC, id DESC LIMIT 21;

\echo '--- same page with OFFSET instead of a cursor (what keyset pagination avoids)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id, status, amount, currency, refunded_amount, merchant_order_id, decline_reason, created_at, updated_at
FROM payments
WHERE merchant_id = :merchant_id
ORDER BY created_at DESC, id DESC OFFSET 100001 LIMIT 21;

\echo '--- status=FAILED, first page'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id, status, amount, currency, refunded_amount, merchant_order_id, decline_reason, created_at, updated_at
FROM payments
WHERE merchant_id = :merchant_id AND status = 'FAILED'
ORDER BY created_at DESC, id DESC LIMIT 21;

\echo '=========================== other hot-path queries ==========================='

\echo '--- reconciler: unresolved payments (partial index)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id FROM payments
WHERE status IN ('PENDING', 'UNKNOWN') AND updated_at <= now() - interval '5 seconds'
ORDER BY updated_at LIMIT 100;

\echo '--- payment by (merchant, idempotency key)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM payments WHERE merchant_id = :merchant_id AND idempotency_key = 'seed-500000';

\echo '--- GET /v1/payments/{id} (primary key, scoped to merchant)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM payments WHERE id = :'cursor_id' AND merchant_id = :merchant_id;

\echo '--- API key authentication'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM merchants WHERE api_key_hash = '6474ca4bfd9754170b61d78c24e94c24af28d55e3ae22acd6d1e597ee8f8852d';

\echo '--- idempotency key lookup'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT request_hash, status FROM idempotency_keys WHERE merchant_id = :merchant_id AND key = 'demo-1';
