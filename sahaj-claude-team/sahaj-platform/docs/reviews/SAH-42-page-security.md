# SAH-42 security and privacy review · Angular page `/admin/register` (frontend only)

Reviewer: security-reviewer, read-only. Date: 11 Nov 2026. No Maven, Playwright, build, database or `.env`. Reviewed the untracked page files and `bootstrap-call.ts`, `git diff HEAD` for the route, both interceptors and specs, `problem-detail*`, plus `app.config.ts`, `api-config.ts`, `admin-url.ts`, `proxy.conf.json`, `angular.json`, `index.html`, the e2e spec and one built chunk of `frontend/dist`. `git diff main` is not usable (main has no `frontend/`).

**Verdict: PASS WITH CONDITIONS. No Critical or High findings.**
**The page has never run against the real backend.** All unit tests and all 106 e2e tests answer the two bootstrap calls with mocks. Checked only on paper: the proxied request passing K2 to K6 (including the `xfwd` headers), the `sahaj_admin_bootstrap` cookie without `Secure` on `http://localhost` (manual check M-4), and browser password-manager behaviour.

**Secret literals:** none. No run of 6 to 8 digits in the page files, specs, e2e or the built chunk; no BCrypt prefix in `src`, `e2e` or `dist`; no `PIN_HASH` or `ADMIN_BOOTSTRAP` names in the frontend; test values are clearly made up.

## Findings
| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| M-1 | Medium | `admin-register.page.html:47-55` | **The browser may offer to save the PIN as a password.** The field is `type="password"` with `autocomplete="off"`; Chrome, Edge and Firefox ignore `off` on password fields, and Chrome offers to save when a password form disappears after a successful request (the Unlock card is replaced by the Create card). Clearing the field in code does not prevent it. If accepted, the PIN goes into the browser's password store for `localhost`, possibly synced to a cloud account, which breaks "never in storage" in practice. Fix: `autocomplete="one-time-code"` (Chromium does not offer to save those), then check by hand in Chrome and Edge next to M-4. The story says "no autofill hint", so the text change needs the human. | angular-developer, business-analyst (story text) | **Human decision in DEC-035 (recommended: accept `one-time-code`).** |
| L-1 | Low | `admin-register.page.ts:201-209, 237-240, 204, 249, 332` | Calls in flight are not cancelled on destroy (no `takeUntilDestroyed`). A late 200 writes the ticket back into a destroyed instance; a late 201 or 404 navigates the user away from where they went. Pipe both calls through `takeUntilDestroyed` and add a unit test that destroys the page during the call. | angular-developer | FIX now. |
| L-2 | Low | `admin-register.page.spec.ts:499` | The test titled "...including a destroyed page" never destroys the component; nothing tests that ticket and form values are cleared on destroy (`DestroyRef.onDestroy` at page.ts:158). Add the test. | angular-developer | FIX now. |
| L-3 | Low | `auth.interceptor.ts:37`, `admin-auth.interceptor.ts:31-33`, `bootstrap-call.ts:9` | `BOOTSTRAP_CALL` bypasses both interceptors for any URL. It fails safe (no Authorization header, no refresh, no redirect), but a call marked by mistake later would lose the 401 handling. Honour the marker only for `/api/v1/admin/bootstrap/unlock` and `/register`, or add a guard test that only `admin-bootstrap.api.ts` uses it. | angular-developer | FIX now (the guard test, or the path check). |
| I-1 | Info | `admin.routes.ts:16-22`; `admin-register-copy.ts:13-14` | Production builds include the route, headings, endpoint path and the hint "Works only on your own computer while the switch in .env is on." The bundle reveals the feature exists, never whether it is on or whether an admin exists (only the server's empty 404 says that, then `**` goes home like any unknown address). Accepted in OQ-42-11 and ADR-018 D9. | none | Accept. |
| I-2 | Info | `admin-register.page.ts:148` | The ticket is readable by JavaScript by design (body half; the binding half is HttpOnly). `private` is compile-time only; in a dev build DevTools can read it. Acceptable per ADR-018 D5. | none | Accept. |
| I-3 | Info | `admin-register.page.ts:249` | After 201 the page navigates without `replaceUrl`; `/admin/register` stays in history. No secret in the URL or `history.state`; Back loads a fresh page whose next call gets the 404 and goes home. | none | Accept. |

## Checked and found sound
- **PIN:** only in the POST body (`admin-bootstrap.api.ts:29`), read once and the field cleared before the call; not in component state, URL, query, header (the spec asserts no headers), storage, history or console; no copy button; masked; no screenshots in e2e. The only issue is M-1.
- **Ticket:** one private field while the Create card is open, never in the template; dropped on 201, 401, 423, 404, destroy and reload; kept on 400, 429, 500, offline (not spent). Exception: the late-response case (L-1).
- **Passwords, email, errors:** both password fields cleared at send; name and email cleared on every exit path; `problem-detail.interceptor.ts` logs nothing and copies only `type`, `title`, `status`, `detail`, `retryAfterSeconds`, `triesLeft` (number only) and `fieldErrors`; server titles, details and field messages are never shown; every bootstrap call has an error handler.
- **Origin and CSRF checks:** `AdminBootstrapApi` uses only the path of `API_BASE_URL` (spec at `api.spec.ts:58`), so in dev the browser sends `Origin: http://localhost:4200` and `Sec-Fetch-Site: same-origin`; the JSON body needs no preflight (K1 answers `OPTIONS` with a 404); the proxy has `xfwd: true` (K3 and K4 should pass, unverified against the server).
- **XSS and dependencies:** interpolation only, no `innerHTML`, no `bypassSecurityTrust*`, no third-party script; `package.json` and `package-lock.json` unchanged.
- **Fallback C:** `preAuthToken` is never read, stored or passed in navigation state; `dropEverything()` then a plain navigate to `/admin/sign-in`.
- **Production bundle:** needs nothing from the dev-tools build; the chunk has no digit runs, `console.*`, storage calls, `innerHTML` or source-map comment.

## Conditions
- **C1:** before the switch is turned on, run the page once against the real dev-tools backend at `http://localhost:4200/admin/register`: confirm K2 to K6 pass through the proxy, the cookie works (M-4) and the PIN is not offered for saving (M-1).
- **C2:** fix M-1, or the human accepts it, before the human types a real PIN into the page.
- **C3:** the server-side conditions in `docs/reviews/SAH-42-security.md` still apply (switch off until the `requireDb` database ITs pass with 0 failed and 0 skipped; M2 new PIN).
- L-1 to L-3 are recommended and do not block.


---

# Re-check after the fix pass and DEC-035 (11 Nov 2026)

Reviewer: security-reviewer, read-only (no Maven, Playwright, build, database or `.env`; no files written). Also read `BootstrapController.java` and `BootstrapRegistered.java` and the built chunk in `frontend/dist`.

**Verdict: PASS WITH CONDITIONS. No Critical or High findings.**
**The page has never run against the real backend.** Checked only on paper: the proxied request passing K2-K6, the binding cookie on `http://localhost` (M-4), whether real browsers stay silent about the PIN (M-5).
**Secret literals:** none. No 6-8 digit runs in the page files, specs, `bootstrap-call*` or `dist` (the e2e's only digit run is the sequential filler in the made-up `TICKET_A`/`TICKET_B`); no BCrypt prefix, long hex, `PIN_HASH` or `ADMIN_BOOTSTRAP`; all test values are clearly fake. The built chunk contains `one-time-code`, no `console.*`, storage calls, `innerHTML` or source-map comment.

## Earlier findings
| # | Now | Evidence |
|---|---|---|
| M-1 | Fixed in code; the browser result still needs manual check M-5 | `admin-register.page.html:50,52`: `#reg-pin` is still `type="password"` and has `autocomplete="one-time-code"`; the only such element; the Create card keeps `username` / `new-password`; nothing says `off` |
| L-1 | Fixed | `takeUntilDestroyed(this.destroyRef)` at `admin-register.page.ts:210, 250` and the `valueChanges` at `:154,158,162`; a late 200 cannot set `ticket`, a late 201 or 404 cannot call `created()` or `showPageNotFound()` |
| L-2 | Fixed | Spec tests at lines 528, 551, 570 call `harness.fixture.destroy()`; they assert `req.cancelled === true`, that `flush` throws, and that `navigate`/`navigateByUrl` were never called; the first has a self-check |
| L-3 | Fixed | `bootstrap-call.ts:20-25`: honoured only if set and the normalised path is exactly one of the two bootstrap paths; `..` and `%2e%2e` collapse as in the browser; encoded, `;params`, upper-case and extra-segment variants fail closed; another host with the same path is honoured, which only removes token and refresh (nothing leaks); the only setter is `AdminBootstrapApi`; unmarked calls return false before parsing; no Authorization on bootstrap calls |
| I-1..I-3 | Unchanged, accepted | route in production builds; ticket readable by JavaScript; no `replaceUrl` after 201 |

## The unused ENROL pre-auth token in the register 201
`BootstrapController.java:150` returns `BootstrapRegistered("ENROL", preAuthToken, 300)`: a live single-use ENROL credential for 5 minutes (whoever holds it can call `/auth/totp/enrol` and `/auth/totp/verify`, bind their own authenticator to the first admin and receive the recovery codes). The page discards it. Readers: any script on the `localhost:4200` origin (no XSS sink exists), browser extensions with access to localhost, DevTools Network tab and saved HAR/trace files, a loopback sniffer. Not logged: the server logs `admin created adminId={}` only, `BootstrapRegistered.toString()` prints `BootstrapRegistered[]` (asserted in `BootstrapBodiesTest:147`), the 201 has `no-store`; the frontend logs nothing. **Marginal risk: low.** Every observer who can read the 201 response can also read the request, which holds the password in clear, and with that password can call `/auth/login` and get their own ENROL token while the admin is not yet enrolled. If someone else enrols first, the owner notices (their sign-in asks for a code instead of offering enrolment). Dropping the token is worth doing as hygiene (least privilege; one fewer live credential nobody consumes; one fewer failure path `admin_preauth_create`), cheapest now because `admin.yaml` 0.5.0 is not yet approved.

## Findings
| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| L-4 | Low | `BootstrapController.java:150`; `docs/api/admin.yaml` ~538-544 | The register 201 returns a live ENROL pre-auth token that no client uses (scenario above) | human decision; then architect, java-developer, QA | **Escalated to the human (question with DEC-035 follow-ups).** Fix: answer 201 with no body or `{next: SIGN_IN}` and skip `admin_preauth_create`, before `contract` is approved; otherwise accept as the ADR-018 threat row records. |
| I-4 | Info (residual of M-1) | `admin-register.page.html:52` | Saving behaviour not verified. Chromium treats `one-time-code` fields as non-credential, so a lone one should give no save prompt in Chrome; Edge shares the code but adds heuristics (unverified); Firefox may still offer "Save password for localhost" for a lone `type=password` field; password-manager extensions (Bitwarden, 1Password, LastPass and others) often ignore `autocomplete` | human | Manual check M-5 in Chrome and Edge, plus Firefox and any extension in use; until then use a private window or a profile with saving off. The Create card will offer to save email and password (the real admin credential, not the PIN). |
| I-5 | Info | `admin-register.page.ts:210, 250` | Cancelling on destroy only drops the answer; the server still finishes. A register cancelled mid-flight may still create the admin while the owner sees nothing; next visit the owner gets the 404 and must know to sign in at `/admin/sign-in` | none | Optionally mention in the usage note. |
| I-6 | Info | `bootstrap-call.ts:15-18` | `BOOTSTRAP_PATHS` hard-codes `/api/v1` while `AdminBootstrapApi` takes its path from `API_BASE_URL`; if the base ever changes, the marker silently stops working (visible, not a leak) | angular-developer | Same as code review N1: FIX. |
| I-7 | Info | `e2e/admin-register.spec.ts:154-158, 1138-1180` | The late-answer e2e tests accept a cancelled call or a delivered-and-ignored answer, so they do not prove cancellation; the unit tests assert `req.cancelled` | qa-engineer | Same as code review N3. |
| N-1 | Nit | `core/http/bootstrap-call.spec.ts` | No case for `%2e%2e`, upper case, or a different host with the same path (honoured by design) | angular-developer | FIX (write the intended behaviour down). |

No new issue of security weight from the fixes (focus on the `h2` is harmless; `dropEverything` runs on every exit path and on destroy; no dependency changes).

## Conditions (updated)
- **C1 (open):** run the page once against the real dev-tools backend at `http://localhost:4200/admin/register`; confirm K2-K6 through the proxy and the cookie (M-4).
- **C2 (code part met; browser part open):** M-5 must pass before a real PIN is typed into a normal browser profile; until then a private window or a profile with saving off.
- **C3 (unchanged):** the server-side conditions in `docs/reviews/SAH-42-security.md` (switch off until the `requireDb` database ITs pass with 0 failed and 0 skipped; M2 new PIN).
- **L-4:** recommended, does not block; the human decides.

---
**L-4 status (11 Nov 2026):** RESOLVED in the documents by ADR-018 Revision 5 (human answered yes in chat): the register 201 has no body and carries no pre-auth token; the first sign-in at `/admin/sign-in` issues its own ENROL token (ADR-003). Code, tests and the re-check follow (java-developer, angular-developer, QA, both reviewers).
