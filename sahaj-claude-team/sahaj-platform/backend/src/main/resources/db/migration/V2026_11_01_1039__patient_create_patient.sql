-- SAH-8 · patient.patient : one row per family member registered at the clinic.
-- A mobile number is shared by a family (AC2), so mobile is deliberately NOT unique on its own;
-- only (tenant_id, uhid) is unique. age/sex are mandatory per the story; area/language/allergy/
-- chronic/abha_linked are optional with sensible defaults.
-- Merge (AC5) is a soft update, never a hard delete: merged_into_id points at the surviving
-- record and the merged-away row stays in the table (excluded from lists/search by the
-- application, still resolvable by direct id). There is no DELETE grant for sahaj_app below.
create table if not exists patient.patient (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null default platform.current_tenant()
                  constraint fk_patient_tenant references platform.tenant (id),
  uhid            text not null default patient.next_uhid()
                  constraint ck_patient_uhid check (uhid ~ '^[A-Z]{2,6}-[0-9]{5,}$'),
  name            text not null
                  constraint ck_patient_name check (char_length(btrim(name)) between 1 and 120),
  age             smallint not null
                  constraint ck_patient_age check (age between 0 and 120),
  sex             char(1) not null
                  constraint ck_patient_sex check (sex in ('F', 'M', 'O')),
  mobile          char(10) not null
                  constraint ck_patient_mobile check (mobile ~ '^[6-9][0-9]{9}$'),
  area            text
                  constraint ck_patient_area check (area is null or char_length(area) <= 160),
  language        text not null default 'mr'
                  constraint ck_patient_language check (language in ('mr', 'hi', 'en')),
  allergy         text
                  constraint ck_patient_allergy check (allergy is null or char_length(allergy) <= 400),
  chronic         text
                  constraint ck_patient_chronic check (chronic is null or char_length(chronic) <= 400),
  abha_linked     boolean not null default false,
  merged_into_id  uuid
                  constraint fk_patient_merged_into references patient.patient (id),
  created_at      timestamptz not null default now(),
  created_by      uuid not null,
  updated_at      timestamptz,
  updated_by      uuid,
  version         bigint not null default 0,
  constraint ck_patient_not_self_merged check (merged_into_id is null or merged_into_id <> id)
);

-- UHID lookup and uniqueness (also serves "search by UHID" as an exact/prefix lookup).
create unique index if not exists uq_patient_tenant_uhid on patient.patient (tenant_id, uhid);
-- Family lookup: "who else is registered under this mobile number" (AC2, AC6) and the list's
-- search-by-mobile.
create index if not exists ix_patient_tenant_mobile on patient.patient (tenant_id, mobile);
-- Search-by-name on the patient list (AC3). pg_trgm gives a substring match (the mockup's search
-- box is a single free-text field, not a "starts with" field), unlike the prefix-only
-- text_pattern_ops indexes used elsewhere in this codebase (opd.medicine, opd.diagnosis); chosen
-- here because clinic staff commonly search a patient by a fragment of the name they remember.
create extension if not exists pg_trgm;
create index if not exists ix_patient_name_trgm on patient.patient using gin (name gin_trgm_ops);
-- Supports listing a tenant's patients excluding merged-away records (every list/search query
-- the application runs starts "where tenant_id = ... and merged_into_id is null").
create index if not exists ix_patient_tenant_active on patient.patient (tenant_id) where merged_into_id is null;

alter table patient.patient enable row level security;
alter table patient.patient force row level security;

create policy p_patient_tenant on patient.patient
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- No delete: registering is forever (clinical record), and merge is the soft update of
-- merged_into_id described above, never a row removal.
revoke all on patient.patient from sahaj_app;
revoke all on patient.patient from public;
grant select, insert, update on patient.patient to sahaj_app;

comment on table patient.patient is
  'One row per family member registered at the clinic (SAH-8). mobile is shared across a family and is not unique on its own; uhid is unique per tenant (patient.next_uhid()). Never hard-deleted: a merge sets merged_into_id on the losing record instead of removing it.';
