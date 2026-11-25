# SAH-90 · Front desk console · Phase 1 analysis (read-only)

Date: 24 Nov 2026 · Branch: none yet (analysis only, no files changed by agents) · Authors: solution-architect (backend, contract, DB), angular-developer (frontend), merged by tech lead.
Inputs: `C:\Users\Hp\Downloads\sahaj-front-desk-console.html` (reference mockup, not yet in docs/design), `C:\Users\Hp\Downloads\front-desk-console-implementation-prompt.md` (human brief). Decisions: DEC-097.

## 1. Stack
| Area | Fact |
|---|---|
| Backend | Spring Boot 3.5.16, Spring Modulith 1.4.14, Java 17, JdbcClient with hand-written SQL (no JPA). opd talks to other modules through ports: `PatientLookupPort`, `TeamMemberPort` (has `feePaise`), `AuditLog`, `DoctorBlockedDatesPort` (implemented by hra). |
| Auth | `@PreAuthorize` + service re-checks; `OpdRoles`: `ANY_CLINIC_ROLE`, `OWNER`, `ISSUE_TOKEN` (OWNER, FRONT_DESK, NURSE), `OWNER_OR_FRONT_DESK`. |
| Tenancy | `TenantBindingTransactionManager` sets `app.tenant_id` per transaction; every opd table has forced RLS on `platform.current_tenant()`. |
| Real time | None (no WebSocket/SSE, no data polling). Only `@Scheduled` job is EMR partition upkeep. |
| Notifications | None (WhatsApp/SMS are Later, ADR-021). `remind_before_hours`, `self_book` are stored only. |
| Frontend | Angular 22.2 standalone + Material 22.2, signals, OnPush, Vitest. Typed `*.api.ts` on HttpClient, `problemDetailInterceptor`. No feature-flag mechanism; sidebar comes from `/me.menu` + `menuGuard`. Helpers: `computeSlots` (`shared/scheduling/slot.util.ts`), IST date helpers, `formatInr`, `bookingRuleChips()`, `WHATSAPP_NOT_IN_PHASE`. |

## 2. What exists today
**Backend (opd.yaml 0.8.0, base `/api/v1/opd`)**
| Capability | Endpoint | Roles |
|---|---|---|
| Doctor slot pattern | `GET/PUT /doctor-slots[/{doctorMembershipId}]` | read any; write OWNER, FRONT_DESK |
| Booking rules | `GET/PUT /booking-rules` | read any; write OWNER |
| Appointment list | `GET /appointments?date=&doctorMembershipId=` (one day) | any |
| Book | `POST /appointments` | OWNER, FRONT_DESK |
| Cancel / no-show | `PATCH /appointments/{id}` (from BOOKED only) | OWNER, FRONT_DESK |
| Check-in → token | `POST /appointments/{id}/token` (creates visit, ARRIVED) | OWNER, FRONT_DESK |
| Walk-in token | `POST /visits` | OWNER, FRONT_DESK, NURSE |
| Queue | `GET /visits/today?mine=` | any |
| Stage move | `PATCH /visits/{id}/stage` (one step forward) | any, stage-owner check |
| Follow-ups | `GET /follow-ups?status=DUE|MISSED|UPCOMING|RETURNED` (read only) | any |
| Today figures | `GET /today-summary` | any |
| Patients | `GET /patients?q=`, `POST /patients`; `POST /patients/lookup-by-mobile` is in patient.yaml 0.3.0 (SAH-89, PROPOSED), not built |

Tables: `opd.doctor_slot_template`, `opd.doctor_slot_session`, `opd.booking_rules`, `opd.appointment` (BOOKED, ARRIVED, NOSHOW, CANCELLED; NEW/FOLLOW_UP), `opd.visit` (WAITING, VITALS, WITH_DOCTOR, COUNSEL, BILLING, DONE; `token_no`), `opd.visit_token_counter` + `opd.next_token_no()`, `opd.follow_up` (interval keys D3 D7 D14 D15 M1 M3), `platform.doctor_profile` (`fee_paise`, free-text `specialty`), `opd.emr_department` + `opd.emr_department_doctor` (room labels per department).

**Frontend:** `/app/appointments` → `AppointmentsBoardPage` (SAH-11; today/tomorrow, per-doctor columns, book and detail dialogs, rule chips). `/app/queue` (SAH-9 stage board), `/app/followups` (SAH-14), `/app/config/slots` (slot settings and booking rules), `/app/config/rules`. Roles: `canManageAppointments` and `canEditSlotSettings` = OWNER, FRONT_DESK; `canEditBookingRules` = OWNER.

**Concurrency today**
- Slot booked once: unique index was dropped (V2026_11_01_1045, overbook rule); prevented by `pg_advisory_xact_lock` on tenant|doctor|date|time + check inside a transaction (`AppointmentDoubleBookingIT`). Exact start times only.
- Token: `opd.next_token_no()` (`INSERT … ON CONFLICT DO UPDATE RETURNING`, row lock) + unique `(tenant_id, token_date, token_no)` → gap-free and unique, **per clinic per day, not per doctor**. Uses DB `current_date` (not pinned to IST). No concurrent check-in test.
- **Defect found:** check-in (`createToken`) reads the appointment then updates without `FOR UPDATE`; two simultaneous check-ins of the same appointment can create two visits. Cancel vs check-in has the same race.
- **Gaps in booking checks:** `create` does not verify the time is on the doctor's grid, not in the past and not a walk-in slot; `max_online_per_day` is never enforced; the cut-off is never applied to OWNER or FRONT_DESK.

## 3. Gap table
| Console capability | Status | Notes |
|---|---|---|
| Header title, clinic, date/time | Supported | `ShellStore.me()`, IST helpers |
| One search (doctor, department, patient on board) | Supported with changes | client-side filter |
| Counters Booked/Waiting/In consult/Done/Free today | Missing | needs appointment + visit stage + free count; one summary endpoint |
| Slot settings / Change rules buttons | Supported | `/app/config/slots` (role-gated) |
| Doctor rail: status, department, next free slot | Missing | no status source; leave only via HRA add-on (whole days); "starts later" derivable from session; department from `emr_department` or `specialty`; next free needs a set-based server query (current `freeSlots` is one query per slot) |
| Slot finder: fees, room, slot length | Supported with changes | consult fee per doctor (SAH-89 D8); follow-up fee clinic-wide only; room only per department |
| 7-day free counts, Morning/Evening, next free day | Supported with changes | logic in `AppointmentService.freeSlots` + `computeSlots`; no front-desk endpoint |
| Board 4 columns + scope toggle | Supported with changes | `GET /appointments` lacks visit stage; walk-ins not included; needs a joined board read |
| Check in · token | Supported | race defect above; no patients-ahead in response |
| Start consultation | Supported with changes | stage move; with Vitals on it is two steps |
| Complete visit | Supported with changes, needs decision | moves to COUNSEL/BILLING not DONE; bypasses EMR sign |
| No-show (automatic) | Missing | `no_show_after_minutes` unused; no scheduler |
| Doctor on leave → move to new slot / reschedule | Missing | SAH-11 put reschedule out of scope |
| Idle: follow-ups to book | Supported with changes | DUE + MISSED lists; filter those with no appointment |
| Confirm booking: patient by mobile, new patient | Supported with changes | `GET /patients?q=` or SAH-89 lookup-by-mobile |
| Token issued: patients ahead, wait | Supported with changes | computable; room has no source |
| Follow-up panel: book / no follow-up needed | Missing | no desk write to link `follow_up.appointment_id`; no stored "not needed" |
| Footer rule chips | Supported | `getBookingRules` + `bookingRuleChips()` |
| Print slip | Missing | no token print |
| Send on WhatsApp, reminders, self-booking, notify waiting | Not phase 1 | use the "Needs WhatsApp (not in this phase)" convention |
| Live refresh | Missing | polling needed |
| Feature flag | Missing | use a new route |

## 4. Risks
1. Process: the new mockup must be saved in `docs/design/` and screen-map row "Appointments" updated by a human before any story is approved (CLAUDE.md rule 1).
2. Size: this is several stories, not one (DEC-097 proposes the split).
3. Depends on SAH-89 contracts (owner as doctor in the doctor list, doctor fee, lookup-by-mobile), which are not yet approved.
4. Token scope: per-doctor tokens would touch Queue, EMR, billing, pharmacy, prints and IPD; mockup initials collide (two "AP", two "SJ").
5. Two status sources (appointment status and visit stage) must be mapped, not duplicated.
6. Only destructive path: dropping `uq_visit_tenant_token_date_token` (only if tokens switch to per-doctor in place). Everything else is additive.
