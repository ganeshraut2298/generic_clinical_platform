# SAH-37 · IPD patient card setup: design note

Story: SAH-37 (`docs/stories/SAH-37.md`, AC1 to AC66; rules 1 to 21 in `docs/requirements/SAH-37.md`).
Mockup screens: Clinic setup → Patient card (screen-map row 29); IPD → Admitted (Customize card, layouts Compact, Standard, Expanded).
Module: ipd only. Decisions: **ADR-015** (`docs/architecture/adr/ADR-015-ipd-patient-card-setup.md`). Contract: `docs/api/ipd.yaml`, tag SAH-37.
Inputs: DEC-027 (decided 9 Nov 2026), DEC-021, ADR-013, ADR-006 D3.

Facts checked on 9 Nov 2026:
- `ipd.admission.reason` exists (SAH-23 `AdmissionInput.reason`, blank stored as "Under evaluation") and is already shown as "Diagnosis" by `DischargeSheet.diagnosis` and `EmrSheet.diagnosis`. **Diagnosis stays in the catalogue**; no new column.
- `patient.api.PatientLookupPort.PatientSummary` already carries `mobile`; `findAllCurrent` exists (ADR-013 D15). No port change.
- `platform.api.ActorDirectoryPort.displayNames` exists (batch). `platform.api.AuditLog.record` feeds `GET /api/v1/platform/audit-events` (the Clinic setup change history). No platform change.
- `EarlyWarningScore` returns the five parameter scores (ADR-006 D3); tile marks reuse them.
- The Clinic setup menu is client-side (`frontend/src/app/features/config/config-sections.ts`, `requires: 'ipd'`); no platform menu key is needed.
- IPD setup writes in Read-only answer 409 `ipd-readonly` after role, module and body checks (`PUT /discharge-settings`); SAH-37 matches (OQ-37-10).

## 1. Catalogue (server constants, `com.sahaj.ipd.domain.card.CardCatalogue`; sent in `GET /card-setup`)

### Sections (default order)

| Key | Name | Description (setup row) | Locked |
|---|---|---|---|
| `header` | Card header | Name, identifiers and bed · always first | Always on, always first ("Always on") |
| `status` | Workflow status | Alerts | Safety · always on |
| `stay` | Admission and bed | Dates, ward and room | — |
| `care` | Care team | Doctor | — |
| `vitals` | Vitals | Latest readings with status | Safety · always on |
| `clinical` | Clinical | Diagnosis and allergies | Safety · always on |
| `billing` | Billing | Running bill and deposit | — |

### Fields

| Key | Standard name | Group | Safety | Allowed sections | Value on the card (source) |
|---|---|---|---|---|---|
| `name` | Patient name | patient | Required (fixed first in header) | header | `patientName` |
| `uhid` | UHID | patient | yes | any | `uhid` |
| `ageSex` | Age and gender | patient | — | any | `age`, `sex` |
| `bed` | Bed | patient | — | any | `bedCode` |
| `ipNumber` | IP number | patient | — | any | `ipNumber` |
| `mobile` | Mobile | contact | — | any | `mobile` (new) |
| `alerts` | Alerts | patient | yes | any | flags line (`clinical` flags, `goingHomeToday`, `depositLow`) |
| `admittedOn` | Admitted on | patient | — | any | `admittedAt` |
| `lengthOfStay` | Length of stay | patient | — | any | `dayNumber`, `asOf` |
| `ward` | Ward | patient | — | any | `wardName` |
| `room` | Room | patient | — | any | `roomName` |
| `admissionType` | Admission type | patient | — | any | `admissionType` |
| `doctor` | Doctor | patient | — | any | `doctorName` |
| `bp` | BP | vitals | — | any | `clinical.latestVitals.bp` (new) |
| `pulse` | Pulse | vitals | — | any | `clinical.latestVitals.pulse` (new) |
| `spo2` | SpO₂ | vitals | — | any | `clinical.latestVitals.spo2` (new) |
| `temp` | Temp | vitals | — | any | `clinical.latestVitals.temp` (new) |
| `rr` | RR | vitals | — | any | `clinical.latestVitals.rr` (new) |
| `ews` | Early warning | vitals | yes | any | `clinical.latestEws` |
| `lastRecorded` | Last recorded | vitals | — | any | `clinical.latestVitals.recordedAt`, `recordedByName` (new) |
| `diagnosis` | Diagnosis | clinical | — | any | `clinical.diagnosis` (new) |
| `allergies` | Allergies | clinical | yes | any | `clinical.allergy` |
| `runningBill` | Running bill | billing | — | any | `runningBillPaise` |
| `deposit` | Deposit | billing | — | any | `depositPaise` |
| `payer` | Payer | billing | — | any | `payer` |

Not listed (OQ-37-3, OQ-37-5): Workflow stage, Assigned nurse, Last nursing note. Placement: only Patient name is restricted (mockup lets every other field move; a vital reading outside Vitals is drawn as "BP: 136/84 mmHg", as the mockup's `kvVal`).

### Data groups and roles (`CardAccessRule`, rule 11; narrows nothing the server allows today)

| Group | Name (`PC_G`) | Roles that may see it |
|---|---|---|
| `patient` | Patient and admission | OWNER, FRONT_DESK, NURSE, DOCTOR |
| `contact` | Contact details | OWNER, FRONT_DESK |
| `clinical` | Clinical details | OWNER, DOCTOR, NURSE |
| `vitals` | Vitals and early warning | OWNER, DOCTOR, NURSE |
| `billing` | Billing | OWNER, FRONT_DESK |

### Actions (rule 10; same set as `AdmittedCardAbilities`)

| Key | Name | Icon | Description | Usable by ("who" line) | In Read-only |
|---|---|---|---|---|---|
| `nursing` | Nursing | nurse | Record vitals and a nursing note | Owner, Nurse, Doctor | yes |
| `emr` | EMR | emr | Clinical record, medicines and results | Owner, Nurse, Doctor | yes |
| `transfer` | Transfer | swap | Move to another bed | Owner, Nurse, Front desk | no |
| `discharge` | Discharge | door | Summary, final bill and bed release | Owner, Doctor | no |
| `deposit` | Deposit | deposit | Running bill and deposit | Owner, Front desk | no |

"View as" roles: OWNER "Owner / Admin", DOCTOR "Doctor", NURSE "Nurse", FRONT_DESK "Front desk" (`catalogue.viewAsRoles`).

## 2. The Sahaj standard setup v1 (DEC-027)

Layout Standard, cards per row 3. Every label empty.

| Section (on) | Fields on | Fields off |
|---|---|---|
| Card header (on) | Patient name, UHID, Age and gender, Bed | IP number, Mobile |
| Workflow status (on) | Alerts | — |
| Admission and bed (on) | Admitted on, Length of stay, Ward, Room | Admission type |
| Care team (on) | Doctor | — |
| Vitals (on) | Early warning | BP, Pulse, SpO₂, Temp, RR, Last recorded |
| Clinical (on) | Allergies | Diagnosis |
| Billing (**off**) | Running bill, Deposit, Payer | — |

Actions: Nursing, EMR, Transfer, Discharge on; Deposit off.

This is the SAH-36 card plus the locked Allergies line and the locked Early warning line (see O1). It needs no fact beyond the SAH-36 answer: `clinical.allergy` and `clinical.latestEws` are already sent to OWNER, DOCTOR and NURSE.

## 3. Templates (`templates`; applied to the editor's draft, start from the standard setup, keep the draft's cards per row)

| Key | Name | Description | Changes from the standard |
|---|---|---|---|
| `smallClinic` | Small clinic | Compact card, no billing | layout Compact; Billing off |
| `nursingHome` | Nursing home | Standard card with deposit action | layout Standard; Deposit action on |
| `hospital` | Hospital | Expanded card, billing section, all actions | layout Expanded; Billing on; every action on; IP number on; Admission type on |

## 4. Validation on Save draft and Publish (400 `validation`, `fieldErrors`, nothing stored)

Checks run in this order; all errors are returned together; `detail` is the first message. Field paths are JSON paths into the body (`setup.sections[2].on`).

| # | Rule | Message |
|---|---|---|
| V1 | `layout` is compact, standard or expanded | Choose Compact, Standard or Expanded. |
| V2 | `cardsPerRow` is 2, 3 or 4 | Choose 2, 3 or 4 cards per row. |
| V3 | every section key known, each once, all 7 present | Unknown section. / Each section can appear only once. / The <Section> section is missing. |
| V4 | `header` first and on | The Card header section is always first and always on. |
| V5 | `status`, `vitals`, `clinical` on | The <Section> section is a safety section and stays on. |
| V6 | every field key known, each once across sections, all 25 present | Unknown field. / Each field can appear only once. / <Field> is missing from the card setup. |
| V7 | Patient name in `header`, first, on, label empty | Patient name stays first in the Card header. |
| V8 | UHID, Alerts, Early warning, Allergies on | <Field> is a safety field and cannot be hidden. |
| V9 | a safety field not in a switched-off section | <Field> is a safety field. Move it to a section that is switched on. |
| V10 | safety field label empty (or equal to the standard name) | The label of <Field> cannot be changed. |
| V11 | label after trim and NFC at most 40 code points | Keep the label to 40 characters. |
| V12 | label has no control or text-direction character (IPD text rule) | Label cannot contain control or text-direction characters |
| V13 | every action key known, each once, all 5 present | Unknown quick action. / Each quick action can appear only once. / The <Action> quick action is missing. |
| V14 | raw label over 400 UTF-16 units refused before normalisation | Keep the label to 40 characters. |

A body that is not the declared JSON shape (wrong type, missing property) is 400 `validation` with the property path. Unknown extra properties are refused (`additionalProperties: false`).

## 5. Equality and changed parts (server, canonical form)

Canonical form: sections in order, fields in order within each, actions in order, labels trimmed and NFC (empty = standard name). `changedParts` against the live setup, in this order: `layout` (layout or cards per row differ), `sections` (section order or on/off differ), `fields` (any field's section, order, on/off or label differ), `quickActions` (order or on/off differ). Pill count "N changes" = number of parts. Toast and history words: layout, sections, fields, quick actions ("Layout, fields updated."). The editor computes the same parts on unsaved edits for the pill only; the server's values win after each answer.

## 6. Screen states (contract fields)

| Screen element | Field |
|---|---|
| "Sahaj standard card · v1 live" (teal) / "Customized · v<n> live" (blue) | `live.isStandard`, `live.version` |
| "Draft · N changes not published · saved 11:05 AM" | `draft.changedParts`, `draft.savedAt` (owner only) |
| "No unpublished changes" | `draft` null or `draft.differsFromLive` false (never true in practice: a draft equal to live is removed) |
| Reset to default disabled "Already the default" | editor draft equal to `standard` |
| Publish enabled | editor draft differs from `live.setup` |
| Save, Discard, Reset, Publish not offered (Read-only) / controls disabled and "set by the clinic owner" (non-owner) | `canEdit`, `readOnlyReason` (`ROLE`, `IPD_READONLY`) |
| "Published versions" rows, "Live" pill | `versions[]` (`version`, `summary`, `publishedByName`, `publishedAt`, `live`) |
| Overview tile "Standard · Sahaj default · v1 · draft" | `live.setup.layout`, `live.isStandard`, `live.version`, `draft` |
| Preview patients "<name> · <bed>" | `GET /admissions?cardFacts=all` `items` |
| "Hidden for <role>: N fields, M actions" | `catalogue.groups[].visibleTo`, `catalogue.actions[].usableBy` |
| Admitted cards | `AdmissionList.cardSetup`, `card.visibleGroups`, `card` abilities, rows |
| "Could not load the card setup. Showing the standard card." | `cardSetup.fallback` true, or `cardSetup` missing or unknown to the client |
| "The card setup was changed by someone else. Reload to see it." | 409 `card-setup-changed` |

## 7. Frontend notes (angular-developer)

- One card component (ADR-015 D8), three layouts as story AC55. Compact always shows "Allergies: Not recorded" (amber) or "Allergies: None known" (DEC-027 point 3), and only BP, SpO₂, Pulse tiles.
- Vitals section with `clinical.latestEws` null: "No vitals recorded yet" whatever fields are on; Last recorded "Not recorded" (amber).
- Tiles: value with unit, "—" when absent; `mark` CHECK → word "Check", title "Outside normal range"; CRITICAL → "Critical", title "Critical"; never colour alone.
- Last recorded: "<N> min ago · <recordedByName>" from `asOf - recordedAt` (the `pcAgo` format), never the device clock.
- Labels: text interpolation only. The owner's label is the field heading (Standard, Expanded) or tooltip (Compact, header).
- The setup page loads `GET /card-setup` and, for the preview, `GET /admissions?cardFacts=all` (once, and on Try again; not polled). Failure of the second shows the example patient and "Could not load patients. Showing an example."
- Keep `baseVersion` and `draftRevision` from the last answer; send them on every write; on 409 `card-setup-changed` show the detail and offer reload; keep the owner's edits on screen until they reload.
- "Customize card" only when the role is OWNER (`/platform/me`), not disabled for others.

## 8. Data needed (db-engineer)

See ADR-015 "Data needed": `ipd.card_setup_version` (append-only, unique `(tenant_id, version)`) and `ipd.card_setup_draft` (primary key `tenant_id`, `revision`), both FORCE RLS on `tenant_id`, no patient data, kept for the life of the account. No existing table changes; no destructive change. Plans to record: latest version probe; ADR-013 R4 with the readings added.

## 9. Open points for the human

- **O1 (safety, DEC-027 reading).** Early warning is a locked "always on" field (rule 9), so the default card shows an Early warning line ("<score> · <risk>", or "No vitals recorded yet") as well as the Allergies line. DEC-027 names only the Allergies line. No new data (the score is already in the SAH-36 answer). Recommended: accept; the alternative is to unlock Early warning, which rule 9 forbids.
- **O2 (privacy, carried from ADR-013 D3 / R36-6).** NURSE and DOCTOR still receive running bill, deposit and payer in every row because the unchanged Table view shows them. The card does not draw them for these roles (`card.visibleGroups`). Rule 11 says "not sent"; making it true changes the Table (out of scope). Recommended: a follow-up story.
- **O3 (privacy, OQ-37-1 / OQ-36-7).** Once an owner publishes Diagnosis or vital readings, the shared ward list carries them for every admitted patient to OWNER, DOCTOR and NURSE with no record-access row (DEC-021 covered the SAH-36 card). Confirm the exception extends to these fields, and add them to the privacy-register entry (DEC-021 go-live condition).
- **O4 (mobile on the list).** Mobile switched on sends it for every admitted patient to OWNER and FRONT_DESK; the owner's preview (`cardFacts=all`) loads it whenever the setup page opens. This amends ADR-013 D15 ("the mobile number is never mapped into the list"). Recommended: accept as the owner's explicit choice; alternative: leave Mobile out of the catalogue.
- **O5 (staff name on the list).** "Last recorded" shows the recorder's name (mockup `vtime`: "35 min ago · Sr. Anjali Pawar"); a staff name, sent only when the field is on. Confirm.
- **O6 (template content).** Under DEC-027 the standard has the readings and Diagnosis off, so the Hospital template ("Expanded card ... every field") still shows no tiles or Diagnosis; the mockup's Hospital card has them because its default had them. Recommended: keep rule 6 as written (no template switches on clinical data); alternative: Hospital also switches on the readings, Last recorded and Diagnosis.
- **O7 (history).** Discard and Reset are not in the Clinic setup change history (mockup `cfgLog`, OQ-37-9); only Save draft and Publish are.
- Go-live condition (DEC-027): a clinician signs off the default card and the tile marks.
