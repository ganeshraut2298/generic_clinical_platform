-- SAH-9 · opd.visit : one row per token issued to a patient for a doctor on a given day.
-- Cross-module FK to patient.patient is the one exception allowed by postgresql.md
-- ("no foreign keys across modules except to platform.tenant and patient.patient"); opd still
-- reads patient name/age/sex through patient.api rather than joining this table directly in a
-- query (service-layer concern, not a DB concern).
-- token_date is a stored (not generated) column, defaulted to current_date at insert time, kept
-- in lockstep with the day used by opd.next_token_no() when the application calls it inside the
-- same transaction. A generated column expression on created_at::date was considered and rejected:
-- it would tie "which day's token sequence" to created_at's clock exactly, which is fragile across
-- a transaction that takes any time at all; a plain stored column set once at insert, together
-- with the (tenant_id, token_date) counter row, keeps both in agreement by construction.
-- Never deleted (clinical/operational record): a visit only ever reaches stage DONE.
create table if not exists opd.visit (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null default platform.current_tenant()
                        constraint fk_visit_tenant references platform.tenant (id),
  patient_id            uuid not null
                        constraint fk_visit_patient references patient.patient (id),
  doctor_membership_id  uuid not null
                        constraint fk_visit_doctor_membership references platform.membership (id),
  visit_type            text not null
                        constraint ck_visit_type check (visit_type in ('NEW', 'FOLLOW_UP')),
  stage                 text not null default 'WAITING'
                        constraint ck_visit_stage check (stage in
                          ('WAITING', 'VITALS', 'WITH_DOCTOR', 'COUNSEL', 'BILLING', 'DONE')),
  token_no              integer not null
                        constraint ck_visit_token_no check (token_no > 0),
  token_date            date not null default current_date,
  fee_paise             bigint not null default 0
                        constraint ck_visit_fee_paise check (fee_paise >= 0),
  created_at            timestamptz not null default now(),
  created_by            uuid not null,
  updated_at            timestamptz,
  updated_by            uuid,
  version               bigint not null default 0,
  -- A token number is unique per clinic per day (story AC1); token_date is stamped at insert
  -- and matches the day opd.next_token_no() allocated against, for the reasons above.
  constraint uq_visit_tenant_token_date_token unique (tenant_id, token_date, token_no)
);

-- "Today's visits" board query: where tenant_id = ... and created_at >= start_of_today.
create index if not exists ix_visit_tenant_created_at on opd.visit (tenant_id, created_at);
-- Per-patient visit history (follow-up-window computation in AC5: "last visit with this doctor
-- within follow_up_days") and the record page's visits tab.
create index if not exists ix_visit_tenant_patient on opd.visit (tenant_id, patient_id);
-- Per-doctor queue / follow-up-window lookup ("last visit with this doctor").
create index if not exists ix_visit_tenant_doctor on opd.visit (tenant_id, doctor_membership_id);

alter table opd.visit enable row level security;
alter table opd.visit force row level security;

create policy p_visit_tenant on opd.visit
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- No delete: a visit is never removed, only advanced to stage DONE.
revoke all on opd.visit from sahaj_app;
revoke all on opd.visit from public;
grant select, insert, update on opd.visit to sahaj_app;

comment on table opd.visit is
  'One row per token issued to a patient for a doctor on a given day (SAH-9). token_no is unique per (tenant_id, token_date) via opd.next_token_no(); stage is a fixed forward state machine (WAITING, VITALS, WITH_DOCTOR, COUNSEL, BILLING, DONE). Never deleted.';
