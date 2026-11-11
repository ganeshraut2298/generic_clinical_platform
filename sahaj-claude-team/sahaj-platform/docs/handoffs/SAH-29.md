# SAH-29 · IPD packages (read-only slice) — handoffs

Decision: DEC-030 (the human chose the read-only slice, 9 Nov 2026). Branch: the current working branch; the tree holds other stories' work.
Approvals: the human's chat go-ahead of 9 Nov 2026. The `SAH-29 | stories` and `contract` lines in docs/approvals.md are still the human's to add.

## solution-architect (9 Nov 2026)
- New design note docs/architecture/SAH-29-ipd-packages.md and ipd.yaml changes: GET /packages (OWNER, FRONT_DESK, NURSE, DOCTOR), the package copy on AdmissionDetail, and the running bill rules from the mockup `ipLines`.
- Stored shape: new table ipd.package (SELECT-only for the app), and three nullable all-or-none columns on ipd.admission with no write grant. A set-once trigger is recommended. Nothing destructive.
- The YAML was parsed by the tech lead with SnakeYAML: OK, 53 paths.
- Open points: DEC-030.

## business-analyst (9 Nov 2026, running)
- Aligns docs/stories/SAH-29.md with the read-only decision.

## Next
- db-engineer, then java-developer ∥ angular-developer, QA, and the reviews. These wait until SAH-37's QA and fix pass have released the backend lock, because a new migration would change the database under their Maven runs.

## business-analyst (9 Nov 2026): done
- docs/requirements/SAH-29.md (new) and docs/stories/SAH-29.md are aligned with DEC-030.
- AC5 now says that no segment line appears on the bill after a transfer.

## db-engineer (9 Nov 2026, running)
- Writes V2026_12_05_1000__ipd_packages.sql and docs/database/SAH-29.md. It waits for the backend lock before `validate db`.

## db-engineer (9 Nov 2026): migration written; validation inferred, not run by the author
- **Files:** V2026_12_05_1000__ipd_packages.sql and docs/database/SAH-29.md.
- **Built:**
  - ipd.package: FORCE RLS, SELECT only for the app, never deleted, seeds nothing.
  - Three nullable columns on ipd.admission: an all-or-none check and range checks; the app has no write grant.
  - A set-once trigger: it refuses a change or a clear, and refuses setting a package on a DISCHARGED admission, including by an insert.
- **Validation:** the author never got the backend lock, so it ran no `validate db` itself.
  - The SAH-34 db-engineer's `validate db` (exit 0, clean schema) ran after this file and the SAH-34 migration were both on disk and in target/classes (19:49 and 19:50), so it applied V2026_12_05 as well and passed. This is an inference from file times and from the engineer's note that V05 existed. If a precise proof is wanted, QA or the java-developer re-runs `validate db`.
- **Notes for the java-developer:**
  - The package list query is `... where active order by created_at, id`.
  - Add the three columns to the admission reads and map them as not insertable or updatable.
  - A test sets a package through the migrator: `no force` RLS inside one transaction, the insert or update, then `force` again, as `DischargeSupport.insertSignoffAt` does. The SQL is in docs/database/SAH-29.md section 4.
  - Do not insert a DISCHARGED admission with a package directly. Any test that lists the ipd tables needs `ipd.package` added.
- **Next:** java-developer ∥ angular-developer. They wait for SAH-37's QA to finish.

## angular-developer (10 Nov 2026): frontend built (read-only slice, DEC-030)
- **Built:** the read-only Packages panel in Clinic setup > Admission and deposits (under Bed transfer rules; mockup `cfgIpAdm`, line 7272). Rows show chip, name, fixed small text and price (shared `formatInr`). No button, input or link in the panel (a test checks). Empty state is the heading and an empty list (OQ-29-3). The bill sheet draws `bill.lines` as the server builds them, with no client billing logic; the bill `@for` now tracks by `$index` because two lines may share a name. `AdmissionDetail.package` is typed but not drawn (AC4).
- **Rules:** 403 clears the list (module-off uses fixed text); network and 5xx keep the stale list with "We could not load the packages. Try again."; no polling; no role check in the UI (four roles see the panel, the server refuses others).
- **Files:** features/ipd/ipd.model.ts, admissions.api.ts, config/ipd/packages.store.ts (+spec), ipadm.page.ts/.html (+spec), admission-dialog.component.html (+spec), ipd.model.spec.ts, admissions.api.spec.ts.
- **Results:** lint passes; 168 files, 2,612 unit tests pass; build passes; `validate frontend` fails only on the known `--browsers` flag.
- **Notes:** the loading line "Loading packages" is state text, not in the mockup (accepted, same pattern as the existing loading lines). A 403 on packages clears only the packages; the other panels on that page never handled a 403 (out of slice, backlog). `PackagesStore` does not call `access.ensureLoaded()` (page-scoped).
- **For QA:** hand-set 2-3 packages (a 0-day and a 1-day) and an admission with a package copy in sahaj_dev/sahaj_test; check the panel, the bill lines at 375 px and desktop, 403 module-off and forbidden, and that a 500 keeps the stale list. Selectors `#f-ippackages`, `.li`, `.tokchip`, `b.num`.
- **Waiting for:** the java-developer's backend.

## java-developer (10 Nov 2026): backend built (read-only slice, DEC-030); `validate backend` PASSED
- **Endpoint:** only `GET /api/v1/ipd/packages` (OWNER, FRONT_DESK, NURSE, DOCTOR; others 403 `forbidden`; module Off 403 `module-off`; no session 401). Query `where tenant_id = :tenantId and active order by created_at, id`, RLS-limited, no audit row, no logging. POST/PUT/DELETE are refused (tested). No contract change needed.
- **Detail:** `AdmissionDetail.package` (`{name, pricePaise, includedDays}` or null) for all four roles; a discharged sheet keeps it.
- **Running bill** (`RunningBill.compute`, the one path behind the sheet, list, board and tiles): line 1 `Package · <name> (bed, nursing and surgeon for <N> days)` at the package price; line 2 `Bed · <category> beyond the package` for max(0, billDays - max(1, includedDays)) days, dropped at 0; then `<service> · not in package` at base x the current multiplier, rounded half-up. No doctor-visit, nursing or transfer lines for a package admission. Literal "for 0 days" and "for 1 days" kept (OQ-29-4). The discharge default template is `surgical` for a package admission with no template set (not maternity or day-care).
- **Files:** application/PackageRepository, PackageService, PackageViews, infrastructure/persistence/JdbcPackageRepository, web/PackageController (new); AdmissionRepository (`PackageCopy`, 16th `AdmissionRow` component, old constructors kept), JdbcAdmissionRepository (read-only columns), RunningBill, IpdBilling, AdmissionViews, AdmissionService (3 small touch points), DischargeRules, DischargeService.
- **Tests:** unit 3,594, integration 1,769, 0 failures (new: RunningBillPackageTest 15, AdmissionPackageTest 6, PackageServiceTest 5, PackageControllerTest 6, JdbcPackageRepositoryTest 2, plus extensions).
- **Billing confirmation still the human's (before go-live):** OQ-29-5 and OQ-26-4 (a dearer bed after a transfer adds no bed-difference charge; extra charges re-priced at the current multiplier).
- **For SAH-34 developer:** AdmissionPackageTest builds `AdmissionService` with the current constructor; `AdmissionRow` has a 16th component `packageCopy` (pass `null` last or use existing constructors).
- **For QA:** ITs for RLS (another clinic's package not listed), app role cannot write `ipd.package` or the three admission columns, GET per role and module state, the package bill through a real transfer; set a package through the migrator (`no force` then `force` in one transaction); never insert a DISCHARGED admission with a package.

## qa-engineer (10 Nov 2026): PASS WITH 3 OPEN LOW DEFECTS
Report: docs/reviews/QA_REPORT_SAH-29.md; plan: docs/test-plans/SAH-29.md. `validate backend` PASSED (unit 3,594; integration 1,818; 0 failed; 26 environment skips; also proves the SAH-29 migration). Frontend: lint, 2,612 unit tests and build pass. New ITs: PackageSupport, PackageListIT (15), PackageTableIT (15), PackageBillIT (19). New spec packages.spec.ts (35 x 2 projects): 64 pass, 6 fail (the defect tests); mutation checks 3 of 3. Whole e2e 2,447 pass, 45 skipped, 22 failed (6 defect runs + 16 bed-transfer runs that did not mock the new GET /ipd/packages; QA added the mock, 46/46 alone; expected full run 2,463 / 6 failed). Every AC has a passing test; AC 1 layout edges fail on the defects.
- **D-1 (Low):** packages.store.ts `load()` has no "already loading" guard (two click events in the same instant send two GETs). Fix: `if (this.loading()) return;`.
- **D-2 (Low):** an 80-character name with no spaces makes the page 791 px wide at 375 px. Fix: `overflow-wrap: anywhere` on the name.
- **D-3 (Low):** Try again is 38 px high at 375 px (44 px rule); the Bed transfer rules panel's Try again too (shared notice-button style).
- N-1 pre-existing: "Collect" and "Add" in the admission sheet are 40 px at 375 px (not SAH-29). N-2: no SAH-29 `stories`/`contract` lines in approvals.md. 35 tracked images under docs/test-plans/*-shots changed again from the whole run (not restored).
- **Human items:** billing confirmation OQ-29-5 / OQ-26-4; P1 (SAH-29 is not for a live clinic: packages exist only by hand in the database); OQ-29-4 literal wording; approvals lines.
- **Next:** angular-developer fixes D-1 to D-3, QA re-runs packages.spec.ts (expected 70/70); security-reviewer (backend) now; code-reviewer after the fix.

## angular-developer (10 Nov 2026): D-1, D-2, D-3 fixed in code (browser proof is QA's)
- D-1: `load()` returns at once when already loading (packages.store.ts; 2 new unit tests). D-2: `overflow-wrap: anywhere` on `.li .main b` (frontend/src/styles/_console.scss; the rule is shared by every `.li` list). D-3: inside the existing 620 px media query, `.notice > .mat-mdc-button-base { min-height: 44px }` (covers the Packages and the Bed transfer rules Try again; desktop unchanged; any other button directly in a `.notice` is also 44 px on phones).
- Results: lint passes; 168 files, 2,614 unit tests pass; build passes.
- For QA: re-run packages.spec.ts (expected 70/70), bed-transfer, and check the shared-style side effects (other `.li` lists and `.notice` buttons: layout matrix and the specs that use them).

## security-reviewer (10 Nov 2026): PASS WITH CONDITIONS
No Critical or High. S-1 (Medium, accepted, go-live gate): attaching a package is a hand edit in the database with no audit row, so SAH-29 is not for a live clinic until create and attach exist with an audit entry (P1). S-2 service role re-check when writes arrive; S-3 package-name logging rule in the contract (optionally drop `AdmissionDetail.package`); S-4 NOT VALID pattern later. Saved in docs/reviews/SAH-29-security.md. Waiting on QA's re-test and the code review.

## code-reviewer (10 Nov 2026): APPROVE WITH COMMENTS
No Blocker or Major. Bill lines match the mockup line by line. Findings 1-2 (global D-2/D-3 styles) are being checked by QA's re-test; finding 3 (serialise a contract DTO instead of the repository record `PackageCopy`) to be fixed by the java-developer after the SAH-34 backend build; 4 handled by the SAH-34 developer (test setUp); 5 is the human's (approval lines, split commits); 6-11 backlog. Saved in docs/reviews/SAH-29-code.md.

## qa-engineer re-test (10 Nov 2026): PASS
D-1, D-2 and D-3 fixed and proven in the browser. Lint passes; 168 files, 2,614 unit tests; build passes. `packages.spec.ts` 70 of 70 three times (was 64). Whole e2e folder once: 2,469 passed, 45 skipped (by design), 0 failed (2,514 runs, 44.3 min), including bed-transfer, layout-matrix (9 projects) and every spec with a `.notice` or `.li`. Mutation: with the D-2 and D-3 rules removed from a scratch build both tests fail again (416 px overflow; Try again 38 px). No other layout moved; no product defect. Backend not re-run (no SAH-29 backend change after the green gate at 05:57); `PackageController.java` changed at 10:17 only by the SAH-34 developer's `@ScopeExempt("Configuration: no patient data")`, to be confirmed by the SAH-34 gate. The shared styles in `_console.scss` belong to the SAH-29 frontend commit. QA did not check whether Maven ran during its 10:11-11:05 Playwright window (the process list was denied); the folder result is 0 failed. 32 tracked images re-drawn again (not restored).
- **Remaining for SAH-29:** the java-developer's finding 3 (contract DTO for `package`) after the SAH-34 backend build; then the human: billing confirmation OQ-29-5 / OQ-26-4, P1 (not for a live clinic), OQ-29-4 wording, `stories` and `contract` lines, `merge`.

## java-developer (10 Nov 2026): code-review finding 3 resolved
The contract DTO `AdmissionViews.AdmissionPackage` replaces the repository record in `AdmissionDetail.package`; JSON output unchanged (Package ITs green). The remaining SAH-29 items are the human's.
