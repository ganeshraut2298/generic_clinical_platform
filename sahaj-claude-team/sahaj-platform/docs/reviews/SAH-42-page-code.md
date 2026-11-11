# SAH-42 code review · Angular page `/admin/register` (frontend only)

Reviewer: code-reviewer, read-only. Date: 11 Nov 2026. Ran nothing (no build, test or Playwright); test numbers are QA's and the developer's. Diffed against HEAD (the branch is the mixed SAH-39 branch). Only the register page, the route, `bootstrap-call.ts`, both interceptors and their specs, `problem-detail.ts` and its interceptor, and `frontend/e2e/admin-register.spec.ts` were judged. No PIN or hash quoted.

**Verdict: CHANGES REQUIRED, for one human decision (finding 1), not for a code defect.** If the human accepts fallback C in writing, the verdict becomes APPROVE WITH COMMENTS with no code change.
**The page has never run against the real backend** (the database half is unexecuted). Every server answer in the unit tests (`HttpTestingController`) and the e2e (`page.route`) is faked.

## What is met
- **Texts and order:** side panel, both headings, labels, hints, buttons, wrong PIN ("tries" / "1 try"), locked, expired, blank PIN, the three field errors, the password-differ text and the offline text match the story table word for word, sentence case. Field order Full name, Work email, Password, Confirm password; `autocomplete` email `username`, passwords `new-password`, PIN password-type with `off`; only the Unlock card first; locked card `role="alert"` with field and button disabled and the hint hidden. The page never shows the server's wording (tested for 401, 423, 400, 429/500/413).
- **Mockup deviation:** limited to the signed-off reuse of `pAdminLogin`. The only link is "Back to website" (part of that card); no link from the sign-in page (e2e checks it); no nav entry, no extra screen, no guard; Material outline fields (DEC-013 item 8).
- **Secrets (pass):** the PIN is read once and the field is reset in the same step; never in a signal, URL, header, storage, history or console. The ticket is a private plain field dropped on 401, 423, 404, success and destroy (the unit scan has a self-check, so it is not vacuous). Passwords cleared when a call is sent; everything dropped on success; the pre-auth token is never read.
- **Interceptors (no regression found):** `BOOTSTRAP_CALL` defaults to false; both realm interceptors short-circuit on it, unmarked calls take the old path. The marker is needed because in dev `API_BASE_URL` is `http://localhost:8080/api/v1` while the page calls `/api/v1/...` on 4200. `triesLeft` is read only when a number; the empty 404 becomes an `ApiError` 404. `AdminBootstrapApi` uses only the path of `API_BASE_URL`; `app.config.ts` has no diff against HEAD.
- **Error mapping against admin.yaml 0.5.0:** complete (400 validation, 400 bad-request, 401 pin-invalid and ticket-invalid, 423, 429/500/413/403 safety net, 404, offline). Standalone, OnPush, signals, typed forms, HTTP only in the `*.api.ts`.

## Findings
| # | Severity | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| 1 | Major (human decision) | `admin-register.page.ts:243-250`; stories SAH-42-5 criteria 2, 9, 10, 11; ADR-018 D6 | Fallback C (on 201 drop everything and go to `/admin/sign-in`) does not deliver the approved main path (authenticator step on this page: criterion 2 second half, 9, 10, 11) and contradicts ADR-018 D6 "enrolment = the existing sign-in step". It is consistent only with criteria 12 and 13 and OQ-42-3 (a closed browser). The human's sign-off covered the page "exactly as specified", so C is a new deviation. Email and password are typed twice. | human; BA if C accepted; angular-developer if A or B | **ESCALATED: DEC-035.** The tech lead's earlier "the story allows C" was too strong; criterion 12 covers only a closed browser. |
| 2 | Minor | `admin-register.page.ts:328-333`; story texts table last row | The story says the app shows its "normal page not found screen"; the app has none, `**` redirects home. Reveals nothing, but a dev with the switch off or a proxy 404 sees only home, no hint | human to acknowledge; BA to fix the wording | Raise with DEC-035. |
| 3 | Minor | `admin-register.page.ts:249` | After 201 the owner lands on an empty sign-in with no explanation; `navigate` without `replaceUrl` leaves `/admin/register` in history | angular-developer after the human decides the wording | Only if C is chosen. |
| 4 | Minor | `admin-register.page.ts:201, 237` | Subscriptions not cancelled on destroy; a late answer could store a ticket on a destroyed instance or navigate away. Use `takeUntilDestroyed` | angular-developer | FIX now. |
| 5 | Minor | `admin-register.page.ts:316-320`; `admin-register.page.html:35, 78` | On lock the focused PIN input becomes disabled and focus falls to the body (also after a 423 on register); `tabindex="-1"` on the headings is never used. Focus the heading or the alert | angular-developer | FIX now. |
| 6 | Minor | `admin-register-copy.ts:38`; page title; `aria-label` in the template | Texts outside the sign-off table: "That PIN is not right." (used when `triesLeft` is missing), the route title "Create the first admin · Sahaj", the aria-label "About this page", plus the three flagged by the developer ("Unlocking…", "Creating admin…", "Something went wrong. Try again." for 429/500/413/403/unknown; and clearing the passwords after a failed create) | human (wording sign-off) | Add all to the sign-off list in DEC-035. |
| 7 | Minor | `admin-register.page.html:47-55` | Chrome ignores `autocomplete="off"` on a password-type field, so the browser may offer to save the PIN | QA, manual (M-4) | Check in a real browser. |
| 8 | Minor | `e2e/admin-register.spec.ts:165-169`; page spec providers | The wrong-PIN e2e does not assert that no `/admin/auth/refresh` call is made; no test drives the page through the real interceptor chain (only interceptor unit tests) | qa-engineer | FIX with the next QA pass. |
| 9 | Minor (process) | whole branch | Mixed SAH-39 branch; `frontend/e2e` is gitignored so the spec needs `git add -f`; `validate frontend` fails on the known flag (human script) | tech lead, human | As for the rest of SAH-42. |
| 10 | Nit | `e2e/admin-register.spec.ts:~254` | The 44 px check measures form fields and form buttons only, not the inputs or the header link | qa-engineer | With finding 8. |
| 11 | Nit | `admin-register.page.ts:151-157` | Form `valueChanges` subscriptions without `takeUntilDestroyed`; harmless | angular-developer | With finding 4. |
| 12 | Info (condition) | n/a | Never run against the real backend; needed before merge: the real-browser run M-4 (binding cookie on `http://localhost`) and the database ITs run by the human. The page must be opened at `http://localhost:4200/admin/register` | human, QA | As in the handoff. |

## What the e2e cannot prove
Real server answers, headers and content types; the empty 404 being byte-identical; the real binding cookie and its round trip; K4/K5 acceptance of the proxied request; the 5-minute ticket clock, the race and the lock; the enrolment after the redirect or the first sign-in of a created admin. The default e2e run uses the production build on 4300; the development-origin case `BOOTSTRAP_CALL` exists for is covered by interceptor unit tests and the 40-test `E2E_REGISTER_BASE=http://localhost:4200` variant.


---

# Re-check after the fix pass and DEC-035 (11 Nov 2026)

Reviewer: code-reviewer, read-only. No Maven, Playwright or build; test counts are the developer's and QA's. No PIN or hash quoted.

**Verdict: APPROVE WITH COMMENTS (security view not part of this check).** No Blocker or Major. The remaining items are human-owned or small.
**The page has never run against the real backend.** Every server answer is faked (`HttpTestingController` in unit tests, `page.route` in the e2e, which serves the built app so the real interceptor chain runs). The real binding cookie, the dev proxy hop, K2-K6, the 5-minute ticket clock, the lock and the database were never involved. Conditions before a real PIN is typed into the page: M-4 (binding cookie on `http://localhost`), M-5 (Chrome and Edge must not offer to save the PIN), C1 (one run against the dev-tools backend at `http://localhost:4200/admin/register`).

## Earlier findings
| # | Now | Evidence |
|---|---|---|
| 1 | Decided: option C (DEC-035). Page, stories, requirement, ADR-018 Revision 4 and `admin.yaml` agree | `admin-register.page.ts:257-264` |
| 2 | Closed as accepted (404 shows what the app shows for any unknown address, the home page) | residual: N2 |
| 3 | Open, human-owned: `navigate` without `replaceUrl` unchanged and undecided; OQ-42-16 defaults to none | `admin-register.page.ts:263` |
| 4, 11 | Fixed: both calls and all form `valueChanges` pipe through `takeUntilDestroyed` | `:208-219, 248-254`, `:152-163` |
| 5 | Fixed: `lock()` focuses `#reg-step-heading`, also after a 423 on register; asserted in the spec | `:330-337` |
| 6 | Open, human: the seven "Open wording" texts match DEC-035 one for one, marked "built, awaiting sign-off" | stories/SAH-42.md:30-40 |
| 7 | Partly addressed: `autocomplete="one-time-code"` asserted in unit and e2e; whether Chrome and Edge stay silent is manual M-5, not run | `admin-register.page.html:52` |
| 8 | Fixed: e2e "no /admin/auth/refresh call after a wrong PIN, ticket-gone 401 or 423", with a baseline for the app's start-up refresh and a control test | spec 500-543 |
| 9 | Open, process: mixed SAH-39 branch; `frontend/e2e` gitignored (`git add -f`); `validate frontend` fails on the known flag | |
| 10 | Fixed except the header link (F-1) | spec 270-300 |
| 12 | Open: never run against the real backend | |

## Checks
- **Story rewording is faithful to the built page** (SAH-42-5 criteria 2, 9, 10, 11, 12, 13, 18, 20, 21; SAH-42-2 criterion 1 and the safety-net criterion; the Open wording table matches `admin-register.page.html` and `admin-register-copy.ts`), with no leftover contradiction with ADR-018 Revision 4 (D6, D9, the two new threat rows, OQ-42-3), the requirement or `admin.yaml`.
- **`takeUntilDestroyed`:** no leaked subscription; the destroy tests really destroy (`req.cancelled`, a later flush throws, no ticket, no navigation; the first test has a self-check). An aborted unlock may still have been counted by the server (inherent, harmless).
- **`isBootstrapCall`:** exact two paths on the parsed `pathname`; `..` and encoded dots normalised and rejected (tested); suffix and prefix paths rejected; query or dev host in front accepted as intended; a marked call to `/api/v1/admin/me` or `/api/v1/platform/me` still gets its token and refreshes; unmarked behaviour unchanged.
- **e2e:** the F-1 `test.fail` cannot hide a missing link (a companion test asserts visible, `href="/"`, width at least 44, height above 0; an equal-size test against `/admin/sign-in`; a fix shows as "unexpectedly passed"). The late-201 test discriminates; the late-200 e2e is weak (the unit test proves the fix); the no-refresh tests are not vacuous (fixed 300-700 ms sleeps accepted for a negative assertion).

## Findings
| # | Severity | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| F-1 | Minor (human decision) | `admin-register.page.html:15-18`; `sign-in/admin-sign-in.page.scss` (`.adm-nav`); `e2e/admin-register.spec.ts:388-396` | "Back to website" is 153.3 x 40 px at both widths, under the 44 px rule in `.claude/rules/angular.md`. Inherited from the ADM-1 sign-in page (same markup and size). Fix: `min-height: 44px` on `.adm-nav .actions a` in the shared SCSS, then delete the `test.fail` line. Not a mockup change in the rule 1 sense (no label, flow, field order, state or role), and the mockup's own `.btn` is about 42-44 px by estimate, but it edits the shared SCSS of the approved ADM-1 page and visibly changes a screen drawn in the mockup, so the human confirms | human, then angular-developer | Ask the human for a one-line yes; does not hold up SAH-42. |
| N1 | Minor | `core/http/bootstrap-call.ts:14-17` vs `admin-bootstrap.api.ts:26` | The marked paths hard-code `/api/v1`; the page builds its URL from the path of `API_BASE_URL`. If the base path ever changes, the marker silently stops applying and a wrong PIN would trigger an admin refresh and redirect. No test ties the two together | angular-developer | FIX (assert `isBootstrapCall` true for both calls under the default base, or one shared constant). |
| N2 | Nit | `admin-register.page.ts:85-86, 261-262`; `admin-register.page.spec.ts:464` | Stale text: the class comment says "normal page not found"; the `created()` comment cites "criterion 12" (now criteria 2 and 9); a test title still says "normal page not found" | angular-developer | FIX with N1. |
| N3 | Nit | `e2e/admin-register.spec.ts:154-158, 1159, 1180`; QA report line 17 | The catch regex in `World.answer` accepts any error containing "closed"; the annotation "answer delivered and ignored" is not proven when `cancelled` is empty, because the request is aborted in the page | qa-engineer | FIX (narrow the regex; reword the annotation). |
| N4 | Nit | stories/SAH-42.md, SAH-42-2 | Criteria numbering off by one after criterion 8 (unreadable-body criterion is the 12th bullet, safety-net the 13th; the story calls them 11 and 12) | business-analyst | FIX (renumber or name them). |
| N5 | Nit | SAH-42-5 c18 | Says focus goes to the first field or the error; after a lock it goes to the card heading | business-analyst | FIX ("or to the card heading when locked"). |
| N6 | Nit | `core/http/bootstrap-call.ts:24` | The URL host is not checked. Harmless (a pass only removes token and refresh behaviour) | none | Accept. |

## Conditions to close
1. The human: wording sign-off for the seven Open wording texts, OQ-42-16, and `replaceUrl` after create (finding 3). 2. The human: F-1. 3. The human: M-4, M-5, C1; until then type a real PIN only in a private window or a profile with password saving off. 4. N1-N5 at the next pass.
