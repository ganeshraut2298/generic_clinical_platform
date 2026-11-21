-- SAH-80 WP-M (story 80-15, patient-wise consumption charge, TP-5 and TP-6), migration M3. ADR-030 "Data needed" (WP-M, last bullet).
-- SAH-79 V2026_12_17_1620 made pharmacy.consumption insert-only and promised: "when SAH-80 later needs to mark an entry as charged, its own
-- migration widens the charge_status check and grants the one update it needs". This is that migration. Additive:
--   1. Three nullable columns: charge_ref, charged_at, charge_failure.
--   2. ck_consumption_charge_status and ck_consumption_shape accept CHARGED for a PATIENT entry (the old values stay valid).
--   3. sahaj_app may update only the four charge columns (column-level grant) under a new row level security update policy.
--   4. A guard: the only change allowed is CHARGE_PENDING to CHARGED (with ref and time), or a new failure text while pending.
-- No IPD table is changed. The charge itself is posted through the pharmacy-owned IpdChargePort (a no-op bean until the IPD session
-- approves it), with sourceKey = "consumption:<line id>".

set local lock_timeout = '30s';

alter table pharmacy.consumption add column if not exists charge_ref varchar(60)
  constraint ck_consumption_charge_ref check (char_length(btrim(charge_ref)) between 1 and 60);
alter table pharmacy.consumption add column if not exists charged_at timestamptz;
alter table pharmacy.consumption add column if not exists charge_failure varchar(200)
  constraint ck_consumption_charge_failure check (char_length(btrim(charge_failure)) between 1 and 200);

alter table pharmacy.consumption drop constraint if exists ck_consumption_charge_status;
alter table pharmacy.consumption add constraint ck_consumption_charge_status
  check (charge_status in ('NOT_APPLICABLE', 'CHARGE_PENDING', 'CHARGED'));

alter table pharmacy.consumption drop constraint if exists ck_consumption_shape;
alter table pharmacy.consumption add constraint ck_consumption_shape check (
  (consumption_type = 'GENERAL' and purpose_key is not null and admission_id is null and ordered_by_membership_id is null
     and ordered_by_name is null and charge_status = 'NOT_APPLICABLE')
  or (consumption_type = 'PATIENT' and purpose_key is null and admission_id is not null
     and charge_status in ('CHARGE_PENDING', 'CHARGED')));

-- A charged entry has its reference and time; a failure text exists only while the charge is pending.
alter table pharmacy.consumption drop constraint if exists ck_consumption_charge_state;
alter table pharmacy.consumption add constraint ck_consumption_charge_state check (
  (charge_status = 'CHARGED' and charge_ref is not null and charged_at is not null and charge_failure is null)
  or (charge_status = 'CHARGE_PENDING' and charge_ref is null and charged_at is null)
  or (charge_status = 'NOT_APPLICABLE' and charge_ref is null and charged_at is null and charge_failure is null));

-- The "post waiting charges" worklist already has ix_consumption_charge_pending (1620).

drop policy if exists p_consumption_update on pharmacy.consumption;
create policy p_consumption_update on pharmacy.consumption for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
grant update (charge_status, charge_ref, charged_at, charge_failure) on pharmacy.consumption to sahaj_app;

create or replace function pharmacy.guard_consumption_charge_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_open text[] := array['charge_status', 'charge_ref', 'charged_at', 'charge_failure'];
begin
  if (to_jsonb(new) - v_open) is distinct from (to_jsonb(old) - v_open) then
    raise exception 'consumption entry is final; only the charge state changes' using errcode = 'check_violation';
  end if;
  if new.charge_status is distinct from old.charge_status
     and not (old.charge_status = 'CHARGE_PENDING' and new.charge_status = 'CHARGED') then
    raise exception 'consumption charge cannot go from % to %', old.charge_status, new.charge_status using errcode = 'check_violation';
  end if;
  if old.charge_status = 'CHARGED'
     and (new.charge_ref is distinct from old.charge_ref or new.charged_at is distinct from old.charged_at
          or new.charge_failure is distinct from old.charge_failure) then
    raise exception 'consumption charge is final' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_consumption_charge_update() from public;
revoke all on function pharmacy.guard_consumption_charge_update() from sahaj_app;

create trigger trg_consumption_guard_charge_update before update on pharmacy.consumption
  for each row execute function pharmacy.guard_consumption_charge_update();

comment on column pharmacy.consumption.charge_ref is
  'SAH-80: the reference the IPD charge port answered when the patient-wise consumption was charged. Null until CHARGED.';
comment on column pharmacy.consumption.charge_failure is
  'SAH-80: why the last attempt to post the charge failed (short text from the port, never a name). Only while CHARGE_PENDING.';
comment on function pharmacy.guard_consumption_charge_update() is
  'Consumption guard (SAH-80): only the charge columns may change, and only CHARGE_PENDING to CHARGED; a charged entry is final. SQLSTATE 23514.';
