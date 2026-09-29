-- EXPLAIN ANALYZE for the four ledger-audit Lambda queries (ledger-audit-lambda/.../LedgerAuditor.java).
-- Run after seed-payments.sql (1M payments):
--
--   docker compose -f infra/docker-compose.yml exec -T postgres \
--     psql -U ledgerline -d ledgerline -f - < infra/scripts/explain-audit.sql
--
-- The UNKNOWN query needs some UNKNOWN payments to be interesting: 2,000 are inserted inside a
-- transaction (half unresolved for 2 hours, half for 10 minutes) and rolled back at the end.

BEGIN;

INSERT INTO payments (id, merchant_id, amount, currency, card_token, merchant_order_id, status,
                      idempotency_key, created_at, updated_at)
SELECT gen_random_uuid(), m.id, 1000, 'INR', 'tok_explain', 'explain-' || n, 'UNKNOWN', 'explain-' || n,
       now() - CASE WHEN n % 2 = 0 THEN interval '2 hours' ELSE interval '10 minutes' END,
       now() - CASE WHEN n % 2 = 0 THEN interval '2 hours' ELSE interval '10 minutes' END
FROM generate_series(1, 2000) AS n
CROSS JOIN (SELECT id FROM merchants WHERE name = 'Chai Point') AS m;
ANALYZE payments;

\echo '--- 1. entries_balanced (full scan by design)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id, COUNT(*) OVER () AS total
FROM (
    SELECT e.id
    FROM journal_entries e
    LEFT JOIN postings p ON p.journal_entry_id = e.id
    GROUP BY e.id
    HAVING COUNT(p.id) = 0
        OR SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount ELSE -p.amount END) <> 0
) unbalanced
ORDER BY id
LIMIT 100;

\echo '--- 2. balances_match_postings (full scan by design)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT a.id, COUNT(*) OVER () AS total
FROM accounts a
LEFT JOIN (
    SELECT account_id,
           SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) AS net
    FROM postings
    GROUP BY account_id
) p ON p.account_id = a.id
WHERE a.balance <> COALESCE(p.net, 0)
ORDER BY a.id
LIMIT 100;

\echo '--- 3. global_net_zero (full scan of accounts by design)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT currency, SUM(balance) AS net
FROM accounts
GROUP BY currency
HAVING SUM(balance) <> 0
ORDER BY currency;

\echo '--- 4. no_stale_unknown_payments (payments_unresolved_idx)'
PREPARE stale_unknown(int, int) AS
SELECT id, COUNT(*) OVER () AS total
FROM payments
WHERE status = 'UNKNOWN'
  AND updated_at < now() - make_interval(mins => $1)
ORDER BY updated_at
LIMIT $2;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) EXECUTE stale_unknown(60, 100);

ROLLBACK;
