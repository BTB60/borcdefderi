-- Avtomatik borc xatırlatması: every day the system finds customers whose debt is due soon or overdue and puts a
-- ready-made reminder on the store's list; the store sends it with one tap (WhatsApp / SMS) or skips it.
ALTER TABLE stores ADD COLUMN reminders_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE stores ADD COLUMN remind_days_before INT NOT NULL DEFAULT 1 CHECK (remind_days_before BETWEEN 0 AND 30);
ALTER TABLE stores ADD COLUMN remind_repeat_days INT NOT NULL DEFAULT 3 CHECK (remind_repeat_days BETWEEN 1 AND 60);

CREATE TABLE debt_reminders (
    id          UUID PRIMARY KEY,
    store_id    UUID          NOT NULL REFERENCES stores(id),
    customer_id UUID          NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    kind        VARCHAR(10)   NOT NULL CHECK (kind IN ('DUE_SOON', 'OVERDUE')),
    amount      NUMERIC(14,2) NOT NULL,
    due_date    DATE          NOT NULL,
    remind_on   DATE          NOT NULL,
    status      VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'SKIPPED')),
    channel     VARCHAR(10),
    handled_by  UUID          REFERENCES users(id),
    handled_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (customer_id, remind_on)
);
CREATE INDEX ix_reminders_store ON debt_reminders(store_id, status, remind_on);
