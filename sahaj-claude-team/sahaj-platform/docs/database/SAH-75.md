# SAH-75 · EMR design alignment · database design

Source: ADR-026 D2 to D7 and "Data needed" (`docs/architecture/adr/ADR-026-opd-emr-design-alignment.md`), `docs/api/opd.yaml` 0.7.0 and 0.7.1 (follow-up, section 7), stories `docs/stories/SAH-75.md`, DEC-052 (and addendum), DEC-053 (contract approved with all defaults, H4 and H5 stay out). Module: opd only. No new module, no cross-module foreign key except `platform.tenant` and `patient.patient`.
Tag on every seeded value: "value from the EMR mockup, not clinically reviewed (SAH-75, DEC-052)".

## Migrations (forward-only; nothing at or before `V2026_12_13_1010` is edited)
| File (`backend/src/main/resources/db/migration/`) | What | Tables touched |
| --- | --- | --- |
| `V2026_12_14_1000__opd_emr_library_chips_and_dental_sections.sql` | `presentation` and `is_alert` columns; 7 library sections, 20 fields, 91 options | `opd.emr_section`, `opd.emr_section_field`, `opd.emr_section_field_option` (global) |
| `V2026_12_14_1010__opd_emr_starter_defaults_v2.sql` | placements and flags per ADR-026 D2; new starter layout version 2 for all 15 starters; `current_layout_version` 1 to 2 | `opd.emr_starter_department_section`, `opd.emr_starter_layout` (insert only), `opd.emr_starter_department` (global) |
| `V2026_12_14_1020__opd_treatment_course_actions.sql` | course and sitting columns and checks | `opd.treatment_course`, `opd.treatment_course_sitting` (tenant, no backfill) |
| `V2026_12_14_1030__opd_emr_cancer_journey.sql` | new append-only table | `opd.emr_cancer_journey` (tenant) |
| `V2026_12_14_1040__opd_emr_option_hint.sql` | 0.7.1 follow-up: `hint` column and check (section 7) | `opd.emr_section_field_option` (global) |
| `V2026_12_14_1050__opd_emr_library_gynae_menstrual_contraception.sql` | 0.7.1: Gynae Menstrual history retyped in place, new `cycle_length`; Contraception `method` and its hints (section 7) | `opd.emr_section_field`, `opd.emr_section_field_option` (global) |
| `V2026_12_14_1060__opd_emr_starter_layout_v3_og_onco.sql` | 0.7.1: onco card order; starter layout version 3 for og and onco only (section 7) | `opd.emr_starter_department_section`, `opd.emr_starter_layout` (insert only), `opd.emr_starter_department` (global) |

The two global files `V2026_12_14_1000` and `V2026_12_14_1010` touch only the six tables of the allow-list below (the three follow-up files in section 7 touch the same six, never more). The two tenant files add columns and one table; they update no existing tenant row. The three follow-up files change no tenant table at all.

### Global tables allow-list (static IT, ADR-026 D2 proof 1)
Every INSERT, UPDATE or DELETE statement in `V2026_12_14_1000` and `V2026_12_14_1010` targets only: `opd.emr_section`, `opd.emr_section_field`, `opd.emr_section_field_option`, `opd.emr_starter_department`, `opd.emr_starter_department_section`, `opd.emr_starter_layout`. (The only other table named in DML is the session temporary table `tmp_sah75_order`, created and dropped inside `V2026_12_14_1010`; the IT should allow it by name or strip `create temporary table ... on commit drop` first.) `ALTER TABLE` in those two files names only `opd.emr_section_field` and `opd.emr_section_field_option`. `V2026_12_14_1020` and `V2026_12_14_1030` contain no INSERT, UPDATE or DELETE at all (only `alter table`, `create`, `grant`, `comment`).

## 1. Global seed (opd, `sahaj_app` SELECT only, unchanged)
### 1.1 New columns
| Table | Column | Type | Null | Default | Check |
| --- | --- | --- | --- | --- | --- |
| `emr_section_field` | `presentation` | `text` | no | `'DEFAULT'` | `ck_emr_section_field_presentation`: `DEFAULT` or `CHIPS` |
| `emr_section_field_option` | `is_alert` | `boolean` | no | `false` | none |

Existing 313 fields and 136 options keep the defaults (checked inside the migration). Adding a constant-default column does not rewrite the table; the tables hold hundreds of rows. No new grant needed (the table-level SELECT covers new columns).

### 1.2 New library sections (kind SPECIALTY, positions 101 to 107)
| Section key | Name | Fields (key: type, options) |
| --- | --- | --- |
| `local_exam` | Local examination | `body_map`: BODY_MAP, meta "Front / back", not required |
| `dental_medhx` | Medical history and alerts | `conditions`, `medicines`, `allergies`: CHIPS, 9 / 6 / 5 options, each with a `none` option (`is_none`); every other option `is_alert` |
| `dental_hx` | Dental history and habits | `last_dental_visit` (4), `brushing` (3): SINGLE_CHOICE; `habits` (7): CHIPS, every habit `is_alert` |
| `dental_pain` | Pain | `type` (7): CHIPS; `score` (`o0` to `o10`), `since` (4): SINGLE_CHOICE |
| `dental_extraoral` | Extra-oral examination | `face` (2), `tmj` (4), `lymph_nodes` (3), `mouth_opening` (2): SINGLE_CHOICE |
| `dental_intraoral` | Intra-oral examination | `oral_hygiene` (3), `gingiva` (4), `calculus` (`none`, `plus_1`, `plus_2`, `plus_3`), `occlusion` (3): SINGLE_CHOICE; `soft_tissue` (5): CHIPS, `normal` is `is_none`, Ulcer / White patch / Red patch / Tongue coating `is_alert` |
| `dental_xray` | Radiographs and photos | `images` (`iopa`, `opg`, `bitewing`, `cbct`, `intra_oral_photos`): CHIPS (option "IOPA" without a tooth, H17) |

Every chip field has `presentation = 'CHIPS'`, `options_source = 'FIELD'`, `allow_free_text = false`. Totals after the migration: 107 sections, 333 fields, 227 options. No field key is `specialty` (reserved by `ck_emr_section_field_key_not_reserved`). Option keys follow the library rule (`o6_12_months`, `plus_2`). Where the design's chip list and `isNone` meet: "Normal" in Soft tissue is `is_none` because the design clears the other chips when it is chosen (many-choice); single-choice rows have no `is_none`.
The `description` of `local_exam` ("Mark local findings on the body map, front and back.") and `dental_extraoral` ("Face, jaw joint, lymph nodes and mouth opening.") are ours (the design draws no sub line and the column is not null); the other five are the drawn sub lines. For the owner to confirm (tagged, trivial).

### 1.3 Starter placements (`emr_starter_department_section`, 341 to 349 rows)
Exact result, in the table the stories name. "Before" is `V2026_12_08_1030`.
| Starter | Required after | Other changes |
| --- | --- | --- |
| gm | complaints, vitals, diagnosis, rx | `local_exam` (ASSESSMENT, shown, optional, print on, copy off, who doctor) after `exam`; `procedures` shown (was hidden); Treatment order diagnosis, rx, investigations, procedures, advice, followup (T2) |
| dental | complaints, dental_medhx, diagnosis, tx_plan | six cards after `complaints` in the drawn order (ASSESSMENT, shown, optional except `dental_medhx`, print off, copy off, who doctor); `intraoral` and `extraoral` kept, `shown = false`; Treatment order diagnosis, dental_chart, tx_plan, rx, advice, then the rest |
| og | anc, complaints, diagnosis, menstrual, pcpndt, rx | `menstrual` now required |
| surgery | complaints, sx_fitness, diagnosis, sx_plan, sx_consent | `rx` now optional; `local_exam` (same settings as gm) after `sx_findings`, before `sx_fitness` |
| onco | diagnosis, rx | `complaints` now optional |
| all 15 | `rxformat` not required (stays shown) | none |

**Dental required before the migration (SAH-75-4 report):** complaints, diagnosis, rx, dental_chart (and rxformat). Of the four sections the story names, **complaints and diagnosis were already required**; **dental_medhx** is new (did not exist) and **tx_plan** was not required (becomes required). **rx and dental_chart stop being required** (story and H18). Other starters' changes against "before": gm vitals (was optional), og menstrual (was optional), surgery sx_fitness, sx_plan, sx_consent (were optional) and rx (was required), onco complaints (was required), rxformat (was required in all 15).
Positions of gm (20), dental (30) and surgery (27) are renumbered 1..n without a gap (two-step update because the unique key on `(starter_key, position)` is not deferrable); the other 12 starters keep their positions. A placement is never removed.
New placements (8): gm `local_exam`; surgery `local_exam`; dental `dental_medhx`, `dental_hx`, `dental_pain`, `dental_extraoral`, `dental_intraoral`, `dental_xray`. `shown or not required` holds for every row (existing check).
Choices not named in the ADR (for the owner to confirm, tagged): the six Dental cards have print off, copy off, doctor only; surgery `local_exam` has the same settings as the gm one.

### 1.4 Starter layout version 2
One new row per starter in `emr_starter_layout` (`layout_version` 2), built from the placements and library with the same builder as version 1, plus per field `presentation` and per option `alert` (the names of `opd.yaml` 0.7.0; version 1 documents have neither: read as `DEFAULT` and `false`). `emr_starter_department.current_layout_version` is 2 for all 15. Version 1 rows are not touched (15 rows, byte for byte). The key `fk_emr_starter_department_layout` is deferred, so the insert and the update are one transaction.
**Immutability triggers:** `trg_emr_starter_layout_immutable` and `trg_emr_starter_layout_no_truncate` refuse UPDATE, DELETE and TRUNCATE. This migration only INSERTS, so **no trigger is disabled** (V2026_12_13_1010 had to correct an existing row; this does not). The closing block asserts that both triggers exist and are enabled. If any check in the migration fails the transaction rolls back.
Layout JSON size: about 15 to 40 KB each, under the 512 KB check.

### 1.5 Effect on stored data (what the migration does not do)
No clinic department draft, department version, EMR record or its snapshot reference, form, course, patient or billing row is read or written. A record keeps the starter version it opened with (v1 for records opened before). A TEMPLATE department (no row) shows version 2 at once (intended, SAH-75-4). The code change "department set = latest version union current starter union live forms" (ADR-026 D2) is java; a published department never changes in the database.

## 2. Tenant tables (opd)
### 2.1 `opd.treatment_course` (V2026_12_10_1120) new column
| Column | Type | Null | Default | Note |
| --- | --- | --- | --- | --- |
| `estimate_signed` | `boolean` | no | `false` | set by "start from plan" when the estimate is signed; old rows false (the read treats a note equal to "estimate signed by patient", ignoring case, as signed; nothing rewritten) |

Grant: `update (estimate_signed)` to `sahaj_app` (insert is table-level already).

### 2.2 `opd.treatment_course_sitting` new columns
| Column | Type | Null | Check or key |
| --- | --- | --- | --- |
| `missed_at` | `timestamptz` | yes | `ck_treatment_course_sitting_missed_pair`: set together with `missed_by_membership_id` |
| `missed_by_membership_id` | `uuid` | yes | opaque membership id (no FK, cross-module) |
| `removed_at` | `timestamptz` | yes | `ck_treatment_course_sitting_removed_pair`: set together with `removed_by_membership_id` |
| `removed_by_membership_id` | `uuid` | yes | opaque |
| `done_source` | `text` | yes | `ck_treatment_course_sitting_done_source`: `MARK_DONE` or `SIGN`; only with `done_at` (`..._done_source_needs_done`); null on old rows (read as SIGN) |
| `done_by_membership_id` | `uuid` | yes | only with `done_at` (`..._done_by_needs_done`); required when `done_source = 'MARK_DONE'` (`..._mark_done_by`) |
| `regimen_key` | `text` | yes | `fk_treatment_course_sitting_regimen` to `opd.emr_chemo_protocol (protocol_key)` (immutable library, same module); CHEMO courses only (application rule) |

State checks: `ck_treatment_course_sitting_removed_not_done` (`removed_at is null or done_at is null`) and `ck_treatment_course_sitting_missed_state` (`missed_at is null or (done_at is null and removed_at is null)`). Not in the DB (application): a linked sitting cannot be missed; Remove only from PLANNED; `regimen_key` only for CHEMO. Grant: `update (missed_at, missed_by_membership_id, removed_at, removed_by_membership_id, done_source, done_by_membership_id, regimen_key)`; insert stays table-level; no delete.
**Note for the java-developer:** because `removed_at` and `done_at` exclude each other, the sign step ("set `done_at` when null", ADR-025 D1 step 9) must skip a REMOVED sitting (and Remove must be refused for a sitting a visit is linked to, or the sign skips it). A sign that wrote `done_at` on a removed row would fail with SQLSTATE 23514 and roll back the signature.
Indexes: none added. A course's sittings in order, one visit or admission per sitting, are served by `uq_treatment_course_sitting_no`, `uq_treatment_course_sitting_visit`, `uq_treatment_course_sitting_admission`. The query "open admission requests" (`admission_requested`, tenant-first) reads a hundred IPD clinics' few thousand sittings; revisit if its plan shows a sequential scan on a large tenant. The new columns are not filtered on across courses.
RLS, policies and tenant-first indexes: unchanged (ENABLE + FORCE, `p_treatment_course_tenant`, `p_treatment_course_sitting_tenant`). Rows per clinic per year: unchanged (tens to hundreds of courses, a few times that in sittings). Retention: never deleted ("Remove" sets `removed_at`).
Migration safety: all new columns are nullable (or constant default); no rewrite, no backfill. Adding the checks and the foreign key scans the table once under a short lock (set `lock_timeout = 30s`); the tables are small (tens of thousands of rows at most across all clinics in phase 1), and every old row passes because all new columns are null.

### 2.3 `opd.emr_cancer_journey` (new, append-only)
| Column | Type | Null | Default | Check |
| --- | --- | --- | --- | --- |
| `id` | `uuid` PK | no | `gen_random_uuid()` | |
| `tenant_id` | `uuid` | no | `platform.current_tenant()` | FK `platform.tenant` |
| `patient_id` | `uuid` | no | | composite FK `(tenant_id, patient_id)` to `patient.patient (tenant_id, id)` |
| `version` | `integer` | no | | `>= 1` |
| `stage_group` | `text` | yes | | one of `0 I IA IB IC II IIA IIB IIC III IIIA IIIB IIIC IV IVA IVB IVC` |
| `tnm_text` | `text` | yes | | 1 to 40 characters after trim |
| `markers_text` | `text` | yes | | 1 to 80 |
| `ki67_pct` | `smallint` | yes | | 0 to 100 |
| `genetics_text` | `text` | yes | | 1 to 60 |
| `intent` | `text` | yes | | `CURATIVE`, `PALLIATIVE` |
| `setting` | `text` | yes | | `NEOADJUVANT`, `ADJUVANT`, `DEFINITIVE`, `ADVANCED` |
| `line_no` | `smallint` | yes | | 1 to 10 |
| `board_date` | `date` | yes | | none in the DB (not more than 365 days ahead is an application rule: it depends on today) |
| `source_record_id` | `uuid` | yes | | opaque (`opd.emr_record` is partitioned: no FK) |
| `recorded_by_membership_id` | `uuid` | no | | opaque |
| `recorded_by_user_id` | `uuid` | no | | opaque |
| `recorded_at` | `timestamptz` | no | `clock_timestamp()` | database time, not insertable |

This table has no `created_at/by`, `updated_*` or `version bigint` of the generic template: it is append-only (the `recorded_*` columns are the creation fields, the integer `version` is the journey version), like `opd.emr_record_addendum`. An empty value is stored as null (the checks refuse `''`).
Keys and indexes: `uq_emr_cancer_journey_tenant_id (tenant_id, id)`; `uq_emr_cancer_journey_tenant_patient_version (tenant_id, patient_id, version)` serves a version's uniqueness (a racing save fails with 23505: map to 409 `journey-changed`), the current journey (`where tenant_id = ? and patient_id = ? order by version desc limit 1` is a backward index scan) and the history. The ADR lists a second index `(tenant_id, patient_id, version desc)`; it would duplicate this one byte for byte, so it is not created.
RLS: ENABLE + FORCE; policies `p_emr_cancer_journey_select` (select) and `p_emr_cancer_journey_insert` (insert, `with check`), both `to sahaj_app` on `tenant_id = platform.current_tenant()`; no update or delete policy. Append-only three ways: grants (`select` plus column-level `insert (patient_id, version, stage_group, tnm_text, markers_text, ki67_pct, genetics_text, intent, setting, line_no, board_date, source_record_id, recorded_by_membership_id, recorded_by_user_id)`), no policy for the other commands, and triggers `trg_emr_cancer_journey_no_update_delete` and `trg_emr_cancer_journey_no_truncate` (`platform.deny_audit_mutation`, every role including the owner).
Volume: a few rows per oncology patient (one per edit of the stage, intent, line or board date): about 1 to 5 per patient per year; a 100-patient oncology clinic: hundreds of rows a year. No partitioning. Retention: life of the clinic, never deleted. Table comment: "Clinical data, never logged".
Backward compatibility (all four files): the previous app version ignores the new columns (all nullable or defaulted; its inserts still pass; its updates never touch them) and the new table; the previous version would read starter v2 without `presentation` or `alert` (tolerant JSON reader) but would show `local_exam` and the Dental cards as plain sections it knows nothing about, so deploy the application together with the migrations. Rollback is a later forward migration, not an edit.

## 3. What the existing integration tests must change (they assert the old numbers)
`EmrGlobalTablesIT` asserts 100 sections, 313 fields, 341 placements, 15 layouts with `layout_version = 1` and `current_layout_version = 1` for 15 starters, and per-starter placement counts (`PLACEMENTS`). After this change: 107 sections, 333 fields, 227 options, 349 placements (gm 20, dental 30, surgery 27, others as before), 30 layout rows (15 of version 1 and 15 of version 2), `current_layout_version = 2` for 15 starters. Any test that reads the required flags of a starter through the "current" layout (the starter-department endpoint) sees the new flags. QA owns these tests (they are in `backend/src/test/java/**/it/`).

## 4. Integration test cases for QA (`sahaj_test`, from scratch and applied on top of an older state)
1. **Static allow-list (ADR-026 D2.1).** Parse `V2026_12_14_1000` and `V2026_12_14_1010` (strip comments, string literals and the dollar-quoted blocks that only `select`/`raise`); every `insert into`, `update`, `delete from` target is in the six-table allow-list (plus `tmp_sah75_order`); `alter table` targets only `opd.emr_section_field` and `opd.emr_section_field_option`. `V2026_12_14_1020` and `V2026_12_14_1030` have no `insert`, `update` or `delete` statement.
2. **Catalogue (ADR-026 D2.2).** Query `pg_constraint` for foreign keys that reference the six global tables: every `confupdtype` and `confdeltype` is `a` (no action) or `r` (restrict), and none is `c`, `n` or `d`; this holds for the FKs from tenant tables as well (today the only tenant-table reference is `opd.emr_record (starter_key, starter_layout_version)` to `opd.emr_starter_layout`, `fk_emr_record_starter_layout`, default NO ACTION: it points at a layout version a record opened with, so the new version 2 rows never touch it and v1 rows are never changed or deleted). Query `pg_trigger` (not internal) on the six global tables: only `trg_emr_starter_layout_immutable` and `trg_emr_starter_layout_no_truncate`, both enabled (`tgenabled = 'O'`), and their function `opd.deny_emr_immutable_change` writes to no table (body contains no `insert`, `update`, `delete`). No rule (`pg_rewrite` non-select rule) on them.
3. **Columns.** `emr_section_field.presentation` is `text not null default 'DEFAULT'` with the check (insert `'X'` as migrator fails `ck_emr_section_field_presentation`); `emr_section_field_option.is_alert` is `boolean not null default false`. All 313 old fields are `DEFAULT` and all 136 old options `false`.
4. **Library.** The seven sections exist (kind SPECIALTY); counts per section as in 1.2; every dental chip field has `presentation = CHIPS`, `options_source = FIELD`; each `dental_medhx` field has exactly one `is_none` option; `soft_tissue.normal` is `is_none`; alert options are exactly: all non-none options of `dental_medhx`, all seven `dental_hx.habits`, `dental_intraoral.soft_tissue` ulcer, white_patch, red_patch, tongue_coating; no field key `specialty`; `sahaj_app` cannot insert or update any of the three tables (42501).
5. **Starter v2 flags (ADR-026 D2 table; criterion 12).** For gm, dental, og, surgery, onco: the required key set and shown flags equal section 1.3 (query `emr_starter_department_section` and `GET /emr/starter-departments/{key}`); every one of the 15 starters has `rxformat` shown and not required; dental `intraoral` and `extraoral` exist with `shown = false`; dental order is vitals, complaints, dental_medhx, dental_hx, dental_pain, dental_extraoral, dental_intraoral, dental_xray, ...; gm Treatment part order is diagnosis, rx, investigations, procedures, advice, followup; surgery `local_exam` is between `sx_findings` and `sx_fitness`; positions of every starter are 1..n without a gap.
6. **Layouts.** 15 rows of version 1 are identical to before (hash them in a test fixture or compare to the seed file's builder); 15 rows of version 2; the `sections` array of each version 2 equals the starter's placements in position order (same count); a field of a Dental card carries `presentation = CHIPS` and its options `alert`; `UPDATE`, `DELETE`, `TRUNCATE` of `emr_starter_layout` as migrator are refused (42501); the triggers are enabled; `current_layout_version = 2`.
7. **Draft-only and published department after the migration (ADR-026 D2 set rule; java + QA).** A department never published reads the appended sections with the starter's own settings and saves; a published department (a version built from layout v1) reads `local_exam` and the six Dental cards appended as hidden, not required, and saves; "Reset to template" returns v2 defaults and passes the set check; an open record keeps its v1 snapshot (flags unchanged).
8. **Existing tenant rows unchanged (criterion 1).** With fixture clinics, a draft, a version, a record, a course and a patient inserted before the new migrations (apply the four files after the fixture), the row counts and checksums of every tenant table are equal before and after; the two course tables compare on the old columns only (see the checksum script).
9. **Sitting checks.** Insert as migrator: `missed_at` without `missed_by_membership_id` fails (`..._missed_pair`) and the reverse; `removed_at` likewise; `done_source = 'X'` fails; `done_source` without `done_at` fails; `done_by_membership_id` without `done_at` fails; `done_source = 'MARK_DONE'` with `done_at` but no `done_by_membership_id` fails; `done_source = 'SIGN'` with `done_at` and no `done_by` passes; `removed_at` with `done_at` fails; `missed_at` with `done_at` or with `removed_at` fails; missed alone passes; clearing missed (rebook) passes. `regimen_key = 'NOPE'` fails the FK; a key from `emr_chemo_protocol` passes. Old-shape insert (no new column) passes with `done_source` null, `estimate_signed` false.
10. **Grants.** As `sahaj_app`: update of `estimate_signed`, `missed_at`, `missed_by_membership_id`, `removed_at`, `removed_by_membership_id`, `done_source`, `done_by_membership_id`, `regimen_key` succeeds (with the tenant set); update of `sitting_no`, `tenant_id`, `course_id`, `kind`/`unit` (course) still fails 42501; no delete on either table.
11. **Journey append-only and RLS.** As `sahaj_app` with tenant A: insert version 1 then 2 for a patient passes; a second insert of version 2 fails 23505; `update`, `delete`, `truncate` fail (42501 from the grant; as migrator, 42501/`deny_audit_mutation` from the trigger); insert with `ki67_pct` 101, `line_no` 0 or 11, `stage_group = 'V'`, `intent = 'X'`, `setting = 'X'`, `tnm_text` of 41 characters or `''` fail their checks; insert with a patient of another clinic fails the composite FK; tenant B sees none of A's rows; no tenant set sees zero rows; insert with `tenant_id` of another clinic fails the policy; `id`, `tenant_id` and `recorded_at` cannot be inserted by `sahaj_app` (column grant, 42501); `FORCE ROW LEVEL SECURITY` is on (`pg_class.relforcerowsecurity`); the table comment contains "never logged".
12. **Comments.** The two new library columns and the new table have a comment containing "SAH-75" or "never logged".

## 5. Before/after proof on `sahaj_dev` (ADR-026 D2 proof 3)
`docs/database/SAH-75-seed-checksum.sql`: read-only. Run it as the PostgreSQL superuser (so row level security does not hide rows) with the application stopped, before and after the migration, and compare the two outputs; the "tenant tables" part must be identical. Agents do not run it (they do not touch `sahaj_dev`).
```
psql -h localhost -U postgres -d sahaj_dev -A -F '|' -f docs/database/SAH-75-seed-checksum.sql -o before.txt
(start the app once so Flyway runs, then stop it)
psql -h localhost -U postgres -d sahaj_dev -A -F '|' -f docs/database/SAH-75-seed-checksum.sql -o after.txt
diff before.txt after.txt     # part 1 must show no difference; part 2 (global tables) is expected to differ
```
Part 3 (after only) counts the new columns that must hold their defaults for every old row.

## 6. Not verified by execution
The migrations were written and reviewed but not applied by me: the full `validate db` gate was not run (instruction: it takes 70 minutes and the locks), and a private PostgreSQL instance was not permitted. Flyway applies them on the next `mvn verify` or app start; each file ends in a `do $$` block that raises on a wrong count, a wrong flag or a disabled trigger, so a mistake fails loudly and rolls that file back. `mvn -o test-compile` is the only build run (it does not apply migrations). The same holds for the three follow-up files of section 7 (`V2026_12_14_1040` to `V2026_12_14_1060`): they are not applied yet, so `sahaj_test` stays at `V2026_12_14_1030` until the next Flyway run.

## 7. 0.7.1 follow-up (ADR-026 D13, D14; "Data needed" items 9 to 14; DEC-053 addendum)
Three new forward files after `V2026_12_14_1030`. Global seed of schema `opd` only: no clinic, department draft or version, EMR record, form, course, patient or billing row is read or written; no tenant table is altered. `V2026_12_14_1000` to `V2026_12_14_1030` are not edited. The tag on every value: "value from the EMR mockup, not clinically reviewed (SAH-75, DEC-052)" (the files add DEC-053).

### 7.1 `V2026_12_14_1040` · option hint (item 9)
| Table | Column | Type | Null | Default | Check |
| --- | --- | --- | --- | --- | --- |
| `emr_section_field_option` | `hint` | `text` | yes | none | `ck_emr_section_field_option_hint`: `hint is null or char_length(hint) between 1 and 60` |

Every existing option keeps null (227 rows). No new grant (table-level SELECT only for `sahaj_app`). Column comment holds the tag. Block at the end: column is nullable text without default, the check exists, no option has a hint, option count still 227, both layout triggers enabled.

### 7.2 `V2026_12_14_1050` · Gynae library data (items 10, 11, 14)
Field positions are unique per section and not deferrable. The updates run from the last position backwards, so no statement meets an occupied position and **no temporary position is needed**.

`menstrual` after the migration (final positions 1 to 6; every field except `lmp` is not required):
| Pos | Key | Label | Type | Presentation | Options (key "label") |
| --- | --- | --- | --- | --- | --- |
| 1 | `lmp` | LMP | DATE (unchanged, required) | DEFAULT | none |
| 2 | `cycle` | Cycle | SINGLE_CHOICE (was PATTERN), meta '' | CHIPS | `regular` "Regular", `irregular` "Irregular" |
| 3 | `cycle_length` (new) | Cycle length | SINGLE_CHOICE | CHIPS | `under_21_d` "< 21 d", `o21_27_d` "21–27 d", `o28_30_d` "28–30 d", `o31_35_d` "31–35 d", `over_35_d` "> 35 d" |
| 4 | `flow` | Flow | SINGLE_CHOICE (was DROPDOWN), meta '' | CHIPS | `scanty`, `normal`, `heavy` (kept, 1 to 3), `with_clots` "With clots" (4) |
| 5 | `dysmenorrhoea` | **Pain** (was Dysmenorrhoea) | SINGLE_CHOICE (was TOGGLE), meta '' | CHIPS | `none` "None", `mild` "Mild", `severe` "Severe" |
| 6 | `age_at_menarche` | Age at menarche | NUMBER (unchanged) | DEFAULT | none |

`cycle`, `cycle_length`, `flow` and `dysmenorrhoea` have `options_source = FIELD`, `options_master` null, `allow_free_text = false`. No option is `is_none` or `is_alert`. Library section description unchanged.

`contraception` after: `method` (new, 1, "Method", SINGLE_CHOICE, CHIPS, `FIELD`, no free text, not required), then `findings` 2, `template` 3, `attachments` 4 (unchanged otherwise). Options of `method` with their hints:
| Pos | Key | Label | `hint` |
| --- | --- | --- | --- |
| 1 | `none` | None | Not using |
| 2 | `condoms` | Condoms | Barrier |
| 3 | `ocp` | OCP | Daily pill |
| 4 | `cu_t` | Cu-T | IUCD, 5–10 years |
| 5 | `injectable` | Injectable | Every 3 months |
| 6 | `sterilisation` | Sterilisation | Permanent |

**Library counts after the migration (item 14):** sections 107 (unchanged); fields 333 + 2 = **335** (`cycle_length`, `method`); options 227 + 17 = **244** (cycle 2, cycle_length 5, flow 1, Pain 3, method 6). Fields with `presentation = CHIPS`: 24 (19 Dental, 4 Menstrual, 1 Contraception). Options with a `hint`: 6 (all in `contraception.method`). Block at the end checks these counts, the exact field order and types, every option list, the `lmp`-only required rule, no `is_none` or `is_alert`, no field key `specialty`, layout rows still 30, both triggers enabled.
Key renames: none. **Keys kept, types changed in place for `cycle` and `dysmenorrhoea`** (and `flow`, same value kind): an older value can be a free text ("Regular, 28 days") or a toggle value, which is why the java-developer rule "copy from last visit leaves out a value that does not fit the new definition" exists (ADR-026 D13). No stored row is rewritten: signed records and drafts are validated against their own snapshot; the starter layout versions 1 and 2 hold the old definitions for ever.

### 7.3 `V2026_12_14_1060` · onco order and starter layout version 3 (items 12, 13)
1. **Onco placements** (`emr_starter_department_section`): `onc_tox` 17, `onc_bsa` 18, `onc_labs` 19, `onc_ecog` 20 (was `onc_ecog` 17, `onc_tox` 18, `onc_bsa` 19, `onc_labs` 20); part ASSESSMENT and every other flag unchanged. Temporary-position pattern of `V2026_12_14_1010`: the four rows first move by +1000, then take the final position (the unique key `uq_emr_starter_department_section_position` is not deferrable). No other onco row moves; 349 placements in total, positions 1..n per starter.
2. **Layout version 3 for og and onco only**: 2 new rows in `emr_starter_layout`, same builder as version 2, with option objects `{key, label, isNone, alert}` plus **`hint` only when the option has one** (the key is absent otherwise, `jsonb_strip_nulls` on the option object; version 1 and 2 documents have no `hint` key at all). `emr_starter_department.current_layout_version` = 3 for og and onco; the other 13 stay at 2. Layout rows: 15 x v1, 15 x v2, 2 x v3 = 32. og v3 differs from v2 in the Menstrual and Contraception field lists (section 7.2); onco v3 differs only in the card order. Placements of og are unchanged.
3. **Immutability triggers:** `trg_emr_starter_layout_immutable` and `trg_emr_starter_layout_no_truncate` refuse UPDATE, DELETE, TRUNCATE. Like `V2026_12_14_1010`, this file only INSERTS new versions, so **no trigger is disabled**; `V2026_12_14_1040` and `V2026_12_14_1050` do not write the layout table at all. Every one of the three files ends with a check that both triggers are enabled (`tgenabled = 'O'`); `V2026_12_14_1060` also checks the version 3 documents (field order, onco card order, six hints in og and none in onco, no other onco section differing from v2, no `hint` in v1 or v2).
4. **Reach (H22, H23):** a TEMPLATE or never-published og or onco department reads version 3 at once. A published department keeps its version's definitions and order until the owner's next publish that changes a setting or reset to template. A record keeps the starter version it opened with.

### 7.4 Rows, retention, compatibility
Global reference data: tens of rows added, no growth per tenant, no tenant row, no partitioning. Never deleted (a placement or field is never removed). Backward compatibility: the previous app version ignores the new `hint` column; reading a version 3 layout it would meet an unknown option key `hint` (tolerant JSON reader as for `presentation` and `alert`) and would show Pain and Cycle as plain choice fields, so deploy the application together with the migrations. Rollback is a later forward migration.

### 7.5 Global tables allow-list (ADR-026 D2 proof 1), extended
The static allow-list test covers the follow-up files too: every `insert into`, `update`, `delete from` in `V2026_12_14_1040`, `V2026_12_14_1050` and `V2026_12_14_1060` targets only the six global tables (`opd.emr_section`, `opd.emr_section_field`, `opd.emr_section_field_option`, `opd.emr_starter_department`, `opd.emr_starter_department_section`, `opd.emr_starter_layout`); in fact `V2026_12_14_1040` has no DML, `V2026_12_14_1050` writes `emr_section_field` and `emr_section_field_option`, and `V2026_12_14_1060` writes `emr_starter_department_section` (update), `emr_starter_layout` (insert) and `emr_starter_department` (update). `alter table` appears only in `V2026_12_14_1040` and names `opd.emr_section_field_option`. No temporary table is used in these three files (`V2026_12_14_1060` updates from a `values` list). The `do $$` blocks only select and raise.

### 7.6 Changes to existing integration tests (QA)
`EmrGlobalTablesIT` totals after the follow-up: 107 sections, **335** fields, **244** options, 349 placements, **32** layout rows (15 v1, 15 v2, 2 v3), `current_layout_version` 3 for og and onco and 2 for the other 13. Any test that reads og or onco through the starter-department endpoint sees version 3 (onco card order, Gynae fields and hints).

### 7.7 Integration test cases for QA (add to section 4)
13. **Hint check.** As migrator: insert an option with `hint = ''` fails `ck_emr_section_field_option_hint`; a hint of 61 characters fails; 60 characters and 1 character pass (roll back); null passes. All 227 older options have null `hint`; exactly 6 options have a hint, all `contraception.method` (`none` "Not using", `condoms` "Barrier", `ocp` "Daily pill", `cu_t` "IUCD, 5–10 years", `injectable` "Every 3 months", `sterilisation` "Permanent"). `sahaj_app` cannot insert or update the table (42501).
14. **Gynae library.** `menstrual` fields in order `lmp, cycle, cycle_length, flow, dysmenorrhoea, age_at_menarche` with types and presentation as 7.2; `dysmenorrhoea` label is "Pain"; `flow` has four options, `with_clots` at position 4; `cycle_length` has five options with the exact keys; `contraception` fields `method, findings, template, attachments`; no option of these two sections is `is_none` or `is_alert`; library totals 107 / 335 / 244.
15. **Starter versions.** `current_layout_version` is 3 for og and onco and 2 for the other 13; `emr_starter_layout` holds 15 v1, 15 v2, 2 v3 rows; v1 and v2 rows are byte for byte as before (hash them; the checksum script part 2 shows v1) and contain no `"hint"`; og v3 lists the Menstrual fields above and `method` with six options carrying `hint`; onco v3 lists `onc_tox` 17, `onc_bsa` 18, `onc_labs` 19, `onc_ecog` 20 (part ASSESSMENT) and every other onco section equal to v2; onco v3 has no option hint; update, delete and truncate of `emr_starter_layout` are still refused and both triggers enabled.
16. **Copy-forward (java-developer rule, ADR-026 D13; java + QA).** A signed og record with free text in `menstrual.cycle` ("Regular, 28 days", opened on version 2): the next visit's copy leaves that value out (and a stored `flow` choice key is kept only if it is an option of the new definition), a section where nothing fits is not copied, and the draft save passes (no 400).
17. **Old and new records side by side.** A record opened before the migration (snapshot from v2) still reads and saves `menstrual.cycle` as text and `dysmenorrhoea` as a toggle; a record opened after (v3) saves the choice keys.
