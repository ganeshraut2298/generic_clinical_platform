# SAH-11 · Appointments and booking rules
Mockup screen: Appointments (sidebar → Appointments); Clinic setup → OPD workflow, Appointments and slots, Booking rules
Module: opd

## 1. Story
As front-desk staff, I want to book, see and manage appointments per doctor, with each doctor's own slot pattern and the clinic's booking rules, so that walk-ins and bookings share the day without clashing, and an arriving patient becomes a token with one click.

This story deliberately bundles the Appointments screen with its own Clinic setup configuration (slot settings and booking rules), because they are the same data in the mockup and neither is useful without the other.

## Acceptance criteria
1. Given a doctor's slot settings (slot length, one or two sessions with start/end time, working days, every Nth slot kept for walk-ins, max online bookings a day), then the day's bookable slots are computed from them exactly as the mockup's `slotsFor` does.
2. Given the clinic's booking rules (booking closes N hours before a slot, no-show after N minutes, follow-ups get priority, patients can self-book, double-booking allowed), then: booking is refused within the close-before window (unless staff override — front desk/owner can always book); a booked slot shown as "Due" becomes "Late" after the no-show window (display only, no auto-cancel); overbook, when off, refuses a second booking in an already-booked slot (403/409); when on, only OWNER/FRONT_DESK may double-book. Self-book is stored but has no effect yet (no WhatsApp/patient-facing booking channel exists in Phase 1).
3. Given the Appointments screen (Today/Tomorrow, one column per doctor), then staff can book (pick or register a patient, as in SAH-8/9), cancel, mark no-show, and — once arrived — "Create token", which issues a SAH-9 visit linked back to the appointment and marks it Arrived with that token number.
4. Given a follow-up-type appointment converted to a token, then the fee follows the same follow-up rule SAH-9 already uses (not recomputed here).
5. Given only the owner, then booking rules and overbook can be changed; front desk can change per-doctor slot settings (length, sessions, days, walk-in reserve, max online) same as the mockup's `ro()` (read-only for anyone who isn't owner or front desk).
6. Given any write, then it is audited and tenant isolation (RLS) applies exactly as prior stories. A patient's name never appears in clear in an audit row.
7. Given a non-owner, non-front-desk role, then the screen is read-only (no book/cancel/no-show/create-token actions), and the API returns 403 on write.

## 3. Technical design
- New tables, schema `opd`:
  - `opd.doctor_slot_template`: `doctor_membership_id` (PK, FK `platform.membership`), `slot_minutes` (check 5-60), `working_days` (smallint[] of 0-6), `walk_in_every_nth` (0 = none), `max_online_per_day`, `updated_at/by`, `version`. FORCE RLS via `platform.membership`'s tenant (join or denormalize `tenant_id` onto this table — denormalize, same pattern as `doctor_profile`).
  - `opd.doctor_slot_session`: `id`, `tenant_id`, `doctor_membership_id`, `session_index` (0 or 1), `start_time`, `end_time` (check end > start), unique `(doctor_membership_id, session_index)`. FORCE RLS.
  - `opd.booking_rules`: `tenant_id` (PK), `close_before_hours`, `remind_before_hours` (stored, unused until notifications exist), `no_show_after_minutes`, `follow_priority boolean`, `self_book boolean` (stored, unused), `overbook boolean`, `updated_at/by`, `version`. FORCE RLS, one row per tenant (default row created on first read if missing, same lazy pattern as SAH-10's bill).
  - `opd.appointment`: `id`, `tenant_id`, `patient_id` (FK `patient.patient`), `doctor_membership_id` (FK `platform.membership`), `appt_date`, `appt_time`, `status` (check BOOKED/ARRIVED/NOSHOW/CANCELLED), `visit_type` (NEW/FOLLOW_UP), `source` (check PHONE/WHATSAPP/FRONT_DESK), `note`, `visit_id` (nullable FK `opd.visit`, set on "Create token"), `created_at/by`, `updated_at/by`, `version`. FORCE RLS, index `(tenant_id, doctor_membership_id, appt_date)`, unique `(tenant_id, doctor_membership_id, appt_date, appt_time)` where status not in (CANCELLED) — a partial unique index, to allow re-booking a cancelled slot.
- "Create token" calls the same visit-issue logic SAH-9 built (reuse `VisitService`/the port it already exposes internally, or add a thin method that issues a visit and stamps `opd.appointment.visit_id` + status ARRIVED in one transaction) rather than duplicating token numbering or fee logic.
- Week view, WhatsApp reminders, self-book, reschedule and the online-booking patient channel are all out of scope (see below); build the day/tomorrow view and the data model so none of those need a schema change later.

## 4. API
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/opd/doctor-slots` | any clinic role | none | `DoctorSlotSettings[]` | 401 |
| PUT | `/opd/doctor-slots/{doctorMembershipId}` | OWNER, FRONT_DESK | `DoctorSlotSettings` | `DoctorSlotSettings` | 400, 403, 404 |
| GET | `/opd/booking-rules` | any clinic role | none | `BookingRules` | 401 |
| PUT | `/opd/booking-rules` | OWNER | `BookingRules` | `BookingRules` | 400, 403 |
| GET | `/opd/appointments?date=&doctorMembershipId=` | any clinic role | none | `Appointment[]` | 401 |
| POST | `/opd/appointments` | OWNER, FRONT_DESK | `AppointmentCreate` | `Appointment` | 400, 403, 404, 409 |
| PATCH | `/opd/appointments/{id}` | OWNER, FRONT_DESK | `{status}` (CANCELLED or NOSHOW) | `Appointment` | 400, 403, 404 |
| POST | `/opd/appointments/{id}/token` | OWNER, FRONT_DESK | none | `Appointment` (with `visitId`, `tokenNo`) | 400, 403, 404, 409 |

All paths under `/api/v1`. Contract: `docs/api/opd.yaml` (extend the existing file).

## 5. Tasks
### Tasks — Backend
- Migrations, repositories, slot computation (port of `slotsFor`), booking-rule enforcement (close-before, overbook), appointment CRUD, create-token (reusing SAH-9's visit issue), audit.
- Tests: slot computation matches hand-worked examples, close-before refusal, overbook on/off, create-token links the visit, cross-tenant 404, non-owner 403 on rule changes, front-desk can change slots but not rules.

### Tasks — Frontend
- Clinic setup: extend the existing config layout (frontend/src/app/features/config) with two new panels — per-doctor slot settings and clinic-wide booking rules — matching the mockup's `cfgSlots`.
- Route `/app/appointments` behind the shell (menu key `appointments` should exist from SAH-5; add if missing). Today/Tomorrow toggle, doctor filter, stat tiles (booked/arrived/no-show/free/walk-in-reserved), the rule chips row (read-only here, with a link into Clinic setup), one column per doctor showing today's slots with booked appointments or "Free".
- Appointment detail sheet: when/doctor/visit-type/source/status, actions per status (cancel, mark no-show, Create token when arrived-eligible) — "Remind"/"Send reminder" show as disabled with "Needs WhatsApp (not in this phase)" rather than being removed, so the layout matches the mockup.
- Book dialog: pick a free slot, then the same patient pick/register flow as SAH-9's new-token dialog, doctor is fixed by the column clicked.
- Unit tests for slot rendering, booking-rule display, and the book/cancel/create-token flows (mock HTTP).

## 6. Out of scope, Open questions
Out of scope: week view, WhatsApp reminders and self-book (no notification service in Phase 1), reschedule (the mockup itself marks this "soon"), online/patient-facing booking, recurring appointments.
Open questions: none blocking; proceeding on the defaults above per DEC-008.

## 7. Status log
