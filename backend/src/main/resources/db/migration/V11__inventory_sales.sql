-- HesabPro Cloud ERP, stage 8: products, stock movements, sales, returns. Additive only.
-- Rollback: db/rollback/V11__undo.sql.

ALTER TABLE member_permissions DROP CONSTRAINT IF EXISTS member_permissions_permission_check;
ALTER TABLE member_permissions ADD CONSTRAINT member_permissions_permission_check
    CHECK (permission IN ('CUSTOMER_DEBTS', 'COMPANY_RECEIVABLES', 'COMPANY_PAYABLES', 'REPORTS', 'CASH', 'EXPENSES',
                          'INVENTORY', 'SALES'));

-- A returned credit sale lowers its debt; when the whole sale is returned the debt amount may reach 0.
ALTER TABLE debts DROP CONSTRAINT IF EXISTS debts_amount_check;
ALTER TABLE debts ADD CONSTRAINT debts_amount_check CHECK (amount >= 0);

-- 1) Products ---------------------------------------------------------------------------------------------
CREATE TABLE product_categories (
    id         UUID PRIMARY KEY,
    store_id   UUID         NOT NULL REFERENCES stores(id),
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (id, store_id)
);
CREATE UNIQUE INDEX ux_product_categories_name ON product_categories(store_id, LOWER(name));

CREATE SEQUENCE product_code_seq START 1;

CREATE TABLE products (
    id              UUID PRIMARY KEY,
    store_id        UUID          NOT NULL REFERENCES stores(id),
    code            VARCHAR(30)   NOT NULL,
    barcode         VARCHAR(64),
    name            VARCHAR(200)  NOT NULL,
    category_id     UUID,
    supplier_id     UUID,                       -- an existing company of the store
    unit            VARCHAR(20)   NOT NULL DEFAULT 'ədəd',
    purchase_price  NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (purchase_price >= 0),
    sale_price      NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (sale_price >= 0),
    stock_qty       NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (stock_qty >= 0),
    min_stock       NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (min_stock >= 0),
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    image           BYTEA,
    image_type      VARCHAR(30),
    created_by      UUID          NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (id, store_id),
    FOREIGN KEY (category_id, store_id) REFERENCES product_categories(id, store_id),
    FOREIGN KEY (supplier_id, store_id) REFERENCES companies(id, store_id)
);
CREATE UNIQUE INDEX ux_products_code ON products(store_id, LOWER(code));
CREATE UNIQUE INDEX ux_products_barcode ON products(store_id, barcode) WHERE barcode IS NOT NULL;
CREATE INDEX ix_products_name ON products(store_id, LOWER(name));

-- 2) Sales ------------------------------------------------------------------------------------------------
CREATE SEQUENCE sale_number_seq START 1;

CREATE TABLE sales (
    id                UUID PRIMARY KEY,
    store_id          UUID          NOT NULL REFERENCES stores(id),
    number            VARCHAR(20)   NOT NULL UNIQUE,
    customer_id       UUID,
    status            VARCHAR(10)   NOT NULL CHECK (status IN ('COMPLETED', 'CANCELLED')),
    subtotal          NUMERIC(14,2) NOT NULL CHECK (subtotal >= 0),
    discount          NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (discount >= 0),
    total             NUMERIC(14,2) NOT NULL CHECK (total >= 0),
    paid_cash         NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (paid_cash >= 0),
    paid_card         NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (paid_card >= 0),
    credit_amount     NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (credit_amount >= 0),
    returned_amount   NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (returned_amount >= 0),
    cost_total        NUMERIC(14,2) NOT NULL DEFAULT 0,
    debt_id           UUID,
    sold_on           DATE          NOT NULL,
    note              VARCHAR(500),
    created_by        UUID          NOT NULL REFERENCES users(id),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    cancelled_at      TIMESTAMPTZ,
    client_request_id UUID,
    UNIQUE (id, store_id),
    CHECK (total = subtotal - discount),
    CHECK (paid_cash + paid_card + credit_amount = total),
    CHECK (credit_amount = 0 OR customer_id IS NOT NULL),
    FOREIGN KEY (customer_id, store_id) REFERENCES customers(id, store_id),
    FOREIGN KEY (debt_id, store_id) REFERENCES debts(id, store_id)
);
CREATE INDEX ix_sales_store_day ON sales(store_id, sold_on);
CREATE UNIQUE INDEX ux_sales_request ON sales(store_id, client_request_id) WHERE client_request_id IS NOT NULL;

CREATE TABLE sale_items (
    id            UUID PRIMARY KEY,
    sale_id       UUID          NOT NULL,
    store_id      UUID          NOT NULL,
    product_id    UUID          NOT NULL,
    name          VARCHAR(200)  NOT NULL,          -- snapshot
    quantity      NUMERIC(14,3) NOT NULL CHECK (quantity > 0),
    unit_price    NUMERIC(14,2) NOT NULL CHECK (unit_price >= 0),
    discount      NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (discount >= 0),
    line_total    NUMERIC(14,2) NOT NULL CHECK (line_total >= 0),
    unit_cost     NUMERIC(14,2) NOT NULL DEFAULT 0,  -- purchase price at sale time (COGS)
    returned_qty  NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (returned_qty >= 0 AND returned_qty <= quantity),
    FOREIGN KEY (sale_id, store_id) REFERENCES sales(id, store_id),
    FOREIGN KEY (product_id, store_id) REFERENCES products(id, store_id)
);
CREATE INDEX ix_sale_items_sale ON sale_items(sale_id);
CREATE INDEX ix_sale_items_product ON sale_items(product_id);

CREATE TABLE sale_returns (
    id           UUID PRIMARY KEY,
    sale_id      UUID          NOT NULL,
    store_id     UUID          NOT NULL,
    amount       NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    cost_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,
    debt_reduced NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (debt_reduced >= 0),
    refunded     NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (refunded >= 0),
    refund_account_id UUID,
    returned_on  DATE          NOT NULL,
    reason       VARCHAR(500),
    created_by   UUID          NOT NULL REFERENCES users(id),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (debt_reduced + refunded = amount),
    FOREIGN KEY (sale_id, store_id) REFERENCES sales(id, store_id)
);
CREATE INDEX ix_sale_returns_sale ON sale_returns(sale_id);

-- 3) Stock movements: every change of a product's quantity, append-only ---------------------------------
CREATE TABLE inventory_movements (
    id             UUID PRIMARY KEY,
    store_id       UUID          NOT NULL REFERENCES stores(id),
    product_id     UUID          NOT NULL,
    kind           VARCHAR(15)   NOT NULL CHECK (kind IN ('RECEIPT', 'ISSUE', 'SALE', 'SALE_RETURN', 'ADJUSTMENT', 'COUNT')),
    quantity       NUMERIC(14,3) NOT NULL CHECK (quantity <> 0),   -- signed: + into stock, - out of stock
    unit_cost      NUMERIC(14,2),
    balance_after  NUMERIC(14,3) NOT NULL CHECK (balance_after >= 0),
    sale_id        UUID,
    note           VARCHAR(500),
    actor_id       UUID          NOT NULL REFERENCES users(id),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    FOREIGN KEY (product_id, store_id) REFERENCES products(id, store_id)
);
CREATE INDEX ix_inventory_product ON inventory_movements(product_id, created_at);
CREATE TRIGGER trg_inventory_immutable BEFORE UPDATE OR DELETE ON inventory_movements
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- 4) Money of a sale goes through the cash desk ------------------------------------------------------------
ALTER TABLE cash_transactions ADD COLUMN sale_id UUID;
ALTER TABLE cash_transactions ADD CONSTRAINT fk_cash_tx_sale FOREIGN KEY (sale_id, store_id) REFERENCES sales(id, store_id);
