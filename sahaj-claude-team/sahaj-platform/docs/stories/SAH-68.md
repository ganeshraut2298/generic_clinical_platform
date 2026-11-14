# SAH-68 · Stories: EMR integrations (vitals read, billing lines and follow-ups, patient record write-back; ABHA link and share, WhatsApp sending and ABDM record are Later (DEC-049))
**Update 13 Nov 2026 (DEC-049 item 1):** WhatsApp sending and ABHA / ABDM linking and sharing moved back to "Later" until the Meta WhatsApp Business account, the ABDM sandbox registration and a legal opinion exist. Wherever this file says "Waits for DEC-040" read "**Later (DEC-049)**": the criterion is kept, not built now. What is built stays (switched-off controls, consent capture, message preview, send queue shell, messages that say sending is not available). SAH-68-4, SAH-68-5 and SAH-68-6 are the stories that hold the sending work; they are re-opened as a new story when the accounts exist (the tech lead gives the id then). No outline story was added at the end of the range.
Requirement: `docs/requirements/SAH-45.md` (rules BR45-1, BR45-5, BR45-16, BR45-23, BR45-29; billing rule B-4; legal rules L-4, L-6; decided sign-offs SO-45-13, SO-45-31, SO-45-32; answered questions OQ-45-23; answered, default accepted (DEC-039 addendum): OQ-45-27, OQ-45-30; carried over from SAH-43: OQ-43-22, OQ-43-32; BRD section 7 in `docs/handoffs/SAH-43-input-doctor-emr-brd.md`). Module: opd (platform, patient, ipd and billing through their `api` packages or domain events; where WhatsApp and ABHA / ABDM live is Later, DEC-049). Phase 5. Depends on SAH-51. **SAH-68-1 to SAH-68-3 do not wait for anything. SAH-68-4, SAH-68-5 and SAH-68-6 are Later (DEC-049)** (was: wait for DEC-040; the sending needs module placement, WhatsApp provider, ABDM registration, credentials, PDF library, and those need the accounts and the legal opinion); the in-app parts (consent capture, message preview, send queue) stay as built with the send control switched off (BR45-29).
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorGeneral and the other doctor screens: "Recorded by … Nurse", "Complete & send to billing", "Share · WhatsApp / ABHA", "ABHA linked").
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details (`clinic.consultation.write`) use the EMR; the other roles see the effects (vitals entry, bill lines, follow-ups) on their existing screens. "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body.

## Where each integration of the BRD (section 7) is carried
| Integration | Story |
| --- | --- |
| Queue (Open EMR button) and workflow move on open | SAH-46-1, SAH-46-2 |
| Workflow move at Complete (skip rules, fee timing) | SAH-51-2 |
| Vitals read | SAH-68-1 |
| Billing lines, visit type fee, IPD running bill | SAH-48-4, SAH-68-2 |
| Follow-up creation | SAH-51-6 (creation), SAH-68-2 (hand-off to the list) |
| IPD admission sheet, IPD round, Emergency outcome and triage | SAH-67-1 to SAH-67-4 |
| Patient record read and write-back | SAH-68-3 |
| Print | SAH-55-2, SAH-51-6 |
| ABHA link, WhatsApp, ABDM | SAH-68-4, SAH-68-5, SAH-68-6 (sending parts Later, DEC-049) |
| Audit log | each story's last audit criterion |

### SAH-68-1 · As a doctor, I want the vitals the nurse recorded to pre-fill the EMR, from the OPD vitals step, the IPD nursing chart or ER triage, so that I do not retype them
Priority: Must
**Scope note (12 Nov 2026, DEC-039 addendum, SO-45-43):** the human decided there is no nurse vitals value in phase 2; the nurse vitals pre-fill is a separate future story, placeholder SAH-72. This story (phase 5) and SAH-72 describe the same read; they are to be merged when scheduled, and nothing in SAH-47 to SAH-52 depends on it.
Screen: EMR mockup #DoctorGeneral, "Recorded by Sr. [NURSE NAME] · Nurse" under General examination, with date and time.
## Acceptance criteria
- Given an OPD visit with vitals recorded in the vitals step (SAH-9) for this visit, when the EMR opens, then BP, pulse, temperature, SpO2, weight, respiratory rate, pain and sugar that exist are pre-filled and the card shows "Recorded by <nurse name> at <time>" with the nurse's name read from Team and access (`platform.api`). (BR45-1)
- Given an admitted patient, then the latest entries of the nursing chart of that admission for the day (SAH-30) pre-fill the day's note with their recorder and time; given an Emergency case, then the triage vitals (SAH-28) pre-fill the assessment. (BR45-1)
- Given the vitals fields the nurse's step does not capture (for example respiratory rate or pain in the OPD step), then they stay empty for the doctor. (BR45-1)
- Given no vitals exist for this encounter but earlier ones exist, then the card shows "Last taken by <name>, <date and time>. Doctor's entry for this visit is still needed." and the earlier values are not copied in (SAH-60-1). (BR45-1)
- Given the doctor changes a pre-filled value, then the new value is stored with the doctor as recorder and the nurse's original entry stays unchanged in its own record; the EMR never edits the nurse's entry. (BR45-1)
- Given the nurse records vitals after the EMR is open, then the EMR shows "New vitals recorded. Use them?" with the values; they replace the pre-fill only when the doctor accepts. (BR45-1)
- Given the Who can fill setting Nurse / assistant on Vitals (SAH-53-1), then the nurse's step is the only place a nurse writes (OQ-45-14, decided). (BR45-16)
- Given the out-of-range colours, then they use the limits of the screen being shown (SAH-47-3 for generic, SAH-60-1 and SAH-65-2 for their own, tagged C-1 and C-8). Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed. (BR45-4)
- Given a doctor who is not the visit's doctor (not owner), a nurse, front desk, billing, pharmacist or counsellor calling the pre-fill directly, 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then no vital sign is written to a log line, audit value or analytics event together with a patient. (BR45-5)
## Notes for design
Read through the existing vitals, nursing and triage `api` ports; no copy is kept except the values the doctor saves into the visit.

### SAH-68-2 · As billing and the front desk, I want the doctor's services and visit type to reach the bill and the follow-up list, so that nothing is entered twice
Priority: Must
Screen: EMR mockup #DoctorDental "Complete & send to billing" and "Send to billing?", #EmrList "Procedures and services", #GeneralTreatment "Follow-up".
## Acceptance criteria
- Given services ticked with Billing "Bill today" (SAH-48-4), when the visit is signed, then billing sees one line each on the visit bill through the billing `api` (SAH-10) with the tariff price for the doctor times the quantity; lines already created at tick time are not duplicated; the lines created are exactly the ones on screen at signing. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (B-4). (BR45-23)
- Given an IPD encounter, then the ticked services go to the admission's running bill (SAH-25) instead of an OPD visit bill; given an Emergency encounter, they go to the bill that encounter already has. (BR45-1)
- Given the visit bill is already paid, then new lines are not forced onto it: the dialog of SAH-51-1 shows "Already billed. Ask billing to adjust." for the changed items and the bill is not edited by the EMR (SAH-10). (BR45-1)
- Given the doctor changes the visit type (SAH-52-2), then the consultation fee rule of SAH-9 is applied again while the bill is unpaid (a follow-up inside the clinic's follow-up window uses the follow-up fee rule of the clinic), and the bill shows the new fee; given the bill is paid, the change is refused for the bill with the same message and the type still changes. Rule tag as above (B-4). (BR45-1)
- Given a follow-up was chosen (SAH-51-6), when the visit is signed, then the front desk sees it in Follow-ups (SAH-14) in Due today, Upcoming, Missed or Returned by the date, with the doctor and the reason "Review after treatment"; the diagnosis never appears in the reason, in the list, or in any role-visible text (OQ-43-32 default). (BR45-23)
- Given a patient returns for the follow-up, then the follow-up becomes Returned by the existing token logic (SAH-14); nothing is added here. (BR45-1)
- Given the dental "Send to billing?" dialog, then it uses this story's rules (SAH-69-3); the other specialties use the lines on screen. (BR45-23)
- Given the SMS reminder the day before a follow-up (mockup text), then it is shown disabled "SMS reminders: later" (OQ-45-27). (BR45-29)
- Given billing and front desk, then they can read the resulting lines and follow-ups on their own screens and cannot edit the EMR (403 on any EMR call); a doctor of another clinic gets 404. (BR45-16, BR45-5)
- Given the same sign is retried, then no bill line and no follow-up is duplicated (idempotent by visit id and line id). (BR45-23)
- Given the audit, then one entry records who, when, visit id, number of lines and total (no service names with the patient). (BR45-5)
## Notes for design
Price lists, packages and the doctor's own fee are existing SAH-7 and SAH-18 data. The bill and follow-up changes happen inside the single sign action of SAH-51-2.

### SAH-68-3 · As a doctor, I want allergy and chronic conditions to come from and go back to the patient record, and the signed visit to appear in the patient's history, so that the header and the history are always right
Priority: Should
Screen: EMR mockup #DoctorGeneral header tags (allergy, known conditions) and #VisitHistory ("Allergies", "Long-term" facts).
## Acceptance criteria
- Given the patient record holds an allergy text and a chronic conditions text (SAH-8), when the EMR opens, then the header shows the red "Allergy: <text>" and the amber "Known: <text>" tags from them and the Allergies and Past history sections open pre-filled from them (SAH-47-1). (BR45-1)
- Given nothing is recorded for allergy, then the header shows no allergy tag and the history box shows "Not recorded"; "No known allergy" is shown only when "none" was explicitly recorded by a doctor (OQ-45-23, decided; OQ-43-22 open). (BR45-1)
- Given the doctor changes allergies or long-term conditions in the visit, when the visit is signed, then the dialog of SAH-51-1 shows an information row "Patient record will be updated: Allergies, Long-term conditions" and, on confirm, the patient record's text is replaced by the visit's confirmed list through the patient `api`; given the doctor did not change them, nothing is written. (BR45-1)
- Given the doctor recorded "None" for allergy explicitly, then the record is updated to "No known allergy" with the date and the doctor, which is the only way that text appears (OQ-45-23). (BR45-1)
- Given an allergy was recorded earlier and the doctor removes it, then the removal is written only after the doctor ticks "Remove from the patient record" in the dialog; otherwise the record keeps it. (BR45-1)
- Given the visit is signed, then a domain event "visit signed" carries the visit id, the patient id, the doctor and the department to the patient module so that the visit appears in the patient's history (SAH-50) and in the patient's visit list; the event holds no clinical text. (BR45-1)
- Given the Past visits facts box (SAH-50-2), then it reads the same record, so the allergy line matches the header. (BR45-24)
- Given two doctors change the same record, then the later write is refused with 409 if the record changed since the visit opened; the dialog says "The patient's record changed. Review before updating" and the visit still signs. (BR45-1)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403 on the write-back; another clinic's patient, 404; no login, 401. (BR45-16, BR45-5)
- Given any write, then one audit entry records who, when, patient id and the field names changed (not the values) and no log line holds the allergy or condition text. (BR45-5)
## Notes for design
The patient record holds one optional free text for each today (SAH-8). Structured allergy and the "no known allergy" versus "not asked" distinction are OQ-43-22; until then the written text is the doctor's confirmed list joined with semicolons.

### SAH-68-4 · As a patient, I want my ABHA linked or shared only if I consent, so that my identity is handled lawfully (sending and linking: Later, DEC-049)
Priority: Could
Screen: EMR mockup #DoctorGeneral header tag "ABHA linked" and #CasePaper "Share · WhatsApp / ABHA".
## Acceptance criteria
- Given the section ABHA / ABDM link (Compliance; ABHA number with OTP verification, "Consent to share"; Nurse / assistant and Reception can fill), then the in-app part is built now: the section shows the library fields, a consent record for "Patient agrees to link and share ABHA" (yes or no, date, time, recorded by) and the sending controls switched off with "Needs the ABDM service". (BR45-29)
- Given the consent is recorded, then it is patient data: shown only to roles allowed to see the patient, audited with who, when and patient id (not the ABHA number), and never written to a log line or analytics event. (BR45-5)
- Given the ABHA number is typed, then it is 14 digits; an invalid length is refused with an error; the number is stored with the patient (patient `api`) and never logged. (BR45-5)
- Given the ABDM service exists, when the OTP verification succeeds, then the header tag "ABHA linked" shows for that patient; until then the tag is never shown (SAH-47-1). **Later (DEC-049)** (ABDM registration as HIP and HIU, sandbox first, credentials, module placement). (BR45-29)
- Given the patient withdraws consent, then the tag is removed, sharing stops and the withdrawal is recorded with date and time. **Later (DEC-049)** (the consent record and its withdrawal date and time are kept in-app as built; removing the tag and stopping sharing need the ABDM service). (BR45-5)
- Given a nurse, front desk, billing, pharmacist or counsellor, then the consent can be recorded by those allowed by the section's Who can fill (Reception and Nurse / assistant), not by billing, pharmacist or counsellor (403); another clinic's patient, 404. (BR45-16)
## Notes for design
Nothing marked "Later (DEC-049)" is built now; it becomes a new story when the ABDM sandbox registration and the legal opinion exist. The existing consent setting is Clinic setup → Privacy, ABDM and backup. Legal wording of the consent is not drawn and is not invented (L-6, tagged).

### SAH-68-5 · As a doctor and as billing, I want the prescription, case paper, reminders and receipts sent to the patient on WhatsApp with the patient's consent, so that the patient has them on the phone (sending: Later, DEC-049)
Priority: Should
Screen: EMR mockup #DoctorGeneral (complete dialog checkbox "Send prescription to patient on WhatsApp"), #CasePaper "Share · WhatsApp / ABHA", #DoctorPaeds, #DoctorPhysio, #OncologyTreatment, #TreatmentCourse, #FormBuilder.
## Acceptance criteria
- Given any "Send on WhatsApp" control of the programme, then the in-app part is built now and uses one shared component: a message preview, a record of the patient's WhatsApp consent (yes or no, date, time, recorded by) and a send queue with states Queued, Sent, Failed and Cancelled; the send control itself is switched off with "Needs the WhatsApp service". (BR45-29)
- Given the list of senders, then they are: prescription at Complete (SAH-51-1), advice (SAH-48-3, SAH-56-2), case paper (SAH-55-3), receipt (SAH-58-5), vaccine reminder (SAH-62-4), home exercise programme (SAH-63-4), chemo calendar and new date (SAH-70-1), form link for a patient (SAH-59-4). (BR45-29)
- Given the service exists, when a message is queued, then it is sent only if the patient's WhatsApp consent is recorded and not withdrawn; otherwise nothing is sent and the message says why. **Later (DEC-049)** (WhatsApp provider, template approval, patient opt-in text). (BR45-5)
- Given a send fails, then the state is Failed with a reason and a "Retry" for the sender; no message is lost silently. **Later (DEC-049).** (BR45-29)
- Given the PDF of a document, then it is generated in India with the same content as the print; **Later (DEC-049)** (PDF library). (BR45-29)
- Given a send, then the audit holds who, when, document type, visit id and channel; never the mobile number, the message text or the document. **Later (DEC-049).** (BR45-5)
- Given data residency, then no patient data leaves India for this feature (CLAUDE.md); the architect confirms the provider's data path. **Later (DEC-049).** (BR45-5)
- Given a role other than doctor, visiting doctor, owner with doctor details (and billing for receipts), 403; another clinic's data, 404. (BR45-16, BR45-5)
## Notes for design
One sending use for all buttons; the integration gateway and notifications are not in phase 1 (CLAUDE.md). This story's sending work is the one that becomes a new story when the Meta account exists (DEC-049); the architect decides then where the use lives.

### SAH-68-6 · As a doctor, I want a signed visit saved as an ABDM OP consultation record, so that it can be shared to the patient's ABHA when the patient agrees (sharing: Later, DEC-049)
Priority: Could
Screen: EMR mockup #CasePaper footer ("saved as an OP consultation record and can be shared to the patient's ABHA").
## Acceptance criteria
- Given the case paper footer, then the ABHA sentence "This visit is saved as an OP consultation record and can be shared to the patient's ABHA." shows only when ABHA sharing is switched on for the clinic (SAH-55-1); sharing is off (DEC-049), so the sentence stays hidden until the Later work is done. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (L-4). (BR45-29)
- Given the codes stored on the fields (ICD-10, SNOMED CT, LOINC), then they are kept with the signed visit now so that the record can be built later (SAH-59-1, SAH-56-2). (BR45-6)
- Given the service exists and the patient consented (SAH-68-4), when the visit is signed, then an OP consultation record is built from the sections chief complaints, allergies, medical history, investigation advice, medications, procedures and follow-up as a FHIR bundle and shared; sharing is manual by the doctor, not automatic on sign unless the human later decides so (BRD question 5 default). **Later (DEC-049)** (FHIR bundle, consent artefact, ABDM gateway). (BR45-29)
- Given a share fails, then the state shows Failed with a retry and the signed record is unchanged. **Later (DEC-049).** (BR45-29)
- Given any share, then the audit holds who, when, visit id and "shared to ABHA", never the ABHA number or the bundle. **Later (DEC-049).** (BR45-5)
- Given a role other than doctor, visiting doctor and owner with doctor details, 403. (BR45-16)
## Notes for design
The ABDM gateway is on the "not in phase 1" list in CLAUDE.md. Module placement and credentials are decided when the Later work starts (DEC-049).

## Out of scope for SAH-68
Opening the EMR and the workflow move at open (SAH-46); the workflow move at Complete (SAH-51-2); the IPD admission sheet and ER outcome (SAH-67); service line editing (SAH-48-4); SMS (OQ-45-27); payment gateway; Lab and Pharmacy (OQ-45-28).

## Traceability
BR45-1 -> SAH-68-1, -2, -3. BR45-4 -> SAH-68-1. BR45-5 -> SAH-68-1 to SAH-68-6. BR45-6 -> SAH-68-6. BR45-16 -> SAH-68-1 to SAH-68-6. BR45-23 -> SAH-68-2. BR45-24 -> SAH-68-3. BR45-29 -> SAH-68-2, -4, -5, -6.

## Open questions
In `docs/requirements/SAH-45.md`: B-4, L-4, L-6 (tagged), SO-45-13, SO-45-31, SO-45-32, OQ-45-23, OQ-45-27, OQ-45-30. From SAH-43: OQ-43-22 (structured allergy), OQ-43-32 (follow-up reason; default applied). **DEC-049 (13 Nov 2026) moved the sending parts of SAH-68-4, SAH-68-5 and SAH-68-6 to Later; they start again as a new story when the Meta WhatsApp Business account, the ABDM sandbox registration and a legal opinion exist.**

## Status log
- 12 Nov 2026 · outline by the business-analyst; updated after DEC-039 and DEC-040.
- 12 Nov 2026 (phase 5) · full stories written from the BRD section 7 and the mockup; SAH-68-1 to SAH-68-3 ready for approval; SAH-68-4 to SAH-68-6 written with the in-app part now and the rest marked "Waits for DEC-040". Awaiting human approval of `stories`.
- 13 Nov 2026 · DEC-049 item 1: every "Waits for DEC-040" in SAH-68-4 (2 criteria), SAH-68-5 (5 criteria) and SAH-68-6 (3 criteria) is now "Later (DEC-049)"; the footer sentence criterion of SAH-68-6 notes sharing is off. Built in-app parts unchanged. No criterion deleted.
