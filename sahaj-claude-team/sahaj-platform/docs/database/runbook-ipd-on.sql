-- Runbook: switch IPD on permanently for ONE clinic (DEC-059 tech lead default 1, security audit #7).
-- Owner of this file: db-engineer. Run by an operator, never by the app. Details: docs/database/runbook-ipd-on.md
-- Round 2 fixes (SAH-76): security M-2 (who and why), L-2 (commit only on request), code review #10.
-- Round 3 fixes (SAH-76): security N-1 (the tenant's audit row is customer-visible: who/why/where go to the Run record instead),
--   N-4 (a refusal exits 3; reason with a phone-number-like digit run is refused), code review #7 (operator digit guard).
--
-- Run as sahaj_migrator (not the superuser, not sahaj_app). One transaction. psql 12 or later.
-- Run it with  psql -f  ONLY. Never the include command, and never pasted into an open psql session (an aborted transaction would keep exclusive locks).
--   Dry run (everything runs, then ROLLBACK, nothing is saved; this is also what happens when -v commit is missing or wrong):
--     psql -h <host> -U sahaj_migrator -d <database> -v tenant_id=<tenant uuid> -v operator=<staff ref> -v approval=<DEC id or ticket> -v reason='<short text>' -f docs/database/runbook-ipd-on.sql
--   For real (the ONLY value that commits is exactly: commit=yes):
--     psql -h <host> -U sahaj_migrator -d <database> -v tenant_id=<tenant uuid> -v operator=<staff ref> -v approval=<DEC id or ticket> -v reason='<short text>' -v commit=yes -f docs/database/runbook-ipd-on.sql
--
-- Exit code (check with: echo $?): 0 for a good dry run or a real run; 3 for a refusal and for any error (ON_ERROR_STOP).
--
-- Required variables (all four; a missing or empty one is refused before any change):
--   tenant_id  the clinic's platform.tenant.id (uuid)
--   operator   the operator's staff reference (1-64 characters: letters, digits and . _ : / # -). NOT a name, NOT a mobile number.
--              Refused when, after removing - . _ : / # +, it still holds 10 or more digits in a row.
--   approval   the decision id or ticket that approved this go-live (same characters, 1-64), e.g. DEC-nnn. No digit guard (ticket ids can be long).
--   reason     short text, 1-200 characters, one line. No patient data, no names, no mobile numbers.
--              Refused when, after removing spaces, dots and dashes, it holds 10 or more digits in a row.
-- Optional: commit=yes. Anything else (missing, "Yes", "1", "true", a typo) is a dry run. -v dry_run is no longer used and is refused.
--
-- What it does, in order:
--   0. Refuses to run (before any change, with an error so psql exits 3) when a required variable is missing, empty or malformed,
--      when dry_run is given, as another role than sahaj_migrator, or with a tenant_id that is not a uuid. The refusal messages never
--      repeat the values that were given. Says at once whether this is a DRY RUN or REAL.
--   1. Binds app.tenant_id to the target tenant (set local), as the app does.
--   2. Read-only checks: the tenant must exist (else the script stops, nothing changed). Prints the entitlement BEFORE.
--   3. Sets ipd_state = 'on' and ipd_trial_ends_at = null ("permanently on": the app only ever expires a 'trial', see
--      ModuleEntitlements.effectiveIpdState). Other fields (HRA, pharmacy_on, bed limit, request time) are left as they are. A clinic
--      with no row gets one with the app defaults (hra off, pharmacy off, 25 beds). If the clinic is already on with no trial end,
--      nothing is written.
--   4. Writes one platform.audit_event row, same shape as the app's (entity MODULE_ENTITLEMENT, before and after snapshots with the
--      same five keys as ModulesService.snapshot: ipdState, ipdTrialEndsAt, hraOn, bedLimit, ipdRequestedAt), action
--      MODULE_IPD_SET_ON_RUNBOOK. actor_user_id is null (the column is a uuid; no marker fits). after_json adds ONLY: actor
--      "ops-runbook" and approval. The clinic OWNER can read this row, so the operator, the reason, the database user names, the client
--      address and the application name are NOT in it; they go to the Run record below (docs/decisions, kept by the human).
--   5. Prints the entitlement AFTER and the audit row, restores row level security and checks it in pg_class, then COMMIT only when
--      commit=yes, otherwise ROLLBACK.
--   6. Last, after the COMMIT or the ROLLBACK, prints the Run record (expanded output) for the human to copy into docs/decisions.
--      It is not printed when the run is refused or fails.
--
-- Why FORCE row level security is lifted for a moment: platform.tenant, platform.module_entitlement and platform.audit_event
-- have policies for sahaj_app only, and sahaj_migrator cannot bypass RLS, so as the owner it would see and write no rows. The same
-- trick as V2026_12_15_1000 (section C3): "alter table ... no force row level security" and restore it at once, inside this
-- transaction. The ALTER takes a short exclusive lock on those three tables until COMMIT: run off peak. lock_timeout is 5 seconds:
-- if the app holds the tables, the script fails with "lock timeout" and changes nothing; run it again.
-- statement_timeout is 30 seconds and idle_in_transaction_session_timeout is 30 seconds: a stalled or half-run session is ended by
-- the server and rolled back, so the locks are released.
-- No secrets, no patient data. Prints ids, states and times only. Values reach SQL only through psql :'var' quoting.

\set ON_ERROR_STOP on
\pset pager off

-- Defaults and input checks. Nothing below this line touches the database until all of it has passed.
-- Every refusal is a DO block that raises (not \quit), so psql stops with exit code 3. No message repeats a given value.
\if :{?commit}
\else
  \set commit no
\endif

select (:{?tenant_id} and :{?operator} and :{?approval} and :{?reason}) as inputs_given \gset
\if :inputs_given
\else
  \echo 'Usage: psql ... -v tenant_id=<tenant uuid> -v operator=<staff ref> -v approval=<DEC id or ticket> -v reason=<short text> [-v commit=yes] -f docs/database/runbook-ipd-on.sql'
  do $$ begin raise exception 'Refused: tenant_id, operator, approval and reason are all required. Nothing was changed.'; end; $$;
\endif

\if :{?dry_run}
  do $$ begin raise exception 'Refused: the variable dry_run is no longer used. A dry run is the default; only -v commit=yes saves. Remove -v dry_run. Nothing was changed.'; end; $$;
\endif

select (:'tenant_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') as tenant_ok,
       (:'operator' ~ '^[A-Za-z0-9._:/#-]{1,64}$') as operator_chars_ok,
       (regexp_replace(:'operator', '[-._:/#+]', '', 'g') !~ '[0-9]{10}') as operator_digits_ok,
       (:'approval' ~ '^[A-Za-z0-9._:/#-]{1,64}$') as approval_ok,
       (length(btrim(:'reason')) between 1 and 200) as reason_length_ok,
       (:'reason' !~ '[[:cntrl:]]') as reason_chars_ok,
       (regexp_replace(:'reason', '[ .-]', '', 'g') !~ '[0-9]{10}') as reason_digits_ok \gset

\if :tenant_ok
\else
  do $$ begin raise exception 'Refused: tenant_id must be a uuid (8-4-4-4-12 hexadecimal characters). Nothing was changed.'; end; $$;
\endif
\if :operator_chars_ok
\else
  do $$ begin raise exception 'Refused: operator must be 1-64 characters from letters, digits and . _ : / # - (a staff reference, not a name). Nothing was changed.'; end; $$;
\endif
\if :operator_digits_ok
\else
  do $$ begin raise exception 'Refused: operator looks like a mobile number (10 or more digits in a row). Use a staff reference. Nothing was changed.'; end; $$;
\endif
\if :approval_ok
\else
  do $$ begin raise exception 'Refused: approval must be 1-64 characters from letters, digits and . _ : / # - (a decision id or ticket). Nothing was changed.'; end; $$;
\endif
\if :reason_length_ok
\else
  do $$ begin raise exception 'Refused: reason must be 1-200 characters. Nothing was changed.'; end; $$;
\endif
\if :reason_chars_ok
\else
  do $$ begin raise exception 'Refused: reason must be on one line, with no control characters. Nothing was changed.'; end; $$;
\endif
\if :reason_digits_ok
\else
  do $$ begin raise exception 'Refused: reason looks like it holds a mobile number (10 or more digits, ignoring spaces, dots and dashes). Remove it. Nothing was changed.'; end; $$;
\endif

select :'tenant_id'::uuid as target_tenant \gset
select (:'commit' = 'yes') as do_commit \gset

\if :do_commit
  \echo '*** REAL RUN: this will COMMIT (commit=yes was given). ***'
\else
  \echo '*** DRY RUN: this will ROLL BACK; nothing will be saved. To save, run again with -v commit=yes. ***'
\endif

begin;
set local lock_timeout = '5s';
set local statement_timeout = '30s';
set local idle_in_transaction_session_timeout = '30s';

-- 0. Who am I. Only the approval goes into the tenant's audit row (read back below with current_setting; a DO body cannot see psql
-- variables). set_config(..., true) lasts for this transaction only. audit_written tells the Run record whether a row was inserted.
do $$
begin
  if current_user <> 'sahaj_migrator' then
    raise exception 'run this runbook as sahaj_migrator (current user is %)', current_user;
  end if;
end;
$$;
select set_config('app.runbook_approval', btrim(:'approval'), true) as kept_approval,
       set_config('app.runbook_audit_written', 'no', true) as kept_audit_flag;
\echo '--- Session ---'
select current_database() as database, session_user as session_user_name, current_user as current_user_name,
       inet_client_addr() as client_addr, current_setting('application_name', true) as application_name;
-- Facts for the Run record (kept in psql variables, printed at the very end; never written to the tenant's audit row).
-- now() is the transaction start time, the same value the audit row's "at" column takes by default.
select current_database() as rr_database,
       session_user as rr_session_user,
       current_user as rr_current_user,
       coalesce(inet_client_addr()::text, 'none (unix socket)') as rr_client_addr,
       coalesce(nullif(current_setting('application_name', true), ''), '(not set)') as rr_application_name,
       to_char(now() at time zone 'Asia/Kolkata', 'DD Mon YYYY, HH12:MI:SS AM') || ' IST' as rr_run_ist,
       to_char(now() at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') as rr_run_utc \gset
-- 1. Bind the tenant for this transaction only (platform.current_tenant() reads it; the audit row takes its tenant_id from it).
select set_config('app.tenant_id', :'tenant_id', true) as bound_tenant;

-- 2. Read-only checks. Lift FORCE on the tenant table to read it, then refuse when the tenant does not exist.
alter table platform.tenant no force row level security;
do $$
begin
  if not exists (select 1 from platform.tenant t where t.id = platform.current_tenant()) then
    raise exception 'tenant % does not exist: nothing was changed', platform.current_tenant();
  end if;
end;
$$;
select t.id as tenant_id, t.slug, t.plan, t.status from platform.tenant t where t.id = platform.current_tenant();
alter table platform.tenant force row level security;

alter table platform.module_entitlement no force row level security;
alter table platform.audit_event no force row level security;

\echo '--- BEFORE ---'
select coalesce(m.ipd_state, 'off (no row yet)') as ipd_state, m.ipd_trial_ends_at, m.hra_on, m.bed_limit, m.ipd_requested_at, m.version
  from (select 1) x
  left join platform.module_entitlement m on m.tenant_id = platform.current_tenant();

-- 3 and 4. Change and audit, in one block so the audit snapshots are exactly what was read and written.
-- Only the five snapshot keys of the app (ModulesService.snapshot) go into before and after; pharmacy_on is not part of the app's
-- snapshot either, and this script never writes it (a new row takes the column default, false).
-- The audit row is in the clinic's own platform.audit_event and the clinic OWNER can read it: after_json holds the five keys,
-- actor and approval, nothing else.
do $$
declare
  v_tenant uuid := platform.current_tenant();
  v_before jsonb;
  v_after  jsonb;
begin
  select jsonb_build_object('ipdState', m.ipd_state, 'ipdTrialEndsAt', m.ipd_trial_ends_at, 'hraOn', m.hra_on,
                            'bedLimit', m.bed_limit, 'ipdRequestedAt', m.ipd_requested_at)
    into v_before
    from platform.module_entitlement m
   where m.tenant_id = v_tenant
   for update;
  if not found then
    -- same defaults as the app (ModuleEntitlements.DEFAULTS)
    v_before := jsonb_build_object('ipdState', 'off', 'ipdTrialEndsAt', null, 'hraOn', false, 'bedLimit', 25, 'ipdRequestedAt', null);
  end if;

  if v_before ->> 'ipdState' = 'on' and v_before -> 'ipdTrialEndsAt' = 'null'::jsonb then
    raise notice 'IPD is already on permanently for this tenant: nothing written, no audit row.';
    return;
  end if;

  insert into platform.module_entitlement (tenant_id, ipd_state, ipd_trial_ends_at, updated_at, updated_by)
  values (v_tenant, 'on', null, now(), null)
  on conflict (tenant_id) do update set
    ipd_state         = 'on',
    ipd_trial_ends_at = null,
    updated_at        = now(),
    updated_by        = null,
    version           = platform.module_entitlement.version + 1;

  v_after := v_before || jsonb_build_object('ipdState', 'on', 'ipdTrialEndsAt', null);
  insert into platform.audit_event (tenant_id, actor_user_id, action, entity_type, entity_id, before_json, after_json)
  values (v_tenant, null, 'MODULE_IPD_SET_ON_RUNBOOK', 'MODULE_ENTITLEMENT', v_tenant,
          v_before,
          v_after || jsonb_build_object(
            'actor',    'ops-runbook',
            'approval', current_setting('app.runbook_approval')));
  perform set_config('app.runbook_audit_written', 'yes', true);
  raise notice 'IPD set on permanently; audit row written (action MODULE_IPD_SET_ON_RUNBOOK, actor ops-runbook).';
end;
$$;

\echo '--- AFTER ---'
select m.ipd_state, m.ipd_trial_ends_at, m.hra_on, m.bed_limit, m.ipd_requested_at, m.version
  from platform.module_entitlement m where m.tenant_id = platform.current_tenant();
\echo '--- Latest audit rows for this tenant (entity MODULE_ENTITLEMENT) ---'
select a.at, a.action, a.actor_user_id, a.after_json ->> 'actor' as actor_marker,
       a.after_json ->> 'approval' as approval, a.before_json ->> 'ipdState' as before_state,
       a.after_json ->> 'ipdState' as after_state
  from platform.audit_event a
 where a.tenant_id = platform.current_tenant() and a.entity_type = 'MODULE_ENTITLEMENT'
 order by a.at desc, a.id desc
 limit 3;

-- 5. Restore row level security and prove it before the transaction ends (the tenant table was restored in step 2; checked again).
alter table platform.module_entitlement force row level security;
alter table platform.audit_event force row level security;
do $$
begin
  if exists (select 1 from pg_catalog.pg_class c
              where c.oid in ('platform.tenant'::regclass, 'platform.module_entitlement'::regclass, 'platform.audit_event'::regclass)
                and not (c.relrowsecurity and c.relforcerowsecurity)) then
    raise exception 'row level security was not restored: rolling back';
  end if;
end;
$$;

-- Was an audit row inserted in this transaction? (read before the transaction ends)
select current_setting('app.runbook_audit_written') as rr_audit_inserted \gset

-- Commit ONLY when commit=yes was given exactly; every other value (or none) rolls back.
\if :do_commit
  commit;
  \set rr_outcome COMMITTED
  \set rr_audit_saved :rr_audit_inserted
  \echo 'COMMITTED. Now copy the Run record below into docs/decisions (see docs/database/runbook-ipd-on.md).'
\else
  rollback;
  \set rr_outcome 'ROLLED BACK (dry run, nothing saved)'
  \set rr_audit_saved no
  \echo 'DRY RUN: rolled back, nothing was saved. To save, run the same command again with -v commit=yes.'
\endif

-- 6. Run record. Printed after the COMMIT or ROLLBACK, on the operator's screen only. It is NOT stored in the database. The human copies it
-- into docs/decisions. rr_run_utc is the same instant as the audit row's "at" (both are the transaction start time).
\echo '--- Run record (copy into docs/decisions) ---'
\pset expanded on
select :'rr_outcome'                 as outcome,
       :'tenant_id'                  as tenant_id,
       :'operator'                   as operator,
       :'approval'                   as approval,
       btrim(:'reason')              as reason,
       :'rr_session_user'            as session_user,
       :'rr_current_user'            as current_user,
       :'rr_client_addr'             as client_address,
       :'rr_application_name'        as application_name,
       :'rr_database'                as database,
       :'rr_run_ist'                 as run_time_ist,
       :'rr_run_utc'                 as run_time_utc,
       :'rr_audit_saved'             as audit_row_saved;
\pset expanded off
