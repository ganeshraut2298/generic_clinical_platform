# Code review · SAH-79 WP-G/H and SAH-80 · round 7 (21 Nov 2026)

Verdict: CHANGES REQUIRED (2 Major, 6 Minor, 3 Nit; no Blocker). Reviewer: code-reviewer (read-only). Integration tests had NOT run (database blocked); SQL judged by reading.

Reviewed in depth: SaleMath, SalePricer, SaleBillService, DiscountApprovals, SalesReturnService (+repo, migration 2000), StockAuditService (to line 963 of 1159) and its repo/trigger (1700), BlindCount, StockReadService, StockPosting, migrations 1800, 1810, 1840, numbering and store series services, frontend audit dialogs.
NOT reviewed (treat as unreviewed): transfers, store return and damage services, ConsumptionService, IPD dispensing, BillCancelService, RegisterService, CommandCentre, ReportService and their SQL, migrations 1850, 1900-2300, frontend sale pages, IT SQL.

Found correct: SaleMath identities and half-down round off within -50..49; StockPosting only ledger writer; lock order consistent; idempotency; Schedule X second signer; audit triggers vs repository order; count sheet has no system quantity; module boundaries; 81 components OnPush; no storage use; no skipped tests; no logging of names or notes.

| # | Sev | Where | Finding | Fix |
| --- | --- | --- | --- | --- |
| 1 | Major | NumberingService.java:84-91 | Prefix-in-use checked one way only: owner can set a unit prefix a store already owns; second bill collides on uq_bill_no (500). | Reject prefixes used in store_bill_series too (case-insensitive, documented lock order); map uq_bill_no violation to 409; IT for the reverse direction. |
| 2 | Major | DiscountApprovals.java:46-63; SaleBillService.java:232-234; V1900 | An APPROVED discount request is never consumed or expired; one approval can discount every later bill with the same lines. | Single use (no bill already carries the id; unique partial index on bill.discount_request_id) and age limit (IST day). Architect and human to confirm (ADR-030 S14 gap). |
| 3 | Minor | StockReadService fefoPick, SalePricer:264-268 | Blind caller can probe a batch quantity via the number of batches picked (rule 24). | For a blind caller on an audited product answer all FEFO batches without pick quantities, or only the first. |
| 4 | Minor | StockReadService byState status, overview lowStock | Row status and low-stock pill computed from hidden quantities leak by comparison. | Null the status and exclude audited products from the pill for a blind caller. |
| 5 | Minor | SalesReturnService.java:481-483 | A line with no decision (Sealed) stored as ACCEPT_QUARANTINE while destination is AVAILABLE. | Store decision null when none sent. |
| 6 | Minor | StockAuditService.java:643 | noRollbackFor commits the client-key claim; a retry with the same key replays 200 as if the post worked. | Release the key before StockShort or replay the stored 409; UI takes a new key on that error. |
| 7 | Minor | SalesReturnService.java:754-768, 349-355 | Cumulative refund shares include other open returns; a rejected earlier return can leave a paisa difference. | Recompute shares at finish from completed quantities. |
| 8 | Minor | SalesReturnService.java:232-234, 637, 682, 1047-1051 | N+1 queries. | Batch-load with findAll. |
| 9 | Nit | SaleBillService.java:525 | Redundant condition (reduces to customer == null). | Simplify. |
| 10 | Nit | SalesReturnService.java:12-14 | Stray blank line in imports. | Format. |
| 11 | Nit | SalesReturnService.java:631-633 | putIfAbsent assumes one ledger line per bill line. | Comment or assert. |

DB-run risks: V1800 drops and re-adds ck_stock_ledger_txn_type on the partitioned parent; V2000 deferred FK sales_return.credit_note_no to credit_note (complete() inserts the credit note first); bill list SQL uses `:status = ''` text inference.
