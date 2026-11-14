# SAH-55 · Stories: Live case paper (fills as the doctor types; A4 or A5; English, Marathi or Hindi headings; print now, share on WhatsApp / ABHA is Later (DEC-049))
**Update 13 Nov 2026 (DEC-049 item 1):** sending on WhatsApp and sharing to ABHA / ABDM are Later. Every "Waits for DEC-040" below now reads "Later (DEC-049)"; the criterion is kept, not built now. The switched-off "Share · WhatsApp / ABHA" button, the English / Marathi / Hindi headings and Print stay as built. The sending work is held by SAH-68-5 (WhatsApp) and SAH-68-6 (ABHA / ABDM), which become a new story when the accounts and the legal opinion exist.
Requirement: `docs/requirements/SAH-45.md` (rules BR45-25, BR45-4, BR45-5, BR45-19, BR45-29; decided sign-offs SO-45-20, SO-45-29, SO-45-31; answered question OQ-45-10 "answered: default accepted (DEC-039 addendum)"; rule L-4 NMC and ABDM text; answered, default accepted (DEC-039 addendum): OQ-45-30; carried over from SAH-43: OQ-43-11 registration number missing, OQ-43-29, OQ-43-30; D-5 capitals and D-10 print layout in `docs/requirements/SAH-43.md`). Module: opd. Phase 3. Depends on SAH-47 (values), SAH-48 (medicines), SAH-56 (lists), SAH-7 (Case paper and Rx settings: size, letterhead, capitals), SAH-43-3 (doctor details).
Mockup screen: Queue (sidebar → Queue) (EMR mockup #CasePaper, 720 px wide; the "Case paper · n of 10 filled · live" tab of the right-hand panel of every doctor screen).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details read the live paper, because it is part of the EMR (nurse, front desk, billing, pharmacist and counsellor get 403 on a direct call). "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body.
Rule tag: where a story says "Rule tag", the rule is "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (BR45-4). The footer wording is a legal rule (L-4) of this kind.
The ten headings in the three languages (from the mockup, in this order):

| # | English | Marathi | Hindi |
| --- | --- | --- | --- |
| 1 | Chief complaints | मुख्य तक्रारी | मुख्य शिकायतें |
| 2 | Allergies | ॲलर्जी | एलर्जी |
| 3 | Medical history | पूर्वीचे आजार | पुरानी बीमारियाँ |
| 4 | Examination | तपासणी | जाँच |
| 5 | Diagnosis | निदान | निदान |
| 6 | Investigations advised | सुचवलेल्या चाचण्या | सुझाई गई जाँचें |
| 7 | Medicines ℞ | औषधे ℞ | दवाइयाँ ℞ |
| 8 | Procedures & services | प्रक्रिया व सेवा | प्रक्रिया व सेवाएँ |
| 9 | Advice | सल्ला | सलाह |
| 10 | Follow-up | पुढील भेट | अगली मुलाक़ात |

### SAH-55-1 · As a doctor, I want a case paper that fills as I type, with the clinic and patient header and ten headings, so that I see the paper the patient will take
Priority: Must
Screen: EMR mockup #CasePaper, header, ten headings, vitals line, signature area and the tab title "Case paper · n of 10 filled · live".
## Acceptance criteria
- Given an open EMR, when the tab "Case paper" shows, then the paper shows "Live case paper" with the mark "● Live", and the tab subtitle reads "n of 10 filled · live" where n is the number of headings that have content (Medicines counts when at least one medicine is present). (BR45-25)
- Given the header, then it shows the clinic name, address and phone from the clinic profile (letterhead, SAH-7), the department, the doctor's name, qualification and registration number from Team and access (SAH-43-3, not retyped), the patient's name, age, sex, weight when recorded, UHID (or "New patient"), and the visit line (for example "OPD · follow-up visit"). (BR45-25)
- Given the doctor has no registration number saved, then the header shows "Reg. no. not set" and the paper still prints; the default of OQ-43-11 applies. (BR45-25)
- Given today's vitals exist, then one vitals line shows (for example "BP 142/90 · P 82 · SpO₂ 98%"). (BR45-25)
- Given a heading, then it is filled from the shown sections that map to it and have "Print on prescription" on (SAH-53, OQ-45-10 decided): complaints (with duration, for example "Headache · 2 days"), allergies, medical history, examination (general, systemic and the specialty findings), diagnosis, investigations advised, medicines, procedures and services, advice, follow-up. A section with Print off never reaches the paper. (BR45-25)
- Given the doctor types or changes a value in the EMR, then the paper changes at once without saving; values with the Active switch off (SAH-56-3) are left out. (BR45-25)
- Given the Medicines heading, then each medicine is numbered with the generic name, frequency, days and the when note, and names are in capital letters only when the capitals setting of Case paper and Rx (SAH-7) is on. (BR45-25)
- Given a heading has no content, then it shows the grey italic text "Not recorded yet" (unless "Hide empty" is on, SAH-55-2). (BR45-25)
- Given the footer, then it shows the lines: "Medicines in generic names, capital letters (NMC)." (only when capitals is on), "This visit is saved as an OP consultation record and can be shared to the patient's ABHA." (only when ABHA sharing is switched on, which is Later (DEC-049); until then the line is not shown) and "Not valid without signature." Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (L-4). The two conditions are a safety choice by the analyst so that the paper never claims something that is not true; the human can change them. (BR45-25, BR45-29)
- Given the visit is not signed, then the signature area is empty; given it is signed (SAH-51), then it shows the doctor's name in the signature style of the mockup and the signing time. (BR45-25)
- Given a nurse, front desk, billing, pharmacist or counsellor, then the paper cannot be opened from the EMR (403); given a doctor of another clinic, 404 and nothing is returned. (BR45-16, BR45-5)
- Given any call, then the paper's content is never written to a log line, audit value or analytics event. (BR45-5)
## Notes for design
The mockup prints the same paper from the screen (HTML print). The mapping of specialty sections (for example Dental chart, ANC card) to the ten headings is for the architect; the mockup puts their findings under Examination. A heading's content is only a reading of the EMR values; nothing is typed on the paper itself.

### SAH-55-2 · As a doctor, I want to choose A4 or A5, English, Marathi or Hindi headings, hide empty headings and print, so that the paper suits the patient and the clinic
Priority: Must
Screen: EMR mockup #CasePaper, controls "A4 / A5", "English / मराठी / हिंदी", "Hide empty", "Print".
## Acceptance criteria
- Given the paper, then a size choice A4 or A5 shows, and a language choice English, मराठी (Marathi) or हिंदी (Hindi); the defaults are the clinic's Case paper and Rx settings (SAH-7); choosing here changes this visit's paper only and does not change the clinic setting. (BR45-25)
- Given the doctor picks Marathi or Hindi, then the ten headings show in the table above; the entries the doctor typed stay as typed, advice items show in the language of their own Language attribute (SAH-56-2), and drug names stay in English generic names. (BR45-25)
- Given the doctor taps "Hide empty", then headings without content disappear from the screen and the print, the button reads "✓ Hiding empty", and the "n of 10 filled" count does not change; tapping again shows them. (BR45-25)
- Given the doctor picks A5, then the paper scales to the A5 page and nothing is cut off; given A4, the same on A4. (BR45-25)
- Given the doctor taps "Print", then the browser print opens with the paper only (no EMR screen around it) at the chosen size and language. (BR45-25)
- Given an unresolved drug-allergy alert (SAH-48-2), then Print is disabled with the note "Resolve the allergy alert first". (BR45-23)
- Given a printed paper, then it holds patient data; it is never logged and no copy is stored by the print action. (BR45-5)
- Given a slow device or print failure, then the screen stays as it was and the doctor can try again. (BR45-25)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403. (BR45-16)
## Notes for design
The mockup's paper width is 720 px with an A4 or A5 proportion. Headings for Marathi and Hindi are the mockup's wording (Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed, until a language reviewer checks them). Fonts must show Devanagari correctly in print.

### SAH-55-3 · As a doctor, I want to share the case paper with the patient on WhatsApp or to their ABHA, so that the patient has it on the phone (sending: Later, DEC-049)
Priority: Should
Screen: EMR mockup #CasePaper, button "Share · WhatsApp / ABHA".
## Acceptance criteria
- Given the paper, then the button "Share · WhatsApp / ABHA" is shown as drawn, switched off with the note "Needs the WhatsApp and ABHA services" until the services and credentials exist (the in-app part stays as built, BR45-29). **Stays as built, switched off. Sending is Later (DEC-049).** (BR45-29)
- Given the services exist, when the doctor taps WhatsApp, then a preview of the message and the PDF shows and the patient's consent for WhatsApp is checked before sending. **Later (DEC-049)** (provider, template approval, PDF library, patient opt-in). (BR45-29)
- Given the services exist, when the doctor taps ABHA, then the visit is shared to the patient's ABHA only with a recorded ABDM consent of the patient and only after the visit is signed (the paper says it is saved as an OP consultation record). **Later (DEC-049)** (ABDM registration, consent artefact, record bundle). (BR45-5, BR45-29)
- Given consent is missing or withdrawn, then nothing is sent and the message says why. **Later (DEC-049)** (while sending is off, the switched-off note is the message). (BR45-5)
- Given any send, then the audit holds who, when, the visit id and the channel, never the patient's mobile number, ABHA number or paper text. **Later (DEC-049).** (BR45-5)
- Given a role other than doctor, visiting doctor and owner with doctor details, 403. (BR45-16)
## Notes for design
Only the switched-off button is built (with SAH-55-2); no sending is built now (DEC-049). The documents service is also listed as not in phase 1 (PDF generation).

## Out of scope for SAH-55
Reprint of a signed paper by front desk or billing (SAH-51 and SAH-68); letterhead and capitals settings (SAH-7); the signature image (documents service, OQ-45-28); typing directly on the paper; the Prescription editor (SAH-48); Form builder print (SAH-59-5).

## Traceability
BR45-4 -> SAH-55-1, -2. BR45-5 -> SAH-55-1 to SAH-55-3. BR45-16 -> SAH-55-1 to SAH-55-3. BR45-23 -> SAH-55-2. BR45-25 -> SAH-55-1, -2. BR45-29 -> SAH-55-1, -3.

## Open questions
In `docs/requirements/SAH-45.md`: OQ-45-10 (answered: Print flags choose sections; size, language, letterhead, capitals stay in SAH-7), OQ-45-30 (44 px). From SAH-43, still open with defaults: OQ-43-11 (registration number missing), OQ-43-29 and OQ-43-30 (how WhatsApp and ABHA buttons show; English only: now superseded by DEC-039 answer 4). Later (DEC-049): the sending in SAH-55-3 and the ABHA clause of the footer.

## Status log
- 12 Nov 2026 · full stories written by the business-analyst from the source of EMR mockup #CasePaper (ten headings in three languages, A4 and A5, Hide empty, Print, footer, "n of 10"), DEC-039 and DEC-040. Replaces the outline. Awaiting human approval of `stories`.
- 13 Nov 2026 · DEC-049 item 1: SAH-55-3 criteria 2 to 5 and the ABHA footer clause marked "Later (DEC-049)"; criterion 1 (switched-off button) stays as built. No criterion deleted.
