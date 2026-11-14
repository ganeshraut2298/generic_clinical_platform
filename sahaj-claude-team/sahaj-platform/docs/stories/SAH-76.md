# SAH-76 · Stories: Nursing integration, Sprints 1-2 (doctor order to nurse execution, MVP rows)
Module: ipd
Mockup screen: Nursing sheet (card → Nursing)
Also uses: EMR sheet (card → EMR); IPD → Safety (Pharmacist check panel); IPD → Admitted (card flags). Reference screen (human-approved design, DEC-055; the human adds it to the screen map): "Nursing workspace · one patient" (`Main.dc.html`).
Requirement: docs/requirements/SAH-76.md (gap check table, business rules BR-1 to BR-18, open questions OQ-76-1 to OQ-76-29).
Source: DEC-055 (human, 15 Nov 2026); spec rows A1, A2, A3, A10, B1, B2, C1, C2, D1 to D6, F1, L2, M1 of Sprints 1-2.

Reading guide. Built behaviour (SAH-30 to SAH-35, SAH-38) is not repeated: each story says only what changes. Every AC that follows a default of an open question says so. Texts marked NEW are not in the mockup or the reference and need the human's sign-off (OQ-76-28). In-app badges and polling only: no push, SSE, sound, SMS or WhatsApp; no text may say that a person was alerted. Time and money formats as CLAUDE.md (11:05 AM IST).

Common rules for every story (each AC below assumes them; they are tested once per endpoint):
- IPD Off: 403 `module-off`. Read-only: OQ-76-23. Another clinic's admission, order or dose: 404 (RLS); the clinic is taken from the signed-in user, never from the request.
- Roles are checked on the server on every call and read again from the clinic membership. Front desk and billing: 403 for everything here.
- When ward scoping (SAH-34) is on, a nurse sees and acts on her wards only; outside it is 403 `outside-scope`.
- A button is disabled while its request runs, and nothing is shown as saved until the server has answered; a failed call keeps what was typed. No offline queue.
- Clinical text is never logged and never put in audit values (BR-15).

---

### SAH-76-1 · As a doctor, I want to order a scheduled, STAT or PRN medicine with dose, route, start and duration, and to change, hold or stop it with a reason, so that the nurse gets an exact schedule and every later change is clear and nothing past is lost
Priority: Must
Screen: EMR sheet (card → EMR), blocks "Medicines on chart" and the order form (mockup); the Nursing workspace reference for how the nurse sees the result. Rows: A1, A2 (order side), A3 (order side), A10. Sprint 1 (A3 order fields may land in Sprint 2 with 76-4).

## Acceptance criteria
Ordering
- Given an Admitted patient and a user who may order (doctor, owner as doctor), when the EMR sheet opens, then the order form keeps **Medicine**, **How often** and the button **Check and order** as built and gains, after them (field order NEW, OQ-76-28): **Dose** (amount and unit, OQ-76-8), **Route** (prefilled from the medicine, changeable from a fixed list), **Start** (date and time, default now), **Duration** (whole days, or "Until stopped", default "Until stopped"), **Indication** (optional for scheduled orders) and **Priority** (Routine, Urgent, STAT; default Routine).
- Given a scheduled order is signed, when its start is 10:00 AM and its frequency's dose times are 8:00 AM and 8:00 PM, then the first dose slot is 8:00 PM that day, no slot exists before the start or after the end (end = start plus duration), and with "Until stopped" slots continue until the order is stopped.
- Given Priority is STAT, when the doctor chooses it, then **How often** and **Duration** are hidden (a STAT order is one dose, due now) and the form says so; when signed, one dose slot is due at the signing time with the STAT window running from that moment (setting, spec default 15 minutes).
- Given **How often** is "As needed (PRN)", when the doctor chooses it, then **Indication**, **Minimum interval** (minutes or hours) and **Most doses in 24 hours** are required, **Duration** is optional, and no scheduled slots are created; the order is a PRN line (BR-2).
- Given Priority is Urgent, when signed, then the order is flagged Urgent (amber) in the pharmacist queue and the nurse inbox and has no window or countdown (OQ-76-14).
- Given the allergy, duplicate and interaction checks of SAH-31, when the doctor orders, then they run on every request exactly as built: a hard stop still cannot be placed and warnings still need a reason from the list (OQ-76-1, OQ-76-2). No dose-range check exists and none is invented (BR-17, TODO hospital).
- Given dose missing or not above 0, unit missing, route not in the list, start before the signing time, duration not a whole number of at least 1 day, PRN interval or maximum missing or not above 0, or STAT sent with a frequency, when signed, then the answer is 400 with a field message for each (texts NEW) and nothing is saved.
- Given verification is On for the patient's ward (76-6), when an order is signed, then it is Pharmacist check as built with the toast "<medicine> ordered. It goes to the pharmacist before the first dose."; given verification is Off, then it is Active at once, tagged "Not pharmacist checked" on the order row, and the toast says it is on the MAR (OQ-76-13).
- Given an order is signed, when it becomes givable (verified, or Active at signing, or STAT), then it appears in the nurses' inbox as New (76-3).
- Given a retry or double tap of Check and order, then no second order is created (the idempotency of SAH-31 applies to the new fields too).
- Given a user who may not order (nurse, pharmacist, front desk, billing, visiting doctor), then the form shows "Only doctors can order medicines." and the API answers 403; a patient who is not Admitted answers 409 `not-admitted`.

Change
- Given an Active or Pharmacist check order, when the doctor taps **Change**, then a form lets the doctor edit dose, route, frequency, duration or end, indication and priority (not the medicine: a different medicine is a new order) and requires a **Reason for the change** (text, 4 to 200 characters, NEW), shown "Changed: <old> → <new>" per edited field before saving.
- Given a saved change, then a new version of the order exists; doses already recorded stay linked to the version they were given under; the old version's future slots are cancelled and shown "Replaced by a change by Dr X at 11:05 AM"; the new version's future slots follow its start, with the first slot not earlier than the change time; the order row shows "Changed" with the new values and a version marker.
- Given a changed version, then its doses are not givable until the pharmacist has verified it ("Waiting for pharmacist" on the MAR; the pharmacist queue shows the old → new lines); a STAT change follows the STAT rule of 76-2 (OQ-76-9).
- Given the change would repeat the current values, then the answer is 400 "Nothing was changed." (NEW).
- Given two doctors save a change on the same version at the same time, then the first succeeds and the second gets 409 `order-changed` with the current values and a notice; nothing is written by the second.

Hold and resume
- Given an Active order, when the doctor taps **Hold** and enters a reason, then the order shows "On hold" with the doctor and time; its slots from that time are not givable, show "On hold by Dr X", are never counted as due or late (OQ-76-27); a dose attempt answers 409 `order-on-hold`.
- Given a held order, when the doctor taps **Resume**, then slots from the resume time on are due as normal, slots missed during the hold are not created again, and the nurse inbox shows the order as Resumed.

Stop
- Given a Pharmacist check, Active or held order, when the doctor taps **Stop** (button as built), then a **Reason for stopping** (NEW, 4 to 200 characters) is required; future slots are cancelled and shown "Stopped by Dr X at 2:00 PM"; slots already given are unchanged; the toast of SAH-31 is kept; stop stays allowed in Read-only (OQ-76-23).
- Given a Rejected or already Stopped order, when stop is tried, then 409 `order-state` as built.

Roles and tenants
- Given a user who is not a doctor (or owner as doctor) calls change, hold, resume or stop, then 403 "Only a doctor can do this."; another clinic's order 404; an order of a patient not Admitted 409 `not-admitted`.
- Given a change, hold, resume or stop, then an audit event is written with ids, medicine key and the before and after values of dose, route, frequency, priority, start and end (never the indication or reason text) and a clinical event (kinds order or stop) with fixed wording (BR-15).

## Notes for design
- A1: today "dose slots" are computed per day from fixed times; "dose events for every time within the duration" needs the architect to choose stored events with a scheduler (spec: rolling 48 hours) or computed slots bounded by start and end. The behaviour above is the requirement, not the storage.
- Order versions must carry through to the MAR, the pharmacist queue and the audit; design the model so later order types (A4 to A9) reuse it.
- The reason for a change, hold or stop is clinical text: stored, shown to clinical roles, never logged.
- Units list, route list and priority wording need the human (OQ-76-8, OQ-76-28).

---

### SAH-76-2 · As a pharmacist, I want to query the doctor on an order, see what changed in a new version, and review STAT medicines that were given before I checked them, so that a doubtful order is held and an urgent dose is never delayed or left unreviewed
Priority: Must
Screen: IPD → Safety, panel "Pharmacist check" with its Verify and Reject sheets (mockup). Rows: B1 (gap), B2 (pharmacy side). B1 Sprint 1; B2 Sprint 2.

## Acceptance criteria
Queue (B1 gap)
- Given verification is On and an order or a changed version waits, when the pharmacist opens the panel, then each row shows as built plus: the dose, route, start and duration; a red "STAT" or an amber "Urgent" tag; for a changed version the lines "Changed: <old> → <new>" and the doctor's reason for the change (clinical text, shown only to PHARMACIST and OWNER, OQ-76-29).
- Given the patient file holds a weight or kidney function value, when the row is shown, then the value is shown beside the order; given it holds none (the case today), then nothing is shown and no dosing rule is applied (OQ-76-12; BR-17).
- Given the checks of SAH-31 (allergy, duplicate, interaction, self-verification, PIN), then they are unchanged.

Query the doctor
- Given a waiting order, when the pharmacist taps **Query doctor** (third button, NEW) and enters a question (required, up to 300 characters), then the order becomes Queried, shows the pill "Queried" (NEW) on the pharmacist row and on the MAR and EMR order row, and is not givable (a dose attempt answers 409 `order-not-verified`).
- Given a Queried order, when the ordering doctor (or another doctor or the owner as doctor) opens the EMR sheet, then the order row shows "Pharmacist query: <question>" with a **Reply** box (required, up to 300 characters); no message is sent anywhere (BR-5, OQ-76-11).
- Given the doctor replies, then the order returns to Pharmacist check with the reply shown on the pharmacist row ("Doctor's reply: <text>"); given the doctor changes or stops the order instead, then the query is closed by that action.
- Given the pharmacist taps Query doctor on an order already Active, Rejected or Stopped, then 409 `order-state`; the question is empty or longer than 300 characters, then 400; a user who is not a pharmacist or an owner or a doctor who is not the orderer (the rule of SAH-31 for verify), then 403.
- Given the question or the reply, then it is shown only to doctors, pharmacists and the owner, never logged, and the clinical event holds "Queried <medicine>" and "Replied to query on <medicine>" only.

Verification On or Off (B1 configurable)
- Given verification is Off for a ward (76-6), when a doctor signs an order for a patient in that ward, then it does not enter the pharmacist queue; the order row and the dose record show "Not pharmacist checked"; the MAR does not show "Waiting for pharmacist". Orders already in the queue when the setting is turned Off stay waiting until verified or rejected.

STAT given before verification (B2)
- Given a STAT order is Pharmacist check, when the nurse gives its one dose (76-4), then the pharmacist panel gets a row under the heading **Review after dose** (NEW) with "Given before pharmacist check at 11:05 AM by <nurse>", the checks run now (allergy, duplicate, interaction) and the buttons **Mark reviewed** and **Query doctor**.
- Given a Review after dose row, when the pharmacist taps **Mark reviewed**, then the row leaves the list, with the pharmacist and time recorded; given the row is not reviewed, it stays until it is (alerts close only through the action, BR-5).
- Given a STAT order whose dose has been given and which the pharmacist now finds a hard stop, then the row shows the hard stop in red and **Mark reviewed** is replaced by **Query doctor** only (the doctor must act; NEW, OQ-76-10).
- Given the pharmacist also taps Verify or Reject on a STAT order after the dose, then the verify and reject rules of SAH-31 apply and the review row closes with it.
- Given a non-STAT order, then it can never be given before verification (409 `order-not-verified` as built).

Roles, tenants, audit
- Given roles as built (PHARMACIST and OWNER see the panel; the verifier cannot be the orderer), then another role gets 403 and another clinic's order 404.
- Given a query, reply, review or mark reviewed, then an audit event is written with ids, medicine key, order version and outcome only.

## Notes for design
- "Queried" and "Review after dose" are new order and review states; the architect decides whether the review item is a state of the dose or a separate record.
- Weight and kidney function have no source in the patient file; if the patient module later adds them, the queue shows them (OQ-76-12).
- No notification service: the doctor finds the query when the EMR sheet opens; the doctor inbox is K2 (Sprint 3).

---

### SAH-76-3 · As a nurse, I want one list of new, changed, stopped and held orders for my patients with one-tap acknowledgement and a STAT countdown, so that I do not miss any doctor's change and know which order is urgent
Priority: Must
Screen: Nursing sheet (card → Nursing), a new tab "Orders" per patient (reference: Nursing workspace, panel "Nursing orders", "n to acknowledge", Acknowledge button); count badge on the Admitted card (mockup card flags); a ward list panel on the Safety tab (placement NEW, OQ-76-4, OQ-76-28). Rows: C1, C2, A2 (nurse side). Sprint 1.

## Acceptance criteria
Inbox content
- Given a medicine order becomes givable (verified, or Active at signing, or STAT), when it is signed or verified, then an item **New** exists for the patient's nurses with the patient (name and bed), the medicine and order summary (dose, route, frequency, start, duration, priority), who ordered and when.
- Given a change, hold, resume or stop of an order (76-1), then an item **Changed**, **On hold**, **Resumed** or **Stopped** exists with who and when and, for Changed, the fields as "<old> → <new>" (BR-7); the doctor's reason is shown to nurses (clinical text, on screen only).
- Given a stop or hold, then it takes effect at once for dosing whether or not the nurse has acknowledged it (a stopped or held dose answers 409 as in 76-1 and 76-4).
- Given the "Orders" tab of the Nursing sheet, when it opens, then the patient's items are listed newest first, unacknowledged before acknowledged, with the empty text "No new orders." (NEW) and, on a failed load, "We could not load the orders. Try again." with a Try again button (NEW); the other tabs still work.
- Given the ward list panel on the Safety tab (nurse and owner), then it lists unacknowledged items of the nurse's patients across patients, STAT first, then by age, each opening that patient's Orders tab; empty text "No orders waiting for you." (NEW).

Acknowledge (C1, C2)
- Given an unacknowledged item and a nurse or the owner, when **Acknowledge** is tapped, then the item is marked with the nurse and time, the badge count drops, and the order shows "Acknowledged by <nurse> at 11:10 AM"; a double tap or a retry answers 200 with the same state and writes nothing more.
- Given two nurses tap Acknowledge on the same item, then the first wins and the second sees "<nurse> already acknowledged this." (409 `order-state`, NEW) and the list reloads.
- Given a nurse gives a dose of an order not yet acknowledged (76-4), then the order becomes acknowledged by that nurse at that time (OQ-76-5).
- Given an order is acknowledged, then its doses (for the whole order, or the PRN line) are on the patient's MAR and counted in the MAR due and late numbers, and no re-entry is needed by the nurse (C2 for this sprint). Samples, tasks and monitoring times are not created: A4, A5, A6 come later.
- Given the nurse acknowledges a Stopped item, then it is only marked read; the stop is already in force.

Badges and STAT (A2 nurse side, C1)
- Given a patient has unacknowledged items, then the Admitted card and the Orders tab show a count badge, red when any item is STAT, else amber (urgent or routine); the badge disappears when all are acknowledged.
- Given a STAT item whose STAT window has not passed, then it shows "STAT · give within 15 min · 9 min left" (the minutes are the setting and the server's remaining time); given the window has passed and the dose is not recorded, then it shows "STAT · overdue by 6 min" in red; the countdown and "overdue" come from the server clock sent with the answer, not the device clock; it is never removed by a timer or reload, only by the dose being given or not-given recorded, or the order stopped (BR-3).
- Given the setting "unacknowledged order flag" (spec default 30 minutes, STAT 5 minutes), when an item has been unacknowledged longer, then it shows "Not acknowledged for 35 min" in red on the Orders tab and at the top of the ward list panel to all nurses of the ward and the owner (OQ-76-6); no message is sent and no text says anyone was alerted.
- Given a nursing screen is open, then the badge counts and STAT countdowns refresh by polling every 30 seconds with counts only (no names), stop while the browser tab is hidden, and a failed refresh keeps the last data with the notice "Could not refresh. Showing data from 11:05 AM." (the pattern of SAH-33; OQ-76-7).

Roles, tenants, scope
- Given the roles, then NURSE and OWNER can read and acknowledge; DOCTOR can read the list but has no Acknowledge button; PHARMACIST, FRONT_DESK, BILLING get no tab, no badge and 403.
- Given ward scoping is on (SAH-34), then a nurse sees and acknowledges only the items of her wards; another clinic's item 404; a discharged patient's items leave the lists.
- Given IPD is Read-only, then the list can be read and Acknowledge answers 409 `ipd-readonly` (OQ-76-23).
- Given an acknowledgement, then an audit event is written with the order id, version, item kind and user.

## Notes for design
- The reference shows the inbox as the "Nursing orders" panel of one patient with "n to acknowledge"; the spec says "one inbox for my patients". The ward list on the Safety tab and the "Orders" tab are the proposed placement (OQ-76-4).
- Polling interval, settings values and in-charge handling are in OQ-76-6, OQ-76-7.
- Do not add the inbox count to the Safety tab number (SAH-35 defined that figure).

---

### SAH-76-4 · As a nurse, I want a clear medicine chart and a give flow that checks the patient, the medicine and the order version, with a manual override when a scan is impossible and a safe route for PRN and STAT doses, so that the right dose reaches the right patient and nothing is lost when a scan fails
Priority: Must
Screen: Nursing sheet (card → Nursing), tab "Medicines (MAR)" and the Give sheet (mockup); reference: Nursing workspace, "Medication" and the give flow (counters, fold, PRN chip, "Checked by the system", "Cannot scan? Manual override"). Rows: D1, D2, D3 (unchanged, one rule), D4, A3 (give side), B2 (give side). Sprint 2.

## Acceptance criteria
Chart (D1)
- Given the MAR tab, then the header of the medicines area shows the counters "N late", "N due now", "N given" (reference, NEW to the mockup layout; kept the mockup word "late", OQ-76-3), and the order blocks keep the mockup layout (slot chips per order).
- Given the orders on the MAR, then blocks with at least one due or late slot come first (most late first), then blocks with a later slot, and the blocks with nothing due or late (all given, all later, on hold, stopped) are folded behind a button **Show N** that toggles them; when nothing is due or late the text "Nothing due right now. Medicines with nothing due are folded away." (reference text) shows.
- Given a slot, then its state is one of: later, due, late, given (time and nurse as built), not given or refused (as built), waiting for pharmacist, on hold, stopped ("Stopped by Dr X at 2:00 PM", cancelled slots after the stop), replaced by a change, or recorded in error (76-5, struck through). The due and late edges follow the dose window setting (OQ-76-15).
- Given an order that was changed or is PRN, then its block shows "Changed" with a version marker, or a "PRN" chip with the indication, the minimum interval and the doses given in the last 24 hours ("2 of 4 in 24 h").
- Given an order on hold, stopped, or waiting for the pharmacist, then no Give button is offered on its slots, with the reason shown as built or "On hold by Dr X".

Give flow (D2, D3)
- Given the Give sheet, then the steps are in this order: **1 · Confirm the patient** (the wristband box and the two-identifiers tick, as built), **2 · Scan or type the medicine code** (NEW box, accessible name "Medicine code", OQ-76-16), then, once both match, the panel **Checked by the system** with these lines: Right patient; Right medicine and dose; Right route (the route of the order); Right time ("Right time" or "Late: 40 min after schedule"); Allergy ("Allergy checked · no clash with <allergy>" or "No known allergy" or "Allergies not recorded", using the SAH-31 allergy rule); Last given (the time of the last given dose of this order, or "No earlier dose") and, only for a medicine a hospital has configured, the weight-based dose (none shipped, BR-17); then step **3 · Second nurse check** for a high-alert medicine and **If you are not giving it** as built.
- Given the wristband box holds another patient's UHID, when the nurse proceeds, then a red STOP shows "Wrong patient. This band belongs to another patient. Check the patient before giving anything." with no name of the other patient (OQ-76-18), **Given** stays disabled, nothing is recorded and the event "Stopped at the identity check…" of SAH-38 is written.
- Given the medicine code is not the code of the ordered medicine, when entered, then a red STOP shows "Wrong medicine. This is <medicine name of the code, or 'an item not on this order'>, not <ordered medicine>." (NEW), **Given** stays disabled, nothing is recorded, and the event "Stopped at the medicine check: entered <code, first 40 characters>, expected <medicine>" is written with no patient name.
- Given both codes match, two identifiers are confirmed, and (high-alert only) the second nurse is chosen and their PIN is correct, then **Given** is enabled; the second-nurse rules, the PIN lockout and the pair limits are those of SAH-32 and SAH-38 and are not changed (D3 is built).
- Given every check, then the server re-runs all of them on **Given** whatever the client showed, in the order: role; order Active and not on hold or stopped; order version is the current one; slot inside the window; no record for the slot; wristband; medicine code (or override); two identifiers; high-alert second nurse; PRN rules (below).
- Given the order was changed or stopped after the nurse opened the Give sheet, when **Given** is sent, then 409 `order-changed` (message "This order was changed. Reload and check the new order.", NEW) or the stop message, nothing is recorded, and the sheet reloads.

Manual override (D4)
- Given the Give sheet, when the nurse taps **Cannot scan? Manual override**, then the reasons "Wristband damaged or missing", "Medicine has no barcode", "Scanner not working", "Emergency" and a note field "What you checked instead, e.g. name and DOB read from the case file" (reference) appear; the note must have at least 4 characters after trimming; the line "Only when a scan is impossible. The override, your reason and your name go to the pharmacy and quality team." shows.
- Given an override with a reason and a note, when the nurse confirms the two identifiers (still required) and, for a high-alert medicine, the second nurse and PIN (still required), then the dose is recorded as Given with method Override, the reason key and "note present"; the MAR chip shows "✓ 11:05 AM · override" and the toast reads "<medicine> recorded as given (override, reason saved)" (reference).
- Given a scan returned a mismatch STOP for a code, then the override is not offered for that item until the nurse rescans or cancels (OQ-76-17); given an override reason is missing or the note is under 4 characters, then 400 with the messages "Choose why you cannot scan." and "Write what you checked instead." (NEW).
- Given an override dose, then it is available to quality with the reason, nurse and time (OQ-76-22) and is marked so that a later compliance item "every dose scanned" can be un-ticked (the compliance screen is later).
- Given a role that cannot give, then no override is offered and the API answers 403.

PRN (A3 give side)
- Given a PRN line on the MAR, when the nurse taps **Give**, then the same Give sheet opens, titled with the PRN medicine, showing the order's indication and the minimum interval; the dose is recorded with the order's indication as the reason (per-dose reason and reassessment task are D7, Next; OQ-76-19).
- Given a PRN dose was given less than the minimum interval ago, when the nurse tries to give, then the sheet shows "Too soon. Next dose allowed at 2:30 PM." (time in IST from the server), **Given** is disabled, and the API answers 409 `prn-too-soon` with the time.
- Given the maximum doses in the last 24 hours (rolling) have been given, then the PRN line shows a lock "Daily maximum reached. Next dose allowed at 6:10 PM.", no Give is offered, and the API answers 409 `prn-max-reached` with the time; doses recorded in error or undone do not count (76-5).
- Given a high-alert PRN medicine, then the second nurse rule applies as for scheduled doses.

STAT before verification (B2 give side)
- Given a STAT order still Pharmacist check, when the nurse opens its dose, then **Give** is offered with the notice "STAT: this dose can be given before the pharmacist check. The pharmacist reviews it after." (NEW); all checks above still apply (including the second nurse); the dose is recorded "Given before pharmacist check" (flag) and the review row of 76-2 is created in the same transaction.
- Given a STAT order has had its one dose given, then no second dose can be given before verification (409 `order-not-verified`).
- Given a non-STAT order is Pharmacist check or Queried, then Give is not offered and the API answers 409 `order-not-verified` as built.

Roles, errors, audit
- Given roles as SAH-32 (NURSE and OWNER give; DOCTOR reads), tenants, Read-only and slow-network rules of SAH-32, then they are unchanged and apply to the new steps; the PIN is cleared after a failed request.
- Given a given dose, a STOP or an override, then audit events are written with ids, medicine key, version, method, reason key and result, and never the scanned text beyond the first 40 characters in the clinical event log, a patient name or the note text.

## Notes for design
- D2 and D4 depend on a code for each drug in the drug master (OQ-76-16); the architect decides the field and who loads it (TODO hospital).
- The mockup MAR keeps slot chips; the reference's time grid is Sprint 4 (OQ-76-3).
- The dose window, STAT window and PRN rules read settings (76-6).
- One code path should decide due, late, on hold and stopped for the MAR, the Safety board medicine round (SAH-35) and the card flags, so the counts agree.

---

### SAH-76-5 · As a nurse, I want to record why a dose was not given, undo a wrong "Given" for 10 seconds, and mark it "recorded in error" afterwards, and as a doctor I want to see on each order what was done or not done, so that mistakes can be corrected without hiding them and the doctor knows my orders are carried out
Priority: Must
Screen: Nursing sheet (card → Nursing), MAR tab and Give sheet "Record not given" (mockup); reference: Nursing workspace, undo toast and "Mark as recorded in error"; EMR sheet "Medicines on chart" (mockup) for the doctor's view. Rows: D5, D6, K1 (partial, as the Sprint 2 plan). Sprint 2.

## Acceptance criteria
Not given (D5)
- Given the Give sheet, when **Record not given** is chosen, then the reasons are the built five ("Patient refused", "Held on doctor's advice", "Nil by mouth", "Medicine not available", "Patient away from the ward") plus **Vomited** and **Other** (OQ-76-26); **Other** requires a note of at least 4 characters ("Write what happened."); no reason gives 400 "Choose why the dose is not given." as built.
- Given a not-given record, then the outcome mapping of SAH-32 is kept (Refused for "Patient refused", Held for the others, new reasons stored with their key), the toast "Recorded: <medicine> not given · <reason>. The doctor sees this on the chart." is kept, and the wristband and second nurse are still not required.
- Given a not-given dose, when the ordering doctor, another doctor or the owner opens the EMR sheet, then the order row shows "Not given · <reason> · 11:05 AM · <nurse>" in amber and, for a PRN or scheduled order, "Last dose: Given 11:05 AM by <nurse>" or "Next due 2:00 PM" or "N late", with the order's acknowledgement ("Acknowledged by <nurse> at 11:10 AM" or "Not acknowledged yet"); no message is sent and no text says the doctor was notified (the doctor inbox is K2, Sprint 3; NEW, OQ-76-28).
- Given an order with several dose records, then the order row shows the latest one and a count of "Not given" doses today; the full history stays on the MAR.

Undo (D6)
- Given a nurse records **Given**, when the answer arrives, then the toast shows "<medicine> recorded as given" with an **Undo** button and a 10-second countdown (reference); the countdown and the limit run on the server clock (the setting, default 10 seconds).
- Given the same nurse taps **Undo** within the window, then the dose returns exactly to its earlier state (due, late or later, per the clock), no "Given" remains on the slot, the toast says "Undone. <medicine> is due again."; the high-alert second nurse is not asked again; the doses of this order are counted again for PRN intervals as if never given.
- Given the window has passed, or a different user taps it, then the answer is 409 `undo-expired` or 403, nothing changes, and the Undo button is gone from the screen after 10 seconds.
- Given an undo, then both the give and the undo are kept in audit with who and when (spec safety rule 5), and the clinical event log shows "Gave <medicine>…" followed by "Undid the dose of <medicine>" (fixed wording, no names); neither is deleted.
- Given a double tap on Undo, then one undo is applied and the second answers 200 with the same state or 409 `dose-state` and writes nothing more.

Recorded in error (D6)
- Given a given dose and the nurse who recorded it, another nurse of the ward or the owner (OQ-76-25), when the dose chip is tapped after the undo window, then a panel shows the dose details ("Given 11:05 AM by <nurse> · wristband and medicine scanned" or "scan override: <reason>" and "checked by <second nurse>") and "Mark as recorded in error · the original entry is kept, struck through, with your name, time and reason" (reference) with four reason chips: "Dose was not actually given", "Documented on the wrong patient", "Wrong time recorded", "Duplicate entry".
- Given a reason is chosen, then the original entry stays visible on the MAR struck through with "recorded in error by <nurse> at 11:20 AM: <reason>", the slot becomes due, late or later again per the clock and may be given again with every check (including the second nurse for a high-alert medicine), and the new give is a new record linked to the struck-through one.
- Given a slot after the end of its Asia/Kolkata day, or a doctor, pharmacist, front desk or billing user, then the Recorded in error action is not offered and the API answers 409 `error-window-closed` or 403 (OQ-76-25).
- Given a dose already recorded in error, or one that is not Given (not given, refused), then the API answers 409 `dose-state`; a wrongly recorded "Not given" is corrected by a nursing note, not by this action (OQ-76-25).
- Given a recorded-in-error entry, then audit holds the dose id, order id, version, reason key, user and time, and the event log holds "Marked the dose of <medicine> recorded in error: <reason>"; entries are never edited or deleted.

Roles, tenants, states
- Given Read-only IPD, a patient not Admitted, another clinic's dose (404), a slow or failed network, then Not given, Undo and Recorded in error answer or behave as the other writes (409, 404, disabled while running, nothing shown as saved before the server answers).

## Notes for design
- This reverses the SAH-32 decision "append-only, no undo" at the human's approved spec. Corrections should be appended records, so history stays append-only.
- The unique record per slot (SAH-32) must allow a new give after an error or an undo.
- Undo and recorded in error do not give a doctor an alert; K2 (Sprint 3) builds the doctor inbox.
- The doctor's view is an addition to the mockup's "Medicines on chart" block (OQ-76-28).

---

### SAH-76-6 · As the owner, I want the nursing settings and a ward profile as configuration, vitals due times that follow the ward's frequency, and a full audit with the data quality needs, so that no ward rule is hard-coded and every action can be shown afterwards
Priority: Must
Screen: Nursing sheet (card → Nursing), tab "Vitals and notes" and the "Vitals due" flag of the Admitted card (mockup); settings page: Clinic setup, IPD group (a page not drawn in the mockup or reference, so its layout is NEW and needs sign-off, OQ-76-21); audit: IPD → Safety, panel "Clinical audit trail" (mockup). Rows: M1, F1, L2. Sprints 1 and 2.

## Acceptance criteria
Settings and ward profile (M1)
- Given the owner opens Clinic setup, IPD group, **Nursing settings** (NEW page, OQ-76-21), then these settings show with their defaults: Pharmacist verification required (On); Dose window before and after the scheduled time (60 and 60 minutes as built; spec 30 and 30: OQ-76-15); STAT window (15 minutes); Flag an order not acknowledged after (30 minutes; STAT 5 minutes); Undo window (10 seconds); the list of high-alert and time-critical medicines (high-alert as built; time-critical none shipped, TODO hospital, OQ-76-10).
- Given a ward, then it has a profile chosen from profile rows ("ICU", "General ward" seeded as data); a profile holds: verification On or Off, vitals frequency (spec defaults ICU 1 hour, ward 4 hours) and the dose window override; no ward or department name appears in code; a new profile is a new row (BR-16, M1).
- Given the owner changes a setting or a ward's profile, then it applies to events after the change and never rewrites past records; the change is audited with before and after values (numbers and On or Off).
- Given a user who is not the owner, then the page is read-only and a change answers 403; Read-only IPD answers 409; values outside range answer 400 (for example a window below 1 minute); another clinic's settings 404.
- Given no profile is set for a ward, then the defaults apply (verification On, vitals every 4 hours, built dose window).
- Given the other spec profile attributes (list type, timeline type, scales, bundles, devices, round frequency), then they are not stored in this set; each is added by the sprint that uses it.

Vitals due (F1)
- Given a patient's ward profile has vitals frequency 4 hours and the last vitals were recorded at 8:00 AM, then vitals are due at 12:00 PM: the Nursing sheet's "Vitals and notes" tab shows "Next vitals due 12:00 PM" (NEW line; "Vitals due now" or "Vitals overdue by 25 min" after that), and the Admitted card flag "Vitals due" (SAH-36) appears from 12:00 PM.
- Given a ward profile with frequency 1 hour (the ICU profile), then the due time is one hour after the last vitals and the same flag follows it.
- Given a patient has no vitals yet, then vitals are due at once (as built); given the nurse saves vitals early, then the next due time restarts from that save.
- Given the monitoring order of A6 exists (Sprint 3), then its frequency overrides the profile; until then a doctor cannot set a frequency (OQ-76-24); the advice text after an escalation (SAH-30) is unchanged and does not shorten the due time.
- Given the due time is computed, then it uses the server clock and the Asia/Kolkata time zone; the frequency in force at the last save is not rewritten when the profile changes later.

Audit (L2)
- Given any write of this story set (order, change, hold, resume, stop, query, reply, review, acknowledge, give, not given, override, undo, recorded in error, settings, profile change), then an audit event is written with the user, the time, the device label, the entity and id, the action and the before and after values (BR-15; the device label is a random per-browser id and the user-agent family, no IP address, OQ-76-22).
- Given a failed identity or medicine check or a refused action, then the refusal is also audited with the reason key and the code length or first 40 characters as built, never a patient name.
- Given the audit values, then they hold ids, keys, numbers, On or Off and times only: never a patient name, mobile number, ABHA number, indication, note, query, reply, reason text or scanned text beyond the built 40 characters.
- Given the stored data, then each of these questions can be answered from it by an authorised reader: which doses were given by override, with reason, nurse and time; which doses were not given, with reason; which doses of a time-critical medicine were late; which orders were unacknowledged and for how long; and (existing, SAH-33) escalation response times. No report screen is built (OQ-76-22).
- Given the clinical event log, then it stays append-only and the new events use fixed wording as listed in the stories above.
- Given a non-owner, then they cannot read audit events beyond what the Safety tab shows them today (OWNER and DOCTOR all; NURSE own and her patients), and another clinic's events are never visible.

## Notes for design
- Departments are mapped to wards (OQ-76-21); the architect decides whether a profile is a table or a setting set.
- The settings page, its sign-off and its layout are a human design decision; if not supplied before the architect starts, ship the defaults and no page (data and audit only).
- Where the spec says "quality", Sahaj has no role: the owner reads (OQ-76-22).
- A "device" in audit is a privacy choice: no IP address and no device serial.
