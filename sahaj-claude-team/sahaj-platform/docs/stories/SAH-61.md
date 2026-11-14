# SAH-61 · Stories: Obstetrics & Gynaecology screen (visit mode, antenatal check with live warnings, PCPNDT Form F, scans and due orders, gynae examination)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-4, BR45-5, BR45-6, BR45-19, BR45-20; clinical rule C-4; legal rule L-2 PCPNDT Form F; decided sign-offs SO-45-1, SO-45-18, SO-45-41; answered questions OQ-45-7, OQ-45-25; answered, default accepted (DEC-039 addendum): OQ-45-27, OQ-45-28, OQ-45-29, OQ-45-30). Module: opd. Phase 4 (BRD out of scope, in scope by DEC-039 answer 1). Depends on SAH-47 (frame), SAH-48 (Prescription), SAH-56 (EMR list), SAH-58 (Pregnancy care course), SAH-51 (finish). Department: Obstetrics & Gynaecology (starter department, 25 sections, SAH-53-1). **Added 12 Nov 2026 at the end of the block (no renumbering): SAH-61-4 (ANC visits table, scan schedule, due orders, supplements and risk chips), split from the oversized SAH-61-1.**
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorGynae).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details (`clinic.consultation.write`) write the screen, on their own patients (owner: all). Nurse, front desk, billing, pharmacist and counsellor get 403 on a direct call. "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body.
Rule tag: "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (BR45-4). It is repeated on each clinical and legal rule below. Pregnancy data is health data of a woman and, in Form F, of a legal register: it is never written to a log, audit value or analytics event.

### SAH-61-1 · As an obstetrician, I want to choose the kind of visit and see an antenatal summary and today's check with live warnings, so that I notice a risk during the visit
Priority: Should
Screen: EMR mockup #DoctorGynae, "This visit is" (Antenatal (ANC) or Gynae), summary strip, "Today's ANC check", symptom chips, alert cards.
## Acceptance criteria
- Given the screen opens, then "This visit is" offers "Antenatal (ANC)" and "Gynae (not pregnant)" with the line "The screen shows only the sections for the visit type you pick."; the mode is stored with the visit; a patient with an open pregnancy course (SAH-58) opens in Antenatal, otherwise the doctor picks. (BR45-6)
- Given Antenatal, then a summary shows: gestational age in weeks and days (for example 27w 1d), expected date, obstetric score (for example G2 P1 L1 A0), days to the expected date and the plan note (for example "Next ANC at 30 weeks"). The expected date is worked out from the LMP by Naegele's rule, or taken from a dating scan when one is recorded; the gestational age follows it. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-4). (BR45-4)
- Given no LMP and no dating scan, then the summary shows "Not recorded" for gestational age and expected date, and the checks that need the gestational age (fundal height) show "Needs LMP or dating scan". (BR45-4)
- Given "Today's ANC check", then steppers (minus and plus buttons and typed entry) show weight (with the note "+x kg since last visit"), BP systolic, BP diastolic, fundal height (cm), fetal heart rate (bpm) and haemoglobin (g/dL), and the hint "Use − / + ; warnings update as you go". Weight steps follow the mockup's feel; fundal height 1 cm, fetal heart 2 bpm and haemoglobin 0.1 g/dL are the step sizes drawn. (BR45-6)
- Given the values change, then each shows a note beside it: BP "High: 140/90 or above" or "Normal"; fundal height "Does not match <n> weeks" or "Matches gestational age"; fetal heart "Outside 110–160" or "Normal (110–160)"; haemoglobin "<Mild/Moderate/Severe> anaemia" or "Normal". Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-4). (BR45-4)
- Given the thresholds, then: BP is high at systolic 140 or above or diastolic 90 or above; anaemia is Mild below 11 g/dL, Moderate below 10, Severe below 7; fundal height does not match when it differs from the gestational weeks by more than 2; fetal heart is outside range below 110 or above 160. Rule tag as above (C-4). (BR45-4)
- Given BP is high, then an alert "Possible pre-eclampsia." shows with the text "Repeat BP after 15 minutes rest and check urine protein." (and " Headache / blurred vision reported." when either symptom is ticked) and the button "Order PIH workup"; tapping it records the order of urine protein, LFT, platelets and uric acid in the EMR (no lab order is sent, OQ-45-28), the button reads "✓ Workup ordered" and the message reads "PIH workup ordered: urine protein, LFT, platelets, uric acid". Rule tag as above (C-4). (BR45-4)
- Given Hb is below 11, then an alert "<Mild/Moderate/Severe> anaemia (Hb x.x)." shows with the text "Continue oral iron." (Mild), "Consider IV iron at this stage of pregnancy." (Moderate) or "Needs urgent evaluation." (Severe) and the button "Add to plan", which adds a plan line to the visit notes ("Anaemia plan added to notes"). Rule tag as above (C-4). (BR45-4)
- Given the symptom chips Headache, Blurred vision, Bleeding / leaking and Reduced movements, then each toggles on tap; given "Bleeding / leaking" or "Reduced movements" is on, then an alert "Warning sign reported." shows with "Do NST and examine before the patient leaves." and the button "Order NST" (message "NST ordered", recorded in the EMR only). Rule tag as above (C-4). (BR45-4)
- Given an alert is showing, then it stays until the value or symptom that caused it changes; alerts are advice for the doctor, not a block: they do not stop Complete, but each unresolved alert is listed in the Complete dialog as information (SAH-51-1). (BR45-20)
- Given the visit is Gynae (not pregnant), then the antenatal summary, the check, the alerts, ANC visits, scans and due orders are hidden and SAH-61-3 shows. (BR45-6)
- Given the visit is signed, then the card is read-only; a correction is an addendum (SAH-51). (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, or a doctor who is not the visit's doctor (not owner), when they call any change, then the answer is 403 and nothing changes; given another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then no BP, Hb, fetal heart, symptom or order is written to a log line or audit value together with a patient. (BR45-5)
## Notes for design
Alert rules run in the screen from the values (no server round trip is needed for the colours); the stored record holds the values, not the alerts. The mockup compares fundal height with a fixed 27 weeks because it is example data; the product uses the real gestational age. The top-bar link "ANC register" is not built, because the doctor-screen top bar is not built (SAH-47-1, DEC-041). The Pregnancy care course is SAH-58.

### SAH-61-2 · As an obstetrician, I want PCPNDT Form F to open when I order an obstetric scan, so that the legal form is not forgotten
Priority: Should
Screen: EMR mockup #DoctorGynae, card "PCPNDT Form F" marked "Legal".
## Acceptance criteria
- Given the department Obstetrics & Gynaecology, then the section PCPNDT Form F is in the layout and cannot be hidden by the owner (OQ-45-7 decided; OQ-45-29 asks the human whether the lock stays, default it stays). (BR45-10)
- Given the card, then it carries the mark "Legal" and the text "Opens automatically when an obstetric USG is ordered. 3 of 3 forms complete for this pregnancy." when no scan has been ordered today. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (L-2). (BR45-4)
- Given the doctor orders an obstetric scan (the "Growth scan + Form F" due order, SAH-61-4, or an obstetric ultrasound from Investigations), then Form F opens automatically and the text reads "Form F started for the growth scan. Fill and sign before the scan is done." with the scan named. Rule tag as above (L-2). (BR45-4)
- Given Form F, then it holds the library fields: "Form F" (template, required for every obstetric USG), "Referral slip" (file) and "Declaration" (signature, patient and doctor). The referral slip file and the signature image need the documents service and are shown disabled with a "Later" note (OQ-45-28); the doctor ticks "Form F filled and declaration signed" with the date, time and doctor as the record until then. No legal wording beyond the mockup's is written here. (BR45-6)
- Given the card shows the count "n of m forms complete for this pregnancy", then n is the number of Form F records marked filled and signed for this pregnancy and m is the number of obstetric scans ordered or done in it; the mockup's "3 of 3" is example data. (BR45-4)
- Given a scan is ordered and its Form F is not marked filled and signed, then the section is Required and the footer lists "pcpndt form f" under "Still needed" (SAH-47-4) until it is marked; Form F is not a block on ordering the scan. (BR45-20)
- Given a Form F record, then it is tenant data visible only to doctors, visiting doctors and the owner (the rules of other EMR data); retention and statutory reporting are not drawn and are not built (legal review later, tagged). Rule tag as above (L-2). (BR45-4)
- Given the scan order is removed before Form F is marked, then the Form F draft is kept with the visit and shown as "Scan removed. Form F kept"; nothing is deleted. (BR45-6)
- Given a signed visit, then Form F is read-only. (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then Form F content is never written to a log line or audit value; the audit records who, when, visit id and "Form F marked". (BR45-5)
## Notes for design
Form F opens as a section that scrolls into view and is highlighted (like a missing section, SAH-47-4). Where Form F data is reported to an authority is not drawn.

### SAH-61-3 · As a gynaecologist, I want menstrual history, per-abdomen, per-speculum and per-vaginum examination, contraception and quick orders, so that a non-pregnant visit is complete
Priority: Should
Screen: EMR mockup #DoctorGynae, Gynae (not pregnant) mode: menstrual history, "P/A, P/S & P/V examination", contraception, infertility workup, quick orders.
## Acceptance criteria
- Given the visit mode Gynae, then the sections shown are those of the published layout that apply: Menstrual history, P/A, P/S & P/V examination, Contraception, Infertility workup and the common sections; hidden ones stay hidden. (BR45-19)
- Given the visit mode, then the sections of the other mode are hidden as drawn: Antenatal hides the gynae examination, Infertility workup and Contraception; Gynae hides the obstetric history, the ANC check, the ANC scans and PCPNDT Form F (with the exception in the next line). A hidden section keeps its stored values, is never listed under "Still needed" and never blocks Complete, whatever its Required setting; the hidden set is decided by the server and returned with the screen, so all clients agree. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-4). (BR45-19, BR45-20)
- Given the mode is Gynae and Form F is required (an obstetric scan was ordered in this record and Form F is not yet marked filled and signed, SAH-61-2), then PCPNDT Form F stays visible and stays in the footer; switching the mode never drops a legal Form F from the footer (H21, applied by default; the human can change it). Rule tag as above (L-2). (BR45-10, BR45-20)
- Given Menstrual history, then the doctor picks the cycle (regular or irregular as in the mockup's chips), cycle length and flow from the chips drawn; the summary in the Complete dialog reads for example "Gynae visit · cycle regular, flow normal". Chips are starter content. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-10). (BR45-4)
- Given P/A, P/S and P/V, then each is a free text with quick chips; the field "Female chaperone present: [name]" asks for the chaperone's name or "Declined by patient" and the examination cannot be marked done without it. The chaperone's name is personal data and is never logged. (BR45-5)
- Given Contraception, then the choices None, Condoms, OCP and the others drawn are one or several; the dialog line reads "Contraception: <choices>". (BR45-6)
- Given Infertility workup (Treatment part at start), then it renders its library fields with the generic renderer (SAH-47-3). (BR45-6)
- Given the quick orders Pap smear, Pelvic USG, Thyroid profile, CBC and hormones, then each is a chip that adds the test to Investigations (SAH-56) and records the order in the EMR only (OQ-45-28). A Pelvic USG does not open Form F because it is not an obstetric scan (the mockup opens Form F for obstetric USG only). (BR45-4)
- Given a signed visit, then the sections are read-only. (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then chaperone name, history and findings are not written to a log line or audit value. (BR45-5)
## Notes for design
The mockup shows the chips but not every label; the architect seeds the chips from the mockup source. The chaperone rule is a safety choice added by the analyst because the mockup puts a name field next to the examination; the human can remove the "cannot be marked done without it" part.

### SAH-61-4 · As an obstetrician, I want the earlier ANC visits, the scan schedule, due orders, supplements and risk chips on the screen, so that nothing due is forgotten
Priority: Should
Screen: EMR mockup #DoctorGynae, "ANC visits" table, "Scans and tests" schedule, "Due" orders with "Order all", "Supplements", risk chips.
## Acceptance criteria
- Given Antenatal, then the "ANC visits" table lists this pregnancy's earlier visits with the columns Date, gestational age, weight, BP, fundal height, Hb and a note, with today's row last and marked "Today". (BR45-6)
- Given the scan and test schedule, then it lists the scans with their windows and states: Dating scan 6–9 w, NT scan 11–13+6 w, Anomaly scan 18–22 w, Growth scan 28–32 w, and "Doppler or NST from 36 w"; a scan is Done (with the finding text the doctor typed), Due (when the gestational age is in or past its window) or later. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-4). (BR45-4)
- Given the due orders, then they show "OGTT (75 g)" with the window 24–28 w, "Td 2nd dose" with "give today" and "Growth scan + Form F" with 28–32 w; tapping one toggles it ordered or un-ordered with the message "<name> ordered" or "<name> un-ordered", and "Order all" orders all that are not yet ordered. Rule tag as above (C-4). (BR45-4)
- Given a due order is ordered, then it is recorded in the EMR and listed under Investigations or Procedures & services; no lab order and no stock movement is made (OQ-45-28). (BR45-6)
- Given "Growth scan + Form F" is ordered, then Form F opens (SAH-61-2). (BR45-4)
- Given Supplements, then the list shows Iron (1-0-1 after food), Calcium 500 mg + Vit D3 (0-1-0, 2 h apart from iron) and Folic acid 5 mg (1-0-0) as tickable lines that add to the Prescription (SAH-48-1) with their frequency and note; the allergy alert of SAH-48-2 applies. The doses are starter content. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-4). (BR45-4)
- Given risk chips, then "High risk: previous LSCS" shows in red when the obstetric history records a previous caesarean, "<Mild/Moderate/Severe> anaemia · Hb x.x" shows in orange when Hb is below 11, and "BP high today" shows in red when BP is high; every chip has text. (BR45-4)
- Given the Complete dialog, then its summary for Antenatal reads: "ANC visit n at <weeks> · BP x/y · Hb z", "Ordered: <list or nothing>" and "Next ANC at <weeks>" (the next visit week follows the mockup's example "30 weeks"; the rule that sets it is the doctor's typed choice, none is invented). (BR45-6)
- Given a signed visit, then the table, orders and chips are read-only. (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then scan findings and orders are not written to a log line or audit value. (BR45-5)
## Notes for design
The scan windows are a fixed table of the product (C-4). Counting a pregnancy (course) and the ANC register list are SAH-58 and OQ-45-28.

## Out of scope for SAH-61
The ANC register list and vaccination register (no module, OQ-45-28); lab orders and results; the signature and file image of Form F (documents service); statutory reporting of Form F; delivery and postnatal screens (not drawn); the frame (SAH-47); Complete (SAH-51).

## Traceability
BR45-4 -> SAH-61-1 to SAH-61-4. BR45-5 -> SAH-61-1 to SAH-61-4. BR45-6 -> SAH-61-1 to SAH-61-4. BR45-10 -> SAH-61-2. BR45-16 -> SAH-61-1 to SAH-61-4. BR45-18 -> SAH-61-1 to SAH-61-4. BR45-19 -> SAH-61-3. BR45-20 -> SAH-61-1, -2.

## Open questions
In `docs/requirements/SAH-45.md`: C-4 and L-2 (tagged), OQ-45-7 (answered), OQ-45-29 (open, default: the PCPNDT lock stays), OQ-45-28 (registers and orders: Later), OQ-45-27, OQ-45-30. Analyst additions with defaults: the chaperone rule and the Form F "filled and signed" tick.

## Status log
- 12 Nov 2026 · outline by the business-analyst; updated after DEC-039.
- 12 Nov 2026 (ADR-023 D13, DEC-044 addendum) · aligned: hidden sections of the visit mode are never required and never in the footer; Form F stays visible in Gynae mode while required (H21). Awaiting human approval of `stories`.
- 12 Nov 2026 (phase 4) · full stories written from the source of EMR mockup #DoctorGynae (modes, steppers, notes, alerts and button texts, Form F text, due orders, supplements, risk chips); SAH-61-4 added at the end of the block. Awaiting human approval of `stories`.
