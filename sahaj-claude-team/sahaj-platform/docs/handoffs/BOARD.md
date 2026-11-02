# Story board (tech-lead keeps this current)

All Phase 1 work is on `feature/SAH-2-home-page`. Approvals come as commands in chat, not from `docs/approvals.md` (DEC-006). Three-agent mode (DEC-007): tech-lead, dev (java-developer/angular-developer), test (qa-engineer, unit tests only). Goal: finish development for a client showcase.

| Story | Title | State | Owner | Branch | Blocker |
|---|---|---|---|---|---|
| SAH-1 | Project foundation | Built, walked end to end live | — | `feature/SAH-2-home-page` | DEC-003 git-history clean-up before any push |
| SAH-2 | Home page | Built | — | `feature/SAH-2-home-page` | DEC-002 validate.mjs edit; screen-map row |
| SAH-3 | Sign up and log in with mobile one-time code | Built, walked end to end live | — | `feature/SAH-2-home-page` | screen-map row |
| SAH-4 | Clinic setup wizard | Built, walked end to end live | — | `feature/SAH-2-home-page` | screen-map row |
| SAH-5 | App shell | Built, walked end to end live | — | `feature/SAH-2-home-page` | screen-map row |
| SAH-6 | Team and access (user management) | Built, walked end to end live | — | `feature/SAH-2-home-page` | — |
| SAH-7 | Clinic masters | Built, walked end to end live (small wording nits open) | — | `feature/SAH-2-home-page` | — |
| SAH-8 | Patients | Built | — | `feature/SAH-2-home-page` | — |
| SAH-9 | Queue and tokens | Built | — | `feature/SAH-2-home-page` | — |
| SAH-10 | Billing counter | Built | — | `feature/SAH-2-home-page` | — |
| SAH-11 | Appointments and booking rules | Built and tested against the real database (10 integration tests; 2 slot-index bugs found and fixed via migration 1045) | — | `feature/SAH-2-home-page` | Known gap: two truly simultaneous bookings of one slot with overbook off could both succeed (no DB uniqueness now) |
| SAH-12 | OPD workflow configuration | Built and tested against the real database (12 integration tests; 391 frontend tests) | — | `feature/SAH-2-home-page` | — |

Decision (human, 1 Nov): whole app first, before fixing the integration-test debt list above. Doctor consultation ("Doctor App") has no screen in the mockup, so it is not being built; "Consult done" (already in SAH-9) is as far as that goes in Phase 1.
Full OPD loop now works end to end: sign up → setup → team → patient → token → queue → billing. Appointments (book/cancel/no-show/create-token) and clinic-wide booking rules now work end to end too.
Not yet started: Revenue/Reports, Follow-ups. IPD module entirely unbuilt.
Known debt: CLOSED (1 Nov). qa-engineer clean-up pass (13 failures + 2 errors → 1) plus java-developer's config-defaults fix + the `plan_ends_at`/`created_at` off-by-one it exposed → `mvn verify` now 115 tests / 0 failures / 0 errors, `node scripts/validate.mjs backend` PASSED. `application-dev.yml` no longer bakes default passwords/JWT secret — **local backend run now requires `SAHAJ_DB_APP_PASSWORD`, `SAHAJ_DB_MIGRATOR_PASSWORD`, `SAHAJ_JWT_SECRET` (32+ chars) exported in the shell before `mvn spring-boot:run`, or the app fails fast at startup.** The human must restart their running backend with those exported to pick up this change.

**Live bug: FIXED (1 Nov).** `POST /api/v1/opd/visits` 500'd on every patient's first-ever visit with a given doctor (the common case). Root cause: `JdbcVisitRepository.lastVisitAt` ran `select max(created_at) ...` and called `.single()` on the result — Spring's `JdbcClient` rejects a single legitimate `NULL` row with `TypeMismatchDataAccessException`. Reproduced deterministically by qa-engineer via a new end-to-end integration test (`WalkInTokenJourneyIT`, real sign-up → setup → team → patient → token, no hand-crafted SQL). Fixed by java-developer: query switched to `order by created_at desc limit 1` + `.optional()` instead of `max()` + `.single()`; swept the rest of `backend/src/main/java/**` for the same `.single()`-on-nullable-aggregate shape, found none. `WalkInTokenJourneyIT` re-enabled and passing. Full suite: 575 tests, 0 failures, 0 errors. **The human must restart their running backend (with the three env vars above exported) to pick up this fix**, then retry issuing a token.
