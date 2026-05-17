# Penetration testing — vendor selection & engagement prep

This document is the input you take to actual pen-test vendors when scheduling the Phase 4 third-party engagement. It captures scope, comparison rubric, and the report-handling SLA the engagement letter should commit to.

**Lead time**: pen tests typically book **6–8 weeks** out from a vendor's first availability, and remediation after report delivery needs another **2–4 weeks** before re-test. Plan accordingly: a deploy gated on pen-test sign-off requires the engagement to start **8–12 weeks** before the target date.

## Scope of engagement

The scope statement below is what to put in the engagement letter. Vendors will scope-bid against it.

### In scope

**Web applications:**
- Customer portal — `https://my.insurance-app.comptech-lab.com` (SvelteKit + `@auth/sveltekit` BFF + WSO2 IS OIDC code-flow).
- Agent dashboard — `https://agent.insurance-app.comptech-lab.com` (React SPA + Express BFF + Redis-backed session + `openid-client`).
- Teaching tour pages — `https://app.insurance-app.comptech-lab.com/` (Liberty-served static HTML + dev-token flow).

**API surface:**
- All `/api/*` endpoints on `https://app.insurance-app.comptech-lab.com` (Jakarta EE 10 + MicroProfile 6.1 on Open Liberty 24.0.0.12). The published OpenAPI spec is at `https://app.insurance-app.comptech-lab.com/openapi`.
- `/auth/*` BFF endpoints on the two portals.

**Identity provider:**
- WSO2 Identity Server 7 at `https://is.insurance-app.comptech-lab.com` (console + carbon admin + my-account end-user portal). Two DCR-registered OIDC clients in scope: `insurance-customer-app` and `insurance-agent-app`.

**API gateway:**
- WSO2 API Manager at `https://apim.insurance-app.comptech-lab.com` (publisher/devportal/admin/carbon) and `https://gateway.insurance-app.comptech-lab.com` (runtime gateway).

**Observability + storage admin UIs** (intentionally permissive in the teaching lab; explicit in-scope so vendors don't flag everything):
- SigNoz, MinIO console, Kafka UI, RedisInsight, OpenSearch Dashboards, Mailpit. See `/credentials.html` on the teaching tour for defaults.

**OWASP focus areas:**
- API Security Top 10 (BOLA, broken auth, mass assignment, BOPLA, etc.).
- ASVS Level 2 against the customer + agent portals.
- WSO2 IS configuration review (token lifetimes, PKCE enforcement, redirect-URI allowlists, signing-key custody).

### Explicitly out of scope

- The supporting infrastructure not exposed publicly (Postgres, Kafka, MinIO internals, Liberty admin port 9443).
- DoS / availability testing — this is a single-VM lab; we accept that 100k req/s would take it down without proving anything new.
- Social-engineering, physical, or supply-chain attacks.
- Findings about platform-default WSO2 keystore passwords (`wso2carbon`) — these are documented intentionally for the teaching artifact. Vendors should still report them, but they go to an "accepted-risk" list, not an action-required list.

### Test environment

- **Staging is production** for this engagement — the same publicly-reachable URLs above. We do not maintain a separate test environment.
- Test data accumulation is acceptable; the lab is intended to be writable.
- Test credentials: `student@comptech.com` / `Student@1234` for both portals; `admin` / `admin` for WSO2 admin surfaces; `minioadmin` / `minioadmin` for MinIO. All documented at <https://app.insurance-app.comptech-lab.com/credentials.html>.

## Vendor comparison rubric

Score each vendor 1–5 on the dimensions below. Total out of 35.

| Dimension | Score (1–5) | Notes |
|---|---|---|
| Web + API pentest experience (insurance domain a bonus) | | |
| WSO2 IS / OIDC / OAuth 2.0 fluency | | |
| Demonstrable track record (case studies, references) | | |
| Methodology transparency (tools list, sample report, retest policy) | | |
| Engagement responsiveness (response time to RFP, lead-time, flexibility) | | |
| Cost vs scope (apples-to-apples; ask for hourly + project flat-rate) | | |
| Aftercare (free retest after remediation, secure report delivery, briefing call) | | |

A score below **22/35** is a polite decline. **22–28** is workable; tighten the engagement letter. **29+** is a confident yes.

## Engagement letter — required clauses

The legal document the vendor sends will likely cover the basics. Make sure these specific items are in:

1. **Scope mirroring** — the engagement letter must reproduce the in-scope / out-of-scope sections of this document verbatim (or by direct reference). Any vendor-side scope drift requires a written change order.
2. **Vulnerability handling SLA**:
   - Vendor delivers a draft report within **5 business days** of engagement completion.
   - We respond with clarifying questions within **5 business days**; vendor revises the final report within another 5.
   - The final report is delivered via the vendor's encrypted portal, not email.
3. **Disclosure embargo**: any findings are confidential between us and the vendor until our remediation is verified. The vendor commits not to discuss findings (even anonymized) for **12 months** post-engagement.
4. **Retest included**: one free retest of findings within **90 days** of report delivery, scoped to the originally reported issues.
5. **Insurance**: vendor carries professional indemnity / errors-and-omissions coverage of at least **$2M USD** (or local equivalent).
6. **Right to terminate**: we can terminate the engagement for any reason with **48 hours' written notice** before the test starts; a prorated fee applies if testing has begun.

## Report-handling SLA on our side

After the report arrives:

| Severity | Triage SLA | Fix SLA | Verification |
|---|---|---|---|
| **Critical** (RCE, auth bypass, mass data leak) | 1 business day | 5 business days | Internal verification + vendor retest |
| **High** (privilege escalation, SQLi/XSS in user-data path, broken access control) | 2 business days | 10 business days | Internal verification + vendor retest |
| **Medium** | 5 business days | 30 business days | Internal verification |
| **Low** / **Informational** | 10 business days | Next quarterly cycle, OR accepted-risk entry in `docs/security-baseline.md` with documented expiry | None required |

Findings get filed as GitHub issues under labels `qa:security` + `pentest-2026-Hn` (one label per engagement). The triager attaches the severity, the SLA target date, and the assignee in the issue body.

## Cross-references

- Phase 4 milestone: <https://github.com/zeshaq/insurance-app/milestone/5>
- QA roadmap: [`docs/qa-roadmap.md`](../qa-roadmap.md)
- Security baseline (running findings list): [`docs/security-baseline.md`](../security-baseline.md)
- Demo credentials (for vendor use): <https://app.insurance-app.comptech-lab.com/credentials.html>

---

**Status:** vendor selection not yet initiated. Add an "Engagement log" section below as vendors are contacted, RFPs sent, and proposals received.

## Engagement log

*(empty)*
