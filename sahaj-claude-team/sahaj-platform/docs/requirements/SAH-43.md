# SAH-43 · Doctor EMR programme (SAH-43 to SAH-52): requirement note, starting with the EMR masters
Module: opd (the whole EMR). It reads platform (doctor details), patient, ipd and billing only through their `api` packages or domain events. No new module is created (the module list is fixed in CLAUDE.md).
Mockup screen: Queue (sidebar → Queue) is the entry point the EMR opens from. The Doctor EMR screen and Clinic setup → Doctor EMR are missing from the repo mockup (status per story below). SAH-43 and SAH-44 themselves have no screen (data only); their nearest existing screens are Sahaj admin portal (platform admin view), tabs Templates and master data, and Clinic setup → Specialty template, Case paper and Rx.
Source: Human (product owner), chat, 11 Nov 2026: "Doctor EMR — Business Requirements" (PDF, 15 pages, 11 Nov 2026; the PDF is the source of truth; faithful transcription in `docs/handoffs/SAH-43-input-doctor-emr-brd.md`). Decisions and phases: `docs/decisions/DEC-036-doctor-emr-brd-intake-and-phasing.md` (answered 11 Nov 2026). Stories written now: `docs/stories/SAH-43.md` and `docs/stories/SAH-44.md`.

## Problem
Doctors today have no place in Sahaj to write a consultation. The Queue only has a "Consult done" step (the mockup says "in the product this happens from the Doctor App"), and the clinic can print a case paper only as settings (SAH-7). The BRD asks for a doctor's EMR opened from the Queue for OPD, IPD and Emergency patients, driven by specialty templates (General practice, Dental, Paediatrics, Homeopathy), with a live case paper beside it.

The human decided to start with **master configuration** (SAH-43 EMR masters, SAH-44 specialty templates as data with clinic overrides, SAH-45 the Clinic setup → Doctor EMR page) and to build every EMR screen only after the human saves the EMR mockup in `docs/design/mockup/` and adds screen-map rows. This note covers the whole programme so that the order, the traceability and the blockers are in one place; only SAH-43 and SAH-44 have full stories.

## Who uses it (roles) and in which setting
Roles use Sahaj names: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor).
- **Doctor, visiting doctor, owner (as doctor): consultation room, ward round, ER bay.** Only these write the EMR (BRD section 2). Doctor and visiting doctor: own patients. Owner: all patients. The BRD table lists only "Doctor" and "Owner (doctor)"; visiting doctor, billing, pharmacist and counsellor are not in it (OQ-43-12).
- **Owner: clinic setup.** Only the owner changes templates, sections and screen defaults (OQ-43-2). Other roles see Clinic setup → Doctor EMR read-only (FR-20); the BRD role table says "No" for them, which conflicts (D-6).
- **Nurse: vitals step.** Fills vitals that pre-fill the EMR; reprints the case paper only; never opens the EMR.
- **Front desk: registration and the counter.** Sets the visit type at registration only; reprints the case paper only.
- **Billing and pharmacist:** see the outcomes (bill lines, medicines) in their own screens; no EMR access (OQ-43-12).
- **Setting:** OPD clinic first. IPD rounds and Emergency use the same screen later (SAH-52).

## In scope / Out of scope
In scope (programme, phase 1, with the order below): masters (SAH-43), templates as data and clinic overrides (SAH-44), Clinic setup → Doctor EMR (SAH-45), EMR shell opened from the Queue (SAH-46), section editors and smart pre-fill (SAH-47), prescription and services (SAH-48), dental tooth chart and plan (SAH-49), past visits and live case paper with browser print (SAH-50), draft, autosave, finish and sign (SAH-51), visit type, Admit to IPD, IPD round and Emergency variants (SAH-52).

Out of scope for the programme now (Later, human decision of 11 Nov 2026 and BRD section 9):
- WhatsApp send of the case paper and ABHA/ABDM share (integration gateway and notifications are not phase 1). **Print only.** The ABDM share on sign is Later too.
- BRD phase 4: ABDM share, Marathi and Hindi labels, performance targets, "audit hardening" (see BR3: the audit itself is built from the first story).
- Handwriting and voice input (Apulki digital notepaper), body map, 3D dental views, form builder, drug interaction and dose checking, multi-visit treatment courses with partial and advance payment per sitting.
- Gynaecology, Physiotherapy, Oncology and Surgery templates and their tools (ANC, chemo order sheet, OT booking); other templates added later as data.
- A screen to edit global masters or templates (platform admin portal): global rows change by migration only in phase 1, as in SAH-7.
- Anything not in the BRD or the repo mockup: an open question, not a developer choice.

## Business rules (numbered)
Rules BR4 to BR9 are covered by `docs/stories/SAH-43.md`, BR10 to BR18 by `docs/stories/SAH-44.md`. BR19 to BR28 belong to SAH-45 to SAH-52 and get acceptance criteria when those stories are written.

**Placement and access**
BR1. **The EMR belongs to module `opd`.** It reads patient history, vitals, billing and admission data only through the other modules' `api` packages or events, and adds no parallel billing, follow-up or admission logic (BRD section 7).
BR2. **Only a doctor writes the EMR** (doctor: own patients; owner who holds doctor details: all patients; visiting doctor: see OQ-43-12). Everyone else sees outcomes only: bill lines, follow-up, admission request, the printed case paper. Roles are checked on the server on every request.
BR3. **Audit from the first story.** Every setup change (SAH-44, SAH-45) and, later, every save, sign, visit-type change and admission request writes an audit event with user, time and what changed. For setup values the before and after values are recorded. For clinical content the audit holds ids and keys, not clinical text (D-8, project rule "never log clinical notes").

**Masters (SAH-43)**
BR4. **Quick-option lists come from global, read-only masters:** complaints, diagnoses with ICD-10, medicines (generic names), investigations, advice. A clinic cannot add to or change them in phase 1 (OQ-43-10). They change by migration only, like SAH-7.
BR5. **Reuse what exists.** The diagnosis list, medicine list, clinic favourite medicines (SAH-7), the clinic tariff and the doctor details (SAH-6) are reused, not copied.
BR6. **Ranking rule.** Quick-add items are ordered by how often this doctor used the item, then by how recently. Items this doctor never used follow in the master's own order (OQ-43-9). The ranking is per doctor and per clinic.
BR7. **Free-typed text is never ranked or shared.** Only master items are counted. Text a doctor types freely stays in that patient's record and never becomes a chip for others (patient data could be in it).
BR8. **The doctor master is the existing doctor details** (name, degree, registration number, specialty). The EMR reads it through `platform.api`. Gaps are listed in "Conflicts and gaps".
BR9. **ICD-10 comes from a named, licensed source, and every starter list is reviewed by a clinician before real use** (OQ-43-7, OQ-43-8).

**Templates and settings (SAH-44)**
BR10. **A template is data, not code.** Each section has a key, label, part (Assessment or Treatment), required default, icon, editor type and a reference to its quick options. Four templates now: General practice, Dental, Paediatrics, Homeopathy (BRD section 5, copied into `docs/stories/SAH-44.md`).
BR11. **Clinic overrides are per clinic, per template and per section.** In release 1 an override holds Show and Required only (OQ-43-15). **Global template definitions** have no `tenant_id` and are read-only to the app; **clinic overrides and EMR settings** are tenant data with `tenant_id` and row-level security.
BR12. **A hidden section is never required.** Some sections cannot be hidden (default list in OQ-43-14: Prescription, Remedy and potency, and sections that carry allergy or medical alerts).
BR13. **Reset to template** removes all of the clinic's overrides for that template.
BR14. **Setup changes apply to visits opened after the change.**
BR15. **The template is chosen from the doctor's specialty, then the clinic's specialty, then General practice** (OQ-43-13). The doctor can switch it for one visit while the visit is a draft (OQ-43-18).
BR16. **Snapshot rule.** A visit stores the template key, the template version and the section list (after overrides) it was opened with. Later setup or template changes never alter a visit, signed or draft. Required-done is judged against the snapshot.
BR17. **Clinic EMR settings:** section panel Collapsed or Open (default Collapsed), right panel first Case paper or Past visits (default Case paper), default part Assessment (stored, no control: OQ-43-16).
BR18. **Only the owner changes templates and settings.** Others read. Changes are audited, use optimistic locking, and tenant comes from the logged-in user, never from the request body.

**EMR (SAH-45 to SAH-52; acceptance criteria written later)**
BR19. **Completion rules per section** (BRD section 6) decide Done. Finish stays blocked until every required section is done; the doctor is taken to the first missing one (SAH-47, SAH-51).
BR20. **A signed record is never edited.** A correction is an addendum with reason, user and time (SAH-51).
BR21. **Two doctors cannot sign the same visit.** A second editor sees the record read-only with who has it open (SAH-51).
BR22. **Medicines use generic names, stored in capitals** (SAH-48; D-5).
BR23. **Case paper carries the doctor's name, degree, registration number, signature line and date** (NMC rule as written in the BRD) and prints at the clinic's A4 or A5 setting; empty rows are left out of the print (SAH-50).
BR24. **Print only now.** WhatsApp and ABHA buttons are Later (OQ-43-29 for how they show).
BR25. **Patient safety:** an allergy ticked in History shows in the header at once; IPD medicines go through the existing IPD safety path (OQ-43-24).
BR26. **Privacy:** no patient data in logs or analytics events; role-based access; audit trail (DPDP rule in the BRD).
BR27. **Visit type, Admit to IPD, IPD round and Emergency variants** reuse the existing Queue, billing, IPD and Emergency logic (SAH-52).
BR28. **Every tenant table has `tenant_id` and row-level security;** the app sets `app.tenant_id` from the logged-in user. Each story states which of its data is global and which is tenant data.

## Patient-safety and privacy points
- **Patient data is not involved in SAH-43 and SAH-44.** Masters and templates hold no patient data; usage counters hold master-item ids, doctor, clinic, count and last-used time only.
- **Free-typed text** (complaints, advice) can contain patient details. It is never ranked, never turned into a shared chip and never logged (BR7).
- **Starter clinical lists are not clinical advice.** The ICD-10, complaint, investigation and advice lists in the repo are starters (as in SAH-7) and need a clinician's review before real use. Medicine and ICD-10 sources need the product owner (OQ-43-6, OQ-43-7, OQ-43-8).
- **Hiding sections is a safety decision.** If an owner can hide Prescription or allergy capture, a doctor may not see the allergy. Default: those sections cannot be hidden (OQ-43-14). Paediatrics and Homeopathy templates in the BRD have no allergy section at all; allergy then comes only from the header (patient record). Needs the human.
- **"No known allergy" vs "not asked".** The patient record holds allergy and chronic condition as one optional free text each (SAH-8). An empty value must not be shown as "No known allergy" without a decision (OQ-43-22, SAH-47).
- **Required chip sections need a "None" choice.** "Any chip section is done when at least 1 option is selected" would force a doctor to tick something when the patient has no alerts (for example the required Medical alerts in Dental). Default: each required chip list has a "None / nothing abnormal" option (OQ-43-19).
- **Out-of-range colours use adult limits** (BP 140/90, pulse 100, temperature 100 °F, SpO2 94). In Paediatrics they could flag normal children or miss sick ones (OQ-43-23, SAH-47).
- **IPD prescribing bypass.** The BRD sends IPD medicines straight to nursing and pharmacy, and puts drug checks out of scope, while the IPD medicine orders (SAH-31) already have allergy, duplicate and interaction checks and a pharmacist check. The EMR must not create a second, unchecked path (OQ-43-24, SAH-52).
- **Follow-up reason shows a diagnosis.** BRD section 7 sets the follow-up reason to "primary diagnosis + review". The Follow-ups list is read by any clinic role (SAH-14), so the front desk would see diagnoses. Needs a decision (OQ-43-32, SAH-51).
- **Offline draft** keeps clinical text on the device. Who may see it on a shared clinic computer is a security question for SAH-51 (OQ-43-28).
- Audit before/after values: for clinical text they would copy clinical notes into the audit table (D-8).
- Never log names, mobile numbers, ABHA numbers or clinical notes (unchanged project rule).

## Plan or entitlement
- The EMR is part of OPD. OPD is always on (SAH-19: Common and OPD always in use), so no separate plan flag is assumed (OQ-43-21; the mockup pricing lists "Doctor App" among Free plan features).
- Admit to IPD uses the existing IPD entitlement (SAH-19, state on or trial). When IPD is off the BRD offers "Advise admission + referral line" and, for the owner, "Switch on IPD" (SAH-52).
- Roles and permissions: new permissions for "write EMR" and "change EMR setup" go through the existing role and permission grid (SAH-6); the architect decides the names.

## What already exists in the repo (reuse, do not rebuild)
Checked 11 Nov 2026 in `backend/src/main/resources/db/migration` and `docs/stories`.
| Need | Exists | Built in | Gap for the EMR |
| --- | --- | --- | --- |
| Doctor master | `platform.doctor_profile` per doctor or visiting doctor membership: specialty (free text, optional), degree, registration number (both optional), fee, mobile; name from the user. `DoctorInput` / `DoctorDetails` in Team and access | SAH-4, SAH-6 | Specialty is free text with no link to a template key; degree and registration number optional; no rule for an owner who is not a doctor |
| Specialties | `platform.specialty` (8 rows: gp, dental, paeds, homeo, ayur, physio, derma, gynae), `specialty_template` (features and starter services, phase 1 or 2); clinic specialty limited to gp, dental, paeds, homeo | SAH-7 | These are not EMR templates (no sections) |
| Diagnoses | `opd.diagnosis`: global, ICD-10 code, name, specialty hint, about 50 starter rows, "not clinically reviewed" | SAH-7 | Source and licence not named; no free-typed diagnosis handling |
| Medicines | `opd.medicine`: global, generic name with strength, form, schedule, usual dose, starter rows | SAH-7 | Source not named; no per-doctor ranking |
| Favourites | `opd.medicine_favourite`: per clinic, owner marks | SAH-7 | BRD says "favourites per specialty" (D-9) |
| Case paper settings | `opd.case_paper_settings`: A5/A4, second language, pre-printed letterhead, capitals, nine section switches (Rx always on) | SAH-7 | Overlap with EMR section show/hide (D-4) |
| Services and fees | `opd.service` (clinic tariff), fee settings, follow-up window | SAH-7, SAH-18 | "Tariff for the doctor" |
| Queue and visit | `opd.visit`, stages Waiting, Vitals, With doctor, Done; "Consult done" button | SAH-9 | No Open EMR button, no doctor queue view |
| Workflow | 8 steps with owners and on/off, draft then publish | SAH-12 | "Move forward" must keep skip rules |
| Billing | bill per visit, items, payments | SAH-10 | Service lines from the EMR |
| Follow-ups | created when a visit leaves With doctor, clinic window days, default reason | SAH-14 | Doctor chooses 3, 5, 7, 15 days, 1 month |
| IPD | admission sheet and deposits (SAH-23), running bill (SAH-25), nursing vitals (SAH-30), medicine orders with safety checks and pharmacist check (SAH-31), MAR (SAH-32), emergency queue and triage (SAH-28), ward scope and break-glass (SAH-34) | various | See SAH-52 |
| Module entitlement | `platform.module_entitlement` | SAH-19 | None |
| Audit | `platform.audit_event` through `platform.api.AuditLog` | SAH-2 | None |
Not in the repo: complaints master, investigations master, advice master, template sections, clinic template overrides, EMR settings, usage ranking, any EMR visit data, a patient allergy or chronic list as structured data.

## Story map (the split of DEC-036 is confirmed; changes are marked)
Next free id was SAH-43; SAH-43 to SAH-52 are confirmed as one block. Each story is one branch and one sprint. SAH-47 and SAH-51 are large and will probably be split in two when written (adding ids at the end of the block, not renumbering).

| Story | Title | Module | Phase | Mockup screen status | Depends on | Blockers |
| --- | --- | --- | --- | --- | --- | --- |
| SAH-43 | EMR masters: complaints, diagnoses (ICD-10), medicines, investigations, advice; ranking per doctor; doctor master reuse | opd (doctor read via platform.api) | 1 | none (data only; nearest: Sahaj admin portal master data) | none | OQ-43-7 and OQ-43-8 for reviewed content (starter lists can proceed) |
| SAH-44 | Specialty templates as data, clinic overrides, EMR settings, template choice and section snapshot | opd | 1 | none (data only; nearest: Clinic setup → Specialty template) | SAH-43 | OQ-43-13, OQ-43-14, OQ-43-15, OQ-43-16, OQ-43-17, OQ-43-18 (defaults exist) |
| SAH-45 | Clinic setup → Doctor EMR page (FR-19 to FR-21 screen) | opd | 1 | missing (not in repo mockup, no screen-map row) | SAH-44 | EMR mockup and screen-map row from the human; D-1, D-6 |
| SAH-46 | Open EMR from the Queue and the EMR shell (OPD) | opd | 1 | missing (Queue is in repo; the Open EMR button and the EMR screen are not) | SAH-44, SAH-45 | EMR mockup; D-2, OQ-43-31 |
| SAH-47 | General editors, smart pre-fill, completion rules | opd | 1 | missing | SAH-46 | OQ-43-19, OQ-43-22, OQ-43-23 |
| SAH-48 | Prescription, procedures and services | opd (billing through api) | 1 | missing | SAH-47 | OQ-43-6 drug master (product owner) |
| SAH-49 | Dental tooth chart and treatment plan | opd | 1 | missing | SAH-47 | Clinician input on suggestion rules |
| SAH-50 | Past visits and live case paper, browser print | opd | 1 (print only) | nearest in repo: Clinic setup → Case paper and Rx print preview; the live case paper beside the EMR is missing | SAH-47, SAH-48 | D-5, D-6, D-7, OQ-43-29, OQ-43-30 |
| SAH-51 | Save draft, autosave, Finish and sign (OPD), follow-up, one signer, addendum | opd | 1 | missing | SAH-46 to SAH-50 | OQ-43-28, OQ-43-32 |
| SAH-52 | Visit type, Admit to IPD, IPD round and Emergency variants | opd (reads ipd, billing through api) | 1 | missing | SAH-51; SAH-23, SAH-28, SAH-30, SAH-31 | OQ-43-24 to OQ-43-27 |
| Later | WhatsApp and ABHA/ABDM share, Marathi and Hindi labels, performance targets, other templates, form builder, drug checks, handwriting and voice | | Later | | integration gateway, notifications, documents service | |

Changes to the DEC-036 split and why:
1. **Audit is not postponed to phase 4.** The BRD's phase 4 says "audit hardening", but section 2 says every save, sign, visit-type change and admission request is audited from the start, and CLAUDE.md requires audit for clinical changes. Basic audit is in every story; only a later review of it is Later.
2. **FR-16 is split:** the bar layout is in SAH-46, its behaviour (Finish, Save draft) in SAH-51, as in DEC-036.
3. **FR-04 (visit type)** is in SAH-52 as in DEC-036. SAH-46 shows the type set at registration, read-only, so the shell does not wait for SAH-52.
4. **Print is in SAH-50** (browser print at A4/A5). The WhatsApp and ABHA buttons are Later.
5. **SAH-45 stays in master configuration** but is blocked until the human saves the EMR mockup.

## Traceability: BRD requirement to story
Phase "1" is the product phase 1 (build now); "Later" is not built now. "Mockup" is the status in the repo mockup.

### Functional requirements FR-02 to FR-21 (the BRD has no FR-01)
| FR | What | Story | Phase | Mockup |
| --- | --- | --- | --- | --- |
| FR-02 | Open EMR for any encounter from a queue card; opens as Consultation, Daily progress note or Emergency assessment; OPD visit moves to With doctor; opens on the configured part and tab; back arrow keeps filter and draft | SAH-46 (OPD), SAH-52 (IPD, ER) | 1 | missing |
| FR-03 | Patient header, tags, allergy tick shows at once | SAH-46 (header), SAH-47 (live allergy tick) | 1 | missing |
| FR-04 | Mark visit as New, Follow-up, Revisit, Emergency; follow-up fee rule | SAH-52 (SAH-46 shows it read-only) | 1 | missing |
| FR-05 | Admit to IPD, or Advise admission + referral line when IPD is off | SAH-52 | 1 | missing (admission sheet exists, SAH-23) |
| FR-06 | Rail collapsed by default, status dots | SAH-46; default from SAH-44-3 | 1 | missing |
| FR-07 | Expanded rail, template selector, progress bar, Edit template link | SAH-46; switch rule SAH-44-4; link to SAH-45 | 1 | missing |
| FR-08 | Jump to a section with highlight | SAH-46 | 1 | missing |
| FR-09 | Parts 1 Assessment and 2 Treatment, done counts, Continue to Treatment | SAH-46 | 1 | missing |
| FR-10 | Section card: icon, name, required star, status tag | SAH-46 | 1 | missing |
| FR-11 | Editors: Complaints, Vitals, Signs, Systems, Diagnosis, Advice, Chip list | SAH-47 | 1 | missing |
| FR-11 | Editors: Prescription, Services | SAH-48 | 1 | missing |
| FR-11 | Editors: Tooth chart, Treatment plan | SAH-49 | 1 | missing |
| FR-11 | Quick-add chips ranked by use (masters and ranking behind them) | SAH-43 | 1 | none (data only) |
| FR-12 | Smart pre-fill (history, vitals, child weight estimate) | SAH-47 | 1 | missing |
| FR-13 | Right half tabs Past visits and Case paper (tab shell and default from settings) | SAH-46 shell, SAH-50 content | 1 | missing |
| FR-14 | Past visits, Copy medicines to today | SAH-50 (SAH-48 for the prescription side) | 1 | missing |
| FR-15 | Live case paper, A4/A5, print; WhatsApp and ABHA buttons | SAH-50 (print); WhatsApp and ABHA Later | 1 / Later | nearest: Case paper and Rx preview |
| FR-16 | Sticky action bar: Still needed, Save draft, Finish | SAH-46 (layout), SAH-51 (behaviour) | 1 | missing |
| FR-17 | Finish consultation: block, sign, next step by encounter type | SAH-51 (OPD), SAH-52 (IPD, ER) | 1 | missing |
| FR-18 | Save draft, autosave every 30 s | SAH-51 | 1 | missing |
| FR-19 | Setup page "Doctor EMR" under Common to all modules after Specialty template, searchable | SAH-45 | 1 | missing |
| FR-20 | Sections per template, Show and Required switches, reset, count, Owner only, audited | Data and rules SAH-44-2; screen SAH-45 | 1 | missing |
| FR-21 | Screen defaults: section panel, right panel first | Data SAH-44-3; screen SAH-45 | 1 | missing |

### BRD sections 5 to 9
| BRD item | Story | Phase |
| --- | --- | --- |
| Section 5: four templates and their sections | SAH-44-1 | 1 |
| Section 5: template chosen from doctor specialty, falls back to clinic's specialty, switch for one visit | SAH-44-4 (switch control in SAH-46) | 1 |
| Section 5: template is data, new specialties added as data later | SAH-44-1 (adding is Later) | 1 / Later |
| Section 5: clinic overrides per template and section; reset clears them | SAH-44-2 | 1 |
| Section 5: quick-option lists from masters; ranking by doctor use then recency | SAH-43-1, SAH-43-2 | 1 |
| Section 5: visit stores template key and section list (snapshot) | SAH-44-4 (stored with the visit in SAH-46) | 1 |
| Section 6: entity emr_template | SAH-44-1 | 1 |
| Section 6: emr_template_override | SAH-44-2 | 1 |
| Section 6: emr_settings | SAH-44-3 | 1 |
| Section 6: emr_visit and its child entities (complaint, vitals, exam, diagnosis, rx, tooth, plan item, service, advice) | SAH-46 (visit), SAH-47 to SAH-49 (children), SAH-51 (sign) | 1 |
| Section 6: completion rules per section | SAH-47 (general), SAH-48 (Prescription, Services), SAH-49 (Tooth chart, Treatment plan) | 1 |
| Section 6: case paper layout and order, "Not recorded yet", footer notes | SAH-50 | 1 |
| Section 6: "One EMR record per OPD visit" versus IPD day notes and ER encounters | SAH-52 (OQ-43-27) | 1 |
| Section 7: Queue, OPD workflow | SAH-46, SAH-51 | 1 |
| Section 7: Vitals read (OPD vitals, IPD nursing chart, ER triage) | SAH-47 (OPD), SAH-52 (IPD, ER) | 1 |
| Section 7: Billing (service lines; visit type fee rules) | SAH-48, SAH-52 | 1 |
| Section 7: Follow-ups | SAH-51 | 1 |
| Section 7: IPD admission and rounds; Emergency | SAH-52 | 1 |
| Section 7: Patient record (read history, allergy, chronic; write signed encounter) | SAH-46, SAH-47, SAH-51 (OQ-43-22) | 1 |
| Section 7: Print | SAH-50 | 1 |
| Section 7: WhatsApp, ABDM (FHIR share) | none | Later |
| Section 7: Audit log | every story (BR3) | 1 |
| Section 8: under 90 seconds and fewer than 15 taps for a routine follow-up | design target for SAH-46 to SAH-51; measured in UAT | 1 (target), measured Later |
| Section 8: opens in under 1 s, re-render under 100 ms | recorded as target; not a gate now | Later (BRD phase 4) |
| Section 8: autosave 30 s and on part switch, offline draft, Saved/Saving/Offline state | SAH-51 | 1 |
| Section 8: two doctors cannot sign the same visit | SAH-51 | 1 |
| Section 8: generic names, capitals | SAH-48 | 1 |
| Section 8: doctor name, qualification, registration number, signature, date on every case paper | SAH-43-3 (data), SAH-50 (print) | 1 |
| Section 8: ABDM mapping and consent | order of rows only in SAH-50; sharing Later | Later |
| Section 8: DPDP: role-based access, audit, no patient data in logs | every story | 1 |
| Section 8: signed record never edited, addendum with reason | SAH-51 | 1 |
| Section 8: UI rules (breakpoints 1180 and 720 px, colour meaning, keyboard, ARIA, 44 px) | SAH-45 to SAH-52 (C-1 on 32 vs 44 px) | 1 |
| Section 8: labels in English, Marathi, Hindi | English only now | Later (BRD phase 4) |
| Section 9: out of scope list | none | Later |
| Section 9: open questions 1 to 6 | OQ-43-1 to OQ-43-6 | |

## Outline of SAH-45 to SAH-52 (no full stories yet)
Each of these needs the EMR mockup in `docs/design/mockup/` and screen-map rows first, except where noted.

**SAH-45 · Clinic setup → Doctor EMR page.** FR-19, FR-20, FR-21. Depends on: SAH-44. Blockers: the mockup and screen-map row; D-1, D-6 (other roles read-only or no access), OQ-43-2, OQ-43-14, OQ-43-15, OQ-43-16. Shows template switcher (the clinic's own marked "yours"), table grouped by Assessment and Treatment with quick-option count, Show and Required switches (Required disabled when hidden), Reset to template, "n sections on · m required", section panel and right-panel defaults, standard page header, search in settings. The repo's Clinic setup lists "Common to all modules" with Specialty template; the new entry goes after it (consistent with the repo).

**SAH-46 · Open EMR from the Queue and the EMR shell (OPD).** FR-02 (OPD), FR-03, FR-06 to FR-10, FR-13 tabs shell, FR-16 layout. Depends on: SAH-44, SAH-45. Blockers: EMR mockup; D-2 (the Queue shows no doctor view or Open EMR button); OQ-43-12 (who can open); OQ-43-31 (44 px). Opening a Waiting or Vitals visit moves it to With doctor through the existing workflow (SAH-9, SAH-12). Takes the template snapshot (SAH-44-4). Back arrow keeps the queue filter and the draft. Doctor sees own queue by default.

**SAH-47 · General editors, smart pre-fill and completion rules.** FR-11 (Complaints, Vitals steppers, Signs, Systems, Diagnosis, Advice, Chip list), FR-12, FR-03 live allergy, section 6 completion rules. Depends on: SAH-46, SAH-43. Blockers: OQ-43-19 (None option), OQ-43-22 (structured allergy and chronic data in the patient module), OQ-43-23 (child limits, weight estimate). Likely split in two.

**SAH-48 · Prescription, procedures and services.** FR-11 Prescription and Services, FR-14 copy medicines, section 6 rules, section 8 generic names. Depends on: SAH-47. Blockers: **OQ-43-6 drug master (product owner)**; D-5 (capitals); weight-based dose for "Prescription (by weight)" is not defined in the BRD; how frequency (1-0-1, SOS) and duration are parsed; whether Remedy and potency uses the same editor; clinic tariff "for the doctor" (the tariff in SAH-7 is per clinic). Service lines go to the existing OPD bill (SAH-10) through the billing `api`.

**SAH-49 · Dental tooth chart and treatment plan.** FR-11 Tooth chart (FDI, cycle of states), Treatment plan and sittings. Depends on: SAH-47. Blockers: clinician confirms the suggestion rules (caries to filling, RCT to root canal 3 sittings, missing to implant or bridge); 32 px versus 44 px for 32 tooth targets (C-1); sittings paid per visit is out of scope.

**SAH-50 · Past visits and live case paper with browser print.** FR-13, FR-14, FR-15, case paper layout. Depends on: SAH-47, SAH-48. Blockers: D-5, D-6 (layout differences from the mockup print preview), D-7 (Marathi and Hindi), OQ-43-29 (how WhatsApp and ABHA buttons show), OQ-43-30. Reuses A4/A5 from Case paper and Rx (SAH-7). Past visits shows up to 8 earlier visits, newest first; reads earlier IPD visits only through the ipd `api` and respects ward scope (SAH-34).

**SAH-51 · Save draft, autosave, Finish and sign (OPD).** FR-16 behaviour, FR-17 (OPD), FR-18, section 8 (one signer, addendum, Saved/Saving/Offline). Depends on: SAH-46 to SAH-50. Blockers: OQ-43-28 (offline draft on the device), OQ-43-32 (follow-up reason and choices), OQ-43-11 (registration number missing at signing), D-8 (audit content). Calls the workflow's "move forward" so skip rules and fee timing stay; creates the follow-up through SAH-14's logic; counts used items for the ranking (SAH-43-2).

**SAH-52 · Visit type, Admit to IPD, IPD round and Emergency variants.** FR-04, FR-05, FR-17 for IPD and ER, FR-02 variants, vitals from the IPD nursing chart and ER triage. Depends on: SAH-51; SAH-23, SAH-28, SAH-30, SAH-31. Blockers: OQ-43-24 (IPD medicines through the SAH-31 path), OQ-43-25 (two meanings of "Emergency"), OQ-43-26 (Revisit; who sets type at registration), OQ-43-27 (record model for IPD and ER), OQ-43-3, OQ-43-4. Investigations and the "pharmacy and lab" receivers are not built (Pharmacy and Lab are Later modules).

## Differences between the BRD and the repo mockup (each one needs human sign-off)
The BRD says "where the mockup and this BRD differ, this BRD wins". CLAUDE.md rule 1 says every difference goes to the human, so the BRD wins only after the human signs each item. The stories name these as SO-43-n and SO-44-n; later stories will add theirs.
- **D-1 · No Doctor EMR screen and no Clinic setup → Doctor EMR page in the repo mockup** (`docs/design/mockup/sahaj-mockup.html` last changed 31 Oct 2026; no "Doctor desk"; the screen map has no row). The BRD cites a "Sahaj Clinic Console" mockup and an "EMR Configuration by Department" canvas that are not in the repo. All screen stories wait. (SAH-45 to SAH-52)
- **D-2 · Queue.** The repo Queue has "Consult done" and the text "Doctor App is the consultation step"; the demo role switcher has only front desk, nurse and owner, so there is no doctor queue view, no "own patients" default with a filter and no Open EMR button on a card. (SAH-46)
- **D-3 · Stylus and voice.** The repo mockup says "Doctor App: stylus or voice fills the case paper", lists "Doctor App with stylus and voice" in the Free plan and has a voice feature flag. The BRD puts handwriting and voice out of scope for release 1. (programme)
- **D-4 · Two places to show or hide sections.** Clinic setup → Case paper and Rx has nine section switches (complaints, vitals, history, exam, diagnosis, investigations, medicines, advice, next visit; "switched-off sections are hidden in the Doctor App too"), and Medicines cannot be switched off. The BRD adds Show and Required switches per template section (FR-20) and lets any section be hidden. How the two relate is not defined. (SAH-44, SAH-45, SAH-50)
- **D-5 · Capitals.** The mockup has a switch "Medicine names in CAPITALS" (default off). The BRD says generic names are always in capitals and stored in capitals. (SAH-48, SAH-50)
- **D-6 · Who can open Clinic setup → Doctor EMR.** The BRD role table gives only the owner "Yes" (others "No"); FR-20 says other roles see it read-only; the repo mockup shows setup pages read-only to non-owners. Default taken: read-only for others. (SAH-44, SAH-45)
- **D-7 · Case paper language.** The mockup and SAH-7 let the clinic print the case paper with + Marathi or + Hindi labels. The BRD puts Marathi and Hindi labels in Later (phase 4). (SAH-50)
- **D-8 · Audit content.** The BRD wants before and after values for every save. The repo's audit convention is ids and keys only, and the rule is never to log clinical notes. Default: setup values are recorded in full, clinical content by ids and keys, and the clinical record keeps its own history through addenda. (all)
- **D-9 · Favourites.** The BRD says favourites per specialty. The built SAH-7 favourites are per clinic (owner marks them) and the mockup's template card says "Rx favourites" per template. (SAH-43, SAH-48)
- **D-10 · Case paper contents.** The mockup print preview shows the patient's mobile number, a "Scan at next visit" QR, a follow-up fee line, a formulation prefix and dose instructions such as "after food". The BRD layout adds UHID, ABHA status, template name, an NMC note, an ABHA note and "Not recorded yet", and does not list the mobile number, the QR or the fee line. (SAH-50)
- **D-11 · Specialty names.** The mockup uses General practice, Dental, Paediatrics, Homeopathy (and Ayurveda, Physiotherapy, Dermatology, Gynaecology as "Phase 2"; the Users master also lists ENT). The BRD uses doctor specialties General Physician, Dental Surgeon, Paediatrician, Homeopath, and later templates Gynaecology, Physiotherapy, Oncology, Surgery. Oncology and Surgery are not in the mockup; Ayurveda, Dermatology and ENT are not in the BRD. (SAH-44)
- **D-12 · Template per doctor.** The mockup's template is per clinic ("a multi-specialty clinic can add a template per doctor later"). The BRD chooses it per doctor with the clinic's specialty as fallback and a per-visit switch. (SAH-44)
- **D-13 · What a template is.** The mockup's template loads workflow, case paper, price list, Rx favourites and reminders. The BRD's template is a set of EMR sections. The mockup admin portal shows template versions with Draft and Publish and an "Update available" card for clinics; the BRD has versions but no publish flow. Phase 1 changes templates by migration only. (SAH-44)
- **D-14 · Masters.** The mockup admin portal masters are Diagnosis, Medicine, Formulation, Dosage, Route, Investigation, Procedure and Allergy, with organisation-level rows (a clinic adds its own), Marathi and Hindi local terms and a "long-term condition" flag. The BRD needs complaints and advice masters (not in the mockup) and no clinic-added rows or local terms now. (SAH-43)
- **D-15 · Roles.** The BRD says the mockup has no Doctor role and shows the EMR to the owner only. The repo: platform roles include doctor, visiting doctor, billing, pharmacist and counsellor; Team and access shows a "Visiting doctor" column; IPD Roles and access lists Doctor and Visiting doctor; the demo role switcher has front desk, nurse and owner only. (SAH-43, programme)
- **D-16 · Emergency.** The BRD moves an emergency patient into an Emergency group at the top of the same OPD queue and says there is no separate ER list. The repo has a separate IPD → Emergency triage queue (SAH-28). (SAH-52)
- **D-17 · Visit types.** The BRD has New, Follow-up, Revisit, Emergency. The repo computes New or Follow-up from the follow-up window (SAH-9); Revisit is not defined anywhere. (SAH-52)

## Conflicts and gaps (BRD, repo, CLAUDE.md and project rules)
- **C-1 · Tap target 32 px (BRD) versus 44 px (`.claude/rules/angular.md`, used in SAH-42).** Default: 44 px everywhere, as the project rule says. A 32 px exception for dense controls (rail dots, tooth cells, steppers) needs the human's sign-off (OQ-43-31).
- **C-2 · "BRD wins over the mockup" versus CLAUDE.md rule 1.** Handled by D-1 to D-17: the human signs each.
- **C-3 · Doctor role.** The BRD's question 1 and its claim that the mockup has no Doctor role are mostly answered by what is built (D-15): separate roles DOCTOR and VISITING_DOCTOR exist in the platform and an owner can hold doctor details (OQ-43-1, OQ-43-12).
- **C-4 · Name collision with the IPD "EMR sheet".** The screen map row "EMR sheet (card → EMR)" is the IPD medicine-orders sheet with allergy, duplicate and interaction checks. In the playbook it is SAH-26; in the story files it is **SAH-31** (SAH-26 is Bed transfer). The Doctor EMR must use a different name in labels and docs (suggestion: "Doctor EMR" or "Consultation"), and must not bypass SAH-31's checks (OQ-43-24).
- **C-5 · Case paper settings (SAH-7) versus the EMR case paper.** SAH-7 already has A4/A5, language, letterhead, capitals and nine section switches, and a print preview. The EMR case paper (SAH-50) reuses size, letterhead and the print rules and must not create a second set (D-4, D-5, D-7, D-10).
- **C-6 · Overlap with built stories.** Queue (SAH-9: add the Open EMR button, stage move), OPD workflow (SAH-12: "move forward" and skip rules), Follow-ups (SAH-14: today it creates one follow-up with a default reason and the clinic window; the BRD lets the doctor pick 3, 5, 7, 15 days or 1 month; SAH-14 lists "doctor choosing a custom next-visit date" as out of scope, so the EMR extends it), Today (SAH-15: day summary per role; a doctor's counts are not in the BRD), Billing counter (SAH-10: service lines), Admission (SAH-23), Running bill (SAH-25), Emergency (SAH-28), Nursing vitals (SAH-30), Team and access (SAH-6), Clinic masters (SAH-7). The playbook numbers for these stories are out of date; this note uses the story files.
- **C-7 · "ER module".** FR-17 says "the ER module acts on it". There is no ER module; Emergency is in `ipd` (SAH-28). Pharmacy and Lab (also named in FR-17) are Later modules, so IPD investigations have no receiver in phase 1.
- **C-8 · Entity names and "clinic_id".** The BRD's working names (emr_template, clinic_id) are not binding: the platform uses `tenant_id`, one schema per module and its own table naming. The db-engineer decides.
- **C-9 · "One EMR record per OPD visit"** (section 6) conflicts with FR-02 (the same EMR for IPD rounds and ER). OQ-43-27.
- **C-10 · FR numbering.** There is no FR-01 in the PDF; the BRD delivery table cites FR-01 to FR-10.
- **C-11 · Template editors and label/part overrides.** The BRD's override record has label and part, but FR-20 only has Show and Required. OQ-43-15. The default part is stored (emr_settings) but FR-21 has no control for it. OQ-43-16.
- **C-12 · Doctor master gaps.** Specialty is free text with no template link; degree and registration number are optional while the case paper must print them; an owner who is not a doctor has no doctor details; visiting doctor is not in the BRD. See `docs/stories/SAH-43.md` story 3.
- **C-13 · Allergy and chronic data.** The patient module holds them as one optional free text each (SAH-8), not as ticks; the BRD needs ticks, pre-fill and a write-back. A patient-module change is needed (OQ-43-22).
- **C-14 · "Tariff for the doctor".** The BRD shows the clinic tariff "for the doctor"; the built tariff (SAH-7) is per clinic. OQ-43-33 (SAH-48).
- **C-15 · Paediatrics and Homeopathy have no allergy or history section** in the BRD table (OQ-43-14).
- **C-16 · Required chip sections cannot be completed with no finding** (OQ-43-19).
- **C-17 · Specialty list of the clinic is limited to the four template specialties** by a database check on the clinic profile (gp, dental, paeds, homeo); consistent with the four templates, and a new template later needs a data change there too.

## Open questions for the human
Defaults are used if the human gives no answer, except where marked "needs an answer".

**BRD questions (defaults from DEC-036, 11 Nov 2026)**
- **OQ-43-1 · Doctor role: separate role or a flag?** Answered by what is built: separate roles doctor and visiting doctor exist, and an owner can hold doctor details. **Default: keep as built.** Please confirm. (programme)
- **OQ-43-2 · Can a doctor edit the template for themselves?** **Default: owner only** (FR-20). (SAH-44, SAH-45)
- **OQ-43-3 · Should Emergency also alert the front desk and nurse beyond the queue position?** **Default: no.** (SAH-52)
- **OQ-43-4 · Referral: a separate printable letter or a line on the case paper?** **Default: a line on the case paper** (FR-05). (SAH-50, SAH-52)
- **OQ-43-5 · ABHA sharing manual or automatic on sign?** **Default: manual, and Later anyway** (needs the integration gateway). (Later)
- **OQ-43-6 · Which drug master feeds favourites and generic names (clinic list, CDSCO list, a vendor)?** **Needs the product owner. Blocks SAH-48 only.** SAH-43 keeps the SAH-7 starter list and does not extend it.

**Questions raised by the business analyst: needed for SAH-43 and SAH-44**
- **OQ-43-7 · ICD-10 source.** Which licensed source feeds the diagnosis master (for example the WHO ICD-10 release, an Indian government release, or a vendor), who may redistribute it inside the product, and which version? Also, should diagnoses later carry SNOMED CT codes for ABDM (Later)? **Needs an answer before real clinic use; the SAH-7 starter list is used until then.** I do not state any licence terms; the human or the product owner must confirm them.
- **OQ-43-8 · Who writes and reviews the clinical content?** Complaint lists, investigations, advice, section chip lists (history, alerts, local examination, development, vaccines, case history, mentals and generals) and diagnosis lists per specialty need a clinician. **Default: a small starter list per master, marked "not clinically reviewed", as in SAH-7 (human decision of 1 Nov 2026); a named clinician reviews before real use.**
- **OQ-43-9 · Ranking details.** What counts as a "use": **default: an item on a signed visit counts once for that visit; drafts and addenda do not count.** Order of never-used items: **default: the master's own order (name A to Z with the specialty hint first).** Free-typed text is not counted (BR7).
- **OQ-43-10 · Can a clinic add its own complaint, diagnosis, advice or investigation items?** **Default: no in release 1** (global read-only; favourite medicines and services stay as built). The mockup masters have organisation-level rows (D-14).
- **OQ-43-11 · Doctor without a registration number or degree.** The case paper must show them (NMC rule as written in the BRD), but both are optional in Team and access today. **Default: do not block opening or signing; the case paper prints "—" as the mockup does and Team and access already highlights the missing field.** Does the human want signing blocked instead? (SAH-43-3, SAH-50, SAH-51)
- **OQ-43-12 · Who may write the EMR?** The BRD table has Doctor and Owner (doctor). **Default: doctor and visiting doctor (own patients) and an owner who has doctor details (all patients). An owner without doctor details, billing, pharmacist, counsellor, nurse and front desk cannot.** (SAH-43-3, SAH-46)
- **OQ-43-13 · How a doctor's specialty maps to a template.** The specialty is free text (up to 120 characters). **Default: a fixed, case-insensitive list of names (the BRD names and the mockup names) maps to the four templates; anything else uses the clinic's specialty, then General practice; no change to the Team and access screen.** The alternative (a pick-list on Team and access) changes a built screen and needs the human. (SAH-44-4)
- **OQ-43-14 · Which sections may the owner hide?** The BRD allows hiding any section; the mockup does not allow hiding Medicines. **Default: Prescription (and Remedy and potency), History and allergies, and Medical alerts cannot be hidden; all other sections can.** Paediatrics and Homeopathy have no allergy section in the BRD: should they get one? (SAH-44-2, SAH-45)
- **OQ-43-15 · Label and part overrides.** The BRD stores them but FR-20 has no control. **Default: not editable in release 1; only Show and Required.** (SAH-44-2)
- **OQ-43-16 · "Default part" setting.** FR-02 says the EMR opens on the part set in Clinic setup; FR-21 has no such control. **Default: stored as Assessment, no control, not changeable in release 1.** (SAH-44-3)
- **OQ-43-17 · A new template version.** **Default: overrides are kept by section key; sections added by a new version appear with the template's defaults; override keys that no longer exist are ignored; visits keep their snapshot; no "Update available" card (D-13).** (SAH-44-1, SAH-44-2)
- **OQ-43-18 · Switching the template for one visit after data is entered.** **Default: the doctor may switch while the visit is a draft; no entered data is deleted; sections not in the new template are kept in the record but not shown, and a switch is audited.** (SAH-44-4, SAH-46)
- **OQ-43-19 · A "None" option in required chip sections** (Medical alerts, Intra-oral examination, Case history, Mentals and generals). **Default: each required chip list contains "None" or "Nothing abnormal".** (SAH-44-1, SAH-47)
- **OQ-43-20 · Section icons.** The BRD says each section has an icon but names none. **Default: an icon key is stored and may be empty until the EMR mockup shows the icons.** (SAH-44-1)
- **OQ-43-21 · Plan.** **Default: the EMR is available whenever OPD is on, with no separate plan flag.** (programme)

**Questions for later stories (do not block SAH-43 or SAH-44)**
- **OQ-43-22 · Allergy and chronic conditions as structured data, and "no known allergy" versus "not asked".** The patient record has one free text each. Needs a patient-module change and a rule for the header tag. (SAH-47)
- **OQ-43-23 · Out-of-range limits and the child weight estimate.** Are BP, pulse, temperature and SpO2 limits the same for children? What formula estimates a child's weight? (SAH-47, Paediatrics)
- **OQ-43-24 · IPD prescriptions** must go through the SAH-31 order path with its checks and the pharmacist check. Confirm. And where do investigations go when Lab is not built? (SAH-52)
- **OQ-43-25 · Two meanings of "Emergency":** the OPD visit type "Emergency" (FR-04) and the ER encounter from the IPD Emergency queue (SAH-28). Which patients live where? (SAH-52)
- **OQ-43-26 · "Revisit":** what is it and which fee rule applies? Is "at registration" the token step? (SAH-52)
- **OQ-43-27 · Record model for IPD and ER:** one note per day? one per ER encounter? (SAH-52)
- **OQ-43-28 · Offline draft on the device:** may clinical text be kept in the browser on a shared computer, how long, and is it cleared on sign-out? (SAH-51)
- **OQ-43-29 · WhatsApp and ABHA buttons in release 1:** hidden, or shown disabled with a "Later" note as the mockup does elsewhere? (SAH-50)
- **OQ-43-30 · Case paper language:** the clinic's "+ Marathi / + Hindi" setting exists (SAH-7) but BRD labels in Marathi and Hindi are Later. Show English only on the EMR case paper for now? (SAH-50)
- **OQ-43-31 · 32 px or 44 px targets** (C-1). **Default: 44 px.**
- **OQ-43-32 · Follow-up reason shows the diagnosis to the front desk** (BR: "primary diagnosis + review"). Hide the diagnosis from roles that should not see it, or keep the default "Review after treatment"? And do the BRD's 3, 5, 7, 15 days and 1 month replace the clinic's follow-up window? (SAH-51)
- **OQ-43-33 · Clinic tariff "for the doctor":** are services restricted by doctor or specialty? (SAH-48)

## Status
- 11 Nov 2026 · written by the business-analyst. Stories for SAH-43 and SAH-44 in `docs/stories/SAH-43.md` and `docs/stories/SAH-44.md`. SAH-45 to SAH-52 are outlines only. No shell was available, so `node scripts/validate.mjs docs` was not run by the author; the tech lead runs it. Awaiting human approval of `stories` for SAH-43 and SAH-44 and the sign-off decisions (D-n, SO-n) and open questions above.
