# SAH-78 · Code review · WP-A (78-1 to 78-4, plus WP-B/WP-C migrations) · 18 Nov 2026

Reviewer: code-reviewer (read-only).
- Scope: the SAH-78 files only, on the working tree of feature/SAH-39-home-kinetic-background.
- Not run: Maven, lint and tests (the hook allows git and npm audit only).

**Verdict: CHANGES REQUIRED** (1 Major).
- When F-1 is fixed and the final gate is green, the verdict becomes APPROVE WITH COMMENTS.
- From the code-review side, security PASS: the tenant comes only from the session, bodies are closed, 404 is answered before 403, audit holds ids only, and the module does no logging.

## Answers to the tech lead's points
1. **Additive and module Off unchanged:** yes, confirmed in `git diff`. The diff touches more files than the 4 touch points DEC-061 lists. All of them are plumbing for those 4 points: MeService, the ModuleEntitlement port, adapter and repository, RoleLabels, RolePermissionService, application*.yml, ClinicWebAddressPort and its adapter, PharmacyAccessPort, team.page and i18n. Tell the human at merge approval. Off has one extra cost: `/me` runs one more select (F-6).
2. **Contract:** records match the schemas. The status order is right, except that the body is bound before the gate (F-3). ADR-028 D1 holds: nothing outside pharmacy depends on it, and pharmacy imports only `platform.api`.
3. **`Sent<T>`:** sound (see the note below).
4. **Guard and `@StoreAccess`:** correct. The `noRollbackFor` requirement exists only as a comment (F-2).
5. **R-1:** fixed. The landing for an unmapped Store staff member is wrong (F-1).
6. **ClinicWebAddressPort:** acceptable (read-only and producer-declared, like TeamMemberPort). Still to be recorded in ADR-028.
7. **JDBC:** fine. All SQL is parameterised and names `tenant_id`, pinned by PharmacySqlGuardrailsTest.
8. **Tests:** good (F-9, F-10).
9. **UI text and accessibility:** mostly good (F-7, F-8).

## Findings
| # | Severity | File:line | Finding | Fix |
|---|---|---|---|---|
| F-1 | **Major** | frontend/src/app/features/pharmacy/pharmacy-landing.ts:17-23 | A Store staff member with no mapped store (module On) has menu `[]`, so the guard lets them through to Today. Today calls APIs that answer 403 for STORE_STAFF, so they see an error page. No existing role has an empty menu, so this is a new state, and an ordinary one: people are created first and mapped later. Story 78-1 wants "You do not have access to Pharmacy & Inventory. Ask your owner." | Also redirect when `me()?.role === 'STORE_STAFF'` to `/app/pharmacy`, which shows the server's 403 or module-off sentence. Unit tests: STORE_STAFF with an empty menu goes to pharmacy; OWNER with an empty menu goes to Today. |
| F-2 | Minor | StoreAccessGuard.java:28; StoreAccessDeniedException.java:9 | The denial audit survives only if every caller has `noRollbackFor`. A forgotten one loses it silently, and the coverage test checks injection only. | Before WP-B, add a test or ArchUnit rule: every method that calls `require` declares `noRollbackFor` StoreAccessDeniedException. |
| F-3 | Minor | ContextController:38-42; StoreController:43/57; StaffController:52/60; UnitSettingsController:31 | `@RequestBody` is bound before the gate, so a bad body answers 400 before module-off or 403 (contract rule 2). | Bind as JsonNode or String and convert after the gate, or run the gate in an interceptor before binding, or change the contract sentence. |
| F-4 | Minor | PharmacyProblemHandler.java:55 | A bad date gets the message "Choose a valid option". | "Enter a valid date" for LocalDate targets. |
| F-5 | Minor | StoreAssembler.java:76,81 | staffCount and HOD include mappings of an inactive store; per the contract they stop counting. | For an INACTIVE store: staffCount 0 and approverState NONE, or filter in the query. |
| F-6 | Minor | MeService.java:100,110-112; JdbcModuleEntitlementRepository | Every `/me` runs a separate `select pharmacy_on`, even with the flag Off. | Read it in the same statement, or add a test that counts the statements. |
| F-7 | Minor | store-form.dialog.html:85,94; unit-settings.card.html:59; map-staff.dialog.ts:121,128 | Native date inputs show the browser locale format, not "24 Oct 2026". | Use the existing Sahaj or Material date control with the Indian display format. |
| F-8 | Minor | pharmacy.page.html:32-44; setup.page.ts:53-69; store-form.dialog.html:3,17; map-staff.dialog.ts:63,71; confirm-sheet.component.ts:28,34 | The tablist has no tabpanel, `aria-controls` or arrow keys. Dialogs have no accessible name. | Use `mat-dialog-title` or `ariaLabelledBy`. Complete the tab pattern or drop the roles. |
| F-9 | Minor | test plan §10a | The fixed fixtures have not had a re-run yet (blocked by SAH-76 Sah76ReviewRoundIT.java:121). `frontend/e2e` is gitignored. | Merge precondition: 128 targeted tests green plus `validate backend`. Human to decide on tracking the e2e spec. |
| F-10 | Minor | 78-2 AC `integration-off` 409 | No code and no test. No 0.1.0 operation needs it. | Add IntegrationOffException (409) now with a unit test, or record it for SAH-79. |
| F-11 | Minor | staff-candidates; store-form.dialog.ts:94-95,173 | `storeId` is required, so the first Central Store cannot pick an approver at create. The form explains this. | Architect makes `storeId` optional; then remove the `hasCandidatesSource` branch. |
| F-12 | Minor | docs/stories/SAH-78.md vs the contract | Unmapped PHARMACIST gets 200; GET /stores lists all stores; 404 instead of `outside-store`; there is no Pharmacist store role. As a result, a pharmacist sees every store, staff names, GSTIN and licence. | BA aligns the stories. |
| F-13 | Nit | StaffMappingService.java:354 | `isHod` is true for an APPROVER mapping that needs review. | `isHod = active && effective == APPROVER`. |
| F-14 | Nit | Store/StaffMapping/UnitService | There are typed and raw-String overloads of each method. The typed ones are used only by tests. | Make them package-private, or keep only the raw form. |
| F-15 | Nit | ModularityTests.java:97-115 | Rule (c) on `@Table` passes trivially (JDBC, no entities). | Replace it with an SQL schema-prefix rule, or drop it. |
| F-16 | Nit | V2026_12_17_* | Version dates run ahead of today. A lower version added later by another session would be out of order. | Agree the next free version with the SAH-76/77 session (board: SAH-77 uses V2026_12_18_*+). |
| F-17 | Nit | V2026_12_17_1220 document_counter | It has no id, created_at, created_by or version columns (db rule). | Note the rule exception in the design note. |
| F-18 | Nit | i18n hi and mr `nav.pharmacy` | Needs a native-speaker check. | Human. |

## Notes
- **Sent<T>:** absent and null are told apart correctly. The strict module affects pharmacy types only, and the global setting is untouched. The Jackson version risk is pinned by tests.
- **Migrations:** forward-only, with no drop, rename or update of data. All new tables have forced RLS, composite tenant foreign keys and no delete grant. The seed is guarded by the placeholder, which FlywayPlaceholderConfigTest pins. `next_document_no` is race-safe.
- **Frontend:**
  - The labels, field order and columns follow mockup 16.
  - Components are OnPush and standalone; HTTP calls are only in pharmacy.api.ts; forms are typed.
  - Loading, error and empty states are there; saving is guarded; view-only users get no buttons.
  - The layout works on narrow screens.

## Conditions for APPROVE
1. F-1 fixed, or a written decision that the current landing is intended.
2. Final gate green: 128 targeted tests, `validate backend` and the frontend tests.
3. F-2 done before WP-B opens.

---

# Round 2 · WP-A re-review, F-11, WP-B (78-5, 78-6, 78-7) · 18 Nov 2026

Reviewer: code-reviewer (read-only).
- Not run: Maven, lint and tests (the hook allows only read commands). It relies on QA's gate: unit 5,974/0, IT 3,226/0 (25 skipped), targeted 219/219.

**Verdict: CHANGES REQUIRED** (1 Major, N-1). With N-1 fixed and tested, the verdict becomes APPROVE WITH COMMENTS.

## Answers to the tech lead
1. **Unreadable body 400 after the path id, before the guard 404:** accepted. It leaks nothing and still runs after module-off and the access level. The contract text must say so (N-9).
2. **F-11:** closed on both sides.
3. **OpeningStockService transactions:** sound.
   - `noRollbackFor = NotAccepted` commits the status, issues and audit, then answers 422.
   - Lock order is store, then import, everywhere.
   - REPLACED happens before WAITING, with a deferred FK.
   - Approve is one transaction under both locks.
   - Gap: N-2.
4. **Numbering:** gap-free under the unit row lock (`UPDATE ... RETURNING`); proven by the concurrency ITs.
5. **ProductKeys:** matches the SQL seed by inspection; pinned for one row only; `\s` is ASCII-only (N-8).
6. **Paise:** correct; at most about 5e18 per file, which fits a bigint (JS precision: N-12).
7. **CSV (browser):** handles BOM, quotes, CR LF, embedded newlines, the example row, the 5,000/5,001 boundary and a trailing empty column. Gaps: N-3, N-6, N-7.
8. **Store guard:** `refuse` is correct and the ArchUnit test is green; the REQUIRES_NEW design causes N-1.
9. **Frontend memory:** rows are dropped on send, destroy and error. Worst case: N-3.
10. **Batching and retry:** sequential, with an idempotent upsert. A lost submit response gives a false error on retry (N-4).
11. **Shared own-product form:** good.

## Round-1 findings
| # | Status |
|---|---|
| F-1 | Closed. pharmacy-landing.ts:22-29; unit and routing tests. |
| F-2 | Closed, but see N-1. |
| F-3 | Closed (deviation accepted; N-9). |
| F-4, F-5, F-6, F-10, F-13, F-14, F-15, F-16, F-17 | Closed. |
| F-7 | Closed (spot-checked). |
| F-8 | Closed (tab pattern at setup.page.ts:73-93, 168-192). |
| F-9 | Partly closed: the gate is green; `frontend/e2e` is still gitignored (human). |
| F-11 | Closed. StaffMappingService:106-139; pharmacy.api.ts:101-112; store-form.dialog.ts:195-213; PharmacyCandidatesWithoutStoreIT 8 tests. |
| F-12 | Closed (BA). OQ-78-8 stays for the human at the merge ask. |
| F-18 | Open: native-speaker check of hi/mr `nav.pharmacy` (human). |

## New findings
| # | Severity | File:line | Finding | Fix | Owner |
|---|---|---|---|---|---|
| N-1 | **Major** | StoreAccessGuard.java:45-50, 56-80 | The REQUIRES_NEW denial audit needs a second pooled connection while the caller's transaction holds one. 10 or more concurrent denials stall the whole app until the connection timeout. (Same issue as security L7.) | Carry the ids on `StoreAccessDeniedException` and write the audit from the exception handler, after the rollback, in one short transaction. Test: more parallel denials than the pool size (test pool 3) gives no timeout. Amend ADR-028 R3 and DEC-068. | java-developer; architect; tech lead (DEC-068) |
| N-2 | Minor | OpeningStockService.java:431-467; StoreService; ProductService:244-297 | Approve rechecks nothing since submit. (a) A store with a DRAFT or WAITING import can be switched off or have its code changed, which orphans the file (approve and reject then answer 404). (b) A product on the lines can be switched off, or its units changed, after submit. | Refuse with 409 while an import is open, or recheck at approve. Contract note. ITs. | architect, java-developer, qa-engineer |
| N-3 | Minor | opening-stock-csv.util.ts:37-78, 106; opening-stock.section.ts:34 | A 5 MB file of separators builds about 2.5 M arrays before the row limit is checked, and can freeze the tab. | Cap the file at 2 MB; stop parsing past MAX_ROWS plus the header and example rows; skip blank rows; add a spec. | angular-developer |
| N-4 | Minor | opening-stock.section.ts:538-594 | A retry after a lost submit response shows a false "can no longer be changed" error. | If `start` returns a status other than DRAFT, skip rows and submit and open the review; add a spec. | angular-developer |
| N-5 | Nit | opening-stock.section.ts:547-572 | The upload continues after the section is destroyed. | `takeUntilDestroyed`, or ask before leaving. | angular-developer |
| N-6 | Minor | opening-stock-csv.util.ts:116-126 | Extra cells beyond 7 are dropped silently. | Refuse the file, or mark the row (row number only). | angular-developer |
| N-7 | Nit | opening-stock.section.ts:548; opening-stock-csv.util.ts:136 | `columns` sent is the constant header, not the file's header. | Send the file's trimmed header cells. | angular-developer |
| N-8 | Minor | ProductKeys.java:16-18, 29-41 | `\s` is ASCII-only, so a non-breaking space breaks the duplicate match. Java/SQL parity is pinned for one row only. | `UNICODE_CHARACTER_CLASS` (or `[\\s\\u00A0]`); an IT comparing `dupKey` for all seeded rows. | java-developer, qa-engineer |
| N-9 | Minor | pharmacy.yaml general rule (2); stories | The accepted unreadable-body order is not written down. | One sentence in rule (2) and in the ACs. | solution-architect, business-analyst |
| N-10 | Minor | JdbcProductRepository.java:58-87 | The OR search with a repeated count may not meet 300 ms on the ~300,000-row real catalogue; there is no index for `code`/`alias` substrings. | Time it on a realistic catalogue before go-live; consider a UNION per column plus an index on `code`. | db-engineer, java-developer, qa-engineer |
| N-11 | Nit | import-review.component.ts:170 | "Nothing was kept." is false. | "Not accepted. Fix every problem below and upload the file again." (BA check). | angular-developer, business-analyst |
| N-12 | Nit | opening-stock.util.ts:12-16; OpeningStockRules.java:20-23 | Totals are JS numbers (above 2^53 is possible in theory). | Record as a known limit, or send a string, or add a ceiling. | architect |
| N-13 | Nit | OpeningStockCsv.java:17 | The template example row uses the sample-only code G-000101. | Use a placeholder `PRODUCT-CODE`; update the contract example. | architect, java-developer |
| N-14 | Minor (tests) | frontend/e2e/pharmacy-products-opening-stock.spec.ts | All calls are mocked, so no full-stack CSV path is tested. | Add one real happy path when the environment allows. | qa-engineer |

## Conditions for APPROVE (round 2)
1. N-1 fixed with the parallel-denial test; DEC-068 and ADR-028 R3 text amended.
2. N-2 decided: fixed now, or recorded as a known limit with a contract note.
3. Gate re-run after N-1 (`validate backend`); the pharmacy specs after any frontend fix.
4. N-3 to N-9 fixed, or listed at the merge ask.

---

# Round 3 · short re-review of the DEC-071 fix round · 18 Nov 2026

Reviewer: code-reviewer (read-only). Maven, lint and tests were not run; it relies on QA's gate (`validate db` passed: unit 5,999/0, IT 3,262/0, 25 skipped). Package C and IPD were out of scope.

**Verdict: APPROVE WITH COMMENTS.** No Blocker or Major. Every round-2 finding in scope is closed. 3 Minor and 2 Nit are new.

## Round-2 findings
| # | Status | Evidence |
|---|---|---|
| N-1 (Major) | Closed | StoreAccessGuard.java:43-63 has no transaction, no audit and no second connection. PharmacyProblemHandler.java:88-97 calls `StoreDenialAuditor.record` after the caller's transaction. It writes in one short transaction, with the tenant bound from the security context and never from the exception. A failed audit still answers 403. Tests: StoreAccessGuardTenantBindingTest (counting DataSource, at most 1 connection) and PharmacyStoreDenialPoolIT (pool of 3, 24 parallel denials, three transaction shapes, 404 writes no audit). |
| N-2 | Closed, residual recorded | StoreService.java:199-202 and :255-258 call `hasOpenImport` under the store row lock; ProductService.java:211-213 and :271-273 call `onOpenImport`. Order: unit, store, import. PharmacyOpenImportIT is thorough. The residual is R3-3. |
| L8(a), L8(b) | Closed | OpeningStockService.java:260-281. The cap is read after `findForUpdate` and refused before any write. Migration 1260 is additive. Tests cover the boundaries, 8 parallel requests (2 pass), the trigger and the check. A bad body after the cap answers 409: accepted (the state check already came before the field check). |
| N-8, N-13 | Closed | `UNICODE_CHARACTER_CLASS`; all 30 seed rows compared; the `PRODUCT-CODE` placeholder is pinned by an IT. |
| N-3 to N-7, N-11, I2, I5, QA-78-4 | Closed | The 2 MB cap and blank-row skip; a non-DRAFT start opens the review; `takeUntilDestroyed`; column 8 refused (stricter than asked, accepted); the file's own header; the new not-accepted text; 60 KB batches; the note hint; `@defer (on immediate)` with `@placeholder`. |
| N-9 | Closed | pharmacy.yaml general rule (2) and `malformed-request`. |

## New findings
| # | Severity | File:line | Finding | Fix | Owner |
|---|---|---|---|---|---|
| R3-1 | Minor | StoreAccessGuardConventionTest.java:46-50 | The rule `noPharmacyClassUsesRequiresNew` uses `accessField(Propagation, "REQUIRES_NEW")`. ArchUnit does not turn annotation values into field accesses, so `@Transactional(propagation = REQUIRES_NEW)` would pass. | Add a rule over the annotation property; confirm it fails on a throwaway violation. | java-developer |
| R3-2 | Minor | PharmacyProblemHandler.java:36 | The audit and the 409 extensions need the controller to implement `PharmacyApi` (`assignableTypes`). All 12 do today, and nothing enforces it. | An ArchUnit rule: every `@RestController` in `com.sahaj.pharmacy.web` implements `PharmacyApi`. | java-developer |
| R3-3 | Minor | ProductService.java:184,200,211,271; ADR-028 R5-2; ADR-029 P5 item 5 | R5-2 says the product row is locked before the check and the line write reads products `FOR SHARE`. Neither is built. A units change racing a line send can leave an open file on a changed product. | Amend R5-2 and ADR-029 P5 item 5 to what is built and name the residual race; a test-plan line. | solution-architect; tech lead |
| R3-4 | Nit | opening-stock-csv.util.ts:173-193; pharmacy.yaml:1154 | The cap is "twice what the file needs". Batches split at 60 KB can exceed the batches of 200 rows. A 5,000-row file at maximum width needs about 29 requests, so a full resend needs 58 against a cap of 50. | Say in the contract that the cap assumes 200-row batches, or compute it from the byte budget. | solution-architect |
| R3-5 | Nit | setup.page.ts:115-158; pharmacy-products-opening-stock.spec.ts:1335 | (a) `@defer` has no `@error`, so a failed chunk load leaves a blank tab. (b) The N-5 e2e test uses `waitForTimeout(500)` before a negative check. | (a) `@error` with "Could not load. Try again." (b) Poll for a stable value. | angular-developer; qa-engineer |

Conditions for the merge: R3-3 recorded in the ADRs; R3-1 and R3-2 done or listed; the human-owned items stay open (F-18, `frontend/e2e` gitignored, OQ-78-8).

---

# Round 4 · hardening round, package C and the SAH-79 migrations · 19 Nov 2026

Reviewer: code-reviewer (read-only; Maven not run).
- **Read in full:** every migration from 1200 to 1700 and the earlier ones they touch (1010, 1100, 1230); OpeningStockService, VendorService, NumberingService, ApprovalRulesService, StoreDenialAuditor, the coverage and convention tests, JdbcDocumentSeriesRepository.
- **Read in part:** StoreService, JdbcOpeningStockRepository, the vendor form dialog and the numbering panel; the ITs and e2e specs by pattern search (no skipped tests, no `waitForTimeout`, no storage).
- **Not read in depth:** ProductService, ReasonService, PrintTemplateService, the other Jdbc repositories and frontend components, the bodies of the ITs.
- OpeningStockService changed on disk during the review (the D-2 work); that in-flight change was not reviewed.

**Verdict: CHANGES REQUIRED.** Two Major (M-1, M-2). No new Blocker. D-1 is known and not re-raised.

| # | Severity | File:line | Finding | Fix (owner) |
|---|---|---|---|---|
| M-1 | Major | V2026_12_17_1380:39-84 (overwrites 1280:23-67) | The second function lost to a later migration. 1380 re-creates `next_document_no()` from the 1220 body, so on every fresh database the live function no longer sets `document_counter.last_issued_at`. The series lock works only by accident, because `coalesce(last_issued_at, updated_at)` falls back to `updated_at`. It breaks where 1280 ran and a number was issued before 1380 (a stale `last_issued_at` wins). The design note, the handoff and a Javadoc all say the function sets it, and no IT reads the column. ADR-030's `store_bill_counter.last_issued_at` also assumes it is live. | A forward migration with the union of both bodies (the ten-type list from 1380 plus `last_issued_at` on insert and on conflict from 1280); keep the grants; an IT that calls the function twice and checks `last_issued_at` is not null and moves (db-engineer; qa-engineer) |
| M-2 | Major | vendors/vendor-form.dialog.ts:406-418 | A silent dead end on save. "Drug licence required" defaults to Yes. Save with the number empty puts `setErrors({server})` on `licenceNo` and `licenceValidTill`. Switching to No hides the two controls but they stay INVALID, so the next Save returns silently at `form.invalid`. The spec always sets No first. | Clear the errors when `licenceRequired` changes (or compute the messages in `save()` only when required). A spec: Yes, empty, Save, switch to No, Save, the request is sent (angular-developer) |
| m-1 | Minor | no guard for repeated `create or replace function` | The root cause of D-1 and M-1. The two cases are `guard_opening_stock_import_update` (1100, 1260, 1270, 1350) and `next_document_no` (1220, 1280, 1380). About 40 other functions are defined once. | A guardrail test that scans the migrations: a function defined in more than one file must be on an allow-list naming the final file and a snippet the final body must contain. Add a rule to the migration checklist (human item under `.claude/skills`) (qa or java) |
| m-2 | Minor | 1700:132-137 | The blind count: `book_qty`, `book_ledger_id` and `book_occurred_at` sit on the same row, and table-level grants cannot hide them. BR-30 depends on the service's select lists. | Confirm the QA test exists and covers every counter-facing read when WP-H java lands; optionally a separate table (java, qa) |
| m-3 | Minor | 1430:46-47 | `ck_vendor_payable_settled` allows OPEN with `credited_paise = amount_paise`, and OPEN rows are what oldest-due credit reads. | OPEN requires `credited_paise < amount_paise or amount_paise = 0`, in a forward migration (db-engineer) |
| m-4 | Minor | 1500:102-107 | The indent guard lets APPROVED, PARTLY_DISPATCHED, DISPATCHED and RECEIVED move in any direction, for example RECEIVED back to APPROVED, and gives no cancel from PARTLY_DISPATCHED. | Check against the contract's state table; tighten to forward moves, or say so in the design note (db-engineer) |
| m-5 | Minor | 1010 `guard_store_update` with 1320 `stock_balance` | Nothing in the database stops a store going INACTIVE while it holds stock or in-transit units. | A trigger on `store` refusing INACTIVE when any balance row has `qty > 0`, and a service rule with an IT in WP-D (db-engineer, java-developer) |
| m-6 | Minor | 1330:77-81 | The ledger indexes lead with product, or with store then batch. A ledger-of-a-store-between-dates report cannot use them. | A `(tenant_id, store_id, occurred_at)` index (cheap while the table is empty) (db-engineer) |
| m-7 | Minor | PharmacySqlGuardrailsTest:165 | The only test that mentions `last_issued_at` checks the Java SQL text, so it could not catch M-1. | Test the database function's behaviour (the M-1 IT) (qa) |
| n-1 | Nit | 1270:42-44 | `drop constraint` then `add constraint` rescans under an exclusive lock. | Use the NOT VALID plus validate pattern of 1230 next time (db) |
| n-2 | Nit | PharmacyStoreAccessCoverageTest:168 | The count of 9 OPERATION handlers is hard-coded, and the `/stores/{}` prefix check does not cover SAH-79 paths outside stores. | Re-read both when the first SAH-79 sub-story is added to `BUILT_SUB_STORIES` (qa) |
| n-3 | Nit | StoreDenialAuditor.record:45-56 | If the audit insert fails, the client gets a generic 500 instead of the 403. It fails closed, but it is not written down. | One sentence in the Javadoc (java) |

**Acceptance list for the forward migration 1710** (1350 dropped all of 1270's guard changes):
- `guard_opening_stock_import_update()` is the union of three bodies: 1270 (DRAFT to REJECTED; both counters never go down in DRAFT with the message "opening stock import line counter cannot go down"; `line_rows_written` frozen in WAITING_APPROVAL), 1350 (the APPROVED-posting branch with its messages), and the final-state branch for REJECTED, NOT_ACCEPTED and REPLACED.
- Keep the revokes from public and `sahaj_app`.
- The existing ITs must pass: PharmacyWpDPostingDocsIT:73-113, PharmacyHardeningImportIT:107-189, PharmacyOpeningStockIT:856.
- `ck_opening_stock_import_submitted` stays as 1270 left it.
- M-1's `next_document_no` goes into the same migration; then the m-1 guardrail proves no third copy.

**Checked, no finding:** all other migrations (tenant tables have `tenant_id`, a leading index, ENABLE and FORCE row level security and select, insert and update policies; append-only tables have no update or delete grant plus deny triggers, including per-partition TRUNCATE triggers); the check-constraint lists match the Java enums; guard messages are consistent; the lock order; the ArchUnit rules and the narrowed coverage test; the frontend (OnPush, typed forms, no storage or console); the numbering row; QA's 17 failures and 1 error were all D-1 to D-3.
