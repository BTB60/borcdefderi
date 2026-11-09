-- Subscription packages managed entirely by the SUPER_ADMIN (prices per period, features, limits, colours),
-- richer payment requests (method, payer, declared amount, date, note) and store notifications.
-- Additive only: no existing row or column is removed. Rollback script: db/rollback/V7__undo.sql.

-- 1) Packages -------------------------------------------------------------------------------------------
ALTER TABLE subscription_plans DROP CONSTRAINT IF EXISTS subscription_plans_code_check;
ALTER TABLE subscription_plans ADD CONSTRAINT subscription_plans_code_check CHECK (code ~ '^[A-Z0-9_]{2,30}$');

ALTER TABLE subscription_plans
    ADD COLUMN description       VARCHAR(500),
    ADD COLUMN features          TEXT,                       -- one advantage per line
    ADD COLUMN max_employees     INT CHECK (max_employees IS NULL OR max_employees >= 0),
    ADD COLUMN max_customers     INT CHECK (max_customers IS NULL OR max_customers >= 0),
    ADD COLUMN sort_order        INT          NOT NULL DEFAULT 0,
    ADD COLUMN recommended       BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN archived          BOOLEAN      NOT NULL DEFAULT FALSE,
    -- price per period; NULL = this period is not offered
    ADD COLUMN price_monthly     NUMERIC(12,2) CHECK (price_monthly IS NULL OR price_monthly >= 0),
    ADD COLUMN price_quarterly   NUMERIC(12,2) CHECK (price_quarterly IS NULL OR price_quarterly >= 0),
    ADD COLUMN price_semi_annual NUMERIC(12,2) CHECK (price_semi_annual IS NULL OR price_semi_annual >= 0),
    ADD COLUMN price_yearly      NUMERIC(12,2) CHECK (price_yearly IS NULL OR price_yearly >= 0),
    ADD COLUMN custom_days       INT CHECK (custom_days IS NULL OR custom_days BETWEEN 1 AND 3660),
    ADD COLUMN custom_price      NUMERIC(12,2) CHECK (custom_price IS NULL OR custom_price >= 0),
    -- design only (never affects price or payment)
    ADD COLUMN primary_color     VARCHAR(7) NOT NULL DEFAULT '#101C35' CHECK (primary_color ~ '^#[0-9A-Fa-f]{6}$'),
    ADD COLUMN secondary_color   VARCHAR(7) NOT NULL DEFAULT '#D6AD60' CHECK (secondary_color ~ '^#[0-9A-Fa-f]{6}$'),
    ADD COLUMN background_color  VARCHAR(7) NOT NULL DEFAULT '#FFFFFF' CHECK (background_color ~ '^#[0-9A-Fa-f]{6}$'),
    ADD COLUMN text_color        VARCHAR(7) NOT NULL DEFAULT '#101C35' CHECK (text_color ~ '^#[0-9A-Fa-f]{6}$'),
    ADD COLUMN button_color      VARCHAR(7) NOT NULL DEFAULT '#101C35' CHECK (button_color ~ '^#[0-9A-Fa-f]{6}$'),
    ADD COLUMN created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN updated_at        TIMESTAMPTZ NOT NULL DEFAULT now();

-- Existing packages keep their price and duration, now expressed as a period price.
UPDATE subscription_plans SET price_monthly     = price WHERE duration_months = 1;
UPDATE subscription_plans SET price_quarterly   = price WHERE duration_months = 3;
UPDATE subscription_plans SET price_semi_annual = price WHERE duration_months = 6;
UPDATE subscription_plans SET price_yearly      = price WHERE duration_months = 12;
UPDATE subscription_plans SET custom_days = duration_months * 30, custom_price = price
WHERE duration_months NOT IN (1, 3, 6, 12);

UPDATE subscription_plans SET sort_order = duration_months;
-- The yearly package was already shown highlighted (dark card, gold button): keep that look.
UPDATE subscription_plans
SET recommended = TRUE, background_color = '#101C35', text_color = '#FFFFFF', button_color = '#D6AD60'
WHERE code = 'YEARLY';

-- 2) Payment requests (rows of "subscriptions" with status PENDING) ------------------------------------
ALTER TABLE subscriptions
    ADD COLUMN period_code     VARCHAR(12) CHECK (period_code IN ('MONTHLY', 'QUARTERLY', 'SEMI_ANNUAL', 'YEARLY', 'CUSTOM')),
    ADD COLUMN period_months   INT CHECK (period_months IS NULL OR period_months > 0),
    ADD COLUMN period_days     INT CHECK (period_days IS NULL OR period_days > 0),
    ADD COLUMN payment_method  VARCHAR(10) CHECK (payment_method IN ('M10', 'CARD')),
    ADD COLUMN payer_name      VARCHAR(120),
    ADD COLUMN declared_amount NUMERIC(12,2) CHECK (declared_amount IS NULL OR declared_amount >= 0),
    ADD COLUMN payment_date    DATE,
    ADD COLUMN note            VARCHAR(500);

-- Snapshot of the paid period, so later package edits never change what a store already paid for.
UPDATE subscriptions s SET period_months = p.duration_months,
       period_code = CASE p.duration_months WHEN 1 THEN 'MONTHLY' WHEN 3 THEN 'QUARTERLY'
                                            WHEN 6 THEN 'SEMI_ANNUAL' WHEN 12 THEN 'YEARLY' ELSE NULL END
FROM subscription_plans p WHERE p.id = s.plan_id AND s.period_months IS NULL;

-- 3) Store notifications ("Ödənişiniz təsdiqləndi" ...) ----------------------------------------------
CREATE TABLE store_notifications (
    id         UUID PRIMARY KEY,
    store_id   UUID         NOT NULL REFERENCES stores(id),
    kind       VARCHAR(40)  NOT NULL,
    title      VARCHAR(200) NOT NULL,
    message    VARCHAR(1000),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at    TIMESTAMPTZ
);
CREATE INDEX ix_store_notifications_store ON store_notifications(store_id, created_at DESC);
