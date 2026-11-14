-- SAH-73 (ADR-024, DEC-045) · Optional patient date of birth. Additive, forward-only.
-- No backfill, no data migration, no guess from age: existing rows stay null. age (smallint, 0..120) is unchanged.
-- The future-date and 120-year rules depend on today, so they live in the service; only a sanity floor is here.
-- No new index. RLS, policy p_patient_tenant and grants (select, insert, update on patient.patient to sahaj_app) are
-- table-level and already cover the new column. A previous app version ignores the column.
alter table patient.patient
  add column if not exists date_of_birth date null;

alter table patient.patient
  add constraint ck_patient_dob_floor check (date_of_birth is null or date_of_birth >= date '1900-01-01');

comment on column patient.patient.date_of_birth is 'Personal data, never log (SAH-73). Never in audit values, never in list responses.';
