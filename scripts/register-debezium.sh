#!/usr/bin/env bash
# Idempotently register the Debezium Postgres source connector that drives
# slice 14 (Search). PUT /connectors/<name>/config is upsert — calling it
# again with the same body is a no-op; calling it with a changed body
# reconfigures in place. Safe to invoke from smoke.
#
# Also: after config upsert, check the runtime state. Kafka restarts (or
# coordinator wobble — see build_gotchas item 16) can leave the connector
# in UNASSIGNED while tasks stay RUNNING; producers/consumers look fine but
# no CDC actually flows. We POST /restart?includeTasks=true to nudge it.
set -euo pipefail

CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
CONFIG="$(cd "$(dirname "$0")/.."; pwd)/compose/infra/connectors/debezium-postgres-claim.json"
NAME="insurance-claim-cdc"

if [ ! -f "$CONFIG" ]; then
    echo "missing $CONFIG" >&2
    exit 1
fi

echo "==> waiting for Kafka Connect at $CONNECT_URL"
for _ in $(seq 1 30); do
    if curl -sS -o /dev/null -w '%{http_code}' "$CONNECT_URL/connectors" | grep -q '^200$'; then break; fi
    sleep 1
done

# /connectors/<name>/config takes just the inner config object, not the
# full {name, config:{...}} envelope. Extract it on the fly with jq.
CONFIG_BODY=$(jq -c '.config' "$CONFIG")
echo "==> upserting connector $NAME"
curl -sS -X PUT -H 'Content-Type: application/json' \
    "$CONNECT_URL/connectors/$NAME/config" \
    --data "$CONFIG_BODY" \
    | jq -r '.tasks // .config // .error_code // .'

# Recover from UNASSIGNED / FAILED. The status endpoint reports the connector
# state and each task's state separately; either being non-RUNNING means no
# CDC flow, even though the connector might claim to exist.
echo "==> checking state"
sleep 2
STATUS=$(curl -sS "$CONNECT_URL/connectors/$NAME/status")
CONN_STATE=$(echo "$STATUS" | jq -r '.connector.state // empty')
TASK_STATES=$(echo "$STATUS" | jq -r '.tasks[]?.state // empty')

NEEDS_RESTART=0
[ "$CONN_STATE" != "RUNNING" ] && NEEDS_RESTART=1
for ts in $TASK_STATES; do
    [ "$ts" != "RUNNING" ] && NEEDS_RESTART=1
done

if [ "$NEEDS_RESTART" = "1" ]; then
    echo "==> connector state=$CONN_STATE, tasks=$TASK_STATES — restarting"
    curl -sS -X POST "$CONNECT_URL/connectors/$NAME/restart?includeTasks=true&onlyFailed=false" >/dev/null
    sleep 4
fi

echo "==> final status:"
curl -sS "$CONNECT_URL/connectors/$NAME/status" | jq '{name, connector:.connector.state, tasks:[.tasks[].state]}'
