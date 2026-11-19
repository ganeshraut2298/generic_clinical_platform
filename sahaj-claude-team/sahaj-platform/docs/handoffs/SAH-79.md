# SAH-79 · Pharmacy & Inventory Phase 2 — Inventory

Programme: DEC-061. Branch: feature/SAH-39-home-kinetic-background (DEC-061 Answer 2). Written ahead; own approvals before build.

## Stage log
| Stage | Agent | Started | State |
|---|---|---|---|
| Requirement + stories (I1–I10, opening stock posting first) | business-analyst (BA 2) | 17 Nov 2026 | running |

## Handoff · SAH-79 · business-analyst (BA 2) → solution-architect · 17 Nov 2026
Status: DONE, written ahead (Phase 2 gets its own approvals). Files: docs/requirements/SAH-79.md (BR 37, OQ-79-1..36, go-live G-13..G-21), docs/stories/SAH-79.md (19 sub-stories, common rules C-1..C-8).
Validation (tech lead): `validate docs` fails only on old ADM-2.md; SAH-79 files pass.
| WP | Sub-stories | Screens |
|---|---|---|
| D | 79-1 post opening stock, 79-2 approval engine + inbox, 79-3 batch/expiry/FEFO/low stock, 79-4 store stock by state, 79-5 manual adjustment (Should) | 06, 14, 11 |
| E | 79-6 PO, 79-7 receive stock + vendor payable, 79-8 vendor return / debit / credit note | 07, 08, 09 |
| F | 79-9..79-13 indent raise, HOD approve, CS dispatch, receive (accepted + rejected = dispatched), CS accepts returns / shortage | 10, 11 |
| G | 79-14 return / damaged, 79-15 transfer, 79-16 ward consumption | 11, 12 |
| H | 79-17 stock audit, 79-18 stock reports, 79-19 purchase reports (Should) | 13, 15 |
Human items (H) for when Phase 2 starts: OQ-79-4 CSV instead of Excel, -5 pharmacy-owned vendor payable, -6 landed cost, -7 expiry as month-year, -8 Schedule X second signer in Phase 2 (colleague PIN), -9 GRN fields, -10 rate-rise %, -11 vendor credit %, -13 GST on notes, -16 replacement rule, -18 transit-loss write-off, -21 cross-store visibility, -23 patient-wise consumption "Charge pending" + `ipd.api.AdmittedPatients` port (IPD change), -28 approval deadlock, -36 value basis.
Touch-ups requested to SAH-78 (78-3 linked ward, 78-4 consume vs return capability, 78-8 vendor credit fields, 78-9 rate-rise %, 78-10 series DN/AUD/RET/DMG/ADJ + reason lists).
Tech lead decision: none of these is used by a Phase 1 screen or API, so they are NOT folded into SAH-78 now (the db-engineer is building the accepted Phase 1 model). They become additive changes (new columns, seed rows, capability table rows, contract minor bump) in the SAH-79 architecture and migrations. This keeps Phase 1 stable and needs no rework.
SAH-78 note for developers: the accepted contract governs; an unmapped store answers 404 (stories 78-4 and 78-7 still say 403 `outside-store`; BA fixes the wording later).

## Handoff · SAH-79 WP-D backend (part 2 of the DEC-061 round) · java-developer → qa-engineer · 19 Nov 2026
Status: DONE (all 16 WP-D operations built; unit tests only). Unit phase `mvn -o test`: 6,218 run, 2 failures, both in new tests of this work; fixed and the 15 touched classes re-run: 160 run, 0 failures. No IT phase and no `validate backend` run (QA's gate). Part 1 (D-2) is in docs/handoffs/SAH-78.md.
- **Operations built against docs/api/pharmacy.yaml 0.3.0 (16):** 79-1 posting-preview, post; 79-2 approvals inbox, get, decision; 79-3 stock overview, batches, product stock, low-stock, fefo-pick; 79-4 all-stores (SCOPED) and my-store states; 79-5 stock adjustments list, create, get; common GET /reasons.
- **Service layer (`com.sahaj.pharmacy.application`):** `StockPosting` (the one writer: insert missing destination rows sorted, lock each store's balance rows in one statement, expiry move to QUARANTINE inside any movement, FEFO in memory, refuse before write, 409 `insufficient-stock` and `batch-expired`), `OpeningStockPostService`, `StockReadService` (read sweep with `FOR UPDATE SKIP LOCKED`), `ApprovalEngine` (plan, submit, who decides, escalation and "needs another approver" on read), `ApprovalService`, `ApprovalSubjectHandler`, `StockAdjustmentService` (also the handler of type STOCK_ADJUSTMENT), `ScheduleXSignature`, `ActiveReasonService`, `AdmittedPatientsReader` and `NoOpAdmittedPatientsReader`. Ports and JDBC: `StockRepository`, `ClientRequestRepository`, `DocumentEventRepository`, `ApprovalRepository`, `StockAdjustmentRepository`, `DocumentNumberPort`.
- **Capabilities:** `StoreAccess` gains `readStock`, `postOpeningStock`, `adjustStock` (additive in GET /context); `@StoreAccess.Kind.SCOPED` added. `BUILT_SUB_STORIES` now lists 79-1 to 79-5.
- **Lock order as built:** unit (shared), client key, store (ascending), request, document, ADJ number, batches (product, batch key, expiry), balances; `OpeningStockPostService` and `StockAdjustmentService` follow it.
