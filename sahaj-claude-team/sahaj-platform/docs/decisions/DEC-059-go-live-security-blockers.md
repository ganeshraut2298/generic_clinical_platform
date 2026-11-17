# DEC-059 · Go-live security blockers vs the date (DEC-058 answer B)

Date: 17 Nov 2026. Status: ANSWERED (A). Raised by: tech lead from docs/reviews/GO-LIVE-security.md (verdict FAIL), GO-LIVE-opd-open-work.md and GO-LIVE-ipd-test-gaps.md.
Blocked: real patient data on any server; the production configuration story.

## Context
The human chose OPD + IPD live with real patients next week (DEC-058 B). The security audit finds blockers that no amount of agent work closes in a week on its own:
- **No production sign-in path.** Only the dev one-time-code sender exists; dev accepts any code for any mobile. Real SMS in India needs a provider account and TRAI DLT sender and template registration (usually more than a week).
- **No production profile, hosting, TLS, backups, restore drill, CERT-In log retention, privacy notice.** Public pages already promise daily backups kept 30 days.
- **IPD:** IPD switches on only through a 30-day owner trial, then becomes read-only; PIN pepper must be set; clinical sign-offs open.
- **Reported (unverified, git check denied):** commit 5151b7e with a real mobile is pushed to a GitHub remote.
Agent-doable in the week (once the ownership hook is fixed): prod profile and bypass hardening, OTP lock race and caps, source IP in logs, session limits, HARD-1 money-path keys, SAH-76 fixes and the IPD P0 tests, provider adapter once a provider is chosen.

## Options
- **A (recommended): keep next week as a supervised pilot on test data (C) while blockers close; first real patient when #1–#6, #10, #11 (+ #7–#9, #12, #13 for IPD) are closed.** Pro: no patient-data or legal exposure; agents work the code blockers in parallel now. Con: real-patient date moves by roughly the SMS/DLT lead time.
- **B: real patients next week with a stop-gap login** (for example a staff password or authenticator app instead of SMS). Pro: keeps the date. Con: new auth method = architecture change (new story, contract, tests, security review) inside a week; still needs hosting, backups, CERT-In and privacy notice done by the human.
- **C: real patients next week on the current build.** Not offered as viable: the only runnable profile accepts any code for any mobile.

## Recommendation
A. Start now on everything agents can close; the human starts the SMS provider + DLT, hosting, backups, privacy notice and the git history check in parallel.

## Answer
**A (human, 17 Nov 2026): next week is a supervised pilot on test data only.** The first real patient comes when the security Blockers are closed (GO-LIVE-security.md #1-#6, #10, #11; for IPD also #7-#9, #12, #13). SMS provider: not chosen yet; the human picks one and starts DLT registration. Agents build the provider-neutral parts now (prod profile, OTP hardening, `OtpSender` for prod behind a provider adapter added once chosen; a new external service needs its own story and contract). This supersedes DEC-058 answer B for the real-patient date.

## Tech lead defaults (human: "fix it, IPD first, end-to-end integration test, take responsibility for gaps", 17 Nov 2026)
Taken under that instruction; the human can overrule any of them.
1. **IPD switch (security #7):** an audited per-tenant runbook (db-engineer: `docs/database/runbook-ipd-on.sql`, run as `sahaj_migrator` with `app.tenant_id` set, writes an audit row) instead of the 30-day owner trial. The admin realm stays parked (needs role `sahaj_admin_definer`, human). No change to tenancy or module boundaries.
2. **Production profile and dev bypass (#1):** `application-prod.yml`; no default profile (fail at start); OTP bypass only for loopback callers and never in prod. Java only, no new library.
3. **Sign-in (#2, #3):** provider-neutral `OtpSender` contract for prod plus OTP lock race fix, daily failure count, request caps, `purpose` column. The SMS adapter itself waits for the human's provider choice (new external service = its own story).
4. **Hosting, TLS, backups, CERT-In (#4-#6):** architect writes a production deployment ADR and runbook (India region, TLS at proxy, separate prod database, backups + PITR + restore drill, log shipping 180 days, NTP); java adds client IP to the access log and the protocol header. The human provisions and runs them.
5. **Privacy notice and backup promise (#5, #10):** business-analyst drafts the notice text for the human's legal approval; the public "daily backups kept for 30 days" text stays only if the runbook delivers it (else the human changes `docs/design` and angular follows).
6. **IPD end-to-end:** QA writes `IpdJourneyIT` (15 steps, GO-LIVE-ipd-test-gaps.md 3.1) first; every gap it finds goes to its owner and is re-run.
