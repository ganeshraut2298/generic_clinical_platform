# SAH-41 · Self-host the web fonts — handoffs

Source: SAH-39 security review, finding #2. Today every page, including signed-in clinical screens, loads Figtree, Noto Sans Devanagari and Caveat from Google, which sends each user's IP to Google (DPDP).
Go-ahead: human, 9 Nov 2026, in chat: "start the dev". Not an approvals.md line.
Tree: shared, on `feature/SAH-39-home-kinetic-background`. The human splits it at commit time.

## business-analyst (9 Nov 2026, running)
- **Done:** docs/requirements/SAH-41.md and docs/stories/SAH-41.md.
- **Mockup screen:** Foundation (no screen).
- **Stories:**
  - 41-1 Home page, no Google request.
  - 41-2 All roles and admin; works on a LAN with no internet.
  - 41-3 The look is unchanged, within 1 px.
  - 41-4 OFL licence, hashed files, offline build, woff2, no new dependency.
  - 41-5 (Should) Regression guard.
- **Open questions** (DEC-023 defaults taken):
  - OQ-41-1: vendored woff2 files in `frontend/public/fonts/` plus a local `@font-face`, with no new npm package.
  - OQ-41-2: latin, latin-ext and devanagari subsets; check that the ₹ sign (U+20B9) is covered.
  - OQ-41-3: cache header `max-age=31536000, immutable` for the font files. The production static server is undefined, so this is a go-live condition.
  - OQ-41-4: variable or static font files, at the developer's choice.
  - OQ-41-5: a broader check for third-party requests is out of scope.
- **Note for the human:** the mockup still loads Google Fonts. The mockup is human-owned and is not changed.
- **Validation:** `validate docs` fails only on the old ADM-2.md name.

## solution-architect (9 Nov 2026, running)
- **Done:** docs/architecture/SAH-41-self-hosted-fonts.md. No ADR, no API or contract change.
- **Font files:** stored in `frontend/src/assets/fonts/<family>/`, not `public/`, because files in `public/` are not hashed. They are declared in a new `src/styles/_fonts.scss` with relative `url()` paths, so the build writes them to `media/` with hashed names.
- **Source:** the files are copied as Google serves them today, keeping the latin, latin-ext and devanagari blocks. The `unicode-range` values and the file layout stay the same.
- **Licences:** an OFL.txt file goes with each font family. If a family declares a Reserved Font Name, work stops there. A README records the source, the version and a SHA-256 for each file.
- **₹:** check which font actually draws it.
- **Loading:** no preload written by hand. `inlineCritical` (beasties) stays on.
- **index.html:** lines 13–18 are removed.
- **angular.json:** one new assets entry copies the licence files into the build.
- **Cache:** Spring Boot does not serve the frontend. The production server is not defined, so cache headers are go-live condition **GL-41-1** (human).
- **Regression guards:** a source guard spec in Vitest, plus the e2e checks listed in section 10.

## angular-developer (9 Nov 2026, running)
- **BLOCKED (permission).** The permission system refused the `curl` download of the Google css2 file and the woff2 and OFL files. The agent did not work around it and changed no files, so the tree is clean.
- It asked the tech lead to download the files instead. **The tech lead declined**, because that would work around a refused permission. This goes to the human.
- **The human's options:**
  1. Allow one-time network access for the download (fonts.googleapis.com, fonts.gstatic.com, raw.githubusercontent.com, github.com).
  2. The human downloads the files into a folder and gives the path.
  3. Park SAH-41.
- SAH-41 is marked NEEDS DECISION.
- **Human answer (9 Nov 2026, AskUserQuestion):** "I'll allow the download". The human allows one-time network access for the download, and the developer is re-run. The tech lead changed no permission settings.

## angular-developer, re-run (9 Nov 2026, running)
- **BLOCKED again (9 Nov 2026).** The permission system denied `curl` twice. Your answer in chat does not change the permission rules, and agents run in the background, so no prompt reaches you. Nothing in the repo changed, and the scratchpad folder `f41` is empty.
- **To unblock:**
  - Either you add an allow rule yourself, `Bash(curl:*)` or one per host, in your own permission settings. `.claude/` is human-only, so no agent or tech lead edits it.
  - Or you download the files and give the path.
- **Then:** the tech lead re-runs the developer, which starts at the verification step.

## Tech lead (9 Nov 2026)
- Human in chat: "go ahead for next development and approved". Recorded as a go-ahead. It is not a line in docs/approvals.md.
- Found the cause of the block. `.claude/settings.json` lists `Bash(curl *)` and `Bash(wget *)` under deny, and deny beats allow, so an allow rule cannot unblock it.
- Wrote DEC-026 (font file source). SAH-41 is NEEDS DECISION.
- **Human answer (DEC-026):** the human downloads the files. Waiting for the folder path.

## angular-developer, run 3 (9 Nov 2026, running)
- The human supplied the files in D:\sahaj-fonts (css2.css, 7 woff2 files plus one duplicate copy, 3 OFL.txt). The tech lead checked that every woff2 starts with wOF2.
- The developer starts at design section 5, step 2. No network access is used.
- **Done (9 Nov 2026).**
  - 7 woff2 files are in src/assets/fonts/<family>/ with an OFL.txt in each family folder and a README with the SHA-256 values. The duplicate file is identical to the original (787d445a...).
  - src/styles/_fonts.scss has 19 @font-face blocks, and src/index.html no longer links to Google. angular.json copies the licences to fonts/licenses/. The new guard spec no-third-party-fonts.spec.ts has 4 tests.
  - No Reserved Font Name is declared. ₹ is drawn in Figtree, and µ ½ × ± ° use Figtree as before. ≥ ≤ → ✓ come from a system font; Google's subsets are the same, so this is expected to match the old page.
  - English Home page: 81,340 bytes of fonts, no Devanagari file, and 0 external requests with the network blocked.
  - Lint passes, 2,232 unit tests pass and the build passes. `validate frontend` fails only on the known --browsers flag.
- **Notes:**
  - overview.page.scss:42 uses weight 800, which is drawn as 700. Reported, not added (story 41-3).
  - GL-41-1 (cache headers) stays open.
  - `npm test` in watch mode hits TS2451 in tokens-dark-glass.spec.ts. That is a SAH-39 file; the fix is `export {};`.

## qa-engineer (9 Nov 2026): READY FOR REVIEW
- **New files:**
  - docs/test-plans/SAH-41.md
  - frontend/e2e/self-hosted-fonts.spec.ts, 48 tests × 2 viewports (`git add -f`)
- **Results:**
  - The new spec passed 96/0 three times in a row.
  - The home, home-background, layout-matrix and app-shell specs passed 294 with 8 skipped by design, so there is no font-swap shift.
  - 2,232 unit tests pass; lint and the build pass.
- **No product defects.**
- **Not testable now:** cache headers (GL-41-1) and a comparison with Google's live download.
- **Note:** the bare `document.fonts.check` for Noto is false by design, because it tests the space character. The test checks with Devanagari text instead.
- **Next:** code-reviewer ∥ security-reviewer.

## security-reviewer (9 Nov 2026): PASS WITH CONDITIONS
- No Critical, High or Medium findings. Nothing blocks the merge. The privacy goal is met.
- The only condition is go-live GL-41-1: cache and cookie rules on the production server.
- Info items: the CSP follow-up for the backlog, and an optional wider guard spec.
- Report: docs/reviews/SAH-41-security.md.

## code-reviewer (9 Nov 2026): APPROVE WITH COMMENTS
- No Blocker or Major findings. The font blocks match Google's exactly (checked by script).
- Fixes:
  - angular-developer: #2, #3 (+ security #4), #9, plus `export {}` in tokens-dark-glass.spec.ts (a SAH-39 item).
  - qa-engineer: #1, #4, #5, #6, #7, #10.
- For the human: #8 (weight 800).
- Report: docs/reviews/SAH-41-code.md. The fix pass is running.

## angular-developer, review fixes (9 Nov 2026)
- The guard spec no longer depends on property order: the face count must equal the block count, and a weight range fails. The host check is case-insensitive and now also scans public/. It fails on any absolute url() or link. Fixture tests prove it still catches a regression.
- The README "How to update" steps now say to keep the saved css2 file outside src.
- The SAH-39 item: `export {}` added to tokens-dark-glass.spec.ts. It goes with the SAH-39 commit.
- Lint passes, 2,242 unit tests pass and the build passes.

## qa-engineer, review fixes (9 Nov 2026)
- Fixed #1, #4, #5, #6, #7 and #10. The BR4 test now holds the font request open and proves the fallback is in use; there is no timer.
- **Results:**
  - self-hosted-fonts.spec passed 96/0 three times in a row.
  - home and home-background passed 174, with 2 skipped by design.
  - 2,242 unit tests pass.

## Status: waiting for the human (`merge`)
- Code review: APPROVE WITH COMMENTS, all fixed. Security review: PASS WITH CONDITIONS; the only condition is go-live GL-41-1.
- **Human lines needed:** `SAH-41 | stories`, `contract` (n/a, frontend only) and `merge`.
- **Human decisions:**
  - Weight 800 on overview.page.scss: confirm 700 against the mockup.
  - CSP follow-up for the hardening backlog.
  - Optional: compare a hash with a fresh download.
- **Commit, after SAH-39:**
  - frontend/src/assets/fonts/**, frontend/src/styles/_fonts.scss, frontend/src/styles/no-third-party-fonts.spec.ts, and the `@use` line in frontend/src/styles.scss.
  - frontend/src/index.html and frontend/angular.json.
  - The "Fonts (self-hosted)" section of frontend/README.md.
  - `git add -f frontend/e2e/self-hosted-fonts.spec.ts`.
  - docs/requirements|stories|test-plans/SAH-41.md, docs/architecture/SAH-41-self-hosted-fonts.md, docs/decisions/DEC-026, docs/handoffs/SAH-41.md and docs/reviews/SAH-41-*.md.
  - The `export {}` in tokens-dark-glass.spec.ts goes with SAH-39.
