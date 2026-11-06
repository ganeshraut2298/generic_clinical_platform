# SAH-25 · Database design: IPD extra charges

Migration: `V2026_11_14_1000__ipd_admission_charge.sql` (forward-only, one new table, no change to existing tables).

## ipd.admission_charge
| Column | Type | Null | Default / check |
|---|---|---|---|
| id | uuid PK | no | gen_random_uuid() |
| tenant_id | uuid | no | platform.current_tenant(); FK platform.tenant |
| admission_id | uuid | no | composite FK (tenant_id, admission_id) to ipd.admission (tenant_id, id) |
| service_key | text | no | in visit, ivfluid, injection, cbc, xray, oxygen, dressing, ecg, nebulisation, physio |
| service_name | text | no | 1 to 100 chars |
| unit_paise | bigint | no | 0 to 100000000 (base price when added) |
| qty | integer | no | default 1, 1 to 50 |
| added_at | timestamptz | no | now() |
| added_by | uuid | no | membership/user id of the adder |

Index: `ix_admission_charge_tenant_admission (tenant_id, admission_id)` serves the running bill and the per-service count (max 50 rows of a service per admission is enforced in the service).

## Tenant scope and RLS
FORCE RLS; separate select and insert policies for `sahaj_app` on `tenant_id = platform.current_tenant()`. No update/delete policy.

## Append-only
`sahaj_app` has select and column-level insert (admission_id, service_key, service_name, unit_paise, qty, added_by) only. Triggers deny update, delete and truncate through `platform.deny_audit_mutation()`.

## Volume and retention
About 5 to 30 rows per admission; a 100-bed hospital at 80 percent occupancy and 5-day stay gives roughly 10,000 to 50,000 rows a year per tenant. No partitioning. Never deleted (billing data).

## Compatibility
Additive. The previous app version ignores the table.

## Follow-up L3: qty fixed at 1 (security review SAH-24/25)
Migration: `V2026_11_16_1000__ipd_admission_charge_qty_fixed.sql` (forward-only; the V2026_11_14 file is not edited).

Problem: `qty` was in the column-level insert grant and the check allowed 1 to 50 per row, so the 50-per-service cap lived only in Java.

Changes:
- `revoke insert (qty) on ipd.admission_charge from sahaj_app`. The column default 1 always applies. `select` on `qty` is unchanged (the running bill still reads it).
- `ck_admission_charge_qty_one check (qty = 1)`, added `not valid` then `validate constraint`. Existing rows all have qty 1 (one row per add, Java inserted the literal 1), so validation passes. The table is small (see volume above), so the validation scan is cheap.
- The old `ck_admission_charge_qty` (1 to 50) is kept. It is implied by the new check and harmless; dropping it adds nothing and a later story that sells quantities would replace both in one new migration.

Insert grant is now: `admission_id, service_key, service_name, unit_paise, added_by`.

Compatibility: not backward compatible for the previous app version. An INSERT that names `qty` fails with permission denied. The java-developer must remove `qty, 1` from `JdbcChargeRepository.INSERT` (column list and values) in the same release. Reads are unaffected. Rollback path: a new forward migration re-granting insert (qty) and dropping the new check.
