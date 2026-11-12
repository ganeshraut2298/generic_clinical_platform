-- SAH-43 and SAH-44 (ADR-019) · Global reference tables for the Doctor EMR. Forward-only, additive.
-- Three quick-option masters (complaints, investigations, advice), the specialty templates held as data
-- (template, sections, template chip options) and the doctor specialty to template aliases.
--
-- Rules for every table in this file:
--   * Global reference data: no clinic owns a row, so there is no tenant column and no row level security.
--     This is the same kind as opd.medicine and opd.diagnosis (SAH-7).
--   * sahaj_app gets SELECT only. Rows change by migration only. A write by the application is refused (42501).
--   * The three template tables are immutable once shipped (trigger): a change is a new version, never an update.
-- The clinic-owned EMR tables (overrides, revision, settings, usage) are in V2026_12_08_1030.
-- Seeds follow in V2026_12_08_1010 (masters) and V2026_12_08_1020 (templates and aliases).
-- Nothing here touches opd.diagnosis, opd.medicine, opd.medicine_favourite or platform.specialty_template.

-- ---------------------------------------------------------------------------------------------
-- 1. The three new masters. Same shape. The id is derived from the name in the seed (md5), so every
--    environment gets the same id for the same item, as for opd.medicine.
--    specialty_hint orders the list only (hint first when a specialty is asked for); null = general.
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_complaint (
  id              uuid primary key default gen_random_uuid(),
  name            text not null
                  constraint ck_emr_complaint_name check (char_length(btrim(name)) between 1 and 200),
  specialty_hint  text
                  constraint ck_emr_complaint_specialty_hint check (specialty_hint is null or specialty_hint in ('gp', 'dental', 'paeds', 'homeo')),
  sort_order      integer not null default 0
                  constraint ck_emr_complaint_sort_order check (sort_order >= 0),
  created_at      timestamptz not null default now()
);

create table if not exists opd.emr_investigation (
  id              uuid primary key default gen_random_uuid(),
  name            text not null
                  constraint ck_emr_investigation_name check (char_length(btrim(name)) between 1 and 200),
  specialty_hint  text
                  constraint ck_emr_investigation_specialty_hint check (specialty_hint is null or specialty_hint in ('gp', 'dental', 'paeds', 'homeo')),
  sort_order      integer not null default 0
                  constraint ck_emr_investigation_sort_order check (sort_order >= 0),
  created_at      timestamptz not null default now()
);

create table if not exists opd.emr_advice (
  id              uuid primary key default gen_random_uuid(),
  name            text not null
                  constraint ck_emr_advice_name check (char_length(btrim(name)) between 1 and 200),
  specialty_hint  text
                  constraint ck_emr_advice_specialty_hint check (specialty_hint is null or specialty_hint in ('gp', 'dental', 'paeds', 'homeo')),
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
-- 2. Templates as data (ADR-019 D4). Key = the specialty code already used by platform.specialty and the
--    clinic profile. No foreign key into platform (cross-module rule), so a check holds the four keys.
--    A version is a set of rows. The newest version per key is the one used for visits opened afterwards.
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_template (
  template_key  text not null
                constraint ck_emr_template_key check (template_key in ('gp', 'dental', 'paeds', 'homeo')),
  version       integer not null
                constraint ck_emr_template_version check (version >= 1),
  name          text not null
                constraint ck_emr_template_name check (char_length(btrim(name)) between 1 and 80),
  created_at    timestamptz not null default now(),
  constraint pk_emr_template primary key (template_key, version)
);

-- section_key is stable: a visit snapshot and a clinic override refer to it. A rename is a new template version.
-- options_source: MASTER (options_master says which), TEMPLATE (rows in emr_template_option), TARIFF (the clinic's
-- active services, SAH-7), NONE (the editor has no option list).
create table if not exists opd.emr_template_section (
  template_key     text not null,
  version          integer not null,
  section_key      text not null
                   constraint ck_emr_template_section_key check (section_key ~ '^[a-z][a-z0-9_]{1,39}$'),
  position         integer not null
                   constraint ck_emr_template_section_position check (position >= 1),
  label            text not null
                   constraint ck_emr_template_section_label check (char_length(btrim(label)) between 1 and 60),
  part             text not null
                   constraint ck_emr_template_section_part check (part in ('ASSESSMENT', 'TREATMENT')),
  required_default boolean not null default false,
  hideable         boolean not null default true,
  editor_type      text not null
                   constraint ck_emr_template_section_editor_type check (editor_type in
                     ('COMPLAINTS', 'VITALS_STEPPERS', 'SIGNS', 'SYSTEMS', 'DIAGNOSIS', 'PRESCRIPTION',
                      'TOOTH_CHART', 'TREATMENT_PLAN', 'SERVICES', 'ADVICE', 'CHIP_LIST')),
  icon_key         text
                   constraint ck_emr_template_section_icon_key check (icon_key is null or icon_key ~ '^[a-z][a-z0-9_-]{0,39}$'),
  options_source   text not null
                   constraint ck_emr_template_section_options_source check (options_source in ('MASTER', 'TEMPLATE', 'TARIFF', 'NONE')),
  options_master   text
                   constraint ck_emr_template_section_options_master check (options_master is null or options_master in
                     ('complaints', 'diagnoses', 'medicines', 'investigations', 'advice')),
  created_at       timestamptz not null default now(),
  constraint pk_emr_template_section primary key (template_key, version, section_key),
  constraint uq_emr_template_section_position unique (template_key, version, position),
  constraint fk_emr_template_section_template foreign key (template_key, version)
    references opd.emr_template (template_key, version),
  -- options_master is set exactly when the source is MASTER
  constraint ck_emr_template_section_master_iff_source check ((options_source = 'MASTER') = (options_master is not null))
);

-- Chip options of a TEMPLATE section. style ALERT is the red chip (allergy, alert). is_none marks the
-- "None" / "Nothing abnormal" option that lets a required chip list be completed with no finding (OQ-43-19).
-- A snapshot refers to these rows by (template_key, version, section_key, option_key); they are never copied.
create table if not exists opd.emr_template_option (
  template_key  text not null,
  version       integer not null,
  section_key   text not null,
  option_key    text not null
                constraint ck_emr_template_option_key check (option_key ~ '^[a-z][a-z0-9_]{0,39}$'),
  position      integer not null
                constraint ck_emr_template_option_position check (position >= 1),
  label         text not null
                constraint ck_emr_template_option_label check (char_length(btrim(label)) between 1 and 80),
  style         text not null default 'NORMAL'
                constraint ck_emr_template_option_style check (style in ('NORMAL', 'ALERT')),
  is_none       boolean not null default false,
  created_at    timestamptz not null default now(),
  constraint pk_emr_template_option primary key (template_key, version, section_key, option_key),
  constraint uq_emr_template_option_position unique (template_key, version, section_key, position),
  constraint fk_emr_template_option_section foreign key (template_key, version, section_key)
    references opd.emr_template_section (template_key, version, section_key)
);

-- At most one "None" option per section.
create unique index if not exists uq_emr_template_option_one_none
  on opd.emr_template_option (template_key, version, section_key) where is_none;

-- Doctor specialty (free text on the doctor's profile) to template. alias_normalised is the text after the
-- application's normalisation: trimmed, lower case, runs of spaces made one. The check refuses any other form,
-- so a lookup by the normalised value always finds it. The product owner may add aliases by migration.
create table if not exists opd.emr_specialty_alias (
  alias_normalised  text primary key
                    constraint ck_emr_specialty_alias_normalised check (
                      char_length(alias_normalised) between 1 and 80
                      and alias_normalised = lower(btrim(regexp_replace(alias_normalised, '\s+', ' ', 'g')))),
  template_key      text not null
                    constraint ck_emr_specialty_alias_template_key check (template_key in ('gp', 'dental', 'paeds', 'homeo')),
  created_at        timestamptz not null default now()
);

-- ---------------------------------------------------------------------------------------------
-- 3. Immutable template versions (ADR-019 D4): a shipped version is never updated or deleted, so a visit
--    snapshot can safely refer to (template_key, version, section_key) and its option list for ever.
--    Same idea as the append-only audit tables. INSERT stays open to the migration role (a new version).
--    TRUNCATE is refused too.
-- ---------------------------------------------------------------------------------------------
create or replace function opd.deny_emr_template_change()
returns trigger
language plpgsql
as $$
begin
  raise exception '% is immutable: a template version is never changed, add a new version (% refused)',
    tg_table_schema || '.' || tg_table_name, tg_op
    using errcode = 'insufficient_privilege';
end;
$$;

create trigger trg_emr_template_immutable
  before update or delete on opd.emr_template
  for each row execute function opd.deny_emr_template_change();
create trigger trg_emr_template_no_truncate
  before truncate on opd.emr_template
  for each statement execute function opd.deny_emr_template_change();

create trigger trg_emr_template_section_immutable
  before update or delete on opd.emr_template_section
  for each row execute function opd.deny_emr_template_change();
create trigger trg_emr_template_section_no_truncate
  before truncate on opd.emr_template_section
  for each statement execute function opd.deny_emr_template_change();

create trigger trg_emr_template_option_immutable
  before update or delete on opd.emr_template_option
  for each row execute function opd.deny_emr_template_change();
create trigger trg_emr_template_option_no_truncate
  before truncate on opd.emr_template_option
  for each statement execute function opd.deny_emr_template_change();

-- ---------------------------------------------------------------------------------------------
-- 4. Grants: the schema default privileges give sahaj_app all four commands, so take everything away first.
-- ---------------------------------------------------------------------------------------------
revoke all on opd.emr_complaint, opd.emr_investigation, opd.emr_advice,
              opd.emr_template, opd.emr_template_section, opd.emr_template_option,
              opd.emr_specialty_alias from sahaj_app;
revoke all on opd.emr_complaint, opd.emr_investigation, opd.emr_advice,
              opd.emr_template, opd.emr_template_section, opd.emr_template_option,
              opd.emr_specialty_alias from public;
grant select on opd.emr_complaint, opd.emr_investigation, opd.emr_advice,
                opd.emr_template, opd.emr_template_section, opd.emr_template_option,
                opd.emr_specialty_alias to sahaj_app;

-- ---------------------------------------------------------------------------------------------
-- 5. Table comments. Every table that is seeded says the seed is a starter list.
-- ---------------------------------------------------------------------------------------------
comment on table opd.emr_complaint is
  'Global read-only EMR complaint master (SAH-43). Changed by migration only. Starter list, not clinically reviewed: a clinician must review it before real use.';
comment on table opd.emr_investigation is
  'Global read-only EMR investigation master (SAH-43). Changed by migration only. Starter list, not clinically reviewed: a clinician must review it before real use.';
comment on table opd.emr_advice is
  'Global read-only EMR advice master (SAH-43). Changed by migration only. Starter list, not clinically reviewed: a clinician must review it before real use.';
comment on table opd.emr_template is
  'Global read-only EMR specialty template, one row per key and version (SAH-44). Immutable: a change is a new version. Starter list, not clinically reviewed: a clinician must review it before real use.';
comment on table opd.emr_template_section is
  'Global read-only sections of an EMR template version (SAH-44). Immutable. Section keys are permanent once a visit stores a snapshot. Starter list, not clinically reviewed: a clinician must review it before real use.';
comment on table opd.emr_template_option is
  'Global read-only chip options of a TEMPLATE section (SAH-44). Immutable. Starter list, not clinically reviewed: a clinician must review it before real use.';
comment on table opd.emr_specialty_alias is
  'Global read-only map from a doctor specialty text (trimmed, lower case, single spaces) to a template key (SAH-44, ADR-019 D6). Starter list, not clinically reviewed: the product owner extends it by migration.';
