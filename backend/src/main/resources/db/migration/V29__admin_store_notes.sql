-- Platform admin's own notes about a store ("zəng etdim, ayın 20-də ödəyəcək"), with an optional follow-up date
-- that puts the store on the admin's "Bu gün" list, and free tags (VIP, Problemli, ...).
CREATE TABLE admin_store_notes (
    id           UUID PRIMARY KEY,
    store_id     UUID          NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    body         VARCHAR(1000) NOT NULL,
    follow_up_on DATE,
    done_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   UUID REFERENCES users(id)
);
CREATE INDEX ix_admin_store_notes_store ON admin_store_notes(store_id, created_at DESC);
CREATE INDEX ix_admin_store_notes_follow_up ON admin_store_notes(follow_up_on) WHERE done_at IS NULL AND follow_up_on IS NOT NULL;

ALTER TABLE stores ADD COLUMN admin_tags VARCHAR(300) NOT NULL DEFAULT '';
