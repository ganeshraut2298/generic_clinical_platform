# Test plan · BUG-emr-finish-no-call · "Save draft" and "Complete visit" send no request; no move to the next step; bill not checked

Owner: qa-engineer · Date: 19 Nov 2026 · Branch: `feature/SAH-39-home-kinetic-background` (the bug handoff explains why this branch is used)
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorGeneral "Save draft", "Complete visit" and "Check before completing")
Stories: SAH-51-1 (check before completing, sign), SAH-51-3 (draft save, autosave). ADR-025 (sign outcome). DEC-054 (live OPD journey, database-busy, statement timeout).
Sources: `docs/handoffs/BUG-emr-finish-no-call.md` (tech lead reading, the angular-developer and java-developer stage log), the angular fix in the working tree (`emr-record.store.ts` `save()`/`lastStop`, `emr-finish.flow.ts` `begin()`/`saveThenCheck()`, `emr.api.ts` 30 s `answerInTime()`, `emr-record.page.ts` `showSaveStop()`/`jumpToField()`), `docs/api/opd.yaml` (draft `saveKind`, sign `Idempotency-Key`), `Problems.busy()` (503 `database-busy` text), DEC-083 and the SAH-82-5 story (header height and scroll position), DEC-084.

## 1. Scope and status

- **Offline part (this pass): DONE, run.** These are mock-backed Playwright specs. The browser runs the real Angular build, and every `/api/v1` call is answered by `page.route()`. They prove what the EMR screen sends and what it shows for each answer.
- **Live part: pending (live platform access, human).** In this session `curl` is denied, and `SAHAJ_OWNER_MOBILE` is not set for `frontend/e2e/live/opd-journey.mjs`. Section 4 lists the checks still owed.
- **Not covered here:** the sign transaction, idempotency on the server, roles, row-level security and the bill lines. Those belong to the backend tests (`VisitBillAfterEmrSignTest`, `EmrSignServiceTest`, `EmrSignOpdIT`, `EmrSignRacesIT`) and to the live checklist.

## 2. Where the tests are

| File | What it covers |
| --- | --- |
| `frontend/e2e/emr-finish-stops.spec.ts` (new) | Cases O-1 to O-8 below. It has its own mock server, which keeps the revision rule of the real one (a stale revision is answered 409). It records every draft, check and sign call with its body and `Idempotency-Key`. Per-call plans can answer a problem, answer slowly (a gate the test opens) or never answer. The 30 s client timeout (`EMR_REQUEST_TIMEOUT_MS`) is driven with `page.clock`, so no test really waits 30 s. |
| `frontend/e2e/emr-finish.spec.ts` (unchanged) | The SAH-51/67/68 finish dialogs. Run once here as a regression check (section 5). |

Technique notes:
1. The build was made into a scratch folder with `ng build --output-path <scratch>` and served with `E2E_DIST=<scratch>/browser` and `E2E_PORT=4391`. That left `frontend/dist` (angular-developer) and the default port 4300 alone.
2. At 360 px the sticky EMR header covers the section toggles for a pointer (open story SAH-82-5). So the spec presses section buttons with the keyboard (focus, Enter), and the stop paths stay testable on the phone. Whether the field jumped to ends up behind the header is checked on its own (O-4g).
3. Snack bars are matched by their text, because a leaving snack bar can still be in the DOM. In O-5 the first one is marked old, to prove that Complete opened a new one.

## 3. Offline cases (one test per case, run on `desktop-1440` and `phone-360`)

| # | Case | Test (`emr-finish-stops.spec.ts`) | desktop-1440 | phone-360 |
| --- | --- | --- | --- | --- |
| O-1 | Save draft sends `PUT …/records/{id}/draft` with the typed section and the revision, and says "Draft saved". The button is back to "Save draft" and enabled. The body has no `saveKind` (MANUAL is the contract default). | O-1 Save draft sends PUT …/draft … | PASS | PASS |
| O-1b | Save draft with nothing changed still sends a MANUAL save with `sections: []` (SAH-51-3 audit entry) | O-1b Save draft with nothing changed … | PASS | PASS |
| O-2 | Complete with an unsaved entry: one AUTO save, then `finish-check`, then "Check before completing" (nothing signed yet), then Confirm: one sign with an `Idempotency-Key` matching `^[A-Za-z0-9_-]{8,64}$` and body `{revision: 4, choices: {}}` (the revision the save answered). Back on `/app/queue` with "Visit completed · calling next patient". | O-2 Complete saves the unsaved entry … | PASS | PASS |
| O-3 | Complete pressed during a slow autosave (the 30 s tick; its PUT held open, button reads "Saving"). Nothing more is sent while the save runs. When the save answers, the check runs and the dialog opens. Totals: one save, one check, one sign. | O-3 Complete pressed while a slow autosave … | PASS | PASS |
| O-4a | Save draft gets a 400 `fieldErrors` on a field in a **collapsed** section. Snack bar "Fix the fields marked in red, then save again."; the section opens (`aria-expanded=true`); focus is on the field; the field has `aria-invalid=true` and its message below it; Save draft is enabled again | O-4a Save draft: a 400 on a field of a collapsed section … | PASS | PASS |
| O-4b | Complete gets the same 400 on its save before the check. Same message and focus; no check, no sign, no dialog. Complete can be pressed again and, after a fix, goes through to the Queue. | O-4b Complete: the same 400 … | PASS | PASS |
| O-4g | After the jump the field marked in red is in view: a pointer at its centre hits the field, not the sticky header, the footer or the snack bar | O-4g after the jump the field marked in red is in view … | PASS | **FAIL (D-2)** |
| O-4c | GM vitals (`VITALS_LOG`): a 400 on `sections[0].values.specialty.entries[0].pulse` with the section collapsed. Snack bar message; the section opens; focus lands in the section card (the editor has no per-field id) | O-4c GM vitals (VITALS_LOG): … opens the collapsed section … | PASS | PASS |
| O-4d | GM vitals (`VITALS_LOG`): the refused value is drawn in red with the server's message | O-4d GM vitals (VITALS_LOG): the value the server refused is drawn in red … | **FAIL (D-1)** | **FAIL (D-1)** |
| O-4e | GM general examination (`EXAM_GENERAL`): a 400 on `sections[0].values.specialty.systems.cvs.text` with the section collapsed. Snack bar message; the section opens; focus lands in the section card | O-4e GM general examination (EXAM_GENERAL): … opens the collapsed section … | PASS | PASS |
| O-4f | GM general examination (`EXAM_GENERAL`): the refused finding is drawn in red with the server's message | O-4f GM general examination (EXAM_GENERAL): the finding the server refused is drawn in red … | **FAIL (D-1)** | **FAIL (D-1)** |
| O-5 | A 409 `emr-record-changed` on Save draft: a snack bar with "This EMR was changed elsewhere. Reload to see the latest." and a **Reload** action. Complete with the unsaved entry then shows a new snack bar with the same text and Reload, and sends no save, check or sign (before the fix it did nothing). Reload in the snack bar reads the record again (one more GET), the banner goes, and Complete then signs with the new revision 9. | O-5 a 409 on Save draft … | PASS | PASS |
| O-6a | A 503 `database-busy` on Save draft shows the server's own words ("This is taking longer than usual and was stopped. Nothing was saved by this attempt. Wait a moment and try again."). Save draft again goes through, with the same entry. | O-6a a 503 database-busy on Save draft … | PASS | PASS |
| O-6b | A 503 `database-busy` on the sign shows the server's words in the dialog. Try again sends the same `Idempotency-Key` and body and lands on the Queue. | O-6b a 503 database-busy on the sign … | PASS | PASS |
| O-7a | Save draft with no answer: still "Saving" at 29 s (not cut short). Just after 30 s the snack bar says "We could not reach the server. Try again." The button is "Save draft" and enabled; the next press saves. | O-7a Save draft with no answer for 30 s … | PASS | PASS |
| O-7b | Complete when `finish-check` gives no answer for 30 s: "We could not reach the server. Try again.", no dialog, no sign, Complete enabled. The next press opens the dialog. | O-7b Complete when the check does not answer … | PASS | PASS |
| O-8 | A sign with no answer for 30 s: "We could not reach the server. Try again." in the dialog. Try again sends the **same** `Idempotency-Key` and body, signs and lands on the Queue. | O-8 a sign with no answer for 30 s … | PASS | PASS |

Totals per run: 34 tests (17 × 2 projects), **29 PASS, 5 FAIL**. The same tests failed in all three runs.

### Coverage table (the asks of the bug → tests)

| Ask | Tests | Result |
| --- | --- | --- |
| Save draft fires `PUT …/draft` and shows "Draft saved" | O-1, O-1b | PASS |
| Complete → finish-check → "Check before completing" → sign with an `Idempotency-Key` → back to the Queue with the after-sign message | O-2 | PASS |
| Complete during a slow autosave waits, then goes through (one sign) | O-3 | PASS |
| 400 field problem in a collapsed section → snack bar, section opens, focus on the field | O-4a, O-4b (plain fields) | PASS |
| … and the field is in view on the phone | O-4g | desktop PASS; phone **FAIL (D-2)** |
| … GM vitals (`VITALS_LOG`) and general examination (`EXAM_GENERAL`) editors: message, open, focus | O-4c, O-4e | PASS |
| … the editor field drawn in red | O-4d, O-4f | **FAIL (D-1)**: not drawn in red |
| 409 conflict → snack bar with Reload | O-5 | PASS |
| 503 database-busy → the server's text, retry succeeds | O-6a (draft), O-6b (sign) | PASS |
| No answer for 30 s → "We could not reach the server. Try again." (clock-driven, no real wait) | O-7a (draft), O-7b (check), O-8 (sign) | PASS |
| Sign timeout, then Confirm again → the same `Idempotency-Key` | O-8 | PASS |

## 4. Live checklist: pending (live platform access, human)

Run with `frontend/e2e/live/opd-journey.mjs` (DEC-054 rules) for **General Medicine and Dental**, once a human gives this session one of these: a permission for local `curl`, or `SAHAJ_OWNER_MOBILE` in the session environment plus a restart. Each line below is **pending: live platform access (human)**.

Backend (the java-developer's 7 points):

| # | Check | Status |
| --- | --- | --- |
| L-J1 | `POST …/records/{id}/sign` answers 200, and `outcome.stage.to` is `COUNSEL` (if the counsel step is on) or `BILLING`, never `DONE` | pending: live platform access (human) |
| L-J2 | The today list (`GET /api/v1/opd/visits/today`) shows the visit at that next stage, with the EMR signed | pending: live platform access (human) |
| L-J3 | The bill has the right lines and amounts: Consultation from `visit.fee_paise` plus the "Bill today" services from the price list. Dental plan lines appear once per key. | pending: live platform access (human) |
| L-J4 | The billing counter lists the visit with that bill and balance | pending: live platform access (human) |
| L-J5 | A replay of the sign (same key, same body) answers the first result and adds no bill line; a second sign adds no line either | pending: live platform access (human) |
| L-J6 | The same record ID from another clinic answers 404 (row-level security) | pending: live platform access (human) |
| L-J7 | A ₹ 0 visit (free follow-up, no services) stays in Billing. Known gap, DEC-084 answer 1 → SAH-84 "Close bill". Record it, do not fail it. | pending: live platform access (human) |

UI (the angular-developer's fix, on the real dev platform at `http://localhost:4200`):

| # | Check | Status |
| --- | --- | --- |
| L-U1 | GM: type in a section, Save draft → network shows `PUT …/draft` 200 and "Draft saved" | pending: live platform access (human) |
| L-U2 | GM: Complete visit → `PUT …/draft` (AUTO, if anything is unsaved), `POST …/finish-check`, "Check before completing", Confirm → `POST …/sign` with an `Idempotency-Key`, then the Queue with "Visit completed · calling next patient". The card is at the next stage. | pending: live platform access (human) |
| L-U3 | Dental: the same, with "Send to billing?", the next sitting and "Sent to billing …" | pending: live platform access (human) |
| L-U4 | Complete pressed during an autosave (type, wait about 30 s, press Complete while the footer says "Saving"): exactly one sign | pending: live platform access (human) |
| L-U5 | A record changed in a second tab: Save draft and Complete each say "This EMR was changed elsewhere…" with Reload; nothing is silent | pending: live platform access (human) |
| L-U6 | No button stays on "Saving" or stays disabled after any of the above | pending: live platform access (human) |
| L-U7 | The bill at the counter matches L-J3 (amounts shown as ₹ 1,25,000 style) | pending: live platform access (human) |

## 5. Regression run of `emr-finish.spec.ts` (desktop-1440, same build, once)

40 tests: **34 PASS, 6 FAIL.** This spec was written in the SAH-51-68 pass and marked "not run" there, so this is its first run. Five failures look older than this fix and unrelated to it. One is a direct effect of the new 30 s client timeout.

| Test | Failure | Linked to this fix? |
| --- | --- | --- |
| Phase 5 review fixes › the 30-second tick that fires while the sign is still in flight … | The test runs the clock 31 s with the sign held. The new 30 s sign timeout fires first, so the screen never reaches the Queue. | **Yes.** See D-3 |
| SAH-51 › error path: 409 held by another doctor … turns read-only | `[data-act="complete"]` not found: the footer hides the buttons when held, while the test expects a disabled button | No (SAH-51-4 rendering vs. test expectation) |
| Dental: "Send to billing?" … | After the sign, "Sent to billing…" is not on the Queue | No (after-sign message) |
| Physiotherapy: "Complete session 2?" … | After the sign, "Session 2 done…" is not on the Queue | No (after-sign message) |
| Phase 5 › FRONT_DESK reprints an Obstetrics visit … trimmed | "Fever" is not in the reprint | No (reprint) |
| Phase 5 › PHARMACIST reprints an Obstetrics visit … trimmed | same | No (reprint) |

None of these was changed in this pass. The five unrelated ones are left for triage in the SAH-51-68 plan.

## 6. Defects

| ID | Severity | Owner | Defect | Steps | Evidence |
| --- | --- | --- | --- | --- | --- |
| D-1 | Major | angular-developer | Field problems from the server are not drawn in the specialty editors `VITALS_LOG` and `EXAM_GENERAL`. The snack bar says "Fix the fields marked in red" while nothing in the card is red. The card pill still says "Done" and the footer says "Ready to complete". The editors (`specialty/gm/gm-editors.ts`, base `shared/sp-editor.ts`) never read `store.problems()`. `mapFieldErrors` keeps the key `specialty.entries[0].pulse` / `specialty.systems.cvs.text`, and no editor control has an id `f-<section>-<that key>`, so `jumpToField` focuses only the card. | GM record with sections `vitals` (VITALS_LOG) and `exam` (EXAM_GENERAL). Record the six vitals (or tap "All absent"), then Save draft. The server answers 400 `fieldErrors: [{field: "sections[0].values.specialty.entries[0].pulse", ...}]`. Nothing is red, and the message is not shown in the card. | O-4d, O-4f (both projects, 3/3 runs) |
| D-2 | Major (phone) | angular-developer (with SAH-82-5) | At 360 px, after the jump to the field marked in red, the field sits behind the sticky EMR header. The doctor sees the past-visits panel and the snack bar, not the field. `ScrollTo.section()` uses `scrollIntoView()` with no offset for the sticky header. Same cause as the SAH-82-5 criterion ("the 'scroll to section' position start[s] just under the real header and never behind it"). | 360 × 740. Type in the second section, collapse it, scroll to the top, Save draft → 400 on that field. The field is focused but hidden under the header. | O-4g phone-360 (3/3 runs) |
| D-3 | Minor (decision) | angular-developer | The sign's 30 s client timeout and the 30 s autosave tick now overlap. After a sign timeout `signInFlight` is false again, so the next tick renews the hold. If the sign did finish on the server, that renewal can get 409 and show "another doctor is editing" (or signed) while the dialog says "We could not reach the server". The old e2e that guards this (`emr-finish.spec.ts`, the 30-second tick … late 409 held) now fails, because the timeout fires before the gate opens. Decide whether the tick should stay quiet while a timed-out sign is unresolved (until Confirm again or Back). The e2e will then be updated to match. | `emr-finish.spec.ts` › "the 30-second tick that fires while the sign is still in flight …" | regression run (section 5) |
| O-a | Low (note) | angular-developer | At 360 px the 8-second snack bar covers Save draft and Complete visit in the footer (screenshot of O-4g). Close is reachable. | Any stop snack bar on a phone | O-4g phone screenshot |

## 7. Runs

| Run | Command | Result |
| --- | --- | --- |
| Build | `npx ng build --output-path <scratchpad>/build` (in `frontend/`) | OK (one pre-existing budget warning, form-builder.page.scss) |
| Type check | `tsc --ignoreConfig --noEmit --strict … emr-finish-stops.spec.ts` | clean |
| 1 (21:37 IST) | `E2E_PORT=4391 E2E_DIST=<scratch>/browser npx playwright test emr-finish-stops.spec.ts --project=desktop-1440 --project=phone-360` (in `frontend/e2e/`) | 29 passed, 5 failed (O-4d ×2, O-4f ×2, O-4g phone) |
| 2 (21:38 IST) | same | 29 passed, 5 failed (same five) |
| 3 (21:39 IST) | same | 29 passed, 5 failed (same five) |
| Regression | same server, `emr-finish.spec.ts --project=desktop-1440` | 34 passed, 6 failed (section 5) |

`node scripts/validate.mjs all` was **not** run. The tech lead asked for spec-only runs because `.claude/locks/frontend.lock` (angular-developer `a401c00d01688a8e4`, 21:19 IST) and `backend.lock` (java-developer `af873584f57f9b9b3`, 21:06 IST) look stale but are within the hook's 2-hour window. `validate frontend` is also known to fail on the DEC-002 `--browsers=ChromeHeadless` flag (human-owned script). The locks were not touched.
