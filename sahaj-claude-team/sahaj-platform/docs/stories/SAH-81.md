# SAH-81 · Stories: IPD money in Revenue and day close
Requirement: `docs/requirements/SAH-81.md` (rules 1 to 21, open questions OQ-81-1 to OQ-81-14). Decision: `docs/decisions/DEC-066-ipd-money-in-revenue-and-day-close.md` (answered A, 18 Nov 2026).
Module: billing (reads IPD data through an `ipd.api` port; no new module). Depends on SAH-10, SAH-16, SAH-19, SAH-23, SAH-24, SAH-25, SAH-26, SAH-27, SAH-29, SAH-58.
Mockup screen: Revenue, Reports (sidebar) (stories 1 and 2: the "In-patient finance" panel; stories 3 to 5: design addition to Billing → Close the day and the Revenue figures, NOT in the mockup, marked NEW)
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist. Not signed in gets 401. The clinic always comes from the logged-in user, never from the request. Money is integer paise; amounts show as ₹ 1,25,000; dates as 24 Oct 2026; times as 11:05 AM IST (Asia/Kolkata).
Marking: a text or behaviour marked **NEW** is not in the mockup and needs the human's sign-off. Placement of the panel is open (OQ-81-1): the stories say "the Revenue page" as proposed.
No real patient data in any example. Examples use made-up names such as "Test Patient".

### SAH-81-1 · As the owner of a hospital, I want an "In-patient finance" panel with running bills, deposits held, deposit alerts and revenue per occupied bed, so that I see in one place how much money is tied up in admitted patients and where deposits fall short
Priority: Must
Screen: Revenue page (proposed host, OQ-81-1), panel `In-patient finance` (mockup `bOwnerHTML`, line 7970).
## Acceptance criteria
- Given IPD is Trial or On and at least one patient is admitted, when the owner opens Revenue, then a panel with the heading "In-patient finance" shows below the existing panels, with the button "Admitted patients" (arrow icon) at the right of the heading and four figures in this order: Running bills, Deposits held, Deposit alerts, Revenue per occupied bed. (rules 1, 2)
- Given the figures, then each shows its label, a number and a sub-line exactly as follows: Running bills: ₹ amount and "N patients admitted"; Deposits held: ₹ amount and either "₹X not covered" or "Covers all running bills"; Deposit alerts: a whole number and "Running bill over T% of deposit"; Revenue per occupied bed: ₹ amount and "per day, this stay". (rules 4 to 7)
- Given three admitted patients whose running bills are ₹ 12,000, ₹ 30,000 and ₹ 8,000, when the panel loads, then Running bills shows ₹ 50,000 and "3 patients admitted", and the amount equals the sum of the Running bill column of the Admitted list for the same moment, including bed-transfer lines (SAH-26) and package lines (SAH-29). (rule 4)
- Given Running bills is ₹ 50,000 and the deposits of the same patients total ₹ 35,000, then Deposits held shows ₹ 35,000 and "₹ 15,000 not covered". Given deposits total ₹ 50,000 or more, then the sub-line reads "Covers all running bills" (also when exactly equal). (rule 5)
- Given a patient who is Insurance or Corporate, then their running bill and any deposit are inside Running bills and Deposits held, and they are never counted in Deposit alerts. Given a Self patient flagged "Going home today" (SAH-27), then they are inside Running bills and Deposits held and are not counted in Deposit alerts. (rules 4 to 6)
- Given the clinic's Deposit alert setting is 80% (Clinic setup → Admission and deposits, SAH-23), and one Self patient has a running bill of ₹ 40,000 and a deposit of ₹ 50,000 (80% reached), then Deposit alerts shows 1 and the sub-line "Running bill over 80% of deposit". Given the owner changes the setting to 90%, then the sub-line reads "Running bill over 90% of deposit" and the count is recalculated on the next load. The count equals the "Deposit alerts" tile of Beds and admissions at the same moment. (rule 6)
- Given a Self patient with no deposit and a running bill above ₹ 0, then they count as a deposit alert (SAH-25). (rule 6)
- Given 4 occupied beds, Running bills ₹ 80,000 and an average of 2 bill days over the admitted patients, then Revenue per occupied bed shows ₹ 10,000 (80,000 ÷ 4 ÷ 2). Given occupied beds or average days would be 0, then each divisor is treated as 1 (no division by zero). The amount is rounded half-up to whole paise in the API. (rule 7)
- Given the clinic's "Bed day counted" setting (SAH-25: At midnight or Every 24 hours), then the bill days used in the average follow it. (rule 7)
- Given the owner taps "Admitted patients", then IPD → Beds and admissions opens on the Admitted tab. (rule 2)
- Given the owner switches the range Today / Last 7 days / Last 30 days, then the four figures do not change (they describe right now). (rule 3)
- Given exactly one patient is admitted, then the sub-line reads "1 patient admitted" (**NEW**, N6; the mockup prints "1 patients admitted", OQ-81-13). Given the optional caption of N7 is approved, it shows under the heading (**NEW**).
- Given a patient is admitted, discharged or moved while the page is open, then the figures update on the next load; the page does not need to refresh by itself.
- Given a slow network, then the panel shows a loading state inside the Revenue page and the rest of the page is not blocked by the panel layout. Given the read fails, then the whole Revenue page shows its existing error state (the report never shows a partial total, rule 16).
## Notes for design
- Reuse the single running-bill calculation used by the Admitted list, board and tiles so the numbers always agree (SAH-25 note). Nothing is stored for these figures.
- The panel data may be part of the Revenue response or a separate call; the architect decides. Amounts are integer paise; the page formats rupees.
- Mockup labels and sub-lines are taken literally from line 7970; only N6 and N7 are new.

### SAH-81-2 · As the owner, I want the panel to appear only when it makes sense and only for me, so that clinics without IPD see no change and other staff do not see hospital money totals
Priority: Must
Screen: Revenue page, panel `In-patient finance`.
## Acceptance criteria
- Given IPD is Off for the clinic, when the owner opens Revenue, then the panel is not drawn and the Revenue page is exactly as SAH-16 built it; a direct call for the panel data returns no panel (or 403 module-off, as the architect decides) and never data. (rules 1, 8)
- Given IPD is On or Trial but nobody is admitted, then the panel is not drawn (the mockup draws it only when someone is admitted). (rule 1)
- Given IPD is Read-only and patients are still admitted, then the panel is shown with the same figures; given Read-only and nobody admitted, it is not drawn. (rule 8)
- Given a Trial that has ended and the state is no longer Trial or On, then the panel follows the state SAH-19 reports at that moment. (rule 8)
- Given a user who is not the owner (front desk, billing, doctor, visiting doctor, nurse, pharmacist), then Revenue is not in their menu, opening it by URL shows the existing text "Revenue is visible to the owner only. The owner can give access in Clinic setup › Team and access.", and the API answers 403; no panel data is returned. (rule 1; SAH-16 AC7)
- Given a user of another clinic, then they never see this clinic's panel or figures (tenant isolation by RLS; the clinic comes from the login). (rule 20)
- Given a signed-out user, then the call answers 401.
- Given the panel is read, then no audit row is written and no patient name, mobile number, IP number or reason is read, logged or returned: the response carries only counts and amounts. (rules 20, 21; privacy)
- Given the owner has a clinic with OPD only, then no IPD query is made and nothing about IPD is shown.
## Notes for design
- The IPD entitlement comes from `ModuleEntitlementPort` (SAH-19). The owner-only rule is the SAH-16 rule; granting `revenue` to other roles stays out of scope.
- If the human picks the Owner view tab (OQ-81-1), the same acceptance criteria apply to that tab and the role rule is the mockup's `can('revenue')`.

### SAH-81-3 · As the front desk or the owner, I want Close the day to include IPD deposits, discharge collections and refunds, so that the cash I count in the drawer matches the system
Priority: Must
Screen: Billing → Close the day sheet (mockup `openClosing`, line 3043). This story is a design addition: the mockup's day close counts OPD visit cash only. All behaviour below is **NEW** unless it repeats SAH-10 AC9.
## Acceptance criteria
- Given a hospital took IPD money today (IST date), when the owner or front desk opens "Close the day", then "Cash collected (system)", "UPI" and "Card" include it, "Still at the billing counter" stays the OPD amount, and the other fields (Cash counted in the drawer, Note, buttons) are as the mockup. (rules 10, 11)
- Given OPD cash ₹ 2,000, a cash IPD deposit ₹ 10,000 and cash collected at a discharge ₹ 1,500 today, then Cash collected (system) shows ₹ 13,500; the difference shown against the cash counted uses ₹ 13,500. (rule 10)
- Given a refund at discharge of ₹ 3,000 paid in Cash today, then it reduces Cash collected (system) by ₹ 3,000; given the refund was paid by UPI, then it reduces the UPI line and leaves Cash unchanged. Given the refund is larger than the money in that mode, the line can show a negative amount (as OPD today); the sheet does not hide it. (rule 10; OQ-81-4)
- Given a deposit taken by UPI and one by Card today, then they are in the UPI and Card lines. Given the discharge balance was collected by Card, it is in the Card line. (rule 9)
- Given a discharge where the deposits already cover the bill ("Settled"), then no money is added at discharge. Given a discharge with "To collect now" ₹ 4,000, then only ₹ 4,000 is added, not the deposits again. (rule 9)
- Given IPD money exists in the day, then a **NEW** read-only line "In-patient money included" (N1) shows the net amount of in-patient money across all modes, with the small text "Deposits and discharge payments, net of refunds" (N2). Given there is no IPD money today, the line is not shown. The line is not an input and does not change the cash expected. (rule 11; OQ-81-3)
- Given the cash counted differs from the expected cash, then the existing rule applies: the Over / Short message and the Note being needed. (SAH-10 AC9)
- Given the owner or front desk records the closing, then the stored expected cash includes the IPD cash; the closing is audited as today with the IPD amount added (amounts only, no names). One closing per clinic per day: a second attempt gets the existing 409 "The day is already closed". (rules 11, 21)
- Given the in-patient money cannot be read (service or database failure), when the user presses "Close the day", then nothing is recorded and the sheet shows **NEW** "In-patient money could not be read, so the day was not closed. Try again." (N3); the existing sheet stays open with the counted cash kept. (rule 16; OQ-81-14)
- Given a role that is not owner or front desk (billing, doctor, visiting doctor, nurse, pharmacist), then they cannot close the day and the call answers 403 as in SAH-10; the IPD amounts are not shown to them. (role table; OQ-81-8)
- Given another clinic's deposits and refunds, then they are never in this clinic's totals. (rule 20)
- Given a slow network on the close button, then the button is disabled while the request runs; a lost answer is resolved by reopening the sheet (the day shows as closed). A double tap records one closing.
- Given IPD is Off, Read-only or Trial, then IPD money recorded that day is still counted in the drawer figure; the "In-patient money included" line shows whenever the amount is not zero. (rule 17; OQ-81-9)
## Notes for design
- Purpose: close security finding SAH-27 I3 (cash recorded as UPI goes unnoticed). The precedent is SAH-58 (course payments merged into the same lines).
- One consistent read so the amounts on the sheet, the stored closing and "Cash in drawer today" on Revenue agree (architect question 4).
- Proposed option merged lines (OQ-81-3); if the human prefers separate lines, only the line list of this sheet changes.

### SAH-81-4 · As the owner, I want the Revenue report's Collected, Refunds and payment-mode figures to include IPD money for Today, Last 7 days and Last 30 days, so that Revenue and the drawer tell the same story
Priority: Must
Screen: Revenue page (`vRevenue`: tiles Collected and Discounts and refunds, "Collections by day", "How patients paid", "Cash in drawer today", "Discounts and refunds" list). A design addition, marked **NEW**.
## Acceptance criteria
- Given IPD money in the chosen period, when the owner opens Revenue, then **Collected** (net of refunds) includes IPD deposits and discharge collections minus IPD refunds at discharge, **Refunds** includes IPD refunds at discharge, and the tile "Discounts and refunds" shows the new refund total in its refunds part. (rules 9, 10, 13)
- Given a deposit of ₹ 10,000 by UPI and a cash refund of ₹ 2,000 today, then "How patients paid" shows UPI +₹ 10,000 and Cash −₹ 2,000 (net per mode, with percent shares as SAH-16 AC4), and "Collections by day" shows today's bar including both. (rule 13)
- Given the range Last 7 days or Last 30 days, then each IPD money movement is on the bar of its IST date (a deposit at 11:50 PM IST on the 3rd is on the 3rd; one at 12:10 AM IST on the 4th is on the 4th). (rules 12, 13)
- Given the "change versus the previous period" on Collected, then the previous period of the same length also includes IPD money; where the previous period has no activity at all, the change is omitted (SAH-16 AC2). (rule 13)
- Given "Cash in drawer today", then it equals the cash expected in the Close the day sheet at that moment (OPD cash + course cash + IPD cash − cash refunds). (rules 10, 13)
- Given an IPD refund at discharge, then the "Discounts and refunds" list shows a line with the patient name (on screen only), the reason **NEW** "Deposit refund at discharge" (N5), the mode, who gave it (the owner, as refunds are owner-only, SAH-27) and the date; it is ordered with the other adjustments as SAH-16 orders them. (rule 13; OQ-81-11)
- Given the period has IPD money and IPD is not Off, then a **NEW** note shows under the figures: "Collected, how patients paid and refunds include in-patient deposits and discharge payments. Billed, discounts, dues, by doctor and top services are for out-patient visits only." (N4). Given the period has no IPD money, the note is not shown. (rule 14; OQ-81-7)
- Given Billed, Average bill, Discounts, Still due, Dues to collect, By doctor and Top services, then they are unchanged from SAH-16 (OPD visits only); IPD money is not added to any doctor's Collected and no IPD line appears in Top services. (rule 14)
- Given an OPD-only clinic or a period without IPD money, then every figure and list is exactly as SAH-16 built them.
- Given a user who is not the owner, then Revenue answers 403 and the existing page text applies. Given another clinic's money, it never appears. (SAH-16 AC7; rule 20)
- Given a slow or failed read of IPD money, then the whole Revenue report shows its existing loading or error state and never a total without IPD money. (rule 16)
- Given the report is read, then no audit row is written; patient names are never logged. (rule 21; SAH-16 AC8)
## Notes for design
- The change of the existing endpoint's response is the architect's call (contract `docs/api/billing.yaml`); amounts are integer paise. Precedent in code: `RevenueService` already merges course payments into the money-in list; course payments have no doctor line and no refunds, IPD money has no doctor line and has refunds.
- IST day boundaries are Asia/Kolkata, as SAH-16.

### SAH-81-5 · As the owner, I want clear rules for which day IPD money belongs to, what happens to a closed day, and what is left out, so that the drawer, the closings and Revenue stay trustworthy
Priority: Must
Screen: Billing → Close the day and Revenue page (rules of the design addition; no new screen).
## Acceptance criteria
- Given a deposit recorded at 9:15 AM IST, then it belongs to that IST date. Given a discharge completed at 10:40 PM IST, then the balance collected or refunded belongs to that IST date, even if the bill days run on past midnight by the "Bed day counted" rule. (rule 12; OQ-81-5)
- Given IPD money, then no user can set its date; the recorded moment is the server time of the action (no back-dating). (rule 12)
- Given the day is closed at 8:00 PM and a deposit is recorded at 9:00 PM the same IST date, then the stored closing is not changed; the deposit shows in that date's Revenue figures ("Collections by day", Collected) and is not in the next day's cash expected. Proposed behaviour, the same as OPD payments today; alternatives in OQ-81-6. (rule 15)
- Given the day is already closed, then Close the day offers nothing new and the existing 409 applies to a second closing. (SAH-10 AC9)
- Given a closing stored before this story, then it is never changed; Revenue for past days is recalculated from recorded money and may show a higher Collected than before for hospitals that took IPD money earlier. (rules 15, 18; OQ-81-6)
- Given IPD money recorded before this story (for example in a trial), then it is counted by its recorded date automatically; no data is rewritten, deleted or back-filled. (rule 18)
- Given a deposit later "used" against the final bill, then it is not counted again at discharge; only the balance paid or returned at discharge counts, so the same rupee is never counted twice. (rule 9)
- Given the running bill, the "Total charges" of a final bill or a package price, then none of them is counted as money collected. (rule 9)
- Given an Insurance or Corporate patient, then only what the hospital actually collected from or returned to the family (a deposit, a discharge balance, a refund) is counted; the insurer's payment is not (out of scope). (rule 9; OQ-81-10)
- Given by-doctor, by-service-category ("Where the money came from"), Billed, Discounts and Dues, then IPD money is excluded from all of them, and the note N4 of SAH-81-4 tells the owner so. (rule 14; OQ-81-7)
- Given the panel figures (SAH-81-1), then they are never added into Collected or the day close: the running bill is not money in. (rule 9)
- Given the in-patient money cannot be read, then the day is not closed and Revenue fails whole (see SAH-81-3 and SAH-81-4). (rule 16)
- Given the two roles that read the figures, then the day-close money lines are visible to the owner and front desk only, and the Revenue figures to the owner only. (role table; OQ-81-8)
- Given any money query, then it runs for the logged-in user's clinic only, through the `ipd.api` port; billing never reads `ipd` tables directly, and no new module or service exists. (rule 20)
- Given logs and audit rows of these reads and of the closing, then they contain amounts, modes, dates and ids and never a patient's name, mobile number, ABHA number, IP number or reason for admission. (rules 20, 21; privacy)
## Notes for design
- These are the rules the integration tests must pin: date boundaries at 11:59 PM and 12:00 AM IST, refund by mode, a settled discharge, a late deposit after closing, another clinic's money, Off and Read-only states.
- Open decisions that may change this story: OQ-81-5, OQ-81-6, OQ-81-7, OQ-81-8, OQ-81-9, OQ-81-14.
- Supersedes ADR-004 D6 ("money stays in ipd" for reading); IPD still stores its own records and billing only reads them.
