Reserved story IDs: SAH-87 (IPD clinical/admin gap analysis A) / SAH-88 (IPD money/supply gap analysis B). Status: PROPOSED, not approved.
# IPD-flow-gap-analysis-A · IPD clinical and administrative flow, admission to discharge
Module: ipd
Mockup screen: Bed board, Admitted, Emergency (IPD →); Nursing sheet (card → Nursing); EMR sheet (card → EMR); Transfer, Discharge, Case paper (card actions); Clinic setup → Discharge. Every PROPOSED flow below that has no screen there is marked "NOT IN MOCKUP: needs human decision".
Source: Programme DEC-061 / IPD, gap analysis A by business analyst, 21 Nov 2026. Written from `docs/handoffs/IPD-gap-A-draft.md` and re-checked against the repo.
Status: ANALYSIS. Every proposed story is PROPOSED. Nothing here is approved, nothing is built from it, and nothing changes an existing story.

## Problem
IPD has many pieces (admit, orders, MAR, charges, transfer, discharge) but a hospital cannot yet run a stay from admission to discharge on paper-free records: there is no admission note, no consent record, no death, LAMA or medico-legal record, no discharged-patients view, and nothing can be printed. This file lists what is covered, partial and missing, rates each gap, and proposes stories to close the Must gaps. It does not design APIs, tables or screens.

Method note (from the draft, kept): the draft read files by name and search; it did not run the system. "Covered" means the pieces exist, not that they work. QA must prove it (see the end-to-end outline).

## Who uses it (roles) and in which setting
Owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist, in a hospital ward and at the front desk. Role differences are in each story. Billing has no IPD access today (SAH-27 OQ-27-3).

## In scope / Out of scope
In scope: clinical and administrative flow from OPD "Admit advice" to discharge, and the records a hospital needs around it (notes, consent, death, LAMA, MLC, handover, orders, census, print, discharged list).
Out of scope: money and supply flows (see `IPD-flow-gap-analysis-B.md`); ABDM push, blood bank, OT scheduling, monitor links, NABH reports (Later, G27, G28); API, table and screen design.

## Re-check against the repo (corrections and confirmations)
| Draft statement | Result |
|---|---|
| "Stories SAH-22..33 have no requirement files found." | PARTLY WRONG. `docs/requirements/SAH-29.md` exists, and so do SAH-34 to SAH-38. The following IPD stories have no requirement file: SAH-22, 23, 24, 25, 26, 27, 28, 30, 31, 32, 33 (stories exist in `docs/stories/`). |
| "SAH-27 cites docs/stories/IPD-open-questions.md (not found)." | CONFIRMED, but wider: SAH-29, 30, 31, 32 cite the same path. The file exists at `docs/decisions/IPD-open-questions.md` (it holds OQ-27-1..10 in sections A, B, C and D). Human-owned folders are untouched; BA cannot fix the story text. |
| "ADM-2 and ADM-3 are platform stories mis-named, no matching requirements." | CONFIRMED. `docs/stories/ADM-2.md` and `ADM-3.md` exist; `docs/requirements/` has no ADM-* file. |
| "Print interim bill says soon." | CONFIRMED in the mockup (admission sheet footer button `data-act="soon"`, "Print interim bill") and in SAH-27 (button stays disabled). |
| "Nursing handover: words only." | CONFIRMED. The mockup transfer notice says "the nurse handover moves to <nurse>'s colleague in the new ward", and the discharge chain shows "Nursing handover"; SAH-27 OQ-27-2 and DEC-014 make it a label completed by the discharge button. |
| "Bed reservation: state drawn, no flow." | CONFIRMED (the mockup has a "reserved" bed state; no story creates one). |
| Isolation (G17) missing. | CONFIRMED. The only "Isolation" in the mockup is "Organization Isolation" (tenant security), not a patient isolation flag. |
| "Discharged-patients view missing." | CONFIRMED. SAH-27 OQ-27-6: the mockup has no screen; only the counter "Going home today"; detail is read through the admission. |
| "LAMA, Referred, Death are discharge types only." | CONFIRMED (SAH-27 AC 3, OQ-27-7). SAH-27 also states the death summary "cannot be printed in this phase". |

## Trace (admission to discharge)
- Covered (pieces exist): OPD Admit advice (SAH-67-3); emergency queue (SAH-28); bed board and housekeeping (SAH-24); admit with deposit and consent tick (SAH-23); doctor round note (SAH-67-1); medicine orders with safety checks and pharmacist check (SAH-31, SAH-76); vitals, notes, early warning score (SAH-30, SAH-33); MAR with wristband and second nurse (SAH-32, 35, 38); charges, running bill, deposit alert (SAH-25); bed transfer (SAH-26); emergency access (SAH-34); normal discharge (SAH-27); bed to cleaning after discharge; setup pages; IPD Off, Trial, On, Read-only gate.
- Partial: bed reservation; nursing handover; interim bill (print says "soon"); LAMA, Referred, Death (type only); discharge summary and bill print (print not built, WhatsApp Later); insurance (one notice, no pre-authorisation record).
- Missing: admission note (the Case paper sheet is a stub); lab, diet and procedure orders in IPD; procedure, anaesthesia, blood and high-risk consent; discharged-patients view with reprint.

## Missing flows, rated (all PROPOSED)
Must for go-live:
| Ref | Gap | Story |
|---|---|---|
| G1 | Admission note and printable Case paper | IPD-A-1 |
| G2 | Death record (time, place, cause, certifier, printed summary, patient marked deceased) | IPD-A-2 |
| G3 | LAMA record (reason, risks explained, signer, witness) | IPD-A-3 |
| G4 | Medico-legal case (MLC) flag | IPD-A-4 |
| G5 | Consent forms (who signed; admission, procedure, anaesthesia, blood, high-risk); a tick is not evidence | IPD-A-5 |
| G6 | Print of discharge summary, final and interim bill, case paper | IPD-A-7 |
| G7 | Discharged-patients list with reprint | IPD-A-6 |
| G8 | TPA and insurance pre-authorisation and cashless (Must for hospitals that take cashless; share to confirm, OQ-A-14) | none proposed here; needs the human decision first |
| G9 | Visiting doctor in IPD (to confirm, OQ-A-13) | none proposed here |

Should: G10 nursing handover (the transfer part is Must because the mockup promises it) IPD-A-8; G11 lab and radiology orders, G12 diet, G13 procedure and OT orders IPD-A-9; G14 mid-stay refund and interim bill; G15 reversal of mistaken admission and of day-care discharge; G16 bed reservation; G17 isolation flag IPD-A-10; G18 bed census IPD-A-11; G19 birth record; G20 referral letters; G21 nursing charts beyond vitals; G22 attendant, address and ID at admission; G23 take-home medicines; G24 billing clearance and nursing handover as real sign-offs.
Later: G25 in-hospital consult request; G26 dietician and physiotherapy; G27 blood bank, OT scheduling, monitor links, ABDM push; G28 incident log and NABH reports.

## Business rules (apply to every proposed story)
1. Every story is PROPOSED. It becomes buildable only after the human approves `stories` and, where marked NOT IN MOCKUP, decides to add the screen to the mockup and screen map or to accept the proposed placement (OQ-A-3).
2. All reads and writes are limited to the user's clinic (tenant). Another clinic's record answers 404.
3. In IPD Read-only state, writes answer 409 `ipd-readonly`; reads stay allowed. In Off state, 403 `module-off`.
4. Clinical sign-offs record who and when and cannot be undone; changes are new, audited entries.
5. Audit events never carry patient name, mobile, ABHA number or clinical text.
6. Legal and clinical content (death, LAMA, MLC, consent wording, certificates) is NOT decided here. Each is an open question; stories give the structure only.
7. Money is integer paise; dates "24 Oct 2026", time "11:05 AM IST".

## Patient-safety and privacy points
- The Case paper, death, LAMA, MLC and consent records are sensitive clinical or legal records; reading them is logged in the patient record-access log (as SAH-27 AC 1).
- Front desk and billing must not read clinical notes unless the human decides (OQ-A-9).
- MLC and death records must not leak into reminders, follow-ups or notices (SAH-27 OQ-27-7: deceased marking).
- A consent tick (SAH-23) is not evidence of informed consent; do not describe it as such to users.
- An unsigned admission note should warn, not block, until the human decides (OQ-A-1), so that an emergency discharge is never blocked by paperwork.

## Plan or entitlement
IPD module On or Trial (Read-only: reads only). Hospital plan only. Print features follow the Documents decision (OQ-A-17): print view in the browser is assumed; PDF and WhatsApp are Later.

## Open questions for the human
Numbering from the draft is kept; the draft's list is merged with the questions the draft summarised in one line. Existing questions stay open and are not renumbered: OQ-27-1..10, OQ-31-8, OQ-76-22.

| Id | Question |
|---|---|
| OQ-A-1 | Must an unsigned admission note block discharge? (default: warn only) |
| OQ-A-2 | Admission note layout: there is no mockup. Who supplies it (the mockup Case paper sheet is a stub)? |
| OQ-A-3 | New screens not in the mockup: add them to the mockup and screen map, or accept the proposed placement? |
| OQ-A-4 | (Legal) Death record: which facts are mandatory, who may certify, what certificate is printed, how the patient is marked deceased. |
| OQ-A-5 | (Legal) LAMA: who signs, who witnesses, what risks text. |
| OQ-A-6 | (Legal) MLC: what does it block, and who may release? |
| OQ-A-7 | (Legal) Is a consent tick enough, or are e-signature or scan needed? What if an emergency has no signer? |
| OQ-A-8 | How long can discharged records be viewed and printed? (the mockup shows 90 days Read-only) |
| OQ-A-9 | May front desk or billing print the clinical summary? |
| OQ-A-10 | Is handover mandatory at transfer? |
| OQ-A-11 | Must consent exist before a procedure is marked done? |
| OQ-A-12 | Isolation types and deep clean after an isolation stay. |
| OQ-A-13 | Visiting doctors in IPD for go-live? |
| OQ-A-14 | What share of hospitals take cashless (decides G8 priority)? |
| OQ-A-15 | Labour rooms, nurseries, OT: in or out of phase 1? |
| OQ-A-16 | Renumber ADM-2 and ADM-3 (breaks SAH-<n>); where are their requirements; fix the path `docs/stories/IPD-open-questions.md` (file is in `docs/decisions/`). Needs the human or tech lead; BA does not own those folders. |
| OQ-A-17 | Print only (browser) or PDF? |

## Proposed stories (summary; full text below and in the same file)
IPD-A-1 admission note; A-2 death record and summary; A-3 LAMA record; A-4 MLC flag; A-5 consent forms; A-6 discharged list and reprint; A-7 print set; A-8 handover note and acknowledge; A-9 investigation, diet and procedure orders; A-10 isolation flag; A-11 bed census.

Go-live priority: Must = A-1, A-2, A-3, A-4, A-5, A-6, A-7 (A-8 transfer part is Must; the rest of A-8 Should). Should = A-8 (beyond transfer), A-9, A-10, A-11. Could = none. Final priority belongs to the human.

Placement note: no existing screen shows these flows except where named. Where "NOT IN MOCKUP", the placement is only a suggestion for the human.

# Stories (PROPOSED)

## Common acceptance criteria (CAC), applied to every story below
- CAC-1 Given a user of another clinic, when they open or change any record of these stories by id, then the answer is 404 and nothing is revealed.
- CAC-2 Given IPD is in Read-only state, when anyone tries to create or change, then the answer is 409 `ipd-readonly`; reads still work.
- CAC-3 Given IPD is Off, then 403 `module-off`.
- CAC-4 Given a slow or lost network on a save, then the button is disabled while the request runs, a repeated tap or a retry never creates a second record (same client key), and if the answer is lost the user reopens the record and sees the saved state.
- CAC-5 Given a discharged or unknown admission where the story needs an admitted patient, then 409 `not-admitted` (or 404 if unknown).
- CAC-6 Given any write, then an audit event is written without patient name, mobile or clinical text.

### IPD-A-1 · As a doctor, I want to write the admission note for an admitted patient and print the Case paper, so that the patient's history and plan are on record from the first day
Status: PROPOSED · Priority: Must
Mockup screen: Transfer, Discharge, Case paper (card actions); the Case paper sheet exists in the mockup as a stub. NOT IN MOCKUP: needs human decision for the note fields and layout (OQ-A-2).
Roles: doctor and owner write; nurse and front desk read where allowed (OQ-A-9); visiting doctor (OQ-A-13).
## Acceptance criteria
- Given an Admitted patient, when the doctor opens Case paper and saves an admission note, then the note is stored with the doctor, time and admission, and a record-access row is written.
- Given a saved note, when the doctor changes it, then a new version is kept and the earlier one stays readable (history).
- Given a nurse, when she tries to create the note, then 403 (until the human decides otherwise).
- Given front desk, when they open the note, then they see the Case paper without clinical text, or 403, as the human decides (OQ-A-9).
- Given the note is empty or a required field is missing or too long, then 422 with the field named, and nothing is saved.
- Given discharge starts and the note is unsigned, then a warning shows and discharge is still possible (OQ-A-1).
- Given the Case paper, when the user taps Print, then a print view opens (see IPD-A-7).
- Given another clinic's admission id, then 404 (CAC-1). Given Read-only, then 409 `ipd-readonly` (CAC-2). Given a slow network, CAC-4.
## Notes for design
Fields and layout are not in the mockup; the architect must not invent them. Reuse the clinical-event record style of SAH-30/33 only if the human agrees.

### IPD-A-2 · As a doctor, I want to record a death and print the death summary, so that the family and the hospital have the required record and the patient is not contacted again
Status: PROPOSED · Priority: Must
Mockup screen: Transfer, Discharge, Case paper (card actions): the Discharge sheet type "Death" exists; the death record itself is NOT IN MOCKUP: needs human decision. SAH-27 says the death summary cannot be printed in this phase.
Roles: doctor (certifier), owner; nurse and front desk read; legal rules OQ-A-4.
## Acceptance criteria
- Given the Discharge sheet with type Death, when the doctor saves the death record (time, place, cause, certifier; exact fields by the human, OQ-A-4), then the record is stored and the discharge can complete as Death.
- Given the death record is missing a mandatory fact, then 422 with the field named and the discharge button stays disabled.
- Given a completed Death discharge, then the patient is marked deceased, and no follow-up or reminder is created or sent (SAH-27 AC 8, OQ-27-7).
- Given a nurse or front desk, when they try to save the record, then 403; reading shows the printed summary only as OQ-A-9 decides.
- Given a completed record, when the user taps Print, then the death summary print view opens (IPD-A-7).
- Given a repeated tap or two users, then one record and one discharge only (409 `not-admitted` for the second).
- Given another clinic (CAC-1), Read-only (CAC-2), slow network (CAC-4).
## Notes for design
Certificate wording and format are legal and stay open (OQ-A-4). The "deceased" flag crosses into `patient.api`; the architect decides how.

### IPD-A-3 · As a doctor, I want to record that a patient leaves against advice, with reason, risks explained, signer and witness, so that the hospital has evidence
Status: PROPOSED · Priority: Must
Mockup screen: Transfer, Discharge, Case paper (card actions): type "On request (LAMA)" exists; the record is NOT IN MOCKUP: needs human decision.
Roles: doctor, owner record; nurse can add witness if the human agrees (OQ-A-5).
## Acceptance criteria
- Given Discharge type LAMA, when the doctor saves reason, "risks explained" confirmation, signer name and relation, and witness, then the LAMA record is stored with who and when.
- Given any of these missing, then 422 with the field named and the discharge button stays disabled (exact required set by the human, OQ-A-5).
- Given a signer who cannot sign (patient unable, no relative), then the record can state why, as the human decides (OQ-A-5, OQ-A-7).
- Given a wrong role (front desk, billing), then 403.
- Given the type is changed from LAMA to Normal after saving, then the LAMA record stays on file marked not used, and the discharge signature of SAH-27 AC 5 must be signed again.
- Given other clinic (CAC-1), Read-only (CAC-2), slow network (CAC-4).
## Notes for design
Legal wording, signer and witness rules are open (OQ-A-5).

### IPD-A-4 · As a doctor or nurse, I want to flag a medico-legal case on an admission, so that staff handle it with the right care
Status: PROPOSED · Priority: Must
Mockup screen: NOT IN MOCKUP: needs human decision. Suggested placement: the Case paper sheet header and a pill on the Admitted card.
Roles: doctor, nurse, owner set the flag; front desk sees the pill only; release by owner only if the human agrees (OQ-A-6).
## Acceptance criteria
- Given an admission, when a doctor sets MLC with the reason category and time, then the flag is stored with who and when, and a pill shows on the card and sheet.
- Given an MLC admission, when anyone starts discharge, then the effect (warn or block) follows the human's answer (OQ-A-6); until then a warning shows.
- Given a user without the right, when they try to set or release, then 403.
- Given the reason is empty or too long, then 422.
- Given the flag is released, then the release is a new audited entry; the flag history stays.
- Given other clinic (CAC-1), Read-only (CAC-2), slow network (CAC-4).
## Notes for design
The pill must not show the reason text to front desk.

### IPD-A-5 · As a nurse or doctor, I want to record consent forms with who signed, for admission, procedure, anaesthesia, blood and high-risk care, so that consent is evidenced and not just ticked
Status: PROPOSED · Priority: Must
Mockup screen: Admission sheet (the consent tick, SAH-23) exists; consent forms are NOT IN MOCKUP: needs human decision (OQ-A-3, OQ-A-7).
Roles: doctor, nurse, front desk record admission consent; doctor records procedure, anaesthesia, blood and high-risk consent; owner reads all.
## Acceptance criteria
- Given an admitted patient, when staff record a consent of a kind, then the form kind, version of the wording, signer name and relation, witness, time and recorder are stored.
- Given the signer is missing, then 422, unless the human allows an emergency "no signer" reason (OQ-A-7).
- Given a consent kind and a wrong role (for example front desk for anaesthesia), then 403.
- Given a procedure order is to be marked done and no consent exists, then the effect (warn or block) follows OQ-A-11; until decided, a warning.
- Given a recorded consent, then it cannot be edited; a withdrawal is a new entry.
- Given the evidence form (e-signature or scan) is undecided, then only the typed record is stored and the screen says so (OQ-A-7).
- Given other clinic (CAC-1), Read-only (CAC-2), slow network (CAC-4).
## Notes for design
Consent wording texts are legal content; not written by BA.

### IPD-A-6 · As a front desk user, doctor or owner, I want a list of discharged patients with reprint, so that I can find a past stay, summary and bill
Status: PROPOSED · Priority: Must
Mockup screen: NOT IN MOCKUP: needs human decision (SAH-27 OQ-27-6: only the counter "Going home today"; the mockup mentions 90 days Read-only, OQ-A-8). Suggested placement: a tab next to Admitted in IPD → Admitted.
Roles: owner, front desk, doctor, nurse read (as SAH-27 AC 13); billing only as the human decides.
## Acceptance criteria
- Given discharged admissions, when the user opens the list, then they see discharge date, type, patient, bed, doctor, newest first, with search by IP number and name and a date filter.
- Given a row, when opened, then the frozen final bill, type, signature and settlement show, read only; reprint opens the print view (IPD-A-7).
- Given the retention window of OQ-A-8 has passed, then the record is hidden from the list as the human decides; until decided, nothing is hidden.
- Given no discharged patients or no search match, then an empty state with text.
- Given a list of many rows, then it pages; a search shorter than the minimum gives 422.
- Given a wrong role, then 403; another clinic's id, 404 (CAC-1); Read-only, reads still allowed (CAC-2); slow network shows a loading state and retry, never a blank list.
- Given a row is opened, then one record-access row is written (not one per list view).
## Notes for design
List does not show diagnosis to front desk.

### IPD-A-7 · As a doctor, nurse or front desk user, I want to print the discharge summary, final bill, interim bill and case paper, so that the patient takes paper home
Status: PROPOSED · Priority: Must
Mockup screen: Transfer, Discharge, Case paper (card actions). The mockup has "Print interim bill" (`data-act="soon"`, disabled in SAH-27); the other prints are NOT IN MOCKUP: needs human decision (OQ-A-17).
Roles: doctor, owner print all; front desk and billing print bills only unless OQ-A-9 says otherwise; nurse prints case paper.
## Acceptance criteria
- Given a signed discharge, when the user taps Print summary, then a print view opens showing the signed summary and who signed; a record-access row is written.
- Given a final bill, when printed, then it shows the frozen lines, total, deposits and balance exactly as stored, never recalculated.
- Given an Admitted patient, when Print interim bill is tapped, then a print view shows the running bill at that moment and the words "Interim, not final".
- Given a summary not yet signed, then Print summary is disabled with the hint "The doctor signs the summary first".
- Given front desk and the clinical summary, then 403 or allowed as OQ-A-9 decides.
- Given the print service is not available (PDF or WhatsApp Later), then only the browser print view is offered.
- Given other clinic (CAC-1); Read-only (prints of existing records stay allowed, CAC-2); slow network: the button is disabled while loading and shows an error with retry.
## Notes for design
No document service in phase 1; browser print only unless OQ-A-17 changes.

### IPD-A-8 · As a nurse, I want to write a handover note and have the receiving nurse acknowledge it, so that a transferred or shift-changed patient is not missed
Status: PROPOSED · Priority: Must for the transfer part, Should for shift handover
Mockup screen: Transfer, Discharge, Case paper (card actions): the transfer notice says the nurse handover moves to the colleague in the new ward. Recording and acknowledging are NOT IN MOCKUP: needs human decision (OQ-A-10, SAH-27 OQ-27-2).
Roles: nurse writes and acknowledges; doctor reads; owner reads.
## Acceptance criteria
- Given a transfer, when the sending nurse saves a handover note, then it is stored and shown to the receiving ward.
- Given a handover note, when the receiving nurse acknowledges, then who and when are stored; an unacknowledged handover shows as pending on the card.
- Given handover is mandatory at transfer (OQ-A-10 yes), then the transfer cannot complete without it; if no, a warning only.
- Given an empty note, then 422; too long, 422.
- Given a wrong role (front desk), then 403.
- Given an acknowledged note, then it cannot be edited; a correction is a new note.
- Given other clinic (CAC-1), Read-only (CAC-2), slow network (CAC-4).
## Notes for design
Do not duplicate nursing notes (SAH-30). The discharge chain label "Nursing handover" stays a label unless OQ-27-2 changes.

### IPD-A-9 · As a doctor, I want to order investigations, diet and procedures for an admitted patient, so that the whole plan is in one place
Status: PROPOSED · Priority: Should
Mockup screen: EMR sheet (card → EMR) shows medicine orders only; investigation, diet and procedure orders are NOT IN MOCKUP: needs human decision.
Roles: doctor and visiting doctor order (OQ-A-13); nurse views and marks done; billing reads charges later.
## Acceptance criteria
- Given an Admitted patient, when the doctor orders a lab test, a diet or a procedure, then the order is stored with the doctor, time, priority and status Ordered.
- Given an order, when the nurse marks it done, then who and when are stored; a done order cannot be undone.
- Given a procedure order, then consent follows OQ-A-11 (warn until decided).
- Given an order item that is not in the clinic's tariff list, then 422 with the item named (no free price).
- Given invalid or empty fields, then 422; a duplicate open order of the same item for the same patient shows a warning.
- Given wrong role (front desk orders), then 403.
- Given other clinic (CAC-1), Read-only (CAC-2), slow network (CAC-4).
## Notes for design
Lab results are Later (lab module). Charges for ordered tests are decided by billing rules, not here.

### IPD-A-10 · As a nurse, I want to flag a patient as isolated and have the bed need a deep clean, so that infection spread is controlled
Status: PROPOSED · Priority: Should
Mockup screen: NOT IN MOCKUP: needs human decision (the only "Isolation" in the mockup is tenant isolation). Suggested placement: pill on the Admitted card and the bed tile.
Roles: doctor, nurse set and clear; owner reads; housekeeping follows OQ-A-12.
## Acceptance criteria
- Given an admission, when the doctor or nurse sets isolation with a type from the list in OQ-A-12, then a pill shows on the card, sheet and bed tile.
- Given the patient is discharged or transferred, then the bed goes to Cleaning with a deep-clean note as decided in OQ-A-12.
- Given an unknown type, then 422; a wrong role, 403.
- Given other clinic (CAC-1), Read-only (CAC-2), slow network (CAC-4).
## Notes for design
Types and deep-clean rule are clinical content (OQ-A-12).

### IPD-A-11 · As an owner or nurse, I want a bed census, so that I know occupied, free, cleaning and reserved beds by ward at a time
Status: PROPOSED · Priority: Should
Mockup screen: IPD → Bed board (shows ward and bed status; a census view or print is NOT IN MOCKUP: needs human decision).
Roles: owner, nurse, front desk read.
## Acceptance criteria
- Given beds in several wards, when the user opens the census, then counts per ward and status (occupied, free, cleaning, reserved) and a total show, matching the board.
- Given no wards or no beds, then an empty state.
- Given a status date filter, a future or invalid date gives 422.
- Given wrong role (doctor if not allowed), then 403.
- Given other clinic (CAC-1), Read-only (CAC-2, reads allowed), slow network (a loading state with retry).
## Notes for design
Counts only; no patient names in the census.

# End-to-end IPD test scenario outline (admission to discharge, for QA)
Setup: two clinics (T1 hospital with IPD On, T2 another hospital), roles owner, doctor, nurse, front desk in T1; one ward, three beds, one package; fake patients only. Each step lists the check. Steps for PROPOSED stories are marked [PROPOSED] and run only after they are approved and built; the rest cover existing behaviour and QA must prove it.
1. OPD: doctor gives Admit advice (SAH-67-3). Check: appears in the admission queue; T2 user cannot see it (404).
2. Front desk admits with deposit and consent tick (SAH-23). Check: bed Occupied, deposit in running bill; second admit of the same patient 409; invalid deposit 422.
3. [PROPOSED A-5] Record admission consent. Check: signer stored; missing signer 422.
4. [PROPOSED A-1] Doctor writes admission note. Check: version history; nurse 403.
5. [PROPOSED A-4] Optional: flag MLC. Check: pill shows; front desk sees no reason.
6. Doctor writes a medicine order (SAH-31). Check: allergy and duplicate warnings; pharmacist check state.
7. Nurse records vitals; a high score raises an early warning (SAH-30, 33). Check: escalation shows in Safety.
8. Nurse gives a dose with wristband scan and second nurse (SAH-32, 35, 38). Check: wrong wristband refused; the same nurse cannot be the second check.
9. Add charges; deposit-low alert (SAH-25). Check: running bill totals; alert appears at the configured percent.
10. [PROPOSED A-9] Order a lab test and a diet; nurse marks done.
11. Transfer to another bed (SAH-26). Check: old bed Cleaning, rate change per setting; [PROPOSED A-8] handover note written and acknowledged.
12. [PROPOSED A-10] Set isolation; check pill and deep-clean note at discharge.
13. Emergency access by a nurse of another ward (SAH-34). Check: reason required; shows in emergency access review.
14. Doctor signs the discharge summary (SAH-27). Check: the nurse cannot tick (403); changing type unsigns it.
15. Discharge Normal with refund by non-owner: 403 `refund-needs-owner`; owner refunds. Check: final bill frozen, bed Cleaning, follow-up created, a second discharge 409 `not-admitted`, a changed total 409 `bill-changed`.
16. [PROPOSED A-3 / A-2] Repeat the stay with LAMA and with Death. Check: required records, no follow-up for Death, patient marked deceased.
17. [PROPOSED A-7] Print interim bill (while admitted), summary and final bill (after). Check: frozen figures match.
18. [PROPOSED A-6] Open the discharged list; reprint. Check: T2 user 404; empty state; search validation.
19. Switch IPD to Read-only. Check: every write above gives 409 `ipd-readonly`; discharged records still readable and printable.
20. Slow network: throttle the discharge request and tap twice. Check: collected once.
21. No PII: search logs and audit entries for the test patient's name and mobile; expect none.
