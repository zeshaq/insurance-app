# PII data flow and retention policy

This document is the durable answer to four questions a compliance officer or DPO will ask on day one:

1. What personal data does this app store?
2. Where does it flow?
3. How long do we keep it?
4. Can we honor a customer's deletion request?

The flows described here already exist in code — this is a surfacing exercise, not a design proposal. Cross-references: schema migrations under `src/main/resources/db/migration/V*.sql`, [`docs/security-baseline.md`](../security-baseline.md), [`docs/compliance/regulatory-jurisdictions.md`](./regulatory-jurisdictions.md), [`docs/compliance/pen-test-vendor-prep.md`](./pen-test-vendor-prep.md).

---

## 1. PII classification table

PII classification follows the three-bucket convention common in DPIA work:

- **Directly identifying (DI)** — identifies an individual on its own (name, email, government ID, account ID linked to a real person).
- **Quasi-identifying (QI)** — does not identify on its own but, in combination with other data, narrows to an individual (VIN, postcode, DOB).
- **Sensitive (S)** — special-category data attracting heightened protection (health, biometric, financial-account, claim photos that may reveal injury).

Every column the app currently writes to a backing store is enumerated below. The "Class" column applies the above buckets.

| Store | Table / object | Field | Class | Notes |
|---|---|---|---|---|
| Postgres | `quote` | `vehicle_vin` | QI | 17-char VIN — uniquely identifies a vehicle, which usually maps 1:1 to an owner via DMV registries. |
| Postgres | `quote` | `driver_age` | QI | Age band is a profiling input; not DI on its own. |
| Postgres | `quote` | `coverage_type`, `premium`, `status`, `valid_until` | non-PII | Business attributes. |
| Postgres | `policy` | `policy_number` | DI | This is the durable customer-facing account identifier; everywhere it appears it must be treated as DI. |
| Postgres | `policy` | `quote_id`, `status`, `bound_at` | non-PII (in isolation) | Joins to `quote.vehicle_vin` make this QI-by-association. |
| Postgres | `payment` | `policy_number` | DI | Foreign key to `policy`. |
| Postgres | `payment` | `amount`, `currency` | S | Financial — not as sensitive as account numbers, but financial-services regulators treat it specially. |
| Postgres | `payment` | `idempotency_key` | DI-adjacent | Includes a client-supplied UUID per request; if the client picks `userId-policyNumber-attempt`, this leaks. |
| Postgres | `payment` | `external_ref` | DI | Payment-gateway reference number, ties our row to the gateway's customer record. |
| Postgres | `payment` | `failure_reason` | S | May leak gateway-side card-decline reasons. |
| Postgres | `notification` | `recipient` | DI | Email address or phone number of the customer. |
| Postgres | `notification` | `subject`, `body` | DI / S | Templated content includes customer name and policy number; if a claim-status notification, may include claim details. |
| Postgres | `notification` | `external_ref` | DI-adjacent | Mail / SMS provider's message ID; ties our row to the provider's. |
| Postgres | `claim` | `policy_number` | DI | FK to `policy`. |
| Postgres | `claim` | `description` | S | Free-text customer narrative — may include injury detail, names of third parties, location. |
| Postgres | `claim` | `photo_key` | QI | MinIO object key; the key itself is opaque, but it points at content that is S. |
| Postgres | `claim` | `ocr_text` | S | Extracted text from claim photos; can contain license plates, names from police reports, signatures, medical-discharge info. |
| Postgres | `claim` | `other_party_vin`, `other_party_policy`, `other_party_carrier` | QI / DI | Third-party data: we are processing PII about a non-customer. This is a distinct lawful-basis problem. |
| MinIO | `claims` bucket | `<photo_key>` | S | Binary photo. May contain bystanders, license plates, injuries. |
| Redis | `idempotency:*` keys | full request body cache | DI / S | Mirror of `payment` and `claim` request bodies for the cache TTL. |
| Redis | `session:*` (agent BFF) | OIDC `sub`, agent profile claims | DI | Session store for the agent dashboard (`gui/agent-app/server/index.ts`). |
| Redis | Redlock keys (`lock:bind:<quote_id>`) | quote_id | QI | Tied via FK to `vehicle_vin`. |
| Kafka | `quote.created`, `policy.bound`, `payment.succeeded`, `claim.filed` | event payloads mirror Postgres rows | DI / S | Whatever is in the row is in the event. |
| WSO2 IS H2 | `UM_USER` table | username, email, profile claims | DI | Customer identity store (will move off H2 to Postgres for production per Phase 6 ops work). |

**Key observation:** the most sensitive data in the app is the claim attachment + its OCR text. A claim photo of an accident scene can capture license plates, faces of bystanders, and injury — that lifts the claim pipeline into special-category processing under GDPR Art. 9 if any health data is captured, and into the equivalent "sensitive data" bucket under every other privacy regime surveyed in [`regulatory-jurisdictions.md`](./regulatory-jurisdictions.md).

## 2. Data-flow diagram

```
                   +----------------------+        +---------------------+
                   |  Customer browser    |        |   Agent browser     |
                   |  (TLS, OIDC redirect)|        |  (TLS, OIDC redirect)|
                   +----------+-----------+        +-----------+---------+
                              |                                |
                              | (1) signed cookies, ID claims  | (1) signed cookies, ID claims
                              v                                v
                   +----------+-----------+        +-----------+---------+
                   |   customer-app BFF   |        |   agent-app BFF     |
                   |   (SvelteKit/Node)   |        |  (Express/Node)     |
                   |  Auth.js sessions    |        |  openid-client      |
                   +----+-------+---------+        +-----+-------+-------+
                        |       |                        |       |
              (2) PII   |       | (3) auth code/PKCE     |       | (3) auth code/PKCE
                        |       v                        |       v
                        |  +----+----------+             |  +----+----------+
                        |  |   WSO2 IS 7   |<------------+  |   WSO2 IS 7   |
                        |  |  (OIDC + UM)  |   (DI: sub,    |  (shared IdP) |
                        |  +----+----------+    email)      +---------------+
                        |       |
                        |       | (4) session store
                        |       v
                        |  +----+----------+
                        |  |   Redis       |   (DI: sessions, idempotency, locks)
                        |  +----+----------+
                        |       ^
              (2) PII   |       | (5) idempotency cache (agent BFF read-through)
                        v       |
                   +----+-------+--+
                   |  Liberty 24   |   (Jakarta EE + MicroProfile)
                   |  insurance-app|
                   +-+-+-+-+-+-+-+-+
                     | | | | | | |
                 (6) | | | | | | | (7)  (8)   (9)  (10) (11)  (12)
                     v | | | | | |
                +----+ | | | | | +---> WSO2 MI ----> Mailpit / SMS stub
                |Postgres| | | | |        (DI: recipient, body)
                | (DI/S) | | | | |
                +--------+ | | | |
                           v | | |
                      +----+---+ +---> MinIO  (S: photo binaries)
                      | Kafka  |       (`claims` bucket)
                      | (DI/S) |
                      +--------+
                                       +---> partner-mock (mTLS)
                                            (QI: other_party_vin)

                                       +---> WireMock OCR  (S: photo binary in, OCR text out)

                                       +---> WireMock payment gateway (S: amount, currency)
```

**Edge legend (PII volume on each labeled edge):**

| # | Edge | Carries |
|---|---|---|
| 1 | browser ↔ BFF | session cookies, request bodies (DI + QI) |
| 2 | BFF ↔ Liberty `/api/*` | full request/response bodies (DI + QI + S) |
| 3 | BFF ↔ WSO2 IS | OIDC auth-code + ID tokens (DI: sub, email, name claims) |
| 4 | BFF → Redis | session blobs (DI) |
| 5 | Liberty ↔ Redis | idempotency cache (DI + S — mirrors request payloads) |
| 6 | Liberty ↔ Postgres | all persistent state (every class) |
| 7 | Liberty → Kafka | domain events (DI + S — payload mirrors rows) |
| 8 | Liberty → MinIO | claim photos (S) |
| 9 | Liberty → WSO2 MI → Mailpit / SMS | rendered notification bodies (DI) |
| 10 | Liberty → partner-mock (mTLS) | third-party `other_party_vin` lookups (QI) |
| 11 | Liberty → WireMock OCR | claim photo binary out, OCR text in (S) |
| 12 | Liberty → WireMock payment gateway | amount + currency + idempotency key (S) |

Edges 1–9 are inside the trust boundary of the VM. Edges 10–12 are the **external** edges; in a real deployment those become outbound mTLS to live carrier APIs, an OCR SaaS, and a real payment processor, each of which is a sub-processor (§6).

## 3. Retention policy table

The app does not implement automated retention today — every record is kept indefinitely. The "Recommended" column is the planning target for a real deployment, derived from the most-stringent jurisdiction surveyed in [`regulatory-jurisdictions.md`](./regulatory-jurisdictions.md).

| Data type | Storage | Current retention | Recommended retention | Rationale | Owner |
|---|---|---|---|---|---|
| Quote (not bound) | `quote` table | indefinite | 90 days from `valid_until` | A quote that never converts has no continuing business purpose; retain only for fraud analytics. | @TBD |
| Quote (bound to a policy) | `quote` table | indefinite | policy lifetime + 7 years | Bound quote is the actuarial basis of the policy; retain for the regulatory tail. | @TBD |
| Policy | `policy` table | indefinite | policy lifetime + 7 years | UK FCA + EU Solvency II + most US states require records "for the duration of the contract plus 7 years." | @TBD |
| Payment | `payment` table | indefinite | 7 years from `processed_at` | Tax + AML record-keeping common floor across jurisdictions. | @TBD |
| Notification | `notification` table | indefinite | 18 months from `dispatched_at` | Audit log of communications; long enough for complaint windows, short enough to limit blast radius. | @TBD |
| Claim record | `claim` table | indefinite | claim closure + 10 years | Statute-of-limitations buffer; some bodily-injury claims surface late. | @TBD |
| Claim photo binary | MinIO `claims` bucket | indefinite | claim closure + 10 years | Tied to claim record; same retention or shorter (e.g. retain redacted thumbnail past full-resolution retention). | @TBD |
| Claim OCR text | `claim.ocr_text` | indefinite | claim closure + 10 years | Same as parent claim. Consider redaction-on-write for license plates / names of bystanders. | @TBD |
| Idempotency cache | Redis `idempotency:*` | TTL (24h) | TTL (24h) | Already bounded; no gap. | OK |
| Agent / customer session | Redis `session:*` | TTL (1h sliding) | TTL (1h sliding) | Already bounded; no gap. | OK |
| Redlock keys | Redis `lock:*` | TTL (≤30s) | TTL (≤30s) | Already bounded; no gap. | OK |
| Kafka events | `quote.*`, `policy.*`, `payment.*`, `claim.*` topics | broker default (7 days) | 30 days | Long enough for replay during incident triage, short enough to limit blast radius. Verify with `kafka-configs.sh --describe`. | @TBD |
| Report snapshots | `report_run` table | indefinite | 7 years | Operational audit; tied to financial reporting. | @TBD |
| WSO2 IS user records | WSO2 H2 / external user store | indefinite | account closure + 7 years | Identity records tied to the policy retention. | @TBD |

**Gap summary:** every Postgres table needs a documented retention window enforced by a scheduled job (likely a WSO2 MI quartz task — pattern already used by `mi-scheduled-task` for `report_run`). The Redis/Kafka stores are already bounded, but the broker-default 7-day Kafka retention may be insufficient for incident triage; verify and pin.

## 4. Right-to-deletion (GDPR Art. 17 and equivalents)

**Today:** there is no path. A customer asking us to delete their data would require a DBA to run hand-written SQL across `quote`, `policy`, `payment`, `notification`, `claim`, plus a MinIO `mc rm` for every `photo_key` for that customer's policies, plus a WSO2 IS user-delete via SCIM. Nothing is wired up.

**Required (filed as a follow-up issue note, NOT implemented in this doc):**

A `DELETE /api/customers/{id}` endpoint on Liberty that, in a single transaction:

1. Looks up every `policy_number` for the customer (today there is no `customer_id` on `policy` — the de-facto customer key is the WSO2 IS `sub` claim, which is not stored alongside the policy; this is itself a gap and must be fixed first).
2. For each policy: deletes the chain `claim → payment → policy → quote`.
3. For each `claim.photo_key`: removes the object from MinIO.
4. Calls WSO2 IS SCIM `DELETE /Users/{id}` to remove the IdP record.
5. Writes a single **audit log entry** to a tamper-evident store (not the same Postgres, since the audit must survive the deletion). A separate `compliance_audit` table on a different schema or, preferably, an append-only sink (Kafka `compliance.audit` topic with infinite retention) is the right shape.
6. Returns **what was deleted** (counts per table, list of object keys) so the customer's request can be evidenced.

**Important: not everything is deletable.** Records held under a legal-retention obligation (e.g. payments held 7 years for tax) must NOT be deleted on customer request — instead they are anonymized (replace `policy_number` with a hash, null out `recipient`, drop `ocr_text`). The endpoint must distinguish "delete" from "anonymize" per record.

**Filing as a follow-up:** a new GitHub issue under `qa:compliance` label, titled "Implement right-to-deletion endpoint (GDPR Art. 17 / CPRA / equivalents)". Add a dependency on a precursor issue: "Add `customer_id` (WSO2 IS `sub`) foreign key to `policy` table — required for any per-customer query."

## 5. Cross-border data transfer

**Physical residency today:** all customer data resides on a single VM (`insurance-app-vm`, IP `30.30.26.1`) in the user's home lab in Bangladesh. Postgres, Redis, Kafka, MinIO, and WSO2 IS H2 all run as rootless podman containers on that VM. See [`docs/adr/0002-vm-canonical-workspace.md`](../adr/0002-vm-canonical-workspace.md) for the workspace decision.

**Conflicts with the jurisdictions surveyed in [`regulatory-jurisdictions.md`](./regulatory-jurisdictions.md):**

| Customer's jurisdiction | Current residency | Conflict |
|---|---|---|
| Bangladesh | BD VM | None — same jurisdiction. |
| India (IRDAI) | BD VM | **Hard conflict.** IRDAI requires Indian residency for policyholder records. Cannot serve Indian customers without an India-region deployment. |
| EU member states | BD VM | **Cross-border transfer under GDPR Ch. V.** Bangladesh is not on the Commission's adequacy list. Requires Standard Contractual Clauses + transfer impact assessment + supplementary measures, OR an EU-region deployment. |
| UK | BD VM | Same as EU; UK adequacy with non-EU third countries is governed separately. |
| US states | BD VM | No federal data-localization rule, but **state-of-residence breach-notification law applies regardless of where the data sits.** |
| Canada (Quebec Law 25) | BD VM | Cross-border disclosure triggers Law 25 §17 — privacy impact assessment + assessment of legal framework in receiving jurisdiction required. |
| Singapore (PDPA) | BD VM | Permitted with transfer-limitation obligation: receiving party must be bound to standards comparable to PDPA via contract. |
| Australia (APP 8) | BD VM | Cross-border disclosure: original collector remains accountable for downstream APP compliance. |

**Architectural answer for a real deployment:** per-region deployments (BD-region, EU-region, IN-region, US-region) with routing at the customer-app BFF tier based on the customer's identified jurisdiction. The `customer_id` foreign key proposed in §4 should carry a `home_jurisdiction` attribute so the router knows which region to serve.

**Teaching-artifact answer for today:** document the conflict, do not pretend it is closed.

## 6. Sub-processor list

A sub-processor is any third party that processes personal data on our behalf. A real-world DPA (Data Processing Agreement) is required with each sub-processor under GDPR Art. 28 and equivalents.

**Current state (lab):** the app uses only self-hosted containers and mock services. **There are no commercial sub-processors today.** This is unusual — it is a property of the teaching artifact, not of how a production insurer would actually run.

The table below enumerates every third-party service the app depends on, and flags where adoption of a commercial equivalent would create a sub-processor relationship requiring a DPA.

| Service | Today | Commercial equivalent | Sub-processor risk |
|---|---|---|---|
| Identity provider | WSO2 IS 7 self-hosted | Okta, Auth0, Azure AD B2C, Cognito | **DPA required.** IdP sees DI (sub, email, name) on every login. |
| Email delivery | Mailpit (no outbound) | SendGrid, AWS SES, Postmark, Mailgun | **DPA required.** Email body contains DI; some templates leak policy info. |
| SMS delivery | Mock | Twilio, Vonage, AWS SNS | **DPA required.** Recipient phone + message body. |
| Payment gateway | WireMock | Stripe, Braintree, Adyen, local PSP | **DPA + PCI scope** required. |
| Vision / OCR | WireMock | AWS Textract, Google Document AI, Azure Form Recognizer | **DPA required and high-risk.** Claim photo binary leaves the trust boundary; a DPIA is mandatory under GDPR for this category. |
| Partner carrier lookup | partner-mock (nginx) | Live carrier APIs over mTLS | Joint-controller / controller-to-controller, not sub-processor. Still needs a data-sharing agreement. |
| Object store | MinIO self-hosted | AWS S3, GCS, Azure Blob | **DPA required.** Holds claim photos (S). |
| Database | Postgres self-hosted | AWS RDS, Cloud SQL, Aurora | **DPA required.** Holds everything. |
| Event bus | Kafka self-hosted | Confluent Cloud, AWS MSK | **DPA required.** Holds DI / S in event payloads. |
| Cache / lock | Redis self-hosted | ElastiCache, Upstash, Redis Cloud | **DPA required.** Holds sessions + idempotency. |
| APIM | WSO2 APIM self-hosted | Kong, Apigee, AWS API Gateway | **DPA required if logs include request bodies.** |
| Observability | SigNoz self-hosted | Datadog, New Relic, Honeycomb | **DPA required.** Traces frequently carry headers containing tokens or IDs; logs may carry PII unless scrubbed. |
| Pen-test vendor | TBD per [`pen-test-vendor-prep.md`](./pen-test-vendor-prep.md) | n/a | **DPA + NDA required** before any engagement against an environment with real data. |

**Operational implication:** the moment the team swaps any of the self-hosted components above for a commercial equivalent, that change carries a compliance dependency. Every such swap should require a checkbox in the PR template: "Is a DPA in place with this provider? Is the provider on the published sub-processor list?"

A published sub-processor list (under `docs/compliance/sub-processors.md`, not in this doc) is itself a GDPR Art. 28 transparency obligation — customers must be told who processes their data. That doc does not exist yet and is filed as a follow-up.

---

## Status and follow-ups

This document is the as-of-2026-05-18 snapshot. Concrete follow-up issues to file (under `qa:compliance` label) once the Phase 6 issue cleanup happens:

1. **Add `customer_id` FK to `policy`** — precursor to any per-customer query (deletion, export, audit).
2. **Implement retention-job framework** — scheduled MI task that walks the retention table in §3 and prunes/anonymizes rows past their window.
3. **Implement right-to-deletion endpoint** — §4.
4. **Publish sub-processor list** — `docs/compliance/sub-processors.md`. Empty today; will fill as commercial services are adopted.
5. **DPIA for the claim-OCR pipeline** — required under GDPR Art. 35 before any EU launch.
6. **Pin Kafka topic retention explicitly** — don't rely on broker default.
