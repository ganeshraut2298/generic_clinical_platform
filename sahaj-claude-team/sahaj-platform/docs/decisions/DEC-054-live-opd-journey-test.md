# DEC-054 · Live OPD journey test on the human's dev platform

Date: 14 Nov 2026. Status: ANSWERED (human, chat). Raised by: tech lead.
Request (human): "Take 5 patients of each department (General, Dental, Gynae and the others integrated) … do the integration and workflow test for OPD, from appointment to billing; use the the clinic owner account account; I will monitor with this account; if issues arise, fix them."

## Answer (human, chat, 14 Nov 2026)
- Run against the human's live dev platform (sahaj_dev on localhost:8080), logged in as the clinic account the clinic owner account (dev OTP bypass: any 6-digit code; loopback only). The human adds a narrow permission rule for the journey script and localhost health checks (agents cannot edit permission settings).
- All 7 departments with screens: General Medicine, Dental, Obstetrics & Gynaecology, Paediatrics, Physiotherapy, General Surgery, Medical Oncology — 5 patients each (35).
- Patients are obviously fake test data (no real names or numbers), marked as test patients.

## Rules for the run
- Journey per patient: register patient → appointment → arrive / token → queue → doctor opens the EMR → fills the department's required sections → finish-check → sign ("Complete" / "send to billing") → bill → payment → receipt. Department extras where drawn: Dental plan rows and next sitting; ANC visit; Paeds vaccines; Physio session; Surgery OT booking; Oncology chemo order with the second check by a second staff member.
- Missing clinic setup (departments, doctors, services, prices, workflow steps) is added through the app's own setup API as the owner, and listed in the report.
- Every failure is logged with request, status, problem type and the server's `Unhandled error` line if any; bugs go to their owners and are fixed, then the journey is re-run.
- Nothing is deleted; no database command outside the app; no secrets printed.

## Preconditions (human)
1. Stop the leftover agent processes (Maven/bash loops under scratchpad\locks).
2. Restart the dev server without `clean` so today's code and migrations V2026_12_14_1000–1060 are applied to sahaj_dev; start the frontend.
3. Add the permission rule (see chat).

## Run 1 (14 Nov 2026)
Script frontend/e2e/live/opd-journey.mjs; report docs/test-plans/live-opd-journey-2026-11-14.md. Server checks passed (0.7.x code, og/onco layout v3). Patient gm-01 went register → appointment → arrival → queue → EMR open → required sections → finish-check (ready) OK; then one draft save (exam + procedures "Dressing" BILL_TODAY + followup D7) never answered, later saves and sign queued, and after ~10 min the connection pool was exhausted (500 / no answer). Run halted; dev server needs a restart. Routed: java-developer (root cause + lock/statement timeouts), qa-engineer (EmrDraftSaveHangIT reproduction on sahaj_test).
Setup made in the clinic via the app: doctors "Dr Test GM Live" (9000000101) and "Dr Test Dental Live" (9000000102) with slots 06:00–23:45; services Dressing ₹ 150, Composite filling ₹ 1,200, Root canal treatment ₹ 5,500; seven "<dept> Live" departments from starters, published v1 (doctors assigned to GM and Dental only — the free plan allows 5 users; other departments use the two test doctors). Left behind: test patient "Test Gm 01 Live" with a visit WITH_DOCTOR and a draft record. Gap: billing.yaml has no receipt for a visit bill (only course receipts).

### QA hand-back (hang reproduction), 14 Nov 2026
`backend/src/test/java/com/sahaj/platform/it/EmrDraftSaveHangIT.java` written (7 variants: live-script save, exam, procedures, followup alone and in pairs; 20 s client limit; prints stuck thread frames, pg_stat_activity and pg_locks; ends hung sahaj_app sessions on sahaj_test only). Not compiled or run: backend/frontend locks were held by the running java-developer from 21:42 to 22:44 IST. Runner script for the lock protocol: scratchpad/run-hang-it.sh. Next: run it once the java-developer releases the locks; compare with the java root cause.

### Java hand-back (hang), 14 Nov 2026
Root cause NOT found by code reading: one transaction, one connection, no self-wait, no lock cycle, no loop on the draft path. Inferred mechanism: the first PUT took the record row lock (`takeHold`) then waited on a lock held by another session that never finished (idle in transaction); later PUTs and sign queued on the row until the pool ran out. Defence added: pgjdbc start-up options `lock_timeout=10000`, `statement_timeout=30000` (sahaj_app pool only; env overrides), Hikari connection-timeout 10 s, leak-detection 60 s; new `DatabaseBusy` maps 55P03/57014/no-connection to 503 `database-busy` with `Retry-After: 5` (deadlock stays 500). StaffPinOpsTest fixed (fixed UUIDs). Verified: main compiles with javac; 6 classes, 94 tests green outside Maven. Whole-project gate NOT run.
Correction to the QA note: no agent held the locks. Both lock folders have been held since 20:48 with an empty owner file refreshed every ~10 s (still at 22:58 with no agent running) = a leftover process from a dead agent. The human must stop it (precondition 1) before any gate can run.
Open: solution-architect to list the generic 503 `database-busy` problem in docs/api/*.yaml.

## Runs 2-3 and root cause (15 Nov 2026)
Run 2 (restarted server, timeouts live) and Run 3 (new patient gm-02) both hung on the `exam` save alone; 0 of 35 patients completed.
**Root cause (java-developer):** infinite loop in `SpecialtyDone.examGeneral` (`marked = marked || signs.next()...` inside `while (signs.hasNext())`: once `marked` is true, `next()` is never called, so the thread spins at 100% CPU). Triggers when the exam `signs` object has 2+ entries; runs on every save and read-back of such a record; holds the record row lock, so sign returned 503. DB timeouts cannot fire on a CPU loop. Fixed: call `next()` first. Regression test `ExamGeneralPurePathTest` (+ `SpecialtyDoneAndProfilesTest`): 22/22 via javac + JUnit launcher (no Maven); hung before the fix (jstack at SpecialtyDone.java:88). No other occurrence in src/main.
Also: the stuck dev JVM keeps spinning; a restart clears it. Records gm-01 and gm-02 hold exam values with 2+ signs only if a draft was stored; the hung saves rolled back or never committed.
Needs: human restarts dev server (no clean). Whole-project `mvn test` still pending (locks). Then QA reruns the journey.

## Run 4 (15 Nov 2026): fix live, 25 of 35 journeys complete
No hangs, no 503s. exam saves in 200 ms. Full journey to payment (balance 0): GM gm-02..05, dental 5, og 5, paeds 5, physio 5 (+ extras: Dressing BILL_TODAY, follow-up, dental plan/next sitting, ANC, vaccines, physio package sessions). Gap known: no visit receipt operation in billing.yaml.
Open: (1) gm-01 (visit 14 Nov) bill ₹ 450 balance ₹ 450 absent from counter lists on 15 Nov: today-only filter? java to confirm against the contract. (2) surgery 5/5 stop at fill_required: record needs "OT booking"; script books OT later (script ordering, QA) — java to confirm intended order. (3) onco 5/5 stop at fill_required: needs "today's decision" and "send chemo order"; script depends on `specialty.onco.suggestion`. Routed to java (analysis, exact call sequence) and QA (script fix) in parallel.

## Run 5 (15 Nov 2026): 34 of 35 journeys complete
Surgery 5/5 and oncology 5/5 now reach payment (script ordering fixed: OT booking last; ECOG/labs/exam, decision, chemo order, nurse second check, doctor send). Java fix (billing counter 30-day lookback: `VisitBillingPort.earlier`, `BillingService.counter`; 37/37 tests via javac) needs a dev-server restart; gm-01 (visit 14 Nov, ₹ 450 unpaid) still absent from the counter until then.
Human to confirm: (1) surgery visit bill is consultation only (₹ 400): no OT/procedure charge reaches the counter: intended? (2) oncology is billed at the fallback doctor's fee (₹ 300, not ₹ 800) because the free plan allows 5 users: setup artefact. (3) onc_ecog/onc_labs not required, so a chemo order can be sent without ECOG: design question. (4) no visit receipt operation in billing.yaml: new story.
Remaining: restart server → QA `--only gm --per 1` for gm-01; whole-project mvn test + IT (locks); code and security review of the DEC-054 fixes.

## QA safety fixes (15 Nov 2026) and an incident
Done by QA: owner mobile now required from SAHAJ_OWNER_MOBILE (exit 2 if missing); script refuses non-local hosts without `--i-know-this-is-dev`; test-plan report no longer overwritten (marked blocks per tag); new `DatabaseTimeoutsIT` (compiled, not run). Reviews saved: docs/reviews/DEC-054-code.md (approve with comments) and DEC-054-security.md (pass, 2 Medium: counter N+1 being fixed; owner mobile removed from this file).
**Incident (QA, disclosed by the agent):** before the guard existed QA ran the old script twice by mistake: once as a GET-only `--probe` against localhost (read today's visits, nothing stored), once with `SAHAJ_API_BASE=http://example.com:8080` which tried to reach that external host (nothing answered). The old script would have sent a login request containing the owner mobile and the dev OTP code to that host. No data was written locally. Told to the human; the guard now blocks this.

## Java review fixes (15 Nov 2026)
Counter lookback now: stage filter (BILLING, DONE), newest 200, batched balance (3 queries, not 3 per visit), earlier dues oldest first, today computed once, comment fixed. SpecialtyDone `systems` loop confirmed safe; new test with 5 signs and 5 systems; ExamGeneralPurePathTest uses assertTimeoutPreemptively and the restored assertion (EmrRecordDocument.empty). 46/46 unit tests via javac + launcher (no Maven). #6 (503 wording) not changed: DatabaseBusyTest asserts the current text. No DB test yet of the new SQL: needs an IT under locks. Server restart needed.
