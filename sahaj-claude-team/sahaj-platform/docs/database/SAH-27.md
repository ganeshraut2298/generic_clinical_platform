# SAH-27 · Database design: IPD discharge, final bill, Discharge setup

Migrations (forward-only; no existing migration is edited):
- `V2026_11_18_1000__ipd_discharge.sql`: three new columns on `ipd.config`, four new append-only tables in `ipd`.
- `V2026_11_18_1010__opd_follow_up_admission_source.sql`: `opd.follow_up` can have an IPD admission as its source (ADR-004 D2). Separate file because `opd` owns the table.
- `V2026_11_20_1000__ipd_discharge_drop_followup_id.sql`: drops `ipd.discharge.followup_id` and its check `ck_discharge_followup_id` (see "Follow-up: followup_id dropped").

## ipd.config (three new columns)
| Column | Type | Null | Default / check |
|---|---|---|---|
| discharge_template_key | text | yes | null = no template chosen; `ck_ipd_config_discharge_template` in general, surgical, maternity, daycare |
| discharge_chain | boolean | no | true (true = the doctor signs the summary first) |
| followup_days | smallint | no | 7; `ck_ipd_config_followup_days` between 1 and 30 |

The row is lazy. Existing rows get the defaults. `sahaj_app` already has table-level `select, insert, update` (SAH-22), so no grant is added. The Java upsert must insert a row with defaults for the other columns when none exists (as in SAH-26).

## ipd.discharge_signoff (append-only)
| Column | Type | Null | Default / check |
|---|---|---|---|
| id | uuid PK | no | gen_random_uuid() |
| tenant_id | uuid | no | platform.current_tenant(); FK platform.tenant |
| admission_id | uuid | no | composite FK (tenant_id, admission_id) to ipd.admission (tenant_id, id) |
| discharge_type | text | no | in normal, lama, referred, death |
| template_key | text | no | in general, surgical, maternity, daycare |
| signed_by | uuid | no | actor id (same pattern as collected_by) |
| signed_at | timestamptz | no | now() (not insertable) |

Keys: `uq_discharge_signoff_tenant_id (tenant_id, id)`; `uq_discharge_signoff_tenant_id_admission (tenant_id, id, admission_id)` (target of the stronger FK from `ipd.discharge`, so a discharge cannot point at another admission's sign-off).
Indexes:
- `ix_discharge_signoff_tenant_admission (tenant_id, admission_id, signed_at desc)`: latest sign-off of one admission (sheet, completion check) and the batched `admission_id = any(?)` load for the list and board.
- `ix_discharge_signoff_tenant_signed_at (tenant_id, signed_at)`: "Going home today" (sign-offs of the IST day, of admissions still ADMITTED).

## ipd.discharge (insert only, one per admission)
| Column | Type | Null | Default / check |
|---|---|---|---|
| id | uuid PK | no | gen_random_uuid() |
| tenant_id | uuid | no | platform.current_tenant(); FK platform.tenant |
| admission_id | uuid | no | composite FK to ipd.admission; `uq_discharge_tenant_admission (tenant_id, admission_id)` |
| signoff_id | uuid | no | composite FK (tenant_id, signoff_id, admission_id) to ipd.discharge_signoff (tenant_id, id, admission_id) |
| discharge_type | text | no | in normal, lama, referred, death |
| template_key | text | no | in the four keys |
| signed_by | uuid | no | copied from the sign-off |
| signed_at | timestamptz | no | copied from the sign-off (insertable, no default) |
| followup_days | smallint | no | 1..30 (the setting value at discharge) |
| followup_due | date | yes | IST discharge day + followup_days; `(discharge_type = 'death') = (followup_due is null)` |
| completed_by | uuid | no | actor id |
| completed_at | timestamptz | no | now() (not insertable) |

Index `ix_discharge_tenant_completed_at (tenant_id, completed_at)`: "discharged today" and date-range reads. The per-admission lookup uses the unique key.
Type and template on the discharge are copied from the sign-off row by the Java code (the database proves only that the sign-off belongs to the same admission).

Note: the table once had a nullable `followup_id`; it is dropped by `V2026_11_20_1000` (below). The OPD follow-up is traced through `opd.follow_up.source_admission_id`.

## ipd.final_bill (append-only, one per admission)
| Column | Type | Null | Default / check |
|---|---|---|---|
| id | uuid PK | no | gen_random_uuid() |
| tenant_id | uuid | no | platform.current_tenant(); FK platform.tenant |
| admission_id | uuid | no | composite FK to ipd.admission; `uq_final_bill_tenant_admission (tenant_id, admission_id)` |
| total_paise | bigint | no | >= 0 |
| deposits_paise | bigint | no | >= 0 |
| balance_paise | bigint | no | `= total_paise - deposits_paise`; positive collected, negative refunded, 0 settled |
| settle_mode | text | yes | in Cash, UPI, Card; `(balance_paise = 0) = (settle_mode is null)` |
| created_by | uuid | no | actor id (the "settled by") |
| created_at | timestamptz | no | now() (not insertable; the "settled at") |
| bill_days | integer | no | >= 1 |
| bed_day_cut | text | no | in midnight, 24h |
| category_key | text | no | in gen, semi, pvt, icu (the category billed at discharge) |
| multiplier | numeric(4,2) | no | between 0.5 and 5 (same as `ipd.bed_category`) |

Keys: `uq_final_bill_tenant_id (tenant_id, id)` (target of the line FK). The unique `(tenant_id, admission_id)` serves the by-admission read.
The category name shown on the bill is looked up from the key and the clinic's current names; the money is frozen, the label is not.

## ipd.final_bill_line (append-only)
| Column | Type | Null | Default / check |
|---|---|---|---|
| id | uuid PK | no | gen_random_uuid() |
| tenant_id | uuid | no | platform.current_tenant(); FK platform.tenant |
| final_bill_id | uuid | no | composite FK (tenant_id, final_bill_id) to ipd.final_bill (tenant_id, id) |
| line_no | integer | no | >= 1; `uq_final_bill_line_no (tenant_id, final_bill_id, line_no)` also serves "lines of a bill in order" |
| label | text | no | 1..200 characters |
| qty | integer | no | >= 0 |
| unit_paise | bigint | no | >= 0 |
| amount_paise | bigint | no | `= unit_paise * qty` |
| segment | boolean | no | false (SAH-26 line of a bed the patient left) |

## opd.follow_up (changed)
- `source_visit_id`: `not null` dropped (the FK and `uq_follow_up_source_visit` stay).
- `source_admission_id uuid null` added, no FK (cross-module). `uq_follow_up_source_admission unique (source_admission_id)` as a plain constraint: NULLs are distinct, so `on conflict (source_visit_id) do nothing` (SAH-14) and `on conflict (source_admission_id) do nothing` (the port) both work with no predicate.
- `ck_follow_up_one_source check (num_nonnulls(source_visit_id, source_admission_id) = 1)`, added `not valid` then validated, so the table scan is separate from the DDL.
- Partial index `ix_follow_up_discharge_by_patient_doctor (tenant_id, patient_id, doctor_membership_id, created_at) where source_admission_id is not null`: serves "latest discharge of this patient with this doctor" for the OPD fee rule at token time (ADR-004 D3).
- RLS policy and grants unchanged: `sahaj_app` has table-level `select, insert, update`, which covers the new column. Still no delete.
- Existing rows: every row has a visit, so the check holds. Existing code paths: `insert ... on conflict (source_visit_id) do nothing` still works. `JdbcFollowUpRepository.CLOSE_OLDEST` uses `source_visit_id <> :returnedVisitId`, which is NULL for an admission follow-up and so would never close it. The java-developer must change it to `is distinct from` (ADR-004 D3). The row mapper already reads `source_visit_id` with `getObject(..., UUID.class)`, which is null-safe.

## Follow-up: followup_id dropped (V2026_11_20_1000)
- Why: `ipd.discharge` is insert-only (update trigger denies) and the `opd.follow_up` id exists only after `FollowUpPort.createForDischarge`, which runs last in the transaction. The column could never be filled. Tracing is available from `opd.follow_up.source_admission_id` (unique).
- Dropped: constraint `ck_discharge_followup_id` (`discharge_type <> 'death' or followup_id is null`), then the column. The column-level insert grant goes with the column.
- Kept: `ck_discharge_followup_due ((discharge_type = 'death') = (followup_due is null))`, the remaining death rule. That no follow-up row exists for a death is enforced by the Java code (the port is not called for death).
- Data: the column was always null, so nothing is lost. Dropping a column does not fire the append-only row triggers. Table comment updated.
- Compatibility: SAH-27 is not merged and no code reads or writes the column, so one step is safe (no expand-and-contract). Java insert and select statements must not list `followup_id` (the current ones do not).

## Tenant scope and RLS
All four new tables: `tenant_id` default `platform.current_tenant()`, ENABLE and FORCE RLS, separate `select` and `insert` policies for `sahaj_app` on `tenant_id = platform.current_tenant()`, no update or delete policy. Composite FKs stop a row pointing at another tenant's admission, sign-off or bill.

## Append-only
`sahaj_app` has `select` and column-level `insert` only. `id`, `tenant_id` and the defaulted timestamps (`signed_at` on the sign-off, `completed_at`, `created_at`) are not insertable. On `ipd.discharge`, `signed_at` is insertable because it is copied from the sign-off. Triggers deny update, delete and truncate through `platform.deny_audit_mutation()` on all four tables.

## Grants on existing tables (checked)
- `ipd.admission`: `grant update (status, discharged_at, version)` exists (SAH-23). `select ... for update` works with it. No change.
- `ipd.bed`: table-level `select, insert, update, delete` (SAH-22), so `occ -> clean` works. No change.
- `ipd.config`: table-level `select, insert, update`. No change.

## Concurrency (for the java-developer)
Discharge is "one row per admission" in `ipd.discharge` and `ipd.final_bill`: the unique keys are the last line of defence against a double discharge. Follow the story: IPD tenant advisory lock, then the admission and bed `for update`, check status is ADMITTED, then write. Insert order in the transaction: final_bill, final_bill_line rows (use the returned final_bill id), discharge, update admission and bed, then the follow-up port last. The sign-off insert should lock the admission row first so a sign-off and a discharge never interleave.

## Volume and retention
- `ipd.discharge`, `ipd.final_bill`, `ipd.discharge_signoff`: about one row per admission (sign-offs a few more if the doctor changes type or template). A 100-bed hospital at 80 percent occupancy and a 5-day stay is about 5,500 admissions a year, so about 6,000 rows a year per tenant per table.
- `ipd.final_bill_line`: 8 to 20 lines per bill, about 100,000 rows a year per hospital; about 10 million rows total after ten years across 100 hospitals. No partitioning.
- `opd.follow_up`: one extra row per non-death discharge (a few thousand a year per hospital). No effect on the OPD volume estimate.
- Retention: never deleted (clinical and billing records). Corrections would be new rows by a later story.

## Compatibility
Additive and expand-only. The previous app version ignores the new tables and columns. Its `opd.follow_up` insert still works (`source_visit_id` is supplied, `source_admission_id` stays null). Rollback path (only before any discharge is recorded): a new forward migration dropping the tables and columns and restoring `not null` on `source_visit_id`.

## Follow-up: tenant-composite keys on opd.follow_up (V2026_11_21_1000, QA finding F-27-3)
- Problem: `fk_follow_up_patient` and `fk_follow_up_doctor_membership` (SAH-14) were single-column. Foreign key checks bypass row level security, so `FollowUpPort.createForDischarge` in clinic B's context could insert a row pointing at clinic A's patient or doctor. Not reachable over HTTP today; this closes it in the database, like `ipd.admission` (V2026_11_13_1000).
- Change: `fk_follow_up_patient (tenant_id, patient_id) -> patient.patient (tenant_id, id)` and `fk_follow_up_doctor_membership (tenant_id, doctor_membership_id) -> platform.membership (tenant_id, id)`. The same constraint names are kept.
- Referenced keys: `uq_patient_tenant_id` already exists (V2026_11_13_1000). New: `platform.membership uq_membership_tenant_id (tenant_id, id)`. Since `id` is the primary key, the pair is unique by construction, so no existing data can violate it; the table has a few rows per clinic, so the index build is instant.
- Order inside the one migration: new keys `NOT VALID`, old keys dropped, `VALIDATE` the new keys (full-table check of existing follow-ups, which the app wrote from one tenant context), then rename to the old names. No moment without a key. If a cross-tenant row existed, `VALIDATE` would stop the migration on purpose.
- Existing indexes unchanged: `ix_follow_up_open_by_patient_doctor (tenant_id, patient_id, doctor_membership_id)` already starts with the FK columns. No delete on referenced rows (patients and memberships are never hard-deleted), so `NO ACTION` costs nothing.
- Compatibility: the app writes `tenant_id` from `platform.current_tenant()` and the patient and doctor of its own clinic, so the previous app version runs unchanged. Not changed here: `source_visit_id` and `returned_visit_id` (single-column to `opd.visit`), see `docs/database/tenant-fk-survey.md`.
