-- Manual rollback of V9 (run together with deleting the V9 row from flyway_schema_history).
-- Customer notes and portal links are lost; customer codes are removed.
DROP INDEX IF EXISTS ix_fin_tx_receipt;
DROP TABLE IF EXISTS customer_portal_tokens;
DROP TABLE IF EXISTS customer_notes;
ALTER TABLE customers DROP COLUMN IF EXISTS last_contact_at;
DROP INDEX IF EXISTS ux_customers_code;
ALTER TABLE customers DROP COLUMN IF EXISTS code;
DROP SEQUENCE IF EXISTS customer_code_seq;
