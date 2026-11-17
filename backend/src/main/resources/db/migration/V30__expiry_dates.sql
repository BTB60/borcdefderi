-- Son istifadə tarixi: each goods-receipt line (a batch) can carry the expiry date of the goods.
-- What is still on the shelf of a batch is worked out from the product's stock (oldest goods are sold first).
ALTER TABLE purchase_items ADD COLUMN expiry_date DATE;
CREATE INDEX ix_purchase_items_expiry ON purchase_items(store_id, expiry_date) WHERE expiry_date IS NOT NULL;
