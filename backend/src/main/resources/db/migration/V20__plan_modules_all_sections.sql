-- Every menu section (except Abunəlik) can now be left out of a package. Packages that already listed their
-- paid modules keep the sections that used to be always open: debts, reports, pocket money and employees.
UPDATE subscription_plans
SET modules = concat_ws(',', NULLIF(modules, ''),
                        'COMPANY_PAYABLES,COMPANY_RECEIVABLES,CUSTOMER_DEBTS,EMPLOYEES,POCKET,REPORTS')
WHERE modules IS NOT NULL;
