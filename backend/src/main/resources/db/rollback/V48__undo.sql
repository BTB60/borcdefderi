-- Manual rollback of V48 (with deleting the V48 row from flyway_schema_history). Deploy the previous backend first:
-- it no longer calls search_norm().
DROP FUNCTION IF EXISTS search_norm(text);
