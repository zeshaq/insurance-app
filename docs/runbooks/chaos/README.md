# Phase 5 chaos drills

Five operator-run drills that verify the resilience claims the design
makes. Each drill INDUCES a specific failure, ASSERTS the expected
graceful-degradation behaviour, and RESTORES the environment via a
`trap` cleanup so a failed run never leaves the lab broken.

The drills run on the VM. They expect `podman`, `psql`, `jq`, and
`curl` to be available. Drill 29 also expects `mc` to be reachable
inside the MinIO container (it is in the upstream image).

## Drill index

| # | Drill | Disrupts | What it proves | Expected duration |
| - | ----- | -------- | -------------- | ----------------- |
| 25 | [`25-kill-liberty-mid-bind`](25-kill-liberty-mid-bind.md) | Liberty | `@Transactional` bind is all-or-nothing under hard kill; quote stays bindable | ~90 s |
| 26 | [`26-kill-postgres-during-bind`](26-kill-postgres-during-bind.md) | Postgres | Bind under DB-loss returns 5xx; Redlock self-releases by TTL; re-bind succeeds | ~3 min |
| 27 | [`27-kill-kafka-during-payment`](27-kill-kafka-during-payment.md) | Kafka | Payment write completes even when broker is down; at most one event on recovery | ~3 min |
| 28 | [`28-partition-wso2is`](28-partition-wso2is.md) | WSO2 IS | Cached JWKS keeps warm sessions valid; fresh signin fails cleanly | ~30 s |
| 29 | [`29-minio-disk-full`](29-minio-disk-full.md) | MinIO | Upload-over-quota returns 5xx (no partial success); retry succeeds | ~60 s |

Each drill is in `tests/chaos/`; each runbook is in
`docs/runbooks/chaos/`.

## Prerequisites

Run once per VM:
```bash
podman ps --format '{{.Names}}' | grep -E '^(insurance-app|postgres|kafka|wso2is|minio|customer-app|agent-app)$' | wc -l
# Expect: 7
test -r /home/ze/insurance-app/.wso2is-creds
which podman psql jq curl
```

If any of the above is missing, fix it before running drills.

## Order of operations

The drills are **independent**. You can run them in any order. Each
one's `trap` restores the artifact it touched, leaving the rest of
the stack untouched. Run them serially though — running 25 and 27 in
parallel would have them fighting over the Liberty container.

## If the lab is broken — emergency recovery

If a drill bombed in a weird way and the trap didn't fire (e.g. you
killed the drill script itself), this restores every container the
drills touch:

```bash
#!/usr/bin/env bash
# Phase 5 emergency recovery -- restart everything a chaos drill might
# have stopped, in dependency order. Idempotent.
set -e
echo "[recovery] postgres..."
podman start postgres 2>/dev/null || true
until podman exec postgres pg_isready -U insurance >/dev/null 2>&1; do sleep 1; done

echo "[recovery] kafka..."
podman start kafka 2>/dev/null || true
until podman exec kafka /opt/kafka/bin/kafka-broker-api-versions.sh \
        --bootstrap-server kafka:9092 >/dev/null 2>&1; do sleep 1; done

echo "[recovery] wso2is..."
podman start wso2is 2>/dev/null || true
podman network connect insurance-net wso2is 2>/dev/null || true

echo "[recovery] insurance-app..."
podman start insurance-app 2>/dev/null || true
until curl -sSf http://localhost:9080/health/ready >/dev/null; do sleep 1; done

echo "[recovery] minio quota..."
podman exec minio mc alias set local http://localhost:9000 minioadmin minioadmin >/dev/null 2>&1 || true
podman exec minio mc quota clear local/claims >/dev/null 2>&1 || true
podman exec minio mc admin bucket quota local/claims --clear >/dev/null 2>&1 || true

echo "[recovery] smoke..."
bash /home/ze/insurance-app/scripts/smoke.sh | tail -3
```

Save this as `scripts/chaos-recovery.sh` (it isn't in the chaos drill
itself because it's an emergency tool, not part of any drill).

## Why these five

These five attack the five non-obvious failure points in the design:

1. **Liberty mid-tx kill** — proves Jakarta EE container-managed tx works.
2. **Postgres kill** — proves Redlock self-releases (the only piece of
   coordination state that's not on the DB).
3. **Kafka kill** — proves payment writes don't depend on the broker
   (the design's "synchronous-write + async-event" split).
4. **WSO2 IS partition** — proves cached JWKS isolates Liberty from IS.
5. **MinIO out-of-space** — proves multipart uploads fail loud, not
   quiet.

Other failure modes (Redis kill, MI kill, customer-app crash, etc.)
are intentionally NOT covered by Phase 5. They're either:
* trivially recoverable from app retries (Redis cache miss), or
* covered by smoke (MI down -> claims OCR is empty, which smoke
  asserts), or
* not on the critical path (customer-app crash → 502 from the
  reverse-proxy until pod restart).

If a Phase 6 chaos pass extends this, see ADR 0005's threat model for
the prioritized list.
