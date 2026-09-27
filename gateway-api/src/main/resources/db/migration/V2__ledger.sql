-- V2: merchants and the double-entry ledger.
--
-- Sign convention: every account's balance = SUM(credits) - SUM(debits).
-- Because every journal entry balances, the sum of all balances is always 0.
-- Enum-like columns are TEXT + CHECK instead of Postgres ENUM types: easy to extend
-- in a later migration and they map directly to JPA @Enumerated(STRING).

CREATE TABLE merchants (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            TEXT        NOT NULL,
    api_key_hash    TEXT        NOT NULL UNIQUE,
    webhook_url     TEXT        NOT NULL,
    webhook_secret  TEXT        NOT NULL, -- needed in clear to sign webhooks, so it can't be hashed
    rate_limit_tier TEXT        NOT NULL DEFAULT 'STANDARD'
                                CHECK (rate_limit_tier IN ('FREE', 'STANDARD', 'PREMIUM')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_type     TEXT    NOT NULL CHECK (owner_type IN ('PLATFORM', 'MERCHANT')),
    owner_id       BIGINT, -- merchants.id for MERCHANT accounts, NULL for PLATFORM accounts
    type           TEXT    NOT NULL
                           CHECK (type IN ('CUSTOMER_FUNDS', 'MERCHANT_PAYABLE', 'PLATFORM_FEES', 'REFUNDS')),
    currency       TEXT    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    balance        BIGINT  NOT NULL DEFAULT 0, -- cached; the postings are the source of truth
    allow_negative BOOLEAN NOT NULL DEFAULT false,
    version        BIGINT  NOT NULL DEFAULT 0,
    CHECK (allow_negative OR balance >= 0),
    CHECK ((owner_type = 'PLATFORM') = (owner_id IS NULL))
);

-- One account of each type per owner. NULLS NOT DISTINCT (PG15+) makes the platform's
-- NULL owner_id count as a single owner, so there is only one PLATFORM_FEES account.
CREATE UNIQUE INDEX accounts_owner_type_uq
    ON accounts (owner_type, owner_id, type) NULLS NOT DISTINCT;

CREATE TABLE journal_entries (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id  UUID        NOT NULL, -- FK to payments(id) is added when payments exist
    type        TEXT        NOT NULL CHECK (type IN ('CAPTURE', 'REFUND')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    description TEXT
);

CREATE INDEX journal_entries_payment_id_idx ON journal_entries (payment_id);

-- A payment can be captured at most once.
CREATE UNIQUE INDEX journal_entries_one_capture_per_payment_uq
    ON journal_entries (payment_id) WHERE type = 'CAPTURE';

CREATE TABLE postings (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journal_entry_id BIGINT NOT NULL REFERENCES journal_entries (id),
    account_id       BIGINT NOT NULL REFERENCES accounts (id),
    direction        TEXT   NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount           BIGINT NOT NULL CHECK (amount > 0)
);

CREATE INDEX postings_account_id_idx ON postings (account_id);
CREATE INDEX postings_journal_entry_id_idx ON postings (journal_entry_id);

-- ---------------------------------------------------------------------------
-- Invariant: every journal entry has postings, debits = credits, one currency.
-- Checked at COMMIT (DEFERRABLE INITIALLY DEFERRED), because the entry row and its
-- postings are inserted by separate statements and are only complete at the end.
-- ---------------------------------------------------------------------------
CREATE FUNCTION ledger_assert_entry_balanced(entry_id BIGINT) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    posting_count INT;
    debits        NUMERIC; -- SUM(bigint) is NUMERIC, so this can't overflow
    credits       NUMERIC;
    currencies    INT;
BEGIN
    SELECT COUNT(*),
           COALESCE(SUM(p.amount) FILTER (WHERE p.direction = 'DEBIT'), 0),
           COALESCE(SUM(p.amount) FILTER (WHERE p.direction = 'CREDIT'), 0),
           COUNT(DISTINCT a.currency)
      INTO posting_count, debits, credits, currencies
      FROM postings p
      JOIN accounts a ON a.id = p.account_id
     WHERE p.journal_entry_id = entry_id;

    IF posting_count = 0 THEN
        RAISE EXCEPTION 'journal entry % has no postings', entry_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF debits <> credits THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: debits % <> credits %', entry_id, debits, credits
            USING ERRCODE = 'check_violation';
    END IF;
    IF currencies > 1 THEN
        RAISE EXCEPTION 'journal entry % mixes currencies', entry_id
            USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

CREATE FUNCTION ledger_check_entry_on_entry_insert() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM ledger_assert_entry_balanced(NEW.id);
    RETURN NULL;
END;
$$;

CREATE FUNCTION ledger_check_entry_on_posting_insert() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM ledger_assert_entry_balanced(NEW.journal_entry_id);
    RETURN NULL;
END;
$$;

-- Fires for the entry itself, so an entry with zero postings is also rejected.
CREATE CONSTRAINT TRIGGER journal_entries_balanced
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_entry_on_entry_insert();

CREATE CONSTRAINT TRIGGER postings_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_entry_on_posting_insert();

-- ---------------------------------------------------------------------------
-- Invariant: the ledger is append-only. Corrections are new (reversing) entries.
-- ---------------------------------------------------------------------------
CREATE FUNCTION ledger_reject_modification() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_modification();

CREATE TRIGGER journal_entries_no_truncate
    BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_modification();

CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_modification();

CREATE TRIGGER postings_no_truncate
    BEFORE TRUNCATE ON postings
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_modification();

-- ---------------------------------------------------------------------------
-- Platform accounts (one set, INR only for now).
-- CUSTOMER_FUNDS is the clearing account money flows in from: it is debited on
-- capture, so under credits-minus-debits it goes negative by design.
-- ---------------------------------------------------------------------------
INSERT INTO accounts (owner_type, owner_id, type, currency, allow_negative) VALUES
    ('PLATFORM', NULL, 'CUSTOMER_FUNDS', 'INR', true),
    ('PLATFORM', NULL, 'PLATFORM_FEES',  'INR', false),
    ('PLATFORM', NULL, 'REFUNDS',        'INR', true);
