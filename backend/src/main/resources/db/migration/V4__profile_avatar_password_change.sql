-- Additive only. Profile picture and password-change time (used to revoke older login tokens).
ALTER TABLE users ADD COLUMN avatar BYTEA;
ALTER TABLE users ADD COLUMN avatar_type VARCHAR(30);
ALTER TABLE users ADD COLUMN avatar_updated_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMPTZ;
