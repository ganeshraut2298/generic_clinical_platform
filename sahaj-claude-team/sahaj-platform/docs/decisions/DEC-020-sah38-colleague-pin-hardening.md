# DEC-020 · SAH-38 (HARD-3) colleague PIN guessing, lockout and dose event wording

Status: NEEDS DECISION (security design + clinical procedure). Blocks: SAH-38 architecture onward. SAH-35 and SAH-36 are not blocked.
Stories: docs/stories/SAH-38.md, docs/requirements/SAH-38.md (written by the BA 7 Nov 2026; "HARD-3" is in the title because validate.mjs requires SAH-<n> file names).
Source: security review SAH-32 S1, S2, S3 (docs/reviews/SAH-32-security.md); ADR-011 Q14-Q16.

## Why this needs the human (escalation rules)
It changes authentication behaviour (ADR-010 D5: the lock must remember who typed the wrong tries; third-lock switch-off rule), adds a clinical ward procedure, and changes the wording of a permanent clinical record. All proposals below are the BA's safer direction.

## Questions and recommendation
| # | Question | Recommendation |
|---|---|---|
| OQ-38-1 | Cap on wrong colleague-PIN tries | 5 wrong tries per (caller, colleague) pair per rolling 7 days, cleared by a right PIN; durable (survives restart, same on every instance); fails closed |
| OQ-38-4 | Colleague-caused locks | They still lock the PIN for 15 minutes but never count toward the third-lock switch-off; mixed runs count as colleague-caused |
| OQ-38-5 | Ward procedure when a high-alert dose is due and no second PIN works | CLINICAL: wait for the nurse in charge or owner / call the doctor / paper second check in a nursing note. Story builds only the line "Do not give this medicine yet ... Never share a PIN" and no bypass |
| OQ-38-6 | Dose event wording (record-keeping, go-live blocker) | New give events say "identity checked against UHID" (old rows unchanged); mismatch event "Stopped at the identity check: entered X, expected Y" instead of "scanned X" |
| OQ-38-2 | Should the colleague see who tried? | No: counts only; owner sees names and times on the Team page |
Proceeding on the BA proposals if not answered: OQ-38-3 (marker = last "Got it" or PIN set), -7, -8 (owner cannot clear a cap), -9 (in-app notice only), -10, -11 (release with HARD-2).

## Blocked
SAH-38 architecture, database, build. Everything else continues.

## Default applied (DEC-023, 7 Nov 2026)
No answer given; the recommendations above are taken. The ward procedure (OQ-38-5) and the permanent-record wording (OQ-38-6) remain go-live conditions for clinical sign-off.

## Architect result (ADR-014, 7 Nov 2026)
Durable store: new append-only table platform.staff_pin_colleague_event (kinds WRONG, LOCKED, RIGHT_AFTER_WRONG, NOTICE_SEEN; ids, kind, DB-clock time). Cap 429 `colleague-pin-limit` (no count, no retry time); fail-closed 503 `pin-check-failed`; staff_pin gets run_has_colleague_try and lock_by_colleagues (guard function replaced; colleague-caused locks never switch the PIN off); notice banner after sign-in (counts only, "Got it"); owner sees the 7-day count and history on the Team page; ward line T6 client-side; new event texts forward only. One additive platform migration, no existing row rewritten. Defaults applied (DEC-023): Q1 "Former staff member" only when no name found (differs from the story), Q2 a new PIN does not clear unseen tries, Q3 owner's 7-day figure survives a PIN reset, Q4 new texts for sign-off. Still the human's: `stories` + `contract` approval lines; go-live: clinical sign-off of ward procedure + T6, release note that old "wristband scanned" rows did not prove a scan, release together with HARD-2. Branch note: lanes share branch feature/SAH-13-appointments-workspace (one story per branch rule not met; human decides when to split at commit time).

## Angular build notes (7 Nov 2026) - texts for sign-off
Built: shell banner (counts only, "Got it", PIN state line, "Set my PIN" only when switched off), Team row button + history sheet (OWNER only, count shown only when > 0), Give sheet 429/503 texts, fixed colleague wrong-PIN text "That PIN is not right.", ward line T6 (shown after pin-locked, pin-not-set, rate-limit, colleague-pin-limit or when no other nurse; not after wrong-pin/503; no bypass). Unit 1,984 (72 new). NEW texts: T1 "Too many wrong PIN tries for this nurse. Choose another nurse."; T2 "N wrong tries of your PIN by colleagues since your last use." / "1 wrong try of your PIN by a colleague since your last use."; T3 "Your PIN is locked until <time> IST." / "Your PIN was switched off. Set a new one."; T4 "N wrong tries by colleagues in 7 days"; T5 history lines; T6 "No second PIN works? Do not give this medicine yet. Choose another nurse, or ask the nurse in charge or the clinic owner. Never share a PIN."; T7 "We could not check the PIN. Try again."; T11 "Wrong PIN tries · <name>"; T12 "No wrong tries by colleagues in the last 7 days."; extras: "Showing the newest 100 entries.", "We could not load the wrong tries. Try again.", "Your PIN is locked.", banner aria-label.
Fix for the architect (fix pass): the POST /team/{id}/pin/reset example in platform.yaml shows colleagueTries7d: 0 after a reset, but ADR-014 D9 says a reset does not clear the figure.

## Review results and decisions (7 Nov 2026, tech lead)
Code review APPROVE WITH COMMENTS (docs/reviews/SAH-38-code.md), security PASS WITH CONDITIONS (docs/reviews/SAH-38-security.md). No Critical or High. SAH-32 findings S1, S2, S3 are closed in code.
- QA found and the java-developer fixed: B1 (notice endpoints 500 for anyone without a "Got it" row), B2 (volatile clock_timestamp() forced a seq scan on the Team count: now statement_timestamp() for the pair cap and the two 7-day reads). B3: new additive migration V2026_12_02_1000 (indexes with `kind`). Measured: Team count 174-380 ms -> 2.5 ms, pair cap ~50 ms -> 8.8 ms.
- Fix pass: S38-1 new forward migration V2026_12_03_1000 (guard needs proof of a colleague WRONG try for lock_by_colleagues); S38-3 seenThrough bounds (400 before any DB call); R38-1 give text capped at 300 characters; R38-4 toast "We could not save that. Try again." when "Got it" fails (NEW text, banner returns); R38-5 banner spacing; the CHECK_FAILED warning now carries callerUserId.
- Accepted: S38-2 (a hostile colleague can lock a nurse's PIN for 15 minutes about once a week per colleague per hostile account; HARD-2 should notify the owner in-app), R38-2 (JVM monitor held during the colleague check; HARD-2), I38-3, I38-4.
- HUMAN decisions open: (1) R38-6 wording: T2 says "since your last use" but the count is since the last "Got it" ("since you last tapped Got it" is clearer?); (2) drop the now redundant `ix_staff_pin_colleague_event_pair` in a later migration (optional; saves one index entry per insert); (3) sign-off of the new texts T1-T12, the failed-"Got it" toast and the screen-reader announcements; (4) go-live: SAHAJ_PIN_PEPPER secret, HARD-2 with SAH-38, OQ-38-5 ward procedure, OQ-38-6 release note (older "wristband scanned" rows did not prove a scan; Medicines-tab legend still says "wristband scan").
