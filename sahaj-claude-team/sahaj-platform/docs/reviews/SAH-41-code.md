# SAH-41 code review · Self-hosted web fonts

Reviewer: code-reviewer, read-only. Date: 9 Nov 2026.

**Verdict: APPROVE WITH COMMENTS.** There are no Blocker or Major findings. Lint passes, and 2,232 unit tests pass in 155 files.

## Confirmed
- **Font declarations.** A script compared the 19 `@font-face` blocks with css2.css: unicode-range, weight, style and stretch are identical. Every block uses swap and woff2, and none uses `local()`. Cyrillic is dropped, and the shared-file layout matches Google's.
- **Files.** The SHA-256 values match the README. Relative url() means the build hashes the files into `media/`.
- **Build.** The licences are in `dist/fonts/licenses/`. index.html is clean.
- **Scope.** No new dependency, and the tokens and typography are untouched.
- **README.** The frontend README section is accurate.
- **Weight 800.** The note is correct: Google's CSS also stopped at 700, so this is not a regression.

## Findings
| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| 1 | Minor | e2e/self-hosted-fonts.spec.ts:537-556 | The BR4 test never shows that the fallback font is in use while the real font is held back. `early.figtreeLoaded` is captured but not asserted. | qa-engineer | FIX: assert Figtree is not loaded while the request is held. Add visibility and opacity checks on the h1. |
| 2 | Minor | src/styles/no-third-party-fonts.spec.ts:77-91 | The family and weight regex depends on property order. A block written in another order would be skipped silently. | angular-developer | FIX: the number of matches must equal the number of faces. A weight range must fail the test. |
| 3 | Minor | src/styles/no-third-party-fonts.spec.ts:28,53-59 | The guard checks only two hosts, case-sensitively, and only in `src`. | angular-developer | FIX (cheap): make the match case-insensitive, scan `public/` too, and fail on any `http(s)` `url()` or link in `_fonts.scss` and index.html. This also covers security finding #4. |
| 4 | Minor | e2e/self-hosted-fonts.spec.ts:291 | The test title says it covers the Home page as well as the queue, but it only opens the queue. | qa-engineer | FIX: visit Home too |
| 5 | Minor | e2e/self-hosted-fonts.spec.ts:294, 564-565 | The test compares the baseURL string with the request origin, and the cookie domain is hard-coded. | qa-engineer | FIX: `new URL(baseURL).origin`, and take the cookie domain from the baseURL |
| 6 | Nit | e2e/self-hosted-fonts.spec.ts:200 | A fixed 500 ms wait. | qa-engineer | FIX: `waitForRequest` |
| 7 | Nit | e2e/self-hosted-fonts.spec.ts:23,254,487 | The pattern assumes the build's file hash is 8 characters, so a change in Angular's hashing would fail the whole file. | qa-engineer | FIX: a note in the file header |
| 8 | Nit | features/config/overview/overview.page.scss:42 | `font-weight: 800` is drawn as 700. | human | Note for the human: confirm 700 against the mockup. No change in SAH-41. |
| 9 | Nit | src/assets/fonts/README.md | The "How to update" steps should say to keep the saved css2 file outside `src`, or the guard test fails on it. | angular-developer | FIX |
| 10 | Nit | docs/test-plans/SAH-41.md:113 | The test plan gives the wrong README heading. | qa-engineer | FIX |

Not defects:
- GL-41-1 (cache headers) remains a go-live condition, so 41-4 AC3 and 41-2 AC5 are not met in code.
- 41-3 AC5 (no more bytes than Google today) was measured on our side only.
- The e2e spec needs `git add -f`.

## Fix pass (9 Nov 2026)
All FIX items are done: #2, #3 and #9 by the developer (2,242 unit tests), and #1, #4-#7 and #10 by QA (96/0 three times). #8 is left for the human.
