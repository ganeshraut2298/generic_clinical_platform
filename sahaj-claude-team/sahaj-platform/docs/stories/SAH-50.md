# SAH-50 · Stories: Patient history (Past visits, OPD and IPD) beside the EMR
Requirement: `docs/requirements/SAH-45.md` (rules BR45-24, BR45-5, BR45-16, BR45-21; decided sign-offs SO-45-15, SO-45-17; answered questions OQ-45-22, OQ-45-23; answered, default accepted (DEC-039 addendum): OQ-45-28, OQ-45-30; carried over from SAH-43: OQ-43-30, OQ-43-32; BRD FR-14 in `docs/handoffs/SAH-43-input-doctor-emr-brd.md`). Module: opd (earlier IPD visits and discharge summaries read through the ipd `api`; patient facts through the patient `api`). Phase 3. Depends on SAH-47-6 (right-hand panel and tab shell), SAH-52-3 (ordering by encounter), SAH-48-1 (Copy medicines), SAH-56 (Repeat tests).
Mockup screen: Queue (sidebar → Queue) (EMR mockup #VisitHistory, 380 px wide, the "Past visits · OPD + IPD" tab; it is the first tab of the right-hand panel of every doctor screen).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details (`clinic.consultation.write`) read the Past visits panel, because it is part of the EMR; nurse, front desk, billing, pharmacist and counsellor do not open the EMR (403 on a direct call). "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body. The panel shows the patient's visits in this clinic only.
Rule tag: where a story says "Rule tag", the rule is "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (BR45-4). The department key-facts lists are of this kind.

### SAH-50-1 · As a doctor, I want to read this patient's earlier OPD and IPD visits newest first beside the EMR, so that I decide with the history in view
Priority: Must
Screen: EMR mockup #VisitHistory, title "Patient history", count text and visit rows.
## Acceptance criteria
- Given an open EMR, when the tab "Past visits" shows (it is the first tab, SAH-47-6), then the panel title reads "Patient history" with the count text "n OPD · m IPD" for the patient's earlier visits in this clinic. (BR45-24)
- Given earlier visits exist, then each is a row with its date (for example 28 Oct 2026), a type chip OPD or IPD, a title (for example "BP follow-up", "Chemo cycle 3 (AC) · day care"), and the doctor with the place or department (for example "Dr. [DOCTOR NAME] · General Medicine", "Dr. [DOCTOR NAME] · Day-care bed 4"); rows are newest first, loaded in pages with "Load more" for long histories. (BR45-24)
- Given the visit across departments (for example a Physiotherapy doctor opens a patient who had an Orthopaedic consult), then the earlier visits of every department of this clinic show. (BR45-24)
- Given the patient has no earlier record, then the panel says "First visit. No earlier records." (BR45-24)
- Given the open EMR is an IPD daily progress note, then this admission's earlier notes and vitals come first, then earlier visits; given it is OPD or Emergency, then earlier OPD and IPD visits show (SAH-52-3). (BR45-21)
- Given the doctor may not see an IPD ward's data (ward scope and break-glass, SAH-34), then those entries are left out, the panel says "Some entries are hidden", and a break-glass access, when used, is audited by the existing IPD path. (BR45-24)
- Given the visit being written today, then it is not listed in Past visits. (BR45-24)
- Given a nurse, front desk, billing, pharmacist or counsellor user, when they call the history directly, then the answer is 403 and nothing is returned; given no login, 401. (BR45-16)
- Given a patient or visit of another clinic, then the answer is 404 and no data is returned (tenant check; row-level security). (BR45-5)
- Given a slow network, then a loading state shows; given the load fails, then an error with a retry shows. (BR45-24)
- Given any call, then no log line, audit value or analytics event holds a diagnosis, a drug, a test or a visit's text. (BR45-5)
## Notes for design
On a tablet the panel sits below the entry area with the same tabs (SAH-47-6). The BRD said "up to 8 earlier visits"; the mockup lists all of them with search, so the mockup wins (SO-45-15). Who else besides the treating doctors may see a diagnosis, for example in a follow-up reason, is OQ-43-32 and is not decided here.

### SAH-50-2 · As a doctor, I want a short facts box for the patient that fits my department, so that I see what matters before I read the visits
Priority: Must
Screen: EMR mockup #VisitHistory, grey box above the filter, department key facts.
## Acceptance criteria
- Given the panel, then a facts box with four labelled lines shows above the visits, and the labels depend on the visit's department. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed. As drawn: General Medicine: Allergies, Long-term, Surgeries, Regular meds; Dental: Allergies, Medical, Habits, Past dental; Obstetrics & Gynaecology: Allergies, Obstetric, Blood group, Risk; Paediatrics: Allergies, Birth, Vaccines, Past illness; Physiotherapy: Diagnosis, Referred by, Medical, Package; Medical Oncology: Diagnosis, Receptors, Allergies, Scheme; General Surgery: Allergies, Past surgery, Medical, Blood thinners. The other eight departments use the General Medicine labels. (BR45-24)
- Given a fact is important, then it is shown highlighted in red (as drawn, for example Allergies, Habits, Obstetric, Risk, Vaccines overdue, Diagnosis in Oncology, Medical in Surgery) and the text also says what it is, so colour is not the only signal. (BR45-24)
- Given a fact has a value recorded in the patient record or in the patient's earlier EMR sections, then the latest value shows; given none, then the line shows "Not recorded". It never shows "None known" unless "none" was explicitly recorded (OQ-45-23, OQ-43-22). (BR45-24)
- Given a fact comes from a section that the clinic switched off in this department, then the line still shows when the value exists in the patient record; nothing is invented. (BR45-24)
- Given the Allergies line, then it matches the allergy tag in the EMR header (SAH-47-1) and updates when the doctor changes the allergy in this visit. (BR45-24)
- Given a user who may not open the EMR, 403; given another clinic's patient, 404. (BR45-16, BR45-5)
## Notes for design
The mockup draws the four facts for seven departments only; for the other eight the analyst used the General Medicine labels (not drawn, flagged for the human). The mockup keeps these facts in sample data. Where each fact comes from (patient `api`, EMR sections of earlier signed visits) is for the architect; the patient record holds only one free text for allergy and one for chronic conditions today (SAH-8). A structured source is OQ-43-22.

### SAH-50-3 · As a doctor, I want to search the history and filter it to OPD or IPD, so that I find a drug, a test or a diagnosis quickly
Priority: Must
Screen: EMR mockup #VisitHistory, tabs "All", "OPD", "IPD" and the box "Search history" (placeholder "Search: drug, test, diagnosis").
## Acceptance criteria
- Given the panel, then three filter tabs "All", "OPD" and "IPD" show, "All" selected at first; choosing one limits the rows to that type and the count text keeps the totals. (BR45-24)
- Given the doctor types in the search box, when the text matches a visit's title, complaint, diagnosis (name or code), drug name, test name or place (any part, any letter case), then only matching visits remain, with the filter applied together with the search. (BR45-24)
- Given nothing matches, then the list shows "Nothing matches." and no error. (BR45-24)
- Given the doctor clears the search, then the full list for the chosen filter returns. (BR45-24)
- Given the IPD filter and the doctor has no ward scope for some admissions, then those admissions are not matched or counted for that doctor. (BR45-24)
- Given the search text, then it is clinical data: it is never written to a log line, audit value or analytics event, and the request is limited to this clinic by row-level security. (BR45-5)
- Given a slow network, then the box keeps the typed text, shows a busy state, and a failure shows a retry; given a user without the EMR role, 403. (BR45-24, BR45-16)
## Notes for design
Search runs on the server over the patient's visits (the list can be long); the architect decides the matching method. The tabs are tablist/tab elements with keyboard support.

### SAH-50-4 · As a doctor, I want to open a past visit's details and reuse its medicines or tests, so that I do not retype a repeat prescription
Priority: Must
Screen: EMR mockup #VisitHistory, "Details ▾" / "Hide ▴", medicines, "Results", actions "Copy medicines to today", "Repeat these tests", "Open full visit" or "Open discharge summary".
## Acceptance criteria
- Given a visit row, when the doctor taps "Details ▾", then the row opens to show its recorded lines as label and value (for example Complaints, Findings, Diagnosis, Advice, Done, Plan, Pre-check, Given, Note), its medicines (name, frequency, days) and its "Results" (test name and value, an abnormal value shown in red with the word "Abnormal"), and the button reads "Hide ▴"; tapping it again closes the row. Only one row is open at a time. (BR45-24)
- Given a visit with medicines, when the doctor taps "Copy medicines to today", then the medicines are added to today's prescription (SAH-48-1) with frequency, days and when, skipping any already there, the drug-allergy check of SAH-48-2 runs on each, and the message reads "n medicine(s) copied to today's prescription". (BR45-24)
- Given a visit with tests, when the doctor taps "Repeat these tests", then the tests are added to today's Investigations list (SAH-56) and the message reads "n test(s) added to today's orders"; no lab order is sent anywhere, because Lab is Later (OQ-45-28). (BR45-24)
- Given a visit with no medicines or no tests, then the matching button is not shown. (BR45-24)
- Given an OPD visit, then the last action reads "Open full visit"; given an IPD visit, it reads "Open discharge summary"; either opens the signed record read-only in a new tab, and for IPD only if the doctor has ward scope (SAH-34). (BR45-24)
- Given today's visit is already signed, then both copy actions are disabled with the note "This visit is signed" (a correction is an addendum, SAH-51). (BR45-18)
- Given the doctor is not the doctor of today's visit (and not the owner), then the copy actions are refused with 403 and nothing is added. (BR45-16)
- Given a copy action is tapped twice quickly, then the items are added once. (BR45-24)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; another clinic's visit, 404. (BR45-16, BR45-5)
- Given any call, then no log line or audit value holds the copied drug or test names; the audit holds the visit ids and counts. (BR45-5)
## Notes for design
The Results block shows what the signed visit or discharge summary holds; there is no live Lab result feed (OQ-45-28). Out-of-range marks follow the stored flag of the result; no new limits are set here.

## Out of scope for SAH-50
Editing or deleting a past visit; printing the history; a patient-facing history; the ranking of past visits; Lab results auto-filled from a Lab module; the case paper (SAH-55); the vitals trend chart (SAH-60-1).

## Traceability
BR45-5 -> SAH-50-1 to SAH-50-4. BR45-16 -> SAH-50-1, -2, -4. BR45-18 -> SAH-50-4. BR45-21 -> SAH-50-1. BR45-24 -> SAH-50-1 to SAH-50-4.

## Open questions
In `docs/requirements/SAH-45.md`: OQ-45-23 (answered: no "No known allergy" unless recorded), OQ-45-28 (Lab feed and orders are Later), OQ-45-30 (44 px). From SAH-43, still open with defaults: OQ-43-22 (structured allergy), OQ-43-30 (English only today), OQ-43-32 (who may see a diagnosis in a follow-up reason).

## Status log
- 12 Nov 2026 · full stories written by the business-analyst from the source of EMR mockup #VisitHistory (count text, department key facts, filter, search, expandable rows and actions), BRD FR-14, DEC-039 and DEC-040. Replaces the outline. Awaiting human approval of `stories`.
