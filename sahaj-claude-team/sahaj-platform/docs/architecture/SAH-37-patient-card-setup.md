# SAH-37 · IPD patient card setup: design note

Story: SAH-37 (`docs/stories/SAH-37.md`, AC1 to AC66; rules 1 to 21 in `docs/requirements/SAH-37.md`).
Mockup screens: Clinic setup → Patient card (screen-map row 29); IPD → Admitted (Customize card, layouts Compact, Standard, Expanded).
Module: ipd only. Decisions: **ADR-015** (`docs/architecture/adr/ADR-015-ipd-patient-card-setup.md`). Contract: `docs/api/ipd.yaml`, tag SAH-37.
Inputs: DEC-027 (decided 9 Nov 2026), DEC-021, ADR-013, ADR-006 D3.

Facts checked on 9 Nov 2026:
- `ipd.admission.reason` exists (SAH-23 `AdmissionInput.reason`, blank stored as "Under evaluation") and is already shown as "Diagnosis" by `DischargeSheet.diagnosis` and `EmrSheet.diagnosis`. **Diagnosis stays in the catalogue**; no new column.
- `patient.api.PatientLookupPort.PatientSummary` already carries `mobile`; `findAllCurrent` exists (ADR-013 D15) but never fills the mobile. **Amended after review (ADR-015 A3):** `findAllCurrent` stays free of mobiles; one new explicit method, `PatientLookupPort.mobilesOf(tenantId, ids)`, returns mobiles for given ids, called only when the facts plan needs Mobile and the caller is OWNER or FRONT_DESK.
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

All errors are returned together, sorted by rule number (V1 first, V15 last; within one rule in body order: section index, then field index); `detail` is the first message. Field paths are JSON paths into the body as sent, indexes from 0 (`setup.sections[2].on`). V14 is checked first for each label but sorts by its number.

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
| V15 | a label may not duplicate or impersonate another field: its comparison key may not equal the key of the standard name of any other of the 25 catalogue fields, or the key of the label of any field placed before it (section 4.1). Added by DEC-032 (G-4, human approved 9 Nov 2026) | This name is already used by another field. |

A body that is not the declared JSON shape (wrong type, missing property) is 400 `validation` with the property path. Unknown extra properties are refused (`additionalProperties: false`).

### 4.1 V15 in full (server; the browser mirrors it, section 4.2)

**Why.** V10 protects only a safety field's own label. Without V15 an owner could name Diagnosis "Allergies", or IP number "UHID", and a ward screen would show two "Allergies" headings, one of which is not the allergy. That is a misreading risk on a clinical card (DEC-032, G-4).

**Comparison key `k(s)`** (same steps on server and browser):
1. Unicode NFC.
2. Every run of white space or space separators (Java `Character.isWhitespace` or category Zs: U+0020, U+00A0, U+1680, U+2000 to U+200A, U+202F, U+205F, U+3000; JS `/[\s\p{Zs}]+/gu`) becomes one U+0020.
3. U+0020 removed at both ends.
4. Case folded: upper case then lower case with no locale (Java `toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT)`; JS `toUpperCase().toLowerCase()`). Both use the Unicode unconditional mappings, so "ß", "SS" and "ss" match, and the final sigma is handled the same way.
5. Unicode NFC again (case mapping can undo it).

**Inputs.** For each field placement F with a known key that is not a duplicate (V6 passed for it), take its canonical label L(F) (NFC, stripped; the value V11 measured). V15 is checked only when:
- L(F) is not empty; and
- the label of F raised no other error (V7, V10, V11, V12 or V14). So a label path has at most one error; and
- `k(L(F))` is not equal to `k(standard name of F)`. A label equal to the field's **own** standard name, in any case or spacing, is never a V15 error. When it is exactly the standard name it is stored as "" (canonical form, section 5); a case or spacing variant ("bed" in the Bed box) is a real label and is stored as typed.

**Rule.** V15 fails for F when `k(L(F))` equals:
- (a) `k(standard name of G)` for **any other** of the 25 catalogue fields G. This covers every catalogue field, whether it is on or off, in a switched-on or switched-off section, and whether or not it carries a label of its own. Standard names are always reserved; or
- (b) `k(L(G))` for any other field G placed **before** F in the body (lower section index, or the same section and a lower field index), where L(G) is not empty and G's label passed V7, V10, V11, V12 and V14.

**Which field gets the error.** For (a), the field that carries the label. For (b), only the later field in body order; the first field keeps its label. One error per label path, never two.

**Path and message.** `setup.sections[i].fields[j].label` (indexes of the body as sent). Message: **"This name is already used by another field."** Fixed text: it never echoes the typed label or the other field's label. Rule number 15, so it sorts after V13 and is `detail` only when it is the only kind of error.

**Notes.**
- Patient name and the four safety fields can only carry "" or their own standard name (V7, V10), so they never trip V15 themselves; their standard names are reserved like every other.
- Unknown or duplicate keys (V6) are skipped as F and as G; the reserved standard names are always the 25 of `CardCatalogue`, never taken from the body.
- Accepted limit: the key uses NFC, not NFKC, and does not map look-alike letters across scripts. "SpO2" does not clash with "SpO₂", and a Cyrillic "А" does not clash with a Latin "A". The rule stops plain duplicates; the label stays plain text in a fixed position under the owner's control.
- Stored drafts and versions are **not** repaired for V15. Repair on read (ADR-015 D3) does not drop or change labels for this rule, so no data is lost and every stored setup stays readable. The next Save draft or Publish of such a setup answers 400 V15 until the owner renames the field.
- Performance: at most 25 keys per setup, compared through a hash set. Negligible.

| Example (field: label) | Result |
|---|---|
| Diagnosis: "Allergies" | V15 on Diagnosis (standard name of Allergies) |
| IP number: " uhid " | V15 on IP number (key "uhid") |
| Room: "Bed" while Bed has the label "Cot" | V15 on Room (standard names stay reserved) |
| Ward: "Cot" (section 2), then Room: "COT" (section 2, later) | V15 on Room only |
| Bed: "Bed" | allowed; stored as "" |
| Bed: "bed" or "Bed  " with a no-break space | allowed (own name); "bed" stored as typed, the second stored as "" after stripping |
| Doctor: "Consultant", nothing else named so | allowed |
| Diagnosis: "Allergies" with a direction mark inside | V12 only (one error per path) |

### 4.2 Browser behaviour for the label (G-1, G-2, G-3; DEC-032, human approved 9 Nov 2026)

The server stays the source of truth: every rule is checked again on Save draft and Publish, and the server's answer wins. The browser checks only to tell the owner earlier and in the right place.

**G-3 · Same canonical form on both sides.** The browser's canonical label is: NFC, then white space and space separators removed at both ends (as `IpdText.strip`), then "" when the result is **exactly** the field's standard name from `catalogue.fields` (case-sensitive, as `CardSetupCanonical`). The browser uses this canonical form for every comparison and for what it sends: the draft pill and its change count, "No unpublished changes", Reset to default disabled, the unsaved-edits check of Save draft and the leave guard, and Publish enabled. So typing "Bed" in the Bed box is no change: the pill stays as it was, Publish stays disabled, and the box shows the standard name, as the server would answer. A case or spacing variant ("bed") is a change on both sides.

**G-2 · Checks in the browser, on blur and on submit.** For each editable label box, on blur and again on Save draft and Publish, the browser checks in this order and shows the first failure only (one message per box, as the server):
1. Control, invisible or text-direction character (mirror of `IpdText.hasControlOrBidi`: categories Cc, Cf, Cn, Co, Cs, Zl, Zp; the blank-looking letters U+115F, U+1160, U+3164, U+FFA0, U+2800, U+180E, U+034F, U+FE00 to U+FE0F; a zero-width joiner or non-joiner is allowed only after a virama, as the server). Message (same text as V12): **"Label cannot contain control or text-direction characters"**.
2. More than 40 code points after NFC and stripping (`[...label].length`, not `label.length`). Message (same text as V11): **"Keep the label to 40 characters."**
3. V15 with the key of section 4.1 against the other 24 standard names and the labels of fields placed before it in the editor's current order. Message: **"This name is already used by another field."**

The texts are exactly the server texts, so the owner reads one wording whether the browser or the server found the problem. No message echoes the typed text. The check runs on the editor's whole setup, so renaming one field can clear or move a V15 error on another; the browser re-runs it for every box after each change and shows a V15 message only on boxes the owner has left (blur) or after a submit. On Save draft or Publish with any label in error, the browser does not send the request: it shows every label message and moves focus to the first box in error. A stored draft that already breaks V15 shows the message on its boxes when the owner first submits.

**G-1 · Server errors field by field.** On a 400 `validation`, the browser keeps `fieldErrors`. Each path that matches `^setup\.sections\[(\d+)\]\.fields\[(\d+)\]\.label$` is resolved to a field key through the **setup body that was sent** (not the editor's current state, which may have moved since). The message is shown under that field's label box in the mockup's error style (`.ferr`, mockup line 1447; `bad` on the input, line 6350), with `aria-invalid="true"` and `aria-describedby` pointing to the message, and focus moves to the first box in error. A server message replaces the browser's message for that box. The message clears on the next keystroke in that box. Every other path, and any label path that cannot be resolved, is listed in the error banner (all messages, not only the first). The banner stays for 409, 403 and network errors as today. This adds an error state that the Patient card part of the mockup does not draw; the human approved it in DEC-032.

**Not changed.** The `maxlength="40"` stop stays (AC31) until the optional G-9 counter is built; it is stricter than the server, never looser. The contract changes only in the V15 wording of the two 400 descriptions.

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
| A refused card-setup call or `cardFacts=all` (not offered to non-owners on screen) | 403 `forbidden` with the shared text "You do not have access to this." (ADR-015 A1); no operation-specific 403 text |

## 7. Frontend notes (angular-developer)

- One card component (ADR-015 D8), three layouts as story AC55. Compact always shows "Allergies: Not recorded" (amber) or "Allergies: None known" (DEC-027 point 3), and only BP, SpO₂, Pulse tiles.
- Vitals section with `clinical.latestEws` null: "No vitals recorded yet" whatever fields are on; Last recorded "Not recorded" (amber).
- Tiles: value with unit, "—" when absent; `mark` CHECK → word "Check", title "Outside normal range"; CRITICAL → "Critical", title "Critical"; never colour alone.
- Last recorded: "<N> min ago · <recordedByName>" from `asOf - recordedAt` (the `pcAgo` format), never the device clock.
- Labels: text interpolation only. The owner's label is the field heading (Standard, Expanded) or tooltip (Compact, header).
- The setup page loads `GET /card-setup` and, for the preview, `GET /admissions?cardFacts=all` (once, and on Try again; not polled). Failure of the second shows the example patient and "Could not load patients. Showing an example."
- Keep `baseVersion` and `draftRevision` from the last answer and send them back unchanged on every write. `draftRevision` is an opaque clock-based token (about 1.79e15, below 2^53, so a JavaScript number holds it exactly; it never repeats for the clinic and never restarts at 1; ADR-015 D5 and A2, DEC-028 addendum): never compute or compare it. A save equal to the stored draft keeps the same revision; on 409 `card-setup-changed` show the detail and offer reload; keep the owner's edits on screen until they reload.
- "Customize card" only when the role is OWNER (`/platform/me`), not disabled for others.

## 8. Data needed (db-engineer)

See ADR-015 "Data needed": `ipd.card_setup_version` (append-only, unique `(tenant_id, version)`) and `ipd.card_setup_draft` (primary key `tenant_id`, `revision bigint`), both FORCE RLS on `tenant_id`, no patient data, kept for the life of the account. No existing table changes; no destructive change. The clock-based draft revision (DEC-028 addendum) needs **no schema change**: it fits the existing `bigint` column and its `>= 1` check. Plans to record: latest version probe; ADR-013 R4 with the readings added.

## 9. Open points for the human

- **O1 (safety, DEC-027 reading).** Early warning is a locked "always on" field (rule 9), so the default card shows an Early warning line ("<score> · <risk>", or "No vitals recorded yet") as well as the Allergies line. DEC-027 names only the Allergies line. No new data (the score is already in the SAH-36 answer). Recommended: accept; the alternative is to unlock Early warning, which rule 9 forbids.
- **O2 (privacy, carried from ADR-013 D3 / R36-6).** NURSE and DOCTOR still receive running bill, deposit and payer in every row because the unchanged Table view shows them. The card does not draw them for these roles (`card.visibleGroups`). Rule 11 says "not sent"; making it true changes the Table (out of scope). Recommended: a follow-up story.
- **O3 (privacy, OQ-37-1 / OQ-36-7).** Once an owner publishes Diagnosis or vital readings, the shared ward list carries them for every admitted patient to OWNER, DOCTOR and NURSE with no record-access row (DEC-021 covered the SAH-36 card). Confirm the exception extends to these fields, and add them to the privacy-register entry (DEC-021 go-live condition).
- **O4 (mobile on the list).** Mobile switched on sends it for every admitted patient to OWNER and FRONT_DESK; the owner's preview (`cardFacts=all`) loads it whenever the setup page opens. This amends ADR-013 D15 ("the mobile number is never mapped into the list"); the mobile comes only from the separate explicit mobile read of ADR-015 A3, never from `findAllCurrent`. Recommended: accept as the owner's explicit choice; alternative: leave Mobile out of the catalogue.
- **O5 (staff name on the list).** "Last recorded" shows the recorder's name (mockup `vtime`: "35 min ago · Sr. Anjali Pawar"); a staff name, sent only when the field is on. Confirm.
- **O6 (template content).** Under DEC-027 the standard has the readings and Diagnosis off, so the Hospital template ("Expanded card ... every field") still shows no tiles or Diagnosis; the mockup's Hospital card has them because its default had them. Recommended: keep rule 6 as written (no template switches on clinical data); alternative: Hospital also switches on the readings, Last recorded and Diagnosis.
- **O7 (history).** Discard and Reset are not in the Clinic setup change history (mockup `cfgLog`, OQ-37-9); only Save draft and Publish are. A Save draft equal to the stored draft writes no line. The Publish row (`IPD_CARD_PUBLISHED`) now also holds `factsOn`: the field keys only of the sensitive facts the published card switches on (diagnosis, the vitals fields, mobile), never a label or patient data (ADR-015 D9).
- Go-live condition (DEC-027): a clinician signs off the default card and the tile marks.
