# DEC-068 · SAH-78 WP-A review: three rule points for the human

Status: ANSWERED (18 Nov 2026): A, A, A
Story: SAH-78 WP-A. Sources: docs/reviews/SAH-78-security.md (L3, L6), docs/reviews/SAH-78-code.md (F-9).
Blocked: the WP-A fix round items L3 and L6, and tracking of the e2e spec. The rest of the fix round does not depend on these answers.

## Q1 · Delegate approver limit "90 days or less" (security L3)
Context: the delegate period counts both its first and last day, but the code and the DB constraint check `to - from <= 90`, which allows 91 calendar days.
- A. At most 90 calendar days counting both ends (`to - from <= 89`). Matches the plain reading of the story. **Recommended.**
  - Pro: no surprise for the owner.
  - Con: needs a small Java change and a constraint change (a new migration, since the migration has been applied in dev/test).
- B. Keep 91 days (`to - from <= 90`).
  - Pro: no change.
  - Con: the UI says 90.

## Q2 · Store staff and the HRA "staff" menu item (security L6)
Context: with HRA switched on, NavigationPolicy gives a Store staff member the `staff` menu item, but every HRA endpoint refuses that role (403).
- A. Hide `staff` for STORE_STAFF. **Recommended.**
  - Pro: the Store staff role gives nothing outside pharmacy, as the ADR says.
- B. Keep it.
  - Con: it is a menu entry that only leads to errors.

## Q3 · `frontend/e2e` is in .gitignore (code review F-9)
Context: all Playwright specs, including the new `pharmacy-setup.spec.ts` (R-1 regression), stay on this machine only. .gitignore is not owned by any agent.
- A. Human edits .gitignore so that the specs are tracked and `frontend/e2e/node_modules` stays ignored. **Recommended.**
  - Pro: regression tests are kept in the repo.
- B. Keep ignored.
  - Pro: nothing changes.
  - Con: the e2e tests are lost if this machine is lost.

## Tech lead decisions (not for the human)
- **F-1 (Major):** STORE_STAFF with no mapped store goes to `/app/pharmacy` (the server's "no access" sentence), as story 78-1 says.
- **F-3 / L1:** fix in code (bind the body after the gate). The approved contract stays as it is.
- **M1:** do not edit V2026_12_17_1030. It is already applied in sahaj_dev and sahaj_test, which are shared with the IPD session, and a checksum change would break both sessions' runs.
  - Add an afterMigrate guard that stops start-up when `pharmacy.catalogue_product` has sample rows in a database not named sahaj_dev or sahaj_test.
- **L5:** a new forward migration with the counter trigger.
- **F-17:** note the counter rule exception in the design note.
- **F-2 / L4:** write the denial audit in its own transaction (REQUIRES_NEW) in the guard, and add the convention test.
  - **Amended 18 Nov 2026 (code review N-1, security L7):** REQUIRES_NEW inside the caller's transaction needs a second pooled connection. Concurrent denials can stall the pool. The audit now carries its ids on `StoreAccessDeniedException` and is written by the exception handler after the caller's transaction has ended, in one short transaction. A test runs more parallel denials than the pool size.
- **F-11 and the ClinicWebAddressPort ADR record:** the architect does these before WP-B.
- **F-12:** the BA aligns the stories.

## Answer (human, 18 Nov 2026)
- Q1: A. At most 90 calendar days counting both ends.
- Q2: A. Hide `staff` for STORE_STAFF.
- Q3: A. Track the e2e specs. The human edits .gitignore: `frontend/e2e` becomes `frontend/e2e/node_modules/` plus the output folders.
