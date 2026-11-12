# SAH-42 code review · admin bootstrap registration behind a PIN (dev-tools only)

Reviewer: code-reviewer, read-only. Date: 11 Nov 2026. Done by reading the code, SQL and tests. No Maven, Playwright or build was run; test results are QA's. No PIN value was written or quoted.

**Verdict: CHANGES REQUIRED.** The Java server side matches the stories, ADR-018 (Revisions 1 and 2) and `docs/api/admin.yaml` 0.5.0, and no defect was found in it. The SQL was read as code against the real parked schema and no defect was found, but it has never been executed. The verdict rests on four things: the database half cannot be approved yet; the skip mechanism can hide a real failure; the gate does not run the database ITs at all; the branch is not clean per story.

## Findings
| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| 1 | Blocker (database half only) | `docs/database/SAH-42-admin-bootstrap.sql` (byte copy `backend/src/devtools/resources/db/dev-tools-bootstrap/afterMigrate__dev_tools_admin_bootstrap.sql`) | The SQL has never run: the role `sahaj_admin_definer` and the admin tables are missing in `sahaj_test`. 83 database IT methods plus 9 more skip. Untested: S4-S26, S30, every wrong-PIN, lock, ticket and register case at HTTP level, the callback, both callback orders, the cleanup and clear-lock runbooks. The static read found no defect, but that is not a run. | human (prerequisites), then qa-engineer | Do not approve the database part. After the prerequisites exist QA re-runs the command in the QA report section 6. Merge only with 0 skipped among `Bootstrap*IT`. |
| 2 | Major | `BootstrapSupport.java:71-81` (`ensureObjectsOrSkip`) | The app is started with Flyway on so the callback runs, then `BootstrapDb.assumeObjects()` is called. If the callback did not create the objects (location not picked up; a callback that does not fire on a migrate with nothing pending) the whole class is silently skipped, not failed. | qa-engineer | FIX: after the start attempt assert (fail) that `objectsPresent()` is true; keep `assume` only for the human prerequisites (role, tables, preauth function). |
| 3 | Major | `BootstrapHttpBase.java:41, :56` (`assumeNoAdmin`); `BootstrapFunctionsIT.java:55, :60` | One stray admin row (left by another IT such as `DevSampleAdminEndToEndIT` or a failed cleanup) turns every Bootstrap database test into a skip with a green build. Nothing counts skips. | qa-engineer | FIX: skip only for the "no database" case; when an admin exists fail with a message naming the cleanup; add a canary test that fails (not skips) with `-Dsahaj.bootstrap.requireDb=true`. |
| 4 | Major | `docs/test-plans/SAH-42.md`; QA report section 3; `scripts/validate.mjs backend` (human-owned) | `validate backend` runs the normal build; the Bootstrap database ITs exist only in `-Pdev-tools verify`. "validate backend PASSED" says nothing about the SQL, the lock, the ticket or register. | human and tech lead | Write into the merge approval: `-Pdev-tools verify` with `Bootstrap*IT,PackagedJar*IT,DevToolsGuardCallbackIT`, 0 failed, 0 skipped among the Bootstrap database ITs; or the human adds that run to the gate. |
| 5 | Major (process) | branch `feature/SAH-39-home-kinetic-background` | SAH-42 sits on the SAH-39 branch (`git diff main` is 1,122 files and holds SAH-34, 37, 39, 40, 41). The `bootstrap/` package is untracked. A SAH-42-only diff cannot be reviewed or merged. `backend/target-devtools/` is tracked and rewritten by every build. | tech lead (`.gitignore` is human) | Create `feature/SAH-42-admin-bootstrap` from `main` and commit only the SAH-42 paths, with `target-devtools` out; ask the human to ignore it; re-review that diff. |
| 6 | Major (process) | `docs/approvals.md`; ADR-018 status "PROPOSED"; `docs/api/admin.yaml` header | No SAH-42 `stories` or `contract` line; the handoff cites a chat go-ahead only. DEC-013 item 16 and H-BOOT-1..7 are open. | human | Add the lines or record the deviation; answer H-BOOT-1..7 and OQ-42-12/13. |
| 7 | Minor | `BootstrapFlywayCustomizer.java:17` (`db/dev-tools-bootstrap`) beside `DevSampleAdminFlywayCustomizer.java:17` (`db/dev-tools`) | The two locations share a prefix; whether Flyway's scan of `classpath:db/dev-tools` also picks up `db/dev-tools-bootstrap/` depends on trailing-slash handling (unverified). The only test that would catch it (sample on, bootstrap off, no bootstrap objects) is in the skipped group. | java-developer, db-engineer | FIX at no cost: rename the folder so it shares no prefix (for example `db/bootstrap-dev-tools`), or add a non-database unit test listing what Flyway finds per location. |
| 8 | Minor | `BootstrapService.java:197-227` (`validate`) | The name is replaced by the placeholder `"x"` when invalid only so `prepare` can run; control flow is hard to follow (behaviour matches D6 step 2). | java-developer | Extract the name check and the email/password check into two small methods with no placeholder. |
| 9 | Minor | `BootstrapService.validate`; `BootstrapBodies.java:74-98` | A JSON `\u0000` in `fullName` or `email` passes the Java checks and PostgreSQL rejects it: a bodyless 500 (rolled back, ticket unspent) instead of a field error. Only a hand-made call can send it (unverified in `AdminProvisioning`). | java-developer | Treat a control character as a field error, or accept the 500 and say so in the contract. |
| 10 | Minor | `BootstrapServiceTest.java:270-285`; skipped ITs | The central transaction rule (wrong PIN and lock commit; only exceptions roll back; register is one transaction) is proven only by a reflection check on the annotation plus skipped ITs; no non-database test uses the real Spring proxy; rollback after `CREATED` and the register 429 are unit-only. | qa-engineer or java-developer | Add one `ApplicationContextRunner` test with a recording `PlatformTransactionManager`: commit on `WRONG_PIN`, `LOCKED_NOW`, `LOCKED`; rollback on `Closed`, `Busy` and a failure after `CREATED`. |
| 11 | Minor | `docs/database/SAH-42.md:244` and the handoff | The "architect: decide 429 / ADR says 55000" request is stale (Revision 1 settled it); two Error mapping rows are still marked "optional: align". | db-engineer | Align the note. |
| 12 | Minor (story text) | `docs/stories/SAH-42.md` SAH-42-1 AC 9, SAH-42-4 AC 6 | "Two registers with two valid tickets" cannot happen (one ticket slot). | business-analyst | **Done 11 Nov 2026** (also SAH-42-4 AC 3 and the Revision 2 alignment). |
| 13 | Nit | `BootstrapService.java:255-261` | `preAuthSeconds()` is never used. | java-developer | Delete. |
| 14 | Nit | `BootstrapService.java:140`; `BootstrapController.java:102` | `unlockSucceeded` returns the expiry and Java throws it away; the 200 body reports a constant 300. Harmless. | java-developer | Use the returned expiry or drop the return value. |
| 15 | Nit | `BootstrapController.java:100-103, :141-145` | `UNLOCKED` and `CREATED` are handled by `default:`; a future enum value would be treated as success. | java-developer | Explicit `case UNLOCKED` and `case CREATED`. |
| 16 | Nit | `BootstrapService.java:128`; `JdbcBootstrapRepository.java:81` | Fully-qualified `java.util.Arrays` and `java.util.function.Supplier`. | java-developer | Import them. |
| 17 | Nit | `application.yml` (flyway comment); guard SQL raise text | The comment does not mention `db/dev-tools-bootstrap`; the guard message and hint say "sample admin objects" and name only the sample cleanup runbook even for a bootstrap object. The guard is a byte copy of `docs/database/ADR-008-guard.sql`, so change the doc first. | java-developer, db-engineer | One-line wording fixes. |
| 18 | Info | `BootstrapSecurityConfig.java:61-66` | The bootstrap chain includes `AdminNetworkFilter`; if `SAHAJ_ADMIN_ALLOWED_NETWORKS` excludes loopback, a caller who passed the gate gets that filter's answer, not the byte-identical 404. A remote caller never reaches it. | none | Note only. |
| 19 | Info | `BootstrapCookie.java:51-58` | The binding cookie is `Secure` unless `sahaj.auth.refresh-cookie-secure=false`; Chrome and Firefox accept `Secure` on `http://localhost`, Safari may not. | none | Real-browser check when the page exists. |

## Confirmed
- **Boundaries:** main holds only `AdminDevPathFilter` (reserves `/api/v1/admin/bootstrap`, asks every `DevEndpointGate`, fails closed), a comment block in `application.yml` and the guard SQL; nothing in `src/main` refers to `devtools` (`DevToolsAbsentFromMainTest`); `PackagedJarDevToolsIT` forbids the bootstrap folder, the SQL file, any "Bootstrap" entry under the devtools package and the hash tool, and a positive control exists in `PackagedJarDevToolsBuildIT`.
- **Transaction rules:** `BootstrapService` is `@Transactional` with the default rollback rule; WRONG_PIN, LOCKED, LOCKED_NOW and BAD_REQUEST return as values and commit; Closed, Busy and Failed are `RuntimeException`s and roll back; register is one transaction (ticket status, form, `register`, `admin_preauth_create`); the pre-auth insert joins it; a failure after `CREATED` rolls the admin back and leaves the ticket unspent; the BCrypt compare runs while the row lock from `begin` is held.
- **Answer order:** unlock: closed, locked, unreadable body, blank PIN, compare (neither 400 counted); register: closed, locked, ticket (401), unreadable body (400 bad-request), form (400 validation); a body without a valid ticket stays 401; a non-JSON content type is the empty 404 at the gate.
- **Error mapping:** SB001 empty 404; SB002 and SB003 bodyless 500; 55P03 429 with Retry-After 5; other failures message-free and cause-less, SQLSTATE only in the log; 401 `bootstrap-pin-invalid` with `triesLeft`; 423 `bootstrap-locked` with `retryAfterSeconds` and Retry-After; 401 `bootstrap-ticket-invalid` with the cookie cleared; 400 `bad-request` with fixed text; the 5th wrong PIN is `LOCKED_NOW` with 3600 and one WARN line carrying the time; every response has `no-store`; the 404 has the same bytes as the other dev paths.
- **PIN and ticket:** BCrypt `checkpw(byte[])`; input over 64 characters or 72 bytes is a counted wrong PIN checked against a dummy hash; copies zeroed; `toString` prints nothing; tickets are 256-bit, HMAC-hashed with a labelled key (64 hex stored); cookie `HttpOnly; SameSite=Strict; Path=/api/v1/admin/bootstrap; Max-Age=300`, read only when it appears once; no new library.
- **Gate:** K1 to K6 match the ADR; forwarding headers can only narrow; every `X-Forwarded-For` entry must be a loopback literal; Host and Origin local only; `DevToolsChecks` is shared with the sample admin.
- **SQL read as code (not run):** column and constraint names match `V2026_11_15_1000`; `admin_preauth_create` exists in `V2026_11_15_1010`; advisory key equals the sample-admin key; lock order advisory lock then state row in `register` only (no deadlock cycle); the state table's three-way constraints agree with every write path; ACLs end as definer plus `sahaj_app`; self-check counts 8 constraints and 5 functions. Residual risks listed in `docs/database/SAH-42.md` as "unverified until a run": `RETURNS TABLE ("open" ...)` with two `SET` options, the `CONSTRAINT_NAME` diagnostic, `ALTER OWNER` on a re-run, a Flyway `afterMigrate` callback on a migrate with nothing pending, interplay with ADR-008's callback order.

## Mockup match
The `/admin/register` page is not built and is not in the mockup (DEC-013 item 16, rule 1). The server side does not depend on it.

## Could not verify
Anything needing the database (SQL, HTTP flows, callback and callback orders, runbooks, the proxy-based commit of the wrong-PIN count); Flyway location-prefix behaviour (finding 7) and "callback with nothing pending"; the hash tool typed twice in a real terminal (plan M-1); whether a `.env` loader keeps the `$` in a single-quoted hash; `.env.example` (read denied); `application-dev.yml` values; `AdminProvisioning` handling of control characters; K2 (unit only); every test result (QA's, not run here).


---

# Re-check after the fix pass, ADR-018 Revision 3 (11 Nov 2026)

Reviewer: code-reviewer, read-only. Ran only `cmp` and `git status`; no Maven, Playwright or build. Test results are QA's and the java-developer's. Only SAH-42 files of the mixed tree. No PIN or hash quoted.

**Verdict: CHANGES REQUIRED, but no longer because of the Java code.** The Java side and the tests are fixed; no Blocker or Major was found in them. Four earlier items remain open and none belongs to a code owner: the database half is unexecuted, the gate does not run the database ITs, the branch is not clean per story, and the `stories` and `contract` approvals are missing.
**The database half has still never been executed and cannot be approved.** The role `sahaj_admin_definer` and the admin tables are missing in `sahaj_test`; 119 database IT methods plus 11 other tests skip. The static read of the SQL below is not a run. Do not switch the feature on until the skipped ITs have run green.

## Earlier findings 1-19
| # | Status | Evidence |
|---|---|---|
| 1 Blocker (DB half) | **Still open** | SQL unexecuted. |
| 2 | Fixed in code (not proven by a run) | `BootstrapSupport.java:79-94`; only the human prerequisites skip; `requireObjectsAfterStart()` (`BootstrapDb.java:305-312`) fails when the callback created nothing or left a Revision 2 shape. |
| 3 | Fixed in code | `failIfAnAdminExists` (`BootstrapDb.java:297-302`); `BootstrapPrerequisitesCanaryIT` fails with `requireDb=true` (QA run 2b). See N1. |
| 4 | **Still open (human, tech lead)** | The gate does not run the Bootstrap database ITs; the command with `requireDb=true` is in QA_REPORT_SAH-42 section 5. |
| 5 | **Still open** | Still on `feature/SAH-39-home-kinetic-background`; bootstrap folders and new ITs untracked; `backend/target-devtools` 1,735 tracked files. |
| 6 | **Still open** | No SAH-42 line in `docs/approvals.md`; ADR-018 "PROPOSED"; H-BOOT-1..7 unanswered. |
| 7 | Fixed | Folder `db/bootstrap-dev-tools`; no string-prefix clash; old folder exists nowhere. |
| 8, 9, 10 | Fixed | `validate` split with no placeholder; control characters are field errors (IT R3-I9 skipped); `BootstrapTransactionProxyTest` (8 tests). |
| 11 | Fixed, one stale line | `docs/database/SAH-42.md:294` (N5). |
| 12-17 | Fixed | Unused method gone; `unlockSucceeded` void; explicit `UNLOCKED`/`CREATED`; imports; comment and guard wording. |
| 18, 19 | Unchanged (Info) | `AdminNetworkFilter` in the chain; Safari and a `Secure` cookie on `http://localhost`. |

## New code against ADR-018 Revision 3: matches, by reading
`unlock` is `@Transactional(NEVER)`; peek, claim (BUSY poll every 250 ms to 10 s on the injected `Clock`, then 429, not counted), compare strictly after the claim commit and outside any transaction, judge; a judge failure is a bodyless 500 (404 for SB001), never 200 or 401; LOCKED without `locked_until` fails closed. Repository row shapes and parameter order match the SQL. Controller: `@KeepProblemInstance`, every problem through `problem()` with the fixed path. The scrubber change found no leak and no change for other controllers. Byte copies (SQL and guard) are IDENTICAL by `cmp`. `BootstrapTransactionProxyTest` is not vacuous (exact event lists) but proves Spring's decisions only, not real JPA/JDBC commit visibility or connection release (those are in skipped ITs).

## SQL read as code: no logic error found (static read only)
`begin` order (tenant, null id, closed, insert, lock, re-check, lock expiry, lease expiry with one UNJUDGED row, LOCKED, BUSY, single UPDATE; the 5th claim sets count, lock, no ticket and lease together); `unlock_failed` audit only; `unlock_succeeded` resets and lifts the claim's own lock; `ticket_status` read-only; `register` advisory lock then row lock; SQLSTATEs SB001, SB003, SB004 (SB002 raised nowhere); `search_path`, `lock_timeout` on four functions, grants and self-check counts (9 columns, 9 constraints, 5 functions); idempotent upgrade; the audit constraints accept the UNJUDGED row. Unverified until a run: the first execution under Flyway (dollar quoting, `RETURNS TABLE` with two `SET` options, the `CONSTRAINT_NAME` diagnostic, `ALTER OWNER` on re-run), an `afterMigrate` callback with nothing pending, the interplay with ADR-008's callback.

## QA tests
Database ITs match the Revision 3 shapes; time travel stays inside the check constraints; R3-I1..I5 and I13 are consistent with the SQL when walked by hand. Vacuity risks (none blocking): N1; `BootstrapSqlPlacementTest` assumes when `docs/` is absent (the reviewer ran `cmp`); R3-I10 accepts any 400-404; one count-versus-answers assertion is tautological; the 1-second-left lock test accepts 200 or 423.

## New findings
| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| N1 | Nit | `BootstrapPrerequisitesCanaryIT.java:58-61`; `SampleAdminDb.java:152-155` | The stray-admin canary passes when the admin tables are missing (`adminCount()` returns 0) | qa-engineer | FIX (assume the tables exist, or rename). |
| N2 | Nit | `BootstrapConditionsTest.java:313-314` | The prefix rule uses a hard-coded list and a literal, not the real constants or `application.yml` | java-developer | FIX. |
| N3 | Nit | `BootstrapService.java:349-350,370-371` | `NAME_INVALID` branch unreachable; harmless | java-developer | Accept. |
| N4 | Minor (unverified) | SQL `:545-558` vs `BootstrapService`, `AdminProvisioning.java:55-68` | `register` re-checks lower/btrim/`\s` in the database locale; Java uses `Locale.ROOT`, Java `\s`, Java `trim`. A non-ASCII email or a name with U+00A0 could give 22023, a bodyless 500 (rolled back, ticket unspent). No IT covers it | qa-engineer, then db-engineer or java-developer | At the first real run QA adds one IT (non-ASCII email, name with U+00A0); align only if it shows a mismatch. |
| N5 | Nit | `docs/database/SAH-42.md:294` | Still asks the architect to confirm the UNJUDGED timing, already decided in ADR D7 | db-engineer | FIX. |
| N6 | Nit | `BootstrapController.java:106`; `BootstrapService.java:48,422-424` | The 300-second ticket answer is a Java constant duplicating the SQL's 5 minutes; a drift would be invisible | java-developer | FIX (a note beside the constant). |
| N7 | Nit | `BootstrapUnlockIT.java:446-463` | `thirtyUnlockCycles...` makes 46 requests; the QA report says "45 cycles" | qa-engineer | FIX (wording). |
| N8 | Info | `BootstrapService.java:172-193` | A BUSY waiter holds a request thread up to 10 s; loopback and dev only | none | Accept. |
| N9 | Info (unverified) | `BootstrapService.java:134` | `@Transactional(NEVER)` fails every unlock with a bodyless 500 if any filter or interceptor on the admin chain opens a transaction; only the skipped ITs can show it | qa-engineer | Check at the first real run. |
| N10 | Info | `BootstrapTransactionProxyTest` | Real JPA/JDBC commit behaviour, and register replies LOCKED, INVALID, CLOSED after a VALID ticket status, are not covered through the proxy | none | Accept. |
| L5 | Low (doc; from the security review) | `docs/database/runbooks/admin-bootstrap-clear-lock.sql:49` | Stale comment ("holds the row while it checks the PIN") | db-engineer | FIX. |

## Before approval
1. **Human:** create the role and activate the parked admin migrations; add the SAH-42 `stories` and `contract` lines, set ADR-018's status, answer H-BOOT-1..7; create `feature/SAH-42-admin-bootstrap` from `main` with SAH-42 paths only (`target-devtools` out and ignored); put the `requireDb=true` run into the merge approval or the gate.
2. **QA:** run that command; read the Flyway error first on failure; confirm the first-run watch list plus N4 and N9.
3. **Re-review** the SAH-42-only diff and the first-run results.


---

# Re-check of ADR-018 Revision 5 (register 201 without body or token) · 11 Nov 2026

Reviewer: code-reviewer, read-only (no Maven, Playwright, build or database).

**Verdict: APPROVE WITH COMMENTS.** No Blocker or Major. Backend, contract and page agree on the bodyless 201.
**The database half and the `/admin/register` page have never run against a real backend.** BootstrapRegisterIT, BootstrapLogsIT, BootstrapFunctionsIT, BootstrapUnlockIT and BootstrapBodiesIT skip at class level (role `sahaj_admin_definer` and admin tables missing in `sahaj_test`); judged on paper. The page e2e answers the API with `page.route`.

## Checked and sound
- **Backend:** `BootstrapController.java:147-153` 201 with Cache-Control no-store, Pragma no-cache, nosniff, no body, no Content-Type, no Location; cookie cleared at `:148`. No `admin_preauth_create` call and no `BootstrapRegistered` left in the bootstrap code; imports used. `BootstrapService.java:305-316` last DB step is `repository.register(...)`; `@Transactional` (`:270`) so only the commit follows; the "admin created" log is in the controller after the commit; a failed commit leaves as `TransactionSystemException`, wrapped in `Failed(sqlState)` and answered by the 500 handler (`:221-227`: reset, no-store, Pragma, no nosniff, no body). Empty 404 is `AdminDevNotFound`, no-store only. Comments correct.
- **Contract and documents:** `admin.yaml` 0.5.0 201 (`:564-576`) no content with the four headers, 500 `BootstrapServerError` (`:731-739`), 404 `BootstrapNotFound`; `BootstrapRegistered` gone (withdrawal note `:23`). ADR-018 Revision 5 consistent; stories, requirements, `docs/database/SAH-42.md` and runbooks never say the 201 returns a token.
- **Page:** `register(): Observable<void>`; `created()` (`admin-register.page.ts:257-264`) drops everything and navigates to `/admin/sign-in`; unit specs flush `null`/201; e2e asserts status 201, body length 0, no content-type, no `/admin/auth` call, empty sign-in card.
- **Tests on paper:** BootstrapRegisterIT 201 test (`:122-152`) asserts empty body, headers incl. nosniff (`:136`), cleared cookie, zero pre-auth rows (`:151`); enrolment flow (`:252`) login → ENROL → enrol → verify → login → TOTP. BootstrapLogsIT token from `/auth/login`. Unit tests prove order (`BootstrapServiceTest.java:880-893`), failed commit after CREATED (`:933-943`), through the proxy (`BootstrapTransactionProxyTest.java:381-394`), bodyless 201 (`BootstrapEndpointTest.java:482-503`).

## Findings
| # | Severity | File:line | Finding | Owner | Tech lead decision |
| --- | --- | --- | --- | --- | --- |
| 1 | Minor | `BootstrapEndpointTest.java:482-503, 447-460, 596-607` | No running test asserts nosniff on the register 201 (ADR-018:423 says this unit test must); the 500 tests assert only Cache-Control, so Pragma on the 500 and the absence of nosniff are unproven; the register commit-failure path is not driven through MockMvc | java-developer | FIX now |
| 2 | Minor | `docs/decisions/DEC-034/035` | The human's Revision 5 answer is recorded only in ADR-018:14 | tech lead | FIX now (DEC-035 addendum) |
| 3 | Minor | `docs/database/SAH-42.md:219`, ADR-018:430, `BootstrapFunctionsIT.java:613-627` | S14 row and ADR say S14 "keeps" that `admin_preauth_create(..., 'ENROL', ...)` works; the test only calls `admin_login_lookup` | qa-engineer, db-engineer | FIX now (reword to "lookup only; issue proven by the register IT") |
| 4 | Minor (open) | `admin-register.page.spec.ts:268` | Stale title "normal page not found" | angular-developer | FIX now |
| 5 | Nit | ADR-018:13 | Revision 4 still says the server returns the ENROL token; add "(superseded by Revision 5)" | solution-architect | FIX now |
| 6 | Nit | `admin.yaml` 401/423/429 headers (~:604-607) | Header lists omit Pragma and nosniff the code sends (pre-existing) | solution-architect | FIX now (list them) |
| 7 | Nit | `BootstrapRegisterIT.java:131-136` | Content-Length 0 not asserted by the server IT | qa-engineer | FIX with 3 |
| 8 | Info | QA report | Unit count 3,767 → 3,766 not investigated (likely the deleted `BootstrapRegistered` toString test) | qa-engineer | Confirm once |

Earlier items stay open and human-owned: mixed branch, gitignored e2e, `--browsers` flag in `validate frontend`, wording sign-off list, `replaceUrl` and OQ-42-16, M-4/M-5, C1, `target-devtools` tracked in git (L3), prerequisites P1/P2.

## Conditions before merge
1. Human creates role and admin tables in `sahaj_test`; Bootstrap ITs run with `-Dsahaj.bootstrap.requireDb=true`, 0 failed, 0 skipped (first real run of the Revision 5 server tests).
2. C1, M-4, M-5: the page once against the real dev-tools backend at `http://localhost:4200`.
3. Findings 1 to 4 fixed (small); 5 to 8 optional.
