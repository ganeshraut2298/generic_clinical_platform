# SAH-46 · Stories: Open the EMR from the Queue card (OPD, IPD, Emergency), choose the department template, keep the version snapshot
Requirement: `docs/requirements/SAH-45.md` (rules BR45-16 to BR45-18, BR45-5; decided sign-offs SO-45-16, SO-45-17, SO-45-19, SO-45-27; answered questions OQ-45-9, OQ-45-11, OQ-45-19, OQ-45-22, OQ-45-23, OQ-45-25 ("answered: default accepted (DEC-039 addendum)"); BRD FR-02 in `docs/handoffs/SAH-43-input-doctor-emr-brd.md`). Module: opd (reads platform for doctor details and roles, ipd for admitted and emergency patients through their `api` packages). Phase 2. Depends on SAH-53 (published layout), SAH-54 (doctors per department), SAH-43-3 (doctor details), SAH-9 and SAH-12 (Queue and workflow), SAH-23, SAH-28. **SAH-46-3 replaces the withdrawn SAH-44-4 (DEC-039 answer 2, Variant B): the template-choice and snapshot criteria moved here with the new order doctor specialty, department, clinic default.**
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorGeneral for the screen it opens). The EMR button on the card is in neither mockup (the repo Queue card has "Consult done"; the EMR mockup draws the doctor screens with their own top bar, which is not built: the EMR opens inside the app shell with a Back button, DEC-041). IPD → Admitted and IPD → Emergency cards get the same button.
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). The EMR opens only for doctor, visiting doctor, and owner with doctor details (`clinic.consultation.write`). Doctor and visiting doctor: own patients. Owner: all patients. "Not signed in" gets 401. The clinic always comes from the logged-in user.

### SAH-46-1 · As a doctor, I want an EMR button on the Queue card for OPD, IPD and Emergency patients, so that I open the consultation from where I already work
Priority: Must
Screen: Queue (sidebar → Queue), IPD → Admitted, IPD → Emergency, card action.
## Acceptance criteria
- Given a doctor, visiting doctor or owner with doctor details, when they view a card in Queue (OPD), IPD → Admitted or IPD → Emergency, then an "Open EMR" button shows on the card (one label for all three places, OQ-45-22 decided; it must not be confused with the IPD "EMR sheet" for medicine orders, which keeps its own name). (BR45-16)
- Given a nurse, front desk, billing, pharmacist or counsellor, or an owner without doctor details, then the button is not shown and a direct call to open an EMR is refused with 403. (BR45-16)
- Given the card of another doctor's OPD patient, when a doctor or visiting doctor opens the Queue, then by default only their own patients show and a filter shows the others; the button on another doctor's patient is not offered and a direct call is refused with 403; the owner with doctor details can open any. (BR45-16)
- Given a clinic where IPD is off, then there are no IPD cards and no IPD button; given IPD is on or in trial, the IPD and Emergency cards show the button. (BR45-16)
- Given the existing "Consult done" button, then it stays on the OPD card until the EMR can sign (SAH-51) and it is not changed here. (SO-45-27)
- Given the card shows a visit that is Done (signed), when the doctor opens it, then the EMR opens read-only (rules for addendum in SAH-51). (BR45-16)
- Given a doctor of clinic B, when they open the EMR for a visit of clinic A by id, then the answer is 404 and no data is returned (tenant check). (BR45-5)
## Notes for design
The button uses the 44 px target (OQ-45-30 default). The label "Open EMR" is decided (OQ-45-22).

### SAH-46-2 · As a doctor, I want opening a waiting patient to move them to With doctor and a way back to the same queue, so that the queue stays true
Priority: Must
Screen: Queue (sidebar → Queue).
## Acceptance criteria
- Given an OPD visit that is Waiting or at Vitals, when the doctor opens the EMR, then the visit moves to With doctor through the existing workflow (SAH-9, SAH-12) honouring the clinic's skip rules and fee timing. (BR45-16)
- Given an OPD visit already With doctor, then opening it changes nothing in the queue. (BR45-16)
- Given opening fails to move the visit (for example the visit was already moved by someone else), then the EMR still opens and a message says the queue status was already updated; no duplicate stage is written. (BR45-16)
- Given the doctor taps the back arrow, then they return to the Queue with the same filter and view and the draft is kept (SAH-47-5). (BR45-16)
- Given two doctors open the same OPD visit, then both can open it; the rule that only one can sign is SAH-51. (BR45-16)
- Given opening a visit of an IPD admission or an Emergency encounter, then no OPD stage is changed. (BR45-16)
- Given any open, then one audit entry is written with who, when and the visit id, no clinical text. (BR45-5)
## Notes for design
Opening is a state change with a side effect (stage) and must be safe to repeat.

### SAH-46-3 · As a doctor, I want the EMR to pick the right department template for me and keep the layout my visit opened with, so that later changes never alter my visit
Priority: Must
Screen: EMR mockup #DoctorGeneral (the screen that opens); EMR mockup #Main (the layout that is read).
## Acceptance criteria
- Given a doctor whose saved specialty text matches a department name or initials in the fixed list of names (any letter case, extra spaces ignored; the list is data the product owner can extend: OQ-45-9, decided), when the EMR opens, then that department's layout is used. (BR45-17)
- Given the specialty is empty or not in the list, then the department the doctor is assigned to in SAH-54-3 is used; given the doctor is in several departments, then (OQ-45-9, decided) the one that matches the clinic's specialty is used, else the first by name, and the doctor can switch department from the header while the visit is a draft (no entered data is deleted). (BR45-17)
- Given no department applies, then the clinic default is used (OQ-45-11, decided: the department matching the clinic's specialty, else General Medicine; a clinic whose specialty is Homeopathy gets General Medicine) and the answer says the choice came from the default. (BR45-17)
- Given the department has a published version, then that version is used; given it has none, then the starter values are used and the EMR shows a small note "Using the starter layout". (BR45-17, SAH-53-5)
- Given the visit is opened for the first time, then a snapshot is stored with the visit: department, published version number (or "starter"), and the ordered list of shown sections with part, required, print, copy, collapsed, who can fill and fields. (BR45-18)
- Given the owner publishes a new version or resets a department after the visit was opened, then the visit still reads its snapshot and judges "required done" by it. (BR45-18)
- Given a visit is signed, when anything tries to change its snapshot, then the change is refused (409 or 403) and the snapshot is unchanged. (BR45-18)
- Given a switch of department for a draft visit, then a new snapshot replaces the old one, no data is deleted (sections not in the new layout are kept in the record but not shown), and one audit entry records who, when, visit id, old and new department. (BR45-18)
- Given the doctor's department switch, then it is not saved as their default. (BR45-17)
- Given a nurse, front desk, billing or pharmacist user, then they cannot read a snapshot (403). (BR45-16)
- Given any call, then no log line holds the doctor's name, the patient's name or clinical text. (BR45-5)
## Notes for design
Resolution order follows the human's brief (doctor specialty, then department, then clinic default); the BRD's order (doctor specialty, then clinic specialty, then General practice) is a difference (SO-45-19). The mockup draws no department switch; the switch is the BRD's "template switch" carried over (OQ-43-18). IPD and Emergency records: OQ-43-27 (answered, default accepted: one record per IPD day and per ER case, DEC-039 addendum).

### SAH-46-4 · As a doctor, I want an IPD or Emergency patient's EMR to open on the same screen, so that I do not learn a second tool
Priority: Must
Screen: IPD → Admitted, IPD → Emergency (EMR mockup #DoctorGeneral).
## Acceptance criteria
- Given an admitted patient's card, when the doctor opens the EMR, then the same frame opens with the encounter set to that admission (differences in SAH-52). (BR45-16)
- Given an Emergency patient's card (triage queue), when the doctor opens the EMR, then the same frame opens with the encounter set to that Emergency case (differences in SAH-52). (BR45-16)
- Given the ward scope and break-glass rules of IPD (SAH-34), then a doctor who may not see a ward's patients cannot open their EMR (403); break-glass access, when used, is audited by the existing IPD path. (BR45-16)
- Given the record model for IPD and Emergency is one note per admission per day and one per Emergency case (OQ-43-27, answered: default accepted, DEC-039 addendum), then this story opens the frame and stores the snapshot; saving a draft per encounter is switched on by the architect's guard removal (ADR-020) and its full behaviour is in SAH-47-5, SAH-51 and SAH-67. (BR45-16)
## Notes for design
SAH-28 triage queue and SAH-23 admission data are read through the ipd `api`.

## Out of scope for SAH-46
Anything inside the EMR frame (SAH-47); visit type, Admit to IPD and the other encounter differences (SAH-52); signing and next step (SAH-51, SAH-67); doctor-specific screens (phase 4).

## Traceability
BR45-5 -> SAH-46-1 to SAH-46-3. BR45-16 -> SAH-46-1, -2, -4. BR45-17 -> SAH-46-3. BR45-18 -> SAH-46-3.

## Open questions
In `docs/requirements/SAH-45.md`, all answered: OQ-45-9 (specialty mapping), OQ-45-11 (default department), OQ-45-19 (doctors per department), OQ-45-22 (button label), OQ-45-23 (allergy wording); decided sign-offs SO-45-16, SO-45-17, SO-45-19, SO-45-27. Answered, default accepted (DEC-039 addendum): OQ-45-30 (44 px). In SAH-43: OQ-43-12, OQ-43-18, OQ-43-27 (record model for IPD and Emergency: answered, default accepted, DEC-039 addendum; one record per IPD day and per ER case).

## Status log
- 12 Nov 2026 · written by the business-analyst from BRD FR-02, DEC-039 and the repo mockup Queue.
- 12 Nov 2026 (later) · updated after the human's answers: OQ-45-9, 11, 19, 22 written in as decided rules; SAH-44-4 withdrawn and replaced by SAH-46-3. Awaiting human approval of `stories`.
