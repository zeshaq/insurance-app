#!/usr/bin/env bash
# e2e/tests/auth/session-fixation.sh — issue #22, Phase 4 security
#
# Goal: prove (or expose) whether the agent-app BFF rotates its session
# cookie ID on an auth state change.
#
# Method:
#   1. Open a session against the agent-app (GET /login) and record the
#      `agent_sid` cookie that comes back. This is the "pre-login" SID
#      an attacker would have if they could fixate a session on the
#      victim's browser before login.
#   2. Drive the OIDC click-through via Playwright (so we get a real
#      authenticated session that survives the WSO2 IS redirect chain).
#      Reuse the SAME cookie jar across the entire flow, so we're
#      asking whether the BFF *rotates* the SID on auth-state change.
#   3. After landing on the agent dashboard, record the `agent_sid`
#      cookie again. Compare PRE vs POST.
#
# Acceptance:
#   - PASS  -> PRE_SID != POST_SID (BFF regenerates session on login)
#   - FAIL  -> PRE_SID == POST_SID (session fixation; the cookie an
#             attacker pre-set is still the one carrying authority)
#
# Today's expected outcome on this codebase: FAIL. The agent-app's
# `/auth/callback/wso2is` handler at gui/agent-app/server/index.ts
# attaches `req.session.user` to the existing session instead of
# calling `req.session.regenerate()`. This test exists precisely to
# surface that finding for issue #22; the fix is one line in the
# callback. The script exits 0 if the expected-vulnerable behavior
# is observed AND ${SESSION_FIXATION_EXPECT_FAIL:-1} is 1 (default),
# so CI is honest about the known finding without going red on every
# nightly run. Once the BFF is patched, flip the env var to 0 and
# the script will go red unless the SIDs differ.

set -euo pipefail

AGENT_BASE_URL="${AGENT_BASE_URL:-https://agent.insurance-app.comptech-lab.com}"
USERNAME="${PORTAL_TEST_USER:-student@comptech.com}"
PASSWORD="${PORTAL_TEST_PASSWORD:-Student@1234}"
EXPECT_FAIL="${SESSION_FIXATION_EXPECT_FAIL:-1}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "== session-fixation against $AGENT_BASE_URL =="

# ---- Step 1: pre-login SID ----
# curl follows redirects so we land on /login; the BFF mints a fresh
# anonymous session cookie on first visit because saveUninitialized is
# false but express-session sets the cookie as soon as we attach state
# (we'll touch /login which renders the page → cookie issued on first
# response that calls req.session.something, or on /auth/signin which
# stashes codeVerifier+state).
curl -sS -c "$WORK/jar.txt" -b "$WORK/jar.txt" -L -o /dev/null \
  "$AGENT_BASE_URL/login"

# Cookie jar uses Netscape format; agent_sid is HttpOnly so the row's
# 7th field is the cookie value. We grep agent_sid specifically.
pre_sid="$(awk '$6=="agent_sid"{print $7}' "$WORK/jar.txt" || true)"
if [ -z "$pre_sid" ]; then
  # If the BFF doesn't issue an anonymous session on /login alone, force
  # it by starting the OIDC flow (POST /auth/signin); that handler
  # writes codeVerifier+state into the session, which forces a Set-Cookie.
  curl -sS -c "$WORK/jar.txt" -b "$WORK/jar.txt" -L -o /dev/null \
    -X POST "$AGENT_BASE_URL/auth/signin" -d ''
  pre_sid="$(awk '$6=="agent_sid"{print $7}' "$WORK/jar.txt" || true)"
fi

if [ -z "$pre_sid" ]; then
  echo "FAIL: could not obtain a pre-login agent_sid cookie"
  exit 2
fi
echo "PRE_SID  = ${pre_sid:0:24}... (truncated)"

# ---- Step 2: drive the OIDC click-through in Playwright, importing
# the cookie jar from step 1 so the SID we started with is the one that
# rides through the auth flow.
node "$HERE/session-fixation-playwright.mjs" \
  --jar "$WORK/jar.txt" \
  --base-url "$AGENT_BASE_URL" \
  --username "$USERNAME" \
  --password "$PASSWORD"

post_sid="$(awk '$6=="agent_sid"{print $7}' "$WORK/jar.txt" || true)"
if [ -z "$post_sid" ]; then
  echo "FAIL: could not obtain a post-login agent_sid cookie"
  exit 2
fi
echo "POST_SID = ${post_sid:0:24}... (truncated)"

# ---- Step 3: compare ----
echo
if [ "$pre_sid" = "$post_sid" ]; then
  echo "FINDING: session ID did NOT rotate on auth (fixation possible)."
  if [ "$EXPECT_FAIL" = "1" ]; then
    echo "Status: expected-vulnerable (SESSION_FIXATION_EXPECT_FAIL=1)."
    echo "Action: patch gui/agent-app/server/index.ts /auth/callback/wso2is"
    echo "        to call req.session.regenerate() before assigning user."
    exit 0
  fi
  exit 1
fi
echo "PASS: session ID rotated on auth (no fixation)."
exit 0
