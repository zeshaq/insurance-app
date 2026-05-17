# V1__init.sql — Rollback

## Forward migration

Creates the `quote` table — the root entity of the demo (no FK references
into it from anything that exists at V1 time). Adds two indexes:

- `idx_quote_vehicle_vin` — looking up quotes for a VIN.
- `idx_quote_status` — listing CALCULATED quotes for a dashboard.

## Reverse SQL (run manually)

Flyway 10 OSS does not run undo scripts; an operator with `psql` access
runs these statements by hand after a verified backup:

```sql
BEGIN;

-- Indexes go with the table on a DROP CASCADE, but listing them is
-- documentation for what V1 created. If a later migration repurposed an
-- index name, drop it explicitly before the DROP TABLE.
DROP INDEX IF EXISTS idx_quote_status;
DROP INDEX IF EXISTS idx_quote_vehicle_vin;

-- CASCADE is required iff a later migration FK'd into quote(id). V2 added
-- policy.quote_id REFERENCES quote(id), so rolling back V1 ALONE is not
-- supported — you must roll back V7..V2 first, then run this. The
-- CASCADE here is a belt; the dependency-ordered rollback is the suspenders.
DROP TABLE IF EXISTS quote CASCADE;

-- Flyway schema-history bookkeeping: remove the V1 row so re-running
-- `flyway migrate` from a clean state replays it.
DELETE FROM flyway_schema_history WHERE version = '1';

COMMIT;
```

## Risk + caveats

- **Data loss is total.** Every quote row evaporates. Take a `pg_dump
  -t quote` BEFORE running the DROP.
- **Order matters.** V2 (policy) FKs into `quote(id)`. Rolling back V1
  before V2 leaves orphaned policies. Follow the rollback chain
  V7 -> V6 -> V5 -> V4 -> V3 -> V2 -> V1.
- **CASCADE is destructive.** It silently drops dependent objects you
  may not have inventoried. The `pg_dump -s` before the rollback is the
  authoritative record of what's about to go.
- **schema_history deletion is not optional.** Without it, Flyway sees
  V1 as "applied" and refuses to re-apply on the next `migrate`.

## Estimated time on prod-scale data

- Schema-only rollback (table is empty): &lt;1s.
- With 1M quote rows: ~5s for the DROP TABLE (no row-level cleanup —
  Postgres deallocates the heap file in O(table-count) time, not O(rows)).
- With FK CASCADE pulling in policies/payments/claims: depends on
  downstream row counts; typically &lt;30s at 1M-row scale.
