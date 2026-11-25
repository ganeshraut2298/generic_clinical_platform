# Code review · SAH-90..93 Front desk console

Date: 25 Nov 2026 · Reviewer: code-reviewer (read-only, static) · Branch: feature/SAH-90-front-desk-console, working tree against 51565e5
**Verdict: APPROVE WITH COMMENTS.** No Blocker or Major findings. Security was not in scope (see SAH-90-security.md). No build or tests were run; the reviewer relied on QA (unit 7038/7038; IT 3743/3748, the 5 non-passing are the known pharmacy ITs).

## Found sound
- **Module boundaries.** Desk code uses `PatientLookupPort`, `TeamMemberPort`, `ClinicSpecialtyPort` and `DoctorBlockedDatesPort` only. The batch methods are default methods with platform and hra overrides.
- **BookingPolicy.** `create` and `reschedule` use `requireBookable`; snapshot and day-slots use `openSlots`. Check order and the staff cut-off exemption agree.
- **Lock order.** The check-in, reschedule, desk follow-up, stage move and doctor-sign paths were traced pairwise. No wait cycle.
- **Optimistic version on reschedule.** Lock, compare, then `update … where version = :version`, with 409 on a lost update.
- **Stage moves.** Single-statement compare-and-set; consultation timestamps use `coalesce`. Also correct for the EMR start and finish paths.
- **IST dates.** Used everywhere. `token_date` is written explicitly. The zero-argument `next_token_no()` has no caller left.
- **Migration.** Additive, guarded; the partial-index predicates are repeated exactly; the function is security invoker, granted to `sahaj_app` only.
- **Snapshot statement count.** About 13; the 50-doctor count is within 2 of the 3-doctor count.
- **Contract.** Matches opd.yaml 0.9.0.
- **Tests.** The 7 changed ITs are changes by design, not masked bugs. The SQL helpers respect RLS. The counting proxy is a valid measure.

## Findings
| # | Sev | File:line | Finding | Fix (owner) |
|---|---|---|---|---|
| 1 | Minor | opd/application/AppointmentService.java:312-417 | `freeSlots`, `isOpenSlot`, `bookAt` and `bookFirstFree` bypass BookingPolicy. `bookAt` (EMR finish) never checks working day, grid, walk-in or past, so a direct API call can create off-grid or past appointments. | Use `BookingPolicy.firstRefusal(staff=true)` and `openSlots` in these paths (java-developer); the architect confirms scope |
| 2 | Minor | opd/application/VisitService.java:213-244 | Queue path: a lost race now answers 409 `visit-stage-changed`, which the Queue page does not map. With `expectedStage`, a forbidden stage answers 409 before 403. | Run the role check first (java-developer); Queue shows the 409 detail (angular-developer) |
| 3 | Minor | desk-console.page.ts:172, 220-223 | After IST midnight, or with a day outside today..+6, polling keeps sending the old `selectedDate`. The server answers 400 forever. | Reset to today, or send `date` only when in range (angular-developer) |
| 4 | Minor | desk-book-panel.component.ts:81, 259; desk-console.page.ts:86, 367 | Busy flags are per component, so a card action can overlap an in-flight booking confirm. There is no data risk. | Shared busy signal (angular-developer) |
| 5 | Minor | desk-console.page.ts:234; desk-book-panel.component.ts:253 | Reschedule keeps a stale `appointmentVersion` after polls, and `?? 0` hides a missing version. | Refresh the version from the snapshot; treat missing as an error (angular-developer) |
| 6 | Minor | opd/application/DeskReadService.java:164-264 | Snapshot reads run in READ COMMITTED, so a card can show Booked for one poll. The appointments read stops silently at 5,000 rows. | Optional REPEATABLE_READ read-only; `truncated` flag (java-developer) |
| 7 | Minor | V2026_12_18_1000__opd_front_desk_console.sql | Edited after it was applied to `sahaj_test` (repaired by QA). A `sahaj_dev` that ran the first version will fail the checksum. | Human: repair or rebuild `sahaj_dev`; no edits after merge |
| 8 | Minor | it/DeskSnapshotIT.java:335-347 | The appointment date index is only checked as existing, not as used. | Loaded-rows plan test, or a documented gap (qa-engineer) |
| 9 | Minor | desk-token-panel.component.scss:72 | Hard-coded `#000`. | Token or a print-only variable (angular-developer) |
| 10 | Minor | DeskReadService.java:176-178 | The finder never offers a taken slot, so overbook can't be used from the console. | Known gap; record as a SAH-91 follow-up (solution-architect) |
| 11 | Nit | AppointmentService.java:68-102 | Chained constructors accept null collaborators. | Explicit test fixtures (java-developer) |
| 12 | Nit | desk-console.page.html:40 | The loading text has no ellipsis. | "Loading the front desk console…" (angular-developer) |
| 13 | Nit | AppointmentService.java:141 vs opd.yaml | 403 before 400 for other roles (unreachable). | Align the contract next revision (solution-architect) |
| 14 | Nit | it/AppointmentDoubleBookingIT.java:32 | Rounds went from 5 to 4. | Optional: 15-minute slots (qa-engineer) |
