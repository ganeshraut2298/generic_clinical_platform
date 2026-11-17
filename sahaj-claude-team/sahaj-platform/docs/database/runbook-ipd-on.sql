-- Runbook: switch IPD on permanently for ONE clinic (DEC-059 tech lead default 1, security audit #7).
-- Owner of this file: db-engineer. Run by an operator, never by the app. Details: docs/database/runbook-ipd-on.md
--
-- Run as sahaj_migrator (not the superuser, not sahaj_app). One transaction. psql 12 or later.
--   Dry run (everything runs, then ROLLBACK, nothing is saved):
--     psql -h <host> -U sahaj_migrator -d <database> -v tenant_id=<tenant uuid> -v dry_run=1 -f docs/database/runbook-ipd-on.sql
--   For real:
--     psql -h <host> -U sahaj_migrator -d <database> -v tenant_id=<tenant uuid> -f docs/database/runbook-ipd-on.sql
--
-- What it does, in order:
--   0. Refuses to run without tenant_id, as another role, or with a tenant_id that is not a uuid.
--   1. Binds app.tenant_id to the target tenant (set local), as the app does.
--   2. Read-only checks: the tenant must exist (else the script stops, nothing changed). Prints the entitlement BEFORE.
--   3. Sets ipd_state = 'on' and ipd_trial_ends_at = null ("permanently on": the app only ever expires a 'trial', see
--      ModuleEntitlements.effectiveIpdState). Other fields (HRA, bed limit, request time) are left as they are. A clinic with no
--      row gets one with the app defaults (hra off, 25 beds). If the clinic is already on with no trial end, nothing is written.
--   4. Writes one platform.audit_event row, same shape as the app's (entity MODULE_ENTITLEMENT, before and after snapshots with the
--      same keys), action MODULE_IPD_SET_ON_RUNBOOK. actor_user_id is null (the column is a uuid; no marker fits) and
--      after_json carries "actor": "ops-runbook".
--   5. Prints the entitlement AFTER and the audit row, restores row level security, then COMMIT (or ROLLBACK for a dry run).
--
-- Why FORCE row level security is lifted for a moment: platform.tenant, platform.module_entitlement and platform.audit_event
-- have policies for sahaj_app only, and sahaj_migrator cannot bypass RLS, so as the owner it would see and write no rows. The same
-- trick as V2026_12_15_1000 (section C3): "alter table ... no force row level security" and restore it at once, inside this
-- transaction. The ALTER takes a short exclusive lock on those three tables until COMMIT: run off peak. lock_timeout is 5 seconds:
-- if the app holds the tables, the script fails with "lock timeout" and changes nothing; run it again.
-- No secrets, no patient data. Prints ids, states and times only.

\set ON_ERROR_STOP on
\pset pager off

\if :{?tenant_id}
\else
  \echo 'Usage: psql ... -v tenant_id=<tenant uuid> [-v dry_run=1] -f docs/database/runbook-ipd-on.sql'
  \quit
\endif

begin;
set local lock_timeout = '5s';
set local statement_timeout = '30s';

-- 0. Who am I, and is the tenant id a uuid (a bad value fails here, before anything else).
do $$
begin
  if current_user <> 'sahaj_migrator' then
    raise exception 'run this runbook as sahaj_migrator (current user is %)', current_user;
  end if;
end;
$$;
select :'tenant_id'::uuid as target_tenant \gset
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
          v_before, v_after || jsonb_build_object('actor', 'ops-runbook'));
  raise notice 'IPD set on permanently; audit row written (action MODULE_IPD_SET_ON_RUNBOOK, actor ops-runbook).';
end;
$$;

\echo '--- AFTER ---'
select m.ipd_state, m.ipd_trial_ends_at, m.hra_on, m.bed_limit, m.ipd_requested_at, m.version
  from platform.module_entitlement m where m.tenant_id = platform.current_tenant();
\echo '--- Latest audit rows for this tenant (entity MODULE_ENTITLEMENT) ---'
select a.at, a.action, a.actor_user_id, a.after_json ->> 'actor' as actor_marker, a.before_json ->> 'ipdState' as before_state,
       a.after_json ->> 'ipdState' as after_state
  from platform.audit_event a
 where a.tenant_id = platform.current_tenant() and a.entity_type = 'MODULE_ENTITLEMENT'
 order by a.at desc, a.id desc
 limit 3;

-- 5. Restore row level security and prove it before the transaction ends.
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

\if :{?dry_run}
  \echo 'DRY RUN: rolling back, nothing was saved.'
  rollback;
\else
  commit;
  \echo 'COMMITTED.'
\endif
