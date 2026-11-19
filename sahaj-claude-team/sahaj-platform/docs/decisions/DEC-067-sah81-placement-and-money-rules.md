# DEC-067 · SAH-81: where the In-patient finance panel goes, and the IPD money rules

Date: 18 Nov 2026. Status: ANSWERED (1 A, 2 A, 3 A, rest as written). Raised by: tech lead from the SAH-81 business-analyst hand-back (`docs/requirements/SAH-81.md`, OQ-81-1..14).
Blocked: the SAH-81 `stories` approval and the architect stage.

## 1. Placement (OQ-81-1): the mockup differs from DEC-066
In the mockup, the "In-patient finance" panel sits on **Billing → Owner view** tab (`bOwnerHTML`, line 7970), not on the sidebar Revenue page. That Owner view tab is not built (SAH-10 left it out).
- **A (recommended): on the built Revenue page, owner only, below the existing panels.** Pro: small; the owner already reads money there. Con: a different place from the mockup.
- **B: build the Billing → Owner view tab** with this panel. Pro: matches the mockup. Con: the tab has other unbuilt parts (KPIs, leakage watch, counter status), so this becomes a bigger story.

## 2. Day close (OQ-81-3, -4, -6)
- **A (recommended): merged.** IPD deposits, discharge collections and refunds are added into the existing Cash / UPI / Card lines, plus one read-only line "In-patient money included" (net). A cash refund lowers cash expected. Money recorded after the day was closed shows in Revenue for that date, but a stored closing is never rewritten. This is the same as OPD today and the course-payment precedent (SAH-58).
- **B: separate IPD lines** in the day close (Deposits, Discharge collections, Refunds) under the OPD lines.

## 3. Front desk sees in-patient cash in the day close (OQ-81-8)
- **A (recommended): yes.** The front desk closes the day and must count the same drawer.
- **B: owner only.** The front desk then cannot reconcile a drawer that holds IPD cash.

## 4. The other proposals (OQ-81-2, -5, -7, -9 to -14) and NEW texts N1-N7
These are accepted by approving the stories as written (see `docs/requirements/SAH-81.md`):
- the panel is a phase-1 operational figure;
- dates are the IST date of the money;
- Billed, discounts, dues, by doctor, by category and services stay OPD-only, with note N4;
- IPD money counts whatever the IPD state;
- the mockup figures are kept as drawn;
- the refund list shows the patient's name (as OPD does; OQ-81-11);
- the Billing counter's "In-patient running bills" list is a later story;
- fail closed.

## Recommendation
1 A, 2 A, 3 A; the rest as proposed.

## Answer
**Human, 18 Nov 2026:**
1. A: the panel goes on the Revenue page, owner only.
2. A: merged into Cash/UPI/Card plus the read-only line "In-patient money included"; a cash refund lowers cash expected; closed days are never rewritten.
3. A: the front desk sees in-patient cash in the day close.
4. All other proposals (OQ-81-2, -5, -7, -9 to -14) and NEW texts N1-N7 are accepted as written.

Next: the human adds `| SAH-81 | stories | APPROVED | Ganesh | 2026-11-18 |` to docs/approvals.md; then the solution-architect starts.

