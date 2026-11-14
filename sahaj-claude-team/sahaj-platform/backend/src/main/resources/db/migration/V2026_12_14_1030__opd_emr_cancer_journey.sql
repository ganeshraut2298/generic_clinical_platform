-- SAH-75 (ADR-026 D7, "Data needed" item 8; DEC-052 addendum OQ-75-7; contract approved in DEC-053) · Oncology cancer journey of a patient.
-- Forward-only, additive. One new clinic table. Design note: docs/database/SAH-75.md.
--   opd.emr_cancer_journey   append-only versions of a patient's cancer journey (stage, TNM, markers, Ki-67, genetics, intent, setting,
--                            line of treatment, tumour board date). Each save inserts version n + 1; the current journey is the highest
--                            version; nothing is ever updated or deleted.
--
-- Clinical data, kept for the life of the clinic. Every text value is clinical: never in a log line or an audit value (the audit
-- ONC_JOURNEY_UPDATED holds the version and the NAMES of changed fields only). The lists (stage groups, intent, setting, line 1 to 10)
-- are ours, not clinically reviewed (ADR-026 H6, DEC-053).
-- Row level security: ENABLE + FORCE, policies for sahaj_app on platform.current_tenant() for select and insert only (no update or delete
-- policy), unique key that starts with the clinic column. The reference to the patient is a composite key that includes the clinic column
-- (foreign key checks ignore row level security): (tenant_id, patient_id) on patient.patient. source_record_id (the EMR record the save
-- was made from) has no foreign key: opd.emr_record is partitioned (key id + created_at). recorded_by_* are opaque ids (cross-module).
-- Append-only is enforced three ways, as opd.emr_record_addendum: no update, delete or truncate privilege for sahaj_app, no policy for
-- them, and a trigger that refuses them for every role (the table owner included) through platform.deny_audit_mutation.
-- Rules left to the application (they depend on other rows or on today's date): the record is an OPD visit of the onco profile and still
-- DRAFT; expectedVersion equals the current highest version (409 journey-changed; the unique key refuses a racing duplicate);
-- boardDate is not more than 365 days ahead; an empty value is stored as null, never as "".

create table if not exists opd.emr_cancer_journey (
  id                          uuid primary key default gen_random_uuid(),
  tenant_id                   uuid not null default platform.current_tenant()
                              constraint fk_emr_cancer_journey_tenant references platform.tenant (id),
  patient_id                  uuid not null,
  version                     integer not null
                              constraint ck_emr_cancer_journey_version check (version >= 1),
  stage_group                 text
                              constraint ck_emr_cancer_journey_stage_group check (stage_group is null or stage_group in
                                ('0', 'I', 'IA', 'IB', 'IC', 'II', 'IIA', 'IIB', 'IIC', 'III', 'IIIA', 'IIIB', 'IIIC', 'IV', 'IVA', 'IVB', 'IVC')),
  tnm_text                    text
                              constraint ck_emr_cancer_journey_tnm_text check (tnm_text is null or char_length(btrim(tnm_text)) between 1 and 40),
  markers_text                text
                              constraint ck_emr_cancer_journey_markers_text check (markers_text is null or char_length(btrim(markers_text)) between 1 and 80),
  ki67_pct                    smallint
                              constraint ck_emr_cancer_journey_ki67_pct check (ki67_pct is null or ki67_pct between 0 and 100),
  genetics_text               text
                              constraint ck_emr_cancer_journey_genetics_text check (genetics_text is null or char_length(btrim(genetics_text)) between 1 and 60),
  intent                      text
                              constraint ck_emr_cancer_journey_intent check (intent is null or intent in ('CURATIVE', 'PALLIATIVE')),
  setting                     text
                              constraint ck_emr_cancer_journey_setting check (setting is null or setting in ('NEOADJUVANT', 'ADJUVANT', 'DEFINITIVE', 'ADVANCED')),
  line_no                     smallint
                              constraint ck_emr_cancer_journey_line_no check (line_no is null or line_no between 1 and 10),
  board_date                  date,
  source_record_id            uuid,
  recorded_by_membership_id   uuid not null,
  recorded_by_user_id         uuid not null,
  recorded_at                 timestamptz not null default clock_timestamp(),
  constraint uq_emr_cancer_journey_tenant_id unique (tenant_id, id),
  -- One row per version of a patient's journey. Also the lookup of the current journey (highest version, read by a backward scan of this
  -- index: where tenant_id = ? and patient_id = ? order by version desc limit 1) and of the history; no second index is needed.
  constraint uq_emr_cancer_journey_tenant_patient_version unique (tenant_id, patient_id, version),
  constraint fk_emr_cancer_journey_patient foreign key (tenant_id, patient_id)
    references patient.patient (tenant_id, id)
);

alter table opd.emr_cancer_journey enable row level security;
alter table opd.emr_cancer_journey force row level security;

create policy p_emr_cancer_journey_select on opd.emr_cancer_journey
  for select to sahaj_app
  using (tenant_id = platform.current_tenant());
create policy p_emr_cancer_journey_insert on opd.emr_cancer_journey
  for insert to sahaj_app
  with check (tenant_id = platform.current_tenant());

create trigger trg_emr_cancer_journey_no_update_delete
  before update or delete on opd.emr_cancer_journey
  for each row execute function platform.deny_audit_mutation();
create trigger trg_emr_cancer_journey_no_truncate
  before truncate on opd.emr_cancer_journey
  for each statement execute function platform.deny_audit_mutation();

-- Grants: the schema default privileges give sahaj_app all four commands, so take everything away first.
-- Column-level insert: id, the clinic and recorded_at keep their defaults (the clinic is never chosen by the caller).
revoke all on opd.emr_cancer_journey from sahaj_app;
revoke all on opd.emr_cancer_journey from public;
grant select on opd.emr_cancer_journey to sahaj_app;
grant insert (patient_id, version, stage_group, tnm_text, markers_text, ki67_pct, genetics_text, intent, setting, line_no,
              board_date, source_record_id, recorded_by_membership_id, recorded_by_user_id)
  on opd.emr_cancer_journey to sahaj_app;

comment on table opd.emr_cancer_journey is
  'Cancer journey of an oncology patient (SAH-75-7, ADR-026 D7): stage group, TNM, markers, Ki-67, genetics, intent, setting, line of treatment, tumour board date. Append-only versions (highest version is current): no update, delete or truncate (privilege, policy and trigger). Clinical data, never logged and never audited by value. The value lists are ours, not clinically reviewed (ADR-026 H6). A few rows per oncology patient; kept for the life of the clinic; no partitioning.';
comment on column opd.emr_cancer_journey.version is '1, 2, 3 ... per patient and clinic. A save inserts the next version; the application checks expectedVersion, the unique key refuses a race.';
comment on column opd.emr_cancer_journey.source_record_id is 'EMR record (OPD visit of the onco profile) the save was made from. No foreign key: opd.emr_record is partitioned (key id + created_at).';
comment on column opd.emr_cancer_journey.recorded_by_membership_id is 'Opaque platform.membership id of the treating doctor (cross-module, no foreign key).';
comment on column opd.emr_cancer_journey.recorded_at is 'Database time at insert (clock_timestamp), never sent by the client.';
