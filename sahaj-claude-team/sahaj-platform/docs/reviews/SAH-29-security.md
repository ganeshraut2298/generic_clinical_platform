# SAH-29 security review · IPD packages (read-only slice, DEC-030)

Reviewer: security-reviewer, read-only. Date: 10 Nov 2026. Done by reading the code (compared with HEAD, plus the untracked SAH-29 files); no builds or tests were run.

**Verdict: PASS WITH CONDITIONS.** No Critical or High finding. The conditions are the human's governance items (P1, billing confirmation); they need no code change.

## Findings
| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| S-1 | Medium (accepted risk; go-live gate) | V2026_12_05_1000__ipd_packages.sql:4-6, 52-55; DEC-030 P1 | The only way to attach a package to an admission, which changes the patient's bill, is a hand edit in the database, with no `platform.audit_event` row and no actor. The set-once trigger stops later changes and blocks DISCHARGED rows, but the first set is not audited. | Human (P1) | Keep P1 as a go-live gate: SAH-29 is for `sahaj_dev` and `sahaj_test` only until create and attach exist with an audit entry (`IPD_PACKAGE_ATTACH`, ids and amounts only). Recorded for the merge approval. |
| S-2 | Low | PackageService.java:27-32; PackageController.java:26-27 | The role list is enforced only by `@PreAuthorize(AdmissionController.READERS)`; the service checks the module state, not the role. Same as the existing read pattern. A future internal caller would skip the role check. | java-developer | Accept now. When the write path arrives, pass `role` into the service and `requireRole`. |
| S-3 | Low (DPDP note) | AdmissionViews.java:227; RunningBill (packageLines) | A package name can imply a procedure. It goes to the same four roles that already get `bill`. `Detail.toString` hides it; nothing is logged. The UI draws no field from `package` (AC4). | solution-architect | Accept. When writes come, the contract says package names are never logged or put in audit `after_json` as free text. Optional: drop `AdmissionDetail.package` if no screen uses it. |
| S-4 | Low (info) | V2026_12_05_1000__ipd_packages.sql:57-69 | `add constraint ck_admission_package_all_or_none` without `NOT VALID` takes an ACCESS EXCLUSIVE lock while it validates. Fine at phase-1 sizes. | db-engineer | Backlog: `NOT VALID` plus `VALIDATE` for big tables later. |

## Checked and passing
- **Endpoint and tenant.** `GET /api/v1/ipd/packages` is the only route (POST, PUT and DELETE are tested as absent). The tenant comes from the signed-in user; the SQL uses a named parameter and RLS also applies. Module Off gives 403 `module-off` after `@PreAuthorize`. No logger, no audit or record-access row (a package is not patient data).
- **Migration.** `ipd.package` has ENABLE and FORCE RLS and a SELECT-only policy and grant; truncate refused; check constraints on name, price (paise, whole rupees, at most 1e8) and days (0 to 60). The three admission columns have no app grant (the earlier column-level grants do not name them); the set-once trigger refuses change, clear and a DISCHARGED set (`SECURITY INVOKER`, `search_path = pg_catalog`, EXECUTE revoked). Nothing dropped, renamed or backfilled.
- **AdmissionDetail.package.** Same four roles that already get `bill`; no new exposure beyond the bill. A discharged sheet keeps the stored copy.
- **Money maths.** `long` paise; `HALF_UP` with `longValueExact`; days beyond the package never negative; zero-quantity line dropped; no overflow risk.
- **Frontend.** No innerHTML, sanitiser bypass, console or storage; a 403 clears the list; no client-side billing logic.
- **Tests and dependencies.** Fictional data; pom.xml, package.json and the lockfile unchanged.

Not verified: `npm audit`, `mvn dependency:tree`, the migration in a live run (inferred from QA's `validate backend`), how humans hand-edit `sahaj_dev`, a real-backend-plus-real-Angular test, and the billing correctness of OQ-29-5 and OQ-26-4 (a business decision).
