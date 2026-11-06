# Code review · SAH-26 (IPD bed transfer and transfer rules)

Reviewer: code-reviewer (read-only), 5 Nov 2026. Saved by the tech lead.

**Verdict: CHANGES REQUIRED, one Major (F2). After F2 is fixed and the e2e case is green: APPROVE WITH COMMENTS.**

| # | Sev | Where | Finding | Fix |
|---|---|---|---|---|
| 1 | Major | frontend ipd.model.ts:390-394; admission-dialog.component.ts:175 | `sortFreeBeds` re-sorts by category then code, discarding the server order (category, room creation order, code; no-room last). Duplicated rule. | Delete `sortFreeBeds`; use `options.beds` as sent; comment; update ipd.model.spec.ts and admission-dialog.component.spec.ts:465 (feed server order, expect same order). |
| 2 | Minor | e2e bed-transfer.spec.ts:318-331 | Test mocks the server in the wrong order and expects the client to sort; contradicts the room-order test. | Mock in server order `[GEN_101B, GEN_201A, SEMI_101C, PVT_301A, ICU_401A]`; rename "shows the beds in the order the server sends". |
| 3 | Minor | admission-dialog.component.ts:138-160,190-205 | After a lost answer, `moving` returns to false before the sheet re-read comes back; the user can click Move again and gets 400 "already in this bed"; the red message stays after the re-read. No unit test for it. | Keep Move disabled until the re-read finishes; when the re-read shows another bed code clear the error and toast "<patient> is now in <code>"; add unit test. |
| 4 | Minor | BedTransferService.java:125-130 | Low/high bed-lock juggling is hard to read. | Helper `lockBeds(tenantId, a, b)` returning a map. |
| 5 | Minor | BedTransferService.java:117-139 | Admission read twice; bed ids come from the unlocked read. Safe today (clinic lock). | Assert bed id equality after the locked read or comment. |
| 6 | Minor | RunningBill.java:78, BedTransferService.java:158 | Cut normalisation and billDays written twice. | One `cutOf(...)`/`billDaysNow(...)` in RunningBill. |
| 7 | Minor | RunningBill.java:53-58 | 9-argument compute overload used only by tests. | Delete; tests pass `List.of()`/`Map.of(...)`. |
| 8 | Minor | BedTransferBillIT, BedTransferIT:580 | `assumeTrue(day.equals(istToday()))` skips near IST midnight. | Clock hook / backdating, or retry after midnight. |
| 9 | Nit | TransferRepository.java:32, JdbcTransferRepository.java:26,32 | `toCategoryKey` selected, never read. | Drop from record and selects. |
| 10 | Nit | AdmissionService.java:72,78, AdmissionController.java:37 | Role sets written three times plus a string. | One `FLOOR_ROLES` constant. |
| 11 | Nit | AdmissionService.java:170-184, BedBoardService.java:~125 | `roomOrder` map and comparator duplicated. | Shared `BedOrder.byRoomThenCode(rooms)`. |
| 12 | Nit | admission-dialog.component.ts:178-186 | `loadFreeBeds` has no switchMap; BEDS_ERROR in moveError never cleared. | Cancel previous call; clear error on success. |
| 13 | Nit | AdmissionService.options | Admit dialog (SAH-23) now receives a new bed order. | Make sure SAH-23 tests/spec assert the new order. |

## Passed
Segment maths match AC 4 and the mockup; bill lines and order (AC 5), half-up rounding; lock order and deadlock freedom; check order and messages; one batched transfers query (no N+1); single transaction with audit ids only; migration and grants (update (bed_id) only); contract and 403 owner-approval-needed; module boundaries; ZWJ/ZWNJ decision tested; test quality (concurrency, four-way race, grants from inside the database); frontend OnPush, signals, a11y, no inline styles, texts per mockup.

## Done well
All bill maths in `RunningBill`; segment days stored once at transfer time; service-level role check; AC 8 amendment documented consistently.
