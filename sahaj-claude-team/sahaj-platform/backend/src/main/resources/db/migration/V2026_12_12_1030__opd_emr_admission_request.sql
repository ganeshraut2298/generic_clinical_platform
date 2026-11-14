-- SAH-67-3 (ADR-025 D10, "Data needed" item 3) · The doctor's admission request from the EMR (OQ-45-31 default).
-- Forward-only, additive. One new clinic table.
--   opd.emr_admission_request   one row per request: an OPD visit or an Emergency case, with the facts the ward needs
--
-- opd owns the row (the request is made from the EMR and holds EMR facts); ipd reads it through the opd.api port
-- EmrAdmissionRequestPort and never through a foreign key. Clinical data (diagnoses text, procedure, note): never in a log line
-- or an audit value. Never deleted: a request ends as ADMITTED (no cancel in phase 5, DEC-048 H14).
-- Row level security: ENABLE + FORCE, policy for sahaj_app on platform.current_tenant(), tenant-first indexes. References inside
-- the module or to a patient are composite keys that include the clinic column (foreign key checks ignore row level security):
-- the record on uq_emr_record_tenant_id_created_at, the visit on uq_visit_tenant_id, the patient on uq_patient_tenant_id.
-- er_case_id and admission_id are ipd ids: no foreign key (cross-module), checked by the application.
-- Rules the database enforces:
--   * encounter_type OPD_VISIT has opd_visit_id and a patient and no case; ER_ASSESSMENT has er_case_id and no visit (an Emergency
--     case has no patient until it is admitted or sent to OPD, so patient_id stays null);
--   * at most one OPEN request per record (partial unique index): a repeat is answered with the open one;
--   * ADMITTED has admission_id and admitted_at, OPEN has neither;
--   * an ADMITTED request is final: no later update (trigger), and no delete or truncate for any role.
-- request_hash is not in the ADR list: the contract answers 409 idempotency-conflict for a key reused with another body.

create table if not exists opd.emr_admission_request (
  id                          uuid primary key default gen_random_uuid(),
  tenant_id                   uuid not null default platform.current_tenant()
                              constraint fk_emr_admission_request_tenant references platform.tenant (id),
  record_id                   uuid not null,
  record_created_at           timestamptz not null,
  encounter_type              text not null
                              constraint ck_emr_admission_request_encounter_type check (encounter_type in ('OPD_VISIT', 'ER_ASSESSMENT')),
  opd_visit_id                uuid,
  er_case_id                  uuid,
  patient_id                  uuid,
  requested_by_membership_id  uuid not null,
  admission_type              text not null
                              constraint ck_emr_admission_request_type check (admission_type in ('planned', 'emergency', 'daycare')),
  planned_stay_days           smallint
                              constraint ck_emr_admission_request_stay check (planned_stay_days is null or planned_stay_days between 1 and 60),
  reason_text                 text
                              constraint ck_emr_admission_request_reason check (reason_text is null or char_length(btrim(reason_text)) between 1 and 200),
  procedure_text              text
                              constraint ck_emr_admission_request_procedure check (procedure_text is null or char_length(btrim(procedure_text)) between 1 and 200),
  note                        text
                              constraint ck_emr_admission_request_note check (note is null or char_length(btrim(note)) between 1 and 300),
  status                      text not null default 'OPEN'
                              constraint ck_emr_admission_request_status check (status in ('OPEN', 'ADMITTED')),
  admission_id                uuid,
  admitted_at                 timestamptz,
  idempotency_key             text not null
                              constraint ck_emr_admission_request_idempotency_key check (idempotency_key ~ '^[A-Za-z0-9_-]{8,64}$'),
  request_hash                text not null
                              constraint ck_emr_admission_request_hash check (request_hash ~ '^[0-9a-f]{64}$'),
  created_at                  timestamptz not null default now(),
  created_by                  uuid not null,
  updated_at                  timestamptz,
  updated_by                  uuid,
  constraint uq_emr_admission_request_tenant_id unique (tenant_id, id),
  constraint uq_emr_admission_request_tenant_idempotency unique (tenant_id, idempotency_key),
  constraint fk_emr_admission_request_record foreign key (tenant_id, record_id, record_created_at)
    references opd.emr_record (tenant_id, id, created_at),
  constraint fk_emr_admission_request_visit foreign key (tenant_id, opd_visit_id)
    references opd.visit (tenant_id, id),
  constraint fk_emr_admission_request_patient foreign key (tenant_id, patient_id)
    references patient.patient (tenant_id, id),
  constraint ck_emr_admission_request_encounter_ids check (
    case encounter_type
      when 'OPD_VISIT' then opd_visit_id is not null and er_case_id is null and patient_id is not null
      else                  er_case_id is not null and opd_visit_id is null
    end),
  constraint ck_emr_admission_request_admitted check (
    (status = 'ADMITTED') = (admission_id is not null) and (admission_id is null) = (admitted_at is null))
);

-- One open request per record: the repeat of "Send admission request" finds it.
create unique index if not exists uq_emr_admission_request_tenant_record_open
  on opd.emr_admission_request (tenant_id, record_id) where status = 'OPEN';
-- IPD "Admission requests" list: open requests of the clinic, newest first (at most 200 shown).
create index if not exists ix_emr_admission_request_tenant_open_created
  on opd.emr_admission_request (tenant_id, created_at desc) where status = 'OPEN';
-- onAdmitted: every open request of the admitted patient, and of the admitted Emergency case.
create index if not exists ix_emr_admission_request_tenant_patient_open
  on opd.emr_admission_request (tenant_id, patient_id) where status = 'OPEN' and patient_id is not null;
create index if not exists ix_emr_admission_request_tenant_er_case_open
  on opd.emr_admission_request (tenant_id, er_case_id) where status = 'OPEN' and er_case_id is not null;

alter table opd.emr_admission_request enable row level security;
alter table opd.emr_admission_request force row level security;

create policy p_emr_admission_request_tenant on opd.emr_admission_request
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- Guard: an ADMITTED request never changes again; no delete and no truncate, for every role. SQLSTATE 42501 like the other
-- immutable tables. SECURITY INVOKER, fixed search_path. The statement-level branch must not read old or new.
create or replace function opd.guard_emr_admission_request()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op in ('DELETE', 'TRUNCATE') then
    raise exception 'opd.emr_admission_request is never deleted (% refused)', tg_op
      using errcode = 'insufficient_privilege';
  end if;
  if old.status = 'ADMITTED' then
    raise exception 'opd.emr_admission_request is immutable once admitted (UPDATE refused)'
      using errcode = 'insufficient_privilege';
  end if;
  return new;
end;
$$;

revoke all on function opd.guard_emr_admission_request() from public;
revoke all on function opd.guard_emr_admission_request() from sahaj_app;

create trigger trg_emr_admission_request_guard
  before update or delete on opd.emr_admission_request
  for each row execute function opd.guard_emr_admission_request();
create trigger trg_emr_admission_request_no_truncate
  before truncate on opd.emr_admission_request
  for each statement execute function opd.guard_emr_admission_request();

-- Grants: the schema default privileges give sahaj_app all four commands, so take everything away first. No delete.
-- Insert is column-limited (id, the clinic, status OPEN and created_at keep their defaults). Updates are column-limited to what
-- onAdmitted writes: never the record, the patient, the type, the texts or the key.
revoke all on opd.emr_admission_request from sahaj_app;
revoke all on opd.emr_admission_request from public;
grant select on opd.emr_admission_request to sahaj_app;
grant insert (record_id, record_created_at, encounter_type, opd_visit_id, er_case_id, patient_id, requested_by_membership_id,
              admission_type, planned_stay_days, reason_text, procedure_text, note, idempotency_key, request_hash, created_by)
  on opd.emr_admission_request to sahaj_app;
grant update (status, admission_id, admitted_at, updated_at, updated_by)
  on opd.emr_admission_request to sahaj_app;

comment on table opd.emr_admission_request is
  'A doctor''s request, from the EMR, to admit a patient to IPD (SAH-67-3, ADR-025 D10): from an OPD visit record or an Emergency assessment record. OPEN until an admission of that patient or Emergency case is made, then ADMITTED with the admission id (no cancel in phase 5). At most one OPEN per record. ipd reads it through opd.api; er_case_id and admission_id are opaque ipd ids. Clinical text (reason_text, procedure_text, note): never logged or audited. Never deleted. Tens of rows a day per hospital.';
comment on column opd.emr_admission_request.reason_text is 'The active diagnosis names at the time of the request (at most 200 characters). Clinical text; never logged.';
comment on column opd.emr_admission_request.procedure_text is 'The booked OT procedure from the surgery plan, when any (at most 200). Clinical text; never logged.';
comment on column opd.emr_admission_request.note is 'The doctor''s note for the ward (at most 300). Clinical text; never logged or audited.';
comment on column opd.emr_admission_request.er_case_id is 'Opaque ipd.emergency_case id for an ER_ASSESSMENT request. No foreign key (cross-module).';
comment on column opd.emr_admission_request.admission_id is 'Opaque ipd.admission id, set when the request is ADMITTED. No foreign key (cross-module).';
comment on column opd.emr_admission_request.record_created_at is 'Part of the key of the partitioned opd.emr_record (id, created_at).';
