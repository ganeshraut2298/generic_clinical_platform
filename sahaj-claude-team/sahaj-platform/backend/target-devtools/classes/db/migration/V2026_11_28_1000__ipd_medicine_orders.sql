-- SAH-31 · IPD medicine orders, safety checks and the pharmacist check (ADR-009 "Data needed", DEC-017). Forward-only;
-- no earlier migration is edited; nothing is dropped or renamed. All three tables are new, so every check is validated
-- normally (no NOT VALID). Nothing here is ever deleted.
-- Not in this migration: platform.staff_pin (the personal PIN of DEC-017) is a separate later migration.
-- The reference content (14 medicines, 3 interactions) is the mockup's demo content (SF_DRUGS, SF_INTER), not clinically
-- validated (OQ-31-1, blocking for go-live). A clinic cannot edit it; a change is a new migration.


-- 1. ipd.drug: global reference table (no tenant_id, no row level security, like opd.medicine). Select-only for sahaj_app.
create table if not exists ipd.drug (
  key         text primary key
              constraint ck_drug_key check (key ~ '^[a-z0-9_]{1,20}$'),
  name        text not null
              constraint uq_drug_name unique
              constraint ck_drug_name check (char_length(name) between 1 and 80),
  route       text not null
              constraint ck_drug_route check (char_length(route) between 1 and 20),
  classes     text[] not null default '{}'
              constraint ck_drug_classes check (classes <@ array['cephalosporin', 'penicillin', 'sulfa', 'antiplatelet',
                                                                   'nsaid', 'anticoag', 'insulin', 'potassium']::text[]),
  high_alert  text
              constraint ck_drug_high_alert check (high_alert is null or char_length(high_alert) between 1 and 40),
  sort_order  smallint not null
              constraint uq_drug_sort_order unique
              constraint ck_drug_sort_order check (sort_order > 0)
);

-- 2. ipd.drug_interaction: global reference table. A pair is stored once (class_a < class_b) and matched in both directions.
create table if not exists ipd.drug_interaction (
  class_a     text not null,
  class_b     text not null,
  severity    text not null
              constraint ck_drug_interaction_severity check (severity in ('MAJOR', 'MODERATE')),
  text        text not null
              constraint ck_drug_interaction_text check (char_length(text) between 1 and 200),
  sort_order  smallint not null
              constraint uq_drug_interaction_sort_order unique
              constraint ck_drug_interaction_sort_order check (sort_order > 0),
  constraint pk_drug_interaction primary key (class_a, class_b),
  constraint ck_drug_interaction_order check (class_a < class_b),
  constraint ck_drug_interaction_classes
    check (array[class_a, class_b] <@ array['cephalosporin', 'penicillin', 'sulfa', 'antiplatelet',
                                            'nsaid', 'anticoag', 'insulin', 'potassium']::text[])
);

revoke all on ipd.drug, ipd.drug_interaction from sahaj_app;
revoke all on ipd.drug, ipd.drug_interaction from public;
grant select on ipd.drug, ipd.drug_interaction to sahaj_app;

-- 3. Seed: exactly the mockup's SF_DRUGS (keys, names, routes, classes, high-alert flags; sort_order = mockup order)
-- and SF_INTER (severity Major/Moderate in upper case, same text, same order).
insert into ipd.drug (key, name, route, classes, high_alert, sort_order) values
  ('pcm',   'Tab. Paracetamol 650 mg',                     'Oral',        '{}',                   null,                       1),
  ('panto', 'Inj. Pantoprazole 40 mg',                     'IV',          '{}',                   null,                       2),
  ('ondan', 'Inj. Ondansetron 4 mg',                       'IV',          '{}',                   null,                       3),
  ('ns',    'IV Normal saline 500 ml',                     'IV',          '{}',                   null,                       4),
  ('ceft',  'Inj. Ceftriaxone 1 g',                        'IV',          '{cephalosporin}',      null,                       5),
  ('amox',  'Tab. Amoxicillin + clavulanate 625 mg',       'Oral',        '{penicillin}',         null,                       6),
  ('pip',   'Inj. Piperacillin + tazobactam 4.5 g',        'IV',          '{penicillin}',         null,                       7),
  ('cotri', 'Tab. Cotrimoxazole 960 mg',                   'Oral',        '{sulfa}',              null,                       8),
  ('asp',   'Tab. Aspirin 75 mg',                          'Oral',        '{antiplatelet}',       null,                       9),
  ('ibu',   'Tab. Ibuprofen 400 mg',                       'Oral',        '{nsaid}',              null,                      10),
  ('met',   'Tab. Metformin 500 mg',                       'Oral',        '{}',                   null,                      11),
  ('hep',   'Inj. Heparin 5,000 IU',                       'SC',          '{anticoag}',           'Anticoagulant',           12),
  ('ins',   'Inj. Regular insulin, sliding scale',         'SC',          '{insulin}',            'Insulin',                 13),
  ('kcl',   'Inj. Potassium chloride 20 mmol in 500 ml',   'IV infusion', '{potassium}',          'Concentrated electrolyte', 14)
on conflict (key) do nothing;

insert into ipd.drug_interaction (class_a, class_b, severity, text, sort_order) values
  ('anticoag',     'antiplatelet', 'MAJOR',    'Bleeding risk: heparin with aspirin',                1),
  ('anticoag',     'nsaid',        'MAJOR',    'Bleeding risk: heparin with an NSAID',               2),
  ('antiplatelet', 'nsaid',        'MODERATE', 'Stomach bleeding risk: aspirin with an NSAID',       3)
on conflict (class_a, class_b) do nothing;

-- 3b. Row level security on the two reference tables. They have no tenant_id, so the policy is not tenant-scoped: it lets
-- sahaj_app read every row (and nothing else) and lets sahaj_migrator (no BYPASSRLS, and FORCE applies to the owner) keep
-- maintaining the content by later migrations. This differs from opd.medicine (no RLS at all) on purpose: the repository
-- gates (scripts/validate.mjs db: a migration file that mentions tenant_id must enable RLS on every table it creates;
-- RowLevelSecurityCatalogIT: every table is enabled and forced unless allow-listed) then pass without an exception list.
alter table ipd.drug enable row level security;
alter table ipd.drug force row level security;
alter table ipd.drug_interaction enable row level security;
alter table ipd.drug_interaction force row level security;
create policy p_drug_select on ipd.drug for select to sahaj_app using (true);
create policy p_drug_migrator on ipd.drug for all to sahaj_migrator using (true) with check (true);
create policy p_drug_interaction_select on ipd.drug_interaction for select to sahaj_app using (true);
create policy p_drug_interaction_migrator on ipd.drug_interaction for all to sahaj_migrator using (true) with check (true);


-- 4. Helper for the warnings check: a check constraint cannot hold a subquery. Immutable, fixed search_path, SECURITY
-- INVOKER. True when the array is empty or one-dimensional with every element 1 to 300 characters and not null.
create or replace function ipd.medicine_warnings_ok(p_warnings text[])
returns boolean
language sql
immutable
parallel safe
set search_path = pg_catalog
as $$
  select (pg_catalog.array_ndims(p_warnings) is null or pg_catalog.array_ndims(p_warnings) = 1)
     and coalesce((select pg_catalog.bool_and(w is not null and pg_catalog.char_length(w) between 1 and 300)
                     from pg_catalog.unnest(p_warnings) as w), true)
$$;

-- 5. ipd.medicine_order: tenant-owned; one row per medicine ordered for an admission.
create table if not exists ipd.medicine_order (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null default platform.current_tenant()
                       constraint fk_medicine_order_tenant references platform.tenant (id),
  admission_id         uuid not null,
  client_key           uuid not null,
  drug_key             text not null
                       constraint fk_medicine_order_drug references ipd.drug (key),
  frequency_key        text not null
                       constraint ck_medicine_order_frequency check (frequency_key in ('od', 'bd', 'tds', 'q8')),
  status               text not null default 'PENDING'
                       constraint ck_medicine_order_status check (status in ('PENDING', 'ACTIVE', 'REJECTED', 'STOPPED')),
  ordered_by           uuid not null,
  ordered_at           timestamptz not null default now(),
  override_reason_key  text
                       constraint ck_medicine_order_override_reason
                       check (override_reason_key is null or override_reason_key in
                              ('BENEFIT_MONITORED', 'SPECIALIST_ADVISED', 'ALLERGY_HISTORY_CHECKED', 'CARDIOLOGY_STENT')),
  warnings             text[] not null default '{}',
  verified_by          uuid,
  verified_at          timestamptz,
  rejected_by          uuid,
  rejected_at          timestamptz,
  reject_reason_key    text
                       constraint ck_medicine_order_reject_reason
                       check (reject_reason_key is null or reject_reason_key in
                              ('ALLERGY_CONFLICT', 'INTERACTION_RISK', 'WRONG_DOSE', 'DUPLICATE_THERAPY')),
  stopped_by           uuid,
  stopped_at           timestamptz,
  version              bigint not null default 0,
  constraint uq_medicine_order_tenant_id unique (tenant_id, id),
  constraint uq_medicine_order_tenant_client_key unique (tenant_id, client_key),
  constraint fk_medicine_order_admission foreign key (tenant_id, admission_id)
    references ipd.admission (tenant_id, id),
  -- the warnings the doctor saw: at most 10, each 1 to 300 characters; present exactly when a reason was given
  constraint ck_medicine_order_warnings_count check (cardinality(warnings) <= 10),
  constraint ck_medicine_order_warnings_text check (ipd.medicine_warnings_ok(warnings)),
  constraint ck_medicine_order_override_warnings check ((cardinality(warnings) > 0) = (override_reason_key is not null)),
  -- who and when are set together
  constraint ck_medicine_order_verified_pair check ((verified_by is null) = (verified_at is null)),
  constraint ck_medicine_order_rejected_triple
    check ((rejected_by is null) = (rejected_at is null) and (rejected_by is null) = (reject_reason_key is null)),
  constraint ck_medicine_order_stopped_pair check ((stopped_by is null) = (stopped_at is null)),
  -- fields agree with the status
  constraint ck_medicine_order_fields_by_status check (
       (status = 'PENDING'  and verified_by is null and rejected_by is null and stopped_by is null)
    or (status = 'ACTIVE'   and verified_by is not null and rejected_by is null and stopped_by is null)
    or (status = 'REJECTED' and rejected_by is not null and verified_by is null and stopped_by is null)
    or (status = 'STOPPED'  and stopped_by is not null and rejected_by is null)),
  -- the person who ordered can never verify
  constraint ck_medicine_order_self_verify check (verified_by is null or verified_by <> ordered_by),
  -- time order
  constraint ck_medicine_order_verified_at check (verified_at is null or verified_at >= ordered_at),
  constraint ck_medicine_order_rejected_at check (rejected_at is null or rejected_at >= ordered_at),
  constraint ck_medicine_order_stopped_at
    check (stopped_at is null or (stopped_at >= ordered_at and (verified_at is null or stopped_at >= verified_at))),
  constraint ck_medicine_order_version check (version >= 0)
);

-- Duplicate backstop behind the admission lock: one live (PENDING or ACTIVE) order per drug per admission (23505 = code bug).
create unique index if not exists ux_medicine_order_tenant_admission_drug_live
  on ipd.medicine_order (tenant_id, admission_id, drug_key) where status in ('PENDING', 'ACTIVE');
-- EMR list (all orders of one admission in chart order), live-order read for the check, and the composite FK from the child side.
create index if not exists ix_medicine_order_tenant_admission_ordered
  on ipd.medicine_order (tenant_id, admission_id, ordered_at, id);
-- Pharmacist queue (keyset, oldest first) and its count and change token.
create index if not exists ix_medicine_order_tenant_pending
  on ipd.medicine_order (tenant_id, ordered_at, id) where status = 'PENDING';

-- 6. RLS (ENABLE and FORCE): select, insert and update policies for sahaj_app. No delete policy.
alter table ipd.medicine_order enable row level security;
alter table ipd.medicine_order force row level security;
create policy p_medicine_order_select on ipd.medicine_order
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_medicine_order_insert on ipd.medicine_order
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_medicine_order_update on ipd.medicine_order
  for update to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- 7. Grants. Insert: id, tenant_id, status (always PENDING), ordered_at (database clock) and version keep their defaults.
-- Update: only the columns a transition sets; verified_at, rejected_at, stopped_at are stamped by the guard trigger from
-- clock_timestamp() and are not granted. No delete.
revoke all on ipd.medicine_order from sahaj_app;
revoke all on ipd.medicine_order from public;
grant select on ipd.medicine_order to sahaj_app;
grant insert (admission_id, client_key, drug_key, frequency_key, ordered_by, override_reason_key, warnings)
  on ipd.medicine_order to sahaj_app;
grant update (status, verified_by, rejected_by, reject_reason_key, stopped_by, version)
  on ipd.medicine_order to sahaj_app;

-- 8. Triggers.
-- 8a. No delete, no truncate for any role, the owner included (SQLSTATE 42501, "... is append-only (DELETE refused)").
create trigger trg_medicine_order_no_delete
  before delete on ipd.medicine_order
  for each row execute function platform.deny_audit_mutation();
create trigger trg_medicine_order_no_truncate
  before truncate on ipd.medicine_order
  for each statement execute function platform.deny_audit_mutation();

-- 8b. Insert guard: a new order is PENDING and its admission is ADMITTED. SECURITY INVOKER, fixed search_path, 23514.
-- The admission read is a plain select in the caller's transaction (no lock, caller's RLS). An admission that is not
-- visible or does not exist is left to the composite foreign key (23503).
create or replace function ipd.guard_medicine_order_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_adm text;
begin
  if new.status <> 'PENDING' then
    raise exception 'medicine order must start as pending'
      using errcode = 'check_violation';
  end if;
  select a.status into v_adm
    from ipd.admission a
   where a.tenant_id = new.tenant_id and a.id = new.admission_id;
  if found and v_adm <> 'ADMITTED' then
    raise exception 'medicine order can be placed only for an admitted patient'
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_medicine_order_insert() from public;
revoke all on function ipd.guard_medicine_order_insert() from sahaj_app;
create trigger trg_medicine_order_guard_insert
  before insert on ipd.medicine_order
  for each row execute function ipd.guard_medicine_order_insert();

-- 8c. Update guard (ADR-009 D1): the order state machine. SECURITY INVOKER, fixed search_path, SQLSTATE 23514; every
-- message contains "medicine order".
--   PENDING -> ACTIVE    verified_by set (not the orderer: check);               trigger stamps verified_at
--   PENDING -> REJECTED  rejected_by and reject_reason_key set;                  trigger stamps rejected_at
--   PENDING -> STOPPED   stopped_by set;                                         trigger stamps stopped_at
--   ACTIVE  -> STOPPED   stopped_by set, verified pair kept;                     trigger stamps stopped_at
--   REJECTED, STOPPED: final, any update refused.  Any other pair and any update that keeps the status: refused.
-- Every transition: version = old + 1, the admission is ADMITTED (fail closed when it is not visible), the order fields
-- (id, tenant_id, admission_id, client_key, drug_key, frequency_key, ordered_by, ordered_at, override_reason_key,
-- warnings) never change, and the fields of the other transitions stay as they were.
create or replace function ipd.guard_medicine_order_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_adm text;
  v_at  timestamptz := clock_timestamp();
begin
  if old.status in ('REJECTED', 'STOPPED') then
    raise exception 'medicine order is closed (update refused)'
      using errcode = 'check_violation';
  end if;

  if new.id is distinct from old.id
     or new.tenant_id is distinct from old.tenant_id
     or new.admission_id is distinct from old.admission_id
     or new.client_key is distinct from old.client_key
     or new.drug_key is distinct from old.drug_key
     or new.frequency_key is distinct from old.frequency_key
     or new.ordered_by is distinct from old.ordered_by
     or new.ordered_at is distinct from old.ordered_at
     or new.override_reason_key is distinct from old.override_reason_key
     or new.warnings is distinct from old.warnings then
    raise exception 'medicine order identity and order fields cannot change'
      using errcode = 'check_violation';
  end if;

  if not ((old.status = 'PENDING' and new.status in ('ACTIVE', 'REJECTED', 'STOPPED'))
          or (old.status = 'ACTIVE' and new.status = 'STOPPED')) then
    raise exception 'medicine order status change is not allowed'
      using errcode = 'check_violation';
  end if;

  if new.version <> old.version + 1 then
    raise exception 'medicine order version must go up by exactly one with a status change'
      using errcode = 'check_violation';
  end if;

  select a.status into v_adm
    from ipd.admission a
   where a.tenant_id = new.tenant_id and a.id = new.admission_id;
  if v_adm is distinct from 'ADMITTED' then
    raise exception 'medicine order can change only while the patient is admitted'
      using errcode = 'check_violation';
  end if;

  -- fields that belong to another transition stay as they were
  if new.status <> 'ACTIVE'
     and (new.verified_by is distinct from old.verified_by or new.verified_at is distinct from old.verified_at) then
    raise exception 'medicine order verification is recorded only when it is verified'
      using errcode = 'check_violation';
  end if;
  if new.status <> 'REJECTED'
     and (new.rejected_by is distinct from old.rejected_by
          or new.rejected_at is distinct from old.rejected_at
          or new.reject_reason_key is distinct from old.reject_reason_key) then
    raise exception 'medicine order rejection is recorded only when it is rejected'
      using errcode = 'check_violation';
  end if;
  if new.status <> 'STOPPED'
     and (new.stopped_by is distinct from old.stopped_by or new.stopped_at is distinct from old.stopped_at) then
    raise exception 'medicine order stop is recorded only when it is stopped'
      using errcode = 'check_violation';
  end if;

  if new.status = 'ACTIVE' then
    if new.verified_by is null then
      raise exception 'medicine order needs a verifying user to become active'
        using errcode = 'check_violation';
    end if;
    new.verified_at := v_at;
  elsif new.status = 'REJECTED' then
    if new.rejected_by is null or new.reject_reason_key is null then
      raise exception 'medicine order needs a rejecting user and a reason to be rejected'
        using errcode = 'check_violation';
    end if;
    new.rejected_at := v_at;
  else
    if new.stopped_by is null then
      raise exception 'medicine order needs a stopping user to be stopped'
        using errcode = 'check_violation';
    end if;
    new.stopped_at := v_at;
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_medicine_order_update() from public;
revoke all on function ipd.guard_medicine_order_update() from sahaj_app;
create trigger trg_medicine_order_guard_update
  before update on ipd.medicine_order
  for each row execute function ipd.guard_medicine_order_update();

-- 9. Comments.
comment on table ipd.drug is 'IPD medicine catalogue (SAH-31, ADR-009 D3): the mockup''s 14 demo medicines with their classes, routes and high-alert flags. Global reference data, no tenant_id and no row level security, select-only for sahaj_app, changed by migration only. Demo content, not clinically validated (OQ-31-1, blocking for go-live). Not opd.medicine.';
comment on column ipd.drug.key is 'Stable key (pcm, panto, ondan, ns, ceft, amox, pip, cotri, asp, ibu, met, hep, ins, kcl) as the mockup. Referenced by ipd.medicine_order.drug_key.';
comment on column ipd.drug.classes is 'Safety classes used by the check rules: cephalosporin, penicillin, sulfa, antiplatelet, nsaid, anticoag, insulin, potassium.';
comment on column ipd.drug.high_alert is 'High-alert group (Anticoagulant, Insulin, Concentrated electrolyte) or null. High-alert medicines get a second-nurse check at administration (SAH-32).';
comment on column ipd.drug.sort_order is 'Select order = mockup order.';
comment on table ipd.drug_interaction is 'IPD drug interaction rules by class pair (SAH-31, ADR-009 D3). Stored once with class_a < class_b, matched in both directions. Global reference data, select-only for sahaj_app. Demo content from the mockup (OQ-31-1).';
comment on function ipd.medicine_warnings_ok(text[]) is 'SAH-31: check helper for ipd.medicine_order.warnings (one-dimensional, each element 1 to 300 characters, no null).';
comment on function ipd.guard_medicine_order_insert() is 'SAH-31 guard: a new medicine order is PENDING and its admission is ADMITTED (an invisible or missing admission is left to the foreign key). SQLSTATE 23514.';
comment on function ipd.guard_medicine_order_update() is 'SAH-31 guard (ADR-009 D1): PENDING->ACTIVE/REJECTED/STOPPED and ACTIVE->STOPPED only; REJECTED and STOPPED final; version exactly +1; admission must be ADMITTED; order fields never change; stamps verified_at, rejected_at, stopped_at from clock_timestamp(). SQLSTATE 23514.';
comment on table ipd.medicine_order is 'IPD medicine order (SAH-31). Lifecycle PENDING (Pharmacist check) -> ACTIVE | REJECTED | STOPPED, and ACTIVE -> STOPPED; never edited, never deleted (privilege and trigger). sahaj_app inserts only PENDING rows and updates only status, verified_by, rejected_by, reject_reason_key, stopped_by and version; the times are stamped by the guard trigger. The person who ordered can never verify (check). One live order per drug per admission (partial unique index). Holds fixed keys and the warning lines the doctor saw (clinical text: never logged, audited or put in the event log). Part of the in-patient record.';
comment on column ipd.medicine_order.client_key is 'Client-generated key, unique per clinic: a retry returns the first order (no double order).';
comment on column ipd.medicine_order.drug_key is 'Key of ipd.drug.';
comment on column ipd.medicine_order.frequency_key is 'od (Once daily), bd (Twice daily), tds (Three times daily), q8 (8-hourly). Labels and dose times are in Java.';
comment on column ipd.medicine_order.status is 'PENDING (shown as "Pharmacist check"), ACTIVE, REJECTED, STOPPED. Transitions only through the guard trigger.';
comment on column ipd.medicine_order.ordered_by is 'Signed-in user id of the ordering doctor (IPD actor convention), not the membership id. verified_by must differ.';
comment on column ipd.medicine_order.ordered_at is 'Database transaction time of the order (not insertable by the app).';
comment on column ipd.medicine_order.override_reason_key is 'The doctor''s reason for ordering despite warnings: BENEFIT_MONITORED, SPECIALIST_ADVISED, ALLERGY_HISTORY_CHECKED, CARDIOLOGY_STENT. Set exactly when warnings is not empty.';
comment on column ipd.medicine_order.warnings is 'The warning lines the doctor saw when overriding, as "Moderate: <text>" or "Major: <text>" (at most 10, each 1 to 300 characters). Clinical text: shown on the pharmacist sheet only.';
comment on column ipd.medicine_order.verified_by is 'Signed-in user id of the verifying pharmacist or owner. Never the orderer. Kept when an ACTIVE order is later stopped.';
comment on column ipd.medicine_order.verified_at is 'Database clock time of the verification (trigger-stamped, not granted to sahaj_app).';
comment on column ipd.medicine_order.rejected_by is 'User id of the rejecting pharmacist or owner.';
comment on column ipd.medicine_order.rejected_at is 'Database clock time of the rejection (trigger-stamped).';
comment on column ipd.medicine_order.reject_reason_key is 'ALLERGY_CONFLICT, INTERACTION_RISK, WRONG_DOSE, DUPLICATE_THERAPY. Set exactly when REJECTED.';
comment on column ipd.medicine_order.stopped_by is 'User id of the doctor who stopped the order.';
comment on column ipd.medicine_order.stopped_at is 'Database clock time of the stop (trigger-stamped).';
comment on column ipd.medicine_order.version is 'Rises by exactly 1 with every status change (guard trigger). Also the input of the pharmacist queue change token.';
