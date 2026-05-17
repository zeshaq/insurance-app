#!/usr/bin/env bash
# tests/monitoring/quick-smoke.sh
#
# Fast (~5-10s wall clock) synthetic monitor for the insurance-app lab.
#
# Designed to be invoked every 60s from a systemd user timer; runs every check
# in parallel where possible, fails fast, and writes only on non-zero exit so
# the log stays small.
#
# What it checks (and why these specifically):
#   * Liberty `/api/ping`                 -- the API container is alive and serving
#   * Liberty -> Postgres `select 1`       -- the durable store is reachable
#   * Quote round-trip (POST + GET)        -- the most load-bearing happy path,
#                                             exercises Postgres + Redis + Kafka
#                                             producer in one request
#   * Public HTTPS HEAD on 13 subdomains  -- HAProxy + Cloudflare + the
#                                             container behind each name
#
# What it skips (and why):
#   * MI / WireMock credit-bureau lookup   -- ~2s on its own (mTLS + JKS load);
#                                             reserve for the full smoke.sh
#   * Debezium / OpenSearch CDC            -- async, eventual; not suited for
#                                             a 60s synthetic
#   * customer-app POST /quote             -- BROKEN since Phase 4 re-enabled
#                                             CSRF; the form action 403s
#                                             without a SvelteKit-generated
#                                             CSRF token. Papering over with
#                                             curl would hide a real failure.
#                                             Tracked separately under the
#                                             customer-app csrf issue.
#   * Spike / load scenarios               -- by definition not a monitor
#
# Exit code: 0 = every check passed; non-zero = number of failed checks.
# stdout = always a one-line summary suitable for tail -F.
# stderr = per-failure detail lines, only on failure (suppressed via &>/dev/null
#          from the systemd service if you want the unit's stderr empty).

set +e

# Locate the wso2is creds for the bearer token.
for p in "$HOME/insurance-app/.wso2is-creds" "./.wso2is-creds" "/home/ze/insurance-app/.wso2is-creds"; do
  [ -f "$p" ] && . "$p" && break
done

START_NS=$(date +%s%N)
fail=0
errors=()

fail_check() {
  fail=$((fail+1))
  errors+=("FAIL: $1")
}

# ---- 1) Liberty /api/ping ----
# Endpoint returns {"status":"ok"} as JSON; we accept either that or the
# bare "ok" historical form, so this monitor survives a future contract change.
PING=$(curl -sf --max-time 3 http://localhost:9080/api/ping)
case "$PING" in
  ok|*'"status":"ok"'*) : ;;
  *) fail_check "/api/ping returned '${PING:-empty}'" ;;
esac

# ---- 2) Liberty -> Postgres select 1 ----
# Liberty exposes /api/health/db (added in Phase 2 for k8s readiness). If it's
# not available in this build, fall back to direct podman exec.
DB_OK=$(curl -sf --max-time 3 http://localhost:9080/api/health/db 2>/dev/null \
         | grep -c '"status":"UP"' || true)
if [ "${DB_OK:-0}" -eq 0 ]; then
  # Fallback: ask Postgres directly.
  podman exec postgres psql -U insurance -d insurance -tAc 'select 1' 2>/dev/null \
    | grep -q '^1$' || fail_check "postgres select 1"
fi

# ---- 3) Quote round-trip ----
# Need a bearer token. Grab one once per run; this is the only slow-ish step
# (~200ms on the inside of the VM).
AT=$(curl -ks --max-time 3 -X POST -u "$WSO2IS_CLIENT_ID:$WSO2IS_CLIENT_SECRET" \
       "$WSO2IS_TOKEN_URL" -d "grant_type=client_credentials" 2>/dev/null \
       | jq -r .access_token 2>/dev/null)
if [ -z "$AT" ] || [ "$AT" = "null" ]; then
  fail_check "WSO2 IS token exchange returned no token"
else
  # VIN must be 3-17 chars (ISO 3779). Use last 6 of epoch + 5 of pid to fit.
  VIN="MON$(date +%s | tail -c 7)$$"
  VIN="${VIN:0:17}"
  QUOTE=$(curl -ksf --max-time 4 -X POST http://localhost:9080/api/quotes \
            -H "Authorization: Bearer $AT" \
            -H "Content-Type: application/json" \
            -d "{\"vehicleVin\":\"$VIN\",\"driverAge\":30,\"coverageType\":\"BASIC\"}")
  QID=$(echo "$QUOTE" | jq -r '.id // empty' 2>/dev/null)
  if [ -z "$QID" ]; then
    fail_check "POST /api/quotes returned no id (body: ${QUOTE:0:80})"
  else
    # GET round-trip.
    GET_OK=$(curl -sf --max-time 3 -H "Authorization: Bearer $AT" \
               "http://localhost:9080/api/quotes/$QID" \
             | jq -r ".id == $QID" 2>/dev/null)
    [ "$GET_OK" = "true" ] || fail_check "GET /api/quotes/$QID didn't round-trip"
  fi
fi

# ---- 4) Public-URL HEAD on every subdomain ----
# The issue spec mentions 13 names; only 12 are wired through HAProxy +
# Cloudflare today (app, signoz, minio, kafka, mail, search, is, apim,
# gateway, redis, my, agent). The 13th "register" name from ADR-0007 has
# not been brought up. HEAD only (no body fetch) so we don't tickle server
# logic; just confirm the edge -> origin path is intact. Run in parallel
# so the slowest single check sets the wall clock, not the sum.
HOSTS=(app signoz minio kafka mail search is apim gateway redis my agent)
# Use --connect-timeout separate from --max-time so a slow TLS handshake
# doesn't masquerade as success.
check_subdomain() {
  local h="$1"
  local url="https://${h}.insurance-app.comptech-lab.com/"
  # /api/ping for the 'app' subdomain; everything else accepts a root HEAD.
  [ "$h" = "app" ] && url="${url}api/ping"
  if ! curl -ksf -o /dev/null -L --connect-timeout 2 --max-time 5 -I "$url" 2>/dev/null; then
    # Some servers reject HEAD; retry GET with -o /dev/null.
    curl -ksf -o /dev/null -L --connect-timeout 2 --max-time 5 "$url" 2>/dev/null \
      || echo "FAIL: ${h}.insurance-app"
  fi
}
export -f check_subdomain
SUB_FAILS=$(printf '%s\n' "${HOSTS[@]}" | xargs -I{} -P 13 bash -c 'check_subdomain "$@"' _ {})
if [ -n "$SUB_FAILS" ]; then
  while IFS= read -r line; do
    fail_check "${line#FAIL: }"
  done <<< "$SUB_FAILS"
fi

# ---- Output ----
ELAPSED_MS=$(( ($(date +%s%N) - START_NS) / 1000000 ))
TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
if [ "$fail" -eq 0 ]; then
  printf 'ok ts=%s elapsed_ms=%d checks=pass\n' "$TS" "$ELAPSED_MS"
  exit 0
else
  printf 'FAIL ts=%s elapsed_ms=%d failed=%d\n' "$TS" "$ELAPSED_MS" "$fail"
  for e in "${errors[@]}"; do
    printf '  %s\n' "$e" >&2
  done
  exit "$fail"
fi
