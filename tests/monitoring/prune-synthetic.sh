#!/usr/bin/env bash
# Prune synthetic-monitor rows from Postgres.
#
# The synthetic monitor at tests/monitoring/quick-smoke.sh fires every
# 60s and POSTs a real quote with VIN prefix `SYNMON`. Left unattended
# that's 1,440 rows/day in the `quote` table — fine for a teaching lab,
# unbounded growth for any longer-running deployment.
#
# This script deletes all rows where vehicle_vin LIKE 'SYNMON%' AND
# the row is older than 24 hours. Run from a daily systemd timer
# (tests/monitoring/prune-synthetic.timer). Logs counts so the
# operator can spot drift.
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-postgres}"
PG_USER="${POSTGRES_USER:-insurance}"
PG_DB="${POSTGRES_DB:-insurance}"
AGE_HOURS="${SYNMON_RETENTION_HOURS:-24}"
LOG_DIR="${SYNMON_LOG_DIR:-$HOME/insurance-app/logs}"

mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/prune-synthetic.log"
TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# Count first so the log records what would be deleted even on a dry run.
COUNT=$(podman exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tA -c \
  "SELECT count(*) FROM quote WHERE vehicle_vin LIKE 'SYNMON%' \
   AND created_at < now() - interval '${AGE_HOURS} hours'")

if [ "$COUNT" = "0" ]; then
  echo "$TS prune-synthetic age=${AGE_HOURS}h deleted=0 (nothing to prune)" >>"$LOG"
  exit 0
fi

# Cascade to dependent tables. policy + payment reference quote_id; both
# have ON DELETE CASCADE in V2/V3 schema, so the single DELETE handles it.
podman exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -c \
  "DELETE FROM quote WHERE vehicle_vin LIKE 'SYNMON%' \
   AND created_at < now() - interval '${AGE_HOURS} hours'" >/dev/null

echo "$TS prune-synthetic age=${AGE_HOURS}h deleted=$COUNT" >>"$LOG"
