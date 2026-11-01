# Security review · SAH-1 and SAH-2

Branch `feature/SAH-2-home-page`, uncommitted on 9dc8f04. Reviewed by reading, plus two isolated checks of framework behaviour outside the repo. No database or integration test was run.
Verdict: **FAIL**. 1 Critical, 1 High, 7 Medium, 7 Low.

## Critical
**C1. Real superuser password in git history (DEC-003, human-owned).** `.env.example` line 6 in commits 3f59573 and 9dc8f04, on `main` and all three feature branches. Local `origin/main` is at e6331b8 and no remote-tracking branch contains 3f59573, so as of the last fetch it is not on GitHub, but `origin` is a GitHub remote and a plain push of `main` would publish it. Fix: rotate, rewrite history before any push, restore `.env.example` with placeholders, add a secret scan.

## High
**H1. Percent-encoded path bypasses the enquiry rate limit.** `EnquiryRateLimitFilter.java:36`. In an isolated run, `%73ales-enquiries` and `sales%2Denquiries` reached the handler without the limiter. Full security chain not exercised. Fix: apply the limit after handler resolution; add regression tests.

## Medium
- **M1.** `SlidingWindowRateLimiter.java:44-48`: a full key map locks out new visitors, reachable with two IPv6 /48s, and each refusal sweeps the map. Fix: bounded eviction, /48 limit, timed sweep, global write cap.
- **M2.** `application.yml:14-15`: header set with an empty proxy list is safe from spoofing but keys every visitor to the proxy, so the whole site shares 5 enquiries an hour. Fix: fail at start-up. Keep the empty default, because removing it would trust forged headers.
- **M3.** `scripts/db/schemas.sql:11`: `sahaj_app` can probably write `flyway_schema_history`. Fix: revoke in a migration and test.
- **M4.** `AuditSanitizer.java:19-20`: exact-match denylist lets names and identifiers through into an append-only table. Fix: allowlist per caller, or at least substring matching.
- **M5.** No request body size limit on public endpoints. Fix: about 4 KB, 413 with a problem body.
- **M6.** `enquiry-sheet.component.html:13-70`: no privacy notice, undecided retention, no check on whose number is entered. **Human decision.**
- **M7.** `frontend/src/index.html:13-18`: Google Fonts sends visitor IP to Google. Fix: self-host, which needs new font packages. **Human decision.**

## Low
- **L1.** `app_user` is readable and updatable across tenants by `sahaj_app`; becomes High if SAH-3 or SAH-6 ships a loose lookup.
- **L2.** Migrator password is in the app's environment; separate it in production.
- **L3.** App can set `at` and `actor_user_id` on audit rows.
- **L4.** Control characters reach PostgreSQL and give a 500; export formula risk.
- **L5.** CSRF is off; SAH-3's refresh cookie needs SameSite=Strict and an Origin check.
- **L6.** JPA would not join the JDBC tenant-binding transaction; extend binding to `JpaTransactionManager` before any entity.
- **L7.** 18 moderate npm findings, dev-only; no Maven CVE scan; verify Spring Boot 3.5 support dates; no CSP yet; test mobile numbers are valid-format.

## Sound by reading (needs a real run)
Tenant binding and fail-closed policies; audit append-only layers; mobile absent from logs and responses; no actuator or CORS exposure; no unsafe frontend APIs; dev proxy and e2e server cannot reach production.

## Must be done to pass
1. C1 (human). 2. H1. 3. Integration tests on `sahaj_test`, including the M3 privilege check. M1 to M7 fixed or accepted by the human.
