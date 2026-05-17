#!/usr/bin/env bash
# tests/monitoring/alert-on-failure.sh
#
# Invoked by quick-smoke.service when quick-smoke.sh exits non-zero.
#
# In the teaching lab, "alert" is a structured log line. The hooks for
# paging integrations (PagerDuty, Slack webhook, Opsgenie) are stubbed
# below -- uncomment, set the env var, and they're live.
#
# Receives:
#   $1 = exit code from quick-smoke.sh
#   stdin = stderr from quick-smoke.sh (the per-failure detail lines)
#
# Writes:
#   /home/ze/insurance-app/logs/synthetic-monitor.log  (always)
#   $SLACK_WEBHOOK / $PAGERDUTY_KEY                    (if configured)

set +e
EXIT_CODE="${1:-?}"
LOG="${SYNTHETIC_LOG:-/home/ze/insurance-app/logs/synthetic-monitor.log}"
mkdir -p "$(dirname "$LOG")"

# Drain stderr from quick-smoke.sh into a single-line summary.
DETAIL=$(cat | tr '\n' ';' | sed 's/;$//')
TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
HOSTNAME=$(hostname)

# Always log.
printf '%s host=%s exit=%s detail="%s"\n' \
  "$TS" "$HOSTNAME" "$EXIT_CODE" "$DETAIL" >> "$LOG"

# ---- Pager integrations: opt-in via env vars. Stubbed for the lab. ----

# Slack incoming webhook.
# Set SLACK_WEBHOOK="https://hooks.slack.com/services/..." in
# ~/.config/systemd/user/quick-smoke.service.d/secrets.conf  (mode 0600).
if [ -n "${SLACK_WEBHOOK:-}" ]; then
  curl -sf -X POST -H 'Content-Type: application/json' \
    --max-time 5 \
    -d "$(jq -nc --arg t ":rotating_light: insurance-app synthetic FAIL on $HOSTNAME ($EXIT_CODE checks): $DETAIL" '{text:$t}')" \
    "$SLACK_WEBHOOK" >/dev/null || true
fi

# PagerDuty Events API v2.
# Set PAGERDUTY_KEY="..." in the same drop-in.
if [ -n "${PAGERDUTY_KEY:-}" ]; then
  curl -sf -X POST https://events.pagerduty.com/v2/enqueue \
    --max-time 5 \
    -H 'Content-Type: application/json' \
    -d "$(jq -nc \
      --arg key "$PAGERDUTY_KEY" \
      --arg sum "insurance-app synthetic FAIL ($EXIT_CODE checks)" \
      --arg src "$HOSTNAME" \
      --arg det "$DETAIL" \
      '{
        routing_key: $key,
        event_action: "trigger",
        dedup_key: ("insurance-app-synthetic-" + $src),
        payload: {
          summary: $sum,
          source: $src,
          severity: "error",
          custom_details: {detail: $det}
        }
      }')" >/dev/null || true
fi

# Always exit 0 -- alert delivery failure should not loop the service into
# more failure noise.
exit 0
