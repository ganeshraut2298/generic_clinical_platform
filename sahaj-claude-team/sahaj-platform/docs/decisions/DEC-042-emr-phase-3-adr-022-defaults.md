# DEC-042 · EMR phase 3 (ADR-022): human items H1-H15

Date: 12 Nov 2026. Status: ANSWERED 12 Nov 2026 (human, chat). Raised by: tech lead, from the architect's hand-back (ADR-022; opd.yaml 0.4.0, billing.yaml 0.2.0, ipd.yaml 0.2.0).

## Answer (human, chat)
- **All defaults H1-H15 accepted**, including the billing rules: H1 IPD/day-care sittings billed on the IPD bill with the course deposit carried there; H3 course payments count in day closing and revenue reports; H4 new RC-n receipt numbers and receipt print view; H14 any payment on a visit bill means its EMR service lines can no longer be removed or changed (409 already-billed). Also H5 visit link as designed, H6 medicine defaults 1-0-1, 5 days, after food where none seeded and typed medicines allowed, H7 favourites per department not built (one list per clinic), H8 IPD round and ER records create no bill lines, H9 "Save to patient record" stays in opd, H10 form visit types stored only, H11 the advice item's own language wins on print, H12 history details only from signed records, H13 doctor's admission request with SAH-67-3, H15 form names unique per clinic.
- **H2 collectors: owner and front desk** (same as the SAH-10 counter; the billing role does not collect course payments).
Under the human's pre-approval of the EMR programme (DEC-039); not lines in docs/approvals.md.
