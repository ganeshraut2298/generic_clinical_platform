# SAH-82 · EMR consultation header: compact layout and data arrangement
Module: opd (frontend only: no API and no database change)
Mockup screen: Queue (sidebar → Queue) (the EMR opens from the Queue card; the patient band is drawn in the EMR design package `docs/design/emr-config/emr-config-design-handoff/emr-config/`: `screens/04-DoctorGeneral.png`, `06-DoctorDental.png` and 07 to 11, sources `source/Doctor*.dc.html`, header block at lines 35 to 48 of each file)
Source: human (product owner chat, 19 Nov 2026): "Need to optimize the EMR component and senior product owner arrange data properly", with a screenshot of the live OPD header (Dental, test patient). Builds on SAH-47-1, SAH-52, SAH-67, SAH-73-4, SAH-75-11, DEC-039, DEC-041, DEC-043, DEC-052 (rule: where the doctor screens and the configurator disagree, the doctor screen wins).
Stories: `docs/stories/SAH-82.md`.

## Problem
The live EMR header uses three loose rows (title and controls, then the patient, then the chips). The doctor's name drifts to the far right, the main safety chip (allergy) sits alone on its own line, and a lot of the width is empty. The design draws one compact patient band: avatar, name, one meta line, and the chips on the right of the same band. Department and doctor are drawn in the app top bar, not in the band. Several things the product needs are **not drawn** in the design (Back, department switch, visit-type control, Admit to IPD, the "Consultation" title, two grey layout notes, the Emergency and IPD variants). They were added by approved stories and must stay reachable. This story rearranges what is already there. It removes no approved rule.

The live header is three rows (screenshot). The design band is one block under a top bar. The stories ask for two rows at desktop width: the action row and the band (heights are for the architect and developer to measure and are not stated here).

## Who uses it (roles) and in which setting
| Role | What changes for them |
| --- | --- |
| doctor, visiting doctor | The header they work under in every consultation (clinic room, tablet at the desk, phone). Sees the new arrangement. Can change visit type, department (draft only) and use Admit to IPD exactly as before. |
| owner (with doctor details) | Same as doctor. Also the person who sees the "starter layout" and "clinic default" notes mean something. |
| nurse, front desk, billing, pharmacist, counsellor | Cannot open the EMR (SAH-46); unchanged. Front desk sets the visit type at registration only, not here. |

Setting: OPD clinic consultation room; IPD ward round and Emergency assessment use the same header (SAH-52). Devices: 1440 desktop, tablet, 360 px phone.

## In scope / Out of scope
In scope
- The arrangement of everything in `emr-header.component` (patient band, chips, doctor and department, title, layout notes, department switch, visit-type control, IPD and triage read-only pills, Admit to IPD, Back).
- Behaviour at desktop, tablet and phone widths and when the page scrolls (sticky).
- The offsets of other sticky parts that depend on the header height (section list, right-hand panel, section scroll margin), because the header gets shorter.

Out of scope
- Any API, data or database change. Every value used is already in the loaded record (`header`, `layout`, `departmentOptions`, `admission`, `admissionAdvised`, `mlc`, `readOnly`, `specialty`).
- Moving department and doctor into the app shell top bar (SAH-5; shared by all screens; the doctor-screen top bar was left out on purpose, SO-45-16 and DEC-041 answer 2). Proposed only as a later story (OQ-82-2).
- The design's "ABHA linked" tag: Later (DEC-040 reversed by DEC-049; no linked-ABHA flag in the record).
- The design's "Dr. [NAME], BDS · MDS": the record holds only the doctor's name and room, no qualification. Not shown.
- The design's "Chair 1" (Dental) and "Room 2": the record holds one room text; it is shown as the record gives it.
- Gynaecology "Husband: <name> · Blood group" in the header: no source in the record (ADR-026 H5). Stays out.
- Token number: SAH-47-1 lists it, the live header does not show it and the design does not draw it. Stays out (OQ-82-9).
- Changing what each chip means, the triage dialog, the admission sheet, the specialty strip's mode switches, the Oncology journey strip, the body of the EMR.

## Inventory: every element of the current header
Column "Design" says where the design draws it ("not drawn" means the human must decide, rule 1 of CLAUDE.md). Column "Added by" says which approved story or decision put it there. Column "New place" is the proposed arrangement (band = the patient band; action row = the one slim row above the band, see rules 6 to 9).

| # | Element (current) | Design | Added by | New place |
| --- | --- | --- | --- | --- |
| 1 | Avatar with initials | Yes, band left | SAH-47-1 | Band, left |
| 2 | Patient name | Yes, band, bold | SAH-47-1 | Band, next to avatar |
| 3 | Age ("27 y"; Paediatrics worked-out age "18 months") | Yes, meta line | SAH-47-1, SAH-73-4 | Meta line, first |
| 4 | Sex | Yes, meta line | SAH-47-1 | Meta line |
| 5 | "UHID <id>" or "New patient" | UHID yes; "New patient" not drawn | SAH-47-1 | Meta line |
| 6 | Visit line ("New, 1st visit", "Follow-up, 3rd visit") | Yes on General (meta line); Dental shows the course instead | SAH-47-1 | Meta line, after UHID |
| 7 | Course line (Dental: "RCT on 36 in progress, sitting 2 of 3") | Yes, meta line (Dental) | SAH-75-11 (D9) | Meta line, last |
| 8 | "ABHA linked" | Yes (General, Oncology) | SAH-47-1 | Later (DEC-049). Not shown. |
| 9 | Allergy chip, red "Allergy: <text>" | Yes, right of band | SAH-47-1, ADR-026 D12 | Chips, right of band, first |
| 10 | "Known: <chronic text>" amber | Yes (General) | SAH-47-1 | Chips, right of band |
| 11 | "Last visit: n days ago" grey | Yes (General) | SAH-47-1 | Chips, right of band, last |
| 12 | Vaccine chip (Paediatrics) | Yes, right of band | SAH-75-11 (P6) | Chips, right of band |
| 13 | Payer and pre-authorisation chip (Surgery) | Yes, right of band | SAH-75-11 (G-S6) | Chips, right of band |
| 14 | Grey "No known allergy" (Paediatrics, only when "none" was recorded) | Yes, with the vaccine chip | SAH-75-11 (P6), ADR-026 D12 | Chips, right of band |
| 15 | "Emergency · <triage level>" red | Not drawn | SAH-52-2, SAH-67-4 | Chips, right of band, safety group |
| 16 | "Admission advised" amber | Not drawn | SAH-52-4, SAH-67-3 | Chips, right of band |
| 17 | "MLC" red | Not drawn in the header (MLC is drawn in the body map) | SAH-57-3 | Chips, right of band, safety group |
| 18 | Doctor name and room | Yes, in the top bar (right) | SAH-47-1 | Action row, context text (OQ-82-2) |
| 19 | Department name | Yes, in the top bar ("Dental · Chair 1") | SAH-46, SAH-47-1 | Action row, context text |
| 20 | Title "Consultation" / "Daily progress note" / "Emergency assessment" | Not drawn | SAH-52-1 | OPD: accessible name only; IPD and Emergency: small visible text in the context (OQ-82-3) |
| 21 | Grey note "Using the starter layout" | Not drawn | SAH-46-3, SAH-53-5 | Info control next to the department (OQ-82-4) |
| 22 | Grey note "Department chosen by the clinic default" | Not drawn | SAH-46-3 (OQ-45-11) | Same info control |
| 23 | Department switch (select) | Not drawn | SAH-46-3, OQ-43-18 | Action row, the department itself becomes the select when a switch is allowed |
| 24 | Visit-type control New / Follow-up / Revisit / Emergency | Not drawn as a control (the visit line is drawn) | SAH-52-2, SAH-67-4 | Action row, right |
| 25 | Emergency button text "Emergency · <level>" | Not drawn | SAH-67-4 | Inside the visit-type control |
| 26 | Read-only "IPD · day n" | Not drawn | SAH-52-2 | Action row, right (replaces the control) |
| 27 | Read-only triage level (Emergency encounter) | Not drawn | SAH-52-2 | Action row, right (replaces the control) |
| 28 | Admit to IPD button and its states (Admission advised, Admission requested, Admitted · IP number, Already admitted · IP number) | Not drawn in the header (a request is drawn inside the course and Surgery screens) | SAH-52-4, SAH-67-3 | Action row, far right |
| 29 | Back button | Not drawn (the product's own addition; Back with unsaved changes follows SAH-47-5) | SAH-47-1 | Action row, far left |
| 30 | Sticky behaviour | Not drawn | SAH-47-1 notes | See rule 11 |
| 31 | Specialty strip tags under the header (Dental "Estimate signed" and medical-history tags, Gynae risk chips, Physiotherapy "Session n of m", Oncology cycle chips) | Yes, in the band (right) | SAH-75-6, -7, -9, -11 | Not in this component today; optional move (story 6, OQ-82-10) |

## Business rules (numbered)
1. **Frontend only.** The new arrangement uses only values the record already has. No API field, no database change, no new permission. The only open point about data is the list of "Later" and "no source" items above (row 8 and the out-of-scope list).
2. **One patient band.** The band is one block: avatar, name, one meta line, and the chips on the right of the same band (as drawn in `04-DoctorGeneral.png` and `06-DoctorDental.png`). At desktop width (1280 px and wider) the safety chips (allergy, Emergency, MLC) are never on a separate row below the band's patient block.
3. **Meta line.** One line, in this order: age · sex · UHID (or "New patient") · visit line · course line. Parts that have no value are left out together with their separator. No "ABHA linked".
4. **Chips and their order.** Chips are text as well as colour. Order from the start of the chip group: red safety chips first (Allergy, Emergency with level, MLC), then amber (Known, Admission advised), then the department chips (vaccine or payer, "No known allergy"), then grey "Last visit". The group is right-aligned, wraps inside the band, and when it wraps the safety chips stay on the first line.
5. **Allergy rules from ADR-026 D12 stay.** The red chip shows only a recorded allergy; "none recorded" is never drawn as "Allergy: none"; Paediatrics draws the grey "No known allergy" only when none was recorded. The allergy text is never cut off with "…"; a long text wraps inside the chip. The same allergy is never drawn twice in the band.
6. **One slim action row** above the band holds everything the design does not draw but earlier stories require: Back, the department, the doctor and room, the visit-type control (or the read-only IPD or triage text), and Admit to IPD. It is one line at tablet width and wider.
7. **Doctor and department** are shown together in the action row as "<department> · <doctor name> · <room>" (design: department and room, and the doctor, in the top bar). They are not in the patient band. The doctor's name is never far from the department.
8. **Title and notes.** The OPD title "Consultation" is not drawn as a heading (it stays as the header's accessible name). "Daily progress note" and "Emergency assessment" stay visible as small text in the context because the design draws no IPD or Emergency variant and the doctor must never write into the wrong encounter (SAH-52-1). The two grey notes keep their exact text and move into an info control next to the department name that opens on hover, focus and tap.
9. **Every earlier rule of the controls stays** (SAH-46-3, SAH-52-2, SAH-52-4, SAH-67-3, SAH-67-4): who may change what, read-only states, the triage dialog and its required level, the admit states, the IPD and Emergency variants, the draft-only department switch that is not saved as the doctor's default and deletes no data. Only the position and the size change.
10. **Touch targets** are at least 44 px high and wide, focus is visible, every control has a label (the department select keeps an accessible label even when the visible word "Department" is removed).
11. **Sticky.** The patient band (with the chips) stays in view while the doctor scrolls, at every width, so the allergy chip is never scrolled out of sight. The action row stays with it at desktop width; at tablet and phone width it scrolls away (OQ-82-6). Everything else that sticks under the header (section list, right-hand panel, "scroll to section" offset) follows the real header height, not a fixed number.
12. **Widths.** Desktop 1280 px and wider (design 1440 and 1920), tablet 721 to 1279 px, phone 720 px and narrower (design 360). The design matrix draws only 360 and 1440; tablet is by the wrap rules in the stories (OQ-82-7). No horizontal scrolling of the page at any width from 360 px.
13. **Text.** Plain English, sentence case, no new wording except where the stories name it. Existing strings stay as they are ("Using the starter layout", "Department chosen by the clinic default", "Admit to IPD", "Admission advised", "Emergency · <level>", "IPD · day n").
14. **Privacy.** The patient name, allergy text and chronic text are for the screen only: no log line, URL, browser storage, page title or analytics event holds them; the info-control text and any tooltip never carry the patient's name.
15. **Access unchanged.** Who can open the EMR, who can change the visit type or admit, and the 401, 403 and 404 answers are the backend's rules (SAH-46, SAH-52, SAH-67) and do not change.

## Patient-safety and privacy points
- The allergy chip must always be visible while the doctor works: sticky, first in the chip order, on the same band as the name, never truncated, never behind a menu.
- The Emergency and MLC chips are safety chips with the same rule (not on a separate row at desktop width, first in the order).
- Removing the visible "Consultation" title must not make IPD or Emergency encounters look like an OPD visit: their visible text stays (rule 8).
- A compact layout must not hide the visit type or the admit state: both stay on screen as a control or chip at every width (they may wrap, not collapse into a menu).
- The "starter layout" and "clinic default" notes are about which form the doctor is filling; they stay reachable, only quieter.
- Names, allergy and chronic text never in logs, URLs, browser storage or tooltips that leave the screen.

## Plan or entitlement (which plan/module unlocks it)
OPD module of every plan; the IPD pieces (IPD · day n, Admit to IPD) follow the clinic's IPD switch as before (SAH-52-4). No plan or entitlement change.

## Open questions for the human
Each has options and the proposed default. The stories use the default. Answers go in a decision file; any change after approval is a new story.

- **OQ-82-1 Where do the department switch, the visit-type control and Admit to IPD go?** (A) One slim action row above the patient band: Back left, department and doctor next, visit type and Admit at the right (proposed default). (B) Inside the band, under the chips: crowds the safety chips, rejected. (C) A "More" menu: hides visit type and Admit, which are used in every consult. (D) The app top bar (see OQ-82-2). Proposed: A. The department switch is not a separate control: the department name in the action row becomes the select when switching is allowed.
- **OQ-82-2 Do doctor and department move to the app top bar, as the design draws?** The app top bar is the app shell (SAH-5) shared by every screen. Putting a patient-visit's department and doctor there means the shell must know which EMR is open, every page changes, and the human already decided to leave the design's doctor-screen top bar out (SO-45-16, DEC-041 answer 2). (A) No: keep them in the EMR action row (proposed default; this story). (B) Yes: a separate story for the shell, after this one. Proposed: A now, B only if the human wants it.
- **OQ-82-3 Drop the "Consultation" title?** (A) Drop it for OPD (not drawn; the visit-type control and the department already say what it is), keep "Daily progress note" and "Emergency assessment" visible as small text because the design does not draw those encounters, and keep the title as the accessible name for all three (proposed default; this changes the wording of SAH-52-1 for OPD only). (B) Keep a small word for all three. (C) Drop it everywhere, including the accessible name (not advised). Proposed: A.
- **OQ-82-4 The two grey notes.** (A) An info control next to the department with the exact text, open on hover, focus and tap (proposed default). (B) Show them only to the owner. (C) Remove them from the EMR and show the same facts only in the configurator. (D) Keep them as text in the action row. Proposed: A (nothing is lost, the row stays one line).
- **OQ-82-5 Visit line and course line on Dental.** The design shows only the course ("RCT on 36 in progress, sitting 2 of 3"); the live header shows the visit line too ("New, 1st visit"). (A) Keep both on one meta line, wrapping only on narrow widths (proposed default: nothing approved is lost, SAH-47-1 and SAH-75-11 both hold). (B) Course replaces the visit line when a course exists, as drawn (the visit type is still in the visit-type control, the visit number would be lost). Proposed: A.
- **OQ-82-6 What sticks when the doctor scrolls?** (A) Patient band always; action row too at desktop, scrolls away at tablet and phone (proposed default). (B) Band and action row at every width (about 110 px lost on a phone). (C) Band only at every width (Admit and visit type scroll away on desktop). Proposed: A.
- **OQ-82-7 Tablet widths are not in the design matrix** (only 360 and 1440). Proposed default: tablet follows the wrap rules in story 5 (768 and 1024 px checked). The human may supply a tablet drawing.
- **OQ-82-8 Duplicated facts.** "Emergency · level" and "Admission advised" appear both as a chip and in a control (visit-type control, Admit button) because two approved stories each asked for them. (A) Keep both (proposed default; no approved rule dropped). (B) Keep the chip only for Emergency and let the control read "Emergency". Proposed: A.
- **OQ-82-9 Token number.** SAH-47-1 lists a token in the header; the live header does not show it and the design does not draw it. Proposed: leave it out (as built and as drawn) and correct the SAH-47-1 wording in a later pass; or the human asks for it as a small chip.
- **OQ-82-10 The specialty strip under the header** holds chips the design draws in the band (Dental "Estimate signed" and medical-history tags, Gynae risk chips, Physiotherapy "Session n of m" and package, Oncology cycle chips). (A) Move the summary chips into the band's chip group, keep the mode switches, the Oncology journey strip, the package bar and "Patient alerts" below (proposed default; story 6, Could). (B) Leave the strip as it is for now. Proposed: A, after the core stories ship. This also removes the Dental allergy shown twice.
- **OQ-82-11 Chip count.** If a patient has all chips (allergy, Known, Emergency, MLC, Admission advised, vaccine, Last visit), the group can need two lines. Proposed default: allow two lines inside the band at desktop; a "Known" text longer than two lines is cut with "…" and the full text opens on tap; the allergy text is never cut. The human may prefer a "+n more" chip for non-safety chips.
