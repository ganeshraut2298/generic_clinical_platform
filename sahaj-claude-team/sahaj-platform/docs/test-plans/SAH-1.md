# Test plan · SAH-1 Project foundation
Mockup screen: Foundation (no screen)
Module: platform · Author: qa-engineer · Date: 1 Nov 2026
Stories: `docs/stories/SAH-1.md` · Design: `docs/database/SAH-1.md` · Contract: `docs/api/platform.yaml`

## Scope and set-up
- Integration tests (`*IT.java`) in `backend/src/test/java/com/sahaj/platform/it/`, run by Failsafe in `mvn -f backend/pom.xml verify` with profile `test`.
- The whole application starts on the `sahaj_test` database: the pool connects as `sahaj_app`, Flyway as `sahaj_migrator`, so the six platform migrations are executed for real by these tests (first run ever).
- Needs PostgreSQL with `sahaj_test` and `SAHAJ_DB_APP_PASSWORD`, `SAHAJ_DB_MIGRATOR_PASSWORD` in the environment (optional `SAHAJ_TEST_DB_URL`).
- Callers are built with `TestTenantSecurity` (no login exists yet). The test-only controllers `TenantProbeController` and `FailureProbeController` (under `src/test`) are the by-id endpoint and the failure/role endpoints.
- Data: every test makes its own tenants with random UUIDs and names. Nothing is cleaned up on purpose: `audit_event` cannot be deleted by anyone, and a tenant row cannot be deleted by `sahaj_app`. Random ids keep tests independent.
- `sahaj_migrator` owns the tables but the tables are FORCE RLS and no policy names it, so it sees no rows of tenant tables. Tests use it only for tables without RLS (`sales_enquiry`), the Flyway history, catalog checks and the trigger test.
- Not covered here: criterion 5 (frontend lint, unit tests, build, theme) belongs to the frontend gate; the Playwright smoke test is a later task (`frontend/e2e/`).

## Coverage by acceptance criterion

| # | Criterion | Test (class.method) | Type |
|---|---|---|---|
| 1 | `validate all` passes | `node scripts/validate.mjs all` | Gate |
| 2 | B asks for A's row by id: 404, no leak | `TenantIsolationIT.aUserOfTenantBGets404ForTenantAById` | Tenant isolation |
| 2 | same, with `X-Tenant-Id` for A | `TenantIsolationIT.sendingTheTenantIdOfAInAHeaderDoesNotLetBReadAsRow` | Tenant isolation |
| 2 | same, with `?tenantId=` for A | `TenantIsolationIT.sendingTheTenantIdOfAInTheQueryDoesNotLetBReadAsRow` | Tenant isolation |
| 2 | header, `tenantId` and `tenant_id` together | `TenantIsolationIT.sendingTheTenantIdOfAInHeaderAndQueryTogetherStillGives404` | Tenant isolation |
| 2 | 404 for another tenant's row is identical to 404 for a missing id | `TenantIsolationIT.theNotFoundAnswerForAnotherTenantIsIdenticalToTheAnswerForAnIdThatDoesNotExist` | Negative |
| 2 | owner still reads own row (control) | `TenantIsolationIT.ownerOfATenantReadsItsOwnRow` | Happy path |
| 2 | list shows only own tenant, also with forged header/query | `TenantIsolationIT.theListOfBShowsOnlyBEvenWhenTheClientNamesTenantA`, `aForgedTenantHeaderOnTheListDoesNotAddTenantsFromElsewhere` | Tenant isolation |
| 2 | B cannot overwrite A's row by posting A's id | `TenantIsolationIT.aUserOfBCannotWriteTenantAsRowEvenByUsingItsId` | Tenant isolation |
| 2 | no login gives 401 problem+json | `TenantIsolationIT.aRequestWithoutLoginGets401AsProblemJson` | Role |
| 3 | a change writes audit_event: actor, `TENANT_CREATED`, `TENANT`, tenant, at | `AuditTrailIT.creatingATenantWritesOneAuditEventWithActorActionEntityTenantAndTime` | Happy path |
| 3 | audit tenant comes from the user, not the request | `AuditTrailIT.theTenantOfTheAuditRowComesFromTheLoggedInUserNotFromTheRequest` | Tenant isolation |
| 3 | mobile masked to last 4, OTP redacted, nothing in clear | `AuditTrailIT.mobileNumbersAreMaskedToTheLastFourDigitsAndOneTimeCodesAreRedacted` | Privacy |
| 3 | audit row rolls back with its change (and commits with it, as control) | `AuditTrailIT.theAuditRowRollsBackTogetherWithTheChange` | Transaction |
| 3 | audit outside a transaction refused | `AuditTrailIT.anAuditEventOutsideATransactionIsRefusedAndWritesNothing` | Negative |
| 3 | audit without a logged-in clinic user refused | `AuditTrailIT.anAuditEventWithoutALoggedInClinicUserIsRefusedAndWritesNothing` | Negative |
| 3 | B cannot see A's audit rows | `AuditTrailIT.tenantBCannotSeeTenantAsAuditRows` | Tenant isolation |
| 3 | B cannot write an audit row for A (RLS WITH CHECK) | `AuditTrailIT.tenantBCannotWriteAnAuditRowForTenantA` | Tenant isolation |
| 3 | UPDATE refused for `sahaj_app` (42501) | `AuditTrailIT.sahajAppCannotUpdateAnAuditRow` | Append-only |
| 3 | DELETE refused for `sahaj_app` (42501), row still there | `AuditTrailIT.sahajAppCannotDeleteAnAuditRow` | Append-only |
| 3 | TRUNCATE refused for `sahaj_app` | `AuditTrailIT.sahajAppCannotTruncateTheAuditTable` | Append-only |
| 3 | trigger stops even the owner (truncate, rolled back) | `AuditTrailIT.theTableOwnerIsAlsoStoppedByTheAppendOnlyTrigger` | Append-only |
| 4 | Modulith boundaries pass (six modules) | `ModularityTests` (existing, java-developer, surefire) | Unit |
| 5 | Angular lint, unit, build, theme | frontend gate (not part of this task) | Out of scope here |
| 6 | `GET /health` with no login is 200 `{"status":"UP"}` | `ErrorsAndRolesIT.healthIsPublicAndReportsUp`, `healthStaysPublicEvenWithAnUnknownBearerToken` | Happy path |
| 7 | 401, 403, 404, 405, 500, 400 are all `application/problem+json` | `ErrorsAndRolesIT.aProtectedPathWithoutLoginGets401AsProblemJson`, `aRoleWithoutPermissionGets403AsProblemJson`, `anUnknownPathForASignedInUserGets404AsProblemJson`, `aWrongMethodGets405AsProblemJson`, `anUnexpectedExceptionGets500AsProblemJsonWithNoStackTraceAndNoMessage`, `SalesEnquiryIT.aMissingOrBlankNameIsRejected...` | Negative |
| 7 | no submitted value or exception message in an error body | `ErrorsAndRolesIT.aNotFoundExceptionNeverEchoesItsMessage`, `aMalformedIdInThePathGets400AsProblemJsonThatDoesNotEchoTheSubmittedValue` | Privacy |
| 7 | deny by default; unknown path without login is 401 | `ErrorsAndRolesIT.anUnknownPathWithoutLoginAlsoGets401...`, `healthDoesNotAcceptWritesWithoutLogin` | Role |
| 7 | role without permission 403, role with permission 200 | `ErrorsAndRolesIT.aRoleWithoutPermissionGets403AsProblemJson`, `theRoleWithPermissionGets200` | Role |
| 8 | no tenant bound: list, by id, audit read nothing | `FailClosedIT.aSignedInUserWithNoTenantListsNoTenantsEvenThoughRowsExist`, `...Gets404ForAnExistingRowById`, `...SeesNoAuditEvents` | Fail closed |
| 8 | no tenant bound: insert refused (HTTP and direct SQL) | `FailClosedIT.aSignedInUserWithNoTenantCannotInsertATenantRow`, `directInsertsWithNoTenantBoundAreRefusedByRowLevelSecurity`, `aMembershipInsertWithNoTenantBoundIsRefused` | Fail closed |
| 8 | no tenant bound: zero rows on tenant, membership, audit_event | `FailClosedIT.directQueriesWithNoTenantBoundReturnZeroRowsOnEveryTenantTable` | Fail closed |
| 8 | no tenant bound: update changes 0 rows, delete refused | `FailClosedIT.anUpdateWithNoTenantBoundChangesNothing`, `aDeleteWithNoTenantBoundIsRefused` | Fail closed |
| 8 | pool of one: no tenant carried to the next transaction | `PooledConnectionIT.theSamePhysicalConnectionCarriesNoTenantFromOneTransactionToTheNext` (same `pg_backend_pid`, A then none then B then none) | Fail closed |
| 8 | pool of one: alternating A, B, none requests | `PooledConnectionIT.alternatingRequestsOfAAndBNeverSeeEachOthersRows`, `thePoolHasExactlyOneConnection` | Tenant isolation |
| 8 | empty `app.tenant_id` is "no tenant", not a cast error; malformed is an error | `RowLevelSecurityCatalogIT.anEmptyTenantSettingStillFailsClosedAndDoesNotRaiseACastError`, `aMalformedTenantSettingIsAnErrorNotAnOpenDoor` | Boundary |
| 9 | current_user is `sahaj_app`; database is `sahaj_test` | `DatabaseRolesIT.theRequestPoolConnectsAsSahajAppToSahajTest` | Config |
| 9 | not superuser, no BYPASSRLS, no CREATEROLE/CREATEDB/REPLICATION | `DatabaseRolesIT.sahajAppIsNotASuperuserAndCannotBypassRowLevelSecurity` | Config |
| 9 | `row_security = off`, DDL, `SET ROLE sahaj_migrator` refused | `DatabaseRolesIT.sahajAppCannotTurnRowLevelSecurityOff`, `sahajAppCannotChangeTheSchemaOrBecomeTheMigrator` | Negative |
| 9 | `sahaj_app` owns no table; platform tables owned by `sahaj_migrator` | `DatabaseRolesIT.sahajAppOwnsNoTableInTheModuleSchemas` | Config |
| 9 | Flyway ran as `sahaj_migrator`, six versions, all successful | `DatabaseRolesIT.flywayRanAsSahajMigratorAndAppliedEveryPlatformMigration` | Config |
| 9 | dev URL is `sahaj_dev`, test URL is `sahaj_test`, users, no password in a file, clean only in test | `ProfileConfigIT` (6 tests, no database needed), `DatabaseRolesIT.theTestProfileUsesTheSahajTestDatabaseForBothConnections`, `theFlywayConnectionIsNotTheRequestPool` | Config |
| 10 | tenant in header/query/body ignored on a write | `TenantIsolationIT.tenantIdInBodyHeaderAndQueryIsIgnoredWhenCreatingARow` | Tenant isolation |
| 10 | no-tenant user cannot choose a tenant by header or query | `TenantIsolationIT.aSignedInUserWithNoTenantCannotChooseOneWithAHeaderOrQuery` | Fail closed |
| 10 | audit row tenant not taken from the request | `AuditTrailIT.theTenantOfTheAuditRowComesFromTheLoggedInUserNotFromTheRequest` | Tenant isolation |

## Database structure checks (design note "Verification" section and the task list)

| Check | Test |
|---|---|
| Every table with `tenant_id`, plus `platform.tenant`: `relrowsecurity` and `relforcerowsecurity` true and at least one policy | `RowLevelSecurityCatalogIT.everyTableWithTenantIdAndTheTenantTableHaveEnabledAndForcedRowLevelSecurityAndAPolicy` |
| No other table lacks RLS except `app_user`, `sales_enquiry`, Flyway history | `RowLevelSecurityCatalogIT.noTableOutsideTheDocumentedExceptionsIsWithoutRowLevelSecurity` |
| Policies apply to `sahaj_app` only, use `platform.current_tenant()`, contain no escape (`coalesce`, `or`, `true`, `is null`); `audit_event` has exactly one SELECT and one INSERT policy | `RowLevelSecurityCatalogIT.everyPolicyAppliesToSahajAppOnlyAndIsBuiltOnTheBoundTenantWithNoEscape` |
| Privilege matrix of `sahaj_app` on the five platform tables (SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER) | `RowLevelSecurityCatalogIT.sahajAppHasExactlyTheDocumentedPrivilegesOnEachPlatformTable` |
| No table granted to PUBLIC | `RowLevelSecurityCatalogIT.noPlatformTableIsOpenToPublic` |
| `current_tenant()` executable by `sahaj_app`, NULL when unbound | `RowLevelSecurityCatalogIT.currentTenantFunctionIsExecutableBySahajAppAndReturnsNullWhenNothingIsBound` |

## Negative, role, boundary, tenant-isolation summary
- Another tenant's data is invisible: `TenantIsolationIT`, `AuditTrailIT.tenantBCannotSeeTenantAsAuditRows`, `PooledConnectionIT`.
- A role without permission gets 403: `ErrorsAndRolesIT.aRoleWithoutPermissionGets403AsProblemJson` (uses `FailureProbeController`; real roles arrive with SAH-3).
- Invalid input gets 400 with problem details: `SalesEnquiryIT` (see SAH-2 plan); malformed id in path: `ErrorsAndRolesIT`.
- Boundaries: empty and malformed `app.tenant_id`; pool size 1; audit append-only at three layers (privilege, no policy, trigger).

## Known limits of this plan
- The trigger layer is tested with TRUNCATE as `sahaj_migrator`. Row-level UPDATE/DELETE triggers cannot be reached by the migrator because FORCE RLS hides every row from it.
- `sahaj_dev` is not started by any test; the dev profile is checked by reading `application-dev.yml`.
- Real roles and login (SAH-3) will replace `TestTenantSecurity`; these tests then need a login helper.

## Result of the last run
See the coverage report in the QA handoff. Status of this run: the 86 database tests did not run against a database (credentials missing); `ProfileConfigIT` (6) ran and passed.
