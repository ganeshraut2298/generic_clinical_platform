# SAH-64 · Stories: General Surgery screen, part 1 (history and surgical examination, pre-op work-up and fitness, surgery plan and OT booking)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-4, BR45-5, BR45-6, BR45-20, BR45-27; clinical rule C-7; decided sign-offs SO-45-1, SO-45-17; answered questions OQ-45-19, OQ-45-25; answered, default accepted (DEC-039 addendum): OQ-45-27, OQ-45-28, OQ-45-30, OQ-45-31). Module: opd (admission request through the ipd `api`; sitting in the Surgery episode through the course). Phase 4 (BRD out of scope, in scope by DEC-039 answer 1). Depends on SAH-47 (frame), SAH-52 (IPD entitlement), SAH-57 (Body map), SAH-58 (Surgery episode course), SAH-67 (admission), SAH-23. Department: General Surgery (starter department, 26 sections, SAH-53-1). **Oversized story split (12 Nov 2026): the estimate, payer, deposit, pre-authorisation, consent, pre-op orders and Complete moved to SAH-71. SAH-64 keeps the examination, fitness and the surgery plan with OT booking.**
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorSurgery).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details (`clinic.consultation.write`) write the screen, on their own patients (owner: all). Nurse, front desk, billing, pharmacist and counsellor get 403 on a direct call; the ward and front desk see the resulting admission request on the IPD screens. "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body.
Rule tag: "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (BR45-4). It is repeated on each clinical rule below.
The mockup's top bar links "OT schedule" and "Ward patients" are not built, because the doctor-screen top bar is not built (SAH-47-1, DEC-041).

### SAH-64-1 · As a surgeon, I want the history tags and a surgical examination with abdomen, signs and hernia sites, so that I record the findings quickly
Priority: Should
Screen: EMR mockup #DoctorSurgery, history tags, card "Surgical examination" ("Abdomen, hernia sites and per-rectal"), Body map.
## Acceptance criteria
- Given the screen opens, then history tags show the patient's recorded surgical and medical history in short form (the mockup's examples: "Mild asthma", "Past: appendicectomy 2019") read from the patient record and the earlier sections, with "Not recorded" when nothing is known; the allergy tag of the header applies (SAH-47-1). (BR45-6)
- Given the card "Surgical examination", then it shows groups: Abdomen (multi choice: Soft, Tender right upper, Tender right lower, Guarding, Distended, Lump felt), Murphy sign (Positive or Negative), Jaundice (No or Yes), Hernia sites (multi choice: Normal, Right inguinal, Left inguinal, Umbilical, Incisional) and Per-rectal (single choice: Not needed, Normal, Piles, Fissure, Mass). Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-10). (BR45-4)
- Given a choice is ticked, then a tick shows with the text; the case paper line reads for example "Abdomen: tenderness, guarding · Murphy's sign positive · jaundice no" (SAH-55-1). (BR45-25)
- Given the section Local findings (or the surgery examination on the body map), then the Body map of SAH-57 renders and is Done by SAH-57-4's rule. (BR45-26)
- Given the section Chief complaints, then the EMR list of SAH-56 renders; the Complete footer's "complaints" item follows the published layout (SAH-47-4). (BR45-22)
- Given the Assessment part, then it contains Complaints, examination, body map and fitness in the order of the layout, with the subtitle "Complaints, examination, body map, fitness". (BR45-6)
- Given a signed visit, then the card is read-only. (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; a doctor who is not the visit's doctor (not owner), 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then findings and history tags are not written to a log line or analytics event together with a patient. (BR45-5)
## Notes for design
Choices are starter content (C-10). The history tags are a read of existing data, not a new field.

### SAH-64-2 · As a surgeon, I want a pre-op work-up and a fitness check, so that I know whether the patient is fit before I book
Priority: Should
Screen: EMR mockup #DoctorSurgery, card "Fitness for surgery" ("Asked and checked before booking"), tests list.
## Acceptance criteria
- Given the card "Fitness for surgery", then it asks: ASA grade (I, II, III, IV), Other illness (multi choice: None, Diabetes, High BP, Heart disease, Asthma / COPD, Kidney disease), Blood thinners (single choice: None, Aspirin, Clopidogrel, Warfarin / DOAC) and Anaesthesia check (PAC) (single choice: Not done, Fit, Fit with caution, Unfit); PAC is Not done at start. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-7). (BR45-4)
- Given a blood thinner other than None, or Other illness other than None, or PAC Unfit, then the choice is shown in a warning style with text; it is information for the surgeon, not a block. Rule tag as above (C-7). (BR45-4)
- Given the pre-op tests, then they are listed as CBC, LFT, KFT, PT / INR, Blood sugar, ECG, Chest X-ray, HIV, HBsAg, HCV and Blood group; each cycles on tap Pending → Normal → Abnormal → Pending, with text and colour; the result is typed by the doctor (no Lab module, OQ-45-28). Rule tag as above (C-7). (BR45-4)
- Given the fitness summary line, then it reads "Fit for surgery" when no test is pending, no test is abnormal and PAC is set and not Not done or Unfit; "<n> test(s) pending: <names>" when tests are pending; "PAC not done" when PAC is Not done; "Not fit yet: <names> abnormal" or "Not fit yet: PAC unfit" otherwise. Rule tag as above (C-7). (BR45-4)
- Given the section Fitness for surgery is required at start for the department (the layout decides), then it is Done when the summary reads "Fit for surgery" or the doctor records PAC as Fit with caution with no test pending and none abnormal (the mockup counts fitness as done when no tests are pending, none abnormal and PAC is set and not Unfit). (BR45-20)
- Given the case paper, then it shows "Fitness: ASA <grade> · <summary>" under Examination (SAH-55-1). (BR45-25)
- Given the Pre-anaesthetic check-up starter form of the Form builder (SAH-59-6), then it can be added to the layout; it is not part of this screen. (BR45-28)
- Given a signed visit, then the card is read-only. (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then ASA grade, illnesses, drugs and test results are not written to a log line or analytics event together with a patient. (BR45-5)
## Notes for design
Which tests apply to which operation is not drawn: the list is the same for every procedure. The PAC is the surgeon's entry; an anaesthetist's separate sign-off is not drawn.

### SAH-64-3 · As a surgeon, I want a surgery plan that books the operation and requests a ward bed, so that the patient goes to the ward and OT with a booking
Priority: Should
Screen: EMR mockup #DoctorSurgery, 2 Treatment, "Surgery plan and OT booking" (procedure, approach, anaesthesia, admission, OT date and slot), "Book OT and admission".
## Acceptance criteria
- Given the section Surgery plan and OT booking (Treatment part at start), then the doctor picks the procedure from the list of the mockup: Lap cholecystectomy, Open cholecystectomy, Lap hernia repair (TEP), Open mesh hernioplasty, Lap appendicectomy, Haemorrhoidectomy, Excision of lipoma, Incision and drainage; each has a default anaesthesia and stay: GA and 1–2 days; GA and 4–5 days; GA and 1 day; Spinal and 2 days; GA and 1–2 days; Spinal and 1 day; Local and Day care; Local and Day care. The doctor can change them. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-7). (BR45-4)
- Given the plan, then "Approach" is free text (for example "Laparoscopic, may convert to open"), "Anaesthesia" is GA, Spinal, Local or Local + sedation, "Admission" is Day care, IPD 1 day, IPD 1–2 days, IPD 2 days or IPD 4–5 days, "OT date" is a date and "Slot" is a text label; the slot is a text because no OT schedule exists (OQ-45-28 default). Rule tag as above (C-7). (BR45-4)
- Given the doctor picks a procedure not in the list, then it is typed (EMR list kind "Procedure / service", SAH-56) with no default; no rate is invented (SAH-71-1). (BR45-6)
- Given the button "Book OT and admission", then its text states what is missing: "Needs: <diagnosis, n consent item(s), pre-authorisation>", "Ready to book" or "Booked"; the button works only when a diagnosis is entered, every consent item of SAH-71-2 is ticked, and, when the payer is not Self, the pre-authorisation is marked sent (SAH-71-1). Rule tag as above (C-7). (BR45-4)
- Given the button is tapped while something is missing, then nothing is booked and the message reads "Before booking: <the missing items>." (BR45-20)
- Given the button is tapped when ready, then the booking is recorded in the EMR and the Surgery episode (SAH-58) with the procedure, date and slot as text, an admission request with the admission type (Day care or IPD n days) is created through the ipd `api` (SAH-23, SAH-67-3) when the clinic's IPD is on, and the message reads "<procedure> booked for <date>, <slot>. Ward bed requested." The mockup also says "admission SMS sent"; the SMS part is shown disabled (OQ-45-27). (BR45-27)
- Given the clinic's IPD is off, then "Book OT and admission" records the booking in the EMR and the episode only, the message says "Booked. This clinic has no IPD, so no ward bed was requested." and the referral line of SAH-52-4 is offered. (BR45-27)
- Given a booking exists and the plan is changed, then the booking shows "Changed after booking. Book again" and the earlier booking stays in the record; the admission request is updated by the ipd `api` only if it is still open (a request already acted on by the ward is not altered). (BR45-27)
- Given the same booking is tapped twice or retried after a lost network, then one booking and one admission request result. (BR45-27)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; a doctor who is not the visit's doctor (not owner), 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given the audit, then one entry records who, when, visit id and "OT booked" (no procedure name). (BR45-5)
## Notes for design
No OT calendar, room or surgeon roster exists: "OT 2 · 10:00" is only text. The admission request is the existing SAH-23 admission with type; the pre-fill is SAH-67-3. The Complete footer for Surgery reads "Still needed: complaints, diagnosis, OT booking." until each is done and "Ready to complete. Admission letter and pre-op instructions will print." (SAH-71-3).

## Out of scope for SAH-64
Estimate, payer, deposit, pre-authorisation, consent checklist, pre-op orders, WHO surgical safety checklist and Complete (SAH-71); OT schedule and ward patient list (OQ-45-28); operation notes and post-op orders (library sections rendered by the generic renderer on the day of surgery); billing of the surgery (SAH-58).

## Traceability
BR45-4 -> SAH-64-1, -2, -3. BR45-5 -> SAH-64-1 to SAH-64-3. BR45-6 -> SAH-64-1, -3. BR45-16 -> SAH-64-1 to SAH-64-3. BR45-18 -> SAH-64-1, -2. BR45-20 -> SAH-64-2, -3. BR45-22 -> SAH-64-1. BR45-25 -> SAH-64-1, -2. BR45-26 -> SAH-64-1. BR45-27 -> SAH-64-3. BR45-28 -> SAH-64-2.

## Open questions
In `docs/requirements/SAH-45.md`: C-7 and C-10 (tagged), SO-45-17, OQ-45-19, OQ-45-25, OQ-45-27 (SMS: Later), OQ-45-28 (OT schedule: Later), OQ-45-30. New OQ-45-31 (who may admit from the EMR) affects the admission request in SAH-64-3 (default: the doctor sends the request; the admit roles of SAH-23 complete it).

## Status log
- 12 Nov 2026 · outline by the business-analyst; updated after DEC-039.
- 12 Nov 2026 (phase 4) · full stories written from the source of EMR mockup #DoctorSurgery (examination choices, fitness, tests, procedure list, booking rule); estimate, consent, orders and finish moved to SAH-71. Awaiting human approval of `stories`.
