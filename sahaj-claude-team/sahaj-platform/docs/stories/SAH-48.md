# SAH-48 · Stories: Prescription and services editor (shared)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-23, BR45-4, BR45-5, BR45-6; decided sign-offs SO-45-12, SO-45-14; answered questions OQ-45-10, OQ-45-14; rule C-2 "drug-allergy warning"; answered, default accepted (DEC-039 addendum): OQ-45-27, OQ-45-30; Marathi and Hindi advice text stays empty until the human supplies it, print falls back to English; carried over from SAH-43: OQ-43-6 drug master, OQ-43-22 structured allergy, OQ-43-24 IPD safety path, OQ-43-33 tariff for the doctor). Module: opd (service lines to billing through the billing `api`). Phase 3. Depends on SAH-47 (frame), SAH-43 (masters, ranking, favourites), SAH-56 (EMR list for services and advice).
Mockup screen: Queue (sidebar → Queue) (EMR mockup #GeneralTreatment "Prescription", #DoctorDental "Today's prescription", #DoctorPaeds "Prescription", #EmrList for services and advice).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details (`clinic.consultation.write`) write the prescription, on their own patients (owner: all). Nurse, front desk, billing, pharmacist and counsellor cannot write it (403 on a direct call); billing sees the bill lines, front desk and pharmacist see the printed prescription through the existing screens. "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body.
Rule tag: "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (BR45-4). It is repeated on each clinical rule below. Marathi and Hindi: DEC-039 answer 4 (printed advice and headings, SAH-48-3). Sending on WhatsApp is Later (DEC-049) (SAH-48-3, last criteria).

### SAH-48-1 · As a doctor, I want to prescribe medicines by generic name with frequency, days and a when-to-take instruction, and repeat or reuse earlier prescriptions, so that the prescription is complete and quick
Priority: Must
Screen: EMR mockup #GeneralTreatment, section "Prescription" (table "Medicine (generic first)", "How often", "For how long", "When", "My favourites:", "Repeat last Rx", "Clear all").
## Acceptance criteria
- Given the section Prescription (Rx) is in the visit's snapshot, then it shows the title (with a red star when required), the column titles "Medicine (generic first)", "How often", "For how long", "When", and, when no medicine is added, the text "No medicines yet. Use quick add below or repeat the last prescription." (BR45-23)
- Given the doctor searches medicines (SAH-43-1, generic names, ranked by the doctor's use) and taps one, then a row is added with the generic name and strength first (for example "Telmisartan 40 mg") and the form or note below it (for example "Tablet", "Tablet · SOS for headache"). The medicine list is the SAH-43 master; its source is OQ-43-6. (BR45-23)
- Given a row, then "How often" offers exactly the four choices 1-0-0, 1-0-1, 0-0-1 and 1-1-1 (shown with OD, BD, HS and TDS), one selected; "For how long" is a number of days with minus and plus buttons that change it by 1 up to 7 days and by 7 above that, never below 1; "When" offers After food, Before food, Bedtime and Morning, empty stomach, one selected, changed by tapping. A value outside these choices (for example 0 days) is refused with 400 and nothing changes. (BR45-23)
- Given the section "My favourites:", then it lists the clinic's favourite medicines (SAH-7 and SAH-43; favourites stay per clinic) that are not yet on the prescription; tapping one adds it with its saved frequency, days and when. (BR45-23)
- Given a patient with an earlier signed prescription, when the doctor taps "Repeat last Rx", then the medicines of the patient's last signed visit are added with their frequency, days and when, skipping any already on the list, and the message names how many were added; given the patient has none, then the button is disabled with the note "No earlier prescription". (BR45-23)
- Given the doctor taps "Clear all" or removes one row, then the rows go and the message reads "<name> removed" or "Prescription cleared" with an Undo; given Undo is tapped while the message shows, then the rows come back as they were. (BR45-23)
- Given a medicine is already on the prescription (same generic name and strength), then it is not offered in the favourites and is not added a second time. (BR45-23)
- Given the section is required (the department's default for Prescription (Rx) is required), then it is Done when at least one medicine row is present; a department that switched the section off (for example Physiotherapy) shows no prescription section at all. (BR45-20)
- Given the printed prescription, then medicine names print in capital letters only when the clinic's capitals setting in Case paper and Rx (SAH-7) is on (OQ-45-10, decided); the editor shows names as stored. (BR45-23)
- Given a nurse, front desk, billing, pharmacist or counsellor user, or a doctor who is not the visit's doctor, when they call add, change, repeat or clear, then the answer is 403 and nothing changes; given no login, 401. (BR45-16)
- Given a visit of another clinic, then the answer is 404 and no row is returned (tenant check; row-level security). (BR45-5)
- Given a slow or lost network, then the change stays on screen as unsaved with a retry; nothing is lost silently. (BR45-23)
- Given any call, then no log line, audit value or analytics event holds a medicine name, because it is clinical data. (BR45-5)
## Notes for design
Frequency, days and when are fixed lists of the product. The Paediatrics "Dose by weight" adds rows into this section (SAH-62-3, phase 4). Tap targets 44 px (OQ-45-30). The IPD medicine path with its own safety checks (SAH-31) is not changed here; see SAH-48-2.

### SAH-48-2 · As a doctor, I want a warning when I prescribe a drug that clashes with the patient's allergy, so that I do not harm the patient
Priority: Must
Screen: EMR mockup #GeneralTreatment (alert "contains a sulfa drug", "Remove it", "I have reviewed the alert and want to prescribe anyway") and #DoctorDental (alert "Amoxicillin is a penicillin").
## Acceptance criteria
- Given the visit's Allergies section records the allergy class "Sulfa drugs" and the doctor adds a drug flagged as a sulfa drug in the master (the mockup flags Co-trimoxazole 960 mg, shown as "Tablet · contains a sulfa drug"), then a red alert shows "<drug> contains a sulfa drug. This patient is allergic to sulfa drugs." and the add message reads "Warning: <drug> …". Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-2). (BR45-23)
- Given the allergy class "Penicillin" and the doctor adds Amoxicillin (flagged penicillin), then the alert reads "Amoxicillin is a penicillin. This patient is allergic to penicillin." Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-2). (BR45-23)
- Given an alert is showing, then the alert offers two actions: "Remove it" (the flagged drug leaves the prescription and the message reads "Removed. Allergy conflict cleared.") and the checkbox "I have reviewed the alert and want to prescribe anyway". (BR45-23)
- Given an alert is unresolved (neither removed nor confirmed), then Complete is blocked, the footer reads "Remove the drug or confirm the allergy alert before completing." in red and takes the place of "Still needed" (SAH-47-4), and the prescription cannot be printed or sent. (BR45-23)
- Given the doctor ticks "I have reviewed the alert and want to prescribe anyway", then the alert is marked reviewed, Complete is no longer blocked by it, and one audit entry records who, when, the visit id, the drug's master key and the allergy class (keys, no free text). (BR45-23, BR45-5)
- Given a reviewed drug is removed and added again, or the recorded allergy classes change, then the check runs again and the alert returns. (BR45-23)
- Given the check, then it covers only the allergy classes that have a flag on drugs in the master (the mockup has sulfa and penicillin) and the allergy classes the doctor picks in the Allergies section; free text in the allergy tag that is not one of those classes is shown in the header but does not trigger the check. The section shows the line "Allergy check covers listed allergy classes only. No alert is not a clearance." (a safety line added by the analyst, not drawn in the mockup, so the human can remove it). (BR45-23)
- Given the visit is an IPD admission, then the IPD medicine order path with its own allergy, duplicate and interaction checks and the pharmacist check (SAH-31) stays the only path for new orders; this story creates no second, unchecked path (OQ-43-24, SAH-67-1). (BR45-1)
- Given a nurse, front desk, billing, pharmacist or counsellor user, then they cannot confirm or clear an alert (403); another clinic's visit gives 404. (BR45-16)
## Notes for design
The flags in the mockup are fixed on two example drugs. The product needs a drug-class flag in the drug master (OQ-43-6 stays open for the master's source) seeded with the two classes as drawn; adding classes is a later clinical decision. Structured allergy entry is OQ-43-22 (the match above uses classes picked in the section).

### SAH-48-3 · As a doctor, I want the advice and the prescription to print in English, Marathi or Hindi with English drug names, so that the patient understands the instructions
Priority: Should
Screen: EMR mockup #GeneralTreatment (Advice with "Marathi", "Hindi", "English"; footer "Prescription prints in Marathi with English drug names") and #EmrList kind Advice.
## Acceptance criteria
- Given the Advice section, then the doctor can pick the print language English, Marathi or Hindi for the visit's advice; the default is the clinic's Case paper and Rx language (SAH-7). (BR45-23)
- Given a language is picked, then the printed prescription shows the section headings (Chief complaints, Medicines ℞, Advice, Follow-up and the others) and each advice item in that language, as in the Case paper headings (SAH-55-2); the headings are the Marathi and Hindi words of the mockup. Medicine names stay in English generic names. (BR45-23)
- Given an advice item has no text in the chosen language, then its English text prints and the editor shows "No Marathi text, English used" (or Hindi). (BR45-23)
- Given every required section is done, then the footer reads "Ready to complete. Prescription prints in Marathi with English drug names." with the chosen language in place of "Marathi" ("in English with English drug names" when English). (BR45-23)
- Given the department's Print flags (SAH-53), then only sections with Print on prescription on reach the printed prescription (OQ-45-10, decided). (BR45-23)
- Given the doctor chooses to send the prescription or advice on WhatsApp, then the choice shows "Needs the WhatsApp service" and is disabled. **Stays as built, switched off. Delivery on WhatsApp is Later (DEC-049)**; the preview of the message stays as built with the send switched off (BR45-29). (BR45-29)
- Given a nurse, front desk, billing, pharmacist or counsellor user, then they cannot change the language (403); another clinic's visit gives 404. (BR45-16)
## Notes for design
The Marathi and Hindi words for headings are the ten in the Case paper (SAH-55-2). Advice text in Marathi and Hindi is an additive column of the SAH-43 master (see SAH-45.md). Printing is browser print as in SAH-55; a PDF for WhatsApp needs a PDF library decision, which is Later (DEC-049).

### SAH-48-4 · As a doctor, I want the procedures and services I tick to appear on the visit bill, so that billing does not enter them again
Priority: Must
Screen: EMR mockup #EmrList kind "Procedures and services" ("Bill today", "No charge", "In package"; empty text "Nothing added. Consultation fee is billed by the front desk.").
## Acceptance criteria
- Given the Procedures & services section, then its choices are the clinic's tariff (SAH-7 and SAH-18) shown as chips with the price in the Indian format (for example ₹ 1,500), not the sample rates of the mockup (B-2: sample rates are seed data for the dental estimate only). (BR45-23)
- Given the doctor adds a service with Billing "Bill today", then one bill line for the visit is created through the billing `api` (SAH-10) with the tariff price for the doctor (OQ-43-33) times Qty; given the doctor removes it before the bill is paid, then the line is removed. (BR45-23)
- Given Billing "No charge" or "In package", then no bill line is created and the item still prints and is recorded in the EMR. (BR45-23)
- Given the same service is added twice quickly, then one bill line results (idempotent). (BR45-23)
- Given the visit bill is already paid, when the doctor removes or changes a billed service, then the EMR refuses with the message "Already billed. Ask billing to adjust." and the bill is not changed; the EMR never edits a paid bill (SAH-10 rules). (BR45-1)
- Given the EMR shows an empty list, then the text "Nothing added. Consultation fee is billed by the front desk." shows; the consultation fee is not added by the EMR. (BR45-23)
- Given a billing user, then they see the lines on the existing billing counter and cannot edit the EMR (403 on the EMR call); given a doctor of another clinic, 404. (BR45-16)
- Given a lost network, then the line is not created twice on retry and the screen shows the real state after reload. (BR45-23)
- Given any call, then no log line holds a service name together with a patient. (BR45-5)
## Notes for design
Price lists, packages and the doctor's own fee are existing SAH-7 and SAH-18 data; this story only creates and removes lines. "Complete & send to billing" and follow-up fee rules are SAH-51 and SAH-68-2.

## Out of scope for SAH-48
The Paediatrics dose calculator (SAH-62-3); the dental estimate and treatment plan (SAH-49); prescription printing layout and paper size (SAH-55, SAH-7); dose checking other than the two allergy flags (not drawn); drug interaction checking in OPD (not drawn); the IPD medicine order path (SAH-31, SAH-67); sending on WhatsApp (Later, DEC-049); a drug master screen.

## Traceability
BR45-1 -> SAH-48-2, -4. BR45-4 -> SAH-48-2. BR45-5 -> SAH-48-1, -2, -4. BR45-16 -> SAH-48-1 to SAH-48-4. BR45-20 -> SAH-48-1. BR45-23 -> SAH-48-1 to SAH-48-4. BR45-29 -> SAH-48-3.

## Open questions
In `docs/requirements/SAH-45.md`: SO-45-12 (drug-allergy warning: decided, built as drawn), OQ-45-10 (answered: Print flags and capitals), OQ-45-27 (nothing used here), OQ-45-30 (44 px). From SAH-43, still open with defaults: OQ-43-6 (drug master source), OQ-43-22 (structured allergy), OQ-43-24 (IPD safety path), OQ-43-33 (tariff price for the doctor). Later (DEC-049): delivery on WhatsApp (SAH-48-3).

## Status log
- 12 Nov 2026 · full stories written by the business-analyst from the source of EMR mockup #GeneralTreatment, #DoctorDental, #DoctorPaeds and #EmrList, DEC-039 and DEC-040. Replaces the outline; SAH-48-2 is no longer held for a clinical owner (tagged instead). Awaiting human approval of `stories`.
