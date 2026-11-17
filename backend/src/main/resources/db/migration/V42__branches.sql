-- Filiallar: a store owner can own several stores (branches) and switch between them with one account.
-- An owner now has one membership per branch; exactly one of them is "current" — every screen works on that one,
-- so nothing else in the app changes. Employees still belong to one store.
DO $$
DECLARE c text;
BEGIN
    SELECT con.conname INTO c
    FROM pg_constraint con
    WHERE con.conrelid = 'store_members'::regclass AND con.contype = 'u' AND array_length(con.conkey, 1) = 1
      AND con.conkey[1] = (SELECT attnum FROM pg_attribute WHERE attrelid = 'store_members'::regclass AND attname = 'user_id');
    IF c IS NOT NULL THEN
        EXECUTE 'ALTER TABLE store_members DROP CONSTRAINT ' || quote_ident(c);
    END IF;
END $$;

ALTER TABLE store_members ADD COLUMN is_current BOOLEAN NOT NULL DEFAULT TRUE;
CREATE UNIQUE INDEX ux_store_members_current ON store_members(user_id) WHERE is_current;
CREATE UNIQUE INDEX ux_store_members_user_store ON store_members(user_id, store_id);

-- the store a branch was opened from (null = the first store)
ALTER TABLE stores ADD COLUMN branch_of UUID REFERENCES stores(id);

-- goods sent from one branch to another
ALTER TABLE inventory_movements DROP CONSTRAINT IF EXISTS inventory_movements_kind_check;
ALTER TABLE inventory_movements ADD CONSTRAINT inventory_movements_kind_check
    CHECK (kind IN ('RECEIPT', 'ISSUE', 'SALE', 'SALE_RETURN', 'ADJUSTMENT', 'COUNT', 'TRANSFER_OUT', 'TRANSFER_IN'));
