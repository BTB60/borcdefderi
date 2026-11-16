-- İki addımlı giriş: after the password, a 6-digit code sent to the user's e-mail is asked (when switched on).
ALTER TABLE users ADD COLUMN two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE login_challenges (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash  VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts   INT         NOT NULL DEFAULT 0,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_login_challenges_user ON login_challenges(user_id, created_at DESC);
