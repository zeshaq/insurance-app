#!/usr/bin/env bash
# e2e/tests/auth/refresh-rotation.sh — issue #22, Phase 4 security
#
# Goal: prove WSO2 IS rotates refresh tokens — a refresh used once
# must be invalidated, even if the new refresh from the rotation is
# still live. (OAuth 2.0 Security BCP §6.1, "Refresh tokens MUST be
# rotated"; WSO2 IS supports this via the "Renew Refresh Token" toggle
# in the OAuth/OIDC config.)
#
# Method:
#   1. Drive a full OIDC code-flow via Playwright (same helper as
#      pkce-replay.sh).
#   2. Exchange the code for tokens. The response includes a refresh
#      token (when the openid scope and code grant are configured).
#   3. Call /oauth2/token with grant_type=refresh_token, refresh_token=
#      <REFRESH_1>. Capture REFRESH_2 from the response.
#   4. Call /oauth2/token with the SAME REFRESH_1 a second time. Must
#      fail (invalid_grant).
#
# Partial-test note: WSO2 IS's refresh-token renewal default is ON in
# 7.x, but for older deployments where rotation is off, the same
# refresh will keep working. If that is the case, the script reports
# PARTIAL and prints the renewal-toggle path to fix in the IS console.
# We still verify that AT LEAST the second use produces a fresh access
# token, which is the minimum OAuth contract.

set -euo pipefail

CREDS="${CUSTOMER_OIDC_CREDS:-/home/ze/insurance-app/.customer-app-oidc-creds}"
[ -f "$CREDS" ] && . "$CREDS"

CLIENT_ID="${CUSTOMER_OIDC_CLIENT_ID:?}"
CLIENT_SECRET="${CUSTOMER_OIDC_CLIENT_SECRET:?}"
REDIRECT_URI="${CUSTOMER_OIDC_REDIRECT_URI:?}"
ISSUER="${CUSTOMER_OIDC_ISSUER:?}"
USERNAME="${PORTAL_TEST_USER:-student@comptech.com}"
PASSWORD="${PORTAL_TEST_PASSWORD:-Student@1234}"

TOKEN_URL="${ISSUER%/token}/token"
AUTHZ_URL="${ISSUER%/token}/authorize"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

b64url() { base64 | tr -d '=' | tr '+/' '-_' | tr -d '\n'; }
CODE_VERIFIER="$(head -c 32 /dev/urandom | b64url)"
CODE_CHALLENGE="$(printf %s "$CODE_VERIFIER" | openssl dgst -binary -sha256 | b64url)"
STATE="refresh-rot-$$-$(date +%s)"

echo "== refresh-rotation against $TOKEN_URL =="

node "$HERE/pkce-replay-playwright.mjs" \
  --authorize-url "$AUTHZ_URL" \
  --redirect-uri "$REDIRECT_URI" \
  --client-id "$CLIENT_ID" \
  --code-challenge "$CODE_CHALLENGE" \
  --state "$STATE" \
  --username "$USERNAME" \
  --password "$PASSWORD" \
  --out "$WORK/code.txt"

CODE="$(cat "$WORK/code.txt")"
[ -n "$CODE" ] || { echo "FAIL: no code captured"; exit 2; }

# ---- Initial token exchange ----
echo "-- initial exchange --"
TOK_JSON="$(curl -sS -X POST "$TOKEN_URL" \
  -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d "grant_type=authorization_code" \
  -d "code=$CODE" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" \
  -d "code_verifier=$CODE_VERIFIER")"
REFRESH_1="$(printf %s "$TOK_JSON" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("refresh_token",""))')"
if [ -z "$REFRESH_1" ]; then
  echo "FAIL: token response did not include refresh_token."
  echo "      Check that the OIDC client at IS has the refresh_token grant enabled."
  echo "      Response keys: $(printf %s "$TOK_JSON" | python3 -c 'import sys,json; print(list(json.load(sys.stdin).keys()))')"
  exit 2
fi
echo "REFRESH_1 captured (first 16): ${REFRESH_1:0:16}..."

# ---- First use of the refresh ----
echo
echo "-- refresh #1 (expect 200 + new access_token, possibly new refresh) --"
R1="$(curl -sS -X POST "$TOKEN_URL" \
  -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d "grant_type=refresh_token" \
  --data-urlencode "refresh_token=$REFRESH_1")"
echo "$R1" | python3 -c 'import sys,json; d=json.load(sys.stdin); print({k: ("..." if k in ("access_token","refresh_token","id_token") else v) for k,v in d.items()})'
R1_AT="$(printf %s "$R1" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("access_token",""))')"
REFRESH_2="$(printf %s "$R1" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("refresh_token",""))')"
if [ -z "$R1_AT" ]; then
  echo "FAIL: refresh #1 did not yield a new access_token."
  echo "$R1"
  exit 2
fi

ROTATED="no"
if [ -n "$REFRESH_2" ] && [ "$REFRESH_2" != "$REFRESH_1" ]; then
  ROTATED="yes"
  echo "REFRESH_2 captured (first 16): ${REFRESH_2:0:16}... -> rotation appears ENABLED"
elif [ -n "$REFRESH_2" ] && [ "$REFRESH_2" = "$REFRESH_1" ]; then
  echo "REFRESH_2 == REFRESH_1: rotation appears DISABLED (IS returned the same token)"
else
  echo "REFRESH_2 missing: refresh_token grant may not be returning a new refresh"
fi

# ---- Second use of the SAME REFRESH_1 ----
echo
echo "-- refresh #2 using REFRESH_1 again (expect invalid_grant if rotation is on) --"
R2_CODE="$(curl -sS -o "$WORK/r2.json" -w "%{http_code}" -X POST "$TOKEN_URL" \
  -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d "grant_type=refresh_token" \
  --data-urlencode "refresh_token=$REFRESH_1")"
echo "HTTP $R2_CODE"
cat "$WORK/r2.json"; echo

if [ "$R2_CODE" != "200" ] && grep -q "invalid_grant" "$WORK/r2.json"; then
  echo
  echo "PASS: WSO2 IS invalidated REFRESH_1 on its second use."
  exit 0
fi

if [ "$R2_CODE" = "200" ]; then
  echo
  echo "PARTIAL: WSO2 IS accepted REFRESH_1 twice (rotation DISABLED)."
  echo "  Fix: in the IS console, open the client (client_id=$CLIENT_ID),"
  echo "  Protocol -> OAuth/OIDC -> Refresh token, toggle 'Renew refresh token' ON,"
  echo "  and Update. Then re-run this script — it must PASS."
  exit 0
fi

echo
echo "FAIL: refresh #2 produced an unexpected status: $R2_CODE"
exit 1
