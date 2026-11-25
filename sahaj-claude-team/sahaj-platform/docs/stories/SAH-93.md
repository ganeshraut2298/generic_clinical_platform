# SAH-93 · Front desk console, follow-up panel
Mockup screen: Appointments (sidebar → Appointments)
Module: opd
Requirement: `docs/requirements/SAH-90.md` (rules R1–R20). Depends on SAH-90, SAH-91 and SAH-92.
Design: the detailed design is the front desk console mockup approved on 24 Nov 2026 (DEC-097). Decisions C1 (clinic-wide follow-up rule), E9 (no follow-up needed stored), E1–E12 are binding.

Common to every story in this file
- Roles: OWNER and FRONT_DESK write; others read only and get 403 on write.
- Tenant: another clinic's patient, doctor, visit or follow-up is "not found" (404).
- Audit: every write is audited; no patient name, mobile number or clinical note in audit rows or logs.
- UI text: plain English, sentence case, ₹ 1,25,000, 24 Oct 2026, 11:05 AM IST.

### SAH-93-1 · As front desk or owner, I want a follow-up panel after Complete visit, so that I can book the next visit or record that none is needed before the patient leaves
Mockup screen: Appointments (sidebar → Appointments)
Priority: Must

## Acceptance criteria
- Given the user presses "Complete visit" (SAH-92-3), then the panel title is "Follow-up" and shows the patient name, the doctor and department, "Come back after" with three buttons "+7 days", "+15 days", "+1 month" (default "+7 days"), and the date that results (for example "Date: Tue, 1 Dec 2026"), computed in IST from today.
- Given a Done card without a follow-up, when the user presses "Set follow-up", then the same panel opens.
- Given an interval, then the panel shows "Earliest slots that day" with the first free slots of that doctor on that date, and when that date has no free slot, then it says "No slots on Tue, 1 Dec. Next free day: Wed, 2 Dec." and offers the slots of the next free day.
- Given the resulting date falls on a day the doctor does not work or is on approved leave (HRA on), then the panel does not offer it as free and gives the next free day.
- Given the user chooses a slot, then it is shown pressed; given none is chosen, then the first slot is selected by default.
- Given the follow-up fee, then "Book follow-up · ₹200" shows the fee from the clinic follow-up rule applied to the doctor's fee (C1).
- Given the user presses "Book follow-up", then one appointment of type follow-up is created for that patient and doctor at the chosen date and time with the same server checks as SAH-91 (grid, past, cut-off, slot booked once), and it is linked to the patient's follow-up record so the follow-up leaves the to-book list.
- Given success, then the Done card shows the tag "Follow-up 1 Dec 2026 · 10:30 AM", the panel returns to Idle, and the follow-up appears in the future day's finder count as booked.
- Given the slot was taken at the same moment by another user, then the server refuses with "That slot was just booked. Pick another time.", the slots refresh and the panel stays open.
- Given the user presses "No follow-up needed", then this is stored on the follow-up record (E9), the Done card shows the tag "No follow-up needed", the follow-up does not appear in the idle to-book list and the panel returns to Idle.
- Given a visit that already has a follow-up booked or marked, then "Set follow-up" is not offered again and the tag shows the result.
- Given a walk-in visit (no appointment), then the panel opens in the same way after Complete visit and uses the visit's follow-up record (decided, DEC-097 addendum: the panel also opens after Complete visit on a walk-in visit).
- Given the user closes the panel without choosing, then nothing is saved and the Done card still offers "Set follow-up".
- Given a follow-up already existing from the doctor in the EMR for this visit, then it is shown as the follow-up to book and is not duplicated.
- Given an interval changes after a slot was chosen, then the slot choice resets.
- Given a user who is not OWNER or FRONT_DESK, then the panel does not offer the buttons and the server returns 403.
- Given a visit or follow-up of another clinic, then the server returns 404.
- Given a slow or failed network, then buttons are disabled while waiting, a failure shows a plain message and a retry does not create two appointments or two marks.
- Acceptance check 1: given a clinic with 12 or more doctors, then find slot, book, check in, token, start, complete and follow-up can all be done without leaving the page.

## Notes for design
- Today there is no desk write that links an appointment to `follow_up.appointment_id`, and no stored "not needed"; both are additive (E9).
- Interval keys D7, D15 and M1 exist in the follow-up table.
- The fee follows the clinic rule; no per-doctor follow-up fee.

### SAH-93-2 · As front desk or owner, I want "Find slot" on a follow-up in the idle list to book that follow-up in the finder, so that overdue patients are called back with the right doctor and visit type
Mockup screen: Appointments (sidebar → Appointments)
Priority: Should

## Acceptance criteria
- Given a follow-up in the idle list, when the user presses "Find slot", then the panel opens in "Pick a slot" mode with the patient preset and the hint "Follow-up with Dr. Meera K. · Thyroid report review", the follow-up's doctor is selected in the rail, and the finder shows the doctor's first day with free slots.
- Given the user picks a slot, then Confirm booking shows the patient as a fixed name, the visit type "Follow-up" preselected with its fee from the clinic rule, and "Confirm booking · ₹200".
- Given the user may change the visit type to "New consultation", then the fee changes to the fee of the chosen visit type, and the booking is still linked to the follow-up record, which is marked as booked (decided, DEC-097 addendum).
- Given the user confirms, then one follow-up appointment is created, linked to the follow-up record, the follow-up leaves the to-book list, the count "Follow-ups to book · N" drops, and the panel shows "Booked".
- Given the doctor has no free slot in the next 7 days, then the finder says "No slots on ... or later this week." and the user can choose another doctor in the rail; the follow-up stays in the list until booked.
- Given the follow-up is booked by another user meanwhile, when the user confirms, then the server refuses with a clear message and the list refreshes.
- Given the follow-up is overdue, then it still shows "Overdue" in the list until booked; booking never changes the original due date record except to link the appointment.
- Given the user closes the panel, then nothing is saved and the follow-up stays in the list.
- Given a user who is not OWNER or FRONT_DESK, then "Find slot" is not available and the server returns 403.
- Given a follow-up of another clinic, then it never appears and the server returns 404.
- Given the console runs under the old Follow-ups screen at the same time, then a follow-up booked in one screen leaves the list of the other on its next refresh.

## Notes for design
- Reuses the Pick a slot mode of SAH-91-3 and the booking server checks of SAH-91-2.
- A follow-up with its own doctor and reason is already in the existing follow-ups read.

## Open questions
1. Decided (DEC-097 addendum): A follow-up booked from the idle list as "New consultation" still marks the follow-up as booked. The fee is set by the visit type chosen.
