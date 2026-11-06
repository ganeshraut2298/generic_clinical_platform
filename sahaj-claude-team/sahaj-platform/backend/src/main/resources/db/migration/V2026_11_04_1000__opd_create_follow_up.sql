-- SAH-14 · Follow-up lists.
-- opd.follow_up : one row per visit that ends the doctor step with the workflow "followup" step on.
-- Created by VisitService.moveStage; closed (returned_visit_id/returned_at) by VisitService.issueToken.
-- Status (DUE/MISSED/UPCOMING/RETURNED) is computed by the app from today's IST date, never stored.
-- In-module FKs only (opd.visit); platform.tenant/platform.membership/patient.patient as in opd.appointment.
create table if not exists opd.follow_up (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null default platform.current_tenant()
                        constraint fk_follow_up_tenant references platform.tenant (id),
  patient_id            uuid not null
                        constraint fk_follow_up_patient references patient.patient (id),
  doctor_membership_id  uuid not null
                        constraint fk_follow_up_doctor_membership references platform.membership (id),
  source_visit_id       uuid not null
                        constraint fk_follow_up_source_visit references opd.visit (id)
                        constraint uq_follow_up_source_visit unique,
  due_date              date not null,
  reason                text not null
                        constraint ck_follow_up_reason check (char_length(reason) <= 200),
  returned_visit_id     uuid
                        constraint fk_follow_up_returned_visit references opd.visit (id),
  returned_at           timestamptz,
  created_at            timestamptz not null default now(),
  created_by            uuid not null,
  updated_at            timestamptz,
  updated_by            uuid,
  version               bigint not null default 0
);

-- Tab lists and counts: GET /opd/follow-ups filters/orders by due_date within a tenant.
create index if not exists ix_follow_up_tenant_due_date
  on opd.follow_up (tenant_id, due_date);

-- "Return" lookup in issueToken: oldest open follow-up for (tenant, patient, doctor).
create index if not exists ix_follow_up_open_by_patient_doctor
  on opd.follow_up (tenant_id, patient_id, doctor_membership_id)
  where returned_visit_id is null;

alter table opd.follow_up enable row level security;
alter table opd.follow_up force row level security;

create policy p_follow_up_tenant on opd.follow_up
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- No delete: a follow-up is closed by setting returned_visit_id/returned_at, never removed.
revoke all on opd.follow_up from sahaj_app;
revoke all on opd.follow_up from public;
grant select, insert, update on opd.follow_up to sahaj_app;

comment on table opd.follow_up is
  'One row per visit that ends the doctor step with the followup workflow step on (SAH-14): patient, doctor, due_date (source visit IST day + followUpDays), reason, and returned_visit_id/returned_at once the patient comes back. Status is computed, not stored. Never deleted.';

comment on constraint uq_follow_up_source_visit on opd.follow_up is
  'One follow-up per source visit: the app inserts with on conflict (source_visit_id) do nothing so a retried moveStage never creates a second row.';
