# SAH-49 · Stories: Dental screen, part 1 (dental chart, suggested treatments and treatment plan, 3D tooth view)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-4, BR45-5, BR45-6, BR45-19, BR45-20; clinical rule C-3; decided sign-offs SO-45-1, SO-45-14, SO-45-41; answered questions OQ-45-25, OQ-45-26 and OQ-45-30 default; answered, default accepted (DEC-039 addendum): OQ-45-28, OQ-45-30). Module: opd. Phase 4. Depends on SAH-47 (frame, parts, done rules), SAH-48 (Prescription, drug-allergy alert), SAH-56 (EMR list). **Oversized story split (12 Nov 2026): the estimate, rates, consent, lab work, "Complete & send to billing" and "Book next sitting" moved to SAH-69. SAH-49 keeps the chart, the plan and the 3D view.** The department is Dental (starter department "Dental", initials DE, 24 sections, SAH-53-1); every other Dental section (Intraoral, Extraoral, Periodontal and so on) renders with the generic renderer of SAH-47-3.
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorDental).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details (`clinic.consultation.write`) write the Dental screen, on their own patients (owner: all). Nurse, front desk, billing, pharmacist and counsellor get 403 on a direct call. "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body. A "dentist" is a doctor or visiting doctor of the Dental department.
Rule tag: "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (BR45-4). It is repeated on each clinical rule below. Tooth conditions, suggestions and phases are rule C-3.
Part: in the mockup the Dental chart sits in 2 Treatment (SO-45-41); the published layout decides (the owner can move it). The Dental screen's own part tabs read "Assessment" and "Treatment" as in SAH-47-2.

### SAH-49-1 · As a dentist, I want a dental chart with a condition for each tooth, so that I record what I find
Priority: Must
Screen: EMR mockup #DoctorDental, section "Dental chart (odontogram)", tooth picker, condition buttons, "Next tooth ›", shade, gum tone, mobility, "Numbers" switch.
## Acceptance criteria
- Given the section Dental chart is in the visit's snapshot, then it shows the title (red star when required, required at start in the Dental department), the hint "Tap a tooth, pick its condition on the right, then add treatment to the plan." and two tabs "Adult" (32 teeth) and "Child" (20 teeth); the tab opens on the patient's age (a patient under 13 opens on Child is an analyst default; the doctor can switch). The age rule is flagged for the human because the mockup does not state it. (BR45-6)
- Given the chart, then teeth use FDI numbering by default and are laid out in four quadrants named "Upper right", "Upper left", "Lower right" and "Lower left", each tooth showing its number and its condition by colour and by text (not colour alone). Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-3). (BR45-4)
- Given the "Numbers" switch, when the doctor switches it off, then tooth numbers are hidden on the drawing and the condition names stay; switching on shows them again. The choice lasts for that visit only. (BR45-6)
- Given the doctor taps a tooth, then it is selected, shown larger with its number, and the condition buttons for it show: Healthy, Caries, Filled, RCT, Crown, Missing, To extract, Implant; one is selected (Healthy at start). Tapping another changes the tooth at once and the message reads "<tooth> marked <condition>" with an Undo. Rule tag as above (C-3). (BR45-4)
- Given a tooth with condition Caries, Filled or Crown, then surface buttons (M mesial, O occlusal or incisal, D distal and the others drawn) can be ticked; the ticks print after the condition (for example "16 (O)"). Surfaces are not offered for Missing, To extract or Implant. (BR45-6)
- Given a tooth is selected, then "Mobility" offers Grade 0, Grade 1, Grade 2 and Grade 3 (shown as G0 to G3), one selected (Grade 0 at start); the grade is stored per tooth. Rule tag as above (C-3). (BR45-4)
- Given the doctor taps "Next tooth ›", then the next tooth in the displayed order is selected; at the last tooth it returns to the first. (BR45-6)
- Given "Tooth shade" offers A1, B1, A2, A3 and C2 and "Gum tone" offers Pink, Light brown and Dark brown, then each is one choice for the visit (A2 and Pink at start as drawn) and appears with the chart in the case paper's Examination line. Rule tag as above (C-3). (BR45-4)
- Given a tooth is marked Missing, then it shows as an empty dashed outline; given a Child chart, then only the 20 primary teeth show and the adult teeth are kept in the record but not shown. (BR45-6)
- Given the section's status (SAH-47-4), then it is Done when at least one tooth has a condition other than Healthy, or when the doctor confirms "All teeth healthy" (a button added by the analyst so that a required section can be completed for a healthy mouth). (BR45-20)
- Given Copy from last visit is on for Dental chart (it is on at start, SAH-53-1), then the teeth of the last signed Dental visit are pre-filled, marked "Copied from <date>", and the section is Done only after the doctor opens and confirms or edits it (OQ-45-26, decided). (BR45-20)
- Given a tap target, then it is at least 44 px; with 32 teeth on a tablet the chart scales and the teeth stay separate tap areas (OQ-45-30 default; the mockup uses 32 px). (BR45-6)
- Given the visit is signed, then the chart is read-only; a correction is an addendum (SAH-51). (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, or a doctor who is not the visit's doctor, when they call any chart change, then the answer is 403 and nothing changes; given another clinic's visit, 404; given no login, 401. (BR45-16, BR45-5)
- Given a slow or lost network, then the tooth change stays on screen as unsaved with a retry; nothing is lost silently. (BR45-20)
- Given any call, then no log line, audit value or analytics event holds a tooth, a condition or a note together with a patient. (BR45-5)
## Notes for design
The chart value is a list of teeth (FDI number, condition, surfaces, mobility) plus shade and gum tone, saved in the section document of the visit. Tooth look settings (real or plain drawing) belong to 49-3. The 3D view reads the same values.

### SAH-49-2 · As a dentist, I want suggested treatments for the selected tooth and a treatment plan with phase and status, so that I plan the work and move each item forward
Priority: Must
Screen: EMR mockup #DoctorDental, "Suggested treatment" chips and the table "Treatment plan & estimate" (columns Tooth, Procedure, Phase, Status; the money columns are in SAH-69).
## Acceptance criteria
- Given a tooth is selected, then "Suggested treatment" shows chips by its condition, each with its phase. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-3): Caries: Composite filling (Phase 1), Root canal treatment (Phase 1); RCT: Root canal treatment (Phase 1), PFM crown (Phase 2); Filled: Replace filling (Phase 1); Crown: Crown re-cementation (Phase 1); Missing: Implant with crown (Phase 3), Bridge (Phase 2); To extract: Extraction (Phase 1); Implant: Implant review (Phase 3); Healthy: no chip. (BR45-4)
- Given the doctor taps a suggestion chip, then a plan row is added with the tooth number, the procedure name (with the ticked surfaces in brackets, for example "Composite filling (O)"), the phase and status Planned, and the message reads "<procedure> on <tooth> added to plan" with an Undo. (BR45-6)
- Given the plan table, then it shows the columns "Tooth", "Procedure", "Phase" and "Status" (and the money columns of SAH-69-1), a remove action for each row (message "Removed from plan" with an Undo), and the hint "Tap a status to move it forward". (BR45-6)
- Given a row, when the doctor taps its status, then it moves Planned → In progress → Done and, after Done, back to Planned; each change is saved with the row. (BR45-6)
- Given a row is marked Done today, then it appears in "Done today" with the title "<tooth> · <procedure>" and the note "Marked done today. Charge added to the bill." and the charge goes to the bill as SAH-69-3 says; marking it back removes the line while the bill is unpaid. (BR45-23)
- Given Root canal treatment is added, then it is planned as 3 sittings and a row "Sitting n of 3" shows (the mockup's example "36 · Root canal treatment — sitting 2 of 3"); the doctor can change the number of sittings from 1 to 6 (analyst addition, flagged for the human; the mockup shows 3 only). Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-3). (BR45-4)
- Given a procedure the doctor wants that is not in the suggestions, then the doctor can type it with the EMR list's search ("Procedure / service" kind, SAH-56) and add it to the plan with a phase 1 to 3; no rate is invented. (BR45-6)
- Given the same tooth and procedure are already in the plan, then it is not added a second time. (BR45-6)
- Given the section Treatment plan & estimate is required or optional per the published layout (it prints on the prescription at start), then it follows SAH-47-4: Done when at least one plan row exists. (BR45-20)
- Given the signed visit, then the plan is read-only; the next sitting continues the plan in a new visit of the same course (SAH-58-2) and the plan rows with status Planned or In progress carry over to it. (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; a doctor who is not the visit's doctor (not owner), 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then no log line or audit value holds a procedure or tooth with a patient. (BR45-5)
## Notes for design
Suggestions are fixed data of the product (a table of condition to procedure and phase), not clinic-editable in this story. Phase is a number 1 to 3. The plan is the list the Treatment course reads (SAH-58-1).

### SAH-49-3 · As a dentist, I want a 3D tooth view, so that I can show the patient a tooth from any side
Priority: Could
Screen: EMR mockup #DoctorDental, chart view "3D" (hint "Drag to turn the jaw. Tap a tooth to select it. Colours show each tooth's condition.").
## Acceptance criteria
- Given the chart, then a choice between "Chart" and "3D" shows; in 3D the hint reads "Drag to turn the jaw. Tap a tooth to select it. Colours show each tooth's condition." (BR45-6)
- Given the doctor drags the 3D view, then the jaw turns; preset views (front, left and the others drawn) return to a fixed angle in one tap. (BR45-6)
- Given the doctor taps a tooth in 3D, then the same tooth is selected as in the flat chart and the condition buttons, surfaces and mobility of SAH-49-1 apply to it. (BR45-6)
- Given a tooth is selected in 3D, then a side view shows the crown and roots with canals, its condition colour and its mobility grade, and the look setting (real or plain drawing) can be switched for the visit. (BR45-6)
- Given a device that cannot draw 3D (old tablet), then the view falls back to the flat chart with the note "3D view is not available on this device" and nothing else is lost. (BR45-6)
- Given the 3D view, then colours also have the condition name in text (accessibility); it can be used by keyboard (arrow keys turn the jaw, Tab moves between teeth). (BR45-6)
- Given a user without the EMR role, 403; another clinic's visit, 404. (BR45-16, BR45-5)
- Given a signed visit, then the 3D view is read-only (turn and select only). (BR45-18)
## Notes for design
In scope by DEC-039 answer 1 (the BRD listed 3D as out of release 1). Likely its own sprint; the architect decides the 3D library and its size. Performance on a clinic tablet is the main risk. The 3D view stores nothing of its own.

## Out of scope for SAH-49
Estimate, rates, "Estimate signed", consent, lab work, Complete & send to billing and Book next sitting (SAH-69); the prescription table and the drug-allergy alert (SAH-48, the mockup's amoxicillin and penicillin example is SAH-48-2); the printed case paper (SAH-55); the Treatment course page (SAH-58); the mockup's doctor-screen top bar and its Dental links (not built, SAH-47-1, DEC-041).

## Traceability
BR45-4 -> SAH-49-1, -2. BR45-5 -> SAH-49-1 to SAH-49-3. BR45-6 -> SAH-49-1 to SAH-49-3. BR45-16 -> SAH-49-1 to SAH-49-3. BR45-18 -> SAH-49-1 to SAH-49-3. BR45-20 -> SAH-49-1, -2. BR45-23 -> SAH-49-2.

## Open questions
In `docs/requirements/SAH-45.md`: C-3 (tagged), SO-45-41 (part of the chart: decided), OQ-45-28 (Lab: Later), OQ-45-30 (44 px). The age rule for the Adult and Child tab and the "All teeth healthy" confirm are analyst additions to be confirmed by the human when approving `stories`; no new question number is needed because the defaults stand.

## Status log
- 12 Nov 2026 · outline by the business-analyst from EMR mockup #DoctorDental source.
- 12 Nov 2026 (later) · outline updated after DEC-039.
- 12 Nov 2026 (phase 4) · full stories written from the source of EMR mockup #DoctorDental (conditions, suggestions, phases, statuses, mobility, shade, gum, 3D hint); estimate and finish moved to SAH-69. Awaiting human approval of `stories`.
