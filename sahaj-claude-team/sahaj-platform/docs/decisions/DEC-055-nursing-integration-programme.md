# DEC-055 · Nursing integration programme (doctor order → nurse execution)

Date: 15 Nov 2026. Status: NEEDS DECISION. Raised by: tech lead.
Request (human, chat): build the loop in docs/nursing-integration/nursing-integration-handoff/nursing-integration/NURSING_INTEGRATION_SPEC.md (507 lines; 47 stories A1–M4, sprints 1–7, three reference screens), "approved for all stories".

## Why this stops here
1. **Mockup rule.** The three reference screens (nursing workspace, nurse round, my console) are not in docs/design/mockup/sahaj-mockup.html or screen-map.md. Only "Nursing sheet" (IPD, phase 1) is mapped. The spec must be accepted as a design addition by a human.
2. **Size.** 47 stories, 7 sprints, new tables, scheduler, push alerts (WebSocket/SSE), barcode scanning, monitor links. That is many stories, not one.
3. **Approval.** Chat approval is not an approvals.md line (only the human writes those). Each story still needs `stories` and `contract` lines.
4. **Overlaps with built work.** SAH-22–SAH-26 (IPD nursing sheet, MAR, medicine safety, pharmacist check, EWS) and SAH-75 already cover parts of A1, A10, B1, D1–D6, F3. Needs a gap analysis before stories.
5. **Out of phase 1 per CLAUDE.md.** Push alerts, monitor/device integration (F2), HL7, notifications (WhatsApp/SMS) are integration services not in phase 1. M3 Marathi/Hindi labels, L3 NABH/JCI mapping, offline round (H2) are "Next".
6. **Layout.** The spec's prompt says "write PLAN.md in docs/nursing-integration/" — that folder is not an agent-owned folder; the plan would be written by the solution-architect under docs/architecture/.
7. **Current blocker.** The live OPD journey (DEC-054) is waiting on the human's cleanup of leftover processes holding the build locks.

## Options
- **A (recommended): MVP first, sprint by sprint.** Business-analyst turns the CSV MVP rows of Sprints 1–2 (A1, A2, A10, B1, B2, C1, C2, D1–D6, F1, L2, M1) into stories mapped to the existing IPD nursing sheet; architect does a gap analysis against SAH-22–26 first. Each sprint is its own story set with its own approvals. "Next" rows stay out. Pro: controlled, reuses built code. Con: slower.
- **B: Whole spec as one programme.** All MVP + Next rows in 7 sprints. Pro: complete. Con: needs new modules/services (push, devices), clashes with phase 1, very large review load.
- **C: Pause until the live OPD test finishes.** Pro: no lock contention on builds. Con: delay.

## Recommendation
A, started after the live OPD journey unblocks (or in parallel for docs-only stages: BA and architect do not use Maven).

## Questions for the human
1. Is the spec accepted as a design addition to the mockup (reference screens count as the design)?
2. Which scope (A/B/C)?
3. Push alerts (SSE) and monitor links: allowed now or Later?

## Blocked
BA stories, architecture, contract, DB, code for the whole programme.

## Answer (human, chat, 15 Nov 2026)
1. Scope: **MVP Sprints 1–2 first** (Option A), after a gap check against built IPD nursing work. Later sprints get their own approvals.
2. The three reference screens **are the design** (human adds them to docs/design/screen-map.md; agents cannot).
3. **Push alerts and monitor links are Later**: in-app badges and polling now; F2 and A2 push parts stay out.
Chat "approved for all stories" is read as go-ahead to start the stories; the `stories` and `contract` lines in docs/approvals.md are still the human's to write, per story set.
Story id for this set: **SAH-76** (Nursing integration, Sprints 1–2). Branch not yet created: the working tree is dirty on feature/SAH-39; human to commit, then I branch from main.

## Answer 2 (human, chat, 15 Nov 2026)
"For nursing console all requirements are APPROVED." Read as: the SAH-76 requirement and stories are accepted with **all OQ-76-n defaults as written** (including the go-live conditions OQ-76-10, -15, -17, -19, which stay listed as go-live items for clinical sign-off). Recorded as a chat approval. `docs/approvals.md` has no SAH-76 line yet; hooks will block the architect until the human writes `SAH-76 · stories · APPROVED`.
