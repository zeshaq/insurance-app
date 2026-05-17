# Synthetic monitoring runbook

How the `insurance-app` lab is poked from outside-the-app every 60s, what fires when the pokes fail, and how to add or silence checks.

## Architecture

```
   systemd timer (60s)
        |
        v
   quick-smoke.service  --invokes-->  tests/monitoring/quick-smoke.sh
        |                                          |
        |                                          | exit code
        |                                          v
        +-- on non-zero -->  alert-on-failure.sh  ->  log + (optional) Slack / PD
                                  |
                                  v
                       ~/insurance-app/logs/synthetic-monitor.log
```

`scripts/smoke.sh` is the *full* (~30s, ~80-check) end-to-end test we run before merges and after a deploy. `quick-smoke.sh` is the *fast* (~400 ms) subset we run every minute to catch outages within the SLO budget.

## What `quick-smoke.sh` covers (and doesn't)

| Check | Why it's in the 60s loop |
|---|---|
| `GET /api/ping` on Liberty | Confirms the API container is up and the JAX-RS pipeline is wired |
| Postgres `select 1` (via `/api/health/db`, falls back to `podman exec`) | Confirms the durable store is reachable |
| Quote round-trip: `POST /api/quotes` + `GET /api/quotes/{id}` | The most load-bearing happy path. One request exercises Postgres write + Redis cache + Kafka producer |
| HEAD on 12 public subdomains (`app`, `signoz`, `minio`, `kafka`, `mail`, `search`, `is`, `apim`, `gateway`, `redis`, `my`, `agent`) | Confirms the Cloudflare -> HAProxy -> origin path is intact for every public name; parallel so wall-clock = slowest, not sum |

What's **deliberately excluded**:

| Skipped | Why |
|---|---|
| Credit-bureau lookup via MI + WireMock | ~2 s on its own (mTLS handshake, JKS load). Adds half the budget for a check that's already covered by `smoke.sh`. |
| Debezium / OpenSearch CDC | Async, eventually-consistent, not suited for a 60 s window |
| customer-app `POST /quote` form-action | **BROKEN since Phase 4 re-enabled CSRF** — the form-action 403s without a SvelteKit-generated CSRF token. A curl POST cannot easily mint one. Tracked separately under the customer-app CSRF issue. Including it here would be papering over a real failure. |
| Spike / load scenarios | By definition not a monitor; live in `load/` |

The issue spec mentions 13 subdomains; only 12 are wired through HAProxy + Cloudflare today (the 13th `register.*` from ADR-0007 is not yet brought up). Adjust the `HOSTS=()` array in `quick-smoke.sh` when it lands.

## Install

```
# One-time, from the VM:
cd ~/insurance-app/tests/monitoring
chmod +x quick-smoke.sh alert-on-failure.sh

mkdir -p ~/.config/systemd/user
cp quick-smoke.service quick-smoke.timer ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now quick-smoke.timer
```

That's it. Logs land in `~/insurance-app/logs/synthetic-monitor.log` (override with `SYNTHETIC_LOG=` in the service drop-in).

`loginctl enable-linger ze` (already configured) keeps the timer running across SSH disconnects. Check with `loginctl show-user ze | grep Linger`.

## Observe

```
# Is the timer alive and when does it next fire?
systemctl --user list-timers quick-smoke.timer

# What did each run do?
journalctl --user -u quick-smoke.service --since '10 minutes ago'

# Just the one-line summaries:
tail -F ~/insurance-app/logs/synthetic-monitor.log

# A single run, manually:
~/insurance-app/tests/monitoring/quick-smoke.sh ; echo "exit=$?"
```

## Confirmed timing (2026-05-17 install)

On VM `insurance-app-vm`, three consecutive 60 s cycles:

```
NEXT                        LEFT LAST                         PASSED UNIT
Sun 2026-05-17 18:27:02 UTC  34s Sun 2026-05-17 18:26:02 UTC 25s ago quick-smoke.timer

ok ts=2026-05-17T18:24:00Z elapsed_ms=394 checks=pass
ok ts=2026-05-17T18:25:01Z elapsed_ms=405 checks=pass
ok ts=2026-05-17T18:26:02Z elapsed_ms=391 checks=pass
```

Wall-clock is ~400 ms; cadence drift is ±1 s (systemd accuracy bound).

## Uninstall

```
systemctl --user disable --now quick-smoke.timer
rm ~/.config/systemd/user/quick-smoke.{service,timer}
systemctl --user daemon-reload
```

The log file is preserved.

## Add a new check

Three knobs:

1. **A new local service health check** — append to `quick-smoke.sh` between the existing sections; keep wall-clock under 10 s total. Pattern: a `curl -sf --max-time 3 <url>` followed by `[ "$result" = expected ] || fail_check "<name>"`.

2. **A new public subdomain** — add to the `HOSTS=()` array. The parallel xargs loop handles fan-out automatically.

3. **A different alert sink** — the alert script reads `SLACK_WEBHOOK` and `PAGERDUTY_KEY` from the env. Add a drop-in at `~/.config/systemd/user/quick-smoke.service.d/secrets.conf` (mode 0600) with `Environment=SLACK_WEBHOOK=https://hooks.slack.com/services/...`. After editing: `systemctl --user daemon-reload`. Both integrations are stubs in `alert-on-failure.sh` — uncomment / extend as needed.

## Silence alerts during planned maintenance

Two options, depending on duration:

* **Short window (under 1 h)**: stop the timer.
  ```
  systemctl --user stop quick-smoke.timer
  # ...do the maintenance...
  systemctl --user start quick-smoke.timer
  ```
  The timer is `Persistent=false`, so no back-fill on resume.

* **Longer windows or scheduled maintenance**: drop a sentinel file and gate the script.
  ```
  touch ~/insurance-app/MAINTENANCE
  ```
  Then add at the top of `quick-smoke.sh`:
  ```bash
  [ -f "$HOME/insurance-app/MAINTENANCE" ] && {
    echo "skip ts=$(date -u +%Y-%m-%dT%H:%M:%SZ) reason=maintenance"; exit 0; }
  ```
  Remove the file to resume.

## Cross-references

- Full smoke: `scripts/smoke.sh`
- Quick smoke: `tests/monitoring/quick-smoke.sh`
- Systemd units: `tests/monitoring/quick-smoke.{service,timer}`
- Alert delivery: `tests/monitoring/alert-on-failure.sh`
- SLOs that this monitor proves: `docs/slos.md`
- Phase 4 CSRF re-enable: see `docs/runbooks/jwt-key-rotation.md` neighbours (the slice 19 reverts).
