# ADR-012 · IPD Medicine round on the Safety board: one clinic-wide read through DoseSlots, dose counts and change token in SafetyCounts, Give through the existing MAR read

Story: SAH-35 (IPD part 13: Medicine round on the Safety board, doses due or overdue across patients)
Mockup screen: IPD → Safety tab of Beds and admissions: tile "Doses due or overdue", panel "Medicine round", dose part of the tab number (`ipSafety`, `doseRow`, `sfDoses`, `sfSlot`, `sfLate`, `sfOverdue`, `sfCount`, `sfgive` with `data-from="safety"`, `sfGive`, `sfBack`)
Module: ipd only (existing `platform.api` and `patient.api` ports; no new dependency, table, port, event or library)
Status: PROPOSED (7 Nov 2026), with the SAH-35 contract. Accepted when the human approves `contract`. `docs/approvals.md` has no `stories` or `contract` line for SAH-35; work proceeds under DEC-014 and DEC-015 on the business analyst's proposed answers (OQ-35-1 to OQ-35-9).
Deciders: Ganesh (human), tech-lead; recorded by solution-architect
Related: `docs/requirements/SAH-35.md`, `docs/stories/SAH-35.md`, ADR-007 (D8 board and summary, D9 roles, D12 record access, R1 change token), ADR-011 (D2 `DoseSlots`, D3 roles and `giveLockReason`, D5 order and slot rules, D8 replay, D10 record access on the MAR, D12 volumes), ADR-009 (D8 pharmacist summary, D11 Safety tab for OWNER and PHARMACIST), ADR-005 (D3 counts-only polls, D6 REPEATABLE READ boards), DEC-014, DEC-015, DEC-017, DEC-018, `docs/api/ipd.yaml`

## Context

1. SAH-32 records doses one patient at a time (MAR tab of the Nursing sheet). `DoseSlots` (ADR-011 D2) already decides, for one order, the state of each slot of today on the database clock. ADR-011 D12 deferred the cross-patient "Medicine round" and the dose figures of the Safety tab to this story and wrote `DoseSlots` for it.
2. The mockup puts on the Safety tab: the tile "Doses due or overdue" (`doses.length`, small text "<late> overdue", red when any is late); the panel "Medicine round" with one row per DUE or LATE slot of a verified order (`sfDoses`), Late first then the earlier slot (`ipSafety` sort), and a Give button that opens the existing Give sheet (`sfgive`, `data-from="safety"`; `sfBack` returns to the Safety tab); and the Safety tab number `sfCount` = open escalations + (owner) pending orders + **late** doses.
3. The Safety board (ADR-007 D8) is one read-only REPEATABLE READ answer refreshed every 60 seconds; the counts-only `GET /safety/summary` and `AdmissionList.safety.counts` carry `SafetyCounts` with a change token (ADR-007 R1) so names never travel in a poll.
4. The Give sheet needs per-patient data the board does not hold: UHID, age, sex, allergy (identity bar), route, frequency, high-alert class, the list of second nurses and the not-given reasons. All of it is in `GET /admissions/{id}/mar` (SAH-32), which writes one record-access row (ADR-011 D10).
5. Scale: a 150-bed hospital has about 150 admitted patients, 3 to 5 live orders each, at most 3 slots per order: about 2,250 slots of today to classify per answer, and about 1,500 dose records of today per clinic. `ipd.dose_administration` grows to about 55 million rows a year across all clinics (ADR-011 D13).

## Decision

### D1 · One clinic-wide read, computed by `DoseSlots` (no per-patient call, no new table)

- The Medicine round is computed in the same read-only REPEATABLE READ transaction as the rest of `GET /safety` (ADR-007 D8), against the same `asOf` (`clock_timestamp()`, read once per answer through a repository call). It is never a call per patient: two set-based queries per answer, whatever the number of patients.
  - **Q1 (orders):** every `ipd.medicine_order` with `status = 'ACTIVE'` of an `ipd.admission` with `status = 'ADMITTED'` of the clinic (tenant from the session; RLS), with `admission_id`, `drug_key`, `frequency_key`, `verified_at`, the admission's `patient_id` and the current bed code (join to bed). Drug name and high-alert class from the `ipd.drug` catalogue.
  - **Q2 (today's dose records):** `select order_id, slot_at, outcome from ipd.dose_administration where tenant_id = :t and admission_id = any(:admittedIds) and slot_at >= :dayStart and slot_at < :dayEnd` (`DoseSlots.dayStart/dayEnd` of `asOf`), or the same as a join to the ADMITTED admissions (java-developer's choice; the db-engineer confirms the plan, see Data needed).
  - For each order: `DoseSlots.slots(frequency, Stage.ACTIVE, verifiedAt, recordedOfThatOrder, true, asOf)`; keep the slots whose state is `DUE` or `LATE`. PENDING, REJECTED and STOPPED orders are not read (WAITING is never actionable anyway); discharged admissions are not read; BEFORE_START and LATER slots are dropped by `DoseSlots`. So the list, the tile, the tab number, the MAR tab count and the dose endpoint use one rule and cannot disagree (invariant for tests: at the same `asOf`, `dosesDue + dosesLate` equals the sum of `Mar.doseCount` over the admitted admissions).
- **One pure class** `com.sahaj.ipd.domain.MedicineRound` (no Spring, no I/O; name is the java-developer's to adjust): input the orders of Q1, the records of Q2 and `asOf`; output the sorted, capped rows plus the full `due` and `late` counts plus the dose part of the change token (D3). The board, the summary and `AdmissionList.safety` call the same function, as `SafetyRows.counts` does for escalations (ADR-007 R1).
- **Summary cost:** `GET /safety/summary` and `AdmissionList.safety` run Q1 and Q2 without the patient name lookup (counts only). At 60-second polls per open screen this is a few thousand rows per poll per clinic, read through existing indexes.
- **Patient names** for the rows: one `patient.api.PatientLookupPort.findAll` call per board answer (already made for the escalation rows; one call for both sets). A missing patient record (data fault) shows "Patient" and the row is never dropped (same rule as `EscalationRow.patientName`). Names never come from a join to `patient.patient`.

### D2 · Row order, tie-break and the 200-row cap (OQ-35-4)

- Order: LATE before DUE; inside each group `slotAt` ascending (most late first, as the mockup); tie-break (not in the mockup): bed code ascending (Java `String.compareTo`, code-point order), then drug name ascending, then order id (`java.util.UUID` order; deterministic only, carries no meaning, ADR-007 R7). The order never jumps between refreshes when nothing changed.
- **Cap: at most 200 rows**, taken after sorting, so the most overdue are kept. `MedicineRound.truncated` is true when more than 200 slots qualify; the panel then shows the line "Showing the 200 most overdue doses." The tile and the tab number always use the full counts (`SafetyCounts.dosesDue`, `dosesLate`), never the row count. 200 rows is about 60 KB of JSON per 60-second refresh, and above the realistic due-or-late set of a 150-bed hospital at a dose time (150 × about 2 live doses in the window).
- No paging: the panel is a working list for a round, not a history (the mockup has none). A cursor would make the "most overdue first" set shift under the nurse.

### D3 · `SafetyCounts` gains `dosesDue` and `dosesLate`; the change token covers doses

- `SafetyCounts` (in `SafetyBoard.counts`, `GET /safety/summary`, `AdmissionList.safety.counts`) gains two required integers:
  - `dosesLate`: listed slots in state LATE (the tile's "<N> overdue" and the dose term of the tab number);
  - `dosesDue`: listed slots in state DUE (not counting LATE).
  - Tile figure = `dosesDue + dosesLate`; red (crit) when `dosesLate > 0`; small text "<dosesLate> overdue" ("0 overdue" when none, OQ-35-5, mockup).
- **Tab number** (mockup `sfCount`, OQ-35-3 as proposed): `counts.open + dosesLate`, plus `PharmacistChecksSummary.waiting` for OWNER; DUE doses are not added; hidden at 0. For OWNER, NURSE and DOCTOR alike (the mockup computes it the same for every role that has the tab). PHARMACIST is unchanged (waiting only; it never calls `/safety` or `/safety/summary`).
- **Change token (extends ADR-007 R1):** the sorted lines are the existing escalation lines `<escalation id lower case>:<version>` **plus one line per listed dose slot (all of them, not only the first 200)** `dose:<order id lower case>:<slotAt as epoch seconds>:<LATE|DUE>`; then, unchanged: join with `\n`, UTF-8, SHA-256, first 16 bytes, base64url without padding (22 characters). The `dose:` prefix cannot collide with an escalation line (a UUID never contains `o` or `s`). The token holds no name, drug or text. It changes when a dose is recorded by anyone (the slot leaves), when a slot enters the 60-minute window, when a slot turns from DUE to LATE, and when an order is verified or stopped or a patient is admitted or discharged with a slot in the window; not when only `lateHours` grows (the open board reloads every 60 seconds by itself). It catches the case the counts miss: one dose recorded while another becomes due in the same minute (counts equal).
- Consequence accepted: a client on the Admitted patients tab compares the token and reloads `GET /admissions` more often (a dose recorded anywhere changes it). The reload is bounded by the 60-second poll: at most one extra list read per minute per open screen. A separate dose token was rejected: two tokens for one tab number, and `AdmissionList.safety.counts` must carry the dose counts anyway to show the right tab number on page load.
- Because `AdmissionList.safety.counts` is the same `SafetyCounts`, `GET /admissions` (OWNER, DOCTOR, NURSE only; `safety` is null for every other role) runs Q1 and Q2 too, with the same function, so the summary, the board and the list always give equal counts and tokens at equal snapshots.

### D4 · Give from a row: one MAR read on tap, then the unchanged SAH-32 Give sheet and dose call

- Each row carries only what the row shows and what identifies the dose: `admissionId`, `orderId`, `slotAt`, `state`, `lateHours`, `drugName`, `highAlert`, `patientName`, `bedCode`. No UHID, age, sex, allergy, second-nurse list or reasons in the list.
- **Give (tap):** the client calls `GET /admissions/{admissionId}/mar` once, for that patient only (never per row, never on refresh), with the button busy meanwhile. From the answer it takes the order `orderId` and the slot `slotAt` and opens the existing SAH-32 Give sheet with the MAR's data (identity bar, route, frequency, high-alert step, `secondNurses`, `notGivenReasons`, `lateHours` for the subtitle " · N h late"). The sheet, its checks, the wristband box (no "Scan wristband" button), the name and date of birth tick, the second nurse and PIN, `clientKey`, and `POST /medicine-orders/{id}/doses` are exactly SAH-32 (ADR-011 D4 to D8). Nothing can be recorded from the row itself.
- **The MAR answer re-checks the dose before the sheet opens.** If the slot is no longer givable, the sheet does not open; the client shows the notice and reloads the board:
  - slot state GIVEN, HELD or REFUSED: "This dose is already recorded." (SAH-32 `dose-recorded` text);
  - order not in `mar.orders` (stopped or rejected meanwhile): "This medicine was stopped. Its doses can no longer be recorded." (SAH-32 `order-state` text);
  - `mar.status` DISCHARGED: "This patient is not admitted any more. Doses cannot be recorded." (SAH-32 `not-admitted` text);
  - `mar.canGive` false for IPD_READONLY: "IPD is read-only. Doses cannot be recorded." (DEC-018 text);
  - any other case (slot not found, state not DUE or LATE, `slot.canGive` false): "Reload the page and try again." (existing contract wording).
  - MAR read fails (403, 404, 5xx, network): the toast "We could not load the medicines. Try again." (the SAH-32 MAR text, DEC-018 build notes); the board stays.
  No new problem type or text is introduced.
- **After the dose answer** (201 or 200 replayed): the SAH-32 toast (AC7 or AC8), the sheet closes, the user stays on the Safety tab (mockup `sfBack` for `from = 'safety'`), and the client reloads `GET /safety` (not the `DoseRecorded.mar` it received); focus goes to the Give button of the next row, or to the panel heading when the list is empty. On 409 `dose-recorded`, `order-state`, `order-not-verified`, `not-admitted`, `dose-not-due`, `dose-before-start`, `ipd-readonly` and on 400 field `slotAt`: the detail as toast, the sheet closes, the board reloads. Other failures keep the sheet open with what was typed, PIN cleared (SAH-32 AC13).
- **Options rejected:** (a) put identity, allergy, nurses and reasons in every row: puts UHID, age, sex and allergy of every patient with a due dose into a list refreshed every 60 seconds, and would then need a record-access row per patient per refresh (DEC-017 precedent for the pharmacist list) or a weaker privacy argument; (b) prefetch the MAR per row: 100 to 200 calls per refresh and an access row each; (c) a new small read `GET /medicine-orders/{id}/give`: duplicates the MAR read and its rules, one more endpoint to secure and test, and the Give sheet component already consumes `Mar`.

### D5 · Record access (OQ-35-6, AC23)

- **The list writes no `patient.record_access` row** (neither `GET /safety`, nor the summary, nor `GET /admissions`). Same reasons as ADR-007 D12: a ward list refreshed every 60 seconds would flood the owner's access log. The list shows name, bed and medicine name only, which is less than the pharmacist list (UHID, age, sex, allergy text, for which DEC-017 does write rows).
- **Opening Give writes one row**, because it is the MAR read of D4 (ADR-011 D10; DEC-018 item 5). A dose recorded with 201 writes none (its audit records it); a replay writes one (unchanged SAH-32).
- Human to confirm (privacy): the medicine names of every admitted patient with a dose due are visible to OWNER, DOCTOR and NURSE on the board without an access row.

### D6 · Roles, Read-only, module Off

| Operation | Token role and membership role (both) | Read-only IPD | Off |
|---|---|---|---|
| `GET /safety` (now with `medicineRound`), `GET /safety/summary`, `GET /admissions` `safety` | OWNER, DOCTOR, NURSE (unchanged, ADR-007 D9) | allowed | 403 `module-off` |
| Give button on a row (`MedicineRound.canGive`) | NURSE, OWNER | not offered | |
| `GET /admissions/{id}/mar` (opened by Give) | OWNER, NURSE, DOCTOR (unchanged) | allowed | 403 `module-off` |
| `POST /medicine-orders/{id}/doses` | NURSE, OWNER (unchanged; others 403 "Only nurses and the clinic owner can give medicines.") | 409 `ipd-readonly` (unchanged; OQ-35-8) | 403 `module-off` |
| every other role (PHARMACIST, FRONT_DESK, BILLING, VISITING_DOCTOR, others) | 403 `forbidden` on `/safety` and `/safety/summary`; `AdmissionList.safety` null | | |

- `MedicineRound.canGive` = token role and membership role both NURSE or OWNER, and IPD Trial or On. `giveLockReason` when false, first that applies: `ROLE` (DOCTOR: rows without a Give button, no line; OQ-35-1) or `IPD_READONLY` (rows without a Give button, and the line "IPD is read-only. Doses cannot be recorded." under the panel subtitle; DEC-018 text; OQ-35-8). The flag is a convenience: the dose endpoint checks role, membership and state again.
- The panel is part of the board for OWNER, DOCTOR and NURSE; PHARMACIST keeps its Pharmacist-only Safety tab (ADR-009 D11) and never sees the round.
- Tenant: everything under RLS with the tenant from the session; another clinic's order or admission is invisible in the list and answers 404 on the MAR and dose calls.

### D7 · Board layout and refresh (no new polling)

- Tiles, in order: "Escalations open"; OWNER "Pharmacist checks"; **"Doses due or overdue"**; NURSE "Your patients". The mockup's OWNER tile "Emergency access" is not shown (SAH-34 blocked).
- Panels, in order: Early warning escalations; OWNER Pharmacist check; **Medicine round** (pill icon), subtitle "Doses due in the next hour and doses not given on time. Clock: <asOf as 11:05 AM>."; Clinical audit trail. (Emergency access to review, SAH-34, would sit after the round.)
- Refresh: unchanged from SAH-33: the board loads when the tab opens, every 60 seconds while open, and after every action (now including a dose recorded from a row); the summary poll compares `open`, `pastTarget`, `dosesDue`, `dosesLate` and `changeToken`. A failed refresh keeps the last answer with "Could not refresh. Showing data from <time>." The board's existing "Loading" and failure states cover the round, because it is one answer (D8).
- No notification, sound or message to anyone; no text says anyone was alerted (phase 1).

### D8 · The round is part of `GET /safety`, not its own endpoint

The story's AC8 asks for a separate failure state for this panel ("We could not load the medicine round. Try again."). The round is in the board answer instead, so one transaction and one `asOf` produce the rows, the tile, the counts and the token (the story's own technical note: "one code path ... so the figures cannot disagree"), and the 60-second refresh stays one request. A failure of the round is a failure of the board read (the same database), so a separate panel error state would almost never show alone. The text "We could not load the medicine round. Try again." is therefore not used (deviation from the story, not from the mockup). Option rejected: `GET /safety/medicine-round` (two requests per refresh; the tile from one answer and the rows from another could differ by a slot crossing 60 minutes between the two calls).

### D9 · Audit, events, logging

- Reads write no audit row and no clinical event (as the board). A dose given from the round writes exactly the SAH-32 event and audit (ADR-011 D9); the event does not say where the sheet was opened.
- Application logs: ids, `tenantId`, `requestId` only. Never the rows, patient names, bed codes with names, or drug names with a patient. Problem details never carry a patient name.

### D10 · Module boundaries and phase 1

- ipd only. Uses `platform.api` (`TeamMemberPort.findActiveByUserId`, entitlement) and `patient.api.PatientLookupPort.findAll` (names; already used by the board). The Give path uses the existing MAR read (`PatientClinicalPort`, `PatientAccessPort`). No new port, event, dependency or library; `ModularityTests` stay green. No cross-module table access.
- Phase 1 held: no notifications, no missed-dose record or midnight job, no PRN or stat doses, no ward scoping, no filters, search, print, export or reports.

### D11 · Midnight and the missed dose (OQ-35-2, not solved here)

`DoseSlots` lists today's slots only (IST date of `asOf`). A dose late at 11:59 PM leaves the list at 00:00 IST and cannot be recorded after (ADR-011 D2, D5 step 8). The earliest dose time is 6 AM (`q8`), so midnight never cuts a DUE window; it only drops LATE doses of the previous day, silently. This is the clinical gap of OQ-35-2 and a go-live condition with OQ-30-6. A later "missed dose" story can add it without changing this contract (the round would gain a state, the counts a field).

### D12 · Retention

No new data. Dose records, orders and admissions keep their existing rules (never deleted, ADR-011 D13). Nothing computed here is stored.

## Options considered (summary)

- **Compute on read with `DoseSlots` (chosen)** vs a stored "due slots" table or a scheduled job: a job and a table are not in phase 1 and go stale on stop, verify and discharge.
- **Round inside `GET /safety` (chosen, D8)** vs its own endpoint.
- **Give through the existing MAR read on tap (chosen, D4)** vs identity data in every row, per-row prefetch, or a new give-sheet read.
- **Dose lines in the one change token (chosen, D3)** vs a second token.
- **200-row cap, most overdue kept, true counts on the tile (chosen, D2)** vs no cap (mockup) or keyset paging.

## Consequences

**Positive.** A late dose of any admitted patient is visible on the Safety tab within 60 seconds without opening a chart; one rule (`DoseSlots`) decides every dose figure; two queries per answer whatever the ward size; no new table, endpoint, problem type or dependency; the Give path keeps every SAH-32 bedside check and its record-access row; polls still carry no names.

**Negative / accepted.**
- `GET /admissions` (OWNER, DOCTOR, NURSE) and the summary poll run two more set queries.
- The Admitted patients tab reloads its list more often when doses change (bounded by the 60-second poll).
- Tapping Give costs one MAR request (a short wait) and one record-access row.
- 60-second refresh with no alert for a late dose (OQ-35-7, DEC-015).
- The nurse sees every admitted patient's doses until wards exist (OQ-33-2).
- A late dose disappears at midnight (OQ-35-2).
- Medicine names of admitted patients are on the board without an access row (D5; human to confirm).

## Data needed (for db-engineer)

No new table, column, constraint, grant, trigger or policy. **No migration is expected.** One verification task, and one conditional index:

1. **Verify the two reads with `explain (analyze, buffers)`** on realistic data in `sahaj_test` (for example one clinic with 150 ADMITTED and 20,000 DISCHARGED admissions, 3,000 orders of which 600 ACTIVE on admitted patients, 2 million dose rows over a year, plus other clinics' rows), as `sahaj_app` with `app.tenant_id` set:
   - Q1 orders: `ipd.admission` ADMITTED of the tenant via `ix_admission_tenant_status_admitted_at (tenant_id, status, admitted_at)`, joined to `ipd.medicine_order` via `ix_medicine_order_tenant_admission_ordered (tenant_id, admission_id, ordered_at, id)` with the filter `status = 'ACTIVE'`.
   - Q2 dose records of today: `ix_dose_administration_tenant_admission_slot (tenant_id, admission_id, slot_at)` with `admission_id = any(:ids)` (or the join to ADMITTED admissions) and the `slot_at` range of the IST day.
   - Expected: index scans only, no sequential scan of `ipd.dose_administration` or `ipd.medicine_order`, well under 50 ms. Record the plans in `docs/database/SAH-35.md`.
2. **Conditional index, only if the Q1 plan shows the ACTIVE filter reading many STOPPED rows** (long stays with many stopped orders): `ix_medicine_order_tenant_active (tenant_id, admission_id) where status = 'ACTIVE'` in a forward-only migration. Not needed for Q2: the existing admission index already leads with `(tenant_id, admission_id, slot_at)`; a `(tenant_id, slot_at)` index is **not** wanted (it would serve a clinic-wide day scan that this design does not run and adds write cost on a 55-million-row-a-year table).
3. Tenant scope unchanged (FORCE RLS on all three tables). Retention unchanged (never deleted). Partition plan of `ipd.dose_administration` by `slot_at` (ADR-011 D13) is unaffected: Q2 always carries a `slot_at` range, so it prunes to one partition later.

## Instructions

**java-developer:**
1. `ipd.domain.MedicineRound` (pure): input orders (orderId, admissionId, drug name, high-alert class, frequency, verifiedAt, patientId, bedCode), today's records by order and slot, `asOf`; uses `DoseSlots.slots(..., Stage.ACTIVE, ..., admitted = true, asOf)`; output the DUE and LATE slots sorted per D2, capped at 200 with `truncated`, the full `due` and `late` counts and the dose token lines (D3). Unit tests: 60 and 61 minutes after; 60 and 61 minutes ahead; 60 min 59 s either side; verified mid-day (BEFORE_START dropped); recorded slot dropped (each outcome); two admissions; equal slot times sorted by bed then drug then id; 201 qualifying slots (200 rows, truncated, counts 201); empty set.
2. Extend `SafetyRows.counts`/`changeToken` (or wrap them) so one function builds `SafetyCounts` with `dosesDue`, `dosesLate` and the combined token, used by the board, the summary and `AdmissionList.safety`. Token unit tests: stable order; with no dose in the window the token equals the SAH-33 token (same lines); a recorded dose changes it; DUE to LATE changes it; a dose recorded while another enters the window (counts equal) changes it; `lateHours` growth alone does not.
3. `SafetyService.board`: Q1 and Q2 in the existing REPEATABLE READ read-only transaction, patient names in the single `findAll` already made, `medicineRound` with `canGive` and `giveLockReason` from the token role and the re-read membership role (D6). Summary and admission list: Q1 and Q2 without names.
4. No new endpoint; no change to the MAR or dose endpoints. Never log rows or names.

**angular-developer:** tile "Doses due or overdue" (order of D7; red when `dosesLate > 0`; "<N> overdue"); panel "Medicine round" with rows (`MedicineRoundRow`), empty text, "Showing the 200 most overdue doses." when `truncated`, the Read-only line for `giveLockReason = IPD_READONLY`, no Give button when `canGive` is false; tab number `open + dosesLate` (+ `waiting` for OWNER) from every `SafetyCounts` seen; summary comparison adds `dosesDue`, `dosesLate`; Give per D4 (MAR read on tap, pre-checks with the existing texts, reuse of the SAH-32 Give sheet, reload the board after every answer, stay on the Safety tab, focus to the next row's Give or the panel heading); accessible name "Give <drugName> to <patientName>", target at least 44 px; status pill "<lateHours> h late" (crit) or "Due now" (warn), never colour alone; slot time as "8 AM", "8:30 AM".

**qa-engineer (for the test plan):** clock hook at the 60/61-minute edges across two patients; a dose recorded on one device disappears from the other's round on the next refresh and its token changes; two nurses giving the same row (one 201, one 409 `dose-recorded`, board reloaded); order stopped between the board and the tap (MAR pre-check notice); discharge between the board and the tap; Read-only: rows, no Give, POST 409 `ipd-readonly`; DOCTOR rows without Give, POST 403; PHARMACIST, FRONT_DESK, BILLING, VISITING_DOCTOR 403 on `/safety` and `/safety/summary`; IPD Off 403 `module-off`; another clinic's doses never in the list; no record-access row from `/safety`, `/safety/summary`, `/admissions`; one row per Give tap; no patient or drug names in logs; 201 qualifying slots.

## Questions for the human

- **Q1 (OQ-35-1, roles, needs human):** DOCTOR reads the round with no Give button (the mockup shows the Safety tab to nurse and owner only; SAH-33 already opened it to DOCTOR).
- **Q2 (OQ-35-2, clinical, needs human and a doctor; go-live condition with OQ-30-6):** a late dose leaves the list silently at midnight IST and cannot be recorded the next day.
- **Q3 (OQ-35-3, needs human):** the tab number adds LATE doses only; the tile shows DUE plus LATE (mockup).
- **Q4 (OQ-35-8, needs human, with DEC-018 item 4):** in a Read-only clinic the round is readable but doses cannot be recorded.
- **Q5 (OQ-35-6, privacy):** no record-access row for the list (which shows medicine names of every patient with a dose due); one row per Give tap through the MAR read.
- **Q6 (OQ-35-4, OQ-35-9, texts):** the 200-row cap with "Showing the 200 most overdue doses.", the tie-break bed then medicine, the Read-only line, the accessible name "Give <medicine> to <patient name>". The story's "We could not load the medicine round. Try again." is not used (D8): the board's existing loading and failure states apply.
- **Q7 (D4):** the Give sheet opens after one short MAR request instead of at once (mockup), and does not open when the dose was recorded, stopped or the patient discharged in the meantime (existing SAH-32 texts shown).
- **Q8 (approvals):** no `stories` or `contract` line for SAH-35 in `docs/approvals.md`.
