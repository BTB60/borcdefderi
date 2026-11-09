-- M10 number shown in the "Abunə ol" window. Only fills it when the SUPER_ADMIN has not set one yet;
-- it stays editable in Admin panel → Parametrlər.
INSERT INTO platform_settings (key, value, updated_at)
VALUES ('m10_account', '0507988177', now())
ON CONFLICT (key) DO UPDATE
    SET value = EXCLUDED.value, updated_at = now()
    WHERE platform_settings.value IS NULL OR btrim(platform_settings.value) = '';
