# Chaos drill 25 — Kill Liberty mid-`@Transactional` during bind

Issue [#25](https://github.com/zeshaq/insurance-app/issues/25). Phase 5.

## When to run

* Before any change that touches `PolicyService.bind()`, `Redlock`, the
  `policy_quote_id_key` unique constraint, or Liberty's `transaction`
  feature configuration in `server.xml`.
* Quarterly, as a "no surprises" verification that we haven't drifted
  away from durable-bind semantics.

## What it proves

* Liberty's container-managed transaction either commits cleanly or
  rolls back cleanly on a hard kill; **no half-state is observable**
  in Postgres.
* If the kill landed after commit, the row carries `status=BOUND` and
  the bind is callable as idempotent (200 on subsequent calls).
* If the kill landed before commit, the row is absent and a fresh
  bind for the same `quote_id` succeeds with 201.

## Pre-flight

* SSH to the VM (`ssh -J ze@dl385-2 ze@30.30.26.1`).
* Confirm everything is up:
  ```bash
  podman ps --format '{{.Names}}' | grep -E '^(insurance-app|postgres|wso2is)$'
  curl -sSf http://localhost:9080/health/ready
  ```
* Confirm WSO2 IS creds exist: `ls /home/ze/insurance-app/.wso2is-creds`.

## Expected outcome

```
== drill #25: kill Liberty mid-bind (iterations=5) ==
  -- iteration 1/5 --
    quote_id=NNNN  policy_rows=1
    PASS  iter 1: 0 or 1 policy row for quote NNNN
    PASS  iter 1: policy.status = BOUND
  ...
== drill #25 results: PASS=N  FAIL=0 ==
```

## Expected duration

~90 seconds for a 5-iteration loop. Each iteration is bounded by:
* Bind request (≤30 s timeout, almost always sub-second).
* Liberty restart + `/health/ready` flip (~10-15 s on this VM).

## How to run

```bash
bash /home/ze/insurance-app/tests/chaos/25-kill-liberty-mid-bind.sh
```

Optional overrides:
* `ITERATIONS=10` — increase loop count to hunt rare races.
* `LIBERTY_BASE=http://liberty-staging:9080` — point at a non-local Liberty.

## If the assertion fails

This indicates a real resilience regression:

* **`FAIL  iter N: 0 or 1 policy row for quote NNNN` with `policy_rows=2`** —
  the `UNIQUE(quote_id)` constraint on `policy` has been dropped or
  Liberty is creating policies outside the `@Transactional` boundary.
  Inspect `src/main/resources/db/migration/` and `PolicyService.java`.
* **`FAIL  iter N: policy.status = BOUND` with another status** —
  somebody added a non-`BOUND` initial state to `PolicyService.bind()`.
  That's a separate slice; flag in a follow-up issue.
* **`FAIL  iter N: post-kill bind retry -> 201` with HTTP 409** —
  the Redlock isn't releasing. This drill doesn't focus on Redlock
  (drill 26 does), but a 409 here indicates the lock from the killed
  transaction outlived `LOCK_TTL`. Cross-check with drill 26.

## Recovery if the drill is stuck

The `trap` in the script restarts `insurance-app` automatically. If
that didn't run (e.g. SIGKILL on the drill itself):

```bash
podman start insurance-app
until curl -sSf http://localhost:9080/health/ready >/dev/null; do sleep 1; done
```

Then re-run `scripts/smoke.sh` to confirm steady state.
