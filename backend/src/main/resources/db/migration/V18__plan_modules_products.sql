-- Package limits: how many products, and which paid modules (SALES, INVENTORY, CASH, EXPENSES) are open.
-- modules NULL = every module (all packages created before this keep working as they did).
ALTER TABLE subscription_plans
    ADD COLUMN max_products INT CHECK (max_products IS NULL OR max_products >= 0),
    ADD COLUMN modules      TEXT;
