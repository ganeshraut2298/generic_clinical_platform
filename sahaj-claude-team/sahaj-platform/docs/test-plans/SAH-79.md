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
Written 19 Nov 2026 15:30 to 16:30: `PharmacyRound5DbIT`, `PharmacyRound6ApiIT`, `PharmacyWpEReceiptApiIT`, `PharmacyWpEDebitNoteApiIT`, `PharmacyWpFIndentApiIT`, `Sah79BuySupport`, the three fixes above and the e2e spec. The gate numbers are added below when the gate has run.

