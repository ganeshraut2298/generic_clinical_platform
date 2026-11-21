# SAH-82 · Stories: EMR consultation header, compact layout and data arrangement
Requirement: `docs/requirements/SAH-82.md` (rules 1 to 15; open questions OQ-82-1 to OQ-82-11, each with a proposed default that these stories use). Builds on SAH-47-1, SAH-52-1 to -4, SAH-67-3, SAH-67-4, SAH-73-4, SAH-75-11, DEC-052.
Module: opd (frontend only: no API, no database, no new field; the header already reads everything from the loaded record).
Mockup screen: Queue (sidebar → Queue) (the EMR opens from the Queue card; the patient band is drawn in the EMR design package `docs/design/emr-config/emr-config-design-handoff/emr-config/screens/04-DoctorGeneral.png`, `06-DoctorDental.png`, 07 to 11; header block in `source/Doctor*.dc.html` lines 35 to 48)
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details open the EMR (SAH-46); nobody else sees this header and their 401, 403 and 404 answers do not change. The clinic always comes from the logged-in user.
Wording: plain English, sentence case. Existing texts stay exactly as built. Examples use test data only (for example "Test Patient", "Dr Test").
Widths used below: desktop is 1280 px and wider (design 1440 and 1920), tablet is 721 to 1279 px, phone is 720 px and narrower (design 360).
Marking: the placements in stories 2 to 6 are not drawn in the design and follow the proposed defaults of the open questions; the human confirms them with the stories.

### SAH-82-1 · As a doctor, I want the patient shown as one compact band with the meta line and the chips on its right, so that I see who I am treating and what is dangerous at one glance
Priority: Must
Screen: EMR design `04-DoctorGeneral.png` and `06-DoctorDental.png`, patient band (avatar, name, meta line, chips at the right).
## Acceptance criteria
- Given an open EMR at desktop width, then the patient is one band with, from left to right, the avatar with the patient's initials, the name, and one meta line under the name; the chips are in the same band at its right, level with the avatar and name. (rule 2)
- Given the meta line, then it reads in this order: age · sex · "UHID <id>" (or "New patient" when there is no UHID) · visit line (for example "Follow-up, 3rd visit") · course line (Dental only, for example "RCT on 36 in progress, sitting 2 of 3"); a part with no value is left out together with its separator. (rule 3, SAH-47-1, SAH-75-11 D9; OQ-82-5)
- Given a Paediatrics patient with a date of birth, then the age reads as the worked-out age (for example "18 months"); in every other department it reads whole years (for example "27 y"). (SAH-73-4)
- Given the patient's ABHA is linked, then the band does not show "ABHA linked" (Later, DEC-049); nothing is drawn in its place. (out of scope item)
- Given a recorded allergy, then a red chip "Allergy: <text>" is the first chip of the group, on the same band as the name, at desktop width. (rules 2, 4, 5)
- Given any of the chips Allergy, "Emergency · <level>" or MLC applies, when the page is at desktop width, then each is in the chip group on the band and none is on a separate row below the patient block. (rule 2)
- Given several chips apply, then they appear in this order: red (Allergy, "Emergency · <level>", MLC), then amber ("Known: <text>", "Admission advised"), then department chips (vaccine or payer chip, "No known allergy"), then grey "Last visit: n days ago". The group is right-aligned and wraps inside the band; when it wraps, the red chips stay on the first line. (rule 4)
- Given the allergy text is long, then it wraps inside the chip and is never cut with "…"; given the "Known" text is longer than two lines, then it is cut with "…" and the full text opens when the doctor taps or focuses the chip. (rule 5, OQ-82-11)
- Given no allergy is recorded, then there is no red chip and no "Allergy: none" text; given Paediatrics and "none" was explicitly recorded, then the grey chip "No known allergy" shows; given Paediatrics and nothing was recorded, then neither shows. (rule 5, ADR-026 D12)
- Given Paediatrics, then the chip reads "<n> vaccine(s) pending" (red) or "Vaccines up to date" (green); given Surgery, then the payer chip reads "Self-pay" (green), "<payer> · pre-auth pending" (amber) or "<payer> · pre-auth sent" (green), as built. (SAH-75-11 P6, G-S6)
- Given the visit is Emergency, then the chip reads "Emergency · <triage level>"; given the visit has an MLC pin, then the chip reads "MLC"; given admission is advised, then the chip reads "Admission advised". (SAH-52-4, SAH-67-4, SAH-57-3)
- Given every chip is text as well as colour, then a doctor who cannot tell red from amber still reads what each chip says. (rule 4)
- Given a patient with no chips at all, then the band has no empty gap or placeholder and the patient block uses the width. (empty state)
- Given a patient whose Dental medical history also names the same allergy, then "Allergy: <text>" shows once in the band. (rule 5; applies fully once story 6 is built, until then the header draws only its own chip)
- Given the header, then the patient's name, allergy text and chronic text are not in any log line, URL, browser storage, page title or tooltip that leaves the screen. (rule 14)
- Given the record has no header data yet (loading) or the record failed to load, then no half-built band shows; the page's existing "Opening the EMR" and error notice apply. (error case)
## Notes for design
Type sizes in the design: name 17 px bold, meta line 13 px muted, chips 12 px bold with 8 px radius and 6 by 10 px padding; the Dental source draws the avatar as a 48 px circle (the build draws a 36 px rounded square: the developer picks one and tells the human). Chips use the Sahaj status tokens, never fixed colours. The existing `data-testid` and `data-act` hooks (`emr-header`, `course-line`, `vaccine-chip`, `payer-chip`, `no-allergy-chip`, `emergency-tag`, `advised`, `mlc-tag`) stay so tests keep working.

### SAH-82-2 · As a doctor, I want the department and my name shown together in one compact line, so that I see which screen I am in and who is signing, without a heading row
Priority: Must
Screen: EMR design top bar of `06-DoctorDental.png` ("Dental · Chair 1" and "Dr. [NAME]"); the product keeps these in the EMR action row (OQ-82-2).
## Acceptance criteria
- Given an open OPD EMR at desktop width, then the action row shows the department and the doctor as one line of text, for example "Dental · Dr Test GM Live · Room 2", next to each other and not at opposite ends of the screen. (rule 7)
- Given the doctor has no room, then the room and its separator are left out. (rule 7)
- Given an OPD visit, then there is no visible "Consultation" heading, and the header's accessible name still reads "Consultation". (rule 8, OQ-82-3)
- Given an admitted patient's ward round, then the small text "Daily progress note" is visible in the context and is the accessible name; given an Emergency assessment, then "Emergency assessment" is visible in the same way. (rule 8, SAH-52-1)
- Given the doctor and the department, then neither is shown inside the patient band. (rule 7)
- Given the layout is the starter layout, then an info control (a small "i" button, 44 px target) sits next to the department with the exact text "Using the starter layout"; given the department came from the clinic default, then the same control carries "Department chosen by the clinic default"; given both, then both lines show; given neither, then the control is not drawn. (rule 8, OQ-82-4)
- Given a mouse, a keyboard or a touch screen, then the info control's text opens on hover, on focus and on tap, closes with Escape or a tap outside, and is read out as the control's description by a screen reader. (rules 8, 10)
- Given the EMR is read-only, then the same line and the info control still show. (rule 9)
- Given a long department or doctor name, then the line wraps or is cut with "…" with the full text available on focus or tap; it never pushes the controls off the row at desktop width. (error case)
- Given any text of this line or of the info control, then it holds no patient name and no clinical text. (rule 14)
## Notes for design
Proposed only: whether the department and doctor move to the app shell top bar (as drawn) is OQ-82-2; the proposed default keeps them here because the shell (SAH-5) serves every screen. If the human answers yes, this story is replaced by a shell story. The two notes are quiet facts for the owner; do not make the info control look like an error.

### SAH-82-3 · As a doctor, I want the visit-type control, the IPD day and the triage level in the same slim row, so that I can still set or read the visit type with one tap
Priority: Must
Screen: Not drawn in the design as a control (the visit line is drawn); follows SAH-52-2 and SAH-67-4. Placement proposed in OQ-82-1.
## Acceptance criteria
- Given an OPD visit at desktop width, then the segmented control New, Follow-up, Revisit, Emergency is at the right of the action row, on the same line as the department and doctor, with the current type pressed. (rules 6, 9)
- Given the doctor or owner taps another type than Emergency, then the visit type changes as before (SAH-52-2); given the visit is read-only, then all four buttons are disabled and still show which one is pressed. (rule 9)
- Given the doctor taps Emergency, then the triage dialog opens; the type does not change until a level is chosen; closing the dialog without a level changes nothing. (SAH-67-4)
- Given a level is chosen, then the Emergency button reads "Emergency · <level>" and the band shows the chip "Emergency · <level>". (SAH-67-4, OQ-82-8)
- Given an admitted patient's ward round, then the control is replaced by the read-only pill "IPD · day <n>"; given an Emergency assessment, then it is replaced by the read-only triage level pill. (SAH-52-2)
- Given each button, then it is at least 44 px high and wide, has a visible focus ring, and the pressed type is shown by text or shape as well as colour. (rule 10)
- Given a nurse, billing, pharmacist, counsellor or front desk user, then they cannot open the EMR, so they never see the control; a direct call by any of them to change the type is refused as before (403); another clinic's visit 404; no login 401. (rule 15)
- Given the type change fails (slow network or server error), then the existing error notice shows, the pressed type goes back to what is saved, and the page does not freeze. (error case, offline or slow network)
## Notes for design
Keep the `data-type` and `aria-pressed` hooks. The four labels may be shortened by the developer only if the full word stays available to a screen reader.

### SAH-82-4 · As a doctor, I want Back, the department switch and Admit to IPD still reachable in the slim row, so that nothing I used is lost
Priority: Must
Screen: Not drawn in the design (Back, department switch, Admit to IPD); follows SAH-47-1, SAH-46-3, SAH-52-4, SAH-67-3. Placement proposed in OQ-82-1.
## Acceptance criteria
- Given an open EMR, then "Back" (with the back icon) is at the far left of the action row, 44 px high, and returns to where the doctor came from; Back with unsaved changes follows SAH-47-5 as before. (rule 9)
- Given the doctor may switch department (the visit is a draft, not read-only, and the clinic has more than one department option), then the department name in the action row is a compact select with the accessible label "Department", listing the options with the current one chosen; picking another switches the snapshot as before (no entered data deleted, one audit entry, not saved as the doctor's default). (rule 9, SAH-46-3)
- Given the visit is read-only or only one department exists, then the department is plain text, not a select. (rule 9)
- Given the doctor picks the department already chosen, then nothing happens. (SAH-46-3)
- Given an OPD or Emergency EMR, then "Admit to IPD" is at the far right of the action row; given an IPD round, then it is not shown. (SAH-52-4)
- Given the admission state of the visit, then the button reads "Admit to IPD", "Admission advised", "Admission requested", "Admitted · <IP number>" or "Already admitted · <IP number>" as built, with the pressed look for every state except "Admit to IPD". (SAH-52-4, SAH-67-3)
- Given the visit is read-only, then the button is disabled, except that for "Admitted" and "Already admitted" it stays enabled and opens the admission. (SAH-67-3)
- Given the doctor taps the button, then the admission sheet, the "OPD only" sheet, the "Send admission request" wording and the "existing request" rule behave exactly as in SAH-52-4 and SAH-67-3. (rule 9)
- Given a nurse, front desk, billing, pharmacist or counsellor, then they cannot open the EMR and a direct admit call is refused (403); another clinic's visit 404; no login 401. (rule 15)
- Given the Admit button state changes after the sheet closes, then the label and the "Admission advised" chip in the band update together without a page reload. (rule 9, SAH-52-4)
## Notes for design
Keep `data-act="back"`, `data-act="switch-department"` and `data-act="admit"`. At desktop width the three controls and the story 2 and story 3 content fit one row at 1440 px; if they do not at 1280 px, the row may wrap to two lines (it never hides a control).

### SAH-82-5 · As a doctor on a tablet or a phone, I want the header to stay small and keep the allergy in view while I scroll, so that the form has room and I am never without the safety information
Priority: Must
Screen: EMR design layout matrix (360 px phone and 1440 px desktop); tablet is by the wrap rules below (OQ-82-7).
## Acceptance criteria
- Given a desktop screen, then the header is two rows: the slim action row and the patient band; the title row, the loose doctor name and the lone chip row of the current header are gone. (rules 2, 6)
- Given a desktop screen and the doctor scrolls down, then the whole header (action row and band) stays at the top of the page. (rule 11, OQ-82-6)
- Given a tablet screen (721 to 1279 px), then the action row is one line where it fits and otherwise wraps without hiding a control; the chip group wraps under the name and meta line inside the band, red chips first. (rules 6, 12)
- Given a phone (360 px wide), then the band shows avatar, name and meta line first, and the chips wrap below them inside the same band, red chips first; the action row shows Back and the department and doctor on its first line, and the visit-type control and Admit to IPD on a second line; each control is at least 44 px. (rule 12)
- Given a tablet or a phone and the doctor scrolls down, then only the band stays at the top (with the chips); the action row scrolls away and returns when the doctor scrolls up to the top. (rule 11, OQ-82-6)
- Given the header is any height, then the section list, the right-hand panel and the "scroll to section" position start just under the real header and never behind it; there is no gap or overlap at desktop, tablet or phone. (rule 11)
- Given a screen as narrow as 360 px, then the page does not scroll sideways because of the header. (rule 12)
- Given the doctor zooms the text to 200 percent or the patient has a very long name, then the name wraps, no control is cut off, and the allergy chip is still visible. (rules 5, 10)
- Given the Emergency visit, an MLC pin and a long allergy at the same time on a phone, then all three red chips show, and nothing is hidden behind a menu. (rules 4, 5)
- Given a slow connection, then the header shows only after the record has loaded (the page's loading text shows first) and does not jump in height when the chips arrive. (offline or slow network)
## Notes for design
The tablet checks to run are 768 and 1024 px (the design draws only 360 and 1440). The existing page has fixed sticky offsets (150 px, 140 px) that belong to the old header height; they have to follow the new height, for example through one shared value. The existing breakpoints of the page (720 px and the tablet one) are reused where they match.

### SAH-82-6 · As a doctor, I want the department's summary chips (estimate signed, session n of m, risk chips) in the same band, so that all the chips the design puts on the right of the band are in one place
Priority: Could
Screen: EMR design `06-DoctorDental.png` ("Estimate signed"), `07-DoctorGynae.png` (risk chips), `09-DoctorPhysio.png` ("Session n of m"), `11-DoctorOncology.png` (cycle chips). Proposed in OQ-82-10.
## Acceptance criteria
- Given Dental with the estimate signed, then the green chip "Estimate signed" shows in the band's chip group, and the dental medical-history tags (the allergy and the "Medical: ..." tag built from the medical-history chips) show there under the same order rules as story 1. (rule 4, SAH-75-6)
- Given Obstetrics and Gynaecology, then the risk chips ("High risk: previous LSCS", anaemia with Hb, "BP high today") show in the band's chip group. (SAH-75-11 O7)
- Given Physiotherapy, then "Session n of m" and the package name show in the band's chip group, and the package bar stays under the header. (SAH-75-11 P-7)
- Given Oncology, then the cycle and response chips show in the band's chip group; the journey strip, "Patient alerts (n)" and the mode switches (Obstetrics "This visit is", Physiotherapy) stay under the header as built. (SAH-75-9)
- Given the same allergy would show twice (the header's red chip and the Dental medical-history allergy tag), then it shows once. (rule 5)
- Given no summary chip applies, then the band is the same as story 1.
- Given the human answers "leave the strip as it is" (OQ-82-10 option B), then this story is dropped and no other story changes.
## Notes for design
This story only moves chips that already exist; it adds no data. If the specialty strip needs to hand its chips to the header, the developer decides how; the architect is not needed unless a module boundary changes (it does not: both are in the EMR feature).

## Out of scope for SAH-82
Any API, data or database change; "ABHA linked" (Later, DEC-049); doctor qualifications, "Chair 1", husband and blood group (no source in the record); the token number (OQ-82-9); moving department and doctor into the app shell top bar (OQ-82-2); the triage dialog, admission sheet, past visits panel, section list and the body of the EMR; the specialty strip's mode switches and Oncology journey strip.

## Traceability
Rule 1 -> all stories (frontend only). Rule 2 -> 82-1, 82-5. Rule 3 -> 82-1. Rule 4 -> 82-1, 82-6. Rule 5 -> 82-1, 82-5, 82-6. Rules 6 and 7 -> 82-2, 82-3, 82-4, 82-5. Rule 8 -> 82-2. Rule 9 -> 82-3, 82-4. Rule 10 -> 82-2, 82-3, 82-5. Rule 11 -> 82-5. Rule 12 -> 82-5. Rule 13 -> all stories (existing wording kept). Rule 14 -> 82-1, 82-2. Rule 15 -> 82-3, 82-4.

## Open questions
In `docs/requirements/SAH-82.md`: OQ-82-1 (placement of the three controls), OQ-82-2 (app top bar), OQ-82-3 (Consultation title), OQ-82-4 (grey notes), OQ-82-5 (visit line and course line), OQ-82-6 (what sticks), OQ-82-7 (tablet widths), OQ-82-8 (duplicated facts), OQ-82-9 (token), OQ-82-10 (specialty strip chips), OQ-82-11 (chip count). Stories use the proposed defaults.

## Status log
- 19 Nov 2026 · written by the business-analyst from the human's request and screenshot, the EMR design package, the current header component and the approved stories. Awaiting human approval of `stories`.
