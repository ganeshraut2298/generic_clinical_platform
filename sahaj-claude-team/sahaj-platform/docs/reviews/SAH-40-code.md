# SAH-40 code review · Refused refresh clears clinical data

Reviewer: code-reviewer, read-only. Date: 9 Nov 2026.

**Verdict: APPROVE WITH COMMENTS.** There are no Blocker or Major findings.

Confirmed:
- Every acceptance criterion is covered by both unit and e2e tests.
- One shared refusal rule (`ipd-refusal.ts`) is used everywhere.
- The epoch and unsubscribe guards are correct.
- No timer or subscription leaks.
- Zoneless signals are used correctly.
- No G-2 regression; the D-1 fix is correct.
- No test was weakened. The only intended change is "—" instead of 0.

| # | Sev | File | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| 1 | Minor | emergency.component.ts `openSheet` / `refuse()` | The Admit sheet is not closed on a refusal. Same as security #1. | angular-developer | FIX |
| 2 | Minor | pharmacist-panel.component.ts `onConflict` | A store refusal leaves the sheet open, and `onConflict` → `store.load()` brings the rows back without Try again. Same as security #2. | angular-developer | FIX |
| 3 | Minor | emergency `load()`/`refuse()`, panel effect | After Try again is refused again, keyboard focus is lost and falls to the body. | angular-developer | FIX: treat focus on the body as lost and move it to the notice |
| 4 | Minor (process) | medicine-round.component.ts focusIndex hunks, safety spec `#sf-round-h` change | These belong to the DEC-025 audit (G-3), not SAH-40. | tech lead | Split them out at commit time: keep only the `giveSheet` close-on-destroy hunk under SAH-40 |
| 5 | Minor | safety `refuse()`, ipd.page `safetyTabNumber` | The Safety tab can show its last counts after a Safety refusal (counts only). | angular-developer | FIX together with security #6: clear `liveSafety` and hide the number until Try again |
| 6 | Nit | safety `refuse()` | A Safety `module-off` shows the notice, not the lock page (G-2 behaviour). | human | Note for the human; no change |
| 7 | Nit | emergency `refuse()`/`apply()` emit after destroy | `emit` can be called on a destroyed output. | angular-developer | FIX (`takeUntilDestroyed` or a guard) |
| 8 | Nit | ipd.page `pharmacistSummarised` | After Try again, the pharmacist part of the tab number waits up to 60 s to come back. | angular-developer | FIX: reset it in `clearData()` |
