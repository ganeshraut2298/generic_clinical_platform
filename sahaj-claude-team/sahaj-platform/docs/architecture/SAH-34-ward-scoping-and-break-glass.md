# SAH-34 · IPD ward scoping and break-glass: design note

Story: SAH-34 (`docs/stories/SAH-34.md`, `docs/requirements/SAH-34.md`). Decisions: DEC-031, ADR-016 (options), ADR-017 (decided build).
Mockup screens: IPD → Admitted (`pctog data-k="mine"`, `pcscope`, `sfbg`, `sfbgok`, `sfFlags`); IPD → Safety (`ipSafety`, tile "Emergency access", `bgRow`, `sfbgrev`, `kic` `bg`). **There is no nurse-to-ward screen in the mockup.** The 34A endpoints carry `x-needs-human`.
Module: ipd. Contract: `docs/api/ipd.yaml` (additive). `docs/api/platform.yaml` is unchanged: the nurse list comes from `platform.api.TeamMemberPort.findActiveMembers` inside ipd.

Part labels: **[34A]** ward-assignment data and the switch (the screen waits for the mockup) · **[34B]** server scope enforcement · **[34C]** list and board scope fields and screen · **[34E]** break-glass grant · **[34F]** owner review.

---

## 1. The guard [34B]

### 1.1 Scope object
`ipd.application.PatientScope` (a Java 17 record):
- `kind`: ALL, WARDS, OWN or NONE;
- `wardIds`: a set of UUIDs;
- `ownMembershipId`;
- `grants`: a map from admission id to `expiresAt`;
- `membershipId` and `role`, from the re-read.

`PatientScopeService.build(tenantId, userId)` runs once per request, inside the request's transaction, right after `TeamMemberPort.findActiveByUserId` (ADR-013 D3).

| Role (token and membership both) | `ipd.config.nurse_ward_scope` false or no row | true |
|---|---|---|
| OWNER, DOCTOR, FRONT_DESK, BILLING, PHARMACIST | ALL | ALL |
| NURSE | ALL | WARDS(live `ipd.ward_assignment` rows of her membership); NONE when there are none |
| VISITING_DOCTOR | OWN(membership); every IPD endpoint still answers 403 `forbidden` (34D) | same |

How the inputs are read:
- **Grants:** read only when the kind is not ALL. `select admission_id, expires_at from ipd.emergency_access_grant g join ipd.admission a … where g.membership_id = :m and g.role = :role and g.expires_at > clock_timestamp() and a.status = 'ADMITTED'`.
- **Failure:** if any read fails, the request fails with a 500 problem and a WARN log line (tenantId, requestId). It never falls back to ALL. A NURSE with no live ward gets NONE: an empty list and `outside-scope` on every by-id route.
- **Changes apply at once:** the role, membership, assignments, switch and grants are all read again on every request.

### 1.2 Guard methods (`ipd.application.ScopeGuard`)

| Method | Used by | Rule |
|---|---|---|
| `listPredicate(scope)` | set reads of lists, the board, Safety and clinical events | ALL: none. WARDS: `b.ward_id = any(:wards) or a.id = any(:grants)`. NONE: `a.id = any(:grants)`. OWN: `a.doctor_membership_id = :own or a.id = any(:grants)` |
| `requireAdmission(scope, facts)` | by-id reads, no lock | facts = ward of `admission.bed_id` (the last bed for DISCHARGED), the admitting doctor and the status. A grant counts only while ADMITTED |
| `requireAdmissionLocked(scope, admissionId)` | every write, after `select … for no key update` of the admission row | reads the ward of the locked admission's bed. For a grant, it reruns the grant query against `clock_timestamp()` under the lock (not the cached map), so a transfer, discharge or expiry that races the write is decided on locked data |
| `resolveOrder(orderId)` / `resolveEscalation(escalationId)` | `/medicine-orders/{id}/…`, `/escalations/{id}/…` | finds the admission id first. Unknown or another clinic's id: 404 (RLS) |

Rules that apply to every guarded call:
- **Refusal:** `OutsideScopeException` is mapped by `ProblemExceptionHandler` to 403 `https://sahaj.in/problems/outside-scope`, title "Outside your scope", detail "This patient is not in your wards.". There is no patient data in the body.
- **Logging:** one INFO line with tenantId, requestId, route name, userId and admissionId. Nothing else is written: no clinical event, audit row or record-access row (OQ-34-24).
- **Answer order on every guarded endpoint:**
  1. role 403 `forbidden`, then module 403 `module-off` (the endpoint's existing order of these two is kept);
  2. Read-only 409, where it already comes before the transaction;
  3. body 400;
  4. membership re-read 403;
  5. 404 for the admission, order, escalation or patient;
  6. **403 `outside-scope`**;
  7. the endpoint's own state checks (`not-admitted`, version, `dose-recorded`, …).

  So `outside-scope` is answered only to a role that may use the endpoint (AC 14).
- **Answers after a move:** a write that is in scope when it starts (for example, a nurse transfers her patient to another ward) returns its normal answer. The next request for that patient is refused.

### 1.3 Endpoint coverage list
Each operation that is guarded carries `x-scope-guard` in `ipd.yaml`. In Java, each IPD controller method carries `@ScopeGuarded(kind)` or `@ScopeExempt("reason")`.

| Endpoint | Kind | Effect |
|---|---|---|
| GET `/admissions` | LIST | rows filtered; `scope`, `mine`, `emergencyAccess` added (section 2) |
| GET `/board` | LIST (mask) | every bed and its status kept; occupied-bed patient fields null and `outsideScope` true when out of scope |
| GET `/safety`, `/safety/summary` | LIST | escalations, medicine round, counts and change token on the scoped set; `scope` added; owner's `emergencyAccess` |
| GET `/clinical-events` | LIST | NURSE: own entries plus entries of admissions in scope (grants included). OWNER, DOCTOR: all |
| GET `/admissions/{id}`, `/nursing`, `/emr`, `/mar`, `/discharge` | ADMISSION | refuse |
| POST `/admissions/{id}/vitals`, `/medicine-orders`, `/transfer`, `/discharge`, `/deposits`, `/charges`; PUT `/admissions/{id}/discharge/signoff` | ADMISSION (locked) | refuse after the lock |
| POST `/medicine-orders/{id}/stop`, `/medicine-orders/{id}/doses` | ORDER (locked) | refuse after the lock |
| POST `/escalations/{id}/acknowledge`, `/respond`, `/call-senior` | ESCALATION (locked) | refuse after the lock |
| GET `/emergency-access/patients`, POST `/emergency-access` | GRANT | callers with kind ≠ ALL only (section 4) |
| GET `/emergency-access`, POST `/emergency-access/{id}/review` | GRANT (owner) | OWNER only; no patient scope applies |

Note on the story's list: it names "`/deposits` and `/charges` (read)". There are no such GET endpoints: deposits and the bill come in `AdmissionDetail`, which is guarded.

Endpoints whose roles are always ALL today (orders, stop, acknowledge, respond, signoff, discharge POST, deposits) still call the guard. The guard is a no-op for ALL, and the uniform call keeps 34D and later roles safe.

**Exempt** (`@ScopeExempt`, the written list of AC 21 and AC 22):
- `/layout`, `/wards`, `/wards/{id}/rooms`, `/beds`, `/beds/{id}`, `/room-level`, `/categories/{key}`, `/charges` (PUT), `/admission-rules`, `/admission-rules/confirm`, `/transfer-rules`, `/packages`, `/charge-services`, `/discharge-settings`, `/emergency-settings`, `/card-setup`, `/card-setup/draft`, `/card-setup/publish`: configuration.
- `/admission-options`, POST `/admissions`: admitting a patient. The 201 answer is the detail the caller just typed.
- `/beds/{id}/ready`, `/beds/{id}/block`: bed state, no patient data.
- `/emergency`, `/emergency/summary`, `/emergency/cases`, `/emergency/cases/{id}/*`: not admitted, no ward.
- `/pharmacist-checks`, `/pharmacist-checks/summary`, `/medicine-orders/{id}/verify`, `/reject`: OWNER and PHARMACIST, scope ALL.
- `/ward-assignments`, `/ward-assignments/{wardId}`, `/ward-scope`: OWNER configuration [34A].

The second-nurse witness of a high-alert dose (SAH-38) is not scoped. Only the caller of `/doses` is guarded (OQ-34-25).

### 1.4 How a test proves that no endpoint skips the guard
`IpdScopeCoverageTest` (java-developer, unit level) has three parts:
1. **Marker scan.** Every handler of `RequestMappingHandlerMapping` under `/api/v1/ipd/**` has exactly one of `@ScopeGuarded` and `@ScopeExempt`. A handler with neither, or both, fails the test.
2. **Contract cross-check.** Parse `docs/api/ipd.yaml` (SnakeYAML on the test classpath). The set of operations with `x-scope-guard` must equal the set of `@ScopeGuarded` handlers (method and path), and every contract operation must have a handler. So the contract and the code cannot drift.
3. **Behaviour.** For every `@ScopeGuarded` handler of kind ADMISSION, ORDER or ESCALATION, a fixture table keyed by (method, path) gives a valid request. A missing fixture fails the test. The real application service runs with:
   - a `PatientScopeService` stub that answers WARDS({other ward});
   - repository fakes that return an admitted admission in a different ward;
   - Mockito write ports (vitals, orders, doses, events, audit, record access).

   The test expects `OutsideScopeException`, and `verifyNoInteractions` on every write port. For the LIST kinds, a parameterised test asserts that the SQL builder receives the predicate. The qa-engineer adds real-database ITs for the LIST reads (section 9).

## 2. Scope fields on the Admitted list [34B, 34C]
`AdmissionList.scope` (`AdmissionScope`) is present for every role and holds no patient data:

| Field | Meaning |
|---|---|
| `kind` | ALL or WARDS in phase 1 (OWN reserved for 34D, NONE is reported as WARDS with `hasWard` false). The chip and "Emergency access" are shown when it is not ALL |
| `visible` | N, the rows in `items` |
| `admitted` | M, every ADMITTED admission of the clinic (one `count(*)`, no rows) |
| `hasWard` | NURSE only (absent for other roles): she has at least one live ward. Drives "My patients" for a nurse (AC 32) and the zero-ward texts (AC 29) |
| `canOpenEmergencyAccess` | kind not ALL, role NURSE (token and membership), IPD Trial, On or Read-only |

Per row (`AdmittedPatientRow`):
- `mine` (boolean). For OWNER and DOCTOR: the admitting doctor is the caller's membership. For NURSE: the bed's ward is one of her live wards (with the switch on or off). It is absent for FRONT_DESK and no membership id is sent. It is a filter only (AC 33).
- `emergencyAccess` (`{ expiresAt }`), present only on a row the caller sees only through her own active grant. It drives the grey flag "Emergency access" and the tooltip "Emergency access until <time> IST" (OQ-34-33).

Counts:
- **On the scoped set**, so the tile and the rows agree: `depositAlerts`, `goingHomeToday` and `safety.counts`.
- **Clinic-wide** (bed facts, not patient data): `bedsTotal`, `bedsOccupied`, `bedsAvailable`, `dischargedToday` and `emergency`.

The tab number for a scoped user is N (OQ-34-28). `wards` keeps every ward (OQ-34-22).

## 3. Scope fields on the Safety board [34B, 34C, 34F]
- **`SafetyBoard.scope`** (`SafetyScope`) is sent for view NURSE only, and is absent for view DOCTOR. Fields: `kind`, `visible`, `admitted` and `hasWard`. They feed the notes "This board shows your wards only (N of M patients)." and "No wards are assigned to you, so this board shows no patients." (AC 37, OQ-34-27). `yourPatients` = `visible`. The tile's small text is "In your wards" when the kind is WARDS and "All admitted patients" when it is ALL.
- **Counts on the scoped set:** escalations, `medicineRound`, `counts` (open, pastTarget, dosesDue, dosesLate, changeToken) and the banner, all per caller (AC 15). OWNER and DOCTOR are always ALL (AC 18).
- **`SafetyBoard.otherWardsPastTarget`** — `x-needs-human`, optional (AC 20, OQ-34-26):
  - **Sent:** for a NURSE with kind WARDS or NONE only; absent for everyone else.
  - **Computation:** one `count(*)` over OPEN escalations of ADMITTED admissions with pastTarget on the database clock, minus the in-scope ones, in the same snapshot. No row is loaded into the answer.
  - **Not counted into** the tile, the tab or the rows.
  - **If the human says no**, the field and the red notice are dropped and nothing else changes.
- **Owner's emergency-access counts** (OWNER only, absent for others):
  - `SafetyCounts.emergencyAccessWaiting` (also in `/safety/summary` and `AdmissionList.safety.counts`). The tab number for OWNER = open + dosesLate + pharmacist waiting + emergencyAccessWaiting (OQ-34-30).
  - The `changeToken` gains one line per unreviewed grant, `bg:<grant id>`, so a new grant or a review triggers a reload.
- **`SafetyBoard.emergencyAccess`** (`EmergencyAccessReviewPage`), OWNER only:
  - **Contents:** `waiting`, the first page of up to 25 rows (oldest `opened_at` first, then id) and `nextCursor`. "Show 25 more" calls `GET /emergency-access?cursor=…`.
  - **Source:** the tile, the rows and the count come from one read in the board's REPEATABLE READ transaction.

## 4. Break-glass grant [34E]
- **Endpoints:** GET `/emergency-access/patients` (picker), POST `/emergency-access` (open).
- **Who:** token role NURSE and membership role NURSE, with kind ≠ ALL. A NURSE with the switch off, and every other role, get 403 `forbidden` "Emergency access is only for staff who see some wards." VISITING_DOCTOR keeps the IPD-wide 403.
- **Picker:** every ADMITTED admission outside the caller's scope (not in her wards, and no active grant of hers). Each item holds `admissionId`, `patientName`, `bedCode` and `uhid` only, ordered by bed code (code-point order; the mockup gives no order). The answer also carries the three reasons with their labels. It writes nothing (no record-access row, no audit) and is never logged. An empty list shows "You can already see every admitted patient." Allowed in Trial, On and Read-only.
- **Open:** the body is `{ admissionId, reasonKey, confirmed }`.
  - **Validation (400):** `reasonKey` missing or unknown gives field reasonKey "Choose a reason for emergency access."; `confirmed` not `true` gives field confirmed "Tick the box to confirm this access is recorded.".
  - **Transaction (READ COMMITTED):**
    1. membership re-read;
    2. scope build (kind ALL: 403 `forbidden`);
    3. admission row lock;
    4. not found, another clinic's, or not ADMITTED: 404 not-found;
    5. in her wards, or an active grant of hers (same membership, `expires_at > clock_timestamp()`): 409 `already-in-scope` "This patient is already in your list.", no row;
    6. insert the grant (the trigger stamps `opened_at` and `expires_at`);
    7. the bg event, the record-access row and the audit (ADR-017 D6).

    Any failure rolls back all of it.
  - **Answer:** 201 `EmergencyAccessGrant` (id, admissionId, patientName, bedCode, reasonKey, reasonLabel, openedAt, expiresAt). The toast is built from `patientName`: "Emergency access to <name> for 30 minutes. The owner will see this on the Safety board."
- **30-minute database clock:**
  - `opened_at` and `expires_at` are set only by the BEFORE INSERT trigger with `clock_timestamp()` and `+ interval '30 minutes'`. A check keeps `expires_at = opened_at + interval '30 minutes'`.
  - The app cannot insert or update these columns.
  - Expiry is checked on every request (section 1.1) and again under the lock for writes.
  - There is no extension and no end-now. A new grant after expiry is a new row with a new review line.
- **Three fixed reasons:**

  | Key | Label |
  |---|---|
  | `DETERIORATING` | Patient deteriorating, rapid response |
  | `COVERING_BREAK` | Covering for a colleague on break |
  | `DUTY_DOCTOR_ASKED` | Asked to help by the duty doctor |

  A database check constraint holds the keys. The labels live in code (`EmergencyAccessReason`) and are used in the bg text and the owner's panel.
- **Effect:** the grant adds the admission to the caller's scope for her own role's endpoints only (D-1). It gives no other role's action and nothing in the Patients module.

## 5. Owner review [34F]
- **GET `/emergency-access`** (OWNER only): one page of unreviewed grants, oldest first. `limit` is 1 to 25 (default 25); `cursor` is opaque keyset (opened_at, id). It writes nothing and is allowed in Read-only.
- **Row** (`EmergencyAccessReviewRow`):
  - ids: grantId, admissionId;
  - names read at display time: `staffName` (ActorDirectoryPort, "Team member" when not found) and `patientName` (patient.api, "Patient" when not found);
  - `bedCode` = the bed **at the time of the grant** (`grant.bed_id`);
  - `reasonKey`, `reasonLabel`, `openedAt`, `expiresAt`;
  - `admissionStatus` (ADMITTED or DISCHARGED).
- **Display:**
  - "<staffName> opened <patientName> · bed <bedCode>";
  - small line "<reasonLabel> · <time> · outside their wards". The time is "11:05 AM" today or "24 Oct, 11:05 AM" on an earlier day (IST).
- **POST `/emergency-access/{id}/review`** (no body):
  - **Order:** role OWNER, else 403 `forbidden`; module 403; bad id 400. Then, in the transaction:
    1. membership re-read (OWNER);
    2. grant not found or another clinic's: 404;
    3. `grant.user_id` = the caller: 403 `forbidden` "You cannot review your own emergency access.";
    4. guarded update `where reviewed_at is null`: zero rows gives 409 `already-reviewed` "Already reviewed.";
    5. bg event "Reviewed emergency access" with `grant_id` and the admission;
    6. audit `IPD_EMERGENCY_ACCESS_REVIEWED`.
  - **Answer:** 200 `EmergencyAccessReviewResult` (grantId, reviewedAt, waiting). Toast: "Marked as reviewed".
  - Allowed in Read-only (OQ-34-29).
- **The audit-trail entry:** `ClinicalEvent.grantStaffName` (optional) is the grantee's name, read at display time from `grant_id`. The client shows "Reviewed emergency access by <grantStaffName>", which is the mockup's text, and no name is stored.

## 6. Ward assignment and the clinic switch [34A data part; screen waits for the mockup]
All three endpoints are OWNER only (token and membership), carry `x-needs-human` in the contract, and are not called by any screen until the human adds the screen.

| Endpoint | Purpose |
|---|---|
| GET `/ward-assignments` | `wardScope` (switch on/off), every ward with its live nurses (membershipId, name), `nurses` = active NURSE members with their ward ids (from `TeamMemberPort.findActiveMembers`, active NURSE only), and `nursesWithoutWard` (a count). Read-only; allowed in Read-only |
| PUT `/ward-assignments/{wardId}` | Body `{ membershipIds: [...] }` (0 to 100, unique). This is the new live set of nurses for the ward. Under the clinic IPD advisory lock, rows not in the set get `removed_at` and `removed_by`, and new ones are inserted. Same set: 200, nothing written. Each id must be an active NURSE member: 400 field membershipIds "Choose nurses from the list.". Unknown or other-clinic ward: 404. Read-only: 409. Audit `IPD_WARD_ASSIGNMENT_CHANGED` (wardId, added, removed) when anything changed |
| PUT `/ward-scope` | Body `{ on: boolean }`. On while an active NURSE has no live ward: 409 `nurse-without-ward` "Assign a ward to every nurse before you switch this on." (OQ-34-21). Upserts `ipd.config.nurse_ward_scope`. Audit `IPD_WARD_SCOPE_CHANGED` (before, after) when it changed. Read-only: 409 `ipd-readonly`. Applies at the next request of every user (OQ-34-5) |

Removing a nurse's last ward while the switch is on is allowed. She then sees no patient (fail closed) and the answer's `nursesWithoutWard` shows it. A ward cannot be deleted in phase 1, so ward removal needs no rule. An assignment of a membership that later becomes inactive is ignored, because the scope is built from the caller's own active membership.

Until the screen exists, tests (and a developer in `sahaj_dev`) create assignments through these endpoints or directly in SQL (OQ-34-35).

## 7. Stored shape for the db-engineer (additive only)

### 7.1 `ipd.ward_assignment` [34A]
| Column | Type | Rule |
|---|---|---|
| id | uuid pk default gen_random_uuid() | |
| tenant_id | uuid not null default platform.current_tenant() | FK `platform.tenant` |
| ward_id | uuid not null | composite FK `(tenant_id, ward_id)` → `ipd.ward (tenant_id, id)` |
| membership_id | uuid not null | no FK (cross-module); validated through `TeamMemberPort` on write |
| assigned_at | timestamptz not null default clock_timestamp() | not insertable by the app |
| assigned_by | uuid not null | user id |
| removed_at | timestamptz null | set once, by trigger to clock_timestamp() |
| removed_by | uuid null | `ck (removed_at is null) = (removed_by is null)` |
| version | bigint not null default 0 | |

- **Indexes:** unique `ux_ward_assignment_live (tenant_id, ward_id, membership_id) where removed_at is null`; `ix_ward_assignment_member_live (tenant_id, membership_id) include (ward_id) where removed_at is null`.
- **RLS:** ENABLE and FORCE, with select, insert and update policies on `platform.current_tenant()`. No delete policy.
- **Grants:** `select`, `insert (ward_id, membership_id, assigned_by)`, `update (removed_at, removed_by, version)`.
- **Triggers:** a guard trigger allows only `removed_*` (from null, once) and `version` to change. `platform.deny_audit_mutation()` blocks delete and truncate.
- **Retention:** never deleted. Expected about 12,000 live rows platform-wide.

### 7.2 `ipd.config.nurse_ward_scope` [34A]
- `alter table ipd.config add column nurse_ward_scope boolean not null default false`.
- A constant default touches no existing value and needs no table rewrite on PostgreSQL 16, so every clinic starts off.
- The table-level grants already cover it.

### 7.3 `ipd.emergency_access_grant` [34E, 34F]
| Column | Type | Rule |
|---|---|---|
| id | uuid pk default gen_random_uuid() | `uq (tenant_id, id)` for the event FK |
| tenant_id | uuid not null default platform.current_tenant() | FK `platform.tenant` |
| admission_id | uuid not null | composite FK → `ipd.admission (tenant_id, id)` |
| bed_id | uuid not null | composite FK → `ipd.bed (tenant_id, id)`; bed at the time of the grant |
| user_id | uuid not null | grantee user (no cross-module FK) |
| membership_id | uuid not null | grantee membership (no FK) |
| role | text not null | `ck role in ('NURSE','VISITING_DOCTOR')` |
| reason_key | text not null | `ck in ('DETERIORATING','COVERING_BREAK','DUTY_DOCTOR_ASKED')` |
| opened_at | timestamptz not null | set by the BEFORE INSERT trigger to clock_timestamp() (this is the row's created_at; user_id is its created_by) |
| expires_at | timestamptz not null | trigger: opened_at + 30 min; `ck expires_at = opened_at + interval '30 minutes'` |
| reviewed_by | uuid null | `ck reviewed_by is null or reviewed_by <> user_id` |
| reviewed_at | timestamptz null | set by trigger to clock_timestamp() when reviewed_by is set; `ck (reviewed_by is null) = (reviewed_at is null)` |
| version | bigint not null default 0 | |

- **Indexes:** `ix_eag_member_expires (tenant_id, membership_id, expires_at desc) include (admission_id, role)` (scope build and the locked recheck); `ix_eag_unreviewed (tenant_id, opened_at, id) where reviewed_at is null` (count, panel, keyset).
- **RLS:** ENABLE and FORCE, with select, insert and update policies on the tenant. No delete policy.
- **Grants:** `select`, `insert (admission_id, bed_id, user_id, membership_id, role, reason_key)`, `update (reviewed_by, version)`.
- **Guard trigger on update:**
  - only `reviewed_by`, `reviewed_at` and `version` may change;
  - only from null, once;
  - `reviewed_by` must not be `user_id`;
  - `reviewed_at := clock_timestamp()`.

  `platform.deny_audit_mutation()` blocks delete and truncate.
- **Content:** no name, note or free text.
- **Retention:** never deleted in phase 1; part of the in-patient access trail. Expected about 10 grants a day per hospital, about 365,000 a year.

### 7.4 `ipd.clinical_event.grant_id` [34E, 34F]
- `alter table ipd.clinical_event add column grant_id uuid null`, plus a composite FK `(tenant_id, grant_id)` → `ipd.emergency_access_grant (tenant_id, id)` match simple.
- `ck_clinical_event_grant check (grant_id is null or kind = 'bg')`. Every existing row has `grant_id` null, so the check validates without change.
- Add `grant_id` to the column-level insert grant.
- The append-only triggers stay as they are.
- No new index: the event page joins the grant by primary key.
- Nothing existing is updated or dropped.

### 7.5 Plans to record (db-engineer; as `sahaj_app` with `app.tenant_id` set, 300 admitted, 26 wards)
- R1 of `GET /admissions` with the ward and grant predicate.
- The escalation, dose and clinical-event reads with the predicate.
- The `admitted` count.
- The other-wards past-target count.
- The unreviewed count and its first page.
- The scope build probes (assignment, grants).

## 8. Effect on SAH-28 to SAH-40
| Story | Change |
|---|---|
| SAH-28 Emergency queue | None (exempt) |
| SAH-29 Packages | None |
| SAH-30 Nursing, vitals | `/nursing` guarded; `/vitals` guarded after the lock |
| SAH-31 Orders, EMR, pharmacist | `/emr`, `/medicine-orders`, `/stop` guarded; pharmacist endpoints exempt |
| SAH-32 MAR | `/mar` guarded; `/doses` guarded after the lock; witness unscoped |
| SAH-33 Safety board | Board, summary, banner, counts, change token, call-senior, acknowledge, respond and the nurse's audit trail scoped. Tile text "In your wards" becomes true. bg entries with `grant_id`. The owner's tile, panel and count are added. The OQ-33-2 text in the contract is replaced |
| SAH-34 | This work |
| SAH-35 Medicine round | Round and dose counts per caller's scope; invariants per caller |
| SAH-36 Admitted cards | Rows filtered; `scope`, `mine`, `emergencyAccess`; abilities stay list-level (every listed row is in scope; ADR-013 D5 holds) |
| SAH-37 Card setup | None (C1). The card draws the Emergency access flag from the row, not from the setup |
| SAH-38 PIN hardening | None (witness unscoped) |
| SAH-39 Home background | None |
| SAH-40 Refused refresh | Extended: a 403 `outside-scope` on a sheet clears that patient's clinical data, closes the sheet and reloads the list (AC 38, 39); not a page lock |
| SAH-23 to SAH-27 | `/admissions/{id}`, deposits, charges, transfer, discharge (GET, POST, signoff) guarded; board masked |

## 9. Tests to plan (qa-engineer)
- Tenant isolation of assignments and grants.
- A nurse with no ward sees nothing and gets `outside-scope`.
- A transfer racing a vitals save, and an expiry racing a dose (under the lock).
- Two owners reviewing at once (one 200, one 409).
- Two taps on Open (one row).
- The whole IPD suite as NURSE with the switch off (unchanged).
- OWNER and DOCTOR boards unchanged with an escalation in an unassigned ward (AC 18).
- The raw JSON of a refused answer holds no name, UHID, bed or ward.
- The trigger refuses an update of `expires_at`, a second review, self-review and delete.
- Logs hold no names.

## 10. Open points for the human
1. **OQ-34-26:** `otherWardsPastTarget` and its red notice. They are optional and marked `x-needs-human`, and can be dropped without changing anything else.
2. **34A screen:** the three ward-assignment endpoints wait for the human's mockup screen. Their names, texts and Read-only rule are contract proposals.
3. **ADR number clash:** two existing files are numbered ADR-016 (`ADR-016-ipd-ward-scoping-and-break-glass.md`, `ADR-016-admin-directory-and-modules.md`). Neither was renamed. This record uses ADR-017.
4. **Review without `{version}`:** the guarded update replaces the BA's proposal (ADR-017 D5).
5. **List counts:** `depositAlerts` and `goingHomeToday` follow the scoped set; bed counts and `dischargedToday` stay clinic-wide. This is an architect choice where the story is silent.
6. **Answer after a move:** a write that moves the patient out of scope (transfer) still returns the full answer to the caller who made it.
7. **Picker order:** bed code. The mockup gives no order.
8. **Inherited open points:** OQ-34-15 (typed values after a refused save), OQ-34-31 (go-live conditions) and the clinical sign-off (OQ-30-6) remain with the human.
