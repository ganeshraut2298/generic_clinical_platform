# SAH-76 · Nursing integration, Sprints 1-2 (doctor order to nurse execution, MVP rows)
Module: ipd
Mockup screen: Nursing sheet (card → Nursing)
Also uses: EMR sheet (card → EMR); IPD → Safety (Pharmacist check panel); IPD → Admitted (card flags)
Reference screens (human-approved as design, DEC-055 answer 2; the human adds them to `docs/design/screen-map.md`, agents cannot): "Nursing workspace · one patient" (`reference/Nursing_workspace_one_patient/Main.dc.html`). The "Nurse round · bed by bed" and "My console" references are read but belong to later sprints (H1, I1-I4) and are out of scope here.
Source: human, chat, 15 Nov 2026, via `docs/decisions/DEC-055-nursing-integration-programme.md` (Option A: MVP rows of Sprints 1-2 first, after a gap check; push alerts and monitor links are Later). Spec: `docs/nursing-integration/nursing-integration-handoff/nursing-integration/NURSING_INTEGRATION_SPEC.md` and `user-stories.csv` in the same folder.

## Problem
In the built IPD module a doctor can order one of 14 medicines at one of four fixed frequencies; a pharmacist checks it; the nurse sees fixed dose slots on the MAR and gives or refuses each one (SAH-30 to SAH-35, SAH-38). The loop is not closed:
- The doctor cannot state dose, start, duration or priority, cannot order "as needed" or STAT medicines, and can only stop an order, never change or hold it, so a changed plan means a stop and a new order.
- The nurse has no list of what is new, changed or stopped, so a doctor's change is found only by opening each chart. Nothing says whose job an urgent order is.
- A pharmacist can only verify or reject; there is no "query the doctor". An urgent medicine cannot be given until verified.
- A dose given by mistake cannot be undone or corrected, there is no manual way to proceed when a barcode cannot be read, and the doctor does not see on the order whether it is being carried out.
- Vitals due follows one fixed 4-hour rule for every ward, and the settings the loop needs (windows, STAT time, ward profile) do not exist.

This requirement closes those gaps for the MVP rows of Sprints 1 and 2 only.

## Who uses it (roles) and in which setting
Hospital ward and pharmacy (IPD). Sahaj roles map to the spec's roles as follows.
| Spec role | Sahaj role | Note |
|---|---|---|
| Consultant / resident doctor | doctor, owner (when the owner is a doctor) | Visiting doctor has no IPD access today (OQ-31-8); unchanged. |
| Pharmacist | pharmacist | Unchanged. |
| Staff nurse, second nurse | nurse (the second nurse is any other active nurse of the clinic) | As built (SAH-32). |
| Nurse in-charge | none | Sahaj has no such role. OQ-76-6. |
| Quality / admin | owner | OQ-76-22. |
Front desk and billing get no access to any of this (403).

## Gap check against what is already built (17 in-scope rows: 1 built, 9 partly built, 7 new)
Sources read: stories SAH-22 to SAH-26, SAH-30 to SAH-33, SAH-35, SAH-36, SAH-38; `docs/api/ipd.yaml` (paths and the medicine-order, stop and MAR text); `backend/src/main/java/com/sahaj/ipd` (no code for PRN, STAT, inbox, change, hold, undo, override or order versions: only escalation "acknowledge", which is a different thing); `docs/handoffs/BOARD.md`. Note: SAH-22 to SAH-26 are wards, beds, admission, bed board, bill and transfer; the nursing sheet, orders, safety checks, pharmacist check, MAR and early warning score are SAH-30 to SAH-35 and SAH-38. SAH-75 is the OPD EMR design alignment and does not touch the IPD nursing loop.

Status: BUILT = works as the spec row says; PARTLY = part built; NEW = nothing built.
| Row | Spec asks | Status | What exists | What is missing (the gap this requirement covers) | Story |
|---|---|---|---|---|---|
| A1 | Scheduled medicine with dose, route, frequency, start, duration; doses for every time in the duration; allergy stop; duplicate warning | PARTLY | Order of 1 of 14 drugs with 4 fixed frequencies (strength is in the drug name, route fixed by drug); allergy hard stop; duplicate hard stop; warnings with override reason; slots computed per day at fixed times (SAH-31, SAH-32) | Dose, route choice, start, duration, indication; slots limited to the start and end of the order; "New" in the nurse's inbox. Allergy and duplicate behaviour differ from the spec (see OQ-76-1, OQ-76-2) | 76-1 |
| A2 | STAT or urgent flag; alert at once; STAT window countdown | NEW | Nothing (STAT and PRN were out of scope of SAH-31) | Priority (routine, urgent, STAT); one dose due now for STAT; STAT window and countdown shown in the app. NO push (DEC-055 answer 3) | 76-1, 76-3 |
| A3 | PRN with indication, minimum interval, 24 h maximum; give flow blocks | NEW | Nothing | PRN order, PRN line on the MAR, interval block, daily maximum lock | 76-1, 76-4 |
| A10 | Change, hold, stop with reason; versions; nurse sees Changed old to new | PARTLY | Stop by a doctor, no reason, removes remaining doses, allowed in Read-only (SAH-31 AC7) | Reason on stop, change as a new version, hold and resume, past doses stay on their version, "stopped by Dr X at hh:mm" on cancelled doses | 76-1 |
| B1 | Pharmacist queue with allergies, interactions, renal/weight dosing, duplicates; verify, query or reject; configurable on or off; MAR says "Waiting for pharmacist" | PARTLY | Pharmacist check panel, allergy, duplicate and interaction checks, Verify with PIN, Reject with reason, self-verification refused, MAR "Waiting for pharmacist" (SAH-31, SAH-32) | Query the doctor (state Queried, doctor sees it); verification on or off per ward; changed versions shown in the queue; renal and weight dosing (no source data: OQ-76-12) | 76-2 |
| B2 | STAT or emergency medicine given before verification; retrospective pharmacist review | NEW | The opposite rule: a dose of an unverified order is refused (409 `order-not-verified`) | Controlled exception for STAT orders, flag "given before pharmacist check", retrospective review item for the pharmacist | 76-2, 76-4 |
| C1 | One inbox of new, changed, stopped orders; one-tap acknowledge; badge STAT red, others amber; unacknowledged alert to in-charge | NEW | Nothing | Inbox, acknowledgement, badges, "not acknowledged for N min" flag (in-app, no push) | 76-3 |
| C2 | Acknowledging creates the nurse's work (doses, samples, tasks, monitoring times) | PARTLY | Doses are computed from an Active order with no acknowledgement step | Acknowledgement step and its effect for doses and vitals due. Samples and tasks come with A4, A5, A6 (Sprints 3-4) | 76-3 |
| D1 | Day MAR with every state; due and overdue first; others fold behind "Show n" | PARTLY | Per-patient MAR tab with slot chips: given, not given or refused, waiting for pharmacist, late, due, later (SAH-32) | States "recorded in error", "on hold", "stopped"; PRN line; version marker; counters; ordering and folding | 76-4 |
| D2 | Give by scanning wristband then medicine; wrong patient or wrong medicine is a STOP; system checks shown | PARTLY | Wristband box (typed or keyboard-scanner UHID), name and date of birth tick, mismatch stop and event (SAH-32, SAH-38) | Medicine code step, "Checked by the system" list, STOP for a wrong medicine, last-given check. Camera scanning, batch and expiry stay Later (OQ-76-16) | 76-4 |
| D3 | High-alert medicines need a second nurse and a server-checked PIN | BUILT | Second nurse and PIN checked on the server, pair and lockout limits (SAH-32, SAH-38) | Nothing. Not re-specified. Only a rule that the new override does not skip it | (76-4 AC) |
| D4 | Manual override with reason and note; goes to a quality report | NEW | No override exists; a mismatch is a stop | Override, four reasons, note, record on the dose, data for the report | 76-4 |
| D5 | "Not given" with reason; ordering doctor notified | PARTLY | Record not given with 5 reasons (SAH-32); doctor sees nothing (OQ-32-4 open) | Reasons "Vomited" and "Other"; the doctor sees the outcome on the order (in-app; no message) | 76-5 |
| D6 | 10-second undo of "Given"; then "recorded in error" that keeps the original | NEW | Deliberately none: dose records are append-only with no undo (SAH-32 AC9, OQ-32-5) | Undo, recorded in error, struck-through history, dose due again. This reverses the SAH-32 decision, as the approved spec requires | 76-5 |
| F1 | Vitals due times follow the doctor's monitoring order | PARTLY | `vitalsDue` after a fixed 4 h (SAH-30 AC10), card flag "Vitals due" (SAH-36) | Frequency per ward profile (spec defaults: ICU 1 h, ward 4 h). The doctor's monitoring order itself is A6 (Sprint 3): until then the profile frequency is used (OQ-76-24) | 76-6 |
| L2 | Audit of every action (who, what, when, device, before/after); five reports | PARTLY | Audit events and the clinical event log for every IPD write, ids and keys only (SAH-30 to SAH-35) | Before/after values, device label, audit for the new actions, stored data for the five reports. The report screens have no design (OQ-76-22) | 76-6 |
| M1 | Department profiles are configuration | NEW | Fixed values in code (60 minute dose window, 4 h vitals, always-on pharmacist check) | A per-ward profile and clinic settings for the values the Sprint 1-2 stories use; no hard-coded department names | 76-6 |

For "PARTLY" rows the built behaviour is reused and the stories describe only the difference.

## In scope / Out of scope
In scope: the 17 rows above, as the gap column describes, inside the existing screens (EMR sheet, Pharmacist check panel, Nursing sheet with its MAR tab and Give sheet, Admitted card flags, Clinic setup) plus the new inbox surfaces named in story 76-3. In-app badges and polling only.

Out of scope (own stories, later):
- Spec rows marked Next or in Sprint 3 and later: A4, A5, A6, A7, A8, A9, A11, D7, D8, E1, E2, F2, F3, F4, G1 to G3, H1, H2, I1 to I4, J1 to J3, K1 to K3, L1, L3, M2 to M4. Two touch points: the doctor's view of dose status in 76-5 is the "K1 partial" of the Sprint 2 plan, limited to the EMR order row; the monitoring order (A6) is not built, so F1 uses the profile.
- Push, SSE or WebSocket alerts, sound, SMS, WhatsApp, monitor and device links (DEC-055 answer 3; CLAUDE.md phase 1). No text may say a person was alerted.
- The full one-page Nursing workspace layout (bed strip, patient band, hour-by-hour timeline, compliance, language switch, Ctrl K finder): Sprint 4. This set delivers the in-scope parts inside the existing sheets (OQ-76-4).
- Camera scanning, batch and expiry checks, pharmacy stock (Pharmacy is Later).
- Clinical scoring charts, dose ranges, accreditation clause numbers. None are invented: each is configuration with a TODO for the hospital (BR-17).
- The quality report screens (OQ-76-22). Data is kept so they can be built.
- Dose and slot times other than the four fixed frequency patterns (SAH-31 AC12). Custom times come later.

## Business rules (numbered)
BR-1. Only doctors (doctor, owner as doctor) order, change, hold and stop medicines; the server checks the role on every call, from the clinic membership, not the token (as SAH-31).
BR-2. A scheduled order has dose, route, frequency, start and, optionally, a duration; its dose slots exist only between its start and its end. A STAT order is one dose due now. A PRN order has an indication, a minimum interval and a maximum number of doses in 24 hours, and no schedule.
BR-3. Priority is routine, urgent or STAT. STAT starts a STAT window (setting; spec default 15 minutes) counted from signing, on the server clock, shown in the app as a countdown, then as "overdue by N min". Urgent is a flag only (OQ-76-14).
BR-4. A medicine order is not givable until a pharmacist has verified it, except a STAT order, which may be given once before verification; that dose is flagged "given before pharmacist check" and the pharmacist gets a review item (spec safety rule 1). When pharmacist verification is Off for a ward, new orders there are Active at signing.
BR-5. The pharmacist may query the doctor. A Queried order is not givable. It leaves that state only by the doctor's reply, a change, or a stop (alerts close only through the clinical action that resolves them, spec safety rule 7).
BR-6. Changing an order creates a new version. Doses already recorded stay on the version they were given under; future slots use the new version; the old version's future slots are cancelled. A changed version is checked by the pharmacist again before its doses are givable (OQ-76-9). Stopping cancels future slots only. Holding makes future slots not givable until the doctor resumes. Change, hold and stop each need a reason. Nothing past is deleted or overwritten.
BR-7. The nurse inbox lists new, changed, stopped, held and resumed orders for the nurse's patients. Acknowledgement is one tap, records who and when, and is idempotent. An item not acknowledged within a setting (spec defaults: 30 minutes, STAT 5 minutes) is flagged in the app; nothing is sent.
BR-8. A dose may be given when the order is Active and not on hold, and the slot is inside its dose window (setting; built 60 minutes before to 60 minutes after, spec default 30 and 30: OQ-76-15). A dose of an order not yet acknowledged may be given; giving it acknowledges the order (OQ-76-5).
BR-9. A dose is recorded as given only after: the wristband code equals the patient's UHID; the medicine code equals the code of the ordered medicine; the nurse confirms two patient identifiers; for a high-alert medicine a second nurse with a server-verified PIN (as built). All checks run on the server on every request.
BR-10. Manual override replaces the two scans (not the two identifiers, not the second nurse, not the allergy or PRN rules). It needs one of four reasons and a note of at least 4 characters, and is kept on the dose and available to quality.
BR-11. A PRN dose is refused while the minimum interval since the last given dose has not passed, and while the maximum in the last 24 hours is reached. The refusal names the time the next dose is allowed.
BR-12. "Not given" needs a reason from the list (note of at least 4 characters for "Other"). The ordering doctor sees it on the order in the app.
BR-13. A given dose can be undone by the same nurse within 10 seconds (server clock). After that it can be marked "recorded in error" with a reason; the original entry stays, struck through, with who, when and why; the dose becomes due or late again. Both are audited. Nothing is deleted.
BR-14. Vitals are due at the last vitals time plus the ward profile frequency (spec defaults: ICU 1 hour, ward 4 hours). A patient with no vitals is due at once (as built).
BR-15. Every action in this set is audited with user, time, device label, entity, action and before/after values. Audit and log text never holds a patient name, mobile number, ABHA number or free clinical text (indication, note, reply, reason text). The clinical event log stays append-only.
BR-16. Settings and the ward profile are changed by the owner only and take effect for later events; they never rewrite past records.
BR-17. Anything clinical that the hospital must define is configuration with a TODO for the hospital, with no value invented here: dose ranges, weight-based dosing, renal dosing, high-alert and time-critical drug lists, last-given intervals for scheduled drugs, scoring charts for other patient groups, accreditation mapping.
BR-18. Tenant and scope: the clinic comes from the signed-in user; another clinic's data answers 404 (RLS); when ward scoping (SAH-34) is on, a nurse sees only her wards' orders and inbox items; IPD Off answers 403 `module-off`; Read-only is OQ-76-23.

## Patient-safety and privacy points
- This set loosens two built safety rules at the human's request (the spec is approved): a STAT dose before verification (BR-4) and manual override of the scans (BR-10), and it reverses the "no undo" rule (BR-13). Each is limited as stated and audited. The human must confirm the limits in OQ-76-10, OQ-76-17, OQ-76-19.
- Everything that was a hard stop stays a hard stop: allergy, duplicate (OQ-76-1, OQ-76-2), wrong patient, wrong medicine, second nurse for high-alert, PRN interval and maximum, an order on hold, a stopped or superseded order.
- A changed order must never be given under its old version: the server refuses a dose against a superseded version.
- A STAT or overdue item is never closed by the screen, a timer or a reload: only by the clinical action (acknowledge, give or not-given recorded, doctor reply).
- No alert is sent. Texts say what is listed, never that someone was alerted (as SAH-35 AC24).
- Allergy stays in the identity bar and Give sheet as built. The scanned text is clinical-adjacent: shown on screen only, first 40 characters in the event log as built, never in application logs.
- Another patient's name is not shown to a nurse who scans a wrong wristband (OQ-76-18) because the spec's text would disclose a second patient's identity to anyone holding a band.
- All clinical text (indication, query, reply, change reason, override note, not-given note) is shown only to roles with clinical access and is never logged or put in audit before/after values (only keys, numbers, "note present").
- Go-live conditions inherited and unchanged: OQ-30-6 (scoring sign-off), OQ-31-1 (medicine, class and high-alert list approval), OQ-31-3 (PIN), OQ-35-2 (missed doses at midnight). New go-live conditions: OQ-76-10, OQ-76-15, OQ-76-19.

## Plan or entitlement
IPD module (SAH-19): state Trial or On for use. Read-only: reading allowed; writes refused with 409 `ipd-readonly` except those listed in OQ-76-23. Off: 403 `module-off`. No new plan limit, no new module. Setup of settings is a Clinic setup page for the owner (OQ-76-21).

## Open questions for the human
Each has a default. "Human" = a clinical, legal, security or design decision only the human can make; work proceeds on the default and the stories say so. Numbers are used in the stories.

Conflicts with built behaviour or the mockup
- OQ-76-1 (Human, clinical). Allergy: the spec (A1) lets the doctor continue past a documented allergy with an override reason; built SAH-31 makes it a hard stop that cannot be ordered at all. Default: keep the built hard stop. The spec's override is not built.
- OQ-76-2 (Human, clinical). Duplicate: the spec says warning; built is a hard stop ("change the existing order"). Default: keep the built hard stop; a change is now possible through A10 so the stop no longer forces stop-and-reorder.
- OQ-76-3 (Human). The reference "Nursing workspace" shows a time-grid MAR (hours across, NOW marker). The mockup Nursing sheet shows slot chips per order, and the mockup rules where it shows the same thing. Default: keep the mockup layout; add from the reference only the counters, folding, PRN chip, struck-through entries and the give flow; the grid timeline is Sprint 4 (G1). Words: the mockup says "late" ("1 h late"); the spec and reference say "overdue". Default: keep "late" on screen so the MAR and the Safety board agree; the data state name is "overdue".
- OQ-76-4 (Human, design). The reference is a full page; Sahaj has the Nursing sheet opened from the patient card. Default: build inside the existing sheets and card (a new "Orders" tab in the Nursing sheet, badge on the card, a panel on the Safety tab) and do not build the full page now.
- OQ-76-5 (Human, clinical). Is a dose givable before the nurse acknowledges the order? Spec is silent; C2 says acknowledging creates the work. Default: a verified order's doses are on the MAR from verification; they can be given without acknowledgement and giving acknowledges the order. This avoids a missed dose caused by a missing tap. If the human wants acknowledgement first, only 76-3 and 76-4 change.
- OQ-76-6 (Human). "Nurse in-charge" is not a Sahaj role. Default: items not acknowledged in time show as a red strip to all nurses of the ward and to the owner; no person is "alerted". A new role or a "nurse in charge" flag on a nurse is a platform decision for later.
- OQ-76-7 (Human, design). Refresh: DEC-055 says "polling". The Safety board refreshes every 60 seconds; a STAT item must be acknowledged in 5 minutes. Default: the inbox count and STAT countdown poll every 30 seconds while a nursing screen is open (counts only, stops when the browser tab is hidden); the countdown itself runs from the server time shown, not the device clock.

Orders and pharmacist
- OQ-76-8 (Human, clinical). Dose entry: strength is in the drug name today ("Tab. Paracetamol 650 mg"). Default: "Dose" is the amount per dose with its unit (for example 1 tablet, 650 mg, 500 ml) chosen from units of the drug; route defaults from the drug and the doctor may choose another route from a fixed list; no dose-range check (TODO hospital supplies ranges; BR-17). Confirm the units list.
- OQ-76-9 (Human, clinical, patient safety). Does a changed version need pharmacist verification again? Spec is silent. Default: yes, except hold, resume and stop; until verified the new version's doses show "Waiting for pharmacist" and the old version's future doses are cancelled, which can create a gap in therapy; a STAT-flagged change may be given first (BR-4). A doctor and pharmacist must confirm.
- OQ-76-10 (Human, clinical, go-live). STAT before verification (B2): who may mark an order STAT (default: any ordering doctor), whether "emergency" is a separate flag (default: no; only priority STAT), whether it is allowed once only (default: yes, one dose), and what the pharmacist's retrospective review can do (default: "Mark reviewed" or "Query doctor").
- OQ-76-11 (Human). Pharmacist query: what the doctor sees and does. Default: the question (up to 300 characters, a limit the architect confirms) shows on the order row in the EMR sheet with a Reply box; replying returns the order to the pharmacist's queue with the reply visible; changing or stopping also resolves it; no message is sent.
- OQ-76-12 (Human, clinical). Renal and weight dosing in the queue: Sahaj records no weight or kidney function. Default: not built; the queue shows these fields only when the patient file holds them (it does not today); no rule is invented (BR-17, TODO hospital).
- OQ-76-13 (Human). Verification On or Off per ward (spec section 11). Default: On for every ward; the owner can switch a ward profile Off; orders already waiting stay waiting.
- OQ-76-14. Priority "urgent": the spec names it but gives it no behaviour. Default: amber flag in the inbox and queue only; no window, no countdown, no early give.

Giving
- OQ-76-15 (Human, clinical, go-live). Dose window: built 60 minutes before to 60 after; spec default 30 and 30. Default: both are settings; the shipped default stays 60 and 60 so the built MAR and Safety board do not change under existing wards; the hospital sets its value (spec section 11).
- OQ-76-16 (Human). Medicine scan: the reference shows a camera and batch and expiry; SAH-32 OQ-32-1 decided keyboard scanner or typed code, no camera, and no pharmacy stock exists. Default: the medicine code is typed or sent by a keyboard-type scanner and compared with a code held for each drug in the drug master (TODO hospital loads the codes); no camera, no batch or expiry. The reference's "Simulate wrong patient" and demo scan buttons are demo aids and are not built.
- OQ-76-17 (Human, patient safety). Override limits (D4): default as BR-10; after a mismatch STOP on a code the override for that item is not offered until the nurse rescans or cancels; the "emergency" reason is the nurse's statement and is flagged to quality like the others; who sees the flag (OQ-76-22).
- OQ-76-18 (Human, privacy). Reference STOP text names the other patient whose band was scanned. Built text names the expected patient and not the scanned one. Default: keep built; show "This band belongs to another patient" without a name.
- OQ-76-19 (Human, clinical, go-live). PRN: whether the daily maximum counts doses (default) or amount; whether the 24 hours roll (default) or restart at midnight; the per-dose PRN reason and the reassessment task are D7 (Next), so a PRN dose here records the order's indication only.
- OQ-76-20 (Human, clinical). Interval and weight checks on scheduled drugs: the spec lists "last-given interval and weight-based dose where configured". Default: the Give sheet shows the time of the last given dose; only PRN has a blocking interval; weight-based checks appear only for drugs a hospital configures (none shipped, TODO).

Not given, undo, error
- OQ-76-21 (Human, design). The settings page and the ward profile choice are not drawn in the mockup or the reference. Default: one page "Nursing settings" in the Clinic setup IPD group, owner edits, other roles read (NEW, needs sign-off); until the human accepts it, shipped defaults apply and no page is built. Departments are mapped to wards: each ward has a profile; "ICU" and "General ward" are seeded data rows, not code.
- OQ-76-22 (Human). The five quality reports (overrides, not-given doses, late time-critical doses, unacknowledged orders, escalation response times) and the "quality" role have no screen. Default: data and audit are kept; no report screen or role is built; the owner reads the audit through the existing clinical event log. "Device" in the audit means a random per-browser id plus the user-agent family, no IP address.
- OQ-76-23 (Human). Read-only IPD: stop and hold are allowed (stop is allowed today because it removes a medicine); order, change, resume, acknowledge, give, not-given, undo and error answer 409 `ipd-readonly`. Confirm.
- OQ-76-24 (Human, clinical). F1: A6 (the monitoring order) is Sprint 3, so a doctor cannot yet set a frequency. Default: ward profile frequency now; an order's frequency overrides it when A6 exists; a raised escalation does not shorten the due time yet (the advice text of SAH-30 is unchanged).
- OQ-76-25 (Human, clinical). Undo and error scope: default Given doses only (as the spec); a wrongly recorded "Not given" cannot be undone and is corrected by a nursing note; recorded in error by the nurse who recorded it or any nurse of the ward and the owner, same Asia/Kolkata day as the slot only (after midnight the dose cannot be re-recorded, OQ-35-2).
- OQ-76-26 (Human). Not-given reasons: the built list (patient refused, held on doctor's advice, nil by mouth, medicine not available, patient away from the ward) keeps its words; "Vomited" and "Other" are added; "Other" needs a note of at least 4 characters.
- OQ-76-27. Unacknowledged times and STAT window come from settings; hold semantics: a hold has no automatic end, slots missed during a hold are not created again on resume, and held slots are never counted as late.
- OQ-76-28. Field order and wording of the extended order form, the inbox, the "Review after dose" list, the query and reply boxes and the new toasts are not in the mockup or reference; the stories propose them and mark them NEW for sign-off.
- OQ-76-29. Pharmacist queue: should the pharmacist see the "Changed old to new" lines? Default: yes.

## Checks before handoff (for the architect)
- Built dose slots are computed per day and the dose record is append-only with one record per slot. Undo, recorded-in-error, hold, order versions and a stored record of what the nurse acknowledged need the architect's decision (stored dose events and a scheduler, as the spec suggests, or computed slots plus correction records). The requirement states behaviour only.
- The Sprint 1 to 2 data and the later sprints share one order model: the architect should design the order and version model so that lab, nursing, monitoring, diet and device orders (A4 to A9) can be added without rework.
