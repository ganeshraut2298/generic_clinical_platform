# Security review · SAH-79 WP-G/H and SAH-80 · round 7 (21 Nov 2026)

Verdict: PASS WITH FINDINGS (0 Critical, 0 High, 2 Medium, 6 Low, 6 Info). Integration tests had NOT run against a database (RLS, trigger guards, new SQL confirmed by reading only). Dependency files unchanged since 7 Nov.

| # | Sev | Where | Finding | Fix |
| --- | --- | --- | --- | --- |
| M5 | Medium | DiscountApprovals.java:46-64; SalePricer.java:276-284; SaleBillService.java:223-234; V1900:71 | An approved discount request can be reused without limit (same store, cart digest, covering percent; no use mark, expiry or tie to seller/buyer/held bill). | Single use (409 discount-approval-void if a saved bill carries it; unique partial index), bind to seller and held bill, expire at the end of the IST day. QA: second bill reusing the id gets 409. |
| M6 | Medium | BillCancelService.java:189-199, 211-214, 221-222; JdbcSaleRegisterRepository.java:45-57 | A Schedule X cancel verifies a second signer's PIN, then keeps no record; ledger lines carry a null signer; the register reversal copies the original sale's signers. | Pass the canceller and signer into the reversal (as the return path does), set the signer on the ledger lines, write a SECOND_SIGNATURE document event. QA test. |
| L22 | Low | ComplianceService.java:295-297; StockAdjustmentService.java:284, 299, 350 | Blind-count leak through the activity feed (adjusted ADJ-.. 40 to 38). | Apply blind.at in activity; leave quantities out when hidden. |
| L23 | Low | CommandCentreService.java:229-235, 249-265 | Reorder card and Schedule X review count leak one bit per audited product to a blind pharmacist. | Exclude hidden products or null the cards while masked. |
| L24 | Low | RegisterService.java:121-154; ReportService.java:168-197 | Bulk H1 exports write no per-patient record-access entries. | support.recordOpened per distinct patientId exported. |
| L25 | Low | StoreRules.java:63-97 | CASHIER silently gets STORE_USER inventory capabilities (default branch). | Explicit CASHIER case: read stock plus sale columns. |
| L26 | Low (human) | SaleRules.java:72-74 | With no SALE_DISCOUNT rule any seller can discount up to 100% with no approval. | Seed a default or go-live checklist item. |
| L27 | Low | CustomerService.java:221-275 | Customer edits audited incompletely (only creditLimitPaise). | Audit the names of changed fields (not values). |
Info: I27 owner refused on Schedule X cancel (fails closed; align wording with OQ-80-37); I28 BillLinkService uses guard.require not requireSale; I29 two Schedule X helpers; I30 DPDP consent for saving a walk-in; I31 DEC-090 per-store reading confirmed; I32 L19 out of scope.
Controls confirmed: RLS on all 26 new tables (no SECURITY DEFINER, no update/delete grants on registers, payments, credit notes, refunds, account entries, discount requests); authorisation per operation; Schedule X PIN outside and rechecked inside the transaction; bind-parameter SQL; CSV neutralising; masking; record access fails closed; logging holds ids only (access log never records query strings); replay and size limits; no unsafe HTML or storage in the frontend.
