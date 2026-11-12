# DEC-037 · SAH-43 and SAH-44 (EMR masters, specialty templates): what the human signs off with the stories

Date: 11 Nov 2026. Status: OPEN. Raised by: tech lead, from the business analyst's hand-back (docs/requirements/SAH-43.md, docs/stories/SAH-43.md, docs/stories/SAH-44.md).
Blocked: the architect for SAH-43 and SAH-44 (needs the `stories` lines in docs/approvals.md). SAH-45 onward also waits for the EMR mockup file (DEC-036).
Default rule (DEC-023): if you do not answer a point, the default below is applied. Approving the stories (the approval lines) accepts the defaults you do not change. No default here is a clinical, pricing or legal rule.

## What the analyst found in the repo
Much of SAH-43 already exists and is reused, not rebuilt: global `opd.diagnosis` (ICD-10, about 50 starter rows), global `opd.medicine`, per-clinic `opd.medicine_favourite`, `platform.doctor_profile` (degree, registration number, specialty as free text), `opd.case_paper_settings`, `platform.specialty` and `specialty_template`; the clinic specialty is limited to gp, dental, paeds, homeo. New: complaints, investigations and advice masters, usage ranking per doctor, EMR templates (global data), clinic overrides (Show and Required only) and EMR settings. Story ids in the playbook are stale; the story files use the real ones (Queue is SAH-9, follow-ups SAH-14, Today SAH-15, IPD medicine orders SAH-31).

## 1. Needs your answer before real use (does not block approving the stories)
- **OQ-43-7 · ICD-10 source.** Which licensed source feeds the diagnosis master. The analyst states no licence terms. Default: keep the starter list, marked "not clinically reviewed", and do not load a full list until you name a source.
- **OQ-43-8 · Who writes and reviews clinical content** (complaints, investigations, advice lists). Default: starter lists marked "not clinically reviewed", as SAH-7 does.
- **OQ-43-6 · Drug master** (clinic list, CDSCO list or a vendor) for favourites and generic names. Blocks SAH-48 only.

## 2. Where the BRD differs from the repo mockup (rule 1: your sign-off), most important first
The full list is D-1 to D-17 in the requirement and stories. The ones that change what gets built:
| # | Difference | Default applied |
| --- | --- | --- |
| D-1 | No Doctor EMR screen and no Clinic setup → Doctor EMR page in the repo mockup | The mockup file is yours to add (DEC-036); SAH-43 and SAH-44 need no screen |
| D-2 | The Queue has "Consult done" only: no doctor queue view, no Open EMR button; demo roles are front desk, nurse, owner | Belongs to SAH-46, waits for the mockup |
| D-4 | Show/hide sections exists twice (the nine case paper switches and the new EMR switches); the mockup never lets Medicines be switched off, the BRD lets any section be hidden | Prescription, Remedy and potency, History and allergies and Medical alerts cannot be hidden |
| D-5 | The mockup has a "capitals" switch (default off); the BRD says generic names are always stored in capitals | Always capitals (the BRD) |
| D-8 | The BRD wants before/after values in the audit log; the project convention logs ids and keys only and never clinical notes | Setup changes (non-clinical) log before/after; clinical content logs ids and keys only. Please confirm. |
| D-9 | The BRD wants favourites per specialty; SAH-7 has them per clinic | Keep per clinic, filtered by the doctor's template |
| D-12 / D-13 | Mockup "template" is per clinic and means workflow, price list and so on, with Draft/Publish; the BRD template is per doctor with a per-visit switch and means EMR sections | Two separate things: EMR templates are new data, the existing clinic template is unchanged |
| D-16 | The BRD puts emergency patients at the top of the OPD queue; the repo has a separate IPD → Emergency queue (SAH-28) | Decided in SAH-46/52, not in SAH-43/44 |
| C-1 | Tap target 32 px (BRD) vs 44 px (`.claude/rules/angular.md`) | 44 px |
Others (D-3 stylus and voice, D-6 non-owner read-only, D-7 Marathi and Hindi Later, D-10 print preview, D-11 specialty names, D-14 master columns, D-15 Doctor role already in the repo, D-17 "Revisit" undefined) keep the analyst's defaults in the files.

## 3. Defaults the analyst took for SAH-43 and SAH-44 (open questions OQ-43-9 to OQ-43-21)
A doctor's free-text specialty maps to a template through a fixed alias list; clinics cannot add their own master items; a missing registration number or degree does not block (prints "—"); only master items are ranked, counted once per signed visit, never-used items in a fixed order; label and part overrides are not editable; default part stored as Assessment with no control; non-owners see setup read-only; a template version change does not alter existing visits (snapshot); a "None" option in required chip lists so Medical alerts can be completed when there is nothing to record; section icons chosen by the architect (BRD silent); the EMR is available whenever OPD is on. Not decided in these stories: who may write the EMR (visiting doctor, an owner who is not a doctor: OQ-43-12), switching template after data is entered (OQ-43-18).

## What I need from the human
1. Add `| SAH-43 | stories | APPROVED | <you> | <date> |` and the same for SAH-44 to docs/approvals.md (or tell me what to change in the stories first). Then the solution-architect starts the contract.
2. Save the EMR mockup in docs/design/mockup/ and add the screen-map rows (DEC-036) before SAH-45.
3. Commit or stash the mixed working tree so I can create `feature/SAH-43-emr-masters` from main.
4. Name the ICD-10 source and the drug master when you can (section 1).

## Answer
(pending)

## Addendum (human, chat, 11 Nov 2026)
"Approval for all stories development" given in chat for the EMR stories; recorded as intent, not as approval lines. With it the defaults in sections 2 and 3 above are applied (DEC-023) and the open points in section 1 (ICD-10 source, drug master) stay open with the "not clinically reviewed" starter lists. The tech lead started the solution-architect on the SAH-43 and SAH-44 contract; the `contract` approval remains a separate human step before the database and developer stages.
