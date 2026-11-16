-- Goods sent back to the supplier (a whole goods receipt or part of it): stock goes down, the supplier debt is
-- lowered and what was already paid can come back to the till.
ALTER TABLE purchase_items ADD COLUMN returned_qty NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (returned_qty >= 0);
ALTER TABLE purchases ADD COLUMN returned_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (returned_amount >= 0);
