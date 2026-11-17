-- New employee permission: TILL_SETTINGS lets a cashier open the Windows kassa "Parametrlər" (printers, receipt
-- design, drawer, screen lock, touch mode). Without it a cashier only sets their own PIN there.
ALTER TABLE member_permissions DROP CONSTRAINT IF EXISTS member_permissions_permission_check;
ALTER TABLE member_permissions ADD CONSTRAINT member_permissions_permission_check
    CHECK (permission IN ('CUSTOMER_DEBTS', 'COMPANY_RECEIVABLES', 'COMPANY_PAYABLES', 'REPORTS', 'CASH', 'EXPENSES',
                          'INVENTORY', 'SALES', 'TILL_SETTINGS'));
