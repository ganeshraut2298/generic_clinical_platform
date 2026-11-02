# SAH-11 · Appointments and booking rules — handoff log

Branch: `feature/SAH-2-home-page` (all Phase 1 work stays on this branch per DEC-006/007)
Mockup screen: Appointments (sidebar → Appointments); Clinic setup → OPD workflow, Appointments and slots, Booking rules
Module: opd

---

## Handoff · SAH-11 · db-engineer → java-developer / angular-developer
Status: DONE
Files changed:
- Flyway migrations under `backend/src/main/resources/db/migration/` for `opd.doctor_slot_template`, `opd.doctor_slot_session`, `opd.booking_rules`, `opd.appointment` — FORCE RLS, denormalized `tenant_id`, partial unique index on `(tenant_id, doctor_membership_id, appt_date, appt_time)` excluding CANCELLED, per the story's technical design.

Validation: pre-existing integration-test failure count confirmed unchanged at 15 — no new regressions from these migrations.

## Handoff · SAH-11 · angular-developer → qa-engineer
Status: DONE (backend not yet live; all HTTP mocked against `docs/api/opd.yaml`)
Files changed (all under `frontend/`, outside `frontend/e2e/`):
- `shared/format/ist-date.ts`, `shared/scheduling/slot.util.ts` (+ specs) — IST date helpers and a port of the mockup's `slotsFor`.
- `features/appointments/` — full feature: models, `appointments.api.ts` (only caller of the five `/opd/*` SAH-11 endpoints), role-gate and display-status utils, board page (Today/Tomorrow, doctor columns, stat tiles, rule chips), appointment detail dialog (per-status actions; Remind/Send reminder disabled with "Needs WhatsApp (not in this phase)"), book dialog (reuses SAH-8/9 patient pick/register flow).
- `features/config/slots/` — Clinic setup "Appointments and slots" panel (per-doctor settings editable by OWNER/FRONT_DESK; clinic-wide booking rules editable by OWNER only).
- Wired `config.routes.ts`, `config-sections.ts`, `shell.routes.ts` (`/app/appointments` now loads the real feature, not the SAH-5 placeholder).

Results: lint clean; `npm test` → 58 files / 373 tests passed; build succeeds (only pre-existing SCSS budget warnings on unrelated files). `validate.mjs frontend` still fails only on the known, human-owned `--browsers=ChromeHeadless` flag issue (not touched).

Departures from mockup: no week view/drag-drop/Reschedule, no live "now" marker/past-slot hiding, self-book/overbook render as stored toggles only — all explicitly called out in the story's "Out of scope" list, so no sign-off gap.

Contract: no gaps. `DoctorSlotSettings`, `BookingRules`, `AppointmentCreate`, `Appointment` and all five paths implemented with no invented fields.

## Handoff · SAH-11 · java-developer → qa-engineer
Status: DONE
Files changed (all under `backend/`):
- `application/{SlotComputation,DoctorSlotSettingsRepository,DoctorSlotSettingsInput,DoctorSlotSettingsView,DoctorSlotSettingsService,BookingRulesRepository,BookingRulesInput,BookingRulesService,AppointmentRepository,AppointmentCreateInput,AppointmentView,AppointmentService}.java`
- `infrastructure/persistence/{JdbcDoctorSlotSettingsRepository,JdbcBookingRulesRepository,JdbcAppointmentRepository}.java`
- `web/{DoctorSlotController,DoctorSlotSettingsDto,BookingRulesController,BookingRulesDto,AppointmentController,AppointmentCreateRequest,AppointmentResponse,AppointmentStatusRequest}.java`, `web/OpdRoles.java` (edited — added `OWNER_OR_FRONT_DESK`)
- `platform.api.TeamMemberPort` + `TeamMemberLookup` (edited — added `findActiveDoctors`)
- `AuditSanitizer` (edited — added `APPOINTMENT` to person-name entity types)
- Tests: `SlotComputationTest`, `DoctorSlotSettingsServiceTest`, `BookingRulesServiceTest`, `AppointmentServiceTest`, `AppointmentControllerTest` (52 new tests, all passing).

Create-token reuses `VisitService.issueToken` directly in one `@Transactional` method — no token numbering or fee logic duplicated. Overbook off → 409 on a second active slot; on → only OWNER/FRONT_DESK. Close-before implemented with an explicit staff-override distinction (every current caller is staff, so it never blocks yet — documented in the class javadoc, ready for a future self-book channel with no schema change).

Validation (tech-lead, re-run after db-engineer's migration `V2026_11_01_1043__opd_create_appointment.sql` landed, which the java-developer had not yet seen when it finished): `node scripts/validate.mjs db` → `mvn verify` → Tests run: 115, Failures: 14, Errors: 2 — identical to the already-catalogued pre-existing debt (`ProfileConfigIT`, `RowLevelSecurityCatalogIT`, `SessionAndClinicSelectionIT`, `SignUpAndLoginIT`; none reference `opd.appointment`/`doctor_slot_template`/`doctor_slot_session`/`booking_rules`). Confirmed SAH-11 introduces zero new regressions.

Open item carried to qa-engineer/solution-architect: `AppointmentResponse`'s optional patient `mobile` field is left null (`patient.api.PatientLookupPort.PatientSummary` has no mobile) — out of this story's explicit asks; flag for a future story if the board needs it surfaced.

Next: qa-engineer — SAH-11, branch `feature/SAH-2-home-page`, write unit-level test plan notes + any `frontend/e2e/` additions, then `validate all`.

## Note — live bug found during SAH-11 development (separate from this story's own scope)
While SAH-11 was in flight, the human reported a live `500 Internal Server Error` on `POST /opd/visits` (SAH-9's existing token-issue endpoint) when called with a real patient and doctor for the first time against the dev database. Tech-lead reviewed the full call chain (`VisitController` → `VisitService` → patient/team lookups → fee settings → token repository → audit log) via read-only inspection (no edits, to avoid colliding with the concurrent backend build in the same `backend/opd` package) and found no structurally obvious cause. Root cause not yet found; next step is the exact backend-terminal exception line, not yet provided.
