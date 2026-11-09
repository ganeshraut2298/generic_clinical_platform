# ADR-015 · IPD patient card setup: live versions and one draft per clinic, server-owned card rule, list answer minimised by the published setup

Story: SAH-37 (IPD patient card setup; the Admitted cards use it)
Mockup screens: Clinic setup → Patient card (screen-map row 29: `cfgIpCard`, `pcBar`, `pcPreview`, `pcDefault`, `PC_PRESET`, `PC_LAY`, `PC_F`, `PC_S`, `PC_A`, "Published versions", locked safety block at mockup line 8434); IPD → Admitted (`pcCard`, `pcgrid lay-*`, "Customize card")
Module: ipd only (schema `ipd`, package `com.sahaj.ipd`). Existing ports only: `platform.api` (`TeamMemberPort`, `ModuleEntitlementPort`, `AuditLog`, `ActorDirectoryPort`) and `patient.api` (`PatientClinicalPort`, `PatientLookupPort`). No new module, port, event, library or service.
Status: PROPOSED (9 Nov 2026), with the SAH-37 contract. Accepted when the human approves `contract`. Work proceeds on the human's go-ahead of 9 Nov 2026 (`docs/handoffs/SAH-37.md`) and DEC-027 (decided).
Deciders: Ganesh (human), tech-lead; recorded by solution-architect
Related: `docs/requirements/SAH-37.md` (rules 1 to 21), `docs/stories/SAH-37.md` (AC1 to AC66), DEC-027, DEC-021, ADR-013 (D2, D3, D5, D6, D7, D10, D14, D15), ADR-006 (D3 `EarlyWarningScore`), ADR-002 (D7 audit port), `docs/architecture/SAH-37-patient-card-setup.md` (catalogue, validation table, defaults, frontend notes), `docs/api/ipd.yaml`

## Context

1. SAH-36 draws one fixed card from `GET /admissions` (ADR-013). SAH-37 lets the owner shape it (layout, sections, fields, labels, quick actions), keep the changes as a draft, and publish numbered versions.
2. The setup is clinic data shared by all staff. It is read on every 60-second reload of every open Admitted tab (up to 100 hospitals, 150 to 300 admitted patients each).
3. Some items are locked for patient safety (rule 9): fields Patient name, UHID, Alerts, Early warning, Allergies; sections Card header, Workflow status, Vitals, Clinical. A setup sent straight to the server must not be able to remove them.
4. DEC-027 (human, 9 Nov 2026): the default card is the SAH-36 card plus the locked Allergies line; the owner may switch on the vitals readings and Diagnosis; safety labels are fixed; Compact always shows allergy; no Last nursing note; tile marks from the SAH-30 per-reading points.
5. New card facts not in today's list: Diagnosis, the five vital readings with marks, Last recorded (time and recorder), Mobile. IP number, Admission type, Payer, Running bill and Deposit are already on every `AdmissionRow` (ADR-013 D3).
6. **Diagnosis exists.** The admission carries `ipd.admission.reason` (SAH-23; blank stored as "Under evaluation"), which the Discharge sheet and the EMR sheet already show as "Diagnosis" (`DischargeSheet.diagnosis`, `EmrSheet.diagnosis`). It is ipd's own column. Diagnosis stays in the catalogue.

## Decision

### D1 · Stored shape: append-only versions and at most one draft per clinic; v1 is not stored

Two new tenant tables in `ipd` (details in "Data needed"):
- `ipd.card_setup_version`: one row per publish, version 2, 3, ... per clinic. Append-only (update, delete, truncate refused by trigger). The live setup is the row with the highest version.
- `ipd.card_setup_draft`: at most one row per clinic (primary key `tenant_id`), with a `revision` counter for optimistic concurrency. Replaced on Save draft; deleted on Discard and on Publish.

A clinic that never published has no row: the live setup is the **Sahaj standard setup, v1**, built in code (`CardSetupDefaults.standard()`). Reading never creates a row (as the Discharge and Transfer settings). Versions are not restored (out of scope); every version stays readable for the history.

The setup is stored as `jsonb` in a fixed canonical form (D3). Reason: it is a small document (about 3 KB) that is always read and written whole, validated by code, never queried by field, and versioned as a unit. A normalised design (sections, fields, actions tables per version) would mean 40 rows per publish, multi-table writes and joins on the 60-second read, with no query that needs them.

Options rejected: (a) one row with live and draft columns: no history; (b) store v1: a row per clinic for 10,000 OPD clinics that may never use IPD; (c) setup in `platform` settings: the card is ipd's (cross-module data).

### D2 · Who may do what (no change to access control)

| Operation | Roles (token role and membership role both, re-read once per call) | IPD state |
|---|---|---|
| Read live setup, catalogue, standard, templates | OWNER, FRONT_DESK, NURSE, DOCTOR | Trial, On, Read-only |
| Read draft and versions | OWNER | Trial, On, Read-only |
| Save draft, Discard draft, Publish | OWNER | Trial, On (Read-only 409 `ipd-readonly`, OQ-37-10, as `PUT /discharge-settings`) |
| Any | BILLING, PHARMACIST, VISITING_DOCTOR, others: 403 `forbidden`; IPD Off: 403 `module-off` | |

Non-owners never receive the draft: `draft` and `versions` are **absent** from their answer (ADR-013 D3 pattern). The membership re-read uses `TeamMemberPort.findActiveByUserId` (the `OwnerGuard` pattern), so a demoted owner cannot publish with an old token. Check order on writes: role 403, module Off 403, body 400, Read-only 409, then the lock and the concurrency check 409 (D5).

### D3 · The server owns the card rule: catalogue, canonical form, validation, repair

One domain package, `com.sahaj.ipd.domain.card` (no Spring, no I/O), holds the only copy of:
- **`CardCatalogue`**: sections, fields (key, standard name, data group, safety flag, allowed sections), actions, layouts, the 40-character label limit.
- **`CardAccessRule`**: data group → roles that may see it (rule 11) and action → roles that may use it plus "allowed in Read-only" (rule 10). `AdmissionService` computes `AdmittedCardAbilities` and the field gating of `GET /admissions` from it (the SAH-36 abilities move here unchanged; `deposit` is added). The catalogue in `GET /card-setup` is serialised from the same constants, so the "who" line and the preview's role rule are never a second copy (answers the BA's "single copy" request). The browser holds no role table.
- **`CardSetupDefaults`**: the standard setup (DEC-027) and the three templates.
- **`CardSetupValidator`** (on Save draft and Publish; rule 16): every message and field path is in the contract. A bad setup answers 400 `validation` with `fieldErrors` and stores nothing.
- **`CardSetupRepair`** (on every read of a stored setup, live or draft): drops unknown keys, adds catalogue items that are missing (new fields arrive off, in their standard section; locked items on), forces locked sections and fields on, puts the Card header first and Patient name first in it, clears labels of locked fields, moves a locked field out of a switched-off section back to its standard section. Repair is in memory; the stored row is never rewritten. A stored setup that cannot be parsed at all gives the standard setup and `fallback: true` (the Admitted page shows "Could not load the card setup. Showing the standard card.").
- **Canonical form and equality**: all 7 sections in order, all catalogue fields exactly once, all 5 actions in order, labels trimmed and NFC-normalised, empty label stored as `""`. Equality (draft vs live, live vs standard) is equality of the canonical form, computed on the server (`differsFromLive`, `isStandard`, `changedParts`). `changedParts` uses the mockup's `pcChanges` split: `layout` (layout or cards per row), `sections` (order or on/off), `fields` (placement, order, on/off, labels), `quickActions`.

The browser mirrors the rules for instant messages and button states (it receives the catalogue), but it is never the protection.

### D4 · API: four operations on `/card-setup`, the setup travels in the list answer

| Method | Path | Role | Answer |
|---|---|---|---|
| GET | `/card-setup` | OWNER, FRONT_DESK, NURSE, DOCTOR | `CardSetupView`: catalogue, standard, templates, live (version, setup, isStandard), `canEdit`, `readOnlyReason`; for OWNER also `draft` (or null) and `versions` |
| PUT | `/card-setup/draft` | OWNER | Save draft. Body `{ baseVersion, draftRevision, setup }`. A setup equal to the live one removes the draft and writes no history line (AC8) |
| DELETE | `/card-setup/draft?draftRevision=n` | OWNER | Discard. No draft: 200 (nothing to do) |
| POST | `/card-setup/publish` | OWNER | Publish the setup in the body (the editor's draft, saved or not) as the next version and delete the draft. Body as PUT. Equal to live: 409 `card-setup-unchanged` |

- **Reset to default and templates are editor actions**: the editor replaces its draft with `standard` (or applies a template from `templates`) and the change is stored by the next Save draft or Publish, like every other edit (rule 4: "every edit changes the draft only"). No reset endpoint. The standard and templates come from the server (D3), so the browser holds no copy of them.
- **`GET /admissions` carries the published setup** as `AdmissionList.cardSetup` (`ResolvedCardSetup`: the repaired live setup, only the items switched on, labels resolved, data group per field, `version`, `fallback`). Reason: one request per reload, and the setup and the facts come from **the same snapshot**, so a card is never half old and half new (AC60) and the facts sent always match the setup used to choose them (D6). About 2 KB per answer before gzip.
- `AdmissionList.card` gains `deposit` (ability) and `visibleGroups` (the caller's data groups from `CardAccessRule`), so the Admitted page draws only groups the caller may see even where the row carries more for the Table view (D7).

Rejected: the Admitted page reading `GET /card-setup` separately (two requests per minute and a window where facts and setup disagree); a server-side reset endpoint (a second path for the same draft write).

### D5 · Concurrency (OQ-37-6): optimistic tokens, one lock, 409 `card-setup-changed`

- The editor remembers `live.version` and `draft.revision` (null when it loaded no draft) and sends them as `baseVersion` and `draftRevision`.
- Save draft, Discard and Publish run in one READ COMMITTED transaction that first takes the **existing IPD clinic advisory lock** (`lockTenant`, as `DischargeService` does for settings), then reads the live version and the draft. Conflict (409 `card-setup-changed`, "The card setup was changed by someone else. Reload to see it.", nothing stored) when the live version is not `baseVersion`; or a draft exists and its revision is not `draftRevision`; or no draft exists and `draftRevision` is not null (another owner discarded or published it). Discard with no draft at all answers 200 (the outcome the owner wanted).
- Publish inserts version `max + 1`. The unique index `(tenant_id, version)` is the backstop; a 23505 maps to 409 `card-setup-changed`, never 500.
- A double tap on Publish: the first succeeds; the second carries the old `baseVersion` and gets 409, and the client reloads (the button is also disabled while busy, AC13). Exactly one version is made (AC8).
- The draft write is `insert ... on conflict (tenant_id) do update ... where revision = :draftRevision` style or an update guarded by revision; either way the advisory lock already serialises it.

### D6 · `GET /admissions`: extra facts, role-gated, absent not null, minimised by the published setup

The rule from ADR-013 D3 is kept: a role-gated property is **absent** from the JSON, `null` inside it means "none exists". A new fact is sent only when **both**: (a) the caller's role may see its group (`CardAccessRule`, token and membership role); (b) the **published** setup has the field switched on in a section that is on (the BA's data-minimisation recommendation). Default card (DEC-027): none of the new facts is sent, so the default list answer is the SAH-36 answer plus `cardSetup` and two `card` properties.

| Fact | Where | Roles | Source (same transaction, same `asOf`) |
|---|---|---|---|
| `clinical.diagnosis` | `AdmittedClinicalFlags` | OWNER, DOCTOR, NURSE | `ipd.admission.reason` in R1 (same row; one more column) |
| `clinical.latestVitals` (each reading present only when its field is on; `recordedByName` only when Last recorded is on) | `AdmittedClinicalFlags` | OWNER, DOCTOR, NURSE | R4 of ADR-013 extended to the five readings and `recorded_by` (same row per admission, no extra probe); recorder names: one `ActorDirectoryPort.displayNames` batch, only when Last recorded is on |
| reading `mark` (`CHECK`, `CRITICAL`, absent for none) | each reading | as above | `EarlyWarningScore` per-parameter points (ADR-006 D3): 3 = CRITICAL, 1 or 2 = CHECK, 0 = none. BP uses the systolic points (diastolic is not scored). No new threshold (DEC-027, OQ-37-11) |
| `mobile` | `AdmittedPatientRow` | OWNER, FRONT_DESK | FRONT_DESK: the R2' `PatientLookupPort.findAllCurrent` already loaded (`PatientSummary.mobile`). OWNER: one extra `findAllCurrent` batch, **only** when Mobile is on (OWNER's R2 is `PatientClinicalPort`, which has no mobile). Amends ADR-013 D15 ("the mobile number is never mapped into the list") for this one switch |

- Billing fields (`runningBillPaise`, `depositPaise`, `payer`, `depositLow`) and `ipNumber`, `admissionType` stay on every row for all four roles, as ADR-013 D3 decided for the unchanged Table view. On the **card** they are drawn only for groups in `card.visibleGroups`. This is a display rule, not a privacy control, exactly as today; removing the bill from NURSE and DOCTOR would change the Table and is out of scope (open point O2).
- `cardFacts=all` (query parameter, **OWNER only**, others 403 `forbidden`): the answer carries every new fact for every patient whatever the published setup says, so the setup page's preview can draw a draft that switches on a field not yet published. It is used only by the setup page (loaded when the page opens and on **Try again**, never polled). The owner may see every group (rule 11), so no data leaves the server that the owner could not already see by publishing. No record-access row, as the list (DEC-021).

### D7 · Preview: the role rule is applied in the browser, on the owner's own answer

The preview draws the editor's draft for a chosen "View as" role from the owner's `GET /admissions?cardFacts=all` answer, using `catalogue.groups[].visibleTo` and `catalogue.actions[].usableBy` from `GET /card-setup` (D3). Reasons: every "View as" role sees a subset of what OWNER sees; the preview is inert display (writes nothing, opens nothing); the server enforces the real gating on every real list. A server preview endpoint would add a second read of the same patients for no protection (story note: not wanted unless needed). The preview never calls the server as another role. The "Example patient" (nobody admitted) is built in the browser and never sent.

### D8 · One shared card component

The Angular card is one component, input: a `ResolvedCardSetup`, one row, the caller's (or the "View as" role's) visible groups and abilities, `asOf`, and a `preview` flag (inert: no buttons in tab order, name as text). The Admitted page passes `AdmissionList.cardSetup`, `card.visibleGroups` and `card`; the setup page resolves its draft with the catalogue into the same `ResolvedCardSetup` (a pure mapping: label or standard name, group, safety flag) and derives groups and abilities for the "View as" role from the catalogue (abilities also follow `live`'s IPD state: Transfer, Discharge, Deposit off in Read-only). What the owner approves is what staff see.

### D9 · History and versions (rule 18, OQ-37-9)

- **Clinic setup change history** is the existing config audit (`platform.api.AuditLog`, shown by `GET /api/v1/platform/audit-events`), in the same transaction as the write:
  - `IPD_CARD_DRAFT_SAVED`, entity type `IPD_CONFIG`: before `{ liveVersion }`, after `{ liveVersion, draftRevision, changedParts }`.
  - `IPD_CARD_PUBLISHED`, entity type `IPD_CONFIG`: before `{ version: n - 1 }`, after `{ version: n, changedParts, isStandard }`.
  - Discard and Reset write no history line (mockup `cfgLog`; OQ-37-9). A Save draft that equals the live setup (draft removed) writes none.
  - No label text, no setup body and no patient data in any audit row (labels are owner text; the parts changed are enough, rule 18).
- **Versions** are the rows of `ipd.card_setup_version` plus the virtual v1; the summary line is derived ("Sahaj standard patient card (default)" for v1, "Back to the Sahaj standard card" when `isStandard`, otherwise "Changed: layout, fields"). Newest first, at most 50 in one answer (a clinic publishes a few times a year).

### D10 · Cost for 100 hospitals polling every 60 seconds

Assume 100 hospitals × 15 visible Admitted tabs = 1,500 list reads a minute (25 a second) at up to 300 patients each; plus the OPD-only clinics, which never call it.

| Added per list read | Cost |
|---|---|
| R7: live setup, `order by version desc limit 1` on unique `(tenant_id, version)` | one index probe, about 3 KB jsonb; repair and resolve in memory (microseconds) |
| Diagnosis | one more column in R1; 0 extra queries |
| Readings, recorder id | more columns from the R4 row already fetched per admission; 0 extra probes (the conditional include-index of ADR-013 R4 would need the readings added; not recommended, the heap fetch is 300 rows) |
| Recorder names | 1 batch query, only when Last recorded is on (distinct recorder ids, usually under 30) |
| Owner mobile | 1 batch patient read, only for OWNER and only when Mobile is on |
| Bytes | default card: +2 KB (`cardSetup`); all fields on at 300 rows: about +120 KB raw, +12 KB gzipped (D14 compression) |

So at most 3 extra statements per read, 1 in the default case: about 25 index probes a second over the whole platform. No cache is needed; if one is wanted later, a per-tenant cache keyed on the live version is safe because versions are append-only. `GET /card-setup` itself is read when the setup page opens, not polled.

### D11 · Logging, privacy, retention, boundaries

- Labels are owner-typed plain text: trimmed, NFC, at most 40 code points, the IPD text rule (no control or text-direction characters), output-encoded everywhere (no `innerHTML`). They are not patient data; they are not logged.
- Logs carry ids, `tenantId`, `requestId`, version numbers only; never names, UHID, mobile, diagnosis, allergy or readings, and never the setup body.
- Retention: the setup holds no patient data. Versions are kept for the life of the clinic account (configuration history, like `config_audit_event`); the draft lives until Publish or Discard. Closing a subscription does not delete them.
- Boundaries: ipd only; `ModularityTests` stay green; no cross-module table access (mobile through `patient.api`, names through `platform.api`). Phase 1 held: no push on publish (the 60-second reload delivers it, OQ-37-7), no notification, no document, no analytics.

## Options considered (summary)

- **jsonb document per version (chosen)** vs normalised rows per section, field and action.
- **Setup inside the list answer (chosen)** vs a second request from the Admitted page.
- **Facts minimised by the published setup, `cardFacts=all` for the owner's preview (chosen)** vs every fact a role may see on every reload (simpler, but sends diagnosis and readings that no card draws) vs a server preview endpoint.
- **Role rule from the server catalogue, applied in the browser for the preview (chosen)** vs a TypeScript copy of the rule vs a server preview per role.
- **Optimistic tokens under the clinic advisory lock (chosen)** vs `If-Match` ETags (no precedent in the API, same effect) vs last-write-wins (forbidden by rule 17).

## Consequences

**Positive.** The locked safety items cannot be removed by any client; old or corrupt rows are repaired on read; staff never see a draft; two owners never overwrite each other; the default list answer carries no new clinical data (DEC-027); one card component and one access rule.

**Negative / accepted.**
- The list answer depends on the published setup: a publish that switches Diagnosis or vitals on widens the clinical data on the shared ward list for OWNER, DOCTOR and NURSE, still with no record-access row (DEC-021; open point O3).
- The owner's preview loads every card fact for every admitted patient once per page open (D6).
- Publish takes the clinic IPD advisory lock for a few milliseconds (rare).
- NURSE and DOCTOR still receive billing fields for the Table view (ADR-013 D3; O2).

## Data needed (for db-engineer)

One forward-only migration. No change to any existing table; **no destructive change**.

1. `ipd.card_setup_version`
   - `id uuid pk default gen_random_uuid()`, `tenant_id uuid not null default platform.current_tenant()`, `version integer not null check (version >= 2)`, `setup jsonb not null check (jsonb_typeof(setup) = 'object' and octet_length(setup::text) <= 16384)`, `is_standard boolean not null`, `changed_parts text[] not null check (changed_parts <@ array['layout','sections','fields','quickActions']::text[])`, `published_by uuid not null` (user id, IPD actor convention), `published_at timestamptz not null default now()`.
   - Unique `(tenant_id, version)` (also serves "latest": backward scan, limit 1).
   - FORCE RLS, policy `tenant_id = platform.current_tenant()`. Grants to `sahaj_app`: select; insert `(version, setup, is_standard, changed_parts, published_by)`. Triggers refuse update, delete, truncate (`platform.deny_audit_mutation()` pattern).
2. `ipd.card_setup_draft`
   - `tenant_id uuid pk default platform.current_tenant()`, `setup jsonb not null` (same checks), `base_version integer not null check (base_version >= 1)`, `revision bigint not null check (revision >= 1)`, `saved_by uuid not null`, `saved_at timestamptz not null default now()`.
   - FORCE RLS, same policy. Grants: select, insert `(setup, base_version, revision, saved_by)`, update `(setup, base_version, revision, saved_by, saved_at)`, delete. Truncate refused.
3. Tenant scope: both tables are tenant tables; other clinics invisible. No patient data in either.
4. Retention: kept for the life of the clinic account; the draft row is deleted on Publish and Discard.
5. Plans to record in `docs/database/SAH-37.md` (as `sahaj_app` with `app.tenant_id` set): R7 latest version is an index scan on the unique index with limit 1; ADR-013 R4 still one probe per admission after adding the readings and `recorded_by` to its select list; R1 with `reason` unchanged.

## Instructions

**java-developer:** `ipd.domain.card` (catalogue, access rule, defaults, templates, validator, repair, canonical equality, changed parts) with unit tests per rule and per message; `CardSetupService` (GET, PUT, DELETE, POST with D2 order, D5 lock and conflicts, D9 audit); move the SAH-36 abilities into `CardAccessRule` with no behaviour change and add `deposit`; `AdmissionService.list`: R7, `cardSetup`, `card.visibleGroups`, `card.deposit`, the D6 facts with the absent-not-null rule (raw-JSON tests per role and per setup), `cardFacts=all` for OWNER only; tile marks from `EarlyWarningScore` parameter points (no copy of the bands). Never log the setup body or labels.

**angular-developer:** Clinic setup entry "Patient card" (`requires: 'ipd'`, after Discharge); the setup page (bar, tabs, editor, preview, versions) driven by `catalogue`, `standard`, `templates`; the shared card component (D8) in three layouts; Admitted page draws `cardSetup`, uses `cardsPerRow` as the opening default, shows the fallback notice on `fallback` or a missing/unknown setup; "Customize card" for OWNER only; Deposit action opens the admission sheet at "Running bill and deposit" (new start view `deposit`, ADR-013 D6 pattern); concurrency tokens and the 409 message; labels as text only.

**qa-engineer:** other clinic's setup, draft and versions invisible; non-owner 403 on PUT, DELETE, POST and on `cardFacts=all`; draft and versions absent for non-owners (raw JSON); each locked item refused on save and publish (400) and repaired on read (seed a stored row without it); two-owner conflicts (stale version, stale revision, discarded draft) 409 and nothing stored; double publish makes one version; Read-only 409; FRONT_DESK raw JSON never has `diagnosis`, `latestVitals` with any field switched on; NURSE and DOCTOR never get `mobile`; a fact switched off is absent; tile marks at the SAH-30 band edges; history rows hold no labels or patient data; default setup answer equals the SAH-36 facts.

## Questions for the human

See the open points O1 to O6 in `docs/architecture/SAH-37-patient-card-setup.md` section 9.
