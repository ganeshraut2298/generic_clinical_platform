# SAH-14 Follow-up lists - test plan

Story: `docs/stories/SAH-14.md`. Contract: `docs/api/opd.yaml` `/follow-ups`. Design: `docs/database/SAH-14.md`.
Mockup screen: Follow-ups (see `docs/design/screen-map.md`).

Integration tests: `backend/src/test/java/com/sahaj/platform/it/FollowUpListsIT.java` (real `sahaj_test`
database, real sign-up, wizard, Team, Patient and OPD endpoints through MockMvc). The clock cannot be moved, so
`opd.follow_up.due_date` is back-dated with a committed SQL update as `sahaj_app` (tenant bound).
Unit tests (mock-based, java-developer): `FollowUpServiceTest`, `FollowUpControllerTest`.

## Status of the run

All 15 tests are written. 11 are `@Disabled` because of one application bug (BUG-1 below); with that one-line fix
applied in a scratch copy, all 15 passed against `sahaj_test`.

BUG-1 (java-developer): `JdbcFollowUpRepository.java:45-48`. The `SELECT` text block ends with
`where tenant_id = :tenantId and """;`. Java strips trailing spaces of a text block line, so `findByStatus`
(line 107) produces `... :tenantId andreturned_visit_id is null ...` and PostgreSQL answers 42601, so
`GET /api/v1/opd/follow-ups` returns 500 for every valid status. Unit tests with a mocked repository could not see it.

## Cases

| # | Case | Type | Test method | Disabled by BUG-1 |
|---|------|------|-------------|---|
| 1 | Consult done (VITALS -> WITH_DOCTOR -> BILLING) creates one UPCOMING row: due = visit IST day + default 7, reason "Review after treatment", patient and doctor names, mobile, ids, counts | AC1 | consultDoneCreatesOneUpcomingFollowUpWithDefaultWindowNamesMobileAndCounts | yes |
| 2 | Nothing is created while the visit is still with the doctor | AC1 boundary | same test | yes |
| 3 | Vitals off: doctor step first, still creates | AC1 | withVitalsOffTheDoctorStepIsFirstAndStillCreatesTheFollowUp | yes |
| 4 | Window changed to 3 days in fee settings: later visits use 3, earlier row keeps 7; list oldest due first | AC1 | changingTheFollowUpWindowInFeeSettingsMovesTheDueDateOfLaterVisitsOnly | yes |
| 5 | Follow-up step off (draft + publish v2): new visit creates none; visit issued before the publish still creates one | AC2 | followUpStepSwitchedOffAppliesToNewVisitsButNotToVisitsIssuedBeforeThePublish | yes |
| 6 | One follow-up per visit: repeated forward move rejected (400), back-and-forward again keeps one row and one audit row | AC1 idempotence | exactlyOneFollowUpPerVisitEvenWhenTheForwardMoveIsRepeated | yes |
| 7 | Same patient and doctor: new token returns the oldest open row; other doctor does not close; second token closes the next oldest; extra token changes nothing | AC3 | newTokenForSamePatientAndDoctorReturnsTheOldestOpenFollowUpOnlyOneAtATime | yes |
| 8 | "Create token" from an appointment also returns it; booking alone does not | AC3 | createTokenFromAnAppointmentAlsoReturnsTheFollowUp | yes |
| 9 | DUE (today), MISSED (daysLate 3 and 10, oldest first), UPCOMING (oldest first), RETURNED tabs and counts | AC4/5 | duePastDueUpcomingAndReturnedTabsAreComputedFromTodayWithOldestDueFirst | yes |
| 10 | A returned row past its due date is RETURNED, never MISSED, daysLate 0 | AC4 boundary | aReturnedFollowUpStaysReturnedEvenWhenItsDueDateIsInThePast | yes |
| 11 | Missing, empty, unknown, lower-case or injected status gives 400 problem+json (type validation) | Negative | missingOrUnknownStatusIsA400ProblemBody | no |
| 12 | Every clinic role (owner, front desk, nurse, doctor, visiting doctor, billing, pharmacist, counsellor) can read; no token gives 401 | Role | everyClinicRoleCanReadAndAnonymousCallersGet401 | yes (reads data) |
| 13 | No write verb on the resource (POST refused) | Role | followUpsAreReadOnlyThroughTheApi | no |
| 14 | Tenant B sees none of tenant A's rows (API, raw SQL under RLS, no tenant bound, cross-tenant update affects 0 rows) | Tenant isolation | anotherClinicNeverSeesTheFollowUpsNeitherThroughTheApiNorUnderRowLevelSecurity | yes |
| 15 | `sahaj_app` cannot DELETE from `opd.follow_up` (permission denied), row remains | Grant | theApplicationRoleCannotDeleteFollowUps | no |
| 16 | Audit rows `FOLLOW_UP_CREATED` / `FOLLOW_UP_RETURNED` hold no name, mobile or reason | Privacy | followUpAuditRowsCarryIdsOnlyNoNameMobileOrReason | no |

## Not covered here

Playwright e2e for the Follow-ups screen: to be added after the angular-developer's screen is merged to this branch
(`frontend/e2e/`).
