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

## Give sheet e2e: D8 and review round 4 (19 Nov 2026)

Playwright, all API calls mocked in the browser (`frontend/e2e/mar.spec.ts`, `sah32-world.ts`, `layout-matrix.spec.ts`). These prove what the Give sheet and the toasts do with the answers; the server rules stay with the backend integration tests. Build checked before the final run (19 Nov, after the angular fix for defects 3 and 4): `frontend/dist/frontend/browser` (newest file `chunk-jTFy7269.js`, 10:46:31) is newer than every file under `frontend/src` (newest `_tokens.scss`, 10:42:46) and `styles-HYXSSGE3.css` holds `sheet-solid`, so no rebuild was needed. Port 4394. The final runs used `--output` in a scratch folder: the default `frontend/e2e/results` folder is shared with the Pharmacy session's runs, and one of them deleted a trace file under a test of mine (see the repeat batch).

Mock world (older specs do not notice): `MarWorld.undoSeconds` (unset = no Undo), `DoseW.undoSecondsLeft` and `DoseW.canUndo` (default 0 and false). The Undo request is answered by a test-local route held on a gate (`holdUndo` in mar.spec.ts).

### D8 (code review round 3 #1): no toast while the Give sheet is open

| Condition | Test (mar.spec.ts) | Result |
| --- | --- | --- |
| A refusal shows only in the sheet; no `.mat-mdc-snack-bar-container` (helper `expectNoToast`) | R11 refusal, R11 failed request, I6, "after an error no toast is shown over the sheet" | pass |
| Close is not covered after a refusal | "after an error no toast covers the Close button" (phone-360); F1/D8 at 360 px (both projects) | pass (were FAIL before the sticky header; defect 1 closed) |
| Record not given and reload toasts are announced after the sheet closes | R11 Record not given done; R11 reload after a 409 | pass |

### Review round 4 and security round 4

| Finding | Condition | Test (mar.spec.ts, "SAH-76 round 4: ...") | Result |
| --- | --- | --- | --- |
| Code #5 | Given toast without Undo is announced (live region is not `aria-live=off`) | "R11: the success toast of Given without Undo ... is announced" (the old test at :1887 now asserts `not.toHaveAttribute('aria-live','off')`) | pass |
| Code #5 | "Undone." is announced | "a late done answer ..." (last assertion) | pass |
| Code #5 | Marked-in-error toast: tap a correctable given dose, choose a reason, the request carries the reason key and a client key, the toast "Marked as recorded in error: duplicate entry. The original entry is kept." shows and is announced, the panel closes, the slot is Due again | "the marked-in-error toast" (new; `DoseW.canMarkInError` added to the mock world, the recorded-in-error request answered by a test-local route) | pass |
| Code #1, R4-1 | `give-failure` in view below `.sheet-h` and above `.sheet-f` at 360x740, `role=alert`, no `tabindex`, focus not inside; Close on screen and hit at its centre | 5 tests: 500, 403 forbidden, 409 client-key-reused, non-high-alert wrong PIN, lost answer | pass: defect 3 closed. 225 of 225 on phone-360 with `--repeat-each=25` in a clean run (see Counts); before the fix about 1 in 25 failed |
| Code #1, R4-1 | `give-prn-lock` (PRN too soon) in view below `.sheet-h` and above `.sheet-f`, alert, no tabindex, focus not inside, Given off; Close hit | "give-prn-lock (PRN too soon) ..." | pass |
| Code #1, R4-1 | `wristband-mismatch`, `code-mismatch`, `no-code-notice`: in view below `.sheet-h` and above `.sheet-f`, polite region, focus on the box; Close hit | 3 tests | pass |
| R4-2 | Wrong patient, then Record not given fails (500): STOP line stays, Given off; editing the box clears the STOP and turns Given on | "R4-2 wrong patient ..." | pass |
| R4-2 | Same for wrong medicine (code box) | "R4-2 wrong medicine ..." | pass |
| Code #2 | 400 with only `secondNurseId` on a non-high-alert sheet: message in `give-failure`, `second-nurse-error` absent | "code #2 ..." | pass |
| Code #3 | Second `no-code-loaded`: notice removed while pending, drawn again as a new element, Given on | "code #3 ..." | pass |
| Code #6 | Late Undo "done" answer while the next give sheet is open: no toast, MAR updated at once, "Undone. ..." after all dialogs close | "a late done answer ..." | pass |
| Code #6 | Late Undo refusal: no toast, MAR read again, server text after all dialogs close | "a late refusal ..." | pass |
| Code #6 (extra) | Undo answer with no give sheet open: "Undone." shows while the Nursing sheet is open | "an Undo answer with no give sheet open ..." | pass (was FAIL; defect 2 closed) |
| Defects 1 and 4 | The sticky header at rest at 1440x900: `position: sticky`, nothing scrolled, title and Close hit at their centres, surface opaque (alpha 1); the header colour equals the sheet surface just below it, within 2 levels per channel (pixels read from a screenshot decoded in the test with `node:zlib`); screenshot kept | "header at rest at 1440x900: opaque --sheet-solid, equal to the sheet surface ..." (reworded and strengthened) | **FAIL on both projects by 1 level**: header 242,243,245; body 3 px below 242,242,242 (blue differs by 3); see defect 4 |
| Defects 1 and 4 | Over scrolled content at 1440x480: the sheet scrolls, the header stays at the same top, opaque, a field or button is scrolled under it (identity bar, name, allergy chip at scrollTop 40), 376 free header points (not text, not Close) on three rows all read the rest colour (worst difference 0); title and Close hit; screenshot kept | "header over scrolled content at 1440x480: ... scrolled fields hidden behind it ..." (reworded and strengthened) | pass |
| Code #7 | Given: toast shows, sheet detaches within 3 s, toast never covers Close while the sheet is not closing (longest cover at most 100 ms, the exit window) | "after a successful Given ..." | pass |
| Code #7 | Same for Record not given | "after a successful Record not given ..." | pass |
| Code #7 | Same for Given at 360x740 (toast at the top) | "at 360x740 the same holds for Given ..." | pass |

Notes on the measures:
- In-view rule: `line.top >= 0`, `line.top >= head.bottom - 1`, `line.bottom <= viewport` and `line.bottom <= foot.top + 1`, plus Close on screen and the element hit at its centre. The 1 px margin is still needed after `Math.ceil`: with the margin removed, `give-failure` ended 0.20 to 0.36 px under `.sheet-f` in all five paths on both projects, because the browser snaps the scroll offset to a whole pixel while the lines sit at fractional positions. A larger gap fails. The header side is within the margin by a wide margin (the bottom lines sit about 480 px below it), so it is not stressed by these tests.
- Cover rule (code #7): the app closes the sheet and opens the toast in one task, so for one or two frames the sheet is in the page without its closing class. That window is not "interactive"; a toast that stayed over an interactive sheet would cover Close for seconds. Three runs of these tests passed each time.

### Layout matrix (`layout-matrix.spec.ts`)

19 Nov final run: 84 of 84 passed (12 per layout project, 7 projects). The one stale test updated on 19 Nov (see the earlier note: it measures the wrong-patient state, edits the box, then measures the second-check text) stays as it was; nothing else changed.

### Counts (Playwright 1.64, port 4394, projects phone-360 and desktop-1440 for the five Give-sheet specs, final run 19 Nov, one run of all specs, 4 workers)

| Spec | Tests per project | phone-360 | desktop-1440 |
| --- | --- | --- | --- |
| mar.spec.ts | 125 | 124 pass, 1 fail | 124 pass, 1 fail |
| colleague-pin.spec.ts | 47 | 47 | 47 |
| medicine-round.spec.ts | 65 | 65 | 65 |
| refused-refresh.spec.ts | 68 | 68 | 68 |
| mar-override.spec.ts | 12 | 12 | 12 |
| Total | 317 | 316 pass, 1 fail | 316 pass, 1 fail |
| layout-matrix.spec.ts | 12 per layout project | 84 of 84 over 7 layout projects | |

Overall 632 of 634 for the two projects and 84 of 84 for the matrix (716 of 718 in the run). The two failures are the same test, "header at rest at 1440x900 ...", one per project, and they are the residual of defect 4 (1 level over the limit on the blue channel).

Repeat batch for defect 3 (phone-360, `--repeat-each=25`, the nine "lines at the bottom of the give sheet are in view" tests: five `give-failure` paths, `give-prn-lock`, `wristband-mismatch`, `code-mismatch`, `no-code-notice`; 225 runs, 4 workers, about 10 minutes):
- Batch 1: 224 passed, 1 error (`wristband-mismatch`). The error was `browserContext.close: ENOENT` on a trace file under `frontend/e2e/results/.playwright-artifacts-0/`, not an assertion. The Pharmacy session ran Playwright at the same time, and its run cleans the shared `results` folder. No in-view assertion failed.
- Batch 2 (rerun with `--output` in a scratch folder, so nothing is shared): **225 passed, 0 failed.**
- Before the fix about 1 in 25 failed (2 and 4 of 56 in two earlier batches). No `give-failure` run failed in either of the two batches now.

### Defects

Closed (confirmed by the rerun on 19 Nov):
1. **Close scrolled out of view after a wrong-patient STOP at 360x740: CLOSED.** The header (`.sheet-h`) is sticky. `mar.spec.ts` "after an error no toast covers the Close button" (phone-360) and F1/D8 (both projects) pass, and every refusal path at 360x740 now also checks that Close is on screen and the element hit at its centre.
2. **"Undone." held until both sheets close: CLOSED.** "an Undo answer with no give sheet open ..." passes on both projects; the two late-answer tests (give sheet open, then closed) still pass.

3. **`--sheet-foot-h` measured too small while the dialog is opening: CLOSED (19 Nov, angular `layout-height.ts`, measure on `animationend` and `transitionend`).** 225 of 225 on phone-360 in a clean repeat batch and no `give-failure` failure in the first batch either (see Counts).

Open (app code, not changed by QA; for the angular-developer, and a decision for the tech lead):
4. **Defect 4 (visual): mostly closed, one residual level.** The big step is gone: the header is opaque `--sheet-solid` (242,243,245) and the scrolled fields are fully hidden behind it (376 free header points all read 242,243,245 at 1440x480). But the test measures a 1-level miss at rest on the blue channel. Measured on both projects at 1440x900, same column (x 410, the header's and the body's left padding; the points are the bare `.sheet-b`, not a card):
   - header 242,243,245 (also 242,243,245 in the free stretch at x 819, y 74);
   - body 3 px under the header (y 118): 242,242,242, a difference of 3 on blue;
   - body 24 px under the header (y 139): 243,243,243, a difference of 2 (within the limit).
   The body is a neutral grey (242 to 244 on all channels; angular measured 244,244,244 far from the header), and `--sheet-solid` `#f2f3f5` is about 2 to 3 levels bluer. This is not visible to the eye. The test keeps the 2-level limit as the tech lead set it, and fails. Options: change `--sheet-solid` light to a neutral near `#f3f3f3` (the body is 242 to 244 and moves a little with what is behind the dialog), or the tech lead widens the limit to 3 with a reason. File `frontend/src/styles/_tokens.scss` line 24. Dark theme still not measured. Human visual sign-off stays open.

### Header at rest and over scrolled content (checked by screenshot, 19 Nov)

Tests "header at rest at 1440x900" and "header over scrolled content at 1440x480" keep PNGs of the sheet (attached to the report; a passing test's files are removed by the next run). The pixels are read in the test: it decodes a page screenshot with `node:zlib` (no library) and compares the colour in the header with the colour in the body in the same column. At rest the title, subtitle and Close are crisp and in the mockup order; the band is gone (header 242,243,245 against body 242 to 243, residual of 1 level, see defect 4); the footer buttons were not compared pixel by pixel with the mockup (the mockup give sheet cannot be opened by the existing tests). Scrolled (1440x480, scrollTop 40, the identity bar, name and allergy chip under the header): the header stays at the same place, is opaque (alpha 1), and the 376 sampled free points read 242,243,245 as at rest (worst difference 0); the title, subtitle and Close stay readable; the footer shows faint blurred text behind it, as the mockup's sticky footer does (the footer is still `--glass-hi` plus blur, not solid). The last failing run keeps its screenshots under the scratch folder of this run (`give-sheet-at-rest-1440-page.png`).
