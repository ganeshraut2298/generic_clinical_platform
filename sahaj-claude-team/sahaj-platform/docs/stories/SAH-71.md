# SAH-71 · Stories: General Surgery screen, part 2 (package estimate, who pays and pre-authorisation, consent checklist, pre-op orders, WHO checklist, Complete)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-4, BR45-5, BR45-6, BR45-20, BR45-23, BR45-27; billing rules B-1 to B-3; legal rules L-3 and L-5; clinical rule C-7; decided sign-offs SO-45-23, SO-45-32; answered, default accepted (DEC-039 addendum): OQ-45-27, OQ-45-28, OQ-45-30, OQ-45-31). Module: opd (estimate and deposit through the billing `api`; admission through the ipd `api`). Phase 4. Depends on SAH-64 (plan and booking), SAH-58 (Surgery episode), SAH-51 (finish), SAH-67 (admission), SAH-23, SAH-10. **This id was added on 12 Nov 2026 to split the oversized SAH-64 (the money, consent, orders and finish part of the Surgery screen).**
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorSurgery, 2 Treatment: package estimate, "Who pays", consent, pre-op orders, footer).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details (`clinic.consultation.write`) write the screen, on their own patients (owner: all). Billing and front desk see the estimate lines and the deposit through the billing counter and the admission request through the IPD screens; they cannot write the EMR (403). Nurse, pharmacist and counsellor get 403. "Not signed in" gets 401. The clinic always comes from the logged-in user. Amounts show as ₹ 1,25,000.
Rule tag: "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (BR45-4). The sample package prices of the mockup are never prices (B-2): they are seed data of a demonstration clinic; real prices come from the clinic tariff (SAH-7, SAH-18).

### SAH-71-1 · As a surgeon, I want a package estimate, who pays and the pre-authorisation state, so that the patient and the insurer know the cost before the operation
Priority: Should
Screen: EMR mockup #DoctorSurgery, card "Estimate" (Package, Anaesthesia, Stay, Deposit at admission), chips "Who pays", payer chip, compliance sections.
## Acceptance criteria
- Given a procedure is chosen (SAH-64-3), then the estimate shows four lines: "Package" with the amount, "Anaesthesia" (GA, Spinal, Local or Local + sedation), "Stay" (the admission choice) and "Deposit at admission". (BR45-27)
- Given a clinic tariff package for the procedure (SAH-7, SAH-18), then the package amount is the tariff price; given none, the amount shows "Not in tariff" and the doctor or the owner types the amount for this patient. The sample prices of the mockup are seed data only: Lap cholecystectomy ₹ 65,000, Open cholecystectomy ₹ 75,000, Lap hernia repair (TEP) ₹ 70,000, Open mesh hernioplasty ₹ 45,000, Lap appendicectomy ₹ 55,000, Haemorrhoidectomy ₹ 35,000, Excision of lipoma ₹ 12,000, Incision and drainage ₹ 8,000. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (B-2). (BR45-4)
- Given "Who pays", then the chips are Self, Insurance (TPA), MJPJAY / PM-JAY and Company; one is selected; the payer chip reads "Self-pay", or "<payer> · pre-auth pending" or "<payer> · pre-auth sent". (BR45-4)
- Given the payer is Self, then the deposit at admission is 50% of the package amount rounded to the nearest ₹ 1,000; given any other payer, the deposit is ₹ 10,000. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (B-1, B-3). (BR45-4)
- Given the payer is not Self, then the sections Scheme / insurance (PM-JAY, MJPJAY) and Insurance pre-authorisation (compliance, SAH-53-1) show; the doctor marks "Pre-authorisation sent" with the date and the reference text; no claim is sent to an insurer or scheme (no integration, CLAUDE.md). Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (B-3, L-5). (BR45-4)
- Given the payer is Self, then pre-authorisation is not needed and the OT booking rule of SAH-64-3 does not ask for it. (BR45-20)
- Given the estimate is saved, then it feeds the Surgery episode estimate (SAH-58-1) and, at admission, the deposit suggestion of SAH-23 (the deposit is collected by billing, SAH-58-3). Nothing is charged at this step. (BR45-27)
- Given the print of the estimate, then it is a browser print with the clinic header, procedure, anaesthesia, stay, package, deposit and the line "Estimate only. Final charges are as billed." in the clinic's print language (analyst addition, as in SAH-69-1). (BR45-23)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403 on every change; a doctor who is not the visit's doctor (not owner), 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then no log line, audit value or analytics event holds the procedure with a patient; the audit holds ids and amounts. (BR45-5)
## Notes for design
The patient share of a scheme package is not drawn and not built (B-3 lists it; no calculation exists in the mockup). Treatment course payment is SAH-58.

### SAH-71-2 · As a surgeon, I want a consent checklist for the operation, so that booking waits until the patient has agreed to each item
Priority: Must
Screen: EMR mockup #DoctorSurgery, card "Consent" (red star) with count "n of m" and the five items.
## Acceptance criteria
- Given the section Surgical consent (compliance, on for General Surgery at start, L-3, SAH-53-1), then the card shows the title with a red star, the count "n of 5" and the five items: "Procedure, benefits and risks explained", "Possible conversion to open surgery", "Anaesthesia consent", "Blood transfusion consent" and "Photos / video for records"; each is a tick the doctor sets with the date, time and doctor. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (L-3). (BR45-4)
- Given the mockup starts with only the first item ticked, then a new visit starts with none ticked (the first item is example data). (BR45-4)
- Given not every item is ticked, then "Book OT and admission" is blocked by SAH-64-3 and the message names "n consent item(s)"; the mockup requires all five to book, including photos and video, and this is built as drawn; the human may decide that the last item is optional (flagged below). Rule tag as above (L-3). (BR45-4)
- Given the library field "Signature" (patient and witness), then it is shown disabled with a "Later" note (documents service, OQ-45-28); the ticks are the record until then. (BR45-6)
- Given an item is unticked after the booking, then the booking shows "Changed after booking. Book again" (SAH-64-3). (BR45-27)
- Given the consent wording of each item, then only the five labels above are shown; no longer consent text is written here (legal review later, tagged). (BR45-4)
- Given a signed visit, then the checklist is read-only. (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then the audit records who, when, visit id and the count of items, never the patient's name. (BR45-5)
## Notes for design
Consent wording that a clinic must print is a legal matter for the human (L-3). The chemotherapy consent is in SAH-66-2; the dental consent in SAH-69-2.

### SAH-71-3 · As a surgeon, I want pre-op orders, a note on the WHO surgical safety checklist and a Complete that prints the admission letter, so that the ward and OT have what they need
Priority: Should
Screen: EMR mockup #DoctorSurgery, card "Pre-op orders", "Blood" chips, footer text, "Go to ward and OT with the booking".
## Acceptance criteria
- Given the section Pre-op orders (Treatment part), then the checklist shows: "Nothing by mouth from midnight", "Inhaler on the morning of surgery", "Antibiotic at induction: cefazolin 2 g IV", "Part preparation in ward", "Clot prevention: stockings, early walking" and "Admit 7 am, OT at 10 am"; each is a tick; the doctor can add a line of text. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-7). (BR45-4)
- Given a pre-op order names a medicine, then it is an instruction printed on the pre-op sheet and is not a medicine order: IPD medicine orders go through the existing path with allergy, duplicate and interaction checks and the pharmacist check (SAH-31); the sheet says so ("Medicine orders are placed by the ward"). The EMR creates no second path (OQ-43-24). (BR45-1)
- Given the "Blood" arrangement chips (Group and save, 1 unit reserved and the others of the mockup), then one is chosen and printed on the pre-op sheet; no blood bank request is made (OQ-45-28). (BR45-6)
- Given the section WHO surgical safety checklist (compliance), then the card shows the note "WHO surgical safety checklist opens in OT on the day." and its library fields with the generic renderer; it is filled in the visit of the operation day, not at booking (L-5). The checklist items are not drawn and none are invented. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (L-5). (BR45-4)
- Given the footer, then it reads "Still needed: complaints, diagnosis, OT booking." (listing what the published layout makes required, SAH-47-4) in red, and "Ready to complete. Admission letter and pre-op instructions will print." in green. (BR45-20)
- Given "Complete", then the checks of SAH-51-1 run; on confirm the visit is signed (SAH-51-2), the admission letter and the pre-op instructions print (browser print, English, Marathi or Hindi headings by the visit's language choice, SAH-55-2), the admission request stays with the ward (SAH-67-3), the episode sitting is added to the Surgery episode (SAH-58-2) and the dialog message "Go to ward and OT with the booking" shows beside the print buttons. (BR45-23)
- Given an unresolved drug-allergy alert (SAH-48-2), then Complete is blocked. (BR45-23)
- Given the pre-op sheet and letter, then they hold patient data; they are never logged and no copy is stored by the print action. (BR45-5)
- Given a signed visit, then pre-op orders are read-only; a correction is an addendum (SAH-51-5). (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
## Notes for design
Day-of-surgery documents (Operation notes, Post-op orders and wound check) are library sections in part 2 rendered by the generic renderer; they are not separate stories. Admission letter wording is the clinic's; the mockup gives only the footer text.

## Out of scope for SAH-71
Examination, fitness and the booking rule (SAH-64); the Surgery episode page and payments (SAH-58); the admission itself (SAH-23, SAH-67); insurer claim submission, patient share (no integration); OT schedule, blood bank and SMS (OQ-45-27, OQ-45-28).

## Traceability
BR45-1 -> SAH-71-3. BR45-4 -> SAH-71-1, -2, -3. BR45-5 -> SAH-71-1 to SAH-71-3. BR45-6 -> SAH-71-2, -3. BR45-16 -> SAH-71-1 to SAH-71-3. BR45-18 -> SAH-71-2, -3. BR45-20 -> SAH-71-1, -3. BR45-23 -> SAH-71-1, -3. BR45-27 -> SAH-71-1, -2.

## Open questions
In `docs/requirements/SAH-45.md`: B-1 to B-3, L-3, L-5, C-7 (all tagged), SO-45-23, SO-45-32, OQ-45-27, OQ-45-28, OQ-45-30. OQ-45-31 (who may admit). To confirm at approval (no new number; defaults apply): the mockup makes the photo and video consent item a condition for booking; default as drawn.

## Status log
- 12 Nov 2026 (phase 4) · new id, split from SAH-64; full stories written from the source of EMR mockup #DoctorSurgery (estimate lines, payer chips, deposit rule, five consent items, six pre-op orders, footer texts). Awaiting human approval of `stories`.
