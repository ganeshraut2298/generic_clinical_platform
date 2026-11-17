# SAH-76 test plan · Nursing integration, Sprints 1-2 (WP1 orders and pharmacist, WP2 notices; WP3 and WP4 by QA-2)

Branch feature/SAH-39-home-kinetic-background. Contract `docs/api/ipd.yaml` 0.4.2, ADR-027, design note `docs/database/SAH-76.md`, migration `docs/test-plans/SAH-76-migration.md` (migration cases M1 to M9, all pass: 21 tests in Sah76MigrationIT and RowLevelSecurityCatalogIT, and the proof line `SAH-76 backfill proof: orders=2847 version1=2847`).
Database: `sahaj_test` only. Test classes (all in `backend/src/test/java/com/sahaj/platform/it/`, profile `test`, MockMvc, real PostgreSQL):

| Class | Scope | Tests |
| --- | --- | --- |
| Sah76OrdersIT | story 76-1 and 76-2: place, change, hold, resume, stop, query, reply, verify, review after dose, paging, tenant, privacy | 28 |
| Sah76NoticesIT | story 76-3: Orders tab list, ward list, summary, acknowledge, scope guard | 11 |
| Sah76OrdersConcurrencyIT | two users at the same moment (forced order through the admission row lock) | 7 |
| Sah76OrderCommitCheckIT | deferred head trigger in every write order | 7 |
| Sah76MigrationIT | migration | 7 |
| Sah76Support | shared urls, bodies, texts | - |
| Sah76DoseGiveIT, Sah76DoseCorrectionIT, Sah76DoseDbIT, Sah76SettingsIT, Sah76SettingsEffectIT, Sah76NursingSupport | WP3 and WP4 (plan: docs/test-plans/SAH-76-dose-settings.md) | 22 + 13 + 11 + 13 + 12 = 71 |

Playwright: not written in this run (the angular work is "not checked in a browser"; e2e needs the running app). Listed as a gap.

## Coverage: acceptance condition to test to result

Result column: "pass" means green on the final run of 16 Nov 2026 (section "Final results" at the end). "gap" means no test.

### Story 76-1 ordering, change, hold, stop

| Acceptance condition | Test (class.method) | Result |
| --- | --- | --- |
| Scheduled order with dose, unit, route, version 1, waits for pharmacist; notice only after the check | Sah76OrdersIT.aScheduledOrderIsPlacedWithDoseRouteAndVersionOneAndWaitsForThePharmacist | pass |
| PRN order with indication, minimum interval, maximum | Sah76OrdersIT.aPrnOrderIsPlacedWithItsIntervalAndMaximum | pass |
| STAT: one dose due now, no frequency, notice at once | Sah76OrdersIT.aStatOrderIsOneDoseDueNowAndTheNurseIsToldAtOnce | pass |
| Verification Off: Active at once, "Not pharmacist checked", notice written | Sah76OrdersIT.whenTheWardDoesNotNeedAPharmacistTheOrderIsActiveAtOnceAndTheNoticeIsWritten | pass |
| Every field error together with the contract text, nothing saved (dose, unit, route, start in the past, duration, PRN fields, STAT with frequency, indication length and control characters) | Sah76OrdersIT.everyFieldErrorOfTheOrderFormIsAnsweredTogetherWithTheContractTextAndNothingIsSaved; MedicineAccessIT.orderBodyErrorsComeTogether... | pass |
| Retry or double tap makes no second order; other values under the same key 409 client-key-reused | Sah76OrdersIT.aRepeatOfTheSameKeyAnswersTheFirstOrderAndADifferentDoseUnderTheSameKeyIsRefused | pass |
| Only doctor or owner may change, hold, resume, stop, reply (403 "Only a doctor can do this.") | Sah76OrdersIT.onlyADoctorOrTheOwnerMayChangeHoldResumeStopOrReplyAndOnlyThePharmacistOrOwnerMayQuery | pass |
| Change writes version 2, old version superseded, back to pharmacist, CHANGED notice, audit | Sah76OrdersIT.aChangeWritesVersionTwoGoesBackToThePharmacistAndWritesAChangedNotice | pass |
| Change: wrong version 409 order-changed; reason 4 to 200; unchanged values 400 "Nothing was changed."; unit of the medicine; control characters | Sah76OrdersIT.aChangeNeedsTheVersionTheDoctorSawAChangeAndAReasonAndRefusesAnUnchangedOrder | pass |
| Repeated change read from the state, never client-key-reused, writes only the access row | Sah76OrdersIT.aRepeatedChangeIsReadFromTheStateAndNeverAnswersClientKeyReused | pass |
| On hold must be resumed before a change; stopped order cannot be changed | Sah76OrdersIT.anOrderOnHoldMustBeResumedBeforeAChangeAndAStoppedOrderCannotBeChanged | pass |
| Changed version not givable until verified; verify names the new version | Sah76OrdersIT.aChangeOfAnUnverifiedOrderKeepsItWaitingAndTheVerifyMustNameTheNewVersion | pass |
| Hold then resume, no new pharmacist check, no new version, HELD and RESUMED notices | Sah76OrdersIT.holdPutsAnActiveOrderOnHoldAndResumeBringsItBackWithoutANewPharmacistCheck | pass |
| Hold and resume refuse wrong state, wrong version, short reason | Sah76OrdersIT.holdAndResumeRefuseTheWrongStateTheWrongVersionAndAShortReason | pass |
| Repeated hold and resume write nothing | Sah76OrdersIT.aRepeatedHoldAndAResumeRepeatWriteNothingNew | pass |
| Stop needs version and reason (4 to 200); stored; never in audit or event log | Sah76OrdersIT.aStopNeedsTheVersionAndAReasonAndStoresTheReasonWithoutLoggingIt | pass |
| An order on hold can be stopped; stop repeat by the same user is 200 | Sah76OrdersIT.anOrderOnHoldCanBeStoppedAndTheOpenHoldStaysAsHistory | pass |
| Two doctors change the same version: first wins, second 409 order-changed, nothing written by the second | Sah76OrdersConcurrencyIT.twoDoctorsChangingTheSameVersionOneWinsAndTheOtherIsToldItChanged; ...twoChangesStartedTogetherNeverMakeTwoVersionTwoRowsOrAServerError | pass |
| Hold against stop, stop against hold | Sah76OrdersConcurrencyIT.holdAheadOfStopAndStopAheadOfHoldGiveTheRightAnswers | pass |
| Another clinic's order 404 for every action | Sah76OrdersIT.anotherClinicsOrdersNoticesAndReviewsAreInvisibleAndAnswerNotFound | pass |
| Audit and events carry no indication, reason, question or reply | Sah76OrdersIT.noClinicalTextOfTheNewActionsReachesTheAuditTrailOrTheEventLog | pass |
| Order without a version row is refused at commit; either write order commits | Sah76OrderCommitCheckIT (7 tests) | pass |
| Not admitted 409; Read-only 409 ipd-readonly | not tested for the new operations (needs a Read-only clinic helper) | gap |

### Story 76-2 pharmacist

| Acceptance condition | Test | Result |
| --- | --- | --- |
| Query makes order Queried, not verifiable, row shows question; reply returns it to the pharmacist; verify works after | Sah76OrdersIT.aPharmacistQueryMakesTheOrderQueriedNotVerifiableAndTheDoctorsReplySendsItBack | pass |
| Question and reply validation (empty, 300, control characters), orderer cannot query own order (403 self-verification), wrong state | Sah76OrdersIT.theQuestionAndReplyAreValidatedAndTheOrdererCannotQueryHisOwnOrder | pass |
| Repeat of query and reply read from state | Sah76OrdersIT.repeatsOfQueryAndReplyAreReadFromTheStateAndWriteNothing | pass |
| Two queries or two replies at once: only the first | Sah76OrdersConcurrencyIT.twoQueriesAndTwoRepliesAtTheSameTimeAcceptOnlyTheFirst | pass |
| Change, stop or reject closes the open query | Sah76OrdersIT.aChangeAStopAndARejectEachCloseTheOpenQuery | pass |
| Verify needs orderVersion; NEW notice written at verify | Sah76OrdersIT.verifyNeedsOrderVersionAndWritesTheNewNoticeOnlyThen | pass |
| Change ahead of verify gives order-changed; verify ahead of change lets the change through | Sah76OrdersConcurrencyIT.aChangeAheadOfTheVerify...; aVerifyAheadOfTheChange... | pass |
| Queue keyset paging, bad limit and cursor 400 | Sah76OrdersIT.thePharmacistQueueIsPagedByKeysetAndRefusesABadLimitOrCursor | pass |
| STAT dose before the check lists a review; Mark reviewed closes; repeat quiet; other user 409 | Sah76OrdersIT.aStatDoseGivenBeforeTheCheckIsListedForReviewAndMarkReviewedClosesIt | pass |
| Verify closes the review as VERIFIED, reject as REJECTED | Sah76OrdersIT.verifyingTheOrderClosesItsOpenReviewAsVerified; rejectingTheOrderClosesItsOpenReviewAsRejected | pass |
| Roles: others 403 on query, reviews, mark reviewed | Sah76OrdersIT.onlyADoctorOrTheOwner... | pass |
| Hard stop on a reviewed STAT order: Mark reviewed replaced by Query | not tested (needs an allergy that appears after the dose) | gap |
| Review lists DISCHARGED admissions; DOSE_UNDONE closes the review | covered by QA-2's dose classes only if they do; not in mine | gap |

### Story 76-3 notices

| Acceptance condition | Test | Result |
| --- | --- | --- |
| Orders tab lists a verified order as New with details, counts, no STAT line | Sah76NoticesIT.theOrdersTabListsAVerifiedOrderAsNewWithItsDetailsAndTheCounts | pass |
| STAT notice shows the STAT line (15 min window) and sorts first | Sah76NoticesIT.aStatNoticeShowsTheStatLineAndSortsBeforeOtherNotices | pass |
| Change, hold, resume, stop each write their notice; CHANGED shows lines and reason | Sah76NoticesIT.changeHoldResumeAndStopEachWriteTheirOwnNotice | pass |
| Acknowledge once, repeat quiet, another nurse 409 "<name> already acknowledged this.", doctor and pharmacist 403, audit without names | Sah76NoticesIT.aNurseAcknowledgesOnceARepeatIsQuietAndAnotherNurseOrADoctorIsRefused | pass |
| Two nurses at once: first wins | Sah76OrdersConcurrencyIT.twoNursesAcknowledgingTheSameNoticeOnlyTheFirstIsRecorded | pass |
| Owner may acknowledge; doctor reads but has no button | Sah76NoticesIT.theOwnerMayAcknowledgeAndTheDoctorSeesTheListButCannotAcknowledge | pass |
| Roles of the list, summary and ward list (403 text "You cannot open the order list.") | Sah76NoticesIT.theOrderListIsForNursesOwnerAndDoctorsOnly | pass |
| Summary is counts only; change token moves; writes nothing | Sah76NoticesIT.theSummaryHoldsCountsOnlyAndItsChangeTokenMovesWithEachNotice | pass |
| Ward list paged, STAT first, one access row per patient of the page, bad limit and cursor 400 | Sah76NoticesIT.theWardWideListIsPagedStatFirstAndWritesOneAccessRowPerPatientOfThePage | pass |
| A dose acknowledges the open notices via DOSE | Sah76NoticesIT.aDoseForTheOrderAcknowledgesItsOpenNoticesWithViaDose | pass |
| Bad ids 400, unknown ids 404 | Sah76NoticesIT.theAcknowledgeRefusesBadIdsAndUnknownNotices | pass |
| Ward scoping: nurse sees and acknowledges only her ward; owner not narrowed | Sah76NoticesIT.aWardScopedNurseSeesAndAcknowledgesOnlyTheNoticesOfHerOwnWard | pass |
| Another clinic's notice 404 and list empty | Sah76OrdersIT.anotherClinicsOrdersNoticesAndReviewsAreInvisibleAndAnswerNotFound | pass |
| "Not acknowledged for n min" red flag after the setting; badge colours; 30 s polling; Read-only 409; DISCHARGED leaves lists | not tested (needs time travel on the notice clock; Read-only helper); polling and badges are UI | gap |

### Older tests changed by the migration and the contract (assertion changes only)

See the QA report for the list. Shared helpers changed: MedicineSupport (order body with dose, unit, route, priority; stop body; verify orderVersion), MarSupport (given and not-given bodies carry orderVersion and the manual override because no medicine codes are loaded; the backdating helper also backdates version 1; "60 min before"), VitalsSupport.asOwner (version table goes with the head; deferred check run before the ALTER TABLE) and the new `backfillVersionOne` for tests that write order heads by SQL.

## Gaps to schedule
1. Playwright e2e for orders, query/reply, notices and acknowledge (happy path and one error path): needs the dev stack and the human-approved screen map entries.
2. Read-only IPD and not-admitted cases for change, hold, resume, query, reply, acknowledge.
3. Overdue flag ("Not acknowledged for n min") and STAT overdue line: need a clock seam in the notice repository.
4. Hard-stop-after-dose review row; DISCHARGED review rows; DOSE_UNDONE closing a review.
5. Device label (X-Sahaj-Device) in the audit of the order actions.

## Final results (16 Nov 2026, branch feature/SAH-39-home-kinetic-background, database sahaj_test, Maven offline except validate)

| Run | Command | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- | --- |
| SAH-76 integration tests | `mvn -o verify -Dit.test='Sah76*IT'` | 131 | 0 | 0 | 0 |
| Older dose, MAR, medicine, vitals, scope, card ITs (touched by SAH-76) | `-Dit.test='Dose*IT,Mar*IT,Medicine*IT,PharmacistCheckIT,AdmittedCardsIT,CardFactsIT,QueryPlansIT,ScopePlansIT,ScopeRaceIT,ScopeReviewFixIT,RowLevelSecurityCatalogIT,WardScopeEnforcementIT,Vitals*IT,StaffPinColleagueHttpIT,ReleaseAuditHardeningIT,EmergencyAccessIT'` | 483 (235 + 248 on the re-run of 18 classes) | 0 | 0 | 0 |
| Unit tests | `mvn -o -f backend/pom.xml test` | 5,462 | 0 | 0 | 0 |
| Gate | `node scripts/validate.mjs backend` (unit + whole IT suite, 21:50 to 23:13) | 5,462 unit + 2,972 IT (219 classes) | 0 | 0 | 25 (DevSampleAdminFunctionIT 19, DevToolsGuardCallbackIT 5, DevToolsCleanupScriptIT 1: older dev-tool tests, not SAH-76) |

Note on the second run: 18 classes (248 tests) errored at the start of the context with `FileNotFoundException ... TenantProbeController.class` because the class files in `backend/target/test-classes` were rewritten by a compile that was not mine while the run was going (21:12 to 21:18). Nothing in the code or the tests was wrong; the same 18 classes passed 248 of 248 on an immediate rerun, and the whole suite later passed in `validate backend`.

Older tests changed for SAH-76 (assertion or set-up changes only, as far as the migration or the contract 0.4.0 to 0.4.2 changed): AdmittedCardsIT, CardFactsIT, DoseConcurrencyIT, DoseGiveIT (a dose before the start is now 400 `slotAt`), DosePrivacyIT, DoseSecondNurseIT, DoseTableIT, DoseWithoutPepperIT, MarReadIT, MarSupport, MedicineAccessIT, MedicineDbErrorsIT, MedicineOrderFlowIT, MedicineOrderTableIT, MedicineSupport, PharmacistCheckIT, QueryPlansIT, RoundSupport, RowLevelSecurityCatalogIT, ScopePlansIT, ScopeRaceIT, ScopeReviewFixIT, StaffPinColleagueHttpIT, VitalsCatalogIT, VitalsSupport, WardScopeEnforcementIT, EmergencyAccessIT, ReleaseAuditHardeningIT. Time of day: tests that place a fresh order backdate version 1 with the head (`verifiedAt`, `backdate`), so slots do not depend on the hour the suite runs; the only tests on the real clock are STAT and PRN ones (no slot, or the slot is the creation minute) and the undo window (10 s).

Still not covered: Playwright e2e (no e2e for orders, notices, giving, correcting or settings: the mocks predate 0.4.0 and the reference screens are not in screen-map.md); Read-only and not-admitted for change, hold, resume, query, reply and acknowledge; overdue notice flag; hard-stop review row, DISCHARGED review rows, DOSE_UNDONE closing a review; MAR slot states ON_HOLD, STOPPED, REPLACED and QUERIED read through the MAR; device label on the order actions; real cross-origin preflight; admitted card `vitalsDue` following the profile.

## Go-live additions (17 Nov 2026)

The whole-flow, dose and order state, race and money cases that this plan listed as gaps or that the go-live review (`docs/reviews/GO-LIVE-ipd-test-gaps.md`) asked for are in `docs/test-plans/IPD-go-live.md`: `IpdJourneyIT` (15 steps on one admission, with the EMR execution line checked against the MAR after undo, attempt 2, recorded in error and not given), `DoseOrderStateIT` (MAR states ON_HOLD, QUERIED, REPLACED, STOPPED, counters, PRN lock reasons, an old order version refused and audited), `DoseOrderRaceIT`, `IpdMoneyStayIT`. Still open from this plan: Read-only and not-admitted for change, hold, resume, query, reply and acknowledge (journey step 14 covers not-admitted for hold, change, stop, verify and the notice list only); the overdue notice flag; the hard-stop-after-dose review row; the PRN start and end window (contract 0.4.3).
