-- SAH-43 (ADR-019 D10) and SAH-53 (ADR-020 D2, D5, D6, D8) · Clinic-owned EMR tables. Forward-only, additive.
-- Rewritten before merge on 12 Nov 2026 (DEC-039 addendum, path (a)): the withdrawn SAH-44 setup, override and settings
-- tables are not created any more.
--   opd.emr_item_usage          per-doctor use count of master items, for the quick-add ranking (SAH-43, kept)
--   opd.emr_department          one row per clinic department that the clinic has saved or added (draft, revision, rooms)
--   opd.emr_department_version  published department layouts: immutable, kept for ever
--   opd.emr_department_doctor   doctors assigned to a department
--   opd.emr_record              the EMR record of one encounter (OPD visit, IPD round, Emergency case): clinical, never
--                               deleted, range-partitioned by month on created_at
-- Every table: tenant_id defaults to platform.current_tenant() (the application never writes it), ENABLE + FORCE row level
-- security, policy for sahaj_app on platform.current_tenant(), first index column is tenant_id. Every reference to another
-- clinic-owned or patient row is a composite key that includes tenant_id, so a row can never point at another clinic's row
-- (foreign key checks ignore row level security). No foreign key to platform.membership or to any other module's table
-- (cross-module rule); ids from ipd are opaque.
-- The key from opd.emr_record to opd.visit is added in V2026_12_09_1000, together with the other change to opd.visit.

-- ---------------------------------------------------------------------------------------------
-- 1. opd.emr_item_usage: how often one doctor used one master item on signed visits (ADR-019 D10).
--    No patient id, no visit id, no free text: the item is named by master + item_key only. item_key is the item's uuid
--    as lower-case text (complaints, investigations, advice, medicines) or the ICD-10 code (diagnoses); the check
--    refuses anything else, so typed text cannot be stored. No foreign key to the five masters (one column) and none to
--    platform.membership (cross-module); a row whose item left its master is ignored on read.
--    Written only by the sign step through an internal service (upsert); never deleted. created_at / updated_at only:
--    the doctor is doctor_membership_id, the recorder has no other actor.
--    Upsert:  insert ... on conflict (tenant_id, doctor_membership_id, master, item_key)
--             do update set use_count = use_count + 1, last_used_at = greatest(opd.emr_item_usage.last_used_at, excluded.last_used_at)
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_item_usage (
  tenant_id            uuid not null default platform.current_tenant()
                       constraint fk_emr_item_usage_tenant references platform.tenant (id),
  doctor_membership_id uuid not null,
  master               text not null
                       constraint ck_emr_item_usage_master check (master in
                         ('complaints', 'diagnoses', 'medicines', 'investigations', 'advice')),
  item_key             text not null
                       constraint ck_emr_item_usage_item_key_length check (char_length(item_key) between 1 and 40),
  use_count            integer not null default 1
                       constraint ck_emr_item_usage_use_count check (use_count >= 1),
  last_used_at         timestamptz not null,
  created_at           timestamptz not null default now(),
  updated_at           timestamptz,
  constraint pk_emr_item_usage primary key (tenant_id, doctor_membership_id, master, item_key),
  constraint ck_emr_item_usage_item_key_shape check (
    case master
      when 'diagnoses' then item_key ~ '^[A-Z][0-9]{2}(\.[0-9A-Z]{1,4})?$'
      else item_key ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    end)
);

-- The ranking read: one doctor, one master, used items by count then recency.
create index if not exists ix_emr_item_usage_ranking
  on opd.emr_item_usage (tenant_id, doctor_membership_id, master, use_count desc, last_used_at desc);

alter table opd.emr_item_usage enable row level security;
alter table opd.emr_item_usage force row level security;

create policy p_emr_item_usage_tenant on opd.emr_item_usage
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- ---------------------------------------------------------------------------------------------
-- 2. opd.emr_department (ADR-020 D5, D6): the clinic's own row for a department. The 15 starter-backed departments exist
--    virtually (status TEMPLATE, version 0) until the clinic's first Save draft, Publish or Add department inserts the row
--    (OQ-45-11). Status is derived: TEMPLATE = no row, DRAFT = row with published_version 0, LIVE = published_version >= 1.
--    department_key: for source_kind STARTER, either the starter key (the starter-backed row; at most one per clinic by
--    uq_emr_department_tenant_key) or a server-made d_ key (a department added from that starter, SAH-54, ADR-020 D5);
--    always a server-made d_ + 10 lower-case letters or digits (never reused) for BLANK and COPY.
--    draft_layout: the settings of the one shared draft (document: schemaVersion 1 and sections with key, part, shown, required,
--    print, copy, collapsed, whoCanFill), or null when there is no saved draft. revision is the optimistic token: every Save
--    draft, Publish or Reset that changes something adds 1; a request carries the revision it read (stale = 409).
--    Two first saves of a virtual department collide on uq_emr_department_tenant_key (the application answers 409).
--    No delete and no rename in phase 1 (SAH-54 out of scope): sahaj_app may update only the draft, the published version,
--    the revision and the update stamps. At most 50 added + 15 starter departments, so up to 65 rows per clinic.
-- ---------------------------------------------------------------------------------------------
create or replace function opd.emr_room_labels_valid(labels text[])
returns boolean
language sql
immutable
as $$
  select labels is not null
     and coalesce(cardinality(labels), 0) <= 10
     and not exists (select 1 from unnest(labels) as l where l is null or char_length(btrim(l)) not between 1 and 40)
     and (select count(distinct lower(btrim(l))) from unnest(labels) as l) = coalesce(cardinality(labels), 0)
$$;

create table if not exists opd.emr_department (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null default platform.current_tenant()
                   constraint fk_emr_department_tenant references platform.tenant (id),
  department_key   text not null
                   constraint ck_emr_department_key check (department_key ~ '^[a-z][a-z0-9_]{1,39}$'),
  name             text not null
                   constraint ck_emr_department_name check (char_length(btrim(name)) between 1 and 60),
  initials         text not null
                   constraint ck_emr_department_initials check (initials ~ '^[A-Z0-9]{1,3}$'),
  colour           text not null
                   constraint ck_emr_department_colour check (colour ~ '^#[0-9A-F]{6}$'),
  starter_key      text
                   constraint ck_emr_department_starter_key check (starter_key is null or starter_key in
                     ('gm', 'dental', 'og', 'paeds', 'physio', 'ortho', 'eye', 'ent', 'derm', 'diab', 'cardio', 'ayush', 'onco', 'surgery', 'psych')),
  source_kind      text not null
                   constraint ck_emr_department_source_kind check (source_kind in ('STARTER', 'BLANK', 'COPY')),
  copied_from_key  text
                   constraint ck_emr_department_copied_from_key check (copied_from_key is null or copied_from_key ~ '^[a-z][a-z0-9_]{1,39}$'),
  room_labels      text[] not null default '{}'
                   constraint ck_emr_department_room_labels check (opd.emr_room_labels_valid(room_labels)),
  draft_layout     jsonb
                   constraint ck_emr_department_draft_shape check (
                     draft_layout is null
                     or (jsonb_typeof(draft_layout) = 'object'
                         and coalesce(jsonb_typeof(draft_layout -> 'schemaVersion'), '') = 'number'
                         and coalesce(jsonb_typeof(draft_layout -> 'sections'), '') = 'array'))
                   constraint ck_emr_department_draft_size check (draft_layout is null or octet_length(draft_layout::text) <= 65536),
  draft_saved_at   timestamptz,
  draft_saved_by   uuid,
  published_version integer not null default 0
                   constraint ck_emr_department_published_version check (published_version >= 0),
  revision         bigint not null default 0
                   constraint ck_emr_department_revision check (revision >= 0),
  created_at       timestamptz not null default now(),
  created_by       uuid not null,
  updated_at       timestamptz,
  updated_by       uuid,
  constraint uq_emr_department_tenant_key unique (tenant_id, department_key),
  constraint uq_emr_department_tenant_id unique (tenant_id, id),
  -- a draft has a time and an author, or none of the three
  constraint ck_emr_department_draft_stamp check (
    (draft_layout is null) = (draft_saved_at is null) and (draft_layout is null) = (draft_saved_by is null)),
  -- identity by source: STARTER has a starter and no copied-from, and its key is the starter key (the starter-backed row)
  -- or a server-made d_ key (a department added from a starter); BLANK and COPY always have a server-made d_ key
  constraint ck_emr_department_source check (
    case source_kind
      when 'STARTER' then starter_key is not null and copied_from_key is null
                          and (department_key = starter_key or department_key ~ '^d_[a-z0-9]{10}$')
      when 'BLANK'   then starter_key is null and copied_from_key is null and department_key ~ '^d_[a-z0-9]{10}$'
      else                copied_from_key is not null and department_key ~ '^d_[a-z0-9]{10}$'
    end)
);

-- One name per clinic, ignoring case (SAH-54). The application also refuses the 15 starter names for an added department.
create unique index if not exists uq_emr_department_tenant_name_lower on opd.emr_department (tenant_id, lower(name));

alter table opd.emr_department enable row level security;
alter table opd.emr_department force row level security;

create policy p_emr_department_tenant on opd.emr_department
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- ---------------------------------------------------------------------------------------------
-- 3. opd.emr_department_version (ADR-020 D6): one row per Publish. layout is the full layout (schemaVersion 1, every
--    section with its settings and a copy of its definition and fields from the library at that moment), so a later
--    library change never alters it. Immutable (trigger and grants) and kept for ever (OQ-45-21): signed visits point to it.
--    The next number is published_version + 1 under the department row lock; the unique key refuses a second publish of
--    the same number.
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_department_version (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null default platform.current_tenant()
                 constraint fk_emr_department_version_tenant references platform.tenant (id),
  department_id  uuid not null,
  version_no     integer not null
                 constraint ck_emr_department_version_no check (version_no >= 1),
  layout         jsonb not null
                 constraint ck_emr_department_version_shape check (
                   jsonb_typeof(layout) = 'object'
                   and coalesce(jsonb_typeof(layout -> 'schemaVersion'), '') = 'number'
                   and coalesce(jsonb_typeof(layout -> 'sections'), '') = 'array')
                 constraint ck_emr_department_version_size check (octet_length(layout::text) <= 524288),
  published_at   timestamptz not null default now(),
  published_by   uuid not null,
  constraint uq_emr_department_version_no unique (tenant_id, department_id, version_no),
  constraint uq_emr_department_version_tenant_id unique (tenant_id, id),
  constraint fk_emr_department_version_department foreign key (tenant_id, department_id)
    references opd.emr_department (tenant_id, id)
);

alter table opd.emr_department_version enable row level security;
alter table opd.emr_department_version force row level security;

-- Select and insert policies only: there is no update or delete policy, so row level security refuses them as well.
create policy p_emr_department_version_select on opd.emr_department_version
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_emr_department_version_insert on opd.emr_department_version
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());

create trigger trg_emr_department_version_immutable
  before update or delete on opd.emr_department_version
  for each row execute function opd.deny_emr_immutable_change();
create trigger trg_emr_department_version_no_truncate
  before truncate on opd.emr_department_version
  for each statement execute function opd.deny_emr_immutable_change();

-- ---------------------------------------------------------------------------------------------
-- 4. opd.emr_department_doctor (ADR-020 D5): which doctors a department is assigned to (SAH-54-3). doctor_membership_id has
--    no foreign key (cross-module); the application checks it against the clinic's active doctors at write. No screen
--    changes the list after creation, so there is no update or delete.
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_department_doctor (
  tenant_id            uuid not null default platform.current_tenant()
                       constraint fk_emr_department_doctor_tenant references platform.tenant (id),
  department_id        uuid not null,
  doctor_membership_id uuid not null,
  created_at           timestamptz not null default now(),
  created_by           uuid not null,
  constraint pk_emr_department_doctor primary key (tenant_id, department_id, doctor_membership_id),
  constraint fk_emr_department_doctor_department foreign key (tenant_id, department_id)
    references opd.emr_department (tenant_id, id)
);

-- Template resolution step 2 (ADR-020 D7): the departments of one doctor.
create index if not exists ix_emr_department_doctor_membership
  on opd.emr_department_doctor (tenant_id, doctor_membership_id);

alter table opd.emr_department_doctor enable row level security;
alter table opd.emr_department_doctor force row level security;

create policy p_emr_department_doctor_tenant on opd.emr_department_doctor
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- ---------------------------------------------------------------------------------------------
-- 5. opd.emr_record (ADR-020 D8): the EMR record of one encounter. One model for three encounter types.
--    Identity: OPD_VISIT = one record per opd.visit; IPD_ROUND = one per admission per round date (Asia/Kolkata calendar
--    day of the first open; OQ-43-27 default, still open); ER_ASSESSMENT = one per emergency case. patient_id is null only for
--    an Emergency case that is not admitted yet (ADR-005); the application sets it when the case is admitted.
--    Snapshot = a reference, not a copy: layout_kind DEPARTMENT_VERSION points to opd.emr_department_version
--    (department_version_id), STARTER_LAYOUT to opd.emr_starter_layout (starter_key + starter_layout_version). Both are
--    immutable. department_key and department_name are the values at the time of the open.
--    sections: one json document {sectionKey: {values, copiedFrom, copyConfirmed, updatedAt, updatedBy}}; its shape version is
--    schema_version (a column, because the top level keys are section keys). Validated in the application against the layout.
--    status DRAFT now; SIGNED, signed_at and signed_by are written from SAH-51. A record is never deleted (no DELETE grant).
--    Partitioning (rule: plan before 50 million rows; expected 100 to 180 million a year at target scale): monthly range
--    partitions on created_at (UTC month boundaries) from the start, plus a default partition so an insert never fails.
--    Consequences of the partition key:
--      * the primary key is (id, created_at); a lookup by id alone probes the index of every partition;
--      * a unique index must contain created_at, so "one record per encounter" cannot be a database constraint. It is kept
--        by the application: a transaction-scoped advisory lock on the encounter id, then find-or-insert (ADR-020 D8).
--        The lookup indexes below serve that find.
--      * a table that points at a record later (SAH-51 addenda) must store id and created_at.
--    Copy from last visit and Past visits read one patient's signed records of one department (ix_..._patient_signed).
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_record (
  id                        uuid not null default gen_random_uuid(),
  tenant_id                 uuid not null default platform.current_tenant()
                            constraint fk_emr_record_tenant references platform.tenant (id),
  encounter_type            text not null
                            constraint ck_emr_record_encounter_type check (encounter_type in ('OPD_VISIT', 'IPD_ROUND', 'ER_ASSESSMENT')),
  opd_visit_id              uuid,
  ipd_admission_id          uuid,
  round_date                date,
  er_case_id                uuid,
  patient_id                uuid,
  opened_by_membership_id   uuid not null,
  department_key            text not null
                            constraint ck_emr_record_department_key check (department_key ~ '^[a-z][a-z0-9_]{1,39}$'),
  department_name           text not null
                            constraint ck_emr_record_department_name check (char_length(btrim(department_name)) between 1 and 60),
  starter_key               text
                            constraint ck_emr_record_starter_key check (starter_key is null or starter_key in
                              ('gm', 'dental', 'og', 'paeds', 'physio', 'ortho', 'eye', 'ent', 'derm', 'diab', 'cardio', 'ayush', 'onco', 'surgery', 'psych')),
  layout_kind               text not null
                            constraint ck_emr_record_layout_kind check (layout_kind in ('DEPARTMENT_VERSION', 'STARTER_LAYOUT')),
  department_version_id     uuid,
  starter_layout_version    integer
                            constraint ck_emr_record_starter_layout_version check (starter_layout_version is null or starter_layout_version >= 1),
  choice_source             text not null
                            constraint ck_emr_record_choice_source check (choice_source in
                              ('DOCTOR_SPECIALTY', 'DOCTOR_DEPARTMENT', 'CLINIC_DEFAULT', 'SWITCHED')),
  status                    text not null default 'DRAFT'
                            constraint ck_emr_record_status check (status in ('DRAFT', 'SIGNED')),
  signed_at                 timestamptz,
  signed_by                 uuid,
  sections                  jsonb not null default '{}'::jsonb
                            constraint ck_emr_record_sections_shape check (jsonb_typeof(sections) = 'object')
                            constraint ck_emr_record_sections_size check (octet_length(sections::text) <= 1048576),
  schema_version            smallint not null default 1
                            constraint ck_emr_record_schema_version check (schema_version >= 1),
  ui_part                   text
                            constraint ck_emr_record_ui_part check (ui_part is null or ui_part in ('ASSESSMENT', 'TREATMENT')),
  ui_section_key            text
                            constraint ck_emr_record_ui_section_key check (ui_section_key is null or ui_section_key ~ '^[a-z][a-z0-9_]{1,39}$'),
  admission_advised         boolean not null default false,
  referral_line             text
                            constraint ck_emr_record_referral_line check (referral_line is null or char_length(btrim(referral_line)) between 1 and 300),
  revision                  bigint not null default 0
                            constraint ck_emr_record_revision check (revision >= 0),
  created_at                timestamptz not null default now(),
  created_by                uuid not null,
  updated_at                timestamptz,
  updated_by                uuid,
  constraint pk_emr_record primary key (id, created_at),
  -- exactly the ids of its encounter type are set; a patient is required unless it is an Emergency case
  constraint ck_emr_record_encounter_ids check (
    case encounter_type
      when 'OPD_VISIT'     then opd_visit_id is not null and ipd_admission_id is null and round_date is null
                                and er_case_id is null and patient_id is not null
      when 'IPD_ROUND'     then ipd_admission_id is not null and round_date is not null and opd_visit_id is null
                                and er_case_id is null and patient_id is not null
      else                      er_case_id is not null and opd_visit_id is null and ipd_admission_id is null
                                and round_date is null
    end),
  -- the layout reference matches the layout kind
  constraint ck_emr_record_layout_ref check (
    case layout_kind
      when 'DEPARTMENT_VERSION' then department_version_id is not null and starter_layout_version is null
      else                           department_version_id is null and starter_layout_version is not null and starter_key is not null
    end),
  constraint ck_emr_record_signed check (
    (status = 'SIGNED') = (signed_at is not null) and (signed_at is null) = (signed_by is null)),
  -- A referral line is stored with "Admission advised" (OPD visit, IPD round, Emergency record), or on an Emergency
  -- (ER_ASSESSMENT) record finished as refer-out, which is not an admission advice (SAH-68, DEC-048 H22). The refer-out
  -- outcome itself lives in ipd.emergency_case.status REFERRED (another module, no foreign key), so the database can only
  -- narrow this to the Emergency encounter type; the sign writes the line only for REFER_OUT.
  constraint ck_emr_record_referral_needs_advice check (
    admission_advised or referral_line is null or encounter_type = 'ER_ASSESSMENT'),
  constraint fk_emr_record_patient foreign key (tenant_id, patient_id)
    references patient.patient (tenant_id, id),
  constraint fk_emr_record_department_version foreign key (tenant_id, department_version_id)
    references opd.emr_department_version (tenant_id, id),
  constraint fk_emr_record_starter_layout foreign key (starter_key, starter_layout_version)
    references opd.emr_starter_layout (starter_key, layout_version)
) partition by range (created_at);

-- Find-or-insert of the open (one query per encounter type), also the Queue card's record id and status.
create index if not exists ix_emr_record_tenant_visit
  on opd.emr_record (tenant_id, opd_visit_id) where opd_visit_id is not null;
create index if not exists ix_emr_record_tenant_admission_round
  on opd.emr_record (tenant_id, ipd_admission_id, round_date) where ipd_admission_id is not null;
create index if not exists ix_emr_record_tenant_er_case
  on opd.emr_record (tenant_id, er_case_id) where er_case_id is not null;
-- Copy from last visit: the patient's latest signed record of one department.
create index if not exists ix_emr_record_tenant_patient_signed
  on opd.emr_record (tenant_id, patient_id, department_key, signed_at desc) where status = 'SIGNED';
-- Past visits, last visit date and visit count of a patient.
create index if not exists ix_emr_record_tenant_patient_created
  on opd.emr_record (tenant_id, patient_id, created_at desc) where patient_id is not null;

alter table opd.emr_record enable row level security;
alter table opd.emr_record force row level security;

create policy p_emr_record_tenant on opd.emr_record
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- Monthly partitions. Partitions do not inherit row level security, so each one gets the same ENABLE + FORCE + policy (a
-- direct read of a partition by the application stays tenant-safe) and no privilege for sahaj_app or PUBLIC: the application
-- only touches the parent table, whose grants apply when a row goes into or out of a partition.
-- Run by sahaj_migrator (a migration or an operations job), idempotent: it skips a month that exists.
-- Create next month's partition before the 25th of each month (see docs/database/SAH-53.md "Partition upkeep"); the default
-- partition only catches a missed month, and a month cannot be created later while the default holds rows of that month.
create or replace function opd.emr_record_ensure_partitions(p_from date, p_months integer)
returns integer
language plpgsql
as $$
declare
  m date;
  part text;
  made integer := 0;
begin
  for i in 0 .. p_months - 1 loop
    m := (date_trunc('month', p_from) + make_interval(months => i))::date;
    part := 'emr_record_' || to_char(m, 'YYYY_MM');
    if to_regclass('opd.' || part) is null then
      execute format('create table opd.%I partition of opd.emr_record for values from (%L) to (%L)',
                     part,
                     to_char(m, 'YYYY-MM-DD') || ' 00:00:00+00',
                     to_char(m + interval '1 month', 'YYYY-MM-DD') || ' 00:00:00+00');
      execute format('alter table opd.%I enable row level security', part);
      execute format('alter table opd.%I force row level security', part);
      execute format('create policy %I on opd.%I for all to sahaj_app using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant())',
                     'p_' || part || '_tenant', part);
      execute format('revoke all on opd.%I from sahaj_app', part);
      execute format('revoke all on opd.%I from public', part);
      made := made + 1;
    end if;
  end loop;
  return made;
end;
$$;

revoke all on function opd.emr_record_ensure_partitions(date, integer) from public;
revoke all on function opd.emr_record_ensure_partitions(date, integer) from sahaj_app;

-- 24 months from November 2026 (the first partition already covers the first open), then the default partition.
select opd.emr_record_ensure_partitions(date '2026-11-01', 24);

create table if not exists opd.emr_record_default partition of opd.emr_record default;
alter table opd.emr_record_default enable row level security;
alter table opd.emr_record_default force row level security;
create policy p_emr_record_default_tenant on opd.emr_record_default
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());
revoke all on opd.emr_record_default from sahaj_app;
revoke all on opd.emr_record_default from public;

-- ---------------------------------------------------------------------------------------------
-- 6. Grants: the schema default privileges give sahaj_app all four commands, so take everything away first.
--    Updates are column-level: the application can never change an id, the clinic, the encounter ids, the key or name of a
--    department, or created_at (the partition key). No delete anywhere, and no truncate or references.
-- ---------------------------------------------------------------------------------------------
revoke all on opd.emr_item_usage, opd.emr_department, opd.emr_department_version, opd.emr_department_doctor, opd.emr_record from sahaj_app;
revoke all on opd.emr_item_usage, opd.emr_department, opd.emr_department_version, opd.emr_department_doctor, opd.emr_record from public;

grant select, insert, update on opd.emr_item_usage to sahaj_app;

grant select, insert on opd.emr_department to sahaj_app;
grant update (draft_layout, draft_saved_at, draft_saved_by, published_version, revision, updated_at, updated_by)
  on opd.emr_department to sahaj_app;

grant select, insert on opd.emr_department_version to sahaj_app;
grant select, insert on opd.emr_department_doctor to sahaj_app;

grant select, insert on opd.emr_record to sahaj_app;
grant update (patient_id, department_key, department_name, starter_key, layout_kind, department_version_id,
              starter_layout_version, choice_source, status, signed_at, signed_by, sections, schema_version,
              ui_part, ui_section_key, admission_advised, referral_line, revision, updated_at, updated_by)
  on opd.emr_record to sahaj_app;

-- ---------------------------------------------------------------------------------------------
-- 7. Comments
-- ---------------------------------------------------------------------------------------------
comment on table opd.emr_item_usage is
  'Per clinic and doctor use count of EMR master items on signed visits, for quick-add ranking (SAH-43). No patient data and no free text. Written only by the sign step; never deleted.';
comment on table opd.emr_department is
  'A clinic''s EMR department (SAH-53, ADR-020 D5, D6): the shared settings draft, published version number and revision of a department the clinic saved or added. Starter-backed departments are virtual until the first save. Setup data, no patient data. No delete, no rename in phase 1. Up to 65 rows per clinic.';
comment on table opd.emr_department_version is
  'Published EMR department layouts (SAH-53, ADR-020 D6): one immutable row per Publish with the full layout and a copy of each section definition at that moment. Update, delete and truncate are refused (privilege, row level security and trigger). Kept for ever (OQ-45-21): records point to it. No patient data.';
comment on table opd.emr_department_doctor is
  'Doctors assigned to an EMR department (SAH-53, ADR-020 D5). doctor_membership_id has no foreign key (cross-module); the application checks it against the clinic''s active doctors. Insert only.';
comment on table opd.emr_record is
  'The EMR record of one encounter: OPD_VISIT, IPD_ROUND or ER_ASSESSMENT (SAH-53, ADR-020 D8). Clinical data: section values in the sections json are never logged or audited by value. Never deleted (no delete grant); the legal retention period is a human decision, kept for the life of the clinic in phase 1. Layout is a reference to an immutable department version or starter layout. Range-partitioned by month on created_at (UTC) with a default partition; create the next month before the 25th with opd.emr_record_ensure_partitions. One record per encounter is kept by the application (advisory lock on the encounter id, then find-or-insert), not by a unique index, because a unique index must contain created_at. About 100 to 180 million rows a year at 10,000 clinics.';
comment on column opd.emr_record.sections is 'Json object {sectionKey: {values, copiedFrom, copyConfirmed, updatedAt, updatedBy}}. Clinical text. Shape version in schema_version.';
comment on column opd.emr_record.created_at is 'Partition key (UTC month). Not updatable by the application.';
comment on function opd.emr_record_ensure_partitions(date, integer) is
  'Creates the missing monthly partitions of opd.emr_record from p_from for p_months months, each with row level security and no application privilege. Idempotent. Run as sahaj_migrator.';
