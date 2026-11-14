# ADR-022 · Doctor EMR phase 3: shared components (EMR list, prescription and services, body map, patient history, live case paper), treatment course with payments, and the form builder

Stories: SAH-48, SAH-50, SAH-55, SAH-56, SAH-57, SAH-58, SAH-59 (phase 3). Forward compatibility only: SAH-49, SAH-51, SAH-60 to SAH-71.
Mockup: `docs/design/mockup/EMR-Configuration-Smart-Clinic.html` (`window.__DC_SRC`: #EmrList, #GeneralTreatment Prescription (`FAVS`, `LAST_RX`), #BodyMap, #VisitHistory, #CasePaper, #TreatmentCourse (`addOpts`, `ledger`, payment panel), #FormBuilder).
Modules: **opd** (EMR list, prescription, body map, history, case paper, treatment course plan, forms), **billing** (course account: payments, receipts, credit carried to IPD; EMR bill lines on the visit bill), **ipd** (admission linked to a course sitting; deposit carried from the course). No new module, no new library, no external service.
Status: PROPOSED (12 Nov 2026). Stories pre-approved by the human in chat (DEC-039 addenda). The `contract` stage (this ADR, `docs/api/opd.yaml` 0.4.0, `docs/api/billing.yaml` 0.2.0, `docs/api/ipd.yaml` 0.2.0) still needs the human's approval. The items in "Human decides" are defaults, not choices: each is outside or beyond the mockup.
Builds on: ADR-020 (record model D8, value kinds D3, snapshot, ports D9, permissions D11, extension points D15), ADR-019 (masters D3, usage ranking D10), ADR-021 (WhatsApp, ABDM, PDF: in-app parts only, sending off), ADR-002 (tenancy, audit), ADR-017 (IPD scope, break-glass).

---

## Context

Facts checked in the code and contracts on 12 Nov 2026 that decide the shape:

- **Module graph today:** `billing → opd.api, patient.api, platform.api`; `ipd → opd.api, patient.api, platform.api`; `hra → opd.api`; `opd → patient.api, platform.api` only. `opd` is the lowest business module. **Any `opd → billing.api` or `opd → ipd.api` import is a cycle and fails `ApplicationModules.verify()`.** `billing.api` is empty today.
- **Visit bill** (SAH-10): `billing.bill` per visit, `billing.bill_item` (deletable compose-time lines, price snapshot via `opd.api.ServiceLookupPort`), `billing.payment` append-only. Collect is `OWNER`, `FRONT_DESK` in code (`BillingRoles.COLLECT`); the `BILLING` role only reads. There is **no receipt number and no receipt view** in billing today.
- **IPD money** lives in `ipd`: deposits (`POST /ipd/admissions/{id}/deposits`), the running bill computed on read, the final bill frozen at discharge. Billing has no IPD data.
- **Medicine master** `opd.medicine` (global: name with strength, form, schedule, usual dose); `opd.medicine_favourite` per clinic, no saved frequency, days or "when". Co-trimoxazole is not in the master; three Amoxicillin rows are.
- **Library fields** (ADR-020 seed): `complaints.complaint`, `diagnosis.provisional_diagnosis`, `investigations.lab_tests`, `advice.advice` (MASTER), `procedures.procedure` (SERVICES), `rx.drug` (MASTER medicines) and their sibling fields (`complaints.duration`, `rx.dose_frequency` …) describe one value per section, not rows. `allergy.drug_allergy` is a free-typed SEARCH_LIST. Fields of type BODY_MAP store `{text}` today (STRUCTURED).
- **Nothing is signed yet.** SAH-51 (phase 5) adds SIGNED. Until then "last signed", "earlier signed prescription", history details and copy-from-last read nothing.
- **Record storage** is one JSON document per record, monthly partitions, 100 to 180 million records a year at target scale (ADR-020 D8). Rows per EMR item would be 1 to 2 billion rows a year.

---

## Decisions

### D1 · Where each piece lives (no cycles)

| Piece | Module | Why |
| --- | --- | --- |
| EMR list, prescription rows, body map pins, form values | opd, inside the record's `sections` document | One record model (ADR-020 D8); clinical; no rows per item at this scale |
| Allergy classes, medicine allergy flag and defaults | opd (global reference) | Extends the SAH-7 medicine master |
| Patient history, key facts, copy actions | opd (reads ipd through an inverted port) | The EMR's own data; IPD read like ADR-020 D9 |
| Live case paper context | opd (letterhead through `platform.api`) | Composition of EMR values |
| Treatment course **plan** (course, sittings, links to visits and admissions) | opd | Clinical plan started by the doctor from the EMR; billing and ipd already depend on `opd.api` |
| Treatment course **account** (payments, receipts, credit carried to IPD, derived adjustments) | billing | Money and receipt numbering are billing's (BR45-1 reuse, SAH-58 "through the billing api") |
| Admission linked to a course sitting, deposit carried in | ipd | The admission sheet and deposits are ipd's (SAH-23) |
| Form definitions, versions, approval, starter forms | opd | Forms become EMR sections (ADR-020 D15) |

Allowed dependency graph after phase 3 (Spring Modulith verifies it):

```
platform  <-  patient  <-  opd  <-  billing  <-  ipd
                            ^                     |
                            +---------------------+   (ipd -> opd.api, existing)
```

- **New edge: `ipd → billing.api`** (one port, `CourseCreditPort`). Allowed because billing never depends on ipd. Rule recorded here: **billing must never import `ipd`**; if billing ever needs IPD data, it declares a port in `billing.api` that ipd implements.
- opd never imports billing or ipd. Where opd must reach them, `opd.api` declares a port that the other module implements (dependency inversion, as `EmrEncounterSource`):

| Port (package) | Declared in | Implemented by | Called by | Purpose |
| --- | --- | --- | --- | --- |
| `EmrVisitChargePort` | `opd.api` | billing | opd (record draft save) | Make the visit bill's EMR lines equal to the "Bill today" services (SAH-48-4) |
| `PatientIpdHistorySource` | `opd.api` | ipd | opd (history) | The patient's admissions with access per caller (SAH-50) |
| `CourseAdmissionSource` | `opd.api` | ipd | opd (course view) | Status, IP number and bill total of admissions linked to sittings (SAH-58) |
| `TreatmentCoursePort` | `opd.api` | opd | billing (account), ipd (admission) | Read a course and its sittings; link an admission to a planned sitting |
| `CourseCreditPort` | `billing.api` | billing | ipd (admission) | Carry the course's earmarked IPD-deposit credit to the admission |
| `ClinicLetterheadPort` | `platform.api` | platform | opd (case paper) | Clinic name, address, phone |

All ports run in the caller's transaction with the tenant already bound (RLS applies); another clinic's id is "absent"; `toString` of their records shows ids only.

Rejected: domain events for these calls (each is a read that needs an answer, or a write that must commit with the caller's action, ADR-019 D10 reasoning); browser-only composition for money totals (the invariant "paid, credit, balance" must be computed by one server).

### D2 · Component fields: how shared components sit inside library sections

ADR-020 D3 gives one value per field. Shared components need rows. Decision: a **component binding**, fixed in code (not data), turns some fields into component fields and hides their siblings.

| Field (section.field) | Component | Covered sibling fields (not rendered, not stored, not required) |
| --- | --- | --- |
| any SEARCH_LIST or CHIPS field with `optionsSource` MASTER `complaints` | EMR_LIST kind COMPLAINTS | in `complaints`: `duration`, `severity` |
| … MASTER `diagnoses` | EMR_LIST kind DIAGNOSIS | in `diagnosis`: `final_diagnosis`, `type` |
| … MASTER `investigations` | EMR_LIST kind INVESTIGATIONS | in `investigations`: `radiology` |
| … MASTER `advice` | EMR_LIST kind ADVICE | none |
| … `optionsSource` SERVICES | EMR_LIST kind SERVICES | none |
| … MASTER `medicines` | PRESCRIPTION | in `rx`: `dose_frequency`, `duration`, `timing`, `instructions` |
| any field of type BODY_MAP | BODY_MAP | none |

- The field definition returns `valueKind: COMPONENT` with `component {type, listKind}`; covered fields return `valueKind: COVERED`. Both are new `EmrValueKind` values (additive; the phase-2 client shows unknown kinds as "Not available yet"). TOOTH_PICKER stays STRUCTURED until SAH-49.
- **Done rule** (extends ADR-020 D8): a COMPONENT field is filled when the EMR list has at least one **active** item, the prescription has at least one row, or the body map has at least one pin or `noFindingsConfirmed` (SAH-57-4). COVERED fields count as absent. So `complaints` is Done with one active complaint, even though the seed marks `complaints.duration` required.
- **Legacy values:** a phase-2 draft may hold an ITEM_LIST array or `{text}` for these fields. The server reads it as a component value (each entry becomes an active item with default attributes; `{text}` of a body map becomes a body map with no pins and the text kept as `legacyText`) and writes the component shape on the next save.
- Component values are written by the existing `PUT /emr/records/{id}/draft` (one write path, record revision, 409 on stale), except the copy actions (D6). Values are validated against the binding; the client cannot set server-stamped properties (below).

### D3 · EMR list (SAH-56): items are record section values, not rows

Decision: **items live in the record document** under their field: `{ "component": "EMR_LIST", "kind": "COMPLAINTS", "schemaVersion": 1, "items": [ … ] }`.
Rejected: a table `opd.emr_record_item` (1 to 2 billion rows a year; joins on every record read; the reads are always "all items of one record" or "all records of one patient", which the document serves).

Item: `id` (uuid made by the client: it is the idempotency key for adds, Undo and bill lines), `itemKey` (master key, ICD-10 code for diagnoses, service id for services; null for typed), `text` 1 to 200, `code` (diagnosis from the master only; set by the server from `itemKey`, never typed), `newToList`, `attr1`, `attr2`, `active`, and **server-stamped** `addedAt`, `addedByMembershipId` (taken from the first save that contains the id; values sent by the client are ignored). Order = array order (order of adding). At most 50 items per list; a second item with the same text ignoring case answers 400 `duplicate-item`; a typed item gets its first letter capitalised by the server.

Attribute value sets (fixed product lists; mockup #EmrList):

| Kind | attr1 (default) | attr2 (default) |
| --- | --- | --- |
| COMPLAINTS | duration `D1 D2 D3 D5 W1 W2 M1 OVER_M1` (D3) | severity `MILD MODERATE SEVERE` (MODERATE) |
| DIAGNOSIS | type `PROVISIONAL FINAL DIFFERENTIAL COMORBIDITY` (PROVISIONAL) | status `ACTIVE CHRONIC RESOLVED` (ACTIVE) |
| INVESTIGATIONS | priority `ROUTINE URGENT BEFORE_NEXT_VISIT` (ROUTINE) | where `CLINIC_LAB OUTSIDE_LAB` (CLINIC_LAB) |
| ADVICE | language `EN MR HI` (the record's print language, else the clinic's Case paper language) | sendBy `PRINT WHATSAPP PRINT_WHATSAPP` (PRINT) |
| SERVICES | qty `1 2 3 4` (1) | billing `BILL_TODAY NO_CHARGE IN_PACKAGE` (BILL_TODAY) |

- `sendBy` WHATSAPP and PRINT_WHATSAPP answer 400 `needs-whatsapp-service` until ADR-021 phase C (SAH-56-2 waits for DEC-040).
- An item with `active: false` stays, is muted, and is left out of Done, the case paper, the bill lines and the allergy check.
- "Added by" names are resolved on read through `platform.api.TeamMemberPort` (not copied).
- **Usage ranking is not written in phase 3.** "Your most used" reads `GET /emr/quick-options/{master}?limit=7&specialty=<starterKey>` (SAH-43, built). `EmrUsageRecorder` is still called only by the SAH-51 sign service (ADR-019 D10; SAH-56-1 criterion "counted only when the visit is signed"). Typed items (`newToList`) can never reach it (`MasterItemRef` type).
- Services chips come from `GET /services?active=true` (SAH-7) in tariff order; services have no ranking.

### D4 · Prescription (SAH-48)

Storage: `{ "component": "PRESCRIPTION", "schemaVersion": 1, "rows": [ … ] }` under `rx.drug` (and under a form's MEDICINE_LIST field, D11).
Row: `id` (client uuid), `medicineId` (null only for a typed medicine), `name` 1 to 160 (generic name with strength, copied from the master), `form` ≤ 40, `frequency` `1-0-0 | 1-0-1 | 0-0-1 | 1-1-1` (shown with OD, BD, HS, TDS), `days` 1 to 365, `when` `AFTER_FOOD | BEFORE_FOOD | BEDTIME | MORNING_EMPTY_STOMACH`, `source` `SEARCH | FAVOURITE | REPEAT | COPY | TYPED`, `allergyReview` (D5), server-stamped `addedAt`, `addedByMembershipId`. At most 30 rows. A second row with the same name ignoring case answers 400 `duplicate-item` (the client never sends it; the copy actions skip it). Any value outside the lists (for example `days` 0) answers 400 `validation` and nothing changes.

- **Generic names, capitals:** the editor shows the stored name. Capitals are a print rule only: the case paper context returns `capitals` (the SAH-7 `caps` setting) and the client prints the medicine names upper-cased (SAH-48-1, SAH-55-1, OQ-45-10). Nothing upper-case is stored.
- **Medicine search for the EMR:** `GET /emr/medicines?q=&page=` (doctor only): prefix match like SAH-7, order = this doctor's use count (signed visits) desc, then clinic favourites, then name; each result carries `allergyClass` and the row defaults. `GET /medicines` (SAH-7) is not changed.
- **Favourites:** `GET /emr/medicine-favourites` lists the clinic's `opd.medicine_favourite` rows with the defaults. **Per clinic, one list** (ADR-020 D13.3; SAH-48-1 "favourites stay per clinic"). The mockup's `FAVS` is a single list on #GeneralTreatment; neither the story nor the mockup filters favourites by department, so no department filter is built (Human decides H7).
- **Row defaults** ("tapping one adds it with its saved frequency, days and when"): additive nullable columns on the global master `default_frequency`, `default_days`, `default_when`, seeded for the medicines of the mockup's `FAVS` and `LAST_RX` with the drawn values (tagged). A medicine with no seeded defaults gets: frequency = its SAH-7 `schedule` when that is one of the four values, else `1-0-1`; days 5; when AFTER_FOOD (Human decides H6: not drawn).
- **Repeat last Rx:** `POST /emr/records/{id}/copy-items` with `source LAST_SIGNED_PRESCRIPTION` (D6). The record view returns `lastSignedPrescription` (record id and date, or null → the button is disabled with "No earlier prescription"). Null for every patient until SAH-51.
- **Clear all, remove, Undo:** client changes saved through the draft save.
- **Advice language and the printed prescription (SAH-48-3):** the visit's print language is stored on the record (`print_language`, D9), default the clinic's Case paper and Rx language. Advice items keep their own Language attribute, which wins on the paper (SAH-55-2, SAH-56-2); changing the visit language does not rewrite existing items (Human decides H11). Advice text in Marathi or Hindi comes from `emr_advice.name_mr/name_hi` (null today, English printed with "No Marathi text, English used").
- **WhatsApp send of the prescription:** not built; the client shows it disabled with "Needs the WhatsApp service" (ADR-021, DEC-040 phase C).
- **IPD:** an IPD_ROUND record's prescription is the doctor's note only and creates no medicine order; new IPD orders stay on the SAH-31 path with its own checks (OQ-43-24). The record view returns `prescriptionIsOrder: false`; nothing in opd calls ipd for medicines.

### D5 · Drug-class allergy check (SAH-48-2; rule C-2, tagged)

- **Allergy classes** are a global reference table `opd.emr_allergy_class` seeded with exactly the two drawn classes: `SULFA` "Sulfa drugs", `PENICILLIN` "Penicillin". Tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed. Adding classes is a later clinical decision (a migration).
- **Medicine flag:** additive nullable `opd.medicine.allergy_class` (FK to the class table). Seed: a new master row "Co-trimoxazole 960 mg" (Tablet, `SULFA`, defaults 1-0-1, 5 days, After food, as drawn) and the existing Amoxicillin rows → `PENICILLIN`. The IPD medicine list (SAH-31) is separate and not touched.
- **Patient's classes for this visit:** the items of `allergy.drug_allergy` whose `itemKey` is an allergy class key (picked from `GET /emr/allergy-classes`). Free text in that list (itemKey null) is shown but never triggers the check. The section shows the analyst's safety line "Allergy check covers listed allergy classes only. No alert is not a clearance." (flagged in the story for the human).
- **Check (server, on every read and save):** for each prescription row (and each form MEDICINE_LIST row) whose medicine's class is among the visit's classes → an alert `{sectionKey, fieldKey, rowId, medicineKey, medicineName, allergyClass, reviewed, reviewedAt, reviewedByName}`. The client may compute the same live; the server's answer wins.
- **Override ("I have reviewed the alert and want to prescribe anyway"):** the client sets `allergyReview: {allergyClass, confirmed: true}` on the row in a draft save. The server stamps `reviewedAt`, `reviewedByMembershipId` and writes one audit `EMR_ALLERGY_ALERT_REVIEWED` (record id; after `{medicineKey, allergyClass}`; no names). The review is bound to the row id and the class: removing the row and adding it again makes a new id (alert returns); when the visit's set of allergy classes changes in a save, the server clears every review on the record (alert returns), as SAH-48-2 says. That includes a review sent in the same save as the change, for example with the first class. It is cleared and no `EMR_ALLERGY_ALERT_REVIEWED` is written for it, and the doctor confirms again in a later save (DEC-051 D1, opd.yaml 0.6.3).
- **Effect:** `readyToComplete` is false and `completeBlockers` contains `ALLERGY_ALERT` while any alert is unreviewed (footer "Remove the drug or confirm the allergy alert before completing." in place of "Still needed"); the case paper context returns `printBlocked: ALLERGY_ALERT` (Print disabled, "Resolve the allergy alert first"). Complete itself is SAH-51.
- Nurse, front desk, billing, pharmacist, counsellor: 403 on every write (as all record writes).

### D6 · Copy actions (Repeat last Rx, Copy medicines to today, Repeat these tests)

`POST /emr/records/{id}/copy-items` `{source: LAST_SIGNED_PRESCRIPTION | PAST_RECORD, sourceRecordId?, what: MEDICINES | TESTS}`.
- Same access as a draft save on the target record (own patient, DRAFT, 409 `emr-record-signed` when signed: "This visit is signed"). The source must be a SIGNED record of the same patient in this clinic (else 404 `past-visit-not-found`).
- Under the record row lock; **no revision is required**: the action is idempotent by content because items already present (same medicine name, same test text, ignoring case) are skipped. A double tap adds once. Returns `{added, skipped, record}`.
- MEDICINES go to the target's `rx.drug` rows with frequency, days and when (new ids, `source` REPEAT or COPY); the allergy check runs on the result. TESTS go to `investigations.lab_tests` with default attributes; nothing is sent to a lab (OQ-45-28).
- The target section must be in the record's layout, else 409 `section-not-in-layout` (for example Physiotherapy has Rx hidden).
- One audit `EMR_ITEMS_COPIED` (record id; `{sourceRecordId, what, added, skipped}`; never names).

### D7 · Services ticked → visit bill lines (SAH-48-4)

- Applies to **OPD_VISIT records** and to the items of the field `procedures.procedure` with `billing BILL_TODAY` and `active` (Human decides H8 for IPD_ROUND and ER_ASSESSMENT: no bill line, the item is recorded and printed; IPD charges stay on the IPD bill, SAH-25). Other SERVICES fields (dental plan, surgery plan) create no bill line until their phase-4 story says so.
- In the draft save transaction that contains the `procedures` section, opd calls `EmrVisitChargePort.syncEmrLines(tenantId, actorUserId, visitId, lines)` with the full wanted set `[(sourceKey = item id, serviceId, qty)]`. Billing (implementation) makes the visit bill's lines with `source_kind = 'EMR'` equal to it: insert missing (price snapshot through `ServiceLookupPort`, as SAH-10 adds an item), update qty, delete lines no longer wanted. Lines added at the counter (`source_kind` null) are never touched. The consultation line is never added by the EMR.
- **Idempotent:** `unique (tenant_id, bill_id, source_key)`; a retry or double tap (same item id) gives one line.
- **Paid bill:** when the bill has any payment, a removal or a qty change of an EMR line is refused: the port returns `ALREADY_BILLED`, opd rolls the whole save back and answers 409 `already-billed` ("Already billed. Ask billing to adjust."); adding a new line is still allowed (it raises the due amount at the counter). (Human decides H14: the stories say "paid"; a partly paid bill is treated as paid, so the EMR never edits a bill money was taken against.)
- An inactive service, an unknown service id or another clinic's service: 400 `validation`.
- Billing audits `BILL_ITEM_ADDED_FROM_EMR`, `BILL_ITEM_REMOVED_FROM_EMR`, `BILL_ITEM_QTY_CHANGED_FROM_EMR` (visit id, service id, qty, amount; no patient name). No log line holds a service name with a patient.
- Billing users see the lines on the existing counter; they cannot call EMR writes (403).

### D8 · Body map (SAH-57): pins are record section values; MLC audit on change

Storage under the BODY_MAP field: `{ "component": "BODY_MAP", "schemaVersion": 1, "pins": [ … ], "nextPinNo": n, "noFindingsConfirmed": false }`. Saved through the draft save (SAH-57-4 "reports pins as a list value to its host; the host stores it").
Pin: `no` (1 up), `side` FRONT | BACK, `x`, `y` (0 to 1 of the drawing box), `region` (key of a drawn region, left and right separate; the list is fixed in code from the mockup's drawing), `where` ≤ 80, `finding` WOUND SWELLING TENDERNESS BRUISE BURN RASH LUMP FRACTURE_SUSPECTED SCAR PAIN ("Fracture?"), `cause` NO_INJURY FALL ROAD_ACCIDENT ASSAULT ANIMAL_BITE BURN SPORTS WORK_INJURY, `severity` MILD MODERATE SEVERE, `sizeCm` 0 to 50 in steps of 0.5 (0 = not measured), `note` ≤ 500, `mlc`, server-stamped `recordedAt`, `recordedByMembershipId`. At most 60 pins. Lists are tagged (C-10, L-1).
- **Numbers are never reused:** the server keeps `nextPinNo`; a new pin must carry `no >= nextPinNo` and the server sets `nextPinNo = max + 1`; a pin removed and its number sent again answers 400 `validation`.
- **MLC rule (L-1, tagged):** when a pin is new with, or changes its cause to, ROAD_ACCIDENT, ASSAULT or BURN, the server sets `mlc = true` whatever was sent; otherwise the sent value stands (the doctor can set or clear by hand; a cause changed away keeps the flag until cleared). Every change of a pin's `mlc` (including a new pin with `mlc` true and removal of an MLC pin) writes one audit `EMR_MLC_FLAG_CHANGED` (record id; `{pinNo, mlc}`; no finding, cause or note).
- Record header gains `mlc` (true when any active BODY_MAP value of the record has an MLC pin); the case paper's Examination line adds "MLC case".
- Sex shown, skin tone, front/back view and zoom are display state, **not stored** (SAH-57-1). No photo is stored (OQ-45-28). No message is sent to anyone.

### D9 · Live case paper (SAH-55): a context endpoint; composition in the client; print by the browser

- The paper "changes at once without saving", so it is **composed in the client** from the in-memory record values by one shared pure function (also used by the form print, SAH-59-5). The server supplies what the client cannot know: `GET /emr/records/{id}/case-paper` → letterhead (clinic name, address, phone through `ClinicLetterheadPort`), department, doctor (name, degree as qualification, registration number or null → "Reg. no. not set", read through `TeamMemberPort`, not copied), patient (name, age, sex, UHID or null → "New patient"), visit line, clinic defaults (size, language, capitals from `opd.case_paper_settings`), the record's chosen size and language, the ten headings in English, Marathi and Hindi (the table of SAH-55, fixed UI labels returned so print and a future server PDF use one source), the **heading of every section in the record's layout** (map below), signature state (SIGNED: signer name and time; SAH-51), `printBlocked`, and `share {available: false, reason}` ("Needs the WhatsApp and ABHA services").
- **Section → heading map** (fixed in code, returned per section; phase-4 stories may extend it): `complaints`, `hpi` → 1 Chief complaints; `allergy` → 2 Allergies; `history`, `chronic` → 3 Medical history; `vitals` → the vitals line; `diagnosis` → 5 Diagnosis; `investigations` → 6 Investigations advised; `rx` → 7 Medicines ℞; `procedures`, `dental_done` → 8 Procedures & services; `advice` → 9 Advice; `followup`, `referral` and the record's `referralLine` → 10 Follow-up; `abha`, `consent`, `rxformat`, `pcpndt`, `onc_scheme`, `sx_consent`, `sx_checklist`, `sx_preauth`, `attachments`, `therapy_notes` → NONE (never printed as content); every other section (including form sections, D11) → 4 Examination. Only sections with Print on (snapshot) reach the paper; inactive EMR list items are left out.
- Per-visit choices: `PUT /emr/records/{id}/print-preferences` `{language: EN|MR|HI, paperSize: A4|A5}` (doctor, own patient; DRAFT or SIGNED; no revision, last write wins; not clinical; no audit). They never change the clinic setting. "Hide empty" is screen state only.
- **Print = browser print** of the paper (A4 or A5, Devanagari fonts bundled in the frontend). **No server PDF:** it waits for the DEC-040 one-week trial and the human's library go-ahead (ADR-021 D4, H3). **No share:** WhatsApp and ABHA wait for the human's CLAUDE.md edit creating `notifications` and `integration` (ADR-021 D1). The footer lines follow SAH-55-1 (the ABHA line only when ABHA sharing exists: never in phase 3).
- Nothing about the print is stored or logged.

### D10 · Patient history (SAH-50)

- **Endpoint shape is record-scoped:** `GET /emr/records/{recordId}/past-visits?type=ALL|OPD|IPD&q=&page=` and `GET /emr/records/{recordId}/past-visits/{kind}/{id}`, `GET /emr/records/{recordId}/key-facts`. The patient is the record's patient; access is the record's access (own patient for OPD, IPD scope for IPD and ER; other roles 403). There is no patient-id history endpoint, so the history cannot be read for a patient the doctor has no encounter with. An Emergency record with no patient answers an empty page ("First visit. No earlier records.").
- **Rows:** OPD = earlier EMR records of the patient in this clinic (OPD_VISIT; every department, SAH-50-1), excluding the current one; IPD = the patient's admissions from `PatientIpdHistorySource` (ipd), one row per admission; when the current record is an IPD_ROUND, the same admission's earlier round records come first (SAH-52-3). Newest first. **Page size 8** (the BRD's "8 earlier visits" kept as the page; "Load more" for the rest, SO-45-15). `counts {opd, ipd}` are totals for the patient (scope-filtered).
- **Title and place:** OPD title = first active diagnosis name, else first complaint, else the visit type label; place = "Dr. <name> · <department>". IPD title = reason for admission (+ " · day care" for a daycare admission); place = "Dr. <admitting doctor> · <ward> bed <code>".
- **Details** come only from SIGNED records (Human decides H12): labelled lines from the printed headings, medicines (name, frequency, days, when), results (the `investigations.results` table; "Abnormal" only when the stored row says so; no limits are set here), body map lines, form entries. Before SAH-51 every OPD row shows "No signed record yet." IPD details = admission and discharge dates, discharge type and the round records' signed content; ipd holds no discharge-summary text or medicines beyond the reason for admission.
- **IPD scope:** ipd builds the caller's scope (ADR-017) and marks each admission ALLOWED, OUTSIDE_SCOPE or FORBIDDEN (visiting doctor). Only ALLOWED rows are listed, matched and counted; `someHidden` true shows "Some entries are hidden". Break-glass use is audited by ipd as today; the history writes no break-glass of its own.
- **Search:** server-side, in the application over the patient's rows (at most the 500 newest records and 200 admissions are scanned; the page says nothing about the cap): case-insensitive "contains" on title, complaint, diagnosis name and code, medicine name, test name and place. No SQL over clinical JSON, no new index, no search engine. `q` ≤ 100, never logged.
- **Open full visit:** `GET /emr/records/{recordId}/past-visits/OPD/{pastRecordId}/record` returns the signed record read-only (EmrRecordView, `readOnly true`, reason `HISTORY`). "Open discharge summary" opens the existing IPD admission view (ipd's own rules and scope).
- **Key facts (SAH-50-2):** four labels per the record's `starterKey` as drawn for gm, dental, og, paeds, physio, onco, surgery; the other eight use the gm labels (analyst's choice, flagged in the story). Each fact has a fixed source list in code: the patient record (`allergyText`, `chronicText` through `PatientClinicalPort`) and the latest SIGNED value of named section fields of the patient's records (for example Allergies ← `allergy.drug_allergy` and patient allergy text; Long-term ← `history.past_illness` and patient chronic text; Surgeries/Past surgery ← `history.surgical_history`; Regular meds ← last signed `rx` rows; Habits ← `history.habits`; Blood thinners ← `sx_fitness.blood_thinners`; Diagnosis ← last signed active diagnoses; the others ← the drawn specialty section of the same name). Null → "Not recorded"; never "None known" unless "none" was recorded (OQ-45-23). The Allergies line of the current record also reflects this visit's unsaved `allergy` values (client). `important` flags as drawn (Allergies, Habits, Obstetric, Risk, Vaccines overdue, Oncology Diagnosis, Surgery Medical), with text, not colour only. The developer lists the exact field keys from the seed in the code with the tag.
- No audit for reads (as ADR-020 record reads). No log line holds a diagnosis, drug, test or search text.

### D11 · Form builder (SAH-59): forms as data, versions, approval, sections

**Data.** A form is tenant data in opd: `emr_form` (key, name, status, the working draft as JSON with `schemaVersion`, draft state, live version number, revision) and `emr_form_version` (immutable published versions, kept for ever). Six starter forms are global reference data `emr_form_starter` seeded from the mockup (tagged C-9, L-1 for MLC).
- **Form key** `f_` + 10 lower-case base32 characters, server-made, never reused. It is also the **section key** of the form in department layouts and records. Library section keys never start with `f_` or `d_` (rule for later library migrations).
- **Definition** (`schemaVersion` 1): `name` 1 to 80 (unique in the clinic ignoring case, H15), `settings` and `fields` (1 to 80 to publish; 0 allowed in a draft).
- **Settings:** `useIn.departmentKeys` (the clinic's departments visible to doctors: the 15 starter-backed ones and published added ones), `useIn.places` EMERGENCY (ER_ASSESSMENT records) and IPD_WARD (IPD_ROUND records), `emrPart` ASSESSMENT | TREATMENT, `visitTypes` NEW_CONSULT FOLLOW_UP PROCEDURE_VISIT TELECONSULT IPD_DAILY_ROUND (**stored and shown only**, one configuration for all visit types as OQ-45-18; H10), `whoCanFill` DOCTOR NURSE RECEPTION PATIENT (stored and shown only, OQ-45-14; no patient screen; a link on WhatsApp waits for DEC-040), `printLanguage` EN | EN_MR | EN_HI, `approvalRequired` (default true).
- **Fields (23 palette types → `EmrFormFieldType`):** SHORT_TEXT, LONG_TEXT, NUMBER, DATE, TIME, YES_NO, SINGLE_CHOICE, MULTI_CHOICE, DROPDOWN; VITALS_BLOCK, BODY_MAP, TOOTH_CHART, MEDICINE_LIST, LAB_TESTS, DIAGNOSIS, SCORE, SCORE_BAND; SECTION_HEADING, INFO_NOTE, SIGNATURE, PHOTO, FILE_UPLOAD, HANDWRITING_PAD.
  Field: `key` (client-made `^[a-z][a-z0-9_]{0,39}$`, unique in the form, kept across versions so "Copy from last visit" matches), `type`, `label` 1 to 200 (+ optional `labelMr`, `labelHi` ≤ 200, shown only when the print language needs them: analyst addition), `help` ≤ 500, `required`, `width` 1 to 6 (six-column grid), `unit` ≤ 20, `min`/`max` (hard limits), `normalFrom`/`normalTo`, `options` (choice types; 1 to 30; key, label ≤ 120, `labelMr`, `labelHi`, `score` integer −100 to 100, default 0), `print`, `copyFromLast`, `whoCanFill`, `standardCode` ≤ 60 (stored text such as "LOINC 32443-7"; no exchange, DEC-040), `showIf {fieldKey, optionKey} | null`, `score {sourceFieldKeys, rules[]}` (SCORE only; rules: `NUMBER_BELOW` or `CHOSEN_COUNT_BELOW` with `fieldKey`, `threshold`, `points` −100 to 100; at most 10), `band {sourceFieldKey, bands[{from, label ≤ 120}]}` (SCORE_BAND only; 1 to 10 bands). All text is plain text; no markup is run.
- **Value kinds in a record** (stored under the form's section key, `values` keyed by field key; the record's `form_refs` holds the form version): SHORT_TEXT string ≤ 200; LONG_TEXT ≤ 4,000; NUMBER finite, within `min`/`max` when set (outside → 400 "Outside <min>–<max>"); DATE; TIME `HH:mm`; YES_NO boolean; SINGLE_CHOICE, DROPDOWN option key; MULTI_CHOICE array of option keys; VITALS_BLOCK object (bpSystolic, bpDiastolic, pulse, spo2, temperatureF, weightKg, heightCm); BODY_MAP, MEDICINE_LIST, LAB_TESTS, DIAGNOSIS the component values of D8, D4, D3 (kinds INVESTIGATIONS, DIAGNOSIS); SCORE, SCORE_BAND, SECTION_HEADING, INFO_NOTE store nothing (computed or display); TOOTH_CHART LATER until SAH-49-1; SIGNATURE, PHOTO, FILE_UPLOAD, HANDWRITING_PAD LATER (disabled with "Later"; nothing stored; OQ-45-27, OQ-45-28).
- **Show-if:** source must be an earlier YES_NO, SINGLE_CHOICE, MULTI_CHOICE or DROPDOWN field and an existing option (YES_NO options are `yes`, `no`); chains allowed, evaluated top to bottom; a hidden field is empty: not required, no score, not printed, and **its values are dropped by the server on save** (analyst's rule, flagged in SAH-59-2). A rule pointing to a later or missing field is refused on save of the draft (400 `form-invalid` with the field named), so the form is never stored broken. Deleting a field that is a show-if or score source is refused in the builder (client) and on save (400).
- **Scores and bands (C-9, tagged):** total = sum of chosen option scores of the source fields (all chosen options of a MULTI_CHOICE) + rule points; blank until one source is answered. Band = highest `from` not above the total; colour by rank (green → amber → red) plus the label. Normal range: inside min/max but outside from/to → accepted and flagged "Abnormal". The server computes the same for Done and for history; the client computes live.
- **Publish checks** (draft saves never fail on these): at least one field ("Add at least one field"), at least one department or place ("Pick at least one department in Form settings first."), every SCORE_BAND has a SCORE source and bands sorted strictly ascending whose lowest `from` is not above the lowest possible total, labels present. Failure → 400 `form-not-publishable` with `fieldErrors`.
- **States and versions:** the form row holds one working draft with `draftVersionNo` (= live + 1) and `draftState` DRAFT | WAITING_APPROVAL | SENT_BACK. Save draft keeps or returns the state to DRAFT ("Draft saved"). With `approvalRequired`: Send for approval → WAITING_APPROVAL; Approve → a new immutable `emr_form_version` (LIVE), `liveVersionNo` = it, draft cleared; Send back with a reason (1 to 300) → SENT_BACK. Without approval: Publish → LIVE at once. Header chip "v<n> · draft | waiting approval | live". Retire → status RETIRED: no new record gets the form; records keep it; departments drop it. Optimistic locking on `revision` (409 `form-changed`). A live form edited gets draft v<n+1>; v<n> stays in use until v<n+1> is live.
- **Who:** OWNER only for every change; **"Medical director" = OWNER** (OQ-45-13, SO-45-24). Two fixed permissions (ADR-020 D11 reserved them): `clinic.emr_forms.edit` (OWNER: create, save, send for approval, publish, retire, use a starter) and `clinic.emr_forms.approve` (OWNER: approve, send back). A clinic with one owner approves its own forms; the approver may be the same person as the editor. If the human later wants a separate approver role, only the mapping of `clinic.emr_forms.approve` changes. Every other clinic role reads forms and starters (403 on change).
- **How a live form becomes a section (ADR-020 compatibility, D15):**
  - Department working layouts (`GET /emr/departments/{key}`) include one section per live form whose `useIn.departmentKeys` contains the department, with `source: FORM`, `formKey`, name = form name, kind SPECIALTY with the tag "Custom". If the saved draft or the latest version does not have it yet, it is appended at the end of the form's `emrPart` with shown true, required false, print true, copy false, collapsed false, whoCanFill from the form. The owner then shows, hides, requires, moves and reorders it like any section; ADR-020 D6 set equality now means "library set ∪ live-form set" (a stale page gets 400 `section-set-mismatch` and reloads).
  - Department versions store form sections as settings only (`source: FORM`, `formKey`), **not the form version**: new records resolve the form's live version at open.
  - **Doctors see a live form at once** (SAH-59-5) without a department publish: at record open, the snapshot = the department version (or starter layout) **plus** live forms for that department (or place) that the version does not hold, appended with the defaults above. The record stores `form_refs` `[{formKey, versionNo, part, position, required, print, copy, collapsed, whoCanFill}]` (small JSON; form versions are immutable, so the reference is as safe as ADR-020's layout reference). Later publishes, approvals or retirements never change an opened record (SAH-46-3).
  - A form removed from a department and published leaves that department for records opened afterwards.
- **"Save to patient record" (SAH-59-5):** the form values are saved with the visit's EMR record (the draft save of that section); the entry is "form key, version, values, time, doctor" inside the record, read by the history (D10) and by "Copy from last visit" (latest SIGNED record of the patient holding that form key). **No copy is written into the patient module** (clinical values stay in the EMR; `patient.api` has no clinical store). The builder's own Preview has no patient and the button is disabled. (H9: the story names the patient api.)
- **Print:** browser print (D9 composition) with the letterhead, Patient, Age / sex, UHID, the shown fields with Print on, "Signature · Dr. <name> · Reg. no. <number>", standard words in the second language for EN_MR and EN_HI (fixed UI words). No PDF.
- **Starter forms (BR45-3):** global, read-only (403 on write for every role), 6 rows (`diabetic_foot`, `gcs`, `morse`, `mlc_injury`, `pre_anaesthetic`, `untitled`) with name, one-line description, suited departments as text and the full definition (fields, options, scores, rules, bands, ranges, show-if) as drawn. "Use this starter" (`POST /emr/forms` with `starterKey`) copies it into a new clinic draft v1, part ASSESSMENT; the starter never changes.
- **Audit** (setup data, no patient data): `EMR_FORM_CREATED`, `EMR_FORM_DRAFT_SAVED`, `EMR_FORM_SUBMITTED`, `EMR_FORM_APPROVED`, `EMR_FORM_SENT_BACK`, `EMR_FORM_PUBLISHED`, `EMR_FORM_RETIRED`; entity EMR_FORM / form id; before and after of the settings, version and state, field count (not field labels in bulk).
- **Limits:** 100 forms per clinic (400 `form-limit`), 80 fields per form, definition ≤ 256 KB, request body 64 KB is raised to 256 KB for form saves only (the developer configures it for these two paths).

### D12 · Treatment course and payments (SAH-58)

**Plan (opd).** `treatment_course`: id, display number `TC-<n>` (per clinic counter; the mockup's TC-0412 and CH-0193 are examples), patient, kind DENTAL | ANC | CHEMO | SURGERY | REHAB, unit SITTING | VISIT | CYCLE | STEP | SESSION (fixed per kind), name 1 to 120 (default per kind as drawn), note ≤ 300 (for example "estimate signed by patient"), doctor membership, start date, origin record, status ACTIVE.
`treatment_course_sitting`: number (S1, S2 … never reused), encounter kind OPD | DAY_CARE | IPD, the add option it came from, planned date (nullable), items (0 to 20 texts ≤ 200), charge in paise, note ≤ 300 (for example "Admission deposit is due by 36 weeks." stored as text, no reminder), linked OPD visit, linked admission, `admissionRequested`, `doneAt`.
- **Add options per kind** (fixed, mockup `addOpts`): DENTAL: OPD sitting (OPD), Day-care admission (DAY_CARE), IPD admission (IPD); ANC: ANC visit (OPD), Emergency admission (IPD), Delivery admission (IPD); CHEMO: Chemo cycle (DAY_CARE), Review OPD (OPD), Admission for side effects (IPD); SURGERY: OPD review (OPD), Re-admission (IPD), Day-care procedure (DAY_CARE); REHAB: Extra session (OPD), Bedside session (IPD) (IPD). With IPD OFF (ModuleEntitlementPort) DAY_CARE and IPD options are not offered and are refused (403 `module-off`). An OPD option copies the last sitting's charge; a DAY_CARE or IPD option has charge 0 and the note "Admission request. Rate set at admission." and `admissionRequested` true.
- **Status** (computed on read): PLANNED; TODAY when an OPD visit is linked and created today, or an admission is linked and not discharged; DONE when the linked visit's record is SIGNED (SAH-51 sets `doneAt` in the sign transaction; never in phase 3) or the linked admission is DISCHARGED. A DONE sitting cannot change (409 `sitting-done`); a PLANNED or TODAY sitting can change date, items, charge, note and encounter kind (OPD ↔ DAY_CARE/IPD before any link).
- **Linking an OPD visit** (SAH-58-2 "the sitting opens its own OPD visit"): the visit is created at the desk as today (SAH-9); the doctor links it from the course page: `POST /emr/courses/{id}/sittings/{sid}/link-visit {visitId}` (same patient, visit not linked to another sitting, sitting not linked, status PLANNED). Not drawn (Human decides H5); default control "This is today's <unit>" on a planned sitting card when the course is opened from today's EMR.
- **Admission request** (SAH-58-2): a DAY_CARE or IPD sitting is the request record in phase 3 (`admissionRequested`). The IPD-side list of requests and the doctor's "Send admission request" are SAH-67-3 (phase 5), which reads open requests through `TreatmentCoursePort`. "Admit now" is shown to the admit roles that may read a course (OWNER, FRONT_DESK; NURSE cannot read courses) and opens the existing IPD admission sheet with `treatmentCourseSittingId` (H13).
- **Starting a course:** `POST /emr/courses` (doctor, own patient through `fromRecordId`, or OWNER with doctor details) with kind, optional name, note and planned sittings (from a specialty screen in phase 4; empty from the generic entry). `Idempotency-Key` header required: the same key returns the first course.
- **Who:** read OWNER, DOCTOR, VISITING_DOCTOR, FRONT_DESK, BILLING (front desk and billing read-only); create, add, change, link: `clinic.consultation.write`, and for an existing course only its doctor or an OWNER with doctor details (403 `not-own-patient`); NURSE, PHARMACIST, COUNSELLOR 403. Optimistic lock on the course `revision` (409 `course-changed`); a sitting add also takes `Idempotency-Key` (a double tap makes one sitting; two different adds at once are both kept and numbered in order under the course row lock).
- **Audit** (ids and money only): `TREATMENT_COURSE_CREATED`, `COURSE_SITTING_ADDED`, `COURSE_SITTING_CHANGED` (before/after of date, kind, charge, status; not item text), `COURSE_SITTING_VISIT_LINKED`, `COURSE_SITTING_ADMISSION_LINKED`.

**Account (billing).** One running account per course (B-1, tagged): `course_payment` (append-only like `billing.payment`: trigger and grants) and `course_credit_transfer` (append-only). Adjustments for credit used are **derived on read**, not stored.
- Definitions (all in paise; every amount on screen in whole rupees):
  - E (course estimate) = Σ charges of sittings **not settled in IPD** (H1).
  - P (paid) = Σ course payments − Σ credit carried to admissions.
  - D (charges done) = Σ charges of DONE sittings not settled in IPD.
  - Credit = max(0, P − D) ("Advance balance · Used against next charges"); Due from earlier = max(0, D − P).
  - Today's charge = the charge of the TODAY sitting not settled in IPD (0 if none); Due today = max(0, D + today − P) with "Use advance balance first", else today + due from earlier.
  - Balance = max(0, E − P) ("Balance on the course: ₹ x").
  - Invariant tested to the rupee: P − D = credit − due from earlier; ledger lines (payments +, transfers −, derived "Advance used · <unit> n" lines when a DONE sitting consumes credit, in time order) add up to paid, credit and balance (SAH-58-4).
- **Settled in IPD (H1):** a DAY_CARE or IPD sitting linked to an admission leaves the course account: it shows the admission's IP number and its running or final bill total (read through `CourseAdmissionSource`) with "Billed in IPD", and its charge leaves E and D. Before linking, a planned admission sitting's charge (if the doctor set one) counts in E. Reason: the IPD running bill, deposits and discharge settlement are the built money path (SAH-23, 25, 27; BR45-1); counting the same stay in two accounts would bill it twice. The mockup has no IPD bill, so this is beyond it.
- **Payment types and checks:** VISIT_PAYMENT ("Today's <unit>"), FULL_BALANCE ("Full course balance"), PART_PAYMENT ("Part payment"), ADVANCE, IPD_DEPOSIT (offered as "Advance / IPD deposit" when the next planned sitting is DAY_CARE or IPD; earmarked to that sitting). Amount: whole rupees (multiple of 100 paise), > 0, ≤ ₹1,00,00,000; VISIT_PAYMENT, FULL_BALANCE and PART_PAYMENT ≤ balance (400 `amount-over-balance`); ADVANCE and IPD_DEPOSIT may exceed it. Mode CASH, UPI, CARD (labels only; no gateway). `useCredit` is recorded for the derived adjustment text.
- **Suggestions** (returned by the account read; tagged B-1): part payment = half of today's amount rounded to the nearest ₹100, at least ₹500, at most the balance; IPD deposit = 40% of the next planned admission sitting's charge rounded to the nearest ₹500 (`Math.round` as the mockup).
- **Receipts:** each payment gets the next number of a per-clinic counter, shown "RC-<n>" (H4: SAH-10 has no receipt number or receipt view today; this numbering covers course payments only). `GET /billing/courses/{id}/receipts/{receiptNo}` gives the printable receipt (browser print). "Send on WhatsApp" is shown disabled (DEC-040).
- **Who collects (H2):** the same rule as `POST /billing/bills/{visitId}/payments` today (`OWNER`, `FRONT_DESK`, plus any "Who can do what" check that endpoint applies). The `BILLING` role reads only, as at the counter today; the story's "billing collects" differs from the built SAH-10 rule. DOCTOR, VISITING_DOCTOR read the account; NURSE, PHARMACIST, COUNSELLOR 403. Receipts: OWNER, FRONT_DESK, BILLING.
- **Idempotency:** `Idempotency-Key` header required on `POST …/payments`; unique `(tenant_id, idempotency_key)` with a hash of the request: same key and body → the first result (200, not a second payment); same key, other body → 409 `idempotency-conflict`.
- **Day closing and revenue (H3):** cash course payments count in the day closing's expected cash, and course payments count in "collected" and the modes of the revenue report (SAH-10, SAH-16), because the money is in the drawer. Credit carried to IPD is not new money.
- **Carry to IPD:** when ipd admits with `treatmentCourseSittingId`, in ipd's admission transaction: (1) `TreatmentCoursePort.linkAdmission` checks the sitting (same patient, DAY_CARE or IPD, not linked, course ACTIVE; else 409 `course-sitting-not-available`) and links it; (2) `CourseCreditPort.carryToAdmission` moves **the IPD_DEPOSIT payments earmarked to that sitting, capped at the course's current credit** (H1 default) into a `course_credit_transfer` row (one per admission) and returns the amount; (3) ipd records a deposit with source COURSE and that amount (no mode). The IPD running bill shows it as "From treatment course TC-<n>"; the admission message "Admitted · <IP number>. Advance from this course is carried to the IPD bill." A refund of surplus at discharge follows SAH-27 (owner only).
- **Audit:** `COURSE_PAYMENT_RECORDED` (course id, receipt no, type, mode, amount; no patient name or items), `COURSE_CREDIT_CARRIED` (course, sitting, admission, amount); ipd's `IPD_ADMITTED` gains `treatmentCourseId` and `carriedPaise`.

### D13 · Record document additions (additive to ADR-020 D8)

- `sections.<key>.values.<componentField>` = the component values of D3, D4, D8; form sections under the form key.
- New record columns: `print_language`, `paper_size`, `form_refs` (D9, D11). Additive, by a **new migration file** (the unmerged V2026_12_08_* files are not edited again, so `sahaj_test` need not be recreated a second time).
- Record view additions: `header.mlc`, `allergyAlerts`, `completeBlockers`, `lastSignedPrescription`, `printPreferences`, form sections in `layout` (`source FORM` with the form version's definition), `prescriptionIsOrder`.
- Draft save limits stay (64 KB body, 32 KB per section); a section's component may hold at most 50 list items, 30 prescription rows, 60 pins.

### D14 · Permissions summary

| Operation | Permission / roles |
| --- | --- |
| Record writes (lists, prescription, pins, form values, print preferences, copy items) | `clinic.consultation.write`, own patient (ADR-020 D10), DRAFT |
| History, key facts, case paper context, EMR medicine search, favourites, allergy classes | `clinic.consultation.write` |
| Forms and starters: read | any clinic role |
| Forms: create, save, submit, publish, retire, use starter | `clinic.emr_forms.edit` (OWNER) |
| Forms: approve, send back | `clinic.emr_forms.approve` (OWNER) |
| Courses: read | OWNER, DOCTOR, VISITING_DOCTOR, FRONT_DESK, BILLING |
| Courses: create, add or change sitting, link visit | `clinic.consultation.write`; existing course: its doctor or OWNER with doctor details |
| Course account: read | OWNER, DOCTOR, VISITING_DOCTOR, FRONT_DESK, BILLING |
| Course payment | as the counter's collect (OWNER, FRONT_DESK) |
| Course receipt | OWNER, FRONT_DESK, BILLING |
| IPD admission with a course sitting | as SAH-23 admission (OWNER, FRONT_DESK, NURSE) |

Tenant always from the signed-in user; `tenantId`/`clinicId` in a body are ignored (ADR-020 rule kept).

---

## Options considered

| Question | Chosen | Rejected |
| --- | --- | --- |
| EMR list storage | Record document, client item ids | Item rows (billions a year, joins on every read) |
| Write path for list, Rx, pins | Existing draft save with revision; copy actions separate | One endpoint per item action (more endpoints, 409 races between quick taps, same data) |
| Bill lines from opd | Port in `opd.api` implemented by billing, set sync by item id | `opd → billing.api` (cycle); event after commit (a paid-bill refusal must roll back the EMR save) |
| Course home | Plan in opd, money in billing | All in billing (billing would own clinical plans and EMR links); all in opd (money and receipts outside billing) |
| IPD stay in a course | Settled in IPD, credit carried | Course as the only account (rebuilds IPD discharge); two accounts both charging (double billing) |
| Case paper | Client composition from live values + server context; browser print | Server-rendered HTML or PDF (cannot be live without saving; PDF library not approved) |
| Form values | In the EMR record under the form key | A separate entry table; a copy in patient (clinical data across modules) |
| Form approver | OWNER via `clinic.emr_forms.approve` | A new "Medical director" role (no such role; OQ-45-13 says owner) |
| History search | Application filter over one patient's rows | SQL over JSON, trigram, search engine |

## Consequences

Positive: no module cycle; one write path for clinical values; bill lines and payments idempotent; IPD money not billed twice; forms reuse the department and record model with immutable references; no new library, module or service.
Negative / accepted: no SQL over clinical values (history search is in memory over one patient); the record document grows (limits above); `ipd → billing.api` is a new edge to guard; until SAH-51 nothing is signed, so Repeat last Rx, history details, copy actions, key facts from EMR values and sitting Done stay empty; several defaults below wait for the human.

---

## Human decides (defaults applied in the contract; each is beyond or different from the mockup or the built screens)

| # | Item | Default in this contract |
| --- | --- | --- |
| H1 | IPD and day-care sittings and the course account (mockup has no IPD bill) | Settled in IPD once linked (out of course estimate and charges); earmarked IPD_DEPOSIT payments, capped at credit, carried to the admission's deposits |
| H2 | Who collects course payments | Same as the counter today: OWNER, FRONT_DESK; BILLING role reads (story says billing collects) |
| H3 | Course payments in day closing and revenue | Included (cash in drawer) |
| H4 | Receipt numbers and receipt view | New "RC-<n>" counter for course payments only; new printable receipt read |
| H5 | Linking today's visit to a planned sitting (not drawn) | Doctor action "This is today's <unit>" on the course page |
| H6 | Defaults for a medicine with no saved values; typed medicines | 1-0-1 or the master schedule, 5 days, After food; typed medicines allowed, never allergy-checked |
| H7 | "Favourites filtered by department" (tech lead's brief) | Not built: not in SAH-48 or the mockup; favourites stay one list per clinic |
| H8 | Services on IPD round and Emergency records | No bill line; recorded and printed only |
| H9 | "Save to patient record" through the patient api | Stored in the EMR record (opd), read by history; nothing written to patient |
| H10 | Form "Visit types" | Stored and shown only |
| H11 | Advice language: visit choice vs item attribute | Item attribute wins on the paper; visit choice is the default for new items |
| H12 | History details before SAH-51 | Only signed records show details; IPD details limited to what ipd holds |
| H13 | Admission request for a doctor | Sitting flag now; the IPD request list and "Send admission request" come with SAH-67-3 |
| H14 | "Already billed" | Any payment on the visit bill blocks removal or qty change of an EMR line |
| H15 | Form names | Unique per clinic ignoring case |

## Data needed (for the db-engineer)

All new migration files (do not edit `V2026_12_08_*`). Every tenant table: `tenant_id uuid not null default platform.current_tenant()` FK `platform.tenant`, ENABLE + FORCE RLS, policy on `platform.current_tenant()`, tenant-first indexes, `created_at/by`, `updated_at/by`, `version`/`revision` where updated. No FK across modules except `platform.tenant` and `patient.patient`. Money in `bigint` paise (the codebase convention, `billing.payment`, `opd.service`). Clinical and billing rows never deleted.

**opd, global (SELECT only for `sahaj_app`, changed by migration):**
1. `opd.emr_allergy_class`: `class_key` pk (`SULFA`, `PENICILLIN`), `label` 1–60, `position`. Comment: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed.
2. `opd.medicine` additive columns: `allergy_class` null FK → 1; `default_frequency` null check in (`1-0-0`,`1-0-1`,`0-0-1`,`1-1-1`); `default_days smallint` null 1–365; `default_when` null check in (AFTER_FOOD, BEFORE_FOOD, BEDTIME, MORNING_EMPTY_STOMACH). Seed: insert "Co-trimoxazole 960 mg" (Tablet, SULFA, 1-0-1, 5, AFTER_FOOD); set PENICILLIN on the Amoxicillin rows; set defaults from mockup `FAVS` and `LAST_RX` where the name exists (Pantoprazole 40 mg 1-0-0/14/MORNING_EMPTY_STOMACH, Paracetamol 650 mg 1-0-1/3/AFTER_FOOD, Amlodipine 5 mg 1-0-0/30/AFTER_FOOD, Cetirizine 10 mg 0-0-1/5/BEDTIME, Telmisartan 40 mg 1-0-0/30/AFTER_FOOD, Metformin 500 mg SR 1-0-1/30/AFTER_FOOD, Atorvastatin 10 mg 0-0-1/30/BEDTIME). Updating seeded global rows by migration is not destructive.
3. `opd.emr_form_starter`: `starter_key` pk (diabetic_foot, gcs, morse, mlc_injury, pre_anaesthetic, untitled), `name` 1–80, `description` ≤ 200, `suited_for` text ≤ 200 (departments as drawn), `position`, `definition jsonb` (`schemaVersion` 1, D11 shape; ≤ 256 KB). Seed from the mockup #FormBuilder starters exactly; immutable trigger like `emr_starter_layout`.

**opd, tenant:**
4. `opd.emr_record` additive: `print_language text` null (EN, MR, HI), `paper_size text` null (A4, A5), `form_refs jsonb not null default '[]'` (array; ≤ 16 KB); column update grants for the three. No new index.
5. `opd.emr_form`: `id`, `form_key` (`^f_[a-z2-7]{10}$`), `name` 1–80, `starter_key` null, `status` ACTIVE/RETIRED, `live_version_no int ≥ 0`, `draft_definition jsonb` null (≤ 256 KB, `schemaVersion`), `draft_version_no int ≥ 1`, `draft_state` NONE/DRAFT/WAITING_APPROVAL/SENT_BACK, `sent_back_reason` null 1–300, `submitted_at/by` null, `revision bigint`. Unique (`tenant_id`, `form_key`), unique (`tenant_id`, `lower(name)`), unique (`tenant_id`, `id`). Select, insert, column-limited update; no delete. ≤ 100 per clinic.
6. `opd.emr_form_version`: `id`, `form_id` (composite FK), `version_no ≥ 1`, `definition jsonb` (≤ 256 KB), `approved_by` null, `approved_at` null, `published_at`, `published_by`. Unique (`tenant_id`, `form_id`, `version_no`). **Immutable** (select/insert grants and policies, trigger). Kept for ever.
7. `opd.treatment_course`: `id`, `course_no int` (unique per tenant; per-clinic counter row or `max+1` under a tenant advisory lock), `patient_id` (composite FK `patient.patient`), `kind` (5), `unit` (5), `name` 1–120, `note` null ≤ 300, `doctor_membership_id` (no FK), `started_on date`, `origin_record_id` null, `origin_record_created_at` null (records are partitioned: carry the partition key), `status` ACTIVE, `idempotency_key text` null unique per tenant, `revision`. Index (`tenant_id`, `patient_id`, `created_at desc`).
8. `opd.treatment_course_sitting`: `id`, `course_id` (composite FK), `sitting_no int ≥ 1` unique per course, `encounter_kind` OPD/DAY_CARE/IPD, `add_option_key` text, `planned_date date` null, `items text[]` (≤ 20, each 1–200), `charge_paise bigint` 0–10,000,000,000, `note` null ≤ 300, `opd_visit_id` null (composite FK `opd.visit (tenant_id, id)`), `ipd_admission_id` null (no FK), `admission_requested bool`, `done_at` null, `idempotency_key` null unique per tenant. Partial unique (`tenant_id`, `opd_visit_id`) and (`tenant_id`, `ipd_admission_id`) where not null. Index (`tenant_id`, `course_id`, `sitting_no`). Select, insert, column-limited update; no delete.

**billing, tenant:**
9. `billing.course_payment` (append-only: select/insert grants and policies, `platform.deny_audit_mutation` trigger like `billing.payment`): `id`, `course_id` (no FK, cross-module), `patient_id` (composite FK `patient.patient`), `receipt_no bigint` (unique per tenant), `type` VISIT_PAYMENT/FULL_BALANCE/PART_PAYMENT/ADVANCE/IPD_DEPOSIT, `mode` CASH/UPI/CARD, `amount_paise bigint > 0` and multiple of 100, `against_sitting_id` null, `against_label` ≤ 80, `use_credit bool`, `idempotency_key text not null` unique (`tenant_id`, `idempotency_key`), `request_hash text`, `recorded_by`, `recorded_at`. Indexes (`tenant_id`, `course_id`, `recorded_at`), (`tenant_id`, `recorded_at`) for day closing and revenue.
10. `billing.course_credit_transfer` (append-only, same protections): `id`, `course_id`, `sitting_id`, `admission_id`, `amount_paise > 0`, `transferred_by`, `transferred_at`; unique (`tenant_id`, `admission_id`); index (`tenant_id`, `course_id`).
11. `billing.receipt_counter`: `tenant_id` pk, `last_no bigint ≥ 0`; select, insert, update (row lock per payment).
12. `billing.bill_item` additive: `source_kind text` null (`EMR`), `source_key text` null 1–40; check both null or both set; partial unique (`tenant_id`, `bill_id`, `source_key`) where not null; add `update (qty)` grant if not present.

**ipd, tenant:**
13. `ipd` admission table additive: `treatment_course_id uuid` null, `treatment_course_sitting_id uuid` null (no FK; opd ids).
14. `ipd` deposit table additive: `source text not null default 'DESK'` check DESK/COURSE; `course_credit_transfer_id uuid` null; mode may be null only when source is COURSE (check).

Retention: all of the above for the life of the clinic; form versions, payments and transfers for ever; nothing hard-deleted. Expected rows: forms ≤ 100 and versions ≤ ~1,000 per clinic; courses tens to hundreds a year per clinic; sittings ≤ 50 per course; course payments ≤ a few per sitting; no partitioning needed.
