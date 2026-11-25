# DEC-097 · SAH-90 Front desk console: scope, tokens, completion, fees, order

Date: 24 Nov 2026 · Status: DECIDED 24 Nov (A1, B1, C1, D2); items E decided by tech lead under DEC-073; the human may override them.
Context: the human asked to replace the Appointments screen with the single-page Front desk console (`C:\Users\Hp\Downloads\sahaj-front-desk-console.html`, brief `front-desk-console-implementation-prompt.md`). Phase 1 analysis: docs/handoffs/SAH-90-phase1-analysis.md.

## Blocked until answered
Business analyst stories for SAH-90. No code or migration starts before `stories` and `contract` approvals.

## Human-only prerequisite
Save the console mockup into `docs/design/` (for example `docs/design/mockup/sahaj-front-desk-console.html`) and update screen-map row "Appointments (sidebar → Appointments)" to point at it. Agents cannot write there.

## A. Token number
- **A1 (recommended): keep the clinic-wide daily number** (shown as today, e.g. T-8). The brief says "follow the existing token format". Fix the check-in race and add a concurrent check-in test. Two simultaneous check-ins get two different consecutive numbers. Pro: no change to Queue, EMR, billing, pharmacy, prints, IPD. Con: not per doctor as in the mockup's example.
- A2: add a per-doctor daily number alongside (new counter + column, additive), shown on the console and the slip. Pro: matches the mockup's "MK-08" idea. Con: two numbers per visit; initials collide (two "AP", two "SJ") so a stored doctor code is needed; larger change.

## B. "Start consultation" and "Complete visit" from the desk
- **B1 (recommended): the desk may press both, using the existing stage moves** (same as the Queue board today). Complete = leaves "With doctor"; the Done column holds COUNSEL, BILLING and DONE. Follow-up panel opens after Complete. Doctors still sign the EMR as today.
- B2: doctor-only; the desk card shows "With doctor" with no button and the follow-up panel opens when the doctor finishes.

## C. Follow-up fee
- **C1 (recommended): keep the clinic-wide follow-up rule** (free, half or full of the doctor's fee). The console shows the computed figure. No platform data change.
- C2: add a per-doctor follow-up fee (platform data and contract change, adds to SAH-89 fee work).

## D. Order of work
- **D1 (recommended): after the pharmacy commit and SAH-89.** The console uses SAH-89 parts (owner as doctor, doctor fee, mobile lookup).
- D2: pause SAH-89 and do SAH-90 first (the console would use name/mobile search and the current doctor list).

## E. Decided by tech lead (DEC-073), override if you disagree
1. New route `/app/appointments/desk` under the existing Appointments menu; the old board stays at `/app/appointments` until you approve the switch (that is the "feature flag").
2. Board mapping: Booked = appointment BOOKED (No-show tag on NOSHOW); Waiting = visit WAITING or VITALS; In consultation = WITH_DOCTOR; Done = COUNSEL, BILLING, DONE. Walk-in visits appear from Waiting onward.
3. No-show: shown automatically once `no_show_after_minutes` has passed (display rule, as in the current mockup) with a "Rebook" action and a manual mark; no background job (avoids a new scheduler ADR).
4. Reschedule: new endpoint updates the same appointment in place (same id, audited). "Move to new slot" and no-show "Rebook" use it; no duplicate rows.
5. Slot booked once: keep the transaction lock (allowed by the brief) and add the missing server checks: on the doctor's grid, not in the past, cut-off. Concurrency test for two bookings.
6. Per-doctor overrides: slot length and walk-in reserve already per doctor; add an optional per-doctor booking cut-off (additive, clinic value when empty).
7. Doctor status in the rail: derived only (In OPD from today's session, Starts later from session start, On leave from approved leave when HRA is on, Full today from counts). "Running late" and the alerts strip are not built (no data source; notifying patients is WhatsApp, Later).
8. Department from Doctor EMR departments, falling back to the doctor's specialty; room from the department's room label when there is one, otherwise not shown.
9. "No follow-up needed" is stored on the follow-up (additive column) so the to-book list stays correct.
10. Live refresh: polling every 20 s while the tab is visible, plus refresh after each action.
11. WhatsApp chips and "Send on WhatsApp": shown disabled with "Needs WhatsApp (not in this phase)". Print slip: browser print of a small slip layout.
12. Split: SAH-90 read side (console page, rail, finder, board, counters, rule chips), SAH-91 booking and reschedule, SAH-92 check-in, stage actions and token panel, SAH-93 follow-up panel. One branch each, in order.

## Human answers
(pending)
- 24 Nov 2026 (AskUserQuestion): A = A1 keep the clinic-wide daily token (fix the check-in race, add a concurrency test). B = B1 the desk may start and complete. C = C1 clinic-wide follow-up rule. D = **D2: SAH-90 before SAH-89**. SAH-89 is paused. The console uses the current doctor list (no owner-as-doctor), the doctor fee as it exists today, and `GET /patients?q=` for name or mobile search (not the SAH-89 lookup). E items stand unless the human overrides them.
- Status: DECIDED. Phase 1 is reported to the human. Phase 2 (stories, then plan and contract) waits for the human's go-ahead and for the console mockup to be saved in docs/design.

## Addendum 24 Nov: BA open questions on SAH-90..93, decided by the tech lead (DEC-073)
1. Cut-off for staff: **keep the SAH-11 rule.** OWNER and FRONT_DESK may book inside the cut-off. The cut-off (the doctor's own value, or the clinic value when the doctor has none) applies to every other booking source, and the console shows a "Inside the booking cut-off" note to staff. The approved rule is not changed.
2. Start consultation while Vitals is on: the existing stage-owner rule decides. If the server refuses, the desk sees the server's message.
3. Done column and Done counter: include COUNSEL, BILLING and DONE, as in E2.
4. The follow-up panel also opens after Complete visit on a walk-in visit.
5. A doctor with no session today shows "Off today".
6. A patient arrives after the no-show tag is shown: follow the mockup and show Rebook. Rebook opens on today's first free slot, so the desk can rebook and then check in. To be reviewed after go-live.
7. A follow-up booked from the idle list as "New consultation" still marks the follow-up as booked. The fee is set by the visit type chosen.

## Addendum 2 (24 Nov): architect open questions from ADR-032, decided by the tech lead (DEC-073)
1. If a department has several room labels, show them all, joined with ", ".
2. Follow-ups marked "No follow-up needed" leave the Follow-ups lists (Due, Missed, Upcoming) and the Today counts. A later visit does not mark them returned. This is a deliberate change to the old Follow-ups screen.
3. Follow-ups booked when the doctor signs the EMR keep their current rule: no cut-off.
4. Doctor rail status: "From 5:00 PM" between sessions, and "Full today" after the last session ends.
5. A session that starts before 12:00 IST is Morning; anything later is Evening.
6. Check-in is allowed only on the visit day, on the old board as well.
7. Free-slot counts depend on the caller's role (staff can see slots inside the cut-off).
