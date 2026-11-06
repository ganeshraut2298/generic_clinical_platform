# Code review · SAH-19 (Plan and modules), SAH-20 (staff, roster, attendance), SAH-21 (leave)

Reviewer: code-reviewer (read-only), 5 Nov 2026. Verdict: **changes required (one Major)**.
Passed: Modulith boundaries (hra uses only platform.api and opd.api; opd reaches leave only through DoctorBlockedDatesPort), advisory lock then overlap check, 60-day check matches DB check, year-boundary clamp, IST used consistently, audit rows without reason or names, forward-only migrations with FORCE RLS and no delete grants, contract conformance, tab accessibility, tests have real assertions.

| # | Sev | Where | Finding | Fix |
|---|---|---|---|---|
| 1 | Major | modules.page.ts toggleHra; staff.page.ts:202 | After switching HRA the shell menu is not reloaded, so "Staff and leave" is missing (or stale) until a full reload; StaffPage decides lock page from the menu | Refresh /me after setHra; StaffPage should rely on 403 module-off too; spec |
| 2 | Minor | LeaveService:464-472 | `from` not capped at today+60 server-side | Reject with field error, test |
| 3 | Minor | HraService.setCell | Roster edit on an approved-leave day accepted | 409 on-leave |
| 4 | Minor | DoctorOpdDaysPort.hasOpdSlots | Unused in production | Remove or document as test-only; slot picker unaware of leave days (only 409 on submit) |
| 5 | Minor | HraService.ROLE_LABELS (+2 frontend copies) | Role labels duplicated | Expose label from platform port |
| 6 | Minor | roster/leave builders | N+1 (holidays per doctor, booked counts) | Batch |
| 7 | Minor | ModulesService / Jdbc repo | Read-modify-write without lock; concurrent owner actions can overwrite | select for update or column update |
| 8 | Minor | menuCounts | Counts base menu, user sees +1 with HRA on | Count with hraOn |
| 9 | Minor | staff.page.ts | Roster/attendance stale after approve/apply/holiday toggle | Reload them |
| 10-13 | Nit | stale package-info comments, two auth styles, no-op setHra audited, fragile date split | tidy |

Not checked: builds/tests run, pixel match to mockup, all test files, security (separate report), SAH-13..18 work.
