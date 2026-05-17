# Chaos drill 26 — Kill Postgres during bind

Issue [#26](https://github.com/zeshaq/insurance-app/issues/26). Phase 5.

## When to run

* Before any change to `PolicyService.bind()`, `Redlock`, the Hikari
  connection pool config in `server.xml`, or the Postgres container's
  shutdown signal handling.
* When changing `LOCK_TTL` — this drill scrapes the constant out of
  `PolicyService.java` automatically so it stays in lockstep with the
  code.
* Quarterly.

## What it proves

* When Postgres becomes unavailable mid-transaction, Liberty returns a
  **5xx** (no silent success, no infinite hang).
* The Redis-backed distributed lock acquired at the start of the bind
  **self-releases via its TTL** even when Liberty's `finally`-block
  release call cannot complete. The acceptance test for this is:
  after Postgres recovers, a re-bind for the same `quote_id` returns
  201/200, **not 409**. A 409 would mean the lock leaked past TTL.
* The post-recovery bind proves the quote is still bindable — there is
  no permanent damage from the chaos.

## Pre-flight

* SSH to the VM.
* Confirm steady state:
  ```bash
  podman exec postgres pg_isready -U insurance
  curl -sSf http://localhost:9080/health/ready
  ```
* Confirm the `PolicyService.java` source is readable so the drill
  can scrape `LOCK_TTL`:
  ```bash
  test -r /home/ze/insurance-app/src/main/java/com/example/insurance/policy/PolicyService.java
  ```

## Expected outcome

```
== drill #26: kill postgres during bind  (LOCK_TTL=10s, iterations=3) ==
  -- iteration 1/3 --
    bind under DB-loss HTTP=500
    PASS  iter 1: bind under DB-loss returned 5xx
    re-bind after recovery HTTP=201
    PASS  iter 1: re-bind succeeded after DB recovery (200/201, NOT 409)
    PASS  iter 1: re-bind not 409 (lock did NOT leak past TTL)
  ...
== drill #26 results: PASS=N  FAIL=0 ==
```

## Expected duration

~3 minutes. Each iteration: 5-10 s for the racing bind, `LOCK_TTL+2`
seconds of waiting (12 s), Postgres restart (30-45 s on this VM), a
short pool-recovery window (5-10 s).

## How to run

```bash
bash /home/ze/insurance-app/tests/chaos/26-kill-postgres-during-bind.sh
```

Overrides:
* `ITERATIONS=5` — run more rounds.
* `PG_READY_TIMEOUT=180` — slower disk; bump if pg startup is sluggish.
* `LIBERTY_BASE` — non-local Liberty.

## If the assertion fails

* **`FAIL  iter N: bind under DB-loss returned 5xx`** with a 2xx
  response — Liberty acknowledged a bind that never persisted. Audit
  `PolicyService.bind()` for any path that returns before the `@Transactional`
  commit.
* **`FAIL  iter N: re-bind succeeded after DB recovery (200/201, NOT 409)`**
  with a 409 — the Redlock leaked past TTL. Likely causes:
  1. `Redlock.tryAcquire` was passed a Duration whose units don't
     match what's used in the `SET key val EX seconds NX` call.
  2. The Redis container also crashed during the chaos (it shouldn't —
     the drill only touches postgres). Verify with `podman ps`.
  3. `LOCK_TTL` was raised in code but the constant in this script is
     stale — the script scrapes the source on every run, so this is
     only possible if `POLICY_SERVICE_SRC` is misconfigured.

**Do not patch the code in this drill.** File a follow-up issue with
the failing iteration's `bind-N.code` and `rebind-N-*.body` files
(under `$WORK`, which the drill prints).

## Recovery if the drill is stuck

The trap unconditionally `podman start postgres`. Smoke confirms:
```bash
podman exec postgres pg_isready -U insurance
bash /home/ze/insurance-app/scripts/smoke.sh | tail -3
```
