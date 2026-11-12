# Input document for SAH-43..SAH-52: "Doctor EMR — Business Requirements"

Source: PDF of 11 Nov 2026 (15 pages, author shown: Aarti Pujari), given by the human in chat. This file is a faithful transcription by the tech lead so the business analyst can read it; the PDF is the source of truth. Mockup reference named in the BRD: "Sahaj Clinic Console mockup" (menu item "Doctor desk", Clinic setup → Doctor EMR) and "EMR Configuration by Department canvas" (richer designs for later releases). Neither is in `docs/design/` yet (DEC-036). The BRD says "where the mockup and this BRD differ, this BRD wins; anything neither covers is an open question for the product owner, not a developer choice." Under CLAUDE.md rule 1 each difference goes to the human.

## 1. Purpose and scope
Build the doctor's EMR, opened from the clinic's existing Queue for OPD, IPD and Emergency patients, driven by specialty templates, with a live case paper beside it. IPD and Emergency patients do not get a separate EMR.
In scope (release 1): open EMR from the existing Queue, and the EMR screen for one encounter of any type (OPD, IPD, Emergency); two parts per visit, 1 Assessment and 2 Treatment, each made of sections from the specialty template; section panel (rail) collapsed by default and can be opened; visit type marking (New, Follow-up, Revisit, Emergency) and Admit to IPD from the EMR; right half: Past visits and a live case paper (print, WhatsApp, ABHA share); Clinic setup page Doctor EMR (sections per template, show/hide, required, how the screen opens); hand-offs to queue workflow, billing, follow-ups and IPD admission.
The mockup shows OPD only under the name "Doctor desk"; in the product the EMR opens from the existing Queue (FR-02). Developer note: rebuild the screens in the product stack; do not copy the mockup's HTML or scripts. Requirement IDs (FR-xx) are the unit of work, each with acceptance criteria.

## 2. Users, roles and permissions
Only a doctor writes the EMR; everyone else sees its outcome (bill items, follow-up, admission request, printed case paper).

| Action | Doctor | Owner (doctor) | Nurse | Front desk |
| --- | --- | --- | --- | --- |
| Open EMR from the queue (OPD, IPD, ER) | Yes | Yes | No | No |
| Open and fill an EMR | Own patients | All patients | No | No |
| Mark visit type | Yes | Yes | No | Yes, at registration only |
| Admit to IPD from the EMR | Yes | Yes | No | No |
| Finish consultation (sign) | Yes | Yes | No | No |
| Fill vitals that pre-fill the EMR | No | No | Yes | No |
| Print or share the case paper | Yes | Yes | Reprint only | Reprint only |
| Clinic setup → Doctor EMR | No | Yes | No | No |

- The mockup has no separate Doctor role, so it shows the EMR (as "Doctor desk") to the Owner role only. Production needs a Doctor role mapped to a doctor master record (name, degree, registration number, specialty). (Tech lead note: the repo mockup does list a Doctor role and the platform has a doctor entry; BA to reconcile.)
- A doctor sees their own queue by default, with a filter for the other doctors' patients.
- Every save, sign, visit-type change and admission request is written to the audit log with user, time and before/after values.

## 3. End-to-end flow
The EMR sits at the consultation step: it opens from the existing Queue, for OPD, IPD rounds and Emergency alike, and hands the encounter back to its module when signed.
Flow: OPD, IPD or ER → Vitals by nurse → Open EMR → 1 Assessment → 2 Treatment → "Required done?" (no: jump to the missing section; yes) → Finish and sign → (Next step, usually billing; Follow-up reminder created; Case paper print, share, ABHA).
Admission branch, from the EMR header at any point: Admit to IPD → "IPD on?" yes: Admission sheet pre-filled from EMR → IPD module (bed, deposit, consent); no (OPD-only clinic): Advise admission + referral line.
Finish stays blocked until every required section is done; the doctor is taken to the first missing one. Admission can be raised at any point and does not end the consultation.

## 4. Functional requirements
### 4.1 Entry point
FR-02 Open EMR for any encounter, from any queue card. The EMR screen, rail, sections and case paper are the same; only these differ:

| | OPD | IPD | Emergency |
| --- | --- | --- | --- |
| Opens as | Consultation | Daily progress note (ward round) | Emergency assessment |
| Visit type control | New, Follow-up, Revisit, Emergency | Shows "IPD · day n", read-only | Shows the triage level, read-only |
| Past visits panel | Earlier OPD and IPD visits | This admission's earlier notes and vitals first, then earlier visits | Earlier OPD and IPD visits |
| Admit to IPD button | Shown | Hidden (already admitted) | Shown |
| Finish does | Next workflow step, usually billing | Signs the day's note; patient stays admitted; card shows round done | Asks for an ER outcome, then signs |

- Opening an OPD visit that is Waiting or at Vitals moves it to With doctor.
- The EMR opens on the part and right-panel tab set in Clinic setup (defaults: Assessment, Case paper).
- Back arrow returns to the queue with the same filter; the draft is kept.
(No FR-01 appears in the PDF; the delivery table cites FR-01–10.)

### 4.2 Patient header
FR-03 Header. Shows avatar initials, name, age, sex, token, UHID (or "New patient"), doctor. Tags: allergy (red) or "No known allergy", chronic conditions (amber), "Admission advised" and "Emergency" when set. An allergy ticked in the History section shows in the header at once.
FR-04 Mark visit as. Segmented control: New, Follow-up, Revisit, Emergency. Default = the type set at registration. Changing it updates the visit everywhere (queue card, case paper, bill rules). Emergency asks for a triage level and moves the patient into the Emergency group at the top of the same queue; it does not create a separate ER record list. Follow-up inside the clinic's follow-up window applies the clinic's follow-up fee rule (free or reduced). IPD and Emergency encounters show their encounter (IPD · day n, ER · triage colour) in place of this control.
FR-05 Admit to IPD. If IPD is on for the clinic: open the existing admission sheet pre-filled with name, age, sex, mobile, admission type (emergency if the visit is Emergency, else planned) and the diagnoses entered; mark the visit "Admission advised". If IPD is off: a sheet explains the clinic is OPD-only and offers Advise admission + referral (marks the visit and adds a referral line to the case paper) and, for the Owner, Switch on IPD (opens Clinic setup → Plan and modules). The button shows a pressed state once admission is advised.

### 4.3 Section rail
FR-06 Collapsed by default. The rail opens collapsed (about 58 px): a toggle, part markers 1 and 2, and one status dot per section. Dot states: green tick = done; red ! = required and not done; empty circle = optional and not done. Hover or focus shows the section name and "required". The Clinic setup option "Section panel: Collapsed / Open" sets the default; the doctor's toggle lasts for that visit.
FR-07 Expanded rail. About 250 px: template selector, part headers with done counts (e.g. Assessment 3/6), section names with status and a "Required" tag, a progress bar "x of y required done", and an Edit template link for the Owner.
FR-08 Jump to a section. Clicking a section (either state) switches to its part, scrolls to it and highlights it for about 1.5 s.

### 4.4 Work area
FR-09 Parts. Two tabs at the top: 1 Assessment and 2 Treatment, each with a subtitle and done count; a tick replaces the number when all sections in the part are done. Only the current part's sections show, in template order. Assessment ends with Continue to Treatment.
FR-10 Section card. Icon, name, red * when required, and a status tag: Done, Required or Optional.
FR-11 Section editors. Each section uses one of these editors:

| Editor | Sections | Behaviour |
| --- | --- | --- |
| Complaints | Chief complaints | Type and Enter or Add; quick-add chips ranked most used first (top 10); each complaint has a duration (1 day, 3 days, 1 week, 1 month) and can be removed; duplicates ignored |
| Vitals steppers | Vitals, Vitals & growth | BP systolic/diastolic, pulse, temperature (°F), SpO2, weight; −/+ steppers; pre-filled from the nurse's vitals step with an info note; out-of-range values turn red (BP ≥ 140/90, pulse > 100, temp ≥ 100 °F, SpO2 < 94) |
| Signs | General examination | Pallor, icterus, cyanosis, clubbing, lymph nodes, oedema, dehydration; tap toggles present/absent; All absent button |
| Systems | Systemic examination | CVS, RS, CNS, Abdomen with normal text hints; Normal/Abnormal each; All normal button |
| Diagnosis | Diagnosis | Type and Enter or quick chips from the specialty list; ICD-10 code shown when known; Provisional/Final per item; first = primary |
| Prescription | Prescription, Remedy & potency | Generic names in capitals; free entry parsed as name + frequency (1-0-1, SOS) + duration; favourites per specialty; Load usual Rx for the primary diagnosis; Repeat last visit; "No medicines needed today" checkbox |
| Tooth chart | Tooth chart (dental) | FDI 18–28 / 48–38; tap cycles healthy → caries → filled → root canal → missing → extract → healthy; colour legend |
| Treatment plan | Treatment plan & sittings (dental) | Suggestions from marked teeth (caries → filling, RCT → root canal 3 sittings, missing → implant/bridge); sittings −/+; remove |
| Services | Procedures & services | Clinic tariff for the doctor as chips with price; ticking adds the item to the visit bill, unticking removes it |
| Advice | Advice & follow-up | Advice chips; follow-up 3, 5, 7, 15 days, 1 month, not needed; note when inside the free/reduced follow-up window |
| Chip list | All other sections | The template's quick options as toggle chips; allergy and alert chips use the red style |

FR-12 Smart pre-fill. History pre-ticks the patient's known chronic conditions and allergy; Vitals pre-fills from the nurse's entry; a child's weight is estimated only when no weight was recorded.

### 4.5 Right half
FR-13 Tabs. Past visits | Case paper, with subtitles ("n earlier · OPD", "Live · fills as you type"). The panel is sticky and scrolls on its own.
FR-14 Past visits. A patient summary card (UHID, visit count, chronic, allergy, ABHA) then up to 8 earlier visits, newest first. Each shows diagnosis, date, doctor, visit type, complaint, vitals and medicines. Copy medicines to today adds them to today's prescription (skipping duplicates), switches to Treatment and shows a "check doses" toast. First visit: "First visit. No earlier records."
FR-15 Live case paper. Updates on every change. Layout and order are defined in section 6. Paper size follows Clinic setup → Case paper (A4/A5). Print and WhatsApp / ABHA buttons below the paper. The signature line shows the doctor's name only after Finish consultation.

### 4.6 Finish
FR-16 Sticky action bar. At the bottom of the work area: "Still needed: …" listing missing required sections (red), or "All required sections done" (green); Save draft and Finish consultation.
FR-17 Finish consultation. If anything required is missing: block, toast the list, switch to the first missing section's part and jump to it. Otherwise sign the record, then by encounter type: OPD: create the follow-up (if chosen) and move the visit to the next workflow step, usually Billing. IPD: save the day's progress note to the admission, mark the round done, and send new medicines and investigations to nursing, pharmacy and lab. Emergency: the doctor first picks an outcome (Discharge from ER, Admit to IPD, Refer out, Send to OPD); the ER module acts on it. Toast the outcome and return to the queue.
FR-18 Save draft. Saves without validation; a draft re-opens exactly as left. Autosave every 30 s while there are changes.

### 4.7 Clinic setup → Doctor EMR
FR-19 Setup page. New entry "Doctor EMR" under Common to all modules, after Specialty template, with the standard page header (what it does, who it affects, tips) and searchable in settings search.
FR-20 Sections per template. Template switcher (General practice, Dental, Paediatrics, Homeopathy; the clinic's own specialty marked "yours"). A table grouped by 1 Assessment and 2 Treatment: section name, part, number of quick options, Show switch, Required switch (disabled when hidden). Footer: Reset to template and a count "n sections on · m required". Owner only; other roles see it read-only. Changes are logged and apply to visits opened after the change.
FR-21 Screen defaults. Section panel Collapsed/Open (default Collapsed) and right panel first: Case paper/Past visits (default Case paper).

## 5. Specialty templates
The template is chosen from the doctor's specialty (doctor master), falling back to the clinic's specialty; the doctor can switch it for one visit from the expanded rail. * = required by default.

| Template | Doctor specialty | 1 Assessment sections | 2 Treatment sections |
| --- | --- | --- | --- |
| General practice | General Physician | Chief complaints*, History & allergies, Vitals*, General examination*, Systemic examination, Local examination | Diagnosis*, Prescription*, Investigations, Procedures & services, Advice & follow-up* |
| Dental | Dental Surgeon | Chief complaints*, Medical alerts*, Extra-oral examination, Intra-oral examination*, Tooth chart*, Radiographs | Diagnosis*, Treatment plan & sittings*, Prescription, Procedures & services, Advice & follow-up* |
| Paediatrics | Paediatrician | Chief complaints*, Birth & immunisation history, Vitals & growth*, General examination*, Development | Diagnosis*, Prescription (by weight), Vaccines given today, Advice & follow-up |
| Homeopathy | Homeopath | Chief complaints*, Case history*, Mentals & generals*, Vitals | Diagnosis*, Remedy & potency*, Advice & follow-up* |

Template rules:
- A template is data, not code: each section has key, label, part, required default, icon, editor type and quick options. New specialties (Gynaecology, Physiotherapy, Oncology, Surgery from the canvas) are added as data in later releases.
- Clinic overrides are stored per template and per section: label, part, shown, required. Reset to template clears them.
- Quick-option lists come from masters (complaints, diagnoses with ICD-10, favourite medicines, investigations, advice). Ranking for quick-add = how often this doctor used the item, then recency.
- A visit stores the template key and the section list it was opened with, so later setup changes never alter a signed record.

## 6. Data, completion rules and case paper
One EMR record per OPD visit, linked to the patient (UHID) and the queue visit.

| Entity | Key fields |
| --- | --- |
| emr_template | key, name, specialty, version, sections[] (key, label, part A/T, required, icon, editor, options_master) |
| emr_template_override | clinic_id, template_key, section_key, label?, part?, shown, required, updated_by, updated_at |
| emr_settings | clinic_id, rail_default (collapsed/open), right_panel_default (paper/history), default_part (A) |
| emr_visit | visit_id, patient_id, doctor_id, template_key, sections_snapshot, visit_type, status (draft/signed), admit_advised, referral, signed_at, signed_by |
| emr_complaint | emr_visit_id, name, duration, order |
| emr_vitals | emr_visit_id, bp_sys, bp_dia, pulse, temp_f, spo2, weight_kg, source (nurse/doctor) |
| emr_exam | emr_visit_id, section_key, item, value (present/absent, normal/abnormal, selected) |
| emr_diagnosis | emr_visit_id, name, icd10, type (provisional/final), is_primary |
| emr_rx | emr_visit_id, generic_name (stored in capitals), frequency, duration, instructions, source (typed/favourite/usual/repeat) |
| emr_tooth | emr_visit_id, fdi_number, status (caries/filled/rct/missing/extract) |
| emr_plan_item | emr_visit_id, fdi_number, procedure, sittings_planned, sittings_done |
| emr_service | emr_visit_id, service_id, price → bill line |
| emr_advice | emr_visit_id, text, follow_up_days |

When a section counts as done:

| Section | Done when |
| --- | --- |
| Chief complaints | at least 1 complaint |
| Vitals | BP (both values) recorded, or temperature and pulse recorded |
| General examination | at least 1 sign marked present or absent |
| Systemic examination | all four systems marked |
| Diagnosis | at least 1 diagnosis |
| Prescription | at least 1 medicine, or "No medicines needed today" ticked |
| Tooth chart | at least 1 tooth marked |
| Treatment plan | at least 1 plan item |
| Procedures & services | at least 1 service ticked |
| Advice & follow-up | a follow-up option chosen or at least 1 advice item |
| Any chip section | at least 1 option selected |

Case paper layout (order follows the ABDM OP consultation record, then clinical order):
1. Header: clinic name, address, phone; doctor name, degree, registration number.
2. Patient band: name, age/sex, date and token; UHID and ABHA status; visit type and "admission advised"; template name.
3. Rows: Chief complaints · Allergies · History · Examination (vitals line, then each exam section, then tooth chart) · Diagnosis (with ICD-10 and provisional/final) · ℞ Medicines (numbered table: generic name in capitals, frequency, duration) · Investigations (incl. radiographs) · Procedures (services, dental plan with sittings, vaccines) · Advice · Follow-up (interval and date).
4. Empty rows show "Not recorded yet" in grey on screen and are left out of the print.
5. Footer: NMC generic-name note, ABHA note, signature line with name and registration number.

## 7. Integrations
The EMR reuses existing modules; it adds no parallel billing, follow-up or admission logic.

| Module | What the EMR sends or reads | Trigger |
| --- | --- | --- |
| Queue | The existing Queue shows an Open EMR button on OPD, IPD and ER cards for the doctor | Always |
| OPD workflow | Opening moves the visit to With doctor; Finish calls the workflow's "move forward" so skip rules and fee timing stay as configured | Open EMR, Finish consultation |
| Vitals | Reads the latest BP, weight and sugar: OPD vitals step, IPD nursing chart, ER triage | Open EMR |
| Billing | Adds/removes service lines at tariff price (OPD bill or IPD running bill); visit type drives consultation and follow-up fee rules | Tick a service, change visit type |
| Follow-ups | Creates a follow-up with due days, doctor and reason (primary diagnosis + "review") | Finish an OPD visit with a follow-up chosen |
| IPD | Admission: opens the existing admission sheet pre-filled from the EMR. Rounds: writes the day's progress note and new orders to the admission | Admit to IPD; Finish an IPD round |
| Emergency | Reads triage level and arrival time; writes the ER outcome back | Open EMR; Finish an ER encounter |
| Patient record | Reads history, allergy, chronic conditions and earlier visits; writes the signed encounter back | Open, Finish |
| Print and WhatsApp | Sends the case paper PDF on the clinic's WhatsApp sender; prints at the configured size | Print, WhatsApp |
| ABDM | Shares the signed OPD visit as an OP consultation record (FHIR) to the patient's ABHA when linked and consented | WhatsApp / ABHA, or auto on sign if enabled |
| Audit log | Every save, sign, visit-type change, admission request and setup change | Always |

## 8. Non-functional, compliance and UI rules
A routine follow-up visit should take a doctor under 90 seconds and fewer than 15 taps from Open EMR to Finish.
Performance and reliability: EMR opens in under 1 s on a clinic connection; a section change re-renders in under 100 ms. Autosave every 30 s and on part switch; a lost connection keeps the draft locally and syncs when back, with a visible "Saved / Saving / Offline" state. Two doctors cannot sign the same visit; a second editor sees the record read-only with who has it open.
Compliance (India): prescriptions use generic names, legible, preferably in capitals; doctor's name, qualification, registration number, signature and date on every case paper (NMC). ABDM: the signed visit maps to the OP consultation record sections (chief complaints, allergies, medical history, investigation advice, medications, procedures, follow-up); share only with consent. DPDP Act: role-based access, audit trail, no patient data in logs or analytics events. A signed record is never edited; corrections are an addendum with reason, user and time.
UI rules (from the mockup): desktop layout = rail | work area | right half (equal halves for work and right); below 1180 px the right half moves under the work area; below 720 px the rail becomes a horizontal strip. Colour carries clinical meaning only: red = required missing, allergy, abnormal; amber = chronic, watch; green = done, normal; always paired with text or an icon. Every control works by keyboard; tap targets at least 32 px (tech lead note: `.claude/rules/angular.md` says 44 px; BA to flag); focus visible; tabs and toggles use proper ARIA states. Labels in English, Marathi and Hindi as in the rest of the console; medicine names stay in English capitals.

## 9. Out of scope, open questions and phases
Release 1 delivers sections 4–7 for the four templates; everything below is decided later.
Out of scope for release 1: handwriting and voice input (Apulki digital notepaper), body map, 3D dental views; Gynaecology, Physiotherapy, Oncology and Surgery templates and their tools (ANC, chemo order sheet, OT booking) from the canvas; multi-visit treatment courses with partial and advance payment per sitting; form builder for clinic-defined forms; drug interaction and dose checking.
Open questions for the product owner (all unticked):
1. Doctor role: a separate role, or a flag on Owner and staff users?
2. Can a doctor edit the template for themselves, or only the Owner for the clinic?
3. Should Emergency also alert the front desk and nurse, beyond moving to the top of the queue?
4. Is a referral letter a separate printable document, or a line on the case paper?
5. Is ABHA sharing manual only, or automatic on sign when the patient has consented?
6. Which drug master feeds favourites and generic names (clinic list, CDSCO list, a vendor)?

Delivery phases (the BRD's own):

| Phase | Scope | Requirements |
| --- | --- | --- |
| 1 | Templates as data, Clinic setup → Doctor EMR, Open EMR from the Queue, EMR shell (header, collapsed rail, parts, finish bar) | FR-01–10, FR-16, FR-19–21 |
| 2 | Section editors, smart pre-fill, live case paper, past visits | FR-11–15 |
| 3 | Integrations: workflow move, billing, follow-ups, admission, print, WhatsApp, autosave | FR-04–05, FR-17–18, section 7 |
| 4 | ABDM share, audit hardening, Marathi/Hindi labels, performance targets | Section 8 |
