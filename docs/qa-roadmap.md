# QA & Go-Live Roadmap

This document captures the testing, security, and operational work required to take `insurance-app` from teaching artifact to production-ready. It is the durable narrative; the executable work lives in GitHub Milestones + Issues filed against this repo.

The work was split into seven phases (plus a small 0.5 hygiene phase that emerged mid-flight), executed in order. Each phase has a milestone of the same name. Issues are tagged with workstream labels (`qa:*`) so a single workstream — security, performance, etc. — can be sliced across phases.

**Current state (2026-05-18): ROADMAP COMPLETE.** All eight milestones closed; every executable phase is live in `main`. See the [completion summary](#completion-summary-2026-05-18) at the bottom for what landed in each phase. This document is preserved as the historical narrative; new testing/QA work that doesn't fit an existing phase opens a new milestone.

## Top four quick wins

If only four things are done before launch, they should be these — they catch the cheap classes of bug before the expensive ones, and each one unblocks the next:

1. **SAST + SCA + secrets scanning in CI** — Dependabot, Trivy, Semgrep, gitleaks. Phase 0.
2. **Playwright browser tests for the OIDC click-through** — closes the only verification gap in the current smoke. Phase 2.
3. **k6 load test of `quote → bind → pay`** — answers whether the current single-VM setup survives a marketing launch. Phase 3.
4. **Third-party penetration test** — book 6–8 weeks before launch; their findings always need time to fix. Phase 4.

## Phase 0 — Foundations

**Goal:** stand up the cheap, always-on protections. Output is an honest security baseline against which every later change is measured.

**Scope:**
- GitHub Dependabot enabled for Maven, npm (customer-app + agent-app), and the Containerfile base images.
- Pre-commit + CI gate for committed secrets (`gitleaks`).
- Container image scanning in CI (`Trivy`) for every image produced (`insurance-app`, `customer-app`, `agent-app`).
- SAST baseline (Semgrep cloud or SonarQube Cloud) across Java + TypeScript.
- A short `docs/security-baseline.md` documenting what the scanners say today, even if the answer is "X high-severity findings."

**Done when:** every PR runs Dependabot, gitleaks, Trivy, and SAST automatically, and the security baseline doc is in `main`.

**Labels:** `qa:foundations`, `qa:security`.

## Phase 1 — Unit & Integration Tests

**Goal:** every failure can be isolated to one layer. The smoke script proxies for this today, but it can't tell you whether a `POST /api/quotes -> 201` failure is JPA, Redis, Kafka, or business logic. Unit + integration tests can.

**Scope:**
- JUnit 5 + Mockito scaffolding in `insurance-app`; the Liberty service layer (`QuoteService`, `PolicyService`, `PaymentService`, `ClaimService`) gets unit coverage with a ≥70% line target.
- Testcontainers integration tests against real PostgreSQL, Kafka, and Redis containers — exercising the persistence, cache, and event-emission edges that mocks can't safely fake.
- Vitest scaffolding for `gui/customer-app/` and `gui/agent-app/` — the BFF helpers (`src/lib/server/liberty.ts`, `server/index.ts`) have non-trivial JSON-vs-FormData branches that have never been tested in isolation.
- Coverage gate in CI: PRs below the threshold fail.

**Done when:** unit + integration suites run on every PR, coverage is enforced, and the smoke script is no longer the only thing that knows whether the app works.

**Labels:** `qa:unit`, `qa:integration`.

## Phase 2 — Contract & E2E Tests

**Goal:** lock in the API surface so the BFFs and Liberty can't drift apart silently. Add a browser-driven layer so the OIDC click-through is part of CI instead of a manual checklist.

**Scope:**
- Generate an OpenAPI spec from Liberty's `@Path` + `microprofile-openapi` annotations; publish it on every build.
- `Schemathesis` property-based fuzzing against the OpenAPI spec — catches mass-assignment, type-confusion, and authorization bugs.
- Pact contract tests between the BFFs (consumer) and Liberty (provider). Customer-app ↔ Liberty and agent-app ↔ Liberty are two separate contracts.
- Playwright end-to-end tests for the customer portal — the OIDC login form is HTML+JS, so curl can't drive it, but Playwright can. Covers quote → bind → pay → file-claim.
- Playwright end-to-end tests for the agent dashboard — sign in, filter to FILED, click Approve, verify the claim flips to APPROVED.

**Done when:** schema drift causes a CI failure, and the OIDC click-through runs in CI instead of being a manual smoke step.

**Labels:** `qa:contract`, `qa:e2e`.

## Phase 3 — Performance

**Goal:** quantify the load the current architecture survives, and define budgets the team agrees on.

**Scope:**
- `k6` scenario for the canonical happy path: `quote → bind → pay`. Baseline runs at 1, 10, 100 concurrent users. Capture p50 / p95 / p99 latency and the error rate.
- Soak test: 10% of peak load for 24h. Watches for memory leaks, connection-pool exhaustion, Liberty thread starvation, Kafka consumer lag.
- Spike test: 0 → 5× peak in 30s. Validates `@Retry` + circuit-breaker behavior. The current single-VM setup will probably fail here — that's the point; you'll know what to scale out.
- Documented performance budgets and SLIs: target p95 for each endpoint, target error budget per quarter.

**Done when:** load profile is known, budgets are written down, and the alerting in Phase 6 has concrete numbers to fire on.

**Labels:** `qa:performance`, `qa:ops`.

## Phase 4 — Deeper Security

**Goal:** beyond Phase 0's scanners — find the bugs that scanners can't see, and book the third-party work that has to start early.

**Scope:**
- Re-enable SvelteKit's CSRF cross-origin check (`kit.csrf.checkOrigin`). Currently disabled in `gui/customer-app/svelte.config.js` for the teaching demo; not acceptable in production.
- DAST: OWASP ZAP baseline scan against a staging environment, focused on the OWASP API Top 10 (BOLA, broken auth, mass assignment, broken object property authorization).
- Auth-specific tests: PKCE replay, refresh-token rotation, JWT signing-key rotation, session fixation, CSRF on the SvelteKit form actions.
- JWT signing-key rotation runbook + test. Today the key is implicit; rotate without downtime is unproven.
- Third-party penetration test — booked during this phase, executed at the end of Phase 5. Allow 6–8 weeks for booking + remediation.

**Done when:** ZAP runs clean against staging, auth-specific tests are part of CI, and the pen test engagement is scheduled.

**Labels:** `qa:security`, `qa:compliance`.

## Phase 5 — Resilience / Chaos

**Goal:** prove the unhappy paths. The smoke verifies idempotency in the happy path; production needs adverse-path proof.

**Scope:**
- Kill Liberty mid-`@Transactional` during a bind — verify no orphan rows in `policy`.
- Kill the Postgres primary during a bind — verify the Redlock distributed lock holds and the request fails clean.
- Kill the Kafka broker during a payment — verify producer retries don't double-publish; verify consumer offsets resume correctly after restart.
- Partition WSO2 IS from the BFFs — verify the `client_credentials` token cache degrades gracefully and reconnects.
- Fill MinIO disk during a multipart claim upload — verify no half-stored objects survive.

**Done when:** each scenario has a documented expected behavior, an automated drill, and a runbook for the on-call engineer.

**Labels:** `qa:resilience`, `qa:ops`.

## Phase 6 — Compliance & Production Ops

**Goal:** the "ready to take real money" gate. Most of this is insurance-specific and easy to overlook because none of it shows up in code.

**Scope:**
- Regulatory jurisdiction analysis. Which insurance regulators apply? Different requirements between US (state-by-state), UK FCA, IRDAI in India, BSEC/IDRA in Bangladesh. Output is a written gap analysis.
- PII data-flow diagram and retention policy. Every API that touches customer data needs documented data classification, retention window, and a "right to deletion" test (GDPR Art. 17 and equivalents).
- Audit-trail completeness tests. The `audit-events` topic exists; prove every state transition (Quote calculated, Policy bound, Payment processed, Claim filed/approved/rejected) emits an audit event.
- Flyway rollback procedure. Every migration in `src/main/resources/db/migration/` gets a rollback script and a dry-run against a prod-sized DB copy.
- Backup + restore drill: actually delete the DB in staging and prove RTO/RPO from backup.
- Synthetic monitoring: `scripts/smoke.sh` running in prod every 60 seconds; failures page the on-call.
- SLO definition + error budget policy: per-endpoint availability and latency targets, error-budget burn-rate alerts.

**Done when:** the regulator-facing documentation is in `docs/compliance/`, audit-trail tests are in CI, backup/restore drill has been executed at least once, and SLO dashboards are live.

**Labels:** `qa:compliance`, `qa:ops`.

## Completion summary (2026-05-18)

Each milestone closed. Headline numbers from each phase, drawn from the live commit history (`git log --oneline --grep='Closes #' main`) and the closing comments on each milestone:

| Phase | Milestone | Verified outcome |
|---|---|---|
| **0** | Foundations | Dependabot + gitleaks + Trivy + Semgrep + JaCoCo + Vitest scanners running on every PR. Snapshot captured in `docs/security-baseline.md`. |
| **0.5** | Dependency hygiene | 7 of 13 deferred Dependabot PRs merged in-session; 6 deferred for code work (later resolved — see follow-ups). |
| **1** | Unit & Integration | JUnit5 + Mockito + Testcontainers + Vitest in place. Coverage ratchet: QuoteService 100%, PaymentService 97.9%, liberty.ts 97.3% (customer-app), server/index.ts 63.75% (agent-app). |
| **2** | Contract & E2E | OpenAPI at `/openapi`. Schemathesis fuzzing on every PR (surfaced 5 real 500s, all fixed). Pact contracts both BFFs ↔ Liberty. Playwright real OIDC click-through both portals, **green in 13.8s**. |
| **3** | Performance | k6 baseline (100 VUs, p99=41ms, 0% errors), soak (5m proxy for 24h), spike (500 VUs, 4.58% errors). SLO targets + error-budget burn-rate policy in `docs/performance-budgets.md`. |
| **4** | Deeper Security | SvelteKit CSRF re-enabled. OWASP ZAP DAST workflow. PKCE-replay / refresh-rotation / session-fixation tests against staging. JWT rotation runbook + dry-run. Pen-test vendor prep doc. |
| **5** | Resilience / Chaos | 5 destructive drills, all PASS multiple iterations. Kill Liberty mid-tx, kill Postgres mid-bind, kill Kafka mid-payment, partition WSO2 IS, MinIO disk-full. Per-drill runbooks. |
| **6** | Compliance & Prod Ops | Regulatory jurisdiction analysis (8 jurisdictions). PII data-flow + retention. Audit-trail completeness test (surfaced 3 missing emissions, fixed in same commit). Flyway rollback docs ×7. Backup/restore drill (~59s end-to-end RTO measured). 60s systemd-timer synthetic monitor on the VM. SigNoz burn-rate alert rules. |

### Follow-up work resolved post-roadmap (2026-05-18 debt-fix session)

* `openid-client` 5→6 (#56), `connect-redis` 8→9 (#57), `kafka-clients` 3.9→4.2 (#58), `kafka-streams` 3.9→4.2 (#59), `io.minio` 8→9 (#60), `flyway` 10→11.8.2 (#61) — all major-version dep bumps that Phase 0.5 deferred. Six of six closed in-session.
* Bean Validation pattern (originally #62) extended to PolicyRequest + PaymentRequest.
* Synthetic-monitor unbounded-growth caveat from issue #35 closed via SYNMON-prefixed VINs + a daily prune timer.

### Genuinely open / not-actionable-here

* **#61b**: Flyway 12.x → blocked by a broader Jackson 2 → 3 migration across the app's transitive deps. Tracked as a stretch future improvement; 11.x is in `main` and works.
* **#63**: MinIO partial multipart cleanup → upstream-limited. The MinIO server build we run silently strips the `AbortIncompleteMultipartUpload` lifecycle field on import. Reopening requires a MinIO server image bump.
* **Phase 4 / #24** pen-test vendor selection → out-of-band human task (vendor RFP, contract). The engagement prep doc is in `docs/compliance/pen-test-vendor-prep.md`.

### What stays alive forever (not "done", but ongoing)

These run on a cadence rather than being one-time deliverables:

* **Synthetic monitor**: user systemd timer fires `tests/monitoring/quick-smoke.sh` every 60s on the VM; failures land in `~/insurance-app/logs/synthetic-monitor.log`. Daily prune via `prune-synthetic.timer`.
* **24-hour soak**: should be run quarterly or after any significant runtime change (Liberty bump, JVM bump, etc.). Command: `DURATION=24h k6 run load/soak.js` on the VM.
* **Spike scenario** (`load/spike.js`): on-demand before any expected traffic surge.
* **OWASP ZAP baseline DAST**: weekly schedule (Mondays 06:30 UTC) plus `workflow_dispatch` for on-demand runs after security-sensitive changes.
* **Backup/restore drill** (`tests/backup/snapshot-all.sh` + `tests/backup/restore-into-scratch.sh`): rehearse on a quarterly cadence at minimum.
* **`docs/security-baseline.md` refresh**: when major scanner changes land or quarterly.

## Cross-references

- Existing end-to-end coverage: `scripts/smoke.sh` (currently 205 checks).
- ADRs that constrain this work: `docs/adr/` — particularly ADR-0007 (DNS/HAProxy/TLS public exposure) for staging endpoints, and the WSO2 IS / mpJwt deployment notes captured in commit history.
- Architectural assumptions captured in commit history: see `gui/customer-app/src/auth.ts` and `gui/agent-app/server/index.ts` for the BFF pattern; `src/main/liberty/config/server.xml` for the four-element mpJwt stack.

## Maintenance

This document is updated when:
- A phase opens (mark current scope).
- A phase closes (link to the milestone, record any scope deferred to a later phase).
- A new layer of testing is added that doesn't fit the existing phases (extend, do not retrofit).
