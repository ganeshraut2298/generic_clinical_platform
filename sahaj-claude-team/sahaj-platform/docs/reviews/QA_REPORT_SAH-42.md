# QA report · SAH-42 admin bootstrap registration behind a PIN (server side), ADR-018 Revision 3 update

## Update 11 Nov 2026 (F-1 closed, about 12:45 IST): header link "Back to website" is now 44 px high
**Verdict: 0 failed, 0 expected failures.** The angular-developer added the human-approved rule `.adm-nav .actions a { display: inline-flex; align-items: center; min-height: 44px }` in `frontend/src/app/features/admin/sign-in/admin-sign-in.page.scss` (shared by `/admin/sign-in` and `/admin/register`).
- **Measured (real browser, the built app, both pages, 360 and 1440 px): 153.3 x 44 px.** Before: 153.3 x 40 px. Width unchanged.
- **`frontend/dist`:** not rebuilt. It was built at 12:30, after the SCSS change (12:28), no source file is newer than it, and the built chunk holds the rule (`.adm-nav .actions a { display:inline-flex; align-items:center; min-height:44px }`, found in the admin sign-in and register chunks). The e2e serves `frontend/dist/frontend/browser`.
- **Spec change (`frontend/e2e/admin-register.spec.ts`):** the `test.fail` is gone; the F-1 test is a normal assertion (height at least 43.5 px, the sub-pixel tolerance of the other specs; width between 150 and 157 px). The companion tests (link exists, goes home, wide enough; same size on both pages) are kept. One new test checks the same height and width on `/admin/sign-in`, because no other e2e measured the link there. Now 81 tests per project.
- **Results.** `npm --prefix frontend run lint`: PASSED. `admin-register.spec.ts`, both projects: **162 passed, 0 failed, 0 expected failures** (160 before, plus the new test in each project). The five older specs that touch the admin link or sign-in page (home, self-hosted-fonts, patient-card-setup, home-background, app-shell): **429 passed, 11 skipped (their own viewport skips), 0 failed.** All together: 591 passed, 11 skipped, 0 failed.
- **F-2 stays open** (native input 24 px inside a 44.0 px control; the 44 px rule holds only at the limit). **The page has still never run against the real backend** (manual M-4, M-5 and C1 for the human). Nothing in this pass started the backend, touched port 8080 or any database; no PIN or hash is used or written (throwaway values only). Locks: the backend lock was free; the frontend lock was taken (mkdir plus owner file) and removed at the end.
- **Reported, not mine:** I could not find a spec title in `frontend/src/` that still says the link is 40 px or a "known finding" (searched for "40 px", "known finding" and F-1 under `frontend/src`); the angular-developer should check the title the tech lead meant. The sections below keep the history; where they say F-1 is an expected failure or 160 runs with 2 expected failures, this update replaces them.

## Update 11 Nov 2026 (after DEC-035, about 10:00 IST): the page `/admin/register`, e2e and plan brought up to the human's answers
**(Superseded for F-1 and the counts by the update above.)** **Verdict then: 0 failed. 80 tests x 2 projects (360 and 1440 px) = 160 runs: 158 pass, and 2 (one per project) were the expected failure F-1 below, reported as a finding for the human, not hidden.** Previous: 53 x 2 = 106. `npm --prefix frontend run lint`: PASSED. Related specs together with it (home, self-hosted-fonts, patient-card-setup, home-background, app-shell, admin-register): **589 passed (the 2 expected failures F-1 counted as passed), 11 skipped (the older specs' own viewport skips), 0 failed.**
**The page has never run against the real backend.** Every server answer in this spec is made in the browser (`page.route`); the app served is the production build, so the page does run through the real interceptor chain, but no real unlock, cookie, proxy hop or database was involved. Nothing in this pass touched the backend, port 8080 (PID 15996) or any database; no real PIN was used or is written anywhere (throwaway values only). Locks: backend lock was free (no directory); the frontend lock was taken by mkdir with an owner file and removed at the end.

### What DEC-035 changed in the tests
- **Option C (human: keep as built).** After "Create admin" the page goes to `/admin/sign-in`; the tests cover only that hand-off (URL, every input on the sign-in card empty, nothing kept, no explanation line added there, one plain navigation) and that the register page never shows or holds an authenticator step. The sign-in page's own enrol step is the ADM-1 page's; the full path (type email and password a second time, enrol, sign in) is manual check C1.
- **PIN field `autocomplete="one-time-code"`** (was `off`): the P-2 test now asserts it (and that it is none of `off`, `current-password`, `new-password`, `username`, and that the Unlock card has no text or email field, so there is no login pair to save). Whether Chrome and Edge then stay silent is **manual M-5**: an e2e cannot see a browser's password manager.
- **Closed or off wording:** P-404a and P-404b now say "the app shows what it shows for any unknown address (the home page)". Besides the old checks (home page, no field, no `.msg.err`, nothing kept) they compare the title and the first heading with a **top-level** unknown address, and search the page for feature words ("registration", "switch", "page not found", "404"). While writing this I found that an unknown address **under** `/admin/...` goes to the admin sign-in (the admin routes' own wildcard), not to the home page, so the comparison uses a top-level address.

### Code review findings 8 and 10, security L-1
| Finding | What was added | Result |
| --- | --- | --- |
| code 8 | "a wrong PIN makes NO call to any /admin/auth/refresh URL and no redirect": three wrong PINs, then no `/admin/auth/*` call, no change in the number of `/auth/refresh` calls since the app's own start-up one, URL still `/admin/register`. Same for 401 "ticket gone" on register and for a 423 lock. A control test shows the recorder does see an `/admin/auth/refresh` call when one is made. The earlier note "no test drives the page through the real interceptor chain" is not right for the e2e: the built app is served, so these tests do; only the interceptor unit tests were isolated | pass |
| code 10 | 44 px widened to the controls, every button, every input (by click) and the "Back to website" link | see F-1 and F-2 |
| security L-1 | late 201 after leaving (held, owner left by "Back to website", released: the owner stays at `/`, not `/admin/sign-in`, nothing kept) and late 200 (no ticket kept, coming back starts at the Unlock card with no new call). Both runs: **not observed as cancelled by the route** (a successful `route.fulfill` does not prove delivery, because the page cancels in-flight calls on destroy; the unit tests assert `req.cancelled`). The real assertions hold: no navigation, no ticket kept | pass |

### Findings for the human (no app code was changed by QA)
- **F-1 (CLOSED 11 Nov 2026, see the update at the top: 153.3 x 40 px before, 153.3 x 44 px after; the wording below is the history).** The "Back to website" header link is **153.3 x 40 px** at 360 and at 1440 px: under the 44 px touch-target rule. It is the same markup and style as on the admin sign-in page (ADM-1; the test `the admin sign-in page header link has the same size` proves the sizes are equal), so the register page only inherits it. The single assertion is in its own test marked `test.fail` with this text; when the height is fixed Playwright reports it as "unexpectedly passed" and the `test.fail` line is to be deleted. A companion test checks the link exists, goes home and is wide enough, so the expected failure cannot hide a missing link. Suggested fix (angular-developer, both pages): a minimum height of 44 px on `.adm-nav .actions a`.
- **F-2 (note).** The field control (`.mat-mdc-text-field-wrapper`) is exactly 44.0 px; the native input inside is 24 px (Material outline default). The old 44 px check measured the 64 px `mat-form-field` element, which includes a 20 px hint and error row, so it could not fail. The new check measures the control and also clicks 6 px from its top and bottom edges and requires the input to take focus. Tolerance 43.5 px, as in `admitted-cards.spec.ts` (sub-pixel rounding).
- **F-3 (note).** The Unlock card always shows, whatever the server state; the home page appears only after a PIN is sent and the server answers the empty 404 (security I-1, accepted).
- **Story numbering.** The BA renumbered on 11 Nov 2026 (page re-check N4): in SAH-42-2 the unreadable-body criterion is c12 and the unlock safety-net criterion is c13 (the last bullet). This report and the plan use c13 for the safety net.

### Coverage table for this update (criterion, test, result; 360 and 1440 px both pass unless marked)
Test names are the `describe: title` of `frontend/e2e/admin-register.spec.ts`; the full table with ids is plan section 11.
| Criterion | Test | Result |
| --- | --- | --- |
| SAH-42-2 c1 (reworded: `one-time-code`) | Unlock card first: the PIN field is password-type, says autocomplete="one-time-code" (not "off")...; the Create card keeps its own hints | pass |
| SAH-42-1 c11 (reworded: home page, no separate not-found screen) | The server answers the empty 404: 404 on unlock; 404 on register | pass |
| SAH-42-5 c2 (reworded: goes to `/admin/sign-in`, drops everything) | Created (201): goes to /admin/sign-in; no token, password, ticket or PIN is kept...; the move is one plain navigation | pass |
| SAH-42-5 c9 (reworded: never an authenticator step on this page) | Created (201): the register page never shows or holds an authenticator step... | pass |
| SAH-42-5 c10 (reworded: second typing; sign-in receives nothing) | Created (201): goes to /admin/sign-in... (every input of the sign-in card empty; no explanation line, OQ-42-16) | pass |
| SAH-42-5 c11 (reworded: the existing sign-in step enrols) | not in this spec by DEC-035 (ADM-1 page; server: `BootstrapRegisterIT.theNewAdminCanFinishEnrolment...`, DB, skipped for the human prerequisites); manual C1 | not run here |
| SAH-42-5 c12 (home-page wording only; behaviour kept) | The server answers the empty 404: 404 on register | pass |
| SAH-42-5 c18 (either card; 44 px) | Unlock card first: no horizontal scroll and 44 px targets; A correct PIN moves to the Create card (same helper) | pass |
| SAH-42-5 c18, header link 44 px (finding 10) | Unlock card first: the "Back to website" header link is at least 44 px high and still about 153 px wide (F-1 fixed); and the same on /admin/sign-in (new) | pass (153.3 x 44 px; was 40 px) |
| SAH-42-2 c13 (new; was called c12 before the BA renumbered): 429, 500, 413, 403, unknown status, 400 bad-request on unlock | The server refuses the form (400): "<status> on unlock: Something went wrong. Try again..." (6) | pass |
| SAH-42-5 c20 (new): both passwords empty after a failed create | The server refuses the form (400): "after a failed create (<kind>) both password fields are empty, name and email stay" (9) | pass |
| SAH-42-5 c21 (new): safety-net text on create | The server refuses the form (400): "<status> on register: Something went wrong. Try again..." (5: 429, 500, 413, 403, 418) | pass |
| code finding 8 | Wrong PIN and the lock: a wrong PIN makes NO call to any /admin/auth/refresh URL...; the same for the other 401 and a 423 lock; control | pass |
| security L-1 | A late answer after leaving the page: late 201; late 200 | pass |

### Manual checks added (plan section 10; for the human, none could be run by QA)
- **M-4** (reworded): the real binding cookie `sahaj_admin_bootstrap` on `http://localhost` is stored and sent with the register call.
- **M-5** (new, security M-1, conditions C1/C2): in Chrome and in Edge with password saving on, unlock with a **throwaway** PIN against the real backend and confirm the browser does **not** offer to save the PIN (also on a wrong PIN); remove any entry made.
- **C1** (new, security condition): the page once against the real dev-tools backend at `http://localhost:4200/admin/register` (K2 to K6 through the proxy, cookie, create, land on `/admin/sign-in`, sign in once more, enrol; afterwards `/admin/register` shows the home page). Until C1 is done the page has never run against the real backend.

### Gates (this update)
| Gate | Result |
| --- | --- |
| `npm --prefix frontend run lint` | PASSED |
| `admin-register.spec.ts` alone, both projects | (F-1 update) 162 runs, 162 passed, 0 failed, no expected failure. (Before the fix: 160 runs, 158 passed, 2 expected failures.) |
| `node scripts/validate.mjs frontend` | not run for success: known `--browsers` failure (human's) |
| `node scripts/validate.mjs docs` | FAILED only on `docs/stories/ADM-2.md` ("name files by story ID"), unrelated to SAH-42, not changed |
| `frontend/dist` | not rebuilt: it was newer than every source file and already held `one-time-code` |

Other notes: `frontend/e2e` is gitignored (`git add -f frontend/e2e/admin-register.spec.ts` when committing; QA did not commit). The 40-test `E2E_REGISTER_BASE=http://localhost:4200` variant was not re-run (no dev server was started; the spec was changed only in ways that are origin-independent).

Handoff block for this update: at the end of this file.

## Earlier update 11 Nov 2026 (about 09:00 IST, before DEC-035; superseded where it says "page not found", "autocomplete off" or "authenticator step"): the page `/admin/register`, Playwright e2e
**Verdict: 0 failed.** New spec `frontend/e2e/admin-register.spec.ts` (53 tests x 2 projects, phone 360 px and desktop 1440 px = **106 passed**). The page was built after the sections below were written; the statements below that say "the page is not built" and "no Playwright test" are now out of date (the plan, `docs/test-plans/SAH-42.md` section 11, carries the table: criterion, test, result).
- **How it runs.** There is no earlier admin e2e spec; the e2e config serves the built app (`127.0.0.1:4300`, `serve.mjs`, no `/api`). So the spec follows `home.spec.ts`: `page.route` answers `POST /api/v1/admin/bootstrap/unlock` and `/register` with the answers of `docs/api/admin.yaml` 0.5.0; every other `/api/**` call is recorded and answered 404; a test fails if another bootstrap path is called. **What this proves is what the page does with each answer, not the server.** The backend, database, role `sahaj_admin_definer` and port 8080 were not touched; no SQL was run. The dist build used was newer than every source file (checked) and contained the page.
- **Origin (deviation from the brief, on purpose).** The brief said to use `http://localhost:4200`. The existing e2e config serves the built app on port 4300 and the 4200 dev server is not part of it, so the spec uses the config's origin by default. `E2E_REGISTER_BASE=http://localhost:4200` runs it on the dev server: **40 selected tests (locked, Create card, 404, 201, keyboard groups; 20 per project) passed there too**. Every `/api` call is answered in the browser either way, so nothing can reach the real backend on 8080. The test asserts that each call URL has the page's own origin.
- **Secrets.** Throwaway values only; the real PIN is nowhere. Every test ends with a check that no secret is in a request URL or header, no `Authorization` header is sent, register bodies never hold the PIN, and no secret is in the browser console. Scans after the steps: URL, `document.cookie`, context cookies, `localStorage` and `sessionStorage` keys and values, `history.state`, the DOM, every input value, resource URLs (PIN, wrong PINs, password, ticket, pre-auth token).
- **Coverage against the story criteria** (full table with test names: plan section 11): only the Unlock card first (SAH-42-2 c1) pass; blank PIN, no request (c2) pass; wrong PIN with 4, 3, 2, "1 try left", field cleared and focused (c3, c4) pass; locked: `role="alert"`, minutes text (60, 30, 2, 2, 1, 1, 60 for the retry times 3600, 1800, 90, 61, 60, 1, none), field and button disabled (SAH-42-3 c11) pass; PIN only in the POST body and never kept (c6, c9) pass; correct PIN moves to the Create card with fields in order, autocomplete, focus (SAH-42-5 c1) pass; page checks, all errors at once, focus on the first, password 11/12/64/65, name 121, mismatch (c4, c6, c19) pass; 400 validation keeps the ticket, shows the page's own field texts, same ticket reused (c4, c19, SAH-42-4 c9) pass; 400 bad-request, 429, 500, 413 give "Something went wrong. Try again." (c8, OQ-42-15) pass; 401 returns to Unlock with "Your unlock took too long. Enter the PIN again." and the next unlock's new ticket is the one sent (SAH-42-4 c3 to c6) pass; 423 on register returns to locked (SAH-42-3 c9) pass; 404 on either call goes to the app's not-found behaviour (the `**` route goes home), nothing kept (SAH-42-1 c10) pass; offline text (SAH-42-2 c8) pass; busy state on both buttons (c7, SAH-42-5 c18) pass; 201 goes to `/admin/sign-in`, no token, password, ticket or PIN in storage, cookies, history state, DOM or inputs, going back shows a fresh Unlock card (SAH-42-5 c2, c12 as the fallback) pass; keyboard-only path pass; label, `role="alert"`, `aria-invalid`, `aria-describedby`, 44 px targets, no horizontal scroll at 360 px (SAH-42-5 c15) pass.
- **Gates.** `npm --prefix frontend run lint`: PASSED. New spec: 106 passed, 0 failed. Related older specs run together with it (`home`, `self-hosted-fonts`, `patient-card-setup`, `home-background`, `app-shell` and the new spec; these are the specs that touch the admin sign-in page or the admin link; there is no other admin e2e spec): **535 passed, 11 skipped, 0 failed** (the 11 skips are the older specs' own `test.skip` for a viewport, none in the new spec). `node scripts/validate.mjs frontend` was not run for success (known `--browsers` failure). `node scripts/validate.mjs docs` fails on an unrelated file name, `docs/stories/ADM-2.md` ("name files by story ID"); it is not a SAH-42 file and was not changed.
- **Not tested, said openly.** No axe library exists in the repo and no spec uses one, so accessibility is structural only (colour contrast and a screen reader stay manual). The page against the real backend (needs the database half and `-Pdev-tools`). The real `sahaj_admin_bootstrap` cookie on `http://localhost` without `Secure` (code review item 19): an answer made in the browser is not the server's cookie; added as manual check M-4 in the plan. The authenticator step on the register page: the page uses the fallback (option C, OQ-42-3), so e2e tests the hand-off to `/admin/sign-in` only (SAH-42-5 criteria 11 to 13 are not what the page does; the human chose C).
- **Findings.** None against the page: no test failed against the code. Two failures during writing were mistakes in the spec itself (a test that did not use the fixture so its mocks were not installed, and a `goBack` to `about:blank`); both fixed in the spec, nothing relaxed or removed. `frontend/e2e` is gitignored: `git add -f frontend/e2e/admin-register.spec.ts` when committing (not committed by QA).

Author: qa-engineer. First report 11 Nov 2026 (runs 01:30 to 03:08 IST); **Revision 3 update 11 Nov 2026 (runs about 05:15 to 06:45 IST)**. Story: SAH-42 (stories SAH-42-1 to -6). Mockup screen: Sahaj admin portal `pAdminLogin` (pattern only); the page `/admin/register` is not in the mockup and is not built (DEC-013 item 16), so this report covers the **server side only** and there is no Playwright test.
Plan: `docs/test-plans/SAH-42.md` (every test, preconditions, skip-or-fail rules, named gaps, section 8.4 = ADR-018 Revision 3 tests). Design: ADR-018 (Revisions 1 to 3), `docs/database/SAH-42.md`, `docs/api/admin.yaml` 0.5.0, DEC-034.
The real PIN is unknown to QA and appears in no file: every test uses its own throwaway PIN (`qa-pin-7f3k`, the cost-16 tests a throwaway 40-character PIN hashed inside the test JVM) and its own password, hash and e-mail. `.env` was not read.

## Verdict
**Everything that can run today passes (0 failed). The database half still cannot run and the SQL has still never been executed, now in its Revision 3 form.** The read-only precondition check, made by the tests themselves (queries as `sahaj_migrator` on `pg_roles` and `to_regclass`; nothing created or changed), found on 11 Nov 2026 about 05:40 IST: **role `sahaj_admin_definer` MISSING, admin tables MISSING.** So the five database classes (`BootstrapUnlockIT` 32 methods, `BootstrapRegisterIT` 27, `BootstrapFunctionsIT` 46, `BootstrapBodiesIT` 9, `BootstrapLogsIT` 5 = **119 methods**, up from 83) and 11 more tests (callback 8, cleanup runbook 1, clear-lock runbook 2) are skipped with `PRECONDITION NOT MET`. **Do not approve the database part on this report.** The parked `V2026_11_15_1000` and `V2026_11_15_1010` are still in `backend/src/main/resources/db/pending/admin/`; `scripts/db/init.sql` and `scripts/setup/create-database.mjs` do not mention the role (checked by search, no change by QA).
What Revision 3 changed in the tests, and what now proves it: the Revision 3 shapes compile and the non-database tests pass (guard text, folder name in every jar, canary). The count-before-compare behaviour, the lease, the forced failures and every SQL case are written (section 3) and wait for the prerequisites.
No code, SQL or contract defect was found by the tests that ran. QA changed no application code and no script; no role, database object or row was created; the SQL was not applied by hand; no database was cleaned or reset; port 8080 (PID 15996) was not touched.

## 1. Numbers
| Run | Command | Result |
| --- | --- | --- |
| 1 | `mvn -f backend/pom.xml -Pdev-tools test` | BUILD SUCCESS, 3 min 09 s. Unit tests **4,057 run, 0 failed, 0 skipped** (java-developer's 4,055 plus 2). The DevToolsGuardCallbackIT line 138 failures are not in this phase (they are ITs) |
| 2 | `mvn -Pdev-tools verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false "-Dit.test=Bootstrap*IT,PackagedJar*IT,DevToolsGuardCallbackIT"` | BUILD SUCCESS. **114 ITs run, 0 failed, 0 errors, 26 skipped** (+ 119 methods in 5 classes that skip at class level and are not counted as run). `DevToolsGuardCallbackIT` 39 run, 0 failed (the 9 failures at line 138 are gone), 5 skipped (need the role); `PackagedJarDevToolsBuildIT` 4 of 4; `BootstrapSwitchesIT` 13 of 13; `BootstrapRequestChecksIT` 16 of 16; `BootstrapCallbackIT` 21 run, 8 skipped; `BootstrapPrerequisitesCanaryIT` 3 run, 2 skipped (see below); `PackagedJarDevToolsIT` and `BootstrapAbsentInProductionIT` stand down in a dev-tools build (skipped by design; they ran in the normal build) |
| 2b | same with `"-Dit.test=BootstrapPrerequisitesCanaryIT" -Dsahaj.bootstrap.requireDb=true` | **BUILD FAILURE, as intended**: `theHumanPrerequisitesOfTheDatabaseTestsArePresent` fails and names both missing items. This proves the canary fails instead of skipping; it is a demonstration of the safeguard, not a defect |
| 3 | `node scripts/validate.mjs backend` | see "Gate 3" below |

Gate 3 (`node scripts/validate.mjs backend`, normal build, about 50 min): **PASSED**. Unit tests 3,767 (0 failed, 0 skipped), integration tests 1,938 (0 failed, 0 errors, 26 skipped, the same 26 as before: human prerequisites and "stand down" tests). The normal build does not compile the dev-tools ITs, so this run exercises `PackagedJarDevToolsIT` (production jar: both folder names forbidden), `BootstrapAbsentInProductionIT` and `DevToolsGuardCallbackIT` (new text) but not the SQL, as code review finding 4 says.

Declared test methods now: Switches 13, RequestChecks 16, Callback 12 (21 invocations), ClearLockRunbook 5, CleanupRunbook 1, Unlock 32, Register 27, Bodies 9, Logs 5, Functions 46, Canary 3; production-build ITs unchanged (`BootstrapAbsentInProductionIT` 3, `PackagedJarDevToolsIT` 9, `DevToolsGuardCallbackIT` 39).

## 2. What QA changed for Revision 3 (all in QA folders)
- **Shapes:** `BootstrapDb` (function signatures `begin(uuid)`, `unlock_failed(uuid,inet,text)`, `unlock_succeeded(uuid,text,text)`, `ticket_status(text,text)`, `register(...)`; the state record and `resetState` carry the lease; `expireLease`, `leaseLiveFor`, `clearLease`; forced-failure triggers `failAuditInsertsOf` / `failTicketWrites`, always dropped; `OLD_SHAPES`). New `BootstrapSql` (the five functions as plain calls).
- **Folder `db/bootstrap-dev-tools`:** `PackagedJarDevToolsIT` (production jar: new and old name both forbidden, no entry mentions either), `PackagedJarDevToolsBuildIT` (positive control: new folder only, the old name in no entry, neither folder in `target/classes`), `BootstrapCallbackIT` (class-path check without a database), `BootstrapCleanupRunbookIT`.
- **Guard text:** `DevToolsGuardCallbackIT` line 138 and the bootstrap-table test assert "Dev-tools admin objects (sample admin or bootstrap registration) found in database ..." and a hint that names the cleanup runbook of both features (the 9 failures are gone).
- **R3-I1 to R3-I13, S31 to S38:** section 3. **R3-I9** (`\u0000` and other control characters in `fullName` / `email`: 400 validation, ticket not spent) in `BootstrapBodiesIT` and `BootstrapRegisterIT`. New: register 429 as an IT (a held row lock; the same ticket still works afterwards).
- **Code review finding 2:** after the start attempt `BootstrapDb.requireObjectsAfterStart()` FAILS when the objects are missing, and a leftover Revision 2 shape fails too; `assume` remains only for role, admin tables and `admin_preauth_create` (human items). **Finding 3:** a stray admin row now FAILS with a message that names the cleanup (admin rows are append-only; the cleanup runbook removes the dev-tools objects and grants but deletes no admin row, so the message also names `SampleAdminDb.cleanup` and `validate db`). **Canary** `BootstrapPrerequisitesCanaryIT`: fails with `-Dsahaj.bootstrap.requireDb=true` (or env `SAHAJ_BOOTSTRAP_REQUIRE_DB=true`) when a human prerequisite is missing, a skip with the same text otherwise; a stray admin fails in both modes. **Finding 10** is covered by the java-developer's `BootstrapTransactionProxyTest`; QA did not duplicate it.

## 3. Coverage table: acceptance criterion / ADR-018 test to test to result
Result key: **PASS** = ran and passed; **SKIP(P)** = written, compiled, skipped because a human prerequisite (P1 role, P2 admin tables) is missing; **UNIT** = covered by a unit test, no IT possible; **-** = page, not built. Full mapping with every test name: `docs/test-plans/SAH-42.md` sections 3 to 8.

| Story / criterion | Test | Result |
| --- | --- | --- |
| 1.1 switch absent or not `true`: empty 404 | `BootstrapSwitchesIT.b3...`; `BootstrapAbsentInProductionIT` | PASS (switches); production build PASS in the earlier run |
| 1.2 hash missing or invalid | `BootstrapSwitchesIT.b4...` | PASS |
| 1.3 realm off | `BootstrapSwitchesIT.b5...` | PASS |
| 1.4 profile not dev | `BootstrapSwitchesIT.b2...` | PASS |
| 1.5 non-loopback caller | `BootstrapGateTest` | UNIT |
| 1.5b proxy, cloud, bind address | `BootstrapSwitchesIT.b6...`, `b7...` | PASS |
| 1.6 on and loopback answers | `BootstrapRequestChecksIT.k1...`, `k3...`, `k4...`, `k5...`, `k6...` | PASS |
| 1.7, 1.8, 1.9 closed once an admin exists; admin appears mid-way; two registers at once | `BootstrapUnlockIT.onceAnAdminExists...`, `BootstrapRegisterIT.afterTheFirstAdmin...`, `anAdminCreatedSomeOtherWay...`, `tenRegisterCalls...`, `BootstrapFunctionsIT.s6...`, `s13...`, `s19`, `s20`, `s21` | SKIP(P2) |
| 1.10 token or cookie ignored | `BootstrapRequestChecksIT.aBearerToken...`; `BootstrapUnlockIT.aBearerToken...` | PASS (gate), SKIP(P2) (service) |
| 2.2 blank PIN not counted (peek, `begin` never called) | `BootstrapBodiesIT.unlockAMissingNullEmptyOrBlankPin...` | SKIP(P2) |
| 2.3 to 2.8 wrong PIN, same shape, correct PIN, longer PIN | `BootstrapUnlockIT` | SKIP(P2) |
| 3.1 to 3.10 five wrong PINs lock, restart, global, audit | `BootstrapUnlockIT`, `BootstrapFunctionsIT.s7`-`s11`, `s32`-`s34` | SKIP(P2) |
| 3.7 and R3-I5 parallel tries: count first, one counted, rest wait or 429, never above 5 | `BootstrapUnlockIT.r3I5TenParallel...`, `fiveParallelWrongPinsWithASlowHash...`; `BootstrapFunctionsIT.s22...` (2 tests) | SKIP(P2) |
| 3.x lock log line once with the time; none for a failed 5th judge | `BootstrapLogsIT.theLockIsLoggedOnce...`, `aFailedJudgeIsLoggedWithout...`; unit `BootstrapEndpointTest` | SKIP(P2) |
| 3.y 429 after a 10 s wait (unlock, register) | `BootstrapUnlockIT.aCallThatWaitsMoreThanTenSeconds...`, `r3I4...`; `BootstrapRegisterIT.aRegisterThatWaitsMoreThanTenSeconds...` | SKIP(P2) |
| 4.1 to 4.9 ticket | `BootstrapRegisterIT`, `BootstrapFunctionsIT.s12`-`s17` | SKIP(P2) |
| 5.2 to 5.14 register | `BootstrapRegisterIT`, `BootstrapLogsIT`, `BootstrapFunctionsIT.s14`, `s18` | SKIP(P2) |
| 5.14 database failure answer path | `BootstrapRequestChecksIT.aDatabaseFailureIsABodylessFiveHundred...` | PASS |
| 6.x proof for the reviewer | `BootstrapSupport` (throwaway values), `BootstrapLogsIT`, `BootstrapUnlockIT` | PASS (by construction) / SKIP(P2) |
| 6.7, 2.1, 2.6, 3.11, 5.1, 5.6, 5.15, 6.6 (page) | none | - |
| B1 / T13 production jar has none of it; **old folder name in no jar** | `PackagedJarDevToolsIT` (normal build), `PackagedJarDevToolsBuildIT.theDevToolsJarHolds...` (positive control, new folder only) | PASS |
| B2 to B7, K1, K3 to K6, 413 | `BootstrapSwitchesIT`, `BootstrapRequestChecksIT` | PASS |
| request bodies, ADR-018 D2 table, both endpoints | `BootstrapBodiesIT` (9) | SKIP(P2) |
| **R3-I1** forced audit failure, wrong path: 5 x 500, count 1 to 5, 5th locks, correct PIN 423 | `BootstrapUnlockIT.r3I1...`; `BootstrapFunctionsIT.s36...` | SKIP(P2) |
| **R3-I2** three failed judges then correct PIN: 200, count 0, three UNJUDGED rows | `BootstrapUnlockIT.r3I2...` | SKIP(P2) |
| **R3-I3** forced failure, correct path: 5 x 500, no ticket, 6th is 423 | `BootstrapUnlockIT.r3I3...`; `s37...` | SKIP(P2) |
| **R3-I4** unjudged try: BUSY 10 s then 429 uncounted; after the lease one UNJUDGED row | `BootstrapUnlockIT.r3I4...` | SKIP(P2) |
| **R3-I5** concurrency | see 3.7 | SKIP(P2) |
| **R3-I6** 4 wrong then correct: 200, count 0, no lock, no lease | `BootstrapUnlockIT.fourWrongPinsThenTheCorrectOne...` | SKIP(P2) |
| **R3-I7** judge with another id, no lease, run-out lease: SB004 | `BootstrapFunctionsIT.s31...` (2) | SKIP(P1,P2) |
| **R3-I8** begin while locked with null lease, while a live lease: LOCKED / BUSY, nothing written | `BootstrapFunctionsIT.s9...`, `s33...`, `r3I8...`, `s10c...` | SKIP(P1,P2) |
| **R3-I9** control character in `fullName` / `email`: 400 validation, ticket not spent | `BootstrapBodiesIT.r3I9...`, `BootstrapRegisterIT.r3I9...` (3) | SKIP(P2) |
| **R3-I10** problem `instance` fixed on 400, 401, 423, 429; odd paths never echoed | `BootstrapUnlockIT.r3I10...` (2), `r3I4...`; `BootstrapRegisterIT.r3I10...`, register 429 test | SKIP(P2) |
| **R3-I11** callback re-run over a Revision 2 database | `BootstrapCallbackIT.r3I11...` | SKIP(P1,P2) |
| **R3-I12** clear-lock runbook with a live lease | `BootstrapClearLockRunbookIT.r3I12...` | SKIP(P1,P2) |
| **R3-I13** lost 5th try, lease out, lock on: LOCKED, one UNJUDGED, no LOCKED row, once only | `BootstrapFunctionsIT.s32AndR3I13...`, `BootstrapUnlockIT.r3I13...` | SKIP(P1,P2) |
| D4 count committed and visible during the compare; no row lock held; lease 30 s | `BootstrapUnlockIT.theClaimIsCommittedAndVisibleWhileTheSlowCompareStillRuns...`; `BootstrapFunctionsIT.s6...` | SKIP(P2) |
| D4 no connection leak across T1, compare, T2 | `BootstrapUnlockIT.fifteenWrongAndCorrectPairsAndFifteenBlankPinsLeakNoConnection...` | SKIP(P2) |
| D6 register while locked never counts or leases | `BootstrapRegisterIT.registerWhileLockedNeverCountsATry...`; `BootstrapFunctionsIT.s13b...` | SKIP(P2) |
| S1, S2, S3 callback | `BootstrapCallbackIT` | PASS (name checks, "role missing" message); the rest SKIP(P1,P2) |
| S4 to S26, S30 the five functions | `BootstrapFunctionsIT` (46) | SKIP(P1,P2) |
| S27 production guard (new text and hint) | `DevToolsGuardCallbackIT` (39) | **PASS** (line 138 fixed) |
| S28, S29 runbooks | `BootstrapClearLockRunbookIT`, `BootstrapCleanupRunbookIT` | 3 PASS (no `ON_ERROR_STOP`, other database name, "Nothing to clear"), 2 + 1 SKIP |
| S38 SB002 retired | `BootstrapFunctionsIT.s38...` | SKIP(P1,P2) |
| code review 2, 3 skip-or-fail; canary | `BootstrapPrerequisitesCanaryIT`, `BootstrapSupport.ensureObjectsOrSkip` | stray-admin test: PASS only when the admin tables exist (it skips with a reason when they are missing, and fails with `requireDb=true`; code review N1); prerequisite test skips normally and **fails with `requireDb=true` (run 2b)** |
| code review 10 transactions through the real proxy | `BootstrapTransactionProxyTest` (java-developer) | UNIT PASS (run 1) |
| T13 hash-tool launcher from the packaged jar | `PackagedJarDevToolsBuildIT` | PASS |

## 4. First-run watch list (requested by the db-engineer and the tech lead): outcome
**None could be confirmed: the database does not have the human prerequisites, so none of these ran.** Each is covered by a named test that will answer it on the first real run:
1. Claim committed and visible from a second connection right after `begin` returns: `s6`, `s7`, `s8`, and `theClaimIsCommittedAndVisibleWhileTheSlowCompareStillRuns...` (reads the state from a second connection while a cost-16 compare is still in flight).
2. No connection leak between T1, the compare and T2: the same slow test takes `select ... for update nowait` on the state row from a second connection during the compare (it must succeed at once; 55P03 would mean T1 is still open); `fifteenWrongAndCorrectPairsAndFifteenBlankPinsLeakNoConnection...` makes 46 requests (15 wrong-then-correct pairs = 30, 15 blank PINs, 1 final correct unlock; renamed from `thirtyUnlockCycles...`, code review N7) against a pool of 10 (a leak per request would stall by the 12th).
3. BUSY, not a wait: `s22` (ten parallel `begin` calls must return within 8 s; the nine losers must be BUSY).
4. UNJUDGED while locked is written once: `s32AndR3I13...`, `r3I13...`.
5. Lock ended with a live lease: `s10c...` (lock cleared, count 0, answer BUSY).
6. Time travel inside the check constraints: every `BootstrapDb` move keeps a lease as two columns together and a lock at count 5 with no ticket; `s25b` proves the lease may sit with a lock.
7. Both generations: `BootstrapCallbackIT.r3I11...` (a fresh database is `s1`, S4); the helper upgrades a Revision 2 database by starting the application once.
8. The guard text: **done and passing** (`DevToolsGuardCallbackIT`, 39 run).
9. N4 (code review): `BootstrapRegisterIT.aNonAsciiEmailAndANameWithANoBreakSpaceAreJudgedTheSameByTheDatabaseAndByJava` (non-ASCII e-mail, name with U+00A0; a 22023 would be a bodyless 500). Written, skipped for the human prerequisites.
10. N9 (code review): `@Transactional(NEVER)` on unlock must not 500 on the real transaction manager; any filter or interceptor on the admin chain that opens a transaction would break every unlock. Shown by the first `BootstrapUnlockIT` call of the first real run.

## 5. Findings and requests
1. **[Blocked, human] The admin prerequisites are still missing** (role `sahaj_admin_definer`; the admin tables; therefore also `admin_preauth_create`). Steps: section 6. Until they exist nothing database-side can be verified and the security condition ("do not switch the feature on until the database ITs ran green") is not met.
2. **[Process, human / tech lead] The normal gate does not run the database ITs** (code review finding 4). Put into the merge approval, or add to the gate: `mvn -f backend/pom.xml -Pdev-tools verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false "-Dit.test=Bootstrap*IT,PackagedJar*IT,DevToolsGuardCallbackIT" -Dsahaj.bootstrap.requireDb=true` with **0 failed and 0 skipped among the Bootstrap database ITs** (the flag makes a missing prerequisite a failure through the canary; skips of the "stand down in a dev-tools build" kind and of `DevToolsGuardCallbackIT` for a missing role are the only others allowed).
3. **[Info, java-developer] none against the code.** No test that ran failed. Things the first real run may show (not defects now): `PackagedJarDevToolsIT` also lists `bootstrap-dev-tools` as forbidden in a normal jar, correct; the JSON escape `\uD800` (a lone surrogate) must reach the service as a string, not be refused by Jackson as a body error (R3-I9 expects a 400 validation).
4. **[Gaps, accepted, named in the plan]** (a) rollback after `CREATED`: unit only (`BootstrapServiceTest`, `BootstrapTransactionProxyTest`); (b) register 429 from the advisory-lock wait: unit only (the row-lock wait is an IT now); (c) the clear-lock runbook on a Revision 2 table (refusal text) and the cleanup runbook on a Revision 2 database (S28, S29): not tested, they need the real table in an old shape; (d) K2 (non-loopback socket): unit only; (e) typing the PIN twice into the hash tool in a real terminal: manual M-1; (f) no e2e: the page is not built.
5. **[Info, human] `backend/target-devtools/` is tracked in git** and each Maven run rewrites it; `.env.example` keys (H-BOOT-7) are still open. Locks: taken 05:18, released after gate 3 (see handoff); the human's dev server on port 8080 was not touched.

## 6. What is blocked on the human, and how to re-run
1. Add the role `sahaj_admin_definer` (`NOLOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT`, `GRANT sahaj_admin_definer TO sahaj_migrator`) to `scripts/db/init.sql` and `scripts/setup/create-database.mjs` and run `node scripts/setup/create-database.mjs`.
2. Activate and renumber the parked migrations `V2026_11_15_1000` and `V2026_11_15_1010` (`backend/src/main/resources/db/pending/admin/`), with the db-engineer's request about the `admin\_%` function count.
3. Re-run under the locks: the command of finding 2. The first database class starts the application once with Flyway on, so the callback creates the table and the five functions. **If the SQL has a defect, the failure shows there first** (read the Flyway error before any test result).
4. The tests clean up after themselves (admin rows and audit rows created since a snapshot, the state row back to a clean slate including the lease, both forced-failure triggers dropped first); they need `platform_admin` to be empty and now FAIL (with the cleanup message) when it is not.
5. When the page exists: Playwright e2e and the 360 px layout check.

## Handoff · SAH-42 · qa-engineer (Revision 3 update)
Status: DONE for everything that can run today; database half WRITTEN FOR REVISION 3, COMPILED, SKIPPED (blocked on human prerequisites, re-verified read-only)
Mockup screen: Sahaj admin portal `pAdminLogin` (page `/admin/register` not built, DEC-013 item 16)
Files changed: `backend/src/devtools/test/java/com/sahaj/platform/devtools/it/` (`BootstrapSupport`, `BootstrapHttpBase`, `BootstrapSql` new, `BootstrapPrerequisitesCanaryIT` new, `BootstrapUnlockIT`, `BootstrapRegisterIT`, `BootstrapBodiesIT`, `BootstrapLogsIT`, `BootstrapFunctionsIT`, `BootstrapCallbackIT`, `BootstrapClearLockRunbookIT`, `BootstrapCleanupRunbookIT`, `BootstrapRequestChecksIT`, `PackagedJarDevToolsBuildIT`); `backend/src/test/java/com/sahaj/platform/it/` (`PackagedJarDevToolsIT`, `DevToolsGuardCallbackIT`, `devsample/BootstrapDb`); `docs/test-plans/SAH-42.md`; `docs/reviews/QA_REPORT_SAH-42.md`
Validation: see section 1
Next agents: code-reviewer and security-reviewer

## Handoff · SAH-42 · qa-engineer (DEC-035 update, page /admin/register)
Status: DONE for the e2e and the plan; 0 failed; 2 expected failures (F-1, header link 40 px) kept visible; the page has never run against the real backend (manual M-4, M-5, C1 for the human). **Superseded by the F-1 handoff below.**
Mockup screen: Sahaj admin portal `pAdminLogin` (page `/admin/register` is the signed-off deviation, DEC-013 item 16)
Files changed: `frontend/e2e/admin-register.spec.ts`; `docs/test-plans/SAH-42.md`; `docs/reviews/QA_REPORT_SAH-42.md`
Validation: `npm --prefix frontend run lint` passed; admin-register 160 runs (158 pass + 2 expected failures); related specs 589 passed, 11 skipped, 0 failed; `validate docs` fails only on `docs/stories/ADM-2.md`; `validate frontend` not run (known `--browsers` failure)
Requests to owners: angular-developer (human decides): header link "Back to website" min-height 44 px on both admin pages (F-1); then delete the `test.fail` line in the spec's F-1 test
Next agents: code-reviewer and security-reviewer

## Handoff · SAH-42 · qa-engineer (F-1 closed)
Status: DONE; 0 failed; 0 expected failures; F-1 closed (153.3 x 40 px before, 153.3 x 44 px after); F-2 stays open; the page has never run against the real backend (manual M-4, M-5, C1 for the human)
Mockup screen: Sahaj admin portal `pAdminLogin` (page `/admin/register` is the signed-off deviation, DEC-013 item 16)
Files changed: `frontend/e2e/admin-register.spec.ts` (`test.fail` removed, normal assertion, new test for `/admin/sign-in`); `docs/test-plans/SAH-42.md`; `docs/reviews/QA_REPORT_SAH-42.md`
Validation: `npm --prefix frontend run lint` passed; admin-register 162 passed, 0 failed (both projects); home, self-hosted-fonts, patient-card-setup, home-background, app-shell 429 passed, 11 skipped, 0 failed; `frontend/dist` already held the new CSS (not rebuilt); `validate frontend` and `validate docs` not re-run in this pass (earlier known failures: `--browsers`, `docs/stories/ADM-2.md`)
Requests to owners: none for F-1. angular-developer: the stale spec title the tech lead mentioned was not found by QA under `frontend/src/` (search for "40 px", "known finding", F-1); please check
Next agents: code-reviewer and security-reviewer
