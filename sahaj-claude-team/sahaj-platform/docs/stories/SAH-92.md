# SAH-92 · Front desk console, check-in, token and visit stages
Mockup screen: Appointments (sidebar → Appointments)
Module: opd
Requirement: `docs/requirements/SAH-90.md` (rules R1–R20). Depends on SAH-90 and SAH-91.
Design: the detailed design is the front desk console mockup approved on 24 Nov 2026 (DEC-097). Decisions A1 (clinic-wide daily token), B1 (desk may start and complete), E1–E12 are binding.

Common to every story in this file
- Roles: OWNER and FRONT_DESK write; others read only and get 403 on write. The existing stage-owner rule of the stage move still applies.
- Tenant: another clinic's appointment or visit is "not found" (404).
- Audit: every write is audited; no patient name, mobile number or clinical note in audit rows or logs.
- UI text: plain English, sentence case, ₹ 1,25,000, 24 Oct 2026, 11:05 AM IST.

### SAH-92-1 · As front desk or owner, I want "Check in · token" to issue the next token safely, so that two patients never get the same token or two visits
Mockup screen: Appointments (sidebar → Appointments)
Priority: Must

## Acceptance criteria
- Given a Booked card, when the user presses "Check in · token", then one visit is created, the appointment becomes Arrived and linked to the visit, the arrival time is recorded, the next clinic-wide daily token is issued, and the card moves to the Waiting column showing the token (for example "T-8").
- Given the token, then it keeps the current format and numbering of the app (clinic-wide, per day, gap-free) (A1, E: no per-doctor number); the board, token panel and slip show the same number.
- Given the token day, then the day is the clinic's calendar day in IST, not the database server's day.
- Given two front desk users who check in the same appointment at the same moment, then exactly one visit is created and one token issued; the other gets a clear message "This patient is already checked in." and sees the current state after refresh.
- Given two users who check in different appointments at the same moment, then two different consecutive tokens are issued with no gap and no duplicate (acceptance check 5), and a concurrency test proves both cases.
- Given a user cancels an appointment while another checks it in, then only one of them succeeds and the other gets a 409 with a plain message; no cancelled appointment holds a token and no visit exists for a cancelled appointment.
- Given an appointment that is not in BOOKED status (already arrived, cancelled, no-show not rebooked), then the server refuses with 409.
- Given a no-show card, then "Check in · token" is not offered; the card shows "Rebook", which opens on today's first free slot, so the desk can rebook the late patient and then check them in (decided, DEC-097 addendum; to be reviewed after go-live).
- Given a booking for another day, then check-in is refused with 400 "Check-in is only possible on the day of the visit.".
- Given a successful check-in, then the token panel opens (SAH-92-2), the counters change (Booked down by one, Waiting up by one) and the card appears in Waiting without a page reload (acceptance check 3, token part).
- Given the visit fee, then it follows the same rules as SAH-9 and SAH-11 (follow-up rule for follow-up type); this story does not recompute it.
- Given a user who is not OWNER or FRONT_DESK, then the button is not shown and the server returns 403.
- Given a slow network, then the button is disabled while waiting and a retry does not create a second visit.

## Notes for design
- Defect from the analysis: check-in reads the appointment and updates without locking, so two check-ins can create two visits. Fix with a lock or a status-guarded update in one transaction.
- Token counter uses `opd.next_token_no()`; today it uses the database `current_date`. Pin to IST. Add a concurrent check-in test (none exists).
- Cancel versus check-in has the same race and needs the same fix.

### SAH-92-2 · As front desk or owner, I want a token panel with patients ahead, estimated wait and a printable slip, so that I can tell the patient where they stand and hand them a slip
Mockup screen: Appointments (sidebar → Appointments)
Priority: Must

## Acceptance criteria
- Given a successful check-in, then the panel title is "Token issued" and shows "Token", the token number in large text, the patient name, the doctor name and the room (when the doctor's department has a room label) and a wait line.
- Given the wait line, then it reads "Next in line" when no other patient of the same doctor is waiting before them, or "3 ahead · about 45 min" where patients ahead = patients of the same doctor in Waiting or Vitals who were checked in before this patient, and minutes = patients ahead × the doctor's slot length.
- Given the figure is an estimate, then the text says "about" and is not shown as a promise.
- Given the user presses "Print slip", then the browser print dialog opens with a small slip layout showing clinic name, token, patient name, doctor, date and time (24 Oct 2026, 11:05 AM IST) and nothing clinical; the rest of the page is not printed (E11).
- Given the slip, then it never shows diagnosis, notes or ABHA number.
- Given the "Send on WhatsApp" button, then it is shown but disabled with the text "Needs WhatsApp (not in this phase)".
- Given the user presses "Close", then the panel returns to Idle.
- Given another patient checks in meanwhile, then the panel does not change the numbers it already showed for this token, but reopening it from the card shows the current patients ahead.
- Given a Waiting card with a token, when the user clicks the token on the card, then the token panel opens for it (read for all roles, with Print slip enabled for all roles).
- Given a patient of another clinic, then the panel never shows them.
- Given a user who is not OWNER or FRONT_DESK, then they can open the panel from the card and print, but cannot check in.

## Notes for design
- Patients ahead and the wait figure can be computed from the visit queue; the architect decides whether the check-in response carries them.
- Print uses the browser; no server printing, no document service.

### SAH-92-3 · As front desk or owner, I want to start a consultation and complete a visit from the board, so that the board reflects what happens in the room without sending staff to the Queue screen
Mockup screen: Appointments (sidebar → Appointments)
Priority: Must

## Acceptance criteria
- Given a Waiting card, when the user presses "Start consultation", then the visit moves one step with the existing stage move, the start time is recorded and the card moves to In consultation (B1).
- Given the clinic has the Vitals step on, then Start consultation uses the existing stage rules; when the server refuses because the stage owner rule does not allow the desk to do this step, then the screen shows the server's plain message and the card stays (decided, DEC-097 addendum: the existing stage-owner rule decides, also when Vitals is on).
- Given a card in Vitals shown in the Waiting column, then it carries a text tag "Vitals" so staff can tell it from a patient not yet at vitals.
- Given an In consultation card, when the user presses "Complete visit", then the visit leaves "With doctor" with the existing stage move (to Counselling or Billing as the clinic's workflow has it), the end time is recorded, the card moves to Done (the Done column holds Counselling, Billing and Done; decided, DEC-097 addendum), and the Follow-up panel opens (SAH-93).
- Given Complete visit by the desk, then the doctor's EMR is not signed, changed or locked by this action; the doctor signs the EMR as today.
- Given a visit stage move that is not one step forward, then the server rejects it with 409, and the screen shows a plain message and refreshes.
- Given two users press the same action on the same card at the same time, then one step is made once and the other gets a clear message and sees the current state (acceptance check 10, stage transitions test).
- Given a walk-in visit (no appointment), then the same Start and Complete actions apply from its Waiting card.
- Given the counters, then they update straight after each action.
- Given a user who is not OWNER or FRONT_DESK, then the buttons are not shown and the server returns 403 for the desk path.
- Given a visit of another clinic, then the server returns 404.
- Given a slow network, then the button is disabled while waiting and a retry does not move the visit twice.
- Acceptance check 1: given a clinic with 12 or more doctors, then the whole flow (find slot, book, check in, token, start, complete, follow-up) can be done on this one page without opening another screen.

## Notes for design
- Use `PATCH /visits/{id}/stage` as is (analysis: with Vitals on it is two steps; Complete moves to COUNSEL or BILLING, not DONE).
- Automated tests for stage transitions are required (check 10).

### SAH-92-4 · As front desk or owner, I want missed appointments to show as no-show and to be markable by hand, so that the day's board shows who did not come and the slot can be rebooked
Mockup screen: Appointments (sidebar → Appointments)
Priority: Should

## Acceptance criteria
- Given a Booked appointment today, when the time plus the clinic's "no-show after N minutes" has passed, then the card in the Booked column shows a "No-show" tag in text and a "Rebook" action, without any stored change and without a background job (E3).
- Given the grace has not passed, then the card shows "Check in · token" (and "Due now" when the time has come).
- Given the clinic changes "no-show after N minutes", then the tag appears or disappears on the next refresh accordingly.
- Given a Booked card, when OWNER or FRONT_DESK marks it as no-show by hand (existing action), then the status is stored as no-show, audited, and the card shows the "No-show" tag with "Rebook"; the counters update (Booked down by one, because no-show is not counted in Booked).
- Given a card that is shown as no-show only by the display rule, then Rebook works as in SAH-91-3 and does not require a manual mark first.
- Given an appointment already Arrived or Cancelled, then a manual no-show is refused with 409.
- Given a user cancels or marks no-show while another checks in, then only one of the two succeeds (see SAH-92-1).
- Given a user who is not OWNER or FRONT_DESK, then the mark action is not shown and the server returns 403.
- Given an appointment of another clinic, then the server returns 404.
- Given times, then they are compared in IST.

## Notes for design
- `no_show_after_minutes` exists but is unused today; the display rule runs in the screen and on the board read.
- Open question 1: Decided (DEC-097 addendum), check-in of a no-show is not offered; use Rebook.

## Open questions
1. Decided (DEC-097 addendum): A patient who arrives late, after the no-show tag has appeared: follow the mockup and show Rebook. Rebook opens on today's first free slot, so the desk can rebook and then check in. To be reviewed after go-live.
