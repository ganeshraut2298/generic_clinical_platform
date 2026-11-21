# Code review · round 7, second pass (21 Nov 2026)

Verdict: CHANGES REQUIRED (6 Major, 12 Minor, 3 Nit; no Blocker). Read-only; nothing run. Covers transfers, store return and damage, consumption, IPD dispensing, bill cancel, registers, command centre, reports, migrations 1600/1610/1620/2220/2300/2310, frontend bill-detail and command-centre.

## Major
- M1 TransferService.java:221-236: lines re-checked after the sending-store lock with a throw-away error list; a line can be silently dropped and the transfer saved short or empty. Fix: real error list, throw; remove unused `checked`; unit test.
- M2 ConsumptionService.java:109,130: admittedPatients is readOnly but writes an audit row (fails on PostgreSQL in Integrated mode). Fix: plain @Transactional; test with a real reader.
- M3 BillCancelService.java:329-345, 422-453: UDHAR refund capped silently with Math.min; money recorded as returned never reaches the account. Fix: refuse when udhar > customer due (as SalesReturnService does); test.
- M4 BillCancelService.java:190-222; V1800:211-216: Schedule X cancel verifies the second signer but never stores it; COUNTER_SALE_CANCELLED not in the L14 list (header says 23 types, list has five of six new). Fix: restore it to the list (db-engineer), pass the signer to ledger lines and register reversals; test.
- M5 DamageReportService.java:242-249,257-267: held quantity not net of other WAITING_APPROVAL reports on the same batch/state; second approval fails at posting. Fix: subtract waiting quantity under the store lock; repository method; test.
- M6 JdbcReportRepository.java:237-258 (SALES_MARGIN): product filter missing on the refund subquery; cost of returned units not subtracted. Fix: filter rr by product; subtract returned cost or state the definition; IT.

## Minor
m1 DispatchReceiptService:319-321 rejection note names Central Store for a transfer. m2 TransferDispatchService:153 receiving store not checked active. m3 Schedule X status/counts reveal hidden book vs count to blind callers (contract:2228 leaves counts visible; confirm). m4 Command centre "Balanced" ignores products never counted. m5 card isolation catches only some exceptions; sweepStore is not isolated and runs on every refresh. m6 ward-consumption label lacks CHARGED. m7 report reason text derived from keys, not list labels. m8 day_close checks reject negative expected cash (day close out of release). m9 consumption charge columns dead surface; do not claim 80-15 charging done. m10 DamageReportService:188-191 batch not checked against this store. m11 bill-detail refund cannot split across modes. m12 Today's sales net vs average on different bases.

## Nit
IpdDispensingService issues() skips refuseIfPortLive and discards date(); about 25 `$any($event.target).value`; no integration tests for transfer receipt, admittedPatients in Integrated mode, Schedule X cancel at ledger level.

## Not covered
Repositories other than JdbcTransferRepository/JdbcReportRepository (store return, damage, consumption, register read, command centre, sale register, SaleBillRepository); ReportDef, ReportService prepare/present/sweep, ReportCsv; migrations 1850 (beyond constraints), 1900, 1910, 1920, 2000, 2010, 2100, 2110, 2200, 2210; contract/ADR line-by-line; frontend pos, sales-return, registers, compliance-cards, specs, mockup labels; StockPosting, ApprovalEngine, BlindCount, ScheduleXSignature internals; transfer screens.
