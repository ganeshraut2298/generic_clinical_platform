# SAH-40 · A refused refresh clears clinical data on the Emergency queue and the Pharmacist check
Mockup screen: IPD → Emergency (queue, mockup `ipEr`) and IPD → Safety (panel "Pharmacist check" and tile "Pharmacist checks", mockup `ipSafety`, `pendRow`). The mockup shows neither screen in a refused state (OQ-40-1); the refusal is the pattern built by audit fix G-2 for IPD → Admitted and IPD → Safety.
Module: ipd

## 0. Scope in one view (8 Nov 2026, business analyst)
Read against `frontend/src/app/features/ipd` (`emergency/emergency.component.ts`, `pharmacist/pharmacist.store.ts`, `ipd.page.ts` `refuse()`, `safety/safety.component.ts` `reloadFailed()`), `docs/api/ipd.yaml`, `docs/reviews/SAH-35-36-38-audit-security.md` finding 5 and `docs/reviews/QA_REPORT_SAH-35-36-38.md` G-2.

| Screen and read | State today on 403 | In SAH-40? |
| --- | --- | --- |
| Admitted list reload (`ipd.page.ts`) | Cleared, refusal or lock page (G-2) | No, done |
| Safety board reload (`safety.component.ts`) | Cleared, refusal (G-2) | No, done |
| Emergency queue, 60-second reload (`emergency.component.ts` `refresh()`) | Keeps the queue, "Could not refresh the queue..." | **Yes** (story 1) |
| Pharmacist list reload (`pharmacist.store.ts` `load()` after the first read) | Keeps the rows, "Could not refresh" | **Yes** (story 2) |
| Pharmacist counts poll (`pollSummary()`) | 403 `forbidden` ignored (no notice, rows stay); 403 `module-off` locks the page but the rows stay in memory | **Yes** (story 2) |
| Pharmacist "Show more orders" (`loadMore()`) | Shows "We could not load more orders. Try again." and keeps the rows | **Yes** (story 2) |
| First load of either screen | Already shows the error notice or the lock page | No, unchanged. Exception D-2 (9 Nov 2026): a first-load Emergency 403 `module-off` locks the whole page (see status log) |
| Emergency menu badge (`GET /emergency/summary`, counts only) | Not touched | No (OQ-40-6) |

A search of the frontend for `refreshFailed.set(true)` finds only these four places (two already fixed by G-2, two in this story), so no other screen has the same pattern.

API check: `docs/api/ipd.yaml` already lists 403 `forbidden` and 403 `module-off` on `GET /emergency`, `GET /emergency/summary`, `GET /pharmacist-checks` and `GET /pharmacist-checks/summary`. No API, contract or database change.

## 1. Stories

### SAH-40-1 · As an owner, front desk, nurse or doctor with the Emergency queue open, I want the queue to disappear when the server no longer lets me see it, so that casualty names and complaints do not stay on a screen I may no longer read
Priority: Must

### SAH-40-2 · As a pharmacist or owner with the Pharmacist check open, I want the orders list to disappear when the server no longer lets me see it, so that patient names, UHIDs and allergy text do not stay on a screen I may no longer read, and I am not told "No orders waiting" when I simply cannot see the list
Priority: Must

## Acceptance criteria

### SAH-40-1 · Emergency queue
1. Given the Emergency queue is on screen for OWNER, FRONT_DESK, NURSE or DOCTOR, when the 60-second reload answers 403 `forbidden` (role changed, clinic membership removed), then the whole queue (the cards, the quick registration form, the queue note and the counts it last sent to the page) leaves the screen at once, and the same error notice as the first load shows: the server's message, otherwise "We could not load the emergency queue. Try again.", with the button **Try again** (G-2 pattern, `role="alert"`).
2. Given the same reload answers 403 `module-off` (IPD switched off), then the queue is cleared and the existing IPD lock page shows for the whole page: "In-patient care (IPD) is not on for your clinic", "Switch it on to admit patients, take deposits and see who is in a bed.", and, for OWNER only, the link **See plan and modules**. Nothing of the page behind it stays on screen.
3. Given the reload fails with a network error, a timeout or a 5xx answer, then nothing is cleared: the last queue stays with the existing notice "Could not refresh the queue. Last updated <time> IST", as today. Given an offline browser, the same.
4. Given a refusal (AC 1 or 2), then no patient name, age, complaint or triage level remains in the page, its accessible tree or its memory; the page header counts and the Emergency tile no longer follow the cleared queue's last numbers (the page shows what its own Admitted-list answer says, or "—" when it has none).
5. Given the queue was empty ("No one in emergency right now.") and the reload answers 403, then the refusal shows (AC 1 or 2), never the empty-queue text.
6. Given the user taps an action (Doctor has seen, Admit, Treat and release, Refer out, a triage level, or Register) and the server answers 403, then the message of the action shows as it does today (toast), and the queue is read again; that read follows AC 1 to 3. Given an action answered 404 or 409 `case-closed`, the queue is read again and follows the same rules.
7. Given the refusal notice shows, when the user taps **Try again**, then the screen loads as the first load does ("Loading the emergency queue"). Given access is back, the queue shows normally with its buttons from the server's flags; given not, the refusal shows again.
8. Given two reads overlap (a timer reload and a reload after an action), when a refusal arrives, then an older successful answer that arrives after it does not bring the queue back; only **Try again** (or leaving and reopening the page) does.
9. Given a refusal, then the notice is announced to screen readers; if the keyboard focus was on a control that is now gone, focus moves to the notice (or the page heading) and not to the top of the document.
10. Given the browser tab is hidden or the page is left, then no new behaviour: the timer stops with the component as today.
11. Given a refusal, then no patient name, complaint or id is written to the console, logs, URL or browser storage by this behaviour. Given the clinic, then it is never sent by the browser; another clinic's cases are never shown (RLS on the server).

Notes for design
- Change is in `emergency.component.ts` (the `error` handler of `refresh()`), with the page's `locked` signal set for `module-off` (a small output from the component to `ipd.page.ts`, or reuse of the page's `refuse()`); the page also drops the counts the component emitted. Follow `ipd.page.ts` `refuse()` and `safety.component.ts` `reloadFailed()`; reuse `isRefusal` and `isModuleOff`.
- `refresh()` is also called by `fail()` (409, 404), by the registration form on conflict, and after Admit, so one change covers AC 6.
- A 403 on the direct action itself stays a toast (OQ-40-2): the action's 403 can be a narrower role (for example a nurse changed to front desk can still read the queue), so the follow-up read decides what to clear.

### SAH-40-2 · Pharmacist check
12. Given the panel "Pharmacist check" is on screen for PHARMACIST or OWNER, when the list reload answers 403 `forbidden` (role changed, membership removed), then all rows, the as-of time, the verifier name, the paging cursor and the waiting number leave the screen and the page's memory, and the panel shows the same error notice as its first load: the server's message, otherwise "We could not load the pharmacist checks. Try again.", with **Try again** (`role="alert"`).
13. Given the same reload answers 403 `module-off`, then the same is cleared and the existing IPD lock page shows for the whole page (as the Admitted list does).
14. Given the reload fails with a network error, a timeout or a 5xx answer, then the rows stay with the existing "Could not refresh" notice (the panel's stale text), as today.
15. Given the 60-second counts poll answers 403 `forbidden`, then it is treated as in AC 12 (the rows are cleared and the error notice shows), even though the poll itself carries no names. Given it answers 403 `module-off`, then AC 13. Given the poll fails with a network error or 5xx, then it is ignored as today: no notice, no clearing.
16. Given "Show more orders" answers 403 (`forbidden` or `module-off`), then it is treated as AC 12 or 13. Given it fails with a network error or 5xx, then the rows stay and "We could not load more orders. Try again." shows, as today.
17. Given a refusal, then the screen never shows "No orders waiting." (the empty-queue text); that text means nothing is waiting and shows only after a successful empty answer.
18. Given a refusal, then the number on the Safety tab and the tile "Pharmacist checks" no longer show the old waiting count and do not show "0": the tab number is hidden and the tile shows "—" (OQ-40-4).
19. Given a pharmacist Verify or Reject sheet is open, when a background read is refused (the counts poll, the list reload, "Show more orders", or the list read that follows the sheet's own 409), then AC 12 or 13 applies and the open sheet closes at once; the panel's refusal notice shows and takes focus (AC 23); and no patient name stays on screen. Given instead that the sheet's own submit (verify or reject) is answered 403, then the sheet shows its own message first ("nothing more can be done from this sheet"), as today; the list read that follows then decides whether to clear (OQ-40-2), and a refusal on that read closes the sheet as above. (Changed 9 Nov 2026 after security review finding 2 and code review finding 2: before, the sheet kept its own message when the read after its 409 was refused, which left a patient name on screen.)
20. Given an OWNER whose Admitted list reload is refused (G-2, the Safety tab goes away), then the pharmacist rows held for that page are dropped from memory as well, not only hidden. Given the owner then taps **Try again** and the Admitted list loads, then the pharmacist part of the Safety tab number comes back at once (the page reads the pharmacist counts again, which carry no names), not only when the panel is opened; the rows stay dropped and are read again when the panel opens. (Changed 9 Nov 2026 after code review finding 8: before, the tab number showed only the escalations until the panel was opened.)
21. Given the refusal notice shows, when the user taps **Try again**, then the panel loads as a first load ("Loading the pharmacist checks"); given access is back, the list shows normally, given not, the refusal shows again.
22. Given a PHARMACIST whose Safety tab shows only this panel and tile, when a refusal happens, then the user sees the refusal (or the lock page), not an empty tab.
23. Given a refusal, then the notice is announced to screen readers, and focus handling is as in AC 9.
24. Given a refusal, then no patient name, UHID, age, allergy text, drug or reason is written to the console, logs, URL or browser storage by this behaviour; the clinic is never sent by the browser; another clinic's orders are never shown.
25. Given a role that never had this panel (DOCTOR, NURSE, FRONT_DESK, BILLING, VISITING_DOCTOR), then nothing changes: the first load already refuses (403) as today.

Notes for design
- Change is in `pharmacist.store.ts`: `load()` (error handler when not the first read), `loadMore()` and `pollSummary()`; add one private "refuse" that empties `meta`, `items`, `nextCursor`, `waiting`, the last summary and sets `loadError` (or `moduleOff`). The panel and the page already read these signals. `ipd.page.ts` shows the tab number from `pharmacist.waiting()`: with `null` it must not show 0 (OQ-40-4).
- Today `isModuleOff` is the only 403 the store knows; add the general 403 test (as `isRefusal` in `ipd.page.ts`).

## 2. Out of scope
The menu badge poll (OQ-40-6), a 401, any API change, the Admitted list and the Safety board (G-2), and new wording. Requirement: `docs/requirements/SAH-40.md`.

## 3. Open questions
"Needs human" marks those the team cannot decide. For the others, development proceeds on the recommended default and the human confirms later.

- **OQ-40-1 (proceed; wording).** The mockup shows no refused state for the Emergency queue or the Pharmacist check, and the only lock page in the mockup (`lockpage`, "In-patients is not switched on for this clinic ... 403 MODULE_NOT_ENABLED") is worded differently from the built lock page ("In-patient care (IPD) is not on for your clinic", from the SAH-23 page). Recommended: reuse the built lock page and the first-load error notices of each screen, and the server's own message for `forbidden`; invent no wording. The human may want a plain-language line for a withdrawn role (for example "You no longer have access to this screen"); that would be a new text and needs the human.
- **OQ-40-2 (proceed).** A 403 on a user action (verify, reject, seen, release, refer, admit, register) stays the toast or sheet message it is today, and the screen then reads the list again, which decides whether to clear. Recommended default; clearing at once on an action's 403 could be wrong for a role that may still read the screen.
- **OQ-40-3 (proceed).** After a refusal, should the 60-second timers and the counts poll keep asking? Recommended: the screen's own timer stops until **Try again** (it avoids a refused call every minute and 403 noise in the logs); if the Admitted list of G-2 keeps polling, follow whatever it does so the page behaves the same. The developer states which in the handoff.
- **OQ-40-4 (proceed).** After a pharmacist refusal the Safety tab number and tile need a value. Recommended: hide the tab number and show "—" in the tile (the page already uses "—" for an unknown count), never "0".
- **OQ-40-5 (proceed).** The counts-only summary poll of the pharmacist panel is treated as a refusal on 403 `forbidden` (it is ignored today). Recommended because it is the call that notices a removed role while nothing else is asking.
- **OQ-40-6 (proceed).** The Emergency menu badge (`EmergencyBadgeStore`, `GET /emergency/summary`) and the menu item itself follow `GET /platform/me`, carry counts and no names, and are not changed here. Recommended: leave them; a later change can hide the menu item when the summary answers 403.
- Shared and still open (not caused by this story): OQ-30-3 and OQ-33-2 (ward scoping), the go-live conditions of DEC-017 and DEC-018.

## 4. Technical notes (for the architect; not a design)
Frontend only. No new endpoint, field or table. The architect confirms there is nothing to do and passes on to the Angular developer; no ADR is needed. The api contract lines to confirm are `docs/api/ipd.yaml` lines 494 to 530 (emergency) and 1150 to 1203 (pharmacist checks).

## 5. API (`/api/v1/ipd`)
| Method | Path | Role | Change |
| --- | --- | --- | --- |
| GET | `/emergency` | OWNER, FRONT_DESK, NURSE, DOCTOR | none (403 `forbidden`, `module-off` already listed) |
| GET | `/emergency/summary` | same | none |
| GET | `/pharmacist-checks` | OWNER, PHARMACIST | none |
| GET | `/pharmacist-checks/summary` | OWNER, PHARMACIST | none |

## 6. Tasks
### Tasks — Backend
None.
### Tasks — Frontend
Emergency component and pharmacist store: 403 on a background read clears the data and shows the refusal or the lock page; transient errors keep stale data; page counts and tab number follow; unit tests for each AC (403 `forbidden`, 403 `module-off`, network error, 5xx, overlapping reads, empty queue, Try again, "Show more orders", counts poll). Browser check by QA (qa-engineer): remove the role in a second session, wait for the reload, see the queue gone.

## 7. Status log
- 8 Nov 2026 · drafted by the business-analyst from the audit finding 5, G-2 and the code of the four screens. Awaiting human approval of `stories`.
- 9 Nov 2026 · aligned by the business-analyst with the review fix pass, citing `docs/reviews/SAH-40-security.md` finding 2, `docs/reviews/SAH-40-code.md` findings 2 and 8, and `docs/test-plans/SAH-40.md` "Review fix pass" (C-1, C-2, D-2). No new wording for any screen; no API change.
  - AC 19 changed (C-1): a background refusal (counts poll, list reload, Show more, or the read after the sheet's own 409) closes the open Verify/Reject sheet at once; the panel's refusal notice shows and is focused; no patient name stays on screen. The sheet's own submit answered 403 still shows its message in the sheet first.
  - AC 20 changed (C-2): after an Admitted-list refusal and Try again, the pharmacist part of the Safety tab number comes back at once; rows are still dropped from memory and re-read when the panel opens.
  - Added behaviour (no AC number change; follows the same rule as AC 12, 13 and the G-2 pattern): open sheets close on a refusal or page lock. This covers the Emergency Admit sheet, the pharmacist Verify/Reject sheet, the Safety Response sheet, the Give sheet, and the page sheets Admission, Nursing, EMR and Admit.
  - Success toasts are dismissed when a refusal or page lock happens, so no patient name stays in a toast. An action's own 403 toast on Emergency stays (AC 6, OQ-40-2).
  - The Safety tab number is hidden after a Safety refusal (the same rule as AC 18 and OQ-40-4: never a stale number, never "0").
  - D-2 (a first-load Emergency 403 `module-off` locks the whole page, instead of the notice with Try again that the scope table first listed) accepted by the tech lead as the default under DEC-023. The human may override; if so, only that test and the scope table row change.
  - Unchanged and counts only (no names): the Emergency menu badge keeps its old count after a refusal on the menu route (OQ-40-6); a Safety `module-off` shows the notice, not the lock page (code review 6, for the human).
