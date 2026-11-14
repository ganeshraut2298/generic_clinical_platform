# SAH-52 · Stories: OPD, IPD and Emergency differences in the EMR frame (opens as, visit-type control, past-visits scope, Admit to IPD button)
Requirement: `docs/requirements/SAH-45.md` (rule BR45-21; decided sign-offs SO-45-17, SO-45-18; answered question OQ-45-22 "Open EMR": "answered: default accepted (DEC-039 addendum)"; BRD FR-02, FR-04, FR-05 in `docs/handoffs/SAH-43-input-doctor-emr-brd.md`; SAH-43 questions OQ-43-3, OQ-43-4, OQ-43-25, OQ-43-26 stay open; OQ-43-27 answered, default accepted: DEC-039 addendum). Module: opd (reads ipd and platform through their `api` packages). Phase 2. Depends on SAH-46 and SAH-47.
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorGeneral for the frame). **The new mockup draws no IPD or Emergency variant of the EMR.** Every rule below comes from the BRD table (FR-02) and is marked "not drawn in the mockup"; the human decided that the BRD table stays the source for what the mockup does not draw (SO-45-17, DEC-036). The old IPD screens (IPD → Admitted, IPD → Emergency, Admission sheet) are the base to reuse.
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details open the EMR. Front desk sets the visit type at registration only. "Not signed in" gets 401. The clinic comes from the logged-in user.

BRD table (FR-02), copied as the base of these stories:

| | OPD | IPD | Emergency |
| --- | --- | --- | --- |
| Opens as | Consultation | Daily progress note (ward round) | Emergency assessment |
| Visit type control | New, Follow-up, Revisit, Emergency | Shows "IPD · day n", read-only | Shows the triage level, read-only |
| Past visits panel | Earlier OPD and IPD visits | This admission's earlier notes and vitals first, then earlier visits | Earlier OPD and IPD visits |
| Admit to IPD button | Shown | Hidden (already admitted) | Shown |
| Finish does | Next workflow step, usually billing | Signs the day's note; patient stays admitted; card shows round done | Asks for an ER outcome, then signs |

### SAH-52-1 · As a doctor, I want the EMR to say what I am writing (Consultation, Daily progress note or Emergency assessment), so that I never write into the wrong encounter
Priority: Must
Screen: EMR mockup #DoctorGeneral (header and title), not drawn for IPD and Emergency.
## Acceptance criteria
- Given an OPD visit, then the EMR title reads "Consultation"; given an admitted patient, "Daily progress note"; given an Emergency case, "Emergency assessment". (BR45-21)
- Given an IPD or Emergency EMR, then the header shows the encounter facts (admission and day number, or the triage level) read from the ipd `api`, not typed. (BR45-21)
- Given the same patient has an OPD visit and an admission, then each opens its own EMR from its own card and the two are never mixed. (BR45-21)
- Given the encounter cannot be read (for example the admission was discharged), then a message says so and the EMR opens read-only. (BR45-21)
- Given any call, then no log line holds the patient's name or clinical text. (BR45-5)
## Notes for design
The record model (one note per day, one per ER case, one per OPD visit) is OQ-43-27 (answered, default accepted: DEC-039 addendum).

### SAH-52-2 · As a doctor and as front desk, I want the visit-type control to follow the encounter, so that the visit type is recorded once and shown everywhere
Priority: Must
Screen: EMR mockup #DoctorGeneral (header "Follow-up, 3rd visit"), not drawn as a control.
## Acceptance criteria
- Given an OPD visit, then a control with New, Follow-up, Revisit and Emergency shows, set to the type chosen at registration (front desk) and changeable by the doctor and owner only. (BR45-21)
- Given a nurse, billing or pharmacist user, then the control is not shown; given front desk, the type can be set only at registration, not in the EMR. (BR45-21)
- Given the doctor changes the type, then the visit record, the queue card and the case paper show the new type, and one audit entry records who, when, the old and new type. (BR45-21)
- Given the doctor picks Emergency, then the existing triage step is opened (SAH-28) and the patient goes to the Emergency group; this is finished in SAH-67 (phase 5) and until SAH-67 is built the option is shown disabled with a "Coming in a later step" note (a build-order note, not a held rule). (BR45-21)
- Given an IPD EMR, then the control is replaced by the read-only text "IPD · day n"; given an Emergency EMR, by the read-only triage level. (BR45-21)
- Given Follow-up inside the clinic's follow-up window, then the fee rule of the clinic applies; the billing effect is SAH-68, here only the type is recorded. (BR45-21)
- Given the meaning of "Revisit" and who may set it is not defined (OQ-43-26) and the configurator's own list is New consult, Follow-up, Procedure visit, Teleconsult (SO-45-18), then this story follows the BRD list for the EMR control. (BR45-21)
## Notes for design
The configurator's visit-type selector (SAH-45-6) is separate and changes no data.

### SAH-52-3 · As a doctor, I want the Past visits panel to show the right history for the encounter, so that the first thing I see is the most useful
Priority: Should
Screen: EMR mockup #VisitHistory (content in SAH-50), not drawn per encounter.
## Acceptance criteria
- Given an OPD or Emergency EMR, then Past visits lists earlier OPD and IPD visits of this patient. (BR45-21)
- Given an IPD EMR, then this admission's earlier notes and vitals come first, then earlier visits. (BR45-21)
- Given the doctor may not see an IPD ward's data (ward scope, SAH-34), then those entries are left out and the panel says some entries are hidden. (BR45-21)
- Given the panel is empty, then it says "First visit. No earlier records." (BR45-21)
## Notes for design
Only the ordering rule and the scope are decided here; the panel itself is SAH-50.

### SAH-52-4 · As a doctor, I want an Admit to IPD button when it applies, and a clear answer when the clinic has no IPD, so that I can advise admission from the EMR
Priority: Must
Screen: EMR mockup #DoctorGeneral (header), not drawn.
## Acceptance criteria
- Given an OPD or Emergency EMR, then an "Admit to IPD" button shows in the header; given an IPD EMR, then it is hidden (the patient is already admitted). (BR45-21)
- Given the clinic's IPD is on or in trial, when the doctor taps the button, then the existing admission sheet opens (pre-fill and result are SAH-67), and after admission is advised the button shows a pressed state and the header shows the tag "Admission advised". (BR45-21)
- Given the clinic's IPD is off, when the doctor taps the button, then a sheet explains the clinic is OPD-only and offers "Advise admission + referral" (marks the visit "Admission advised" and stores a referral line for the case paper); for the owner it also offers "Switch on IPD" which opens Clinic setup → Plan and modules. (BR45-21)
- Given a doctor who is not the owner, then "Switch on IPD" is not shown. (BR45-21)
- Given "Advise admission + referral" is used, then one audit entry records who, when and the visit id (no clinical text). (BR45-5)
- Given a nurse, front desk, billing or pharmacist user, then they never see the button (403 on a direct call). (BR45-16)
- Given the referral line is a line on the case paper or a printable letter (OQ-43-4), then the default is a line on the case paper (SAH-55). (BR45-21)
## Notes for design
The mockup draws an "Admission request" only inside the Treatment course and Surgery screens (not from the header).

## Out of scope for SAH-52
The finish step for each encounter (SAH-51, SAH-67); creating the admission and its pre-fill (SAH-67); the triage step and Emergency group (SAH-67); fee rules (SAH-68); the case paper referral line (SAH-55).

## Traceability
BR45-5 -> SAH-52-1, -4. BR45-16 -> SAH-52-4. BR45-21 -> SAH-52-1 to SAH-52-4.

## Open questions
In `docs/requirements/SAH-45.md`: SO-45-17 (IPD and Emergency not drawn: decided, BRD table), SO-45-18 (visit types: decided), OQ-45-22 (button label: answered). Still open from SAH-43, not blocking the criteria above: OQ-43-3, OQ-43-4, OQ-43-25, OQ-43-26 (their defaults apply). OQ-43-27 (one EMR record per IPD day and per ER case) is answered, default accepted (DEC-039 addendum).

## Status log
- 12 Nov 2026 · written by the business-analyst from BRD FR-02, FR-04 and FR-05 and DEC-039; the new mockup draws OPD only.
- 12 Nov 2026 (later) · updated after the human's answers (SO-45-17, SO-45-18, OQ-45-22 decided). Awaiting human approval of `stories`.
