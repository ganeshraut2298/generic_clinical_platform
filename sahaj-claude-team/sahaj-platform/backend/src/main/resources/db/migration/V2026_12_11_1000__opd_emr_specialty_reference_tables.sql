-- SAH-49, 60 to 66, 69 to 71 (ADR-023 D4, "Data needed" items 1 to 6) · Global reference tables for the EMR specialty screens.
-- Forward-only, additive. Nothing existing is changed.
--
--   opd.emr_immunization_group    the nine IAP schedule groups of the Paediatrics screen, with the age window of each
--   opd.emr_immunization_vaccine  the 30 vaccines of those groups
--   opd.emr_growth_band           growth bands by measure and age (today: the weight bands drawn for an 18-month child)
--   opd.emr_milestone             developmental milestones by age band (today: 18 months)
--   opd.emr_joint_item            joint reference: range of motion normals, muscle groups, special tests (today: the knee)
--   opd.emr_chemo_protocol        the four drawn chemotherapy protocols with drugs, doses per m2 and pre-medicines (IMMUTABLE)
--
-- Rules for every table in this file (same kind as opd.medicine and opd.emr_starter_layout):
--   * Global reference data: no clinic owns a row, so no clinic column and no row level security.
--   * sahaj_app gets SELECT only. Rows change by migration only. A write by the application is refused (42501).
--   * Seed rows are in V2026_12_11_1010. Every value is "value from the EMR mockup, accepted by the human in DEC-039,
--     not clinically reviewed". A clinician must review the lists before real use.
-- Where a rule is arithmetic (thresholds that drive a computed result) it lives in code, not here (ADR-023 D4).

-- ---------------------------------------------------------------------------------------------
-- 1. opd.emr_immunization_group (ADR-023 D4, H9): the nine schedule rows. The window is the age at which a vaccine of the
--    group is "due today"; its end is exclusive. After the end, a vaccine not given is "overdue". The unit of the two ends may
--    differ (14 weeks to 6 months), so the check compares them in days (week = 7, month = 30), used only to refuse a window
--    that is empty or reversed. The windows are the analyst's reading of the mockup (ADR-023 H9, accepted in DEC-044).
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_immunization_group (
  group_key          text not null
                     constraint ck_emr_immunization_group_key check (group_key ~ '^[a-z][a-z0-9_]{1,39}$'),
  label              text not null
                     constraint ck_emr_immunization_group_label check (char_length(btrim(label)) between 1 and 40),
  position           integer not null
                     constraint ck_emr_immunization_group_position check (position >= 1),
  window_from_value  integer not null
                     constraint ck_emr_immunization_group_from_value check (window_from_value >= 0),
  window_from_unit   text not null
                     constraint ck_emr_immunization_group_from_unit check (window_from_unit in ('DAYS', 'WEEKS', 'MONTHS')),
  window_to_value    integer not null
                     constraint ck_emr_immunization_group_to_value check (window_to_value > 0),
  window_to_unit     text not null
                     constraint ck_emr_immunization_group_to_unit check (window_to_unit in ('DAYS', 'WEEKS', 'MONTHS')),
  created_at         timestamptz not null default now(),
  constraint pk_emr_immunization_group primary key (group_key),
  constraint uq_emr_immunization_group_position unique (position),
  constraint ck_emr_immunization_group_window_order check (
    window_from_value * (case window_from_unit when 'DAYS' then 1 when 'WEEKS' then 7 else 30 end)
    < window_to_value * (case window_to_unit when 'DAYS' then 1 when 'WEEKS' then 7 else 30 end))
);

-- ---------------------------------------------------------------------------------------------
-- 2. opd.emr_immunization_vaccine: one row per vaccine of a group, in the order drawn. vaccine_key is permanent (a record
--    stores it as "given"). The unique key (group_key, position) also serves "the vaccines of a group in order".
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_immunization_vaccine (
  vaccine_key  text not null
               constraint ck_emr_immunization_vaccine_key check (vaccine_key ~ '^[a-z][a-z0-9_]{1,39}$'),
  group_key    text not null,
  label        text not null
               constraint ck_emr_immunization_vaccine_label check (char_length(btrim(label)) between 1 and 40),
  position     integer not null
               constraint ck_emr_immunization_vaccine_position check (position >= 1),
  created_at   timestamptz not null default now(),
  constraint pk_emr_immunization_vaccine primary key (vaccine_key),
  constraint uq_emr_immunization_vaccine_position unique (group_key, position),
  constraint fk_emr_immunization_vaccine_group foreign key (group_key) references opd.emr_immunization_group (group_key)
);

-- ---------------------------------------------------------------------------------------------
-- 3. opd.emr_growth_band (ADR-023 D4): the note for a measured value is the first band (by position) of the measure whose age
--    range holds the child's age in months and whose upper_exclusive is above the value; upper_exclusive null = no upper bound.
--    sex null = either. age_months_to is inclusive. WHO tables load into this same table when the human supplies the source
--    (OQ-45-32), as a new migration. Only the weight bands drawn for 18 months are seeded: the length and head circumference
--    centile notes are fixed text in the mockup, independent of the value, and the human decided to hide them until real
--    WHO tables exist (DEC-044, H8), so no LENGTH or HEAD_CIRC row is seeded.
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_growth_band (
  id                uuid not null default gen_random_uuid(),
  measure           text not null
                    constraint ck_emr_growth_band_measure check (measure in ('WEIGHT', 'LENGTH', 'HEAD_CIRC')),
  sex               text
                    constraint ck_emr_growth_band_sex check (sex is null or sex in ('F', 'M')),
  age_months_from   integer not null
                    constraint ck_emr_growth_band_age_from check (age_months_from between 0 and 228),
  age_months_to     integer not null
                    constraint ck_emr_growth_band_age_to check (age_months_to between 0 and 228),
  upper_exclusive   numeric
                    constraint ck_emr_growth_band_upper check (upper_exclusive is null or upper_exclusive > 0),
  label             text not null
                    constraint ck_emr_growth_band_label check (char_length(btrim(label)) between 1 and 40),
  alert             boolean not null default false,
  position          integer not null
                    constraint ck_emr_growth_band_position check (position >= 1),
  created_at        timestamptz not null default now(),
  constraint pk_emr_growth_band primary key (id),
  constraint ck_emr_growth_band_age_order check (age_months_to >= age_months_from),
  -- sex is nullable, so the key must treat two nulls as equal or it would not refuse a duplicate band
  constraint uq_emr_growth_band_position unique nulls not distinct (measure, sex, age_months_from, position)
);

-- ---------------------------------------------------------------------------------------------
-- 4. opd.emr_milestone: milestones asked at an age band (ADR-023 D4). milestone_key is permanent (a record stores it).
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_milestone (
  age_band_months  integer not null
                   constraint ck_emr_milestone_age_band check (age_band_months between 0 and 228),
  milestone_key    text not null
                   constraint ck_emr_milestone_key check (milestone_key ~ '^[a-z][a-z0-9_]{1,39}$'),
  label            text not null
                   constraint ck_emr_milestone_label check (char_length(btrim(label)) between 1 and 80),
  domain           text not null
                   constraint ck_emr_milestone_domain check (domain in ('GROSS_MOTOR', 'FINE_MOTOR', 'SOCIAL', 'LANGUAGE')),
  position         integer not null
                   constraint ck_emr_milestone_position check (position >= 1),
  created_at       timestamptz not null default now(),
  constraint pk_emr_milestone primary key (age_band_months, milestone_key),
  constraint uq_emr_milestone_position unique (age_band_months, position)
);

-- ---------------------------------------------------------------------------------------------
-- 5. opd.emr_joint_item (ADR-023 D4, SAH-63-1): reference rows of one joint. kind ROM = a movement with its normal range in
--    degrees (both ends set, from <= to; extension has normal 0 to 0); MMT = a muscle group (manual muscle testing 0 to 5,
--    right and left); SPECIAL_TEST = a named test (result cycles Not done, Positive, Negative). Normal degrees belong to ROM
--    rows only. Only the knee is drawn; any other joint is typed by the doctor and has no normal range (gap shown to the human).
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_joint_item (
  joint_key        text not null
                   constraint ck_emr_joint_item_joint_key check (joint_key ~ '^[a-z][a-z0-9_]{1,39}$'),
  kind             text not null
                   constraint ck_emr_joint_item_kind check (kind in ('ROM', 'MMT', 'SPECIAL_TEST')),
  item_key         text not null
                   constraint ck_emr_joint_item_item_key check (item_key ~ '^[a-z][a-z0-9_]{1,39}$'),
  label            text not null
                   constraint ck_emr_joint_item_label check (char_length(btrim(label)) between 1 and 60),
  normal_from_deg  numeric,
  normal_to_deg    numeric,
  position         integer not null
                   constraint ck_emr_joint_item_position check (position >= 1),
  created_at       timestamptz not null default now(),
  constraint pk_emr_joint_item primary key (joint_key, kind, item_key),
  constraint uq_emr_joint_item_position unique (joint_key, kind, position),
  constraint ck_emr_joint_item_normal check (
    case kind
      when 'ROM' then normal_from_deg is not null and normal_to_deg is not null and normal_from_deg <= normal_to_deg
      else            normal_from_deg is null and normal_to_deg is null
    end)
);

-- ---------------------------------------------------------------------------------------------
-- 6. opd.emr_chemo_protocol (ADR-023 D4, D6, H6): the chemotherapy protocol library. A chemotherapy order refers to a
--    protocol by protocol_key, and a dose is computed from these numbers, so a shipped protocol is IMMUTABLE (trigger,
--    as opd.emr_starter_layout): a changed protocol is a new protocol_key added by a migration, never an update.
--    definition (schemaVersion 1): {"schemaVersion":1,
--      "drugs":[{"drugKey","name","dosePerM2","infusionMinutes","vialStepMg","vesicant","anthracycline","note"?}],
--      "premeds":[{"key","text","minutes"}]}. The order of drugs is the order drawn (the sequence of the order sheet).
--    suggested_on_change = shown as "Suggested" when the decision is Change regimen (paclitaxel 3-weekly, as drawn).
--    A clinic's own protocols are a later tenant table (H6), not this one.
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_chemo_protocol (
  protocol_key         text not null
                       constraint ck_emr_chemo_protocol_key check (protocol_key ~ '^[A-Z][A-Z0-9_]{1,29}$'),
  label                text not null
                       constraint ck_emr_chemo_protocol_label check (char_length(btrim(label)) between 1 and 60),
  sub_line             text not null
                       constraint ck_emr_chemo_protocol_sub_line check (char_length(btrim(sub_line)) between 1 and 120),
  interval_days        integer not null
                       constraint ck_emr_chemo_protocol_interval check (interval_days between 1 and 90),
  emetic_risk          text not null
                       constraint ck_emr_chemo_protocol_emetic_risk check (emetic_risk in ('HIGH', 'MODERATE', 'LOW')),
  suggested_on_change  boolean not null default false,
  position             integer not null
                       constraint ck_emr_chemo_protocol_position check (position >= 1),
  definition           jsonb not null
                       constraint ck_emr_chemo_protocol_shape check (
                         jsonb_typeof(definition) = 'object'
                         and coalesce(jsonb_typeof(definition -> 'schemaVersion'), '') = 'number'
                         and coalesce(jsonb_typeof(definition -> 'drugs'), '') = 'array'
                         and coalesce(jsonb_typeof(definition -> 'premeds'), '') = 'array')
                       constraint ck_emr_chemo_protocol_size check (octet_length(definition::text) <= 16384),
  created_at           timestamptz not null default now(),
  constraint pk_emr_chemo_protocol primary key (protocol_key),
  constraint uq_emr_chemo_protocol_position unique (position)
);

-- Same function as the starter layouts and starter forms (V2026_12_08_1000): refuses UPDATE, DELETE and TRUNCATE for every role.
create trigger trg_emr_chemo_protocol_immutable
  before update or delete on opd.emr_chemo_protocol
  for each row execute function opd.deny_emr_immutable_change();
create trigger trg_emr_chemo_protocol_no_truncate
  before truncate on opd.emr_chemo_protocol
  for each statement execute function opd.deny_emr_immutable_change();

-- ---------------------------------------------------------------------------------------------
-- 7. Grants: the schema default privileges give sahaj_app all four commands, so take everything away first.
-- ---------------------------------------------------------------------------------------------
revoke all on opd.emr_immunization_group, opd.emr_immunization_vaccine, opd.emr_growth_band, opd.emr_milestone,
              opd.emr_joint_item, opd.emr_chemo_protocol from sahaj_app;
revoke all on opd.emr_immunization_group, opd.emr_immunization_vaccine, opd.emr_growth_band, opd.emr_milestone,
              opd.emr_joint_item, opd.emr_chemo_protocol from public;
grant select on opd.emr_immunization_group, opd.emr_immunization_vaccine, opd.emr_growth_band, opd.emr_milestone,
                opd.emr_joint_item, opd.emr_chemo_protocol to sahaj_app;

-- ---------------------------------------------------------------------------------------------
-- 8. Comments (the tag is on every table)
-- ---------------------------------------------------------------------------------------------
comment on table opd.emr_immunization_group is
  'Global read-only IAP immunization schedule groups for the Paediatrics screen (SAH-62, ADR-023 D4): nine rows with the age window in which the group is due (end exclusive). Changed by migration only. Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed. The windows are the analyst reading of the mockup (ADR-023 H9).';
comment on table opd.emr_immunization_vaccine is
  'Global read-only vaccines of the IAP schedule groups (SAH-62, ADR-023 D4): 30 rows as drawn. vaccine_key is permanent: a record stores it as given. Changed by migration only. Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed.';
comment on table opd.emr_growth_band is
  'Global read-only growth bands by measure and age in months (SAH-62, ADR-023 D4): first band whose age range holds the age and whose upper_exclusive is above the value. Today only the weight bands drawn for an 18-month child; length and head circumference notes are hidden until WHO tables exist (DEC-044). Changed by migration only. Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed.';
comment on table opd.emr_milestone is
  'Global read-only developmental milestones by age band in months (SAH-62, ADR-023 D4): six rows for 18 months as drawn. milestone_key is permanent. Changed by migration only. Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed.';
comment on table opd.emr_joint_item is
  'Global read-only joint reference for Physiotherapy (SAH-63, ADR-023 D4): range of motion rows with normal degrees, muscle groups and special tests. Only the knee is drawn; other joints are typed and have no normal range. Changed by migration only. Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed.';
comment on table opd.emr_chemo_protocol is
  'Global read-only immutable chemotherapy protocol library (SAH-65, SAH-66, ADR-023 D4, D6): four protocols as drawn (AC, paclitaxel 3-weekly, paclitaxel weekly, TC) with drugs, dose per m2, infusion minutes, vial step, vesicant and anthracycline flags, and pre-medicines. Update, delete and truncate are refused by trigger; a change is a new protocol_key. Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed. A clinical reviewer must check every dose before real use.';
comment on column opd.emr_chemo_protocol.definition is 'Json {schemaVersion 1, drugs [{drugKey, name, dosePerM2, infusionMinutes, vialStepMg, vesicant, anthracycline, note?}], premeds [{key, text, minutes}]}.';
comment on column opd.emr_growth_band.upper_exclusive is 'The band holds values below this number; null = no upper bound (the last band of an age range).';
