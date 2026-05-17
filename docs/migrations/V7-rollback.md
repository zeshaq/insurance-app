# V7__report.sql — Rollback

## Forward migration

Creates `report_run` — one row per snapshot tick of MI's quartz task
hitting `POST /api/reports/snapshot`:

- `succeeded_count`, `failed_count`, `unknown_count` — per-tick rollup.
- `source VARCHAR(50) NOT NULL DEFAULT 'mi-scheduled-task'` — who fired
  the tick. Today only the scheduled task, but ad-hoc REST snapshots
  would write a different value.
- `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
- `idx_report_run_created_at` for time-range queries on the report
  history.

## Reverse SQL (run manually)

```sql
BEGIN;

DROP INDEX IF EXISTS idx_report_run_created_at;

-- No tables FK into report_run; safe to drop independently.
DROP TABLE IF EXISTS report_run;

DELETE FROM flyway_schema_history WHERE version = '7';

COMMIT;
```

## Risk + caveats

- **Operational telemetry, not customer data.** report_run holds counters,
  not user-impacting rows. Loss is low-risk vs V1-V6, but the historical
  report timeline (used for capacity planning + the dashboards) goes
  with it. Archive `pg_dump -t report_run` if those dashboards matter.
- **MI's scheduled task keeps firing.** Same shape as V4: MI's quartz task
  continues to call `POST /api/reports/snapshot`, which inserts into
  `report_run`. After the DROP, every tick logs a JPA failure. Disable
  the MI scheduled task BEFORE running the DROP (compose stop or
  `mi-cli stop-task report-snapshot`), and re-enable after V7 is
  re-applied.
- **Leaf table.** No FK fan-out, so the rollback is local and reversible
  in isolation — V1-V6 stay intact.
- **The DEFAULT now() detail.** Re-applying V7 after rollback creates a
  fresh empty table. If a report consumer relies on historical row ids
  (e.g. /reports/latest), restore from the archived dump rather than
  letting V7 recreate the table empty.

## Estimated time on prod-scale data

- Empty / small: &lt;1s.
- 100k report_run rows (years of hourly snapshots): &lt;5s.
- This table grows slowly (one row per scheduled tick), so even
  long-running deployments stay small. Rollback is effectively constant
  time at any realistic scale.
