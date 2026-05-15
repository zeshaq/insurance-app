# `compose/` — infrastructure for the insurance-app demo

The `compose.yaml` in this directory brings up every infrastructure container
the curriculum needs (Postgres, Redis, Kafka, MinIO, WireMock, etc.) on the
shared `insurance-net` podman network. The application containers
(`insurance-app` Liberty, `insurance-mi` WSO2 MI) are built and run separately
— they have their own Containerfiles at the repo root and at `../mi/`.

## Bring up

```
# everything
podman-compose --profile all up -d

# or one slice at a time
podman-compose --profile data up -d           # postgres, redis, adminer
podman-compose --profile messaging up -d      # kafka + topics + UI + apicurio
podman-compose --profile storage up -d        # minio
podman-compose --profile mocks up -d          # wiremock, mailpit
podman-compose --profile search up -d         # opensearch + dashboards + debezium
podman-compose --profile identity up -d       # wso2 IS
podman-compose --profile gateway up -d        # wso2 APIM
```

The `insurance-net` network and the linger setting on user `ze` are
prerequisites — both should already be in place from earlier ADRs.

## Port allocation

| Service | Host port | Inside | Use |
| --- | --- | --- | --- |
| postgres | 5432 | 5432 | DB (insurance / insurance) |
| redis | 6379 | 6379 | cache |
| adminer | 8090 | 8080 | Postgres UI |
| kafka | 9092 | 9092 | broker |
| kafka-ui | 8088 | 8080 | topic inspector |
| apicurio | 8081 | 8080 | schema registry |
| minio API | 9000 | 9000 | S3-compatible API |
| minio console | 9001 | 9001 | browser UI |
| wiremock | 8888 | 8080 | HTTP mock |
| mailpit SMTP | 1025 | 1025 | outbound mail sink |
| mailpit UI | 8025 | 8025 | view caught mail |
| opensearch | 9200, 9300 | 9200, 9300 | search index |
| opensearch dashboards | 5601 | 5601 | search UI |
| debezium | 8083 | 8083 | Kafka Connect REST |
| wso2 IS | 9444 | 9443 | identity (HTTPS) |
| wso2 APIM management | 9446 | 9443 | publisher / devportal / admin |
| wso2 APIM gateway HTTPS | 8243 | 8243 | runtime gateway |
| wso2 APIM gateway HTTP | 8280 | 8280 | runtime gateway (plain) |
| **insurance-app (Liberty)** | 9080, 9443 | 9080, 9443 | the app itself |
| **insurance-mi** | 8290, 8253 | 8290, 8253 | ESB |

## SigNoz (observability)

SigNoz isn't in this compose — see `infra/signoz/README.md`. It runs from its
own upstream compose file and joins the same `insurance-net` network.

## Public exposure

Per ADR 0007, admin UIs (minio console, kafka-ui, mailpit, opensearch
dashboards, signoz, wso2 IS, wso2 APIM, …) are added to Cloudflare + HAProxy
**lazily** — only when a module needs them. The pattern is one Cloudflare A
record + one HAProxy ACL/backend block per subdomain.

## Wiping state

`podman-compose down -v` removes containers and named volumes. Useful between
teaching cohorts. The `insurance-net` network is external and survives.
