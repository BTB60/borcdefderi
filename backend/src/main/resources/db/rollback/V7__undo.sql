-- Manual rollback of V7__plan_designer_payment_requests.sql (NOT run by Flyway).
-- Run only after taking a Neon branch/backup, and only if no package created after V7 is referenced
-- by a subscription (those plans would violate the old code CHECK). Afterwards delete the V7 row:
--   DELETE FROM flyway_schema_history WHERE version = '7';

BEGIN;

DROP TABLE IF EXISTS store_notifications;

ALTER TABLE subscriptions
    DROP COLUMN IF EXISTS period_code,
    DROP COLUMN IF EXISTS period_months,
    DROP COLUMN IF EXISTS period_days,
    DROP COLUMN IF EXISTS payment_method,
    DROP COLUMN IF EXISTS payer_name,
    DROP COLUMN IF EXISTS declared_amount,
    DROP COLUMN IF EXISTS payment_date,
    DROP COLUMN IF EXISTS note;

ALTER TABLE subscription_plans
    DROP COLUMN IF EXISTS description,
    DROP COLUMN IF EXISTS features,
    DROP COLUMN IF EXISTS max_employees,
    DROP COLUMN IF EXISTS max_customers,
    DROP COLUMN IF EXISTS sort_order,
    DROP COLUMN IF EXISTS recommended,
    DROP COLUMN IF EXISTS archived,
    DROP COLUMN IF EXISTS price_monthly,
    DROP COLUMN IF EXISTS price_quarterly,
    DROP COLUMN IF EXISTS price_semi_annual,
    DROP COLUMN IF EXISTS price_yearly,
    DROP COLUMN IF EXISTS custom_days,
    DROP COLUMN IF EXISTS custom_price,
    DROP COLUMN IF EXISTS primary_color,
    DROP COLUMN IF EXISTS secondary_color,
    DROP COLUMN IF EXISTS background_color,
    DROP COLUMN IF EXISTS text_color,
    DROP COLUMN IF EXISTS button_color,
    DROP COLUMN IF EXISTS created_at,
    DROP COLUMN IF EXISTS updated_at;

ALTER TABLE subscription_plans DROP CONSTRAINT IF EXISTS subscription_plans_code_check;
ALTER TABLE subscription_plans ADD CONSTRAINT subscription_plans_code_check
    CHECK (code IN ('START', 'STANDARD', 'PREMIUM', 'PREMIUM_YEARLY', 'MONTHLY', 'SEMI_ANNUAL', 'YEARLY'));

COMMIT;
