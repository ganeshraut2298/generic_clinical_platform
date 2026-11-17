-- SAH-76 review round · DEC-057 (human answered Option A on all three findings, 16 Nov 2026). Forward-only, one migration.
-- Design note: docs/database/SAH-76-review.md. No earlier migration is edited (V2026_12_15_1000 stays as it is).
--
-- What it does:
--   1. Security M2 (self-verification): the pharmacist verify (ipd.guard_medicine_order_version_update) and the pharmacist query
--      (ipd.guard_medicine_order_query_insert) are refused when the actor is the order's ordered_by OR the author of the version
--      (created_by). Function bodies are replaced in place (create or replace; same trigger names, no trigger is touched).
--   2. Security M1 (override split): new tenant table ipd.dose_scan_stop, one row per wristband or medicine mismatch stop, so the
--      server can refuse a manual override for 15 minutes after a stop by the same user on the same order and slot. The event log
--      has no order or slot columns and ipd must not read platform.audit_event, hence a table of its own. Append-only.
--   3. Backstop in the dose insert guard (ipd.guard_dose_administration_insert, replaced in place): a GIVEN dose needs the wristband
--      scan (identity_scan), the override reason WRISTBAND_DAMAGED is refused on new rows, an OVERRIDE row carries no medicine_scan.
--
-- NO DATA IS CHANGED, NOTHING IS DROPPED. No UPDATE, no DELETE, no ALTER of an existing table, no constraint touched. Old rows
-- (including old doses with WRISTBAND_DAMAGED or a null identity_scan on an OVERRIDE) stay valid: triggers fire on new inserts only,
-- and ck_dose_administration_override_reason and ck_dose_administration_identity_scan_by_outcome are not changed.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. Version verify: never the orderer, never the author of the version being verified
-- ======================================================================================================================
-- Same body as in V2026_12_15_1000 except the verifier rule. SQLSTATE 23514 (check_violation), message contains "medicine order".
create or replace function ipd.guard_medicine_order_version_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_ordered_by uuid;
  v_at         timestamptz := clock_timestamp();
begin
  if (pg_catalog.to_jsonb(new) - 'superseded_at' - 'verified_by' - 'verified_at')
     is distinct from (pg_catalog.to_jsonb(old) - 'superseded_at' - 'verified_by' - 'verified_at') then
    raise exception 'medicine order version cannot be edited'
      using errcode = 'check_violation';
  end if;
  if old.superseded_at is not null and new.superseded_at is distinct from old.superseded_at then
    raise exception 'medicine order version is already replaced'
      using errcode = 'check_violation';
  end if;
  if old.superseded_at is null and new.superseded_at is not null then
    new.superseded_at := v_at;
  end if;
  if old.verified_by is not null and (new.verified_by is distinct from old.verified_by
                                      or new.verified_at is distinct from old.verified_at) then
    raise exception 'medicine order version is already verified'
      using errcode = 'check_violation';
  end if;
  if old.verified_by is null and new.verified_by is not null then
    if old.verification <> 'REQUIRED' then
      raise exception 'medicine order version needs no verification'
        using errcode = 'check_violation';
    end if;
    select o.ordered_by into v_ordered_by
      from ipd.medicine_order o
     where o.tenant_id = new.tenant_id and o.id = new.order_id;
    -- DEC-057 item 2: the person who placed the order and the person who wrote this version cannot verify it.
    if new.verified_by = v_ordered_by or new.verified_by = old.created_by then
      raise exception 'medicine order version cannot be verified by the person who ordered or changed it'
        using errcode = 'check_violation';
    end if;
    new.verified_at := v_at;
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_medicine_order_version_update() from public;
revoke all on function ipd.guard_medicine_order_version_update() from sahaj_app;

-- ======================================================================================================================
-- 2. Pharmacist query: never the orderer, never the author of the current version
-- ======================================================================================================================
create or replace function ipd.guard_medicine_order_query_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_status     text;
  v_ordered_by uuid;
  v_cur        integer;
  v_version_by uuid;
begin
  select o.status, o.ordered_by, o.current_version into v_status, v_ordered_by, v_cur
    from ipd.medicine_order o
   where o.tenant_id = new.tenant_id and o.id = new.order_id;
  if not found then
    return new;
  end if;
  if v_status not in ('PENDING', 'QUERIED') then
    raise exception 'medicine order query applies only to an order in the pharmacist check'
      using errcode = 'check_violation';
  end if;
  select v.created_by into v_version_by
    from ipd.medicine_order_version v
   where v.tenant_id = new.tenant_id and v.order_id = new.order_id and v.version_no = v_cur;
  -- DEC-057 item 2: the person who placed the order and the person who wrote the current version cannot query it.
  if new.asked_by = v_ordered_by or new.asked_by = v_version_by then
    raise exception 'medicine order query cannot be asked by the person who ordered or changed it'
      using errcode = 'check_violation';
  end if;
  if new.order_version <> v_cur then
    raise exception 'medicine order query must name the current version'
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_medicine_order_query_insert() from public;
revoke all on function ipd.guard_medicine_order_query_insert() from sahaj_app;

-- ======================================================================================================================
-- 3. ipd.dose_scan_stop: a wristband or medicine scan that did not match (DEC-057 item 1)
-- ======================================================================================================================
-- One row per stop. The app reads it to refuse a manual override within 15 minutes of a stop by the same user on the same order
-- and slot (PRN: same order). Append-only. Holds ids and a code kind only: no patient name, mobile, scanned text or clinical text.
create table if not exists ipd.dose_scan_stop (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null default platform.current_tenant()
               constraint fk_dose_scan_stop_tenant references platform.tenant (id),
  admission_id uuid not null,
  order_id     uuid not null,
  slot_at      timestamptz not null,
  check_kind   text not null
               constraint ck_dose_scan_stop_check_kind check (check_kind in ('WRISTBAND', 'MEDICINE')),
  actor        uuid not null,
  stopped_at   timestamptz not null default clock_timestamp(),
  constraint fk_dose_scan_stop_admission foreign key (tenant_id, admission_id)
    references ipd.admission (tenant_id, id),
  -- the order belongs to the admission (same composite target as ipd.dose_administration)
  constraint fk_dose_scan_stop_order foreign key (tenant_id, order_id, admission_id)
    references ipd.medicine_order (tenant_id, id, admission_id)
);
-- "Did this user have a stop on this order in the last 15 minutes": equality on tenant, order and actor, range on stopped_at, newest
-- first. Also serves the foreign key to the order from the child side (leading tenant_id, order_id). The slot is filtered on the
-- few rows that remain. The admission foreign key needs no own index: admissions are never deleted and the table is small.
create index if not exists ix_dose_scan_stop_tenant_order_actor_stopped
  on ipd.dose_scan_stop (tenant_id, order_id, actor, stopped_at desc);

alter table ipd.dose_scan_stop enable row level security;
alter table ipd.dose_scan_stop force row level security;
create policy p_dose_scan_stop_select on ipd.dose_scan_stop
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_dose_scan_stop_insert on ipd.dose_scan_stop
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());

-- Default privileges give sahaj_app four commands on a new table: take everything away, then grant select and a column insert.
-- id, tenant_id (default platform.current_tenant(), still checked by the policy) and stopped_at (clock_timestamp()) come from the
-- database, never from the app. No update, no delete.
revoke all on ipd.dose_scan_stop from sahaj_app;
revoke all on ipd.dose_scan_stop from public;
grant select on ipd.dose_scan_stop to sahaj_app;
grant insert (admission_id, order_id, slot_at, check_kind, actor) on ipd.dose_scan_stop to sahaj_app;

-- Update, delete and truncate refused for every role, the owner included (SQLSTATE 42501, "... is append-only (<op> refused)").
create trigger trg_dose_scan_stop_no_update_delete
  before update or delete on ipd.dose_scan_stop
  for each row execute function platform.deny_audit_mutation();
create trigger trg_dose_scan_stop_no_truncate
  before truncate on ipd.dose_scan_stop
  for each statement execute function platform.deny_audit_mutation();

-- ======================================================================================================================
-- 4. Dose insert guard, replaced in place (same trigger name): backstop for the split override
-- ======================================================================================================================
-- Same body as in V2026_12_15_1000 plus three rules for a GIVEN dose (all SQLSTATE 23514, message contains "dose"):
--   a. identity_scan is required (the wristband is never waived; the column check still lets old OVERRIDE rows have none),
--   b. override_reason_key WRISTBAND_DAMAGED is refused (the key stays valid in the check so old rows still read),
--   c. an OVERRIDE dose has no medicine_scan (an override means the medicine code was not scanned).
create or replace function ipd.guard_dose_administration_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_order_status text;
  v_cur_version  integer;
  v_order_prio   text;
  v_drug_alert   boolean;
  v_kind         text;
  v_adm_status   text;
  v_prev_order   uuid;
  v_prev_slot    timestamptz;
  v_prev_attempt smallint;
  v_undo_secs    integer;
begin
  select o.status, o.current_version, o.priority, (d.high_alert is not null)
    into v_order_status, v_cur_version, v_order_prio, v_drug_alert
    from ipd.medicine_order o
    join ipd.drug d on d.key = o.drug_key
   where o.tenant_id = new.tenant_id and o.id = new.order_id and o.admission_id = new.admission_id;
  if not found then
    return new;
  end if;

  select v.kind into v_kind
    from ipd.medicine_order_version v
   where v.tenant_id = new.tenant_id and v.order_id = new.order_id and v.version_no = v_cur_version;

  if v_order_status = 'ACTIVE' then
    if new.before_verification then
      raise exception 'dose of an active order is not before verification'
        using errcode = 'check_violation';
    end if;
  elsif v_order_status = 'PENDING' and v_kind = 'ONCE' and v_order_prio = 'STAT' then
    if not new.before_verification then
      raise exception 'dose before verification must be marked before verification'
        using errcode = 'check_violation';
    end if;
    if new.outcome = 'GIVEN' and exists (
         select 1 from ipd.dose_administration g
          where g.tenant_id = new.tenant_id and g.order_id = new.order_id and g.outcome = 'GIVEN'
            and not exists (select 1 from ipd.dose_correction c where c.tenant_id = g.tenant_id and c.dose_id = g.id)) then
      raise exception 'dose of a STAT order before verification can be given only once'
        using errcode = 'check_violation';
    end if;
  else
    raise exception 'dose can be recorded only for an active medicine order'
      using errcode = 'check_violation';
  end if;

  if new.order_version <> v_cur_version then
    raise exception 'dose must be recorded on the current version of the order'
      using errcode = 'check_violation';
  end if;
  if new.dose_kind is distinct from v_kind then
    raise exception 'dose kind does not match the order version'
      using errcode = 'check_violation';
  end if;

  select a.status into v_adm_status
    from ipd.admission a
   where a.tenant_id = new.tenant_id and a.id = new.admission_id;
  if not found or v_adm_status <> 'ADMITTED' then
    raise exception 'dose can be recorded only while the patient is admitted'
      using errcode = 'check_violation';
  end if;
  if new.high_alert is distinct from v_drug_alert then
    raise exception 'dose high-alert flag does not match the medicine'
      using errcode = 'check_violation';
  end if;

  if new.attempt > 1 then
    select p.order_id, p.slot_at, p.attempt into v_prev_order, v_prev_slot, v_prev_attempt
      from ipd.dose_administration p
     where p.tenant_id = new.tenant_id and p.id = new.supersedes_dose_id;
    if not found or v_prev_order <> new.order_id or v_prev_slot <> new.slot_at or v_prev_attempt <> new.attempt - 1 then
      raise exception 'dose attempt must follow the earlier attempt of the same slot'
        using errcode = 'check_violation';
    end if;
    if not exists (select 1 from ipd.dose_correction c where c.tenant_id = new.tenant_id and c.dose_id = new.supersedes_dose_id) then
      raise exception 'dose can follow an earlier attempt only after that attempt was corrected'
        using errcode = 'check_violation';
    end if;
  end if;

  if new.outcome = 'GIVEN' then
    if new.scan_method is null then
      raise exception 'given dose needs a scan method'
        using errcode = 'check_violation';
    end if;
    if new.identity_scan is null then
      raise exception 'given dose needs the wristband scan'
        using errcode = 'check_violation';
    end if;
    if new.override_reason_key = 'WRISTBAND_DAMAGED' then
      raise exception 'dose override reason wristband damaged is no longer allowed'
        using errcode = 'check_violation';
    end if;
    if new.scan_method = 'OVERRIDE' and new.medicine_scan is not null then
      raise exception 'dose with a manual override has no medicine scan'
        using errcode = 'check_violation';
    end if;
    select c.undo_seconds into v_undo_secs from ipd.config c where c.tenant_id = new.tenant_id;
    new.undo_until := new.recorded_at + pg_catalog.make_interval(secs => coalesce(v_undo_secs, 10));
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_dose_administration_insert() from public;
revoke all on function ipd.guard_dose_administration_insert() from sahaj_app;

-- ======================================================================================================================
-- 5. Comments
-- ======================================================================================================================
comment on table ipd.dose_scan_stop is 'SAH-76 review (DEC-057 item 1): a wristband or medicine scan that did not match (check_kind WRISTBAND or MEDICINE), by actor, on an order and slot. The app refuses a manual override for 15 minutes after a stop by the same user on the same order and slot. Append-only: select and insert only; triggers refuse update, delete, truncate. Ids and a kind only, no scanned text.';
comment on function ipd.guard_medicine_order_version_update() is 'SAH-76, replaced by the SAH-76 review round (DEC-057 item 2): only two one-way stamps; a verify is refused for the person who ordered the order or wrote the version. SQLSTATE 23514, message contains "medicine order".';
comment on function ipd.guard_medicine_order_query_insert() is 'SAH-76, replaced by the SAH-76 review round (DEC-057 item 2): a query is refused for the person who ordered the order or wrote its current version. SQLSTATE 23514, message contains "medicine order".';
comment on function ipd.guard_dose_administration_insert() is 'SAH-32, replaced by SAH-76 and again by the SAH-76 review round (DEC-057 item 1): adds a backstop for a GIVEN dose (wristband scan required, override reason WRISTBAND_DAMAGED refused, OVERRIDE has no medicine scan). SQLSTATE 23514, message contains "dose".';
