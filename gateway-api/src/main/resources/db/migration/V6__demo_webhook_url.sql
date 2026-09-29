-- V6: the demo merchants' webhook URL comes from configuration instead of being hard-coded.
-- V3 seeded http://localhost:8083/webhooks, which is right when the apps run on the host, but inside
-- a Kubernetes pod "localhost" is the dispatcher itself. The URL is the Flyway placeholder
-- demo_webhook_url (spring.flyway.placeholders.demo_webhook_url, from DEMO_WEBHOOK_URL).
--
-- One-time seed configuration: Flyway runs this once per database and records it in
-- flyway_schema_history. Changing DEMO_WEBHOOK_URL later does NOT touch an already-migrated
-- database; only a fresh database picks the value up. merchants is not a ledger table, so UPDATE is allowed.

UPDATE merchants
SET webhook_url = '${demo_webhook_url}'
WHERE webhook_url = 'http://localhost:8083/webhooks';
