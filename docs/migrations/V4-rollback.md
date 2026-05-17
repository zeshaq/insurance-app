# V4__notification.sql — Rollback

## Forward migration

Creates the `notification` audit-log table — one row per dispatched
notification regardless of channel:

- `event_topic`, `event_key` — the upstream Kafka event that triggered the
  notification.
- `channel` ∈ { `email`, `sms`, `push` } — the routed delivery channel.
- `recipient`, `subject`, `body` — what was actually sent.
- `status` ∈ { `PENDING`, `SENT`, `FAILED` }, `external_ref` from the
  delivery provider, `failure_reason` on failure.
- Three indexes for the operator queries: by event_topic, status, channel.

## Reverse SQL (run manually)

```sql
BEGIN;

DROP INDEX IF EXISTS idx_notification_channel;
DROP INDEX IF EXISTS idx_notification_status;
DROP INDEX IF EXISTS idx_notification_event_topic;

-- No tables FK into notification (it's a leaf log table), so DROP is local.
DROP TABLE IF EXISTS notification;

DELETE FROM flyway_schema_history WHERE version = '4';

COMMIT;
```

## Risk + caveats

- **Audit-trail erasure.** Notification rows are the answer to "did the
  customer ever receive a receipt for policy X." Rolling back V4 erases
  that record. Compliance / customer-support sign-off recommended before
  drop. Archive a `pg_dump -t notification` to cold storage first.
- **No FK fan-out.** Safe to drop independently; no order dependency with
  V5 / V6 / V7.
- **In-flight notifications keep firing.** Rolling back V4 does NOT stop
  MI's notification router from trying to write new rows. The next write
  attempts a `INSERT INTO notification ...` and fails with `relation
  "notification" does not exist` — Liberty's notification consumer will
  start logging exceptions. Pause MI's `notify-route` channel or stop the
  notification consumer BEFORE running the DROP.
- **Body column is wide TEXT.** A bloated table with millions of email
  bodies takes longer to dump than the schema suggests. Plan dump time
  proportional to total body bytes, not row count.

## Estimated time on prod-scale data

- Empty / small: &lt;1s.
- 1M notifications with average 2 KB body: ~5s drop, ~2 minutes to
  `pg_dump` for archive.
- 10M+: 30s drop; archival dump scales with total body bytes — figure 1
  GB/minute on the demo's I/O budget.
