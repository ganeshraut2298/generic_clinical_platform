-- ADR-008 R3 / DEC-016 (security review L2) · Runbook: remove the dev-tools sample admin objects from a database.
-- RUN BY A HUMAN, NOT BY FLYWAY. Not a migration, not a callback.
--
--   psql -v ON_ERROR_STOP=1 -U sahaj_migrator -d <database> -f docs/database/runbooks/dev-tools-sample-admin-cleanup.sql
--
-- When: the Flyway guard (afterMigrate__platform_dev_tools_absent_guard) stopped the application with
-- "Dev-tools sample admin objects found in database ...", or you want to switch the dev-tools objects off in sahaj_dev.
--
-- What it does, in ONE transaction (any error rolls everything back; psql with ON_ERROR_STOP stops at the first error):
--   1. Drops platform.admin_dev_create_sample_admin(uuid,text,bytea,smallint,inet,text) if it exists.
--   2. Revokes INSERT (table level, which also removes column-level grants) on platform.platform_admin from
--      sahaj_admin_definer. This restores the ADR-003 D1 state: no INSERT for the definer.
--   3. Only OUTSIDE sahaj_dev / sahaj_test: switches the dev sample admin (sample-admin@example.test) off with
--      active = false (never deletes: retention), ends its open admin sessions (reason ADMIN_OFF), closes its open
--      pre-auth tokens, and writes one append-only audit row (kind ACTION, action ADMIN_DEV_SAMPLE_DEACTIVATED). In
--      sahaj_dev and sahaj_test the sample admin is left alone: it is dev data.
--   4. Checks itself: raises (and rolls back) if the function still exists, the definer still has any INSERT on
--      platform.platform_admin, or (outside dev/test) an active sample admin remains.
--
-- Idempotent: running it again changes nothing and passes. Each step is skipped when its object does not exist.
-- Platform scope only. Touches no tenant table. Does not touch any other admin, any other role or any other privilege.
-- It prints notices with object kinds and the database name only: no email, hash, secret or row value.
-- In sahaj_dev the next dev-tools start recreates the function and the grant (the dev-tools callback).
-- Design note: docs/database/ADR-008-dev-sample-admin.md

-- ON_ERROR_STOP check. psql always defines the variable (value off unless set), so test its VALUE, not whether it is
-- defined. The inner \if is only read when the variable exists; inside an inactive branch psql does not interpolate.
-- psql accepts only valid booleans for ON_ERROR_STOP (on/off/1/0/true/false/yes/no), so \if :ON_ERROR_STOP is safe.
\set sahaj_onerror_ok off
\if :{?ON_ERROR_STOP}
  \if :ON_ERROR_STOP
    \set sahaj_onerror_ok on
  \endif
\endif
\if :sahaj_onerror_ok
\else
  \echo 'Stopped: run this file with psql -v ON_ERROR_STOP=1 so that an error cannot be followed by later steps.'
  \quit
\endif
\unset sahaj_onerror_ok

begin;

-- 0. Pre-flight: the current user must be able to drop the function and change the admins table.
do $pre$
declare
  v_fn_owner oid;
  v_tbl_owner oid;
begin
  select p.proowner into v_fn_owner
    from pg_proc p
   where p.oid = to_regprocedure('platform.admin_dev_create_sample_admin(uuid,text,bytea,smallint,inet,text)');
  if v_fn_owner is not null and not pg_has_role(current_user, v_fn_owner, 'usage') then
    raise exception 'Cannot drop platform.admin_dev_create_sample_admin: run this as sahaj_migrator (member of sahaj_admin_definer with inherit).';
  end if;

  select c.relowner into v_tbl_owner
    from pg_class c
   where c.oid = to_regclass('platform.platform_admin');
  if v_tbl_owner is not null and not pg_has_role(current_user, v_tbl_owner, 'usage') then
    raise exception 'Cannot change platform.platform_admin: run this as sahaj_migrator (the table owner).';
  end if;
end
$pre$;

-- 1. The function. Harmless when the function or the schema does not exist.
drop function if exists platform.admin_dev_create_sample_admin(uuid, text, bytea, smallint, inet, text);

-- 2. The grant. Only when the role and the table exist.
do $revoke$
begin
  if exists (select 1 from pg_roles where rolname = 'sahaj_admin_definer')
     and to_regclass('platform.platform_admin') is not null then
    revoke insert on platform.platform_admin from sahaj_admin_definer;
  end if;
end
$revoke$;

-- 3. The sample admin account, outside dev/test databases only.
do $account$
declare
  v_db      text := current_database();
  v_admin   uuid;
  v_name    text;
  v_ended   integer := 0;
  v_tokens  integer := 0;
begin
  if v_db ~ '^sahaj_(dev|test)$' then
    raise notice 'Database "%" is a dev or test database: the sample admin account is left as it is.', v_db;
    return;
  end if;
  if to_regclass('platform.platform_admin') is null then
    raise notice 'No platform.platform_admin table in database "%": nothing to switch off.', v_db;
    return;
  end if;

  -- Never deletes. Only an active row changes, so a second run finds nothing and writes no second audit row.
  update platform.platform_admin a
     set active = false,
         updated_at = now(),
         version = a.version + 1
   where a.email = 'sample-admin@example.test'
     and a.active
  returning a.id, a.full_name into v_admin, v_name;

  if v_admin is null then
    raise notice 'No active sample admin account in database "%".', v_db;
    return;
  end if;

  -- End its open sessions. revoked_at and revoked_reason are set together (table check); ADMIN_OFF is the existing
  -- reason for "the admin was switched off".
  if to_regclass('platform.platform_admin_session') is not null then
    update platform.platform_admin_session s
       set revoked_at = now(), revoked_reason = 'ADMIN_OFF'
     where s.admin_id = v_admin and s.revoked_at is null;
    get diagnostics v_ended = row_count;
  end if;

  -- Close its open pre-auth tokens (5 minute, single use).
  if to_regclass('platform.platform_admin_preauth') is not null then
    update platform.platform_admin_preauth p
       set consumed_at = now()
     where p.admin_id = v_admin and p.consumed_at is null;
    get diagnostics v_tokens = row_count;
  end if;

  -- One append-only audit row. The action column is free text (1 to 60 characters, no list check), so the new code
  -- ADMIN_DEV_SAMPLE_DEACTIVATED needs no migration. No ip, no request id: this is a human at a console.
  if to_regclass('platform.platform_admin_audit') is not null then
    insert into platform.platform_admin_audit
      (kind, action, admin_id, admin_name, summary, outcome)
    values
      ('ACTION', 'ADMIN_DEV_SAMPLE_DEACTIVATED', v_admin, v_name,
       'Sample admin switched off by the dev-tools cleanup script', 'APPLIED');
  end if;

  raise notice 'Sample admin account switched off in database "%" (% session(s) ended, % pre-auth token(s) closed).',
    v_db, v_ended, v_tokens;
end
$account$;

-- 4. Self-check. Raising rolls the whole run back.
do $check$
declare
  v_db text := current_database();
begin
  if to_regprocedure('platform.admin_dev_create_sample_admin(uuid,text,bytea,smallint,inet,text)') is not null then
    raise exception 'Cleanup failed: platform.admin_dev_create_sample_admin still exists.';
  end if;

  -- Each table test is its own if: PL/pgSQL plans a statement or expression only when it runs, so a reference to a
  -- missing table or role is never planned when the guarding if is false. One AND expression would be planned whole.
  if to_regclass('platform.platform_admin') is not null then
    if exists (select 1 from pg_roles where rolname = 'sahaj_admin_definer') then
      if has_any_column_privilege('sahaj_admin_definer'::name, 'platform.platform_admin'::text, 'INSERT'::text) then
        raise exception 'Cleanup failed: sahaj_admin_definer still has INSERT on platform.platform_admin (granted by another role? list the table privileges with psql describe-privileges on platform.platform_admin).';
      end if;
    end if;

    if v_db !~ '^sahaj_(dev|test)$' then
      if exists (select 1 from platform.platform_admin a
                  where a.email = 'sample-admin@example.test' and a.active) then
        raise exception 'Cleanup failed: an active sample admin account remains in database "%".', v_db;
      end if;
    end if;
  end if;

  raise notice 'Cleanup check passed for database "%".', v_db;
end
$check$;

commit;
