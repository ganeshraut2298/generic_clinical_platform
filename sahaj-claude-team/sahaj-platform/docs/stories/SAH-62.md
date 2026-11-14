# SAH-62 · Stories: Paediatrics screen (growth, milestones, immunization schedule, dose by weight, vaccination card)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-4, BR45-5, BR45-6, BR45-20, BR45-23, BR45-29; clinical rule C-5; decided sign-offs SO-45-1, SO-45-41; answered questions OQ-45-25, OQ-45-26; DEC-039 answer 4 for Marathi and Hindi; answered, default accepted (DEC-039 addendum): OQ-45-27, OQ-45-28, OQ-45-30, OQ-45-32 in `docs/requirements/SAH-45.md`; carried over from SAH-43: OQ-43-23 child limits). Module: opd. Phase 4. Depends on SAH-47 (frame), SAH-48 (Prescription), SAH-55 (language headings), SAH-51 (finish). Department: Paediatrics (starter department, 23 sections, SAH-53-1). **Added 12 Nov 2026 at the end of the block (no renumbering): SAH-62-4 (vaccination card, feeding history and the finish summary), split from SAH-62-2.**
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorPaeds).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details (`clinic.consultation.write`) write the screen, on their own patients (owner: all). Nurse, front desk, billing, pharmacist and counsellor get 403 on a direct call (the vaccine given today is recorded by the doctor in this phase). "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body. The patient is a child: the parent or guardian is the contact; no child's name, date of birth or mobile number is written to a log, audit value or analytics event.
Rule tag: "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (BR45-4). It is repeated on each clinical rule below. A wrong dose or a wrong vaccine state is a patient-safety risk; the tech lead advised against building dose and centile rules unreviewed and the human decided to build as drawn (DEC-039 answer 3), so the tag stays on every number in the screen's help, the code documents and the tests.

### SAH-62-1 · As a paediatrician, I want today's growth measurements on a chart with centile notes, so that I see how the child is growing
Priority: Should
Screen: EMR mockup #DoctorPaeds, "Growth today" steppers (weight, length, head circumference), "WHO 0–5 y" chart.
## Acceptance criteria
- Given the section Growth chart (required at start in Paediatrics, copy from last visit on, Nurse / assistant can fill), then "Growth today" shows steppers (minus and plus buttons and typed entry) for Weight (kg, step 0.1), Length (cm, step 0.5) and Head circ. (cm, step 0.5), pre-filled from the nurse's weight if one was recorded in the vitals step, and the child's age in months and days from the date of birth. (BR45-6)
- Given the values, then a chart titled "WHO 0–5 y" plots the child's point for weight against age, with the reference curves behind it, and the point is also stated in text (value and age). (BR45-6)
- Given a weight, then a note beside it shows the centile band as drawn for a child of 18 months: below 8.6 kg "Below 3rd centile" (red), below 9.8 kg "~15th centile", below 11 kg "~25–50th centile", below 13.7 kg "~50–97th centile", otherwise "Above 97th centile" (red); Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-5). (BR45-4)
- Given length and head circumference, then the measured values are shown with no centile note at all: the mockup's fixed texts "~40th centile" and "~50th centile" are not shown and not even "Centile not available for this age", because they do not depend on the value (H8, patient safety; decided by the human in DEC-044: hidden until real WHO tables exist, a later story). Only the weight note is shown. (BR45-4)
- Given a child whose age is not the age the weight bands were drawn for, then the weight note reads "Centile not available for this age" and the point is still plotted on the chart, because the bands in the mockup are fixed for 18 months and the reference tables are not supplied; the architect loads WHO tables as global reference data when the human confirms the source (OQ-45-32 default; see `docs/requirements/SAH-45.md`). No centile is guessed. (BR45-4)
- Given the red notes, then they also carry the words ("Below 3rd centile", "Above 97th centile") so that colour is not the only signal. (BR45-4)
- Given a value is outside what the field allows (a negative number, a weight over the limit the architect sets), then the field shows an error and the value is not saved (400 on a direct call). (BR45-20)
- Given the case paper, then Examination (or the vitals line) shows "Wt x kg · Length y cm · HC z cm" (SAH-55-1). (BR45-25)
- Given Copy from last visit, then the last signed Paediatrics visit's measurements are shown as "Copied from <date>" for comparison only; the section is Done only after today's measurements are recorded (OQ-45-26 applied: copy needs the doctor's confirm). (BR45-20)
- Given the adult out-of-range colours (SAH-47-3), then they are not applied to a child's vitals in this screen; no child limits are drawn, so none are shown (OQ-43-23 default). (BR45-4)
- Given a signed visit, then the section is read-only. (BR45-18)
- Given a nurse (except through the vitals step), front desk, billing, pharmacist or counsellor, 403; a doctor who is not the visit's doctor (not owner), 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then no measurement is written to a log line or analytics event together with a patient. (BR45-5)
## Notes for design
The chart is drawn by the product from reference data (not a service). Source and licence of WHO tables are for the architect after OQ-45-32.

### SAH-62-2 · As a paediatrician, I want milestones and the immunization schedule with due and overdue vaccines, so that no vaccine is missed
Priority: Should
Screen: EMR mockup #DoctorPaeds, "Milestones · 18 months", "Immunization · IAP schedule", legend (Given, Due today, Overdue), "Mark all due as given".
## Acceptance criteria
- Given the child is 18 months, then "Milestones · 18 months" lists six items that toggle on tap between achieved and not yet: Walks alone, Climbs stairs with help (Gross motor); Scribbles with crayon (Fine motor); Feeds self with spoon, Points to show interest (Social); Says 6 or more words (Language); each shows its domain. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-5). (BR45-4)
- Given a child of another age, then the milestone list of the nearest age that the mockup draws is not guessed: the card says "Milestones for this age are not set up" until the human supplies lists (OQ-45-32 default, same note as the growth chart). (BR45-4)
- Given the schedule "Immunization · IAP schedule", then it lists by age group: Birth: BCG, OPV 0, Hep B 1; 6 weeks: DTwP 1, IPV 1, Hib 1, Hep B 2, Rota 1, PCV 1; 10 weeks: DTwP 2, IPV 2, Hib 2, Rota 2, PCV 2; 14 weeks: DTwP 3, IPV 3, Hib 3, Rota 3, PCV 3; 6–9 months: Influenza 1, Influenza 2, Typhoid conj.; 9 months: MMR 1; 12 months: Hep A 1; 15 months: MMR 2, Varicella 1, PCV booster; 16–18 months: DTwP booster 1, IPV booster 1, Hib booster 1; and the later groups of the mockup. Rule tag as above (C-5). (BR45-4)
- Given each vaccine, then it shows one state with text and colour: Given (with the date given), Due today, or Overdue; the legend shows the three. A vaccine is Given when a given date is recorded (here or in an earlier visit); it is Due today when its group's age window includes the child's age today and it is not given; it is Overdue when its group's age is earlier than the current group and it is not given (in the mockup, Varicella 1 is overdue for an 18-month-old). The mapping from age to window is the analyst's reading of the mockup's example, flagged for the human. Rule tag as above (C-5). (BR45-4)
- Given the doctor taps a Due today or Overdue vaccine, then it is marked given today (date today, doctor) and the message reads "<vaccine> given today"; tapping again within the visit undoes it; a vaccine given in an earlier visit cannot be unmarked here. (BR45-6)
- Given the button "Mark all due as given", then every Due today vaccine (not the Overdue ones) is marked given today and the message names how many; the note beside it reads for example "n due today · m overdue". (BR45-6)
- Given the vaccine given today, then it appears in "Vaccines given today" (the library section) and under Procedures on the case paper (SAH-55-1); the batch number and manufacturer are not drawn and not asked. (BR45-25)
- Given the section Immunization (required at start, print on prescription and copy from last visit on), then it is Done when the doctor has opened the schedule and confirmed it with "Schedule reviewed" (analyst addition, so that a child with nothing due can complete); marking any vaccine also makes it Done. (BR45-20)
- Given a signed visit, then it is read-only; a vaccine given earlier is never changed here. (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then no vaccine record is written to a log line or analytics event together with a patient. (BR45-5)
## Notes for design
The schedule is a fixed table of the product (IAP schedule as drawn). Whether a vaccine given today also makes a bill line (a vaccine charge) is not drawn and not built; it is a service the doctor ticks in Procedures & services if the clinic bills it (SAH-48-4).

### SAH-62-3 · As a paediatrician, I want a dose by weight calculator that adds to the prescription, so that I dose a child correctly
Priority: Must
Screen: EMR mockup #DoctorPaeds, card "Dose by weight" (medicine chips, "Uses today's weight", "Dose rule", "Per dose", frequency text, "Add to prescription").
## Acceptance criteria
- Given the card, then it lists the medicines of the mockup as buttons: "Paracetamol syrup 120 mg / 5 ml" (Fever or pain), "Paracetamol syrup 250 mg / 5 ml" (Fever or pain, stronger syrup) and "Ibuprofen syrup 100 mg / 5 ml" (Fever or pain, with food); one is selected. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-5). (BR45-4)
- Given the child's weight recorded today (SAH-62-1), then the card shows "Uses today's weight: x kg"; given no weight, then the card shows "Record today's weight first" and "Add to prescription" is disabled. No weight is estimated from age (OQ-43-23 default: no estimate). (BR45-4)
- Given a medicine, then "Dose rule" shows mg per kg and the calculation is: dose in mg = weight × rule, rounded to a whole mg, and never more than the maximum; paracetamol 15 mg/kg with a maximum of 500 mg a dose; ibuprofen 10 mg/kg with a maximum of 400 mg a dose. "Per dose" shows the mg and the ml; ml = mg divided by the syrup strength per ml (120 mg/5 ml = 24 mg/ml; 250 mg/5 ml = 50 mg/ml; 100 mg/5 ml = 20 mg/ml), rounded to 0.1 ml. Rule tag as above (C-5). (BR45-4)
- Given the weight makes the dose reach the maximum, then the card says "Limited to the maximum dose of <n> mg"; the capped dose is the one shown. Rule tag as above (C-5). (BR45-4)
- Given a medicine, then the frequency text is fixed: paracetamol "every 6 hours if needed, at most 4 doses a day"; ibuprofen "every 8 hours with food, at most 3 doses a day". Rule tag as above (C-5). (BR45-4)
- Given the doctor taps "Add to prescription", then one row is added to the Prescription (SAH-48-1) with the generic name and strength, "<ml> ml (<mg> mg)", the frequency text and "Calculated by weight" as the note; the row can be edited like any other row and the message reads "<medicine> added to prescription". (BR45-23)
- Given the same medicine is already on the prescription, then it is not added twice; the doctor is told "Already on the prescription". (BR45-23)
- Given the patient has a recorded allergy class that matches the medicine's drug flag, then the alert of SAH-48-2 shows for the added row. (BR45-23)
- Given the card, then the line "Calculated from the mockup's dose rules, not clinically reviewed. Check before prescribing." shows in small text (the tag in the screen help, as DEC-039 requires). (BR45-4)
- Given the weight is changed after a row was added, then the row is not changed silently; the card shows "Weight changed. Add the dose again." (BR45-4)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403 on add; another clinic's visit, 404. (BR45-16, BR45-5)
- Given any call, then no dose, weight or drug is written to a log line or analytics event together with a patient. (BR45-5)
## Notes for design
Rates, caps and strengths are product data (a small table), not clinic-editable. Each number has a test that quotes this story's tag. The BRD listed dose checking as out of release 1; in scope by DEC-039 answer 1. Tap targets 44 px (OQ-45-30).

### SAH-62-4 · As a paediatrician, I want to print the vaccination card, record the feeding history and see a finish summary, so that the parent leaves with the card and the record is complete
Priority: Should
Screen: EMR mockup #DoctorPaeds, "Print vaccination card", "Feeding history", dialog summary and "Send next-vaccine reminder to parent on WhatsApp".
## Acceptance criteria
- Given the button "Print vaccination card", then the browser print opens a card with the clinic header, the child's name and date of birth, every vaccine of the schedule with its date given or its state (Due today, Overdue) and the next due vaccine with its age; the toast reads "Vaccination card sent to printer". (BR45-25)
- Given the print language choice (English, Marathi or Hindi) of the visit (SAH-55-2), then the card headings print in that language; vaccine names stay in English. The Marathi and Hindi words for the card headings are not in the mockup; until the human supplies reviewed wording (OQ-45-32 default) the card prints English headings and the language choice shows "Marathi and Hindi card wording: later". (BR45-25)
- Given the section Feeding history (free text in the mockup), then it renders as a text field with chips for common feeds (breast milk, formula, solids as the library fields allow); the text is saved with the visit and shown under History on the case paper. (BR45-6)
- Given the dialog "Check before completing" (SAH-51-1), then its summary reads "Weight <x> kg (<centile note>)", "Vaccines given today: <list or none>" and "Prescription: <list or none>"; the mockup's last line says "n given today · m medicine(s) prescribed" in the footer. (BR45-6)
- Given the dialog, then the checkbox "Send next-vaccine reminder to parent on WhatsApp" is shown as drawn, switched off with the note "Needs the WhatsApp service". **Stays as built, switched off. Sending is Later (DEC-049)** (provider, template, patient opt-in). The finish message reads "Visit completed" without "reminder scheduled" until it exists. (BR45-29)
- Given the next due vaccine and its date, then the Follow-up (SAH-51-6) can use it as the suggested follow-up interval; the doctor can choose any other interval. (BR45-23)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403 on print; front desk can reprint a signed card through SAH-51 reprint (not here); another clinic's visit, 404. (BR45-16, BR45-5)
- Given a print, then it holds a child's data; it is never logged and no copy is stored by the print action. (BR45-5)
## Notes for design
The card is a browser print like the case paper. Feeding history chips are starter content (C-10).

## Out of scope for SAH-62
Frame, Prescription table and alerts (SAH-47, SAH-48); the vaccination register and recall list (no module, OQ-45-28); batch and manufacturer capture; child-specific vitals limits (OQ-43-23); WhatsApp delivery of the reminder (SAH-68-5); SMS (OQ-45-27).

## Traceability
BR45-4 -> SAH-62-1 to SAH-62-4. BR45-5 -> SAH-62-1 to SAH-62-4. BR45-6 -> SAH-62-1, -2, -4. BR45-16 -> SAH-62-1 to SAH-62-4. BR45-18 -> SAH-62-1, -2. BR45-20 -> SAH-62-1, -2. BR45-23 -> SAH-62-3, -4. BR45-25 -> SAH-62-1, -2, -4. BR45-29 -> SAH-62-4.

## Open questions
In `docs/requirements/SAH-45.md`: C-5 (tagged), OQ-45-27, OQ-45-28, OQ-45-30 (defaults accepted, DEC-039 addendum), **OQ-45-32 (growth centile and milestone reference beyond the drawn age, and Marathi / Hindi card wording; answered, default accepted: not available / English until supplied)**. From SAH-43: OQ-43-23 (child limits: default adult limits not applied). Later (DEC-049): the WhatsApp reminder.

## Status log
- 12 Nov 2026 · outline by the business-analyst; updated after DEC-039.
- 12 Nov 2026 (DEC-044 H8, ADR-023 D13) · aligned: no centile note for length or head circumference; weight notes stay. Awaiting human approval of `stories`.
- 12 Nov 2026 (phase 4) · full stories written from the source of EMR mockup #DoctorPaeds (growth steppers, centile bands, milestones, IAP schedule, dose rules and frequency text, card, dialog); SAH-62-4 added at the end of the block. Awaiting human approval of `stories`.
