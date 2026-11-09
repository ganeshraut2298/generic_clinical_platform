# SAH-39 security review · Homepage ambient background

Reviewer: security-reviewer, read-only. Date: 8 Nov 2026.

**Verdict: PASS.** There are no Critical, High or Medium findings and no merge conditions.

## Verified
- No third-party code or new dependency is added.
- No inline script is added. The app has no CSP at all yet.
- Pointer data and telemetry: none is stored, sent or logged.
- DOM injection: none. The only DOM write is a `data-state` attribute drawn from a fixed set of values.
- Flags cannot be set from the URL or from browser storage. Inputs are clamped.
- The layer mounts only on the public homepage (`home.page.ts:43`, route `''`). No signed-in screen shows it, so no patient data appears under it.
- Accessibility: the layer is `aria-hidden` and has `pointer-events: none`. Reduced motion and touch follow the user's setting live. Nothing flashes, so WCAG 2.3.1 is not at risk.
- The animation loop stops when the layer is idle or destroyed, and all listeners are removed.
- `E2E_NO_KINETIC` is test-server only and never reaches a production build.

## Findings
| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| 1 | Low | frontend/e2e/serve.mjs:30 | A malformed URL makes `decodeURIComponent` throw, which crashes the local test server. Test and localhost only. | qa-engineer | Fix with the next QA touch |
| 2 | Info | frontend/src/index.html:12-17 | Pre-existing: Google Fonts sends each visitor's IP to Google. This matters for DPDP and "data stays in India". | angular-developer | HUMAN: follow-up story to self-host the fonts (new backlog item) |
| 3 | Info | whole app | There is no CSP. When one is added, Angular styles will need nonces. | solution-architect | Backlog: platform hardening |
| 4 | Info | kinetic-background.component.ts:47-50, 588 | The `data-surface` and `data-dot-exclude` selectors only affect visuals. | — | None |
