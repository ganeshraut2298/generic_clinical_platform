# SAH-31 · IPD medicine orders, safety checks, pharmacist check and staff PIN · browser (Playwright) test plan and results

Story: `docs/stories/SAH-31.md` · Decisions: DEC-017, ADR-009, ADR-010 · Contracts: `docs/api/ipd.yaml` (EMR, medicine orders, pharmacist checks), `docs/api/platform.yaml` (`/me/pin`, `/me/pin/code`, `/team/{id}/pin/reset`, `TeamMember.pin`)
Mockup screens: EMR sheet (`pcEmr`, `sfIdBar`, `sfOrdBlock`, `sfCheck`, `sfordchk`, `sfstop`); IPD → Safety (`ipSafety`, `pendRow`, `sfver`, `sfverok`, `sfrej`, `sfrejok`); Clinic setup → Team and access (panel Staff, PIN pill); Overview checklist.
Specs (new; the whole folder `frontend/e2e` is ignored by git, so the human stages them with `git add -f frontend/e2e/medicine.spec.ts frontend/e2e/pharmacist.spec.ts frontend/e2e/pin.spec.ts frontend/e2e/sah31-world.ts`): `frontend/e2e/medicine.spec.ts`, `frontend/e2e/pharmacist.spec.ts`, `frontend/e2e/pin.spec.ts`. The shared mock `frontend/e2e/sah31-world.ts` is not a spec (it has no tests); the three specs import it. `frontend/e2e/safety.spec.ts` was also changed (see "Changes to existing specs").
Projects: `phone-360` and `desktop-1440` (every test runs in both); the layout tests also set 360 and 1280 px themselves.
Scope of this file: the browser part only. The backend integration tests (`*IT.java`, the rule engine, the PIN hashing and lock rules, tenant isolation, the audit rows) belong to the other QA run.

## How the tests work

- The backend is **not** running. Every API call is mocked in the browser with `page.route()` (the same pattern as `safety.spec.ts`, `nursing.spec.ts` and `emergency.spec.ts`), by one shared mock, `sah31-world.ts`. A call that is not mocked is answered 404 and the test fails on `w.unmocked`.
- The mock "server" mimics only what the screens depend on: the four answers of the order endpoint (PLACED 201, replayed 200, BLOCKED, NEEDS_REASON) with a stored client key (same key and values = replay, other values = 409 `client-key-reused`), the check token a reason must carry, the queue with cursor paging, the own-PIN check (right, wrong with the count, locked, not set), the weak-PIN and wrong-code 400s of the Set PIN call, and the rule "`pin` on every Team row for OWNER, on the own row only for everyone else". It is **not** proof of the real rules: the allergy, duplicate and interaction rules, the 14-medicine list, PIN hashing, the 5-tries lock and the third-lock switch-off, the rate limits and the audit rows are backend ITs.
- Per-test hooks (`w.orderAnswer`, `w.verifyAnswer`, `w.setAnswer`, `w.checksAnswer`, ...) force an answer for the n-th call: a problem, a lost connection (`'abort'`) or "stored, then lost" (`'lose'`).
- The 60-second polls and the lock expiry use the Playwright fake clock (`page.clock.install()` then `runFor`). The hidden-tab pause uses an init script that controls `document.hidden`, as in `safety.spec.ts`.
- The server clock is fixed at `2026-11-06T05:47:00Z` (11:17 AM IST); every time on screen comes from the answer.
- PINs and codes used in the tests are values that appear nowhere else (`7392`, `8146`, `5307`, `604185`, `1234`), so "never in the DOM, the storage, the console or the network log" is a real search, not a coincidence. `expectNowhere()` searches the page HTML, every field value, `localStorage`, `sessionStorage`, cookies and every request URL and body of the page; a request body may contain the value only on the named, intended request (`POST .../verify`, `PUT /me/pin`).

## Coverage: acceptance criterion → test

### medicine.spec.ts (EMR sheet and medicine orders)

| AC | What is checked | Test (describe › title, abbreviated) |
| --- | --- | --- |
| 1 | EMR button only when the server says `canOpenEmr` (OWNER, DOCTOR, NURSE yes; FRONT_DESK, BILLING, VISITING_DOCTOR, COUNSELLOR no, and the record is never read); the sheet is read once on opening; title "EMR · <name>", subtitle "<UHID> · <age> · <sex> · <doctor>"; the line "Opening this record is logged in the clinical audit trail."; Done closes it and the admission sheet stays | EMR button › `<role>: the EMR button shows...` (3), `<role>: canOpenEmr false...` (4), `Done closes...` |
| 1 | A refused role (403 `forbidden`): only "Clinical details are hidden for your role." with no name, allergy or order; 403 `module-off` is **not** the hidden notice | Locks and refused reads › `403 forbidden...`, `403 module-off...` |
| 2 | Identity bar, allergy chip in the three tones with words and an icon (not colour alone), red for any recorded text, strip Diagnosis and Day, age and sex left out when unknown | Allergy chip › 4 status cases, `the identity line leaves out...`; EMR button › strip assertions |
| 3 | Four pills and their colour classes, "High-alert" pill, small line with ` · override: <reason>` and ` · rejected: <reason>`, struck-through rows, Stop only where `canStop`, "No medicines ordered yet", the list is a polite live region | Medicines on chart › `every status has its pill...`, `no orders...` |
| 4 | Medicine select ("· high-alert" label), How often select, labels, hint text, no Clear before a check; the form is absent for a nurse and replaced by the lock line "Only doctors can order medicines." | Medicines on chart › `the form lists every medicine...`; Locks › `NURSE (orderLockReason ROLE)...`, IPD_READONLY and NOT_ADMITTED (no form; the lines "IPD is read-only. Medicines can be stopped but not added." and "This patient is not admitted any more." in `order-lock`, with `data-reason`) |
| 5 | BLOCKED: hard stop notice with the server text, no reason field, nothing added; NEEDS_REASON: warnings with severity words, reason field with placeholder and four reasons, button "Order with this reason"; Clear; changing the medicine or frequency drops the check | Check and order › BLOCKED (2), NEEDS_REASON (4) |
| 5 | The warnings changed while the reason was chosen: new text shown, reason cleared and focused, the old token is not sent again | Check and order › `the warnings changed while the reason was chosen...` |
| 6 | PLACED (201): body is key, medicine, frequency only; toast "<medicine> ordered. It goes to the pharmacist before the first dose."; new row with the Pharmacist check pill and its small line; form back to the first choices; a repeat (200, `replayed`) is one row and the same toast; the toast is announced | Check and order › `PLACED...`, `a repeat of the same order...` |
| 6 | Order with a reason sends `overrideReasonKey` and the `checkToken` the doctor was shown; the row shows its override line | Check and order › `NEEDS_REASON: pressing "Order with this reason"...` |
| 7 | Stop asks first (Stop / Keep, focus on Keep, Keep returns focus to Stop); confirm sends no body, toast "<medicine> stopped. Remaining doses are removed from the MAR.", the Stopped pill, focus on the heading; 409 reloads the true state; lost connection keeps it Active; no button when `canStop` is false | Stop (5 tests) |
| 13 | Role lines and locks as above (the exact lock lines for IPD read-only and not admitted are asserted); a 409 `not-admitted` or `ipd-readonly` on an order reads the record again and the form goes | Order errors › `a 500 ...409 not-admitted...`, `409 ipd-readonly...` |
| 14 | A full session logs no console error and nothing about the patient (name, allergy, medicine, warning) to the console; nothing in storage or the URL | Clinical data stays on the screen › 1 test |
| 15 | Order and Stop are off while a request runs (Check and order, Done, close, Stop, Escape), one request for a double click, a failed order is not shown as placed, a retry keeps the key; a new key after PLACED, after a changed medicine, frequency or chosen reason, and after 409 `client-key-reused` or a 400 on `clientKey` | Focus and keyboard › 7 tests (3 about "off while it runs"); Client key › 4 tests; Order errors › 6 tests |
| 12 | The 14 medicines, the rules and the catalogue | Not in the browser: backend ITs (the mock lists 4 medicines) |

### pharmacist.spec.ts (Safety tab, Pharmacist check panel, Verify and Reject sheets)

| AC | What is checked | Test (describe › title, abbreviated) |
| --- | --- | --- |
| 8 | OWNER: tab number = open escalations + orders waiting (read once by a counts-only call before the tab opens, the list is not read before the tab opens); tile "Pharmacist checks" with number (warn colour above 0) and "Orders waiting before the first dose"; panel title and subtitle; "No orders waiting."; no number when 0; the panel sits after the escalations | Safety tab of the OWNER › 5 tests |
| 8, 13 | DOCTOR and NURSE: the Safety tab has no pharmacist tile or panel, the number is the escalations only, no pharmacist call is made | Safety tab of the OWNER › `DOCTOR / NURSE: ...` |
| 8, 13 | PHARMACIST: the Safety tab is the only tab (selected, with the number waiting), tile and panel, no Admit button, no bed tiles; the admissions list, the Safety board, beds, layout and clinical events are **never** read (only `GET /ipd/pharmacist-checks` and `.../summary`); the tab number follows verify and reject; a pharmacist's own PIN verifies; counts poll every 60 s | PHARMACIST › 8 tests |
| 8 | IPD off (403 `module-off`) for a PHARMACIST and for an OWNER whose poll answers it: the lock notice "In-patient care (IPD) is not on for your clinic" (the plan link only for the owner) | PHARMACIST › `IPD off...`; Poll › `IPD switched off while the page is open...` |
| 9 | Row text "<drug> · <frequency>", "<name> · bed · allergy · ordered by <doctor> at <time>", high-alert pill, warnings with severity words, the doctor's reason, a hard stop with Verify off and "Hard stop: reject this order", the lock hints for the ordering doctor and IPD read-only (linked with `aria-describedby`), "Allergies not recorded", `canReject` false hides Reject, order as sent | Pharmacist rows › 6 tests |
| 9 | "Show more orders": 60 waiting = 50 rows + button, cursor read, 60 rows, button gone; a failed page keeps the 50 rows and can be retried; exactly 50 has no button; first read failing shows Try again; "Loading" until the queue is there | Show more orders › 3 tests; Panel load › 2 tests |
| 10 | Verify sheet: title, subtitle, identity bar, "No allergy, duplicate or interaction found.", warnings with the doctor's reason, "Verified by" read-only, PIN field masked / numeric / 4 / empty; digits only; right PIN: body is `{pin, checkToken}` only, sheet closes, toast "<medicine> verified by <name>. It is now on the MAR.", queue, tile and tab follow, focus on the panel heading | Verify sheet › first 5 tests |
| 10 | PIN states: "Enter the 4-digit PIN." (no request); wrong PIN "That PIN is not right. 2 tries left." under the field, field cleared and focused, sheet stays; counting down; locked (403 `pin-locked`, and locked when opened): notice with the time, "Set a new PIN", no PIN field, Verify off, the lock end re-reads the status; not set (never / reset by owner / switched off after too many tries) as opening state and as a 409 `pin-not-set`; "Set my PIN" opens the Set PIN sheet on top, Cancel returns, a saved PIN returns to Verify with the field and the new PIN works; 429 (text, Verify off for the wait, then back); 503 and `available` false; status unreadable with Try again; self-verification 403 (Verify stays off); lost connection | Verify sheet › 14 tests |
| 10 | `order-checks-changed`: text shown, panel read again behind the sheet, new warnings shown, Verify back, the next try sends the **new** token; `order-state` (someone else verified): order gone, Verify off, Cancel closes; 400 on `checkToken` reloads | Verify sheet › 3 tests |
| 10 | Off while it runs (Verify, Cancel, close; Escape; one request); Cancel / close / Escape send nothing and Escape returns focus to the Verify button; focus trap | Verify sheet › 3 tests |
| 10 (privacy) | No PIN in the DOM, a field, storage, console, a URL or a request other than the verify and set-PIN bodies, after a wrong PIN, a lock, a new PIN and a verify | Verify sheet › `wrong PIN`, `no PIN or code is in the page...` |
| 11 | Reject sheet: title, subtitle, four reasons with the first chosen, no PIN field; body `{reasonKey}` only; toast "Order rejected · <reason>."; a hard-stop order can be rejected; Cancel / Escape send nothing; 409 locks the button and reloads; 400 under the field; lost connection keeps the reason; off while it runs; focus trap | Reject sheet › 9 tests |
| 8 | 60-second counts poll: same count = no list read; changed count = list read and rows, tile and tab follow; same count with another token = list read; another tab = tab number only, list not read; hidden browser tab = no poll and one on return; failed summary changes nothing; failed reload keeps the rows with "Could not refresh. Showing data from 11:17 AM." | Poll › 7 tests |
| 15 | Layout 360 and 1280 px for OWNER and PHARMACIST: no sideways scroll, rows and both sheets on screen, every button and field 44 px (except the close X, below) | Layout › 4 tests |

### pin.spec.ts (staff PIN: Team, Set PIN sheet, account menu, Overview)

| Requirement (DEC-017, ADR-010 D7, D8, D12) | What is checked | Test (describe › title, abbreviated) |
| --- | --- | --- |
| D12 Team pills | OWNER: a pill on every row in words ("PIN set", "PIN locked", "No PIN yet"), colour class, icon; "Reset PIN" on set or locked rows only, named for the person; "Change my PIN" / "Set my PIN" on the own row only | Team pills › 4 tests |
| D12 own row only | DOCTOR, NURSE, BILLING, PHARMACIST with the Team page: one pill and "Change my PIN" on their own row; none on others; no Reset PIN; a colleague's lock is never in the page | Team pills › 5 tests |
| D8 Reset PIN | Confirm sheet "Reset PIN · <name>" with the text that the owner will not see or choose the PIN; Cancel, X and Escape send nothing; confirm sends no body, toast "PIN reset for <name>.", pill "No PIN yet", Reset button gone, focus on the pill; locked can be reset; 403 and lost connection keep the sheet with the text; off while it runs; focus trap and Escape returns focus | Reset PIN › 7 tests |
| D7 Set PIN | Title by state, masked mobile line, no fields before the code is sent, Save off; Send code without a body, "Code sent to ••••••7410.", code field focused, masked numeric PIN fields; countdown "Resend code in 0:30" and "Resend code" at 0; digits only; Save body = code + 2 PINs only; toasts "Your PIN is set." / "Your PIN is changed."; the Team pill and button follow | Set PIN sheet › about 8 tests |
| D7 errors | Empty fields and wrong lengths (messages in order, focus, no request); mismatch; 400 on newPin (weak), on code (wrong), on both, on no known field; both PINs cleared and the right field focused; 423, 429, 409, 503; 429 / 423 / 503 / lost connection on Send code and the countdown from the server's wait; `available` false; status unreadable with Try again | Set PIN sheet › 12 tests |
| D7 lost answer | After a lost answer the status is read again: new set time = success toast and closed sheet (no second save); unchanged = "We could not save your PIN. Send a new code and try again." with the PINs cleared; a 500 is read the same way; a status that cannot be read gives the same notice | Set PIN sheet › 4 tests |
| D7 behaviour | Off while it runs; Cancel sends nothing and returns focus; keyboard-only flow, focus trap, Escape; a full session leaves no PIN or code anywhere (DOM, fields, storage, console, URLs; bodies only of the one PUT) | Set PIN sheet › 3 tests |
| D12 account menu | Roles without the Team page (no `config` in the menu: DOCTOR, NURSE, FRONT_DESK, BILLING, PHARMACIST) have "My PIN" in the account menu, which opens the sheet; OWNER and a role with `config` do not; keyboard flow | My PIN menu item › 8 tests |
| D12 Overview | Checklist line "Own PIN for each staff member · optional" with its reason and an "Add" link to `/app/config/team#f-staff`, which shows the Staff panel; done = ticked, no link; a required line shows "Fix", counts as a thing left and puts "1 to do" on the Team tile | Overview › 3 tests |
| D10 change history | Plain labels for the five PIN actions and the five medicine actions, entity names "Staff PIN" / "Medicine order", an unknown code turned into a sentence, "Last change: PIN reset by the owner by <name> · <date>"; no PIN, medicine or code in the list | Overview › 2 tests |
| Layout | 360 and 1280 px: the Staff and Doctors panels and the PIN cells stay inside the screen, the Reset PIN and Set PIN sheets stay on screen, every PIN button and field is 44 px (except the close X, below) | Layout › 2 tests |

Counts of tests per group in the tables are as run (see the Playwright list); a few are approximate where a group was split across rows.

Cross-cutting: layout at 360 and 1280 px in all three specs; focus traps and focus return for the EMR, Verify, Reject, Reset PIN and Set PIN sheets; `role="alert"` on every error; field errors linked with `aria-describedby` and `aria-invalid`; the order list is a polite live region and the toasts are live regions; no console error in a full session of each area.

## Negative, role, boundary and isolation cases

- Role: EMR button by `canOpenEmr` (3 roles yes, 4 no); order form by role; Safety tab pharmacist content for OWNER only (DOCTOR, NURSE none); PHARMACIST sees the Safety tab only and makes no admissions call; Team PIN data for OWNER on every row, for others on their own row only; "My PIN" for roles without the Team page.
- Boundary: 50 and 60 waiting (paging), 4 / 3 / 5 / 6 digit PIN and code, the 4th and 5th wrong try texts, empty queue, tab count 0, no orders.
- Negative: 400 (every field of the order, verify, reject, set PIN, send code), 403 (`forbidden`, `self-verification`, `wrong-pin`, `pin-locked`), 404 and 409 (`order-state`, `order-checks-changed`, `client-key-reused`, `not-admitted`, `ipd-readonly`, `pin-not-set`, `concurrent-change`), 423, 429, 500, 503, lost connections, lost-after-stored answers, unreadable status.
- Tenant isolation is a server rule (RLS, 404): it is covered by the backend ITs. The browser has no way to ask for another clinic's data, and every tenant comes from the session.

## Results

Run after `npm --prefix frontend run lint` (all files pass), `npm --prefix frontend run test` (133 files, 1,553 tests passed) and `npm --prefix frontend run build` (exit 0), under the frontend lock, in `frontend/e2e`, `--workers=3`. 6 Nov 2026. `frontend/src` did not change while testing.

Tests per spec at the first run: medicine 61, pharmacist 76, pin 62 = 199 tests, run in two projects = 398 cases (now 227 tests, 454 cases: see the second fix pass below).

| Run | Command | Result |
| --- | --- | --- |
| medicine 1, 2, 3 | `npx playwright test medicine.spec.ts --workers=3` | 116 passed, 6 failed (122 cases) each time; the same 6 |
| pharmacist 1, 2, 3 | `npx playwright test pharmacist.spec.ts --workers=3` | 146 passed, 6 failed (152 cases) each time; the same 6 |
| pin 1, 2, 3 | `npx playwright test pin.spec.ts --workers=3` | 116 passed, 8 failed (124 cases) each time; the same 8 |
| safety (once) | `npx playwright test safety.spec.ts --workers=3` | 212 passed, 0 failed (106 tests x 2 projects) |
| nursing, emergency, discharge, bed-transfer (once) | `npx playwright test nursing.spec.ts emergency.spec.ts discharge.spec.ts bed-transfer.spec.ts --workers=3` | 412 passed, 0 failed |

The three SAH-31 specs together: 378 of 398 cases pass and 20 fail in every one of the three runs, always the same 20 (10 tests x 2 projects). No flake in the final nine runs. The 20 are not test mistakes: each is a finding about the app (next section). They are **not** weakened, skipped or deleted; they stay red until the angular-developer fixes the cause. Every other assertion in the three specs passes in both projects.

### Re-run after the angular-developer fix pass (6 Nov 2026, later) · 8 of the 10 red tests are now green

Same method: `npm --prefix frontend run build` (exit 0), then under the frontend lock, in `frontend/e2e`, `--workers=3`. The tests were not changed.

| Run | Command | Result |
| --- | --- | --- |
| medicine + pharmacist + pin, run 1 | `npx playwright test medicine.spec.ts pharmacist.spec.ts pin.spec.ts --workers=3` | 394 passed, 4 failed (398 cases), 10.1 min |
| same, run 2 | same | 394 passed, 4 failed, 8.9 min; the same 4 |
| same, run 3 | same | 394 passed, 4 failed, 9.0 min; the same 4 |
| every other spec, once | `npx playwright test app-shell home safety nursing emergency discharge bed-transfer --workers=3` | 732 cases: 724 passed, 8 skipped, 0 failed, 13.5 min |

- The 20 red cases (10 tests x 2 projects) are now **4 red cases (2 tests x 2 projects)**: 16 cases are green in all three runs. Green now: F1 (order row at 360 px), F2 (all six close-button tests: medicine, pharmacist, pin, each at 360 and 1280 px) and F4 (My PIN focus back on the account button). Still red: **F3** and **F5**, the same 4 cases in every run (no flake).
- Regression of the other specs: **none**. 732 cases: app-shell 42, home 66, safety 212, nursing 114, emergency 148, discharge 104, bed-transfer 46 = 732. The 8 skipped are the existing `test.skip(project !== desktop-1440)` blocks in `app-shell.spec.ts` (line 287) and `home.spec.ts` (lines 158 and 439), as before. The emergency flake seen once earlier did not come back. So the shared sheet-header and table CSS change broke nothing in the 7 older specs (0 cases regress; 0 of 732 changed from pass to fail).
- The whole browser suite after the fix pass: 1,130 cases run, 1,118 passed, 8 skipped (by design), 4 failed (F3, F5).
- `frontend/src` was not changed by me; one temporary probe spec was used for the two measurements below and deleted.

Measured causes of the two that are still red (for the angular-developer, exact):

- **F5 (Team page at 360 px scrolls sideways by 194 px; `scrollWidth` 554 over `clientWidth` 360).** `src/styles/_primitives.scss` line 197 is still `.tbl-wrap { overflow-x: auto; }` with no `position`; the computed style of `.tbl-wrap` on the page is `position: static`. The element whose right edge pushes the page is **not the table**: it is the eight `span.sr-only` elements (`position: absolute`, right edge 554 px) inside the locked "tick" cells of table `.pmx` (`span.tick.on.lock`). An absolutely positioned box is clipped only by an overflow container that is its containing block or a descendant of it; `.tbl-wrap` is static, so these boxes escape its clip and widen the document. `.tbl-wrap` itself is correct (left 27, right 333, width 306, `scrollWidth` 560). Measured fix: setting `position: relative` on `.tbl-wrap` in the browser takes the page overflow from 194 to **0** (removing the wrapper also gives 0; no element outside `.tbl-wrap` goes beyond the viewport). So the described change ("`.tbl-wrap` is now a containing block") is **not in the code that was built**: `_primitives.scss` has no `position: relative` on `.tbl-wrap` (checked with grep after the build). Expected: `.tbl-wrap { position: relative; overflow-x: auto; }`. The same wrapper is used by `compare.component.html`, `trust.component.html` and `revenue.page.html`.
- **F3 (Verify sheet: PIN field does not keep the focus when the status answers at once).** Measured with a focus log (page `focus()` calls with stack, `focusin` and `focusout`): at 1506 ms the sheet's `focusPinIfReady()` (called from `afterOpened()` > `afterNextRender`) focuses `input#sf-vpin`; **1 to 2 ms later** Material's own `FocusTrap.focusInitialElement > focusFirstTabbableElement` focuses `button[vf-x]` (the close X, the first tabbable in the sheet) and the PIN field loses it; the focus then stays on the X for the rest of the test (snapshots at +180, +500, +1100, +2300 ms). So `afterOpened` plus `afterNextRender` is still **earlier** than the dialog's initial-focus step. Expected fix: in `pharmacist-panel.component.ts` `verify()` add `autoFocus: false` to the `MatDialogConfig` (the sheet already focuses the field itself), or `autoFocus: '#sf-vpin'` (only works if the field exists at that moment, so `false` is safer), and keep the focus call for the later status changes. The same check may be needed for the Reject sheet and the Set PIN sheet (their tests pass today, so no action unless they change).

### Re-run after the second angular-developer fix pass (6 Nov 2026, evening) · all findings closed, 0 failures that repeat

Changes in the app that this run covers: `.tbl-wrap { position: relative; overflow-x: auto }` (F5); the Verify dialog opens with `autoFocus: false` and the sheet places the focus by state (F3); after a 409 `pin-not-set` the sheet also reads `GET /me/pin`; the EMR order lock lines (`data-testid="order-lock"`); Verify PIN `autocomplete="one-time-code"`; `.sheet-h > h2 { flex: 1 }`.
Method: `npm --prefix frontend run build` (exit 0, the built CSS has `position: relative` on `.tbl-wrap`), then under the frontend lock in `frontend/e2e`, `--workers=3`. No assertion was weakened, skipped or deleted; the specs only changed where the app's behaviour changed (below).

Tests per spec now: medicine 68, pharmacist 97, pin 62 = 227 tests = 454 cases (two projects).

| Run | Command | Result |
| --- | --- | --- |
| medicine + pharmacist + pin, run A | `npx playwright test medicine.spec.ts pharmacist.spec.ts pin.spec.ts --workers=3` | **454 passed, 0 failed** (10.1 min) |
| same, run B | same | 453 passed, **1 failed** (10.8 min): `medicine.spec.ts:797` › `while an order runs: ... Stop are off` on `phone-360` (see "The one failure" below) |
| same, run C | same | **454 passed, 0 failed** (10.6 min) |
| the three specs, an extra clean run | same | **454 passed, 0 failed** (10.1 min) |
| the whole folder `frontend/e2e`, once | `npx playwright test --workers=3` | 1,186 cases: **1,178 passed, 8 skipped (by design), 0 failed** (24.4 min). app-shell 42, home 66, safety 212, nursing 114, emergency 148, discharge 104, bed-transfer 46, medicine 136, pharmacist 194, pin 124 |

- The first "run A" I started was disturbed by me (a second Playwright process started while it ran cleared the `results` folder and 8 `phone-360` cases failed with `ENOENT ... traces`). It does not count; the extra clean run above replaces it. (Same cause as the one noted in "Earlier runs".)
- F3 and F5 are **green in all four clean runs, both projects**: `pin.spec.ts` › `at 360 px wide: the Team page does not scroll sideways...` and `pharmacist.spec.ts` › `the PIN field has the focus when the sheet is ready (the status answers at once...)`. F1, F2 and F4 stay green. Every earlier regression is still green: the 7 older specs (732 cases) pass in the whole-folder run, 0 changed from pass to fail.
- **The one failure (run B only):** `medicine.spec.ts:797` on `phone-360`. The test opens an order with `orderDelay: 900` and asserts, in this order, that Check and order, the X and Done are disabled, then that Stop is disabled. The first four assertions passed; the fifth saw Stop `enabled` for the whole 7 s poll, which means the mocked answer (900 ms after the click) had already arrived before the assertion ran, so the sheet was back to idle. That is a timing race of the test under load (first test of the run, 3 workers, other jobs on the machine), not a bug in the app: it never failed in the other runs, nor in 72 repeats (`--repeat-each 12`, tests `while an order runs`, `while Stop runs`, `double click`; 72 of 72 passed). The test and the 900 ms were left as they are; if it returns, raise `orderDelay` in that one test (a longer mocked delay does not change what is asserted). I report it as a flake, not as fixed.
- `frontend/src` was not changed by me. Lint and unit tests were not part of this brief (the angular-developer ran them).

#### What changed in the specs (behaviour changed in the app)

| Spec | Change |
| --- | --- |
| `pharmacist.spec.ts` | `autocomplete` of the PIN field: `off` became `one-time-code` (the app changed on purpose). |
| `pharmacist.spec.ts` | The 409 `pin-not-set` test is rewritten: the mock now answers `GET /me/pin` with the true state (`NOT_SET` and `notSetReason`) when the verify call is refused; the notice must come from the **status** (the answer's own words are generic on purpose), the status is read exactly twice (open, then after the 409), the focus is on "Set my PIN", the PIN is nowhere. Three reasons x two projects. |
| `pharmacist.spec.ts` | New: 409 and the status cannot be read (generic words "You have not set your PIN yet. Set it to verify orders."); 409 and the status says the PIN is set (field back, empty, focused, the second try verifies); 409 and the status says locked or not available (matching notice, action focused). |
| `pharmacist.spec.ts` | `Verify sheet first focus`: the F3 test stays; new, for every state (ready, not set, not set after owner reset, locked, status cannot be read, not available) x status answers at once / after 400 ms: the focus is on the state's target (`#sf-vpin`, `vf-setpin`, `vf-newpin`, `vf-retry`, the notice), is never on the close button, and is still there 500 ms later; while the status is read (1.2 s) the focus is not on the close button and moves to the field; Try again moves the focus to the field; the focus trap is still on (Tab from the last control reaches the close button, Shift+Tab goes back); closing returns the focus to the row's Verify button. |
| `pharmacist.spec.ts` | New `Verify sheet and the browser password manager`: the PIN field is `type=password` with `autocomplete="one-time-code"` (also after Try again and after a PIN was set from the notice); no input of the sheet has `current-password`, `new-password`, `username`, `email` or `on`; the only password input is the PIN; the form has no `autocomplete` that turns saving on. Attribute check only, as agreed (a browser's own save prompt is not testable in Playwright). |
| `medicine.spec.ts` | The two lock tests now assert the exact lines: `IPD is read-only. Medicines can be stopped but not added.` and `This patient is not admitted any more.` (`data-testid="order-lock"`, `data-reason` set, inside the sheet, no sideways scroll); the same lines appear after a 409 `ipd-readonly` / `not-admitted` re-reads the record; IPD read-only keeps Stop where the server says `canStop`; not admitted with `canStop` false has no Stop; the ROLE line (`order-role-lock`) never shows with the other test id. |
| `medicine.spec.ts` | New `Sheet header placement`: the admission sheet while loading and after its load fails, and the EMR sheet after a failed read (the variants whose title is a bare `<h2>`), at 360 and 1280 px: the 44 px close button sits at the right edge of the header (within 24 px), the title takes the free width (only the 12 px gap is left before the button), no overlap, no sideways scroll. The nursing and discharge sheets use the same bare `<h2>` header but are not part of SAH-31; their specs were not changed. |

#### Notes for the owners (not failures)

- The Set PIN sheet still uses `autocomplete="new-password"` on its two PIN fields (and `one-time-code` on the code). That is as before and its test still asserts it; if a browser's "save password" prompt after a PIN change is a problem, the angular-developer can use `one-time-code` there as well. Only the Verify field was in this brief.
- The "Verified by" field is a read-only text input right before the password-type PIN input. Browsers sometimes treat a text field before a password field as a user name; with `one-time-code` on the PIN field this does not trigger saving in the browsers' documented behaviour, but a real browser was not used to prove it.

### Findings (app) · for the angular-developer · original list (first run). Status now: **F1 to F5 all fixed and green** (F1, F2, F4 after the first fix pass; F3 and F5 after the second)

| # | Severity | Finding | Failing test(s) (both projects) |
| --- | --- | --- | --- |
| F1 | Low | **EMR sheet, 360 px:** the order list is wider than its box by 4 px (`scrollWidth` 304 over `clientWidth` 300). `.ipbl.sfol` in `emr-sheet.component.scss` is `grid-template-columns: 1fr auto minmax(96px, auto)`; with the pill "Pharmacist check" and a Stop button (and with the "Stop this medicine?" question) the three columns need more than the row has, so the Stop column sticks out. No page-level scroll (the page test passes). 1280 px is fine. | `medicine.spec.ts` › Layout findings › `at 360 px wide: an order row fits inside the list...` |
| F2 | Low | **The close buttons of all five new sheets are 40 x 40 px** (`mat-icon-button`: EMR `emr-x`, Verify `vf-x`, Reject `rj-x`, Reset PIN `rp-x`, Set PIN `sp-x`); the project rule (`.claude/rules/angular.md`) is 44 px. The footer buttons, row buttons, selects and inputs are all 44 px or more (asserted). The older sheets use the same 40 px X, so one global rule in `_console.scss` for `.sheet-h` icon buttons is the cheapest fix. | `medicine.spec.ts` › `the close button of the sheet is a 44 px touch target` (360, 1280); `pharmacist.spec.ts` › `the close buttons of the Verify and Reject sheets...` (360, 1280); `pin.spec.ts` › `the close buttons of the Reset PIN and Set PIN sheets...` (360, 1280) |
| F3 | Low | **Verify sheet first focus:** the code puts the focus in the PIN field (`effect` and `focusLater('sf-vpin')`) when the PIN status arrives, but when the status answers before the dialog has finished opening, the dialog's own first-focus takes it away (the field gets `ng-touched`, focus ends elsewhere). When the status answers 400 ms later the focus is in the field (that test passes). On a fast network the pharmacist has to tap the field first. Suggest `autoFocus: false` on the dialog, or focusing after the open animation. | `pharmacist.spec.ts` › Verify sheet first focus › `the PIN field has the focus when the sheet is ready (the status answers at once...)` |
| F4 | Low (a11y) | **"My PIN" in the account menu:** after the Set PIN sheet is closed with Escape, focus is not back on the account button (it is lost to the page), because the menu item that opened the sheet is gone when the dialog tries to give focus back. The Team row and the Verify sheet return focus correctly (tested). Suggest `openMyPin()` focuses the `.user-menu` button after the dialog closes. | `pin.spec.ts` › My PIN menu item › `keyboard: the account menu opens with Enter...focus returns to the account button` |
| F5 | Medium, **existing (SAH-6), not SAH-31** | **Team page, 360 px:** the page scrolls sideways by 194 px because the table "Who can do what" (`table.pmx`, 560 px) is not scrolled inside its own wrapper (`.tbl-wrap`). The PIN cells, the Doctors and Staff panels and both new sheets fit (asserted); only the permission table is wider than the screen. Found because the new layout test measured the whole page. | `pin.spec.ts` › Layout findings › `at 360 px wide: the Team page does not scroll sideways...` |

Not findings, but worth a look by the owners:
- After "Set a new PIN" on a locked PIN the toast says "Your PIN is changed." (a first PIN says "Your PIN is set."). That follows the sheet's rule (`before.state === 'NOT_SET'` means "set"), and the tests assert it.
- After a 400 on the new PIN the code stays in its field (only the two PINs are cleared), by design: a 400 on the PIN does not use up the code. A PIN is never left in a field after an answer (asserted).

### Changes to existing specs (the Safety tab changed)

`safety.spec.ts` (SAH-33) failed in the first regression run (20 of 214 cases) for two reasons that belong to the SAH-31 change, not to a bug:
1. The OWNER's Safety tab now also reads `GET /ipd/pharmacist-checks/summary` (when the page opens and every 60 seconds) and `GET /ipd/pharmacist-checks` (when the tab opens). The mock of that spec answered 404, so its `unmocked` check, its console check and a "Try again" check failed. The mock now answers both with an empty queue (`waiting: 0`), so every number, text and count of that spec is unchanged.
2. The test `PHARMACIST: no Safety tab...` is not true any more: by ADR-009 D11 a PHARMACIST has the Safety tab only. That role was taken out of that list (the spec now has 106 tests, not 107); it is covered in `pharmacist.spec.ts` › PHARMACIST.

After the change: safety 212 passed, 0 failed. One failure in `emergency.spec.ts` (`setup: every control is disabled while a save is pending`, `phone-360`: 2 saves instead of 1) happened once in the loaded first regression run (5 specs together, 13 minutes) and did not come back: the later run of nursing, emergency, discharge and bed-transfer passed 412 of 412. It is not about SAH-31 (it is the Emergency setup page); if it returns, treat it as a flake of that spec under load.

### Earlier runs on the way (kept for the record, not hidden)
- First medicine run: 88 passed, 26 failed. Test mistakes: the mock did not answer `GET /ipd/admission-options` (the admission sheet reads it), `getByLabel('Medicine')` also matched the list "Medicines on chart", an `.ipbl` count included the bill rows of the admission sheet, and a PHARMACIST cannot open an admission (no Admitted tab). Fixed in the tests. The real findings F1 and F2 first showed here.
- First pharmacist run: 90 passed, 54 failed. The helper asserted that the PIN field has focus as soon as the sheet opens (F3), so every Verify test failed on it; that is now its own finding test and the helper only waits for the field to be visible. The 5th-wrong-PIN tests expected "Your PIN is set." where the app says "Your PIN is changed." for a locked PIN (test mistake, fixed).
- First pin run: 97 passed, 25 failed. About half were my own doing (a second Playwright process started for a probe cleared the `results` folder while a run was going), the rest were a helper that waited for two rows at once, a missing mock (`GET /platform/clinic/profile`) and a text match that did not normalise white space.
- Three failures came and went on `desktop-1440` in the runs with the first helper (`reject sf-rj 35px`, `reset rp-cancel 44px`): a button measured while the sheet was still scaling in. `dialogReady()` now waits for the open state and for the running CSS animations to end, and the layout tests wait for the first sheet to be gone before the next opens. No flake in the nine final runs.

`node scripts/validate.mjs` was **not** run (the brief asked for lint, unit tests, build and Playwright only, and no backend). Lint, unit tests and build were run as separate commands (results above).

## Files

- `frontend/e2e/medicine.spec.ts` (new; ignored by git, needs `git add -f`)
- `frontend/e2e/pharmacist.spec.ts` (new; same)
- `frontend/e2e/pin.spec.ts` (new; same)
- `frontend/e2e/sah31-world.ts` (new, the shared mock; same)
- `frontend/e2e/safety.spec.ts` (changed: two mock answers, one role moved out of a list)
- `docs/test-plans/SAH-31-e2e.md` (this file)
