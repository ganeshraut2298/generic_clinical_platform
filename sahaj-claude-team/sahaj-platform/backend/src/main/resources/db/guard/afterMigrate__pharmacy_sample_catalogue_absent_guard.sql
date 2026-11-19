-- SAH-78 review fix round 1 (security M1) · Pharmacy sample catalogue absent guard.
-- FLYWAY CALLBACK, NOT A MIGRATION. File: backend/src/main/resources/db/guard/afterMigrate__pharmacy_sample_catalogue_absent_guard.sql
-- Flyway location classpath:db/guard (application.yml: spring.flyway.locations: classpath:db/migration,classpath:db/guard).
-- Runs as sahaj_migrator after every migrate, in every build and profile, next to afterMigrate__platform_dev_tools_absent_guard.sql.
--
-- Why: V2026_12_17_1030 seeds 30 FAKE catalogue products (pharmacy.catalogue_product.sample = true, among them a fake
-- "Sample Morphine" with NDPS) only while the Flyway placeholder sahajSampleData is 'true'. That placeholder is false in
-- application.yml and true in application-dev.yml and application-test.yml, but a profile mix-up or an environment variable can
-- turn it on in another database, and once the migration is recorded as applied, changing the setting later does not remove the
-- rows and nothing would warn. This guard is the backstop that does.
--
-- What it does:
--   In sahaj_dev and sahaj_test: nothing.
--   In any other database: raises (so the migrate fails and the application does not start) when pharmacy.catalogue_product
--   holds one or more rows with sample = true. Skipped when the table does not exist yet.
--
-- Never deletes, updates or revokes anything: removing rows from a shared catalogue by an automatic step is worse than a stop
-- the human sees. The fix is by hand, as sahaj_migrator:
--   delete from pharmacy.catalogue_product where sample;
-- (the catalogue is global and select-only for the application; sahaj_migrator has the all-commands policy on it; check first
-- whether a clinic adopted a sample product).
-- Read-only, idempotent, no tenant data, sets no app.tenant_id. The message carries the database name and a row count only.
-- Test: the database name is read once, in the line marked TEST SUBSTITUTION POINT. A test replaces that call with a string
-- literal such as 'sahaj_staging' in the SQL text to exercise the raising path from inside sahaj_test.

do $guard$
declare
  v_db    text := current_database();   -- TEST SUBSTITUTION POINT (the only place the database name is read)
  v_count bigint;
begin
  -- Exact names only. sahaj_dev_staging, sahaj_test_prod, sahaj_dev_1 are NOT dev or test databases.
  if v_db ~ '^sahaj_(dev|test)$' then
    return;
  end if;

  -- to_regclass returns null (no error) when the schema or the table does not exist, so a fresh database passes.
  if to_regclass('pharmacy.catalogue_product') is null then
    return;
  end if;

  select count(*) into v_count from pharmacy.catalogue_product where sample;

  if v_count > 0 then
    raise exception 'Sample catalogue products (sample = true) found in database "%" which is not sahaj_dev or sahaj_test: % row(s). Remove them by hand, then start again (SAH-78 security M1).', v_db, v_count
      using detail  = 'pharmacy.catalogue_product holds fake sample rows; they must never exist in a clinic database.',
            hint    = 'As sahaj_migrator: delete from pharmacy.catalogue_product where sample;  Check first whether a clinic adopted one. Keep the Flyway placeholder sahajSampleData false outside dev and test.',
            errcode = 'P0001';
  end if;
end
$guard$;
