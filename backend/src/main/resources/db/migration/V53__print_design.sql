-- Çek və barkod etiketi dizaynı per store (was kept only in each browser): {"receipt": {...}, "label": {...}}.
-- Every till and browser of the store prints with the same design; the owner (or TILL_SETTINGS) changes it.
ALTER TABLE stores ADD COLUMN print_design JSONB;
