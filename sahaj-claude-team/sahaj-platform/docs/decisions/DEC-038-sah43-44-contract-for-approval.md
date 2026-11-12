# DEC-038 · SAH-43 and SAH-44 contract (opd.yaml 0.2.0, ADR-019): what the human approves

Date: 11 Nov 2026. Status: OPEN, waiting for the `contract` approval. Raised by: tech lead, from the solution-architect hand-back.
Checks done by the tech lead: `docs/api/opd.yaml OK, paths=34` (version 0.2.0); `validate docs` fails only on the known ADM-2.md name.
Blocked: db-engineer, java-developer, QA for SAH-43 and SAH-44 until the human adds `| SAH-43 | contract | APPROVED | … |` and `| SAH-44 | contract | APPROVED | … |` to docs/approvals.md (the `stories` lines are also still missing; the human's chat go-ahead of 11 Nov is recorded as intent in DEC-036/037, not as lines). angular-developer has nothing until SAH-45 and the EMR mockup file.

## What the contract adds (base /api/v1/opd, everything in module opd, no new module, library or service)
- Masters: `GET /emr/masters/{complaints|investigations|advice}`; `GET /diagnoses` gains optional `specialty` and `page` (without them unchanged); `GET /medicines` unchanged.
- Ranked quick options for the doctor: `GET /emr/quick-options/{complaints|diagnoses|medicines|investigations|advice}`; own doctor details and template choice: `GET /emr/me`.
- Templates: `GET /emr/templates`, `GET /emr/templates/{templateKey}`; Owner sets Show/Required: `PUT /emr/templates/{templateKey}/sections/{sectionKey}/override`, `POST /emr/templates/{templateKey}/reset`; settings `GET/PUT /emr/settings`.
- Data (ADR-019): masters and the four templates are global read-only data shipped by migration (no tenant_id); overrides, per-template revision, settings and usage ranking are tenant tables with RLS. Usage ranking is written only by the future sign step (internal service, same transaction), never by a REST call. Two small `platform.api` additions (doctor details; clinic specialty). Starter lists are marked "not clinically reviewed".

## Points the architect flagged, with the default applied (DEC-023) unless the human changes them
1. **Section keys and editor types** (SO-44-10) are the BA's reading of the BRD and become permanent when SAH-46 stores snapshots. Default: build them as written; confirm before SAH-46.
2. **EMR permissions are fixed in code** (`clinic.consultation.write` = doctor, visiting doctor, or an owner with a doctor profile; `clinic.emr_setup.edit` = owner), not cells of the "Who can do what" grid, so the Team and access screen does not change. Default: fixed in code.
3. **No allergy section in Paediatrics and Homeopathy** (OQ-43-14, a patient-safety point). Default: as the BRD table (none); the human or the clinical owner should confirm.
4. **Release gate:** ICD-10 source (OQ-43-7) unnamed and starter lists not clinically reviewed (OQ-43-8). Default: starter lists only, labelled; no real clinical use until answered.
5. **Small convention deviations:** write calls on the read-only global paths answer 403 (the story asks for 403; platform convention is 405); one revision per clinic and template, so two owners editing different sections at the same moment get a 409 for the second.
6. The Specialty-template-page vs new "Doctor EMR" entry question (DEC-036) does not change the contract; SAH-45 decides from the mockup file.
Also noticed: ADR-016 number is used twice in the repo (pre-existing; not fixed here).

## Human steps
Review docs/api/opd.yaml (0.2.0) and ADR-019; add the `stories` and `contract` lines for SAH-43 and SAH-44 (or tell me what to change); commit or stash the mixed tree so the branch `feature/SAH-43-emr-masters` can be created.

## Answer
(pending)

## Answer (human, chat, 11 Nov 2026)
"approved" (in reply to the request to approve the SAH-43 and SAH-44 stories and contract). Recorded as the human's chat approval of the stories and the contract as proposed, with the defaults in this file applied (DEC-023). **Not** lines in docs/approvals.md (the tech lead cannot write that file); if a hook blocks an agent, the human adds `| SAH-43 | stories | APPROVED |`, `| SAH-43 | contract | APPROVED |` and the same for SAH-44. Still open and unanswered: the unnamed ICD-10 source and clinical review of the starter lists (release gate), the Paediatrics/Homeopathy allergy-section question (patient safety, built as the BRD table says), the EMR mockup file for SAH-45 onward, and the dirty working tree that stops the feature branch (work continues on the current branch; no commit).
