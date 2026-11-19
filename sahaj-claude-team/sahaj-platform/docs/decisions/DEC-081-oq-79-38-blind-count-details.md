# DEC-081 · OQ-79-38: details of the blind count and the Schedule X ledger rule

Date: 19 Nov 2026 · Story: SAH-79 (79-5, 79-6, 79-17; DEC-080 L12 and L14) · Authority: DEC-073
Status: DECIDED (auto). Go-live check for the pharmacist (G-15) and the owner.

## Decisions
- **(a) Reorder list and stock adjustment while a count is open.**
  - The "Suggested" column and the "Below reorder" status of a product in an open audit are hidden from a person who can count but cannot review ("Counting in progress"), like every other quantity (the stories' common rule C-9).
  - **A stock adjustment of a product that is in an open audit is refused for everyone**: 409 `count-in-progress`, "This product is being counted. Change it from the count review." This closes the leak through "No change to post" and the freeze is the usual control during a count.
  - Ordering the product (a purchase order) stays allowed; the suggestion is hidden for the counter.
- **(b) A person who both counts and reviews.** The person who counted any line of an audit cannot review (approve) that audit: the same rule as "nobody approves their own request". The blind read follows the capability: `reviewCount` holders see quantities, so a counter who is also a reviewer must be a different person on the other audit. The architect writes the rule into the stock count operations (WP-H); the service enforces it, and QA tests it.
- **(c) Schedule X and the ledger trigger (the list of person-driven transaction types).**
  - It excludes **opening stock** posting (DEC-075 A-8), and every system movement (expiry quarantine, reconciliation).
  - It **includes** the damage report move to Quarantine and the vendor-return hold, because a person moves the units.
  - A missing second signer is refused by the service as 400 validation on `secondSigner` ("Choose the second signer") for the person-driven operations; the database trigger refuses it with 23514 as a backstop. The existing `schedule-x-pharmacist` stays for a caller who is not a pharmacist.
- Errors that name an available quantity (`insufficient-stock` and the 400 above available) leave the quantity out for a counter while the audit is open (the architect's 0.3.2 and the java service).

## Work
Architect (a next touch of the contract and ADR-029 P7, after the 0.3.2 run now in progress), java (WP-H), QA. Nothing blocks the running agents; the QA ITs for L14 check that the opening-stock posting of a Schedule X item passes.

## Blocked
Nothing.
