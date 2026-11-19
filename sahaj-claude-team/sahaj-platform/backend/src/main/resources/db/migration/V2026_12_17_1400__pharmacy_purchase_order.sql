-- SAH-79 WP-E (story 79-6), migration E1. ADR-029 P10 "Purchase order", P9 (numbers, drafts, history), "Data needed" WP-E, OQ-79-31, OQ-79-32.
-- New objects only: function pharmacy.guard_central_store_insert() (shared by purchase_order, goods_receipt, debit_note), tables
-- pharmacy.purchase_order and pharmacy.purchase_order_line with their guard functions and triggers.
-- Tenant tables, forced row level security, no delete grant. A line removed from a draft is flagged removed (no row is ever deleted);
-- a removed line stays removed. PO numbers come from pharmacy.next_document_no('PURCHASE_ORDER') at first submit (not at draft).

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. Backstop shared by the three buying documents: the store is the unit's Central Store (ADR-029 P10, "Central Store only").
--    Runs as the caller (sahaj_app can read store); a missing store is left to the foreign key. SQLSTATE 23514.
-- ======================================================================================================================
create or replace function pharmacy.guard_central_store_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_type text;
begin
  select s.type into v_type from pharmacy.store s where s.tenant_id = new.tenant_id and s.id = new.store_id;
  if v_type is not null and v_type <> 'CENTRAL' then
    raise exception '% must be for the central store', tg_table_name using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_central_store_insert() from public;
revoke all on function pharmacy.guard_central_store_insert() from sahaj_app;

-- ======================================================================================================================
-- 2. pharmacy.purchase_order
-- ======================================================================================================================
create table if not exists pharmacy.purchase_order (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null default platform.current_tenant()
                          constraint fk_purchase_order_tenant references platform.tenant (id),
  unit_id               uuid not null,
  store_id              uuid not null,
  vendor_id             uuid not null,
  -- PO number: doc_seq from next_document_no('PURCHASE_ORDER') at first submit; doc_no the text rendered then (a later series edit never
  -- changes an issued number). Both null while the order is a draft (or a discarded draft).
  doc_seq               bigint
                          constraint ck_purchase_order_doc_seq check (doc_seq >= 1),
  doc_no                text
                          constraint ck_purchase_order_doc_no check (char_length(doc_no) between 1 and 16),
  status                text not null default 'DRAFT'
                          constraint ck_purchase_order_status check (status in
                            ('DRAFT', 'WAITING_APPROVAL', 'APPROVED', 'REJECTED', 'SENT', 'PARTLY_RECEIVED', 'RECEIVED',
                             'CLOSED_SHORT', 'CANCELLED', 'DISCARDED')),
  -- Required before submit (service rule), so nullable in a draft. Today or later at submit.
  delivery_expected     date,
  credit_days           integer not null default 0
                          constraint ck_purchase_order_credit_days check (credit_days between 0 and 365),
  sent_on               date,
  -- The approval request that decided it; null at level 0.
  approval_request_id   uuid,
  subtotal_paise        bigint not null default 0
                          constraint ck_purchase_order_subtotal check (subtotal_paise >= 0),
  gst_paise             bigint not null default 0
                          constraint ck_purchase_order_gst check (gst_paise >= 0),
  total_paise           bigint not null default 0
                          constraint ck_purchase_order_total check (total_paise >= 0),
  created_at            timestamptz not null default now(),
  -- Membership id (opaque uuid) of the person who raised it.
  created_by            uuid not null,
  updated_at            timestamptz,
  updated_by            uuid,
  version               bigint not null default 0,
  constraint uq_purchase_order_tenant_id unique (tenant_id, id),
  constraint uq_purchase_order_doc_no unique (tenant_id, unit_id, doc_no),
  constraint uq_purchase_order_doc_seq unique (tenant_id, unit_id, doc_seq),
  constraint fk_purchase_order_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_purchase_order_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_purchase_order_vendor foreign key (tenant_id, vendor_id) references pharmacy.vendor (tenant_id, id),
  constraint fk_purchase_order_request foreign key (tenant_id, approval_request_id) references pharmacy.approval_request (tenant_id, id),
  -- Number and sequence together; a submitted order always has one.
  constraint ck_purchase_order_number check ((doc_no is null) = (doc_seq is null)),
  constraint ck_purchase_order_numbered check (status in ('DRAFT', 'DISCARDED') or doc_no is not null),
  -- Total includes GST (BR-17).
  constraint ck_purchase_order_total_sum check (total_paise = subtotal_paise + gst_paise),
  -- "Sent on" exists only once the order was approved.
  constraint ck_purchase_order_sent_on check (sent_on is null or status not in ('DRAFT', 'WAITING_APPROVAL', 'REJECTED', 'DISCARDED'))
);

-- The list of a store by status, newest first, and the five tiles (draft, waiting, sent, partly received) of the screen.
create index if not exists ix_purchase_order_store_status on pharmacy.purchase_order (tenant_id, store_id, status, created_at desc);
-- Orders of one vendor (vendor screen, "is this vendor on an open order") and the child side of the vendor foreign key.
create index if not exists ix_purchase_order_vendor on pharmacy.purchase_order (tenant_id, vendor_id, created_at desc);
-- "Overdue <n> days": delivery date passed while goods are outstanding (SENT or PARTLY_RECEIVED), and the po-vs-grn report.
create index if not exists ix_purchase_order_overdue on pharmacy.purchase_order (tenant_id, store_id, delivery_expected)
  where status in ('SENT', 'PARTLY_RECEIVED');

alter table pharmacy.purchase_order enable row level security;
alter table pharmacy.purchase_order force row level security;
create policy p_purchase_order_select on pharmacy.purchase_order for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_purchase_order_insert on pharmacy.purchase_order for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_purchase_order_update on pharmacy.purchase_order for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.purchase_order from sahaj_app;
revoke all on pharmacy.purchase_order from public;
grant select, insert, update on pharmacy.purchase_order to sahaj_app;

create trigger trg_purchase_order_central_store before insert on pharmacy.purchase_order
  for each row execute function pharmacy.guard_central_store_insert();

-- Insert: always a DRAFT. Update: finished orders are final; only listed moves; identity and number never change once set; vendor,
-- dates, credit days and totals change only while the order is a DRAFT (or in the update that leaves DRAFT).
create or replace function pharmacy.guard_purchase_order_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'INSERT' then
    if new.status <> 'DRAFT' then
      raise exception 'purchase order must start as a draft' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if old.status in ('REJECTED', 'RECEIVED', 'CLOSED_SHORT', 'CANCELLED', 'DISCARDED') then
    raise exception 'purchase order is final (%)', old.status using errcode = 'check_violation';
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.store_id is distinct from old.store_id or new.created_at is distinct from old.created_at
     or new.created_by is distinct from old.created_by
     or (old.doc_no is not null and new.doc_no is distinct from old.doc_no)
     or (old.doc_seq is not null and new.doc_seq is distinct from old.doc_seq) then
    raise exception 'purchase order identity cannot change' using errcode = 'check_violation';
  end if;
  if new.status is distinct from old.status and not (
       (old.status = 'DRAFT' and new.status in ('WAITING_APPROVAL', 'APPROVED', 'DISCARDED'))
    or (old.status = 'WAITING_APPROVAL' and new.status in ('APPROVED', 'REJECTED', 'CANCELLED'))
    or (old.status = 'APPROVED' and new.status in ('SENT', 'PARTLY_RECEIVED', 'RECEIVED', 'CANCELLED'))
    or (old.status = 'SENT' and new.status in ('PARTLY_RECEIVED', 'RECEIVED', 'CANCELLED'))
    or (old.status = 'PARTLY_RECEIVED' and new.status in ('RECEIVED', 'CLOSED_SHORT'))) then
    raise exception 'purchase order cannot go from % to %', old.status, new.status using errcode = 'check_violation';
  end if;
  if old.status <> 'DRAFT' and (
       new.vendor_id is distinct from old.vendor_id or new.delivery_expected is distinct from old.delivery_expected
       or new.credit_days is distinct from old.credit_days or new.subtotal_paise is distinct from old.subtotal_paise
       or new.gst_paise is distinct from old.gst_paise or new.total_paise is distinct from old.total_paise) then
    raise exception 'purchase order cannot change once submitted' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_purchase_order_write() from public;
revoke all on function pharmacy.guard_purchase_order_write() from sahaj_app;
create trigger trg_purchase_order_guard_write before insert or update on pharmacy.purchase_order
  for each row execute function pharmacy.guard_purchase_order_write();

comment on table pharmacy.purchase_order is
  'Purchase order of the Central Store (SAH-79-6, ADR-029 P10). PO number at first submit. Statuses DRAFT .. DISCARDED; Overdue is computed from delivery_expected. Never deleted; finished orders are final. No patient data.';
comment on column pharmacy.purchase_order.created_by is 'Membership id (opaque uuid) of the person who raised it.';

-- ======================================================================================================================
-- 3. pharmacy.purchase_order_line
-- ======================================================================================================================
create table if not exists pharmacy.purchase_order_line (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null default platform.current_tenant()
                         constraint fk_purchase_order_line_tenant references platform.tenant (id),
  unit_id              uuid not null,
  purchase_order_id    uuid not null,
  product_id           uuid not null,
  -- Position on the screen (1-based). Removed lines keep theirs.
  sort                 integer not null
                         constraint ck_purchase_order_line_sort check (sort between 1 and 1000),
  -- Purchase units. Rate per purchase unit (the Rate type of the contract: up to 7 digits and 4 decimals).
  qty                  integer not null
                         constraint ck_purchase_order_line_qty check (qty between 1 and 999999),
  rate                 numeric(14,4) not null
                         constraint ck_purchase_order_line_rate check (rate >= 0),
  -- Copied from the product at submit (ADR-029 P10); typed percent 0 to 100.
  gst_rate             numeric(5,2) not null default 0
                         constraint ck_purchase_order_line_gst_rate check (gst_rate between 0 and 100),
  amount_paise         bigint not null default 0
                         constraint ck_purchase_order_line_amount check (amount_paise >= 0),
  gst_paise            bigint not null default 0
                         constraint ck_purchase_order_line_gst check (gst_paise >= 0),
  -- Posted receipts raise it (purchase units); never goes down. A receipt may exceed the ordered quantity (over-supply is the service's call).
  received_qty         integer not null default 0
                         constraint ck_purchase_order_line_received check (received_qty >= 0),
  closed_short         boolean not null default false,
  -- A line taken off a draft is flagged, not deleted; it stays removed.
  removed              boolean not null default false,
  created_at           timestamptz not null default now(),
  created_by           uuid not null,
  updated_at           timestamptz,
  updated_by           uuid,
  version              bigint not null default 0,
  constraint uq_purchase_order_line_tenant_id unique (tenant_id, id),
  constraint fk_purchase_order_line_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_purchase_order_line_order foreign key (tenant_id, purchase_order_id) references pharmacy.purchase_order (tenant_id, id),
  constraint fk_purchase_order_line_product foreign key (tenant_id, product_id) references pharmacy.product (tenant_id, id),
  -- A removed line carries no received quantity (it could never have been received: removal is only while the order is a draft).
  constraint ck_purchase_order_line_removed check (not removed or received_qty = 0)
);

-- One live line per product per order (a removed line may be added again as a new row).
create unique index if not exists uq_purchase_order_line_product on pharmacy.purchase_order_line (tenant_id, purchase_order_id, product_id)
  where not removed;
-- The lines of one order in screen order (the order screen, receipt fill-in from remaining quantities).
create index if not exists ix_purchase_order_line_order on pharmacy.purchase_order_line (tenant_id, purchase_order_id, sort);
-- "Open orders for this product" (suggested order quantity, product stock card) and the child side of the product foreign key.
create index if not exists ix_purchase_order_line_product on pharmacy.purchase_order_line (tenant_id, product_id) where not removed;

alter table pharmacy.purchase_order_line enable row level security;
alter table pharmacy.purchase_order_line force row level security;
create policy p_purchase_order_line_select on pharmacy.purchase_order_line for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_purchase_order_line_insert on pharmacy.purchase_order_line for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_purchase_order_line_update on pharmacy.purchase_order_line for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.purchase_order_line from sahaj_app;
revoke all on pharmacy.purchase_order_line from public;
grant select, insert, update on pharmacy.purchase_order_line to sahaj_app;

-- Insert and every content change (product, sort, qty, rate, GST, amounts, removed) only while the parent is a DRAFT: write the lines
-- before the header leaves DRAFT (submit copies the GST rate and amounts first, then updates the header). received_qty and closed_short
-- move after posting: received_qty never goes down, closed_short only goes to true. A removed line stays removed. The parent is read
-- without a lock (the service holds the header lock FOR UPDATE, ADR-029 P5 step 3).
create or replace function pharmacy.guard_purchase_order_line_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_parent text;
begin
  select o.status into v_parent from pharmacy.purchase_order o where o.tenant_id = new.tenant_id and o.id = new.purchase_order_id;
  if tg_op = 'INSERT' then
    if v_parent is not null and v_parent <> 'DRAFT' then
      raise exception 'purchase order lines can change only while the order is a draft' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.purchase_order_id is distinct from old.purchase_order_id or new.created_at is distinct from old.created_at
     or new.created_by is distinct from old.created_by then
    raise exception 'purchase order line identity cannot change' using errcode = 'check_violation';
  end if;
  if old.removed and not new.removed then
    raise exception 'purchase order line stays removed' using errcode = 'check_violation';
  end if;
  if new.received_qty < old.received_qty then
    raise exception 'purchase order line received quantity cannot go down' using errcode = 'check_violation';
  end if;
  if old.closed_short and not new.closed_short then
    raise exception 'purchase order line stays closed short' using errcode = 'check_violation';
  end if;
  if v_parent <> 'DRAFT' and (
       new.product_id is distinct from old.product_id or new.sort is distinct from old.sort or new.qty is distinct from old.qty
       or new.rate is distinct from old.rate or new.gst_rate is distinct from old.gst_rate
       or new.amount_paise is distinct from old.amount_paise or new.gst_paise is distinct from old.gst_paise
       or new.removed is distinct from old.removed) then
    raise exception 'purchase order lines can change only while the order is a draft' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_purchase_order_line_write() from public;
revoke all on function pharmacy.guard_purchase_order_line_write() from sahaj_app;
create trigger trg_purchase_order_line_guard_write before insert or update on pharmacy.purchase_order_line
  for each row execute function pharmacy.guard_purchase_order_line_write();

comment on table pharmacy.purchase_order_line is
  'Line of a purchase order, in purchase units with a rate per purchase unit (SAH-79-6). Content changes only while the order is a DRAFT; received_qty and closed_short move after receipts. A removed line is flagged, never deleted.';
