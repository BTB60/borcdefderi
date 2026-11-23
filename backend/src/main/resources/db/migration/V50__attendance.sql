-- İşçi davamiyyəti: every member clocks in when they come and out when they leave. The owner sees who came when,
-- who was late (after the store's work start + grace minutes) and the hours worked, and may correct a record.
ALTER TABLE stores ADD COLUMN work_start_time TIME;
ALTER TABLE stores ADD COLUMN late_grace_minutes INT NOT NULL DEFAULT 10 CHECK (late_grace_minutes BETWEEN 0 AND 180);

CREATE TABLE attendance (
    id          UUID PRIMARY KEY,
    store_id    UUID         NOT NULL REFERENCES stores(id),
    user_id     UUID         NOT NULL REFERENCES users(id),
    work_date   DATE         NOT NULL,
    check_in    TIMESTAMPTZ  NOT NULL,
    check_out   TIMESTAMPTZ,
    late        BOOLEAN      NOT NULL DEFAULT FALSE,
    note        VARCHAR(300),
    edited_by   UUID         REFERENCES users(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (check_out IS NULL OR check_out >= check_in)
);
CREATE UNIQUE INDEX ux_attendance_open ON attendance(store_id, user_id) WHERE check_out IS NULL;
CREATE INDEX ix_attendance_store ON attendance(store_id, work_date DESC);
