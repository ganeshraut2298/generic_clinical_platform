# Test plan SAH-29 · Packages and package billing (IPD, part 8, read-only slice, DEC-030)

Story: `docs/stories/SAH-29.md`. Decision: `docs/decisions/DEC-030-sah29-read-only-packages.md`. Design: `docs/architecture/SAH-29-ipd-packages.md`, `docs/database/SAH-29.md` (migration `V2026_12_05_1000__ipd_packages.sql`). Contract: `docs/api/ipd.yaml` (`GET /packages`, `AdmissionDetail.package`). Mockup: `cfgIpAdm` (panel "Packages", `pkgs`) and `ipLines` / `a.pkg` (running bill) in `docs/design/mockup/sahaj-mockup.html`.

Scope: only what the mockup shows. One read endpoint, the package copy on the admission sheet, and the running bill with a package. There is no create, edit, remove or attach (OQ-29-1, OQ-29-2): those tests are the negative ones ("nothing can write").

Tests, all run on 10 Nov 2026:
- Integration (real `sahaj_test`, MockMvc through the full filter chain, profile `test`; `backend/src/test/java/com/sahaj/platform/it/`):
  - `PackageListIT` (15): `GET /packages`, shape, order, roles, module states, tenants, no write route.
  - `PackageTableIT` (15): `ipd.package` and the three admission columns by SQL (RLS, grants, checks, set-once guard).
  - `PackageBillIT` (19): the bill maths, transfer, the five readers of the bill, discharge, tenants and roles.
  - `PackageSupport` (shared set-up; not a test). Packages are set through `sahaj_migrator` (`NO FORCE` row level security, write, `FORCE` again, one transaction), as `docs/database/SAH-29.md` section 4 says. The expected bills are written in the tests from the story and the default rates, not taken from the application.
- Unit tests by the developers (not re-listed case by case): `RunningBillPackageTest` 15, `AdmissionPackageTest` 6, `PackageServiceTest` 5, `PackageControllerTest` 6, `JdbcPackageRepositoryTest` 2 (backend); `packages.store.spec.ts`, `ipadm.page.spec.ts`, `admission-dialog.component.spec.ts`, `ipd.model.spec.ts`, `admissions.api.spec.ts` (frontend, 168 files, 2,614 tests in all after the D-1 fix; 2,612 before).
- Playwright: `frontend/e2e/packages.spec.ts` (35 cases, run on phone-360 and desktop-1440 = 70 runs). The API is mocked in the browser as in `bed-transfer.spec.ts`; the real endpoint and the maths are covered by the integration tests above. The mock bills are written by hand from the story.

## Coverage: acceptance criterion to test

| AC | Case | Test | Result |
| --- | --- | --- | --- |
| 1 | The list holds the clinic's active packages with `id`, `name`, `pricePaise`, `includedDays` and nothing else | PackageListIT.theListHoldsTheClinicsActivePackagesInTheShapeOfTheContractAndNothingElse | pass |
| 1 | Boundary values (price 0 and 10,00,00,000 paise, days 0 and 60, name 1 and 80 characters) come back as stored | PackageListIT.theBoundaryValuesOfTheTableAreListedAsStored | pass |
| 1 | Empty state: `items` is an empty array, not null or missing | PackageListIT.aClinicWithNoPackageGetsAnEmptyItemsArrayNotNullOrMissing; e2e `empty state: the heading and an empty list, no new text` | pass |
| 1 | Only active packages are listed (P2); one switched back on returns | PackageListIT.onlyActivePackagesAreListedAndASwitchedOffOneComesBackWhenSwitchedOn | pass |
| 1 | Order of creation (not by name or price); same instant ordered by id | PackageListIT.thePackagesComeInTheOrderTheyWereCreatedNotByNameOrPrice; packagesCreatedAtTheSameInstantAreOrderedById | pass |
| 1 | Panel under "Bed transfer rules" in Admission and deposits; row = chip, bold name, fixed small text, price | e2e `<role>: the panel lists the packages in the server order with chip, bold name, fixed text and price` (4 roles) and `the panel sits under Bed transfer rules and nothing in it is a control` | pass |
| 1 | Chip reads "3 days", "Day-care" for 0 days; one day reads "1 days" (mockup, OQ-29-4); price "₹45,000", "₹10,00,000" | e2e rows test (SHOWN table); `a long package name wraps ...` (60 days, ₹10,00,000) | pass |
| 1 | Read-only: no button, input, link or tab stop in the panel; click, double click, Delete, Enter and Space send nothing | e2e `the panel sits under Bed transfer rules and nothing in it is a control`; `keyboard: Tab never lands in the panel and Enter or Space on a row sends nothing` | pass |
| 1 | No new text in the empty state | e2e empty state (the panel text is exactly "Packages") | pass |
| 1 | State text while loading ("Loading packages", not in the mockup, accepted pattern) | e2e `loading: the state text shows while the answer is awaited, then the rows replace it` | pass |
| 1 | Visible when IPD is Trial, On or Read-only; hidden with the gate message when Off, and the list is never asked for | e2e `IPD trial/on/readonly: the panel is read and shown`; `IPD off in the plan: ...`; PackageListIT.trialOnReadOnlyAndAnExpiredTrialAllowTheRead | pass |
| 1 | Readable by OWNER, FRONT_DESK, NURSE, DOCTOR; no audit or record-access row for the read | PackageListIT.everyReaderRoleSeesTheSameListWithoutAnAuditOrRecordAccessRow | pass |
| 1 | Layout: long name wraps at 375 px and 1440 px, nothing sticks out; Try again is a 44 px target | e2e `a long package name wraps ...`; D-2 and D-3 below | pass |
| 2 | The admission keeps its own copy of name, price and days; later changes to the list never change a bill | PackageBillIT.theAdmissionKeepsItsOwnCopyWhateverHappensToThePackageListAfterwards | pass |
| 2 | Copy is all-or-none, each column range checked; app can read but never insert or update the three columns; once set it cannot be changed or cleared by any role (same value again is harmless); not settable on a DISCHARGED admission | PackageTableIT (theCopyIsAllOrNoneAndEachColumnIsRangeChecked; theAppCanReadButNeverInsertOrUpdate...; anAppUpdateOrInsertNamingAPackageColumnIsRefused...; onceSetTheCopyCannotBeChangedOrCleared...; aPackageCannotBeSetOnADischargedAdmission...; aDischargedAdmissionCannotBeInsertedWithAPackage...) | pass |
| 2 | A discharged admission keeps its package copy and the Final bill freezes the package lines | PackageBillIT.aDischargedAdmissionKeepsItsPackageAndTheFinalBillFreezesThePackageLines; e2e `a discharged admission shows the frozen package lines in the Final bill` | pass |
| 3 | Lines in order: "Package · <name> (bed, nursing and surgeon for <N> days)" x1 at the package price, then "Bed · <category> beyond the package", then extras; for day-care, one day and three days; beyond = max(0, bill days - max(1, days)), line dropped at 0 | PackageBillIT.theBillIsThePackageThenOnlyTheBedDaysBeyondTheIncludedDays... | pass |
| 3 | Literal mockup wording "for 0 days" and "for 1 days" | PackageBillIT.theMockupWordingIsKeptLiterallyForZeroAndOneDay; e2e `the mockup wording for a day-care package (0 days) and a one-day package is kept literally` | pass |
| 3 | Beyond days at the daily bed charge of the current category, no multiplier; Clinic setup rate changes move only the bed line, not the package or the old nursing and visit rates | PackageBillIT.theBedBeyondThePackageIsBilledAtTheBedChargeOfTheCurrentCategoryWithNoMultiplier; ratesSetInClinicSetupAfterwardsMoveOnlyTheBedLine... | pass |
| 3 | Both cuts of "Bed day counted" (calendar day and 24 hours) | PackageBillIT.bothBedDayCutsCountTheBillDaysForThePackage | pass |
| 3 | Extras "<service> · not in package", base x current multiplier, rounded half-up per unit before quantity, in order of first charge; an extra on day 1 is the only line after the package | PackageBillIT.extraChargesComeAfterTheBedLine...; anExtraChargeOnDayOneIsTheOnlyLineAfterThePackage...; theExtraChargeMultiplierIsRoundedHalfUp... | pass |
| 3 | No "Doctor visits" line, no "Nursing care" line, no transfer segment | PackageBillIT (all bill tests assert the exact line list); e2e `<mock>: the sheet shows the package line, the bed beyond the package and the extras, with no visit, nursing or transfer line` (375 px and desktop) | pass |
| 3 | Same-name lines are both shown (the bill list is not keyed by name) | e2e `two lines with the same name are both shown ...` | pass |
| 3 | Long package line wraps at 375 px with no sideways scroll; the bill table sits inside the sheet and has no control | e2e `a long package line (80-character name) wraps ...`; `375 px: the package bill sits inside the sheet ...` | pass |
| 3 | Plain (no package) admissions keep their visit and nursing lines | e2e heading-note test (plain bill keeps "Nursing care"); existing RunningBillIT (SAH-25) | pass |
| 4 | Sheet, Admitted list, Deposit low pill, Deposit alerts tile and board flag all use the one package bill | PackageBillIT.theSheetTheListTheBoardTheDepositLowPillAndTheAlertsTileAllUseOnePackageBill | pass |
| 4 | The package price counts from day 1 (deposit low notice follows it) | e2e `the deposit low notice follows the package bill (package price counts from day 1)` | pass |
| 4 | Heading note of the Running bill panel unchanged; Summary strip has no new field; no new field on list row, board tile or summary | e2e `the heading note of the Running bill panel and the summary strip are the same with and without a package`; PackageBillIT.thePackageAddsNoFieldToTheListRowTheBoardTileOrTheSummaryOfThePatient | pass |
| 4 | Roles that see the bill see the same package bill | e2e `<role>: sees the same package bill on the sheet` (3 roles) | pass |
| 5 | After a transfer there is no segment line; the bed difference is not billed (a dearer bed adds nothing, a cheaper bed bills the days beyond at the cheaper rate); several moves give one bed line at the current bed | PackageBillIT.aTransferAddsNoSegmentLine...; aDearerBedAddsNoBedDifferenceAndACheaperBed...; severalMovesStillGiveOneBedLine... | pass |
| 5 | Extras re-priced at the new bed's multiplier; transfer facts (`segment_days`) still stored; transfers written before the package was set are never read | PackageBillIT.aTransferAddsNoSegmentLineBillsDaysBeyondAtTheNewBedAndRepricesTheExtras; transfersWrittenBeforeThePackageWasSetAreNeverRead... | pass |
| 5 | Discharge template defaults to "Surgical" for a package admission unless Maternity or Day-care (or the setup template says otherwise) | PackageBillIT.theDischargeSheetDefaultsToSurgicalForAPackageAdmission... | pass |
| 6 | Roles: OWNER, FRONT_DESK, NURSE, DOCTOR read; every other role gets 403 `forbidden` with a problem body; no session 401 | PackageListIT.everyOtherRoleGets403ForbiddenWithAProblemBody; noSessionGets401; PackageBillIT.aRoleWithoutPermissionGets403OnTheSheetThatCarriesThePackageAndNoSessionGets401; e2e `403 forbidden from the list: no rows and the server message is shown` | pass |
| 6 | Module Off answers 403 `module-off`; Read-only and expired trial keep reading; switching IPD off hides and on shows again | PackageListIT.moduleOffAnswers403ModuleOff...; trialOnReadOnlyAndAnExpiredTrialAllowTheRead; switchingIpdOffHidesTheListAndSwitchingItOnShowsItAgain; e2e `403 module-off from the list: no rows, the fixed alert text ...` | pass |
| 6 | Tenant isolation: another clinic's packages are never listed; the clinic comes from the session, not the request; another clinic's package admission answers 404; SQL as the app sees no other clinic's rows | PackageListIT.anotherClinicsPackagesAreNeverListed...; thePackageRowsOfAnotherClinicAreInvisibleToSqlAsTheAppToo; PackageBillIT.anotherClinicsPackageAdmissionAnswers404AndItsPackageIsNeverSeen; PackageTableIT.packagesAreInvisibleWithNoClinicBoundAndAnotherClinicsRowCannotBeNamed | pass |
| 6 | `ipd.package` has `tenant_id`, FORCE RLS, one read policy; the app role has SELECT only (no insert, update, delete, truncate, reference); table cannot be truncated even by its owner; names unique per clinic ignoring case, but two clinics may share a name; check constraints and boundaries | PackageTableIT (first 7 tests) | pass |
| 6 | The set-once guard function cannot be called or read by the app | PackageTableIT.theGuardFunctionCannotBeCalledByTheAppAndHoldsNoTableAccess; theAppCannotRunTheGuardDirectlyToLearnAnything | pass |
| 6 | No endpoint to create, change or remove a package (POST, PUT, PATCH, DELETE refused, nothing written by trying) | PackageListIT.thereIsNoEndpointToCreateChangeOrRemoveAPackageAndNothingIsWrittenByTryingToo | pass |
| 6 | Money is integer paise | PackageListIT boundary test (bigint), PackageBillIT (all amounts in paise, half-up rounding) | pass |
| 6 | Invalid input gives 400 with a problem body | Not applicable: the only endpoint is a GET with no parameters or body. The 403 answers are checked for a problem-details body with the `forbidden` or `module-off` type; 401 for the status. | n/a |

## Error and edge paths in the browser

| Case | Test | Result |
| --- | --- | --- |
| 500 from the list: fixed alert "We could not load the packages. Try again." (not the server text), no rows, Try again works and drops the alert | e2e `error: a 500 shows the alert and Try again, and a working retry shows the rows and drops the alert` | pass |
| Lost connection: same alert; Try again disabled while it runs; a second click while it runs sends nothing | e2e `error: a lost connection shows the same alert, Try again is disabled while it runs and a second click while it runs sends nothing` | pass |
| Two click events in the same instant on Try again send ONE request | e2e `D-1: two click events in the same instant on Try again send one request` | pass after the fix (re-test 10 Nov; first pass: 2 requests) |
| Keyboard: Tab reaches Try again and Enter retries | e2e `keyboard: Tab reaches Try again and Enter retries` | pass |
| 403 module-off: fixed alert text (not the server text), no rows; Try again recovers | e2e `403 module-off from the list ...` | pass |
| 403 forbidden: no rows, server message shown | e2e `403 forbidden from the list ...` | pass |
| 80-character name with no spaces at 375 px: no sideways scroll | e2e `D-2: an 80-character name with no spaces ...` | pass after the fix (first pass: page 791 px wide) |
| Try again is a 44 px target at 375 px | e2e `D-3: 375 px: Try again is a 44 px target ...` | pass after the fix (first pass: 38 px) |
| Sheet read fails (500): no package lines, no crash | e2e `error: the sheet cannot be read (500) shows no package lines and no crash` | pass |
| Not reachable in the browser: a failed second read keeps the stale list (Try again exists only while an error shows, and an error shows only when the read before it failed) | Covered by `packages.store.spec.ts` | unit pass |

## Mutation checks (e2e)
A temporary copy of the production build in the scratchpad, served with `E2E_DIST`, one thing changed, the matching tests run on it:

| Change in the built app | Result |
| --- | --- |
| "Day-care" to "Day care" in the chip | 8 failed (4 roles x 2 projects) |
| "Bed, nursing and surgeon included" to "Bed and nursing included" | 8 failed |
| "Loading packages" to "Loading" | 2 failed (both projects) |
| Re-test: the two fixed CSS rules (`.li .main b` wrap, `.notice` button 44 px at 620 px) taken out of the new build | D-2 and D-3 tests: 4 failed (both projects); page 416 px beyond the viewport, Try again 38 px |

The backend tests were not mutation-checked (no product change allowed to QA); the expected values are written in the tests from the story, so a wrong application value fails them.

## Not tested, and why
- Create, edit, remove, attach, and the audit of package changes: future work (OQ-29-1, OQ-29-2). Packages exist only by hand in `sahaj_dev` and `sahaj_test` (P1: not for a live clinic).
- Billing confirmation of OQ-29-5 and OQ-26-4 (a dearer bed adds no bed-difference charge; extras re-priced at the current multiplier): the tests prove the built behaviour equals the mockup. Whether it is right for a real hospital is the human's.
- Pre-existing finding N-1 (not SAH-29): at 375 px the "Collect" and "Add" buttons in the admission sheet are 40 px high. The e2e test records it as an annotation and does not assert it.

## Results of the first run (10 Nov 2026, before the fixes)
- Backend `validate backend`: PASSED. PackageListIT 15, PackageTableIT 15, PackageBillIT 19, 0 failures.
- `packages.spec.ts` 3 runs in a row: 64 pass, 6 fail (D-1, D-2, D-3 in both projects).
- Whole e2e folder: 2,447 passed, 45 skipped, 22 failed: the 6 above plus 16 runs of `bed-transfer.spec.ts` that did not mock the new `GET /ipd/packages` call; the spec was fixed (one mock) and re-run alone: 46 of 46 pass.

## Results of the re-test (10 Nov 2026, after the angular-developer fixed D-1, D-2, D-3)
- Fixes: D-1 `load()` returns at once while loading (`packages.store.ts`); D-2 `overflow-wrap: anywhere` on `.li .main b`; D-3 `.notice > .mat-mdc-button-base { min-height: 44px }` at 620 px and narrower (both in `frontend/src/styles/_console.scss`, shared styles).
- Lint passes; 168 files, 2,614 unit tests pass; `frontend/dist` rebuilt and the build passes.
- `packages.spec.ts` 3 runs in a row: **70 of 70 each time**. The three defect tests (both projects) are green.
- Right reason: in a scratch copy of the new build with the two CSS rules taken out, the D-2 and D-3 tests fail again with the first-pass numbers (416 px; 38 px). With the rules in, they pass. D-1: first pass sent two GETs, now one.
- Shared-style side effects: whole e2e folder, once: **2,469 passed, 45 skipped, 0 failed** (44.3 min). It holds `bed-transfer.spec.ts` (46), `layout-matrix.spec.ts` (all 9 projects), `work-area-width.spec.ts`, `admitted-cards.spec.ts`, `patient-card-setup.spec.ts` and every spec that draws a Try again notice or a `.li` list. None of their measured widths, heights or no-overflow checks changed, and no assertion was relaxed.
- Backend not re-run (SAH-29 backend code unchanged since the green gate of 05:57; only the `@ScopeExempt` annotation in `PackageController`, added by SAH-34 at 10:17).
- Final verdict: **PASS** (`docs/reviews/QA_REPORT_SAH-29.md`).
