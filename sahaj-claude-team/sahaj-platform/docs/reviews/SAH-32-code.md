# Code review · SAH-32 (IPD Medicines MAR and giving a dose with the bedside checks)

Reviewer: code-reviewer (read-only), 6 Nov 2026. Saved by the tech lead. Nothing was run by the reviewer (QA ran the suites).

**Verdict: APPROVE WITH COMMENTS.** No Blocker or Major. 8 Minor, 8 Nit. Security review: PASS (docs/reviews/SAH-32-security.md).

Process notes for the human: no `stories`/`contract`/`merge` lines for SAH-30, SAH-31, SAH-32 in docs/approvals.md; ADR-010 and ADR-011 are PROPOSED and carry build-time wording; the branch carries SAH-1..33 plus ADM-1, so `merge` approval covers the whole bundle; SAH-31 and SAH-32 must be released together (ADR-011 D15).

| # | Sev | Where | Finding | Fix | Tech lead decision |
|---|---|---|---|---|---|
| R1 | Minor | MedicineAdministration.java:359-367 | Replay compares the second nurse by display name (`TeamMember` has no user id); two nurses with the same name or a rename give a wrong 200/409. Nothing is written. | Add `userId` to `TeamMemberPort.TeamMember`, compare ids. | Accept for now (DEC-018); backlog. |
| R2 | Minor | WristbandCheck.java:23, 64-67; ADR-011 D4; ipd.yaml | UHID-shape regex allows 41 characters; stored `identity_scan` allows 1-40, so a 41-char UHID would match then fail the insert with a 500. | `{5,33}` or align docs; cap digits at source. | FIX (java; with security S6); architect aligns the contract. |
| R3 | Minor | DoseRules.java:32; MarService.java:53,57 | GET /mar 403 text not in ipd.yaml. | Add to contract. | FIX (architect). |
| R4 | Minor | nursing-sheet.component.ts:366; MarService.java:62; story AC9 | Story AC9 says opening the MAR is not logged; code and contract write a record-access row per GET /mar (more with tab switches). | Human confirms; BA aligns AC9. | HUMAN (DEC-018 item 5); safer direction kept. |
| R5 | Minor | StaffPinService.java:170-195 | `checkColleague` holds a JVM monitor (64 stripes) across three DB calls and a ~0.25 s hash. | Per-key lock or DB counter. | Accept; add to HARD-2 note. |
| R6 | Minor | give-sheet.component.ts:281, 328-334 | After a wristband mismatch the focus returns to the box but the old text stays; a keyboard-wedge scanner appends the next scan to it, so it mismatches again. | `select()` the text (or clear) after a mismatch; unit + e2e test. | FIX (angular; QA adds the e2e). |
| R7 | Minor | MarAssembler.java:76-80,134-138; VitalsService.java:338 | MAR and `doseCount` load every order of the admission and filter in Java; `doseCount` runs inside the admission lock on every vitals save. | Repository method with `status in ('PENDING','ACTIVE')`; compute outside the lock window. | FIX (java). |
| R8 | Minor | MedicineAdministration.java (prepare, decide) | Each mismatch writes a permanent clinical event and an audit row; no limit, no volume estimate. | Add to ADR-011 D13 estimate; optional collapse of repeats. | Accept; HARD-2 and audit_event partition plan (security S5). |
| R9 | Minor | mar-tab.component.ts:133; ipd.page.ts:396 | Mockup Medicines sheet is "wide" (760 px); built at 640 px, only the sticky footer built. | Human decides. | HUMAN (DEC-018 browser fix notes). |
| R10 | Nit | nursing-sheet.component.html:26,39,54,140 | `aria-controls` points at a panel that is not in the DOM when unselected; panels lack `tabindex=0`. | Drop `aria-controls` on the unselected tab or render both panels `hidden`; `tabindex=0`. | FIX (angular). |
| R11 | Nit | give-sheet.component.ts:299-325; .html | One refusal is announced up to three times (snackbar, field alert, live line). | Snackbar `aria-live="off"` for these, or drop the duplicate. | FIX (angular). |
| R12 | Nit | give-sheet.component.html:149; mar-tab.component.scss | "Record not given" is a stroked Material button (mockup: ghost); slot small text 0.68 rem in `--ink-3`. | List the deviations; consider 0.75 rem / `--ink-2`. | Accept; list in DEC-018. |
| R13 | Nit | MedicineAssembler.java:147-151 | `row(UUID, OrderRow, boolean)` unused and passes `lastDose = null`. | Delete. | FIX (java). |
| R14 | Nit | ColleaguePinCheck.java:29,51 | `platform.api` knows the ipd field name `secondNurseId`; `ok()` hard-codes 5 tries. | ipd maps NOT_ELIGIBLE to its field; use `StaffPinRow.MAX_TRIES`. | FIX the constant; field mapping stays (DEC-018 note). |
| R15 | Nit | MedicineAdministration.java:218-226 | `Decision` is a mutable class with seven fields; class 479 lines. | Record/builder later. | Backlog. |
| R16 | Nit | DoseSlots.java:113-116 | Whole minutes rounded down: 60 min 59 s ahead is DUE; the story says "more than 60 minutes". Chosen on purpose (ADR-011 D2, contract). | One line in DEC-018. | Record in DEC-018. |

## Differences from the mockup (all in DEC-018 or the contract unless NEW)
Scan wristband button not built; subtitle drops the assigned nurse; late shown as "N h late"; two extra slot states and lock lines; 403 text "Only nurses and the clinic owner can give medicines." (mockup: nurses and doctors); server-side wristband compare (NFKC, 200 cap) and UHID-shaped deny scan; real colleague PIN with masked box; new texts for replay and refusals; read-only clinics refuse recording; MAR open writes a record-access row; 44 px slots and footers; NEW Medicines sheet 640 px not 760 px; NEW "Record not given" stroked not ghost; phone toasts at the top; loading/load-error/"could not record" texts; NEW focus returns to the box after a mismatch; doctor sees Not given/Refused in the EMR "Last dose" line and the Nursing tab shows a dose count.

## Checked and correct
All 13 acceptance criteria map to code and tests apart from AC9 (R4); slot and clock rules (IST, 60/61 minute edges, mid-day verification, Stopped/Rejected leave the MAR, doses stay in `lastDose`); check order per ADR-011 D5; insert matches docs/database/SAH-32.md; migration matches the design; thin controller with token and membership role checks; three-step shape (prepare, colleague PIN outside any transaction, locked transaction) and single bare admission-row lock with no cycle; `asOf` after the lock; colleague check eligibility, limits before hashing, audit and error mapping; AuditSanitizer UUID fix narrow; wristband check and committed deny entry; idempotent replay; module boundaries (ipd imports only platform.api and patient.api); privacy (DosePrivacyIT); Angular OnPush/signals/focus/44 px/no storage or console; no hidden skips in tests; clock seam test-only; real race tests.

## Done well
Pure `DoseSlots` class shared by MAR, count and dose checks; one clock source read after the lock; prepare/PIN/locked shape with the deny entry committed by returning a result; database backstops never swallowed; colleague PIN design (lockout on the colleague's row, wrong tries only, audited, caller cannot switch a PIN off alone); no names in mismatch details; client key regenerated when the request differs; body-safe focus fallback; test quality; plain handoff and DEC-018.
