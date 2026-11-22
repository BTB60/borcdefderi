-- Manual rollback of V13 (with deleting the V13 row from flyway_schema_history). Approval records and PINs are lost.
ALTER TABLE sale_returns DROP COLUMN IF EXISTS approved_by;
ALTER TABLE store_members DROP COLUMN IF EXISTS approval_locked_until;
ALTER TABLE store_members DROP COLUMN IF EXISTS approval_failed;
ALTER TABLE store_members DROP COLUMN IF EXISTS approval_pin_hash;
ALTER TABLE stores DROP COLUMN IF EXISTS return_approval_limit;
ALTER TABLE stores DROP COLUMN IF EXISTS return_approval_enabled;
DELETE FROM member_permissions WHERE permission = 'RETURN_APPROVE';
ALTER TABLE member_permissions DROP CONSTRAINT IF EXISTS member_permissions_permission_check;
ALTER TABLE member_permissions ADD CONSTRAINT member_permissions_permission_check
    CHECK (permission IN ('CUSTOMER_DEBTS', 'COMPANY_RECEIVABLES', 'COMPANY_PAYABLES', 'REPORTS', 'CASH', 'EXPENSES',
                          'INVENTORY', 'SALES'));
