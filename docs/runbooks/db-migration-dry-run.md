# DB migration dry-run procedure

This runbook is the operator checklist for testing a new Flyway migration
against a prod-sized clone before promoting it. Pairs with the per-migration
rollback docs at `docs/migrations/Vn-rollback.md`.

The principle: production data has shapes the migration author cannot
predict from local fixtures. A migration that lints clean and passes the
unit tests can still take 6 hours on prod and lock the `policy` table.
The dry-run answers two operator questions before a maintenance window
gets booked:

1. Will it actually finish in the window?
2. After it finishes, do the row counts and the EXPLAIN plans still
   look right?

---

## 1. Snapshot prod into a clone

The clone runs against rootless podman on the project VM so we don't
hold a long-running transaction on production while inspecting the
migration's behaviour.

```bash
# On the prod Postgres host (or a replica), dump the schema + data.
# --no-owner / --no-privileges keep restores portable to the clone.
pg_dump \
    --host=prod-db.internal \
    --username=insurance \
    --dbname=insurance \
    --no-owner --no-privileges \
    --file=/tmp/insurance-prod-snapshot.sql

# Sanity check: dump should be non-empty, contain CREATE TABLE quote,
# and the row-count COPY headers.
ls -lh /tmp/insurance-prod-snapshot.sql
head -20 /tmp/insurance-prod-snapshot.sql
grep -c '^COPY ' /tmp/insurance-prod-snapshot.sql  # one COPY per table
```

On the VM, spin a scratch Postgres in podman with the same major
version production uses (16.x):

```bash
podman run -d --name pg-dryrun \
    --network insurance-net \
    -e POSTGRES_DB=insurance \
    -e POSTGRES_USER=insurance \
    -e POSTGRES_PASSWORD=insurance \
    -p 15432:5432 \
    docker.io/library/postgres:16-alpine

# Wait until ready.
until podman exec pg-dryrun pg_isready -U insurance; do sleep 1; done

# Restore the snapshot.
podman exec -i pg-dryrun psql -U insurance -d insurance < /tmp/insurance-prod-snapshot.sql

# Verify the restore landed.
podman exec pg-dryrun psql -U insurance -d insurance -c "\dt"
podman exec pg-dryrun psql -U insurance -d insurance -c \
    "SELECT schemaname, relname, n_live_tup FROM pg_stat_user_tables ORDER BY n_live_tup DESC;"
```

Capture the pre-migration baseline. The post-migration diff is against
this:

```bash
podman exec pg-dryrun pg_dump -s -U insurance insurance \
    > /tmp/dryrun-schema-before.sql

podman exec pg-dryrun psql -U insurance -d insurance -t -A -F$'\t' -c \
    "SELECT relname, n_live_tup FROM pg_stat_user_tables ORDER BY relname" \
    > /tmp/dryrun-counts-before.tsv
```

## 2. Apply the new migration

Point a local Flyway run at the clone (port 15432), with the candidate
migration in `src/main/resources/db/migration/`:

```bash
cd /home/ze/insurance-app

# Time the migrate run — this is the number the maintenance window must
# accommodate. `time` reports wall clock; that's what the operator cares
# about, not CPU time.
time mvn -B -ntp flyway:migrate \
    -Dflyway.url=jdbc:postgresql://localhost:15432/insurance \
    -Dflyway.user=insurance \
    -Dflyway.password=insurance \
    -Dflyway.locations=classpath:db/migration

# Confirm the new version is at the head.
podman exec pg-dryrun psql -U insurance -d insurance -c \
    "SELECT version, description, success, installed_on \
     FROM flyway_schema_history ORDER BY installed_rank"
```

If the candidate migration is large enough that the `time` output is
not the whole story (locks held, autovacuum starved), also capture
`pg_stat_activity` during the migrate from a second shell.

## 3. Verify the post state

### a. Schema diff

```bash
podman exec pg-dryrun pg_dump -s -U insurance insurance \
    > /tmp/dryrun-schema-after.sql

diff -u /tmp/dryrun-schema-before.sql /tmp/dryrun-schema-after.sql \
    > /tmp/dryrun-schema-diff.patch

# The diff MUST match what the migration says it changes. A surprise
# (column rename you didn't expect, lost index, default value drift)
# fails the sign-off.
less /tmp/dryrun-schema-diff.patch
```

### b. Row counts

```bash
podman exec pg-dryrun psql -U insurance -d insurance -t -A -F$'\t' -c \
    "SELECT relname, n_live_tup FROM pg_stat_user_tables ORDER BY relname" \
    > /tmp/dryrun-counts-after.tsv

diff -u /tmp/dryrun-counts-before.tsv /tmp/dryrun-counts-after.tsv
```

Counts should be **identical** unless the migration backfills, adds a
column with a synthesized value, or drops rows by design. Any
unexplained delta is a sign-off blocker.

### c. Query-plan check on the hot paths

The five queries the app hits in steady state. Run each with `EXPLAIN
(ANALYZE, BUFFERS)` against the clone, then again against the prod
replica (read-only) and confirm the plan shape is the same. A migration
that "works" but flips a query from Index Scan to Seq Scan brings the
app down at the next traffic peak.

```sql
-- 1. Quote lookup by id (cache miss path)
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM quote WHERE id = 12345;

-- 2. Policies-recent list (PolicyResource.list)
EXPLAIN (ANALYZE, BUFFERS)
    SELECT * FROM policy ORDER BY bound_at DESC LIMIT 50;

-- 3. Payment idempotency lookup (PaymentService.process replay path)
EXPLAIN (ANALYZE, BUFFERS)
    SELECT * FROM payment WHERE idempotency_key = 'some-key';

-- 4. Claims by policy (claim-list endpoints)
EXPLAIN (ANALYZE, BUFFERS)
    SELECT * FROM claim WHERE policy_number = 'POL-ABCDEF12';

-- 5. Recent report runs (dashboards)
EXPLAIN (ANALYZE, BUFFERS)
    SELECT * FROM report_run ORDER BY created_at DESC LIMIT 10;
```

Compare plan shape (Index Scan vs Seq Scan, join order, estimated rows)
and total wall-clock time. Significant regressions block sign-off.

### d. App smoke test against the clone

Point a Liberty instance at the cloned DB and hit the read endpoints:

```bash
# Set the test profile to use the dry-run DB instead of compose's prod DB.
podman run --rm --network insurance-net \
    -e DB_HOST=pg-dryrun -e DB_PORT=5432 \
    insurance-app:dryrun \
    /bin/sh -c "wait-for-port pg-dryrun:5432 && \
                curl -sf http://localhost:9080/api/policies?limit=1 && \
                curl -sf http://localhost:9080/api/health"
```

Both calls must return 200. If a column rename / drop is involved and the
WAR predates the migration, this is the test that catches the mismatch
BEFORE prod deploys.

## 4. Sign-off criteria

The migration is cleared for promotion when ALL of:

| # | Criterion | Sign-off owner |
|---|---|---|
| 1 | `flyway:migrate` completed against the clone with `success=true`. | DBA |
| 2 | Wall-clock time fits in the booked maintenance window with at least 25% headroom. | Release manager |
| 3 | `dryrun-schema-diff.patch` matches the migration's documented changes — no surprise drops, renames, or index losses. | Migration author |
| 4 | `dryrun-counts-after.tsv` shows zero unexplained row deltas. | Migration author + DBA |
| 5 | All 5 hot-path EXPLAIN plans show no regression in plan shape and ≤2x increase in total time. | DBA |
| 6 | App smoke test against the clone returns 200 on `/policies` and `/health`. | App team |
| 7 | The matching rollback doc (`docs/migrations/Vn-rollback.md`) exists and has been reviewed. | Migration author |
| 8 | Backup of prod DB has been taken and verified restorable within the last 24 h. | DBA |

Any failing criterion is a STOP. The migration goes back to authoring,
not "we'll fix it after deploy."

## 5. Tear-down

```bash
podman stop pg-dryrun && podman rm pg-dryrun
rm /tmp/insurance-prod-snapshot.sql \
   /tmp/dryrun-schema-before.sql /tmp/dryrun-schema-after.sql \
   /tmp/dryrun-counts-before.tsv /tmp/dryrun-counts-after.tsv \
   /tmp/dryrun-schema-diff.patch
```

Snapshot artefacts may contain prod data — the tear-down is not
optional. If they need to be retained for audit, move them to the
encrypted backup bucket, not `/tmp`.
