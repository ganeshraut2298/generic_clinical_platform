# DEC-077 · SAH-78 hardening after security round 3

Date: 18 Nov 2026 · Story: SAH-78 · Authority: DEC-073 · Status: DECIDED (auto). No Critical, High or Medium finding, so no hard stop.

## Decisions
1. **L9 and L10 (two races, integrity only): fix.**
   - `start`, `submit` and `approve` re-check that the store is ACTIVE after taking the store lock.
   - Product edits lock the product row before the open-import check. The line write reads the product `FOR SHARE`, and `submit` flags a line whose product is no longer ACTIVE.
   - A database backstop refuses an import for a store that is not ACTIVE (insert trigger).
   - This also settles code review R3-3: ADR-028 R5-2 and ADR-029 P5 item 5 are then amended to match the code.
2. **I7 and L8(b): the cap counts rows written, not requests.**
   - A new counter `line_rows_written` (additive column, default 0, in 1270-1299) replaces the request count. The limit is at most 2 × rowCount changed rows written per import. A request whose rows are all unchanged writes nothing and is not counted.
   - This removes the clash with the 60 KB batches, and a retry after an error resumes without a 409.
   - The old column `line_request_count` stays in place and is no longer used. It is not renamed or dropped (no destructive change).
   - I8: in the DRAFT branch of the guard trigger, a counter may never go down. The same rule applies to the new counter.
3. **L8, starts: cap new imports per person per store per IST day at 10.** The 11th answers 409 with the sentence "Too many files today. Try again tomorrow." The count is taken under the store lock in `start`. The architect adds the wording and the problem code.
4. **I10:** `start` locks the person's own drafts before replacing them.
5. **I9 and code review R3-1 and R3-2:**
   - ArchUnit rules: a `@RestController` in `com.sahaj.pharmacy.web` implements `PharmacyApi`; no `@Transactional(propagation = REQUIRES_NEW)`; no pharmacy class catches `StoreAccessDeniedException`.
   - `StoreDenialAuditor.record` fails loudly if a transaction is already active.
6. **DEC-076 (an owner may reject a DRAFT):** the migration needs a forward fix in 1270 to 1299.
   - The transition trigger allows DRAFT to REJECTED.
   - `ck_opening_stock_import_submitted` is changed only so that a rejected draft needs no `submitted_at`; the db-engineer chooses the clean form and keeps `ck_..._decision`.

## Order
db-engineer (one migration for 2, 6 and the L9 trigger; after the SAH-79 WP-D agent's lock), then the architect (wording: R5-2, P5, the cap by rows, the starts limit, reject from DRAFT), then java, then angular, then QA, then a short re-review.

## Blocked
Nothing.
