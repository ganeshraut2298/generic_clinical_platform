-- SAH-68-3 (ADR-025 D13, "Data needed" item 12) · Who recorded the patient's allergy text, and when. Additive, forward-only.
-- allergy_recorded_at / allergy_recorded_by are set by patient when the doctor's sign updates the allergy text
-- (PatientClinicalUpdatePort.updateFacts) and cleared when the front desk changes the allergy text. Existing rows stay null
-- (no backfill, no guess). No new index. RLS (p_patient_tenant, ENABLE + FORCE) and the table-level select, insert, update
-- grants on patient.patient already cover the new columns (as in V2026_12_11_1130); no delete.
-- allergy_recorded_by is a user id (opaque, no foreign key). Both columns are set together or not at all. The check is added
-- NOT VALID and validated at once: every existing row has both null, so it passes, and the validation scan does not block writes.
-- The previous application version ignores the columns and keeps working.

alter table patient.patient
  add column if not exists allergy_recorded_at timestamptz,
  add column if not exists allergy_recorded_by uuid;

alter table patient.patient
  add constraint ck_patient_allergy_recorded_pair check ((allergy_recorded_at is null) = (allergy_recorded_by is null)) not valid;
alter table patient.patient validate constraint ck_patient_allergy_recorded_pair;

comment on column patient.patient.allergy_recorded_at is 'When the allergy text was last recorded by a doctor''s signed visit (SAH-68-3). Null when recorded at the front desk or never. Cleared by a front-desk change of the allergy text.';
comment on column patient.patient.allergy_recorded_by is 'The user (opaque id) whose signed visit recorded the allergy text (SAH-68-3). Set together with allergy_recorded_at. Audit values name field names only, never the allergy text.';
