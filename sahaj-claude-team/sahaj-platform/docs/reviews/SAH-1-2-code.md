# Code review · SAH-1 and SAH-2

Branch `feature/SAH-2-home-page`, uncommitted on 9dc8f04. Reviewed by reading only; nothing was run.
Verdict: **CHANGES REQUIRED**.

`B` = `backend/src/main/java/com/sahaj/platform`. `T` = `backend/src/test/java/com/sahaj/platform`.

## Major
1. `B/infrastructure/security/EnquiryRateLimitFilter.java:36`: matches the raw URI, so `/public/sales%2Denquiries` probably skips the rate limit and still reaches the controller. Unconfirmed until a real HTTP test. Fix: match the decoded path or use a handler interceptor; test encoded paths.
2. `T/it/FailClosedIT.java:96`, `:350-355`: membership RLS tests are vacuous. The table is empty, and the insert fails on the foreign key whether or not RLS exists. Fix: insert real `app_user` and membership rows; assert the "row-level security" error.
3. `B/web/ProblemExceptionHandler.java:88-98`: flattens every `ErrorResponseException`, so later domain problems (plan limit, 409s) would lose their type. Fix: rewrite only problems whose type is still `about:blank`; add a test.
4. Whole branch: `validate backend` is not green now that the integration tests exist (no database credentials), and `validate frontend` fails at the `--browsers` flag in `scripts/validate.mjs:28`. Done-means-validated is not met. Needs the human.

## Minor
5. `ProblemExceptionHandler.java:53-56`, `:123-126`: parameter validation returns empty `fieldErrors`; will affect SAH-4 and SAH-7.
6. `B/infrastructure/ratelimit/SlidingWindowRateLimiter.java:44-49`, `:68-75`: when 100,000 keys are held, new visitors are refused; check-then-compute is not atomic; shared mobile (CGNAT) addresses may hit 5 per hour.
7. `B/application/SalesEnquiryService.java:27`, `B/web/SalesEnquiryRequest.java:15-17`: control characters can cause a 500 on a public endpoint. Fix: reject them with a 400.
8. `scripts/db/schemas.sql` (human-owned), `T/it/RowLevelSecurityCatalogIT.java:144-152`: `sahaj_app` gets write access to `platform.flyway_schema_history` through default privileges. Fix: revoke in a migration; add to the grants test.
9. `B/infrastructure/persistence/AuditSanitizer.java:19-20`: exact-name masking lets `mobileNumber`, `phoneNo`, `abhaNumber` through in clear. Fix: regex, add `abha`, mask 10-digit runs.
10. `application.yml`, `RequestLogContextFilter.java`: `requestId` and `tenantId` are never printed in logs.
11. `T/it/RowLevelSecurityCatalogIT.java:240-248`: `anEmptyTenantSettingStillFailsClosed` proves nothing on an empty table.
12. `pom.xml`, `.claude/rules/java-spring.md`: JDBC instead of JPA. Acceptable for SAH-1 and SAH-2 only; decide before SAH-3 and record it.
13. `T/support/LogCapture.java:29`, `T/it/RealHttpSupport.java:229`: flakiness risks (unlocked log list, HTTP/2 upgrade and keep-alive).
14. `EnquiryRateLimitFilter.java:5`, `SecurityConfig.java:6`, `SalesEnquiryService.java:4`: layering inversions.
15. `application.yml:14-15`: header set with an empty proxy regex trusts all private ranges. Fix: fail at start-up.
16. `frontend/e2e`, `.env.example`: `report/` is not ignored; `.env.example` is deleted and needs restoring by the human.

## Nits
17. `compare.component.ts:53`: `₹` prefix differs from the mockup but follows the UI-text rule; accepted deviation.
18. Migrations: drop `if not exists` from later ones.

## Confirmed
- Calculator formulas, presets, prices and text match the mockup.
- `RowLevelSecurityCatalogIT` tests what it claims, apart from finding 11.
- `fieldErrors` reach the client today. Contract paths and status codes match. Deny-by-default holds. No cross-module references.
- Thank-you shows only on a real 201.
- Unexplained BUILD FAILURE: no deterministic cause found. Candidates are finding 13 and concurrent Maven runs in the shared checkout.

## Needs a real database run
All six migrations under Flyway; INSERT-only on `sales_enquiry`; foreign keys under FORCE RLS; policy and error-message text; the migrator TRUNCATE block; the encoded-path bypass.
