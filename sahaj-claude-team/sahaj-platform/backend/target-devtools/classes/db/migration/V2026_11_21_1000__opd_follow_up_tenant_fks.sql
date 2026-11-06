-- SAH-27 QA finding F-27-3 (defence in depth). Forward-only.
-- FK checks ignore row level security, so the single-column keys fk_follow_up_patient and
-- fk_follow_up_doctor_membership (SAH-14) let a follow-up of clinic B point at clinic A's patient or doctor.
-- Same cure as V2026_11_13_1000 (ipd.admission): composite keys that include tenant_id.
--
-- Referenced keys:
--   patient.patient (tenant_id, id)    uq_patient_tenant_id already exists (V2026_11_13_1000).
--   platform.membership (tenant_id, id) added here. id is the primary key, so the pair is unique by
--     construction and the index build cannot fail; the table is small (a few rows per clinic).
alter table platform.membership
  add constraint uq_membership_tenant_id unique (tenant_id, id);

-- New keys first (NOT VALID: the table scan happens in VALIDATE, after the DDL locks), old keys dropped after,
-- so there is never a moment without a key. Existing rows were written by the app from one tenant context,
-- so VALIDATE is expected to pass; if a cross-tenant row existed, the migration would stop here on purpose.
-- ON DELETE NO ACTION (default): patients and memberships are never hard-deleted.
alter table opd.follow_up
  add constraint fk_follow_up_patient_tenant foreign key (tenant_id, patient_id)
  references patient.patient (tenant_id, id) not valid;
alter table opd.follow_up
  add constraint fk_follow_up_doctor_membership_tenant foreign key (tenant_id, doctor_membership_id)
  references platform.membership (tenant_id, id) not valid;

alter table opd.follow_up drop constraint fk_follow_up_patient;
alter table opd.follow_up drop constraint fk_follow_up_doctor_membership;

alter table opd.follow_up validate constraint fk_follow_up_patient_tenant;
alter table opd.follow_up validate constraint fk_follow_up_doctor_membership_tenant;

-- Keep the original constraint names (the ipd migration did the same for fk_admission_patient).
alter table opd.follow_up rename constraint fk_follow_up_patient_tenant to fk_follow_up_patient;
alter table opd.follow_up rename constraint fk_follow_up_doctor_membership_tenant to fk_follow_up_doctor_membership;

comment on constraint fk_follow_up_patient on opd.follow_up is
  'SAH-27 F-27-3: composite (tenant_id, patient_id) so a follow-up cannot reference another clinic''s patient (FK checks bypass RLS).';
comment on constraint fk_follow_up_doctor_membership on opd.follow_up is
  'SAH-27 F-27-3: composite (tenant_id, doctor_membership_id) so a follow-up cannot reference another clinic''s doctor (FK checks bypass RLS).';
