-- Onlayn kataloq: a public page of the store's goods (switched on by the owner, secret-ish slug in the link)
-- where customers send orders; the orders land in the store's "Onlayn sifarişlər".
ALTER TABLE stores
    ADD COLUMN catalog_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN catalog_slug    VARCHAR(40) UNIQUE,
    ADD COLUMN catalog_note    VARCHAR(300);

CREATE TABLE online_orders (
    id             UUID PRIMARY KEY,
    store_id       UUID          NOT NULL REFERENCES stores(id),
    number         VARCHAR(20)   NOT NULL,
    customer_name  VARCHAR(100)  NOT NULL,
    customer_phone VARCHAR(30)   NOT NULL,
    address        VARCHAR(300),
    note           VARCHAR(500),
    total          NUMERIC(14,2) NOT NULL CHECK (total >= 0),
    status         VARCHAR(10)   NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'CONFIRMED', 'DONE', 'CANCELLED')),
    sale_id        UUID,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by     UUID REFERENCES users(id)
);
CREATE INDEX ix_online_orders_store ON online_orders(store_id, created_at DESC);
CREATE SEQUENCE online_order_number_seq START 1;

CREATE TABLE online_order_items (
    id         UUID PRIMARY KEY,
    order_id   UUID          NOT NULL REFERENCES online_orders(id) ON DELETE CASCADE,
    product_id UUID          REFERENCES products(id) ON DELETE SET NULL,
    name       VARCHAR(200)  NOT NULL,
    quantity   NUMERIC(14,3) NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(14,2) NOT NULL CHECK (unit_price >= 0),
    line_total NUMERIC(14,2) NOT NULL CHECK (line_total >= 0)
);
CREATE INDEX ix_online_order_items_order ON online_order_items(order_id);
