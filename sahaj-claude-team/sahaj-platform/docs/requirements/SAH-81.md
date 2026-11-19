# SAH-81 · IPD money in Revenue and day close
Module: billing (reads IPD data through an `ipd.api` port; no new module, no new service)
Mockup screen: Revenue, Reports (sidebar) (part 1: the "In-patient finance" panel, shown when IPD is on; part 2, a design addition: Billing → Close the day and the Revenue Collected / Refunds figures)
Also uses: Billing (sidebar → Billing) for the day-close sheet; IPD → Beds and admissions for the link target.
Source: human, answered option A in `docs/decisions/DEC-066-ipd-money-in-revenue-and-day-close.md`, 18 Nov 2026. Closes OQ-27-4 and security finding SAH-27 I3.
Stories: `docs/stories/SAH-81.md`.

## Problem
Two gaps remain after the IPD discharge story (SAH-27) was built.

1. **The mockup's "In-patient finance" panel is missing.** The mockup shows four owner figures about admitted patients (running bills, deposits held, deposit alerts, revenue per occupied bed). SAH-16 (Revenue) was built before IPD and has none of them.
2. **IPD money never reaches the cash drawer reconciliation.** Deposits (SAH-23), the balance collected at discharge and refunds at discharge (SAH-27) are stored only in `ipd` (ADR-004 D6). Billing → Close the day and the Revenue Collected / Refunds figures count OPD visit payments (and course payments, SAH-58) only. A hospital that takes cash deposits at the desk cannot make the drawer match the system, and cash recorded as UPI goes unnoticed (SAH-27 security I3, a go-live blocker).

Part 1 is exactly the mockup. Part 2 is **not in the mockup** (its day close counts OPD visit cash only); the human has asked for it and signs off its wording. Every new text is marked **NEW** below and in the stories.

### Mockup finding the human must see (OQ-81-1)
DEC-066 says "Revenue page". In `docs/design/mockup/sahaj-mockup.html` the panel (line 7970) is drawn inside `bOwnerHTML()`, which is the **Billing page → "Owner view" tab** (owner only, with the "Full revenue report" button), after "Revenue leakage watch". The sidebar **Revenue** page (`vRevenue`, line 3142), which SAH-16 built, has no In-patient finance panel. The Billing → Owner view tab is not built (SAH-10 left it out; the counter page says so). The stories are written so the panel is one self-contained block; the proposal is to place it on the built Revenue page (below the existing panels), and the human confirms or asks for the Owner view tab instead.

## Who uses it (roles) and in which setting
| Role | Panel "In-patient finance" | Close the day sheet (with IPD money) | Revenue figures (with IPD money) |
|---|---|---|---|
| owner | sees it | can close the day, sees the figures | sees it |
| front desk | no (Revenue is owner only, SAH-16 AC7) | can close the day (as SAH-10 AC9), sees the figures | no |
| billing | no | no change (the SAH-10 rule for who may close the day stays as built) | no |
| doctor, visiting doctor, nurse, pharmacist | no (403 on a direct call) | no | no |

Setting: the hospital front desk and the owner's office of a clinic that has IPD (Trial, On or Read-only). OPD-only clinics see no change.

Role grants: Revenue is owner only today; the permission grid of SAH-6 can name `revenue` for other roles but SAH-16 left granting it out of scope. SAH-81 follows SAH-16 and adds no new permission.

## In scope / Out of scope
In scope, part 1 (mockup):
- The panel "In-patient finance": heading, the link button "Admitted patients", four figures with their sub-lines, shown only when IPD is on and someone is admitted.

In scope, part 2 (design addition, marked NEW):
- IPD **deposits**, **balance collected at discharge** and **refunds at discharge** counted in:
  - Billing → Close the day: Cash collected (system), UPI, Card, and so the cash expected in the drawer;
  - the Revenue report: Collected, Refunds, "How patients paid", "Collections by day", "Cash in drawer today", the change against the previous period, for Today, Last 7 days and Last 30 days.
- A read-only reconciliation line and notes so the owner can see how much of the figures is in-patient money (NEW texts).

Out of scope (stated; see open questions for the ones a human may change):
- By-doctor, by-service-category ("Where the money came from"), Top services, Billed, Average bill, Discounts and Still due figures: stay OPD-only. IPD has no discount and no dues in phase 1 (SAH-27 has no discharge against an unpaid bill). (OQ-81-7)
- A new screen of any kind. A discharged-patients list or an IPD ledger.
- The mockup's "In-patient running bills" list on the Billing counter (`bIpdHTML`, line 7936). It is another IPD item of the mockup that is not built; DEC-066 does not ask for it. (OQ-81-12)
- Revenue per ward, per bed category or per doctor for IPD; trends or history of the four panel figures (needs the analytics service, Later).
- Insurance or corporate claim money (the insurer's payment). Only what the hospital collects from or returns to the family is counted.
- WhatsApp, SMS, notifications, payment gateway, export. Phase 1 only.
- Changing how deposits, discharge or refunds are taken (SAH-23, SAH-27 stay as built). Reopening a closed day.
- Package creation or attach (SAH-29 future work).

## Business rules (numbered)
Part 1 · the panel
1. **Where it is shown.** The panel shows only when IPD is on for the clinic (state Trial, On or Read-only; not Off) and at least one patient is admitted. Otherwise it is not drawn at all (the mockup condition `ipdSeen() && ipAdm().length`). Only the owner can see it (as Revenue, SAH-16). Placement: OQ-81-1.
2. **Heading and link.** Heading "In-patient finance". A button "Admitted patients" (arrow icon) opens IPD → Beds and admissions on the Admitted tab. Order of the figures is fixed: Running bills, Deposits held, Deposit alerts, Revenue per occupied bed.
3. **The panel is a snapshot of right now.** It counts admitted patients as they are at the moment the page is read and does not change with the Today / Last 7 days / Last 30 days switch. Nothing is stored; nothing is posted by a timer (as the running bill, SAH-25).
4. **Running bills** = the sum of the running bill of every admitted patient, whatever the payer (Self, Insurance, Corporate). The running bill is the one of SAH-25, SAH-26 (bed transfer) and SAH-29 (package), computed by the same code as the Admitted list, so the figure equals the sum of the Running bill column. Sub-line: "N patients admitted" (N = admitted patients). Discharged patients are not counted (their bill is frozen and settled, SAH-27).
5. **Deposits held** = the sum of all deposits received for the admitted patients (SAH-23, all modes). Sub-line: "₹X not covered" when Running bills is more than Deposits held, X = the difference; otherwise "Covers all running bills" (also when equal). This is a clinic-wide total, not a per-patient check, exactly as the mockup (a patient with a large balance can be hidden by another patient's surplus; OQ-81-10).
6. **Deposit alerts** = the number of admitted patients with the "Deposit low" status of SAH-25: payer is Self, the patient is not flagged "Going home today" (SAH-27 AC10), and running bill × 100 is at or above deposit × alert percent. The alert percent is the clinic's **Deposit alert** setting in Clinic setup → Admission and deposits (SAH-23; default 80%, 50 to 100). Insurance and Corporate patients are never counted (their deposit alerts are paused). Sub-line: "Running bill over T% of deposit" (T = the alert percent now set). The count equals the "Deposit alerts" tile of the Beds and admissions page.
7. **Revenue per occupied bed** = Running bills ÷ number of occupied beds ÷ the average bill days of the admitted patients, where occupied beds are the beds in the state Occupied (SAH-24) and bill days follow the clinic's "Bed day counted" setting (SAH-25: At midnight or Every 24 hours). Both divisors are at least 1. Rounded half-up to whole paise in the API and shown in rupees. Sub-line: "per day, this stay". (This is the mockup formula; it is an approximate figure, not a ledger. OQ-81-13.)
8. **IPD state effect.** Trial and On: the panel shows (the clinic is using IPD). Read-only: the panel still shows while patients remain admitted (the mockup condition is "IPD is not Off"). Off: the panel is not drawn and the API returns no panel data. Trial that has ended follows the entitlement state SAH-19 reports at that moment.

Part 2 · IPD money in day close and Revenue (NEW, design addition)
9. **What counts as IPD money.** Three kinds of real money movement recorded in `ipd`, each with its mode (Cash, UPI, Card), amount in integer paise, and the moment it was recorded:
   a. a **deposit** (at admission or a later "Collect deposit", SAH-23): money in;
   b. the **balance collected at discharge** (final bill balance above zero, SAH-27): money in, with the "Collect by" mode;
   c. a **refund at discharge** (final bill balance below zero, SAH-27): money out, with the "Refund by" mode.
   Nothing else counts. Deposits used against the final bill are not new money, so they are not counted a second time at discharge: only the balance paid or returned at discharge counts. A "Settled" discharge (balance zero) adds no money. The running bill itself is never money collected.
10. **Same arithmetic as OPD.** Money in adds to the mode's total; a refund subtracts from the mode's total (OPD refunds are negative payments, SAH-10). So cash expected in the drawer = OPD cash + course cash (SAH-58) + IPD cash deposits + IPD cash collected at discharge − IPD cash refunds. UPI and Card follow the same rule per mode. Proposed: a refund reduces cash expected only when it was given in cash (OQ-81-4).
11. **Day close.** The existing lines of the sheet (Cash collected (system), UPI, Card) include IPD money (proposal: merged into the existing lines, as course payments were by SAH-58, so the mockup layout does not change; OQ-81-3). Also NEW: one read-only line "In-patient money included" with the net amount (all modes) so the cashier can see how much came from in-patient money when the drawer does not match. "Still at the billing counter" stays OPD-only (IPD has no bill waiting at the counter in phase 1). Who may close and the "one closing per clinic per day" rule of SAH-10 AC9 do not change. The stored closing keeps the expected cash it was computed with (so it includes the IPD money of that moment).
12. **Which day.** A money movement belongs to the Asia/Kolkata calendar date of the moment it was recorded: the deposit time, and for the discharge balance or refund the time the discharge was completed. No money can be dated in the past or future by a user. (OQ-81-5)
13. **Revenue report.** For Today, Last 7 days and Last 30 days: **Collected** is net of refunds and includes IPD money; **Refunds** includes IPD refunds at discharge; **How patients paid** (Cash, UPI, Card, with the percent shares) includes it by mode; **Collections by day** includes it on its IST day; **Cash in drawer today** equals the cash expected in the day-close sheet at that moment; the "change versus the previous period" compares like with like (the previous period also includes IPD money). The "Discounts and refunds" list adds a line for each IPD refund at discharge with the reason "Deposit refund at discharge" (NEW), who gave it, mode and date. The patient name is shown on screen as for OPD refunds (SAH-16 AC8); OQ-81-11.
14. **Not counted.** Billed, Average bill, Discounts, Still due, Dues to collect, By doctor and Top services stay as SAH-16 built them (OPD visits only). Because "Collected" then includes money that is not in "Billed", the page shows a NEW note (only when the period has IPD money): "Collected, how patients paid and refunds include in-patient deposits and discharge payments. Billed, discounts, dues, by doctor and top services are for out-patient visits only." (OQ-81-7)
15. **A closed day stays closed.** A closing already recorded is never rewritten when IPD money arrives later. IPD money recorded after the day was closed, on the same IST date, shows in the Revenue figures for that date but is not part of the stored closing (the same as OPD payments today). Proposed to accept, with options in OQ-81-6.
16. **No guess when the data cannot be read.** If the in-patient money cannot be read, the day is not closed and the Revenue report fails as a whole (it never shows a total that quietly leaves IPD money out). NEW text for the sheet: "In-patient money could not be read, so the day was not closed. Try again." (OQ-81-14)
17. **IPD state and money.** IPD money counts in all IPD states, including Read-only and Off, because the cash in the drawer is real whatever the module state. The panel and the NEW line "In-patient money included" follow rule 8 and rule 11 (the line is shown whenever the amount is not zero). (OQ-81-9)
18. **History.** Deposits and discharge settlements recorded before this story are counted by their recorded date automatically; no data is rewritten. Revenue for past days will therefore show higher Collected than before for hospitals that already took IPD money; closings already stored do not change. (OQ-81-6)

Cross-cutting
19. Money is integer paise in the API and database; shown as ₹ 1,25,000, dates as 24 Oct 2026, times as 11:05 AM IST. The IST date logic is Asia/Kolkata, never the server's zone.
20. The tenant always comes from the logged-in user; another clinic's money is invisible (RLS). The IPD figures are read through an `ipd.api` port from billing, with no table access across schemas and no new module.
21. Reading is not audited (as SAH-16: read-only). Recording a day closing is audited as today, with the IPD amount added (amounts only).

## Patient-safety and privacy points
- Money safety: the day close is the control that catches cash recorded as UPI. Counting IPD money closes that gap (SAH-27 I3). The "fail whole, never partial" rule (rule 16) prevents a day being closed against a drawer figure that silently leaves IPD money out.
- No clinical data is used or shown. The panel and the day close show only amounts, counts and modes: no names, no diagnosis, no reasons for admission.
- Revenue's refund list shows patient names on screen only (as SAH-16 AC8; OQ-81-11). Names, mobile numbers, admission reasons and IP numbers are never logged and never in an audit row; audit rows carry amounts and ids only.
- The port between billing and ipd carries amounts, modes, times and ids, not the patient's name, mobile or reason. Names for the refund list come from the existing patient lookup port, as SAH-16 does.
- The panel's per-clinic totals are owner-only; the day-close money lines are visible to the two roles that may close the day.
- Deposit alerts shown here are the SAH-25 alerts; Insurance and Corporate patients stay excluded exactly as there (no new alert rule).

## Plan or entitlement (which plan/module unlocks it)
- Part 1 needs the IPD module on (Trial, On, Read-only) and at least one admitted patient (SAH-19 entitlement through `ModuleEntitlementPort`). Off: not drawn.
- Part 2 needs no extra entitlement: it only adds IPD money that exists. An OPD-only clinic has no IPD money and sees no change.

## Dependencies on existing stories
- SAH-10 (billing counter, payments as negative refunds, day close AC9, role rule), SAH-16 (Revenue: owner only, ranges, tiles, "Cash in drawer today", IST days), SAH-58 (course payments merged into day close and Revenue: the precedent).
- SAH-19 (IPD state and entitlement), SAH-22 and SAH-24 (beds, Occupied state), SAH-23 (admission, deposits, deposit alert setting), SAH-25, SAH-26, SAH-29 (running bill, transfer, package), SAH-27 (final bill, settlement, refund owner-only, Going home today), ADR-004 D6 (to be superseded), `docs/reviews/SAH-27-security.md` I3, OQ-27-4 and OQ-27-9.
- Code starts only after the human commits SAH-76, on its own branch (DEC-066).

## Architecture questions for the solution-architect
1. Port: an `ipd.api` read port used by billing (no new module, no new service). Direction billing → ipd, and ipd must not depend on billing (check the Spring Modulith cycle). Suggested shape (not a design): money movements between two instants (kind, mode, signed paise, recorded at, admission id), per-mode sums since an instant, period totals, and one "finance snapshot" (running total, deposits, alert count, admitted count, occupied beds, average bill days) computed with the same `RunningBill` code as the Admitted list.
2. Where the panel endpoint lives (an extra part of `GET /billing/revenue`, or a call to ipd by the page) and the response shape; the panel absent for Off or nobody admitted.
3. How the port behaves when IPD is Off or Read-only (it must still return money, rule 17) while the panel data respects the state (rule 8).
4. One consistent read for day close: a single snapshot so the cash expected shown, the stored closing and "Cash in drawer today" agree (same transaction or same instant).
5. Where the day-close sheet gets its figures today (the existing counter/revenue read) and how the new "In-patient money included" value reaches it; whether the DAY_CLOSING_RECORDED audit adds the IPD amount.
6. Failure policy of rule 16 (fail the call; no partial total) and its error text.
7. Supersede ADR-004 D6 with a new ADR; contract changes in `docs/api/billing.yaml` (and `docs/api/ipd.yaml` only if the port is also an endpoint).
8. Performance: 10,000 OPD clinics, 100 hospitals; the IPD sums are per tenant and bounded by the period (at most 30 days), plus the admitted list.

## Open questions for the human
Numbered OQ-81-n. Each has a proposed default; the stories are written to the proposal and the proposal is marked.

- **OQ-81-1 (placement, mockup difference).** The mockup draws the panel on Billing → Owner view, not on the Revenue page, and Owner view is not built. Proposed: put the panel on the built Revenue page (owner only), below the existing panels. Alternative: build the Owner view tab (a bigger story: tiles, charts, leakage watch, counter status). Which?
- **OQ-81-2 (phase 1 scope).** The screen map says Revenue is "1 (lists) / Later (dashboards)". The panel is four figures from live data (sums and a count), no history or trend, no analytics service; SAH-16 already shows tiles. Proposed: it counts as an operational figure and is phase 1. Confirm.
- **OQ-81-3 (day close lines).** Separate lines for IPD (for example "Cash from in-patients") or merged into Cash, UPI, Card? Proposed: merged into the three existing lines (as SAH-58 did) plus one NEW read-only line "In-patient money included".
- **OQ-81-4 (refund and cash expected).** Does a refund at discharge reduce cash expected in the drawer? Proposed: yes, when the refund mode is Cash (the same as an OPD cash refund, SAH-10 AC9); a UPI or Card refund reduces only that mode's line.
- **OQ-81-5 (date counted).** Proposed: the IST date of the deposit time, and of the discharge completion time for the balance and the refund. Confirm. Should a deposit taken after the clinic's closing time still count for that IST day (proposed: yes, there is no cut-off time in the mockup)?
- **OQ-81-6 (closed day and late money).** What if IPD money is recorded after the day is closed (for example a night deposit)? Proposed: the stored closing never changes; the money is in that date's Revenue figures; this is the same gap OPD has today. Alternatives: (a) refuse new IPD money after the day is closed; (b) carry it into the next day's cash expected with a label. Also: Revenue for past days will rise retroactively for hospitals with IPD money recorded before this story, and will not match closings already stored; accept?
- **OQ-81-7 (breakdowns).** Proposed: Billed, discounts, dues, by doctor, by category and top services stay OPD only (no IPD data in them), with the NEW note in rule 14. Is a note acceptable, or should IPD final bills also count as Billed (a bigger change)?
- **OQ-81-8 (who sees what).** Proposed: the panel and Revenue figures owner only; the day-close money lines owner and front desk (they already close the day); no change for the billing role. Does the front desk see in-patient cash totals in the day close? (They are amounts only, no names.)
- **OQ-81-9 (IPD Off and Read-only).** Proposed: IPD money counts in all states; the panel follows the mockup (hidden when Off, shown while patients remain admitted in Read-only). Confirm.
- **OQ-81-10 (aggregate and insurance).** The mockup's "₹X not covered" is a clinic-wide difference, and Running bills includes Insurance and Corporate patients' whole bill (the insurer's share is not money the family owes: related to OQ-27-9). Proposed: keep as the mockup. Confirm, or ask for Self-pay only.
- **OQ-81-11 (refund list privacy).** Proposed: the Revenue refund list shows the IPD patient's name and the NEW reason "Deposit refund at discharge" (as OPD refunds). An in-patient stay is more sensitive than a visit. Show the name, or only the IP number?
- **OQ-81-12 (Billing counter list).** The mockup's "In-patient running bills" list (top 5 running bills on the Billing counter, with deposit and "deposit low") is also unbuilt. Proposed: a later story, not SAH-81. Confirm.
- **OQ-81-13 (texts and formula).** Sign-off of the NEW texts below, of the singular "1 patient admitted" (the mockup prints "1 patients admitted"), and of the Revenue-per-bed formula (running bills ÷ occupied beds ÷ average bill days), which is approximate. Also whether to add the small caption "Right now, whatever period is chosen above" under the heading so the owner does not read the panel as a period figure.
- **OQ-81-14 (fail closed).** Proposed: if IPD money cannot be read, the day is not closed and the Revenue report fails whole (rule 16). Confirm that this is wanted over showing a partial figure with a warning.

## NEW texts (every one needs the human's sign-off; none is in the mockup)
| # | Where | Text |
|---|---|---|
| N1 | Close the day sheet, label | In-patient money included |
| N2 | Close the day sheet, small text under the amount | Deposits and discharge payments, net of refunds |
| N3 | Close the day sheet, error | In-patient money could not be read, so the day was not closed. Try again. |
| N4 | Revenue, note under the figures (only when the period has IPD money and IPD is not Off) | Collected, how patients paid and refunds include in-patient deposits and discharge payments. Billed, discounts, dues, by doctor and top services are for out-patient visits only. |
| N5 | Revenue, "Discounts and refunds" list, reason of an IPD refund | Deposit refund at discharge |
| N6 | Panel sub-line, one patient | 1 patient admitted (the mockup prints "1 patients admitted") |
| N7 | Panel caption (optional, OQ-81-13) | Right now, whatever period is chosen above |

## Status log
- 18 Nov 2026: requirement and stories written by the business-analyst (docs only, branch `feature/SAH-39-home-kinetic-background`). Code later on its own branch after SAH-76 is committed (DEC-066).
