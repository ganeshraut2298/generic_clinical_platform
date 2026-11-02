-- SAH-11 fix: slot uniqueness moves from the database to the application.
-- The overbook booking rule is tenant-configurable (owner/front desk may double-book when ON), and a
-- NOSHOW slot may be rebooked, so the database cannot hold a blanket unique rule. AppointmentService
-- enforces "slot taken" (409 appointment-slot-taken) by checking BOOKED/ARRIVED occupants.
-- V2026_11_01_1043 is left untouched (Flyway checksum); this migration supersedes its unique index.
drop index if exists opd.uq_appointment_tenant_doctor_date_time_active;

create index if not exists ix_appointment_tenant_doctor_date_time
  on opd.appointment (tenant_id, doctor_membership_id, appt_date, appt_time)
  where status in ('BOOKED','ARRIVED');

comment on index opd.ix_appointment_tenant_doctor_date_time is
  'Non-unique. Serves the slot-conflict lookup in AppointmentService.create (active occupants of a doctor/date/time). Uniqueness is enforced by the application because the overbook rule is tenant-configurable.';

comment on table opd.appointment is
  'One row per booked appointment (SAH-11): patient/doctor/date/time, status (BOOKED/ARRIVED/NOSHOW/CANCELLED), visit_type, source, optional note, and visit_id once "Create token" issues a visit. Never deleted. Slot conflicts (BOOKED/ARRIVED occupants) are checked by the application, not a unique index, so overbook and rebooking of cancelled/no-show slots work.';
