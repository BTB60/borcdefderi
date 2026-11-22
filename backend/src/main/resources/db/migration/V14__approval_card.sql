-- Manager card for return approval: a printed Code128 barcode the cashier scans instead of typing the PIN.
-- Only the SHA-256 of the card code is kept; issuing a new card replaces (revokes) the old one.
-- Rollback: db/rollback/V14__undo.sql.
ALTER TABLE store_members ADD COLUMN approval_card_hash VARCHAR(64);
ALTER TABLE store_members ADD COLUMN approval_card_issued_at TIMESTAMPTZ;
CREATE UNIQUE INDEX ux_store_members_approval_card ON store_members(approval_card_hash) WHERE approval_card_hash IS NOT NULL;
