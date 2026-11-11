-- SAH-32: IPD Medicines (MAR) and giving a dose. Decision record: ADR-011 ("Data needed" items 1 and 2, D1, D2, D6, D13).
-- Forward-only and additive: one new unique constraint on ipd.medicine_order (no column, data or grant change), one new
-- table ipd.dose_administration, one guard function. Nothing earlier is edited, dropped or renamed.
-- platform.staff_pin, ipd.clinical_event and platform.audit_event are unchanged (no schema change is needed for the colleague PIN check).

-- 1. Target of the composite foreign key "the dose belongs to the order's admission".
-- ipd.medicine_order is small at this point (SAH-31 is not released alone: ADR-011 D15), so the brief table lock is harmless.
alter table ipd.medicine_order
  add constraint uq_medicine_order_tenant_id_admission unique (tenant_id, id, admission_id);

-- 2. ipd.dose_administration: tenant-owned, append-only; one row per recorded dose (order + slot). Slots are computed, never stored.
create table if not exists ipd.dose_administration (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null default platform.current_tenant()
                      constraint fk_dose_administration_tenant references platform.tenant (id),
  admission_id        uuid not null,
  order_id            uuid not null,
  client_key          uuid not null,
  slot_at             timestamptz not null,
  outcome             text not null
                      constraint ck_dose_administration_outcome check (outcome in ('GIVEN', 'HELD', 'REFUSED')),
  reason_key          text
                      constraint ck_dose_administration_reason
                      check (reason_key is null or reason_key in
                             ('PATIENT_REFUSED', 'HELD_DOCTOR', 'NIL_BY_MOUTH', 'NOT_AVAILABLE', 'AWAY_FROM_WARD')),
  recorded_by         uuid not null,
  second_check_by     uuid,
  high_alert          boolean not null,
  identity_scan       text
                      constraint ck_dose_administration_identity_scan check (char_length(identity_scan) between 1 and 40),
  identity_confirmed  boolean,
  recorded_at         timestamptz not null default clock_timestamp(),
  constraint uq_dose_administration_tenant_client_key unique (tenant_id, client_key),
  constraint uq_dose_administration_order_slot unique (tenant_id, order_id, slot_at),
  constraint fk_dose_administration_admission foreign key (tenant_id, admission_id)
    references ipd.admission (tenant_id, id),
  constraint fk_dose_administration_order foreign key (tenant_id, order_id, admission_id)
    references ipd.medicine_order (tenant_id, id, admission_id),
  -- a slot is a dose time: a whole minute
  constraint ck_dose_administration_slot_minute check (slot_at = date_trunc('minute', slot_at)),
  -- outcome and reason agree: GIVEN has no reason; REFUSED is exactly PATIENT_REFUSED; HELD is any of the other four
  constraint ck_dose_administration_reason_by_outcome check ((outcome = 'GIVEN') = (reason_key is null)),
  constraint ck_dose_administration_refused_reason
    check ((outcome = 'REFUSED') = (reason_key is not distinct from 'PATIENT_REFUSED')),
  -- second check: GIVEN only, never the person who recorded; a high-alert GIVEN always has one
  constraint ck_dose_administration_second_check_outcome check (second_check_by is null or outcome = 'GIVEN'),
  constraint ck_dose_administration_second_check_other check (second_check_by is null or second_check_by <> recorded_by),
  constraint ck_dose_administration_high_alert_second_check
    check (not (high_alert and outcome = 'GIVEN') or second_check_by is not null),
  -- identity: the scan and the nurse's statement exist exactly for a GIVEN dose
  constraint ck_dose_administration_identity_scan_by_outcome check ((outcome = 'GIVEN') = (identity_scan is not null)),
  constraint ck_dose_administration_identity_confirmed_by_outcome check ((outcome = 'GIVEN') = (identity_confirmed is true)),
  constraint ck_dose_administration_identity_confirmed_only_given check (identity_confirmed is null or outcome = 'GIVEN')
);

-- MAR of today for one admission, the tab count, and the composite FK from the child side (leading tenant_id, admission_id).
create index if not exists ix_dose_administration_tenant_admission_slot
  on ipd.dose_administration (tenant_id, admission_id, slot_at);
-- The two unique constraints above also serve: replay by client_key, "is this slot recorded", "last dose of an order"
-- (latest slot_at per order; order by slot_at desc), and the FK to the order from the child side (leading tenant_id, order_id).

-- 3. RLS (ENABLE and FORCE): select and insert for sahaj_app only. No update or delete policy.
alter table ipd.dose_administration enable row level security;
alter table ipd.dose_administration force row level security;
create policy p_dose_administration_select on ipd.dose_administration
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_dose_administration_insert on ipd.dose_administration
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());

-- 4. Grants: select, and a column insert so id, tenant_id and recorded_at (clock_timestamp()) always come from the database.
revoke all on ipd.dose_administration from sahaj_app;
revoke all on ipd.dose_administration from public;
grant select on ipd.dose_administration to sahaj_app;
grant insert (admission_id, order_id, client_key, slot_at, outcome, reason_key, recorded_by, second_check_by,
              high_alert, identity_scan, identity_confirmed)
  on ipd.dose_administration to sahaj_app;

-- 5. Triggers.
-- 5a. No update, delete or truncate for any role, the owner included (SQLSTATE 42501, "... is append-only (UPDATE refused)").
create trigger trg_dose_administration_no_update_delete
  before update or delete on ipd.dose_administration
  for each row execute function platform.deny_audit_mutation();
create trigger trg_dose_administration_no_truncate
  before truncate on ipd.dose_administration
  for each statement execute function platform.deny_audit_mutation();

-- 5b. Insert guard (backstop of the Java rules in ADR-011 D5/D6): the order is ACTIVE, the admission is ADMITTED, and the
-- high_alert snapshot equals the medicine's flag (ipd.drug.high_alert is not null). SECURITY INVOKER, fixed search_path,
-- SQLSTATE 23514, every message contains "dose". A BEFORE trigger fires before the check constraints and the foreign keys.
-- An order that is missing, belongs to another admission or is not visible (another clinic's, under RLS) is left to the
-- composite foreign key (23503). The reads take no lock: the app holds the admission row (for no key update, ADR-006 D4),
-- and a Stop updates the order only after taking the same admission row lock.
create or replace function ipd.guard_dose_administration_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_order_status text;
  v_drug_alert   boolean;
  v_adm_status   text;
begin
  select o.status, (d.high_alert is not null)
    into v_order_status, v_drug_alert
    from ipd.medicine_order o
    join ipd.drug d on d.key = o.drug_key
   where o.tenant_id = new.tenant_id and o.id = new.order_id and o.admission_id = new.admission_id;
  if not found then
    return new;
  end if;
  if v_order_status <> 'ACTIVE' then
    raise exception 'dose can be recorded only for an active medicine order'
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
  return new;
end;
$$;
revoke all on function ipd.guard_dose_administration_insert() from public;
revoke all on function ipd.guard_dose_administration_insert() from sahaj_app;
create trigger trg_dose_administration_guard_insert
  before insert on ipd.dose_administration
  for each row execute function ipd.guard_dose_administration_insert();

-- 6. Comments.
comment on table ipd.dose_administration is 'IPD medicine administration record (SAH-32, ADR-011): one row per recorded dose (order + slot_at), outcome GIVEN, HELD or REFUSED. Append-only: select and column insert only; triggers refuse update, delete and truncate; never deleted (in-patient record; retention period is the human''s). Slots are computed in Java (DoseSlots, ADR-011 D2), never stored: a slot without a row is not recorded. Actor convention: recorded_by and second_check_by are signed-in user ids, not membership ids. No patient name and no clinical free text. Volume about 150,000 rows a day at full use (100 hospitals). Starts unpartitioned. PARTITION PLAN (ADR-006 D2 procedure, ADR-011 D13): at about 30 million rows convert to range partitioning on slot_at, one partition per month (about 4.5 million rows each): (1) create the unique index (id, slot_at) concurrently on the old table; (2) create the partitioned table with the same columns, checks, RLS, grants and triggers and monthly partitions covering existing and the next 3 months; (3) attach or copy per month in a maintenance window; (4) swap names in one transaction; (5) primary key becomes (id, slot_at). Nothing references this table by foreign key, and uq_dose_administration_order_slot already contains slot_at so it stays a real unique index; uq_dose_administration_tenant_client_key becomes local per partition (the admission row lock and the business key stay the guard). Create next month''s partition before the 25th of each month.';
comment on column ipd.dose_administration.slot_at is 'Instant of the scheduled dose time (IST dose time converted to UTC), a whole minute. Partition key when the table is partitioned (monthly).';
comment on column ipd.dose_administration.client_key is 'Client-generated key (ADR-005, ADR-011 D8), unique per clinic; same key + same values = replay (200), the first record is returned.';
comment on column ipd.dose_administration.outcome is 'GIVEN, HELD or REFUSED. The API sends GIVEN or NOT_GIVEN; the server derives HELD or REFUSED (reason PATIENT_REFUSED = REFUSED).';
comment on column ipd.dose_administration.recorded_by is 'Signed-in user id of the nurse or owner who recorded the dose (IPD actor convention).';
comment on column ipd.dose_administration.second_check_by is 'User id of the second nurse (GIVEN of a high-alert medicine); checked by that nurse''s own staff PIN in platform.staff_pin; never the recorder.';
comment on column ipd.dose_administration.high_alert is 'Snapshot of (ipd.drug.high_alert is not null) at the time of the dose; the guard trigger requires it to equal the medicine''s flag.';
comment on column ipd.dose_administration.identity_scan is 'Normalised wristband text typed or scanned (equals the patient''s UHID, at most 40 characters), GIVEN only. Never logged or audited.';
comment on column ipd.dose_administration.identity_confirmed is 'The nurse''s statement that name and date of birth were confirmed; true for GIVEN, null otherwise.';
comment on column ipd.dose_administration.recorded_at is 'Database clock at the insert (clock_timestamp()), not the transaction start and not insertable by the app.';
comment on function ipd.guard_dose_administration_insert() is 'SAH-32 guard: a dose is recorded only for an ACTIVE order of an ADMITTED admission, and high_alert equals the medicine''s flag. SECURITY INVOKER, fixed search_path, SQLSTATE 23514, message contains "dose". Missing or invisible order is left to the composite foreign key (23503).';
comment on constraint uq_medicine_order_tenant_id_admission on ipd.medicine_order is 'SAH-32: target of the composite foreign key from ipd.dose_administration (the dose belongs to the order''s own admission).';
