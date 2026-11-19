# DEC-066 · IPD money in Revenue and day close (OQ-27-4)

Date: 18 Nov 2026. Status: ANSWERED (A; BA now, code later). Raised by: tech lead, after the human asked "is billing clearance of IPD developed? If not, do it."
Blocked: a new story SAH-81 (SAH-80 is reserved for Pharmacy, DEC-061).

## What exists (checked 18 Nov)
- **Billing clearance at discharge: BUILT (SAH-27, DONE and reviewed).** It follows the mockup's Discharge sheet.
  - **Steps:** "Doctor signs the summary → Billing clearance → Nursing handover" (approval chain on, the default), or "Summary signed → Final bill".
  - **Final bill:** total charges, minus deposits received, then "To collect now", "To refund" or "Settled". It offers Collect by / Refund by (UPI, Cash, Card) and one button: "Collect ₹X and discharge", "Refund ₹X and discharge" or "Discharge".
  - **Rules:** the final bill is frozen at discharge; refund is owner-only; a bill that changed while the sheet was open gives 409; everything is audited; there is a Clinic setup → Discharge "Approval chain" switch.
  - **Tests:** about 90 real-database tests (DischargeIT, DischargeChecksIT, DischargeConcurrencyIT, IpdMoneyStayIT …) and 52 browser cases.
  - **DEC-014 (OQ-27-2):** "Billing clearance" and "Nursing handover" are labels completed by the one discharge action, exactly as in the mockup. They are not separate sign-offs by billing or nursing staff.

## What is not built
1. **Revenue page → "In-patient finance" panel (in the mockup, Revenue page, shown when IPD is on).** Four figures:
   - Running bills (patients admitted);
   - Deposits held (with "₹X not covered" or "Covers all running bills");
   - Deposit alerts (running bill over the threshold % of deposit);
   - Revenue per occupied bed (per day, this stay);
   - plus a link to Admitted patients.
   The Revenue report (SAH-16) was built before IPD and does not have it.
2. **IPD money in the Billing counter, day close and Revenue totals (OQ-27-4, never decided).** Deposits, the balance collected at discharge and refunds are kept only in `ipd` (ADR-004 D6). The Close-the-day sheet and the Revenue "Collected / Refunds" figures count OPD visit payments only. The mockup's day close also counts only OPD visit cash, so including IPD money there goes beyond the mockup.
   - **Risk** (security review SAH-27 I3; test plan SAH-27 "blocking for go-live"): IPD cash taken at the desk is never reconciled with the drawer, so cash recorded as UPI goes unnoticed.

## Options
- **A (recommended): one story SAH-81 "IPD money in Revenue and day close" with both parts.**
  - (1) The In-patient finance panel exactly as the mockup.
  - (2) IPD deposits, discharge collections and refunds counted in day close (Cash / UPI / Card, and cash expected in the drawer) and in Revenue Collected / Refunds, read from `ipd` through an `ipd.api` port (no new module, no new service).
  - Part (2) is a design addition to the mockup's day close, so the human signs off its wording.
  - Pro: closes OQ-27-4 and the cash-reconciliation risk before real patients.
  - Con: about 2-3 days through all stages; cross-module port (architect).
- **B: only the mockup panel (1).** IPD cash is reconciled outside the system for now, with written acceptance.
  - Pro: smaller, pure mockup.
  - Con: the reconciliation risk stays.
- **C: defer both** until after the test-data pilot.

## Order of work and branch
- SAH-76 (IPD safety fixes) is in its last QA round. The tree also holds the uncommitted Pharmacy story from another session, so a clean new branch `feature/SAH-81-…` cannot be cut from `main` now.
- Recommendation: business-analyst writes the SAH-81 requirement and stories now (docs only, on the current branch, as DEC-061 did for Pharmacy). Code starts after the human commits SAH-76, then on its own branch.

## Recommendation
A, with the BA starting now and the code after the SAH-76 commit.

## Answer
**Human, 18 Nov 2026:** A. SAH-81 covers both parts: (1) the In-patient finance panel on the Revenue page as in the mockup; (2) IPD deposits, discharge collections and refunds counted in day close and Revenue Collected / Refunds (a design addition; the human signs off its wording). The business-analyst starts now (docs only, current branch); code starts on its own branch after the human commits SAH-76.

