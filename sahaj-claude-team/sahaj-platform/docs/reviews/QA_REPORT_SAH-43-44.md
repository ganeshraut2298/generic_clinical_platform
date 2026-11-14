# QA report · SAH-43 and SAH-44 · Doctor EMR: masters, ranking per doctor, doctor details, specialty templates, clinic overrides, EMR settings, template choice

Date: 12 Nov 2026. Author: qa-engineer. Plan: `docs/test-plans/SAH-43-44.md`. Contract: `docs/api/opd.yaml` 0.2.0. Stored shape: `docs/database/SAH-43-44.md`. Approval: the human's chat approval of stories and contract (DEC-038); no lines in `docs/approvals.md`; no hook blocked this work.
Mockup screen: none (data and backend only). **No Playwright test was written or run: there is no screen to drive.**

## 1. Verdict

**The backend of SAH-43 and SAH-44 passes every integration test written for it: 146 new tests, 0 failed, 0 errors, 0 skipped** (see section 3 for how the counts were taken). Two defects were found by the first runs and are already fixed in the java-developer's code (section 5). Nothing in the application is red. What is **not** covered is in section 7: everything that needs the EMR visit (SAH-46), the sign step (SAH-51) or a human decision (ICD-10 source, clinical review).

Read two things before merge: **(a)** on 12 Nov the business-analyst marked SAH-44-1 to 44-4 as withdrawn and replaced by SAH-53, SAH-45 and SAH-46 (DEC-039: 15 departments, 100 sections, only Allergies locked). The tests below check the approved SAH-44 of 11 Nov and the built data (4 templates, 38 sections, 6 sections that cannot be hidden); several of them will have to change when SAH-53 replaces the template data model (section 8). **(b)** All lists are starter lists, not clinically reviewed (section 9).

## 2. What was written (QA folders only)

| File | What |
| --- | --- |
| `docs/test-plans/SAH-43-44.md` | Plan: every acceptance criterion and every S-case mapped to a test or to "not testable yet" with the reason. |
| `backend/src/test/java/com/sahaj/platform/it/EmrSupport.java` | Shared set-up (staff of every role, owner with and without doctor details, SQL helpers, the usage recorder helper). |
| `EmrMastersIT` (23), `EmrQuickOptionsIT` (21), `EmrDoctorContextIT` (17), `EmrTemplatesReadIT` (11), `EmrOverridesIT` (21), `EmrSettingsIT` (12), `EmrGlobalTablesIT` (24), `EmrClinicTablesIT` (12), `EmrLogPrivacyIT` (3) | The integration tests, same folder. Real application, real `sahaj_test`, `sahaj_app`, MockMvc through the real security chain; the usage counts through `EmrUsageRecorder` inside a transaction. |
| `VitalsCatalogIT` (+2) | The six `trg_emr_*` immutability triggers by name and enabled; FORCE RLS on the four clinic tables. |
| `RowLevelSecurityCatalogIT` | (Earlier, unblock only) the seven global tables in `NO_RLS_BY_DESIGN`. It now passes. |
| `frontend/e2e/` | Nothing: no UI exists. |

Throwaway data only: made-up names, registration numbers and mobile numbers; nothing real was logged or committed. No application code, migration, script, database or row outside the test clinics was touched. No `git stash/checkout/reset/switch/clean`, no `mvn clean`, the human's server on port 8080 was not stopped.

## 3. Gates and counts

New tests: **146** (144 in the nine `Emr*` classes, 2 added to `VitalsCatalogIT`). Locks: the java-developer's `validate backend` held them from 22:58 and again from 07:29 and 08:28; I took both (Maven takes both) only when free, refreshed them while running and released my own owner files (script `scratchpad/qa43-mvn.sh`). No `mvn clean`.

| # | Run | Result |
| --- | --- | --- |
| 1 | My targeted run 1 (08:23 to 08:25), `mvn -f backend/pom.xml verify -Dtest=NoSuchUnitTest -Dit.test=Emr*IT (9 classes),VitalsCatalogIT,RowLevelSecurityCatalogIT,PooledConnectionIT,ClinicMastersIT,TenantIsolationIT` | 215 run, 2 failed: D-1 and D-2 (section 5). All fixture errors of my first tests were already fixed. |
| 2 | java-developer `validate backend`, run 2 (ended 08:22), which compiled my first version of the tests | 2,082 integration tests, 12 red, **all in my new classes** (my fixture errors and D-1, D-2); every older test passed. The earlier run 1 of the java-developer (stale `target`) is explained in the handoff log. |
| 3 | java-developer `validate backend`, run 3 (08:28 to 09:17) after D-1, D-2 were fixed and my tests corrected | **PASSED, exit 0. Integration tests 2,084 run, 0 failed, 0 errors, 26 skipped** (the 26 are older tests that skip by design, as in every earlier gate); unit tests (`mvn test`) 3,878 run, 0 failed, 0 skipped. This is the full-suite result. |
| 4 | My targeted run 2 (09:18 to 09:20), same command as run 1 | **BUILD SUCCESS. 216 run, 0 failed, 0 errors, 0 skipped.** |

Run 4 per class: `EmrMastersIT` 23, `EmrQuickOptionsIT` 21, `EmrDoctorContextIT` 17, `EmrTemplatesReadIT` 11, `EmrOverridesIT` 21, `EmrSettingsIT` 12, `EmrGlobalTablesIT` 24, `EmrClinicTablesIT` 12, `EmrLogPrivacyIT` 3 (= 144, all pass); `VitalsCatalogIT` 6 (4 old + 2 new); regression: `RowLevelSecurityCatalogIT` 12, `PooledConnectionIT` 3, `ClinicMastersIT` 39, `TenantIsolationIT` 12, all pass. Concurrency tests (8 threads on the usage counts, 2 owners on override and on settings, 2 first inserts) passed in runs 3 and 4 and in run 1.
`node scripts/validate.mjs all` was not run a second time: the java-developer's `validate backend` (run 3) is the full backend result, as agreed. `validate frontend` and the e2e folder are not affected (no frontend change). `validate docs` is expected to fail only on the known `ADM-2.md` name.

## 4. Coverage: acceptance criterion → test → result

Test names are in the plan (`docs/test-plans/SAH-43-44.md`, same row numbers). Result key: **PASS** ran and passed; **N/T** not testable yet, reason in section 7.

### SAH-43-1 Search the masters
| # | Criterion | Test (class.method, shortened) | Result |
| --- | --- | --- | --- |
| 1 | Complaints prefix search, any letter case, ordered, empty list when nothing matches | `EmrMastersIT.aSearchMatchesTheStartOf...`, `aWordInTheMiddle...`, `nothingMatchingIsAnEmptyList...` | PASS |
| 2 | Investigations and advice behave the same | the same tests, three masters | PASS |
| 3 | Diagnoses: name or code prefix, carries the code, SAH-7 unchanged | `diagnosesWithoutTheNewParameters...`, `EmrGlobalTablesIT.everyDiagnosisHasOneIcd10Code...` | PASS |
| 4 | Medicines as SAH-7 | `medicinesStayAsInSah7...` | PASS |
| 5 | No text: first items in master order, page limit, next page | `noTextReturnsTheFirstItems...`, `aSpaceOnlyText...`, `aPageOutside...`, `diagnosesPagesWalk...` | PASS |
| 6 | Specialty: tagged first, nothing hidden | `aSpecialtyPutsItsTaggedItemsFirst...`, `aSpecialtyOutside...`, quick-option tail test | PASS |
| 7 | Long or odd text: empty list or 400, never 5xx, never wider | `textOverOneHundred...`, `percentUnderscoreBackslash...`, `aLiteralPercent...`, `aNulCharacter...`, `EmrGlobalTablesIT.thePrefixSearch...` (2) | PASS |
| 8 | Not signed in: 401 | `notSignedInAnswers401OnEveryEmrReadPath` | PASS |
| 9 | Every clinic role can search | `everyClinicRoleSearchesEveryMaster...` (8 roles x 3 masters) | PASS |
| 10 | Create, change, delete: 403 for every role incl. owner, nothing changes | `everyWriteToAMasterOrTemplatePath...`, `aWriteWithoutASession...`, `EmrGlobalTablesIT.theAppCannotInsert...` | PASS |
| 11 | One clinic's favourite does not move the other's order | `medicinesStayAsInSah7...` | PASS |
| 12 | Seed: no patient data, unique names, 1 to 200 characters | `noMasterItemLooksLikePatientData...`, `EmrGlobalTablesIT.aNameRepeated...`, `theNameHintAndOrder...` | PASS |
| 13 | A removed item does not change a signed visit | none | N/T (no visit yet) |

### SAH-43-2 Ranking per doctor
| # | Criterion | Test | Result |
| --- | --- | --- | --- |
| 1-3 | Count, then recency, then never-used in master order | `EmrQuickOptionsIT.usedItemsComeByCountThenRecency...`, `aDoctorWithNoUse...` | PASS |
| 4 | Same rule per master, separately | `everyMasterIsRankedByTheSameRule...`, `usedMedicinesThenTheClinicsFavourites...` | PASS |
| 5 | At most N, upper limit | `anUnknownMasterIsABadRequestAndTheLimit...` (1, 10, 50; 0, 51 refused) | PASS |
| 6 | Doctor X and Y see only their own | `twoDoctorsOfOneClinic...` | PASS |
| 7 | Visiting doctor at two clinics | `aVisitingDoctorOfTwoClinics...` | PASS |
| 8 | Signed visit counts each item once, last used = signature; drafts and addenda do not count | recorder: `usedItemsCome...`, `lastUsedKeepsTheLaterTime...`, `theRecorderNeedsATransaction...` | PASS for the recorder; sign step N/T (SAH-51) |
| 9 | Same item twice counts once | `theSameItemTwiceOnOneVisit...` | PASS |
| 10 | Typed text never counted, stored or offered | `textTheDoctorTyped...` | PASS |
| 11 | Two visits signed at the same moment lose no count | `visitsSignedAtTheSameMomentLoseNoCount` (8 threads) | PASS |
| 12 | No public way to write counts | `EmrMastersIT.thereIsNoPublicWayToWriteUsageCounts`, 403 on writes | PASS |
| 13 | Own ranking only; another doctor's id ignored, owner included | `aDoctorAVisitingDoctorAndAnOwner...`, `twoDoctorsOfOneClinic...` | PASS |
| 14 | Nurse, front desk, billing, pharmacist: 403 | `nurseFrontDeskBillingPharmacist...`, `theRoleInTheTokenIsNotTrusted...`, `anOwnerWithoutDoctorDetails...` | PASS |
| 15 | Clinic B never sees A's counts (RLS) | `anotherClinicNeverSeesTheCounts...`, `EmrClinicTablesIT.oneClinicCannot...` | PASS |
| 16 | Stored data only ids, count, time | `whatIsStoredHoldsNo...`, `EmrClinicTablesIT.noColumnOf...`, `theUsageTableRefuses...` | PASS |
| 17 | No use yet: master order, not empty | `aDoctorWithNoUse...` | PASS |
| + | `ix_emr_item_usage_ranking` is used (finding 6) | `theRankedReadUsesTheRankingIndexAndNeedsNoSort`, `...WithTheMasterJoin...` | PASS (see risk R-3) |

### SAH-43-3 Doctor details through platform.api
| # | Criterion | Test | Result |
| --- | --- | --- | --- |
| 1 | Details exactly as saved; no second copy | `EmrDoctorContextIT.aDoctorGetsTheirOwnDetails...`, `theEmrReadsDoctorDetailsThroughThePort...` | PASS |
| 2 | `opd` uses the port, not the tables | existing `ModularityTests`, `TeamMemberLookupTest` (developer); port IT | PASS (by the developer's tests; no source scan) |
| 3 | Owner without details: "none", cannot write | `anOwnerWithDoctorDetailsGetsThem...`, `EmrQuickOptionsIT.anOwnerWithoutDoctorDetails...` | PASS |
| 4 | Missing degree or registration number listed, nothing blocked | `aMissingDegreeOrRegistrationNumber...`, `aDoctorWithoutADoctorProfileRow...` | PASS |
| 5 | Specialty free text returned as saved | alias test, fallback test | PASS |
| 6 | Doctor who left: details still returned | `aDoctorWhoLeftTheClinic...` | PASS |
| 7 | Other roles asking another user's details: 403 | no such call exists; own call 403 for the five roles; no path, ids ignored | PASS |
| 8 | Clinic A's doctor never returned to clinic B | `anotherClinicsDoctorIsNeverReturnedByThePort` | PASS |
| 9 | Name and registration number never logged | `EmrLogPrivacyIT` (as configured, and at DEBUG) | PASS (after D-2 fix) |
| 10 | Visiting doctor, two clinics | `aVisitingDoctorOfTwoClinicsGetsEach...` | PASS |

### SAH-43-4 ICD-10 source and review
| # | Criterion | Test | Result |
| --- | --- | --- | --- |
| 1 | Source, version, licence named when the human answers | none | N/T (human decision OQ-43-7, open) |
| 2 | One code per diagnosis, pattern, unique | `EmrGlobalTablesIT.everyDiagnosisHasOneIcd10Code...` | PASS |
| 3 | Starter diagnoses keep working | `diagnosesWithoutTheNewParameters...`, `theOlderStarterListsOfSah7AreUnchanged` | PASS |
| 4 | Starter lists marked; clinician review open | `everySeededEmrTableSaysItIsAStarterList...` (7 comments) | PASS for the label; the review is a human gate |
| 5, 6 | Typed diagnosis has no code; old visit keeps its code | none | N/T (visit stories) |

### SAH-44-1 Templates as data
| # | Criterion | Test | Result |
| --- | --- | --- | --- |
| 1 | Four templates with key, name, version | `EmrTemplatesReadIT.theListHoldsTheFourTemplates...`, `EmrGlobalTablesIT.theFourTemplatesAre...` | PASS |
| 2 | Sections in story order with all attributes (38 rows) | `everyTemplateHasItsSectionsInTheOrder...`, `EmrGlobalTablesIT.everySectionMatchesTheStoryTableRowByRow` | PASS |
| 3 | Counts 11/6, 11/7, 9/4, 7/6 | list test, parts test | PASS |
| 4 | Eleven editor types only | `aSectionThatBreaksTheRulesCannotBeStored` | PASS |
| 5 | Master sections refer to the master; Chip lists have options | `quickOptionCountsAre...`, `everyTemplateSectionHasChips...` | PASS |
| 6 | Required chip lists have a None option | `theChipListsCarryTheirOptions...`, `everyTemplateSectionHasChips...` | PASS |
| 7 | Unique keys, parts, bad lists cannot be stored | `aSectionThatBreaks...`, `anOptionThatBreaks...` | PASS |
| 8 | Global, read-only, 403 for any role | `noGlobalTableHasATenantColumn`, `theAppCannotInsert...`, `aShippedTemplateVersionCannotBe...`, `EmrMastersIT.everyWriteTo...` | PASS |
| 9 | New version keeps the old; newest used; opened visits keep theirs | `aNewVersionIsNewRowsAndTheOldVersionStaysAsItWas` (rolled back) | PASS for the stored rule; end to end N/T |
| 10 | Specialty without a template: clear refusal | `aSpecialtyWithoutATemplateIsAClearRefusal...`, `specialtiesWithoutAnEmrTemplate...` | PASS |
| 11 | Any role reads; no login 401 | `everyClinicRoleReadsTheTemplates...`, `notSignedInAnswers401...` | PASS |
| 12 | SAH-7 specialty templates unchanged | `theClinicsOwnSpecialtyIsMarkedYours...`, existing `ClinicMastersIT` (39) | PASS |
| 13 | Same definitions for two clinics | `theDefinitionsAreTheSameForEveryClinic` | PASS |

### SAH-44-2 Overrides and reset
| # | Criterion | Test | Result |
| --- | --- | --- | --- |
| 1 | Hide Local examination; other clinics unchanged | `EmrOverridesIT.theOwnerHidesASection...` | PASS |
| 2 | Required on or off | `theOwnerSwitchesRequiredOnAndOff...` | PASS |
| 3 | No override: Show on, Required as the template | `EmrTemplatesReadIT.everyTemplateHasItsSections...` | PASS |
| 4 | Hidden and required 400; hiding a required section saves hidden and not required | `aHiddenSectionCannotBeRequired...`, `makingARequiredSectionOptional...` | PASS |
| 5 | Fixed sections 400 with a reason | `theSixSectionsThatCannotBeHidden...` | PASS |
| 6 | Reset removes overrides; answer has the new counts | `resetRemovesEveryOverride...` | PASS |
| 7 | Reset with no overrides: success, no audit | `resettingATemplateWithNoOverrides...` | PASS |
| 8 | Label, part or other field: 400 | `theBodiesAreClosed...` | PASS |
| 9 | Unknown key 404 / malformed 400 | `anUnknownTemplateOrSectionIs404...` | PASS |
| 10 | Clinic id in a body ignored | refused with 400 by the closed body, never applied (note N-1) | PASS (contract reading) |
| 11 | Other roles 403, nothing changes | `everyRoleButTheOwnerIsRefusedWith403...` | PASS |
| 12 | Any role reads, no login 401 | `everyClinicRoleReadsTheTemplates...` | PASS |
| 13 | Two owners: second gets 409 | `aStaleRevisionIs409EvenWhenNothingWouldChange...` (finding 2), `twoOwnersAtTheSameMoment...` (4 templates, real threads), reset 409 | PASS |
| 14 | Clinic B cannot see or change A's overrides | `anotherClinicsOwnerCannot...`, `EmrClinicTablesIT.oneClinicCannot...` | PASS |
| 15 | One audit entry per change; reset one entry with counts | `eachAppliedChangeWritesOneAuditEntry...`, reset test | PASS |
| 16 | Opened visit keeps its sections | resolver part PASS; stored snapshot N/T (SAH-46) | PASS / N/T |
| 17 | New template version: overrides kept by key, removed ignored not deleted | `anOverrideRowForASectionThatIsNotInTheTemplate...`, `EmrClinicTablesIT...SurvivesVersionChanges` | PASS (a real second version cannot be committed) |
| 18 | All or nothing | every refusal asserts no revision, override or audit row; failure injection mid-change not done | PASS / gap (R-4) |
| + | First save stores revision 1 / settings version 1 while the API shows 0 before (finding 1) | override and settings tests | PASS |
| + | 413, broken JSON | `aBodyOverSixtyFourKilobytesIs413`, `anEmptyOrBrokenBody...` | PASS |

### SAH-44-3 Settings
| # | Criterion | Test | Result |
| --- | --- | --- | --- |
| 1 | Defaults Collapsed, Case paper, Assessment | `EmrSettingsIT.aClinicWithNoSavedSettings...` | PASS |
| 2, 3 | Owner saves the two settings, returned on next read | `theOwnerSavesTheSectionPanel...` | PASS |
| 4 | Other values 400 | `anyOtherValueIsA400...` | PASS |
| 5 | Default part refused | `theDefaultPartCannotBeChanged...` | PASS |
| 6 | Other roles 403; all read; 401 | `everyRoleButTheOwnerIsRefused...`, defaults test | PASS |
| 7 | One audit entry, before and after of each changed setting | `everyChangeWritesOneAuditEntry...` | PASS |
| 8 | Two owners: 409 | `aStaleVersionIs409...`, `twoOwnersSavingFirst...` | PASS |
| 9 | Two clinics isolated | `oneClinicsSettingsAreNeverSeen...` | PASS |
| 10, 11 | Doctor's toggle stores nothing; applies next time an EMR opens | none | N/T (no screen, no EMR) |

### SAH-44-4 Template choice and snapshot
| # | Criterion | Test | Result |
| --- | --- | --- | --- |
| 1 | Eight names choose four templates, ignoring case and spaces | `EmrDoctorContextIT.theSpecialtyNamesOfTheAliasList...` (12 spellings), `EmrGlobalTablesIT.theEightAliases...` | PASS |
| 2 | Not in list: clinic's specialty, then General practice | `aSpecialtyNotInTheListFallsBack...` | PASS |
| 3 | Effective sections = template + overrides | `aSnapshotHoldsTheShownSections...`, `anOwnersChangeAppliesToSnapshots...` | PASS |
| 4-8, 10, 13 | Stored snapshot, change after change, new version, signed refusal, switch, other clinic's visit | none | N/T (SAH-46, SAH-51) |
| 9 | Switch not saved as default | `theDoctorsSpecialtyBeatsTheClinicsAndTheSwitch...` | PASS |
| 11 | Key not one of four refused (400) | `aSnapshotOfATemplateThatIsNotOneOfTheFour...`, `aMissingChoiceSourceIsABadRequest...` (service level) | PASS (HTTP in SAH-46) |
| 12 | Other roles 403; owner without details refused | `/emr/me` and quick-option 403 tests | PASS for the calls that exist |
| 14 | No specialty anywhere: General practice, says default | `aSpecialtyNotInTheListFallsBack...` | PASS |
| 15 | No name or clinical text in logs | `EmrLogPrivacyIT` | PASS |

### The three standard backend checks
Another clinic's data invisible: PASS (overrides, revision, settings, usage, doctor details; all four tables at SQL level). A role without permission gets 403: PASS (eight roles on every endpoint; claim-only and claim-OWNER tokens). Invalid input gets 400 with a problem body: PASS (page, limit, specialty, `q` over 100, unknown master and key, hidden-and-required, fixed sections, closed bodies, wrong-typed and missing fields, bad settings values, broken JSON; 404, 409, 413 bodies checked for type and fixed wording; nothing stored after any refusal).

## 5. Defects

Both were found by the first runs, reported here, and **fixed by the java-developer before the final runs** (the java-developer saw the red tests in `validate backend` run 2). No defect is open.

| ID | Owner | Severity | What | Reproduction | State |
| --- | --- | --- | --- | --- | --- |
| D-1 | java-developer | Low | `EmrTemplateResolver.snapshot(tenant, "gp", null)` threw `NullPointerException` (`List.of(...).contains(null)`) instead of the 400 bad-request that every other bad input gets. Only the future SAH-46 caller can pass null. | Test `EmrDoctorContextIT.aMissingChoiceSourceIsABadRequestNotANullPointer` (was part of `aSnapshotOfATemplateThatIsNotOneOfTheFour...`). | Fixed: `choiceSource == null ||` added (`EmrTemplateResolver.java` line 91); test passes. |
| D-2 | java-developer (config), security-reviewer to confirm | Low (needs DEBUG logging switched on) | At DEBUG, Spring Security's `FilterChainProxy` prints `Securing GET /api/v1/opd/emr/masters/complaints?q=<typed search text>`. The search text of the EMR (and of `GET /diagnoses?q=`, `GET /medicines?q=`, and any other query-string search) would reach the log if someone raised the level. The application already pinned `org.springframework.web.servlet` to INFO for the same reason; the security logger was not. | `EmrLogPrivacyIT.noLogLineAtDebugLevelHolds...` (root DEBUG, only the Spring MockMvc harness left out). At the configured levels the application logged nothing. | Fixed: `org.springframework.security.web.FilterChainProxy: INFO` in `application.yml`; test passes. Other query-string searches (patients, etc.) are covered by the same pin. |

Findings from the handoff, checked: (1) first save stores revision/version 1 while the API shows 0 before, and a second save with 0 is a 409: confirmed by tests. (2) stale revision before the no-change shortcut: confirmed (409 even when nothing would change). (4) `snapshot(tenantId, templateKey, choiceSource)` is three-argument: tested in that form. (6) the ranking index is used, favourites and hints order as designed: confirmed. (7) a wrong role with an unknown field gets 400 before 403 (the body is read before the role check): not asserted; no data is exposed; the security-reviewer may want it noted.

## 6. Problems in my own first tests (fixed; recorded so nobody repeats them)
The first run had 12 red tests, all fixture errors of mine except D-1 and D-2: (a) the setup wizard already gives the owner a doctor profile, so my "owner without doctor details" was wrong; now a clinic that has only signed up is used, and the owner's details are written as an upsert. (b) The free plan allows 5 users in total, so adding seven staff to one clinic got `409 plan-limit`; `addStaff` now falls back to a second sign-up plus a membership row when the clinic is full. (c) `canEdit` is decided from the membership, not from the token, so "canEdit is false" is asserted for real people only.

## 7. Not tested, and why

| Item | Why | Where it goes |
| --- | --- | --- |
| Any browser test | No screen. | SAH-45 onward. |
| Snapshot stored with a visit, switching, signed-visit refusal, other clinic's visit 404 (44-4 criteria 4 to 8, 10, 13); doctor's panel toggle and "applies next time" (44-3 criteria 10, 11) | No EMR visit (SAH-46), no sign step (SAH-51). The snapshot content and rules are tested on the resolver service. | SAH-46, SAH-47, SAH-51 test plans. |
| Counts from the real sign step; drafts and addenda not counting | SAH-51. The recorder is tested with the sign step's contract. | SAH-51. |
| A committed second template version | The template tables are immutable; a committed row can never be removed, so every later run and every environment copy of the test database would carry it. The stored rule is tested in a rolled-back transaction. | Revisit in SAH-53 if the product owner accepts a fixture version. |
| Failure injected between the revision step and the audit write | Needs a fault in a committed code path. | Code review of `@Transactional`; the refusals are tested for "nothing stored". |
| Removed master items, old ICD-10 code on an old visit; free-typed diagnosis has no code | No visits. | Visit stories. |
| ICD-10 source and licence; clinical review of every list | Human and clinician decisions (OQ-43-7, OQ-43-8). | Release gate. |
| `opd` does not read `platform` tables | Covered by `ModularityTests` and the developer's tests; I did not add a source scan. | Code review. |
| Performance | No numbers are set in the stories. | Later. |

## 8. Risks

- **R-1 Withdrawn stories.** SAH-44-1 to 44-4 are marked withdrawn by the BA (12 Nov, DEC-039). If the human's merge approval covers the built code, fine; but `EmrTemplatesReadIT`, `EmrGlobalTablesIT` (section table, six non-hideable, aliases, counts 38/45/8), `EmrOverridesIT` (six fixed sections) and parts of `EmrDoctorContextIT` encode the old model (4 templates, 38 sections, six fixed). DEC-039 says only Allergies is locked and there are 15 departments and 100 sections: expect these tests to be rewritten with SAH-53.
- **R-2 Section keys become permanent** when SAH-46 stores snapshots (SO-44-10, not confirmed by a human).
- **R-3 The index proof is by EXPLAIN with sequential scans off** on 4,500 rows in a rolled-back transaction. It shows the index can serve the read and give the order without a sort; it does not show the planner chooses it at 10 million rows. Re-check with real volume before the table grows (design note: review at 30 million rows).
- **R-4 All-or-nothing** is checked by the absence of partial rows after refusals, not by injecting a fault.
- **R-5 Concurrency tests use real threads** and passed in every run (three full runs); they depend on timing in principle, but each asserts exact outcomes (one 200 and one 409; counts of 6 and 2).
- **R-6 Team plan limit:** a clinic with five users cannot add a doctor through Team; unrelated to this story but it made the first tests fail, so the next tests with many roles should use `addStaff` of `EmrSupport`.
- **R-7 The 403 on writes to read-only paths** is a convention deviation approved in DEC-038 (platform convention is 405). Tests assert 403.

## 9. What a clinician must still review (release gate; nothing here is clinically reviewed)

1. Every starter list: complaints (59), investigations (46), advice (50), the 53 ICD-10 diagnoses and 52 medicines of SAH-7: names, wording, specialty tags.
2. The ICD-10 source, version and licence (OQ-43-7): unnamed.
3. The 38 sections: which are required by default, which cannot be hidden. **Paediatrics and Homeopathy have no allergy section** (as the BRD table says; a patient-safety question the human left open).
4. The chip lists: Local examination, Extra-oral and Intra-oral examination, Radiographs, Birth and immunisation history, Development, Vaccines given today, Case history, Mentals and generals hold **only a "None" chip**; the Dental Medical alerts chips are only the five the mockup names (bleeding disorder, heart condition and blood thinners are missing); the General practice allergy chips are the mockup's.
5. "Prescription" cannot be hidden in Dental and Paediatrics either (ADR D4); the Paediatrics "by weight" has no weight rule (SAH-48).
6. The doctor-specialty alias list (eight spellings) and the rule that an unknown specialty falls back to the clinic's.
7. That a missing degree or registration number is only reported (the case paper prints "—"), not blocked, against the NMC wording of the BRD.

## 10. Notes

- **N-1** Story SAH-44-2 says a clinic id in a body "is ignored"; the contract closes the body and the code refuses it with 400. The tests follow the contract; either way a body never chooses the clinic. For the business-analyst to align the wording.
- **N-2** The design note S-16 expects 42501 "immutable" for TRUNCATE on all three template tables; for the two that other tables point at, a plain TRUNCATE may be stopped first by the foreign key (0A000). The test requires 42501 "immutable" for `TRUNCATE ... CASCADE` and accepts either for the plain statement. For the db-engineer to correct the sentence.
- **N-3** `GET /emr/me` returns the name and registration number on purpose (they are on the screen); the log test proves they are not logged and not repeated in other responses.

## Handoff · SAH-43 and SAH-44 · qa-engineer → code-reviewer, security-reviewer
Status: DONE (backend only; the stored-visit parts wait for SAH-46 and SAH-51)
Mockup screen: none (data and backend only; Clinic setup → Specialty template is the nearest, built later in SAH-45)
Files changed:
- `docs/test-plans/SAH-43-44.md` — plan: every criterion and S-case mapped to a test or "not testable yet"
- `docs/reviews/QA_REPORT_SAH-43-44.md` — this report
- `backend/src/test/java/com/sahaj/platform/it/EmrSupport.java` — shared set-up
- `backend/src/test/java/com/sahaj/platform/it/EmrMastersIT.java`, `EmrQuickOptionsIT.java`, `EmrDoctorContextIT.java`, `EmrTemplatesReadIT.java`, `EmrOverridesIT.java`, `EmrSettingsIT.java`, `EmrGlobalTablesIT.java`, `EmrClinicTablesIT.java`, `EmrLogPrivacyIT.java` — 144 new integration tests
- `backend/src/test/java/com/sahaj/platform/it/VitalsCatalogIT.java` — +2 tests (six `trg_emr_*` triggers by name; FORCE RLS on the four clinic tables)
Validation: java-developer `node scripts/validate.mjs backend` run 3 → PASSED (ITs 2,084 run, 0 failed, 26 skipped by design; unit 3,878 run, 0 failed). My targeted run: 216 run, 0 failed, 0 errors, 0 skipped.
Decisions made: tests follow the contract where the story differs (closed body: a clinic id in a body is refused with 400, N-1); no browser test (no screen); a committed second template version was not created (immutable, permanent).
Open questions / requests for other owners:
- code-reviewer: check `opd` reads platform only through `platform.api` (no source scan was added); check `@Transactional` on override, reset and settings (all-or-nothing is proven by absence of partial rows, not by fault injection); note finding 7 (400 before 403 for an unknown field).
- security-reviewer: confirm D-2 fix (`org.springframework.security.web.FilterChainProxy: INFO`) is enough for query-string search text; confirm 403 on writes to read-only paths; the registration number and name are returned on `/emr/me` by design and never logged.
- business-analyst: align the SAH-44-2 sentence "a clinic id in a body is ignored" with the closed body (N-1); note SAH-44-1..4 are withdrawn (DEC-039), so the template tests will change with SAH-53 (R-1).
- db-engineer: design note S-16 TRUNCATE wording (N-2).
- Human / clinician: section 9 (starter lists, ICD-10 source OQ-43-7, no allergy section in Paediatrics and Homeopathy, chip lists with only "None").
