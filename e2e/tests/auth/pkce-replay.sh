#!/usr/bin/env bash
# e2e/tests/auth/pkce-replay.sh — issue #22, Phase 4 security
#
# Goal: prove WSO2 IS rejects a second use of the same authorization
# code. (OAuth 2.0 §4.1.2: "The client MUST NOT use the authorization
# code more than once. If an authorization code is used more than once,
# the authorization server MUST deny the request.")
#
# Method:
#   1. Drive a full OIDC code-flow via Playwright using the
#      customer-app's OIDC client. We supply our own state +
#      code_verifier so we can replay the resulting code from a shell
#      script.
#   2. Capture the `code` parameter from the IS-issued redirect to
#      our /auth/callback/wso2is. We capture from inside Playwright
#      BEFORE the BFF's callback handler successfully exchanges the
#      code (in our case the BFF errors with "Configuration" because
#      we didn't go through /auth/signin to stash state+verifier —
#      so the code remains unused and our shell-side replay starts
#      with a fresh code).
#   3. Exchange the code for tokens once (must succeed).
#   4. Exchange the SAME code a second time (must fail with
#      invalid_grant).

set -euo pipefail

CREDS="${CUSTOMER_OIDC_CREDS:-/home/ze/insurance-app/.customer-app-oidc-creds}"
if [ -f "$CREDS" ]; then
  # shellcheck disable=SC1090
  . "$CREDS"
fi

CLIENT_ID="${CUSTOMER_OIDC_CLIENT_ID:?CUSTOMER_OIDC_CLIENT_ID must be set}"
CLIENT_SECRET="${CUSTOMER_OIDC_CLIENT_SECRET:?CUSTOMER_OIDC_CLIENT_SECRET must be set}"
REDIRECT_URI="${CUSTOMER_OIDC_REDIRECT_URI:?CUSTOMER_OIDC_REDIRECT_URI must be set}"
ISSUER="${CUSTOMER_OIDC_ISSUER:?CUSTOMER_OIDC_ISSUER must be set}"
USERNAME="${PORTAL_TEST_USER:-student@comptech.com}"
PASSWORD="${PORTAL_TEST_PASSWORD:-Student@1234}"

TOKEN_URL="${ISSUER%/token}/token"
AUTHZ_URL="${ISSUER%/token}/authorize"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# PKCE: generate verifier + S256 challenge per RFC 7636.
#   verifier:  43–128 chars, [A-Z a-z 0-9 - . _ ~]
#   challenge: base64url(sha256(verifier)), no padding
# 32 random bytes -> base64url -> exactly 43 chars (the floor of the
# legal range). Never truncate a base64 stream: cutting mid-byte
# produces invalid characters and IS returns "Code verifier used is
# not up to RFC 7636 specifications.".
b64url() { base64 | tr -d '=' | tr '+/' '-_' | tr -d '\n'; }
gen_verifier() { head -c 32 /dev/urandom | b64url; }

CODE_VERIFIER="$(gen_verifier)"
CODE_CHALLENGE="$(printf %s "$CODE_VERIFIER" | openssl dgst -binary -sha256 | b64url)"
STATE="pkce-replay-$$-$(date +%s)"

echo "== pkce-replay against $TOKEN_URL =="
echo "client_id=$CLIENT_ID redirect_uri=$REDIRECT_URI"
echo "verifier len=${#CODE_VERIFIER} challenge len=${#CODE_CHALLENGE}"

# ---- Step 1: drive Playwright with our state + challenge ----
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
if [ -z "$CODE" ]; then
  echo "FAIL: did not capture an authorization code from the redirect"
  exit 2
fi
echo "captured code (first 16): ${CODE:0:16}..."

# ---- Step 2: first exchange (must succeed) ----
echo
echo "-- exchange #1 (expect 200 + access_token) --"
RESP1="$(curl -sS -X POST "$TOKEN_URL" \
  -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d "grant_type=authorization_code" \
  -d "code=$CODE" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" \
  -d "code_verifier=$CODE_VERIFIER")"
echo "$RESP1" | head -c 300; echo
if ! echo "$RESP1" | grep -q '"access_token"'; then
  echo "FAIL: first exchange did not return access_token"
  exit 2
fi

# ---- Step 3: second exchange of the SAME code (must fail) ----
echo
echo "-- exchange #2 with same code (expect 400 invalid_grant) --"
RESP2_CODE="$(curl -sS -o "$WORK/resp2.json" -w "%{http_code}" -X POST "$TOKEN_URL" \
  -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d "grant_type=authorization_code" \
  -d "code=$CODE" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" \
  -d "code_verifier=$CODE_VERIFIER")"
echo "HTTP $RESP2_CODE"
cat "$WORK/resp2.json"; echo
if [ "$RESP2_CODE" = "200" ]; then
  echo
  echo "FAIL: WSO2 IS accepted the same authorization code twice."
  exit 1
fi
if ! grep -q "invalid_grant" "$WORK/resp2.json"; then
  echo
  echo "WARNING: second exchange failed but error was not invalid_grant."
  echo "         RFC 6749 §5.2 expects invalid_grant for a replayed code."
  echo "         Treating as PASS because the code was rejected."
fi
echo
echo "PASS: WSO2 IS rejected the replayed authorization code."
exit 0
