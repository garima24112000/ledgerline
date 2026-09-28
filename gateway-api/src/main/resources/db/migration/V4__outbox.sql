-- V4: transactional outbox. A row is written in the same transaction as the payment state change it
-- describes; OutboxRelay later publishes it to Kafka and sets published_at.

CREATE TABLE outbox_events (
    id           UUID        PRIMARY KEY, -- also the event id merchants dedupe on (X-Ledgerline-Event-Id)
    merchant_id  BIGINT      NOT NULL REFERENCES merchants (id), -- Kafka key, so the relay needn't parse JSON
    aggregate_id UUID        NOT NULL, -- the payment or refund the event is about
    event_type   TEXT        NOT NULL CHECK (event_type IN ('payment.captured', 'payment.failed', 'refund.created')),
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts     INT         NOT NULL DEFAULT 0 CHECK (attempts >= 0)
);

-- Relay: only unpublished rows, oldest first. Partial, so it stays tiny however many events were published.
CREATE INDEX outbox_events_unpublished_idx ON outbox_events (created_at) WHERE published_at IS NULL;

-- An event is immutable once written. The relay may only set published_at (once) and count attempts.
CREATE FUNCTION outbox_check_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.merchant_id <> OLD.merchant_id OR NEW.aggregate_id <> OLD.aggregate_id
       OR NEW.event_type <> OLD.event_type OR NEW.payload <> OLD.payload OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'outbox event %: only published_at and attempts can change', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.published_at IS NOT NULL AND NEW.published_at IS DISTINCT FROM OLD.published_at THEN
        RAISE EXCEPTION 'outbox event % is already published', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER outbox_events_update_check
    BEFORE UPDATE ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION outbox_check_update();
