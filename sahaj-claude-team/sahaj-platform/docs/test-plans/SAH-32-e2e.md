# SAH-32 · Medicines (MAR) and giving a dose with the bedside checks · browser (Playwright) test plan and results

Story: `docs/stories/SAH-32.md` · Decisions: ADR-011 (ADR-010 and ADR-009 as amended), DEC-018 · Contract: `docs/api/ipd.yaml` (`GET /admissions/{id}/mar`, `POST /medicine-orders/{id}/doses`, `NursingSheet.doseCount`, `MedicineOrderRow.lastDose`)
Mockup screen: Nursing sheet → Medicines (MAR) tab (`sfNsTabs`, `sfMar`, `sfSlot`, `sfSlotChip`, `sfMedHead`) and the Give sheet (`sfGive`, `sfgiveok`, `sfnogive`, `sfIdBar`). The mockup's `sfscan` ("Scan wristband") is deliberately not built (DEC-018 item 1).
Specs: new `frontend/e2e/mar.spec.ts` and the shared mock `frontend/e2e/sah32-world.ts` (no tests in it); `frontend/e2e/sah31-world.ts` (extended), `frontend/e2e/nursing.spec.ts` and `frontend/e2e/medicine.spec.ts` (one new test each, see "Changes to existing specs").
**The whole folder `frontend/e2e/*.spec.ts` is ignored by git.** The human stages the files with:
`git add -f frontend/e2e/mar.spec.ts frontend/e2e/sah32-world.ts frontend/e2e/sah31-world.ts frontend/e2e/nursing.spec.ts frontend/e2e/medicine.spec.ts docs/test-plans/SAH-32-e2e.md`
Projects: `phone-360` and `desktop-1440` (every test runs in both); the layout tests also set 360 and 1280 px themselves.
Scope of this file: the browser part only. The backend integration tests (races, the clock at 60 and 61 minutes, the colleague PIN lock and limits, roles, the audit rows, the append-only table, RLS) belong to the other QA run.

## How the tests work

- The backend is **not** running. Every API call is mocked in the browser with `page.route()` by the shared mock (`sah31-world.ts` for the admission list, admission sheet, EMR and platform calls; `sah32-world.ts` for the Nursing sheet, the MAR and the dose endpoint, registered after it so it wins). A call that is not mocked is answered 404 and the test fails on `w.unmocked`.
- The mock "server" mimics only what the screens depend on, in the order of AC 6: role (OWNER and NURSE only), read-only, discharged, slot known, slot already recorded, order not Active, slot not due; then for **Given** the empty box (400), the wristband compared without case and spaces (409 `wristband-mismatch`, nothing recorded, the scanned text kept in `m.denied`), the statement (400), the high-alert second nurse and PIN (400 on both fields with one text; not on the list 400; locked 403 `pin-locked`; no PIN 409 `pin-not-set`; wrong 403 `wrong-pin` with the tries left); for **Not given** the reason (400). The client key is stored: same key and same values = 200 `replayed`, other values = 409 `client-key-reused`. A recorded dose changes the slot and the EMR order's `lastDose`. It is **not** proof of the real rules: the clock windows, the colleague lockout and limits, roles, audit, RLS and the append-only table are backend ITs.
- Per-test hooks: `m.doseAnswer(call, body, orderId)` forces an answer (a problem, `'abort'` = lost connection, `'lose'` = stored, then lost); `m.getAnswer`, `m.doseDelay`, and plain edits of `m.orders`, `m.state`, `m.status` between steps (another session winning the slot, the order stopped meanwhile).
- The server clock of the MAR is fixed at `2026-11-06T05:35:00Z` (11:05 AM IST); every time on screen comes from the answers. The "60 minute edges" test sets the device clock to 2031 (`page.clock.setFixedTime`) and shows that nothing on the MAR depends on it.
- Secrets used in the tests appear nowhere else (second nurse PIN `4827`, wrong PIN `6150`, other patient's band `UH-0077`), so "not in the DOM, a field, storage, the console or a URL" is a real search (`expectNowhere`, `collectConsole`, `watchNetwork`). A request body may contain a PIN only on the dose POST.
- A refusal that closes the sheet is checked in two tests per case (the message, the closing and the second read; and where the focus is), so a focus problem does not hide the rest.
- Retries after a toast: on a phone the toast sits over the footer buttons (finding F1), so the helpers `tapGiven` and `tapNotGiven` close any toast first. The finding itself has its own test that does not use the helper.

## Coverage: acceptance criterion → test → result

Result column: result of the last of the three runs of `mar.spec.ts` (both projects) unless a finding is named. **P** = passed in `phone-360` and `desktop-1440`; **F1/F2/F3** = failed in the first build, now fixed (see "Findings" and "Runs").

| AC | What is checked | Test (describe › title, abbreviated) | Result |
| --- | --- | --- | --- |
| 1 | Two tabs, "Vitals and notes" (heart icon, default) and "Medicines (MAR)" (pill icon), for OWNER, NURSE and DOCTOR; title stays "Nursing · <name>"; the MAR is not read until its tab is chosen | Nursing sheet tabs › `<role>: two tabs...` (3) | P |
| 1 | Count of due and late doses on the tab, only above 0; follows the server's number; falls as doses are recorded and disappears at 0 | Nursing sheet tabs › `the count on the tab...`; Given › `<role>: wristband...count falls`, `the last slot of the day...count reaches 0`; Record not given › `a reason...` (count 1) | P |
| 1 | Subtitle on the MAR tab "Bed <code> · medicine round, today 11:05 AM" (IST time from the answer); tab panel `aria-controls` / `aria-labelledby`; Done button; no "Save vitals" on this tab | Nursing sheet tabs › `choosing the MAR tab...` | P |
| 1 | Accessible tab pattern: arrows wrap, Home and End jump, focus follows, roving tabindex, other keys do nothing; automatic activation | Nursing sheet tabs › `keyboard: arrow keys...` | P |
| 1 | Back to Vitals: typed values kept, sheet read again | Nursing sheet tabs › `back to Vitals and notes...` | P |
| 1 | Done closes the Nursing sheet; the admission sheet stays | Nursing sheet tabs › `Done closes...` | P |
| 2 | Identity bar (name, "<UHID> · <age> · <sex> · bed <code>", allergy chip); one block per order: name, red "High-alert · <class>" with the hint (text and title), small line "<route> · <frequency> · <ordering doctor>", status pill, slots; closing line; Done | MAR tab › `identity bar, one block per order...` | P |
| 2 | No order: "No medicines on the chart. The doctor orders them from EMR." with the closing line and Done | MAR tab › `no order on the chart...` | P |
| 2 / 10 | Only what the server sends is listed (a Stopped order gone after the next read) | MAR tab › `a stopped order is not on the MAR...` | P |
| 3 | A Pharmacist check order: pill, lock line "Cannot be given until the pharmacist checks the order.", slots say "Waiting for pharmacist" and are not buttons | MAR tab › `an order still in Pharmacist check...` | P |
| 3 | Every slot state in words: late "N h late · Give" (4 h and 1 h), due "Due now · Give", later "Later today", Given (check icon, "<time> · <last name>", hint "Given by <nurse>, checked by <second nurse>"), "Not given · <reason>", "Refused · <reason>", "Not recorded", "Before the order started"; only DUE and LATE are `<button>`s | MAR tab › `every slot state shows its own words...` | P |
| 3 | The 60 minute edges as the server states them (61 late = late 1 h; 60 late = due; 60 ahead = due; 61 ahead = later); the device clock (2031) changes nothing | MAR tab › `the 60 minute edges are the server's...` | P |
| 3 | Times read "8 AM", "2 PM", "8:30 AM" | MAR tab › `the times read 8 AM, 2 PM and 8:30 AM in IST` | P |
| 3 | Load fails: message, Try again reads again; a refused read (403) shows the server text and no medicine | MAR tab › `a load that fails...`, `a refused read (403)...` | P |
| 4 | DOCTOR: slot buttons disabled, no lock line, pressing opens nothing and sends nothing | Who may give › `DOCTOR reads the MAR...` | P |
| 4 | OWNER and NURSE: due and late buttons enabled and open the Give sheet | Who may give › `<role>: the due and late slot buttons...` (2) | P |
| 4 / 11 | A call that is refused with 403 (role changed): the server text in the sheet, sheet stays ("Only nurses and the clinic owner can give medicines.", DEC-018 item 3) | Races and failures › `a 403 from a role...` | P |
| 4 | Same slot from two sessions: 409 `dose-recorded` closes the sheet, toast, MAR read again and shows the other nurse's dose; a double tap sends one request | Races and failures › `another nurse gave the same slot first...`, `a double tap on Given...`; table of closing 409s › `dose-recorded` | P (focus after the close: F2) |
| 4 / 11 | 409 `order-state`, `order-not-verified`, `dose-not-due`, `dose-before-start`, `not-admitted`, `ipd-readonly` and 400 on `slotAt`: the message, the sheet closes, the MAR is read again and shows the new state (pending note, Later today, Before the order started, lock lines) | Races and failures › `<case>: the message shows, the sheet closes and the MAR is read again` (8 cases) | P |
| 5 | Give sheet: title "Give · <medicine>", subtitle "<time> dose · <route> · <frequency>", identity bar, step 1 text, box named "Wristband MRN" with its placeholder, the checkbox text, last step "2 · If you are not giving it", Reason with "Choose a reason" and five reasons, buttons "Record not given" and "Given" (check icon), **no Scan wristband** button or text | Give sheet › `a medicine that is not high-alert...` | P |
| 5 | Subtitle with " · 3 h late" | Give sheet › `a late slot says how late...` | P |
| 5 | High-alert: "2 · Second nurse check · high-alert <class lower case>" with its small text, "Checked by" (Choose a nurse + the other nurses, never the signed-in one), "Their PIN" (password, numeric, 4, placeholder), the last step "3" | Give sheet › `a high-alert medicine...` | P |
| 5 | PIN box takes digits only, at most 4; no second nurse on the clinic: only "Choose a nurse" and Record not given still works | Give sheet › `the PIN box takes digits only...`; Second nurse list › `no other nurse on the clinic...` | P |
| 5 | Focus: box on opening; Esc and the X close without a request and focus returns to the slot; the sheet is a dialog named by its title and the focus stays inside (16 Tab and 16 Shift+Tab) | Give sheet › `Esc and the close button...`, `the sheet is a dialog named by its title...`; Accessibility › `keyboard only...` | P |
| 6 | Wrong wristband: toast "Wristband does not match <name>. Stop. Check the patient before giving anything.", live line "Wrong patient. This band is not <name>'s." under the box (polite live region), nothing recorded, entries kept, focus on the box; the line goes when the box is edited; the right band then gives it with the same key | Given › `a wrong wristband...`, `after a wrong band the right band...`; Give sheet › `the box has no live announcement...` | P |
| 6 | Empty box: "Scan the wristband first." (alert, aria-invalid, aria-describedby, focus, cleared by typing); statement not ticked: "Confirm the second identifier: name and date of birth." (focus on the checkbox); both missing shows only the first refusal | Given › `an empty box...`, `the statement not ticked...`, `both missing...` | P |
| 6 | Scanner style: text ending in Enter does not submit; Given then works | Given › `scanner style...` | P |
| 6 | High-alert without nurse or PIN: one message, once, under "Their PIN", focus there; a 2-digit PIN refused; a nurse not on the list: "Choose a nurse from the list." under "Checked by" | Given › `high-alert without a nurse and PIN...`, `a nurse that is not on the list...` | P |
| 6 | Second nurse PIN: wrong ("That PIN is not right." with no count of tries; only the own-PIN texts keep the count) under "Their PIN", PIN cleared, focus on the PIN, other entries kept, right PIN then works with the same key; locked and no-PIN: text under "Their PIN", focus on "Checked by", never a "Set my PIN" state, `GET /me/pin` never called, another nurse then works with a new key; 429 and 503 `pin-unavailable` under "Their PIN" | Second nurse PIN › 4 tests | P |
| 6 | A refusal moves the focus to the field that needs it (box, statement, PIN, reason) | Accessibility › `a refusal moves the focus...` | P |
| 7 | Given sends exactly `clientKey, slotAt, outcome, wristband, identityConfirmed` (no nurse or PIN for a medicine that is not high-alert); toast "<medicine> recorded as given to <name>"; sheet closes; slot reads Given with its time and last name; the slot chip is no longer a button and has the focus; OWNER and NURSE; the box is sent as typed (" uh-0001 ") | Given › `<role>: wristband and statement...` (2), `the box is sent as typed...` | P |
| 7 | High-alert: second nurse id and PIN sent; the chip carries "Given by <nurse>, checked by <second nurse>" | Given › `high-alert: second nurse and PIN are sent...` | P |
| 8 | No reason: "Choose why the dose is not given." under Reason, focus there; with a reason only `clientKey, slotAt, outcome, reasonKey` are sent (nothing from the typed box, tick, nurse or PIN); toast "Recorded: <medicine> not given · <reason>. The doctor sees this on the chart."; the slot reads "Not given · <reason>" (HELD) or "Refused · Patient refused" (REFUSED); works for a high-alert medicine without a second nurse | Record not given › 5 tests | P |
| 8 / OQ-32-4 | The EMR shows "Last dose 8 AM: Not given · Nil by mouth" and "...Refused · Patient refused" in the warning style, "Last dose 8 AM: Given · 11:05 AM · Pawar" in the normal style, a dose of another day with its date, nothing without a dose | EMR "Last dose" line › 2 tests; `medicine.spec.ts` › Medicines on chart › `SAH-32 "Last dose" line...` | P |
| 9 | Append-only has no screen: after a Given and a Refused dose the MAR has no Undo, Edit, Change, Delete or Correct button, the recorded slots are not buttons, pressing one opens nothing | Given › `append-only (AC 9)...`; MAR tab › `every slot state shows its own words...` (only DUE and LATE are buttons); the table itself is a backend IT | P |
| 10 | Stopped order gone; mid-day verification: "Before the order started" slots are not buttons; the order verified later | MAR tab › `a stopped order...`, `every slot state...`; the clock rule is a backend IT | P |
| 11 | IPD read-only and discharged: the lock lines "IPD is read-only. Doses cannot be recorded." and "This patient is not admitted any more." (`data-reason`), buttons disabled, reading and history still shown, count hidden when discharged | Who may give › `IPD read-only...`, `a discharged patient...` | P |
| 12 | Nothing about the patient in the console; PIN and scanned text not in the page, a field, storage, the console or a URL after each answer (wrong PIN, wrong band, lost answer, success); after the sheet closes the band text is gone; reopening starts empty | PIN and wristband text are not kept › 4 tests | P |
| 13 | Both buttons, the X and Esc are off while a request runs (`aria-busy`); a slot is never shown as given before the answer; a failed request keeps the sheet and the entries, clears the PIN; lost answer (stored, answer lost): retry with the same key gets 200 `replayed`, recorded once; no connection and a 500: the same text, nothing shown as given; 409 `client-key-reused`: text shows, new key on the next try, works; the failure text goes with the next try; a changed reason or outcome is a new key | Races and failures › `a double tap...`, `a lost answer...`, `no connection...`, `409 client-key-reused...`, `the failure text disappears...`; Record not given › `after a wrong band...`, `a different reason...` | P |
| Layout | 360 and 1280 px: no part sticks out of the sheet or scrolls sideways (long name, long allergy text, long medicine name, long doctor name), slot chips, tabs, Give, Record not given, selects, inputs, the statement row and the X are at least 44 px | Layout › `at 360 / 1280 px: the Give sheet (high-alert)...` (2) | P |
| Layout | The MAR tab and its **Done** button: every button on the MAR tab, Done included, is 44 px or more | Layout › `at 360 / 1280 px: the MAR tab...` (2) | P (was F3) |
| Focus | After a dose is refused with a 409 that closes the sheet, the focus is on a control of the Nursing sheet | Races and failures › `<case>: after the sheet closes the focus stays inside the Nursing sheet...` (7 cases) | P (was F2) |
| Phone | After an error the toast does not cover Given or Record not given | Second nurse list and the toast over the footer › `after an error the toast does not sit over...` | P (was F1; phone-360 and desktop-1440) |
| 6 / R6 | After a wristband mismatch the text in the box is selected (selectionStart 0, selectionEnd = length) and typing replaces it; the right band then gives the dose; a second mismatch selects the new text again | Review fixes › `R6: after a wristband mismatch...`, `R6: a second mismatch...` | P |
| 5 / I6 | The wristband box has `maxlength="200"`; 205 typed characters leave 200 and 200 are sent | Review fixes › `I6: the wristband box takes at most 200...` | P |
| 1 / R10 | Only the chosen tab has `aria-controls` (the other has none); every `aria-controls` points to an element that exists; both panels (`role=tabpanel`, `aria-labelledby`) have `tabindex="0"`; ids unchanged | Review fixes › `R10: only the chosen tab has aria-controls...` | P |
| 6 / R11 | A Give sheet toast (refusal and success) has `aria-live="off"` while its text is visible; the field alert (`#sf-id-err`, role alert) and the live line (`#sf-idok`, polite) remain | Review fixes › `R11: a Give sheet toast has aria-live off...`, `R11: the success toast...` | P |
| Phone / F1 | Give sheet toast at 360 px is at the top: its bottom is above the top of Given and of Record not given | Review fixes › `F1: at 360 px the Give sheet toast sits at the top...` | P |
| Phone / F1 (SAH-30) | Vitals toast (saved and error) at 360 px: toast bottom above the sheet footer and above Save vitals and note, toast on screen, the toast is not at the centre of Save | `nursing.spec.ts` › layout and console › `F1 (SAH-30 toasts): ...` (2) | P |
| Accessibility | Slot button described by the medicine name (`aria-describedby`), named list "Medicines on the chart", the high-alert hint also for a screen reader | Accessibility › `a slot button is named...` | P |

## Findings (app) · all three fixed and verified on the rebuilt app (6 Nov 2026, second round)

The table is kept as the record of what failed in the first build. Every failing test passed, unchanged, against the build with the fixes: F1 in `phone-360` and `desktop-1440`, F2 in all 14 runs, F3 in all 4 runs.

| # | Severity | Finding | Failing test(s) |
| --- | --- | --- | --- |
| F1 (fixed: toasts at the top on a phone through `sheetToast`; Give sheet and Vitals) | **Medium** (phone, in a safety flow) | **360 px: the error toast covers the footer buttons of the Give sheet.** After any refusal (wrong wristband, wrong PIN, missing box, lost answer) the Material snackbar (275 x 70 px at x 42, y 653 at a 740 px screen) sits over "Given" and "Record not given" (44 px high at y 677 to 721 when the sheet is tall, y 684 to 728 on a short one). `document.elementFromPoint` at the centre of Given returns the snackbar's button touch target (`span.mat-mdc-button-touch-target`), at the centre of Record not given the snackbar text. A tap in the next 8 seconds hits the toast, not the button, so the nurse has to wait or dismiss the toast before retrying a dose. At 1440 px the toast is at the bottom centre below the sheet and covers nothing. Suggest a snackbar position above the sheet footer on narrow screens (for example `verticalPosition: 'top'` for this sheet), or a shorter duration for refusals that are also shown in the sheet. | `mar.spec.ts` › Second nurse list and the toast over the footer › `after an error the toast does not sit over Given or Record not given...` (phone-360) |
| F2 (fixed: focus kept inside the Nursing sheet after a closing 409) | Low (a11y) | **Focus is lost to the page `<body>` when a 409 closes the Give sheet and the MAR is read again.** `MarTabComponent.afterGive` focuses the slot one tick after the dialog closes, but the answer of the second read arrives later and re-draws the slot: a DUE button that turns into a Given or Later `<span>`, a button that becomes disabled (read-only, discharged) or an order that is gone cannot keep the focus, so `document.activeElement` is `BODY` (measured `{"inside":false,"tag":"BODY","id":""}` in all 14 of 14 runs: `dose-recorded`, `order-state`, `order-not-verified`, `dose-not-due`, `dose-before-start`, `not-admitted`, `ipd-readonly`, in both projects). Keyboard and screen-reader users are dropped out of the Nursing sheet after the toast. The success path (the slot becomes a Given chip and keeps the focus) and the 400 on `slotAt` (the slot stays a button) are fine. Suggest moving the focus after the second read has been drawn (focus the slot if it is still there, else the tab or the first slot button, else the sheet title). | `mar.spec.ts` › Races and failures › `<case>: after the sheet closes the focus stays inside the Nursing sheet (not on the page body)` (7 cases, both projects) |
| F3 (fixed: 44 px footer buttons) | Low | **"Done" on the MAR tab is 40 px high** (`mat-flat-button` default), the project rule is 44 px for tablets. It is the same 40 px as the vitals footer buttons "Save vitals and note" and "Close" of the SAH-30 sheet (`[data-act="mar-done"]` 67 x 40, `nursing-save` 183 x 40, `Close` 71 x 40), so the one global rule for the footer buttons of the Nursing sheet fixes all three. Every other control of SAH-32 measures 44 px or more (slots 49, tabs 44, Given and Record not given 44, X 44, selects, inputs and the statement row 44). | `mar.spec.ts` › Layout › `at 360 / 1280 px wide: the MAR tab...` (4 runs: two widths in two projects) |

Not findings, for the owners:
- A toast that was opened before a newer sheet (for example the "recorded as given" toast and then the next Give sheet) is hidden behind that sheet until it times out. Nothing is lost (the slot shows the result), but a refusal toast raised while another sheet is on top would also be hidden; not measured.
- (Closed by R10.) The unselected tab had an `aria-controls` that pointed to a panel that is not in the DOM; now only the chosen tab carries it, and a test checks that every `aria-controls` points to an existing element. No automated accessibility checker was run (not installed in the e2e folder).
- `nursing-save` and `Close` of the vitals tab were 40 px (SAH-30); F3 fixed them with the Done button. The MAR layout test measures Done; the vitals footer buttons have no 44 px test of their own (only the existing 36 px minimum in `nursing.spec.ts`).

## Notes
1. The mock server's texts for new problem details follow DEC-018: 403 on the dose "Only nurses and the clinic owner can give medicines." and 403 on the MAR read "You cannot open the medicine round."; the screens show whatever `detail` the server sends, so these two texts are only an example of the real ones.
2. The server problem for a field validation has the generic detail "Some fields are missing or not valid."; the screens put the field message under the field and the generic detail in the toast. Tests assert both.
3. `GET /me/pin` is never called by any flow of the Give sheet (asserted with `w.pinGets` and the call log in every PIN test): the second nurse's `pin-not-set` is shown as text under "Their PIN" only.
4. AC 9 (append-only table, trigger) and AC 12 (audit rows, no names in logs) are backend ITs. In the browser they show as: a recorded slot offers no control, the PIN and the scanned text are on no surface after an answer, and the console is clean.

## Changes to existing specs (the Nursing sheet has tabs, the EMR has a "Last dose" line)

`nursing.spec.ts` and `medicine.spec.ts` passed unchanged against the new build before any edit (250 of 250 for those two files, `--workers=3`): the Vitals tab is the default and the old hooks (`#nv-title`, `#nv-bp`, `nursing-save`, ...) are all still on it. They were extended, none of their assertions was changed or removed:

| Spec | Change |
| --- | --- |
| `nursing.spec.ts` | The mock sheet has `doseCount: 0` (the field of the contract). New test `SAH-32: the sheet opens on the tab Vitals and notes (chosen), the Medicines (MAR) tab is next to it, the number on it shows only above 0, and the vitals footer is unchanged` (two tabs, selection, vitals panel, no count at 0, a count of 3 when the server says 3, no MAR subtitle on the Vitals tab, Save and Close still there, no `mar-done`, no unmocked call). |
| `medicine.spec.ts` | The mock order has `lastDose: null` (in `sah31-world.ts`). New test `SAH-32 "Last dose" line` (none without a dose; Given, Not given and Refused with their warning flag; a dose of another day carries its date "5 Nov, 8 PM"). |
| `nursing.spec.ts` (second round) | Two new tests, `F1 (SAH-30 toasts)`: at 360 px the Vitals saved toast and the 409 error toast sit above the sheet footer and Save vitals and note. No existing assertion changed. |
| `sah32-world.ts` (second round) | The second nurse's wrong PIN answers "That PIN is not right." with no count (the own-PIN texts in `sah31-world.ts` keep the count). |
| `sah31-world.ts` | `canOpenNursing` in the world (default false, as before), `OrderRow.lastDose` (default null), `later()` and `answer()` exported for `sah32-world.ts`. |

## Runs, second round (frontend lock held; 6 Nov 2026, after fixes F1 to F3, R6, R10, R11, I6 and the wrong-PIN text)

| Run | Command | Result |
| --- | --- | --- |
| Lint | `npm --prefix frontend run lint` | All files pass linting |
| Unit tests | `npm --prefix frontend run test` | 138 files, 1,752 tests passed |
| Build | `npm --prefix frontend run build` | OK |
| `mar.spec.ts` run 1 | `--workers=3` (97 tests x 2 projects = 194) | **194 passed, 0 failed** |
| `mar.spec.ts` run 2 | same | 194 passed, 0 failed (7.1 min) |
| `mar.spec.ts` run 3 | same | 194 passed, 0 failed (7.0 min) |
| Whole `frontend/e2e` folder, once | `playwright test --workers=3` (a backend Maven run was going in parallel) | 1,388 runs: **1,380 passed, 0 failed, 8 skipped** (31.9 min). No test timed out, so no re-run was needed. The 8 skips are the existing `test.skip(project !== 'desktop-1440')` lines of `app-shell.spec.ts` and `home.spec.ts`. |

The 19 runs that failed in the first round (F1 phone: 1, F2: 14, F3: 4) all pass now, with no change to those tests. New in this round: 7 tests in `mar.spec.ts` (14 runs; R6 x2, I6, R10, R11 x2, F1 for the Give sheet) and 2 in `nursing.spec.ts` (4 runs; F1 for the Vitals toasts), plus the changed wrong-PIN text. `node scripts/validate.mjs` and Maven were not run in this round.

## Runs, first round (frontend lock held; 6 Nov 2026)

| Run | Command | Result |
| --- | --- | --- |
| Lint | `npm --prefix frontend run lint` | All files pass linting |
| Unit tests | `npm --prefix frontend run test` | 137 files, 1,733 tests passed |
| Build | `npm --prefix frontend run build` | OK (the e2e server serves this build) |
| Before any change to the specs | `playwright test nursing.spec.ts medicine.spec.ts --workers=3` | 250 passed, 0 failed |
| `mar.spec.ts` run 1 | `--workers=3` (89 tests x 2 projects = 178) | 159 passed, 19 failed |
| `mar.spec.ts` run 2 | same | 159 passed, 19 failed (the same 19) |
| `mar.spec.ts` run 3 | same | 159 passed, 19 failed (the same 19) |
| New test added afterwards (`append-only (AC 9)`) | `-g "append-only"`, three times | 2 passed, 2 passed, 2 passed |
| Whole `frontend/e2e` folder, once | `playwright test --workers=3` | 1,370 tests: **1,343 passed, 19 failed, 8 skipped** (29.4 min). The 19 failures are the findings below and nothing else; the 8 skips are the existing `test.skip(project !== 'desktop-1440')` lines of `app-shell.spec.ts` and `home.spec.ts`. |

The 19 failing tests are 1 (F1, phone only) + 14 (F2: 7 cases x 2 projects) + 4 (F3: 2 widths x 2 projects). They failed in the same way in every run, so none is a flake; none was skipped, loosened or deleted. `mar.spec.ts` had 90 tests (180 runs): 161 passed, 19 failed (all fixed in the second round, above).

`node scripts/validate.mjs` and Maven were not run (the brief said so).

### Earlier runs on the way (kept for the record)
- First `mar.spec.ts` run, desktop only: 70 passed, 8 failed. Test mistakes: the multi-element `toHaveAttribute` (strict mode), a wrong note that the old "PIN error" stays after the PIN is edited (the app removes it, correctly), the title check used the wrong patient name in the long-name layout tests, the discharged case expected no buttons where disabled ones are right, and a sideways-scroll measurement that counted screen-reader-only text (428 px). All fixed in the tests.
- Second run, both projects: 147 passed, 23 failed. The wrong-text 403 expectations were updated to the DEC-018 wording, the footer check was changed from "in the viewport without scrolling" to "the sheet fits the screen and the buttons can be scrolled to" (the console sheets scroll as a whole by design), and the toast-over-footer finding was found here (a click on Given waited up to 8 s for the toast on a phone and timed the privacy test out); the helper now closes the toast first and the finding has its own test.


## Files
- `frontend/e2e/mar.spec.ts` (new; ignored by git, needs `git add -f`)
- `frontend/e2e/sah32-world.ts` (new, the shared mock; same)
- `frontend/e2e/sah31-world.ts`, `frontend/e2e/nursing.spec.ts`, `frontend/e2e/medicine.spec.ts` (extended; same)
- `docs/test-plans/SAH-32-e2e.md` (this file)
