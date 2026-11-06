-- SAH-23 security review M2. Forward-only.
-- FK checks ignore RLS, so a plain FK to patient.patient(id) would let an admission point at another tenant's patient.
-- Replace it with a composite FK that forces the patient to belong to the same tenant.
alter table patient.patient
  add constraint uq_patient_tenant_id unique (tenant_id, id);

alter table ipd.admission
  drop constraint fk_admission_patient;

-- ON DELETE NO ACTION (default): patients are never hard-deleted.
alter table ipd.admission
  add constraint fk_admission_patient foreign key (tenant_id, patient_id)
  references patient.patient (tenant_id, id);
