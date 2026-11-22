-- Manager approval for sale returns: a cashier's return above the store's limit needs a manager's PIN.
-- Additive only. Rollback: db/rollback/V44__undo.sql.

ALTER TABLE member_permissions DROP CONSTRAINT IF EXISTS member_permissions_permission_check;
ALTER TABLE member_permissions ADD CONSTRAINT member_permissions_permission_check
    CHECK (permission IN ('CUSTOMER_DEBTS', 'COMPANY_RECEIVABLES', 'COMPANY_PAYABLES', 'REPORTS', 'CASH', 'EXPENSES',
                          'INVENTORY', 'SALES', 'TILL_SETTINGS', 'RETURN_APPROVE'));

-- Off by default, so existing stores keep working until the owner turns it on.
ALTER TABLE stores ADD COLUMN return_approval_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE stores ADD COLUMN return_approval_limit NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (return_approval_limit >= 0);

-- The approver's own PIN (BCrypt). The failure counter / lock belong to the member who TYPES the PIN (the cashier),
-- because a wrong PIN cannot be attributed to any manager.
ALTER TABLE store_members ADD COLUMN approval_pin_hash VARCHAR(100);
ALTER TABLE store_members ADD COLUMN approval_failed INT NOT NULL DEFAULT 0;
ALTER TABLE store_members ADD COLUMN approval_locked_until TIMESTAMPTZ;

ALTER TABLE sale_returns ADD COLUMN approved_by UUID REFERENCES users(id);
