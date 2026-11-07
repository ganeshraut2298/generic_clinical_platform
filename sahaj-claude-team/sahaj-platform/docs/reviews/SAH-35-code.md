# Code review · SAH-35 (Medicine round on the Safety board)

Reviewer: code-reviewer (read-only), 7 Nov 2026. Saved by the tech lead. Nothing was run by the reviewer (QA ran the suites: backend unit 3,222, frontend unit 1,991, ITs by QA). The three stories SAH-35, 36 and 38 share one branch. Security review: docs/reviews/SAH-35-security.md (PASS).

**Verdict: APPROVE WITH COMMENTS.** 0 Blocker, 0 Major, 2 Minor, 3 Nit.

| # | Sev | Where | Finding | Fix | Tech lead decision |
|---|---|---|---|---|---|
| R35-1 | Minor | MedicineRoundReader.java:47-50 | One order whose drug key is missing from the cached catalogue, or whose frequency key is unknown, throws `IllegalStateException`: a 500 for the whole answer. `read` is also used by `summary()`, `SafetyService.banner` and therefore `GET /admissions`, so one bad order blanks the Safety board, the poll and the Admitted list for every clinical user of the clinic. Unlikely (FK and check constraints) but possible in a rolling deploy with an old cache. The same fail-loud rule in MedicineAssembler and MarAssembler affects one patient only. | Keep the 500 for the single-patient MAR. For the round, skip the one order, log only tenantId and order id at WARN, carry on; never guess the high-alert class; optional generic "Medicine" row; one unit test. | FIX (java). |
| R35-2 | Minor | medicine-round.component.html:46-59 | While one MAR read runs every Give button, including the tapped one, gets `[disabled]`; a disabled focused button can lose focus and a keyboard or screen-reader user hears nothing until the sheet opens. | `disabledInteractive` or `aria-disabled` plus the existing guard in `give()`; keep focus on the tapped button. | FIX (angular). |
| R35-3 | Nit | SafetyRows.java ~129-131, ~159-161 | Old overloads `counts(Collection, Instant)` and `changeToken(Collection)` have no main caller (tests only). | Delete and point tests at the 3-argument forms. | FIX (java). |
| R35-4 | Nit | SafetyService.java:69-72 | Constructor assigns `round` before the others. | Reorder. | FIX (java). |
| R35-5 | Nit | medicine-round.component.html:20 | List `aria-label="Medicine round"` equals the section heading, so it is read twice. | Name it "Doses due or late" or drop it. | FIX (angular). |

## Differences from the mockup (all in ADR-012, DEC-022 or the contract; none new)
A DOCTOR sees the tab and rows without Give (mockup: nurse and owner only; OQ-35-1); the Emergency access tile is absent (SAH-34 blocked) and the tile order differs; a 200-row cap with a tie-break and the line "Showing the 200 most overdue doses."; "N min late" can never appear because a slot is Late only after more than 60 minutes (the pill reads "1 h late"); the Give button is hidden, not disabled, for roles that cannot give; the Read-only line is NEW; Give makes one MAR read first so the sheet opens after a short wait and not at all if the dose is already recorded, stopped or the patient discharged; no "Scan wristband" button (typed box, by design); Give is `mat-flat-button`; the story's panel error text is not built (the board's own loading and error states apply, ADR-012 D8).

## Checked and correct
`MedicineRound` is pure Java over `DoseSlots`; sort Late first then slot, bed, drug, order id; cap 200 after sorting; counts and token cover all slots (invariant test `MedicineRoundInvariantTest`); token is a `dose:` line per slot with the shared `Fingerprint`, no name, drug or text; two set queries per answer whatever the number of patients, tenant bound, fixed SQL, ids as a `uuid[]` bind; names from one `PatientLookupPort.findAll` call, a missing patient shows "Patient"; board and summary in a REPEATABLE READ read-only transaction against one `asOf`; role gating (token AND re-read membership, IPD Trial/On); no record-access row for the list (DEC-015, ADR-012 D5); `toString` hides names; no new logging; module boundaries; frontend OnPush/signals, one MAR read per tap, 44 px targets, focus returns to the same row's Give or the panel heading, tile and tab number from server counts; no skipped tests.

## Done well
One pure function and one token for board, summary, banner and list; the invariant test; the MAR re-check before the sheet opens; the e2e plan covers the edges.

## Process notes for the human
No `stories`/`contract`/`merge` line for SAH-35, 36, 38 in docs/approvals.md; ADR-012, 013, 014 are PROPOSED; three stories share one branch; `.gitignore` ignores `frontend/e2e` (use `git add -f`); `.env.example` (human) lacks `SAHAJ_HTTP_COMPRESSION` and `SAHAJ_DB_POOL_SIZE`; open clinical go-live items OQ-35-2 (a late dose leaves the list silently at midnight), OQ-38-5, OQ-38-6.
