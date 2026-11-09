# Test plan SAH-40 · A refused refresh clears clinical data on the Emergency queue and the Pharmacist check (+ Safety board extra)

Story: `docs/stories/SAH-40.md` (25 acceptance criteria, 2 Must stories). Design: `docs/architecture/SAH-40-refused-refresh.md`. Handoffs: `docs/handoffs/SAH-40.md`.
Mockup screens: IPD → Emergency (`ipEr`); IPD → Safety, panel "Pharmacist check" and tile "Pharmacist checks" (`ipSafety`). The mockup has no refused state (OQ-40-1); the built lock page and the first-load notices are reused.
Module: ipd. **Frontend only. No backend, API, contract or database change in this story, so there are no new backend integration tests (`*IT.java`).** Who gets a 403 and why is already covered by the existing ITs of SAH-28, SAH-31 and SAH-33 (not changed). The browser tests prove only what the screens do with a 403.

## How it is tested

- **Unit (Vitest), by the angular-developer:** `emergency.component.spec.ts` ("a refused reload (SAH-40)"), `pharmacist.store.spec.ts` ("a refused read (SAH-40)"), `pharmacist-panel.component.spec.ts`, `ipd.page.spec.ts` ("refused reads (SAH-40)"), `safety.component.spec.ts` (reads nothing after a 403 until Try again), `ipd-refusal.spec.ts`. Reported by the developer and re-run by QA: 154 files, 2,166 of 2,166 pass.
- **Browser (Playwright), by QA:** `frontend/e2e/refused-refresh.spec.ts`, 63 tests (38 first; the D-1 probe became 7 real tests, then 19 more for the review fix pass, all on 9 Nov 2026), run at `phone-360` and `desktop-1440` (126 test runs per repeat). Every API call is MOCKED in the browser with `page.route()`: the shared `sah31-world.ts` (list, Safety board, pharmacist) plus a small Emergency mock at the top of the spec. The 60-second reloads run on the page's fake clock (`page.clock.runFor(61_000)`); a late answer is made with a real-time hold in the mock. One additive change to `sah31-world.ts`: an optional `emergencyCounts` field, so the Admitted list carries Emergency counts (the tile and the tab) that differ from the queue's own.
- Browser build: `npm --prefix frontend run build` (rebuilt on 8 Nov 2026 before the runs).
- The layout projects (`layout-*`, `zoom125-*`) only run `layout-matrix.spec.ts`, so the new spec does not run there. SAH-40 changes no layout.

## Acceptance criterion to test to result

Result: browser = 3 of 3 repeats green at both sizes (final spec); unit = developer run. "Probe" = the test records what happens in annotations and does not fail on it.

### SAH-40-1 · Emergency queue

| AC | Point | Browser test (`refused-refresh.spec.ts`, group "SAH-40-1") | Unit test | Result |
| --- | --- | --- | --- | --- |
| 1 | 60 s reload 403 `forbidden`: cards, form, note and counts leave; server message with **Try again**, `role="alert"` | "AC1, AC4, AC7, AC9, AC11: 403 forbidden clears the queue, form, note and counts ..." | emergency.component "403 forbidden clears the queue, the form and the note ..." | pass |
| 1 | Same for FRONT_DESK, NURSE, DOCTOR | "AC1: FRONT_DESK / NURSE / DOCTOR gets the same refusal" (3) | n/a | pass |
| 1 | No server message: "We could not load the emergency queue. Try again." | "AC1: the notice falls back to ..." | "403 without a message shows the first-load wording" | pass |
| 2 | 403 `module-off`: lock page for the whole page, plan link for OWNER only, nothing of the page behind it, no more reads | "AC2, AC4: 403 module-off shows the IPD lock page ... OWNER gets the plan link, NURSE does not" | "403 module-off clears the queue ... tells the page to lock"; ipd.page "Emergency 403 module-off ..." | pass |
| 3 | Network error, 503, 500: queue stays with "Could not refresh the queue. Last updated ... IST"; next good reload clears it; the timer keeps reading | "AC3: 503 / 500 / a lost connection keeps the queue ..." (3) | "a network error and a 503 keep the queue ..." | pass |
| 3 | Browser offline | covered by "a lost connection" (aborted request, same code path as status 0) | same | pass (by abort, not by `setOffline`) |
| 4 | No name, complaint, age or level left in the screen, html, storage, URL; tile and tab fall back to the list's own answer (5, not 2) | first test above (tile "5", tab "5", `expectNowhere`) | ipd.page "Emergency 403 forbidden: the queue goes, the tile and the tab follow the Admitted list answer" | pass |
| 4 | Tile "—" when the page has no list answer | not reachable in the browser: the Emergency tab only exists when the list carries `emergency.writable` | ipd.page unit test | n/a in browser (see Notes) |
| 5 | Empty queue then 403: the refusal, never "No one in emergency right now." | "AC5: an empty queue and then a 403 shows the refusal ..." | "an empty queue then a 403 shows the refusal ..." | pass |
| 6 | Action 403: toast, then the queue is read again; narrower role keeps the queue | "AC6: an action that is refused (403) shows its toast and reads the queue again ..." | "an action answered 403 shows its toast and reads the queue again" | pass |
| 6 | Action 403 and the follow-up read is 403: queue cleared | "AC6: ... whose follow-up read is refused too clears the queue" | same group | pass |
| 6 | Action 409 `case-closed`, follow-up read 403 `forbidden` or `module-off`: queue cleared, or lock page | "AC6: an action answered 409 case-closed reads the queue again ..." | "an action answered 409 case-closed whose reload is 403 clears the queue" | pass |
| 7 | **Try again** loads as the first load ("Loading the emergency queue"); access back: queue and buttons from the server flags; still refused: refusal again | first test above (Try again twice) | "Try again loads like the first load ...", "... shows the refusal again ..." | pass |
| 8 | An older good answer (held action answer) landing after the refusal does not bring the queue back; no toast either | "AC8: an older answer to an action that lands after the refusal does not bring the queue back" | "an older answer that arrives after the refusal does not bring the queue back", "a registration answer arriving while refused is ignored" | pass |
| 9 | Notice announced (`role="alert"`); focus moves to the notice, not the document top | first test above (`toBeFocused()` on the notice) | not in the Emergency unit list | pass |
| 10 | Hidden tab / leaving the page: timer stops with the component, no new behaviour | not tested (no change); the module-off test checks no read follows once the page is locked | n/a | not re-tested (unchanged) |
| 11 | Nothing about the refusal in console, URL, storage; the clinic is never sent | "AC11: nothing about a refusal reaches the console, the URL or the storage" | n/a | pass |
| OQ-40-3 | After a refusal the timer sends nothing until Try again | first test above (3 minutes, no `/emergency` read) | "the timer asks for nothing while refused, until Try again" | pass |
| First load | 403 `forbidden`: same notice with Try again (unchanged) | "first load: 403 forbidden is the same notice with Try again (unchanged) ..." | existing | pass |
| First load | 403 `module-off`: **now locks the whole page** (the old notice text with Try again is gone) | "first load: 403 module-off now locks the whole page ..." | "a first-load 403 module-off also tells the page to lock" | pass, **deviation from the story table, see Defects D-2** |
| Route | Same refusal when the page is opened from the menu item `/app/emergency` | "route /app/emergency (menu item): the same refusal ..." | n/a | pass (menu badge recorded, see Notes) |

### SAH-40-2 · Pharmacist check

| AC | Point | Browser test (group "SAH-40-2") | Unit test | Result |
| --- | --- | --- | --- | --- |
| 12 | List reload 403 `forbidden`: rows, as-of, count leave; server message with **Try again** (`role="alert"`) | "AC12, AC17, AC18, AC21, AC23: list reload 403 forbidden clears rows ..." | pharmacist.store "a list reload 403 forbidden clears everything ..."; panel "removes the rows and shows the notice ..." | pass |
| 13 | List reload 403 `module-off`: lock page for the whole page | "AC13: list reload 403 module-off clears everything and the IPD lock page replaces the whole page" | store "... module-off clears everything and sets moduleOff" | pass |
| 14 | Network error or 5xx: rows stay with the stale notice; next good reload clears it | "AC14: list reload 503 or a lost connection keeps the rows ..." | store "a list reload that fails on the network or with 503 keeps the rows" | pass |
| 15 | Counts poll 403 `forbidden`: as AC 12; `module-off`: as AC 13 | "AC15: the counts poll answering 403 forbidden is a refusal ...; 403 module-off locks the page" | store "a counts poll 403 forbidden / module-off ..." | pass |
| 15 | Counts poll network error or 5xx: ignored, no notice, no clearing | "AC15: the counts poll failing with a network error or a 5xx is ignored ..." | store "a counts poll that fails on the network or with 503 clears nothing ..." | pass |
| 16 | "Show more orders" 403 (`forbidden`, `module-off`): AC 12 / 13 | "AC16: Show more orders answering 403 forbidden is a refusal; 403 module-off locks the page" | store "Show more orders 403 forbidden or module-off is a refusal" | pass |
| 16 | "Show more orders" network error: rows stay, "We could not load more orders. Try again." | "AC16: Show more orders failing with a network error keeps the rows ..." | store "Show more orders that fails on the network keeps the rows ..." | pass |
| 17 | Never "No orders waiting." after a refusal | asserted in the AC12, AC15, AC16, AC22 tests (`pharmacist-empty` count 0, text count 0) | store "never has a meta ..."; panel (AC 12, 15, 17) | pass |
| 18 | Safety tab number hidden, tile "—", never 0 | AC12, AC15, AC16, AC22 tests; "AC18 before the first read: ... the tile shows '—' and not '0'" | page "owner: a counts-poll 403 hides the Safety tab number ..."; panel "shows a dash, never 0 ..." | pass |
| 19 | Reject sheet open, its 409 makes the list reload, reload 403: list behind it cleared, sheet keeps its own message | "AC19: a verify or reject sheet is open and its conflict makes the list reload, which is refused ..." | store "ignores a verify or reject answer while refused" | pass |
| 19 | A verify or reject answer that lands after a refusal does not bring the rows back | "AC19: a verify or reject answer that lands after a refusal does not bring the rows back" | same | pass |
| 20 | Admitted-list refusal drops the pharmacist rows from memory (observed: tab number loses the pharmacist part until the panel reads again) | "AC20: an Admitted-list refusal (G-2) drops the pharmacist rows from memory too ..." | page "an Admitted-list refusal drops the pharmacist rows, not only hides them" | pass |
| 21 | **Try again** loads as a first load; access back: list shows; still refused: refusal again | AC12 test (Try again twice) | store "load() after a refusal is a first load ..." and "... shows the refusal again ..." | pass |
| 22 | PHARMACIST sees the refusal (or lock page), not an empty tab | "AC22: a PHARMACIST whose counts poll is refused sees the refusal in the panel ..."; "AC22, AC13: ... module-off sees the lock page, without the plan link" | page "pharmacist: a counts-poll 403 shows the refusal, not an empty tab"; "... module-off shows the lock page" | pass |
| 23 | Notice announced; focus moves to the notice | AC12 test (focus on a Reject button, then `toBeFocused()` on the notice) | panel "moves focus to the notice when the focused row button is gone" | pass |
| 24 | No patient data in console, URL, storage; the clinic never sent | "AC24: nothing about a refusal reaches the console, the URL or the storage" | n/a | pass |
| 25 | Roles that never had the panel: first load refuses as before | "AC25 and first load: a first load answered 403 forbidden or module-off is the notice or the lock page, as before" (PHARMACIST first load; the other roles have no panel to open) | n/a | pass (see Notes) |
| OQ-40-3 | After a refusal the list timer and the counts poll send nothing until Try again | AC12 test (3 minutes, no list and no summary read); AC22 test (summary read count stays 1) | store "reads nothing while refused" | pass |

### Safety board extra (architect observation, tech lead decision: added to SAH-40)

| Point | Browser test (group "Safety board: reads stop after a refusal") | Unit test | Result |
| --- | --- | --- | --- |
| After a 403 on the 60 s reload the board and the audit trail are not read again by the timer; a later 200 does not bring the board back; Try again does, and the timer then runs again | "403 forbidden on the 60 s reload: the board leaves ...; the timer then reads neither the board nor the audit trail; Try again brings the board back" | safety.component "reads nothing on later ticks after a 403 until Try again" | pass |
| Same for 403 `module-off` (observed: the notice, not the lock page, as the G-2 audit test already records) | "403 module-off on the board reload: the board leaves and no board read follows until Try again" | same | pass |
| A first load answered 403 does not read again by itself | "a first load answered 403 does not read again by itself either; Try again does" | same | pass |
| 503 or a lost connection is not a refusal: board stays with "Could not refresh", the timer keeps reading | "a 503 or a lost connection is NOT a refusal ..." | existing | pass |
| D-1 (fixed): an open Response sheet when the board is refused (403 `forbidden` and `module-off`) | "D-1, forbidden / module-off: an open Response sheet closes with the board (patient name gone); no board read until Try again; Try again restores the board and the 60 s timer reads again" (2) | safety.component (angular-developer, D-1 fix) | pass |
| D-1 (fixed): a Response save already on its way when the refusal lands | "D-1: a Response save that is on its way when the board is refused does not bring the board back, and no board read follows its answer" | same | pass |
| D-1 (fixed): an open Give sheet of the Medicine round (`forbidden` and `module-off`): closes, no dose is recorded, no board read until Try again, the timer then reads again | "D-1, forbidden / module-off: an open Give sheet (Medicine round) closes with the board ..." (2) | panel destroy closes the sheet | pass |
| D-1 (fixed): an open pharmacist Verify or Reject sheet; the pharmacist rows are cleared with the board (screen and memory); no pharmacist or board read until Try again; after Try again the panel reads the list again | "D-1: an open pharmacist Reject / Verify sheet closes when the Safety board is refused; the pharmacist rows leave memory and the screen; Try again reads them again" (2) | `refuse()` clears the pharmacist store | pass |

### Review fix pass (9 Nov 2026): findings of the code and security reviews

Tech lead decision FIX for all rows. Browser group: "SAH-40 review fixes: sheets, toasts, tab number, focus" in `refused-refresh.spec.ts` (19 tests), plus two older tests whose expectation changed by design (below). Unit tests are the angular-developer's (2,221 pass, 35 new).

| Finding | Point | Browser test | Result |
| --- | --- | --- | --- |
| security 1, code 1 (Medium) | An open Emergency Admit sheet (casualty name, bed) closes on a 403 `forbidden` and on `module-off`; no admission is made; no name left on screen | "security 1, forbidden / module-off: an open Emergency Admit sheet ... closes when the 60 s reload is refused" (2) | pass. Focus after the sheet closes: now a real assertion (`toBeFocused()` on the notice), F-1 fixed |
| security 2, code 2 (Medium) | An open pharmacist Reject / Verify sheet closes after a counts-poll 403; the pharmacist panel stays with the notice; focus goes to the notice; no rows, no list read | "security 2: an open pharmacist Reject / Verify sheet closes when the counts poll is refused" (2) | pass |
| security 2, code 2 (Medium) | A conflict (409) inside the sheet does not read the list while refused | "security 2 (conflict): a Reject answered 409 after a refusal closed its sheet does not read the list again; the rows stay away until Try again" (the 409 is held back in the mock and lands after the sheet is closed; the list read count does not move) | pass |
| security 2 (AC 19) | Sheet open, its own 409 reads the list, the read is 403 | "AC19: ... the list behind it is cleared and the sheet closes with it" | pass after a **changed expectation** (C-1) |
| security 3 (Medium) | Page sheets close when the page locks on `module-off`: Admission, Nursing, EMR | "security 3, module-off: an open Admission / Nursing / EMR sheet closes when the Admitted list reload says module-off ..." (3) | pass |
| security 3 | Admission sheet closes when the page refuses (`forbidden`) | "security 3, forbidden: an open Admission sheet closes ..." | pass |
| security 3, 4 | The page's own "Admit patient" sheet closes on the lock; no Admitted-list read follows while locked (a 200 would otherwise refill the list behind the lock page) | "security 3 / 4, module-off: the page \"Admit patient\" sheet closes when the page locks; nothing is read for the list while locked" | pass |
| security 3 | The Safety Response sheet closes when the page locks (Safety is destroyed) | "security 3, module-off: an open Safety Response sheet closes when the page locks ..." | pass |
| security 4 (Low) | `refreshList()` guarded when locked | the "no Admitted-list read while the page is locked" check above. The exact case (a late Admit save answer) is not reachable in the browser: the lock closes the sheet, which drops its answer | pass (unit: the angular-developer) |
| security 5, code 7 (Low) | A success toast that names a patient is dismissed on a refusal: Safety (Response save, then the board read is refused) | "security 5 (Safety): a success toast ... is dismissed when the board read that follows the save is refused" (the board read is held until the toast is seen) | pass |
| security 5 | Same, pharmacist (Reject succeeds, then "Show more orders" is refused) | "security 5 (pharmacist): a success toast of a Reject is dismissed when \"Show more orders\" is refused" | pass |
| security 5 | Same, Emergency ("seen" succeeds while the 60 s reload's refusal is on its way) | "security 5 (Emergency): a success toast present when the reload is refused is dismissed" | pass |
| security 5 | The Emergency action's own 403 message stays when the follow-up read clears the queue | "security 5 (Emergency): the 403 message of the action that was refused stays on screen ..." | pass |
| security 6, code 5 (Info) | The Safety tab number is hidden after a Safety board refusal, and back (same value) after Try again | "security 6: the Safety tab number is hidden after a Safety board refusal and comes back after Try again" | pass |
| code 3 | Focus on the notice after a repeated refusal following Try again (keyboard Enter on the button): Emergency | "code 3 (Emergency): Try again answered 403 again puts the focus on the notice ..." | pass |
| code 3 | Same, pharmacist panel | "code 3 (pharmacist): Try again answered 403 again puts the focus on the notice" | pass |
| code 8 | After an Admitted-list refusal and Try again the pharmacist counts are read at once; the tab number is complete again | "code 8: after an Admitted-list refusal and Try again the pharmacist part of the Safety tab number is read again at once ..." and AC20 (changed, C-2) | pass |
| security 7 (Info), code 4 | No change / commit split (process) | n/a | n/a |

#### Expectations changed by design in older tests (nothing weakened)

- **C-1, AC 19 test** ("a verify or reject sheet is open and its conflict makes the list reload, which is refused"). Before: the sheet stayed open and showed its own message (`reject-failure`) over the cleared list. Now: the refusal closes the sheet at once (security 2); the test asserts the sheet, its buttons and its message are gone, the notice shows, no row, no name on screen, and the reject was sent once. The patient data check is stronger, not weaker. The story AC 19 text ("the sheet keeps its own message") is superseded by the review decision; the BA should align the story.
- **C-2, AC 20 test** (Admitted-list refusal drops the pharmacist rows from memory). Before: after Try again the Safety tab number was 1 (escalations only) until the panel was opened. Now: 2 at once, because the page reads the pharmacist counts again after Try again (code 8). The "rows dropped from memory" check stays: the panel still reads the list again when it is opened (read count +1).

### Security re-check (9 Nov 2026): R-1 and R-2 (Low, both fixed by the angular-developer)

Browser group: "SAH-40 re-check R-1 and R-2" in `refused-refresh.spec.ts` (5 tests per size; the spec now has 68 per size, 136 runs per repeat). Unit tests are the angular-developer's (2,228 pass, 6 new).

| Finding | Point | Browser test | Result |
| --- | --- | --- | --- |
| R-1 (Low) | OWNER, on Bed board: a counts poll is on its way (held by a gate) and will answer with different counts; the Admitted-list reload (made by leaving the Safety tab) answers 403 `forbidden`. The notice shows with Try again, no tab, no name. The late poll answer makes **no** list read. Over 3 x 61 s of fake clock: no `GET ipd/admissions`, no `GET ipd/safety/summary`, notice stays, no names. Try again reads the list once and the page returns. The next 60 s tick then reads the Safety counts again (polling resumes) | "R-1, OWNER: a refused Admitted-list reload on another tab stops the list and the Safety-counts reads (also one already on its way) until Try again; Try again reads and the polling resumes" | pass |
| R-1 (Low) | Same for DOCTOR | "R-1, DOCTOR: ..." | pass |
| R-1 (Low) | A first load answered 403 `forbidden` (the `load()` error path sets the flag too): 3 minutes, no list read, no counts read; Try again reads once | "R-1 (first load): an Admitted list refused on the first load (403 forbidden) is not read again by the timer; Try again reads it" | pass |
| R-2 (Low, a11y) | Emergency tab with the Admit sheet open (casualty name shown); the queue reload answers 403 `module-off`: the sheet and every dialog close, the lock page shows, `#ipd-lock-h` ("In-patient care (IPD) is not on for your clinic") has the focus (`toBeFocused()`), no casualty name left, no admission call | "R-2: Emergency with the Admit sheet open, the queue reload says module-off: the sheet closes, the lock page shows and the focus is on the lock heading" | pass |
| R-2 (Low, a11y) | Same with the focus on a card button (no sheet): the focus is on the lock heading, not on the body | "R-2: Emergency with the keyboard focus on a card button, ..." | pass |

Method note for R-1: the held poll uses a gate (a promise released by the test), not a timer, so the order "poll in flight, then the list refusal, then the poll answer" is exact. The mock for the held poll counts its read in `w.calls` like the shared mock. These tests would have failed before the fix (the late answer with different counts called `refreshList()` and the 60 s tick called `pollSafety()` again); this was not run against the old code, because the shared tree has no old build.

## Negative, role, tenant and boundary points

- **Negative:** network error, 500, 503, aborted request, empty queue, 403 without a message, a late answer after a refusal, Try again while still refused (all above).
- **Roles:** Emergency FRONT_DESK, NURSE, DOCTOR, OWNER; pharmacist OWNER and PHARMACIST; plan link for OWNER only on the lock page.
- **Tenant isolation:** the clinic is never sent by the browser (no `tenant` text in any URL or body, AC 11 and AC 24 tests). Another clinic's rows cannot appear: RLS on the server, covered by the existing backend ITs (no backend change here).
- **Boundary:** 0 cases and 0 orders waiting; 60 orders (second page) for "Show more orders".

## Run counts (8 Nov 2026, 4 workers, build of 8 Nov 17:44 UTC)

| Run | Result |
| --- | --- |
| New spec, first complete run (desktop-1440 only, while fixing tests) | 38 of 38 after three test fixes (all test-side: an assertion on the failed-reload rows, the menu badge, the route order of a mock) |
| New spec, 3 repeats before `test.slow()` on the 8 two-scenario tests | run 1: 74 of 76, two `page.goto` timeouts at 30 s (cold start, two scenarios in one test); runs 2 and 3: 76 of 76 |
| New spec, 3 repeats, final spec, phone-360 and desktop-1440 | 76 of 76, 76 of 76, 76 of 76 |
| Related specs, once (emergency, pharmacist, safety, mar, medicine-round, admitted-cards, app-shell, audit-sah35-36-38), both sizes | 1,111 passed, 6 skipped (the skips are in the older specs, unchanged), 3 failed: `mar.spec.ts` "Who may give" at phone-360 (a `page.goto` 30 s timeout twice, one `#nv-title` text read before it was filled), all under load. Re-run: `mar.spec.ts -g "Who may give" --repeat-each=3` at both sizes = 30 of 30 green. Not caused by SAH-40 (same cold-start timeout signature as the one seen once in the new spec) |
| **Re-test after the D-1 fix (9 Nov 2026)**: build rebuilt, new spec x3, phone-360 and desktop-1440, 4 workers | 88 of 88, 88 of 88, 88 of 88 |
| **Re-test**: safety, pharmacist, mar, medicine-round, emergency specs once, both sizes | 880 passed, 2 failed, both a 30 s test timeout under load (the run took 19.5 min with other agents active): emergency.spec.ts "bays stepper goes from 0 to 10 ..." at phone-360 and mar.spec.ts "not-admitted: the patient was discharged: after the sheet closes the focus ..." at desktop-1440. Re-run of both, --repeat-each=3, both sizes: 12 of 12 green. Not caused by SAH-40 (neither touches the Safety refusal path) |
| **Final re-run after the review fix pass (9 Nov 2026)**: build rebuilt (`npm --prefix frontend run build`), new spec x3, phone-360 and desktop-1440, 4 workers | 122 of 122 (61 tests per size, before two late additions), 122 of 122, 122 of 122. With the final spec (63 per size): 126 of 126, 126 of 126, 126 of 126 |
| **F-1 fix check (9 Nov 2026)**: build rebuilt, `refused-refresh.spec.ts` x3, both sizes, 4 workers | 126 of 126 x3 (the F-1 probe is now a real focus assertion) |
| **F-1 fix check**: `emergency.spec.ts` once, both sizes, 4 workers | 148 of 148 |
| **Final re-run**: safety, pharmacist, mar, medicine-round, emergency, admitted-cards, nursing, discharge, app-shell specs once, both sizes | 1,287 passed, 6 skipped (old specs, unchanged), 1 failed: `safety.spec.ts` "subtitle by role ..." at phone-360, a `page.goto` 30 s timeout (cold start under load, the same signature as before). Re-run `--repeat-each=3`, both sizes: 6 of 6 green. Not caused by SAH-40 |
| **R-1 and R-2 check (9 Nov 2026)**: build rebuilt (`npm --prefix frontend run build`, 0 errors), the 10 new runs alone, phone-360 and desktop-1440, 4 workers | 10 of 10 |
| **R-1 and R-2 check**: `refused-refresh.spec.ts` x3, both sizes, 4 workers (68 tests per size) | 136 of 136, 136 of 136, 136 of 136 |
| **R-1 and R-2 check**: emergency, safety, admitted-cards, app-shell specs once, both sizes, 4 workers | 542 passed, 6 skipped (old specs, unchanged), 0 failed (7.0 min) |
| Frontend unit tests (developer's R-1 and R-2 fix run) | 2,228 pass (6 new); not re-run by QA |
| Frontend unit tests (developer's D-1 fix run) | 2,186 pass (20 new); not re-run by QA |
| Frontend unit tests (developer's review fix pass) | 2,221 pass (35 new); not re-run by QA |
| Frontend unit tests (`npm --prefix frontend test -- --watch=false`, re-run by QA, 8 Nov) | 154 files, 2,166 of 2,166 pass |
| `node scripts/validate.mjs docs` | fails only on `docs/stories/ADM-2.md` (name not by story ID; a known human item). The SAH-40 files pass |
| `node scripts/validate.mjs frontend` | lint passes; the test step is blocked by the script flag `--browsers=ChromeHeadless` (needs `@vitest/browser-*`; a human item, noted by the angular-developer). `validate all` was not run to the end: no backend change in this story |

## Notes

- The tile "—" for the Emergency queue (AC 4, second half) cannot be reached in the browser: the Emergency tab only shows when the Admitted list carries `emergency.writable`, so a list answer always exists there. The unit test covers the code path.
- AC 25 for DOCTOR, NURSE, FRONT_DESK, BILLING and VISITING_DOCTOR cannot be opened from the screen (the panel is not drawn for them); the server answers 403 on their first call, which is the backend IT of SAH-31.
- AC 3 "offline browser" is tested as an aborted request, the same code path as a status 0 answer.
- The Emergency menu badge (OQ-40-6) is out of scope. Observed: after the refusal on the menu route it still shows the old count (a number, no names).

## Defects (status after the re-test of 9 Nov 2026)

- **D-1: FIXED and verified in the browser.** Evidence: the probe is now 7 real tests, all green at both sizes in 3 of 3 repeats (88 of 88 each). After a 403 (`forbidden` and `module-off`) on the 60 s board reload: the open Response, Give (Medicine round), Verify and Reject sheets close, no patient name is left in the body, no respond, dose, verify or reject call is made, no board, audit-trail or pharmacist-list read happens in 3 minutes of fake clock, **Try again** brings the board back and the 60 s timer reads again (board reads +1, then +2). A save that was already on its way when the refusal landed does not bring the board back and causes no board read. The pharmacist rows are gone from the screen and from memory (after Try again the panel reads the list again). Before the fix the same scenario showed the board back without Try again and frozen.
- **D-2: accepted by the tech lead as the default under DEC-023** (a first-load Emergency `module-off` locks the whole page). The human may override. The test "first load: 403 module-off now locks the whole page" documents it; if the human overrides, only that test and the story table change.
- Left as is by the developer (counts only, no names, not tested as defects): after a Safety refusal the page's Safety tab count can still show the last counts; a toast for an action that already succeeded can still show.

## Findings of the final re-run (9 Nov 2026)

- **F-1 (angular-developer, Low): FIXED and verified (9 Nov 2026).** The Emergency page did not move the focus to the notice when its Admit sheet was closed by a refusal (the probe recorded `body`, 4 of 4 runs). Fix: `emergency.component.ts` `refuse()` now computes `focusWasInside` as `focusIsLost(host, document, retried) || this.sheet !== null`. Evidence: the probe annotation in "security 1, forbidden" is now `await expect(alertBox(page)).toBeFocused()`; build rebuilt; `refused-refresh.spec.ts` x3 at phone-360 and desktop-1440, 4 workers: 126 of 126, 126 of 126, 126 of 126; `emergency.spec.ts` once, both sizes: 148 of 148. No defects.
- **C-1, C-2:** two older test expectations changed by design (see "Expectations changed by design"). The story text for AC 19 (the sheet "keeps its own message") should be aligned by the business-analyst.
- D-1: fixed (verified before). D-2: accepted by the tech lead (DEC-023); the test "first load: 403 module-off now locks the whole page" documents it.
- Unchanged, counts only: the Emergency menu badge keeps its old count after a refusal on the menu route (OQ-40-6, out of scope); a Safety `module-off` shows the notice, not the lock page (code review 6, a note for the human).

## Defects found earlier (history)

- **D-1 (was: Safety board, medium, low likelihood; now fixed, see above).** A sheet that was open when the board was refused (Response sheet; the Give sheet of the Medicine round uses the same path) stays open with the patient name ("Response · Geeta Menon") after the board is cleared. When it then saves, `reloadAll()` / `roundAnswered()` read the board although the board is denied, and a 200 shows the board again **without Try again**. Worse, `denied` stays true, so the 60-second timer never reads again: the board is on screen with no "Could not refresh" notice and never updates (observed: 0 board reads in 2 minutes). Probe: "PROBE: a sheet that was open when the board was refused". Suggested fix: skip or reset `denied` consistently in `reloadAll` / `roundAnswered`, and close an open sheet on a refusal.
- **D-2 (Emergency first load `module-off`, low, needs the human).** The story table says the first load is unchanged (error notice with Try again). The build locks the whole page (architecture note 3.4 says both). In practice the Emergency tab is only reached after the Admitted list answered, so this is a race case. Test "first load: 403 module-off now locks the whole page" documents the behaviour; the human confirms it is acceptable.
