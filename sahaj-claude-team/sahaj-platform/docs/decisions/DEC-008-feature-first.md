# DEC-008 · Feature-first: build all stories, defer testing/security/audit review

Date: 1 Nov 2026. Decided by: Ganesh.

"Try to build entire user stories first, then test. All agents need to develop the system, then we work on testing and other security related things. Skip Testing, Security, Audit logs for now — focus on requirement creation and development per the platform document. Run the backend in the background/terminal so I can test it myself."

Effect, on top of DEC-006/DEC-007:
- No new qa-engineer, code-reviewer or security-reviewer tasks are started until the human asks again. (The SAH-3 qa-engineer task already running when this was decided is left to finish, since it was already in flight.)
- Developers (java-developer, angular-developer) and db-engineer move through the remaining stories (SAH-4 → SAH-5 → SAH-6 → SAH-7, then OPD core screens) focused on making each feature run end to end against the mockup. A basic compile/build check still applies (the app must actually start for the human to test it), but writing exhaustive new automated tests, hardening and audit-log completeness are not required right now.
- Backend and frontend are started so the human can use the app directly while development continues.
- Nothing already found (DEC-003 password-in-history, the review findings already fixed) is undone. This decision only stops new review cycles, not the fixes already in flight.
