-- SAH-11 · Appointments and booking rules.
-- Four tables: opd.doctor_slot_template + opd.doctor_slot_session (per-doctor slot pattern),
-- opd.booking_rules (one row per tenant, lazy-created on first write by the app — see
-- docs/database/SAH-11.md, same pattern as billing.bill in SAH-10), and opd.appointment itself.
-- No cross-module FK beyond platform.tenant/platform.membership/patient.patient/opd.visit, per
-- postgresql.md (opd.visit is in-module so no exception needed there).

-- opd.doctor_slot_template : one row per doctor membership, the clinic's slot pattern for that
-- doctor (slot length, working days, walk-in reserve, max online bookings/day). tenant_id is
-- denormalized onto this table, same pattern as platform.doctor_profile, so RLS does not need a
-- join through platform.membership.
create table if not exists opd.doctor_slot_template (
  doctor_membership_id uuid primary key
                        constraint fk_doctor_slot_template_membership references platform.membership (id),
  tenant_id             uuid not null default platform.current_tenant()
                        constraint fk_doctor_slot_template_tenant references platform.tenant (id),
  slot_minutes          integer not null default 15
                        constraint ck_doctor_slot_template_slot_minutes check (slot_minutes between 5 and 60),
  working_days          smallint[] not null default '{1,2,3,4,5,6}'
                        constraint ck_doctor_slot_template_working_days check (
                          working_days <@ array[0,1,2,3,4,5,6]::smallint[]
                        ),
  walk_in_every_nth     integer not null default 0
                        constraint ck_doctor_slot_template_walk_in_every_nth check (walk_in_every_nth >= 0),
  max_online_per_day    integer not null default 0
                        constraint ck_doctor_slot_template_max_online_per_day check (max_online_per_day >= 0),
  updated_at            timestamptz,
  updated_by            uuid,
  version               bigint not null default 0
);

-- Tenant-wide "every doctor's slot settings" list (GET /opd/doctor-slots has no doctor filter).
create index if not exists ix_doctor_slot_template_tenant on opd.doctor_slot_template (tenant_id);

alter table opd.doctor_slot_template enable row level security;
alter table opd.doctor_slot_template force row level security;

create policy p_doctor_slot_template_tenant on opd.doctor_slot_template
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- Never deleted: a doctor's slot settings are edited in place (default row created the first
-- time a template is needed, or on first PUT); the membership row carries history, not this one.
revoke all on opd.doctor_slot_template from sahaj_app;
revoke all on opd.doctor_slot_template from public;
grant select, insert, update on opd.doctor_slot_template to sahaj_app;

comment on table opd.doctor_slot_template is
  'One row per DOCTOR/VISITING_DOCTOR membership: slot length, working days (0=Sunday..6=Saturday), walk-in reserve (every Nth slot) and max online bookings/day (SAH-11). tenant_id is denormalized, same pattern as platform.doctor_profile.';

-- opd.doctor_slot_session : up to two sessions (start/end time) per doctor, e.g. morning/evening.
create table if not exists opd.doctor_slot_session (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null default platform.current_tenant()
                        constraint fk_doctor_slot_session_tenant references platform.tenant (id),
  doctor_membership_id  uuid not null
                        constraint fk_doctor_slot_session_template references opd.doctor_slot_template (doctor_membership_id),
  session_index         smallint not null
                        constraint ck_doctor_slot_session_index check (session_index in (0, 1)),
  start_time            time not null,
  end_time              time not null
                        constraint ck_doctor_slot_session_end_after_start check (end_time > start_time),
  constraint uq_doctor_slot_session_doctor_index unique (doctor_membership_id, session_index)
);

-- A doctor's sessions, loaded together with the template row.
create index if not exists ix_doctor_slot_session_tenant_doctor
  on opd.doctor_slot_session (tenant_id, doctor_membership_id);

alter table opd.doctor_slot_session enable row level security;
alter table opd.doctor_slot_session force row level security;

create policy p_doctor_slot_session_tenant on opd.doctor_slot_session
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- Sessions are replaced wholesale on save (java-developer: delete-then-insert or upsert by
-- (doctor_membership_id, session_index) inside one transaction), so delete is granted here only
-- (never on appointment/booking_rules/doctor_slot_template, which are clinical/operational
-- records edited in place).
revoke all on opd.doctor_slot_session from sahaj_app;
revoke all on opd.doctor_slot_session from public;
grant select, insert, update, delete on opd.doctor_slot_session to sahaj_app;

comment on table opd.doctor_slot_session is
  'Up to two sessions (start_time/end_time) per doctor slot template, session_index 0 or 1 (SAH-11). Replaced wholesale on save; delete is granted for that reason only.';

-- opd.booking_rules : one row per tenant, lazy-created on first write by the app (same pattern
-- as billing.bill in SAH-10) — no seed row per tenant via this migration.
create table if not exists opd.booking_rules (
  tenant_id             uuid primary key
                        constraint fk_booking_rules_tenant references platform.tenant (id),
  close_before_hours    integer not null default 1
                        constraint ck_booking_rules_close_before_hours check (close_before_hours between 0 and 48),
  remind_before_hours   integer not null default 2
                        constraint ck_booking_rules_remind_before_hours check (remind_before_hours between 0 and 48),
  no_show_after_minutes integer not null default 15
                        constraint ck_booking_rules_no_show_after_minutes check (no_show_after_minutes between 0 and 180),
  follow_priority       boolean not null default true,
  self_book             boolean not null default false,
  overbook              boolean not null default false,
  updated_at            timestamptz,
  updated_by            uuid,
  version               bigint not null default 0
);

alter table opd.booking_rules enable row level security;
alter table opd.booking_rules force row level security;

create policy p_booking_rules_tenant on opd.booking_rules
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- Never deleted: edited in place once the lazy-created row exists.
revoke all on opd.booking_rules from sahaj_app;
revoke all on opd.booking_rules from public;
grant select, insert, update on opd.booking_rules to sahaj_app;

comment on table opd.booking_rules is
  'One row per tenant: booking close-before window, no-show window, follow-priority/self-book/overbook flags (SAH-11). remind_before_hours and self_book are stored but have no effect yet (no notification/patient-facing channel in Phase 1). Lazy-created on first write by the app, like billing.bill (SAH-10) — not seeded by this migration.';

-- opd.appointment : one row per booked appointment. Never deleted — cancelled via status, which
-- frees the (doctor, date, time) slot for rebooking through the partial unique index below.
create table if not exists opd.appointment (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null default platform.current_tenant()
                        constraint fk_appointment_tenant references platform.tenant (id),
  patient_id            uuid not null
                        constraint fk_appointment_patient references patient.patient (id),
  doctor_membership_id  uuid not null
                        constraint fk_appointment_doctor_membership references platform.membership (id),
  appt_date             date not null,
  appt_time             time not null,
  status                text not null default 'BOOKED'
                        constraint ck_appointment_status check (status in ('BOOKED', 'ARRIVED', 'NOSHOW', 'CANCELLED')),
  visit_type            text not null
                        constraint ck_appointment_visit_type check (visit_type in ('NEW', 'FOLLOW_UP')),
  source                text not null
                        constraint ck_appointment_source check (source in ('PHONE', 'WHATSAPP', 'FRONT_DESK')),
  note                  text
                        constraint ck_appointment_note check (note is null or char_length(note) <= 400),
  visit_id              uuid
                        constraint fk_appointment_visit references opd.visit (id),
  created_at            timestamptz not null default now(),
  created_by            uuid not null,
  updated_at            timestamptz,
  updated_by            uuid,
  version               bigint not null default 0
);

-- Appointments board query: a doctor's day (GET /opd/appointments?date=&doctorMembershipId=).
create index if not exists ix_appointment_tenant_doctor_date
  on opd.appointment (tenant_id, doctor_membership_id, appt_date);

-- A slot is unique per doctor per day per time, unless the prior occupant was cancelled — a
-- cancelled slot can be rebooked (story technical design). Partial unique index, not a plain
-- unique constraint, for that reason.
create unique index if not exists uq_appointment_tenant_doctor_date_time_active
  on opd.appointment (tenant_id, doctor_membership_id, appt_date, appt_time)
  where status <> 'CANCELLED';

alter table opd.appointment enable row level security;
alter table opd.appointment force row level security;

create policy p_appointment_tenant on opd.appointment
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- No delete: an appointment is cancelled via status, never removed.
revoke all on opd.appointment from sahaj_app;
revoke all on opd.appointment from public;
grant select, insert, update on opd.appointment to sahaj_app;

comment on table opd.appointment is
  'One row per booked appointment (SAH-11): patient/doctor/date/time, status (BOOKED/ARRIVED/NOSHOW/CANCELLED), visit_type, source, optional note, and visit_id once "Create token" issues a visit. Never deleted; a cancelled slot frees its (doctor, date, time) for rebooking via the partial unique index.';
