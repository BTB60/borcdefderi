-- Editing and cancelling (ləğv) debt payments. Nothing is deleted: a cancelled payment stays in the table with
-- voided_at set; the debt balance, the ledger and the cash desk are corrected with new reversal rows.
-- Additive only. Rollback: db/rollback/V14__undo.sql.

-- 1) Payment rows: who cancelled / last edited them, and why.
ALTER TABLE payments
    ADD COLUMN voided_at   TIMESTAMPTZ,
    ADD COLUMN voided_by   UUID REFERENCES users(id),
    ADD COLUMN void_reason VARCHAR(500),
    ADD COLUMN edited_at   TIMESTAMPTZ,
    ADD COLUMN edited_by   UUID REFERENCES users(id);

-- 2) Payments were append-only. Now: DELETE stays forbidden, a cancelled payment is frozen, and an UPDATE may not
--    move a payment to another store / debt or rewrite who recorded it and when.
CREATE OR REPLACE FUNCTION guard_payment_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Table payments: DELETE is not allowed (cancel the payment instead)';
    END IF;
    IF OLD.voided_at IS NOT NULL THEN
        RAISE EXCEPTION 'Table payments: a cancelled payment cannot be changed';
    END IF;
    IF NEW.id <> OLD.id OR NEW.store_id <> OLD.store_id OR NEW.debt_id <> OLD.debt_id
            OR NEW.recorded_by <> OLD.recorded_by OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Table payments: id, store, debt, recorded_by and created_at cannot be changed';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER trg_payments_immutable ON payments;
CREATE TRIGGER trg_payments_guard BEFORE UPDATE OR DELETE ON payments
    FOR EACH ROW EXECUTE FUNCTION guard_payment_mutation();

-- 3) Ledger: REVERSAL = a payment taken back (debt grows again by the amount).
ALTER TABLE financial_transactions DROP CONSTRAINT financial_transactions_transaction_type_check;
ALTER TABLE financial_transactions ADD CONSTRAINT financial_transactions_transaction_type_check
    CHECK (transaction_type IN ('DEBT_CREATED', 'DEBT_ADJUSTED', 'DEBT_INCREASED',
                                'PAYMENT_CASH', 'PAYMENT_CARD', 'PAYMENT_TRANSFER', 'PAYMENT_OTHER', 'REVERSAL'));

-- 4) Cash desk: PAYMENT_REVERSAL books a payment's money movement back (opposite direction), pointing at the
--    reversed row. A payment can now have several cash rows (original, reversal, corrected), so the
--    one-row-per-payment index becomes a plain index; each row can be reversed only once.
ALTER TABLE cash_transactions DROP CONSTRAINT cash_transactions_kind_check;
ALTER TABLE cash_transactions ADD CONSTRAINT cash_transactions_kind_check CHECK (kind IN (
    'OPENING', 'DEPOSIT', 'WITHDRAWAL', 'TRANSFER_IN', 'TRANSFER_OUT',
    'DEBT_COLLECTION', 'SUPPLIER_PAYMENT', 'EXPENSE', 'EXPENSE_REVERSAL',
    'SALE', 'SALE_REFUND', 'COUNT_ADJUSTMENT', 'PAYMENT_REVERSAL'));
ALTER TABLE cash_transactions ADD COLUMN reverses_id UUID REFERENCES cash_transactions(id);
DROP INDEX ux_cash_tx_payment;
CREATE INDEX ix_cash_tx_payment ON cash_transactions(payment_id) WHERE payment_id IS NOT NULL;
CREATE UNIQUE INDEX ux_cash_tx_reverses ON cash_transactions(reverses_id) WHERE reverses_id IS NOT NULL;
