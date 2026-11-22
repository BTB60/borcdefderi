-- Search text for "contains" matching that does not depend on the database locale: LOWER() under a "C" locale
-- leaves Ə, Ş, Ç, Ğ, Ö, Ü, İ and Cyrillic capitals as they are, so "əli" never found "Əli". The capitals are
-- mapped first, then LOWER() handles ASCII, and ı/i are treated as the same letter (people type either).
-- Used on both sides: search_norm(column) LIKE search_norm(:q).
-- Rollback: db/rollback/V48__undo.sql.
CREATE OR REPLACE FUNCTION search_norm(t text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
AS $$
SELECT translate(
    lower(translate(t,
        'ƏÖÜÇŞĞIİАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ',
        'əöüçşğiiабвгдеёжзийклмнопрстуфхцчшщъыьэюя')),
    'ı', 'i')
$$;
