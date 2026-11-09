# SAH-40 · Refused refresh clears clinical data on the Emergency and Pharmacist screens — handoffs

Source: security re-review of the DEC-025 audit (docs/reviews/SAH-35-36-38-audit-security.md #5). It applies the same rule as G-2 (Admitted, Safety) to the Emergency queue and the pharmacist check.
Branch: work happens in the shared working tree on `feature/SAH-39-home-kinetic-background`. The tree is dirty with uncommitted earlier stories. The human splits the branches at commit time, as done for SAH-35, 36 and 38.
Go-ahead: human, 8 Nov 2026, in chat: "go ahead and approved for further development". The `stories` line in docs/approvals.md must still be added by the human.

## business-analyst (8 Nov 2026, running)
- Done. docs/requirements/SAH-40.md and docs/stories/SAH-40.md: 2 Must stories, 25 acceptance criteria.
- Mockup screens: IPD → Emergency (`ipEr`); IPD → Safety, Pharmacist check panel and tile (`ipSafety`).
- No API change expected: ipd.yaml already lists 403 `forbidden` and `module-off` on the four GETs.
- Extra gap found:
  - The pharmacist counts poll ignores a 403 `forbidden`.
  - On `module-off`, the store keeps the rows in memory.
  - The screen must never show "No orders waiting" or 0 after a refusal.
- Open questions OQ-40-1 to OQ-40-6: recommended defaults are taken under DEC-023. They reuse the built lock page, the error notices and the server message, so there is no new wording. OQ-40-1 (wording) needs the human to confirm.
- Validation: `validate docs` fails only on the old `docs/stories/ADM-2.md` file name (human item). The SAH-40 files are fine.

## solution-architect (8 Nov 2026, running)
- Done: docs/architecture/SAH-40-refused-refresh.md. No ADR. **The contract is unchanged** (the four GETs already declare both 403 types), so no `contract` line is needed for SAH-40; the human still adds `stories`.
- Rule:
  - 403 `module-off` → clear and lock the page.
  - Any other 403 → clear, then show the first-load notice with Try again.
  - Network error, 5xx, 4xx other than 403 → keep the data and show "Could not refresh".
- Design:
  - Shared helper `features/ipd/ipd-refusal.ts`.
  - An epoch guard stops older answers from overwriting newer ones.
  - Timers keep ticking but send nothing while refused, the same as G-2.
  - Counts show "—" when not known, never 0.
- Extra found: after a G-2 refusal, `safety.component.ts` `refresh()` still reads the board every 60 s, so a later 200 brings the board back without Try again. **Tech lead decision (DEC-023 default):** added to SAH-40. It is the same rule on the same page, with no new wording.

## angular-developer (8 Nov 2026, running)
- **Done.** New shared helper `features/ipd/ipd-refusal.ts`. Changed: Emergency (refuse, epoch guard, `refused` output), pharmacist store (refused, clear, guards), panel, tile ("—"), ipd.page (clearData, lock, onEmergencyRefused, the moduleOff effect now locks, Safety tab number hidden), and the Safety board extra (`denied` guard on refresh).
- **Timers (OQ-40-3):** the timers keep ticking, but a refused screen sends nothing until Try again. This is the same as G-2.
- **Results:**
  - Unit tests: 2,166 pass (154 files, about 48 new). One test was changed on purpose: the tile now shows "—" instead of 0 (OQ-40-4).
  - Lint and build pass. `validate frontend` is blocked by the script flag (human).
- **For QA:**
  - A first-load Emergency `module-off` now locks the whole page.
  - The Safety board's dialog reloads (`reloadAll`, `roundAnswered`) are not guarded while denied.
- **Housekeeping note:** at 22:27 the developer removed the frontend lock by mistake for about one second, then restored it. It ran nothing during that time. The SAH-39 QA result (two green runs) is not affected; its counts were reported after that.

## qa-engineer (8 Nov 2026, running)
- **Done.** Test plan docs/test-plans/SAH-40.md and the new spec refused-refresh.spec.ts (38 tests). The spec passed 76/76 three times. Related IPD specs: 1,111 pass; 3 load timeouts in mar.spec passed 30/30 on re-run. Unit tests: 2,166 pass. Every browser-testable AC passes.
- **D-1 (angular-developer, Medium):** in safety.component.ts, `reloadAll()` (after a sheet is saved) and `roundAnswered()` are not guarded by `denied`. A sheet that stays open after a refusal keeps the patient name. Saving it brings the board back without Try again, and then the board freezes. **Tech lead: FIX.** Guard both paths, close any open sheet on a refusal, and also clear the pharmacist store when the Safety board itself is refused (QA observation).
- **D-2 (human, Low):** a first-load Emergency `module-off` locks the whole page, while the story table says first load is unchanged. **Tech lead default (DEC-023):** accept. `module-off` means IPD is off for the clinic, and the lock page is the safer and consistent reading. The human may override.

## angular-developer, D-1 fix (8 Nov 2026, running)
- **D-1 fixed.** All in frontend/src/app/features/ipd/:
  - **safety.component.ts:**
    - A single `refuse()` (line 238) handles every 403. It closes the Response sheet, clears the pharmacist store, cancels the events and "earlier entries" requests, and moves focus to the notice.
    - `reloadAll()` (342) and `roundAnswered()` (356) are guarded by `denied`.
  - **Panels:** the Give, Verify and Reject sheets close when their panel is destroyed.
- **Unit tests:** 2,186 pass (20 new). Lint and build pass.
- **Remaining, left as is (counts only, no names):**
  - After a Safety refusal, the page's Safety tab count can still show the last counts.
  - A toast for an action that already succeeded can still show.

## qa-engineer re-run, and code-reviewer and security-reviewer (9 Nov 2026, running)
- **QA re-run after D-1:**
  - The PROBE became 7 real tests (the spec now has 44 tests).
  - Covered: Response, Give, Verify and Reject sheets close on a Safety refusal; a save already in flight cannot bring the board back; the pharmacist rows are cleared.
  - refused-refresh.spec passed 88/88 three times.
  - Related specs: 880 passed; 2 load timeouts passed 12/12 on re-run.
- **Reviews (9 Nov 2026):**
  - Code: APPROVE WITH COMMENTS. See docs/reviews/SAH-40-code.md.
  - Security: PASS WITH CONDITIONS. See docs/reviews/SAH-40-security.md.
  - Both found more open-sheet cases: the Emergency Admit sheet, the pharmacist sheet after a poll refusal, and page sheets left over the lock page.
- **Commit split:** the medicine-round `focusIndex` hunks and the safety spec `#sf-round-h` change belong to the DEC-025 audit (G-3), not SAH-40.

## angular-developer, review fix pass (9 Nov 2026, running)
- **Done.**
  - **Sheets:** every open sheet closes on a refusal or page lock (Emergency Admit, pharmacist Verify and Reject, Safety Response, and the page's Admission, Nursing, EMR and Admit sheets).
  - **Reads:** `onConflict` no longer reads while refused, and `refreshList` is guarded when locked.
  - **Toasts:** success toasts are dismissed. The 403 message from an action on Emergency stays.
  - **Safety tab number:** hidden after a Safety refusal.
  - **Focus:** a new `focusIsLost()` helper.
  - **Emergency:** uses `takeUntilDestroyed`.
  - **Pharmacist summary:** reset on Try again.
- **Tests and build:** 2,221 unit tests pass (35 new). Lint and build pass.

## qa-engineer, final re-run (9 Nov 2026, running)
- **Final QA:**
  - refused-refresh.spec has 63 tests per size. The final spec passed 126/126 three times.
  - Related specs: 1,287 passed; 1 load timeout passed 6/6 on re-run.
  - Every review finding a browser can reach is tested and passes.
- **Changed by design:**
  - C-1: AC 19, the sheet now closes on a background refusal. The business-analyst aligns the AC text.
  - C-2: AC 20, the tab number now comes back at once.
- **F-1 (Low, angular-developer):** when an Emergency refusal closes the Admit sheet, focus falls to the body, because `focusIsLost` checks only the host and not the overlay. Fix: `|| this.sheet !== null`, as in the pharmacist panel.

## angular-developer F-1 and business-analyst AC 19 (9 Nov 2026, running)
- **business-analyst:** AC 19 and AC 20 are aligned in docs/stories/SAH-40.md. The 9 Nov status log covers sheets closing, toasts and the tab number, and records D-2. The requirement now has one clarifying sentence. `validate docs` fails only on ADM-2.md.
- **solution-architect:** aligning line ~95 of the design note (running).
- **F-1 fixed:** emergency.component.ts:195-197 now uses `|| this.sheet !== null`, with a unit test at spec:884. Unit tests: 2,222 pass. Lint and build pass.
- **qa-engineer:** turning the F-1 probe into an assertion (running).
- **solution-architect:** the design note is aligned (section 4 AC 19, section 3.4 D-2, new section 9 final behaviour). The contract is unchanged.
- **QA F-1 verified:** refused-refresh.spec passed 126/126 three times; emergency.spec 148/148. No open defects. **security-reviewer:** checking the conditions (running).
- **Security re-check: PASS.** Two Lows (R-1 the page refused flag, R-2 module-off focus) are being fixed (angular-developer, running).
- **R-1 and R-2 fixed:**
  - ipd.page.ts gets a `refused` flag (line 211) that guards `refreshList()` (405) and `pollSafety()` (~448). It is cleared by Try again.
  - Emergency's `refused` output now carries `{error, focusWasInside}`, so `lock()` moves focus to `#ipd-lock-h`.
  - Unit tests: 2,228 pass (6 new). Lint and build pass.
- **qa-engineer:** browser checks for R-1 and R-2 (running).
- **QA R-1 and R-2 verified:** refused-refresh.spec has 68 tests per size and passed 136/136 three times. Related specs: 542 passed, 0 failed.

## Status: waiting for the human (`merge`)
- **Reviews:**
  - Code: APPROVE WITH COMMENTS. All comments are fixed.
  - Security: PASS, re-checked.
- **Gates:**
  - Frontend lint, 2,228 unit tests and the build pass.
  - E2e: refused-refresh 136/136 three times; related IPD specs green.
  - `validate frontend` is blocked by the script flag, and `validate docs` by ADM-2.md. Both are for the human.
- **Human, approvals.md:**
  - `SAH-40 | stories` line.
  - No contract line is needed, because the contract is unchanged.
  - `merge` line.
- **Human, decisions:**
  - OQ-40-1: existing wording is reused.
  - D-2: a first-load module-off locks the page.
  - Code #6: a Safety module-off shows the notice, not the lock page.
- **Commit split:** the medicine-round `focusIndex` hunks and the safety spec `#sf-round-h` change go with the DEC-025 audit commits, not SAH-40.
