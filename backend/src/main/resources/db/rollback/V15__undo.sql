-- Manual rollback of V15 (run together with deleting the V15 row from flyway_schema_history).
ALTER TABLE products DROP COLUMN IF EXISTS old_price;
