# Test plan SAH-35 / SAH-36 / SAH-38 · Release audit DEC-025 (browser part, e2e)

Scope: the frontend fixes of the release audit (`docs/reviews/QA_REPORT_SAH-35-36-38.md`, "Phase 1-2 Fixes made") and the layout pass G-7. The backend part (G-1, G-4, G-12, G-13, G-14, G-16) is in `SAH-35-36-38-audit.md`.
Every API call in these tests is mocked in the browser (`page.route()`, `frontend/e2e/sah31-world.ts`, `sah32-world.ts`, `audit-world.ts`). They prove what the screens do with the answers; who gets a 403 and why is a backend integration test.
App under test: the production build (`npm --prefix frontend run build`), served by `frontend/e2e/serve.mjs` on port 4300.

## Gap to test to result

Result = last full run of the whole `frontend/e2e` folder (section "Run counts"), phone-360 and desktop-1440 for the older specs, seven window sizes for the layout matrix.

| Gap | Acceptance point | Test (file: name) | Result |
| --- | --- | --- | --- |
| G-2 | Admitted list, 60 s reload answers 403 forbidden: cards and tabs leave, server detail with "Try again", no patient name left in the page; Try again brings the list back | `audit-sah35-36-38.spec.ts` G-2 Admitted list: "403 forbidden ..." | pass |
| G-2 | 403 module-off: the lock page shows, no cards, no stale notice | same group: "403 module-off ..." | pass |
| G-2 | Network error and 5xx: the cards stay, "Could not refresh. Showing data from 11:17 AM." shows, no refusal text | same group: per-failure tests | pass |
| G-2 | Refused read when coming back from Safety to Admitted also clears the list | same group: "a read that is refused when the user comes back ..." | pass |
| G-2 | Safety board: 403 clears the board, the Medicine round and the audit trail; Try again restores; module-off; network and 5xx keep the board with "Could not refresh" | `audit-sah35-36-38.spec.ts` G-2 Safety board (4 tests) | pass |
| G-3 | Focus after Given goes to the Give button of the next row (same position) | `medicine-round.spec.ts` "Given: toast, the sheet closes ... the focus goes to the Give button of the NEXT row (same position, G-3)" | pass |
| G-3 | Last row: focus goes to the last Give of the shorter list; only row: to the panel heading; Record not given on the first row: to the new first row; Esc (nothing recorded): back to the same button; a row that stays gets the focus back | `medicine-round.spec.ts` "G-3: ..." (4 tests) and "a row that stays after a refusal ..." | pass |
| G-6 | 4 per row: small action text only above 1100 px; "Transfer", "Discharge", Nursing, EMR whole inside their buttons at 1366 and at 1150 to 1200 px | `audit-sah35-36-38.spec.ts` G-6 (one test per width) | pass |
| G-7 | Five screens at seven sizes, fit and 44 px targets (matrix below) | `layout-matrix.spec.ts` (12 tests per size) | pass (84 of 84) |
| G-9 | High-alert Given without a nurse and 4-digit PIN: nothing sent, the one contract text under "Their PIN" with the focus there | `mar.spec.ts` "G-9: high-alert without a nurse and PIN: NOTHING is sent ..." | pass |
| G-9 | Only the nurse missing (PIN typed): text under "Checked by", focus on the nurse list; then it is sent with nurse and PIN | `mar.spec.ts` "G-9: only the nurse is missing ..." | pass |
| G-9 | A medicine that is not high-alert needs no second nurse | `mar.spec.ts` "G-9: a medicine that is NOT high-alert ..." | pass |
| G-9 | Focus order of refusals (see "Changed test" below): PIN and nurse stopped in the browser; box, statement, wrong band and reason from the server | `mar.spec.ts` "a refusal moves the focus to the field that needs it (PIN, nurse in the browser; box, statement, wrong band, reason from the server)" | pass (3 of 3 repeats, both projects) |
| G-10 | Search box takes at most 100 characters on Cards and Table; the filter is instant, the polite line waits 400 ms after the last key and is replaced, never cleared | `admitted-cards.spec.ts` "G-10: ..." and "the announcement is replaced, never cleared ..." | pass |
| G-15 | Times print as `h:mm AM` with one plain space (no U+202F, no U+00A0): Admitted cards, Safety board and round, colleague notice ("until 11:20 AM IST."), Team history sheet | `audit-sah35-36-38.spec.ts` G-15 (4 tests) | pass |
| G-5 | Contrast of the mockup colours, measured (report only, never failing: colours are a human decision) | `audit-sah35-36-38.spec.ts` G-5 | pass (numbers below) |
| G-11 | Give sheet closes on Esc or backdrop without confirm (mockup behaviour, human decision) | `mar.spec.ts` keyboard test (Esc returns focus to the slot); no new test | open for the human |

## Layout matrix (G-7): width x screen

Seven projects (`playwright.config.ts`): layout-1920 (1920x1080), layout-1366 (1366x768), layout-1024 (1024x768, touch), layout-768 (768x1024, touch), layout-375 (375x667, touch), zoom125-1920 (1536x864 CSS px, device scale 1.25 = a 1920x1080 screen at 125 %), zoom125-1366 (1093x614 CSS px, device scale 1.25 = a 1366x768 screen at 125 %). The older specs also run at 360 and 1440. Each cell is two tests: `[fit]` (no sideways scroll, no clipped text, dialogs inside the window with the footer reachable, console clean, no 4xx asset, call counts, focus after close) and `[targets]` (44 px touch targets).

| Screen | 1920 | 1366 | 1024 | 768 | 375 | zoom 1920 (1536) | zoom 1366 (1093) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1a Admitted cards (3 and 4 per row) | pass | pass | pass | pass | pass | pass | pass |
| 1b Admitted table | pass | pass | pass | pass | pass | pass | pass |
| 2 Medicine round panel | pass | pass | pass | pass | pass | pass | pass |
| 3 Give sheet (high-alert, wrong-patient line, second-check text) | pass | pass | pass | pass | pass | pass | pass |
| 4 Colleague-tries banner | pass | pass | pass | pass | pass | pass | pass |
| 5 Team page PIN cells and history sheet | pass | pass | pass | pass | pass | pass | pass |

12 of 12 per size, 84 of 84 in total. Measurements: `docs/test-plans/SAH-35-36-38-shots/layout-results/*.json`; screenshots at 1366 and 375 px: `docs/test-plans/SAH-35-36-38-shots/*.png`.

## Contrast numbers (G-5, measured at 1440 px, WCAG 2.x, normal text needs 4.5)

| Element | Size and weight | Ratio |
| --- | --- | --- |
| Card flag "Going home today" text | 11.52 px, 700 | **3.60** (below 4.5) |
| KPI "Going home today" label (`.eyebrow`) | 11.52 px, 600 | **4.34** (below 4.5) |
| Stale notice text | 13.44 px, 400 | **4.28** (below 4.5) |
| Card `.pckv dt` ("Admitted on"), card identity line | 10.56 px / 12.16 px | 4.50 (at the limit) |
| Flags Vitals due / EWS (warn) | 11.52 px, 700 | 4.74 |
| Flags Deposit low / Allergy / Not seen (crit) | 11.52 px, 700 | 4.63 |
| KPI line (`.s`) | 12.16 px, 400 | 7.30 |
| Card bed label, action labels | 9.92 px / 11.2 px | 6.70 |
| KPI figure, card name, `.pckv dd` | 20.48 / 16 / 13.12 px | 15.63 to 16.19 |

Three measured values are under 4.5:1 (Going home today flag 3.60, KPI label 4.34, stale notice 4.28). The colours come from the mockup tokens, so the change is a human decision (G-5). Raw data: `SAH-35-36-38-shots/contrast-desktop-1440.json`, `contrast-phone-360.json`.

## Changed tests in this round (test side only; product code untouched)

1. `dialogReady` (`frontend/e2e/sah31-world.ts`) waits for `.mat-mdc-dialog-container.mdc-dialog--open` instead of `...--open:not(.mdc-dialog--opening):not(.mdc-dialog--closing)`. Reason: Angular Material 22.2.5 never removes `mdc-dialog--opening` if the first frame takes more than 150 ms, although the sheet is open and usable. `.last()` and the animation settle loop that follows are kept. Fixes `layout-matrix.spec.ts` lines 358 and 451 at layout-1920. The same one-line change was made in `safety.spec.ts` `openResponse` (it had its own copy of the old selector and failed once under load).
2. `mar.spec.ts` "a refusal moves the focus ...": since G-9 a high-alert Given without a second nurse and 4-digit PIN never leaves the browser, so the old order (box, statement, PIN, reason) cannot be reached on the high-alert medicine o2. The test now checks, in order: PIN (contract text under "Their PIN", focus there); nurse (PIN typed, text under "Checked by", focus on the nurse list, `doseCalls` still empty); then, with nurse and PIN typed, the server refusals that move the focus: empty box -> box, missing statement -> statement, wrong wristband -> box (mismatch toast), Record not given without a reason -> reason list. The PIN box is cleared after each refusal, so the test types it again before each send (as the wrong-PIN tests already show). No assertion was removed; the server-refusal focus cases for box, statement and reason are all still covered.
3. The Admitted table "Open" button is now 44 px (product fix by the owner): the 14 table-view checks of Layout matrix 1 pass.

## Performance note

At 1920x1080 the first frame of a dialog can take more than 150 ms because of the `backdrop-filter` layers behind it (the glass cards and the dialog backdrop). Angular Material's open animation then never clears `mdc-dialog--opening`, so a test that waits for the class to go times out on a sheet that is open and usable. It is not a functional fault, but a slow first frame on large screens is a real performance finding for the angular-developer (check how many `backdrop-filter` surfaces sit under the backdrop; consider fewer or none on `.cdk-overlay-backdrop` and on the cards behind it).

## Run counts

| Run | Result |
| --- | --- |
| Earlier audit run (before these fixes), whole folder | 690 passed, 12 failed |
| The 12 failing tests, 3 repeats each, after the fixes | layout matrix tests (Admitted table x 7 sizes, Give sheet fit and targets and Team page at layout-1920): all passed in 84 of 90 executions; the 6 others were the old `mar.spec.ts` focus test (phone-360, desktop-1440, 3 repeats) before it was rewritten. After the rewrite: 6 of 6 passed. Final: 12 of 12 distinct tests pass, 3 of 3 repeats |
| Whole `frontend/e2e` folder, once (2,000 tests: 4 workers, 38.3 min) | 1,986 passed, 8 skipped, 6 failed |
| Skipped (8) | by design: `app-shell.spec.ts` 1280 px block and `home.spec.ts` section links run once, only at desktop-1440 (`test.skip` with a reason) |
| Failed (6): `home-background.spec.ts` (partial SAH-39 work, reported separately) | 4: "1-7 no homepage text sits directly on the canvas" and "1-8 contrast: no text becomes worse than before the change", each at phone-360 and desktop-1440. 100 other tests of that file passed |
| Failed (6): other | 2, both passed 3 of 3 when re-run alone: `nursing.spec.ts:1266` at desktop-1440 ("bp height" measured 35.2 px, limit 36; a size measured under load) and `safety.spec.ts:1280` at desktop-1440 (opening-class race, selector fixed as in item 1) |
| After the fixes: `safety.spec.ts` whole file, 2 repeats | 424 of 424 passed (2 repeats, both projects, 6.7 min) |

No test was deleted or skipped to get a pass.
