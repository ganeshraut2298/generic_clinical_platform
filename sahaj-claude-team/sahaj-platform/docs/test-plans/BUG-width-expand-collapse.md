# Test plan BUG · page width wrong when the expand and minimize control is clicked

Bug note: `docs/handoffs/BUG-width-expand-collapse.md` (reported by the human, 9 Nov 2026).
Mockup: the shell is fluid (last mockup rule `.axws, .cs main.page { max-width: none }`). DEC-013 option B (a 1680 px cap) is deviated from by the fix; the angular-developer added an update to DEC-013 item 1 for the human.
Written by qa-engineer, 9 Nov 2026. Status: see "Results" at the end.

## The bug and the fix

`.shell main.page` had `max-width: var(--page-max, 1680px)` and was centred. With the rail collapsed at 1920 px the work area (1860 px) was wider than 1680, so the page jumped into a centred 1680 px column with about 90 px of empty band on each side. Fix: no maximum width and no centring on `.shell main.page` (`_console.scss`), top bar padding fixed at 20 px (`_shell.scss`), no 1320 px cap on `.adm-page` (`admin-shell.component.scss`).

## What is tested where

| Layer | File | What it proves |
| --- | --- | --- |
| Unit (angular-developer) | `frontend/src/styles/work-area-width.spec.ts` (4 tests) | The style files hold no max-width and no centring margin (read as text; the unit runner has no layout) |
| Browser (Playwright, production build, API mocked) | `frontend/e2e/work-area-width.spec.ts` | The real measured geometry in Chromium, expanded and collapsed, at 1440, 1680, 1920, 2560, 768 and 360 px |

The spec runs once (project `desktop-1440`; every test sets its own window size). It signs in as Owner on IPD → Admitted with the SAH-37 mock server (`mountCardServer`) and a preferences route that keeps what the app saves (`sidebarCollapsed`), like `app-shell.spec.ts`, because the app applies the server's answer.

## Cases

| # | Case | Test | Checks |
| --- | --- | --- | --- |
| 1 | The bug sizes 1920 and 2560, and the regression sizes 1440 and 1680: expand, collapse, 2/4/3 per row, expand again | `<w> px: the work area fills the space beside the rail ...` (4 tests) | `main.page` left edge 248 expanded and 60 collapsed; right edge = window width; first page host starts 20 px after the rail and ends 20 px before the right edge in both states; `scrollWidth - clientWidth` = 0; `main.page` grows by exactly 188 px when collapsed and is back to the start width when expanded; `.pcgrid` keeps 3 columns in both states; the 2, 3 and 4 buttons give 2, 3 and 4 columns and the grid stays inside the work area with the rail collapsed; `aria-expanded` true/false/true and the label (collapse / expand) flips |
| 2 | The centred-column signature of the bug at 1920 | `1920 px: the page is never a centred column` | collapsed: first host left < 100 px (bug: 150), width > 1800 (bug: 1680), `main.page` wider than 1680 |
| 3 | Tablet and phone (768, 360): the rail is a drawer | `<w> px: no rail toggle ...` (2 tests) | the toggle is not drawn, the hamburger is; the work area starts at x=0 and ends at the window edge; the page margin is 12 px; no sideways scroll; the grid is inside the margins; 2 columns at 768 and 1 at 360 (3 per row becomes 2 on a tablet and 1 on a phone) |
| 4 | Error path: the server refuses to save the rail setting | `1440 px: a refused save of the rail setting puts the rail back ...` | the rail stays expanded, `aria-expanded` stays true, the page edges stay at 268 and 1420, no sideways scroll |
| 5 | Admin shell `.adm-page` has no max-width | not driven in a browser (needs admin mocks). Covered by the angular-developer's unit spec reading the style file | open, see "Not covered" |

## Not covered

- The admin shell was not measured in a browser (no admin mocks exist in `frontend/e2e/`). The style rule is covered by the unit spec only.
- A real scrollbar: headless Chromium draws overlay scrollbars, so the window width equals the client width; the test asserts that equality. A browser with a classic scrollbar would show a width smaller by the scrollbar, which is a property of the browser and not of the page.

## Results

Run 9 Nov 2026, 21:25 to 21:50, production build rebuilt 21:23, frontend lock held, mocked API.

| Run | Tests | Result |
| --- | --- | --- |
| `work-area-width.spec.ts`, run 1 / 2 / 3 | 8 per run (+ 8 phone-360 copies that skip by design) | 8 passed, 0 failed, three times; 11 to 13 s each |
| `layout-matrix.spec.ts`, project layout-1920 | 12 | 12 passed (it does not depend on the old centred layout) |
| `layout-matrix.spec.ts`, all 7 projects | 84 | 84 passed |
| Existing IPD specs once (admitted-cards, safety, nursing, emergency, medicine-round, mar, discharge, bed-transfer) | 1,104 | 1,104 passed |
| Frontend unit tests / lint / build | 2,439 / - / - | passed / passed / passed |

Mutation check (temporary copy of the spec, not kept): with the old rule `max-width: 1680px; margin: 0 auto` injected into the page, the 1920 and 2560 tests and the "never a centred column" test failed; the 1440 and 1680 tests passed. The spec therefore detects the bug and the fix removes it.

Measured by the spec (same in all three runs): left edge of `main.page` 248 expanded and 60 collapsed at every desktop size; right edge = window width; first host 20 px from both sides in both states; `main.page` 188 px wider when collapsed; three columns kept, and 2, 3 and 4 per row stay inside the work area with the rail collapsed; no sideways scroll at any size and state; `aria-expanded` and the label flip; at 768 and 360 px there is no rail toggle, the margin is 12 px and the grid is 2 and 1 columns.

Verdict: the width fix is verified in a real browser. No defect found.
