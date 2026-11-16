-- Promo codes: a discount on the package price when the store sends its payment request.
CREATE TABLE promo_codes (
    id           UUID PRIMARY KEY,
    code         VARCHAR(40)   NOT NULL UNIQUE,
    percent_off  INTEGER       CHECK (percent_off BETWEEN 1 AND 100),
    amount_off   NUMERIC(12,2) CHECK (amount_off > 0),
    max_uses     INTEGER       CHECK (max_uses > 0),
    valid_until  DATE,
    active       BOOLEAN       NOT NULL DEFAULT TRUE,
    note         VARCHAR(200),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   UUID REFERENCES users(id),
    CHECK ((percent_off IS NULL) <> (amount_off IS NULL))
);

ALTER TABLE subscriptions
    ADD COLUMN promo_code_id UUID REFERENCES promo_codes(id),
    ADD COLUMN discount      NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (discount >= 0),
    ADD COLUMN trial         BOOLEAN       NOT NULL DEFAULT FALSE;
CREATE INDEX ix_subscriptions_promo ON subscriptions(promo_code_id) WHERE promo_code_id IS NOT NULL;

-- Free trial for new stores: 14 days by default; the admin can change or switch it off (0).
INSERT INTO platform_settings (key, value) VALUES ('trial_days', '14') ON CONFLICT (key) DO NOTHING;

-- Announcements from the platform admin to every store (banner + notification).
CREATE TABLE announcements (
    id          UUID PRIMARY KEY,
    title       VARCHAR(150)  NOT NULL,
    body        VARCHAR(2000) NOT NULL,
    level       VARCHAR(10)   NOT NULL DEFAULT 'INFO' CHECK (level IN ('INFO', 'WARNING', 'SUCCESS')),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    ends_on     DATE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by  UUID REFERENCES users(id)
);
CREATE INDEX ix_announcements_active ON announcements(active, created_at DESC);
