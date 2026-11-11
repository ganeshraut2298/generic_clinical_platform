-- SAH-42 / ADR-018 (Revision 3) / DEC-034 · Dev-only bootstrap registration of the first platform admin: database part.
-- STATUS: written by reading only. NOT YET EXECUTED; the first run is the QA gate (the role sahaj_admin_definer and the
-- admin tables do not exist in any database yet). If the first run fails, fix forward.
--
-- FLYWAY CALLBACK, NOT A MIGRATION. Target file (placed byte for byte by the java-developer):
--   backend/src/devtools/resources/db/bootstrap-dev-tools/afterMigrate__dev_tools_admin_bootstrap.sql
-- Flyway location classpath:db/bootstrap-dev-tools is added by the dev-tools customizer only when the ADR-018 conditions
-- B1..B7 hold. It is a SEPARATE location from ADR-008's db/dev-tools so that each feature's callback runs only when its
-- own switch is on, and the two folder names share no prefix (ADR-018 D1 "Folder name rule", code review finding 7).
-- The old folder name db/dev-tools-bootstrap must not exist in any build. The production jar does not contain this file.
-- Runs as sahaj_migrator after every migrate.
--
-- What it does, in this order, in one transaction (Flyway runs a callback script in a transaction):
--   0. Stops with a clear message if anything it needs is missing (database name, roles, admin tables and functions).
--   1. Creates platform.platform_admin_bootstrap_state (one row; wrong-PIN count, lock end, open ticket) if it is missing,
--      then adds the two lease columns try_id and try_expires_at and their check if they are missing (Revision 3).
--      Never drops it, never resets it, never deletes from it: the count and the lock survive every restart and re-run.
--   2. Table privileges: nothing for sahaj_app and PUBLIC; SELECT, INSERT, UPDATE for sahaj_admin_definer.
--   3. INSERT (id, email, full_name, password_hash) on platform.platform_admin for sahaj_admin_definer. Additive only:
--      it does NOT revoke anything, because ADR-008's callback owns a seven-column grant on the same table and the two
--      callbacks must not undo each other (see the design note).
--   4. Drops the Revision 2 shapes of the functions if they are present, then creates the five SECURITY DEFINER functions
--      owned by sahaj_admin_definer: admin_bootstrap_begin, _unlock_failed, _unlock_succeeded, _ticket_status, _register.
--      EXECUTE for sahaj_app only.
--   5. Self-checks the result and fails the run (rolling this callback back) if it is not exactly as designed.
-- Idempotent: every statement can run again and ends in the same state; a re-run with the new shapes drops nothing.
-- Touches no tenant table and no tenant data and sets no app.tenant_id. No secret is written, logged or raised: error
-- messages never carry a parameter value. No PIN value or PIN hash exists in this file or in any database object.
-- Constants live inside the function bodies (never parameters): 5 tries, 60 minutes lock, 5 minutes ticket, 30 seconds
-- try lease, 10 seconds lock wait. The database clock (clock_timestamp()) is the only clock.
-- Count first, judge after (ADR-018 D4): admin_bootstrap_begin counts a try and hands out the lease; the caller COMMITS
-- that transaction, THEN compares the PIN outside any transaction, THEN calls unlock_failed or unlock_succeeded in a second
-- transaction. The judge functions never change the count of a wrong try; nothing that fails after the claim can give the
-- try back.
-- Design note: docs/database/SAH-42.md

-- 0. Pre-flight. Raising here fails the Flyway run with a readable message.
do $pre$
begin
  -- Exact names only (same rule as ADR-008 R3): sahaj_dev_staging, sahaj_test_prod, sahaj_dev_1 are refused.
  if current_database() !~ '^sahaj_(dev|test)$' then
    raise exception 'Dev-tools admin bootstrap refused: database "%" is not a Sahaj dev or test database (sahaj_dev, sahaj_test). This script must never run anywhere else (ADR-018).', current_database();
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
  -- member: the grant exists; usage: its privileges are inherited (needed to replace or drop a function the definer already
  -- owns on a later run); set: needed for ALTER FUNCTION ... OWNER TO. All three come from GRANT sahaj_admin_definer TO sahaj_migrator.
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
  -- V2026_11_15_1010 counts the admin_% functions once when it runs: this callback must therefore never be the first
  -- thing to run after V2026_11_15_1000. This check for admin_preauth_create is that ordering guard, and the owner's
  -- first sign-in needs the function (register itself no longer calls it).
  if to_regprocedure('platform.admin_preauth_create(uuid,text,text,text,inet)') is null then
    raise exception 'Admin sign-in functions are missing: activate V2026_11_15_1010 first (DEC-016).';
  end if;
end
$pre$;

-- 1. The state table. Platform scope: no tenant_id, no row level security; privileges are the control (as every
--    platform_admin* table, ADR-003 D1). Exactly one row (id = 1), created by admin_bootstrap_begin on first use.
--    This statement keeps the Revision 2 shape (seven columns, eight constraints) so that a table created by an earlier run
--    and a table created now go through the same ALTER below and end identical.
create table if not exists platform.platform_admin_bootstrap_state (
  id                smallint    not null
                    constraint pk_platform_admin_bootstrap_state primary key
                    constraint ck_platform_admin_bootstrap_state_id check (id = 1),
  failures          smallint    not null default 0
                    constraint ck_platform_admin_bootstrap_state_failures check (failures between 0 and 5),
  locked_until      timestamptz,
  ticket_hash       text
                    constraint ck_platform_admin_bootstrap_state_ticket_hash check (ticket_hash ~ '^[0-9a-f]{64}$'),
  binding_hash      text
                    constraint ck_platform_admin_bootstrap_state_binding_hash check (binding_hash ~ '^[0-9a-f]{64}$'),
  ticket_expires_at timestamptz,
  updated_at        timestamptz not null default now(),
  -- The open ticket is three columns that are all set or all null.
  constraint ck_platform_admin_bootstrap_state_ticket_all_or_none check (
    (ticket_hash is null) = (binding_hash is null)
    and (ticket_hash is null) = (ticket_expires_at is null)),
  -- A lock exists exactly when the count has reached 5 (a lock that has run out is cleared by the next function that
  -- touches the row, never left half set).
  constraint ck_platform_admin_bootstrap_state_lock_matches_failures check (
    (failures = 5) = (locked_until is not null)),
  -- A lock cancels the open ticket (BR7e).
  constraint ck_platform_admin_bootstrap_state_no_ticket_while_locked check (
    locked_until is null or ticket_hash is null)
);

-- 1b. Revision 3: the try lease (ADR-018 D4, "Data needed" 1). try_id is the try that was counted and is being judged;
--     try_expires_at is the end of its 30 seconds. Both set or both null. Added to an existing table without touching
--     its rows: an existing count and lock stay as they are and the new columns start null. A lease may coexist with a
--     lock (the claim of the 5th try sets both). Column order afterwards: id, failures, locked_until, ticket_hash,
--     binding_hash, ticket_expires_at, updated_at, try_id, try_expires_at.
alter table platform.platform_admin_bootstrap_state
  add column if not exists try_id         uuid,
  add column if not exists try_expires_at timestamptz;

do $try$
begin
  if not exists (select 1
                   from pg_constraint k
                  where k.conrelid = 'platform.platform_admin_bootstrap_state'::regclass
                    and k.conname = 'ck_platform_admin_bootstrap_state_try_both_or_none') then
    alter table platform.platform_admin_bootstrap_state
      add constraint ck_platform_admin_bootstrap_state_try_both_or_none
      check ((try_id is null) = (try_expires_at is null));
  end if;
end
$try$;

comment on table platform.platform_admin_bootstrap_state is
  'DEV AND TEST DATABASES ONLY (ADR-018, SAH-42). One row: global wrong-PIN count (0 to 5), the 60-minute lock end, the single open bootstrap ticket (HMAC-SHA256 hex only) and the 30-second lease of the one try being judged (try_id, try_expires_at). No tenant_id, no RLS; sahaj_app has no privilege. Written only by the admin_bootstrap_* functions and the human clear-lock runbook. Never deleted by any callback.';

-- 2. Table privileges. The schema's default privileges give sahaj_app all four commands on a new table, so take everything
--    away from sahaj_app and PUBLIC on every run. The definer starts from nothing and gets exactly three commands
--    (no DELETE: there is no delete path).
revoke all on platform.platform_admin_bootstrap_state from sahaj_app, public;
revoke all on platform.platform_admin_bootstrap_state from sahaj_admin_definer;
grant select, insert, update on platform.platform_admin_bootstrap_state to sahaj_admin_definer;

-- 3. The first admin is inserted by admin_bootstrap_register, running as the definer. Four columns only: active (true),
--    password_changed_at, last_sign_in_at, created_*, updated_*, version take their defaults and the TOTP columns stay null
--    (the admin is created NOT enrolled). Deliberately no REVOKE here: ADR-008's callback grants seven columns on the same
--    table (these four plus three TOTP columns) and revokes first; whichever callback runs last, the result is a set of
--    columns that contains these four and is contained in those seven. The self-check below tests exactly that.
grant insert (id, email, full_name, password_hash) on platform.platform_admin to sahaj_admin_definer;
-- Already granted by V2026_11_15_1000 and relied on here: SELECT on platform.platform_admin (id, ...) and
-- SELECT, INSERT on platform.platform_admin_audit (append-only; UPDATE, DELETE, TRUNCATE are refused by trigger).

-- 4. The functions. ALTER FUNCTION ... OWNER TO needs CREATE on the schema for the new owner: granted here, taken back
--    below (same pattern as V2026_11_15_1010 and ADR-008).
grant create on schema platform to sahaj_admin_definer;

-- 4.0 Revision 3: remove the Revision 2 shapes. CREATE OR REPLACE cannot change arguments or a result type, so the old
--     shapes must go first. Three old shapes have argument lists that the new shapes do not share: plain drops. The other
--     two (ticket_status, register) keep their argument lists and are dropped only while their result type is still text,
--     so a re-run with the new shapes drops nothing. No cascade: nothing depends on these functions. The statements inside
--     the DO block are planned only when their branch runs.
drop function if exists platform.admin_bootstrap_begin();
drop function if exists platform.admin_bootstrap_unlock_failed(inet, text);
drop function if exists platform.admin_bootstrap_unlock_succeeded(text, text);

do $drop$
begin
  if exists (select 1
               from pg_proc p
              where p.oid = to_regprocedure('platform.admin_bootstrap_ticket_status(text,text)')
                and p.prorettype = 'text'::regtype) then
    drop function platform.admin_bootstrap_ticket_status(text, text);
  end if;
  if exists (select 1
               from pg_proc p
              where p.oid = to_regprocedure('platform.admin_bootstrap_register(text,text,uuid,text,text,text,inet,text)')
                and p.prorettype = 'text'::regtype) then
    drop function platform.admin_bootstrap_register(text, text, uuid, text, text, text, inet, text);
  end if;
end
$drop$;

-- 4.1 begin: THE CLAIM. First call of every unlock whose body is readable and whose PIN is not blank. It COUNTS the try
--     before the PIN is compared; the caller commits this transaction before it compares (ADR-018 D4). Locks the state
--     row (waiting at most 10 seconds), creates it on first use, and in this order: clears a lock that has run out, clears
--     a lease that has run out (one audit row, the try was counted but never judged), answers LOCKED while a lock is in
--     force, answers BUSY while a live lease exists, otherwise adds 1 to the count and hands the 30-second lease to
--     p_try_id (the 5th try also sets the 60-minute lock and cancels the ticket). Never called by register or by a
--     blank-PIN peek (those use ticket_status). Writes nothing once an admin exists (it returns before touching the row).
--     Outcome: CLOSED | LOCKED | BUSY | COUNTED. locked_until is set with LOCKED and with COUNTED on the 5th try only.
create or replace function platform.admin_bootstrap_begin(p_try_id uuid)
returns table (outcome text, failures smallint, locked_until timestamptz)
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
set lock_timeout = '10s'
as $fn$
#variable_conflict use_column
declare
  c_max_tries  constant smallint := 5;
  c_lock_for   constant interval := interval '60 minutes';
  c_lease_for  constant interval := interval '30 seconds';
  v_failures   smallint;
  v_locked     timestamptz;
  v_try        uuid;
  v_try_ends   timestamptz;
  v_now        timestamptz;
begin
  -- Admin functions are never available inside a clinic session (ADR-003 D4 rule 4).
  if platform.current_tenant() is not null then
    raise exception 'admin function in a clinic session' using errcode = '42501';
  end if;
  if p_try_id is null then
    raise exception 'try id required' using errcode = '22023';
  end if;

  -- Closed for good once ANY admin exists (active or not, however it was made). Answered without touching the row.
  if exists (select a.id from platform.platform_admin a) then
    return query select 'CLOSED'::text, 0::smallint, null::timestamptz;
    return;
  end if;

  insert into platform.platform_admin_bootstrap_state (id) values (1) on conflict (id) do nothing;

  -- The row lock serialises the claims; it is held only until the caller's commit, never during the BCrypt compare.
  -- A wait longer than lock_timeout (10 s) raises 55P03.
  select s.failures, s.locked_until, s.try_id, s.try_expires_at
    into v_failures, v_locked, v_try, v_try_ends
    from platform.platform_admin_bootstrap_state s
   where s.id = 1
     for update;
  if not found then
    raise exception 'bootstrap state row missing' using errcode = 'SB003';
  end if;

  v_now := clock_timestamp();

  -- Re-check after the row lock: a register that finished first has committed its admin by now (READ COMMITTED).
  if exists (select a.id from platform.platform_admin a) then
    return query select 'CLOSED'::text, 0::smallint, null::timestamptz;
    return;
  end if;

  -- (1) A lock that has run out is cleared here: the count starts again at zero (BR7d).
  if v_locked is not null and v_locked <= v_now then
    update platform.platform_admin_bootstrap_state s
       set failures = 0, locked_until = null, updated_at = v_now
     where s.id = 1;
    v_failures := 0;
    v_locked   := null;
  end if;

  -- (2) A lease that has run out belongs to a try that was counted and never judged (its request died or its judge
  --     rolled back). The count stays; the lease is cleared and the try is recorded. ip and request_id stay null: they
  --     belong to the lost request, which is not stored. No PIN, no hash, no try id in the row.
  if v_try is not null and v_try_ends <= v_now then
    update platform.platform_admin_bootstrap_state s
       set try_id = null, try_expires_at = null, updated_at = v_now
     where s.id = 1;
    insert into platform.platform_admin_audit
      (occurred_at, kind, action, summary, outcome, refusal_code)
    values
      (v_now, 'AUTH', 'ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED',
       'Bootstrap registration: a counted try ended without a result',
       'REFUSED', 'try-unjudged');
    v_try      := null;
    v_try_ends := null;
  end if;

  -- (3) A lock in force: nothing is counted and no compare may run.
  if v_locked is not null then
    return query select 'LOCKED'::text, v_failures, v_locked;
    return;
  end if;

  -- (4) Another try holds a live lease: tries are judged one at a time. Java waits and asks again.
  if v_try is not null then
    return query select 'BUSY'::text, v_failures, null::timestamptz;
    return;
  end if;

  -- (5) The claim: one count slot, the lease, and on the 5th try the lock, all in one UPDATE.
  v_failures := v_failures + 1;
  if v_failures >= c_max_tries then
    v_failures := c_max_tries;
    v_locked   := v_now + c_lock_for;
    update platform.platform_admin_bootstrap_state s
       set failures = v_failures, locked_until = v_locked,
           ticket_hash = null, binding_hash = null, ticket_expires_at = null,
           try_id = p_try_id, try_expires_at = v_now + c_lease_for,
           updated_at = v_now
     where s.id = 1;
  else
    update platform.platform_admin_bootstrap_state s
       set failures = v_failures,
           try_id = p_try_id, try_expires_at = v_now + c_lease_for,
           updated_at = v_now
     where s.id = 1;
  end if;

  return query select 'COUNTED'::text, v_failures, v_locked;
end
$fn$;

-- 4.2 unlock_failed: THE JUDGE, wrong PIN. Call it in a second transaction, after the compare, with the p_try_id that
--     begin counted. It NEVER changes the count (the claim did). It checks that p_try_id holds a live lease, clears the
--     lease, writes ADMIN_BOOTSTRAP_UNLOCK_FAILED ("wrong PIN (n of 5)") and, when the row is locked (this try was the
--     5th), ADMIN_BOOTSTRAP_LOCKED. The caller must COMMIT. Raises SB001 closed, SB003 no row, SB004 try not open.
create or replace function platform.admin_bootstrap_unlock_failed(p_try_id uuid, p_ip inet, p_request_id text)
returns table (failures smallint, locked_until timestamptz)
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
set lock_timeout = '10s'
as $fn$
#variable_conflict use_column
declare
  v_failures smallint;
  v_locked   timestamptz;
  v_try      uuid;
  v_try_ends timestamptz;
  v_now      timestamptz;
begin
  if platform.current_tenant() is not null then
    raise exception 'admin function in a clinic session' using errcode = '42501';
  end if;
  if p_try_id is null then
    raise exception 'try id required' using errcode = '22023';
  end if;
  if p_request_id is not null and char_length(p_request_id) > 100 then
    raise exception 'invalid request id' using errcode = '22023';
  end if;

  select s.failures, s.locked_until, s.try_id, s.try_expires_at
    into v_failures, v_locked, v_try, v_try_ends
    from platform.platform_admin_bootstrap_state s
   where s.id = 1
     for update;
  if not found then
    raise exception 'bootstrap state row missing' using errcode = 'SB003';
  end if;

  v_now := clock_timestamp();

  -- Closed beats the lease check (same precedence as the other functions).
  if exists (select a.id from platform.platform_admin a) then
    raise exception 'bootstrap registration is closed' using errcode = 'SB001';
  end if;
  -- Only the try that holds a live lease may be judged: no lease, another try's lease, or a lease that ran out.
  if v_try is null or v_try <> p_try_id or v_try_ends <= v_now then
    raise exception 'bootstrap try is not open' using errcode = 'SB004';
  end if;

  -- The count is NOT touched. The lease is given back so the next try can start.
  update platform.platform_admin_bootstrap_state s
     set try_id = null, try_expires_at = null, updated_at = v_now
   where s.id = 1;

  -- Audit rows: no PIN, no typed text, no hash, no ticket, no try id. The summary holds the count only.
  insert into platform.platform_admin_audit
    (occurred_at, kind, action, summary, outcome, refusal_code, ip, request_id)
  values
    (v_now, 'AUTH', 'ADMIN_BOOTSTRAP_UNLOCK_FAILED',
     'Bootstrap registration: wrong PIN (' || v_failures::text || ' of 5)',
     'REFUSED', 'pin-invalid', p_ip, p_request_id);

  -- locked_until is set only by the claim of the 5th try, and no other lock can coexist with a live lease.
  if v_locked is not null then
    insert into platform.platform_admin_audit
      (occurred_at, kind, action, summary, outcome, refusal_code, after_json, ip, request_id)
    values
      (v_now, 'AUTH', 'ADMIN_BOOTSTRAP_LOCKED',
       'Bootstrap registration locked for 60 minutes',
       'REFUSED', 'locked',
       jsonb_build_object('lockedUntil', to_char(v_locked at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')),
       p_ip, p_request_id);
  end if;

  return query select v_failures, v_locked;
end
$fn$;

-- 4.3 unlock_succeeded: THE JUDGE, correct PIN. Call it in a second transaction, after the compare, with the p_try_id that
--     begin counted. Checks the live lease, then in one UPDATE: count back to 0 (BR7c), locked_until = null (lifts only the
--     lock that this try's own claim set when it was the 5th try: no other lock can coexist with a live lease, because
--     begin refuses to count while locked), replaces any earlier ticket (one slot) and starts its 5 minutes, gives the
--     lease back. Not audited (OQ-42-9). The two hashes are the HMAC-SHA256 hex of the ticket and of the binding cookie
--     value; the plain values never reach the database. Raises SB001, SB003, SB004 as unlock_failed.
create or replace function platform.admin_bootstrap_unlock_succeeded(p_try_id uuid, p_ticket_hash text, p_binding_hash text)
returns timestamptz
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
set lock_timeout = '10s'
as $fn$
declare
  c_ticket_for constant interval := interval '5 minutes';
  v_try        uuid;
  v_try_ends   timestamptz;
  v_now        timestamptz;
  v_expires    timestamptz;
begin
  if platform.current_tenant() is not null then
    raise exception 'admin function in a clinic session' using errcode = '42501';
  end if;
  if p_try_id is null then
    raise exception 'try id required' using errcode = '22023';
  end if;
  if p_ticket_hash is null or p_ticket_hash !~ '^[0-9a-f]{64}$'
     or p_binding_hash is null or p_binding_hash !~ '^[0-9a-f]{64}$' then
    raise exception 'invalid hash' using errcode = '22023';
  end if;

  select s.try_id, s.try_expires_at
    into v_try, v_try_ends
    from platform.platform_admin_bootstrap_state s
   where s.id = 1
     for update;
  if not found then
    raise exception 'bootstrap state row missing' using errcode = 'SB003';
  end if;

  v_now := clock_timestamp();

  if exists (select a.id from platform.platform_admin a) then
    raise exception 'bootstrap registration is closed' using errcode = 'SB001';
  end if;
  if v_try is null or v_try <> p_try_id or v_try_ends <= v_now then
    raise exception 'bootstrap try is not open' using errcode = 'SB004';
  end if;

  v_expires := v_now + c_ticket_for;

  update platform.platform_admin_bootstrap_state s
     set failures = 0, locked_until = null,
         ticket_hash = p_ticket_hash, binding_hash = p_binding_hash, ticket_expires_at = v_expires,
         try_id = null, try_expires_at = null,
         updated_at = v_now
   where s.id = 1;

  return v_expires;
end
$fn$;

-- 4.4 ticket_status: read only. status CLOSED | LOCKED | INVALID | VALID, in that order of precedence; locked_until is set
--     only with LOCKED. A null or malformed argument is INVALID, never an error. Takes no row lock, so it never waits
--     behind a claim or a judge, and ignores the lease. Java also calls ticket_status(null, null) as the "peek" that
--     answers a blank PIN or an unreadable unlock body without counting a try (ADR-018 D4 step 2).
create or replace function platform.admin_bootstrap_ticket_status(p_ticket_hash text, p_binding_hash text)
returns table (status text, locked_until timestamptz)
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $fn$
#variable_conflict use_column
declare
  v_locked  timestamptz;
  v_ticket  text;
  v_binding text;
  v_expires timestamptz;
  v_now     timestamptz;
begin
  if platform.current_tenant() is not null then
    raise exception 'admin function in a clinic session' using errcode = '42501';
  end if;

  if exists (select a.id from platform.platform_admin a) then
    return query select 'CLOSED'::text, null::timestamptz;
    return;
  end if;

  v_now := clock_timestamp();

  select s.locked_until, s.ticket_hash, s.binding_hash, s.ticket_expires_at
    into v_locked, v_ticket, v_binding, v_expires
    from platform.platform_admin_bootstrap_state s
   where s.id = 1;
  if not found then
    -- nobody has ever unlocked
    return query select 'INVALID'::text, null::timestamptz;
    return;
  end if;

  if v_locked is not null and v_locked > v_now then
    return query select 'LOCKED'::text, v_locked;
    return;
  end if;

  if p_ticket_hash is null or p_ticket_hash !~ '^[0-9a-f]{64}$'
     or p_binding_hash is null or p_binding_hash !~ '^[0-9a-f]{64}$'
     or v_ticket is null then
    return query select 'INVALID'::text, null::timestamptz;
    return;
  end if;

  -- Both halves must match, and the 5 minutes (counted from the unlock) must not be over.
  if v_ticket = p_ticket_hash and v_binding = p_binding_hash and v_expires > v_now then
    return query select 'VALID'::text, null::timestamptz;
    return;
  end if;

  return query select 'INVALID'::text, null::timestamptz;
end
$fn$;

-- 4.5 register: creates the FIRST admin (not enrolled, active, no tenant) and spends the ticket. Takes the same
--     transaction advisory lock as ADR-008's admin_dev_create_sample_admin (the two creation paths serialise), THEN the
--     state row lock (lock order: advisory first, row second, everywhere). Both waits are bounded by lock_timeout 10 s
--     (55P03). Ignores the lease (a register may run while a try is being judged) and never counts a try. Returns
--     outcome CREATED | CLOSED | LOCKED | INVALID; locked_until is set only with LOCKED, so Java needs no second call.
--     Email and full name go only into platform_admin; the audit row holds the admin id and name, never the email.
--     Java validates the form (AdminProvisioning) first; the checks here repeat the table rules and raise 22023 without
--     echoing a value.
create or replace function platform.admin_bootstrap_register(
  p_ticket_hash   text,
  p_binding_hash  text,
  p_admin_id      uuid,
  p_email         text,
  p_full_name     text,
  p_password_hash text,
  p_ip            inet,
  p_request_id    text)
returns table (outcome text, locked_until timestamptz)
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
set lock_timeout = '10s'
as $fn$
#variable_conflict use_column
declare
  -- The SAME fixed key as platform.admin_dev_create_sample_admin (ADR-008, 5 Nov 2026).
  c_lock    constant bigint := 202611050008;
  v_has_row boolean;
  v_locked  timestamptz;
  v_ticket  text;
  v_binding text;
  v_expires timestamptz;
  v_now     timestamptz;
  v_con     text;
begin
  if platform.current_tenant() is not null then
    raise exception 'admin function in a clinic session' using errcode = '42501';
  end if;

  -- Parameter checks (the table's own rules). Messages name the problem, never the value.
  if p_admin_id is null then
    raise exception 'admin id required' using errcode = '22023';
  end if;
  if p_email is null
     or p_email <> lower(btrim(p_email))
     or char_length(p_email) not between 3 and 254
     or p_email !~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' then
    raise exception 'invalid email' using errcode = '22023';
  end if;
  if p_full_name is null
     or p_full_name <> btrim(p_full_name)
     or char_length(p_full_name) not between 1 and 120 then
    raise exception 'invalid full name' using errcode = '22023';
  end if;
  if p_password_hash is null
     or p_password_hash !~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$' then
    raise exception 'invalid password hash' using errcode = '22023';
  end if;
  if p_request_id is not null and char_length(p_request_id) > 100 then
    raise exception 'invalid request id' using errcode = '22023';
  end if;

  -- Lock order: 1. advisory lock, 2. state row. A wait longer than 10 s raises 55P03.
  perform pg_advisory_xact_lock(c_lock);

  select s.locked_until, s.ticket_hash, s.binding_hash, s.ticket_expires_at
    into v_locked, v_ticket, v_binding, v_expires
    from platform.platform_admin_bootstrap_state s
   where s.id = 1
     for update;
  v_has_row := found;

  v_now := clock_timestamp();

  -- Precedence as ticket_status: CLOSED, LOCKED, INVALID. Nothing is written on any of these three.
  if exists (select a.id from platform.platform_admin a) then
    return query select 'CLOSED'::text, null::timestamptz;
    return;
  end if;
  if not v_has_row then
    return query select 'INVALID'::text, null::timestamptz;
    return;
  end if;
  if v_locked is not null and v_locked > v_now then
    return query select 'LOCKED'::text, v_locked;
    return;
  end if;
  if p_ticket_hash is null or p_ticket_hash !~ '^[0-9a-f]{64}$'
     or p_binding_hash is null or p_binding_hash !~ '^[0-9a-f]{64}$'
     or v_ticket is null
     or v_ticket <> p_ticket_hash or v_binding <> p_binding_hash
     or v_expires <= v_now then
    return query select 'INVALID'::text, null::timestamptz;
    return;
  end if;

  -- Not enrolled: the TOTP columns stay null (ck_platform_admin_totp_all_or_none). The inner block is a savepoint, so a
  -- unique violation undoes only this insert; both locks stay held.
  begin
    insert into platform.platform_admin (id, email, full_name, password_hash)
    values (p_admin_id, p_email, p_full_name, p_password_hash);
  exception when unique_violation then
    get stacked diagnostics v_con = constraint_name;
    if v_con = 'uq_platform_admin_email' then
      -- an admin with this email exists, so an admin exists
      return query select 'CLOSED'::text, null::timestamptz;
      return;
    end if;
    raise;
  end;

  -- Spend the ticket. The count is already 0 (the unlock that issued the ticket reset it). The lease is not touched.
  update platform.platform_admin_bootstrap_state s
     set ticket_hash = null, binding_hash = null, ticket_expires_at = null, updated_at = v_now
   where s.id = 1;

  insert into platform.platform_admin_audit
    (occurred_at, kind, action, admin_id, admin_name, summary, outcome, ip, request_id)
  values
    (v_now, 'AUTH', 'ADMIN_BOOTSTRAP_ADMIN_CREATED', p_admin_id, p_full_name,
     'First admin created through bootstrap registration', 'APPLIED', p_ip, p_request_id);

  return query select 'CREATED'::text, null::timestamptz;
end
$fn$;

comment on function platform.admin_bootstrap_begin(uuid) is
  'DEV AND TEST DATABASES ONLY (ADR-018 Revision 3, SAH-42). The claim: counts one try and COMMITS it before the PIN is compared. Locks the single bootstrap state row (10 s wait), clears a lock or a lease that has run out (audit ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED), returns (outcome CLOSED|LOCKED|BUSY|COUNTED, failures, locked_until) and hands a 30-second lease to the try. Writes nothing once an admin exists. Created by the bootstrap-dev-tools Flyway callback; never present in production.';
comment on function platform.admin_bootstrap_unlock_failed(uuid, inet, text) is
  'DEV AND TEST DATABASES ONLY (ADR-018 Revision 3, SAH-42). Judge, wrong PIN: audit only (UNLOCK_FAILED, and LOCKED on the 5th try); never changes the count. The try must hold a live lease. Raises SB001 closed, SB003 no row, SB004 try not open.';
comment on function platform.admin_bootstrap_unlock_succeeded(uuid, text, text) is
  'DEV AND TEST DATABASES ONLY (ADR-018 Revision 3, SAH-42). Judge, correct PIN: count back to 0, lifts the lock set by the claim of this same try, stores the keyed hashes of the ticket and the binding cookie for 5 minutes (one slot), gives the lease back. The try must hold a live lease (SB004 otherwise). Not audited.';
comment on function platform.admin_bootstrap_ticket_status(text, text) is
  'DEV AND TEST DATABASES ONLY (ADR-018 Revision 3, SAH-42). Read only: (status CLOSED|LOCKED|INVALID|VALID, locked_until set with LOCKED) for the given ticket and binding hashes. Malformed input is INVALID, not an error. ticket_status(null, null) is the no-count peek.';
comment on function platform.admin_bootstrap_register(text, text, uuid, text, text, text, inet, text) is
  'DEV AND TEST DATABASES ONLY (ADR-018 Revision 3, SAH-42). Creates the first platform admin (not enrolled) when no admin exists and the ticket is valid; returns (outcome CREATED|CLOSED|LOCKED|INVALID, locked_until set with LOCKED). Never counts a try. Same advisory lock as admin_dev_create_sample_admin, then the state row, 10 s wait. Audits ADMIN_BOOTSTRAP_ADMIN_CREATED (id and name only).';

-- 4.6 Function privileges: nobody but the owner and sahaj_app. (Functions default to EXECUTE for PUBLIC: revoke first.)
revoke all on function platform.admin_bootstrap_begin(uuid)                                                  from public;
revoke all on function platform.admin_bootstrap_unlock_failed(uuid, inet, text)                              from public;
revoke all on function platform.admin_bootstrap_unlock_succeeded(uuid, text, text)                           from public;
revoke all on function platform.admin_bootstrap_ticket_status(text, text)                                    from public;
revoke all on function platform.admin_bootstrap_register(text, text, uuid, text, text, text, inet, text)     from public;

grant execute on function platform.admin_bootstrap_begin(uuid)                                                  to sahaj_app;
grant execute on function platform.admin_bootstrap_unlock_failed(uuid, inet, text)                              to sahaj_app;
grant execute on function platform.admin_bootstrap_unlock_succeeded(uuid, text, text)                           to sahaj_app;
grant execute on function platform.admin_bootstrap_ticket_status(text, text)                                    to sahaj_app;
grant execute on function platform.admin_bootstrap_register(text, text, uuid, text, text, text, inet, text)     to sahaj_app;

alter function platform.admin_bootstrap_begin(uuid)                                                  owner to sahaj_admin_definer;
alter function platform.admin_bootstrap_unlock_failed(uuid, inet, text)                              owner to sahaj_admin_definer;
alter function platform.admin_bootstrap_unlock_succeeded(uuid, text, text)                           owner to sahaj_admin_definer;
alter function platform.admin_bootstrap_ticket_status(text, text)                                    owner to sahaj_admin_definer;
alter function platform.admin_bootstrap_register(text, text, uuid, text, text, text, inet, text)     owner to sahaj_admin_definer;

revoke create on schema platform from sahaj_admin_definer;

-- 5. Self-check. Fails the run (and rolls this callback back, including the temporary CREATE) if the result is not
--    exactly as designed.
do $chk$
declare
  c_tbl      constant regclass := 'platform.platform_admin_bootstrap_state'::regclass;
  v_fn_names constant text[] := array['admin_bootstrap_begin', 'admin_bootstrap_unlock_failed',
                                      'admin_bootstrap_unlock_succeeded', 'admin_bootstrap_ticket_status',
                                      'admin_bootstrap_register'];
  v_required text[] := array['id', 'email', 'full_name', 'password_hash'];
  v_allowed  text[] := array['id', 'email', 'full_name', 'password_hash', 'totp_secret_enc', 'totp_key_version',
                             'totp_enrolled_at'];
  v_actual   text[];
  v_cols     text[];
  v_bad      integer;
  r          record;
begin
  -- 5a. The state table has exactly the nine designed columns and all nine named constraints (an older table of the same
  --     name is not changed by "create table if not exists", so this catches a foreign shape; the ALTER above adds the
  --     two lease columns and their check to a Revision 2 table).
  select array_agg(a.attname::text order by a.attnum) into v_cols
    from pg_attribute a
   where a.attrelid = c_tbl and a.attnum > 0 and not a.attisdropped;
  if v_cols is distinct from array['id', 'failures', 'locked_until', 'ticket_hash', 'binding_hash',
                                   'ticket_expires_at', 'updated_at', 'try_id', 'try_expires_at']::text[] then
    raise exception 'platform.platform_admin_bootstrap_state does not have the designed columns (SAH-42)';
  end if;
  select count(*) into v_bad
    from pg_constraint k
   where k.conrelid = c_tbl
     and k.conname = any (array[
       'pk_platform_admin_bootstrap_state',
       'ck_platform_admin_bootstrap_state_id',
       'ck_platform_admin_bootstrap_state_failures',
       'ck_platform_admin_bootstrap_state_ticket_hash',
       'ck_platform_admin_bootstrap_state_binding_hash',
       'ck_platform_admin_bootstrap_state_ticket_all_or_none',
       'ck_platform_admin_bootstrap_state_lock_matches_failures',
       'ck_platform_admin_bootstrap_state_no_ticket_while_locked',
       'ck_platform_admin_bootstrap_state_try_both_or_none']);
  if v_bad <> 9 then
    raise exception 'platform.platform_admin_bootstrap_state does not have the designed constraints (SAH-42)';
  end if;

  -- 5b. Table ACL: only the owner, and sahaj_admin_definer with SELECT, INSERT, UPDATE and nothing else.
  select count(*) into v_bad
    from pg_class c,
         lateral aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) x
   where c.oid = c_tbl
     and (x.grantee = 0
          or (x.grantee <> c.relowner
              and (pg_get_userbyid(x.grantee) <> 'sahaj_admin_definer'
                   or x.privilege_type not in ('SELECT', 'INSERT', 'UPDATE'))));
  if v_bad <> 0 then
    raise exception 'platform.platform_admin_bootstrap_state has a privilege beyond the owner and sahaj_admin_definer SELECT, INSERT, UPDATE';
  end if;
  if not has_table_privilege('sahaj_admin_definer', c_tbl, 'SELECT')
     or not has_table_privilege('sahaj_admin_definer', c_tbl, 'INSERT')
     or not has_table_privilege('sahaj_admin_definer', c_tbl, 'UPDATE') then
    raise exception 'sahaj_admin_definer needs SELECT, INSERT, UPDATE on platform.platform_admin_bootstrap_state';
  end if;

  -- 5c. sahaj_app has no privilege at all on any platform_admin* table, the new one included (ADR-003 D1).
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

  -- 5d. The definer's INSERT columns on platform_admin: at least the four of this feature, and never anything beyond
  --     the seven of ADR-008 (so no table-wide INSERT, no 'active', no 'version').
  select array_agg(a.attname::text) into v_actual
    from pg_attribute a
   where a.attrelid = 'platform.platform_admin'::regclass
     and a.attnum > 0 and not a.attisdropped
     and has_column_privilege('sahaj_admin_definer', a.attrelid, a.attnum, 'INSERT');
  if v_actual is null or not (v_required <@ v_actual) or not (v_actual <@ v_allowed) then
    raise exception 'sahaj_admin_definer INSERT columns on platform.platform_admin must include id, email, full_name, password_hash and be within the ADR-008 seven columns';
  end if;

  -- 5e. The five functions (and no other admin_bootstrap_ function, so no Revision 2 shape is left): the Revision 3
  --     signatures and argument names, owner, SECURITY DEFINER, fixed search_path, lock_timeout on the four row-locking
  --     functions (not ticket_status), EXECUTE for the owner and sahaj_app only.
  select count(*) into v_bad
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'platform' and p.proname like 'admin\_bootstrap\_%';
  if v_bad <> 5 then
    raise exception 'expected 5 admin_bootstrap_ functions in schema platform, found %', v_bad;
  end if;
  select count(*) into v_bad
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'platform' and p.proname = any (v_fn_names);
  if v_bad <> 5 then
    raise exception 'the five admin_bootstrap_ functions are not the designed ones';
  end if;
  -- proargnames lists every argument, input and table column, in order.
  select count(*) into v_bad
    from pg_proc p
   where (p.oid = to_regprocedure('platform.admin_bootstrap_begin(uuid)')
          and p.proargnames = array['p_try_id', 'outcome', 'failures', 'locked_until']::text[])
      or (p.oid = to_regprocedure('platform.admin_bootstrap_unlock_failed(uuid,inet,text)')
          and p.proargnames = array['p_try_id', 'p_ip', 'p_request_id', 'failures', 'locked_until']::text[])
      or (p.oid = to_regprocedure('platform.admin_bootstrap_unlock_succeeded(uuid,text,text)')
          and p.proargnames = array['p_try_id', 'p_ticket_hash', 'p_binding_hash']::text[]
          and p.prorettype = 'timestamptz'::regtype)
      or (p.oid = to_regprocedure('platform.admin_bootstrap_ticket_status(text,text)')
          and p.proargnames = array['p_ticket_hash', 'p_binding_hash', 'status', 'locked_until']::text[])
      or (p.oid = to_regprocedure('platform.admin_bootstrap_register(text,text,uuid,text,text,text,inet,text)')
          and p.proargnames = array['p_ticket_hash', 'p_binding_hash', 'p_admin_id', 'p_email', 'p_full_name',
                                    'p_password_hash', 'p_ip', 'p_request_id', 'outcome', 'locked_until']::text[]);
  if v_bad <> 5 then
    raise exception 'the five admin_bootstrap_ functions do not have the Revision 3 signatures (ADR-018 D4)';
  end if;
  select count(*) into v_bad
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'platform' and p.proname = any (v_fn_names)
     and (pg_get_userbyid(p.proowner) <> 'sahaj_admin_definer'
          or not p.prosecdef
          or p.proconfig is null
          or not exists (select 1 from unnest(p.proconfig) c where c like 'search_path=%pg_catalog%pg_temp%')
          or (p.proname in ('admin_bootstrap_begin', 'admin_bootstrap_unlock_failed',
                            'admin_bootstrap_unlock_succeeded', 'admin_bootstrap_register')
              and not exists (select 1 from unnest(p.proconfig) c where c like 'lock_timeout=%'))
          or not has_function_privilege('sahaj_app', p.oid, 'EXECUTE')
          or exists (select 1
                       from aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) x
                      where x.privilege_type = 'EXECUTE'
                        and (x.grantee = 0
                             or pg_get_userbyid(x.grantee) not in ('sahaj_admin_definer', 'sahaj_app'))));
  if v_bad <> 0 then
    raise exception '% admin_bootstrap_ function(s) are not owned by sahaj_admin_definer, not SECURITY DEFINER with a fixed search_path, missing lock_timeout, or executable by anyone but the owner and sahaj_app', v_bad;
  end if;

  -- 5f. The temporary CREATE on the schema is gone.
  if has_schema_privilege('sahaj_admin_definer', 'platform', 'CREATE') then
    raise exception 'sahaj_admin_definer must not keep CREATE on schema platform';
  end if;
end
$chk$;
