# SAH-30 · Database design: IPD nursing vitals, nursing notes, early warning escalation, clinical event log

Migration (forward-only; no existing migration is edited): `V2026_11_23_1000__ipd_vitals_ews.sql`. Four new tables in `ipd`, one guard function. No change to an existing table. Decision record: ADR-006 ("Data needed"). Mockup screen: Nursing sheet (`pcNursing`, `pcvsave`, `pcEws`, `sfRaise`, `sfLog`).

All four tables: `tenant_id uuid not null default platform.current_tenant()` (FK `platform.tenant`), ENABLE and FORCE row level security, composite FK `(tenant_id, admission_id)` to `ipd.admission (tenant_id, id)` (`uq_admission_tenant_id` exists since V2026_11_12). No single-column FK to a tenant table (see `tenant-fk-survey.md`). Actor columns (`recorded_by`, `raised_by`, `actor`) hold the signed-in user id with no FK, as in `ipd.deposit.collected_by`.

## ipd.vitals (append-only)
| Column | Type | Null | Default / check |
|---|---|---|---|
| id | uuid PK | no | gen_random_uuid() |
| tenant_id | uuid | no | platform.current_tenant() |
| admission_id | uuid | no | composite FK `fk_vitals_admission` |
| client_key | uuid | no | client-generated; `uq_vitals_tenant_client_key (tenant_id, client_key)` |
| bp_systolic | smallint | no | 40..300 (`ck_vitals_bp_systolic`) |
| bp_diastolic | smallint | no | 20..200 (`ck_vitals_bp_diastolic`) |
| pulse | smallint | no | 1..300 (`ck_vitals_pulse`) |
| spo2 | smallint | no | 1..100 (`ck_vitals_spo2`) |
| temp_c | numeric(3,1) | no | 25.0..45.0 (`ck_vitals_temp_c`) |
| rr | smallint | no | 1..300 (`ck_vitals_rr`) |
| ews_score | smallint | no | 0..15 (`ck_vitals_ews_score`) |
| ews_risk | text | no | LOW, MEDIUM, HIGH (`ck_vitals_ews_risk`) |
| note_added | boolean | no | none (the app always sends it) |
| recorded_at | timestamptz | no | now() (not insertable: transaction time = server clock) |
| recorded_by | uuid | no | user id |

Risk consistency: `ck_vitals_risk_high` `(ews_score >= 7) = (ews_risk = 'HIGH')`; `ck_vitals_risk_low` `ews_score < 5 or ews_risk <> 'LOW'`. (Score 0..4 may be MEDIUM, because one parameter at 3 makes Medium.)

Indexes:
- `ix_vitals_tenant_admission_recorded (tenant_id, admission_id, recorded_at desc)`: latest vitals of an admission (sheet, `vitalsDue`, `latestEws` of every admission answer), prefilled values.
- `uq_vitals_tenant_client_key (tenant_id, client_key)`: the replay lookup under the admission lock; last defence against a double save.
- Primary key `(id)`. Nothing else is unique.

## ipd.nursing_note (append-only)
| Column | Type | Null | Default / check |
|---|---|---|---|
| id | uuid PK | no | gen_random_uuid() |
| tenant_id | uuid | no | platform.current_tenant() |
| admission_id | uuid | no | composite FK `fk_nursing_note_admission` |
| note | text | no | `char_length(note) between 1 and 1000` (code points) |
| recorded_at | timestamptz | no | now() (not insertable) |
| recorded_by | uuid | no | user id |

Index `ix_nursing_note_tenant_admission_recorded (tenant_id, admission_id, recorded_at desc)`: "Recent notes", last three. The note has no key to the vitals row (`vitals.note_added` says a note came with the set), which keeps the vitals table partitionable.

## ipd.ews_escalation
| Column | Type | Null | Default / check |
|---|---|---|---|
| id | uuid PK | no | gen_random_uuid() |
| tenant_id | uuid | no | platform.current_tenant() |
| admission_id | uuid | no | composite FK `fk_ews_escalation_admission` |
| ews_score | smallint | no | 0..15 and `>= 3` (`ck_ews_escalation_score_min`) |
| ews_risk | text | no | MEDIUM, HIGH; `ck_ews_escalation_score_risk`: `(ews_score >= 7) = (ews_risk = 'HIGH')` |
| target_minutes | smallint | no | 15 or 60; `ck_ews_escalation_risk_target`: `(ews_risk = 'HIGH') = (target_minutes = 15)` |
| status | text | no | 'OPEN'; in OPEN, ACK, DONE (not insertable) |
| raised_at | timestamptz | no | now() (not insertable) |
| raised_by | uuid | no | user id |
| acknowledged_by, acknowledged_at | uuid, timestamptz | yes | set together (`ck_ews_escalation_ack_pair`); Safety board story |
| response_action, response_note | text | yes | Safety board story; no length check yet (see questions) |
| senior_calls | smallint | no | 0; `>= 0` |
| closed_at | timestamptz | yes | `(status = 'DONE') = (closed_at is not null)` (`ck_ews_escalation_closed`) |
| version | bigint | no | 0 (was integer; changed by V2026_11_25_1000) |

Index `ux_ews_escalation_tenant_admission_open (tenant_id, admission_id) where status <> 'DONE'` (unique): at most one open (OPEN or ACK) escalation per admission. It also serves "open escalation of this admission" (the lookup under the admission row lock) and a clinic-wide scan of open escalations (the Safety board). A second insert for the same admission fails with 23505.

Grants and triggers (the model for this table):
- `select`; `insert (admission_id, ews_score, ews_risk, target_minutes, raised_by)`; `update (ews_score, ews_risk, target_minutes, version)` only; no delete. The Safety board story widens the update grant (acknowledge, response, senior calls, status, close) in its own migration.
- `trg_ews_escalation_no_delete`, `trg_ews_escalation_no_truncate` (shared `platform.deny_audit_mutation()`, SQLSTATE 42501).
- `trg_ews_escalation_guard_update` (`ipd.guard_ews_escalation_update()`, SECURITY INVOKER, fixed `search_path`, SQLSTATE 23514 check_violation): refuses any update of a DONE row, refuses a lower `ews_score`, refuses HIGH becoming MEDIUM, and (since V2026_11_25_1000) refuses a lower `version`. Equal values pass. Backstop for the Java rule (a higher score replaces; an equal or lower one changes nothing). Also check violations from `ck_ews_escalation_risk_target` mean Java sent a risk and target that disagree: score, risk and target must be updated together in one statement.

## ipd.clinical_event (append-only)
| Column | Type | Null | Default / check |
|---|---|---|---|
| id | uuid PK | no | gen_random_uuid() |
| seq | bigint | no | `generated always as identity` (not insertable) |
| tenant_id | uuid | no | platform.current_tenant() |
| admission_id | uuid | yes | composite FK `fk_clinical_event_admission`, MATCH SIMPLE (null skips the check) |
| kind | text | no | give, order, verify, esc, view, bg, deny, stop, info |
| text | text | no | `char_length(text) between 1 and 300` |
| actor | uuid | no | user id (not the membership id the story names: ADR-006 Q11) |
| at | timestamptz | no | now() (not insertable) |

Indexes: `ix_clinical_event_tenant_at (tenant_id, at desc, seq desc)` (newest events of a clinic), `ix_clinical_event_tenant_admission_at (tenant_id, admission_id, at desc, seq desc)` (newest events of one admission). Read order is `at desc, seq desc`: the two events of one save share `at`, `seq` puts the escalation line above "Recorded vitals". `seq` is an identity column, so `sahaj_app` needs no sequence privilege. Text is fixed templates only: no patient name, no note.

## Tenant scope and RLS
Every table has `tenant_id` and ENABLE plus FORCE row level security. Policies for `sahaj_app` on `tenant_id = platform.current_tenant()`: select and insert on all four; update on `ews_escalation` only; no delete policy anywhere. Another clinic's admission is invisible, and the composite FK stops a row pointing at another tenant's admission even if the app passes a wrong id.

## Grants (sahaj_app)
- `ipd.vitals`: `select`; `insert (admission_id, client_key, bp_systolic, bp_diastolic, pulse, spo2, temp_c, rr, ews_score, ews_risk, note_added, recorded_by)`.
- `ipd.nursing_note`: `select`; `insert (admission_id, note, recorded_by)`.
- `ipd.ews_escalation`: as above.
- `ipd.clinical_event`: `select`; `insert (admission_id, kind, text, actor)`.
- Not insertable, so the database fixes them: `id`, `seq`, `tenant_id`, `recorded_at`, `raised_at`, `at`, `status`, `senior_calls`, `version`, ack and response columns. Tests that need old timestamps must write as the migrator.
- Existing tables: none changed. The FK check on `ipd.admission` needs no grant (FK checks run as the table owner).

## Append-only proofs (what refuses what)
| Table | UPDATE | DELETE | TRUNCATE |
|---|---|---|---|
| vitals, nursing_note, clinical_event | no update privilege (42501) and `trg_*_no_update_delete` (42501) | no privilege and trigger (42501) | no privilege and `trg_*_no_truncate` (42501) |
| ews_escalation | only the four granted columns; guard trigger (23514); other columns 42501 | no privilege and trigger (42501) | no privilege and trigger (42501) |

The triggers fire for every role, including the migrator and the table owner, so a role that later gets a privilege still cannot change these rows. Message text contains "append-only". QA proofs: as `sahaj_app`, update/delete/truncate each fail; as the migrator, update/delete/truncate fail too (trigger); insert of a column outside the grant fails (42501); cross-tenant `admission_id` insert fails (23503 for the FK or 42501/RLS violation).

## Error codes for the java-developer
| Condition | SQLSTATE | Constraint / source | Meaning |
|---|---|---|---|
| Vital outside its range, score or risk inconsistent | 23514 | `ck_vitals_*` | validation should stop it first (400); a hit here is a code bug (500) |
| Note empty or over 1000 characters | 23514 | `ck_nursing_note_note` | code validates first; empty note must not be stored |
| Event text empty or over 300 | 23514 | `ck_clinical_event_text` | code bug |
| Second open escalation for one admission | 23505 | `ux_ews_escalation_tenant_admission_open` | never swallow (ADR-006 D4): map to 500, the whole save rolls back |
| Repeat clientKey | 23505 | `uq_vitals_tenant_client_key` | the replay lookup under the admission lock should have caught it; a hit is a bug |
| Escalation update of a DONE row, or score/risk falling | 23514 | `ipd.guard_ews_escalation_update()` | code bug; the service must compare first and update only when the new score is strictly higher |
| Risk and target disagree on escalation | 23514 | `ck_ews_escalation_risk_target` | set `ews_score`, `ews_risk`, `target_minutes` together |
| Update/delete/truncate on an append-only table | 42501 | `platform.deny_audit_mutation()` | never attempted by the code |
| admission_id of another clinic or missing | 23503 | `fk_*_admission` | the admission is read first under RLS (404) |

## Concurrency (for the java-developer)
- Save: lock the admission `for no key update` first (ADR-006 D4), READ COMMITTED. Then replay lookup, status check, insert vitals, insert note if any, read the open escalation (`status <> 'DONE'`), insert or update it, insert the events. No advisory lock, no bed lock.
- The same lock must be taken by Acknowledge/Response/Call senior/Close in the Safety board story.
- Insert order inside one transaction is free of FK ordering issues: all four tables reference only `ipd.admission`.
- `recorded_at`, `raised_at` and `at` are the transaction start time, so all rows of one save carry the same instant (AC5) and `asOf` can use it.

## Expected rows per tenant per year
Assumptions from ADR-006: IPD hospital with 150 occupied beds, 6 to 12 vitals sets per patient-day, 4-day average stay (about 13,700 admissions a year), 100 IPD hospitals. OPD clinics have no rows in these tables.
- `ipd.vitals`: 330,000 to 660,000 rows per hospital per year (design for 700,000); 33 to 66 million for 100 hospitals. About 120 bytes of heap per row plus three indexes (PK, client_key, read index), roughly 250 bytes in all: about 8 to 17 GB a year in total.
- `ipd.nursing_note`: about one note in three sets: 110,000 to 220,000 rows per hospital per year; about 150 bytes a row.
- `ipd.ews_escalation`: only Medium or High, one open at a time: roughly 0.3 to 0.5 per admission, 4,000 to 7,000 rows per hospital per year; under 1 million in total for 100 hospitals. Small table.
- `ipd.clinical_event`: one `info` line per save plus escalation lines: 360,000 to 720,000 rows per hospital per year in this story; SAH-31 and SAH-32 (medicines given, orders) add more, so plan for 1.5 to 2 times that. Rows are about 100 bytes.

## Partitioning plan (rule: plan before 50 million rows)
`ipd.vitals` passes 50 million rows within the first year of full use, `ipd.clinical_event` soon after SAH-32. Both start unpartitioned, as the story and ADR-006 D2 say, but nothing blocks range partitioning on `recorded_at` (`at` for events):
- No foreign key references `ipd.vitals` or `ipd.clinical_event` (the escalation stores its own score; the note has no key to the vitals row).
- The only unique constraints are the primary key and `(tenant_id, client_key)`. On a partitioned table every unique key must include the partition key: the primary key becomes `(id, recorded_at)` and `client_key` becomes a local unique index per partition. The admission row lock (not the index) is the real guard against a double save, as designed.
- Procedure at about 30 million rows (a runbook item opened at go-live; do it with the migrator in a maintenance window):
  1. Create `ipd.vitals_p` partitioned by range on `recorded_at` with primary key `(id, recorded_at)`; one partition per quarter, monthly when growth is at the top of the range (the rule says monthly; quarterly is the planned departure while volume is lower).
  2. On the OLD table first: `create unique index concurrently ... on ipd.vitals (id, recorded_at)`, then `add check (recorded_at < <cut-off>) not valid` and `validate constraint`.
  3. `attach partition` then finds the matching index and skips the scan. It is NOT a metadata-only step without step 2: the attach would build the index and block writes.
  4. `(tenant_id, client_key)` cannot stay a global unique key on a partitioned table; keep a local unique index per partition and rely on the admission row lock as the double-save guard.
  5. Rename the tables and create the future partitions ahead of time with a scheduled job. The read index `(tenant_id, admission_id, recorded_at desc)` is created on the parent; RLS policies, grants and triggers are re-created on the parent. The app queries only the parent, so partitions get no grant for `sahaj_app`.
- Archival later: detach and move whole old partitions (retention rule below).
- `ipd.clinical_event` follows the same procedure; it has an identity `seq` that stays on the parent.

## Retention and personal data
- Never deleted in phase 1: no delete privilege; triggers refuse delete and truncate for every role. Vitals, notes, escalations and events are in-patient medical record. IMC Regulations 2002 (1.3.1) ask doctors to keep in-patient records at least 3 years; a DPDP erasure request does not override that duty. **Human decision (ADR-006 Q10):** the retention period. Closing a clinic's subscription does not delete these rows.
- Vitals values and note text are clinical data: shown on screen only, never in logs, audit events or `clinical_event.text`. The event log holds fixed templates, numbers and ids only.
- No patient name or mobile is stored in these tables; they join to `patient.patient` through `ipd.admission`.

## Compatibility
Additive and expand-only. The previous app version ignores the four new tables; no existing table, column, grant or policy changes, so it runs unchanged against the new schema. Rollback path (only before any row exists): a new forward migration dropping the four tables and the guard function.

## Questions
1. `ews_escalation` has extra checks beyond ADR-006's list: `ck_ews_escalation_ack_pair` (acknowledged_by and acknowledged_at together) and `ck_ews_escalation_closed` (`status = 'DONE'` iff `closed_at` is set). They anticipate the Safety board story; if that story needs a different shape (for example DONE without acknowledgement is fine, as written), it relaxes them in its own migration. The optional guard trigger of the ADR was added (score and risk never fall; DONE is frozen).
2. `response_action` and `response_note` have no length check because the Safety board contract does not exist yet; the Safety board migration should add them (suggest 1..100 and 1..1000).
3. Done in the fix pass: `ews_risk` is now checked against `ews_score` (see Fix pass below).
4. Retention period (ADR-006 Q10): human to set; partition archival follows it.
5. OQ-30-4: the DB ranges are the ADR proposals and still need the doctor's confirmation. Widening a range later is a new migration (drop and re-add the check).

## Fix pass (review SAH-30: code M7, N2, N3; security L5) - V2026_11_25_1000__ipd_vitals_fixes.sql
V2026_11_23_1000 is applied and not edited. The new migration is forward-only:
- `ck_ews_escalation_score_risk`: `(ews_score >= 7) = (ews_risk = 'HIGH')` (N2, L5). Created as `ck_ews_escalation_risk_score` in V2026_11_25_1000 and renamed in V2026_11_25_1010__ipd_ews_escalation_check_name.sql so that it sorts after `ck_ews_escalation_score` and `ck_ews_escalation_risk_target` (PostgreSQL checks in name order; a bad range or a risk/target mismatch is still reported by its own constraint).
- `ck_ews_escalation_score_min`: `ews_score >= 3` (L5). Logic checked against `EarlyWarningScore`: HIGH is a total of 7 or more; MEDIUM is a total of 5 or 6, or one parameter scoring 3, so a MEDIUM total is 3 to 6 and never below 3. An escalation exists only for MEDIUM or HIGH, so every valid row satisfies both checks. No existing row can violate them (new table, written only by the app); the checks are added validated.
- `version` changed from integer to bigint (N3). Safe: tiny table (under 1 million rows in total), the rewrite holds its lock for a moment; the column privilege, default and trigger stay. The app reads it with `getInt`, which still works for small values; the java-developer may move to `getLong`.
- Guard function `ipd.guard_ews_escalation_update()` also refuses `new.version < old.version` (23514). Equal or higher passes. Function stays revoked from public and `sahaj_app`.
- Partition runbook (M7): table comments on `ipd.vitals` and `ipd.clinical_event` corrected to the procedure above: `create unique index concurrently` on `(id, recorded_at)` / `(id, at)` on the old table first, then attach; quarterly, monthly at the top of growth (a stated departure from the monthly rule).
- Not a migration: L6 (catalog IT for enabled triggers is qa-engineer; the `validate db` check for `disable trigger` / `no force row level security` is humans, `scripts/`).
- Compatibility: the previous app version runs unchanged (same columns and grants; version still readable as an int).
