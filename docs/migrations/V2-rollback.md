# V2__policy.sql — Rollback

## Forward migration

Creates the `policy` table:

- Primary key `policy_number VARCHAR(20)` — human-readable identifier
  (`POL-XXXXXXXX`), not an autoincrement.
- `quote_id BIGINT NOT NULL UNIQUE REFERENCES quote(id)` — one policy per
  quote, enforced at the DB level. This UNIQUE is the second line of
  defense behind the Redis Redlock that serializes concurrent binds.
- `status` default `'BOUND'`.
- `idx_policy_status` for the recent-policies query.

## Reverse SQL (run manually)

```sql
BEGIN;

DROP INDEX IF EXISTS idx_policy_status;

-- Cascade pulls in V3 (payment.policy_number FKs into policy), V5 (claim).
-- If you've already rolled V3 and V5 back, this DROP is cheap.
DROP TABLE IF EXISTS policy CASCADE;

DELETE FROM flyway_schema_history WHERE version = '2';

COMMIT;
```

## Risk + caveats

- **The UNIQUE(quote_id) constraint is load-bearing.** It is the
  application's only defence against double-binding under Redis lock
  failures. Anyone restoring policy from a backup MUST verify the
  constraint is back in place before re-enabling traffic.
- **Cascade reach:** payment(policy_number) and claim(policy_number) FK
  into this table. The CASCADE will drop policies AND their dependent
  payments / claims. If you only want to undo V2 and keep payment/claim
  rows for analysis, roll back V3 / V5 / V6 first.
- **policy_number is opaque.** Restoring policy from `pg_dump` brings
  the same `POL-XXXXXXXX` numbers back, so downstream caches keyed on
  policy_number (Redis idempotency:payment:*) remain valid.

## Estimated time on prod-scale data

- Empty table: &lt;1s.
- 100k policies + cascade to 1M payments + 200k claims: ~1-2 minutes,
  dominated by the FK-driven row deletes in payment and claim. If those
  tables were already dropped (in dependency order) the policy DROP is
  ~5s.
