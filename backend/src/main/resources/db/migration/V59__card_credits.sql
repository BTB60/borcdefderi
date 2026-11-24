-- Bonus kartları pullu: a store buys card packages from the platform (1 card = 0.85 AZN by default). The owner sends a
-- payment request (package size, how it was paid, an optional receipt); the platform admin approves it and the cards
-- are added to the store's card balance. Every new bonus card uses one. Additive only.
ALTER TABLE stores ADD COLUMN card_credits INT NOT NULL DEFAULT 0 CHECK (card_credits >= 0);

CREATE TABLE card_credit_requests (
    id              UUID PRIMARY KEY,
    store_id        UUID          NOT NULL REFERENCES stores(id),
    quantity        INT           NOT NULL CHECK (quantity > 0),
    amount          NUMERIC(14,2) NOT NULL CHECK (amount >= 0),
    status          VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'GRANT')),
    payment_method  VARCHAR(10),
    payer_reference VARCHAR(120),
    note            VARCHAR(500),
    receipt         BYTEA,
    receipt_type    VARCHAR(60),
    receipt_name    VARCHAR(200),
    reject_reason   VARCHAR(300),
    requested_by    UUID REFERENCES users(id),
    requested_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    reviewed_by     UUID REFERENCES users(id),
    reviewed_at     TIMESTAMPTZ
);
CREATE INDEX ix_card_credit_requests_store ON card_credit_requests(store_id, requested_at DESC);
CREATE INDEX ix_card_credit_requests_pending ON card_credit_requests(requested_at) WHERE status = 'PENDING';
