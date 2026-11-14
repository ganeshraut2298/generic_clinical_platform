# SAH-47 · Stories: EMR shell and template-driven sections (frame, parts, section list, generic field renderer, required and done, Save draft)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-18 to BR45-20, BR45-5, BR45-6; decided sign-offs SO-45-3, SO-45-4, SO-45-11, SO-45-15, SO-45-16, SO-45-25, SO-45-41; answered questions OQ-45-14, OQ-45-23, OQ-45-25, OQ-45-26: "answered: default accepted (DEC-039 addendum)"; answered: default accepted (DEC-039 addendum): OQ-45-27, OQ-45-28, OQ-45-30; nurse vitals pre-fill left out of phase 2 by the human's decision, placeholder SAH-72). Module: opd. Phase 2. Depends on SAH-46 and SAH-53. Rules marked "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" are clinical values built as drawn (DEC-039 answer 3). Marathi and Hindi: the mockup draws the EMR entry screens in English; Marathi and Hindi appear on the printed outputs (SAH-55, SAH-48-3), not here. This story is large and will probably be split in two (SAH-47-1 to SAH-47-3, then SAH-47-4 to SAH-47-6) by adding an id at the end of the block, not renumbering.
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorGeneral and #GeneralTreatment for the frame; generic rendering for the departments without a specialty screen).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details open and write the EMR (see SAH-46). "Not signed in" gets 401. The clinic always comes from the logged-in user.
The doctor screens in the mockup carry their own top bar (clinic name, "Queue (n waiting)", "Appointments", "Patients" and department tools such as "ANC register"). In the product the EMR opens from the Queue card inside the normal Sahaj app shell, with a Back button; that bar is not built, and neither are its department links (decided by the human, SO-45-16, DEC-041 answer 2).

### SAH-47-1 · As a doctor, I want the patient header with allergy and alert tags, so that I see who I am treating and what is dangerous
Priority: Must
Screen: EMR mockup #DoctorGeneral, header strip.
## Acceptance criteria
- Given an open EMR, then the header shows the patient's initials, name, age, sex, token, UHID (or "New patient"), the doctor's name and room, and the visit line (for example "Follow-up, 3rd visit"). (BR45-19)
- Given the patient has a recorded allergy, then a red tag "Allergy: <text>" shows; given chronic conditions are recorded, an amber tag "Known: <text>" shows; given an earlier visit exists, a tag "Last visit: n days ago" shows. (BR45-19)
- Given no allergy is recorded, then the tag does not say "No known allergy" unless "none" was explicitly recorded; what it says instead is decided by OQ-45-23 (decided: until structured allergy exists and OQ-43-22 is answered, nothing recorded shows no allergy tag). (BR45-19)
- Given the doctor ticks an allergy in the Allergies section, then the header tag updates at once. (BR45-19)
- Given the tag "ABHA linked" as drawn, then it shows only for a patient whose ABHA is linked with the patient's consent. **Later (DEC-049)** (ABHA / ABDM linking); until then the tag is not shown. (BR45-5)
- Given an open EMR, then it shows inside the normal Sahaj app shell (sidebar and header) with a Back button that returns to where the doctor came from (Queue, IPD → Admitted or IPD → Emergency); the mockup's doctor-screen top bar ("Queue (n waiting)", "Appointments", "Patients" and the department links such as ANC register, OT schedule or Lab work) is not built and no disabled "Later" links are shown for it (SO-45-16, human's decision in DEC-041 answer 2). Back with unsaved changes follows SAH-47-5. (BR45-19)
- Given "Admission advised" or "Emergency" is set on the visit (SAH-52), then it shows as a tag. (BR45-19)
- Given the header, then it does not appear in any log, analytics event or error message with the patient's name. (BR45-5)
- Given a doctor of clinic B, then clinic A's patient is never returned (404). (BR45-5)
## Notes for design
The header keeps its content when the doctor scrolls. Tags use text as well as colour.

### SAH-47-2 · As a doctor, I want two parts, 1 Assessment and 2 Treatment, with done counts, so that I know how far I am
Priority: Must
Screen: EMR mockup #DoctorGeneral and #GeneralTreatment, part tabs and "Continue to Treatment →".
## Acceptance criteria
- Given an open EMR, then two tabs show: "1 Assessment" with a subtitle and "2 Treatment" with a subtitle; each shows a badge "n of m required" counting required, shown sections of that part from the snapshot. (BR45-19, BR45-20)
- Given every required section of a part is done, then a tick replaces the number on that tab. (BR45-20)
- Given a part has no required section, then the badge reads "0 of 0 required" and the tick shows. (BR45-20)
- Given the doctor is in Assessment, then a button "Continue to Treatment →" shows at the end; in Treatment, a line "From assessment:" with a short recap (complaints and examination in one line) and a button "← Back to assessment" show. (BR45-19)
- Given a doctor taps a tab, then only that part's sections show, in the snapshot's order. (BR45-19)
- Given the left-hand section list, then it shows both parts as headings with "done/total" (for example "2/4") and each section with its status; tapping a section switches to its part, scrolls to it and highlights it for a short moment. (BR45-19)
- Given the snapshot moved a section to the other part (SAH-45-3), then it shows in that part. (BR45-18)
## Notes for design
The tab titles and subtitles in the mockup (General Medicine: "Complaints and examination" and "Diagnosis, medicines, tests and services") come from the department; other departments use the part descriptions of SAH-45-2.

### SAH-47-3 · As a doctor, I want the sections my clinic published for my department, in order, with the required flag, so that I fill what is asked
Priority: Must
Screen: EMR mockup #DoctorGeneral and #Main "Doctor screen preview".
## Acceptance criteria
- Given a snapshot, then only sections that were Show on appear, in the snapshot's order, each as a card with its name, a red star when required, and a status tag Done, Required or Optional. (BR45-19)
- Given a section marked Collapsed by default, then its card opens collapsed and the doctor taps to open it; a section not marked collapsed opens expanded. (BR45-19)
- Given a section has fields in the library, then it renders them with a generic renderer by field type (for example Text, Number, Number + unit, Dropdown, Rich text, Search list, File), with the field's label, meta text and required marker. (BR45-6)
- Given the department has a specialty editor built in a later story (for example the dental chart, ANC card), then until it exists that section renders its library fields with the generic renderer and nothing is lost. (BR45-6)
- Given the eight departments with no specialty screen (Orthopaedics, Ophthalmology, ENT, Dermatology, Diabetology, Cardiology, AYUSH, Psychiatry), then their whole EMR is rendered this way. (BR45-6)
- Given a section with Copy from last visit on, then the values of the patient's last signed visit in the same department are pre-filled, marked "Copied from <date>", and the section is Done only after the doctor opens and confirms or edits it; only the last signed visit of the same department is used (OQ-45-26, decided). (BR45-19)
- Given a section's Who can fill setting, then it is stored and shown as a small label on the card, and the doctor can still edit every section; no role other than the doctor writes the EMR in phase 2 (OQ-45-14, decided). (BR45-19)
- Given the section Vitals (or the vitals rows of a General examination section), then in phase 2 it starts empty and the doctor types the values; vitals the nurse recorded in the existing vitals step are **not** pre-filled and the "Recorded by <nurse>" line is not shown. Human's decision (DEC-039 addendum, SO-45-43); reading the nurse's vitals is the separate future story SAH-72. (BR45-19)
- Given a Compliance section (for example Consent forms, PCPNDT Form F) is in the snapshot, then it renders as a section; what it does is in its own later story (SAH-61, SAH-64; OQ-45-7 decided). (BR45-19)
- Given the section Vitals (or the vitals rows of a General examination section) shows BP, pulse, SpO2, temperature, respiratory rate and pain, then a value outside the limits below is coloured and labelled: BP systolic 140 or above or diastolic 90 or above "High", systolic below 90 "Low"; pulse above 100 "High", below 60 "Low"; SpO2 below 95 "Low"; temperature 99.5 °F or above "Fever", below 96 "Low"; respiratory rate above 20 "High". The limits are adult limits; they are shown by text as well as colour. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-1; replaces the BRD limits, SO-45-11). (BR45-4)
- Given a field of type photo, file or signature image, then it shows the control as drawn disabled with a "Later" note and stores no file until the documents service exists (OQ-45-28). (BR45-6)
- Given a value typed into a field, then it is stored with the visit (tenant data, row-level security) and never written to a log. (BR45-5)
- Given a field value is invalid (a number out of the allowed range, text over the allowed length), then the field shows an error and the value is not saved. (BR45-20)
## Notes for design
Nurse vitals are not read by the EMR in phase 2 (placeholder story SAH-72); the generic renderer treats Vitals like any other section. Field types come from the SAH-53-1 library; the architect decides the storage of values (typed columns or a document per section) and the print/case-paper mapping. The vitals limits are the mockup's (tagged rule above); in Paediatrics the adult limits stay until SAH-62 says otherwise (OQ-43-23). Dental chart, immunisation and similar sections default to part 2 as seeded (SO-45-41).

### SAH-47-4 · As a doctor, I want to see what is still needed, so that I complete the right sections
Priority: Must
Screen: EMR mockup #DoctorGeneral, sticky footer.
## Acceptance criteria
- Given a section from the snapshot, then it is Done when every required field of the section is filled; a section with no required field is Done when at least one field is filled; Optional sections never block (OQ-45-25, decided). (BR45-20)
- Given the doctor taps "Continue to Treatment →", "← Back to assessment" or a tab while required sections are missing, then the move is allowed; only Complete is blocked (the mockup's Dental screen lets the doctor go on with assessment gaps while the footer lists them). (BR45-20)
- Given at least one required section is not done, then the footer shows in red "Still needed: <sections>." listing them by name in lower case. (BR45-20)
- Given every required section is done, then the footer shows in green "Ready to complete." (BR45-20)
- Given the doctor taps Complete while something is missing, then nothing is completed, the first missing section's part opens and that section is highlighted. (BR45-20)
- Given the Complete button when all is done, then what it does is SAH-51; in this story it is shown but does not sign. (BR45-20)
- Given a hidden section (not in the snapshot), then it never appears in "Still needed". (BR45-18)
- Given required sections of the snapshot change in the clinic after the visit opened, then "Still needed" still follows the snapshot. (BR45-18)
## Notes for design
In General Medicine the mockup requires four things (Chief complaints, General examination, Diagnosis, Prescription) and also blocks completion when a drug clashes with an allergy; the allergy part is SAH-48-2. "Required" blocks Complete only, not moving between parts (SO-45-4, decided: the published configuration decides what is required).

### SAH-47-5 · As a doctor, I want to save a draft and come back exactly where I was, so that I can be interrupted
Priority: Must
Screen: EMR mockup #DoctorGeneral, "Save draft".
## Acceptance criteria
- Given any state of the form, when the doctor taps "Save draft", then everything entered is stored without checking required sections and the message reads "Draft saved". (BR45-20)
- Given a draft, when the doctor opens the EMR again (from the Queue card), then it shows the same part, the same section values and the same position in the list as when it was saved. (BR45-20)
- Given the doctor goes back to the Queue with unsaved changes, then a message asks whether to save the draft first. (BR45-20)
- Given the network is slow or lost during Save draft, then a busy state shows, a failure shows an error, and the values stay on screen; nothing is lost silently. (BR45-20)
- Given a draft belongs to a visit of another doctor, then it cannot be opened for writing (403). (BR45-16)
- Given a draft of clinic A, then clinic B cannot read it (404). (BR45-5)
- Given Save draft, then one audit entry is written with who, when and the visit id (no clinical text). (BR45-5)
## Notes for design
Autosave every 30 seconds, the "Saved / Saving / Offline" state, one signer and the addendum are SAH-51. Offline drafts on the device are an open security question (OQ-43-28).

### SAH-47-6 · As a doctor, I want the right-hand panel with tabs Past visits and Case paper, so that history and the paper stay beside what I type
Priority: Must
Screen: EMR mockup #DoctorGeneral, right panel (tabs "Past visits · OPD + IPD" and "Case paper · n of 10 filled · live").
## Acceptance criteria
- Given an open EMR, then the right half shows two tabs, "Past visits" with the subtitle "OPD + IPD" and "Case paper" with the subtitle "n of 10 filled · live"; the panel stays in view and scrolls on its own. (BR45-19)
- Given the mockup opens the right panel on "Past visits" (SO-45-15, decided: mockup wins; the withdrawn SAH-44-3 setting "right panel first" is dropped), then the tab shown first is Past visits; the doctor's choice of tab lasts for that visit only and is not stored. (BR45-19)
- Given the Past visits and Case paper stories are not built yet, then each tab shows an empty state "Nothing to show yet"; once SAH-50 and SAH-55 exist they fill in. (BR45-19)
- Given a narrow screen (tablet), then the panel moves below the entry area with the same tabs (breakpoints: BRD 1180 and 720 px, to be confirmed by the architect). (BR45-19)
## Notes for design
Some departments add a third tab "Quick tools" in the mockup (helpers for the visit); it is part of the specialty stories (phase 4).

## Out of scope for SAH-47
Specialty editors and tools (phase 4); prescription, services and allergy check (SAH-48); past visits and case paper contents (SAH-50, SAH-55); signing, autosave, one signer, addendum (SAH-51); visit type, Admit to IPD (SAH-52); handwriting and voice; WhatsApp, ABHA, Marathi and Hindi.

## Traceability
BR45-5 -> SAH-47-1, -3, -5. BR45-6 -> SAH-47-3. BR45-16 -> SAH-47-5. BR45-18 -> SAH-47-2, -4. BR45-19 -> SAH-47-1 to SAH-47-3, -6. BR45-20 -> SAH-47-2 to SAH-47-5.

## Open questions
In `docs/requirements/SAH-45.md`, all answered: OQ-45-14 (who can fill), OQ-45-23 (allergy), OQ-45-25 (what Done means for a generic section), OQ-45-26 (copy from last visit); decided sign-offs SO-45-3, SO-45-4, SO-45-11, SO-45-15, SO-45-16, SO-45-25, SO-45-41, SO-45-43 (no nurse vitals pre-fill in phase 2). Also answered, default accepted (DEC-039 addendum): OQ-45-27 (SMS, handwriting, voice, teleconsult: Later), OQ-45-28 (registers and services without a module: Later), OQ-45-30 (44 px). In SAH-43: OQ-43-22 (structured allergy), OQ-43-23 (child limits), OQ-43-28 (offline draft); OQ-43-19 is superseded by OQ-45-25.

## Status log
- 12 Nov 2026 · written by the business-analyst from the source of EMR mockup #DoctorGeneral and #GeneralTreatment, DEC-039.
- 12 Nov 2026 (later) · updated after the human's answers: OQ-45-14, 23, 25, 26 written in as decided rules; vitals limits of the mockup applied (tagged); right panel opens on Past visits; ABHA tag waits for DEC-040 (now Later, DEC-049, 13 Nov 2026); top bar and no-module links added. Awaiting human approval of `stories`.
- 12 Nov 2026 (DEC-041) · SAH-47-1 and the intro aligned with DEC-041 answer 2: the EMR opens inside the app shell with a Back button; the mockup's doctor-screen top bar and its links are not built.
- 12 Nov 2026 (follow-up pass) · DEC-039 addendum applied: OQ-45-27, 28, 30 marked answered (default accepted); nurse vitals pre-fill removed from SAH-47-3 (phase 2) and moved to the placeholder story SAH-72.
