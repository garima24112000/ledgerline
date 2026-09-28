-- EXPLAIN ANALYZE for OutboxRelay's batch query and OutboxMetrics' lag query, with and without
-- outbox_events_unpublished_idx.
-- Self-contained and leaves nothing behind: seeds 1M published + 1,000 unpublished events inside a
-- transaction and rolls it back at the end. Needs the V4 migration (start gateway-api once).
--
--   docker compose -f infra/docker-compose.yml exec -T postgres \
--     psql -U ledgerline -d ledgerline -f - < infra/scripts/explain-outbox.sql

BEGIN;

SELECT id AS merchant_id FROM merchants WHERE name = 'Chai Point' \gset

-- A healthy outbox: almost everything published, a small backlog waiting.
INSERT INTO outbox_events (id, merchant_id, aggregate_id, event_type, payload, created_at, published_at)
SELECT gen_random_uuid(), :merchant_id, gen_random_uuid(), 'payment.captured', '{"amount": 100}',
       now() - make_interval(secs => g), now() - make_interval(secs => g) + interval '1 second'
FROM generate_series(1, 1000000) g;

INSERT INTO outbox_events (id, merchant_id, aggregate_id, event_type, payload, created_at)
SELECT gen_random_uuid(), :merchant_id, gen_random_uuid(), 'payment.captured', '{"amount": 100}',
       now() - make_interval(secs => g)
FROM generate_series(1, 1000) g;

ANALYZE outbox_events;

\echo '=================== WITH outbox_events_unpublished_idx ==================='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id, merchant_id, aggregate_id, event_type, payload::text AS payload, created_at
FROM outbox_events
WHERE published_at IS NULL
ORDER BY created_at
LIMIT 100
FOR UPDATE SKIP LOCKED;

\echo '--- lag query (OutboxMetrics) ---'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) AS unpublished,
       COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0) AS oldest_age_seconds
FROM outbox_events
WHERE published_at IS NULL;

\echo '=================== WITHOUT it ==================='
DROP INDEX outbox_events_unpublished_idx;
-- Warm-up, so both runs are measured with a warm cache.
SELECT count(*) FROM (SELECT id FROM outbox_events WHERE published_at IS NULL ORDER BY created_at LIMIT 100) warm;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id, merchant_id, aggregate_id, event_type, payload::text AS payload, created_at
FROM outbox_events
WHERE published_at IS NULL
ORDER BY created_at
LIMIT 100
FOR UPDATE SKIP LOCKED;

\echo '--- lag query (OutboxMetrics) ---'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) AS unpublished,
       COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0) AS oldest_age_seconds
FROM outbox_events
WHERE published_at IS NULL;


ROLLBACK;
