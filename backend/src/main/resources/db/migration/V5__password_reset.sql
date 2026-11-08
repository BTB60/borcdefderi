-- Additive only. Forgotten-password recovery.

-- Set when an admin / store owner issues a temporary password: the user must choose a new one after login.
ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

-- One-time e-mail reset links. Only the SHA-256 of the token is stored.
CREATE TABLE password_reset_tokens (
    token_hash  CHAR(64)    PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users(id),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_reset_tokens_user ON password_reset_tokens(user_id);

-- "Şifrəni unutdum" requests, shown to the platform admin (manual recovery over phone / WhatsApp).
CREATE TABLE password_reset_requests (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users(id),
    status      VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DONE')),
    ip_address  VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    resolved_by UUID REFERENCES users(id)
);
CREATE INDEX ix_reset_requests_status ON password_reset_requests(status, created_at DESC);
