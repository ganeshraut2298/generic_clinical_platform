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
- **No active membership (rule, 10 Nov 2026):** the scope never widens access beyond what the caller had before SAH-34.
  - Token role OWNER, DOCTOR, FRONT_DESK, BILLING or PHARMACIST with no active membership: scope ALL, as before SAH-34. The endpoint's own membership re-read (ADR-013 D3) still answers 403 where it already exists; SAH-34 adds no check and removes none.
  - Token role NURSE or VISITING_DOCTOR with no active membership: 403 `forbidden`. It never falls back to ALL, because for these roles ALL would be wider than the switch allows.
  - An assignment or a grant of a membership that has become inactive counts for nothing: the scope is built only from the caller's own active membership.
  - Accepted: it is fail-closed for the narrowed roles and unchanged for the others. Endpoints that do not re-read the membership today are a pre-existing ADR-013 matter, not changed by this story.

### 1.2 Guard methods (`ipd.application.ScopeGuard`)

| Method | Used by | Rule |
|---|---|---|
| `visibleAdmissionIds(scope)` (as built; section 1.5) | the Admitted list, the board mask, Safety, summary and the medicine round | ALL: null (no filter). Otherwise one narrow read of the clinic's ADMITTED set (admission id, bed's ward, admitting doctor), kept in memory when the ward is in `wards`, the doctor is `own` (OWN), or the id is in `grants`. NONE keeps grants only |
| SQL predicate | `GET /clinical-events` only (history, unbounded) | WARDS: `b.ward_id = any(:wards) or a.id = any(:grants)`, with `a.status = 'ADMITTED'` kept; NONE: grants only; plus the nurse's own entries. No wards and no live grants: the actor-only predicate (section 1.7) |
| `requireAdmission(scope, facts)` | by-id reads, no lock | facts = ward of `admission.bed_id` (the last bed for DISCHARGED), the admitting doctor and the status. A grant counts only while ADMITTED |
| `requireAdmissionLocked(scope, admissionId)` | every write, after `select … for no key update` of the admission row, or under the clinic IPD advisory lock (deposit, charge, transfer, discharge; section 1.6) | reads the ward of the admission's bed in a new statement after the lock. For a grant, it reruns the grant query against `clock_timestamp()` (not the cached map), so a transfer, discharge or expiry that races the write is decided on data that cannot change under it |
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

  **Role and module order (security review finding 9, decided 10 Nov 2026).** AC 14 lists IPD Off before the role check. The contract and the code keep each endpoint's existing order of these two, and that order stays:
  - GET `/emergency-access/patients` and POST `/emergency-access` check the role first, then module Off. The contract says so and the code does so (`EmergencyAccessService.picker` and `open`). The other SAH-34 endpoints and most IPD endpoints since SAH-23 also check the role first. A few older ones (GET `/admissions/{id}/discharge`, PUT `/discharge/signoff`) check module Off first.
  - Both answers are fixed-text 403s with no patient data, so the order reveals nothing either way. The part of AC 14 that protects data is met: 404 before `outside-scope`, and `outside-scope` only to an allowed role.
  - Moving module Off first on every IPD endpoint would change about 60 approved handlers and their tests for no gain. **No code change.**
  - The business-analyst should change AC 14's wording (the human approves it with `stories`), for example: "the role check (403 `forbidden`) and the IPD Off check (403 `module-off`) come first, in the endpoint's existing order; then id not found or another clinic 404; then out of scope 403 `outside-scope`."
- **Answers after a move:** a write that is in scope when it starts (for example, a nurse transfers her patient to another ward) returns its normal answer. The next request for that patient is refused.

### 1.3 Endpoint coverage list
Each operation that is guarded carries `x-scope-guard` in `ipd.yaml`. In Java, each IPD controller method carries `@ScopeGuarded(kind)` or `@ScopeExempt("reason")`.

| Endpoint | Kind | Effect |
|---|---|---|
| GET `/admissions` | LIST | rows filtered; `scope`, `mine`, `emergencyAccess` added (section 2) |
| GET `/board` | LIST (mask) | every bed and its status kept; occupied-bed patient fields null and `outsideScope` true when out of scope (absent, never false, otherwise); the tile shows the code, category and "Occupied" only and does nothing on click (aria-disabled) |
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

**As built and accepted (10 Nov 2026).** Part 3 is a table, not a fixture-driven run. `IpdScopeCoverageTest.REFUSAL_TESTS` maps each of the 17 guarded by-id endpoints (ADMISSION 12, ORDER 2, ESCALATION 3) to one named service test:
- The table's keys must equal the set of `@ScopeGuarded` handlers of kind ADMISSION, ORDER or ESCALATION. So a new guarded by-id endpoint without a named test fails, and so does a table entry with no handler.
- Each named test class and method must exist (reflection).
- Each named test runs the real application service with an out-of-scope admission. It expects `OutsideScopeException` and no call to the write ports (and, for reads, no record-access row).
- A fourth check pins the LIST handlers to the five scoped reads.

Together with part 1 (markers) and part 2 (contract cross-check) this gives the guarantee that D3 asks for: no endpoint ships unguarded or unlisted, and every guarded by-id endpoint has a refusal test.

**One small addition is required:** the reflective check must also assert that the named method carries `@Test` (or `@ParameterizedTest`) and is not `@Disabled`. Otherwise a renamed helper or a disabled test would still satisfy the table.

### 1.5 How the lists are filtered (as built, accepted 10 Nov 2026)
The lists are not filtered by a SQL join predicate. They are filtered in memory from one narrow read of the admitted set, `ScopeGuard.visibleAdmissionIds`:
- **The read:** admission id, the bed's ward and the admitting doctor of every ADMITTED admission of the clinic. The set is kept in memory as described in 1.2.
- **Where it applies:** the Admitted list, the board mask, the Safety board and its summary, and the medicine round. Each of these then keeps only the rows, escalations, orders and doses of the visible ids.
- **Same snapshot:** the read runs inside the list's own REPEATABLE READ transaction, so the rows, the counts, `scope.visible` and the change token agree. This is a rule: a later change must not move the read out of that transaction.
- **ALL callers:** `visibleAdmissionIds` returns null, so the read is skipped. OWNER, DOCTOR, FRONT_DESK, BILLING, PHARMACIST and a NURSE with the switch off pay nothing.
- **Clinical events:** `GET /clinical-events` is the exception. It reads history, which grows without bound, so it keeps the SQL predicate in its keyset query, with `a.status = 'ADMITTED'`.

Effect on the db-engineer's expected plans (`docs/database/SAH-34.md` section 10):
- **R1, R2, R3 and C2 do not apply as written.** No statement carries `ward_id = any(:wards) or a.id = any(:grants)`. Each of these reads is the unscoped read that existed before SAH-34, followed by a filter in memory. For C2, the other-wards count is the clinic count minus the in-scope count, in the same snapshot.
- **What does apply:**
  - R4, the clinical events query with the predicate and `a.status = 'ADMITTED'`;
  - S1 and S2, the scope probes;
  - C1, the `admitted` count;
  - U1 to U3, the unreviewed grants.
- **New plan to record instead of R1 to R3:** the narrow admitted-set read. As measured (docs/database/SAH-34.md section 10, row N): a bitmap scan of the partial unique index `ux_admission_tenant_bed_admitted` (`(tenant_id, bed_id) where status = 'ADMITTED'`) hash-joined to the beds, about 300 rows, 0.5 to 1.2 ms. (Corrected 10 Nov 2026; this line first named `ix_admission_tenant_status_admitted_at`, which is also correct for the read but is not the one the planner picks. No index change.)
- **Who records the plans:** the qa-engineer, with the real plans in section 9.

Scale assessment:
- **Size of the read:** at 100 hospitals with a few hundred admitted patients each, the extra read for a scoped nurse is at most one bed-limit's worth of rows (about 300, 3 uuids each, about 15 KB). It is one index range scan plus bed probes, under 5 ms. It runs once per scoped request, after the same unscoped reads every ALL caller already makes.
- **No worse than today:** the in-memory filter never costs more than the OWNER's view of the same screen. The admitted set is bounded by the bed count, not by history.
- **No backlog item now.** A SQL predicate on these lists is not needed.
- **Revisit trigger, recorded here:** move to the SQL predicate if either of these holds:
  - one clinic's ADMITTED set exceeds about 2,000 rows;
  - p95 of `GET /admissions` or `GET /safety` for a scoped NURSE exceeds 300 ms in a real hospital.

  At that point the db-engineer's R1 to R3 become the plan.

### 1.6 Locks of the scoped writes (as built, confirmed 10 Nov 2026)
There are two lock families:
- **Admission row lock:** vitals, medicine orders, stop, doses, discharge sign-off and the escalation actions take `select … for no key update` of the admission row.
- **Clinic IPD advisory lock:** deposit, charge, transfer and discharge take this lock first, and run `requireAdmissionLocked` under it.

Both are sufficient. Why:
1. **Only transfer and discharge change scope facts.** An admission's scope facts are its bed (so its ward) and its status. Only transfer and discharge change them, and both take the clinic IPD advisory lock first and then the admission row lock (BedTransferService, DischargeService).
   - A writer under the advisory lock is serialised with every transfer and discharge.
   - A writer under the row lock is serialised with the row-lock step of every transfer and discharge.
   - Either way, the fresh READ COMMITTED statement that reads the ward after the lock sees committed facts that cannot change before the writer commits.
2. **Other changes also take the advisory lock.** Ward assignments (`PUT /ward-assignments/{wardId}`), the switch (`PUT /ward-scope`) and bed configuration also take the clinic IPD advisory lock, so they are serialised with deposit, charge, transfer and discharge. A row-lock writer is not serialised with an assignment or switch change. That is accepted: such a change applies "at the next request" (OQ-34-5), as the design already says.
3. **No lock can hold back an expiry.** It is a time, not a row change. The rule is the same for both families: the grant is re-read with `expires_at > clock_timestamp()` after the lock, at the moment of the check. A write that passes the check just before minute 30 commits a few milliseconds later. This is the same "decided when the write starts" rule as section 1.2 (answers after a move), and is accepted.
4. **A new grant only widens scope.** It takes the admission row lock and never refuses another writer. A review does not change scope.

**Rule for later stories (binding):** any new operation that changes an admission's bed, ward or status must take the clinic IPD advisory lock and then the admission row lock, in that order. Both lock families rely on this. No statement changes a bed's ward today (`ipd.bed` updates touch only the category and the status). A later one that does must take the same two locks.

### 1.7 Clinical-event page for a scoped nurse with no wards and no grants (security finding 5, QA F-2; decided 10 Nov 2026)
`GET /clinical-events` for a scoped NURSE today always adds `JdbcClinicalEventRepository.SCOPED_NURSE`:
`and (e.actor = :nurse or (a.status = 'ADMITTED' and (b.ward_id = any(cast(:wards as uuid[])) or a.id = any(cast(:grants as uuid[])))))`.
The planner then checks every scanned event against the left-joined admissions and beds. A new nurse with no ward (the fail-closed state) has few entries of her own, so the scan reads the whole log.

**Change (java-developer, Java only, no schema change):**
- Add a fixed fragment `static final String ACTOR_ONLY = " and e.actor = :nurse\n";`.
- In `findPageInScope`, when `wardIds` is empty **and** `grantAdmissionIds` is empty, build `PAGE_HEAD + ACTOR_ONLY + (after == null ? "" : AFTER) + PAGE_TAIL` and bind only `tenantId`, `nurse`, `limit` and, for a later page, `afterAt` and `afterSeq`. Do not bind `wards` or `grants`.
- Otherwise keep the current `SCOPED_NURSE` statement unchanged.
- `PAGE_HEAD` (the three left joins for `patient_id`, `bed_code` and `grant_user_id`), the keyset and the order stay the same.
- `grantAdmissionIds` is the scope's live-grant set, as passed today (grants of ADMITTED admissions with `expires_at > clock_timestamp()`). "No grants" means that set is empty.

**The rows must be identical.** With both arrays empty, `any('{}')` is false for every row, so the inner `and` is false (also when `a.status` is null from the left join: null and false is false). The predicate becomes `e.actor = :nurse or false`, which is `e.actor = :nurse` for every row. A null actor is excluded both ways. Same rows, same order, same page size, same `nextCursor`, same display columns.

**Tests:**
- java-developer, unit: the empty-scope call sends the SQL with `ACTOR_ONLY` and without `any(`; a non-empty ward or grant set sends `SCOPED_NURSE`.
- qa-engineer, IT: on one data set (own entries, entries of in-scope, out-of-scope and discharged admissions, a `bg` entry), the old statement and the new path return identical rows over at least two keyset pages. Then add R4d (nurse with no ward, no grant and no own entries) to `ScopePlansIT`.
- The index `ix_clinical_event_tenant_actor_at` stays a proposal with its build trigger (docs/database/SAH-34.md 10.4). Security asks that it be decided before any clinic switches the scope on in production. That decision is the human's, at the go-live check (OQ-34-31).

### 1.8 Discharged admissions (security finding 7; recorded as intended, 10 Nov 2026)
- A DISCHARGED admission stays readable through the by-id reads (`/admissions/{id}`, `/nursing`, `/emr`, `/mar`, `/discharge`) by a scoped NURSE whose live wards include the ward of the admission's **last bed** (`admission.bed_id`, which is kept at discharge; `ScopeGuard.ownsPlacement`).
- Nurses of every other ward are refused with `outside-scope`.
- **No grant ever counts after discharge.** A grant counts only while the admission is ADMITTED (section 1.2), so a nurse who reached the patient only through emergency access loses the record at the discharge, even within the 30 minutes.
- This is intended. The ward that cared for the patient last can finish its notes and answer questions. It is narrower than before SAH-34, when every NURSE could read every discharged admission.
- Writes to a discharged admission are still refused by the endpoint's own state check (`not-admitted`) after the scope check.

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

The Admitted tab number for a scoped user is N (OQ-34-28). `wards` keeps every ward (OQ-34-22). `scope` is always present, for every role and every kind (ALL included).

**Safety tab gate (corrected 10 Nov 2026, angular-developer open point 1):** the client shows the Safety tab when `safety` is not null and `scope.admitted` (M) is above 0. This follows the mockup's `sfTabOn`, which tests `ipAdm().length`, the clinic's admitted count. It does not use `items`, because a scoped NURSE with no ward, or with no patient in her wards, has `items` empty and would otherwise never reach the board or its scope note (AC 37). For kind ALL, `items` holds every admitted patient, so M equals `items.length`. The frontend as built (M for scoped users, `items.length` otherwise) gives the same answer and stays as it is. The earlier contract wording, "safety not null and items not empty", was wrong and is replaced.

## 3. Scope fields on the Safety board [34B, 34C, 34F]
- **`SafetyBoard.scope`** (`SafetyScope`) is sent for view NURSE only, and is absent for view DOCTOR. Fields: `kind`, `visible`, `admitted` and `hasWard`. They feed the notes "This board shows your wards only (N of M patients)." and "No wards are assigned to you, so this board shows no patients." (AC 37, OQ-34-27). `yourPatients` = `visible`. The tile's small text is "In your wards" when the kind is WARDS and "All admitted patients" when it is ALL.
- **Counts on the scoped set:** escalations, `medicineRound`, `counts` (open, pastTarget, dosesDue, dosesLate, changeToken) and the banner, all per caller (AC 15). OWNER and DOCTOR are always ALL (AC 18).
- **`SafetyBoard.otherWardsPastTarget`** — `x-needs-human`, optional (AC 20, OQ-34-26):
  - **Sent:** for a NURSE with kind WARDS or NONE only; absent for everyone else.
  - **Computation:** one `count(*)` over OPEN escalations of ADMITTED admissions with pastTarget on the database clock, minus the in-scope ones, in the same snapshot. No row is loaded into the answer.
  - **Not counted into** the tile, the tab or the rows.
  - **If the human says no**, the field and the red notice are dropped and nothing else changes.
- **Owner's emergency-access counts** (OWNER only, absent for others):
  - `SafetyCounts.emergencyAccessWaiting` (also in `/safety/summary` and `AdmissionList.safety.counts`). The Safety tab number for OWNER = open + dosesLate + pharmacist waiting + emergencyAccessWaiting (AC 71, OQ-34-30), hidden at 0; for DOCTOR and NURSE it stays open + dosesLate, on the caller's scope. The contract's `/safety/summary`, `SafetyCounts` and `AdmissionSafety` texts now say the same.
  - The `changeToken` gains **one** line when at least one grant is unreviewed: `bg:<count>:<newest grant id, lower case>`. There is no `bg` line when none is unreviewed. (Security finding 4, decided 10 Nov 2026. It replaces "one line `bg:<grant id>` per unreviewed grant", which read every unreviewed id with no limit on every reload.)
    - `count` is `emergencyAccessWaiting`, in decimal with no padding. The newest grant is `order by opened_at desc, id desc limit 1` over the unreviewed grants, a backward scan of the partial index `ix_emergency_access_grant_unreviewed` that reads one entry. The count is the existing index-only `COUNT_UNREVIEWED` (U1). Both come from the board's (or the list's) snapshot, in one statement or two in the same transaction. A `max(id)` aggregate is not used: uuid order means nothing here, and the newest row by keyset is exact.
    - **Why it detects every change:** grants are only added or reviewed. An added grant gets `opened_at` from the database clock at insert, so it becomes the newest one and the newest id changes. Reviews alone lower the count.
    - **Accepted limit:** two grants that commit out of the order of their `opened_at`, combined with a review in the same poll interval, can leave count and newest unchanged once. Then the owner's panel is stale until the next change or the next board load. The token is a reload hint; the board is always read fresh. Accepted.
    - **Compatibility:** the line goes into the same sorted set, with the same SHA-256, first 16 bytes and base64url (22 characters, the same `pattern`). The `bg:` prefix keeps it apart from the escalation and `dose:` lines. Clients compare for equality only, so the owner's Safety board, the summary poll and the Admitted list need no change. After the deploy every OWNER token differs once from the last one seen, which costs one reload. Tokens of DOCTOR and NURSE are unchanged, because they never carry a `bg` line. With no unreviewed grant, the OWNER token is the SAH-35 token, as before.
    - **Java (java-developer):**
      - Replace `WardScopeRepository.unreviewedIds` (`JdbcWardScopeRepository.UNREVIEWED_IDS`) with a method that returns `(waiting, newestId)`, for example `record UnreviewedMark(int waiting, UUID newestId)`.
      - `SafetyRows.counts(…)` takes it in place of `List<UUID>`. It adds the single line when `waiting > 0` and sets `emergencyAccessWaiting = waiting`.
      - Callers: `SafetyService` (board and summary, lines about 130 and 207) and `AdmissionService` (list, line about 303).
      - The first page of the panel (`SafetyBoard.emergencyAccess`) is unchanged: it is already limited to 25 rows.
- **`SafetyBoard.emergencyAccess`** (`EmergencyAccessReviewPage`), OWNER only:
  - **Contents:** `waiting`, the first page of up to 25 rows (oldest `opened_at` first, then id) and `nextCursor`. "Show 25 more" calls `GET /emergency-access?cursor=…`.
  - **Source:** the tile, the rows and the count come from one read in the board's REPEATABLE READ transaction.

## 4. Break-glass grant [34E]
- **Endpoints:** GET `/emergency-access/patients` (picker), POST `/emergency-access` (open).
- **Who:** token role NURSE and membership role NURSE, with kind ≠ ALL. A NURSE with the switch off, and every other role, get 403 `forbidden` "Emergency access is only for staff who see some wards." VISITING_DOCTOR keeps the IPD-wide 403.
  - **As built, confirmed 10 Nov 2026:** both endpoints are reachable by every IPD token role (`@PreAuthorize` lists OWNER, DOCTOR, VISITING_DOCTOR, NURSE, FRONT_DESK, BILLING, PHARMACIST and COUNSELLOR). The service refuses any token role but NURSE first, before the module check and before any read, with the fixed sentence above (type `forbidden`, title "Not allowed").
  - This is the least-surprising reading of the contract: one sentence for every refused role, and nothing is read or written.
  - VISITING_DOCTOR's answer is still 403 `forbidden` (34D unchanged); only the detail text is this sentence.
  - A token without an IPD role gets the generic 403 from `@PreAuthorize`.
- **Picker:** every ADMITTED admission outside the caller's scope (not in her wards, and no active grant of hers). Each item holds `admissionId`, `patientName`, `bedCode` and `uhid` only, ordered by bed code (code-point order; the mockup gives no order). The answer also carries the three reasons with their labels. It writes no record-access row and no clinical event, and the names are never logged.
  - **Audit of each read (security finding 1, decided 10 Nov 2026):** every successful read writes exactly one platform audit event: `IPD_EMERGENCY_ACCESS_PICKER_VIEWED`, entity type `IPD_EMERGENCY_ACCESS`, entity id null, with the acting user id as the audit row's actor. Before is null; after is `{"count": N}`, where N is the number of items returned, 0 included. No admission id, patient id, name, UHID or bed code goes in.
  - It is written with `auditLog.record("IPD_EMERGENCY_ACCESS_PICKER_VIEWED", ENTITY, null, null, Map.of("count", items.size()))` at the end of `EmergencyAccessService.picker`, after the list is built.
  - The transaction becomes `@Transactional(isolation = REPEATABLE_READ)`, no longer `readOnly = true`, so the insert can run. Check this first: `readOnly = true` sets the connection read-only and the audit insert would fail.
  - If the audit write fails, the request fails with a 500 and no list is returned: no directory without a trace.
  - A refused request (401, 403 `forbidden` or `module-off`) writes nothing.
  - Allowed in Read-only as before. The audit is platform data, not an IPD write.
  - Update the Javadoc of `picker` ("Writes nothing").

  An empty list shows "You can already see every admitted patient." Allowed in Trial, On and Read-only.
- **Open:** the body is `{ admissionId, reasonKey, confirmed }`.
  - **Validation (400):** `reasonKey` missing or unknown gives field reasonKey "Choose a reason for emergency access."; `confirmed` not `true` gives field confirmed "Tick the box to confirm this access is recorded.". A field that is not in the body schema (for example a duration), or a body that is not a JSON object, gives 400 `malformed-request` with no fieldErrors, and the field name is not echoed. The same rule applies to the bodies of PUT `/ward-assignments/{wardId}` and PUT `/ward-scope`. `bad-request` is only for a path id that is not a UUID.
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
| PUT `/ward-scope` | Body `{ on: boolean }`. On while an active NURSE has no live ward: 409 `nurse-without-ward` "Assign a ward to every nurse before you switch this on." (OQ-34-21). The check runs on **every** request with `on: true`, also when the switch is already on. A repeated "on" while a nurse has no ward answers 409, the switch stays on and nothing is written. `on: false` is never refused by it. Upserts `ipd.config.nurse_ward_scope`. Audit `IPD_WARD_SCOPE_CHANGED` (before, after) when it changed. Read-only: 409 `ipd-readonly`. Applies at the next request of every user (OQ-34-5) |

Removing a nurse's last ward while the switch is on is allowed. She then sees no patient (fail closed) and the answer's `nursesWithoutWard` shows it. A ward cannot be deleted in phase 1, so ward removal needs no rule. An assignment of a membership that later becomes inactive is ignored, because the scope is built from the caller's own active membership.

Until the screen exists, tests (and a developer in `sahaj_dev`) create assignments through these endpoints or directly in SQL (OQ-34-35).

### 6.1 Audit entity types (confirmed 10 Nov 2026)
Every audit row holds ids, keys and values only. None holds a name, a UHID or free text.

| Action | Audit action | Entity type | Entity id |
|---|---|---|---|
| Picker read (GET `/emergency-access/patients`; added 10 Nov 2026, security finding 1) | `IPD_EMERGENCY_ACCESS_PICKER_VIEWED` | `IPD_EMERGENCY_ACCESS` | null; after `{"count": N}` only, actor = the caller's user id |
| Grant opened | `IPD_EMERGENCY_ACCESS_OPENED` | `IPD_EMERGENCY_ACCESS` | grant id |
| Grant reviewed | `IPD_EMERGENCY_ACCESS_REVIEWED` | `IPD_EMERGENCY_ACCESS` | grant id |
| Nurses of a ward changed | `IPD_WARD_ASSIGNMENT_CHANGED` | `IPD_WARD_ASSIGNMENT` | ward id |
| Switch changed | `IPD_WARD_SCOPE_CHANGED` | `IPD_CONFIG` | null (as the other IPD settings) |

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
Updated 10 Nov 2026 to match what was built (section 1.5): the lists do not use a SQL predicate, so the predicate plans of R1, R2, R3 and C2 are not recorded. Record the narrow admitted-set read instead.
- ~~R1 of `GET /admissions` with the ward and grant predicate.~~ The narrow admitted-set read (`ScopeGuard.visibleAdmissionIds`).
- ~~The escalation and dose reads with the predicate.~~ The clinical-event read with the predicate and `a.status = 'ADMITTED'` (R4).
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
- **Added 10 Nov 2026 (security review follow-ups):**
  - **Picker audit.** One successful `GET /emergency-access/patients` by a scoped NURSE writes exactly one audit row: action `IPD_EMERGENCY_ACCESS_PICKER_VIEWED`, entity type `IPD_EMERGENCY_ACCESS`, entity id null, actor her user id, after exactly `{"count": N}` with N = `items.length`.
    - The raw audit row (before, after, entity id) holds no admission id, patient id, name, UHID or bed code.
    - An empty picker writes the row with count 0.
    - Two reads write two rows.
    - A 403 (`forbidden` for OWNER, DOCTOR or a NURSE with the switch off; `module-off`) and a 401 write none.
    - With the audit insert made to fail, the answer is 500 and no list is returned.
    - A picker read in IPD Read-only still writes it.
    - Opening a grant still writes only `IPD_EMERGENCY_ACCESS_OPENED`.
  - **Change token.** For OWNER:
    - a new grant changes the token;
    - a review changes it;
    - a review plus a new grant in one interval changes it;
    - with 0 unreviewed the token equals the DOCTOR token on the same data;
    - with 30 unreviewed grants the statement reads one index entry for the newest (plan) and the token is still 22 characters;
    - DOCTOR and NURSE tokens are unchanged by grants.
    - Update `SafetyChangeTokenIT` for the new line.
  - **Clinical events, empty scope.** The identical-rows IT and R4d of section 1.7.
  - **Discharged.** A nurse of the last bed's ward reads a DISCHARGED admission (200); a nurse of another ward gets `outside-scope`; a nurse whose only link was a grant gets `outside-scope` after the discharge, within the 30 minutes.
  - **Order.** On the picker and Open with IPD Off: OWNER gets 403 `forbidden`, NURSE gets 403 `module-off` (role first, section 1.2).

## 10. Open points for the human
1. **OQ-34-26:** `otherWardsPastTarget` and its red notice. They are optional and marked `x-needs-human`, and can be dropped without changing anything else.
2. **34A screen:** the three ward-assignment endpoints wait for the human's mockup screen. Their names, texts and Read-only rule are contract proposals.
3. **ADR number clash:** two existing files are numbered ADR-016 (`ADR-016-ipd-ward-scoping-and-break-glass.md`, `ADR-016-admin-directory-and-modules.md`). Neither was renamed. This record uses ADR-017.
4. **Review without `{version}`:** the guarded update replaces the BA's proposal (ADR-017 D5).
5. **List counts:** `depositAlerts` and `goingHomeToday` follow the scoped set; bed counts and `dischargedToday` stay clinic-wide. This is an architect choice where the story is silent.
6. **Answer after a move:** a write that moves the patient out of scope (transfer) still returns the full answer to the caller who made it.
7. **Picker order:** bed code. The mockup gives no order.
8. **Inherited open points:** OQ-34-15 (typed values after a refused save), OQ-34-31 (go-live conditions) and the clinical sign-off (OQ-30-6) remain with the human.
9. **Privacy-register entries (OQ-34-31; proposed wording for the human, security finding 1 and 8; added 10 Nov 2026).** The register is not in this folder, so the human adds these lines:
   - *Emergency access picker.* "When the clinic limits nurses to their wards, a nurse can open the Emergency access list. It shows the name, UHID and bed of every admitted patient outside her wards, before she gives a reason. It holds no clinical data. This is a directory of admitted patients for every scoped nurse, also one with no ward assigned. Each time the list is opened, Sahaj records who opened it, when, and how many patients it showed. It does not record which patients were shown."
   - *What a scoped nurse sees (N of M).* "A scoped nurse sees the clinical records of the patients in her wards (N) and is told how many patients are admitted in the clinic (M). She sees no names or details of the other M − N, except in the Emergency access list and the bed board, which shows those beds as Occupied only."
   - *Emergency access grants.* "Each emergency access lasts 30 minutes from the database clock and cannot be extended or ended early. Every grant is kept with the staff member, the patient's admission, the bed, the reason, and the open, expiry and review times. Grants are never deleted in phase 1 and stay on the owner's list until the owner reviews them. They are part of the in-patient access trail, with its record-access and audit rows." (The retention period is the human's to set; phase 1 keeps them indefinitely.)
   - *Visibility of emergency-access entries (`bg`).* "The clinical audit trail shows 'Emergency access opened' and 'Reviewed emergency access' with the staff member's name. Every nurse who can see that patient sees these entries, including colleagues in the patient's ward, and so do the owner and doctors." The other choice from security finding 8 is to hide colleagues' `bg` entries from nurses. That is a change, and it needs the human's decision.
10. **AC 14 wording (security finding 9):** the business-analyst rewords AC 14 as in section 1.2. The human approves it with the `stories` line. No code change.
