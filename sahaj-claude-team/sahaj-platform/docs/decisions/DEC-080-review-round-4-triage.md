# DEC-080 · Review round 4: triage and the round 5 queue

Date: 19 Nov 2026 · Programme: DEC-061 · Authority: DEC-073 · Status: DECIDED (auto). No Critical, High or Medium security finding, so no hard stop.

## Decisions on the security findings
- **L11 (a series edit racing a number being issued):** fix. `next_document_no` reads the unit row `FOR SHARE`, so a series save waits for an issue in flight and the reverse. SAH-79 posting reads the series after `next_document_no` in the same transaction.
- **L12 (the blind count can be read through the stock endpoints):** fix, and keep the mockup rule (BR-30). While a store has an open audit, the stock reads return no quantity for a caller who has `count` but not `reviewCount`, for the products in the open audit. The architect writes the contract change (additive: the quantity fields are null). QA asserts that no quantity reaches a counter through any endpoint. Go-live check for the pharmacist.
- **L13 (membership id against user id in `created_by`):** SAH-79 writers take the actor from `caller.membershipId()` through one helper, never from `userId`. Each SAH-79 table gets an IT where the same person as signer or as raiser and decider is refused by the database. The design note says so for every SAH-79 table.
- **L14 (Schedule X signer not required by the database):** a ledger BEFORE INSERT trigger requires `second_signer_membership_id` when `actor_kind = 'USER'`, the transaction type is on the person-driven list of ADR-029 P7, and the product's schedule is X. The architect lists the types.
- **I11 (a buggy caller could skip an approval):** a reconciliation IT now. Two cheap database guards: a request cannot become APPROVED without its decision rows, and the rule-set pointer only moves forward.
- **I12 (the database check is looser than the contract):** accepted as a backstop; no migration.
- **I13 (print preview):** add a Content-Security-Policy meta tag to the generated page and map `paperSize` through a whitelist.
- **I14:** extend the ArchUnit rule to forbid catching the supertypes of `StoreAccessDeniedException` in `com.sahaj.pharmacy.application`.
- **I15 and code review m-1:** a guardrail test that scans the migrations (a function defined in more than one file must be on an allow-list naming the final file and a snippet the final body must contain) and an IT of the key behaviour of each such function after a full migrate. A `validate db` rule and a migration checklist item are human items (scripts/, .claude/skills).
- **I16 (a given-up prefix can be reused by another type):** accepted as a known limit. Tax invoices keep the financial-year part.

## Round 5 queue (in this order of lock availability)
- **db-engineer, migration V2026_12_17_1720** (after the 1710 run; its lock frees about two hours after the engineer's last write):
  - L11 (`next_document_no` reads the unit FOR SHARE); L14 (the Schedule X trigger); I11 (the two guards);
  - m-3 (OPEN needs `credited_paise < amount_paise or amount_paise = 0`);
  - m-4 (check the indent state table against the contract and tighten to forward moves);
  - m-5 (a trigger on `store` refusing INACTIVE while any balance row has `qty > 0` or units are in transit);
  - m-6 (an index `(tenant_id, store_id, occurred_at)` on the ledger);
  - n-1 (the NOT VALID plus validate pattern).
- **architect:** pharmacy.yaml 0.3.2 (L12, the person-driven transaction types for L14 in ADR-029 P7, the vendor return and indent state table if m-4 needs it).
- **java-developer:** I14; the migration guardrail test; `NumberingService.numbersIssued` by the financial-year check; the auditor Javadoc (n-3); then SAH-79 WP-E and later.
- **angular-developer:** M-2 (the vendor form dead end); I13.
- **QA:** the `next_document_no` IT (twice; `last_issued_at` not null and moving); the migration function guardrail IT; the reconciliation IT; L13 and L14 ITs; D-3, `numbersIssued`, the limit at 9,999,999,900 and 9,999,999,901, vendor create answers version 0.
- **Human items:** the migration checklist rule under `.claude/skills` and a `validate db` rule for repeated functions (scripts/); `frontend/e2e` in `.gitignore`; F-18 (hi and mr strings); OQ-78-8.

## Blocked
Nothing.
