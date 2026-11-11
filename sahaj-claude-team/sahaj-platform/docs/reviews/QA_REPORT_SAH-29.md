# QA report · SAH-29 IPD packages (read-only slice, DEC-030)

Status: **COMPLETE, RE-TEST DONE** (verdict below).
Branch: feature/SAH-39-home-kinetic-background (mixed tree, not switched). First QA pass 10 Nov 2026 (third QA agent; two earlier ones were cut off). Re-test after the fixes of D-1, D-2 and D-3: 10 Nov 2026, 10:11 to 11:05 IST. Test plan: `docs/test-plans/SAH-29.md`.

## Verdict
**FINAL: PASS.** Every acceptance criterion has a passing test, and the three Low defects found in the first pass (D-1, D-2, D-3) are fixed and proven in the browser.
- `packages.spec.ts`: **70 of 70, three runs in a row**, both projects.
- Whole e2e folder: **2,469 passed, 45 skipped (by design), 0 failed** (2,514 runs, 44.3 min).
- Lint clean; 168 files and **2,614** unit tests pass; build passes.
- Backend: unchanged for SAH-29 since the green gate (49 new integration tests, 0 failures); not re-run (section 6, item 5).

Still open and not QA's to close: the human's billing confirmation (OQ-29-5 / OQ-26-4) and the go-live limit (P1).

First-pass verdict, kept for the record: PASS WITH 3 OPEN LOW DEFECTS (64 of 70 browser runs passed; the 6 failures were D-1, D-2, D-3 in both projects, identical in 3 runs).

## 1. Validation matrix
| Check | Result | Evidence |
| --- | --- | --- |
| `validate backend` (unit + integration on `sahaj_test`) | **PASSED** (first pass, rc=0, 05:57); not re-run in the re-test | unit: 229 classes, 3,594 tests, 0 failures, 0 errors, 0 skipped. Integration: 132 classes, 1,818 tests, 0 failures, 0 errors, 26 skipped (DevSampleAdminFunctionIT 19, DevToolsGuardCallbackIT 5, DevToolsCleanupScriptIT 1, PatientsIT 1: environment-guarded, not SAH-29). PackageListIT 15, PackageTableIT 15, PackageBillIT 19, all green. |
| Frontend lint (`ng lint`) | **PASSED** ("All files pass linting") | re-test 10:13 |
| Frontend unit tests (`ng test --watch=false`) | **PASSED**: 168 files, **2,614 tests** (2 new for D-1) | re-test 10:14 (first pass 2,612) |
| Frontend build (`frontend/dist` rebuilt) | **PASSED** | re-test 10:13 |
| eslint on `frontend/e2e/packages.spec.ts` | **PASSED** (first pass; the spec is unchanged since) | |
| `packages.spec.ts`, 3 runs in a row, both projects (70 runs each) | **70 of 70 pass, 3 of 3 runs** (first pass: 64 pass, 6 fail) | re-test, 59 s, 66 s, 78 s |
| Mutation check of the two CSS fixes (re-test) | **detected**: old CSS put back in a scratch copy of the build, D-2 and D-3 tests: 4 of 4 fail again (page 416 px beyond the viewport; Try again 38 px) | section 5 |
| Whole e2e folder, once (4 workers, 2,514 runs) | **2,469 passed, 45 skipped, 0 failed** (44.3 min, 10:20 to 11:04) | section 5. Includes `bed-transfer.spec.ts` (46) with the F-1 mock, `layout-matrix.spec.ts` (all 7 layout projects plus the 2 zoom projects) and every spec that draws a `.notice` button or a `.li` list. The 45 skips are by design (project-specific skips and guarded measure specs, as in the SAH-37 report). The first pass had 2,447 passed, 45 skipped, 22 failed (6 defect runs + 16 `bed-transfer.spec.ts` runs, F-1). |
| `validate frontend` | **FAILS: known tooling problem, not fixed** | the `--browsers` flag (reported by the developers; `scripts/` is human-owned). Its parts (lint, unit tests, build) were run by hand and pass. |
| `validate docs` | **FAILS: known, not fixed** | `ADM-2.md` (not SAH-29; `docs/` rule file) |

## 2. Acceptance criteria to tests
In full in `docs/test-plans/SAH-29.md`. Summary:

| AC | What | Tests | Result |
| --- | --- | --- | --- |
| 1 | Packages panel: order, chip / name / fixed text / price, active only, read-only, empty state, four roles | PackageListIT (15), packages.spec.ts rows, control-free, keyboard, empty, loading, states, D-1 to D-3 | pass (layout edges D-2 and D-3 now pass too) |
| 2 | Admission keeps its own copy, set once, never changed by list changes | PackageTableIT (copy, guard, grants), PackageBillIT copy test, discharged freeze | pass |
| 3 | Bill lines and maths (day-care, 1, 3 days; beyond; extras; no visit / nursing / segment) | PackageBillIT (9 tests), e2e bill tests (375 px and desktop) | pass |
| 4 | Sheet, list, pill, tile, board use one bill; heading note and summary strip unchanged | PackageBillIT (2 tests), e2e heading-note, deposit-low | pass |
| 5 | Transfer: no segment line, bed difference not billed; discharge template "Surgical" | PackageBillIT (5 tests) | pass |
| 6 | Roles, module states, tenants, RLS, grants, no write route, paise | PackageListIT, PackageTableIT, PackageBillIT, e2e 403 cases | pass |

## 3. Test inventory (new in this story)
| File | Tests |
| --- | --- |
| `backend/src/test/java/com/sahaj/platform/it/PackageSupport.java` | shared set-up (not a test) |
| `.../it/PackageListIT.java` | 15 |
| `.../it/PackageTableIT.java` | 15 |
| `.../it/PackageBillIT.java` | 19 |
| `frontend/e2e/packages.spec.ts` | 35 cases x 2 projects = 70 runs |
| `docs/test-plans/SAH-29.md` | the test plan |
Developer tests (not mine): backend unit 34 new, frontend unit in `packages.store.spec.ts` (now with the D-1 guard tests), `ipadm.page.spec.ts`, `admission-dialog.component.spec.ts`, `ipd.model.spec.ts`, `admissions.api.spec.ts`.

## 4. Defects and findings
| ID | Severity | Where | What | Owner | Status after the re-test |
| --- | --- | --- | --- | --- | --- |
| D-1 | Low | `packages.store.ts` `load()` | No "already loading" guard: two click events in the same instant on Try again started two reads. | angular-developer | **FIXED, verified.** `load()` returns at once while loading. e2e `D-1: two click events in the same instant on Try again send one request` passes in both projects (3 of 3 runs). |
| D-2 | Low | global `.li .main b` rule | An 80-character name with no spaces made the page 791 px wide at 375 px (416 px of sideways scroll). | angular-developer | **FIXED, verified.** `overflow-wrap: anywhere` on `.li .main b` (`_console.scss`). Test passes in both projects. With the rule removed from a scratch copy of the build, the test fails again with the same 416 px, so it passes for the right reason. |
| D-3 | Low | notice buttons (Try again) at 375 px | 38 px high; rule is 44 px. | angular-developer | **FIXED, verified.** `.notice > .mat-mdc-button-base { min-height: 44px }` inside the 620 px media query. Test passes in both projects. With the rule removed from a scratch copy, the test fails again with 38 px. |
| F-1 | Fixed (my spec) | `frontend/e2e/bed-transfer.spec.ts` | The "Bed transfer rules" tests did not mock the new `GET /ipd/packages` call. Not a product defect: the spec now answers it with an empty list. | qa-engineer | Fixed; passes in the whole-folder run (46 of 46). |
| N-1 | Info, pre-existing | admission sheet (SAH-25 deposit and charge forms) | "Collect" and "Add" are 40 px high at 375 px. Not changed by SAH-29. Recorded as an annotation in `packages.spec.ts`, not asserted. | angular-developer (backlog) | open, unchanged. Note: the D-3 rule is `.notice > .mat-mdc-button-base`, so any other button placed directly in a `.notice` is now 44 px on phones; no spec measured a change that failed. |
| N-2 | Info | process | `docs/approvals.md` has no `SAH-29 | stories` or `contract` line yet (the human adds them). The hook did not stop the work because the human went ahead in chat (DEC-030). | human | open |

Test-side mistakes found and fixed in my own spec during the first pass (not product defects): the Enter / Space keyboard test pressed the Sign out button that was focused after 25 Tabs on the phone project (now clicks the row first); a `Close` locator matched two buttons (now `exact` + `first`); the "every control 44 px in the sheet" test caught SAH-25 buttons (narrowed to the SAH-29 bill, N-1 noted); the double-click test was split into a passing "second click while it runs" test and the D-1 test.

Known and not a defect: "a failed second read keeps the stale list" cannot happen in the browser (Try again exists only while an error shows). The `PackagesStore` unit tests cover it.

## 5. Browser runs and mutation checks
| Run | Result |
| --- | --- |
| First pass: first run of `packages.spec.ts` | 57 pass, 11 fail (5 of my mistakes x projects, plus D-1 to D-3) |
| First pass: after my fixes, 3 runs in a row | 64 pass, 6 fail each time (D-1, D-2, D-3 in both projects) |
| First pass: mutation 1, "Day-care" to "Day care" in the built chip | 8 failed |
| First pass: mutation 2, "Bed, nursing and surgeon included" to "Bed and nursing included" | 8 failed |
| First pass: mutation 3, "Loading packages" to "Loading" | 2 failed |
| First pass: whole e2e folder | 2,447 passed, 45 skipped, 22 failed (6 defects + 16 F-1) |
| **Re-test: `packages.spec.ts` x3, 4 workers** | **70 of 70 each time** |
| **Re-test: mutation 4 (old CSS back)** in a scratch copy of the new build (the two rules removed with `sed`), `-g "D-2\|D-3"` | **4 failed** (Expected <= 0, received 416; expected >= 43.5, received 38), in both projects. D-1 is a script behaviour (two GETs vs one); its proof is the first-pass failure ("expected two GETs, got three in all") and the new passing run; it was not re-mutated because that needs a rebuild of the app. |
| **Re-test: whole e2e folder, once** | **2,469 passed, 45 skipped, 0 failed** (44.3 min) |

Layout side effects (check of step 3): the whole run includes `layout-matrix.spec.ts` (fit and targets checks at 1920, 1366, 1024, 768, 375, and two zoom sizes), `work-area-width.spec.ts`, `admitted-cards.spec.ts`, the Clinic setup IPD pages (`bed-transfer`, `patient-card-setup`), and every spec that opens a Try again notice or a `.li` row. All of their measured widths, heights and no-overflow checks pass unchanged; no assertion was relaxed and no spec changed in the re-test. So the two shared rules moved nothing that the existing tests measure. The only visible change is the intended one: a phone-width Try again (and any other button placed directly in a `.notice`) is 44 px high.

Temporary copies of the build are in the scratchpad (`qa29-mutB`, `qa29-mutC`, `qa29-mutD` from the first pass; `qa29r-mut-<time>` from the re-test), not in the repository.

## 6. Risks and human items
1. **Billing confirmation (OQ-29-5, OQ-26-4), the human's before go-live.** After a transfer, a dearer bed adds no bed-difference charge and extra charges are re-priced at the current bed's multiplier (as the mockup). The tests prove the built behaviour equals the mockup; they cannot say it is right for a hospital.
2. **P1: not for a live clinic.** Packages and the admission copy can only be set by hand in the database (no create, edit or attach; no audit). The set-once guard and grants are tested. Do not use SAH-29 in a live clinic until OQ-29-1 and OQ-29-2 are built.
3. **OQ-29-4:** the literal "for 0 days" / "1 days" wording is kept and tested; a one-line change if the human prefers "for the day" (the tests would change with it).
4. **Mocked browser tests.** `packages.spec.ts` mocks the API, so browser and server are proven separately; there is no test with the real backend and the real Angular app together for this story (the repository has no such harness for IPD screens).
5. **Backend not re-run in the re-test (as instructed).** Files compared by time: every SAH-29 backend source file (`RunningBill`, `IpdBilling`, `AdmissionViews`, `DischargeRules`, `PackageService`, `PackageRepository`, `PackageViews`, `JdbcPackageRepository`) is dated 04:11 to 04:12 and the migration `V2026_12_05_1000__ipd_packages.sql` 19:49 of the day before, all before the green gate of 05:57; no file under `backend/src/test` changed after 05:57. One SAH-29 file did change: `PackageController.java` (10:17), where the SAH-34 java-developer added the `@ScopeExempt("Configuration: no patient data")` annotation (visible on line 25). That is outside this story and covered by the SAH-34 run; it should be confirmed by that story's own gate (the SAH-29 `PackageListIT` is part of the integration suite it runs).
6. **Tracked screenshots under `docs/test-plans/*-shots`:** see section 7.

## 7. Working tree
- The whole e2e run re-draws the screenshots of the audit specs (`audit-sah35-36-38.spec.ts`, the SAH-39 specs) on every run. After this re-test, `git status` shows 32 modified `.png` files under `docs/test-plans/*-shots` (they were already modified before this QA work began). I did not restore them (instruction). Whoever commits should decide: commit the new images or `git restore` them; they carry no SAH-29 content.
- Files I changed in the re-test: `docs/reviews/QA_REPORT_SAH-29.md` and `docs/test-plans/SAH-29.md` only. No product code, no spec, no database change. `frontend/dist`, `frontend/e2e/report` and `frontend/e2e/results` are build output.
- The frontend lock was taken at 10:11 and released at 11:05.

## 8. Proposed commits (QA's part only; the human commits)
The e2e folder is ignored by `.gitignore` (`frontend/e2e`), so new files need `git add -f`.
1. `test(it): SAH-29 package list, table and bill integration tests` — `git add backend/src/test/java/com/sahaj/platform/it/PackageSupport.java backend/src/test/java/com/sahaj/platform/it/PackageListIT.java backend/src/test/java/com/sahaj/platform/it/PackageTableIT.java backend/src/test/java/com/sahaj/platform/it/PackageBillIT.java`
2. `test(e2e): SAH-29 packages spec; bed-transfer spec answers the packages call` — `git add -f frontend/e2e/packages.spec.ts frontend/e2e/bed-transfer.spec.ts` (both are ignored by git; `bed-transfer.spec.ts` is not tracked yet either)
3. `docs(test): SAH-29 test plan and QA report` — `git add docs/test-plans/SAH-29.md docs/reviews/QA_REPORT_SAH-29.md`
The developers' product files (backend `Package*` classes, `RunningBill` and friends, frontend `packages.store.ts` and spec, `ipadm.page.*`, `src/styles/_console.scss` with the D-2 and D-3 rules) belong to their own commits and are listed in `docs/handoffs/SAH-29.md`. `_console.scss` is a global stylesheet: it should go in the SAH-29 frontend commit, not in a SAH-34 or SAH-39 one. I did not commit anything.

## 9. Handoff
Story SAH-29. From qa-engineer. Next: code-reviewer and security-reviewer (read-only).
- Verdict: PASS (re-test). Nothing is handed back to the angular-developer.
- For the reviewers: the shared-style side effect (a global `.li .main b` wrap rule and a phone-only `.notice > .mat-mdc-button-base` height rule); security points: the read endpoint's role list and tenant scoping (`PackageController`, `JdbcPackageRepository`), the grants and the set-once trigger (`V2026_12_05_1000__ipd_packages.sql`), that no patient data reaches logs.
- For the human: billing confirmation (OQ-29-5 / OQ-26-4), P1 go-live limit, OQ-29-4 wording, `stories` and `contract` lines in `docs/approvals.md`, decision on the 32 re-drawn tracked images.
