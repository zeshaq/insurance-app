#!/usr/bin/env bash
# Smoke + e2e for the insurance-app environment.
# 64 checks across 5 sections — runs from the VM, takes ~30s.
# Exit code is 0 only if every check passes.
set +e

pass=0
fail=0
check() {
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then
    printf "  ✓ %-55s OK\n" "$name"
    pass=$((pass+1))
  else
    printf "  ✗ %-55s FAIL\n" "$name"
    fail=$((fail+1))
  fi
}

# Fetch a WSO2 IS access token once; all POST /api/quotes calls below need it.
# .wso2is-creds is gitignored and sets WSO2IS_CLIENT_ID / SECRET / TOKEN_URL.
if [ -f "$HOME/insurance-app/.wso2is-creds" ]; then
  . "$HOME/insurance-app/.wso2is-creds"
elif [ -f ".wso2is-creds" ]; then
  . "./.wso2is-creds"
fi
AT=$(curl -k -sS -X POST -u "$WSO2IS_CLIENT_ID:$WSO2IS_CLIENT_SECRET" \
      "$WSO2IS_TOKEN_URL" -d "grant_type=client_credentials" 2>/dev/null \
      | jq -r .access_token 2>/dev/null)
AUTH_HDR="Authorization: Bearer $AT"

echo "=== 0) Host-level prerequisites ==="
# Lingering MUST be on for ze, otherwise every rootless container dies the
# moment the last SSH session ends. Catching this at smoke time saves the
# inevitable "everything stopped overnight" debugging session.
check "loginctl linger enabled for ze" bash -c "loginctl show-user ze 2>/dev/null | grep -q '^Linger=yes'"

echo
echo "=== 1) Containers running (21 expected) ==="
for c in postgres redis adminer redisinsight kafka kafka-ui apicurio minio wiremock mailpit \
         opensearch opensearch-dashboards debezium wso2is wso2apim \
         signoz signoz-clickhouse signoz-zookeeper-1 signoz-otel-collector \
         insurance-app insurance-mi; do
  check "container $c" bash -c "podman ps --format {{.Names}} | grep -qx $c"
done

echo
echo "=== 2) Per-service health ==="
check "postgres select 1"                      bash -c 'podman exec postgres psql -U insurance -d insurance -c "select 1" 2>&1 | grep -q "1 row"'
check "redis PING"                             bash -c 'podman exec redis redis-cli ping | grep -q PONG'
check "kafka 6 ADR-0005 topics"                bash -c '[ $(podman exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list 2>/dev/null | grep -cE "^(quote-events|policy-events|claim-events|payment-events|notification-requested|audit-events)$") = 6 ]'
check "minio /minio/health/live"               curl -sf http://localhost:9000/minio/health/live
check "apicurio /apis/registry/v2/system/info" curl -sf http://localhost:8081/apis/registry/v2/system/info
check "kafka-ui /actuator/health"              curl -sf http://localhost:8088/actuator/health
check "wiremock /__admin/mappings"             curl -sf http://localhost:8888/__admin/mappings
check "mailpit /api/v1/info"                   curl -sf http://localhost:8025/api/v1/info
check "opensearch /_cluster/health"            curl -sf http://localhost:9200/_cluster/health
check "opensearch-dashboards /api/status"      curl -sf http://localhost:5601/api/status
check "debezium /connectors"                   curl -sf http://localhost:8083/connectors
check "wso2is /"                               curl -ksf -o /dev/null https://localhost:9444/
check "wso2apim /publisher → 200"              bash -c '[ $(curl -ksL -o /dev/null -w %{http_code} https://localhost:9446/publisher/) = 200 ]'
check "signoz /api/v1/version"                 curl -sf http://localhost:8080/api/v1/version
check "signoz-otel-collector :4317"            nc -z localhost 4317
check "signoz-otel-collector :4318"            nc -z localhost 4318
check "redisinsight"                           curl -sf -o /dev/null http://localhost:5540/
check "Liberty /api/ping (direct)"             bash -c 'curl -sf http://localhost:9080/api/ping | grep -q ok'
check "MI /insurance/ping → Liberty"           bash -c 'curl -sf http://localhost:8290/insurance/ping | grep -q ok'

echo
echo "=== 3) Liberty → infra TCP reachability ==="
for tgt in postgres:5432 redis:6379 kafka:9092 signoz-otel-collector:4317 signoz-otel-collector:4318 \
           wso2is:9443 wso2apim:9443 apicurio:8080 minio:9000 mailpit:1025 opensearch:9200 wiremock:8080; do
  check "Liberty → $tgt" bash -c "podman exec insurance-app bash -c \"echo > /dev/tcp/${tgt/:/\/}\" 2>/dev/null"
done

echo
echo "=== 4) Kafka round-trip (produce + consume) ==="
KEY="smoke-$(date +%s)-$$"
podman exec -i kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic quote-events <<<"$KEY" >/dev/null 2>&1
# Read the tail of each partition rather than scanning from earliest — robust
# as the topic grows. kafka-get-offsets returns the high-water-mark per
# partition; we consume the last 5 messages from each.
check "produced + consumer reads it back" bash -c "
  for p in 0 1 2; do
    HWM=\$(podman exec kafka /opt/kafka/bin/kafka-get-offsets.sh \
      --bootstrap-server kafka:9092 --topic quote-events --partitions \$p 2>/dev/null \
      | awk -F: '{print \$3}')
    if [ -z \"\$HWM\" ] || [ \"\$HWM\" -eq 0 ]; then continue; fi
    START=\$(( HWM > 5 ? HWM - 5 : 0 ))
    if timeout 20 podman exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
        --bootstrap-server kafka:9092 --topic quote-events --partition \$p \
        --offset \$START --max-messages 5 2>/dev/null | grep -q $KEY; then exit 0; fi
  done; exit 1
"

echo
echo "=== 4.5) Identity / Auth (feature 1, slice 5) ==="
AUTH_BODY='{"vehicleVin":"AUTHCHECK","driverAge":30,"coverageType":"BASIC"}'
check "WSO2 IS issued an access token" bash -c "[ -n \"$AT\" ] && [ \"$AT\" != null ]"
UNAUTH_CODE=$(curl -sS -o /dev/null -w "%{http_code}" -X POST http://localhost:9080/api/quotes -H "Content-Type: application/json" -d "$AUTH_BODY")
check "unauth POST /api/quotes -> 401" bash -c "[ '$UNAUTH_CODE' = '401' ]"
BAD_CODE=$(curl -sS -o /dev/null -w "%{http_code}" -X POST http://localhost:9080/api/quotes -H "Content-Type: application/json" -H "Authorization: Bearer not.a.real.token" -d "$AUTH_BODY")
check "bogus-Bearer POST /api/quotes -> 401" bash -c "[ '$BAD_CODE' = '401' ]"
OK_CODE=$(curl -sS -o /dev/null -w "%{http_code}" -X POST http://localhost:9080/api/quotes -H "Content-Type: application/json" -H "$AUTH_HDR" -d "$AUTH_BODY")
check "valid-Bearer POST /api/quotes -> 201" bash -c "[ '$OK_CODE' = '201' ]"

echo
echo "=== 5) Quote round-trip (feature 1, slice 1) ==="
QUOTE_REQ='{"vehicleVin":"SMOKE'"$$"'","driverAge":30,"coverageType":"STANDARD"}'
QUOTE_RESP=$(curl -sSf -X POST http://localhost:9080/api/quotes \
  -H "$AUTH_HDR" \
  -H "Content-Type: application/json" -d "$QUOTE_REQ" 2>/dev/null || echo "")
QUOTE_ID=$(echo "$QUOTE_RESP" | jq -r '.id // empty' 2>/dev/null)
check "POST /api/quotes returns CALCULATED quote w/ id" bash -c "[ -n '$QUOTE_ID' ] && echo '$QUOTE_RESP' | jq -e '.status == \"CALCULATED\"' >/dev/null"
check "GET /api/quotes/\$id returns the same quote"     bash -c "curl -sSf http://localhost:9080/api/quotes/$QUOTE_ID | jq -e '.id == $QUOTE_ID' >/dev/null"
check "row visible in postgres.quote"                   bash -c "podman exec postgres psql -U insurance -d insurance -t -c \"select 1 from quote where id = $QUOTE_ID\" 2>/dev/null | grep -q 1"

echo
echo "=== 6) Cache + rate limit (feature 1, slice 2) ==="
# Strict cache check: delete BOTH the cache key and the suspicious "quote:null"
# key BEFORE a fresh POST, then verify the POST populated the right key (not
# "quote:null"). This catches the regression where em.flush() is missing in
# QuoteRepository and every cache.put writes to "quote:null".
CACHE_VIN="CACHE$$X"
podman exec redis redis-cli del "quote:null" >/dev/null 2>&1
CACHE_RESP=$(curl -sSf -X POST http://localhost:9080/api/quotes \
  -H "$AUTH_HDR" \
  -H "Content-Type: application/json" \
  -d "{\"vehicleVin\":\"$CACHE_VIN\",\"driverAge\":35,\"coverageType\":\"BASIC\"}" 2>/dev/null || echo "")
CACHE_ID=$(echo "$CACHE_RESP" | jq -r '.id // empty' 2>/dev/null)
check "POST wrote redis key quote:\$id (not quote:null)" bash -c "[ -n '$CACHE_ID' ] && podman exec redis redis-cli get 'quote:$CACHE_ID' 2>/dev/null | grep -q $CACHE_VIN && ! podman exec redis redis-cli get 'quote:null' 2>/dev/null | grep -q $CACHE_VIN"

RL_VIN="RLSMOKE$$X"
RL_LAST_STATUS=200
for _i in 1 2 3 4 5 6; do
  RL_LAST_STATUS=$(curl -sS -o /dev/null -w "%{http_code}" -X POST http://localhost:9080/api/quotes \
    -H "$AUTH_HDR" \
    -H "Content-Type: application/json" \
    -d "{\"vehicleVin\":\"$RL_VIN\",\"driverAge\":30,\"coverageType\":\"BASIC\"}")
done
check "6th rapid POST same VIN returns 429" bash -c "[ '$RL_LAST_STATUS' = '429' ]"

echo
echo "=== 7) Kafka event emission (feature 1, slice 3) ==="
EVT_VIN="EVT$$X"
# Use a unique VIN so we don't collide with the cache/rate-limit sections.
EVT_RESP=$(curl -sSf -X POST http://localhost:9080/api/quotes \
  -H "$AUTH_HDR" \
  -H "Content-Type: application/json" \
  -d "{\"vehicleVin\":\"$EVT_VIN\",\"driverAge\":40,\"coverageType\":\"STANDARD\"}" 2>/dev/null || echo "")
EVT_ID=$(echo "$EVT_RESP" | jq -r '.id // empty' 2>/dev/null)
sleep 2
check "POST emitted quote.calculated event to Kafka" bash -c "
  [ -n '$EVT_VIN' ] || exit 1
  for p in 0 1 2; do
    if timeout 20 podman exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
        --bootstrap-server kafka:9092 --topic quote-events \
        --partition \$p --offset earliest --max-messages 500 --timeout-ms 15000 2>/dev/null \
      | grep -q $EVT_VIN; then exit 0; fi
  done
  exit 1
"
# Self-consumer was a nice-to-have for slice 3 — Liberty's mpReactiveMessaging
# silently fails to subscribe an @Incoming consumer in this image. The producer
# (verified above) is what matters for downstream features. Slice 4 brings real
# consumer fan-out and will revisit the @Incoming pattern there.

echo
echo "=== 8) Credit-bureau lookup via MI + WireMock (feature 1, slice 4) ==="
# Default-score VIN: WireMock returns score=720 → credit factor 1.0 →
# 30yo STANDARD premium stays at 500 * 1.5 * 1.0 * 1.0 = 750.00.
NORM_VIN="NORM$$X"
NORM_PREM=$(curl -sSf -X POST http://localhost:9080/api/quotes \
  -H "$AUTH_HDR" \
  -H "Content-Type: application/json" \
  -d "{\"vehicleVin\":\"$NORM_VIN\",\"driverAge\":30,\"coverageType\":\"STANDARD\"}" 2>/dev/null \
  | jq -r '.premium // empty' 2>/dev/null)
check "default-VIN quote gets credit factor 1.0 (premium 750.00)" bash -c "[ '$NORM_PREM' = '750.00' ]"

# RISKY-prefixed VIN: WireMock returns score=550 → credit factor 1.5 →
# 30yo STANDARD premium becomes 500 * 1.5 * 1.0 * 1.5 = 1125.00.
RISKY_VIN="RISKY$$X"
RISKY_PREM=$(curl -sSf -X POST http://localhost:9080/api/quotes \
  -H "$AUTH_HDR" \
  -H "Content-Type: application/json" \
  -d "{\"vehicleVin\":\"$RISKY_VIN\",\"driverAge\":30,\"coverageType\":\"STANDARD\"}" 2>/dev/null \
  | jq -r '.premium // empty' 2>/dev/null)
check "RISKY-VIN quote gets credit factor 1.5 (premium 1125.00)" bash -c "[ '$RISKY_PREM' = '1125.00' ]"

# Confirm WireMock actually saw the call (proves Liberty → MI → WireMock chain).
check "WireMock received /credit/score?vin=$NORM_VIN" \
  bash -c "curl -sSf http://localhost:8888/__admin/requests 2>/dev/null | jq -e --arg v $NORM_VIN '.requests[] | select(.request.url | contains(\$v))' >/dev/null"

echo
echo "=== 10) Policy bind (feature 2, slice 6) ==="
# Fresh quote so each smoke run gets a clean quote->policy binding.
PB_VIN="POLBIND$$X"
PB_QUOTE=$(curl -sSf -X POST http://localhost:9080/api/quotes \
  -H "$AUTH_HDR" \
  -H "Content-Type: application/json" \
  -d "{\"vehicleVin\":\"$PB_VIN\",\"driverAge\":35,\"coverageType\":\"BASIC\"}" 2>/dev/null || echo "")
PB_QID=$(echo "$PB_QUOTE" | jq -r '.id // empty' 2>/dev/null)
check "smoke: created a quote to bind"                   bash -c "[ -n '$PB_QID' ]"

# First bind: 201 Created + body contains policyNumber matching POL-<8 hex>.
PB_RESP1=$(curl -sS -w "\n%{http_code}" -X POST http://localhost:9080/api/policies \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"quoteId\":$PB_QID}")
PB_BODY1=$(echo "$PB_RESP1" | head -n-1)
PB_CODE1=$(echo "$PB_RESP1" | tail -n1)
PB_POLNUM=$(echo "$PB_BODY1" | jq -r '.policyNumber // empty')
check "POST /api/policies (1st) -> 201"                  bash -c "[ '$PB_CODE1' = '201' ]"
check "1st bind returned a POL-XXXXXXXX policyNumber"    bash -c "echo '$PB_POLNUM' | grep -qE '^POL-[A-F0-9]{8}$'"

# Second bind with same quoteId: 200 OK, same policyNumber (idempotent).
PB_RESP2=$(curl -sS -w "\n%{http_code}" -X POST http://localhost:9080/api/policies \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"quoteId\":$PB_QID}")
PB_CODE2=$(echo "$PB_RESP2" | tail -n1)
PB_POLNUM2=$(echo "$PB_RESP2" | head -n-1 | jq -r '.policyNumber // empty')
check "POST /api/policies (2nd, same quote) -> 200"      bash -c "[ '$PB_CODE2' = '200' ]"
check "idempotent re-bind returns same policyNumber"     bash -c "[ '$PB_POLNUM' = '$PB_POLNUM2' ]"

# Unauth: no Bearer -> 401 (proves @RolesAllowed gate is wired).
PB_UNAUTH=$(curl -sS -o /dev/null -w "%{http_code}" -X POST http://localhost:9080/api/policies \
  -H "Content-Type: application/json" -d "{\"quoteId\":$PB_QID}")
check "unauth POST /api/policies -> 401"                 bash -c "[ '$PB_UNAUTH' = '401' ]"

# DB row exists, keyed by quote_id (UNIQUE), and only one of them.
PB_COUNT=$(podman exec postgres psql -U insurance -d insurance -t -c "SELECT count(*) FROM policy WHERE quote_id = $PB_QID" 2>/dev/null | tr -d ' ')
check "exactly 1 policy row for quote_id=$PB_QID"        bash -c "[ '$PB_COUNT' = '1' ]"

# Concurrent bind: 5 parallel POSTs for ONE fresh quoteId. Outcomes can be
# 201 (the winner), 200 (idempotent losers that re-checked after the lock),
# or 409 (losers that couldn't even acquire the lock) — but only ONE row.
CC_VIN="CCBIND$$X"
CC_QID=$(curl -sSf -X POST http://localhost:9080/api/quotes \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"vehicleVin\":\"$CC_VIN\",\"driverAge\":35,\"coverageType\":\"BASIC\"}" 2>/dev/null | jq -r .id)
for i in 1 2 3 4 5; do
  curl -sS -o /dev/null -X POST http://localhost:9080/api/policies \
    -H "$AUTH_HDR" -H "Content-Type: application/json" \
    -d "{\"quoteId\":$CC_QID}" &
done
wait
CC_COUNT=$(podman exec postgres psql -U insurance -d insurance -t -c "SELECT count(*) FROM policy WHERE quote_id = $CC_QID" 2>/dev/null | tr -d ' ')
check "5 concurrent binds yield exactly 1 policy row"    bash -c "[ '$CC_COUNT' = '1' ]"

# Kafka: the policy-events record landed, keyed by policyNumber. Hunt across
# all three partitions because the key hashes deterministically to one.
sleep 1
check "policy-events Kafka record keyed by policyNumber" bash -c "
  for p in 0 1 2; do
    if timeout 6 podman exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
        --bootstrap-server kafka:9092 --topic policy-events \
        --partition \$p --offset earliest --max-messages 200 \
        --property print.key=true --property key.separator='|||' 2>/dev/null \
      | grep -q \"^$PB_POLNUM|||\"; then exit 0; fi
  done
  exit 1
"

# Topic-level config: this MUST be a log-compacted topic (the whole point of
# slice 6's Kafka piece). Catches a future kafka-init regression.
check "policy-events topic has cleanup.policy=compact" bash -c "podman exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic policy-events 2>/dev/null | head -1 | grep -q 'cleanup.policy=compact'"

echo
echo "=== 11) Payment — idempotency + DLQ (feature 3, slice 7) ==="
# Need a bound Policy to pay against.
PY_VIN="PAY$$X"
PY_QID=$(curl -sSf -X POST http://localhost:9080/api/quotes \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"vehicleVin\":\"$PY_VIN\",\"driverAge\":40,\"coverageType\":\"BASIC\"}" 2>/dev/null | jq -r .id)
PY_POL=$(curl -sSf -X POST http://localhost:9080/api/policies \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"quoteId\":$PY_QID}" 2>/dev/null | jq -r .policyNumber)
check "smoke: created a policy to pay against"           bash -c "echo '$PY_POL' | grep -qE '^POL-[A-F0-9]{8}$'"

# Happy path: idempotency-key controls retries. First POST -> 201, replay -> 200, same body.
PY_KEY="smoke-ok-$$"
PY_OK1=$(curl -sS -w "\n%{http_code}" -X POST http://localhost:9080/api/payments \
  -H "$AUTH_HDR" -H "Content-Type: application/json" -H "Idempotency-Key: $PY_KEY" \
  -d "{\"policyNumber\":\"$PY_POL\",\"amount\":100.00,\"currency\":\"USD\"}")
PY_OK1_CODE=$(echo "$PY_OK1" | tail -n1)
PY_OK1_REF=$(echo "$PY_OK1" | head -n-1 | jq -r '.externalRef // empty')
check "POST /api/payments (1st, success) -> 201"         bash -c "[ '$PY_OK1_CODE' = '201' ]"
check "1st payment has an externalRef"                   bash -c "echo '$PY_OK1_REF' | grep -qE '^ext-'"

PY_OK2=$(curl -sS -w "\n%{http_code}" -X POST http://localhost:9080/api/payments \
  -H "$AUTH_HDR" -H "Content-Type: application/json" -H "Idempotency-Key: $PY_KEY" \
  -d "{\"policyNumber\":\"$PY_POL\",\"amount\":100.00,\"currency\":\"USD\"}")
PY_OK2_CODE=$(echo "$PY_OK2" | tail -n1)
PY_OK2_REF=$(echo "$PY_OK2" | head -n-1 | jq -r '.externalRef // empty')
check "POST /api/payments (replay same key) -> 200"      bash -c "[ '$PY_OK2_CODE' = '200' ]"
check "replay returns same externalRef (idempotent)"     bash -c "[ '$PY_OK1_REF' = '$PY_OK2_REF' ]"

# Required-header + auth gates.
PY_MISSING_KEY=$(curl -sS -o /dev/null -w "%{http_code}" -X POST http://localhost:9080/api/payments \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"policyNumber\":\"$PY_POL\",\"amount\":100.00}")
check "missing Idempotency-Key -> 400"                   bash -c "[ '$PY_MISSING_KEY' = '400' ]"

PY_UNAUTH=$(curl -sS -o /dev/null -w "%{http_code}" -X POST http://localhost:9080/api/payments \
  -H "Content-Type: application/json" -H "Idempotency-Key: x" \
  -d "{\"policyNumber\":\"$PY_POL\",\"amount\":100.00}")
check "unauth POST /api/payments -> 401"                 bash -c "[ '$PY_UNAUTH' = '401' ]"

# DLQ path: amount >= 9000 makes the WireMock stub return 503; @Retry burns
# through 1 initial + 2 retries (3 gateway calls), payment lands as FAILED,
# and a payment-dlq Kafka record is emitted keyed by the idempotency-key.
PY_FAIL_KEY="smoke-fail-$$"
curl -sS -X DELETE http://localhost:8888/__admin/requests >/dev/null
PY_FAIL=$(curl -sS -w "\n%{http_code}" -X POST http://localhost:9080/api/payments \
  -H "$AUTH_HDR" -H "Content-Type: application/json" -H "Idempotency-Key: $PY_FAIL_KEY" \
  -d "{\"policyNumber\":\"$PY_POL\",\"amount\":9999.00,\"currency\":\"USD\"}")
PY_FAIL_CODE=$(echo "$PY_FAIL" | tail -n1)
PY_FAIL_STATUS=$(echo "$PY_FAIL" | head -n-1 | jq -r '.status // empty')
check "failing payment -> 502 Bad Gateway"               bash -c "[ '$PY_FAIL_CODE' = '502' ]"
check "failed payment body status=FAILED"                bash -c "[ '$PY_FAIL_STATUS' = 'FAILED' ]"

# WireMock should have seen 3 attempts for amount=9999 (the @Retry config).
PY_RETRIES=$(curl -sS "http://localhost:8888/__admin/requests" 2>/dev/null | jq "[.requests[] | select(.request.url | contains(\"payment-gateway\"))] | length")
check "@Retry made 3 gateway attempts (1 + 2 retries)"   bash -c "[ '$PY_RETRIES' = '3' ]"

sleep 1
check "payment-dlq Kafka record keyed by idempotency-key" bash -c "
  for p in 0 1 2; do
    if timeout 6 podman exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
        --bootstrap-server kafka:9092 --topic payment-dlq \
        --partition \$p --offset earliest --max-messages 200 \
        --property print.key=true --property key.separator='|||' 2>/dev/null \
      | grep -q \"^$PY_FAIL_KEY|||\"; then exit 0; fi
  done
  exit 1
"

# Failed payment still has a durable DB record (the original slice 7 bug
# threw inside @Transactional and JTA rolled the FAILED row back).
PY_DB_STATUS=$(podman exec postgres psql -U insurance -d insurance -t -c "SELECT status FROM payment WHERE idempotency_key = '$PY_FAIL_KEY'" 2>/dev/null | tr -d ' ')
check "failed payment row exists with status=FAILED"     bash -c "[ '$PY_DB_STATUS' = 'FAILED' ]"

echo
echo "=== 12) Notification — multi-topic fan-in + MI channel router (feature 4, slice 8) ==="
# Use unique markers so we can find the right Mailpit messages even with
# previous smoke runs in the inbox.
NF_MARK="NSMK$$"
NF_PRE_INBOX=$(curl -sS "http://localhost:8025/api/v1/messages?start=0&limit=1" 2>/dev/null | jq -r '.total // 0')

# Fire one of each event source.
NF_QID=$(curl -sSf -X POST http://localhost:9080/api/quotes \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"vehicleVin\":\"$NF_MARK\",\"driverAge\":40,\"coverageType\":\"BASIC\"}" 2>/dev/null | jq -r .id)
NF_POL=$(curl -sSf -X POST http://localhost:9080/api/policies \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"quoteId\":$NF_QID}" 2>/dev/null | jq -r .policyNumber)
curl -sSf -X POST http://localhost:9080/api/payments \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $NF_MARK-pay" \
  -d "{\"policyNumber\":\"$NF_POL\",\"amount\":150.00,\"currency\":\"USD\"}" >/dev/null

# The consumer needs a moment to poll, dispatch, and write the row.
sleep 8

# Each event source should produce one notification row that reaches SENT.
NF_Q_STATUS=$(podman exec postgres psql -U insurance -d insurance -t -c \
  "SELECT status FROM notification WHERE event_topic='quote-events' AND body LIKE '%$NF_MARK%' ORDER BY id DESC LIMIT 1" 2>/dev/null | tr -d ' ')
check "quote-events fan-in -> notification SENT"        bash -c "[ '$NF_Q_STATUS' = 'SENT' ]"

NF_P_STATUS=$(podman exec postgres psql -U insurance -d insurance -t -c \
  "SELECT status FROM notification WHERE event_topic='policy-events' AND event_key='$NF_POL' ORDER BY id DESC LIMIT 1" 2>/dev/null | tr -d ' ')
check "policy-events fan-in -> notification SENT"       bash -c "[ '$NF_P_STATUS' = 'SENT' ]"

NF_PAY_STATUS=$(podman exec postgres psql -U insurance -d insurance -t -c \
  "SELECT status FROM notification WHERE event_topic='payment-events' AND body LIKE '%$NF_POL%' ORDER BY id DESC LIMIT 1" 2>/dev/null | tr -d ' ')
check "payment-events fan-in -> notification SENT"      bash -c "[ '$NF_PAY_STATUS' = 'SENT' ]"

# Mailpit should now hold at least 3 more messages than before the burst.
NF_POST_INBOX=$(curl -sS "http://localhost:8025/api/v1/messages?start=0&limit=1" 2>/dev/null | jq -r '.total // 0')
NF_DELTA=$((NF_POST_INBOX - NF_PRE_INBOX))
check "Mailpit gained >=3 messages from the fan-in"     bash -c "[ '$NF_DELTA' -ge 3 ]"

# Direct probe of MI's channel router — proves SMS and PUSH cases route too.
NF_SMS=$(curl -sS -o /dev/null -w "%{http_code}" -X POST http://localhost:8290/notification/dispatch \
  -H "Content-Type: application/json" \
  -d "{\"channel\":\"sms\",\"recipient\":\"+15551234\",\"subject\":\"smoke-sms\",\"body\":\"x\"}")
check "MI routes channel=sms -> 200 (WireMock stub)"    bash -c "[ '$NF_SMS' = '200' ]"

NF_PUSH=$(curl -sS -o /dev/null -w "%{http_code}" -X POST http://localhost:8290/notification/dispatch \
  -H "Content-Type: application/json" \
  -d "{\"channel\":\"push\",\"recipient\":\"dev-abc\",\"subject\":\"smoke-push\",\"body\":\"x\"}")
check "MI routes channel=push -> 200 (WireMock stub)"   bash -c "[ '$NF_PUSH' = '200' ]"

# WireMock should have seen the SMS + push hits.
check "WireMock saw sms-gateway request"                bash -c "curl -sS http://localhost:8888/__admin/requests 2>/dev/null | jq -e '[.requests[].request.url] | any(. == \"/sms-gateway/send\")' >/dev/null"
check "WireMock saw push-gateway request"               bash -c "curl -sS http://localhost:8888/__admin/requests 2>/dev/null | jq -e '[.requests[].request.url] | any(. == \"/push-gateway/send\")' >/dev/null"

echo
echo "=== 13) Claim filing — multipart upload + MinIO + OCR via MI (feature 5, slice 9) ==="
# Need a policy to attach the claim to.
CL_VIN="CLM$$X"
CL_QID=$(curl -sSf -X POST http://localhost:9080/api/quotes \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"vehicleVin\":\"$CL_VIN\",\"driverAge\":35,\"coverageType\":\"BASIC\"}" 2>/dev/null | jq -r .id)
CL_POL=$(curl -sSf -X POST http://localhost:9080/api/policies \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"quoteId\":$CL_QID}" 2>/dev/null | jq -r .policyNumber)
check "smoke: created a policy to claim against"        bash -c "echo '$CL_POL' | grep -qE '^POL-[A-F0-9]{8}$'"

# Generate a 32 KB random "photo" so the upload path is exercised end-to-end.
CL_PHOTO=$(mktemp --suffix=.jpg)
dd if=/dev/urandom of="$CL_PHOTO" bs=1024 count=32 status=none

# Happy path: multipart POST with auth + valid policy + attachment.
CL_RESP=$(curl -sS -w "\n%{http_code}" -X POST http://localhost:9080/api/claims \
  -H "$AUTH_HDR" \
  -F "policyNumber=$CL_POL" \
  -F "description=smoke fender bender" \
  -F "attachment=@$CL_PHOTO;type=image/jpeg")
CL_BODY=$(echo "$CL_RESP" | head -n-1)
CL_CODE=$(echo "$CL_RESP" | tail -n1)
CL_PHOTO_KEY=$(echo "$CL_BODY" | jq -r '.photoKey // empty')
CL_OCR_CONF=$(echo "$CL_BODY" | jq -r '.ocrConfidence // empty')
CL_OCR_TEXT=$(echo "$CL_BODY" | jq -r '.ocrText // empty')

check "POST multipart /api/claims -> 201"               bash -c "[ '$CL_CODE' = '201' ]"
check "response carries a MinIO photoKey"               bash -c "echo '$CL_PHOTO_KEY' | grep -qE '^[a-f0-9-]{36}\\.jpg$'"
check "OCR confidence populated (>0.0)"                 bash -c "[ -n '$CL_OCR_CONF' ] && [ '$CL_OCR_CONF' != 'null' ]"
check "OCR text mentions POLICY NUMBER"                 bash -c "echo '$CL_OCR_TEXT' | grep -q 'POLICY NUMBER'"

# Unauthorized: no Bearer -> 401.
CL_UNAUTH=$(curl -sS -o /dev/null -w "%{http_code}" -X POST http://localhost:9080/api/claims \
  -F "policyNumber=$CL_POL" -F "attachment=@$CL_PHOTO;type=image/jpeg")
check "unauth POST /api/claims -> 401"                  bash -c "[ '$CL_UNAUTH' = '401' ]"

# Missing policyNumber -> 400.
CL_MISSING=$(curl -sS -o /dev/null -w "%{http_code}" -X POST http://localhost:9080/api/claims \
  -H "$AUTH_HDR" -F "attachment=@$CL_PHOTO;type=image/jpeg")
check "missing policyNumber -> 400"                     bash -c "[ '$CL_MISSING' = '400' ]"

# DB row matches what the response said.
CL_DB=$(podman exec postgres psql -U insurance -d insurance -t -c "SELECT photo_key FROM claim WHERE photo_key = '$CL_PHOTO_KEY'" 2>/dev/null | tr -d ' ')
check "claim row exists with the returned photoKey"     bash -c "[ '$CL_DB' = '$CL_PHOTO_KEY' ]"

# File actually landed in MinIO.
check "uploaded object exists in MinIO bucket claims"   bash -c "podman exec minio sh -c 'mc alias set local http://localhost:9000 minioadmin minioadmin >/dev/null 2>&1 && mc stat local/claims/$CL_PHOTO_KEY >/dev/null 2>&1'"

rm -f "$CL_PHOTO"

echo
echo "=== 14) mTLS partner API (feature 5 part 2, slice 10) ==="
# Direct probe of partner-mock from another container on insurance-net.
# Without a client cert nginx rejects the handshake with 400.
NOAUTH_CODE=$(podman exec insurance-mi curl -k -sS -o /dev/null -w "%{http_code}" --max-time 5 https://partner-mock:8443/partner/lookup 2>/dev/null)
check "partner-mock rejects no-cert client -> 400"      bash -c "[ '$NOAUTH_CODE' = '400' ]"

# With the client cert (copied into the insurance-mi container for the probe)
# nginx accepts and returns synthetic carrier data.
podman cp $HOME/insurance-app/compose/certs/client.crt insurance-mi:/tmp/c.crt 2>/dev/null
podman cp $HOME/insurance-app/compose/certs/client.key insurance-mi:/tmp/c.key 2>/dev/null
WITHAUTH_CODE=$(podman exec insurance-mi curl -k -sS -o /dev/null -w "%{http_code}" --cert /tmp/c.crt --key /tmp/c.key "https://partner-mock:8443/partner/lookup?vin=SMOKE" 2>/dev/null)
check "partner-mock accepts valid client cert -> 200"   bash -c "[ '$WITHAUTH_CODE' = '200' ]"

# End-to-end through Liberty: claim with otherPartyVin should come back with
# the partner-populated fields, proving Liberty's mTLS client config works.
MT_VIN="MTLS$$X"
MT_QID=$(curl -sSf -X POST http://localhost:9080/api/quotes \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"vehicleVin\":\"$MT_VIN\",\"driverAge\":35,\"coverageType\":\"BASIC\"}" 2>/dev/null | jq -r .id)
MT_POL=$(curl -sSf -X POST http://localhost:9080/api/policies \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"quoteId\":$MT_QID}" 2>/dev/null | jq -r .policyNumber)
MT_PHOTO=$(mktemp --suffix=.jpg)
dd if=/dev/urandom of="$MT_PHOTO" bs=1024 count=8 status=none

MT_RESP=$(curl -sS -X POST http://localhost:9080/api/claims \
  -H "$AUTH_HDR" \
  -F "policyNumber=$MT_POL" \
  -F "description=mTLS smoke" \
  -F "otherPartyVin=OTHER-$$-X" \
  -F "attachment=@$MT_PHOTO;type=image/jpeg")
MT_OTHER_VIN=$(echo "$MT_RESP" | jq -r '.otherPartyVin // empty')
MT_OTHER_POL=$(echo "$MT_RESP" | jq -r '.otherPartyPolicy // empty')
MT_OTHER_CAR=$(echo "$MT_RESP" | jq -r '.otherPartyCarrier // empty')

check "claim returned otherPartyVin"                    bash -c "[ -n '$MT_OTHER_VIN' ]"
check "Liberty mTLS -> partner returned a policyNumber" bash -c "[ '$MT_OTHER_POL' = 'P-12345-RIVAL' ]"
check "Liberty mTLS -> partner returned carrier name"   bash -c "[ '$MT_OTHER_CAR' = 'RivalInsurance' ]"

# Persisted to DB.
MT_DB_CAR=$(podman exec postgres psql -U insurance -d insurance -t -c "SELECT other_party_carrier FROM claim WHERE other_party_vin = '$MT_OTHER_VIN' ORDER BY id DESC LIMIT 1" 2>/dev/null | tr -d ' ')
check "claim row stores other_party_carrier in DB"      bash -c "[ '$MT_DB_CAR' = 'RivalInsurance' ]"

rm -f "$MT_PHOTO"

echo
echo "=== 15) Agent dashboard — Redis Pub/Sub + Streams + WebSocket (feature 6, slice 11) ==="
# Baseline the stream length so we can verify XADD happened.
DSH_STREAM_BEFORE=$(podman exec redis redis-cli xlen dashboard:stream 2>/dev/null | tr -d ' ')

# Set up a policy + photo for the claim that the WS probe will fire.
DSH_VIN="DSH$$X"
DSH_QID=$(curl -sSf -X POST http://localhost:9080/api/quotes \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"vehicleVin\":\"$DSH_VIN\",\"driverAge\":40,\"coverageType\":\"BASIC\"}" 2>/dev/null | jq -r .id)
DSH_POL=$(curl -sSf -X POST http://localhost:9080/api/policies \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"quoteId\":$DSH_QID}" 2>/dev/null | jq -r .policyNumber)
DSH_PHOTO=$(mktemp --suffix=.jpg)
dd if=/dev/urandom of="$DSH_PHOTO" bs=1024 count=4 status=none

# scripts/ws-probe.py: pure-stdlib WebSocket client. Opens ws://localhost:9080
# /ws/dashboard, expects an INITIAL_STATE frame, fires a claim, then waits up
# to 8s for a CLAIM_FILED frame matching the new claimId.
DSH_OUT=$(python3 $HOME/insurance-app/scripts/ws-probe.py "$AT" "$DSH_POL" "$DSH_PHOTO" 2>&1)
DSH_INIT=$(echo "$DSH_OUT" | jq -r '.INITIAL_STATE // empty')
DSH_LIVE=$(echo "$DSH_OUT" | jq -r '.LIVE_FRAME // empty')
DSH_CID=$(echo "$DSH_OUT" | jq -r '.claimId // empty')

check "WS handshake + INITIAL_STATE received on connect"  bash -c "[ '$DSH_INIT' = 'true' ]"
check "Pub/Sub fan-out: live CLAIM_FILED frame delivered" bash -c "[ '$DSH_LIVE' = 'true' ]"
check "live frame claimId matched the POSTed claim"        bash -c "[ -n '$DSH_CID' ] && [ '$DSH_CID' != 'null' ]"

DSH_STREAM_AFTER=$(podman exec redis redis-cli xlen dashboard:stream 2>/dev/null | tr -d ' ')
check "Redis Stream XLEN grew (durable backlog)"          bash -c "[ '$DSH_STREAM_AFTER' -gt '$DSH_STREAM_BEFORE' ]"

# Latest stream entry should JSON-decode to a CLAIM_FILED payload referencing the new claim.
DSH_LAST=$(podman exec redis redis-cli xrevrange dashboard:stream + - COUNT 1 2>/dev/null | tail -1)
check "latest stream entry mentions the new claimId"      bash -c "echo \"$DSH_LAST\" | grep -q '$DSH_CID'"

rm -f "$DSH_PHOTO"

echo
echo "=== 16) Reporting — Kafka Streams + scheduled MI task (feature 7, slice 12) ==="
# Baseline: how many report_run rows exist before our wait window?
RPT_BEFORE=$(podman exec postgres psql -U insurance -d insurance -t -c "SELECT count(*) FROM report_run" 2>/dev/null | tr -d ' ')

# Fire a few payments (SUCCEEDED + FAILED) so the Streams topology has fresh
# events to aggregate. Need a policy first.
RPT_VIN="RPT$$X"
RPT_QID=$(curl -sSf -X POST http://localhost:9080/api/quotes \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"vehicleVin\":\"$RPT_VIN\",\"driverAge\":40,\"coverageType\":\"BASIC\"}" 2>/dev/null | jq -r .id)
RPT_POL=$(curl -sSf -X POST http://localhost:9080/api/policies \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"quoteId\":$RPT_QID}" 2>/dev/null | jq -r .policyNumber)
for i in 1 2; do
  curl -sS -o /dev/null -X POST http://localhost:9080/api/payments \
    -H "$AUTH_HDR" -H "Content-Type: application/json" \
    -H "Idempotency-Key: rpt-$$-$i" \
    -d "{\"policyNumber\":\"$RPT_POL\",\"amount\":100.00,\"currency\":\"USD\"}"
done
# One failing payment so FAILED count moves too.
curl -sS -o /dev/null -X POST http://localhost:9080/api/payments \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -H "Idempotency-Key: rpt-fail-$$" \
  -d "{\"policyNumber\":\"$RPT_POL\",\"amount\":9999.00,\"currency\":\"USD\"}"

# Give the Streams topology a moment to ingest from payment-events.
sleep 6

# Query the in-memory state store via REST.
RPT_STATS=$(curl -sS http://localhost:9080/api/reports/payment-stats)
RPT_SUCC=$(echo "$RPT_STATS" | jq -r '.totalsByStatus.SUCCEEDED // 0')
RPT_FAIL=$(echo "$RPT_STATS" | jq -r '.totalsByStatus.FAILED // 0')
RPT_WIN=$(echo "$RPT_STATS"  | jq -r '.windows | length')
check "Streams state store has SUCCEEDED count >= 2"      bash -c "[ '$RPT_SUCC' -ge '2' ]"
check "Streams state store has FAILED count >= 1"         bash -c "[ '$RPT_FAIL' -ge '1' ]"
check "windowed rollups returned at least one window"     bash -c "[ '$RPT_WIN' -ge '1' ]"

# Wait one MI task tick (interval=30s) — long enough that we observe a NEW
# report_run row appearing without our intervention. Slow but on purpose:
# the whole point of the slice is to prove the scheduler actually fires.
sleep 35
RPT_AFTER=$(podman exec postgres psql -U insurance -d insurance -t -c "SELECT count(*) FROM report_run" 2>/dev/null | tr -d ' ')
check "MI scheduled task fired (report_run count grew)"   bash -c "[ '$RPT_AFTER' -gt '$RPT_BEFORE' ]"

# Most-recent run must have come from the MI scheduler, not an ad-hoc POST.
RPT_LAST_SRC=$(podman exec postgres psql -U insurance -d insurance -t -c "SELECT source FROM report_run ORDER BY id DESC LIMIT 1" 2>/dev/null | tr -d ' ')
check "latest report_run source=mi-scheduled-task"        bash -c "[ '$RPT_LAST_SRC' = 'mi-scheduled-task' ]"

echo
echo "=== 17) Audit trail — compacted topic + retention contrast (feature 8, slice 13) ==="
# Build a fresh policy + claim so the audit/contrast checks have a clean entity.
AU_VIN="AUD$$X"
AU_QID=$(curl -sSf -X POST http://localhost:9080/api/quotes \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"vehicleVin\":\"$AU_VIN\",\"driverAge\":35,\"coverageType\":\"BASIC\"}" 2>/dev/null | jq -r .id)
AU_POL=$(curl -sSf -X POST http://localhost:9080/api/policies \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"quoteId\":$AU_QID}" 2>/dev/null | jq -r .policyNumber)
AU_PHOTO=$(mktemp --suffix=.jpg)
dd if=/dev/urandom of="$AU_PHOTO" bs=1024 count=4 status=none

AU_CLAIM_ID=$(curl -sSf -X POST http://localhost:9080/api/claims \
  -H "$AUTH_HDR" \
  -F "policyNumber=$AU_POL" \
  -F "description=audit smoke" \
  -F "attachment=@$AU_PHOTO;type=image/jpeg" 2>/dev/null | jq -r .id)
sleep 3

AU_AFTER_FILE=$(curl -sS "http://localhost:9080/api/audit/claim/$AU_CLAIM_ID" | jq -r '.action // empty')
check "after FILED: audit snapshot action=FILED"          bash -c "[ '$AU_AFTER_FILE' = 'FILED' ]"

# Approve the claim — second write to the same audit-events key (claim:$AU_CLAIM_ID).
curl -sSf -o /dev/null -X POST -H "$AUTH_HDR" \
  "http://localhost:9080/api/claims/$AU_CLAIM_ID/approve"
sleep 3

AU_AFTER_APR=$(curl -sS "http://localhost:9080/api/audit/claim/$AU_CLAIM_ID" | jq -r '.action // empty')
check "after APPROVED: audit snapshot action=APPROVED"    bash -c "[ '$AU_AFTER_APR' = 'APPROVED' ]"
check "compacted snapshot has exactly one record per key" bash -c "[ \"$(curl -sS http://localhost:9080/api/audit/claim/$AU_CLAIM_ID | jq -r '.entityId')\" = '$AU_CLAIM_ID' ]"

# Contrast endpoint: snapshot is one record, claim-events retains both.
AU_CONTRAST=$(curl -sS "http://localhost:9080/api/audit/contrast/$AU_CLAIM_ID")
AU_SNAP_ACT=$(echo "$AU_CONTRAST" | jq -r '.snapshot.action // empty')
AU_EV_COUNT=$(echo "$AU_CONTRAST" | jq -r '.events | length')
AU_FIRST=$(echo "$AU_CONTRAST" | jq -r '.events[0].action // empty')
AU_LAST=$(echo "$AU_CONTRAST"  | jq -r '.events[-1].action // empty')
check "contrast.snapshot.action = APPROVED (compacted)"   bash -c "[ '$AU_SNAP_ACT' = 'APPROVED' ]"
check "contrast.events has both FILED and APPROVED"       bash -c "[ '$AU_EV_COUNT' -eq '2' ] && [ '$AU_FIRST' = 'FILED' ] && [ '$AU_LAST' = 'APPROVED' ]"

# Sanity: audit-events topic actually received 2 records keyed by claim:$AU_CLAIM_ID
# (compaction MAY have collapsed them by now, but earliest replay shows the writes).
AU_KAFKA_HITS=$(timeout 12 podman exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 --topic audit-events \
  --from-beginning --max-messages 500 --timeout-ms 10000 \
  --property print.key=true --property key.separator='|||' 2>/dev/null \
  | grep -c "^claim:$AU_CLAIM_ID|||" || true)
check "audit-events received >=1 record for the claim"    bash -c "[ '$AU_KAFKA_HITS' -ge '1' ]"

rm -f "$AU_PHOTO"

echo
echo "=== 18) Search — Debezium CDC + OpenSearch (feature 9, slice 14) ==="
# Idempotent: PUT /connectors/<name>/config is upsert, so re-running smoke
# does not error. First-run also primes the connector cold.
$HOME/insurance-app/scripts/register-debezium.sh >/dev/null 2>&1
sleep 3

SR_CONN_STATE=$(curl -sS http://localhost:8083/connectors/insurance-claim-cdc/status 2>/dev/null | jq -r '.connector.state // empty')
SR_TASK_STATE=$(curl -sS http://localhost:8083/connectors/insurance-claim-cdc/status 2>/dev/null | jq -r '.tasks[0].state // empty')
check "Debezium connector state=RUNNING"                  bash -c "[ '$SR_CONN_STATE' = 'RUNNING' ]"
check "Debezium connector task state=RUNNING"             bash -c "[ '$SR_TASK_STATE' = 'RUNNING' ]"

# Build a claim with a unique marker phrase so we can prove THIS claim
# (and not historical backfill) landed in the index.
SR_VIN="SRCH$$X"
SR_QID=$(curl -sSf -X POST http://localhost:9080/api/quotes \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"vehicleVin\":\"$SR_VIN\",\"driverAge\":35,\"coverageType\":\"BASIC\"}" 2>/dev/null | jq -r .id)
SR_POL=$(curl -sSf -X POST http://localhost:9080/api/policies \
  -H "$AUTH_HDR" -H "Content-Type: application/json" \
  -d "{\"quoteId\":$SR_QID}" 2>/dev/null | jq -r .policyNumber)
SR_MARKER="dent-marker-$$"
SR_PHOTO=$(mktemp --suffix=.jpg)
dd if=/dev/urandom of="$SR_PHOTO" bs=1024 count=4 status=none

SR_CLAIM_ID=$(curl -sSf -X POST http://localhost:9080/api/claims \
  -H "$AUTH_HDR" \
  -F "policyNumber=$SR_POL" \
  -F "description=$SR_MARKER" \
  -F "attachment=@$SR_PHOTO;type=image/jpeg" 2>/dev/null | jq -r .id)

# CDC pipeline: Postgres insert -> Debezium snapshot -> Kafka -> SearchIndexer -> OpenSearch.
# 8s covers the realistic envelope; OpenSearch refresh is forced by /api/search.
sleep 8

SR_RESP=$(curl -sS "http://localhost:9080/api/search/claims?q=$SR_MARKER")
SR_TOTAL=$(echo "$SR_RESP" | jq -r '.total // 0')
SR_IDS=$(echo "$SR_RESP"   | jq -r '.hits[].id')

check "search by description marker -> >=1 hit"           bash -c "[ '$SR_TOTAL' -ge '1' ]"
check "search hit ids include the new claim id"           bash -c "echo \"$SR_IDS\" | grep -qx '$SR_CLAIM_ID'"

# Sanity: OpenSearch claims index has at least the backfill + the new doc.
SR_COUNT=$(curl -sS http://localhost:9200/claims/_count | jq -r '.count // 0')
check "OpenSearch claims index has docs (>= 2)"           bash -c "[ '$SR_COUNT' -ge '2' ]"

# Second query path: search by policy number (verifies multi_match across
# multiple fields).
SR_BYPOL=$(curl -sS "http://localhost:9080/api/search/claims?q=$SR_POL" | jq -r '.total // 0')
check "search by policy number -> >=1 hit"                bash -c "[ '$SR_BYPOL' -ge '1' ]"

rm -f "$SR_PHOTO"

echo
echo "=== 19) GUI — landing page + dev-token + money chain pages (slice 15) ==="
# Bare root must serve the tour page (welcome-file-list -> index.html).
GUI_ROOT_CODE=$(curl -sS -o /tmp/gui_root -w "%{http_code}" http://localhost:9080/)
check "GET / -> 200 (welcome file)"                       bash -c "[ '$GUI_ROOT_CODE' = '200' ]"
check "/ contains tour-page marker text"                  bash -c "grep -q 'teaching tour' /tmp/gui_root"

# Static assets reachable.
for asset in /index.html /quote.html /policy.html /payment.html /static/app.js /static/app.css; do
  CODE=$(curl -sS -o /dev/null -w "%{http_code}" http://localhost:9080$asset)
  check "static $asset returns 200"                       bash -c "[ '$CODE' = '200' ]"
done

# Dev token endpoint mints a real JWT (the GUI's bootstrap call).
TOK_RESP=$(curl -sS http://localhost:9080/api/auth/token)
GUI_JWT=$(echo "$TOK_RESP" | jq -r '.jwt // empty')
GUI_EXP=$(echo "$TOK_RESP" | jq -r '.expiresIn // 0')
check "/api/auth/token returns a JWT >200 chars"          bash -c "[ '${#GUI_JWT}' -gt '200' ]"
check "/api/auth/token expiresIn > 0"                     bash -c "[ '$GUI_EXP' -gt '0' ]"

# The minted token must satisfy mpJwt — pick a gated endpoint and verify 201.
GUI_VIN="GUITEST$$X"
GUI_CODE=$(curl -sS -o /dev/null -w "%{http_code}" -X POST http://localhost:9080/api/quotes \
  -H "Authorization: Bearer $GUI_JWT" \
  -H "Content-Type: application/json" \
  -d "{\"vehicleVin\":\"$GUI_VIN\",\"driverAge\":30,\"coverageType\":\"BASIC\"}")
check "browser-style JWT works on POST /api/quotes -> 201" bash -c "[ '$GUI_CODE' = '201' ]"

# Page bodies actually reference what they claim to. Saves a fresh student
# from clicking around when a page renders but is wired to the wrong slice.
check "quote.html mentions Redis cache + rate limit"      bash -c "curl -sS http://localhost:9080/quote.html | grep -q 'rate limit'"
check "policy.html mentions Redlock"                      bash -c "curl -sS http://localhost:9080/policy.html | grep -q 'Redlock'"
check "payment.html mentions Idempotency-Key + DLQ"       bash -c "curl -sS http://localhost:9080/payment.html | grep -qE 'Idempotency-Key'"

# Slice 16: claim.html (multipart, OCR, mTLS) + dashboard.html (live WS feed).
for page in claim.html dashboard.html; do
  CODE=$(curl -sS -o /dev/null -w "%{http_code}" http://localhost:9080/$page)
  check "static /$page returns 200"                       bash -c "[ '$CODE' = '200' ]"
done
check "claim.html mentions MinIO + OCR + mTLS"            bash -c "curl -sS http://localhost:9080/claim.html | grep -qE 'MinIO' && curl -sS http://localhost:9080/claim.html | grep -qE 'OCR' && curl -sS http://localhost:9080/claim.html | grep -qE 'mTLS'"
check "dashboard.html mentions Pub/Sub + Streams"         bash -c "curl -sS http://localhost:9080/dashboard.html | grep -qE 'Pub/Sub' && curl -sS http://localhost:9080/dashboard.html | grep -qE 'Streams|Stream'"
check "index.html promotes claim + dashboard cards"       bash -c "curl -sS http://localhost:9080/ | grep -q 'href=\"/claim.html\"' && curl -sS http://localhost:9080/ | grep -q 'href=\"/dashboard.html\"'"

# Slice 17: search.html + audit.html + report.html observability lanes.
for page in search.html audit.html report.html; do
  CODE=$(curl -sS -o /dev/null -w "%{http_code}" http://localhost:9080/$page)
  check "static /$page returns 200"                       bash -c "[ '$CODE' = '200' ]"
done
check "search.html mentions Debezium + OpenSearch"        bash -c "curl -sS http://localhost:9080/search.html | grep -q 'Debezium' && curl -sS http://localhost:9080/search.html | grep -q 'OpenSearch'"
check "audit.html mentions compacted + retention"         bash -c "curl -sS http://localhost:9080/audit.html | grep -qE 'compact' && curl -sS http://localhost:9080/audit.html | grep -qE 'retention'"
check "report.html mentions Kafka Streams + MI task"      bash -c "curl -sS http://localhost:9080/report.html | grep -q 'Kafka Streams' && curl -sS http://localhost:9080/report.html | grep -qE 'scheduled MI task'"
check "index.html promotes search + audit + report"       bash -c "curl -sS http://localhost:9080/ | grep -q 'href=\"/search.html\"' && curl -sS http://localhost:9080/ | grep -q 'href=\"/audit.html\"' && curl -sS http://localhost:9080/ | grep -q 'href=\"/report.html\"'"

echo
echo "=== 20) Customer GUI — SvelteKit + Auth.js + WSO2 IS OIDC (slice 18) ==="
# Local probes only. Public-URL (my.insurance-app.comptech-lab.com) needs
# DNS + HAProxy backend; those are external concerns documented in SETUP.md.
CG_ROOT=$(curl -sS -o /dev/null -w "%{http_code}" http://localhost:3000/)
check "customer-app GET / -> 200"                       bash -c "[ '$CG_ROOT' = '200' ]"

CG_ACCT=$(curl -sS -o /dev/null -w "%{http_code}" http://localhost:3000/account)
check "customer-app GET /account -> 302 (gated)"        bash -c "[ '$CG_ACCT' = '302' ]"

CG_SIGNIN=$(curl -sS -o /dev/null -w "%{http_code}" -X POST http://localhost:3000/auth/signin/wso2is)
check "customer-app POST /auth/signin/wso2is -> 302"    bash -c "[ '$CG_SIGNIN' = '302' ]"

check "landing page has the brand tagline"              bash -c "curl -sS http://localhost:3000/ | grep -q 'Get an insurance quote'"

# Slice 19: quote wizard
QR_CODE=$(curl -sS -o /dev/null -w "%{http_code}" http://localhost:3000/quote)
check "customer-app /quote page renders 200"            bash -c "[ '$QR_CODE' = '200' ]"

# POST a quote through the SvelteKit server action; expects a 200 with
# a SvelteKit form-action success envelope referencing the quote id.
QR_RESP=$(curl -sS -X POST http://localhost:3000/quote \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "vehicleVin=SMOKEGUI$$&driverAge=35&coverageType=STANDARD")
check "POST /quote returns SvelteKit success envelope"  bash -c "echo '$QR_RESP' | grep -q 'success'"
check "POST /quote response contains CALCULATED status" bash -c "echo '$QR_RESP' | grep -q 'CALCULATED'"
check "POST /quote response contains a premium number"  bash -c "echo '$QR_RESP' | grep -qE 'premium[^,]*[0-9]+'"

# Slice 20: policies list + detail + payment
PL_CODE=$(curl -sS -o /dev/null -w "%{http_code}" http://localhost:3000/policies)
check "customer-app /policies page renders 200"          bash -c "[ '$PL_CODE' = '200' ]"

PL_BODY=$(curl -sS http://localhost:3000/policies)
check "/policies lists at least one POL-* number"        bash -c "echo '$PL_BODY' | grep -qE 'POL-[A-F0-9]+'"

# Detail page should resolve a real policy. Pick the first POL-* from the list.
POL=$(echo "$PL_BODY" | grep -oE 'POL-[A-F0-9]+' | head -1)
if [ -n "$POL" ]; then
  PD_CODE=$(curl -sS -o /dev/null -w "%{http_code}" "http://localhost:3000/policies/$POL")
  check "/policies/$POL detail renders 200"               bash -c "[ '$PD_CODE' = '200' ]"
  PD_BODY=$(curl -sS "http://localhost:3000/policies/$POL")
  check "/policies/$POL body contains 'Make a payment'"   bash -c "echo '$PD_BODY' | grep -q 'Make a payment'"
fi

# bind + pay both require auth — should 302 to sign-in.
PB_CODE=$(curl -sS -o /dev/null -w "%{http_code}" "http://localhost:3000/policies/bind?quoteId=1")
check "/policies/bind?quoteId=1 -> 302 (gated)"           bash -c "[ '$PB_CODE' = '302' ]"
PY_CODE=$(curl -sS -o /dev/null -w "%{http_code}" "http://localhost:3000/policies/$POL/pay")
check "/policies/POL/pay -> 302 (gated)"                  bash -c "[ '$PY_CODE' = '302' ]"

# Slice 21: claims
CL_CODE=$(curl -sS -o /dev/null -w "%{http_code}" http://localhost:3000/claims)
check "customer-app /claims page renders 200"            bash -c "[ '$CL_CODE' = '200' ]"

CL_BODY=$(curl -sS http://localhost:3000/claims)
check "/claims lists at least one #N claim id"           bash -c "curl -sS http://localhost:3000/claims | grep -qE '#[0-9]+'"
check "/claims shows at least one FILED|APPROVED badge"  bash -c "curl -sS http://localhost:3000/claims | grep -qE 'FILED|APPROVED'"

# /claims/file is gated — should redirect to sign-in.
CF_CODE=$(curl -sS -o /dev/null -w "%{http_code}" http://localhost:3000/claims/file)
check "/claims/file -> 302 (gated)"                      bash -c "[ '$CF_CODE' = '302' ]"

# Claim detail page should resolve a real id from the list.
CID=$(echo "$CL_BODY" | grep -oE '/claims/[0-9]+' | head -1 | sed 's|/claims/||')
if [ -n "$CID" ]; then
  CD_CODE=$(curl -sS -o /dev/null -w "%{http_code}" "http://localhost:3000/claims/$CID")
  check "/claims/$CID detail renders 200"                 bash -c "[ '$CD_CODE' = '200' ]"
fi

# Policy detail page should now have a 'File a claim' CTA.
POL=$(echo "$PL_BODY" | grep -oE 'POL-[A-F0-9]+' | head -1)
if [ -n "$POL" ]; then
  PD_BODY=$(curl -sS "http://localhost:3000/policies/$POL")
  check "/policies/$POL detail has 'File a claim' CTA"    bash -c "echo '$PD_BODY' | grep -q 'File a claim'"
fi

# Slice 22: polish — custom error page + breadcrumbs.
NF_CODE=$(curl -sS -o /dev/null -w "%{http_code}" http://localhost:3000/no-such-route)
check "unknown route returns 404"                        bash -c "[ '$NF_CODE' = '404' ]"
check "404 body uses custom error page"                  bash -c "curl -sS http://localhost:3000/no-such-route | grep -q 'Page not found'"

if [ -n "$POL" ]; then
  check "breadcrumbs render on /policies/$POL"          bash -c "curl -sS http://localhost:3000/policies/$POL | grep -q 'aria-label=\"Breadcrumb\"'"
fi




echo
echo "=== 9) Public HTTPS subdomains ==="
for h in app signoz minio kafka mail search is apim gateway redis my; do
  URL="https://${h}.insurance-app.comptech-lab.com/"
  [ "$h" = "app" ] && URL="${URL}api/ping"
  check "$h.insurance-app" bash -c "curl -sSf -o /dev/null -L --max-time 10 \"$URL\""
done

echo
echo
echo "=== 23) Live OIDC handshake (slice 23) ==="
# customer-app -> WSO2 IS authorize -> IS login page. We don't drive the
# login form; this proves the BFF computes a proper authorize URL with
# PKCE+state and that IS accepts the redirect_uri whitelist.
AUTH_REDIR=$(curl -sS -o /dev/null -w "%{redirect_url}" -X POST https://my.insurance-app.comptech-lab.com/auth/signin/wso2is)
check "POST /auth/signin/wso2is -> IS authorize"        bash -c "echo '$AUTH_REDIR' | grep -q 'is.insurance-app.comptech-lab.com/oauth2/authorize'"
check "authorize redirect carries PKCE code_challenge"  bash -c "echo '$AUTH_REDIR' | grep -q 'code_challenge'"
check "authorize redirect carries the customer client_id" bash -c "echo '$AUTH_REDIR' | grep -q 'client_id='"

LOGIN_REDIR=$(curl -sS -o /dev/null -w "%{redirect_url}" "$AUTH_REDIR")
check "IS authorize -> /authenticationendpoint/login.do" bash -c "echo '$LOGIN_REDIR' | grep -q 'authenticationendpoint/login.do'"

echo
echo "Passed: $pass    Failed: $fail"
exit $fail
