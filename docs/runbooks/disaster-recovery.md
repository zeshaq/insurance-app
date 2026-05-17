# Disaster recovery runbook

Operator procedures for backing up and restoring the insurance-app stack. The two scripts (`tests/backup/snapshot-all.sh`, `tests/backup/restore-into-scratch.sh`) implement the mechanics; this document defines the targets, the schedule, and the human decision points.

## Scope

Three durable data stores carry state that cannot be re-derived:

| Store | What's in it | Source of truth? |
|---|---|---|
| Postgres (`postgres`) | `quote`, `policy`, `claim`, `payment`, `notification`, `report_run` rows + flyway history | Yes — the system of record |
| MinIO (`minio`) | Claim attachments under `claims/` | Yes — attachments are not stored anywhere else |
| Kafka (`kafka`) | `audit-events` (compacted), `policy-events` (compacted) | **Compacted topics only.** All other topics are derived from Postgres + the producers and can be replayed |

WSO2 IS, WSO2 APIM, OpenSearch indices, Redis cache, ClickHouse (SigNoz traces/metrics), Apicurio registry: **out of scope**. They are either re-bootstrapped from config or rebuilt from upstream sources (Debezium re-snapshots Postgres into OpenSearch; the Redis cache warms back up on first read).

## RTO / RPO targets

Recovery time objective (RTO) = max acceptable time the data store is unavailable. Recovery point objective (RPO) = max acceptable data loss measured in real time.

| Store | RTO | RPO | Rationale |
|---|---|---|---|
| **Postgres** | **1 hour** | **5 minutes** | The money chain. `pg_dump -Fc` of 130 MB takes ~3s; restore-into-scratch consistently completes in ~25s with the full verification block. The bottleneck is operator decision time (deciding to restore, confirming target, validating result), hence the 1h budget. RPO of 5 min implies pg_dump every 5 min for the strictest workloads; for this teaching lab we accept hourly. |
| **MinIO** | **4 hours** | **1 hour** | Claim attachments. Lower priority — claim *records* are in Postgres; only the attached images are at risk. `mc mirror` is incremental, so a 1-hour cadence is cheap. |
| **Kafka — compacted topics** (`audit-events`, `policy-events`) | **1 hour** | **0** (zero loss tolerance) | Compaction means latest-key-wins is the contract; we replay every record from the snapshot back into the scratch broker and verify offset counts >= snapshot line count. |
| **Kafka — retention topics** (`quote-events`, `claim-events`, `payment-events`, `notification-requested`) | **1 hour** | **1 hour** | These topics are replayed from Postgres + producer redrive, not from snapshot. Anything not yet drained by a consumer at the moment of failure is lost — that's the 1-hour RPO. |

The targets above are not aspirational — the drill on 2026-05-17 measured **34s snapshot + 25s restore** end-to-end on the live VM, well inside both budgets. The 1-hour RTO reserves the rest of the budget for operator response time and post-restore application reconnect.

## Snapshot frequency and rotation

Recommended schedule (matching the RPOs above):

| Job | Cadence | Retention |
|---|---|---|
| `snapshot-all.sh` (full coordinated) | Hourly | 7 daily (one per day, last 7) + 4 weekly + 1 monthly |
| Postgres-only WAL archive (future) | Every 5 min | 24 hours |
| MinIO `mc mirror` (incremental within snapshot dir) | Already done inside `snapshot-all.sh` | n/a |

The script's `KEEP` env var implements simple time-ordered pruning (`KEEP=7` keeps the 7 most recent). For tiered retention (daily/weekly/monthly), wrap the script with `logrotate(8)` or a follow-up systemd timer that hardlinks specific snapshots into `weekly/` and `monthly/` subdirs.

Storage budget: at current data sizes (Postgres 13 MB compressed dump, MinIO 2.4 MB, Kafka 81K records ~12 MB) a single snapshot is ~26 MB. **One year of hourly snapshots with 7-daily / 4-weekly / 1-monthly rotation ≈ 320 MB** — trivially cheap. Plan for 10x growth.

The teaching lab's snapshots land under `/home/ze/insurance-app/backups/` (override with `BACKUP_ROOT=`). In a real deployment that directory **must** be on a separate physical volume from the live data, and the rotation policy must include shipping off-site (S3 / Backblaze / restic to a remote target).

## Operator decision points

When something goes wrong, the question is rarely "do I have a backup" (the timer runs hourly); it's "is restoring the right call?" The decision tree:

1. **Identify what's broken.**
   - Containers stopped? `podman ps -a` + `journalctl --user`.
   - Data corruption? Run `scripts/smoke.sh` — section 5 (Quote round-trip) and section 10 (Policy bind) hit Postgres + Kafka + MinIO.
   - Bad deploy? Roll back the image tag first; that's not a DR scenario.

2. **Pick the snapshot.**
   - Newest snapshot that pre-dates the corruption. Look at `ls -lt /home/ze/insurance-app/backups/`.
   - Verify integrity: `cd <snapshot>; sha256sum -c SHA256SUMS`. The restore script does this for you in step 0.

3. **Restore into scratch first, ALWAYS.**
   - Run `tests/backup/restore-into-scratch.sh <snapshot-dir>`. This builds a parallel `postgres-scratch` / `minio-scratch` / `kafka-scratch` set on the `insurance-net-scratch` network with ports offset by +50000 (55432 / 59000 / 59092).
   - Inspect the scratch data: connect with `psql -h localhost -p 55432 -U insurance` (password `insurance`) and confirm the row you expected is there.
   - This step is your veto: if the snapshot is bad, you find out without having touched live data.

4. **Cut over to scratch — or restore *over* live.**
   - **Option A (preferred):** rename the scratch containers to take over live traffic. Stop the broken live containers, rename scratch → live, update HAProxy / DNS if the addresses changed. Lower risk because you've already verified the scratch state.
   - **Option B (faster but higher risk):** stop the live containers, restore the snapshot into a fresh live container set. This is the textbook restore but with no veto step.
   - **In the teaching lab, always use Option A.** The lab has no traffic SLA and the safety of the veto step is worth the few extra minutes.

5. **Re-bootstrap derived stores after cutover.**
   - Re-run `scripts/register-debezium.sh` to repoint the Debezium connector at the new Postgres. CDC will re-snapshot Postgres into OpenSearch from scratch.
   - Restart `insurance-app` Liberty container so its connection pools repoint and Redis cache invalidates.
   - Wait ~60s, then `scripts/smoke.sh` end-to-end. Investigate any failure before re-opening traffic.

6. **Post-incident.**
   - Write up the incident in `docs/runbooks/incidents/<date>-<slug>.md` (create that directory).
   - Note the actual RTO and RPO you observed. If they exceeded the targets above, file a `qa:ops` issue to address the gap.

## Step-by-step restore procedure

This is the happy-path sequence. Branch points are flagged.

```bash
# 0. Snapshot the broken environment first -- you may want it for forensics.
BACKUP_ROOT=/home/ze/insurance-app/backups/forensics \
  KEEP=0 ~/insurance-app/tests/backup/snapshot-all.sh

# 1. Pick the snapshot you want to restore from.
ls -lt /home/ze/insurance-app/backups/
SNAP=/home/ze/insurance-app/backups/20260518T020000Z   # for example

# 2. Restore into scratch and verify.
~/insurance-app/tests/backup/restore-into-scratch.sh "$SNAP"
# -> watch the [5/5] verifying block. Every check must pass.

# 3. Manual spot-checks against the scratch instances.
PGPASSWORD=insurance psql -h localhost -p 55432 -U insurance insurance \
  -c "select id, status, created_at from policy order by id desc limit 5"
curl -s http://localhost:59000/minio/health/live
podman exec kafka-scratch /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka-scratch:9092 --list

# 4. DECISION POINT: cut over or restore-in-place?
#    If cutting over (Option A):
podman stop postgres minio kafka          # quiesce live
podman rename postgres postgres-bad       # keep for forensics
podman rename minio minio-bad
podman rename kafka kafka-bad
podman network disconnect insurance-net postgres-scratch && \
  podman network connect insurance-net postgres-scratch && \
  podman rename postgres-scratch postgres   # repeat for minio/kafka
# (Network rename for rootless podman requires stop->disconnect->reconnect.)

# 5. Re-bootstrap derived state.
~/insurance-app/scripts/register-debezium.sh
podman restart insurance-app insurance-mi customer-app agent-app

# 6. Smoke-test.
~/insurance-app/scripts/smoke.sh

# 7. Once smoke passes, remove the *-bad containers and free their volumes
#    after a 24-hour holdback (in case you need forensics).
```

## Drill record

| Date | Snapshot size | Snapshot time | Restore time | Total RTO | Verification |
|---|---|---|---|---|---|
| 2026-05-17 | 26 MB (13 MB pg + 2.4 MB MinIO + 12 MB kafka) | 34 s | 25 s | **59 s** end-to-end | 7/7 checks pass (quote 82,622 / policy 81,761 / claim 210 / minio 206 objects / kafka 342 + 81,759 records) |

Drill cadence: **monthly** (first Monday of the month). Capture the timing in this table and open a `qa:ops` issue if either dimension regresses by more than 2x.

## Cross-references

- `tests/backup/snapshot-all.sh` — the backup script.
- `tests/backup/restore-into-scratch.sh` — the verified restore script.
- `docs/performance-budgets.md` — SLO context.
- `docs/slos.md` — availability SLOs that the RTO targets above feed into.
- `compose/compose.yaml` — the live container definitions.
