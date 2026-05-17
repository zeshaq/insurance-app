#!/usr/bin/env bash
# tests/chaos/27-kill-kafka-during-payment.sh -- issue #27, Phase 5
#
# Drill: kill the Kafka broker mid-payment-flow.
#
# What this proves
#   * POST /api/payments returns 201 even when Kafka is down: the
#     core payment write (Postgres row + idempotency entry) is on
#     the synchronous path; the payment-events publish is downstream
#     and must not block the response.
#     (PaymentEventPublisher in src/.../payment/ uses an async
#     KafkaProducer.send(...) call. Acceptable degradation when the
#     broker is unreachable is: warning logged, payment row committed,
#     201 returned to the client.)
#   * Once Kafka comes back, the payment-events topic eventually
#     contains EXACTLY ONE record keyed by the payment id (or the
#     Idempotency-Key on the DLQ side). No duplicate publishes from
#     producer-side retries.
#
# Method
#   1. Mint JWT, create a quote + bound policy.
#   2. Race: POST /api/payments (background) + podman stop kafka.
#   3. Assert the bind got a 201 (payment created despite Kafka
#      outage). If the producer is configured to block on send (it
#      shouldn't be), we'll see a 5xx -- that's a regression worth
#      flagging.
#   4. Restart kafka, wait for the broker to accept connections.
#   5. Read the payment-events topic from offset 0 for the partition
#      that hashes for our key, counting records keyed by the
#      payment id. Expect exactly 1 (or 0 if the producer dropped
#      the event entirely -- also acceptable, since payment-events
#      is the success-ledger and we have a DLQ for failed publishes;
#      but a duplicate is a real bug).
#   6. Loop 3 times.
#
# Trap
#   restore_env always restarts kafka at exit.
#
# Run location: VM. Expected wall time: ~3 min for 3 iterations.

set -eo pipefail

CREDS="${WSO2IS_CREDS:-/home/ze/insurance-app/.wso2is-creds}"
[ -f "$CREDS" ] && . "$CREDS"

LIBERTY_BASE="${LIBERTY_BASE:-http://localhost:9080}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka}"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:9092}"
TOPIC="${TOPIC:-payment-events}"
ITERATIONS="${ITERATIONS:-3}"
KAFKA_READY_TIMEOUT="${KAFKA_READY_TIMEOUT:-120}"

WORK="$(mktemp -d)"
PASS=0
FAIL=0

restore_env() {
  echo "[trap] restoring kafka..."
  podman start "$KAFKA_CONTAINER" >/dev/null 2>&1 || true
  local t=0
  while [ $t -lt "$KAFKA_READY_TIMEOUT" ]; do
    if podman exec "$KAFKA_CONTAINER" \
         /opt/kafka/bin/kafka-broker-api-versions.sh \
         --bootstrap-server "$KAFKA_BOOTSTRAP" \
         >/dev/null 2>&1; then
      echo "[trap] kafka ready"
      break
    fi
    sleep 1
    t=$((t+1))
  done
  rm -rf "$WORK"
}
trap restore_env EXIT

mint_jwt() {
  curl -k -sS -X POST \
    -u "$WSO2IS_CLIENT_ID:$WSO2IS_CLIENT_SECRET" \
    "$WSO2IS_TOKEN_URL" \
    -d "grant_type=client_credentials" \
    | jq -r .access_token
}

wait_kafka_ready() {
  local t=0
  while [ $t -lt "$KAFKA_READY_TIMEOUT" ]; do
    if podman exec "$KAFKA_CONTAINER" \
         /opt/kafka/bin/kafka-broker-api-versions.sh \
         --bootstrap-server "$KAFKA_BOOTSTRAP" \
         >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    t=$((t+1))
  done
  return 1
}

count_events_for_key() {
  # Drains the topic to a temp file, counts records where the key matches.
  # Liberty's PaymentEventPublisher keys records by payment.id (as a string).
  local key="$1"
  local out="$WORK/dump.txt"
  timeout 25 podman exec "$KAFKA_CONTAINER" \
    /opt/kafka/bin/kafka-console-consumer.sh \
      --bootstrap-server "$KAFKA_BOOTSTRAP" \
      --topic "$TOPIC" \
      --from-beginning \
      --timeout-ms 10000 \
      --property print.key=true \
      --property key.separator='|' \
      > "$out" 2>/dev/null || true
  # Count exact-match keys in column 1.
  awk -F'|' -v k="$key" '$1 == k { c++ } END { print c+0 }' "$out"
}

check() {
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then
    printf "    PASS  %s\n" "$name"
    PASS=$((PASS+1))
  else
    printf "    FAIL  %s\n" "$name"
    FAIL=$((FAIL+1))
  fi
}

echo "== drill #27: kill kafka during payment  (iterations=$ITERATIONS) =="

AT=$(mint_jwt)
[ -z "$AT" ] || [ "$AT" = "null" ] && { echo "ERROR: JWT mint failed"; exit 2; }

if ! wait_kafka_ready; then
  echo "ERROR: kafka not ready at start"
  exit 2
fi

for i in $(seq 1 "$ITERATIONS"); do
  echo "  -- iteration $i/$ITERATIONS --"

  # Setup: quote + bound policy. We do these BEFORE we kill Kafka so
  # they aren't affected by the chaos.
  VIN="CHAOS27-$i-$$"
  QID=$(curl -sSf -X POST "$LIBERTY_BASE/api/quotes" \
    -H "Authorization: Bearer $AT" -H "Content-Type: application/json" \
    -d "{\"vehicleVin\":\"$VIN\",\"driverAge\":29,\"coverageType\":\"BASIC\"}" \
    | jq -r .id)

  POL=$(curl -sSf -X POST "$LIBERTY_BASE/api/policies" \
    -H "Authorization: Bearer $AT" -H "Content-Type: application/json" \
    -d "{\"quoteId\":$QID}" \
    | jq -r .policyNumber)

  if [ -z "$POL" ] || [ "$POL" = "null" ]; then
    echo "ERROR: policy bind failed on iter $i"
    FAIL=$((FAIL+1))
    continue
  fi

  IDEM_KEY="chaos27-$i-$$-$(date +%s)"

  # Race: POST /api/payments + kafka stop.
  (
    curl -sS -o "$WORK/pay-$i.body" -w "%{http_code}" \
      --max-time 60 \
      -X POST "$LIBERTY_BASE/api/payments" \
      -H "Authorization: Bearer $AT" \
      -H "Content-Type: application/json" \
      -H "Idempotency-Key: $IDEM_KEY" \
      -d "{\"policyNumber\":\"$POL\",\"amount\":42.42,\"currency\":\"USD\"}" \
      > "$WORK/pay-$i.code" 2>/dev/null
  ) &
  PAY_PID=$!

  sleep 0.2  # give Liberty a chance to start the request
  podman stop -t 1 "$KAFKA_CONTAINER" >/dev/null 2>&1 || true

  wait "$PAY_PID" 2>/dev/null || true

  CODE=$(cat "$WORK/pay-$i.code" 2>/dev/null || echo "")
  BODY=$(cat "$WORK/pay-$i.body" 2>/dev/null || echo "")
  PAY_ID=$(echo "$BODY" | jq -r '.id // empty' 2>/dev/null)
  echo "    payment under Kafka-loss HTTP=$CODE  payment.id=${PAY_ID:-<none>}"

  check "iter $i: payment under Kafka-loss returned 201" \
    bash -c "[ '$CODE' = '201' ]"
  check "iter $i: payment row carries an id" \
    bash -c "[ -n '$PAY_ID' ] && [ '$PAY_ID' != 'null' ]"

  # Restore kafka, then check the topic.
  podman start "$KAFKA_CONTAINER" >/dev/null
  if ! wait_kafka_ready; then
    echo "ERROR: kafka did not recover within ${KAFKA_READY_TIMEOUT}s"
    FAIL=$((FAIL+1))
    continue
  fi
  # Producer's internal retries (delivery timeout) can take up to ~30s
  # after the broker reappears before they either land or are dropped.
  # Wait a generous slice so the assertion sees the steady state.
  sleep 35

  COUNT=$(count_events_for_key "${PAY_ID:-__none__}")
  echo "    payment-events records keyed by payment.id=$PAY_ID -> $COUNT"
  # The contract: 0 or 1 -- never 2+. 0 means the producer dropped
  # after exhausting retries; 1 means it eventually landed. Either is
  # a fine resilient outcome. >=2 means duplicate publishes, which IS
  # a regression.
  check "iter $i: at most one payment-events record per payment.id" \
    bash -c "[ '$COUNT' -le 1 ]"
done

echo
echo "== drill #27 results: PASS=$PASS  FAIL=$FAIL =="
[ "$FAIL" -eq 0 ]
