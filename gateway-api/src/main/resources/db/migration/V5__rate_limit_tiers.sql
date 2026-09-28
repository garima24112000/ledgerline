-- V5: rate-limit tiers become FREE and PRO (limits per tier live in application.yml).
-- STANDARD and PREMIUM merchants move to PRO, so no existing merchant gets a lower limit.
-- The old CHECK has to go first: it doesn't allow 'PRO'. Flyway runs this in one transaction,
-- so no other session ever sees the table without a CHECK.

ALTER TABLE merchants DROP CONSTRAINT merchants_rate_limit_tier_check;

UPDATE merchants SET rate_limit_tier = 'PRO' WHERE rate_limit_tier IN ('STANDARD', 'PREMIUM');

ALTER TABLE merchants ADD CONSTRAINT merchants_rate_limit_tier_check CHECK (rate_limit_tier IN ('FREE', 'PRO'));
ALTER TABLE merchants ALTER COLUMN rate_limit_tier SET DEFAULT 'FREE';
