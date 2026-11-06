# Security review · SAH-24 (bed board, ready/block) and SAH-25 (running bill, extra charges, deposit alert)

Reviewer: security-reviewer (read-only), 5 Nov 2026. Branch feature/SAH-13-appointments-workspace, uncommitted. Saved by the tech lead. Tests were not executed by the reviewer.

**Verdict: PASS.** Nothing Critical, High or Medium. 6 Low, 5 Info.

| # | Sev | Where | Finding | Fix |
|---|---|---|---|---|
| L1 | Low | BedBoardService:91-93; AdmissionService:352-357 | The three new writes (ready, block, add charge) are guarded only by `@PreAuthorize(ADMITTERS)`; the service uses `role` only for UI flags. A future caller without the annotation, or a role stale up to 15 min after demotion, could add bill rows that cannot be removed. | Re-check role in `change(...)` and `addCharge(...)` before `gate.check` (AccessDeniedException); unit test with a DOCTOR role. Optional role re-read like OwnerGuard. |
| L2 | Low | AdmissionService:375 (also :334 deposits) | POST charges/deposits return the full Detail (name, reason, mobile for OWNER/FRONT_DESK) without a `patient.record_access` row; a nurse could read the reason via a cheap POST without appearing in the access log. | Call `patientAccess.recordOpened(...)` in `addCharge` and `addDeposit`, or return the bill only. |
| L3 | Low | V2026_11_14_1000 migration lines 17, 38 | `qty` is in the insert grant, check allows 1-50 per row; the 50-per-service cap is enforced only in Java. | New forward migration: revoke insert (qty) or check qty = 1; remove `qty, 1` from JdbcChargeRepository.INSERT. |
| L4 | Low | IpdText:13-20 | Control/bidi check misses U+061C, U+200B-200D, U+2028/2029, U+2060-2064, U+FEFF. | Add them, with unit cases. |
| L5 | Low | patient/api/PatientLookupPort:17 | `PatientSummary` default toString prints name and mobile. | Override toString to ids and merged flag only. |
| L6 | Low | AdmissionChargesIT (gap) | No test proving extra JSON fields (unitPaise, qty, tenantId, addedBy) are ignored. | IT posting those fields; assert unit_paise 30000 for ecg, qty 1, caller's tenant and added_by. |
| I1 | Info | BedBoardService | Board shows names, day, doctor and the Deposit-low flag to OWNER/FRONT_DESK/NURSE/DOCTOR without a record-access row (matches AC8). | Record as deliberate in the DPDP register. |
| I2 | Info | IpdBillingSupport:367-387 | Test helper turns off FORCE RLS in one transaction; confirmed test-only, nothing in src/main. Residual: runtime holds migrator credentials because Flyway runs at start-up. | Production: run Flyway as a separate job, `spring.flyway.enabled=false` in prod. |
| I3 | Info | RunningBill | Bill is computed on read with current rates, so an owner rate change re-prices running bills. By design (AC1). | Discharge/final-bill story must snapshot rates. |
| I4 | Info | AdmissionService:370-374 | Audit carries serviceKey and basePaise, not the multiplied billed unit. | Add `billedUnitPaise` with I3. |
| I5 | Info | BedBoardService:81-89 | `ready` does not check for an ADMITTED admission on the bed (cannot happen today). | Discharge/transfer stories must keep "bed leaves occ only after the admission moves". |

## Passed
Authorisation per endpoint matches the contract (ITs for every role, 401); tenant isolation (token tenant, RLS enable+force, composite FK, no update/delete policy, cross-tenant ITs); IDOR (404 for unknown and other-clinic ids); module entitlement checks first on every method; input validation (serviceKey enum, 50 cap under advisory lock, parallel IT); money tampering (client sends only serviceKey, prices from server catalog, integer paise); append-only at three layers; lock order and races; audit/log privacy; frontend has no innerHTML, bypassSecurityTrust, storage or console use; no new dependencies.

## Done well
Single RunningBill for all views; SAH-22/23 review items closed; layered append-only defence with proofs; careful test helper; real, meaningful test coverage.
