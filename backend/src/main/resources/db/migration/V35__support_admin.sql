-- Köməkçi admin: a platform helper who can look at stores, approve / reject payment requests, remind stores
-- and keep notes — but not packages, money reports, settings, users or data deletion.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_platform_role_check;
ALTER TABLE users ADD CONSTRAINT users_platform_role_check CHECK (platform_role IN ('SUPER_ADMIN', 'SUPPORT_ADMIN', 'USER'));
