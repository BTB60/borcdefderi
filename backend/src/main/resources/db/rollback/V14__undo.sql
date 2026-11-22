-- Manual rollback of V14 (with deleting the V14 row from flyway_schema_history). Issued manager cards stop working.
DROP INDEX IF EXISTS ux_store_members_approval_card;
ALTER TABLE store_members DROP COLUMN IF EXISTS approval_card_issued_at;
ALTER TABLE store_members DROP COLUMN IF EXISTS approval_card_hash;
