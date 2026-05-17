# V3__payment.sql — Rollback

## Forward migration

Creates the `payment` table for charge attempts:

- `idempotency_key VARCHAR(64) UNIQUE` — the DB backstop behind the
  Redis idempotency cache. Even on a Redis flush, the same key can't
  produce two payments.
- `external_ref VARCHAR(64)` — payment-gateway transaction id.
- `status` ∈ { `PENDING`, `SUCCEEDED`, `FAILED` }.
- Indexes on `policy_number` and `status` for "payments for this policy"
  and "all FAILED payments today" queries.

## Reverse SQL (run manually)

```sql
BEGIN;

DROP INDEX IF EXISTS idx_payment_status;
DROP INDEX IF EXISTS idx_payment_policy_number;

-- No downstream table FKs into payment, so plain DROP suffices.
-- The UNIQUE(idempotency_key) constraint goes with the table.
DROP TABLE IF EXISTS payment;

DELETE FROM flyway_schema_history WHERE version = '3';

COMMIT;
```

## Risk + caveats

- **Financial data.** Payment rows are the ONLY durable record that a
  charge attempt happened. Reverting V3 deletes evidence of every
  attempted charge — successful, failed, and pending. Compliance /
  finance MUST sign off and a `pg_dump -t payment` MUST be archived
  before this runs.
- **Idempotency key uniqueness disappears.** A client that retries with
  a previously-used key after V3 is rolled back would be double-charged.
  Pause payment traffic before rolling back, and do not resume until
  V3 is re-applied.
- **No FK fan-out.** Unlike V1/V2, nothing depends on payment, so the
  DROP is local — no CASCADE needed.
- **External system drift.** The mocked payment gateway (or a real one)
  has its own ledger of `external_ref` values. After this rollback,
  reconciliation against the gateway is impossible because the linking
  row is gone. Archive a join of `payment` with the gateway statement
  if reconciliation matters.

## Estimated time on prod-scale data

- Empty / small (&lt;10k rows): &lt;1s.
- 1M payments: ~10s for the table drop. Index drops are near-instant
  because Postgres removes the index metadata, not row-by-row.
- 10M payments: ~30s; mostly autovacuum cleanup afterwards.
