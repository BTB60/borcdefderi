-- Rekvizitlər for the qaimə-faktura: the store's tax number (VÖEN), bank details and director,
-- and the customer's VÖEN (companies buying wholesale).
ALTER TABLE stores
    ADD COLUMN tax_id       VARCHAR(20),
    ADD COLUMN bank_name    VARCHAR(100),
    ADD COLUMN bank_account VARCHAR(40),
    ADD COLUMN bank_code    VARCHAR(20),
    ADD COLUMN director     VARCHAR(100);
ALTER TABLE customers ADD COLUMN tax_id VARCHAR(20);
