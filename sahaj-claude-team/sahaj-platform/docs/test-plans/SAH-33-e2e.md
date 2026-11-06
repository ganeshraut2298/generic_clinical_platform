# SAH-33 · Safety board · browser (Playwright) test plan and results

Story: `docs/stories/SAH-33.md` · Decisions: DEC-015, ADR-007 · Contract: `docs/api/ipd.yaml` (safety parts)
Mockup screen: IPD → Safety tab (`ipSafety`, `escRow`, `sfack`, `sfresp`, `sfrespok`, `sfup`, `sfLog`, `sfban`)
Spec: `frontend/e2e/safety.spec.ts` (new file: needs `git add -f`, see the handoff). Projects: `phone-360` and `desktop-1440`; the layout tests also set 360 and 1280 px themselves.
Scope of this file: the browser part only. The backend integration tests and `docs/test-plans/SAH-33.md` belong to the other QA run.

## How the tests work

- The backend is **not** running. Every API call is mocked in the browser with `page.route()`, as in `nursing.spec.ts` and `emergency.spec.ts`. A call that is not mocked is answered 404 and the test fails on `w.unmocked`.
- The small mocked server mimics only the rules the screens depend on (version check, state, replay, the Acknowledged / Closed states, cursor paging of the log). It is **not** proof of the real rules: the state machine, the version and `seniorCalls` checks, the role re-read, the event texts and the audit rows are the job of the backend ITs.
- The 60-second reloads use the Playwright fake clock (`page.clock.install()` then `runFor(61_000)`), as in `emergency.spec.ts`.
- The server clock is fixed at `2026-11-05T05:47:00Z` (11:17 AM IST); every "ago" and every overdue flag comes from the mocked answer, never from the browser.

## Coverage: acceptance criterion → test (all in `safety.spec.ts`)

| AC | What is checked | Test (describe › title, abbreviated) |
| --- | --- | --- |
| 1 | Tab last, count of not-closed next to the name; OWNER, DOCTOR, NURSE | Safety tab › `<role>: the Safety tab is the last tab...` (3 roles) |
| 1 | No tab, no banner, board never read for FRONT_DESK, BILLING, VISITING_DOCTOR, PHARMACIST | Safety tab › `<role>: no Safety tab, no banner and the board is never read` (4 roles) |
| 1 | Nobody admitted: no tab | Safety tab › `nobody admitted: no Safety tab even for a doctor` |
| 1 | Count hidden at 0 | Safety tab › `nothing open: the tab has no number...` |
| 1 | Last tab, after Emergency | Safety tab › `with Emergency on, Safety is the tab after Emergency` |
| 1 | IPD Off (403 module-off), 404 of another clinic's id | Not in the browser: backend ITs (the IPD lock page is covered by the existing IPD specs) |
| 2 | Order notice, tiles, escalations, audit trail | Safety tab › `the sections of the board from top to bottom` |
| 3 | Notice text, singular/plural, doctor and nurse endings, "1 h 5 min ago", none when nothing is late | Red notice and tiles › OWNER / NURSE / `nothing past target` |
| 4 | Tile Escalations open (red number, "N past target" in red, "All within target"); tab, tile and open rows equal; Your patients for NURSE only | Red notice and tiles › `tile Escalations open...`, `OWNER and DOCTOR have no "Your patients" tile`, `NURSE: the second tile Your patients...` |
| 5 | Row: badge (High red, Medium amber), name and bed, raised line, target, " · escalated to senior N×", order as the server sent it | Escalation rows › `rows come in the order...`, `Open past target`, `Medium`, `a senior call adds...` |
| 5 | Subtitle of the panel, empty text | Safety tab › `the sections of the board...`, `no escalations at all...` |
| 6 | Pills Not seen / Overdue · not seen (row highlighted) / Doctor reviewing / Closed; Acknowledged and Closed detail lines | Escalation rows › `Open within target`, `Acknowledged`, `Closed` (OWNER and NURSE) |
| 7 | Overdue and "ago" from the server only; acknowledging stops the overdue state | Acknowledge › `happy path` (notice drops from 2 to 1 and the tile follows); every test uses the fixed `asOf` |
| 8 | Acknowledge button by role, toast, request body `{version}`, reload, event in the trail | Escalation rows › `OWNER and DOCTOR: ...`, `NURSE: only Call senior...`; Acknowledge › `happy path` |
| 9 | Response sheet: title, subtitle, select with five options, first preselected, text area (3 rows, placeholder), Cancel, Close escalation | Response sheet › `opens from Record response...`, `Cancel and the x button...` |
| 10 | Empty plan refused (client and server 400), 1,000 / 1,001 characters, control and text-direction characters, line break allowed, action refused by the server, toast without the plan, sheet closes, board reloads, plan not in the trail | Response sheet › `an empty or blank plan...`, `1,000 characters...`, `control and text-direction...`, `happy path`, `closing an Acknowledged escalation...`, `server 400 on the plan`, `server 400 on the action` |
| 11 | Call senior (NURSE): body `{version, seniorCalls}`, toast "Call recorded for <patient>. No alert is sent: phone the senior doctor now.", "escalated to senior N×", one tap one call, disabled while it runs, double click once | Call senior › `NURSE: sends version and seniorCalls...`, `the button is off while the request runs...` |
| 12 | The SAH-30 toast stays unchanged on the REOPENED outcome; a row whose score rose is redrawn from the reload | Nursing sheet › `outcome REOPENED keeps the SAH-30 toast unchanged`; Acknowledge › `409 escalation-changed: the score rose...`. The re-open itself (Acknowledged to Open on a risk rise) and the Not seen state after it are server rules (backend ITs); the browser draws any Open row as Not seen with Acknowledge (Escalation rows) |
| 13 | 409 `escalation-changed` / `escalation-state`: notice with the server text, board reloads, Dismiss, the retry sends the new version; the Response sheet adopts the reloaded row; closed row locks the sheet | Acknowledge › three 409 tests; Call senior › two 409 tests; Response sheet › `409 escalation-changed` (two), `409 escalation-state` |
| 14 | Discharge sheet warning before the button (normal and death/LAMA/referred wording), none when no escalation, FRONT_DESK sees it, 409 `escalation-open` shown once and the sheet reloads | Discharge sheet › four tests |
| 15 | Concurrency (two doctors, lock, rollback) | Not in the browser: backend concurrency ITs. The browser shows its outcome: 409 `escalation-state` with the other doctor's name |
| 16 | First page 14 (`limit=14`), subtitle by role, kind class and icon for all nine kinds, "<user> · <patient> · bed <code>", time today / earlier day, empty text, Show earlier entries (limit 50 with cursor, gone at the end), events error, failed Show earlier keeps the button, 60-second merge | Clinical audit trail › 8 tests; Reloads › `a reload while older entries are showing...` |
| 17 | No plan, no patient details in toasts and in the trail | Response sheet › `happy path` (toast and trail), Clinical audit trail › `shows the plan of a closed escalation nowhere`. The log text rules are a backend IT |
| 18 | Read-only IPD: the browser follows the row flags the server sends; the rule itself is a backend IT | Not separately tested in the browser (no UI difference except the server flags) |
| 19 | Banner with names and "Open safety board", switches tab, none when nothing is late, none for roles without the tab, follows after acting | Banner › 3 roles, `nothing past target`, `after acting on the board...`; Safety tab › four roles without the banner |
| 20 | Buttons off while a request runs, row not changed before the answer, error keeps the sheet and the typed plan, lost answer then replay (acknowledge, call senior, close), 60-second reload, "Loading", stale notice with the last `asOf` time, summary poll reads the list only when the counts change, failed poll changes nothing | Acknowledge, Call senior, Response sheet (500, network failure, lost answer), Reloads › 7 tests |
| 21 | Audit rows written by the server | Not in the browser: backend ITs |
| 22 | SAH-30 toast unchanged | Nursing sheet › `outcome REOPENED keeps the SAH-30 toast unchanged` |

Cross-cutting (browser): layout at 360 and 1280 px (OWNER and NURSE, long names, 12 senior calls, the sheet and its error state; no sideways scroll, row buttons at least 44 px high); two console-error checks (a doctor session and a nurse session with a 409); keyboard: Tab / Shift+Tab / Enter / Space on the row buttons, End key on the tab list, focus trap of the sheet (10 Tab and 10 Shift+Tab stay inside), arrow key on the select, Escape closes and returns focus to Record response, labels and `aria-describedby` of the errors, accessible names of the row buttons.

## Negative, role, boundary and isolation cases

- Role: 3 roles with the tab, 4 without (no tab, no banner, no call to the board, the summary or the log); buttons by role (OWNER, DOCTOR, NURSE); no "Your patients" tile for doctors.
- Boundary: 14 entries exactly (no Show earlier), 70 entries (14 + 50 + 6), plan of 1,000 and 1,001 characters, blank plan, tab count 0.
- Negative: 400 on version, action and note; 403, 404, 409 (two types), 500, network failure, lost answers; failed first load with Try again; failed 60-second reload; failed summary poll; events error.
- Tenant isolation is a server rule (RLS, 404): it is covered by the backend ITs. The browser has no way to ask for another clinic's data.

## Results (5 Nov 2026, after a fresh `npm --prefix frontend run build`, under the frontend lock)

| Run | Command (in `frontend/e2e`) | Result |
| --- | --- | --- |
| Safety 1 | `npx playwright test safety.spec.ts --workers=3` | 190 passed (95 tests x 2 projects), 0 failed |
| Safety 2 | same | 190 passed, 0 failed |
| Safety 3 | same | 190 passed, 0 failed |
| Other IPD specs, once | `nursing.spec.ts emergency.spec.ts discharge.spec.ts bed-transfer.spec.ts` | 412 passed, 0 failed |

Earlier runs on the way (kept for the record, not hidden):
- First run of the new spec: 186 passed, 4 failed. Both failing tests were mistakes in the test, not in the app: (a) the events-error test expected the fallback text where the app correctly shows the server's own `detail` (the test now covers both: a network failure gives the fallback, a 503 gives the server text); (b) the Nursing test did not mock `GET /ipd/admission-options`, which the admission sheet calls.
- Three further runs with 4 workers while the other QA run was loading the machine: 189 of 190 each time, with one different failing test each time, always on `desktop-1440`, always a tap on "Close escalation" in the Response sheet that had no effect (no error, no toast). It did not reproduce in 108 repeated runs of the Response sheet tests with 3 workers. Most likely the tap landed during the sheet's opening animation under CPU load. The spec now waits for the dialog to finish opening (`openResponse` helper) and the runs above used 3 workers and were clean. Not an app defect on the evidence seen; if it comes back it should be looked at again before it is called a test flake.
- `nursing.spec.ts` "a changed field starts a new reading with a new client key" failed once on `phone-360` (32.8 s, a timeout) in the same loaded 4-worker run and passed in the clean run: load, not SAH-33.

`node scripts/validate.mjs` was **not** run by this task (the brief asked for the Playwright runs only); the tech lead runs it for the story.

## Findings

App bugs found: **none**.

Notes for the owners (not failures):
1. **angular-developer / business-analyst, OQ-33-2 (open):** the tile "Your patients" reads "In your wards" (mockup text). AC4 proposes "All admitted patients" because wards do not exist yet, so the text is not true today. The test asserts the mockup text; change it with the decision.
2. **angular-developer, small:** the Response sheet has no way to reach the message "Choose what was done" from the keyboard or mouse, because the select has no empty option and the first action is preselected. The message only appears if the server answers 400 on `action`; the test covers that path. Fine as built, mentioned so nobody expects a client-side test for it.
3. **Behaviour confirmed, worth a human glance:** a 409 `escalation-changed` on Record response keeps the sheet open, adopts the reloaded row (new score in the subtitle, new version) and keeps the typed plan; the doctor then taps Close escalation again with the new version. That matches AC13, but it means the doctor can close in two taps against a score he has just been told about. A human may want the second tap to require a fresh look (for example a changed button label). Not changed here.
4. The 4xx answers (409, 404) make the browser write a "Failed to load resource" line to the console. The console test for the nurse session ignores exactly that text; nothing else was logged.
5. Not testable in the browser (backend ITs by the other QA run): IPD Off `module-off`, tenant isolation (404 for another clinic's escalation), the role re-read, the version / `seniorCalls` rules, the re-open on a risk rise, `escalation-open` at discharge and the close-at-discharge outcomes, the Read-only rule, the event texts, the audit rows, the counts agreeing on the database clock.

## Files

- `frontend/e2e/safety.spec.ts` (new, untracked: needs `git add -f` because `frontend/e2e` is ignored for new files; the tech lead stages it)
- `docs/test-plans/SAH-33-e2e.md` (this file)

## Review fix pass (6 Nov 2026) · what changed in the browser tests

After `docs/reviews/SAH-33-code.md` and `SAH-33-security.md` the front end changed (ADR-007 R1 to R6; code review 4, 5, 6, 12, 13; security L4, I6). `safety.spec.ts` was updated; the discharge, nursing, emergency and bed-transfer specs needed no change.

The small mocked server now returns a `changeToken` in the counts (an opaque string that follows the ids and versions of the not-closed rows, so two different sets with equal counts get different tokens) and the Discharge sheet answers carry `signoffBeforeEscalation` and a signature of a chosen type.

| Item | Test (describe › title, abbreviated) | Result |
| --- | --- | --- |
| R1, code review 2: the banner/list reloads when the counts are equal but the token differs | Reloads › `the change token reloads the list when the counts are equal: one overdue escalation closes while another becomes overdue...` (the banner names the new patient; the next poll with the same token reads no list) | pass |
| Code review 4: the Response sheet cannot be closed while the request runs, or by Esc / backdrop with a typed plan | Response sheet › `while the request runs Cancel, x, Escape and a click outside do nothing...`; `with a typed plan Escape and a click outside the sheet do nothing...` | pass |
| Code review 4: "Discard the plan you typed?" (`[data-testid=response-discard]`, `response-keep`, `response-discard`, `response-x`) | Response sheet › `Cancel and the x button close the sheet at once when no plan was typed`; `with a typed plan Cancel and the x button ask "Discard the plan you typed?"...` (focus on Discard plan, back on the plan after Keep editing, the discarded plan is gone); the 409 `escalation-state` test now discards through the question | pass |
| Security I6: counter "N / 1,000" and the over-limit message (`[data-testid=plan-counter]`) | Response sheet › `the plan has a counter "N / 1,000"; over the limit it turns red with the message before anything is sent` (0, 9, 1,000, 1,000 emoji, 1,001, back to 999; `aria-describedby` now names the error and the counter) | pass |
| Code review 6: focus on the Safety tab after the banner button | Banner › the three role tests assert `#ipd-tab-safety` is focused | pass |
| Code review 6: focus on the panel heading after a close | Response sheet › `after Close escalation succeeds focus goes to the panel heading...` (`#sf-esc-h`) | pass |
| Code review 5: a failed first load is cleared by the next good refresh | Reloads › `a first load that failed is cleared by the next good 60-second refresh without pressing Try again` | pass |
| Security L4, code review 12: no polling while `document.hidden`; one reload when the tab shows again | Reloads › `polling is paused while the tab is hidden and one reload follows...` (board and audit trail) and `...on the Admitted tab too (counts poll)` | pass |
| R2: the Discharge sheet with `signoffBeforeEscalation`: box unticked whatever the signature, the "signed earlier" warning for Death, LAMA and Referred, Normal unchanged | Discharge sheet › `the doctor signed before the escalation started...`; `the same signature that comes after the escalation...`; `signing again after the escalation ticks the box...` | pass |
| Code review 13: touch target of 44 px instead of 36 | Layout, console and keyboard › the four width tests (OWNER, NURSE; 360 and 1280 px) assert the smallest row button is at least 44 px (a normal `expect`) | pass (after the angular-developer fix `min-height: 44px`, see "Fix check" below) |

### Results (6 Nov 2026, after a fresh `npm --prefix frontend run build`, under the frontend lock; the build did not fail on the fonts)

| Run | Command (in `frontend/e2e`) | Result |
| --- | --- | --- |
| Safety 1 | `npx playwright test safety.spec.ts --workers=3` | 206 passed, 8 failed (214 = 107 tests x 2 projects) |
| Safety 2 | same | 206 passed, 8 failed |
| Safety 3 | same | 206 passed, 8 failed |
| Other IPD specs, once | `nursing.spec.ts emergency.spec.ts discharge.spec.ts bed-transfer.spec.ts` | 412 passed, 0 failed |

The same 8 fail in every run: the four layout tests x 2 projects, all with `smallest row button height (touch target, 44 px): expected >= 44, received 38`. Nothing else failed in any of the three runs (no flake seen). The test is not weakened or skipped; it stays red until the row buttons are 44 px high.

Earlier runs on the way, kept for the record: the first run of the new tests had 13 failures: 4 x 2 the 44 px assertion (kept), plus 5 mistakes in the tests that were corrected: (a) the 409 `escalation-state` test closed the sheet with Cancel after typing a plan, which now asks first (the test discards through the question); (b) the keyboard test expected `aria-describedby="rs-note-err"`, which now also names the counter; (c) the new "Escape closes again after the plan is cleared" check pressed Escape before the sheet had seen the change on the desktop project (it now waits for the counter to read 0 / 1,000).

### Findings (review fix pass)

- **angular-developer, small:** the buttons of the escalation rows (Acknowledge, Record response, Call senior in `.sfrow`, `safety.component.html` lines 95-150) are 38 px high; the project rule is a 44 px touch target (`.claude/rules`, `_console.scss`). `.notice button` was already set to 44 px (`safety.component.scss:27`); the row buttons need the same `min-height: 44px`.

### Fix check: 44 px touch target (6 Nov 2026, after the angular-developer fix)

The angular-developer added `min-height: 44px` to `.sfacts button` and `.more button` (`safety.component.scss`) and to `.sheet-f button` and `.notice button` (`response-sheet.component.scss`). Run after a fresh `npm --prefix frontend run build`, under the frontend lock, in `frontend/e2e`. In `safety.spec.ts` the 44 px assertion is now a normal `expect` (was `expect.soft`).

| Run | Command | Result |
| --- | --- | --- |
| Safety 1 (with the assertion still `expect.soft`) | `npx playwright test safety.spec.ts --workers=3` | 213 passed, 1 failed (214). The 8 layout failures are gone. The one failure: `desktop-1440` › Response sheet › `with a typed plan Cancel and the x button ask "Discard the plan you typed?"...` (see below) |
| Safety 2 (normal `expect`) | same | 214 passed, 0 failed |
| Safety 3 | same | 214 passed, 0 failed |
| Other IPD specs, once | `nursing.spec.ts emergency.spec.ts discharge.spec.ts bed-transfer.spec.ts` | 412 passed, 0 failed |

- The four layout tests (OWNER, NURSE; 360 and 1280 px) x 2 projects now pass with the 44 px assertion: no wrap, no overflow and no sideways scroll at 360 px. No layout test broke because of the fix.
- The single failure in Safety 1 did not come back: the test passed 30 of 30 when run alone (`--repeat-each=15`, both projects, 3 workers) and the next two full runs were clean (428 of 428). The failure details were lost (the reporter output was cut to the last lines); it is the same kind of event as the 5 Nov loaded-machine flake (a tap on a sheet button on `desktop-1440` that did not register while the sheet was still opening), and the machine was also building and running other work. Not called an app defect on this evidence; if it returns, capture the trace (`trace: retain-on-failure` is on, `frontend/e2e/results`) before deciding. Possible test hardening for the QA owner: wait for the sheet's opening animation to end in `openResponse` (it already waits for the dialog) before the first tap on Cancel.
- Counts of this fix pass: Safety 214 + 214 clean (2 of 3 full runs), 1 isolated failure in run 1; other IPD specs 412 of 412. The Review fix pass table above lists the 8 failures of the earlier run; they are resolved.
