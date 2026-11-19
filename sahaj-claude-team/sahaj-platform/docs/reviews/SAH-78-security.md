# SAH-78 · Security review · WP-A (78-1 to 78-4) · 18 Nov 2026

Reviewer: security-reviewer (read-only). Scope: SAH-78 files only (pharmacy package, platform additive edits, migrations V2026_12_17_1000..1220, pharmacy frontend). Compared with HEAD; no Maven run (the tree's tests did not compile because of a SAH-76 file).
No dependency changes (pom.xml, package.json and package-lock.json unchanged).

**Verdict: PASS WITH FINDINGS.** 0 Critical, 0 High, 1 Medium, 6 Low, 1 Info.

## Findings

| # | Severity | File:line | Finding | Fix |
|---|---|---|---|---|
| M1 | Medium | `db/migration/V2026_12_17_1030__pharmacy_catalogue_sample_seed.sql:19`; `application.yml:28` | The sample catalogue is held back only by configuration. The default profile is `dev` when `SPRING_PROFILES_ACTIVE` is unset, and an env var can turn the placeholder on. Once the seed has run, Flyway records it as done, so changing config later does not remove the rows, and nothing raises an alarm. The 30 fake rows include a Schedule X / NDPS "Sample Morphine" that a clinic could adopt. | Add `and current_database() ~ '^sahaj_(dev\|test)$'` to the seed condition (not merged yet). Add an afterMigrate guard that stops start-up if `catalogue_product` has `sample` rows outside dev or test. |
| L1 | Low | `pharmacy/web/StoreController.java:43,57`; `StaffController.java:52,59`; `UnitSettingsController.java:31`; `ContextController.java:39`; `PharmacyProblemHandler.java:45` | Request bodies are parsed before the gate, so a bad body gets 400 before module-off or access-level 403 (contract order, pharmacy.yaml lines 17-23). The body shape leaks; no data does. | Bind the body as `JsonNode` and convert after `gate.require`, or record an accepted deviation in the contract. |
| L2 | Low | `pharmacy/application/StaffMappingService.java:216-217` | When changing a mapping, a missing team member skips the team-role check, so the mapping can be raised to APPROVER or DELEGATE. | 400 `membershipId` "Choose an active team member". |
| L3 | Low | `pharmacy/application/DelegateRules.java:36`; `V2026_12_17_1010…sql:166`; `domain/DelegateState.java:11-19` | The check is `to - from <= 90` with both dates inclusive, which allows **91** calendar days. | Human to confirm the meaning of "90 days or less". If it is 90 calendar days, use `>=` in Java and `<= 89` in the constraint. |
| L4 | Low | `pharmacy/application/StoreAccessGuard.java:28,46-58` | The denial audit row survives only if the caller declares `noRollbackFor`, which is a comment-only rule. No production caller exists yet; WP-B adds the first. | Write the audit in a `REQUIRES_NEW` transaction inside the guard, or add an architecture test. |
| L5 | Low | `V2026_12_17_1220…sql:83-85` | `sahaj_app` can update `pharmacy.document_counter` freely, so a bug could make `next_value` go down and reissue a document number. No Phase 1 code writes it. | BEFORE UPDATE trigger that refuses a decreasing `next_value` and key changes. |
| L6 | Low | `platform/application/NavigationPolicy.java:118-121`; `NavigationPolicyPharmacyTest.java:78-79` | With HRA on, Store staff get the `staff` menu item, which only leads to 403s. | Leave `staff` out of the menu for STORE_STAFF, or have the human confirm the current behaviour. |
| I1 | Info | `platform/application/TeamService.java:121-128` | The owner can add Store staff through the API while Pharmacy is off. The member gets no access but uses a seat. | None; noted. |

## Controls confirmed
1. **Tenant isolation:**
   - All 20 new tenant tables: RLS enabled and FORCED, policies on `platform.current_tenant()`, not-null `tenant_id` default, no delete policy or grant, and composite FKs with `tenant_id`.
   - The 3 approval-rule tables allow select and insert only.
   - Global tables: select-only for `sahaj_app`; delete and truncate are refused by trigger for everyone (PharmacyTablesIT:458-496).
   - No SECURITY DEFINER; `search_path = pg_catalog` everywhere; execute revoked on triggers.
   - `next_document_no()` runs as the caller under RLS.
   - The tenant is never taken from a request body, and `tenantId` in a body is refused.
2. **Authorisation:**
   - Gate order: module, then access level, with the membership re-read every call.
   - SETUP_EDIT is owner only; SETUP_VIEW and PHARMACY_ACCESS follow the contract.
   - The guard gives the same 404 for unknown, foreign, inactive and unmapped stores, and a 403 audit with ids only.
   - Schedule X receive only for team role PHARMACIST, derived on the server.
   - STORE_STAFF can be mapped only as Store user or Central Store user, and reaches only GET /me and PATCH /me/preferences (StoreStaffRoleGatesTest).
   - Working store: mapped stores only.
3. **Input handling:**
   - Closed bodies.
   - `Sent<T>` tells absent from null.
   - Path and query values are parsed after the gate.
   - All SQL uses bind parameters.
4. **Privacy:**
   - No loggers in pharmacy.
   - Audit holds ids and master fields only.
   - No mobile numbers in views.
   - Fixed problem texts.
   - No patient data in WP-A.
5. **Sample seed:** false by default, true only in dev and test; data clearly fake (see M1 for the backstop).
6. **Frontend:**
   - No storage, console, `innerHTML` or bypassSecurityTrust.
   - URLs carry ids only.
   - Route guards are UX only.

---

# Round 2 · WP-A re-check, F-11, WP-B (78-5, 78-6, 78-7) · 18 Nov 2026

Reviewer: security-reviewer (read-only).
- Read: the pharmacy package, migrations 1000-1250, the db/guard callback, and the frontend products and opening-stock folders.
- Checked against: pharmacy.yaml 0.1.2, ADR-028 rev 3/4 and the design note.
- Not run: Maven. Dependencies: pom.xml and package.json are unchanged since 7 Nov.

**Verdict: PASS WITH FINDINGS.** 0 Critical, 0 High, 0 Medium, 2 Low, 6 Info. All 7 round-1 findings closed.

## Round-1 findings
| # | Status | Evidence |
|---|---|---|
| M1 | Closed | `db/guard/afterMigrate__pharmacy_sample_catalogue_absent_guard.sql:26-50` stops start-up if any sample row exists outside `sahaj_dev`/`sahaj_test`. It is read-only, and the placeholder defaults to false. Deployment rule: a production DB must never be named `sahaj_dev` or `sahaj_test`. |
| L1 / F-3 | Closed | All 12 `@RequestBody` are raw `String`; `body.read()` is called after the gate and the path-id parse; strict bodies are kept. Leftover: I6. |
| L2 | Closed | `StaffMappingService.java:233-237`: a missing or inactive member gives 400. |
| L3 | Closed | `DelegateState.java:12-21` (90 inclusive) and migration 1230 (`<= 89`, NOT VALID plus a guarded validate). |
| L4 / F-2 | Closed | `StoreAccessGuard.java:45-50, 75-80`: the audit runs in REQUIRES_NEW. Side effect: L7. |
| L5 | Closed | Migration 1240: the trigger allows only a higher `next_value` and an unchanged key; execute revoked. |
| L6 | Closed | `NavigationPolicy.java:118-123`: no `staff` key for STORE_STAFF. |
| I1 | Noted | No action. |

## F-11 (`GET /staff-candidates` without `storeId`)
No finding.
- The SETUP_EDIT gate runs first, then the `storeId` parse.
- The tenant comes from `ClinicUser`, and team data is under RLS.
- The fields are the same as with a store; no mobile number is returned.
- A foreign `storeId` gives 404.

## New findings
| # | Severity | File:line | Finding and risk | Fix | Owner |
|---|---|---|---|---|---|
| L7 | Low | `StoreAccessGuard.java:48-49, 75-80`; `OpeningStockService.java:255-256, 352-355` | The REQUIRES_NEW denial audit takes a second pool connection while the request holds its first, plus row locks in `addLines`/`submit`. Pool: 10 connections, 10 s timeout. About 10 concurrent denied calls from mapped users can stall the pool for every clinic on the node; the audit then fails, the caller gets 500, and the denial row is lost. | Write the denial audit after the service transaction has released its connection (e.g. in the `PharmacyProblemHandler` exception handler, in its own short transaction, ids from the exception), or give the audit its own small pool. Keep the ArchUnit convention test. | java-developer |
| L8 | Low | `OpeningStockService.java:192-199, 277-290`; `V2026_12_17_1100…sql:417-455` | Unbounded growth: unlimited starts (each leaves a REPLACED draft with up to 5,000 lines), and each resend of 200 rows adds up to about 1,600 issue rows (superseded rows are kept). A script can add millions of rows per tenant. | Skip issue writes when a resent row's 7 raw cells are unchanged; cap line sends per import (e.g. at most 2 × ceil(rowCount/200), then 409); optionally cap new drafts per person per store per day. | java-developer (db-engineer if a counter column is chosen) |
| I2 | Info | `RequestBodySizeLimitFilter.java:51,93` vs `opening-stock-csv.util.ts:16-27` | 200 full-length rows come to about 66.8 KB of JSON, over the 64 KB cap. The batch gets 413 and the upload shows the generic failure. Fails closed. | Split batches by encoded size (60 KB or less), or allow about 128 KB on the lines path. | angular-developer or java-developer |
| I3 | Info | `JdbcOpeningStockRepository.java:113-130, 166-169`; `OpeningStockRules.java:70-76` | Raw cells keep a leading `= + - @`. Safe today (no re-export; Angular escapes). | Record "CSV export escapes formula prefixes" for SAH-79 exports. | solution-architect |
| I4 | Info | `V2026_12_17_1100…sql:232,238`; `OpeningStockService.java:192, 439-441, 449` | The DB self-approval check compares opaque uuids. Safe today (membership and user are 1:1 per tenant, never deleted). | An IT asserting `uploaded_by` and `decided_by` equal the caller's membership id. | qa-engineer |
| I5 | Info | `OpeningStockService.java:513` | The reject note (free text, 300 characters) goes into the audit `after`. | Optional UI hint "Do not add patient details". | angular-developer (BA wording) |
| I6 | Info | `ProductController.java:49,83,91`; `OpeningStockController.java:70,97,121` | `consumes = application/json` gives 415 before the gate; no leak. | Accept, or drop `consumes`. | java-developer / architect |

Not raised: product search SQL (`JdbcProductRepository.java:58-87`) likely seq-scans the ~300,000-row real catalogue (bounded by `statement_timeout` 30 s and SETUP_VIEW). Run EXPLAIN before the real catalogue is loaded.

## Controls confirmed
1. **Tenant isolation:**
   - The 5 new tables have RLS enabled and FORCED, select/insert/update policies on `platform.current_tenant()` and no delete.
   - Composite tenant FKs.
   - Triggers use `search_path = pg_catalog`, execute revoked, no SECURITY DEFINER.
   - The global catalogue is select-only.
   - The tenant always comes from `ClinicUser`.
2. **Authorisation:**
   - SETUP_VIEW / SETUP_EDIT / PHARMACY_ACCESS per operation.
   - `@StoreAccess(OPERATION)` and `guard.require` before any import query.
   - Uploader-only line writes and submit.
3. **Self-approval and races:**
   - Self-approval is refused by the service (403) and by the DB check.
   - Locks are taken store, then import.
   - Version- and status-guarded updates; one-waiting and one-approved unique indexes.
   - Terminal-state triggers; approve and reject on the same import give 409 to the second.
4. **Input:**
   - Bind parameters only.
   - `q` is trimmed, at most 60 characters, at least 2, and LIKE-escaped.
   - CSV limits are enforced on the server (1-5,000 rows, 200 per request, cell lengths, anchored regexes); paise cannot overflow.
   - 64 KB body cap.
   - `clientKey` scoped to the caller.
   - Paging caps.
5. **Logging:**
   - Ids, counts and reason keys only.
   - Ids-only denial audits; fixed problem texts.
   - No patient data.
6. **Frontend:**
   - The CSV is held in memory (at most 5 MB) and cleared.
   - No storage, console, `innerHTML` or bypass.
   - Ids in URLs; Blob template download.

---

# Round 3 · re-check of the DEC-071 fix round · 18 Nov 2026

Reviewer: security-reviewer (read-only). Scope: L7/N-1, L8, I2, I4, N-2, N-3 to N-7 and migration 1260. Package C and IPD are out of scope. Maven was not run; it relies on QA's gate (`validate db`: unit 5,999/0, IT 3,262/0, 25 skipped). Dependencies are unchanged.

**Verdict: PASS WITH FINDINGS.** 0 Critical, 0 High, 0 Medium, 3 Low (L8 in part, L9, L10), 4 Info (I7 to I10). L7, I2, I4 and N-3 to N-7 are closed.

## Round-2 findings
| # | Status | Evidence |
|---|---|---|
| L7 / N-1 | Closed | The guard writes nothing (`StoreAccessGuard.java:43-63`). `PharmacyProblemHandler.java:88-97` calls `StoreDenialAuditor.record` after the service transaction, in one short transaction with the tenant bound from the principal. Nothing but ids is read from the exception. A failed write still answers 403 and logs ids only. The exception cannot be forged (ArchUnit). Every denial path is covered. PharmacyStoreDenialPoolIT is sound (pool 3, 24 parallel, then 18 more). |
| L8 | Partly closed | The cap is read and raised under the import row lock; refused requests write nothing; the counter is frozen after DRAFT; an unchanged resend writes nothing. Each import is bounded. **Starts are still unbounded.** |
| I2, I4, I5 | Closed | The 60 KB / 200-row split; the membership-id test; the note hint. |
| I3, I6 | Recorded, accepted | CSV-export escape recorded for SAH-79; the content-type 415 accepted. |
| N-2 | Closed in part | The checks exist under the right locks for edits one at a time. Two races remain: L9 and L10. |
| N-3 to N-7 | Closed | 2 MB cap and bounded parse; column 8 refused; the file's header; `takeUntilDestroyed`. |

## Migration V2026_12_17_1260
Additive, with a fast default and a guarded check. The replaced trigger function has the same body plus one frozen-counter test, the same `search_path`, no SECURITY DEFINER, and execute revoked again. Grants and RLS are unchanged.

## New findings
| # | Severity | File:line | Finding and risk | Fix | Owner |
|---|---|---|---|---|---|
| L8 (open part) | Low | `OpeningStockService.java:155-209` (192-199) | Starts are unbounded, and REPLACED and NOT_ACCEPTED imports are kept. A mapped user with UPLOAD_OPENING_STOCK can repeat start, 50 line requests, start, adding about 5,000 lines and up to about 70k issue rows per cycle. It is attributable and costs an authenticated user. | Cap starts per store per day (or per person per store); `start` already holds the store lock, so the count is race-free. Contract wording needed. Or accept as a known limit until SAH-79. | architect, java |
| L9 | Low | `OpeningStockService.java:161,182`; `JdbcStoreRepository.java:25,90-92`; `StoreService.java:243-259` | **Switch-off against the start of an import.** `start` checks the store is active without a lock, then `findForUpdate` returns the now-INACTIVE row and a DRAFT is inserted for a switched-off store. That is the orphan DEC-071 meant to stop, and DEC-076 cannot reject it because the guard answers 404. Integrity only. | After the lock: `findForUpdate(...).filter(Store::active).orElseThrow(notFound)` in `start`, and as a defence in `submit` and `approve`. Optional DB backstop: an insert trigger refusing a non-ACTIVE store. An IT with an interleaved switch-off and start. | java (db for the trigger) |
| L10 | Low | `OpeningStockService.java:283-284`; `JdbcProductRepository.java:131-144`; `ProductService.java:199-213,258-273` | **A product edit against a line write.** Neither side locks the product row, so both can succeed and leave a DRAFT or WAITING line on an inactive product, or one with changed units. Approve then marks `has_stock` on it. Integrity only. | Product side: `select … for update` before `onOpenImport`. Line side: `activeByCodes … for share of p`. Backstop in `submit`: flag `product-unknown` for a line whose product is no longer ACTIVE. | java |
| I7 | Info | `OpeningStockService.java:262,330-333`; `opening-stock-csv.util.ts:173-193` | The 60 KB split and the cap of 2 × ceil(rowCount/200) requests work against each other. A 5,000-row file with long cells needs about 28 to 58 requests against a cap of 50, and a retry fails with 409 around request 51. Fails closed. | Cap the rows written (at most 2 × rowCount), or resume from `receivedRows`. | architect, java |
| I8 | Info | `V2026_12_17_1260…sql:44-47` | In DRAFT, `sahaj_app` may lower `line_request_count`. The cap is enforced only by the service. | In the DRAFT branch of the trigger, refuse a decrease. | db-engineer |
| I9 | Info | `PharmacyProblemHandler.java:36`; `StoreDenialAuditor.java:31,39`; `StoreAccessGuardConventionTest.java` | The audit's completeness rests on conventions: every pharmacy `@RestController` must implement `PharmacyApi`; and `TransactionTemplate` joins an outer transaction if one ever wraps the request. | ArchUnit rules; `record` fails loudly if a transaction is already active. | java |
| I10 | Info | `OpeningStockService.java:194-199`; `JdbcOpeningStockRepository.java:80-85,136-141` | The same person racing themselves: `start` while their own `addLines` runs leaves two DRAFTs, because `markReplaced` finds a changed version and is ignored. | Lock the person's drafts before `markReplaced`, or retry on false. | java |

## OQ-78-42 / DEC-076 (queued, not built): design comments
- The database refuses DRAFT to REJECTED in two places: the transition trigger (1260:44-47) and `ck_opening_stock_import_submitted` (1100:239-240). A forward migration is needed (1270-1299), keeping `ck_..._decision`.
- `markRejected` must allow DRAFT and keep the version and status guard. Keep the lock order store, then import.
- **L9 must be fixed first**, because DEC-076 does not clear a draft on a switched-off store (the guard answers 404).
- No new risk from an owner with APPROVE rejecting a staff draft; the audit stays ids, reason key and note.

## Controls confirmed
1. Tenant isolation: the audit binds the tenant from the principal; RLS is unchanged on the 3 import tables; the cap and row reads are tenant-scoped with bind parameters.
2. Authorisation: the gate, then the path ids, then the body, then the guard. Uploader-only writes give an audited 403; a 404 for unknown, foreign, inactive or unmapped stores writes no audit.
3. The denial audit: one connection and one row per denial, ids only, in every transaction shape.
4. Locks: the cap is checked under `FOR UPDATE`; the counter is frozen after DRAFT; unchanged resends write nothing; store lock before import lock.
5. Logging: ids, counts and reason keys only.
6. Frontend: the CSV is held in memory only, with 2 MB and 5,002-row caps; fixed messages; no storage, console, `innerHTML` or bypass.
7. Migration 1260: additive; `search_path = pg_catalog`; execute revoked; grants and RLS unchanged.

---

# Round 4 · hardening round, package C, SAH-79 migrations 1270 to 1710 · 19 Nov 2026

Reviewer: security-reviewer (read-only). Maven not run. Dependencies are unchanged (no change to pom.xml, package.json or package-lock.json).
Read: the migrations 1270 to 1290, 1300 to 1390, 1400 to 1430, 1500 to 1520, 1600 to 1620, 1700 and the new 1710; the hardening code; package C; the print preview. Known items were not re-raised (D-2, D-3, the Flyway order risk, N-10, N-12, F-18, OQ-78-8, DEC-002, DEC-070 Q4/Q5, the 2^53 limit, and the SAH-79 and SAH-80 services and screens that are not built).

**Verdict: PASS WITH FINDINGS.** 0 Critical, 0 High, 0 Medium, 4 Low (L11 to L14), 6 Info (I11 to I16). Both lost guards are fixed by V2026_12_17_1710, checked by reading it only; it still needs a QA run on a freshly migrated database.

## Round-3 findings
| # | Status | Evidence |
|---|---|---|
| L8 (starts) | Closed | At most 10 starts per person per store per IST day, counted under the store lock. |
| L9 | Closed | `lockActiveStore` in `start`, `submit` and `approve`; the insert trigger in 1270 takes the store row FOR SHARE. |
| L10 | Closed | Product edits lock the unit, then the product; line writes lock the unit shared, then the import, then the product FOR SHARE; `submit` flags inactive products. |
| I7 / L8(b) | Closed | The cap is 2 × rowCount changed rows written in `line_rows_written`. |
| I8 | Closed by 1710 | 1350's body let counters go down; 1710 restores "never down in DRAFT, frozen once waiting". |
| I9 | Closed (small gap: I14) | The ArchUnit rules cover the controllers, REQUIRES_NEW, the catch, and who records the denial. `record` throws if a transaction is open. |
| I10 | Closed | `start` locks the caller's own drafts before replacing them. |
| DEC-076 | Closed by 1710 | `reject` accepts DRAFT or WAITING_APPROVAL; 1710 allows DRAFT to REJECTED in the database again. |

## Functions replaced by more than one migration
Only two are defined by more than one migration:
1. `guard_opening_stock_import_update()` (1100, 1260, 1270, 1350, 1710): this is D-1. 1710 is the union of all four earlier bodies.
2. `next_document_no(text)` (1220, 1280, 1380, 1710): a new find with the same cause. 1380 was built on 1220's body and dropped 1280's `last_issued_at` write. On a fresh database the series lock still worked only because `coalesce(last_issued_at, updated_at)` fell back to `updated_at`. A database migrated to 1290 before 1380 would keep a stale `last_issued_at` and wrongly unlock the series. 1710 restores it.
Replaced constraints, triggers and policies were checked too: nothing narrowed the widened checks again, and no policy or deny trigger is dropped by a later file.

## New findings
| # | Severity | File:line | Finding and risk | Fix | Owner |
|---|---|---|---|---|---|
| L11 | Low | `1710:146`; `NumberingService.java:76,102`; `JdbcDocumentSeriesRepository.java:32-36` | **A series edit can race a number being issued.** The save locks the unit row FOR UPDATE and then checks "issued this year". `next_document_no()` reads the unit and the series without a lock. If a number is issued in an uncommitted transaction, the save cannot see it and commits a new prefix or format, so the year has numbers under two formats. This matters once SAH-79 issues numbers. | In `next_document_no` read the unit `FOR SHARE`, so a save waits for an issue in flight and the reverse. In 1710 if unmerged, otherwise 1720. SAH-79 posting reads the series after `next_document_no` in the same transaction. An IT: an issue held open, a save gets 409 `series-locked`. | db-engineer; java (WP-D) |
| L12 | Low | `pharmacy.yaml:2089-2106` (`readStock`), `4111-4129`; `1700:132-137, 230-231` | **The blind count can be bypassed.** The count sheet hides the book quantity, but the same mapped users can call `GET /stores/{id}/stock/batches` and `.../stock/products/{id}`, which return per-batch AVAILABLE quantities while the audit is open. The book side also sits on the same row as the count, hidden only by a service rule. BR-30 is not true end to end. An anti-fraud control gap; no data leaves the tenant. | Decide before WP-H. While a store has an open audit, the stock reads hide quantities from callers who have `count` but not `reviewCount` (a contract change), or the human accepts the limit and the screen text changes. In both cases QA asserts no quantity reaches a counter through any endpoint. | architect; human |
| L13 | Low | `1370:46-47,61`; `1600:44,113`; `1360:65` against `JdbcDocumentSeriesRepository.java:41,47`, `OpeningStockService.java:537-538`, `VendorService.java:138` | **One column name, two kinds of uuid.** In the SAH-79 tables `created_by` must be a membership id; `ck_stock_adjustment_signer_differs`, `ck_store_return_signer_differs`, `ck_damage_report_signer_differs` and `ck_approval_request_raiser` depend on it. The SAH-78 code writes the user id. If a WP-D to WP-H developer follows the SAH-78 habit, a membership id is compared with a user id, those checks always pass, and the Schedule X and self-approval backstops do nothing. | SAH-79 writers take the actor from `caller.membershipId()` through one helper, never `userId`. QA: per table, an IT where the same person as signer, or as raiser and decider, is refused by the database (a direct insert as `sahaj_app`), and `created_by` equals the caller's membership id. The design note says so for every SAH-79 table. | java (WP-D to H); qa |
| L14 | Low | `1330:68-70`; `1410:195`; `1370:61` | **Schedule X dual signature: the database checks only part of it.** It checks "signer is not the actor" when a signer is present, but does not require a signer on a Schedule X movement. The pharmacist rule is in the service only. A skipped service check could post narcotic stock with one signature. | A ledger BEFORE INSERT trigger: when `actor_kind = 'USER'`, `txn_type` is in the person-driven list (ADR-029 P7) and the product's schedule is X (looked up by `batch_id` or `product_id`), require `second_signer_membership_id is not null`. `search_path = pg_catalog`, execute revoked. The architect lists the transaction types. | db-engineer; architect |
| I11 | Info | `1360:87-112`; `1410:113-118`; `1320:52-65`; `1210` | **Things a buggy `sahaj_app` caller could still do** (the permissive-guard design accepted earlier): set an approval request to APPROVED with no decision rows; post a document whose request was never approved, or take a GRN APPROVED to DRAFT to POSTED and skip the rate-rise approval; change `stock_balance.qty` without a ledger line; point `approval_rule_set` back at an older rule version. | A reconciliation IT now and an operations query later: ledger sums equal balances; every POSTED document with a request has it APPROVED; every APPROVED request has an APPROVED decision per applying level, by someone other than the raiser. Optional guards: request to APPROVED needs its decision rows; the rule-set pointer only moves forward. | qa; db |
| I12 | Info | `1210:23,98`; `1360:144` | The database caps rule amounts at 99,999,999,900 paise, ten times the contract limit. The service enforces the contract. | Optional: tighten the checks (NOT VALID, then validate). | db |
| I13 | Info | `documents.util.ts:193-209`; `print-templates.panel.ts:395-405` | **Print preview: no injection found.** Every header, footer, title and field value is HTML-escaped. The page is a `blob:` URL with the app's origin, so a future missed escape would run as the app. `paperSize` goes into the CSS unescaped (typed A4 or A5 today). | Defence in depth: add `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">` to the page, and map `paperSize` through a whitelist. | angular |
| I14 | Info | `StoreAccessGuardConventionTest.java:125-144` | The "no catch" rule matches the exact type only. A broad catch (ForbiddenException, RuntimeException, Exception) around a guarded call would swallow the denial and its audit without failing the rule. | Forbid catching those supertypes in `com.sahaj.pharmacy.application..`. | java |
| I15 | Info | process | D-1 and the `next_document_no` loss are one pattern: a later migration copies an older function body. The comments in 1710 help, but nothing enforces them. | An IT that, after a full migrate, checks the key behaviour of each function more than one migration defines; or a `validate db` rule that flags a second `create or replace` of the same function unless the file says what it merges. | qa; human (scripts/) |
| I16 | Info | `NumberingService.java:83-89` | `prefix-in-use` checks only the prefixes in use now. A prefix one type gave up can be taken by another type, which then issues numbers that look like old documents. Tax invoices (BILL) are not affected when they keep the financial-year part. | Optional: refuse a prefix any other type has ever used, or record it as accepted. | architect |

## Controls confirmed
1. **Row level security and grants:**
   - All 34 new tables in 1300 to 1700 have RLS enabled and forced, with select and insert policies on `platform.current_tenant()`. Update policies (USING and WITH CHECK) exist only on the 20 tables that need them. There is no delete policy anywhere.
   - Each ledger partition has forced RLS, its own policies, no grant and its own TRUNCATE deny trigger.
   - Each table has `revoke all ... from sahaj_app` and from public (34 of 34); afterwards only select and insert, or select, insert and update. No DELETE, TRUNCATE or ALL.
2. **Append-only tables:**
   - With the `deny_append_only_mutation` trigger and insert-only grants: `stock_ledger` (parent and every partition), `document_event`, `approval_decision`, `vendor_payable_entry`.
   - Insert-only by grant: `client_request`, `approval_request_level`, `transfer_line`, `dispatch_line`, `dispatch_receipt_line`, `return_item_note`, `debit_note_credit`, `consumption`, `consumption_line`, `store_return`.
3. **Trigger functions:** all have `search_path = pg_catalog`, none is SECURITY DEFINER, and execute is revoked from public and `sahaj_app`. `next_document_no` is executable by `sahaj_app` only and runs under RLS; `create_ledger_partitions` is executable by the migrator only.
4. **Self-approval:** `ck_approval_decision_not_self`; a trigger keeps `raised_by` equal to the request's; one approval per person per request; one decision per level; composite foreign keys pin the tenant.
5. **Tenant pinning:** composite `(tenant_id, ...)` foreign keys on every reference. Ids from other modules (`admission_id`, `ipd_ward_id`) have no foreign key and are checked through ports.
6. **Authorisation and locks:** package C writes need SETUP_EDIT (owner only); rule versions are insert-only and saves are serialised by the unit lock. The lock order is unit, store, import, products. `reject` takes only the import lock.
7. **Privacy:** pharmacy logs carry ids, counts and reason keys only. Vendor GSTIN, licence and contact details appear only in audit rows, where the phone is masked by `AuditSanitizer`. Consumption stores an admission id, never a patient name.
8. **Input and frontend:** anchored regexes and length caps on vendor fields; reason labels and template text are capped and escaped; no `innerHTML`, `bypassSecurityTrust*`, storage or console in pharmacy code.
