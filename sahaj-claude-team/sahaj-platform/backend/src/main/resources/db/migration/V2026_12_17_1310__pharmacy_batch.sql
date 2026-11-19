-- SAH-79 WP-D, migration D2a. ADR-029 P2 (batch identity, expiry, rates, the OQ-79-37 interim holding), "Data needed" D2.
-- New table pharmacy.batch, one row per product, batch number and expiry. Tenant table, forced row level security, no delete grant.
-- Nothing existing is changed.

set local lock_timeout = '30s';

create table if not exists pharmacy.batch (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null default platform.current_tenant()
                        constraint fk_batch_tenant references platform.tenant (id),
  unit_id             uuid not null,
  product_id          uuid not null,
  -- As first received (shown as typed). Matching ignores case and leading or trailing spaces through batch_key (OQ-79-37 interim).
  batch_no            text not null
                        constraint ck_batch_no_length check (char_length(batch_no) between 1 and 20)
                        constraint ck_batch_no_not_blank check (char_length(btrim(batch_no)) >= 1),
  batch_key           text generated always as (upper(btrim(batch_no))) stored,
  -- Last valid day (IST); month and year typed means the last day of that month (OQ-79-7). Expired when expiry_date < today (IST).
  expiry_date         date not null,
  mrp                 numeric(14,4) not null
                        constraint ck_batch_mrp check (mrp > 0),
  -- Per issue unit, without GST, the latest receipt's rate (OQ-79-6). Null for an opening-stock row that had no rate.
  purchase_rate       numeric(14,4)
                        constraint ck_batch_purchase_rate check (purchase_rate >= 0),
  -- Vendor of the latest receipt. Null for opening stock and found stock (OQ-79-11).
  vendor_id           uuid,
  first_received_at   timestamptz not null default now(),
  source              text not null
                        constraint ck_batch_source check (source in ('OPENING_STOCK', 'GRN', 'FOUND')),
  created_at          timestamptz not null default now(),
  created_by          uuid not null,
  updated_at          timestamptz,
  updated_by          uuid,
  version             bigint not null default 0,
  constraint uq_batch_tenant_id unique (tenant_id, id),
  -- Target of the composite foreign keys that carry product_id next to batch_id (balance, ledger, adjustment): product always matches.
  constraint uq_batch_tenant_id_product unique (tenant_id, id, product_id),
  -- One batch per product, batch number (case and edge spaces ignored) and expiry (ADR-029 P2, ADR-028 D6).
  constraint uq_batch_identity unique (tenant_id, unit_id, product_id, batch_key, expiry_date),
  constraint fk_batch_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_batch_product foreign key (tenant_id, product_id) references pharmacy.product (tenant_id, id),
  constraint fk_batch_vendor foreign key (tenant_id, vendor_id) references pharmacy.vendor (tenant_id, id)
);

-- Batches of one product by expiry (product stock, batch pick lists). The unique key above starts with unit_id after tenant_id, so
-- a query by tenant and product alone cannot use it well.
create index if not exists ix_batch_product on pharmacy.batch (tenant_id, product_id, expiry_date);

alter table pharmacy.batch enable row level security;
alter table pharmacy.batch force row level security;
create policy p_batch_select on pharmacy.batch for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_batch_insert on pharmacy.batch for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_batch_update on pharmacy.batch for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.batch from sahaj_app;
revoke all on pharmacy.batch from public;
grant select, insert, update on pharmacy.batch to sahaj_app;

-- Fixed rule BR-10: a batch is the same product, batch number and expiry with ONE MRP. Identity and MRP never change after the row
-- exists; a different MRP is a different truth that the service refuses (409 batch-mrp-mismatch). Only the latest purchase rate and
-- vendor (and the bookkeeping columns) move. Fires on update only.
create or replace function pharmacy.guard_batch_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.product_id is distinct from old.product_id or new.batch_no is distinct from old.batch_no
     or new.expiry_date is distinct from old.expiry_date or new.source is distinct from old.source
     or new.first_received_at is distinct from old.first_received_at
     or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
    raise exception 'batch identity cannot change' using errcode = 'check_violation';
  end if;
  if new.mrp is distinct from old.mrp then
    raise exception 'batch MRP cannot change' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_batch_update() from public;
revoke all on function pharmacy.guard_batch_update() from sahaj_app;
create trigger trg_batch_guard_update before update on pharmacy.batch
  for each row execute function pharmacy.guard_batch_update();

comment on table pharmacy.batch is
  'Batch of a product: one row per product, batch number and expiry (SAH-79, ADR-029 P2). Never deleted. Identity and MRP are fixed once created (BR-10); the latest purchase rate and vendor are updated by receipts. Stock quantities are in pharmacy.stock_balance, not here.';
comment on column pharmacy.batch.batch_key is
  'Generated upper(btrim(batch_no)). Interim holding while OQ-79-37 is open: batch numbers that differ only in case or edge spaces are one batch. If the pharmacist decides case matters, a forward migration replaces uq_batch_identity by one on batch_no (a relaxation, no row can violate it).';
comment on column pharmacy.batch.expiry_date is
  'Last valid day (IST). Valid through that day; expired when expiry_date is before today in Asia/Kolkata. Shown as month and year.';
comment on column pharmacy.batch.purchase_rate is
  'Purchase rate per issue unit, without GST, after discount spread over paid and free units (OQ-79-6). The latest receipt''s rate; every receipt line keeps its own. Null when unknown (shown as a dash).';
comment on column pharmacy.batch.first_received_at is
  'FEFO tie-break after expiry_date, then batch id.';
