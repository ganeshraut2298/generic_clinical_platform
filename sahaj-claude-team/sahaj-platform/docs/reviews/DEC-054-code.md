# Code review · DEC-054 fixes (branch feature/SAH-39) · 15 Nov 2026
Reviewer: code-reviewer (read-only; could not run Maven/javac/tests; did not run the live script). Verdict: **APPROVE WITH COMMENTS**. Blocker/Major: none.

| # | Sev | Where | Finding | Owner |
|---|---|---|---|---|
| 1 | Minor | BillingService.java:108-119, VisitBillingAdapter.java:61-70 | 30-day counter lookback is an N+1 on every poll (findBetween takes all visits, bill lookups per visit). Filter to Billing/Done stages in the query, compute balance in one SQL, cap newest ~200, measure. | java |
| 2 | Minor | same | 30-day cut-off is a silent business rule not in billing.yaml or SAH-10 AC5; unpaid bills older than 30 days vanish. Comment cites AC5 wrongly. **Human decision**; amend SAH-10/contract. | human, architect |
| 3 | Minor | BillingService.java:111-113 | Needless local; EARLIER_DAYS mid-class; order = today first then oldest first (oldest dues should be first). | java |
| 4 | Minor | VisitBillingAdapter.java:63-66 | `LocalDate.now` computed twice (midnight race). Compute once. | java |
| 5 | Minor | tests | Missing: today + earlier together in order, day-30/31 edge, partial payment on an earlier day. | java (unit) |
| 6 | Minor | ProblemExceptionHandler.java:385-402 | 503 mapping sound, deadlock stays 500. Optionally soften text to "This attempt was stopped"; check no more specific handler catches Spring wrappers first. | java |
| 7 | Minor | application.yml:42-47 | Global 30 s statement_timeout may cut long report queries; comment mentions a `set local` override that exists only in StaffPin; test checks YAML text only. Add `SHOW statement_timeout` IT. | java, qa |
| 8 | Minor | docs/api | 503 database-busy not in contracts (architect chore running). | architect |
| 9 | Info | SpecialtyDone.java:93-100 | Re-read the `systems` loop; add a guard/test with 5 entries per object. | java |
| 10 | Minor | ExamGeneralPurePathTest.java:55-69 | An unrelated assertion was removed without explanation; use assertTimeoutPreemptively so a returning bug fails not hangs. | java |
| 11 | Nit | StaffPinOpsTest | Fixed UUIDs: good. | — |
| 12 | Minor | frontend/e2e/live/opd-journey.mjs:36 | A concrete 10-digit mobile number is hard-coded as default; require SAHAJ_OWNER_MOBILE from env. | qa |
| 13 | Minor | opd-journey.mjs:35,160-164 | Script has no guard against a non-dev target; refuse unless base is localhost/127.0.0.1. | qa |
| 14 | Info | opd-journey.mjs | No DELETE, no DB command; no token/OTP/cookie printed or stored; paths redacted; state file ignored. `frontend/e2e/` is entirely git-ignored (script untracked): human to decide. | human |
Also: whole-project `mvn verify` and `validate all` still to run when the build locks are free.
