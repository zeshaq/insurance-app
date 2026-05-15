#!/usr/bin/env bash
# Register the first SigNoz admin user. SigNoz's OTel collector refuses to
# bind OTLP receivers (ports 4317/4318) until an org/user exists, because the
# collector negotiates its receiver config over OPAMP with the SigNoz query
# service, and the query service rejects agent registration with
# "cannot create agent without orgId" before signup.
#
# Run once, on the VM, after `cd ~/signoz/deploy/docker && podman-compose up -d`
# has completed and the `signoz` container is healthy.
set -euo pipefail

ENDPOINT="${SIGNOZ_URL:-http://localhost:8080}"
NAME="${SIGNOZ_ADMIN_NAME:-Admin}"
EMAIL="${SIGNOZ_ADMIN_EMAIL:-admin@insurance-app.local}"
PASSWORD="${SIGNOZ_ADMIN_PASSWORD:-InsuranceLab123!}"
ORG="${SIGNOZ_ORG:-insurance-app}"

echo "Waiting for SigNoz frontend at $ENDPOINT ..."
for i in $(seq 1 60); do
  if curl -sf "$ENDPOINT/api/v1/version" >/dev/null 2>&1; then
    echo "  ready (took ~${i}s)"
    break
  fi
  sleep 2
done

if ! curl -sf "$ENDPOINT/api/v1/version" >/dev/null 2>&1; then
  echo "ERROR: SigNoz frontend never came up at $ENDPOINT" >&2
  exit 1
fi

echo "Registering admin user $EMAIL (org $ORG)..."
RESPONSE=$(curl -sS -X POST "$ENDPOINT/api/v1/register" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"$NAME\",\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"orgName\":\"$ORG\"}")

if echo "$RESPONSE" | grep -q '"status":"success"'; then
  ORGID=$(echo "$RESPONSE" | jq -r '.data.orgId' 2>/dev/null || echo unknown)
  echo "  registered. orgId=$ORGID"
elif echo "$RESPONSE" | grep -qiE "already|exist"; then
  echo "  admin already exists (probably from a prior run) — no-op."
else
  echo "  unexpected response:" >&2
  echo "$RESPONSE" >&2
  exit 1
fi

echo "Waiting for signoz-otel-collector to open OTLP ports..."
for i in $(seq 1 60); do
  if ss -tln 2>/dev/null | grep -qE ":(4317|4318)\b"; then
    echo "  OTLP receivers up (took ~${i}s)"
    break
  fi
  sleep 2
done

if ! ss -tln 2>/dev/null | grep -qE ":(4317|4318)\b"; then
  echo "ERROR: OTLP ports 4317/4318 still not listening after admin registration." >&2
  echo "Check 'podman logs signoz-otel-collector | tail -20' for clues." >&2
  exit 1
fi

echo "OK — SigNoz is ready to receive telemetry."
