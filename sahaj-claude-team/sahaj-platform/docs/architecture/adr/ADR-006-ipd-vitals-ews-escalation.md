# ADR-006 · IPD nursing vitals, early warning score and escalation: append-only clinical tables, one admission lock, score in code

Story: SAH-30 (IPD part 9: nursing vitals, nursing notes and early warning score)
Mockup screen: Nursing sheet (card → Nursing), tab "Vitals and notes" (`pcNursing`, `pcvsave`), early warning score (`pcEws`, `pcAgo`, `pcDue`), escalation raise and log of the Safety layer (`sfRaise`, `sfLog`, wrapped `ACT.pcvsave`)
Module: ipd only (uses the existing `platform.api` and `patient.api` ports; no new dependency)
Status: PROPOSED (5 Nov 2026), with the SAH-30 contract. It is accepted when the human approves `contract`. Amended 5 Nov 2026 by the review fix pass (`docs/reviews/SAH-30-code.md` M1, M2, M3, M4, M7, N2, N3, N6, N7; `docs/reviews/SAH-30-security.md` M1, M2, M3, L1, L2, L5, L8; DEC-013 item 13): D1 Time and checks, D2 runbook, D4 role re-read and lock statements, D5 replay comparison and client confirmations, D6 replay access row, D7a toast, D7b note text.
Deciders: Ganesh (human), tech-lead; recorded by solution-architect
Related: `docs/stories/SAH-30.md`, `docs/decisions/DEC-014-ipd-open-questions-proceed-with-proposals.md` (OQ-30-1 to OQ-30-8), `docs/api/ipd.yaml` (`/admissions/{id}/nursing`, `/admissions/{id}/vitals`, `NursingSheet`, `VitalsInput`, `VitalsSaved`, `AdmissionDetail`), ADR-002 (tenancy and audit), ADR-004 (review decision a: sheets that show clinical data write a record-access row), ADR-005 (clientKey pattern, partial unique index as last defence)

## Context

1. SAH-30 is the first IPD story that stores clinical observations. Nurses save a set of vitals every 30 minutes to 4 hours per patient. It is the fastest-growing IPD table: about 100 hospitals × 150 occupied beds × 6 to 12 sets a day is 90,000 to 180,000 rows a day, so 33 to 66 million rows a year. The story's rule is to plan partitioning before 50 million rows, which is within the first year of full use.
2. A Medium or High score must raise or update one escalation per admission in the same transaction (AC8). The mockup keeps at most one open escalation per patient (`sfEscOf`: the first one not done) and raises the score of the open one only when the new score is higher (`sfRaise`).
3. Every save writes a line to a new clinical event log (`sfLog`) that SAH-31, SAH-32 and the Safety board will also write to.
4. The IPD writes so far serialise with the IPD tenant advisory lock, then bed rows, then the admission row (SAH-23 to SAH-28; ADR-005). The discharge, the transfer and the sign-off all lock the admission row.
5. The score bands come from the mockup and have no clinical sign-off yet (OQ-30-6, blocking for go-live).
6. Phase 1 has no notifications service, so a raised escalation alerts nobody (OQ-30-2). No screen lets a doctor act on it until the Safety board story.

## Decision

### D1 · Four new append-mostly tables in `ipd`

All four tables have `tenant_id` (default `platform.current_tenant()`), FORCE row-level security, a tenant-composite FK `(tenant_id, admission_id) → ipd.admission (tenant_id, id)`, column-level insert grants, and no delete grant. A trigger refuses delete and truncate for every role, as on `ipd.deposit` (`platform.deny_audit_mutation()`).

| Table | Writes | Notes |
|---|---|---|
| `ipd.vitals` | insert only; update also refused by trigger | five values, score, risk, `recorded_at`, `recorded_by`, `client_key`, `note_added` |
| `ipd.nursing_note` | insert only; update also refused by trigger | note text up to 1,000 code points |
| `ipd.ews_escalation` | insert; update of score, risk, target and version only (this story) | at most one row per admission whose status is not DONE (partial unique index). Never deleted. |
| `ipd.clinical_event` | insert only; update also refused by trigger | `admission_id` may be null (events with no patient, for later stories). Fixed-template text only. |

- **Actor columns** (`recorded_by`, `raised_by`, `actor`) hold the same actor id as every other IPD table: the signed-in user's id, as in `ipd.deposit.collected_by`, `ipd.emergency_triage_event.recorded_by` and `ipd.discharge_signoff.signed_by`. The name is resolved when the data is read, with "Team member" as the fallback. The story says "membership id" for `clinical_event.actor`. This ADR keeps the IPD convention instead, so that one lookup serves every table (question Q11).
- **Time.** `recorded_at`, `raised_at` and `at` default to `now()`, the transaction time. Every row of one save therefore has the same instant, which AC5 asks for ("same user and time"). **`asOf` and `vitalsDue` (review fix pass, code review M1)** use the database clock at the moment the answer is built: `clock_timestamp()`, read through a repository call (as `EmergencyCaseRepository.databaseNow()`), on GET, on the 201 after the writes and on the 200 replay; also for `AdmissionDetail.vitalsDue`. The application clock (`IpdGate.now()`) is never used for them, so app and database skew cannot show a wrong "N min ago" on a clinical sheet. `clock_timestamp()` is never before the transaction's `now()`, so after a save `asOf >= recorded_at` and the subtitle reads "last vitals 0 min ago" unless the save waited a minute or more on the lock.
- **Ordering.** Vitals and notes are read by `recorded_at desc, id desc`. Events are read by `at desc, seq desc`, where `seq` is a `bigint generated always as identity`: the two events of one save share `at`, and `seq` keeps the escalation line above the "Recorded vitals" line, as `sfLog` (unshift) shows.
- **DB checks** as a safety net for the code: the ranges of the contract (systolic 40 to 300, diastolic 20 to 200, pulse and RR 1 to 300, SpO₂ 1 to 100, temp 25.0 to 45.0, score 0 to 15). The risk must agree with the total where the total alone decides it: `(ews_score >= 7) = (ews_risk = 'HIGH')`, and `ews_score < 5 or ews_risk <> 'LOW'`. On escalations: risk MEDIUM or HIGH, and `(ews_risk = 'HIGH') = (target_minutes = 15)` with target 15 or 60. **Added in the review fix pass** (code review N2, N3; security review L5; db-engineer, `V2026_11_25_1000__ipd_vitals_fixes.sql`): `check ((ews_score >= 7) = (ews_risk = 'HIGH'))`, `check (ews_score >= 3)` (MEDIUM from one parameter scoring 3 has a total of at least 3), `version bigint` (was integer; the postgresql.md rule), and the update guard trigger refuses `new.version < old.version` next to its existing refusals (update of a DONE row, a fall of score or of HIGH risk).

### D2 · Partitioning plan for `ipd.vitals` (and `ipd.clinical_event`)

The table starts unpartitioned, as the story says, but nothing in it may block range partitioning by `recorded_at` later:

- **No foreign key points at `ipd.vitals`.** The escalation stores its own score and does not reference the vitals row. The note does not reference it either: `ipd.vitals.note_added` records that a note came with the set.
- **The only unique index besides the primary key is `(tenant_id, client_key)`.** It is kept now as a defence. When the table is partitioned it cannot stay global, because a unique index on a partitioned table must include the partition key. It then becomes a local index on each partition. The real guard is the admission row lock (D4): a repeat key is looked up and the row inserted under that lock, so two saves for one admission can never both pass the lookup.
- **Procedure, when the table nears 30 million rows** (well before 50 million), in a maintenance window, as `sahaj_migrator` (corrected in the review fix pass, code review M7; the earlier text called the attach "metadata-only", which is wrong once the parent's primary key is `(id, recorded_at)`):
  1. Create `ipd.vitals_p` partitioned by range on `recorded_at`, primary key `(id, recorded_at)`. Partitions **quarterly, monthly at the top of growth** (from about 150,000 rows a day, roughly 13 million rows a quarter).
  2. On the **old table first**: `create unique index concurrently ... on ipd.vitals (id, recorded_at)` (outside a transaction, so not in a normal Flyway migration; a runbook step or a migration with `executeInTransaction=false` and a sign-off), and `alter table ipd.vitals add constraint ... check (recorded_at < <cut-off>) not valid`, then `validate constraint` (takes only a SHARE UPDATE EXCLUSIVE lock).
  3. Attach the old table as the first partition. With the matching unique index and the validated check present, the attach reuses the index and skips the scan; without step 2 it builds the index and scans the table while blocking writes. Then rename.
  4. The global unique `(tenant_id, client_key)` cannot stay on a partitioned table (it lacks the partition key): it becomes a local unique index per partition. The real guard stays the admission row lock (D4).
  5. Create the read index `(tenant_id, admission_id, recorded_at desc)` on the parent; re-create RLS (ENABLE and FORCE), the policy, column grants and the append-only triggers on the parent; create future partitions ahead of time.
  A db-engineer runbook item is opened at go-live. The table comments in `V2026_11_25_1000` carry this procedure.
- **Departure from the postgresql.md rule** ("plan monthly partitioning before 50 million rows"): the plan is quarterly while volume is in the lower part of the range (90,000 rows a day is about 8 million a quarter, a good partition size), monthly once growth reaches the top of the range. The 50-million trigger is kept (the procedure starts near 30 million). The rule is human-owned; this ADR records the departure for the human to accept with the contract.
- `ipd.clinical_event` follows the same procedure on `at`, with `create unique index concurrently ... (id, at)` on the old table before the attach; quarterly, monthly at the top of growth; `seq` (identity) stays on the parent (SAH-32 will add MAR events). No FK points at it.

### D3 · `EarlyWarningScore`: a pure domain class with the bands in code

`com.sahaj.ipd.domain.EarlyWarningScore` (no Spring, no I/O) takes systolic, pulse, SpO₂, temperature in tenths of a degree (an `int`, so 35.0 and 35.1 compare exactly, with no binary floating point) and RR. It returns the total, the five parameter scores and the risk. It also owns `targetMinutes(risk)`: HIGH 15, MEDIUM 60, LOW none. The mockup's `pcEws` bands, exactly:

| Parameter | 3 | 2 | 1 | 0 |
|---|---|---|---|---|
| SpO₂ % | ≤ 91 | 92 to 93 | 94 to 95 | ≥ 96 |
| RR /min | ≤ 8 or ≥ 25 | 21 to 24 | 9 to 11 | 12 to 20 |
| Pulse /min | ≤ 40 or ≥ 131 | 111 to 130 | 41 to 50 or 91 to 110 | 51 to 90 |
| Temp °C | ≤ 35.0 | ≥ 39.1 | 35.1 to 36.0 or 38.1 to 39.0 | 36.1 to 38.0 |
| Systolic mmHg | ≤ 90 or ≥ 220 | 91 to 100 | 101 to 110 | 111 to 219 |

Risk: HIGH when the total is 7 or more; MEDIUM when the total is 5 or more, or any one parameter scores 3; otherwise LOW. Diastolic is not scored.

Options: **A · constants in code (chosen).** The story requires it. The rules can be unit-tested at every edge, and a clinical change is a reviewed code change with a test, not a data edit. **B · bands in a table.** Rejected for phase 1. Per-clinic editable clinical thresholds need a sign-off workflow that does not exist. The class carries a Javadoc warning that the bands are "from the mockup, not clinically validated for Sahaj (OQ-30-6, blocking for go-live)".

The monitoring advice ("Routine vitals every 4 hours." and the others) is display text and lives in the client, keyed by risk. `vitalsDue` is a small domain rule next to the score: no vitals, or whole minutes since `recorded_at` (rounded down) greater than 240. This follows the mockup's `pcDue`: `at > 240`.

### D4 · Locks: the save takes exactly one row lock, the admission row

Order inside `VitalsService.record`. The transaction runs in READ COMMITTED, which is required here. It is the PostgreSQL and Spring default, but the service declares it explicitly (`@Transactional(isolation = Isolation.READ_COMMITTED)` with a comment pointing here; code review M3), so a later change of the default cannot break D4 silently.

1. Before any lock: token role check (403), IPD state (403 module-off, 409 ipd-readonly), clientKey and body validation (400). Security review SAH-22-23 L3 asked for validation before locks.
2. Inside the transaction, still before the lock: **the role is read again from the caller's active membership** (`platform.api.TeamMemberPort.findActiveByUserId`, the pattern of `OwnerGuard`; security review SAH-30 M1). No active membership, or a role other than OWNER or NURSE, gives 403 forbidden and nothing is written. The GET does the same with OWNER, NURSE, DOCTOR, and computes `canRecord` from both roles (token and membership must both be OWNER or NURSE). A role change therefore applies at once to clinical writes and reads, not when the 15-minute access token expires. The platform-wide fix (cache the role in the 60-second membership check, 401 on a mismatch) is HARD-2 and the human's decision; this per-operation re-read stays until then. It uses an existing port, so no new dependency.
3. **The lock, in two statements (code review M2).** First the bare row: `select id, status from ipd.admission where tenant_id = :t and id = :id for no key update`. A row hidden by RLS or missing gives 404. Only then, in a second statement, the joined read (admission, bed, ward) for the bed code and the rest. Reason: in READ COMMITTED, a statement that waited on a row lock re-checks only the locked row against its conditions (EvalPlanQual), with the other joined rows as of its own snapshot. A save that waited behind a transfer would see the new admission row (new bed id) but the old bed row, the join would stop matching, and the nurse would get a false 404. Locking the bare row first and reading the join after it, with a fresh snapshot, removes that. **The same rule applies to the discharge and transfer locks** (`FIND_FOR_UPDATE`, `FIND_FOR_NO_KEY_UPDATE` in `JdbcAdmissionRepository`): lock the bare `ipd.admission` row, then read the joined row; QA adds a transfer-vs-save IT.
4. The admission's patient is read through `patient.api.PatientLookupPort`; a missing patient gives 404 not-found (never a placeholder name; code review N6).
5. Replay lookup by `(tenant_id, client_key)` (D5), then the status check (409 not-admitted), then score, inserts, the escalation read and its insert or update, the events and the audit.

**No IPD tenant advisory lock and no bed lock.** The save does not touch beds, numbering or clinic settings, which are what the advisory lock serialises. Taking it would make every vitals save in a hospital wait on admits, transfers and every other nurse's save.

**Why no deadlock.** The save holds one row lock and waits for nothing else while holding it. Its inserts only take key-share locks on the admission row it already holds, and the partial unique index can only collide with another save of the same admission, which is already queued behind the row lock. The discharge, the transfer and the sign-off take advisory → beds → admission, or advisory → admission → bed. A save either finishes before them or waits for them, and finds DISCHARGED (409 not-admitted). No cycle is possible.

**Why `for no key update` and not `for update`.** Both modes conflict with each other, with `for update` (discharge, transfer, sign-off) and with plain updates of the admission. So two saves of one admission still run one after the other, and a save and a discharge never interleave. `for no key update` does not block the key-share locks that FK checks take, so a deposit or charge added to the same patient at the same moment does not wait behind a vitals save.

**Two concurrent saves and the one-open-escalation index.** Save A gets the row lock. Save B waits on it. A reads "no open escalation", inserts one and commits. B then gets the lock. In READ COMMITTED each later statement takes a new snapshot, so B's escalation read sees A's row. B then updates it (higher score) or leaves it (equal or lower score). It never inserts a second one. The partial unique index `unique (tenant_id, admission_id) where status <> 'DONE'` is the last line of defence, in case a code path skips the lock. The second insert then blocks on the index entry until the first commits and fails with 23505. The service must not swallow this error: it maps to 500, the whole save rolls back (vitals, note and events too), and the client keeps the typed values. A retry with the same clientKey is not a replay, because nothing was stored, so it saves and updates the open escalation. Under REPEATABLE READ step 3 would see a stale snapshot, which is why READ COMMITTED is required.

**Rule for the Safety board story.** Acknowledge, record response and call senior must take the same admission row lock first (`for no key update`) before they change an escalation. Raise, update and close then never interleave. The `version` column stays as an extra optimistic check.

### D5 · Idempotency and the lost answer: reuse the SAH-28 clientKey pattern

The platform already has the pattern: `EmergencyCaseInput.clientKey` (ADR-005, SAH-28 AC13). `VitalsInput.clientKey` works the same way:

- The client makes the key when the sheet opens. It keeps the key for a retry of the same values. It makes a new one after a success, or when the user changes any field or the note.
- The server checks for a repeat under the admission lock. **Review fix pass (security review SAH-30 L1, L2):** a key already used for this admission is compared with the stored record: the parsed systolic, diastolic, pulse, SpO₂, temperature in tenths and RR, and `noteAdded` (whether this body carries a note that is not empty after the note rules). The note text is not compared, because the note row is not linked to the vitals row (D2).
  - All equal: 200 with the sheet as it is now and `saved` set to the first record (`replayed: true`, `escalation.outcome: NONE`). No vitals, note, escalation, clinical event or audit is written. One `patient.record_access` row **is** written through `PatientAccessPort.recordOpened`, as on the GET, because the answer is the full sheet (name, vitals, other people's notes); repeated POSTs are then not an untraced read.
  - Any value different: 409 problem type `client-key-reused`, detail "This reading was already saved with different values. Reload the page and try again." Nothing is written, not even an access row. A reused key can no longer silently drop a new reading (for example a new High score). With the client key lifecycle above this only happens after a client fault or a crafted request. The client keeps the typed values, shows the detail and makes a new key (a retry with the old key would be refused again).
  - A key used for another admission answers 400 clientKey (unchanged).
- `asOf` of a replay is the database clock at the replay (D1, Time).
- So a double tap, or a retry after a lost answer, never stores a second set of vitals or a second "Recorded vitals" event. Save is also disabled while the request runs (AC12), but that alone does not cover a lost answer. A new set entered on purpose (values changed) gets a new key and a new record. That is correct: it is a new reading.
- The toast of a replay is built from `saved` and `escalation.open`, so it repeats the first answer's message. The only exception is when the escalation has since been closed (a later story). The client then shows the "Vitals saved" toast.
- **After a success Save stays disabled** until the user types in a field or the note (code review M4, DEC-013 item 13), so one more tap cannot store an identical second reading.
- **Same-values confirmation (security review SAH-30 M3, OQ-30-8, DEC-013 item 13; client only, no server change).** When `latest` is set and the five typed values, after the client format check, equal the last record exactly (systolic, diastolic, pulse, SpO₂, temperature to one decimal, RR; the note is not compared), the first tap sends nothing and shows the warn notice "These are the same values as the last reading. Check them against the patient, then choose Save again to confirm." The second tap saves. Any edit removes the notice. The server still accepts equal values as a new reading (a stable patient may truly have them). This keeps the mockup's prefill (Q13) while making an unchanged save a deliberate act; the wording is the contract's, not the mockup's. The human may still choose empty fields with the last values beside them.

### D6 · Record-access log when the Nursing sheet opens (OQ-30-7): recommend logging

**Recommendation: log it.** `GET /admissions/{id}/nursing` writes one `patient.record_access` row through the existing `patient.api.PatientAccessPort.recordOpened`, after the 404 check passes. It is the same rule as `GET /admissions/{id}` (SAH-23 security review M1) and `GET /admissions/{id}/discharge` (ADR-004 review decision a). Reasons:

- The sheet returns the patient's name together with clinical data (vitals, score, nursing notes), more clinical detail than the admission sheet, which is already logged.
- The owner's "Who opened which record" log (SAH-17) would otherwise miss the most frequent clinical read in a ward.
- DEC-014 says to take the safer direction for clinical items.

Cost: about 6 to 12 rows per patient-day per opening nurse, which is small. A stored save (201) writes no access row: its answer goes to the same user who just wrote the data, and IPD_VITALS_RECORDED already records it. **A replay (200) writes one access row** (review fix pass, security review SAH-30 L2): it writes no audit, so without the row a repeated POST would be an untraced read of the full sheet. A 409 client-key-reused writes none.

**Conflict:** story AC13 says the opening is not logged in this story, and the mockup logs only EMR opening (`sfLog 'Opened the clinical record'`). The contract follows this recommendation. The tech lead recorded it in DEC-013 item 13 (sheet read and replayed save are logged); **the human confirms (Q1)**, then the business-analyst amends AC13 and OQ-30-7 (code review M6).

### D7 · Audit and clinical event text

- **Audit `IPD_VITALS_RECORDED`**, one per stored save (none on a replay). Fields: `admissionId`, `vitalsId`, `ewsScore`, `ewsRisk`, `noteAdded`, `escalationId` (null when there is no open escalation), `escalationOutcome` (RAISED, UPDATED, NONE). It never holds a vital value, the note, the patient's name or any other name. The brief listed "ids, score, risk, noteAdded". `escalationOutcome` is added because raising an escalation is a clinical action that must be audited (CLAUDE.md), and a separate event type would only duplicate the ids (Q12).
- **Clinical events** (`ipd.clinical_event.text`, at most 300 characters) are built only from fixed templates with numbers and fixed words. The kinds this story writes:
  - `info`: "Recorded vitals · EWS 6 (medium)"
  - `esc`: "Raised early warning escalation · EWS 6 (medium) · doctor review within 1 hour" (or "within 15 min" for high)
  - `esc`: "Escalation updated · EWS now 8 (high)"
  
  The risk word is written in lower case. No user text is ever put in an event. Later stories may name a medicine (story section 3), but never a patient name or a note. The actor and time come from the columns, not from the text.
- **Logs:** vitals, notes, names and scores are never written to application logs (CLAUDE.md, AC13). Log lines carry ids, `tenantId` and `requestId` only.

### D7a · Escalation toast wording: a recorded deviation from the mockup (review fix pass)

The mockup's toast ends " · duty doctor alerted". Phase 1 sends no alert (OQ-30-2), and the earlier contract wording "Escalation raised ... · doctor review within 1 hour" still suggested the doctor was told (security review SAH-30 M2). The Medium and High toasts now read:

- "Escalation raised for <patient name> · EWS N · doctor review within 15 min. Tell the doctor now: no alert is sent." (target 15, HIGH)
- "Escalation raised for <patient name> · EWS N · doctor review within 1 hour. Tell the doctor now: no alert is sent." (target 60, MEDIUM)

The Low toast "Vitals saved for <patient name> · early warning N (low)" is unchanged. This is a **deviation from the mockup**, decided by the tech lead and recorded in DEC-013 item 13 for the human to confirm. It stays until the Safety board story exists (an escalation then reaches a screen a doctor watches); that story decides the wording again. The clinical event text (D7) is unchanged, because it records what happened, not an instruction. SAH-30 must still not be used on a live ward before the Safety board (OQ-30-2) and the clinical sign-off of the bands (OQ-30-6).

### D7b · Note text: tab, raw size and characters (review fix pass)

Order of the note checks (contract `VitalsInput.note`): (1) a raw note longer than 4,000 UTF-16 code units (`String.length()`) is refused with "Keep the note to 1,000 characters." **before** NFC, so normalisation never runs on an unbounded string (security review SAH-30 L8; a global request body limit is HARD-2); (2) NFC; (3) CR LF and CR become LF, and **a tab becomes one space** (code review N7: a tab pasted from another program is common and harmless); (4) the IPD text rule on the whole text, LF being the only allowed control character; every other control character is still refused; (5) strip and the 1,000 code point limit. The message "Note cannot contain control or text-direction characters" is kept unchanged: it is the shared IpdText wording of every IPD text field, and the contract now lists which characters are fine (letters of every script with their conjuncts, digits, punctuation, symbols, spaces, tabs and line breaks). Extending the message would not break the frontend (it shows the server's message as it comes), but it is not needed now. Open point: an emoji followed by a variation selector (U+FE0F, as phone keyboards send for many emoji) is still refused (Q16).

### D8 · Module boundaries and menu

- **ipd only.** Patient name: `patient.api.PatientLookupPort` (existing). Access row: `patient.api.PatientAccessPort` (existing). Recorder names: the existing actor display-name lookup. IPD state: the existing entitlement read. **No dependency on `opd` or `billing`**, and no new port, event or library. `ModularityTests` must stay green with `com.sahaj.ipd` unchanged in its allowed dependencies (`platform.api`, `patient.api`, `opd.api`).
- **No menu key.** The Nursing sheet is a sheet opened from the footer button "Nursing" on the admission sheet (OQ-30-1), controlled by `AdmissionDetail.canOpenNursing`. It is not a page, so `/platform/me` `menu` and `platform.yaml` do not change. A badge (for example "vitals due" or open escalations) would follow ADR-005 D3 (summary endpoint in the owning module) in the card or Safety board story.
- **Phase 1 scope held:** no notification, no device integration, no document, no analytics. The mockup's "duty doctor alerted" is not shown.

### D9 · Retention of clinical data

Vitals, nursing notes, escalations and clinical events are part of the in-patient medical record. Phase 1 never deletes or purges them: there are no delete grants, and triggers refuse delete and truncate. The Indian Medical Council (Professional Conduct) Regulations, 2002 (1.3.1) ask doctors to keep in-patient records for at least 3 years from the start of treatment. Hospitals commonly keep them longer for medico-legal cases and accreditation. A DPDP erasure request does not override a legal duty to retain medical records. **The human sets the retention period** (Q10). A later archival job would move whole old partitions, which D2 makes cheap. Closing a clinic's subscription does not delete these rows.

## Consequences

**Positive.**
- One small lock per save: ward saves for different patients never wait on each other or on admits.
- The one-open-escalation rule is guaranteed by the lock, with the partial unique index as a backstop.
- The score is computed once, on the server, in one tested class that the card will reuse.
- Retries are safe through a pattern the team already uses.
- No new dependency, menu key or service.
- The table design keeps partitioning cheap: no FK points at the table, and with the index built concurrently first (D2) the attach does not block writes.
- A role change applies at once to vitals saves and sheet reads; a reused key never drops a new reading; every answer that returns the sheet is in the record-access log.

**Negative / accepted.**
- A raised escalation reaches nobody until the Safety board story ships (OQ-30-2). SAH-30 must not be used in a live ward before it. The toast says so (D7a), which departs from the mockup.
- Each GET and POST costs one more indexed membership read (the role re-read), until HARD-2 moves the role into the cached membership check.
- An unchanged reading needs a second tap (D5); a small cost for a nurse with a stable patient.
- The bands are unvalidated (OQ-30-6).
- An escalation open at discharge stays open (Q9).
- Two nurses saving vitals for the same patient in the same second are ordered by transaction start time, not commit time. `now()` is taken when the transaction starts, before it waits on the lock. The effect is invisible at minute resolution.
- Every `AdmissionDetail` answer now runs one indexed lookup for the latest vitals.

## Data needed (for db-engineer; one forward-only migration `V<ts>__ipd_vitals_notes_ews.sql`)

- `ipd.vitals`: `id uuid pk default gen_random_uuid()`, `tenant_id uuid not null default platform.current_tenant()`, `admission_id uuid not null`, `client_key uuid not null`, `bp_systolic smallint not null check 40..300`, `bp_diastolic smallint not null check 20..200`, `pulse smallint not null check 1..300`, `spo2 smallint not null check 1..100`, `temp_c numeric(3,1) not null check 25.0..45.0`, `rr smallint not null check 1..300`, `ews_score smallint not null check 0..15`, `ews_risk text not null check in (LOW, MEDIUM, HIGH)` plus the two consistency checks of D1, `note_added boolean not null`, `recorded_at timestamptz not null default now()`, `recorded_by uuid not null`. FK `(tenant_id, admission_id) → ipd.admission (tenant_id, id)`. Unique `(tenant_id, client_key)`. Index `(tenant_id, admission_id, recorded_at desc)`. Grants: select; insert `(admission_id, client_key, bp_systolic, bp_diastolic, pulse, spo2, temp_c, rr, ews_score, ews_risk, note_added, recorded_by)`. Triggers deny update, delete, truncate. Comment: partition plan D2.
- `ipd.nursing_note`: `id`, `tenant_id`, `admission_id` (composite FK), `note text not null check (char_length(note) between 1 and 1000)`, `recorded_at timestamptz not null default now()`, `recorded_by uuid not null`. Index `(tenant_id, admission_id, recorded_at desc)`. Grants: select; insert `(admission_id, note, recorded_by)`. Triggers deny update, delete, truncate.
- `ipd.ews_escalation`: `id`, `tenant_id`, `admission_id` (composite FK), `ews_score smallint not null check 0..15`, `ews_risk text not null check in (MEDIUM, HIGH)`, `target_minutes smallint not null check in (15, 60)` with `(ews_risk = 'HIGH') = (target_minutes = 15)`, `status text not null default 'OPEN' check in (OPEN, ACK, DONE)`, `raised_at timestamptz not null default now()`, `raised_by uuid not null`, `acknowledged_by uuid`, `acknowledged_at timestamptz`, `response_action text`, `response_note text`, `senior_calls smallint not null default 0 check >= 0`, `closed_at timestamptz`, `version bigint not null default 0` (integer in V2026_11_23, changed to bigint in V2026_11_25 with the two score checks and the version guard of D1). Partial unique index `(tenant_id, admission_id) where status <> 'DONE'`. Grants: select; insert `(admission_id, ews_score, ews_risk, target_minutes, raised_by)`; update `(ews_score, ews_risk, target_minutes, version)` only (the Safety board story widens it). Optional trigger: while not DONE, `ews_score` may only rise. Triggers deny delete, truncate.
- `ipd.clinical_event`: `id uuid pk`, `seq bigint generated always as identity`, `tenant_id`, `admission_id uuid null` (composite FK, MATCH SIMPLE so null is allowed), `kind text not null check in (give, order, verify, esc, view, bg, deny, stop, info)`, `text text not null check (char_length(text) between 1 and 300)`, `actor uuid not null`, `at timestamptz not null default now()`. Indexes `(tenant_id, at desc, seq desc)` and `(tenant_id, admission_id, at desc, seq desc)`. Grants: select; insert `(admission_id, kind, text, actor)`. Triggers deny update, delete, truncate.
- Tenant scope: all four tables are tenant tables with FORCE RLS on `tenant_id = platform.current_tenant()`. Retention: never deleted in phase 1 (D9).
- Review fix pass (no new table or column): the escalation checks, `version bigint` and the version guard are in `V2026_11_25_1000__ipd_vitals_fixes.sql` with the corrected partition runbook comments (D2). The replay comparison reads the existing `ipd.vitals` columns and `note_added`; the role re-read uses `platform.membership` through `platform.api` only; `asOf` needs only `select clock_timestamp()`. Nothing more for the db-engineer in this pass (`docs/database/SAH-30.md` already carries the D2 runbook).

## Instructions

**java-developer:**
1. `ipd.domain.EarlyWarningScore` (D3) and `VitalsDue`, with a unit test at every band edge listed in story section 5. Temperature is handled in tenths, as an `int`.
2. `VitalsService`: GET (role, state, 404, record access, sheet) and POST with the exact check order and lock of the contract and D4: `for no key update`, READ COMMITTED, and 23505 never swallowed. Parse the five strings with the contract patterns. Run the note text rule as in `IpdText`, with line breaks allowed and CR LF and CR stored as LF, raw text checked before strip, lengths in code points.
3. Clinical event writer with the fixed templates of D7. Audit `IPD_VITALS_RECORDED` (D7).
4. `AdmissionDetail` (every endpoint that returns it) gains `canOpenNursing`, `vitalsDue` and `latestEws`. `vitalsDue` and `latestEws` are null for FRONT_DESK.
5. Roles: GET OWNER, NURSE, DOCTOR. POST OWNER, NURSE. Every other role (including VISITING_DOCTOR and BILLING) gets 403.
6. Review fix pass: role re-read from the membership inside the transaction for GET and POST (D4 step 2); `asOf` and `vitalsDue` from `clock_timestamp()` (D1 Time; also `AdmissionDetail.vitalsDue`); explicit READ COMMITTED; bare-row lock then joined read in the save, discharge and transfer locks (D4 step 3); missing patient 404 (D4 step 4); replay comparison, 409 `client-key-reused` and the replay access row (D5, D6); note steps of D7b (raw 4,000 UTF-16 units first, tab to space).

**angular-developer:** Nursing button (`canOpenNursing`) in the admission sheet footer. Nursing sheet as in `NursingSheet`: the "ago" text comes from `asOf`, never the device clock; the notice texts; fields prefilled from `latest`, temperature with one decimal; the note always empty when the sheet opens; the role notice only for `readOnlyReason` ROLE. Save is disabled while the request runs, and typed values are kept on any failure. clientKey lifecycle as in D5. Toasts as in the POST description. Client-side format check with the same patterns and the same message. Review fix pass: the Medium and High toasts of D7a; Save disabled after a success until the user types; the same-values confirmation of D5; 409 `client-key-reused` handled like every failure (detail as error toast, values kept, no new key).

## Questions for the human

- **Q1 (privacy, OQ-30-7):** log Nursing sheet opening in the record-access log (recommended, in the contract) against story AC13 (not logged)? Confirm or strike.
- **Q2 (mockup wording):** the toast reads "Escalation raised for … · doctor review within …" also when the open escalation was only updated or left as it was. This is what the mockup's `sfRaise` and wrapped `pcvsave` do, and the contract follows the mockup. Should an update say "updated" instead? (That would be a mockup change.)
- **Q3 (mockup toast):** the mockup shows "Vitals saved …" and then, for Medium or High, the escalation toast replaces it. The contract asks for one toast: the escalation toast for Medium or High. " · duty doctor alerted" is dropped because no alert exists (story).
- **Q4 (subtitle):** the mockup subtitle includes the assigned nurse ("Bed 202 · Sr. Anjali Pawar · last vitals …"). The story and the contract leave it out until ward assignment exists.
- **Q5 (read-only view):** in IPD Read-only or for a discharged patient the fields are disabled and there is no save button, but no notice is shown. The mockup has a notice only for the role case. Is a notice wanted, for example "This patient is discharged."?
- **Q6 (vitals for front desk):** `AdmissionDetail.vitalsDue` and `latestEws` are null for FRONT_DESK, because the role has no vitals access (AC1). Confirm.
- **Q7 (note line breaks):** the note is a 3-row text area, so line breaks are allowed and stored as LF, unlike every other IPD text field. The mockup shows notes in a single span, where line breaks collapse into spaces. Should the client show line breaks?
- **Q8 (clinical):** a BP with systolic at or below diastolic (for example 80/120, a likely swap) is accepted and scored on the systolic. Refuse it?
- **Q9 (patient safety):** an escalation still open at discharge stays open for ever, and a later Low score never closes one (mockup). The Safety board story must decide automatic closing at discharge.
- **Q10 (retention):** how long are in-patient clinical records kept (at least 3 years under the IMC regulations; longer by hospital policy)? Phase 1 keeps them for ever.
- **Q11 (story deviation):** `clinical_event.actor` holds the user id, as every other IPD actor column does, not the membership id the story names.
- **Q12 (audit content):** the audit event adds `escalationId` and `escalationOutcome` to the brief's list (ids, score, risk, noteAdded). There is still no clinical value or text.
- **Q13 (OQ-30-8, safety):** fields prefilled with the last values (mockup) can be saved unchanged by mistake, and the clientKey does not prevent that, because the key is new after the last success. Keep the mockup's prefill, or show the last values beside empty fields?
- **Q14 (units):** temperature is °C only (mockup). Many Indian wards chart in °F. A value such as 98.6 is refused by the plausible range ("Check the temperature. Enter 25.0 to 45.0 °C.") instead of being converted. Confirm.
- **Q15 (contract wording):** the plausible-range messages and the not-admitted message "This patient is not admitted any more. Vitals cannot be saved." are this contract's wording, not the mockup's or the story's. The review fix pass adds three more: the 409 detail "This reading was already saved with different values. Reload the page and try again.", the confirmation notice "These are the same values as the last reading. Check them against the patient, then choose Save again to confirm." and the D7a toasts.
- **Q16 (note characters):** an emoji sent with a variation selector (U+FE0F, as many phone keyboards do) is still refused with the control-character message. Drop U+FE0F silently like the tab, or keep refusing it?
- **Q17 (rule departure):** partitions quarterly, monthly at the top of growth, against the postgresql.md rule "monthly" (D2). Accept?
