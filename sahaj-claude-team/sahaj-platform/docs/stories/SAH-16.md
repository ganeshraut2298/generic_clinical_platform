# SAH-16 · Revenue report
Mockup screen: Revenue (sidebar → Revenue), `vRevenue`
Module: billing

## 1. Story
As the clinic owner, I want to see what was collected, billed, discounted, refunded and is still due over today, the last 7 days and the last 30 days, so that I know how the clinic is doing in money terms without adding up bills by hand.

Scope note: the mockup draws its earlier days from sample data. Here every figure is computed from real bills and payments; a clinic that has been open for two days shows two days. Nothing is invented.

## Acceptance criteria
1. Given the owner opens Revenue, then a range switch offers Today, Last 7 days and Last 30 days (default Today), with the lead "Everything collected, billed and still due <today | in the last 7 days | in the last 30 days>, in one place."
2. Given a range, then four tiles show: **Collected** (net of refunds, with change versus the previous period of the same length where one exists, shown as ▲/▼ percent), **Billed** (total of closed bills after discount, with patient count and average bill), **Still due** (unpaid balance on bills that are Done, plus "N at the counter now" for the Billing stage), **Discounts and refunds** (discount rupees and refund rupees).
3. Given the range, then a "Collections by day" bar chart shows net collected per Asia/Kolkata day, today highlighted; days with no data show zero.
4. Given the range, then "How patients paid" shows Cash, UPI and Card as a stacked bar with rupee amounts and percent, and "Cash in drawer today" shows cash taken today net of cash refunds, with a "Close the day" link to the Billing counter's existing day-closing.
5. Given the range, then "By doctor" lists each doctor with patients, collected, average and share; "Top services" lists up to 6 services by billed amount (consultation counts as "Consultation").
6. Given the range, then "Dues to collect" lists patients with an unpaid balance on closed bills (name, mobile, date, doctor, amount), oldest first, and "Discounts and refunds" lists each discount (percent and reason, who approved if applicable) and refund (reason, mode, who) in the period. WhatsApp "Remind" buttons are shown disabled with "Needs WhatsApp (not in this phase)".
7. Given a role that is not the owner, then Revenue is not in the menu and the API returns 403; the page shows "Revenue is visible to the owner only. The owner can give access in Clinic setup › Team and access." if opened by URL. (The per-role permission grid already exists in SAH-6; granting `revenue` to another role is out of scope for this story.)
8. Given patient names and mobiles appear in the Dues list, then they are shown on screen only: never logged, never in an audit row. The endpoint is read-only and writes no audit rows. Tenant isolation (RLS) applies as in earlier stories.
9. "Export to Excel" is shown disabled ("Shown for layout in this phase"). No export is built.

## 3. Technical design
- No new tables. Read-only queries over `billing.payment`, `billing.bill`, `billing.bill_item` and the visit's consultation fee through the existing `opd.api` ports (billing may depend on opd, not the other way round). Doctor and patient names through `TeamMemberPort` and `PatientLookupPort`.
- All day boundaries are Asia/Kolkata calendar days. "Previous period" for 7 and 30 days is the immediately preceding 7 or 30 days; for Today it is yesterday. If the clinic has no activity in the previous period, the change is omitted (null).
- Net collected = payments minus refunds (refunds are negative payments, SAH-10).
- Money is integer paise in the API.
- Revenue uses the same bill math as SAH-10 (`BillingService` totals). Do not re-implement it: reuse the same calculation so the numbers match the counter.

## 4. API
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/billing/revenue?range=TODAY\|WEEK\|MONTH` | OWNER | none | `RevenueReport` | 400 (bad range), 401, 403 |

All paths under `/api/v1`. Contract: `docs/api/billing.yaml`.

## 5. Tasks
### Tasks — Backend
- Revenue query service and controller, owner-only. Unit tests for each figure, the previous-period change, a refund reducing net collected, discount math, dues only from Done bills, doctor and service grouping, IST day boundaries, 403 for a non-owner.
### Tasks — Frontend
- Replace the Revenue placeholder with the page: range switch, four tiles, daily bar chart (SVG, accessible title per bar), payment-mode stack, by-doctor table, top services bars, dues list, discounts and refunds list, disabled Remind and Export, loading and error states, non-owner message. Unit tests (mock HTTP).

## 6. Out of scope, Open questions
Out of scope: Excel export, WhatsApp reminders, role-based access to Revenue beyond the owner, charts older than 30 days, per-branch revenue, tax reports.
Open questions: none blocking; proceeding per DEC-008.

## 7. Status log
