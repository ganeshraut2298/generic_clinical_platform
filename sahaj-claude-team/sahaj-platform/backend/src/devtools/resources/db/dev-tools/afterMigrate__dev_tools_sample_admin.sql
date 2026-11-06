-- ADR-008 / DEC-016 · Dev-only sample platform admin: database part (option W1).
-- FLYWAY CALLBACK, NOT A MIGRATION. Target file (placed by the java-developer):
--   backend/src/devtools/resources/db/dev-tools/afterMigrate__dev_tools_sample_admin.sql
-- It exists only in the dev-tools source set (Maven profile dev-tools). The production jar does not contain it, so a
-- production database never gets the grant or the function. Flyway location classpath:db/dev-tools is added by the
-- dev-tools customizer only when the ADR-008 conditions C1..C5 hold. Runs as sahaj_migrator after every migrate.
--
-- What it does, in this order, in one transaction (Flyway runs a callback script in a transaction):
--   0. Stops with a clear message if anything it needs is missing (role, admin tables, privileges, database name).
--   1. Gives sahaj_admin_definer INSERT on exactly seven columns of platform.platform_admin (and nothing wider).
--   2. Creates platform.admin_dev_create_sample_admin(...), SECURITY DEFINER, owner sahaj_admin_definer.
--   3. EXECUTE for sahaj_app only.
--   4. Self-checks the result and fails the run if it is not exactly as designed.
-- Idempotent: every statement can run again and ends in the same state. Touches no tenant table and no tenant data.
-- No secret is written, logged or raised: error messages never carry a parameter value.
-- Design note: docs/database/ADR-008-dev-sample-admin.md

-- 0. Pre-flight. Raising here fails the Flyway run with a readable message.
do $$
begin
  -- Exact names only (ADR-008 R3, review L1): sahaj_dev_staging, sahaj_test_prod and sahaj_dev_1 are refused.
  if current_database() !~ '^sahaj_(dev|test)$' then
    raise exception 'Dev-tools sample admin refused: database "%" is not a Sahaj dev or test database (sahaj_dev, sahaj_test). This script must never run anywhere else (ADR-008).', current_database();
  end if;

  if not exists (select 1 from pg_roles where rolname = 'sahaj_admin_definer') then
    raise exception 'Role sahaj_admin_definer does not exist. Create it first (DEC-012, ADR-003 D4: NOLOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT, GRANT sahaj_admin_definer TO sahaj_migrator) and re-run scripts/setup/create-database.mjs (DEC-016).';
  end if;
  if exists (select 1 from pg_roles where rolname = 'sahaj_admin_definer'
              and (rolcanlogin or rolsuper or rolbypassrls)) then
    raise exception 'Role sahaj_admin_definer must be NOLOGIN NOSUPERUSER NOBYPASSRLS (ADR-003 D4).';
  end if;
  if not exists (select 1 from pg_roles where rolname = 'sahaj_app') then
    raise exception 'Role sahaj_app does not exist. Run scripts/setup/create-database.mjs first.';
  end if;
  -- member: the grant exists; usage: its privileges are inherited (needed to replace a function the definer owns on a
  -- later run); set: needed for ALTER FUNCTION ... OWNER TO. All three come from GRANT sahaj_admin_definer TO sahaj_migrator.
  if not pg_has_role(current_user, 'sahaj_admin_definer', 'member')
     or not pg_has_role(current_user, 'sahaj_admin_definer', 'usage')
     or not pg_has_role(current_user, 'sahaj_admin_definer', 'set') then
    raise exception 'The migration user must be a member of sahaj_admin_definer with inherit and set: GRANT sahaj_admin_definer TO sahaj_migrator (DEC-012).';
  end if;

  if to_regclass('platform.platform_admin') is null
     or to_regclass('platform.platform_admin_audit') is null then
    raise exception 'Admin tables are missing: activate the parked admin migrations first (DEC-016).';
  end if;
  if to_regprocedure('platform.current_tenant()') is null then
    raise exception 'platform.current_tenant() is missing: run the platform migrations first.';
  end if;
end
$$;

-- 1. Privileges on the admins table. Start from "no INSERT at all" so a wider grant from any earlier state is removed
--    (revoking the table privilege also removes column privileges), then grant exactly seven columns. Every other
--    column (active, password_changed_at, last_sign_in_at, totp_last_used_step, created_*, updated_*, version) takes
--    its default; totp_last_used_step stays null. Nothing else is granted to anyone.
revoke insert on platform.platform_admin from sahaj_admin_definer;
grant insert (id, email, full_name, password_hash, totp_secret_enc, totp_key_version, totp_enrolled_at)
  on platform.platform_admin to sahaj_admin_definer;
-- Already granted by V2026_11_15_1000 and relied on here: SELECT on platform.platform_admin (id, ...) and
-- SELECT, INSERT on platform.platform_admin_audit (append-only; UPDATE, DELETE, TRUNCATE are refused by trigger).

-- 2. The function. ALTER FUNCTION ... OWNER TO needs CREATE on the schema for the new owner: granted here, taken back
--    below (same pattern as V2026_11_15_1010).
grant create on schema platform to sahaj_admin_definer;

create or replace function platform.admin_dev_create_sample_admin(
  p_admin_id        uuid,
  p_password_hash   text,
  p_totp_secret_enc bytea,
  p_totp_key_version smallint,
  p_ip              inet,
  p_request_id      text)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $fn$
declare
  -- The only identity this function can ever create. Constants, never parameters: even an SQL injection as sahaj_app
  -- in a dev database can create only this one admin, once, and only while no admin exists.
  c_email constant text   := 'sample-admin@example.test';
  c_name  constant text   := 'Sample admin';
  -- Fixed advisory lock key (ADR-008, 5 Nov 2026). Serialises concurrent callers; held to the end of the transaction.
  c_lock  constant bigint := 202611050008;
  v_any   uuid;
begin
  -- Admin functions are never available inside a clinic session (ADR-003 D4 rule 4).
  if platform.current_tenant() is not null then
    raise exception 'admin function in a clinic session' using errcode = '42501';
  end if;

  -- Parameter checks. The messages name the problem, never the value.
  if p_admin_id is null then
    raise exception 'admin id required' using errcode = '22023';
  end if;
  if p_password_hash is null
     or p_password_hash !~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$' then
    raise exception 'invalid password hash' using errcode = '22023';
  end if;
  if p_totp_secret_enc is null or octet_length(p_totp_secret_enc) not between 48 and 64 then
    raise exception 'invalid authenticator secret ciphertext' using errcode = '22023';
  end if;
  if p_totp_key_version is null or p_totp_key_version < 1 then
    raise exception 'invalid key version' using errcode = '22023';
  end if;
  if p_request_id is not null and char_length(p_request_id) > 100 then
    raise exception 'invalid request id' using errcode = '22023';
  end if;

  -- One caller at a time. After the lock is granted, the next statement sees every admin committed before it.
  perform pg_advisory_xact_lock(c_lock);

  -- ANY admin (active or not, seeded or not) closes the door.
  select a.id into v_any from platform.platform_admin a limit 1;
  if found then
    insert into platform.platform_admin_audit
      (kind, action, summary, outcome, refusal_code, ip, request_id)
    values
      ('AUTH', 'ADMIN_DEV_SAMPLE_REFUSED',
       'Sample admin not created: an admin already exists',
       'REFUSED', 'admin-exists', p_ip, p_request_id);
    return false;
  end if;

  -- Created already enrolled: secret, key version and enrolment time are set together (table check
  -- ck_platform_admin_totp_all_or_none). No recovery codes. totp_last_used_step stays null.
  insert into platform.platform_admin
    (id, email, full_name, password_hash, totp_secret_enc, totp_key_version, totp_enrolled_at)
  values
    (p_admin_id, c_email, c_name, p_password_hash, p_totp_secret_enc, p_totp_key_version, now());

  -- Same transaction as the insert. The audit row holds no password, hash, secret, ciphertext or URI.
  insert into platform.platform_admin_audit
    (kind, action, admin_id, admin_name, summary, outcome, ip, request_id)
  values
    ('AUTH', 'ADMIN_DEV_SAMPLE_CREATED', p_admin_id, c_name,
     'Sample admin created by the dev-only endpoint', 'APPLIED', p_ip, p_request_id);

  return true;
end
$fn$;

comment on function platform.admin_dev_create_sample_admin(uuid, text, bytea, smallint, inet, text) is
  'DEV AND TEST DATABASES ONLY (ADR-008, DEC-016). Creates the fixed admin sample-admin@example.test, already TOTP-enrolled, when no admin exists; returns true when created, false when an admin exists (audited). Refuses in a clinic session (42501). Created by the dev-tools Flyway callback; never present in production.';

-- Privileges: nobody but the owner and sahaj_app. (Functions default to EXECUTE for PUBLIC, so revoke it first.)
revoke all on function platform.admin_dev_create_sample_admin(uuid, text, bytea, smallint, inet, text) from public;
grant execute on function platform.admin_dev_create_sample_admin(uuid, text, bytea, smallint, inet, text) to sahaj_app;
alter function platform.admin_dev_create_sample_admin(uuid, text, bytea, smallint, inet, text) owner to sahaj_admin_definer;

revoke create on schema platform from sahaj_admin_definer;

-- 3. Self-check. Fails the run (and rolls this callback back, including the temporary CREATE) if the result is not
--    exactly as designed.
do $$
declare
  v_expected text[] := array['id', 'email', 'full_name', 'password_hash', 'totp_secret_enc', 'totp_key_version',
                             'totp_enrolled_at'];
  v_actual   text[];
  v_bad      integer;
  v_fn       oid := to_regprocedure('platform.admin_dev_create_sample_admin(uuid, text, bytea, smallint, inet, text)');
  r          record;
begin
  -- 3a. The definer can INSERT exactly the seven columns, and no table-wide INSERT.
  select array_agg(a.attname::text) into v_actual
    from pg_attribute a
   where a.attrelid = 'platform.platform_admin'::regclass
     and a.attnum > 0 and not a.attisdropped
     and has_column_privilege('sahaj_admin_definer', a.attrelid, a.attnum, 'INSERT');
  if v_actual is null or not (v_actual <@ v_expected and v_expected <@ v_actual) then
    raise exception 'sahaj_admin_definer INSERT columns on platform.platform_admin are not exactly the seven designed columns';
  end if;

  -- 3b. sahaj_app has no privilege at all on any platform_admin* table (ADR-003 D1).
  for r in
    select c.oid, c.relname
      from pg_class c join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'platform' and c.relkind in ('r', 'p') and c.relname like 'platform\_admin%'
  loop
    if has_any_column_privilege('sahaj_app', r.oid, 'SELECT, INSERT, UPDATE, REFERENCES')
       or has_table_privilege('sahaj_app', r.oid, 'DELETE, TRUNCATE, TRIGGER') then
      raise exception 'sahaj_app must have no privilege on platform.% (ADR-003 D1)', r.relname;
    end if;
  end loop;

  -- 3c. The function: owner, SECURITY DEFINER, fixed search_path, EXECUTE for the owner and sahaj_app only.
  if v_fn is null then
    raise exception 'platform.admin_dev_create_sample_admin was not created';
  end if;
  select count(*) into v_bad
    from pg_proc p
   where p.oid = v_fn
     and (pg_get_userbyid(p.proowner) <> 'sahaj_admin_definer'
          or not p.prosecdef
          or p.proconfig is null
          or not exists (select 1 from unnest(p.proconfig) c where c like 'search_path=%pg_catalog%pg_temp%'));
  if v_bad <> 0 then
    raise exception 'platform.admin_dev_create_sample_admin is not owned by sahaj_admin_definer, not SECURITY DEFINER, or has no fixed search_path';
  end if;
  select count(*) into v_bad
    from pg_proc p,
         lateral aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) x
   where p.oid = v_fn
     and x.privilege_type = 'EXECUTE'
     and (x.grantee = 0
          or pg_get_userbyid(x.grantee) not in ('sahaj_admin_definer', 'sahaj_app'));
  if v_bad <> 0 then
    raise exception 'platform.admin_dev_create_sample_admin must be executable by sahaj_app and its owner only';
  end if;
  if not has_function_privilege('sahaj_app', v_fn, 'EXECUTE') then
    raise exception 'sahaj_app cannot execute platform.admin_dev_create_sample_admin';
  end if;

  -- 3d. The temporary CREATE on the schema is gone.
  if has_schema_privilege('sahaj_admin_definer', 'platform', 'CREATE') then
    raise exception 'sahaj_admin_definer must not keep CREATE on schema platform';
  end if;
end
$$;
