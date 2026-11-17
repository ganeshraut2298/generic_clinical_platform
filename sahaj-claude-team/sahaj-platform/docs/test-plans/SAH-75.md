# SAH-75 · EMR design alignment · test plan (database cases, QA part)

Owner: qa-engineer. Source: `docs/database/SAH-75.md` section 4 (cases 1 to 12) and 7.7 (cases 13 to 17), ADR-026, `docs/api/opd.yaml` 0.7.0 and 0.7.1.
Scope of this file: the **database and seed cases** the db-engineer listed for QA. The API and screen cases of the eleven sub-stories are covered by the
existing Emr* ITs re-baselined on 15 Nov 2026 (see `DEC-054-backend-gates-2026-11-15.md`, "SAH-75 IT re-baseline") and by the angular unit tests.
Database: `sahaj_test`, profile `test`. Writes that must be refused or accepted run in a transaction that is rolled back; anything stateful uses fresh clinics
made by the existing helpers. Seed values are read only. Throwaway data only.

## New integration test classes (backend/src/test/java/com/sahaj/platform/it/)
| Class | Tests | What |
| --- | --- | --- |
| `Sah75GlobalSeedIT` | 16 | cases 1, 2, 3, 4, 6, 12, 13, 14, 15 (static migration parse, catalogue, columns, library, hint, layout documents, comments) |
| `Sah75TenantTablesIT` | 5 | cases 9 and 11 (sitting checks, cancer journey append-only, row level security, tenant isolation) |
| `Sah75StarterVersionsIT` | 5 | cases 7, 16, 17 (older published department, copy-forward of Gynae values, old and new records side by side) |
| `Sah75LayoutHashes` | fixture | md5 of the 30 stored layout documents of version 1 and 2 |

## Coverage table (database cases)
| Case | Criterion (short) | Test | Result |
| --- | --- | --- | --- |
| 1 | Every INSERT/UPDATE/DELETE/TRUNCATE in the global seed migrations targets only the six global tables (+ `tmp_sah75_order`); `alter table` only field and option table; 1020, 1030, 1040 have no write; dollar blocks only select and raise | `Sah75GlobalSeedIT` `everyWriteOfTheSixGlobalSeedMigrations...`, `theAlterTableStatements...`, `theTwoTenantMigrationsAndTheOptionHintMigration...`, `theSevenMigrationsWriteNoTenantTable...` | PASS |
| 2 | Every FK to the six tables is NO ACTION or RESTRICT; the only tenant-table reference is `fk_emr_record_starter_layout`; only the two enabled layout triggers; no rule; trigger function writes no table | `everyForeignKeyToTheSixGlobalTables...`, `theSixGlobalTablesHaveOnlyTheTwoEnabledLayoutImmutabilityTriggers...` | PASS |
| 3 | `presentation` text not null default DEFAULT with check (migrator `'X'` fails `ck_emr_section_field_presentation`); `is_alert` boolean not null false; older rows keep defaults | `presentationAndIsAlertAreNotNull...` | PASS |
| 4 | Seven sections (SPECIALTY, 101 to 107), field and option counts per 1.2, chips fields, one none option per medical-history field, alert list exact, totals 107/335/244, no `specialty` key, app cannot write | `theSevenNewLibrarySections...`, `everyDentalChipFieldIsAChipsField...`, `theAppCannotInsertUpdateOrDeleteTheThreeLibraryTables` | PASS |
| 5 | Starter v2 flags | already covered (EmrGlobalTablesIT, EmrLibraryIT, EmrDepartmentsIT, re-baselined 15 Nov) | PASS |
| 6 | v1 rows unchanged (hash), 15 v2 rows, sections equal placements in position order, current version, no new keys in v1 | `thirtyTwoLayoutRowsHold...`, `theStarterLayoutDocumentsOfVersionOneAndTwoAreByteForByte...` (hashes pinned from the migrated state) | PASS |
| 6 | UPDATE, DELETE, TRUNCATE of the layout table refused; triggers enabled | EmrGlobalTablesIT (re-baselined) and case 2 test | PASS |
| 7 | Never-published gm reads `local_exam` with starter settings and saves; department published from v1 reads `local_exam` / six Dental cards appended hidden and optional, saves; reset to template returns v2 defaults and saves; doctors see only the published version | `Sah75StarterVersionsIT` two department tests | PASS |
| 7 | An open record keeps its v1 snapshot (flags unchanged) | partly: case 17 test (record on the v2 snapshot keeps its old definitions); v1 record not reproducible after the migration | PASS (partial) |
| 8 | Existing tenant rows unchanged by the four tenant/global files | **Not written as an IT**: needs a fixture applied before the migrations (not possible on a migrated `sahaj_test`). Covered by case 1 (no write to any tenant table in the migrations) and the read-only checksum script `docs/database/SAH-75-seed-checksum.sql` (H10, human on `sahaj_dev`) | LISTED (H10) |
| 9 | Sitting checks (missed pair, removed pair, done_source, done_by, MARK_DONE needs person, SIGN no person, removed vs done, missed vs done/removed, rebook, regimen FK, old shape) | `Sah75TenantTablesIT.theSittingStateChecks...` | PASS |
| 9 | Another clinic cannot see or add to a clinic's sittings | `Sah75TenantTablesIT.anotherClinicCannotSeeTheSittings...` | PASS |
| 10 | Grants of the new columns | EmrPhase3TablesIT (re-baselined) | PASS |
| 11 | Journey: v1 then v2, duplicate 23505, every check refused and accepted at its limit (stage list of 17, Ki-67, line, text lengths, empty), update/delete/truncate refused (app 42501, owner TRUNCATE by trigger), id/tenant_id/recorded_at not insertable, RLS forced, two policies only, two triggers, tenant B sees none, no tenant sees none, patient of another clinic refused | `Sah75TenantTablesIT` three journey tests | PASS |
| 12 | Comments hold SAH-75 or "never logged" | `theNewColumnsAndTablesSah75Has...` (`done_by_membership_id` has a comment without a case number: only checked to be present) | PASS |
| 13 | Hint check (empty, 61 fail; 1, 60, null pass); six hints exactly in `contraception.method`; app cannot write | `theOptionHintCheckRefuses...` | PASS |
| 14 | Gynae menstrual and contraception fields, types, presentation, options, totals | `theGynaeMenstrualAndContraceptionFields...`, totals in case 4 test | PASS |
| 15 | current_layout_version 3 for og/onco, 2 for 13; 15 v1 / 15 v2 / 2 v3; no hint in v1/v2; og v3 six hinted options; onco v3 order 17 to 20, rest equal to v2, no hint | `thirtyTwoLayoutRowsHold...`, `theOncoVersionThreeOnlyReorders...`, hashes | PASS |
| 16 | Next visit's copy leaves out free-text `cycle`, keeps `flow: heavy` and `lmp`, copied section saves (no 400); a section where nothing fits is not copied | `Sah75StarterVersionsIT` two copy tests | PASS |
| 17 | Record on the v2 snapshot saves `cycle` as text and `dysmenorrhoea` as a toggle; a v3 record saves choice keys, refuses text with 400, names version 3 | `Sah75StarterVersionsIT.aRecordOpenedOnTheVersionTwoSnapshot...` | PASS |

## Fixes in my own tests
- `EmrRecordsIT.saveDraftStoresTheValues...` (item B of the re-baseline): after Java dropped blank strings in `EmrRecordService.cleanValues` the save answers 200 and clears the section. The assertion that was never reached before, "one audit entry per save" `hasSize(5)`, was wrong: the method makes four saves (revisions 1 to 4). Now 4. Not a product defect.

## Notes for the owners (not defects)
- Case 7 as written in the design note says a published department "reads local_exam and the six Dental cards appended as hidden": that holds for the configurator view; the doctor's published layout (`GET /emr/departments/{key}/layout`) deliberately shows the published version only (EmrLayouts.libraryBase comment). The test asserts both.
- The older published version in case 7 and case 17 is made by inserting one `opd.emr_department_version` row copied from the stored starter layout (v1 or v2) and moving `published_version`, as `sahaj_app`. No application path can publish an older starter version now.
- `done_by_membership_id` comment has no "SAH-75" or "never logged" (design note case 12 says one of the two). Trivial; db-engineer may add the tag in a later forward migration.

## Run totals (15 Nov 2026)
| Run | Result |
| --- | --- |
| `EmrRecordsIT` (step 1) | 28 run, 0 failures: `saveDraftStoresTheValues...` PASS (Java dropped blank strings; the follow-on audit count was corrected from 5 to 4 saves) |
| `Sah75GlobalSeedIT` + `Sah75TenantTablesIT` + `Sah75StarterVersionsIT` | 26 run (16 + 5 + 5), 0 failures |
| `*Emr*IT,*Billing*IT,*Counter*IT,Sah75*IT` (the 10 Emr* classes included) | 825 run, 1 failure (`EmrChemoPinIT`, my own flaky assertion, fixed, rerun 7 of 7 PASS), 0 errors, 0 skipped. Before: 799 run, 1 failure |
| Whole IT suite (`mvn verify`) | not run (time) |
| `validate backend` | not rerun (still stops on IpdScopeCoverageTest, SAH-76) |

`EmrChemoPinIT.theCheckersRightPinConfirmsTheSecondCheck...` failed once: "never stored in the order" checked that the order row text does not contain the 4-digit PIN
`7391`, and the row holds a fingerprint hash where the digits occur by chance. Not a product defect; the PIN is not stored.
The two assertions now use `hasBarePin` (a PIN standing alone), the helper the other PIN tests use.
