-- Manual rollback of V13 (run together with deleting the V13 row from flyway_schema_history).
-- Count documents are lost; the COUNT stock movements they booked stay (only their link to the count is dropped).
ALTER TABLE inventory_movements DROP COLUMN IF EXISTS stock_count_id;
DROP TABLE IF EXISTS stock_count_lines;
DROP TABLE IF EXISTS stock_counts;
DROP SEQUENCE IF EXISTS stock_count_number_seq;
