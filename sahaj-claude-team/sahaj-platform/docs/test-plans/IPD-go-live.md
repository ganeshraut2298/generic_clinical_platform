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
