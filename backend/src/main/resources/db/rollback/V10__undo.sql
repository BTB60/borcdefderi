-- Manual rollback of V10 (run together with deleting the V10 row from flyway_schema_history).
-- All cash movements, expenses, recurring plans and budgets are lost. Employees lose CASH / EXPENSES permissions.
DROP TABLE IF EXISTS budgets;
DROP TABLE IF EXISTS expense_attachments;
ALTER TABLE IF EXISTS cash_transactions DROP CONSTRAINT IF EXISTS fk_cash_tx_expense;
DROP TABLE IF EXISTS expenses;
DROP TABLE IF EXISTS recurring_expenses;
DROP TABLE IF EXISTS expense_categories;
DROP TABLE IF EXISTS cash_transactions;
DROP TABLE IF EXISTS cash_accounts;
DELETE FROM member_permissions WHERE permission IN ('CASH', 'EXPENSES');
ALTER TABLE member_permissions DROP CONSTRAINT IF EXISTS member_permissions_permission_check;
ALTER TABLE member_permissions ADD CONSTRAINT member_permissions_permission_check
    CHECK (permission IN ('CUSTOMER_DEBTS', 'COMPANY_RECEIVABLES', 'COMPANY_PAYABLES', 'REPORTS'));
