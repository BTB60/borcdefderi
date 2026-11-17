-- Müştəri borc limiti: the most a customer may owe; a credit sale over it is stopped at the till
-- (only the owner can let it through). NULL = no limit.
ALTER TABLE customers ADD COLUMN credit_limit NUMERIC(14,2) CHECK (credit_limit >= 0);
