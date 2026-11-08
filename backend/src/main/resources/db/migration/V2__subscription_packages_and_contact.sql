-- Subscription packages: monthly, 6-month and yearly. Old plans stay for history but are no longer sold.
ALTER TABLE subscription_plans DROP CONSTRAINT subscription_plans_code_check;
ALTER TABLE subscription_plans ADD CONSTRAINT subscription_plans_code_check
    CHECK (code IN ('START', 'STANDARD', 'PREMIUM', 'PREMIUM_YEARLY', 'MONTHLY', 'SEMI_ANNUAL', 'YEARLY'));

UPDATE subscription_plans SET active = FALSE WHERE code IN ('START', 'STANDARD', 'PREMIUM', 'PREMIUM_YEARLY');

-- Prices are starting values; the platform admin can change them in the admin panel (Planlar).
INSERT INTO subscription_plans (id, code, name, price, duration_months, active) VALUES
    ('00000000-0000-0000-0000-000000000011', 'MONTHLY',     'Aylıq',    19.00,  1,  TRUE),
    ('00000000-0000-0000-0000-000000000012', 'SEMI_ANNUAL', '6 aylıq',  99.00,  6,  TRUE),
    ('00000000-0000-0000-0000-000000000013', 'YEARLY',      'İllik',    179.00, 12, TRUE);

-- Platform-wide settings editable by the SUPER_ADMIN (e.g. the phone number shown on "Abunə ol").
CREATE TABLE platform_settings (
    key        VARCHAR(60)  PRIMARY KEY,
    value      VARCHAR(500),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users(id)
);
