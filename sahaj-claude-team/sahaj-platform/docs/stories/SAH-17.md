# SAH-17 · Privacy and consent
Mockup screen: Clinic setup → Privacy, ABDM and backup (`cfgData`)
Module: platform (settings), patient (consent and access log)

## 1. Story
As the clinic owner, I want to record that patients agreed to how their health records are kept and used, and to see who opened which patient record, so that the clinic can answer a patient or an inspector under India's Digital Personal Data Protection Act, 2023.

## Acceptance criteria
1. Given the owner opens Clinic setup → Privacy, ABDM and backup, then the page shows four panels as in the mockup: **Patient consent**, **ABDM**, **Records and backup**, **Who opened which record**. Non-owners see it read-only.
2. Given Patient consent, then three switches are saved per clinic: "Ask for consent at the first visit" (default on), "Separate yes for promotional messages" (default on), "Patients can ask for a copy of their records" (default on). When the first is on, the page shows the consent line with the clinic's own name ("I agree that <clinic name> may keep my health records to treat me and send me visit reminders on WhatsApp.") plus the Marathi line when the clinic has a Marathi name. When it is off, a warning notice explains the risk.
3. Given consent at first visit is on, then the patient registration dialog (SAH-8) shows a checkbox "The patient agreed to the consent line", and when the promotional switch is on, a second checkbox "The patient agreed to promotional messages". Saving a patient records each answer (agreed or not) with the staff member and the time. A patient can be registered without consent (the checkbox is not forced), and the patient's file then shows "Consent not recorded", so the front desk can ask again and record it later.
4. Given a patient's file, then it shows the latest consent state for records and promotions with date and staff name, and staff who can edit patients can record or withdraw a consent. Consent history is append-only: a withdrawal is a new row, never an edit.
5. Given the ABDM panel, then the Health Facility Registry (HFR) ID can be typed and saved (owner only, optional, free text up to 32 characters); each doctor shows "Healthcare Professional Registry (HPR) ID: Not added" and "Scan and Share at reception: After linking" as disabled placeholders, with the notice "ABDM linking is planned." No ABDM call is made.
6. Given Records and backup, then it shows the three static facts from the mockup ("Until you delete them", "Encrypted, every night", "Servers in India (planned)"). "Export all data" is shown disabled ("Needs the documents service, not in this phase"). The "Last: 2:00 AM today" backup line is not shown, because no backup status exists yet.
7. Given a user opens a patient's file (SAH-8 patient detail), then one access row is written (who, patient, time). Rows are append-only and are never deleted by the app. Opening the list or searching does not write rows.
8. Given the owner opens "Who opened which record", then the latest 5 rows (limit 1 to 50 by query) are listed, newest first (amended 4 Nov: no "from today" filter, so the list is useful on a quiet day), as "<staff name> opened the file of <patient name>" with the time. Only the owner may read this list (403 otherwise). The patient's name is resolved at read time; no name, mobile or ABHA number is stored in the log row.
9. Given any change to the three switches or the HFR ID, then it is audited (before and after values; the HFR ID is not sensitive patient data). Consent records and access rows are themselves the audit trail for those events and are not duplicated in `platform.audit_event`.
10. Tenant isolation (RLS) applies to every new table, as in earlier stories.

## 3. Technical design
- `platform.privacy_settings`: `tenant_id` PK, `consent_required boolean default true`, `promo_consent boolean default true`, `record_copy boolean default true`, `hfr_id text null (<= 32)`, `updated_at/by`, `version`. FORCE RLS. Lazy row: synthesized with defaults until the first save (same pattern as SAH-10/11/12).
- `patient.patient_consent`: `id`, `tenant_id`, `patient_id` FK `patient.patient`, `kind text check in ('RECORDS','PROMOTIONAL')`, `given boolean`, `recorded_by uuid`, `recorded_at timestamptz default now()`. Append-only (select, insert grants only). Index `(tenant_id, patient_id, kind, recorded_at desc)`. Latest row per kind is the current state.
- `patient.record_access`: `id`, `tenant_id`, `patient_id`, `user_id`, `at timestamptz default now()`. Append-only. Index `(tenant_id, at desc)`. Written by `GET /patient/patients/{id}`; the user's display name is resolved at read time through `platform.api` (`TeamMemberPort`), the patient's through `PatientRepository`.
- The settings are read by the patient registration dialog through the existing platform settings endpoint (the frontend calls both); the patient module does not read `platform.privacy_settings` directly.

## 4. API
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/platform/privacy` | any clinic role | none | `PrivacySettings` | 401 |
| PUT | `/platform/privacy` | OWNER | `PrivacySettings` | `PrivacySettings` | 400, 403 |
| GET | `/patient/patients/{id}/consents` | any clinic role | none | `PatientConsentState` | 404 |
| POST | `/patient/patients/{id}/consents` | OWNER, FRONT_DESK, NURSE | `{kind, given}` | `PatientConsentState` | 400, 403, 404 |
| GET | `/patient/record-access?limit=5` | OWNER | none | `RecordAccess[]` | 401, 403 |

Patient registration (`POST /patient/patients`) gains optional `consents: [{kind, given}]` so the dialog can send them with the new patient in one call. Contracts: `docs/api/platform.yaml`, `docs/api/patient.yaml`.

## 5. Tasks
### Tasks — Backend
- Migrations; platform privacy service and controller (owner write, audited); patient consent repository, service and endpoints, optional `consents` on create; record-access write on patient detail read and the owner-only read endpoint. Unit tests plus the first real-database tests come from qa-engineer afterwards.
### Tasks — Frontend
- Clinic setup "Privacy, ABDM and backup" page (`config-sections` key `data`, `built: true`) with four panels per the mockup. Patient registration dialog: consent checkboxes shown according to settings. Patient file: consent state with record/withdraw. Unit tests (mock HTTP).

## 6. Out of scope, Open questions
Out of scope: ABDM calls, data export, backup status, patient self-service copy of records over WhatsApp, a full access-log screen ("See full log" is shown disabled), consent for staff, consent texts per language beyond English and Marathi.
Open questions: none blocking; proceeding per DEC-008. (Legal wording of the consent line is the mockup's; the clinic should confirm it with its own adviser.)

## 7. Status log
