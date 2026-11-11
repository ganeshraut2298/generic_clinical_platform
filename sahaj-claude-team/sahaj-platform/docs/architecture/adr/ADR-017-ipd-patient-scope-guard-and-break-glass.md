# ADR-017 · IPD patient scope guard and break-glass emergency access (decided path of ADR-016)

Story: SAH-34 (34A data part, 34B, 34C, 34E, 34F)
Mockup screens: IPD → Admitted (`pctog data-k="mine"`, `pcscope`, `sfbg`, `sfbgok`, `sfFlags`), IPD → Safety (`ipSafety`, `bgRow`, `sfbgrev`, `kic` `bg`)
Module: ipd. Reads `platform.api` (`TeamMemberPort`, `ActorDirectoryPort`, entitlement), writes `platform.api.AuditLog` and `patient.api.PatientAccessPort`. No new module, port, event, library or service.
Status: PROPOSED for the `contract` approval (9 Nov 2026). Decisions taken by the human in DEC-031; this ADR records how they are built.
Supersedes nothing. ADR-016 (`ADR-016-ipd-ward-scoping-and-break-glass.md`) stays as the options record; its section 10 recommendations A1, M2, B1, C1, E2, S1, D-1a, D-3a, D-4a, D-5a, D-6 (keep until reviewed), R1 and P1 are the chosen ones.
Design note with the endpoint list and the stored shape: `docs/architecture/SAH-34-ward-scoping-and-break-glass.md`.

## Context
Every IPD endpoint checks role, membership and tenant, but not which patient. DEC-031 chose a standing nurse-to-ward table (A1), a per-clinic switch off by default (M2), the admitting doctor as the own-patient rule (B1), scope fixed in code (C1), 403 `outside-scope` (E2), one application guard (S1), and break-glass as the mockup with three deviations (fixed 30 minutes, the toast "The owner will see this on the Safety board.", unreviewed grants kept after discharge).

## Decision

### D1 · One scope object per request
`ipd.application.PatientScope` is built once per request, inside the request's transaction, right after the membership re-read (ADR-013 D3):

| Caller (token role and membership role) | Switch off | Switch on |
|---|---|---|
| OWNER, DOCTOR, FRONT_DESK, BILLING, PHARMACIST | ALL | ALL |
| NURSE | ALL | WARDS(live assignments of her membership), NONE when she has no ward |
| VISITING_DOCTOR | OWN(her membership), but every IPD endpoint still answers 403 `forbidden` until 34D | same |

The scope also holds the caller's active grants, as a set of admission ids, from one read: `membership_id = mine`, `role = my membership role`, `expires_at > clock_timestamp()`, admission ADMITTED. Inputs come only from the signed-in user, `ipd.config.nurse_ward_scope`, `ipd.ward_assignment` and `ipd.emergency_access_grant`. No request field, header or query takes part. If the setting, the assignments or the grants cannot be read, the request fails (500 problem, WARN log with tenantId and requestId) and never falls back to ALL. A missing `ipd.config` row means the switch is off.

**No active membership (rule, 10 Nov 2026).** Token role OWNER, DOCTOR, FRONT_DESK, BILLING or PHARMACIST with no active membership keeps scope ALL, as before SAH-34; the endpoint's existing membership re-read decides as before. Token role NURSE or VISITING_DOCTOR with no active membership gets 403 `forbidden` and never ALL. The scope therefore never widens beyond the pre-SAH-34 behaviour.

### D2 · One guard; lists filter, details and writes refuse
`ipd.application.ScopeGuard` is the only class that decides scope:
- `admissionPredicate(scope)` adds one predicate to the set-based reads: `bed.ward_id = any(:wards) or admission.id = any(:grantAdmissionIds)`, or `doctor_membership_id = :own` for OWN.
- `requireAdmission(scope, facts)` is used by the by-id reads. `facts` = ward of the admission's bed (the last bed for a DISCHARGED admission), admitting doctor and status. A grant counts only while the admission is ADMITTED.
- `requireAdmissionLocked(scope, admissionId)` is used by writes, after the admission row lock (ADR-006 D4) or, for deposit, charge, transfer and discharge, under the clinic IPD advisory lock they already hold. It reads the bed's ward and, for a grant, runs the grant query again against `clock_timestamp()` under the lock, so a transfer, discharge or expiry that races the write is decided on locked data. Both locks are sufficient because transfer and discharge, the only changes of bed or status, take the advisory lock and then the row lock (design note 1.6). **Binding rule:** any later operation that changes an admission's bed, ward or status takes the clinic IPD advisory lock, then the admission row lock.
- Order and escalation routes find the admission of the order or escalation first. A row of another clinic is 404 (RLS), never `outside-scope`.
- Answer order: the existing role 403 `forbidden`, module 403 `module-off`, Read-only 409 (where it comes before the transaction today), membership re-read 403, then 404 not-found (admission, order, escalation, patient record), then **403 `outside-scope`**, then the endpoint's own state checks (`not-admitted`, version, …). The `outside-scope` body never holds a name, bed, ward or UHID.
- A refusal writes nothing: no row, no clinical event, no audit, no record-access row. It writes one INFO log line: tenantId, requestId, route name, userId, admissionId.

### D3 · Coverage is proven by a test, not by review
Each IPD controller method carries `@ScopeGuarded(LIST | ADMISSION | ORDER | ESCALATION | GRANT)` or `@ScopeExempt("<reason>")`. `IpdScopeCoverageTest`:
1. scans `RequestMappingHandlerMapping` for every `/api/v1/ipd/**` handler and fails if a handler has neither marker, or both;
2. reads `docs/api/ipd.yaml` (SnakeYAML, already on the test classpath through Spring Boot) and fails if an operation has `x-scope-guard` and the handler is exempt, or the other way round;
3. for every handler marked ADMISSION, ORDER or ESCALATION, runs the real application service with a scope that excludes the target and a fixture request from a table keyed by method and path. A missing fixture fails the test. The test expects `OutsideScopeException`, and the write ports (Mockito) must not be called.
A new endpoint therefore cannot ship unless it is guarded or written down as exempt, and a guarded one cannot skip the call.

**As built, accepted (10 Nov 2026).** Point 3 is built as a table rather than a fixture-driven run. `REFUSAL_TESTS` maps the 17 guarded by-id endpoints (ADMISSION 12, ORDER 2, ESCALATION 3) to named service tests.
- The table keys must equal the scanned set of those handlers, both ways.
- Each named class and method must exist.
- Each named test runs the real service with an out-of-scope admission and asserts `OutsideScopeException` with no write-port call.
- A fourth check pins the LIST handlers to the five scoped reads.

This keeps the guarantee: a new guarded by-id endpoint without a refusal test fails the build.

**Required addition:** the reflective check also asserts that the named method is a `@Test` (or `@ParameterizedTest`) and is not `@Disabled`.

Design note sections 1.4 to 1.6 record the in-memory list filter, which replaces `admissionPredicate` on every list except `GET /clinical-events`, and the lock rule.

### D4 · Break-glass grant
One row per grant in `ipd.emergency_access_grant` (design note, section 7):
- Stamped by the database: a BEFORE INSERT trigger sets `opened_at = clock_timestamp()` and `expires_at = opened_at + interval '30 minutes'`. The app has no insert privilege on either column.
- Three reason keys: `DETERIORATING`, `COVERING_BREAK`, `DUTY_DOCTOR_ASKED`.
- Bound to the user, the membership, the role and the bed at the time of the grant.
- Append-only, except that `reviewed_by` and `reviewed_at` are set once, never by the grantee and never cleared. A trigger enforces this, and delete and truncate are denied.
- No extension and no early end. Discharge, a role change or an inactive membership end its effect at the next request (D1 read).
- When the same membership already has an active grant on the admission, or the patient is in her wards, the answer is 409 `already-in-scope` and no row is written. Every grant insert takes the admission row lock first, so two taps make one grant.

### D5 · Owner review
- Only OWNER, never the grantee, once per grant. `update … set reviewed_by = :me, version = version + 1 where id = :id and reviewed_at is null`. Zero rows: not found gives 404, already reviewed gives 409 `already-reviewed`.
- The request has no body: the guarded update replaces the BA's `{version}`, because the review is the only change a grant can ever get.
- Allowed in IPD Read-only.
- The panel, the tile and the owner's Safety tab term all come from one count of unreviewed grants, read in the board's transaction. Order: oldest first, 25 rows a page.

### D6 · What is written
- **Grant, in one transaction:**
  - clinical event `bg` "Emergency access opened · <reason label>", with `grant_id` set;
  - one `patient.record_access` row through `PatientAccessPort.recordOpened` (R1);
  - audit `IPD_EMERGENCY_ACCESS_OPENED` (grantId, admissionId, reasonKey, expiresAt).
- **Picker read (added 10 Nov 2026, SAH-34 security finding 1):** one audit event per successful `GET /emergency-access/patients`: `IPD_EMERGENCY_ACCESS_PICKER_VIEWED`, entity type `IPD_EMERGENCY_ACCESS`, entity id null, after `{"count": N}` only. No patient id or name. It is written in the read's transaction, and if it fails the request fails. No record-access row.
- **Review:**
  - clinical event `bg` "Reviewed emergency access", with `grant_id` set. The trail shows "by <grantee name>", read at display time through `grant_id`. No name is ever stored;
  - audit `IPD_EMERGENCY_ACCESS_REVIEWED` (grantId, admissionId).
- **Assignment change:** audit `IPD_WARD_ASSIGNMENT_CHANGED` (wardId, added and removed membership ids).
- **Switch change:** audit `IPD_WARD_SCOPE_CHANGED` (before and after).
- **Audit entity types (confirmed 10 Nov 2026):**
  - `IPD_EMERGENCY_ACCESS` (entity id = grant id) for opened and reviewed; entity id null for the picker read;
  - `IPD_WARD_ASSIGNMENT` (entity id = ward id);
  - `IPD_CONFIG` (entity id null, as the other IPD settings) for the switch.

### D7 · Ward assignment and the switch (data part of 34A)
- `ipd.ward_assignment` holds standing assignments: many nurses per ward, and a nurse can have many wards. Rows are ended, never deleted.
- `ipd.config.nurse_ward_scope` is the switch, default false.
- OWNER-only endpoints `GET /ward-assignments`, `PUT /ward-assignments/{wardId}` and `PUT /ward-scope`. They are in the contract with `x-needs-human`, because the screen is not in the mockup yet (DEC-031 item 2). They may be built and tested now (OQ-34-35). No screen calls them until the human adds it.
- Switching on is refused with 409 `nurse-without-ward` while an active NURSE has no live ward (OQ-34-21). The check runs on every `on: true` request, also when the switch is already on. A repeated "on" in that state is 409, the switch stays on and nothing is written.
- Unknown fields in the three new request bodies (and a body that is not a JSON object) answer 400 `malformed-request`. `bad-request` stays for a path id that is not a UUID.

### D8 · Not in phase 1
- RLS enforcement of the scope (S2).
- Scope on the Emergency queue, the Patients search, the pharmacist check and the configuration endpoints.
- Notifications. The toast never claims an alert.
- The Roles and access panel (34G).
- Visiting doctor access (34D).

## Options
Weighed in ADR-016 sections 3 to 8. This ADR adds only build-level choices:
- **Coverage:** marker annotations with a contract cross-check (chosen), against an AOP aspect on every service (hidden control flow, and it cannot see the lock order) or code review only (fails silently).
- **Grant duplicate check:** the admission lock (chosen), against a partial unique index. An index cannot use `expires_at > now()` because `now()` is not immutable.
- **Review concurrency:** a guarded update with no body (chosen), against `{version}`. The version adds no information because the review is the only possible change.
- **Who opened, in the review entry:** a nullable `grant_id` on `ipd.clinical_event` (chosen), against storing the staff name (forbidden by SAH-33 AC 17) or showing no name (fails story AC 70).

## Consequences
- **Per request:**
  - one index probe on assignments, for a NURSE with the switch on only;
  - one on grants, for a scoped caller only;
  - for a scoped caller, one narrow read of the admitted set (about 300 rows at most), which filters the lists in memory; `GET /clinical-events` instead adds one SQL predicate. If one clinic exceeds about 2,000 admitted, or p95 of a scoped list exceeds 300 ms, the lists move to a SQL predicate (design note 1.5).
  ALL callers pay one `ipd.config` read, which is already made on most paths.
- Existing clinics see no change at deploy. With the switch off, every existing suite must pass unchanged as NURSE.
- Additive schema only: two new tables, one column on `ipd.config`, and one nullable column with a check on `ipd.clinical_event`. Nothing on existing rows is changed or dropped.
- The clinical sign-off owner (OQ-30-6) must read ADR-016 section 8 before any clinic switches the scope on. That go-live condition is the human's.
