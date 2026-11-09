# SAH-41 security review · Self-hosted web fonts

Reviewer: security-reviewer, read-only. Date: 9 Nov 2026.

**Verdict: PASS WITH CONDITIONS.** There are no Critical, High or Medium findings, and nothing blocks the merge. The privacy goal is met: neither `frontend/src` nor the built output points to a Google font host any more, so visitors' IP addresses no longer go to Google.

## What was checked
- **Third-party hosts.**
  - The index.html diff removes both preconnect links and the css2 stylesheet.
  - Searching `frontend/src` for googleapis, gstatic, typekit, bunny, jsdelivr, unpkg and cdnjs finds only the guard spec's split list.
  - The built `dist` has no Google host in any html, css or js file.
  - The styles file has 7 `./media/<name>-<hash>.woff2` references, all on our own origin.
- **File integrity.**
  - All 7 woff2 files start with `wOF2`.
  - Their SHA-256 values match the README, the human's files in D:\sahaj-fonts and the hashed copies in `dist/media`. The duplicate Noto latin-ext copy is the same file.
- **Font declarations.** The unicode-range values match Google's css2.css exactly for the latin, latin-ext and devanagari blocks. That gives 19 of Google's 21 blocks; only Caveat's two cyrillic blocks are left out.
- **Licence (OFL 1.1).**
  - Each family has the full licence text with its copyright line.
  - No header declares a Reserved Font Name, so shipping Google's subsets is allowed.
  - The licences ship at `/fonts/licenses/<family>/OFL.txt`.
- **Cookies.**
  - `sahaj_rt` is scoped to `/api/v1/platform/auth` and `sahaj_admin_rt` to `/api/v1/admin/auth`.
  - Both security configs are stateless, so there is no session cookie and no CSRF cookie.
  - e2e test E-26 shows that font requests carry no cookie and no authorization header. E-27 is a positive control.
- **Dependencies.** package.json and the lockfile are unchanged.
- **e2e data.** Only made-up values.

## Findings
| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| 1 | Low | docs/architecture/SAH-41-self-hosted-fonts.md section 7; frontend/README.md | **GL-41-1 is open.** The production static server is not defined yet, so the server rules below are not in place. Without `no-cache` on index.html, clients keep an old shell. A cookie on `Path=/` would go with every static request. | Human / deployment owner | Go-live condition, already recorded. QA adds a header test once the server exists. <br>The server needs: <br>- `Cache-Control: public, max-age=31536000, immutable` on `/media/*`; <br>- `no-cache` on index.html; <br>- no cookie on `Path=/` (no sticky-session or WAF cookie); <br>- `Content-Type: font/woff2` and `X-Content-Type-Options: nosniff`. |
| 2 | Info | frontend/src/assets/fonts/README.md | The files can only be traced back to the human's folder (DEC-026). No agent could compare them with Google's live files. They are now pinned by SHA-256. | Human | Optional: compare one or two hashes with a fresh download |
| 3 | Info | dist index.html (beasties) | **Content Security Policy (CSP) follow-up.** `font-src 'self'` is now possible. `style-src 'self'` alone is not possible yet: beasties adds an inline style and a media-swap script, and Angular adds runtime `<style>` tags. A strict policy needs `ngCspNonce` and a nonce or hash for the beasties script. | Human: hardening backlog | Log it with SAH-39 security finding #3 (there is no CSP yet) |
| 4 | Info | frontend/src/styles/no-third-party-fonts.spec.ts:28 | The source guard only looks for the two Google hosts. The e2e test E-01 catches any off-origin font or stylesheet request, so this is not a gap today. | angular-developer | Optional. Include it if the code review also asks for changes. |
| 5 | Info | frontend/src/assets/fonts/README.md | The README is not shipped, and it lists paths only, not full URLs. | — | None |
