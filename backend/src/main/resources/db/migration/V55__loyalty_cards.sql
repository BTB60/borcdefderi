-- Bonus kartları: a card of its own per customer (one at most), with the holder's full name, phone and the card's own
-- bonus percent (overrides the store's percent for that customer). The card number is the customer's code (HP-000001),
-- which the till already scans. The activation code lets the customer open their own card page later.
CREATE TABLE loyalty_cards (
    id              UUID PRIMARY KEY,
    store_id        UUID          NOT NULL REFERENCES stores(id),
    customer_id     UUID          NOT NULL UNIQUE REFERENCES customers(id) ON DELETE CASCADE,
    first_name      VARCHAR(60)   NOT NULL,
    last_name       VARCHAR(60)   NOT NULL,
    patronymic      VARCHAR(60),
    phone           VARCHAR(30)   NOT NULL,
    percent         NUMERIC(5,2)  NOT NULL CHECK (percent >= 0 AND percent <= 50),
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    activation_code VARCHAR(10)   NOT NULL,
    created_by      UUID          NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_loyalty_cards_store ON loyalty_cards(store_id, created_at DESC);
