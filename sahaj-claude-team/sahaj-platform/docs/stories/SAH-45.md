# SAH-45 · Stories: Clinic setup → Specialty template page (EMR configuration by department)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-7 to BR45-15; decided sign-offs SO-45-2, SO-45-5, SO-45-6, SO-45-8, SO-45-9, SO-45-10, SO-45-24 to SO-45-26, SO-45-29, SO-45-30; answered questions OQ-45-2, OQ-45-6 to OQ-45-8, OQ-45-12 to OQ-45-15, OQ-45-18, OQ-45-24: "answered: default accepted (DEC-039 addendum)", OQ-45-6 answered explicitly; also answered, default accepted (DEC-039 addendum): OQ-45-27, OQ-45-29, OQ-45-30). Module: opd. Phase 1. Depends on SAH-53 (data and rules). **This story replaces the screen part of the withdrawn SAH-44-2 and SAH-44-3 (DEC-039 answer 2, Variant B).** Marathi and Hindi: the mockup draws this configuration page in English only, so the page has no Marathi or Hindi labels.
Mockup screen: Clinic setup → Specialty template, Case paper and Rx (EMR mockup #Main). Other screens this page links to: EMR mockup #AddDepartment (SAH-54), EMR mockup #FormBuilder (SAH-59, button shown disabled until then), EMR mockup #DoctorGeneral and the other doctor screens ("Open full doctor screen", SAH-47).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only the owner can change anything on this page (OQ-45-12, decided: owner only; "admin" in the brief means owner). Every other signed-in clinic role can open the page read-only. "Not signed in" gets 401. The clinic always comes from the logged-in user. The page exists only where OPD is on.
Words on screen are those of the mockup (sentence case): "EMR configuration by department", "Departments", "Search department", "Configure sections", "Doctor screen preview", "Save draft", "Publish changes", "Reset to template", "Show in EMR", "Required to close visit", "Print on prescription", "Copy from last visit", "Collapsed by default", "Who can fill this section".

### SAH-45-1 · As the owner, I want a list of my clinic's departments with search, status and version, so that I can pick the department I want to configure
Priority: Must
Screen: EMR mockup #Main, column "Departments".
## Acceptance criteria
- Given the owner opens the page, when it loads, then it shows the title "EMR configuration by department", the line "Choose a department, then pick which EMR sections the doctor sees, in what order, and which are required.", the name of the owner's clinic, and the list of departments. (BR45-8)
- Given the department list, then each card shows initials, name, status (Template, Draft or Live), version (for example v6) and "x of y sections on" counting shown sections of the draft. (BR45-8, BR45-10)
- Given a new clinic (OQ-45-11, decided), then all 15 starter departments are listed as Template v0 until the clinic saves a draft or adds one; the clinic's own record is created at its first Save draft or by Add department.
- Given the old "Start from your specialty" cards of Clinic setup → Specialty template (SAH-7), then they stay above the department list and keep working as built (OQ-45-2, decided). (BR45-8)
- Given the owner types in "Search department", when the text matches the start or any part of a department name (any letter case) or equals its initials exactly, then only those cards remain; given no match, then the list shows an empty message and no card is selected. (BR45-8)
- Given the owner taps a department, then it is selected, its sections show in the next column, and the first Specialty section of that department is selected (or Vitals if it has none). (BR45-8)
- Given departments the clinic added (SAH-54), then they appear in the list with their own initials and colour; given a department the clinic has never published, then status is Template or Draft and the version is v0 or v1 as stored.
- Given a doctor, visiting doctor, nurse, front desk, billing or pharmacist user, when they open the page, then they see the same list without any switch or button that changes data, and a line that only the owner can change it; direct calls to change anything are refused with 403 (SAH-53). (BR45-15)
- Given another clinic's owner, then they never see this clinic's departments (tenant check). (BR45-3)
- Given a slow network, when the list is loading, then a loading state shows; given the load fails, then an error with a retry shows and nothing is editable. (BR45-15)
## Notes for design
The status chip shows the status of the department as stored; the version shown changes when the owner publishes. Starter content comes from SAH-53-1. Screen width in the mockup: 1440 px, three columns (Departments, Sections, Section settings).

### SAH-45-2 · As the owner, I want to see a department's sections grouped by part and switch Show, Required, Print, Copy, Collapsed and Who can fill, so that the doctor screen asks only what we use
Priority: Must
Screen: EMR mockup #Main, columns "1 · Assessment" / "2 · Treatment" and "Section settings".
## Acceptance criteria
- Given a selected department, then its sections are in two groups: "1 · Assessment · <department>" with the line "What the doctor finds: complaints, history, vitals, examination, findings", and "2 · Treatment · <department>" with "What the doctor decides and does: diagnosis, medicines, tests, procedures, follow-up". Each group shows "x of y on". (BR45-9, BR45-10)
- Given a section row, then it shows its name, a short description, "n fields", a tag ("<initials> specialty", "Compliance" or "Common"), a red star when required, the word "Required" or "Optional", and a Show switch and a Required switch. (BR45-7, BR45-9)
- Given the owner turns Show off, then the row shows as off, "x of y on" drops by one, and the Required switch cannot be turned on for it; given it was required, then Required is cleared in the same change (rule BR45-10, decided: the mockup only leaves hidden sections out of the required count; Required is cleared on hiding). (BR45-10)
- Given the owner turns Required on or off for a shown section, then the star and the word change at once and the department's required count changes. (BR45-9)
- Given the owner selects a section, then the "Section settings" panel shows its name, kind (for example "Dental specialty", "Compliance", "Common section"), description, and the five switches Show in EMR ("Doctor sees this section in <department>"), Required to close visit ("Visit cannot be completed until filled"), Print on prescription ("Appears on the printed Rx / summary"), Copy from last visit ("Pre-fills with the previous values") and Collapsed by default ("Doctor taps to open it"). (BR45-9)
- Given the panel, then "Who can fill this section" shows Doctor, Nurse / assistant and Reception as chips the owner can switch on and off (OQ-45-14, decided: stored and shown only; no other role writes the EMR; no nurse vitals pre-fill in phase 2, SO-45-43). (BR45-9)
- Given the panel, then the section's fields are listed read-only (label, type, meta, required) under "Fields (n)". The button "+ Add field" is not available in this phase (the mockup gives it no action: SO-45-9; field editing is SAH-59). (BR45-7)
- Given the section Allergies in any department, then its Show switch is locked on with a short reason; every other section, including Prescription (Rx), Chief complaints and Diagnosis, can be switched off, as in the mockup (OQ-45-6, answered explicitly by the human). (BR45-10)
- Given the department Obstetrics & Gynaecology, then the Compliance section PCPNDT Form F has its Show switch locked on with a short reason (OQ-45-7, decided; whether this lock stays next to "only Allergies" is OQ-45-29, default: it stays). Every other Compliance section (Consent forms, Surgical consent, WHO surgical safety checklist, Insurance pre-authorisation, Scheme / insurance, ABHA / ABDM link, Prescription print format) can be switched off as drawn. (BR45-10)
- Given a department that starts with a section off (for example Physiotherapy with Prescription (Rx) off), then it shows as off and the owner can switch it on. (BR45-9)
- Given the owner hides every shown section except the locked one, or makes nothing required, then the change is accepted and can be published; there is no rule (OQ-45-24, decided). (BR45-10)
- Given any change, then it affects the draft only; the sections doctors see do not change until Publish (SAH-45-4). (BR45-12)
- Given a doctor, visiting doctor, nurse, front desk, billing or pharmacist user, then all switches and chips are shown but disabled. (BR45-15)
- Given a department with no sections matching (never expected), or a section whose field list is empty, then a plain empty message shows and nothing breaks. (BR45-7)
- Given a switch change fails to save because of the network, then the screen keeps the change as unsaved and says so; it is never lost silently. (BR45-14)
## Notes for design
Switch targets must be 44 px (SO-45-33). Colours and tags follow the mockup.

### SAH-45-3 · As the owner, I want to move a section to the other part and move it up or down, so that the doctor sees it where it belongs
Priority: Must
Screen: EMR mockup #Main, row actions "To Treatment →" / "← To Assessment", Up and Down.
## Acceptance criteria
- Given a section in part 1, when the owner taps "To Treatment →", then it moves to part 2, the message says "<section> moved to Treatment", and the change counts as unsaved; given a section in part 2, "← To Assessment" does the reverse. (BR45-11)
- Given a section is moved, then it keeps its place in the department's overall order and shows among the sections of the new part at that place. (BR45-11)
- Given a section, when the owner taps Up or Down, then it swaps with the next section of the same part; at the top or bottom of its part nothing changes and no error shows. (BR45-11)
- Given the Up and Down buttons, then each has an accessible name ("Move <section> up") and works from the keyboard. (BR45-11)
- Given the move and order only affect the draft, then doctors see no change until Publish. (BR45-12)
- Given a doctor, nurse or any role other than the owner, then the move and order controls are not available. (BR45-15)
- Given the mockup's "To Treatment" for the Dental chart (which the BRD puts in Assessment), then the starting part follows the seed (SO-45-41) and the owner can move it. (BR45-11)
## Notes for design
Dragging is not drawn (the mockup shows a drag icon only on field rows). Moving the last section out of a part leaves that part empty; the EMR then shows its tab with 0 required (SAH-47-2).

### SAH-45-4 · As the owner, I want to see how many changes are unsaved, save a draft and publish, so that doctors get a new version only when I decide
Priority: Must
Screen: EMR mockup #Main, header chip and buttons.
## Acceptance criteria
- Given there are no changes, then the chip reads "All changes published" (green); given one or more changes, then it reads "n unsaved change(s)" (amber) counting distinct differences from the last saved draft, so a switch turned off and on again counts 0 (OQ-45-15, decided). (BR45-14)
- Given changes, when the owner taps "Save draft", then the draft is stored and the message reads "Draft saved. Doctors still see the published layout."; the chip changes to show the draft is saved but not published. (BR45-12)
- Given no changes, when the owner taps "Save draft", then the message reads "Nothing new to save". (BR45-12)
- Given changes, when the owner taps "Publish changes", then the next version of the selected department is created and the message reads "<department> published as v<n>. New visits use it from now."; the chip reads "All changes published" and the department card shows the new version and status Live. (BR45-12)
- Given no changes, when the owner taps "Publish changes", then the message reads "Nothing to publish". (BR45-12)
- Given the page shows the line "Changes apply to <department> · <visit type> after you publish. Visits already in progress keep the layout they started with.", then this is true: a visit already opened keeps its version (SAH-46, SAH-53-3). (BR45-12)
- Given another owner saved a newer draft or published a newer version, when this owner saves or publishes, then the answer is 409, the message says someone else changed the department and offers a reload, and nothing is applied. (BR45-15)
- Given the owner taps Save draft or Publish twice quickly, then one request is made and one version results. (BR45-12)
- Given the owner leaves the page with unsaved changes, then a warning asks whether to leave. (BR45-14)
- Given a doctor, nurse or any role other than the owner, then Save draft and Publish changes are not shown enabled; a direct call is refused with 403. (BR45-15)
- Given each save draft or publish, then one audit entry is written (SAH-53-3). (BR45-15)
- Given the network is slow or lost, then the buttons show a busy state, a failure shows an error, and the changes stay on screen as unsaved. (BR45-14)
## Notes for design
No approval step on this page (OQ-45-13, decided). The mockup's counter adds one for every click; the decided rule counts distinct differences (OQ-45-15). The department card's status chip is not drawn changing in the mockup after Publish; here it must change (Draft to Live).

### SAH-45-5 · As the owner, I want to reset a department to its starter template and undo that, so that I can start again without fear
Priority: Must
Screen: EMR mockup #Main, "Reset to template" and the message with Undo.
## Acceptance criteria
- Given the owner taps "Reset to template" for the selected department, then its draft returns to the starter values for that department only, the message reads "<department> reset to template" with an Undo action, and the change is counted as unsaved. (BR45-13)
- Given the owner taps Undo while the message is shown, then the draft returns to exactly what it was before the reset (OQ-45-8, decided: including parts and order). (BR45-13)
- Given another change was made after the reset, or the owner left the page, then Undo is no longer offered. (BR45-13)
- Given the department is already equal to the starter values, then the message says nothing changed and no unsaved change is counted. (BR45-13)
- Given a reset, then doctors keep seeing the published version until Publish. (BR45-12)
- Given a doctor, nurse or any role other than the owner, then the button is not available; a direct call is refused with 403. (BR45-15)
- Given a reset is made while another owner has changed the department, then 409 as in SAH-45-4. (BR45-15)
## Notes for design
The mockup's Undo restores only settings and order, and its reset forgets moved parts only because the screen keeps them separately; the decision is SO-45-10 (Reset and Undo cover settings, parts and order). Audit: SAH-53-4.

### SAH-45-6 · As the owner, I want to preview the doctor screen from my draft and reach the Add department and Form builder pages, so that I can check my changes
Priority: Should
Screen: EMR mockup #Main, tab "Doctor screen preview", "Visit type", links.
## Acceptance criteria
- Given the owner picks the tab "Doctor screen preview", then a small version of the doctor screen shows the draft: a header card for the patient with example tags (for example "ABHA linked" and "Allergy: [NONE RECORDED]", as drawn; the "ABHA linked" tag in the preview is example text only, the real tag on a patient is Later (DEC-049), SAH-47-1), a card "1 · Assessment" ("Doctor fills this first") and "2 · Treatment" ("Opens after assessment"), then one card per shown section in order, with the required star, the tag and the field names, and a "Missing" state for required empty sections. The example uses no real patient data. (BR45-19)
- Given the owner picks the tab "Configure sections", then the three columns show again. (BR45-9)
- Given the "Visit type" selector (New consult, Follow-up, Procedure visit, Teleconsult), then selecting one changes the words "Changes apply to <department> · <visit type>" and the preview heading; the configuration stored is the same for every visit type (OQ-45-18, decided). All four can be selected, as drawn; selecting Teleconsult changes only these words, because the video call itself is not built (OQ-45-27, default Later, shown disabled in any place that would start a call). (BR45-9)
- Given the preview, then the link "Open full doctor screen →" opens the department's EMR screen in a preview with example data once SAH-47 exists; until then it is hidden. (BR45-19)
- Given the "+ Form builder" button and the card "Have your own chart or form? Build it in the Form builder: fields, scores, show-if rules and print layout. It appears here as a custom section." with "Open form builder", then both open the Form builder of SAH-59 (in scope since DEC-039 answer 1); until SAH-59 is built they are hidden. A form published in SAH-59 appears in the sections list of the chosen departments as a custom section. (BR45-6)
- Given a department the clinic added with the wizard, then "Add department" is reachable from the page header or the list and opens SAH-54. (BR45-8)
- Given a doctor, nurse or any role other than the owner, then the preview works (it holds no patient data) and the links that change data are hidden. (BR45-15)
## Notes for design
The preview must read the draft but never write. Cards of specialty sections with special editors (dental chart, ANC card) show a placeholder in this phase.

## Out of scope for SAH-45
Editing a section's name, description or fields (SAH-59); adding a department (SAH-54); the doctor screens themselves (SAH-47 and later); approval before publish (OQ-45-13); Marathi and Hindi labels on this page (not drawn); printing the configuration; a list of old versions (not drawn on this page); per-visit-type configuration (OQ-45-18).

## Traceability
BR45-7 -> SAH-45-2. BR45-8 -> SAH-45-1, -6. BR45-9 -> SAH-45-2, -6. BR45-10 -> SAH-45-1, -2. BR45-11 -> SAH-45-3. BR45-12 -> SAH-45-4, -5. BR45-13 -> SAH-45-5. BR45-14 -> SAH-45-4. BR45-15 -> SAH-45-1 to SAH-45-6. BR45-19 -> SAH-45-6.

## Open questions
In `docs/requirements/SAH-45.md`, all answered (the decided rule is written into the criteria above): OQ-45-2 (where the page lives), OQ-45-6 and OQ-45-7 (locked sections), OQ-45-8 (Reset), OQ-45-12 (owner or admin), OQ-45-13 (approval), OQ-45-14 (who can fill), OQ-45-15 (change count), OQ-45-18 (visit type selector), OQ-45-24 (empty department). Also answered, default accepted (DEC-039 addendum, 12 Nov 2026): OQ-45-27 (Teleconsult call: Later), OQ-45-29 (PCPNDT lock stays next to "only Allergies"), OQ-45-30 (44 px tap targets).

## Status log
- 12 Nov 2026 · written by the business-analyst from the source of EMR mockup #Main, DEC-039 and SAH-44.
- 12 Nov 2026 (later) · updated after the human's answers: OQ-45-2, 6, 7, 8, 12 to 15, 18, 24 written in as decided rules; Form builder link no longer held; withdrawn SAH-44-2 and SAH-44-3 replaced here. Awaiting human approval of `stories`.
