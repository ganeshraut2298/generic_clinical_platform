# SAH-57 · Stories: Body map (local examination, injuries, lesions)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-26, BR45-4, BR45-5, BR45-6; decided sign-offs SO-45-1 (body map is in scope), SO-45-24; rule L-1 MLC; answered, default accepted (DEC-039 addendum): OQ-45-28 photos, OQ-45-30 tap target size). Module: opd. Phase 3. Depends on SAH-47 (frame, generic renderer). Used by SAH-60 (General Medicine "Local findings"), SAH-65 (Oncology "Body map"), SAH-59 (Form builder block "Body map") and the generic renderer for fields of type Body map.
Mockup screen: Queue (sidebar → Queue) (EMR mockup #BodyMap, 1000 px wide; embedded in the EMR section "Local examination" of the doctor screens).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details (`clinic.consultation.write`) add, move, change and delete pins, on their own patients (owner: all). Nurse, front desk, billing, pharmacist and counsellor cannot (403 on a direct call). "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body.
Rule tag: where a story says "Rule tag", the rule is "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (BR45-4). The MLC trigger and wording are legal rules (L-1) of this kind.

### SAH-57-1 · As a doctor, I want a body drawing I can switch between male, female and other, front and back, and zoom, so that I can point to small areas exactly
Priority: Should
Screen: EMR mockup #BodyMap, title "Body map · local examination", controls Male / Female / Other, "Skin", Front / Back, Turn, zoom +, − and "Fit".
## Acceptance criteria
- Given the component opens, then it shows the title (for example "Local examination · body map"), the line "Tap the body to drop a pin, drag a pin to move it, then record what you found and what happened.", and a drawing of the body from the front, with the sex set to the patient's recorded sex (Male, Female) or Other when not recorded. (BR45-26)
- Given the doctor taps Male, Female or Other, then the drawing changes and the pins stay where they are. (BR45-26)
- Given the doctor taps Light, Wheatish or Dark under "Skin", then the drawing's skin tone changes; this is a display choice for the visit and is not stored as a clinical fact. (BR45-26)
- Given the doctor taps Front or Back, or "Turn", then the other side shows with a short turn effect and the zoom returns to the whole body; pins show on the side they were dropped on. (BR45-26)
- Given the doctor taps "+", then the drawing zooms in by one and a half times up to four times the whole body, centred on the selected pin when there is one; "−" zooms out to the whole body at one; "Fit" returns to the whole body. When zoomed in, dragging the background moves the view. (BR45-26)
- Given the drawing, then each body region (front: head and face, neck, chest, abdomen, groin and pelvis, shoulder and upper arm, forearm, hand, thigh, knee and leg, foot; back: back of head, back of neck, upper back, lower back, buttocks, upper arm, forearm, back of hand and the same for the legs) is a separate tap area with a name, left and right separate. (BR45-26)
- Given a small screen (tablet), then the drawing and the pin form sit one above the other and all controls stay reachable. (BR45-26)
- Given a user without the EMR role, 403; another clinic's visit, 404. (BR45-16, BR45-5)
## Notes for design
Zoom is limited to four times and one at the least (mockup). The drawing is vector artwork drawn by the product (no photo of a person). Tap targets 44 px for buttons (OQ-45-30); the body regions are as drawn. Keyboard operation: regions reachable by Tab, Enter drops a pin, arrows move the selected pin by small steps (accessibility, not drawn).

### SAH-57-2 · As a doctor, I want to drop a numbered pin and record the finding, what happened, severity, size and a note, so that location and cause are exact
Priority: Must
Screen: EMR mockup #BodyMap, pin form: "Where" (Choose…), Finding, What happened, Severity, "Size", note, "Delete pin".
## Acceptance criteria
- Given the doctor taps anywhere on the body, then a numbered pin drops at that spot on the side shown, the region name is set as "Where", and the message reads "Pin n on <region>. Pick the finding and what happened." The new pin has the defaults: finding Wound, what happened Fall, severity Mild, size 2 cm, empty note, not MLC, with the time and the doctor recorded. The hint reads "Tap anywhere on the body to drop pin n. Use Turn to see the back, + to zoom in on small areas." (BR45-26)
- Given a pin, when the doctor drags it, then it moves and its region follows the new spot; a drag of less than a few pixels counts as a tap and does not move it. (BR45-26)
- Given a pin is selected, then its form shows: "Where" with the choice list "Choose…" of body parts (left and right sides included); "Finding" with the choices Wound, Swelling, Tenderness, Bruise, Burn, Rash, Lump, Fracture?, Scar and Pain, each in its own colour and also named in text; "What happened" with the choices No injury (illness), Fall, Road accident, Assault, Animal bite, Burn, Sports and Work injury; "Severity" with Mild, Moderate, Severe; "Size" in centimetres with plus and minus buttons (0 means not measured); a free note "What happened / notes"; the checkbox "Medico-legal case (MLC)"; and "Delete pin". Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-10). (BR45-26)
- Given the doctor picks a body part from "Choose…", then the pin's "Where" changes to it; given the pin was on another region, it stays where it is on the drawing unless the part is on the other side, in which case the view turns. (BR45-26)
- Given the doctor taps a pin number or its row in the register, then that pin is selected, the form shows it, and the drawing centres on it when zoomed in. (BR45-26)
- Given the doctor taps "Delete pin", then the pin is removed, the message reads "Pin n deleted", and the other pins keep their numbers (numbers are not reused). (BR45-26)
- Given a size or note is entered, then size is a whole or half number between 0 and an upper limit that the architect sets, and the note is limited in length; a value outside gives an error on the field and is not saved (400 on a direct call). (BR45-26)
- Given a visit that is signed, then pins cannot be added, moved, changed or deleted (a correction is an addendum, SAH-51). (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, or a doctor who is not the visit's doctor, then every pin action is refused with 403; another clinic's visit gives 404. (BR45-16, BR45-5)
- Given any call, then no log line, audit value or analytics event holds a note, a finding or a cause together with a patient. (BR45-5)
## Notes for design
Pins hold: id (visit-local), side, position, region, where text, finding, cause, severity, size, note, MLC flag, recorded time and doctor. The finding and cause lists are fixed lists of the product (the "Fracture?" label keeps its question mark as drawn). The pin is a clinical record; photos are not part of this story (documents service, OQ-45-28).

### SAH-57-3 · As a doctor, I want a findings register and an automatic medico-legal flag, so that the record shows every finding and no injury case is missed
Priority: Must
Screen: EMR mockup #BodyMap, "Findings register (n)", table, MLC checkbox and the MLC note.
## Acceptance criteria
- Given pins exist, then "Findings register (n)" lists them as a table with the columns "#", "Where", "Finding" (finding and severity, for example "wound · mild"), "What happened" (cause and note), "Size" (for example 2 cm, or "—" when 0), "Recorded" (date, time and doctor) and "MLC" (a red "MLC" badge for MLC pins); the side text shows "n medico-legal" when there are MLC pins, otherwise "Tap a number to find the pin". (BR45-26)
- Given there is no pin, then the register shows "No local findings yet." (BR45-26)
- Given the doctor sets "What happened" to Road accident, Assault or Burn on a pin, then the pin is marked MLC at once and the message reads "Marked as medico-legal (MLC) for <cause in lower case>". Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (L-1). (BR45-26)
- Given a pin is MLC, then the pin form shows the note "MLC: record time of examination, who brought the patient, and inform the police as per hospital policy." Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (L-1). The last sentence of the mockup, "Photos are kept with the record.", is shown only when the documents service exists (OQ-45-28), so that the note never claims what the product does not do. (BR45-26)
- Given a pin's cause is changed away from Road accident, Assault or Burn, then the MLC flag stays on until the doctor clears the checkbox "Medico-legal case (MLC)"; the doctor can also set the flag by hand for any pin. (BR45-26)
- Given the MLC flag is set or cleared on a pin, then one audit entry records who, when, the visit id, the pin number and on or off (no finding text). (BR45-5)
- Given a visit has at least one MLC pin, then the EMR header shows the tag "MLC" (text as well as colour) and the case paper's Examination line says "MLC case" (SAH-55-1). (BR45-26)
- Given the product only records and reminds, then no message is sent to the police or any other party; "inform the police as per hospital policy" is advice for the doctor. (BR45-26)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403 on the register and on any change; another clinic's visit, 404. (BR45-16, BR45-5)
## Notes for design
MLC handling beyond the flag and the note (police intimation forms, hospital policy workflow) is not drawn and is not built. The MLC injury report is a starter form of the Form builder (SAH-59-6).

### SAH-57-4 · As a doctor, I want the body map to sit inside the EMR section and be saved with the visit, so that it appears in the paper, the history and the oncology and general screens
Priority: Must
Screen: EMR mockup #BodyMap, as embedded in #DoctorGeneral "Local examination" and #DoctorOncology "Body map".
## Acceptance criteria
- Given an EMR section of type Body map (General Medicine "Local findings", Medical Oncology "Body map", the Form builder block "Body map"), then it renders this component with the visit's pins and reports every change to the visit at once. (BR45-6)
- Given the pins change, then the section's status (Done, Required, Optional) is judged by the rule of SAH-47-4: Done when at least one pin exists, or when the doctor confirms "No local findings" (a button added by the analyst so that a required section can be completed when there is nothing to mark). (BR45-20)
- Given Save draft (SAH-47-5), then pins are saved with the visit and come back as they were; given the visit is signed, then the component is read-only. (BR45-20, BR45-18)
- Given the case paper, then it lists the pins as short lines under Examination (for example "Abdomen: tenderness, moderate, 2 cm") in the heading order of SAH-55-1; given the history, the pins show in the visit details (SAH-50-4). (BR45-25)
- Given the Form builder block (SAH-59-1), then the same component is used and the values saved with the form. (BR45-28)
- Given the section is switched off in the department's configuration, then it does not show and no pins are asked for. (BR45-19)
- Given a lost network while a pin is moved, then the screen keeps the pin where the doctor left it as unsaved with a retry; nothing is lost silently. (BR45-20)
## Notes for design
The component reports pins as a list value to its host; the host stores it. The architect decides the storage (a document per section). Photos are not stored (OQ-45-28).

## Out of scope for SAH-57
Clinical photos and files (documents service, OQ-45-28); 3D rotation of the body; the Dental tooth chart (SAH-49); notifying the police or any external party; printing the body drawing (the case paper lists findings in text); measuring tools beyond the size in centimetres.

## Traceability
BR45-4 -> SAH-57-2, -3. BR45-5 -> SAH-57-1 to SAH-57-4. BR45-6 -> SAH-57-4. BR45-16 -> SAH-57-1 to SAH-57-3. BR45-18 -> SAH-57-2, -4. BR45-19 -> SAH-57-4. BR45-20 -> SAH-57-4. BR45-25 -> SAH-57-4. BR45-26 -> SAH-57-1 to SAH-57-4. BR45-28 -> SAH-57-4.

## Open questions
In `docs/requirements/SAH-45.md`: SO-45-1 (decided: body map in scope), OQ-45-28 (photos and files are Later, shown disabled), OQ-45-30 (44 px). From SAH-43, still open: OQ-43-8 (clinician review of lists).

## Status log
- 12 Nov 2026 · full stories written by the business-analyst from the source of EMR mockup #BodyMap (sexes, three skin tones, front and back, zoom, pin form, findings, causes, severities, size, register, MLC rule and wording), DEC-039 and DEC-040. Replaces the outline; no longer held for the legal owner (tagged instead). Awaiting human approval of `stories`.
