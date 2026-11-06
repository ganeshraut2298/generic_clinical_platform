# Test plan SAH-23 · Admission and deposits (IPD, part 2)

Story: `docs/stories/SAH-23.md`. Contract: `docs/api/ipd.yaml` (SAH-23 parts). Design: `docs/database/SAH-23.md` (migration `V2026_11_12_1000__ipd_admission_and_deposits.sql`).
Integration tests: `backend/src/test/java/com/sahaj/platform/it/AdmissionDepositsIT.java` (30 tests; real `sahaj_test` database, MockMvc through the full filter chain). IPD is switched on with `POST /platform/modules/ipd-trial`; wards and beds are created through the SAH-22 API, patients through the patient API, doctors through the team API. Expired trial and `DISCHARGED` status are set with committed SQL as `sahaj_app`. Raw SQL checks use `DbAccess.inTenant`. Logs are captured with `OutputCaptureExtension`.
Frontend e2e (Playwright): not part of this pass.
Known deviation (not tested as a failure): the owner is not offered in the admitting-doctor list.

## Coverage
| AC | Case | Test |
|---|---|---|
| 1 | IPD off: all 8 endpoints 403 (owner: `module-off`); no rows, no audit | ipdOffAllEightEndpointsAre403ModuleOffAndNothingIsWritten |
| 1 | Read-only: reads work, canAdmit/canCollectDeposit false, 4 writes 409 `ipd-readonly`, nothing changed | readonlyReadsWorkAndEveryWriteIs409IpdReadonlyWithNothingChanged |
| 1 | Menu key `ipd` for OWNER, FRONT_DESK, NURSE, DOCTOR in trial/readonly; not for others or when off; menuCounts +1; position after followups | menuHasIpdForFourRolesWhenIpdIsOnOrTrialOrReadonlyAndNeverWhenOff |
| 7 | GET rules defaults, no config row written, 4 reader roles, 4 others 403, 401 | getRulesReturnsDefaultsWithoutWritingARowAndOnlyFourRolesMayRead |
| 7 | PUT saves, audit, keeps confirmed flag | putRulesSavesWholeRupeesAuditsOnceAndKeepsTheConfirmedFlag |
| 7 | Boundaries 0/100000000, alert 50/100; non-whole rupee, negative, over max, alert 45/49/82/105/0, missing parts, bad JSON all 400 with fieldErrors, nothing changed | rulesBoundariesAreAcceptedAndBadValuesGet400WithFieldErrorsAndChangeNothing |
| 7, 9 | 7 non-owner roles 403 on PUT and confirm | nonOwnersGet403OnRulesWritesAndNothingChanges |
| 7 | Confirm idempotent, one audit, row with defaults | confirmIsIdempotentAuditsOnceAndCreatesTheRowWithDefaults |
| 7, 9 | No-op PUT writes no row/audit | aPutThatChangesNothingWritesNoRowAndNoAudit |
| 3 | Options: free beds only, occupied bed disappears, doctors (nurse excluded), rules; roles OWNER/FRONT_DESK/NURSE only | optionsListFreeBedsDoctorsAndRulesAndAnAdmittedBedDisappears |
| 4, 5 | Admit: 201, IP/FY/0001 then 0002, bed occ, ward bed count same, admission and deposit columns, sequence | admitHappyPathStoresEverythingIssuesIpNumbersInSequenceAndOccupiesTheBed |
| 3, 5 | Reason blank/missing/null -> Under evaluation, trimmed, 200 chars ok | blankOrMissingReasonIsStoredAsUnderEvaluationAndAReasonIsTrimmedAndBoundaryIs200 |
| 4 | Check order: patient 404 (unknown, other clinic) -> already-admitted 409 -> doctor 400 -> bed-not-free 409 (occupied, unknown and other-clinic bed are all 409) -> consent 400 -> deposit 400; nothing consumed | checkOrderAndMessagesEachRefusalLeavesNothingBehind |
| 4 | Minimum = base + category add-on: planned/General 10,000, planned/ICU 20,000, Semi 12,000, Private 15,000, day-care, maternity; exact message; rule change via PUT (Indian grouping ₹1,25,500) | selfPayerBelowTheMinimumGetsTheExactMessageForEachTypeAndCategoryAndRuleChangesApply |
| 4 | Emergency with 0 deposit allowed (DUE); switch off refused; planned still refused | emergencyWithoutDepositIsAllowedWhenTheSwitchIsOnAndRefusedWhenOff |
| 2, 4 | Insurance/Corporate without deposit -> NOT_NEEDED | insuranceAndCorporatePayersNeedNoDeposit |
| 4 | Malformed: enums, reason 201, mode missing/invalid, negative, non-whole, over max, missing ids, bad UUID, bad JSON -> 400; max deposit ok | malformedAdmissionInputGets400ProblemDetailsAndWritesNothing |
| 9 | Roles: OWNER/FRONT_DESK/NURSE 201; 5 others 403; 401 | ownerFrontDeskAndNurseMayAdmitAndTheOtherRolesGet403 |
| 5 | Same bed, two patients: one 201, one 409 bed-not-free | twoSimultaneousAdmitsOfDifferentPatientsToTheSameBedGiveOneCreatedAndOneBedNotFree |
| 5 | 6 parallel admits: distinct IP 0001..0006, last_no 6 | sixSimultaneousAdmitsToDifferentBedsAllSucceedWithDistinctConsecutiveIpNumbers |
| 5 | Same patient, 3 beds: one 201, two 409 already-admitted | simultaneousAdmitsOfTheSamePatientToDifferentBedsGiveExactlyOneAdmission |
| 6 | Deposit adds row, total, DUE -> OK; boundaries 100 and 100000000 paise | collectingADepositAddsARowUpdatesTheTotalAndMovesDueToOk |
| 6, 9 | Deposit roles (NURSE 403), amount/mode validation, 404 unknown/other clinic, 409 `not-admitted` | depositRolesValidationAndNotFoundAnd409WhenNotAdmitted |
| 5, 6 | Append-only deposit; no delete/truncate; column grants (id, admitted_at denied); check constraints; positive control | depositTableIsAppendOnlyAndColumnGrantsHoldForSahajApp |
| 2 | List newest first, counts, row fields, canAdmit by role, `?status` other than ADMITTED 400, discharged leaves list | listIsNewestFirstWithCountsRowFieldsAndCanAdmitByRole |
| 6 | Detail: deposits, collector name, reason, mobile, canCollectDeposit by role, 404, 400 bad id | detailShowsDepositsReasonMobileAndCanCollectByRoleAnd404ForUnknown |
| 8 | Bed delete: occupied `bed-occupied`; history (even discharged) `bed-has-history`; never-used still deletes; raw delete blocked by FK | removingABedWithAdmissionHistoryIs409AndANeverUsedBedStillDeletes |
| 9 | Audit ids/IP/type/payer/bed/amounts; reason, name, mobile in no audit row and no log | auditCarriesIdsAndAmountsButNeverTheReasonNameOrMobileAndNothingIsLogged |
| 9 | RLS: other clinic sees nothing (API + SQL), no tenant 0 rows, IP numbers per clinic | anotherClinicSeesNothingThroughTheApiOrSqlAndIpNumbersStartAgainAt0001 |
| 9 | Composite FK to other clinic's bed, foreign tenant_id, no tenant, one admission per patient, unique IP number | anAdmissionCannotPointAtAnotherClinicsBedOrBeBoundToAnotherTenant |
