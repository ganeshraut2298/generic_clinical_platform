# DEC-091 · Round 7 code review triage

Date: 21 Nov 2026 · Programme: DEC-061 · Authority: DEC-073 · Status: DECIDED (auto). Report: docs/reviews/SAH-80-code.md. Billing rule: go-live check for the owner/accountant.
1. **Major 1:** fix as proposed (java); QA adds the reverse-direction IT.
2. **Major 2 (discount approval reuse):** single use plus an age limit of the IST day on which it was approved; a unique partial index on bill.discount_request_id as the backstop (db-engineer, new forward migration, additive); the architect amends ADR-030 S14 and the contract (a used or old approval answers discount-approval-void). Owner exempt (DEC-090) unaffected.
3. **Minor 3 and 4 (rule 24):** for a blind caller on an audited product the FEFO pick answers the batches with no quantities and only the first batch; the row status and low-stock pill are nulled/excluded. Java.
4. **Minor 5, 6, 7, 8 and Nits:** java. For 6, the claim is released before StockShort is thrown (a retry with the same key re-runs and gets the same 409).
5. The unreviewed areas go to a second code review pass after the gate.
