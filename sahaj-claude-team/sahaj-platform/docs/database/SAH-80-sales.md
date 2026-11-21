# SAH-80 · Pharmacy sales and dispensing · database design (WP-I to WP-N)

Story: SAH-80 (sub-stories 80-1 to 80-23). ADR-030 (S1 to S14, "Data needed"), DEC-074, contract `docs/api/pharmacy-sales.yaml` 0.3.1 and `pharmacy.yaml` 0.3.x. Programme DEC-061. Author: db-engineer. Not run against a live database; every statement was checked by hand against PostgreSQL 16 and the patterns of 1330, 1360, 1400 to 1700, 1720, 1730, 1740. The tech lead or QA runs `node scripts/validate.mjs db`.

## 0. Safety summary
- 17 new migration files, `V2026_12_17_1800` to `V2026_12_17_2300`. No applied migration (1000 to 1750) is edited. Forward only, every table additive.
- Two functions are replaced, each from its latest body with only the stated change: `pharmacy.next_document_no(text)` (from 1720) and `pharmacy.guard_stock_ledger_schedule_x()` (from 1730). Both are in the table of SAH-78-pharmacy-masters.md section 11 (winner 1800).
- Backward compatibility: the previous app version runs against the new schema (new tables unused, new unit columns have defaults or are null, wider checks). The new app needs all files.
- Flyway runs them in one start; `set local lock_timeout = '30s'` in each file.

## 1. Migration order

| File | WP | Content |
| --- | --- | --- |
| `1800__pharmacy_sale_touch_ups` | I | CASHIER role; unit columns; document types; `next_document_no`; nine reason lists (34 rows); ledger types; Schedule X guard list |
| `1810__pharmacy_store_bill_series` | I | `store_bill_series`, `store_bill_counter`, `next_store_bill_no(uuid)`, counter guard |
| `1820__pharmacy_customer` | I | `customer`, guard |
| `1830__pharmacy_held_bill` | I | `held_bill`, guard |
| `1840__pharmacy_bill` | I | `bill`, `bill_line`, `bill_payment`, two guards |
| `1850__pharmacy_credit_note_refund_account_entry` | I | `credit_note`, `refund`, `customer_account_entry` |
| `1900__pharmacy_sale_discount_request` | J | subject types; `sale_discount_request`; two foreign keys left open by I |
| `1910__pharmacy_h1_register_row` | J | H1 register |
| `1920__pharmacy_schedule_x_register_row` | J | Schedule X sale rows |
| `2000__pharmacy_sales_return` | L | `sales_return`, `sales_return_line`, guards; three foreign keys left open by I |
| `2010__pharmacy_pharmacist_registration_activity_index` | L | `pharmacist_registration`; activity feed index on `document_event` |
| `2100__pharmacy_rx_dispense` | K | `rx_dispense`, `rx_dispense_line`, guards |
| `2110__pharmacy_product_link` | K | `product_link`, `product_link_history` |
| `2200__pharmacy_ipd_order_supply_issue` | M | `ipd_order_supply`, `ipd_issue`, `ipd_issue_line` |
| `2210__pharmacy_ward_return_discharge_clearance` | M | `ward_return`, `discharge_clearance` |
| `2220__pharmacy_consumption_charge_columns` | M | `consumption` charge columns, update grant, guard |
| `2300__pharmacy_day_close` | N | `day_close` |
| `2310__pharmacy_bill_discount_request_unique` | DEC-091 | unique partial index `uq_bill_discount_request` on `bill` |

(Full file names: `V2026_12_17_<number>__...sql`.) Build order for Java follows ADR-030: I, J, L, K, M, N. Flyway order is the ADR order I, J, L (20xx), K (21xx), M, N.

## 2. Conventions (as SAH-79) and deviations
Conventions kept: uuid pk; `tenant_id` default `platform.current_tenant()` with FK to `platform.tenant`; `unit_id` with composite FK `(tenant_id, unit_id)`; every other FK composite on `tenant_id`; RLS enabled and forced, policies `to sahaj_app` on `platform.current_tenant()`; no delete grant; `tenant_id` first in every index; trigger functions `set search_path = pg_catalog`, execute revoked from public and `sahaj_app`; paise `bigint`; actor and signer columns hold membership ids (opaque uuid, no FK, L13); append-only tables use `pharmacy.deny_append_only_mutation()` for update, delete and truncate; patient and source ids from other modules are plain uuid (no cross-module FK).

Deviations and choices where ADR-030 is silent (conservative, additive):
1. **`pharmacy.credit_note` table added** (1850). The ADR keeps the CREDIT_NOTE number on `refund`, `bill.refund_credit_note_no` and `sales_return`. Two sources issue the number (cancel refund, sales return), so one append-only table gives a unique, gapless-checkable number and the foreign target of `refund` and `sales_return`. `sales_return.credit_note_no` is a **deferrable initially deferred** FK, so a return can be inserted COMPLETED and its credit note row written after it in one transaction.
2. **No separate tables for allergy override, substitution or discount approval link beyond `sale_discount_request`**: ADR-030 S3 and S9 keep them on `bill_line` (columns `substitution_reason_key`, `patient_agreed`, `allergy_override_key/note`, `h1_override_key/note`, `override_decided_by`).
3. **`product.ndps`**: already a column (1100); nothing to add. "Writable for own products" is a service rule.
4. **`document_event.event`** has no check (pattern only), so no widening. One index added for the activity feed (see 4.12).
5. **Bill line has `line_no`** and one row per batch; unique `(tenant_id, bill_id, line_no)`.
6. **`sales_return.refunds` jsonb array** (max 5, `[ {mode, amountPaise} ]`) holds the chosen refund until completion; real rows go to `refund` at completion. No separate plan table.
7. **`next_document_no` default restart** is now true for `CREDIT_NOTE` as well as `BILL` (ADR-030 S3 default CN/26-27/000001 restarts). A saved `document_series` row still decides.
8. **WARD_RETURN_WASTAGE and COUNTER_SALE_CANCELLED are on the Schedule X signer list** (all six SAH-80 types), because each is a person handling stock (DEC-081 c). If the architect wants wastage off the list, change the array in one file.
9. **Bill identity checks** (`net = gross - discount + round off`; `taxable + CGST + SGST = gross - discount`; per line `n = a - d`, `t + x = n`) are database checks. They hold by the S4 maths; if the accountant changes the rounding rule (O-2, O-4, G-22) these checks must change with it.
10. **`ipd_issue_line` partial index** is `(tenant_id, created_at) where charge_status = 'CHARGE_PENDING'` (oldest first) instead of `(tenant_id, charge_status)` where that column is constant.

## 3. Touch-ups on existing objects (1800)
- `store_staff.store_role`: `ck_store_staff_role` = STORE_USER, CENTRAL_STORE_USER, APPROVER, DELEGATE, **CASHIER**. Which team roles may hold CASHIER and "only at an ACTIVE sale-allowed non-Central store" stay service rules.
- `unit`: `return_window_days integer null` (1 to 365), `refund_modes text[] not null default '{}'` (subset of ORIGINAL_MODE, CASH, CREDIT_NOTE, UDHAR; at most 4), `schedule_x_licence_no varchar(40)`, `schedule_x_licence_valid_till date`. The unit update policy and grants are unchanged.
- `document_series` and `document_counter` doc types: + CREDIT_NOTE, IPD_ISSUE_SLIP (twelve). `next_document_no` accepts the twelve.
- `reason_default` and `reason` list checks: + `substitution`, `allergy-override`, `h1-override`, `return-condition`, `cancel-bill`, `ward-return`, `rx-closed`, `clearance`, `day-close-difference`. 34 global seed rows. `ck_reason_default_state_fixed` now also keeps `return-condition.sealed`, `.opened`, `.damaged` switched on (and `receipt-reject.short`).
- `stock_ledger.txn_type`: + COUNTER_SALE, COUNTER_SALE_CANCELLED, SALES_RETURN, IPD_ISSUE, WARD_RETURN_ACCEPTED, WARD_RETURN_WASTAGE (30 values).
- `guard_stock_ledger_schedule_x()` array: 17 + 6 = 23 types.
- `approval_request.subject_type` (1900): + SALE_DISCOUNT, SALES_RETURN (`transaction_type` already had them in 1360).
- `consumption` (2220): `charge_ref`, `charged_at`, `charge_failure`; CHARGED allowed for PATIENT entries; column-level `update` grant on those columns only; guard allows only CHARGE_PENDING to CHARGED.

## 4. Tables

### 4.1 `store_bill_series`, `store_bill_counter` (1810)
`store_bill_series`: prefix `^[A-Za-z0-9/-]{1,6}$`, `financial_year_part`, `sequence_length` 4 to 8, `restarts_each_fy` (needs FY part), rendered length <= 16; unique `(tenant_id, store_id)`; unique index `(tenant_id, unit_id, lower(prefix))` (store series apart from each other; the check against the unit BILL prefix is a service check under the lock of the unit's BILL `document_series` row). Select, insert, update. `store_bill_counter`: pk `(tenant_id, unit_id, store_id, fiscal_year)`, `next_value >= 2`, `updated_at`, `last_issued_at` (series lock, DEC-078 item 4). Guard trigger: key never changes, `next_value` strictly up. `next_store_bill_no(store_id)` returns the sequence number; SECURITY INVOKER; reads the unit row `FOR SHARE`; raises `no_data_found` (P0002) when the store has no own series (use `next_document_no('BILL')`), 22023 for a null store, 42501 without tenant.

### 4.2 `customer` (1820)
Kinds WALK_IN (name required; patient id null) and PATIENT (patient id only; name, phone, allergy note null). `credit_limit_paise`, `due_paise >= 0`, `credit_note_balance_paise >= 0`, status ACTIVE/INACTIVE, version. Unique `(tenant_id, unit_id, patient_id) where patient_id is not null`. Indexes: `(tenant_id, unit_id, phone) where phone is not null` (picker by mobile), `(tenant_id, unit_id, lower(name))` (picker by name), `(tenant_id, unit_id, due_paise desc) where due_paise > 0` (udhar list). Guard: tenant, unit, kind, patient id never change.

### 4.3 `held_bill` (1830)
`cart jsonb` (object), `cart_schema_version`, status OPEN/RESUMED/REPLACED/DISCARDED, holder, `discount_request_id`, `held_net_paise`. Index `(tenant_id, store_id, status)` serves the list and the count of OPEN under the store lock (limit 20 is a service check). Guard: starts OPEN; identity fixed; a non-OPEN row is final.

### 4.4 `bill`, `bill_line`, `bill_payment` (1840)
`bill`: `bill_no varchar(16)`, `series_kind` UNIT/STORE, `fiscal_year`, status SAVED/CANCELLED, buyer kind PATIENT/CUSTOMER/WALK_IN/NONE with shape check, `link_pending` (only for WALK_IN), `allergy_not_checked`, prescription fields, `held_bill_id`, `discount_percent`, `discount_request_id`, totals `gross, discount, taxable, cgst, sgst, round_off (-50..49), net`, `udhar_paise`, `saved_at/by`, `pharmacist_membership_id`, cancel fields (`back_on_shelf`), `refund_due_paise`, `refund_credit_note_no`. Unique `(tenant_id, unit_id, lower(bill_no))`. Indexes: `(tenant_id, store_id, saved_at desc)` (today's bills, GST register, command centre); `(tenant_id, customer_id, saved_at desc)`; partial `link_pending` (mode guard); partial refund due; partial held bill; partial discount request. Guard `guard_bill_update`: final at save except status SAVED to CANCELLED with cancel fields, `refund_credit_note_no` once on a cancelled bill, one-time link WALK_IN(link pending) to PATIENT, version and stamps.
`bill_line`: one row per batch; `returned_qty` grows to `qty` (guard); MRP numeric(14,4) rupees, `gst_rate` numeric(5,2), hsn, a/d/n/t/x paise; substitution, allergy and H1 override keys and notes, `override_decided_by`, `actor_membership_id`, `second_signer_membership_id` (`<> actor`), `rx_line_key`. Unique `(tenant_id, bill_id, line_no)`; indexes by batch and product.
`bill_payment`: append-only; unique `(tenant_id, bill_id, mode)`; `store_id`, `actor_membership_id`, `paid_at`; index `(tenant_id, store_id, actor_membership_id, paid_at)` (day close).

### 4.5 `credit_note`, `refund`, `customer_account_entry` (1850)
All append-only. `credit_note`: number varchar(24) unique `(tenant_id, credit_note_no)` and unique lower-case index; source CANCELLED_BILL or SALES_RETURN (shape check with `sales_return_id`), amount > 0. `refund`: mode CASH/UPI/CARD/CREDIT_NOTE/UDHAR, amount > 0, FK to `credit_note (tenant_id, credit_note_no)`, unique `(tenant_id, source_kind, bill_id, credit_note_no, mode)`; index by bill and by `(tenant_id, store_id, actor_membership_id, refunded_at)`. `customer_account_entry`: kind CREDIT_BILL, PAYMENT, CANCELLED_BILL, RETURN_TO_UDHAR, CREDIT_NOTE_ISSUED, CREDIT_NOTE_USED; `amount > 0`, `due_after_paise >= 0`, `cn_balance_after_paise >= 0`; mode only for PAYMENT; bill required for CREDIT_BILL, CANCELLED_BILL, CREDIT_NOTE_USED; indexes `(tenant_id, customer_id, occurred_at desc)` and `(tenant_id, store_id, actor_membership_id, occurred_at)`.

### 4.6 `sale_discount_request` (1900)
Append-only link of an engine request: `approval_request_id` (unique, FK), `cart_digest`, `discount_percent`, `discount_paise`, `held_bill_id`, `requested_by`. VOID is computed. FKs from `held_bill.discount_request_id` and `bill.discount_request_id` are added here.

**Single-use backstop (2310, DEC-091 item 2, review Major 2).** `uq_bill_discount_request` is a unique partial index on `bill (tenant_id, discount_request_id) where discount_request_id is not null`: one saved bill per discount request. A second bill on the same request fails with SQLSTATE 23505 (`uq_bill_discount_request`); the java-developer maps it to the same problem as the service check (discount already used). Held bills are not covered (a request may sit on a held bill and then on its bill). Cancelled bills still count: cancelling does not free the approval. The migration stops with a message if duplicates already exist (none expected, sales are not live); the table is empty or tiny, so a plain `create unique index` is safe. The older non-unique `ix_bill_discount_request` (1900) is now redundant; drop it in a later release. Additive; the previous app version still runs.

**Approval time for the IST-day rule.** No column added. The time of approval is `approval_decision.decided_at` (timestamptz, `clock_timestamp()`, append-only) of the decision with `decision = 'APPROVED'` at the final level of a request whose `approval_request.status = 'APPROVED'`; use the maximum `decided_at` of its APPROVED decisions (unique `(tenant_id, request_id, level_no)` serves the lookup). `approval_request` itself has no decided_at; `updated_at` is not a decision time, do not use it. The service compares `(decided_at at time zone 'Asia/Kolkata')::date` with today's IST date.

### 4.7 `h1_register_row` (1910) and `schedule_x_register_row` (1920)
Append-only. H1: ENTRY (qty > 0) or REVERSAL (qty < 0, `reverses_row_id`, reason CANCELLED or RETURNED, credit note number when RETURNED); patient name, age (0 to 130), address, prescriber, registration number, prescription date, Rx source, drug, prescribed product, batch, pharmacist, override key (a missing prescriber needs a pharmacist override key, check). Indexes `(tenant_id, store_id, occurred_at desc)`, `(tenant_id, product_id, occurred_at)`, bill, reverses. Schedule X: source type BILL/IPD_ISSUE/WARD_RETURN/SALES_RETURN with id, number and line id (no FK), prescriber and prescription date required (no override), `second_signer <> first_signer`, same ENTRY/REVERSAL shape; indexes by store time, product time, source, reverses.

### 4.8 `sales_return`, `sales_return_line` (2000)
Status WAITING_PHARMACIST, WAITING_APPROVAL, APPROVED, COMPLETED, REJECTED (contract). COMPLETED exactly when `credit_note_no`, `completed_by`, `completed_at` are set. Partial unique `(tenant_id, credit_note_no)`. Indexes: store newest first, by bill, open returns partial, completed by date, approval. Guard moves: WAITING_PHARMACIST to WAITING_APPROVAL, COMPLETED, REJECTED; WAITING_APPROVAL to APPROVED, REJECTED, COMPLETED; APPROVED to COMPLETED; start only WAITING_* or COMPLETED. Line: decision ACCEPT/ACCEPT_QUARANTINE/REFUSE, destination AVAILABLE/QUARANTINE/REFUSED/PENDING, refund with `taxable + tax = refund`; unique per bill line; the line is frozen once the return is COMPLETED or REJECTED (update lines **before** the return completes). Foreign keys from `credit_note`, `refund`, `customer_account_entry` to `sales_return` are added here.

### 4.9 `pharmacist_registration` (2010)
Unique `(tenant_id, unit_id, membership_id)`, number varchar(40), `valid_till`. Select, insert, update.

### 4.10 `rx_dispense`, `rx_dispense_line`, `product_link`, `product_link_history` (2100, 2110)
`rx_dispense`: unique `(tenant_id, store_id, rx_record_id)` and `(tenant_id, store_id, token_day, token)`; status NEW, PICKING, PICKED, SUBSTITUTE_OK, NEEDS_ACTION, PART_SUPPLIED, HANDED_OVER, CLOSED (HANDED_OVER and CLOSED final); claim pair check; open partial index. `rx_dispense_line`: unique `(tenant_id, rx_dispense_id, line_key)`; `supplied_qty <= qty`. `product_link`: unique `(tenant_id, unit_id, source_kind, source_item_id)`; source never changes; `product_link_history` append-only.

### 4.11 `ipd_order_supply`, `ipd_issue`, `ipd_issue_line`, `ward_return`, `discharge_clearance` (2200, 2210)
`ipd_order_supply` unique `(tenant_id, store_id, order_id, order_version)` (the row the dispense locks). `ipd_issue` append-only with unique lower slip number and a patient name snapshot. `ipd_issue_line` charge states CHARGED, CHARGE_PENDING, CREDITED, CREDIT_PENDING, NOT_APPLICABLE; guard: only the charge columns and `returned_qty` (upwards) change; partial index of CHARGE_PENDING. `ward_return` statuses WAITING, ACCEPTED_AVAILABLE, ACCEPTED_QUARANTINE, WASTAGE, NOT_ACCEPTED; column `return_condition` (SEALED, OPENED, EMPTY_AMPOULE); the decision is made once; credit state changes after. `discharge_clearance` unique per admission, append-only.

### 4.12 `day_close` (2300) and the activity index (2010)
`day_close`: append-only; expected, counted and difference (check `diff = counted - expected`) per CASH, UPI, CARD; credit note used and udhar given; reason key and note per mode; unique `(tenant_id, store_id, person_membership_id, period_to)`; index by store and `closed_at desc`. `ix_document_event_store_time (tenant_id, store_id, occurred_at desc)`: the activity feed reads one store's events in time order, which the existing `(tenant_id, doc_type, doc_id, occurred_at)` cannot serve (structural reason).

## 5. Tenant scope, RLS and grants
Every table above is tenant scoped, RLS enabled and forced, policies to `sahaj_app` on `platform.current_tenant()`. Grants: select and insert for append-only tables (`bill_payment`, `credit_note`, `refund`, `customer_account_entry`, `sale_discount_request`, `h1_register_row`, `schedule_x_register_row`, `product_link_history`, `ipd_issue`, `discharge_clearance`, `day_close`); select, insert, update (guarded) for `store_bill_series`, `store_bill_counter`, `customer`, `held_bill`, `bill`, `bill_line`, `sales_return`, `sales_return_line`, `pharmacist_registration`, `rx_dispense`, `rx_dispense_line`, `product_link`, `ipd_order_supply`, `ipd_issue_line`, `ward_return`; `consumption` gets update on four columns. **No delete grant anywhere.** `next_store_bill_no` and `next_document_no`: execute to `sahaj_app`. All guard functions: execute revoked.

## 6. Trigger and check messages (SQLSTATE 23514 unless stated; append-only 42501)
- Append-only tables: `<table> is append-only (<UPDATE|DELETE|TRUNCATE> refused)` (42501).
- Counters: `store bill counter key cannot change`, `store bill counter can only go up`.
- Customer: `customer identity cannot change`.
- Held bill: `held bill must start open`, `held bill identity cannot change`, `held bill is final (%)`.
- Bill: `bill is final once saved`, `bill cannot go from % to %`, `cancelled bill cannot change`, `bill cancel fields change only with the cancel`, `bill refund credit note is set once on a cancelled bill`, `bill buyer can only be linked to a patient once`.
- Bill line: `bill line must start with nothing returned`, `bill line is final once saved; only the returned quantity changes`, `bill line returned quantity cannot go down`.
- Sales return: `sales return cannot start as %`, `sales return identity cannot change`, `sales return is final (%)`, `sales return cannot go from % to %`, `sales return acceptance is set once`; line: `sales return line identity cannot change`, `sales return line is final (%)`.
- Rx dispense: `prescription dispense identity cannot change`, `prescription dispense is final (%)`, `prescription dispense line identity cannot change`.
- Product link: `product link source cannot change`.
- IPD: `ipd order supply identity cannot change`, `ipd issue line must start with nothing returned`, `ipd issue line is final once saved; only the charge state and the returned quantity change`, `ipd issue line returned quantity cannot go down`, `ward return must start waiting`, `ward return facts cannot change`, `ward return is decided (%)`.
- Consumption: `consumption entry is final; only the charge state changes`, `consumption charge cannot go from % to %`, `consumption charge is final`.
- Schedule X ledger: `schedule X movement needs a second signer` (unchanged text).
- `next_store_bill_no`: `store is required` (22023), `no tenant bound` (42501), `pharmacy unit is not set up for this tenant` (P0002), `store has no own bill series` (P0002).
- Check names the service maps: `ck_bill_total_identity`, `ck_bill_tax_identity`, `ck_bill_udhar_within_net`, `ck_bill_line_signer_differs`, `ck_schedule_x_register_row_signers_differ`, `ck_customer_due`, `ck_customer_cn_balance`, `ck_sales_return_completed_shape`, `ck_day_close_difference`, `ck_unit_refund_modes`, `ck_unit_return_window_days`.

## 7. Lock order for the java-developer (ADR-029 P5 steps, with SAH-80 tables)
1. `client_request` replay check. 2. `approval_request` (discount) `FOR SHARE`. 3. Document rows `FOR UPDATE`, in this order: `held_bill`, `rx_dispense` (claim) or `ipd_order_supply`, `customer` (udhar and credit note). The store row `FOR UPDATE` is taken first only for the held-bill limit and the OPD token. 5 to 7. `batch` and **every** `stock_balance` row of each `(store, product)` in canonical order (through `StockPosting`). 8. **The number, last**: `next_store_bill_no(store)` or `next_document_no('BILL')`, `next_document_no('CREDIT_NOTE')`, `next_document_no('IPD_ISSUE_SLIP')`, immediately before the inserts (ADR-030 S3). Then inserts: `bill`, `bill_line`, `bill_payment`, ledger lines, `credit_note` (before the `refund` rows), `customer_account_entry`, register rows, `document_event`.
Sales return completion: update `sales_return_line` **before** setting the return COMPLETED (the line guard refuses updates once final). Cancel and return update `customer` (due, balance) in the same transaction as the account entry. `bill_line.returned_qty` goes up in the same transaction as the return ledger lines. Charge posting (`ipd_issue_line`, `consumption`, `ward_return`) updates only the charge columns.
The customer row lock comes before the balance locks; the counters come after them. Counters are taken only by sale-side transactions (bill save, cancel refund, sales return, IPD issue), so no cycle exists with Phase 2.

## 8. Volumes, partitioning, retention
Per tenant per year (a clinic pharmacy with about 100 bills a day; a hospital pharmacy about 1,500): `bill` 36,000 to 550,000; `bill_line` 2 to 3 times that; `bill_payment` 1.2 times bills; `held_bill` same order as 1 in 5 bills; `customer` thousands; `customer_account_entry` about 10 percent of bills; `h1_register_row` about 5 percent of lines; `schedule_x_register_row` under 1 percent; `sales_return` about 2 percent of bills; `ipd_issue_line` hospital only, about 200 to 800 a day; `day_close` stores times staff times 365. Platform-wide at 10,000 clinics: `bill` about 360 million a year, which is **above 50 million**. Partitioning is not done in this release (the same open point as `stock_ledger` growth, ADR-029 P15): `bill` and `bill_line` should be range-partitioned by `saved_at` (monthly) before the platform total passes 50 million rows. A later migration can convert it with expand and contract; the bill number and the ids are not tied to the partition key now. Flagged as a risk for the tech lead and architect (open point D-1 below).
Retention: nothing is ever deleted. Registers (H1, Schedule X), bills, credit notes, ledger lines, refunds, day close are kept until the human sets legal periods (G-11, G-23, G-29). Patient data lives in `customer`, `bill`, `held_bill` (cart), `h1_register_row`, `schedule_x_register_row`, `ipd_issue` (name snapshot): tenant isolated, never logged, never in the ledger or audit text.

## 9. Tests for QA (belong to these migrations)
1. Every new table: RLS forced, a second tenant sees nothing, insert for another tenant refused, no delete right (`delete` fails with 42501), tenant_id first in every index.
2. Append-only tables refuse update, delete and truncate (42501); the guarded tables refuse what the guard lists and allow what it allows (bill cancel once, link once; bill line `returned_qty` up only; sales return moves; ward return decided once; consumption CHARGE_PENDING to CHARGED only).
3. Bill identity checks: a bill with `net <> gross - discount + round_off` and with `taxable + cgst + sgst <> gross - discount` is refused; the worked example of S4 (A 56,040; D 2,802; N 53,238; t 47,534; x 5,704; CGST 2,852; SGST 2,852; round off -38; net 53,200) inserts.
4. `next_store_bill_no`: gapless on rollback; two sessions serialise on the counter; no own series raises P0002; April restart creates a new counter row; counter cannot go down (direct update refused).
5. `next_document_no('CREDIT_NOTE')` and `('IPD_ISSUE_SLIP')` work; the default restart for CREDIT_NOTE is true; an unknown type still raises 22023; `last_issued_at` still set; the unit row is read FOR SHARE (guardrail).
6. Ledger: the six new types insert; a USER line with `COUNTER_SALE` for a Schedule X product without a second signer is refused (23514); with a signer it inserts; an OPENING_STOCK line still passes.
7. Reason seed: 34 new `reason_default` rows in nine lists (total 70); the three `return-condition` keys cannot be switched off (`reason_default_state` active false refused).
8. `store_staff` accepts CASHIER; `unit.refund_modes` refuses an unknown mode; `return_window_days` 0 and 366 refused.
9. `sales_return` COMPLETED needs a credit note number; the deferred FK fails at commit for a number that has no `credit_note` row; one credit note number per return; `refund.credit_note_no` must exist.
10. H1 and Schedule X rows: a REVERSAL with positive quantity refused; `second_signer = first_signer` refused; an H1 ENTRY with no prescriber and no override key refused.
11. `ipd_issue_line` and `ward_return`: charge states; the partial indexes exist; `ward_return` WAITING with a decider refused.
12. `MigrationFunctionGuardrailTest` entries (section 10).

## 10. Repeated functions and guardrail entries
| Function | Files | Winner | Guardrail snippets |
| --- | --- | --- | --- |
| `pharmacy.next_document_no` | 1220, 1280, 1380, 1710, 1720, 1800 | 1800 | `last_issued_at`, `for share`, `CREDIT_NOTE` |
| `pharmacy.guard_stock_ledger_schedule_x` | 1720, 1730, 1800 | 1800 | `second signer`, `WARD_RETURN_WASTAGE` |
Change the winner prefix of the two existing entries in `MigrationFunctionGuardrailTest` from 1720 and 1730 to `V2026_12_17_1800`. All other new functions are created once.

## 11. Index to-do (WP-N, not created: no EXPLAIN yet)
Command centre cards and the six reports are served by the indexes above: `bill (tenant_id, store_id, saved_at desc)`, `sales_return (tenant_id, store_id, started_at / completed_at)`, `h1_register_row` and `schedule_x_register_row` by store time and by product time, `stock_ledger` and balance indexes of SAH-79, `document_event` by store time. After QA loads a realistic set, run EXPLAIN (ANALYZE, BUFFERS) on: product search (ADR-030 S13: `pharmacy.product` search text, 300 ms; if a plain scan misses it, `pg_trgm` is a human decision, O-18), the `sales-margin` report (join `bill_line` to `batch` for cost), the GST register by date range, the udhar ageing, and the activity feed. Candidate: `bill_line (tenant_id, product_id)` is present; a covering index on `bill_payment (tenant_id, bill_id)` is served by the unique key. Add an index only with the plan attached.

## 12. Open points and risks
- D-1 Partitioning of `bill` and `bill_line` by `saved_at` is not done (platform volume above 50 million rows a year at the 10,000 clinic target). Decide before go-live scale; additive plan in section 8.
- D-2 Accountant checks on the S4 identity checks (O-2, O-4, O-5, G-22).
- D-3 `bill.bill_no` is `varchar(16)` (the series rule); a credit note number allows 24 characters.
- D-4 Whether WARD_RETURN_WASTAGE belongs on the Schedule X signer list (deviation 8).
- D-5 An H1 `patient_address` is stored on every H1 line row (O-15); the human and the lawyer decide retention (G-23).

## Change log
- 19 Nov 2026: WP-I to WP-N written (17 files, `V2026_12_17_1800` to `2300`). Replaced `next_document_no` and `guard_stock_ledger_schedule_x`; rows of SAH-78-pharmacy-masters.md section 11 updated. No applied migration edited. Not run against a live database; `validate.mjs db` is run by the tech lead or QA.
- 21 Nov 2026: `V2026_12_17_2310` added (unique partial index `uq_bill_discount_request`, DEC-091 item 2). No applied migration edited. Not run against a database.
