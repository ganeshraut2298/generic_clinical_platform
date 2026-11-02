# SAH-10 · Billing counter — database design

Schema: `billing` (already created by `scripts/db/schemas.sql`, with default grants — no
schema-level migration needed, only the tables below).

Money follows the existing codebase convention: `bigint` paise (`opd.service.price_paise`,
`opd.visit.fee_paise`), not `numeric(12,2)`, for consistency through the visit → bill → payment
chain.

## Tables

### `billing.bill`
One row per visit once billing starts (an item added, a discount set, or a payment recorded) —
not one row per visit. Until that first write the bill is synthesized read-only by the service
layer from `opd.visit.fee_paise` with zero items/discount/payments (see the story's technical
design). The consultation line is never stored as a row; it is always derived live.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| id | uuid | no | `gen_random_uuid()` | PK |
| tenant_id | uuid | no | `platform.current_tenant()` | FK `platform.tenant(id)` |
| visit_id | uuid | no | — | FK `opd.visit(id)`; unique per tenant — one bill per visit |
| discount_percent | integer | no | 0 | `0–100` |
| discount_reason | text | yes | null | ≤400 chars |
| created_at/by, updated_at/by, version | — | — | — | standard audit + optimistic lock |

**Indexes:** `uq_bill_tenant_visit (tenant_id, visit_id)` unique — one bill per visit, and the
lookup `GET /billing/bills/{visitId}` uses. `ix_bill_tenant_visit` (duplicate-serving index kept
for clarity in query plans; the unique index already covers it).

**RLS:** ENABLE + FORCE, policy `p_bill_tenant` (`for all`) on
`tenant_id = platform.current_tenant()`, fail-closed.

**Grants:** `select, insert, update` to `sahaj_app`. **No delete** — once a bill exists it stays,
even if every item is later removed; it is the anchor row items and payments hang off.

### `billing.bill_item`
Extra service lines added to a bill beyond the always-derived consultation line. `name` and
`amount_paise` are a **snapshot** taken when the item is added, so a later rename or price change
on `opd.service` never changes an existing bill's history.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| id | uuid | no | `gen_random_uuid()` | PK |
| tenant_id | uuid | no | `platform.current_tenant()` | FK `platform.tenant(id)` |
| bill_id | uuid | no | — | FK `billing.bill(id)` |
| service_id | uuid | yes | null | FK `opd.service(id)`, nullable |
| name | text | no | — | 1–160 chars, snapshot |
| amount_paise | bigint | no | — | `>= 0`, snapshot |
| qty | integer | no | 1 | `1–20` |
| created_at/by | — | — | — | append fields only (no update/version — see decision below) |

**Index:** `ix_bill_item_tenant_bill (tenant_id, bill_id)` — loading a bill's items.

**RLS:** ENABLE + FORCE, policy `p_bill_item_tenant` (`for all`) on tenant.

**Delete decision (this story's "your call"):** `bill_item` **does get a delete grant**
(`select, insert, update, delete`). Rationale: it is working/compose-time data, not itself the
financial record. The story's AC2 ("remove any item except the consultation line") and the API
contract's real `DELETE /bills/{visitId}/items/{itemId}` both expect a hard removal while a bill
is still being put together. The record that must never be edited or removed is `billing.payment`
(below) — once money is collected, receipts/audits are built from `payment` rows, not from which
`bill_item` rows happened to exist at that moment. **java-developer:** feel free to call the
repository's delete on `bill_item`; do not build a soft-delete/`active` flag for it, there is no
column for one.

### `billing.payment`
The financial record. **Append-only**, same pattern as `platform.audit_event`: `select, insert`
only, plus a `before update or delete` / `before truncate` trigger reusing
`platform.deny_audit_mutation()` so no later privilege grant can make it mutable by accident. A
refund is its own row with a **negative** `amount_paise` — never an edit to a prior payment.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| id | uuid | no | `gen_random_uuid()` | PK |
| tenant_id | uuid | no | `platform.current_tenant()` | FK `platform.tenant(id)` |
| bill_id | uuid | no | — | FK `billing.bill(id)` |
| mode | text | no | — | `CASH`, `UPI`, `CARD` |
| amount_paise | bigint | no | — | `<> 0`; negative = refund |
| reference | text | yes | null | ≤120 chars; card slip/UPI ref |
| recorded_by | uuid | no | — | |
| recorded_at | timestamptz | no | `now()` | |

**Indexes:** `ix_payment_tenant_bill (tenant_id, bill_id)` — a bill's payment history/receipt.
`ix_payment_tenant_recorded_at (tenant_id, recorded_at)` — "Paid today" list and day-closing's
expected-cash sum (today's cash payments minus cash refunds).

**RLS:** ENABLE + FORCE, separate `select` and `insert` policies only (no `update`/`delete`
policy exists at all, so those commands are denied under RLS even before the grant/trigger layers
are considered — same three-layer defence as `platform.audit_event`).

**Grants:** `select, insert` only. **Never update or delete.**

### `billing.day_closing`
One row per tenant per business day, written once at end of day.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| id | uuid | no | `gen_random_uuid()` | PK |
| tenant_id | uuid | no | `platform.current_tenant()` | FK `platform.tenant(id)` |
| business_date | date | no | — | unique with tenant_id |
| counted_cash_paise | bigint | no | — | `>= 0` |
| expected_cash_paise | bigint | no | — | computed server-side |
| diff_paise | bigint | no | — | computed server-side |
| note | text | yes | null | ≤400 chars |
| closed_by | uuid | no | — | |
| closed_at | timestamptz | no | `now()` | |

**Index:** `uq_day_closing_tenant_date (tenant_id, business_date)` unique — "one closing per
clinic per day" (AC9); the service maps its unique-violation to the API's `409 (already closed)`.

**RLS:** ENABLE + FORCE, separate `select`/`insert` policies, same shape as `billing.payment`.

**Grants:** `select, insert` only — no update/delete grant (not a trigger-enforced table like
`payment`, because a correction here is "close again tomorrow with a note", not an in-place edit;
the privilege layer alone is enough since there is no legitimate same-day re-close path at all —
the unique constraint already blocks a second row for the same day).

## Cross-module references
`billing.bill.visit_id` → `opd.visit(id)`, `billing.bill_item.service_id` → `opd.service(id)`.
Both are the kind of cross-module FK `postgresql.md` restricts (only `platform.tenant` and
`patient.patient` are named there); `opd.visit`/`opd.service` are accepted here the same way
SAH-9 accepted `patient.patient` — `billing` depends one-direction on `opd.api`, never the
reverse, matching the module-dependency rule in the story's technical design. No FK to
`patient.patient` is needed in `billing` itself: the bill carries patient info only by reading
through `opd.api` → `patient.api`, not by storing a `patient_id` here.

## Expected rows, retention
`bill`: at most one row per visit that reaches billing — same order of magnitude as `opd.visit`.
`bill_item`: a handful per bill. `payment`: one or more per bill (partial payments, a refund).
`day_closing`: one row per tenant per calendar day the clinic is open, ~365/year. All well under
partitioning thresholds even at 10,000 clinics × 100 hospitals.

Retention: `bill` and `bill_item` are never deleted once created (only `bill_item` rows allow a
hard delete, pre-payment, as documented above). `payment` and `day_closing` are **never** deleted
or updated — they are the financial/audit record, retained indefinitely.

## Backward compatibility
New tables only (`billing` schema was empty before this story); no existing migration was edited.
A previous app version that does not know about `billing.*` is unaffected.
