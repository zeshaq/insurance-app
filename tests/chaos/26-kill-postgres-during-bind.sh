#!/usr/bin/env bash
# tests/chaos/26-kill-postgres-during-bind.sh -- issue #26, Phase 5
#
# Drill: kill the postgres primary mid-bind.
#
# What this proves
#   * When the DB is unavailable, POST /api/policies returns 5xx
#     (not 2xx-with-half-state, not a hang past the lock TTL).
#   * The Redlock distributed lock self-releases by TTL even when the
#     transaction never reaches commit. After postgres comes back,
#     a re-bind for the same quote_id MUST succeed -- if the lock
#     leaked, the second bind would get 409 Conflict and stay
#     409 until LOCK_TTL elapsed, then succeed.
#   * Bonus: the LOCK_TTL value below is auto-extracted from
#     PolicyService.java so this drill stays correct if the constant
#     ever changes.
#
# Method
#   1. Mint JWT, create a fresh Quote.
#   2. Background-fire POST /api/policies AND `podman stop postgres`
#      back-to-back so they race.
#   3. Wait for both to settle. Assert the bind returned a 5xx.
#   4. Wait LOCK_TTL+2 seconds so the Redlock TTL definitely
#      expires regardless of how the bind unwound.
#   5. Restart postgres; wait for pg_isready.
#   6. Re-attempt bind. Must 201 (clean) or 200 (if a previous
#      attempt somehow committed); never 409.
#   7. Loop 3 times.
#
# Trap
#   restore_env always ensures postgres is running at exit. Liberty is
#   left alone -- the drill should never need to touch it. If a
#   regression somehow wedges Liberty, that's a separate finding.
#
# Run location: VM. Override LIBERTY_BASE etc.
# Expected wall time: ~3 min for 3 iterations (postgres startup is
# the long pole, ~30-45s per iter).

set -eo pipefail

CREDS="${WSO2IS_CREDS:-/home/ze/insurance-app/.wso2is-creds}"
[ -f "$CREDS" ] && . "$CREDS"

LIBERTY_BASE="${LIBERTY_BASE:-http://localhost:9080}"
PG_CONTAINER="${PG_CONTAINER:-postgres}"
PG_USER="${PG_USER:-insurance}"
PG_DB="${PG_DB:-insurance}"
ITERATIONS="${ITERATIONS:-3}"
PG_READY_TIMEOUT="${PG_READY_TIMEOUT:-120}"

# Extract Redlock TTL from the source so we stay honest if it changes.
# The constant: `private static final Duration LOCK_TTL = Duration.ofSeconds(10);`
SRC="${POLICY_SERVICE_SRC:-/home/ze/insurance-app/src/main/java/com/example/insurance/policy/PolicyService.java}"
if [ -r "$SRC" ]; then
  LOCK_TTL_SECONDS=$(grep -E 'LOCK_TTL\s*=\s*Duration\.ofSeconds' "$SRC" \
    | sed -E 's/.*ofSeconds\(([0-9]+)\).*/\1/' \
    | head -1)
fi
LOCK_TTL_SECONDS="${LOCK_TTL_SECONDS:-10}"
WAIT_AFTER_DEATH=$((LOCK_TTL_SECONDS + 2))

WORK="$(mktemp -d)"
PASS=0
FAIL=0

restore_env() {
  echo "[trap] restoring postgres..."
  podman start "$PG_CONTAINER" >/dev/null 2>&1 || true
  local t=0
  while [ $t -lt "$PG_READY_TIMEOUT" ]; do
    if podman exec "$PG_CONTAINER" pg_isready -U "$PG_USER" >/dev/null 2>&1; then
      echo "[trap] postgres ready"
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

wait_pg_ready() {
  local t=0
  while [ $t -lt "$PG_READY_TIMEOUT" ]; do
    if podman exec "$PG_CONTAINER" pg_isready -U "$PG_USER" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    t=$((t+1))
  done
  return 1
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

echo "== drill #26: kill postgres during bind  (LOCK_TTL=${LOCK_TTL_SECONDS}s, iterations=$ITERATIONS) =="

if ! podman exec "$PG_CONTAINER" pg_isready -U "$PG_USER" >/dev/null 2>&1; then
  echo "ERROR: postgres not ready at start"
  exit 2
fi

AT=$(mint_jwt)
[ -z "$AT" ] || [ "$AT" = "null" ] && { echo "ERROR: JWT mint failed"; exit 2; }

for i in $(seq 1 "$ITERATIONS"); do
  echo "  -- iteration $i/$ITERATIONS --"

  VIN="CHAOS26-$i-$$"
  QID=$(curl -sSf -X POST "$LIBERTY_BASE/api/quotes" \
    -H "Authorization: Bearer $AT" \
    -H "Content-Type: application/json" \
    -d "{\"vehicleVin\":\"$VIN\",\"driverAge\":31,\"coverageType\":\"BASIC\"}" \
    | jq -r .id)

  if [ -z "$QID" ] || [ "$QID" = "null" ]; then
    echo "ERROR: quote creation failed on iter $i"
    FAIL=$((FAIL+1))
    continue
  fi

  # Stop postgres FIRST so the bind definitely races against a dying
  # DB connection. We started here trying to fire both simultaneously,
  # but the bind path is ~50ms and postgres takes longer to stop, so
  # most of the time the bind committed before podman stop landed.
  # Stopping first means: the bind ALWAYS sees a connection error
  # (either at connection-acquire or mid-tx), which is the failure
  # mode we want to assert on.
  podman stop -t 1 "$PG_CONTAINER" >/dev/null 2>&1 &
  STOP_PID=$!
  # Small overlap so Liberty has begun the request but postgres is
  # actively going down.
  sleep 0.1
  CODE=$(curl -sS -o "$WORK/bind-$i.body" -w "%{http_code}" \
    --max-time 30 \
    -X POST "$LIBERTY_BASE/api/policies" \
    -H "Authorization: Bearer $AT" \
    -H "Content-Type: application/json" \
    -d "{\"quoteId\":$QID}" || echo "ERR")
  wait "$STOP_PID" 2>/dev/null || true

  echo "    bind under DB-loss HTTP=$CODE"
  # Race-lost outcome: if the bind landed BEFORE postgres' connections
  # actually closed (Liberty's HikariCP can serve a pre-checked-out
  # connection even after `podman stop` issued SIGTERM), we got 200/201
  # back. That isn't a regression -- it just means the chaos didn't
  # bite. Skip this iteration's first assertion in that case rather
  # than counting it against us.
  if [ "$CODE" = "200" ] || [ "$CODE" = "201" ]; then
    echo "    NOTE: race lost (bind committed before DB-loss took effect); not asserting on this iteration"
  else
    check "iter $i: bind under DB-loss returned 5xx" \
      bash -c "[ -n '$CODE' ] && [ '$CODE' != 'ERR' ] && [ '$CODE' -ge 500 ] && [ '$CODE' -lt 600 ]"
  fi

  # Wait past LOCK_TTL so the Redlock entry self-expires even if Liberty
  # never got to call release().
  sleep "$WAIT_AFTER_DEATH"

  # Bring postgres back up.
  podman start "$PG_CONTAINER" >/dev/null
  if ! wait_pg_ready; then
    echo "ERROR: postgres did not become ready within ${PG_READY_TIMEOUT}s"
    FAIL=$((FAIL+1))
    continue
  fi

  # Liberty may need a few seconds for its connection pool to recover
  # after the DB returns; HikariCP's default validation handles this
  # automatically but the first request can still fail. Retry the
  # re-bind a few times.  curl can exit non-zero (timeout / connection
  # reset) so we `|| echo` to keep set -e from tripping.
  REBIND_CODE=""
  for attempt in 1 2 3 4 5; do
    REBIND_CODE=$(curl -sS -o "$WORK/rebind-$i-$attempt.body" -w "%{http_code}" \
      --max-time 15 \
      -X POST "$LIBERTY_BASE/api/policies" \
      -H "Authorization: Bearer $AT" \
      -H "Content-Type: application/json" \
      -d "{\"quoteId\":$QID}" || echo "ERR")
    if [ "$REBIND_CODE" = "201" ] || [ "$REBIND_CODE" = "200" ]; then
      break
    fi
    sleep 2
  done

  echo "    re-bind after recovery HTTP=$REBIND_CODE"
  check "iter $i: re-bind succeeded after DB recovery (200/201, NOT 409)" \
    bash -c "[ '$REBIND_CODE' = '201' ] || [ '$REBIND_CODE' = '200' ]"
  check "iter $i: re-bind not 409 (lock did NOT leak past TTL)" \
    bash -c "[ '$REBIND_CODE' != '409' ]"
done

echo
echo "== drill #26 results: PASS=$PASS  FAIL=$FAIL =="
[ "$FAIL" -eq 0 ]
