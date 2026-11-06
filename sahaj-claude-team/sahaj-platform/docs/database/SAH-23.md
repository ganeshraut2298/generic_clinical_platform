# SAH-23 · Admission and deposits: database design

Migration: `V2026_11_12_1000__ipd_admission_and_deposits.sql` (forward-only; no earlier migration edited).

## Changes
- `ipd.config`: new columns `dep_planned_paise` 1000000, `dep_emergency_paise` 500000, `dep_daycare_paise` 300000, `dep_maternity_paise` 1500000, `cat_add_gen_paise` 0, `cat_add_semi_paise` 200000, `cat_add_pvt_paise` 500000, `cat_add_icu_paise` 1000000 (all bigint, 0 to 100000000 paise = ₹10,00,000), `emergency_without_deposit` true, `deposit_alert_percent` 80 (50 to 100, multiple of 5), `deposit_rules_confirmed` false. Existing rows get the defaults.
- `ipd.bed`: added `uq_bed_tenant_id (tenant_id, id)` as composite FK target.
- `ipd.admission`: patient (FK `patient.patient`), `ip_number`, `bed_id`, `doctor_membership_id` (no FK), type, source (default DIRECT), payer, reason (1 to 200, default "Under evaluation"), `consent_signed` (check: must be true), status, `admitted_at/by`, `discharged_at`, `version`.
  - Unique `(tenant_id, ip_number)`; partial unique `(tenant_id, patient_id) where status = 'ADMITTED'` (one current admission per patient); index `(tenant_id, status, admitted_at)` serves the Admitted list; index `(tenant_id, bed_id)` serves bed history.
  - Composite FK `(tenant_id, bed_id)` to `ipd.bed`, ON DELETE NO ACTION. `ipd.bed` still has a DELETE grant, so this FK is what stops deleting a bed with admission history. The service maps the FK violation (or checks first) to 409 `bed-has-history`.
- `ipd.deposit`: append-only; composite FK to admission, `amount_paise` > 0 (max ₹10,00,000), mode Cash/UPI/Card, `collected_at/by`. Index `(tenant_id, admission_id, collected_at)`.
- `ipd.ip_sequence`: PK `(tenant_id, fy)`, `last_no`; `fy` like `26-27`.

## Tenant scope and RLS
All three tables have `tenant_id` default `platform.current_tenant()` with FK to `platform.tenant`, ENABLE and FORCE RLS. Admission and ip_sequence: one policy for sahaj_app. Deposit: separate select and insert policies.

## Grants (sahaj_app)
- admission: select; insert on columns patient_id, ip_number, bed_id, doctor_membership_id, admission_type, source, payer, reason, consent_signed, status, admitted_by; update on status, discharged_at, version. No delete.
- deposit: select; insert on admission_id, amount_paise, mode, collected_by only. Triggers (`platform.deny_audit_mutation()`) refuse update, delete and truncate for every role.
- ip_sequence: select, insert, update.

## Volume and retention
Per hospital (100 hospitals): admissions roughly 2,000 to 10,000 a year, deposits 1 to 3 per admission. Total well under 50 million rows; no partitioning. Clinical and billing rows are never deleted.

## Compatibility
Additive only (new defaulted columns, new tables, one new unique constraint); the SAH-22 app version keeps working.

## Same-tenant patient FK (security review M2)
Migration `V2026_11_13_1000__ipd_admission_patient_same_tenant_fk.sql` adds `uq_patient_tenant_id` unique (tenant_id, id) on `patient.patient` and replaces `fk_admission_patient` with a composite FK `(tenant_id, patient_id)` to `patient.patient (tenant_id, id)`, NO ACTION. FK checks ignore RLS, so this stops an admission from referencing another tenant's patient. Existing rows already satisfy it, because every admission was created with its own tenant's patient under RLS. The cross-module FK to `patient.patient` is allowed by `.claude/rules/postgresql.md`.
