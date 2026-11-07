# Test plan SAH-35 · Medicine round on the Safety board (backend)

Story: `docs/stories/SAH-35.md` (24 acceptance criteria). Decisions: ADR-012 (one clinic-wide read through `DoseSlots`, dose counts and change token in `SafetyCounts`, Give through the existing MAR read), DEC-022 (open points, proceeding on the proposals), DEC-023. Contract: `docs/api/ipd.yaml` (`SafetyBoard.medicineRound`, `SafetyCounts.dosesDue/dosesLate`, `GET /safety`, `GET /safety/summary`, `GET /admissions` `safety.counts`). Database note: `docs/database/SAH-35.md` (no migration; plan check was NOT MEASURED there).

This is the **backend** part: integration tests on the real `sahaj_test` database as `sahaj_app`, MockMvc through the full filter chain, profile `test`. The browser part (tile, panel, row texts, 60-second refresh, Give from a row, focus, 44 px, loading and error texts) is written by the browser QA lane and is marked "e2e" below.

| Class | Tests | What |
| --- | --- | --- |
| `MedicineRoundIT` | 20 | the Medicine round on the Safety board: panel keys and row keys, the 60 / 61 minute edges on the database clock (10 clock points, MAR tab count compared at each), exclusions (Pending, Rejected, Stopped, discharged, each recorded outcome, slot before the order started), order and tie-break, the invariant `dosesDue + dosesLate = sum of Mar.doseCount`, the change token worked out in the test (empty, DUE, LATE, only `lateHours` growing, a dose recorded), the same count with a different token, two nurses (sequential and parallel), another clinic, roles / membership re-read / module Off / Read-only, no record-access row, no log text, 201 slots (cap 200, `truncated`, full counts and token) |
| `RoundSupport` | - | shared set-up (not tests): a second patient in a ward; the token computed from the rule of ADR-012 D3; bulk ACTIVE orders written by the table owner for the volume case |
| `QueryPlansIT` | 1 | `EXPLAIN (ANALYZE, BUFFERS)` of Q1 and Q2 on generated volume in one rolled-back transaction (plans below) |

Existing tests changed by design (nothing was weakened):
- `MarSupport` (shared by all the MAR / dose test classes) now also spies `EscalationBoardRepository.databaseNow()` (the clock of the Safety board, its summary and the Admitted list) and `clockAt` moves it together with the two MAR seams. It is in the shared context on purpose: a first version put the extra spy in `RoundSupport`, which made a tenth Spring context and a tenth connection pool of 10, and the full gate then hit `max_connections` (100) of the local PostgreSQL ("remaining connection slots are reserved for roles with the SUPERUSER attribute"). The suite now has nine contexts again, which is still close to the limit (see the handoff).
- `SafetyBoardIT.theSummaryHoldsCountsOnlyAndNoNameScoreOrText`: the summary has 6 fields (open, pastTarget, dosesDue, dosesLate, changeToken, asOf); still numbers and a token only.
- `SafetyChangeTokenIT.theTokenHoldsNoNameScoreOrIdentifier`: `counts` now has 6 keys (open, pastTarget, dosesDue, dosesLate, changeToken, asOf). The other token tests of that class seed no ACTIVE order, so the SAH-33 token is still the whole token there (the rule "no dose in the window gives the SAH-33 token" is itself asserted in `MedicineRoundIT.theChangeTokenHoldsTheDoseLines...`).

## How time, people and volume are handled
- **Clock.** The board decides Due / Late with `asOf` read through `EscalationBoardRepository.databaseNow()`; the MAR (used for the invariant) and the dose endpoint read it through `MedicineOrderRepository.databaseNow()`. `MarSupport.clockAt` moves both (and the vitals clock) to one fixed instant; the spies change nothing else.
- **Orders** are placed and verified through the real endpoints and backdated to the day before the test date (`MarSupport.activeOrder`). The 201-slot case writes 67 ACTIVE `tds` orders (5 admissions) directly as the table owner with the insert guard off inside one committed transaction (a state the API cannot write), clock at 8:05 PM IST: 134 LATE + 67 DUE = 201 slots.
- **Texts and key names** come from the contract, not from the code.

## Coverage: acceptance criterion to test

| AC | Case | Test | Result |
| --- | --- | --- | --- |
| 1 | Panel for OWNER, DOCTOR, NURSE with `canGive` / `giveLockReason`; subtitle clock = `SafetyBoard.asOf` = the database clock | MedicineRoundIT.theBoardHoldsThePanelWithTheRowsTheFieldsAndTheTileFiguresForTheThreeBoardRoles | pass |
| 1 | Panel position, icon, subtitle text, IPD Trial / On / Read-only visible | not an API behaviour: e2e | e2e |
| 2 | Listed from 60 minutes ahead to 60 whole minutes after; LATE from 61 (10 clock points incl. 60 min 59 s either side) | MedicineRoundIT.aSlotIsListedFromSixtyMinutesAheadToSixtyWholeMinutesAfter... | pass |
| 2 | Not listed: Pending, Rejected, Stopped, discharged, GIVEN / HELD / REFUSED slot, slot before the order started | MedicineRoundIT.pendingRejectedAndStoppedOrdersDischargedPatients... | pass |
| 3 | Row: name, high-alert class, patient / bed / slot, `lateHours` (61 to 119 min is 1, 125 is 2), exact key sets | MedicineRoundIT.theBoardHoldsThePanel... | pass |
| 3 | Pill text, bold name, time as "8 AM" | not an API behaviour: e2e | e2e |
| 4 | LATE first, then earlier slot, then bed, drug name, order id; stable on a second read | MedicineRoundIT.lateRowsComeFirstThenTheEarlierSlot... | pass |
| 5 | "No doses due right now." (empty `rows`, counts 0); a clinic with no order | MedicineRoundIT.whenNoDoseIsDueTheRoundIsEmptyAndTheFiguresAreZero | pass |
| 6 | 201 slots: 200 rows, `truncated`, counts 134 / 67, token over all 201, summary and list agree, MAR counts add to 201; the dropped slot is a DUE one | MedicineRoundIT.twoHundredAndOneQualifyingSlots... | pass |
| 7 | No name, bed or medicine in a log; reads write no audit | MedicineRoundIT.noPatientNameBedOrMedicineReachesTheLogsOrTheAuditWhenTheBoardIsRead | pass |
| 8 | Loading and failure states | e2e (the round is part of `GET /safety`, ADR-012 D8) | e2e |
| 9 | Tile figures: `dosesDue + dosesLate`, `dosesLate` for "N overdue" | MedicineRoundIT.theBoardHoldsThePanel...; theTabNumberInputsAre... | pass |
| 10 | Tab number inputs: `open + dosesLate` (+ pharmacist waiting for OWNER), DUE not added | MedicineRoundIT.theTabNumberInputsAreOpenPlusLateDoses... | pass |
| 11 | A dose that becomes Due or turns Late appears / changes on the next read; the token changes (DUE to LATE) and does not on `lateHours` growth | MedicineRoundIT.theChangeTokenHoldsTheDoseLines...; oneDoseRecordedWhileAnotherEntersTheWindow... | pass |
| 11 | 60-second reload, failed-reload notice | e2e | e2e |
| 12 | Summary carries `dosesDue` / `dosesLate` and no name, medicine or bed; the Admitted list carries the same counts and token | MedicineRoundIT.theBoardHoldsThePanel... (summary); theTokenOfTheAdmittedList... | pass |
| 13 | Figures change only after the server answered | e2e (the server is the only source in these tests) | e2e |
| 14 | Give opens the SAH-32 sheet; every check and audit of SAH-32 applies | existing `DoseGiveIT`, `DoseSecondNurseIT`, `MarReadIT` (unchanged endpoints); sheet itself e2e | e2e |
| 15 | After Given the row leaves the list and the counts and token change | MedicineRoundIT.whenOneNurseRecordsADoseItLeavesTheOthersList...; theChangeTokenHoldsTheDoseLines... | pass |
| 16 | Another nurse recorded it first: 409 `dose-recorded`, sequential and in parallel (exactly one 201 and one 409, one dose row) | MedicineRoundIT.whenOneNurseRecordsADose...; twoNursesGivingTheSameRowAtTheSameMoment... | pass |
| 16 | Order stopped / patient discharged before the tap | `MarReadIT`, `DoseGiveIT` (409 `order-state`, `not-admitted`); the pre-check texts e2e | e2e |
| 17 | Failed or lost answer keeps the sheet and what was typed | e2e | e2e |
| 18 | Read-only: list readable, `canGive` false, `giveLockReason` IPD_READONLY, POST 409 `ipd-readonly`; DOCTOR's reason is ROLE first | MedicineRoundIT.inReadOnlyTheListStaysReadable... | pass |
| 19 | Accessible name, 44 px, focus | e2e | e2e |
| 20 | OWNER, DOCTOR, NURSE read; DOCTOR no Give (`ROLE`); PHARMACIST, FRONT_DESK, BILLING, VISITING_DOCTOR, COUNSELLOR 403 on `/safety` and `/safety/summary`; role re-read from the membership (NURSE changed to FRONT_DESK); no token 401 | MedicineRoundIT.everyRoleOutsideTheBoardRoles...; aDoctorSeesTheRows... | pass |
| 21 | IPD Off: 403 `module-off` (OWNER; also NURSE, DOCTOR 403); another clinic's orders, patients, doses never listed or counted; the dose and MAR calls on another clinic's ids are 404 | MedicineRoundIT.withTheModuleOffEveryCallAnswers403ModuleOff; anotherClinicsOrdersPatientsAndDosesAreNeverInTheList... | pass |
| 22 | DOCTOR calling the dose endpoint: 403 "Only nurses and the clinic owner can give medicines.", no dose row | MedicineRoundIT.aDoctorSeesTheRowsButCannotGive... | pass |
| 23 | `/safety`, `/safety/summary`, `/admissions` write no `patient.record_access` row (6 reads x 3 rounds); the MAR open writes exactly one | MedicineRoundIT.theBoardTheSummaryAndTheAdmittedListWriteNoRecordAccessRow... | pass |
| 24 | No text says anyone was alerted | frontend / e2e (no server text) | e2e |
| 25 (R35-1) | One damaged order must not blank the clinic's board: an ACTIVE order whose drug is not in the catalogue is left out, one WARN line with the clinic id, the order id and the cause word only; `GET /safety`, `GET /safety/summary` and `GET /admissions` still answer 200 with the healthy order; figures follow the healthy order; no patient name, mobile number, UHID, drug text or key in the log | MedicineRoundIT.anOrderWhoseDrugIsNotInTheCatalogueIsLeftOut... | pass |

R35-1 note: the database cannot hold an order with a drug that is not in `ipd.drug` (foreign key) or a frequency outside od / bd / tds / q8 (check), so the damage is made on the application side. The test replaces the cached `MedicineCatalogue` (its private `loaded` field) with one that lacks `hep`, reads the three endpoints, and puts the original back in `finally`. The unknown-frequency branch cannot be reached from a real row at all and is covered by `MedicineRoundReaderTest` (unit) only.

Additional rules of the design notes (ADR-012) covered: Q1 and Q2 run on PostgreSQL under row level security for the first time in every test above, and the `cast(:admissionIds as uuid[])` literal binding of Q2 is exercised in each of them; the invariant `dosesDue + dosesLate = sum of Mar.doseCount` is asserted at 10 clock points and over two patients at 6 more; the change token rule of D3 is worked out in the test and compared for equality with the application's token.

## Query plans (docs/database/SAH-35.md "How to measure")

Measured on 7 Nov 2026 by `QueryPlansIT` (the plan of the database note that was "NOT MEASURED"). Data: clinic A with 40 ADMITTED patients and 560 ACTIVE orders (14 drugs each), plus **60,200 STOPPED orders** on the same 40 admissions (a harsher case than "STOPPED rows of discharged stays"), 170,000 dose rows (about 270 of them today), 178,000 vitals rows, other rows of a second clinic; everything generated by the table owner inside one transaction and **rolled back** (row counts before and after are asserted equal); `analyze` run inside it. Taken as the table owner with row level security switched off on the touched tables for that transaction and `tenant_id = :t` in the statement text: the migrator may not `SET ROLE sahaj_app` (`as_sahaj_app=0`), so the policy qualification is not in the plan. No DISCHARGED admissions were generated (each needs its own patient row), so the 20,000 discharged rows of the database note are not in this measurement. The planner reads the ADMITTED admissions through the partial index `ux_admission_tenant_bed_admitted` (it holds only ADMITTED rows), so discharged rows would not be read either.

| Read | Plan (key lines) | Execution |
| --- | --- | --- |
| Q1 orders of admitted patients | Hash join of `bed` (bitmap on `ix_bed_tenant_category`) and `admission` (bitmap on `ux_admission_tenant_bed_admitted`, 40 rows), then **Nested Loop with `Index Scan using ux_medicine_order_tenant_admission_drug_live`** (14 rows per probe, `Filter: status = 'ACTIVE'`, no "Rows Removed by Filter": the 60,200 STOPPED rows are never read, because the partial live index holds only PENDING and ACTIVE) | 1.3 ms, 560 rows, shared hit 163 read 13 |
| Q2 today's dose records | **`Index Scan using ix_dose_administration_tenant_admission_slot`**, Index Cond: `tenant_id`, `admission_id = ANY('{...}')` (40 ids) and the `slot_at` range (the `cast(:admissionIds as uuid[])` literal works), no sort | 1.0 ms, 268 rows, shared hit 90 read 48 |

Result against the pass rule of `docs/database/SAH-35.md`: index scans only, no sequential scan of `ipd.dose_administration` or `ipd.medicine_order`, far under 50 ms. **The conditional index `ix_medicine_order_tenant_active` is not needed** (the existing partial unique index already skips STOPPED rows). Full text of both plans: `backend/target/qa-plans/plans.txt` after a run of `QueryPlansIT` (a build output, not kept).

## Gate run (`node scripts/validate.mjs backend`, 7 Nov 2026, 04:38 to 05:15 IST)
Unit tests 3,221 run, 0 failures. Integration tests 1,650 run in 121 classes, **7 failures, 0 errors, 26 skipped** (the 26 are the existing precondition skips of the dev sample admin tests, which need a database role that does not exist on this machine). The 7 failures are the 6 notice tests of `StaffPinColleagueHardeningIT` (defect B1 in `docs/test-plans/SAH-38.md`) and `QueryPlansIT` (defect B2 in the same file); nothing else failed. An earlier gate run the same evening ended with 335 errors "remaining connection slots are reserved for roles with the SUPERUSER attribute": the QA support class had added a tenth Spring context (a tenth pool of 10 connections) to nine existing ones and used up `max_connections` (100); that was fixed in the test support (see `docs/test-plans/SAH-35.md`) and the run above is the one that counts.

## Re-run after the fix pass (7 Nov 2026, 05:38 to 06:17 IST, `node scripts/validate.mjs backend`)
Unit tests **3,222 run, 0 failures, 0 errors, 0 skipped**. Integration tests **1,650 run in 121 classes, 1 failure, 0 errors, 26 skipped** (the 26 are the existing dev sample admin precondition skips). Nothing of this story failed. The one red was `QueryPlansIT` on a SAH-38 notice-count plan (a planner choice between two near-equal indexes at an extreme test parameter; fixed in the test and 3 of 3 targeted runs green afterwards; details and the full-gate caveat in `docs/test-plans/SAH-38.md`). The 7 reds of the previous gate (6 notice tests in `StaffPinColleagueHardeningIT`, and `QueryPlansIT` Team plan) are gone. The pool is now 5 per context (`application-test.yml`, `SAHAJ_DB_POOL_SIZE`): all concurrency ITs passed (`BedTransferConcurrencyIT` 6/6, `DischargeConcurrencyIT` 6/6, `DoseSecondNurseIT` 22/22, `AppointmentDoubleBookingIT` 2/2), no `waiting` connection in any pool statistic line, no connection-slot error. The SAH-35 and SAH-36 reads in `QueryPlansIT` (Q1, Q2, R1, R4) are index plans as before, all under 250 ms. `SafetyService.banner(Map)` was deleted by the java-developer; no integration test used it.
