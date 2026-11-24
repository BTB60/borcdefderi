-- Bonus kartı telefonla: when the customer has no card with them, the till finds the card by phone. Spending the
-- bonus that way needs a 4-digit code sent to the card's phone by SMS (hashed here, 10 minutes, 5 tries). Additive only.
CREATE TABLE bonus_spend_codes (
    id          UUID PRIMARY KEY,
    store_id    UUID         NOT NULL REFERENCES stores(id),
    card_id     UUID         NOT NULL REFERENCES loyalty_cards(id),
    code_hash   VARCHAR(64)  NOT NULL,
    attempts    INT          NOT NULL DEFAULT 0,
    used        BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_by  UUID REFERENCES users(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_bonus_spend_codes_card ON bonus_spend_codes(card_id, created_at DESC);
