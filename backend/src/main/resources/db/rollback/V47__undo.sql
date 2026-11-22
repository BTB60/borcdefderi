-- Manual rollback of V47 (with deleting the V47 row from flyway_schema_history). Assistant usage counts are lost.
DROP TABLE IF EXISTS assistant_usage;
