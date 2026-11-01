-- SAH-1 · closes security review M3 and code review finding 8.
-- The schema default privileges (scripts/db/schemas.sql) give sahaj_app SELECT, INSERT, UPDATE and DELETE
-- on every table that sahaj_migrator creates in schema platform. That includes the Flyway history table,
-- which Flyway creates as platform.flyway_schema_history (spring.flyway.schemas and default-schema are
-- 'platform' and no table name is configured). The app could therefore rewrite or delete migration records.
-- The app has no reason to touch it, so remove everything. Flyway keeps working: it runs as sahaj_migrator,
-- the owner of the table, and the owner's rights are not affected by this revoke.
-- The history table exists while this script runs (Flyway creates it before the first migration), so the
-- statement is valid on a fresh database. The revoke is idempotent.
revoke all on platform.flyway_schema_history from sahaj_app;
revoke all on platform.flyway_schema_history from public;
