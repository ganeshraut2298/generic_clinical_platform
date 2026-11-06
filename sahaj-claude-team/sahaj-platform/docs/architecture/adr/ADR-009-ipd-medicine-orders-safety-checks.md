# ADR-009 · IPD medicine orders: one safety-check class, order lifecycle under the admission lock, pharmacist check with a personal PIN

Story: SAH-31 (IPD part 10: medicine orders with safety checks, and the pharmacist check)
Mockup screens: EMR sheet (card → EMR: `pcEmr`, `sfIdBar`, `sfOrdBlock`, `sfCheck`, `sfordchk`, `sfordx`, `sfstop`) and IPD → Safety, panel "Pharmacist check" with its sheets (`ipSafety`, `pendRow`, `sfver`, `sfverok`, `sfrej`, `sfrejok`, `sfCount`)
Module: ipd (reads patient data through `patient.api`, team data and the PIN check through `platform.api`)
Status: PROPOSED (6 Nov 2026), with the SAH-31 contract. Accepted when the human approves `contract`. **Amended 6 Nov 2026 after the build:** D5 step 3 (third-lock answer), "Build notes" B1 to B7 (as-built choices, including RLS on the reference tables, which replaces the earlier "no RLS" text). `docs/approvals.md` has no `stories` or `contract` line for SAH-31; work proceeds under DEC-014. **Amended again 6 Nov 2026 after the reviews** (docs/reviews/SAH-31-code.md, SAH-31-security.md; DEC-017 "Review decisions"): Q10 re-opened and decided (record-access rows for the pharmacist list, D7, B3); allergy keyword list extended and "unknown" / "not known" / "?" read as not recorded (D2, B8); the allergy read follows `merged_into_id` and a merge combines allergy and chronic texts (D9, B9); the Verify sheet reads the not-set reason from `GET /me/pin` (B10); accepted review points R2, R8, R10 (B11); open questions Q15 to Q18 for the human. **D6 (the PIN) and D11 (PHARMACIST menu) were decided by the human in DEC-017 (6 Nov 2026): personal staff PIN (option B), designed in ADR-010; OWNER and PHARMACIST verify and reject, PHARMACIST gets the Safety-only page.**
Deciders: Ganesh (human), tech-lead; recorded by solution-architect
Related: ADR-010 (staff PIN), DEC-017, `docs/stories/SAH-31.md`, `docs/stories/SAH-32.md` (MAR, same PIN), `docs/decisions/IPD-open-questions.md` (OQ-31-1 to OQ-31-8), DEC-014, DEC-015, ADR-005 (clientKey, summary endpoints), ADR-006 (D1 clinical_event, D4 lock, D5 replay, D6 record access, D7 templates), ADR-007 (D3 lock rule, D8 counts-only polling, D12 no access row for work lists, D13 keyset cursor, R1 change token), `docs/api/ipd.yaml`

## Context

1. SAH-31 is the first IPD story where the system can stop a clinical act: a hard stop refuses an order. The rules (allergy keywords, duplicates, three interactions, high-alert flags, four override reasons) are the mockup's demo content (OQ-31-1, blocking for go-live).
2. The pharmacist check is a second person's check. The mockup accepts any 4 digits as a PIN and lets the user pick the verifier's name from a list (`SF_VERIFIERS`). Both give no assurance at all (OQ-31-3).
3. The patient's allergy is a free-text field of up to 400 characters in `patient.patient` (SAH-8); `ipd` may not read that table, and the existing `patient.api.PatientLookupPort` has neither the UHID nor the allergy.
4. Clinic users sign in with mobile and a one-time code; they have no password. There is no PIN store. The mockup does show a personal staff PIN ("Everyone signs in with their own 4-digit PIN", "PIN sign-in set up for <name>"), which is not built.
5. The mockup has no PHARMACIST role (the demo roles are Front desk, Nurse, Owner); the panel is shown to the owner only. The menu (`NavigationPolicy`) gives PHARMACIST only `today`, and `GET /admissions` (the Beds and admissions page) is OWNER, FRONT_DESK, NURSE, DOCTOR.
6. The platform's `OWNER` role has no doctor profile (`platform.doctor_profile` exists only for DOCTOR and VISITING_DOCTOR), so "OWNER when they are doctors" (story AC4) cannot be checked.
7. The IPD lock order is: clinic IPD advisory lock → bed rows → admission row. Vitals saves and escalation actions take only the admission row (`for no key update`, bare row first).

## Decision

### D1 · Order lifecycle

| From → to | Action | Who (token role and the role re-read from the active membership, both) | IPD Read-only |
|---|---|---|---|
| (new) → PENDING "Pharmacist check" | Check and order / Order with this reason | OWNER, DOCTOR | refused (409 ipd-readonly) |
| PENDING → ACTIVE "Active" | Verify order (PIN) | PHARMACIST, OWNER; never the person who ordered it | refused (409 ipd-readonly) |
| PENDING → REJECTED "Rejected" | Reject order (reason) | PHARMACIST, OWNER | **allowed** |
| PENDING or ACTIVE → STOPPED "Stopped" | Stop | OWNER, DOCTOR (any doctor, not only the orderer; mockup) | **allowed** |
| REJECTED, STOPPED | final | nobody | |

- Orders are never edited or deleted. A change is Stop, then a new order (story out of scope: "medicine changes").
- VISITING_DOCTOR, NURSE (except reading the list), FRONT_DESK, BILLING, COUNSELLOR: 403 for every action. VISITING_DOCTOR has no IPD access (SAH-23; OQ-31-8 open).
- **OWNER orders as a doctor** (the mockup's demo owner is the doctor, `sfIsDoc = role === 'owner'`). The platform cannot tell whether an OWNER is a clinician (Context 6); this is the same open point as ADR-007 Q11 (Q5 below).
- **Verify and reject are PHARMACIST and OWNER only.** The story also lists "a DOCTOR who did not place the order" (AC13). The panel is shown only to OWNER and PHARMACIST (AC8, mockup: owner only), so a DOCTOR would have an API right with no screen. The narrower set is taken (DEC-014 rule 3); Q4.
- **Self-verification** is refused for every role (403 `self-verification`), checked in Java and by a database check (`verified_by <> ordered_by`). Reject by the orderer is allowed (it only removes a medicine; the doctor could equally stop it).
- **Read-only:** ordering and verifying add a medicine and are refused; stopping and rejecting remove one and stay allowed, so a patient still admitted in a Read-only clinic can be made safer (the DEC-015 OQ-33-9 reasoning). The story says "Read-only 409 on writes" (AC13); this is a deviation in the safer direction (Q6).
- **Discharge:** no change in this story. Orders of a DISCHARGED admission keep the status they had; every action on them answers 409 `not-admitted`; they leave the pharmacist queue and its count at once (the queue reads ADMITTED admissions only). Stopping all orders at discharge would record a clinical act nobody did; medicine reconciliation at discharge is a later story (Q11).
- **Safety board:** the owner's Safety tab gets the tile "Pharmacist checks" and the panel "Pharmacist check" (D10). Escalations are unchanged.
- **No billing link** in this story (no medicine charge, no pharmacy stock; Pharmacy is Later).

### D2 · One rule class: `com.sahaj.ipd.domain.MedicineSafetyCheck`

Pure Java (no Spring, no I/O), used by the order, the verify, the pharmacist panel and the EMR, so the four never disagree. Input: the drug catalogue (D3), the patient's allergy text, the admission's live orders (PENDING and ACTIVE, in chart order, without the order being checked), the candidate drug key. Output: `OrderCheck(allergyStatus, hardStops, warnings, checkToken)`.

**Allergy classes from the free text** (mockup `sfAlgCls`; OQ-31-4; **keyword list extended after security review S1**, DEC-017 "Review decisions", B8): the text is NFC-normalised and lower-cased with `Locale.ROOT`; a keyword matches when it occurs in that text **or** in the same text with hyphens and white space removed (so "Co-trimoxazole" and "co trimoxazole" match `cotrimox`). Substring match, no word boundaries, as the mockup.

| Class | Keywords (stems) |
|---|---|
| `penicillin` | `penicillin`, `amoxic`, `amoxy`, `cillin` (the "-cillin" ending: ampicillin, cloxacillin, amoxicillin), `augmentin`, `pcn` |
| `sulfa` | `sulfa`, `sulpha`, `sulf`, `sulph` (sulfonamide, sulphonamide), `cotrimox`, `septran`, `bactrim` |

Classes are evaluated in the order penicillin, sulfa. Known false positives in the safer direction (a hard stop that a clinician may find unnecessary): `sulf`/`sulph` also match "sulfate"/"sulphate" (for example "magnesium sulphate"). Hindi and Marathi text is not matched. The list is demo content: **clinical sign-off of the keywords and structured allergies remain the go-live blocker (OQ-31-1, OQ-31-4; Q3, Q12).**

**Allergy status** (the chip, AC2), first that applies: any allergy class found → RECORDED (red, "Allergy: <text>"); the text contains "none known" → NONE_KNOWN (green, "No known allergies"); the text is null, empty after stripping (ADR-007 R6 stripping), contains "not recorded", **or is only "unknown" or "not known" (after stripping, any case, a final full stop ignored) or only question marks and spaces ("?", "??")** → NOT_RECORDED ("Allergies not recorded"; added after security review S1: these texts say nobody asked, so the not-recorded warning must fire); otherwise RECORDED. A longer text that merely contains "unknown" (for example "Iodine; others unknown") stays RECORDED, so its text is shown in the red chip. The first rule is a deviation from the mockup, which shows the green chip for "Penicillin; none known otherwise" while it blocks penicillins (safer: a chip never says "No known allergies" when the check finds one; deviation table row 6).

**Findings, in this order** (mockup `sfCheck`, so the "first message" is deterministic):

| # | Rule | Kind | Code | Text (exact) |
|---|---|---|---|---|
| 1 | for each allergy class c in order: the drug has class c | hard stop | `ALLERGY_PENICILLIN` / `ALLERGY_SULFA` | "Documented allergy: <allergy text>. <drug name> is a penicillin." / "… is a sulfa drug." |
| 1b | same loop: c is penicillin and the drug has class cephalosporin | warning, Moderate | `ALLERGY_CROSS_CEPHALOSPORIN` | "Penicillin allergy: possible cross-reaction with cephalosporins. Confirm what reaction the patient had." |
| 2 | allergy status NOT_RECORDED | warning, Moderate | `ALLERGIES_NOT_RECORDED` | "Allergies are not recorded. Ask the patient and record them before the first dose." |
| 3 | a live order has the same drug key | hard stop | `DUPLICATE` | "<drug name> is already on the chart. Change the existing order instead of adding a second one." |
| 4 | for each live order (chart order) × each interaction rule (D3 order): the candidate has one class of the pair and the live drug the other (both directions) | warning, rule severity | `INTERACTION` (with `otherDrugKey`) | "<rule text> (<other drug name> is on the chart)." |

- **Rule 2 deviates from the mockup** in one point: the mockup warns only when the text says "not recorded"; the story (AC2, AC5) and this ADR also warn when the field is empty, because the chip then says "Allergies not recorded" (safer). OQ-31-6 (hard stop instead of warning) stays open.
- **Outcome rule** (AC5): any hard stop → BLOCKED; else any warning → needs a reason; else clear.
- **`checkToken`**: base64url without padding of the first 16 bytes of SHA-256 over the UTF-8 lines `<allergyStatus>` then, for every finding in order, `<code>|<severity>|<otherDrugKey or ->`, joined by `\n` (22 characters, the ADR-007 R1 encoding). It carries no allergy text, name or free text. It lets the server refuse an override or a verification made against findings the user did not see (D4, D5).
- Unit tests (java-developer): each allergy keyword and class (one test per keyword of the table, including "Amoxycillin", "Augmentin", "Ampicillin", "Cloxacillin", "PCN", "Sulfonamide", "Septran", "Bactrim", "Co-trimoxazole" and "co trimoxazole"), "unknown", "Not known.", "?" and "??" as NOT_RECORDED and "Iodine; others unknown" as RECORDED, upper/lower case, the cephalosporin warning, empty / blank / "not recorded" / "none known" texts and the keyword-wins rule, duplicate against PENDING and ACTIVE but not REJECTED or STOPPED, the three interactions in both directions, two warnings at once, hard stop plus warning, the order being checked excluded (verify), deterministic order and token.

### D3 · Catalogue: IPD reference tables, demo content, not editable by a clinic (OQ-31-1)

- **Where:** `ipd.drug` and `ipd.drug_interaction`, reference tables without `tenant_id` (global, the same rows for every clinic; RLS enabled and forced with a non-tenant policy, see "Build notes" B7), select-only for `sahaj_app`, seeded by a Flyway migration with exactly the 14 drugs and 3 interactions of the mockup and story AC12 (keys as the mockup: `pcm, panto, ondan, ns, ceft, amox, pip, cotri, asp, ibu, met, hep, ins, kcl`; select order = mockup order). Interaction pairs are stored once (`class_a < class_b`) and matched in both directions.
- **Not `opd.medicine`:** `ipd` may not read `opd` tables, `opd.medicine` has no classes or high-alert flags, and changing the OPD prescription list for an IPD rule engine would put OPD under the same clinical sign-off. Whether one shared list replaces both later is OQ-31-1 (Q3): it would become a `platform` or a shared reference schema read through an api port, decided with the clinical owner.
- **Clinic editing: none in phase 1.** Clinical rules need a reviewed change with tests (ADR-006 D3 reasoning); a change is a new migration. No setup screen exists in the mockup.
- **Code-side constants:** the allergy keywords and the cephalosporin cross-reaction rule are in `MedicineSafetyCheck` (they parse free text and go away with structured allergies, OQ-31-4). Frequencies are a Java enum and a DB check: `od` "Once daily" [8:00 AM], `bd` "Twice daily" [8:00 AM, 8:00 PM], `tds` "Three times daily" [8:00 AM, 2:00 PM, 8:00 PM], `q8` "8-hourly" [6:00 AM, 2:00 PM, 10:00 PM] (dose times for SAH-32). Override reasons and reject reasons are Java enums with DB checks (D4, D5).
- The catalogue is loaded once at start-up into an immutable in-memory object (reference data changes only by a migration, which means a deploy). A start-up check fails fast if a class the code relies on (`penicillin`, `sulfa`, `cephalosporin`) is missing.
- The medicine names are fixed reference text, so they may appear in clinical events (ADR-006 D7). If a later story lets a clinic edit names, they become user text and need the IpdText rules first.
- Javadoc and table comment: "demo content from the mockup, not clinically validated (OQ-31-1, blocking for go-live)".

### D4 · Placing an order: `POST /admissions/{id}/medicine-orders`

Body: `clientKey`, `drugKey`, `frequencyKey`, `overrideReasonKey` (optional), `checkToken` (optional, required with a reason).

Override reasons (select order, mockup `SF_OVR`): `BENEFIT_MONITORED` "Benefit outweighs the risk, monitoring in place", `SPECIALIST_ADVISED` "Specialist advised this combination", `ALLERGY_HISTORY_CHECKED` "Allergy history checked with the patient: mild rash only, no anaphylaxis", `CARDIOLOGY_STENT` "Recent coronary stent, cardiology advised".

Check order:
1. Before the transaction: token role OWNER or DOCTOR (403 forbidden, "Only a doctor can do this."); IPD Off 403 module-off; Read-only 409 ipd-readonly; body 400 (every field error together).
2. One READ COMMITTED transaction (declared explicitly): membership re-read (OWNER or DOCTOR, else 403 "Only a doctor can do this."); the bare admission row `for no key update` (404); the joined admission, bed, ward; the patient through `patient.api` (D9; 404).
3. **Replay**: an order with this `clientKey` for this admission: same drug, frequency and override reason → 200 outcome PLACED, `replayed: true`, nothing written except one record-access row (ADR-006 D6 rule: an answer with the sheet that writes no audit writes an access row); any value different → 409 `client-key-reused`; key of another admission → 400 field clientKey.
4. Not ADMITTED → 409 `not-admitted`.
5. Run the check. **Hard stop** → 200 outcome BLOCKED: nothing stored for the order; clinical event (kind deny, D7) and audit IPD_MED_ORDER_BLOCKED are written and committed. **Warnings and** (no reason, or `checkToken` missing or not equal to the current token) → 200 outcome NEEDS_REASON with the check; nothing written except one record-access row. **Warnings, a reason from the list and the current token** → place. **No finding** → place; a reason sent anyway is ignored (stored null: a stale screen may still show a select that no longer applies).
6. Place: insert PENDING with `ordered_by`, `ordered_at` (transaction time), the override reason, and the warning lines as the doctor saw them (`"<Moderate|Major>: <text>"`); clinical event (kind order); audit IPD_MED_ORDERED. 201 outcome PLACED.

**Why 200 with an outcome and not 409 for BLOCKED and NEEDS_REASON** (story section 4 says 409 `order-blocked` and `order-needs-reason`): the client needs the list of messages, and the hard-stop message contains the patient's allergy text. The platform's `ConflictException` carries only a detail, whose text "must carry no personal data" (it may be logged) and ADR-007 already chose "no extension member, no platform change". So the check result travels in a normal JSON body to the doctor who asked for the check, and a problem detail never holds clinical text. The client must switch on `outcome` (contract, required field). Deviation from the story (not the mockup), Q7.

**Lost answers and retries (AC15):** the clientKey (SAH-28/30 pattern) makes a retry return the first order instead of a false "already on the chart" hard stop and a false "Order blocked" entry. The client makes a key when the form opens, keeps it while the same medicine, frequency and reason are retried, and makes a new one after a 201/200 PLACED or when the medicine, frequency or reason changes. A BLOCKED or NEEDS_REASON answer stores nothing under the key, so the same key goes on to "Order with this reason". A retried BLOCKED request writes a second deny entry (accepted; the button is disabled while the request runs). The duplicate rule plus the partial unique index stay the last defence.

**Why the token on overrides:** the doctor chooses a reason for the warnings on the screen. If another doctor adds aspirin meanwhile, a new Major interaction appears; without the token the old reason would be recorded against a warning nobody saw. The answer is NEEDS_REASON again with the new list (no error text needed: the notice simply shows the new warnings).

### D5 · Verify and reject

**Verify** `POST /medicine-orders/{id}/verify`, body `pin`, `checkToken`:
1. Before any transaction: token role PHARMACIST or OWNER (403 forbidden); IPD Off; Read-only 409; body (pin `^[0-9]{4}$` else 400 "Enter the 4-digit PIN."; checkToken format else 400 "Reload the page and try again.").
2. A short read-only lookup of the order (404; RLS) and **self-verification**: `ordered_by` = caller → 403 `self-verification` "The doctor who ordered this cannot verify it. Ask the pharmacist." (before the PIN, so the orderer never spends PIN tries; `ordered_by` never changes, so reading it outside the lock is safe).
3. **The PIN check** through `platform.api.StaffPinPort` (D6, ADR-010 D9), in its own platform transaction that commits before the IPD transaction starts, so a wrong try is counted even though the request fails, and no second connection is held while IPD locks are held: `check(...).requireOk()` throws the platform's `StaffPinException`: WRONG 403 `wrong-pin`, LOCKED 403 `pin-locked`, NOT_SET 409 `pin-not-set`, RATE_LIMITED 429 `rate-limit`, UNAVAILABLE 503 `pin-unavailable` (texts in ADR-010 D11). The 5th wrong try in a row answers by the lock it causes: the first or second lock within 24 hours 403 `pin-locked`; the **third** lock within 24 hours 409 `pin-not-set` with the text "Your PIN was switched off after too many wrong tries. Set a new one, then try again." (reason `TOO_MANY_LOCKS`), because the PIN is already switched off at that moment (ADR-010 D5).
4. One READ COMMITTED transaction: membership re-read (PHARMACIST or OWNER); the order by id → its admission; the bare admission row `for no key update`; the order again; admission not ADMITTED 409 `not-admitted`; the patient through `patient.api` (404).
5. State: ACTIVE and verified by the caller → 200 `replayed: true`, nothing written. ACTIVE otherwise, REJECTED, STOPPED → 409 `order-state` (texts in the contract).
6. Run the check again now (the order itself excluded): any hard stop → 409 `order-blocked` "Hard stop on this order. Reject it."; `checkToken` not the current token → 409 `order-checks-changed` "The checks on this order changed. Review them and try again." (the pharmacist then reloads and sees the new findings; not in the mockup or the story, safer direction, Q8).
7. Update to ACTIVE, `verified_by` = caller, `verified_at` stamped by the database; event (kind verify) "Verified <drug>" plus " · accepted doctor's reason" when the order has an override; audit IPD_MED_VERIFIED. 200.

"Verified by" on the sheet is the signed-in user's name, read-only: the mockup's select of names (`SF_VERIFIERS`) is not built (story AC10; deviation table row 9).

**Reject** `POST /medicine-orders/{id}/reject`, body `reasonKey`: `ALLERGY_CONFLICT` "Allergy conflict", `INTERACTION_RISK` "Interaction risk too high", `WRONG_DOSE` "Wrong dose or frequency", `DUPLICATE_THERAPY` "Duplicate therapy" (select order, mockup `sfrej`). Same steps without the PIN and the self check; allowed in Read-only. PENDING → REJECTED with reason, `rejected_by`, `rejected_at` (database); event (kind verify) "Rejected <drug> · <reason label>"; audit IPD_MED_REJECTED. REJECTED by the caller with the same reason → 200 replayed; any other non-PENDING state 409 `order-state`. A reject is allowed whatever the findings. No message reaches the doctor (no notifications in phase 1); the doctor sees the reason under the order in the EMR (OQ-31-5 proposal, built).

**Stop** `POST /medicine-orders/{id}/stop` (no body): OWNER, DOCTOR (403 "Only a doctor can do this."); IPD Off; allowed in Read-only; the same transaction steps; PENDING or ACTIVE → STOPPED, `stopped_by`, `stopped_at` (database); event (kind stop) "Stopped <drug>"; audit IPD_MED_STOPPED. STOPPED by the caller → 200 replayed (one record-access row, it returns the sheet); STOPPED by another user or REJECTED → 409 `order-state`. No version is needed: every transition is one-way and is decided under the lock.

### D6 · The PIN (OQ-31-3) — DECIDED: option B (DEC-017 Q1); full design in ADR-010

**Update 6 Nov 2026.** The human chose B. The design below is kept as the record of the options; the binding design (table, hash, pepper, weak-PIN rules, lockout, set and reset, rate limits, audit, problem texts, the port) is **ADR-010**, which replaces the "Design of B" sketch and the "Until B exists" stub. Changes against the sketch: the table is keyed on the membership as sketched, but setting or changing a PIN needs a one-time code sent to the person's own mobile (not the current PIN); the owner reset path is `POST /team/{id}/pin/reset`; the self path is `GET/PUT /me/pin` and `POST /me/pin/code`; the port returns `PinCheck` (outcomes OK, WRONG, LOCKED, NOT_SET, RATE_LIMITED, UNAVAILABLE) with `requireOk()`; verify can now also answer 429 and 503. The medicine-order rules (D1 to D5, D7 to D12) are unchanged.

A PIN that is only checked for format gives no safety: anyone can type four digits, and the mockup lets the user choose whose name is recorded. **Not acceptable for release** (story AC10).

| Option | What | Verdict |
|---|---|---|
| A | Format-only PIN (mockup) | Rejected: a fake control in a clinical record. |
| **B** | **Personal 4-digit staff PIN in `platform`**, set by each person, checked by `platform.api.StaffPinPort` | **Recommended.** Matches the mockup's own "Everyone signs in with their own 4-digit PIN" and "PIN sign-in set up for <name>"; SAH-32 reuses it for the second-nurse check. |
| C | Re-authentication with a one-time code to the phone | Rejected for phase 1: needs an SMS/WhatsApp gateway (not in phase 1). Clinic users have no password to re-enter. |
| D | No PIN: the signed-in user is the verifier | Fallback only if the human drops the PIN for phase 1: honest (identity = the session), but it removes the mockup's PIN field (a mockup deviation) and gives no protection on a shared, signed-in computer. |

Design of B (for a small platform story; not built by SAH-31 without the human's go):
- Table `platform.staff_pin` per membership (tenant table, FORCE RLS): `membership_id` pk and FK, `tenant_id`, `pin_hash`, `failed_count`, `locked_until`, `set_at`. The hash is BCrypt (already in the build through spring-security-crypto; no new library) of an HMAC-SHA256 of the PIN with a server secret `SAHAJ_PIN_PEPPER` (new environment variable, human-owned `.env` and `.env.example`), so a database copy alone cannot be brute-forced over the 10,000 possible PINs.
- `StaffPinPort.check(tenantId, userId, pin)` → `OK | WRONG | LOCKED | NOT_SET`, in its own transaction (`REQUIRES_NEW` is not needed: it is called before the IPD transaction, D5 step 3). 5 wrong tries in a row lock it for 15 minutes; a success resets the count; the lock is audited (PLATFORM_PIN_LOCKED: membership id only). The PIN is never logged, audited, stored in clear or echoed.
- Set and reset (screens not in the mockup): the user sets or changes their own PIN after sign-in (`PUT /api/v1/platform/me/pin`, current PIN required to change); the owner can clear someone's PIN (`POST /api/v1/platform/team/{id}/pin-reset`) so they set a new one. Trivial PINs (0000, 1234, four equal digits, straight runs) are refused. Sketch only; the platform contract is written when the human chooses B.
- **Until B exists**, the java-developer implements the verify against the port, and the port's only implementation answers NOT_SET (fail closed): Verify order answers 409 `pin-not-set` and nothing is verified. Unit tests use a test double. SAH-31 cannot be released, and its verify path cannot be tested end to end, before the PIN story (Q1, blocking).

### D7 · Clinical event log and audit (no clinical free text)

Clinical events (`ipd.clinical_event`, existing kinds; actor = the caller's user id; time = the transaction's; names never stored):

| Kind | Text (exact template) | When |
|---|---|---|
| view | "Opened the clinical record" | every 200 of `GET /admissions/{id}/emr` (not the order or stop answers, mockup `keep` flag) |
| deny | "Order blocked: <drug name> · <first hard stop as logged>" | BLOCKED outcome |
| order | "Ordered <drug name> <frequency label in lower case>" + " · override: <reason label>" when a reason is stored | 201 PLACED |
| stop | "Stopped <drug name>" | stop |
| verify | "Verified <drug name>" + " · accepted doctor's reason" when the order has an override | verify |
| verify | "Rejected <drug name> · <reject reason label>" | reject |

"First hard stop as logged": for DUPLICATE the mockup text exactly ("<drug name> is already on the chart. Change the existing order instead of adding a second one."); for an allergy hard stop **"Documented penicillin allergy. <drug name> is a penicillin."** / **"Documented sulfa allergy. <drug name> is a sulfa drug."** The mockup writes the full message, which contains the patient's free-text allergy; AC14 forbids clinical free text in the log, so the class word replaces it (deviation table row 7). Longest text is about 190 characters (limit 300). Replays write no event.

Audit (`AuditLog.record`, entity `IPD_MEDICINE_ORDER`, or `IPD_ADMISSION` for a blocked attempt), ids and fixed keys only:

| Event | Fields |
|---|---|
| IPD_MED_ORDERED | orderId, admissionId, drugKey, frequencyKey, status PENDING, overrideReasonKey (or null), warningCount |
| IPD_MED_ORDER_BLOCKED | admissionId, drugKey, frequencyKey, status BLOCKED |
| IPD_MED_STOPPED | orderId, admissionId, drugKey, frequencyKey, statusBefore, status STOPPED |
| IPD_MED_VERIFIED | orderId, admissionId, drugKey, frequencyKey, status ACTIVE, overrideReasonKey |
| IPD_MED_REJECTED | orderId, admissionId, drugKey, frequencyKey, status REJECTED, rejectReasonKey |

`warningCount` and `rejectReasonKey` are added to the story's list (a number and a fixed key; no clinical text). The allergy text, the patient's name, the PIN and the warning texts never go into an audit row, an event, a problem detail or an application log (log lines: ids, tenantId, requestId).

**Record access** (`patient.api.PatientAccessPort`): one row on every 200 of `GET /admissions/{id}/emr` (AC1) and on every answer that returns the EMR sheet without writing an audit row (NEEDS_REASON, replays of order and stop). **Pharmacist list (Q10 re-opened and decided after security review S3, DEC-017 "Review decisions"):** every 200 of `GET /pharmacist-checks` writes **one row per distinct patient among the items of that page**, for the caller, in the same transaction (a page shows names, UHID, age, sex and allergy text of up to 100 patients, which is more than a work list of counts). An empty page writes none; "Show more orders" writes rows for the patients of the page it loads. No row for BLOCKED (it writes the audit row IPD_MED_ORDER_BLOCKED), the counts-only summary `GET /pharmacist-checks/summary`, or the verify and reject answers (their first page of the queue writes none in this version; open, Q17). The earlier "no row for the pharmacist queue, a work list like the Safety board (ADR-007 D12)" is **superseded** for this list; the Safety board keeps ADR-007 D12.

### D8 · Paging

- **EMR order list: not paged.** All orders of the admission, chart order (`ordered_at`, then id, ascending: the mockup appends). One admission rarely exceeds 100 orders (a 30-day stay with about 3 new orders a day); the read uses `(tenant_id, admission_id, ordered_at, id)`. Revisit if a real admission passes 200 orders.
- **Pharmacist queue: keyset**, oldest waiting first (`ordered_at asc, id asc`; new orders arrive at the end, so pages do not shift), `limit` 1 to 100, default 50, cursor = base64url of `ordered_at` (epoch microseconds) and id (ADR-007 D13 pattern; not signed, scope-limited). "Show more orders" (contract wording, not in the mockup) loads the next page. `waiting` is always the full count. Scope: PENDING orders of ADMITTED admissions of the clinic (every ward; ward scoping does not exist).
- **Counts-only summary** `GET /pharmacist-checks/summary` (`waiting`, `changeToken`, `asOf`; no names; ADR-005 D3, ADR-007 D8): polled every 60 seconds while Beds and admissions shows the Safety tab to OWNER or PHARMACIST; the client reloads the queue when `waiting` or `changeToken` differ. `changeToken` = the R1 encoding over the sorted lines `<orderId>:<version>` of the counted orders.
- Reads (`GET /emr` aside, which writes the view event) run in one REPEATABLE READ transaction so the count, the rows and their checks agree (ADR-005 D6); `asOf` from the database clock. The summary's transaction is read-only. `GET /pharmacist-checks` is **not** read-only since the review fix (it writes the record-access rows of D7 in the same transaction; `PatientAccessPort.recordOpened` is `Propagation.MANDATORY`); the rows are inserts only, so REPEATABLE READ raises no serialisation failure on them.

### D9 · Module boundaries

- **ipd only** for tables and rules. Uses:
  - `patient.api` — **new additive port `PatientClinicalPort`** (`find(tenantId, patientId)`, `findAll(tenantId, ids)`) returning id, UHID, name, age, sex, allergy text; `toString()` with ids only. Implemented in `patient`. **Follows `merged_into_id`** (security review S4, B9): when the requested patient was merged into another record, the port answers with the kept record's data (end of the chain), keyed by the requested id, so an admission that points at a merged-away record sees later allergy updates. `ipd` already depends on `patient.api`, so no new module dependency. Existing `PatientAccessPort` for the access row.
  - `platform.api` — existing `TeamMemberPort.findActiveByUserId` (role re-read), `ActorDirectoryPort` (names, "Team member"), `AuditLog`, entitlement; **new `StaffPinPort`, `PinCheck`, `StaffPinException`** (D6, ADR-010 D9).
  - Nothing from `opd` or `billing`. `ModularityTests` must stay green.
- **Menu** (D11, DECIDED by DEC-017 Q2): `NavigationPolicy` adds `ipd` for PHARMACIST.
- Phase 1 held: no notification (the rejection reaches the doctor only on screen), no pharmacy stock or dispensing, no document, no analytics, no external drug database.

### D10 · Concurrency and lock order

- Every write (order, stop, verify, reject) takes **exactly one row lock: the admission row**, `select id, status from ipd.admission where tenant_id = :t and id = :id for no key update`, bare row first, then the joined read in a new statement (ADR-006 D4, ADR-007 D3). No IPD advisory lock, no bed lock. Explicit `@Transactional(isolation = READ_COMMITTED)`.
- Order actions find the admission through a plain read of the order first (as escalation actions), then lock, then re-read the order.
- **Against the existing order** (advisory → bed → admission): discharge and transfer take the admission row `for update`, which conflicts with `for no key update`, so a discharge and a medicine action never interleave; a medicine action holds one lock and waits for nothing while holding it (its inserts take key-share locks on the row it holds; patient and platform reads take no locks), so no cycle exists. The PIN check runs and commits before the IPD transaction (D5), so no platform row lock is ever held together with the admission lock.
- **Races (QA, real-database ITs):** two orders of the same drug at once (one PLACED, one BLOCKED duplicate); order vs verify on the same admission adding an interaction (verify 409 `order-checks-changed`); verify vs stop (stop first → verify 409 `order-state`; verify first → stop of an ACTIVE order succeeds); verify vs reject (one wins, the other 409 `order-state`); two verifies by two pharmacists (one 200, one 409 `order-state`); order vs discharge (discharge first → 409 `not-admitted`).
- **Database backstops** (a hit is a code bug: 500, full rollback, never swallowed): partial unique index on live orders of the same drug; the guard trigger on transitions; `verified_by <> ordered_by`.
- **Accepted race:** the allergy text lives in `patient` and is not locked by IPD; a change committed between the order's read and its commit is not seen by that order. The verify re-runs the check, and a later hard stop disables Verify.

### D11 · Safety tab, counts and the PHARMACIST role — DECIDED (DEC-017 Q2, option A)

- **OWNER**: the existing Safety tab (SAH-33) gains, after the tile "Escalations open", the tile "Pharmacist checks" (`waiting`, small text "Orders waiting before the first dose", warn when above 0), and after the panel "Early warning escalations" the panel "Pharmacist check" (mockup order). The tab number is `safety.counts.open + waiting` (mockup `sfCount` adds the owner's waiting orders). Data from `GET /pharmacist-checks` and its summary; `GET /safety` is unchanged.
- **PHARMACIST** (OQ-31-2 proposal, Q2): `NavigationPolicy` adds `ipd` (Beds and admissions) for PHARMACIST when IPD is Trial, On or Read-only (after `today`). For PHARMACIST the page shows only the Safety tab with the tile and the panel; it never calls `GET /admissions` (which carries bills and deposits) or `GET /safety` (escalations stay OWNER, DOCTOR, NURSE; DEC-015). The tab is shown even with no admitted patient (empty: "No orders waiting."). The mockup has no pharmacist role, so this page is a deviation for sign-off (deviation table row 11).
- DOCTOR and NURSE see no pharmacist tile or panel (mockup: owner only).
- `AdmissionDetail.canOpenEmr` (button **EMR**, emr icon, on the admission sheet footer; OQ-31-7): OWNER, DOCTOR, NURSE (token and membership), ADMITTED, IPD Trial, On or Read-only. Read-only is added to the story's "Trial or On" so a doctor can still read and stop medicines of a patient in a Read-only clinic (Q6).

### D12 · Retention

Medicine orders are part of the in-patient record (ADR-006 D9): never deleted; no delete grant; triggers refuse delete and truncate. The period is the human's (ADR-006 Q10). Volume: about 100 hospitals × 150 beds × 1 to 3 orders a day = 15,000 to 45,000 rows a day (5 to 16 million a year): no partitioning needed in phase 1. **`ipd.clinical_event` and `patient.record_access` grow faster**: each EMR opening writes one row in each (estimate 10 to 20 openings per patient-day, 150,000 to 300,000 rows a day across 100 hospitals); the ADR-006 D2 partition procedure for `clinical_event` must start earlier than the vitals estimate suggested (db-engineer to restate the volumes). Since the review fix, each load of the pharmacist list adds up to one `patient.record_access` row per patient on the page (up to 100; the list is loaded when the Safety tab opens and when the 60-second summary changes, not on every poll), and EMR reloads after a refused action add rows too (B11); db-engineer to include both in the restated volumes.

## Build notes (as built, 6 Nov 2026; java-developer and db-engineer hand-backs, DEC-017 "Build-time choices")

These record how the build carries out D1 to D12. They change no rule; where the earlier text differs, the note says so.

- **B1 · Verify runs in three steps** (D5, D10). (1) A short **read-only** READ COMMITTED transaction (`MedicineOrderService.prepareVerify`): token role, module, Read-only, body, membership re-read, the order by id (404) and the self-verification rule (403); it writes nothing and commits. (2) The **PIN check outside any transaction** (`StaffPinPort.check(...).requireOk()`, in its own platform transaction that commits, ADR-010 D9). (3) The **IPD transaction** (`verifyLocked`, READ COMMITTED): token role, module, Read-only and body again, membership re-read again, the admission row locked `for no key update` (bare row first), the order re-read, then state, self-verification and the safety check again, then PENDING → ACTIVE. Everything checked in step 1 is checked again in step 3 because the PIN check can take about 0.25 s and a role, module state or order may change in between. The PIN is not an argument of step 3.
- **B2 · Client-key replay** (D4 step 3): drug and frequency are always compared; the override reason is compared **only when the first order stored one**. A reason sent with no warning is ignored and stored null (D4 step 5), so an identical retry of that request must not be refused as `client-key-reused`. Contract wording aligned in `docs/api/ipd.yaml`.
- **B3 · Record-access rows** (D7; **amended after security review S3**, Q10 decided): written on every 200 of `GET /admissions/{id}/emr`, on NEEDS_REASON, on replays of order and stop (answers that return the EMR sheet and write no audit row), and on every 200 of `GET /pharmacist-checks`: one row per distinct patient among the items of the page, for the caller, in the same (no longer read-only) REPEATABLE READ transaction. **Not** written on BLOCKED (it writes the audit row IPD_MED_ORDER_BLOCKED and the deny event), the counts-only summary, verify or reject (the first page carried by verify and reject: open, Q17). Contract wording aligned in `docs/api/ipd.yaml` (`GET /pharmacist-checks`, `PharmacistActionResult`).
- **B4 · A waiting order whose patient record is missing** (a data fault) stays in the pharmacist queue with the patient shown as "Patient" (UHID empty, age, sex and allergy text null, allergy status NOT_RECORDED, so its check carries the "Allergies are not recorded" warning), the same rule as the Safety board (ADR-007). A list never drops a waiting order. Verifying or rejecting it still answers 404 (the patient is read through `patient.api` in the transaction). Contract wording aligned in `docs/api/ipd.yaml`.
- **B5 · Controller `@PreAuthorize`** on `MedicineOrderController` admits **any clinic role**; the per-action role (OWNER and DOCTOR for order and stop, PHARMACIST and OWNER for verify, reject and the queue) is checked in the service on the token role and again on the membership. This is deliberate: a Spring Security refusal would answer a generic 403, while the contract gives each action its own 403 detail ("Only a doctor can do this.", "Only the pharmacist or the clinic owner can check orders."). The annotation lists the eight clinic roles (OWNER, DOCTOR, VISITING_DOCTOR, NURSE, FRONT_DESK, BILLING, PHARMACIST, COUNSELLOR), so a token without a clinic role is still refused before the service.
- **B6 · `MedicineCatalogueStartupCheck`** (an `ApplicationRunner`) loads the catalogue at start-up: bad catalogue content (an empty catalogue, a repeated key, or no medicine of a class the code relies on: `penicillin`, `sulfa`, `cephalosporin`) is a fault that propagates and **stops the start** (fail fast, D3); a `DataAccessException` (database not reachable at that moment, as in context tests without a database) is logged as one WARN line and ignored, and the catalogue is read again on first use, where the same content checks apply.
- **B7 · RLS on the global reference tables** (D3, "Data needed" 1 and 2). The db-engineer **enabled and forced RLS** on `ipd.drug` and `ipd.drug_interaction` with policies `select` to `sahaj_app` using (true) and `all` to `sahaj_migrator` using (true) with check (true) (migration `V2026_11_28_1000__ipd_medicine_orders.sql`, section 3b). Reason: `node scripts/validate.mjs db` and `RowLevelSecurityCatalogIT` require RLS on every table created by a migration that mentions `tenant_id`, and this migration also creates `ipd.medicine_order`. **This replaces the earlier "no RLS, like `opd.medicine`" text.** The policies are not tenant-scoped (the tables have no `tenant_id`; every clinic reads the same rows); grants stay select-only for `sahaj_app`, so the app still cannot change the catalogue. Tenant isolation is unchanged.

**After the reviews (6 Nov 2026; DEC-017 "Review decisions"; the java-developer and angular-developer carry them out in the review fix pass):**

- **B8 · Allergy matching (security review S1).** The keyword table and the not-recorded texts of D2 replace the earlier four keywords. It only adds hard stops and warnings (safer direction). Clinical sign-off of the keyword list and structured allergies remain the **go-live blocker** (OQ-31-1, OQ-31-4; Q3, Q12). `docs/api/ipd.yaml` `AllergyStatus` aligned.
- **B9 · Merged patients (security review S4; patient module, SAH-8 code).**
  - `PatientClinicalPort.find` / `findAll` follow `merged_into_id` to the end of the chain (A merged into B, later B into C: C is read) and return the kept record's UHID, name, age, sex and allergy text under the requested id. Tenant-filtered at every step (RLS); a chain is followed at most 10 steps, and a longer chain or a cycle is a data fault answered as "not found" (the B4 rule for lists, 404 for actions). ipd is unchanged: it still passes the admission's `patient_id`, and record-access rows keep naming the admission's patient id.
  - **Patient merge never overwrites a non-empty allergy or chronic-condition text with an empty one.** For each of the two texts: only one record has a non-empty text → that text is kept; both have the same text (compared after stripping, any case) → kept once; both have different texts → combined as "<kept record's text>; <merged-away record's text>". Null and blank count as empty. This is the tech lead's choice ("keep any non-null text and combine both"), for the human to confirm against the mockup "Review and merge" (DEC-017). A combined text longer than the 400-character limit is open (Q18): it must never be cut silently.
  - No contract path or field changes; `docs/api/patient.yaml` `/patients/merge` is unchanged.
- **B10 · The not-set reason (code review R4).** After a 409 `pin-not-set` on verify, the Verify sheet reads `GET /api/v1/platform/me/pin` again and picks its notice by `PinStatus.notSetReason`; it never matches the problem detail text. If that read shows SET (a PIN set meanwhile in another tab), the PIN field is shown again. `docs/api/ipd.yaml` aligned.
- **B11 · Accepted review points (no change to the build):**
  - **R2:** reloading the EMR sheet after a refused action or a retry calls `GET /admissions/{id}/emr` again, which writes one more view event and one more record-access row for an opening the doctor did not make. Accepted; counted in the D12 growth.
  - **R8:** `ordered_at` is the database's `now()`, the **transaction start**, so an order committed later may carry an earlier time than one committed before it. Effects: the chart order of two near-simultaneous orders may differ from the commit order, and a pharmacist-list cursor already past that time may not show the order on the next page. It is never lost: `waiting` counts it, the summary's `changeToken` changes and the client reloads from the first page. Changing it needs a change to an applied migration (`clock_timestamp()`); not done.
  - **R10:** after a verify or reject the answer's first page of the queue replaces every page loaded with "Show more orders" (the list collapses to page 1). Accepted; noted for the test plan.
- **B12 · Order-lock lines (code review R6).** The EMR sheet shows one line in place of the order form for every `orderLockReason`: ROLE "Only doctors can order medicines." (unchanged), IPD_READONLY "IPD is read-only. Medicines can be stopped but not added.", NOT_ADMITTED "This patient is not admitted any more." The last two are new texts not in the mockup (deviation list, DEC-017). `docs/api/ipd.yaml` `EmrSheet` aligned.

## Options considered (summary)

- **409 problem with an extension for hard stops** vs **200 with an outcome (chosen)**: D4.
- **Checks in Java, catalogue in the database (chosen)** vs **everything in code** vs **everything in tables**: D3. Tables give FK integrity and a single source for names; the free-text parsing stays in code until structured allergies.
- **IPD catalogue (chosen)** vs **extend `opd.medicine`**: D3, OQ-31-1.
- **PIN**: D6.
- **Stop all orders at discharge** vs **leave them, drop them from the queue (chosen)**: D1.
- **Verify by any doctor (story)** vs **PHARMACIST and OWNER (chosen)**: D1.
- **Offset paging of the queue** vs **keyset (chosen)**: D8.

## Consequences

**Positive.** One rule class keeps the order form, the queue and the verify in agreement; a hard stop is enforced by the server on every request; retries never create a second order or a false "blocked" entry; an override or a verification is never recorded against findings the user did not see; no clinical free text in events, audit, problem details or logs; one row lock per action; no new library or module dependency.

**Negative / accepted.**
- Verification needs the staff PIN of ADR-010 and the secret `SAHAJ_PIN_PEPPER`; without the secret every verify answers 503 `pin-unavailable` (fail closed), and a person without a PIN answers 409 `pin-not-set`.
- A single-doctor clinic without a pharmacist cannot verify the owner's own orders (self-verification); such orders wait in "Pharmacist check" (Q4).
- Allergy matching by keywords misses other allergies, spellings and Hindi/Marathi text, and the wider stems give some false hard stops ("sulphate") (OQ-31-4; B8).
- Each EMR opening adds two rows (event, access); EMR reloads add more (R2); each load of the pharmacist list adds up to one access row per patient on the page (Q10).
- Toasts "It is now on the MAR." and "Remaining doses are removed from the MAR." describe SAH-32, which is not built yet (Q9).

## Data needed (for db-engineer; one forward-only migration, for example `V2026_11_28_1000__ipd_medicine_orders.sql`)

1. **`ipd.drug`** (reference, no tenant; ~~no RLS, like `opd.medicine`~~ superseded: ENABLE and FORCE RLS with `select` for `sahaj_app` using (true) and `all` for `sahaj_migrator`, see "Build notes" B7): `key text primary key` check `^[a-z0-9_]{1,20}$`; `name text not null unique` (1 to 80); `route text not null` (1 to 20; seed values Oral, IV, SC, IV infusion); `classes text[] not null default '{}'`, every element in (`cephalosporin`, `penicillin`, `sulfa`, `antiplatelet`, `nsaid`, `anticoag`, `insulin`, `potassium`) (check by an immutable function or `classes <@ array[...]`); `high_alert text null` (1 to 40: Anticoagulant, Insulin, Concentrated electrolyte); `sort_order smallint not null unique`. Seed the 14 rows of story AC12 with the mockup keys and names exactly (mockup `SF_DRUGS`). Grant `select` to `sahaj_app`; no insert, update or delete grant. Comment: demo content, OQ-31-1.
2. **`ipd.drug_interaction`** (reference; RLS as `ipd.drug`, B7): `class_a text`, `class_b text`, primary key `(class_a, class_b)`, check `class_a < class_b`, both in the class list; `severity text not null` check in (`MAJOR`, `MODERATE`); `text text not null` (1 to 200); `sort_order smallint not null unique`. Seed: (anticoag, antiplatelet, MAJOR, "Bleeding risk: heparin with aspirin", 1), (anticoag, nsaid, MAJOR, "Bleeding risk: heparin with an NSAID", 2), (antiplatelet, nsaid, MODERATE, "Stomach bleeding risk: aspirin with an NSAID", 3). Select only.
3. **`ipd.medicine_order`** (tenant table, ENABLE and FORCE RLS on `tenant_id = platform.current_tenant()`, select, insert and update policies):
   - `id uuid pk default gen_random_uuid()`; `tenant_id uuid not null default platform.current_tenant()` FK `platform.tenant`; `admission_id uuid not null`, FK `(tenant_id, admission_id) → ipd.admission (tenant_id, id)`; unique `(tenant_id, id)` (SAH-32 MAR rows will reference it).
   - `client_key uuid not null`, unique `(tenant_id, client_key)`.
   - `drug_key text not null` FK `ipd.drug(key)`; `frequency_key text not null` check in (`od`, `bd`, `tds`, `q8`).
   - `status text not null default 'PENDING'` check in (`PENDING`, `ACTIVE`, `REJECTED`, `STOPPED`).
   - `ordered_by uuid not null` (user id, IPD actor convention); `ordered_at timestamptz not null default now()`.
   - `override_reason_key text null` check in (`BENEFIT_MONITORED`, `SPECIALIST_ADVISED`, `ALLERGY_HISTORY_CHECKED`, `CARDIOLOGY_STENT`); `warnings text[] not null default '{}'`, at most 10 elements, each 1 to 300 characters; check `(cardinality(warnings) > 0) = (override_reason_key is not null)`.
   - `verified_by uuid null`, `verified_at timestamptz null`; `rejected_by uuid null`, `rejected_at timestamptz null`, `reject_reason_key text null` check in (`ALLERGY_CONFLICT`, `INTERACTION_RISK`, `WRONG_DOSE`, `DUPLICATE_THERAPY`); `stopped_by uuid null`, `stopped_at timestamptz null`; `version bigint not null default 0`.
   - Status checks: PENDING → verified, rejected and stopped fields all null; ACTIVE → verified pair set, rejected and stopped null; REJECTED → rejected triple set, verified and stopped null; STOPPED → stopped pair set, rejected null (verified pair set or null). Pairs are set together. `verified_by is null or verified_by <> ordered_by`. Time order: each stamp `>= ordered_at`, `stopped_at >= verified_at` when both.
   - Partial unique index `(tenant_id, admission_id, drug_key) where status in ('PENDING', 'ACTIVE')` (duplicate backstop).
   - Indexes: `(tenant_id, admission_id, ordered_at, id)` (EMR list, live orders); partial `(tenant_id, ordered_at, id) where status = 'PENDING'` (queue, keyset and count).
   - Grants to `sahaj_app`: `select`; `insert (admission_id, client_key, drug_key, frequency_key, ordered_by, override_reason_key, warnings)` (so status is always PENDING and `ordered_at` is the database's); `update (status, verified_by, rejected_by, reject_reason_key, stopped_by, version)` only. No delete.
   - Triggers: deny delete and truncate (`platform.deny_audit_mutation()`); a BEFORE UPDATE guard (SECURITY INVOKER, fixed `search_path`, revoked from public and `sahaj_app`, SQLSTATE 23514 with "medicine order" in the message) that allows only PENDING→ACTIVE, PENDING→REJECTED, PENDING→STOPPED, ACTIVE→STOPPED; keeps `id, tenant_id, admission_id, client_key, drug_key, frequency_key, ordered_by, ordered_at, override_reason_key, warnings` unchanged; stamps `verified_at`, `rejected_at`, `stopped_at` with `clock_timestamp()` on the matching transition and refuses any other change of them; requires `version = old.version + 1` on every status change; refuses any transition when the admission is not ADMITTED (exists read of `ipd.admission`). Optional BEFORE INSERT check that the admission is ADMITTED (db-engineer's choice).
   - Comments on table and columns (lifecycle, actor convention, demo catalogue).
4. **`ipd.clinical_event`**: no change (kinds view, deny, order, stop, verify exist; text limit 300 is enough). Restate the volume estimate (D12).
5. **Retention**: never deleted (D12). Tenant scope: `medicine_order` tenant-scoped with FORCE RLS; the two reference tables global and read-only (RLS forced with a non-tenant policy, B7).
6. **`platform.staff_pin`**: the human chose D6 option B (DEC-017). Its data list is in **ADR-010 "Data needed"**, as a separate platform migration after this one (for example `V2026_11_28_1100__platform_staff_pin.sql`).

## Instructions

**java-developer:**
1. `ipd.domain.MedicineSafetyCheck`, `DrugCatalogue` (immutable, loaded at start-up, fail-fast class check), `Frequency`, `OverrideReason`, `RejectReason` enums, with the unit tests of D2.
2. `patient.api.PatientClinicalPort` and its implementation in `patient` (D9).
3. `MedicineOrderService` (order, stop, verify, reject) and the EMR and queue reads per D4, D5, D8, D10: check orders, explicit READ COMMITTED, role re-read, bare-row lock, replay rules, 23505 never swallowed, `asOf` from `clock_timestamp()`.
4. `platform.api.StaffPinPort` and its implementation per ADR-010 (D6 decided; no stub needed any more). Unit tests of the verify use a test double.
5. Templates of D7 in the existing clinical event template class; audit of D7; access rows of D7.
6. `AdmissionDetail.canOpenEmr`; `NavigationPolicy` `ipd` for PHARMACIST (D11 decided, DEC-017).

**angular-developer:** EMR button and sheet (identity bar, allergy chip by `allergyStatus`, Diagnosis and Day strip, Medicines on chart with pills and Stop, order form with the hard-stop notice, warnings, reason select and "Order with this reason", Clear, client key lifecycle of D4); owner's Pharmacist checks tile and panel on the Safety tab, tab count `open + waiting`; PHARMACIST page with the Safety tab only (if D11 accepted); Verify sheet (signed-in verifier, PIN field cleared on every failure) and Reject sheet; 60-second summary poll; every button disabled while its request runs.

## Questions for the human

- **Q1 (security, OQ-31-3): ANSWERED** (DEC-017): B personal staff PIN; design in ADR-010, whose own questions (set and reset texts, checklist line, third-lock rule, pepper rotation) are open there.
- **Q2 (roles and menu, OQ-31-2): ANSWERED** (DEC-017): OWNER and PHARMACIST; PHARMACIST gets the Safety-only page.
- **Q3 (clinical, blocking for go-live, OQ-31-1):** who approves the 14 medicines, their classes, the high-alert flags, the 3 interactions and the 4 override reasons; and whether a shared catalogue replaces `ipd.drug` and `opd.medicine` later.
- **Q4 (roles):** verify and reject are PHARMACIST and OWNER only (story: also a DOCTOR who did not order). With no pharmacist, the owner's own orders can never be verified. Allow DOCTOR to verify another person's order (then DOCTOR needs the panel too)?
- **Q5 (roles, OQ-31-8 and ADR-007 Q11):** OWNER orders and verifies without any doctor profile check (the platform has none for owners); VISITING_DOCTOR has no access.
- **Q6 (Read-only):** in a Read-only clinic, stop and reject stay allowed and the EMR opens; order and verify are refused (story AC13 says every write is refused).
- **Q7 (API shape):** BLOCKED and NEEDS_REASON are 200 answers with an outcome, not the story's 409 `order-blocked` and `order-needs-reason`, so the allergy text never travels in a problem detail.
- **Q8 (new rule and texts):** verification is refused with 409 `order-checks-changed` when the findings changed since the pharmacist's screen loaded, and an override is asked again when the warnings changed since the doctor saw them. Not in the mockup.
- **Q9 (toasts):** "It is now on the MAR." and "Remaining doses are removed from the MAR." are kept from the mockup although the MAR is SAH-32. Recommend releasing SAH-31 and SAH-32 together; otherwise change the texts.
- **Q10 (privacy): RE-OPENED AND DECIDED** (security review S3, DEC-017 "Review decisions", 6 Nov 2026): loading `GET /pharmacist-checks` writes one record-access row per distinct patient in the page; the counts-only summary writes none (D7, B3).
- **Q11 (clinical):** orders are left as they are at discharge (no automatic stop); medicine reconciliation at discharge is a later story.
- **Q12 (clinical, OQ-31-4 and OQ-31-6):** keyword allergy matching (list extended after security review S1, D2 and B8; still a go-live blocker until clinical sign-off and structured allergies); empty allergy field only warns.
- **Q13 (contract wording):** the texts marked "contract wording" in `docs/api/ipd.yaml` (403, 409 and 400 details, " · rejected: <reason>", "Show more orders", the deny event allergy template).
- **Q14 (approvals):** no `stories` or `contract` line for SAH-31 in `docs/approvals.md`.

### Open after the reviews (6 Nov 2026; for the human, nothing decided)

- **Q15 (security review S6, go-live blocker): may an OWNER who is not a registered doctor place medicine orders?** Today any OWNER orders (D1 "OWNER orders as a doctor"; Q5; ADR-007 Q11), because the platform has no doctor profile for owners.
  - (a) OWNER may order only with a doctor profile and a registration number (as DOCTOR): needs a `platform` change so an OWNER can carry a doctor profile, the role re-read in ipd to check it, `canOrder` false with a new lock text for an OWNER without one.
  - (b) A clinic setting "Owner is a registered doctor", set by the owner in clinic setup: simpler, but self-declared and not tied to a registration number.
  - (c) Leave as now (any OWNER orders); record it as an accepted risk.
  - (a) and (b) change who may order and add screens or settings the mockup does not show; either needs the contract and the mockup deviations re-approved.
- **Q16 (security review S2(c)): clear allergy and chronic-condition text already stored in `platform.audit_event`.** The fix pass stops new `PATIENT_REGISTERED/UPDATED/MERGED` audit rows from holding the texts (they hold `allergyRecorded` / `chronicRecorded` flags) and `AuditSanitizer` redacts allergy, chronic, diagnosis, complaint, clinical and history keys for every entity (S2 a, b). Rows written before the fix still hold the texts. `audit_event` is append-only (triggers refuse update and delete), so clearing them is a destructive change to existing rows. No live data exists yet.
  - (a) A one-off, human-approved migration as `sahaj_migrator` that replaces the two texts in existing `PATIENT_*` rows with the flags (it must lift the append-only guard for that statement only, inside the migration, and record that it did).
  - (b) Leave existing rows; recreate `sahaj_dev` and `sahaj_test` before go-live (no live data), and write that down as the go-live step.
  - (c) Leave existing rows permanently and record the exception.
- **Q17 (raised by the architect in this pass): the first page of the queue carried by the verify and reject answers.** `PharmacistActionResult.checks` shows the same names, UHID and allergy texts as `GET /pharmacist-checks`, but writes no record-access row in this version (the review decision named only the list load). (a) Write the same rows (one per distinct patient of that page) in the verify or reject transaction, which also makes a replayed verify or reject write rows; (b) leave as now (the action itself is audited); (c) drop the page from the answers and let the client call `GET /pharmacist-checks` (a contract change).
- **Q18 (raised by the architect, with the merge rule of B9): a combined allergy or chronic text longer than 400 characters.** Combining two texts of up to 400 characters each can exceed the column limit, and an allergy must never be cut silently. (a) Raise the limit of the merged field only (db and patient contract change); (b) refuse the merge with a validation answer asking the user to shorten one text first (a new 400 answer on `POST /patients/merge`, a contract change); (c) keep the kept record's text and list the other in the merge audit flags for a person to fix (loses the allergy from the chart; not recommended). Until decided, the java-developer must not truncate and reports in the handoff what the code does in that case.
