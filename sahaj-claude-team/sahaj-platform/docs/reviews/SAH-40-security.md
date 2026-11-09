# SAH-40 security review · Refused refresh clears clinical data

Reviewer: security-reviewer, read-only. Date: 9 Nov 2026.

**Verdict: PASS WITH CONDITIONS.** No Critical or High findings.

## What works
- A 403 empties every signal that holds patient data.
- `module-off` locks the page.
- Epoch guards and unsubscribes stop late answers from bringing data back.
- While refused, no reads are sent until the user presses Try again.
- Nothing is written to the console, storage or URL.
- The server's 403 message is shown as plain text, so it cannot inject markup.

## Findings
| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| 1 | Medium | emergency.component.ts:183-204, 343-358 | An open Admit sheet (casualty name, bed) is not closed on a refusal or on destroy. | angular-developer | FIX |
| 2 | Medium | pharmacist-panel.component.ts:84-95, 69, 137, 166 | A refusal from the counts poll, list reload or Show more leaves an open Verify/Reject sheet showing name, UHID, drug and allergy. A 409 then calls `store.load()`, which reads without Try again. | angular-developer | FIX: close the sheet when `refused()` is set. `onConflict` must not read while refused. AC 19 still holds, because the sheet's own action refusal shows its message first. |
| 3 | Medium | safety.component.ts:180-184; ipd.page.ts:337-346, 254-260 | When the page locks (module-off) or the page refuses, page-owned sheets (Admission, Nursing, EMR, Admit) and the Safety Response sheet stay open over the lock page. | angular-developer | FIX: Safety closes its Response sheet on destroy. The page closes the dialogs it opened on `lock()` and `refuse()`, tracking the dialog references (preferred over `closeAll`). |
| 4 | Low | ipd.page.ts:512-524, 236-253 | `refreshList()` is not guarded by `locked()`, so a late Admit save can refill the list behind the lock page. | angular-developer | FIX (one line) |
| 5 | Low | emergency.component.ts:217-219; safety.component.ts:366-368; pharmacist-panel.component.ts:199; ipd.page.ts:517 | A success toast from just before the refusal stays 5-8 s and can name the patient. | angular-developer | FIX (cheap): dismiss the snackbar in `refuse()` and `lock()` |
| 6 | Info | ipd.page.ts:159-169 | The Safety tab keeps its last counts; the menu badge keeps its counts. Counts only, no names. | angular-developer | Optional: clear `liveSafety` on a Safety refusal. Include it. |
| 7 | Info | pharmacist.store.ts:66-89 | `moduleOff` is never reset. Harmless. | — | None |

Conditions for PASS: #1-#3 fixed, with unit tests showing that a refusal closes an open sheet.

## Re-check after fixes (9 Nov 2026): PASS
Conditions #1–#3 are closed, and recommendations #4–#5 are done. No patient identifiers stay on screen after a refusal. Two new Low findings, neither blocking:

| # | Sev | File:line | Finding | Tech lead decision |
|---|---|---|---|---|
| R-1 | Low | ipd.page.ts:393-399, 449 | After a `forbidden` list refusal, `pollSafety()` on other tabs can call `refreshList()`, which reads the list again without Try again. It returns data only if the server answers 200, so this is a process gap, not a data leak. | FIX: a page `refused` flag, set in `refuse()` and cleared in `load()`, that guards `refreshList()` and `pollSafety()` |
| R-2 | Low (a11y) | emergency.component.ts:206,220; ipd.page.ts:364-365 | This is F-1 for `module-off`. Focus can be left on the body instead of the lock heading. | FIX: Emergency passes "focus was inside" to the page with `refused`; QA adds a focus assertion for `module-off` |

R-1 and R-2 are closed (9 Nov 2026). They were fixed, and QA covers them with browser tests: refused-refresh.spec, 136/136 three times. The unit guard tests fail when the guard is removed.
