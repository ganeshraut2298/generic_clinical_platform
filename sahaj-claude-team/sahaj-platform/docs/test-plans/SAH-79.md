# Test plan · SAH-79 Pharmacy & Inventory Phase 2 · work package D (stock core and approvals): the migrations V2026_12_17_1300 to 1390

Author: qa-engineer · Date: 19 Nov 2026 · Branch: `feature/SAH-39-home-kinetic-background` (DEC-061 Answer 2, additive only)
Scope of this plan: the database of WP-D only, as the db-engineer wrote it (docs/database/SAH-79-inventory.md, sections 3 to 5 and the QA list in section 11). No WP-D API exists yet, so every test here is a database test through the real `sahaj_test` database; the WP-D screens and operations get their own sections when the java-developer and angular-developer land them. Sources: `docs/database/SAH-79-inventory.md`, `docs/architecture/adr/ADR-029-pharmacy-inventory-runtime.md`, `docs/api/pharmacy.yaml` 0.2.0 (`x-work-package: D`), DEC-072, DEC-073, DEC-075, `docs/handoffs/SAH-78.md` (handoff of 18 Nov 23:00).

## 1. Where the tests are

| File (all in `backend/src/test/java/com/sahaj/platform/it/`) | What it covers |
| --- | --- |
| `Sah79Support.java` | Shared set-up: a real clinic with the module on, a Central Store, a Pharmacy store, an adopted product and a vendor (API, committed); every probe of a table runs in one transaction as `sahaj_app` with the tenant bound and is **rolled back**; a refused statement runs in a savepoint, so one test checks many rules; `commitRows` writes one row in each of the nine new tables for isolation tests |
| `PharmacyWpDTablesIT.java` | The nine new tables: RLS enabled and forced, `tenant_id` not null with the bound-tenant default, the policy commands, the grants (no delete, no truncate, nothing on a partition), clinic isolation, the append-only triggers, the functions |
| `PharmacyWpDRulesIT.java` | Batch, stock balance, ledger line, approval request and decision, stock adjustment, client key, document event, linked ward |
| `PharmacyLedgerPartitionsIT.java` | The 41 partitions, `create_ledger_partitions()`, the IST bounds, no default partition, the upkeep check (12 months or more remain) |
| `PharmacyWpDPostingDocsIT.java` | Opening stock posting (the one allowed update), the ten document types, `next_document_no`, 36 reasons in 11 lists, the fixed reason, the migration list and the checksums |
| `PharmacyTablesIT.java` (edited) | The seed count is 36 reasons; the migration list test now says the 12 SAH-78 migrations are the first twelve |
| `RowLevelSecurityCatalogIT.java` (edited) | 29 pharmacy tenant tables plus every ledger partition; the insert-only set widened |

The inventory note says "ten new tables"; there are **nine** tables (`client_request`, `document_event`, `batch`, `stock_balance`, `stock_ledger`, `approval_request`, `approval_request_level`, `approval_decision`, `stock_adjustment`) in **ten migrations**. The tests assert the nine. 41 partitions bring the tenant-table total in the catalogue test to 29 names and 41 partitions.

Technique notes. (1) `sahaj_migrator` owns the tables but is not exempt from row level security and no policy names it, so it sees no tenant rows. To reach the row triggers as the owner, a test creates a temporary policy for `sahaj_migrator` inside its own rolled-back transaction (`create policy tmp_qa_owner ... using (true)`), then updates, deletes and truncates; the triggers fire for the owner, as the design note says. As `sahaj_app` an update or delete of an append-only table is refused earlier, by the missing grant (42501 "permission denied"). (2) AssertJ finds `assertThat(SQLException)` ambiguous; `Sah79Support.thatRefusal` is used.

## 2. Test table (one test per rule of the QA list, design note section 11)

| # | Rule | Test (class.method) |
| --- | --- | --- |
| T79-D-01 | Each new tenant table: RLS enabled and forced, `tenant_id` not null defaulting to the bound tenant, policies only for `sahaj_app` | `PharmacyWpDTablesIT.theNineNewTablesHaveForcedRowLevelSecurity...`, `everyPolicyOfTheNewTablesIsForSahajApp...` |
| T79-D-02 | Policy commands: select and insert only for `client_request`, `document_event`, `stock_ledger`, `approval_request_level`, `approval_decision`; select, insert, update for `batch`, `stock_balance`, `approval_request`, `stock_adjustment` | same |
| T79-D-03 | Grants: exactly the documented privileges; no delete and no truncate anywhere (permission denied 42501); nothing to PUBLIC; every ledger partition has no privilege for `sahaj_app` | `sahajAppHoldsOnlyTheDocumentedPrivileges...`, `noNewTableCanBeDeletedFromOrTruncatedByTheApplication...` |
| T79-D-04 | Tenant A's rows invisible to tenant B; B's update touches no row; an insert naming A's tenant refused (row-level security); a composite foreign key cannot reach A's batch or request | `oneClinicsRowsAreInvisibleToAnother...`, `aTenantOfAnotherClinicCannotReferenceMyRows...` |
| T79-D-05 | The three append-only tables (and the parent and every partition): update, delete, truncate refused for the owner with SQLSTATE 42501 and "`<table>` is append-only (`<op>` refused)"; the application cannot even try | `theTriggersRefuseUpdateDeleteAndTruncate...`, `theTableTheOwnerNamesInATruncate...`, `aRowCannotBeUpdatedThroughThePartitionName...`, `theApplicationCannotUpdateOrDeleteThe...` |
| T79-D-06 | Functions: the new trigger functions and `create_ledger_partitions` not executable by `sahaj_app` or PUBLIC; `next_document_no` is; every function pins `search_path` | `theNewDatabaseFunctionsCannotBeCalledByTheApplication...` |
| T79-D-07 | Ledger partitions: 41, `stock_ledger_y2026m11` to `stock_ledger_y2030m03`, no default partition, range partitioned on `occurred_at`; each partition has forced RLS, two policies (select, insert), no grant, the truncate trigger and the cloned row trigger, a `tenant_id` column | `PharmacyLedgerPartitionsIT.thereAreFortyOnePartitions...`, `everyPartitionHasForcedRowLevelSecurity...` |
| T79-D-08 | IST bounds: 00:30 IST on 1 Dec lands in December; 23:59:59 IST on 30 Nov in November; 00:00 IST on 1 Nov 2026 in the first partition; 23:59:59 IST on 31 Mar 2030 in the last; one second later, any month before Nov 2026 and Dec 2030 fail with "no partition of relation" (no default partition) | `aLineAt0030IstOnTheFirstOfDecember...`, `aLineOutsideTheCreatedMonthsFailsLoudly...` |
| T79-D-09 | `create_ledger_partitions`: 0 for existing months (also a day inside a month), 1 for April 2030 (then 42 partitions, forced RLS, two policies, truncate trigger, truncate refused), idempotent, 22023 for 0, 121 months or a null month, 120 months make 120, refused for `sahaj_app`, all rolled back | `createLedgerPartitionsIsIdempotent...` |
| T79-D-10 | Upkeep check: the last partition is March 2030 and at least 12 months remain after this month (fails from April 2029: a forward migration `create_ledger_partitions(date '2030-04-01', 12)` is then due) | `atLeastTwelveMonthsOfPartitionsRemainAfterToday` |
| T79-D-11 | Ledger line checks: one direction (both 0, both above 0), non-negative quantities and balance, SYSTEM exactly when no actor, second signer never the actor and needs an actor, document type and id together and in the pattern, `admission_id` exactly for `WARD_CONSUMPTION_PATIENT`, unknown transaction type, not self-reversing, reason key shape; all 24 transaction types accepted | `PharmacyWpDRulesIT.aLedgerLineHasOneDirection...`, `everyOneOfTheTwentyFourTransactionTypes...` |
| T79-D-12 | Reconciliation: the ledger sum equals the balance after a posting (the query of section 3.2 returns no row); a balance moved without a ledger line is caught by the same query | `theLedgerSumEqualsTheBalance...` |
| T79-D-13 | Balance: `qty = -1` refused, an update below zero refused, one row per store, batch and state, the product is the batch's product, the key never changes (store, batch, product, state, unit), `qty` may change | `aBalanceCanNeverBeBelowZero...`, `theKeyOfABalanceNeverChanges...` |
| T-79-D-14 | Two sessions taking the last unit through the lock order: the second waits, sees 0 after the first commits, and the database refuses its write (`ck_stock_balance_qty`) | `twoSessionsTakingTheLastUnit...` |
| T79-D-15 | Batch identity: `ab1` and ` AB1 ` with one expiry refused (`uq_batch_identity`), another expiry accepted, key generated upper-cased and trimmed; MRP above 0; rate not negative; number not blank and at most 20; source list; unknown product and a vendor of another clinic refused; MRP and identity never change (5 columns and 3 more), rate and vendor may | `aBatchIsOnePerProductBatchNumber...`, `theBatchChecksRefuse...`, `theMrpAndTheIdentityOfABatchNeverChange...` |
| T79-D-16 | Approval decision: not self (`ck_approval_decision_not_self`), `raised_by` must equal the request's, one decision per level, the same person cannot approve both levels (but may reject), a rejection needs a reason key, unknown request and bad values refused | `nobodyApprovesTheirOwnRequest...`, `oneFinalDecisionPerLevel...` |
| T79-D-17 | Approval request: one PENDING request per document (a second after the first is decided is accepted), level and status go together, the raiser is the creator, a decided request is final, a pending one keeps its identity (12 columns) and its level never goes back | `aRequestHasAtMostOnePending...`, `aPendingRequestKeepsItsIdentity...`, `theLevelRowsOfARequestFollowTheirKindColumns` |
| T79-D-18 | Stock adjustment: physical must differ from book, `difference` generated, one WAITING row per batch and store (a second after POSTED accepted), unique number and sequence, WAITING to POSTED once with `posted_at` then final, a rejected insert refused, identity of a waiting row frozen (8 columns), second signer never the raiser, REJECTED needs its approval request | `anAdjustmentMustChangeTheStock...`, `aWaitingAdjustmentGoesToPostedOnce...`, `theSecondSignerOfAnAdjustment...` |
| T79-D-19 | Opening stock posting: an APPROVED import accepts one update that sets `posted_at` and `posted_by` and nothing else (with `updated_*` and `version`); one without a posting, or with another column, refused; a posted import is final; a draft or waiting import cannot carry a posting; an approval cannot set it in the same update; the older messages are unchanged | `PharmacyWpDPostingDocsIT.anApprovedImportAcceptsExactlyOnePostingUpdate...`, `aDraftOrWaitingImportCannotCarryAPosting...`, `theOldMessagesOfTheOtherStatesAreUnchanged...` |
| T79-D-20 | `client_request`: one use of a key per caller, another caller may store the same key, operation 1 to 60 characters; `document_event`: doc type and event patterns, acting-as list, quantities not negative, note 1 to 300, reason shape, database clock | `aClientKeyIsUsedOncePerCaller...`, `aDocumentEventHasAVocabularyPattern...` |
| T79-D-21 | Document types: `document_series` and `document_counter` accept the ten and refuse `FOO` and `CREDIT_NOTE` (0.3.0, not yet); `next_document_no` returns 1, 2, 3 for each of the ten types, 22023 for an unknown or null type, fiscal year 0 for the new types and the year for the bill; one clinic's numbers never another's; no tenant bound is refused | `theDocumentSeriesAndCounterAcceptTheTenTypes...`, `nextDocumentNoReturnsOneTwoThree...`, `nextDocumentNoWithoutATenant...` |
| T79-D-22 | Reasons: 36 seeded rows in 11 lists with the counts 3, 4, 3, 5, 5, 4, 4, 3, 3, 1, 1, every key shaped and prefixed by its list, the three new labels; `ck_reason_default_state_fixed` refuses an inactive state of `receipt-reject.short` (insert and update) and accepts any other; a clinic's own reason is accepted in each of the 11 lists and refused in an unknown one | `thereAreThirtySixSeededReasons...`, `theFixedReasonCannotHaveAnInactiveStateRow...`, `aClinicsOwnReasonIsAcceptedInEachOfTheElevenLists` |
| T79-D-23 | Linked ward: `ipd_ward_id` refused on a Central and a Pharmacy store (`ck_store_ipd_ward`), accepted on a WARD store, no foreign key | `aLinkedWardIsOnlyAllowedOnAWardStore...` |
| T79-D-24 | Migration list: 1000 to 1390 apply in version order, none failed, only migrations of the reserved range 1270 to 1299 lie between 1260 and 1300, 1300 to 1390 follow in order, and the Flyway checksum of every applied file equals the file's | `migrations1000To1390ApplyInOrder...` |
| T79-D-25 | Existing tests changed by the migrations: reason seed 13 to 36 (`PharmacyTablesIT`), migration list (`PharmacyTablesIT`), 20 tenant tables to 29 and 41 partitions, insert-only set widened (`RowLevelSecurityCatalogIT`), the "APPROVED import is final" assertions still pass (message still contains "is final"), and the new posting rule is in T79-D-19; no existing test expected `next_document_no` of a new type to raise | edits and `PharmacyWpDPostingDocsIT` |

## 3. Not tested here (and why)

- Concurrency of the posting service, FEFO, expiry quarantine, the approval engine and the document history: no WP-D service exists yet (java-developer, next). Their tests are written with the operations.
- The retention of `client_request` (OQ-D-1) and the batch MRP correction path (OQ-D-2): open decisions, no behaviour to test.
- `LedgerPartitionCheck` (the application's start-up warning, from October 2029): a java class not yet built; the database half is T79-D-10.
- The vendor credit tiers (migration 1290) and `document_counter.last_issued_at` (migration 1280) belong to the SAH-78 hardening round; see SAH-78.md sections 21 and 23.

## 4. Results of this run (19 Nov 2026)

**First result: the migrations apply cleanly.** `V2026_12_17_1300` to `1390` (and `1270` to `1290`, and the WP-E to WP-H files `1400` to `1700` that the db-engineer wrote at the same time) were applied by Flyway to `sahaj_test` from scratch, in version order, with no failed row; the checksum of every applied file equals the file (T79-D-24 passes). Nobody had applied them before. The typos the db-engineer feared in `1340` and `1350` are not there (T79-D-07 to T79-D-10 and T79-D-19 pass).

| Class | Tests | Result |
| --- | --- | --- |
| `PharmacyWpDTablesIT` | 11 | 11 pass |
| `PharmacyWpDRulesIT` | 20 | 20 pass |
| `PharmacyLedgerPartitionsIT` | 6 | 6 pass (after one test fix: a partition's `tenant_id` is read through `pg_attribute`, `information_schema` hides it from `sahaj_app`) |
| `PharmacyWpDPostingDocsIT` | 11 | 11 pass (after one test fix: `reason_default` is not even insertable by the application, 42501) |
| `PharmacyTablesIT` (edited) | 15 | 15 pass (reason seed 36; the "23 tables" test is now "the 23 of SAH-78 plus any other"; the migration list test) |
| `RowLevelSecurityCatalogIT` (edited) | 15 | 15 pass (29 tenant tables of WP-D with the 25 of WP-E to WP-H, every partition with its own policies) |

Gate numbers: unit 6,127 run, 0 failures; integration 3,414 run (this run) with 17 failures and 1 error, none of them in a WP-D test after the fixes above; the remaining failures are in the SAH-78 hardening class (SAH-78.md section 28). 25 skipped (older tests).

**One defect that touches WP-D data.** D-1 of SAH-78.md section 28: migration `1350` (WP-D, D3) replaces `pharmacy.guard_opening_stock_import_update()` with a body that predates the hardening migration `1270`, so the final function in the database loses the 1270 rules (DRAFT to REJECTED, counters never go down, `line_rows_written` frozen). The posting rule of 1350 itself (T79-D-19) works. Owner: db-engineer.

**Findings worth the reviewers' eyes (no test fails).**
1. `1350`'s guard compares the whole row with `to_jsonb(new) - ... is distinct from to_jsonb(old) - ...` in the APPROVED branch; it is correct, but a later column added to `opening_stock_import` is covered without anyone choosing it (as the note says).
2. `guard_approval_decision_insert` reads the request under the caller's row level security: a decision for a request of another tenant reaches the foreign key (23503), not the trigger message. Both refuse; the order is not a defect.
3. The partition check `ix_stock_ledger_*` indexes were not checked one by one (no plan test). The volume and plan checks of section 9 of the note are not tested here.
4. The "ten new tables" of the note are nine; see section 1.

---

# Rounds 5 and 6 (19 Nov 2026, qa-engineer): the WP-D API, the purchase orders (79-6), migrations 1710 to 1730

Sources: docs/api/pharmacy.yaml 0.3.3, DEC-072 to DEC-081, ADR-029 (P7, N2, N3), docs/database/SAH-79-inventory.md sections 11 to 19, the handoffs of 19 Nov. Section 8 holds the results after the gate. A test that fails is a defect for its owner, never removed.

## 5. Where the new tests are (all in `backend/src/test/java/com/sahaj/platform/it/` unless noted)

| File | What it covers |
| --- | --- |
| `Sah79ApiSupport.java` | Fixture: clinic, Central Store, Main Pharmacy, a pharmacist mapped at the pharmacy (`keeper`), a pharmacist mapped at the Central Store (`csUser`), a second owner, product A (catalogue, Schedule H, 12 percent GST, box of 10 to vial), product B (own), a vendor; the clean three-row file is submitted, approved and posted through the real API |
| `PharmacyWpDPostingApiIT` | 79-1: preview, post, replay, already-posted, not-approved, batch-mrp-mismatch, case-only batch numbers, an expired line, Schedule X, roles, clinics, module off, bad input |
| `PharmacyWpDStockReadsApiIT` | 79-3 and 79-4: overview, batches (views, search, paging, 400s), one product, low stock (at or below), FEFO pick, all stores, my store, guards, clinics, module off, `GET /context` capabilities |
| `PharmacyWpDAdjustmentsApprovalsApiIT` | 79-2 and 79-5: adjustments (wait, post at once, replay, adjustment-waiting, changed, validation), inbox, decision (self-approval, level 2, stale, reject reasons, rollback, who may read), Schedule X (owner 403, missing signer 400, second-signer-invalid, pin-not-set, wrong-pin, signer on the ledger, rejected moves nothing), second signers, active reasons, reconciliation (I11) |
| `PharmacyWpEPurchaseOrderApiIT` | 79-6: the 12 operations and the store guard, reorder suggestions, create (manual and from suggestions), edit, discard, submit, mark sent, cancel, close short, copy, list and tiles, the approver reading, clinics |
| `PharmacyRound4DbIT` | 1710 guard (nine cases in five tests), `next_document_no`, the unit lock L11 with two sessions and a series save, I11a, I11b, m-3, m-4, m-5, m-6, L13 |
| `PharmacyScheduleXLedgerAndAuditSignerDbIT` | L14 (1720 and 1730): 17 types x adopted and own Schedule X, the 7 unlisted types, a system line, Schedule H, the monthly partitions, opening stock of a Schedule X item passes; the 1730 audit signer |
| `PharmacyFunctionGuardrailIT` | I15: every pharmacy function defined by more than one migration keeps the messages of its earlier versions; live bodies; winners 1710, 1720, 1730 |
| `PharmacyRound5ContractIT` | Threshold limit, vendor version 0, `numbersIssued` |
| `PharmacyHardeningImportIT` (edited) | The outdated cap test is renamed and rewritten (D-3) |
| `frontend/e2e/pharmacy-inventory-wpd.spec.ts`, `frontend/e2e/pharmacy-purchase-orders.spec.ts` | The WP-D and 79-6 screens with the browser API mocked, phone-360 and desktop-1440 (the folder is git-ignored; DEC-069 says to track it) |

## 6. Test table

| # | Rule | Test (class.method, abbreviated) |
| --- | --- | --- |
| T79-1-01 | Preview of an approved import: totals, names, canPost, writes nothing | `PharmacyWpDPostingApiIT.thePreviewOfAnApprovedImportShowsTheTotals...` |
| T79-1-02 | Preview of a draft: canPost false, NOT_APPROVED | `...thePreviewOfAnImportThatIsNotApproved...` |
| T79-1-03 | Post writes 3 ledger lines (OPENING_STOCK, AVAILABLE, USER), 3 batches, balances; ledger sums equal balances; audit once; stock reads show it | `...postingWritesTheLedgerTheBalancesAndTheImport...` |
| T79-1-04 | Same clientKey replays and posts nothing twice | `...theSameClientKeyAgainPostsNothingTwice...` |
| T79-1-05 | Another key: 409 already-posted with postedAt and the sentence | `...aSecondPostWithAnotherKey...` |
| T79-1-06 | Draft or waiting: 409 not-approved, nothing written | `...anImportThatIsNotApproved...` |
| T79-1-07 | Same product, batch and expiry with another MRP: preview MRP_MISMATCH, post 409 batch-mrp-mismatch with lines, nothing posted | `...aBatchWithTheSameKeyButAnotherMrp...` |
| T79-1-08 | Batch numbers that differ only in case are one batch | `...batchNumbersThatDifferOnlyInCase...` |
| T79-1-09 | A line whose expiry passed goes to QUARANTINE | `...aLineWhoseExpiryHasPassed...` |
| T79-1-10 | A Schedule X line posts without a second signer (A-8) | `...aScheduleXLineIsPosted...` |
| T79-1-11 | Roles: owner posts; pharmacist unmapped at the store 404; front desk, nurse, doctor, billing 403 | `...theOwnerMayPost...` |
| T79-1-12 | Another clinic 404; module off 403 module-off; bad body 400; bad path id 400 | `...anotherClinic...`, `...theModuleSwitchedOff...`, `...aBadBody...` |
| T79-3-01 | Overview: empty before posting, counts, value in paise, pills | `PharmacyWpDStockReadsApiIT.theOverviewOfAStoreWithNoStock...` |
| T79-3-02 | Stock belongs to one store | `...theOverviewOfTheOtherStore...` |
| T79-3-03 | Batches: one row per batch and state, FEFO rank 1 and 2, supplier null | `...theBatchListShowsOneRowPerBatch...` |
| T79-3-04 | Views SCHEDULE, NEAR_EXPIRY, EXPIRED; search ignores case; paging | `...theBatchViewsFilter...` |
| T79-3-05 | 400 for bad view, withinDays, short q, size 0 or 101, page -1 or 1001 | `...theBatchListRefusesBadInput...` |
| T79-3-06 | One product: FEFO order and the store's reorder level; unknown product 404; bad id 400 | `...oneProductShows...`, `...aProductThatIsNotInTheUnit...` |
| T79-3-07 | Low stock: at or below the level is listed, above is not; no level never listed | `...lowStockListsAProductAtOrBelow...`, `...aProductWithNoReorderLevel...` |
| T79-3-08 | FEFO pick: earliest expiry first, availableQty, shortBy; boundaries 1 and 999999; 400 for 0, 1,000,000, missing, text; unknown product 404 | `...theFefoPick...` (2 tests) |
| T79-4-01 | All stores: owner sees both stores and the unit total; a pharmacist only the mapped store | `...theOwnerSeesEveryActiveStore...` |
| T79-4-02 | A pharmacist mapped to no store: 200, rows [], empty true | `...aPharmacistMappedToNoStore...` |
| T79-4-03 | Status BELOW_REORDER at or below the level, OK above; my store the same | `...theStatusIsBelowReorder...` |
| T79-4-04 | My store for an unmapped store 404; unknown product 404 | `...myStoreForAStore...` |
| T79-3-09 | Guards: unmapped pharmacist 404, role without access 403, owner 200; another clinic 404; module off 403 | `...everyStockRead...`, `...anotherClinicsStore...`, `...theModuleSwitchedOff...` |
| T79-D-26 | `GET /context` lists readStock, postOpeningStock, adjustStock and the INVENTORY and APPROVALS_INBOX capabilities | `...theContextListsTheNewCapabilities...` |
| T79-5-01 | Standard rule: the adjustment waits and moves no stock; value = difference x purchase rate (6,050 paise) | `PharmacyWpDAdjustmentsApprovalsApiIT.anAdjustmentWithTheStandardRule...` |
| T79-5-02 | No level for STOCK_ADJUSTMENT: posts at once (decrease and increase); ledger and balances reconcile | `...whenNoRuleApplies...` |
| T79-5-03 | clientKey replay 200 with the same id; the same key with another body 409 client-key-reused | `...theSameClientKeyReplays...` |
| T79-5-04 | Second adjustment of the batch while one waits: 409 adjustment-waiting with the sentence | `...aSecondAdjustmentOfTheSameBatch...` |
| T79-5-05 | bookQtySeen differs: 409 changed with current | `...whenTheBookQuantityChanged...` |
| T79-5-06 | Validation (no change, unknown reason, 1,000,000, -1, unknown batch, empty body, not JSON); 0 is accepted | `...theAdjustmentIsValidatedAllTogether...` |
| T79-5-07 | A stale screen of a batch emptied by an earlier adjustment is refused as changed | `...aStaleScreenOfABatchEmptied...` |
| T79-5-08 | List newest first, status filter, paging, 400s; one adjustment with history | `...theListIsNewestFirst...` |
| T79-5-09 | Another clinic 404; non-capable 403; unmapped 404; module off 403 | `...anAdjustmentOfAnotherClinic...` |
| T79-2-01 | Inbox: row fields, counts, signedInAs, chip filter changes items not counts, a plain pharmacist 403, 400s | `...theInboxListsTheWaitingRequest...` |
| T79-2-02 | Empty inbox; another clinic's requests never show | `...theInboxOfAClinicWithNothing...` |
| T79-2-03 | Self-approval 403 with the sentence; the raiser is not in his own inbox; another owner approves and the approval posts | `...anOwnerWhoRaisedTheAdjustment...` |
| T79-2-04 | Level 2 needs another person; the level-1 approver gets the second-level sentence; posts only after level 2 | `...aSecondLevelNeedsAnotherPerson...` |
| T79-2-05 | Stale or repeated decision 409 already-decided with current; same key replays; posted once | `...aStaleOrRepeatedDecision...`, `...aWrongLevelOrAWrongRequestVersion...` |
| T79-2-06 | Reject needs a reason of the approval-reject list; other-list reason 400; indentLines on a non-indent 400; no stock moves | `...aRejectionNeedsAReason...` |
| T79-2-07 | A handler refusal (insufficient-stock) rolls the whole decision back | `...whenThePostingIsRefused...` |
| T79-2-08 | Who may read a request: raiser, owner, approver; others and another clinic 404 | `...whoMayReadARequest...` |
| T79-2-09 | Module off refuses inbox and decision | `...theModuleSwitchedOffRefusesTheInbox...` |
| T79-I11-01 | Ledger sums equal balances; every POSTED document with a request has it APPROVED; every APPROVED request has an approved decision per applying level by another person | `...afterAPostingAndAnAdjustment...` |
| T79-5-10 | Schedule X: owner 403 schedule-x-pharmacist; missing signer 400 on `secondSigner` | `...aScheduleXAdjustmentByAnOwner...` |
| T79-5-11 | Signer: the caller, a nurse, an unknown id: second-signer-invalid; no PIN 409 pin-not-set; wrong PIN 403 wrong-pin; nothing written | `...theSecondSignerMustBeAnother...` |
| T79-5-12 | Right PIN: posted, signer on the ledger line, the PIN never echoed or audited | `...aScheduleXAdjustmentWithTheRightPin...` |
| T79-5-13 | A rejected Schedule X adjustment moves no stock | `...aRejectedScheduleXAdjustment...` |
| T79-5-14 | Second signers: never the caller, no nurse, no mobile number, three fields, sorted; owner 403, unmapped 404, other clinic 404, bad id 400, module off 403 | `...theSecondSignerList...` (2 tests) |
| T79-5-15 | Active reasons: lists with the note flag; unknown or missing lists 400; a switched-off reason not listed | `...theActiveReasons...` |
| T79-6-01 | Store guard: Central Store user and owner pass; owner at another store 409 doc-state; mapped non-central pharmacist 403; unmapped 404; module off 403 | `PharmacyWpEPurchaseOrderApiIT.onlyTheCentralStoreUser...` |
| T79-6-02 | Reorder: at or below the level; suggestedQty = ceil(need / 10), at least 1; 0 with openPoNo when covered exactly; allStoresStock owner only | `...theReorderList...`, `...anOpenOrderCounts...` |
| T79-6-03 | Manual PO: DRAFT, no number, version 0, 70,000 + 8,400 = 78,400 paise | `...aManualPoIsADraft...` |
| T79-6-04 | Replay and key reuse | `...theSameClientKeyReplays...` |
| T79-6-05 | Create rules (11 bad bodies, a vendor that does not supply the Central Store) and the boundaries | `...theCreateRulesAreChecked...` |
| T79-6-06 | From suggestions: one draft per vendor, merged for one vendor, replay; bad rows 400 and nothing half created | `...aDraftIsCreatedPerVendor...`, `...theSuggestionRowsAreValidated...` |
| T79-6-07 | Edit: current version saves; stale 409 changed; a submitted order 409 doc-state; discard hides a draft | `...aDraftCanBeEdited...`, `...aSubmittedOrderCannotBeEdited...` |
| T79-6-08 | Submit: number taken; no level means APPROVED; Standard rule waits in the inbox and another owner approves; stale version 409; not a draft 409 | `...submitTakesTheNumber...`, `...withTheStandardRule...`, `...submitOfAnOrderThatIsNotADraft...` |
| T79-6-09 | Mark sent: APPROVED only; a future date or one before approval 400 | `...markSentNeedsAnApprovedOrder...` |
| T79-6-10 | Cancel withdraws the request and keeps the number; reason required; a draft 409; close short only PARTLY_RECEIVED (only the refusal can be tested until 79-7 exists) | `...cancellingAWaitingOrder...`, `...aDraftCannotBeCancelled...` |
| T79-6-11 | Copy: only a REJECTED order; same vendor, lines and credit days, delivery null, replay | `...aRejectedOrderCanBeCopied...` |
| T79-6-12 | List newest first, tiles ignore the filter, status filter, overdue, 400s, paging | `...theListIsNewestFirst...` |
| T79-6-13 | RLS across two clinics on every operation; a vendor of another clinic 400 | `...anOrderOfAnotherClinic...` |
| T79-6-14 | Print data and the rule note | `...thePrintDataAndTheRuleNote...` |
| T79-1710-01 | `guard_opening_stock_import_update`: nine cases (REJECTED final; DRAFT to REJECTED; no posting in a draft; no draft to APPROVED; counters never down; identity frozen; WAITING frozen and its moves; APPROVED one posting then final) | `PharmacyRound4DbIT` (5 tests) |
| T79-1710-02 | `next_document_no`: ten types, `last_issued_at`, unknown type 22023, no tenant 42501, no unit P0002, year restart, clinics apart | `PharmacyRound4DbIT.nextDocumentNo...` (3 tests) |
| T79-L11-01 | A session with an issued number open makes `for update` on the unit wait (55P03) and the reverse; two issues do not block each other | `PharmacyRound4DbIT.whileOneSessionHoldsAnIssuedNumberOpen...` |
| T79-L11-02 | A series save while an issue is open waits, then 409 series-locked after the commit | `PharmacyRound4DbIT.aSeriesSaveWhileAnIssueIsHeldOpen...` |
| T79-I11a | A request becomes APPROVED only with an APPROVED decision per applying level (none: permissive) | `PharmacyRound4DbIT.aRequestBecomesApproved...` |
| T79-I11b | The rule-set pointer moves only to a newer version | `PharmacyRound4DbIT.theRuleSetPointer...` |
| T79-m3 | The payable check: definition and validation only (a payable row needs a posted receipt, 79-7 is not built) | `PharmacyRound4DbIT.thePayableCheck...` |
| T79-m4 | The indent moves forward only: 9 moves allowed, 10 refused | `PharmacyRound4DbIT.anIndentMovesForwardOnly...` |
| T79-m5 | A store cannot go INACTIVE while a balance holds qty above 0 in any of the four states | `PharmacyRound4DbIT.aStoreCannotGoInactive...` |
| T79-m6 | `ix_stock_ledger_store_time` on the parent and every partition | `PharmacyRound4DbIT.theLedgerHasTheStoreTimeIndex...` |
| T79-L13 | The database refuses the same person as signer, or as raiser and decider; every created_by, actor, signer and decider column holds a membership id after a real flow | `PharmacyRound4DbIT.theDatabaseRefusesTheSamePerson...`, `...everyCreatedByActorAndSignerColumn...` |
| T79-L14-01 | 17 types x adopted and own Schedule X: a person's line without a signer is refused ("schedule X movement needs a second signer"), with another signer accepted | `PharmacyScheduleXLedgerAndAuditSignerDbIT.forEachOfTheSeventeenTypes...` |
| T79-L14-02 | The 7 unlisted types, a SYSTEM line and a Schedule H product pass; opening stock of a Schedule X item passes (A-8) | `...theSevenTypesThatAreNotListed...` |
| T79-L14-03 | The trigger is on every monthly partition; a line two months ahead is guarded | `...theTriggerIsOnTheMonthlyPartitions...` |
| T79-1730-01 | Audit second signer: not at insert, not while COUNTING or RECOUNT, never the submitter, never a person who counted, set once, bookkeeping passes, CLOSED final | `PharmacyScheduleXLedgerAndAuditSignerDbIT` (3 tests) |
| T79-I15-01 | A function defined by more than one migration keeps the messages of its earlier versions | `PharmacyFunctionGuardrailIT.everyPharmacyFunctionDefinedByMoreThanOneMigration...` |
| T79-I15-02 | Live bodies of the four functions; winners 1710, 1720, 1730; the two new entries with snippet "second signer" | `...theLiveBodiesCarryTheKeyBehaviour...` |
| T79-I15-03 | Migrations 1710, 1720 and 1730 applied, none failed | `...theMigrationsOf1710To1730...` |
| T78-R5-* | D-3 both bodies; limit 9,999,999,900 and 9,999,999,901; vendor version 0; `numbersIssued` | `PharmacyHardeningImportIT` (renamed), `PharmacyRound5ContractIT` (4 tests) |

Not an integration test: `numbersIssued` by financial year with a fixed clock is the java-developer's `NumberingServiceTest` (around 1 April 2027).

## 7. e2e (mocked API, phone-360 and desktop-1440)

| # | Spec | Test |
| --- | --- | --- |
| T79-D-E01 | `pharmacy-inventory-wpd.spec.ts` | Batch inventory: summary, pills, FEFO table, "Mar 2028", no sideways scroll; error path: a busy server shows the error block |
| T79-D-E02 | same | Store stock: My store and All stores with the unit total |
| T79-D-E03 | same | Approvals inbox: review and approve send level, version, clientKey; error path: self-approval sentence; reject needs a reason chip |
| T79-D-E04 | same | Stock adjustment: batch, count, reason chip, the book quantity seen is sent; error path: 409 changed |
| T79-E-E01 | `pharmacy-purchase-orders.spec.ts` | Reorder row with the suggested quantity; "Create POs" posts rows with vendor; the draft shows with its tile |
| T79-E-E02 | same | Open the draft, submit, number PO-0001 |
| T79-E-E03 | same | Error path: vendor-blocked sentence; another store shows the not-central sentence |
| not written | receiving (79-7), vendor return (79-8) | their backend does not exist yet |
| not written | "Post to stock" on an approved import; the Schedule X signer step of the adjustment form | next run |

## 6b. Round 6 and 7 tests (added 19 Nov 2026, 15:30 onward; all in `backend/src/test/java/com/sahaj/platform/it/`)

| Test | What it proves | Class |
| --- | --- | --- |
| T79-1740-01 | A note with `SHORT_SUPPLY` lines only moves CREATED and PICKED to CREDIT_NOTE_RECEIVED with no hand-over time | `PharmacyRound5DbIT` |
| T79-1740-02 | A note with a live `STOCK_RETURN` line is refused the same moves (`debit note cannot go from CREATED to CREDIT_NOTE_RECEIVED`); HANDED_OVER to CREDIT_NOTE_RECEIVED accepted | same |
| T79-1740-03 | A removed `STOCK_RETURN` line counts as none; a mix of goods and short supply needs the hand-over; DRAFT, WAITING_APPROVAL and CANCELLED to CREDIT_NOTE_RECEIVED refused; CREDIT_NOTE_RECEIVED stays final | same |
| T79-1740-04 | `ck_debit_note_handed_over_status` refuses HANDED_OVER with no time, is validated, and no longer names CREDIT_NOTE_RECEIVED | same |
| T79-1740-05 | The credit insert per state: accepted in CREATED and PICKED for a short-supply-only note, refused with goods (`credit note can be recorded only after hand-over`), accepted in HANDED_OVER, refused in DRAFT, WAITING_APPROVAL, CANCELLED; a second credit refused by `uq_debit_note_credit_note` | same |
| T79-1740-06 | Goods receipt APPROVED to DISCARDED accepted and the invoice number free again; APPROVED to POSTED and DRAFT; POSTED and DISCARDED final; DRAFT to APPROVED refused | same |
| T79-1740-07 | Purchase order SENT to CLOSED_SHORT refused; PARTLY_RECEIVED to CLOSED_SHORT accepted | same |
| T79-1740-08 | L16: a SYSTEM line for GRN, VENDOR_RETURN_HOLD, OPENING_STOCK refused by `ck_stock_ledger_system_types` in two months; SYSTEM EXPIRY_QUARANTINE accepted; a USER line of each type with an actor accepted | same |
| T79-1740-09 | The check is on the parent, on every partition (validated) and on a partition made afterwards | same |
| T79-1740-10 | m-7: the function comment says "StockPosting takes no store lock" | same |
| T79-L14-fix | A SYSTEM line of any type but EXPIRY_QUARANTINE is refused; the unlisted types and a Schedule H product pass with an actor and no signer | `PharmacyScheduleXLedgerAndAuditSignerDbIT` (changed) |
| T79-D8-fix | An unknown field after the cap answers 400 `malformed-request` or `validation` | `PharmacyHardeningImportIT` (changed) |
| T79-D4-fix | Capabilities of a plain pharmacist, a HOD and the owner | `PharmacyWpDStockReadsApiIT` (changed) |
| T79-R6-01 | D-6 and D-5: the inbox and one request with a waiting purchase order answer 200; with no saved rules the owner approves; no rule version row; `ruleVersionNo` 0; the first save is version 1 and the old request keeps 0 (needs migration 1750) | `PharmacyRound6ApiIT` |
| T79-R6-02 | D-5 for a first stock adjustment: WAITING_APPROVAL, the raiser cannot approve, a second owner can | same |
| T79-R6-03 | D-4: capabilities of owner, HOD, delegate, pharmacist, Central Store user and store staff | same |
| T79-R6-04 | M-2: a signer for a non-Schedule-X batch or from a non-pharmacist makes no PIN attempt; the control makes one | same |
| T79-R6-05 | L17: a missing `membershipId` is 400 `secondSigner`; a PIN of 3, 5 digits, letters, a space or empty or null is 400 `secondSigner.pin`; no try is counted | same |
| T79-R6-06 | M3: a Central Store user who is not a pharmacist gets only id, name, status, blocked and version on the list and one vendor; owner, pharmacist and store pharmacist get the full record; `?view=full` changes nothing | same |
| T79-R6-07 | L15: two parallel creates with one key make one order | same |
| T79-R6-08 | L18: a mapped person without the document's read capability gets 404 on its approval request | same |
| T79-R6-09 | D-7: `q` of 1 character is 400; 2 and 60 pass; 61 is 400 with "Type at most 60 characters." | same |
| T79-7-* | Receipt: post against an order (batches, balances, ledger, payable, INVOICE entry, the order's received quantities, the debit-note draft), short line without action 400, the strip case (10,000,000 paise), rate-difference claim, rate-rise approval and post after approval, discard of an approved receipt, reopen, batch-mrp-mismatch, duplicate invoice, blocked vendor, invoice total within 100 paise, Schedule X (pharmacist and signer), M-2 on a receipt, roles and two clinics, 400 on bad bodies, a switch-off against a post, two posts of one receipt, 66 KB accepted and 130 KB refused | `PharmacyWpEReceiptApiIT` |
| T79-8-* | Vendor return: eligible rows and the credit level, create, submit at level 0 (hold), picked, hand-over, credit note applied oldest due first with the remainder unapplied, cancel releases the hold, approval then hold, short-supply-only note from CREATED and PICKED, goods plus short needs hand-over, validation, replay, roles and two clinics, Schedule X hand-over, ledger and balance and payable reconciliation | `PharmacyWpEDebitNoteApiIT` |
| T79-9-*, T79-10-* | Indents: raise, replay, validation, Central Store 409, edit and stale version, discard, another person's draft 404, submit at level 0 and with the Standard default, the HOD's decision with changed quantities, remark and zero rules, reject, cancel (waiting; approved; refused after a dispatch), lists, two clinics, module off | `PharmacyWpFIndentApiIT` |

Not reachable through the API, so not tested here: a vendor-return row that is both damaged on receipt and expired (needs the damage report of WP-G and a batch past its expiry), so the "lower of the two levels" rule has only its unit test; an indent from a store with no supplying store (the server always sets Issues-from to the Central Store).

## 7b. e2e added in round 7 (`frontend/e2e/pharmacy-indents-wpf.spec.ts`, mocked API, phone-360 and desktop-1440)

| # | Test |
| --- | --- |
| T79-F-E01 | Indents tab: raise an indent, submit it, the card shows the number and "Waiting for HOD approval"; error path: the Central Store sentence |
| T79-F-E02 | The approver's view: reduce a line (remark chip needed), approve |
| T79-F-E03 | Dispatch with the pick list and a partial back-order; the Schedule X second signer step |
| T79-F-E04 | Receive with accepted and rejected quantities and the `receipt-reject` chips; a Schedule X line is skipped by a non-pharmacist |
| T79-F-E05 | "Waiting for action": accept, dispute and settle a shortage |
| T79-F-E06 | "Return" on a quarantined batch opens the vendor return with the batch and vendor chosen; the credit-days hint "From the vendor" |

## 8. Results of this run

### 8.1 The 12:45 run (rounds 5 and 6, before this one)
- **Migration 1730 applied cleanly** with 1710 and 1720 (Flyway "now at version v2026.12.17.1730"); `PharmacyFunctionGuardrailIT` passed.
- **Gate** (`validate db`, 10:50 to 12:11, one Maven only): unit passed; integration 3,527 run, 22 failures, 11 errors, 25 skipped. All 33 were in QA's 7 new or changed classes (about half were QA's own faults: a missing `@WithPinPepper`, a tenant binding lost after a rollback, a wrong column name, fixtures that assumed a Standard rule, clientKey semantics, a constraint counted per partition); the other 3,494 passed.
- **Per class after the fixes:** `PharmacyWpDPostingApiIT` 14 (all pass), `PharmacyWpDStockReadsApiIT` 19 (17 pass), `PharmacyWpDAdjustmentsApprovalsApiIT` 27 (all pass), `PharmacyWpEPurchaseOrderApiIT` 21 (18 pass), `PharmacyRound4DbIT` 18 (all pass), `PharmacyScheduleXLedgerAndAuditSignerDbIT` 7 (all pass), `PharmacyFunctionGuardrailIT` 3, `PharmacyRound5ContractIT` 4.
- **Defects:** D-4 (High, `ContextService.capabilities()` lacked the Inventory capabilities): **fixed** in java round 6, verified by the hook gate. D-5 (High, no saved rule set: the engine planned no level): **fixed** (the engine applies the served Standard default; the version link needs migration 1750). D-6 (High, the approvals inbox answered 500 with a waiting purchase order): **fixed**. D-7 (Low, `q` of one character answered 200): **fixed**. D-8 (Low, an unknown field after the cap answered 400 `validation`): **was a test that was too strict**; the test now accepts `malformed-request` or `validation`.

### 8.2 The java finish hook's `mvn verify` (13:40 to 15:20)
Unit passed; integration 3,527 run, 3 failures, 0 errors, 25 skipped; no code defect. Migration 1740 applied cleanly. The 3 failures were QA tests, fixed first in this run: `PharmacyHardeningImportIT` (unknown field), `PharmacyScheduleXLedgerAndAuditSignerDbIT` (a SYSTEM `GRN` line is now refused by `ck_stock_ledger_system_types`) and `PharmacyWpDStockReadsApiIT` (APPROVALS_INBOX is for the owner, a HOD or a current delegate).

### 8.3 This run (round 7)
Written 19 Nov 2026 15:30 to 16:30: `PharmacyRound5DbIT`, `PharmacyRound6ApiIT`, `PharmacyWpEReceiptApiIT`, `PharmacyWpEDebitNoteApiIT`, `PharmacyWpFIndentApiIT`, `Sah79BuySupport`, the three fixes above and the e2e spec. Gate results (19 to 20 Nov 2026, one Maven only):

- **`node scripts/validate.mjs db` cannot pass.** It stops before Maven with `V2026_12_17_1910__pharmacy_h1_register_row.sql: pharmacy.h has tenant_id but no row level security`. The check in `scripts/validate.mjs` line 38 reads table names with the pattern `[a-z_]+.[a-z_]+`, which has no digit, so it cuts `pharmacy.h1_register_row` at `h` and then looks for the wrong table. The migration is correct (it enables and forces row level security at lines 84 and 85). Owner: humans (`scripts/`); or the db-engineer renames the table. The full `mvn verify` was run directly instead.
- **Migrations:** 1740 and 1750 applied cleanly (earlier runs). The SAH-80 migrations `V2026_12_17_1800` to `2300` (17 files) applied cleanly to `sahaj_test` from the Flyway log: "Successfully applied 17 migrations to schema platform, now at version v2026.12.17.2300".
- **`mvn verify` (first full run, java developer's edits compiled in):** unit 6,504 run, 1 error (`ReturnShortageServiceTest.theWaitingListNeedsASideAndTheCapabilityOfThatSide`, NullPointerException at `ReturnShortageService.java:125`, a mock that returns null); integration 3,620 run, 26 failures, 12 errors, 25 skipped. 13 classes had failures; 11 of them were test faults caused by the java round 6 and the SAH-80 migrations and were fixed here (see below).
- **Fixed in the tests:** `PharmacyFunctionGuardrailIT` (migration 1800 is a third definer of `guard_stock_ledger_schedule_x` and the winner of `next_document_no`); `PharmacyRound4DbIT` and `PharmacyWpDPostingDocsIT` (CREDIT_NOTE and BILL are valid document types since 1800); `PharmacyWpDPostingDocsIT` and `PharmacyTablesIT` (70 seeded reasons, not 36); `RowLevelSecurityCatalogIT` (26 SAH-80 tenant tables, 11 of them insert-only, and `consumption` now has an update policy); `PharmacyHardeningImportIT` (`{"rows":"text"}` is `validation` or `malformed-request`); `PharmacyRound6ApiIT`, `PharmacyWpFIndentApiIT`, `PharmacyWpEDebitNoteApiIT` (a plain pharmacist gets 403 on the approvals inbox, D-4); and in the new class the product id is `productId`, a reason is `reasonKey`, a dispatch must list every item that still has a quantity, the import takes 200 rows a request, a Schedule X test needs `@WithPinPepper` and the signer's real PIN.
- **After the fixes (targeted reruns):** `PharmacyFunctionGuardrailIT` 3, `PharmacyHardeningImportIT` 23, `PharmacyRound4DbIT` 18, `PharmacyRound6ApiIT` 9, `PharmacyTablesIT` 15, `PharmacyWpDPostingDocsIT` 11, `PharmacyWpDStockReadsApiIT` 19, `PharmacyWpFIndentApiIT` 17, `RowLevelSecurityCatalogIT` 15: all pass. `PharmacyWpFDispatchReceiptApiIT` 23, 22 pass (D-QA-2 fails). `PharmacyWpEDebitNoteApiIT` 14 (11 pass) and `PharmacyWpEReceiptApiIT` 18 (16 pass): the 5 failures are defects D-QA-3 to D-QA-5 below (not changed). `PharmacyProductCatalogueIT` 17 (16 pass): D-QA-6.
- **Per class (new in rounds 6 and 7):** `PharmacyRound5DbIT` 12, `PharmacyRound6ApiIT` 9, `PharmacyWpEReceiptApiIT` 18, `PharmacyWpEDebitNoteApiIT` 14, `PharmacyWpFIndentApiIT` 17, `PharmacyWpFDispatchReceiptApiIT` 23.

**Defects found (not fixed by QA; main code is not QA's).**

| ID | Where | Expected | Actual | Owner |
| --- | --- | --- | --- | --- |
| D-QA-1 | `ReturnShortageServiceTest` line 310 / `ReturnShortageService.java:125` | the waiting list answers with the mocked slice | NullPointerException: the mocked repository returns null (a new repository call that the unit test does not stub) | java-developer |
| D-QA-2 | `DispatchReceiptService`, indent status after a receipt that skips a Schedule X line | pharmacy.yaml 0.3.4: the indent stays PARTLY_DISPATCHED with `chips.scheduleXWaiting` above 0 | the indent is DISPATCHED (test `aNonPharmacistSkipsTheScheduleXLine...` line 471) | java-developer |
| D-QA-3 | debit note create (short supply) | credit = qty x rate x credit %, and the test sends 100 | credit worked at the vendor's 80 % (6,400 not 8,000 paise; 8,000,000 not 10,000,000 in the strip case); either the sent `creditPercent` of a SHORT_SUPPLY line is ignored, or the 80 % default of the prepared draft is intended: decide | java-developer, architect |
| D-QA-4 | debit note create | 400 `validation` for an unknown vendor and for a batch that is not an eligible row (pharmacy.yaml pharmacyCreateDebitNote) | unknown vendor 404; a non-eligible batch 201 | java-developer |
| D-QA-5 | receipt against an order | the order line's `receivedQty` equals the billed quantity 10 of a line received 8 with 2 free (the test's reading) | 8 (the received quantity without the free units); read the contract and decide | architect |
| D-QA-6 | product catalogue search `q` of 61 characters | the SAH-78 test expects "Keep the search to 60 characters" | "Type at most 60 characters." (ProductSearchService line 60, the same sentence as the stock read) | java-developer or the SAH-78 test owner to choose one sentence |
| D-QA-7 | receipt with a missing line | pharmacy.yaml 0.3.5: field `lines` "Send every line not yet received, once." | "List every line of this dispatch that is not yet received" | java-developer |
| D-QA-8 | (closed) queued decisions of DEC-083 | M4, L20, L21 and I23 tests of section 8.4 | all four fail on the first full run and **pass** on the last targeted run: the java developer built the fixes in between | none |
| D-QA-9 | `scripts/validate.mjs` line 38 | the table name `pharmacy.h1_register_row` is read whole | read as `pharmacy.h` | humans (scripts) |

**e2e (mocked API, `frontend/e2e`, `playwright test pharmacy-`, built app).** Run after the frontend lock had been quiet for two hours (23:50). 199 passed, 2 skipped, 9 failed in 4 tests: (1) `pharmacy-indents-wpf` raise an indent on phone-360: the create call is not seen by the mock; (2) the HOD approval: the body carries `approvedQty: 6` but no `remarkReasonKey` for the reduced line; (3) the partial dispatch: `dispatch-backorder-0` is never found (desktop), the body is not found (phone); (4) "Return" opens the vendor return with the credit field at 80, the spec expects the eligible row's 70 (`vr-credit-percent-0`); and the older `pharmacy-products-opening-stock` test at line 785 (`local-G-000103` is not found, phone and desktop; it passed in earlier runs). Items 1 to 4 are the new WP-F spec against the angular developer's current build and need a look by both owners. Not written: separate specs for Post to stock, the Schedule X signer step of receiving and the vendor return of receiving (the screens exist: `grn-*`, `f-grn-signer`, `vr-*`; the existing specs of those screens were not extended in this run).


### 8.4 New in this run: dispatch, receipt, return items, shortages and the DEC-083 decisions (`PharmacyWpFDispatchReceiptApiIT`, 22 tests)

| Test | Rule | Test method |
| --- | --- | --- |
| T79-11-01 | Full dispatch: FEFO batch (TESTB001 first), Central AVAILABLE to the requesting store IN_TRANSIT in one movement, reference "<IND no> / 1", indent DISPATCHED, audit, payable untouched, ledger equals balances | `aFullDispatchTakesFefoBatches...` |
| T79-11-02 | One dispatch line per batch in FEFO order (102 units: 100 + 2) | `aDispatchOfMoreThanOneBatchSplits...` |
| T79-11-03 | Partial dispatch needs BACK_ORDER or CLOSE (with reason); all zero is "Nothing to dispatch"; above remaining 400; stale indent version 409 `changed`; second dispatch completes it | `aPartialDispatchNeedsABalanceAction...` |
| T79-11-04 | CLOSE with reason stops counting; the same clientKey replays (200) with no double dispatch | `aClosedBalanceNeedsAReason...` |
| T79-11-05 | Above Central stock: 409 `insufficient-stock`, nothing moves | `aDispatchAboveTheCentralStoreStock...` |
| T79-11-06 | Replacement of the same salt: options listed, reason needed, "replaced by Central Store" and the reason | `aReplacementOfTheSameSalt...` |
| T79-11-07 | Schedule X dispatch: owner 403 `schedule-x-pharmacist`, missing signer 400 `secondSigner`, wrong PIN 403, good PIN 201 and the signer on the ledger, PIN never echoed | `aScheduleXDispatchNeedsAPharmacist...` |
| T79-11-08 | Role and tenant: store user 403 or 404, front desk 403, another clinic 404, unknown field 400 | `dispatchPathsAreRefused...` |
| T79-12-01 | Receipt of accepted, rejected and short lines: states AVAILABLE, PENDING_RETURN, IN_TRANSIT (short); reason and sum rules 400; a missing line 400 "Send every line not yet received, once."; chips; RECEIVED then 409 `changed`; audit | `aReceiptOfAcceptedRejectedAndShortLines...` |
| T79-12-02 | Expired batch: accept 400; reject with another reason 400 `rejectReasonKey`; reject as `receipt-reject.near-expiry` 200 (the batch is expired in the test through a committed update) | `anExpiredBatchCannotBeAccepted...` |
| T79-12-03 | Non-pharmacist: Schedule X with skipped false 403; skipped on a non-Schedule-X line 400; PARTLY_RECEIVED with `chips.scheduleXWaiting`; the indent stays PARTLY_DISPATCHED; a re-send of only skipped lines 400 "There is nothing to receive."; pharmacist receives the one line (missing signer 400, older dispatch version 409); indent RECEIVED; no indent ever PARTLY_RECEIVED | `aNonPharmacistSkipsTheScheduleXLine...` |
| T79-12-04 | A back-order keeps the indent PARTLY_DISPATCHED after the first receipt; it never moves backwards; RECEIVED when all is dispatched and received | `anIndentWithABackOrderStays...` |
| T79-12-05 | Role and tenant: Central Store user 403 or 404 at the receiving store, front desk 403, wrong path store 404, another clinic 404, 401 lines 400, the incoming list and its bad status 400 | `theReceiptPathsAreRefused...` |
| T79-13-01 | Accept a rejected item into QUARANTINE: default chip, the holding store cannot accept, no balance moves before accept (BR-25), result note, second accept 409, audit, chip cleared | `theCentralStoreAcceptsARejectedItem...` |
| T79-13-02 | Dispute (reason `dispute.other`), units stay at the holding store, reply (note under 4 characters 400), accept SALEABLE afterwards | `aDisputedItemShowsTheReason...` |
| T79-13-03 | An expired item: default QUARANTINE, SALEABLE 409 `batch-expired` | `anExpiredItemCannotBeAcceptedAsSaleable` |
| T79-13-04 | Shortage settled FOUND: units back to Central AVAILABLE; a second settle 409; audit | `aShortageFoundInTheCentralStore...` |
| T79-13-05 | CARRIER_CLAIM keeps IN_TRANSIT (CLAIM_OPEN); TRANSIT_LOSS at level 0 writes off, no stock added back | `aCarrierClaimKeepsTheUnits...` |
| T79-13-06 | Shortage list: store user 403 or 404, another clinic empty and 404 on settle, an unknown outcome 400 | `theShortageListIsRefused...` |
| T79-Q-M4 | **Queued, expected to fail until built.** A dispatch that picks 401 dispatch lines (one line, 401 batches of 1 unit) is 400 `lines` "Split this dispatch into smaller ones."; 400 lines still pass | `m4aDispatchThatPicksMoreThan400...` |
| T79-Q-L20 | **Queued.** Two levels: the store approver raising above the requested quantity is 400 `indentLines` "This increase needs the next approver."; the owner at the last level may | `l20AnApproverBelowTheLastLevel...` |
| T79-Q-I23 | **Queued.** A transit loss of Schedule X units at level 0 by an owner (not a pharmacist) is 403 `schedule-x-pharmacist`, nothing written off; the Central Store pharmacist may | `i23ANonPharmacistCannotWriteOff...` |
| T79-Q-L21 | **Queued.** A missing signer `membershipId` is field `secondSigner` with "A second signature is needed for Schedule X items."; a bad PIN is `secondSigner.pin` | `l21AMissingSignerMembership...` |

Not covered by an automated test: the line-count refusal after FEFO is tested with 401 batches of one line; a dispatch rule that depends on a delegate is not. The expiry of a batch is set by a committed update through a tenant-bound connection in `expire()`; if the application role may not update `pharmacy.batch`, T79-12-02 and T79-13-03 fail on that update and the helper must change.

## 9. WP-G and WP-H tests (written 20 Nov 2026; NOT YET RUN: see section 10)

Support: `Sah79StoreUseSupport` (the buy world plus the clean opening-stock file posted into the Main Pharmacy and a Ward 3B store, the pharmacist mapped at both). Classes in `backend/src/test/java/com/sahaj/platform/it/`.

| Test group | Class | What it proves |
| --- | --- | --- |
| T79-14-01..04 store return | `PharmacyWpGStoreUseApiIT` | AVAILABLE to PENDING_RETURN, item WAITING, nothing enters the Central Store, audit row; replay by clientKey; 400 bad input (quantity 0, above stock "Enter a whole number from 1 to 100", bad reason, unknown batch, unknown field, missing clientKey); Central Store, other clinic (404) and front desk (403) refused |
| T79-14-05..09 damage report | same | level 0 WRITE_OFF, level 0 SEND_TO_CS, waiting then approved as write-off, approved as send to CS, rejected with RELEASE_TO_AVAILABLE and KEEP_IN_QUARANTINE, reporter cannot approve, `approval_request_id` attached with a version bump, outcome wording, bad input and Central Store SEND_TO_CS refused |
| T79-15-01..07 transfers | same | create at level 0 (nothing moves), dispatch then receipt with ledger equal to balances, waiting approval blocks dispatch, cancel keeps the number, 400 rules (same store, to Central, quantity, duplicate, reason, no lines), dispatch after stock was taken is 409 insufficient-stock, transfer-stores read, other clinic 404, front desk 403 |
| T79-16-01..05 consumption and ward link | same | general consumption FEFO and replay, 400 and 409 rules, patient entry and admitted-patients answer integration-off, ward link owner-only and integration-off, ordered-by options, tenant and role |
| T79-17-01..09 stock audit | `PharmacyWpHAuditReportsApiIT` | full lifecycle to CLOSED with ledger equal to balances; count sheet has exactly the nine blind keys for counter and owner; counter sees no variance figure and gets 403 on the variance read; count validation and stale version; recount returns the line blind; stock moved gives 409 stock-moved; submitter cannot approve (403 self-approval); StockShort refusal commits the recount state and a replay posts nothing; scope, tenant and role refusals |
| T79-17-10 rule 24 | same | no quantity of the audited product reaches a blind counter through overview, batches, product, low stock, FEFO pick, states, all-stores, reorder suggestions, adjustments, three stock reports, the stock ledger report and its CSV; messages carry no number (return, damage, transfer dispatch, consumption); the owner sees quantities (the check has teeth) |
| T79-17-11 guard | same | `stock_audit_line` guard: counts after submit, raising the book quantity, identity change, final after close; lowering the book quantity is accepted |
| T79-18-01..05, T79-19-01 reports | same | catalogue tiers and labels; ten reports run with typed columns, paging, filters and bad input; range over 366 days 400 range-too-long on screen and as CSV problem+json; CSV with BOM, filename and audit rows; every CSV error is application/problem+json with Accept text/csv; 50,001 rows give 409 export-too-large and nothing is sent |
| T79-H-01 inbox | same | type filter, counts ignore the type, 400 on bad type, size and page, plain pharmacist 403, other clinic empty |
| DEC-086 items 3, 4 | `PharmacyWpEReceiptApiIT`, `PharmacyProductCatalogueIT` (changed) | order line receivedQty is 8 (free units not counted); "Type at most 60 characters." |
| e2e | `pharmacy-indents-wpf.spec.ts` (changed) | fefo-pick mock has the FefoPick shape; the cs-change.out-of-stock chip is clicked after Back-order |

Not testable here: the inbox 2,000 request cap (needs 2,001 pending requests), a patient-wise consumption with a real admission (IPD reader is a no-op in phase 1), a Schedule X audit with a second signer (not written yet).

## 10. Results of the 20 Nov 2026 gate run

Not run. `mvn verify` on a snapshot compiled, but every integration test stopped at Flyway validation: "Migration checksum mismatch for migration version 2026.12.17.1800". The recreation of sahaj_test and sahaj_dev (`node scripts/setup/create-database.mjs`, which also does not drop a database that exists) was refused by the permission system, so the databases still hold the old migration 1800. A separate defect stops the application from starting at all (see the handoff).

## 11. SAH-80 Pharmacy sales tests (written 21 Nov 2026, qa-engineer; integration tests COMPILED, NOT RUN)

Contract: `docs/api/pharmacy-sales.yaml` 0.3.7 (DEC-088, DEC-089, DEC-090). Support: `Sah80SalesSupport` (the buy world, a cashier mapped at the Main Pharmacy, a Ward 3B store where sale is not allowed, opening stock of product A, product B, an own Schedule H1 product and an own Schedule X product posted into the pharmacy). Classes in `backend/src/test/java/com/sahaj/platform/it/`. The integration tests have not run: `PharmacyRound5DbIT` on a snapshot stopped again at "Migration checksum mismatch for migration version 2026.12.17.1800" (21 Nov). A human must drop `sahaj_test` and `sahaj_dev` and run `node scripts/setup/create-database.mjs`. The first run will also find wrong guesses of this plan (an exact problem type, a field name); those are test faults to fix, not defects, unless the contract says otherwise.

### 11.1 Criterion to test to result

| Criterion (story) | Test (class, method) | Result |
| --- | --- | --- |
| 80-4 create customer, replay, same phone needs confirmation, candidates masked | `PharmacySalesCoreApiIT.aWalkInCustomerIsCreatedReplayedAndTheSamePhoneNeedsConfirmation` | written, not run |
| 80-4 phone masked for a cashier, input limits, unknown field 400 | `PharmacySalesCoreApiIT.aCustomerPhoneIsMaskedForACashierAndInputIsChecked` | written, not run |
| tenant isolation and role 403 (customers) | `PharmacySalesCoreApiIT.customersOfAnotherClinicAreInvisibleAndARoleWithoutSellingIsRefused` | written, not run |
| 80-3 product search (stock, no cost fields, 400 rules) | `PharmacySalesCoreApiIT.theProductSearchFindsAnActiveProductWithItsStockAndRefusesBadInput` | written, not run |
| 80-3 quote FEFO split over two batches, totals, short line cannot save, a quote writes nothing | `PharmacySalesCoreApiIT.aQuoteSplitsALineByFefoAndWorksTheTotalsAndAShortLineCannotBeSaved` | written, not run |
| 80-3 quote 400 (quantity, empty, 101 lines), unknown product 404, ward store 409 sale-not-allowed | `PharmacySalesCoreApiIT.aQuoteRefusesAMalformedCartWithAProblemBody` | written, not run |
| 80-3 save bill moves stock, ledger COUNTER_SALE, gapless numbers (a failed save takes none) | `PharmacySalesCoreApiIT.aSavedBillMovesStockWritesTheLedgerAndTakesTheNextNumberWithNoGap` | written, not run |
| 80-3 split bill takes both batches; double tap saves once | `PharmacySalesCoreApiIT.aSplitBillTakesBothBatchesAndTheDoubleTapSavesOnce` | written, not run |
| 80-3 sale-changed carries the new quote; payment and key checks | `PharmacySalesCoreApiIT.aChangedCartIsRefusedWithTheNewQuoteAndInputIsChecked` | written, not run |
| tenant isolation and role 403 (bills) | `PharmacySalesCoreApiIT.aBillOfAnotherClinicIsInvisibleAndTheListShowsOnlyThisStore`, `theSaleOfAnotherClinicsStoreIsNotFoundAndTheWardStoreRefusesASale` | written, not run |
| 80-5 hold, read, discard, 20 held bills limit | `PharmacySalesCoreApiIT.aHeldBillIsListedReadAndDiscardedAndTheTwentyFirstIsRefused` | written, not run |
| 80-5 cancel into QUARANTINE, number kept, cashier 403, already-cancelled | `PharmacySalesCoreApiIT.aCancelledBillGoesToQuarantineKeepsItsNumberAndCannotBeCancelledTwice` | written, not run |
| 80-5 cancel back on the shelf, reason other needs a note | `PharmacySalesCoreApiIT.aBillCancelledBackOnTheShelfReturnsToAvailableAndAReasonOtherNeedsANote` | written, not run |
| 80-5 refund after cancel (no modes 400, recorded once, live bill 409) | `PharmacySalesCoreApiIT.aRefundOfACancelledBillWaitsForTheOwnersRefundModesAndIsRecordedOnce` | written, not run |
| 80-6 within the limit saves, above needs approval, 409 discount-approval-needed | `PharmacySalesDiscountApiIT.aDiscountWithinTheLimitSavesAtOnceAndAboveItNeedsApproval` | written, not run |
| 80-6 request pending, read, inbox row, withdraw twice, audit once | `PharmacySalesDiscountApiIT.aRequestIsPendingReadWithdrawnAndWithdrawingTwiceChangesNothing` | written, not run |
| 80-6 approved request saves; a bigger cart is VOID, 409 discount-approval-void | `PharmacySalesDiscountApiIT.anApprovedRequestLetsTheCashierSaveAndAChangedCartVoidsIt` | written, not run |
| 80-6 reject with reason; a pharmacist cannot decide | `PharmacySalesDiscountApiIT.aRejectedRequestIsShownWithItsReasonAndTheRaiserCannotApproveHisOwn` | written, not run |
| DEC-090 owner exempt: quote, request (required false, no request, no audit), createBill (any id ignored), role read-out ALLOW | `PharmacySalesDiscountApiIT.theOwnerIsExemptFromTheDiscountRuleEverywhere` | written, not run |
| DEC-089 discarding a held bill withdraws its pending request | `PharmacySalesDiscountApiIT.discardingAHeldBillWithdrawsItsPendingRequest` | written, not run |
| 80-6 a new request withdraws the old one; percent 400; other clinic 404; front desk refused | `PharmacySalesDiscountApiIT.aNewRequestOfTheSameSellerWithdrawsTheEarlierOneAndInputIsChecked` | written, not run |
| 80-18 pharmacist return completes at once, credit note CN/, stock back, ledger SALES_RETURN | `PharmacySalesReturnApiIT.aPharmacistReturnOfSealedItemsCompletesAtOnceWithACreditNoteAndStockComesBack` | written, not run |
| 80-18 over-return, wrong refund sum, zero, bad condition, unknown field: 400, nothing moves | `PharmacySalesReturnApiIT.aReturnAboveWhatWasSoldOrWithAWrongRefundIsRefusedAndMovesNothing` | written, not run |
| 80-18 no return window: 409 return-window-not-set with the sentence | `PharmacySalesReturnApiIT.withoutAReturnWindowTheReturnIsRefused` | written, not run |
| 80-18 O-8 cashier return waits, cashier cannot accept, pharmacist accepts and finishes | `PharmacySalesReturnApiIT.aCashierReturnWaitsForThePharmacistWhoAcceptsAndFinishesIt` | written, not run |
| 80-18 opened item to QUARANTINE | `PharmacySalesReturnApiIT.aPharmacistCanSendAnOpenedItemToQuarantineAndRefuseAnother` | written, not run |
| 80-18 SALES_RETURN rule: waits for the owner, approval, completes | `PharmacySalesReturnApiIT.aReturnRuleThatNeedsTheOwnerWaitsForApprovalThenCompletes` | written, not run |
| tenant isolation and role 403 (returns) | `PharmacySalesReturnApiIT.aReturnOfAnotherClinicIsInvisibleAndAnUnmappedRoleIsRefused` | written, not run |
| 80-9 H1 bill needs a prescription and a pharmacist; register row with the patient name; cashier 403 | `PharmacySalesRegistersApiIT.anH1BillNeedsAPrescriptionAndAPharmacistAndWritesARegisterRowWithThePatientName` | written, not run |
| 80-9 missing prescriber blocks until a pharmacist overrides; override in the register | `PharmacySalesRegistersApiIT.aMissingPrescriberBlocksTheBillUntilAPharmacistOverridesWithAReason` | written, not run |
| 80-9 cancel adds a Cancelled reversal, register append only | `PharmacySalesRegistersApiIT.cancellingAnH1BillAddsACancelledReversalToTheRegister` | written, not run |
| 80-9 range-too-long, bad date 400, other clinic 404, filter | `PharmacySalesRegistersApiIT.theH1RegisterRangeIsCheckedAndAnotherClinicSeesNothing` | written, not run |
| 80-10 Schedule X needs the second signer (400 field, same person 403, cashier 403); ledger carries second_signer; register and entries carry both | `PharmacySalesRegistersApiIT.aScheduleXBillNeedsTheSecondSignerAndTheLedgerAndRegisterCarryBothSigners` | written, not run |
| 80-10 cancel of an X bill: reversal and stock back | `PharmacySalesRegistersApiIT.cancellingAScheduleXBillAddsAReversalAndPutsTheStockBack` | written, not run |
| DEC-089 h1-register report masked per store (owner and pharmacist member full; HOD masked; pharmacist of another store none) | `PharmacySalesRegistersApiIT.theH1ReportShowsTheNameInFullOnlyToTheOwnerAndAPharmacistMemberAndMasksItForOthers` | written, not run |
| DEC-089 h1 export owner and pharmacist only, audited | `PharmacySalesRegistersApiIT.theH1ReportExportIsForTheOwnerAndAPharmacistMemberOnlyAndIsAudited` | written, not run |
| 80-20 command centre card isolation per store, all stores for the owner, cashier gets today's sales only, no gross margin | `PharmacySalesCommandApiIT.theCardsOfOneStoreNeverCountTheBillsOfAnotherStoreAndACashierGetsTodaysSalesOnly` | written, not run |
| 80-20 cancelled not counted; 400 without a store; 404 other clinic; 403 front desk | `PharmacySalesCommandApiIT.aCancelledBillIsNotCountedAndTheStoreAndRoleRulesApply` | written, not run |
| 80-19 role read-out, activity feed text without a patient name, type filter, 403 cashier, other clinic empty | `PharmacySalesCommandApiIT.theRoleReadoutAndTheActivityFeedAreForTheOwnerAndAPharmacistAndBuiltFromIdsOnly` | written, not run |
| 80-11, 80-13, 80-15 OPD and IPD queues answer connected false; a write answers 409 not-connected | `PharmacySalesCommandApiIT.theOpdAndIpdQueuesAnswerNotConnectedAndTheirWritesAnswerNotConnected` | written, not run |
| 80-21 SALES_COMPLIANCE group and label, six reports run, range 400, unknown key 400, cashier 403, other clinic | `PharmacySalesCommandApiIT.theSalesAndComplianceGroupIsInTheCatalogueAndEachReportRunsWithItsColumns` | written, not run |
| 80-21 margin cells empty for a non-pharmacist HOD | `PharmacySalesCommandApiIT.theMarginCellsAreEmptyForAHeadOfDepartmentWhoIsNotAPharmacist` | written, not run |
| inbox rows of sales (chip DISCOUNT, subject SALE_DISCOUNT, action APPROVE) | `PharmacySalesCommandApiIT.aDiscountRequestIsAnInboxRowWithTheApproveAction` | written, not run |
| rule 24 in sale fields: blind seller gets null quantities, own cart stays, owner still sees | `PharmacySalesCommandApiIT.whileAProductIsOnAnOpenAuditABlindSellerSeesNoQuantityAndAnOwnerStillDoes` | written, not run |
| 80-1 twenty reason lists, 70 seeded reasons; `PharmacyReasonsTemplatesIT` line 188 expects 20; `ck_reason_list` takes every list | `PharmacyReasonsTemplatesIT`, `PharmacyWpDPostingDocsIT`, `Sah78CSupport.LIST_KEYS` (changed) | written, not run |
| Schedule X ledger signer on COUNTER_SALE | `PharmacySalesRegistersApiIT.aScheduleXBillNeedsTheSecondSignerAndTheLedgerAndRegisterCarryBothSigners` (API); the DB guard stays in `PharmacyScheduleXLedgerAndAuditSignerDbIT` (not extended) | written, not run |

Not written (endpoint not built, or no screen): day close (80-22, out of go-live), link bill to patient, customer payments and the udhar flow (`pharmacyReceiveCustomerPayment`, udhar sale, `credit-limit-exceeded`), substitution options and the substitution line (80-7), the allergy block (patient port is a no-op), OPD Rx billing, IPD issue and ward returns (no-op ports), pharmacist registrations add and update, the store-path H1 and Schedule X CSV exports (the report export is covered), and the activity types SECOND_SIGNATURE and STOCK_ADJUSTED. This is the next QA backlog for SAH-80.

### 11.2 e2e (`frontend/e2e/pharmacy-sales-wpl.spec.ts`, mocked API, phone-360 and desktop-1440, app built from the current tree)

| Criterion | Test | phone-360 | desktop-1440 |
| --- | --- | --- | --- |
| 80-3 POS: search, cart, save, invoice | a cashier finds a medicine, prices it and saves the bill | FAIL (invoice page 387 px wider than the screen) | pass |
| 80-3 error: sale-changed | the prices changed while the cashier was saving | pass | pass |
| 80-6 discount above the limit blocks save, send for approval | a discount above the limit cannot be saved until it is sent for approval | pass | pass |
| 80-5 cancel with reason and shelf No, refund | the Command Center shows the cards and today bills; a bill is cancelled | pass | pass |
| 80-5 error: cancel not allowed | a cashier cannot cancel; the bill stays saved | pass | pass |
| 80-18 return of one unit and credit note | a return of one unit finds the bill, sends the refund and shows the credit note | pass | pass |
| 80-18 error: window over | the return window is over | pass | pass |
| 80-9, 80-10, 80-19 registers and licences | the H1 register, the Schedule X balance register and the licences show | pass | pass |
| 80-9 error: H1 register 503 | the H1 register cannot load | pass | pass |
| 80-11, 80-13 not connected | both queues answer Not connected | pass | pass |

Result: 19 passed, 1 failed (`playwright test pharmacy-sales-wpl`, 21 Nov 2026). The failing assertion is the page overflow check, not a spec fault: at 360 px the POS invoice makes the document 387 px wider than the screen (owner: angular-developer). The screen shows the cancel-not-allowed sentence "Only the owner can cancel a bill from an earlier day." whatever the server detail says; the spec follows the screen.

### 11.3 Unit and architecture checks (snapshot copy of `backend/`, `mvn -o test`)

6,875 tests, 6 failures. Five were artifacts of the snapshot (the coverage tests read `docs/api/*.yaml` from the repo root; with `docs/api` copied next to the snapshot, `PharmacyStoreAccessCoverageTest` 7/7 and `IpdScopeCoverageTest` 5/5 pass). One is real: `StoreAccessGuardConventionTest.noPharmacyClassUsesRequiresNew` (`CommandCentreService` calls `TransactionTemplate.setPropagationBehavior`). `BeanNameUniquenessTest` 1/1 and `ModularityTests` 6/6 pass.
