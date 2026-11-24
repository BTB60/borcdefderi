-- HesabPro Kart (the customers' own app): a customer signs in with their phone (an SMS code) or with a card's
-- activation code, and sees every bonus card issued to that phone in any store that uses HesabPro.
CREATE TABLE customer_accounts (
    id            UUID PRIMARY KEY,
    phone         VARCHAR(15)  NOT NULL UNIQUE,   -- 994XXXXXXXXX
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ
);
CREATE TABLE customer_login_codes (
    id         UUID PRIMARY KEY,
    phone      VARCHAR(15)  NOT NULL,
    code_hash  VARCHAR(100) NOT NULL,
    attempts   INT          NOT NULL DEFAULT 0,
    used       BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_customer_login_codes_phone ON customer_login_codes(phone, created_at DESC);
-- the login codes are the platform's own SMS, not a store's
ALTER TABLE sms_messages ALTER COLUMN store_id DROP NOT NULL;
CREATE INDEX ix_loyalty_cards_phone ON loyalty_cards (right(regexp_replace(phone, '[^0-9]', '', 'g'), 9));
