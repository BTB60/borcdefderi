-- IMEI / seriya nömrəsi və zəmanət: a product can ask for a serial number per unit at the till and carry a
-- warranty in months; each sold serial is kept, so the shop finds the sale (date, customer, price) by it.
ALTER TABLE products ADD COLUMN track_serial BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE products ADD COLUMN warranty_months SMALLINT CHECK (warranty_months BETWEEN 1 AND 120);

CREATE TABLE sale_item_serials (
    id            UUID PRIMARY KEY,
    store_id      UUID         NOT NULL REFERENCES stores(id),
    sale_id       UUID         NOT NULL REFERENCES sales(id),
    sale_item_id  UUID         NOT NULL REFERENCES sale_items(id),
    product_id    UUID         NOT NULL,
    serial        VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_serials_lookup ON sale_item_serials(store_id, serial);
CREATE INDEX ix_serials_item ON sale_item_serials(sale_item_id);
