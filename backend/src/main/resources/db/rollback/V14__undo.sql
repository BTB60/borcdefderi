-- Manual rollback of V14 (with deleting the V14 row from flyway_schema_history).
-- Cancelled / corrected payments keep their reversal rows; those rows must be removed by hand (with the
-- immutability triggers disabled) before the old constraints can be restored, otherwise they stay NOT VALID.
DROP INDEX IF EXISTS ux_cash_tx_reverses;
DROP INDEX IF EXISTS ix_cash_tx_payment;
CREATE UNIQUE INDEX IF NOT EXISTS ux_cash_tx_payment ON cash_transactions(payment_id)
    WHERE payment_id IS NOT NULL AND kind <> 'PAYMENT_REVERSAL' AND reverses_id IS NULL;
ALTER TABLE cash_transactions DROP CONSTRAINT IF EXISTS cash_transactions_kind_check;
ALTER TABLE cash_transactions ADD CONSTRAINT cash_transactions_kind_check CHECK (kind IN (
    'OPENING', 'DEPOSIT', 'WITHDRAWAL', 'TRANSFER_IN', 'TRANSFER_OUT',
    'DEBT_COLLECTION', 'SUPPLIER_PAYMENT', 'EXPENSE', 'EXPENSE_REVERSAL',
    'SALE', 'SALE_REFUND', 'COUNT_ADJUSTMENT')) NOT VALID;
ALTER TABLE financial_transactions DROP CONSTRAINT IF EXISTS financial_transactions_transaction_type_check;
ALTER TABLE financial_transactions ADD CONSTRAINT financial_transactions_transaction_type_check
    CHECK (transaction_type IN ('DEBT_CREATED', 'DEBT_ADJUSTED', 'DEBT_INCREASED',
                                'PAYMENT_CASH', 'PAYMENT_CARD', 'PAYMENT_TRANSFER', 'PAYMENT_OTHER')) NOT VALID;
DROP TRIGGER IF EXISTS trg_payments_guard ON payments;
DROP FUNCTION IF EXISTS guard_payment_mutation();
CREATE TRIGGER trg_payments_immutable BEFORE UPDATE OR DELETE ON payments
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
-- columns are kept (data of cancelled payments); drop them only if no payment was cancelled or edited:
-- ALTER TABLE payments DROP COLUMN voided_at, DROP COLUMN voided_by, DROP COLUMN void_reason,
--     DROP COLUMN edited_at, DROP COLUMN edited_by;
ALTER TABLE cash_transactions DROP COLUMN IF EXISTS reverses_id;
DROP INDEX IF EXISTS ux_fin_tx_reverses;
ALTER TABLE financial_transactions DROP COLUMN IF EXISTS reverses_entry_id;
