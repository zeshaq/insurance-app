# Security baseline

**As of:** 2026-05-17 (commit `26c69eb`)

This document captures what the four Phase 0 scanners are saying *today*. It is the reference point against which every future change is measured — if the numbers below drift up, that drift gets reviewed in the PR that caused it.

The scanners run on every push and pull request via GitHub Actions:

- **Dependabot** — `Maven`, `npm` (×2 apps), `Docker` (×3 Containerfiles), `github-actions`. Weekly cadence, Mondays 06:00.
- **gitleaks** — `.github/workflows/gitleaks.yml` + `.pre-commit-config.yaml`.
- **Trivy** — `.github/workflows/trivy.yml`, two jobs: filesystem scan (deps + secrets + misconfig) and config scan (Containerfile/IaC checks).
- **Semgrep OSS** — `.github/workflows/semgrep.yml`, rulesets: `p/default`, `p/owasp-top-ten`, `p/java`, `p/typescript`.

Findings publish as SARIF to the GitHub **Security** tab. The full live view is at <https://github.com/zeshaq/insurance-app/security>.

## Findings snapshot

### Dependabot — 13 open PRs

| Ecosystem | Open PRs | Notes |
|---|---|---|
| `maven` (Liberty backend) | 6 | Major version bumps (kafka 3.9→4.2, MinIO 8→9, Flyway 10→12). Review each for breaking changes before merging. |
| `npm` (agent-app)         | 5 | Major bumps in `connect-redis 8→9`, `openid-client 5→6`, `redis 4→5`, `typescript 5→6`. Bundled rollup recommended after Phase 1 tests exist. |
| `github-actions`          | 2 | `actions/checkout 4→6`, `aquasecurity/trivy-action 0.28→0.36`. Safe; merge after the next clean CI run. |
| `npm` (customer-app)      | 0 | No outdated deps detected at this snapshot. |
| `docker` (3 Containerfiles) | 0 | Base images current. |

**Triage policy:** merge `github-actions` updates immediately when CI passes. Hold majors (`maven` + `npm`) until Phase 1 introduces real test coverage; they're more useful to merge once a unit suite can prove nothing broke.

### gitleaks — 0 leaked secrets

Clean. The workflow's `success` conclusion confirms no committed secrets in the repo. The credential-shaped tokens visible in `compose/infra/wso2{is,apim}/deployment.toml` and `src/main/liberty/config/server.xml` (`wso2carbon`, `insurance`, etc.) are documented WSO2 platform defaults, not real secrets — gitleaks regex tuned to recognize these isn't necessary.

### Trivy — 23 alerts

| Severity | Count | Mostly |
|---|---|---|
| High   | 11 | Language-specific package vulnerabilities in transitive npm + Maven dependencies. |
| Medium |  7 | Same shape as High, lower CVSS. |
| Low    |  5 | Containerfile misconfigurations (e.g. running as root, no `USER` directive on intermediate stages). |

**Acceptance position:** the High/Medium pool will shrink naturally as the Dependabot bumps land (especially the major-version Maven and npm PRs above). The Low misconfigurations on Containerfiles get fixed when we re-touch the images during Phase 1; some are unavoidable for the IBM Liberty `full` image we depend on (see `build_gotchas` memory item #2).

### Semgrep OSS — 2 alerts

Both are warnings (not blockers). They surface as user-visible issues; brief triage:

1. **`javascript.express.security.audit.xss.direct-response-write`** — agent-app BFF's Liberty proxy does `res.send(buf)` where `buf` is the upstream response body. The body is binary from an internal trusted API, not user input, but Semgrep can't see the trust boundary. **Status:** false-positive in our context; will be added to `.semgrepignore` with a comment explaining the trust boundary.
2. **`python.lang.security.insecure-hash-algorithms.insecure-hash-algorithm-sha1`** — there is no Python in this repo. Semgrep's match is on a non-source file (likely a Python tool string in a vendored asset). **Status:** investigating; suspect false-positive, but the `.semgrepignore` entry should target the exact file rather than the rule globally.

A `.semgrepignore` will land in a follow-up PR once the file-path triage is complete; we are *not* baseline-suppressing the whole ruleset.

## Accepted risks

No accepted-risk entries yet. New entries go here as a table:

| Date | Tool | Rule | Path/CVE | Reason | Expiry |
|---|---|---|---|---|---|

Each entry must carry an **expiry**. Risk decisions are not load-bearing forever; quarterly review re-evaluates the entry against current threat landscape.

## Update procedure

- **Quarterly** (or after any major Dependabot batch lands): re-run this audit, update the snapshot tables, refresh the accepted-risks expiries.
- **Per PR**: if a PR raises new High/Critical findings that aren't on the accepted-risk list, the PR is blocked until either fixed or explicitly accepted in this document.
- **Per scanner upgrade**: bumping a scanner to a new major version triggers a full re-baseline; old findings may close or move severity buckets.

## Cross-references

- Live alerts: <https://github.com/zeshaq/insurance-app/security>
- Dependabot config: `.github/dependabot.yml`
- Workflows: `.github/workflows/{gitleaks,trivy,semgrep}.yml`
- Phase 0 milestone: <https://github.com/zeshaq/insurance-app/milestone/1>
- Overall QA plan: `docs/qa-roadmap.md`


## Bug fixes since the baseline

Subsequent scanner runs in Phase 2 (Schemathesis) and the response since
have produced concrete fixes that close out specific findings. Listed
here in reverse chronological order for the next baseline refresh.

### 2026-05-17 — VIN-length validation (issue #62)

Phase 3 k6 load testing surfaced another 500-where-400-belonged: POST
/api/quotes with a VIN longer than 17 characters threw an EclipseLink
column-size error, and Liberty's RESTEasy additionally corrupted the
response while trying to serialise the stack trace into a header. Both
failure modes are gone:

* `QuoteRequest` record components now carry Jakarta Bean Validation
  constraints (`@NotBlank`, `@Size(min=3, max=17)`, `@Min(16)`,
  `@Max(99)`, `@Pattern("BASIC|STANDARD|PREMIUM")`).
* `@Valid` on `QuoteResource.create()` triggers validation before the
  request reaches the service layer.
* New `ConstraintViolationExceptionMapper` in
  `com.example.insurance.error/` maps `ConstraintViolationException` to
  a 400 with a `{error, violations: [{field, message}, ...]}` body.

Verified live: VIN >17 chars, driverAge <16, and coverageType outside
the enum all return 400 with informative bodies; a clean 17-char VIN
still returns 201 with the quote.

### 2026-05-17 — five 500s -> 4xx (issues #51, #52)

Phase 2 Schemathesis fuzz against the live Liberty surfaced five
endpoints returning `500 Internal Server Error` instead of 4xx on bad
input. All five have been fixed and verified live:

| Endpoint | Was | Now | Fix |
|---|---|---|---|
| `POST /api/quotes`   (malformed JSON) | 500 | **400** | `JsonbExceptionMapper` |
| `POST /api/policies` (malformed JSON) | 500 | **400** | `JsonbExceptionMapper` |
| `POST /api/payments` (malformed JSON) | 500 | **400** | `JsonbExceptionMapper` |
| `POST /api/claims`   (broken multipart) | 500 | **400** | `ProcessingExceptionMapper` + Liberty falls through to `policyNumber required` 400 |
| `GET /api/audit/contrast/{id}` (unknown id) | 500 | **404** | `AuditResource.contrast()` returns `NotFoundException` when neither snapshot nor stream has data; `HashMap` replaces `Map.of()` to tolerate null snapshot values |

Three new test classes back the change:
* `com.example.insurance.error.JsonbExceptionMapperTest`
* `com.example.insurance.error.JsonExceptionMapperTest`
* `com.example.insurance.error.ProcessingExceptionMapperTest`
* `com.example.insurance.audit.AuditContrastTest`

Re-running the Phase 2 Schemathesis suite against the new build no
longer surfaces these specific 500s. New surface that the fuzz reveals
on subsequent runs gets a new row here.


### 2026-05-18 — debt-fix session (issues #56, #57, #58, #59, #60, #61, #62 follow-up, #63 wontfix)

Closed all remaining Dependabot follow-ups deferred from Phase 0.5,
extended the bean-validation pattern from issue #62 to PolicyRequest
and PaymentRequest, and resolved the synthetic-monitor data-growth
caveat from issue #35.

**Major-version dep bumps shipped:**

| Issue | Bump | Notes |
|---|---|---|
| #56 | `openid-client` 5.7.1 → 6.8.4 (agent-app) | API rewrite touch points: config init, /auth/signin, /auth/callback, claims pluck. Live OIDC handshake against WSO2 IS verified post-bump. |
| #57 | `connect-redis` 8.1.0 → 9.0.0 (agent-app) | Peer dep now `redis >= 5` (already on 5 from Phase 0.5). |
| #58 | `kafka-clients` 3.9.0 → 4.2.0 (Liberty) | Zero source changes — our API usage is on the stable subset that survived 3.x → 4.x. Phase 5 drill #27 confirmed: no double-publish, no message loss after broker recovery. |
| #59 | `kafka-streams` 3.9.0 → 4.2.0 (Liberty) | Bundled with #58; `StreamsBuilder.build()` no-arg form unchanged. |
| #60 | `io.minio` 8.5.10 → 9.0.0 (Liberty) | Added explicit `com.squareup.okhttp3:okhttp` 4.12.0 dep — MinIO 9 dropped okhttp from transitives. No source changes in `MinioStorageService`. |
| #61 | `flyway` 10.20.0 → 11.8.2 (Liberty) | Substitute path: 12.x switched to Jackson 3 (`tools.jackson.*`) which conflicts with our Jackson 2 transitive deps. 11.x is the last major on Jackson 2 — safe bump. Live Liberty boot confirms migration discovery + the build_gotchas item-6 marker files still work. |

**Pattern extensions and operational fixes:**

* **Bean Validation extended** (#62 follow-up) to `PolicyRequest`
  (`@NotNull @Positive quoteId`) and `PaymentRequest`
  (`@NotBlank @Pattern("POL-...")` `policyNumber`, `@DecimalMin(0.01)`
  `amount`, `@Pattern("[A-Z]{3}")` `currency`). Same `@Valid` +
  `ConstraintViolationExceptionMapper` envelope as the original
  QuoteRequest fix.

* **Synthetic-monitor VIN prefix + prune** (#35 caveat closed):
  `tests/monitoring/quick-smoke.sh` now emits SYNMON-prefixed VINs;
  `tests/monitoring/prune-synthetic.{sh,service,timer}` deletes them
  after 24h via a user systemd timer. No more unbounded growth.

**Closed wontfix:**

* **#63 MinIO partial multipart** — three approaches tried (per-call
  cleanup, lifecycle policy via SDK, lifecycle policy via mc JSON
  import). MinIO server build `RELEASE.2025-09-07T16-13-09Z` silently
  strips `AbortIncompleteMultipartUpload` lifecycle fields on import;
  the v9 Java SDK removed the public `removeIncompleteUpload` method.
  No application-side fix is expressible without a MinIO server image
  bump. User-visible contract (5xx + no `photoKey` exposed) is
  already correct; MinIO's internal scanner does cleanup eventually.
  Reopening trigger is documented in the issue.
