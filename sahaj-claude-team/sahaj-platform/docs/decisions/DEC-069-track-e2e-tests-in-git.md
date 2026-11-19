# DEC-069 · Browser (e2e) tests are not in git

Date: 18 Nov 2026. Status: ANSWERED (A). Raised by: tech lead, from security review SAH-76 round 4, finding R4-3.
Blocked: none directly, but the SAH-76 commit leaves its browser tests out unless this is decided.

## What I found (checked 18 Nov)
- **The cause:** line 8 of `.gitignore` is `frontend/e2e`, set in commit ed2c07d "chore: Sahaj team setup".
- **What git holds today:** 6 files under `frontend/e2e` (setup files and `home.spec.ts`).
- **What git does not hold:** every Playwright test written since then. That includes `mar.spec.ts`, `mar-override.spec.ts`, the shared worlds, the PIN-hygiene tests, and the tests of every OPD and IPD story.
- **What that means:**
  - Reviewers cannot see e2e changes in a diff.
  - `main` does not hold them.
  - If this folder is lost, the tests are lost too.
- `frontend/e2e/.gitignore` already keeps `node_modules/`, `report/` and `results/` out.

## Options
- **A (recommended): track the e2e tests.** You remove line 8 from `.gitignore` (the file is not owned by an agent), and the next commit adds the specs.
  - Pro: the tests are versioned and reviewable, and `main` can rerun them.
  - Con: a large first add. Every spec needs a check before it is committed: the security review found only made-up PINs and patients, but you should grep once for the real SAH-42 PIN, because I cannot read `.env`.
- **B: keep them out of git** and back the folder up separately.
  - Con: no history and no review of the browser tests.

## Recommendation
A. Grep for the real PIN first, then remove the line.

## Answer
**Human, 18 Nov 2026:** A. Track the e2e tests. The human greps frontend/e2e for the real SAH-42 PIN, then removes line 8 (`frontend/e2e`) from `.gitignore`. The SAH-76 commit then includes the browser tests.
