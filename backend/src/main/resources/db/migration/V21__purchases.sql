-- Mal qəbulu (purchase receipts): goods bought from a supplier in one document. Stock goes up (RECEIPT movements),
-- the purchase price is updated, the whole amount goes onto "Bizim şirkətə borcumuz" for that supplier and the
-- part paid now is paid off that debt right away (or, without a supplier, taken from the till as PURCHASE).
CREATE SEQUENCE purchase_number_seq START 1;

CREATE TABLE purchases (
    id                UUID PRIMARY KEY,
    store_id          UUID          NOT NULL REFERENCES stores(id),
    number            VARCHAR(20)   NOT NULL UNIQUE,
    company_id        UUID          REFERENCES companies(id) ON DELETE SET NULL,
    supplier_invoice  VARCHAR(60),
    total             NUMERIC(14,2) NOT NULL CHECK (total >= 0),
    paid              NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (paid >= 0),
    debt_id           UUID          REFERENCES debts(id) ON DELETE SET NULL,
    received_on       DATE          NOT NULL,
    note              VARCHAR(500),
    created_by        UUID          NOT NULL REFERENCES users(id),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    client_request_id UUID,
    CHECK (paid <= total)
);
CREATE INDEX ix_purchases_store_day ON purchases(store_id, received_on);
CREATE UNIQUE INDEX ux_purchases_request ON purchases(store_id, client_request_id) WHERE client_request_id IS NOT NULL;

CREATE TABLE purchase_items (
    id          UUID PRIMARY KEY,
    purchase_id UUID          NOT NULL REFERENCES purchases(id) ON DELETE CASCADE,
    store_id    UUID          NOT NULL REFERENCES stores(id),
    product_id  UUID          REFERENCES products(id) ON DELETE SET NULL,
    name        VARCHAR(200)  NOT NULL,
    quantity    NUMERIC(14,3) NOT NULL CHECK (quantity > 0),
    unit_cost   NUMERIC(14,2) NOT NULL CHECK (unit_cost >= 0),
    line_total  NUMERIC(14,2) NOT NULL CHECK (line_total >= 0)
);
CREATE INDEX ix_purchase_items_purchase ON purchase_items(purchase_id);

ALTER TABLE cash_transactions DROP CONSTRAINT cash_transactions_kind_check;
ALTER TABLE cash_transactions ADD CONSTRAINT cash_transactions_kind_check CHECK (kind IN (
    'OPENING', 'DEPOSIT', 'WITHDRAWAL', 'TRANSFER_IN', 'TRANSFER_OUT',
    'DEBT_COLLECTION', 'SUPPLIER_PAYMENT', 'EXPENSE', 'EXPENSE_REVERSAL',
    'SALE', 'SALE_REFUND', 'COUNT_ADJUSTMENT', 'PAYMENT_REVERSAL', 'PURCHASE'));
