# SAH-76 test plan · work packages 3 and 4: giving, correcting and nursing settings

Story: SAH-76 (stories 76-4, 76-5, 76-6). Contract: `docs/api/ipd.yaml` 0.4.2. Database: `docs/database/SAH-76.md`. Author: QA-2. Branch `feature/SAH-39-home-kinetic-background`.

**Status: run on 16 Nov 2026 (`mvn -o verify -Dit.test='Sah76*IT'`, database `sahaj_test`): 131 tests including the QA-1 classes, 0 failures, 0 errors, 0 skipped. The five classes of this plan: 13 + 11 + 22 + 12 + 13 = 71, all pass.** The first run (15 Nov) failed in the shared helper, in the CORS test and in one attempt-chain case; the fixes are in the tests only (section 6). Contract 0.4.2 moved the line for a dose before the order's start (400 `slotAt`, not 409); the tests follow it.

## 1. What was written

| Class (`backend/src/test/java/com/sahaj/platform/it/`) | Tests | What it proves |
| --- | --- | --- |
| `Sah76NursingSupport` (abstract) | none | New order body, verify with `orderVersion`, backdating of a version by the table owner, medicine codes (inserted as owner, removed after each test), dose bodies, `X-Sahaj-Device` call, PRN/undo ageing helpers. Extends `ScopeSupport` (so `MarSupport` clock seam, ward scope). |
| `Sah76DoseGiveIT` | 22 | Give: medicine code, mismatch trace, override rules, NOT_GIVEN VOMITED/OTHER, replay, version, slots, STAT before check, PRN, roles, tenant, race, device label. |
| `Sah76DoseCorrectionIT` | 13 | Undo, recorded in error, attempt n+1, two corrections, windows, Read-only, discharged, tenant, ward scope guard on `/doses/{id}/*`. |
| `Sah76DoseDbIT` | 11 | Dose insert guard, new checks, correction insert guard, uniqueness, append-only (app and owner), RLS on `ipd.dose_correction`. |
| `Sah76SettingsIT` | 13 | Nursing settings upsert, starter profiles, 20 cap, unique name, key from name, ranges and texts, ward profile, roles, Read-only, tenant, CORS header list. |
| `Sah76SettingsEffectIT` | 12 | What a profile or the clinic setting changes: verification, vitals interval stamp and `vitalsDueAt`, dose windows in MAR and Safety counts, STAT window, slot states from `DoseSlots`. |

Run: `mvn -f backend/pom.xml verify -Dit.test='Sah76*IT'` (QA-1). All use `@OnTestDatabase` (profile `test`, `sahaj_test`).

### Method notes (read before judging a failure)
- **Clock.** Scheduled orders use the fixed-clock seam of `MarSupport` (`clockAt`). A scheduled order is made old by the table owner (`backdate`: version 1 `start_at`, `created_at`, `verified_at`, and the head) because the slot rule reads the version row. STAT (slot = creation minute) and PRN (no slot) orders run on the **real** clock, because the undo window and the PRN limits use the real `recorded_at`.
- **Medicine codes.** `ipd.drug_code` ships empty, is global and immutable. Tests insert codes starting `QAS76` as owner and delete them in `@AfterEach` (update/delete trigger switched off for that one delete) so `Sah76MigrationIT` ("code list ships empty") still holds. If a run is killed between insert and cleanup, delete `where code like 'QAS76%'` by hand.
- **Fixtures.** `Sah76DoseDbIT` builds one shared fixture on first use (committed rows in new clinics), like `DoseTableIT`.
- **Time of day.** A few tests assume the run does not cross IST midnight (in-error day rule) and that undo is asked within 10 s of the give.
- **Unsupported by this layout:** none of the tests need a pharmacist PIN except `verifyNow`.

## 2. Coverage: acceptance criterion to tests (story 76-4, 76-5, 76-6)

| Story | Acceptance criterion (short) | Test (class.method) | Result |
| --- | --- | --- | --- |
| 76-4 | Slot states (later, due, late, given, held, refused, waiting, before start, not recorded) | `Sah76SettingsEffectIT.theSlotStatesOfAnOrderFollowTheClockTheOutcomeAndTheVerification`, `.aSlotBeforeTheTimeTheOrderWasCheckedIsBeforeStartAndIsRefused`, `.afterDischargeEverySlotThatWasNotRecordedIsNotRecorded` | pass |
| 76-4 | On hold, stopped, replaced slot states | not here: need hold/change/stop of WP1 (QA-1; unit-covered by `DoseSlots` tests) | gap, see section 4 |
| 76-4 | PRN line, minimum interval, N of max in 24 h | `Sah76DoseGiveIT.aPrnDoseHasNoSlotAndTheMinimumIntervalHoldsUntilTheDoseIsRecordedInError` | pass |
| 76-4 | Counters late / due now / given, fold order | `Sah76SettingsEffectIT.theWardProfileWindowsDecide...` (counters, `doseCount`); fold order (`folded`, `foldedCount`) not covered | partial |
| 76-4 | Medicine code step: code required, normalised, stored | `Sah76DoseGiveIT.aScanGivenDoseStoresTheVersionKindMethodAndTheNormalisedMedicineCode`, `.theMedicineCodeIsRequiredWithoutAnOverrideAndNothingIsWritten` | pass |
| 76-4 | Wrong patient STOP, no name | `Sah76DoseGiveIT.aWrongWristbandIsCheckedBeforeTheMedicineCode`, `.anOverrideNeverSkips...` | pass |
| 76-4 | Wrong medicine STOP, texts, deny entry, audit, nothing recorded | `Sah76DoseGiveIT.aCodeOfAnotherMedicineStops...`, `.aCodeThatIsNotInTheDrugMasterIsAnItemNotOnThisOrder...` | pass |
| 76-4 | Server re-runs the checks; order changed 409 `order-changed` | `Sah76DoseGiveIT.anOrderVersionThatIsNotTheCurrentOneIsRefusedAndTheRefusalIsAudited`, `.theSlotRulesStillHold...` | pass |
| 76-4 | Manual override: reasons, note, stored, never logged | `Sah76DoseGiveIT.anOverrideGivesWithoutAScan...`, `.anOverrideWithABadReasonOrAShortNote...` | pass |
| 76-4 | Override never skips two identifiers, second nurse + PIN | `Sah76DoseGiveIT.anOverrideNeverSkips...`, `.anOverrideOnAHighAlertMedicineStillNeedsASecondNurse...` | pass |
| 76-4 | PRN too soon 409 `prn-too-soon` with IST time, audited | `Sah76DoseGiveIT.aPrnDoseHasNoSlot...` | pass (F-1 fixed by java B; see section 5) |
| 76-4 | PRN daily maximum 409 `prn-max-reached`; in-error/undone never count | `Sah76DoseGiveIT.thePrnDailyMaximumHolds...` | pass (F-1 fixed) |
| 76-4 | STAT given once before verification, review item, second dose refused | `Sah76DoseGiveIT.aStatDoseMayBeGivenOnceBeforeThePharmacistCheckAndOpensAReviewItem`, `.aWaitingScheduledOrderAndAWaitingPrnOrderAreRefusedWithAnAuditedTrace`, `Sah76SettingsEffectIT.aStatOrderIsNotWaitingOnTheCheckWhateverTheProfileSays` | pass |
| 76-4 | Non-STAT pending / queried not givable | `Sah76DoseGiveIT.aWaitingScheduledOrder...` (queried needs WP1) | partial |
| 76-4 | Roles (NURSE, OWNER give; others 403), Read-only, tenant | `Sah76DoseGiveIT.everyRoleThatIsNotANurseOrTheOwnerIsRefused...`, `.aNurseOfAnotherClinicCannotSeeOrGiveTheOrder`, `Sah76DoseCorrectionIT.aReadOnlyClinicAndADischargedPatient...` | pass |
| 76-4 | Audit with ids/keys only, device label, no scanned text/note | `Sah76DoseGiveIT.theAuditRowsCarryTheDeviceLabel...`, `.aScanGiven...`, `.anOverrideGivesWithoutAScan...` | pass |
| 76-4 | Two nurses, one slot | `Sah76DoseGiveIT.twoNursesGivingTheSameSlotAtTheSameMomentRecordItOnce` | pass |
| 76-4 | Replay by client key / key reused | `Sah76DoseGiveIT.aRepeatWithTheSameKeyAnswersTheFirstRecord...` | pass |
| 76-5 | Not given reasons incl. VOMITED, OTHER (note 4+), stored as HELD | `Sah76DoseGiveIT.notGivenVomitedIsStoredAsHeldAndOtherNeedsANote...`, `.theNoteOfNotGivenIsIgnored...` | pass |
| 76-5 | Undo within the window by the recorder; slot due again; originals kept | `Sah76DoseCorrectionIT.theRecorderUndoesADoseInsideTheWindow...` | pass |
| 76-5 | Undo after the window / other user: 409 `undo-expired` / 403 | `Sah76DoseCorrectionIT.anUndoAfterTheWindowIsRefused...`, `.onlyTheNurseWhoRecordedTheDoseMayUndoIt...` | pass |
| 76-5 | Double tap on undo | `Sah76DoseCorrectionIT.anUndoRepeatedWithTheSameKeyIsAReplay...`, `.aDoseCanBeCorrectedOnlyOnce...` | pass |
| 76-5 | Undo window from the clinic setting, never above 10 s | `Sah76DoseCorrectionIT.theClinicUndoWindowIsStamped...`, `Sah76SettingsIT.everyNumberHasItsRange...` (undoSeconds 11 refused) | pass |
| 76-5 | Recorded in error: any nurse, reasons, struck through, slot given again | `Sah76DoseCorrectionIT.anyNurseMayMarkADoseInErrorOnTheDay...`, `.aCorrectedSlotCanBeGivenAgainAsTheNextAttempt...` | pass |
| 76-5 | Error window: IST day only, `error-window-closed` | `Sah76DoseCorrectionIT.recordedInErrorIsOnlyAllowedOnTheIstDayOfTheSlot`, `Sah76DoseDbIT.anUndoNeedsTheRecorder...` | pass |
| 76-5 | Not given or already corrected: 409 `dose-state` | `Sah76DoseCorrectionIT.aDoseCanBeCorrectedOnlyOnce...` | pass |
| 76-5 | Audit rows and event text of undo / in error | `Sah76DoseCorrectionIT.theRecorderUndoes...`, `.anyNurseMayMarkADoseInError...` | pass |
| 76-5 | Roles, 400, 404, scope, Read-only, discharged | `Sah76DoseCorrectionIT.recordedInErrorNeedsAReason...`, `.aNurseOfAnotherClinicSeesNothing...`, `.theWardScopeGuardAppliesToTheDoseAndBothCorrections...`, `.aReadOnlyClinic...` | pass |
| 76-5 | Database: append-only, RLS, guards | `Sah76DoseDbIT.*` | pass |
| 76-5 | EMR order row "Not given / Last dose" lines | not here (EMR sheet is WP1/QA-1) | gap |
| 76-6 | Settings defaults, upsert, ranges, owner only, Read-only, tenant | `Sah76SettingsIT.aClinicThatNeverSaved...`, `.theOwnerSavesTheSettings...`, `.everyNumberHasItsRange...`, `.onlyTheOwnerMayChangeTheSettings...`, `.theSettingsOfOneClinicNeverChangeAnother` | pass |
| 76-6 | Profiles: starters copied on first write, 20 cap, unique name | `Sah76SettingsIT.theFirstProfileWriteCopiesTheStarters...`, `.aProfileNameIsUniqueIgnoringCase...`, `.aClinicCanHaveTwentyProfiles...`, `.changingAProfileWritesOnlyWhen...` | pass |
| 76-6 | Ward profile choice, defaults when none | `Sah76SettingsIT.theOwnerChoosesTheProfileOfAWard...`, `.aWardProfileOfTheListIsRequired...` | pass |
| 76-6 | Applies to later events, never rewrites past records | `Sah76SettingsEffectIT.aWardProfileWithVerificationOff...`, `Sah76DoseCorrectionIT.theClinicUndoWindow...`, `Sah76SettingsEffectIT.theVitalsIntervalOfTheWardProfile...` | pass |
| 76-6 | Vitals due follows the ward profile; stamp kept; old records 240 | `Sah76SettingsEffectIT.theVitalsIntervalOfTheWardProfile...`, `.anOwnProfileIntervalAtTheEdges...` | pass |
| 76-6 | Dose windows from profile, else clinic, in MAR and Safety counts | `Sah76SettingsEffectIT.theWardProfileWindowsDecide...`, `.theWindowBeforeDecidesLaterAndTheRefusalText...`, `.theStatWindowDecides...` | pass |
| 76-6 | Audit with before/after and device label | `Sah76SettingsIT.theOwnerSavesTheSettings...`, `.theFirstProfileWrite...`, `.changingAProfile...`, `.theOwnerChoosesTheProfileOfAWard...` | pass |
| 76-6 | Browser may send `X-Sahaj-Device` (CORS) | `Sah76SettingsIT.theBrowserMaySendTheDeviceHeader...` (bean level; a real cross-origin preflight needs the dev origin property, not set in profile `test`) | pass |
| 76-6 | Vitals due on the admitted card flag (`vitalsDue`) follows the profile | **not covered**: java B noted `AdmittedCards`/`AdmissionService` do not use the interval yet (java A item) | gap |

## 3. Cases by class (condition to test)

### Sah76DoseGiveIT (D-01 to D-22)
D-01 scan give stores version, kind SCHEDULED, method SCAN, normalised code, undo window 10 s; audit keys and device; no code in events/audit. D-02 code required without override (400 `medicineCode`), wristband asked first, nothing written. D-03 code of another medicine: 409 `medicine-mismatch`, text, deny event, audit, no dose, right code then works. D-04 unknown code: "an item not on this order", event and audit never hold the typed text, a text over 100 characters never matches. D-05 wrong wristband beats wrong code. D-06 override stores reason and note, event suffix, audit `notePresent`, note never returned or logged, four reasons in the MAR. D-07 override: bad reason / short note / empty note 400 with texts. D-08 override never skips wristband mismatch, medicine mismatch, identity tick. D-09 override on a high-alert drug still needs second nurse (400), different nurse (400), PIN (403), then 201. D-10 VOMITED stored HELD, OTHER needs 4+ characters, note stored, never in events/audit/answers, seven reasons in the MAR. D-11 note ignored for a reason that needs none. D-12 replay 200, key reused 409, key of another order 400. D-13 order version 409 `order-changed` audited as `IPD_DOSE_REFUSED`; missing/0 version 400. D-14 slot rules and the "from 60 min before" text. D-15 waiting scheduled and waiting PRN: `order-not-verified`, audited. D-16 STAT before check: flags, review item, event suffix, second dose refused. D-17 PRN no slot, too soon, in-error frees it. D-18 PRN daily maximum. D-19 roles. D-20 another clinic 404. D-21 race (forced order): one 201, one `dose-recorded`. D-22 device label (id, agent, none, malformed, refusal).

### Sah76DoseCorrectionIT (C-01 to C-13)
C-01 undo happy path with everything it writes. C-02 replay and key reuse. C-03 recorder only, roles, `canUndo`/`canMarkInError` per viewer. C-04 undo after window, then in error works. C-05 clinic undo seconds stamped per dose, past doses keep theirs. C-06 one correction only (both orders), not-given dose. C-07 in error by another nurse: MAR history, event, audit, review closed, replay, reasons list. C-08 in error 400s and roles. C-09 IST-day boundary (23:59 same day allowed, 00:00 next day `error-window-closed`). C-10 attempt 1, 2, 3 chain with `supersedes_dose_id`. C-11 Read-only, Off, discharged. C-12 other clinic 404, bad id 400, anonymous 401. C-13 ward scope guard (dose, undo, in error), same-ward colleague, owner.

### Sah76DoseDbIT (B-01 to B-11)
B-01 GIVEN needs scan method; undo stamped by trigger (10); `undo_until` not insertable. B-02 not-given rows: no undo window; checks for OTHER note, note length, scan method on HELD, medicine scan on HELD, override pair, override note. B-03 attempt chain: duplicate slot 23505 under the old constraint name, attempt 2 after a correction, wrong slot, skipped attempt, live record. B-04 version, kind, high-alert flag, before-verification on ACTIVE, PENDING scheduled. B-05 STAT rules. B-06 undo guard (recorder, window), in error day rule, only GIVEN. B-07 reason by kind, one correction per dose, tenant column not insertable. B-08 unique client key. B-09 append-only for app and owner (update, delete, truncate). B-10 RLS: other clinic sees none, FK blocks cross-clinic insert, no tenant bound. B-11 dose table RLS.

### Sah76SettingsIT (S-01 to S-13) and Sah76SettingsEffectIT (E-01 to E-12)
S-01 defaults, starters, wards, high-alert list, no writes on GET. S-02 read roles, 403 roles, 401, Read-only, Off. S-03 save and audit, same values write nothing, one config row. S-04 ranges for all six numbers and On or Off, all errors together, edges accepted. S-05 owner only, Read-only 409, Off 403. S-06 clinic isolation. S-07 first write copies starters, key from name (`-2`, `-3`, accents). S-08 unique name ignoring case, every field text, edges. S-09 20 cap. S-10 profile change, same values, rename, 404, roles, Read-only. S-11 ward profile set, same, null. S-12 ward profile 400/404/403/409. S-13 CORS header list.
E-01 verification Off by ward profile: ACTIVE, `NOT_REQUIRED`, not in queue, notice at once, earlier order untouched, profile back On. E-02 clinic setting and profile precedence. E-03 STAT not waiting. E-04 vitals stamp 60/240, `vitalsDueAt`, earlier stamp kept. E-05 edges 15/1440 and a null stamp reads 240. E-06 windows 60/60 default, 15/15 profile (LATE, Safety counts), profile without windows uses clinic 45/50, exact window is DUE. E-07 window before and the "from 15 min" text. E-08 STAT window. E-09 slot states LATE/DUE/LATER, GIVEN/HELD/REFUSED, WAITING. E-10 BEFORE_START. E-11 NOT_RECORDED after discharge. E-12 other clinic isolation.

## 4. Gaps (not tested here, with reason)
1. Slot states ON_HOLD, STOPPED, REPLACED and QUERIED (need WP1 hold, stop, change, query endpoints; owner QA-1) and the fold order of `MarOrder.folded`.
2. EMR order-row execution lines (`OrderExecution`), review-after-dose list (WP1).
3. Admitted card `vitalsDue` and `AdmissionDetail.vitalsDue` following the profile: java A item, not yet in code.
4. Frontend e2e: **not feasible.** The existing e2e layout mocks every API call (`sah31-world.ts`, `sah32-world.ts` predate the 0.4.0 contract), the reference screens for acknowledge, undo toast, override and recorded in error are not in `docs/design/screen-map.md` (human step), the Nursing settings page is not built, and the angular units say the UI has not been checked in a browser. A spec written now would test an invented screen. Revisit after the human adds the reference screens and the mock worlds are updated for 0.4.x. No dev server was started.
5. A real cross-origin preflight (needs `sahaj.cors.allowed-origins`, empty in profile `test`).

## 5. Findings
- **F-1 (java-developer, fixed 15 Nov).** `MedicineAdministration.decide` answered 409 `dose-recorded`, with no `IPD_DOSE_REFUSED` audit, for a second PRN dose in the same minute. After the fix it answers `prn-too-soon` / `prn-max-reached` and audits. `aPrnDoseHasNoSlot...` and `thePrnDailyMaximum...` were written to the contract, were not weakened, and pass on the fixed code.
- **Open question, no failure:** `PrnRules` answers MAX_REACHED before TOO_SOON when both hold; the contract is silent (architect or human to decide). The tests pin neither order.
- No other defect of main code was found by these classes.

## 6. Results
Run 16 Nov 2026 on `sahaj_test`, offline Maven, `-Dit.test='Sah76*IT'`: **Tests run: 131, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS** (71 in this plan).

| Class | Tests | Result |
| --- | --- | --- |
| Sah76DoseGiveIT | 22 | pass |
| Sah76DoseCorrectionIT | 13 | pass |
| Sah76DoseDbIT | 11 | pass |
| Sah76SettingsIT | 13 | pass |
| Sah76SettingsEffectIT | 12 | pass |

Fixes to the tests after the first run (no main code touched):
1. `Sah76NursingSupport.backdate`: `else ?::timestamptz end` in both statements (a bare `?` inside CASE has no type); `set constraints all immediate` before the last `alter table ... enable trigger` (the deferred head check is pending); runs on the fixed `VitalsSupport.asOwner` (version table goes along with the head; the deferred check runs before the FORCE ALTER).
2. `Sah76SettingsIT` CORS test: the context failed for want of a `CorsConfigurationSource` bean; the test now takes the application bean `corsConfigurationSource` by name and asserts the allowed header list (X-Sahaj-Device, Authorization, Content-Type, Accept), the methods, that another header is refused and that no wildcard origin is allowed. Nothing weakened.
3. `Sah76DoseCorrectionIT.aCorrectedSlotCanBeGivenAgain...`: the closing check (a live record blocks the next attempt) now verifies the STAT order first; a STAT order with a live dose and no verification answers `order-not-verified`, which is the documented behaviour, not a main-code defect.
4. Contract 0.4.2: a dose for a time before the version's start is 400 `validation` on `slotAt`. `DoseGiveIT.aSlotBeforeTheOrderStarted...` expects it; `Sah76SettingsEffectIT.aSlotBeforeTheTimeTheOrderWasChecked...` keeps 409 `dose-before-start` because its slot (6:00) is after the version start (the day before) and more than 60 minutes before the verification (10:00).

Not covered: section 4 (slot states ON_HOLD, STOPPED, REPLACED, QUERIED as MAR slots; EMR execution lines; card `vitalsDue`; Playwright; real cross-origin preflight).
