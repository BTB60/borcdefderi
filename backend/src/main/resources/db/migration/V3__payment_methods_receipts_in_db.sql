-- Additive only: no existing rows are changed or removed.

-- 1) More payment methods: bank transfer and "other" (besides cash and card).
ALTER TABLE payments DROP CONSTRAINT payments_payment_method_check;
ALTER TABLE payments ADD CONSTRAINT payments_payment_method_check
    CHECK (payment_method IN ('CASH', 'CARD', 'TRANSFER', 'OTHER'));

ALTER TABLE financial_transactions DROP CONSTRAINT financial_transactions_transaction_type_check;
ALTER TABLE financial_transactions ADD CONSTRAINT financial_transactions_transaction_type_check
    CHECK (transaction_type IN ('DEBT_CREATED', 'DEBT_ADJUSTED',
                                'PAYMENT_CASH', 'PAYMENT_CARD', 'PAYMENT_TRANSFER', 'PAYMENT_OTHER'));

-- 2) Subscription receipts are stored in the database (hosting disks such as Render's are wiped on deploy).
--    Older rows keep using storage_name on disk; new rows carry the bytes here.
ALTER TABLE subscription_receipts ADD COLUMN content BYTEA;

-- 3) Lookups used by the admin panel and payment receipts.
CREATE INDEX IF NOT EXISTS ix_subscriptions_store_requested ON subscriptions(store_id, requested_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_created ON audit_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS ix_fin_tx_payment ON financial_transactions(payment_id);
