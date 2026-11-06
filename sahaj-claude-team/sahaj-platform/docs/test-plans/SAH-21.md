# Test plan SAH-21 · Leave requests and balances

Story: `docs/stories/SAH-21.md`. Contract: `docs/api/hra.yaml` (`/leave*`, LEAVE, onLeave, leaveRequests) and `docs/api/opd.yaml` (appointment POST 409 `doctor-unavailable`). Design: `docs/database/SAH-21.md`.
Integration tests: `backend/src/test/java/com/sahaj/platform/it/LeaveRequestsIT.java` (real `sahaj_test` database, MockMvc, HRA switched on through `PUT /platform/modules/hra`). Team members with their own identity are real memberships; their user id is read back from `platform.membership`.
Frontend e2e for the Leave tab: not part of this pass (the UI owner's unit tests cover it; no Playwright suite exists for SAH-20 either).

Note on "slot lookup": the server has no per-date slot-list endpoint (slots are computed from the doctor's settings). "The doctor has no slots" is therefore asserted through the `opd.api` `DoctorOpdDaysPort.hasOpdSlots` bean (the same computation the roster uses) and through the booking POST refusing with 409.

## Acceptance criteria to tests
| AC | Case | Test (LeaveRequestsIT) |
|---|---|---|
| 1, 9 | HRA off: all four endpoints 403 `module-off` for owner and every role, nothing written | hraOffAllLeaveEndpointsAre403ModuleOffForEveryRole |
| 9 | No token: 401 | unauthenticatedCallsGet401 |
| 3, 9 | Owner sees all, others only own; newest first; pendingCount per visibility; canApprove only owner; types CL 8, SL 7, EL 15 | ownerSeesEveryoneOthersOnlyTheirOwnTypesAllowancesNewestFirst |
| 5 | Inactive members left out of balances and applicants; cannot be named (404) | inactiveMembersAreLeftOutOfBalancesAndApplicants |
| 4 | Staff apply for self, pending, membership from token; owner applies for another and self | staffAppliesForSelfCreatedPendingWithMembershipFromTokenAndOwnerAppliesForAnother |
| 4 | Blank reason stored null, reason trimmed | blankReasonIsStoredAsNullAndReasonIsTrimmed |
| 9 | Non-owner naming another: 403 (real user and all 7 roles) | nonOwnerNamingAnotherPersonIs403AndNothingIsWritten |
| 4 | Unknown, inactive, other-tenant member: 404 | unknownInactiveAndOtherTenantMembersAre404 |
| 4 | Validation 400 with fieldErrors: missing/bad type, missing from/to, from before today, to before from, span over 60, reason over 200, bad JSON | invalidInputGets400ProblemWithFieldErrorsAndWritesNothing |
| 4 | Boundaries: from today, span exactly 60 days, reason of 200 characters accepted; 61 refused | boundariesAreAcceptedTodaySpanOfSixtyDaysAndReasonOfTwoHundredCharacters |
| 4 | Overlap with pending or approved: 409 "That person already has leave on some of these days."; declined does not block; adjacent days and other people free | overlapWithPendingOrApprovedIs409DeclinedDoesNotBlockAndOtherPeopleAreNotAffected |
| 4 | Two concurrent requests, same person and days: exactly one 201 and one 409 (4 rounds, row count) | twoConcurrentRequestsForTheSamePersonAndDaysGiveExactlyOneCreatedAndOneConflict |
| 6 | Approve and decline: status, decided_at and decided_by together, version 1; second decision 409 "This request was already decided." | ownerApprovesAndDeclinesDecisionFieldsAreSetTogetherVersionBumpedAndASecondDecisionIs409 |
| 6, 9 | Seven other roles and a real nurse: 403, request stays pending, no audit | onlyTheOwnerCanDecideEveryOtherRoleGets403AndTheRequestStaysPending |
| 6 | Unknown or other-tenant id: 404 | decidingAnUnknownOrOtherTenantRequestIs404 |
| 5 | Approved reduces; pending and declined do not; floor 0 | approvedDaysReducePendingAndDeclinedDoNotAndNeverBelowZero |
| 5 | Across New Year only the current year's days count (API row across 31 Dec to 2 Jan, row from last year, row wholly next year) | aRequestAcrossNewYearCountsOnlyTheDaysInTheCurrentYear |
| 3 | affectedAppointments = BOOKED only on those days (cancelled, no-show, arrived, outside days excluded); 0 for non-doctors and decided requests; appointments untouched | pendingDoctorRequestCountsOnlyBookedAppointmentsOnThoseDaysNonDoctorsAndDecidedRequestsHaveZero |
| 7 | Roster LEAVE cell for staff and doctor on approved days only; pending and declined show nothing; cell edit on a leave day (see observation) | approvedLeaveShowsAsLeaveInTheRosterForStaffAndDoctorsAndOverridesCellEdits |
| 7 | Attendance onLeave for today, mark refused 409 `on-leave`; pending, declined, tomorrow not on leave | attendanceShowsOnLeaveTodayAndRefusesAMarkWith409 |
| 1 | Summary leaveRequests: pending count for owner, 0 for others | summaryLeaveRequestsIsThePendingCountForTheOwnerAndZeroForEveryoneElse |
| 7, 8 | Doctor on approved leave: no slots, booking 409 `doctor-unavailable`, other doctor and other dates fine, existing appointments untouched, HRA off unblocks (row kept), HRA on blocks again; pending leave closes nothing | doctorOnApprovedLeaveHasNoSlotsThatDayBookingIs409ExistingAppointmentsStayAndHraOffUnblocks |
| 8 | Declined leave does not close booking | declinedLeaveDoesNotCloseBooking |
| 9 | Another tenant's leave does not close this clinic's doctor | anotherTenantsDoctorLeaveDoesNotCloseThisClinicsDoctor |
| 6, 9 | Audit LEAVE_REQUESTED, LEAVE_APPROVED, LEAVE_DECLINED hold ids, dates, type, status; the typed reason appears nowhere in audit_event (raw SQL on the whole row) nor in the application log; no names | auditHasIdsDatesTypeAndStatusButNeverTheReasonText |
| 9 | RLS: tenant B sees none of A's rows (API and SQL), cannot update or insert for A | anotherTenantSeesNoneOfThisClinicsLeaveThroughApiOrSql |
| 9 | No tenant bound: 0 rows | withNoTenantBoundTheAppRoleSeesNoLeaveRows |
| 9 | sahaj_app cannot DELETE or TRUNCATE; check constraints reject bad status/decision combinations, type, dates, span, reason length; valid edge rows accepted | appRoleCannotDeleteLeaveRowsAndTheTableChecksRejectBadRows |
| contract | JSON field names of LeaveView, LeaveRequest, remaining | leaveViewJsonKeepsTheContractFieldNames |

## Observations (not failures)
- `PUT /roster/cells` for a staff member on a day of approved leave is accepted (200) and stored; the roster cell keeps showing LEAVE. AC7 says the cell is "not editable": today this is a display rule only. Raise with the java-developer and angular-developer whether the server should refuse (400/409) or whether UI-only is intended.
- Date-relative tests: a few cases use dates 2 to 13 days ahead and compute year-dependent expectations from the clock, so they stay valid near New Year.

## Not covered here
- Playwright e2e for the Leave tab (apply, approve, decline, error path); needs the running stack, to be added with the SAH-20/21 e2e batch.
- Log content other than the reason text (checked in one test through captured output).

## Note: java-developer adjustments to LeaveRequestsIT (checked by qa-engineer, SAH-22 pass)
- Roster edit on an approved-leave day now expects 409 with zero stored `hra.roster_shift` rows (`approvedLeaveShowsAsLeaveInTheRosterForStaffAndDoctorsAndOverridesCellEdits`). Original intent: approved leave wins over a cell edit and the cell keeps showing LEAVE. The test still asserts the cell is LEAVE after the refused edit and now also that nothing was stored. Intent preserved.
- The "wholly next year" leave row is inserted through `commitAsTenant` because the API refuses a first day more than 60 days ahead (`aRequestAcrossNewYearCountsOnlyTheDaysInTheCurrentYear`). Same dates, type and approved status, asserted the same way. Intent preserved.
- Also fixed by QA in the same test: the "31 Dec to 2 Jan" row was still created through the API, which refuses it for any run date before 1 Nov (a date-dependent failure). It is now committed through the database the same way; assertions (EL 14 left, 3-day request) unchanged.
