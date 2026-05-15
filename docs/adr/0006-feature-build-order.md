# ADR 0006: Feature build order

- Status: Accepted
- Date: 2026-05-15
- Deciders: ze
- Related: ADR 0005

## Context

The system shape (ADR 0005) defines nine features. Building them in arbitrary order means later features have to re-do scaffolding the earlier ones already needed. We pick a build order that:

1. Stands up each platform component (Postgres, Redis, Kafka, MI, APIM, WSO2 IS, SigNoz) in the smallest viable feature first.
2. Has each subsequent feature add 1–2 new enterprise capabilities without reworking earlier ones.
3. Ends with features that exercise the most advanced platform capabilities (compaction, CDC, Streams) on already-existing data.

## Decision

Build order:

| Order | Feature | New enterprise capabilities introduced |
| --- | --- | --- |
| 1 | **Quote** | Postgres + JPA + Flyway, Redis read-through cache + rate-limit, Kafka first topic, MI first outbound, APIM first product, WSO2 IS first protected endpoint, SigNoz first spans |
| 2 | **Policy bind** | Redlock distributed lock, **log-compacted** Kafka topic |
| 3 | **Payment** | Idempotency keys, DLQ pattern, APIM webhook plan |
| 4 | **Notification** | Channel-routed MI mediation, multi-topic fan-out |
| 5 | **Claim filing** | Large-payload multipart upload, OCR mediator, **mTLS partner API** |
| 6 | **Agent dashboard** | Redis Pub/Sub + Redis Streams, WebSocket from Liberty |
| 7 | **Reporting** | Kafka Streams windowed aggregation, scheduled MI task |
| 8 | **Audit trail** | Log-compacted topic consumption, retention vs compaction contrast |
| 9 | **Search** | Debezium CDC, Kafka Connect, OpenSearch |

Each feature delivery is one short PR (or a small series) **plus** updates to the `/tour` page (per ADR 0003).

## Consequences

- Features 1–3 establish the runtime; if any are skipped, later features are harder to teach because their prerequisite patterns aren't in place.
- The `/tour` page grows monotonically — students can pause the curriculum at any feature and have a coherent demo.
- Cross-cutting concerns (security, auth, observability) are first-class from feature 1, not retrofitted.
- We never have a "big rewrite" cliff — each feature is additive.

## Alternatives considered

- **All Liberty-only features first, all platform integrations last.** Rejected: students miss the integration story until very late in the curriculum and the dopamine of seeing it work.
- **Feature-first, ignore platform completeness.** Rejected: leaves Schema Registry / CDC / Pub/Sub uncovered in a track that promises them.
- **Random order, driven by student questions.** Rejected: works for a 1:1 tutorial; doesn't scale to a self-paced track.

## Revisit when

- A cohort feedback session indicates the order doesn't match how engineers actually think about insurance work (e.g., they want Claim before Payment because that's how they consume insurance themselves).
- A feature turns out to need scaffolding from a *later* feature, indicating a real ordering bug.
