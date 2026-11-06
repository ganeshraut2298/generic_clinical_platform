# Test plan SAH-25 · Running bill, extra charges and deposit alerts (IPD, part 4)

Story: `docs/stories/SAH-25.md`. Contract: `docs/api/ipd.yaml` (SAH-25 parts: `AdmissionRow.runningBillPaise/depositLow`, `AdmissionList.depositAlerts/alertPercent`, `BoardBed.depositLow`, `AdmissionDetail.bill/canAddCharge`, `GET /charge-services`, `POST /admissions/{id}/charges`). Design: `docs/database/SAH-25.md` (migration `V2026_11_14_1000__ipd_admission_charge.sql`).

Integration tests (real `sahaj_test` database, MockMvc through the full filter chain, profile `test`):
- `backend/src/test/java/com/sahaj/platform/it/RunningBillIT.java` (bill math, bill days, Deposit low, list, tiles, board, detail).
- `backend/src/test/java/com/sahaj/platform/it/AdmissionChargesIT.java` (extra charges, errors, roles, append-only table, audit and privacy, RLS, review-fix regressions).
- `backend/src/test/java/com/sahaj/platform/it/IpdBillingSupport.java` (shared set-up, not a test).

Playwright e2e: `frontend/e2e/app-shell.spec.ts` (API mocked in the browser): paise display (₹187.50, 3 × ₹562.50, ₹1,687.50), computed-style check that `.p-crit`, `.p-blue`, `.p-good` and `.notice.crit` are painted, add-charge happy and error path. Angular unit tests are owned by the angular-developer.

## How the bill days above 1 are tested (admission time moved)
`ipd.admission.admitted_at` has no UPDATE grant for `sahaj_app` and the application clock cannot move. `IpdBillingSupport.backdate` connects as `sahaj_migrator` (the table owner; the table is FORCE row level security, so the owner sees no rows) and runs, in ONE committed transaction: `ALTER TABLE ipd.admission NO FORCE ROW LEVEL SECURITY`, a single-row `UPDATE ... WHERE id AND tenant_id`, `ALTER TABLE ... FORCE ROW LEVEL SECURITY`. DDL is transactional in PostgreSQL, so other sessions never see the table unforced and a crash rolls the change back (a `lock_timeout` of 30 s protects against a long wait). The IST-midnight tests use `Assumptions.assumeTrue` to skip if IST midnight passes while the test runs (a vanishing window, never a failure). The 24-hour tests keep a margin of one minute on each side of every period boundary.

## Coverage

| AC | Case | Test |
| --- | --- | --- |
| 1 | Fresh General admission: lines Bed · General 80000, Doctor visits · Dr Asha 50000, Nursing care 30000 = 160000 paise (₹1,600); day 1; midnight cut; multiplier 1.00; paid, deposit percent 16, alert 80; list row 160000 | RunningBillIT.freshGeneralAdmissionBillsOneDayOfBedVisitAndNursingForExactly1600Rupees |
| 1 | Semi 1.25 (visit 62500, total 242500), Private 1.5 (75000, 355000), ICU 2.0 (100000, 630000); category name, multiplier, list total | RunningBillIT.everyCategoryBillsItsOwnBedRateAndTheVisitChargeTimesTheMultiplier |
| 1, 8 | Visit x multiplier rounded half-up: 50002 x 1.25 = 62503 (half-even 62502); 50003 x 1.5 = 75005; 50003 x 1.25 = 62504 | RunningBillIT.theVisitChargeIsRoundedHalfUpToWholePaiseAfterTheMultiplier |
| 1 | Rates "currently set": nursing, visit, bed rate and multiplier changes change the next read; nothing stored | RunningBillIT.ratesSetInClinicSetupDriveTheBillOnTheNextReadWithNothingStored |
| 2 | Midnight cut: IST midnight exactly / 1 s after = day 1; 1 s and 1 ms before = day 2; yesterday midnight = 2; before it = 3; 3 days ago = 4; 10 days ago = 11; lines qty, totals, list and detail dayNumber | RunningBillIT.midnightCutCountsIstCalendarDaysInclusiveAroundMidnightAndOverSeveralDays |
| 2, 5 | Board tile day number and bill days agree on day 5 | RunningBillIT.theBoardTileDayNumberAndTheBillAgreeAfterBackdating |
| 2 | 24 h cut: 1 h, 23h59, 24h01, 47h59 = 1; 48h01, 71h59 = 2; 72h01 = 3; 10 days = 10; at least 1 | RunningBillIT.every24HoursCutCountsCompletedPeriodsAtLeastOneWithAMinuteOfMarginOnEachSide |
| 2 | Same admission 23:59 IST yesterday: 2 days at midnight, 1 day every 24 h, back to 2 | RunningBillIT.sameAdmissionGivesTwoDaysAtMidnightButOneDayEvery24HoursWhenAdmittedJustBeforeMidnight |
| 2 | Boundary: admission time in the future still bills 1 day (both cuts) | RunningBillIT.anAdmissionDatedInTheFutureStillBillsOneDay |
| 3 | Deposit low boundary (bill ₹1,600, alert 80): no deposit low; ₹1,999 low; ₹2,000 low (equal); ₹2,001 not low; ₹50,000 not low; list and detail agree; depositPercent half-up; null with no deposit; tile count 2 | RunningBillIT.depositLowIsTrueAtExactlyTheAlertShareAndFalseOnePaiseRupeeAbove |
| 3 | Changing alertPercent via rules (80, 85, 50, 55, 100, 75) changes the flag, the list `alertPercent`, the `depositAlerts` count and `bill.alertPercent` (exact equality at 80 and 50) | RunningBillIT.theAlertPercentOfAdmissionAndDepositsChangesTheFlagAndTheTile |
| 3 | Adding a charge makes the patient low; adding a deposit clears it | RunningBillIT.addingAChargeOrADepositFlipsDepositLowBothWays |
| 3 | Insurance and Corporate never low (no deposit, huge bill, 30 days, ICU) in detail, list, tile count and board | RunningBillIT.insuranceAndCorporatePatientsAreNeverDepositLowEvenWithNoDepositAndAHugeBill |
| 3, 4 | List fields `runningBillPaise`, `depositLow`; list-level `depositAlerts`, `alertPercent` for all four reader roles; a discharged patient leaves the count | RunningBillIT.theListCarriesRunningBillDepositLowAndTheTileCountsOnlyDepositLowPatients |
| 4 | Empty clinic: 0 alerts, default 80, changed value shown | RunningBillIT.anEmptyClinicHasZeroAlertsAndTheDefaultAlertPercent |
| 5 | Board occupied tile `depositLow` true / false / false (Insurance); free and cleaning beds have none; four reader roles; clears after a deposit | RunningBillIT.boardTilesShowDepositLowTrueFalseForOccupiedBedsAndNothingForOtherBeds |
| 3, 5 | List, board and detail agree (backdated 3 and 21 days, six charges) | RunningBillIT.listAndBoardAgreeOnWhoIsDepositLowAfterBackdatingAndExtraCharges |
| 6 | Detail bill block fields (billDays, bedDayCut, categoryName, multiplier, lines, totalPaise, paidPaise, depositPercent, alertPercent); `canAddCharge` OWNER/FRONT_DESK/NURSE true, DOCTOR false, read-only false; 4 non-readers 403 | RunningBillIT.detailBillBlockHasEveryFieldAndCanAddChargeFollowsRoleAndState |
| 6, 7 | Discharged admission: bill still shown, canAddCharge and canCollectDeposit false | RunningBillIT.aDischargedAdmissionStillShowsItsBillButCannotTakeCharges |
| 7 | `GET /charge-services`: ten services, exact keys, names, base prices, in order; 4 roles OK, 4 roles 403, 401 | RunningBillIT.chargeServicesAreTheTenOfTheMockupWithTheirExactNamesAndBasePrices |
| 7 | Service list shows base prices whatever the multiplier | RunningBillIT.chargeServicesAreTheSameWhateverTheCategoryMultiplierIs |
| 7 | Each of the ten services adds one grouped line at base price (General), totals accumulate, rows hold key, name, base price, qty 1, adder | AdmissionChargesIT.eachOfTheTenServicesAddsOneGroupedLineAtItsBasePriceOnAGeneralBed |
| 1, 7 | Unit = base x multiplier for all ten services on Semi/Private/ICU; row keeps base price; a rate change later changes the line (1.5, 0.5) | AdmissionChargesIT.theCategoryMultiplierScalesEachExtraChargeUnitButTheRowKeepsTheBasePrice |
| 1, 7 | Quantity accumulates in one line per service in order of first charge; one row per add; "Extra doctor visit" is separate from the daily doctor visits | AdmissionChargesIT.quantityAccumulatesInOneLinePerServiceInOrderOfTheFirstCharge |
| 1 | One admission's charges never appear on another's bill | AdmissionChargesIT.chargesOfOneAdmissionNeverAppearOnAnotherAdmissionInTheSameClinic |
| 1, 2 | Extra charges are not multiplied by bill days (3 days + one IV fluid) | AdmissionChargesIT.chargesAreBilledEveryDayNotMultipliedByBillDays |
| 7 | 50 charges of one service accepted (qty 50, ₹15,000); 51st = 400 `serviceKey`, exact message, nothing written, no audit; other service and other admission still fine | AdmissionChargesIT.theFiftyFirstChargeOfTheSameServiceIs400AndNothingIsWrittenButOtherServicesAndAdmissionsStillWork |
| 7 | Race: 4 parallel adds with 2 slots left = exactly 2 x 200 and 2 x 400; 50 rows | AdmissionChargesIT.fourParallelAddsWithTwoSlotsLeftGiveExactlyTwoOkAndTwo400 |
| 7 | Unknown, empty, blank, wrong-case, padded, injection-like, null, missing, 300-char keys = 400 with `fieldErrors[0].field = serviceKey` and the exact message; bad JSON, no body, bad UUID 400; nothing written | AdmissionChargesIT.unknownMissingOrMalformedServiceKeysGet400WithAFieldErrorAndWriteNothing |
| 7 | JSON `null` body = 400, not 500 | AdmissionChargesIT.aJsonNullBodyIs400NotA500 |
| 7 | Check order: invalid key on unknown admission = 400; valid key on unknown admission = 404 | AdmissionChargesIT.anInvalidKeyOnAnUnknownAdmissionIs400AndAValidKeyOnAnUnknownAdmissionIs404 |
| 7 | Discharged (committed SQL) = 409 `not-admitted` with the exact message for the three roles; nothing written; invalid key still 400 first | AdmissionChargesIT.aDischargedAdmissionRefusesChargesWith409NotAdmittedAndTheExactMessage |
| 7 | IPD read-only = 409 `ipd-readonly` before every other check (valid or invalid key, known or unknown admission), reads still work | AdmissionChargesIT.readonlyAddingACharge409IpdReadonlyBeforeAnyOtherCheckAndNothingChanges |
| 7 | IPD off = 403 `module-off` on both endpoints; nothing written | AdmissionChargesIT.ipdOffBothNewEndpointsAre403ModuleOffForEveryRoleAndNothingIsWritten |
| 8 | Roles: OWNER, FRONT_DESK, NURSE 200 (mobile shown to owner and front desk only); DOCTOR, VISITING_DOCTOR, BILLING, PHARMACIST, COUNSELLOR 403; anonymous 401; rows and audits equal successes | AdmissionChargesIT.ownerFrontDeskAndNurseMayAddChargesEveryOtherRoleGets403AndAnonymousGets401 |
| 8 | Tenant isolation: B gets 404 on A's admission (POST and GET); B sees 0 charge rows by id, by SQL and with no tenant bound; no audit seen; B's bill and list unaffected | AdmissionChargesIT.anotherClinicCannotSeeOrAddChargesToAnAdmissionOfThisClinic |
| 8 | Composite FK: B's session cannot insert a charge for A's admission; `tenant_id` not insertable; no tenant bound refused; positive control | AdmissionChargesIT.aChargeRowCannotPointAtAnotherClinicsAdmissionOrCarryAnotherTenantId |
| 7 | Append-only: `sahaj_app` UPDATE, DELETE, TRUNCATE denied; explicit `id` / `added_at` insert denied; positive control; check constraints (key, name, unit, qty 0 and 51); owner (`sahaj_migrator`) TRUNCATE, UPDATE and DELETE stopped by triggers; table still FORCE RLS | AdmissionChargesIT.chargeTableIsAppendOnlyForSahajAppAndItsOwnerAndOnlyTheListedColumnsAreInsertable |
| 8 | Audit `IPD_CHARGE_ADDED`: entity `IPD_CHARGE`, entity id = charge id, no before image, after has admission id, service key, base price (not the multiplied price), actor; no name, mobile or reason in any audit row or in the captured log | AdmissionChargesIT.auditCarriesIdsServiceKeyAndBasePriceOnlyAndNoNameMobileOrReasonIsAuditedOrLogged |
| 8 (security L2) | `patient.record_access`: exactly one row per successful charge POST (it returns the full sheet) and one per detail GET; none for list, board, charge-services or for a refused POST (403, 400). Deposit POST: one row (AdmissionDepositsIT, access-log test) | AdmissionChargesIT.openingTheDetailAndAddingAChargeWriteAPatientAccessRowButTheListAndTheBoardDoNot |
| 7 (security L6) | Extra JSON fields `unitPaise`, `qty`, `tenantId`, `addedBy`, `serviceName`, `admissionId` in the POST body are ignored: row is key ecg, name ECG, unit 30000, qty 1, caller tenant, caller user, URL admission; other clinic untouched; audit actor is the caller | AdmissionChargesIT.extraJsonFieldsUnitPaiseQtyTenantIdAndAddedByAreIgnoredAndTheServerValuesAreStored |
| 7 (security L3) | `qty` is not in the insert grant: `sahaj_app` insert of `qty` (even 1) is permission denied; rows always have qty 1 (`count(qty <> 1) = 0`); check qty 0 and 51 still refused | AdmissionChargesIT.chargeTableIsAppendOnlyForSahajAppAndItsOwnerAndOnlyTheListedColumnsAreInsertable; quantityAccumulatesInOneLinePerServiceInOrderOfTheFirstCharge |
| 7 (security L1) | Role re-check in the service (OWNER, FRONT_DESK, NURSE only) is unit-tested (AdmissionChargeServiceTest, BedBoardServiceTest); the HTTP 403 matrix of ITs above and in BedBoardIT still passes unchanged | AdmissionChargesIT.ownerFrontDeskAndNurse...; BedBoardIT.ownerFrontDeskAndNurseMayChangeBeds... |
| 2, 5 (review 3, 8) | One day number: sheet `dayNumber`, list `dayNumber` and board tile `dayNumber` all equal `bill.billDays`, under midnight and 24-hour cuts, for three admissions where the cuts disagree (23:59 IST yesterday: 2 vs 1) | RunningBillIT.oneDayNumberEverywhereTheSheetTheListAndTheBoardTileAllEqualTheBillDaysUnderBothBedDayCuts; midnight/24 h loops also assert detail and list `dayNumber` |
| review fix | Merged-away patient admit = 409 `patient-merged` with the exact message; nothing written, bed still free; kept record can be admitted | AdmissionChargesIT.aMergedAwayPatientCannotBeAdmittedAndTheKeptRecordCan |
| review fix | Control, NUL, C1, DEL and bidi characters in the reason = 400 `reason`; nothing written; Devanagari text accepted | AdmissionChargesIT.controlAndTextDirectionCharactersInTheReasonGet400WithAFieldErrorAndNothingIsWritten |

## Notes and gaps
- The "no query per row" property of the list and board (optional in the task) is not asserted: the test database is shared by concurrent agents, so database-wide statement counters are not reliable. The tests that put 3 to 4 admissions with different bill days, deposits and charges through the list and board at once (listAndBoardAgreeOnWhoIsDepositLowAfterBackdatingAndExtraCharges) check that every row gets its own bill.
- `dayNumber` (sheet, list, board tile) now equals `bill.billDays` under both cuts (code review finding 3, implemented as the preferred option). The tests assert the equality, not a stored constant. All IST-midnight tests read first, then call `assumeTrue(sameIstDay)`, then assert (code review finding 8: the guard in sameAdmissionGivesTwoDays... was after the assertions).
- `IpdBillingSupport.backdate` takes an ACCESS EXCLUSIVE lock on `ipd.admission` for the length of its transaction (ALTER TABLE NO FORCE / FORCE row level security, lock_timeout 30 s). A concurrent test on the same table waits for it. Test-only, accepted by the code review (finding 10); do not run IPD ITs in parallel in one JVM.
- Zero-width joiner / non-joiner (U+200D / U+200C) in the reason: the current IpdText rejects them; the java-developer will allow them in the SAH-26 pass (needed for Indic scripts). No test pins the rejection; add a positive test (Devanagari with ZWJ accepted) once that change lands.
- Reason validation order (review finding 5): the raw value is checked before trimming. Not yet pinned by an IT for trailing control characters; covered by the Java unit tests.
- The multiplier of extra charges cannot produce a half paise (all base prices are whole rupees and the multiplier has two decimals), so half-up rounding is tested on the doctor visit, whose base is any paise value set in Clinic setup.

## Browser tests (Playwright, `frontend/e2e/app-shell.spec.ts`, API mocked in the browser)
Run: `npm --prefix frontend run build`, then in `frontend/e2e`: `npx playwright test` (bundled Chromium; both projects, phone 360 and desktop 1440; the shell block sets 1280 px and runs once). Last run 5 Nov 2026: 100 passed, 8 skipped (shell-at-1280 block on the phone project, two desktop-only home tests), 0 failed.

| Case | Test |
| --- | --- |
| Paise are exact: list ₹187.50 (not ₹188), deposit ₹10,000 | IPD: Admitted tab shows paise exactly |
| Sheet: ₹187.50, 3 × ₹562.50 = ₹1,687.50, 2 × ₹1,200, total ₹4,275, deposit − ₹10,000, ₹5,725 left; charge list ₹187.50 and ₹562.50; no rounded ₹188 / ₹1,688 | IPD: admission sheet: every line, quantity price and total is exact to the paisa |
| Add a charge posts only `{serviceKey}` and the new bill shows | IPD: adding a charge posts only the service key |
| Error: 409 `ipd-readonly` message shown, bill unchanged | IPD: error path refused charge |
| Error: list load failure alert with Try again; 403 `module-off` shows the locked page | IPD: error path (two tests) |
| Bed board tile: Day 2 · Dr Asha, Deposit low flag painted, free bed ₹1,200 a day | IPD: bed board tile |
| `.p-crit` (Deposit low), `.p-blue` (Insurance, Corporate), `.p-good` have a non-transparent background and text colour, and crit differs from blue | Pills and notices are painted: Deposit low ... |
| `.notice.crit` in the sheet has a non-transparent background and text colour | Pills and notices are painted: Deposit low notice |
