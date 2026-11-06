-- SAH-27 · opd.follow_up can come from an IPD discharge (ADR-004 D2). Forward-only, expand-only.
-- A follow-up has exactly one source: an OPD visit (source_visit_id) or an IPD admission (source_admission_id).
-- Existing rows all have a visit and get a null admission, so the new check holds for them.

alter table opd.follow_up alter column source_visit_id drop not null;

alter table opd.follow_up add column if not exists source_admission_id uuid;

-- Plain unique constraints treat NULLs as distinct: uq_follow_up_source_visit keeps working unchanged
-- (on conflict (source_visit_id) do nothing), and this one supports on conflict (source_admission_id) do nothing.
alter table opd.follow_up
  add constraint uq_follow_up_source_admission unique (source_admission_id);

-- NOT VALID then VALIDATE: the scan runs separately from the DDL lock.
alter table opd.follow_up
  add constraint ck_follow_up_one_source check (num_nonnulls(source_visit_id, source_admission_id) = 1) not valid;
alter table opd.follow_up
  validate constraint ck_follow_up_one_source;

-- Fee rule (VisitService.issueToken): latest discharge of a patient with a doctor.
create index if not exists ix_follow_up_discharge_by_patient_doctor
  on opd.follow_up (tenant_id, patient_id, doctor_membership_id, created_at)
  where source_admission_id is not null;

-- RLS policy and grants are unchanged: sahaj_app has table-level select, insert, update (SAH-14), which covers the new column.

comment on column opd.follow_up.source_admission_id is
  'SAH-27: the IPD admission whose discharge added this follow-up; opaque, no FK (no foreign key across modules).';
comment on constraint ck_follow_up_one_source on opd.follow_up is
  'SAH-27: exactly one source, an OPD visit (source_visit_id) or an IPD admission (source_admission_id).';
comment on constraint uq_follow_up_source_admission on opd.follow_up is
  'SAH-27: one follow-up per discharged admission; the port inserts with on conflict (source_admission_id) do nothing so a retry never creates a second row.';
