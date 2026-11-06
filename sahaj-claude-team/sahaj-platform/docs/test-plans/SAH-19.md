# Test plan SAH-19 · Plan and modules

Integration tests: `backend/src/test/java/com/sahaj/platform/it/ModulesIT.java` and `ModulesTrialSwitchOffIT.java` (real `sahaj_test` database, MockMvc through the real security chain). The Angular page is covered by its unit tests (mock HTTP); no e2e in this pass.

| AC | Test | Notes |
| --- | --- | --- |
| 1, 2 defaults, plan, menu counts, no row on GET | freshTenantGetsDefaultsAndGetWritesNoRow | |
| 7 every role reads, 401 anonymous | allEightRolesCanReadAndUnauthenticatedGets401 | |
| 2 request, idempotent, one audit | ownerRequestsIpdSecondCallKeepsFirstTimeAndOneAuditEvent | passes (B1 fixed) |
| 2 request 409 when not off | requestIsConflictOnceIpdIsNotOff | passes (B1 fixed) |
| 7 non-owner request 403 | nonOwnersCannotRequestIpd | |
| 8 trial 30 days, audit before/after | ownerStartsTrialWithThirtyDaysAndAudit | passes (B1 fixed) |
| 8 second trial 409 | secondTrialIsConflict | passes (B1 fixed) |
| 8 trial keeps requested_at | trialKeepsAnEarlierRequestedAt | passes (B1 fixed) |
| 7 non-owner trial 403 | nonOwnersCannotStartTrial | |
| 8 property off: 404, ipdTrialAvailable false | ModulesTrialSwitchOffIT.trialEndpointReturns404AndViewSaysNotAvailable, nonOwnerStillGets403 | separate Spring context |
| 8 property off: request still works | ModulesTrialSwitchOffIT.requestPathStillWorksWhenTrialIsSwitchedOff | passes (B1 fixed) |
| 3 expired trial reads readonly, port readonly, 409s | expiredTrialReadsAsReadonlyAndRequestAndTrialAreConflicts | passes (B1 fixed) |
| Port defaults and HRA | portReportsDefaultsAndThenHra | |
| 4 HRA on/off, one row, version up | hraOnThenOffPersistsOneRowAndVersionIncreases | |
| 4 invalid body 400 problem+json | invalidHraBodiesAreRefusedWithProblemDetailsAndNothingIsSaved | missing, null, string, array |
| 7 non-owner HRA 403 | nonOwnersCannotChangeHra | |
| 4 HRA audit, no patient data | hraChangeIsAuditedWithBeforeAndAfterAndNoPatientData | |
| Plan mapping FREE / PRO | planColumnMapsToFreeAndPro | tenant plan updated as sahaj_app |
| 7 RLS: other tenant, API and SQL | anotherTenantHasItsOwnDefaultsAndCannotSeeOrAlterTheRow | |
| RLS: no tenant bound sees 0 rows | withNoTenantBoundTheAppRoleSeesNoRows | |
| No delete grant | appRoleCannotDeleteEntitlementRows | delete and truncate denied |

## Bugs
- B1 (closed): `JdbcModuleEntitlementRepository` bound `java.sql.Timestamp` with `Types.TIMESTAMP_WITH_TIMEZONE` (PSQLException 07006, so `ipd-request` and `ipd-trial` returned 500) and did not truncate to micros. Fixed by java-developer (timestamp binding and micros truncation). The 7 tests that were disabled (six in `ModulesIT`, one in `ModulesTrialSwitchOffIT`) are enabled again and run in the backend gate.
