# Security review · SAH-14 to SAH-18

Reviewer: security-reviewer (read-only), 5 Nov 2026. Verdict: **PASS for these stories** (no Critical or High in the diff). One High outside the diff blocks any non-local deployment.

Passed: RLS enabled and forced on all 6 new tables with tenant policies and minimal grants; tenant_id never from the request; consent and record_access append-only (grants and triggers); @PreAuthorize on every endpoint matching the contracts; IDOR checks on path ids; JdbcClient named parameters (one fixed switch for status SQL); input allow-lists and length checks; no patient data in logs or audit rows; no XSS sinks; no secrets in code.

| # | Sev | Finding | Fix |
|---|---|---|---|
| High (outside diff) | OTP dev-bypass `application-dev.yml:21-24` plus default profile `dev` (`application.yml:21`): a forgotten profile accepts any 6-digit code for any mobile | Default profile must not be dev; startup fails if dev-bypass is true outside profile dev; flag from env var defaulting to false |
| M1 | Follow-up list returns names and mobiles to BILLING, PHARMACIST, COUNSELLOR who cannot read patients | Check menu permission server-side or drop mobile for roles outside PatientRoles.READ (human to choose) |
| M2 | Follow-up list unbounded, N+1 lookups | Cap/paging, batch lookups (in progress) |
| M3 | Double-booking race on appointments | Advisory transaction lock before occupancy check plus parallel IT |
| L1 | Consent GET open to roles that cannot read patients | Use PatientRoles.READ or record decision |
| L2 | Story AC4 wording ("staff who can edit patients") vs contract (NURSE allowed) | Human to choose |
| L3 | Append-only tables accept app-chosen timestamps/actors | Column-level insert grants (new migration) |
| L4 | Revenue checks owner from token only | Add OwnerGuard.requireOwner |
| L5 | Access log covers only patient detail GET | Note in DPDP notes; extend when case paper views arrive |
| L6 | deny_audit_mutation message names the wrong table | Use tg_table_schema/name (new migration) |
| L7 | validate.mjs has no secret-scan gate (DEC-003 approved) | Humans own scripts/ |

Not checked: dependencies, tests, JWT/tenant-binding chain, AppointmentService code itself, frontend guards in detail, older code.
