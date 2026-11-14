# SAH-65 · Stories: Medical Oncology assessment and today's decision (summary and side effects, examination with BSA and lesion response, pre-chemo labs and ECOG, suggested decision)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-4, BR45-5, BR45-6, BR45-20; clinical rule C-8; decided sign-offs SO-45-1, SO-45-11; answered questions OQ-45-23, OQ-45-25; answered, default accepted (DEC-039 addendum): OQ-45-27, OQ-45-28, OQ-45-30). Module: opd (labs through a Lab module later; scheme through billing). Phase 4 (BRD out of scope, in scope by DEC-039 answer 1). Depends on SAH-47 (frame), SAH-57 (Body map), SAH-56 (EMR list), SAH-48 (Prescription), SAH-51 (finish). Department: Medical Oncology (starter department, 27 sections, SAH-53-1). **Added 12 Nov 2026 at the end of the block (no renumbering): SAH-65-4 (suggested decision and the reason when the doctor differs), split from the oversized SAH-65-3.**
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorOncology, "Medical Oncology · 1 Assessment → decision").
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details (`clinic.consultation.write`) write the screen, on their own patients (owner: all). The oncology nurse records vitals in the vitals step (SAH-9, SAH-30) and never opens the EMR. Nurse, front desk, billing, pharmacist and counsellor get 403 on a direct call. "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body.
**Patient-safety critical.** Every number below is built as drawn and carries the rule tag "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (BR45-4). The tech lead advised against building these unreviewed; the tag stays on each number in the screen help text, the code documents and the tests, so that a clinical review can find them. The screen never makes the decision: it shows a suggestion and "You make the final call".
Vitals limits in this screen are the mockup's own for oncology and differ from General Medicine (SAH-60-1): here SpO2 below 94 is "Low" and diastolic 100 or above is "High". Both are built as drawn in their own screen; the difference is flagged for the human.

### SAH-65-1 · As an oncologist, I want a day-care summary strip, patient alerts and a graded side-effect check, so that I see how the last cycle went
Priority: Should
Screen: EMR mockup #DoctorOncology, summary strip, "1 · Side effects since last cycle", events.
## Acceptance criteria
- Given the screen opens, then a summary strip shows Diagnosis, Stage, Biology (for example receptors), Intent and line, Plan (for example "AC × 4 then paclitaxel") and Response, read from the sections Cancer stage, Histology and receptors and Chemo order and cycles; "Not recorded" for what is missing; and header tags such as the cumulative anthracycline dose, the scheme balance and the allergy tag (SAH-47-1). (BR45-6)
- Given the mockup's top-bar items "Day-care list (n)" and "Patient alerts (n)", then the doctor-screen top bar is not built (SAH-47-1, DEC-041) and "Day-care list" is not shown (no chair or day-care list module); "Patient alerts (n)" stays as a control inside the screen (its place is for design to propose) and counts the alerts active on this screen (Grade 3 or more side effect, labs out of range, fever, weight change of 10% or more, cumulative dose above 400 mg/m², port without blood return) and opens the list of them. (BR45-6)
- Given "1 · Side effects since last cycle", then nine symptoms show: Nausea, Vomiting, Mouth sores, Loose motions, Tingling / numbness, Fatigue, Fever with low counts, Pain and Loss of appetite; each row shows what the patient said (a grade 0 to 4), the grades of the earlier cycles (for example C1, C2, C3) and "Today · your grade" buttons 0 to 4 for the doctor to confirm. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-8). (BR45-4)
- Given the doctor has not tapped a grade for a symptom, then it counts as "not confirmed"; the footer lists "n side effect grade(s)" until all nine are confirmed; the patient's own grade is shown but is not the confirmed grade. Rule tag as above (C-8). (BR45-20)
- Given any confirmed grade is 3 or more, then the alert "Grade 3 or more side effect" shows with the symptom named, and it feeds the suggested decision (SAH-65-4). Rule tag as above (C-8). (BR45-4)
- Given "events since last cycle", then the doctor ticks any of: ER visit, Admitted, Fever ≥ 38 °C at home, Blood transfusion, Antibiotics, Missed oral medicines; "Fever ≥ 38 °C at home" or "Admitted" or "ER visit" counts as a fever or admission event for the suggestion. Rule tag as above (C-8). (BR45-4)
- Given a previous cycle exists, then the grades of earlier cycles come from the earlier signed Oncology visits of this patient (cycle number, grades); a first cycle shows no history. (BR45-6)
- Given a signed visit, then the card is read-only; a correction is an addendum (SAH-51-5). (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; a doctor who is not the visit's doctor (not owner), 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then no grade, event or diagnosis is written to a log line or analytics event together with a patient. (BR45-5)
## Notes for design
The nine symptoms are a fixed list of the product. The grades follow the 0 to 4 scale of the mockup; the text of each grade is not drawn and is not invented.

### SAH-65-2 · As an oncologist, I want examination with vitals, body surface area, measurable lesions and response, systems and port check, so that dosing and response use today's facts
Priority: Should
Screen: EMR mockup #DoctorOncology, "2 · Examination": vitals, BSA, general signs, "Measurable lesions", breast and skin findings, systems, chemo line, Body map.
## Acceptance criteria
- Given the vitals, then steppers show BP systolic, BP diastolic, Pulse, Temperature (°C), SpO₂ (%), Weight (kg) and Height (cm) pre-filled from the nurse's entry (SAH-68-1); each shows a flag in text and colour: systolic 160 or above or below 90 "Check again"; diastolic 100 or above "High"; pulse above 100 "Fast · check for fever, anaemia, heart"; temperature 38 °C or above "Fever · rule out infection before chemo"; SpO₂ below 94 "Low · examine lungs". Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-1, C-8). (BR45-4)
- Given weight and height, then the body surface area shows as the square root of (height in cm × weight in kg ÷ 3600), to two decimals, in m²; a switch "Cap at 2.0 m²" limits the value used for dosing to 2.0 and the screen says "capped at 2.0" when it acts. Rule tag as above (C-8). (BR45-4)
- Given the weight differs from the cycle 1 weight, then a note reads "Cycle 1: x kg · +y%" and, when the change is 10% or more up or down, "· recalculate doses" in red. Rule tag as above (C-8). (BR45-4)
- Given General signs, then Pallor, Icterus, Cyanosis, Clubbing, Lymph nodes (other), Pedal oedema and Dehydration cycle absent → present → clear as in SAH-60-3, with "All absent". (BR45-6)
- Given "Measurable lesions", then each lesion row shows its name, Baseline, Last (C3), Today (longest diameter in cm by calliper, minus and plus 0.1 cm, typed entry), a small trend, the change in percent from baseline and "remove" (message "<lesion> removed from tracker"); "Add a lesion" adds a row; a checkbox "New lesion seen" is set by the doctor. Per lesion the change is green at -30% or lower and red at +20% or higher, with the number in text. Rule tag as above (C-8). (BR45-4)
- Given the lesions, then the response category is computed from the sum of diameters: Progressive disease when a new lesion is seen, or when the sum is 20% or more above the smallest earlier sum (the nadir) and at least 0.5 cm above it; Complete response when lesions exist and today's sum is 0; Partial response when the sum is 30% or more below baseline; otherwise Stable disease; the category shows with colour and words (Complete response, Partial response, Stable disease, Progressive disease). Rule tag as above (C-8). (BR45-4)
- Given Breast and skin findings, then the chips Skin redness, Peau d'orange, Ulceration, Nipple retraction, Fixed to chest wall and Lump now mobile toggle on tap. (BR45-6)
- Given Systems, then five systems show as drawn: Heart (CVS) with the note "Anthracycline: watch for heart failure" and the chips New murmur, Raised JVP, Tachycardia and S3; the lungs; the abdomen (stored under its existing key `pa`, shown as "Abdomen"); the nervous system; and "Spine and bones" (hint "Bone spread"; chips Spine tenderness, Rib tenderness, Restricted movement); each system is marked Normal or Abnormal, with text. Rule tag as above (C-10). (BR45-4)
- Given fewer than five systems are marked, then "systemic exam" stays in the footer and the Done state of the Examination card is not reached; it needs all five, including "Spine and bones". Rule tag as above (C-10). (BR45-20)
- Given "Chemo line or access device", then the choice is Chemo port (right chest), PICC line or Peripheral cannula; for a port the checks Flushes freely, Blood return seen, No redness or swelling, No pain at site, Dressing clean and Flushed in last 4 weeks are ticked by the doctor; the line is ready for a vesicant when Flushes freely, Blood return seen and No redness or swelling are ticked, otherwise the reason "Port: no blood return yet" (or the missing check) shows. Rule tag as above (C-8). (BR45-4)
- Given the section Body map (Lesion body map is required at start for Medical Oncology only where the layout says so), then the Body map of SAH-57 renders (SAH-57-4). (BR45-26)
- Given a value outside what a field allows (negative weight, height over the limit the architect sets), then an error shows and the value is not saved (400 on a direct call). (BR45-20)
- Given a signed visit, then the card is read-only. (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; a doctor who is not the visit's doctor (not owner), 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then no value is written to a log line or analytics event together with a patient. (BR45-5)
## Notes for design
BSA is the formula the mockup shows (the sections list names Mosteller). The rounding and the cap are as drawn. Tap targets 44 px (OQ-45-30).

### SAH-65-3 · As an oncologist, I want pre-chemo labs checked against limits and a performance status, so that I know if the patient can be treated today
Priority: Must
Screen: EMR mockup #DoctorOncology, "3 · Pre-chemo labs" and "4 · Performance status (ECOG)".
## Acceptance criteria
- Given "3 · Pre-chemo labs", then rows show Haemoglobin (g/dL), Neutrophils (ANC) (/µL), Platelets (×10³/µL), Creatinine (mg/dL), Bilirubin (mg/dL), ALT (SGPT) (U/L) and Heart pumping (LVEF) (%), each with the values of cycles C1, C2 and C3 and a small trend, today's value (minus and plus steps 0.2, 100, 10, 0.1, 0.1, 5 and 1, and typed entry) and the "Needed" limit. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-8). (BR45-4)
- Given the limits, then a value fails when: Haemoglobin is below 8; neutrophils below 1,500; platelets below 100; creatinine above 1.5; bilirubin above 1.5; ALT above 100; LVEF below 50. A failed value shows red with the words "Below needed" or "Above needed" and the badge on the card reads for example "2 out of range"; with none failed it reads "Within limits". Rule tag as above (C-8). (BR45-4)
- Given the labs are typed by the doctor because no Lab module exists, then the card reads "Typed by the doctor (labs are not connected yet)" and the "auto-filled from lab" wording of the mockup is not shown (OQ-45-28). Rule tag as above (C-8). (BR45-4)
- Given a "Drawn" date and time for the labs, then the doctor sets it (default: now); the labs are fit to use when they were drawn within 72 hours before the decision; older labs show "Older than 72 hours" in red and count as a failed check in SAH-66-2. The mockup's example reads "Drawn today 8:40 am · auto-filled from lab · within 72 hours". Rule tag as above (C-8). (BR45-4)
- Given LVEF, then it is read from the last echo or entered by the doctor; a value below 50 does not count as a count failure but feeds the suggestion "Change regimen" (SAH-65-4). Rule tag as above (C-8). (BR45-4)
- Given "4 · Performance status (ECOG)", then buttons 0 to 4 show with the descriptions: 0 "Fully active"; 1 "Light work possible"; 2 "Up and about over 50% of day; no work"; 3 "In bed or chair over 50% of day"; 4 "Fully bedbound"; and the line "ECOG n · fit for chemo" at 2 or lower, "ECOG n · usually not fit for chemo" at 3 or 4. Rule tag as above (C-8). (BR45-4)
- Given the ECOG grade is not chosen, then the card is Required and the footer lists "ecog". (BR45-20)
- Given the section values are typed by the doctor, then a value is clinical data, never written to a log line or analytics event. (BR45-5)
- Given a signed visit, then the card is read-only. (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
## Notes for design
Lab limits are a table in the product; the regimen's own limits are not drawn (one set for all regimens). Auto-fill from a Lab module comes with that module.

### SAH-65-4 · As an oncologist, I want a suggested decision worked out from today's facts, and a reason saved when I choose differently, so that the decision is safe and recorded
Priority: Must
Screen: EMR mockup #DoctorOncology, "5 · Today's decision" ("Suggested: …", options, "You chose differently from the suggestion", reasons).
## Acceptance criteria
- Given the card "5 · Today's decision", then it shows "Suggested: <decision>" with the line "Worked out from side effects, examination, labs, ECOG and response. You make the final call." and the reasons behind it, each with a tick or a cross: ECOG, counts and fever, worst side effect grade, response, heart (LVEF and exam), line ready for a vesicant. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-8). (BR45-4)
- Given the engine, then the suggestion is, in this order: Stop chemo when ECOG is 3 or 4; otherwise Delay 1 week when any count or organ lab fails or the temperature is 38 °C or above; otherwise Change regimen when the response is Progressive disease or LVEF is below 50; otherwise Give, reduce dose when the worst confirmed side-effect grade is 3 or more or there was a fever or admission event; otherwise Give as planned. Rule tag as above (C-8). (BR45-4)
- Given the options, then five buttons show: "Give as planned" (Full dose today), "Give, reduce dose" (Next level down), "Delay 1 week" (Repeat counts, re-book), "Change regimen" (New protocol) and "Stop chemo" (Palliative / supportive care); the suggested one carries the word "Suggested"; none is chosen until the doctor taps one. Rule tag as above (C-8). (BR45-4)
- Given the doctor taps an option different from the suggestion, then the message "You chose differently from the suggestion. Pick a reason (saved with the note):" shows with the reasons Patient preference, Tumour board advice, Counts recovering, recheck tomorrow, Clinical judgement and Scheme / cost reasons; one must be picked, and the decision is not recorded until a reason is chosen. Rule tag as above (C-8). (BR45-4)
- Given the doctor picks the suggestion, then no reason is asked. (BR45-4)
- Given a decision is recorded, then it is saved with the visit with the suggestion, the choice, the reason (if any), who and when; the cycle note (SAH-70-2) and the order sheet (SAH-66-1) read it. (BR45-4)
- Given a "Give" decision (Give as planned, Give, reduce dose, Change regimen), then the order sheet opens for it; given Delay 1 week or Stop chemo, then the order sheet shows "Record the hold" in place of "Send chemo order" (SAH-66-2). (BR45-4)
- Given an input changes after the decision (a lab, ECOG, a grade), then the suggestion is recomputed and the card shows "Facts changed. Suggested now: <decision>" while the recorded decision stays until the doctor confirms again. (BR45-4)
- Given the mockup's final call text, then no step in this screen sends, orders or changes anything on its own; sending is the doctor's action in SAH-66-2. (BR45-4)
- Given a signed visit, then the decision is read-only; a change is an addendum with a reason (SAH-51-5). (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; a doctor who is not the visit's doctor (not owner), 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given the audit, then one entry records who, when, visit id, the suggestion key, the choice key and the reason key (keys only, no clinical text). (BR45-5)
## Notes for design
The engine runs on the server so that the same rule applies to all clients and can be tested; every constant carries the tag in the code documents and tests. "Counts" means haemoglobin, neutrophils and platelets; "organ" means creatinine, bilirubin and ALT (LVEF is the heart test).

## Out of scope for SAH-65
Order sheet, doses, sequence, safety checks and second check (SAH-66); take-home medicines, next cycle, referrals and cycle note (SAH-70); lab auto-fill, tumour board and day-care chair lists (OQ-45-28); the frame (SAH-47); Complete (SAH-51).

## Traceability
BR45-4 -> SAH-65-1 to SAH-65-4. BR45-5 -> SAH-65-1 to SAH-65-4. BR45-6 -> SAH-65-1, -2. BR45-16 -> SAH-65-1 to SAH-65-4. BR45-18 -> SAH-65-1 to SAH-65-4. BR45-20 -> SAH-65-1, -2, -3. BR45-26 -> SAH-65-2.

## Open questions
In `docs/requirements/SAH-45.md`: C-1, C-8 and C-10 (all tagged, patient-safety critical), SO-45-11, OQ-45-23, OQ-45-25, OQ-45-27, OQ-45-28, OQ-45-30. To confirm at approval (defaults apply; no new number): the Oncology vitals limits differ from General Medicine; the labs "drawn" time is typed because no Lab module exists; "Patient alerts (n)" counts the alerts on this screen.

## Status log
- 12 Nov 2026 · outline by the business-analyst; updated after DEC-039.
- 12 Nov 2026 (ADR-023 D13, DEC-044 addendum) · aligned with the mockup: oncology examination has five systems including "Spine and bones"; Done and the footer "systemic exam" need all five. Awaiting human approval of `stories`.
- 12 Nov 2026 (phase 4) · full stories written from the source of EMR mockup #DoctorOncology (side effects, vitals flags, BSA, response rule, lab limits, ECOG, decision engine and reasons); SAH-65-4 added at the end of the block. Awaiting human approval of `stories`.
