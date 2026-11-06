# Security review · SAH-19, SAH-20, SAH-21

Reviewer: security-reviewer (read-only), 5 Nov 2026. Verdict: **PASS** (no Critical or High). 3 Medium, 7 Low.

Passed: RLS enabled and forced on all 5 new tables with single tenant policy and minimal grants (no delete on entitlement, attendance, leave); client-supplied membership ids resolved through TeamMemberPort with explicit tenant filter (no FK needed); leave ids tenant-scoped, approve/decline owner-only with OwnerGuard; role gates match contracts; privilege escalation paths closed (trial cannot restart, non-owner cannot switch HRA/IPD); all hra endpoints gated by module-off; audit rows without names or reasons; parameterised SQL; advisory locks cannot deadlock; no frontend XSS sinks; earlier booking lock and OTP guard intact.

| # | Sev | Finding | Fix |
|---|---|---|---|
| M1 | Medium | Owner IPD trial switch defaults to true in base config (DEC-010 says lock before real use; nothing enforces it) | Default false; true only in dev/test profiles (or startup guard) |
| M2 | Medium | Leave: no cap on `from` ahead (story says 60 days), no cap on pending per person, unbounded owner list, N+1 countBooked | Reject from > today+60, cap pending (e.g. 20), grouped count |
| M3 | Medium | `LeaveRow` / `LeaveView.Request` records print `reason` in toString (staff health data) | Override toString without reason |
| L1 | Low | Attendance mark trusts token role (no re-read) | Re-read membership/role |
| L2 | Low | Owner leave view decided by token role | Optional re-read |
| L3 | Low | ModulesService read-modify-write race | for update / column update |
| L4 | Low | Booking vs leave approval race; accepted by story | Accept |
| L5 | Low | Control characters / NUL in reason give 500 | Refuse with 400 |
| L6 | Low | Profile defaults to dev; guard passes `dev,prod` | Guard refuses dev with other profiles |
| L7 | Low | roster_shift has DELETE grant, unused | Drop in later migration |

Also noted: `node_modules/.vitest-cache` content appears in the git diff stat (accidentally tracked build artefact; remove from the branch before commit; humans own .gitignore).
Not checked: dependencies, test assertions, role/BYPASSRLS setup scripts, tenant binding chain, rest of the diff vs main.
