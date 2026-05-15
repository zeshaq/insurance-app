# ADR 0008: Infrastructure compose — services, profiles, scope

- Status: Accepted
- Date: 2026-05-15
- Deciders: ze
- Related: ADR 0005 (system shape), ADR 0007 (public exposure)

## Context

ADR 0005 named the platform components the demo needs — Postgres, Redis, Kafka, WSO2 IS, WSO2 APIM, SigNoz. Beyond those, an honest teaching environment also wants object storage (MinIO), mockable external services (WireMock), an SMTP sink (Mailpit), a Kafka inspector (kafka-ui), a schema registry (Apicurio), and a search index (OpenSearch + Debezium CDC). A Postgres UI (Adminer) is a quality-of-life addition that costs nothing.

Standing these up one `podman run` at a time, mid-module, hides the topology from anyone reading the repo and turns "set up the environment" into per-cohort tribal knowledge. A declarative compose file is the obvious shape.

## Decision

A single `compose/compose.yaml` declares all infrastructure services for the demo. Application containers (`insurance-app`, `insurance-mi`) stay out of compose — they have their own Containerfiles and build pipelines and are versioned separately.

### Services in scope

| Service | Image | Role |
| --- | --- | --- |
| **postgres** | `postgres:17-alpine` | Primary datastore. `wal_level=logical` for Debezium CDC (feature 9). |
| **redis** | `redis:7-alpine` | Cache + sessions + Redlock. |
| **adminer** | `adminer` | Browser-based Postgres inspection. Quality-of-life only. |
| **kafka** | `bitnami/kafka:3.8` | Event broker. KRaft mode — no ZooKeeper. |
| **kafka-init** | (same) | One-shot: creates the topics from ADR 0005 and exits. |
| **kafka-ui** | `provectuslabs/kafka-ui` | Browser-based topic / partition / consumer-group viewer. Mandatory for teaching. |
| **apicurio** | `apicurio/apicurio-registry-mem` | Apache-2.0 Avro/Protobuf schema registry; in-memory variant. |
| **minio** | `minio/minio` | S3-compatible object store for claim documents (feature 5). |
| **wiremock** | `wiremock/wiremock:3.10.0` | Configurable HTTP mock for the MI outbound mediations (credit bureau, payment gateway, OCR). The highest-leverage non-obvious addition — students see retries, failures, circuit-breaker trips deterministically. |
| **mailpit** | `axllent/mailpit` | SMTP sink + web UI for the notification feature (feature 5). |
| **opensearch** + **dashboards** | `opensearchproject/*` | Search index for feature 9 (CDC-fed). |
| **debezium** | `debezium/connect:2.7` | Kafka Connect + Postgres CDC connector. Feature 9. |
| **wso2is** | `wso2/wso2is:7.0.0` | OIDC provider (feature 5 — identity). |
| **wso2apim** | `wso2/wso2am:4.4.0` | API gateway + dev portal (feature 6). |

**SigNoz is deliberately excluded from this compose** — it has its own upstream multi-container stack (clickhouse + collector + frontend + …) and tracking their version matrix in a hand-rolled compose is busywork. Instructions for cloning SigNoz, attaching it to `insurance-net`, and pointing Liberty at it live at `compose/infra/signoz/README.md`.

### Profiles

Each service belongs to a topic profile so subsets can be brought up alone:

| Profile | Services |
| --- | --- |
| `data` | postgres, redis, adminer |
| `messaging` | kafka, kafka-init, kafka-ui, apicurio |
| `storage` | minio |
| `mocks` | wiremock, mailpit |
| `search` | opensearch, opensearch-dashboards, debezium |
| `identity` | wso2is |
| `gateway` | wso2apim |
| `all` | everything |

Bring up everything with `podman-compose --profile all up -d`, or one slice with `podman-compose --profile data up -d`. The slices map onto the feature build order (ADR 0006) — a module that introduces caching brings the `data` profile up first.

### Network

All services join the existing external `insurance-net` user-defined bridge. Container names are DNS names within the network (e.g. Liberty reaches Postgres at `postgres:5432`, the kafka-ui reaches Kafka at `kafka:9092`).

### Persistence

Named volumes for state that should survive a recreate:

- `postgres_data` — schema + data
- `kafka_data` — broker logs
- `minio_data` — object store
- `opensearch_data` — index

WSO2 IS / APIM run with their default in-container H2 databases. For the demo, that's fine — restart means fresh tenant config, which is sometimes what we want. Production would attach external databases (out of scope).

### Public exposure

Per ADR 0007, admin UIs are added to Cloudflare + HAProxy **lazily** — only when a module's exercises actually need browser access. Anticipated subdomains, in rough order of "when added":

| Subdomain | Backend | Module |
| --- | --- | --- |
| `signoz.insurance-app.comptech-lab.com` | signoz-frontend `:3301` | early (observability) |
| `minio.insurance-app.comptech-lab.com` | minio console `:9001` | feature 5 (Claim) |
| `mail.insurance-app.comptech-lab.com` | mailpit `:8025` | feature 5 (Notification) |
| `kafka.insurance-app.comptech-lab.com` | kafka-ui `:8088` | feature 4 (messaging) |
| `is.insurance-app.comptech-lab.com` | wso2is `:9444` | feature 5 (Identity) |
| `apim.insurance-app.comptech-lab.com` | wso2apim `:9446` | feature 6 (Gateway) |
| `gateway.insurance-app.comptech-lab.com` | wso2apim `:8243` | feature 6 |
| `search.insurance-app.comptech-lab.com` | opensearch dashboards `:5601` | feature 9 |

Adminer, WireMock, Debezium, Apicurio admin: kept internal — accessed by tunneling (`ssh -L`) or directly from the VM. Externalising them adds attack surface for tools that students should reach during a controlled session, not from arbitrary networks.

## Consequences

Positive:
- One file documents the stack. New engineers (and future Claude sessions) need read only one thing to understand the demo's topology.
- Bring-up is one command per cohort.
- Teardown (`podman-compose down -v`) clears state between sessions cleanly.
- Profiles map onto the feature build order; the curriculum's narrative ("now we add Redis") corresponds to a `--profile data` step rather than fabricating a podman invocation.

Negative / accepted trade-offs:
- The pedagogical "now we add a container" moment softens — the container exists from compose. Per ADR 0003 framing, the lesson is the *code* that uses the container, which is closer to how real engineers learn these tools anyway.
- Compose adds a tool dependency (`podman-compose`) on the VM.
- One large file (~200 lines) vs the ten-podman-run-blocks alternative.

## Alternatives considered

- **Quadlet systemd units (one per service).** Modern podman's recommended approach for long-running services. Rejected for the demo: less recognised by readers, more boilerplate per service, less easily torn down between teaching cohorts. Worth revisiting when the demo moves toward production-shape.
- **All `podman run` invocations in a setup script.** Imperative. Doesn't survive copy-paste edits the way declarative compose does.
- **Helm chart for OCP from day one.** Wrong altitude — the demo runs on a VM, and OCP is a deliberate "later track" per ADR 0007.
- **Inlining SigNoz into our compose.** Their stack moves; we'd be re-syncing constantly. Reference upstream by version tag and let them own the topology of their own stack.

## Revisit when

- A service in this list grows to need a dedicated compose or operator (likely WSO2 APIM once we attach external Postgres + an external Redis for clustering).
- We add a second teaching VM (per-cohort or per-student) — at that point a per-VM Ansible / Terraform shape is probably right.
- The demo migrates to OpenShift — compose retires, Helm/Kustomize takes over.
