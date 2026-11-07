# ADR-011 · IPD medicine administration (MAR): computed dose slots, one append-only dose record per order and slot, bedside checks on the server, second-nurse PIN

Story: SAH-32 (IPD part 11: Medicines (MAR) and giving a dose with the bedside checks)
Mockup screens: Nursing sheet → tab "Medicines (MAR)" (`sfNsTabs`, `sfMar`, `sfSlot`, `sfSlotChip`, `sfMedHead`, `SF_FREQ`, `sfLate`, `sfDoses`) and the Give sheet (`sfGive`, `sfgiveok`, `sfnogive`, `sfscan`, `sfIdBar`)
Module: ipd (patient data through `patient.api`, team, PIN and audit through `platform.api`)
Status: PROPOSED (6 Nov 2026), with the SAH-32 contract. Accepted when the human approves `contract`. `docs/approvals.md` has no `stories` or `contract` line for SAH-32 (nor for SAH-30 and SAH-31); work proceeds under DEC-014. Revised 6 Nov 2026 after the code review (R2, R3, R8, R16) and the security review (S1, S2, S3, S5, S6, S7, I6): wording and risks only, no behaviour change.
Deciders: Ganesh (human), tech-lead; recorded by solution-architect
Related: ADR-005 (clientKey), ADR-006 (D1 clinical_event, D2 partitioning, D4 lock, D6 record access, D7 templates), ADR-007 (D3 lock rule), ADR-009 (D1 order lifecycle, D3 frequencies, D5 PIN outside the transaction, D10 lock), ADR-010 (staff PIN; D5 lockout, D9 port, D11 texts, D13 limits, Q7), DEC-013, DEC-014, DEC-015, DEC-017, `docs/stories/SAH-32.md`, `docs/decisions/IPD-open-questions.md` (OQ-32-1 to OQ-32-5, OQ-30-7, OQ-31-3), `docs/api/ipd.yaml`, `docs/api/platform.yaml`

## Context

1. SAH-31 made medicine orders with the lifecycle Pharmacist check (PENDING) → Active (ACTIVE) | Rejected | Stopped, and fixed dose times per frequency (ADR-009 D3: `od` 8 AM; `bd` 8 AM, 8 PM; `tds` 8 AM, 2 PM, 8 PM; `q8` 6 AM, 2 PM, 10 PM, IST). Its toasts "It is now on the MAR." and "Remaining doses are removed from the MAR." describe this story (ADR-009 Q9).
2. The mockup's MAR shows today only. A slot is Given / Not given / Refused when recorded, "Waiting for pharmacist" while the order is pending, else by the clock: more than 60 minutes after = late, within 60 minutes either side = due, more than 60 minutes ahead = later today (`sfSlot`).
3. The mockup's bedside checks run in this order (`sfgiveok`): box empty → wristband ≠ MRN (deny entry) → name and date of birth not confirmed → high-alert: no second nurse or PIN not 4 digits → second nurse is the signed-in person. The PIN is checked for format only. The **Scan wristband** button (`sfscan`) copies the patient's own UHID into the box, so the check passes by itself (OQ-32-1).
4. The staff PIN exists (ADR-010). Its port checks only the **signed-in** person's own PIN; ADR-010 Q7 left the second-person check to this story.
5. Dosing is the highest-volume clinical write in IPD: about 100 hospitals × 150 occupied beds × 3 to 5 live orders × 1 to 3 slots ≈ 10 dose records per patient-day, so about 150,000 rows a day (55 million a year), plus one clinical event and one audit row each.
6. The IPD lock rule: writes on an admission take exactly one row lock, the bare admission row `for no key update` (ADR-006 D4, ADR-009 D10); discharge and transfer take it `for update`.

## Decision

### D1 · The dose record: `ipd.dose_administration`, append-only, one row per order and slot

- One row per recorded dose: order, admission, slot time (`slot_at`, the instant of the dose time), outcome `GIVEN` / `HELD` / `REFUSED`, reason key (null for GIVEN), the recorder (`recorded_by`, the signed-in user id, IPD actor convention), the second nurse (`second_check_by`, user id, GIVEN of a high-alert medicine only), the identity scan (normalised, at most 40 characters, GIVEN only), the identity statement (`identity_confirmed`, true for GIVEN), a snapshot of the high-alert flag, the `client_key`, and `recorded_at` = **`clock_timestamp()`** (the database clock at the insert, not the transaction start: ADR-009 R8 showed that `now()` gives misleading order times).
- **Unique `(tenant_id, order_id, slot_at)`**: a dose can be recorded once, by anyone, ever. This is the rule of AC4 ("dose-recorded") and its database backstop.
- **Append-only** (AC9, OQ-32-5): select and column insert only; triggers refuse update, delete and truncate. No undo, no correction, no late edit. A wrong record is followed by a nursing note and the doctor. No "struck through" state is designed (that would need a clinical rule: OQ-32-5, Q11).
- Outcome rule (AC8): reason `PATIENT_REFUSED` → REFUSED; any other reason → HELD; the API sends `GIVEN` or `NOT_GIVEN` and the server derives the stored outcome. Database checks make the pairs impossible to break (data list).
- **Not-given reasons** (mockup select order): `PATIENT_REFUSED` "Patient refused", `HELD_DOCTOR` "Held on doctor's advice", `NIL_BY_MOUTH` "Nil by mouth", `NOT_AVAILABLE` "Medicine not available", `AWAY_FROM_WARD` "Patient away from the ward". Java enum and DB check.
- Name: the story's `ipd.dose_administration` is kept (the brief's "dose_record" is the same thing).

### D2 · Slots are computed, never stored: `com.sahaj.ipd.domain.DoseSlots`

Pure Java (no Spring, no I/O), used by the MAR, the tab count (`NursingSheet.doseCount`), the dose endpoint's checks and later the Safety board's "Medicine round". Input: frequency key, order status, `verifiedAt`, today's dose records of the order, the admission status, the database clock `asOf`. Output: the slots of today with state and `lateHours`.

- **"Today"** is the IST calendar date of `asOf` (`Asia/Kolkata`, no daylight saving). Slot instants = that date at each dose time of the frequency, converted to UTC. `asOf` is always `clock_timestamp()` read through a repository call, never the application or device clock (ADR-006 D1).
- **Minutes** are whole minutes rounded down: `after = floor((asOf − slotAt) / 60 s)`, `ahead = floor((slotAt − asOf) / 60 s)`.
- **State, first that applies:**

| # | Condition | State | Chip (mockup `sfSlotChip`) | Clickable |
|---|---|---|---|---|
| 1 | a dose record exists for (order, slot) | `GIVEN` / `HELD` / `REFUSED` | check icon, time, "<time recorded> · <recorder's last name>", hint "Given by <name>" + ", checked by <second nurse>"; or "Not given · <reason>" / "Refused · <reason>" | no |
| 2 | admission not ADMITTED | `NOT_RECORDED` | grey, time, "Not recorded" (contract wording) | no |
| 3 | order PENDING | `WAITING` | grey, time, "Waiting for pharmacist" | no |
| 4 | order verified today (IST) and `slotAt < verifiedAt − 60 min` | `BEFORE_START` | grey, time, "Before the order started" (contract wording) | no |
| 5 | `after > 60` | `LATE` | red button, "<lateHours> h late · Give" | yes |
| 6 | `ahead ≤ 60` (and not LATE: from 60 minutes before to 60 minutes after the slot time) | `DUE` | amber button, "Due now · Give" | yes |
| 7 | `ahead > 60` | `LATER` | grey, "Later today" | no |

- `lateHours = after div 60` (at least 1 for LATE). The mockup's "N min late" branch can never occur (late starts after 60 minutes); it is not built.
- Edges (unit tests): exactly 60 whole minutes after → DUE; 61 → LATE "1 h late"; 120 → "2 h late"; exactly 60 minutes ahead → DUE; 61 ahead → LATER; 60 min 59 s after → DUE (rounded down).
- **By design (code review R16):** because minutes are whole minutes rounded down, a slot **60 min 59 s ahead is DUE** (`ahead` = 60, so not "more than 60 minutes"), and can be given (D5 step 10 uses the same rounding); it becomes LATER only from 61 whole minutes ahead. The story's "more than 60 minutes" is read in whole minutes on both sides of the slot, so the MAR, the tab count and the dose endpoint never disagree by a few seconds. Recorded in DEC-018.
- **Mid-day verification (AC10, OQ-32-2):** rule 4. Example: `bd` verified at 10:30 AM → 8 AM is BEFORE_START, 8 PM counts. Verified at 8:45 AM → 8 AM counts (8:00 ≥ 7:45), is DUE until 9:00 and LATE from 9:01. On every later day all slots count. Safer reading: a dose that was never asked for is never shown as late, and the nurse is never pushed to give a dose the doctor's order did not yet cover. BEFORE_START slots can not be recorded (D5).
- **Only PENDING and ACTIVE orders** are listed (Rejected and Stopped are not; AC2, AC10). A Stopped order disappears with its remaining slots; its recorded doses stay in the table, in the clinical event log and in the EMR "last dose" line (D11).
- **Count** (tab badge, AC1): DUE + LATE slots of ACTIVE orders, 0 for a DISCHARGED admission. Not a stored counter.
- **Midnight:** a 10 PM dose not recorded by midnight leaves the MAR at 00:00 IST and can no longer be recorded (slot not of today: 400). "Missed dose" records are not designed (OQ-32-2 second half; Safety board story; Q4).

### D3 · Who may give, who reads (OQ-32-3)

- **Give or record not given: NURSE and OWNER** (mockup `sfCanGive`: nurse or owner). DOCTOR reads only (AC1, AC4), as do the roles below. VISITING_DOCTOR, FRONT_DESK, BILLING, PHARMACIST, COUNSELLOR: 403 for the MAR read and the dose write.
- **The 403 text:** the mockup says "Only nurses and doctors can give medicines." because its demo owner is the doctor. Sent to a refused DOCTOR it contradicts itself. The contract uses **"Only nurses and the clinic owner can give medicines."** (deviation table row 3, Q3). The button is disabled for DOCTOR, so the text is seen only by a direct API call.
- **Read the MAR: OWNER, NURSE, DOCTOR** (token role and the role re-read from the active membership, both), in Trial, On and Read-only state, for ADMITTED and DISCHARGED admissions (AC11). Any other role: 403 `forbidden`, detail "You cannot open the medicine round." (contract wording, as built, code review R3; seen only by a direct API call).
- `canGive` (one flag for the sheet) = token and membership role both NURSE or OWNER, admission ADMITTED, IPD Trial or On. `giveLockReason` when false: `ROLE`, `IPD_READONLY`, `NOT_ADMITTED` (first that applies). ROLE shows no notice (mockup: buttons disabled); IPD_READONLY and NOT_ADMITTED show one line (contract wording, as the EMR lines of ADR-009 B12): "IPD is read-only. Doses cannot be recorded." and "This patient is not admitted any more.".

### D4 · The bedside checks on the server: `com.sahaj.ipd.application.MedicineAdministration`

All checks run on the server; the client's ticks are a record of the nurse's statement only (AC6). Identity rules live in a pure helper `WristbandCheck` (domain).

**Wristband (OQ-32-1, decided here in the safer direction for the human to confirm, Q1):**
- The **Scan wristband button is not built.** The box accepts what a keyboard-type barcode scanner sends (it types the code and usually Enter or Tab) or what the nurse types. Camera scanning and wristband printing are not in phase 1. The UHID is the mockup's "MRN"; the box keeps the mockup's accessible name "Wristband MRN" and placeholder.
- **Normalisation** (both sides): NFKC; remove every white-space and every control or format character (Unicode Cc, Cf: so a scanner's trailing CR, LF or Tab and text-direction marks vanish); upper case with `Locale.ROOT`. **Match** when the normalised box equals the normalised UHID (UHIDs are `^[A-Z]{2,6}-[0-9]{5,}$`). A box longer than 200 UTF-16 units is not normalised and counts as a mismatch.
- Empty after normalisation → 400 "Scan the wristband first.".
- Mismatch → **409 `wristband-mismatch`**, detail "Wristband does not match this patient. Stop. Check the patient before giving anything." (the detail carries no name: problem details may be logged; the client builds the mockup's toast "Wristband does not match <patient name>. Stop. Check the patient before giving anything." and the line under the box "Wrong patient. This band is not <patient name>'s." from the MAR it holds). The clinical event (kind `deny`) **"Stopped at the wristband check: scanned <scan>, expected <UHID> (<drug name>)"** and the audit IPD_DOSE_WRISTBAND_MISMATCH are written and committed although the answer is 409 (D6 step 3).
- **`<scan>` in the deny entry:** the normalised box text **only when it matches the UHID-shape pattern `^[A-Z]{2,6}-[0-9]{5,9}$`** (2 to 6 letters, a hyphen, 5 to 9 digits: **8 to 16 characters, 16 at most**; a band of another patient); otherwise the fixed words "text that is not a UHID". **The server's pattern in `WristbandCheck` is the authority** (as built after code review R2 and security review S6). It is bounded to what the UHID generator produces: `patient.next_uhid()` returns `'PT-' || lpad(seq, 5, '0')`, so a UHID is at most 22 characters (`PT-` plus up to 19 digits). 9 digits at most means a 10-digit mobile or a 14-digit ABHA-like run can never pass into the permanent event text. A scan that is a UHID with more than 9 digits is shown in the deny entry only as "text that is not a UHID"; the match check itself is not affected (it compares the whole normalised box with any UHID). The scan stored on a GIVEN dose equals the UHID and so never exceeds 22 characters (the `identity_scan` column allows 40; the earlier `{5,34}` allowed 41 and would have failed the insert with a 500). Reason: the box is free text; a typed name or note must never reach the event log (ADR-006 D7; CLAUDE.md). The story says "<text sent, first 40 characters>"; this is narrower (deviation row 5, Q2). Another patient's UHID in this patient's log is accepted: it is what the investigation needs, the mockup writes it, and it is not a name, mobile or ABHA number.
- Stored on the dose (GIVEN only): the normalised box text (it equals the UHID: at most 22 characters in practice, the column limit is 40).
- The box is limited to 200 characters on the client (`maxlength="200"`) and in the contract (`DoseInput.wristband` `maxLength: 200`, security review I6). As built, a longer value sent directly is not a 400 format error: it counts as a mismatch (409, deny entry "text that is not a UHID").

**Order of the checks for `GIVEN`** (AC6, exact texts in the contract), after the order and slot checks of D5:
1. wristband empty → 400 field `wristband` "Scan the wristband first.";
2. wristband ≠ UHID → 409 `wristband-mismatch` (+ deny entry and audit, committed);
3. `identityConfirmed` not `true` → 400 field `identityConfirmed` "Confirm the second identifier: name and date of birth.";
4. high-alert medicine: `secondNurseId` missing, or `secondNursePin` not exactly 4 ASCII digits → 400 (fields `secondNurseId` / `secondNursePin`) "High-alert medicine: a second nurse must check it and enter a 4-digit PIN.";
5. high-alert: `secondNurseId` is the caller's own membership → 400 field `secondNurseId` "The second check must be a different nurse.";
6. high-alert: `secondNurseId` is not an ACTIVE NURSE membership of the clinic → 400 field `secondNurseId` "Choose a nurse from the list." (contract wording; only a stale screen or a crafted request reaches it);
7. high-alert: the second nurse's PIN through the colleague check of D7 → 403 `wrong-pin`, 403 `pin-locked`, 409 `pin-not-set`, 429 `rate-limit`, 503 `pin-unavailable`.

Each step stops the request (one message at a time, the mockup's toast order). For a medicine that is not high-alert, `secondNurseId` and `secondNursePin` are ignored: not checked, not stored, the PIN never sent to the port.

**`NOT_GIVEN`:** `reasonKey` missing or not one of the five → 400 field `reasonKey` "Choose why the dose is not given."; wristband, statement and second nurse are not required and are ignored if sent (AC8).

### D5 · Order and slot rules (AC4, AC10, AC11)

Checked for both outcomes, before the bedside checks, in this order:
1. token role NURSE or OWNER → else 403 `forbidden` "Only nurses and the clinic owner can give medicines." (D3); IPD Off 403 `module-off`; IPD Read-only 409 `ipd-readonly` (AC11: recording a dose is refused in a Read-only clinic, as the story says; Q8 asks whether it should stay allowed like Stop);
2. body format (400, every format error together; contract `DoseInput`): `clientKey`, `slotAt`, `outcome`;
3. membership re-read (NURSE or OWNER, else 403 with the same text);
4. order missing or another clinic's 404 (RLS); its admission's patient missing 404;
5. client-key replay (D8);
6. admission not ADMITTED → 409 `not-admitted` "This patient is not admitted any more. Doses cannot be recorded.";
7. order PENDING → 409 `order-not-verified` "This order is not checked by the pharmacist yet."; STOPPED → 409 `order-state` "This medicine was stopped. Its doses can no longer be recorded."; REJECTED → 409 `order-state` "This order was rejected by the pharmacist. It cannot be given." (contract wording for the two `order-state` texts);
8. `slotAt` is not one of the order's dose times on today's IST date (database clock) → 400 field `slotAt` "This is not a dose time of this medicine today. Reload the page and try again." (contract wording; covers a screen left open past midnight);
9. slot BEFORE_START (D2 rule 4) → 409 `dose-before-start` "This dose time is before the pharmacist checked the order." (contract wording);
10. slot more than 60 whole minutes ahead (rounded down as D2: 60 min 59 s ahead is still due) → 409 `dose-not-due` "This dose is not due yet. It can be given from 1 hour before its time." (contract wording);
11. a record exists for (order, slot) → 409 `dose-recorded` "This dose is already recorded." (any recorder, any outcome).

### D6 · Transactions, PIN outside, lock order

The ADR-009 B1 shape (verify), so the PIN is checked before the IPD transaction and never with an admission lock held:

1. **No transaction:** D5 steps 1 and 2.
2. **Prepare transaction** (`MedicineAdministration.prepare`, READ COMMITTED, read-write only for the deny entry): D5 steps 3 to 11, then (GIVEN) D4 steps 1 to 6. No lock is taken. On a wristband mismatch it writes the deny event and the audit, **commits, and returns a result** `WristbandMismatch`; the non-transactional facade then throws the 409. (Throwing inside the transaction would roll the entry back.) Every other refusal writes nothing.
3. **No transaction (GIVEN of a high-alert medicine only):** `staffPinPort.checkColleague(...)` (D7) in its own platform transaction that commits, so a wrong try is counted although this request fails.
4. **Locked transaction** (`MedicineAdministration.recordLocked`, `@Transactional(isolation = READ_COMMITTED)`, declared explicitly):
   - token role, module, Read-only, membership re-read again (the PIN check takes about 0.25 s; anything can change meanwhile);
   - the order by id (plain read) → its admission id; **the bare admission row** `select id, status from ipd.admission where tenant_id = :t and id = :id for no key update`; then, in new statements, the order again and the admission, bed and ward joined (ADR-006 D4 step 3);
   - `asOf` = `clock_timestamp()` read **after** the lock, so the due rule uses the time the dose is really recorded, not the time the request arrived;
   - D5 steps 4 to 11 again; the patient again through `patient.api.PatientClinicalPort` and the wristband compared again (a UHID changed by a merge in the meantime: the deny entry and audit are written instead of the dose, the transaction commits, the facade answers 409 `wristband-mismatch`); the second nurse's membership again (still ACTIVE NURSE, not the caller; else 400 "Choose a nurse from the list.");
   - insert the dose, the clinical event, the audit (D9); build the MAR with the same `asOf` rule (a fresh `clock_timestamp()` after the insert); 201.
- **Lock order against the existing IPD order** (clinic advisory → beds → admission): the dose path holds exactly one row lock, the admission row, and waits for nothing while holding it. Its inserts take key-share locks on the admission row it holds and on the `ipd.medicine_order` row (composite FK); a Stop updates that order row only after taking the same admission row lock, so it is already queued behind (or ahead of) the dose. Vitals saves, escalation actions and order actions take the same lock in the same mode, so on one admission they run one after another; different admissions never wait on each other. Discharge and transfer take the row `for update`, which conflicts, so a discharge and a dose never interleave. No platform row lock is ever held together with the admission lock (step 3 commits first; `checkColleague` refuses to run inside a transaction). No cycle is possible.
- **Races (QA, real-database ITs):** two nurses give the same slot at once (one 201, the other 409 `dose-recorded`); double tap with the same clientKey (one 201, one 200 `replayed`); give vs Stop (Stop first → 409 `order-state`; give first → Stop succeeds, the dose stays); give vs discharge (discharge first → 409 `not-admitted`); give vs verify of the same order (verify first → the give of a PENDING order was already refused in prepare; no race to the record); give at 60 vs 61 minutes before (clock hook).
- **Database backstops** (a hit is a code bug: 500, full rollback, never swallowed): the unique `(tenant_id, order_id, slot_at)` and `(tenant_id, client_key)`; the insert guard trigger (order ACTIVE, admission ADMITTED, high-alert snapshot matches the drug, high-alert GIVEN has a second nurse); the pair checks.

### D7 · The second-nurse PIN: a colleague check in `platform.api` (OQ-31-3, ADR-010 Q7)

**PROPOSED platform change** (small, additive, same table and lockout; java-developer platform part):

```java
// platform.api.StaffPinPort, new method
ColleaguePinCheck checkColleague(UUID tenantId, UUID callerUserId, UUID colleagueMembershipId,
                                 String requiredRole, String pin);
// platform.api
public record ColleaguePinCheck(PinCheck pin, UUID colleagueUserId) {
    public UUID requireOk() { ... }   // returns the colleague's user id, or throws StaffPinException (subject COLLEAGUE)
}
```

- **Who may be named:** an ACTIVE membership of the same clinic (RLS and tenant id), with role **NURSE** (the mockup's "Checked by … Choose a nurse" lists nurses; OWNER is not offered, Q6), whose user is **not the caller**. The MAR offers exactly these (`Mar.secondNurses`: membership id and display name, the caller excluded). ipd checks them in prepare (D4 steps 5, 6) so the PIN is never tried for an ineligible person; the port checks them again and answers a new outcome `NOT_ELIGIBLE` (ipd → 400 "Choose a nurse from the list.").
- **The PIN checked is the colleague's own staff PIN** (ADR-010 D2 hash; the same row, the same constant-time comparison). The giver types nothing of their own: the giver is the signed-in session (as the mockup: "Given by <me>").
- **Lockout counts on the colleague's row** (ADR-010 D5): 5 wrong tries in a row lock that PIN for 15 minutes, and the third lock in 24 hours switches it off. The lock protects the PIN, whoever types the guesses. The lock audit (`PLATFORM_PIN_LOCKED`, `PLATFORM_PIN_CLEARED_AFTER_LOCKS`) gains `checkedByMembershipId` when the try came from a colleague check.
- **Guess protection from a colleague's session** (the new risk: nurse A, signed in, tries nurse B's PIN):
  - **Per-caller limit on failed colleague checks** (new bucket in `InMemoryStaffPinLimits`, key `tenantId:callerUserId`, any colleague): **5 per 15 minutes and 10 per 24 hours**; at the limit 429 `rate-limit` before any hash. Only WRONG counts (as ADR-010 D13 after R1; right PINs never consume allowance). With 10 a day, one person can cause at most two locks a day on a colleague and can never switch a colleague's PIN off alone.
  - The colleague's own failed-check bucket (ADR-010 D13, 10 per 5 minutes, key the colleague's user) also counts these tries. **Amended by ADR-014 D7 (SAH-38, 7 Nov 2026, PROPOSED):** it no longer does; colleague tries are limited by the caller's bucket, the durable pair cap (ADR-014 D3, 429 `colleague-pin-limit`) and the lockout, and a colleague-caused lock never counts toward the switch-off (ADR-014 D6).
  - **Every wrong colleague try is audited** (new platform action `PLATFORM_PIN_COLLEAGUE_CHECK_FAILED`: `membershipId` of the colleague, `checkedByMembershipId`, `triesLeft`; ids and numbers only), so the owner's change history shows repeated tries against one person. ADR-010 D10 does not audit single wrong tries of one's own PIN; this is the exception, because here the guesser is someone else (Q5).
  - Residual risk, accepted for the local merge only (Q5): 10 guesses a day against about 9,600 allowed PINs; a success only lets A name B as the checker of a dose A records under A's own name, and every try is audited. In-memory limits are per instance (HARD-2). The security review found two residual risks that are **go-live blockers** with HARD-2: quiet guessing over time (Q14) and colleague-caused lockout or switch-off (Q15).
- **Texts** (platform-owned, `StaffPinException` with subject COLLEAGUE; no names, so a detail can be logged):

| Slug | Status | Detail (contract wording) |
|---|---|---|
| `wrong-pin` | 403 | "That PIN is not right." (no tries-left count, security review S4: the count would tell a guesser to stop just before a lock; the try still counts on the colleague's row and `triesLeft` stays in the audit; the own-PIN answer of ADR-010 D11 keeps the count) |
| `pin-locked` | 403 | "The second nurse's PIN is locked after 5 wrong tries. Choose another nurse, or try again at <h:mm AM/PM>." (`retryAfterSeconds`) |
| `pin-not-set` | 409 | "The second nurse has not set a PIN yet. Choose another nurse." (every not-set reason; also the try that causes the third lock) |
| `rate-limit` | 429 | "Too many PIN tries in a short time. Try again in a few minutes." |
| `pin-unavailable` | 503 | "PIN checks are not available right now. Try again later." |

  The client shows them under "Their PIN" and **never** treats `pin-not-set` from this endpoint as the signed-in user's own state (no `GET /me/pin`, no "Set my PIN": that is the Verify sheet's rule only).
- Call order: ipd passes `TenantContext` tenant and caller, the `secondNurseId` from the body (validated in prepare), the role `NURSE`; outside any transaction (`IllegalStateException` inside one). The PIN is never logged, audited, stored or echoed; `ColleaguePinCheck.toString()` holds outcome and numbers only.
- `platform.yaml`: no new path; the Problem description lists the colleague texts.

### D8 · Double tap, lost answer and double record (AC4, AC13)

- **`clientKey`** (uuid, required; the SAH-28/30/31 pattern): the client makes it when the Give sheet opens and keeps it while the same request is retried; a new sheet makes a new key.
- **Replay** (prepare step 5 and again under the lock): a dose with this `clientKey` exists for this order and the same `slotAt`, the same outcome sent (`GIVEN`, or `NOT_GIVEN` with the same `reasonKey`) and, for GIVEN of a high-alert medicine, the same `secondNurseId` → **200** with `replayed: true`, the stored dose and the MAR now; nothing written except one record-access row (D10). No PIN is checked for a replay (it writes nothing and returns only what the caller may read). Any compared value different → 409 `client-key-reused` "This dose was already recorded with different values. Reload the page."; key of another order → 400 field `clientKey` "Reload the page and try again.".
- **Another key, same slot** (a second nurse, a second device, a retry after the sheet was reopened) → 409 `dose-recorded`. So a dose can never be stored twice, and a lost answer shows the first result instead of an error when the same sheet retries.
- Client: Given and Record not given are disabled while a request runs; the slot shows Given only after a 201 or 200; a failure keeps the sheet open with every entry except the PIN (cleared); no offline queue.

### D9 · Clinical events and audit (no clinical free text, no patient name)

Clinical events (`ipd.clinical_event`, existing kinds; actor = the caller's user id; `at` = transaction time). Times in the text use the mockup format "8 AM", "8:30 AM".

| Kind | Text (exact template) | When |
|---|---|---|
| give | "Gave <drug name> (<slot time> dose) · wristband scanned" + " · second check by <second nurse display name>" for a high-alert medicine | 201 GIVEN |
| give | "Did not give <drug name> (<slot time> dose) · <reason label>" | 201 NOT_GIVEN |
| deny | "Stopped at the wristband check: scanned <scan>, expected <UHID> (<drug name>)" (`<scan>` per D4) | wristband mismatch |

- **Amended by ADR-014 D11 (SAH-38, 7 Nov 2026, PROPOSED; ADR-011 Q16 option a):** from that release the give text ends "· identity checked against UHID" instead of "· wristband scanned", and the deny text reads "Stopped at the identity check: entered <scan>, expected <UHID> (<drug name>)". Rows already written keep their text (append-only). ADR-014 D3 and D7 also amend D7 above: the colleague's own failed-check bucket is no longer used; a durable pair cap answers 429 `colleague-pin-limit`; a store failure answers 503 `pin-check-failed`.
- The second nurse's display name is written into the text at the time of the dose (mockup), unlike actors, which are resolved at display time; the dose row keeps the id as the source of truth. Staff names are not patient data. Longest text about 245 characters (limit 300). Replays write nothing.
- **Audit** (`AuditLog.record`, entity `IPD_DOSE`, id = dose id; for a mismatch entity `IPD_MEDICINE_ORDER`, id = order id):

| Event | Fields |
|---|---|
| IPD_DOSE_GIVEN | doseId, orderId, admissionId, drugKey, slotAt, outcome GIVEN, highAlert, secondCheckUserId (or null) |
| IPD_DOSE_NOT_GIVEN | doseId, orderId, admissionId, drugKey, slotAt, outcome HELD or REFUSED, reasonKey |
| IPD_DOSE_WRISTBAND_MISMATCH (added, Q2) | orderId, admissionId, drugKey, slotAt |

  `highAlert` and `secondCheckUserId` (a flag and an id) are added to the story's list. Never in an audit row, event, problem detail or log: the scanned text (except the UHID-shaped `<scan>` in the deny event), the PIN, the patient's name, allergy or notes. Log lines: ids, tenantId, requestId.

### D10 · Record access when the MAR opens (OQ-30-7)

- **`GET /admissions/{id}/mar` writes one `patient.record_access` row** (`PatientAccessPort.recordOpened`) after the 404 checks pass, in a READ COMMITTED read-write transaction. Same rule as `GET /admissions/{id}/nursing` (ADR-006 D6, already in the contract and DEC-013 item 13): the MAR shows the name, UHID, age, sex, allergy and every medicine. A replayed dose (200, no audit) writes one row too; a 201 writes none (its audit records it); a 4xx writes none. **No clinical event "view"**: the mockup logs only the EMR opening.
- Story AC9 says "not written to the record-access log in this story (same open question as OQ-30-7)". The contract takes the safer reading that is already built for the Nursing sheet (deviation from the story, not the mockup; Q7). Each switch to the MAR tab is a new GET and a new row.

### D11 · Where the doctor sees "Not given" and "Refused" (OQ-32-4)

- **`MedicineOrderRow.lastDose`** (EMR list, every order including Stopped): the latest dose record of the order (highest `slot_at`) or null. EMR line under the order's small line (contract wording, deviation row 8): "Last dose 8 AM: Given · 8:04 AM · Pawar", "Last dose 8 AM: Refused · Patient refused", "Last dose 8 AM: Not given · Nil by mouth"; when the slot is not today "Last dose 24 Oct, 8 PM: …". Not-given lines in warn colour. The EMR is the "chart" of the mockup's toast "The doctor sees this on the chart."
- Read with one query per EMR sheet (latest row per order of the admission, backed by the unique index `(tenant_id, order_id, slot_at)`). No extra access row (the EMR GET already writes one).
- No notification to the doctor (phase 1).

### D12 · Paging, history and the frontend data

- **MAR: not paged.** PENDING and ACTIVE orders of the admission (chart order: `ordered_at`, id), at most 3 slots each, today's dose records only (index `(tenant_id, admission_id, slot_at)`). Bounded by the live orders.
- **Dose history across days: no endpoint in this story.** The mockup shows no history screen; the records are in the table, in the clinical event log (Safety board, SAH-33, already paged) and in the EMR "last dose". A history view is a later story (Q10); the indexes support a keyset read `(slot_at desc, id)`.
- **`NursingSheet.doseCount`** (new field, required): the tab badge on the default Vitals tab without a second request. The story's technical note says "NursingSheet gains no field"; the badge of AC1 needs the number on the Vitals tab, so the field is added (additive).
- The cross-patient "Medicine round" and the Safety tab counters are not built (story scope note); `DoseSlots` is written so that story can reuse it.

### D13 · Volumes, partitioning and retention

- ~150,000 dose rows a day across 100 hospitals (Context 5): about 4.5 million a month, 55 million a year. The postgresql.md trigger (plan partitioning before 50 million rows) is reached in the first year of full use. The table **starts unpartitioned** (story) and is designed so the ADR-006 D2 procedure applies by range on **`slot_at`**, **monthly** partitions (4.5 million rows each), started near 30 million rows:
  - No foreign key points at `ipd.dose_administration`.
  - The business unique key `(tenant_id, order_id, slot_at)` already contains the partition key, so it stays a real unique index on the partitioned table. `(tenant_id, client_key)` becomes local per partition; the guard stays the admission row lock and the business key.
  - Primary key `(id)` now; `(id, slot_at)` on the partitioned table (create the unique index concurrently on the old table first, ADR-006 D2 step 2).
- **Wristband mismatches (code review R8, security review S5):** each mismatch writes one permanent `deny` clinical event and one `IPD_DOSE_WRISTBAND_MISMATCH` audit row, committed, with no limit per user and no collapse of repeats (a double tap writes two). Planning figure: 1 to 5 % of GIVEN attempts in normal use (a wrong band, a mistyped UHID, a scanner that appends to old text), so about **1,500 to 7,500 extra clinical events and the same number of extra audit rows a day** at full use. Worst case is unbounded per user until HARD-2 adds a limit (a stuck scanner or a script). The estimates below include the upper planning figure. Optional later change for the human (not built): collapse repeats of the same order, slot and scan within a few minutes into one entry.
- **Also grows:** `ipd.clinical_event` (+1 per dose, +1 per mismatch: about 150,000 to 160,000 a day; with the EMR views of ADR-009 D12 it now reaches the 30-million mark within months, so its D2 procedure must be planned at go-live); `platform.audit_event` (+1 per dose, +1 per mismatch, +1 per wrong colleague PIN try: about 155,000 to 160,000 a day from this story alone, about 57 to 58 million a year); `patient.record_access` (+1 per MAR opening and per tab switch, est. 10 to 20 per patient-day).
- **`platform.audit_event` partition plan (Q9, go-live item):** the table has no partition plan and is the largest grower after this story. Proposed for the db-engineer to design (not in this story's migration): the ADR-006 D2 procedure, range partitions on **`at`**, **monthly**, started before 30 million rows; no foreign key points at it; primary key becomes `(id, at)`; the existing index `(tenant_id, at desc)` becomes local per partition; append-only triggers and grants repeated on each partition. If HARD-3 (Q14) counts colleague tries from audit rows, its `(actor_user_id, action, at)` index is part of the same plan.
- **Retention:** part of the in-patient record (ADR-006 D9). Never deleted; no delete grant; triggers refuse update, delete and truncate. Period is the human's (ADR-006 Q10).

### D14 · Module boundaries and scope

- **ipd** owns the table, `DoseSlots`, `WristbandCheck`, `MedicineAdministration`. Uses `patient.api.PatientClinicalPort` (UHID, name, age, sex, allergy; follows merges, ADR-009 B9), `PatientAccessPort`; `platform.api.TeamMemberPort` (`findActiveByUserId`, `find`, `findActiveMembers` filtered to NURSE for the list), `ActorDirectoryPort` (names), `StaffPinPort.checkColleague` (new), `AuditLog`, entitlement. Nothing from `opd` or `billing`. No new module dependency; `ModularityTests` stay green.
- No new library, service, table outside `ipd`, event or menu key. No notification, stock deduction, barcode on the medicine pack, camera scanning, document or analytics (phase 1; story out of scope).

### D15 · What SAH-31's toasts need

- "<drug name> verified by <name>. It is now on the MAR." is true once SAH-32 is released: an ACTIVE order is listed with its slots (D2).
- "<drug name> stopped. Remaining doses are removed from the MAR." is true by D2 (Stopped orders are not listed and their slots cannot be recorded, D5 step 7).
- So **SAH-31 must not be released without SAH-32** (ADR-009 Q9 answered by this design). `MedicineOrderStatus` contract text "Until SAH-32 an ACTIVE order waits for the MAR" is replaced.

## Options considered

- **Slots computed (chosen)** vs **slot rows generated at verification or by a nightly job**: generated rows need a scheduler (not in phase 1), go stale on stop and give a write per slot even when nothing is recorded; computed slots follow the order status at once.
- **One dose per (order, slot), append-only (chosen)** vs **editable record with a correction flow**: OQ-32-5; a correction is a clinical-legal rule for the human.
- **Prepare / PIN / locked transaction (chosen, ADR-009 B1)** vs **PIN inside the IPD transaction with REQUIRES_NEW** (second connection, platform lock inside the admission lock).
- **Mismatch as 409 with the deny entry committed by a returned result (chosen)** vs **200 with an outcome** (ADR-009 D4 style): the mismatch carries no clinical text, so a problem detail is fine, and the story names 409 `wristband-mismatch`.
- **Colleague PIN (chosen)** vs **second nurse signs in on the same device** (needs a second session; no screen) vs **format-only PIN** (AC6: not acceptable for release) vs **giver's own PIN plus a name** (proves nothing about the second person).
- **Wristband button built as in the mockup** (rejected: the check passes by itself) vs **box only (chosen)**.
- **Store the UHID-shaped scan only in the deny entry (chosen)** vs **any 40 characters (story)**.
- **Partition now** vs **later by the D2 procedure (chosen, story)**.

## Consequences

**Positive.** One rule class for slot states everywhere; a dose can never be recorded twice; the identity check cannot pass by itself; the second nurse proves presence with their own PIN, and a colleague cannot switch another's PIN off alone from a clean state (see Q15 for the limits of this); no clinical free text in events, audit, problem details or logs; one row lock; no new library or module dependency; partitioning stays cheap.

**Negative / accepted.**
- A nurse can lock a colleague's PIN for 15 minutes by wrong guesses (audited; at most twice a day per guesser).
- A high-alert dose cannot be given when no other active nurse with a PIN exists (single-nurse night shift; OWNER is not offered as checker, Q6).
- Recording is refused in a Read-only clinic (story; Q8) and after midnight for yesterday's slots (Q4).
- Each MAR opening adds an access row; each dose adds a dose row, a clinical event and an audit row.
- Wristbands must be produced outside the product in phase 1 (UHID handwritten or printed by the clinic's label printer; Q1).

## Data needed (for db-engineer; one forward-only migration, for example `V2026_11_29_1000__ipd_dose_administration.sql`)

1. **`ipd.medicine_order`**: add unique `(tenant_id, id, admission_id)` (`uq_medicine_order_tenant_id_admission`) for the composite FK below. No other change.
2. **`ipd.dose_administration`** (tenant table, ENABLE and FORCE RLS, policy `tenant_id = platform.current_tenant()` for select and insert; no update or delete policy):
   - `id uuid primary key default gen_random_uuid()`;
   - `tenant_id uuid not null default platform.current_tenant()` FK `platform.tenant`;
   - `admission_id uuid not null`, FK `(tenant_id, admission_id) → ipd.admission (tenant_id, id)`;
   - `order_id uuid not null`, FK `(tenant_id, order_id, admission_id) → ipd.medicine_order (tenant_id, id, admission_id)` (the dose belongs to the order's admission);
   - `client_key uuid not null`, unique `(tenant_id, client_key)`;
   - `slot_at timestamptz not null`, check `slot_at = date_trunc('minute', slot_at)`; unique `(tenant_id, order_id, slot_at)` (`uq_dose_administration_order_slot`);
   - `outcome text not null` check in (`GIVEN`, `HELD`, `REFUSED`);
   - `reason_key text null` check in (`PATIENT_REFUSED`, `HELD_DOCTOR`, `NIL_BY_MOUTH`, `NOT_AVAILABLE`, `AWAY_FROM_WARD`); checks `(outcome = 'GIVEN') = (reason_key is null)` and `(outcome = 'REFUSED') = (reason_key is not distinct from 'PATIENT_REFUSED')`;
   - `recorded_by uuid not null` (user id, IPD actor convention);
   - `second_check_by uuid null` (user id); checks `second_check_by is null or outcome = 'GIVEN'`, `second_check_by is null or second_check_by <> recorded_by`;
   - `high_alert boolean not null` (snapshot of `ipd.drug.high_alert is not null` for the order's drug); check `not (high_alert and outcome = 'GIVEN') or second_check_by is not null`;
   - `identity_scan text null` check `char_length(identity_scan) between 1 and 40`; `identity_confirmed boolean null`; check `(outcome = 'GIVEN') = (identity_scan is not null)` and `(outcome = 'GIVEN') = (identity_confirmed is true)` and `identity_confirmed is null or outcome = 'GIVEN'`;
   - `recorded_at timestamptz not null default clock_timestamp()`.
   - Indexes: the two uniques; `(tenant_id, admission_id, slot_at)` (MAR of today, tab count).
   - Grants to `sahaj_app`: `select`; `insert (admission_id, order_id, client_key, slot_at, outcome, reason_key, recorded_by, second_check_by, high_alert, identity_scan, identity_confirmed)` (so `recorded_at` is always the database's). No update, no delete.
   - Triggers: refuse update, delete and truncate (`platform.deny_audit_mutation()`); a BEFORE INSERT guard (SECURITY INVOKER, fixed `search_path`, revoked from public and `sahaj_app`, SQLSTATE 23514 with "dose" in the message): the order is ACTIVE, the admission is ADMITTED, `high_alert` equals `(drug.high_alert is not null)` of the order's drug.
   - Comments: append-only, actor convention, slots computed (ADR-011 D2), partition procedure by `slot_at` monthly (D13), never deleted.
   - Volume ~150,000 rows a day at full use; start unpartitioned; runbook item at go-live (D13).
3. **`ipd.clinical_event`**: no change (kinds `give`, `deny` exist; 300 characters is enough). Restate the volume with SAH-32 (D13).
4. **`platform.audit_event`**: no schema change; restate its growth and propose a partition plan (Q9).
5. **`platform.staff_pin`**: no schema change (colleague tries use the same row and counters).
6. Tenant scope: tenant table with FORCE RLS. Retention: never deleted (D13).

## Instructions

**java-developer (ipd):** `DoseSlots` (D2, every edge of the table), `WristbandCheck` (normalisation, UHID shape, the `<scan>` rule), `NotGivenReason` enum; `MedicineAdministration` (prepare / PIN / locked, D4 to D8; facade throws the mismatch 409 after commit; 23505 and 23514 never swallowed); `MarService` read (D10 access row; `asOf` from `clock_timestamp()`); `NursingSheet.doseCount`; `MedicineOrderRow.lastDose`; event templates and audit of D9.
**java-developer (platform):** `StaffPinPort.checkColleague`, `ColleaguePinCheck`, outcome `NOT_ELIGIBLE`, the per-caller bucket (5 per 15 minutes, 10 per 24 hours, WRONG only), `PLATFORM_PIN_COLLEAGUE_CHECK_FAILED`, `checkedByMembershipId` on the lock audits, colleague texts in `StaffPinException`; ITs: cross-tenant colleague id, inactive and non-NURSE colleague, the caller as colleague, limits, lock caused by a colleague, no PIN in logs.
**angular-developer:** see the frontend list in the SAH-32 architect handoff.

## Questions for the human

- **Q1 (safety, OQ-32-1):** the Scan wristband button is not built; the box is typed or filled by a keyboard-type scanner. How are wristbands made in phase 1 (handwritten UHID, the clinic's own label printer)? Printing is not in phase 1. The typed-UHID weakness and the "wristband scanned" event text are Q16.
- **Q2 (privacy, addition):** the deny entry shows the scanned text only when it looks like a UHID, otherwise "text that is not a UHID" (story: any first 40 characters); a mismatch also writes the audit IPD_DOSE_WRISTBAND_MISMATCH (not in the story).
- **Q3 (roles and text, OQ-32-3):** NURSE and OWNER give; DOCTOR reads only. The 403 text is "Only nurses and the clinic owner can give medicines." instead of the mockup's "Only nurses and doctors can give medicines.".
- **Q4 (clinical, OQ-32-2):** slots before verification minus 60 minutes are "Before the order started" and cannot be recorded; a dose not recorded by midnight can no longer be recorded and is not marked missed. Is that acceptable until the Safety board story?
- **Q5 (security, ADR-010 Q7):** the colleague PIN design: lockout on the colleague's row, 5 failed colleague checks per 15 minutes and 10 per 24 hours per caller, every wrong colleague try audited; residual risk accepted.
- **Q6 (roles):** only active NURSE members can be the second checker (not OWNER or DOCTOR). A single-nurse shift cannot give a high-alert dose.
- **Q7 (privacy, OQ-30-7):** opening the MAR writes a record-access row (story AC9 says not), as the Nursing sheet already does.
- **Q8 (Read-only):** recording a dose is refused in a Read-only clinic (story AC11), unlike Stop. Should recording what was given stay allowed while patients are still admitted?
- **Q9 (operations):** `platform.audit_event` grows by about 155,000 to 160,000 rows a day with doses, wristband mismatches and wrong colleague PIN tries, and has no partition plan. Proposed plan in D13 (monthly range partitions on `at`), for the db-engineer to design as a go-live item.
- **Q10 (scope):** no dose history screen (past days); the doctor sees the last dose per order in the EMR (OQ-32-4) and the clinical audit trail.
- **Q11 (clinical-legal, OQ-32-5):** no correction of a wrong dose record in the product; a nursing note and the doctor.
- **Q12 (texts):** new texts not in the mockup (deviation table of the SAH-32 handoff): "Before the order started", "Not recorded", the two lock lines, the EMR "Last dose …" line, every 409/400 detail marked "contract wording", the colleague PIN texts.
- **Q13 (approvals):** no `stories` or `contract` line for SAH-32 in `docs/approvals.md`.

## Risks accepted for the local merge, open for go-live (security review, 6 Nov 2026)

Each item below **needs the human**. None is fixed in SAH-32 and none changes the built behaviour; the security review passed the merge with these recorded. Q14, Q15 and Q16 block go-live.

- **Q14 (security S1, go-live blocker with HARD-2): a colleague's PIN can be guessed quietly over time.** The per-caller cap on failed colleague checks (5 per 15 minutes, 10 per 24 hours, D7) is held in memory, so a restart or a second instance resets it; there is no durable cap per (caller, colleague) pair. The colleague's `failed_count` resets on their next right PIN, so up to 4 wrong tries between the colleague's own uses never lock and nobody is told. DDMM birthdays are allowed PINs. A caller who finds a colleague's PIN can record high-alert doses with that colleague as "second check". **Proposed follow-up story "HARD-3 colleague PIN guessing"**, options for the human (they combine):
  - (a) a durable weekly cap per (caller, colleague) pair on failed colleague checks: a small platform table, or a count of `PLATFORM_PIN_COLLEAGUE_CHECK_FAILED` audit rows with an `(actor_user_id, action, at)` index (D13 partition plan);
  - (b) a notice to the colleague at their next PIN use: "N wrong tries by colleagues since your last use" (new text and screen state, so a mockup question);
  - (c) colleague-caused locks do not count toward the switch-off (see Q15);
  - (d) a ward procedure for when no second PIN can be used (paper second check, owner informed).
  The Q5 residual risk is accepted for go-live only after (a).
- **Q15 (security S2, go-live blocker with HARD-2): colleague-caused lockout and switch-off.** A hostile colleague can lock another nurse's PIN twice a day; with a restart, a second instance or a second caller, a third lock switches the PIN off (409 `pin-not-set`), and the colleague's own failed-check bucket fills, so anyone naming them gets 429. "Never switched off alone" holds only from a clean state (DEC-018 build notes). Neither the colleague nor the owner is told. Options, in HARD-3 with Q14: notify the colleague or owner on a lock or switch-off; colleague-caused locks do not count toward the switch-off (Q14 c); a ward procedure when no second PIN works (Q14 d); a shared (database) limiter instead of the in-memory one (HARD-2).
- **Q16 (security S3, go-live blocker; with Q1): the identity can be typed from the UHID shown on screen, while the permanent event says "wristband scanned".** The box accepts typing and the identity bar shows the UHID, so a nurse can pass the check without seeing the wristband; the `give` event (D9) still says "· wristband scanned", a permanent claim that may be untrue. Options:
  - (a) **recommended:** change the event text to "· identity checked against UHID", so the record never claims a scan that may not have happened (a template change in D9 and the story's wording; no schema change);
  - (b) store an entry-mode hint (scanner-like typing speed measured by the client; weak evidence, client-supplied);
  - (c) hide the UHID in the identity bar until the box is filled (mockup change).
- **Q17 (security S7, backlog): idle screen lock for ward devices.** A dose of a medicine that is not high-alert needs no re-authentication, so an unattended signed-in ward screen can record doses under the signed-in nurse's name. Option: an idle lock (re-enter own PIN or sign in again after N minutes without use) as a platform story. Not a go-live blocker in the review; the human decides its priority.
