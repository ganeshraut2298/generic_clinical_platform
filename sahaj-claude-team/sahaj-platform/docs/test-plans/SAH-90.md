# Test plan SAH-90..93 · Front desk console

Branch: feature/SAH-90-front-desk-console. Stories: SAH-90 (page and read side), SAH-91 (book, checks, reschedule), SAH-92 (check-in, token, stages, no-show), SAH-93 (follow-up). Mockup screen: Appointments (sidebar → Appointments), console at `/app/appointments/desk`.
Sources: `docs/stories/SAH-90..93.md`, `docs/requirements/SAH-90.md`, DEC-097 (both addenda), ADR-032, `docs/api/opd.yaml` 0.9.0, `docs/database/SAH-90-front-desk-console.md`, brief acceptance checks 1 to 10.

## Layers and where the tests are

| Layer | Files | What it proves |
| --- | --- | --- |
| Backend integration (real PostgreSQL `sahaj_test`, RLS, MockMvc, profile `test`) | `backend/src/test/java/com/sahaj/platform/it/DeskBookingIT.java`, `DeskFlowIT.java`, `DeskSnapshotIT.java` (+ `DeskSupport.java`) | server rules, SQL, tenant isolation, roles, concurrency, audit privacy |
| Existing ITs changed by design | `AppointmentDoubleBookingIT`, `AppointmentsConfigIT`, `FollowUpListsIT` and the other opd journeys (see "Existing ITs") | the old behaviour that the desk rules replaced |
| Browser (Playwright, API mocked by a stateful fake) | `frontend/e2e/desk-console.spec.ts`, `desk-world.ts` | the page: flow, filters, chips, roles, keyboard, narrow width |

The browser tests mock the API (like every other spec in `frontend/e2e/`). The server rules are therefore proved only by the integration tests; the browser tests prove what the page does with correct answers and with problem answers.
Not run in a browser against the real backend: a live run is not part of this plan (see "Not covered").

Dates: bookings in the ITs are for tomorrow or later, so the result never depends on the time of day. A check-in needs an appointment dated today; the IT books for tomorrow and moves the row to today with committed SQL (the application clock cannot move).
Test data: patient "Ramesh Kulkarni" and doctor "Dr Asha Rao" are fixed test strings, not real people.

## Brief acceptance checks 1 to 10

| # | Check | Tests |
| --- | --- | --- |
| 1 | 12+ doctors, whole flow on one page | E-1 (14 doctors, browser); IT-FLOW-WHOLE (server walk); IT-SNAP-12 (12+ doctors, strip of 7 days) |
| 2 | Booking today adds a card, removes the slot, updates counters, no reload | E-1; IT-SNAP-12 (slot leaves the day-slots and the count drops); IT-SNAP-BOARD |
| 3 | Check-in issues the next token and shows patients ahead | E-1; IT-FLOW-CHECKIN (panel: tokenNo, patientsAhead, wait); IT-FLOW-WHOLE |
| 4 | Two simultaneous bookings: exactly one succeeds | IT-BOOK-RACE (6 rounds, one 201 and one 409), AppointmentDoubleBookingIT (existing, 5 rounds) |
| 5 | Two simultaneous check-ins for one doctor: two different consecutive tokens | IT-FLOW-TOKENS (4 rounds, tokens differ by 1, 1..8 gap-free) |
| 6 | A rescheduled appointment appears once, in its new slot | E-5; IT-BOOK-RESCHEDULE (one active row, old slot free, new slot taken); IT-SNAP-BOARD (unique card keys) |
| 7 | Search filters doctors by name or department and cards by patient name | E-3 |
| 8 | Booking rules in the footer match the saved settings | E-4 (chips); IT-SNAP-OFF (the snapshot echoes the saved values) |
| 9 | Existing appointment screens and other modules still work | E-6 (old board); existing ITs listed below; `validate all` |
| 10 | Lint, type check, existing tests pass; new logic has tests | `node scripts/validate.mjs all` (result in the handoff); unit tests are the developers' |

## Test list

Naming: `IT-*` = backend integration test (class and method in the table at the end), `E-*` = Playwright test in `desk-console.spec.ts`.
Negative, role, tenant and boundary cases are marked N, R, T, B.

### SAH-90 · page and read side

| ID | Criterion (story) | Test | Kind |
| --- | --- | --- | --- |
| T90-1a | Console with title, clinic name, date and time (90-1) | E-1 (title, header text with clinic name) | happy |
| T90-1b | Old board still works; console has its own route (90-1) | E-6 | happy |
| T90-1c | Five counters in order Booked, Waiting, In consult, Done, Free today (90-1) | E-1 reads each counter; IT-SNAP-BOARD, IT-FLOW-WHOLE check the numbers | happy |
| T90-1d | Counter meaning: no-show excluded from Booked; Waiting, In consult, Done mapping (90-1) | IT-SNAP-BOARD, IT-FLOW-WHOLE | happy |
| T90-1e | Search and scope do not change counters (90-1) | E-3 (Free today unchanged) | happy |
| T90-1f | Poll every 20 s / refresh straight after an action (90-1) | E-1 (counters and cards change after each write with no reload); the 20 s timer is a unit test of the page (not repeated in the browser: a real 20 s wait) | happy |
| T90-1g | Failed poll keeps last data and shows "Could not refresh. Showing the last update." (90-1) | E-8 | N |
| T90-1h | Rules footer chips equal saved values; reminder and self-book chips disabled with the WhatsApp note (90-1) | E-4; IT-SNAP-OFF | happy |
| T90-1i | Slot settings / Change rules visible to staff only (90-1) | E-7 (no Slot settings link for a viewer); server 403 of the writes: IT-BOOK-ROLES, IT-BOOK-CUTOFF-RANGE | R |
| T90-1j | HRA off: no leave text, nothing breaks (90-1) | E-1 (no leave text on the page, HRA-off fake); IT: every snapshot IT runs with HRA off (the default test clinic) | happy |
| T90-1k | Narrow viewport stacks rail, centre, panel; board scrolls inside; no sideways page scroll (90-1) | E-10 (600 px); the two default projects (360 and 1440 px) run the whole file | B |
| T90-1l | Keyboard reachable in logical order, visible focus, status as text (90-1) | E-9 | happy |
| T90-1m | No session: 401 (90-1) | IT-SNAP-ROLES (snapshot, day-slots, token-panel without login are 401) | N |
| T90-2a | 12+ doctors as compact rows, "N of M", rail scrolls inside (90-2) | E-1 ("14 of 14"); IT-SNAP-12 | happy |
| T90-2b | Department list: All plus each department once; choosing narrows the rail (90-2) | E-3 (department select) | happy |
| T90-2c | Department = EMR department else specialty else none (90-2) | IT-SNAP-12 (departmentSource is EMR_DEPARTMENT or SPECIALTY, name set) | happy |
| T90-2d | Status derived: Off today (90-2) | IT-SNAP-OFF | happy |
| T90-2e | Status In OPD, Starts later, Full today, On leave | not in an IT: the status depends on the wall clock and on HRA leave; covered by `DeskReadServiceTest` (unit, developer). See "Not covered" | - |
| T90-2f | Next free text and 7-day counts agree with the day-slots read (90-2, 90-3) | IT-SNAP-12 (strip count of tomorrow equals day-slots freeCount) | happy |
| T90-2g | Free slots exclude booked, past, walk-in reserved and (for non-staff) cut-off slots (90-2, 90-3) | IT-BOOK-DAYSLOTS, IT-BOOK-WALKIN, IT-BOOK-CUTOFF | happy |
| T90-2h | Row click selects the doctor (pressed), finder shows that doctor (90-2) | E-1, E-7 | happy |
| T90-2i | Search by name or department; no match leaves the rail whole (90-2) | E-3 | N |
| T90-2j | Another clinic's doctors never visible (90-2) | IT-SNAP-TENANT | T |
| T90-3a | Finder: name, department, room, slot length, fees incl. follow-up fee by rule C1 (90-3) | E-1 (heading, finder); IT-SNAP-12 (followUpFee "free", followUpFeePaise 0, consultFeePaise 30000) | happy |
| T90-3b | Seven day buttons with free count or "No slots" (90-3) | E-1; IT-SNAP-12 | happy |
| T90-3c | Slots grouped Morning and Evening (90-3) | IT-BOOK-DAYSLOTS (MORNING), IT-BOOK-EVENING (EVENING) | happy |
| T90-3d | Doctor on leave: No slots (90-3) | not covered by an IT (needs HRA leave set-up); see "Not covered" | - |
| T90-3e | Empty day text and next free day (90-3) | `nextFreeDate` in IT-BOOK-WORKING-DAY; the text is a unit test of the model | N |
| T90-3f | Slot is a real button with time "10:30 AM", pressed state (90-3) | E-1 | happy |
| T90-3g | Click a slot opens Confirm booking; viewer cannot (90-3) | E-1; E-7 | R |
| T90-3h | Another user books a slot: it leaves the finder on the next read (90-3) | IT-SNAP-12 (after a booking the slot is gone from the read); E-12 (the lost slot) | happy |
| T90-3i | day-slots input: date window today..+120, bad date, missing date (90-3) | IT-BOOK-DAYSLOTS | B, N |
| T90-4a | Four columns with counts; mapping of stages to columns; walk-ins appear from Waiting (90-4) | IT-SNAP-BOARD, IT-FLOW-WHOLE, IT-FLOW-NOTNEEDED (walk-in card kind) | happy |
| T90-4b | Card shows time, patient, doctor, type, token; one primary action or a tag (90-4) | E-1 (action labels per column); IT-FLOW-WHOLE (primaryAction per state) | happy |
| T90-4c | No-show tag and Rebook after grace; manual no-show (90-4, 92-4) | IT-SNAP-BOARD (grace 0), E-5 | happy |
| T90-4d | Doctor-on-leave tag and Move to new slot (90-4) | not covered by an IT (HRA leave); see "Not covered" | - |
| T90-4e | Empty column "Nobody here" (90-4) | E-3 / E-1 initial board (Waiting and Done start empty); text asserted in unit tests | happy |
| T90-4f | Scope toggle; search by patient name; both filters at once (90-4) | E-3 (patient filter); scope is a unit test of the page; the server board filter: IT-SNAP-BOARD | happy |
| T90-4g | Viewer sees cards and tags but no action buttons (90-4) | E-7 | R |
| T90-4h | Another clinic's appointments never appear (90-4) | IT-SNAP-TENANT | T |
| T90-4i | Cancelled appointments not on the board (90-4) | IT-SNAP-BOARD | N |

### SAH-91 · booking, checks, reschedule

| ID | Criterion | Test | Kind |
| --- | --- | --- | --- |
| T91-1a | Confirm booking creates one appointment for patient, doctor, date, time, type, source front desk (91-1) | E-1; IT-BOOK-CHECKS (valid booking 201) | happy |
| T91-1b | Patient search by name or mobile; New patient with minimal fields (91-1) | E-1 (search); the new-patient form is a unit test (`desk-book-panel.component.spec.ts`) | happy |
| T91-1c | Booking for today: card in Booked, slot leaves the finder, counters update (91-1) | E-1 | happy |
| T91-1d | Success panel "Booked" and "Book another" (91-1) | E-1, E-11 | happy |
| T91-1e | Same patient same slot: refused (91-1, 91-2) | IT-BOOK-SLOT-TAKEN (appointment-duplicate 409 with appointmentId) | N |
| T91-1f | Non-staff: 403, no confirm button (91-1) | IT-BOOK-ROLES (NURSE, DOCTOR, BILLING get 403 and nothing is written); E-7 | R |
| T91-1g | Other clinic's patient or doctor: 404 (91-1) | IT-BOOK-TENANT | T |
| T91-1h | Failure and retry (91-1) | E-12 (problem answer, panel stays open, plain message) | N |
| T91-2a | Two parallel bookings of one slot: exactly one saved (91-2, brief 4) | IT-BOOK-RACE, AppointmentDoubleBookingIT | happy |
| T91-2b | Loser sees "That slot was just booked. Pick another time." and the panel stays open (91-2) | E-12; IT-BOOK-SLOT-TAKEN (appointment-slot-taken 409) | N |
| T91-2c | Off-grid time: 400 slot-not-on-grid (91-2) | IT-BOOK-CHECKS | N |
| T91-2d | Doctor not working that day: 400 doctor-not-working (91-2) | IT-BOOK-WORKING-DAY | N |
| T91-2e | Outside the session: 400 (91-2) | IT-BOOK-CHECKS (11:00 with a 09:00 to 10:00 session) | B |
| T91-2f | Walk-in reserved slot: 400 slot-walk-in-only, and not offered (91-2) | IT-BOOK-WALKIN | N |
| T91-2g | Past time: 400 slot-in-past (91-2) | IT-BOOK-CHECKS | N |
| T91-2h | Cut-off: doctor's own beats the clinic's; non-staff do not get the slots; staff are exempt and see insideCutOff (91-2) | IT-BOOK-CUTOFF | happy |
| T91-2i | Cut-off range 0 to 48, whole hours, clear returns to clinic value (91-2) | IT-BOOK-CUTOFF-RANGE (49, -1, 1.5, "x" are 400; 0 and 48 accepted) | B |
| T91-2j | Doctor on approved leave: refused (91-2) | not covered by an IT (HRA leave); see "Not covered" | - |
| T91-2k | Overbook on lets staff book a taken slot; off refuses (91-2) | IT-BOOK-SLOT-TAKEN | happy |
| T91-2l | Request body with a clinic id is ignored (91-2) | not added: the tenant comes from the token in every IT; a body field is not part of the request record (`@JsonIgnoreProperties`) | - |
| T91-2m | Invalid doctor id, date or time format: 400; other clinic's doctor: 404 (91-2) | IT-BOOK-CHECKS (bad time 400), IT-BOOK-TENANT (404) | N, T |
| T91-2n | Old board uses the same server checks (91-2) | AppointmentsConfigIT and AppointmentDoubleBookingIT (existing) run the same endpoint | happy |
| T91-3a | Reschedule: same id, new doctor/date/time, status BOOKED, version bumped, old slot free (91-3) | IT-BOOK-RESCHEDULE | happy |
| T91-3b | Rebook a no-show in place (91-3) | IT-BOOK-NOSHOW-REBOOK; E-5 | happy |
| T91-3c | Appears once, in its new slot (91-3, brief 6) | E-5; IT-BOOK-RESCHEDULE | happy |
| T91-3d | Same checks as booking: grid, past, taken (91-3) | IT-BOOK-RESCHEDULE-CHECKS | N |
| T91-3e | Only BOOKED and NOSHOW can move; others 409 appointment-not-reschedulable (91-3) | IT-BOOK-RESCHEDULE-CHECKS (cancelled) | N |
| T91-3f | Two users reschedule the same appointment: one wins (91-3) | IT-BOOK-RESCHEDULE-SAME | happy |
| T91-3g | Two reschedules into one slot: one wins; old unchanged for the loser (91-3) | IT-BOOK-RESCHEDULE-RACE | happy |
| T91-3h | Stale version: 409 appointment-changed; missing version 400 (91-3) | IT-BOOK-RESCHEDULE, IT-BOOK-RESCHEDULE-CHECKS | N |
| T91-3i | Audit records old and new placement, no patient name (91-3) | IT-FLOW-WHOLE and IT-FLOW-CHECKIN scan every audit row for the names; the placement fields are a unit test | N |
| T91-3j | Non-staff 403; other clinic 404 (91-3) | IT-BOOK-RESCHEDULE-CHECKS (NURSE 403, unknown id 404), IT-BOOK-TENANT | R, T |

### SAH-92 · check-in, token, stages, no-show

| ID | Criterion | Test | Kind |
| --- | --- | --- | --- |
| T92-1a | Check-in: one visit, appointment ARRIVED and linked, next token, card to Waiting (92-1) | IT-FLOW-CHECKIN; E-1 | happy |
| T92-1b | Token day is the IST calendar day; counter per clinic and day, starts at 1 (92-1) | IT-FLOW-CHECKIN (token_date and created_at in IST); IT-FLOW-TOKEN-DAY (date overload 1, 2, then 1 for the next day; another clinic starts at 1; no tenant bound is refused). The IST midnight itself cannot be reached with the real clock: the IST date is compared with the visit date, which differs from the UTC date between 00:00 and 05:30 IST | happy |
| T92-1c | Two check-ins of one appointment: one visit, one token, "already checked in" 409 with token (92-1) | IT-FLOW-CHECKIN-RACE; IT-FLOW-CHECKIN-TWICE | happy |
| T92-1d | Two check-ins of different appointments: consecutive tokens (92-1, brief 5) | IT-FLOW-TOKENS | happy |
| T92-1e | Cancel racing check-in: one outcome, no visit for a cancelled appointment (92-1) | IT-FLOW-CANCEL-RACE | happy |
| T92-1f | Not BOOKED: 409; other day: 400 check-in-not-today (92-1) | IT-FLOW-REFUSALS | N |
| T92-1g | Non-staff 403; other clinic 404 (92-1) | IT-FLOW-REFUSALS | R, T |
| T92-2a | Token panel: token, patient, doctor, room, wait line; patients ahead (92-2) | IT-FLOW-CHECKIN (panel fields); E-1 ("Next in line") | happy |
| T92-2b | Panel readable by every clinic role, no-store (92-2) | IT-FLOW-CHECKIN (OWNER, NURSE, DOCTOR, BILLING 200 with no-store) | R |
| T92-2c | Another clinic's token panel: 404 (92-2) | IT-SNAP-TENANT | T |
| T92-2d | Print slip, WhatsApp disabled (92-2) | not in the browser spec (print dialog); unit test `desk-token-panel.component.spec.ts` | - |
| T92-3a | One step with expectedStage; consult times stored (92-3) | IT-FLOW-STAGES; IT-FLOW-CONSULT-TIMES; E-1 | happy |
| T92-3b | Times set once, even after the owner moves back and forward again (92-3) | IT-FLOW-CONSULT-TIMES (also the DB check refuses an end before the start) | B |
| T92-3c | Not the next stage: 409 visit-stage-changed (92-3) | IT-FLOW-STAGES | N |
| T92-3d | Two presses of the same action: one step, one 409 (92-3) | IT-FLOW-STAGE-RACE (5 rounds, one audited step per visit) | happy |
| T92-3e | Walk-in uses the same actions (92-3) | IT-FLOW-STAGES and IT-FLOW-STAGE-RACE use walk-in visits | happy |
| T92-3f | Role that does not own the step: 403; other clinic: 404; bad stage word: 400 (92-3) | IT-FLOW-STAGES | R, T, N |
| T92-4a | No-show tag after the grace, without a stored change (92-4) | IT-SNAP-BOARD (grace 0 and an early time) | happy |
| T92-4b | Manual no-show stored, Rebook offered, Booked drops (92-4) | IT-SNAP-BOARD; E-5 | happy |
| T92-4c | Arrived or cancelled: manual no-show or cancel is 409 appointment-not-booked (92-4) | IT-FLOW-REFUSALS | N |
| T92-4d | Status other than Cancelled or No-show: 400 (92-4) | IT-FLOW-REFUSALS | N |

### SAH-93 · follow-up

| ID | Criterion | Test | Kind |
| --- | --- | --- | --- |
| T93-1a | Book the follow-up of a completed visit (interval, date, slot): one follow-up appointment, linked, due date from the interval (93-1) | IT-FLOW-FOLLOWUP-BOOK; E-1 | happy |
| T93-1b | Walk-in visit: same panel and the visit's own follow-up record, no duplicate (93-1) | IT-FLOW-FOLLOWUP-BOOK (a walk-in visit) | happy |
| T93-1c | "No follow-up needed" stored once with time and user, idempotent, row kept (93-1) | IT-FLOW-NOTNEEDED; E-2 | happy |
| T93-1d | Not-needed follow-up is not in the lists, counts, today summary or the desk to-book list (93-1) | IT-FLOW-NOTNEEDED-EXCLUDED | happy |
| T93-1e | After booked or not needed, a second write is 409 follow-up-already-booked / follow-up-closed (93-1) | IT-FLOW-FOLLOWUP-BOOK, IT-FLOW-NOTNEEDED | N |
| T93-1f | Booking racing "no follow-up needed": one outcome (93-1) | IT-FLOW-FOLLOWUP-RACE | happy |
| T93-1g | Two bookings of one follow-up: one appointment (93-1) | IT-FLOW-FOLLOWUP-DOUBLE | happy |
| T93-1h | Visit not completed: 409 visit-not-done; bad interval, missing date, bad type: 400; bad slot: 400 slot-not-on-grid (93-1) | IT-FLOW-FOLLOWUP-VALIDATION | N |
| T93-1i | Non-staff 403; other clinic 404 (93-1) | IT-FLOW-FOLLOWUP-VALIDATION | R, T |
| T93-1j | Slot taken meanwhile: plain message, panel stays (93-1) | E-12 (same page behaviour for a booking); IT-BOOK-SLOT-TAKEN (the server answer used by the follow-up booking, which calls the same booking code) | N |
| T93-2a | Idle list shows open follow-ups with days late; Find slot books it linked, due date unchanged (93-2) | IT-FLOW-FINDSLOT; E-11 | happy |
| T93-2b | Booked by someone else meanwhile: 409 (93-2) | IT-FLOW-FINDSLOT | N |
| T93-2c | Type can be New and the booking is still linked (93-2) | IT-FLOW-FINDSLOT (visitType NEW) | happy |
| T93-2d | Non-staff 403; other clinic 404 (93-2) | IT-FLOW-FINDSLOT, IT-FLOW-FOLLOWUP-VALIDATION | R, T |
| T93-2e | Audit rows carry no patient name (93) | IT-FLOW-FOLLOWUP-BOOK (scan) | N |

### Cross-cutting

| ID | Test |
| --- | --- |
| X-1 | Statement count bounded: the snapshot sends the same number of statements for 3 doctors as for 50+ (at most 2 more), at most 30 in absolute terms, and p95 of 20 calls with 50 doctors under 2 s (IT-SNAP-COUNT) |
| X-2 | Partial indexes are usable for the to-book query and the active-appointment date range (EXPLAIN with sequential scan off) (IT-SNAP-INDEX) |
| X-3 | `Cache-Control: no-store` on the snapshot and the token panel (IT-SNAP-ROLES, IT-FLOW-CHECKIN) |
| X-4 | Tenant isolation at the SQL level: another clinic's connection sees none of the appointment, visit or follow-up rows; with no tenant bound the app role sees no appointment (IT-SNAP-TENANT) |
| X-5 | Invalid input on the snapshot: bad date window, bad UUID, unknown doctor (404) (IT-SNAP-INPUT) |

## Index of the backend tests

| ID | Class#method |
| --- | --- |
| IT-BOOK-CHECKS | DeskBookingIT#bookingRefusalsCarryTheirOwnProblemTypeAndStatus |
| IT-BOOK-WORKING-DAY | DeskBookingIT#aDoctorWhoDoesNotWorkThatWeekdayIsRefusedWithDoctorNotWorking |
| IT-BOOK-WALKIN | DeskBookingIT#aWalkInReservedSlotIsNotOfferedAndNotBookable |
| IT-BOOK-ROLES | DeskBookingIT#aNurseCannotBookAndGetsAProblem403WhileTheOwnerAndFrontDeskCan |
| IT-BOOK-SLOT-TAKEN | DeskBookingIT#theSameSlotTwiceGivesSlotTaken409ThePatientTwiceGivesDuplicate409AndOverbookAllowsStaff |
| IT-BOOK-CUTOFF | DeskBookingIT#theDoctorsOwnCutOffBeatsTheClinicsAndStaffAreNeverRefusedButSeeTheNote |
| IT-BOOK-CUTOFF-RANGE | DeskBookingIT#closeBeforeHoursOutOfRangeIsRefusedWithAProblem400 |
| IT-BOOK-DAYSLOTS | DeskBookingIT#daySlotsHidesTakenSlotsAndRefusesDatesOutsideTheWindow |
| IT-BOOK-EVENING | DeskBookingIT#anEveningSessionIsGroupedUnderEvening |
| IT-BOOK-RESCHEDULE | DeskBookingIT#rescheduleMovesTheSameAppointmentOnceAndBumpsTheVersion |
| IT-BOOK-RESCHEDULE-CHECKS | DeskBookingIT#rescheduleChecksTheNewSlotRolesAndStatus |
| IT-BOOK-NOSHOW-REBOOK | DeskBookingIT#aNoShowAppointmentCanBeRebookedIntoANewSlotAndBecomesBookedAgain |
| IT-BOOK-RESCHEDULE-RACE | DeskBookingIT#twoReschedulesOfDifferentAppointmentsIntoOneSlotGiveOneSuccessAndOneConflict |
| IT-BOOK-RESCHEDULE-SAME | DeskBookingIT#twoReschedulesOfTheSameAppointmentWithTheSameVersionGiveOneSuccessAndOneAppointmentChanged |
| IT-BOOK-RACE | DeskBookingIT#twoParallelBookingsOfOneSlotGiveOneCreatedAndOneConflictInEveryRound |
| IT-BOOK-TENANT | DeskBookingIT#anotherClinicCannotBookRescheduleOrCheckInThisClinicsAppointment |
| IT-SNAP-12 | DeskSnapshotIT#aClinicWithTwelveOrMoreDoctorsGetsEveryDoctorWithSevenDaysAndFreeCountsThatMatchTheDaySlots |
| IT-SNAP-OFF | DeskSnapshotIT#aDoctorWhoDoesNotWorkTodayIsOffTodayAndTheSavedRulesAreEchoedForTheFooter |
| IT-SNAP-BOARD | DeskSnapshotIT#theBoardAndCountersFollowTodaysAppointmentsWalkInsAndTheNoShowRule |
| IT-SNAP-INPUT | DeskSnapshotIT#theFinderSelectionReturnsThatDoctorsDayAndRefusesBadInput |
| IT-SNAP-ROLES | DeskSnapshotIT#everyClinicRoleReadsTheSnapshotWithNoStoreAndAnAnonymousCallerGets401 |
| IT-SNAP-TENANT | DeskSnapshotIT#clinicANeverSeesClinicBsDoctorsPatientsAppointmentsOrFollowUpsAndCannotAskForThem |
| IT-SNAP-COUNT | DeskSnapshotIT#theSnapshotUsesTheSameFewStatementsFor50DoctorsAsFor3AndStaysFast |
| IT-SNAP-INDEX | DeskSnapshotIT#theToBookQueryAndTheDateRangeQueryCanUseTheirPartialIndexes |
| IT-FLOW-CHECKIN | DeskFlowIT#checkInIssuesTheTokenOnTheIstDayAnswersThePanelAndKeepsNamesOutOfTheAudit |
| IT-FLOW-CHECKIN-TWICE | DeskFlowIT#aSecondCheckInIs409AlreadyCheckedInWithTheTokenAndNoSecondVisit |
| IT-FLOW-REFUSALS | DeskFlowIT#checkInRefusalsNotTodayNotBookedRoleAndOtherClinic |
| IT-FLOW-CHECKIN-RACE | DeskFlowIT#twoCheckInsOfOneAppointmentGiveOneVisitOneTokenAndOneAlreadyCheckedIn409 |
| IT-FLOW-TOKENS | DeskFlowIT#twoCheckInsOfDifferentAppointmentsGetTwoDifferentConsecutiveTokens |
| IT-FLOW-CANCEL-RACE | DeskFlowIT#aCancelRacingACheckInEndsInOneOutcomeWithNoVisitForACancelledAppointment |
| IT-FLOW-TOKEN-DAY | DeskFlowIT#theTokenCounterIsPerClinicPerDayAndTheDateOverloadStartsAtOneForEachDay |
| IT-FLOW-STAGES | DeskFlowIT#theStageMovesOneStepWithExpectedStageAndRefusesAStaleOrSkippingMove |
| IT-FLOW-STAGE-RACE | DeskFlowIT#twoPressesOfTheSameStageMoveMakeOneStepAndOneVisitStageChanged409 |
| IT-FLOW-CONSULT-TIMES | DeskFlowIT#theConsultationTimesAreStoredOnceEvenWhenTheOwnerMovesTheCardBackAndForward |
| IT-FLOW-FOLLOWUP-BOOK | DeskFlowIT#aWalkInVisitCanBookItsFollowUpAfterCompleteVisitAndASecondBookingIs409 |
| IT-FLOW-FOLLOWUP-VALIDATION | DeskFlowIT#followUpWritesValidateRoleVisitStateAndInput |
| IT-FLOW-NOTNEEDED | DeskFlowIT#noFollowUpNeededIsStoredOnceIsIdempotentAndClosesTheRecordForBooking |
| IT-FLOW-NOTNEEDED-EXCLUDED | DeskFlowIT#aNotNeededFollowUpLeavesTheFollowUpListsTheCountsTheTodaySummaryAndTheDeskList |
| IT-FLOW-FINDSLOT | DeskFlowIT#findSlotBooksAFollowUpFromTheListWithoutChangingItsDueDateAndTheListDropsIt |
| IT-FLOW-FOLLOWUP-RACE | DeskFlowIT#aFollowUpBookingRacingNoFollowUpNeededEndsInOneOutcome |
| IT-FLOW-FOLLOWUP-DOUBLE | DeskFlowIT#twoBookingsOfTheSameFollowUpGiveOneAppointment |
| IT-FLOW-WHOLE | DeskFlowIT#theWholeFlowBookCheckInStartCompleteFollowUpShowsOnTheBoardAtEachStep |

## Browser tests (`frontend/e2e/desk-console.spec.ts`, run at 360 px and 1440 px)

| ID | Test |
| --- | --- |
| E-1 | whole flow with 14 doctors: find slot, book, check in, token, start, complete, follow-up |
| E-2 | "No follow-up needed" is stored and the Done card shows it |
| E-3 | search filters doctors by name or department and cards by patient name; counters unchanged; department select |
| E-4 | rule chips equal the saved settings; WhatsApp rules disabled with their note |
| E-5 | a no-show is rebooked in place: one card, in its new slot |
| E-6 | the old board at /app/appointments still works and links to the console |
| E-7 | a viewer role sees no write buttons and cannot start a booking |
| E-8 | a failed refresh keeps the last data and shows the plain message |
| E-9 | keyboard reachability and visible focus |
| E-10 | narrow width: rail, centre and panel stack; no sideways scroll |
| E-11 | Find slot on an idle follow-up books it and the list drops it |
| E-12 | a slot lost to someone else shows the plain message and the panel stays open |

## Existing ITs changed by design (DEC-097)

The whole backend suite was run after the change. Seven existing IT classes needed an update because the server now refuses what the old tests did. None was deleted or skipped; no product code was changed.

| IT | Failed because | Change |
| --- | --- | --- |
| AppointmentDoubleBookingIT | 5th round booked 13:00, outside the default 09:00 to 13:00 grid: now 400 slot-not-on-grid | 4 rounds on hours 9 to 12 |
| AppointmentsConfigIT | cancel or no-show of a non-booked appointment is now 409 appointment-not-booked (was 400); a token needs an appointment dated today | two expectations 400 to 409; the rebooked appointment is moved to today before its token |
| FollowUpListsIT | check-in of an appointment booked for +3 days is 400 check-in-not-today | the appointment is moved to today with committed SQL before the token |
| LeaveRequestsIT | same: token of an appointment on a future day | moved to today before the token (it stays ARRIVED, so the count it checks is unchanged) |
| EmrDraftSaveHangIT | booked today at 06:00, which is off the grid and in the past: 400 | booked tomorrow 09:00, moved to today before the token |
| WorkflowConfigIT (2 tests) | token of a future appointment is 400 check-in-not-today | moved to today before the token |

The helper is `AppointmentToday.java` (same folder). Everything else that books appointments (QueueIT, TodayDashboardIT, WalkInTokenJourneyIT, BillingCounterEarlierDaysIT, PatientsIT, RevenueIT, RealSessionJourneyIT, LifecycleEdgeCasesIT, EmrDentalSendToBillingIT and the rest of the 322 classes) passes unchanged.

## Not covered, and why

1. Doctor on leave (HRA on): status On leave, "No slots", the leave tag and the 409 doctor-unavailable. No IT sets up an approved leave for the desk; the unit tests of `DeskReadServiceTest` and `AppointmentBookingChecksTest` cover the rules with a fake leave port. A later IT should approve a leave through the HRA endpoints and re-check these four points.
2. Today statuses In OPD, Starts later, Full today: depend on the wall clock; unit tests only.
3. The 20-second poll and tab visibility: timers are unit-tested in the page spec; the browser test uses the visibility event only.
4. Live run of the page against the real backend: the browser tests use a mocked API.
5. Check-in exactly at IST midnight: the real clock cannot be moved; see T92-1b for what is checked instead.
