-- Sərbəst məbləğ: one hidden system product per store for goods without a barcode / not in the system.
-- It is inactive (so it never shows in lists, the catalogue or plan limits) and keeps no stock.
ALTER TABLE products ADD COLUMN system_kind VARCHAR(20);
CREATE UNIQUE INDEX ux_products_system_kind ON products(store_id, system_kind) WHERE system_kind IS NOT NULL;
