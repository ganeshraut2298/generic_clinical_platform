# ADR-007 · IPD Safety board: escalation state machine in the database, one admission lock, discharge rule, counts-only polling

Story: SAH-33 (IPD part 12: Safety board, early warning escalations and the clinical event log)
Mockup screen: IPD → Safety tab of Beds and admissions (`sfTabOn`, `sfCount`, `ipSafety`, `escRow`, `sfack`, `sfresp`, `sfrespok`, `sfup`, `sfLateEsc`, `sfMins`, `sfLog`, `kic`) and the banner "Open safety board" on the Admitted patients tab (`ipList` wrap, `sfban`)
Module: ipd only (uses the existing `platform.api` and `patient.api` ports; no new dependency)
Status: PROPOSED (5 Nov 2026), with the SAH-33 contract. It is accepted when the human approves `contract`. Note: `docs/approvals.md` has no `stories` line for SAH-33 yet; work proceeds under DEC-015. Amended the same day by the section "Review fixes" (R1 to R8, after `docs/reviews/SAH-33-code.md` and `docs/reviews/SAH-33-security.md`); where a review fix and an earlier decision differ, the review fix applies.
Deciders: Ganesh (human), tech-lead; recorded by solution-architect
Related: `docs/stories/SAH-33.md`, `docs/decisions/DEC-015-safety-board-open-questions-proceed.md`, ADR-006 (D1 tables, D4 lock, D7 templates, Q9 discharge), ADR-005 (D3 summary per module, D6 clock), ADR-004 (discharge), `docs/reviews/SAH-30-security.md` (L3, L4, L5, M1), `docs/reviews/SAH-30-code.md` (M2, N12), `docs/database/SAH-30.md`, `docs/api/ipd.yaml`

## Context

1. SAH-30 raises and updates one escalation per admission (`ipd.ews_escalation`) and writes `ipd.clinical_event`. Nobody can act on an escalation yet (OQ-30-2). The update grant covers only `ews_score`, `ews_risk`, `target_minutes`, `version`; the guard trigger only refuses updates of DONE rows, falling scores and falling versions.
2. The security review left three items for this story: L3 (a rising score does not re-open an acknowledged escalation), L4 (discharge leaves an open escalation open for ever), L5 (done in V2026_11_25).
3. DEC-015 fixes the roles (OWNER and DOCTOR acknowledge and respond; NURSE calls the senior; no access for VISITING_DOCTOR, FRONT_DESK, BILLING, PHARMACIST in this story), the honest toast for Call senior, the re-open on a risk rise, discharge refusal with exceptions to be defined here, the privacy rules (plan not in the log, names at display time, no record-access row for the board), Read-only actions allowed, 60-second refresh with counts-only polling, and that SAH-33 creates the tab.
4. The board shows names and clinical text; the tab count and the banner must update without polling names.

## Decision

### D1 · Columns, grants and checks on `ipd.ews_escalation` (one new forward-only migration)

New columns:
- `closed_by uuid null` (user id, IPD actor convention; ADR-006 Q11).
- `reopened_at timestamptz null`: set only when an Acknowledged escalation is re-opened (D4). The **response clock start** is `coalesce(reopened_at, raised_at)`. `raised_at` and `raised_by` never change, so the original raise stays on the record.

Grant widened (column-level update for `sahaj_app`): existing `ews_score, ews_risk, target_minutes, version` plus `status, acknowledged_by, response_action, response_note, senior_calls, closed_by`. **Not granted: `acknowledged_at`, `closed_at`, `reopened_at`** (a deliberate tightening of the story's list): the guard trigger stamps them from the database clock, so the app can never back-date or forward-date an acknowledgement, a close or a re-open. A BEFORE UPDATE trigger may set columns outside the caller's update grant.

Codes for `response_action` (labels in the order of the mockup's select):

| Code | Label | Who |
|---|---|---|
| `BEDSIDE_REVIEW` | Reviewed at the bedside | doctor (default) |
| `TREATMENT_CHANGED` | Treatment changed | doctor |
| `MOVED_ICU_HDU` | Moved to ICU or HDU | doctor |
| `RAPID_RESPONSE` | Rapid response team called | doctor |
| `NO_CHANGE` | Reviewed, no change needed | doctor |
| `CLOSED_AT_DISCHARGE` | Closed at discharge | system only (D6); never accepted from a request |

Checks (added validated; every existing row is OPEN with null response fields, so all hold):
- `ck_ews_escalation_response_action`: `response_action is null or response_action in (the six codes)`.
- `ck_ews_escalation_done_action`: `(status = 'DONE') = (response_action is not null)` and `(status = 'DONE') = (closed_by is not null)` (two checks or one, db-engineer's choice). The existing `ck_ews_escalation_closed` (`DONE` iff `closed_at`) stays.
- `ck_ews_escalation_response_note`: `response_note is null or char_length(response_note) between 1 and 1000`, and `(response_note is not null) = (status = 'DONE' and response_action <> 'CLOSED_AT_DISCHARGE')`.
- `ck_ews_escalation_ack_by_status`: `status <> 'OPEN' or acknowledged_by is null`; `status <> 'ACK' or acknowledged_by is not null`; `status <> 'DONE' or response_action = 'CLOSED_AT_DISCHARGE' or acknowledged_by is not null`. The existing `ck_ews_escalation_ack_pair` stays.
- `ck_ews_escalation_reopened`: `reopened_at is null or reopened_at >= raised_at`.

Index: `ix_ews_escalation_tenant_admission (tenant_id, admission_id, raised_at)` (not partial), for the board's closed rows of admitted patients. The partial unique index stays the one-open guarantee.

### D2 · The state machine lives in Java and is enforced again by the guard trigger

Java: one pure class `com.sahaj.ipd.domain.EscalationState` (no Spring, no I/O) decides each transition and refusal; one class `com.sahaj.ipd.domain.EscalationClock` holds "past target" and the minute counts (D5). The trigger is the backstop (SQLSTATE 23514, message contains "escalation"); a trigger hit is a code bug (500, full rollback).

`ipd.guard_ews_escalation_update()` is replaced (`create or replace`, SECURITY INVOKER, fixed `search_path`, still revoked from public and `sahaj_app`):

| From → to | Allowed when | Trigger stamps |
|---|---|---|
| any DONE → anything | never (existing) | |
| OPEN → OPEN | score update (strictly higher score, risk never falls; existing rule) or a senior call alone | |
| OPEN → ACK | `old.acknowledged_by is null`, `new.acknowledged_by is not null`; score, risk, target unchanged | `acknowledged_at := clock_timestamp()` |
| ACK → ACK | score update inside the same risk, or HIGH stays HIGH with a higher score; acknowledgement unchanged | |
| ACK → OPEN | `old.ews_risk = 'MEDIUM'` and `new.ews_risk = 'HIGH'` and `new.ews_score > old.ews_score` and `new.acknowledged_by is null` | `acknowledged_at := null`, `reopened_at := now()` (the vitals save's transaction instant, ADR-006 D1) |
| OPEN or ACK → DONE | `new.response_action` and `new.closed_by` set; for the five doctor codes the admission is ADMITTED, the note is set, and from OPEN `new.acknowledged_by = new.closed_by`; for `CLOSED_AT_DISCHARGE` the admission is DISCHARGED (an `exists` read of `ipd.admission` in the same transaction) and the note is null | `closed_at := clock_timestamp()`; from OPEN with a doctor code also `acknowledged_at := closed_at`; with `CLOSED_AT_DISCHARGE` the acknowledgement fields are kept as they were (null from OPEN) |
| ACK → anything else, OPEN → anything else | refused | |

Field rules in the same trigger:
- `id`, `tenant_id`, `admission_id`, `raised_at`, `raised_by` never change. An app-supplied change of `acknowledged_at`, `closed_at` or `reopened_at` is refused (they are not granted anyway; the trigger overwrites them on the transitions above and otherwise requires them unchanged).
- `acknowledged_by` changes only on OPEN → ACK, OPEN → DONE (doctor code) and ACK → OPEN (to null). `response_action`, `response_note`, `closed_by` change only on → DONE.
- `senior_calls` changes only while `old.status = 'OPEN'` and `new.status = 'OPEN'`, only by exactly + 1, and only in an update that changes nothing else (version unchanged).
- **`version`**: exactly `old.version + 1` when any of `status`, `ews_score`, `ews_risk`, `target_minutes`, `acknowledged_by`, `response_action`, `response_note`, `closed_by` changes; unchanged otherwise. This replaces "never lower" and gives the replay rules of D7 a reliable "+1". The SAH-30 update already sends `version + 1` with every score change (db-engineer and java-developer confirm no other statement sets `version`).

Why a senior call does not bump `version`: AC13 lists the changes that must stop a stale action (re-opened, score raised, acknowledged, closed). A nurse's call is not one of them; bumping it would refuse a doctor's Acknowledge just because the nurse called the senior.

### D3 · One lock rule for every escalation write (ADR-006 D4 "Rule for the Safety board story")

Every write runs in an explicit READ COMMITTED transaction (`@Transactional(isolation = Isolation.READ_COMMITTED)`, comment pointing here):

1. Before the transaction: token role (403), IPD Off (403 module-off), body (400). No Read-only check (D9).
2. In the transaction, before any lock: the role is read again from the active membership (`platform.api.TeamMemberPort.findActiveByUserId`); no active membership or a role outside the operation's set: 403 forbidden, nothing written (SAH-30 security M1).
3. A plain read of the escalation by id gives its `admission_id` (404 not-found when missing or another clinic's; RLS).
4. **The lock:** the bare admission row `select id, status from ipd.admission where tenant_id = :t and id = :admissionId for no key update` (the SAH-30 statement; code review M2). Then, in new statements, the escalation again (it may have changed while waiting) and the joined admission, bed and ward for the bed code. Admission not ADMITTED: 404 not-found (AC18).
5. Patient name through `patient.api.PatientLookupPort` (404 when missing, never a placeholder in an action answer; ADR-006 N6).
6. State and version checks (D7), then one UPDATE of the escalation, one `clinical_event` insert, one audit row.

The same lock is already taken by the SAH-30 save (re-open, D4). **The discharge** keeps its existing order (clinic IPD advisory lock → bed `for update` → admission `for update`, bare row first, then the join; code review M2) and reads the escalation only after it holds the admission row. `for update` conflicts with `for no key update`, so the discharge and every escalation write are serialised on that row. The brief's "discharge takes the same lock first" is met in the sense that matters: the admission row lock is taken before the escalation is read. Taking the admission lock before the advisory lock and the bed lock would invert the order the transfer uses and could deadlock, so the discharge order is not changed.

**No deadlock:** an escalation action holds exactly one row lock and waits for nothing while holding it (its UPDATE touches a row whose writers all hold the same admission lock; its event insert takes only a key-share lock on that admission). The discharge and the transfer take advisory → bed → admission; an action either finishes first or waits and then finds DISCHARGED (404).

**Races (QA, real-database ITs):** two Acknowledges (one 200, one 409 `escalation-state` "<doctor name> already acknowledged this escalation."); Acknowledge against a re-opening save (the later one sees the other's version: ack first then save → REOPENED; save first then ack → 409 `escalation-changed`); Close against discharge (close first → discharge passes; discharge first → close 404); Call senior against Close (close first → 409 `escalation-state`). A failed action rolls back the update, the event and the audit together.

### D4 · The re-open rule in the SAH-30 save (AC12, OQ-33-4)

Inside `VitalsService.record`, under the lock it already holds, the escalation step becomes (L3 closed):

| Open escalation | New reading | Outcome | Writes |
|---|---|---|---|
| none | MEDIUM or HIGH | RAISED (unchanged) | insert, event "Raised early warning escalation · …" |
| OPEN | higher score | UPDATED (unchanged) | score, risk, target, version + 1; event "Escalation updated · EWS now N (risk)" |
| ACK, risk MEDIUM | HIGH (always a higher score) | **REOPENED** (new) | status OPEN, score, risk HIGH, target 15, `acknowledged_by` null, version + 1 (trigger clears `acknowledged_at`, stamps `reopened_at = now()`); event "Escalation re-opened · EWS now N (high) · doctor review within 15 min"; audit IPD_ESCALATION_REOPENED |
| ACK | higher score, same risk | UPDATED | as above; status stays ACK |
| any | equal or lower score, or LOW | NONE (unchanged) | nothing |

`VitalsSaved.escalation.outcome` gains `REOPENED`; `IPD_VITALS_RECORDED.escalationOutcome` gains `REOPENED`. The SAH-30 toast is unchanged (AC22): `open` is set, `open.targetMinutes` is 15, so it reads "Escalation raised for … · doctor review within 15 min. Tell the doctor now: no alert is sent." The earlier "Acknowledged escalation after N min" stays in the event log (append-only).

### D5 · Overdue and minutes on the database clock (AC7)

- `asOf` = `clock_timestamp()` read once per answer through a repository call (ADR-006 D1), after the role re-read on reads and after the writes on actions. The browser and the application clock are never used.
- `EscalationClock` (pure Java, `Instant` in, no I/O): `clockStart = reopenedAt != null ? reopenedAt : raisedAt`.
  - `pastTarget = status == OPEN && Duration.between(clockStart, asOf) > targetMinutes minutes` (strictly more, exact duration: at 15:00 not past, at 15:00 plus any time past). Acknowledged or closed is never past target.
  - `waitingMinutes` = whole minutes from `clockStart` to `asOf`, rounded down, never negative.
  - `minutesSinceRaised` = whole minutes from `raisedAt` to `asOf`, rounded down, never negative.
  - Event minutes: "Acknowledged escalation after N min" = whole minutes from `clockStart` to `acknowledged_at`; "Called the senior doctor: escalation not seen after N min" = `waitingMinutes` at the call. Both rounded down, never negative.
- Accepted display effect: at 15 min 30 s a High row reads "15 min ago · target 15 min" with "Overdue · not seen". The safe side (earlier) is chosen over the mockup's `Math.round`.
- Unit tests: 15:00 not past, 15:00.001 and 15:01 past; 60:00 not past, 60:01 past; ACK never past; re-opened measured from `reopenedAt`.

### D6 · Discharge with an escalation not closed (AC14, OQ-33-5, L4): refuse Normal, close-at-discharge for Death, LAMA, Referred

The check runs in `DischargeService.discharge` under the admission row lock, **after** the `summary-not-signed` check (so the type is the one a doctor signed) and before `bill-changed`:

| Discharge type (signed) | Escalation OPEN or ACK | Why |
|---|---|---|
| `normal` | **409 `escalation-open`**, "This patient has an early warning escalation that is not closed. A doctor must record the response first." Nothing written. | A deteriorating patient sent home is the case L4 is about. A doctor already has to sign the summary, so a doctor is available to record the response; the refusal never blocks indefinitely. |
| `death` | **closed in the discharge transaction** as `CLOSED_AT_DISCHARGE` | The response list has no true answer; refusing would push a doctor to record a false response to complete the record. The event says plainly that no response was recorded. |
| `lama` | closed as `CLOSED_AT_DISCHARGE` | The patient has left; refusing keeps a phantom patient on the bed board and an "Overdue · not seen" row on the Safety board that distracts from real ones. |
| `referred` | closed as `CLOSED_AT_DISCHARGE` | The patient is physically moved to another hospital; the five actions do not include a referral; refusing only makes the record wrong. |
| (bed transfer, SAH-26) | not a discharge: nothing changes | The escalation stays with the admission and the board shows the new bed. A transfer is never refused for an escalation. |

Close-at-discharge writes, after `markDischarged` (the trigger requires DISCHARGED): status DONE, `response_action = 'CLOSED_AT_DISCHARGE'`, `response_note` null, `closed_by` = the discharging user (OWNER or FRONT_DESK), `version + 1`; acknowledgement fields kept as they were. Event (kind esc) "Escalation closed at discharge (death) · no doctor response recorded" ("(on request (lama))", "(referred)": the DischargeType label in lower case, as the SAH-27 toast writes it). Audit IPD_ESCALATION_CLOSED_AT_DISCHARGE. `IPD_DISCHARGED` is unchanged.

A FRONT_DESK user cannot use this to bypass the Normal refusal: the discharge type must equal the doctor's signed type (`summary-not-signed`).

The Discharge sheet carries `openEscalation` (boolean only, no score: FRONT_DESK opens it) and shows a warning before the button is pressed: for Normal the 409 text above (story); for Death, LAMA and Referred "This patient has an early warning escalation that is not closed. Completing this discharge closes it as "Closed at discharge", with no doctor response recorded." (contract wording, new). The button is not disabled; the server is the judge.

**Clinical sign-off required** (patient safety): the exceptions, the event wording and whether Referred should be refused instead (Questions Q2, Q3). Tightened by the review fix R2: an exception applies only when the doctor's sign-off is later than the escalation's response-clock start.

### D7 · Check order, version and replay per action (AC13, AC15)

After the lock (D3 step 4) and the patient lookup:

| Action | Current state | Answer |
|---|---|---|
| Acknowledge | OPEN, version = sent | 200, write |
| | OPEN, version ≠ sent | 409 `escalation-changed` |
| | ACK by the caller and version = sent + 1 | 200 `replayed: true`, nothing written (lost answer, double tap) |
| | ACK otherwise | 409 `escalation-state` "<doctor name> already acknowledged this escalation." |
| | DONE | 409 `escalation-state` "This escalation is already closed." |
| Respond | OPEN or ACK, version = sent | 200, write |
| | OPEN or ACK, version ≠ sent | 409 `escalation-changed` |
| | DONE, closed by the caller, version = sent + 1, same action and same stored note | 200 `replayed: true`, nothing written |
| | DONE otherwise | 409 `escalation-state` "This escalation is already closed." |
| Call senior | OPEN, version = sent, `senior_calls` = sent `seniorCalls` | 200, write (+ 1) |
| | OPEN, version = sent, `senior_calls` ≠ sent | 200 `replayed: true`, nothing written |
| | OPEN, version ≠ sent | 409 `escalation-changed` |
| | ACK / DONE | 409 `escalation-state` (the two messages above) |

`escalation-changed` detail: when the current status is ACK and `acknowledged_by` is not the caller, "<doctor name> already acknowledged this escalation."; otherwise "The score is now EWS <score> (<risk in lower case>). Review it and try again." Doctor names are staff names (`ActorDirectoryPort`, "Team member" fallback); a problem detail never holds a patient name. The 409 carries no extension member (no platform change): the client reloads the board, which is the current state.

**Call senior idempotency: expected-count compare, not a clientKey and not none.** The request carries the `seniorCalls` the nurse saw. A retry after a lost answer finds the count already raised and writes nothing (200, same toast, which stays true: a call is recorded). A deliberate second call after the board reloaded carries the new count and is recorded, so "each tap is one call" (mockup) holds for taps on fresh data. Options rejected: **clientKey** (ADR-005/006 pattern) needs a key per call, so a new table, for a counter; the compare gives the same guarantee with no new storage. **None**: a lost answer retried would inflate "escalated to senior N×", which is audit data a reviewer reads later. Two nurses tapping within the same refresh count once; that is correct (one escalation to the senior).

### D8 · Board, summary, banner and refresh (ADR-005 D3)

- `GET /safety` (OWNER, DOCTOR, NURSE): the whole board, one read-only REPEATABLE READ transaction (ADR-005 D6) so the counts, the notice and the rows agree; `asOf` from the same transaction. Rows: every escalation of every ADMITTED admission (open and closed, as the mockup: the panel keeps closed rows for as long as the patient is admitted; OQ-33-13 stays open). Order: not closed first, then score descending, then the older clock start first, then id (`java.util.UUID` order). The tiles' numbers are counted by the server from the same rows. Patient names in one `PatientLookupPort.findAll`; staff names cached per answer. No record-access row (DEC-015). Never logged.
- `GET /safety/summary` (OWNER, DOCTOR, NURSE): **counts only** (`open`, `pastTarget`, `asOf`); no names, scores or text. The page polls it every 60 seconds while Beds and admissions is open and the Safety tab is shown (DEC-015, OQ-33-10); it sets the tab count. When `open` or `pastTarget` differ from what the current tab shows, the client reloads that tab's data (the board, or `GET /admissions` for the banner). So names never travel in a poll.
- The Safety board itself reloads every 60 seconds while the tab is open and after every action (AC20); the tab count also takes `counts` of each board answer.
- **Banner** (Admitted patients tab): `AdmissionList.safety.pastTarget` (names and scores of OPEN escalations past target), returned only to OWNER, DOCTOR, NURSE; `safety` is null for every other role (FRONT_DESK and BILLING never get a score). The list already carries names; this adds scores to roles with vitals access only.
- **Tab visibility** (client, AC1): when `AdmissionList.safety` is not null (OWNER, DOCTOR, NURSE; IPD Trial, On or Read-only) and at least one patient is admitted (mockup `sfTabOn`), the last tab "Safety" with `safety.counts.open` next to the name (hidden at 0). No `/me.menu` key: the mockup has no sidebar item or badge for Safety, so `platform.yaml` does not change.
- **No `AdmissionDetail` change** in this story: the banner is on the list. The card flags "Not seen · N min" and "Doctor reviewing" are the card story's; it adds an escalation field to `AdmissionDetail` then, using `EscalationClock`.

### D9 · Roles and Read-only

| Operation | Token role and membership role (both) | Read-only IPD |
|---|---|---|
| `GET /safety`, `GET /safety/summary`, `GET /clinical-events` | OWNER, DOCTOR, NURSE | allowed |
| `POST /escalations/{id}/acknowledge`, `/respond` | OWNER, DOCTOR | allowed (DEC-015, OQ-33-9) |
| `POST /escalations/{id}/call-senior` | NURSE | allowed |
| every other role (FRONT_DESK, BILLING, VISITING_DOCTOR, PHARMACIST, others) | 403 forbidden | |

Reads re-read the membership too; the row flags `canAcknowledge`, `canRespond`, `canCallSenior` and the board `view` (DOCTOR for OWNER and DOCTOR, NURSE for NURSE) use the membership role and require the token role to be in the same set, so a shown button is never refused for the role. The mockup's "Only a doctor can do this." needs no text here: the buttons are not shown to other roles. An OWNER without a doctor profile may act (OQ-33-1 detail, open).

### D10 · Clinical event templates (fixed, `ipd.clinical_event.text`, kind `esc`)

- "Acknowledged escalation after N min"
- "Escalation closed: <action label>" (never the plan; deviation from the mockup, DEC-015 OQ-33-7)
- "Called the senior doctor: escalation not seen after N min"
- "Escalation re-opened · EWS now N (high) · doctor review within 15 min"
- "Escalation updated · EWS now N (medium)" (existing SAH-30 template, now also for ACK)
- "Escalation closed at discharge (<type label in lower case>) · no doctor response recorded" (D6; new)

The SAH-30 templates stay in the same single template class (ADR-006 D7). No patient name, mobile, ABHA number, vital value or note text ever goes into an event. A close from OPEN writes no "Acknowledged" event (mockup).

### D11 · Audit

`AuditLog.record(type, "IPD_EWS_ESCALATION", escalationId, before, after)`; fields only from this list, never the plan, a name or a vital value:

| Event | Fields |
|---|---|
| IPD_ESCALATION_ACKNOWLEDGED | escalationId, admissionId, ewsScore, ewsRisk, minutesToAcknowledge |
| IPD_ESCALATION_CLOSED | escalationId, admissionId, ewsScore, ewsRisk, action (code), statusBefore (OPEN or ACK) |
| IPD_ESCALATION_SENIOR_CALLED | escalationId, admissionId, ewsScore, ewsRisk, seniorCalls (after) |
| IPD_ESCALATION_REOPENED | escalationId, admissionId, ewsScore, ewsRisk, previousScore, previousRisk |
| IPD_ESCALATION_CLOSED_AT_DISCHARGE | escalationId, admissionId, ewsScore, ewsRisk, statusBefore, dischargeType |

A replay (200 `replayed: true`) writes no audit and no event. Application logs carry ids, `tenantId` and `requestId` only; the board, the event list and the Response sheet body are never logged.

### D12 · Record access (OQ-33-8): no row for the board, the summary, the log or the actions

DEC-015: reading the board does not write `patient.record_access`. Reasons: the board is a ward list refreshed every 60 seconds (one row per patient per refresh per user would flood the owner's "who opened which record" log and hide real openings); every clinical action on it is already audited and in the clinical event log; the board holds less than the Nursing sheet (no vitals values, no notes). The clinical audit trail list follows the same rule. Action answers and replays write none either (the replay goes only to the user who did the action, with data the board already shows them). The Nursing sheet and the admission sheet keep their access rows (ADR-006 D6).

### D13 · Clinical audit trail pagination: keyset on `(at, seq)`

- `GET /clinical-events?limit=&cursor=`: newest first, `at desc, seq desc` (ADR-006 D1). `limit` 1 to 50, default 14 (mockup); "Show earlier entries" sends `limit=50` and the `nextCursor` of the last page (DEC-015).
- The cursor is opaque to the client: base64url of the last entry's `at` (epoch microseconds) and `seq`. The query is `(at, seq) < (:at, :seq)` on `ix_clinical_event_tenant_at`; `limit + 1` rows are read to know whether `nextCursor` is set. Offset paging is rejected: new entries arrive every minute and would shift pages.
- Scope: OWNER and DOCTOR every entry of the clinic; NURSE her own entries (`actor` = caller) and the entries of ADMITTED admissions (every admitted patient until wards exist; OQ-33-2 open). Entries with no admission by other users are not shown to a nurse.
- Patient name and bed are read at display time (patient through `PatientLookupPort.findAll`, bed from the admission's current or last bed); actor name through `ActorDirectoryPort` ("Team member"). The bed is the bed now, not the bed at the time of the entry (a stated gap; the mockup stores it in the entry, which this design avoids so no identifying text is stored in the log).
- There is no edit, delete or export endpoint (append-only, ADR-006 D1).

### D14 · Module boundaries and phase 1

- **ipd only.** Uses `platform.api` (`TeamMemberPort.findActiveByUserId`, `ActorDirectoryPort`, `AuditLog`, entitlement) and `patient.api.PatientLookupPort` (existing `findAll`). No new port, event, dependency or library; `ModularityTests` stays green. Optional, java-developer's choice: a batch `ActorDirectoryPort.displayNames(tenantId, Collection<UUID>)` in `platform.api` for the 14 to 50 names of an event page (per-answer cache is enough at these sizes).
- **No cross-module table access:** patient names never come from a join to `patient.patient`.
- **Phase 1 held:** no notification, call, SMS, WhatsApp or sound to anyone (Call senior only records); no break-glass (SAH-34); no ward scoping; no report, analytics, export, search or filter of the log; no document.

### D15 · Retention

Escalations and clinical events are in-patient record (ADR-006 D9): never deleted; the period is the human's (ADR-006 Q10, OQ-33-16). The plan text (`response_note`) is clinical text kept with the escalation, shown only to OWNER, DOCTOR and NURSE on the board.

## Options considered (summary)

- **State machine only in Java** vs **Java plus trigger (chosen):** the trigger costs one function and catches any code path that skips the rules, as the SAH-30 guard already does.
- **App-supplied timestamps (story's grant list)** vs **trigger-stamped (chosen):** time is owned by the database, as in SAH-30 (`raised_at` not insertable).
- **Discharge refuses every type** vs **auto-close every type** vs **refuse Normal, close Death/LAMA/Referred (chosen):** D6.
- **Summary with names for the banner** vs **counts-only summary plus names in the admitted list (chosen):** ADR-005 D3, no names in polls.

## Consequences

**Positive.** An escalation can now be acted on and every response is shown afterwards; a worsening patient cannot stay "Doctor reviewing"; a Normal discharge cannot leave an escalation unanswered; one lock and the database guard make races and stale screens safe; polls carry no names; no new dependency.

**Negative / accepted.**
- The trigger grows (one function); its tests are QA's (every transition and refusal as `sahaj_app` and as the migrator).
- An Acknowledged escalation has no target (OQ-33-6 open): it can stay "Doctor reviewing" for hours.
- 60-second refresh for a 15-minute target (DEC-015).
- The nurse sees every admitted patient (OQ-33-2), and the tile text "In your wards" is not true until wards exist.
- The log shows the current bed, not the bed at the time.
- Each board, summary and event read costs one more membership read (until HARD-2).

## Data needed (for db-engineer; one forward-only migration, for example `V2026_11_26_1000__ipd_safety_board.sql`)

- `ipd.ews_escalation`: add `closed_by uuid null`, `reopened_at timestamptz null`; the checks of D1; index `ix_ews_escalation_tenant_admission (tenant_id, admission_id, raised_at)`; widen the update grant to add `status, acknowledged_by, response_action, response_note, senior_calls, closed_by` (not `acknowledged_at`, `closed_at`, `reopened_at`); replace `ipd.guard_ews_escalation_update()` with the D2 machine (keep 23514, SECURITY INVOKER, fixed `search_path`, revoked from public and `sahaj_app`); update the table and column comments (the "Safety board story" wording, the clock start, the codes). The update RLS policy exists. Tenant scope unchanged (FORCE RLS). Nothing deleted.
- `ipd.clinical_event`: no change. Optional index for the nurse scope `(tenant_id, actor, at desc, seq desc)` if the plan shows the filter scan is costly; db-engineer decides from the expected volumes in `docs/database/SAH-30.md`.
- `ipd.admission`, `ipd.discharge`: no change.
- Retention: never deleted (D15). Compatibility: additive; the SAH-30 app version still works (it never sets the new columns; its update sets `version + 1` together with a score change, which the new guard accepts). Confirm no other statement updates `version` alone.

## Instructions

**java-developer:**
1. `ipd.domain.EscalationState` and `EscalationClock` (D2, D5) with unit tests for every transition and refusal (OPEN→ACK, OPEN→DONE, ACK→DONE, ACK→OPEN only on MEDIUM→HIGH, DONE final, double acknowledge, stale version, replays of D7) and the clock edges.
2. `SafetyService` (board, summary, events) and `EscalationService` (acknowledge, respond, call-senior) per D3 and D7; explicit READ COMMITTED; role re-read; Read-only allowed; patient missing 404 on actions.
3. `VitalsService`: the re-open rule of D4, outcome REOPENED, audit IPD_ESCALATION_REOPENED.
4. `DischargeService.discharge`: D6 after `summary-not-signed`, under the lock it already holds; close-at-discharge after `markDischarged`. `DischargeSheet.openEscalation`.
5. `AdmissionList.safety` (D8). Templates (D10) in the existing template class; audit (D11). Plan text rules as the SAH-30 note (contract `EscalationResponseInput.note`). Remove or use `EscalationRow.version` (code review N12): it is now used.

**angular-developer:** Safety tab (visibility and count, D8); red notice; tiles; escalation panel, pills, buttons by row flags; Response sheet with validation and kept text; audit trail with "Show earlier entries"; banner on Admitted patients; 60-second summary poll and board refresh; stale notice; toasts and 409 handling per contract; discharge sheet warning (D6). Every "ago" from `asOf`.

## Questions for the human

- **Q1 (go-live, OQ-33-12):** record in a document that SAH-30/33 do not run on a live ward before SAH-33 is built and OQ-30-6 (score bands and response times) is signed off. No technical gate exists.
- **Q2 (clinical, OQ-33-5):** discharge rule D6: Normal refused; Death, LAMA and Referred close the escalation as "Closed at discharge" with the event "Escalation closed at discharge (<type>) · no doctor response recorded". A doctor to confirm, or to choose refusal for all types. Amended by R2: the exceptions apply only when the sign-off is later than the escalation's response-clock start (see the clinical sign-off note in R2).
- **Q3 (clinical):** should Referred be refused instead (a referral is a doctor's decision about a deteriorating patient, so a response could be recorded first)?
- **Q4 (new text):** the Discharge sheet warning for Death, LAMA and Referred, and the closed-at-discharge event text, are contract wording, not the mockup's or the story's.
- **Q5 (clinical, OQ-33-4):** re-open only on a risk rise MEDIUM→HIGH, clock restarted from the rise (DEC-015). The red notice and the row's "ago" for a re-opened escalation: the notice uses the minutes since the re-open (the overdue clock), the row keeps "Raised by <nurse> <ago of the first raise>". Is a visible "re-opened" marker on the row wanted (not in the mockup)?
- **Q6 (past target):** strictly more than the target as an exact duration (15 min 1 s is past), not the mockup's rounded minutes. Confirm.
- **Q7 (OQ-33-7 detail):** the closed row shows the acknowledging doctor's name, as the mockup (`e.ack`). When another doctor closes it, the closer is stored and returned (`closedByName`) but not shown. Show the closer instead (story proposal)?
- **Q8 (OQ-33-2):** the nurse's tile reads "In your wards" (mockup) although she sees every admitted patient. Keep, or "All admitted patients" (story proposal)?
- **Q9 (OQ-33-13):** closed escalations stay on the board for the whole stay (mockup). Limit to the last 24 hours?
- **Q10 (OQ-33-6):** Acknowledged has no target; `acknowledgedAt` is returned so a later rule can use it.
- **Q11 (OQ-33-1 detail):** an OWNER without a doctor profile may acknowledge and close; VISITING_DOCTOR has no access.
- **Q12 (lost answers):** a repeated Acknowledge, Close or Call senior from the same user after a lost answer is answered 200 with nothing written (D7), not 409. Confirm.
- **Q13 (time stamps):** acknowledged and closed times are stamped by the database (trigger), not sent by the app; the story's grant list is tightened accordingly.
- **Q14 (log bed):** the audit trail shows the patient's current (or last) bed, not the bed at the time of the entry.
- **Q15 (plan text rules, OQ-33-14):** 1,000 characters, SAH-30 note rules, line breaks allowed; messages "Keep the plan to 1,000 characters." and "Plan cannot contain control or text-direction characters" are contract wording.
- **Q16 (approvals):** `docs/approvals.md` has no `stories` line for SAH-33 (nor `stories`/`contract` for SAH-28, SAH-30); record them before `merge`.

## Review fixes (5 Nov 2026, after `docs/reviews/SAH-33-code.md` and `docs/reviews/SAH-33-security.md`)

Tech lead decisions in those reports; contract in `docs/api/ipd.yaml` (texts marked "SAH-33 review fix"). Each item below amends the decision it names.

### R1 · Change token in `SafetyCounts` (code review 2; amends D8)

`SafetyCounts` gains `changeToken` (required, 22 characters, `^[A-Za-z0-9_-]{22}$`). The client compares `open`, `pastTarget` and `changeToken` with the last values it saw and reloads the board (or `GET /admissions` for the banner) when any differs. It catches what the counts miss: one overdue escalation closes while another becomes overdue (counts equal), or an overdue score rises (the banner would name the wrong patient or an old score).

Exact rule (java-developer, one function in `SafetyRows` so the board, the summary and the admitted list agree): take the escalations counted in `open` (status OPEN or ACK of ADMITTED admissions), in the same transaction snapshot as the counts; sort them by the canonical lower-case id text; build the UTF-8 text of the lines `<id>:<version>` joined by `\n` (empty text for none); SHA-256; the first 16 bytes; base64url without padding (22 characters). It holds no name, score or text; ids are the clinic's own (RLS) and the token is no security control. It changes on raise, acknowledge, close, re-open and score rise (all bump `version` or change the set); not on a senior call (version unchanged, D2) and not when only minutes pass (the `pastTarget` count covers that). The open board reloads every 60 seconds anyway, so a senior-call count on another nurse's screen updates within a minute.

Options: refresh the list on every poll (rejected: names in every poll, ADR-005 D3); token over every board row including closed ones (rejected: closed rows do not drive the tab count or the banner).

### R2 · Sign-off must be later than the escalation (security M1; amends D6)

Rule, in `DischargeService.discharge` under the admission row lock, right after the escalation is read (after `summary-not-signed`, before `bill-changed`), with `clockStart = coalesce(reopened_at, raised_at)` and `signedAt` = the latest sign-off (the one that passed `summary-not-signed`):

| Signed type | Escalation OPEN or ACK | Answer |
|---|---|---|
| `normal` | any sign-off time | 409 `escalation-open`, unchanged text "This patient has an early warning escalation that is not closed. A doctor must record the response first." |
| `death`, `lama`, `referred` | `signedAt <= clockStart` | **409 `escalation-open`**, "This patient has an early warning escalation that started after the doctor signed the summary. A doctor must record the response or sign the summary again." Nothing written. |
| `death`, `lama`, `referred` | `signedAt > clockStart` (strictly) | close-at-discharge as D6 |

Reading of "the exceptions and the normal rule both require the later sign-off": the requirement holds for every type, but for Normal it is subsumed: an escalation not closed always refuses a Normal discharge, and signing again cannot lift that, so Normal keeps its own message (telling the front desk to get a sign-off again would send them on a useless errand).

Both instants are database time (`signed_at default now()`, not insertable by the app; `raised_at` and `reopened_at` from the database, D1/D4). Strictly later: equal instants count as not covered (safe side).

Making "sign again" possible:
- `PUT /admissions/{id}/discharge/signoff`: the double-tap no-op (same type and template, nothing written) no longer applies when the admission has an escalation not closed and the latest sign-off is not later than its `clockStart`; a new sign-off is appended and audited (`IPD_DISCHARGE_SIGNED`). The sign-off already locks the admission row, so it reads the escalation under that lock.
- `DischargeSheet.signoffBeforeEscalation` (boolean, required): true when `openEscalation`, a signature exists, and `signature.signedAt <= clockStart`. The client then shows the checkbox unticked whatever the type (a state SAH-27 already has: a signature that does not apply shows unticked), and for Death, LAMA and Referred the warning is the 409 text above instead of the close-at-discharge warning. Boolean only; no score (FRONT_DESK opens the sheet).

Residual (accepted, stated): `raised_at` is the vitals save's transaction start, taken before it waits for the admission lock. If a vitals save starts, and within the milliseconds of its membership re-read a sign-off starts later and takes the admission lock first, the escalation commits after the sign-off but with `raised_at` earlier than `signed_at`, so the sign-off counts as covering it. The window is the save's pre-lock work (one single-row membership read, R8). Closing it fully needs a per-admission sequence or the escalation version stored on the sign-off (a migration); proposed as a backlog item, not done here.

**Clinical sign-off note (Q2, Q3 amended).** For a doctor to confirm before go-live: (a) Death, LAMA and Referred still close an escalation at discharge with no doctor response, but only when the doctor's latest sign-off is later than the escalation was raised or re-opened; otherwise a doctor must record a response or sign the summary again, so no escalation raised after the doctor's decision is closed without a doctor seeing the patient's state; (b) whether a fresh sign-off is enough for a deteriorating patient (rather than a recorded response) for each of the three types; (c) Q3 stands: should Referred be refused until a response is recorded. Q17 below covers the new texts.

### R3 · Discharge re-reads the membership (security M2; amends D3, D6)

The first step inside the discharge transaction, before the IPD advisory lock and before any write: `TeamMemberPort.findActiveByUserId`; no active membership, or a role other than OWNER or FRONT_DESK, gives 403 `forbidden` and nothing is written (the `requireActiveSigner` pattern of the sign-off, SAH-30 M1 for vitals). The `refund-needs-owner` check uses this membership role, not the token role (the stricter view when they differ, as D9). The lock order (advisory, bed, admission) is unchanged; the membership read takes no lock that could invert it.

### R4 · Call senior: actor-tied replay, "already called" entry, throttle, cap (security L2, L3; code review 11; replaces the D7 call-senior rows)

New append-only table `ipd.ews_senior_call` (Data needed below): one row per accepted entry, `outcome` CALLED (with `call_no`, the count after the call) or ALREADY_CALLED. It makes the replay, the throttle and "who called" exact per user without reading the clinical event log by its text.

After the lock and the patient lookup, for status OPEN and version = sent (ACK, DONE and version ≠ sent unchanged):

| Stored `senior_calls` vs sent `seniorCalls` | Condition | Answer and writes |
|---|---|---|
| lower | (only a forged or other-build request) | 409 `escalation-changed`, the generic score detail; nothing written |
| higher | the caller has a CALLED row with `call_no` > sent | 200 `replayed: true`, nothing written (her own call, answer lost) |
| higher | otherwise, and no row of the caller on this escalation in the last 60 s | one ALREADY_CALLED row; clinical event (esc) "Senior doctor already called · seen by another team member · no new call recorded"; no audit; count and version unchanged; 200 `replayed: true` |
| equal | no row of the caller on this escalation in the last 60 s, stored < 32,767 | the call: +1, one CALLED row (`call_no` = new count), event and audit as before; 200 `replayed: false` |
| equal | stored = 32,767 | 409 `escalation-state`, "This escalation cannot record more senior calls. Phone the senior doctor now."; nothing written |
| higher or equal | the caller has a row (either outcome) on this escalation in the last 60 s | 200 `replayed: true`, nothing written (throttle) |

The throttle is checked before the cap and before the "already called" write. "Last 60 s" is `called_at > clock_timestamp() - interval '60 seconds'` (database clock), read under the admission lock, so two taps of the same nurse serialise.

**No 429** on this endpoint: a throttled tap repeats a call that is already recorded, so answering it like a replay keeps the toast true ("Call recorded …") and the nurse's screen simple. A platform rate limiter (HARD-2) is separate.

**Overflow: cap with 409, no widening.** `senior_calls` stays `smallint`. With one entry per user per 60 s, 32,767 calls on one escalation are not reachable in practice; mapping the cap to a fixed 409 removes the 500 without a column type change (which would rewrite the table and touch the guard trigger). Widening to integer remains possible later as a forward migration if ever needed.

The "already called" text has no name and no role: the event's actor line shows who saw it (names at display time, D13). It is a clinical event, not an audit row, because it records what the nurse saw, not a change.

### R5 · Closed escalations on the board for 24 hours (security L5, OQ-33-13 proposal; amends D8, answers Q9 as proposed)

`GET /safety` returns OPEN and ACK escalations of admitted admissions and DONE ones with `closed_at > asOf - 24 hours` (database clock, same transaction). Older closed ones are not on the board; they stay in the clinical audit trail ("Escalation closed: <action label>") and in the table (never deleted, D15). The closed row keeps its plan text (`response.note`), as before (not loaded on demand: a closed row on a ward board is read with its plan). Counts, the change token and the banner are unaffected (they count open ones only). The existing index `ix_ews_escalation_tenant_admission` serves the read; the extra `closed_at` filter needs no index at these volumes (db-engineer may confirm).

### R6 · Blank text: white space and space separators (code review 3; amends the plan text rule of Q15 and the contract, and the SAH-30 note rule)

Stripping, for every IPD text field where "empty" decides something, removes at both ends every code point that is white space (`Character.isWhitespace`) or a space separator (Unicode category Zs: U+0020, U+00A0, U+1680, U+2000 to U+200A, U+202F, U+205F, U+3000), and line breaks. `String.strip()` alone is not enough (it keeps U+00A0, U+2007, U+202F).
- Plan (`EscalationResponseInput.note`): empty after it gives 400 "Write the plan and when vitals are next due." (AC10: the server refuses an empty plan).
- Nursing note (SAH-30 `VitalsInput.note`): empty after it counts as no note: no `nursing_note` row, `noteAdded` false, no error, the vitals are saved; the replay compare uses the same `noteAdded`.
- The same stripping applies to the other IpdText fields that refuse an empty value (SAH-23 reason for admission, SAH-28 name and complaint): java-developer applies the shared helper there; their contract texts ("after stripping") now mean this rule.

### R7 · Notes (code review 1, 9, 10, 15; security I1, I3, I4)

- **reopened_at (code 9, I3):** `reopened_at := now()` is the vitals save's transaction start, so the re-opened response clock may start a few milliseconds early. Kept: it is the safe side (overdue a little sooner) and it equals the instant `t` of every row of that save (ADR-006 D1).
- **Event time and the cursor (code 10):** `clinical_event.at` is the writing transaction's start. A long transaction can commit an entry older than a cursor already served, so "Show earlier entries" does not show it until the trail is reloaded (the first page is reloaded with the board). Accepted: entries are never lost, only late on a page already passed.
- **Tie-break by UUID (code 15):** the last board order key (`java.util.UUID` order) and `seq` in the trail only make the order deterministic; they carry no meaning. The ipd package Javadoc names SAH-33 (java-developer).
- **Cursor (I1):** the contract says "a cursor that does not decode is refused" (400). It is not signed; a decoded cursor only moves the start of a page inside the caller's clinic and scope.
- **Four NOT VALID checks (code 1, I4):** they stay NOT VALID until the old test rows in `sahaj_test` are cleaned (agents may not clean the database). QA asserts only that the constraints exist. Backlog item before production: clean the test data, then a forward migration runs `validate constraint` on the four checks. They are enforced for every new and updated row already.

### R8 · Single-row membership lookup (security I7; amends D3, D14)

`platform.api.TeamMemberPort.findActiveByUserId(tenantId, userId)` keeps its signature. Its implementation in `platform` (`TeamMemberLookup`) stops loading the whole team and filtering (`teamRepository.findAll(...).stream().filter(...)`); `TeamRepository` gets a single-row query `where tenant_id = :t and user_id = :u and status = 'ACTIVE'`, served by the existing unique constraint `uq_membership_tenant_user (tenant_id, user_id)`. Additive inside platform (a new repository method; the port and its callers in hra and ipd are unchanged); no migration; behaviour identical (at most one membership per user per clinic).

### Data needed (db-engineer; one new forward-only migration, for example `V2026_11_27_1000__ipd_senior_call.sql`)

- **New `ipd.ews_senior_call`** (append-only, tenant table): `id uuid pk default gen_random_uuid()`; `tenant_id uuid not null default platform.current_tenant()` FK `platform.tenant`; `escalation_id uuid not null`, composite FK `(tenant_id, escalation_id)` to `ipd.ews_escalation (tenant_id, id)` (add the unique `(tenant_id, id)` on the escalation if missing); `called_by uuid not null` (user id, IPD actor convention); `outcome text not null` check in (`CALLED`, `ALREADY_CALLED`); `call_no smallint null` with check `(outcome = 'CALLED') = (call_no is not null)` and `call_no between 1 and 32767`; `called_at timestamptz not null default clock_timestamp()`. Optional unique `(tenant_id, escalation_id, call_no)` for CALLED rows (partial), db-engineer's choice. Index `ix_ews_senior_call_tenant_escalation_user (tenant_id, escalation_id, called_by, called_at desc)`. RLS ENABLE and FORCE, select and insert policies on `app.tenant_id`; grant `select` and `insert (escalation_id, called_by, outcome, call_no)` to `sahaj_app` (not `called_at`, not `tenant_id`); no update or delete grant or policy. IPD append-only convention as `ipd.clinical_event` (no updated_* or version columns). Retention: never deleted (in-patient record, D15). Comments on table and columns.
- `ipd.ews_escalation`: no column change (`senior_calls` stays smallint, R4). The four NOT VALID checks: not validated now (R7).
- No change for R1, R2, R3, R5, R6, R8 (discharge sign-off, membership and board read existing columns and indexes).

### Instructions (review fixes)

**java-developer:** R1 `changeToken` in one place (`SafetyRows`), in the board, the summary and `AdmissionList.safety`; R2 the sign-off time rule in `DischargeService.discharge`, the sign-off no-op exception, `DischargeSheet.signoffBeforeEscalation`, the new 409 text; R3 membership re-read first in the discharge transaction, refund check on the membership role; R4 the call-senior table of R4 with `ews_senior_call` writes, the "already called" template in the template class, the cap 409; R5 the 24 h filter in the `ON_BOARD` query; R6 one shared blank/strip helper for the plan, the nursing note and the other IpdText fields; R7 package Javadoc; R8 the single-row repository query in platform. Remove the test-only `canMove`, `Answer.isChanged`, `Answer.isState` (code 8, tech lead). Tests: unit for the token (stable order, empty set, changes on version) and the strip helper (U+00A0, U+2007, U+202F, U+3000, mixed with line breaks); ITs as the security review asks (sign-off T0 then escalation T1 > T0 then FRONT_DESK lama/referred discharge 409; re-sign then 200; deactivated FRONT_DESK membership 403 with nothing written; throttle within and after 60 s; second nurse writes one ALREADY_CALLED and one event, no audit; stored < sent 409; cap 409).

**angular-developer:** compare `changeToken` together with `open` and `pastTarget` in `sameCounts`; Discharge sheet: `signoffBeforeEscalation` shows the checkbox unticked and, for Death, LAMA and Referred, the new warning text; show the 409 texts (new `escalation-open` detail, the cap `escalation-state` detail) as the existing error handling does; Call senior toast unchanged on every 200. (The other code-review items 4, 5, 6, 12 and security L4, I6 are UI fixes without contract change.)

### Questions for the human (review fixes)

- **Q17 (new texts, clinical):** the 409 and sheet warning "This patient has an early warning escalation that started after the doctor signed the summary. A doctor must record the response or sign the summary again.", and the checkbox shown unticked when the sign-off is older than the escalation (R2). Contract wording, not the mockup's.
- **Q18 (new event text):** "Senior doctor already called · seen by another team member · no new call recorded" (R4).
- **Q19 (cap text):** "This escalation cannot record more senior calls. Phone the senior doctor now." (R4).
- **Q20 (board window):** closed escalations shown for 24 hours, then only in the audit trail (R5; answers Q9 and OQ-33-13 as proposed).
- **Q21 (residual):** the millisecond sign-off window of R2 is accepted for phase 1, with a backlog item to close it.
