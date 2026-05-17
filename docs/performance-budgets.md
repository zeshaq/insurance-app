# Performance budgets & SLO targets

This document records the latency / availability targets each user-facing endpoint must meet, the methodology for measuring them, and the error-budget policy when they're breached. Numbers below come from the **Phase 3 baseline run** against live staging on 2026-05-17 (commit `765d3ea`).

Source of truth for the measurements: `load/baseline.js`, `load/soak.js`, `load/spike.js`. Re-run any time via `npx grafana-k6-action` or directly with `k6 run load/baseline.js` on the VM. CI runs a capped baseline on every PR; heavy scenarios run via `workflow_dispatch`.

## Per-endpoint targets

Latency targets are intentionally tight in absolute terms (this is a single-VM lab, not a multi-region production) but their *ratios* reflect what a production deployment would target.

| Endpoint | Today (p50 / p95 / p99 baseline) | Target p95 | Target p99 | Notes |
|---|---|---|---|---|
| `POST /api/quotes`     | 11.3 / 30.4 / 44.1 ms | **≤ 120 ms** | **≤ 300 ms** | Includes Redis cache write + Kafka publish. The 30-ms p95 is dominated by Redis + Postgres flush; Kafka is async. |
| `POST /api/policies`   |  8.4 / 21.5 / 31.9 ms | **≤ 150 ms** | **≤ 400 ms** | Redlock distributed locking is the load-bearing latency contributor under contention; the baseline doesn't exercise contention. |
| `POST /api/payments`   | 13.3 / 30.5 / 43.0 ms | **≤ 200 ms** | **≤ 600 ms** | Includes the synchronous gateway round-trip. p99 is sensitive to `@Retry` firing — every retry adds ~50 ms. |
| `GET  /api/quotes/{id}` | not measured in baseline (read path; covered by smoke) | **≤ 50 ms** | **≤ 150 ms** | Should be served entirely from Redis cache once warm. |
| `POST /api/claims` (multipart)   | not in money-chain k6 baseline | **≤ 800 ms** | **≤ 3000 ms** | Multipart upload to MinIO + OCR via MI; size-dependent. Re-baseline with realistic attachment sizes. |
| `GET  /api/policies?limit=N` (list) | not measured | **≤ 100 ms** | **≤ 300 ms** | Used by the customer portal /policies page; N≤50. |

## SLI / SLO definitions

| SLI | Definition | SLO |
|---|---|---|
| **Money-chain availability** | Fraction of (quote, policy, payment) requests over a 30-day window returning ≤ 4xx (excluding 4xx-from-input-validation). | **≥ 99.5%** (≈ 3.6 h / month error budget) |
| **Money-chain latency** | Fraction of (quote, policy, payment) requests meeting their per-endpoint p95 target over the same window. | **≥ 99.0%** (≈ 7.2 h / month error budget) |
| **Public-page availability** | Fraction of GETs to the teaching tour pages and the customer / agent portal landing pages returning 200. | **≥ 99.9%** (≈ 43 m / month error budget) |
| **OIDC sign-in success rate** | Fraction of OIDC code-flow handshakes (POST /auth/signin -> callback completes) succeeding for known-valid creds. | **≥ 99.0%** |

The two money-chain SLOs together imply a 99.5% × 99.0% ≈ **98.5% end-to-end success-meeting-latency rate** — that's the headline number for the teaching deployment.

## Error-budget policy

The error budget is the *complement* of the SLO target over a 30-day rolling window. Burning the budget triggers escalating responses:

| Burn rate | What it means | Response |
|---|---|---|
| < 1× | Normal operations | None. Ship features. |
| 1×–5× | Burning faster than 30-day budget allows | Investigate but don't pause. Add a tracking issue under `qa:performance`. |
| 5×–14× | Will exhaust the month's budget in days, not weeks | **Pause feature work in the affected service.** Investigate. Escalate. |
| ≥ 14× | Will exhaust the month's budget in <24h | Immediate page. Roll back the most recent change. Halt feature deploys. |

Burn-rate alerts in Phase 6 (the SigNoz wiring under `qa:ops`) read directly from this table.

## Methodology

- **Baseline measurement**: `load/baseline.js` — 1 → 10 → 100 VUs ramp over 2m35s. Run weekly via `workflow_dispatch` and any time the API surface changes. The PR-on-push variant runs with capped VUs (1/5/10) so CI stays under 3 minutes.
- **Soak**: `load/soak.js` — 10 VUs sustained. Default 5m for CI; **`DURATION=24h k6 run load/soak.js`** on the VM under `nohup` is the real long-soak test, repeated quarterly or after any significant runtime change (Liberty bump, JVM bump, Kafka client major bump, etc.). Captures slow leaks, connection-pool drift, managed-executor starvation.
- **Spike**: `load/spike.js` — 0 → 500 VUs in 30s. Validates `@Retry` / circuit-breaker / rate-limit behavior under sudden traffic. Run on-demand before any expected traffic surge.

## Recorded results

### 2026-05-17 baseline (commit `765d3ea`)

| Scenario | Requests | Errors (excl. 429) | Global p50 / p95 / p99 |
|---|---|---|---|
| baseline (1→10→100 VUs, 2m35s) | 81,535 | 0 (**0.000%**) | 10.96 / 28.26 / 41.01 ms |
| soak (10 VUs / 5m proxy)       |  8,800 | 0 (**0.000%**) |  7.64 / 11.85 / 20.46 ms |
| spike (0→500→0, 2m15s)         | 13,837 | 611 (**4.58%**) | 30.23 / 27,691 / 50,001 ms |

Per-endpoint, baseline:

| Endpoint | p50 / p95 / p99 |
|---|---|
| `/api/quotes`   | 11.33 / 30.38 / 44.10 ms |
| `/api/policies` |  8.40 / 21.49 / 31.88 ms |
| `/api/payments` | 13.34 / 30.47 / 42.97 ms |

**Spike findings (worth a follow-up):**
- First 5xx onset at t+39s — squarely at the end of the 30s ramp-up to 500 VUs.
- 5xx breakdown: 265× HTTP 500, 454× HTTP 504 (gateway timeout from the simulated payment gateway).
- `/api/quotes` takes the brunt: 219× 500 came from it. `/api/policies` got zero 5xx because chains that 500 on quote never reach the bind step.
- A back-to-back rerun of the same spike scenario without a recovery window logged 22.76% errors — Liberty had not fully recovered. **Treat spike as a destructive test**; don't run it twice without restarting Liberty in between.

**Notes on what isn't measured:**
- 24h soak intent not validated. Memory-leak / connection-pool-exhaustion behaviour at that timescale is unknown. Run quarterly.
- Rate limit (5 req/min/VIN on `/api/quotes`) is documented in ADR-0005 but the baseline runs use unique VINs per iteration so the limit was never hit. A separate VIN-collision-shaped scenario would confirm the 429 onset point.
- Multipart claim filing (`POST /api/claims`) not in the baseline. Realistic file sizes (1–10 MB) significantly affect p99; should be its own scenario.

## Cross-references

- Roadmap context: [`docs/qa-roadmap.md`](qa-roadmap.md) — Phase 3.
- Phase 3 GitHub milestone: [milestone #4](https://github.com/zeshaq/insurance-app/milestone/4) (closed when this doc lands).
- Live scenarios: `load/baseline.js`, `load/soak.js`, `load/spike.js`.
- CI: `.github/workflows/k6.yml`.
- Phase 6 will wire SigNoz alerts to the burn-rate table above.
