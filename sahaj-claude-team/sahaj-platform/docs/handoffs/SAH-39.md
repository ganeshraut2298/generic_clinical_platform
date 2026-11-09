# SAH-39 · Homepage ambient background — handoffs

Branch: `feature/SAH-39-home-kinetic-background` (created from `feature/SAH-13-appointments-workspace`, because main has no homepage yet).
Decision: DEC-024 (the human narrowed the request to the homepage, gave the go-ahead and wrote "APPROVED" in chat on 7 Nov 2026).
Approval lines in docs/approvals.md: none yet. The human must add `stories`, `contract` (or "no contract: frontend only") and later `merge`.

## business-analyst (7 Nov 2026)
- Wrote docs/requirements/SAH-39.md and docs/stories/SAH-39.md, with 5 stories.
- Mockup screen: `h-hero` homepage. Its entry in docs/design/screen-map.md is the human's job.
- Open question for the human: keep the red "alert" node colour on the marketing page. The recommended default is to keep it, because the page shows no clinical data.

## solution-architect (7 Nov 2026)
- Wrote the design note docs/architecture/SAH-39-home-background.md. There is no ADR and no API.
- Correction found later: section 4 calls `.member.surf` "already solid". It is not, because of the order of the global `_console.scss` rules. A one-line fix of the note is pending with the architect.

## angular-developer (7 Nov 2026)
- Built the `shared/ui/kinetic-background/` component, its config, physics and tokens.
- Added `_tokens.scss` and `.surface-*` styles in `_primitives.scss`.
- Wired up the home page components (`data-surface`, `data-dot-exclude`).
- Updated the README.
- Results: 2,093 unit tests pass; lint and build pass.

## qa-engineer, part 1 (7 Nov 2026, stopped by the human partway)
- Wrote frontend/e2e/home-background.spec.ts, the partial docs/test-plans/SAH-39.md and the screenshots.

## angular-developer fix (8 Nov 2026)
- E2E tests 1-7 and 1-8 caught a real defect. The role cards (`.member`) were transparent, because the global rule order differs from the mockup's. The selected specialty card had a translucent tint, so its text sat on the canvas.
- Fix:
  - roles.component.scss:26 now uses `background: var(--surface)`.
  - specialties.component.scss:30 now lays the tint on white.
  - Added a new unit test.
- Results:
  - Tests 1-7 and 1-8 pass 3 times out of 3.
  - home-background.spec and home.spec: 168 pass, 2 skipped by design.
  - Lint, all 2,110 unit tests and the build pass.
- Visual change for the human to confirm: the role cards are now white, as in the mockup.

## qa-engineer, part 2 (8 Nov 2026), Testing done
- **Repeat runs.** home-background.spec and home.spec ran in all projects, three times in a row: 168 passed, 2 skipped by design and 0 failed each time. The final run after QA's additions: 172 passed, 2 skipped, 0 failed (174 runs).
- **Earlier "7 desktop timeouts".** These were the app's cold start when the machine was saturated (10 workers plus a Maven run), not the background layer: with the layer off there were more failures. The expect timeout went from 7 s to 15 s in playwright.config.ts. No assertion was relaxed.
- **Speed, 4x CPU throttle.**
  - Drawing time per frame meets the 8 ms target: median 0.5–3.1 ms, p95 under 7 ms.
  - At rest the layer costs nothing.
  - On a software-rendered 1440 px page the frame rate drops from about 22 to about 15 fps while the pointer moves. This is not a limit set by the story; it is for the human.
- **Other checks.** Reduced motion, touch, dark theme off, and no trace of the layer on signed-in screens (all 7 roles, OPD and IPD): all pass.
- **Screenshots for OQ-39-6.** In docs/test-plans/SAH-39-shots/. The role cards are white and the selected specialty card is the tint on white. The dimmed role cards (`.dim` opacity) show the dots faintly, which the human should look at.
- **Not verified in the browser:**
  - AC 5-6, because the whole-folder regression was not re-run.
  - Contrast was checked without axe, which is the human's decision.
- **Gate.**
  - `validate frontend` is blocked by the script flag, which is the human's to fix.
  - In unit tests, 2 ist-clock.spec failures come from the audit fix lane, which was still in progress, not from SAH-39.
  - `validate docs` fails on the ADM-2.md file name.
- **Next:** code-reviewer and security-reviewer.

## Reviews (8 Nov 2026)
- Security: PASS. See docs/reviews/SAH-39-security.md.
- Code: CHANGES REQUIRED, because the dark-theme hero glass panel is near-white. See docs/reviews/SAH-39-code.md.

## angular-developer, review fixes (8 Nov 2026)
- Fixes:
  - Added the dark `--glass-surface` value `rgba(22, 38, 66, 0.92)` at `_tokens.scss:102`. It is the same as the dark `--glass-hi`. Hero text contrast: 13.1:1, 7.6:1 and 8.5:1.
  - New unit test: tokens-dark-glass.spec.ts.
  - README note added.
  - The grid is now drawn once per frame.
- Results:
  - Lint passes.
  - 2,117 unit tests pass.
  - The build passes.
  - home-background.spec: 95 passed, 13 failed. The pixel-hash "first look" checks see a redraw about 0.9 s after load. The failures stay even with the edits reverted, so the edits do not cause them. The cause is under investigation by qa-engineer.

## qa-engineer, re-run (8 Nov 2026)
- **Root cause of the 13 failures:**
  - The production build loads `styles-*.css` late, through a critical-CSS swap.
  - When that file arrives, the sticky header shrinks by 2 px. The layer then rightly redraws, which is a real layout change, so it is not a product bug.
  - Fonts and machine load did not reproduce it; a delayed stylesheet did.
- **Fix, test side only:** `settlePage()` in home-background.spec.ts waits for the stylesheets to apply and for the fonts. It uses `expect.poll`, because `waitForFunction` leaves an observer in the page, which broke test 5-1. No assertion was weakened.
- **Dark-theme check 1-8 added:** the panel is `rgba(22, 38, 66, 0.92)` and the text is at least 4.5:1. It passes in both projects.
- **Counts:** two runs in a row, each 174 passed, 2 skipped by design, 0 failed.
- **Note:** home.spec "no script errors" failed once because Google Fonts was unreachable while the network was down. The suggestion is to block the font hosts in that test (owner: qa-engineer, later).

## code-reviewer, re-check (8 Nov 2026, running)
- Verdict: APPROVE (security unchanged: PASS). There is one Nit (`settlePage` is not used in non-pixel tests), which is fine as is.

## Status: waiting for the human (`merge`)
- Human lines needed in docs/approvals.md:
  - `SAH-39 | stories`.
  - `SAH-39 | contract`: none needed, because SAH-39 is frontend only with no API. The human may write "n/a".
  - `SAH-39 | merge`.
- Human decisions:
  - Accept the e2e expect timeout of 15 s (it was 7 s), or limit it to the home specs.
  - Accept the contrast check in place of axe, or allow `@axe-core/playwright`.
  - Look at the dimmed role cards (see the screenshots).
  - Keep the red node on the homepage (OQ-39-1 default).
  - Add a "Home page" row to the screen map.
  - Approve a follow-up to self-host Google Fonts (DPDP).
- Validation:
  - Frontend lint, 2,117 unit tests and the build pass; e2e home 174/0 twice.
  - `validate frontend` is blocked by the script flag, and `validate docs` by ADM-2.md. Both are human fixes.
