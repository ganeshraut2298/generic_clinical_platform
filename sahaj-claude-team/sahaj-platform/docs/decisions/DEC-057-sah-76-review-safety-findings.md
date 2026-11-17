# DEC-057 · SAH-76 review: three medication-safety findings

Date: 16 Nov 2026. Status: ANSWERED (human, 16 Nov 2026). Raised by: tech lead from docs/reviews/SAH-76-security.md (M1, M2) and docs/reviews/SAH-76-code.md (#2).
Blocked: SAH-76 merge.

## 1. Manual override waives the wristband too (security M1)
One override covers both a missing wristband and a missing medicine code. The medicine code table ships empty, so every Given needs an override, which makes the wristband scan optional in practice. After a wrong-patient STOP, only the screen (not the server) blocks an override.
- **A (recommended):** split the override: a missing medicine code can be overridden, but the wristband must still be scanned or typed and must match; the server refuses any override for the same user, order and slot within 15 minutes of a wristband or medicine mismatch. Pro: keeps patient identity. Con: contract + Java round.
- **B:** accept as is, listed as a go-live condition with the code-loading item (ADR Q1). Pro: no work. Con: weak identity control until codes are loaded.

## 2. One person can verify their own changed order (security M2)
The self-verification rule only compares with the author of version 1. An OWNER (doctor and pharmacist rights) can change an order and verify the change alone.
- **A (recommended):** refuse verify and query when the caller wrote the current version (403 `self-verification`), in the service and the database guard (new forward migration, no data change). Pro: real second check. Con: a one-person clinic owner can no longer verify their own changes (they set verification Off for that ward instead).
- **B:** accept, document.

## 3. PRN start and end not enforced (code review #2)
A 3-day PRN stays givable on day 4; a PRN starting tomorrow is givable now. Contract silent.
- **A (recommended):** before start or after end a PRN dose answers 409 `dose-not-due`, the MAR shows the PRN line locked with "Not started yet" / "Ended", new lock reasons NOT_STARTED and ENDED. Pro: matches order duration. Con: contract + Java + Angular round.
- **B:** go-live condition only.

## Recommendation
A for all three, done in one round with the other review fixes (code #1 EMR line after undo, #3, #6, #7, #8, security L1, L4).

## Answer (human, 16 Nov 2026)
1. **Override: A (split).** Medicine code may be overridden; the wristband must still match; server refuses an override for 15 minutes after a wristband or medicine mismatch by the same user on the same order and slot.
2. **Self-check: A (bar).** Caller who wrote the current version cannot verify or query it (403 `self-verification`), service and database guard (forward migration, no data change).
3. **PRN dates: A (enforce).** Before start / after end: 409 `dose-not-due`; MAR PRN line locked NOT_STARTED / ENDED.

## Architect design for contract 0.4.3 / ADR-027 A9 (16 Nov 2026), NOT YET WRITTEN
The ownership hook identified the architect as tech-lead and refused both edits (same for the java and angular fix agents). Design kept here so it is not lost; the architect writes it once the hook is fixed.
1. **Override (M1):** wristband always required for GIVEN (empty 400 `wristband` "Scan the wristband first."; mismatch 409 as built). Non-empty code always compared; a match ignores any override (SCAN). Empty code and no override: 400 `medicineCode` "Scan or type the medicine code." Override reasons NO_BARCODE, SCANNER_DOWN, EMERGENCY (WRISTBAND_DAMAGED refused on input, still readable on old rows). New 409 `override-after-mismatch` ("Override not allowed" / "A scan for this dose did not match in the last 15 minutes. Check the patient and the medicine, then scan the medicine code.") when the same user had a wristband or medicine stop on the same order and slot (PRN: same order) in the last 15 minutes (fixed), audited IPD_DOSE_REFUSED. Check order: wristband, code, override fields, override-after-mismatch, identityConfirmed, second nurse, PRN limits. NEW UI texts: "Cannot scan the medicine? Manual override", hint and note label (see architect hand-back).
2. **Self-check (M2):** verify and query 403 `self-verification` for ordered_by OR current version created_by. NEW texts "The doctor who changed this order cannot verify it. Ask the pharmacist." / "...cannot check it...". Queue `verifyLockReason` gains SELF_CHANGED.
3. **PRN dates:** 409 `dose-not-due` (existing type) before start_at or at/after end_at, not audited; `PrnLine.lockReason` NOT_STARTED "Not started yet", ENDED "Ended".
4. change() field order documented as built. 5. Dose replay by another recorder: 409 `client-key-reused`, nothing written. 6. Record-access writes per reload accepted for phase 1 (go-live capacity review; dedupe would be a human decision); older-version slot stays a go-live item.
**Database (one forward migration):** replace `guard_medicine_order_version_update` and `guard_medicine_order_query_insert` for the version-author rule; **new tenant table `ipd.dose_scan_stop`** (admission, order, slot_at, check_kind WRISTBAND/MEDICINE, actor, stopped_at; FORCE RLS, insert+select only, deny triggers, index (tenant_id, order_id, actor, stopped_at desc)) because the event log has no order/slot columns and ipd may not read the platform audit; backstop in the dose insert guard (GIVEN needs identity_scan; no WRISTBAND_DAMAGED; OVERRIDE has no medicine_scan). No data change.
