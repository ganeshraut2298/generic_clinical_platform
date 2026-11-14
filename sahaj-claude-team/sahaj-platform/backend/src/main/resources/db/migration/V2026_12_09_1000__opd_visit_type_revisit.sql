-- SAH-52 (ADR-020 D10) and SAH-53 (ADR-020 D8) · Two additive changes to the merged table opd.visit. Forward-only.
-- 1. visit_type gains REVISIT (the Queue-card visit type of the EMR: NEW, FOLLOW_UP, REVISIT). The check is widened, never
--    narrowed, so rows written by the previous application version (NEW, FOLLOW_UP) stay valid and the previous version keeps
--    working against the new schema. opd.appointment.visit_type is not changed (a booking is NEW or FOLLOW_UP; the doctor
--    sets REVISIT on the visit).
-- 2. A (tenant_id, id) key on opd.visit, so opd.emr_record can point at a visit of the same clinic only (foreign key checks
--    ignore row level security; same cure as uq_patient_tenant_id and uq_membership_tenant_id). id is the primary key, so
--    the pair is unique by construction and the index build cannot fail. Building it blocks writes to opd.visit for the
--    length of the build; run at a quiet time on a database that already holds millions of visits.
alter table opd.visit
  add constraint uq_visit_tenant_id unique (tenant_id, id);

-- New check first (NOT VALID, then VALIDATE after the DDL lock), old one dropped before, in this one transaction.
alter table opd.visit drop constraint ck_visit_type;
alter table opd.visit
  add constraint ck_visit_type check (visit_type in ('NEW', 'FOLLOW_UP', 'REVISIT')) not valid;
alter table opd.visit validate constraint ck_visit_type;

-- The EMR record points at its OPD visit. ON DELETE NO ACTION (default): visits are never deleted.
alter table opd.emr_record
  add constraint fk_emr_record_visit foreign key (tenant_id, opd_visit_id)
  references opd.visit (tenant_id, id);

comment on constraint ck_visit_type on opd.visit is
  'visit_type is NEW, FOLLOW_UP or REVISIT (REVISIT added by SAH-52, ADR-020 D10; widening only).';
