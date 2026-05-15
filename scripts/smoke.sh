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
check "produced + consumer reads it back" bash -c "
  for p in 0 1 2; do
    if timeout 8 podman exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
        --bootstrap-server kafka:9092 --topic quote-events --partition \$p \
        --offset earliest --max-messages 20 2>/dev/null | grep -q $KEY; then exit 0; fi
  done; exit 1
"

echo
echo "=== 5) Quote round-trip (feature 1, slice 1) ==="
QUOTE_REQ='{"vehicleVin":"SMOKE'"$$"'","driverAge":30,"coverageType":"STANDARD"}'
QUOTE_RESP=$(curl -sSf -X POST http://localhost:9080/api/quotes \
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
  -H "Content-Type: application/json" \
  -d "{\"vehicleVin\":\"$CACHE_VIN\",\"driverAge\":35,\"coverageType\":\"BASIC\"}" 2>/dev/null || echo "")
CACHE_ID=$(echo "$CACHE_RESP" | jq -r '.id // empty' 2>/dev/null)
check "POST wrote redis key quote:\$id (not quote:null)" bash -c "[ -n '$CACHE_ID' ] && podman exec redis redis-cli get 'quote:$CACHE_ID' 2>/dev/null | grep -q $CACHE_VIN && ! podman exec redis redis-cli get 'quote:null' 2>/dev/null | grep -q $CACHE_VIN"

RL_VIN="RLSMOKE$$X"
RL_LAST_STATUS=200
for _i in 1 2 3 4 5 6; do
  RL_LAST_STATUS=$(curl -sS -o /dev/null -w "%{http_code}" -X POST http://localhost:9080/api/quotes \
    -H "Content-Type: application/json" \
    -d "{\"vehicleVin\":\"$RL_VIN\",\"driverAge\":30,\"coverageType\":\"BASIC\"}")
done
check "6th rapid POST same VIN returns 429" bash -c "[ '$RL_LAST_STATUS' = '429' ]"

echo
echo "=== 7) Public HTTPS subdomains ==="
for h in app signoz minio kafka mail search is apim gateway redis; do
  URL="https://${h}.insurance-app.comptech-lab.com/"
  [ "$h" = "app" ] && URL="${URL}api/ping"
  check "$h.insurance-app" bash -c "curl -sSf -o /dev/null -L --max-time 10 \"$URL\""
done

echo
echo "Passed: $pass    Failed: $fail"
exit $fail
