# SAH-39 code review · Homepage ambient background

Reviewer: code-reviewer, read-only. Date: 8 Nov 2026.

**Verdict: CHANGES REQUIRED.** One Major finding, with a one-token fix.

| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| 1 | Major | styles/_tokens.scss:57-65, _primitives.scss:112-115 | `--glass-surface` (white at 85 %) has no dark-theme value. A dark-theme user coming back to `/` sees a near-white hero panel under light text, which is unreadable. This breaks OQ-39-5 and AC 1-8. The dark e2e test misses it. | angular-developer; QA for the test | FIX: dark value plus unit and e2e assertion |
| 2 | Minor | e2e/playwright.config.ts:22-23 | The expect timeout was raised from 7 s to 15 s for every spec. No assertion was relaxed. | human | Keep it, but name it in the merge note for the human |
| 3 | Minor | e2e/playwright.config.ts:11,33-43 | The layout-matrix projects (DEC-025 G-7) sit in the same file. | tech lead | Proposed as a separate commit |
| 4 | Minor | docs/approvals.md | There are no SAH-39 lines yet. | human | Human |
| 5 | Minor | AC 1-8 and test plan:239 | No axe check was run. QA used a computed and structural contrast check instead. | human | The human accepts the substitute or allows the library |
| 6 | Minor | kinetic-background.component.ts:221, 466-470 | In still mode (touch or reduced motion), the header exclusion is not re-measured on scroll. The difference is about 6 px. | angular-developer | Note it in the README (accept) |
| 7 | Nit | kinetic-background.component.ts:503-505 | The grid is drawn twice on relayout frames. | angular-developer | FIX (cheap) |
| 8 | Nit | kinetic-background.component.ts:532, 748-759 | New arrays are allocated on every frame. | — | Leave it; the frame time is fine |
| 9 | Nit | e2e/serve.mjs:12,43-52 | The `E2E_NO_KINETIC` performance hook is still in place. | qa-engineer | Keep it and document it; also fix the crash on a malformed URL (security #1) |
| 10 | Nit | kinetic-background.component.ts:76-91 | The colour may flash for one frame on first paint. | — | Leave it |

The review confirmed the following:
- The acceptance criteria map to the tests.
- The work stays on the homepage only.
- Event listeners and the animation loop are cleaned up.
- The zoneless setup is correct.
- The physics match the spec.
- Performance is acceptable.
- No new library was added.
- The README is complete.
- The roles and specialties fix is correct.

## Re-check after fixes (8 Nov 2026): APPROVE
- Major #1 is fixed. The dark `--glass-surface` is in `_tokens.scss:102`. The unit spec `tokens-dark-glass.spec.ts` and the e2e test 1-8 (`home-background.spec.ts:844-905`) check it in a meaningful way.
- #7 is correct: `layout(force, draw)` at line 620 and the frame loop at lines 500-508. No draw is missed.
- #6: the README note is fine.
- `settlePage()` waits for the late `styles-*.css` critical-CSS swap and the fonts. The wait is justified and weakens no assertion. `NAV = domcontentloaded` is fine.
- Nit: not every test that uses NAV calls `settlePage`. That is fine where a test compares no pixels. If one of them turns flaky, adding `settlePage` is the first thing to try.
