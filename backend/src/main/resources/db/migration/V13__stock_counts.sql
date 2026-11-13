-- HesabPro: stock counts (anbar sayımı). A count is a document with one line per product: the system quantity, the
-- counted quantity and who counted it. On completion the differences are booked as COUNT movements that point back
-- to the count, and the count can no longer change. Additive only. Rollback: db/rollback/V13__undo.sql.

CREATE SEQUENCE stock_count_number_seq START 1;

CREATE TABLE stock_counts (
    id           UUID PRIMARY KEY,
    store_id     UUID         NOT NULL REFERENCES stores(id),
    number       VARCHAR(20)  NOT NULL UNIQUE,
    status       VARCHAR(12)  NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    category_id  UUID,                         -- NULL = all active products
    note         VARCHAR(500),
    created_by   UUID         NOT NULL REFERENCES users(id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_by UUID REFERENCES users(id),
    completed_at TIMESTAMPTZ,
    version      BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (id, store_id),
    FOREIGN KEY (category_id, store_id) REFERENCES product_categories(id, store_id),
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);
-- one open count per store at a time
CREATE UNIQUE INDEX ux_stock_counts_open ON stock_counts(store_id) WHERE status = 'IN_PROGRESS';
CREATE INDEX ix_stock_counts_store ON stock_counts(store_id, created_at DESC);

CREATE TABLE stock_count_lines (
    id          UUID PRIMARY KEY,
    count_id    UUID          NOT NULL,
    store_id    UUID          NOT NULL,
    product_id  UUID          NOT NULL,
    system_qty  NUMERIC(14,3) NOT NULL CHECK (system_qty >= 0),   -- stock when the line was counted (or when the count began)
    counted_qty NUMERIC(14,3) CHECK (counted_qty >= 0),            -- NULL = not counted yet
    unit_cost   NUMERIC(14,2) NOT NULL DEFAULT 0,                  -- purchase price used to value the difference
    counted_by  UUID REFERENCES users(id),
    counted_at  TIMESTAMPTZ,
    UNIQUE (count_id, product_id),
    FOREIGN KEY (count_id, store_id) REFERENCES stock_counts(id, store_id) ON DELETE CASCADE,
    FOREIGN KEY (product_id, store_id) REFERENCES products(id, store_id)
);
CREATE INDEX ix_stock_count_lines_count ON stock_count_lines(count_id);

ALTER TABLE inventory_movements ADD COLUMN stock_count_id UUID REFERENCES stock_counts(id);
