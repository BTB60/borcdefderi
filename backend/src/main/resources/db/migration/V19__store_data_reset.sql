-- The platform admin can wipe a store's business data ("məlumatları sıfırla"). Ledger-like tables stay append-only
-- for everyone else: a DELETE passes only inside the purge transaction of that very store, which sets
--   SELECT set_config('app.store_purge', '<store id>', true)   -- transaction-local
-- Audit logs are never deleted.

CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND TG_TABLE_NAME <> 'audit_logs'
            AND COALESCE(current_setting('app.store_purge', true), '') = OLD.store_id::text THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Table % is append-only: UPDATE/DELETE are not allowed', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION guard_payment_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF COALESCE(current_setting('app.store_purge', true), '') = OLD.store_id::text THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'Table payments: DELETE is not allowed (cancel the payment instead)';
    END IF;
    IF OLD.voided_at IS NOT NULL THEN
        RAISE EXCEPTION 'Table payments: a cancelled payment cannot be changed';
    END IF;
    IF NEW.id <> OLD.id OR NEW.store_id <> OLD.store_id OR NEW.debt_id <> OLD.debt_id
            OR NEW.recorded_by <> OLD.recorded_by OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Table payments: id, store, debt, recorded_by and created_at cannot be changed';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
