# SAH-29 code review · IPD packages (read-only slice, DEC-030)

Reviewer: code-reviewer, read-only. Date: 10 Nov 2026. Done by reading the code (no builds or tests). Only SAH-29 files were judged.

**Verdict: APPROVE WITH COMMENTS.** No Blocker, no Major.

## Confirmed
- **Bill lines (AC 3)** match the mockup `ipLines` line by line (`RunningBill.packageLines`): literal texts including "for 0 days" and "1 days" (OQ-29-4); order package, bed beyond, extras; beyond-days = max(0, billDays - max(1, includedDays)); extras at base x the current multiplier, HALF_UP; no doctor-visit, nursing or segment lines; the non-package path is unchanged (null-package test).
- **One code path:** the sheet, Admitted list, board, tiles and discharge all go through `IpdBilling.Batch.bill`; a discharged sheet carries the stored copy.
- **Discharge default (AC 5)** equals the mockup order (setup template, maternity, day-care, package gives surgical, else general).
- **Contract, database, modules:** `GET /packages` and `AdmissionDetail.package` match; check order is role, then module; the migration is forward-only with FORCE RLS, select-only grants and a correct set-once guard; everything stays in `com.sahaj.ipd`; no logging.
- **Tests** are meaningful, none skipped or weakened (expected values are written from the story, not read back from the app). **Frontend:** signals, OnPush, HTTP only in `admissions.api.ts`, panel matches mockup line 7272, bill `@for` tracks by `$index`.

## Findings
| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| 1 | Minor | _console.scss:983-987 (D-3) | `.notice > .mat-mdc-button-base {min-height:44px}` at 620 px and narrower is global: at least 15 other screens put a stroked button in a `.notice` and grow from 38-40 px to 44 px on phones. Meets the 44 px rule but is an unreviewed change outside SAH-29. | angular-developer / QA | QA's running re-test includes the layout matrix and `.notice` specs; keep if nothing breaks, else scope it. |
| 2 | Minor | _console.scss:233-236 (D-2) | `overflow-wrap:anywhere` on `.li .main b` is shared by every `.li` list; could break long words that used to overflow. | angular-developer / QA | Same QA re-test. |
| 3 | Minor | AdmissionViews.java:226; AdmissionRepository.java:74 | The repository record `PackageCopy` is serialised directly as the API shape. Should be a contract DTO `AdmissionViews.AdmissionPackage`. | java-developer | FIX before merge, after the SAH-34 backend build (same files). |
| 4 | Minor | AdmissionRepository.java:90-105; AdmissionPackageTest.java:79-85 | `AdmissionRow` has 16 components and three constructors; `AdmissionPackageTest` uses the 20-argument `AdmissionService` constructor, which SAH-34 is editing. | java-developer | The SAH-34 developer updates the test setUp; consider a test builder. |
| 5 | Minor (process) | branch; docs/approvals.md | SAH-29 sits on the SAH-39 branch in a mixed tree; no `SAH-29 | stories` or `contract` lines. | Human | Split or commit by story; add the lines. |
| 6-8 | Nit | packages.store.ts:36-41, 46, 63-67; ipadm.page.ts:13-23, 28-33 | `load()` inside `effect()` should be `untracked`; the `refused` signal is set but only the spec reads it; import order and a stale doc comment; module-off shows a Try again button that cannot help. | angular-developer | Backlog, batch with the SAH-34 frontend pass |
| 9-10 | Nit | JdbcPackageRepositoryTest; PackageControllerTest:98-107 | The repository test only checks SQL substrings; the no-write-route test accepts any 4xx (assert 405 or 404). | java-developer | Backlog |
| 11 | Nit | migration :50 | `trg_package_no_truncate` reuses `deny_audit_mutation()`, so the message says "append-only". Documented. | db-engineer | None |

Not verified by the reviewer: any test or browser result after the D-1 to D-3 fixes (QA's 70/70 re-run was pending), the shared-style effects (findings 1 and 2) in a browser, the `ipd.yaml` parse, and the billing questions (OQ-29-5, OQ-26-4, P1), which are the human's.
