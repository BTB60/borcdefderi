-- 1) Kuryer pulu təhvil verdi: the cash a courier took at the door goes into the till; each delivery remembers when.
ALTER TABLE deliveries ADD COLUMN handed_over_at TIMESTAMPTZ;
ALTER TABLE deliveries ADD COLUMN handed_over_by UUID REFERENCES users(id);
ALTER TABLE cash_transactions DROP CONSTRAINT cash_transactions_kind_check;
ALTER TABLE cash_transactions ADD CONSTRAINT cash_transactions_kind_check CHECK (kind IN (
    'OPENING', 'DEPOSIT', 'WITHDRAWAL', 'TRANSFER_IN', 'TRANSFER_OUT',
    'DEBT_COLLECTION', 'SUPPLIER_PAYMENT', 'EXPENSE', 'EXPENSE_REVERSAL',
    'SALE', 'SALE_REFUND', 'COUNT_ADJUSTMENT', 'PAYMENT_REVERSAL', 'PURCHASE', 'COURIER_HANDOVER'));

-- 2) Tədarükçüyə sifariş: an order to a supplier, turned into a goods receipt when the goods come; what did not come shows.
CREATE SEQUENCE purchase_order_number_seq START 1;
CREATE TABLE purchase_orders (
    id          UUID PRIMARY KEY,
    store_id    UUID         NOT NULL REFERENCES stores(id),
    number      VARCHAR(20)  NOT NULL UNIQUE,
    company_id  UUID         REFERENCES companies(id) ON DELETE SET NULL,
    status      VARCHAR(10)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'PARTIAL', 'RECEIVED', 'CANCELLED')),
    note        VARCHAR(500),
    created_by  UUID         NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_purchase_orders_store ON purchase_orders(store_id, status, created_at DESC);
CREATE TABLE purchase_order_items (
    id           UUID PRIMARY KEY,
    order_id     UUID          NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
    product_id   UUID          REFERENCES products(id) ON DELETE SET NULL,
    name         VARCHAR(200)  NOT NULL,
    quantity     NUMERIC(14,3) NOT NULL CHECK (quantity > 0),
    received_qty NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (received_qty >= 0)
);
CREATE INDEX ix_purchase_order_items_order ON purchase_order_items(order_id);
ALTER TABLE purchases ADD COLUMN purchase_order_id UUID REFERENCES purchase_orders(id) ON DELETE SET NULL;

-- 3) İşçi maaşı: each member's pay (a monthly salary or an hourly rate, a deduction per late day) and every advance /
-- salary payment (also booked as an "İşçi maaşı" expense, so the cash and profit see it).
ALTER TABLE store_members ADD COLUMN pay_type VARCHAR(10) CHECK (pay_type IS NULL OR pay_type IN ('MONTHLY', 'HOURLY'));
ALTER TABLE store_members ADD COLUMN pay_rate NUMERIC(14,2) CHECK (pay_rate IS NULL OR pay_rate >= 0);
ALTER TABLE store_members ADD COLUMN late_penalty NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (late_penalty >= 0);
CREATE TABLE payroll_entries (
    id          UUID PRIMARY KEY,
    store_id    UUID          NOT NULL REFERENCES stores(id),
    user_id     UUID          NOT NULL REFERENCES users(id),
    month       DATE          NOT NULL,
    kind        VARCHAR(10)   NOT NULL CHECK (kind IN ('ADVANCE', 'PAYMENT')),
    amount      NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    expense_id  UUID,
    note        VARCHAR(300),
    created_by  UUID          NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (EXTRACT(DAY FROM month) = 1)
);
CREATE INDEX ix_payroll_store_month ON payroll_entries(store_id, month, user_id);
