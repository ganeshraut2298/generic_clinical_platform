# SAH-90 · Front desk console, page and read side
Mockup screen: Appointments (sidebar → Appointments)
Module: opd
Requirement: `docs/requirements/SAH-90.md` (read it first: scope, deviations, rule numbers R1–R20).
Design: the detailed design is the front desk console mockup approved on 24 Nov 2026 (DEC-097). Decisions A1, B1, C1, D2, E1–E12 are binding.

Common to every story in this file
- Roles: OWNER and FRONT_DESK write; all other clinic roles read. Nothing on this page writes data except where a later story (SAH-91 to SAH-93) adds the action.
- Tenant: the clinic comes from the logged-in user; a user never sees another clinic's doctors, patients or appointments.
- UI text: plain English, sentence case, ₹ 1,25,000, 24 Oct 2026, 11:05 AM IST.
- Acceptance check 10 of the brief (lint, type check, existing tests pass; new backend logic has automated tests) applies to every story.

### SAH-90-1 · As front desk or owner, I want one console page with a header, live counters and a rules footer, so that I can see the state of the OPD day at a glance and keep it current without reloading
Mockup screen: Appointments (sidebar → Appointments)
Priority: Must

## Acceptance criteria
- Given a logged-in clinic user, when they open `/app/appointments/desk`, then the console shows inside the existing shell and sidebar with the title "Front desk console", the clinic name and the current date and time in IST (for example "Tue, 24 Nov 2026 · 11:05 AM").
- Given the Appointments menu entry, when the user opens `/app/appointments`, then the old board still works as before, and the console is reachable by its own route (acceptance check 9: existing screens and other modules still work).
- Given today's data, when the console loads, then the header shows five counters in this order: Booked, Waiting, In consult, Done, Free today.
- Given today's appointments and visits, then Booked counts appointments in BOOKED (no-show excluded), Waiting counts visits in Waiting or Vitals, In consult counts With doctor, Done counts Counselling, Billing and Done, and Free today counts the free bookable slots left today across all doctors.
- Given the user types in search or changes the scope toggle, then the counters do not change.
- Given another user books a slot or moves a patient, when 20 seconds pass with the tab visible, then counters, rail, finder and board update without a page reload.
- Given the browser tab is hidden, then polling pauses, and when the tab becomes visible again the page refreshes at once.
- Given the user performs an action on the page, then the data refreshes straight after the action and does not wait for the next 20 seconds.
- Given a poll fails (slow or lost network), then the last data stays on screen, a plain message "Could not refresh. Showing the last update." appears, and the page retries on the next cycle without losing what the user has typed or selected.
- Given the clinic's saved booking rules, when the footer loads, then it shows "Booking rules" and one read-only chip per saved rule that matches the saved values exactly (booking closes N h before, no-show after N min, follow-ups get priority, double booking allowed or not) (acceptance check 8).
- Given the reminder rule and the self-booking rule, then their chips are shown but disabled with the text "Needs WhatsApp (not in this phase)".
- Given the user is OWNER or FRONT_DESK, then "Slot settings" in the header and footer opens `/app/config/slots`; given the user is OWNER then "Change rules" opens the booking rules there; given any other role then the buttons are not shown or are disabled, and the server still refuses writes with 403.
- Given a clinic with HRA off, then no leave-related text appears anywhere on the page and nothing breaks.
- Given a viewport narrower than the three-column width, then the doctor rail, the centre and the action panel stack in that order, and the board scrolls horizontally inside its own container.
- Given keyboard use only, then every control is reachable in a logical order with visible focus; status is conveyed by text as well as colour; inputs have labels.
- Given a user with no clinic session, then the API returns 401 and the page goes to sign-in as for other pages.

## Notes for design
- Running late and the alerts strip are not built (R-out of scope). Per-doctor tokens, per-doctor follow-up fee, SAH-89 items are out of scope.
- A single summary read for counters is expected (analysis gap table); the architect decides the shape.
- `bookingRuleChips()` and the `WHATSAPP_NOT_IN_PHASE` text exist in the frontend.

### SAH-90-2 · As front desk or owner, I want a doctor rail with department filter, status and next free slot, so that I can find the right doctor quickly in a clinic with 10 to 50 doctors
Mockup screen: Appointments (sidebar → Appointments)
Priority: Must

## Acceptance criteria
- Given a clinic with 12 or more doctors, when the console loads, then every doctor appears as a compact row with name, department, status text, a status indicator and the next free slot, and the rail scrolls inside itself, not the whole page (acceptance check 1, scale part).
- Given the rail header, then it shows "N of M" doctors (shown of total).
- Given the Department list, then it holds "All departments" and each department once, sorted by name, and choosing one narrows the rail to that department.
- Given a doctor's department, then it is the Doctor EMR department, and when none is set it is the doctor's specialty; when neither exists the department shows as empty text and the doctor is listed only under "All departments".
- Given a doctor, then the status is derived: "In OPD" when they have a session today that has started and is not over; "Starts later" with the start time (for example "From 5:00 PM") when all of today's sessions are still ahead; "Off today" when they have no session today (decided, DEC-097 addendum); "On leave" when approved leave covers today and HRA is on; "Full today" when the doctor has sessions today but no free slots left.
- Given a status, then it is shown as text and as an indicator, never by colour alone.
- Given a doctor with free slots, then the next free text reads "Today · 7 free" or, when today has none, the next day with slots such as "Wed 25 · 8 free"; given no free slot in the next 7 days, then it reads "No slots".
- Given the next free slot for a doctor, then it ignores slots that are in the past, walk-in reserved or already booked, and slots inside the cut-off unless the user is OWNER or FRONT_DESK (DEC-097 addendum).
- Given the user clicks a doctor row, then that doctor becomes the selected doctor (row marked pressed), the finder shows that doctor's today, and a Confirm booking panel in progress returns to Idle.
- Given the user types in the search box, then doctors whose name or department contains the text are shown; when no doctor matches, the rail is not narrowed (as the mockup).
- Given the doctor list for the clinic, then it uses the current doctor list; the owner appears as a doctor only if the app lists them today (owner-as-doctor from SAH-89 is out of scope).
- Given a user from another clinic, then none of this clinic's doctors are visible to them.

## Notes for design
- Next free slot needs a set-based server query; today's single-slot approach is one query per slot.
- Status sources: session today, leave (HRA add-on), free counts. No running late.
- Open question 5 in the requirement: Decided (DEC-097 addendum), "Off today".

### SAH-90-3 · As front desk or owner, I want a slot finder for the selected doctor with a 7-day strip and Morning and Evening slots, so that I can see free times and pick one
Mockup screen: Appointments (sidebar → Appointments)
Priority: Must

## Acceptance criteria
- Given a selected doctor, then the finder shows their name, department, status, room, slot length (for example "15-min slots"), the consultation fee and the follow-up fee as computed by the clinic follow-up rule (C1), for example "Consultation ₹400 · Follow-up ₹200".
- Given the room, then it shows only when the doctor's department has a room label; otherwise the room is left out.
- Given the next 7 days starting today, then the strip shows seven day buttons, each with a label such as "Today" or "Wed 25" and the free count ("8 free") or "No slots" in text.
- Given a day button is clicked, then that day is selected (pressed) and the open slots for that day show grouped under "Morning" and "Evening".
- Given a day, then free slots exclude booked slots, slots in the past and walk-in reserved slots, and slots inside the cut-off are excluded for users other than OWNER and FRONT_DESK (staff see them with the note "Inside the booking cut-off", DEC-097 addendum), and are computed from the doctor's own slot length, sessions, working days and walk-in reserve.
- Given a doctor on approved leave on a day (HRA on), then that day shows "No slots".
- Given the selected day has no free slots, then a box reads "No slots on Tue, 24 Nov. Next free day: Wed, 25 Nov." and when no day in the next 7 days has slots, "No slots on Tue, 24 Nov or later this week.".
- Given a slot button, then it shows the time in the form "10:30 AM", has a pressed state and a visible focus ring, and is a real button.
- Given the user clicks a free slot, then the action panel opens in Confirm booking mode for that doctor, day and time (the booking itself is SAH-91).
- Given another user books a slot, when the next poll runs, then that slot disappears from the finder and the free count drops (acceptance check 2, read part).
- Given a user who is not OWNER or FRONT_DESK, then slots are shown but clicking them does not open booking.
- Given a slow network, then the previous slot list stays and a loading indicator shows; a failed load shows a plain error with a "Try again" button.

## Notes for design
- Slot logic exists in `AppointmentService.freeSlots` and `computeSlots`; a front-desk endpoint for range counts and one day's slots is needed.
- Follow-up fee uses the existing clinic rule and the doctor's current fee; no per-doctor follow-up fee.

### SAH-90-4 · As front desk or owner, I want a patient flow board with four columns and a scope toggle, so that I can see who is booked, waiting, in consultation and done today and what to do next for each
Mockup screen: Appointments (sidebar → Appointments)
Priority: Must

## Acceptance criteria
- Given today's data, then the board shows four columns in this order: "Booked", "Waiting", "In consultation", "Done", each with its count.
- Given the mapping, then Booked holds appointments in BOOKED and the no-show cards, Waiting holds visits in Waiting or Vitals, In consultation holds With doctor, and Done holds Counselling, Billing and Done; walk-in visits appear from Waiting onward (E2); cancelled appointments are not shown.
- Given a card, then it shows the time, patient name, doctor short name, visit type (New or Follow-up) and the token number when one is issued, and exactly one primary action for its state, or a tag instead of an action when no action applies.
- Given a Booked card, then its action is "Check in · token"; a Waiting card "Start consultation"; an In consultation card "Complete visit"; a Done card "Set follow-up", or a tag "Follow-up 1 Dec 2026 · 10:30 AM" or "No follow-up needed" when already set. The actions themselves are built in SAH-92 and SAH-93; this story shows the cards and the right label.
- Given a Booked appointment whose time plus the no-show grace has passed, then the card has a "No-show" tag and a "Rebook" action instead of "Check in · token".
- Given a Booked appointment due within the grace period, then it shows a "Due now" tag.
- Given a Booked appointment of a doctor on approved leave that day (HRA on), then the card has a "Doctor on leave" tag and a "Move to new slot" action.
- Given an empty column, then it shows "Nobody here".
- Given the toggle "All doctors" or "Selected doctor", then All doctors shows every doctor's cards and Selected doctor shows only the selected doctor's cards; the pressed state is visible in text and style.
- Given text in the search box, then board cards whose patient name contains it are shown; when none match, the board is not narrowed (acceptance check 7, board part).
- Given the search text and the doctor rail, then doctors are filtered by name or department and cards by patient name at the same time (acceptance check 7).
- Given a narrow viewport, then the board scrolls horizontally inside its own container and the page does not scroll sideways.
- Given a board with many cards, then each column keeps its own order by time, earliest first.
- Given a user who is not OWNER or FRONT_DESK, then cards show the information and tags but not the action buttons.
- Given another clinic's appointments, then they never appear.
- Given a slow or failed load, then the last board stays and the same refresh message as SAH-90-1 shows.

## Notes for design
- `GET /appointments` has no visit stage and no walk-ins today; a joined board read is needed.
- Patient names are shown on screen but never written to logs or audit rows.

### SAH-90-5 · As front desk or owner, I want an idle panel that lists follow-ups still to book, so that I know who should be called back and can start booking
Mockup screen: Appointments (sidebar → Appointments)
Priority: Should

## Acceptance criteria
- Given no action in progress, then the action panel title is "Next actions" and shows the text "Pick a slot to book, or use a button on a patient card. This panel follows what you are doing."
- Given follow-ups that are due today or overdue, have no appointment and are not marked "No follow-up needed", then the panel lists "Follow-ups to book · N" with, for each, patient name, a tag "Due today" or "Overdue", doctor short name, the reason and a "Find slot" button.
- Given an upcoming, returned or already booked follow-up, or one marked "No follow-up needed", then it is not listed.
- Given no follow-up to book, then the panel shows "All follow-ups are booked.".
- Given the user presses "Find slot", then the panel changes to Pick a slot mode with the patient and doctor preset (the booking flow is SAH-93 and SAH-91).
- Given the panel is in another mode, then a "Close" button returns it to Idle.
- Given the user is not OWNER or FRONT_DESK, then the list is shown but "Find slot" is not available.
- Given a follow-up of another clinic, then it never appears.
- Given a follow-up whose doctor is on leave or has no slots in 7 days, then it is still listed and "Find slot" shows "No slots" in the finder.

## Notes for design
- Source is the existing follow-ups read (DUE and MISSED) filtered to those without an appointment and not marked "no follow-up needed" (E9, column added under SAH-93).
- Until SAH-93 adds the "no follow-up needed" mark, no follow-up is hidden by that mark.
