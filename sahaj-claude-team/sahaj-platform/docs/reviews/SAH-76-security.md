# SAH-76 · Security and privacy review

Date: 16 Nov 2026. Reviewer: security-reviewer (read-only). Branch: feature/SAH-39-home-kinetic-background (uncommitted; `git diff HEAD` plus untracked files). No new dependencies (pom.xml, package.json, package-lock.json unchanged vs HEAD).

**Verdict: PASS.** No Critical or High. Two Medium findings need a fix or an explicit human acceptance before go-live.

## Findings

| # | Severity | Where | Finding | Fix | Owner |
|---|---|---|---|---|---|
| M1 | Medium | `ipd/application/MedicineAdministration.java:363-390`, `DoseRules.java:190-192`, ADR-027 line 123 | One override object waives both bedside identity checks (missing wristband and missing medicine code). The "no override after a wrong-patient STOP" rule exists only in the client: after a 409 `wristband-mismatch` a direct API call with an override and no wristband records the dose on `identityConfirmed=true` alone. With `ipd.drug_code` shipped empty every Given needs an override, so in practice the wristband scan becomes optional. Detectable afterwards (audit `method=OVERRIDE`), not prevented. | (a) Split the override by what it covers (WRISTBAND, MEDICINE_CODE). (b) Server refuses an override after a wristband/medicine mismatch by the same user on the same order and slot within N minutes unless the new request scans a match. (c) Until codes are loaded, a clinic/global switch "medicine code not required" instead of a blanket override. At minimum, explicit human acceptance as part of the Q1 go-live condition. | solution-architect, java-developer, human |
| M2 | Medium | `MedicineOrderService.java:551, 586`; `PharmacistQueryService.java:84`; migration `V2026_12_15_1000…sql:473-479`; `OrderChangeService.java:149` | Separation of duties: the self-verification rule compares the caller only with `medicine_order.ordered_by` (author of version 1), not with `medicine_order_version.created_by`. OWNER is in both order and pharmacist roles, so an OWNER can change an order (e.g. 10x dose → v2 PENDING) and verify v2 alone. Same gap in the database guard and for query. Matches the contract wording ("placed by the caller"): a design gap, not drift. | Refuse verify and query when the actor equals the current version's `created_by` as well as `ordered_by` (403 `self-verification`); same check in `ipd.guard_medicine_order_version_update` by a new forward migration; contract text; unit test and IT. | solution-architect, java-developer, db-engineer |
| L1 | Low | `MedicineAdministration.java:531-551` (pre-existing, SAH-32) | Dose client-key replay does not check the caller is the recorder; another in-scope nurse with someone's `clientKey` gets 200 `replayed` and a record-access row. `DoseCorrectionService.java:166-168` does check the actor: inconsistent. | Add `stored.recordedBy().equals(actor)` to `checkRepeat`, else 409 `client-key-reused`. | java-developer |
| L2 | Low | `AuditDevice.java:42-48`, `AccessLogFilter.java:54` | `device.id` is client-supplied (validated, no injection) so it is a label, not evidence. No source IP is recorded in audit or access log (pre-existing); CERT-In expects source addresses in retained logs. | Document in ADR-027 D11 that `device.id` is advisory; confirm the reverse proxy keeps source IP with the request id, in India, 180 days. | tech lead / human (ops) |
| L3 | Low | `frontend/src/app/features/ipd/ipd-device.ts:19-33` | DPDP: persistent per-browser id links staff activity across sessions on shared tablets (staff personal data, legitimate audit purpose, no patient data). | Add to the staff privacy notice and the record of processing. | human |
| L4 | Low | `backend/src/test/java/com/sahaj/platform/it/ScopeRaceIT.java:~28-33` | `GRANT_ENDS_IN_SECONDS` 30 → 5 (not weakened: still fails loudly); the Javadoc still explains the old 30 s choice. | Update the comment. | qa-engineer |

## Re-check of changes since DEC-054 and SAH-75
- AdmittedCardsTest: mixed allergy text is RECORDED (safe side); new stamped-interval test. Fine.
- ScopeRaceIT: see L4.
- EmrOverridesIT, EmrSettingsIT, EmrTemplatesReadIT: empty at HEAD; deletion loses no coverage.

## Checked with no issue
- **RLS on the 9 new tables:** ENABLE + FORCE on all (7 tenant, 2 global); policies on `platform.current_tenant()` with USING and WITH CHECK; column-limited insert grants never include `tenant_id`; no delete grant or policy; delete/truncate deny triggers everywhere; `dose_correction`, `drug_code`, `nursing_profile_template` also refuse update; composite `(tenant_id, …)` foreign keys; guard functions pin `search_path`, execute revoked. Proven by RowLevelSecurityCatalogIT and Sah76MigrationIT.
- **DEC-056 RLS switch:** NO FORCE (line 344) and FORCE (353) in one Flyway transaction (no `executeInTransaction=false`, no mixed, no .conf); a failure rolls the ALTER back; ACCESS EXCLUSIVE until commit; `sahaj_app` never affected.
- **Scope guard:** acknowledge, undo, recorded-in-error find (404) → lock admission → `requireAdmissionLocked` → re-read; change, hold, resume, query-reply order-locked and guarded; query and pharmacist reviews scope-all as contracted; IpdScopeCoverageTest knows NOTICE and DOSE and the 7 scoped list reads.
- **Roles:** token role and active membership role checked per contract; settings writes OWNER-only; clinic, user and role from `ClinicUser.current()`.
- **Override path:** a sent wristband or code is still checked and a mismatch stops; identity confirmation, high-alert second nurse with PIN, PRN and order-state rules never skipped; allergy is a hard stop at order and verify; database refuses a `drug_key` change.
- **Idempotency:** correction replay tied to dose, kind, actor, reason; order-action replays require the same actor (dose replay: L1).
- **X-Sahaj-Device:** `^[A-Za-z0-9-]{8,36}$` after trim, else null; agent is a fixed family; audit label only, never authorisation; CORS adds only this header with explicit configured origins; the frontend sends it only to `/api/v1/ipd`.
- **Logs, audit, events:** ids, keys, numbers and SQL state only; no names or free text (indication, reasons, questions, replies, override and not-given notes); 500 handler strips values; every write audited with `device`.
- **SQL:** constant text with bound parameters.
- **Frontend:** no `console`, `innerHTML` or `bypassSecurityTrust*`; URLs carry ids, limit and cursor only; localStorage holds only the random device id.
