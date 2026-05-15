#!/usr/bin/env bash
# Idempotently register the Debezium Postgres source connector that drives
# slice 14 (Search). PUT /connectors/<name>/config is upsert — calling it
# again with the same body is a no-op; calling it with a changed body
# reconfigures in place. Safe to invoke from smoke.
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

echo "==> status:"
curl -sS "$CONNECT_URL/connectors/$NAME/status" | jq '{name, connector:.connector.state, tasks:[.tasks[].state]}'
