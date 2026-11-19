# IPD go-live test plan · journey, dose and order states, races, money across a stay

Branch feature/SAH-39-home-kinetic-background (SAH-76 line of work). Spec: `docs/reviews/GO-LIVE-ipd-test-gaps.md` section 3.1 and the QA-1 table (reviewer finding F1, F3 to F7, F10). Contract: `docs/api/ipd.yaml` 0.4.2. Database: `sahaj_test` only, profile `test`, MockMvc through the real security filter chain, real PostgreSQL with row level security, real PIN pepper (`@WithPinPepper`). Test classes are in `backend/src/test/java/com/sahaj/platform/it/`.

| Class | What it proves | Tests |
| --- | --- | --- |
| IpdJourneyIT | One admission, 15 ordered steps (section 3.1), every write through the real endpoint, a second clinic probing after every step, the log captured for the whole run | 1 test, 15 steps plus 14 second-clinic probes |
| DoseOrderStateIT | Dose and MAR against an order on hold, queried, changed, replaced, stopped; counters; PRN lock reason | 7 |
| DoseOrderRaceIT | Give against hold, change, undo, recorded in error and a transfer out of the nurse's ward, in the lock orders the database can serve and once free running | 6 |
| IpdMoneyStayIT | Running bill = sum of lines at every step, frozen = running, deposits + balance = total, owner-only refund, Insurance and Corporate never deposit low, parallel deposits | 5 |
| IpdJourneySupport | Shared set-up: people with PINs, the second clinic, time-stamp moves, step runner, audit check | - |

## How a test never depends on the time of day

Scheduled doses need a known time of day. The classes use the clock seam of `MarSupport` (the dose, order and board reads answer a fixed instant), fixed on a day two days back (8:05 AM, 8:10 PM). Times that the database stamps itself with its real clock (hold, resume, change, supersede, verify of a new version, stop, undo window) are moved to that clock right after the write by the owner with the guard triggers of that one table off inside one transaction, the order version and the order head together (`IpdJourneySupport.stampVersion`, `stampHold`, `stampStop`, `undoUntil`, `ageGrant`; `Sah76NursingSupport.backdate` for version 1). The fixed clock sits in the real past so that a moved time never lies after the real clock (a resume must not stamp a time before its hold). The bed day is cut after 24 hours and admissions are moved back by whole 24-hour periods plus 30 minutes, so the bill days never depend on the IST day. No test uses `assumeTrue` on the time of day.

SQL is used for two things only: reading state back and moving a time stamp back.

## IpdJourneyIT: acceptance condition (section 3.1) to step

| # | Condition | Where in the test |
| --- | --- | --- |
| 1 | Setup: two wards, a nurse in each, rates, minimum deposit, pharmacist check On, ward scope On, a nurse cannot change the setup | step1Setup |
| 2 | Admit and deposit: below the minimum 400, bed occupied, running bill = sum of lines and the same in list, nurse of ward A reads, nurse of ward B outside-scope 403, other clinic 404 | step2AdmitAndDeposit |
| 3 | Orders: bd high-alert, PRN, STAT; duplicate and allergy hard stops (200 BLOCKED, nothing placed, audited); a nurse, the pharmacist and a nurse of another ward cannot order (403); empty body 400 with field errors | step3Orders |
| 4 | Pharmacist: wrong PIN once (403 wrong-pin, 4 tries left); verify with orderVersion; replay 200 replayed true, no audit row; second verifier 409 order-state; STAT given before the check lists a review row, verify closes it as VERIFIED | step4Pharmacist |
| 5 | MAR give: wrong wristband 409 and no dose (deny entry, audit row); high-alert without second nurse 400; give with the second nurse's PIN; replay 200; two nurses on one slot at once: one 201, one 409 dose-recorded; PRN dose then prn-too-soon; nurse of ward B outside-scope | step5Give |
| 6 | Undo, attempt 2, recorded in error, attempt 3, not given; EMR execution line equals the MAR after each | step6Correct |
| 7 | Hold, give 409 order-on-hold (audited), resume, give; change v2; old version 409 order-changed; v2 not givable until verified; CHANGED notice acknowledged once, repeat quiet, second nurse told who did it | step7HoldResumeChange |
| 8 | Vitals LOW (no escalation), MEDIUM (escalation 1 hour); other ward's nurse outside-scope; bad body 400 | step8Vitals |
| 9 | Safety: acknowledge, HIGH reopens, call senior, nurse of the other ward outside-scope, nurse cannot acknowledge | step9Safety |
| 10 | Break-glass for nurse B: picker, open 30 minutes, reads, owner review once, window ends, outside-scope again | step10BreakGlass |
| 11 | Transfer to ward B: bill split (General 2 days, Semi-private 1 day, nursing 3), old bed cleaning, nurse A outside-scope for reads and writes, nurse B reads MAR and notices, the slots before and after are identical | step11Transfer |
| 12 | Extra charge, two deposits at the same moment, totals add up, detail = list | step12Money |
| 13 | Discharge refused while an escalation is open (409 escalation-open), response closes it, stale total 409 bill-changed, refund is the owner's (403 refund-needs-owner), frozen bill = running bill = sum of lines (paise), bed to cleaning | step13Discharge |
| 14 | After discharge: 14 writes answer 409 not-admitted and write nothing, lists and board empty of the patient, MAR read-only, EMR line zero | step14AfterDischarge |
| 15 | At every step: second clinic sees nothing (by-id 404 on 6 reads and 3 writes per order, lists, board, row level security by SQL on 14 tables); at the end: the log, audit rows and events hold no name, mobile, UHID (except the identity-check stop entry), allergy or clinical text (one marker is put into every free-text field) and no PIN; every IPD audit row has a user; one audit row per write is checked at each write | after(...), step15Privacy |

Left out on purpose: the PRN start and end window (DEC-057 item 3, contract 0.4.3 pending). When 0.4.3 is approved: `PrnWindowIT` (start minus 1 minute refused, start, end allowed per contract, end plus 1 minute refused).

## Coverage: QA-1 row to test to result

Result column: result of the last run of 17 Nov 2026 (see "How it was run" for the run, which is not `mvn verify`).

| QA-1 row (go-live review section 4) | Test | Result |
| --- | --- | --- |
| IpdJourneyIT: 15 steps of section 3.1 | IpdJourneyIT.anAdmissionIsTakenFromSetupToDischargeAndEveryStepHoldsItsRules (steps 1 to 15 and 13 second-clinic probes) | pass |
| DoseOrderStateIT: give on held 409, writes nothing | aDoseOnAHeldOrderIs409AndWritesNothingAndAfterTheResumeTheSlotIsGiven | pass |
| DoseOrderStateIT: resume then give | same test (second half) | pass |
| DoseOrderStateIT: slot inside a hold stays ON_HOLD after the resume | aSlotThatFellInsideTheHoldStaysOnHoldAfterTheResumeAndIsNotCreatedAgain | pass |
| DoseOrderStateIT: QUERIED refused until reply and verify | aQueriedOrderCannotBeGivenUntilTheDoctorRepliesAndThePharmacistVerifies | pass |
| DoseOrderStateIT: old orderVersion refused and audited, new version not givable until verified | theOldVersionIsRefusedAndAuditedAndTheNewVersionIsNotGivableUntilVerified | pass |
| DoseOrderStateIT: MAR states REPLACED | anOrderChangedToOnceADayShowsTheEveningSlotOfTheOldVersionAsReplaced | pass |
| DoseOrderStateIT: MAR states STOPPED and the counts (late, dueNow, given, doseCount), EMR line = MAR | theMarShowsStoppedAndCountsLateDueGivenAndHeldCorrectly | pass |
| DoseOrderStateIT: PRN on hold / stopped lockReason | aPrnLineSaysWhyItCannotBeGivenWhileWaitingOnHoldAndStopped | pass |
| DoseOrderRaceIT: give vs hold | aGiveAndAHoldAtTheSameMomentNeverGiveAHeldOrderAndNeverLoseTheDoseThatWonTheLock | pass |
| DoseOrderRaceIT: give vs change | aGiveAndAChangeAtTheSameMomentNeverRecordADoseOfAReplacedVersion; whenThePharmacistChecksChangesAGiveThatLosesTheLockIsRefusedAsNotVerified | pass |
| DoseOrderRaceIT: undo vs re-give | anUndoAndAGiveOfTheSameSlotAreServedOneAfterTheOtherAndNeverLeaveTwoLiveDoses | pass |
| DoseOrderRaceIT: undo vs recorded in error | anUndoAndARecordedInErrorOfTheSameDoseAtTheSameMomentCorrectItOnce | pass |
| DoseOrderRaceIT: give vs transfer-out (scoped nurse) | aGiveAndATransferOutOfTheNursesWardAtTheSameMomentAreServedInTheOrderOfTheLockAndNeverLeakADose | pass |
| IpdMoneyStayIT: running bill = sum of lines at each step, one total everywhere | theRunningBillIsTheSumOfItsLinesAndTheSameEverywhereAtEveryStepOfAStay | pass |
| IpdMoneyStayIT: frozen = running, deposits + balance = total | theFinalBillIsTheRunningBillFrozenAndDepositsPlusBalanceAreTheTotalForACollectionARefundAndASettlement | pass |
| IpdMoneyStayIT: refund owner-only, negative | aRefundIsTheOwnersAndIsStoredNegative | pass |
| IpdMoneyStayIT: insurance and corporate never deposit-low | anInsuranceOrCorporatePatientIsNeverDepositLowHoweverBigTheBill | pass |
| IpdMoneyStayIT: parallel deposits (P1 DepositConcurrencyIT, done here) | depositsThatArriveAtTheSameMomentAreAllKeptAndAddUp | pass |
| EmrExecutionLineIT | not written as its own class: the EMR execution line is checked against the MAR after the dose, undo, attempt 2, recorded in error, not given, hold, change, transfer and discharge in IpdJourneyIT and after stop and the counters in DoseOrderStateIT | pass (with the java fix of SAH-76 code #1 in the build) |
| CorsPreflightIT, PrnWindowIT | not written (PRN: contract 0.4.3 pending; CORS: next run) | gap |

## Defects found by these tests

None in the application code in the build of 17 Nov 2026 (the SAH-76 code #1 fix was already in it: the EMR line equals the MAR after undo and recorded in error). Observations for the human: (1) the extra services of a stay are priced at base times the multiplier of the bed category the patient is in when the bill is read, so an X-ray taken on a General bed costs more after the patient moves to a Semi-private bed (contract AdmissionChargesIT rule "rates read at billing time"; confirm this is wanted for services already given). (2) A discharge with late doses, active orders or an open order notice is accepted silently (go-live review decision 4).

Defects of the tests themselves that this run found and fixed (not application faults): a fixed clock in the past makes the database refuse an undo ("dose undo window has closed", 23514) and a recorded in error ("only on the day of the slot") and the application answers 500 because the database guard reads the real clock while the application reads the clock seam; the journey therefore fixes the clock on the real IST day and waits out the last 15 minutes of the IST day (`IpdJourneySupport.waitOutTheIstMidnight`).

## How it was run

The command of the task, `mvn -o -f backend/pom.xml verify -Dit.test=IpdJourneyIT,DoseOrderStateIT,DoseOrderRaceIT,IpdMoneyStayIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`, compiled for about 15 minutes (929 main and 663 test sources, every run recompiles all of them) and then did not filter: surefire ran `SahajApplicationTests` and failsafe started the whole suite (223 IT classes; its reports are in `backend/target/failsafe-reports`, and those of the four new classes there come from sources of the first hour, before the fixes, so they must not be read as results). Maven itself was stopped by the QA agent after about 35 minutes; its failsafe fork carried on and finished the suite by itself. Whether the two `-D` filters are lost by the `mvn` script on this machine or by the pom, the tech lead has to check (`mvn` is the Git Bash script `C:\Users\Hp\tools\apache-maven-3.9.9\bin\mvn`). The four classes were therefore run with the JUnit launcher on the classes Maven had compiled (javac of the five new files against `backend/target/classes`, `backend/target/test-classes` and the dependency class path, `-Dspring.profiles.active=test`, real `sahaj_test`, real PIN pepper): IpdJourneyIT 1 of 1, DoseOrderStateIT 7 of 7, DoseOrderRaceIT 6 of 6, IpdMoneyStayIT 5 of 5. Two earlier attempts of IpdMoneyStayIT ended with "remaining connection slots are reserved" and with 503 database-busy while the leaked JVMs of this session's launcher runs (a Spring context keeps the JVM alive) and the suite held connections and table locks; they were killed, the launcher now exits, and the later runs were clean. `node scripts/validate.mjs all` was not run for the same reason (it runs the whole suite).

## Found alongside (not new tests): older ITs that disagree with the DEC-057 migration

The same session ran the old ITs that touch the dose and order tables against `V2026_12_16_1000__ipd_review_safety_guards.sql` (DEC-057 option A; the dose insert guard now needs the wristband scan on every GIVEN dose, the override reason WRISTBAND_DAMAGED is refused, and the new table `ipd.dose_scan_stop` references `ipd.medicine_order`). Seven tests of those classes fail: `Sah76DoseGiveIT.anOverrideGivesWithoutAScanAndStoresTheReasonAndTheNoteButNeverLogsTheNote` and `anOverrideOnAHighAlertMedicineStillNeedsASecondNurseWhosePinIsChecked` (the application answers 500 where the contract text of 0.4.2 (e) says an override may have an empty wristband: either the contract and the tests follow DEC-057 and the application must answer 400 "Scan the wristband first." and not reach the guard, or the guard is wrong), `DoseTableIT.item11...` and `Sah76DoseDbIT.aNotGivenDose...` (a GIVEN row without identity_scan is now refused by the guard before the check constraint they name), `DoseTableIT.item17...` and `MedicineOrderTableIT.item17...` (TRUNCATE of ipd.medicine_order is now refused with 0A000 by the new foreign key and not with 42501), and `Sah76SettingsIT.theFirstProfileWriteCopiesTheStartersAndTheKeyIsMadeFromTheName`. They are tests of earlier behaviour, in this folder, and wait for contract 0.4.3 before they are changed.

## Fix round part 2 (contract 0.4.3, DEC-057): old ITs brought to the new behaviour, new real-database ITs, e2e

Contract `docs/api/ipd.yaml` 0.4.3, ADR-027 A9, design note `docs/database/SAH-76.md` (review-round migration `V2026_12_16_1000__ipd_review_safety_guards.sql`). Mockup screens: Nursing sheet (MAR, Orders, give sheet), Pharmacist check. The texts marked NEW in the contract are not in the mockup; the human signs them off.

### Old tests changed (only as far as the migration or the contract changed)

| Test | Old behaviour | New behaviour asserted |
| --- | --- | --- |
| Sah76DoseGiveIT.anOverrideGivesWithoutAScanAndStoresTheReason... | MAR lists 4 override reasons, first WRISTBAND_DAMAGED; the override body had no wristband | 3 reasons in the order NO_BARCODE, SCANNER_DOWN, EMERGENCY; every override body sends the wristband (`Sah76NursingSupport.overrideGiven(ward, ...)`) |
| Sah76DoseGiveIT.anOverrideNeverSkips... | the statement was tried after two mismatches | the statement is tried first: after a mismatch the next override answers 409 override-after-mismatch before the statement check (contract check order D) |
| DoseTableIT.item11 | GIVEN without identity_scan: table check ck_dose_administration_identity_scan_by_outcome | the insert guard fires first: 23514 "given dose needs the wristband scan" |
| Sah76DoseDbIT.aNotGivenDose... | a good OVERRIDE row without identity_scan | the row keeps its wristband; new cases: OVERRIDE without wristband, reason WRISTBAND_DAMAGED and OVERRIDE with a medicine_scan are each refused by the guard (23514) |
| DoseTableIT.item17, MedicineOrderTableIT.item17 | the list of referencing tables ended at dose_correction and reached the append-only trigger (42501) | without ipd.dose_scan_stop in the list: 0A000 (foreign key); with it: 42501; TRUNCATE of ipd.dose_scan_stop alone: 42501 |
| Sah76SettingsIT.theFirstProfileWriteCopiesTheStarters... | the profile name is not in the audit | the profile name is in the audit on purpose (code review 8): asserted present |
| Sah76MigrationIT | nine new tables | ten: `ipd.dose_scan_stop` has enabled and forced RLS and a policy, and its two append-only triggers exist. RowLevelSecurityCatalogIT finds it by its own catalog query (every table with tenant_id) |

### New real-database ITs: Sah76ReviewRoundIT (13 tests)

| Id | Requirement (contract 0.4.3) | Test | Result |
| --- | --- | --- | --- |
| R-01 | wristband mismatch, then an override within 15 min by the same nurse, order and slot: 409 override-after-mismatch with the contract text and title; the dose_scan_stop row stays committed (not doubled); IPD_DOSE_REFUSED audited with refusal key, order, admission, device; no dose, no clinical event, no note or code in any audit; another slot, another nurse allowed; a MEDICINE mismatch counts; 14 minutes later still refused, 16 minutes later allowed | anOverrideWithin15MinutesOfAMismatchIsRefusedAndTheStopRowAndTheAuditStayCommitted | pass |
| R-02 | PRN: any mismatch of the order blocks the override; a code that matches is never refused | forAPrnOrderAnyMismatchOfTheOrderBlocksTheOverrideButAMatchingScanIsNeverRefused | pass |
| R-03 | the rule is per order; stop rows are invisible to another clinic; the application cannot update, delete, truncate; check_kind is WRISTBAND or MEDICINE | aMismatchOfAnotherClinicOrAnotherOrderNeverBlocksAndTheStopRowIsInvisibleToAnotherClinic | pass |
| R-04 | PRN before its start: 409 dose-not-due "This dose cannot be given now. Reload the page." for GIVEN and NOT_GIVEN, nothing written (dose, event, audit, access, PIN state unchanged), PrnLine.lockReason NOT_STARTED | aPrnDoseBeforeItsStartIsRefusedWithDoseNotDueAndNoAuditAndTheLineIsLockedNotStarted | pass |
| R-05 | PRN after its end: same, lockReason ENDED | aPrnDoseAtOrAfterItsEndIsRefusedWithDoseNotDueAndNoAuditAndTheLineIsLockedEnded | pass |
| R-06 | PRN inside its window: no lock reason, dose given (positive boundary) | aPrnDoseInsideItsWindowIsGivenAndTheLineHasNoLockReason | pass |
| R-07 | the same client key sent by another nurse or the owner: 409 client-key-reused, nothing written, no record_access row; the recorder still gets the replay | aReplayOfAClientKeyByAnotherNurseOrTheOwnerIsRefusedAndWritesNothingNotEvenARecordAccessRow | pass |
| R-08 | an OWNER who changed an order (placed by the doctor) cannot verify or query it: 403 self-verification with the "changed" texts; the queue row says SELF_CHANGED with canVerify and canQuery false; the order and the query table are untouched; the pharmacist sees canVerify true and verifies version 2 | anOwnerWhoChangedAnOrderCannotVerifyOrQueryItAndTheQueueRowSaysSelfChanged | pass |
| R-09 | an owner who placed and changed his own order gets the "ordered" text and SELF | anOwnerWhoPlacedAndThenChangedHisOwnOrderGetsTheOrderedTextNotTheChangedText | pass |
| R-10 | a missing, blank or null wristband with an override (also with a code, also with nothing): 400 field wristband "Scan the wristband first.", never 500, nothing written, no stop row | aMissingWristbandWithAnOverrideIsA400AndNeverA500AndWritesNothing | pass |
| R-11 | the same on a high-alert override with a second nurse (the DB backstop is never reached; the PIN allowance is untouched) | aMissingWristbandOnAHighAlertOverrideWithASecondNurseIsA400BeforeTheDatabaseBackstop | pass |
| R-12 | WRISTBAND_DAMAGED refused on input (400 override.reasonKey); a matching code ignores an override sent with it (stored SCAN, no override fields, not even a bad reason is checked) | theDamagedWristbandReasonIsRefusedOnInputAndAMatchingCodeIgnoresAnyOverrideSent | pass |
| R-13 | a wrong typed code with an override: 409 medicine-mismatch (never skipped), a MEDICINE stop row, the next override refused, the right code accepted | aTypedCodeThatDoesNotMatchStopsEvenWithAnOverrideAndTheNextOverrideIsRefused | pass |

### Mocked Playwright (frontend/e2e)

The mock server of the e2e suite (`sah31-world.ts`, `sah32-world.ts`) was last written for SAH-31 and SAH-32. SAH-76 changed what the screens read, so before this round 97 of the 99 tests of `mar.spec.ts` failed on `desktop-1440` (the MAR did not draw: no counters, windows, override reasons, order version or details in the mocked answers; a poll of `GET /order-notices/summary` was not mocked). The world now answers in the 0.4.3 shape and follows the 0.4.3 check order of the dose (wristband, medicine code, override fields, override after a mismatch, statement, second nurse), the PRN lock reasons and the replay by another user. New: `mar-override.spec.ts` (R-UI-1 to R-UI-8: step 2 and the position of the override, three reasons and no WRISTBAND_DAMAGED, override hidden while a code is typed, override send body, wrong code, empty wristband with an override, override-after-mismatch with Given off until the code is edited, PRN Not started yet and Ended, PRN give) and a SELF_CHANGED test in `pharmacist.spec.ts`. In `mar.spec.ts` the texts and counts that SAH-76 changed were updated (three tabs, step numbers 2/3/4, seven not-given reasons, toast texts, one wrong-patient text, Given off after a mismatch, execution lines instead of "Last dose").

### More old ITs that disagreed with 0.4.3 (found by the full suite run of 17 Nov 2026, fixed here)

The older ITs build their GIVEN body with the manual override (no medicine code is loaded in `ipd.drug_code`). Since 0.4.3 a mismatch is on record for 15 minutes, so an override of the same nurse on the same order and slot is refused (409 override-after-mismatch, before the statement and the second-nurse checks). Changed, behaviour asserted unchanged otherwise: `DoseGiveIT` (3 tests), `DosePrivacyIT`, `IpdJourneyIT` step 5 (the stop rows are moved 16 minutes back with the new `MarSupport.ageScanStops` before the override give), `DoseSecondNurseIT` and `DoseWithoutPepperIT` (the statement check is tried before the mismatch). `Sah76NursingSupport.extendUndo` now reads one hour after the later of the database clock and the fixed application clock: `IpdJourneyIT` step 6 failed when it ran between 00:00 and 08:05 IST (the fixed clock "8:05 AM of today" was later than the database clock, so the undo window read as closed). The other fixed-clock ITs that read the window by the application clock were not re-run at night.

### Mocked Playwright results (desktop-1440 unless said)

| Scope | Result |
| --- | --- |
| mar.spec, mar-override.spec, pharmacist.spec, both projects | 406 passed, 4 failed in the run before the last fixes; after them the only open failure is mar.spec "after an error the toast does not sit over Given or Record not given" (both projects, see the defect list of the handoff) |
| nursing, safety, colleague-pin, medicine-round, pharmacist, mar-override, desktop-1440, after fixes | 382 passed, 2 failed (both fixed afterwards, not re-run alone) |
| app-shell, bed-transfer, discharge, emergency, nursing, packages, safety, patient-dob, medicine-round, colleague-pin, mar, pharmacist, mar-override, phone-360 | 689 passed, 5 failed (1 mar toast, 4 patient-dob), 6 skipped |
| whole suite, desktop-1440, before the mock fixes | 1,189 passed, 206 failed, 14 skipped; 87 + 56 of the failures were an unmocked `GET /opd/emr/me` (SAH-46-1) and 20 an unmocked `GET /ipd/order-notices/summary` or `/ipd/order-notices` (SAH-76); the rest are listed below |

Still failing and not changed by this round: `medicine.spec.ts` (34: the SAH-31 order form and "Last dose" rows, which SAH-76 replaced by the dose, unit, route and priority fields and the execution lines; the mocked sheet and the specs need a rewrite), `emr-finish.spec.ts` (5, EMR finish and print dialogs, not IPD), `home-background.spec.ts` (4, SAH-39 home layer: "home to Log in" still has the canvas), `patient-dob.spec.ts` on phone-360 (4).

### Gate of 17 and 18 Nov 2026 (how it was run, and what it says)

* Filtered runs with the quoted arguments (`"-Dit.test=A,B" "-Dtest=NoSuchTest" "-Dsurefire.failIfNoSpecifiedTests=false"`) were honoured: only the named classes ran. Last run (all classes this round touched): 122 tests, 0 failures: DoseGiveIT 35, DoseOrderRaceIT 6, DoseOrderStateIT 7, DoseWithoutPepperIT 2, IpdJourneyIT 1, IpdMoneyStayIT 5, Sah76DoseCorrectionIT 13, Sah76DoseDbIT 11, Sah76DoseGiveIT 22, Sah76MigrationIT 7, Sah76ReviewRoundIT 13. Earlier in the round: DoseTableIT 24, MedicineOrderTableIT 24, Sah76SettingsIT 13, RowLevelSecurityCatalogIT 14, DosePrivacyIT 2, DoseSecondNurseIT 23 (all pass). Every run compiles all main and test sources first (about 10 to 15 minutes).
* `node scripts/validate.mjs docs` fails on the known file name `docs/stories/ADM-2.md`. `validate db` and `validate backend` run `mvn verify` over the whole suite. The first complete run (21:56 to 00:05, 224 IT classes, 3,004 tests, 5,730 unit tests with 0 failures) ended with 49 failures and 379 errors. It was not a clean run: another story (Pharmacy, migrations `V2026_12_17_*`, `com.sahaj.pharmacy`, `MembershipRole`, `application*.yml`) was being built in the same working tree and the same `target` folder during it. Evidence: Flyway "No value provided for placeholder: ${sahajSampleData}" in the application context of most failing classes, `NoClassDefFoundError: Sah75LayoutHashes`, "No qualifying bean of type MedicineOrderRepository", and HTTP 500 from patient endpoints that no SAH-76 code touches. All 39 classes that failed or errored were run again alone on a quiet tree: 626 tests, 2 failures (DoseWithoutPepperIT and IpdJourneyIT, both the 0.4.3 and time-of-day causes above, both passing after the change, see the last run).
* The sample-data placeholder, the Pharmacy migrations and the working-tree sharing are not SAH-76; the tech lead has to give the gate a quiet tree (nobody else compiling or running `mvn` or `ng` in this folder) for the final `validate all`.
* `validate frontend` fails on `--browsers=ChromeHeadless` (scripts/validate.mjs:28). Run by hand: `ng lint` passes ("All files pass linting"); `ng build` passed at the start of the run (before the Pharmacy files); `ng test` could not compile at 23:12 because `features/pharmacy/setup/setup.page.ts` imported a file the other story had not written yet; at 01:12 it ran: 307 test files, 4,815 tests, all pass.
* Side effect: running the whole Playwright suite re-generated the SAH-39 screenshots and JSON under `docs/test-plans/SAH-39-shots/` (spec home-background). The tech lead may restore them from git if the old evidence is wanted.

## Round after reviews 2 (contract 0.4.4, DEC-065 answer A; code review round 2 #3, #4, #8; security round 2 L-3) · 18 Nov 2026

Contract `docs/api/ipd.yaml` 0.4.4, ADR-027 A9 points 8 to 12, `docs/decisions/DEC-065-scan-with-no-codes-loaded.md`. Mockup screens: Nursing sheet (give sheet), Pharmacist check. The two NEW texts ("No code loaded", "No code is loaded for this medicine. Use Manual override: Medicine has no barcode.") and the client behaviour H1 (the sheet stays open, warn line, code box cleared, toggle offered but closed, no toast) are not in the mockup; the human signs them off.

### Test code that was repaired or tightened

| Item | What |
| --- | --- |
| Compile error | `Sah76ReviewRoundIT` line 121 had `"IPD\_%"` (an illegal escape that broke every backend test compile of the tree); now `"IPD\\_%"`. |
| Code review #3 | `IpdJourneySupport` helpers now name their trigger (`trg_medicine_order_version_guard_update`, `trg_medicine_order_guard_update`, `trg_order_hold_guard_update`, `trg_dose_administration_no_update_delete`, `trg_emergency_access_grant_guard_update`) instead of `disable trigger user`, so the deferred head-consistency check of `stampVersion` stays live. |
| Code review #8 | `MarSupport`: the orphan Javadoc moved onto `ageScanStops`; `Sah76ReviewRoundIT.ageStops` removed (it used `ageScanStops`); new `MarSupport.setScanStopAge(order, slot, seconds)` stamps the stop rows at an exact age on the database clock. |
| Old test, new contract | `Sah76DoseGiveIT.aCodeThatIsNotInTheDrugMasterIs...`: with no code loaded an unknown code is now no-code-loaded (0.4.4), so the test first loads one code of the drug and keeps asserting the mismatch (case d). |
| Security L-3 (a), (b) | R-01 repeats the audit check at the end (after the mismatch and every override audit, with the heparin code). R-03 probes the column-limited insert grant of `ipd.dose_scan_stop`: naming `tenant_id`, `stopped_at` or `id` is refused (42501). |
| Security L-3 (c) | `IpdJourneyIT` step 4 now types a pharmacist question and a doctor reply (each with the journey's marker); step 15 adds every free text the journey typed to the "never in a log, an audit row or an event" check: the override note of every give (`MarSupport.GIVEN_OVERRIDE_NOTE`), the PRN indication, the two hold reasons, the change reason, the question and the reply. The journey types no not-given OTHER note and no stop reason; the OTHER note is covered in `Sah76DoseGiveIT` (no log, no audit), the stop reason is not covered (see "Not covered"). |
| Fixed-clock safety (code review #4) | `Sah76NursingSupport.extendUndo` already used `greatest(clock_timestamp(), fixed clock) + 1 hour`. No other helper compares a database-stamped time with the fixed clock: `expireUndo`, `ageDose`, `ageScanStops` and `setScanStopAge` only move stamps back, and `backdate` / `stampVersion` / `stampHold` write explicit instants. Proof: the 21 classes that use `clockAt` were run on 18 Nov 2026 in this order. Before 08:05 IST (the fixed clock is "8:05 AM of today", later than the real time): Sah76ReviewRoundIT and Sah76NoCodeLoadedIT (07:20 to 07:25), IpdJourneyIT, Sah76DoseCorrectionIT, Sah76DoseGiveIT (07:25 to 07:33), AdmittedCardsIT, ApiAccessLogIT, DoseConcurrencyIT, DoseGiveIT, DoseOrderRaceIT, DoseOrderStateIT, DosePrivacyIT, DoseSecondNurseIT, DoseTableIT, DoseWithoutPepperIT, MarReadIT (07:50 to 08:04). After 08:05, so NOT proven for the early hours: MedicineRoundIT, ReleaseAuditHardeningIT, Sah76DoseDbIT, Sah76SettingsEffectIT, StaffPinColleagueHttpIT (they read no database-stamped time against the fixed clock, found by reading, not by a run). |

### New in Sah76ReviewRoundIT (18 tests now: the 13 of the last round and these 5)

| Id | Requirement | Test | Result |
| --- | --- | --- | --- |
| R-14 | override after a mismatch at the exact edge of the 15 minutes (database clock, rule "newer than 15 minutes"): 14:30 refused (renamed and re-aimed in round 3: was 14:59), 15:00 allowed, 15:01 allowed, a slot that was not aged stays refused; refusals audited once each, no note or code in any audit | aStopThirtySecondsInsideTheWindowStillCountsAndFromFifteenMinutesOnItDoesNot (was theFifteenMinuteWindowEndsAtExactlyFifteenMinutesOfTheDatabaseClock) | pass |
| R-15 | PRN start: 1 second before the start GIVEN and NOT_GIVEN are 409 dose-not-due with nothing written and the line NOT_STARTED; at the start the line is open and the dose is recorded (slot 10:00) | aPrnDoseIsRefusedOneSecondBeforeItsStartAndRecordedAtItsStart | pass |
| R-16 | PRN end: 1 second before the end the dose is recorded; at the end and 1 second after both outcomes are 409 dose-not-due with nothing written and the line ENDED | aPrnDoseIsRecordedOneSecondBeforeItsEndAndRefusedAtItsEnd | pass |
| R-17 | pharmacist review rows: `canQuery` is false for the OWNER who changed the order (SELF_CHANGED) and for the OWNER who placed it (SELF), true for the pharmacist on both rows | aReviewRowOfAnOrderTheOwnerChangedOrPlacedCannotBeQueriedByThatOwnerButByThePharmacist | pass |
| R-18 | Read-only: query allowed (200, audited), reply and acknowledge 409 ipd-readonly with nothing written; discharged patient: query, reply and acknowledge each 409 not-admitted with nothing written | queryIsAllowedInReadOnlyReplyAndAcknowledgeAreRefusedAndAllThreeAreRefusedForADischargedPatient | pass |

### New: Sah76NoCodeLoadedIT (contract 0.4.4, real database; each test runs on a normal order and on a high-alert order, 5 tests x 2 = 10)

The normal order runs the first transaction and then the locked one; the high-alert order adds the second nurse's PIN between them. A refused request from the high-alert path carries a wrong PIN on purpose and the test asserts the PIN state of the clinic is unchanged (the refusals come before the PIN is tried).

| Id | Requirement (contract 0.4.4 B1, D1, E1) | Test | Result |
| --- | --- | --- | --- |
| N-01 | (a) a code of the ordered drug (typed with spaces and lower case) records scanMethod SCAN and the matched code; no stop row; no refusal audit; one IPD_DOSE_GIVEN | aCodeOfTheOrderedDrugIsRecordedAsScanOnBothPaths | pass (both) |
| N-02 | (b) a known code of another drug while the ordered drug has no code: 409 medicine-mismatch, one MEDICINE stop row of the caller, one deny entry, one IPD_DOSE_MEDICINE_MISMATCH, no IPD_DOSE_REFUSED, no dose; the same nurse's override right after: 409 override-after-mismatch (contract text), one IPD_DOSE_REFUSED with that key, no second stop row | aKnownCodeOfAnotherDrugStopsEvenWhileTheOrderedDrugHasNoCodeAndTheSameNursesOverrideIsRefused | pass (both) |
| N-03 | (c) an unknown code while the ordered drug has no code: 409 no-code-loaded, title "No code loaded", the contract detail; no stop row, no deny entry, no clinical event at all, no mismatch audit; exactly one IPD_DOSE_REFUSED with exactly the keys orderId, admissionId, refusal (no-code-loaded), device; also for a text that is no code and one over 100 characters (3 refusals, 3 audit rows, still no stop row); the scanned texts (and the override note) appear in no audit row, event, dose row, stop row, log line or answer; the same nurse's empty-code override right after: 201 OVERRIDE, reason NO_BARCODE | anUnknownCodeWhileTheOrderedDrugHasNoCodeIsNoCodeLoadedWritesOneAuditRowAndTheOverrideIsAcceptedAtOnce | pass (both) |
| N-04 | after no-code-loaded a known code of another drug is still a STOP (stop row, mismatch audit) and the override is then refused for 15 minutes | afterNoCodeLoadedAKnownCodeOfAnotherDrugIsStillAStopWithTheFifteenMinuteRule | pass (both) |
| N-05 | (d) an unknown code, and a 120-character text, while the ordered drug has codes: 409 medicine-mismatch, MEDICINE stop rows, deny entry with the fixed words "a code not in the medicine list", typed code in no audit or event, the next override refused | anUnknownCodeWhileTheOrderedDrugHasCodesIsAMismatchWithTheStopRowAndTheRule | pass (both) |

Setup: the codes are inserted into `ipd.drug_code` as table owner with the prefix `QAS76` (`Sah76NursingSupport.codeOf`) and deleted after each test (the immutability trigger is switched off for that one delete); a precondition in each test fails if the shipped drug master already has a code of the ordered drug.

### Mocked Playwright (frontend/e2e): `mar-override.spec.ts` gains 4 tests (NCL-1 to NCL-4), run on desktop-1440 and phone-360

The mock server (`sah32-world.ts`) now follows 0.4.4: an unknown code while the ordered drug has no code (`world.codes`) is 409 no-code-loaded with no stop; a known code of another drug is still a mismatch with a stop; `MarOrder.hasMedicineCode` is derived from `world.codes`.

| Id | Behaviour (angular handoff H1) | Result |
| --- | --- | --- |
| NCL-1 | hint under the box before the scan; after the 409 the warn line `[data-testid=no-code-notice]` is inside the polite `#sf-codeok`, no mismatch line, the hint is not shown twice, code box cleared and focused, toggle visible with `aria-expanded=false`, no override reasons open, no toast, sheet open, wristband and statement kept, slot still DUE, no stop written | pass (both) |
| NCL-2 | the override then gives the dose at once: 201, the same clientKey as the refused call, `medicineCode` empty, override and wristband sent | pass (both) |
| NCL-3 | typing in the box clears the warn line; a code of another drug is still a STOP (mismatch line, no toggle, Given disabled, one stop) | pass (both) |
| NCL-4 | a drug that has a code keeps the mismatch for an unknown code (no warn line, no hint, no override) | pass (both) |

The D8 test of `mar.spec.ts` (the top toast must not cover the Close button on phone-360) is unchanged and is expected to stay red until angular fixes D8.

### Results of 18 Nov 2026 (three Maven runs and two Playwright runs; each run compiles all sources first, 3 to 12 minutes with the other session at work)

| Run | Classes | Tests | Result |
| --- | --- | --- | --- |
| Maven 1 | Sah76ReviewRoundIT 18, Sah76NoCodeLoadedIT 10 | 28 | 26 pass; the 2 failures were my own test bug (`overrideReason` is an object `{key, label}`, the test read it as text); fixed, rerun in Maven 2: 10 pass |
| Maven 2 | IpdJourneyIT 1, Sah76NoCodeLoadedIT 10, Sah76DoseCorrectionIT 13, Sah76DoseGiveIT 22 | 46 | 0 failures |
| Maven 3 | AdmittedCardsIT 13, ApiAccessLogIT 11, DoseConcurrencyIT 15, DoseGiveIT 35, DoseOrderRaceIT 6, DoseOrderStateIT 7, DosePrivacyIT 2, DoseSecondNurseIT 23, DoseTableIT 24, DoseWithoutPepperIT 2, MarReadIT 25, MedicineRoundIT 21, ReleaseAuditHardeningIT 10, RowLevelSecurityCatalogIT 15, Sah76DoseDbIT 11, Sah76SettingsEffectIT 12, StaffPinColleagueHttpIT 4 | 236 | 0 failures |
| Playwright 1 | mar-override.spec 24 (12 x 2 projects) | 24 | 24 pass |
| Playwright 2 | mar.spec 200, colleague-pin.spec 94, medicine-round.spec 130, mar-override.spec 24 (desktop-1440 and phone-360) | 448 | 447 pass, 1 fail: mar.spec "after an error the toast at the top does not cover the Close button of the give sheet" on phone-360 (D8, expected; passes on desktop-1440) |

No Maven run was spoiled by the Pharmacy session (no `sahajSampleData`, NoClassDefFound or missing-bean error). `validate all` was not run (the tech lead asked for it later).

## Round 3 (code review round 3 #2, #3; security round 3 N-5) · 18 Nov 2026

| Finding | Change | Test | Result |
| --- | --- | --- | --- |
| Code #2 (R-14) | The 14:59 case was racy (a 1 s margin on a loaded machine). Test renamed `aStopThirtySecondsInsideTheWindowStillCountsAndFromFifteenMinutesOnItDoesNot`; the inside case now ages the stop to 870 s (14:30); 900 s and 901 s stay as "outside". The comments say plainly that `>` against `>=` at exactly 900.000 s cannot be told apart by a test, so it is not claimed. `MarSupport.setScanStopAge` Javadoc now says "870 s (a 30 s margin) is inside; 900 s or more is outside". | Sah76ReviewRoundIT R-14 | pass |
| Code #3 (QAS76 sweep) | `Sah76NursingSupport.sweepQaMedicineCodes(DbAccess)` (migrator connection, trigger `trg_drug_code_no_update_delete` off and back on in `finally`, only the `QAS76` prefix). Called from a new `@BeforeEach` and from the existing `@AfterEach`; covers Sah76NoCodeLoadedIT, Sah76ReviewRoundIT, Sah76DoseGiveIT, Sah76DoseDbIT, Sah76DoseCorrectionIT, Sah76SettingsEffectIT, Sah76SettingsIT. `Sah76MigrationIT.theGlobalReferenceDataIsSeededAndTheCodeListShipsEmpty` calls it first, so a QA code left by a killed run no longer fails it, while a real code still does. | Sah76MigrationIT (7 tests) | pass |
| Security N-5 | New N-06 in Sah76NoCodeLoadedIT, both paths (pcm normal, hep high-alert): the code of another drug typed as `" " + code.toLowerCase() + "\r\n"` while the ordered drug has no code is 409 medicine-mismatch; one MEDICINE stop row of the caller; one IPD_DOSE_MEDICINE_MISMATCH; no IPD_DOSE_REFUSED; one more deny event; no dose row; the second nurse's PIN never tried (a wrong PIN is sent and the PIN state is unchanged). | aKnownCodeOfAnotherDrugTypedWithSpacesLowerCaseAndALineBreakIsStillAMismatchOnBothPaths | pass (both) |

Sah76NoCodeLoadedIT is now 6 tests x 2 paths = 12. The D8 test in `frontend/e2e/mar.spec.ts` was not touched.

Run (one Maven run, `-Dit.test=Sah76ReviewRoundIT,Sah76NoCodeLoadedIT,Sah76MigrationIT,Sah76DoseGiveIT`): Sah76DoseGiveIT 22, Sah76MigrationIT 7, Sah76NoCodeLoadedIT 12, Sah76ReviewRoundIT 18; 59 tests, 0 failures, BUILD SUCCESS. No Pharmacy spoilage seen.

Not done in this round: the early-hours (before 08:05 IST) run of the five fixed-clock classes (MedicineRoundIT, ReleaseAuditHardeningIT, Sah76DoseDbIT, Sah76SettingsEffectIT, StaffPinColleagueHttpIT) is not possible after 08:05 IST; it stays open.

### Still not covered after this round

* Stop reason text (stop of an order) is typed by no journey step; `IpdJourneyIT` step 15 cannot check it (the stop is only probed after discharge, 409 not-admitted).
* PRN boundaries are tested for the start and end of the order; the PRN 15-minute override rule at the exact edge (R-14 runs on a scheduled order only).
* The early-hours (before 08:05 IST) run of MedicineRoundIT, ReleaseAuditHardeningIT, Sah76DoseDbIT, Sah76SettingsEffectIT and StaffPinColleagueHttpIT.
* CorsPreflightIT, e2e for the Orders tab and notices, the layout-matrix project, the rewrite of `medicine.spec.ts` (D2).
