# SAH-73 · Optional patient date of birth · database design

Extends `docs/database/SAH-8.md` (table `patient.patient`). Source: ADR-024 "Data needed", DEC-045.
Migration: `backend/src/main/resources/db/migration/V2026_12_11_1130__patient_date_of_birth.sql` (one file, additive).

## Change
| Column | Type | Null | Default | Check |
| --- | --- | --- | --- | --- |
| `date_of_birth` | `date` | yes | none | `ck_patient_dob_floor`: `date_of_birth is null or date_of_birth >= date '1900-01-01'` |

- Column comment: "Personal data, never log (SAH-73). Never in audit values, never in list responses."
- `age smallint not null 0..120` (`ck_patient_age`) is unchanged. Future-date and 120-year rules stay in the service (they depend on today).
- No backfill, no data migration, no guess from age. Existing rows read `null`.
- No new index: chips filter by a date bound inside the tenant's active rows (`ix_patient_tenant_active`); duplicate check uses `ix_patient_tenant_mobile`. Revisit if the list query plan says otherwise.
- RLS (`p_patient_tenant`, ENABLE + FORCE) and grants unchanged: `select, insert, update` is table-level on `patient.patient` (no column-level grants exist), so the new column is covered. No delete.
- Tenant scope, retention, rows per tenant: as SAH-8 (never deleted; thousands to tens of thousands per tenant).
- Backward compatibility: the previous app version ignores the nullable column; its inserts still pass. Rollback is a later forward migration (drop constraint, drop column), not an edit of this file.
- Not-null add on a big table: not applicable (nullable). Adding the check scans the table once under a short lock; patient tables are small, existing rows are all null so it cannot fail.

## Test cases for QA (integration, `sahaj_test`)
1. Migration applies from scratch; `patient.patient.date_of_birth` exists, type `date`, nullable, no default.
2. Existing / legacy insert without `date_of_birth` succeeds; the row reads `null`.
3. Insert with `1900-01-01` succeeds; `1899-12-31` fails with `ck_patient_dob_floor`.
4. Insert with `null` succeeds; update from a date back to `null` succeeds.
5. `age` check still enforced (121 fails) with and without a date of birth.
6. RLS: tenant A sets a date of birth; tenant B (other `app.tenant_id`) sees no row and cannot update it; unset tenant sees zero rows.
7. `sahaj_app` can insert and update the column; has no delete on `patient.patient`.
8. Column comment contains "never log" (`col_description`).
9. Service level (java-developer/QA): future date rejected, older than 120 years rejected, never in logs, audit values or list responses.
