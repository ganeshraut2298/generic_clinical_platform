# SAH-28 · Database design: IPD emergency queue, triage, Emergency setup

Migration (forward-only; no existing migration is edited): `V2026_11_22_1000__ipd_emergency.sql`. Five new columns on `ipd.config`, two new tables in `ipd`, one check helper function, one guard function. Decision record: ADR-005.

## ipd.config (five new columns)
| Column | Type | Null | Default / check |
|---|---|---|---|
| triage_scale | smallint | no | 3; `ck_ipd_config_triage_scale` in (3, 5) |
| triage_targets_3 | smallint[] | no | `{5,30,120}`; `ck_ipd_config_triage_targets_3` = `ipd.triage_targets_ok(col, 3)` |
| triage_targets_5 | smallint[] | no | `{0,10,30,60,120}`; `ck_ipd_config_triage_targets_5` = `ipd.triage_targets_ok(col, 5)` |
| er_bays | smallint | no | 2; `ck_ipd_config_er_bays` between 0 and 10 |
| unknown_patient_registration | boolean | no | true |

`ipd.triage_targets_ok(smallint[], integer)`: immutable SQL function (fixed `search_path`). True when the array is one-dimensional, starts at index 1, has exactly the given number of elements, none null, each 0 to 240 and a multiple of 5. The two target sets are kept separately, so a scale switch never loses the other set. Not enforced in the database: order of the values (for example, increasing); the Java code and the stepper decide.
The row is lazy. Existing rows get the defaults. `sahaj_app` already has table-level `select, insert, update` (SAH-22), so no grant is added. The Java upsert must insert a row with defaults when none exists (as in SAH-26/27).

## ipd.emergency_case
| Column | Type | Null | Default / check |
|---|---|---|---|
| id | uuid PK | no | gen_random_uuid() |
| tenant_id | uuid | no | platform.current_tenant(); FK platform.tenant |
| patient_id | uuid | yes | composite FK (tenant_id, patient_id) to patient.patient (tenant_id, id); set only when ADMITTED |
| display_name | text | no | 1..80 characters, not blank |
| age | smallint | yes | 0..120 (null = unknown, shown "?") |
| sex | text | no | in M, F, O |
| mobile | text | yes | `^[6-9][0-9]{9}$` |
| complaint | text | no | 1..200 characters, not blank |
| identity_pending | boolean | no | false |
| level | smallint | no | 1..5 (meaning depends on the clinic scale; level <= scale is checked in the event table and by Java) |
| bay | text | yes | `^ER-([1-9]|10)$`; insert-only (no update grant) |
| status | text | no | 'WAITING'; in WAITING, SEEN, ADMITTED, RELEASED, REFERRED |
| arrived_at | timestamptz | no | now() (not insertable; the server clock is the arrival time) |
| registered_by | uuid | no | actor id |
| seen_at, seen_by | timestamptz, uuid | yes | set together (`ck_emergency_case_seen_pair`) |
| closed_at, closed_by | timestamptz, uuid | yes | see status rules below |
| admission_id | uuid | yes | composite FK (tenant_id, admission_id) to ipd.admission (tenant_id, id) |
| client_key | uuid | no | client-generated; `uq_emergency_case_tenant_client_key (tenant_id, client_key)` makes a repeat registration return the first case |
| version | integer | no | 0 |

Status rules (table checks):
- WAITING: no `seen_*`, no `closed_*`, no `admission_id`.
- SEEN: `seen_at` and `seen_by` set; no `closed_*`.
- ADMITTED: `closed_at`, `closed_by`, `admission_id` and `patient_id` set.
- RELEASED, REFERRED: `closed_at` and `closed_by` set, no `admission_id`. `seen_*` stay when the case was seen first.
- `admission_id` is set if and only if status is ADMITTED.

Keys and indexes (each with the query it serves):
- `uq_emergency_case_tenant_id (tenant_id, id)`: target of the event FK; by-id load and `for update`.
- `uq_emergency_case_tenant_client_key (tenant_id, client_key)`: the idempotent registration lookup (`on conflict` or select-first).
- `ix_emergency_case_tenant_status_arrived (tenant_id, status, arrived_at)`: the queue (status in WAITING, SEEN, sorted by level then arrival in Java; at most a few dozen open rows), the tile and menu counts.
- `ux_emergency_case_tenant_bay_open (tenant_id, bay) where bay is not null and status in ('WAITING','SEEN')`: two open cases never hold one bay; "bays free X of Y" reads the held bays; a closed case frees its bay by status and keeps the value as history.
- `ux_emergency_case_tenant_admission (tenant_id, admission_id) where admission_id is not null`: an admission is linked to at most one case; "case of this admission".

Triggers:
- `trg_emergency_case_no_delete`, `trg_emergency_case_no_truncate` (shared `platform.deny_audit_mutation()`): rows are never deleted.
- `trg_emergency_case_guard_update` (`ipd.guard_emergency_case_update()`, SECURITY INVOKER, fixed `search_path`): an update on a case that is ADMITTED, RELEASED or REFERRED is refused, and SEEN never goes back to WAITING. SQLSTATE 23514 (check_violation). This is a backstop; the Java code still answers 409 `case-closed` first, from the locked row. Added by the db-engineer beyond the architect's list (see handoff).

## ipd.emergency_triage_event (append-only)
| Column | Type | Null | Default / check |
|---|---|---|---|
| id | uuid PK | no | gen_random_uuid() |
| tenant_id | uuid | no | platform.current_tenant(); FK platform.tenant |
| case_id | uuid | no | composite FK (tenant_id, case_id) to ipd.emergency_case (tenant_id, id) |
| from_level | smallint | yes | 1..5; null only for REGISTERED |
| level | smallint | no | 1..5; `level <= scale` |
| scale | smallint | no | in 3, 5 (the scale in force when recorded) |
| reason | text | no | in REGISTERED, RETRIAGED, SCALE_CHANGED; `(reason = 'REGISTERED') = (from_level is null)` |
| recorded_at | timestamptz | no | now() (not insertable) |
| recorded_by | uuid | no | actor id (for SCALE_CHANGED: the owner who changed the setting) |

Keys: `uq_emergency_triage_event_tenant_id (tenant_id, id)`. Index `ix_emergency_triage_event_tenant_case_recorded (tenant_id, case_id, recorded_at)`: level history of one case, oldest first.
`from_level` may equal `level` for a SCALE_CHANGED row (5 to 3: Level 1 becomes Red, both 1). That is allowed on purpose.

## Tenant scope and RLS
Both tables: `tenant_id` default `platform.current_tenant()`, ENABLE and FORCE RLS. `emergency_case`: separate `select`, `insert` and `update` policies for `sahaj_app` on `tenant_id = platform.current_tenant()`, no delete policy. `emergency_triage_event`: `select` and `insert` policies only. Composite FKs stop a row pointing at another tenant's patient, admission or case (FK checks bypass RLS, so the tenant column is part of every key).

## Grants (sahaj_app)
- `ipd.emergency_case`: `select`; `insert (display_name, age, sex, mobile, complaint, identity_pending, level, bay, registered_by, client_key)`; `update (level, status, seen_at, seen_by, closed_at, closed_by, admission_id, patient_id, version)`; no delete.
  Not insertable, so the database fixes them: `id`, `tenant_id`, `status` (always WAITING), `arrived_at` (server clock), `version` (0), `patient_id`, `seen_*`, `closed_*`, `admission_id`. Not updatable: identity fields, `bay`, `arrived_at`, `registered_by`, `client_key`, `tenant_id`.
- `ipd.emergency_triage_event`: `select`; `insert (case_id, from_level, level, scale, reason, recorded_by)`; no update, no delete; update, delete and truncate also denied by triggers.
- Existing tables, checked:
  - `ipd.admission`: the insert grant already lists `source` (SAH-23) and the check `ck_admission_source` allows EMERGENCY, so the admit inserts source EMERGENCY. No change.
  - `ipd.admission` bed guard (`trg_admission_guard_bed_change`, V2026_11_19): fires only on `update of bed_id`. The admit is an insert of a new admission, and the link afterwards updates only `ipd.emergency_case`. No interaction. `ux_admission_tenant_bed_admitted` (one ADMITTED admission per bed) still applies to the new admission as for any admit.
  - `ipd.config`: table-level `select, insert, update`; covers the new columns.

## Concurrency (for the java-developer)
- Run register, bay choice, re-triage, close, admit-link and settings change under the clinic IPD advisory lock (story section 3). The partial unique index on the bay is the last line of defence: a second open case with the same bay fails with unique violation 23505, which must be retried or reported, never silently ignored.
- Idempotent registration: look up `(tenant_id, client_key)` first; on unique violation 23505 of `uq_emergency_case_tenant_client_key`, read and return the existing case.
- Admit link: lock the case `for update`, check status is WAITING or SEEN (else 409 `case-closed`), insert the admission (source EMERGENCY), then update the case: `status = 'ADMITTED'`, `admission_id`, `patient_id`, `closed_at`, `closed_by`, `version = version + 1`, all in one update (the table checks need them together).
- Closing (release/refer): one update setting `status`, `closed_at`, `closed_by`, `version`. A seen case keeps its `seen_*`.
- Seen: one update setting `status = 'SEEN'`, `seen_at`, `seen_by`, `version`.
- Re-triage and scale change: update `level` (+ `version`) and insert an event row (`from_level`, `level`, `scale`, reason RETRIAGED or SCALE_CHANGED) in the same transaction. Registration inserts the case and then a REGISTERED event (`from_level` null).
- `arrived_at` and `recorded_at` use the transaction start (`now()`). Integration tests that need old arrival times must write them as the migrator, since `sahaj_app` cannot set them.
- `bay` cannot be changed after insert (no update grant). To free a bay, close the case.

## Expected rows per tenant per year
- `ipd.emergency_case`: a hospital with an emergency room: about 20 to 100 arrivals a day, so 7,000 to 36,000 rows a year (design for 40,000). 100 IPD hospitals, ten years: about 40 million rows at the very top, more likely under 15 million. Under the 50 million limit, so no partitioning now. Re-check against the estimate after a year of real data. The hot queries touch only open cases through `(tenant_id, status, arrived_at)`.
- `ipd.emergency_triage_event`: one REGISTERED row per case plus re-triages (about 0.3 a case) and scale-change rows (rare): about 1.4 rows per case, so up to 55,000 rows a year per hospital.
- `ipd.config`: still one row per tenant.
- OPD clinics (10,000) have no rows in these tables.

## Retention and personal data
- Rows are never deleted: no delete privilege, delete and truncate denied by trigger. A released or referred case is clinical history. Corrections would be new rows by a later story.
- `display_name`, `age`, `sex`, `mobile` and `complaint` are personal (and for the complaint, health) data stored outside `patient.patient`, with `patient_id` null for patients who are never admitted. After a case closes, nothing in the product shows these fields again (the queue shows open cases only), but they stay in the table.
- **Human decision needed (DPDP Act 2023):** how long closed cases may keep the name and mobile, and whether they are then masked. Options for the human: keep for the clinic's medical-record period; mask `display_name` and `mobile` after N days for RELEASED and REFERRED cases. Masking needs a new migration with a narrow, audited update path (the identity columns are not updatable by `sahaj_app` today), so it is a deliberate design step, not a quiet job. Until decided, the data is kept as is, and application code, logs and audit text must never contain it (AC12).
- The event table holds no personal data (case id, levels, times, actor id).

## Compatibility
Additive and expand-only. The previous app version ignores the new tables and columns; its `ipd.config` upserts still work because every new column has a default. No change to `ipd.admission`. Rollback path (only before any case exists): a new forward migration dropping the two tables, the two functions and the five columns.

## Fix pass (review items 2 and 9, security M1(a) and I1)
Migration `V2026_11_24_1000__ipd_emergency_fixes.sql` (forward-only; `V2026_11_22_1000` is not edited).
- **One clock (code review 2).** `ipd.emergency_triage_event.recorded_at` and `ipd.emergency_case.arrived_at` now default to `clock_timestamp()` instead of `now()`. `now()` is the transaction start, before the clinic advisory lock; `clock_timestamp()` is read when the row is written, after the lock, so the stored order follows the write order. Reason for `arrived_at` too: the arrival time must also be taken after the lock. Neither column is writable by `sahaj_app`, and tests that back-date arrivals do an UPDATE as the table owner, so they still work. Caveat: in one transaction `arrived_at` can now be later than `now()`.
- **Mobile cleared on closing (security M1(a)).** New grant `update (mobile)` for `sahaj_app`. The guard `ipd.guard_emergency_case_update()` now has three rules, all SQLSTATE 23514 (`check_violation`):
  1. a case that is ADMITTED, RELEASED or REFERRED refuses every update (message "emergency case is closed (update refused)"), including a mobile change;
  2. SEEN never returns to WAITING (unchanged);
  3. `mobile` may change only to null and only in the same UPDATE that sets the closing status (ADMITTED, RELEASED or REFERRED) on a WAITING or SEEN case. Any other change of `mobile` (to another number, or to null while the case stays open, or from null to a number) is refused with "emergency case mobile can only be cleared when the case is closed". An update that leaves `mobile` as it is passes as before.
  While the case is open the mobile is stored and readable, so the Admit hand-off still reads it (lock the open case, read, then close and clear in one update). `close` and `markAdmitted` must set `mobile = null` in their UPDATE.
- **Existing closed rows.** The migration nulls `mobile` on rows already closed. This is a one-off data migration, safe because: the table is new on this unreleased branch (only dev/test rows); nothing reads `mobile` of a closed case; it is idempotent (`where mobile is not null`); and it is limited to the one column of closed rows. To pass the guard and FORCE RLS (the policies are for `sahaj_app` only) the migrator, as table owner, lifts FORCE RLS and disables the guard trigger for that one transaction and restores both at the end of the same script.
- **patient_id (I1).** New check `ck_emergency_case_patient_admitted`: `patient_id is null or status = 'ADMITTED'`, added plainly (a violation would fail the migration loudly; no row can violate it when the Java code only sets `patient_id` in the admit update). Together with `ck_emergency_case_admitted` this gives: `patient_id` is set exactly when ADMITTED.
- **Not changed.** Retention and masking of name and complaint after closing (security M1(b)-(d), DEC-013 item 12) stay a human decision. No new table, index or policy; RLS and FORCE are as before. Compatibility: the previous app version still works (it never sets `mobile` on update and never sets `patient_id` on an open case); the new checks only refuse writes it does not make.
