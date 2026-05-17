#!/usr/bin/env bash
# tests/chaos/28-partition-wso2is.sh -- issue #28, Phase 5
#
# Drill: network-partition WSO2 IS from the BFFs (and from Liberty).
#
# What this proves
#   * A pre-existing service-account JWT keeps validating against
#     Liberty even when IS is unreachable, because Liberty's mpJwt
#     caches JWKS for ~10 min by default. (We mint a token BEFORE
#     the partition so the JWT itself is signed by a key the cached
#     JWKS already knows.)
#   * A NEW sign-in attempt against either BFF fails CLEANLY: the
#     BFFs' /auth/signin endpoints must respond (a 5xx is acceptable;
#     a hang or a 200-with-broken-redirect is not).
#   * Restoring network connectivity lets a fresh sign-in proceed
#     again. (We only check the redirect lands at IS -- not the full
#     OIDC click-through, which needs a real browser. Phase 4 covers
#     that.)
#
# Method
#   1. Mint a token NOW; cache it as $WARM_AT.
#   2. POST /api/quotes through Liberty with $WARM_AT -> expect 201.
#      Sanity-check the warm path before the partition.
#   3. podman network disconnect insurance-net wso2is
#   4. Repeat the warm /api/quotes call -- still 201 (JWKS cached).
#   5. POST /auth/signin/wso2is at the customer-app: expect 302 to
#      IS (the BFF can't reach IS to start the flow but at minimum
#      it must not hang). curl --max-time 10 catches a hang.
#      Note: the redirect URL points at IS by hostname; the BROWSER
#      would discover the partition only when it tries to follow.
#      So 302 is the correct degradation here -- the BFF doesn't
#      proactively dial IS on the /auth/signin path.
#   6. (Optional) attempt minting a NEW token against IS through
#      the partitioned network: must fail (the actual harden gate).
#   7. podman network connect insurance-net wso2is, wait 5s.
#   8. Re-mint a token -- must succeed.
#
# Trap
#   restore_env always reconnects wso2is to the network, so a failed
#   drill never leaves identity broken.
#
# Run location: VM. Expected wall time: ~30s.

set -eo pipefail

CREDS="${WSO2IS_CREDS:-/home/ze/insurance-app/.wso2is-creds}"
[ -f "$CREDS" ] && . "$CREDS"

LIBERTY_BASE="${LIBERTY_BASE:-http://localhost:9080}"
CUSTOMER_BASE="${CUSTOMER_BASE:-http://localhost:3000}"
AGENT_BASE="${AGENT_BASE:-http://localhost:3001}"
NETWORK="${NETWORK:-insurance-net}"
WSO2IS_CONTAINER="${WSO2IS_CONTAINER:-wso2is}"
ITERATIONS="${ITERATIONS:-3}"
RECONNECT_SETTLE="${RECONNECT_SETTLE:-8}"

WORK="$(mktemp -d)"
PASS=0
FAIL=0
PARTITIONED=0

restore_env() {
  echo "[trap] reconnecting $WSO2IS_CONTAINER to $NETWORK..."
  if [ "$PARTITIONED" = "1" ]; then
    podman network connect "$NETWORK" "$WSO2IS_CONTAINER" >/dev/null 2>&1 || true
  fi
  # Idempotent: if it's already connected (e.g. drill never partitioned),
  # `network connect` errors and we ignore. The trap is the safety net.
  rm -rf "$WORK"
}
trap restore_env EXIT

mint_jwt() {
  curl -k -sS -X POST --max-time 10 \
    -u "$WSO2IS_CLIENT_ID:$WSO2IS_CLIENT_SECRET" \
    "$WSO2IS_TOKEN_URL" \
    -d "grant_type=client_credentials" \
    | jq -r '.access_token // empty'
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

probe_warm_quote() {
  local at="$1"
  # Per-call unique VIN. Constraints:
  #   * QuoteRequest.vehicleVin is @Size(min=3, max=17) per ISO 3779;
  #     a too-long VIN gets 400 (constraint violation), not 201.
  #   * QuoteResource rate-limits 5 quotes per 60s per vehicleVin, so
  #     a static VIN trips 429 after the 5th call.
  # Use a 16-char string built from the high-resolution clock so each
  # call hits a fresh rate-limit bucket but stays under the size cap.
  local vin
  vin="P$(date +%s%N | tail -c 16)"
  local out
  out=$(curl -sS --max-time 10 -o /dev/null -w "%{http_code}" \
    -X POST "$LIBERTY_BASE/api/quotes" \
    -H "Authorization: Bearer $at" \
    -H "Content-Type: application/json" \
    -d "{\"vehicleVin\":\"$vin\",\"driverAge\":33,\"coverageType\":\"BASIC\"}")
  echo "$out"
}

probe_signin() {
  # POST against /auth/signin endpoints. We use --max-time 10 so a HANG
  # registers as failure. Acceptable responses while partitioned:
  #   - 302 (redirect to IS issued from cached BFF config)
  #   - 5xx (BFF can't reach IS to start PKCE)
  # NOT acceptable: empty (curl error), or response >10s.
  local url="$1"
  local code
  code=$(curl -sS --max-time 10 -o /dev/null -w "%{http_code}" \
    -X POST "$url" 2>/dev/null || echo "ERR")
  echo "$code"
}

echo "== drill #28: partition wso2is  (iterations=$ITERATIONS) =="

for i in $(seq 1 "$ITERATIONS"); do
  echo "  -- iteration $i/$ITERATIONS --"

  # Step 1: warm JWT before partition.
  WARM_AT=$(mint_jwt)
  if [ -z "$WARM_AT" ]; then
    echo "ERROR: could not mint warm JWT on iter $i"
    FAIL=$((FAIL+1))
    continue
  fi

  # Step 2: sanity-check the warm path.
  WARM_BEFORE=$(probe_warm_quote "$WARM_AT")
  echo "    warm-token quote (pre-partition) HTTP=$WARM_BEFORE"
  check "iter $i: warm-token /api/quotes pre-partition -> 201" \
    bash -c "[ '$WARM_BEFORE' = '201' ]"

  # Step 3: partition.
  echo "    >>> disconnecting $WSO2IS_CONTAINER from $NETWORK"
  podman network disconnect "$NETWORK" "$WSO2IS_CONTAINER" >/dev/null
  PARTITIONED=1

  # Step 4: warm JWT keeps working (JWKS cache in Liberty).
  WARM_DURING=$(probe_warm_quote "$WARM_AT")
  echo "    warm-token quote (during partition) HTTP=$WARM_DURING"
  check "iter $i: warm-token /api/quotes during partition stays 2xx" \
    bash -c "[ '$WARM_DURING' = '201' ]"

  # Step 5: BFF signin endpoints respond without hanging.
  CUST_SIGNIN=$(probe_signin "$CUSTOMER_BASE/auth/signin/wso2is")
  AGENT_SIGNIN=$(probe_signin "$AGENT_BASE/auth/signin")
  echo "    customer /auth/signin/wso2is HTTP=$CUST_SIGNIN"
  echo "    agent /auth/signin HTTP=$AGENT_SIGNIN"
  check "iter $i: customer /auth/signin/wso2is did not hang (got a code)" \
    bash -c "[ -n '$CUST_SIGNIN' ] && [ '$CUST_SIGNIN' != 'ERR' ] && [ '$CUST_SIGNIN' != '000' ]"
  check "iter $i: agent /auth/signin did not hang (got a code)" \
    bash -c "[ -n '$AGENT_SIGNIN' ] && [ '$AGENT_SIGNIN' != 'ERR' ] && [ '$AGENT_SIGNIN' != '000' ]"
  # And: must NOT be 200 (which would imply the BFF returned a body
  # pretending the signin succeeded). Either 302 (started redirect)
  # or 5xx (BFF couldn't reach IS) is fine.
  check "iter $i: customer signin is 302 or 5xx (not 200)" \
    bash -c "[ '$CUST_SIGNIN' = '302' ] || ([ '$CUST_SIGNIN' -ge 500 ] 2>/dev/null && [ '$CUST_SIGNIN' -lt 600 ] 2>/dev/null)"
  check "iter $i: agent signin is 302 or 5xx (not 200)" \
    bash -c "[ '$AGENT_SIGNIN' = '302' ] || ([ '$AGENT_SIGNIN' -ge 500 ] 2>/dev/null && [ '$AGENT_SIGNIN' -lt 600 ] 2>/dev/null)"

  # Step 6: a fresh token mint against IS MUST fail while partitioned.
  # (This is the lower bound on the chaos -- if this succeeds, the
  # partition didn't take effect.)
  set +e
  curl -k -sS --max-time 8 -o /dev/null \
    -u "$WSO2IS_CLIENT_ID:$WSO2IS_CLIENT_SECRET" \
    "$WSO2IS_TOKEN_URL" \
    -d "grant_type=client_credentials"
  RC=$?
  set -e
  check "iter $i: fresh JWT mint fails while partitioned" \
    bash -c "[ '$RC' != '0' ]"

  # Step 7: restore connectivity.
  echo "    <<< reconnecting $WSO2IS_CONTAINER to $NETWORK"
  podman network connect "$NETWORK" "$WSO2IS_CONTAINER" >/dev/null
  PARTITIONED=0
  sleep "$RECONNECT_SETTLE"

  # Step 8: a fresh token mint MUST succeed again.
  AFTER_AT=$(mint_jwt)
  check "iter $i: fresh JWT mint succeeds after reconnect" \
    bash -c "[ -n '$AFTER_AT' ]"
  if [ -n "$AFTER_AT" ]; then
    AFTER_QUOTE=$(probe_warm_quote "$AFTER_AT")
    echo "    fresh-token quote (post-recovery) HTTP=$AFTER_QUOTE"
    check "iter $i: fresh-token /api/quotes after recovery -> 201" \
      bash -c "[ '$AFTER_QUOTE' = '201' ]"
  fi
done

echo
echo "== drill #28 results: PASS=$PASS  FAIL=$FAIL =="
[ "$FAIL" -eq 0 ]
