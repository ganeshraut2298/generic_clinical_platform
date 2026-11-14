# SAH-67 · Stories: IPD round and Emergency finish, Admit to IPD sheet, Emergency visit type and triage
Requirement: `docs/requirements/SAH-45.md` (rules BR45-1, BR45-5, BR45-16, BR45-18, BR45-20, BR45-21, BR45-23; decided sign-offs SO-45-17, SO-45-18, SO-45-32; answered questions OQ-45-22, OQ-45-23; answered, default accepted (DEC-039 addendum): OQ-45-28, OQ-45-30, OQ-45-31 in `docs/requirements/SAH-45.md`; carried over from SAH-43: OQ-43-3, OQ-43-24, OQ-43-25, OQ-43-26; OQ-43-27 answered, default accepted: DEC-039 addendum). Module: opd (reads and writes ipd only through its `api` and events). Phase 5. Depends on SAH-51 (finish), SAH-52 (encounter frame, Admit button), SAH-23 (admission), SAH-28 (Emergency), SAH-30 (nursing chart), SAH-31 (IPD medicine orders), SAH-34 (ward scope and break-glass), SAH-25 (running bill). **The mockup draws no IPD or Emergency finish.** The BRD (FR-02, FR-04, FR-05, FR-17, approved in DEC-036) is the source (SO-45-17, decided); the mockup's "Admission request" with admission type and deposit (Treatment course and Surgery screens) is the base for the sheet. **Added 12 Nov 2026 at the end of the block (no renumbering): SAH-67-4 (Emergency visit type and triage), the part SAH-52-2 left for this story.**
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorGeneral; the finish of IPD and Emergency is not drawn: BRD FR-04, FR-05, FR-17 are the source; "Admission request" as in EMR mockup #TreatmentCourse and #DoctorSurgery).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details (`clinic.consultation.write`) finish an IPD round or an Emergency assessment. The existing admit roles of SAH-23 and SAH-28 are owner, front desk and nurse (a doctor who is not the owner cannot admit; OQ-45-31). Ward scope and break-glass (SAH-34) apply to IPD data. "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body. IPD and Emergency need the IPD entitlement (on or trial); with IPD off there are no such cards.

### SAH-67-1 · As a doctor, I want to sign the day's progress note on a ward round and leave the patient admitted, so that the round shows done
Priority: Must
Screen: Queue (sidebar → Queue) → IPD → Admitted card → Open EMR (EMR mockup #DoctorGeneral frame, "Daily progress note", SAH-52-1); not drawn for IPD.
## Acceptance criteria
- Given the doctor opened the EMR from an admitted patient's card (SAH-46-4), when Complete is tapped, then the checks of SAH-51-1 run and the dialog "Check before completing" opens with the title "Sign today's progress note" and the summary of the day's note; the confirm button reads "Sign note"; the patient stays admitted. (BR45-21)
- Given the doctor confirms, then in one action: the day's progress note is signed (user, date and time in IST) and saved to the admission through the ipd `api`, the card in IPD → Admitted shows "Round done" for today, and the screen returns to the IPD list with the same filter. (BR45-21)
- Given one note per admission per calendar day (Asia/Kolkata; OQ-43-27 answered, default accepted: DEC-039 addendum), then opening the same admission on the same day after signing opens the note read-only with "Add addendum" (SAH-51-5); the next day opens a new draft with Copy from last visit rules (SAH-47-3) applied to the admission's notes. (BR45-18)
- Given the doctor added new medicines in the Prescription section of the IPD EMR, then at Complete each is submitted through the existing IPD medicine order path (SAH-31) with its allergy, duplicate and interaction checks and the pharmacist check; the EMR creates no second path (OQ-43-24, SAH-48-2). (BR45-1)
- Given a medicine is refused by the SAH-31 checks (a hard block), then the dialog lists it as "Not ordered: <reason>" and the confirm is disabled until the doctor removes it or resolves it by the SAH-31 rules; a medicine the checks accept with a warning follows SAH-31's own warning and override rules and the override is audited by SAH-31. (BR45-23)
- Given a medicine is accepted, then nursing sees it on the nursing chart and the pharmacist on the pharmacist check list, as SAH-31 already does; nothing else is sent. (BR45-1)
- Given the doctor ticked investigations in the Investigations section, then they are recorded in the day's note and shown to nursing as "Investigations advised" text; no order reaches a laboratory because Lab is Later (OQ-45-28, OQ-43-24 default). (BR45-6)
- Given the admission is discharged meanwhile, then Complete is refused with "This admission is closed" and the note stays a draft. (BR45-21)
- Given the doctor has no ward scope for this patient (SAH-34), then the EMR does not open (403); break-glass access is audited by the existing IPD path. (BR45-16)
- Given the same confirm is tapped twice or retried after a lost network, then one signed note, one set of medicine orders and one round mark result. (BR45-1)
- Given a nurse, front desk, billing, pharmacist or counsellor, or a doctor who is not the admission's doctor and not the owner, 403; another clinic's admission, 404; no login, 401. (BR45-16, BR45-5)
- Given the audit, then one entry records who, when, admission id, visit id, number of medicines ordered; no medicine names and no clinical text. (BR45-5)
## Notes for design
The ipd `api` gains a way to save a signed daily note and mark the round done (additive, for the architect). The Vitals the nurse recorded on the nursing chart pre-fill the note (SAH-68-1).

### SAH-67-2 · As a doctor, I want to pick an ER outcome before signing an Emergency assessment, so that the patient goes to the right place
Priority: Must
Screen: Queue (sidebar → Queue) → IPD → Emergency card → Open EMR (EMR mockup #DoctorGeneral frame, "Emergency assessment", SAH-52-1); not drawn.
## Acceptance criteria
- Given the doctor opened the EMR from an Emergency card (SAH-46-4), when Complete is tapped and every required section is done, then before the dialog the screen asks "ER outcome" with four choices: "Discharge from ER", "Admit to IPD", "Refer out" and "Send to OPD"; none is chosen at start and one must be chosen. (BR45-21)
- Given "Discharge from ER", then the case is closed through the existing action "Treat and release" of SAH-28 with the signed assessment; the patient's prescription prints as in SAH-51-6. (BR45-21)
- Given "Refer out", then the case is closed through the existing action "Refer out" of SAH-28; the referral line (name of the place, typed, and a note) goes on the case paper. (BR45-21)
- Given "Admit to IPD", then the admission sheet opens pre-filled (SAH-67-3) with admission type Emergency, the signed assessment is attached to the admission request and the ER case is handed to the admission step through the ipd `api` as in SAH-28 ("Admit" with `emergencyCaseId`); the ER case closes when the admission is made by the admit roles (OQ-45-31). (BR45-21)
- Given "Send to OPD", then the ER case is closed as "Sent to OPD" and a new OPD token is issued for the same patient and the same doctor through the OPD workflow (SAH-9); SAH-28 has no such closing reason today, so the ipd `api` gains one (additive, for the architect; the BRD lists the outcome). (BR45-21)
- Given the outcome is chosen, then the dialog of SAH-51-1 opens with the title "Sign emergency assessment" and a summary including the triage level (read-only) and the outcome; the confirm signs the assessment and acts on the outcome in one action. (BR45-21)
- Given the outcome action fails part-way (for example the ER case was closed by someone else), then nothing is half done: the assessment stays unsigned, the error says what changed and a retry is offered. (BR45-21)
- Given the same confirm is tapped twice or retried after a lost network, then one signature and one outcome result. (BR45-21)
- Given the BRD question OQ-43-3 (should Emergency also alert the front desk and nurse), then no extra alert is sent (default: no). (BR45-21)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403 on sign and on outcome (the existing SAH-28 actions stay available to their own roles on the ER page); another clinic's case, 404; no login, 401. (BR45-16, BR45-5)
- Given the audit, then one entry records who, when, case id, visit id and the outcome key (no clinical text). (BR45-5)
## Notes for design
The ER page of SAH-28 keeps its own buttons "Doctor has seen", "Treat and release", "Refer out" and "Admit"; this story is the doctor's path from the EMR to the same actions, not a second ER module.

### SAH-67-3 · As a doctor, I want Admit to IPD to open the admission sheet with the patient's details filled, so that I do not type them twice
Priority: Must
Screen: Queue (sidebar → Queue) (the "Admit to IPD" button in the frame header, SAH-52-4; the sheet is the existing IPD admission sheet; the mockup's "Admission request" with admission type and deposit in EMR mockup #TreatmentCourse and #DoctorSurgery).
## Acceptance criteria
- Given the clinic's IPD is on or in trial and the EMR is OPD or Emergency, when the doctor taps "Admit to IPD" (SAH-52-4), then the existing admission sheet opens pre-filled with the patient's name, age, sex and mobile number (read through the patient `api`), the admission type (Emergency when the visit is an Emergency visit or an ER case, otherwise Planned), the diagnoses entered in the visit and, when the OT booking exists, the procedure and stay (SAH-64-3); the doctor can change the admission type to Day care or "IPD n days" as the mockup's request shows. (BR45-21)
- Given the doctor is the owner or holds an admit role (OQ-45-31), then the sheet can be completed (bed, deposit, consent as SAH-23 requires) and the patient is admitted. (BR45-21)
- Given the doctor is a doctor or visiting doctor who is not the owner (OQ-45-31 default: the doctor cannot admit), then the sheet is saved as an admission request with the pre-filled facts and the doctor's note and the button reads "Send admission request"; owner, front desk and nurse see the request on the IPD admission screen and complete the admission with the bed and the deposit. (BR45-16)
- Given the request or admission is made, then the visit is marked "Admission advised" and the header tag shows "Admission advised"; the button shows a pressed state "Admission requested" or "Admitted" with the id (SAH-52-4). (BR45-21)
- Given the visit continues, then admission does not end the consultation: the doctor can still complete the visit; the visit's next workflow step follows SAH-51-2 and the admission follows SAH-23. (BR45-21)
- Given a deposit suggestion, then the admission sheet shows the deposit rule of SAH-23; the course rule (40% of the charge rounded to ₹ 500, SAH-58-3) applies only to a Treatment course advance. (BR45-27)
- Given an open request exists for the visit, then the button does not create a second one: it shows the existing request ("Admission requested"). (BR45-21)
- Given the clinic's IPD is off, then SAH-52-4 applies (sheet "OPD only" with "Advise admission + referral"); nothing is created in IPD. (BR45-21)
- Given the patient has an open admission already, then the button shows "Already admitted" with the admission id and opens that admission read-only. (BR45-21)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403 on the EMR button; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then the pre-filled name, mobile number and diagnoses are not written to a log line, audit value or analytics event; the audit holds ids and "admission advised". (BR45-5)
## Notes for design
The pre-fill is read-only data passed to the ipd `api`; the sheet itself is not rebuilt (SO-45-32). An "admission request" that a doctor leaves for admit roles is the mockup's term (Surgery and course screens); if SAH-23 has no such state, the architect adds it (additive).

### SAH-67-4 · As a doctor, I want to mark an OPD visit as Emergency and give a triage level, so that the patient moves to the top of the queue
Priority: Should
Screen: Queue (sidebar → Queue) (visit-type control of SAH-52-2; triage step of SAH-28; not drawn in the EMR mockup).
## Acceptance criteria
- Given an OPD visit in the EMR, when the doctor picks "Emergency" in the visit-type control (SAH-52-2, enabled by this story), then the existing triage step opens (SAH-28) and asks for a triage level from the clinic's triage levels; the visit cannot be saved as Emergency without one. (BR45-21)
- Given a triage level is set, then the visit moves into the "Emergency" group at the top of the same OPD queue, ordered most urgent first and then longest waiting (the rule of the ER queue, SAH-28), and the card shows the triage level as text and colour; no separate ER case is created (BRD FR-04; OQ-43-25 default: the OPD visit type Emergency and the ER case are different things). (BR45-21)
- Given the clinic has no triage settings (an OPD-only clinic), then the default levels of SAH-28 are used. (BR45-21)
- Given the doctor changes Emergency back to another visit type, then the visit leaves the Emergency group, keeps its queue position by arrival, and the triage level is kept in the audit; one audit entry records who, when, old and new type, level. (BR45-21)
- Given the visit is Emergency, then the header shows the tag "Emergency" and the triage level, and the control is read-only text "Emergency · <level>" for the doctor until changed back by the doctor or owner. (BR45-21)
- Given front desk, then they see the Emergency group on the Queue but cannot set the type from the EMR (SAH-52-2). (BR45-16)
- Given a nurse, billing, pharmacist or counsellor, 403 on the control; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then no log line holds the patient's name or the clinical reason. (BR45-5)
## Notes for design
Until this story is built, SAH-52-2 shows the Emergency option disabled with "Coming in a later step". OQ-43-25 (two meanings of Emergency) stays open with this default.

## Out of scope for SAH-67
The sheet's bed, deposit and consent steps (SAH-23); ER registration and the ER page (SAH-28); IPD medicine order checks (SAH-31); nursing chart (SAH-30); discharge summary (existing IPD); WhatsApp or ABHA (Later, DEC-049); the OPD finish (SAH-51).

## Traceability
BR45-1 -> SAH-67-1, -2. BR45-5 -> SAH-67-1 to SAH-67-4. BR45-6 -> SAH-67-1. BR45-16 -> SAH-67-1 to SAH-67-4. BR45-18 -> SAH-67-1. BR45-21 -> SAH-67-1 to SAH-67-4. BR45-23 -> SAH-67-1. BR45-27 -> SAH-67-3.

## Open questions
In `docs/requirements/SAH-45.md`: SO-45-17 (BRD table is the source), SO-45-18, OQ-45-22, OQ-45-23, OQ-45-28, OQ-45-30; **new OQ-45-31** (can a doctor admit from the EMR; default: the doctor sends an admission request, owner, front desk and nurse complete it). From SAH-43 (defaults applied): OQ-43-3, OQ-43-24, OQ-43-25, OQ-43-26; OQ-43-27 answered, default accepted (DEC-039 addendum).

## Status log
- 12 Nov 2026 · outline by the business-analyst from the BRD and DEC-039.
- 12 Nov 2026 (phase 5) · full stories written from BRD FR-02, FR-04, FR-05, FR-17 and the existing IPD stories (SAH-23, SAH-28, SAH-31); SAH-67-4 added at the end of the block; OQ-45-31 raised because the admit roles of SAH-23 and SAH-28 exclude a doctor. Awaiting human approval of `stories`.
