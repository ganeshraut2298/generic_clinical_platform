# DEC-082 · SAH-79 review round 5: triage and the round 6 queue

Date: 19 Nov 2026 · Programme: DEC-061 (SAH-79 WP-D and WP-E) · Authority: DEC-073 · Status: DECIDED (auto).
Code review: CHANGES REQUIRED (2 Major). Security: PASS WITH FINDINGS (0 Critical, 0 High, 2 Medium). Reports: docs/reviews/SAH-79-code.md and SAH-79-security.md. There is no Critical or High finding, so there is no hard stop.

## Decisions on the security findings
- **M2 (PIN attempts by anyone who can adjust stock):** same as code review M-2. Fix: only a pharmacist on a Schedule X batch can trigger a PIN check; otherwise no PIN call (400 or 403 from `create`).
- **M3 (the vendor view):** the view is shaped **by who reads**. An owner or a pharmacist gets the full vendor record. A caller who has the vendor read **only through a Central Store user mapping** gets `id`, `name`, `status` and the blocked state, on the list and on `GET /vendors/{id}`; GSTIN, licence number and date, contact name and phone and the credit terms are not returned to them. The architect rewords the contract (the SETUP_VIEW line and `pharmacyListVendors`). Java builds it. QA asserts that such a caller's response has none of the hidden fields.
- **L15 (concurrent replays):** when `clients.take` returns false the service re-reads the key: the same operation and subject answers as a replay, anything else is 409 `client-key-reused`. No new unique index (the key stays per member by design). An IT with two parallel creates using one key.
- **L16 (a Schedule X line with no person):** a database check `ck_stock_ledger_system_types`: `actor_kind = 'USER' or txn_type = 'EXPIRY_QUARANTINE'` (the only system type today), NOT VALID then a guarded validate. In migration 1740.
- **L17 (PIN format):** the service refuses a missing `membershipId` or a PIN that is not `^[0-9]{4}$` with 400 `secondSigner.pin` "Enter a 4-digit PIN." before any check; the architect adds the pattern to the `SecondSigner` schema.
- **L18 (who can read an approval request):** the reader needs the document's own read capability at the subject store (the purchase capability for a purchase order, receipt or debit note; the adjustment capability for an adjustment); otherwise 404. A `canRead` on each approval subject handler.
- **L19 (the inbox has no limit):** the pending query is filtered in SQL to the levels and rule stores the caller can decide, levels and decisions are loaded in one query, and the scan is capped at 2,000 with a "more waiting" flag. With the N+1 fix (m-8), in the run after WP-F.
- **I17 (a credit note's amount is independent of the debit note):** accepted for now. The difference is shown on the debit note and in the audit; the owner sees it. A go-live check for the accountant (G-14): approval or a reason above a threshold.
- **I18 (receipt with no spending approval, or over-receipt):** accepted; no cap (vendors over-supply). Go-live check.
- **I19 (HOLD and RELEASE are not on the person-driven list):** confirmed, because the held units stay in the store and only the hand-over (`VENDOR_RETURN`) removes them; the architect notes it in P7.
- **I20:** same as code review m-5 (recheck the signer in `onApproved`).
- **I21 (200 receipt lines are about 66 KB, over the 64 KB cap):** the request-body limit is raised to 128 KB for the goods-receipt and purchase-order write paths, as for the form-save path (a java change in the platform filter's path list, additive). The cap stays 200 lines per receipt; the 128 KB limit covers a full one.
- **I22:** same as code review m-1.

## Round 6 queue (in order of lock availability)
- **java-developer (lock frees about 12:25), in this order:**
  1. M-1 (the lock-free read for the purchase-order summary), M-2 / M2, M3 (the vendor view), L15 / m-3, L17, L18, m-1 / I22, m-2 (one `BatchLockOrder`), m-5 / I20, m-6, m-9, m-4 (the short-supply credit in purchase units), n-3, n-4, I21.
  2. Then SAH-79 WP-F (stories 79-9 to 79-13, 19 operations).
  3. m-8 and L19 (N+1 and the inbox cap) go into the run after.
- **db-engineer (about 12:00):** migration 1740: the short-supply-only debit note path (CREATED or PICKED to CREDIT_NOTE_RECEIVED, and the credit insert guard); APPROVED to DISCARDED and SENT to CLOSED_SHORT directly if the design allows it; `ck_stock_ledger_system_types` (L16); the 1720 comment (m-7: say which paths are serialised and the residual).
- **architect (about 12:10):** contract 0.3.4 (the vendor view by caller, the `SecondSigner` pin pattern, the indent chip "· Schedule X lines waiting for a pharmacist", the receipt with empty lines and a purchase order, 128 KB for the receipt and PO paths), ADR-029 P7 (HOLD and RELEASE not on the list; a note on m-7).
- **angular-developer (about 10:45 and later):** WP-F screens (stories 79-9 to 79-13: indents and dispatch); m-10 ("Return" on a quarantined or near-expiry batch); the credit-days hint "From the vendor".
- **qa-engineer:** the tests of the round 5 report, M-1 and M-2, M3, L15 to L18, I17.

## Blocked
Nothing.
