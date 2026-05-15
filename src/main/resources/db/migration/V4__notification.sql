-- Slice 8 (feature 4): Notification audit log.
-- One row per dispatched notification — captures the source event, the
-- routed channel, the recipient, and the outcome. Operators querying
-- "did the customer ever receive a payment receipt for policy X" hit this
-- table directly; the actual delivery happens via MI's channel router.
CREATE TABLE notification (
    id              BIGSERIAL PRIMARY KEY,
    event_topic     VARCHAR(64)  NOT NULL,
    event_key       VARCHAR(120),
    channel         VARCHAR(20)  NOT NULL,           -- email / sms / push
    recipient       VARCHAR(200) NOT NULL,
    subject         VARCHAR(200) NOT NULL,
    body            TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING / SENT / FAILED
    external_ref    VARCHAR(100),
    failure_reason  VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    dispatched_at   TIMESTAMPTZ
);

CREATE INDEX idx_notification_event_topic ON notification (event_topic);
CREATE INDEX idx_notification_status      ON notification (status);
CREATE INDEX idx_notification_channel     ON notification (channel);
