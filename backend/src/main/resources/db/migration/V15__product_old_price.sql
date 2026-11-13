-- HesabPro: price before a discount (endirimdən əvvəlki qiymət). Shown struck through on barcode labels when it is
-- higher than the sale price. NULL = no discount. Additive only. Rollback: db/rollback/V15__undo.sql.
ALTER TABLE products ADD COLUMN old_price NUMERIC(14,2) CHECK (old_price IS NULL OR old_price > 0);
