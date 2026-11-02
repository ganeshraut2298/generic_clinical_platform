# SAH-13 · Front-desk appointments workspace
Module: opd
Mockup screen: Appointments (sidebar → Appointments)
Source: Human, 2026-11-02

---

*Mockup screen reference: Appointments (sidebar → Appointments)*

## Problem
Front-desk staff need a dedicated workspace to view, book, and manage appointment slots across doctors and sessions (morning/evening) for the current day and future dates. The workspace must show real-time slot availability, support walk-in bookings, handle no-shows, and integrate with clinic-wide booking rules.

## Who uses it (roles) and in which setting (clinic, hospital ward, front desk)
- **Front desk** (primary): books appointments, manages walk-ins, marks arrivals/no-shows, views schedule
- **Doctor**: views their own schedule (read-only in this workspace)
- **Owner**: oversees booking rules, capacity, utilisation

Setting: Clinic front desk, during OPD hours.

## In scope / Out of scope

### In scope
- Day/week schedule grid: time slots × doctors × sessions
- Slot states: free, booked (patient name, mobile), arrived, no-show, past, walk-in
- Booking a slot: select patient (existing or new walk-in), confirm
- Walk-in slot creation and booking
- Mark arrival / mark no-show / cancel appointment
- Patient search and quick-add (name, mobile)
- Booking rules enforcement (max advance days, min gap, overbook toggle, same-day cutoff)
- Session config: morning/evening, slot duration, doctors per session
- Stats bar: total slots, booked, available, walk-ins, no-shows
- Responsive: collapses to day view on mobile
- Keyboard shortcuts (Go to… palette)

### Out of scope
- Recurring appointments (later)
- Patient self-booking portal (later, needs integration gateway)
- WhatsApp/SMS reminders (later, needs notifications service)
- Multi-clinic view (owner sees one clinic at a time)
- Doctor app consultation UI (separate "Doctor App" screen, not in mockup)

## Business rules (numbered)
1. **Slot grid**: Rows = time slots (e.g., 09:00, 09:15…), columns = doctors. Each doctor has morning/evening sessions with configurable slot duration (10/15/20 min).
2. **Booking rules** (clinic-wide, configured in SAH-12):
   - Max advance booking days (e.g., 30)
   - Min gap between slots (e.g., 0 min)
   - Overbook allowed (yes/no) — if no, double-booking prevented at DB level
   - Same-day cutoff time (e.g., 10:00 AM) — after this, no new bookings for today
   - Walk-in quota per session (e.g., 2 slots reserved)
3. **Slot states** (mutually exclusive):
   - `FREE` — available for booking
   - `BOOKED` — patient assigned, shows patient name + masked mobile
   - `ARRIVED` — patient checked in (green left border)
   - `NO_SHOW` — marked no-show (red left border, 72% opacity)
   - `WALK_IN` — reserved for walk-ins (striped background)
   - `PAST` — time passed, not arrived (55% opacity)
4. **Actions by role**:
   - Front desk: book, cancel, mark arrived, mark no-show, convert walk-in
   - Doctor: read own column only
   - Owner: all actions + modify booking rules
5. **Walk-in booking**: Front desk can book a `WALK_IN` slot directly, or convert a `FREE` slot to walk-in (if quota allows).
6. **No-show**: Marks slot `NO_SHOW`, increments patient no-show count (used in billing rules later).
7. **Date navigation**: Today, ±1 day, week view (7 days), jump to date.
8. **Session toggle**: Morning / Evening / All sessions.
9. **Patient search**: Type-ahead on name/mobile; if not found, "New walk-in patient" option.
10. **Accessibility**: Full keyboard navigation, ARIA labels, focus management in dialogs.

## Patient-safety and privacy points
- Never log patient name or mobile in audit for appointment actions (use patient ID only)
- Mask mobile in UI (●●●●●●7410)
- Slot grid does not show clinical details — only name + mobile
- Booking rules are clinic config, not per-patient

## Plan or entitlement (which plan/module unlocks it)
- Requires **OPD module** enabled in clinic plan (SAH-7)
- Works on Trial, Standard, and Pro plans
- Read-only on expired/read-only plans

## Open questions for the human
1. Should the slot grid show patient age/gender alongside name? (Mockup shows only name + masked mobile)
2. What happens when a patient has multiple bookings on the same day? (Mockup doesn't show conflict warning)
3. Is there a "block slot" feature for doctor breaks? (Not in mockup)
4. Should the week view show a mini-calendar for quick date picking? (Mockup has date picker only)