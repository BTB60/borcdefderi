-- Manual rollback of V46 (with deleting the V46 row from flyway_schema_history). All ad stories and their pictures are lost.
DROP TABLE IF EXISTS ad_stories;
