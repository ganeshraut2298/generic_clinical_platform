# SAH-43 · Stories: EMR masters (quick-option masters, ranking per doctor, doctor master reuse, ICD-10 source)
Requirement: `docs/requirements/SAH-43.md` (rules BR1 to BR9, BR26, BR28). Module: opd (masters, ranking); platform (doctor details, read through `platform.api`).
Mockup screen: Sahaj admin portal (platform admin view), tab Templates and master data and the Master data pages (Diagnosis, Medicine, Investigation masters), used as reference only. **This story has no screen: data and backend only.** Nothing in it adds or changes a screen. No master-editing screen is built (global rows change by migration only, as in SAH-7).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). "Wrong role" means a signed-in clinic user whose role may not do the action; "not signed in" gets 401; the clinic always comes from the logged-in user, never from the request.
Defaults for open questions are used unless the human answers (OQ-43-n are in the requirement). The real content of the lists is a starter, not clinically reviewed (OQ-43-8).

## Data kinds (requirement-level; the architect and db-engineer design the tables)
| Data | Kind | Status |
| --- | --- | --- |
| Complaints master | Global reference: no `tenant_id`, read-only to the app, changed by migration only | New |
| Investigations master | Global reference, same rules | New |
| Advice master | Global reference, same rules | New |
| Diagnosis master with ICD-10 code (`opd.diagnosis`) | Global reference, same rules | Exists (SAH-7) |
| Medicine master, generic names (`opd.medicine`) | Global reference, same rules | Exists (SAH-7) |
| Clinic favourite medicines (`opd.medicine_favourite`) | Tenant data, `tenant_id` and row-level security | Exists (SAH-7), reused |
| Usage per doctor (counts and last used) | Tenant data, `tenant_id` and row-level security | New |
| Doctor details (`platform.doctor_profile`) | Tenant data, `tenant_id` and row-level security | Exists (SAH-4, SAH-6), reused |

## Needs human sign-off (differences from the repo mockup, and BRD points the repo does not show)
- **SO-43-1.** The BRD needs a **complaints master** and an **advice master**. The mockup's master data pages have no complaints or advice master (they have Diagnosis, Medicine, Formulation, Dosage, Route, Investigation, Procedure, Allergy). Two masters are added that the mockup does not show.
- **SO-43-2.** The mockup's masters have **organisation-level rows** (a clinic's own diagnosis such as "Dengue fever (suspected)"), **Marathi and Hindi local terms** and a **long-term condition** flag. This story builds global rows, English names, no clinic-added rows and no chronic flag (Marathi and Hindi labels are Later, BRD phase 4; OQ-43-10).
- **SO-43-3.** **Ranking per doctor** (how often this doctor used an item, then recency) is not in the mockup; the mockup only has favourites per clinic and per template.
- **SO-43-4.** **Favourites.** The BRD says "favourites per specialty". The built SAH-7 favourites are per clinic. This story keeps the clinic favourites unchanged and adds no per-specialty favourites (D-9 in the requirement); the Prescription editor (SAH-48) decides how favourites, ranking and any specialty starter list combine.
- **SO-43-5.** **Roles.** The BRD role table lists Doctor, Owner (doctor), Nurse, Front desk. The platform and the mockup also have visiting doctor, billing, pharmacist and counsellor; the BRD claim that the mockup has no Doctor role is not true of the repo (D-15). Default for who may write the EMR: OQ-43-12.
- **SO-43-6.** **Capitals.** The BRD says generic medicine names are stored in capitals. The mockup has a switch "Medicine names in CAPITALS" (default off). This story does not change the master's stored names; the rule is applied in SAH-48 and SAH-50 (D-5).
- **SO-43-7.** **Specialty names.** The BRD maps template specialties as General Physician, Dental Surgeon, Paediatrician, Homeopath; the mockup says General practice, Dental, Paediatrics, Homeopathy (D-11). Used in story SAH-44-4.

### SAH-43-1 · As a doctor, I want to search complaints, diagnoses (with ICD-10 code), medicines (generic names), investigations and advice from fixed lists, so that I pick items instead of typing them
Priority: Must
## Acceptance criteria
- Given a signed-in clinic user, when they search the complaints master by the first letters of a name (any letter case), then the matching complaints are returned in order, and when nothing matches the answer is an empty list, not an error. (BR4)
- Given the same search on the investigations master and the advice master, then they behave the same way as the complaints search. (BR4)
- Given a search on diagnoses, then it matches the start of the name or the start of the ICD-10 code (the SAH-7 behaviour, unchanged), and each result carries its ICD-10 code. (BR4, BR5)
- Given a search on medicines, then it behaves as built in SAH-7: generic name with strength and form, the clinic's favourites first. Nothing about SAH-7 medicine search changes. (BR5)
- Given a search with no text, then the first items of that master in its own order are returned, up to a page limit, with a way to ask for the next page. (BR4)
- Given a specialty is passed with the search (one of the four template specialties), then items tagged for that specialty come first and all others follow; nothing is hidden because of the specialty. (BR4)
- Given a very long or odd search text (over the allowed length, only spaces, percent or underscore characters, or other characters that mean something to a database), then the answer is either a normal empty list or a bad-request answer, never a server error and never a wider match than typed. (BR4)
- Given the user is not signed in, when any master is searched, then the answer is 401. (BR4)
- Given a nurse, front desk, billing or pharmacist user (or any other signed-in clinic role), when they search a master, then it works, because the masters hold no patient data. (BR4)
- Given any clinic role including the owner, when a request tries to create, change or delete an item in a global master, then it is refused with 403 and nothing changes. (BR4)
- Given two clinics, when one of them marks a favourite medicine, then the other clinic's search order does not change (tenant check), and the master lists themselves are the same for both. (BR4, BR28)
- Given the seed data, then no item in any master contains a patient name, mobile number or other patient data, names are unique within a master ignoring letter case, and every complaint, investigation and advice item has a name of 1 to 200 characters. (BR4, BR26)
- Given an item is later removed from a master by a migration, when a signed visit used it, then the visit is not changed (visits keep their own copy of the name; checked when the visit stories are built). (BR4)
## Notes for design
Same pattern as SAH-7 medicine and diagnosis (prefix search, page limit, `text_pattern_ops` style index). The architect decides paths and contract. Master content is a **starter list** (about 50 items per master, as decided for SAH-7 on 1 Nov 2026), marked "not clinically reviewed" until a clinician signs off (OQ-43-8). The investigation list can start from the mockup's Investigation master (13 tests). Marathi and Hindi names are not part of this story.

### SAH-43-2 · As a doctor, I want quick-add items ordered by how often I use them and then how recently, so that my routine visits need fewer taps
Priority: Must
## Acceptance criteria
- Given a doctor has used complaint A on 5 signed visits and complaint B on 3, when the doctor asks for ranked complaints, then A comes before B. (BR6)
- Given two items with the same count, when the ranked list is read, then the one used more recently comes first. (BR6)
- Given items this doctor never used, when the ranked list is read, then they follow all used items in the master's own order (default of OQ-43-9). (BR6)
- Given the same ranking is asked for diagnoses, medicines, investigations and advice, then each master is ranked by the same rule, separately. (BR6)
- Given a request for the first N items (the chips screen will ask for 10), then at most N items are returned and N has an upper limit. (BR6)
- Given doctor X and doctor Y in the same clinic, when each reads the ranking, then each sees an order built only from their own use. (BR6)
- Given a visiting doctor who works at two clinics, when they read the ranking in each clinic, then each clinic has its own counts and neither shows the other's. (BR6, BR28)
- Given a visit is signed with master items on it, when the EMR reports that visit's items (the call SAH-51 will make), then each master item counts once for that visit for the signing doctor, and the last-used time is that of the signature. Drafts and addenda do not change the counts (default of OQ-43-9). (BR6)
- Given the same item appears twice on one visit, then it counts once. (BR6)
- Given text the doctor typed freely (not a master item), when a visit is reported, then it is not counted, not stored in any list and never offered as a chip to anyone. (BR7)
- Given two visits signed at the same moment by the same doctor, when the counts are updated, then neither update is lost. (BR6)
- Given any client request, when it tries to set or change counts directly, then it is refused (there is no public way to write counts; only the sign step writes them). (BR6)
- Given a doctor, visiting doctor or owner with doctor details asks for the ranking, then they get their own. Given a request that names another doctor's id, then it is ignored or refused: nobody reads another doctor's ranking, not even the owner. (BR2, BR6)
- Given a nurse, front desk, billing or pharmacist user, when they ask for a ranking, then the answer is 403. (BR2)
- Given the clinic's data, when clinic B reads ranking, then clinic A's counts are never visible (row-level security; checked with a second clinic). (BR28)
- Given the stored usage, then it holds only master item ids, doctor, clinic, count and last-used time: no patient id, no visit id, no text. (BR7, BR26)
- Given a doctor with no use yet, when the ranking is read, then the answer is the master's own order, not an empty list. (BR6)
## Notes for design
Counting happens when SAH-51 signs a visit; until then the capability is tested with direct service tests. The ranking is read by the EMR (SAH-47, SAH-48); the chips screen limit of 10 is a UI rule. Usage counts are not audited one by one (they are derived from the signed record, which is audited in SAH-51). No numbers for speed are set here (the BRD's targets belong to Later).

### SAH-43-3 · As a doctor or owner, I want the EMR to use my name, degree, registration number and specialty from Team and access, so that the case paper header and template choice are right without typing them again
Priority: Must
## Acceptance criteria
- Given a user whose role is doctor or visiting doctor, or an owner who has doctor details, when the EMR (module `opd`) asks for their details through `platform.api`, then it gets name, degree, registration number and specialty exactly as saved in Team and access (SAH-6), and no second copy of these fields is created. (BR5, BR8)
- Given the details are read, then `opd` does not read the platform tables directly; it uses the `platform.api` port. (BR1, BR8)
- Given an owner who has no doctor details, when the EMR asks for their details, then the answer says there are none, and by the default of OQ-43-12 that owner cannot write the EMR. (BR2, BR8)
- Given a doctor whose degree or registration number is empty, when the details are read, then the answer says which of them are missing, so that a later story can warn the owner and the case paper can print "—" as the mockup does (OQ-43-11). Nothing is blocked in this story. (BR8)
- Given the doctor's specialty is free text up to 120 characters, when it is read, then it is returned as saved; this story does not change the Team and access screen, its validation or its API. (BR8)
- Given a doctor who left the clinic (membership removed), when an earlier record asks for the doctor's details, then the name, degree and registration number are still returned for that record, as the doctor row is never deleted (SAH-4). (BR8)
- Given a nurse, front desk, billing or pharmacist user, when they ask for another user's doctor details for the EMR, then the answer is 403; a case paper reprint later reads the printed details through the case paper, not through this call. (BR2)
- Given a doctor of clinic A, when clinic B asks, then they are never returned (tenant check). (BR28)
- Given the details are read or logged, then the doctor's name and registration number are not written to any log line. (BR26)
- Given a visiting doctor who works at two clinics, when the details are read in each clinic, then each clinic's own saved details are returned. (BR28)
## Notes for design
Existing: `platform.doctor_profile` (specialty, degree, registration number, fee, mobile) and `DoctorInput` / `DoctorDetails` in SAH-6. **Gaps found, to be decided before SAH-44-4 and SAH-46 are built:** (1) specialty is free text with no link to a template key (OQ-43-13); (2) degree and registration number are optional while the NMC rule in the BRD wants them on every case paper (OQ-43-11); (3) an owner who is not a doctor has no doctor details (OQ-43-12); (4) the BRD does not mention visiting doctor, billing, pharmacist or counsellor (SO-43-5). No change to these is made in this story. The doctor's name comes from the user (membership), not from the doctor details.

### SAH-43-4 · As the product owner, I want the ICD-10 diagnosis master to come from a named, licensed source and every starter list to be reviewed by a clinician before real use, so that codes and clinical content are lawful and safe
Priority: Must (a release gate before real clinic use; the starter lists keep development moving)
## Acceptance criteria
- Given the human answers OQ-43-7, when the design note is written, then it names the ICD-10 source, its version and the licence terms that allow the product to store and show it, and who may redistribute it. Until the human answers, no source is assumed and nothing is said about licence terms. (BR9)
- Given the diagnosis master, when it is read, then each item has exactly one ICD-10 code matching the code pattern already enforced (SAH-7), and codes are unique. (BR9)
- Given the existing starter diagnoses (about 50 rows, "not clinically reviewed"), when the app runs in development or test, then they keep working. (BR9)
- Given any master list is still a starter list, when the release is prepared, then the design note says so in one line per master, and the human's release check lists "clinician has reviewed" as open until a named clinician signs it (a human gate, not code). (BR9)
- Given a diagnosis typed freely (not in the master), when it is later saved on a visit, then it has no ICD-10 code, and the case paper shows the code only "when known" (rule for SAH-47 and SAH-50). (BR9)
- Given the master's source or version changes later, when an old signed visit is read, then it still shows the code as it was saved (checked when the visit stories are built). (BR9)
- Given the source needs a list of terms the clinic can search in Marathi or Hindi, then that is out of scope (Later). (BR9)
## Notes for design
Blocked only for the **final content** by OQ-43-7 and OQ-43-8; the other three stories do not wait for it. I do not state any licence terms: that is for the human and the product owner. A later ABDM share may want SNOMED CT coded diagnoses; not built, not designed (Later).

## Out of scope for SAH-43
Any screen (including a master-editing screen), clinic-added master rows, Marathi and Hindi names, the Dosage, Formulation, Route, Allergy and Procedure masters of the mockup (the services list is the clinic tariff of SAH-7 and SAH-18), the Prescription editor's parsing of frequency and duration (SAH-48), the "check doses" behaviour, per-specialty favourites, any change to SAH-6 Team and access.

## Traceability
BR1, BR5 -> SAH-43-1 (reuse), SAH-43-3. BR2 -> SAH-43-2, SAH-43-3. BR4 -> SAH-43-1. BR6 -> SAH-43-2. BR7 -> SAH-43-2. BR8 -> SAH-43-3. BR9 -> SAH-43-4. BR26 -> SAH-43-1, -2, -3. BR28 -> SAH-43-1, -2, -3.

## Open questions
All are in `docs/requirements/SAH-43.md` with defaults: OQ-43-6 (drug master, blocks SAH-48 only), OQ-43-7 (ICD-10 source), OQ-43-8 (clinical content), OQ-43-9 (what counts as a use, order of never-used items), OQ-43-10 (clinic-added items), OQ-43-11 (missing registration number), OQ-43-12 (who writes the EMR), OQ-43-13 (specialty mapping, used in SAH-44-4).

## Status log
- 11 Nov 2026 · written by the business-analyst from the BRD (SAH-43-input), DEC-036 and the repo (SAH-7 migrations, SAH-6 doctor details). Awaiting human approval of `stories`.
