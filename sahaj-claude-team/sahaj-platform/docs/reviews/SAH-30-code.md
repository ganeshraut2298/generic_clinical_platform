# Code review · SAH-30 (IPD nursing vitals, notes, early warning score) and the SAH-28 fix pass

Reviewer: code-reviewer (read-only), 5 Nov 2026. Saved by the tech lead. Nothing was run by the reviewer. QA findings F1 (strict clientKey) and F2 (detail = first message) were excluded; a java-developer fixes them.

**Verdict: APPROVE WITH COMMENTS.** No Blocker or Major. 8 Minor, 13 Nit.

| # | Sev | Where | Finding | Fix | Tech lead decision |
|---|---|---|---|---|---|
| M1 | Minor | VitalsService.java:112,222; AdmissionService buildDetail `gate.now()`; IpdGate.now() | Contract says NursingSheet `asOf` is the database time; GET and replay use the app clock while `recorded_at` is database time: skew shows a wrong "N min ago" on a clinical sheet; `vitalsDue` also mixes clocks. | Read `clock_timestamp()` through a repository call (like `EmergencyCaseRepository.databaseNow()`) for `asOf` and `vitalsDue`. | Fix |
| M2 | Minor | JdbcAdmissionRepository.java:60,64-67 (FIND_FOR_UPDATE, FIND_FOR_NO_KEY_UPDATE) | The lock query joins admission, bed and ward; in READ COMMITTED a save waiting for a transfer is re-checked against the new admission row but the old bed row; the join stops matching and the nurse gets a false 404. Same shape in discharge and transfer locks. | Lock the bare row first (`select id, status from ipd.admission where tenant_id=:t and id=:id for no key update`), then read the joined row in a second statement; add a transfer-vs-save IT. | Fix (all three lock queries) |
| M3 | Minor | VitalsService.java:125 | ADR-006 D4 correctness depends on READ COMMITTED, only the implicit default. | `@Transactional(isolation = Isolation.READ_COMMITTED)` with a comment. | Fix |
| M4 | Minor (safety) | nursing-sheet.component.ts:171-178,118-120; spec :522 | After a save the sheet stays open, re-prefilled with the saved values, a new clientKey and Save enabled: one more tap stores a second identical reading. | Keep Save disabled after a success until the user types (input event) in a field or the note. | Fix (DEC-013) |
| M5 | Minor (process) | frontend/e2e/nursing.spec.ts, emergency.spec.ts | `frontend/e2e` is gitignored (line 8), so the specs are not in version control. | `git add -f` at commit time, or un-ignore `*.spec.ts`. | Noted for the commit proposal (humans own .gitignore decision) |
| M6 | Minor (doc drift) | SAH-30.md AC13, OQ-30-7; VitalsService.java:105-116 | Code logs the sheet open; story AC13 says it does not. | Human confirms DEC-013 item 11, then the BA amends AC13 and OQ-30-7. | Human, then BA |
| M7 | Minor | V2026_11_23 table comment :213-214; ADR-006 D2; docs/database/SAH-30.md:133; postgresql.md rule | Partition runbook calls the attach "metadata only"; with parent PK `(id, recorded_at)` it builds an index and blocks writes. Quarterly vs the rule's monthly. | Runbook: `create unique index concurrently ... (id, recorded_at)` on the old table first, then attach; "quarterly, monthly at the top of growth", note the departure from the rule. | Fix in docs (db-engineer, architect) and a COMMENT in a new migration |
| M8 | Minor (tests) | VitalsConcurrencyIT.java:215-244; JdbcVitalsRepositoriesTest | `aSaveRacingADischarge` accepts both outcomes in each round and can pass without hitting the race; a `Thread.sleep(1500)` blocked-save check; SQL-text mocks mirror the code. | Make the race deterministic (hold the lock on a second connection, poll pg_locks); add the transfer race (M2); optionally drop the SQL-text test. | Fix (QA) |
| N1 | Nit | V2026_11_23 `recorded_at default now()` | Transaction-start time taken before the lock wait; racing saves can store `recorded_at` out of write order. ADR accepts it. | Accepted. | Accept |
| N2 | Nit | V2026_11_23 ews_escalation checks | No `(score>=7)=(risk='HIGH')`. | Add it (also security L5). | Fix (new migration) |
| N3 | Nit | V2026_11_23 `version integer` | Rule says bigint. | bigint or note. | Fix in the same migration if safe, else note |
| N4 | Nit | VitalsService.java:126-218 | `record` is about 90 lines; escalation block reads twice. | Extract `applyEscalation(...)`. | Fix if cheap |
| N5 | Nit | VitalsService.java:158-163 | Same key on two admissions at the same instant gives 500 on the unique index. | Accept or map to 400. | Accept |
| N6 | Nit | VitalsService.java:256 | Missing patient gives the name "Patient" in the toast. | Throw 404 or leave the name empty. | Fix (404) |
| N7 | Nit | VitalsRules.java:136-156; IpdText | A pasted tab or emoji with a variation selector is refused with a message that does not say what to remove. | Clearer message (architect) or convert tabs to a space before the check. | Fix: tab becomes a space; contract note |
| N8 | Nit | AdmissionService.java:184 `options` | Always read-write REPEATABLE_READ even without `emergencyCaseId`. | Keep, or split the case-opening path. | Keep |
| N9 | Nit (a11y) | nursing-sheet.component.html:272-277,323,370-378 | Score notice not a live region; failure notice has no role; loading text has no `role="status"`; a field error stays until the next Save. | `role="status"` on the score notice and loading line, `role="alert"` on the failure notice, clear a field error when edited. | Fix |
| N10 | Nit | nursing-sheet.component.ts:48-52,255-257,124-133 | `didSave` false after a lost answer that "Check the sheet" shows did save; `load()` has no in-flight guard. | Set `didSave` when `check()` finds a newer `latest`; guard `load()`. | Fix |
| N11 | Nit | nursing-sheet.component.ts:18 | Imports `newClientKey` from the emergency feature. | Move to `shared/`. | Fix |
| N12 | Nit | EscalationRow.version; ipd/package-info.java | `version` read and never used; package-info lacks SAH-30. | Remove or use; update the Javadoc. | Fix |
| N13 | Nit | VitalsService `Names` | Up to 4 name lookups per sheet (cached per sheet). | Optional batch later. | None |

## SAH-28 fix pass
V2026_11_24 one-off statement judged safe (one Flyway transaction, ACCESS EXCLUSIVE lock, lock_timeout 30 s, idempotent, only `mobile` on closed rows). Residual: if anyone sets `executeInTransaction=false` for the script, FORCE RLS and the trigger stay off; nothing checks `tgenabled` (add a catalog assertion, security L6); the data statement is untested on real rows; run in a quiet window. Guard, grant, clock, snapshot, mobile handling, ActedCase and IpdText all correct.

## Checked and correct
Score bands, risk rule and targets exactly as AC 6 and the mockup; event texts; escalation logic (raise, strictly higher update, equal/lower and Low change nothing); single admission lock and no deadlock; replay semantics; check order and messages; audit payload and redacted toString; module boundaries; one-query-per-part sheet (no N+1); migration quality; mockup match; Angular OnPush/signals/a11y/key lifecycle/lost-answer handling; test quality (no skips; expected values from the story).

## Done well
One pure unit-tested `EarlyWarningScore` with integer tenths; the database as last line of defence; one row lock with a rule for the Safety board; rollback ITs by fault injection; privacy care; a lost-answer design on the client; the SAH-28 fix pass closes items with database enforcement plus tests.
