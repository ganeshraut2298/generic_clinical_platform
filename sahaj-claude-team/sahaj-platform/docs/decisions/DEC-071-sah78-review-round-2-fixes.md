# DEC-071 · SAH-78 review round 2: fix plan and the open-import rule

Date: 18 Nov 2026 · Story: SAH-78 (WP-A + WP-B) · Raised by: tech lead
Status: DECIDED (auto, human away, DEC-047). The human confirms Q1 at the merge ask.

## Context
Code review round 2: CHANGES REQUIRED, with 1 Major (N-1) and Minors N-2 to N-14. Security round 2: PASS WITH FINDINGS (L7 = N-1, L8 and I2-I6). There is no Critical or High finding, so there is no hard stop. Both reports are in docs/reviews/SAH-78-*.md (Round 2).

## Q1 · N-2: what may change while an opening-stock import is open?
A store with a DRAFT or WAITING_APPROVAL import can today be switched off, or have its code changed. A product on the lines can be switched off, or have its units and conversion changed. Switching the store off orphans the file: approve and reject then answer 404.

Options:
- **A (recommended, chosen):** refuse these changes while the import is open. Answer 409 `open-import` with the sentence "Finish or reject the opening stock file of this store first."
  - Store switch-off and store code change: refused while the store has a DRAFT or WAITING_APPROVAL import.
  - Product switch-off, or a units or conversion change: refused while the product is on a line of a DRAFT or WAITING_APPROVAL import.
  - Pros: simple, no orphan data, the rule is visible. Cons: a new 409 code, so contract 0.1.3 is additive.
- **B:** recheck at approve and answer 409 if anything drifted. Pros: no change to the store and product screens. Cons: the owner learns of it only at approve and must start again.
- **C:** record it as a known limit until SAH-79. Pros: no work. Cons: a reachable orphan state.

The contract change is additive: one new problem code on existing 409 responses. It is recorded in pharmacy.yaml 0.1.3 and confirmed by the human at the merge ask (DEC-064 sign-off).

## Fix plan (round 2)
- **java-developer:**
  - N-1/L7: write the audit after the transaction, plus a test with parallel denials larger than the pool.
  - N-2 option A.
  - L8: skip unchanged resends; cap line sends per import at 2 × ceil(rowCount/200), then 409 `import-state`, unless the architect names another code.
  - N-8: Unicode whitespace.
  - N-13: placeholder code in the template.
  - I6: accepted, no change.
- **solution-architect:**
  - pharmacy.yaml 0.1.3: `open-import`; the L8 cap; N-9 body-order sentence; N-13 example; N-12 known limit.
  - ADR-028 R3 amended for N-1.
  - I3 (CSV export formula escape) recorded for SAH-79.
- **business-analyst:**
  - N-9 AC sentence; N-2 rule in 78-3/78-5/78-6/78-7.
  - Text check: N-11 "Not accepted. Fix every problem below and upload the file again."; I5 reject-note hint "Do not add patient details."; the card sentence "A product only this unit uses."
- **angular-developer:**
  - QA-78-4 (`@defer (on immediate)` plus `@placeholder`).
  - The 4 BA texts from 15:26.
  - N-3, N-4, N-5, N-6, N-7, N-11; I2 batches by encoded size; I5 hint.
  - Show the `open-import` 409 sentence.
- **qa-engineer** (after the fixes): ITs for N-1, N-2, L8, N-8 (seed parity), I4; QA-78-4 tests green; the gate re-run; e2e. N-14 if the environment allows.
- **Recorded, not fixed now:**
  - N-10: search timing on a realistic catalogue before go-live. db-engineer and QA, before the real catalogue load.
  - N-12: JS number limit on totals.
  - F-18: hi/mr native check (human).

## Blocked
Nothing. Q1 is confirmed at the merge ask.
