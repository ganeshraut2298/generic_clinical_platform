# SAH-60 · Stories: General Medicine screen (vitals and general examination, trend, signs, systems, local findings, visit templates, treatment part)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-4, BR45-5, BR45-6, BR45-19, BR45-20, BR45-22, BR45-23, BR45-26; clinical rule C-1; decided sign-offs SO-45-3, SO-45-4, SO-45-11, SO-45-13, SO-45-14; answered questions OQ-45-14, OQ-45-25, OQ-45-26; answered, default accepted (DEC-039 addendum): OQ-45-27, OQ-45-28, OQ-45-30). Module: opd. Phase 4. Depends on SAH-47 (frame), SAH-48 (Prescription, services), SAH-50 (Past visits), SAH-55 (case paper), SAH-56 (EMR list), SAH-57 (Body map), SAH-51 (finish), SAH-68-1 (nurse vitals read). Department: General Medicine (starter department, 19 sections, SAH-53-1). The section names in this screen are the library sections; the published layout decides which show and which are required (SO-45-3, SO-45-4).
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorGeneral for 1 Assessment and #GeneralTreatment for 2 Treatment).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details (`clinic.consultation.write`) write the screen, on their own patients (owner: all). The nurse records vitals in the existing vitals step (SAH-9) and never opens the EMR. Front desk, billing, pharmacist and counsellor get 403 on a direct call. "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body.
Rule tag: "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (BR45-4). It is repeated on each clinical rule below.
Frame already written in SAH-47 (header, part tabs, section cards, footer "Still needed", Save draft, right panel); this story adds only what is specific to General Medicine.

### SAH-60-1 · As a physician, I want the general examination with vitals pre-filled from the nurse, out-of-range colours and a record of who took them, so that I confirm the vitals quickly and trust them
Priority: Must
Screen: EMR mockup #DoctorGeneral, section "General examination", vitals steppers, "Recorded by", "Record general examination", views "Today" and "History".
## Acceptance criteria
- Given the section General & systemic examination is in the snapshot, then the General examination card shows vitals rows for BP (systolic and diastolic), pulse, SpO2, temperature, respiratory rate and pain, each with a value, a unit, minus and plus buttons and a typed entry; values the nurse recorded in the vitals step of this visit are pre-filled (SAH-68-1). (BR45-6)
- Given the nurse recorded vitals, then the card shows the line "Recorded by <nurse name> at <time>. Record again to add a new entry." with the nurse's name read from Team and access; given no vitals are recorded for this visit, then the line reads "Last taken by <name>, <date and time>. Doctor's entry for this visit is still needed." (BR45-6)
- Given a value is outside the limits, then it is coloured and labelled by text as well: BP systolic 140 or above or diastolic 90 or above "High"; systolic below 90 "Low"; pulse above 100 "High", below 60 "Low"; SpO2 below 95 "Low"; temperature 99.5 °F or above "Fever", below 96 "Low"; respiratory rate above 20 "High". Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-1; replaces the BRD limits, SO-45-11). (BR45-4)
- Given the limits are adult limits, then they apply to every patient in this screen; the Paediatrics screen says its own (OQ-43-23). (BR45-4)
- Given the doctor changes a value or taps "Record general examination", then a vitals entry is saved with the date, time, the doctor as recorder and "Typed" as the way of entry, and the section is Done only after this tap (the nurse's values alone do not make it Done, as for copied values, OQ-45-26). (BR45-20)
- Given views "Today" and "History (n)", then Today is the entry above and History is a table with the columns "Date & time", "Recorded by", "BP", "PR", "SpO2", "Temp", "RR", "Pain" and "How" for every entry of this visit and the patient's earlier entries, newest first; out-of-range values are coloured and labelled in the table too. (BR45-6)
- Given a number is outside what the field allows (for example a negative pulse), then the field shows an error and the value is not saved (400 on a direct call). (BR45-20)
- Given the visit is signed, then the card is read-only; a correction is an addendum (SAH-51). (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; a doctor who is not the visit's doctor (not owner), 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then no log line, audit value or analytics event holds a vital sign value together with a patient; the audit holds ids and counts. (BR45-5)
## Notes for design
Vitals are stored once and read by the nurse step, the EMR and the history (SAH-68-1). The nurse pre-fill and the "Recorded by <nurse>" line are not built before SAH-72 / SAH-68-1 (human's decision, DEC-039 addendum, SO-45-43): until then the doctor types the values and the line shows the doctor's own entry. The ways of entry are Typed, Handwriting and Voice; the last two are SAH-60-6 (Later). Tap targets 44 px (OQ-45-30).

### SAH-60-2 · As a physician, I want to see the trend of blood pressure and sugar over the last five visits, so that I judge control without opening old records
Priority: Should
Screen: EMR mockup #DoctorGeneral, view "Trend" and the card "Trend · last 5 visits" with tabs "BP" and "Sugar".
## Acceptance criteria
- Given the doctor taps the view "Trend", then cards show small charts with the last 5 values for the tab chosen: "BP" (systolic and diastolic over the last 5 visits) and "Sugar" (the sugar readings of the last 5 visits). (BR45-6)
- Given the BP chart, then a target line is drawn at 140 for systolic as in the mockup, and values at or above the line are shown in red with their number in text. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-1). (BR45-4)
- Given a patient with fewer than 5 earlier readings, then the chart shows what exists; given none, then it says "No earlier readings". (BR45-6)
- Given sugar readings, then they come from earlier signed visits of this patient (the blood sugar values saved in the visit; the source section is chosen by the architect, for example the Chronic disease tracker, shared by General Medicine and Diabetology); the unit is mg/dL; no value is invented. (BR45-6)
- Given the card "Trend · last 5 visits" in the right-hand area of the mockup, then it appears under the Past visits tab or as a view of the vitals card; the analyst places it as a view of the General examination card as in the mockup. (BR45-6)
- Given the doctor may not see an earlier IPD ward's readings (ward scope, SAH-34), then those readings are left out and the chart says "Some readings are hidden". (BR45-24)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; another clinic's patient, 404. (BR45-16, BR45-5)
- Given any call, then no reading is written to a log line or analytics event. (BR45-5)
## Notes for design
Charts are drawn by the product (no chart service). Text values accompany every chart for screen readers.

### SAH-60-3 · As a physician, I want general signs, a systemic examination and local findings on a body map, so that I record the examination in a few taps
Priority: Must
Screen: EMR mockup #DoctorGeneral, "General signs" (tap cycle, "All absent"), "Systemic examination" (Normal or Abnormal), "Local examination" (Body map).
## Acceptance criteria
- Given the General examination card, then "General signs" lists Pallor, Icterus, Cyanosis, Clubbing, Lymphadenopathy, Oedema and Dehydration; each tap cycles absent → present → clear (not asked); the button "All absent" sets all seven to absent. (BR45-6)
- Given at least one sign is marked absent or present, then the card's sign part counts as filled; the case paper line reads for example "Pallor absent, icterus absent" (SAH-55-1). (BR45-25)
- Given the section Systemic examination, then five systems show in this order: General, CVS, RS, P/A and CNS, each Normal or Abnormal; the button "All normal" marks all five Normal with the texts: General "No pallor, icterus, cyanosis, oedema", CVS "S1 S2 heard, no murmur", RS "Air entry equal, clear", P/A "Soft, non-tender", CNS "Conscious, oriented, no focal deficit". Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-10). (BR45-4)
- Given a system is marked Abnormal, then a free text box asks "What did you find?" and the system counts as filled only when it has text; the case paper shows the typed text. (BR45-6)
- Given the section is Done when every system is marked (the BRD rule) or, under the generic rule, when at least one field is filled (OQ-45-25, decided), then the generic rule applies; with "All normal" tapped the section is Done at once. (BR45-20)
- Given the section Local examination (Local findings), then the Body map component of SAH-57 renders with Male, Female or Other from the patient's sex, front and back, zoom, pins, findings register and the MLC rule (SAH-57-1 to SAH-57-4); this story adds nothing to it. (BR45-26)
- Given the case paper, then it lists the systems and the pins under Examination in the order of SAH-55-1 and writes "Systemic examination: within normal limits" when all four are normal. (BR45-25)
- Given the visit is signed, then all three parts are read-only. (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; a doctor who is not the visit's doctor (not owner), 403; another clinic's visit, 404. (BR45-16, BR45-5)
- Given any call, then no finding or system text is written to a log line or audit value. (BR45-5)
## Notes for design
The default texts are starter content (C-10). Sign and system lists are fixed lists of the product. The generic rule of OQ-45-25 replaces the BRD's "all four systems marked", because the human accepted it; the "All normal" button keeps the common case to one tap.

### SAH-60-4 · As a physician, I want to start a routine visit from a visit template and use my favourites and the last prescription, so that a repeat visit takes few taps
Priority: Should
Screen: EMR mockup #DoctorGeneral and #GeneralTreatment, card "Start from a visit template", "My favourites:", "Repeat last Rx".
## Acceptance criteria
- Given a new General Medicine visit, then the card "Start from a visit template" shows with the line "Fills complaints, diagnosis, tests, medicines and advice in one tap. Change anything after." and the clinic's visit templates as buttons with a name and a sub line (the mockup's example: "BP + diabetes follow-up", "HTN, T2DM · HbA1c, lipids · repeat Rx"). (BR45-6)
- Given the doctor taps a template, then it fills the visit's complaints, diagnoses, investigations, services, medicines and advice with that template's items, adding to what is there and skipping duplicates, and the message names how many items were added with an Undo that removes exactly what the template added. (BR45-22)
- Given the mockup's example template "BP + diabetes follow-up" (complaints Headache and Giddiness; diagnoses Hypertension I10 and Type 2 diabetes E11.9; investigations HbA1c and Lipid profile; the blood sugar test as a service; the last prescription; advice Low salt diet, 30 min walk daily, Check BP at home), then it is the seed template of a demonstration clinic only. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-10). (BR45-4)
- Given the template adds a service, then its price is the clinic tariff price, never the placeholder rate of the mockup (B-2), and a bill line follows the rule of SAH-48-4. (BR45-23)
- Given a template adds a medicine that clashes with the patient's recorded allergy class, then the alert of SAH-48-2 applies as for any medicine. (BR45-23)
- Given templates are not defined in any other story, then the owner or doctor creates a template from the current visit with "Save as visit template" (name, sub line) and it is shared by the doctors of the clinic; the analyst adds this because the mockup shows the list but not how it is made. A template holds clinical text only, no patient data. (BR45-6)
- Given "My favourites:" and "Repeat last Rx", then they are those of SAH-48-1 and appear in the Prescription card here. (BR45-23)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403 on apply and on save; another clinic's template, 404. (BR45-16, BR45-5)
- Given any call, then no log line holds template content together with a patient. (BR45-5)
## Notes for design
Visit templates are tenant data, not the global department templates of SAH-53. "Save as visit template" and the sharing rule are analyst additions flagged for the human; if the human prefers owner-only maintenance, only the "who may save" criterion changes.

### SAH-60-5 · As a physician, I want diagnosis suggestions, investigations, services, advice and a follow-up in the Treatment part, so that I finish the plan
Priority: Must
Screen: EMR mockup #GeneralTreatment, sections "Diagnosis", "Prescription", "Investigations", "Procedures & services", "Advice" and "Follow-up".
## Acceptance criteria
- Given 2 Treatment, then the section order is the snapshot's (mockup order: Diagnosis, Prescription, Investigations, Procedures & services, Advice, Follow-up); each section uses the shared component: Diagnosis, Investigations and Advice use the EMR list (SAH-56), Prescription and services use SAH-48, Procedures & services use SAH-48-4. (BR45-6)
- Given the doctor types in Diagnosis, then suggestions show with their ICD-10 codes (the mockup's examples: "Hypertension, uncontrolled", "Type 2 diabetes with hyperglycaemia"); the codes come from the diagnosis master (SAH-43; source OQ-43-7). (BR45-22)
- Given the section Follow-up, then the choices are 3 days, 7 days, 15 days, 1 month and 3 months (one selected; SO-45-13), and a note reads "Books an appointment on that day." The mockup adds "and sends an SMS reminder the day before"; the SMS part is shown disabled with the note "SMS reminders: later" (OQ-45-27). The follow-up is created at Complete (SAH-51-6). (BR45-23)
- Given the Advice section, then chips of the doctor's most used advice show with the print language choice (English, Marathi, Hindi) of SAH-48-3. (BR45-22)
- Given the Prescription, then the allergy alert of SAH-48-2 blocks Complete until resolved, and the footer shows that text in place of "Still needed". (BR45-23)
- Given "Continue to Treatment →" and "← Back to assessment" (SAH-47-2), then the recap line reads complaints and examination in one line (for example "Headache 2 days · BP 142/90 · Pallor absent"). (BR45-6)
- Given the required sections at start in the mockup (Chief complaints, Diagnosis, Prescription and Prescription print format), then the footer lists whichever of them the published layout makes required, and nothing the layout does not (SO-45-3, SO-45-4). (BR45-20)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
## Notes for design
The Complete button and dialog "Check before completing" are SAH-51-1. The mockup's General Medicine screen has no separate Vitals or Advice & follow-up sections; it follows the published layout (SO-45-3).

### SAH-60-6 · As a physician, I want handwriting and voice entry shown as drawn but switched off, so that nothing misleads the clinic while those services do not exist
Priority: Could
Screen: EMR mockup #DoctorGeneral, entry modes beside "Record general examination" ("Tap and write", "Recognise handwriting", "Put into fields", voice).
## Acceptance criteria
- Given the General examination card, then the entry modes "Write", "Type" and "Voice" show as in the mockup; "Type" is selected and works; "Write" ("Tap and write", "Recognise handwriting", "Put into fields") and "Voice" are shown disabled with the note "Needs a handwriting and voice service. Later." (OQ-45-27, default accepted: Later, shown disabled). (BR45-6)
- Given the History table column "How", then it shows Typed for every entry made here; Handwriting and Voice never appear until those services exist. (BR45-6)
- Given the disabled modes, then they are reachable by keyboard and announce "Not available yet" to a screen reader. (BR45-6)
- Given nothing is recorded by these modes, then no audio or pen data is created or stored. (BR45-5)
## Notes for design
OQ-45-27 (default Later) is not yet answered by the human. If it is answered "build", this story becomes full criteria and the architect picks a service.

## Out of scope for SAH-60
Frame, parts, footer and Save draft (SAH-47); prescription and drug-allergy alert (SAH-48); Past visits (SAH-50); case paper (SAH-55); EMR list (SAH-56); Body map (SAH-57); Complete, sign, follow-up creation (SAH-51); handwriting and voice services (OQ-45-27); other specialty screens.

## Traceability
BR45-4 -> SAH-60-1, -2, -3, -4. BR45-5 -> SAH-60-1 to SAH-60-6. BR45-6 -> SAH-60-1 to SAH-60-6. BR45-16 -> SAH-60-1 to SAH-60-5. BR45-18 -> SAH-60-1, -3. BR45-20 -> SAH-60-1, -3, -5. BR45-22 -> SAH-60-4, -5. BR45-23 -> SAH-60-4, -5. BR45-24 -> SAH-60-2. BR45-25 -> SAH-60-3. BR45-26 -> SAH-60-3.

## Open questions
In `docs/requirements/SAH-45.md`: C-1 and C-10 (tagged), SO-45-3, SO-45-4, SO-45-11, SO-45-13, SO-45-14 (decided), OQ-45-25 and OQ-45-26 (answered), OQ-45-27 (open, default Later, shown disabled), OQ-45-28, OQ-45-30. From SAH-43: OQ-43-7 (ICD-10 source), OQ-43-23 (child limits). Analyst additions with defaults, to confirm at approval: the nurse's values do not make the card Done until the doctor records, "Save as visit template", and the place of the Trend view.

## Status log
- 12 Nov 2026 · outline by the business-analyst; updated after DEC-039.
- 12 Nov 2026 (ADR-023 D13, DEC-044 addendum) · aligned with the mockup: systemic examination has a first "General" line before CVS, RS, P/A, CNS (SAH-60-3); Done rule unchanged. Awaiting human approval of `stories`.
- 12 Nov 2026 (phase 4) · full stories written from the source of EMR mockup #DoctorGeneral and #GeneralTreatment (signs, systems, vitals, History columns, Trend tabs, visit templates, follow-up choices, entry modes). Awaiting human approval of `stories`.
