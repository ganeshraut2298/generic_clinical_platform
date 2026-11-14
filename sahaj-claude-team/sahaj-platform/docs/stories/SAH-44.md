# SAH-44 · Stories: Specialty templates as data, clinic overrides, EMR settings, template choice and section snapshot

> **WITHDRAWN on 12 Nov 2026 (DEC-039 answer 2: rework before merge, "Variant B"). SAH-44-1, SAH-44-2, SAH-44-3 and SAH-44-4 below are replaced and must not be built, tested or reviewed any further.** The text is kept only as the record of what was approved in chat under DEC-038 and built. Where each criterion moves:
> - **SAH-44-1** (four templates as data) → **SAH-53-1** (100-section library and 15 starter departments). Moves unchanged: global read-only data, 403 for any write, versions added by migration, no patient data. Changes: counts, field types, "unknown template" becomes 404.
> - **SAH-44-2** (Show / Required overrides, reset, 409, audit, tenant and role checks) → **SAH-53-2** (settings, part, order), **SAH-53-3** (draft and publish, 409, audit), **SAH-53-4** (reset and undo); screen **SAH-45-2 to SAH-45-5**. Reversed: part and order may now be changed; Prescription, History and allergies and Medical alerts are no longer locked (only Allergies is); changes take effect at Publish, not at once.
> - **SAH-44-3** (EMR settings: section panel, right panel first, default part) → dropped; replaced by "Collapsed by default" per section (**SAH-53-2**, **SAH-45-2**) and the Past visits / Case paper tabs, Past visits first (**SAH-47-6**).
> - **SAH-44-4** (template choice, snapshot, switch for one visit) → **SAH-46-3** (department resolution and snapshot) and **SAH-53-5** (published layout read model). The order is doctor specialty, department, clinic default.
> - The architect revises ADR-019 and `docs/api/opd.yaml` 0.2.0 and the human approves them again. Resetting the unmerged migrations in `sahaj_test` is a separate human decision. SAH-43 (masters) is kept. Details: `docs/requirements/SAH-45.md`, section "SAH-43 and SAH-44 after DEC-039".

Requirement: `docs/requirements/SAH-43.md` (rules BR10 to BR18, BR3, BR26, BR28; this programme note covers SAH-44, so there is no separate SAH-44 requirement file). Module: opd.
Mockup screen: Clinic setup → Specialty template, Case paper and Rx (nearest existing screen; the Specialty template page lists the same four templates). **This story has no screen: data and backend only.** The Clinic setup → Doctor EMR page that uses it is SAH-45 and is missing from the repo mockup; nothing here builds it.
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). "Wrong role" means a signed-in clinic user whose role may not do the action; "not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body.
Defaults for open questions (OQ-43-n, in the requirement) are used unless the human answers. Content of option lists is a starter, not clinically reviewed (OQ-43-8).

## Data kinds (requirement-level; the architect and db-engineer design the tables)
| Data | Kind | Notes |
| --- | --- | --- |
| Template definitions: key, name, specialty, version, sections (BRD `emr_template`) | **Global reference**: no `tenant_id`, read-only to the app, changed by migration only in phase 1 | Same pattern as `platform.specialty_template` (SAH-7), which stays untouched |
| Clinic overrides: clinic, template key, section key, shown, required, updated by and time (BRD `emr_template_override`) | **Tenant data**: `tenant_id` and row-level security | Only Show and Required in release 1 (OQ-43-15) |
| EMR settings: rail default, right-panel default, default part (BRD `emr_settings`) | **Tenant data**: `tenant_id` and row-level security | One set per clinic; defaults apply until the first save |
| Section snapshot on a visit | **Tenant data**, stored with the EMR visit (SAH-46) | This story defines the content and the rule; storage arrives with the visit |

## Needs human sign-off (differences from the repo mockup, and BRD points the repo does not show)
- **SO-44-1 · Specialty names.** The BRD maps doctor specialties General Physician, Dental Surgeon, Paediatrician, Homeopath to the templates. The mockup names them General practice, Dental, Paediatrics, Homeopathy, and shows Ayurveda, Physiotherapy, Dermatology and Gynaecology as "Phase 2" (the Users master also lists ENT). The BRD's later templates are Gynaecology, Physiotherapy, Oncology, Surgery; Oncology and Surgery are not in the mockup. Only the four are built; the others are Later.
- **SO-44-2 · Template per doctor.** The mockup's template is per clinic and says "a multi-specialty clinic can add a template per doctor later". The BRD chooses it per doctor, falls back to the clinic's specialty, and lets the doctor switch it for one visit.
- **SO-44-3 · What a template is.** The mockup's template loads "workflow, case paper, price list, Rx favourites and reminders" and (admin portal) has versions with Draft, Publish and an "Update available" card for clinics. The BRD's template is a set of EMR sections with versions and no publish flow. Phase 1: global templates change by migration; no publish flow and no "Update available" card (OQ-43-17).
- **SO-44-4 · Hiding any section.** The BRD lets the owner hide any section. The mockup's Case paper and Rx does not let Medicines be switched off. Default: Prescription, Remedy and potency, History and allergies and Medical alerts cannot be hidden (OQ-43-14). Paediatrics and Homeopathy have no allergy or history section in the BRD; should they? (OQ-43-14)
- **SO-44-5 · Two places to show or hide sections.** Case paper and Rx (nine switches, SAH-7) and the new EMR Show switches. Default: they are separate; the case paper settings control the printed paper, the EMR settings control the doctor's screen; how they combine on the live case paper is decided in SAH-50 (D-4 in the requirement).
- **SO-44-6 · Who sees the setup.** The BRD role table says only the owner can open Clinic setup → Doctor EMR; FR-20 says other roles see it read-only; the mockup shows setup pages read-only to non-owners. Default: all clinic roles can read, only the owner writes.
- **SO-44-7 · Default part.** FR-02 says the EMR opens on the part set in Clinic setup and the entity has `default_part`, but FR-21 has no control for it. Default: stored as Assessment, not changeable (OQ-43-16).
- **SO-44-8 · Label and part overrides.** The BRD entity stores them; FR-20 has no control. Default: not editable in release 1 (OQ-43-15).
- **SO-44-9 · Icons.** The BRD says each section has an icon and names none; neither does the repo mockup. Default: an icon key is stored and may be empty until the EMR mockup shows the icons (OQ-43-20).
- **SO-44-10 · Section keys and editor types** in the table below are the analyst's reading of BRD sections 4 and 5 (FR-11 maps editors to sections; "all other sections" use Chip list). Please confirm.

### SAH-44-1 · As the Sahaj team, I want the four specialty templates held as data with their sections, so that a new specialty can be added later without changing code
Status: WITHDRAWN, replaced by SAH-53-1 (DEC-039 answer 2).
Priority: Must
## Acceptance criteria
- Given a fresh installation, when the templates are read, then four templates exist: General practice, Dental, Paediatrics, Homeopathy, each with a key, a name, a specialty and a version number. (BR10)
- Given each template, when its sections are read, then they are in the order of the table in "Notes for design", each with a stable section key, a label, a part (Assessment or Treatment), a required default, an icon key (may be empty, OQ-43-20), an editor type and a reference to its quick options. (BR10)
- Given the table, then the section counts are General practice 11 (6 required by default), Dental 11 (7), Paediatrics 9 (4), Homeopathy 7 (6); a test compares the stored data to the table. (BR10)
- Given a section's editor type, then it is one of the eleven in FR-11 (Complaints, Vitals steppers, Signs, Systems, Diagnosis, Prescription, Tooth chart, Treatment plan, Services, Advice, Chip list). Any other value cannot be stored. (BR10)
- Given a section whose quick options come from a master, then it refers to the SAH-43 master (complaints, diagnoses, medicines, investigations, advice) and the search and ranking of SAH-43 apply; given a Chip list section, then it carries its own list of options with at least one option. (BR10)
- Given a required Chip list section (Medical alerts, Intra-oral examination, Case history, Mentals and generals), then its option list includes "None" or "Nothing abnormal" so that the section can be completed when there is no finding (default of OQ-43-19). (BR10)
- Given a template's data, then section keys are unique within the template, part is only Assessment or Treatment, and a list that breaks these rules cannot be stored. (BR10)
- Given the data is global, then it has no `tenant_id`, the app can only read it, and any request by any clinic role, including the owner, to create, change or delete a template or section is refused with 403. (BR10, BR11, BR28)
- Given a new version of a template is added by migration later, then the old version stays stored, only the newest version is used for visits opened afterwards, and visits already opened keep what they were opened with (OQ-43-17, BR16). (BR10, BR16)
- Given a specialty that has no EMR template (Ayurveda, Physiotherapy, Dermatology, Gynaecology, ENT, Oncology, Surgery), then it is not offered as a template, and asking for it is a clear refusal, not an empty template. (BR10)
- Given a signed-in clinic user (any role), when templates are read, then the answer is returned; given no login, then 401. Templates hold no patient data. (BR10)
- Given the existing specialty templates of SAH-7 (features and starter services), then they are not changed and applying a specialty in Clinic setup keeps working as before. (BR10)
- Given a clinic's tenant, when templates are read for clinic A and clinic B, then both get the same definitions (global), and neither sees the other's overrides (SAH-44-2). (BR28)
## Notes for design
Section keys below are proposals (SO-44-10); the architect and db-engineer decide storage and names. Stars (*) are required by default. "Options from": M = a SAH-43 master; T = the template's own chips or items; none = the editor has no option list; tariff = the clinic tariff (SAH-7, SAH-18).

| Template | Part | # | Proposed key | Label | Req. | Editor type (FR-11) | Options from |
| --- | --- | --- | --- | --- | --- | --- | --- |
| General practice | A | 1 | chief_complaints | Chief complaints | * | Complaints | M complaints |
| General practice | A | 2 | history_allergies | History & allergies | | Chip list | T (allergy chips red) |
| General practice | A | 3 | vitals | Vitals | * | Vitals steppers | none |
| General practice | A | 4 | general_exam | General examination | * | Signs | T (pallor, icterus, cyanosis, clubbing, lymph nodes, oedema, dehydration) |
| General practice | A | 5 | systemic_exam | Systemic examination | | Systems | T (CVS, RS, CNS, Abdomen) |
| General practice | A | 6 | local_exam | Local examination | | Chip list | T |
| General practice | T | 7 | diagnosis | Diagnosis | * | Diagnosis | M diagnoses |
| General practice | T | 8 | prescription | Prescription | * | Prescription | M medicines |
| General practice | T | 9 | investigations | Investigations | | Chip list | M investigations |
| General practice | T | 10 | services | Procedures & services | | Services | tariff |
| General practice | T | 11 | advice_followup | Advice & follow-up | * | Advice | M advice |
| Dental | A | 1 | chief_complaints | Chief complaints | * | Complaints | M complaints |
| Dental | A | 2 | medical_alerts | Medical alerts | * | Chip list | T (alert chips red, includes None) |
| Dental | A | 3 | extra_oral_exam | Extra-oral examination | | Chip list | T |
| Dental | A | 4 | intra_oral_exam | Intra-oral examination | * | Chip list | T (includes Nothing abnormal) |
| Dental | A | 5 | tooth_chart | Tooth chart | * | Tooth chart | none |
| Dental | A | 6 | radiographs | Radiographs | | Chip list | T |
| Dental | T | 7 | diagnosis | Diagnosis | * | Diagnosis | M diagnoses |
| Dental | T | 8 | treatment_plan | Treatment plan & sittings | * | Treatment plan | T (suggestion rules in SAH-49) |
| Dental | T | 9 | prescription | Prescription | | Prescription | M medicines |
| Dental | T | 10 | services | Procedures & services | | Services | tariff |
| Dental | T | 11 | advice_followup | Advice & follow-up | * | Advice | M advice |
| Paediatrics | A | 1 | chief_complaints | Chief complaints | * | Complaints | M complaints |
| Paediatrics | A | 2 | birth_immunisation | Birth & immunisation history | | Chip list | T |
| Paediatrics | A | 3 | vitals_growth | Vitals & growth | * | Vitals steppers | none |
| Paediatrics | A | 4 | general_exam | General examination | * | Signs | T |
| Paediatrics | A | 5 | development | Development | | Chip list | T |
| Paediatrics | T | 6 | diagnosis | Diagnosis | * | Diagnosis | M diagnoses |
| Paediatrics | T | 7 | prescription | Prescription (by weight) | | Prescription | M medicines |
| Paediatrics | T | 8 | vaccines_today | Vaccines given today | | Chip list | T |
| Paediatrics | T | 9 | advice_followup | Advice & follow-up | | Advice | M advice |
| Homeopathy | A | 1 | chief_complaints | Chief complaints | * | Complaints | M complaints |
| Homeopathy | A | 2 | case_history | Case history | * | Chip list | T (includes None) |
| Homeopathy | A | 3 | mentals_generals | Mentals & generals | * | Chip list | T (includes None) |
| Homeopathy | A | 4 | vitals | Vitals | | Vitals steppers | none |
| Homeopathy | T | 5 | diagnosis | Diagnosis | * | Diagnosis | M diagnoses |
| Homeopathy | T | 6 | remedy_potency | Remedy & potency | * | Prescription | M medicines (remedy list needs the product owner, OQ-43-6) |
| Homeopathy | T | 7 | advice_followup | Advice & follow-up | * | Advice | M advice |

The order within each part is the BRD order. The BRD gives only the number of quick options in the setup table, not their content (OQ-43-8). "Prescription (by weight)" has no weight-based rule in the BRD (SAH-48 blocker).

### SAH-44-2 · As the owner, I want to show or hide a section and make it required or optional for my clinic, and to reset a template, so that my doctors fill only what we use
Status: WITHDRAWN, replaced by SAH-53-2, SAH-53-3, SAH-53-4 and SAH-45-2 to SAH-45-5 (DEC-039 answer 2).
Priority: Must
## Acceptance criteria
- Given the owner and the General practice template, when they set "Local examination" to hidden, then the clinic's effective General practice template no longer has that section for visits opened after the change, and other clinics are unchanged. (BR11, BR14)
- Given the owner and a shown section, when they switch Required on or off, then the effective required flag changes for visits opened after the change. (BR11, BR14)
- Given a section with no override, then its effective Show is on and its effective Required is the template's default. (BR11)
- Given a hidden section, when the owner sets it required, then the request is refused as invalid input (400) with a reason; and when the owner hides a section that is required, then it is saved as hidden and not required in the same change. (BR12)
- Given the sections that cannot be hidden by default (Prescription, Remedy & potency, History & allergies, Medical alerts; OQ-43-14), when the owner tries to hide one, then the request is refused as invalid input (400) with a reason, and nothing changes. (BR12)
- Given the owner resets a template, then all of the clinic's overrides for that template are removed and the effective sections equal the template's defaults; the answer includes the new counts "n sections on · m required". (BR13)
- Given the owner resets a template that has no overrides, then the answer is a success, nothing changes and no audit entry is written. (BR13)
- Given a request that sets a label or a part, or sends any field other than Show and Required, then it is refused as invalid input (400) (OQ-43-15). (BR11)
- Given a template key or section key that does not exist, then the answer is 404 (or 400 for a malformed key) and nothing is stored. (BR11)
- Given a body that holds a clinic id, then it is ignored; the clinic is the logged-in user's. (BR11, BR28)
- Given a doctor, visiting doctor, nurse, front desk, billing or pharmacist user, when they try to change an override or reset a template, then the answer is 403 and nothing changes (OQ-43-2: owner only). (BR18)
- Given any signed-in clinic role, when they read the effective template and the clinic's overrides, then it works (read-only for non-owners, SO-44-6); given no login, then 401. (BR18)
- Given two owners change the same section at the same time, when the second sends an older version, then the answer is 409 and the second change is not applied. (BR18)
- Given the owner of clinic A, when clinic B's owner reads or changes overrides, then A's overrides are never visible or changeable (row-level security; checked with a second clinic). (BR11, BR28)
- Given any change or reset, then one audit entry is written with who, when, template key, section key and the before and after of Show and Required (setup values, no patient data); a reset writes one entry for the template with the before and after counts. (BR3)
- Given the owner changes a section after a doctor opened a visit, when that visit is read, then it still shows the sections it was opened with (SAH-44-4). (BR14, BR16)
- Given a template version change adds or removes a section (OQ-43-17), then existing overrides are kept by section key, new sections appear with defaults and overrides for removed sections are ignored, not applied and not deleted. (BR11)
- Given a request that is invalid or slow to reach the server, then no half change is stored: each change is all or nothing. (BR11)
## Notes for design
Overrides are per clinic, per template, per section (BRD). A change to Show or Required has no effect on signed visits. No numbers for speed are set. The clinic setup screen is SAH-45 and waits for the mockup.

### SAH-44-3 · As the owner, I want to choose whether the section panel opens collapsed or open and which right-hand tab shows first, so that the EMR opens the way my clinic likes
Status: WITHDRAWN, dropped; replaced by "Collapsed by default" per section (SAH-53-2, SAH-45-2) and the tab order in SAH-47-6 (DEC-039 answer 2).
Priority: Must
## Acceptance criteria
- Given a clinic with no saved settings, when the settings are read, then they are: section panel Collapsed, right panel first Case paper, default part Assessment. (BR17)
- Given the owner, when they set the section panel to Open or Collapsed, then it is saved and returned on the next read. (BR17)
- Given the owner, when they set the right panel first to Case paper or Past visits, then it is saved and returned. (BR17)
- Given any other value for either setting, then the request is refused as invalid input (400) and nothing changes. (BR17)
- Given a request to change the default part, then it is refused (OQ-43-16: stored as Assessment, no control in release 1). (BR17)
- Given a doctor, visiting doctor, nurse, front desk, billing or pharmacist user, when they try to change the settings, then the answer is 403; any signed-in clinic role can read them; no login gets 401. (BR18)
- Given the settings are saved, then each change writes one audit entry with who, when and before and after of each changed setting (setup values). (BR3)
- Given two owners change the settings at the same time, when the second sends an older version, then the answer is 409. (BR18)
- Given clinic A's settings, when clinic B reads or changes its own, then neither sees nor changes the other's (row-level security). (BR28)
- Given a doctor opens the section panel for one visit, then nothing is stored in these settings: the doctor's toggle lasts for that visit only (BRD FR-06). (BR17)
- Given the settings change, then it applies the next time an EMR is opened; an EMR already open is not changed. (BR14, BR17)
## Notes for design
One set of settings per clinic; the first save creates it and defaults apply before that (the same lazy pattern as other settings). Paper size and language stay in Case paper and Rx (SAH-7): this story must not copy them.

### SAH-44-4 · As a doctor, I want the EMR to pick the right template for me and keep the sections my visit opened with, so that setup changes never change a visit I started or signed
Status: WITHDRAWN, replaced by SAH-46-3 and SAH-53-5 (DEC-039 answer 2).
Priority: Must
## Acceptance criteria
- Given a doctor whose saved specialty reads "General Physician" or "General practice", when the template is chosen, then it is General practice; "Dental Surgeon" or "Dental" gives Dental; "Paediatrician" or "Paediatrics" gives Paediatrics; "Homeopath" or "Homeopathy" gives Homeopathy. The match ignores letter case and extra spaces. The list of names is data that the product owner may extend. (BR15, OQ-43-13)
- Given a doctor whose specialty is empty or not in the list, when the template is chosen, then the clinic's specialty decides (gp, dental, paeds or homeo from the clinic profile); and when that is also empty, then it is General practice. (BR15)
- Given the template is chosen, when the effective sections are built, then they are the template's sections with the clinic's overrides applied (hidden sections left out, required flags as set). (BR15, BR11)
- Given a doctor, visiting doctor or owner with doctor details opens a visit's EMR for the first time, then a snapshot is stored with that visit: template key, template version and the ordered list of shown sections with key, label, part, required flag, editor type, icon key and option reference, as they were at that moment. (BR16)
- Given a snapshot exists, when the owner changes an override or resets a template, then that visit still shows and judges "required done" by the snapshot; a visit opened after the change uses the new settings. (BR14, BR16)
- Given a snapshot exists, when the template gets a new version, then the visit is unchanged. (BR16)
- Given a visit is signed, when anything tries to change its snapshot, then the change is refused (409 or 403) and the snapshot is unchanged. (BR16)
- Given a draft visit, when the doctor switches the template for this visit (one of the four), then a new snapshot replaces the old one, no entered data is deleted, sections that are not in the new template are kept in the record but not shown, and the switch writes one audit entry with who, when, visit id, old and new template key (no clinical text). (BR15, OQ-43-18)
- Given the doctor switches the template, then it is not saved as their default: the next visit uses the rule above again. (BR15)
- Given a visit that is signed, when the doctor tries to switch the template, then it is refused. (BR15, BR16)
- Given a template key that is not one of the four, when it is chosen for a visit, then it is refused (400). (BR15)
- Given a nurse, front desk, billing or pharmacist user, when they try to open the EMR or build a snapshot, then the answer is 403 (they never open the EMR); an owner without doctor details is refused by the default of OQ-43-12. (BR2)
- Given a doctor of clinic B, when they ask for a visit of clinic A, then the answer is 404 and no snapshot is readable (tenant check). (BR28)
- Given the doctor has no saved specialty and the clinic has none, when the template is chosen, then General practice is used and the answer says that the choice came from the default. (BR15)
- Given any of these calls, then no log line holds the doctor's name or any clinical text. (BR26)
## Notes for design
The rule lives in `opd`; it reads the doctor's specialty through `platform.api` (SAH-43-3) and the clinic's specialty from the clinic profile through `platform.api`. The snapshot is stored with the EMR visit created in SAH-46; this story defines what the snapshot holds and the rules, and can be tested before the visit exists. Required-done checks and the finish gate use the snapshot (SAH-47, SAH-51). The doctor's switch control lives in the expanded rail (SAH-46, FR-07).

## Out of scope for SAH-44
Any screen, including Clinic setup → Doctor EMR (SAH-45, waits for the mockup); editing labels or parts; a screen to edit global templates; template versions with draft and publish and the "Update available" card (admin portal, later); the other templates (Gynaecology, Physiotherapy, Oncology, Surgery, Ayurveda, Dermatology, ENT); a pick-list for the doctor's specialty on Team and access; the case paper's section switches (SAH-7, unchanged); Marathi and Hindi labels (Later).

## Traceability
BR3 -> SAH-44-2 (override audit), SAH-44-3 (settings audit), SAH-44-4 (switch audit). BR10 -> SAH-44-1. BR11 -> SAH-44-1, SAH-44-2. BR12 -> SAH-44-2. BR13 -> SAH-44-2. BR14 -> SAH-44-2, SAH-44-3, SAH-44-4. BR15 -> SAH-44-4. BR16 -> SAH-44-1, SAH-44-4. BR17 -> SAH-44-3. BR18 -> SAH-44-2, SAH-44-3. BR26 -> SAH-44-4. BR28 -> SAH-44-1 to SAH-44-4.

## Open questions
All are in `docs/requirements/SAH-43.md` with defaults: OQ-43-2 (doctor edits templates: owner only), OQ-43-8 (clinical content of option lists), OQ-43-12 (who writes the EMR), OQ-43-13 (specialty mapping), OQ-43-14 (sections that cannot be hidden; allergy section for Paediatrics and Homeopathy), OQ-43-15 (label and part overrides), OQ-43-16 (default part), OQ-43-17 (new template version), OQ-43-18 (switching template after data is entered), OQ-43-19 (None option), OQ-43-20 (icons).

## Status log
- 11 Nov 2026 · written by the business-analyst from the BRD (SAH-43-input), DEC-036, the repo mockup and the built SAH-6 and SAH-7 data. Approved in chat (DEC-038).
- 12 Nov 2026 · WITHDRAWN (DEC-039 answer 2, Variant B). Not for approval, build or test. Replacement stories: SAH-53, SAH-45, SAH-46.
