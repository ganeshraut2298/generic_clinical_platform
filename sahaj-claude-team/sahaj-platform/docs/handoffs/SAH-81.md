# SAH-81 · IPD money in Revenue and day close

Decision: DEC-066 (answered A, 18 Nov 2026). Closes OQ-27-4 and security SAH-27 I3.
Mockup screens: Revenue (the "In-patient finance" panel, shown when IPD is on); Billing → Close the day (design addition: IPD money is not in the mockup's day close).
Branch: the BA works on the current branch, `feature/SAH-39-home-kinetic-background` (docs only). Code goes on `feature/SAH-81-ipd-money-revenue` after the human commits SAH-76.

## Stage log
| Stage | Agent | Started | State |
|---|---|---|---|
| Requirement + stories | business-analyst | 18 Nov 2026 | running |

## Stage 1 · business-analyst · 18 Nov 2026
Status: DONE, NEEDS DECISION (DEC-067). Files: `docs/requirements/SAH-81.md` (21 rules, OQ-81-1..14, 8 architect questions), `docs/stories/SAH-81.md` (SAH-81-1..5). Validation (tech lead): `validate docs` fails only on the known `ADM-2.md` name.
- **Mockup finding:** the "In-patient finance" panel is drawn on Billing → **Owner view** tab (`bOwnerHTML`, line 7970), not on the sidebar Revenue page. The Owner view tab is unbuilt. Proposal: put the panel on the built Revenue page, owner only.
- **Panel:**
  - Running bills, Deposits held ("₹X not covered" / "Covers all running bills"), Deposit alerts (Self payer, threshold), Revenue per occupied bed.
  - Shown when IPD is not Off and at least one patient is admitted; a snapshot, not tied to the period switch; owner only.
- **Part 2 (NEW):**
  - IPD deposits, discharge collections and refunds are merged into the day-close Cash/UPI/Card lines, plus a read-only line "In-patient money included" (the SAH-58 course-payment precedent).
  - A cash refund reduces cash expected. Dates are the IST date of the money.
  - A stored closing is never rewritten.
  - Revenue Collected / Refunds / modes / by day / cash in drawer include IPD money; Billed, discounts, dues, doctor, category and services stay OPD (note N4).
  - Fail closed.
- **NEW texts N1-N7** for sign-off.
- **Architect (after approval):** an `ipd.api` read port used by billing (billing → ipd only), a new ADR superseding ADR-004 D6, `billing.yaml` changes, one snapshot for the day close.
