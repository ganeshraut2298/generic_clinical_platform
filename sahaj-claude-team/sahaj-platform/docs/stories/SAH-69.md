# SAH-69 · Stories: Dental screen, part 2 (estimate and rates, estimate signed, consent, lab work, Complete & send to billing, book next sitting)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-4, BR45-5, BR45-23, BR45-27; billing rules B-1, B-2, B-4; legal rule L-3 consent; decided sign-offs SO-45-23, SO-45-32; answered questions OQ-45-19; answered, default accepted (DEC-039 addendum): OQ-45-28, OQ-45-30). Module: opd (estimate lines and charges to billing through the billing `api`; next sitting through Appointments and the Treatment course). Phase 4. Depends on SAH-49 (chart and plan), SAH-48 (service lines), SAH-58 (course), SAH-51 (finish), SAH-10 (billing counter), SAH-11 (Appointments). **This id was added on 12 Nov 2026 to split the oversized SAH-49 (the money and finish part of the Dental screen).**
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorDental, "Treatment plan & estimate", "Print estimate", "Estimate signed", "Complete & send to billing", "Send to billing?", "Confirm & book next sitting").
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details write the estimate, consent and finish, on their own patients (owner: all). Billing and front desk see the resulting bill lines and the next appointment through their existing screens and cannot write the EMR (403). Nurse, pharmacist and counsellor get 403. "Not signed in" gets 401. The clinic always comes from the logged-in user. Amounts show as ₹ 1,25,000.
Rule tag: "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (BR45-4). The sample rates are never prices (B-2): they are seed data only; real prices come from the clinic tariff (SAH-7, SAH-18).

### SAH-69-1 · As a dentist, I want an estimate for the treatment plan that I can print and mark as signed by the patient, so that the patient knows the cost before work starts
Priority: Must
Screen: EMR mockup #DoctorDental, plan table column "Estimate", total, "Print estimate", chip "Estimate signed".
## Acceptance criteria
- Given plan rows (SAH-49-2), then each row shows its estimate in ₹ with Indian grouping and the table shows a total of the rows that are not removed. (BR45-27)
- Given a plan procedure that matches a service of the clinic tariff (SAH-7, SAH-18) for the doctor, then the row's estimate is the tariff price (OQ-43-33 default); given no tariff service matches, then the row shows "Not in tariff" and the doctor types the amount for that row, which is kept on the plan only. The mockup's sample rates are used only as seed data of a demonstration clinic: Composite filling ₹ 1,500, Replace filling ₹ 1,500, Root canal treatment ₹ 4,500, PFM crown ₹ 5,500, Implant with crown ₹ 30,000, Implant review ₹ 0, Extraction ₹ 1,200, Bridge ₹ 12,000, Crown re-cementation ₹ 500. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (B-2). (BR45-4)
- Given a root canal planned as 3 sittings, then the estimate for the procedure is split across the sittings in equal parts rounded to ₹ 50 with the last sitting taking the rest (the mockup's course shows ₹ 2,750 for two PFM crown sittings of ₹ 5,500; the split rule is an analyst reading, flagged for the human). (BR45-27)
- Given the doctor taps "Print estimate", then the browser print opens with the clinic header, the patient's name, age and sex, the plan rows (tooth, procedure, phase, estimate), the total and the line "Estimate only. Final charges are as billed."; the language is the clinic's Case paper and Rx language (English, Marathi or Hindi headings as in SAH-55-2) and the toast reads "Estimate sent to printer". The line "Estimate only. Final charges are as billed." is an analyst addition (not drawn). (BR45-23)
- Given a plan with no row, then "Print estimate" is disabled with the note "Add a treatment first". (BR45-23)
- Given the patient agrees, when the doctor ticks "Estimate signed by patient", then the chip "Estimate signed" shows in the header with the date, time and doctor; a paper signature image is not stored (documents service, OQ-45-28, shown disabled "Later"). (BR45-23)
- Given the plan changes after "Estimate signed" (a row added, removed or an amount changed), then the chip changes to "Estimate changed. Sign again" and the sign tick is cleared; the earlier signed total stays in the audit as a number. (BR45-23)
- Given the estimate, then it also becomes the course estimate when a Treatment course is started (SAH-58-1): the planned sittings and charges come from the plan. (BR45-27)
- Given a nurse, billing, front desk, pharmacist or counsellor, then they cannot change or sign the estimate (403); billing can print the saved estimate from the Treatment course. (BR45-16)
- Given another clinic's visit, 404; no login, 401. (BR45-5)
- Given any call, then no log line, audit value or analytics event holds a procedure name or a tooth together with a patient; the audit holds ids, totals and counts. (BR45-5)
## Notes for design
The estimate is a reading of the plan plus tariff. Whether the line amounts are stored on the plan row or recomputed is for the architect; a signed estimate must keep the amounts it was signed with.

### SAH-69-2 · As a dentist, I want consent recorded for the planned procedures and lab work noted for crowns, bridges, dentures and aligners, so that the record shows what was agreed and what goes to the lab
Priority: Should
Screen: EMR mockup #DoctorDental sections "Consent forms" and "Lab work" (library sections; Consent forms is on at start for Dental, Lab work is in part 2).
## Acceptance criteria
- Given the section Consent forms is in the snapshot (it is on for Dental at start, the consent flag, L-3), then it shows the library fields: "Form" (a template per procedure) and "Signature" (patient or guardian). The doctor picks the form for each procedure in the plan that needs consent and ticks "Consent explained and signed" with the date, time and doctor. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (L-3). (BR45-4)
- Given the Signature field, then it is shown disabled with a "Later" note because it needs the documents service (OQ-45-28); the tick above is the record until then. (BR45-6)
- Given a procedure in the plan has no consent ticked, then the section status is Required or Optional per the published layout, and the footer lists "consent forms" under "Still needed" only when the section is required (SAH-47-4). (BR45-20)
- Given the section Lab work is in the snapshot, then it shows "Item" (Crown, bridge, denture, aligner), "Lab" and "Due date"; the doctor adds one or more lab items to a tooth in the plan. The lab name is free text because no lab vendor list exists in any module (OQ-45-19 and OQ-45-28 default: text label). (BR45-6)
- Given a lab item with a due date, then the next-sitting suggestion of SAH-69-3 shows the due date as a note ("Lab work due <date>") and nothing is ordered anywhere; no message is sent to a lab. (BR45-6)
- Given the mockup's "Lab work" link in the doctor-screen top bar, then it is not built, because that top bar is not built (SAH-47-1, DEC-041). (BR45-6)
- Given a due date before today, then the field shows an error and the value is not saved. (BR45-20)
- Given a signed visit, then both sections are read-only. (BR45-18)
- Given a nurse, billing, front desk, pharmacist or counsellor, 403; a doctor who is not the visit's doctor (not owner), 403; another clinic's visit, 404. (BR45-16, BR45-5)
- Given any call, then no log line or audit value holds a lab item or procedure with a patient. (BR45-5)
## Notes for design
Consent wording and the consent forms themselves are not drawn beyond the library field list; no consent text is invented here. Surgical and chemotherapy consent are in SAH-71 and SAH-66.

### SAH-69-3 · As a dentist, I want to finish the visit with "Complete & send to billing" and book the next sitting in one step, so that billing and the appointment book are updated together
Priority: Must
Screen: EMR mockup #DoctorDental, "Complete & send to billing", dialog "Send to billing?" and "Confirm & book next sitting", next-sitting choices.
## Acceptance criteria
- Given the Dental footer (SAH-47-4), then it shows "Still needed: <sections>." in red or "Ready to complete." in green; "Complete & send to billing" follows the checks of SAH-51-1 (including the drug-allergy alert of SAH-48-2) and does nothing when something is missing except open the first missing section. (BR45-20)
- Given everything required is done, when the doctor taps "Complete & send to billing", then the dialog "Send to billing?" opens with a short list of what will happen: the plan rows marked Done today with their charges, the services ticked in Procedures & services (SAH-48-4), the estimate total and the next sitting, and two buttons "Back" and "Confirm & book next sitting". (BR45-23)
- Given the dialog, then "Next sitting" offers 3 days, 7 days, 14 days and 1 month (7 days at start). Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-3). (BR45-4)
- Given the doctor taps "Confirm & book next sitting", then in one action: the visit is signed (SAH-51-2), the charges of rows marked Done today are added to the visit bill through the billing `api` once each (SAH-10; an item already on the bill is not added again), the visit moves to its next workflow step (SAH-9, SAH-12), a planned sitting is added to the Treatment course for the date and an appointment is booked with the same doctor through Appointments (SAH-11) on the first free slot of that date or the next working day, and the message reads "Sent to billing · next sitting booked in <interval>". (BR45-23, BR45-27)
- Given no free slot exists within 7 days after the date, then the sitting is added to the course with the date only and the message says "No free slot found. Book the appointment from Appointments." (analyst default, flagged for the human). (BR45-27)
- Given the plan has rows still Planned or In progress, then the next sitting carries them over; given every row is Done, then "Confirm & book next sitting" becomes "Confirm" and no appointment is booked. (BR45-27)
- Given Back is tapped, then the dialog closes and nothing is saved or sent. (BR45-23)
- Given a failure part-way (for example the appointment cannot be booked), then nothing is half done: either the whole action succeeds or the visit stays unsigned with the error and a retry. (BR45-23)
- Given the same confirm is tapped twice quickly or retried after a lost network, then one signature, one set of bill lines and one appointment result. (BR45-23)
- Given the visit bill is already paid, then new Done charges are added as a new open bill line set by the billing counter's rule (SAH-10); the EMR never edits a paid bill. (BR45-1)
- Given the follow-up of SAH-14, then a Dental visit with a next sitting creates its follow-up through SAH-51-6 and never two for one visit. (BR45-23)
- Given billing and front desk, then they see the lines and the appointment on their own screens and cannot confirm this dialog (403); nurse, pharmacist and counsellor 403; another clinic's visit 404; no login 401. (BR45-16, BR45-5)
- Given the audit, then one entry records who, when, visit id, number of bill lines and total (no procedure names). (BR45-5)
## Notes for design
The mockup's top-level text "Complete & send to billing" is the Dental name of the Complete button (SAH-51). The "next sitting" is a Treatment course sitting (SAH-58-2); whether the course page opens after confirm is not drawn (it does not).

## Out of scope for SAH-69
The chart, plan and 3D view (SAH-49); per-sitting payment, advances and part payments (SAH-58-3); lab ordering to a Lab module (Later); the signature image and photo of the IOPA (documents service, OQ-45-28); SMS to the patient for the next sitting (OQ-45-27, Later, shown disabled).

## Traceability
BR45-1 -> SAH-69-3. BR45-4 -> SAH-69-1, -2, -3. BR45-5 -> SAH-69-1 to SAH-69-3. BR45-16 -> SAH-69-1 to SAH-69-3. BR45-18 -> SAH-69-2. BR45-20 -> SAH-69-2, -3. BR45-23 -> SAH-69-1, -3. BR45-27 -> SAH-69-1, -3.

## Open questions
In `docs/requirements/SAH-45.md`: B-2 (sample rates, tagged), L-3 (consent, tagged), OQ-45-19 and OQ-45-28 (text labels, Later), OQ-45-27 (SMS: Later), OQ-45-30 (44 px). From SAH-43: OQ-43-33 (tariff price for the doctor). Analyst additions flagged for the human at approval (no new question number: each has a default): the equal-split rule of a multi-sitting estimate, the "No free slot found" fallback, and the print line "Estimate only. Final charges are as billed."

## Status log
- 12 Nov 2026 (phase 4) · new id, split from SAH-49; full stories written from the source of EMR mockup #DoctorDental. Awaiting human approval of `stories`.
