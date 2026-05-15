# SigNoz on insurance-net

SigNoz runs as its own multi-container stack (clickhouse + clickhouse-keeper +
otel-collector + query-service + frontend + alertmanager + migrator).
Maintaining a hand-rolled subset would mean tracking the upstream's version
matrix; instead we use SigNoz's own compose unchanged, pinned to a release tag.

## One-time setup

```
git clone --branch v0.55.0 https://github.com/SigNoz/signoz.git ~/signoz
cd ~/signoz/deploy/docker/clickhouse-setup
```

Make two edits to that compose file:

1. Replace the top-level `networks:` block with:
   ```yaml
   networks:
     default:
       name: insurance-net
       external: true
   ```
   So every SigNoz container joins our shared bridge alongside Liberty and the
   rest. Liberty exports OTLP to `otel-collector:4317` by container name.

2. (Optional) Publish the frontend on `:3301` to the host — it's already in the
   upstream compose; check that nothing else on the VM has grabbed that port.

## Bring up

```
podman-compose up -d
```

First boot takes ~90 seconds while clickhouse initialises. Frontend at
`http://localhost:3301`.

## Liberty side

The Liberty container needs these env vars to start exporting traces and
metrics to the collector:

```
OTEL_SERVICE_NAME=insurance-app
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
OTEL_EXPORTER_OTLP_PROTOCOL=grpc
OTEL_METRICS_EXPORTER=otlp
OTEL_TRACES_EXPORTER=otlp
OTEL_LOGS_EXPORTER=otlp
OTEL_SDK_DISABLED=false
```

`OTEL_SDK_DISABLED=false` is the gotcha — Liberty's `mpTelemetry-1.1` feature
defaults to disabled.

## Public exposure

When ready, add `signoz.insurance-app.comptech-lab.com` to Cloudflare and the
HAProxy frontend per ADR 0007 — points at `30.30.26.1:3301`.

## Upgrades

`cd ~/signoz && git fetch && git checkout <tag>` then bring the stack down and
back up. ClickHouse data persists in named volumes; trace history survives.
