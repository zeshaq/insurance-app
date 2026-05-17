# V5__claim.sql — Rollback

## Forward migration

Creates the `claim` table for filed claims (slice 9):

- `policy_number VARCHAR(20) NOT NULL REFERENCES policy(policy_number)` —
  every claim belongs to a policy.
- `photo_key VARCHAR(200)` — MinIO object key inside the `claims`
  bucket. Binary content never lives in Postgres.
- `photo_content_type VARCHAR(100)` — captured at upload time.
- `ocr_text TEXT`, `ocr_confidence NUMERIC(4,3)` — populated from MI's
  OCR mediator.
- `status` ∈ { `FILED`, `UNDER_REVIEW`, `APPROVED`, `DENIED` }.
- Two indexes: by policy_number, by status.

## Reverse SQL (run manually)

```sql
BEGIN;

DROP INDEX IF EXISTS idx_claim_status;
DROP INDEX IF EXISTS idx_claim_policy_number;

-- V6 added three columns on top of this table; if V6 has not been rolled
-- back yet, the DROP still works (columns go with the table), but the
-- V6 rollback step (DELETE flyway_schema_history WHERE version='6') must
-- be done in conjunction. Recommended sequence:
--   1. Run V6 rollback first (drops added columns + history row).
--   2. Then run this V5 rollback.
DROP TABLE IF EXISTS claim CASCADE;

DELETE FROM flyway_schema_history WHERE version = '5';

COMMIT;
```

## Risk + caveats

- **MinIO objects ARE NOT cleaned up.** Each claim row has a `photo_key`
  referencing an object in MinIO's `claims` bucket. Dropping the claim
  rows orphans those MinIO objects — they continue to consume storage
  but no row points to them. Either:
    1. Before the SQL DROP, extract photo_keys: `SELECT photo_key FROM
       claim WHERE photo_key IS NOT NULL` -> `mc rm` each one; or
    2. Accept the orphans and run a separate MinIO janitor pass.
- **OCR + partner enrichment data lost.** The OCR text and partner-carrier
  fields (from V6) are demo-only signal, but if a downstream report
  consumed them they need to be archived too.
- **Order with V6.** V6 is a same-table ALTER that adds columns; rolling
  back V5 before V6 leaves a stale V6 history entry pointing to a column
  set that no longer exists. Roll back V6 first, then V5.
- **Other-party data referenced by claim.** No FK to another table; safe
  to drop independently of any V6/V7 follow-ups other than the same-table
  ALTER chain above.

## Estimated time on prod-scale data

- Empty / small: &lt;1s.
- 100k claims with averaged 4 KB OCR text: ~3s table drop, ~30s for
  archival `pg_dump`. Add the MinIO object cleanup time separately
  (depends on bucket size + `mc rm` parallelism).
- 1M+ claims: dump dominates; plan for hours on cold storage I/O.
