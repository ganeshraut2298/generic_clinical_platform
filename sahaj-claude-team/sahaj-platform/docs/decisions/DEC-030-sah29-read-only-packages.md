# DEC-030 · SAH-29 packages: read-only slice

Date: 9 Nov 2026. Status: DECIDED by the human (AskUserQuestion, 9 Nov 2026: "Build only what the mockup shows"). Open points: defaults applied (DEC-023), reversible.
Design: docs/architecture/SAH-29-ipd-packages.md. Contract: GET /packages and the package copy on AdmissionDetail in docs/api/ipd.yaml (parses, 53 paths).

## Scope
- IN: the read-only Packages list in Clinic setup → Admission and deposits, and the running bill with a package, following the mockup `ipLines` exactly.
- OUT, future work: create, edit and attach (OQ-29-1, OQ-29-2), and the audit of package changes.
- Packages are set by hand in the database for now, in `sahaj_dev` and `sahaj_test` only.

## Open points (architect's list), defaults applied
| # | Point | Default |
|---|---|---|
| P1 | **Go-live.** Without an attach screen, a real clinic could only get a package through an unaudited hand edit of billing data. | Hand-set packages only in `sahaj_dev` and `sahaj_test`. Do not use SAH-29 in a live clinic until create and attach are built. |
| OQ-29-3 | Empty panel: nothing is seeded, and the mockup has no empty-state text. | Show the empty list with no new text. |
| OQ-29-4 | The mockup prints "for 0 days" for day-care and "1 days" for one day. | Keep the mockup text literally. This is a one-line change if the human prefers "for the day". |
| OQ-29-5, OQ-26-4 | After a transfer, extra charges are re-priced at the new bed's multiplier, and a dearer bed adds no bed-difference charge (as the mockup does). | Built as the mockup. **The human should confirm the billing behaviour before go-live.** |
| P2 | Only active packages are listed. | Keep. |
| P3 | Package prices are whole rupees. | Keep, as for deposits. |

## Human answer on the open points
(none yet; defaults applied under DEC-023)
