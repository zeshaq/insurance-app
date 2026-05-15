-- Slice 6 (feature 2): Policy bind.
-- A Policy is the durable artifact created when a customer accepts a Quote.
-- The UNIQUE constraint on quote_id is the second line of defense behind
-- Redlock: even if two concurrent binds slip past the distributed lock, the
-- DB still refuses to record two policies for the same quote.
CREATE TABLE policy (
    policy_number  VARCHAR(20) PRIMARY KEY,
    quote_id       BIGINT      NOT NULL UNIQUE REFERENCES quote(id),
    status         VARCHAR(20) NOT NULL DEFAULT 'BOUND',
    bound_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_policy_status ON policy (status);
