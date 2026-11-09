# SAH-40 · Refused refresh clears clinical data on Emergency and Pharmacist screens: design note

Story: SAH-40 (`docs/stories/SAH-40.md`, AC 1 to 25; rules 1 to 8 in `docs/requirements/SAH-40.md`). Follow-up of audit fix G-2 (DEC-025) and `docs/reviews/SAH-35-36-38-audit-security.md` finding 5.
Mockup screens: IPD → Emergency (`ipEr`); IPD → Safety, panel "Pharmacist check" and tile "Pharmacist checks" (`ipSafety`, `pendRow`). No refused state in the mockup (OQ-40-1): the built lock page and the first-load notices are reused.
Module: ipd (frontend only). **No API, no table, no contract change.** Nothing for db-engineer or java-developer.
**No ADR.** No boundary, dependency or cross-cutting rule changes: the rule is the G-2 rule, applied to two more reads inside the same IPD page.
Open questions: recommended defaults taken (DEC-023). OQ-40-1 stays open with the human only if a new line of wording is wanted.

Facts this note relies on (checked in the code on 8 Nov 2026):
- `ipd.page.ts` has private `isModuleOff()` and `isRefusal()` (lines 66 to 73), `refuse()` (lines 311 to 321) and a `switchMap` reload queue (lines 222 to 249).
- `pharmacist.store.ts` has a private copy of `isModuleOff()` (line 151) and no general 403 test. `emr-sheet.component.ts` has a third private `isModuleOff()`; `safety.component.ts` tests `status === 403` inline.
- `emergency.component.ts` `refresh()` sets `refreshFailed` on every error (lines 118 to 133); its timer is a plain `setInterval` (line 98), stopped on destroy.
- `PharmacistTileComponent` shows `store.waiting() ?? 0` (line 33), so `null` shows as "0" today.
- The panel template tests `loadError` before `meta`, so a cleared store with `loadError` set can never fall through to "No orders waiting." (`pharmacist-panel.component.html` lines 7 to 24).

---

## 1. Contract check (confirmed, no change)

| Operation | `docs/api/ipd.yaml` | 403 types |
| --- | --- | --- |
| GET `/emergency` | lines 495 to 517 | description: "others 403 forbidden ... module Off 403 module-off"; response `'403': Problem` |
| GET `/emergency/summary` | lines 518 to 531 | description: "others 403 forbidden. Module Off 403 module-off"; response `'403': Problem` |
| GET `/pharmacist-checks` | lines 1150 to 1186 | response `'403'` "forbidden (token role or membership role not OWNER or PHARMACIST) or module-off" |
| GET `/pharmacist-checks/summary` | lines 1187 to 1203 | description: "others 403 forbidden; module Off 403 module-off"; response `'403': Problem` |

All four already declare both problem types; the client only has to read `status` and `problem.type`. The story's line references (494 to 530) are one line off; the content is the same. The contract is not edited, so no new `contract` approval is needed for SAH-40.

## 2. The shared refusal rule

A background read of a screen (timer reload, reload after an action, counts poll, "Show more orders") ends in one of three ways:

| Answer | Meaning | What the screen does |
| --- | --- | --- |
| 403, `type` ends with `module-off` | IPD switched off | Clear the screen's patient data, then the whole page shows the existing IPD lock page (page `locked`). |
| 403, any other type (`forbidden`, ...) | This user may no longer read it | Clear the screen's patient data, then show the screen's first-load notice: `problemMessage(error, <screen LOAD_ERROR>)` with **Try again** (`role="alert"`). |
| Network error, timeout, offline (status 0), 5xx, anything else | Transient | Keep the last data with the existing "Could not refresh" notice. Nothing is cleared. |

- 401 never reaches these handlers as a refusal (app-wide interceptor). 400, 404 and 409 on a background read stay "transient" (no change).
- A 403 on a **user action** (seen, release, refer, level, register, admit options, verify, reject) stays a toast or sheet message (OQ-40-2). Only the read that follows the action applies this rule.
- "Clear" means the signals are set to `null` / `[]` (the data leaves memory), not that the template hides them.
- Nothing about the refusal is logged; no patient field is written to the console, URL or storage.

### Shared helper (recommended)

Create one small file `frontend/src/app/features/ipd/ipd-refusal.ts` (no new library):

```ts
export function isRefusal(error: unknown): boolean   // ApiError && status === 403
export function isModuleOff(error: unknown): boolean // isRefusal && problem.type ends with 'module-off'
```

- `ipd.page.ts` drops its two private functions and imports these; `pharmacist.store.ts` drops its private `isModuleOff()`; `emergency.component.ts` imports both. This avoids a third and fourth copy.
- `emr-sheet.component.ts` and `safety.component.ts` may switch to the helper in the same change (pure refactor, no behaviour change); not required by SAH-40.
- It stays inside `features/ipd` (not `core/`): other modules have their own lock pages and are not touched by this story.

## 3. Emergency queue (`emergency.component.ts`, story 1)

State added in the component: a `refused` flag and a private request generation counter `epoch`.

1. **Refresh error handler** (`refresh()`): `isRefusal(error)` → `refuse(error)`; otherwise `refreshFailed.set(true)` as today.
2. **`refuse(error)`** (private): `epoch++`; `refreshing = false`; `queue.set(null)`; `refreshFailed.set(false)`; `busy.set(false)`; `refused = true`. Then:
   - `module-off`: emit a new output `refused` (payload: the error) and do not set `loadError`; the page locks (section 5).
   - otherwise: `loadError.set(problemMessage(error, LOAD_ERROR))` and emit `refused` as well, so the page drops the counts this component sent.
   With `queue` null, the cards, the quick registration form (`queue.canRegister`, levels, bays) and the queue note leave the DOM; `note()` returns `''`. The empty-queue text cannot show because the template's `loadError` branch comes first (AC 5).
3. **AC 6 is covered by the same handler**: `fail()` (404, 409), the registration conflict, Admit and `onCaseClosed` all go through `refresh()`.
4. **First-load 403** (`load()` error): `forbidden` shows the same notice as before. Also set `refused = true`, and for `module-off` emit `refused`, so the first load and the reload lock the page the same way. This means a first-load `module-off` locks the whole page instead of the old notice (QA defect D-2); accepted by the tech lead as the default under DEC-023, the human may override.
5. **Try again** calls `load()`: it does `epoch++`, `refused = false`, `loadError.set(null)`, `loading.set(true)` ("Loading the emergency queue"). Success → `apply()`; 403 → refusal again.
6. **Badge**: `EmergencyBadgeStore.accept()` is not called on a refusal; the menu badge is out of scope (OQ-40-6).

### Stale-response ordering guard (AC 8)

The component does not keep its `act()`, `admit()` or `refresh()` subscriptions, so cancelling is not enough. Every request captures `const at = this.epoch` when it starts, and its `next` and `error` handlers return at once when `at !== this.epoch`. `refuse()` and `load()` increment `epoch`. Effect:
- a timer reload or an action answer that started before the refusal and arrives after it is dropped (the queue does not come back);
- an answer that started before **Try again** cannot overwrite the first load's answer;
- `onRegistered(queue)` (emitted by the child form) is ignored while `refused` is true.
`apply()` stays the single place that sets `queue`, `refreshFailed`, the badge and the `counts` output.

## 4. Pharmacist check (`pharmacist.store.ts`, story 2)

New store members:
- `readonly refused = signal(false)`
- `clear()` (public): unsubscribe `listSub`, `moreSub`, `summarySub`; `meta.set(null)`, `items.set([])`, `nextCursor.set(null)`, `waiting.set(null)`, `lastSummary = null`, `refreshFailed.set(false)`, `moreLoading.set(false)`, `moreError.set(null)`, `loading.set(false)`. No notice.
- `refuse(error)` (private): `clear()`; `refused.set(true)`; then `module-off` → `moduleOff.set(true)`, otherwise `loadError.set(problemMessage(error, CHECKS_LOAD_ERROR))`.

Where it is called:
| Read | Today | SAH-40 |
| --- | --- | --- |
| `load()` error, not first | `module-off` → `moduleOff` (rows kept); other → `refreshFailed` | `isRefusal` → `refuse(error)`; other → `refreshFailed` (unchanged) |
| `load()` error, first | `module-off` → `moduleOff`; other → `loadError` | `isRefusal` → `refuse(error)` (same visible result, plus `refused`); other → `loadError` (unchanged) |
| `loadMore()` error | `moreError`, rows kept | `isRefusal` → `refuse(error)`; other → `moreError` (unchanged) |
| `pollSummary()` error | `module-off` → `moduleOff` (rows kept); other ignored | `isRefusal` → `refuse(error)` (OQ-40-5); other ignored (unchanged) |

- **`load()`** sets `refused.set(false)` before its request, so the panel's **Try again** is a first load (`meta` is null → "Loading the pharmacist checks").
- **Ordering**: the store owns all three subscriptions, and `clear()` unsubscribes them, so an in-flight list, page or summary answer cannot land after a refusal. The only outside writer is `apply()` (verify and reject answers from the sheets): `apply()` returns early while `refused()` is true, so a late verify or reject answer cannot refill the cleared list.
- **Open Verify/Reject sheet (AC 19, as changed 9 Nov 2026).** A background refusal (counts poll, list reload, "Show more orders", or the list read that follows the sheet's own 409) closes the open sheet at once; the panel's refusal notice shows and takes focus; no patient name stays on screen. Only the sheet's own submit (verify or reject) answered 403 shows its message in the sheet first (OQ-40-2); the read that follows then decides, and a refusal on it closes the sheet as above. The panel's `onConflict` does not read while `refused()` is true (only **Try again** reads). See section 9.
- "No orders waiting." can show only when `meta` is set by a successful answer (AC 17); the template already tests `loadError` first.

## 5. Page interplay (`ipd.page.ts`)

1. **Emergency output.** Template: `<app-emergency ... (refused)="onEmergencyRefused($event)" />`. Handler: `liveCounts.set(null)` always; for `isModuleOff(error)` call the existing `refuse(error)`, which also drops `list`, `liveSafety` and sets `locked` (nothing of the page stays in memory, AC 2). After a `forbidden`, the tile and the Emergency tab number fall back to `list()?.emergency` (the page's own Admitted-list answer), or "—" when the page has none (AC 4), as `tileNumber` already does.
2. **Pharmacist `module-off`.** The existing `effect()` on `pharmacist.moduleOff()` sets only `locked`; it should call the page's `refuse()` path for module-off instead (or clear `list`, `liveCounts`, `liveSafety` and set `locked`), so the Admitted list does not stay in memory behind the lock page. Use `untracked()` for the writes if needed.
3. **Admitted-list refusal drops the pharmacist rows** (AC 20). The page's `refuse(error)` also calls `this.pharmacist.clear()`. The store is provided by the page, so without this the rows stay in memory until the page is left. (For `module-off` the store's own `moduleOff` is not needed; the page is already locked.)
4. **PHARMACIST** (AC 22): the page never loads the list for this role; the tab and the panel stay drawn, and the panel shows the store's notice. On `module-off` the page lock shows.
5. Focus (AC 9, AC 23): the notices are `role="alert"`. When the refusal removes the control that had focus (`document.activeElement` is `body` or was inside the cleared region), move focus to the notice (give it `tabindex="-1"`) or to the page heading, after the view updates (`afterNextRender`). As built, the test is the shared `focusIsLost()` helper (section 9).

## 6. Timers after a refusal (OQ-40-3)

What `ipd.page.ts` does after G-2 (read in the code): its two `startVisiblePoll` timers **keep ticking**, but a refused page **sends nothing**. `pollList()` reads only when `list() !== null && !locked()`; `pollSafety()` needs `safetyOn()`, which needs `list`; `pollPharmacist()` for OWNER needs `safetyOn()`. After `refuse()` the list is null, so every tick is a no-op until **Try again** (`load()`) brings the list back. On `module-off` the lock page replaces the tab panel, so the child components (and their timers) are destroyed.

SAH-40 follows the same rule: the timer keeps running, the tick does nothing while refused, and only **Try again** reads again.
- Emergency: `refresh()` returns early when `refused` is true (next to the existing `refreshing` and `loading()` guards). On `module-off` the page lock destroys the component and `clearInterval` runs.
- Pharmacist: `pollSummary()` and `loadMore()` return early when `refused()` is true. This guard must sit in the store, because the page calls `pollSummary()` for a PHARMACIST on every tick without any list check.
- The tab-hidden rule is unchanged (AC 10): `startVisiblePoll` skips hidden ticks; the Emergency `setInterval` stops with the component.

Observation for the tech lead (added to SAH-40 by the tech lead under DEC-023, see the handoff): `safety.component.ts` `refresh()` guards only on `loading()`, so after its G-2 refusal the Safety board keeps reading every 60 seconds and a later 200 silently brings the board back without **Try again**. That differs from the page and from the rule above (requirement rule 6). Suggest a follow-up story, or the human may add it to SAH-40.

## 7. Counts after a refusal (OQ-40-4)

- **Tile "Pharmacist checks"**: `count` becomes `number | null` (`store.waiting()`, no `?? 0`); the template shows "—" when null, and the warn class applies only when it is a number above 0. This also shows "—" (not "0") in the moment before the first read, which matches the page's use of "—" for an unknown count.
- **Safety tab number**: `safetyTabNumber` returns 0 (hidden, template already hides at 0) while `pharmacist.refused()` is true, for both roles. For OWNER the sum would otherwise show escalations plus late doses without the refused part, which reads as a valid total. For PHARMACIST `waiting` is null, which already sums to 0.
- **Emergency tile and tab number**: see section 5.1; never left at the cleared queue's last numbers.

## 8. Tests the developer adds (unit, Vitest)

Emergency component: reload 403 `forbidden` (queue null, notice text, `refused` emitted), 403 `module-off` (`refused` emitted, no notice), network error and 503 (queue kept, stale notice), empty queue then 403, an action 409 whose reload is 403, an older reload answer arriving after the refusal (ignored), timer tick while refused (no request), Try again success and Try again 403.
Pharmacist store: the four rows of the table in section 4 for 403 `forbidden`, 403 `module-off`, network and 5xx; `apply()` ignored while refused; poll and loadMore skipped while refused; `load()` resets `refused`; `clear()` empties every field.
Page: Emergency `module-off` locks and clears `list`; Emergency `forbidden` drops `liveCounts`; Admitted-list refusal calls `pharmacist.clear()`; pharmacist `module-off` clears `list`; tab number hidden and tile "—" while refused.

## 9. Final behaviour after the review fix pass (9 Nov 2026)

Sources: `docs/reviews/SAH-40-security.md` (findings 1 to 6), `docs/reviews/SAH-40-code.md` (findings 1 to 3, 5, 7, 8), `docs/stories/SAH-40.md` AC 19, AC 20 and the 9 Nov status log, `docs/test-plans/SAH-40.md` (C-1, C-2, D-2, F-1). Still frontend only; no API, table or contract change.

- **Open sheets close on a refusal or page lock.** Emergency Admit sheet; pharmacist Verify/Reject sheet (AC 19, section 4); Safety Response sheet and Give sheet (closed on refusal and on destroy); the page's own sheets Admission, Nursing, EMR and Admit, closed in the page's `lock()` and `refuse()`. The page closes only the dialog references it opened and tracked, not `MatDialog.closeAll()`, so dialogs owned by other features are not touched.
- **Toasts.** Success toasts shown just before a refusal or lock are dismissed, so no patient name stays in a toast. On Emergency, an action's own 403 toast stays (AC 6, OQ-40-2).
- **Safety tab number after a Safety refusal.** The Safety component's `refused` output makes the page drop `liveSafety` and hide the Safety tab number until **Try again** succeeds (same rule as AC 18: never a stale number, never "0"). A Safety `module-off` still shows the notice, not the lock page (G-2 behaviour; code review 6, left for the human).
- **Focus.** `ipd-refusal.ts` also exports `focusIsLost()`: focus counts as lost when it is on `body` (including after a **Try again** that is refused again), inside the cleared region, or when an overlay sheet of that screen is open (the sheet closes with the refusal). When lost, focus moves to the notice after the view updates (AC 9, AC 23; QA F-1 fix for the Emergency Admit sheet).
- **Reads while refused or locked.** The panel's `onConflict` does not read while refused; `ipd.page.ts` `refreshList()` returns early while `locked()`, so a late Admit save cannot refill the list behind the lock page. Emergency output emits are bound with `takeUntilDestroyed`, so nothing is emitted after destroy.
- **AC 20 (as changed).** `clearData()` resets `pharmacistSummarised`, so after an Admitted-list refusal and **Try again** the page reads the pharmacist counts (no names) at once and the pharmacist part of the Safety tab number comes back without waiting for the next poll; the rows stay dropped and are read again when the panel opens.
- **D-2.** A first-load Emergency 403 `module-off` locks the whole page (section 3.4). Accepted by the tech lead as the default under DEC-023; the human may override, in which case only that e2e test and the story scope row change.
