-- Seeds 1,000,000 payments for the three demo merchants, for measuring the list query.
--
--   docker compose -f infra/docker-compose.yml exec -T postgres \
--     psql -U ledgerline -d ledgerline -f - < infra/scripts/seed-payments.sql
--
-- Payments only, no ledger entries: the list query never reads the ledger, and 1M captures would
-- spend minutes in the commit-time balance triggers (see DESIGN.md, "Trigger cost").
-- Split 60/30/10 between the merchants, spread over the last 365 days, 88% CAPTURED / 12% FAILED.
-- Remove again with:  DELETE FROM payments WHERE idempotency_key LIKE 'seed-%';

\timing on

INSERT INTO payments (id, merchant_id, amount, currency, card_token, merchant_order_id, status,
                      idempotency_key, created_at, updated_at)
SELECT gen_random_uuid(),
       CASE WHEN r.pick < 0.6 THEN chai.id WHEN r.pick < 0.9 THEN book.id ELSE pixel.id END,
       100 + (random() * 500000)::bigint,
       'INR',
       'tok_seed',
       'seed-order-' || r.n,
       CASE WHEN random() < 0.88 THEN 'CAPTURED' ELSE 'FAILED' END,
       'seed-' || r.n,
       r.created_at,
       r.created_at
FROM (SELECT n, random() AS pick, now() - random() * interval '365 days' AS created_at
      FROM generate_series(1, 1000000) AS n) AS r
CROSS JOIN (SELECT id FROM merchants WHERE name = 'Chai Point') AS chai
CROSS JOIN (SELECT id FROM merchants WHERE name = 'Book Nook') AS book
CROSS JOIN (SELECT id FROM merchants WHERE name = 'Pixel Prints') AS pixel;

VACUUM ANALYZE payments;

SELECT m.name, COUNT(*) AS payments
FROM payments p JOIN merchants m ON m.id = p.merchant_id
GROUP BY m.name ORDER BY payments DESC;
