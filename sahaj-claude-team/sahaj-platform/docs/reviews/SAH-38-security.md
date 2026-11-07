# Security review · SAH-38 (HARD-3: colleague PIN hardening)

Reviewer: security-reviewer (read-only), 7 Nov 2026. Saved by the tech lead. The reviewer only read code (`git diff HEAD` plus untracked files; SAH-32 was reviewed before). No new runtime libraries (pom.xml, package.json, lock file unchanged). The three stories SAH-35, 36 and 38 share one branch.

**Verdict: PASS WITH CONDITIONS.** No Critical, High or Medium. 3 Low, 7 Info. Conditions apply at go-live only. SAH-32 findings S1 (quiet colleague-PIN guessing), S2 (colleague-caused lockout) and S3 (event text claimed a scan) are closed in code.

## Low
| # | Where | Finding | Fix | Tech lead decision |
|---|---|---|---|---|
| S38-1 | V2026_12_01_1000 guard, lock branch (lines 162-169) | The guard accepts `lock_by_colleagues = true` on any fifth-try lock but never requires proof that a colleague actually typed a try. Java decides correctly today (StaffPinOps.java:261-264). Missing second line of defence: anyone running SQL as `sahaj_app` could mark an own-session lock as caused by colleagues so it never counts toward switch-off. | In the guard: when `new.lock_by_colleagues` is true and `old.run_has_colleague_try` is false, require a WRONG row for this membership with `at >= transaction_timestamp()`. Add a test in StaffPinColleagueGuardIT. | FIX in the fix pass: db-engineer writes a NEW forward migration (`create or replace` of the guard function; never edit the applied ones); QA adds the guard test. |
| S38-2 | StaffPinOps.java:261-266; ADR-014 D6 "Negative" | A hostile colleague can still lock a nurse out for 15 minutes (about once a week per colleague per hostile account); her own PIN does not work meanwhile. Every lock is visible to her and the owner. | Accept (already recorded in ADR-014). HARD-2 backlog: tell the owner about LOCKED events in-app. | ACCEPT; add to HARD-2 note. |
| S38-3 | ColleagueTriesService.java:72-81, 123-132; platform.yaml:870 | No upper bound on `seenThrough`: an extreme value (year 200000) is outside timestamptz range and probably gives 500 instead of the contract's 400. Only the caller's own notice is affected; the DB cap still stops a future value from hiding later tries. | 400 `seenThrough` if more than one day ahead of the server clock or before 2020; `maxLength: 40` in platform.yaml. | FIX (java; architect adds maxLength; QA adds a test). |

## Info
- I38-1: a capped caller can keep asking and get `colleague-pin-limit`, `pin-locked` or `pin-not-set`; those are not counted; each holds the colleague's row lock a few ms. General limit belongs to HARD-2.
- I38-2: `pin-locked` and `pin-not-set` are answered before the pair cap, so a capped caller can learn whether a colleague is locked. SAH-32 design (the Give sheet needs it); reveals nothing about the PIN.
- I38-3: the pair is keyed on membership id; remove-and-re-add by the owner gives a fresh pair. Owner only; accepted.
- I38-4: anyone using the PIN owner's signed-in session can tap "Got it"; the owner's count and history are not affected.
- I38-5: the `CHECK_FAILED` warning (StaffPinService.java:200) has no caller id. Confirm that RequestLogContextFilter adds the user id (CERT-In). Tech lead: ask java-developer to confirm in the fix pass.
- I38-6: the older indexes `..._pair` and `..._member` are kept next to the `_kind` ones; performance only; dropping is the human's decision.
- I38-7: the new event texts apply to new events only; old rows keep "wristband scanned"; the Medicines-tab legend still says "wristband scan" (OQ-38-6 release note and wording).

## Passed
Pair cap (durable, counted after the colleague's row lock with a fresh READ COMMITTED snapshot, window start = latest of 7 days ago / set_at / last RIGHT_AFTER_WRONG, not compared at the cap, only the same caller's right PIN clears); 429 `colleague-pin-limit` with fixed text and no count; colleague wrong-PIN answer is the fixed "That PIN is not right."; fail closed (lock_timeout 3 s, statement_timeout 5 s, identical 503 for right/wrong PIN, nothing counted, constraint/permission/SQL errors stay 500 via `isCodeFault`); colleague-caused locks (15 min, lock_count and window unchanged, never switch off, 3 own locks still do); colleague tries no longer read or fill the colleague's own bucket; event table (ENABLE+FORCE RLS, select/insert policies only, column-limited insert so id/tenant_id/at cannot be supplied, update/delete/truncate refused by triggers, DB-clock `at`, `seen_through` capped, composite FKs, check constraints, ids/kind/times only); notice (own data only, ACTIVE membership required, counts and state only, marker = last "Got it", POST `seen` idempotent, a try arriving between show and tap stays unseen); owner view (OWNER from the re-read membership, 403 before 404, other clinic 404, `colleagueTries7d` null for non-owners, names at display time, no access row for staff data); logs and audit hold ids and outcome only; timing: wrong and right both pay BCrypt; frontend (no innerHTML/storage/console/URL, store reset on sign-out, history names interpolated).

## Done well
Append-only event table instead of counting audit rows; stable clock for window reads and DB clock for stored times; `isCodeFault` separates "database refused" from "store slow or down"; notice on its own endpoint so sign-in is never blocked; thorough ITs (restart, 8-day window, parallel tries, mixed run, forced timeouts, 404 across clinics, log hygiene).

## Go-live actions for the human (not merge blockers)
1. `SAHAJ_PIN_PEPPER` must be a secret on every server (missing = every PIN check 503 `pin-unavailable`; no rotation design yet, ADR-010 Q6).
2. HARD-2 (shared, durable limiter) must ship with SAH-38 (OQ-38-11); other PIN/code buckets are still in memory per instance.
3. OQ-38-5: clinical sign-off of the ward procedure and the T6 line.
4. OQ-38-6: release note that older "wristband scanned" events did not prove a scan, and the Medicines-tab legend wording.
5. Idle screen lock for ward devices (ADR-011 Q17 / SAH-32 S7) still open.
6. DEC-016 dev sample admin endpoint stays NO-GO on shared, staging or server machines.
7. `docs/approvals.md` has no `stories` or `contract` lines for SAH-35, 36, 38 (human).
