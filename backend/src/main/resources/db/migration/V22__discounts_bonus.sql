-- Endirim və bonus: a customer can have a standing discount (%); the store can give bonus (% of what is paid)
-- which the customer later spends on a purchase. Bonus moves are kept as entries; the balance is their sum.
ALTER TABLE stores ADD COLUMN bonus_percent NUMERIC(5,2) NOT NULL DEFAULT 0 CHECK (bonus_percent >= 0 AND bonus_percent <= 50);
ALTER TABLE customers ADD COLUMN discount_percent NUMERIC(5,2) NOT NULL DEFAULT 0
    CHECK (discount_percent >= 0 AND discount_percent <= 100);
ALTER TABLE sales ADD COLUMN bonus_used NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (bonus_used >= 0);
ALTER TABLE sales ADD COLUMN bonus_earned NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (bonus_earned >= 0);

CREATE TABLE customer_bonus_entries (
    id          UUID PRIMARY KEY,
    store_id    UUID          NOT NULL REFERENCES stores(id),
    customer_id UUID          NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    sale_id     UUID          REFERENCES sales(id) ON DELETE CASCADE,
    kind        VARCHAR(10)   NOT NULL CHECK (kind IN ('EARN', 'USE', 'REVERSE', 'ADJUST')),
    amount      NUMERIC(14,2) NOT NULL CHECK (amount <> 0),
    note        VARCHAR(255),
    created_by  UUID          NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_bonus_customer ON customer_bonus_entries(customer_id, created_at);
