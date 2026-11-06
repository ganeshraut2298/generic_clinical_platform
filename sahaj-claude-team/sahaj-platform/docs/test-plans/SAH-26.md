# Test plan SAH-26 · Bed transfer and bed transfer rules (IPD, part 5)

Story: `docs/stories/SAH-26.md`. Contract: `docs/api/ipd.yaml` (SAH-26 parts: `POST /admissions/{id}/transfer`, `GET` and `PUT /transfer-rules`, `BillLine.segment`, `AdmissionDetail.canTransfer/transferRules`, free beds from `GET /admission-options`). Design: `docs/database/SAH-26.md` (migration `V2026_11_17_1000__ipd_bed_transfer.sql`).

Integration tests (real `sahaj_test` database, MockMvc through the full filter chain, profile `test`), all in `backend/src/test/java/com/sahaj/platform/it/`:
- `BedTransferBillIT` (12): segment maths and the split bill, both bed-day cuts, both rules, backdated admissions.
- `BedTransferIT` (20): what a move writes, check order and messages, owner rule, demoted owner, input errors, roles, module state, tenant isolation, access log, audit and privacy, free-bed order.
- `BedTransferConcurrencyIT` (6): same bed at once, double submit, no deadlock, the real row lock of `FIND_FOR_UPDATE`.
- `TransferRulesIT` (10): `GET` / `PUT /transfer-rules`.
- `BedTransferTableIT` (7): `ipd.bed_transfer` by SQL (RLS, composite FKs, append-only, grants, checks), bed history.
- `IndicJoinerTextIT` (3): ZWJ / ZWNJ accepted, ZWSP / BOM and the other invisible characters refused.
- `BedTransferSupport` (shared set-up and the independent expected-bill helpers; not a test).

Playwright e2e: `frontend/e2e/bed-transfer.spec.ts` (22 cases, run on both projects = 44), API mocked in the browser as in `app-shell.spec.ts`.

## How the bill days above 1 are tested (admission time moved)
As in SAH-25: `IpdBillingSupport.backdate` runs `ALTER TABLE ipd.admission NO FORCE ROW LEVEL SECURITY`, a one-row `UPDATE admitted_at`, `FORCE` again, in ONE committed transaction as `sahaj_migrator`. A transfer is made on "bill day n" by backdating before the move. A later transfer is made by moving the admission further into the past between two moves; the segment days of the first move stay what they were (they are stored). Tests that depend on the IST day call `assumeTrue(sameIstDay)` after reading and before asserting (a vanishing window, never a failure). The 24-hour cut uses `now - (n x 24 h + 30 min)`.

The expected bill lines are written in the test (`BedTransferSupport.before/current/nursing/assertBill`) from the story and the default rates (General ₹800, Semi-private ₹1,500, Private ₹2,500, ICU ₹5,000; visit ₹500 x multiplier 1, 1.25, 1.5, 2; nursing ₹300), not taken from the application. `assertBill` checks the line names in order, unit x quantity, the `segment` marker and the total.

## Coverage: acceptance criterion to test

| AC | Case | Test |
| --- | --- | --- |
| 1 | `canTransfer` true for OWNER, FRONT_DESK, NURSE while admitted; DOCTOR false (can read); false for a discharged admission; false in Read-only; the three roles read `/admission-options`, the other five get 403 | BedTransferIT.canTransferIsFalseWhenDischargedOrReadOnlyAndTheFreeBedOptionsStayForTheThreeRolesOnly; ownerFrontDeskAndNurseMayMoveEveryOtherRoleGets403AndAnonymousGets401 |
| 1 | Row visibility in the browser: shown for OWNER, FRONT_DESK, NURSE; absent for DOCTOR and in Read-only, and the free beds are not even requested | e2e: `<role>: the Move to another bed row is shown...` (3), `DOCTOR: no Move row...`, `IPD read-only: no Move row...` |
| 1 | Option label "<code> · <category> · ₹<rate>", ordered General, Semi-private, Private, ICU (then code) | e2e: the free beds are listed "<code> · <category> · <rate>" ordered... |
| 1 | Server order of the free beds: category, then room creation order (rooms 101, 201, 102), then code, beds without a room last; excludes Occupied, Cleaning, Blocked, Reserved; fields id, code, wardName, categoryKey, categoryName, ratePaise; rate follows Clinic setup | BedTransferIT.theFreeBedsAreOrderedByCategoryThenRoomCreationOrderThenCodeWithBedsWithoutARoomLast |
| 1 | Inside a category the screen keeps the server's room order | e2e: inside a category the beds keep the room order the server sends (FAILS, finding F2) |
| 1 | "No free bed" single option, select and Move disabled | e2e: with no free bed the select shows "No free bed" and Move is disabled |
| 1 | After a move the old bed (Cleaning) is not offered; after "Cleaned · ready" it is again | BedTransferIT.theOldBedStaysOutOfTheOptionsUntilItIsCleanedAndThenThePatientCanMoveBack |
| 2 | Rule Owner approves (default): FRONT_DESK and NURSE to Semi-private / Private / ICU from General get 403 `owner-approval-needed` with the exact text "Moving to <category> needs the owner's approval. Ask the clinic owner."; nothing changed; other roles get a plain 403; OWNER moves | BedTransferIT.withOwnerApprovesOnlyTheOwnerMovesToAHigherCategoryAndOthersGetTheExactMessage |
| 2 | Same and lower category always allowed to the three roles with the rule on; a higher one is still refused | BedTransferIT.theOwnerRuleOnlyAppliesToAHigherCategorySameOrLowerAlwaysWorksForTheThreeRoles |
| 2 | Rule Front desk can do it: OWNER, FRONT_DESK, NURSE all move to ICU; switching back applies at once | BedTransferIT.withFrontDeskCanDoItOwnerFrontDeskAndNurseMayAllMoveToAHigherCategory |
| 2 | An OWNER token of a person demoted a moment ago is refused when the rule applies (403, nothing changed) and cannot change the rules; same-category move still works; the real owner works; with the rule off the demoted person may upgrade | BedTransferIT.anOwnerDemotedAMomentAgoIsRefusedTheOwnerOnlyActionsEvenWithTheOldOwnerToken |
| 3 | Order 404 admission, 409 `not-admitted` (exact text), 400 same bed (field `toBedId`, exact text, even though that bed is Occupied), 404 bed (unknown and another clinic's); discharged beats every bed check | BedTransferIT.checksRunInOrderAdmission404ThenNotAdmitted409ThenSameBed400ThenBed404 |
| 3 | 409 `bed-not-free` ("That bed is no longer free. Pick another.") for Occupied, Cleaning, Blocked, Reserved, for all three roles; nothing changes | BedTransferIT.aBedThatIsNotAvailableIs409BedNotFreeForOccupiedCleaningBlockedAndReserved |
| 3 | `bed-not-free` is answered before the owner rule (a nurse to an occupied Private bed gets 409, not 403) | BedTransferIT.aBedThatIsNotFreeIs409BeforeTheOwnerRuleIsLookedAt |
| 3 | One transaction: admission `bed_id` and `version` +1, old bed `clean` (version +1), new bed `occ` (version +1), one `ipd.bed_transfer` row with ids, categories, segment 0, rule, null reason, actor, time, one audit; answer is the full sheet with the new bed, category, ward; list row and board tiles follow | BedTransferIT.aMoveWritesTheAdmissionTheTwoBedsTheTransferRowAndTheAnswerInOneStep |
| 3 | Reason: the four values are stored, null when absent; "", "Fever", other case, padded, 5000 chars, NUL are 400 `reason`; table holds only the four values | BedTransferIT.theOptionalReasonIsStoredOnlyWhenItIsOneOfTheFourListedValues |
| 3 | Missing / null toBedId 400 "Choose a bed"; malformed UUID, number, `[]`, bad JSON, no body, `null` body, bad path id 400; nothing written; input is checked before the admission lookup (as for charges in SAH-25) | BedTransferIT.invalidBodiesGet400WithProblemDetailsAndNothingIsWritten |
| 3 | Extra JSON (`tenantId`, `segmentDays`, categories, `transferredBy`, `fromBedId`) ignored; the server decides | BedTransferIT.extraJsonFieldsAreIgnoredAndTheServerChoosesTheClinicTheActorAndTheCategories |
| 4 | General to Private on day 3: Same day General 2 days / Private from day 3; Next day General 3 days, no Private line yet; line names and order | BedTransferBillIT.movedGeneralToPrivateOnDayThreeGivesGeneralTwoDaysSameDayAndThreeDaysNextDay |
| 4 | Moved on day 1: Same day bills the whole first day at the new category, segment 0, no segment lines; Next day keeps day 1 on the old category and the new rate starts on day 2 | BedTransferBillIT.movedOnDayOneSameDayBillsTheWholeFirstDayAtTheNewCategoryAndNextDayKeepsItOnTheOldOne |
| 4 | Formula over both cuts (midnight, 24 h) x both rules x days 1, 2, 3, 5 x upgrade and downgrade (General to Private, Private to General); stored `segment_days` | BedTransferBillIT.segmentDaysFollowTheFormulaForBothCutsBothRulesAndAllCategoryDirections |
| 4 | Second move on the same day: Same/Same segments 2 then 0; Next/Next 3 then 0 (c = max(0, n - p)); Same then Next 2 then 1; the rule used is stored per move | BedTransferBillIT.aSecondMoveOnTheSameDayFixesOnlyTheDaysNotYetMovedToEarlierSegments |
| 4 | Later moves with earlier segments: day 3, day 6, day 8 (segments 2, 3, 3), ICU first day on day 9; every line and the total | BedTransferBillIT.aLaterMoveWithEarlierSegmentsTakesOnlyTheDaysSpentOnTheCurrentBed |
| 4 | Move back to the first bed after it is cleaned is a new transfer with its own segment | BedTransferBillIT.movingBackToTheFirstBedAfterItIsCleanedIsAnotherTransferWithItsOwnSegment |
| 5 | Exact lines and order: segment pairs per transfer first (`segment` = true), then current bed and doctor visits, then nursing, then extras; zero-quantity lines dropped | BedTransferBillIT (all of the above use `assertBill`) |
| 5 | Nursing is one line for all bill days, not split | BedTransferBillIT.nursingCareIsOneLineForAllBillDaysNotSplitByCategory |
| 5 | Old category's visit charge = visit x that category's multiplier, half-up to whole paise (50003 x 1.5 = 75005; x 1.25 = 62504; 50002 x 1.25 = 62503) x segment days | BedTransferBillIT.theOldCategorysVisitChargeIsRoundedHalfUpToWholePaiseAfterTheMultiplier |
| 5 | Extra charges re-priced at the current category's multiplier (OQ-26-1), rows keep the base price, lines after nursing | BedTransferBillIT.extraChargesAreRepricedAtTheCurrentCategoryAndComeAfterNursing |
| 5 | Rates "currently set" price segment lines on the next read (bed rate, multiplier, nursing, visit) | BedTransferBillIT.ratesCurrentlySetInClinicSetupPriceEverySegmentLineOnTheNextRead |
| 5 | List, board tile, sheet, Deposit low pill and Deposit alerts tile follow the transfer (low to not low and not low to low); day number unchanged; old bed tile Cleaning with no flag; all four reader roles agree | BedTransferBillIT.theSheetTheListTheBoardTileAndDepositLowAllFollowTheTransfer |
| 5 | Insurance patient never Deposit low after a move to ICU | BedTransferBillIT.anInsurancePatientStaysNeverDepositLowAfterAMoveToAnExpensiveBed |
| 6 | Summary strip shows the new bed ("Private · Private ward"), toast "<name> moved from 201-A to 301-A · Private rate from today · 201-A sent for cleaning", bill split lines with exact paise, free beds read again, list shows the new bed after closing | e2e: a move posts only the bed, shows the new bed, the split bill and the toast, and the list follows |
| 6 | "from tomorrow" when the rule is Next day | e2e: the toast says "from tomorrow"... |
| 6 | Board: old bed Cleaning, new bed Occupied with the patient | BedTransferIT.aMoveWritesTheAdmission... (board part) |
| 7 | `GET /transfer-rules` defaults (true, same) with no stored row, exactly two fields, no row created by reading; same values on the sheet | TransferRulesIT.aClinicThatNeverSavedGetsOwnerApprovesAndSameDayAndNoRowIsCreatedByReading |
| 7 | Each choice saved at once, read back by OWNER, FRONT_DESK, NURSE, DOCTOR and on the sheet; columns hold the values | TransferRulesIT.eachChoiceIsSavedAtOnceAndReadBackByEveryReaderAndOnTheSheet |
| 7 | Unchanged `PUT` is a no-op: no row, no audit, `updated_at` unchanged | TransferRulesIT.savingTheValuesTheClinicAlreadyHasIsANoOpWithNoRowAndNoAudit |
| 7 | The lazy `ipd.config` row takes the defaults for every other setting, a rules save keeps room level, bed-day cut, nursing, visit and deposit alert, and the reverse | TransferRulesIT.theLazyConfigRowTakesTheDefaultsOfEveryOtherSettingAndASaveNeverChangesThem |
| 7 | Only OWNER changes (FRONT_DESK, NURSE, DOCTOR and the four other roles 403), 4 roles read, 4 get 403, anonymous 401; nothing written | TransferRulesIT.fourRolesReadFourRolesAreRefusedAndOnlyTheOwnerChangesAnonymousGets401 |
| 7 | Invalid bodies 400 with field `upgradeNeedsOwner` / `newRateFrom` and exact messages (empty, null, "later", "SAME", padded, wrong types, bad JSON, no body, `null`); nothing saved | TransferRulesIT.invalidBodiesGet400WithAFieldErrorAndNothingIsSaved |
| 7 | Extra fields (tenantId, bedDayCut, nursingPaise) ignored | TransferRulesIT.extraFieldsInTheBodyAreIgnored |
| 7 | Read-only: `PUT` is 409 `ipd-readonly` before the body is read (valid, unchanged, empty, invalid), `GET` still works | TransferRulesIT.readOnlyIpdStillReadsButRefusesTheChangeBeforeLookingAtTheBody |
| 7 | Module off: `GET` and `PUT` 403 `module-off`, no row, no audit | TransferRulesIT.ipdOffBothVerbsAreModuleOffForTheOwnerAndTheReadForEveryReader |
| 7 | Panel in Clinic setup: heading, hints, defaults checked | e2e: the panel shows the defaults: Owner approves and Same day, with their hints |
| 7 | Owner changes each choice, saved at once with both values, same value sends nothing, values return after a reload | e2e: the owner changes each choice and each is saved at once with both values |
| 7 | NURSE, FRONT_DESK, DOCTOR see the saved rules read-only, notice text, radios disabled, a forced click sends nothing; Read-only IPD locks the owner too | e2e: `<role> sees the saved rules read-only...` (3), `IPD read-only: even the owner sees the choices locked` |
| 7 | Refused save (409 `ipd-readonly`) shows the message and the choice goes back; load failure shows an alert, Try again recovers | e2e: error: a refused save...; error: the rules cannot be loaded... |
| 8 | Two patients to the same free bed (4 rounds): exactly one 200, one 409 `bed-not-free`, loser untouched, one transfer row and audit per round | BedTransferConcurrencyIT.twoPatientsMovedToTheSameFreeBedAtOnceGiveExactlyOne200AndOne409BedNotFree |
| 8 | Four at once: one 200, three 409; one admission in the bed, one Cleaning bed | BedTransferConcurrencyIT.fourPatientsMovedToTheSameBedAtOnceGiveOne200AndThree409 |
| 8 | Double submit of the same move: one success, one transfer row, no 500. The second answer is 400 on `toBedId`, not 409 (finding F1) | BedTransferConcurrencyIT.aDoubleSubmitOfTheSameMoveSucceedsOnceAndTheSecondIsRefusedWithNothingWrittenTwice |
| 8 | Two patients into each other's beds (8 rounds): every answer 409, no deadlock; one to a free bed while the other asks for the first one's bed: 200 and 409 | BedTransferConcurrencyIT.twoPatientsMovingIntoEachOthersBedsNeverDeadlockAndEveryAnswerIsA409 |
| 8 | The real `select ... for update of a` (FIND_FOR_UPDATE, only unit-mocked before): a session holding the admission row makes the move wait (seen in `pg_stat_activity`: a `Lock` wait on a statement containing `for update of a`); when the holder discharges and commits the move re-reads and answers 409 `not-admitted`, nothing written | BedTransferConcurrencyIT.theMoveWaitsOnTheAdmissionRowLockAndRechecksTheStateAfterTheLockIsReleased |
| 8 | If the holder only rolls back, the waiting move goes through (200) | BedTransferConcurrencyIT.whenTheOtherSessionOnlyReleasesTheLockTheWaitingMoveGoesThrough |
| 8 | A patient moved more than once: each move is a new transfer (also back to the first bed) | BedTransferIT.theOldBedStaysOutOfTheOptions...; BedTransferBillIT.movingBackToTheFirstBed... |
| 9 | Roles: OWNER, FRONT_DESK, NURSE 200 (mobile in the answer for OWNER and FRONT_DESK only); DOCTOR, VISITING_DOCTOR, BILLING, PHARMACIST, COUNSELLOR 403 (also for bad input); anonymous 401; rows and audits equal successes | BedTransferIT.ownerFrontDeskAndNurseMayMoveEveryOtherRoleGets403AndAnonymousGets401 |
| 9 | Module Off: transfer, `GET` and `PUT /transfer-rules` 403 `module-off`; nothing written | BedTransferIT.ipdOffTheMoveAndBothRulesEndpointsAre403ModuleOffAndNothingIsWritten |
| 9 | Read-only: transfer 409 `ipd-readonly` before every other check (valid, bad reason, empty body, unknown admission, unknown bed), exact text, three roles; reads still work | BedTransferIT.readOnlyIpdRefusesTheMoveBeforeAnyOtherCheckAndNothingChanges |
| 9 | Tenant isolation: other clinic's admission 404 (move and GET), own patient to the other clinic's bed 404, no rows / audit seen, RLS with no tenant bound = 0 rows, rules separate, options only own beds | BedTransferIT.anotherClinicCannotSeeMoveOrTargetThisClinicsAdmissionsBedsAndTransfers |
| 9 | RLS by SQL with no tenant bound; composite FKs stop a row pointing at another clinic's admission or either bed (constraint names asserted); `tenant_id` insert denied; positive control | BedTransferTableIT.rowsAreInvisibleWithoutATenantAndToAnotherClinicAndCannotPointAtAnotherClinicsAdmissionOrBeds |
| 9 | Append-only for `sahaj_app`: UPDATE (any column), DELETE, TRUNCATE denied; `id` and `transferred_at` not insertable; positive control | BedTransferTableIT.sahajAppCannotUpdateDeleteOrTruncateAndCannotWriteIdTenantOrTime |
| 9 | Append-only for the table owner (`sahaj_migrator`): TRUNCATE, UPDATE, DELETE stopped by triggers; table FORCE RLS; policies are SELECT and INSERT only; two triggers | BedTransferTableIT.theTableOwnerIsStoppedByTheTriggersAndTheTableStaysForcedAndWithoutUpdateOrDeletePolicies |
| 9 | Grants: select + exactly nine insert columns, no update/delete/truncate/references/trigger, none for PUBLIC; `ipd.admission.bed_id` updatable, identity and billing columns not | BedTransferTableIT.theGrantsAreExactlySelectAndTheNineInsertColumnsAndTheAdmissionBedColumnIsTheOnlyNewUpdate |
| 9 | Checks: `segment_days >= 0`, from and to category keys, `new_rate_from`, `reason` (only the four values, no case or space variants), `from <> to`, not-null columns; every allowed value accepted | BedTransferTableIT.theChecksRefuseBadDaysCategoriesRulesReasonsAndTheSameBedTwice |
| 9 | `ipd.config` checks (`new_rate_from`, not null) and database defaults (`same`, `true`) | BedTransferTableIT.theConfigColumnsRefuseValuesOutsideTheTwoRuleChoices |
| 9 | A bed with a transfer history cannot be deleted even when empty (API 409, SQL FK violation); a never-used bed still can | BedTransferTableIT.aBedThatHasATransferHistoryCannotBeDeletedNotEvenWhenItIsEmptyAndTheAnswerIsA409NotA500 |
| 9 | Audit `IPD_BED_TRANSFER`: entity `IPD_BED_TRANSFER`, entity id = transfer id, no before image, exactly the 12 keys (admission id, IP number, from/to bed id and code, from/to category, bill days, segment days, rule values), actor; `IPD_TRANSFER_RULES_UPDATED`: entity `IPD_CONFIG`, before and after values; no name, mobile, "Zubin", admission reason or transfer reason in any audit row or in the captured log | BedTransferIT.theAuditRowsCarryIdsCodesCategoriesDaysAndRulesAndNeverTheNameTheMobileOrAnyReason |
| 9 (security L2) | One `patient.record_access` row per successful move (the answer is the full sheet); none for 403, 404, 400, list, options or rules | BedTransferIT.aSuccessfulMoveWritesOnePatientAccessRowAndRefusedMovesWriteNone |
| 9 | Money stays integer paise: every bill line `amount = unit x qty` and the total is the sum | `assertBill` / `assertBillAddsUp` in every bill test |
| 10 | Move disabled while the request runs, a double click sends one request | e2e: Move is disabled while the request runs and a double click sends one request |
| 10 | 403 `owner-approval-needed`: server message shown, sheet unchanged, sheet re-read, Move usable again | e2e: error: 403 owner-approval-needed... |
| 10 | 409 `bed-not-free`: message shown, free beds re-read without the taken bed | e2e: error: 409 bed-not-free... |
| 10 | 409 `ipd-readonly` and a network failure show a message, sheet unchanged, Move enabled again | e2e: error: 409 ipd-readonly and a network failure... |
| tech lead decision | Devanagari conjuncts: ZWJ (U+200D) and ZWNJ (U+200C) accepted in the admission reason (stored character for character, shown back) and in a ward name; U+200B, U+FEFF, U+200E, U+200F, U+2060, U+202E, U+2066, U+2028, NUL, U+0085 still 400 (front, middle, end; also next to a joiner) | IndicJoinerTextIT (3 tests) |

## Findings for the owners (not fixed here)
- **F1 (low, java-developer or BA wording).** AC 8 says a double click on **Move** gives the second request 409 `bed-not-free`. The server serialises the two requests behind the clinic IPD lock, so the second finds the patient already in the bed and answers the same-bed check first: **400, field `toBedId`, "The patient is already in this bed. Pick another."** (seen 3 of 3 rounds). State is correct (one transfer row, one audit, no 500). The test accepts either answer and pins the state. Decide: change AC 8, or have the server answer 409 `bed-not-free` for "target is the current bed of this same admission after a concurrent move".
- **F2 (low, angular-developer).** `sortFreeBeds` (`frontend/src/app/features/ipd/ipd.model.ts`) re-sorts the options by category then code, which throws away the room order the server sends (AC 1: category, then room, then code). With rooms created 101, 201, 102 the screen lists 102-B before 201-A. The e2e case `inside a category the beds keep the room order the server sends` fails on both projects until the sort keeps the server order inside a category (sorting by category only, stable, is enough).
- **F3 (info).** Input errors (missing `toBedId`, bad `reason`) are answered 400 before the admission is looked up, so for an unknown admission with an invalid body the answer is 400, not 404 (AC 3 lists the admission check first). Same precedent as SAH-25 (`serviceKey`). Pinned by `invalidBodiesGet400...`. No change asked unless the BA wants the AC order literally.

## Notes and gaps
- `IpdBillingSupport.backdate` takes an ACCESS EXCLUSIVE lock on `ipd.admission` for the length of its transaction. Do not run IPD ITs in parallel in one JVM or against a shared `sahaj_test` from two runs.
- The "later" transfers are simulated by backdating again (time cannot move forward); the stored segment of the first move is what is asserted, plus the bill after the second move.
- Not tested because not built (story scope): the card Transfer sheet and its reason list in the UI, nurse handover, an approval queue, a transfer history screen, package admissions (OQ-26-4), Reserved beds as targets (a Reserved target is refused: tested).
- The Angular unit tests are owned by the angular-developer. The "no query per row" property of the batched transfer load is not asserted (shared test database; same decision as SAH-25); `theSheetTheListTheBoardTile...` puts two admissions with different transfers through list and board at once and checks each gets its own bill.
- Existing SAH-25 audit privacy test also checks the log; here the transfer reason ("Isolation") is added to the secrets that must not appear in audit rows or the log.

## Browser tests (Playwright, `frontend/e2e/bed-transfer.spec.ts`, API mocked in the browser)
Run: `npm --prefix frontend run build`, then in `frontend/e2e`: `npx playwright test bed-transfer.spec.ts` (both projects, phone 360 and desktop 1440). A call that is not mocked fails the test (`unmocked` list, as in `app-shell.spec.ts`).

| Case | Test |
| --- | --- |
| Visibility matrix | `<role>: the Move to another bed row is shown...` (OWNER, FRONT_DESK, NURSE), `DOCTOR: no Move row...`, `IPD read-only: no Move row...` |
| Label, category order | the free beds are listed "<code> · <category> · <rate>" ordered General, Semi-private, Private, ICU |
| Room order kept (FAILS, F2) | inside a category the beds keep the room order the server sends |
| No free bed | with no free bed the select shows "No free bed" and Move is disabled |
| Success, toast, bill, list | a move posts only the bed, shows the new bed, the split bill and the toast, and the list follows; the toast says "from tomorrow" |
| Disabled while running | Move is disabled while the request runs and a double click sends one request |
| 403, 409, network | error: 403 owner-approval-needed...; error: 409 bed-not-free...; error: 409 ipd-readonly and a network failure... |
| Rules panel | defaults and hints; owner changes (saved at once, both values); NURSE / FRONT_DESK / DOCTOR read-only; Read-only IPD locked; refused save reverts; load failure with Try again |

## Last run (5 Nov 2026)
- `node scripts/validate.mjs backend`: passed. Unit and slice tests 1,241 passed; integration tests 699 completed, 0 failures, 0 errors, 1 skipped (not a SAH-26 test); the 58 SAH-26 integration tests: 58 passed, 0 skipped.
- Frontend: `npm run lint` passed, `npm test` 98 files / 794 tests passed, `npm run build` passed. (`node scripts/validate.mjs frontend` itself stops at the test step: `ng test --browsers=ChromeHeadless` needs a `@vitest/browser-*` package that the vitest-based scaffold does not have; the command in `scripts/validate.mjs` is human-owned.)
- Playwright, all three spec files: 142 passed, 8 skipped (pre-existing desktop-only / shell-at-1280 skips), 2 failed (finding F2, `bed-transfer.spec.ts` room order, one per project).
