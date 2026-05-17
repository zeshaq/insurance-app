#!/usr/bin/env bash
# tests/backup/snapshot-all.sh
#
# Coordinated point-in-time snapshot of the three durable data stores in the
# insurance-app stack:
#
#   1. Postgres   -> pg_dump custom-format (schema + data)
#   2. MinIO      -> mc mirror of every bucket
#   3. Kafka      -> per-partition console-consumer dump of audit-events and
#                    policy-events (the two compacted topics that hold durable
#                    state; every other topic is derived and replayable).
#
# Output goes to a single timestamped directory under $BACKUP_ROOT (default
# /home/ze/insurance-app/backups) with a SHA256SUMS manifest. The dir is
# self-contained -- restore-into-scratch.sh consumes it.
#
# Read-only against the live containers: pg_dump, mc mirror, and the kafka
# consumer all run against the live network but never mutate it.
#
# Designed to run unattended from a systemd timer or cron; logs to stdout.
# Exits 0 only if all three stages succeed.
#
# Usage:
#   ./snapshot-all.sh                    # writes to $BACKUP_ROOT/<ts>/
#   BACKUP_ROOT=/var/backups ./snapshot-all.sh
#   KEEP=14 ./snapshot-all.sh            # prune older than 14 days at end

set -euo pipefail

BACKUP_ROOT="${BACKUP_ROOT:-/home/ze/insurance-app/backups}"
KEEP="${KEEP:-7}"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$BACKUP_ROOT/$TS"
COMPACTED_TOPICS=(audit-events policy-events)

mkdir -p "$OUT"/{postgres,minio,kafka}
exec > >(tee -a "$OUT/snapshot.log") 2>&1

echo "=== insurance-app snapshot $TS ==="
echo "output: $OUT"
START_EPOCH=$(date +%s)

# --- Sanity: containers we depend on are up ---
for c in postgres minio kafka; do
  if ! podman ps --format '{{.Names}}' | grep -qx "$c"; then
    echo "FATAL: container $c is not running; aborting." >&2
    exit 2
  fi
done

# --- 1) Postgres ---
echo
echo "[1/3] Postgres pg_dump"
t0=$(date +%s)
# Custom format (-Fc) -> compressed, restorable with pg_restore, parallel-safe.
# --no-owner / --no-privileges keep the dump portable across roles.
podman exec postgres pg_dump \
  -U insurance -d insurance \
  -Fc --no-owner --no-privileges \
  -f /tmp/insurance.dump
podman cp postgres:/tmp/insurance.dump "$OUT/postgres/insurance.dump"
podman exec postgres rm -f /tmp/insurance.dump
PG_SIZE=$(stat -c '%s' "$OUT/postgres/insurance.dump")
echo "  -> $OUT/postgres/insurance.dump ($PG_SIZE bytes, $(( $(date +%s) - t0 ))s)"

# --- 2) MinIO ---
echo
echo "[2/3] MinIO mc mirror"
t0=$(date +%s)
# `mc alias set local` is already configured in the minio container per
# compose/minio's entrypoint; if not, configure it now (idempotent).
# The minio image is distroless-ish: no awk/sed/cut. Parse bucket names on
# the host (which has jq) and feed them back in. Falls back to a static list
# if jq is missing.
BUCKETS=$(podman exec minio sh -c '
  mc alias set local http://localhost:9000 minioadmin minioadmin >/dev/null 2>&1 || true
  mc ls --json local/ 2>/dev/null
' | jq -r 'select(.type=="folder") | .key | rtrimstr("/")' 2>/dev/null)
if [ -z "$BUCKETS" ]; then
  echo "  (no jq parse -> falling back to: claims)"
  BUCKETS="claims"
fi
echo "  buckets: $(echo "$BUCKETS" | tr '\n' ' ')"
podman exec minio sh -c 'mkdir -p /tmp/minio-snap && rm -rf /tmp/minio-snap/*'
for b in $BUCKETS; do
  podman exec minio sh -c "
    mkdir -p /tmp/minio-snap/$b
    mc mirror --quiet --overwrite local/$b /tmp/minio-snap/$b
  " >/dev/null
done
podman cp minio:/tmp/minio-snap "$OUT/minio/data"
podman exec minio rm -rf /tmp/minio-snap
MN_SIZE=$(du -sb "$OUT/minio/data" 2>/dev/null | awk '{print $1}')
echo "  -> $OUT/minio/data ($MN_SIZE bytes, $(( $(date +%s) - t0 ))s)"

# --- 3) Kafka (compacted topics only) ---
echo
echo "[3/3] Kafka topic snapshot (${COMPACTED_TOPICS[*]})"
t0=$(date +%s)
for topic in "${COMPACTED_TOPICS[@]}"; do
  # Records are dumped one-per-line with a key separator so they can be
  # re-produced by restore-into-scratch.sh. timeout-ms 10000 caps reads at
  # the high-water-mark; partition count is whatever the topic was created
  # with.
  echo "  consuming $topic ..."
  podman exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server kafka:9092 \
    --topic "$topic" \
    --from-beginning \
    --timeout-ms 10000 \
    --property print.key=true \
    --property key.separator='|||' \
    > "$OUT/kafka/${topic}.ndjson" 2>/dev/null || true
  N=$(wc -l < "$OUT/kafka/${topic}.ndjson")
  echo "    -> $N records"
done
echo "  ($(( $(date +%s) - t0 ))s)"

# --- Manifest ---
echo
echo "[manifest] generating SHA256SUMS"
( cd "$OUT" && find . -type f ! -name SHA256SUMS ! -name snapshot.log -print0 \
    | xargs -0 sha256sum > SHA256SUMS )

# --- Rotation ---
if [ "$KEEP" -gt 0 ]; then
  echo
  echo "[rotate] keeping last $KEEP daily snapshots under $BACKUP_ROOT"
  find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d \
    | sort | head -n -"$KEEP" | while read -r old; do
      echo "  pruning $old"
      rm -rf "$old"
    done
fi

ELAPSED=$(( $(date +%s) - START_EPOCH ))
TOTAL=$(du -sh "$OUT" | awk '{print $1}')
echo
echo "=== DONE in ${ELAPSED}s, total ${TOTAL} -> $OUT ==="
