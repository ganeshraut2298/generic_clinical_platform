-- SAH-10 · Billing counter: bill, bill_item, payment, day_closing.
-- Schema billing already exists (scripts/db/schemas.sql) with default grants for sahaj_app.
-- Money is stored as bigint paise throughout this codebase (opd.service.price_paise,
-- opd.visit.fee_paise) rather than numeric(12,2); this migration keeps that existing convention
-- for consistency across the visit -> bill -> payment chain.

-- billing.bill : one row per visit that has had its first billing write (an item, a discount or a
-- payment). Until that first write the bill is synthesized read-only in the service layer from the
-- visit's own fee_paise with zero items/discount/payments (see SAH-10 technical design) - there is
-- no "empty bill" row for every visit, only for ones actually touched by billing.
create table if not exists billing.bill (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null default platform.current_tenant()
                    constraint fk_bill_tenant references platform.tenant (id),
  visit_id          uuid not null
                    constraint fk_bill_visit references opd.visit (id),
  discount_percent  integer not null default 0
                    constraint ck_bill_discount_percent check (discount_percent between 0 and 100),
  discount_reason   text
                    constraint ck_bill_discount_reason check (discount_reason is null or char_length(discount_reason) <= 400),
  created_at        timestamptz not null default now(),
  created_by        uuid not null,
  updated_at        timestamptz,
  updated_by        uuid,
  version           bigint not null default 0,
  -- One bill per visit (story: "created on first write").
  constraint uq_bill_tenant_visit unique (tenant_id, visit_id)
);

create index if not exists ix_bill_tenant_visit on billing.bill (tenant_id, visit_id);

alter table billing.bill enable row level security;
alter table billing.bill force row level security;

create policy p_bill_tenant on billing.bill
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

revoke all on billing.bill from sahaj_app;
revoke all on billing.bill from public;
-- No delete: once a bill exists (an item/discount/payment was recorded) it stays, even if every
-- item is later removed - it is the anchor row payments and items hang off.
grant select, insert, update on billing.bill to sahaj_app;

comment on table billing.bill is
  'One row per visit once billing starts (SAH-10). The consultation line itself is never stored here - it is derived live from opd.visit.fee_paise. Never deleted.';

-- billing.bill_item : extra service lines added to a bill, beyond the always-derived consultation
-- line. name/amount_paise are a snapshot taken at the time the item is added, so a later rename or
-- price change on opd.service does not change an existing bill's history.
--
-- Delete decision (documented per the story's "your call"): bill_item DOES get a delete grant.
-- It is working/compose-time data, not itself the financial record - the story's AC2 lets staff
-- "remove any item except the consultation line" while the bill is still being put together, and
-- the API contract (docs/api/billing.yaml) has a real DELETE /bills/{visitId}/items/{itemId}.
-- The append-only financial record that must never be edited or removed is billing.payment below;
-- a bill_item removed before payment leaves no trace to falsify, and once money has been collected
-- against a bill, billing.payment rows are what audits/receipts are built from, not bill_item rows.
create table if not exists billing.bill_item (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_bill_item_tenant references platform.tenant (id),
  bill_id       uuid not null
                constraint fk_bill_item_bill references billing.bill (id),
  service_id    uuid
                constraint fk_bill_item_service references opd.service (id),
  name          text not null
                constraint ck_bill_item_name check (char_length(btrim(name)) between 1 and 160),
  amount_paise  bigint not null
                constraint ck_bill_item_amount check (amount_paise >= 0),
  qty           integer not null default 1
                constraint ck_bill_item_qty check (qty between 1 and 20),
  created_at    timestamptz not null default now(),
  created_by    uuid not null
);

create index if not exists ix_bill_item_tenant_bill on billing.bill_item (tenant_id, bill_id);

alter table billing.bill_item enable row level security;
alter table billing.bill_item force row level security;

create policy p_bill_item_tenant on billing.bill_item
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

revoke all on billing.bill_item from sahaj_app;
revoke all on billing.bill_item from public;
grant select, insert, update, delete on billing.bill_item to sahaj_app;

comment on table billing.bill_item is
  'Extra service lines on a bill, beyond the always-derived consultation line (SAH-10). name/amount_paise are a price snapshot. Deletable by design while a bill is being composed; the true append-only financial record is billing.payment.';

-- billing.payment : the financial record. Append-only, same pattern as platform.audit_event -
-- select/insert only, no update, no delete, ever. A refund is its own row with a negative
-- amount_paise, never an edit to a prior payment.
create table if not exists billing.payment (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_payment_tenant references platform.tenant (id),
  bill_id       uuid not null
                constraint fk_payment_bill references billing.bill (id),
  mode          text not null
                constraint ck_payment_mode check (mode in ('CASH', 'UPI', 'CARD')),
  amount_paise  bigint not null
                constraint ck_payment_amount_not_zero check (amount_paise <> 0),
  reference     text
                constraint ck_payment_reference check (reference is null or char_length(reference) <= 120),
  recorded_by   uuid not null,
  recorded_at   timestamptz not null default now()
);

create index if not exists ix_payment_tenant_bill on billing.payment (tenant_id, bill_id);
-- Day-closing's "expected cash" sum (today's cash payments minus cash refunds) and "paid today" list.
create index if not exists ix_payment_tenant_recorded_at on billing.payment (tenant_id, recorded_at);

alter table billing.payment enable row level security;
alter table billing.payment force row level security;

create policy p_payment_select on billing.payment
  for select to sahaj_app
  using (tenant_id = platform.current_tenant());

create policy p_payment_insert on billing.payment
  for insert to sahaj_app
  with check (tenant_id = platform.current_tenant());

revoke all on billing.payment from sahaj_app;
revoke all on billing.payment from public;
grant select, insert on billing.payment to sahaj_app;

-- Third layer, same as platform.audit_event: refuse UPDATE/DELETE/TRUNCATE for every role,
-- including the table owner, so this can never become editable by a privilege mistake later.
create trigger trg_payment_no_update_delete
  before update or delete on billing.payment
  for each row execute function platform.deny_audit_mutation();

create trigger trg_payment_no_truncate
  before truncate on billing.payment
  for each statement execute function platform.deny_audit_mutation();

comment on table billing.payment is
  'Append-only financial record (SAH-10): every cash/UPI/card collection and refund (negative amount_paise) against a bill. Never updated, deleted or truncated - enforced by trigger as well as privilege.';

-- billing.day_closing : one row per tenant per business day, recorded once at end of day.
-- A closed day stays closed - no update, no delete, enforced by grants only (append-only by
-- omission of those privileges, same spirit as billing.payment but without needing the trigger
-- since a correction here is "close again tomorrow with a note", not an in-place edit).
create table if not exists billing.day_closing (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null default platform.current_tenant()
                        constraint fk_day_closing_tenant references platform.tenant (id),
  business_date         date not null,
  counted_cash_paise    bigint not null
                        constraint ck_day_closing_counted check (counted_cash_paise >= 0),
  expected_cash_paise   bigint not null,
  diff_paise            bigint not null,
  note                  text
                        constraint ck_day_closing_note check (note is null or char_length(note) <= 400),
  closed_by             uuid not null,
  closed_at             timestamptz not null default now(),
  -- "One closing per clinic per day" (story AC9); 409 on a second attempt.
  constraint uq_day_closing_tenant_date unique (tenant_id, business_date)
);

create index if not exists ix_day_closing_tenant_date on billing.day_closing (tenant_id, business_date);

alter table billing.day_closing enable row level security;
alter table billing.day_closing force row level security;

create policy p_day_closing_select on billing.day_closing
  for select to sahaj_app
  using (tenant_id = platform.current_tenant());

create policy p_day_closing_insert on billing.day_closing
  for insert to sahaj_app
  with check (tenant_id = platform.current_tenant());

revoke all on billing.day_closing from sahaj_app;
revoke all on billing.day_closing from public;
grant select, insert on billing.day_closing to sahaj_app;

comment on table billing.day_closing is
  'One row per tenant per business date (SAH-10): cash counted vs expected at day end. Select/insert only - a closed day stays closed, never updated or deleted.';
