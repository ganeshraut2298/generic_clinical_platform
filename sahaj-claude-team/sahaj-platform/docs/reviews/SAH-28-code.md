# Code review · SAH-28 (IPD emergency queue, triage, Emergency setup)

Reviewer: code-reviewer (read-only), 5 Nov 2026. Saved by the tech lead. Nothing was run by the reviewer.

**Verdict: APPROVE WITH COMMENTS.** No Blocker or Major. 4 Minor, 9 Nit. Security review: PASS (docs/reviews/SAH-28-security.md).

| # | Sev | Where | Finding | Fix | Tech lead decision |
|---|---|---|---|---|---|
| 1 | Minor | EmergencyService.java:114-127, 348-379, 338-344 | Read race on a scale change: setup and open cases are read in separate READ COMMITTED statements; a level-4/5 case can meet a 3-entry list, `targetOf`/`levels.get(level-1)` throw, one poll gets a 500. | `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` on queue, summary, options; or clamp; add a test (level 5 case with a 3-colour setup). | Fix |
| 2 | Minor (QA F3) | V2026_11_22_1000:63,113; EmergencyService:184,214,247,325 | Mixed time sources: `arrived_at` and triage `recorded_at` use transaction-start `now()` (before the lock), `seen_at`/`closed_at`/audit use the app clock. Racing re-triages can be stored out of order; AC12 promises who and when. | New migration V2026_11_24 (not an edit of V2026_11_22): `recorded_at default clock_timestamp()`; compute waitingMinutes consistently (one clock); IT asserting write order, not `xmin`. | Fix |
| 3 | Minor | emergency.component.ts:110-121, emergency-badge.store.ts:44-49 | A failed 60-second reload is silent; stale waits and "within target" shown as current on a triage screen. | Warning notice after a failed refresh ("Could not refresh the queue. Last updated 11:05 AM"), cleared on success. | Fix |
| 4 | Minor | iper.page.html:62,71,89,98; iper.page.ts:55-82 | Steppers and scale buttons stay enabled while a save runs; clicks silently ignored. | Disable with `store.locked()` like the switch. | Fix |
| 5 | Minor | EmergencyService.java:423-425,451-452; ipd.yaml POST /emergency/cases | Contract drift: (a) control/bidi messages for name and complaint not in the contract message list; (b) `identityUnknown` required but null treated as false; (c) Jackson coerces "40" into numeric fields. | Architect: add (a). Java: reject null identityUnknown. (c): accept (documented). | Fix (a), (b); accept (c) |
| 6 | Nit (QA F4) | EmergencyRules.java:61-62; EmergencyIT.java:289-303 | Third sort key is Java signed `UUID.compareTo`, not PostgreSQL uuid order; IT only checks stability. | Contract: "then id (as a UUID compare)"; one IT fixing the order. | Fix |
| 7 | Nit (QA F5) | EmergencyService.java:502-515 | Target order is not checked (Red 120, Green 5 accepted); mockup has no rule. | Record "no order rule"; do not add without the human. | Recorded in DEC-013 item 10 as a question |
| 8 | Nit | TriageEventRepository.java:17; JdbcTriageEventRepository; `CaseRow.version` | `findByCase`/`EventRow` used only by tests; `version` bumped, never read. | Keep (level-history screen is expected) or drop. | Keep, note in handoff |
| 9 | Nit | JdbcEmergencyCaseRepository.java:23-26,42 | `findOpen` loads `mobile` on every poll though the queue never shows it. | Column list without `mobile` for queue reads; read it only for the hand-off. Fits security M1. | Fix |
| 10 | Nit | emergency.component.html:65,76,87,111 | Every card repeats identical button names; screen-reader list cannot tell patients apart. | Visually hidden suffix with the patient name; keep the visible label. | Fix |
| 11 | Nit | EmergencyService.java:143-171 | `register` reads the setup twice and re-reads the case for `arrived_at`; per-case insert loop in `updateSettings`. | Pass setup into `buildQueue`; `returning id, arrived_at`. | Fix (small) |
| 12 | Nit | EmergencyController.java:44,59,84; emergency.component.scss:42 | Borrows `AdmissionController.READERS`; stripe text uses `--crit-ink` on warn/good backgrounds (token name misleading); `Case`/`Queue` shadow common names. | Own the constants; optional rename. | Fix constants only |
| 13 | Nit | emergency.spec.ts:1250,1468,1540 | `waitForTimeout(300-500)` as negative waits. | Assert a request counter. | QA, later |

## Carried by others
- Security M1 (clear `mobile` when a case closes): not yet in code. New migration (do not edit V2026_11_22): `grant update (mobile)`, clear in the same UPDATE as the close (`close`, `markAdmitted`), the hand-off must read the mobile before it is cleared (`lockOpenCase`), guard trigger accepts `new.mobile is null` only on the open-to-closed transition; extend EmergencyTableIT and EmergencyPrivacyIT. Retention of name and complaint is a human decision (DEC-013 item 12).
- DEC-013 item 10 deviations still need sign-off (reviewer finds each acceptable): no-beds behaviour on `/app/emergency`; Emergency in "Daily work" before Beds; changed setup tip text; no setup-overview summary line.
- `validate.mjs frontend` fails on its `--browsers` flag (human-owned).

## Checked and correct
AC 1-13 each map to code and a passing test; route mount fix with a real regression spec; ordering, waits, strict over-target, target 0; bay allocation and the partial unique backstop; client-key idempotency; lock order (advisory, case, patient, beds) and admit atomicity; settings checks and the 3-to-5 / 5-to-3 mapping; module boundaries; roles and tenancy; privacy (redacted toString, fixed messages); migration (forward-only, composite FKs, column grants); contract match apart from item 5; no N+1; frontend OnPush, signals, a11y, texts; no skipped or disabled tests.

## Done well
Written design with every deviation recorded; defence in depth; pure rules separated from service and repository; honest tests of the hard parts (backdated arrivals, real second user, parallel bays/keys/release-vs-admit); one answer shape for every action; thoughtful UX details.
