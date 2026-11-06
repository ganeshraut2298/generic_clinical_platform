# Tenant-composite foreign key survey

Story: SAH-27 QA finding F-27-3. Date: 2026-11-05. Author: db-engineer. Status: survey only, nothing here is fixed except `opd.follow_up` patient and doctor (V2026_11_21_1000).

## Why it matters
PostgreSQL checks foreign keys without applying row level security. A single-column key from a tenant table to another tenant table lets a row of clinic B point at a row of clinic A if the app ever passes the wrong id. The cure (used by the `ipd` tables and by V2026_11_21_1000) is a composite key `(tenant_id, x_id) -> parent (tenant_id, id)`, which needs `unique (tenant_id, id)` on the parent. None of the paths below is reachable over HTTP today (ids come from rows already filtered by RLS), so this is defence in depth, Low severity.

## Already composite (no action)
All `ipd` tables (ward, room, bed, admission, deposit, charge, bed_transfer, discharge, signoff, final_bill, final_bill_line), including `ipd.admission -> patient.patient` (V2026_11_13_1000); `opd.follow_up` patient and doctor (V2026_11_21_1000). The `hra` tables (roster, attendance, leave) have no foreign key to membership (logical reference only, by design), so there is nothing to cross.

## Still single-column and tenant-owned on both sides
| # | Child column | Parent | Parent needs | Rows (order of magnitude) | Risk if wrong id |
| --- | --- | --- | --- | --- | --- |
| 1 | `opd.visit.patient_id` | `patient.patient (id)` | has `uq_patient_tenant_id` | very large (visits) | visit of B on A's patient |
| 2 | `opd.visit.doctor_membership_id` | `platform.membership (id)` | has `uq_membership_tenant_id` (V2026_11_21_1000) | large | visit of B on A's doctor |
| 3 | `opd.appointment.patient_id` | `patient.patient (id)` | has | large | same |
| 4 | `opd.appointment.doctor_membership_id` | `platform.membership (id)` | has | large | same |
| 5 | `opd.appointment.visit_id` | `opd.visit (id)` | new `uq_visit_tenant_id` | large | appointment of B tied to A's visit |
| 6 | `opd.follow_up.source_visit_id`, `returned_visit_id` | `opd.visit (id)` | new `uq_visit_tenant_id` | medium | follow-up of B tied to A's visit |
| 7 | `billing.bill.visit_id` | `opd.visit (id)` | new `uq_visit_tenant_id` | large | bill of B on A's visit (money) |
| 8 | `billing.bill_item.bill_id` | `billing.bill (id)` | new `uq_bill_tenant_id` | large | item of B on A's bill (money) |
| 9 | `billing.bill_item.service_id` | `opd.service (id)` | new `uq_service_tenant_id` | large | item of B priced from A's service |
| 10 | `billing.payment.bill_id` | `billing.bill (id)` | new `uq_bill_tenant_id` | large | payment of B on A's bill (money) |
| 11 | `opd.doctor_slot_template.doctor_membership_id` | `platform.membership (id)` | has | small | template of B for A's doctor |
| 12 | `opd.doctor_slot_session.doctor_membership_id` | `opd.doctor_slot_template (doctor_membership_id)` | needs `unique (tenant_id, doctor_membership_id)` | small | session of B under A's template |
| 13 | `platform.doctor_profile.membership_id` | `platform.membership (id)` | has | small | profile of B for A's member |
| 14 | `patient.patient_consent.patient_id`, `patient.record_access.patient_id` | `patient.patient (id)` | has | large (append-only) | consent or access log of B on A's patient |
| 15 | `patient.patient.merged_into` | `patient.patient (id)` | has | small | B patient merged into A's patient |

Not an issue: `opd.medicine_favourite.medicine_id` (`opd.medicine` is global, no tenant), all keys to `platform.tenant` and `platform.app_user` (global), `platform.specialty` (global).

## Recommendation
1. Do the money path first, in one story: items 7, 8, 9, 10 (`billing.bill`, `bill_item`, `payment`). Adds `uq_visit_tenant_id`, `uq_bill_tenant_id`, `uq_service_tenant_id`. These are the most damaging if crossed.
2. Then `opd.visit` and `opd.appointment` (items 1 to 6), and `patient_consent`, `record_access`, `merged_into` (14, 15). These are large tables: add the composite key `NOT VALID` first, `VALIDATE` in a later statement (the scan holds only a `SHARE UPDATE EXCLUSIVE` lock, writes continue), and add `uq_*_tenant_id` indexes with `create unique index concurrently` in their own non-transactional migration, then attach them with `add constraint ... unique using index`.
3. Small tables (11, 12, 13) can go in the same pass; they carry no risk of lock time.
4. All need a Java check: every code path that inserts these rows already writes `tenant_id` from `platform.current_tenant()` (column default), so no code change is expected; the ITs `DischargeFollowUpIT` style "cross-tenant insert is refused by the database" should be added per table by qa-engineer.
5. Each change is forward-only, drops nothing but the old key, and keeps the old constraint name.

I did not fix these here: more than three tables and several large ones need a concurrent index migration plus its own test plan, which is not trivial and safe enough for a defence-in-depth finding closure. A new story or a hardening ticket is suggested.
