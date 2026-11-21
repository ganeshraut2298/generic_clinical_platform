# DEC-093 · Round 7 code review, second pass: triage

Date: 21 Nov 2026 · Programme: DEC-061 · Authority: DEC-073 · Status: DECIDED (auto). Report: docs/reviews/SAH-80-code-pass2.md. No Blocker.
1. **M1, M2, M3, M5, M6 and Minors m1, m2, m4, m5, m6, m7, m10, m11, m12:** java (and angular for m11, m12). M3 refuses a UDHAR refund above the customer's due. M6: the margin report filters refunds by product and subtracts the cost of units returned to stock (definition stated by the architect).
2. **M4:** COUNTER_SALE_CANCELLED goes back on the Schedule X signer list in migration 1800 (db-engineer; 1800 is not live, so it is edited and the databases recreated). This reverses part of the earlier DEC-078/1800 decision: the owner no longer cancels Schedule X bills (DEC-092 item 6), so a signer is always present. Java stores the signer on the ledger lines and the register reversals.
3. **m3:** the Schedule X MATCH/REVIEW status is hidden for audited products for a blind caller; the architect updates the contract line.
4. **m8, m9:** day close and consumption charging are out of this release; recorded in the go-live notes.
5. **Not covered areas** and the new IT gaps go to QA (transfer receipt, admittedPatients Integrated, Schedule X cancel ledger) and a third reading pass after the gate.

## Addendum (architect contract and ADR 0.3.8, same day)
6. **O-19:** a Schedule X bill from an earlier IST day cannot be cancelled by anyone; the sales return is the route. Accepted. COUNTER_SALE_CANCELLED goes back on the signer list (item 2).
7. **O-20:** the low-stock list and reorder suggestions exclude audited products for a blind caller (java). A quote's SHORT state and a mixed-MRP line leak a little to a blind caller: accepted as a known limit for go-live (security to note in the next round).
8. **Cashier and 'Discount above 10%':** mockup 05 shows Cashier = Deny; the server lets any seller request a discount (OQ-80-8). OPEN FOR THE HUMAN (rule 1): keep OQ-80-8 or follow the mockup (a cashier cannot discount above the limit at all). Interim: the read-out follows the server. GL-2 (DPDP notice wording) also needs the human and a lawyer.
9. **Human answer (21 Nov 2026, "go with your option"):** item 8 is resolved: keep OQ-80-8 (any seller may request a discount approval; the owner is exempt; the read-out follows the server). The mockup 05 cashier "Deny" cell is recorded as a known deviation for the human to fix in the mockup. GL-2 (DPDP notice) stays on the go-live checklist: no screen change until wording is approved.
