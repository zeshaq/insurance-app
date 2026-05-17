#!/usr/bin/env bash
# e2e/tests/auth/jwt-rotation-dryrun.sh — issue #23, Phase 4
#
# Steady-state JWT validation check. Designed to be run BEFORE, DURING
# (the grace window), and AFTER a real key rotation in WSO2 IS. The
# actual rotation requires IS-console clicks — see
# docs/runbooks/jwt-key-rotation.md.
#
# What it does:
#   1. Snapshot the current JWKS (kids + algs).
#   2. Mint a fresh JWT via the IS token endpoint using the
#      service-account client_credentials grant.
#   3. Decode the JWT header and assert its kid is present in JWKS.
#   4. POST /api/quotes through Liberty with the token; assert 201.
#      We use POST /api/quotes specifically because it is the only
#      route on insurance-app today that returns 401 without a token
#      and 2xx with one — the right shape to exercise mpJwt.
#   5. Wait JWKS_CACHE_TTL seconds (default 65 — slightly over a 60s
#      Liberty JWKS cache window) so any in-flight rotation has time
#      to propagate.
#   6. Re-snapshot JWKS, re-mint, re-validate. If anything changed
#      (kid added, kid removed, alg changed), surface it.
#   7. Exit 0 iff every validation step came back 2xx and every
#      minted kid was published in JWKS.
#
# Run location: from the VM (so http://localhost:9080 reaches Liberty
# directly). Override LIBERTY_BASE for environments where Liberty is
# fronted by haproxy. The auth-tests CI job does NOT run this script —
# Liberty is not publicly exposed.
#
# Limitations:
#   - This is a steady-state probe. It can't itself rotate the key —
#     that's a console action. It WILL catch a half-rotated state:
#     e.g. IS started signing with NEW but forgot to add NEW to JWKS
#     first (Liberty's JWKS fetch won't find the kid → 401).
#   - The post-rotation verification must be done by re-running this
#     script after step 3 of the runbook, and again after step 6.

set -euo pipefail

CREDS="${WSO2IS_CREDS:-/home/ze/insurance-app/.wso2is-creds}"
[ -f "$CREDS" ] && . "$CREDS"

TOKEN_URL="${WSO2IS_TOKEN_URL:-https://is.insurance-app.comptech-lab.com/oauth2/token}"
JWKS_URL="${WSO2IS_JWKS_URL:-https://is.insurance-app.comptech-lab.com/oauth2/jwks}"
LIBERTY_BASE="${LIBERTY_BASE:-http://localhost:9080}"
# Liberty's mpJwt JWKS cache TTL — see the runbook for how to set it.
# Defaults to 65s so we land just past a typical 60s cache window.
JWKS_CACHE_TTL="${JWKS_CACHE_TTL:-65}"

CID="${WSO2IS_CLIENT_ID:?WSO2IS_CLIENT_ID must be set}"
CSECRET="${WSO2IS_CLIENT_SECRET:?WSO2IS_CLIENT_SECRET must be set}"

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

jwt_kid() {
  python3 -c "
import sys, base64, json
h = sys.argv[1].split('.')[0]
h += '=' * ((4 - len(h) % 4) % 4)
print(json.loads(base64.urlsafe_b64decode(h)).get('kid', ''))
" "$1"
}

snapshot_jwks() {
  curl -k -sS "$JWKS_URL" > "$1"
  python3 -c "
import sys, json
d = json.load(open('$1'))
print(f'kids: {len(d[\"keys\"])}')
for k in d['keys']:
    print(f'  kid={k[\"kid\"][:48]} alg={k[\"alg\"]} use={k.get(\"use\",\"-\")}')
"
}

kid_in_jwks() {
  python3 -c "
import sys, json
d = json.load(open('$2'))
print('OK' if '$1' in [k['kid'] for k in d['keys']] else 'MISS')
"
}

mint() {
  curl -k -sS -X POST -u "$CID:$CSECRET" "$TOKEN_URL" \
    -d "grant_type=client_credentials" | python3 -c 'import sys, json; print(json.load(sys.stdin).get("access_token",""))'
}

call_liberty() {
  local tok="$1"
  # vehicleVin must be 3-17 chars (ISO 3779). "JR-" + last 10 digits of
  # epoch seconds = 13 chars, safely under the cap. The dollar-PID is
  # omitted to keep length deterministic.
  local vin="JR-$(date +%s | tail -c 11)"
  local body="{\"vehicleVin\":\"${vin}\",\"driverAge\":35,\"coverageType\":\"STANDARD\"}"
  curl -sS -o "$WORK/api.json" -w "%{http_code}" \
    -X POST \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $tok" \
    -d "$body" \
    "$LIBERTY_BASE/api/quotes"
}

echo "== jwt-rotation-dryrun =="
echo "JWKS URL:   $JWKS_URL"
echo "Liberty:    $LIBERTY_BASE"
echo "Cache wait: ${JWKS_CACHE_TTL}s"
echo

# ---- Step 1: snapshot ----
echo "-- JWKS snapshot #1 --"
snapshot_jwks "$WORK/jwks1.json"

# ---- Step 2: mint #1 ----
TOK1="$(mint)"
[ -n "$TOK1" ] || { echo "FAIL: token mint returned empty"; exit 2; }
KID1="$(jwt_kid "$TOK1")"
echo "Token #1 kid=${KID1:0:48}..."

# ---- Step 3: kid presence ----
RES_KID1="$(kid_in_jwks "$KID1" "$WORK/jwks1.json")"
echo "kid present in JWKS: $RES_KID1"
[ "$RES_KID1" = "OK" ] || {
  echo "FAIL: token #1's kid is not in the current JWKS."
  echo "      IS started signing with a key it hasn't published yet."
  echo "      Action: in the runbook, you skipped step 2 (add new alias"
  echo "      to JWKS *before* promoting it to active signer in step 3)."
  exit 1
}

# ---- Step 4: Liberty accepts ----
HTTP1="$(call_liberty "$TOK1")"
echo "Liberty validation #1: POST /api/quotes -> HTTP $HTTP1"
case "$HTTP1" in
  2*) ;;
  *)  echo "FAIL: Liberty rejected token #1 ($HTTP1)."
      echo "Body: $(head -c 300 "$WORK/api.json")"
      exit 1 ;;
esac

# ---- Step 5: wait past cache TTL ----
echo
echo "-- waiting ${JWKS_CACHE_TTL}s for any JWKS-cache TTL to lapse --"
sleep "$JWKS_CACHE_TTL"

# ---- Step 6: re-snapshot + re-mint + re-validate ----
echo "-- JWKS snapshot #2 --"
snapshot_jwks "$WORK/jwks2.json"

if ! diff -q "$WORK/jwks1.json" "$WORK/jwks2.json" >/dev/null; then
  echo "JWKS CHANGED during the wait — rotation in progress."
  diff <(python3 -c "import json; [print(k['kid']) for k in json.load(open('$WORK/jwks1.json'))['keys']]") \
       <(python3 -c "import json; [print(k['kid']) for k in json.load(open('$WORK/jwks2.json'))['keys']]") || true
fi

TOK2="$(mint)"
KID2="$(jwt_kid "$TOK2")"
echo "Token #2 kid=${KID2:0:48}..."
RES_KID2="$(kid_in_jwks "$KID2" "$WORK/jwks2.json")"
echo "kid present in JWKS: $RES_KID2"
[ "$RES_KID2" = "OK" ] || { echo "FAIL: token #2 signed by a kid not in JWKS"; exit 1; }

HTTP2="$(call_liberty "$TOK2")"
echo "Liberty validation #2: POST /api/quotes -> HTTP $HTTP2"
case "$HTTP2" in
  2*) ;;
  *)  echo "FAIL: Liberty rejected token #2 ($HTTP2)."
      echo "Body: $(head -c 300 "$WORK/api.json")"
      exit 1 ;;
esac

echo
echo "PASS: both tokens minted, both kids published in JWKS, both validated by Liberty."
[ "$KID1" != "$KID2" ] && echo "      kid changed between mint #1 and mint #2 — rotation is live."
exit 0
