# SAH-51 · Stories: Finish an OPD consultation (check before completing, sign and next step, autosave, one signer, addendum, follow-up and prescription print)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-1, BR45-5, BR45-16, BR45-18, BR45-20, BR45-23, BR45-29; decided sign-offs SO-45-13, SO-45-14, SO-45-27; answered questions OQ-45-22, OQ-45-25; answered, default accepted (DEC-039 addendum): OQ-45-27, OQ-45-30; carried over from SAH-43: OQ-43-11 registration number, OQ-43-28 offline draft, OQ-43-32 follow-up reason; BRD FR-16 to FR-18 and section 8 in `docs/handoffs/SAH-43-input-doctor-emr-brd.md`). Module: opd (workflow, follow-ups and billing through their existing logic and `api`). Phase 5. Depends on SAH-47 (footer and Save draft), SAH-48, SAH-50, SAH-55, SAH-56; SAH-9, SAH-12 (workflow), SAH-14 (follow-ups), SAH-10 (billing), SAH-43-2 (ranking). The finish of IPD rounds and Emergency is SAH-67; the integrations in detail are SAH-68.
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorGeneral "Complete visit" and "Check before completing"; the other doctor screens have their own complete dialogs, named in the specialty stories).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details (`clinic.consultation.write`) complete and sign, on their own patients (owner: all). Nurse, front desk, billing, pharmacist and counsellor get 403 on a direct call, except the read-only reprint in SAH-51-6. "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body.
Rule tag: "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (BR45-4) wherever a clinical value appears. The BRD finish rules (FR-16 to FR-18) are the base for what the mockup does not draw.

### SAH-51-1 · As a doctor, I want "Complete visit" to show what will happen and check what is missing, so that I never sign an incomplete record
Priority: Must
Screen: EMR mockup #DoctorGeneral, "Complete visit", dialog "Check before completing" with "Back to edit" and "Print Rx & call next patient".
## Acceptance criteria
- Given something required is missing (SAH-47-4) or a drug-allergy alert is unresolved (SAH-48-2), when the doctor taps "Complete visit", then the dialog does not open, a message lists the missing sections (or reads "Remove the drug or confirm the allergy alert before completing."), the first missing section's part opens and that section is highlighted. (BR45-20)
- Given everything required is done, when the doctor taps "Complete visit", then the dialog "Check before completing" opens with the patient's name and the department (for example "[Patient name] · General Medicine") and a summary that depends on the department: for General Medicine the rows Complaints, General exam, Local findings, Diagnosis, Tests & services, Medicines and Follow-up with a short text each; the other departments show the summary named in their own stories (SAH-69-3, SAH-61-4, SAH-62-4, SAH-63-3, SAH-71-3, SAH-70-2); the generic departments show one row per shown section with its status. (BR45-20)
- Given the summary row text, then it is read from the record (for example "Headache 2 days" for Complaints, the diagnosis names for Diagnosis, "Not recorded" for an empty optional section); nothing is typed here. (BR45-20)
- Given the dialog shows advice-only information (for example unresolved ANC alerts, SAH-61-1), then it lists them as information and does not block. (BR45-20)
- Given "Back to edit", then the dialog closes and nothing is saved or signed. (BR45-20)
- Given the primary button, then its label is "Print Rx & call next patient" for General Medicine as drawn (the other departments use the label named in their stories); it is the confirm of SAH-51-2. (BR45-20)
- Given the checkbox "Send prescription to patient on WhatsApp" of the dialog, then it is shown as drawn, switched off with the note "Needs the WhatsApp service". **Stays as built, switched off. Sending is Later (DEC-049).** (BR45-29)
- Given the prescription "prints in Marathi with English drug names" (the footer text of SAH-48-3), then the dialog shows the chosen print language and the confirm prints in it. (BR45-23)
- Given the visit is already signed, then "Complete visit" is not shown; the visit is read-only (SAH-46-1). (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, or a doctor who is not the visit's doctor (not owner), 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given the dialog, then it holds patient data on screen only; nothing in it is written to a log line or analytics event. (BR45-5)
## Notes for design
The dialog is a modal with a focus trap and Escape to close. "Call next patient" has no matching function in the Queue today: the confirm returns to the Queue where the next waiting patient is at the top (OQ-45-34, answered: default accepted, DEC-039 addendum; see `docs/requirements/SAH-45.md`); the label stays as drawn.

### SAH-51-2 · As a doctor, I want to sign the consultation and move the patient to the next step, so that the record is final and the queue moves
Priority: Must
Screen: EMR mockup #DoctorGeneral, "Print Rx & call next patient", message "Visit completed · calling next patient".
## Acceptance criteria
- Given the dialog is confirmed, then in one action: the record is signed (status signed, the doctor who signed, the date and time in IST), the snapshot and all values are frozen, the visit moves to its next workflow step through the existing workflow (SAH-9, SAH-12) honouring the clinic's skip rules and fee timing (usually billing), and the prescription prints in the chosen language. (BR45-1)
- Given the follow-up chosen and the services ticked, then the follow-up and bill lines are created as SAH-51-6 and SAH-68-2 say, in the same action. (BR45-23)
- Given the action succeeds, then the message reads "Visit completed · calling next patient" and the screen returns to the Queue with the same filter and view, the signed visit shown as Done; the mockup's words "Rx sent" are not used until a send exists (Later, DEC-049). (BR45-1)
- Given the action fails part-way (for example the workflow move is refused), then nothing is half done: the visit stays unsigned and editable, an error says what failed and a retry is offered. (BR45-1)
- Given the same confirm is tapped twice or retried after a lost network, then exactly one signature, one workflow move, one set of bill lines and one follow-up result. (BR45-1)
- Given the signed visit, then the case paper shows the doctor's name and the signing time in the signature area (SAH-55-1), and the registration number or "—" when none is saved (OQ-43-11 default: signing is not blocked). (BR45-25)
- Given the signed visit, then the master items it used are counted for ranking "Your most used" once (SAH-43-2); items typed free are not counted (SAH-56-4). (BR45-22)
- Given the existing button "Consult done" on the OPD card, then for a visit that the EMR can sign it is replaced by "Open EMR" for the doctor and owner; the button is kept for any visit the EMR cannot open (for example a clinic with the EMR off). (SO-45-27 follow-up, flagged for the human.) (BR45-16)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403 on sign; a doctor who is not the visit's doctor (not owner), 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given the audit, then one entry records who, when, visit id and "signed"; no clinical text. (BR45-5)
## Notes for design
Signing is a state change with side effects in other modules and must run as one transaction or a safe sequence with retry. Ranking is counted at sign by an event.

### SAH-51-3 · As a doctor, I want my work saved automatically and a clear Saved, Saving or Offline state, so that an interruption does not lose it
Priority: Must
Screen: EMR mockup #DoctorGeneral, footer "Save draft" and the status text beside it (BRD FR-18, section 8).
## Acceptance criteria
- Given there are unsaved changes, then the draft is saved automatically every 30 seconds and when the doctor switches part (1 Assessment or 2 Treatment), without checking required sections. (BR45-20)
- Given the footer, then a small text shows "Saving…" while a save runs, "Saved" with the time after it succeeds and "Offline" when the connection is lost; the text is also read by a screen reader. (BR45-20)
- Given the connection is lost, then the screen keeps the values on screen and shows "Offline. Your changes are not saved yet." until the connection returns, then saves at once and shows "Saved"; values are held in the open page only and are not written to browser storage (OQ-43-28 default: no clinical text on the device). (BR45-5)
- Given the page is closed while offline with unsaved changes, then the browser warns that unsaved changes will be lost. (BR45-5)
- Given an autosave fails for another reason (for example a validation or a 409), then the text shows "Not saved" with the reason and a retry; the values stay on screen. (BR45-20)
- Given "Save draft" is tapped, then a save happens at once with the message "Draft saved" (SAH-47-5) and the autosave clock restarts. (BR45-20)
- Given the visit is signed or read-only, then there is no autosave. (BR45-18)
- Given autosave, then the audit is written for the explicit "Save draft" only and not every 30 seconds, so the audit does not fill with noise; one audit entry is written at open and at sign. (BR45-5)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then no log line holds the draft's content. (BR45-5)
## Notes for design
An offline draft kept on the device (as the BRD said, "keeps the draft locally and syncs when back") is a security question (OQ-43-28); the default above avoids it. If the human allows local keeping, only the third criterion changes.

### SAH-51-4 · As a doctor, I want only one doctor to edit and sign a visit at a time, so that two people never overwrite or sign twice
Priority: Must
Screen: EMR mockup #DoctorGeneral (a state of the whole frame; not drawn; BRD section 8: "Two doctors cannot sign the same visit; a second editor sees the record read-only with who has it open").
## Acceptance criteria
- Given a doctor opens a visit for writing, then the visit is marked as held by that doctor; a second doctor who opens it (SAH-46-2: both can open) sees the EMR read-only with the banner "Open for editing by Dr. <name>" and no Save draft, Complete or change controls. (BR45-16)
- Given the first doctor leaves the EMR, completes the visit, or has not saved for 2 minutes (four missed autosaves; the value is the analyst's, flagged for the human), then the hold ends and the second doctor can tap "Edit this visit". (BR45-16)
- Given the owner (with doctor details), then the owner can take over a held visit with "Take over", after a message "Dr. <name> is editing. Take over?"; the first doctor's screen then becomes read-only and the take-over is audited. (BR45-16)
- Given two sign requests arrive at the same moment, then exactly one succeeds and the other gets 409 with "Already signed by Dr. <name>"; the visit is signed once. (BR45-16)
- Given a doctor edits an old copy (the visit changed meanwhile), then a save is refused with 409 and the screen offers "Reload"; no data is overwritten silently. (BR45-20)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given the banner, then it shows the doctor's name (a staff name, not patient data) and is not written to a log line. (BR45-5)
## Notes for design
The mechanism (lock row, version number, both) is the architect's. IPD and Emergency use the same rule (SAH-67).

### SAH-51-5 · As a doctor, I want to correct a signed record with an addendum, so that the original stays untouched and the correction is traceable
Priority: Must
Screen: EMR mockup #CasePaper (signature area) and #DoctorGeneral (read-only visit); the addendum is not drawn (BRD section 8: "a signed record is never edited; corrections are an addendum with reason, user and time").
## Acceptance criteria
- Given a signed visit, when the visit's doctor or the owner taps "Add addendum", then a form asks for a "Reason" (required, short text) and the "Correction or addition" (required text); the original values are not editable. (BR45-18)
- Given the doctor saves, then the addendum is stored with the reason, the text, the user and the date and time in IST, and it can never be changed or deleted. (BR45-18)
- Given a signed visit with addenda, then the case paper (SAH-55) and the history (SAH-50-4) show them under the signature as "Addendum · <date and time> · Dr. <name>: <reason>. <text>", newest last; the printed case paper includes them. (BR45-18)
- Given a correction needs structured data to change (for example a wrong drug), then the addendum text states it and the original rows stay; the product does not rewrite the original (the analyst's reading of "addendum"; flagged for the human). (BR45-18)
- Given an addendum, then the visit remains signed and its workflow step and bill lines are not changed by it; billing changes go through billing's own rules (SAH-10). (BR45-1)
- Given the reason or text is empty or over the allowed length, then the form shows an error and nothing is saved (400 on a direct call). (BR45-18)
- Given a doctor who is not the visit's doctor and not the owner, a nurse, front desk, billing, pharmacist or counsellor, 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given an addendum, then one audit entry records who, when, visit id and "addendum added" (never the text), and the text is never written to a log line or analytics event. (BR45-5)
## Notes for design
The Treatment course (SAH-58-2) refers to this rule for a Done sitting. Whether the owner may add an addendum to another doctor's visit follows `clinic.consultation.write` (DEC-038).

### SAH-51-6 · As a doctor, I want to choose a follow-up and print the prescription, so that the patient leaves with the next date and the paper
Priority: Must
Screen: EMR mockup #GeneralTreatment, section "Follow-up" (3 days, 7 days, 15 days, 1 month, 3 months) and the print of the prescription; reprint through the Queue.
## Acceptance criteria
- Given the section Follow-up, then the choices 3 days, 7 days, 15 days, 1 month and 3 months show (SO-45-13, the mockup list replaces the BRD list), one selected or none; the note reads "Books an appointment on that day." and the SMS reminder part is shown disabled "SMS reminders: later" (OQ-45-27). (BR45-23)
- Given a follow-up is chosen, when the visit is signed, then one follow-up is created through the existing follow-up logic (SAH-14) with due date = the visit day plus the chosen interval (1 month and 3 months by calendar month), the same doctor, and the reason "Review after treatment" (the diagnosis is not put in the reason, OQ-43-32 default); never more than one follow-up per visit. This changes the SAH-14 default of "visit day plus the clinic window" only when the doctor chose an interval; with no choice the clinic's window and the clinic's follow-up step apply as today. (BR45-23)
- Given the follow-up window of the clinic (SAH-7 fee settings), then a return inside it follows the clinic's follow-up fee rule through the existing token logic (SAH-9); nothing is recomputed here. (BR45-1)
- Given the choice "Books an appointment on that day", then an appointment is booked on the first free slot of that day or the next working day through Appointments (SAH-11) and the follow-up shows the booked time; when no slot is free within 7 days the follow-up keeps the date only and the message says "No free slot found. Book the appointment from Appointments." (analyst default, flagged). (BR45-23)
- Given the prescription prints, then the headings are in the visit's language (English, Marathi or Hindi, SAH-55-2, SAH-48-3), the medicine names in English capitals when the clinic's capitals setting is on, only the sections with Print on prescription on (SAH-53), the follow-up date, the doctor's name, qualification, registration number (or "—") and the signature line "Not valid without signature" until signed. (BR45-23)
- Given a signed visit, then the doctor, visiting doctor, owner, front desk and pharmacist can reprint the signed prescription read-only from the visit; billing and nurse cannot (analyst default, flagged for the human); a reprint is audited with who, when and visit id and shows "Reprint". (BR45-16)
- Given the doctor changes the follow-up after signing, then it is an addendum (SAH-51-5) and the follow-up record is changed only by the follow-up logic's own rules. (BR45-18)
- Given a nurse, billing or counsellor, 403 on choose; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then no diagnosis or drug text is written to a log line, analytics event or the follow-up reason. (BR45-5)
## Notes for design
Calendar month arithmetic uses Asia/Kolkata dates. The change to SAH-14's due date is additive and is for the architect to confirm; it is not an edit of the approved SAH-14 stories.

## Out of scope for SAH-51
WhatsApp delivery of the prescription (SAH-68-5, Later, DEC-049); ABHA and the ABDM record (SAH-68-4, SAH-68-6, Later, DEC-049); IPD round and Emergency finish (SAH-67); billing line creation in detail (SAH-48-4, SAH-68-2); SMS reminders (OQ-45-27); handwriting and voice; a "call next patient" announcement in the Queue.

## Traceability
BR45-1 -> SAH-51-2, -3, -5, -6. BR45-5 -> SAH-51-1 to SAH-51-6. BR45-16 -> SAH-51-1 to SAH-51-6. BR45-18 -> SAH-51-1, -3, -5, -6. BR45-20 -> SAH-51-1, -3, -4. BR45-22 -> SAH-51-2. BR45-23 -> SAH-51-1, -2, -6. BR45-25 -> SAH-51-2. BR45-29 -> SAH-51-1.

## Open questions
In `docs/requirements/SAH-45.md`: SO-45-13 and SO-45-27 (decided), OQ-45-27 (SMS: Later), OQ-45-30, OQ-45-34 ("call next patient"; answered: default accepted, DEC-039 addendum). From SAH-43 (defaults applied): OQ-43-11, OQ-43-28, OQ-43-32. Later (DEC-049): the WhatsApp send.

## Status log
- 12 Nov 2026 · outline by the business-analyst; updated after DEC-039.
- 12 Nov 2026 (phase 5) · full stories written from the source of EMR mockup #DoctorGeneral (Complete dialog) and BRD FR-16 to FR-18 and section 8. Awaiting human approval of `stories`.
