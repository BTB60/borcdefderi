-- "Borc artır": adding more to an existing debt is its own ledger entry, with the date of the purchase,
-- an optional receipt (çek) number and a note. Additive only. Rollback script: db/rollback/V8__undo.sql.
ALTER TABLE financial_transactions DROP CONSTRAINT financial_transactions_transaction_type_check;
ALTER TABLE financial_transactions ADD CONSTRAINT financial_transactions_transaction_type_check
    CHECK (transaction_type IN ('DEBT_CREATED', 'DEBT_ADJUSTED', 'DEBT_INCREASED',
                                'PAYMENT_CASH', 'PAYMENT_CARD', 'PAYMENT_TRANSFER', 'PAYMENT_OTHER'));

ALTER TABLE financial_transactions
    ADD COLUMN entry_date     DATE,
    ADD COLUMN receipt_number VARCHAR(60),
    ADD COLUMN note           VARCHAR(500);
