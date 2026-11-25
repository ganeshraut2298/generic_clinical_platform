# ADR-032 · Front desk console: one desk read model, polling, booking checks, reschedule in place, check-in and stage races

Stories: SAH-90 (read side), SAH-91 (booking and reschedule), SAH-92 (check-in, token, stages, no-show), SAH-93 (follow-up panel)
Mockup screen: Appointments (sidebar → Appointments), new route `/app/appointments/desk`. Reference design: the front desk console mockup approved in chat on 24 Nov 2026 (DEC-097), not yet saved in `docs/design/` (human prerequisite).
Module: **opd** only. Two additive port methods: `opd.api.DoctorBlockedDatesPort` (implemented by hra) and `platform.api.TeamMemberPort`. No new module dependency.
Status: PROPOSED (24 Nov 2026), awaiting the `contract · APPROVED` rows of SAH-90, SAH-91, SAH-92 and SAH-93 in `docs/approvals.md` (one contract revision, built story by story)
Source: DEC-097 (A1, B1, C1, D2 human; E1 to E12 and addendum 1 to 7 tech lead under DEC-073), `docs/requirements/SAH-90.md` R1 to R20, `docs/handoffs/SAH-90-phase1-analysis.md`
Builds on: ADR-002 (tenancy, RLS, audit), ADR-004 (discharge follow-ups), ADR-020 D7 (department resolution), ADR-025 D6 (EMR follow-up booking), SAH-9, SAH-11, SAH-14, SAH-21 (leave port)
Contract: `docs/api/opd.yaml` 0.8.0 → **0.9.0** (additive; three narrow behaviour changes listed in D9). `patient.yaml`: no change (`GET /patients?q=`, `POST /patients` are used as they are, D2 of DEC-097).

---

## Context

1. The console needs, every 20 seconds, for up to 50 doctors: today's status, 7-day free counts, next free slot, fees, department and room; a board of today's appointments and walk-in visits with their visit stage; five counters; follow-ups to book; and the open slots of the selected doctor. Today this data is spread over `GET /appointments` (one day, no stage, no walk-ins), `GET /visits/today`, `GET /follow-ups`, `GET /doctor-slots`, and free slots are computed one SQL query per slot (`AppointmentService.freeSlots`).
2. Booking does not check the grid, the past or (for staff) anything but the taken slot; check-in reads then writes without a lock (two visits possible); the token day is the database `current_date`, not the IST day; the stage move writes without a compare-and-set (two presses can both "succeed").
3. Reschedule does not exist; E4 requires an in-place update.
4. There is no real-time channel; E10 decides polling.

---

## Decisions

### D1 · One desk read model, computed per request with set-based queries (`GET /desk/snapshot`)

- One read, `GET /api/v1/opd/desk/snapshot`, returns in one answer: booking rules, the clinic follow-up fee rule, the five counters, the doctor rail (every active doctor with today's status, 7 day counts, next free day, fees, department, room, effective cut-off), the board for today (appointments and walk-in visits with mapped column, tags and primary action), the idle "follow-ups to book" list, and, when a doctor is selected, that doctor's open slots for the selected day.
- It is a **query-side composition inside opd**, computed on each request; nothing is stored or cached (no projection table, no materialised view). All rows read are opd's own tables; patient names come through `patient.api` (`PatientLookupPort.findAll`, one call), doctor names and fees through `platform.api` (`TeamMemberPort.findActiveDoctors`, one call), specialties through a **new bulk method** `TeamMemberPort.doctorSpecialties(tenantId, ids)`, leave through a **new bulk default method** `DoctorBlockedDatesPort.blockedDatesForDoctors(tenantId, ids, from, to)` (default loops over the existing method; hra overrides it with one query).
- **Fixed number of SQL statements per snapshot, independent of doctors, slots and cards** (target 12 or fewer): rules and fee settings; doctors; slot templates and sessions of all doctors; doctor departments (published `emr_department` joined with `emr_department_doctor`); leave for today..today+60 (one port call); active appointments (BOOKED, ARRIVED) of all doctors for today..today+6; today's non-cancelled appointments; today's visits; follow-ups of today's visits with their appointment date and time; follow-ups to book (rows and total); patient names. Free slots are computed **in memory** from the template, sessions, taken set, leave set and clock (`SlotComputation`), never one query per slot or per doctor.
- Performance target: p95 under 300 ms server time for 50 doctors × 7 days and 1,500 cards on the reference laptop database; answer gzip-compressed (Spring `server.compression` for `application/json`, java-developer).
- Small focused reads beside it, same computation: `GET /desk/doctors/{id}/day-slots?date=` (finder day click, follow-up panel dates up to 120 days ahead) and `GET /desk/visits/{visitId}/token-panel`.

Options: A · reuse the existing reads and join on the client (rejected: 4+ calls per poll, no stage on appointments, per-slot SQL, inconsistent counters). B · stored read model (projection table updated by events) (rejected for phase 1: two writers per change, a backfill, and time-derived values such as no-show and past slots still need the clock at read time). **C · per-request composition with set-based queries (chosen).**

### D2 · Polling contract, no ETag in phase 1

- The client polls `GET /desk/snapshot` every **20 s while the tab is visible** (Page Visibility API), refreshes at once when the tab becomes visible, and right after each own write. It never has two snapshot requests in flight (a tick is skipped while one is running). On failure it keeps the last answer and shows "Could not refresh. Showing the last update."
- The answer carries `generatedAt` (server instant) and is sent with `Cache-Control: no-store` (patient names). **No ETag / 304**: the answer holds clock-derived values (no-show and due-now tags, past slots leaving the finder, In OPD / Starts later / Full today) that change without any data write, so a data-version ETag would serve stale values, and a time-bucketed ETag saves bandwidth but not the computation, which is the cost. Gzip covers the bandwidth.
- Load envelope: 10,000 clinics × about 1.5 visible desk tabs ÷ 20 s ≈ 750 snapshot requests per second at the national peak, upper bound. The interval is a client constant; if measured load is too high the first lever is the interval, the second a short server cache per tenant (a few seconds), the third a push channel (SSE), which needs its own ADR.

Options: A · SSE or WebSocket push (rejected: no channel exists, E10, phase 1 scope). B · ETag on a data version (rejected, wrong answers for clock-derived values). **C · plain polling of one composed read (chosen).**

### D3 · One free-slot rule and one cut-off rule, used by every read and every write

A slot of doctor *d* on date *x* at time *t* is **free** when all hold:
1. *x* is a working day of *d* and *t* is a slot start on *d*'s grid (the doctor's slot length from each session start, slots that end after the session end are not slots: `SlotComputation`, unchanged);
2. the slot is not walk-in reserved (every Nth slot, unchanged);
3. the start instant (IST) is after now;
4. *d* is not on approved leave on *x* (`DoctorBlockedDatesPort`; without HRA nothing is blocked and no leave text appears);
5. no BOOKED or ARRIVED appointment of *d* starts at exactly *x t* (NOSHOW and CANCELLED free the slot, unchanged);
6. **cut-off**: the effective cut-off is the doctor's own `closeBeforeHours` when set, else the clinic's `closeBeforeHours`. A slot whose start is less than that many hours from now is **inside the cut-off**. For OWNER and FRONT_DESK it stays free and is returned with `insideCutOff: true` (the console shows "Inside the booking cut-off"); for every other caller it is not free (DEC-097 addendum 1, SAH-11 rule kept).

Free counts in the rail, the finder strip, the counters (Free today) and "next free" use this rule **for the calling user's role**, so a nurse may see a lower Free today than the front desk. The same code (`BookingPolicy` in `com.sahaj.opd.application`) validates `POST /appointments`, reschedule and the follow-up bookings (D4). Not changed: the EMR sign's own bookings (ADR-025 D6, D7, source EMR) keep their current rule (they already use free slots; cut-off not applied) — see open question 3.

Morning and Evening grouping of a day's slots (finder): a slot belongs to the group of its session; a session that starts before 12:00 IST is **Morning**, otherwise **Evening**; two sessions with the same label merge into one group in time order.

### D4 · Booking checks and their order (SAH-91-2)

`POST /appointments`, `POST /appointments/{id}/reschedule`, `POST /visits/{visitId}/follow-up/appointment` and `POST /follow-ups/{followUpId}/appointment` run, in one transaction, in this order (first failure answers):

| # | Check | Answer |
|---|---|---|
| 1 | role OWNER or FRONT_DESK | 403 forbidden |
| 2 | formats (uuid, date, `HH:mm`, enums) | 400 validation |
| 3 | patient of this clinic; doctor an active doctor of this clinic | 404 not-found |
| 4 | doctor on approved leave that day | 409 doctor-unavailable (existing) |
| 5 | working day and time inside a session | 400 doctor-not-working / 400 slot-not-on-grid |
| 6 | not walk-in reserved | 400 slot-walk-in-only |
| 7 | start after now (IST) | 400 slot-in-past "That time has passed." |
| 8 | outside the effective cut-off, unless OWNER or FRONT_DESK | 400 booking-closed |
| 9 | `pg_advisory_xact_lock` on tenant, doctor, date, time (existing key) | — |
| 10 | same patient already BOOKED or ARRIVED with this doctor at this date and time | 409 appointment-duplicate (with `appointmentId`) |
| 11 | another BOOKED or ARRIVED appointment in the slot: overbook off → refuse; overbook on → OWNER or FRONT_DESK only (existing) | 409 appointment-slot-taken "That slot was just booked. Pick another time." |

Check 10 before 11 makes a retried confirm after a lost answer come back as `appointment-duplicate` with the saved id, which the client treats as success (SAH-91-1 "a retry does not create a second appointment"); no idempotency store is needed. `max_online_per_day` is not enforced (out of scope). The old board at `/app/appointments` gets the same checks because they are in the server.

### D5 · Reschedule in place (SAH-91-3, E4)

- `POST /appointments/{id}/reschedule` `{ version, doctorMembershipId, apptDate, apptTime }` updates the **same row**: doctor, date, time, status → BOOKED, `version + 1`. No row is created, none is cancelled, so the follow-up link (`follow_up.appointment_id`) and history stay on one id; the old slot is free as soon as the transaction commits.
- Allowed from BOOKED or NOSHOW only (else 409 appointment-not-reschedulable). A BOOKED appointment past its grace (display no-show) is rebooked the same way without a manual mark first.
- **Optimistic version**: the board gives each appointment card its `appointmentVersion`; a stale version answers 409 appointment-changed, so two simultaneous reschedules end in one success (SAH-91-3). Row lock `select … for update` on the appointment first, then checks 4 to 11 of D4 (the own row is excluded from 10 and 11).
- The visit type is kept; the fee shown follows it (C1).
- Same doctor, date and time as now: 200, nothing written.
- Audit `APPOINTMENT_RESCHEDULED`, before and after: `doctorMembershipId`, `apptDate`, `apptTime`, `status` (ids, dates, times and status words only; no patient name).

Options: A · cancel and create (rejected: E4, a duplicate card, the follow-up link lost). **B · update in place with a version (chosen).**

### D6 · Check-in and cancel race, token day in IST (SAH-92-1)

- `POST /appointments/{id}/token` and `PATCH /appointments/{id}` take `select … for update` on the appointment row **before** reading its status. The loser of check-in vs check-in gets 409 appointment-already-checked-in ("This patient is already checked in.", with `visitId` and `tokenNo` so the client opens the token panel); the loser of check-in vs cancel or no-show gets 409 appointment-not-bookable or 409 appointment-not-booked. No cancelled appointment holds a token; no visit exists for a cancelled appointment.
- **Lock order** (no cycle): appointment row → slot advisory lock (reschedule only) → token counter row. Booking takes only the slot lock.
- Check-in only on the appointment day (IST): another day answers 400 check-in-not-today "Check-in is only possible on the day of the visit."
- **Token day pinned to IST**: the application computes today in Asia/Kolkata, allocates with the new `opd.next_token_no(p_token_date date)` and inserts `token_date` explicitly with that same date. Numbering stays clinic-wide per day and gap-free (A1): allocation and insert are one transaction, a rollback also rolls back the counter, and `uq_visit_tenant_token_date_token` is the backstop. The old zero-argument function stays until a later contract migration removes it.
- The answer of check-in gains `tokenPanel` (patients ahead, estimated wait, room) so the panel opens without a second call; `GET /desk/visits/{visitId}/token-panel` recomputes it for reopening from a card (values then reflect the current queue).
- Patients ahead = visits of the same doctor today in WAITING or VITALS whose check-in (`created_at`) is earlier than this visit's (ties by token number); estimated wait = patients ahead × the doctor's slot length; 0 ahead shows "Next in line". It is an estimate and labelled "about".
- Visit type and fee at check-in: unchanged (SAH-9 rule in `VisitService.issue`).
- Required tests: two concurrent check-ins of one appointment (one visit), two concurrent check-ins of different appointments (two consecutive tokens), check-in vs cancel (one outcome), token day across IST midnight.

### D7 · Start and complete from the desk through the existing stage move, made race-safe (SAH-92-3, B1)

- No new desk endpoint: `PATCH /visits/{id}/stage` is used as it is, with an **optional** `expectedStage`. The board gives each visit card `stage` and `nextStage` (the next enabled stage of the visit's own workflow version); "Start consultation" and "Complete visit" send `{ stage: nextStage, expectedStage: stage }`.
- The write becomes a **compare-and-set** for every caller (`update … where id = ? and stage = ?`, the existing `moveStageIfCurrent`): if the stage changed since it was read, or differs from `expectedStage`, the answer is 409 visit-stage-changed and nothing is written. With `expectedStage` sent, a target that is not the next enabled stage is also 409 visit-stage-changed (the client's board was stale). Without `expectedStage`, the old 400 answers stay for the Queue.
- The stage-owner rule is unchanged (OWNER and FRONT_DESK may move any card forward). With Vitals on, "Start consultation" on a WAITING card moves it to VITALS (one step); the card stays in Waiting with the "Vitals" tag and offers "Start consultation" again (addendum 2).
- "Complete visit" leaves WITH_DOCTOR to COUNSEL, BILLING or DONE as the workflow has it, with the existing fee timing and follow-up step; it never signs, changes or locks the EMR. A later doctor sign sees the visit past With doctor and answers ALREADY_MOVED, as today.
- Start and end are recorded in two new nullable columns `opd.visit.consult_started_at` (set once when the visit enters WITH_DOCTOR by any path: desk, Queue, EMR open, sign) and `consult_ended_at` (set once when it leaves WITH_DOCTOR forward), besides the existing `VISIT_STAGE_MOVED` audit.

Options: A · thin desk endpoints `/desk/visits/{id}/start|complete` (rejected: a second path to the same state machine; the CAS fix is needed for the Queue anyway). **B · existing move with CAS and optional expectedStage (chosen).**

### D8 · Follow-up panel writes (SAH-93, E9)

- `opd.follow_up` gains `not_needed_at`, `not_needed_by` (nullable, set together).
- `POST /visits/{visitId}/follow-up/appointment` `{ intervalKey: D7|D15|M1, apptDate, apptTime, visitType }`: the visit must be past WITH_DOCTOR (else 409 visit-not-done). In one transaction: find the visit's follow-up (`source_visit_id`, unique) with `for update`, or create it (reason "Follow-up", as the workflow step does; audit FOLLOW_UP_CREATED); refuse when it already has an appointment (409 follow-up-already-booked), is returned or marked not needed (409 follow-up-closed); set `due_date` = today (IST) + interval and `interval_key` (FOLLOW_UP_DUE_CHANGED when the date changes); book with the doctor of the visit through D4 (source FRONT_DESK); set `appointment_id`; audit FOLLOW_UP_BOOKED. Walk-in visits work the same (addendum 4).
- `POST /visits/{visitId}/follow-up/not-needed`: same lookup or create; sets `not_needed_at/by`; audit FOLLOW_UP_NOT_NEEDED. A second call answers 200 with the stored state (idempotent); a booked one answers 409 follow-up-already-booked.
- `POST /follow-ups/{followUpId}/appointment` `{ doctorMembershipId, apptDate, apptTime, visitType }` (idle list "Find slot"; the doctor may be another doctor picked in the rail): `for update` on the follow-up, same refusals, D4 booking, link, audit FOLLOW_UP_BOOKED. The due date is never changed here (SAH-93-2). Visit type NEW still links and closes the to-book entry (addendum 7).
- **To book** = follow-ups with no return visit, no appointment, not marked not needed, due date ≤ today (IST); oldest due first; at most 200 rows plus the total.
- A follow-up the doctor's sign already booked (ADR-025 D6) shows as BOOKED on the Done card and is not booked twice.

### D9 · Behaviour changes of existing operations (narrow, listed in the contract as "changed")

1. `POST /appointments`: new 400 checks (D4 rows 5 to 8) for every caller, also the old board; new 409 appointment-duplicate; the detail of appointment-slot-taken becomes "That slot was just booked. Pick another time." (title unchanged). The non-staff cut-off answer changes from 400 validation to 400 booking-closed (no such caller exists in phase 1).
2. `PATCH /appointments/{id}` on an appointment that is not BOOKED: 409 appointment-not-booked instead of 400 validation (SAH-92-4).
3. `POST /appointments/{id}/token`: 400 check-in-not-today; 409 appointment-already-checked-in for an ARRIVED appointment (was appointment-not-bookable); the answer adds `tokenPanel`.
4. `PATCH /visits/{id}/stage`: compare-and-set; a lost race answers 409 visit-stage-changed instead of writing twice.
5. `GET /follow-ups` (SAH-14) and `GET /today-summary` follow-up counts: a follow-up marked not needed is left out of DUE, MISSED and UPCOMING lists and counts (it is no longer owed); `FollowUp` gains `appointmentId` and `notNeeded`. A new visit with the same doctor no longer "returns" a not-needed follow-up (`closeOldestOpen` skips it). See open question 2 below.

### D10 · Doctor rail derivations

- Department (E8, ADR-020 D7 step 2): the published EMR departments the doctor is assigned to; one → it; several → the one whose starter key equals the clinic default's, else the first by name; none → the doctor's specialty (platform doctor profile); neither → null (listed only under "All departments"). `departmentSource` says which.
- Room: the chosen EMR department's room labels joined with ", " in stored order; null when the doctor has no EMR department or it has no room label. Never from the specialty fallback.
- Today status, first match wins: **ON_LEAVE** (approved leave covers today; `leaveUntil` = last day of the continuous leave from today, looked up to 60 days) → **OFF_TODAY** (not a working day or no session) → **FULL_TODAY** (sessions today but no free slot left today under D3 for the caller, also after the last session ended) → **IN_OPD** (now inside a session) → **STARTS_LATER** (a session later today; `startsAt` = its start, also between a morning and an evening session). Running late is not built (E7).
- Fees: `consultFeePaise` = the doctor's current fee (platform doctor profile, as today); `followUpFeePaise` = clinic rule free 0, half `fee / 2` (integer paise, as `VisitService.feeFor`), full `fee` (C1). Information on the booking only; billing follows SAH-9 and SAH-10 at check-in.

### D11 · Board mapping and tags (E2, E3)

- Cards: today's (IST) appointments that are not CANCELLED, each with its visit when checked in, plus today's visits that no such appointment points to (walk-ins; `kind` WALK_IN).
- Column: appointment BOOKED or NOSHOW → BOOKED; visit WAITING, VITALS → WAITING; WITH_DOCTOR → IN_CONSULTATION; COUNSEL, BILLING, DONE → DONE.
- Tags (server-computed with IST now and the saved rules, nothing stored): NO_SHOW (stored NOSHOW, or BOOKED and now ≥ time + `noShowAfterMinutes`), DUE_NOW (BOOKED, now ≥ time and before the grace ends), DOCTOR_ON_LEAVE (BOOKED and the doctor is on approved leave that day), VITALS (stage VITALS).
- Primary action, by state only (the client hides write actions for read-only roles): NO_SHOW → REBOOK; else DOCTOR_ON_LEAVE → MOVE_TO_NEW_SLOT; else BOOKED → CHECK_IN; WAITING column → START_CONSULTATION; IN_CONSULTATION → COMPLETE_VISIT; DONE → SET_FOLLOW_UP when the visit's follow-up is NONE or OPEN, else NONE with the follow-up tag (BOOKED with date and time, or NOT_NEEDED).
- Counters (today, all doctors, never filtered by the board filter): booked = cards in BOOKED without NO_SHOW; waiting, inConsult, done by column; freeToday = sum of today's free slots of all doctors under D3 for the caller.

### D12 · Security and privacy

- Every desk read: any clinic role (`OpdRoles.ANY_CLINIC_ROLE`); every write: OWNER, FRONT_DESK (`OWNER_OR_FRONT_DESK`, re-checked in the service). Tenant from the signed-in user only; a `tenantId` or `clinicId` in a body is ignored; other clinics' ids answer 404.
- The snapshot carries patient names (needed on the cards) but no mobile, age, ABHA or clinical text. The idle list's discharge follow-up reason keeps the SAH-27 masking. Patient mobile is shown only in the booking panel from `GET /patients?q=`.
- Audit values: ids, dates, times, status words, interval keys, token numbers. Never names, mobiles or notes. Problem details never hold a patient name.

---

## Consequences

- Positive: one poll per tab; slot logic in one place; the old board and the console get the same server checks; check-in, cancel and stage races closed; reschedule keeps one id and its follow-up link.
- Negative: the snapshot is a larger answer (gzip needed); clock-derived values mean no HTTP caching; the console behaviour of Start consultation with Vitals on is two presses.
- Follow-ups: drop the zero-argument `opd.next_token_no()` in a later contract migration once nothing calls it; measure snapshot latency and request rate after go-live (D2 levers).

## Open questions (defaults applied in the contract; the tech lead or human may override)

1. Room when a department has several room labels: default all labels joined with ", " (for example "Room 2, Room 3"); alternative the first label only.
2. A follow-up marked "No follow-up needed" leaves the SAH-14 Due, Missed and Upcoming lists and the Today counts, and a later visit with the same doctor does not "return" it (D9.5). Default yes; the alternative keeps SAH-14 unchanged and shows it there as still owed.
3. Bookings made by the doctor's EMR sign (source EMR) keep their current rule: no cut-off and no past-time refusal beyond what free slots already exclude. Default unchanged.
4. Rail status between a morning and an evening session reads "Starts later · From 5:00 PM"; after the last session ends the doctor reads "Full today" (literal reading of SAH-90-2). Default as stated.
5. Morning and Evening: by session start before or from 12:00 IST (D3). Default as stated.
6. Check-in only on the appointment day (400 check-in-not-today) also applies to the old board at `/app/appointments`. Default yes (it is a server rule).
7. Free counts follow the caller's role (D3): a nurse does not count slots inside the cut-off, the front desk does. Default as stated.

## Data needed (db-engineer)

All additive; no drop, rename or rewrite. Tenant scope: every row already carries `tenant_id` with forced RLS; nothing new is cross-tenant. Retention: as the existing tables (never deleted).

1. `opd.doctor_slot_template.close_before_hours integer null`, check `0..48`. Null means the clinic value. (SAH-91-2)
2. `opd.follow_up.not_needed_at timestamptz null`, `not_needed_by uuid null`, check both null or both set. (SAH-93-1)
3. Partial index for the to-book list: `opd.follow_up (tenant_id, due_date) where returned_visit_id is null and appointment_id is null and not_needed_at is null`. (SAH-90-5)
4. `opd.visit.consult_started_at timestamptz null`, `consult_ended_at timestamptz null`; check `consult_ended_at is null or consult_started_at is null or consult_ended_at >= consult_started_at`. No backfill. (SAH-92-3)
5. `opd.next_token_no(p_token_date date) returns integer`: same body as today keyed on the given date (row lock through `insert … on conflict do update … returning`), `security invoker`, executable by `sahaj_app`. Keep the zero-argument version. Comment that the app passes the Asia/Kolkata date and inserts `token_date` explicitly. (SAH-92-1)
6. Index `opd.appointment (tenant_id, appt_date) where status <> 'CANCELLED'` for the all-doctor date-range reads (snapshot and board); the existing `(tenant_id, doctor_membership_id, appt_date)` stays for single-doctor reads. (SAH-90)
7. Check that `opd.appointment.version` is incremented by every update (status change, token, reschedule); if it is only a column without a trigger, the java-developer increments it in SQL (no DB change needed, confirm in the design note).
8. No change to `opd.appointment`, `opd.booking_rules`, `opd.visit_token_counter` columns. No new table.
