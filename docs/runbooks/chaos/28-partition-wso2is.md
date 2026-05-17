# Chaos drill 28 — Partition WSO2 IS from the BFFs and Liberty

Issue [#28](https://github.com/zeshaq/insurance-app/issues/28). Phase 5.

## When to run

* Before any change to Liberty's `mpJwt-2.1` configuration in
  `server.xml` (especially `jwksUri`, JWKS cache TTL, or `audiences`).
* Before changing the BFFs' OIDC adapter (`customer-app`/`auth.ts`,
  `agent-app`/`server/index.ts`).
* When raising the WSO2 IS access-token TTL — would change the
  warm-token assertion's validity window.
* Quarterly.

## What it proves

* **Warm path still works.** A JWT minted before the partition
  continues to validate at Liberty (via cached JWKS) while WSO2 IS is
  unreachable. This is the SLO the design depends on: ~10-minute JWKS
  cache means a 30-second IS outage causes zero user-visible breakage
  for already-authenticated requests.
* **Cold path degrades cleanly.** A fresh `/auth/signin/...` POST at
  either BFF responds within 10 seconds (no hang). The response is
  either a 302 (BFF kicked off PKCE; the BROWSER will only discover
  the partition when it follows the redirect) or a 5xx (BFF detected
  IS is down before redirecting). Both are acceptable; 200 or a hang
  are not.
* **Recovery is immediate.** Reconnecting the container restores the
  ability to mint a fresh token.

## Pre-flight

```bash
podman ps --format '{{.Names}}' | grep -E '^(insurance-app|wso2is|customer-app|agent-app)$'
podman network inspect insurance-net --format '{{range .Containers}}{{.Name}} {{end}}'
# Should list wso2is among others.
```

## Expected outcome

```
== drill #28: partition wso2is  (iterations=3) ==
  -- iteration 1/3 --
    warm-token quote (pre-partition) HTTP=201
    PASS  iter 1: warm-token /api/quotes pre-partition -> 201
    >>> disconnecting wso2is from insurance-net
    warm-token quote (during partition) HTTP=201
    PASS  iter 1: warm-token /api/quotes during partition stays 2xx
    customer /auth/signin/wso2is HTTP=302
    agent /auth/signin HTTP=302
    PASS  iter 1: customer /auth/signin/wso2is did not hang (got a code)
    PASS  iter 1: agent /auth/signin did not hang (got a code)
    PASS  iter 1: customer signin is 302 or 5xx (not 200)
    PASS  iter 1: agent signin is 302 or 5xx (not 200)
    PASS  iter 1: fresh JWT mint fails while partitioned
    <<< reconnecting wso2is to insurance-net
    fresh-token quote (post-recovery) HTTP=201
    PASS  iter 1: fresh JWT mint succeeds after reconnect
    PASS  iter 1: fresh-token /api/quotes after recovery -> 201
  ...
== drill #28 results: PASS=N  FAIL=0 ==
```

## Expected duration

~30 seconds for 3 iterations. The slow part is the 8-second reconnect
settle wait between iterations to let DNS in the overlay refresh.

## How to run

```bash
bash /home/ze/insurance-app/tests/chaos/28-partition-wso2is.sh
```

Overrides:
* `RECONNECT_SETTLE=15` — give the network longer to converge.
* `CUSTOMER_BASE`, `AGENT_BASE` — non-local BFFs.

## If the assertion fails

* **`FAIL  iter N: warm-token /api/quotes during partition stays 2xx`**
  with a 401 — Liberty's JWKS cache TTL has been lowered enough that
  it expired during the drill, OR Liberty was misconfigured to re-fetch
  JWKS on every request. Check `server.xml`'s `mpJwt` element for the
  `jwksConnectTimeout` / cache settings. The default in Open Liberty
  is 10 minutes; this drill expects at least 60 seconds.
* **`FAIL  iter N: customer signin ... not 200`** with 200 — the BFF
  is returning a stale signed-in shell page instead of bouncing to IS.
  This would mean a stale session beat the partition; cookie-clear
  and re-run.
* **`FAIL  iter N: fresh JWT mint fails while partitioned`** — the
  partition didn't actually take effect. Inspect:
  ```bash
  podman network inspect insurance-net | grep -A1 wso2is
  ```
  If wso2is is still listed, retry the disconnect manually.
* **`FAIL  iter N: fresh JWT mint succeeds after reconnect`** — the
  IS container itself crashed during the drill (rare but possible if
  the disconnect happened mid-request). `podman logs wso2is --tail 50`
  and `podman start wso2is` if needed.

## Recovery if the drill is stuck

The trap always runs `podman network connect insurance-net wso2is`,
but `podman network connect` is idempotent-on-error: if the container
is already connected, the trap is a no-op (good). If you re-ran the
drill and got "container is already connected", that's safe — it
means the previous trap fired.

Manual emergency recovery:
```bash
podman network connect insurance-net wso2is || true
podman exec wso2is curl -k -sSf https://localhost:9443/oauth2/jwks >/dev/null \
  && echo "wso2is is reachable from inside its own container"
```
