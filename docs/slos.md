# Service-level objectives

This document is the **consolidated SLO register** for the insurance-app teaching deployment. It collects every measurable user-facing service objective in one place, names an owner per objective, and points at where the objective is actually measured.

Latency targets and the money-chain SLIs already exist in [`docs/performance-budgets.md`](performance-budgets.md) (Phase 3). This document **references** that table — it does not duplicate it. What's new here is the portal-availability and sign-in SLOs, the ownership column, and the link from each SLO to its measurement source.

## SLO register

> Compliance window: **30-day rolling** unless stated otherwise.
> "Owner" is the on-call human responsible for the SLO. `@TBD` while the rota is informal.

### Money chain (API)

| ID | SLO | Target | Window | Owner | Measured where |
|---|---|---|---|---|---|
| MC-1 | Money-chain **availability** (POST /api/quotes, /api/policies, /api/payments) returns ≤ 4xx excluding validation 4xx | **≥ 99.5%** | 30d | `@TBD` | SigNoz dashboard "money-chain", computed from `http.server.request.duration` count grouped by status |
| MC-2 | Money-chain **latency** — fraction of money-chain requests meeting the per-endpoint p95 target from `performance-budgets.md` | **≥ 99.0%** | 30d | `@TBD` | Same SigNoz dashboard; latency bucket histogram |
| MC-3 | `GET /api/quotes/{id}` p95 | **≤ 50 ms** | 30d | `@TBD` | SigNoz, `http.server.request.duration` with `http.route="/api/quotes/{id}"` and method GET |

Per-endpoint latency targets live in `performance-budgets.md` table "Per-endpoint targets" — that table is the source of truth for the per-request bound; MC-2 is the *aggregate* SLO over it.

### Portal availability (separate from API)

The portals (SvelteKit BFF for customer, React + Express BFF for agent) are independent user journeys. A customer portal outage with a fully-healthy API is still a customer-facing outage, so these get their own SLOs.

| ID | SLO | Target | Window | Owner | Measured where |
|---|---|---|---|---|---|
| PT-1 | **customer-app availability** — `GET https://my.insurance-app.comptech-lab.com/` returns 200 | **≥ 99.5%** | 30d | `@TBD` | Synthetic monitor (`tests/monitoring/quick-smoke.sh`, public-subdomain checks for `my`) -- count of `ok` vs `FAIL` log lines; plus SigNoz instrumentation on the SvelteKit `hooks.server.ts` |
| PT-2 | **agent-app availability** — `GET https://agent.insurance-app.comptech-lab.com/` returns 200 | **≥ 99.5%** | 30d | `@TBD` | Synthetic monitor public-subdomain check for `agent`; plus Express middleware traces in SigNoz |
| PT-3 | **Public-page availability** (any of the 13 tour subdomains) | **≥ 99.9%** | 30d | `@TBD` | Synthetic monitor: HEAD check on each of `app/signoz/minio/kafka/mail/search/is/apim/gateway/redis/my/agent` (12 wired; `register` deferred per ADR-0007) — already in `docs/performance-budgets.md` SLI table, restated here for completeness |

PT-1 / PT-2 are *intentionally* lower than PT-3: a portal failure that's bypassable (user can call our API directly with a token) is less severe than a public-page failure that affects every visitor.

### Sign-in flow

The OIDC sign-in journey is its own user journey, distinct from "the portal renders." A working portal with a broken sign-in is still a customer-facing outage.

| ID | SLO | Target | Window | Owner | Measured where |
|---|---|---|---|---|---|
| SI-1 | **OIDC sign-in success rate** (customer-app): full code-flow handshake for known-valid creds completes | **≥ 99.0%** | 30d | `@TBD` | E2E Playwright suite in `e2e/` (gated runs); SigNoz instrumentation on `customer-app` `/auth/callback` route success vs error |
| SI-2 | **OIDC authorize redirect availability** — `POST /auth/signin/wso2is` returns a 302 to `is.insurance-app.comptech-lab.com/oauth2/authorize` with PKCE + state | **≥ 99.5%** | 30d | `@TBD` | Synthetic monitor extension (planned: extend `quick-smoke.sh` to assert the redirect target rather than just liveness) |
| SI-3 | **agent-app OIDC sign-in success rate** (agent portal, separate IS client) | **≥ 99.0%** | 30d | `@TBD` | Same as SI-1 but for the agent client_id; tracked separately because the agent client has different scope + redirect_uri whitelist |

We deliberately split SI-1 and SI-3 because they exercise different IS apps and different BFF code paths (Auth.js for customer, `openid-client` + Redis for agent). A regression in one shouldn't be masked by the other.

### Data durability (linked to RTO/RPO)

| ID | SLO | Target | Window | Owner | Measured where |
|---|---|---|---|---|---|
| DR-1 | **Postgres RTO** (Postgres unreachable -> Postgres serving from latest snapshot) | **≤ 1 h** | per incident | `@TBD` | Drill table in `docs/runbooks/disaster-recovery.md`; verified 2026-05-17 at 59 s |
| DR-2 | **Postgres RPO** (max wall-clock data loss in a restore scenario) | **≤ 5 min** (with hourly snapshots: best-effort 1 h until WAL archive lands) | per incident | `@TBD` | Snapshot timer cadence; the 5 min target depends on Phase 7 WAL archive work |
| DR-3 | **MinIO RTO / RPO** | **≤ 4 h / ≤ 1 h** | per incident | `@TBD` | Same DR runbook |
| DR-4 | **Kafka compacted-topic RPO** (audit-events, policy-events) | **0** (full replay from snapshot) | per incident | `@TBD` | Same DR runbook; verified by restore-into-scratch's offset-sum check |

Full RTO/RPO context lives in `docs/runbooks/disaster-recovery.md`.

## Error-budget policy

Identical to `performance-budgets.md` — repeating the table here for the alert thresholds.

| Burn rate | Window meaning | Response | Runbook |
|---|---|---|---|
| **< 1×** | Budget is being spent at or below the long-window rate | Normal operations | none |
| **1× – 5×** | Burning faster than 30-day budget allows | Investigate, file `qa:performance`, do not pause | `docs/runbooks/slo-burn-response.md` § "1× burn" |
| **5× – 14×** | Will exhaust the month in days | **Pause feature work** in the affected service; investigate; escalate | `docs/runbooks/slo-burn-response.md` § "5× burn" |
| **≥ 14×** | Will exhaust the month in <24 h | **Page** + roll back latest change + halt deploys | `docs/runbooks/slo-burn-response.md` § "14× burn" |

The concrete SigNoz alert configuration that implements this table is `compose/infra/signoz/alert-rules.yml`.

## How SLOs become alerts

For each (SLI, SLO) pair we configure **two multi-window multi-burn-rate** Prometheus-style alert rules in SigNoz (one fast window for early detection, one slower window to filter out spikes). The rules live in `compose/infra/signoz/alert-rules.yml`.

The math:

```
# For an availability SLO with target T (e.g. T = 0.995, so budget = 0.005):
#   instantaneous burn rate over window W
#     = (1 - success_ratio(W)) / (1 - T)
#
# A page-worthy 14x burn over a 1h window means:
#   (1 - success_ratio(1h)) / 0.005 >= 14
#   <=> success_ratio(1h) <= 1 - 14*0.005 = 0.930
#
# That's the threshold in the alert rule. The 5x burn over 6h is the
# slow-rate confirmer: (1 - success_ratio(6h)) / 0.005 >= 5  =>  ratio <= 0.975
```

Multi-window: short window catches the spike, long window catches sustained burn. Both must fire to alert -- avoids paging on a brief burst.

## What's not yet measured (debt)

| SLO | Status | Tracking |
|---|---|---|
| MC-2 — money-chain latency | Latency is *recorded* by SigNoz; the *aggregate ratio* is not yet a panel/alert. **TODO** in Phase 6 follow-up. | Open issue: signoz dashboard "money-chain latency conformance" |
| SI-1 / SI-3 — sign-in success rate | Manual E2E only. Need the customer-app and agent-app BFFs to emit a structured `auth.callback.success` / `auth.callback.failure` span attribute. | Tracked under the agent-dashboard pattern memory note |
| Multipart claim upload latency | Not in k6 baseline (per `performance-budgets.md`). | Phase 7 baseline rerun |

These gaps are knowingly accepted; they're listed so future incident reviewers don't think the SLO file is lying.

## Cross-references

- Latency budgets per endpoint: `docs/performance-budgets.md` -- the source of truth for MC-2 / MC-3.
- Burn-rate response runbook: `docs/runbooks/slo-burn-response.md`.
- Concrete alert configuration: `compose/infra/signoz/alert-rules.yml`.
- Disaster recovery (DR-1..DR-4): `docs/runbooks/disaster-recovery.md`.
- Synthetic monitor that feeds PT-* and the sign-in liveness checks: `docs/runbooks/synthetic-monitoring.md`.
