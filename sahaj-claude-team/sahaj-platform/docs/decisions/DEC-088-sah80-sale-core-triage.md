# DEC-088 · SAH-80 sale core: triage of java run 10 questions

Date: 20 Nov 2026 · Programme: DEC-061 · Authority: DEC-073 and the human's approval to build Pharmacy sales · Status: DECIDED (auto).
1. Substitution, allergy and H1 notes: the table is varchar(200); the contract says 300. Decision: the contract is changed to 200 (architect); no migration change.
2. Field errors carry no code (payment-mismatch, credit-limit-exceeded and others): accepted for go-live; messages and field names follow the contract. A code needs a platform.api change: follow-up.
3. Cancelled-bill refund is refused until the owner ticks refund modes (unit.refund_modes empty): accepted; a setup step in the go-live list.
4. Account entry amounts are signed (CREDIT_BILL and CREDIT_NOTE_ISSUED plus, the others minus): accepted; the architect states it in the contract.
5. Bill list label: walk-in name shows as "Walk-in"; customer and patient masked for a cashier, full for owner and pharmacist: accepted.
6. creditLimitPaise answers 0 when no credit: accepted.
7. Prescription parts answer 409 not-connected, patient search 503 patients-unreachable (walk-in fallback), abhaLinked null: accepted for go-live (follow-ups: OPD/IPD/patient ports).
