# SAH-10 · Billing counter
Mockup screen: Billing (sidebar → Billing)
Module: billing

## 1. Story
As front-desk staff or the owner, I want to collect payment for a visit once the doctor is done, so that the visit closes with an accurate bill and the money collected is recorded.

Scope note: the mockup's Billing screen also has a Revenue tab (`vRevenue`) with 30 days of trend charts and breakdowns. Screen-map marks Revenue/Reports as "simple operational reports now, dashboards wait for the analytics service" (Later). This story builds the billing counter only; Revenue is a later story once there is real historical data to report on, not fabricated history.

## Acceptance criteria
1. Given a visit reaches the BILLING stage (from SAH-9), then it appears under "Awaiting payment" with its token, patient and the consultation fee as the first line item.
2. Given staff open a bill, then they can add extra service items from the clinic's service list (SAH-7), each with a quantity, and remove any item except the consultation line.
3. Given a discount, then the percent cannot exceed the signed-in role's discount limit from SAH-7's fee settings (`discountLimits`); a role with no key has no discount right (0%). The owner has no limit.
4. Given payment is collected (Cash, UPI or Card — no real payment gateway in Phase 1, same as the mockup's own "mark as received" placeholder for UPI), then the balance reduces; when it reaches ₹0 the visit's stage moves to DONE automatically.
5. Given a partial payment, then the visit stays visible under "Dues" after the consultation ends, with the remaining balance shown.
6. Given a fully paid visit, then "Paid today" shows it with a simple receipt (patient, items, amounts, who collected, when). No PDF or WhatsApp send in Phase 1 (both need services this project doesn't have yet).
7. Given a refund, then only the owner can do it; it is recorded as a negative payment with a reason, and the bill's balance/paid total reflect it.
8. Given a non-owner, non-front-desk role, then they can view a bill but cannot edit items, discount or collect payment ("Only the front desk or the owner can take payments. You can view this bill."), matching the mockup's own wording.
9. Given the end of the day, then the owner or front desk can "close the day": enter cash counted, see the expected cash (sum of today's cash payments minus cash refunds) and the difference, with a note if there is one. One closing per clinic per day.
10. Given any write, then it is audited (who, what, when) and tenant isolation (RLS) applies exactly as prior stories. A patient's name never appears in clear in an audit row (reuse the existing entity-type-aware redaction).

## 3. Technical design
- Schema `billing`, module `com.sahaj.billing`, talking to `opd.api` (visit detail, advancing a visit's stage to DONE), `patient.api` (patient name/age/sex) and `platform.api` (AuditLog, fee-settings/discount-limit read) — one direction only: `billing` depends on `opd.api`/`patient.api`/`platform.api`; nothing below calls up into `billing`, so there is no new module cycle.
- `billing.bill`: `id`, `tenant_id`, `visit_id` (FK to `opd.visit`, unique — one bill per visit), `discount_percent` (0-100, default 0), `discount_reason`, `created_at/by`, `updated_at/by`, `version`. Created on first write (an item added, a discount set, or a payment recorded) rather than at visit creation, so `opd` never needs to know `billing` exists. Until first write, a bill is synthesized read-only from the visit's own consultation fee with zero items/discount.
- `billing.bill_item`: `id`, `tenant_id`, `bill_id`, `service_id` (nullable FK to `opd.service`), `name` (snapshot, so a later service rename/removal doesn't change history), `amount_paise`, `qty`, `created_at/by`. The consultation line itself is never stored here — it is always derived live from the visit's `fee_paise`, so there is one source of truth for it.
- `billing.payment`: `id`, `tenant_id`, `bill_id`, `mode` (check CASH/UPI/CARD), `amount_paise` (negative for a refund), `reference` (nullable — card slip or UPI ref), `recorded_by`, `recorded_at`.
- `billing.day_closing`: `id`, `tenant_id`, `business_date`, `counted_cash_paise`, `expected_cash_paise`, `diff_paise`, `note`, `closed_by`, `closed_at`. Unique `(tenant_id, business_date)`.
- All tables FORCE RLS, fail-closed on `current_tenant()`, least-privilege grants, no delete (payments/refunds are both rows, never removed).
- Balance = consultation fee + sum(bill_item) − discount − sum(payments where amount > 0) + sum(|refunds|)... i.e. balance = total − (payments net of refunds). Compute server-side only; the client never sends a balance.
- Reaching ₹0 balance calls `opd.api`'s stage-advance to DONE in the same transaction as the payment that closes it.

## 4. API
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/billing/counter` | any clinic role | none | `{awaiting: BillSummary[], dues: BillSummary[], paidToday: BillSummary[]}` | 401 |
| GET | `/billing/bills/{visitId}` | any clinic role | none | `Bill` | 403, 404 |
| POST | `/billing/bills/{visitId}/items` | OWNER, FRONT_DESK | `{serviceId, qty}` | `Bill` | 400, 403, 404 |
| DELETE | `/billing/bills/{visitId}/items/{itemId}` | OWNER, FRONT_DESK | none | `Bill` | 403, 404 |
| PUT | `/billing/bills/{visitId}/discount` | OWNER, FRONT_DESK | `{percent, reason}` | `Bill` | 400, 403, 404 |
| POST | `/billing/bills/{visitId}/payments` | OWNER, FRONT_DESK | `{mode, amountPaise, reference}` | `Bill` | 400, 403, 404 |
| POST | `/billing/bills/{visitId}/refund` | OWNER | `{amountPaise, mode, reason}` | `Bill` | 400, 403, 404 |
| POST | `/billing/day-closing` | OWNER, FRONT_DESK | `{countedCashPaise, note}` | `DayClosing` | 400, 403, 409 (already closed) |

All paths under `/api/v1`. Contract: `docs/api/billing.yaml` (new file, written directly by the tech lead per DEC-007's 3-agent mode).

## 5. Tasks
### Tasks — Backend
- Migrations, repositories, `BillingService` (totals/balance math, discount-limit check, stage-advance on zero balance, day-closing math), controller, role gate, audit.
- Tests: add/remove item, discount over limit rejected, partial payment keeps it in Dues, full payment moves visit to DONE, refund owner-only, cross-tenant 404, non-owner read-only.

### Tasks — Frontend
- Route `/app/billing` behind the shell (menu key `billing` should exist from SAH-5; add if missing).
- Two-pane layout per the mockup: left list (Awaiting payment / Dues / Paid today), right compose panel (items with qty steppers, discount field with reason, totals, payment mode toggle with the amount field, collect button, receipt view once paid, refund button for the owner).
- Read-only view for roles that cannot collect, with the mockup's own message.
- Day-closing dialog (counted cash, note, shows expected and difference).
- Unit tests for totals/balance math, discount-limit gating, and the collect/refund flows (mock HTTP).

## 6. Out of scope, Open questions
Out of scope: Revenue/Reports screen (later story, needs real historical data), real payment gateway (Phase 1 has no integration gateway per CLAUDE.md), WhatsApp/PDF receipts, IPD billing, insurance/TPA, number series/invoice numbering beyond a simple per-tenant counter if needed later.
Open questions: none blocking; proceeding on the defaults above per DEC-008.

## 7. Status log
