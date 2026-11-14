-- SAH-43 (ADR-019) and SAH-53 (ADR-020) · Global reference tables for the Doctor EMR. Forward-only, additive.
-- Rewritten before merge on 12 Nov 2026 (DEC-039 addendum, path (a)): the withdrawn SAH-44 template tables,
-- the specialty alias table and their immutability function are not created any more.
--
--   Quick-option masters (SAH-43, kept):  opd.emr_complaint, opd.emr_investigation, opd.emr_advice
--   Section library (ADR-020 D3):          opd.emr_section, opd.emr_section_field, opd.emr_section_field_option
--   Starter departments (ADR-020 D4):      opd.emr_starter_department, opd.emr_starter_department_section,
--                                          opd.emr_starter_layout (immutable), opd.emr_department_alias
--
-- Rules for every table in this file:
--   * Global reference data: no clinic owns a row, so there is no clinic column and no row level security.
--     This is the same kind as opd.medicine and opd.diagnosis (SAH-7).
--   * sahaj_app gets SELECT only. Rows change by migration only. A write by the application is refused (42501).
--   * opd.emr_starter_layout is immutable once shipped (trigger): a change is a new layout version, never an update.
-- The clinic-owned EMR tables (departments, versions, doctors, records, usage) are in V2026_12_08_1040.
-- Seeds follow in V2026_12_08_1010 (masters), V2026_12_08_1020 (library) and V2026_12_08_1030 (starters).
-- Nothing here touches opd.diagnosis, opd.medicine, opd.medicine_favourite or platform.specialty_template.

-- ---------------------------------------------------------------------------------------------
-- 1. The three quick-option masters. Same shape. The id is derived from the name in the seed (md5), so every
--    environment gets the same id for the same item, as for opd.medicine.
--    specialty_hint orders the list only (hint first when a department is asked for); null = general.
--    It holds a starter department key (ADR-020 D13): the 15 keys of the EMR mockup DEPTS list.
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_complaint (
  id              uuid primary key default gen_random_uuid(),
  name            text not null
                  constraint ck_emr_complaint_name check (char_length(btrim(name)) between 1 and 200),
  specialty_hint  text
                  constraint ck_emr_complaint_specialty_hint check (specialty_hint is null or specialty_hint in
                    ('gm', 'dental', 'og', 'paeds', 'physio', 'ortho', 'eye', 'ent', 'derm', 'diab', 'cardio', 'ayush', 'onco', 'surgery', 'psych')),
  sort_order      integer not null default 0
                  constraint ck_emr_complaint_sort_order check (sort_order >= 0),
  created_at      timestamptz not null default now()
);

create table if not exists opd.emr_investigation (
  id              uuid primary key default gen_random_uuid(),
  name            text not null
                  constraint ck_emr_investigation_name check (char_length(btrim(name)) between 1 and 200),
  specialty_hint  text
                  constraint ck_emr_investigation_specialty_hint check (specialty_hint is null or specialty_hint in
                    ('gm', 'dental', 'og', 'paeds', 'physio', 'ortho', 'eye', 'ent', 'derm', 'diab', 'cardio', 'ayush', 'onco', 'surgery', 'psych')),
  sort_order      integer not null default 0
                  constraint ck_emr_investigation_sort_order check (sort_order >= 0),
  created_at      timestamptz not null default now()
);

-- name_mr and name_hi: the Marathi and Hindi text of an advice line for the printed advice (ADR-020 D13). Null means
-- "no translation yet": print falls back to English. The seed leaves them null (no translation is drawn in the mockup).
create table if not exists opd.emr_advice (
  id              uuid primary key default gen_random_uuid(),
  name            text not null
                  constraint ck_emr_advice_name check (char_length(btrim(name)) between 1 and 200),
  name_mr         text
                  constraint ck_emr_advice_name_mr check (name_mr is null or char_length(btrim(name_mr)) between 1 and 200),
  name_hi         text
                  constraint ck_emr_advice_name_hi check (name_hi is null or char_length(btrim(name_hi)) between 1 and 200),
  specialty_hint  text
                  constraint ck_emr_advice_specialty_hint check (specialty_hint is null or specialty_hint in
                    ('gm', 'dental', 'og', 'paeds', 'physio', 'ortho', 'eye', 'ent', 'derm', 'diab', 'cardio', 'ayush', 'onco', 'surgery', 'psych')),
  sort_order      integer not null default 0
                  constraint ck_emr_advice_sort_order check (sort_order >= 0),
  created_at      timestamptz not null default now()
);

-- One row per name, ignoring case. Also the prefix search:  where lower(name) like lower(:q) || '%' escape '\'
-- (text_pattern_ops makes LIKE 'abc%' use the index under any database collation). Same as uq_medicine_name_lower.
create unique index if not exists uq_emr_complaint_name_lower     on opd.emr_complaint     (lower(name) text_pattern_ops);
create unique index if not exists uq_emr_investigation_name_lower on opd.emr_investigation (lower(name) text_pattern_ops);
create unique index if not exists uq_emr_advice_name_lower        on opd.emr_advice        (lower(name) text_pattern_ops);

-- ---------------------------------------------------------------------------------------------
-- 2. Section library (ADR-020 D3): 100 sections, their fields and the fixed options of a field.
--    section_key is stable: a published department version and an EMR record refer to it for ever.
--    position is the order of the mockup S list (library order, not unique so a later section can be added without
--    renumbering; read with order by position, section_key); a department's own order is in
--    emr_starter_department_section.position and, for a clinic, in its department layout.
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_section (
  section_key   text not null
                constraint ck_emr_section_key check (section_key ~ '^[a-z][a-z0-9_]{1,39}$'),
  name          text not null
                constraint ck_emr_section_name check (char_length(btrim(name)) between 1 and 120),
  kind          text not null
                constraint ck_emr_section_kind check (kind in ('COMMON', 'SPECIALTY', 'COMPLIANCE')),
  description   text not null
                constraint ck_emr_section_description check (char_length(btrim(description)) between 1 and 500),
  always_shown  boolean not null default false,
  position      integer not null
                constraint ck_emr_section_position check (position >= 1),
  created_at    timestamptz not null default now(),
  constraint pk_emr_section primary key (section_key)
);

-- field_type is the closed list of the 30 types found in the mockup library (ADR-020 D3). The Form builder (SAH-59)
-- adds values with a new migration; a client shows an unknown type as "Not available yet" and never fails.
-- options_source: NONE (no list, free text), FIELD (rows in emr_section_field_option), MASTER (options_master says which
-- SAH-43 master), SERVICES (the clinic's active tariff, SAH-7).
-- columns: for a TABLE field, a json array of {key, label}; null = one column "entry".
-- min_value and max_value are null in the seed: the mockup draws no allowed range for any field.
create table if not exists opd.emr_section_field (
  section_key     text not null,
  field_key       text not null
                  constraint ck_emr_section_field_key check (field_key ~ '^[a-z][a-z0-9_]{0,39}$'),
  position        integer not null
                  constraint ck_emr_section_field_position check (position >= 1),
  label           text not null
                  constraint ck_emr_section_field_label check (char_length(btrim(label)) between 1 and 120),
  field_type      text not null
                  constraint ck_emr_section_field_type check (field_type in
                    ('TEXT', 'RICH_TEXT', 'NUMBER', 'NUMBER_UNIT', 'NUMBER_TREND', 'CALCULATED', 'FORMULA', 'RULE', 'REFERENCE',
                     'DATE', 'CALENDAR', 'TOGGLE', 'DROPDOWN', 'DROPDOWNS', 'SINGLE_CHOICE', 'MULTI_SELECT', 'CHECKLIST', 'CHIPS',
                     'SEARCH_LIST', 'TABLE', 'GRID', 'PATTERN', 'TEMPLATE', 'DOCTOR_HOSPITAL', 'CHART', 'BODY_MAP',
                     'TOOTH_PICKER', 'LIBRARY', 'FILE', 'SIGNATURE')),
  meta            text not null default ''
                  constraint ck_emr_section_field_meta check (char_length(meta) <= 200),
  required        boolean not null default false,
  options_source  text not null default 'NONE'
                  constraint ck_emr_section_field_options_source check (options_source in ('NONE', 'FIELD', 'MASTER', 'SERVICES')),
  options_master  text
                  constraint ck_emr_section_field_options_master check (options_master is null or options_master in
                    ('complaints', 'diagnoses', 'medicines', 'investigations', 'advice')),
  allow_free_text boolean not null default false,
  min_value       numeric,
  max_value       numeric,
  columns         jsonb
                  constraint ck_emr_section_field_columns check (columns is null or jsonb_typeof(columns) = 'array'),
  created_at      timestamptz not null default now(),
  constraint pk_emr_section_field primary key (section_key, field_key),
  constraint uq_emr_section_field_position unique (section_key, position),
  constraint fk_emr_section_field_section foreign key (section_key) references opd.emr_section (section_key),
  -- options_master is set exactly when the source is MASTER
  constraint ck_emr_section_field_master_iff_source check ((options_source = 'MASTER') = (options_master is not null)),
  constraint ck_emr_section_field_range check (min_value is null or max_value is null or min_value <= max_value)
);

-- Fixed options of a field with options_source FIELD. is_none marks the "None" / "Nothing abnormal" option that lets a
-- required choice field be completed when there is no finding (SAH-53-1, OQ-43-19).
create table if not exists opd.emr_section_field_option (
  section_key   text not null,
  field_key     text not null,
  option_key    text not null
                constraint ck_emr_section_field_option_key check (option_key ~ '^[a-z][a-z0-9_]{0,39}$'),
  position      integer not null
                constraint ck_emr_section_field_option_position check (position >= 1),
  label         text not null
                constraint ck_emr_section_field_option_label check (char_length(btrim(label)) between 1 and 80),
  is_none       boolean not null default false,
  created_at    timestamptz not null default now(),
  constraint pk_emr_section_field_option primary key (section_key, field_key, option_key),
  constraint uq_emr_section_field_option_position unique (section_key, field_key, position),
  constraint fk_emr_section_field_option_field foreign key (section_key, field_key)
    references opd.emr_section_field (section_key, field_key)
);

-- At most one "None" option per field.
create unique index if not exists uq_emr_section_field_option_one_none
  on opd.emr_section_field_option (section_key, field_key) where is_none;

-- ---------------------------------------------------------------------------------------------
-- 3. Starter departments (ADR-020 D4): the 15 departments of the mockup DEPTS list, their section placements
--    (341 rows) and one immutable layout document per starter version.
--    current_layout_version is the starter version used for visits opened on a never-published department. The key
--    to emr_starter_layout is deferred so a migration can add the starter and its layout in one transaction.
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_starter_department (
  starter_key             text not null
                          constraint ck_emr_starter_department_key check (starter_key in
                            ('gm', 'dental', 'og', 'paeds', 'physio', 'ortho', 'eye', 'ent', 'derm', 'diab', 'cardio', 'ayush', 'onco', 'surgery', 'psych')),
  name                    text not null
                          constraint ck_emr_starter_department_name check (char_length(btrim(name)) between 1 and 60),
  initials                text not null
                          constraint ck_emr_starter_department_initials check (initials ~ '^[A-Z0-9]{1,3}$'),
  colour                  text not null
                          constraint ck_emr_starter_department_colour check (colour ~ '^#[0-9A-F]{6}$'),
  description             text
                          constraint ck_emr_starter_department_description check (description is null or char_length(btrim(description)) between 1 and 500),
  consent_flag            boolean not null default false,
  pcpndt_flag             boolean not null default false,
  position                integer not null
                          constraint ck_emr_starter_department_position check (position >= 1),
  current_layout_version  integer not null default 1
                          constraint ck_emr_starter_department_layout_version check (current_layout_version >= 1),
  created_at              timestamptz not null default now(),
  constraint pk_emr_starter_department primary key (starter_key),
  constraint uq_emr_starter_department_position unique (position)
);

-- One row per section of a starter department. "Hide locks are data": always_shown is true only for PCPNDT Form F in
-- Obstetrics & Gynaecology (OQ-45-7, OQ-45-29 default); Allergies is locked everywhere by emr_section.always_shown.
-- Dropping the PCPNDT lock later is one migration row.
create table if not exists opd.emr_starter_department_section (
  starter_key      text not null,
  section_key      text not null,
  position         integer not null
                   constraint ck_emr_starter_department_section_position check (position >= 1),
  part             text not null
                   constraint ck_emr_starter_department_section_part check (part in ('ASSESSMENT', 'TREATMENT')),
  shown            boolean not null,
  required         boolean not null,
  print            boolean not null,
  copy_from_last   boolean not null,
  collapsed        boolean not null,
  who_doctor       boolean not null,
  who_nurse        boolean not null,
  who_reception    boolean not null,
  always_shown     boolean not null default false,
  created_at       timestamptz not null default now(),
  constraint pk_emr_starter_department_section primary key (starter_key, section_key),
  constraint uq_emr_starter_department_section_position unique (starter_key, position),
  constraint fk_emr_starter_department_section_starter foreign key (starter_key) references opd.emr_starter_department (starter_key),
  constraint fk_emr_starter_department_section_section foreign key (section_key) references opd.emr_section (section_key),
  -- a hidden section is never required (SAH-53-2); a locked section is always shown
  constraint ck_emr_starter_department_section_hidden_not_required check (shown or not required),
  constraint ck_emr_starter_department_section_lock_is_shown check (shown or not always_shown)
);

-- The full starter layout as one json document (schemaVersion 1, ADR-020 D6 shape: every section with its settings and a
-- copy of its definition and fields). Visits opened on a never-published department point to it. Immutable: a change to
-- the library or to a starter adds layout_version + 1; visits already opened keep the version they point to.
create table if not exists opd.emr_starter_layout (
  starter_key     text not null,
  layout_version  integer not null
                  constraint ck_emr_starter_layout_version check (layout_version >= 1),
  layout          jsonb not null
                  constraint ck_emr_starter_layout_shape check (
                    jsonb_typeof(layout) = 'object'
                    and coalesce(jsonb_typeof(layout -> 'schemaVersion'), '') = 'number'
                    and coalesce(jsonb_typeof(layout -> 'sections'), '') = 'array')
                  constraint ck_emr_starter_layout_size check (octet_length(layout::text) <= 524288),
  created_at      timestamptz not null default now(),
  constraint pk_emr_starter_layout primary key (starter_key, layout_version),
  constraint fk_emr_starter_layout_starter foreign key (starter_key) references opd.emr_starter_department (starter_key)
);

alter table opd.emr_starter_department
  add constraint fk_emr_starter_department_layout foreign key (starter_key, current_layout_version)
  references opd.emr_starter_layout (starter_key, layout_version) deferrable initially deferred;

-- Doctor specialty (free text on the doctor's profile) to starter department (ADR-020 D7). alias_normalised is the text
-- after the application's normalisation: trimmed, lower case, runs of spaces made one. The check refuses any other form,
-- so a lookup by the normalised value always finds it. The product owner may add aliases by migration. There is no alias
-- for homeopath (SAH-53-1).
create table if not exists opd.emr_department_alias (
  alias_normalised  text not null
                    constraint ck_emr_department_alias_normalised check (
                      char_length(alias_normalised) between 1 and 80
                      and alias_normalised = lower(btrim(regexp_replace(alias_normalised, '\s+', ' ', 'g')))),
  starter_key       text not null,
  created_at        timestamptz not null default now(),
  constraint pk_emr_department_alias primary key (alias_normalised),
  constraint fk_emr_department_alias_starter foreign key (starter_key) references opd.emr_starter_department (starter_key)
);

-- ---------------------------------------------------------------------------------------------
-- 4. Immutability of the starter layouts (ADR-020 D2): a shipped layout version is never updated or deleted, so a visit
--    can point to (starter_key, layout_version) for ever. INSERT stays open to the migration role (a new version).
--    TRUNCATE is refused too. The same function is used by the clinic version table in V2026_12_08_1040.
--    The trigger applies to every role, the owner included.
-- ---------------------------------------------------------------------------------------------
create or replace function opd.deny_emr_immutable_change()
returns trigger
language plpgsql
as $$
begin
  raise exception '% is immutable: a published EMR layout is never changed, add a new version (% refused)',
    tg_table_schema || '.' || tg_table_name, tg_op
    using errcode = 'insufficient_privilege';
end;
$$;

create trigger trg_emr_starter_layout_immutable
  before update or delete on opd.emr_starter_layout
  for each row execute function opd.deny_emr_immutable_change();
create trigger trg_emr_starter_layout_no_truncate
  before truncate on opd.emr_starter_layout
  for each statement execute function opd.deny_emr_immutable_change();

-- ---------------------------------------------------------------------------------------------
-- 5. Grants: the schema default privileges give sahaj_app all four commands, so take everything away first.
-- ---------------------------------------------------------------------------------------------
revoke all on opd.emr_complaint, opd.emr_investigation, opd.emr_advice,
              opd.emr_section, opd.emr_section_field, opd.emr_section_field_option,
              opd.emr_starter_department, opd.emr_starter_department_section, opd.emr_starter_layout,
              opd.emr_department_alias from sahaj_app;
revoke all on opd.emr_complaint, opd.emr_investigation, opd.emr_advice,
              opd.emr_section, opd.emr_section_field, opd.emr_section_field_option,
              opd.emr_starter_department, opd.emr_starter_department_section, opd.emr_starter_layout,
              opd.emr_department_alias from public;
grant select on opd.emr_complaint, opd.emr_investigation, opd.emr_advice,
                opd.emr_section, opd.emr_section_field, opd.emr_section_field_option,
                opd.emr_starter_department, opd.emr_starter_department_section, opd.emr_starter_layout,
                opd.emr_department_alias to sahaj_app;

-- ---------------------------------------------------------------------------------------------
-- 6. Table comments. Every table that is seeded says the seed is a starter list.
-- ---------------------------------------------------------------------------------------------
comment on table opd.emr_complaint is
  'Global read-only EMR complaint master (SAH-43). Changed by migration only. specialty_hint is a starter department key (ADR-020 D13). Starter list, not clinically reviewed: a clinician must review it before real use.';
comment on table opd.emr_investigation is
  'Global read-only EMR investigation master (SAH-43). Changed by migration only. specialty_hint is a starter department key (ADR-020 D13). Starter list, not clinically reviewed: a clinician must review it before real use.';
comment on table opd.emr_advice is
  'Global read-only EMR advice master (SAH-43). Changed by migration only. specialty_hint is a starter department key; name_mr and name_hi are the optional Marathi and Hindi text (ADR-020 D13). Starter list, not clinically reviewed: a clinician must review it before real use.';
comment on table opd.emr_section is
  'Global read-only EMR section library (SAH-53, ADR-020 D3): 100 sections, kinds COMMON 14, SPECIALTY 78, COMPLIANCE 8. Changed by migration only. Section keys are permanent once a version or record refers to them. Starter list, not clinically reviewed: names and descriptions are values from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed.';
comment on table opd.emr_section_field is
  'Global read-only fields of an EMR library section (SAH-53, ADR-020 D3): label, type (30 values), meta hint, required flag, options source. Sections with no field list in the mockup carry the mockup FALLBACK fields (Findings, Template, Attachments). Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed.';
comment on table opd.emr_section_field_option is
  'Global read-only fixed options of a library field with options_source FIELD (SAH-53). Taken from list-like mockup hints; a None option is added on required multi-select and checklist fields (SAH-53-1). Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed.';
comment on table opd.emr_starter_department is
  'Global read-only starter departments (SAH-53, ADR-020 D4): the 15 departments of the EMR mockup. consent_flag turns Consent forms on at start, pcpndt_flag adds PCPNDT Form F. Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed.';
comment on table opd.emr_starter_department_section is
  'Global read-only section placements of a starter department (SAH-53, ADR-020 D4): 341 rows with position, part, Show, Required, Print, Copy from last visit, Collapsed and Who can fill at start. always_shown is the hide lock of the starter (PCPNDT Form F in og). Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed.';
comment on table opd.emr_starter_layout is
  'Global read-only immutable starter layout documents (SAH-53, ADR-020 D4, D6): one json per starter and layout_version, built from the library and the placements. Update, delete and truncate are refused by trigger. A change is a new layout_version; visits already opened keep theirs. Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed.';
comment on table opd.emr_department_alias is
  'Global read-only map from a doctor specialty text (trimmed, lower case, single spaces) to a starter department key (SAH-53, ADR-020 D7). Starter list, not clinically reviewed: the product owner extends it by migration.';
comment on column opd.emr_section.always_shown is 'True only for Allergies (OQ-45-6): the section cannot be hidden in any department.';
comment on column opd.emr_section_field.columns is 'TABLE fields only: json array of {key, label}. Null means one column "entry".';
comment on column opd.emr_starter_department_section.always_shown is 'Hide lock of this starter (PCPNDT Form F in og, OQ-45-7 and OQ-45-29 default). The lock of Allergies is on opd.emr_section.';
