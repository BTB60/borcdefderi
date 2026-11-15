-- Two sale prices: Top 1 = topdan (wholesale), Top 2 = pərakəndə (retail, the existing sale_price).
-- The till always sells at retail; the cashier switches to Top 1 for a wholesale sale. NULL = same as retail.
ALTER TABLE products ADD COLUMN wholesale_price NUMERIC(14,2) CHECK (wholesale_price >= 0);
ALTER TABLE sales ADD COLUMN price_level VARCHAR(10) NOT NULL DEFAULT 'RETAIL' CHECK (price_level IN ('RETAIL', 'WHOLESALE'));
