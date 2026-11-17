# DEC-054 backend gates, 15 Nov 2026 (qa-engineer)

Status: DEC-054 items PASS. Wider EMR IT set has 40 failures that are SAH-75 0.7.1 seed/layout drift (not DEC-054).
Validate backend fails only on IpdScopeCoverageTest (SAH-76 contract drift, expected).

| Item | Test | Result |
| --- | --- | --- |
| 1. Whole-project unit tests | `mvn -o -f backend/pom.xml test` | 5,077 run, 1 failure (IpdScopeCoverageTest, SAH-76 drift, expected); DEC-054 unit tests PASS |
| 2a. Database timeouts | `DatabaseTimeoutsIT` (2) | PASS |
| 2b. Earlier-day billing | `BillingCounterEarlierDaysIT` (6) | PASS |
| 2c. Dental send to billing | `EmrDentalSendToBillingIT` (14) | PASS (after test fix 1) |
| 2d. Draft save hang | `EmrDraftSaveHangIT` (7), all 200 (after test fix 2) | PASS |
| 3. Wider IT set | `*Emr*IT,*Billing*IT,*Counter*IT` (59 classes) | 799 run, 39 failures, 1 error (see below) |
| 4. `node scripts/validate.mjs backend` | | FAIL, only IpdScopeCoverageTest (5,077 run, 1 failure); ITs not reached |
| Frontend gates | | Skipped (no frontend change) |

EmrDraftSaveHangIT elapsed (ms, Save draft answered 200): exam+followup 34, followup 45, procedures+followup 32, exam 21,
exam+procedures+followup 27, procedures 31, exam+procedures 31. No timeout in any variant.

## Test-side fixes (my ITs)
1. `EmrDraftSaveHangIT.world()`: POST "Dressing" got 409 duplicate-service because the specialty seed already creates it per clinic.
   Now uses `serviceId(clinic, "Dressing", 150)` (unique name).
2. `EmrDentalSendToBillingIT` next-sitting test: `opd.treatment_course_sitting` has no `status` column (computed on read).
   Query now derives PLANNED from done_at, opd_visit_id, ipd_admission_id all null.

## Step 3 failures (not DEC-054; none touch billing, draft save timeout or the send-to-billing flow)
Classes: EmrDepartmentsIT 9, EmrGlobalTablesIT 8+1 error, EmrLibraryIT 5, EmrRecordsIT 9, EmrSignProfilesIT 3, EmrPhase3TablesIT 1,
EmrPhase4TablesIT 1, EmrSpecialtyCoursesIT 1, EmrSpecialtyReferenceIT 1, EmrSpecialtyScreensIT 1. Causes seen:
- Seed totals moved by SAH-75 0.7.1 migrations: 107 sections (test expects 100), gm shown count 20 (expects 19), 313 fields.
- `estimate_signed` column granted on the record table (EmrPhase3TablesIT expects four columns).
- Layout/field shape changes: draft save 400 "field not in the layout" (EmrSignProfilesIT dental), "diet: choose an option" (EmrRecordsIT).
- Dental estimate note "estimate signed by patient" (EmrSpecialtyCoursesIT:61) and dental profile list (EmrSpecialtyReferenceIT:61): not
  inspected further; same SAH-75 area.
The SAH-75 handoff already lists these IT updates for QA (EmrGlobalTablesIT totals, EmrRecordsIT, cases 13-17). Not changed here.

## Earlier notes
IpdScopeCoverageTest: ipd.yaml 0.4.0 lists 18 operations with no handler yet (SAH-76). Owner: java-developer or solution-architect.
BillingCounterEarlierDaysIT: the 200-row cap stays covered by `VisitBillingAdapterTest`.

## SAH-75 IT re-baseline (qa-engineer, 15 Nov 2026)
Scope: the 40 failures of step 3 above, in 10 Emr* IT classes. Expected values come from `docs/database/SAH-75.md` (1.2, 1.3, 7.2, 7.6),
`docs/stories/SAH-75.md` and the V2026_12_14_1000..1060 headers, then were compared with what the code returned. Result of the 10 classes:
200 run, 1 failure (item B below, a product question, test left as written). 39 stale expectations updated in the ITs.

### A. Stale expectations updated (no product change)
| Class (failures) | What changed in the test | Why (source) |
| --- | --- | --- |
| EmrDepartmentsIT (9) | gm total 19 to 20, shown 16 to 18, required stays 4; every dependent count (hide chronic 15 to 17, reset 14/16 to 16/18, version 15 to 17); `procedures` is now shown | `local_exam` added, `procedures` shown (SAH-75.md 1.3, V1010 header); required is now complaints, vitals, diagnosis, rx |
| EmrGlobalTablesIT (8+1) | 107 sections (85 SPECIALTY), 335 fields, 244 options, 349 placements (gm 20, dental 30, surgery 27), 32 layout rows (15 v1, 15 v2, 2 v3), current version 3 for og/onco and 2 for the other 13; 60 fields with options; required-at-start now 17 keys; print adds `local_exam`; off at start gm {referral, consent}, dental {exam, hpi, referral, intraoral, extraoral}, 31 hidden in all; None options add the four dental chip fields; default-order test no longer asserts the first 14 for gm/dental/surgery (they are renumbered, T2); layout-document test compares each starter with its current layout version; Allergies lock 32 rows, Form F lock 3 rows; the "new version" probe now adds version 3 for gm (v2 exists) | SAH-75.md 1.2, 1.3, 7.2, 7.6, 7.7 cases 13-15 |
| EmrLibraryIT (5) | 107 / 85 / 335, gm layoutVersion 2, 20 sections, 18 shown, `local_exam` after `exam`, procedures shown, starter section counts and total 349, wizard counts gm 3 and dental 13 (derived from the placements; the test also checks them against the table), dental_chart no longer required, dental_medhx and tx_plan required | SAH-75.md 1.3 |
| EmrRecordsIT (8 of 9) | starter layout version 2, gm 18 sections (parts 10 and 8), required total 2 + 2, still needed starts with vitals, rxformat no longer required, `vitalsLog()` helper because Vitals is now required and is filled with the vitals log editor, dental layout holds `dental_intraoral` and no `intraoral`, procedures is in the gm layout (removed from the "not in layout" 400 list) | SAH-75.md 1.3 |
| EmrSignProfilesIT (3) | `dentalRec` saved the General Medicine `fillRequired`, which includes a VITALS_LOG value; Dental has no vitals editor (SpecialtyProfiles: only gm and onco), so `vitals.values.specialty` is "not in the layout". The helper (my own change of last night, vitals added for gm) was wrong for Dental. Dental now saves complaints, diagnosis, rx, rxformat | Test helper bug, not a product defect: Dental's required sections (complaints, dental_medhx, diagnosis, tx_plan; relax keeps the four the test fills) are all saveable |
| EmrPhase3TablesIT (1) | `estimate_signed` in the course grant; sitting grant adds the seven SAH-75 columns | SAH-75.md 2.1, 2.2 (grants), case 10 |
| EmrPhase4TablesIT (1) | 335 seeded fields | SAH-75.md 7.2 |
| EmrSpecialtyCoursesIT (1) | course note is null and `estimateSigned` is true | opd.yaml 0.7.0 TreatmentCourse.estimateSigned |
| EmrSpecialtyReferenceIT (1) | dental lists add dentalMedConditions, dentalMedMedicines, dentalMedAllergies (47 names, was 44) | opd.yaml 0.7.0 enum, SAH-75-6 |
| EmrSpecialtyScreensIT (1) | og ANC mode also hides `menstrual` (two assertions) | ADR-026 D5 / story BR75-9 |

### B. Open: product question (test not bent)
`EmrRecordsIT.saveDraftStoresTheValuesThePositionAndANewRevisionWithoutCheckingTheRequiredSections` (line 497).
Request: `PUT /api/v1/opd/emr/records/{id}/draft` with `sections:[{key:"history", values:{"past_illness":[],"diet":""}}]`, expecting 200 and the
section cleared ("an empty value clears the field").
Response: 400 `validation`, `fieldErrors:[{field:"sections[0].values.diet", message:"Choose an option from the list."}]`.
Not caused by SAH-75: `EmrValueRules.checkOne` (unchanged since it was added) rejects "" for a choice field with options, while `isEmpty`
(ADR-020 D8) and the Angular `compact()` treat blank text as "nothing entered". The real client never sends "" (it drops empty keys), so no screen is
affected today. Owner: java-developer to decide: skip blank strings in `EmrRecordService.cleanValues` before `EmrValueRules.check`, or the architect states
that only null/absent clears (then QA changes the test to send null). The remaining assertions of this method are not reached until it is decided.

### C. For the human (not test defects)
- Wizard card counts (`specialtySectionCount`) are derived: gm now 3, dental 13 (the six new cards plus the two hidden old ones). The test used to pin the
  numbers "as drawn" (2 and 7). If the mockup wizard card still says 7 for Dental, the count should exclude hidden starter sections: owner angular/java after a decision.

### Totals after the re-baseline
| Run | Result |
| --- | --- |
| The 10 classes (`-Dit.test=...`) | 200 run, 1 failure (item B) after the second run; first run 200 run, 40 failures/errors |
| `*Emr*IT,*Billing*IT,*Counter*IT` (59 classes) | 799 run, 1 failure (item B), 0 errors, 0 skipped; before: 39 failures, 1 error |
| `validate backend` | not rerun (it still stops on IpdScopeCoverageTest, SAH-76); lock `locks2/backend` released, refresher stopped |

Coverage (SAH-75 database cases 1-17 of `docs/database/SAH-75.md` that these ITs cover): 5 starter flags and v2 layout (EmrGlobalTablesIT, EmrLibraryIT, EmrDepartmentsIT) PASS;
13/14/15 library, hint and starter versions as counts (EmrGlobalTablesIT, EmrPhase4TablesIT) PASS; 10 grants (EmrPhase3TablesIT) PASS; 6 layout immutability (EmrGlobalTablesIT) PASS.
Not yet written as ITs (still open for QA): cases 1-4, 7-9, 11-13 and 16-17 in full (static allow-list, byte-for-byte v1/v2 hashes, journey append-only, copy-forward).

## SAH-75 QA follow-up (qa-engineer, 15 Nov 2026)
- Item B closed: `EmrRecordsIT` 28 of 28 PASS after Java dropped blank draft values; the unreached audit assertion was `hasSize(5)` for four saves, now 4.
- New SAH-75 database ITs (`Sah75GlobalSeedIT` 16, `Sah75TenantTablesIT` 5, `Sah75StarterVersionsIT` 5) cover cases 1-4, 6, 7, 9, 11-17; case 8 is listed for the H10 checksum script (needs a before-migration fixture). Details and coverage table: `docs/test-plans/SAH-75.md`.
- Wider set `*Emr*IT,*Billing*IT,*Counter*IT,Sah75*IT`: 825 run, 1 failure (EmrChemoPinIT PIN digits found by chance inside a hash, test fixed, rerun PASS), 0 errors.
- Lock `locks2/backend` released, refresher stopped.

## Combined gate after the DEC-054 and SAH-75 review fixes (qa-engineer, 15 Nov 2026)
Lock: the `locks2/backend` folder was not created. The Bash tool refused to write inside `.claude/` (auto-mode classifier), and no other
`locks2` location is documented. Only the dev API (`spring-boot:run`, port 8080) was running; no other test run was seen. No refresher was started.

### Run totals
| Run | Result |
| --- | --- |
| `mvn -o test` (whole unit suite, ArchUnit and Modulith included) | 5,087 run, 2 failures, 0 errors, 0 skipped: `IpdScopeCoverageTest` (SAH-76 drift, expected) and `AdmittedCardsTest.noneKnownAndNotRecordedSendNoText` (new, see D1) |
| New ITs and unit test (`Sah75CourseActionFixesIT` 5, `Sah75CancerJourneyFixesIT` 3, `Sah75ChemoSendRevisionIT` 2, `Sah75AllergyChipIT` 2, `HasBarePinTest` 2) | 14 run, 0 failures |
| `mvn -o verify` whole IT suite, 55-minute cap | cut by the cap: 147 classes (A to P), 2,061 tests, 0 failures, 0 errors, 25 skipped; Q to W classes not reached |
| Remaining ITs `Q*IT,R*IT,S*IT,T*IT,V*IT,W*IT` | 782 run, 2 failures (D2 ScopeRaceIT, D3 VitalsCatalogIT) |
| `VitalsCatalogIT` after the test fix (D3) | 6 of 6 PASS |
| Whole IT suite, all classes | 2,843 run, 1 failure (D2, now fixed). The three empty files were deleted (see below) |
| `ScopeRaceIT` after the D2 fix (`-Dit.test=ScopeRaceIT`, sahaj_test) | 6 of 6 PASS (44 s) |

### Coverage
| Fix under test | Test | Result |
| --- | --- | --- |
| Rebook beyond today + 2 years is 400 on `plannedDate`; exactly +2 years accepted | `Sah75CourseActionFixesIT.rebookAcceptsExactlyTwoYearsAheadAndRefusesOneDayMoreWith400OnPlannedDate` | PASS |
| Reschedule the same limits, past date 400, nothing changed on refusal | `...rescheduleAcceptsExactlyTwoYearsAheadAndRefusesOneDayMoreOrAPastDateWith400OnPlannedDate` | PASS |
| Missing or null date is 400 on `plannedDate` | `...aMissingOrNullDateIsAlso400OnPlannedDate` | PASS |
| Mark done with null, empty or missing body is 400 problem details, nothing written | `...markDoneWithANullOrEmptyBodyIs400WithAProblemDetailsBodyNot500` | PASS |
| Tenant isolation of the three actions (404) | `...anotherClinicCannotRebookRescheduleOrMarkDoneThisSitting` | PASS |
| Cancer journey NUL, tab, line break, 0x1f in `tnmText` is 400 with a field error, nothing stored | `Sah75CancerJourneyFixesIT.aNulOrTabInTnmTextIs400WithFieldErrorsNotAnInternalError` | PASS |
| No-op save returns the latest version, no new row, no new audit; a real change is version 2 | `...aSaveThatChangesNothingReturnsTheLatestVersionAndWritesNoRowAndNoAudit` | PASS |
| Stale version 409 `journey-changed`; other clinic 404 | `...aStaleVersionIs409JourneyChangedAndAnotherClinicsDoctorGets404` | PASS |
| Chip: "Peanuts; none known otherwise" and a second mixed text are RECORDED | `Sah75AllergyChipIT.aTextThatOnlyContainsNoneKnownIsRecordedNotNone` | PASS |
| Chip: "No known allergy", "none known", "NKDA", "Nil", "No known drug allergies." are NONE_KNOWN | `...theWholeTextBeingANoneWordIsNoneKnown` | PASS |
| Chemo send bumps the course revision once, sets the regimen, retry bumps nothing | `Sah75ChemoSendRevisionIT.sendingBumpsTheCourseRevisionExactlyOnceSetsTheRegimenAndARetryBumpsNothing` | PASS |
| A cycle with a regimen is left alone, revision unchanged | `...aCycleThatAlreadyHasARegimenIsLeftAloneAndTheRevisionDoesNotMove` | PASS |
| `hasBarePin` finds a standalone PIN and one beside spaces or punctuation; ignores digits in a longer run, hash, uuid, time (both copies agree) | `HasBarePinTest` (2 tests) | PASS |

Limit of `hasBarePin` (not a failure): a PIN glued to letters, such as `pin4827`, is treated as part of a hash and is not flagged. The leak checks
that use it can therefore miss a PIN written like that. Tighten it only if the team wants that case covered.

### Defects and open items
- D1 (owner java-developer): `AdmittedCardsTest.noneKnownAndNotRecordedSendNoText` (backend/src/test/java/com/sahaj/ipd/application) still lists
  "no known allergies - none known" as not RECORDED. After the single none-word list fix, the text is no longer the whole none word, so the chip is
  RECORDED (the new `Sah75AllergyChipIT` agrees with the code). Either the unit expectation is stale (remove that text from the list) or the product
  wants such a text to be none: a decision for the tech lead. Not changed by QA (not in the `it/` folder).
- D2 (owner qa-engineer, premise outdated by the 10 s `lock_timeout` in `application.yml`; product behaviour is as designed):
  `ScopeRaceIT.aGrantThatEndsWhileTheDoseWaitsForTheAdmissionRowIsRefusedWhenTheRowIsFreeAndNothingIsWritten` fails every time (two runs).
  The test holds the admission row lock while a grant that ends in `GRANT_ENDS_IN_SECONDS = 30` expires, so the waiting dose is cut by
  `lock_timeout` after 10 s: `POST /api/v1/ipd/admissions/{id}/medicine/orders/{orderId}/doses` answers 503 `database-busy`, expected 403 `outside-scope`.
  FIXED (qa-engineer, tech lead go-ahead): `GRANT_ENDS_IN_SECONDS` is now 5, so the dose waits about 5.3 s, under the 10 s `lock_timeout`.
  Assertions unchanged (403 `outside-scope`, fixed detail, no dose row, no event, nothing written). `application.yml` and test profile untouched.
- D3 (fixed, qa-engineer): `VitalsCatalogIT.theEmrLayoutImmutabilityTriggersAreThereByNameAndEnabled` listed the allowed `trg_emr_` triggers; SAH-75 added
  `trg_emr_cancer_journey_no_update_delete` and `trg_emr_cancer_journey_no_truncate` (V2026_12_14_1030). Both names added to the allow-list.
- Three zero-byte files `EmrOverridesIT.java`, `EmrSettingsIT.java`, `EmrTemplatesReadIT.java`: DELETED (qa-engineer). Commit f26dc01 had real tests
  (519, 263, 348 lines) for the template, settings and override endpoints; commit 6763811 emptied them when those endpoints and their repositories
  were removed (only `EmrReadOnlyPathsController` remains, for masters, quick-options, library and similar, covered by `EmrMastersIT`,
  `EmrQuickOptionsIT`, `EmrLibraryIT`). No endpoint is left to test, so no replacement tests were written.

### `node scripts/validate.mjs backend`
FAIL as expected before the ITs: 5,090 unit tests run (includes `HasBarePinTest`), 2 failures: `IpdScopeCoverageTest` (SAH-76) and `AdmittedCardsTest` (D1).
The gate runs `mvn verify`, so the failing unit phase stops it before the ITs; the ITs were run separately above.

## Re-run after D1 (AdmittedCardsTest) and D2 (ScopeRaceIT) fixes, 2026-11-15

- `mvn -o -f backend/pom.xml test` (unit suite): 5090 run, 1 failure, 0 errors, 0 skipped.
  - Only failure: `IpdScopeCoverageTest.theContractAndTheCodeListTheSameOperations` (SAH-76 contract drift, operations without handlers). Expected.
- `node scripts/validate.mjs backend`: exit 1 (`mvn verify` failed at the unit stage on the same single test; the IT stage was not reached). Not a pass.
- No other failures. Whole IT suite was not re-run, as instructed.
