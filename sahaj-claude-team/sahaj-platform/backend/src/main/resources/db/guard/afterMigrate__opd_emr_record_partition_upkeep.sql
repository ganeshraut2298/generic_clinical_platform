-- SAH-53 / DEC-049 item 3 · Partition upkeep for opd.emr_record.
-- FLYWAY CALLBACK, NOT A MIGRATION. Target file (placed by the java-developer, in MAIN code, shipped in every build):
--   backend/src/main/resources/db/guard/afterMigrate__opd_emr_record_partition_upkeep.sql
-- (same location as afterMigrate__platform_dev_tools_absent_guard.sql; application.yml already lists
--  classpath:db/guard, so no configuration change is needed. It cannot live in db/migration: scripts/validate.mjs db
--  accepts only V__ and R__ file names there.) Copy this file unchanged, from the first statement down.
-- Runs as sahaj_migrator after EVERY migrate (dev, test, prod), also when no migration was pending.
-- Does: ensures the current UTC month and the next 12 months of opd.emr_record exist. A month it cannot make is skipped with a
-- warning in the Flyway log; it never fails the migrate. Idempotent. Function: V2026_12_13_1000__opd_emr_record_partition_upkeep.sql.
-- No placeholders, no row data, no tenant data.
do $upkeep$
begin
  if to_regprocedure('opd.emr_record_upkeep_partitions(integer)') is not null then
    perform opd.emr_record_upkeep_partitions(12);
  end if;
end
$upkeep$;
