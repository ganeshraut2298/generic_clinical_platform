# GO-LIVE · IPD test-gap analysis (read-only, 17 Nov 2026)

Reviewer: code-reviewer (read and grep only; no Maven, npm or browser). Saved by the tech lead. Context: DEC-058 (IPD live with real patients next week). Baseline: 5,462 unit, 2,972 IT in 219 classes, gate 16 Nov.

**Verdict:** strong per endpoint (77 paths, all handlers scope-marked via `IpdScopeCoverageTest`; ~120 IT classes on real `sahaj_test` with RLS, roles, cross-clinic, idempotency, races). Weak on whole-flow and real-stack proof.

## 0. Findings
| # | Finding | Why it matters |
|---|---|---|
| F1 | No IPD journey test in any layer (only OPD `WalkInTokenJourneyIT`, `RealSessionJourneyIT`). | Wrong-patient, wrong-dose and money bugs sit between steps. |
| F2 | All 1,372 Playwright cases mock the API (`page.route`); SAH-76 mocks predate 0.4.0; SAH-76 UI has no e2e. | Contract drift and browser-only faults (CORS preflight with `X-Sahaj-Device`, cookies, polling) ship unseen. |
| F3 | SAH-76 #1 still open: `MedicineAssembler.java:159-160` passes corrected doses to `ExecutionSlots.of`. No test. | EMR "N late / Next due" wrong after undo or recorded in error. |
| F4 | Giving on ON_HOLD / QUERIED / changed-version orders proven only with fakes or SQL-text asserts. | Wrong-dose path. |
| F5 | `DoseConcurrencyIT` covers give vs stop and give vs discharge only. Missing: give vs hold, give vs change, undo vs re-give, undo vs recorded-in-error, give vs transfer-out of a scoped nurse. | Double dose / out-of-scope dose. |
| F6 | PRN start and end not enforced (DEC-057 item 3 decided; not built). | PRN givable outside its dates. |
| F7 | Money tested per step, not across a stay; no parallel-deposit test. | Bill and deposit errors. |
| F8 | Seven paths have no frontend client: `ward-assignments` (2), `ward-scope`, `nursing-settings`, `nursing-profiles` (2), `wards/{id}/nursing-profile`. | Go-live operations gap: how does the owner set them? |
| F9 | SAH-76 screens not in `docs/design/screen-map.md`, so QA-2 cannot write their specs (strict rule 1). | Human step. |
| F10 | ~30 `assumeTrue(day.equals(istToday()))` in BedTransferBillIT, DischargeIT, DischargeConcurrencyIT, DischargeFollowUpIT, BedTransferIT: money tests skip silently near IST midnight. | Green run with money tests skipped. |

Go-live items tests cannot close: EWS bands sign-off (OQ-30-2, OQ-30-6); PIN lockout S1, S2 (DEC-018); owner-as-prescriber S6 and catalogue/allergy sign-off (DEC-017); billing link OQ-27-4.

## 1. Coverage map (Y yes, P partial, N not found; e2e is mocked everywhere)
| Area | Unit | IT happy | IT refusal | IT conc | e2e | Missing |
|---|---|---|---|---|---|---|
| Setup: wards, beds, charges, rules, settings | Y | Y IpdSetupIT (36) + rules ITs | Y | P | N | ward/bed removal with live admissions; rate change while admitted |
| Card setup, packages | Y | Y CardSetupIT (33), Package*IT | Y | P | Y | OK |
| Admission and deposits | Y | Y AdmissionDepositsIT (31), AdmissionChargesIT (22) | Y | Y admit; **N parallel deposits** | N (unit specs only) | parallel deposits; whole-stay totals |
| Bed board | Y | Y BedBoardIT (23) | Y | Y | N | board inside a journey |
| Admitted cards | Y | Y AdmittedCardsIT (13) | Y | N | Y (74) | OK |
| Transfer | Y | Y BedTransferIT (20), BedTransferBillIT (12, skips) | Y | Y BedTransferConcurrencyIT, ScopeRaceIT | Y (21) | transfer with live orders, doses, notices; give vs transfer-out |
| Discharge, final bill | Y | Y DischargeIT (16), DischargeChecksIT (11), Tail, Privacy | Y | Y DischargeConcurrencyIT (6) | Y (51) | discharge with active orders, late doses, open STAT reviews, open notices; SAH-27 QA tail |
| Emergency, triage | Y | Y (4 ITs, 70+) | Y | Y (11) | Y (78) | OK |
| Safety board, escalations | Y | Y SafetyBoardIT, SafetyActionsIT (24), EscalationTriggerIT (17) | Y | Y SafetyConcurrencyIT (18) | Y (109) | OK |
| Break-glass, ward scope | Y | Y EmergencyAccessIT (20), WardAssignmentIT, WardScopeEnforcementIT | Y strong | Y | Y (56); no UI client (F8) | OK |
| Vitals, EWS, notes | Y | Y VitalsSaveIT (18), VitalsValidationIT (26), VitalsEscalationIT (14) | Y | Y (9) | Y (58) | bands sign-off |
| Medicine orders, EMR | Y | Y MedicineOrderFlowIT (21), Sah76OrdersIT (28) | Y; **N read-only / not-admitted on change, hold, resume, query, reply** | Y | P (mocks pre-0.4.0); N change/hold/resume/reply UI | F3, F6 |
| Pharmacist, reviews, notices | Y | Y PharmacistCheckIT (25), Sah76NoticesIT (11) | Y; N hard-stop-after-dose row, DISCHARGED rows, DOSE_UNDONE close, overdue flag | Y | Y pharmacist (93); N notices / Orders tab | see QA-1 |
| MAR, dose, corrections | Y | Y DoseGiveIT (35), Sah76DoseGiveIT (22), DoseSecondNurseIT (23), MarReadIT (25), Sah76DoseCorrectionIT (13) | Y | Y stop/discharge only (F5) | Y mar (92), round (59), PIN (43); N undo, recorded in error; stale assertion `mar.spec.ts:665` | F4 |
| Nursing settings | Y | Y Sah76SettingsIT (13), Sah76SettingsEffectIT (12) | Y | N | N, no client (F8) | profile change mid-dose; card `vitalsDue` from profile |

Frontend components with no own spec: `dose-error-panel`, `review-list`, `pharmacist-tab`, `pharmacist-tile`, `order-fields`, `card-actions`, `card-vitals`; hold/stop/change/query-reply share `emr/order-sheets.spec.ts`.

## 2. Risk ranking
1 F3 EMR line after corrections · 2 F4 give on held/queried/old version (real DB) · 3 F5 races · 4 F6 PRN window · 5 whole-stay scope after transfer · 6 F7 money reconciliation · 7 parallel deposits · 8 discharge with open items · 9 F2 real-stack browser · 10 SAH-76 UI e2e · 11 F10 silent skips · 12 SAH-76 review #10 gaps · 13 SQL-text repository tests (`JdbcDoseRepositoryTest:171`, `JdbcMedicineOrderRepositoryTest:84,133`) · 14 contract shape checked only for MAR.

## 3. Missing journey
### 3.1 `IpdJourneyIT` (+ `IpdJourneySupport`), real DB, one admission, ordered, ward scope ON, PIN pepper set, second clinic for probes. Real endpoints for writes; SQL only to backdate time. 1–2 min.
1 Setup (two wards, rates, min deposit, pharmacist check ON, scope ON) · 2 Admit + deposit (bed occupied, running bill, other clinic 404, ward-B nurse 403) · 3 Orders: bd high-alert, PRN, STAT; duplicate and allergy hard stops; nurse cannot order · 4 Pharmacist: wrong PIN once, verify with `orderVersion`, replay 200, second verifier 409, STAT-before-check review row · 5 MAR give: wrong wristband 409 no dose; give with second-nurse PIN; replay; parallel same slot 409 · 6 Not given, undo, attempt 2, recorded in error; **EMR line equals MAR after each** · 7 Hold → give 409 → resume → give; change v2 → old version refused → verify v2 → notice acknowledged once · 8 Vitals LOW/MEDIUM/HIGH escalation · 9 Safety acknowledge, respond, reopen, call senior; ward-B nurse 403 · 10 Break-glass for nurse B, review, expiry · 11 Transfer to ward B: bill split, nurse A 403, nurse B reads MAR and notices, no lost or doubled slot · 12 Extra charge, parallel deposits, totals · 13 Discharge: refused with open escalation; sign-off; bill-changed 409; settle; frozen = running = sum of lines (paise); bed to cleaning · 14 After discharge every write 409 not-admitted; lists empty; EMR line zero · 15 At every step: second clinic sees nothing (RLS), logs hold no name, mobile, UHID, allergy or note, one audit row per write, no 500.

### 3.2 `frontend/e2e/real/ipd-journey.real.spec.ts` (real backend, no mocks)
Needs human-owned pieces: `scripts/e2e/start-real-stack.mjs` (jar on `sahaj_test`, loopback, OTP bypass, PIN pepper, same-origin Angular build), a seed script and `.env` keys. Serial, one context per role, 1440 / 1024 / 360 px for nurse steps; API read-back after each write; no console errors, no 5xx, poll survives a 403, preflight with `X-Sahaj-Device` succeeds. Interim: a recorded manual smoke on a dev machine before go-live.

## 4. Work list (P0 before go-live, P1 first week, P2 later)
### QA-1 backend ITs
| Pri | File | Cases |
|---|---|---|
| P0 | `IpdJourneyIT` | section 3.1 |
| P0 | `DoseOrderStateIT` | give on held 409 writes nothing; resume then give; QUERIED refused until reply verified; old `orderVersion` refused and audited, new version not givable until verified; MAR states ON_HOLD, QUERIED, REPLACED, STOPPED and counts; PRN on hold/stopped `lockReason` |
| P0 | `DoseOrderRaceIT` | give vs hold; give vs change; undo vs re-give; undo vs recorded in error; give vs transfer-out (scoped nurse) |
| P0 | `EmrExecutionLineIT` (after java fix) | after undo; after recorded in error; undo + attempt 2 counts once; PRN never in "N late" |
| P0 | `IpdMoneyStayIT` | running bill = sum of lines at each step; frozen = running; deposits + balance = total; refund owner-only negative; insurance/corporate never deposit-low |
| P0 | `CorsPreflightIT` | OPTIONS for `/doses`, `/vitals` with `X-Sahaj-Device` allowed from app origin, refused from another |
| P0 | `PrnWindowIT` | start-1 min refused, start, end allowed per contract 0.4.3, end+1 refused |
| P1 | `DischargeOpenItemsIT`, `Sah76ReadonlyNotAdmittedIT`, `Sah76ReviewRowsIT`, `Sah76NoticeOverdueIT` (needs clock seam), `IpdContractShapeIT`, `DepositConcurrencyIT`, `TransferWithLiveOrdersIT`, `NursingProfileRaceIT`, `AdmittedCardVitalsDueIT`, `DischargeTailIT` additions (SAH-27 tail) | as named |
| P2 | `Sah76DeviceLabelIT`; remove IST-midnight skips (clock seam); replace SQL-text repository asserts | |

### QA-2 Playwright
| Pri | File | Cases |
|---|---|---|
| P0 | real journey spec + `playwright.real.config.ts` | needs human stack script; interim manual smoke |
| P0 | refresh `sah31-world.ts`, `sah32-world.ts`, `sah34-world.ts` to 0.4.2; remove stale `mar.spec.ts:665` | re-run medicine, pharmacist, mar, medicine-round |
| P0 | `mar-corrections.spec.ts`, `orders-tab.spec.ts` (after F9) | undo toast, recorded in error, held slot; notices STAT first, acknowledge once, 403 stops poll |
| P1 | `order-actions`, `admit-deposit`, `bed-board`, `ipd-setup`, phone real journey, `contract-fixtures` specs | |
| P2 | layout matrix additions | |

### java-developer
| Pri | Target | Work |
|---|---|---|
| P0 | `MedicineAssembler` / `ExecutionSlots` + new `ExecutionSlotsTest` | SAH-76 #1 fix (live scheduled doses only, as `MarAssembler.liveScheduled`) |
| P0 | `MedicineAdministration.decide`, `MarAssembler.prn` + tests | PRN window (DEC-057 item 3) |
| P0 | `MarServiceTest`, `MarNursingViewTest`, `DoseServiceTest` | states ON_HOLD, QUERIED, REPLACED, STOPPED; refusal before PIN, nothing written |
| P1 | `NoticeClockTest`/`DoseSlotsTest` STAT helper; `OrderChangeServiceTest` field order; `DoseCorrectionServiceTest` PRN recount; `EarlyWarningScoreTest` every band edge; `OrderNoticeService` clock seam | |
| P2 | `AuditDeviceTest` on order actions | |

### angular-developer
| Pri | Target | Work |
|---|---|---|
| P0 | `undo-toast`, new `dose-error-panel` spec, `mar-tab`/`mar.model` states incl. NOT_STARTED, ENDED, `emr-sheet` execution line | |
| P1 | split order-sheet specs; new `review-list`, `pharmacist-tab`, `pharmacist-tile`, `order-fields`, `card-actions`, `card-vitals` specs; `orders-tab` | |
| P2 | api URL vs contract path list check | |

## 5. Human decisions
1. Screen-map entries for SAH-76 screens (F9). 2. Real-stack runner and seed script, or accept a manual smoke. 3. PRN rule (answered DEC-057, contract 0.4.3 pending). 4. Discharge with late doses or active orders: refuse, warn or silent (silent today). 5. Ward assignment and nursing profile screens: API-only for go-live or schedule (F8). 6. Accept the clock seam change.
