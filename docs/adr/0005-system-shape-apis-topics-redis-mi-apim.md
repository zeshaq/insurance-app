# ADR 0005: Initial system shape — APIs, Kafka topics, Redis keys, MI flows, APIM products

- Status: Accepted (first-cut; substantial revisions deserve new ADRs)
- Date: 2026-05-15
- Deciders: ze
- Related: ADR 0004, ADR 0006

## Context

With the domain locked (ADR 0004), we need a concrete first-cut surface area for the system: the external API endpoints, the Kafka topics, the Redis keying conventions, the MI mediations, and the APIM products. Without this written down, every feature build will rediscover these decisions independently and inconsistently.

This ADR is intentionally first-cut. Each section will be refined as features land; substantial revisions (renaming topics, restructuring API products) deserve new ADRs that mark this one partially superseded.

## Decision

### API surface

Authentication: bearer JWT issued by WSO2 IS. Authorization: `@RolesAllowed` per endpoint; roles in the JWT `groups` claim — `customer`, `agent`, `underwriter`, `adjuster`, `partner`, `admin`.

| Method | Path | Audience | Notes |
| --- | --- | --- | --- |
| `POST /quotes` | request a quote | customer | rate-limited |
| `GET /quotes/{id}` | fetch a quote | customer | gateway-cached |
| `POST /quotes/{id}/accept` | bind into a policy | customer | |
| `GET /me/policies` | my policies | customer | |
| `POST /policies/{id}/cancel` | cancel | customer or agent | |
| `POST /policies/{id}/renew` | renew | customer or agent | |
| `POST /claims` | file a claim | customer | |
| `POST /claims/{id}/documents` | upload a claim document | customer | multipart |
| `GET /claims/{id}` | view a claim | customer or adjuster | |
| `POST /payments` | pay a premium | customer | idempotency-required |
| `POST /webhooks/payments` | provider callback | signed payload | webhook plan |
| `GET /agents/me/queue` | claims pending review | adjuster | |
| `POST /partners/batch` | bulk policy upload | partner | mTLS, paid plan |
| `GET /admin/audit` | audit trail | admin | |

### Kafka topics

Topics are produced over MicroProfile Reactive Messaging from Liberty. Schemas: Confluent Schema Registry with Avro for the durable topics (`policy-events`, `claim-events`, `payment-events`, `audit-events`); plain JSON for the short-lived ones during the early modules — converted to Avro when the Schema Registry module lands.

| Topic | Key | Retention | Producers | Consumers |
| --- | --- | --- | --- | --- |
| `quote-events` | quote_id | 7d | quote service | analytics, notification |
| `policy-events` | policy_id | **log-compacted** | policy service | search indexer, audit |
| `claim-events` | claim_id | 30d | claim service | fraud scorer, notification, indexer |
| `payment-events` | payment_id | 90d | payment service | ledger, reconciliation, notification |
| `notification-requested` | customer_id | 7d | various | MI (channel router) |
| `audit-events` | entity_id | **log-compacted** | every service | search indexer |
| `policy-cdc` | postgres-pk | 7d | Debezium | search indexer |

### Redis usage patterns

| Pattern | Keys | TTL | Feature |
| --- | --- | --- | --- |
| Quote cache (read-through) | `quote:{id}` | 15m | 1 — Quote |
| Session store | `session:{token}` | 1h sliding | 1+ |
| Per-user rate limit (sliding window) | `ratelimit:quote:{customer_id}` (sorted set) | window | 1 |
| Distributed lock (Redlock) | `lock:policy:{id}` | 30s | 2 — Policy bind |
| Payment idempotency | `idempotency:{key}` | 24h | 3 — Payment |
| Activity feed (Redis Streams) | `feed:agent:{id}` | trim by length | 6 — Agent dashboard |
| Pub/Sub real-time | channel `agent:{id}` | n/a | 6 |
| Template cache | `template:{name}:{lang}` | 1h | 5 — Notification |

### WSO2 MI flows

| Sequence | Purpose | Pattern |
| --- | --- | --- |
| `outbound/credit-bureau` | quote: pull credit / risk score | HTTP call, circuit breaker, cache, fallback |
| `outbound/payment-gateway` | payment: charge card | HTTP call, retry w/ backoff, DLQ |
| `outbound/document-ocr` | claim: OCR uploaded docs | HTTP call, large-payload streaming |
| `inbound/partner-batch` | partner: bulk policy CSV | scheduled task or webhook; transform; emit Kafka events |
| `internal/notification-router` | fan out to email/SMS/push | switch by channel, call channel-specific mediator |
| `scheduled/billing-run` | nightly: assess premiums due | cron, scan, emit `payment-requested` |

### APIM products

| Product | Audience | Auth | Throttling tier | APIs published |
| --- | --- | --- | --- | --- |
| **Customer API** | end customers | OAuth2 (WSO2 IS) | Bronze (60 req/min) | Quote, Policy, Claim, Payment, Notification |
| **Agent API** | internal staff | OAuth2 (WSO2 IS) + role | Gold (5 000 req/min) | All customer APIs + Agent queue + Audit (read) |
| **Partner API** | external brokers | mTLS + OAuth2 client-creds | Paid tier | Partner batch upload, webhook receivers |
| **Webhook API** | external providers (payment, OCR) | signed payload (HMAC) | unthrottled (separate path) | Payment webhooks, OCR callbacks |
| **Admin API** | ops | OAuth2 + role | unthrottled (internal-only) | Audit, system metrics, feature flags |

## Consequences

- Topic and key conventions are set early; renaming later is painful enough to do via ADR.
- Schema Registry adoption is deferred to the Kafka deep-dive module to avoid front-loading complexity in the first feature.
- Five APIM products is more than most demo apps; the over-segmentation is intentional — students need to *see* multi-tenant gateway concerns.
- Cross-cutting concerns (auth, observability) are first-class from feature 1, not retrofitted.

## Alternatives considered

- **One topic per entity-type rather than per state-changes.** Rejected: doesn't allow multi-consumer fan-out cleanly.
- **JSON everywhere, no Schema Registry.** Rejected: a core Kafka teaching point is Avro evolution.
- **One mega-API product in APIM.** Rejected: hides product / plan / throttling features.

## Revisit when

- Topic count climbs past 12 (consider a naming-policy ADR).
- A real document store (S3 / MinIO) is added — affects claims-pipeline shape.
- Schema Registry is wired in: revisit Avro vs Protobuf at that point.
- A second APIM tenant is introduced — pricing/product separation may need its own structure.
