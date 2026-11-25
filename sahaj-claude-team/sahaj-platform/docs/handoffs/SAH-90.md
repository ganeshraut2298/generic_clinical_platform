# SAH-90 handoff log · Front desk console (SAH-90..93)

Branch: feature/SAH-90-front-desk-console. It was created 24 Nov 2026 from feature/SAH-39-home-kinetic-background at 51565e5, the pharmacy commit, not from main. The reason: main lacks the unmerged work that the console builds on.

## 24 Nov · Phase 1 analysis
- Done by solution-architect and angular-developer, read-only. Report: docs/handoffs/SAH-90-phase1-analysis.md.
- Decisions in DEC-097: A1 clinic-wide token, B1 the desk may start and complete, C1 clinic follow-up fee rule, D2 SAH-90 goes before SAH-89 (SAH-89 paused). Items E1 to E12 were decided by the tech lead.

## 24 Nov · Human approval to proceed
- The human wrote in chat: "approved from my side as per designe please do it".
- That approves phase 2 and the console mockup as the design.
- Still open for the human:
  - save the mockup into docs/design;
  - update the screen map;
  - add the `stories` lines to docs/approvals.md after review.
- business-analyst started on the requirement and stories SAH-90..93.

## 24 Nov · business-analyst: done
- Files:
  - docs/requirements/SAH-90.md (19 mockup deviations, 20 rules)
  - docs/stories/SAH-90.md (90-1..5)
  - docs/stories/SAH-91.md (91-1..3)
  - docs/stories/SAH-92.md (92-1..4)
  - docs/stories/SAH-93.md (93-1..2)
- The brief's acceptance checks 1 to 10 are mapped to stories.
- `validate docs` PASSED (run by the tech lead).
- Open questions 1 to 7 were decided in the DEC-097 addendum. Q1 keeps the SAH-11 staff exemption. The BA is updating the stories to match.

## 24 Nov · business-analyst: fix round done
- DEC-097 addendum applied in the requirement and stories SAH-90..93.
- Staff are exempt from the cut-off (SAH-11 rule kept). Staff see an "Inside the booking cut-off" note on those slots.
- `validate docs` PASSED (run by the tech lead).
- Waiting for the human:
  - the `stories` lines for SAH-90..93;
  - the mockup saved in docs/design and the screen map updated.
- Next agent: solution-architect.

## 24 Nov · Human approved the stories in chat
- The human wrote "approved start the development".
- docs/approvals.md has no SAH-90..93 lines yet. The human still needs to add them, because the hooks check that file.
- solution-architect started on the ADR and the opd.yaml contract for SAH-90..93.

## 24 Nov · solution-architect: done
- ADR-032 (D1–D12). opd.yaml goes from 0.8.0 to 0.9.0 (112 paths). patient.yaml is unchanged.
- New and changed endpoints:
  - GET /desk/snapshot (polled every 20 s, no-store)
  - GET /desk/doctors/{id}/day-slots
  - POST /appointments/{id}/reschedule (optimistic version)
  - check-in returns tokenPanel, with a row lock and the IST token day
  - stage move is a compare-and-set using expectedStage
  - three follow-up write endpoints
  - booking checks with new problem types
- Data needed (all additive):
  - slot_template close_before_hours
  - follow_up not_needed_at/by and a partial index
  - visit consult_started_at/ended_at
  - next_token_no(date) overload
  - appointment date index
  - appointment version increment
- Checks: SnakeYAML parse OK for opd.yaml and patient.yaml. validate docs PASSED.
- The 7 open questions are decided in DEC-097 addendum 2.
- Waiting for the human: approvals.md lines for stories, then contract, for SAH-90..93.

## 24 Nov · Human approved the contract in chat
- The human wrote "in approved my side, please start the development".
- Lines for SAH-90..93 (stories and contract) are still not in docs/approvals.md. The human needs to add them.
- db-engineer started on the additive migration and design note.
- angular-developer started on the SAH-90 console read side, in parallel. It only depends on the contract.

## 24 Nov · angular-developer: SAH-90 read side done
- New: features/appointments/desk/
  - desk.model.ts and desk.api.ts
  - the console page, plus rail, finder, board and action-panel components
  - desk.testing.ts, and 3 specs with 36 tests
- Changed: appointments.routes.ts (child route `desk`) and the old board (an "Open front desk console" button).
- Polling every 20 s only while the tab is visible. One read at a time. On a failed read the last data stays, and timers stop on destroy.
- Stubbed for later stories: card actions are shown disabled for OWNER and FRONT_DESK. The book and pick panels are placeholders; booked, token and follow are typed but not built.
- `validate frontend` PASSED on the third run. The first run had a pharmacy spec timeout; the second had OOM worker crashes in non-SAH-90 specs.
- The tech lead will rerun `validate frontend` after the SAH-91..93 frontend round.
- Not run against the real backend yet.

## 25 Nov · db-engineer: done
- Migration V2026_12_18_1000__opd_front_desk_console.sql. It is additive only. It adds:
  - close_before_hours
  - follow_up not_needed_at/by with a pair check
  - visit consult_started_at/ended_at with an order check
  - opd.next_token_no(date) overload, security invoker, granted to sahaj_app
  - 2 partial indexes
- appointment.version already exists as bigint. No trigger, so the java-developer must increment it in SQL.
- Design note: docs/database/SAH-90-front-desk-console.md (rollback per change).
- Flyway applied the migration cleanly. MigrationFunctionGuardrailTest passes: the overload is written as plain `create function`.
- `validate db` FAILED on 4 pharmacy ITs not related to opd:
  - PharmacySalesCommandApiIT ×2: inbox type DISCOUNT vs SALES; catalogue reports
  - PharmacySalesDiscountApiIT ×2: NPE at line 110; held-bill discard
- These belong to DEC-061 (these classes were part of the 27-class final run that never finished). Reported to the human. Log: scratchpad val.txt.

## 25 Nov · angular-developer: SAH-91..93 frontend done
- New: desk-book-panel, desk-token-panel and desk-follow-panel components. All card actions are on, for OWNER and FRONT_DESK only.
- deskProblem() maps every 0.9.0 problem type to a message. refresh(afterWrite) queues a refresh after each write.
- Outside the desk folder: core/http/problem-detail.ts and problem-detail.interceptor.ts now carry appointmentId, visitId, tokenNo and closeBeforeHours. This is additive.
- Tests: desk folder 8 specs / 146 tests. `validate frontend` PASSED. Tested against fixtures only, not against the live backend.
- Tech lead rulings:
  - The "Mark no-show" secondary button is accepted. It is covered by DEC-097 E3 (manual mark) and the brief's capability 7.
  - Overbook pre-confirm note: not reachable from the finder. Accepted as a known gap.
  - appointment-not-bookable text: the java-developer aligns the server text with the frontend wording.
- Human item: delete the stray file d:\tmp_out.txt (outside the repo).

## 25 Nov · java-developer: SAH-90 + SAH-91 backend done
- GET /desk/snapshot (no-store; gzip already on) and GET /desk/doctors/{id}/day-slots.
- POST /appointments checks run in the D4 order, with the new problem types.
- POST /appointments/{id}/reschedule: row lock, optimistic version, audited.
- closeBeforeHours added on doctor slots.
- BookingPolicy is the one rule for reads and writes. The snapshot uses about 13 fixed statements.
- Ports: TeamMemberPort.doctorSpecialties and DoctorBlockedDatesPort.blockedDatesForDoctors are default methods, with overrides in platform and hra.
- Contract deviations: none. Judgement calls:
  - create() gives 403 for roles other than OWNER and FRONT_DESK (D4 check 1).
  - findActiveConflict now has limit 1.
- Unit tests: `mvn test` 7001 run, 0 failures, 0 errors. `validate backend` (full verify) was not run, because the ITs belong to QA.
- QA must cover:
  - real SQL and RLS for the desk reads
  - concurrency (booking, reschedule)
  - cut-off by role, leave with HRA on and off
  - existing appointment ITs, which may now get 400 for past or off-grid times
- java-developer round 2 (SAH-92, SAH-93) started.

## 25 Nov · java-developer round 2: BLOCKED by the backend lock
- .claude/locks/backend.lock is still held by the finished round 1 agent. It is 31 min old, and the hook treats a lock as stale only after 2 h.
- No code was written. The build plan is ready.
- The tech lead started a watcher. The human may delete the lock now, because the holder has finished.

## 25 Nov · java-developer: SAH-92 + SAH-93 backend done (retry 1)
- Check-in:
  - row lock first
  - 409 already-checked-in (with token), 409 not-bookable, 400 not-today
  - IST token day via next_token_no(date)
  - answers with tokenPanel
- PATCH appointment: a non-BOOKED appointment now answers 409 appointment-not-booked.
- Stage move: compare-and-set with expectedStage, 409 visit-stage-changed, consult timestamps set once.
- Token-panel read added.
- Three follow-up writes (DeskFollowUpService and controller): FOLLOW_UP_BOOKED, FOLLOW_UP_NOT_NEEDED and FOLLOW_UP_CREATED for walk-ins.
- Not-needed follow-ups are excluded from the lists, counts and today-summary.
- Contract deviations: none.
- `mvn test`: 7038 run, 0 failures, 0 errors. Full verify not run.
- Hook refused a java-developer write into the session scratchpad. That is correct (protected area); no action needed.
- Next: qa-engineer.

## 25 Nov · qa-engineer: PASS for SAH-90..93
- Test plan: docs/test-plans/SAH-90.md (about 110 rows, brief checks 1–10 mapped).
- New ITs: DeskBookingIT 16, DeskFlowIT 18, DeskSnapshotIT 8 (42 total, all pass). Helpers: DeskSupport and AppointmentToday.
- Playwright desk-console.spec.ts: 12 × 2 projects (360 px, 1440 px), 24/24 pass, mocked API.
- 7 existing ITs changed by design. None were deleted or skipped.
- Full verify:
  - unit 7038/7038
  - IT 3748 run, 3743 pass
  - the remaining 4 failures + 1 error are the known pharmacy items (PharmacySalesCommandApiIT ×2, PharmacySalesDiscountApiIT ×2) and are not SAH-90
- validate docs and validate frontend: PASSED.
- Product bugs: none.
- Notes:
  - The migration file was edited after it was applied to sahaj_test (the db-engineer's guardrail fix). QA ran a Flyway repair on sahaj_test. sahaj_dev may need the same if the app ran in between. A stray flyway_schema_history may remain in sahaj_test's public schema.
  - The clinic set-up team-add audit row holds the doctor's display name. That is staff, not a patient. Sent to the security reviewer.
- Not covered: leave with HRA on (unit only), wall-clock statuses (unit only), print dialog (unit only).
- Next: code-reviewer ∥ security-reviewer.

## 25 Nov · Reviews
- Code review: APPROVE WITH COMMENTS (docs/reviews/SAH-90-code.md).
- Security review: FAIL on F1 High, no consent on the console new-patient form (docs/reviews/SAH-90-security.md).
- DEC-098 answered: consent added to the quick form; 15-minute idle pause; pharmacy ITs fixed on this branch in a separate SAH-80 commit.
- Fix round started: angular-developer and java-developer.

## 25 Nov · Fix round interrupted
- The previous session ended while both fix agents (angular-developer and java-developer) were still running, so neither handed off.
- They left partial edits:
  - frontend: desk components, a new features/privacy/consent-block.component.ts, the patient form dialog, the queue board
  - backend: AppointmentService, VisitService, DeskRead*, Jdbc*Repository, tests
- Their locks (backend, frontend) go stale at 2 h. A watcher is running. Both agents will be relaunched (retry 1) to finish and verify the partial work.

## 25 Nov · Human away: standing approval (DEC-099)
- The tech lead decides and continues through the fix round, QA, re-reviews and the pharmacy IT fixes. The merge and approvals.md lines wait for the human.

## 25 Nov · angular-developer: fix round retry 1, done
- All 7 items were already complete from the interrupted run. No files were changed.
  - F1: shared ConsentBlockComponent used by the patient form dialog and the desk book panel. Consent is sent when required, with a retry.
  - F2: idle pause after 15 min.
  - #3: date window.
  - #4: shared busy signal.
  - #5: version refreshed from the snapshot.
  - #2: Queue page shows the 409 message and reloads.
  - #9 and #12 done.
- Each item has a proving spec. The one gap: no spec for the follow panel's busy emission (minor).
- `validate frontend` PASSED.
- Waiting for the java-developer before QA.
