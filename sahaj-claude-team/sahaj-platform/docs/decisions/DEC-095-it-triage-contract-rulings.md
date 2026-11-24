# DEC-095 · Integration-test triage: contract rulings

Date: 22 Nov 2026 · Programme: DEC-061 · Authority: DEC-073 (tech lead decides open questions, recorded) · Status: DECIDED (auto)

Context: after the connection-pool fix the IT suite went from 65 failures / 978 errors to 39 failures / 7 errors (3706 run). The java-developer raised five contract questions. Rulings below keep the built behaviour where it matches the contract and avoid new contract fields before go-live.

1. **Short-supply credit percent.** Contract says "default the vendor's" credit percent. Product does that (test vendor 80 %). Ruling: product correct; tests change to expect the vendor's percent. (WpEDebitNote:342, WpEReceipt:68, :103; also check :400.)
2. **Sales report dates.** sales-margin and h1-register require `from` and `to`, like the SAH-79-18 reports. Ruling: required, 400 when missing (consistent with the other reports); the architect states it in the contract; tests send dates.
3. **Second signer same as caller.** Contract says 403 `second-signer-same`; product answers `second-signer-invalid`, as the stock-adjustment flow and its test already do; the same person is still refused. Ruling: contract changes to `second-signer-invalid` for this case (no new mapping port before go-live). Tests follow.
4. **Discount approval id.** No new contract field. Tests take the approval request id from the approvals inbox, as the screen does.
5. **Cashier refused with `store-permission-denied`.** The contract lists it. Ruling: accepted; tests accept it.

Product items, not contract questions: blind counter sees system quantity on `GET /stock/products/{id}/stores` (rule 24 leak, java to fix); approve-and-post answers 200 instead of 409 insufficient-stock (java to investigate); `ck_document_event_event` rejects digits so event `H1_OVERRIDE` fails (db-engineer: new additive migration widening the pattern, no data change).

## Addendum (22 Nov)
6. **Short supply when the vendor has no returnCreditPercent.** Built: the receipt-prepared draft credits 100 %. Ruling: keep it (the vendor did not supply the units, so full credit is the safe default); the architect documents the 100 % fallback in the contract at the next contract touch. The eligible-items read may keep answering null.
Findings: the java-developer showed the two "product items" are test-side (the /stores row with 103 belongs to an un-audited ward store; a linked consumption lowers the audit book by design). QA corrects ITs :226 and :396.
7. **Approvals inbox `type=SALES`.** Added to the pharmacy.yaml enum to match what is built and what pharmacy-sales.yaml already documented; recorded as an alignment in the 0.3.9 addendum (the human re-approves 0.3.9).
8. **No reachable store → 403 before any id lookup; reachable store → 404 for an unknown or foreign id.** Contract follows the product; no cross-clinic leak (decided from the caller's own mappings).
9. **IPD_ISSUE_SLIP series listed in every unit mode** (product lists all twelve). Ruling: the contract follows the product; the architect removes "listed only when the unit is Integrated" at the next contract touch. Harmless: an unused series is only a setting.
10. **Inbox `counts.all` leaves out SALES items, and there is no `counts.sales`.** Not changed before go-live (needs a contract field and a mockup decision on a Sales chip count). Known gap, logged for after go-live.
