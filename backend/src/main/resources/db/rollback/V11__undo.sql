-- Manual rollback of V11 (with deleting the V11 row from flyway_schema_history). Products, stock history and sales are lost.
-- Cash rows of sales stay (SALE / SALE_REFUND) but lose their sale link.
ALTER TABLE cash_transactions DROP CONSTRAINT IF EXISTS fk_cash_tx_sale;
ALTER TABLE cash_transactions DROP COLUMN IF EXISTS sale_id;
DROP TABLE IF EXISTS inventory_movements;
DROP TABLE IF EXISTS sale_returns;
DROP TABLE IF EXISTS sale_items;
DROP TABLE IF EXISTS sales;
DROP SEQUENCE IF EXISTS sale_number_seq;
DROP TABLE IF EXISTS products;
DROP SEQUENCE IF EXISTS product_code_seq;
DROP TABLE IF EXISTS product_categories;
-- debts with amount 0 (fully returned credit sales) must be handled before restoring amount > 0
ALTER TABLE debts DROP CONSTRAINT IF EXISTS debts_amount_check;
ALTER TABLE debts ADD CONSTRAINT debts_amount_check CHECK (amount > 0) NOT VALID;
DELETE FROM member_permissions WHERE permission IN ('INVENTORY', 'SALES');
ALTER TABLE member_permissions DROP CONSTRAINT IF EXISTS member_permissions_permission_check;
ALTER TABLE member_permissions ADD CONSTRAINT member_permissions_permission_check
    CHECK (permission IN ('CUSTOMER_DEBTS', 'COMPANY_RECEIVABLES', 'COMPANY_PAYABLES', 'REPORTS', 'CASH', 'EXPENSES'));
