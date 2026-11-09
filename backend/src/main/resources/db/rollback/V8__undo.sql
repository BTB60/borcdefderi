-- Manual rollback of V8. Fails on purpose if DEBT_INCREASED rows exist (the ledger is append-only):
-- such history must not be thrown away silently.
ALTER TABLE financial_transactions DROP COLUMN IF EXISTS note, DROP COLUMN IF EXISTS receipt_number,
    DROP COLUMN IF EXISTS entry_date;
ALTER TABLE financial_transactions DROP CONSTRAINT financial_transactions_transaction_type_check;
ALTER TABLE financial_transactions ADD CONSTRAINT financial_transactions_transaction_type_check
    CHECK (transaction_type IN ('DEBT_CREATED', 'DEBT_ADJUSTED',
                                'PAYMENT_CASH', 'PAYMENT_CARD', 'PAYMENT_TRANSFER', 'PAYMENT_OTHER'));
