# DEC-058 · Go-live next week: scope and team set-up

Date: 17 Nov 2026. Status: ANSWERED (B). Raised by: tech lead.
Request (human, chat): deploy several agents in parallel, one lead per developer, finish the remaining OPD and IPD work, do IPD unit and integration testing as a priority, production-level work, because the application goes live next week.

## Facts that shape the plan
1. **Write agents are blocked.** `.claude/hooks/guard-ownership.mjs` treats every subagent as `tech-lead` (line 12 uses `input.agent_id`, and `.claude/settings.json` sets the main agent to tech-lead). Until the human adds after line 12:
   `if (agent === 'tech-lead' && input.agent_id) process.exit(0);`
   no developer, db or QA agent can write. Read-only reviewers work.
2. **One writer per area.** The same hook has an area lock (section 3), so at most one java-developer, one angular-developer, one db-engineer, one qa-engineer, one architect write at the same time. Parallel = 5 writing lanes plus read-only reviewers, not "N java developers". There is no separate tech-lead subagent: the main session leads every lane.
3. **One Maven run at a time** (shared `target/`, `sahaj_test`): QA lanes take turns on `mvn verify`.
4. **Human-only go-live items no agent can close:** OTP delivery outside dev (notifications are Later in phase 1); clinical sign-offs (early warning bands OQ-30, medicine catalogue and allergy matching DEC-017, PIN lockout DEC-018); medicine code table empty (ADR-027 Q1); approval lines; screen-map rows; production hosting in India, TLS, backups and restore drill, source IP retention 180 days (CERT-In); privacy notice (DPDP); dev OTP bypass off; history rewrite of commit 5151b7e.
5. Read-only audits started 17 Nov: production security and operations (docs/reviews/GO-LIVE-security.md), IPD test gaps (GO-LIVE-ipd-test-gaps.md), OPD open work (GO-LIVE-opd-open-work.md).

## Options for "live next week"
- **A (recommended): pilot.** OPD live at one clinic with real patients; IPD in a supervised trial with test patients until its sign-offs and the IPD live journey pass. Pro: OPD is ~80% and proven end to end (34/35); IPD gets a week of testing. Con: IPD real use waits.
- **B: OPD and IPD both live with real patients.** Pro: meets the date. Con: IPD has never run end to end live; four clinical sign-offs open; empty medicine code table means the bedside medicine scan is overridden on every dose.
- **C: UAT / demo with test data only.** Pro: no patient-data risk. Con: not a real go-live.

## Recommendation
A. Work order once the hook is fixed: SAH-76 fix round and IPD tests (P0 from the gap list) first, OPD go-live blockers in parallel, then a live IPD journey on the dev server, then full gate and re-review.

## Answer (human, 17 Nov 2026)
**B: OPD and IPD both live with real patients next week.** Consequences recorded: IPD P0 tests and a live IPD journey are the top priority; every human-only item in "Facts" 4 must be signed or explicitly accepted by the human in writing before the first real patient; the tech lead keeps a go-live checklist from the three audits.
