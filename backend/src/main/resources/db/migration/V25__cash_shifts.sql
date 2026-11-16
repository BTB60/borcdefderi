-- Kassir növbəsi (smen): a cashier opens a shift with the cash in the drawer and closes it by counting the cash.
-- The Z-report compares what should be there (opening cash + the cashier's cash in/out during the shift) with what
-- was counted. One open shift per cashier.
CREATE TABLE cash_shifts (
    id            UUID PRIMARY KEY,
    store_id      UUID          NOT NULL REFERENCES stores(id),
    user_id       UUID          NOT NULL REFERENCES users(id),
    opened_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    opening_cash  NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (opening_cash >= 0),
    closed_at     TIMESTAMPTZ,
    expected_cash NUMERIC(14,2),
    counted_cash  NUMERIC(14,2) CHECK (counted_cash >= 0),
    difference    NUMERIC(14,2),
    note          VARCHAR(500),
    CHECK ((closed_at IS NULL) = (counted_cash IS NULL))
);
CREATE UNIQUE INDEX ux_shift_open ON cash_shifts(user_id) WHERE closed_at IS NULL;
CREATE INDEX ix_shifts_store ON cash_shifts(store_id, opened_at DESC);
