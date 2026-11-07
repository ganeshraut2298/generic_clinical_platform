# SAH-35 · Medicine round on the Safety board (doses due or overdue across patients) (IPD, part 13)
Mockup screen: IPD → Safety (the "Safety" tab of Beds and admissions) — the tile "Doses due or overdue", the panel "Medicine round" and the dose part of the tab number (`ipSafety`, `doseRow`, `sfDoses`, `sfSlot`, `sfLate`, `sfCount`, `sfgive` with `data-from="safety"`)
Module: ipd

## 0. IPD inventory against the mockup (7 Nov 2026, business analyst)
Checked in `frontend/src/app/features/ipd`, `features/config/ipd`, `backend/.../ipd` and `docs/stories`. BUILT = works as the mockup shows; PARTLY = part built; NOT BUILT = nothing in code.

| # | Mockup screen / sheet / setup section | State | What is missing, or the story |
|---|---|---|---|
| 1 | IPD → Bed board (wards, beds, status, tiles, ready/block) | BUILT | 26-ward rail and Emergency dock layouts are not built (not in any story) |
| 2 | IPD page: 4 tiles, Admit patient, "Add wards and beds" panel | BUILT | Trial and Read-only notices on this page, "Load/Clear example data": NOT BUILT |
| 3 | Admit sheet, admission sheet (running bill, deposits, extra charges) | BUILT | |
| 4 | Move to another bed / Transfer sheet; Discharge sheet and final bill | BUILT | OQ-26-5 reason field; OQ-27-4 billing link open |
| 5 | Packages: setup panel and package lines on the bill (SAH-29) | NOT BUILT | Story SAH-29 exists; BLOCKED on OQ-29-1/2 (who creates and attaches packages: not in the mockup) |
| 6 | IPD → Admitted, table view with Open and red safety banner | BUILT | The mockup's table view is this plain table; it is the only view that exists |
| 7 | IPD → Admitted, uniform patient cards 2/3/4 per row, Cards/Table toggle, search, ward filter, stage chips, My patients, scope notice, "Customize card" | NOT BUILT | Needs the patient card story (no story file yet) |
| 8 | Card flags: Not seen N min, Doctor reviewing, Vitals due, EWS, Allergy, Dose overdue | NOT BUILT | Deposit low and Going home today exist as pills on the table only |
| 9 | Card actions: Nursing, EMR, Transfer, Discharge, Deposit | PARTLY | Buttons in the admission sheet instead of the card (OQ-30-1) |
| 10 | Card actions: Case paper sheet and Print case paper; Update stage picker | NOT BUILT | Print is "soon" in the mockup itself; stage needs the card story |
| 11 | Clinic setup → Patient card (4 tabs, templates, preview, draft/publish, versions, locked safety fields) | NOT BUILT | Not even a placeholder entry in the setup menu |
| 12 | Clinic setup → Roles and access (patient scope per role, data matrix, action matrix) | NOT BUILT | Roles are fixed in code; needs ward-scoping design (OQ-33-2, OQ-30-3) |
| 13 | Clinic setup → Wards/rooms/beds, Bed categories, Admission and deposits, Emergency and triage, Discharge | BUILT | Packages panel (row 5) missing |
| 14 | IPD → Emergency (triage queue, bays, targets, admit, treat and release, refer) | BUILT | "Treat and release" bill of 600 rupees not built (OQ-28-3) |
| 15 | Safety tab: banner, tiles Escalations/Pharmacist/Your patients, Early warning escalations, Response sheet | BUILT | SAH-33 |
| 16 | Safety tab: Pharmacist check panel, Verify and Reject sheets | BUILT | SAH-31 |
| 17 | Safety tab: tile "Doses due or overdue", panel "Medicine round", dose part of the tab number | NOT BUILT | **This story, SAH-35** |
| 18 | Safety tab: Clinical audit trail | BUILT | Latest entries; "show earlier" is an extension |
| 19 | Emergency access (break-glass): button, sheet, 30-minute grant, tile, "Emergency access to review", Mark reviewed, card flag | NOT BUILT | **BLOCKED (SAH-34)**: needs ward scoping and its own security design |
| 20 | Nursing sheet: Vitals and notes, EWS; Medicines (MAR) tab and Give sheet | BUILT | SAH-30, SAH-32 (box is typed, no simulated scan: by design) |
| 21 | EMR sheet: identity bar, medicine orders with checks, Stop | PARTLY | Stage in the strip and the "Results" block not built |
| 22 | Doctor rounds / progress notes | NOT IN MOCKUP | The mockup says the full EMR "opens in the Doctor App"; nothing to build |

## 1. Stories
Chosen as the next story because it is the smallest slice that still closes a patient-safety gap. SAH-32 records doses; nobody can see late doses across the ward. All data, the slot rules (`DoseSlots`, SAH-32) and the Give sheet already exist, so it is a read, one panel, one tile and a number. The mockup shows it fully, no new setup, no new role, no ward scoping and no human design decision is needed first. Candidates not chosen: the patient card and Roles and access (large, need ward scoping and several decisions); Packages (OQ-29-1/2 blocking); Case paper (print is deferred in the mockup); break-glass (blocked, see row 19).

### SAH-35-1 · As a nurse, I want one list of the medicines due now and the doses not given on time for all admitted patients, so that no dose is missed because I did not open that patient's chart
Priority: Must

### SAH-35-2 · As a nurse or the owner, I want a tile and a number on the Safety tab that show how many doses are due or overdue, so that I notice a late dose from any screen of Beds and admissions
Priority: Must

### SAH-35-3 · As a nurse, I want to tap Give on a row and record the dose with the same bedside checks as on the Medicines tab, so that speed never skips the wristband or the second nurse
Priority: Must

### SAH-35-4 · As the owner or a doctor, I want to read the same list, and have everyone else kept out, so that clinical information stays with the right roles and clinic
Priority: Should

## Acceptance criteria

### SAH-35-1 · The panel
1. Given IPD is Trial, On or Read-only, at least one patient is Admitted and the user is OWNER, DOCTOR or NURSE, then the Safety board has a panel **Medicine round** (pill icon) below "Pharmacist check" (OWNER) and above "Clinical audit trail", subtitle "Doses due in the next hour and doses not given on time. Clock: 11:05 AM." where the time is the server clock in IST ("asOf"), not the device clock. (Emergency access, SAH-34, would sit after it later; not shown now.)
2. Given today's dose slots, then a slot is listed when its order is Active (checked by the pharmacist), the patient is Admitted, the slot has no dose record, and by the SAH-32 rule it is Due (from 60 minutes before to exactly 60 minutes after the slot time) or Late (more than 60 minutes after). Not listed: Pending (they are in Pharmacist check), Rejected and Stopped orders, discharged patients, a slot before the order started (SAH-32 AC 10), and a slot more than 60 minutes ahead.
3. Given a row, then it shows: the medicine name in bold; for a high-alert medicine the red pill "High-alert"; the small line "<patient name> · bed <code> · <time> dose" with the time written "8 AM", "2 PM", "8:30 AM"; a status pill that reads "<N> h late" in red for a Late dose (hours rounded down, at least 1) or "Due now" in amber for a Due dose (a colour is never the only signal); and the primary button **Give**.
4. Given several rows, then Late rows come first, and inside each group the earlier slot time first (most late first). Proposed tie-break, not in the mockup: bed code, then medicine name, so the order does not jump between refreshes (OQ-35-4).
5. Given no dose is due or late, then the panel shows "No doses due right now."
6. Given a very large hospital, then the list is limited to 200 rows, Late first (proposed, OQ-35-4); above 200 a line says "Showing the 200 most overdue doses." and the tile still shows the true count.
7. Given the data, then the patient name, bed and medicine come from the server at display time. They are not written to any application log or audit event (the audit of a given dose follows SAH-32).
8. Given a slow network, then the panel shows "Loading" while it loads. Given a failed load, then the other panels of the board stay, and the panel shows "We could not load the medicine round. Try again." with a Try again button (text not in the mockup).

### SAH-35-2 · Tile, tab number and refresh
9. Given the tiles, then **Doses due or overdue** shows the number of listed doses (Due plus Late, the same set as the rows) with the small text "<N> overdue", N being the Late doses (the mockup writes "0 overdue" when none); the figure is red when N is above 0. The tile comes after "Escalations open" and, for the owner, "Pharmacist checks", and before "Your patients" (NURSE). The tile does not show for the roles without access in AC 20.
10. Given the number next to the Safety tab name, then it is the open escalations (SAH-33) plus, for the owner, the orders waiting for the pharmacist (SAH-31), plus the **Late** doses only, as the mockup (`sfCount`): Due doses are not added. Hidden at 0. (Because the tile counts Due and Late and the tab counts Late only, the two figures differ on purpose; OQ-35-3.)
11. Given the board is open, then it reloads every 60 seconds and after every action, as SAH-33. A dose that becomes Due, or turns from "Due now" to "1 h late", appears or changes on the next reload without anyone acting. A failed reload keeps the last data with the notice "Could not refresh. Showing data from <time>." (SAH-33 AC 20).
12. Given any other tab of Beds and admissions is open, then the counts-only refresh of SAH-33 also carries the Late and Due dose numbers (numbers only, no names, no medicine), and the tab number follows within 60 seconds. The refresh stops while the browser tab is hidden (as SAH-33).
13. Given a dose recorded by anyone, then the figures change only after the server has answered; they are never changed by the browser on its own.

### SAH-35-3 · Give from the list
14. Given a row and the user is NURSE or OWNER and IPD is Trial or On, then **Give** opens the Give sheet of SAH-32 for that order and slot: title "Give · <medicine>", subtitle "<time> dose · <route> · <frequency>" plus " · 1 h late" when late, the identity bar (name, UHID, age, sex, bed, allergy chip), step 1 with the wristband box and the name/date-of-birth tick, step 2 for high-alert medicines (a second nurse and their own PIN), and the reasons for not giving. Every check, message, status code and audit of SAH-32 AC 5 to 8, 12 and 13 applies unchanged. There is no "Scan wristband" button and nothing can be given from the row without the sheet.
15. Given **Given** or **Record not given** succeeded, then the sheet closes, the toast is the one of SAH-32 AC 7 or 8, the user stays on the Safety tab (not sent to the patient's chart), the row leaves the list, and the tile, the tab number and the other rows are reloaded from the server.
16. Given another nurse recorded the same dose first, then the server answers 409 `dose-recorded`; the sheet shows "This dose is already recorded.", closes, the board reloads and the row is gone. Two nurses can never both record one dose. Given the order was stopped or the patient discharged in the meantime, then the answer is 409 (`order-not-verified`, `not-admitted`), the sheet shows the server's message, and the board reloads.
17. Given a failed or lost answer to Give, then the sheet stays open with what was typed (the PIN is cleared) and the row is not shown as given (SAH-32 AC 13).
18. Given IPD is Read-only, then the list stays readable, no Give button is offered, and the panel shows the line "IPD is read-only. Doses cannot be recorded." (text of DEC-018; OQ-35-8).
19. Given the Give button, then its accessible name is "Give <medicine> to <patient name>" (so rows with the same medicine can be told apart), its target is at least 44 px, and focus returns to the Give button of the next row (or to the panel heading when the list is empty) after the sheet closes.

### SAH-35-4 · Roles, tenants, privacy
20. Given the role: OWNER, DOCTOR and NURSE can read the tile, the panel and the counts. NURSE and OWNER can give. DOCTOR sees the rows with no Give button (proposed, OQ-35-1). PHARMACIST (who sees the Safety tab for Pharmacist check only), FRONT_DESK, BILLING and VISITING_DOCTOR get no tile, no panel, and the endpoints answer 403 `forbidden`. The role is read again from the clinic membership on the server (a role change applies at once); the button state is only a convenience.
21. Given IPD Off, then every call answers 403 `module-off`. Given another clinic's order, admission or dose, then the answer is 404 (RLS); the list never holds another clinic's data. The clinic is taken from the signed-in user, never from the request.
22. Given a user who is not NURSE or OWNER calls the dose endpoint, then SAH-32 AC 4 applies (403, "Only nurses and the clinic owner can give medicines.").
23. Given privacy, then reading the list writes no record-access row per patient per refresh (the same rule as the Safety board, DEC-015); opening the Give sheet loads that patient's identity and allergy, which follows the SAH-32 rule for opening the MAR (DEC-018 item 5; architect to confirm). The counts-only refresh carries no name, bed or medicine.
24. Given language, then no text says a nurse or a doctor was alerted; the screen only lists what is due (no notifications in phase 1).

## 3. Technical design (notes for the architect; not a design)
- Reuse `DoseSlots` (SAH-32 ADR-011 D2), which was written for this story. One code path decides Due and Late for the MAR tab count, this list, the tile and the tab number, so the figures cannot disagree.
- Data: no new table is expected (dose records, orders and admissions exist). The db-engineer checks whether the dose table needs an index for "today's records of a clinic" before the 55-million-row growth in DEC-018 item 12.
- The list is one read over all admitted patients of the clinic, not a call per patient (a 100-bed ward must not make 100 calls every 60 seconds). The counts-only refresh must stay cheap.
- Contract changes (proposals): the Safety board answer gains the dose tile numbers and the list; the Safety counts of the refresh gain two dose numbers (Late, Due); the Give call is the existing `POST /medicine-orders/{id}/doses` of SAH-32, unchanged. Whether the Give sheet loads its identity from the existing MAR read or from a small new read is the architect's decision.
- Frontend: the Safety tab already has panels and a tab number; this story adds one tile, one panel and the dose term to the number; the existing Give sheet is opened from a new place.

## 4. API (`/api/v1/ipd`, `docs/api/ipd.yaml`; proposals for the architect)
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/safety` | OWNER, DOCTOR, NURSE | none | `SafetyBoard` plus the dose tile and `MedicineRound` rows | 403, `module-off` |
| GET | `/safety/summary` | OWNER, DOCTOR, NURSE | none | `SafetyCounts` plus Late and Due dose numbers | 403 |
| POST | `/medicine-orders/{id}/doses` | OWNER, NURSE | as SAH-32 | `Mar` | as SAH-32 |

## 5. Tasks
### Tasks — Backend
Dose list query over the clinic's admitted patients using `DoseSlots`; counts for tile and tab from the same code; unit tests at the edges (exactly 60 and 61 minutes late; 60 and 61 minutes ahead; order checked mid-day; Pending, Rejected, Stopped orders; discharged patient; recorded slot; two admissions; sort and tie-break; 200-row cap with true count); role checks twice; no names in logs. Real-DB tests by qa-engineer (clock hook; two nurses giving the same dose; other clinic invisible; PHARMACIST 403).
### Tasks — Frontend
Tile, panel with rows and states, dose term in the tab number, counts refresh, Give from a row with focus handling, Read-only line, error and loading texts; unit tests.

## 6. Out of scope, Open questions
Out of scope: see the requirement `docs/requirements/SAH-35.md`. In short: the card flag "Dose overdue", Emergency access (SAH-34), missed-dose records, PRN and stat doses, alerts to anyone, ward scoping, filters, printing.

Open questions. "Needs human" marks those that cannot be decided by the team; for the others development proceeds on the proposed safer answer (the rule of DEC-014/DEC-015).
- OQ-35-1 (roles, **needs human**): may DOCTOR see the list, and may DOCTOR give? The mockup shows the Safety tab to nurse and owner only. Proposed: OWNER, NURSE and DOCTOR read; only NURSE and OWNER give (follows OQ-32-3, "Only nurses and the clinic owner can give medicines.").
- OQ-35-2 (clinical, patient safety, **needs human and a doctor**): a dose that nobody records before midnight leaves the list and cannot be recorded the next day (SAH-32, DEC-018 item 7), so a missed dose is silent. Proposed: build as the mockup (today only) and add a "missed dose" story after a doctor decides the rule; until then the ward procedure must say who checks at the end of the shift. Record this as a go-live condition with OQ-30-6.
- OQ-35-3 (**needs human**, clinical wording): the mockup counts only overdue doses in the tab number and Due plus overdue in the tile. Proposed: as the mockup (a dose due in the next hour does not turn the tab red).
- OQ-35-4: volume and order. Proposed: 200 rows, Late first, tie-break by bed code then medicine name, a line when cut; the mockup shows no limit.
- OQ-35-5: the tile text when no one is late. Proposed: "0 overdue" as the mockup.
- OQ-35-6: record-access log for the Give sheet opened from a row (identity and allergy of one patient are shown). Proposed: one row, as opening the MAR (DEC-018 item 5); the list itself writes none.
- OQ-35-7: the 60-second refresh for a Late dose, with no alert. Proposed: accepted for phase 1 as DEC-015 OQ-33-10; notifications are Later.
- OQ-35-8 (**needs human**, shared with DEC-018 item 4): in a Read-only clinic, recording a dose is refused. Proposed: keep refusing, show the line in AC 18; decide whether a patient already admitted must still be given medicines on screen.
- OQ-35-9: new texts not in the mockup ("We could not load the medicine round. Try again.", "Showing the 200 most overdue doses.", "IPD is read-only. Doses cannot be recorded.", the accessible name of Give). Proposed: as written; the human confirms wording.
- Shared and still open: OQ-33-2 (ward scoping: a nurse sees every admitted patient), OQ-32-1/OQ-31-3 (wristband and PIN), OQ-30-6 (clinical sign-off), and the go-live conditions of DEC-017 and DEC-018. This story adds no new go-live blocker except OQ-35-2.

## 7. Status log
- 7 Nov 2026 · drafted by the business-analyst from the mockup Safety layer, SAH-32, SAH-33, ADR-011 and DEC-015/DEC-018. Awaiting human approval of `stories`.
