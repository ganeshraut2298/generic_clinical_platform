# Test plan SAH-36 · IPD Admitted patient cards: `GET /admissions` (backend)

Story: `docs/stories/SAH-36.md` (53 acceptance criteria; the cards, toolbar, search, ward filter, paging, focus and the 60-second reload are frontend / browser behaviour). Decisions: ADR-013 (one role-gated batch read in `GET /admissions`, list-level card abilities, no record-access row), DEC-021, DEC-023. Contract: `docs/api/ipd.yaml` (`AdmissionList`, `AdmittedPatientRow`, `AdmittedClinicalFlags`, `AdmittedCardAbilities`, `WardOption`). Database note: none for SAH-36 beyond ADR-013 "Data needed" (no migration); the plans are recorded below.

This is the **backend** part: integration tests on the real `sahaj_test` database as `sahaj_app`, MockMvc through the full filter chain, profile `test`. The browser part is written by the browser QA lane ("e2e" below).

| Class | Tests | What |
| --- | --- | --- |
| `AdmittedCardsIT` | 12 | the new row facts and list facts (UHID, ward id, room name, `asOf`, `wards`, `card`), abilities by role and in Read-only, the raw JSON of a front desk answer, role read again from the membership (role change on the second read, inactive, roles outside the four, no token), another clinic, 300 rows in one request, the escalation flag OPEN to ACK to none and re-opened (minutes equal to the Safety board's), vitals due at 240 / 241 minutes, three allergy states, room name null when rooms are off, no record-access row / audit / log text, the table-view fields that stay |
| `RoundSupport` | - | shared set-up (see SAH-35): the clock seam that moves `asOf` of the list |
| `QueryPlansIT` | 1 | plans of the placement read (room left join) and of the latest-vitals lateral on generated volume (below) |

Existing tests changed by design (nothing was weakened):
- `AdmissionDepositsIT.listIsNewestFirstWithCountsRowFieldsAndCanAdmitByRole`: the row assertions gain `uhid`, `wardId`, `roomName`, `wards`, `card`, `asOf`, and the per-role loop asserts that `clinical` is present for OWNER, NURSE, DOCTOR and absent for FRONT_DESK.
- `SafetyBoardIT` (lines about the list for FRONT_DESK and the 403 roles) needed no change: its assertions (no `ewsScore`, no `pastTarget`, safety null, 403 for BILLING / PHARMACIST / VISITING_DOCTOR, token DOCTOR with membership FRONT_DESK) hold unchanged.

## Coverage: acceptance criterion to test

| AC | Case | Test | Result |
| --- | --- | --- | --- |
| 1, 2, 5-8, 14-22 | Cards / Table, toolbar, card layout, search, ward filter, paging | frontend unit and e2e (device-only behaviour) | e2e |
| 3 | Card facts from the server: `uhid`, `wardId`, `roomName` (UHID; "—" is a client rule), ward name, admitted-at, day number, doctor name | AdmittedCardsIT.everyRowCarriesUhidWardIdAndRoomName...; AdmissionDepositsIT list test | pass |
| 9 | Empty list: "No one is admitted" | existing `AdmissionDepositsIT` (empty list) / e2e | e2e |
| 10 | Admitted tab number = all admitted | unchanged `bedsOccupied`; AdmittedCardsIT.threeHundredAdmittedPatients... (`bedsOccupied` 300) | pass |
| 11 | IPD Off: 403 `module-off`; roles without the list (BILLING, PHARMACIST, VISITING_DOCTOR, COUNSELLOR) 403 | MedicineRoundIT.withTheModuleOffEveryCallAnswers403ModuleOff (list included); AdmittedCardsIT.aRoleChangedBetweenTwoReadsAppliesOnTheSecondOne... | pass |
| 12 | Another clinic's patients never listed | AdmittedCardsIT.anotherClinicsPatientsNeverAppear... | pass |
| 13 | Nurse and front desk both see every admitted patient (no narrowing) | AdmittedCardsIT.threeHundredAdmittedPatients... (nurse and front desk both 300) | pass |
| 23-25 | Going home today / Deposit low stay on the row for all four roles; the card flag follows `card.depositLowFlag` (OWNER, FRONT_DESK only) | AdmittedCardsIT.theDepositLowAndGoingHomeFlagsStayOnTheRow...; theCardAbilitiesFollowTheRole... | pass |
| 29 | OPEN escalation: status, whole minutes, target 15 / 60, past target; same numbers as the Safety board | AdmittedCardsIT.theEscalationGoesOpenToAcknowledgedToNone... | pass |
| 30 | ACK is "Doctor reviewing"; closed gives no flag | AdmittedCardsIT.theEscalationGoesOpenToAcknowledged... | pass |
| 31 | Latest score and risk of the latest record; none for no vitals | AdmittedCardsIT.vitalsAreDueOnlyWhenMoreThan240WholeMinutes... | pass |
| 32 | Vitals due: not at 240:00 and 240:59, due at 241:00, due with no vitals | AdmittedCardsIT.vitalsAreDueOnlyWhenMoreThan240WholeMinutes... | pass |
| 33 | Allergy RECORDED (text), NONE_KNOWN and NOT_RECORDED (no `text` key) | AdmittedCardsIT.theAllergyStatusIsNoneKnownNotRecordedOrRecorded... | pass |
| 34 | FRONT_DESK: no `clinical`, `escalation`, `latestEws`, `vitalsDue`, `allergy`, `dosesLate`, `dosesDue`, `changeToken`, `waitingMinutes` anywhere in the raw JSON; `safety` null; no allergy text, no score, no escalation id | AdmittedCardsIT.aFrontDeskAnswerHasNoClinicalKeyAtAll... | pass |
| 35 | Flag order / folding / red outline | frontend unit (client rules) | e2e |
| 36, 37 | Reload every 60 seconds; minutes come from the server clock (`asOf`) | `asOf` = the database clock asserted in AdmittedCardsIT.everyRowCarriesUhid...; reload itself e2e | pass |
| 38 | Stale actions rejected | existing endpoint ITs (unchanged) | existing |
| 39 | No record-access row per reload; no patient data in a log; no audit | AdmittedCardsIT.theListWritesNoRecordAccessRowOrAuditAndNoPatientDataReachesALog; MedicineRoundIT.theBoardTheSummaryAndTheAdmittedListWriteNoRecordAccessRow... | pass |
| 40 | Dose overdue flag not built: `clinical` has exactly four keys | AdmittedCardsIT.aFrontDeskAnswerHasNoClinicalKey... (key set of `clinical`) | pass |
| 41 | Role changed on the server applies on the next read (NURSE to FRONT_DESK, to BILLING, to DOCTOR); inactive membership 403 | AdmittedCardsIT.aRoleChangedBetweenTwoReadsAppliesOnTheSecondOne... | pass |
| 42 | 300 admitted patients in one request (and the time of that request) | AdmittedCardsIT.threeHundredAdmittedPatientsComeInOneRequest... | pass |
| 43-47, 51 | Card actions: `card.nursing / emr / transfer / discharge` per role; Read-only removes Transfer and Discharge | AdmittedCardsIT.theCardAbilitiesFollowTheRoleAndReadOnly... | pass |
| 48-50, 52 | Opening the sheets, focus, stale sheet | frontend unit / e2e; the sheet endpoints are unchanged (`BedTransferIT`, `DischargeIT`, `VitalsAccessIT`, `MedicineAccessIT`) | e2e |
| 53 | Opening Nursing / EMR from a card writes the same access row as today (the card writes none) | unchanged endpoints; the list writes none | pass |
| 54 (R36-5, ADR-013 D15) | A patient merged away after the admission: the Front desk card (identity read through `PatientLookupPort.findAllCurrent`, no `PatientClinicalPort`) shows the same UHID (the kept record's) and the same name as the Owner, Doctor and Nurse cards; the raw JSON of the Front desk answer still holds none of the nine clinical keys and `safety` is null | AdmittedCardsIT.aPatientMergedAwayAfterTheAdmissionShowsTheSameNameAndUhidOnTheFrontDeskCard... | pass |

R36-5 observation for the reviewers: after a merge the clinical roles' card shows the kept record's UHID but the name of the admitted (merged-away) record, and the Front desk card is identical to it. The test asserts equality across the four roles and the kept UHID, not the name of the kept record, because `PatientClinicalPort` names id, UHID and allergy text as the merge-resolved fields.

Also covered (ADR-013 D3, D4, D5): `roomName` is null (key present) when the clinic switched rooms off and the stored room name when on; the table-view fields (`runningBillPaise`, `depositLow`, `goingHomeToday`) stay on every row for all four roles; the `safety` object keeps its shape; the HTTP compression setting (`server.compression.enabled=true`, `application/json`, 2 KB) is asserted, and MockMvc cannot show the gzip itself (not run over a real connector).

Not testable through the API (reported, not skipped): a patient record that cannot be found shows `uhid` null and the name "Patient" (ADR-013 D2). The admission has a foreign key to `patient.patient`, so the data fault cannot be produced without breaking the database; it is covered by the java-developer's unit tests of the mapping.

## Query plans (ADR-013 "Data needed")

Measured on 7 Nov 2026 by `QueryPlansIT` on the data described in `docs/test-plans/SAH-35.md` (40 ADMITTED patients of one clinic, **178,000 vitals rows** (4,000 per admitted patient in the measured clinic), generated by the table owner in one transaction that was **rolled back**; `analyze` run inside it; plans taken as the table owner with row level security off on the touched tables, the clinic in every statement; the migrator may not switch to `sahaj_app`).

| Read | Plan (key lines) | Execution |
| --- | --- | --- |
| R1 placement with the room left join | admission (bitmap on `ux_admission_tenant_bed_admitted`) hash-joined to the bed and ward (`uq_ward_tenant_id`, `ix_bed_tenant_ward`, both by tenant), `Nested Loop Left Join` to `ipd.room` (materialised, 1 row), the deposit sub-total as a sub-plan on `ix_deposit_tenant_admission`, one small quicksort of 40 rows | 1.8 ms, 40 rows |
| R4 latest vitals per admitted admission (`cross join lateral ... order by recorded_at desc, id desc limit 1`) | for each of the 40 admissions **one probe of `ix_vitals_tenant_admission_recorded`** (`Index Cond: tenant_id, admission_id`), then an **Incremental Sort** (`Presorted Key: x.recorded_at`) that reads **2 index rows per admission** (the second row only proves there is no tie on `recorded_at`; the index does not hold `id`), no sort of the 4,000 rows of an admission, no sequential scan | 4.2 ms, 40 rows, shared hit 104 read 61 |

Result against the target of ADR-013 "Data needed" (list read under 100 ms at 300 admitted patients; R4 one index probe per admission, no sort of all vitals of an admission, no sequential scan of `ipd.vitals`): **met**. The conditional index `ix_vitals_tenant_admission_recorded_id (... recorded_at desc, id desc) include (ews_score, ews_risk)` is **not needed**: no heap-heavy or sort-heavy behaviour was seen. Also measured end to end through MockMvc: `GET /admissions` for 300 admitted patients in one request (`AdmittedCardsIT.threeHundredAdmittedPatients...`, asserted under 5 seconds; the full-stack time includes the 300 patient lookups, JSON writing and the test machine under load). Not measured: the R3 escalation read (unchanged SAH-33 query, partial index `ux_ews_escalation_tenant_admission_open`).

## Gate run (`node scripts/validate.mjs backend`, 7 Nov 2026, 04:38 to 05:15 IST)
Unit tests 3,221 run, 0 failures. Integration tests 1,650 run in 121 classes, **7 failures, 0 errors, 26 skipped** (the 26 are the existing precondition skips of the dev sample admin tests, which need a database role that does not exist on this machine). The 7 failures are the 6 notice tests of `StaffPinColleagueHardeningIT` (defect B1 in `docs/test-plans/SAH-38.md`) and `QueryPlansIT` (defect B2 in the same file); nothing else failed. An earlier gate run the same evening ended with 335 errors "remaining connection slots are reserved for roles with the SUPERUSER attribute": the QA support class had added a tenth Spring context (a tenth pool of 10 connections) to nine existing ones and used up `max_connections` (100); that was fixed in the test support (see `docs/test-plans/SAH-35.md`) and the run above is the one that counts.

## Re-run after the fix pass (7 Nov 2026, 05:38 to 06:17 IST, `node scripts/validate.mjs backend`)
Unit tests **3,222 run, 0 failures, 0 errors, 0 skipped**. Integration tests **1,650 run in 121 classes, 1 failure, 0 errors, 26 skipped** (the 26 are the existing dev sample admin precondition skips). Nothing of this story failed. The one red was `QueryPlansIT` on a SAH-38 notice-count plan (a planner choice between two near-equal indexes at an extreme test parameter; fixed in the test and 3 of 3 targeted runs green afterwards; details and the full-gate caveat in `docs/test-plans/SAH-38.md`). The 7 reds of the previous gate (6 notice tests in `StaffPinColleagueHardeningIT`, and `QueryPlansIT` Team plan) are gone. The pool is now 5 per context (`application-test.yml`, `SAHAJ_DB_POOL_SIZE`): all concurrency ITs passed (`BedTransferConcurrencyIT` 6/6, `DischargeConcurrencyIT` 6/6, `DoseSecondNurseIT` 22/22, `AppointmentDoubleBookingIT` 2/2), no `waiting` connection in any pool statistic line, no connection-slot error. The SAH-35 and SAH-36 reads in `QueryPlansIT` (Q1, Q2, R1, R4) are index plans as before, all under 250 ms. `SafetyService.banner(Map)` was deleted by the java-developer; no integration test used it.
