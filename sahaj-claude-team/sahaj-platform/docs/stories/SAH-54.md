# SAH-54 · Stories: Add department wizard (blank, copy or one of the starter templates; review sections; assign doctors and rooms)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-3, BR45-8, BR45-15; decided sign-offs SO-45-2, SO-45-21; answered questions OQ-45-10, OQ-45-11, OQ-45-19, OQ-45-20: "answered: default accepted (DEC-039 addendum)"). Module: opd (doctor names read through `platform.api`). Phase 1. Depends on SAH-53 and SAH-45.
Mockup screen: Clinic setup → Specialty template, Case paper and Rx (EMR mockup #AddDepartment).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only the owner can add a department (same permission as SAH-45). Other roles never see the wizard. "Not signed in" gets 401. The clinic comes from the logged-in user.
**What the mockup draws:** only step 1 "Choose template" in full. Steps 2 "Review sections" and 3 "Assign doctors & rooms" exist as labels of the stepper only; there is no screen for them. Stories SAH-54-2 and SAH-54-3 therefore state the behaviour the human asked for in the brief and mark it "not drawn in the mockup" (SO-45-21, decided: built from the brief with the OQ-45-19 default; the screens reuse the SAH-45 components and the human reviews the layout when it is built).

### SAH-54-1 · As the owner, I want to start a department from a blank page, from a copy of one of my departments, or from a starter template with a preview, so that I do not set up from nothing
Priority: Must
Screen: EMR mockup #AddDepartment, step 1 "Choose template".
## Acceptance criteria
- Given the owner opens "Add a department", then the page shows the title "Add a department", the line "Start from a ready template built for Indian OPD practice, then change anything.", a three-step stepper "1 Choose template", "2 Review sections", "3 Assign doctors & rooms", and Cancel and "Next: review sections" buttons. (BR45-8)
- Given step 1, then it offers "Start blank" ("Common sections only"), "Copy an existing department" ("e.g. copy Dental for a second dental chair") and a list "Specialty templates" with the starter templates, each showing initials, name and the number of Specialty sections (for example Physiotherapy 10, Dental 7). (BR45-8)
- Given the mockup lists 12 starter templates (General Medicine 2, Dental 7, Obstetrics & Gynaecology 7, Paediatrics 6, Physiotherapy 10, Orthopaedics 5, Ophthalmology 5, ENT 5, Dermatology & Cosmetology 3, Diabetology 5, Cardiology 4, AYUSH 4) and the configurator has 15, then the list offers all 15 (OQ-45-20, decided). (BR45-8)
- Given the owner taps a template, then a "Template preview" shows its name, a one-line description (for example for Physiotherapy: "Built for session-based care: assessment on the first visit, then quick session notes against a package."), the list of its sections (how they are grouped is not clear from the mockup), and the line "Turned off by default: <sections>. You can switch them back on." taken from the starter's off-at-start list (SAH-53). (BR45-8)
- Given the checkbox "Also create a matching print template", then it is offered, and when ticked the new department starts with the Print flags of its starter (OQ-45-10, decided: Print flags choose which sections reach the printed Rx; size, language, letterhead and capitals stay in Case paper and Rx, SAH-7); no separate print template is created. (BR45-8)
- Given the owner chooses "Copy an existing department", then a list of the clinic's own departments appears to pick from; given the clinic has none, then the option is disabled with a reason. (BR45-8)
- Given the owner taps Cancel, then nothing is created and the owner returns to SAH-45. (BR45-8)
- Given a doctor, nurse or any role other than the owner, then the wizard cannot be opened; a direct call is refused with 403. (BR45-15)
- Given another clinic, then its departments never appear in "Copy an existing department" (tenant check). (BR45-3)
## Notes for design
Mockup width 1080 px; Physiotherapy is selected by default in the drawing only. The three steps are one flow with a Back action (not drawn).

### SAH-54-2 · As the owner, I want to review and change the sections of the new department before it is created, so that I do not have to fix it afterwards (not drawn in the mockup)
Priority: Should
Screen: EMR mockup #AddDepartment, step 2 "Review sections" (label only).
## Acceptance criteria
- Given the owner taps "Next: review sections", then step 2 shows the new department's sections as in SAH-45-2 (grouped by part, Show, Required, move, order) for the chosen starter, blank or copy. (BR45-8)
- Given the owner changes nothing, then the department is created with the starter's values. (BR45-8)
- Given the owner goes Back, then the choice in step 1 is kept. (BR45-8)
- Given Start blank, then only the 14 Common sections and the three Compliance sections listed for every department are offered. (BR45-8)
## Notes for design
Not drawn in the mockup: layout and wording follow the SAH-45 page components; the architect and angular-developer propose, the human reviews the layout (SO-45-21, decided).

### SAH-54-3 · As the owner, I want to assign doctors and rooms to the new department, so that the right doctors see the right EMR (not drawn in the mockup)
Priority: Should
Screen: EMR mockup #AddDepartment, step 3 "Assign doctors & rooms" (label only).
## Acceptance criteria
- Given step 3, then the owner can pick doctors from the clinic's doctors and visiting doctors (name and degree read from Team and access through `platform.api`; no copy of those fields) and give the department one or more rooms by name (OQ-45-19, decided: text labels such as "Room 2" or "Chair 1"). (BR45-8)
- Given a doctor, then they may belong to more than one department (OQ-45-19, decided). (BR45-8)
- Given a person who is not a doctor or visiting doctor (nurse, front desk, billing, pharmacist, counsellor, owner without doctor details), then they are not offered. (BR45-15)
- Given a doctor of another clinic, then they are never offered or accepted (tenant check). (BR45-3)
- Given the owner assigns no doctor, then the department is created without doctors and the wizard says doctors can be added later; given a room name over the allowed length or empty, then 400. (BR45-8)
- Given the assignment, then it is used by SAH-46 to choose the template for that doctor. (BR45-17)
## Notes for design
There is no room, chair, OT or day-care chair data in any module. Scheduling by room is out of scope; only the label is stored (OQ-45-19, decided; the OT schedule, ward list and chairs are OQ-45-28). A screen to change a department's doctors after creation is not drawn.

### SAH-54-4 · As the owner, I want the new department created as a draft that I can publish, so that nothing reaches doctors until I decide
Priority: Must
Screen: EMR mockup #AddDepartment, buttons after step 3.
## Acceptance criteria
- Given the owner confirms the wizard, then the department is created for this clinic with the chosen name, initials, colour and sections, status Draft, and it opens in SAH-45 for a first publish. (BR45-8, BR45-12)
- Given a name that already exists in this clinic (any letter case), then the request is refused as invalid input (400) with a reason; given an empty name or one over the allowed length, then 400. (BR45-8)
- Given two requests create the same name at the same moment, then only one succeeds. (BR45-8)
- Given the department is created from a starter, then its sections, parts, order and settings equal the starter's (SAH-53-1); given it is a copy, then they equal the source department's draft and not its history. (BR45-8)
- Given the department is created, then doctors do not see it in the EMR until it is published. (BR45-12)
- Given any creation, then one audit entry is written with who, when, the new department id, name and source (no patient data). (BR45-15)
- Given a wrong role, 403; another clinic's data is never read or changed; the clinic id in a body is ignored. (BR45-3, BR45-15)
- Given a slow or lost network, then a busy state shows and a failure keeps the wizard open with the choices kept. (BR45-15)
## Notes for design
Creating a department does not change the clinic's specialty in the clinic profile.

## Out of scope for SAH-54
Deleting or renaming a department; a screen to change a department's doctors or rooms after creation; room and chair scheduling; creating separate print templates (OQ-45-10: Print flags only); templates for specialties not in the 15.

## Traceability
BR45-3 -> SAH-54-1, -3, -4. BR45-8 -> SAH-54-1 to SAH-54-4. BR45-12 -> SAH-54-4. BR45-15 -> SAH-54-1, -3, -4. BR45-17 -> SAH-54-3.

## Open questions
In `docs/requirements/SAH-45.md`, all answered: OQ-45-10 (print template), OQ-45-11 (starting state), OQ-45-19 (doctors and rooms), OQ-45-20 (12 versus 15 templates); sign-off SO-45-21 (steps 2 and 3 not drawn: decided, built from the brief). Answered, default accepted (DEC-039 addendum): OQ-45-28 (rooms, OT and chairs scheduling is Later). Wizard fields the mockup does not draw use the architect's defaults (ADR-020, accepted in the DEC-039 addendum).

## Status log
- 12 Nov 2026 · written by the business-analyst from the source of EMR mockup #AddDepartment, DEC-039.
- 12 Nov 2026 (later) · updated after the human's answers: OQ-45-10, 11, 19, 20 written in as decided rules; steps 2 and 3 no longer held. Awaiting human approval of `stories`.
