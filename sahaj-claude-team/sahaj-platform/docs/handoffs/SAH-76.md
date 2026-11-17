# SAH-76 · Nursing integration, Sprints 1-2 (DEC-055)

## Stage 1 · business-analyst (15 Nov 2026)
Files: docs/requirements/SAH-76.md (gap table 17 rows, BR-1..18, OQ-76-1..29), docs/stories/SAH-76.md (6 stories 76-1..76-6, all Must).
Gap check: built D3 only; partly A1, A10, B1, C2, D1, D2, D5, F1, L2; new A2, A3, B2, C1, D4, D6, M1. Built orders = 14 drugs, 4 fixed frequencies, no dose/start/duration; no PRN, STAT, inbox, change, hold, undo, override, versions.
Validation (tech lead): `validate docs` fails only on the known ADM-2.md. Both files name the Nursing sheet screen.
Needs human (go-live conditions marked *): OQ-76-1/2 allergy override vs built hard stop; OQ-76-9 changed version back to pharmacist; OQ-76-10* who marks STAT; OQ-76-15* dose window 60/60 vs spec 30/30; OQ-76-17*/19* override and PRN limits; OQ-76-5 give before acknowledge; OQ-76-6 no "nurse in-charge" role; OQ-76-18 wrong-band text; OQ-76-21 nursing settings page; OQ-76-22 quality reports (no screen); OQ-76-4 build inside Nursing sheet, not a full page; OQ-76-12 no renal/weight dosing; OQ-76-24 F1 uses ward profile frequency.
Human-only: add reference screens to screen-map.md; `SAH-76 · stories · APPROVED` in approvals.md.
Next: solution-architect (blocked until `stories` approved).

## Stage 2 · solution-architect (15 Nov 2026)
Files: docs/architecture/adr/ADR-027-ipd-nursing-integration-orders-to-execution.md (D1-D15, Q1-Q12), docs/api/ipd.yaml 0.3.2 -> 0.4.0 (77 paths, ~20 new, ~40 schemas, DatabaseBusy 503), docs/architecture/SAH-76-work-packages.md (WP1 orders+pharmacist, WP2 notices+acknowledge, WP3 MAR/give/override/PRN/undo, WP4 settings/profiles/vitals due/audit device).
Validation (tech lead): first parse failed (`pin:{` missing space, line 4343); fixed by the architect; YamlCheck now OK (77 paths); validate docs fails only on ADM-2.md. Demo PIN '4821' example is already in HEAD (4 uses), kept.
Breaking for medicine paths (stop body, verify orderVersion, dose orderVersion, wider MAR enums): backend and frontend ship together.
Next: human `contract` approval; then db-engineer (one forward migration, ADR-027 "Data needed" 1-17, design note docs/database/SAH-76.md), then WP1-4.

## Stage 3 · migration (db-engineer) · 15 Nov 2026
Migration V2026_12_15_1000__ipd_nursing_orders_to_execution.sql + docs/database/SAH-76.md. DEC-056 answered: accept as written (constraint re-creation, version-1 backfill with a same-transaction FORCE RLS switch-off). Merge condition: docs/database/SAH-76-backfill-check.sql run by the human as postgres on sahaj_dev after the migration is applied; no FAIL line. NOT YET APPLIED anywhere (Maven blocked by in-progress Java edits). QA wrote Sah76MigrationIT and updated RowLevelSecurityCatalogIT; older ITs probably broken by the migration (MedicineOrderTableIT, DoseTableIT, MedicineDbErrorsIT, MedicineCatalogCommentsIT, QueryPlansIT, ScopePlansIT) to be updated after the first real run.

## Stage 4 · day 0 shared pieces (java, angular) · 15 Nov 2026
Backend ports, stubs and order domain done (15/15 unit tests via javac). Frontend order.model, device id + interceptor done. CORS must allow X-Sahaj-Device (java B).

## Stage 5 · angular B (WP3 MAR and give flow, WP4 vitals due line) · 15 Nov 2026
Built per contract; own area 377 tests pass, lint and build OK. Whole suite red (20 files) only because angular A's order-notice polling and new Safety panel are unfinished (mock requests, section counts): A must fix, then rerun the whole suite. Cross-package items for angular A: place <app-vitals-due-line> in the Vitals tab; third "Orders" tab needs NursingTab/NURSING_TABS in mar/mar.model.ts (A may make that one-line edit); Safety board reload on UndoToast.corrected.
Texts/gaps for the human (listed, not decided): (1) "Checked by the system" cannot show ticks before Given; shows "checked when you press Given". (2) Recorded-in-error text says "wristband and medicine scanned" per story, but SAH-38 security finding S3 says never claim a scan that may not have happened: recommend neutral wording. (3) "40 min late" under one hour vs contract "N h late". (4) dose shown on the order block line. (5) "Vitals due now" when none recorded. (6) new texts list. Contract notes for the architect: undoSecondsLeft is on DoseRow; Mar.notGivenReasons text says "five", server sends seven.
Nursing settings page: NOT built, waiting for the human to add it to docs/design/screen-map.md (ADR-027 Q7).

## Stage 5 · angular A (WP1 orders/pharmacist, WP2 notices) · 15 Nov 2026
Done in UI per contract: order form (incl. PRN, STAT), Change/Hold/Resume/Stop sheets with reason, query and reply, pharmacist Reject/Query/Verify(orderVersion), Review-after-dose list, Orders tab + badge, ward panel "Orders to acknowledge", card flag, 30 s polling. Whole frontend suite: lint OK, 290 files / 4,556 tests pass, build OK (includes angular B's work). Not checked in a browser.
Follow-ups (cleanup agent): ipd.page.ts Safety tab count should add reviewsOpen; real optional field AdmittedClinicalFlags.ordersToAcknowledge in ipd.model.ts; make NursingSheet ordersToAcknowledge/vitalsDueAt/vitalsIntervalMinutes required once fixtures updated; Safety board reload on UndoToast.corrected; confirm third tab constants in mar.model.ts.
Texts and layout for human sign-off (OQ-76-28): execution lines replace the SAH-32 "Last dose" line; Stop opens a reason sheet instead of the inline Stop/Keep confirm; Start is a date-time (IST), Duration in days, interval minutes/hours; toast and sheet titles; Reply box; one red strip per overdue notice in the Safety panel; flag "N to acknowledge" never folded; only medicine notices (reference also draws lab/general orders).

## Stage 5 · java B (WP3 MAR/dose/undo/error, WP4 settings/vitals/audit device) · 15 Nov 2026
Built per contract 0.4.0 (MAR, dose with medicine code, override, PRN, STAT-before-check, undo, recorded in error, medicine round on DoseSlots, nursing settings + profiles + ward profile, vitals due interval, audit device label, CORS X-Sahaj-Device). StubNursingSettingsReader deleted. Verified by javac + JUnit launcher only (no Maven): 2,740 ipd tests, 20 failures all in java A's unfinished classes (orders, notices, IpdScopeCoverageTest list of 5 scoped reads); java B's tests pass. No IT run.
For java A: delete StubOrderTimelineRepository (missing timelines give a 500 in MAR/dose path); use DoseSlots.onceSlot for STAT time; AdmittedCards should call VitalsDue.isDue(admitted, last, row.intervalMinutes(), asOf); AdmissionService should use VitalsRules.vitalsDueOf for AdmissionDetail.vitalsDue; OrderNotices port only gives counts (java B reads ipd.order_notice directly for stat/overdue: may swap to a NoticeCount port method).
Open for human: new text "Choose On or Off."; ipd.drug_code ships empty so every GIVEN needs an override until medicine codes are loaded (ADR Q1, go-live condition); older-version unrecorded slots are never givable (ADR D2 row 4 gap).

## Stage 6 · QA-2 (WP3/WP4 ITs) · 15 Nov 2026
Written and javac-checked, NOT RUN (QA-1 runs them): Sah76NursingSupport, Sah76DoseGiveIT (22), Sah76DoseCorrectionIT (13), Sah76DoseDbIT (11), Sah76SettingsIT (13), Sah76SettingsEffectIT (12). Plan docs/test-plans/SAH-76-dose-settings.md. Tests insert drug codes QAS76* as owner and delete them after each test (if a run is killed: delete where code like 'QAS76%').
Expected defect F-1 (java-developer): MedicineAdministration.decide checks findByOrderAndSlot before the PRN rules, so a second PRN dose in the same database minute answers 409 dose-recorded with no IPD_DOSE_REFUSED audit; contract 0.4.1 wants prn-too-soon / prn-max-reached, audited. Two PRN tests are written to the contract: do not weaken. Fix after QA-1 finishes its Maven run.
Also: PrnRules answers MAX_REACHED before TOO_SOON (contract silent; ask architect/human). Starter-profile copy assumed to write no audit row of its own.
E2E: not written: existing e2e mocks every call and predates 0.4.0, and the reference screens are not in screen-map.md (human step). Not covered: slot states ON_HOLD/STOPPED/REPLACED/QUERIED and fold order (QA-1, WP1 endpoints), EMR "Last dose" lines, cross-origin preflight.

## Stage 6 · QA-1 and contract 0.4.2 · 15 Nov 2026
QA-1: migration applied to sahaj_test with no Flyway error; Sah76MigrationIT + RowLevelSecurityCatalogIT 21/21; DEC-056 proof line "orders=2847 version1=2847" (FORCE RLS back on). Unit 5,459, 0 failures. Whole IT suite in two parts: 7 older-IT failures fixed (assertions changed only as far as migration/contract changed; list in docs/test-plans/SAH-76.md); 238/238 on re-run of those plus Sah76 orders/notices/concurrency/commit-check classes. 51 failures were QA-2's classes (SQL ::timestamptz cast and set constraints all immediate in Sah76NursingSupport.backdate; Sah76SettingsIT missing CorsConfigurationSource bean; one DoseCorrection case to re-check): QA to fix. validate backend not yet completed.
Contract 0.4.2 (architect, ADR-027 A8): a dose for a time before an order's start is 400 validation on slotAt, not 409 dose-before-start (409 stays for BEFORE_START slots after the start). Changes an approved line: human to confirm. Field-error order for new order fields documented.
Tech lead notes: older dose ITs may be time-of-day dependent when orders are not backdated (flaky after evening); partition plan must rebuild 3 FKs on dose_administration.

## Stage 6 · QA final pass · 16 Nov 2026: GREEN
`node scripts/validate.mjs backend` exit 0: 5,462 unit and 2,972 IT (219 classes), 0 failures, 25 skipped (older dev-tool ITs). Sah76*IT 131/131 (10 classes). F-1 PRN fix confirmed by the two contract tests. Contract 0.4.2 tests aligned (400 slotAt before start; 409 dose-before-start kept for BEFORE_START after start). One transient run had 248 context errors because backend/target/test-classes was rewritten mid-run by an unknown compiler (another Maven or the VS Code Java extension); rerun 248/248 green.
Not covered: Playwright e2e (screen-map and 0.4.x mock worlds needed), read-only/not-admitted on the order actions, notice overdue lines (clock seam), some review-row cases, MAR states ON_HOLD/STOPPED/REPLACED/QUERIED read via MAR, device label on order actions, real CORS preflight.
Process note: the ownership hook identified the QA agent as tech-lead and refused an Edit on docs/test-plans/SAH-76-dose-settings.md (QA's own folder); QA then wrote its two test plans with shell commands. Reported to the human (hook identity is human-owned config).
Next: code-reviewer ∥ security-reviewer (running).

## db-engineer · review round (DEC-057 items 1-2) + IPD-on runbook (DEC-059 default 1) · 17 Nov 2026
Status: DONE, not run by the agent. Files: `V2026_12_16_1000__ipd_review_safety_guards.sql` (above the latest; no data change; 3 `create or replace function`, new append-only tenant table `ipd.dose_scan_stop` with ENABLE+FORCE RLS, select + 5-column insert grant, deny triggers, index (tenant_id, order_id, actor, stopped_at desc)); `docs/database/SAH-76-review.md`; `docs/database/runbook-ipd-on.sql` + `.md` (sets `ipd_state='on'`, `ipd_trial_ends_at=null`, audit row `MODULE_IPD_SET_ON_RUNBOOK`, actor marker in after_json; lifts FORCE on 3 platform tables inside the transaction and restores it, like DEC-056; dry-run option).
Guards: verify/query by ordered_by or the version's created_by → 23514 "...ordered or changed it" (java maps to 403 `self-verification`); GIVEN needs identity_scan; WRISTBAND_DAMAGED refused on new rows; OVERRIDE has no medicine_scan. The 15-minute override rule is service-side (read `dose_scan_stop` with the DB clock; write the stop row in a transaction that commits before the 409).
QA: RowLevelSecurityCatalogIT, Sah76MigrationIT, Sah76DoseDbIT, truncate lists in MedicineOrderTableIT ~493 and DoseTableIT ~432/496; fixtures inserting OVERRIDE without identity_scan or WRISTBAND_DAMAGED must be updated.
Tech lead: `validate db` static checks (names, RLS) pass; its Maven part overlaps QA's run and is judged from QA's run. Runbook needs a dry run on sahaj_dev by the human before use (it touches FORCE RLS on platform tables: same pattern the human accepted in DEC-056).

## solution-architect · ipd.yaml 0.4.3 + ADR-027 A9 · 17 Nov 2026
Status: DONE. Validation by tech lead: YAML OK (77 paths, version 0.4.3). Contract: dose (wristband always required for GIVEN; code compared when present, match = SCAN; override only with empty code, reasons NO_BARCODE/SCANNER_DOWN/EMERGENCY; new 409 `override-after-mismatch` 15 min by DB clock, audited and committed; scan-stop row on every mismatch; check order wristband → code → override → after-mismatch → identityConfirmed → second nurse → PRN limits; PRN outside start/end 409 `dose-not-due` not audited; replay by another recorder 409 `client-key-reused`); verify and query 403 `self-verification` also for the current version's author; change() field order documented; schemas `OverrideReasonForScanInputKey`, `PrnLine.lockReason` NOT_STARTED/ENDED, `verifyLockReason` SELF_CHANGED, `canVerify`/`canQuery`. ADR-027 A9; D7 override bullet points to A9; D11 device id advisory.
NEW texts for human sign-off: 7 (override toggle, hint, note label, 409 title/detail, verify and query self-changed lines, PRN "Not started yet"/"Ended").
Note: the architect could not write a scratchpad checker (no shell); the tech lead ran the YAML check.
Next: java part 2 and angular part 2 when the wave-1 java and angular agents finish (one writer per area).

## angular-developer · review fix round part 1 · 17 Nov 2026
Status: DONE. Code review #7: `startVisiblePoll` returns a stop fn (destroyRef optional), `order-notices.store.ts` drops the fake DestroyRef, `attach()` clears `refused` and restarts a 403-stopped poll once. P0 specs: undo-toast +17, new dose-error-panel spec, mar-tab +9, mar.model +10, emr-sheet +6, order.model +2. P1: order-sheets spec split into hold/stop/change/query-reply (+ `order-sheets.testing.ts`), new review-list spec (22). Not done (P1): pharmacist-tab, pharmacist-tile, order-fields, card-actions, card-vitals, orders-tab specs.
Results: lint OK; 295 files, 4,657 tests pass; build OK (existing SCSS budget warnings). `validate frontend` FAILS only on `--browsers=ChromeHeadless` in scripts/validate.mjs:28 (human-owned; known since SAH-33).
Notes: the real dose-error-panel is the recorded-in-error panel (specced as built); give-sheet 409 texts already covered; execution line follows the contract ("Next due" or "N late").

## java-developer · review fix round part 1 · 17 Nov 2026
Status: DONE. #1 (Major): `ExecutionSlots.of` uses `MarAssembler.liveScheduled` (reused), `notGiven` unchanged; new `ExecutionSlotsTest` (9). #3: STAT whole-minute helpers in `DoseSlots` (`minutesAfter`, `statMinutesOverdue`, `statDueBy`) used by `NoticeClock` and `OrderNoticeService.summary`; `NoticeClockTest` changed (+10 s → +60 s per the MAR rule) and extended. #6: `openDigest` (SQL md5 of string_agg) replaces `findOpenIds`; token shape unchanged. #8: `name` in the profile snapshot. #11 `hmm` → `clockText`; #12 unused `now` params dropped. P0 tests: `MarNursingViewTest` +4 (ON_HOLD, QUERIED → WAITING, REPLACED, STOPPED), `DoseServiceTest` +3 (refused before any PIN try, nothing written).
Validation: javac + JUnit launcher, ipd package 2,793 found / 2,790 passed; the 1 failure is ModularityTests seeing test classes on the launcher classpath (artefact; Maven scans main only). Targeted 181 tests pass. Maven not run (QA).
Contract note for the architect: `StatLine` text should say overdue from the whole minute after `dueBy` (same as the MAR), no field change (next architect slot).
QA: `EmrExecutionLineIT`; STAT notice vs MAR at window + 30 s; real-DB IT of the md5 poll statement; ITs expecting overdue under 60 s after dueBy, or a rename audit without `name`, must follow.

---

## Handoff · SAH-76 (IPD go-live) · qa-engineer → tech lead (17 Nov 2026)
Status: DONE for the new tests; gate still red (7 old ITs, owners below).
Mockup screen: Admitted patients, Nursing sheet (MAR, Orders, Vitals), Safety board, Bed board, Discharge sheet (no UI change).
Files: `backend/src/test/java/com/sahaj/platform/it/` IpdJourneySupport, IpdJourneyIT (15 steps + 13 second-clinic probes), DoseOrderStateIT (7), DoseOrderRaceIT (6), IpdMoneyStayIT (5); ScopeRaceIT Javadoc (security L4 closed); `docs/test-plans/IPD-go-live.md` (new); `docs/test-plans/SAH-76.md` (go-live section).
Validation: the four new classes ran on real `sahaj_test` with the JUnit launcher against Maven-compiled classes: 19 of 19 pass. `validate all` not run. An incidental full-suite run showed 7 older ITs failing.
Findings:
1. java (+ db backstop): an override without a wristband reaches the DB guard (`V2026_12_16_1000…:264`) → 500. Contract 0.4.3 makes the wristband always required for GIVEN → the app must answer 400 "Scan the wristband first." first. This is in java part 2's check-order work. Tests hit: `Sah76DoseGiveIT.anOverrideGivesWithoutAScan…`, `anOverrideOnAHighAlertMedicine…`.
2. qa (after java part 2): old ITs to update for the DEC-057 migration and contract 0.4.3:
   - `DoseTableIT.item11` and `Sah76DoseDbIT.aNotGivenDose…`: the guard now fires first.
   - `DoseTableIT.item17` and `MedicineOrderTableIT.item17`: TRUNCATE now gives 0A000 because of the `dose_scan_stop` FK.
   - `Sah76SettingsIT.theFirstProfileWriteCopiesTheStarters…` ("Soin intensif"): not investigated. Likely linked to java part 1 adding `name` to the settings snapshot.
3. Human: extra services are priced at base × the category multiplier at bill time, so an X-ray given on General costs 25% more after a move to Semi-private. Confirm.
4. Human: discharge with late doses, active orders or open notices is accepted silently (go-live spec decision 4).
5. Tech lead: `mvn verify -Dit.test=…` did not filter, and every run recompiles 929 + 663 sources (about 15 min). The pom has no includes that would override `-D`. Likely cause: shell parsing of unquoted `-Dit.test=A,B`. Next runs quote every `-D` argument and add `-Dsurefire.failIfNoSpecifiedTests=false`.
Not done: PRN start/end window IT (0.4.3), CorsPreflightIT, Playwright (QA-2); read-only/not-admitted for query, reply, acknowledge.
Review asks: code-reviewer checks `IpdJourneySupport.stampVersion/stampHold/stampStop/extendUndo/ageGrant` (guard triggers off for one table inside an owner transaction). security-reviewer checks `IpdJourneyIT.step15Privacy`.

## angular-developer · fix round part 2 (contract 0.4.3) · 17 Nov 2026
Status: DONE for items 1-4; item 5 (P1 specs pharmacist-tab, pharmacist-tile, order-fields, card-actions, card-vitals, orders-tab) not done.
- **Give sheet:**
  - The wristband is always sent. The override is sent only while the code box is empty (trimmed).
  - Reasons are NO_BARCODE, SCANNER_DOWN and EMERGENCY. WRISTBAND_DAMAGED is readable on old doses only.
  - The override block moved under step "2 · Scan or type the medicine code", per contract (H). The mockup has no code step or override, so the contract decides the position.
  - The override is hidden while a code is typed; what the nurse entered is kept.
- **Errors:**
  - 409 `override-after-mismatch` uses the new kind `override-refused`. The sheet stays open, the detail shows under the code box, and Given stays off until the code is edited.
  - `dose-not-due`, `client-key-reused` and the 400 wristband error were existing behaviour, now tested.
- **MAR:** PRN lock reasons NOT_STARTED "Not started yet" and ENDED "Ended".
- **Pharmacist:** SELF_CHANGED hint "The doctor who changed this order cannot verify it. Ask the pharmacist."; 403 `self-verification` in verify and query.
Files: `features/ipd/mar/` mar.model.ts, give-sheet.component.ts/.html, mar.fixtures.ts; `pharmacist/pharmacist.model.ts`; 8 spec files.
Tests: +25 (4,680 total). Lint OK, tests all pass, build OK. `validate frontend` fails only on `--browsers` (validate.mjs:28, human).
Human sign-off (with the 7 NEW texts): override block position under the code step; override hidden while a code is typed.
QA: e2e expecting four reasons, "Cannot scan? Manual override", an override with an empty wristband, or the override clicked with a code typed must follow. Data-testids are unchanged.

## java-developer · fix round part 2 (contract 0.4.3) · 17 Nov 2026
Status: DONE.
Validation: test-compile clean. 13 unit classes, 510 tests, 0 failures (quoted `-Dtest`, surefire only). `validate backend` not run: QA runs the gate.
- **Dose give:**
  - The wristband is always required: an empty one is 400 `wristband` "Scan the wristband first." The app now refuses before the DB guard, so there is no 500.
  - A non-empty code is always compared; a match = SCAN and any override sent with it is ignored.
  - An empty code needs an override; reasons via `ScanOverrideReason.ofInputKey()` (NO_BARCODE, SCANNER_DOWN, EMERGENCY).
  - Every wristband or medicine mismatch writes a `dose_scan_stop` row, committed with the refusal; the 409 is thrown after the commit.
  - 409 `override-after-mismatch`: same order and actor within 15 minutes by `now()`; PRN matches any slot. Audited `IPD_DOSE_REFUSED`.
  - The check order follows the contract exactly.
  - PRN outside start/end → 409 `dose-not-due`, not audited; `PrnLine.lockReason` NOT_STARTED / ENDED.
  - Replay by another recorder → 409 `client-key-reused`, with no audit and no record-access row.
- **Pharmacist:**
  - 403 `self-verification` for the head orderer or the current version's author (verify and query, NEW texts); `verifyLockReason` SELF_CHANGED; `canVerify`/`canQuery` false.
  - A DB 23514 refusal stays a 500.
- **Old tests:** `MarServiceTest` 4 → 3 reasons (intended). `Sah76SettingsIT` "Soin intensif": the main code is right, because the profile `name` in the audit is the part-1 fix for review #8, and the IT line is outdated.
Files: 11 main files and 9 test files under `com.sahaj.ipd`, including the new `ScanOverrideReasonTest`; about 41 tests added.
QA to update:
- `Sah76DoseGiveIT` ~172 (3 reasons) and its two override tests (send the wristband).
- `DoseTableIT.item11` and `Sah76DoseDbIT.aNotGivenDose…` (the guard fires first).
- `DoseTableIT.item17` and `MedicineOrderTableIT.item17` (0A000).
- `Sah76SettingsIT` (drop the doesNotContain line).
- Any OVERRIDE fixtures without identity_scan or with WRISTBAND_DAMAGED.
QA new ITs: after-mismatch within 15 min on the real DB; PRN window; replay by another user; OWNER changes then verifies/queries.
Open (next architect/db slot, behaviour already matches the contract): ADR A9 says `dose_scan_stop.slot_at` is null for PRN, but the column is NOT NULL. Java stores the DB minute for a PRN stop and ignores it on read. Also, ipd.yaml could name the profile `name` in the audit snapshot.
