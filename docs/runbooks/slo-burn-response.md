# SLO burn-response runbook

What to do when an error-budget burn alert fires. Mirrors the burn-rate table in `docs/performance-budgets.md` and `docs/slos.md`, with concrete steps per severity level.

## At-a-glance

| Burn | Severity tag | Channel | First action | Time-bound |
|---|---|---|---|---|
| **≥ 14×** | `page` | PagerDuty + Slack | Acknowledge, roll back, halt deploys | within **15 min** |
| **5× – 14×** | `ticket` | Slack (auto-files Jira) | Investigate; pause feature work in the affected service | within **2 h** |
| **1× – 5×** | `info` | Quiet Slack channel | Triage during business hours | within **3 working days** |
| **< 1×** | none | none | Normal operations | n/a |

The burn-rate alert configurations live in `compose/infra/signoz/alert-rules.yml`.

## 14× burn

**This is a real page. The 30-day error budget will exhaust in under 24 hours at the current rate.**

1. **Acknowledge** the PagerDuty incident immediately. Set yourself as incident commander in Slack `#insurance-app-alerts`.

2. **Identify the affected SLI** from the alert payload's `sli` label:
   - `money_chain_availability` -> MC-1
   - `portal_availability` (look at `service_name` label) -> PT-1 or PT-2
   - `signin_success` -> SI-1 or SI-3
   - `synthetic_monitor` -> the 60 s probe is failing; jump straight to step 4

3. **Confirm with the synthetic monitor.** Tail the log to see if it agrees:
   ```
   ssh -J ze@dl385-2 ze@30.30.26.1 'tail -F ~/insurance-app/logs/synthetic-monitor.log'
   ```
   If quick-smoke is also failing, the outage is real and broad. If it's still green, suspect a partial-traffic issue (one customer, one endpoint, one upstream).

4. **Identify the most recent change.** A 14× burn is almost always change-induced. Check:
   - Last `podman` image pull / `git pull` on the VM: `cd ~/insurance-app && git log -5 --oneline`
   - Last successful deploy: GH Actions runs on `main`
   - Last config edit: `git log -- compose/`

5. **Decide: roll back or push forward.** Defaults:
   - If the burn started within 15 minutes of a deploy: **roll back**.
   - If it started without a recent deploy: investigate upstream / dependency first (WSO2 IS, Postgres, Kafka).
   - In either case: **halt new deploys** until the burn drops below 5×.

6. **Roll back recipe** (most common case):
   ```
   ssh -J ze@dl385-2 ze@30.30.26.1
   cd ~/insurance-app
   git log -5 --oneline                              # find the prior good commit
   git checkout <prev-sha> -- Containerfile pom.xml  # narrow rollback
   ./scripts/build.sh                                # rebuild
   podman restart insurance-app                      # cut over
   ./scripts/smoke.sh                                # verify
   ```

7. **Comms.** Post in `#insurance-app-alerts` every 15 minutes during the incident with: what you know, what you're trying, ETA.

8. **Recovery confirmation.** Wait for `insurance:<sli>_error_ratio:5m` to fall below the 14× threshold *and stay there for 10 minutes* before declaring all-clear. The 1h rate will lag by definition; that's OK as long as it's monotonically decreasing.

9. **Post-incident.** Within 24 h, file `docs/runbooks/incidents/<date>-<slug>.md`. Include the timeline, root cause, what fixed it, and the actual budget burn measured.

## 5× burn

**Not a page. File a ticket and pause feature work in the affected service.**

1. **Find the alert in Slack** (channel `#insurance-app-alerts`, auto-filed Jira ticket linked in the message).

2. **Pause feature work** in the affected service:
   - For `money_chain_availability`: pause merges into anything under `src/main/java/com/example/insurance/quote|policy|payment`.
   - For `portal_availability` (customer-app): pause merges under `gui/customer-app/`.
   - For `portal_availability` (agent-app): pause merges under `gui/agent-app/`.
   - For `signin_success`: pause anything touching WSO2 IS config or BFF auth code.

   Communicate the pause in Slack; this is the "stop the line" moment.

3. **Investigate.** Useful starting points:
   - SigNoz dashboard "money-chain" -> error breakdown by route and status code.
   - SigNoz "service map" -> which downstream call is failing?
   - Recent k6 baseline runs in CI -- did the regression appear in load tests first?

4. **Fix or defer.** If the fix is obvious + low-risk, ship it under the normal review process (a 5× burn is *not* a 14× burn -- normal change-control still applies). If the fix is large, write up a remediation plan in the ticket.

5. **Resume feature work** only after `insurance:<sli>_error_ratio:1h` has been below the 5× threshold for **1 hour**.

## 1× burn

**Informational. No paging, no work stoppage.**

1. The alert is in `#insurance-app-burn` (low-noise channel). It implies the SLO is exactly on track to exhaust the monthly budget -- not over, not under. Worth understanding why, but not worth interrupting feature work.

2. Add a `qa:performance` issue with the SigNoz dashboard link and a one-line hypothesis ("`/api/quotes` p99 climbing since X; possibly the Redis cache eviction tuning"). Triage at the next QA sync.

3. Do *not* page anyone, do *not* pause work. The whole point of a separate severity is to keep this signal cheap.

## Cross-references

- Error-budget table: `docs/performance-budgets.md` § "Error-budget policy"
- SLO definitions: `docs/slos.md`
- Concrete alert config: `compose/infra/signoz/alert-rules.yml`
- Synthetic monitor: `docs/runbooks/synthetic-monitoring.md`
- DR (if a 14× is caused by data loss): `docs/runbooks/disaster-recovery.md`
- Incident write-ups: `docs/runbooks/incidents/` (create on first incident)
