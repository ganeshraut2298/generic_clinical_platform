-- ADR-008 R3 / DEC-016 (security review L2) · Dev-tools absent guard.
-- FLYWAY CALLBACK, NOT A MIGRATION. Target file (placed by the java-developer, in MAIN code, shipped in every build):
--   backend/src/main/resources/db/guard/afterMigrate__platform_dev_tools_absent_guard.sql
-- Flyway location classpath:db/guard (application.yml: spring.flyway.locations: classpath:db/migration,classpath:db/guard).
-- Runs as sahaj_migrator after every migrate, in every build and profile.
--
-- What it does:
--   In sahaj_dev and sahaj_test: nothing.
--   In any other database: raises (so the migrate fails and the application does not start) when ANY of these exists:
--     1. function platform.admin_dev_create_sample_admin(uuid,text,bytea,smallint,inet,text);
--     2. any INSERT privilege (table level or column level) of role sahaj_admin_definer on platform.platform_admin;
--     3. an ACTIVE row in platform.platform_admin with the dev sample email (a database cloned from sahaj_dev).
--   Each check is skipped when its object (role, table, function) does not exist yet, so a fresh database, or one where
--   the parked admin migrations are not active, passes.
--
-- Never drops, revokes or updates anything: a migrate that silently changes privileges or data in a non-dev database is
-- worse than a stop the human sees. The fix is the runbook docs/database/runbooks/dev-tools-sample-admin-cleanup.sql.
-- Read-only, idempotent, platform scope only, no tenant data, sets no app.tenant_id. The messages carry the database name
-- and the kind of finding only: no email, no hash, no secret, no row value.
-- Tests (QA T18): the database name is read once, in the line marked TEST SUBSTITUTION POINT. A test replaces that call with
-- a string literal such as 'sahaj_staging' in the SQL text to exercise the raising paths from inside sahaj_test.
-- Design note: docs/database/ADR-008-dev-sample-admin.md

do $guard$
declare
  v_db      text := current_database();   -- TEST SUBSTITUTION POINT (the only place the database name is read)
  v_found   text[] := array[]::text[];
begin
  -- Exact names only. sahaj_dev_staging, sahaj_test_prod, sahaj_dev_1 are NOT dev or test databases.
  if v_db ~ '^sahaj_(dev|test)$' then
    return;
  end if;

  -- 1. The dev-only function. to_regprocedure returns null (no error) when the schema or the function does not exist.
  if to_regprocedure('platform.admin_dev_create_sample_admin(uuid,text,bytea,smallint,inet,text)') is not null then
    v_found := array_append(v_found, 'the function platform.admin_dev_create_sample_admin exists'::text);
  end if;

  -- 2. INSERT privilege of the definer on the admins table (table-level or any column-level grant).
  -- Nested ifs on purpose: has_any_column_privilege raises when the role or the table is missing, and SQL does not
  -- guarantee left-to-right evaluation of AND inside one expression.
  if exists (select 1 from pg_roles where rolname = 'sahaj_admin_definer')
     and to_regclass('platform.platform_admin') is not null then
    if has_any_column_privilege('sahaj_admin_definer'::name, 'platform.platform_admin'::text, 'INSERT'::text) then
      v_found := array_append(v_found, 'sahaj_admin_definer holds INSERT on platform.platform_admin'::text);
    end if;
  end if;

  -- 3. An active dev sample admin row. Only read when the table exists.
  if to_regclass('platform.platform_admin') is not null then
    if exists (select 1 from platform.platform_admin a
                where a.email = 'sample-admin@example.test' and a.active) then
      v_found := array_append(v_found, 'an active dev sample admin account exists'::text);
    end if;
  end if;

  if array_length(v_found, 1) is not null then
    raise exception 'Dev-tools sample admin objects found in database "%". Run docs/database/runbooks/dev-tools-sample-admin-cleanup.sql as sahaj_migrator, then start again (ADR-008 R3).', v_db
      using detail  = array_to_string(v_found, '; '::text),
            hint    = 'psql -v ON_ERROR_STOP=1 -U sahaj_migrator -d <database> -f docs/database/runbooks/dev-tools-sample-admin-cleanup.sql',
            errcode = 'P0001';
  end if;
end
$guard$;
