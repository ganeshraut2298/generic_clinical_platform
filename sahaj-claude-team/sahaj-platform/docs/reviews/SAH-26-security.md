# Security review · SAH-26 (IPD bed transfer and transfer rules)

Reviewer: security-reviewer (read-only), 5 Nov 2026. Saved by the tech lead. Tests not executed by the reviewer. No new dependencies.

**Verdict: PASS.** No Critical or High. 1 Medium, 2 Low, 6 Info.

| # | Sev | Where | Finding | Fix |
|---|---|---|---|---|
| M1 | Medium | migration V2026_11_17_1000:69; JdbcAdmissionRepository:63-67,126-128 | `grant update (bed_id) on ipd.admission` has no database guard. sahaj_app can change bed_id on ANY admission in its clinic (including DISCHARGED) without the service; no unique index for "one ADMITTED admission per bed"; UPDATE_BED has no status filter and no row-count check. A server flaw could put two patients in one bed, change a discharged admission's bed, re-price days, with no transfer row or audit. Tenant isolation still holds. | New forward migration: `create unique index ux_admission_tenant_bed_admitted on ipd.admission (tenant_id, bed_id) where status = 'ADMITTED'` (check existing data) plus trigger `ipd.guard_admission_bed_change()` before update of bed_id: refuse (42501) unless old and new status are ADMITTED and a bed_transfer row with matching from/to bed and `transferred_at = now()` exists in the same transaction. Java: `and status = 'ADMITTED'` in UPDATE_BED, throw IllegalStateException unless update count is 1 (also markOccupied/updateStatus in the transfer). QA: BedTransferTableIT direct update refused, second ADMITTED on same bed refused. |
| L1 | Low | IpdText.java:13-26; IpdService.java:95-98; JdbcWardRepository.java:42-46 | U+200C/U+200D allowed anywhere; ward uniqueness uses `lower(name)`, so `IC<ZWJ>U` and `ICU` both accepted (look-alike wards). | Accept ZWNJ/ZWJ only directly after a virama (IDNA CONTEXTJ: U+094D, 09CD, 0A4D, 0ACD, 0B4D, 0BCD, 0C4D, 0CCD, 0D4D, 0DCA), never first, last or doubled; NFC-normalise before check and store; uniqueness ignores joiners (`lower(translate(name, U&'\200C\200D', ''))`) with a forward-migration unique index; tests. |
| L2 | Low | ProblemExceptionHandler.java:174,298 | Refused access (403 owner-approval-needed, role 403, stale owner) is not logged (ASVS V7.2, CERT-In). | `log.warn("Access refused type={} tenantId={} userId={} handler={}", ...)` in both handlers; no message text or ids from the path. Optional rate-limited ACCESS_REFUSED audit row. |
| I1 | Info | BedTransferService.java:104-106 | Only the owner path re-reads membership; a FRONT_DESK/NURSE demoted under 15 min ago can still move patients. | Accept, or re-read membership for all IPD writes through a platform port later. |
| I2 | Info | BedTransferService.java:174 | Audit includes ipNumber (not in AC 9). Pseudonymous. | Add to AC 9 / contract or drop. |
| I3 | Info | ipd.bed_transfer.reason | Health category stored, not shown, not sent by the UI. | Record purpose in the DPDP register; keep out of exports. |
| I4 | Info | JdbcBedRepository.java:59-61 | HAS_HISTORY ignores transfer rows; FKs still block delete (409). | Add the bed_transfer exists clause. |
| I5 | Info | IpdService.java:88 | `trim()` before `hasControlOrBidi` (inconsistent with Javadoc). | Check raw text first, then strip. |
| I6 | Info | SAH-27 | Transfer re-checks ADMITTED under the row lock. | Discharge must take `wards.lockTenant` first (one lock order). |

## Passed
Authorisation per endpoint (annotation, service role re-check, OwnerGuard re-read), tenant isolation and RLS, IDOR (same 404), module/read-only checks, input validation and allow-lists, append-only proofs, races and lock order, audit/log privacy, record_access, errors, frontend (no innerHTML, storage, console).

## Done well
Layered defence on the transfer; old category copied onto the transfer row; SAH-24/25 items closed; thorough real-database tests.
