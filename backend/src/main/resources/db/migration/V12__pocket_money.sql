-- "Cib pulu": a personal pocket-money log per user and store. Money put into the pocket (IN) and spent from it (OUT).
-- Deliberately separate from cash accounts and expenses: nothing here touches kassa, xərclər or profit.
CREATE TABLE pocket_entries (
    id          UUID PRIMARY KEY,
    store_id    UUID          NOT NULL REFERENCES stores(id),
    user_id     UUID          NOT NULL REFERENCES users(id),
    entry_date  DATE          NOT NULL,
    kind        VARCHAR(3)    NOT NULL CHECK (kind IN ('IN', 'OUT')),
    amount      NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    note        VARCHAR(200),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_pocket_entries_owner_date ON pocket_entries(store_id, user_id, entry_date);
