-- SAH-58 (ADR-022 D12) · IPD: an admission linked to a treatment course sitting, and the deposit carried in from the course
-- account. Forward-only, additive: columns are added and one NOT NULL is relaxed; no row is rewritten, nothing is dropped.
-- The previous application version keeps working: it never writes the new columns, the defaults keep every old deposit a
-- DESK deposit with its mode, and the admission columns stay null.
--   ipd.admission  + treatment_course_id, treatment_course_sitting_id   (opaque opd ids, no foreign key: cross-module)
--   ipd.deposit    + source (DESK, COURSE), course_credit_transfer_id (opaque billing id); mode may be null for COURSE only
-- Row level security, policies, triggers and every other grant of both tables are unchanged. ipd.deposit stays append-only.

-- ---------------------------------------------------------------------------------------------
-- 1. ipd.admission: the course and the sitting the admission was made for. Both or neither. A sitting is linked to at most
--    one admission (the same rule opd keeps on its side). Set at admission (insert) only, so only the insert grant grows.
-- ---------------------------------------------------------------------------------------------
alter table ipd.admission
  add column if not exists treatment_course_id uuid,
  add column if not exists treatment_course_sitting_id uuid;

alter table ipd.admission
  add constraint ck_admission_course_pair check ((treatment_course_id is null) = (treatment_course_sitting_id is null));

create unique index if not exists uq_admission_tenant_course_sitting
  on ipd.admission (tenant_id, treatment_course_sitting_id) where treatment_course_sitting_id is not null;
-- The admissions of one course (the course page shows their status and bill total).
create index if not exists ix_admission_tenant_course
  on ipd.admission (tenant_id, treatment_course_id) where treatment_course_id is not null;

grant insert (treatment_course_id, treatment_course_sitting_id) on ipd.admission to sahaj_app;

comment on column ipd.admission.treatment_course_id is 'Opaque opd.treatment_course id when the admission was made for a course sitting (SAH-58). No foreign key: cross-module.';
comment on column ipd.admission.treatment_course_sitting_id is 'Opaque opd.treatment_course_sitting id (SAH-58). At most one admission per sitting.';

-- ---------------------------------------------------------------------------------------------
-- 2. ipd.deposit: source and the carried-in link. DESK (default) = collected at the desk with a mode, as before.
--    COURSE = credit carried from the course account: no mode, and the row names the billing.course_credit_transfer that
--    moved it. The mode column keeps its check (Cash, UPI, Card); a null passes it. NOT NULL on mode is relaxed, and a check
--    brings it back for DESK: (DESK and mode set) or (COURSE and no mode). One deposit per transfer.
-- ---------------------------------------------------------------------------------------------
alter table ipd.deposit
  add column if not exists source text not null default 'DESK'
    constraint ck_deposit_source check (source in ('DESK', 'COURSE')),
  add column if not exists course_credit_transfer_id uuid;

alter table ipd.deposit alter column mode drop not null;

alter table ipd.deposit
  add constraint ck_deposit_mode_by_source check (
    (source = 'DESK' and mode is not null) or (source = 'COURSE' and mode is null)),
  add constraint ck_deposit_transfer_by_source check (
    (source = 'COURSE') = (course_credit_transfer_id is not null));

create unique index if not exists uq_deposit_tenant_course_credit_transfer
  on ipd.deposit (tenant_id, course_credit_transfer_id) where course_credit_transfer_id is not null;

-- The insert grant is column-level (V2026_11_12_1000): add the two new columns. Update and delete stay refused.
grant insert (source, course_credit_transfer_id) on ipd.deposit to sahaj_app;

comment on column ipd.deposit.source is 'DESK = collected at the desk with a mode; COURSE = credit carried from a treatment course account (SAH-58), no mode.';
comment on column ipd.deposit.course_credit_transfer_id is 'Opaque billing.course_credit_transfer id for a COURSE deposit (SAH-58). No foreign key: cross-module.';
comment on column ipd.deposit.mode is 'Cash, UPI or Card for a DESK deposit; null only for a COURSE deposit (SAH-58).';
