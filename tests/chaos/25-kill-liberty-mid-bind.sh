#!/usr/bin/env bash
# tests/chaos/25-kill-liberty-mid-bind.sh -- issue #25, Phase 5
#
# Drill: kill Liberty mid-@Transactional during a bind.
#
# What this proves
#   * Liberty's @Transactional + the UNIQUE(quote_id) constraint on
#     `policy` mean we MUST see either 0 OR 1 policy row for the
#     quote_id, never a half-written intermediate. The quote must
#     remain bindable on a clean restart.
#   * If the kill lands AFTER commit-but-before-response, the row
#     exists with status=BOUND. The follow-up GET succeeds.
#   * If the kill lands BEFORE commit, the row is absent. A retried
#     bind succeeds and creates the row.
#
# Method
#   1. Mint a JWT via the WSO2 IS client_credentials grant (same path
#      smoke.sh uses).
#   2. Create a fresh Quote.
#   3. Fire POST /api/policies in the background.
#   4. Kill `insurance-app` with `podman kill -s SIGKILL` while the
#      request is in flight.
#   5. Wait for the bg curl to terminate (it will fail with a
#      connection error or 5xx -- the drill doesn't care which).
#   6. Restart insurance-app, wait for /health/ready to flip to 200.
#   7. Assert COUNT(*) FROM policy WHERE quote_id=$QID IN (0,1).
#   8. If 0: retry the bind, must 201 + new row. If 1: GET the policy,
#      must 200 + status=BOUND.
#   9. Loop 5 times to catch flakes.
#
# Trap
#   restore_env always restarts insurance-app at the end so a failed
#   assertion never leaves the lab with Liberty down.
#
# Run location: from the VM. Override LIBERTY_BASE for proxy setups.
# Expected wall time: ~90s for the 5-iteration loop.

set -eo pipefail

CREDS="${WSO2IS_CREDS:-/home/ze/insurance-app/.wso2is-creds}"
[ -f "$CREDS" ] && . "$CREDS"

LIBERTY_BASE="${LIBERTY_BASE:-http://localhost:9080}"
LIBERTY_CONTAINER="${LIBERTY_CONTAINER:-insurance-app}"
PG_CONTAINER="${PG_CONTAINER:-postgres}"
PG_USER="${PG_USER:-insurance}"
PG_DB="${PG_DB:-insurance}"
ITERATIONS="${ITERATIONS:-5}"
READY_TIMEOUT="${READY_TIMEOUT:-90}"   # seconds

WORK="$(mktemp -d)"
PASS=0
FAIL=0

restore_env() {
  echo "[trap] restoring environment..."
  # If Liberty isn't running for any reason (e.g. drill failed mid-loop),
  # bring it back. `podman start` is a no-op on a running container.
  podman start "$LIBERTY_CONTAINER" >/dev/null 2>&1 || true
  # Wait up to READY_TIMEOUT for /health/ready, but never block the trap.
  local t=0
  while [ $t -lt "$READY_TIMEOUT" ]; do
    if curl -sSf "$LIBERTY_BASE/health/ready" >/dev/null 2>&1; then
      echo "[trap] Liberty ready"
      break
    fi
    sleep 1
    t=$((t+1))
  done
  rm -rf "$WORK"
}
trap restore_env EXIT

mint_jwt() {
  curl -k -sS -X POST \
    -u "$WSO2IS_CLIENT_ID:$WSO2IS_CLIENT_SECRET" \
    "$WSO2IS_TOKEN_URL" \
    -d "grant_type=client_credentials" \
    | jq -r .access_token
}

wait_ready() {
  local t=0
  while [ $t -lt "$READY_TIMEOUT" ]; do
    if curl -sSf "$LIBERTY_BASE/health/ready" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    t=$((t+1))
  done
  return 1
}

count_policies_for_quote() {
  local qid="$1"
  podman exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tA \
    -c "select count(*) from policy where quote_id = $qid"
}

policy_status_for_quote() {
  local qid="$1"
  podman exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tA \
    -c "select status from policy where quote_id = $qid"
}

check() {
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then
    printf "    PASS  %s\n" "$name"
    PASS=$((PASS+1))
  else
    printf "    FAIL  %s\n" "$name"
    FAIL=$((FAIL+1))
  fi
}

echo "== drill #25: kill Liberty mid-bind (iterations=$ITERATIONS) =="

if ! wait_ready; then
  echo "ERROR: Liberty not ready at start. Bail."
  exit 2
fi

AT=$(mint_jwt)
if [ -z "$AT" ] || [ "$AT" = "null" ]; then
  echo "ERROR: failed to mint JWT. Check WSO2 IS creds."
  exit 2
fi

for i in $(seq 1 "$ITERATIONS"); do
  echo "  -- iteration $i/$ITERATIONS --"

  VIN="CHAOS25-$i-$$"
  QID=$(curl -sSf -X POST "$LIBERTY_BASE/api/quotes" \
    -H "Authorization: Bearer $AT" \
    -H "Content-Type: application/json" \
    -d "{\"vehicleVin\":\"$VIN\",\"driverAge\":33,\"coverageType\":\"BASIC\"}" \
    | jq -r .id)

  if [ -z "$QID" ] || [ "$QID" = "null" ]; then
    echo "ERROR: quote creation failed iteration $i"
    FAIL=$((FAIL+1))
    continue
  fi

  # Fire bind in background. Capture exit code in a file (background
  # subshell can't easily return into the parent's variables).
  (
    curl -sS -o "$WORK/bind-$i.body" -w "%{http_code}" \
      --max-time 30 \
      -X POST "$LIBERTY_BASE/api/policies" \
      -H "Authorization: Bearer $AT" \
      -H "Content-Type: application/json" \
      -d "{\"quoteId\":$QID}" > "$WORK/bind-$i.code" 2>/dev/null
    echo $? > "$WORK/bind-$i.exit"
  ) &
  BIND_PID=$!

  # Tiny jitter so the kill lands somewhere mid-flight. The exact
  # window doesn't matter -- the assertion is shape-invariant under
  # any kill timing.
  sleep "0.$(( (RANDOM % 5) + 1 ))"
  podman kill -s SIGKILL "$LIBERTY_CONTAINER" >/dev/null 2>&1 || true

  # Wait for the in-flight request to finish (it will: either the
  # response landed before SIGKILL, or curl will get a connection-reset
  # / timeout). We DO NOT assert on the exit code here -- we assert on
  # the resulting DB state.
  wait "$BIND_PID" 2>/dev/null || true

  podman start "$LIBERTY_CONTAINER" >/dev/null
  if ! wait_ready; then
    echo "ERROR: Liberty did not become ready within ${READY_TIMEOUT}s on iter $i"
    FAIL=$((FAIL+1))
    continue
  fi

  COUNT=$(count_policies_for_quote "$QID")
  echo "    quote_id=$QID  policy_rows=$COUNT"
  check "iter $i: 0 or 1 policy row for quote $QID" bash -c "[ '$COUNT' = '0' ] || [ '$COUNT' = '1' ]"

  if [ "$COUNT" = "1" ]; then
    STATUS=$(policy_status_for_quote "$QID")
    check "iter $i: policy.status = BOUND" bash -c "[ '$STATUS' = 'BOUND' ]"
  elif [ "$COUNT" = "0" ]; then
    # Retry must succeed. Need a fresh JWT only if the original expired
    # (unlikely -- token is valid for 1h). Use the same AT.
    RETRY=$(curl -sS -o "$WORK/retry-$i.body" -w "%{http_code}" \
      -X POST "$LIBERTY_BASE/api/policies" \
      -H "Authorization: Bearer $AT" \
      -H "Content-Type: application/json" \
      -d "{\"quoteId\":$QID}")
    check "iter $i: post-kill bind retry -> 201" bash -c "[ '$RETRY' = '201' ]"
    NEW_COUNT=$(count_policies_for_quote "$QID")
    check "iter $i: row visible after retry" bash -c "[ '$NEW_COUNT' = '1' ]"
  fi
done

echo
echo "== drill #25 results: PASS=$PASS  FAIL=$FAIL =="
[ "$FAIL" -eq 0 ]
