# SAH-91 · Front desk console, booking and reschedule
Mockup screen: Appointments (sidebar → Appointments)
Module: opd
Requirement: `docs/requirements/SAH-90.md` (rules R1–R20). Depends on SAH-90 (page, finder, board).
Design: the detailed design is the front desk console mockup approved on 24 Nov 2026 (DEC-097). Decisions A1, B1, C1, D2, E1–E12 are binding.

Common to every story in this file
- Roles: OWNER and FRONT_DESK write; every other role gets 403 from the server and sees no write buttons.
- Tenant: the clinic comes from the logged-in user; another clinic's doctor, patient or appointment is "not found" (404), never visible.
- Audit: every write is audited; no patient name, mobile number or clinical note in audit rows or logs.
- UI text: plain English, sentence case, ₹ 1,25,000, 24 Oct 2026, 11:05 AM IST.

### SAH-91-1 · As front desk or owner, I want to pick a free slot and confirm a booking for a patient found by name or mobile, so that I can book a visit without leaving the console
Mockup screen: Appointments (sidebar → Appointments)
Priority: Must

## Acceptance criteria
- Given a free slot inside the cut-off, when staff (OWNER or FRONT_DESK) view it in the finder, then it shows the note "Inside the booking cut-off" and can still be picked.
- Given the user clicks a free slot in the finder, then the panel title becomes "Confirm booking" and shows the doctor and department, and the day, time and slot length (for example "Wed, 25 Nov · 10:30 AM · 15 min").
- Given the Patient field, when the user types a name or a mobile number, then matching patients of the clinic are listed with name and mobile, using the existing patient search; the field is labelled.
- Given no match, then the panel offers "New patient" with the minimal fields (name, mobile, and any field the existing quick patient form already requires) and creates the patient, then selects them.
- Given the minimal new patient form, when a required field is empty or the mobile number is not valid, then the field shows a plain error and nothing is saved.
- Given a patient is chosen, then the panel shows their name and mobile so that staff can confirm the right person.
- Given the Visit type buttons "New consultation" and "Follow-up", then each shows its fee in ₹ and the selected one is pressed; New shows the doctor's consultation fee, Follow-up shows the fee from the clinic follow-up rule (free, half or full of the doctor's fee) (C1); the confirm button reads "Confirm booking · ₹400" and changes with the type.
- Given a follow-up fee of zero under the clinic rule, then it shows "₹0" or "Free" consistently and the booking is still allowed.
- Given the user presses "Confirm booking", then one appointment is created for that patient, doctor, date and time, with the chosen visit type and source front desk, and it is audited.
- Given the booking is for today, then a card appears in the Booked column, the slot leaves the finder and the counters update without a page reload (acceptance check 2).
- Given the booking is for a later day, then the free count of that day in the strip drops and the card appears on that day.
- Given success, then the panel shows "Booked" with patient, doctor, date and time and a note "Added to Booked in today's flow." or "It will appear in the flow on that day.", and a "Book another" button that returns the panel to Idle.
- Given the user closes the panel before confirming, then nothing is saved.
- Given the same patient already has an active appointment with the same doctor at the same time, then the server refuses with a clear message and nothing is saved.
- Given a user who is not OWNER or FRONT_DESK, then the server returns 403 and the screen shows no confirm button.
- Given another clinic's patient id in the request, then the server returns 404.
- Given the server is slow or the network drops during confirm, then the button is disabled while waiting, a failure shows "Could not book. Check the connection and try again." and a retry does not create a second appointment if the first one was saved.

## Notes for design
- Patient search is `GET /patients?q=` and minimal create is `POST /patients` (D2). The SAH-89 mobile lookup is not used.
- The fee on screen is information on the booking; billing at check-in follows SAH-9 and SAH-10 (SAH-11 AC 4).
- Patient names show on screen but not in logs or audit.

### SAH-91-2 · As front desk or owner, I want the server to refuse bad or clashing bookings, so that two people cannot book the same slot and no impossible time is booked
Mockup screen: Appointments (sidebar → Appointments)
Priority: Must

## Acceptance criteria
- Given two front desk users who confirm the same doctor, date and time at the same moment, then exactly one booking is saved and the other gets a clear error "That slot was just booked. Pick another time." (acceptance check 4), and a concurrency test proves it.
- Given the loser of that race, then the screen refreshes the finder, the slot is no longer offered, and the panel stays open so the user can pick another time.
- Given a time that is not on the doctor's slot grid for that day (for example 10:07 AM with 15-minute slots), then the server refuses with 400 and a plain message.
- Given a doctor who does not work that day or a time outside their sessions, then the server refuses with 400.
- Given a walk-in reserved slot, then the server refuses to book it here.
- Given a time that is already in the past, then the server refuses with 400 "That time has passed.".
- Given a time inside the cut-off, where the cut-off is the doctor's own cut-off when set, otherwise the clinic's "booking closes N hours before", when the booking comes from any source other than OWNER or FRONT_DESK, then the server refuses with 400 naming the cut-off.
- Given a slot inside the cut-off, when OWNER or FRONT_DESK books it, then the booking is allowed (SAH-11 rule kept; decided in the DEC-097 addendum), and the console shows staff the note "Inside the booking cut-off" on such slots.
- Given a doctor with no cut-off set, then the clinic value is used.
- Given the optional per-doctor cut-off, then OWNER and FRONT_DESK can set or clear it in the doctor's slot settings, and clearing it returns the doctor to the clinic value; the value must be zero or more hours and a whole number or the server returns 400.
- Given a doctor on approved leave on that date (HRA on), then the server refuses the booking with a plain message.
- Given the clinic allows double booking ("overbook" on), then the existing rule from SAH-11 stays: only OWNER and FRONT_DESK may book a taken slot, and the console states that the slot is already booked before confirming. Given overbook off, then a second booking in a taken slot is refused.
- Given a request body with a clinic id, then it is ignored; the clinic comes from the logged-in user.
- Given an invalid doctor id, date or time format, then the server returns 400; given a doctor of another clinic, 404.
- Given the old board at `/app/appointments` books an appointment, then the same server checks apply to it (the checks are in the server, not in the screen).

## Notes for design
- The transaction lock already exists (`AppointmentDoubleBookingIT`); keep it (brief allows it) and add the missing checks listed in the analysis. `max_online_per_day` is not enforced for desk booking (out of scope).
- The per-doctor cut-off is an additive optional value on the doctor's slot settings.

### SAH-91-3 · As front desk or owner, I want to move a patient to a new slot or rebook a no-show in place, so that the patient appears once, in the new slot
Mockup screen: Appointments (sidebar → Appointments)
Priority: Must

## Acceptance criteria
- Given a no-show card, when the user presses "Rebook", then the panel opens in "Pick a slot" mode with the patient name preset, the hint "Rebooking the 11:15 AM appointment with Dr. Meera K.", the same doctor selected and the day set to today, with today's first free slot shown first, so the desk can rebook and then check in a late patient (decided, DEC-097 addendum; to be reviewed after go-live). When today has no free slot, the day is the doctor's first day with free slots.
- Given a Booked card with the tag "Doctor on leave" (HRA on), when the user presses "Move to new slot", then the panel opens in "Pick a slot" mode in the same way; the user may choose a different doctor in the rail.
- Given "Pick a slot" mode, then the panel says "Pick a day and a time in the slot finder. The doctor is already selected." and the user picks a day and a free time as usual.
- Given the user picked a slot in this mode, then Confirm booking shows the patient as a fixed name (no patient search), the visit type kept from the old appointment, and the fee.
- Given the user confirms, then the same appointment (same id) is updated in place to the new doctor, date and time and set to BOOKED, the old slot becomes free, and the change is audited (E4).
- Given the update, then the board shows the appointment once, in its new slot, with no duplicate card (acceptance check 6), and the counters update.
- Given a reschedule, then the same server checks as booking apply (grid, past time, cut-off, leave, slot booked once) and the same race rule applies; as for booking, OWNER and FRONT_DESK may move an appointment into a slot inside the cut-off and see the note "Inside the booking cut-off".
- Given only appointments in BOOKED or NOSHOW status, then reschedule is allowed; given ARRIVED or CANCELLED, then the server refuses with 409 and a plain message.
- Given two users reschedule the same appointment at the same time, then one succeeds and the other gets a clear error and sees the current state after refresh.
- Given the user closes the panel before confirming, then the old appointment stays unchanged.
- Given a reschedule, then the audit row records the old and new doctor, date and time, but no patient name.
- Given a user who is not OWNER or FRONT_DESK, then the buttons are not shown and the server returns 403.
- Given an appointment of another clinic, then the server returns 404.
- Given the new slot is taken at the moment of confirm, then the error is shown and the old appointment is unchanged.
- Given an invalid slot (not on grid, past; or inside the cut-off for a source other than OWNER or FRONT_DESK), then the server returns 400 and the old appointment is unchanged.

## Notes for design
- New endpoint updates the same row; no new appointment row is created and the old one is not cancelled (E4).
- "Doctor on leave" needs HRA leave; without HRA the tag does not appear.
- A no-show appointment is only a display state until marked; the server must treat a BOOKED appointment past its grace the same way when rebooked.
