# Security review · SAH-90..93 Front desk console

Date: 25 Nov 2026 · Reviewer: security-reviewer (read-only) · Branch: feature/SAH-90-front-desk-console, working tree against 51565e5
**Verdict: FAIL.** One High finding (F1). The branch passes once F1 is fixed; the other findings can be accepted as conditions. Escalated in DEC-098.

## Findings
| # | Sev | File:line | Risk | Fix | Owner |
|---|---|---|---|---|---|
| F1 | **High** (DPDP) | frontend/src/app/features/appointments/desk/desk-book-panel.component.ts:173-234 | The console's "New patient" form calls `POST /patients` without loading the clinic's privacy settings. It never shows the consent line and never sends `consents`. Every other new-patient path uses `PatientFormDialog`, which records RECORDS and PROMOTIONAL consent when `consentRequired` is on. The backend accepts an empty list (PatientService.java:113). Patients registered from the console get no DPDP notice and no consent row. | Load the privacy settings. When consent is required, show the same consent line and checkboxes and send `consents` (a shared component is preferred). Add a spec for consent on and off. Optional: server-side refusal (patient module; needs architect or human). | angular-developer |
| F2 | Medium (ASVS V3.3) | desk-console.page.ts:146-162; core/auth/auth.interceptor.ts:45-49; platform AuthService.java:31,84 | Polling every 20 s plus the silent refresh (30-day sliding refresh token) keeps an unattended reception screen signed in indefinitely, showing the day's patient names. | Pause polling after N minutes without input and blank the board behind "Continue". Platform-wide: absolute refresh-token lifetime and an idle timeout. | angular-developer; architect or human (session policy) |
| F3 | Low (DoS) | opd/web/DeskController.java:35-45; DeskReadService.java:164-264 | The heaviest read has no per-user rate limit. | Per-user limiter (SlidingWindowRateLimiter, about 10/min with a burst) or a 2–5 s memo keyed by tenant and role; measure after go-live. | java-developer / architect |
| F4 | Low (integrity) | JdbcDeskReadRepository.java:31,57,70 | `MAX_CARD_ROWS = 5000` cuts silently, so free slots on later days can be over-counted. Writes re-check, so there is no confidentiality issue. | Fetch limit+1 and set a truncated flag, or bound per day. | java-developer |
| F5 | Info | JdbcAppointmentRepository RESCHEDULE (~43); JdbcVisitRepository MOVE_IF_CURRENT | UPDATE statements filter by id only. Safe under RLS and the tenant-scoped lock read. | Optionally add `tenant_id = :tenantId`. | java-developer |
| F6 | Info | VisitService.java:214-223 | 409 is returned before 403 for a stale stage. Nothing leaks. | None. | — |
| F7 | Info (out of scope) | platform/application/TeamService.java:150, 327-342 | The `TEAM_MEMBER_ADDED` audit row holds staff name, masked mobile, specialty and registration number. This is staff data with a legitimate purpose, and it is not changed by this branch. | Backlog: ids and changed fields only. | platform backlog |
| F8 | Info (pre-existing) | desk-book-panel.component.ts:281-285 | The patient search sends name or mobile in the URL query string, which can reach access logs. | Backlog: strip query strings from logs, or use POST search. | solution-architect |
| F9 | Info | `../../.claude/worktrees/agent-ad097865b1fd082f5/` | A leftover agent worktree in a humans-only folder. | A human removes it. | human |

## Checked and passing
- **Tenant isolation.** Bound parameters and `tenant_id` filters with RLS underneath. Tenant and role come from `ClinicUser.current()` only. `opd.next_token_no(date)` runs as security invoker, revoked from public and granted to `sahaj_app`. Cross-tenant ids answer 404 (tests in DeskSnapshotIT, DeskFlowIT, DeskBookingIT). The hra and platform overrides are tenant-scoped.
- **Authorisation.** Reads use ANY_CLINIC_ROLE. Writes use OWNER_OR_FRONT_DESK, re-checked in the services. The stage-owner rule is kept. `insideCutOff` is set for staff only. Negative role tests exist.
- **Data minimisation.** Cards and the token panel carry the patient name only. Responses send `no-store`. The print slip has no clinical content. Nothing is kept in browser storage, and there is no innerHTML or trust bypass.
- **Logging and audit.** No new logger calls. `toString` leaves names out. Audit rows hold ids, dates, times, statuses and interval keys only. Problem bodies have fixed text and ids only.
- **Abuse and races.** Inputs are bounded. Check-in, booking, reschedule, follow-up and stage moves are race-safe, with no lock cycle.
- **Test-only code.** `DeskSupport` and `AppointmentToday` stay in src/test and run under RLS. No Flyway repair code remains.
- **Dependencies.** Unchanged.
