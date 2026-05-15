# ADR 0004: Auto insurance as the bounded domain

- Status: Accepted
- Date: 2026-05-15
- Deciders: ze
- Related: ADR 0003

## Context

The teaching app needs a domain rich enough to demonstrate quote → bind → claim → payment, multi-consumer event pipelines, document workflows, partner integrations, and audit. The domain must be **familiar** (so students don't spend cycles understanding the business) and **not heavily regulated** (so we can hand-wave compliance).

Candidate domains considered:

- **Auto insurance** — familiar, claims involve documents, payments are recurring.
- **Health / life insurance** — heavy regulatory burden, specialty entities (medical records, beneficiaries).
- **Property insurance** — similar to auto with appraisals; less claim variety.
- **Commercial / specialty lines** — too varied to scope.
- **E-commerce** — over-used as a teaching example; orders/fulfillment less interesting than claims pipelines.
- **Banking / payments** — heavily regulated, fraud-sensitive; students question every shortcut.

## Decision

**Auto insurance**, single carrier, three audiences (customers, agents, partner brokers).

Core entities and lifecycles:

| Entity | Owns | Lifecycle |
| --- | --- | --- |
| **Customer** | self | created → active |
| **Vehicle** | customer | active → archived |
| **Quote** | customer | DRAFT → CALCULATED → ACCEPTED / EXPIRED |
| **Policy** | customer | BOUND → ACTIVE → RENEWED / CANCELLED / LAPSED |
| **Claim** | policy | SUBMITTED → UNDER_REVIEW → APPROVED / DENIED → PAID / CLOSED |
| **Payment** | policy | PENDING → SUCCEEDED / FAILED / REFUNDED |
| **Document** | claim | uploaded → processed |
| **Notification** | customer | queued → sent / failed |
| **AuditEvent** | * | append-only (Kafka-compacted topic, not a table) |

Rating math: hand-waved. `premium = base * vehicle_factor * driver_factor * coverage_factor` is enough. Students are not actuaries.

Out of scope:

- Multi-state regulatory rating
- Bundling / multi-line products
- Commission tracking, carrier hierarchies, reinsurance
- Compliance regimes (HIPAA / SOX / GDPR specifics)
- Underwriter / adjuster workflow tooling (we model the entities; we do not build the back-office)

## Consequences

- Auto-specific terms (VIN, plate, premium) appear in code and UI; comfortable for most engineers, mildly unfamiliar to some non-US students.
- Entity model stays compact (~9 entities); easier to teach than the typical e-commerce sprawl.
- The claims pipeline gives a natural place to demonstrate Kafka fan-out, MI document mediation, and APIM partner integration without forcing the demo.

## Alternatives considered

- **E-commerce.** Too crowded a teaching example. Insurance's claims pipeline is richer than orders/fulfillment for the patterns we care about.
- **Banking / payments.** Heavily regulated, fraud-sensitive; students would spend energy questioning "would you really do that."
- **A non-business synthetic domain (TODO app, etc.).** Doesn't exercise enough integration surface; partners and webhooks feel forced.

## Revisit when

- The track expands to a second app variant — we may add a health/life variant later for students who already know auto.
- A specific feature turns out to require entities we excluded (rare; the model is already wide).
