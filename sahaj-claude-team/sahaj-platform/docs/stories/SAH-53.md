# SAH-53 · Stories: Template data model extension and seed (section library, 15 starter departments, clinic departments, drafts and published versions)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-3, BR45-7 to BR45-15, BR45-18; decided sign-offs SO-45-2, SO-45-3, SO-45-5 to SO-45-8, SO-45-34 to SO-45-40; answered questions OQ-45-1, OQ-45-3 (Variant B), OQ-45-6, OQ-45-7, OQ-45-10, OQ-45-11, OQ-45-14, OQ-45-21, OQ-45-24: "answered: default accepted (DEC-039 addendum)", OQ-45-6 answered explicitly, OQ-45-3 answered Variant B; answered, default accepted (DEC-039 addendum): OQ-45-29). Module: opd. Phase 1. **This story replaces the withdrawn SAH-44-1 to SAH-44-4 (data part): SAH-53-1 replaces SAH-44-1, SAH-53-2 to SAH-53-4 replace SAH-44-2, SAH-53-5 replaces the layout part of SAH-44-4 (DEC-039 answer 2, Variant B).**
Mockup screen: Clinic setup → Specialty template, Case paper and Rx (EMR mockup #Main, #AddDepartment). **This story has no screen: data and backend only.** The screens that use it are SAH-45 (#Main) and SAH-54 (#AddDepartment).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). "Wrong role" means a signed-in clinic user whose role may not do the action; "not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body.
All counts and lists below are taken from the mockup and accepted by the human (OQ-45-1, SO-45-3). The clinical content of field lists is "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (OQ-43-8 still advises clinician review before real use).
The human chose Variant B (rework SAH-44 before merge). There is one configuration model only. ADR-019 and `docs/api/opd.yaml` 0.2.0 will be revised by the architect and approved again; the destructive step on `sahaj_test` (unmerged migrations V2026_12_08_1020 and V2026_12_08_1030 already applied there) is a separate human decision with a plan from the architect and db-engineer. Story SAH-53-6 (compatibility with the four built templates) is dropped.

## Data kinds (requirement-level; the architect and db-engineer design the tables)
| Data | Kind | Notes |
| --- | --- | --- |
| Section library: key, name, kind (Common, Specialty, Compliance), description, field list (label, type, meta, required) | **Global reference**: no `tenant_id`, read-only to the app, changed by migration only | 100 sections |
| Starter departments: id, name, initials, colour, ordered section list, sections off at start, consent flag, PCPNDT flag, default Required, Print, Copy and Who-can-fill lists, default part of each section | **Global reference**, same rules | 15 departments |
| Clinic department: name, initials, colour, status (Template, Draft, Live), current published version number, source (starter id, blank, or copied department) | **Tenant data**: `tenant_id`, row-level security | Created by SAH-54 or on first Save draft (OQ-45-11) |
| Department draft: one working copy of the settings, parts and order, with a revision number | **Tenant data** | One shared draft per department |
| Published department version: version number, ordered list of sections with settings, who published and when | **Tenant data**, never changed after publishing | Kept for ever (OQ-45-21) |
| Doctors and rooms of a department | **Tenant data** | Written by SAH-54 |

## Starting values seen in the mockup (seeded as drawn)
- **Common sections (14), in this order:** Vitals; Chief complaints; History of present illness; Past, family & personal history; Allergies; General & systemic examination; Diagnosis; Investigations; Prescription (Rx); Advice & diet; Procedures; Follow-up; Referral; Attachments & images.
- **Compliance sections (8):** ABHA / ABDM link; Consent forms; Prescription print format; PCPNDT Form F; Scheme / insurance (PM-JAY, MJPJAY); Surgical consent; WHO surgical safety checklist; Insurance pre-authorisation.
- **Required at start (12 keys):** Chief complaints, Diagnosis, Prescription (Rx), Prescription print format, Dental chart, PCPNDT Form F, ANC visit card, Pain assessment, Visual acuity, Immunization, Growth chart, Lesion body map.
- **Print on prescription at start (12 keys):** Vitals, Chief complaints, Diagnosis, Prescription (Rx), Advice & diet, Follow-up, Investigations, Refraction & glasses Rx, Treatment plan & estimate, Home exercise program, ANC visit card, Immunization.
- **Copy from last visit at start (10 keys):** Past, family & personal history; Allergies; Obstetric history; Menstrual history; Birth history; Dental chart; Chronic disease tracker; Immunization; Growth chart; Prakriti assessment.
- **Who can fill at start:** Doctor on every section; Nurse / assistant on Vitals, Chief complaints, Past history, Allergies, Attachments, ABHA link, Growth chart, Visual acuity, Refraction, Glucose and HbA1c log, IOP, Audiometry; Reception on ABHA link and Consent forms.
- **Part 2 Treatment at start (39 keys):** Diagnosis, Investigations, Prescription, Advice & diet, Procedures, Follow-up, Referral, Consent forms, Prescription print format, Adult vaccination, Treatment plan & estimate, Procedures done (dental), Lab work (dental), Dental chart, Contraception, Infertility workup, Immunization, Weight-based dosing, Treatment goals, Treatment modalities, Home exercise program, Session & package tracker, Cast / splint, Refraction & glasses Rx, Procedure sessions, Insulin regimen, Panchakarma plan, Therapy notes, Tumour board decision, Chemo order and cycles, Pain and palliative care, Scheme / insurance, Surgery plan and OT booking, Surgical consent, Pre-op orders, Insurance pre-authorisation, WHO surgical safety checklist, Operation notes, Post-op orders and wound check. Every other section starts in part 1 Assessment.
- **Default order of a department:** the 14 Common sections, then the department's Specialty sections, then ABHA / ABDM link, Consent forms, Prescription print format (and PCPNDT Form F for Obstetrics & Gynaecology).
- **Off at start (Common sections only):** General Medicine: Procedures, Referral. Dental: General & systemic examination, History of present illness, Referral. Obstetrics & Gynaecology: Referral. Paediatrics: Referral, Procedures. Physiotherapy: Prescription, Investigations, General & systemic examination, Procedures. Ophthalmology, ENT, Dermatology: General & systemic examination. AYUSH: Investigations. Medical Oncology: Procedures, Referral, General & systemic examination. Psychiatry & Counselling: General & systemic examination, Procedures. Consent forms start on only for departments with the consent flag (Dental, Obstetrics & Gynaecology, Orthopaedics, Dermatology, Medical Oncology, General Surgery).

### SAH-53-1 · As the Sahaj team, I want the 100-section library and the 15 starter departments held as data, so that every clinic starts from the same departments and new ones can be added without changing code
Priority: Must
## Acceptance criteria
- Given a fresh installation, when the section library is read, then it holds exactly 100 sections: 14 Common, 78 Specialty and 8 Compliance, each with a unique stable key, a name of 1 to 200 characters, a kind, a description and a field list. (BR45-7)
- Given a section whose field list is not specified in the mockup, then it carries the three default fields shown in the mockup (Findings, rich text; Template, dropdown; Attachments, file) and a test compares the stored data to the mockup. (BR45-7)
- Given each field, then it has a label, a type, a meta text and a required flag; the type is one of a fixed list of types agreed by the architect, and any other value cannot be stored. (BR45-7)
- Given the 15 starter departments are read, then they are General Medicine, Dental, Obstetrics & Gynaecology, Paediatrics, Physiotherapy, Orthopaedics, Ophthalmology, ENT, Dermatology & Cosmetology, Diabetology, Cardiology, AYUSH (Ayurveda), Medical Oncology, General Surgery, Psychiatry & Counselling, each with initials, a colour and an ordered section list. (BR45-3)
- Given the ordered section lists, then the numbers per department are General Medicine 19, Dental 24, Obstetrics & Gynaecology 25, Paediatrics 23, Physiotherapy 27, Orthopaedics 22, Ophthalmology 22, ENT 22, Dermatology 20, Diabetology 22, Cardiology 21, AYUSH 21, Medical Oncology 27, General Surgery 26, Psychiatry 20 (341 in all), and every one of the 100 library sections is used by at least one department. (BR45-7)
- Given the starting values listed above (Required, Print, Copy, Who can fill, Part, Off at start), then they are seeded as drawn and a test compares them to the lists. (BR45-9)
- Given any clinic role including the owner, when a request tries to create, change or delete a library section or a starter department, then it is refused with 403 and nothing changes. (BR45-3)
- Given a signed-in clinic user (any role), when the library or the starter departments are read, then the answer is returned and is the same for every clinic; given no login, then 401. They hold no patient data. (BR45-3)
- Given the seed, then no item holds a patient name, mobile number or other patient data. (BR45-5)
- Given a future migration adds or changes a library section, then clinic departments already published are not changed (they keep their own published copy). (BR45-12)
- Given a request for a starter department or section key that does not exist (including the retired `gp` and `homeo` keys), then the answer is 404 and not an empty template. (BR45-3)
- Given the keys `dental` and `paeds`, then they are **valid starter department keys again** because the mockup uses them (ADR-020 D4); they answer 404 only at the **removed old paths** (`/emr/templates/**` of the withdrawn SAH-44), never at the new starter paths. This narrows the earlier wording "including the retired gp, dental, paeds, homeo template keys" to those removed paths for `dental` and `paeds`; `gp` and `homeo` stay unknown keys everywhere (404 `starter-not-found`). (BR45-3)
- Given a required field that offers a fixed choice list, then the list includes "None" or "Nothing abnormal" so that the field can be completed when there is no finding (moved from the withdrawn SAH-44-1 and OQ-43-19). (BR45-7)
- Given the Homeopathy template of the withdrawn SAH-44, then there is no Homeopathy department (the mockup has AYUSH (Ayurveda) instead); a clinic whose specialty is Homeopathy gets the clinic default department (SAH-46-3). (BR45-8)
## Notes for design
The library and the starter list are the mockup's `S` and `DEPTS` data; the db-engineer reads them from the file. Icons are not drawn (OQ-43-20). The 78 Specialty and the field text are starter content for review (OQ-43-8). Field type names are for the architect.

### SAH-53-2 · As the owner, I want my clinic's department to hold its own setting for every section (show, required, print, copy, collapsed, who can fill, part and order), so that each department's EMR is set up the way we work
Priority: Must
## Acceptance criteria
- Given a clinic department, when its draft is read, then every section of the department's list appears with Show in EMR, Required to close visit, Print on prescription, Copy from last visit, Collapsed by default, Who can fill (a set of Doctor, Nurse / assistant, Reception), its part (1 Assessment or 2 Treatment) and its position, starting from the starter values. (BR45-9)
- Given the owner sets Show off for a section, then it is saved as hidden; given a hidden section is set Required, then the request is refused as invalid input (400) with a reason; and when the owner hides a section that is required, then it is saved as hidden and not required in the same change. (BR45-10)
- Given the department's list of "x of y on" and "required" counts is read, then it counts shown sections only. (BR45-10)
- Given the owner moves a section to the other part, then its part changes and it keeps its place in the department's overall order (so it shows among the sections of the new part at that place); given Up or Down, then it swaps with the next section of the same part, and at the top or bottom of its part nothing changes and the answer is a success. (BR45-11)
- Given the section Allergies, when the owner tries to hide it in any department, then the request is refused (400) with a reason and nothing changes; given any other section (including Prescription (Rx)), then it can be hidden (OQ-45-6, answered explicitly: only Allergies cannot be hidden). (BR45-10)
- Given the Compliance section PCPNDT Form F in Obstetrics & Gynaecology, when the owner tries to hide it, then the request is refused (400) with a reason (OQ-45-7, decided; OQ-45-29 asks the human whether this lock stays, default: it stays); given other Compliance sections, then they can be hidden as drawn. (BR45-10)
- Given a department with every section hidden (except the locked ones) or with nothing required, then the change is accepted; there is no rule (OQ-45-24, decided). (BR45-10)
- Given a section key that is not in the department, then the answer is 404 and nothing is stored; given a setting value other than the listed ones (an unknown role, a part other than the two), then 400. (BR45-9)
- Given a request that sets a label, a kind, a description or a field, then it is refused as invalid input (400): in this story only the six settings, the part and the order change. (BR45-9)
- Given a body that holds a clinic id, then it is ignored; the clinic is the logged-in user's. (BR45-3)
- Given a change is invalid or fails half way, then nothing is stored (all or nothing). (BR45-15)
## Notes for design
Who can fill is stored and shown only; no role other than the doctor writes the EMR, and the EMR does not pre-fill the nurse's vitals in phase 2 (OQ-45-14, decided; SO-45-43, placeholder story SAH-72). Compliance sections: OQ-45-7 (decided). Print flags choose which sections reach the printed Rx; size, language, letterhead and capitals stay in Case paper and Rx (OQ-45-10, decided). The default part of a section comes from the Part 2 list above. Field editing is not here (SO-45-9, SAH-59).

### SAH-53-3 · As the owner, I want to save a draft and publish it as the next version of a department, so that doctors only see a layout I have finished and old visits keep theirs
Priority: Must
## Acceptance criteria
- Given a department with changes, when the owner saves the draft, then the draft is stored with a new revision number, the published version is unchanged and doctors still see the published layout. (BR45-12)
- Given there are no changes since the last published version or saved draft, when the owner saves or publishes, then the answer is a success with the message "Nothing new to save" or "Nothing to publish" and no audit entry and no new version is written. (BR45-12)
- Given the draft has changes, when the owner publishes, then a new published version is stored with the next number (v6 becomes v7), the status becomes Live, the draft is cleared, and the answer says "<department> published as v<n>. New visits use it from now." (BR45-12)
- Given a department that was never published (status Template or Draft), when the owner publishes, then the first published version is v1. (BR45-12)
- Given a visit was opened with version n, when version n+1 is published, then the visit still reads version n (checked when the visit exists, SAH-46). (BR45-18)
- Given a published version, when anything tries to change it, then the change is refused and the version is unchanged. (BR45-12)
- Given two owners edit the same department, when the second saves or publishes with an older revision, then the answer is 409 and nothing is applied. (BR45-15)
- Given publish is called twice at the same moment, then only one new version is created. (BR45-12)
- Given the owner of clinic A, when the owner of clinic B reads or changes departments, then A's drafts and versions are never visible or changeable (row-level security; checked with a second clinic). (BR45-3)
- Given a doctor, visiting doctor, nurse, front desk, billing or pharmacist user, when they try to save a draft or publish, then the answer is 403 and nothing changes; given no login, 401. (BR45-15)
- Given any save draft or publish, then one audit entry is written with who, when, department, version or revision and the before and after of each changed setting (setting values only, no patient data). (BR45-15)
## Notes for design
No approval step on this page (OQ-45-13, decided). Every published version is kept for ever because signed visits point to it (OQ-45-21, decided). Statuses: Template = never saved by the clinic (v0), Draft = saved but never published, Live = at least one published version (OQ-45-11). The version is per department, not per clinic.

### SAH-53-4 · As the owner, I want to reset a department to its starter template and get the previous draft back if I change my mind, so that I can start over safely
Priority: Must
## Acceptance criteria
- Given a department, when the owner resets it, then the draft's settings, parts and order become the starter template's values, the count of changes rises, and the published version is unchanged. (BR45-13)
- Given the reset has just been made, when the owner undoes it, then the draft returns to exactly what it was before the reset; given any other change was made after the reset, then Undo is no longer offered. (BR45-13)
- Given a department already equal to its starter values, when the owner resets it, then the answer is a success, nothing changes and no audit entry is written. (BR45-13)
- Given the reset, then it covers only the selected department and no other. (BR45-13)
- Given a reset is saved and published, then a new version is created from the starter values and earlier versions are kept. (BR45-12, BR45-13)
- Given a doctor, visiting doctor, nurse, front desk, billing or pharmacist user, when they try to reset, then the answer is 403; given another clinic's department, then 404. (BR45-15)
- Given a reset, then one audit entry is written with who, when, department and the before and after counts of shown and required sections. (BR45-15)
## Notes for design
Undo is a screen-level behaviour (SAH-45-5); this story only requires that the draft before the reset can be restored and that nothing is lost on the server until the next save. Reset covers settings, parts and order, and Undo restores all three until the page is left (OQ-45-8, decided).

### SAH-53-5 · As a doctor, I want the EMR to read the published layout of my department, so that I see the sections my clinic chose, in order, with the required flags
Priority: Must
## Acceptance criteria
- Given a department with a published version, when a signed-in clinic user reads the layout, then the answer lists only the shown sections of that version, in order, each with name, kind, part, required, print, copy, collapsed, who can fill and its fields. (BR45-19)
- Given a department with no published version, then the layout is the starter template's values (shown sections only) and the answer says it comes from the starter. (BR45-8)
- Given a draft exists, then the layout read by doctors does not include it. (BR45-12)
- Given a specific version number, when it is read, then that version is returned as it was published. (BR45-12, BR45-18)
- Given a department of another clinic, then the answer is 404. (BR45-3)
- Given a nurse, front desk, billing or pharmacist user, then the layout can be read (it holds no patient data) but is not used to open the EMR (SAH-46). Given no login, 401. (BR45-3)
- Given the layout is read, then no log line holds any patient data. (BR45-5)
## Notes for design
This is the read model SAH-46 snapshots and SAH-47 renders. Doctors' reads must be fast; no number is set here.

## Dropped: the former SAH-53-6 (compatibility with the four built templates)
It existed only for Variant A, which the human did not choose. The id SAH-53-6 is not used, so that ids are not renumbered. There is nothing to build or test under it. SAH-44-1 to SAH-44-4 are withdrawn and replaced by SAH-53-1 to SAH-53-5, SAH-45 and SAH-46 (DEC-039 answer 2).
- The old template endpoints and tables of the withdrawn SAH-44 are not kept and are not marked deprecated; the architect removes them from the contract in the revision of `docs/api/opd.yaml` 0.2.0 and ADR-019, and the human approves the revision. (SO-45-39)
- The clinic specialty check on the clinic profile (gp, dental, paeds, homeo) is revisited by the architect (SO-45-40); the stories SAH-53-1 to SAH-53-5 do not widen it.
- Whether to reset or repair `sahaj_test` after the withdrawn migrations is a separate human decision with a concrete plan.

## Out of scope for SAH-53
Any screen; editing a library section or its fields (SAH-59); icons; Marathi and Hindi names of sections (not drawn: the mockup shows section names in English); the other doctor-facing editors; approval workflow; a platform-admin screen to edit the library (global rows change by migration only).

## Traceability
BR45-3 -> SAH-53-1, -3, -5. BR45-5 -> SAH-53-1, -5. BR45-7 -> SAH-53-1. BR45-8 -> SAH-53-5. BR45-9 -> SAH-53-1, -2. BR45-10 -> SAH-53-2, -3. BR45-11 -> SAH-53-2. BR45-12 -> SAH-53-3, -5. BR45-13 -> SAH-53-4. BR45-15 -> SAH-53-2, -3, -4. BR45-18 -> SAH-53-3, -5. BR45-19 -> SAH-53-5.

## Open questions
In `docs/requirements/SAH-45.md`, all answered: OQ-45-1 (counts), OQ-45-3 (Variant B), OQ-45-6 (only Allergies cannot be hidden), OQ-45-7 (Compliance sections), OQ-45-8 (Reset), OQ-45-10 (Print versus Case paper), OQ-45-11 (starting state of a clinic), OQ-45-13 (approval), OQ-45-14 (who can fill), OQ-45-21 (versions kept), OQ-45-24 (empty department). Answered, default accepted (DEC-039 addendum): OQ-45-29 (PCPNDT lock stays next to "only Allergies").

## Status log
- 12 Nov 2026 · written by the business-analyst from EMR mockup #Main and #AddDepartment source, DEC-039 and the built SAH-44.
- 12 Nov 2026 (later) · updated after the human's answers: Variant B chosen (SAH-53-6 dropped, SAH-44 withdrawn), OQ-45-6, 7, 8, 10, 14, 21, 24 written in as decided rules. Awaiting human approval of `stories`.
