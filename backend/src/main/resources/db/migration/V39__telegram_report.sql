-- Hər axşam sahibə Telegram hesabatı: the owner links a Telegram chat with a one-time code; the day summary
-- goes there every evening at the chosen hour.
ALTER TABLE stores ADD COLUMN telegram_chat_id BIGINT;
ALTER TABLE stores ADD COLUMN telegram_link_code VARCHAR(16);
ALTER TABLE stores ADD COLUMN telegram_report_hour SMALLINT NOT NULL DEFAULT 22 CHECK (telegram_report_hour BETWEEN 0 AND 23);
ALTER TABLE stores ADD COLUMN telegram_last_sent DATE;
CREATE UNIQUE INDEX ux_stores_telegram_code ON stores(telegram_link_code) WHERE telegram_link_code IS NOT NULL;
