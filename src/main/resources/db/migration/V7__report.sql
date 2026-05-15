-- Slice 12 (feature 7): scheduled report snapshots.
-- Each row is one tick of MI's quartz task hitting POST /api/reports/snapshot.
-- source captures who triggered the snapshot — "mi-scheduled-task" today,
-- but ad-hoc snapshots fired from Liberty REST would write a different value.
CREATE TABLE report_run (
    id                BIGSERIAL PRIMARY KEY,
    succeeded_count   BIGINT      NOT NULL DEFAULT 0,
    failed_count      BIGINT      NOT NULL DEFAULT 0,
    unknown_count     BIGINT      NOT NULL DEFAULT 0,
    source            VARCHAR(50) NOT NULL DEFAULT 'mi-scheduled-task',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_report_run_created_at ON report_run (created_at);
