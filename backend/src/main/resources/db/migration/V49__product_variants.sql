-- Məhsul variantları (ölçü / rəng): a variant is an ordinary product (own code, barcode, price, stock) that points to
-- its model product. Sales, stock counts and reports keep working on products, so nothing else has to change.
ALTER TABLE products ADD COLUMN parent_id UUID;
ALTER TABLE products ADD COLUMN variant_size VARCHAR(30);
ALTER TABLE products ADD COLUMN variant_color VARCHAR(30);
ALTER TABLE products ADD CONSTRAINT fk_products_parent FOREIGN KEY (parent_id, store_id) REFERENCES products(id, store_id);
ALTER TABLE products ADD CONSTRAINT ck_products_variant
    CHECK (parent_id IS NULL OR variant_size IS NOT NULL OR variant_color IS NOT NULL);
CREATE INDEX ix_products_parent ON products(parent_id) WHERE parent_id IS NOT NULL;
-- one product per size + colour of a model
CREATE UNIQUE INDEX ux_products_variant ON products(parent_id, LOWER(COALESCE(variant_size, '')), LOWER(COALESCE(variant_color, '')))
    WHERE parent_id IS NOT NULL;
