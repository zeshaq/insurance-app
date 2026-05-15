-- Slice 7 (feature 3): Payment.
-- idempotency_key UNIQUE is the DB-level backstop behind the Redis idempotency
-- cache; even if the cache evicts or a duplicate slips past, the DB still
-- refuses a second row for the same key. external_ref is the reference the
-- (mocked) payment gateway returns on a successful charge — what we'd reconcile
-- against in a real audit.
CREATE TABLE payment (
    id                BIGSERIAL PRIMARY KEY,
    policy_number     VARCHAR(20) NOT NULL REFERENCES policy(policy_number),
    amount            NUMERIC(12,2) NOT NULL,
    currency          VARCHAR(3) NOT NULL DEFAULT 'USD',
    status            VARCHAR(20) NOT NULL,                -- PENDING / SUCCEEDED / FAILED
    idempotency_key   VARCHAR(64) NOT NULL UNIQUE,
    external_ref      VARCHAR(64),
    failure_reason    VARCHAR(255),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at      TIMESTAMPTZ
);

CREATE INDEX idx_payment_policy_number ON payment (policy_number);
CREATE INDEX idx_payment_status        ON payment (status);
