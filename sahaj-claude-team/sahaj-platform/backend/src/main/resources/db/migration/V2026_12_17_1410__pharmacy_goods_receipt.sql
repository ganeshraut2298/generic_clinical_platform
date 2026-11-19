-- SAH-79 WP-E (story 79-7), migration E2. ADR-029 P10 "Receipt (GRN)", "Rate rise", "Posting a receipt", P9 (the GRN number is taken at
-- posting), P7 (Schedule X second signature), OQ-79-9, OQ-79-10, "Data needed" WP-E.
-- New objects only: tables pharmacy.goods_receipt and pharmacy.goods_receipt_line with guard functions and triggers. Depends on E1 (purchase
-- order tables and pharmacy.guard_central_store_insert()). Tenant tables, forced row level security, no delete grant. A line taken off a draft
-- is flagged removed (E1 convention). The ledger lines, batches and balances are written by the posting service, not here.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.goods_receipt (header)
-- ======================================================================================================================
create table if not exists pharmacy.goods_receipt (
  id                      uuid primary key default gen_random_uuid(),
  tenant_id               uuid not null default platform.current_tenant()
                            constraint fk_goods_receipt_tenant references platform.tenant (id),
  unit_id                 uuid not null,
  store_id                uuid not null,
  -- Null for a direct receipt (no purchase order).
  purchase_order_id       uuid,
  vendor_id               uuid not null,
  -- GRN number: taken at posting only (ADR-029 P9). doc_no is the text rendered then.
  doc_seq                 bigint
                            constraint ck_goods_receipt_doc_seq check (doc_seq >= 1),
  doc_no                  text
                            constraint ck_goods_receipt_doc_no check (char_length(doc_no) between 1 and 16),
  status                  text not null default 'DRAFT'
                            constraint ck_goods_receipt_status check (status in ('DRAFT', 'WAITING_APPROVAL', 'APPROVED', 'POSTED', 'DISCARDED')),
  invoice_no              text not null
                            constraint ck_goods_receipt_invoice_no check (char_length(invoice_no) between 1 and 30 and char_length(btrim(invoice_no)) >= 1),
  invoice_date            date not null,
  credit_days             integer not null default 0
                            constraint ck_goods_receipt_credit_days check (credit_days between 0 and 365),
  -- As printed on the paper invoice (OQ-79-9b). The service refuses a post when it differs from the computed total by more than 100 paise.
  invoice_total_paise     bigint not null
                            constraint ck_goods_receipt_invoice_total check (invoice_total_paise between 0 and 99999999900),
  -- The rate-rise approval request; null when no approval was needed.
  approval_request_id     uuid,
  posted_at               timestamptz,
  -- Membership id of the person who posted (the ledger actor; a Schedule X second signer is never this person).
  posted_by               uuid,
  created_at              timestamptz not null default now(),
  created_by              uuid not null,
  updated_at              timestamptz,
  updated_by              uuid,
  version                 bigint not null default 0,
  constraint uq_goods_receipt_tenant_id unique (tenant_id, id),
  constraint uq_goods_receipt_doc_no unique (tenant_id, unit_id, doc_no),
  constraint uq_goods_receipt_doc_seq unique (tenant_id, unit_id, doc_seq),
  constraint fk_goods_receipt_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_goods_receipt_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_goods_receipt_order foreign key (tenant_id, purchase_order_id) references pharmacy.purchase_order (tenant_id, id),
  constraint fk_goods_receipt_vendor foreign key (tenant_id, vendor_id) references pharmacy.vendor (tenant_id, id),
  constraint fk_goods_receipt_request foreign key (tenant_id, approval_request_id) references pharmacy.approval_request (tenant_id, id),
  -- The number, the posting time and the poster exist exactly when the receipt is POSTED.
  constraint ck_goods_receipt_number check ((doc_no is null) = (doc_seq is null)),
  constraint ck_goods_receipt_posted check (
    (status = 'POSTED') = (doc_no is not null and posted_at is not null and posted_by is not null)
    and ((doc_no is null) = (posted_at is null) and (posted_at is null) = (posted_by is null)))
);

-- "Duplicate invoice" (409 duplicate-invoice): one non-discarded receipt per vendor and invoice number, ignoring case and edge spaces.
create unique index if not exists uq_goods_receipt_invoice on pharmacy.goods_receipt (tenant_id, vendor_id, lower(btrim(invoice_no)))
  where status <> 'DISCARDED';
-- Receipts of a store by status, newest first (the receipts list).
create index if not exists ix_goods_receipt_store on pharmacy.goods_receipt (tenant_id, store_id, status, created_at desc);
-- Receipts of one purchase order (the order screen's "Receipts" card, remaining quantities) and the child side of the order foreign key.
create index if not exists ix_goods_receipt_order on pharmacy.goods_receipt (tenant_id, purchase_order_id) where purchase_order_id is not null;
-- Posted receipts by time (purchase register and po-vs-grn reports: EXPLAIN first, section 13.7) and "last posted receipt of this vendor".
create index if not exists ix_goods_receipt_vendor_posted on pharmacy.goods_receipt (tenant_id, vendor_id, posted_at desc) where status = 'POSTED';

alter table pharmacy.goods_receipt enable row level security;
alter table pharmacy.goods_receipt force row level security;
create policy p_goods_receipt_select on pharmacy.goods_receipt for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_goods_receipt_insert on pharmacy.goods_receipt for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_goods_receipt_update on pharmacy.goods_receipt for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.goods_receipt from sahaj_app;
revoke all on pharmacy.goods_receipt from public;
grant select, insert, update on pharmacy.goods_receipt to sahaj_app;

create trigger trg_goods_receipt_central_store before insert on pharmacy.goods_receipt
  for each row execute function pharmacy.guard_central_store_insert();

-- Insert: DRAFT only. Update: POSTED and DISCARDED are final (only updated_*, version may move on a POSTED row: nothing else); only listed
-- moves; identity (tenant, unit, store, order, created) never changes; invoice fields and vendor change only while DRAFT.
create or replace function pharmacy.guard_goods_receipt_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'INSERT' then
    if new.status <> 'DRAFT' then
      raise exception 'goods receipt must start as a draft' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if old.status = 'DISCARDED' then
    raise exception 'goods receipt is final (%)', old.status using errcode = 'check_violation';
  end if;
  if old.status = 'POSTED' then
    if (pg_catalog.to_jsonb(new) - 'updated_at' - 'updated_by' - 'version')
       is distinct from (pg_catalog.to_jsonb(old) - 'updated_at' - 'updated_by' - 'version') then
      raise exception 'goods receipt is final (%)', old.status using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.store_id is distinct from old.store_id or new.purchase_order_id is distinct from old.purchase_order_id
     or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
    raise exception 'goods receipt identity cannot change' using errcode = 'check_violation';
  end if;
  if new.status is distinct from old.status and not (
       (old.status = 'DRAFT' and new.status in ('WAITING_APPROVAL', 'POSTED', 'DISCARDED'))
    or (old.status = 'WAITING_APPROVAL' and new.status in ('APPROVED', 'DRAFT', 'DISCARDED'))
    or (old.status = 'APPROVED' and new.status in ('POSTED', 'DRAFT'))) then
    raise exception 'goods receipt cannot go from % to %', old.status, new.status using errcode = 'check_violation';
  end if;
  if old.status <> 'DRAFT' and (
       new.vendor_id is distinct from old.vendor_id or new.invoice_no is distinct from old.invoice_no
       or new.invoice_date is distinct from old.invoice_date or new.credit_days is distinct from old.credit_days
       or new.invoice_total_paise is distinct from old.invoice_total_paise) then
    raise exception 'goods receipt cannot be edited once it has been sent for approval' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_goods_receipt_write() from public;
revoke all on function pharmacy.guard_goods_receipt_write() from sahaj_app;
create trigger trg_goods_receipt_guard_write before insert or update on pharmacy.goods_receipt
  for each row execute function pharmacy.guard_goods_receipt_write();

comment on table pharmacy.goods_receipt is
  'Goods receipt (GRN) of the Central Store against a purchase order or direct (SAH-79-7, ADR-029 P10). GRN number at posting. DRAFT, WAITING_APPROVAL (rate rise), APPROVED, POSTED, DISCARDED. Never deleted; POSTED and DISCARDED are final. No patient data.';
comment on column pharmacy.goods_receipt.posted_by is 'Membership id (opaque uuid) of the person who posted: the ledger actor.';

-- ======================================================================================================================
-- 2. pharmacy.goods_receipt_line
-- ======================================================================================================================
create table if not exists pharmacy.goods_receipt_line (
  id                         uuid primary key default gen_random_uuid(),
  tenant_id                  uuid not null default platform.current_tenant()
                               constraint fk_goods_receipt_line_tenant references platform.tenant (id),
  unit_id                    uuid not null,
  goods_receipt_id           uuid not null,
  purchase_order_line_id     uuid,
  product_id                 uuid not null,
  sort                       integer not null
                               constraint ck_goods_receipt_line_sort check (sort between 1 and 1000),
  removed                    boolean not null default false,
  -- As typed (the first received spelling), 1 to 20 characters.
  batch_no                   text not null
                               constraint ck_goods_receipt_line_batch_no check (char_length(batch_no) between 1 and 20 and char_length(btrim(batch_no)) >= 1),
  -- Held as the last day of the typed month (OQ-79-7): the next day is the 1st.
  expiry_date                date not null
                               constraint ck_goods_receipt_line_expiry check (extract(day from expiry_date + 1) = 1),
  -- Purchase units. Issue units added to stock = (qty + free_qty) x conversion (service).
  qty                        integer not null
                               constraint ck_goods_receipt_line_qty check (qty between 0 and 999999),
  billed_qty                 integer not null
                               constraint ck_goods_receipt_line_billed check (billed_qty between 0 and 999999),
  free_qty                   integer not null default 0
                               constraint ck_goods_receipt_line_free check (free_qty between 0 and 999999),
  ptr                        numeric(14,4) not null
                               constraint ck_goods_receipt_line_ptr check (ptr >= 0),
  mrp                        numeric(14,4) not null
                               constraint ck_goods_receipt_line_mrp check (mrp > 0),
  discount_percent           numeric(5,2) not null default 0
                               constraint ck_goods_receipt_line_discount check (discount_percent between 0 and 100),
  gst_rate                   numeric(5,2) not null default 0
                               constraint ck_goods_receipt_line_gst_rate check (gst_rate between 0 and 100),
  -- Computed and stored at save so a posted receipt keeps what it showed even when the product or the last rate changes later.
  taxable_paise              bigint not null default 0
                               constraint ck_goods_receipt_line_taxable check (taxable_paise >= 0),
  gst_paise                  bigint not null default 0
                               constraint ck_goods_receipt_line_gst check (gst_paise >= 0),
  -- ptr after discount, per purchase unit: the rate the rate-rise check compares (OQ-79-10).
  net_rate                   numeric(14,4)
                               constraint ck_goods_receipt_line_net_rate check (net_rate >= 0),
  reference_rate             numeric(14,4)
                               constraint ck_goods_receipt_line_reference_rate check (reference_rate >= 0),
  rate_basis                 text
                               constraint ck_goods_receipt_line_rate_basis check (rate_basis in ('PO', 'LAST')),
  -- Rate rise in percent (one decimal in the screen; negative is a fall). Null when there is no reference rate.
  rate_percent               numeric(9,2),
  short_qty                  integer
                               constraint ck_goods_receipt_line_short_qty check (short_qty >= 0),
  short_action               text
                               constraint ck_goods_receipt_line_short_action check (short_action in ('BALANCE_TO_COME', 'CLOSE_SHORT')),
  claim_rate_difference      boolean not null default false,
  -- Set at posting (once): the batch created or topped up, the landed cost per issue unit, the Schedule X second signer.
  batch_id                   uuid,
  landed_rate                numeric(14,4)
                               constraint ck_goods_receipt_line_landed check (landed_rate >= 0),
  second_signer_membership_id uuid,
  created_at                 timestamptz not null default now(),
  created_by                 uuid not null,
  updated_at                 timestamptz,
  updated_by                 uuid,
  version                    bigint not null default 0,
  constraint uq_goods_receipt_line_tenant_id unique (tenant_id, id),
  constraint fk_goods_receipt_line_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_goods_receipt_line_receipt foreign key (tenant_id, goods_receipt_id) references pharmacy.goods_receipt (tenant_id, id),
  constraint fk_goods_receipt_line_po_line foreign key (tenant_id, purchase_order_line_id) references pharmacy.purchase_order_line (tenant_id, id),
  constraint fk_goods_receipt_line_product foreign key (tenant_id, product_id) references pharmacy.product (tenant_id, id),
  -- MATCH SIMPLE: a null batch_id is not checked; when set, the batch must belong to this product.
  constraint fk_goods_receipt_line_batch foreign key (tenant_id, batch_id, product_id) references pharmacy.batch (tenant_id, id, product_id),
  constraint ck_goods_receipt_line_removed check (not removed or batch_id is null)
);

-- The lines of one receipt in screen order (the receipt screen, posting).
create index if not exists ix_goods_receipt_line_receipt on pharmacy.goods_receipt_line (tenant_id, goods_receipt_id, sort);
-- "Last posted rate of this product" (rate-rise reference when there is no PO line) joins here, and the child side of the product FK.
create index if not exists ix_goods_receipt_line_product on pharmacy.goods_receipt_line (tenant_id, product_id) where not removed;
-- Received so far against a PO line (remaining quantity) and the child side of the PO-line FK.
create index if not exists ix_goods_receipt_line_po_line on pharmacy.goods_receipt_line (tenant_id, purchase_order_line_id)
  where purchase_order_line_id is not null;
-- Receipt lines of a batch (batch history) and the child side of the batch FK.
create index if not exists ix_goods_receipt_line_batch on pharmacy.goods_receipt_line (tenant_id, batch_id) where batch_id is not null;

alter table pharmacy.goods_receipt_line enable row level security;
alter table pharmacy.goods_receipt_line force row level security;
create policy p_goods_receipt_line_select on pharmacy.goods_receipt_line for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_goods_receipt_line_insert on pharmacy.goods_receipt_line for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_goods_receipt_line_update on pharmacy.goods_receipt_line for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.goods_receipt_line from sahaj_app;
revoke all on pharmacy.goods_receipt_line from public;
grant select, insert, update on pharmacy.goods_receipt_line to sahaj_app;

-- Insert and every content change only while the parent receipt is a DRAFT. The posting columns (batch_id, landed_rate, second signer)
-- are set once, while the parent is DRAFT, APPROVED or POSTED; a removed line stays removed. The parent is read without a lock (the service
-- holds the header lock FOR UPDATE, ADR-029 P5 step 3).
create or replace function pharmacy.guard_goods_receipt_line_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_parent text;
begin
  select g.status into v_parent from pharmacy.goods_receipt g where g.tenant_id = new.tenant_id and g.id = new.goods_receipt_id;
  if tg_op = 'INSERT' then
    if v_parent is not null and v_parent <> 'DRAFT' then
      raise exception 'goods receipt lines can change only while the receipt is a draft' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.goods_receipt_id is distinct from old.goods_receipt_id or new.created_at is distinct from old.created_at
     or new.created_by is distinct from old.created_by then
    raise exception 'goods receipt line identity cannot change' using errcode = 'check_violation';
  end if;
  if old.removed and not new.removed then
    raise exception 'goods receipt line stays removed' using errcode = 'check_violation';
  end if;
  if (old.batch_id is not null and new.batch_id is distinct from old.batch_id)
     or (old.landed_rate is not null and new.landed_rate is distinct from old.landed_rate)
     or (old.second_signer_membership_id is not null
         and new.second_signer_membership_id is distinct from old.second_signer_membership_id) then
    raise exception 'goods receipt line posting data is set once' using errcode = 'check_violation';
  end if;
  if v_parent not in ('DRAFT', 'APPROVED', 'POSTED') and (
       new.batch_id is distinct from old.batch_id or new.landed_rate is distinct from old.landed_rate
       or new.second_signer_membership_id is distinct from old.second_signer_membership_id) then
    raise exception 'goods receipt line posting data is set at posting' using errcode = 'check_violation';
  end if;
  if v_parent <> 'DRAFT' and (pg_catalog.to_jsonb(new)
       - 'batch_id' - 'landed_rate' - 'second_signer_membership_id' - 'updated_at' - 'updated_by' - 'version')
     is distinct from (pg_catalog.to_jsonb(old)
       - 'batch_id' - 'landed_rate' - 'second_signer_membership_id' - 'updated_at' - 'updated_by' - 'version') then
    raise exception 'goods receipt lines can change only while the receipt is a draft' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_goods_receipt_line_write() from public;
revoke all on function pharmacy.guard_goods_receipt_line_write() from sahaj_app;
create trigger trg_goods_receipt_line_guard_write before insert or update on pharmacy.goods_receipt_line
  for each row execute function pharmacy.guard_goods_receipt_line_write();

comment on table pharmacy.goods_receipt_line is
  'Line of a goods receipt (SAH-79-7): batch, expiry (last day of the month), received, billed and free quantities in purchase units, PTR, MRP, discount, GST and the rate-rise check. Content changes only while the receipt is a DRAFT; batch, landed rate and second signer are set once at posting. A removed line is flagged, never deleted.';
