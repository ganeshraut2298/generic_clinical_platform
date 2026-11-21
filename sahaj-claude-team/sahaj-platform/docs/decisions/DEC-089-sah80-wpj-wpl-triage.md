# DEC-089 · SAH-80 WP-J/L/N/K triage (java run 12)

Date: 21 Nov 2026 · Programme: DEC-061 · Authority: DEC-073 · Status: DECIDED (auto).
1. **pharmacySetProductLink** answers 409 not-connected in phase 1; the architect adds `not-connected` to its documented answers.
2. **Gross margin card and sales-margin grouping** (G-13): not for go-live; card stays absent and the report groups by day and store. Go-live check for the owner/accountant.
3. **H1 report patient names (security):** the report-run path must mask patient names for everyone except the owner and pharmacists and write record-access entries like the register operations. Java fixes in the next run (a High if left, so it must be fixed before go-live).
4. **Discarding a held bill** withdraws its pending discount request (java, next run).
5. Assumed rules accepted: cumulative half-up tax split on partial refunds, a new discount request withdraws the seller's earlier pending one for the same held bill, substitute margin uses batch purchase rate, a return with every line refused ends REJECTED, refund ceiling = paid less earlier refunds. Pharmacist/accountant to confirm at go-live.
6. Command centre cards independent: each card in its own savepoint/transaction (java, next run).
