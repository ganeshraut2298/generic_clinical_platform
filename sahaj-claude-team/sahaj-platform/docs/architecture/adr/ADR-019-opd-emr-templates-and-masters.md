# ADR-019 · Doctor EMR: masters, usage ranking, specialty templates as data, clinic overrides, EMR settings and the section snapshot

Stories: SAH-43 (EMR masters, SAH-43-1 to SAH-43-4) and SAH-44 (templates, overrides, settings, snapshot, SAH-44-1 to SAH-44-4)
Mockup screen: none (data only). Nearest: Sahaj admin portal → Templates and master data; Clinic setup → Specialty template, Case paper and Rx.
Module: opd (reads platform through `platform.api` only)
Status: PROPOSED (11 Nov 2026), awaiting `SAH-43 · contract · APPROVED` and `SAH-44 · contract · APPROVED`
Deciders: human (product owner), tech lead; recorded by solution-architect
Related: `docs/requirements/SAH-43.md` (BR1 to BR28, OQ-43-n, D-n, C-n), `docs/stories/SAH-43.md`, `docs/stories/SAH-44.md`,
`docs/handoffs/SAH-43-input-doctor-emr-brd.md`, DEC-036, DEC-037, ADR-002 (D3 global tables, D6 authorization, D7 audit),
`docs/api/opd.yaml` (0.2.0)

---

## Context

The BRD "Doctor EMR" (11 Nov 2026) starts with master configuration: quick-option masters with per-doctor
ranking (SAH-43) and four specialty templates held as data, with clinic overrides, clinic EMR settings and a
rule that a visit keeps the section list it was opened with (SAH-44). No screen is built in these two stories;
the EMR screens and Clinic setup → Doctor EMR wait for the human's mockup file (DEC-036).

What already exists and is reused, not rebuilt (checked in the migrations and code on 11 Nov 2026):

| Need | Exists | Where |
| --- | --- | --- |
| Diagnoses with ICD-10 | `opd.diagnosis` (global, PK `code`, `specialty` hint, ~50 starter rows, SELECT-only grant, no RLS) | SAH-7 |
| Medicines | `opd.medicine` (global, uuid id, SELECT-only), search `GET /medicines` with favourites first, page size 20 | SAH-7 |
| Clinic favourites | `opd.medicine_favourite` (tenant, RLS) | SAH-7 |
| Doctor details | `platform.doctor_profile` (per membership: specialty free text ≤ 120, optional; degree, registration no optional) | SAH-4, SAH-6 |
| Clinic specialty | `platform.clinic_profile.specialty_code` ∈ gp, dental, paeds, homeo (nullable) | SAH-4, SAH-7 |
| Role checks | Spring `hasRole`/`hasAnyRole` per endpoint plus `platform.api.OwnerGuard` (fresh membership read) | SAH-2, SAH-7 |
| "Who can do what" grid | `platform.role_permission_default` + `role_permission`, keys token, vitals, collect, cancel, refund, revenue, export (database check on the key list) | SAH-6 |
| Audit | `platform.api.AuditLog.record(action, entityType, entityId, before, after)`, same transaction | SAH-2, ADR-002 D7 |
| Doctor reads from opd | `platform.api.TeamMemberPort` (`find`, `doctorSpecialty`, …) | SAH-9, SAH-27 |

---

## Decisions

### D1 · Module rule: everything is in `opd`; no new module, library or external service

All EMR masters, templates, overrides, settings, usage counts and (later) the EMR visit live in the `opd`
schema and the `com.sahaj.opd` package. `opd` reads doctor details and the clinic specialty only through
`platform.api` ports (D7). No new module, no new Maven dependency, no new external service, no cache, no
search engine. Prefix search with `text_pattern_ops` indexes (the SAH-7 pattern) is enough for lists of a few
hundred to a few thousand rows. I found nothing in SAH-43 or SAH-44 that needs more; if a full licensed ICD-10
release (about 12,000 codes, OQ-43-7) or a vendor drug master (OQ-43-6) later needs trigram or full-text
search, that is a new ADR, and `pg_trgm` would be an extension the human must approve in
`create-database.mjs` (human-owned).

### D2 · Two data kinds: global reference (read-only to the app) and tenant data (RLS)

| Data | Kind | Rule |
| --- | --- | --- |
| Complaints, investigations, advice masters (new) | Global reference | No `tenant_id`, no RLS, `GRANT SELECT` only to `sahaj_app`, changed by migration only (same as `opd.medicine`, `opd.diagnosis`) |
| Diagnoses, medicines (existing) | Global reference | Unchanged |
| EMR templates, sections, template chip options, specialty aliases (new) | Global reference | Same as above, plus versions are immutable (D4) |
| Clinic template overrides, clinic template revision, EMR settings, per-doctor usage (new) | Tenant data | `tenant_id uuid not null default platform.current_tenant()`, `ENABLE` + `FORCE ROW LEVEL SECURITY`, `p_tenant` policy, tenant-first indexes (ADR-002 D3) |
| Medicine favourites (existing) | Tenant data | Unchanged |

**Writes to global data by any clinic role answer 403.** The contract has no write operation on
`/emr/masters/**`, `/emr/quick-options/**` or `/emr/templates` (list and definition). The stories require
403, not the framework's 405, for a write attempt, so the developer adds one handler that answers any
`POST`, `PUT`, `PATCH` or `DELETE` on those read-only paths with 403 problem type `forbidden`. The database
grant (SELECT only) is the second layer.

No clinic-added master rows in release 1 (OQ-43-10 default), no Marathi or Hindi names (Later).

### D3 · Masters and search

- New global tables: complaints, investigations, advice. Each row: stable id (uuid, derived from the name so
  every environment gets the same id, as for `opd.medicine`), name 1 to 200 characters (unique ignoring case),
  optional specialty hint (gp, dental, paeds, homeo; null = general), sort order, `created_at`.
- Search endpoint for the three new masters: `GET /emr/masters/{master}` (master ∈ complaints, investigations,
  advice), prefix match on the name ignoring case, page size 20, `page` from 0, answer `{items, total, page,
  pageSize}`. With `specialty`, items whose hint equals it come first; nothing is hidden.
- Diagnoses and medicines keep their SAH-7 search unchanged. Two additive, optional query parameters are
  added to `GET /diagnoses`: `specialty` (hint first) and `page` (default 0 gives exactly today's answer). The
  answer stays an array, so no client breaks; a client asks for the next page until it receives fewer than 20.
  `GET /medicines` is not changed at all (SAH-43-1).
- Input rules for every search: `q` is trimmed; empty means "first page in master order"; at most 100
  characters, else 400 `validation`; `%`, `_` and `\` are matched literally (escaped with `ESCAPE '\'`, bound
  as a parameter, never concatenated); only spaces = empty. Never a 500, never a wider match than typed.
- Master order (the "master's own order", OQ-43-9 default): specialty hint first when a specialty is given,
  then `sort_order`, then name A to Z. For medicines the master order is the SAH-7 order (clinic favourites
  first, then name).

### D4 · Templates are data: global, versioned, immutable once shipped

- Template key = the specialty code already used by `platform.specialty` and the clinic profile: `gp`
  (General practice), `dental`, `paeds` (Paediatrics), `homeo` (Homeopathy). Reusing the codes avoids a
  mapping table between "clinic specialty" and "template" (C-17 consistent). A specialty with no EMR template
  (ayur, physio, derma, gynae, ENT, Oncology, Surgery) answers 404 `template-not-found` with a clear detail,
  never an empty template.
- A template version is a set of rows `(template_key, version)` with its sections and chip options. A new
  version is new rows; old versions stay. **A shipped version is never updated or deleted**, enforced by a
  `BEFORE UPDATE OR DELETE` trigger on the three template tables (same idea as the audit tables, ADR-002 D7),
  so a snapshot can safely refer to `(template_key, version, section_key)` for its option list.
- The newest version per key is the one used for visits opened after it ships (OQ-43-17 default). No Draft,
  no Publish, no "Update available" card (D-13, SO-44-3).
- Section fields: key, label, part (`ASSESSMENT` or `TREATMENT`), position, required default, `hideable`,
  editor type, icon key (may be empty, OQ-43-20), options source. Section keys are unique within a template
  version; constraints refuse anything else.
- **Editor types** (FR-11, eleven values): `COMPLAINTS`, `VITALS_STEPPERS`, `SIGNS`, `SYSTEMS`, `DIAGNOSIS`,
  `PRESCRIPTION`, `TOOTH_CHART`, `TREATMENT_PLAN`, `SERVICES`, `ADVICE`, `CHIP_LIST`.
- **Options source**: `MASTER` (with the master: complaints, diagnoses, medicines, investigations, advice),
  `TEMPLATE` (the version's own chip options, at least one), `TARIFF` (the clinic's active services, SAH-7),
  `NONE`. A template chip option has key, label, style (`NORMAL` or `ALERT`, the red allergy and alert chips)
  and `isNone` (the "None" or "Nothing abnormal" option, required in every required `CHIP_LIST` section by
  OQ-43-19).
- **Section keys and editor types are the business analyst's reading of BRD sections 4 and 5 (SO-44-10)**,
  copied as-is from the table in `docs/stories/SAH-44.md` (General practice 11 sections, 6 required; Dental 11,
  7; Paediatrics 9, 4; Homeopathy 7, 6). They become stable keys the moment a visit stores a snapshot, so the
  human should confirm them before SAH-46 ships. Renaming a key later means a new template version, not an
  update.
- **Sections that cannot be hidden** (OQ-43-14 default) are data, `hideable = false`: `prescription`,
  `remedy_potency`, `history_allergies`, `medical_alerts`. Paediatrics and Homeopathy have no allergy section
  in the BRD; that stays an open question for the human (patient safety).
- `platform.specialty_template` (SAH-7 features and starter services) is not touched (D-12, D-13: two
  different things).

### D5 · Clinic overrides: Show and Required only, one revision per clinic and template

- One override row per clinic, template key and section key: `shown`, `required`. Nothing else is accepted:
  the request body is closed (`additionalProperties: false`); `label`, `part` or any other field answers 400
  (OQ-43-15). The developer must make these request DTOs fail on unknown properties (Spring ignores them by
  default).
- Effective section = template default, then the override if one exists for that key. No override = shown,
  and required as the template says. Overrides are keyed by section key, not by version, so they survive a new
  template version; an override whose key is no longer in the newest version is ignored, not applied and not
  deleted (OQ-43-17).
- Rules, checked on the server: hidden → never required (BR12); a request `shown=false, required=true` answers
  400 `hidden-section-required`; `shown=false` alone stores `required=false` in the same change; hiding a
  section whose `hideable` is false answers 400 `section-not-hideable` and changes nothing.
- An override equal to the template default is stored as "no override" (the row is deleted), so "Reset to
  template" and "the section shows as overridden" stay meaningful.
- **Optimistic locking: one revision per clinic and template**, held in a small tenant row
  (`emr_template_setup`) created on the first change. Every change and every reset that changes something
  increments it; the client sends the revision it read; a different revision answers 409
  `emr-setup-changed`. Chosen over a version per override row because a reset deletes override rows, and a
  per-row version would restart at 0 and let a stale editor overwrite a newer change (the ABA case). Cost:
  two owners editing different sections of the same template at the same moment also get a 409 for the
  second; acceptable for an owner-only setup page. A first change from two owners at once collides on the
  unique key of the revision row and also answers 409.
- Reset to template = delete the clinic's overrides for that template in one transaction, revision + 1, one
  audit entry. A reset with no overrides answers 200, changes nothing, writes no audit entry and does not
  change the revision (SAH-44-2).
- Changes apply only to visits opened afterwards (BR14) because visits read their snapshot (D6), never the
  live overrides.
- D-4 / SO-44-5: these Show switches control the doctor's EMR screen only. The nine case paper switches of
  SAH-7 stay the print control. How the two combine on the live case paper is SAH-50's decision.

### D6 · The section snapshot on a visit (shape fixed now, storage arrives with SAH-46)

When a doctor first opens a visit's EMR (SAH-46) the visit stores one snapshot. Shape (also in `opd.yaml` as
`EmrTemplateSnapshot`, informational until SAH-46 adds an operation):

```json
{
  "schemaVersion": 1,
  "templateKey": "gp",
  "templateVersion": 1,
  "choiceSource": "DOCTOR_SPECIALTY",
  "takenAt": "2026-11-11T05:35:00Z",
  "sections": [
    { "key": "chief_complaints", "label": "Chief complaints", "part": "ASSESSMENT", "position": 1,
      "required": true, "editorType": "COMPLAINTS", "iconKey": null,
      "options": { "source": "MASTER", "master": "complaints" } }
  ]
}
```

- Only shown sections are stored, in template order, with the effective required flag. Labels are copied.
  Template chip options are **not** copied: the snapshot refers to them by `(templateKey, templateVersion,
  key)`, which is safe because versions are immutable (D4). Master and tariff items are never copied into the
  snapshot; the visit's own entries keep their own copy of the name (SAH-43-1, SAH-43-4) when SAH-47 builds
  them.
- Storage (for the db-engineer in SAH-46, not now): a `jsonb` column on the EMR visit row with the
  `schema_version` inside, as `.claude/rules/postgresql.md` allows `jsonb` for configurable forms. Tenant data.
- Rules: Required-done and the finish gate read the snapshot only (BR16, SAH-47, SAH-51). Setup changes and new
  template versions never alter a snapshot. A signed visit's snapshot can never change (409
  `emr-visit-signed`). A draft visit may switch template (OQ-43-18 default): a new snapshot replaces the old
  one, `choiceSource = SWITCHED`, no entered data is deleted, sections not in the new template are kept and not
  shown, one audit entry `EMR_TEMPLATE_SWITCHED` with visit id and old and new template key. The switch is not
  saved as the doctor's default.
- Template choice (BR15, OQ-43-13): (1) the doctor's free-text specialty, normalised (trim, lower case, runs of
  spaces made one), looked up in the global alias table; (2) else the clinic's `specialty_code`; (3) else `gp`.
  `choiceSource` records which: `DOCTOR_SPECIALTY`, `CLINIC_SPECIALTY`, `DEFAULT` (or `SWITCHED`). Seed aliases
  are only the BRD and mockup names: "general physician", "general practice" → gp; "dental surgeon", "dental" →
  dental; "paediatrician", "paediatrics" → paeds; "homeopath", "homeopathy" → homeo. The product owner may add
  aliases later by migration. No change to Team and access.
- In SAH-44 this is an application service in `com.sahaj.opd.application.emr` (`EmrTemplateResolver`:
  `choose(tenantId, doctorMembershipId)` and `snapshot(tenantId, templateKey)`), tested directly. The doctor's
  current choice is visible over REST through `GET /emr/me` (D9).

### D7 · How opd reads doctor details and the clinic specialty: two `platform.api` additions

`opd` never reads `platform.doctor_profile`, `platform.membership` or `platform.clinic_profile`. Two additions
to `platform.api` (java-developer, platform package):

1. `TeamMemberPort.doctorDetails(UUID tenantId, UUID membershipId)` →
   `Optional<DoctorDetails>` where
   `record DoctorDetails(UUID membershipId, String name, String role, String membershipStatus, String specialty, String degree, String registrationNo)`.
   Empty when the membership is another clinic's, unknown, or has no doctor profile row (an owner who is not a
   doctor). Returns rows for removed memberships too (the doctor row is never deleted, SAH-4), so an earlier
   record can still print the name. Null or blank values are returned as null; `opd` computes the "missing"
   list (degree, registration number) itself.
2. `ClinicSpecialtyPort.specialtyCode(UUID tenantId)` → `Optional<String>` (gp, dental, paeds, homeo), a new
   one-method port implemented by `platform`.

Both run in the caller's transaction with the tenant already bound (RLS still applies). Neither is exposed over
HTTP from `platform`. The doctor's name and registration number are never logged (BR26).

### D8 · Permissions: two fixed capabilities, not owner-editable cells in the SAH-6 grid

New permission names, in the `clinic.<area>.<action>` shape of ADR-002 D6:

| Permission | Who has it | Used by |
| --- | --- | --- |
| `clinic.consultation.write` ("write EMR") | `DOCTOR` or `VISITING_DOCTOR` with an active membership; `OWNER` only when the owner has a doctor profile row (OQ-43-12 default). Never NURSE, FRONT_DESK, BILLING, PHARMACIST, COUNSELLOR, nor an owner without doctor details. | `GET /emr/me`, `GET /emr/quick-options/{master}`, and every later EMR write (SAH-46 to SAH-52) |
| `clinic.emr_setup.edit` ("change EMR setup") | `OWNER` only (OQ-43-2 default) | `PUT …/override`, `POST …/reset`, `PUT /emr/settings` |
| (read) | any clinic role | masters search, templates, settings (SO-44-6: read-only for non-owners) |

How they map to roles: **fixed in code, not through owner-editable cells of the "Who can do what" grid.**
Reasons: (a) BR2 says only a doctor writes the EMR, and an editable cell would let an owner give it to a nurse;
(b) the grid's key list is a database check and a screen in the mockup (Team and access) that shows seven
columns of actions; adding cells changes a built, mockup-defined screen, which SAH-43-3 says this story does
not do; (c) the owner-as-padlock rule (ADR-002 D6) would otherwise give `clinic.consultation.write` to every
owner, including owners who are not doctors. Implementation: one opd class `EmrAccess` with
`requireConsultationWrite(ClinicUser)` (fresh membership and doctor-profile read through D7, not the token's
role alone, like `OwnerGuard`) and `requireSetupEdit` (delegates to `OwnerGuard`). Endpoints also carry a
`@PreAuthorize` role expression as the first gate. Refusal: 403 `forbidden`; an owner without doctor details
gets 403 `doctor-details-missing` so the UI can say why. The permission names are recorded in the contract as
`x-required-permission` so a later move into a data-driven catalogue keeps the names. Putting them in the grid
is a human decision (NEEDS DECISION N3 in the handoff).

A doctor's own patients versus all patients (BRD section 2) is a patient-scope rule for SAH-46, not a
permission; not decided here.

### D9 · Doctor master read: `GET /emr/me` only

The EMR needs the signed-in doctor's identity (case paper header, template choice). One endpoint returns the
caller's own details only: name, role, specialty as saved, degree, registration number, a `missing` list
(`DEGREE`, `REGISTRATION_NO`; OQ-43-11 default: nothing is blocked) and the template choice with its source.
There is no endpoint to read another doctor's details for the EMR (SAH-43-3: 403 for other roles; a reprint
reads the printed case paper later). Team and access stays the place where an owner reads all doctors.

### D10 · Usage ranking: written only by the sign step, through an internal application service

- Stored per clinic, per doctor membership, per master and per item: `use_count`, `last_used_at`. No patient
  id, no visit id, no text (SAH-43-2). The item is identified by `master` + `item_key` (the uuid as text for
  complaints, investigations, advice and medicines; the ICD-10 code for diagnoses, the existing primary key).
  No foreign key (five masters, one column); a usage row whose item has left the master is ignored on read.
  No foreign key to `platform.membership` either (cross-module rule).
- **Write path: no REST operation.** One opd application service, `EmrUsageRecorder.recordSignedVisit(UUID
  tenantId, UUID doctorMembershipId, Instant signedAt, Set<MasterItemRef> items)`, in
  `com.sahaj.opd.application.emr`, not in `opd.api` (no other module may call it). `MasterItemRef(master,
  itemKey)` can only name a master item, so free text cannot reach it (BR7, structural). It runs with
  `Propagation.MANDATORY` inside the sign transaction of SAH-51, counts each distinct item once per visit
  (it is a `Set`), ignores items not in their master, and upserts with one statement per item:
  `insert … on conflict (tenant_id, doctor_membership_id, master, item_key) do update set use_count =
  use_count + 1, last_used_at = greatest(last_used_at, excluded.last_used_at)`. The row lock of the upsert
  means two visits signed at the same moment both count (no lost update).
- Who may call it: only the SAH-51 sign service. Until then only tests call it. A convention test (ArchUnit or
  Spring Modulith) fails the build if any class other than the sign service and tests depends on it.
  Drafts and addenda never call it (OQ-43-9 default).
- Options considered: (a) a domain event `EmrVisitSigned` with an `@ApplicationModuleListener` in opd:
  rejected, it runs after commit in another thread and another transaction, so a failure silently loses
  counts and the tenant must be re-bound (ADR-002 D4); the publisher and the consumer are the same module, so
  an event buys no decoupling. (b) a public REST call from the browser: rejected, the client could inflate
  counts (SAH-43-2 forbids any public write). (c) **synchronous internal service in the sign transaction:
  chosen.** The count commits or rolls back with the signature.
- Read path: `GET /emr/quick-options/{master}?limit=` (1 to 50, default 10; the chip screen asks for 10). The
  doctor is always the caller's own membership. There is no doctor id parameter and the server never reads
  one, so a request that adds `?doctorMembershipId=…` gets the caller's own ranking (SAH-43-2: "ignored or
  refused"; ignored is chosen). Nobody, not even the owner, reads another doctor's ranking. Order: used items by `use_count` desc, then `last_used_at` desc, then
  never-used items in master order (D3). A doctor with no use gets the master order, never an empty list.
- Usage counts are not audited one by one (they derive from the signed record, which SAH-51 audits).

### D11 · EMR settings per clinic

One row per clinic, created on the first save (lazy, like other settings); defaults before that:
`sectionPanel = COLLAPSED`, `rightPanelFirst = CASE_PAPER`, `defaultPart = ASSESSMENT`. `defaultPart` is
stored with a database check that only allows `ASSESSMENT` and is not accepted in the request body (OQ-43-16):
a body with `defaultPart` answers 400. Optimistic locking with `version` (0 before the first save); stale →
409 `emr-setup-changed`. Paper size and language stay in Case paper and Rx (SAH-7) and are not copied. The
doctor's own rail toggle is per visit and never stored here (FR-06).

### D12 · Audit

Through `platform.api.AuditLog.record` in the same transaction (ADR-002 D7):

| Action | entityType | before / after |
| --- | --- | --- |
| `EMR_TEMPLATE_OVERRIDE_CHANGED` | `EMR_TEMPLATE_OVERRIDE` | `{templateKey, sectionKey, shown, required}` before and after (setup values) |
| `EMR_TEMPLATE_RESET` | `EMR_TEMPLATE_SETUP` | `{templateKey, sectionsOn, requiredOn, overrides}` before and after |
| `EMR_SETTINGS_UPDATED` | `EMR_SETTINGS` | each changed setting, before and after |
| `EMR_TEMPLATE_SWITCHED` (SAH-46) | `EMR_VISIT` | `{templateKey}` before and after, entity id = visit id |

A request that changes nothing writes no entry. Clinical content (from SAH-46 on) is audited by ids and keys,
never text (D-8 default, project rule). No log line carries a doctor's name, registration number, a patient
detail or any free text a doctor typed; search text `q` is not logged either (it could be a patient's words).

---

## Consequences

Positive
- One module, no new technology; every rule above is a constraint, a grant, a trigger or one service class.
- Visits are protected from later setup and template changes by construction (snapshot + immutable versions).
- Counts cannot be forged from a browser and cannot carry free text (type of the recorder's argument).
- Reusing the specialty codes as template keys means no mapping table between clinic specialty and template.

Negative / accepted
- One revision per clinic and template gives a 409 to a second owner editing a different section at the same
  moment.
- Fixed capabilities in code mean an owner cannot tune who writes the EMR; that is BR2's intent, and the
  human can still decide to expose them (N3).
- Section keys become permanent once SAH-46 stores snapshots; they must be confirmed by the human first.
- Starter content for complaints, investigations, advice and template chips is not clinically reviewed
  (OQ-43-8) and the ICD-10 source is unnamed (OQ-43-7): a release gate, not a code gate.

---

## Data needed (for the db-engineer, SAH-43 and SAH-44)

All in schema `opd`. No foreign key into another module except `platform.tenant`. Seeds are reference data,
starter lists, each table comment says "Starter list, not clinically reviewed: a clinician must review it before
real use."

Global (no `tenant_id`, no RLS, `revoke all … from sahaj_app, public; grant select … to sahaj_app`):

| Table | Fields | Notes |
| --- | --- | --- |
| `emr_complaint` (or `complaint`) | `id uuid pk` (derived from name), `name text` 1–200, `specialty_hint text null` ∈ gp/dental/paeds/homeo, `sort_order int`, `created_at` | unique `lower(name) text_pattern_ops`; ~50 starter rows |
| `emr_investigation` | same | starter from the mockup Investigation master (13 tests) plus a few, ~50 at most |
| `emr_advice` | same | ~50 starter rows |
| `emr_template` | `template_key text` ∈ gp/dental/paeds/homeo, `version int ≥ 1`, `name`, `created_at`; pk `(template_key, version)` | 4 rows, version 1 |
| `emr_template_section` | `template_key`, `version`, `section_key` (`^[a-z][a-z0-9_]{1,39}$`), `position`, `label` 1–60, `part` ∈ ASSESSMENT/TREATMENT, `required_default`, `hideable`, `editor_type` (11 values, D4), `icon_key null`, `options_source` ∈ MASTER/TEMPLATE/TARIFF/NONE, `options_master null` ∈ complaints/diagnoses/medicines/investigations/advice; pk `(template_key, version, section_key)`, unique `(template_key, version, position)`, fk to `emr_template` | check: `options_master` set iff source MASTER; 38 rows from the SAH-44 table; `hideable=false` for prescription, remedy_potency, history_allergies, medical_alerts |
| `emr_template_option` | `template_key`, `version`, `section_key`, `option_key`, `position`, `label` 1–80, `style` ∈ NORMAL/ALERT, `is_none bool`; pk `(template_key, version, section_key, option_key)`, fk to section | one `is_none` option in each required CHIP_LIST section; at least one option per TEMPLATE section (checked by a test, not a constraint) |
| `emr_specialty_alias` | `alias_normalised text pk` (lower, trimmed, single spaces), `template_key` | 8 seed rows (D6) |

Trigger on `emr_template`, `emr_template_section`, `emr_template_option`: `BEFORE UPDATE OR DELETE … deny`
(versions are immutable; a change is a new version).

Tenant (`tenant_id uuid not null default platform.current_tenant()` fk `platform.tenant`, `ENABLE` + `FORCE`
RLS, `p_tenant` policy, `created_at/by`, `updated_at/by`):

| Table | Fields | Grants / notes |
| --- | --- | --- |
| `emr_template_setup` | `tenant_id`, `template_key`, `revision bigint not null default 0`; pk or unique `(tenant_id, template_key)` | select, insert, update. Created on the first change. |
| `emr_template_override` | `tenant_id`, `template_key`, `section_key`, `shown bool`, `required bool`; pk `(tenant_id, template_key, section_key)`; `check (shown or not required)` | select, insert, update, delete (reset; setup data, not clinical; audit keeps history). No fk to the global section (keys survive versions). |
| `emr_settings` | `tenant_id` unique, `section_panel` ∈ COLLAPSED/OPEN, `right_panel_first` ∈ CASE_PAPER/PAST_VISITS, `default_part` check = ASSESSMENT, `version bigint` | select, insert, update. Lazy. |
| `emr_item_usage` | `tenant_id`, `doctor_membership_id uuid` (no fk, cross-module), `master` ∈ 5 values, `item_key text` 1–40, `use_count int ≥ 1`, `last_used_at timestamptz`; pk `(tenant_id, doctor_membership_id, master, item_key)` | select, insert, update; no delete. Index `(tenant_id, doctor_membership_id, master, use_count desc, last_used_at desc)` for the ranking read. |

Retention: masters and templates forever (versions kept). Overrides: until reset (audit keeps history).
Settings: life of the clinic. Usage: life of the clinic in phase 1 (no patient data; small: doctors × items).
`GET /diagnoses` with `specialty`: the existing indexes suffice.
