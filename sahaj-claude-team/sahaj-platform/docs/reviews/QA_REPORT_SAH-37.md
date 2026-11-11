# QA report · SAH-37 IPD patient card setup (release audit, DEC-032)

Author: qa-engineer. Dates: 9 and 10 Nov 2026 (final QA pass of 10 Nov 2026, 01:30 to 03:00 IST, after the angular-developer's fixes for D-11, D-17 and D-18). Story: SAH-37 (Clinic setup → Patient card, and the Admitted cards that follow it). Mockup screen: screen-map row 29 (`cfgIpCard`, `pcBar`, `pcPreview`, `pcCard`). Decisions: DEC-032 (the human's answers), DEC-027, DEC-028. Earlier audit of the same kind: `docs/reviews/QA_REPORT_SAH-35-36-38.md`.
Detail documents: `docs/test-plans/SAH-37-audit-validation.md` (Phase 0 field table and gap list, results in section 10), `docs/test-plans/SAH-37-audit-ui.md` (UI measure pass in sections 1 to 5, re-measure in section 7), `docs/test-plans/SAH-37.md` (AC to test, AC 67 to 69 added). Screenshots: `docs/test-plans/SAH-37-audit-shots/` (before) and `.../after-fix/` (after, 1366 and 375 px).

## Final verdict (10 Nov 2026, closing step, DEC-033): READY FOR RELEASE, apart from the human items in section 8

**D-11 is closed.** The tech lead applied option B of DEC-033 by default (the human can reverse it). I changed only the second assertion of the D-11 test, from "the page head height never changes" to "the tiles stay where they are first drawn", and kept the layout-shift assertion (under 0.1). Result:
- `sah37-audit-regression.spec.ts`, whole file, 3 runs in a row: **38 passed, 0 failed each time** (19 tests x 2 projects). ESLint on the file: clean.
- The changed test is still a real test. On the pre-fix build (the HEAD source built in the scratchpad, served with `E2E_DIST`) it **fails** in both projects, on three assertions at once: layout shift **0.2098** at 375 px, the first tile top **174 > 249**, and **16 elements** below the head moved (for example `<span> 184 > 259`, `<button> 530 > 605`).
- Whole e2e folder: not re-run (DEC-033 says so; only a test assertion changed). The last whole run (pass 3) was **2,397 passed, 45 skipped (by design), 2 failed**, and the 2 failures were exactly this test in the two projects. With those two now passing (3 of 3 runs), the folder stands at **2,399 passed, 45 skipped, 0 failed of 2,444** (derived, not measured in one run).
- Residuals 1 and 2 of DEC-033 are logged as **accepted Sev-4** (section 7). No Sev-1, Sev-2 or Sev-3 is open.
- The code reviewer reads the D-11 retry diff once more (DEC-033, Consequence).
- One thing to know: DEC-033 and my pass-3 notes said "keep the screen-reader check", but the old D-11 test never had one (the check was in my throw-away probe: the hidden copy has empty text and is `visibility: hidden`). I added it as a real assertion in this test (section 5.3), so nothing is lost.

## Earlier verdict (pass 3, 10 Nov 2026, after D-11 retry 1): NOT READY, one test still red (D-11), but the visible jump is gone

**Pass 3 result in one paragraph.** The sized-from-a-hidden-copy fix works for what the owner sees: on the default setup the four bed tiles are drawn once and never move (one tile top per width, for example 249 px at 375 px; layout shift 0.000 at every probed width from 320 to 768 px, light and dark), where the first-version build shifted 0.21 at 375 px and 0.23 at 621 px. The hidden copy is not in the accessibility tree or the page text. The settled page is identical to the earlier after-fix shots. **But the regression test I wrote for D-11 still fails 3 of 3 runs in both projects**, on its second assertion ("the page head keeps one height"): the head is 80 px for the first frames and 124 px once the list arrives, because the "Admit patient" button only exists after the list answers (the developer's stated limit 2). The layout-shift assertion of the same test passes. Because a failing test is reported and not changed, the verdict stays NOT READY; the tech lead decides between (a) the angular-developer reserving the button row too, or (b) accepting that the tiles do not move and telling me to change that assertion to "tile tops stay put" (numbers in section 5.2). Two smaller residuals are also in 5.2 (a failed lead call shifts the tiles up; a tenant with other values shifts 19 px at a few widths).

### Earlier verdict (final pass, before the retry): NOT READY (one open Sev-3, D-11; D-17 and D-18 are fixed and verified)

**Final QA pass, 10 Nov 2026.** The angular-developer's fixes were built (`frontend/dist` rebuilt) and re-measured in a real browser:
- **D-17 fixed.** The Standard "Admitted on" shows in full (Admitted at 1150 px, preview at 375 px). The test passes 3 of 3 runs and fails on the old rule (mutation check: 148 px of text in 121 px).
- **D-18 fixed.** `layout-matrix.spec.ts` at `layout-375`: 12 of 12 pass (the two tests that failed before, lines 252 and 295, pass now).
- **D-11 NOT fixed.** The IPD page still jumps at phone width: layout shift **0.19** at 375 px (it was 0.21 to 0.26), 3 of 3 runs, both projects, and the same in the dark theme. The developer's change reserves two lines for the lead text, but the lead text is **not what moves**. What moves is the "Admit patient" button: with the short lead "Beds and admissions" it sits beside the heading, and when the long lead arrives it wraps to a row of its own under the lead. The page head grows from 68 px to 124 px (+56 px, the 44 px button and its gap) and the four bed tiles jump down by 56 px. Measured values and a fix idea are in section 7.
- No Sev-1 or Sev-2 is open. The rest of the gate is green (section 5): lint, 2,578 unit tests, build, and one whole e2e folder run of 2,444 tests: 2,396 passed, 45 skipped, 3 failed (the D-11 test in both projects, and one timing flake in `medicine.spec.ts` that also happens on the old source and that I fixed in my own test).

That condition was met in the closing step: D-11 passes with the changed assertion (DEC-033), so the final verdict above is READY FOR RELEASE, apart from the human items in section 8 (contrast tokens, go-live conditions, the ₹ format question).

## 1. What was done (phases 2 to 5)

| Phase | Work | Result |
|---|---|---|
| 3 and 4, integration | 11 new tests: 5 in `CardSetupIT` (V15), 6 in `CardSetupStoredIT` (`schema_version`). | `CardSetupIT` 33 of 33, `CardSetupStoredIT` 16 of 16. |
| 2, re-measure | The audit spec at 10 variants (1920, 1366, 1150, 1024, 768, 375, 125 % zoom of 1920 and of 1366, dark at 1366 and 375) plus the focus pass, on a fresh production build. | 13 of 13 ran. 13 of the 16 defects fixed (D-6 for Expanded only), D-16 is not a defect, D-9 is the human's, D-11 is open; one new defect, D-17. |
| 3, browser regression | New `frontend/e2e/sah37-audit-regression.spec.ts`: 19 tests, run in two projects (38 runs). | First pass: 34 pass, 4 fail (D-11 and D-17, both projects). Final pass: 3 runs in a row, identical: 36 pass, 2 fail (only the D-11 test, both projects). Mutation check: 7 of 7. |
| 4, final gate | `validate backend`, lint, unit, build, regression 3 times, the whole e2e folder (includes the layout matrix and every IPD spec). | See section 5. One more defect (D-18) and two harness items found. |
| 5, final QA pass (10 Nov 2026) | Rebuild, regression 3 times, `layout-matrix` at `layout-375`, re-measure (`AUDIT_UI=1`, 375, 375 dark, 1366), 320 px and 360 to 768 px probe, uniform-grid height probe, whole e2e folder, lint, unit, build. | D-17 and D-18 fixed and verified. D-11 still open, with its true cause found (the Admit button wraps). One flaky test deflaked. Section 5.1. |

## 2. Validation matrix (field × rule × layer), state after the audit

Layers: Screen = Angular, Server = Spring, DB = PostgreSQL. ✔ enforced, n/a not the layer's job. Before the audit 5 rules were missing in one layer (M-12, M-15, M-16, M-36, M-41); all five are now ✔. Full Phase 0 table (47 fields, 41 rules): `docs/test-plans/SAH-37-audit-validation.md` sections 2 and 3.

| Field | Rule | Screen | Server | DB | Test |
|---|---|---|---|---|---|
| Layout | compact, standard or expanded (V1) | ✔ 3 buttons | ✔ | n/a (accepted, ADR-015 D1) | `CardSetupIT.everyRuleOfTheValidator...` |
| Cards per row | 2, 3, 4, integer (V2) | ✔ 3 buttons | ✔ strict type | n/a | same |
| Sections | 7 keys once, header first and on, safety sections on (V3 to V5) | ✔ no switch, pills | ✔ | n/a | same |
| Field on or off, order, section | 25 keys once, name first, safety fields on and in an "on" section (V6 to V9) | ✔ toast on refused edits | ✔ | n/a | same, `aSafetyFieldMayMoveToAnotherSection...` |
| Label, safety fields | empty or the standard name (V7, V10) | ✔ box disabled | ✔ | n/a | same |
| Label, length | at most 40 code points (V11, V14) | ✔ `maxlength` and inline message "Keep the label to 40 characters." | ✔ | n/a | `theLabelLimitIs40CharactersNotBytes...`; e2e G-2 |
| Label, characters | no control, invisible or direction character (V12) | ✔ inline message on blur and submit (G-2) | ✔ | n/a (cannot) | `everyRuleOfTheValidator...`; e2e G-2 |
| Label, trim and own name | trimmed, NFC; the exact standard name is stored as empty; "bed" stays "bed" (AC 69) | ✔ same canonical form (G-3) | ✔ | n/a | `anOwnNameInAnyCaseAndANewLabelAreAccepted...`; e2e G-3 |
| Label, duplicate | not another field's standard name or label, later field gets the error (V15, AC 67) | ✔ mirror, inline | ✔ 14 cases | n/a | `aLabelThatDuplicatesAnotherFieldsNameIs400...`, `v15IsReportedLast...`, `v15IsPerClinic...`, `aStoredDuplicateSetup...` |
| Error display | every server 400 message next to its box, focus on the first, nothing sent when a label is in error (AC 68) | ✔ (G-1) | ✔ all errors together, ordered by rule, no typed text | n/a | e2e G-1 (4 tests) |
| Quick actions | 5 keys once, any boolean (V13) | ✔ | ✔ | n/a | `everyRuleOfTheValidator...` |
| Body shape | exact properties and types, 64 KB | typed | ✔ 400, 413 | ✔ object and 16 KB | `aBodyThatIsNotTheDeclaredShape...` |
| baseVersion, draftRevision | tokens, 409 on stale | ✔ from the last answer | ✔ | ✔ check ≥ 1 | `aStaleDraftRevisionIsRefused...` and the 2 stale-editor tests |
| `schema_version` (G-5) | present, default 1, check ≥ 1; a higher stored value is read as the standard setup with `fallback` | n/a | ✔ writes 1, falls back on > 1 (live and draft) | ✔ column, check, default | 6 new tests in `CardSetupStoredIT` |
| Role | owner writes; the four list roles read; others 403 | ✔ disabled or absent | ✔ 403 before the body is read | n/a | `everyWriteByAnyoneButTheOwnerIs403...`, `v15IsPerClinic...` (7 roles) |
| Tenant | clinic from the sign-in, never the body; other clinic invisible | n/a | ✔ | ✔ forced RLS, grants | `anotherClinicsSetupDraftAndVersionsAreInvisible...`, `withNoClinicBoundNothingIsVisible...`, `schemaVersionIsInvisibleToTheOtherClinic...` |
| Double submit | one version | ✔ busy lock | ✔ lock, 409 | ✔ unique key | `twoPublishesAtTheSameMoment...` |

## 3. Defects found and fixed

Severity: Sev-1 loses data or safety; Sev-2 a main control or clinical value unusable or hidden; Sev-3 visible glitch or missing rule with a workaround; Sev-4 polish. Files and lines are the fixes as they stand now.

| ID | Screen or API | Sev | Root cause | Fix | File:line | Test |
|---|---|---|---|---|---|---|
| G-1 | Fields tab, a server 400 | 3 | `fieldErrors` thrown away; one banner line | Each label path resolved through the setup that was sent and shown under its box (`.ferr`, `aria-invalid`, `aria-describedby`); focus to the first box; the other messages listed in the banner | `card-setup.store.ts:675-703`, `fields-tab.component.html:53-85`, `ipcard.page.html:89-102` | e2e G-1 ×2 |
| G-2 | Fields tab, label box | 3 | no browser check of V11 and V12 | Mirror of the server rules, shown on blur and on submit; nothing is sent while a label is in error | `card-label.rules.ts:129`, `card-setup.store.ts:169-179`, `:452-457`, `:484-487` | e2e G-2 ×2, G-1 |
| G-3 | pill, Reset, Publish | 4 | the browser counted the exact standard name as a change; the server stores it as empty | canonical form in the browser | `card-setup.logic.ts:43-60` | e2e G-3; IT own-name |
| G-4 | `PUT /draft`, `POST /publish` | 3 | a label could duplicate or impersonate another field (human approved V15) | V15: later field gets "This name is already used by another field." at `…fields[j].label`, ordered last, standard names of all 25 fields reserved, not applied on read | `CardSetupValidator.java:34`, `:161-178`, `:184` | IT, 4 new tests |
| G-5 | `ipd.card_setup_version`, `ipd.card_setup_draft` | 3 | jsonb without `schema_version` (`.claude/rules/postgresql.md`) | new forward-only migration; app writes 1; a higher value reads as the standard setup, `fallback: true` | `V2026_12_07_1000__ipd_card_setup_schema_version.sql:12-30`, `CardSetupRepository.java:21`, `CardSetups.java:52,68-78`, `JdbcCardSetupRepository.java:26,42-58,104-126` | IT, 6 new tests |
| G-10 | Admitted search | 4 | no Unicode normalisation | NFC before the compare | `admitted.model.ts:96` | developer's unit tests |
| D-1 | Fields and Layout tabs, 1100 to 1300 px | 2 | split at a window breakpoint although the page sits in the rail and the setup menu | page is a size container; editor above the preview under 880 px; label box minimum width | `ipcard.page.scss:7-8,95-105`, `ipcard-editor.scss:208-210` | e2e ×2 |
| D-2 | Fields tab, 375 px | 2 | no phone rule for the rows | rows stack in a narrow editor | `ipcard-editor.scss:244-266` | e2e ×2 |
| D-3 | Status bar, 375 px | 2 | sticky at every width; the mockup is static on phones | restored | `ipcard.page.scss:108-112` | e2e |
| D-4 | Card and preview, BP on | 2 | mockup rule `.vt.k-bp` missing | restored | `card-vitals.component.scss:94-100` | e2e + mutation |
| D-5 | Admitted, preview, setup | 3 | one long word pushed the card wider (up to 278 px) | `overflow-wrap: anywhere` on `dt` | `patient-card.component.scss:182-188` | e2e ×2 |
| D-6 | Expanded card | 3 | "Admitted on" cut in a three-column card | wraps in Expanded | `patient-card.component.scss:253-265` | e2e + mutation |
| D-7, D-8 | Preview column | 3 | preview taller than the window; fixed offsets under a two-row bar | max height, inner scroll, offset from the measured bar height | `ipcard.page.scss:73-80`, `ipcard.page.ts:101-106` | e2e |
| D-10 | Move arrows and switches | 3 | `aria-label` only | `title` added | `layout-tab.component.html:130-153`, `actions-tab.component.html:45-67` | developer's unit tests |
| D-12 | Move arrows, pill buttons, dialog buttons | 3 | under 44 px | 44 px; the patient-name link keeps a 20 px box with a 44 px hit area (accepted, tested) | `ipcard-editor.scss:236-239`, `ipcard.page.scss:36-38`, `patient-card.component.scss:80-84` | e2e ×2 + mutation |
| D-13 | Leave dialog, 375 px | 4 | floating, not docked | `sheet-panel` | `ipcard.page.ts:168` | screenshot |
| D-14 | Publish button | 4 | width changed while "Publishing" | minimum width 112 px | `ipcard.page.scss:41-43` | measure |
| D-15 | Admitted toolbar, 375 px | 4 | mockup rule missing | view words hidden on phones; the leftover (39 px wide buttons, D-18) is fixed below | `admitted-panel.component.scss:127-131` | measure |
| D-17 | Standard card, Admitted at about 1150 px and the preview at 375 px | 3 | the `dd` rule cut "Admitted on" with an ellipsis (D-6 was fixed for Expanded only) | `dd` wraps in every layout (`white-space: normal; overflow-wrap: anywhere`; mockup line 2079; mockup line 2368 says the opposite for the uniform grid, DEC-032 records the default applied) | `patient-card.component.scss:191-198` | e2e + mutation (148 px of text in 121 px on the old rule) |
| D-18 | Admitted toolbar, 375 px | 3 | the icon-only Cards and Table buttons were 39 px wide | `.pcview button { min-width: 44px }` | `admitted-panel.component.scss:134-138` | `layout-matrix.spec.ts` at `layout-375` (12 of 12) |

Every Sev-2 of the first measure pass (D-1 to D-4) is fixed and has a regression test that fails when the old rule is put back (section 4).

## 4. Browser regression tests and the mutation check

`frontend/e2e/sah37-audit-regression.spec.ts`, 19 tests, mocked API (`sah37-world.ts`). Final pass, 3 runs in a row on the rebuilt build: **36 pass and 2 fail each time**, the same 2 (the D-11 test in the two projects). The first pass had 4 failing (D-11 and D-17). The first run had 5 failing tests that were mistakes in my own tests (an arrow count, a caret position, a hit test below the window, a focus read before the effect, a probe under a sticky bar); they were corrected in the test, not by loosening an assertion.

| Test | Defect or AC | Result |
|---|---|---|
| no sideways scroll, 40 W label, setup page (1366, 1150, 375) | D-5 | pass |
| no sideways scroll, 40 W label, Admitted Standard and Expanded (1366, 1150, 375) | D-5 | pass |
| BP tile does not cover Pulse, Admitted and preview, text ends before Pulse | D-4 | pass |
| every label box at least 120 px at 1920, 1366, 1150, 1024, 768, 375 | D-1, D-2 | pass |
| preview not under the status bar, 1366 (stacked), 1536 and 1920 (sticky), last line reachable | D-7, D-8 | pass |
| bar static at 375, sticky at 1366 | D-3 | pass |
| editor above the preview at 1100, 1150, 1200, 1300; beside it at 1920 | D-1 | pass |
| rows stacked at 375 | D-2 | pass |
| Expanded "Admitted on" in full at 1366, 1024, 768, 375 and in the preview | D-6 | pass |
| Move arrows 44 by 44 on three tabs at 1366 and 375 | D-12 | pass |
| patient-name link 44 px hit area at 1366 and 375 | D-12 | pass |
| duplicate name under the box on blur, aria-invalid, clears on a keystroke | G-2, AC 68 | pass |
| name check blind to case, spaces and no-break spaces; safety box shows its own name | G-2, AC 67 | pass |
| nothing sent when a label is in error; focus on the first box | G-1, G-2, AC 68 | pass |
| server 400 with a label path under that box, other messages in the banner, clears on a keystroke | G-1, AC 68 | pass |
| server 400 path resolved through the body that was sent | G-1 | pass |
| own standard name is unchanged, a case variant is a change | G-3, AC 69 | pass |
| Standard "Admitted on" in full (Admitted 1150, preview 375) | D-17 | pass (was FAIL: 148 px of text in 121 px) |
| **layout shift under 0.1 and the tiles stay where they are first drawn (375 and 768 px)** | **D-11** | **pass (closing step, DEC-033: 3 of 3 runs, both projects). Was FAIL: 0.19 at 375 px; page head heights seen 68 then 124. Fails on the pre-fix build: 0.2098, tile 174 > 249, 16 elements moved** |

Mutation check (a temporary copy of the production build, served with `E2E_DIST`, one old rule put back in the built CSS, the matching tests run on it):

| Old rule put back | Test that failed | Why |
|---|---|---|
| split at the window breakpoint (editor stays beside the preview) | editor above the preview | preview below the editor expected at 1100 px; it was beside it |
| no label minimum width, no stacked rows | label boxes, stacked rows | name box 68 px, control not below the box |
| no `.vt.k-bp` rule | BP tile | unit `inline`, expected `none` |
| bar sticky on phones | bar static at 375 | `sticky`, expected `static` |
| `nowrap` and ellipsis on the Expanded date | Expanded date | text 148 px in 100 px |
| 40 px Move arrows | Move arrows | 40, expected at least 44 |

| nowrap and ellipsis on every `dd` (the old D-17 rule, `E2E_DIST` copy) | D-17 and D-6 | Admitted at 1150 px: 148 px of text in 121 px; Expanded at 1366 px: 148 in 100 |

| the pre-fix build (HEAD source built, `E2E_DIST`), D-11 test as changed by DEC-033, both projects | D-11 | layout shift 0.2098 at 375 px; first tile top 174 > 249; 16 elements below the head moved (`<span> 184 > 259` ... `<button> 530 > 605`) |

8 of 8 detected, each for the right reason. The temporary copies are in the scratchpad, not in the repository. `frontend/e2e/serve.mjs` got one optional variable `E2E_DIST` for this; unset in every normal run.

## 5. Final gate (first pass; the final pass is in 5.1)

All runs on the production build made at 00:39 on 10 Nov 2026 (no source change after it), locks held, the human's dev server on 8080 untouched.

| Step | Result |
|---|---|
| `node scripts/validate.mjs backend` | **PASSED**, exit 0. Unit **3,557**, 0 failed. Integration **1,769**, 0 failed, 26 skipped (the existing dev-admin precondition skips). Integration was 1,758 before the 11 new tests. The run applied `V2026_12_07_1000__ipd_card_setup_schema_version.sql` on a clean schema. |
| Targeted integration run, `CardSetupIT` and `CardSetupStoredIT` | 33 + 16 = 49 of 49 passed (the first run had 1 failure, a wrong order of two expected errors in my own new test; corrected). |
| Frontend lint | passes ("All files pass linting"). |
| Frontend unit, `npm --prefix frontend test -- --watch=false` | **166 files, 2,570 tests, all pass.** |
| Production build | passes. |
| `node scripts/validate.mjs frontend` | fails on the known `--browsers=ChromeHeadless` flag (the runner needs a browser package). Lint inside it passes. Human item. |
| `node scripts/validate.mjs docs` | fails only on `docs/stories/ADM-2.md` ("name files by story ID"). Human item. |
| New spec `sah37-audit-regression.spec.ts`, 3 runs in a row | 34 pass and 4 fail each time, the same four: the D-11 test and the D-17 test in each of the two projects. |
| Whole e2e folder, once (`npx playwright test --workers=4`, 40.5 min, 2,444 tests; includes the layout matrix at 7 sizes and every IPD spec) | **2,389 passed, 45 skipped, 10 failed.** The 45 skips are by design (the guarded measure specs and project-specific skips, as in the earlier audit; not itemised again). The 10 failures are listed below. |

The 10 failures of the whole-folder run:
| Test | Project | Cause | Status |
|---|---|---|---|
| `sah37-audit-regression` D-11 layout shift | phone-360, desktop-1440 | SAH-37 defect D-11 | **open**, test stays red |
| `sah37-audit-regression` D-17 Standard "Admitted on" | phone-360, desktop-1440 | SAH-37 defect D-17 | **open**, test stays red |
| `layout-matrix.spec.ts:252` cards and `:295` table, touch targets | layout-375 | SAH-37 defect D-18 (D-15 fix shrank the Cards and Table buttons to 39 px) | **open**, test unchanged |
| `pin.spec.ts:834` change history shows the plain label... | phone-360, desktop-1440 | harness gap: the Overview page (Patient card tile, SAH-37) reads `GET ipd/card-setup`, which `sah31-world.ts` did not mock, so `w.unmocked` held one entry | **fixed in the harness**: `sah31-world.ts:1308` now answers that GET with the same "Not needed here" 404 as the other Overview tile endpoints. No assertion was changed. The test passes 6 of 6 (3 repeats × 2 projects) after the change. |
| `emergency.spec.ts:1733` setup pending-save (phone-360) and `:796` age limits (desktop-1440) | one each | not SAH-37. Timing under 4 workers in a 40-minute run: two different tests, each in one project. | **not reproducible**: both tests and `pin.spec:834` pass 18 of 18 when re-run alone (3 repeats, 2 projects, 2 workers). Recorded as load flakes, not skipped or weakened. |

Reproduction for the open ones: `cd frontend/e2e && npx playwright test sah37-audit-regression.spec.ts layout-matrix.spec.ts --workers=4`. The first two print the measured values (148 px in 121 px; layout shift 0.21).
After the harness change in `sah31-world.ts` the whole folder was not run again (40 minutes). `pin.spec.ts:834` and the two emergency tests were re-run alone and pass. With the three open defects fixed, the whole folder should show 2,399 passed, 45 skipped and 0 failed (2,444 tests); that needs one more full run by QA after the angular-developer's fix.

### 5.1 Final QA pass (10 Nov 2026, 01:30 to 03:00 IST)

Locks: the frontend lock was held for the whole pass; no Maven ran; the human's dev server on 8080 was not touched. The production build was rebuilt first (passed) and again at the end (no source change in between).

| Step | Result |
|---|---|
| `sah37-audit-regression.spec.ts`, 3 runs in a row | **36 pass, 2 fail each time.** The 2 are the D-11 test (phone-360 and desktop-1440). The D-17 test passes 3 of 3 (and fails on the old rule, mutation check). |
| `layout-matrix.spec.ts`, project `layout-375` | **12 of 12 pass**, including the cards test (line 252) and the table test (line 295) that failed for D-18. |
| Re-measure, `audit-sah37-ui.spec.ts` (AUDIT_UI=1) at 375, 375 dark and 1366 | No new finding. The layout shift is still 0.233 on every Admitted screen at 375 px, light and dark (it was 0.258): one shift, of the four tiles (`section.kpis`, `div.tabs`) at about 400 ms. 1366 px: only the 0.017 of the "Publishing" state, as before. |
| Whole e2e folder, once (`npx playwright test`, 4 workers, 40.5 min, 2,444 tests) | **2,396 passed, 45 skipped, 3 failed.** The 45 skips are by design, as before. The 3 failures: the D-11 test in phone-360 and desktop-1440 (open defect), and `medicine.spec.ts:775` in desktop-1440 (a timing flake, next row). The 10 failures of the first pass (D-17 x2, D-18 x2, D-11 x2, pin x2, emergency x2) are down to the D-11 pair. |
| `medicine.spec.ts:775` "opened with the keyboard..." | Failed once in the whole run. Re-run alone it failed 5 of 12, then 1 of 20, and **it also fails 1 in 20 on a build of the old source** (the HEAD frontend files built in a scratch copy), so it is not caused by the D-11, D-17 or D-18 change. Cause: the test pressed Enter right after `focus()` while the admission sheet was still opening; the sheet's own initial focus moved and Enter landed on another button (the screenshot shows the sheet closed and the focus ring on "Open"). **Fixed in my test**: it now waits for `dialogReady(page)` before focusing. 30 of 30 pass after the change. No assertion was loosened. |
| Frontend lint | passes ("All files pass linting"). |
| Frontend unit tests | **167 files, 2,578 tests, all pass** (was 166 and 2,570). |
| Production build | passes. |
| `node scripts/validate.mjs backend` | **Not re-run.** `git status` shows no backend change since the green gate: the only modified backend files are my own `CardSetupIT.java` and `CardSetupStoredIT.java` (last written 9 Nov 23:31 and 23:29, before the gate of 10 Nov 00:39), and no file under `backend/` is newer than the first version of this report. The result stays: unit 3,557, integration 1,769, 0 failed, 26 skipped. |
| `node scripts/validate.mjs frontend` and `docs` | Not re-run; the same known failures as before (the `--browsers` flag; `ADM-2.md`). |
| Tracked images | **They were rewritten again** by the whole e2e run and the layout-375 run. `git status` shows 36 modified files in `docs/test-plans/SAH-35-36-38-shots/` and `docs/test-plans/SAH-39-shots/` (images and `.json`; the three `layout-results/*layout-375.json` files now hold the D-18 fix, "Cards 44x44", not "39x44"). I did not restore them. They are not part of this story. |

**Extra checks asked for in this pass (on the rebuilt build, measured in the browser; the product code was not changed):**

1. **D-11 at 320, 360, 375, 414, 620, 621 and 768 px** (page head, lead and tile positions sampled every frame while the page loads). The cause is the "Admit patient" button wrapping, not the lead text:

| Width | Page head before the lead arrives | After | The tiles move |
|---|---|---|---|
| 320 | 124 px (the button is already on its own row; lead reserved 38 px) | 143 px (the lead needs **three lines**, 58 px) | **+19 px** |
| 360, 375, 414 | 68 px (button beside the heading; lead reserved 38 px) | 124 px (button wrapped under the lead) | **+56 px** |
| 621 | 49 px | 105 px (button wrapped; lead one line) | **+56 px** (the reserve applies only up to 620 px) |
| 768 | 49 px | 49 px | 0 |

   So, does the lead need three lines at 320 px? **Yes** (58 px against the 38 px reserved): a smaller shift of 19 px, the second remaining part of D-11. (CLS numbers from my probe are not quoted: its observers add up across widths.)
2. **Dark theme at 375 px.** The same as light: shift 0.233 on every Admitted screen, in the same elements; no dark-only finding (the contrast items are the first pass's D-9).
3. **Uniform grid (`.pcgrid.uni`, `grid-auto-rows: 1fr`, so the tallest card sets the height of every card).** Measured on the Standard layout with the new rule and with the old rule (a build copy with `nowrap` and the ellipsis put back). Cards are 484 px at 1366 and 462 to 484 px at 900 to 1024 and at 768, the same with both rules, because the date fits there. Where the date used to be cut and now wraps, every card grows by one line: **+17 or +18 px at 1150 to 1280 px with 3 per row** (552 to 569, 572 to 590), **+18 px with 4 per row at 1200 to 1600 px** (552 to 569, 600 to 618) and **+35 px with 4 per row at 1150 px** (600 to 635, two values wrap). Compact and Expanded cards did not change. All cards in a row stay the same height and nothing scrolls sideways. This is the cost of wrapping that DEC-032 names; I judge it acceptable (a cut admission date is worse), and the human can still choose the mockup's line 2368 instead.

### 5.2 Pass 3 (10 Nov 2026, 02:56 to 04:10 IST): D-11 after retry 1 (the hidden "sizer" copy of the lead)

Locks: the frontend lock was held for the whole pass; no Maven ran (the only Maven process is the human's spring-boot:run on 8080, not touched). `frontend/dist` rebuilt first. No product code changed. A throw-away probe spec (zz-d11-probe.spec.ts) was used and deleted again.

| Step | Result |
|---|---|
| `sah37-audit-regression.spec.ts`, 3 runs in a row | **36 pass, 2 fail each time**: the D-11 test in phone-360 and desktop-1440. Message: `the page head height changed while the page loaded at 375 px: 80 > 124`. The layout-shift assertion (under 0.1), which comes first in the test, passes. |
| `layout-matrix.spec.ts`, project `layout-375` | **12 of 12 pass** |
| Whole e2e folder, once (4 workers, 40.3 min, 2,444 tests) | **2,397 passed, 45 skipped (by design), 2 failed**: only the D-11 test in the two projects. `medicine.spec:775` and the emergency tests passed. |
| Lint, FE unit tests, build | lint passes; **167 files, 2,582 tests pass**; build passes |
| Backend | `git status`: no backend file changed (only my `CardSetupIT.java` and `CardSetupStoredIT.java`, as before). `validate backend` not re-run; the result stays unit 3,557, integration 1,769, 0 failed, 26 skipped. |

**What was measured** (every animation frame: page head height, Admit button present, tile top, layout-shift API; probe at 320, 360, 375, 414, 540, 620, 621, 700, 767, 768 px, light and dark = 20 runs per scenario):

1. **Default setup (charges at midnight, alert 80 %), all 20 runs: the tiles are drawn once and never move.** Layout shift 0.000 everywhere. Tile top 314 px at 320, 249 at 360 to 540, 184 to 188 at 620 to 700, 132 at 767 and 768. Final page head 143 / 124 / 105 / 49 px. Before (first version, same probe on the built HEAD source): shift **0.210 at 375 px, 0.230 at 621 px, 0.033 at 320 px**, tiles 174 px then 249 px at 375. So the jump a user would see is gone. The lead text swaps from "Beds and admissions" to the full line with no height change.
2. **Why the test still fails.** The head grows once, 80 to 124 px at 375 px (49 to 105 at 621, 99 to 143 at 320), in the **same frame in which the list arrives and the tiles are first drawn**. Before that frame nothing is drawn below the head (no tile, no skeleton), so no element moves; that is why the shift is 0. The cause is the "Admit patient" button, which exists only when the list says `canAdmit`. Order of the calls made no difference: list first; list 700 ms after the lead calls; lead calls 500 ms after the list; lead call 1.5 s late. Tiles never moved in any of them (shift 0.000, one 0.001 at 320 px). The Admit button "appears late", as the developer said, but together with the tiles.
3. **Limit 1, tenants with other values.** Lead longer or shorter than the copy (24 hours instead of midnight; alert 100 %; 5 %), 39 widths from 320 to 700 px in steps of 10, light: the tiles move **19 px** (one line), layout shift 0.010 to 0.016, at these widths only: 600, 610, 620 (24 h and 100 %); 600 and 610 (24 h and 5 %); 600 (midnight and 100 %); 320 (midnight and 5 %). Every other width 0. Midnight with 80 %: none at any width. A different tenant moves by one line at a few widths, not by the 56 to 75 px of before.
4. **A failed lead call (new in kind).** When the charges call and the deposit-alert call both fail, the head was laid out for the long lead and then shrinks, so the tiles move **up**: 75 px at 375 px (249 to 174), 56 px at 621, 38 px at 320 (layout shift 0.189, 0.218, 0.033). One call failing: 19 px (0.016) at 320 to 375, 56 px (0.218) at 621. The built HEAD source did not move in this case (0.000). Light and dark identical. This is the cost of the fix on a rare path (two side calls fail while the list answers); I rate it Sev-4 for the owner, but it is a visible jump, so it is stated here.
5. **Screen readers.** While the lead is pending (lead call held for 4 s): the sizer is `visibility: hidden`; its textContent and innerText are empty (the words are generated content taken from a `data-like` attribute); the head's text is only the real lead ("Beds and admissions · deposit alert at 80% of the deposit"); the accessibility snapshot lists the heading, one paragraph with the real lead and the "Admit patient" button; a text search for "Bed and nursing charges" finds nothing. After the call answers the snapshot shows the full lead. Not exposed.
6. **The settled page is unchanged.** Admitted (Compact, Standard, Expanded), the Overview and the empty state at 375 and 1366 px, compared pixel by pixel with the earlier after-fix shots: same size (for example 375 x 1984 and 1366 x 773) and the same tile and card positions. The only differences are the top-bar clock (the time of the run) and, at 375 px, the Cards and Table buttons (the D-18 fix made them 44 px wide, after the old shots). The settled DOM is the plain `<p class="lead">`. There is no earlier shot at 1440 px; its numbers (tile top 132, head 49 px, no wrap) equal those at 1366 px.
7. No sideways scroll at any probed width or scenario (scroll width equals client width).

**Decision needed from the tech lead (QA does not change a failing assertion on its own):**
- Option A (product): the angular-developer keeps the Admit button's row from the first paint (for example reserves the button's height until the list answers, then drops it if `canAdmit` is false); and, for item 4, a failed lead call must not move the tiles (keep the reserved height, or draw the default lead text on failure).
- Option B (test): accept that the tiles do not move and tell QA to change the second assertion from "one page head height" to "the tile tops do not change after they are drawn". Items 3 and 4 would then be logged as accepted Sev-4.

### 5.3 Closing step (10 Nov 2026): DEC-033 applied, D-11 test changed and re-run

Locks: the frontend lock was held (taken and released by me); no Maven; port 8080 not touched; `frontend/dist` unchanged (built 03:14, after the last source change at 02:54). No product code changed.

What the D-11 test asserts now (one observer for the whole test, sampled every animation frame, at 375 and 768 px; the assertions are `expect.soft`, so one run reports every failing part):
1. layout shift under 0.1 (kept);
2. the first tile (`[data-kpi="occupied"]`) was drawn, and its top has **one value** from its first frame to the end (this replaces "one page head height"; the head heights are still printed in the layout-shift message);
3. extra: every drawn leaf element in `main` below the page head keeps the top it had in the first frame in which it was drawn (nothing moves, not only the tiles);
4. extra, the screen-reader check: every `.sizer` (the hidden copy of the lead) is `visibility: hidden` and has empty text at every frame;
5. no unmocked API call (kept).
The comment in the test cites DEC-033 and says why the head height was only a proxy.

| Step | Result |
|---|---|
| Mutation: pre-fix build (`scratchpad/fe-head/dist`, built from the HEAD source), `E2E_DIST` + `E2E_PORT=4411`, `-g "D-11"` | **2 failed (both projects)**: layout shift 0.2098 at 375 px (head heights 49 > 124); tile top changed 174 > 249; 16 elements moved. The test is still real. |
| `sah37-audit-regression.spec.ts`, whole file, 3 runs in a row, both projects (the current build) | **38 passed, 38 passed, 38 passed**. Each run 1.1 to 1.2 min. |
| ESLint on the spec | clean |
| Tracked images | I did not restore any. This spec writes no images; the 29 modified `.png` files in `git status` are the earlier side effect of the whole-folder run (R-8). |

Residuals accepted as Sev-4 (DEC-033): see section 7. They are not asserted by any test.

## 6. What was seen at 1366 and 375 px (screenshots in `docs/test-plans/SAH-37-audit-shots/after-fix/`)

- **1366 px.** The Fields tab shows label boxes 479 to 570 px wide on every row, the switch, pill and 44 px arrows aligned on one right edge. The editor is above the preview (the page is about 800 px wide inside the rail and the setup menu), so the preview is below the editor and the status bar (sticky at this width) stays at the top. With a draft the bar is two rows (98 px) and nothing is hidden under it. Admitted shows the BP tile at 85 px with no unit, Pulse starting 6 px after it, and "Admitted on" in full in both layouts. No sideways scroll anywhere, also with 40 W labels.
- **375 px.** The status bar is static. Each row has the label box on its own line (243 px), the control and arrows under it, and "Early warning" no longer breaks word by word. The leave dialog is docked to the bottom with 44 px buttons. The Admitted card is one column; Expanded shows "4 Nov 2026, 9:20 AM IST" on two lines. Cards and Table are icon buttons, 39 by 44 px, one pixel row short of the 44 px rule (D-18). The Admit tab count "Admitted 3" wraps to two lines (IPD shell, not this story).
- **What is still wrong at these sizes (final pass):** only D-11. At 375 px (and 360 to about 700 px) the first paint of the IPD page jumps 56 px when the long lead arrives and the "Admit patient" button wraps under it. The Standard "Admitted on" is now in full (D-17 fixed) and the Cards and Table buttons are 44 px wide (D-18 fixed): `Cards 44x44` and `Table 44x44` in `layout-results/admitted-cards__layout-375.json`.
- Console, network, loading and error states, the leave dialog, keyboard order, the dark theme, 125 % zoom: clean, as in the first pass (UI report section 7).

## 7. Defects open, accepted and deferred

**Open: nothing of Sev-1 to Sev-3. D-11 is CLOSED (10 Nov 2026, DEC-033, option B by default; the human can reverse it).** On the default setup the tiles are drawn once and never move (layout shift 0.000 at 320 to 768 px); the D-11 test now asserts that and passes 3 of 3. Two residuals are **accepted Sev-4** (DEC-033; backlog for the angular-developer: hold the Admit button's row from the first paint, and keep the reserve when the lead calls fail):

| ID | Sev | What | Reproduction |
|---|---|---|---|
| D-11 residual 1 | 4 | A clinic whose deposit-alert percent or cut-off time differs from the defaults (midnight, 80 %) can see the tiles move one line (19 px, layout shift 0.010 to 0.016) at only 320, 600, 610 and 620 px. | Section 5.2 item 3 |
| D-11 residual 2 | 4 | If the charges call and the deposit-alert call both fail, the tiles move up 75 px at 375 px (249 to 174, shift 0.189). It shows less text, never a wrong value, and is rare. The old code did not move in that case. | Section 5.2 item 4 |

The row below is the finding before the fixes, kept for the record.
| ID | What | Measure | Where | Failing test |
|---|---|---|---|---|
| D-11 | The four bed summary tiles on `/app/ipd` jump down when the page head grows after the first paint. The developer's change (`reserveLead`, `min-height: 2lh` on the lead at 620 px and below) reduced the shift (0.26 to 0.19 at 375 px) but did not remove it, because the lead text is not the only thing that moves. With the short lead "Beds and admissions" the "Admit patient" button sits beside the heading (page head 68 px). When the charges and the deposit-alert percent arrive the lead becomes 38 px of text, the button no longer fits beside it and wraps under it (page head 124 px, +56 px). Probed every frame at 360, 375 and 414 px. At 320 px the button is already below and the lead needs three lines, not two (+19 px). At 621 px the same +56 px happens (the reserve applies only up to 620 px). At 768 px nothing moves. | layout shift 0.19 (regression test), 0.233 (audit) at 375 px; page head 68 then 124 px; light and dark | `page-head.component.ts` (the `.pagehead` row and the `.reserve` rule), `ipd.page.html:1-5`. Fix idea: while `reserveLead` is on, put the actions row where it will end up from the first paint (for example `flex-basis: 100%` on the actions block up to about 700 px, with the same rule kept once the lead is complete), and reserve three lines at 340 px and below. | (record) the test failed with `page head heights seen: 68 > 124` before the fixes. Closed: the D-11 test (layout shift under 0.1, tiles stay where first drawn) passes 3 of 3 in both projects |

**Fixed in the final pass (verified in the browser, section 5.1):**
| ID | Status | Test |
|---|---|---|
| D-17 | Fixed: the Standard "Admitted on" wraps and shows in full; costs one line of card height where it used to be cut (+17 to +35 px at about 1150 to 1280 px, section 5.1 item 3) | `D-17 ...` passes 3 of 3, fails on the old rule |
| D-18 | Fixed: the Cards and Table buttons are 44 by 44 px at 375 px | `layout-matrix.spec.ts` at `layout-375`: 12 of 12 |

**Deferred (Sev-1 and Sev-2: none).** Nothing of Sev-1 or Sev-2 is deferred. The Sev-4 items below were accepted in the Phase 0 report and stay as they are:
| ID | Sev | Reason | Reproduction |
|---|---|---|---|
| G-6 | 4 | accepted design: the database checks object and 16 KB only (ADR-015 D1, D3) | `docs/test-plans/SAH-37-audit-validation.md` section 7 |
| G-7 | 4 | accepted design: partial audit columns, `revision` plays the version role | same |
| G-8 | 4 | accepted: `DELETE /draft` with no or a bad `draftRevision` gives Spring's generic 400, as the contract says; the screen never sends it | `DELETE .../draft?draftRevision=abc` |
| G-9 | 4 | optional counter not built; the box stops at 40 UTF-16 units without a message | paste 45 letters into a label box |
| G-11 | 4 | accepted: the number of validation errors is not capped; bounded by the 64 KB body and owner only | a body with 1,500 unknown sections |
| D-14 | 4 | accepted: the bar wraps under the pills at 1366, as the mockup does | 1366 px with a draft |
| D-13 | 4 | accepted: `aria-modal` is `false`, the Material default for every dialog of the app | open the leave dialog |
| D-16 | 4 | not a defect: decorative separators | none |
| shell | 4 | the IPD tabs "Admitted 3" and "Safety 2" wrap to two lines, the shell icon buttons (theme, log out, open menu) have no title, "Simple" and "Advanced" are 32 px high: outside SAH-37, for the shell owner | any IPD page |

## 8. Items for the human (accepted or needing a decision; none is a failing test)

1. **D-9 contrast.** The human changes `docs/design/tokens.md`; the developer then applies the same values in the app. Pairs and passing values are in DEC-032 (light `--ink-3` 4.01 to `#5E6E84` 4.57; teal pill 3.58 to `#0C786E` 4.52; `--good` on `--good-soft` 4.17 to `#147A49` 4.50; dark `--ink-3` 4.38 to `#8799B4` 4.55). The re-measure shows the same failing pairs as before (not changed, as agreed). The "Required" and "Always on" pills were measured at 3.66:1 and are not in the DEC-032 list yet.
2. **The ₹ format.** CLAUDE.md writes "₹ 1,25,000" (with a space); the mockup and the shared formatter write "₹1,25,000". The screens follow the mockup. A rule for the human to settle in one of the two places.
3. **The Workflow stages tab** (OQ-37-3): in the mockup, not built. Not a defect.
4. **Go-live conditions.** DEC-027: a clinician signs off the default card and the vital tile marks. DEC-028: confirm O1 to O7 and the addendum; the privacy register entry for diagnosis, vitals and mobile on the ward list (O3).
5. **Security finding 5** (fictional names and numbers in the e2e data): still a backlog item; the regression spec uses the same fictional patients of `sah37-world.ts`.
6. **Approvals** in `docs/approvals.md`: `SAH-37 | stories`, `contract` (the wording changed, AC 67 to 69 and the V15 text) and `merge`.
7. **Known tooling items, not fixed here:** `node scripts/validate.mjs frontend` fails on the `--browsers` flag; `node scripts/validate.mjs docs` fails on `ADM-2.md`. Both are unchanged.
8. **Consequence of the D-1 decision to know about:** at 1366 px and below (page narrower than 880 px) the live preview is below the editor and is not in view while the owner edits. It follows the approved "stack sooner" answer.

## 9. Risks

- **R-1** D-11 is closed under DEC-033 (option B by default, reversible by the human). Residuals 1 and 2 stay as accepted Sev-4 and are not covered by a test. D-17 and D-18 are fixed.
- **R-2 (process)** `frontend/e2e/` is gitignored; new and changed specs need `git add -f`. The list is in the handoff.
- **R-3 (drift)** The browser mirror of V12 and V15 (TypeScript) and the server rule (Java) are two copies of the same key function (NFC, space collapse, case fold). The unit tests of both sides use the same cases and an integration test proves the server; there is no test that runs both against one list. A later change to one side can drift. The accepted limit (NFC, not NFKC, no look-alike letters) is written in the design note section 4.1.
- **R-4 (mock)** The browser tests use a mocked API; the real V15, the stored shape and the tenant checks are the integration tests. `sah37-world.ts` still counts the draft revision 1, 2, 3; the large-token path is covered by unit specs only.
- **R-5 (contract note)** `GET /card-setup` has no `fallback` property; only the Admitted list carries it. For a live setup written in a newer shape the owner's setup page shows the standard setup with `isStandard: true` and no warning. If a warning is wanted there, the contract needs the property (architect).
- **R-6** The G-9 counter was not built: a label of 40 emoji (80 UTF-16 units) is refused by the browser box although the server allows it. Stricter, never looser.
- **R-8 (side effect of the whole e2e run; seen again in pass 3, a third time)** The layout-matrix and home-background specs rewrite tracked files: 36 modified files now in `docs/test-plans/SAH-35-36-38-shots/` and `docs/test-plans/SAH-39-shots/`. They are not part of this audit and I did not restore them. The human can drop those changes (`git restore docs/test-plans/SAH-35-36-38-shots docs/test-plans/SAH-39-shots`). The three `layout-375` JSON files now show the D-18 fix (`Cards 44x44`); the human may prefer to commit those three and drop the rest.
- **R-9 (test timing)** `medicine.spec.ts:775` was a 1-in-20 timing flake that exists on the old source too; fixed in the test (it waits for the admission sheet). If the whole folder shows it again, report it.
- **R-7 (environment)** The backend gate ran on `sahaj_test` while the human's dev server held port 8080; no class-not-found failure was seen.

## 10. Proposed commits (the human commits; no agent commits)

Order, with the files each takes. `frontend/e2e/` is gitignored: use `git add -f`.
1. `fix(validation): label rules V15, inline label errors, schema_version for the card setup` (already in commit 62064bf: `CardSetupValidator`, `CardSetupService`, `CardSetupJson`, `CardSetups`, `CardSetupRepository`, `JdbcCardSetupRepository`, `CardSetup`, `CardSetupRepair`, migration `V2026_12_07_1000__ipd_card_setup_schema_version.sql`, and the Angular store, fields tab, label rules, logic). Nothing new from QA.
2. `fix(ui): patient card setup and Admitted layout (D-1 to D-8, D-10, D-12 to D-15)` (also in 62064bf). **Next two commits (D-11, D-17 and D-18 are all done and verified; the human commits):**
   - `fix(ui): Standard card shows Admitted on in full (D-17); Cards and Table buttons 44 px (D-18)`. Files: `frontend/src/app/features/ipd/admitted/patient-card.component.scss`, `admitted-panel.component.scss`, `sah37-layout-styles.spec.ts` (new).
   - `fix(ui): IPD page head is sized from a hidden copy of the lead, so the bed tiles do not jump on phones (D-11)`. Files: `frontend/src/app/shared/ui/page-head.component.ts` and `.spec.ts`, `frontend/src/app/features/ipd/ipd.page.ts`, `ipd.page.html`, `ipd.page.spec.ts`. The body should name the two accepted Sev-4 residuals (DEC-033).
3. `test(unit): label rules, V15 key, schema_version and layout unit tests` (in 62064bf: 63 Java and about 131 Angular unit tests).
4. `test(integration): V15 and schema_version integration tests for the card setup`. Files: `backend/src/test/java/com/sahaj/platform/it/CardSetupIT.java`, `CardSetupStoredIT.java`.
5. `test(e2e): SAH-37 audit regression spec and the re-measure spec`. Files: `git add -f frontend/e2e/sah37-audit-regression.spec.ts frontend/e2e/audit-sah37-ui.spec.ts`, and `frontend/e2e/serve.mjs` (tracked; the `E2E_DIST` variable). The D-11 test in that spec was rewritten in the final pass (one observer, and it prints the page head heights) and its second assertion was changed in the closing step under DEC-033 (tile tops and every element below the head stay put; plus the hidden-copy check). Suggested message: `test(e2e): SAH-37 audit regression spec and re-measure spec (D-11 asserts the visible outcome, DEC-033)`. Also `frontend/e2e/medicine.spec.ts` (one added `dialogReady` wait; a deflake, no assertion changed) and `frontend/e2e/sah31-world.ts` (the `GET ipd/card-setup` mock): use `git add -f` if they show as untracked.
6. `docs(qa): SAH-37 release audit report, test plans and after-fix screenshots`. Files: `docs/reviews/QA_REPORT_SAH-37.md`, `docs/test-plans/SAH-37.md`, `SAH-37-audit-validation.md`, `SAH-37-audit-ui.md`, `docs/test-plans/SAH-37-audit-shots/after-fix/` (13 MB, 52 files; the human can leave the images out).

Every commit ends with the attribution line the human's setup uses.
