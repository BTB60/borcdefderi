-- Avtomatik SMS: when the platform has an SMS provider (SMS_* environment) and the store switches it on, the morning
-- debt reminders go out by SMS by themselves. Every SMS is logged (and counted against the monthly limit per store).
ALTER TABLE stores ADD COLUMN sms_auto_reminders BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE sms_messages (
    id          UUID PRIMARY KEY,
    store_id    UUID          NOT NULL REFERENCES stores(id),
    phone       VARCHAR(30)   NOT NULL,
    text        VARCHAR(1000) NOT NULL,
    purpose     VARCHAR(20)   NOT NULL,
    status      VARCHAR(10)   NOT NULL CHECK (status IN ('SENT', 'FAILED')),
    error       VARCHAR(500),
    ref_id      UUID,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_sms_store ON sms_messages(store_id, created_at DESC);
