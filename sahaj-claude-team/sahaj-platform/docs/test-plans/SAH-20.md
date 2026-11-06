# Test plan SAH-20 · Staff, roster, attendance and holidays

Integration tests: `backend/src/test/java/com/sahaj/platform/it/StaffRosterAttendanceIT.java` (real `sahaj_test` database, MockMvc through the real security chain, raw SQL under `DbAccess.inTenant`). Unit tests (mock-based) already exist from the developers. The Angular page is covered by its unit tests; no e2e in this pass. The Holidays tab uses the existing holiday endpoints (SAH-5/7); AC6 is covered here only through their effect on the doctor roster cell.

| AC | Test | Notes |
| --- | --- | --- |
| 1 HRA off: six endpoints 403 `module-off`, menu lacks `staff` | hraOffByDefaultAllSixEndpointsAreModuleOffAndMenuHasNoStaff | all 8 roles on `/me`; no rows written |
| 1 HRA on: menu has `staff` for every role, endpoints work, off again | ownerSwitchesHraOnMenuGetsStaffForEveryRoleAndEndpointsWorkThenOffAgain | |
| 401 anonymous | unauthenticatedGets401 | |
| 2 summary counts | summaryCountsStaffDoctorsOthersAndPresentLate | owner + doctor + nurse + front desk; present counts in and late |
| 2 inactive excluded | inactiveMembersAreExcludedFromSummaryRosterAndAttendanceAndPresentCount | also their marks |
| 3 7 IST days, all active rows, staff default Off | rosterIsSevenIstDaysFromTodayWithEveryActiveMemberAndStaffDefaultOff | legend times, unpublished, GET writes nothing |
| 3 doctor OPD/OFF matches `GET /opd/doctor-slots` | doctorCellsAreOpdOrOffDerivedFromDoctorSlotsAndNeverStored | default and Mon-Fri pattern; nothing stored |
| 3 CLOSED holiday turns doctor day OFF | closedClinicHolidayTurnsTheDoctorDayOffButNotStaffOrPartialHolidays | MORNING_ONLY does not; removal restores OPD |
| 3 owner cycles M, E, N, X, upsert | ownerCyclesMorningEveningNightOffOneRowPerCellUpsert | one row per cell; edge days; updated_by |
| 3/4 negative: doctor, dates, shift, bad body | cellEditRejectsDoctorsDatesOutsideTheWeekUnknownShiftsAndBadBodies | 400 problem+json |
| 3 404 unknown, other tenant, inactive | cellEditUnknownOtherTenantAndInactiveMembersAre404 | |
| 3/4 non-owner 403 | nonOwnersCannotEditCellsOrPublish | 7 roles |
| 4 publish once, edits keep it | ownerPublishesSecondCallKeepsFirstTimeAndByAndEditsKeepItPublished | one audit event |
| 5 list, canMark by role | attendanceListsActiveMembersNotMarkedAndCanMarkOnlyForOwnerAndFrontDesk | |
| 5 in/late/abs, IST time, version, one row | markingTransitionsRecordIstTimeOnlyForPresentAndLateAndBumpVersion | in -> late -> abs -> in |
| 5 403 roles, 404, 400 | attendanceMarkRefusedForOtherRolesUnknownInactiveAndBadStatus | |
| 7 audit before/after, no names | cellPublishAndAttendanceAreAuditedWithBeforeAfterAndNoNames | |
| 7 RLS API + SQL | anotherTenantSeesNothingOfThisClinicsRosterPublishOrAttendanceThroughApiOrSql | cross-tenant insert denied |
| 7 RLS no tenant bound | withNoTenantBoundTheAppRoleSeesNoRowsOfTheThreeTables | |
| Grants and checks | appRoleCannotDeleteAttendanceOrPublishMarkerAndTablesEnforceChecks | delete/truncate denied; check constraints |

## Bugs
None found. All 20 tests pass.

## Notes
- Tests that depend on "today" use the IST date; assertions on the day list tolerate a one-day shift if a run crosses IST midnight.
- SAH-19 bug B1 is closed (see `SAH-19.md`).
