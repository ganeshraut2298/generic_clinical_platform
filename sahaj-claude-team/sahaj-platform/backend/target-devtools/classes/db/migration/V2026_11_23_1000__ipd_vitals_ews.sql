-- SAH-30 · IPD nursing vitals, nursing notes, early warning escalation and clinical event log (ADR-006). Forward-only.
-- Same-schema FKs are composite (tenant_id, id) -> ipd.admission (tenant_id, id): see docs/database/tenant-fk-survey.md.
-- vitals, nursing_note and clinical_event are append-only like ipd.deposit; ews_escalation is mutable only through a
-- column-level update grant and a guard trigger. Nothing here is ever deleted.
-- No foreign key points at ipd.vitals or ipd.clinical_event, so both can be range-partitioned later (comment on vitals).

-- 1. ipd.vitals (append-only): one set of five vital signs with the server-calculated score.
create table if not exists ipd.vitals (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_vitals_tenant references platform.tenant (id),
  admission_id  uuid not null,
  client_key    uuid not null,
  bp_systolic   smallint not null
                constraint ck_vitals_bp_systolic check (bp_systolic between 40 and 300),
  bp_diastolic  smallint not null
                constraint ck_vitals_bp_diastolic check (bp_diastolic between 20 and 200),
  pulse         smallint not null
                constraint ck_vitals_pulse check (pulse between 1 and 300),
  spo2          smallint not null
                constraint ck_vitals_spo2 check (spo2 between 1 and 100),
  temp_c        numeric(3,1) not null
                constraint ck_vitals_temp_c check (temp_c between 25.0 and 45.0),
  rr            smallint not null
                constraint ck_vitals_rr check (rr between 1 and 300),
  ews_score     smallint not null
                constraint ck_vitals_ews_score check (ews_score between 0 and 15),
  ews_risk      text not null
                constraint ck_vitals_ews_risk check (ews_risk in ('LOW', 'MEDIUM', 'HIGH')),
  note_added    boolean not null,
  recorded_at   timestamptz not null default now(),
  recorded_by   uuid not null,
  constraint uq_vitals_tenant_client_key unique (tenant_id, client_key),
  constraint fk_vitals_admission foreign key (tenant_id, admission_id) references ipd.admission (tenant_id, id),
  -- the total alone decides High (7 or more) and rules out Low from 5 up (Medium can also come from one parameter at 3)
  constraint ck_vitals_risk_high check ((ews_score >= 7) = (ews_risk = 'HIGH')),
  constraint ck_vitals_risk_low check (ews_score < 5 or ews_risk <> 'LOW')
);
-- Latest vitals of an admission, the sheet, the vitals-due rule and the batched load for the list.
create index if not exists ix_vitals_tenant_admission_recorded
  on ipd.vitals (tenant_id, admission_id, recorded_at desc);

-- 2. ipd.nursing_note (append-only): the optional note that came with a save.
create table if not exists ipd.nursing_note (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_nursing_note_tenant references platform.tenant (id),
  admission_id  uuid not null,
  note          text not null
                constraint ck_nursing_note_note check (char_length(note) between 1 and 1000),
  recorded_at   timestamptz not null default now(),
  recorded_by   uuid not null,
  constraint fk_nursing_note_admission foreign key (tenant_id, admission_id) references ipd.admission (tenant_id, id)
);
-- "Recent notes": the last three of an admission, newest first.
create index if not exists ix_nursing_note_tenant_admission_recorded
  on ipd.nursing_note (tenant_id, admission_id, recorded_at desc);

-- 3. ipd.ews_escalation: at most one not-DONE escalation per admission. Raised and score-raised by SAH-30; the later
-- columns (acknowledge, response, senior calls, close) are used by the Safety board story.
create table if not exists ipd.ews_escalation (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null default platform.current_tenant()
                   constraint fk_ews_escalation_tenant references platform.tenant (id),
  admission_id     uuid not null,
  ews_score        smallint not null
                   constraint ck_ews_escalation_score check (ews_score between 0 and 15),
  ews_risk         text not null
                   constraint ck_ews_escalation_risk check (ews_risk in ('MEDIUM', 'HIGH')),
  target_minutes   smallint not null
                   constraint ck_ews_escalation_target check (target_minutes in (15, 60)),
  status           text not null default 'OPEN'
                   constraint ck_ews_escalation_status check (status in ('OPEN', 'ACK', 'DONE')),
  raised_at        timestamptz not null default now(),
  raised_by        uuid not null,
  acknowledged_by  uuid,
  acknowledged_at  timestamptz,
  response_action  text,
  response_note    text,
  senior_calls     smallint not null default 0
                   constraint ck_ews_escalation_senior_calls check (senior_calls >= 0),
  closed_at        timestamptz,
  version          integer not null default 0,
  constraint fk_ews_escalation_admission foreign key (tenant_id, admission_id) references ipd.admission (tenant_id, id),
  -- High is answered within 15 minutes, Medium within 60
  constraint ck_ews_escalation_risk_target check ((ews_risk = 'HIGH') = (target_minutes = 15)),
  -- acknowledged_by and acknowledged_at are set together; a closed escalation has a closing time and only then
  constraint ck_ews_escalation_ack_pair check ((acknowledged_by is null) = (acknowledged_at is null)),
  constraint ck_ews_escalation_closed check ((status = 'DONE') = (closed_at is not null))
);
-- One open escalation per admission (OPEN or ACK). Also serves "open escalation of this admission" (lookup under the
-- admission row lock) and a clinic-wide scan of open escalations. Last defence behind the lock: second insert = 23505.
create unique index if not exists ux_ews_escalation_tenant_admission_open
  on ipd.ews_escalation (tenant_id, admission_id) where status <> 'DONE';

-- 4. ipd.clinical_event (append-only): fixed-template lines for the clinical event log. admission_id may be null
-- (composite FK, MATCH SIMPLE: a null admission_id skips the check). seq orders events that share one transaction time.
create table if not exists ipd.clinical_event (
  id            uuid primary key default gen_random_uuid(),
  seq           bigint generated always as identity,
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_clinical_event_tenant references platform.tenant (id),
  admission_id  uuid,
  kind          text not null
                constraint ck_clinical_event_kind check (kind in ('give', 'order', 'verify', 'esc', 'view', 'bg', 'deny', 'stop', 'info')),
  text          text not null
                constraint ck_clinical_event_text check (char_length(text) between 1 and 300),
  actor         uuid not null,
  at            timestamptz not null default now(),
  constraint fk_clinical_event_admission foreign key (tenant_id, admission_id) references ipd.admission (tenant_id, id) match simple
);
-- Newest events of a clinic, and newest events of one admission.
create index if not exists ix_clinical_event_tenant_at
  on ipd.clinical_event (tenant_id, at desc, seq desc);
create index if not exists ix_clinical_event_tenant_admission_at
  on ipd.clinical_event (tenant_id, admission_id, at desc, seq desc);

-- 5. RLS (ENABLE and FORCE). Select and insert policies for all four; ews_escalation also has an update policy. No delete policy.
alter table ipd.vitals enable row level security;
alter table ipd.vitals force row level security;
alter table ipd.nursing_note enable row level security;
alter table ipd.nursing_note force row level security;
alter table ipd.ews_escalation enable row level security;
alter table ipd.ews_escalation force row level security;
alter table ipd.clinical_event enable row level security;
alter table ipd.clinical_event force row level security;

create policy p_vitals_select on ipd.vitals
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_vitals_insert on ipd.vitals
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_nursing_note_select on ipd.nursing_note
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_nursing_note_insert on ipd.nursing_note
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_ews_escalation_select on ipd.ews_escalation
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_ews_escalation_insert on ipd.ews_escalation
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_ews_escalation_update on ipd.ews_escalation
  for update to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());
create policy p_clinical_event_select on ipd.clinical_event
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_clinical_event_insert on ipd.clinical_event
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());

-- 6. Grants. Column-level insert so id, seq, tenant_id, status, timestamps, senior_calls and version keep their
-- defaults (the database clock is the recording time; an escalation always starts OPEN, version 0). No delete.
revoke all on ipd.vitals, ipd.nursing_note, ipd.ews_escalation, ipd.clinical_event from sahaj_app;
revoke all on ipd.vitals, ipd.nursing_note, ipd.ews_escalation, ipd.clinical_event from public;

grant select on ipd.vitals, ipd.nursing_note, ipd.ews_escalation, ipd.clinical_event to sahaj_app;
grant insert (admission_id, client_key, bp_systolic, bp_diastolic, pulse, spo2, temp_c, rr, ews_score, ews_risk,
              note_added, recorded_by) on ipd.vitals to sahaj_app;
grant insert (admission_id, note, recorded_by) on ipd.nursing_note to sahaj_app;
grant insert (admission_id, ews_score, ews_risk, target_minutes, raised_by) on ipd.ews_escalation to sahaj_app;
grant update (ews_score, ews_risk, target_minutes, version) on ipd.ews_escalation to sahaj_app;
grant insert (admission_id, kind, text, actor) on ipd.clinical_event to sahaj_app;

-- 7. Triggers.
-- 7a. Append-only tables: update, delete and truncate denied (even for a role that gets a privilege later). SQLSTATE 42501.
create trigger trg_vitals_no_update_delete
  before update or delete on ipd.vitals
  for each row execute function platform.deny_audit_mutation();
create trigger trg_vitals_no_truncate
  before truncate on ipd.vitals
  for each statement execute function platform.deny_audit_mutation();
create trigger trg_nursing_note_no_update_delete
  before update or delete on ipd.nursing_note
  for each row execute function platform.deny_audit_mutation();
create trigger trg_nursing_note_no_truncate
  before truncate on ipd.nursing_note
  for each statement execute function platform.deny_audit_mutation();
create trigger trg_clinical_event_no_update_delete
  before update or delete on ipd.clinical_event
  for each row execute function platform.deny_audit_mutation();
create trigger trg_clinical_event_no_truncate
  before truncate on ipd.clinical_event
  for each statement execute function platform.deny_audit_mutation();

-- 7b. Escalation: no delete and no truncate.
create trigger trg_ews_escalation_no_delete
  before delete on ipd.ews_escalation
  for each row execute function platform.deny_audit_mutation();
create trigger trg_ews_escalation_no_truncate
  before truncate on ipd.ews_escalation
  for each statement execute function platform.deny_audit_mutation();

-- 7c. Escalation: update guard (backstop for the Java rule "a higher score replaces, an equal or lower one changes
-- nothing"). A DONE escalation never changes again; while open, the score and the risk never go down. Equal values pass,
-- so the Safety board story can update other columns without touching the score. SECURITY INVOKER, fixed search_path.
-- SQLSTATE 23514 (check_violation).
create or replace function ipd.guard_ews_escalation_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if old.status = 'DONE' then
    raise exception 'early warning escalation is closed (update refused)'
      using errcode = 'check_violation';
  end if;
  if new.ews_score < old.ews_score then
    raise exception 'early warning escalation score cannot go down'
      using errcode = 'check_violation';
  end if;
  if old.ews_risk = 'HIGH' and new.ews_risk <> 'HIGH' then
    raise exception 'early warning escalation risk cannot go down'
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_ews_escalation_update() from public;
revoke all on function ipd.guard_ews_escalation_update() from sahaj_app;

create trigger trg_ews_escalation_guard_update
  before update on ipd.ews_escalation
  for each row execute function ipd.guard_ews_escalation_update();

comment on function ipd.guard_ews_escalation_update() is 'SAH-30 guard: a DONE early warning escalation is never updated, and while open its score and risk never go down. SQLSTATE 23514.';
comment on table ipd.vitals is 'IPD nursing vitals (SAH-30). Append-only: no update, delete or truncate (privilege and trigger); a wrong entry is followed by a new one. Clinical data: shown on screen only, never logged or audited with values. Fastest-growing IPD table: PARTITION PLAN (ADR-006 D2): no foreign key points at this table and the only unique index besides the primary key is (tenant_id, client_key); at about 30 million rows create ipd.vitals_p partitioned by range on recorded_at (one partition per quarter), attach this table as the first partition with a check (recorded_at < cut-off), rename, make the primary key (id, recorded_at) and the client_key unique a local index per partition. Metadata-only step.';
comment on column ipd.vitals.client_key is 'SAH-30: client-generated key, unique per clinic; a repeat of the same key returns the first record (no double save). Under the admission row lock the service looks the key up before it inserts.';
comment on column ipd.vitals.temp_c is 'Degrees Celsius, one decimal (25.0 to 45.0).';
comment on column ipd.vitals.ews_score is 'Early warning score 0..15 calculated by the server (EarlyWarningScore); bands from the mockup, not clinically validated (OQ-30-6).';
comment on column ipd.vitals.note_added is 'True when a nursing note came with this set (the note is a separate row in ipd.nursing_note; no key between them).';
comment on column ipd.vitals.recorded_by is 'Signed-in user id (IPD actor convention). Name resolved at read time.';
comment on table ipd.nursing_note is 'IPD nursing note (SAH-30). Append-only: no update, delete or truncate (privilege and trigger). Clinical text: shown on screen only, never logged or audited.';
comment on table ipd.ews_escalation is 'IPD early warning escalation (SAH-30). At most one not-DONE row per admission (partial unique index). Never deleted (privilege and trigger). sahaj_app may update only ews_score, ews_risk, target_minutes and version; trigger refuses any update of a DONE row and any fall of score or risk. The acknowledge/response/senior-call/close columns belong to the Safety board story. No message is sent to anyone in phase 1.';
comment on column ipd.ews_escalation.target_minutes is 'Response target: 15 for HIGH, 60 for MEDIUM.';
comment on table ipd.clinical_event is 'IPD clinical event log (SAH-30; also written by SAH-31/32 and the Safety board). Append-only: no update, delete or truncate (privilege and trigger). Fixed-template text only: never a patient name or a clinical note. admission_id may be null. seq orders events of one transaction time. Partitionable by at like ipd.vitals (no FK points at it).';
comment on column ipd.clinical_event.actor is 'Signed-in user id (IPD actor convention), not the membership id.';
