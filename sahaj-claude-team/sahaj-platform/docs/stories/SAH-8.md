# SAH-8 · Patients
Mockup screen: Patients (sidebar → Patients)
Module: patient

## 1. Story
As clinic staff, I want to register and find patients by mobile number, with families grouped under one number, so that the same person is never registered twice and the whole family is easy to find.

## Acceptance criteria
1. Given a mobile number with no patients, when staff register a new patient, then a patient record is created with a clinic-unique ID (`SSC-00001` style, read docs/database/*.md for the clinic's tenant prefix convention, else default `PT-00001`), full name, age, sex, mobile and the other optional fields below.
2. Given a mobile number that already has patients, when staff add another, then it is saved under the same mobile as a family member, not a new family.
3. Given the patient list, then it shows name, age, sex, UHID, chronic tag if any, last visit (none yet until SAH-9/consultation exist) and supports search by name, mobile or UHID.
4. Given the filter chips (All, New this month, Long-term care, Children, Seniors 60+), then each shows a count and filters the list. Follow-up due/missed and check-up overdue chips are out of scope until visits exist (SAH-9+); show them with a count of 0 rather than remove them, so the layout matches the mockup.
5. Given two patients share a mobile, age within 2 years and a similar name, then a "Possible duplicate" notice offers "Review and merge"; merging moves the newer record's data onto the older one and is reversible by the owner (soft: keep the merged-away record with a pointer, never hard delete).
6. Given a patient record, then it shows the summary (visits count, last visit, follow-ups kept — all zero/placeholder until SAH-9+), the family list (same mobile), and allows adding a family member from there.
7. Given any write, then it is audited (who, what, when); tenant isolation applies (RLS) exactly as in prior stories.
8. Given a non-owner role without `Patients:Create`/`Patients:Edit` (the mockup's permission names; map onto existing roles: OWNER/FRONT_DESK/NURSE can view, OWNER/FRONT_DESK can create/edit, others read-only), then write actions are hidden/disabled and the API returns 403.
9. Given required fields, then name and mobile are mandatory; age, sex are mandatory (age 0-120, sex F/M/O); area, language (mr/hi/en, default clinic's primary language), allergy, chronic condition and ABHA-linked flag are optional.
10. Given invalid input (missing name, bad mobile, age out of range), then the API returns 400 with field errors and the form shows inline messages.

## 3. Technical design
- Schema `patient`, module `com.sahaj.patient`, talking to `platform` only through `platform.api` (for AuditLog and the current user/tenant).
- `patient.patient`: `id`, `tenant_id`, `uhid` (unique per tenant, sequential, format `<prefix>-%05d`), `name`, `age`, `sex` (check F/M/O), `mobile` char(10) (not unique alone — many patients can share a mobile as a family), `area`, `language`, `allergy`, `chronic`, `abha_linked boolean default false`, `merged_into_id` (nullable self-FK, set when soft-merged), `created_at`, `created_by`, `updated_at`, `updated_by`, `version`. RLS FORCE, fail-closed on `current_tenant()`. Index on `(tenant_id, mobile)` and a trigram or prefix index on `(tenant_id, lower(name))` for search; `uhid` unique per tenant.
- No `patient.visit` table yet — that is SAH-9 (Queue/consultation). The record view's visits/bills tabs show "No visits yet" until then; do not fake data.
- Merge: `merged_into_id` points at the surviving record; a merged record is excluded from lists/search but still resolvable by direct id (for any future link) and the merge is one audit event.
- Permissions: reuse the existing `platform.role_permission` mechanism from SAH-6 if a `patients.create`/`patients.edit` key fits; otherwise gate by role server-side (OWNER, FRONT_DESK can write; OWNER, FRONT_DESK, NURSE, DOCTOR, VISITING_DOCTOR can read) — simplest default, document the choice.

## 4. API
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/patient/patients?q=&filter=&page=` | any clinic role | none | page of `PatientSummary` | 401 |
| POST | `/patient/patients` | OWNER, FRONT_DESK | `PatientCreate` | `Patient` | 400, 403 |
| GET | `/patient/patients/{id}` | any clinic role | none | `Patient` (with family list) | 403, 404 |
| PATCH | `/patient/patients/{id}` | OWNER, FRONT_DESK | `PatientUpdate` fields | `Patient` | 400, 403, 404 |
| GET | `/patient/patients/{id}/family` | any clinic role | none | `PatientSummary[]` | 403, 404 |
| POST | `/patient/patients/merge` | OWNER, FRONT_DESK | `{keepId, mergeId}` | `Patient` | 400, 403, 404 |

All paths under `/api/v1`. Contract: `docs/api/patient.yaml` (new file, solution-architect territory — tech lead writes a minimal version directly since there is no separate architect agent in 3-agent mode; db-engineer and developers may adjust field names as they build, this is feature-first per DEC-008).

## 5. Tasks
### Tasks — Backend
- Migration, repository, service, controller per above. Tenant isolation, audit, role gate, 400s with field errors, duplicate detection on create (flag only, never block).
- Tests: create, list, search, duplicate flag, merge, cross-tenant 404, non-owner 403 on write.

### Tasks — Frontend
- Route `/app/patients` behind the existing shell and `patients` menu key (already in nav.config.ts from SAH-5 — check it's there, add if missing).
- Patient list: KPIs row (registered count, seen last 30 days — 0 until visits exist, follow-ups pending — 0, long-term care count), search box, doctor filter (hide until SAH-9, since there's no doctor-visit link yet), sort, filter chips, list rows, "New patient" button.
- New patient as a simple form (not the mockup's combined walk-in/token modal — that is SAH-9's job): name, age, sex, mobile, area, language, allergy, chronic, ABHA toggle.
- Patient record page: header (avatar, name, age/sex, UHID, mobile, area, language, ABHA), summary panel (zeros/placeholders for now), family panel with add-family-member, visits/bills tabs showing "No visits yet".
- Duplicate notice and a simple merge confirm dialog.
- Unit tests for the list, the form, and the record page.

## 6. Out of scope, Open questions
Out of scope: visit history, bills, vitals trend, WhatsApp, follow-up tracking, new-token button — all need SAH-9 (Queue) or SAH-10 (Billing). Appointments-sourced patients. ABHA linking flow (just a flag here).
Open questions: none blocking — proceeding on the defaults above per DEC-008 (feature-first, sensible defaults, note and move on).

## 7. Status log
