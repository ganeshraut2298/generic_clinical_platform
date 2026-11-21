# DEC-083 · SAH-79 review round 6: triage and queue

Date: 19 Nov 2026 · Programme: DEC-061 · Authority: DEC-073 and the human's approval of all stories (19 Nov, evening) · Status: DECIDED (auto).
Code review: CHANGES REQUIRED (3 Major in the receipt path). Security: PASS WITH FINDINGS (0 Critical, 0 High, 1 Medium). Reports: docs/reviews/SAH-79-code.md and SAH-79-security.md (Round 6). No hard stop.

## Decisions
1. **M4 (a dispatch over the receipt's 400-line cap can never be received):** the dispatch is refused when the picks come to more than 400 dispatch lines (400 `lines`, "Split this dispatch into smaller ones."). The architect adds it to the contract; java builds it; QA tests one dispatch over the cap.
2. **L20 (an approver raising quantities skips a value-based level):** the approved quantity of a line may not exceed the requested quantity unless the decider is at the last configured level or is the owner. Otherwise 400 `indentLines` "This increase needs the next approver." The architect words it; java builds it; QA tests it.
3. **L21:** a missing `membershipId` is field `secondSigner` ("A second signature is needed for Schedule X items."); a bad PIN is `secondSigner.pin`.
4. **I23:** at level 0 a person who is not a pharmacist cannot write off Schedule X units as a transit loss: 403 `schedule-x-pharmacist`. No second signature is needed.
5. **I24:** Schedule X is decided as the dispatch-line copy OR the product, at receipt time. **I26:** one order for an adopted product's schedule: the trigger's (the product's own, then the catalogue's), in the service too. **I25:** accepted as it is.
6. **Code review M-1 and M-2** (the three bad-line cases are 400 `lines` with the contract text; an expired batch rejected with a reason other than `receipt-reject.near-expiry` is 400 `lines[i].rejectReasonKey`), **m-1, m-3, N-1, N-2:** java. **M-3 and m-2** (the expired-batch key in the receipt dialog and its spec; the partial-receipt screen shows the skipped Schedule X lines as waiting): angular.

## Queue
- **angular (lock frees about 23:20):** M-3 and m-2; blocked vendors shown on the vendor-return form with the warning "Vendor licence expired" (OQ-79-39); "To (issuing store)" from `issuesFrom`; `requestVersion` and `levelNo` from the approval summary; then the WP-G and WP-H screens.
- **java (lock frees about 00:15), in this order:** the code review fixes and 1 to 5 above; the interrupted run's unit tests green; m-8 and L19 (the N+1 reads and the inbox cap); WP-G (79-14 to 79-16); WP-H (79-17 to 79-19) with rule 24 (the blind count); then SAH-80 WP-I to WP-N after the db-engineer's migrations.
- **architect:** the M4 and L20 contract text; the L21 field; I23.
- **QA (lock frees about 23:45):** a gate on the whole tree, the new ITs and e2e, then the tests for these decisions.
