# Chaos drill 27 — Kill Kafka during payment flow

Issue [#27](https://github.com/zeshaq/insurance-app/issues/27). Phase 5.

## When to run

* Before any change to `PaymentService.process()`, `PaymentEventPublisher`,
  or the Liberty Kafka client producer config (acks, delivery-timeout,
  enable-idempotence, retries).
* Before raising the Kafka producer's `acks` to `all` (would change
  the latency surface this drill measures).
* Quarterly.

## What it proves

* `POST /api/payments` returns **201** even when Kafka is down. The
  payment row commits to Postgres; the event publish is downstream and
  must not block the synchronous response. (`PaymentEventPublisher`
  fires-and-forgets via `producer.send(...).get()` with a bounded
  delivery-timeout — the timeout fires, a `WARNING` is logged, and
  the resource layer returns the payment.)
* Once Kafka recovers, the `payment-events` topic eventually has
  **at most one** record keyed by the payment id. The contract is
  "exactly-once or zero, never duplicate." The producer's `enable.
  idempotence` keeps replays from creating duplicate publishes.

## Pre-flight

```bash
podman ps --format '{{.Names}}' | grep -E '^(insurance-app|postgres|kafka|minio)$'
podman exec kafka /opt/kafka/bin/kafka-broker-api-versions.sh \
  --bootstrap-server kafka:9092 >/dev/null
```

If the broker is reachable, this prints nothing and returns 0.

## Expected outcome

```
== drill #27: kill kafka during payment  (iterations=3) ==
  -- iteration 1/3 --
    payment under Kafka-loss HTTP=201  payment.id=NNNN
    PASS  iter 1: payment under Kafka-loss returned 201
    PASS  iter 1: payment row carries an id
    payment-events records keyed by payment.id=NNNN -> 0 or 1
    PASS  iter 1: at most one payment-events record per payment.id
  ...
== drill #27 results: PASS=N  FAIL=0 ==
```

The "0 or 1" range is intentional — both are correct outcomes:

* **1** — producer's in-memory buffer survived the broker outage and
  successfully delivered the record after reconnect. This is the
  preferred outcome.
* **0** — the producer exhausted its delivery-timeout before the
  broker came back. The DLQ side (`payment-dlq`) is the contractually-
  visible record for this case; verify in the
  `kafka-payment-events-down` follow-up if you care.

**2 or more** — duplicate publish on retry. Real regression.

## Expected duration

~2-3 minutes. Each iteration: ~5 s for the racing payment, Kafka
restart (~30 s on this VM), 35 s settle window so producer retries
finish, ~10 s for the consume-and-count scan.

## How to run

```bash
bash /home/ze/insurance-app/tests/chaos/27-kill-kafka-during-payment.sh
```

Overrides:
* `TOPIC=payment-dlq` — re-target the assertion at the DLQ.
* `KAFKA_READY_TIMEOUT=180` — slower Kafka startup.

## If the assertion fails

* **`FAIL  iter N: payment under Kafka-loss returned 201`** with a 5xx
  — the publisher's failure is bubbling up into the payment write
  path. Check `PaymentService.process()` for a synchronous `.get()`
  call on the producer future without a delivery-timeout. The fix is
  in `PaymentEventPublisher` (set `delivery.timeout.ms` to something
  bounded and catch the `TimeoutException`).
* **`FAIL  iter N: at most one payment-events record per payment.id`**
  — duplicate. Re-check `enable.idempotence=true` and `acks=all` in
  the producer config. **Do not fix in this drill** — file a follow-up
  issue with the count and the offending key.

## Recovery if the drill is stuck

The trap restarts Kafka and waits for `kafka-broker-api-versions.sh`
to succeed. Manual recovery:
```bash
podman start kafka
until podman exec kafka /opt/kafka/bin/kafka-broker-api-versions.sh \
       --bootstrap-server kafka:9092 >/dev/null 2>&1; do sleep 1; done
```
