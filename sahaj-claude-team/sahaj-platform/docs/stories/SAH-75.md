# SAH-75 · Stories: EMR design alignment (audit of 14 Nov 2026, DEC-052)
Requirement: `docs/requirements/SAH-75.md` (rules BR75-1 to BR75-12; open questions OQ-75-1 to OQ-75-16; rules BR45-1 to BR45-29 of `docs/requirements/SAH-45.md` still apply). Decision: `docs/decisions/DEC-052-emr-design-alignment.md`. Audit: `docs/reviews/EMR-design-audit-2026-11-14.md` (gap ids such as G15-1, D2, O-1 are quoted from it). Module: opd.
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorGeneral, #GeneralTreatment, #DoctorDental, #DoctorGynae, #DoctorPaeds, #DoctorPhysio, #DoctorSurgery, #DoctorOncology, #OncologyTreatment, #TreatmentCourse, #BodyMap, #EmrList, #VisitHistory, #CasePaper; configuration screens EMR mockup #Main, #AddDepartment, #FormBuilder under Clinic setup → Specialty template, Case paper and Rx). Design package folder: `docs/design/emr-config/emr-config-design-handoff/emr-config/` (`screens/NN-Name.png`, `source/Name.dc.html`).

Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Doctor, visiting doctor and owner with doctor details write the EMR (doctor and visiting doctor: own patients; owner: all). Nurse, front desk, billing, pharmacist and counsellor never open the EMR (a direct call answers 403). The owner alone writes the configuration (SAH-75-4 data, SAH-75-8 screens); every other clinic role reads it. "Not signed in" answers 401. The clinic always comes from the logged-in user, never from the request body; another clinic's data answers 404. Amounts show as ₹ 1,25,000, dates as 24 Oct 2026, time as 11:05 AM IST.
Rule tag: "value from the EMR mockup, not clinically reviewed" (BR75-5; same meaning as the DEC-039 tag of BR45-4). Labels, headings and sentences quoted in the criteria are the design text, in the design's sentence case, and are the text the screen must show.
Privacy in every story (BR75-11): no patient name, mobile number, ABHA number or clinical note in any log, audit value or analytics event; audit values hold ids, keys, status words and setting values only.
Not changed in any story: the accepted decisions listed in BR75-3 (DEC-039, 041, 042, 043, 044, 048, 049, 051, OQ-45-27, SO-45-9, SO-45-16, SAH-48, the added safety features). Controls the design draws for WhatsApp, SMS, ABHA / ABDM, file upload and photos, lab, pharmacy, stock and OT schedule stay shown switched off with the "Later" note (DEC-049, OQ-45-27, OQ-45-28).
Test levels: [U] unit, [I] integration (QA, `it/`), [B] browser (Playwright, QA).

Suggested order (the architect may change it): 75-4 first (small, data only, also needed by 75-2 counts), then 75-1, 75-2, 75-5, 75-9, 75-3, 75-10, 75-6, 75-7, then the minor-gap stories 75-8 and 75-11. Each story is independently buildable; 75-2 shows correct counts after 75-4 is merged, and 75-3 and 75-10 share the embedded course panel.

---

### SAH-75-1 · As a doctor, I want the doctor screen laid out as drawn (50/50, the section nav only where drawn, the Quick tools tab, the department colour), so that the EMR and the paper sit side by side and each department looks like its design
Priority: Must
Size: M
Screen: `screens/04-DoctorGeneral.png` (nav, Quick tools), `06-DoctorDental.png`, `07-DoctorGynae.png`, `08-DoctorPaeds.png`, `09-DoctorPhysio.png`, `10-DoctorSurgery.png`, `11-DoctorOncology.png`; `13-EmrList.png`, `15-TreatmentCourse.png`, `17-CasePaper.png` (accent). Source: `SCREENS.md` line 36 "Layout of every doctor screen: 50/50. EMR entry on the left, past visits and the live case paper on the right."; `DoctorGeneral.dc.html:50-65,322-328` (nav, left main, right aside `flex: 1 1 0%`); `DoctorGynae.dc.html:154-181,222-227` (Quick tools panes); `DoctorPaeds.dc.html:130-168`; `DoctorPhysio.dc.html:169-196`; Quick tools flag `TOOLS` at `DoctorGeneral.dc.html:809`, `DoctorGynae.dc.html:408`, `DoctorPaeds.dc.html:306`, `DoctorPhysio.dc.html:333` (true) and `DoctorDental.dc.html:856`, `DoctorSurgery.dc.html:274`, `DoctorOncology.dc.html:900` (false). Audit gaps: G-X1, G1 (04), X2, X4, O2, P-5, P5, G-X2, G13-4, G17-4, D4, O6.
## Acceptance criteria
- Given a doctor opens an EMR on any department at a window 1280 px wide or more, when the screen shows, then the EMR entry is the left half and the right half holds the tabs "Past visits" and "Case paper" (and "Quick tools" where this story lists it), and the two halves are of equal width; the right column is no longer capped at 400 px. [B at 1280, 1440, 1920]
- Given a window 1920 px wide, when the "Case paper" tab is open, then the case paper has half of the page width (designed 720 px paper, `17-CasePaper.png`) and none of its text is squeezed or cut off. [B]
- Given the right panel, then its tabs read "Past visits" with the second line "OPD + IPD", "Case paper" with the second line "n of 10 filled · live" (n is the live count), and "Quick tools" with the second line "Helpers for this visit"; the panel stays in view while the left side scrolls (sticky at the top, as drawn). [B]
- Given a window narrower than 1280 px, then the right panel sits under the left panel with its tabs kept at the top of the panel (default for OQ-75-11); there is no horizontal scroll at 1024 px. [B]
- Given General Medicine, then the left nav with the label "EMR sections" is shown: a group "1 Assessment" and a group "2 Treatment", each with its count "n/m" of required sections done; each section row has a tick when done, "!" when required and not done, an empty ring when optional, and the word "Required" when required and not done; at the bottom, "n of m required done" with a progress bar that turns green when all are done. [B] (`DoctorGeneral.dc.html:52-63,483-497`)
- Given Dental, Obstetrics and Gynaecology, Paediatrics, Physiotherapy, General Surgery or Medical Oncology, then no left nav is shown and the EMR uses the full left half (D4, O6, X2). Given a department with no drawn screen (the eight generic departments and a clinic-made department), then the left nav stays as built (default for OQ-75-10). [B]
- Given General Medicine, Paediatrics, Physiotherapy or Obstetrics and Gynaecology, then the tab "Quick tools" is shown; given Dental, General Surgery or Medical Oncology, then no "Quick tools" tab is shown (the design draws none). [B]
- Given General Medicine, when "Quick tools" is open, then it holds what is already built: "Start from a visit template" (with "Fills complaints, diagnosis, tests, medicines and advice in one tap. Change anything after.") and "Trend · last 5 visits". Given Paediatrics, then it holds "Dose by weight" (with "Uses today's weight: n kg. Change weight on the left and this updates.") and "Feeding" (text of the child's feeding history) and nothing of these two sits any more in the left column (P5). Given Physiotherapy, then it holds "Home exercises (n)" and "Goals · tap when achieved" (P-5). [B]
- Given Obstetrics and Gynaecology in the "Antenatal (ANC)" visit mode, when "Quick tools" is open, then it holds three cards in this order: "Due now · n left" (the orders to place, with "Order all"), "PCPNDT Form F" (tag "Legal"), "Supplements" (each with the button "Continue" or "Stopped"); the same three cards no longer sit in the ANC column on the left (O2). Given the "Gynae (not pregnant)" visit mode, then "Quick tools" holds one card "Quick orders". [B]
- Given a department with an accent colour (General Medicine #1D5FA8, Dental #0B6E69, Obstetrics and Gynaecology #A3366B, Paediatrics #B4471F, Physiotherapy #6D3FC0, General Surgery #1E40AF, Medical Oncology #9D174D), then the accent shows on the EMR list (title ring, Add button, chips, "Your most used" count), on the case paper (bar, headings, medicines header) and on the treatment course (active tab, today card ring, collect button) of that department; given any other department, then the app colour is used. [B]
- Given Gynae, Paediatrics or Physiotherapy, then the "1 Assessment / 2 Treatment" part tabs are not shown and the sections follow the published order on one page (default for OQ-75-9); given Dental, General Surgery or Medical Oncology, then the part tabs stay (SAH-75-2 draws their text). [B]
- Given a nurse, front desk, billing, pharmacist or counsellor, then no EMR is reachable (no button on the queue card, 403 on a direct call); given another clinic's visit, 404. [I]
- Given any call of this story, then no log line holds a patient's name or a clinical note (BR75-11). [review]

## Notes for design
- Chips stay 44 px high (OQ-45-30) although the design draws 32 px chips. The layout change is frontend only; the registry that decides which departments have Quick tools and which draw part tabs is data in the frontend or the starter profile (architect decides).
- Nothing is removed from the data: the part setting of a section still saves and still decides the order (OQ-75-9).
- Not changed: Past visits first tab order, Back button (DEC-041), ABHA tag hidden (DEC-049), the "Can be filled by" line on a card (OQ-45-14, G11).

---

### SAH-75-2 · As a doctor, I want each department's step titles, "n of m" badges and ready sentences as drawn, so that I know what is still needed before I complete the visit
Priority: Must
Size: M
Screen: `04-DoctorGeneral.png`, `05-GeneralTreatment.png`, `06-DoctorDental.png`, `07-DoctorGynae.png`, `08-DoctorPaeds.png`, `09-DoctorPhysio.png`, `10-DoctorSurgery.png`, `11-DoctorOncology.png`, `12-OncologyTreatment.png`. Source: `DoctorGeneral.dc.html:499-502,712,786-788`; `DoctorDental.dc.html:802-806,661-662,843-845`; `DoctorGynae.dc.html:397-398`; `DoctorPaeds.dc.html:295-296`; `DoctorPhysio.dc.html:314-315`; `DoctorSurgery.dc.html:243,250,264`; `DoctorOncology.dc.html:762-763,780,889`. Audit gaps: X3, X5, O5, D8, P2, G9, O-8, O2-6, G-S5 (footer part).
## Acceptance criteria
The badge counts below always count the required sections of the published layout for that part (m) and the ones done (n); the design's numbers (for example "1 of 2 required") are what the starter defaults of SAH-75-4 give. A tick replaces the number when every required section of the part is done. Save draft never validates; moving between parts is never blocked; only Complete is (BR45-20).
- **General Medicine.** Given the part tabs, then they read "Assessment" with the second line "Complaints and examination" and "Treatment" with "Diagnosis, medicines, tests and services", each with the badge "n of m required". Given the assessment is not done, then the dark bar under it reads "Still needed in assessment: chief complaint, general examination. You can still go ahead." (the names of the required sections still missing, in lower case, separated by commas) with the button "Continue to Treatment →"; given it is done, then it reads "Assessment done. Next: diagnosis, medicines, tests and services." The nav group count and the "n of m required done" count only required sections, not all sections (G9). The sticky footer reads "Still needed: <missing, lower case>." in red, "Remove the drug or confirm the allergy alert before completing." when a drug clashes with an allergy, and "Ready to complete. Prescription prints in <print language> with English drug names." in green (for example "in Marathi"). Buttons "Save draft" and "Complete visit". [B]
- **Dental.** Given the part tabs, then they read "Assessment" with "Complaints, history, examination, X-rays" and "Treatment" with "Diagnosis, chart, plan, sittings and payments", with the badge "n of m required". The assessment bar reads "Still needed in assessment: chief complaint, medical history review. You can still go ahead." or "Assessment done. Next: diagnosis, chart, plan and payments." The footer reads "n procedure(s) done today will be billed." in green, or "Remove Amoxicillin before completing (penicillin allergy)." in red with "Complete & send to billing" not pressable; buttons "Print estimate" and "Complete & send to billing" (D8). [B]
- **Obstetrics and Gynaecology.** Given the visit mode control "This visit is" with "Antenatal (ANC)" and "Gynae (not pregnant)" and the hint "The screen shows only the sections for the visit type you pick.", then the footer in ANC mode reads "n warning(s) on this visit, reviewed before completing." when there are warnings, else "Next ANC visit suggested at 30 weeks (in 3 weeks)." (the week numbers come from the gestational age); in Gynae mode it reads "Gynae visit: menstrual history required."; the print button reads "Print ANC card" in ANC mode and "Print Rx" in Gynae mode; the second button reads "Complete visit"; no part tabs and no badges are shown (default for OQ-75-9). [B]
- **Paediatrics.** Given the footer, then it reads "n vaccine(s) given today · m medicine(s) prescribed" with the buttons "Print vaccination card" and "Complete visit"; no part tabs and no badges are shown. [B]
- **Physiotherapy.** Given the footer, then it reads "Completing marks session n as used and books session n+1." in green when at least one treatment is selected, else "Select at least one treatment given today." in red; buttons "Progress report" and "Complete session & book next"; no part tabs and no badges. [B]
- **General Surgery.** Given the part tabs, then they read "Assessment" with "Complaints, examination, body map, fitness" and "Treatment" with "Diagnosis, surgery plan, consent, episode, payments", with the badge "n of m required". The assessment bar reads "Add complaints. <fitness sentence>." (for example "Add complaints. 2 test(s) pending: PT / INR, Chest X-ray." or "Fit for surgery.") with "Continue to Treatment →". The footer reads "Ready to complete. Admission letter and pre-op instructions will print." in green, or "Still needed: complaints, diagnosis, OT booking." (the missing ones only) in red; buttons "Save draft" and "Complete visit" (G-S5). [B]
- **Medical Oncology (step 1 and step 2).** Given the part tabs, then they read "Assessment" with "Side effects · examination · labs · decision" and "Treatment" with "Order sheet · safety checks · take-home · next cycle", with the badges "n of 3 done" and "n of 2 done". The footer reads "Ready to complete. Cycle record, take-home medicines and calendar will print." in green, or "Still needed: <n> side effect grade(s), systemic exam, today's decision, diagnosis, send chemo order." (only the missing ones; "record the hold" instead of "send chemo order" when the decision is to hold); buttons "Save draft" and "Complete visit" (O-8, O2-6). [B]
- Given a required section is hidden by the visit mode (Gynae mode hides the ANC check; ANC mode hides menstrual history), then it is not counted in n or m and does not appear in "Still needed" (BR75-9). [U]
- Given the owner changes a required flag in the configurator and publishes, then a new visit shows the new m in its badges and footer, and a visit already opened keeps its own (BR45-18). [I]
- Given a doctor presses "Complete visit" while something required is missing, then the existing "Check before completing" dialog (SAH-51) opens with the missing items; the footer sentence does not replace it. [B]
- Given a slow network, then the counts and sentences show a loading state and do not flash a wrong count. [B]
- Given any call, then no log line holds clinical text (BR75-11). [review]

## Notes for design
- The sentences are built from the published layout's section names and the visit's data; the design's example sections are not hard-coded. Where a department's drawn sentence names a section the starter does not have (for example "OT booking"), the section name in the layout is used.
- Not changed: "Check before completing" dialog and its exact rows (SAH-51), "Complete & call next" label (OQ-45-34).

---

### SAH-75-3 · As a doctor, I want the treatment course shown inside the Dental, Gynae (ANC), Physiotherapy, Surgery and Oncology screens with a way to start one, so that I see the plan, the visits and the money of the treatment without leaving the visit
Priority: Must
Size: L
Screen: `15-TreatmentCourse.png` (the component), `06-DoctorDental.png`, `07-DoctorGynae.png`, `09-DoctorPhysio.png`, `10-DoctorSurgery.png`, `11-DoctorOncology.png`, `12-OncologyTreatment.png`. Source: `TreatmentCourse.dc.html:21-133` (panel), `:141-217` (data per kind, names and units); embedded at `DoctorDental.dc.html:369-371` (kind dental, after the plan, before "Today's prescription"), `DoctorGynae.dc.html:142-144` (kind anc, after "ANC visits", before "Doctor's advice"), `DoctorPhysio.dc.html:157-159`, `DoctorSurgery.dc.html:135`, `DoctorOncology.dc.html:395` (Treatment part, after "F · Next cycle and patient calendar", before "Referrals"). Audit gaps: G15-1 (Major), D3, O1, P-1, G-S2, O2-1. This story supersedes DEC-043 item 11 for the entries; the actions are SAH-75-10.
## Acceptance criteria
- Given a patient has a dental course, when the doctor opens the Dental screen, then the course panel is inside the Treatment part (after the plan and estimate, before "Today's prescription") and replaces the button and link that were there; the full course page stays reachable from the panel with a link "Open full course". [B] (D3)
- Given a patient has a pregnancy course and the visit mode is "Antenatal (ANC)", then the panel is shown in the main column after "ANC visits" with the title "Pregnancy care · ANC course" and the unit word "Visit"; given the mode is "Gynae (not pregnant)", then no course panel is shown. [B] (O1)
- Given a patient has a physiotherapy package, then the panel is shown after the examination cards and before "Doctor's advice", with the package name (for example "Knee rehab package · 10 sessions") and the unit word "Session". [B] (P-1)
- Given a patient has a surgery episode, then the panel is shown after "Consent" and "Pre-op orders" and before "Doctor's advice", named "<procedure> episode" with the unit word "Step". [B] (G-S2)
- Given a patient has a chemotherapy course, then the panel is shown in the Treatment part after "F · Next cycle and patient calendar" and before "Referrals", named "Chemotherapy · <protocol>" with the unit word "Cycle". [B] (O2-1)
- Given the panel, then it shows what the course page shows: the title, the id chip (for example TC-0412), the sub line ("Started <date> · <doctor>" and, only when true, "estimate signed by patient"), the tabs "Visits", "Table" and "Payments", the four tiles "Course estimate", "Paid so far", "Advance balance" (or "Due from earlier" when no advance) and "Due today", the step strip (S1, S2 ... with the date and the chip OPD or IPD), the sitting cards, the "Add to this course:" row and the legend "OPD each sitting opens its own OPD visit", "IPD admissions and day care link to the same course" and "one running account: advances and part payments carry across visits"; the sample note "sample rates" is never shown. [B]
- Given the tab "Payments" in the panel, then the types, the use of advance first, the ₹ 500 steps, the modes UPI, Cash and Card and the receipts list are those of SAH-58-3 and SAH-58-4; a doctor sees the money but "Collect" is not available to a doctor (only the roles of SAH-58-3 collect). All amounts come from the server. [B] [I]
- Given the panel uses the department accent colour (BR75-10), then the active tab, the today card ring and the collect button show it. [B]
- Given a patient has no course of the kind of this department, when the Treatment part (or, for Gynae, the ANC mode) shows, then the panel shows its title, the sentence "No course started yet." and one button "Start course"; the Dental screen keeps its existing start button; pressing it opens the existing course start flow (SAH-58-1) with the kind and name filled for this department; the wording and layout of this empty state are not drawn and wait for the human (OQ-75-12). [B]
- Given the doctor starts a course, then it opens with the kind and the name and the sittings the doctor planned (SAH-58-1); a course belongs to one patient and one clinic. [I]
- Given the patient has more than one course of this kind, then the panel shows the open one first and the existing switcher "Courses of this patient" lets the doctor change (accepted extra, G15-5). [B]
- Given a nurse, pharmacist or counsellor, then a direct call for the course answers 403; given billing or front desk, then the course is read-only except payment (SAH-58-1); given another clinic's course, 404; given a doctor who is not the course's doctor and not the owner, a change answers 403. [I]
- Given the clinic's IPD is off, then the IPD choices and chips are not offered in the panel (SAH-58-2). [B]
- Given a slow network, then the panel shows a loading state; given a failure, an error message with a retry that does not hide the rest of the screen. [B]
- Given any call, then no log line holds the patient's name or the clinical items of a sitting (BR75-11). [review]

## Notes for design
- The panel is the existing course component (SAH-58) hosted in the screen; the API already returns `courseId` and `pregnancyCourseId` (audit G15-1). A billing-counter link and a patient-page link to the course are not asked for in DEC-052 and are not part of this story.
- Not changed: course page route, "Courses of this patient" switcher and the account error banner (G15-5).

---

### SAH-75-4 · As an owner and as a doctor, I want the starter layouts' required flags and shown sections to follow the doctor screens, so that a doctor completes a visit by the rules the screen draws
Priority: Must
Size: S
Screen: `01-Main.png` (the configurator tables that are corrected), `04-DoctorGeneral.png`, `05-GeneralTreatment.png`, `06-DoctorDental.png`, `07-DoctorGynae.png`, `10-DoctorSurgery.png`, `11-DoctorOncology.png`. Source: `DoctorGeneral.dc.html:473-475,478-481` (required: Chief complaints, General examination, Diagnosis, Prescription; "n of 4 required done" at line 60); `DoctorGeneral.dc.html:225-227` (Local examination · body map); `GeneralTreatment.dc.html` (shows "Other procedures & services"); `DoctorGynae.dc.html:189,397` (Menstrual history *, "Gynae visit: menstrual history required."); `DoctorDental.dc.html:661` (medical history review needed); `DoctorSurgery.dc.html:80,103,121,240-241,264` (Pre-op work-up *, Surgery plan and OT booking *, Consent *); `DoctorOncology.dc.html:107` ("Other complaints (optional)", `required=false`). Audit gaps: G3, G4, G2 (seed part), O3, G-S3, O-5 (flag), T1. Decision: DEC-052 items 2 and 6. Database: forward seed migrations only (BR75-4).
## Acceptance criteria
- Given the migration is applied to a database that holds clinics, visits and patients, when it finishes, then only the global starter layouts changed; every clinic's published layouts and drafts, every saved or signed visit (with its snapshot) and every patient row are unchanged (row counts and checksums before and after are equal). [I]
- Given a clinic that has no saved layout of its own for General Medicine (it shows the starter, Template v0), when a doctor opens a new visit, then the required sections are Chief complaints, General examination, Diagnosis and Prescription (four), and "Prescription print format" is not required. [I]
- Given the General Medicine starter, then "Other procedures & services" is shown by default (T1) and a section "Local examination" of kind Body map is added to the Assessment part after "Systemic examination", shown and optional (OQ-75-1); the screen of the body map is SAH-75-5. [I]
- Given the Obstetrics and Gynaecology starter, then "Menstrual history" is required and shown only in the Gynae visit mode, and "Today's ANC check" is required and shown only in the ANC mode (BR75-9); given the doctor completes an ANC visit without menstrual history, then Complete is not blocked; given a Gynae visit without it, then "Still needed: menstrual history." shows. [I] [B]
- Given the General Surgery starter, then the required sections are Chief complaints, Pre-op work-up, Diagnosis, Surgery plan and OT booking and Consent; Prescription is optional and "Prescription print format" is not required (OQ-75-2). [I]
- Given the Medical Oncology starter, then Chief complaints is optional and the card on the Oncology screen is titled "Other complaints (optional)"; given a doctor completes an Oncology visit with no complaint entered, then Complete is not blocked by it. [I] [B]
- Given the Dental starter, then the required sections are Chief complaints, "Medical history and alerts" (done when the doctor presses "Mark as reviewed", SAH-75-6), Diagnosis and Treatment plan; the db-engineer reports in the design note which of these four were already required. [I]
- Given any starter, then "Prescription print format" (rxformat) is not required; given a visit started on a fresh clinic where its header is empty, when the doctor completes it, then it completes and the print uses the clinic letterhead default; this is also checked once in Wave 0 on a fresh visit. [I] [B]
- Given an owner who already published a layout in which a section is required, then that layout is unchanged and still decides new visits until the owner changes it; given the owner uses "Reset to template" on a department, then the department returns to the new starter defaults and Undo restores the previous settings (BR45-13). [I] [B]
- Given a section is required and hidden, then it is not counted (BR45-10); given Allergies and PCPNDT Form F, the hide-locks stay. [U]
- Given a visit is already open, then it keeps the layout it started with and its required flags do not change when this migration runs (BR45-18). [I]
- Given a nurse, front desk, billing, pharmacist or counsellor calls a configuration write, then 403; given the owner of another clinic, then the other clinic's layout is not visible (404). [I]
- Given a published layout of the starters is read twice (a new database and an upgraded database), then both give the same flags. [I]
- Given the migration, then it writes no patient data, no secret and no log of names (BR75-11). [review]

## Notes for design
- Forward migrations only; the existing V2026_12_08_1020 and V2026_12_08_1030 are not edited. The db-engineer decides the exact keys. The configurator tables of `01-Main.png` already differ from these defaults in four places (General examination, Menstrual history, Surgery, Oncology); the doctor screen wins (DEC-052).
- A clinic that already published the old General Medicine or Dental layout does not receive the new sections automatically; the owner adds or resets them (BR75-4). The architect confirms the configurator offers the new sections for adding.
- The values are those of the doctor screens (tag: value from the EMR mockup, not clinically reviewed).
- Not changed: "one configuration for all visit types" (OQ-45-18), versions kept for ever (OQ-45-21).

---

### SAH-75-5 · As a doctor, I want the body map where the design draws it (General Medicine local examination, Surgery local examination, Oncology exam tab, Physiotherapy pain site), so that I mark findings on the body instead of typing them
Priority: Must
Size: M
Screen: `14-BodyMap.png` (the component), `04-DoctorGeneral.png`, `10-DoctorSurgery.png`, `11-DoctorOncology.png`, `09-DoctorPhysio.png`. Source: `DoctorGeneral.dc.html:225-227,713,771-773,798` (card "Local examination · body map", recap "n local finding(s)" and "incl. MLC", dialog row "Local findings"); `DoctorSurgery.dc.html:77`; `DoctorOncology.dc.html:115-117,183-185,630` (tab "Body map", second line "n marked" or "Optional", card "Body map · mark lumps, nodes, skin lesions, port site"); `DoctorPhysio.dc.html:83-96` (card "Pain site"). Audit gaps: G2 (Major), G-S1 (Major), O-2 (Major), P-2 (Major). The body map component and its rules are SAH-57 (not rebuilt).
## Acceptance criteria
- Given General Medicine with the "Local examination" section shown (SAH-75-4), when the doctor opens the Assessment part, then a card "Local examination · body map" is shown after "Systemic examination" and holds the shared body map (tabs Male, Female, Other, skin tones, Front and Back, zoom, pins, findings register); it opens on the patient's recorded sex and the doctor can switch. [B] (G2)
- Given General Medicine and the doctor drops pins, then the findings are saved with the visit; the assessment recap reads "n local finding(s)" and adds "incl. MLC" when any pin is medico-legal; the dialog "Check before completing" shows "Local findings:" with "<finding> · <where> (<cause>)" and " · MLC" per finding, separated by commas, or "None" when there are no pins; the case paper shows the findings under the examination heading. [B] [I]
- Given General Surgery, then the same card "Local examination · body map" is shown after the surgical examination cards and before "Pre-op work-up". [B] (G-S1)
- Given Medical Oncology, then the Examination card has five tabs in this order: "Vitals & general", "Tumour & response", "Body map", "Systems", "Port / line"; the "Body map" tab shows the second line "Optional" when there are no pins and "n marked" when there are, and its panel holds the body map titled "Body map · mark lumps, nodes, skin lesions, port site", opening on the patient's recorded sex. [B] (O-2)
- Given Physiotherapy, then the card "Pain site" shows a body outline (front view, "R" and "L" marked) with the painful region marked, and under it a one-line note such as "Right knee, medial side · worse on stairs, better with rest"; the doctor sets the region by tapping the body and edits the note; the field that was a text input is replaced (default for OQ-75-3: one marker, no findings register, no MLC). [B] (P-2)
- Given a pin has the cause Road accident, Assault or Burn, then the pin is flagged as medico-legal and the message reads "Marked as medico-legal (MLC) for <cause>" as in SAH-57-3; the findings register lists every pin. [B]
- Given the pin form, then the lists (finding, cause, severity, size) and defaults are those of SAH-57-2 (rule tag: value from the EMR mockup, not clinically reviewed); photos are not uploaded (Later, OQ-45-28) and the control is shown switched off with the "Later" note. [B]
- Given the section is hidden in the published layout, then the card is not shown and nothing is required; given it is shown and optional, then a visit with no pins can be completed (the dialog shows "Local findings: None"). [B]
- Given a nurse, front desk, billing, pharmacist or counsellor, then the visit's findings cannot be read or written (403); given another clinic's visit, 404. [I]
- Given invalid input (a pin outside the body figure, a size below zero, a note longer than the existing limit), then the pin is refused with a message and the rest of the visit is not lost. [U] [B]
- Given a slow network, then a pin dropped is kept on screen and saved with the visit's draft; given a failure, the existing draft error shows and the pins are kept. [B]
- Given any call, then no log line holds a finding, a body part with a patient, or a note (BR75-11). [review]

## Notes for design
- The body map component is reused as is (BR45-6). Whether the pain site reuses its geometry in a small read-only variant or a field of the body map is the architect's choice. The minor body-map differences (MLC banner sentence, register case) are SAH-75-8.
- Not changed: keyboard extras of the body map (audit "Matches").

---

### SAH-75-6 · As a dentist, I want the Dental assessment cards as drawn (medical history and alerts, dental history and habits, pain, extra-oral and intra-oral examination, radiographs and photos), so that I ask and record the right things before any injection or extraction
Priority: Must
Size: L
Screen: `06-DoctorDental.png` (all six cards under "Chief complaints"). Source: `DoctorDental.dc.html:69-87` (card frame), `:468-475` (card titles, subtitles, groups and options), `:629-663` (alert rules, button, border, footer), `:488` (case paper lines), `:663` (recap), `:40-47` (header chips). Audit gap: D2 (Major). Rule tag on every clinical list and alert text: value from the EMR mockup, not clinically reviewed.
## Acceptance criteria
- Given the Dental Assessment part, then after "Chief complaints" these six cards are shown in this order, each with the title and second line as written: "Medical history and alerts" / "Ask before any injection, extraction or surgery"; "Dental history and habits" / "Shapes the treatment plan and advice"; "Pain" / "For the main complaint"; "Extra-oral examination" (no second line); "Intra-oral examination" / "Tooth-by-tooth findings go on the chart in Treatment"; "Radiographs and photos" / "Tap to order; attached images open in the viewer". [B]
- Given "Medical history and alerts", then it has the groups "Conditions" (Diabetes, High BP, Heart disease, Thyroid, Asthma, Bleeding disorder, Epilepsy, Pregnant, None), "Medicines" (Blood thinners, Aspirin, Bisphosphonates, Steroids, Thyroid medicine, None) and "Allergies" (Penicillin, Local anaesthetic, Latex, Painkillers (NSAIDs), None), each as round chips that can be many at once; a chosen chip shows "✓ " before its text; choosing "None" clears the others of that group and choosing another clears "None"; a chosen chip other than "None" is shown in red. [B]
- Given the card, then the button "Mark as reviewed" is shown at its top right; when pressed it reads "✓ Reviewed with patient" (green) and the card border, red until then, turns normal; pressing it again takes the review back; the review is saved with the visit and by whom; this is what makes the section Done and satisfies the required flag of SAH-75-4. [B] [I]
- Given conditions, medicines or habits are chosen, then these alert banners show inside the card, each with its bold title and text, only when their condition holds: "Penicillin allergy." "Avoid amoxicillin; use a non-penicillin antibiotic." (Penicillin allergy); "Bleeding risk." "Check INR or stop advice with the physician before extraction or surgery." (Blood thinners, Aspirin or Bleeding disorder); "Diabetes." "Check sugar before surgery; healing is slower."; "BP / heart." "Check BP; limit adrenaline in local anaesthetic." (High BP or Heart disease); "Pregnant." "Avoid X-rays unless essential; second trimester is safest for treatment."; "Bisphosphonates." "Risk of jaw bone problems after extraction or implant."; "Tobacco use." "Screen the mouth for pre-cancer patches and counsel to quit." (Tobacco chewing, Gutkha, Paan / betel or Smoking). Rule tag: value from the EMR mockup, not clinically reviewed. [B] [U]
- Given "Allergies" holds Penicillin and the prescription holds amoxicillin, then the allergy alert of SAH-48 blocks "Complete & send to billing" until the drug is removed or the alert is confirmed, and the dental footer says "Remove Amoxicillin before completing (penicillin allergy)." (SAH-75-2); the header shows the tags "Allergy: Penicillin" and "Medical: on thyroid medicine" built from the chosen chips. Whether the chosen allergies also update the patient's allergy record is OQ-75-16. [B] [I]
- Given "Dental history and habits", then it has "Last dental visit" (single choice: Under 6 months, 6–12 months, Over 1 year, Never), "Brushing" (single: Once a day, Twice a day, Irregular) and "Habits" (many: Tobacco chewing, Gutkha, Paan / betel, Smoking, Alcohol, Teeth grinding, Mouth breathing; chosen habits in red). [B]
- Given "Pain", then it has "Type" (many: Sharp, Throbbing, On chewing, Cold sensitivity, Hot sensitivity, Night pain, Radiating), "Score (0–10)" (single: 0 to 10) and "Since" (single: 1 day, 2–3 days, 1 week, Over 2 weeks); pressing a chosen single choice again clears it. The assessment recap reads "<complaints> · pain n/10 · n medical alert(s): <alert names>". [B]
- Given "Extra-oral examination", then it has "Face" (Symmetrical, Swelling present), "Jaw joint (TMJ)" (Normal, Clicking, Pain, Deviation), "Lymph nodes" (Not palpable, Palpable, tender, Palpable, non-tender) and "Mouth opening" (Normal, Restricted), as chips (this replaces the generic library fields). Given "Intra-oral examination", then it has "Oral hygiene" (Good, Fair, Poor), "Gingiva" (Healthy, Mild inflammation, Bleeding, Recession), "Calculus / stains" (None, +, ++, +++), "Occlusion" (Class I, Class II, Class III) and "Soft tissue" (many: Normal, Ulcer, White patch, Red patch, Tongue coating; abnormal ones in red). [B]
- Given "Radiographs and photos", then it has "Images" (many: IOPA 36 (attached), OPG, Bitewing, CBCT, Intra-oral photos); choosing an image marks it as ordered in this visit (it appears under investigations on the case paper); "(attached)" and the viewer show only when an image is attached; file upload and the viewer are Later (documents service, OQ-45-28) and are shown switched off with the "Later" note; nothing is sent to any service. [B]
- Given the doctor has not pressed "Mark as reviewed", then the Assessment bar reads "Still needed in assessment: medical history review. You can still go ahead." and the part badge counts it as not done (SAH-75-2); Save draft is never blocked. [B]
- Given the case paper, then the examination heading shows "Extra-oral: face <value>, TMJ <value>, nodes <value>" and "Intra-oral: oral hygiene <value>, gingiva <value>, occlusion <value>" and the Dental chart line (FDI); empty values are left out. [B]
- Given a chip tap target, then it is at least 44 px high (OQ-45-30). [B]
- Given a clinic that already published a Dental layout with the old generic sections, then its layout is not rewritten (BR75-4); given a new Template v0 clinic or a "Reset to template", then the six cards are in the layout. [I]
- Given a nurse, front desk, billing, pharmacist or counsellor, then the card data cannot be read or written (403); given another clinic's visit, 404. [I]
- Given a patient's medical history, allergies and habits, then they are clinical data: never in a log, an audit value or an analytics event (BR75-11). [review]
- Given a slow network or a failure on save, then the chosen chips stay on screen and the draft error shows; nothing is lost. [B]

## Notes for design
- The six cards are drawn as one generic chip-card pattern (title, second line, groups of chips, optional action, alerts). Whether they are special editors bound to section keys or a chip-group renderer driven by starter data is the architect's decision; the content above must be seed data for the starter (BR75-4). The existing library sections for these topics are replaced in the Dental starter by a forward migration.
- OQ-75-16 (in the requirement): whether the allergy chips write to the patient record. Default: they are saved with the visit and shown in the header; the write to the patient record follows the existing write-back rule (SAH-68-1), not decided here.
- Not changed: the shared prescription editor (SAH-48, D10), the chart, 3D view and tooth detail built in SAH-49.

---

### SAH-75-7 · As an oncologist, I want the cancer journey strip and the watch chips at the top of the screen, so that I see the stage, the plan, the response and the dose and scheme limits before I decide
Priority: Must
Size: M
Screen: `11-DoctorOncology.png`, `12-OncologyTreatment.png`. Source: `DoctorOncology.dc.html:37-60` (watch chips line 47; strip lines 50-59), `:757-761` (cycle chips), `:771-778` (chip texts, cycle text, response). Audit gap: O-1 (Major). Rule tag: value from the EMR mockup, not clinically reviewed (lifetime anthracycline limit 450 mg/m², response categories).
## Acceptance criteria
- Given the Oncology screen, then under the patient line a block labelled "Cancer journey snapshot" shows five cells in one row, each with a small upper-case label: "Diagnosis" (line 1 the cancer and histology, line 2 the stage in the accent colour, for example "Ca left breast · IDC G2" and "Stage IIIA · cT2 cN2 M0"), "Biology" (for example "ER+ PR+ HER2−" and "Ki-67 30% · BRCA pending"), "Intent · line" (for example "Curative · neoadjuvant" and "1st line · board 26 Jul"), "Plan · <protocol>" (for example "Plan · AC ×4 → Paclitaxel ×4") with the text "Cycle n of m · day 1" and one chip per cycle, and "Response (clinical)" with "<category> · +n%" and the second line "<category word> since baseline". [B]
- Given the plan chips (AC1 ... AC4, P1 ... P4), then a given cycle is green with the hover text "Given", today's cycle is in the accent colour with the hover text "Today", a planned cycle is grey with "Planned"; given today's decision is Delay, then today's chip is amber with "Delayed 1 week" and the text reads "Cycle n delayed". [B]
- Given the response cell, then its category and percentage follow the lesion tracker of the visit (complete response, partial response, stable or progressive; SAH-65-2) and its colour follows the category; given no lesion is measured, then it reads "Not measured". [B]
- Given the watch chips on the right of the patient line, then "Doxorubicin <given> / 450 mg/m²" is shown in amber and "<scheme> · ₹ <amount> left" in green, and a third chip "No known drug allergy" only when "none" was explicitly recorded (OQ-45-23); a chip with no value is hidden (default for OQ-75-8). [B]
- Given the data of the strip, then diagnosis, plan chips, cycle number and response come from the chemotherapy course and the visit; stage, biology, intent, line and board date come from a per-patient "Cancer journey" record; until it exists those cells read "Not recorded"; its entry form is not drawn and waits for the human (OQ-75-7). [B] [I]
- Given the lifetime doxorubicin total, then it is the sum of the doxorubicin doses (mg/m²) of this patient's chemotherapy orders in Sahaj; the order sheet's own check "Cumulative dose within limit" is unchanged (SAH-66-1, rule C-8, tagged). [I] [U]
- Given the strip on the "Treatment" part (`12-OncologyTreatment.png`), then it stays at the top of the screen as on the Assessment part. [B]
- Given a nurse, front desk, billing, pharmacist or counsellor, then the strip data answers 403; given another clinic's patient, 404; given a patient with no chemotherapy course, then the plan cell reads "No chemotherapy plan" and the other cells read "Not recorded". [I] [B]
- Given invalid input in the journey record (a stage outside the list the architect defines, a Ki-67 above 100%), then the value is refused with a message. [U]
- Given a slow network, then the strip shows a loading state, never a half-filled one. [B]
- Given any call, then no log line holds the diagnosis, stage, biology or scheme details with a patient (BR75-11). [review]

## Notes for design
- OQ-75-7 and OQ-75-8 need a human decision before the "Cancer journey" record form is built; the strip itself (read side) and the plan chips can be built now. The architect designs the data in the contract and the database design; this note does not.
- Scheme balance depends on a scheme payment existing in the course ledger (SAH-58-4); no scheme master exists.
- Not changed: header tag "ABHA linked" (hidden, DEC-049), the order sheet's checks and second check (SAH-66, SO-45-42).

---

### SAH-75-8 · As an owner and a doctor, I want the minor differences on the configuration screens and the shared components aligned to the design text, so that labels, copy and small behaviours match
Priority: Should
Size: L
Screen: `01-Main.png`, `02-AddDepartment.png`, `03-FormBuilder.png`, `13-EmrList.png`, `14-BodyMap.png`, `16-VisitHistory.png` (the package screenshot shows the design tool's menu, not the component; criteria are judged from the source), `17-CasePaper.png`. Source file and line are given on each criterion. Audit gaps: G1-1 to G1-10, G2-1, G2-2, G3-1, G3-4, G13-1 to G13-3, G13-5, G14-1, G14-2, G16-1 to G16-3, G17-1 to G17-3. G1-11, G2-4, G14-3, G16-4, G17-5 are information only and are not criteria. Owner writes on the configuration; the shared components serve the doctor.
## Acceptance criteria
**01 Main (configurator)**
- Given a part header, then the number is shown once, in its circle, and the title reads "Assessment · Dental" (part name, dot, department name) with its second line; the number is not repeated in the title (G1-1, `Main.dc.html:104-112,504`). [B]
- Given the count on a part header and on a department row, then they read "n of m on" and "n of m sections on" as plain text, and a required section shows "Required" with no "*" before it (G1-9, `Main.dc.html:112,132,462`). [B]
- Given the Preview tab for a department with a tooth chart section, then the card draws the two tooth rows (upper and lower), a thin dividing line and the legend "Caries", "Filled", "RCT", "Missing"; for the ANC section it draws the ANC card; the old sentence "The special editor for this section comes in a later step." is removed (G1-2, `Main.dc.html:169-185`). [B]
- Given the Preview tab and a department that has a drawn doctor screen, then a link "Open full doctor screen →" is shown in the preview header and opens that screen with example data and no patient (SAH-45-6); for a department without one it is hidden (G1-3, `Main.dc.html:155-157`). [B]
- Given the preview cards for dental chart, prescription, ANC, range of motion, vitals, treatment plan, refraction, growth and immunization, then each spans two columns (G1-4, `Main.dc.html:162-163`). [B]
- Given a required preview card, then its name is followed by the red " *" and no separate "Missing" pill (G1-5, `Main.dc.html:164-168`). [B]
- Given the visit-type buttons, then the line under them reads "Base layout for this department" (one configuration for all visit types stays, OQ-45-18; the design's sentence "Starts from the New consult layout — changes here apply only to <visit>" is not shown because it would be untrue) (G1-6b, `Main.dc.html:96,594`). [B]
- Given the page header, then the breadcrumb reads "Clinic setup / Specialty template / EMR configuration" above the title "EMR configuration by department" and its sentence; the control "Clinic" is a select that shows the clinic name and is disabled when the user has one clinic; the extra title "Clinic setup" is removed (G1-7, `Main.dc.html:21-30`; default for OQ-75-15). [B]
- Given a department row, then it shows the name, "n of m sections on" and the status chip (Template, Draft or Live) only; the version number moves to the department detail heading (G1-8, `Main.dc.html:52-59`). [B]
- Given the buttons "Save draft" and "Publish changes", then both are always pressable: with nothing changed "Save draft" answers "Nothing new to save" and "Publish changes" answers "Nothing to publish"; while a save is in progress a second press is ignored (the owner sees no disabled button flash) (G1-10, `Main.dc.html:33-34,576-578`). [B]
**02 Add department**
- Given the owner opens "Add department", then the template "Physiotherapy" is preselected and its preview ("Turned off by default…") is shown (G2-1, `AddDepartment.dc.html:67-68,159-165`). [B]
- Given the template preview, then the section names carry the design's decorations (for example "Pain assessment (VAS / NPRS)") (G2-2, `AddDepartment.dc.html` preview list). [B]
**03 Form builder**
- Given a form, then the meta line reads "<departments> · <part> · filled by <roles in lower case, separated by commas>" (for example "General Medicine, Surgery · Assessment · filled by doctor, nurse") (G3-1, `FormBuilder.dc.html:621`). [B]
- Given nothing changed, then "Save draft" is pressable and answers "Nothing new to save" (G3-4, `FormBuilder.dc.html:265,288`); the preview's save stays as decided in DEC-042 H9. [B]
**13 EMR list**
- Given the list header, then the count reads "n" (the active items) and, only when some are disabled, "n active · m disabled" (G13-1, `EmrList.dc.html:259`). [B]
- Given a chip, then its attribute is a pill that cycles through the values on each tap (with "▾"), not a select (G13-2, `EmrList.dc.html:68,79-80`). [B]
- Given the Table view, then the columns are "#", the name label, the two attribute labels, "Added on", "Added by", "Active" and an empty last column, and the diagnosis code is shown in the name cell before the name, not as an extra column (G13-3, `EmrList.dc.html:72-78`). [B]
- Given the toolbar, then "Your most used:" has the bolt icon and the tabs have the icons drawn; the print-language selector, "New to list" tag and price column added by SAH-56 and SAH-57 stay (G13-5, `EmrList.dc.html:34-36,93-94`). [B]
**14 Body map**
- Given a finding register line, then the finding is shown as it was entered, not in lower case (G14-2, `BodyMap.dc.html:306`). [B]
- Given an MLC pin, then the banner reads "MLC: record time of examination, who brought the patient, and inform the police as per hospital policy." The sentence "Photos are kept with the record." is added only when file upload exists (OQ-75-6, `BodyMap.dc.html:111`). [B]
**16 Past visits**
- Given an IPD visit, then the medicines heading reads "Discharge medicines", and "Take-home medicines" for a day-care chemotherapy visit; an OPD visit keeps "Medicines" (G16-1, `VisitHistory.dc.html:96,110,127,138,171`). [B]
- Given the visit list, then each visit has a timeline dot on a vertical line at the left, not a card border in colour (G16-2, `VisitHistory.dc.html:168`). [B]
- Given an abnormal result, then it is shown in red without the added words "· Abnormal" (G16-3, `VisitHistory.dc.html:167`). [B]
**17 Case paper**
- Given the tool bar, then the progress text reads "n of 10 sections filled" and no added heading "Live case paper" is shown (G17-1, `CasePaper.dc.html:131`). [B]
- Given 7 or more sections are filled, then the progress bar is green; below 7 it is the accent colour (G17-2, `CasePaper.dc.html:132`). [B]
- Given the doctor presses Print, then the message reads "Sent to printer · A4" (or "A5") and, when fewer than 5 sections are filled, adds " · some sections are still empty" (G17-3, `CasePaper.dc.html:139`). [B]
**All of this story**
- Given a role other than the owner opens the configuration, then it is read only as before; given another clinic's data, 404; given a slow network, the existing loading states stay. [I] [B]
- Given any call, then no log line holds a patient's name or a clinical note (BR75-11). [review]

## Notes for design
- Not changed (BR75-3 and OQ-75-14, OQ-75-15): the "+ Add field" button and field drag handle (SO-45-9), the "Copy an existing department" tile disabled until a saved department exists (DEC-041-6; its reason text is a human wording question), the form builder "Tooth chart" type shown as "Later" (OQ-75-14), the placeholder "Standard code" without ", for reports and ABDM" (consistent with DEC-049), the Teleconsult and WhatsApp-link hint texts of the form settings (consistent with DEC-049), "Medical director" shown as "owner" (OQ-45-13), the Chips and Table view names (DEC-043-1), details closed at the start in Past visits (DEC-043-7), Share off on the case paper (DEC-049), Marathi and Hindi headings unreviewed.
- The screenshot `16-VisitHistory.png` is to be re-exported by the human (DEC-052 item 11); until then criteria for screen 16 are judged from `VisitHistory.dc.html`.

---

### SAH-75-9 · As a paediatrician, I want "Mark all due as given" to record the overdue vaccine too, so that a child who is behind leaves the visit with every due and overdue vaccine given and recorded
Priority: Must
Size: S
Screen: `08-DoctorPaeds.png`. Source: `DoctorPaeds.dc.html:97-120` (card "Immunization · IAP schedule", legend, button), `:207-213` (schedule and overdue list), `:254-267,287-290` (chip states, note, header chip, toast, "Mark all due as given"), `:295,300` (footer, dialog line). Audit gap: P1. Supersedes the built behaviour of SAH-62-2 (due today only). Rule tag: value from the EMR mockup, not clinically reviewed (the vaccine schedule and the rule that overdue vaccines are given at the same visit).
## Acceptance criteria
- Given the card "Immunization · IAP schedule" with the second line "Tap a due or overdue vaccine to mark it given today" and the legend "Given", "Due today", "Overdue", then each vaccine is a chip: given earlier (green, "✓ <name>", not pressable), due today (dashed, orange-brown), overdue (dashed, red, "! <name>"), given today (solid green, "✓ <name> · today"). [B]
- Given at least one vaccine is due today or overdue, when the doctor presses "Mark all due as given", then every due-today vaccine and every overdue vaccine not yet given is recorded as given today in one action, and the toast reads "<n> vaccines recorded" (for example "4 vaccines recorded" for DTwP booster 1, IPV booster 1, Hib booster 1 and Varicella 1); vaccines already given are not touched and vaccines not yet due are never marked. (OQ-75-13 for the batch-number words.) [B] [U]
- Given a vaccine chip that is due or overdue, when the doctor taps it, then it is marked given today and the toast reads "<name> recorded as given today"; given a chip given today, when tapped, then it is unmarked and the toast reads "<name> unmarked"; a vaccine given at an earlier visit cannot be unmarked here. [B]
- Given vaccines are still to give, then the note beside the button reads "n still to give: <names>" and the header chip reads "n vaccine(s) pending" in red; given none is left, then the note reads "All due vaccines given. Next: Hep A 2 at 18–19 months." (the next vaccine comes from the schedule for the child's age) and the chip reads "Vaccines up to date" in green. [B]
- Given nothing is due or overdue, then the button records nothing and the toast reads "0 vaccines recorded". [B]
- Given vaccines given today, then the footer reads "n vaccine(s) given today · m medicine(s) prescribed", the case paper lists "Vaccine given: <name>" under procedures, and the "Complete this visit?" dialog lists "Vaccines given today: <names>" (or "none"). [B]
- Given the dialog, then the checkbox "Send next-vaccine reminder to parent on WhatsApp" is shown switched off with the "Later" note (DEC-049); no message is sent. [B]
- Given the child's age has no schedule set up (outside the 0 to 18 months of the drawn schedule), then the card shows "not set up for this age" and nothing is guessed (OQ-45-32). [B]
- Given each recorded vaccine, then the visit stores the vaccine name, the date, the doctor and, when the doctor unmarks it, the removal; vaccine stock and batch number are Later. [I]
- Given two quick presses of the button, then each vaccine is recorded once. [U]
- Given a nurse, front desk, billing, pharmacist or counsellor, then 403; given another clinic's patient, 404. [I]
- Given a slow network, then the chips keep their state and show a saving state; given a failure, the old state returns with an error message. [B]
- Given any call, then no log line holds the child's name or the vaccines with a child (BR75-11). [review]

## Notes for design
- Safety: the overdue vaccine is marked only by an explicit press of the button or of its own chip, never automatically on opening the screen. The schedule itself is the one built in SAH-62-2.

---

### SAH-75-10 · As a doctor, I want the course actions drawn in the design (mark a sitting done, patient did not come, reschedule, rebook, remove, extra session, move to day care or IPD), so that I run the whole course from its card
Priority: Must
Size: L
Screen: `15-TreatmentCourse.png`. Source: `TreatmentCourse.dc.html:54-77` (sitting cards, action row, "Add to this course:"), `:249-285` (statuses and actions), `:143,157,173,189,202` (header sub lines), `:154,170,186,199,216` (add options per kind), `:322-345` (tabs, stats, payments note). Audit gaps: G15-2, G15-3, G15-4. This story supersedes DEC-043 item 11; it uses the panel of SAH-75-3 and the course page.
## Acceptance criteria
- Given the course (page or embedded panel), then each sitting has one status chip: "Done" (green), "Today" (accent, white text), "Planned" (grey) or "Missed" (red); the step strip shows a tick for Done, the accent dot with a ring for Today, a red dot for Missed and a dashed dot for Planned (purple dashed for IPD); the unit word is Sitting, Visit, Cycle, Step or Session by kind. [B] (G15-3)
- Given the Today card, then it has the main button "Mark <unit> done" (for example "Mark sitting done") in the accent colour and the button "Patient did not come". When "Mark <unit> done" is pressed, then the sitting becomes Done, its charge is added to the account by the server, and the toast reads "<Unit> n done · ₹ <charge> added to the account"; the sitting also becomes Done when its visit is signed (SAH-58-2), and the charge is added only once. [B] [I]
- Given the Today card and "Patient did not come" is pressed, then the sitting becomes Missed, no charge is added and the toast reads "Marked missed." (the drawn sentence "Reminder sent to rebook." is Later, OQ-75-4). [B] [I]
- Given a Missed card, then it has the button "Rebook"; pressing it opens a date choice (today or later) and, once a date is chosen, the sitting becomes Planned with that date and the toast reads "Rebooked for <date>". [B] [I]
- Given a Planned card, then it has "Reschedule: <date> ▾" (a date choice, today or later; the new date is saved), "Remove" and, for an OPD sitting, "Move to day care / IPD"; for a planned IPD sitting it has "Admit now" instead (OQ-75-5). [B]
- Given "Move to day care / IPD" is pressed, then the sitting shows IPD · Day care · request, an admission request is created through the ipd `api` (SAH-23) with the course id, and the toast reads "Will be done as a day-care admission. Admission request created."; given the clinic's IPD is off, then this action is not offered. [B] [I]
- Given "Admit now" is pressed by a role that may admit (owner, front desk, nurse; OQ-45-31), then the existing admission sheet (SAH-23) opens and, on admission, the sitting shows the admission id and the toast reads "Admitted · <id>. Advance from this course is carried to the IPD bill."; given a doctor who is not the owner, then the button reads "Request admission" and sends the pre-filled request. [B] [I]
- Given "Remove" is pressed on a Planned sitting, then it leaves the plan (the later sittings are renumbered) and the toast reads "<Unit> removed from plan"; the removal is kept in the audit with the course id and the sitting number; a Done sitting has no "Remove" and no receipt or advance is ever deleted. [B] [I]
- Given the row "Add to this course:", then the choices of the kind are shown with "+": dental "OPD sitting", "Day-care admission", "IPD admission"; ANC "ANC visit", "Emergency admission", "Delivery admission"; chemotherapy "Chemo cycle", "Review OPD", "Admission for side effects"; surgery "OPD review", "Re-admission", "Day-care procedure"; physiotherapy "Extra session", "Bedside session (IPD)"; choosing one adds a Planned sitting and the toast reads "<choice> added to the course" (SAH-58-2). [B] [I]
- Given a Done card, then it has no action; the Table tab shows the same statuses including "Missed"; the tile "Course estimate" counts "n done" without the Missed ones. [B]
- Given the header sub line, then it reads "Started <date> · <doctor>" and adds "estimate signed by patient" only when the estimate was signed; the words "sample rates" are never shown (G15-4). [B]
- Given an invalid step (Mark done on a Planned or Done sitting, Rebook on a sitting that is not Missed, a date in the past), then the call is refused with 409 or 400 and a message, and nothing changes. [I]
- Given two presses at once or a stale screen (the sitting changed on another device), then the sitting changes once and the second answer is 409 with "This sitting changed. Reload."; the screen reloads the course. [I] [B]
- Given a nurse, pharmacist or counsellor, then 403; given billing or front desk, then the actions are not shown (they collect payments only, SAH-58-3); given a doctor who is not the course's doctor and not the owner, then 403; given another clinic's course, 404. [I]
- Given money, then the charge added, the tiles and the amounts are computed by the server and shown as ₹ 1,25,000; the browser never computes a charge. [I]
- Given each action, then one audit event holds the user, the time, the course id, the sitting number, the old and the new status or date; no clinical items or patient name. [I] [review]
- Given a slow network, then the pressed button shows a working state and cannot be pressed twice; given a failure, the old state returns with an error. [B]

## Notes for design
- Whether sitting actions are new endpoints on the course or part of the sitting resource is the architect's decision. The existing rule of SAH-58-2 (Done by signing the visit) and this story's "Mark done" must end in the same state and one charge.
- "Reschedule" and "Rebook" change the sitting's date only; booking the chair or the appointment is the existing "next sitting" flow (SAH-69-3). The design's sample date lists are not used.
- Not changed: payments tab (SAH-58-3), receipts and the "Courses of this patient" switcher (accepted extras).

---

### SAH-75-11 · As a doctor, I want the minor differences on the doctor screens aligned to the design text, so that each department's cards, labels and small behaviours match what was drawn
Priority: Should
Size: L
Screen: `04-DoctorGeneral.png`, `05-GeneralTreatment.png`, `06-DoctorDental.png`, `07-DoctorGynae.png`, `08-DoctorPaeds.png`, `09-DoctorPhysio.png`, `10-DoctorSurgery.png`, `11-DoctorOncology.png`, `12-OncologyTreatment.png`. Source file and line are given on each criterion. Audit gaps: G5 to G10, T2, D5 to D9, D11, O4, O7, O8, P3, P4, P6, P-3, P-4, P-6 to P-9, G-S4 to G-S8, O-3, O-4, O-6, O-7, O2-2 to O2-5, O2-7 (X6 is in SAH-75-9; X7 library titles are covered by each card below). G5 ("Speak" reads "Voice") and G12 are accepted and not changed. Rule tag on clinical values: value from the EMR mockup, not clinically reviewed.
## Acceptance criteria
**04 and 05 General Medicine**
- Given the general examination, then the card is titled "General examination" with the red " *" and has the six values BP, PR, SpO2, Temp, RR and Pain (BP is one control for systolic and diastolic); "Record general examination" is pressable only when all six are filled; the line beside it reads "Ready: BP <value> · PR <value> · ..." when all six are filled, "n value(s) still empty." when some are, and "Write, type or speak the 6 values, then record them." when none, naming only the entry modes that are on (G6, `DoctorGeneral.dc.html:149-175,649-650`). [B]
- Given the Assessment part, then the cards are "General examination", "Systemic examination" and "Local examination · body map" under their drawn titles, replacing the library titles "Vitals" and "General & systemic examination" (G7, `DoctorGeneral.dc.html:82-86,225-227,479`). [B]
- Given the general examination "Trend" view, then six small charts are shown in a grid of three columns, one each for BP (systolic), PR, SpO2, Temp, RR and Pain, each with the last value in green or red, the line, and the note "Within normal range" or "<flag> at the last reading"; the BP and sugar chart stays in Quick tools (G8, `DoctorGeneral.dc.html:195-208,644-648`). [B]
- Given the Treatment recap, then it reads "From assessment: <complaints with duration, comma separated, or no complaints> · BP <value>, PR <value> (or "general exam not recorded") · n local finding(s) incl. MLC" with the button "← Back to assessment" (G10, `DoctorGeneral.dc.html:713`). [B]
- Given the Treatment part, then its sections are in this order: Diagnosis, Prescription, Investigations, Procedures & services, Advice & follow-up (T2, `DoctorGeneral.dc.html:480`). [B]
**06 Dental**
- Given the Treatment part, then the order is Diagnosis, Dental chart (with its views Chart, 3D model, Tooth detail and the hints as drawn), treatment plan and estimate, treatment course (SAH-75-3), Today's prescription, Doctor's advice (D5, `DoctorDental.dc.html:100-103,369-382`). [B]
- Given the Tooth detail view, then three blocks sit side by side: "Surfaces of <tooth> · tap to mark" (the five-surface cross), "Side view · crown and roots" (dark block) and "Mobility" with "History of <tooth>"; "History of <tooth>" lists the earlier work on that tooth (what, when) from the patient's signed visits and course, and shows "No earlier work on this tooth." when there is none (wording not drawn) (D6, D7, `DoctorDental.dc.html:253-293`). [B]
- Given the header, then the sub line reads "<age> · <sex> · UHID <id> · <course summary>" where the course summary is the running treatment, for example "RCT on 36 in progress, sitting 2 of 3", built from the course and the plan; given no course, the part is left out (D9, `DoctorDental.dc.html:40`). [B]
- Given the 3D view, then the zoom and tilt sliders have the ranges drawn at `DoctorDental.dc.html:233-244` (D11). [B]
**07 Obstetrics and Gynaecology**
- Given the "Gynae (not pregnant)" mode, then "Menstrual history *" shows four rows of chips: "Cycle" (Regular, Irregular), "Cycle length" (< 21 d, 21–27 d, 28–30 d, 31–35 d, > 35 d), "Flow" (Scanty, Normal, Heavy, With clots), "Pain" (None, Mild, Severe); "Examination" shows "Per abdomen", "Per speculum" ("Cervix, discharge"), "Per vaginum" ("Uterus size, adnexa") and the checkbox "Female chaperone present: <name>"; "Contraception" shows six tiles with a second line: None "Not using", Condoms "Barrier", OCP "Daily pill", Cu-T "IUCD, 5–10 years", Injectable "Every 3 months", Sterilisation "Permanent" (O4, `DoctorGynae.dc.html:188-213,372-378`). [B]
- Given the header, then it shows "<age> · UHID <id> · Husband: <name> · Blood group <group>" and risk tags: "High risk: previous LSCS" (red) and, when true, "<Mild|Moderate|Severe> anaemia · Hb <value>" (amber) and "BP high today" (red) (O7, `DoctorGynae.dc.html:40,362-365`). Rule tag: not clinically reviewed. [B]
- Given "Complete this visit?", then the dialog shows the three bullets first ("ANC visit 5 at 27w 1d · BP <value> · Hb <value>", "Ordered: <orders or nothing>", "Next ANC at 30 weeks"; in Gynae mode "Gynae visit · cycle <value>, flow <value>", "Contraception: <value>", "Orders: <orders or none>") and the section rows of "Check before completing" (SAH-51) after them; buttons "Back" and "Complete & call next" (O8, `DoctorGynae.dc.html:244-257,385-387`). [B]
**08 Paediatrics**
- Given the "Give" panel under "Dose by weight", then it shows the dose rule, "Per dose" in mg, and a coloured block "Give" with the volume in large text and the frequency line, then the button "Add to prescription" (P3, `DoctorPaeds.dc.html:141-150`). Rule tag: not clinically reviewed. [B]
- Given the page, then "Growth today *" (WHO 0–5 y) and "Milestones · 18 months" (with "Tap to mark achieved or not yet") sit side by side in one row; below 1280 px they stack (P4, `DoctorPaeds.dc.html:55-95`). [B]
- Given the header, then the vaccine chip sits on the right of the patient line with the "No known allergy" chip only when "none" was recorded (OQ-45-23) (P6, `DoctorPaeds.dc.html:42-45`). [B]
**09 Physiotherapy**
- Given "Pain today *" (NPRS 0–10), then the 11 buttons are coloured by score (0 to 3 green, 4 to 6 amber, 7 to 10 red), with "No pain" and "Worst pain" under them, and a small trend line of the earlier sessions with the sentence "Sessions 1–3: 7, 6, 5. Today: n — improving." (or "— same as last session." or "— worse than last session.") (P-3, `DoctorPhysio.dc.html:67-80,309`). [B]
- Given "Range of motion & strength · knee", then under the table three chips are shown: "Quadriceps R 4/5 · L 5/5", "Hamstrings R 4/5 · L 5/5" and "WOMAC 48 → 41" (the values come from the visit; manual muscle testing is no longer a separate card); the table columns are "Movement", "Right", "Left", "Normal" and "Right vs normal" with a bar; "Right" is typed and "Left" is shown as text (P-4, P-9, `DoctorPhysio.dc.html:99-122`). [B]
- Given "Treatment given today *", then the "Session note" field is inside that card ("e.g. tolerated well, no increase in pain") and "Progress report" is a footer button, not inside the card (P-6, `DoctorPhysio.dc.html:124-139,199-204`). [B]
- Given the header, then it shows "Session n of m" with "Knee package" (the package name) and a bar of m segments: done green, today in the accent colour, the rest grey (P-7, `DoctorPhysio.dc.html:42-50,258`). [B]
- Given no treatment is selected, then "Complete session & book next" looks and acts as not pressable (grey, "not-allowed" cursor) and pressing it shows "Pick the treatments given today first" (P-8, `DoctorPhysio.dc.html:313-317`). [B]
**10 General Surgery**
- Given the Assessment part, then "Pre-op work-up *" is its own card with a fitness pill ("Fit for surgery", "n test(s) pending: <tests>", "Not fit yet: <tests> abnormal" or "PAC not done") and nine test tiles each cycling pending → normal → abnormal on tap, with the line "Tap a test to move it: pending → normal → abnormal." (G-S4, `DoctorSurgery.dc.html:79-87,210-214`). [B]
- Given the Treatment part, then it opens with the recap "From assessment: <complaints> · Murphy <value> · ASA <value> · <fitness>" and "← Back to assessment" (G-S5, `DoctorSurgery.dc.html:96-99,251`). [B]
- Given the header, then it shows chips "<history tags>" and a payer chip: "Self-pay" (green) or "<payer> · pre-auth pending" (amber) / "<payer> · pre-auth sent" (green) (G-S6, `DoctorSurgery.dc.html:40-47,253-254`). [B]
- Given a payer other than Self, then the button reads "Send pre-authorisation to <payer>" and, once done, "✓ Pre-authorisation sent to <payer>"; given Self, "No pre-authorisation needed (self-pay)". Sending to the payer is Later (no integration): the button records "pre-authorisation requested" in the visit and the toast says so, and does not claim the payer received it (G-S7, `DoctorSurgery.dc.html:255-257`). [B]
- Given "OT date", then the chips are the next available OT days as drawn (for example "Mon 17 Nov", "Tue 18 Nov", "Thu 20 Nov", "Sat 22 Nov"); the real days come from the clinic's OT days setting, or from a free-choice date when none is set (no OT schedule exists, OQ-45-28) (G-S8, `DoctorSurgery.dc.html:224`). [B]
**11 and 12 Medical Oncology**
- Given the Examination card, then "Vitals & general" is the first tab and holds the vitals with the "Body surface area" tile and "General signs" with "None present" (O-3, `DoctorOncology.dc.html:115-142`). [B]
- Given the Assessment part, then a sticky bar "Assessment steps" shows five steps in this order, each with a number or a tick: "Side effects", "Examination", "Labs", "ECOG", "Decision", and the cards below follow the numbers "1 · Side effects since last cycle", "2 · Examination", "3 · Pre-chemo labs", "4 · Performance status (ECOG)", "5 · Today's decision" (O-4, `DoctorOncology.dc.html:77-79,752`). [B]
- Given "Measurable lesions · longest diameter (cm, calliper)", then quick-add chips "+ <lesion>" are shown and the table has the columns "Lesion", "Baseline", "Last (C3)", "Today", "Trend", "Change" with a small trend line (O-6, `DoctorOncology.dc.html:145-168`). [B]
- Given "3 · Pre-chemo labs" ("Drawn today 8:40 am · auto-filled from lab · within 72 hours"), then the table has "Test", "C1 · C2 · C3", "Trend" (with the dashed limit line), "Today", "Needed" and an OK or Check mark; "auto-filled from lab" is Later and reads "typed by the doctor" until a lab module exists (O-7, `DoctorOncology.dc.html:208-230`). Rule tag: lab limits not clinically reviewed. [B]
- Given the order sheet header, then it reads "Chemotherapy order sheet", the title "Cycle n · Day 1 · <regimen>" (with " at <level>%" when reduced) and the sub line "Decision: <decision> · Day-care bed <n> · Intent: <intent>" (or "Waiting for decision"), with the button "← Back to assessment" and six stage chips "Decided", "Doses checked", "Second check", "Pharmacy", "Chair", "Given" (O2-2, O2-3, `DoctorOncology.dc.html:267-279,717,841-842`). [B]
- Given "E · Take-home medicines", then the sub line reads "Generic names first · printed in <language>" and each row has a check box, the medicine name, the reason and the dose on the right; given "F · Next cycle and patient calendar", then the language buttons English, मराठी and हिंदी and the line "Dates, fever warning (call if ≥ 38 °C), blood test reminders and the daily symptom check." are shown, with the WhatsApp button switched off (Later, DEC-049) (O2-4, O2-5, `DoctorOncology.dc.html:371-393`). [B]
- Given the order sheet, then any control that is not drawn there is removed or moved to where the design has it (O2-7); the list is in the architect's design note. [B]
**All of this story**
- Given a nurse, front desk, billing, pharmacist or counsellor, then no EMR (403); given another clinic's visit, 404; given a slow network, the existing loading states stay. [I] [B]
- Given any call, then no log line holds a patient's name, a vital value or a clinical note (BR75-11). [review]

## Notes for design
- Items quoted from the audit without a drawn text of their own (O2-7, D11) are listed with the design line; the developer opens that line. If the line does not decide the point, the question goes back to the tech lead, not guessed.
- Not changed: "Speak" reads "Voice" and default mode Type (OQ-45-27, G5), ABHA and Back (DEC-049, DEC-041, G12), SMS reminder text (OQ-45-27, T3), the shared prescription editor (SAH-48, D10), the extra card chrome and "Can be filled by" line (OQ-45-14, G11).

---

## Traceability: rules to acceptance criteria
| Rule | Covered by |
| --- | --- |
| BR75-1 doctor screen wins; design text | all stories (labels quoted) |
| BR75-2 one configuration | 75-2 (badges), 75-4 |
| BR75-3 not changed | note on each story; 75-8, 75-11 last notes |
| BR75-4 seed only, forward only | 75-4 (criteria 1, 9, 11, 13), 75-6 (last-but-two criterion) |
| BR75-5 tagged values | 75-5 (pin form), 75-6, 75-7, 75-9, 75-11 |
| BR75-6 embedded course | 75-3 |
| BR75-7 course actions | 75-10 |
| BR75-8 body map | 75-5 |
| BR75-9 required by visit mode | 75-2 (criterion 9), 75-4 (O&G) |
| BR75-10 accent | 75-1 (accent criterion), 75-3 |
| BR75-11 privacy | the last criterion of every story |
| BR75-12 tap targets | 75-1 (note), 75-6 |

## Gap coverage check (audit gap to story)
G-X1, G1(04), X2, X4, O2, P-5, P5, G-X2, G13-4, G17-4, D4, O6 → 75-1. X3, X5, O5, D8, P2, G9, O-8, O2-6, G-S5 → 75-2. G15-1, D3, O1, P-1, G-S2, O2-1 → 75-3. G3, G4, O3, G-S3, O-5, T1, G2 (seed) → 75-4. G2, G-S1, O-2, P-2 → 75-5. D2 → 75-6. O-1 → 75-7. G1-1 to G1-10, G2-1, G2-2, G3-1, G3-4, G13-1 to G13-3, G13-5, G14-1, G14-2, G16-1 to G16-3, G17-1 to G17-3 → 75-8. P1 → 75-9. G15-2, G15-3, G15-4 → 75-10. G6 to G8, G10, T2, D5 to D7, D9, D11, O4, O7, O8, P3, P4, P6, P-3, P-4, P-6 to P-9, G-S4 to G-S8, O-3, O-4, O-6, O-7, O2-2 to O2-5, O2-7 → 75-11.
Accepted or information only, no criterion: G1-6 (SO-45-9), G1-11, G2-3 (DEC-041-6), G2-4, G3-2 (OQ-75-14), G3-3, G3-5 (DEC-049), G3-6 (OQ-45-13), G5 (OQ-45-27), G11 and G12 (OQ-45-14, DEC-041, DEC-049), G14-3, G15-5, G16-4, G17-5, T3, D10, D1 (Wave 0), runtime checks (QA).

## Open questions
All open questions are in `docs/requirements/SAH-75.md` (OQ-75-1 to OQ-75-16), each with a default that these stories follow.
