# Test plan — SAH-3: Sign up and log in with mobile one-time code

Branch: `feature/SAH-2-home-page` (per DEC-007, 3-agent mode: backend only — no Playwright/e2e,
no security-review tasks here).

Scope: `POST /api/v1/platform/auth/{otp/request,otp/verify,select-clinic,refresh,logout,logout-all}`.

## Environment note (checked before writing a single test)

`mvn -f backend/pom.xml failsafe:integration-test` fails to start the Spring context for **every**
integration test in the module — old and new alike — with:

```
Caused by: org.postgresql.util.PSQLException: FATAL: password authentication failed for user "sahaj_migrator"
```

This is the `sahaj_migrator` credential mismatch already reported to the human; it is not caused
by anything in this change. Confirmed by running the pre-existing `AuditTrailIT` first (12/12
errors, same cause), then the three new SAH-3 test classes (23/23 errors, same cause). See
"Execution status" at the end of this plan.

## Backend integration tests — files

- `backend/src/test/java/com/sahaj/platform/it/AuthFlowTestSupport.java` — shared helpers (drives
  the real `/auth/**` endpoints through MockMvc end to end; reads the one-time code only the way
  the product allows — the dev/test log line — never from a response).
- `backend/src/test/java/com/sahaj/platform/it/SignUpAndLoginIT.java` — AC1, AC2, AC4, AC5, AC11,
  AC12, AC13, AC14, plus negative/boundary cases.
- `backend/src/test/java/com/sahaj/platform/it/OtpSecurityIT.java` — AC3, AC6, AC9.
- `backend/src/test/java/com/sahaj/platform/it/SessionAndClinicSelectionIT.java` — AC10, AC15,
  refresh rotation, reuse detection, refresh-token-cookie-only hygiene, a tenant-isolation case.

Frontend criteria AC7 and AC8 (screen copy, field shapes, countdown) are `angular-developer`'s
component/unit tests, not backend integration tests; AC6's backend half (never in a response) is
covered here, the browser-storage half is the frontend's to prove. No Playwright/e2e test was
written for this story, per the task instruction (3-agent mode, backend only).

## Coverage table

| Acceptance criterion | Test name | File | Status* |
|---|---|---|---|
| AC1 — sign-up creates tenant, owner, membership, consent, audit in one go; next = SETUP | `signUpCreatesTenantUserOwnerMembershipConsentAndAuditTogetherAndOpensSetup` | SignUpAndLoginIT | Compiled, not yet run (env) |
| AC2 — registered mobile, right code → Today for their role (next = APP) | `loginResponseShapeIsAppWithAFreshSessionAndNoClinicChoice` | SignUpAndLoginIT | Compiled, not yet run (env) |
| AC3 — 5 wrong codes locks for 15 minutes with a clear message | `fiveWrongCodesLocksTheMobileForFifteenMinutesWith423` | OtpSecurityIT | Compiled, not yet run (env) |
| AC3 — locked mobile also can't request a fresh code | `whileLockedRequestingANewCodeIsAlsoRefused` | OtpSecurityIT | Compiled, not yet run (env) |
| AC4 — unregistered mobile on Log in → NOT_REGISTERED, no session | `unregisteredMobileOnLoginReturnsNotRegisteredWithoutOpeningASession` | SignUpAndLoginIT | Compiled, not yet run (env) |
| AC5 — removed access can't log in (403) | `aRemovedMembershipCannotLogInAndGets403` | SignUpAndLoginIT | Compiled, not yet run (env) |
| AC5 — deactivated (INACTIVE) access can't log in either | `anInactiveMembershipCannotLogInEither` | SignUpAndLoginIT | Compiled, not yet run (env) |
| AC6 — code never in a response body, header or URL (request + verify) | `theCodeNeverAppearsInAnyResponseBodyHeaderOrUrlAcrossRequestAndVerify` | OtpSecurityIT | Compiled, not yet run (env) |
| AC9 — a code older than 5 minutes is refused, a new one can be requested and used | `aCodeOlderThanFiveMinutesIsRefusedAndANewOneCanBeRequestedAndUsed` | OtpSecurityIT | Compiled, not yet run (env) |
| AC10 — two clinics → CHOOSE_CLINIC, then selecting the right one opens the app | `loginWithTwoClinicsReturnsChooseClinicAndSelectingTheRightOneOpensTheApp` | SessionAndClinicSelectionIT | Compiled, not yet run (env) |
| AC10 — selecting a clinic the user doesn't belong to is refused (403) | `selectClinicWithATenantTheUserDoesNotBelongToIsRefused` | SessionAndClinicSelectionIT | Compiled, not yet run (env) |
| AC10 — no selection token (400) | `selectClinicWithNoSelectionTokenIs400` | SessionAndClinicSelectionIT | Compiled, not yet run (env) |
| AC10 — a garbled/invalid selection token (401) | `selectClinicWithAGarbledSelectionTokenIs401` | SessionAndClinicSelectionIT | Compiled, not yet run (env) |
| AC11 — sign-up again on a mobile with a clinic logs in instead, no second tenant | `signingUpAgainOnAMobileThatAlreadyHasAClinicLogsInInsteadOfCreatingAnother` | SignUpAndLoginIT | Compiled, not yet run (env) |
| AC12 — consent recorded with terms/privacy version and a timestamp | `consentIsRecordedWithTermsVersionPrivacyVersionAndATimestamp` | SignUpAndLoginIT | Compiled, not yet run (env) |
| AC13 — new clinic: FREE plan ending 12 months from creation | `aNewClinicHasTheFreePlanEndingTwelveMonthsFromCreation` | SignUpAndLoginIT | Compiled, not yet run (env) |
| AC14 — sign-up/sign-in/logout each write an audit row with the mobile masked (and clinic name visible) | `signUpSignInAndLogoutEachWriteAnAuditEventWithTheMobileMaskedButTheClinicNameVisible` | SignUpAndLoginIT | Compiled, not yet run (env) |
| AC15 — log out on all devices stops every refresh token in every tenant | `logoutAllRevokesRefreshTokensInEveryTenantTheUserBelongsToNotJustTheOneActiveAtLogout` | SessionAndClinicSelectionIT | Compiled, not yet run (env) |
| Hygiene — refresh token only ever a cookie, never JSON | `theRefreshTokenIsNeverPresentInAnyJsonResponseBody` | SessionAndClinicSelectionIT | Compiled, not yet run (env) |
| Hygiene — refresh rotates; the old cookie value stops working | `refreshRotatesTheCookieAndTheOldValueStopsWorking` | SessionAndClinicSelectionIT | Compiled, not yet run (env) |
| Hygiene — reuse of an already-rotated token revokes every live token for that user+tenant | `reusingAnAlreadyRotatedRefreshTokenRevokesEveryLiveTokenForThatUserInThatTenant` | SessionAndClinicSelectionIT | Compiled, not yet run (env) |
| Negative/400 — invalid mobile on `/otp/request` is 400 with a problem-details body | `anInvalidMobileOnOtpRequestIs400WithAProblemDetailsBody` | SignUpAndLoginIT | Compiled, not yet run (env) |
| Negative/400 — sign-up without consent is 400 with a field error | `signUpWithoutConsentIs400WithAFieldError` | SignUpAndLoginIT | Compiled, not yet run (env) |

\* "Compiled, not yet run (env)" = the test class compiles and JUnit started it; the Spring
context failed to come up for the unrelated `sahaj_migrator` password reason described above, so
the test body never executed. This is not a pass and not a skip — it is reported honestly as
blocked by the environment, matching the pre-existing tests' state.

### Mapping to the mandatory QA checks

- **Another tenant's data is invisible**: `selectClinicWithATenantTheUserDoesNotBelongToIsRefused`
  (a selection token proves who the user is, not which tenants they may enter — a tenant not in
  their own membership list is refused); `logoutAllRevokesRefreshTokensInEveryTenantTheUserBelongsToNotJustTheOneActiveAtLogout`
  proves the reverse isn't true either — a user's own session in tenant B is reachable from an
  action taken while "in" tenant A, because the revoke keys on `user_id` through
  `platform.current_user_id()` (migration 1012), never leaking into *another* user's rows. General
  cross-tenant row isolation for `platform.tenant`/`platform.audit_event` is already proven by the
  existing `TenantIsolationIT` and `AuditTrailIT`; this story adds no table that changes that.
- **A role without permission gets 403**: SAH-3 has no granular role/permission matrix of its own
  (that is SAH-6); the equivalent here is a membership that is no longer allowed in — covered by
  `aRemovedMembershipCannotLogInAndGets403` and `anInactiveMembershipCannotLogInEither`, and by
  `selectClinicWithATenantTheUserDoesNotBelongToIsRefused`.
- **Invalid input gets 400 with a problem-details body**: `anInvalidMobileOnOtpRequestIs400WithAProblemDetailsBody`
  (asserts `application/problem+json`, `type` starting with `https://sahaj.in/problems/`, `status`,
  `fieldErrors`), `signUpWithoutConsentIs400WithAFieldError`, `selectClinicWithNoSelectionTokenIs400`.

### Deliberately not re-tested at IT level (already covered elsewhere)

- Exact 5-minute/15-minute/30-second arithmetic and the failed-attempt counter: unit-tested with a
  mocked `Clock` in `OtpServiceTest` (existing). The IT tests here prove the same rules end to end
  through the real `/auth/**` endpoints and the real database, using direct, committed row edits
  (`ageOutLatestChallenge`, never the production code path) to move a challenge's `expires_at`/
  `last_sent_at` into the past instead of waiting in real time or swapping the `Clock` bean for the
  whole Spring context.
- Transactional rollback-on-failure of the sign-up write (tenant + owner + membership + consent +
  refresh token + audit all in `AuthTransactionalOps.createTenantAndOwner`, one `@Transactional`
  method): proven structurally by `@Transactional` plus the existing `AuditTrailIT`'s general
  "the audit row rolls back together with the change" test for the same transaction manager. No
  fault-injection IT was added to force a mid-transaction failure in this specific method; doing so
  safely would need a seam `java-developer` hasn't exposed (e.g. a test-only hook), so it is flagged
  below rather than built around.
- `AuthControllerTest` (existing, `@WebMvcTest`) already pins the controller's contract shape
  (status codes, cookie attributes, field names) with a mocked `AuthService`; the IT tests here
  exercise the real service and the real database on top of that, not a second copy of the same
  assertions.

## Bugs / follow-ups found

None in the SAH-3 auth code itself from reading `AuthController`, `AuthService`,
`AuthTransactionalOps`, `OtpService`, `JwtTokenService`, `SecurityConfig`,
`ProblemExceptionHandler`, and migrations 1008–1012: the AC14 mobile-masking test is written to
also assert the clinic name (`"name"`) is **not** masked in the same audit row, specifically to
re-check the `AuditSanitizer` fix the task asked about — reading `AuditSanitizer`'s marker lists
confirms `name` is deliberately excluded from both `FULL_REDACT_MARKERS` and `MASK_MARKERS`, so this
is expected to pass once the database is reachable, not expected to surface a bug.

One pre-existing, already-reported item blocks running any of this: **owner = human/db-engineer**,
not a code bug — `sahaj_migrator`'s password does not match `SAHAJ_DB_MIGRATOR_PASSWORD` for
`sahaj_test`, so Flyway cannot connect and the whole Spring context fails for every `*IT.java` in
the module (old and new).

## Execution status

- Compilation: `mvn -f backend/pom.xml test-compile` — **success**, no errors, for all production
  and test sources including the 4 new files (`AuthFlowTestSupport`, `SignUpAndLoginIT`,
  `OtpSecurityIT`, `SessionAndClinicSelectionIT`).
- Execution: `mvn -f backend/pom.xml failsafe:integration-test -Dspring.profiles.active=test`
  - Baseline check, pre-existing `AuditTrailIT`: 12 run, 12 errors, all
    `FATAL: password authentication failed for user "sahaj_migrator"`.
  - New SAH-3 tests: **23 run, 23 errors**, same root cause, same message, confirmed by grep on
    the raw Maven log. 0 passed, 0 skipped, 0 weakened or removed to hide the failure.
  - `node scripts/validate.mjs all` was not run to a clean finish for the same reason (the backend
    leg runs this same Maven build); re-run once the database credential is fixed.

Once `sahaj_migrator`'s password is corrected for `sahaj_test` (or `SAHAJ_DB_MIGRATOR_PASSWORD` is
set to match it), re-run `mvn -f backend/pom.xml verify` and update the "Status" column above from
actual pass/fail results.
