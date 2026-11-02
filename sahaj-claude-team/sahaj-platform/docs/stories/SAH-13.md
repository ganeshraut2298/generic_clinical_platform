Mockup screen: Appointments (sidebar → Appointments)

### SAH-13-1 · As a front-desk user, I want to see the day's appointment grid with all doctors and sessions, so that I can view slot availability at a glance
Priority: Must
## Acceptance criteria
- Given I am signed in as front desk, when I open Appointments, then I see a grid with time-slot rows and doctor columns for the selected date and session(s).
- Given the grid loads, then each slot shows its state: FREE (dashed border, grey text), BOOKED (patient name + masked mobile), ARRIVED (green left border), NO_SHOW (red left border, 72% opacity), WALK_IN (striped), PAST (55% opacity).
- Given I switch session (Morning / Evening / All), then the grid updates to show only slots in that session.
- Given I change the date (Today / ±1 day / Week view / date picker), then the grid reloads for that date.
- Given I am a doctor, then I see only my own column (other columns hidden).
- Given no slots exist for the date, then I see an empty state with "No sessions configured" and a link to Clinic setup → OPD workflow.
- Given the clinic has no doctors configured, then I see an empty state with "No doctors in team" and a link to Clinic setup → Team and access.
- Given network is slow, then a skeleton loader shows for the grid.
- Given I am on mobile (< 620px), then the grid collapses to a single-column day view with session tabs.

## Notes for design
- Mockup: `.sched-wrap` / `.sched` for the grid; `.apws` / `.apmain` / `.apdesk` for the workspace layout.
- Slot component: `.slot2` with modifiers `.booked`, `.arrived`, `.noshow`, `.walk`, `.past`.
- Stats bar: `.apstats` (5 KPIs: total, booked, available, walk-in, no-show).
- Session toggle: `.seg` with Morning/Evening/All.
- Date navigator: `.aptool` with `.aplabel` (date) and `.aprules` pills.

---

### SAH-13-2 · As a front-desk user, I want to book an appointment for an existing patient, so that the slot shows the patient's name and mobile
Priority: Must
## Acceptance criteria
- Given I click a FREE slot, then a booking dialog opens with patient search (name/mobile type-ahead).
- Given I type ≥2 chars, then matching patients from this clinic appear (name, masked mobile, ABHA if present).
- Given I select a patient and confirm, then the slot becomes BOOKED showing patient name + masked mobile.
- Given the patient has another booking at the same time with another doctor, then a warning shows but booking is allowed (clinical decision).
- Given the clinic has "overbook: false" and the slot is already BOOKED, then I cannot book it (409 conflict).
- Given I try to book past the max advance days, then the dialog shows an error with the limit.
- Given I try to book a WALK_IN reserved slot, then it is allowed (walk-in quota not enforced on booking existing patient).
- Given booking succeeds, then an audit event is recorded (action: `appointment.booked`, entity: `visit`, patientId, doctorId, slotStart).
- Given I press Escape, then the dialog closes without booking.

## Notes for design
- Mockup: booking dialog (not explicitly shown but implied by slot click).
- Patient search: reuse patient search from SAH-9 (`.ptsearch`, `.chips`).
- Dialog: `.sheet` / `.modal` pattern.
- Error states: `.errs` list.

---

### SAH-13-3 · As a front-desk user, I want to book a walk-in patient (new or existing) into a walk-in or free slot, so that same-day patients are accommodated
Priority: Must
## Acceptance criteria
- Given I click a WALK_IN slot, then a walk-in booking dialog opens with "New walk-in patient" pre-selected.
- Given I enter name and mobile (valid Indian mobile), then the slot becomes BOOKED (walk-in) with patient name.
- Given I click a FREE slot and choose "Walk-in" in the dialog, then if walk-in quota for the session is not exceeded, the slot becomes WALK_IN → BOOKED.
- Given walk-in quota is exceeded, then the dialog shows a warning but allows override (owner only).
- Given the patient is new, then a patient record is created with `source: WALK_IN` and the appointment links to it.
- Given the patient exists, then the existing record is used and `walk_in_count` increments.

## Notes for design
- Walk-in slot styling: `.slot2.walk` (repeating linear gradient striped).
- Dialog: include quick fields — name, mobile, (optional) age/gender.
- Quota indicator in stats bar: walk-in used / total.

---

### SAH-13-4 · As a front-desk user, I want to mark a patient as arrived or no-show, so that the queue (SAH-14) and doctor see real-time status
Priority: Must
## Acceptance criteria
- Given a BOOKED slot, when I click "Mark arrived", then the slot becomes ARRIVED (green left border) and a visit token is created (links to SAH-14 Queue).
- Given a BOOKED slot past its start time + grace period (configurable, default 15 min), when I click "Mark no-show", then the slot becomes NO_SHOW (red left border, 72% opacity) and patient no-show count increments.
- Given an ARRIVED slot, then "Mark arrived" is disabled; "Cancel" is available.
- Given a NO_SHOW slot, then "Mark no-show" is disabled; "Revert to booked" is available (owner only).
- Given I mark arrived/no-show, then an audit event is recorded (action: `appointment.arrived` / `appointment.no_show`).

## Notes for design
- Slot actions: `.qact` with `.btn.sm` for Arrived / No-show / Cancel.
- Arrived state: `.slot2.booked.arrived` (green left border).
- No-show state: `.slot2.booked.noshow` (red left border, opacity 0.72).

---

### SAH-13-5 · As a front-desk user, I want to cancel an appointment and optionally rebook, so that slots are freed correctly
Priority: Must
## Acceptance criteria
- Given a BOOKED or ARRIVED slot, when I click "Cancel", then a confirmation dialog shows (patient name, time, doctor).
- Given I confirm cancellation, then the slot becomes FREE and the visit (if arrived) is cancelled.
- Given the patient had a no-show fee rule, then cancellation before start time waives it; after start time, fee applies (billing integration later).
- Given I cancel, then an audit event is recorded (action: `appointment.cancelled`, reason optional).

## Notes for design
- Cancel button in `.qact` (red ghost button).
- Confirmation dialog: `.sheet` with patient details and reason selector (optional).

---

### SAH-13-6 · As a clinic owner, I want to configure booking rules (advance days, overbook, same-day cutoff, walk-in quota), so that front-desk booking follows clinic policy
Priority: Must
## Acceptance criteria
- Given I am the owner, when I open Clinic setup → Appointments and slots (SAH-11) or Booking rules (SAH-12), then I can set:
  - Max advance booking days (1–90, default 30)
  - Overbook allowed (toggle, default false)
  - Same-day cutoff time (HH:MM, default 10:00)
  - Walk-in quota per session (0–10, default 2)
  - Slot duration (10/15/20 min, default 15)
  - Min gap between slots (0–30 min, default 0)
- Given rules are saved, then they are enforced in the appointments workspace (SAH-13-1 to 13-5).
- Given I am not the owner, then I see the rules read-only (pills in `.aprules`).

## Notes for design
- This story overlaps with SAH-12 (Booking rules). The API contract for rules is defined in SAH-12.
- Mockup: `.aprules` shows pills for each active rule.
- Rules are stored in `opd.booking_rules` table (created in SAH-11/12).

---

### SAH-13-7 · As a front-desk user, I want to switch to week view and see 7 days at a glance, so that I can plan ahead
Priority: Should
## Acceptance criteria
- Given I click "Week" in the date navigator, then the grid shows 7 columns (Mon–Sun) with each day's sessions.
- Given week view, then each day column shows a condensed grid (time slots × doctors) with slot counts (booked/total).
- Given I click a day in week view, then I switch to day view for that date.
- Given week view on mobile, then it shows a horizontal scrollable list of day cards with summary stats.

## Notes for design
- Mockup: `.wk` table for week view (`.wkday`, `.wkcell`, `.wkcell.busy`).
- Day cell shows a mini meter (`.meter`) for utilisation.

---

### SAH-13-8 · As a front-desk user, I want to search/filter appointments by patient name or mobile, so that I can find a specific booking quickly
Priority: Should
## Acceptance criteria
- Given I type in the appointment search box (in `.aptool`), then the grid filters to show only slots for matching patients.
- Given search results, then each matching slot highlights the patient name.
- Given I clear search, then the full grid returns.
- Given no matches, then an empty state shows "No appointments found for '...'".

## Notes for design
- Search input in `.aptool` (not explicitly in mockup but standard pattern).
- Reuse patient search debounce logic from SAH-9.

---

### SAH-13-9 · As a doctor, I want to see my own schedule for the day in the appointments workspace, so that I know my slots without the full grid
Priority: Should
## Acceptance criteria
- Given I am signed in as a doctor, when I open Appointments, then I see only my column (other doctors hidden).
- Given my column, then I see all my sessions (morning/evening) with slot states.
- Given I am a visiting doctor, then I see only my assigned sessions.
- Given I have no sessions configured, then I see "No sessions configured for you" with a link to request schedule from owner.

## Notes for design
- Mockup: doctor-specific column view.
- Role check: `CurrentRole` → DOCTOR / VISITING_DOCTOR.

---

### SAH-13-10 · As a front-desk user, I want keyboard shortcuts (Go to… palette) to jump to date, patient, or doctor, so that I can navigate faster during peak hours
Priority: Could
## Acceptance criteria
- Given I press `Cmd/Ctrl + K`, then the command palette (`.cmdsheet`) opens.
- Given the palette, then I can type a date (e.g., "25 Dec"), a patient name, or a doctor name.
- Given I select a result, then the workspace navigates to that date/patient/doctor.
- Given I press `Esc`, then the palette closes.

## Notes for design
- Mockup: `.cmdk` trigger, `.cmdsheet` palette, `.cmdi` items.
- Shortcuts: `G D` for date, `G P` for patient, `G O` for doctor (shown in `.cmdfoot`).