# SAH-34 QA follow-up after the review fix pass (10 Nov 2026) - INTERIM, updated as steps finish

Owner: qa-engineer. Written early because earlier QA agents were cut off by network drops. The finished results are in `docs/test-plans/SAH-34.md` section 6 and `docs/reviews/QA_REPORT_SAH-34.md` section 10.

| Step | Task | State |
|---|---|---|
| 1 | `EmergencyAccessIT`: picker audit row, count 0, refused reads write nothing, read-only and IPD Off order, audit failure gives 500 (forced with a trigger on `platform.audit_event` for this test's tenant only) | DONE, 20/20 pass |
| 2 | `ScopePlansIT` rewritten on the repositories' own statements (`RealSql`), work-count budgets, S2b, R4, R4x, R4d, DOSES_BETWEEN, request time | DONE, passes (scoped nurse `GET /clinical-events` 15 ms, was 200 ms; S2b 0 rows read, was 3,650) |
| 3 | New `ScopeReviewFixIT` (7): owner `bg:` token, identical rows, guard order, discharged reads | DONE, 7/7 pass; `SafetyChangeTokenIT` 9/9 unchanged |
| 4 | `ScopeRaceIT`: 30 s margin, precondition, lock-waiter `%ipd.admission%` | DONE, 6/6 pass |
| 5 | Slug flake: `UniqueSlugs` | DONE; cause probable (32-bit slug against a never-cleaned database), not proven |
| 6 | `packages.spec.ts` module-off text; two focus tests in `ward-scope.spec.ts` | DONE, `ward-scope.spec.ts` + `packages.spec.ts` 172/172 pass |
| 7 | `validate backend` and the reports | DONE: `validate backend` PASSED (unit 3,763; integration 1,938 in 142 classes; 0 failed; 26 pre-existing skips) |
