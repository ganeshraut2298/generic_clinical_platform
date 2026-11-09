# Bug · page width wrong when the expand and minimize control is clicked

Reported by the human on 9 Nov 2026, with a screenshot of IPD → Beds and admissions (Admitted tab, Owner, about 1915 px wide).
The report does not say which control it means, so the developer checks:
- the left rail toggle;
- the cards-per-row buttons;
- any section expand and collapse control.
The developer compares the result with the mockup and DEC-013, reproduces the bug with Playwright, and fixes the root cause.

## angular-developer (9 Nov 2026, running)
- It edits first. It takes the frontend lock before any test, because QA holds it.
- **Done (9 Nov 2026).**
  - **Root cause:** `.shell main.page` had `max-width: var(--page-max, 1680px)` and was centred. With the rail collapsed at 1920 px, the work area (1860 px) is wider than 1680, so the page jumped to a 1680 px column with an empty band of about 90 px on each side.
  - **Fix (frontend):**
    - `_console.scss`: no max-width and no centring on `.shell main.page`.
    - `_shell.scss`: top bar padding fixed at 20 px; `--page-max` removed.
    - `admin-shell.component.scss`: removed the 1320 px cap and the centring from `.adm-page`.
    - New guard spec `work-area-width.spec.ts` (4 tests).
  - **Measured at 1920 with the rail collapsed:**
    - before: the page started at x=150 and was 1680 wide;
    - after: it starts at x=60 and is 1860 wide, with a 20 px margin at each side.
    - Widths at 1440, 1680, 360 and 768 did not change, and there is no horizontal scrollbar in any state.
  - **Mockup:** it is fluid, so this follows the mockup. It deviates from DEC-013 option B, so I appended an update to DEC-013 item 1 for the human.
  - **Tests:** lint passes, 2,411 unit tests pass (4 new), and the build passes.
  - **A flaky test, not touched:** `admitted.model.spec.ts` (`cardFlags`) failed in two earlier full runs and then passed. One failure read "Not seen · 7 min" instead of "Going home today", which points to a dependency on the real clock. It is the SAH-36 area; the angular-developer should make it deterministic.
  - **Not measured in a browser:** the admin shell. It needs admin mocks.
- **Next:** QA adds an e2e spec at 1920 and 2560 (and 1440, 1680, 360 and 768 as a regression): rail left edge 248/60, right edge equal to the viewport, 20 px margins, no horizontal scroll, `aria-expanded` flips, and `layout-matrix.spec.ts` re-run.
- **QA (9 Nov 2026): the width fix PASSES.** work-area-width.spec ran 3 times with 0 failures at 1440, 1680, 1920, 2560, 768 and 360 px. A mutation check shows that the spec catches the old cap. layout-matrix passed 84 of 84. The admin shell is not driven in a browser. Reviews: code APPROVE, security no impact.
