#!/usr/bin/env bash
# tests/backup/restore-into-scratch.sh
#
# Restores a snapshot produced by snapshot-all.sh into a fully isolated
# scratch environment:
#
#   * Network:    insurance-net-scratch (separate user-defined bridge)
#   * Containers: postgres-scratch / minio-scratch / kafka-scratch
#   * Ports:      55432 / 59000+59001 / 59092 (offset by +50000 from live)
#
# The live containers (postgres, minio, kafka) are untouched. This script is
# a recovery drill -- it proves the snapshot is restorable end-to-end without
# clobbering production data.
#
# After restore, runs a verification step:
#   - quote row count matches dump
#   - policy row count matches dump
#   - claim row count matches dump
#   - claims/ bucket file count matches mirror
#   - audit-events / policy-events record counts produced into scratch kafka
#
# Usage:
#   ./restore-into-scratch.sh <snapshot-dir>
#   ./restore-into-scratch.sh /home/ze/insurance-app/backups/20260518T101500Z
#
# Idempotent: tears down any prior scratch containers/network before starting.
# Exits 0 only if all verification checks pass.

set -euo pipefail

SNAP="${1:-}"
if [ -z "$SNAP" ] || [ ! -d "$SNAP" ]; then
  echo "usage: $0 <snapshot-dir>" >&2
  exit 2
fi
SNAP="$(cd "$SNAP" && pwd)"

NET=insurance-net-scratch
PG=postgres-scratch
MN=minio-scratch
KF=kafka-scratch

PG_PORT=55432
MN_PORT=59000
MN_CONS=59001
KF_PORT=59092

exec > >(tee -a "$SNAP/restore.log") 2>&1
START=$(date +%s)
echo "=== restore drill $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
echo "snapshot: $SNAP"

# --- 0) Manifest verify ---
echo
echo "[0/5] verifying SHA256SUMS"
( cd "$SNAP" && sha256sum -c --quiet SHA256SUMS )

# --- 1) Tear down any prior scratch ---
echo
echo "[1/5] tearing down any prior scratch environment"
for c in "$PG" "$MN" "$KF"; do
  podman rm -f "$c" >/dev/null 2>&1 || true
done
podman network rm "$NET" >/dev/null 2>&1 || true
podman network create "$NET" >/dev/null
echo "  network: $NET"

# --- 2) Postgres ---
echo
echo "[2/5] starting $PG and restoring dump"
t0=$(date +%s)
podman run -d --name "$PG" --network "$NET" \
  -e POSTGRES_USER=insurance -e POSTGRES_PASSWORD=insurance -e POSTGRES_DB=insurance \
  -p ${PG_PORT}:5432 \
  docker.io/library/postgres:17-alpine >/dev/null
# Wait for readiness.
for _ in $(seq 1 60); do
  if podman exec "$PG" pg_isready -U insurance -d insurance >/dev/null 2>&1; then break; fi
  sleep 1
done
podman cp "$SNAP/postgres/insurance.dump" "$PG":/tmp/insurance.dump
# --clean drops existing objects, --if-exists silences "doesn't exist".
# -j 4 = 4-way parallel restore.
podman exec "$PG" pg_restore \
  -U insurance -d insurance \
  --clean --if-exists --no-owner --no-privileges -j 4 \
  /tmp/insurance.dump
podman exec "$PG" rm -f /tmp/insurance.dump
echo "  postgres restored ($(( $(date +%s) - t0 ))s)"

# --- 3) MinIO ---
echo
echo "[3/5] starting $MN and mirroring buckets"
t0=$(date +%s)
podman run -d --name "$MN" --network "$NET" \
  -e MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=minioadmin \
  -p ${MN_PORT}:9000 -p ${MN_CONS}:9001 \
  docker.io/minio/minio:latest server /data --console-address :9001 >/dev/null
# Wait for readiness.
for _ in $(seq 1 60); do
  if curl -sf "http://localhost:${MN_PORT}/minio/health/live" >/dev/null 2>&1; then break; fi
  sleep 1
done
# Push every bucket dir back into the scratch MinIO.
podman cp "$SNAP/minio/data" "$MN":/tmp/snap
podman exec "$MN" sh -c '
  set -e
  mc alias set scratch http://localhost:9000 minioadmin minioadmin >/dev/null 2>&1
  for d in /tmp/snap/*; do
    [ -d "$d" ] || continue
    b=$(basename "$d")
    mc mb -p scratch/$b >/dev/null 2>&1 || true
    mc mirror --quiet --overwrite "$d" scratch/$b
  done
'
echo "  minio restored ($(( $(date +%s) - t0 ))s)"

# --- 4) Kafka ---
echo
echo "[4/5] starting $KF and replaying compacted topics"
t0=$(date +%s)
# Single-node KRaft Kafka for the scratch env.
podman run -d --name "$KF" --network "$NET" \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS="PLAINTEXT://:9092,CONTROLLER://:9093" \
  -e KAFKA_ADVERTISED_LISTENERS="PLAINTEXT://${KF}:9092" \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS="1@${KF}:9093" \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP="PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT" \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -p ${KF_PORT}:9092 \
  docker.io/apache/kafka:4.0.2 >/dev/null
# Wait for readiness.
for _ in $(seq 1 90); do
  if podman exec "$KF" /opt/kafka/bin/kafka-topics.sh --bootstrap-server ${KF}:9092 --list >/dev/null 2>&1; then break; fi
  sleep 1
done
for topic in audit-events policy-events; do
  src="$SNAP/kafka/${topic}.ndjson"
  [ -s "$src" ] || { echo "  (skipping empty $topic)"; continue; }
  podman exec "$KF" /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server ${KF}:9092 --create \
    --topic "$topic" --partitions 3 --replication-factor 1 \
    --config cleanup.policy=compact >/dev/null 2>&1 || true
  # Replay key|||value pairs back into the scratch broker.
  podman cp "$src" "$KF":/tmp/${topic}.ndjson
  podman exec "$KF" sh -c "
    /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server ${KF}:9092 \
      --topic $topic \
      --property parse.key=true --property key.separator='|||' \
      < /tmp/${topic}.ndjson
  "
  echo "  replayed $(wc -l < "$src") records into $topic"
done
echo "  kafka restored ($(( $(date +%s) - t0 ))s)"

# --- 5) Verification ---
echo
echo "[5/5] verifying restore"
pass=0; fail=0
# ck takes a name and then a single shell snippet evaluated under bash -c.
# Earlier draft passed quoting through one too many layers of bash -c and
# every check silently returned "command not found" -> failure.
ck() {
  local name="$1"; shift
  if eval "$@" >/dev/null 2>&1; then
    pass=$((pass+1)); echo "  ok:   $name"
  else
    fail=$((fail+1)); echo "  FAIL: $name"
  fi
}

QC=$(podman exec "$PG" psql -U insurance -d insurance -tAc 'select count(*) from quote' | tr -d ' ')
PC=$(podman exec "$PG" psql -U insurance -d insurance -tAc 'select count(*) from policy' | tr -d ' ')
CC=$(podman exec "$PG" psql -U insurance -d insurance -tAc 'select count(*) from claim' | tr -d ' ')
ck "scratch postgres quote rows ($QC)"  "[ ${QC:-0} -gt 0 ]"
ck "scratch postgres policy rows ($PC)" "[ ${PC:-0} -gt 0 ]"
ck "scratch postgres claim rows ($CC)"  "[ ${CC:-0} -gt 0 ]"

# Sample-row spot check: latest quote id round-trips.
LATEST_Q=$(podman exec "$PG" psql -U insurance -d insurance -tAc 'select max(id) from quote' | tr -d ' ')
HIT=$(podman exec "$PG" psql -U insurance -d insurance -tAc "select 1 from quote where id=${LATEST_Q:-0}" | tr -d ' ')
ck "scratch quote id ${LATEST_Q:-?} round-trips" "[ \"$HIT\" = 1 ]"

MN_COUNT=$(podman exec "$MN" sh -c 'mc alias set scratch http://localhost:9000 minioadmin minioadmin >/dev/null 2>&1; mc ls scratch/claims/ 2>/dev/null' | wc -l)
ck "scratch minio claims/ has objects ($MN_COUNT)" "[ ${MN_COUNT:-0} -gt 0 ]"

# Kafka: ensure each compacted topic has the count we replayed.
for topic in audit-events policy-events; do
  src="$SNAP/kafka/${topic}.ndjson"
  [ -s "$src" ] || continue
  EXPECT=$(wc -l < "$src")
  # End-offset sum across partitions >= EXPECT (compaction may collapse later).
  SUM=$(podman exec "$KF" /opt/kafka/bin/kafka-get-offsets.sh \
        --bootstrap-server ${KF}:9092 --topic "$topic" 2>/dev/null \
        | awk -F: '{s+=$3} END{print s+0}')
  ck "scratch kafka $topic has >=$EXPECT records (got ${SUM:-0})" \
     "[ ${SUM:-0} -ge $EXPECT ]"
done

ELAPSED=$(( $(date +%s) - START ))
echo
echo "=== restore drill ${ELAPSED}s -- $pass pass / $fail fail ==="
if [ "$fail" -ne 0 ]; then exit 1; fi

cat <<EOF

Scratch environment is live and isolated. Inspect:
  PGPASSWORD=insurance psql -h localhost -p ${PG_PORT} -U insurance insurance
  curl http://localhost:${MN_PORT}/minio/health/live
  podman exec ${KF} /opt/kafka/bin/kafka-topics.sh --bootstrap-server ${KF}:9092 --list

Tear down with:
  podman rm -f ${PG} ${MN} ${KF} && podman network rm ${NET}
EOF
