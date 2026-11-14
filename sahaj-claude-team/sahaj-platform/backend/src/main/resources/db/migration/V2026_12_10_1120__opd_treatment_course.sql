-- SAH-58 (ADR-022 D12) · Treatment course PLAN (opd). The money side (payments, receipts, credit carried to IPD) is billing's:
-- V2026_12_10_1200. Forward-only, additive.
--   opd.treatment_course_counter   per-clinic counter of the course number (TC-<n>); gaps allowed, duplicates never
--   opd.treatment_course           one course of a patient (kind, unit, name, doctor, start date, origin record)
--   opd.treatment_course_sitting   one planned, running or done sitting / visit / cycle / step / session of a course
-- Every table: the clinic column defaults to platform.current_tenant() (the application never writes it), ENABLE + FORCE row
-- level security, policy for sahaj_app on platform.current_tenant(), tenant-first indexes. References inside the module and
-- to patient.patient are composite keys including the clinic column (foreign key checks ignore row level security). Ids
-- from other modules (ipd admission, the doctor's membership) and the EMR record (partitioned: its key is id + created_at,
-- so no foreign key is possible) are opaque. Clinical plan data: never deleted (no delete grant).

-- ---------------------------------------------------------------------------------------------
-- 1. opd.treatment_course_counter: next course number per clinic. One row per clinic, created by the first course.
--    Used as:  insert ... (tenant_id, last_no) values (platform.current_tenant(), 1)
--              on conflict (tenant_id) do update set last_no = opd.treatment_course_counter.last_no + 1 returning last_no
--    The conflict update takes the row lock, so two courses started at once get two numbers.
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.treatment_course_counter (
  tenant_id  uuid not null default platform.current_tenant()
             constraint fk_treatment_course_counter_tenant references platform.tenant (id),
  last_no    integer not null
             constraint ck_treatment_course_counter_last_no check (last_no >= 0),
  constraint pk_treatment_course_counter primary key (tenant_id)
);

alter table opd.treatment_course_counter enable row level security;
alter table opd.treatment_course_counter force row level security;

create policy p_treatment_course_counter_tenant on opd.treatment_course_counter
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- ---------------------------------------------------------------------------------------------
-- 2. opd.treatment_course (D12). kind sets the unit (DENTAL Sitting, ANC Visit, CHEMO Cycle, SURGERY Step, REHAB Session):
--    the pair is a check. status is ACTIVE only in phase 3 (the check is widened by a later migration if a story adds a
--    state). The display number is TC-<course_no>. note is plain text such as "estimate signed by patient" (never logged).
--    doctor_membership_id has no foreign key (cross-module). origin_record_id + origin_record_created_at point at the EMR
--    record the course was started from (null for a course started elsewhere); both or neither.
--    idempotency_key / request_hash: POST /emr/courses is idempotent (same key and body = the first course; other body =
--    409 idempotency-conflict). request_hash is added to the ADR-022 column list because the contract needs it.
--    revision is the optimistic token (409 course-changed), +1 per change to the course or any of its sittings.
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.treatment_course (
  id                        uuid primary key default gen_random_uuid(),
  tenant_id                 uuid not null default platform.current_tenant()
                            constraint fk_treatment_course_tenant references platform.tenant (id),
  course_no                 integer not null
                            constraint ck_treatment_course_no check (course_no >= 1),
  patient_id                uuid not null,
  kind                      text not null
                            constraint ck_treatment_course_kind check (kind in ('DENTAL', 'ANC', 'CHEMO', 'SURGERY', 'REHAB')),
  unit                      text not null
                            constraint ck_treatment_course_unit check (unit in ('SITTING', 'VISIT', 'CYCLE', 'STEP', 'SESSION')),
  name                      text not null
                            constraint ck_treatment_course_name check (char_length(btrim(name)) between 1 and 120),
  note                      text
                            constraint ck_treatment_course_note check (note is null or char_length(btrim(note)) between 1 and 300),
  doctor_membership_id      uuid not null,
  started_on                date not null,
  origin_record_id          uuid,
  origin_record_created_at  timestamptz,
  status                    text not null default 'ACTIVE'
                            constraint ck_treatment_course_status check (status in ('ACTIVE')),
  idempotency_key           text
                            constraint ck_treatment_course_idempotency_key check (idempotency_key is null or idempotency_key ~ '^[A-Za-z0-9_-]{8,64}$'),
  request_hash              text
                            constraint ck_treatment_course_request_hash check (request_hash is null or char_length(request_hash) between 16 and 128),
  revision                  bigint not null default 0
                            constraint ck_treatment_course_revision check (revision >= 0),
  created_at                timestamptz not null default now(),
  created_by                uuid not null,
  updated_at                timestamptz,
  updated_by                uuid,
  constraint uq_treatment_course_tenant_no unique (tenant_id, course_no),
  constraint uq_treatment_course_tenant_id unique (tenant_id, id),
  constraint uq_treatment_course_tenant_idempotency unique (tenant_id, idempotency_key),
  constraint ck_treatment_course_kind_unit check (
    (kind, unit) in (('DENTAL', 'SITTING'), ('ANC', 'VISIT'), ('CHEMO', 'CYCLE'), ('SURGERY', 'STEP'), ('REHAB', 'SESSION'))),
  constraint ck_treatment_course_origin_pair check ((origin_record_id is null) = (origin_record_created_at is null)),
  constraint ck_treatment_course_idempotency_pair check ((idempotency_key is null) = (request_hash is null)),
  constraint fk_treatment_course_patient foreign key (tenant_id, patient_id)
    references patient.patient (tenant_id, id)
);

-- A patient's courses, newest first (GET /emr/courses?patientId=, at most 200).
create index if not exists ix_treatment_course_tenant_patient
  on opd.treatment_course (tenant_id, patient_id, created_at desc);

alter table opd.treatment_course enable row level security;
alter table opd.treatment_course force row level security;

create policy p_treatment_course_tenant on opd.treatment_course
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- ---------------------------------------------------------------------------------------------
-- 3. opd.treatment_course_sitting (D12). sitting_no is S1, S2 ... of the course, never reused (unique per course; a sitting is
--    never removed). Status (PLANNED, TODAY, DONE) is computed on read, never stored; done_at is set by the sign step
--    (SAH-51) or the discharge, not in phase 3. add_option_key is the option it came from (a fixed list in code, the
--    mockup's addOpts). items: 0 to 20 texts of 1 to 200 characters (clinical plan text, never logged). charge_paise: 0 to
--    ₹10 crore (the contract bound), whole rupees are checked by the application. note is plain text up to 300.
--    Encounter rules (checks):
--      * admission_requested is true exactly for DAY_CARE and IPD sittings (the sitting is the request, D12);
--      * an OPD visit can be linked to an OPD sitting only; an admission to a DAY_CARE or IPD sitting only; not both.
--    opd_visit_id is a composite key to opd.visit (same clinic only). ipd_admission_id is an ipd id: no foreign key.
--    idempotency_key / request_hash: POST .../sittings is idempotent (same key and body = the first sitting; other body =
--    409 idempotency-conflict). Two different adds at once are both kept: the application takes the course row lock and
--    numbers them in order (max(sitting_no) + 1); the unique key refuses a duplicate number.
-- ---------------------------------------------------------------------------------------------
create or replace function opd.treatment_sitting_items_valid(items text[])
returns boolean
language sql
immutable
as $$
  select items is not null
     and coalesce(cardinality(items), 0) <= 20
     and not exists (select 1 from unnest(items) as i where i is null or char_length(btrim(i)) not between 1 and 200)
$$;

create table if not exists opd.treatment_course_sitting (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null default platform.current_tenant()
                      constraint fk_treatment_course_sitting_tenant references platform.tenant (id),
  course_id           uuid not null,
  sitting_no          integer not null
                      constraint ck_treatment_course_sitting_no check (sitting_no >= 1),
  encounter_kind      text not null
                      constraint ck_treatment_course_sitting_encounter check (encounter_kind in ('OPD', 'DAY_CARE', 'IPD')),
  add_option_key      text not null
                      constraint ck_treatment_course_sitting_option check (add_option_key ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  planned_date        date,
  items               text[] not null default '{}'
                      constraint ck_treatment_course_sitting_items check (opd.treatment_sitting_items_valid(items)),
  charge_paise        bigint not null default 0
                      constraint ck_treatment_course_sitting_charge check (charge_paise between 0 and 10000000000),
  note                text
                      constraint ck_treatment_course_sitting_note check (note is null or char_length(btrim(note)) between 1 and 300),
  opd_visit_id        uuid,
  ipd_admission_id    uuid,
  admission_requested boolean not null default false,
  done_at             timestamptz,
  idempotency_key     text
                      constraint ck_treatment_course_sitting_idempotency_key check (idempotency_key is null or idempotency_key ~ '^[A-Za-z0-9_-]{8,64}$'),
  request_hash        text
                      constraint ck_treatment_course_sitting_request_hash check (request_hash is null or char_length(request_hash) between 16 and 128),
  created_at          timestamptz not null default now(),
  created_by          uuid not null,
  updated_at          timestamptz,
  updated_by          uuid,
  constraint uq_treatment_course_sitting_no unique (tenant_id, course_id, sitting_no),
  constraint uq_treatment_course_sitting_tenant_id unique (tenant_id, id),
  constraint uq_treatment_course_sitting_idempotency unique (tenant_id, idempotency_key),
  constraint ck_treatment_course_sitting_requested check (admission_requested = (encounter_kind in ('DAY_CARE', 'IPD'))),
  constraint ck_treatment_course_sitting_visit_is_opd check (opd_visit_id is null or encounter_kind = 'OPD'),
  constraint ck_treatment_course_sitting_admission_not_opd check (ipd_admission_id is null or encounter_kind in ('DAY_CARE', 'IPD')),
  constraint ck_treatment_course_sitting_idempotency_pair check ((idempotency_key is null) = (request_hash is null)),
  constraint fk_treatment_course_sitting_course foreign key (tenant_id, course_id)
    references opd.treatment_course (tenant_id, id),
  constraint fk_treatment_course_sitting_visit foreign key (tenant_id, opd_visit_id)
    references opd.visit (tenant_id, id)
);

-- A visit or an admission belongs to at most one sitting ("link visit" refuses a visit already linked).
create unique index if not exists uq_treatment_course_sitting_visit
  on opd.treatment_course_sitting (tenant_id, opd_visit_id) where opd_visit_id is not null;
create unique index if not exists uq_treatment_course_sitting_admission
  on opd.treatment_course_sitting (tenant_id, ipd_admission_id) where ipd_admission_id is not null;
-- (tenant_id, course_id, sitting_no) is covered by uq_treatment_course_sitting_no: the sittings of a course in order.

alter table opd.treatment_course_sitting enable row level security;
alter table opd.treatment_course_sitting force row level security;

create policy p_treatment_course_sitting_tenant on opd.treatment_course_sitting
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- ---------------------------------------------------------------------------------------------
-- 4. Grants: the schema default privileges give sahaj_app all four commands, so take everything away first.
--    No delete anywhere. Updates are column-limited: never an id, the clinic, the patient, the number, the kind or the unit.
-- ---------------------------------------------------------------------------------------------
revoke all on opd.treatment_course_counter, opd.treatment_course, opd.treatment_course_sitting from sahaj_app;
revoke all on opd.treatment_course_counter, opd.treatment_course, opd.treatment_course_sitting from public;

grant select, insert, update on opd.treatment_course_counter to sahaj_app;

grant select, insert on opd.treatment_course to sahaj_app;
grant update (status, revision, updated_at, updated_by) on opd.treatment_course to sahaj_app;

grant select, insert on opd.treatment_course_sitting to sahaj_app;
grant update (encounter_kind, add_option_key, planned_date, items, charge_paise, note, opd_visit_id, ipd_admission_id,
              admission_requested, done_at, updated_at, updated_by)
  on opd.treatment_course_sitting to sahaj_app;

comment on table opd.treatment_course_counter is 'Next treatment course number per clinic (SAH-58): TC-<last_no>. Gaps allowed, duplicates never.';
comment on table opd.treatment_course is
  'A patient''s treatment course (SAH-58, ADR-022 D12): kind and unit (DENTAL Sitting, ANC Visit, CHEMO Cycle, SURGERY Step, REHAB Session), doctor, start date, origin EMR record. The plan only: payments are billing.course_payment. Clinical plan data, never deleted. note is plain text, never logged. About tens to hundreds of rows per clinic a year.';
comment on table opd.treatment_course_sitting is
  'A sitting / visit / cycle / step / session of a treatment course (SAH-58, ADR-022 D12): encounter kind, planned date, items, charge in paise, links to an OPD visit or an IPD admission. Status is computed on read. Never deleted, number never reused. items and note are clinical plan text, never logged.';
comment on column opd.treatment_course.origin_record_id is 'EMR record the course was started from. No foreign key: opd.emr_record is partitioned (key id + created_at).';
comment on column opd.treatment_course_sitting.ipd_admission_id is 'Opaque ipd.admission id (cross-module, no foreign key). Set through TreatmentCoursePort.linkAdmission in the admission transaction.';
