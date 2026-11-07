# ADR-013 · IPD Admitted patient cards: one role-gated batch read in GET /admissions, list-level card abilities, 60-second reload, no record-access row

Story: SAH-36 (IPD part 14: admitted patients as uniform cards, search, ward filter, flags, card actions)
Mockup screen: IPD → Admitted (`ipList` overrides, `pctools`, `pcq`, ward select `data-pcl="ward"`, `pcview`, `pcColsSeg`, `pcgrid cols-N uni`, `pcCard`, `pcf` flags, `sfFlags`, `pcacts`, `pcdo`, `pcmore`, `pcDefault`)
Module: ipd only (existing `platform.api` and `patient.api` ports; no new dependency, table, port, event, endpoint or library)
Status: PROPOSED (7 Nov 2026), with the SAH-36 contract. Accepted when the human approves `contract`. `docs/approvals.md` has no `stories` or `contract` line for SAH-36; work proceeds under DEC-014, DEC-015 and DEC-021 on the business analyst's proposed answers (OQ-36-1 to OQ-36-13).
Deciders: Ganesh (human), tech-lead; recorded by solution-architect
Related: `docs/requirements/SAH-36.md`, `docs/stories/SAH-36.md`, DEC-021, ADR-012 (D1 dose reads in `GET /admissions`, D3 `SafetyCounts`), ADR-007 (D5 `EscalationClock`, D8 banner and polling, D12 record access, R1 change token), ADR-009 (D2 allergy status, D7 pharmacist-list record access, D9 `PatientClinicalPort`), ADR-006 (vitals, `vitalsDue` rule), ADR-005 (D3 counts-only polls, D6 REPEATABLE READ answers), DEC-014, DEC-015, DEC-017, `docs/api/ipd.yaml`

## Context

1. `GET /admissions` (SAH-23) already loads every admitted patient of the clinic in one read-only REPEATABLE READ transaction (`AdmissionService.list`): rows, one batch patient lookup, one batch billing read, one batch sign-off read, the emergency counts and, for OWNER, DOCTOR and NURSE, `safety` (banner, counts, change token; from SAH-35 also the two dose reads of ADR-012 D1). It writes no record-access row.
2. The card needs facts the list does not carry: UHID, ward id (names can repeat across clinics but a filter needs an id), room, the open escalation (SAH-33), the latest score and "vitals due" (SAH-30; today only on `AdmissionDetail`), and the allergy (patient record, through `patient.api`).
3. The mockup gates card data by group: vitals and escalation (`vitals`) and allergy (`clinical`) are not for front desk or billing; `Deposit low` (`billing`) is not for doctor or nurse; `Dose overdue` is nurse and owner only (`sfFlags`). Actions are gated per role (`pcDefault().acl.act`).
4. Scale: up to 100 hospitals; a big one has 150 to 300 admitted patients (bed limit); each open Admitted tab reloads every 60 seconds.

## Decision

### D1 · `GET /admissions` carries the card; no new endpoint, one answer for both views

The list answer gains, per row, `uhid`, `wardId`, `roomName` and (role-gated, D3) `clinical`; and, once per list, `asOf`, `wards` (filter options) and `card` (abilities, D5). The rows become `AdmittedPatientRow` = `AdmissionRow` plus these fields, so `AdmissionDetail` (which extends `AdmissionRow`) does not change.

Options rejected: (a) a new `GET /admissions/cards`: two reads of the same rows, two answers that could disagree with the table and the banner, two endpoints to secure; (b) per-patient reads from each card (`GET /admissions/{id}`, `/nursing`): 150 to 300 calls per refresh and a record-access row each (AC42 forbids it); (c) `GET /layout` for the ward list: an extra request carrying bed rates to roles that do not need them.

### D2 · One batch read: a fixed number of set queries per answer, whatever the ward size

All in the existing read-only REPEATABLE READ transaction, against one `asOf` = `clock_timestamp()` read once through a repository call (ADR-005 D6, ADR-007 D5). No call per patient.

| # | Read | Who | Index (existing) |
|---|---|---|---|
| R1 | Admitted admissions with bed, **ward id, room name** (joins bed → ward, bed → room by primary key) and the clinic's room-level flag | all four roles | `ix_admission_tenant_status_admitted_at` |
| R2 | Patients: **one** `patient.api.PatientClinicalPort.findAll(tenantId, patientIds)` (ADR-009 D9: id, UHID, name, age, sex, allergy text; follows `merged_into_id`) | all four roles | patient primary key |
| R3 | Open escalations (OPEN, ACK) of the admitted admissions: the **same rows** `SafetyRows.counts` already reads for `safety` (one query feeds counts, token, banner and card flags) | OWNER, DOCTOR, NURSE | `ux_ews_escalation_tenant_admission_open` (partial) |
| R4 | Latest vitals per admitted admission: `admission_id, ews_score, ews_risk, recorded_at` (`recorded_at desc, id desc`, one per admission; lateral `limit 1` or `distinct on`, java-developer's choice) | OWNER, DOCTOR, NURSE | `ix_vitals_tenant_admission_recorded` |
| R5, R6 | Dose orders and today's dose records (ADR-012 D1 Q1, Q2) | OWNER, DOCTOR, NURSE (unchanged from SAH-35) | as ADR-012 |
| — | Existing billing, sign-off, bed counts, emergency counts | unchanged | unchanged |

- R2 may replace the existing `PatientLookupPort.findAll` of the list (one patient read, not two). The allergy text is **mapped into the answer only for OWNER, DOCTOR, NURSE** (D3); the mapping is one method with unit tests per role. **Amended 7 Nov 2026 (D15):** R2 through `PatientClinicalPort` runs **only for a caller with clinical access**; any other caller gets R2' = one batch `PatientLookupPort.findAll` with name, UHID, age and sex only, so the allergy text is never loaded for FRONT_DESK.
- Flags are **existing rules computed by existing code**: `EscalationClock` (ADR-007 D5) for `waitingMinutes` and `pastTarget`; the SAH-30 `vitalsDue` rule (no vitals, or more than 240 whole minutes since the latest `recorded_at` at `asOf`), the same function that fills `AdmissionDetail.vitalsDue` and `NursingSheet.vitalsDue`; `MedicineSafetyCheck`'s allergy-status function (ADR-009 D2) for `allergy.status`; `DischargeRules.goingHomeToday` and the SAH-25 deposit rule unchanged. No new clinical rule; the browser computes none of them.
- A patient record that cannot be found (data fault): the row is never dropped; `uhid` null (card "—"), `allergy.status` NOT_RECORDED (no flag), as the name already falls back today.
- Cost: R1 to R6 are index reads of at most a few thousand rows for a 300-bed hospital. Answer size about 1 KB per row (about 300 KB uncompressed, 30 to 40 KB gzipped at 300 rows). java-developer: enable HTTP response compression for JSON if it is not on (configuration only, no library); the decision, its BREACH assessment and its rules are in D14.

### D3 · Role-gated fields are absent, not null; roles are token and membership both

- The membership is re-read **once per answer** (`platform.api.TeamMemberPort.findActiveByUserId`, single-row since ADR-007 R8), before any read. **Tightening:** no active membership, or a membership role outside OWNER, FRONT_DESK, NURSE, DOCTOR, answers 403 `forbidden` for the whole list (today only `safety` used the re-read). This is what makes a role change apply on the next reload (AC41).
- `clinical` (object) is **present only when the token role and the membership role are both OWNER, DOCTOR or NURSE**; for every other caller the property is **absent** from the JSON (not `null`, not an empty object). The serializer must not write it (for example `@JsonInclude(NON_NULL)` on that one field, or two record types); a test asserts the raw JSON of a FRONT_DESK answer contains none of `clinical`, `escalation`, `latestEws`, `vitalsDue`, `allergy`.
- Inside `clinical`, `null` means "none exists" (`escalation` null = no open escalation; `latestEws` null = no vitals), never "not allowed".
- `allergy.text` is present only when `allergy.status` is RECORDED (the only state that shows text). "None known" and "Not recorded" send no text (data minimisation, OQ-36-6).
- The existing `AdmissionList.safety` keeps its SAH-33 shape (`null` for other roles); it is not changed to "absent" because the SAH-33/35 client already relies on it.
- Billing fields (`runningBillPaise`, `depositPaise`, `depositLow`, ...) stay on every row for all four roles because the **Table view (unchanged) shows them** to NURSE and DOCTOR today. The card's "Deposit low" flag follows `card.depositLowFlag` (D5), so it is a display choice, not a privacy control (OQ-36-4; removing the bill from nurses and doctors would change the table, out of scope).

### D4 · Escalation, score, vitals-due and allergy flags (texts in the contract)

| Flag | Source field | Shown when | Order |
|---|---|---|---|
| Not seen · N min (crit, siren) | `clinical.escalation`, status OPEN; N = `waitingMinutes` | OPEN | 1 |
| Doctor reviewing (warn, siren) | status ACK | ACK | 1 |
| (later) Dose overdue | D9 | — | 2 (mockup `sfFlags` order) |
| Allergy: <text> (crit, alert) | `clinical.allergy` | status RECORDED | 3 |
| EWS <score> · Medium/High (warn/crit, heart) | `clinical.latestEws` | risk MEDIUM or HIGH | 4 |
| Vitals due (warn, clock) | `clinical.vitalsDue` | true | 5 |
| Going home today (blue, door) | `goingHomeToday` | true, all four roles | 6 |
| Deposit low (crit, deposit) | `depositLow` and `card.depositLowFlag` | both true | 7 |

- N in "Not seen · N min" is `waitingMinutes` (from the response-clock start: the re-open, else the raise), the same number as the Safety board's red notice, so the card, the banner and the board agree for a re-opened escalation. The tooltip uses `targetMinutes` and `pastTarget`.
- Red outline (`pc-crit`): `clinical.latestEws.risk` = HIGH.
- Safety flags (Not seen, Doctor reviewing, Allergy, EWS) never fold into "+N" (OQ-36-1 proposal); the client may fold Vitals due, Going home today, Deposit low.
- Minutes come from the server's `asOf`; the client never counts up between reloads (OQ-36-10).

### D5 · Action abilities once per list (`AdmissionList.card`), not per row

Every listed admission is ADMITTED and phase 1 has no ward scoping, so an ability depends only on the caller's role and the clinic's IPD state. One object per answer:

| Ability | True when (token role and membership role both in the set, and state) | Opens |
|---|---|---|
| `nursing` | OWNER, NURSE, DOCTOR; Trial, On, Read-only | Nursing sheet (`GET /admissions/{id}/nursing`) |
| `emr` | OWNER, NURSE, DOCTOR; Trial, On, Read-only | EMR sheet (`GET /admissions/{id}/emr`) |
| `transfer` | OWNER, NURSE, FRONT_DESK; Trial, On | admission sheet, Move row open and focused |
| `discharge` | OWNER, DOCTOR; Trial, On | admission sheet, discharge view |
| `depositLowFlag` | OWNER, FRONT_DESK | (flag, not an action) |

- Each set is the intersection of the mockup's default card (`pcDefault().acl.act`) and what the server already allows (`canOpenNursing`, `canOpenEmr`, `canTransfer`, `canStartDischarge`): the narrower side (OQ-36-4). The abilities are a convenience: every endpoint the sheets call checks role, membership, state and admission status again, unchanged.
- Rejected: per-row flags (`canTransfer` on 300 rows that are all equal) adds bytes and suggests a row difference that does not exist. If ward scoping (OQ-33-2) later makes abilities depend on the patient, rows gain an optional override then; the list-level object stays the default.

### D6 · How the card opens the sheets (no new endpoint, no URL state)

- **Nursing, EMR:** the existing sheets, given the admission id only; they read and write record access exactly as today (SAH-30, SAH-31).
- **Name, Transfer, Discharge:** the existing admission sheet (SAH-23), opened with an in-memory start view: `details` (name, "Open bill and bed details"), `move` (Move row of SAH-26 open and focused), `discharge` (the SAH-27 discharge view). The client makes the same requests as the manual path today: `GET /admissions/{id}` (one record-access row, as today), then for `discharge` the discharge read the "Start discharge" button makes. If the detail answer says the action is no longer possible (`canTransfer` or `canStartDischarge` false, DISCHARGED), the sheet opens on its main view with its existing texts. The start view is a component input, never a route or query parameter (no patient or admission id in the URL from the card).
- After a sheet closes with a change, or after a 409 `not-admitted` / `ipd-readonly`, the list reloads (D7).

### D7 · Refresh: 60 seconds while visible, replaces the summary poll on this tab

- While the Admitted tab is shown and the browser tab is visible (`document.visibilityState`), the client reloads `GET /admissions` every 60 seconds and after any change; it pauses while hidden and reloads once on return. Search text, ward, view, cards per row and the number of cards shown are kept.
- **While the Admitted tab is shown, this reload replaces the SAH-33/35 summary poll** (`GET /safety/summary`): each list answer carries `safety.counts` (tab number and change token), so a second request per minute would add nothing. On the Safety tab the board's own refresh is unchanged; on other tabs the counts-only summary poll is unchanged (ADR-005 D3, ADR-007 D8). This full reload with names is the same pattern as the Safety board's own 60-second reload of the visible tab.
- A failed reload keeps the last answer with "Could not refresh. Showing data from <AdmissionList.asOf as 11:05 AM>." (SAH-33 text). `asOf` is new on the list for this (FRONT_DESK has no `safety.counts.asOf`).

### D8 · Search, ward filter and paging stay on the device

- `GET /admissions` gets no `q`, `ward`, `limit` or `cursor` parameter. The typed text never leaves the browser (not sent, logged, put in the URL or browser storage). Ward filter by `wardId` against `wards` (every ward of the clinic, in the order of `IpdLayout.wards`, also wards with no patient).
- No server paging: the list is bounded by the clinic's bed limit and is already fully loaded today. "Show 24 more" / "Show all" only draw more cards.
- A server-side search was not chosen: it would send patient search text to the server and into access logs (story note: ask the human first).

### D9 · Dose overdue later, from the same rule (no second rule)

ADR-012 D1 already runs Q1 and Q2 in `GET /admissions` for OWNER, DOCTOR and NURSE and classifies every slot with `DoseSlots` inside `MedicineRound`. The later flag adds nothing new to compute:
- `MedicineRound` also returns the LATE count per admission (a group-by over the slots it already classified, same `asOf`).
- `AdmittedClinicalFlags` gains an optional integer `dosesLate`, **present only when the token role and the membership role are both NURSE or OWNER** (mockup `sfFlags`: `role==='nurse'||role==='owner'`), absent for DOCTOR (D3 rule).
- Flag "Dose overdue" (warn, pill icon) when `dosesLate > 0`, second in the order of D4 (after the escalation flag, before Allergy, as `sfFlags` pushes it).
- Invariant for tests: in one answer, the sum of `clinical.dosesLate` over the rows equals `safety.counts.dosesLate`.
- Additive: no change to any field of this ADR. Not in the contract now (DEC-021: out of scope; no placeholder).

### D10 · Record access: no row for the list (OQ-36-7, needs the human)

- `GET /admissions` writes **no** `patient.record_access` row, on first load or on reload (as SAH-23, the Safety board ADR-007 D12 and ADR-012 D5). Opening Nursing, EMR or the admission sheet from a card writes the rows those reads write today.
- **Tension to resolve (human):** the list now shows, for every admitted patient, UHID, age, sex, recorded allergy text, score and escalation state. ADR-009 D7 (DEC-017 review decision) writes one row per distinct patient for the pharmacist list because it shows UHID, age, sex and allergy text. By that precedent the card list would need rows too. The proposal keeps "no row" because the list reloads every 60 seconds on a ward screen (a row per patient per minute per screen would bury real openings), the data is the ward's working set, and the full record (vitals values, notes, orders) needs a sheet that is logged. Alternatives for the human: (a) one row per distinct patient on the **first** load of the tab only (needs a client marker the server cannot verify; weak); (b) one row per patient per user per IST day (needs a dedupe read or unique index: a db change); (c) drop the allergy **text** from the list (flag "Allergy recorded", text in EMR): fewer data, but a mockup deviation on a safety flag. None is built without the human.

### D11 · Audit, logging, events

Reads write no audit row and no clinical event. Application logs: ids, `tenantId`, `requestId` only; never names, UHID, allergy text, scores, escalation data or search text. Problem details never carry patient data.

### D12 · Module boundaries and phase 1

ipd only. Uses `platform.api` (`TeamMemberPort`, entitlement), `patient.api.PatientClinicalPort` (existing) and `patient.api.PatientLookupPort` (existing; one additive field and one method, D15). No cross-module table access (UHID and allergy never come from a join to `patient.patient`). No new port, event, dependency or library; `ModularityTests` stay green. Phase 1 held: no notifications (no text says anyone was alerted), no ward scoping, no preference store (view choices in memory, OQ-36-5), no print (Case paper), no analytics.

### D13 · Retention

No new data. Nothing computed here is stored.

### D14 · HTTP compression (added 7 Nov 2026; code review R36-1, security review S36-3)

**Decision.** The app gzips `application/json` responses of 2 KB or more through Spring Boot `server.compression` (`enabled`, `mime-types: application/json`, `min-response-size: 2KB`). Environment switch **`SAHAJ_HTTP_COMPRESSION`**, default `true`. Configuration only, no library. Reason: `GET /admissions` is about 300 KB at 300 patients and is reloaded every 60 seconds per open Admitted tab (D2, D7); gzip brings it to about 30 to 40 KB. The setting is global, so every JSON answer of 2 KB or more is compressed, not only this list.

**BREACH assessment (compression plus a secret plus attacker-chosen text in one response).** No practical exposure today:
- The access token is a bearer token sent in the `Authorization` header and held in memory by the client; it is never in a cookie the browser adds by itself.
- The only cookie is the refresh cookie: `HttpOnly`, `Secure`, `SameSite=Strict`. CSRF protection is off, so there is no CSRF token in any body.
- No endpoint echoes attacker-chosen request text in the same response as a secret. The `/auth/refresh` answer holds an access token but echoes nothing from the request.
- `application/problem+json` is not in `mime-types`, so error answers (which may repeat a field name) are never compressed.

**Residual, accepted.** A FRONT_DESK insider controls patient names (registration), and those names sit in the same compressed list as other patients' data. Exploiting it needs TLS length observation on the ward network and many edits and polls; impractical. The list carries no credential.

**Rules.**
1. Never add an endpoint that echoes request text next to a token or other secret in a JSON body.
2. When a reverse proxy exists, compress there, limited to `/api/v1/ipd/admissions`, and set `SAHAJ_HTTP_COMPRESSION=false` in the app (no double compression).
3. Never compress auth, refresh or admin responses at the proxy. If the app setting must ever be narrowed before a proxy exists, those paths are the first to exclude.
4. `SAHAJ_HTTP_COMPRESSION` (default `true`) and `SAHAJ_DB_POOL_SIZE` (default `10`, Hikari pool) must be listed in `.env.example`. That file is human-owned: **tech lead, ask the human to add both.**

Options rejected: no compression (300 KB per tab per minute on ward Wi-Fi); compress only at a proxy (no proxy in phase 1); per-path compression in the app (Spring Boot's setting has no path filter; a servlet filter is code for a phase 1 need the proxy will take over).

### D15 · Data minimisation of the patient read (added 7 Nov 2026; security review S36-2, code review R36-5)

**Decision.** A caller without clinical access (D3: token role and membership role not both OWNER, DOCTOR or NURSE; in practice FRONT_DESK) must not cause the allergy text to be loaded at all, not only not sent:
- **Clinical access:** R2 as before, one `patient.api.PatientClinicalPort.findAll` (id, UHID, name, age, sex, allergy text).
- **No clinical access:** R2' = one batch read through `patient.api.PatientLookupPort` returning **only name, UHID, age and sex** (plus what `PatientSummary` already carries; the mobile number is never mapped into the list). `PatientClinicalPort` is not called.
- The branch is decided once per answer from the membership re-read of D3, before any patient read. A unit test asserts `PatientClinicalPort` is never called for FRONT_DESK.

**Intended change in `patient.api` (owner java-developer, patient module):**
1. `PatientLookupPort.PatientSummary` gains **`String uhid`** (additive; the existing constructors stay and fill it as before from the record; `toString` keeps ids and the merged flag only, never the UHID).
2. The card must show the **same identity to every role**. `PatientClinicalPort.findAll` follows `merged_into_id` (ADR-009 S4); `PatientLookupPort.findAll` does not (it returns a merged-away record with `merged = true`). So `PatientLookupPort` gains **`Map<UUID, PatientSummary> findAllCurrent(UUID tenantId, Collection<UUID> patientIds)`**: keyed by the requested id, a merged-away record answered with the record it was merged into (id, UHID, name, age, sex), the same resolution as `PatientClinicalPort.findAll`; unknown and other-clinic ids absent. The existing `find` and `findAll` keep their behaviour for their current callers.
3. No new port, no table access from ipd, no schema change (UHID is an existing column read by the patient module). `ModularityTests` stay green.

Options rejected: keep loading through `PatientClinicalPort` and drop the text in mapping (works today, but one mapping change could leak it; the reviews ask for the fix); a `PatientClinicalPort` method that returns `allergyText` null (null already means "nothing recorded", so a role filter would look like a clinical fact).

## Options considered (summary)

- **Extend `GET /admissions` (chosen)** vs a separate card endpoint vs per-patient calls.
- **Absent role-gated object (chosen)** vs `null` fields (the SAH-30 `AdmissionDetail.vitalsDue` pattern): absent cannot be mistaken for "no allergy" or "no escalation".
- **Abilities per list (chosen)** vs per row.
- **List reload replaces the summary poll on the Admitted tab (chosen)** vs both running.
- **No record-access row (proposed, human)** vs rows per patient (D10).

## Consequences

**Positive.** A deteriorating, allergic or overdue-for-vitals patient is visible on the ward list within 60 seconds without opening a chart; the card, the banner, the Safety tab number and the board come from the same rows and snapshot; front desk receives no clinical data at all; no new table, endpoint, rule or dependency.

**Negative / accepted.**
- `GET /admissions` for clinical roles runs one more set read (R4) and returns about 30 percent more bytes; it is reloaded every 60 seconds per visible tab (bounded; the summary poll stops on that tab).
- The list re-reads the membership and can now answer 403 to a deactivated member with a valid token (intended).
- Clinical data of every admitted patient on one screen without an access row (D10, human).
- Every nurse sees every admitted patient until wards exist (OQ-33-2).

## Data needed (for db-engineer)

No new table, column, constraint, grant, trigger or policy. **No migration is expected.** Verification, and one conditional index:

1. `explain (analyze, buffers)` as `sahaj_app` with `app.tenant_id` set, in `sahaj_test` with realistic data (one clinic with 300 ADMITTED and 20,000 DISCHARGED admissions, 26 wards, rooms on; 50 vitals rows per admitted patient and about 2 million vitals rows in the clinic; other clinics' rows):
   - R1: admitted admissions with bed, ward, room (PK joins) through `ix_admission_tenant_status_admitted_at`.
   - R3: OPEN and ACK escalations of the admitted admissions through `ux_ews_escalation_tenant_admission_open`.
   - R4: latest vitals per admitted admission through `ix_vitals_tenant_admission_recorded (tenant_id, admission_id, recorded_at desc)`: one index probe per admission, no sequential scan of `ipd.vitals`, no sort of all vitals of an admission.
   - Target: the whole list read under 100 ms at 300 admitted patients. Record the plans in `docs/database/SAH-36.md`.
2. **Conditional (only if R4 shows a sort or heap reads that matter):** a forward-only migration replacing or adding `ix_vitals_tenant_admission_recorded_id (tenant_id, admission_id, recorded_at desc, id desc) include (ews_score, ews_risk)` for an index-only "latest per admission". Not needed for escalations or doses (ADR-012 covers Q1/Q2).
3. Patient side (owner `patient`, through the port): `PatientClinicalPort.findAll` reads by primary key; nothing for ipd to index.
4. Tenant scope unchanged (FORCE RLS on all tables read). Retention unchanged.

## Instructions

**java-developer:** extend `AdmissionService.list` per D2 (R1 ward id, room name, room level; R2 one `PatientClinicalPort.findAll`; R4 latest vitals in one query; R3 reuse the escalation rows of `SafetyRows`); membership re-read first with the 403 of D3; `asOf` once; `clinical` absent for other roles (serializer test on raw JSON); `allergy.text` only for RECORDED; `card` abilities per D5; `wards` in layout order; `roomName` null when the ward has no room or room level is off. Reuse `EscalationClock`, the SAH-30 vitals-due function and the ADR-009 allergy-status function (no copies). Unit tests: each role's field presence, each flag source, abilities by role and state, missing patient record. Never log rows or names. Fix pass (7 Nov 2026): D15 (no `PatientClinicalPort` call without clinical access; `PatientSummary.uhid` and `PatientLookupPort.findAllCurrent` in patient.api, with a unit test per role and a merged-record test); keep `server.compression` as D14.

**angular-developer:** toolbar, Cards/Table, cards per row, card, flags (order and folding of D4), abilities of D5, sheet opening of D6 (start view as an input, not a route), 60-second reload and summary-poll replacement of D7, device-only search and filter of D8; texts as the contract.

**qa-engineer:** FRONT_DESK raw JSON without `clinical`; NURSE/DOCTOR/OWNER with it; membership changed NURSE → FRONT_DESK between two reads; deactivated member 403; other clinic invisible; 300-row list in one request (no per-patient calls in the network log); escalation OPEN → ACK → DONE reflected; re-opened escalation minutes equal the board's; vitals due at 240/241 minutes; allergy texts NONE_KNOWN / NOT_RECORDED / RECORDED; Read-only abilities; no `record_access` rows from `GET /admissions`; no search text in requests, URL or storage; FRONT_DESK card UHID equals the clinical roles' UHID for a patient merged after admission (D15); a list answer above 2 KB has `Content-Encoding: gzip` when the client sends `Accept-Encoding: gzip`, and a problem answer never has it (D14).

## Questions for the human

- **Q1 (OQ-36-7, privacy, DPDP):** no record-access row for a list that now shows UHID, age, sex, allergy text, score and escalation for every admitted patient, while the pharmacist list (DEC-017) writes rows for similar data (D10, options a to c).
- **Q2 (OQ-36-1, patient safety):** safety flags never folded into "+N"; the flags line may wrap to two lines (mockup folds all after the first).
- **Q3 (OQ-36-2):** no workflow stage (chip, filter, Update stage).
- **Q4 (OQ-36-4):** narrower roles: Transfer not for DOCTOR; Discharge only OWNER and DOCTOR on the card; Deposit low hidden from NURSE and DOCTOR on the card while the table still shows their bill (a display choice, not a privacy control).
- **Q5 (OQ-36-6):** no flag for "Allergy not recorded".
- **Q6 (mockup conflict):** the last `ipList` override of the mockup removes the red "Open safety board" banner from the Admitted tab (`t.querySelector(':scope>.sfban').remove()`); SAH-33 built it and SAH-36 keeps it (safer). Confirm.
- **Q7 (new decision):** "Room" shows "—" when the clinic has switched rooms off, even if the bed is still linked to a room (the mockup would show the stored room).
- **Q8 (text):** "Length of stay" shows "1 day" when the 24-hour cut gives day 1 on the calendar day after admission (the mockup's rule never produces this case); "Day 1 (today)" is used only on the IST date of admission.
- **Q9 (behaviour change):** `GET /admissions` now answers 403 to a user whose clinic membership is inactive or whose membership role is outside the four list roles, even with a valid token.
- **Q10 (approvals):** no `stories` or `contract` line for SAH-36 (nor SAH-35) in `docs/approvals.md`.
