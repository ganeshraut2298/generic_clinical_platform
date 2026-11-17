# Security review · DEC-054 fixes (branch feature/SAH-39) · 15 Nov 2026
Reviewer: security-reviewer (read-only; no Maven, npm audit or runtime check). Verdict: **PASS**. 0 Critical, 0 High, 2 Medium, 7 Low/Info.

| # | Sev | Where | Finding | Fix / owner |
|---|---|---|---|---|
| M1 | Medium (availability) | BillingService.java:110-120, VisitBillingAdapter.java:62-70,108-112 | 30-day lookback loads every visit in any stage and filters in Java: ~4-5 queries per visit, 12-15k queries per counter load for 100 visits/day, one connection, shared pool of 10 for all tenants; any clinic role can refresh. | Filter in SQL (stage BILLING/DONE, balance > 0), batch lookups, cap rows, rate limit. java (in progress) |
| M2 | Medium (personal data) | opd-journey.mjs:36; DEC-054:7 | A real owner mobile number was a hard-coded default and written in DEC-054 (in history since commit 5151b7e, unmerged). | Script: require SAHAJ_OWNER_MOBILE (qa, in progress). DEC-054: number removed by tech lead 15 Nov. Human decides whether to rewrite unmerged commit 5151b7e. |
| L1 | Low | application.yml:43 | `options` string built from env vars; a value with spaces could add `-c` settings. Operator-controlled. | Bind the *_MS env vars as integers. java |
| L2 | Low | Problems.java:88-93, ProblemExceptionHandler ~380/393 | "Nothing was saved" not guaranteed if an earlier transaction in the same request committed; fixed Retry-After 5 with no jitter. | Soften text; frontend jitter. java, angular |
| L3 | Low (DPDP) | BillingController.java:34-38 + BillingService.java:110 | Counter now shows names and balances for 30 days to every clinic role incl. NURSE, PHARMACIST, COUNSELLOR. | Check SAH-10 AC5 / mockup roles; differences go to the human. |
| L4 | Low | ProblemExceptionHandler.java:393 | Busy log line has no tenantId/requestId unless MDC adds them. | Confirm MDC. java |
| L5 | Low (hygiene) | branch | Build output committed: backend/target-devtools/ (2,846 files), root node_modules/.vite, .vitest-cache. | Human: .gitignore + remove from branch. |
| L6 | Low | ExamGeneralPurePathTest.java:~58 | An assertion was deleted. | java (in progress) |
| L7 | Info | .gitignore:8 | `frontend/e2e` fully ignored: script and QA e2e specs untracked. | Human: narrow to `frontend/e2e/live/*.state.*.json`. |
Clean: DB timeouts (request pool only, tenant binding unaffected), DatabaseBusy mapping, earlier-visits query (tenant-filtered, RLS, no IDOR), SpecialtyDone fix (also removes a CPU denial of service), live script and state file (no tokens/OTP/cookies; synthetic data), no secrets or SAH-42 PIN in changed files.
Not verified: mvn verify, npm audit, dependency tree, runtime 503 behaviour.
