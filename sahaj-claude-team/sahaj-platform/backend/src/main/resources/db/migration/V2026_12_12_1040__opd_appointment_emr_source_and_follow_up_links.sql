-- SAH-51-6, SAH-68-2 (ADR-025 D6, D14, "Data needed" items 4 and 5) · The EMR books the follow-up appointment.
-- Forward-only, additive; nothing is dropped, renamed or rewritten. Row level security, policies and the table-level
-- select, insert, update grants of opd.appointment and opd.follow_up are unchanged (the grants are table-level and
-- cover the new columns); neither table gets a delete grant.
-- 1. opd.appointment.source gains EMR (a booking made by the doctor's sign). The check is widened, never narrowed: rows written
--    by the previous version (PHONE, WHATSAPP, FRONT_DESK) stay valid and the previous version keeps working. The booking
--    visit_type stays NEW or FOLLOW_UP (the EMR books FOLLOW_UP).
-- 2. uq_appointment_tenant_id (tenant_id, id): the target of the composite key from opd.follow_up, so a follow-up can only name
--    an appointment of its own clinic (foreign key checks ignore row level security; same cure as uq_visit_tenant_id). id is the
--    primary key, so the pair is unique by construction and the build cannot fail. The build takes a lock that blocks writes to
--    opd.appointment while it runs: run at a quiet time on a database that already holds millions of appointments.
-- 3. opd.follow_up.appointment_id (null): the appointment the sign booked; null when no free slot was found within 7 days
--    (DEC-048 H6) or no interval was chosen. interval_key (null): the doctor's choice, D3 D7 D14 D15 M1 M3 (D14 is dental).
--    Both nullable without a default: a catalog change only. No new index: the follow-up list reads the appointment by its
--    primary key and appointments are never deleted.

alter table opd.appointment
  add constraint uq_appointment_tenant_id unique (tenant_id, id);

-- New check first (NOT VALID, then VALIDATE after the DDL lock), old one dropped before, in this one transaction.
alter table opd.appointment drop constraint ck_appointment_source;
alter table opd.appointment
  add constraint ck_appointment_source check (source in ('PHONE', 'WHATSAPP', 'FRONT_DESK', 'EMR')) not valid;
alter table opd.appointment validate constraint ck_appointment_source;

alter table opd.follow_up
  add column if not exists appointment_id uuid,
  add column if not exists interval_key text
    constraint ck_follow_up_interval_key check (interval_key is null or interval_key in ('D3', 'D7', 'D14', 'D15', 'M1', 'M3'));

-- A null appointment_id is not checked (MATCH SIMPLE). ON DELETE NO ACTION: appointments are never deleted.
alter table opd.follow_up
  add constraint fk_follow_up_appointment foreign key (tenant_id, appointment_id)
  references opd.appointment (tenant_id, id);

comment on constraint ck_appointment_source on opd.appointment is
  'source is PHONE, WHATSAPP, FRONT_DESK or EMR (EMR added by SAH-51, ADR-025 D6; widening only).';
comment on column opd.follow_up.appointment_id is
  'The appointment the doctor''s sign booked for this follow-up (SAH-51-6, ADR-025 D6); null when none was booked. Same clinic only (composite key).';
comment on column opd.follow_up.interval_key is
  'The follow-up interval the doctor chose at the sign: D3, D7, D14 (dental), D15, M1 or M3 (SAH-51-6, ADR-025 D6). Null for a follow-up made by the workflow step or a discharge.';
