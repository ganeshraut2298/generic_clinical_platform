# SAH-90 · Front desk console (single-page Appointments workspace)
Module: opd
Mockup screen: Appointments (sidebar → Appointments)
Source: Human brief `front-desk-console-implementation-prompt.md` and console mockup `sahaj-front-desk-console.html`, approved as the design in chat on 24 Nov 2026 (DEC-097). Human answers A1, B1, C1, D2 and tech-lead decisions E1–E12 in `docs/decisions/DEC-097-sah90-front-desk-console.md` are binding. Analysis: `docs/handoffs/SAH-90-phase1-analysis.md`.

Note on the design: the detailed design is the front desk console mockup approved on 24 Nov 2026 (DEC-097). It is not yet saved in `docs/design/`; a human must save it and point the screen-map row "Appointments (sidebar → Appointments)" at it before the stories are approved. Until then the reference is `C:\Users\Hp\Downloads\sahaj-front-desk-console.html`, and its script's `renderVals()` is the behaviour specification (data is sample only).

Delivery is split into four stories files, one branch each, in this order:
- SAH-90 console page, read side (rail, finder, board, counters, rule chips, idle follow-up list, polling).
- SAH-91 booking and reschedule.
- SAH-92 check-in, token panel, start and complete, no-show.
- SAH-93 follow-up panel.

## Problem
Front desk staff today use several screens to run the OPD day: the Appointments board (SAH-11), the Queue (SAH-9) and Follow-ups (SAH-14). To book a patient, check them in, hand over a token and set the next visit, they move between pages and cannot see which doctor has free slots, who is waiting and who is due back. Two defects also exist in booking and check-in: the server does not check that a booked time is on the doctor's grid, is not in the past or respects the cut-off; and two simultaneous check-ins of the same appointment can create two visits. The clinic wants one page where the whole front desk flow can be done and the board stays current without a reload.

## Who uses it (roles) and in which setting
- Front desk and owner (write): clinic front desk counter. Search doctors, find slots, book, reschedule, check in, issue tokens, start and complete visits, set follow-ups.
- Doctor, visiting doctor, nurse, billing, pharmacist (read): can open the console and see the same data as the current board; no write buttons (shown disabled or hidden per current SAH-11 rules). Nurse keeps the existing right to issue a walk-in token elsewhere; that is not part of this console.
- Owner only: Change rules (booking rules). Owner and front desk: Slot settings.
- Setting: OPD clinics only; one tenant per request, the clinic is taken from the logged-in user.

## In scope / Out of scope
In scope
- New route `/app/appointments/desk` under the existing Appointments menu entry (E1). The old board at `/app/appointments` stays until the human approves the switch.
- Header: title, clinic name and current date and time, one search box, five counters (Booked, Waiting, In consult, Done, Free today), Slot settings button.
- Doctor rail: all clinic doctors, department filter, derived status text and indicator, next free slot, scrolls inside the rail.
- Slot finder for the selected doctor: name, department, room (where a department room label exists), slot length, consultation fee and follow-up fee, 7-day strip with free counts, Morning and Evening slots, no-slots message with next free day.
- Patient flow board with four columns and the All doctors / Selected doctor toggle; one primary action per card.
- Action panel modes: Idle (follow-ups to book), Pick a slot, Confirm booking, Booking confirmed, Token issued, Follow-up.
- Footer: active booking rules as read-only chips from saved settings, with Slot settings and Change rules buttons to `/app/config/slots` according to role.
- Booking with patient search by name or mobile (existing `GET /patients?q=`) and minimal new patient; visit type and fee.
- Reschedule in place ("Move to new slot", no-show "Rebook").
- Server checks for booking: on the doctor's grid, not in the past, within cut-off (optional per-doctor cut-off), slot booked once under concurrency.
- Check-in to token with the check-in race fixed; token panel with patients ahead and estimated wait; print slip via browser print.
- Start consultation and Complete visit from the desk using the existing stage moves (B1).
- No-show shown automatically after the grace period (display rule), plus manual mark.
- Follow-up panel after Complete visit; "No follow-up needed" stored; Find slot from the idle list.
- Polling every 20 seconds while the tab is visible, and a refresh after each action (E10).
- Responsive layout (rail, centre, panel stack at narrow widths; board scrolls horizontally in its own container) and accessibility (real buttons, labelled inputs, keyboard, visible focus, status never by colour alone).
- Automated tests for slot locking, token sequence and stage transitions.

Out of scope
- Running-late status and the alerts strip (running late, "Notify waiting patients"). No data source exists, and notifying patients needs WhatsApp (E7).
- WhatsApp sending, reminders, patient self-booking, "Notify waiting patients". Shown disabled with the text "Needs WhatsApp (not in this phase)" (E11). This covers the "Send on WhatsApp" button, the "WhatsApp reminder 2 h before" chip and the "Patients can book on WhatsApp" chip.
- Per-doctor token numbers such as "MK-08" (A1).
- Per-doctor follow-up fee (C1).
- Owner-as-doctor, doctor fee changes and mobile lookup from SAH-89 (D2; SAH-89 is paused).
- Background no-show job; automatic status change of stored appointments (E3).
- Deleting or changing the old Appointments board, Queue or Follow-ups screens.
- Walk-in token creation from the console (not in the mockup; stays on the Queue screen).
- Week view beyond the 7-day strip, recurring appointments, online booking.
- Enforcing "max online bookings a day" for desk bookings (it is a limit for online booking, which does not exist in phase 1).

## Deviations from the console mockup, with the reason
| # | Mockup shows | Built as | Reason |
| --- | --- | --- | --- |
| 1 | Token like "MK-08", per doctor | Clinic-wide daily token as the app shows today (for example T-8) | A1: no change to Queue, EMR, billing, pharmacy, prints, IPD; the brief says follow the existing token format. Initials also collide in the sample. |
| 2 | Check-in "Patients ahead" is per doctor | Patients ahead = patients of the same doctor waiting before this one; estimated wait = patients ahead × that doctor's slot length (as `renderVals`) | Token is clinic-wide but waiting is per doctor. |
| 3 | Running-late status ("30 min late") and alerts strip with "Notify waiting patients" | Not built | E7: no data source; notification is WhatsApp (Later). The alerts strip is also not rendered in the mockup template. |
| 4 | Doctor status: In OPD, Full today, late, On leave, From 5:00 PM | Derived only: In OPD (has a session today), Starts later (session start in the future), Off today (no session today; wording decided, DEC-097 addendum 5), On leave (approved leave, only when HRA is on), Full today (no free slots today) | E7. |
| 5 | Room per doctor ("Room 2") | Room from the department's room label when one exists, otherwise not shown | E8: room has no per-doctor source. |
| 6 | Department per doctor | Doctor EMR department; falls back to the doctor's specialty | E8. |
| 7 | Follow-up fee per doctor (for example ₹250) | Computed from the clinic-wide follow-up rule (free, half or full of the doctor's consultation fee) | C1. |
| 8 | Footer chips "WhatsApp reminder 2 h before", "Patients can book on WhatsApp" | Chips shown from saved settings but disabled with "Needs WhatsApp (not in this phase)" | E11: reminders and self-booking have no effect in phase 1 (stored only). |
| 9 | "Send on WhatsApp" on the token panel | Disabled with "Needs WhatsApp (not in this phase)" | E11. |
| 10 | "Print slip" placeholder | Browser print of a small slip layout | E11. |
| 11 | Patient picker is a fixed list ("Omkar Lokhande · 98220 12345", "New patient") | Search by name or mobile with the existing patient search, plus minimal new patient | D2: current patient search, not the SAH-89 mobile lookup. Mockup data is sample only. |
| 12 | Done column holds only completed visits | Done column holds visits in Counselling, Billing or Done | E2: existing stage values are mapped, not duplicated. |
| 13 | Complete visit goes straight to Done | Complete moves the visit one step with the existing stage move (it leaves "With doctor") | B1; the doctor still signs the EMR as today. |
| 14 | No-show is a stored stage | Display rule once the grace period has passed, plus manual mark; no background job | E3. |
| 15 | Reschedule removes and re-adds an appointment | The same appointment is updated in place (same id, audited) | E4: appears once, no duplicates. |
| 16 | Live data is in memory | Polling every 20 s while visible, refresh after each action | E10: there is no real-time channel in the app. |
| 17 | Follow-up date labels are fixed ("Tue, 1 Dec") | Date computed from the doctor's calendar from today plus 7 days, 15 days or 1 month | Sample data only. |
| 18 | "Booked (sample)" in the booking confirmed panel | "Booked" with patient, doctor, date and time | Sample label. |
| 19 | Search keeps the full list when nothing matches | Same behaviour kept: with no match, the lists are not narrowed | `renderVals` behaviour spec. |

## Business rules (numbered)
1. The console is a new route `/app/appointments/desk`; the old board stays reachable at `/app/appointments`. Nothing else changes while the console is off the menu default.
2. Roles: OWNER and FRONT_DESK may book, reschedule, check in, start, complete and set follow-ups. Every other clinic role reads only. Slot settings button: OWNER and FRONT_DESK. Change rules button: OWNER only. Write attempts by other roles get 403 from the server.
3. Everything is limited to the logged-in user's clinic. The clinic is never taken from the request body.
4. Board mapping (E2): Booked column = appointment status BOOKED, with a "No-show" tag for NOSHOW; Waiting = visit stage WAITING or VITALS; In consultation = WITH_DOCTOR; Done = COUNSEL, BILLING or DONE. Walk-in visits appear from Waiting onward. Cancelled appointments are not shown.
5. Primary action per card: Booked = "Check in · token"; Waiting = "Start consultation"; In consultation = "Complete visit"; Done = "Set follow-up" (or a tag showing the booked follow-up or "No follow-up needed"); No-show = "Rebook"; Booked with doctor on leave = "Move to new slot".
6. Counters are for today across all doctors and do not change with search or the scope toggle. Booked = appointments in BOOKED (no-show excluded as in the mockup), Waiting, In consult, Done as the column mapping; Free today = free bookable slots left today across all doctors.
7. A slot can be booked once. Two people booking the same slot at the same moment result in one booking and one clear error. This is enforced on the server in a transaction, not on the screen (E5).
8. Server booking checks (E5, E6): the time is on the doctor's slot grid for that day; the time is not in the past; the booking is not inside the cut-off, except for staff. The cut-off is the doctor's own cut-off when set, otherwise the clinic's "booking closes N hours before". As in SAH-11, OWNER and FRONT_DESK may book inside the cut-off; the cut-off applies to every other booking source, and the console shows staff the note "Inside the booking cut-off" on such slots (DEC-097 addendum 1). Walk-in reserved slots are not bookable here.
9. Slot length, walk-in reserve and cut-off can differ per doctor and fall back to the clinic value (E6). The optional per-doctor cut-off is added to the doctor's slot settings.
10. Free counts, finder slots, board and counters come from the server and reflect other users' changes within 20 seconds, and straight away after the user's own action (E10). Polling pauses when the browser tab is hidden.
11. Rescheduling updates the same appointment in place to the new doctor, date and time, with the same checks as booking; the old slot becomes free; it is audited (E4).
12. Fee on booking: New consultation = the doctor's consultation fee as it exists today. Follow-up = the clinic follow-up rule applied to that fee (C1). The fee on screen is information on the booking; billing continues to work as in SAH-9 and SAH-10.
13. Check-in creates one visit for the appointment, marks it arrived, records the arrival time and issues the next clinic-wide daily token (A1). Two check-ins of the same appointment produce one visit; two check-ins of different appointments at the same time produce two different consecutive tokens. Check-in versus cancel of the same appointment ends in one outcome only. The token day is the clinic's calendar day in IST.
14. Start consultation and Complete visit use the existing one-step stage move and its stage-owner rule (B1). Start time and end time are recorded. Invalid transitions are rejected by the server.
15. No-show is shown on a BOOKED appointment once `no_show_after_minutes` has passed after its time (display only). Staff can also mark no-show manually (existing action). A no-show card offers "Rebook".
16. Follow-up panel opens after Complete visit and from "Set follow-up" on a Done card. Intervals: +7 days, +15 days, +1 month (existing interval keys D7, D15, M1). Booking a follow-up creates an appointment of type follow-up linked to the follow-up record. "No follow-up needed" is stored on the follow-up so it leaves the to-book list (E9).
17. Follow-ups to book (idle panel) lists follow-ups that are due today or overdue and that have no appointment and are not marked "no follow-up needed". "Find slot" opens Pick a slot with the patient and doctor preset.
18. Footer chips are read from the clinic's saved booking rules and must match them exactly.
19. Every write is audited. The audit row and logs never contain patient names, mobile numbers or clinical notes.
20. UI text: plain English, sentence case, ₹ 1,25,000, 24 Oct 2026, 11:05 AM IST.

## Patient-safety and privacy points
- Wrong-patient risk: the Confirm booking panel shows name and mobile of the chosen patient and the booking confirmed panel repeats them, so staff can catch a wrong pick. Patient search returns only the clinic's own patients.
- Token and queue: a duplicated or skipped token can send the wrong patient to the doctor. Check-in is idempotent per appointment and tokens are unique and gap-free per clinic per day.
- Complete visit by front desk (B1) moves the visit stage only; it does not sign or change the EMR, which the doctor still signs.
- The printed slip carries token, doctor and date and the patient's name only; no diagnosis, no clinical notes, no ABHA number.
- Audit and logs: no names, mobile numbers, ABHA numbers or clinical notes. Data stays in India as for all of Sahaj.
- A rescheduled or rebooked appointment keeps its history through audit, so a missed patient can be traced.
- Doctor on leave: appointments with a doctor on approved leave are flagged on the board so the patient is not left without a visit.

## Plan or entitlement (which plan/module unlocks it)
OPD module (all clinic plans that have Appointments today). "On leave" status and the "Doctor on leave" tag use the HRA leave add-on and appear only when HRA is on for the clinic; without HRA the console works with the other statuses. WhatsApp features stay disabled in phase 1.

## Open questions for the human
All decided by the tech lead in the DEC-097 addendum of 24 Nov (DEC-073). Kept here for the record.
1. Decided (DEC-097 addendum): Cut-off for staff. The SAH-11 rule is kept. OWNER and FRONT_DESK may book inside the cut-off; the cut-off (doctor's own value, else the clinic value) applies to all other booking sources; the console shows staff the note "Inside the booking cut-off". The SAH-11 rule is not changed.
2. Decided (DEC-097 addendum): Start consultation with Vitals on. The existing stage-owner rule decides; if the server refuses, the desk sees the server's message.
3. Decided (DEC-097 addendum): Done column and Done counter include Counselling, Billing and Done.
4. Decided (DEC-097 addendum): The follow-up panel also opens after Complete visit on a walk-in visit, linked to the visit's follow-up record.
5. Decided (DEC-097 addendum): A doctor with no session today shows "Off today".
