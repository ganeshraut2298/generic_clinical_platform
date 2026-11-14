# ADR-020 · Doctor EMR by department: section library, clinic departments with draft and published layouts, the EMR record and its snapshot, Queue-card entry for OPD, IPD and Emergency

Stories: SAH-53, SAH-45, SAH-54 (phase 1), SAH-46, SAH-47, SAH-52 (phase 2). Forward compatibility only: SAH-48 to SAH-51, SAH-55 to SAH-68.
Mockup: `docs/design/mockup/EMR-Configuration-Smart-Clinic.html` (#Main, #AddDepartment, #DoctorGeneral, #GeneralTreatment; `window.__DC_SRC`: `S`, `DEPTS`, `CORE`, `PART_T`, `REQ`, `PRINT`, `COPY`, `ASSIST`). Queue (sidebar → Queue), IPD → Admitted, IPD → Emergency for the entry.
Module: opd. Reads platform, patient and ipd only through `platform.api`, `patient.api` and an `opd.api` port that ipd implements (D9).
Status: PROPOSED (12 Nov 2026). The human pre-approved the EMR stories in chat (DEC-039 addenda); the `contract` stage for this ADR and `docs/api/opd.yaml` 0.3.0 still needs the human's approval.
Supersedes: **ADR-019 D4, D5, D6, D11 in full**, and the SAH-44 rows of ADR-019 D2, D8, D9, D12 and "Data needed". **Keeps ADR-019 D1, D3, D7, D10** and the principle of D8 (fixed capabilities), with the additive changes in D13 below.
Related: DEC-039 (scope, Variant B, tag rule, OQ-45 defaults), DEC-040 (WhatsApp and ABHA, not decided here), `docs/requirements/SAH-45.md` (BR45-n, SO-45-n, OQ-45-n), ADR-002 (tenancy, audit), ADR-005 (ER case has no patient until admitted), ADR-017 (IPD scope guard, break-glass).

---

## Context

DEC-039 replaced the BRD design (four global templates, Show and Required overrides) with the department-wise mockup: a 100-section library, 15 starter departments, per-clinic departments with six settings per section, two parts, ordering, Save draft and Publish with versions, Reset with Undo, an Add department wizard, and an EMR that opens from the Queue card for OPD, IPD and Emergency and renders the published layout. SAH-44 is withdrawn before merge (Variant B). SAH-43 (masters, ranking, doctor context) stays.

Facts that shape the design (checked 12 Nov 2026):
- `ipd` already depends on `opd.api` (`FollowUpPort`, ADR-004) and `ModularityTests` runs `ApplicationModules.verify()`. **A dependency from opd to ipd.api would be a cycle and fail the build.**
- An Emergency case has no patient until it is admitted (`ipd.emergency_case.patient_id` null, ADR-005).
- OPD stores no vitals values: the Vitals step is a stage of `opd.visit` only.
- `patient.api.PatientClinicalPort` gives UHID, name, age, sex and allergy text, not the chronic conditions text the header needs.
- Target scale: 10,000 clinics at 30 to 50 visits a day gives 100 to 180 million EMR records a year.

---

## Decisions

### D1 · Module rule (unchanged from ADR-019 D1)
Everything is in `opd`: library, starters, clinic departments, versions, EMR records. No new module, no new library, no new external service. WhatsApp and ABHA are DEC-040 (D15 leaves extension points only).

### D2 · Data kinds

| Data | Kind | Rule |
| --- | --- | --- |
| Section library (100 sections, their fields and options) | Global reference | No `tenant_id`, no RLS, SELECT only for `sahaj_app`, changed by migration only |
| Starter departments (15) and their section placements (341) | Global reference | Same |
| Starter layouts (one immutable document per starter and starter version) | Global reference, **immutable** | Trigger denies UPDATE, DELETE, TRUNCATE; a change is a new starter version |
| Doctor-specialty-to-department aliases | Global reference | Same as library |
| Clinic department (settings draft, revision, rooms) | Tenant | RLS, `tenant_id` from the session |
| Published department version | Tenant, **immutable** | Trigger + grants SELECT, INSERT only; kept for ever (OQ-45-21) |
| Department doctors | Tenant | RLS |
| EMR record (one per encounter, D8) with section values | Tenant, clinical | RLS, never hard-deleted, audited by id |
| Masters, usage (SAH-43) | as ADR-019 D2 | Additive changes in D13 |

Writes to global data by any clinic role answer 403 (ADR-019 D2 rule extended to `/emr/library/**` and `/emr/starter-departments/**`).

### D3 · Section library: sections, fields and field types are data

- **Section:** stable `key` (the mockup's `S` key, for example `vitals`, `dental_chart`, `onc_bsa`; `^[a-z][a-z0-9_]{1,39}$`), `name` 1 to 120, `kind` COMMON (mockup `core`, 14), SPECIALTY (`spec`, 78), COMPLIANCE (`comp`, 8), `description` up to 500, `alwaysShown` (true only for `allergy`: OQ-45-6), ordered fields. Section names stay English (SAH-53 out of scope; the mockup draws them in English).
- **Field:** `key` (`^[a-z][a-z0-9_]{0,39}$`, unique within the section, derived by the db-engineer from the mockup label, for example "BP systolic" → `bp_systolic`), `label`, `type`, `meta` (the mockup's third part, up to 200), `required`, optional `options`, `optionsSource`, `columns`. Sections with no field list in the mockup get the mockup's `FALLBACK` (Findings rich text, Template dropdown, Attachments file).
- **Field types:** the 30 distinct types found in the library, one enum value each (mockup label → value): Text TEXT, Rich text RICH_TEXT, Number NUMBER, Number + unit NUMBER_UNIT, Number + trend NUMBER_TREND, Calculated CALCULATED, Formula FORMULA, Rule RULE, Reference REFERENCE, Date DATE, Calendar CALENDAR, Toggle TOGGLE, Dropdown DROPDOWN, Dropdowns DROPDOWNS, Single choice SINGLE_CHOICE, Multi-select MULTI_SELECT, Checklist CHECKLIST, Chips CHIPS, Search list SEARCH_LIST, Table TABLE, Grid GRID, Pattern PATTERN, Template TEMPLATE, Doctor / hospital DOCTOR_HOSPITAL, Chart CHART, Body map BODY_MAP, Tooth picker TOOTH_PICKER, Library LIBRARY, File FILE, Signature SIGNATURE. A database check holds the list. The Form builder (SAH-59, 23 palette types) adds values additively; the client must show an unknown type as "Not available yet" and never fail.
- **Options:** where the mockup meta is a list ("Mild / Moderate / Severe", "Pallor, icterus, …"), the seed stores it as options (key, label). A required choice field with seeded options also gets one `isNone` option "None" or "Nothing abnormal" (SAH-53-1, from OQ-43-19), except fields whose list is a scale or a setting (for example FDI / Universal / Palmer, VAS / NPRS); the db-engineer lists those exceptions in the design note. A choice field whose meta is not a list ("By region", "Vendor list", "Per eye") has no options and accepts free text (`allowFreeText`). `optionsSource` is NONE, FIELD (own options), MASTER (`complaints`, `diagnoses`, `medicines`, `investigations`, `advice`: SAH-43 search and ranking apply) or SERVICES (the clinic's active tariff, SAH-7). The seed maps Complaint → complaints, Provisional and Final diagnosis → diagnoses, Lab tests and Radiology → investigations, Drug → medicines, Procedure → SERVICES; other search lists are free typed ("New to list", OQ-45-17).
- **Value kind** (how the generic renderer edits and stores a field) is derived from the type, fixed in code and returned with each field:

| valueKind | Types | Stored JSON value |
| --- | --- | --- |
| TEXT | TEXT, RICH_TEXT, PATTERN, DOCTOR_HOSPITAL | string (TEXT, PATTERN, DOCTOR_HOSPITAL up to 2,000; RICH_TEXT plain text with line breaks up to 10,000; no HTML) |
| NUMBER | NUMBER, NUMBER_TREND | number, finite, at most 4 decimals, field `min`/`max` when set, else −1,000,000 to 1,000,000 |
| NUMBER_UNIT | NUMBER_UNIT | `{ "value": number, "unit": string ≤ 20 }` |
| DATE | DATE | `"YYYY-MM-DD"` |
| BOOLEAN | TOGGLE | boolean |
| CHOICE | DROPDOWN, SINGLE_CHOICE | string: an option key, or free text ≤ 200 when the field has no options |
| MULTI_CHOICE | MULTI_SELECT, CHECKLIST, CHIPS | array ≤ 50 of strings (option keys, or free text ≤ 200 when no options) |
| ITEM_LIST | SEARCH_LIST | array ≤ 50 of `{ "itemKey": string or null, "text": string ≤ 200, "newToList": boolean }`; `itemKey` is the SAH-43 item key or a service id; free text has `itemKey` null and `newToList` true |
| TABLE | TABLE, GRID | array ≤ 50 of rows `{ <columnKey>: string ≤ 500 }`; a field with no `columns` has one column `entry` "Entry" |
| STRUCTURED | TEMPLATE, DROPDOWNS, BODY_MAP, TOOTH_PICKER, LIBRARY | object; phase 2 writes `{ "text": string ≤ 2,000 }`; the specialty editors (SAH-49, SAH-57, SAH-65 …) add properties next to `text` with their own `schemaVersion` |
| DISPLAY_ONLY | CALCULATED, FORMULA, RULE, REFERENCE, CHART | nothing stored; the renderer shows the meta; specialty stories compute them |
| LATER | FILE, SIGNATURE, CALENDAR | nothing stored; control shown disabled "Later" (documents service and OT schedule: OQ-45-28) |

  A value of the wrong shape, an unknown field key, or a value for a DISPLAY_ONLY or LATER field answers 400 `validation`. No numeric "allowed range" is drawn in the mockup for any field; `min`/`max` exist in the model and are null in the seed (see Human items).

### D4 · Starter departments and starter layouts

- 15 starters, keys = the mockup `DEPTS` ids: `gm`, `dental`, `og`, `paeds`, `physio`, `ortho`, `eye`, `ent`, `derm`, `diab`, `cardio`, `ayush`, `onco`, `surgery`, `psych`; name, initials, colour (`hue`), wizard description (only Physiotherapy's is drawn; the others are null), `consent` and `pcpndt` flags.
- Placements (341) follow the mockup logic exactly: order = 14 COMMON, then the starter's `spec` list, then `abha`, `consent`, `rxformat` (+ `pcpndt` for og); shown = not in the starter's `off` list for COMMON, `consent` shown only with the consent flag, SPECIALTY and COMPLIANCE shown; required = `REQ`; print = `PRINT`; copy = `COPY`; who can fill = DOCTOR always, NURSE for `ASSIST`, RECEPTION for `abha` and `consent`; part = TREATMENT for `PART_T`, else ASSESSMENT; collapsed false.
- **Hide locks are data:** a section cannot be hidden when `section.alwaysShown` (Allergies, everywhere) or when the placement of the department's starter has `alwaysShown` (`pcpndt` in `og`, OQ-45-7; OQ-45-29 default). Dropping the PCPNDT lock later is one migration row.
- **Starter layout:** for each starter the seed also writes one immutable JSON document (`starter_version` 1) holding the full layout in the D6 shape (all sections with settings and copied section definitions). Visits that open on a never-published department point to it (D8). A later migration that changes the library or a starter adds starter version 2; visits already opened keep version 1.
- **Blank layout** (Start blank, SAH-54-2): the 14 COMMON sections and `abha`, `consent`, `rxformat` with the COMMON defaults (gm values), part per `PART_T`.
- The retired SAH-44 keys `gp` and `homeo` are unknown starter keys (404 `starter-not-found`). `dental` and `paeds` are valid again as starter keys because the mockup uses them; the old `/emr/templates/**` paths no longer exist (404). This narrows the SAH-53-1 criterion "including the retired gp, dental, paeds, homeo template keys" to the retired paths for `dental` and `paeds`; flagged to QA.

### D5 · Clinic departments, identity and status

- A department is addressed by `departmentKey`. The 15 starter-backed departments use the starter key (`gm` …) and exist **virtually** (status TEMPLATE, version 0) until the clinic's first Save draft, Publish or Add department creates the row (OQ-45-11). Departments added by the wizard get a server-generated key `d_` + 10 lower-case base32 characters, never reused.
- Fields: key, name (1 to 60, unique per clinic ignoring case, **including the 15 virtual names**), initials (1 to 3, `^[A-Z0-9]{1,3}$`, derived from the name when not sent), colour (`^#[0-9A-F]{6}$`, from a fixed palette when not sent), `starterKey` (the starter for STARTER, the source's starter for COPY, null for BLANK), `sourceKind` STARTER, BLANK or COPY, `copiedFromKey`, room labels (0 to 10, each 1 to 40, unique ignoring case; text only, OQ-45-19), `revision`, `publishedVersion` (0 = never), draft (D6).
- Status: TEMPLATE = no row; DRAFT = row, never published; LIVE = at least one version. `hasDraft` tells whether a saved draft differs from what doctors see.
- At most 50 added departments per clinic (400 `department-limit`); no delete or rename (SAH-54 out of scope).
- **Doctors** (SAH-54-3): many to many, active DOCTOR or VISITING_DOCTOR memberships of the clinic only (`platform.api.TeamMemberPort.findActiveDoctors`); ids are checked against that list at write (400 `validation` otherwise, never another clinic's). No screen to change them after creation (not drawn).

### D6 · Draft, publish, versions, reset with undo

- **Layout settings** (draft and request bodies): the ordered list of the department's sections, each with `key`, `part`, `shown`, `required`, `print`, `copy`, `collapsed`, `whoCanFill` (subset of DOCTOR, NURSE, RECEPTION). Position = array order (1-based); "keeps its place in the overall order" (SAH-53-2) is therefore a client concern: moving a section between parts changes `part` only, Up and Down swap it with the next section of the same part in the array.
- **Server rules on every save and publish** (all or nothing): the key set must equal the department's section set exactly (unknown key 404 `section-not-found`; missing or duplicate key 400 `section-set-mismatch`; sections cannot be added or removed in this phase); `shown = false` with `required = true` → 400 `hidden-section-required`; hiding a locked section → 400 `section-not-hideable`; only these eight properties are accepted (a label, kind, description or field → 400 `validation`; the request bodies are closed).
- **Draft:** one shared draft per department, stored as a JSON document (`schemaVersion` 1) on the department row, with `revision` (bigint). `PUT …/draft` with the revision read; stale → 409 `emr-setup-changed`; equal to the current draft (or, with no draft, to what doctors see) → 200 `NOTHING_NEW`, no write, no audit. The first save of a virtual department inserts the row; two first saves at once collide on the unique key → 409.
- **Publish:** `POST …/publish` carries the revision and the full layout on screen (so unsaved changes are published without a second call). Under the department row lock: if there is no saved draft and the body equals what doctors see → 200 `NOTHING_TO_PUBLISH`. Otherwise insert version `publishedVersion + 1` holding the full layout **with a copy of each section's definition and fields from the library at that moment**, set `publishedVersion`, clear the draft, revision + 1. An added department (status DRAFT) always has a draft, so its first publish makes v1. Two publishes at once: the row lock and the revision check give one version.
- **Versions are immutable** (trigger and grants) and kept for ever. A library change by a later migration never alters them (SAH-53-1).
- **What doctors see** (`…/layout`, SAH-53-5): the shown sections of the latest version; with no version, the starter layout of the department's `starterKey` with `layoutSource = STARTER` ("Using the starter layout"). An added department that was never published is not visible to doctors (SAH-54-4): it is skipped by resolution and not offered in the department switch; its `/layout` answers 404 `department-not-published` for doctors.
- **Reset and Undo are a screen action** (SAH-53-4 note, SAH-45-5): `GET …/reset-target` returns the starter (or blank) settings; the page replaces its working copy, counts the change as unsaved, and keeps the previous working copy in memory for Undo until the page is left or the next change. Nothing is written on the server until Save draft or Publish, so nothing is lost. The save that follows carries `origin: RESET`, which makes the audit action `EMR_DEPARTMENT_RESET` (with before and after counts) instead of `EMR_DEPARTMENT_DRAFT_SAVED`; a reset that changes nothing is `NOTHING_NEW` with no audit. A non-owner gets 403 on that save, as on every save. Rejected: a server-side reset with a stored undo copy (two extra endpoints, and it would save the reset at once, against SAH-45-5 "counted as unsaved").
- **Unsaved changes count** (OQ-45-15): computed on the page as the number of distinct (section, property) differences plus moved positions against the last saved draft; the server returns that baseline in `GET /emr/departments/{key}`.

### D7 · Template resolution (doctor specialty → department → clinic default)

Run at the first open of a record (D8) and by `GET /emr/me`:
1. **Doctor specialty:** the doctor's free-text specialty (through `TeamMemberPort.doctorDetails`), normalised (trim, lower case, runs of spaces made one), looked up in the global alias table → starter key S → department key S (virtual or real). Seed aliases: every department name and initials, and the usual doctor titles (general physician, physician, dentist, dental surgeon, gynaecologist, obstetrician, paediatrician, pediatrician, physiotherapist, orthopaedic surgeon, ophthalmologist, ent surgeon, dermatologist, diabetologist, cardiologist, ayurveda, ayurvedic physician, oncologist, medical oncologist, general surgeon, surgeon, psychiatrist, counsellor, psychologist). No alias for homeopath (SAH-53-1). The product owner extends the list by migration.
2. **Doctor's departments:** the published departments the doctor is assigned to; one → it; several → the one whose `starterKey` equals the clinic default's, else the first by name.
3. **Clinic default:** `ClinicSpecialtyPort.specialtyCode` mapped gp → `gm`, dental → `dental`, paeds → `paeds`, homeo or empty → `gm` (SO-45-40: the clinic profile check of four values is **not** widened).

`choiceSource` = DOCTOR_SPECIALTY, DOCTOR_DEPARTMENT, CLINIC_DEFAULT, or SWITCHED (doctor switched for one record). A doctor may switch a DRAFT record to any department visible to doctors; not stored as a default.

### D8 · One EMR record model for OPD, IPD round and Emergency

**Decision: one table, one shape, one API**, with `encounterType` OPD_VISIT, IPD_ROUND, ER_ASSESSMENT.
Reasons: the frame, sections, snapshot, Done rule, Save draft, signing (SAH-51), Past visits (SAH-50, one query across OPD and IPD), Copy from last visit, case paper and usage ranking are the same for all three; the BRD table FR-02 differences (title, visit-type control, Admit button, finish) are presentation and finish rules keyed by `encounterType`. Three tables would triplicate every later story. Rejected: (b) three tables; (c) storing EMR data inside ipd for IPD and ER (ipd would own a second copy of the EMR model and opd would read clinical text across a module boundary).

- **Identity and uniqueness:** OPD_VISIT: one record per `opd.visit` (FK, same module). IPD_ROUND: one record per admission per round date (Asia/Kolkata calendar day of the first open) — the default for **OQ-43-27**, still open. ER_ASSESSMENT: one record per emergency case. Admission and case ids are opaque uuids from ipd (no foreign key across modules). `patient_id` is required for OPD and IPD and null for an Emergency case until the case is admitted (ADR-005); the record view then shows the case's display name, age and sex from ipd, and Past visits and Copy from last visit are empty.
- **Open** (`POST /emr/records/open`, idempotent): finds or creates the record under a transaction-scoped advisory lock on the encounter id (two first opens make one record), resolves the department (D7), stores the **snapshot reference** (below), pre-fills Copy-from-last-visit sections, and for OPD moves the visit to WITH_DOCTOR through the existing workflow service (D10). Every open writes one audit entry.
- **Snapshot = a reference to an immutable layout, not a copy.** The record stores `layoutKind` (DEPARTMENT_VERSION or STARTER_LAYOUT), the department key and name at the time, `versionNo` or starter version, and `choiceSource`. Both targets are immutable (D4, D6), so the reference is as safe as a copy (ADR-019 D6 used the same argument for template options) and saves about 10 to 20 KB per record (2 TB or more a year at target scale). The API expands it: the record view lists only the **shown** sections of that layout, in order, with part, required, print, copy, collapsed, who can fill and fields (SAH-46-3). Later publishes and resets never change it. A switch of department on a DRAFT record replaces the reference, keeps all values (sections not in the new layout stay stored and are not shown), and is audited. A SIGNED record's reference never changes (409 `emr-record-signed`).
- **Section values: one JSON document per record** (`sections`, `schemaVersion` 1): `{ <sectionKey>: { "values": { <fieldKey>: value }, "copiedFrom": { "recordId", "date" } | null, "copyConfirmed": boolean, "updatedAt", "updatedBy" } }`. Chosen over typed rows (one row per field: an entity-attribute-value table of billions of rows a year with 30 value shapes) and over one row per section (10 to 25 rows per record, 1 to 3 billion rows a year). The project rule allows `jsonb` for configurable forms with a schema version. Values are validated in the application against the snapshot's field definitions (D3). Cost: no SQL query over clinical values; nothing in phases 1 to 5 needs one (Trend and Past visits read whole records of one patient).
- **Status:** DRAFT now; SAH-51 adds SIGNED, `signedAt`, `signedBy` and addenda. Records are never deleted (no DELETE grant).
- **Save draft** (`PUT /emr/records/{id}/draft`): carries `revision`, the UI position (`part`, `sectionKey`, SAH-47-5) and **only the sections changed since the last save**, each with its full values; other stored sections stay. No required check. Stale revision → 409 `emr-record-changed` (two writers of one OPD visit, SAH-46-2). The 64 KB body limit applies; one section's values are at most 32 KB.
- **IPD and ER drafts wait for OQ-43-27** (SAH-46-4): open and snapshot work for all three types now; `PUT …/draft` on an IPD_ROUND or ER_ASSESSMENT record answers 409 `encounter-drafts-not-enabled` until the human confirms the one-per-day and one-per-case default. Turning it on is removing that guard; the data model does not change.
- **Done, Still needed, part badges** (OQ-45-25, OQ-45-26): computed by the server on every read and save from the snapshot and the values, and returned (`status` per section DONE, REQUIRED, OPTIONAL; `copyPendingConfirmation`; `parts[].requiredDone/requiredTotal`; `stillNeeded`; `readyToComplete`). A section is Done when every required field whose value kind is fillable (not DISPLAY_ONLY or LATER) has a non-empty value; with no such required field, when at least one fillable field has a value; never while a copied value waits for confirmation. The client may recompute live with the same rule; the server's answer wins.
- **Copy from last visit:** at first open only, for sections with `copy` on, from the patient's latest SIGNED record with the same department key (none exist until SAH-51). Marked `copiedFrom`; `confirmCopy: true` in a save confirms.
- **Partitioning:** expected above 50 million rows in the first year at target scale; the db-engineer plans monthly range partitions of the record table by `created_at` from the start (uniqueness per encounter then needs the partition key in the unique index plus the advisory lock above).

### D9 · How opd reads IPD admissions and Emergency cases without a module cycle

`ipd → opd.api` already exists, so opd must not import `ipd.api`. **Decision: dependency inversion.** `opd.api` declares a port, ipd implements it:

```java
package com.sahaj.opd.api;
public interface EmrEncounterSource {
    Optional<AdmissionFacts> admission(UUID tenantId, UUID admissionId);
    Optional<EmergencyCaseFacts> emergencyCase(UUID tenantId, UUID caseId);

    enum Access { ALLOWED, OUTSIDE_SCOPE, FORBIDDEN }
    record AdmissionFacts(UUID admissionId, UUID patientId, String ipNumber, String status, int dayNumber,
                          UUID admittingDoctorMembershipId, Access access) { }
    record EmergencyCaseFacts(UUID caseId, UUID patientId /* null until admitted */, String status, int triageLevel,
                              String triageLevelName, String displayName, Integer age, String sex, Access access) { }
}
```

- The ipd implementation runs in the caller's transaction, builds the caller's `PatientScope` and applies `ScopeGuard` exactly as an IPD by-id read (ADR-017 D1, D2): OUTSIDE_SCOPE for a ward-scoped nurse (never reaches here) or a doctor without scope, FORBIDDEN for VISITING_DOCTOR (IPD answers 403 to visiting doctors until 34D), ALLOWED with an active break-glass grant (the grant itself is audited by ipd as today; the EMR open writes its own audit). Another clinic's id is empty (RLS) → opd answers 404.
- opd maps OUTSIDE_SCOPE to 403 `outside-scope` and FORBIDDEN to 403 `forbidden`; refusals write nothing.
- `toString` of both records shows ids only; `displayName` is never logged.
- No change to `docs/api/ipd.yaml`: the IPD → Admitted and IPD → Emergency cards already carry the admission id and the case id, and the "Open EMR" button calls the opd endpoint. The IPD module entitlement is read through `platform.api.ModuleEntitlementPort`: OFF → 403 `module-off`; READ_ONLY → an existing record opens read-only, no new record (409 `ipd-read-only`); TRIAL and ON → normal.
- A discharged admission or a closed Emergency case: an existing record opens read-only (`readOnlyReason ENCOUNTER_CLOSED`); with no record → 409 `encounter-closed` (SAH-52-1).

### D10 · Queue-card entry and the OPD stage

- The "Open EMR" button (one label, OQ-45-22) shows for DOCTOR, VISITING_DOCTOR and OWNER with doctor details. The client knows this from `GET /emr/me` (200, or 403 `doctor-details-missing` / `forbidden`). OPD own-patient rule: DOCTOR and VISITING_DOCTOR may open only visits whose `doctorMembershipId` is their membership (403 `not-own-patient`); OWNER with doctor details may open any. IPD and ER follow D9.
- Queue card fields (additive on `Visit`): `emrRecordId` (null before the first open) and `emrStatus` NONE, DRAFT, SIGNED. Signed records open read-only (SAH-46-1). "Consult done" is unchanged (SO-45-27).
- Stage on open (SAH-46-2): WAITING or VITALS → WITH_DOCTOR through the existing opd stage service (skip rules, fee timing); the open answers `stageOutcome` MOVED, ALREADY_WITH_DOCTOR, ALREADY_UPDATED (someone moved it first or the workflow refused; the EMR still opens) or NOT_APPLICABLE (other stages, IPD, ER). Repeating the open is safe.
- Visit type (SAH-52-2): `PATCH /visits/{id}/visit-type` (doctor and owner with doctor details, own patient), values NEW, FOLLOW_UP, REVISIT; EMERGENCY answers 400 `visit-type-not-available` until SAH-67. `opd.visit.visit_type` gains REVISIT (widening check, additive). Billing effect is SAH-68.
- Admission advice (SAH-52-4): `POST /emr/records/{id}/admission-advice` on OPD and ER records sets `admissionAdvised` and an optional referral line (≤ 300, for the case paper); IPD records → 400 `not-applicable`. The admission sheet itself is ipd's existing screen; its pre-fill is SAH-67.

### D11 · Permissions (ADR-019 D8 kept: fixed in code, not grid cells)

| Permission | Who | Operations |
| --- | --- | --- |
| `clinic.emr_setup.edit` | OWNER | draft, publish, add department, department doctor options |
| `clinic.consultation.write` | DOCTOR, VISITING_DOCTOR, OWNER with doctor details (fresh read, `EmrAccess`) | `/emr/me`, quick options, every `/emr/records/**` operation, `PATCH /visits/{id}/visit-type` |
| read | any clinic role | library, starters, departments list and view, reset target, doctor layout, versions, masters |

Nurse, front desk, billing, pharmacist and counsellor never open or read an EMR record (403). "Who can fill" is stored and shown only (OQ-45-14); see Human items for the nurse vitals point. Phase 3 reserves `clinic.emr_forms.edit` and `clinic.emr_forms.approve` (OWNER) for SAH-59; decided there.

### D12 · Audit (through `platform.api.AuditLog.record`, same transaction)

| Action | entityType / id | before / after (setup values or ids only) |
| --- | --- | --- |
| `EMR_DEPARTMENT_DRAFT_SAVED` | EMR_DEPARTMENT / department row id | `{departmentKey, revision, changes: [{sectionKey, property, before, after}], moved: [sectionKey]}` |
| `EMR_DEPARTMENT_RESET` | same | the above plus `{sectionsOn, requiredOn}` before and after |
| `EMR_DEPARTMENT_PUBLISHED` | same | `{departmentKey, versionBefore, versionAfter, changes}` against the previous version (or the starter) |
| `EMR_DEPARTMENT_CREATED` | same | after: `{departmentKey, name, sourceKind, starterKey, copiedFromKey, doctorMembershipIds, rooms}` |
| `EMR_RECORD_OPENED` | EMR_RECORD / record id | after: `{encounterType, encounterId, departmentKey, layoutKind, versionNo, created: boolean}` |
| `EMR_RECORD_DRAFT_SAVED` | same | after: `{revision, sectionKeys}` (keys only, never values) |
| `EMR_RECORD_DEPARTMENT_SWITCHED` | same | `{departmentKey, versionNo}` before and after |
| `EMR_VISIT_TYPE_CHANGED` | OPD_VISIT / visit id | `{visitType}` before and after |
| `EMR_ADMISSION_ADVISED` | EMR_RECORD / record id | after: `{withReferral: boolean}` |

A request that changes nothing writes no entry (opens always write one). No patient name, mobile, ABHA number, field value, referral text or free text in any audit value or log line; department names and room labels are clinic setup text, not patient data.

### D13 · What stays from SAH-43 and what changes (additive)

Stays: masters and search (ADR-019 D3), usage ranking and its recorder (D10), `GET /emr/me` (D9, new shape), `platform.api` ports (D7), `EmrAccess`.
Changes:
1. The specialty hint on the three new masters and the `specialty` query parameter of `/emr/masters/{master}`, `/emr/quick-options/{master}` and `GET /diagnoses` take a **starter department key** (15 values) instead of the four template codes. Seed hints map gp → `gm`, dental → `dental`, paeds → `paeds`, homeo → null (the three homeopathy complaints become general). `opd.diagnosis.specialty` (SAH-7, merged, eight platform codes) is not changed; the server maps the department key to it: gm → gp, og → gynae, derm → derma, ayush → ayur, dental, paeds, physio as is; other keys give no hint ordering. The EMR passes the record's `starterKey`.
2. Advice gets Marathi and Hindi text: `nameMr`, `nameHi` (nullable, 1 to 200) on the advice master and in quick options for advice. The seed leaves them null (no translations are drawn; see Human items). Print falls back to English when null. Case paper headings in Marathi and Hindi (drawn in #CasePaper) are fixed UI labels of SAH-55, not data.
3. "My favourites" (SAH-60): stays the clinic's `opd.medicine_favourite` (SAH-7) unless the human asks for per-doctor favourites; nothing in phases 1 and 2.
4. `GET /emr/me` returns the department choice (D7) instead of the template choice.
5. Removed: `/emr/templates`, `/emr/templates/{templateKey}`, `…/override`, `…/reset`, `/emr/settings` and their schemas; the classes `EmrTemplateService`, `EmrTemplateComposer`, `EmrTemplateRepository`, `EmrSetupRepository`, `EmrSettingsService`, `EmrSettingsRepository`, `EmrTemplateController`, `EmrSettingsController` and their JDBC adapters are replaced; `EmrTemplateResolver` becomes the D7 department resolver.

### D14 · Patient header data (SAH-47-1)

From `patient.api.PatientClinicalPort` (UHID, name, age, sex, allergy text) plus one additive field `chronicText` (the patient record's existing `chronic` value) — a patient-module change for the java-developer. Allergy tag only when allergy text is recorded (OQ-45-23); "Last visit: n days ago" and "nth visit" from opd's own visits; doctor name from `TeamMemberPort`; room = the department's first room label. "ABHA linked" is not returned (DEC-040).

### D15 · Extension points for later phases (not built now)

- Layout sections carry `source: LIBRARY`; SAH-59 adds `FORM` with form id and form version (same immutability rule).
- Specialty editors store richer values under their section keys with their own `schemaVersion` (D3 STRUCTURED).
- SAH-51 adds SIGNED, signer, addendum rows, and calls `EmrUsageRecorder` in the sign transaction (ADR-019 D10).
- WhatsApp and ABHA (DEC-040): the EMR exposes nothing yet. When DEC-040 is answered, the integration side reads signed records through a new read port in `opd.api` (for example `SignedEmrRecordReader`); the EMR only offers the in-app button with sending switched off. No outbound call, credential or queue is added by this ADR.
- Breakpoints for the right panel (SAH-47-6): 1180 px (panel below the entry area at and under this width) and 720 px (single column), as the BRD says.

---

## Options considered (main ones)

| Question | Chosen | Rejected |
| --- | --- | --- |
| ADR-019 revise or new ADR | New ADR-020, ADR-019 marked "superseded in part" | Rewriting ADR-019 (loses the record of what DEC-038 approved) |
| Layout storage | JSON document with `schemaVersion` for draft and versions; typed global rows for library and starters (seed is tested row by row) | Typed tenant rows per section setting (more rows and joins, no gain: always read and written whole) |
| Snapshot | Reference to an immutable version or starter layout | Copy per record (terabytes a year); reference to a mutable library (breaks SAH-53-1) |
| Record model | One model for three encounters | Three models; EMR in ipd for IPD |
| Value storage | One JSON document per record | Typed rows; one row per section |
| opd reading ipd | Port in `opd.api` implemented by ipd | `ipd.api` port (module cycle); domain events (a read cannot be an event) |
| Reset | Screen action, audited at the next save | Server reset with stored undo copy |

## Consequences

Positive: one model for the whole programme; visits are protected from later setup changes by immutability; no module cycle; IPD scope and break-glass reused, not rebuilt; small records at national scale.
Negative / accepted: no SQL over clinical values; a second owner editing any section of the same department gets 409; reset audit happens at save time, not at the tap; the PCPNDT lock and the record-per-day default are defaults until the human answers OQ-45-29 and OQ-43-27; IPD and ER drafts are switched off until OQ-43-27.

---

## Data needed (for the db-engineer)

All in schema `opd`. No foreign key across modules except `platform.tenant` and `patient.patient`. Seeds: starter content, table comments say "Starter list, not clinically reviewed" and, for rule values, "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed".

Global (no `tenant_id`, no RLS, SELECT only for `sahaj_app`, PUBLIC nothing):

| Table | Fields | Notes |
| --- | --- | --- |
| `emr_section` | `section_key` pk, `name` 1–120, `kind` COMMON/SPECIALTY/COMPLIANCE, `description` ≤ 500, `always_shown` bool, `created_at` | 100 rows (14/78/8); `always_shown` only `allergy` |
| `emr_section_field` | `section_key` fk, `field_key`, `position`, `label` 1–120, `field_type` (30 values, check), `meta` ≤ 200, `required`, `options_source` NONE/FIELD/MASTER/SERVICES, `options_master` null or 5 masters (set iff MASTER), `allow_free_text`, `min_value`/`max_value numeric` null, `columns jsonb` null; pk (`section_key`, `field_key`), unique (`section_key`, `position`) | from the mockup `S` field strings; FALLBACK fields where none |
| `emr_section_field_option` | `section_key`, `field_key`, `option_key`, `position`, `label` 1–80, `is_none`; pk the four keys; one `is_none` per field (partial unique) | from list-like meta; "None"/"Nothing abnormal" on required choice fields (exceptions listed in the design note) |
| `emr_starter_department` | `starter_key` pk (15 values), `name`, `initials`, `colour`, `description` null, `consent_flag`, `pcpndt_flag`, `position`, `current_layout_version int` | |
| `emr_starter_department_section` | `starter_key`, `section_key`, `position`, `part`, `shown`, `required`, `print`, `copy_from_last`, `collapsed`, `who_doctor`, `who_nurse`, `who_reception`, `always_shown`; pk (`starter_key`, `section_key`), unique (`starter_key`, `position`); check `shown or not required` | 341 rows; `always_shown` only (`og`, `pcpndt`) |
| `emr_starter_layout` | `starter_key`, `layout_version int ≥ 1`, `layout jsonb` (schemaVersion 1, D6 shape with copied definitions), `created_at`; pk (`starter_key`, `layout_version`) | **immutable** (trigger deny update, delete, truncate); 15 rows, version 1, built in SQL from the two tables above in the same migration |
| `emr_department_alias` | `alias_normalised` pk (normalised check as `emr_specialty_alias`), `starter_key` | names, initials and titles of D7 |

Tenant (`tenant_id uuid not null default platform.current_tenant()` fk `platform.tenant`, ENABLE + FORCE RLS, policy on `platform.current_tenant()`, tenant-first indexes, `created_at/by`, `updated_at/by`):

| Table | Fields | Keys, grants, notes |
| --- | --- | --- |
| `emr_department` | `id uuid`, `department_key` (`^[a-z][a-z0-9_]{1,39}$`), `name` 1–60, `initials`, `colour`, `starter_key` null (15 values), `source_kind` STARTER/BLANK/COPY, `copied_from_key` null, `room_labels text[]` (≤ 10, each 1–40), `draft_layout jsonb` null (settings only, `schemaVersion` inside), `draft_saved_at`, `draft_saved_by`, `published_version int ≥ 0`, `revision bigint ≥ 0` | unique (`tenant_id`, `department_key`), unique (`tenant_id`, `lower(name)`); check STARTER ⇒ `department_key = starter_key`; select, insert, update; no delete. ≤ 65 rows per clinic |
| `emr_department_version` | `id`, `department_id` fk, `version_no ≥ 1`, `layout jsonb` (full, with section definitions), `published_at`, `published_by` | unique (`tenant_id`, `department_id`, `version_no`); **immutable** (trigger) and grants select, insert only; kept for ever |
| `emr_department_doctor` | `department_id` fk, `doctor_membership_id` (no fk, cross-module) | pk (`tenant_id`, `department_id`, `doctor_membership_id`), index (`tenant_id`, `doctor_membership_id`) for resolution; select, insert |
| `emr_record` | `id`, `encounter_type` OPD_VISIT/IPD_ROUND/ER_ASSESSMENT, `opd_visit_id` null fk `opd.visit`, `ipd_admission_id` null, `round_date date` null, `er_case_id` null, `patient_id` null fk `patient.patient`, `opened_by_membership_id`, `department_key`, `department_name`, `starter_key` null, `layout_kind` DEPARTMENT_VERSION/STARTER_LAYOUT, `department_version_id` null, `starter_layout_version` null, `choice_source`, `status` DRAFT (SIGNED later), `signed_at`, `signed_by` null, `sections jsonb` (`schemaVersion` inside), `ui_part`, `ui_section_key`, `admission_advised bool`, `referral_line` ≤ 300 null, `revision bigint` | checks: exactly the ids of its type set; `patient_id` not null unless ER; layout ids match `layout_kind`. Unique per encounter (OPD: visit; IPD: admission + round date; ER: case) — with monthly partitions by `created_at`, include it and rely on the advisory lock. Index (`tenant_id`, `patient_id`, `department_key`, `signed_at desc`) where SIGNED (copy from last visit, Past visits later); index (`tenant_id`, `patient_id`, `created_at desc`). Select, insert, update; never delete |

Changes to existing or unmerged tables: masters' hint check → 15 starter keys and re-mapped seed (D13); `emr_advice` gets `name_mr`, `name_hi` (null or 1–200); `opd.visit.ck_visit_type` widened to NEW, FOLLOW_UP, REVISIT (merged table, additive). Retired: `emr_template`, `emr_template_section`, `emr_template_option`, `emr_specialty_alias`, `emr_template_setup`, `emr_template_override`, `emr_settings`, function `opd.deny_emr_template_change` (see the rework plan). Kept: `emr_item_usage`, the three masters.
Retention: library, starters, starter layouts, versions: for ever. Departments: life of the clinic. EMR records: clinical, never hard-deleted; kept for the life of the clinic in phase 1 (legal retention period is a human decision). Expected rows: records 100 to 180 million a year at target scale → monthly partitioning planned now.

---

## Rework of the unmerged SAH-44 migrations (plan for the human; nothing has been run)

What is affected: all four files of 8 Dec, not only 1020 and 1030. `V2026_12_08_1000` creates the SAH-43 masters **and** the SAH-44 template tables, alias table and trigger function, with four-code checks; `V2026_12_08_1010` seeds the masters with four-code hints; `V2026_12_08_1020` seeds the templates; `V2026_12_08_1030` creates the SAH-44 setup, override and settings tables **and** the SAH-43 usage table. Flyway records them in `platform.flyway_schema_history` as versions `2026.12.08.1000` to `2026.12.08.1030`.

**Option (a) · Rewrite the unmerged files, recreate `sahaj_test`.** The db-engineer edits 1000 (keep masters with the 15-key hint check and the advice language columns; remove template tables, alias, trigger function), 1010 (re-mapped hints), 1030 (keep only `emr_item_usage`), deletes 1020, and adds new files with new timestamps for D2 to D8. Flyway refuses to start on any database that ran the old files (checksum mismatch, missing 1020). `flyway repair` is **not** enough: it would accept the new checksums but leave the old tables and the old check constraints in place, so the schema would no longer match the files. Flyway `clean` is not usable either: the configured schema list is `platform` only, so it would leave the `opd` tables. The database must be recreated.
- Pros: `main` never sees the withdrawn design; no create-then-drop in the history for ever; no destructive migration ships.
- Cons: `sahaj_test` is dropped (test data only, rebuilt by the next `mvn verify`); if `sahaj_dev` ran the old files, it must be recreated too (losing local demo data) or cleaned by hand.
- Commands (human, PowerShell, repo root; `$env:PGPASSWORD` = `PG_SUPERUSER_PASSWORD` from `.env`):
  ```
  psql -h localhost -U postgres -d postgres -c "DROP DATABASE IF EXISTS sahaj_test WITH (FORCE);"
  node scripts/setup/create-database.mjs
  mvn -f backend/pom.xml verify
  ```

**Option (b) · Leave the four files, add a migration that retires SAH-44.** A new file (for example `V2026_12_09_1000__opd_emr_retire_sah44.sql`) drops `emr_template_override`, `emr_template_setup`, `emr_settings`, `emr_template_option`, `emr_template_section`, `emr_template`, `emr_specialty_alias` and `opd.deny_emr_template_change()` (DROP TABLE does not fire the immutability triggers), updates master hints (gp → gm, homeo → null), replaces the three hint checks, adds the advice language columns; later files add the new tables.
- Pros: no database is dropped anywhere; works whether or not `sahaj_dev` ran the files; Flyway validation passes everywhere.
- Cons: a destructive DROP in one step, against the expand-and-contract rule (acceptable only because the tables never shipped and no code reads them after the rework; still needs the human's approval as a destructive change); the history keeps a dead create, seed and drop of SAH-44 for ever; QA must remove the dropped tables from `RowLevelSecurityCatalogIT`'s allow-list.
- Commands: none beyond the normal `mvn -f backend/pom.xml verify` and the next dev start.

**Is `sahaj_dev` affected?** The dev server applies migrations only when it starts (no automatic restart is configured). If it was started before the 8 Dec files existed and not restarted since, it does not have them. Check, read-only:
```
psql -h localhost -U postgres -d sahaj_dev -c "select version, description, installed_on, success from platform.flyway_schema_history where version like '2026.12.08%' order by installed_rank;"
```
Zero rows: `sahaj_dev` is unaffected. Do not restart the dev server on this branch until the rework migrations are in place.

**Recommendation:** run the check. **Zero rows → option (a)** (only the disposable test database is recreated, the history stays clean). **Rows found → option (b)**, unless the human is happy to recreate `sahaj_dev` too. Either way the human runs the commands; agents do not.
