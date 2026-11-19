-- SAH-79 WP-D, migration D2b. ADR-029 P3 (balance), P4 (FEFO and the expiry sweep indexes), P5 (lock order), "Data needed" D2.
-- New table pharmacy.stock_balance: a projection of the ledger, one row per (store, batch, state). Tenant table, forced row level
-- security, no delete grant. Written only by the posting service in the same transaction as its ledger lines.

set local lock_timeout = '30s';

create table if not exists pharmacy.stock_balance (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null default platform.current_tenant()
                 constraint fk_stock_balance_tenant references platform.tenant (id),
  unit_id      uuid not null,
  store_id     uuid not null,
  -- Denormalised from the batch (for FEFO and store views); the composite foreign key below keeps it equal to the batch's product.
  product_id   uuid not null,
  batch_id     uuid not null,
  state        text not null
                 constraint ck_stock_balance_state check (state in ('AVAILABLE', 'IN_TRANSIT', 'PENDING_RETURN', 'QUARANTINE')),
  -- Fixed rule BR-6: stock is never negative. The posting service refuses first (409 insufficient-stock); this is the backstop.
  qty          integer not null default 0
                 constraint ck_stock_balance_qty check (qty >= 0),
  created_at   timestamptz not null default now(),
  created_by   uuid not null,
  updated_at   timestamptz,
  updated_by   uuid,
  version      bigint not null default 0,
  -- The row the posting service locks (lock order step 7).
  constraint uq_stock_balance_key unique (tenant_id, store_id, batch_id, state),
  constraint fk_stock_balance_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_stock_balance_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_stock_balance_batch foreign key (tenant_id, batch_id, product_id) references pharmacy.batch (tenant_id, id, product_id)
);

-- FEFO pick and lock of every balance row of (store, product): select ... where store_id = ? and product_id = ? order by batch_id, state for update.
create index if not exists ix_stock_balance_store_product on pharmacy.stock_balance (tenant_id, store_id, product_id, state);
-- One product's stock in every store ("All stores" view, reorder list, vendor return).
create index if not exists ix_stock_balance_product_state on pharmacy.stock_balance (tenant_id, product_id, state);
-- Expiry sweep of a store (ADR-029 P4): AVAILABLE rows with stock, joined to the batch by expiry_date, FOR UPDATE SKIP LOCKED; also the
-- store overview counts and batch lists, which read only rows with stock.
create index if not exists ix_stock_balance_sweep on pharmacy.stock_balance (tenant_id, store_id, state) where qty > 0;

alter table pharmacy.stock_balance enable row level security;
alter table pharmacy.stock_balance force row level security;
create policy p_stock_balance_select on pharmacy.stock_balance for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_stock_balance_insert on pharmacy.stock_balance for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_stock_balance_update on pharmacy.stock_balance for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.stock_balance from sahaj_app;
revoke all on pharmacy.stock_balance from public;
grant select, insert, update on pharmacy.stock_balance to sahaj_app;

-- The key of a balance row never changes (a move is a decrease on one row and an increase on another). Only qty and bookkeeping move.
create or replace function pharmacy.guard_stock_balance_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.store_id is distinct from old.store_id or new.product_id is distinct from old.product_id
     or new.batch_id is distinct from old.batch_id or new.state is distinct from old.state then
    raise exception 'stock balance key cannot change' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_stock_balance_update() from public;
revoke all on function pharmacy.guard_stock_balance_update() from sahaj_app;
create trigger trg_stock_balance_guard_update before update on pharmacy.stock_balance
  for each row execute function pharmacy.guard_stock_balance_update();

comment on table pharmacy.stock_balance is
  'Current quantity per store, batch and state (AVAILABLE, IN_TRANSIT, PENDING_RETURN, QUARANTINE): a projection of pharmacy.stock_ledger (SAH-79, ADR-029 P3). Changed only by the posting service in the same transaction as its ledger lines. Rows are never deleted; a zero row stays. qty >= 0 always. Reconciliation: for every store, batch and state, sum(qty_in - qty_out) of the ledger equals qty.';
comment on column pharmacy.stock_balance.qty is
  'Whole issue units. Never negative (check). In transit and Pending return belong to the store that will receive them or holds them (BR-4).';
