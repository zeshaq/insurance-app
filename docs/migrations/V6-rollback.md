# V6__claim_partner.sql — Rollback

## Forward migration

Extends `claim` with three cross-carrier (partner-lookup) columns and
one index:

```sql
ALTER TABLE claim
    ADD COLUMN other_party_vin     VARCHAR(17),
    ADD COLUMN other_party_policy  VARCHAR(50),
    ADD COLUMN other_party_carrier VARCHAR(50);

CREATE INDEX idx_claim_other_party_vin ON claim (other_party_vin);
```

This is an additive ALTER, no data backfill. The columns are nullable.

## Reverse SQL (run manually)

```sql
BEGIN;

DROP INDEX IF EXISTS idx_claim_other_party_vin;

ALTER TABLE claim
    DROP COLUMN IF EXISTS other_party_carrier,
    DROP COLUMN IF EXISTS other_party_policy,
    DROP COLUMN IF EXISTS other_party_vin;

DELETE FROM flyway_schema_history WHERE version = '6';

COMMIT;
```

## Risk + caveats

- **Partner data is lost.** Any rows that had partner_carrier filled in
  (claims where the other driver's vehicle was covered by a partner
  carrier) lose that information. Take a `pg_dump
  --column-inserts -t claim` if the partner lookup history matters for
  the regulator.
- **JPA mapping mismatch window.** The `@Entity` `Claim` class has
  fields for `otherPartyVin`, `otherPartyPolicy`, `otherPartyCarrier`.
  After this rollback the columns no longer exist but the entity mapping
  still references them. EclipseLink will fail with `column ... does not
  exist` on any read/write to `claim`. Either:
    1. Deploy the application BEFORE this rollback (a version that
       removed the columns from `Claim.java`); or
    2. Accept brief application downtime while rollback runs, then
       deploy a fixed app.
- **In-flight claim filings.** ClaimService's `file()` passes
  `otherPartyVin` to `savePartner` which writes the column. Rolling back
  V6 mid-flight throws a `PSQLException` from JPA. Pause claim filing
  before the rollback, or accept the failed-claim window.
- **Safe vs V5.** No FK changes; V5 (which created the table) is
  unaffected. V6 rollback is independent of V7.

## Estimated time on prod-scale data

- Empty / small: &lt;1s.
- 1M claims: ~5-15s. Postgres's `DROP COLUMN` is a metadata operation
  (sets the column to dropped in `pg_attribute`), so it's near-instant
  even on large tables. The actual reclamation of disk space happens at
  the next `VACUUM FULL` or table rewrite.
- 10M+: still seconds for the ALTER; the index drop adds a few more.
