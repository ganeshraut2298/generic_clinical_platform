-- SAH-80 WP-I (story 80-5), migration I4. ADR-030 S5 "Held bill", BR-11, OQ-80-13.
-- New table pharmacy.held_bill and its guard. A held bill is a parked cart: ids and choices only, no number, no stock, no payment.
-- The cart holds ids, quantities and choices plus the typed walk-in name; it is patient data, so it is never logged. The limit of 20 OPEN
-- bills per store is counted by the service under the store row lock (409 held-limit); the database does not count.
-- discount_request_id is a plain uuid here; WP-J (V2026_12_17_1900) adds its composite foreign key to sale_discount_request.

set local lock_timeout = '30s';

create table if not exists pharmacy.held_bill (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null default platform.current_tenant()
                          constraint fk_held_bill_tenant references platform.tenant (id),
  unit_id               uuid not null,
  store_id              uuid not null,
  holder_membership_id  uuid not null,
  -- The cart: product ids, quantities, substitution and override choices, the buyer as an id or a typed walk-in name.
  cart                  jsonb not null
                          constraint ck_held_bill_cart check (jsonb_typeof(cart) = 'object'),
  cart_schema_version   integer not null default 1
                          constraint ck_held_bill_cart_schema check (cart_schema_version >= 1),
  status                text not null default 'OPEN'
                          constraint ck_held_bill_status check (status in ('OPEN', 'RESUMED', 'REPLACED', 'DISCARDED')),
  discount_request_id   uuid,
  held_net_paise        bigint not null default 0
                          constraint ck_held_bill_net check (held_net_paise >= 0),
  created_at            timestamptz not null default now(),
  created_by            uuid not null,
  updated_at            timestamptz,
  updated_by            uuid,
  version               bigint not null default 0,
  constraint uq_held_bill_tenant_id unique (tenant_id, id),
  constraint fk_held_bill_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_held_bill_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id)
);

-- The held-bill list of a store, and the count of OPEN bills (limit 20) under the store lock.
create index if not exists ix_held_bill_store_status on pharmacy.held_bill (tenant_id, store_id, status);

alter table pharmacy.held_bill enable row level security;
alter table pharmacy.held_bill force row level security;
create policy p_held_bill_select on pharmacy.held_bill for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_held_bill_insert on pharmacy.held_bill for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_held_bill_update on pharmacy.held_bill for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.held_bill from sahaj_app;
revoke all on pharmacy.held_bill from public;
grant select, insert, update on pharmacy.held_bill to sahaj_app;

-- Guard: starts OPEN; identity (tenant, unit, store, holder) fixed; the cart and discount link change only while OPEN; OPEN goes to RESUMED,
-- REPLACED or DISCARDED and then the row is final.
create or replace function pharmacy.guard_held_bill_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'INSERT' then
    if new.status <> 'OPEN' then
      raise exception 'held bill must start open' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.store_id is distinct from old.store_id or new.holder_membership_id is distinct from old.holder_membership_id
     or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
    raise exception 'held bill identity cannot change' using errcode = 'check_violation';
  end if;
  if old.status <> 'OPEN' then
    raise exception 'held bill is final (%)', old.status using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_held_bill_write() from public;
revoke all on function pharmacy.guard_held_bill_write() from sahaj_app;

create trigger trg_held_bill_guard_write before insert or update on pharmacy.held_bill
  for each row execute function pharmacy.guard_held_bill_write();

comment on table pharmacy.held_bill is
  'Parked cart of the POS (SAH-80-5, ADR-030 S5, BR-11): OPEN, RESUMED (saved as a bill), REPLACED or DISCARDED. No number, no stock reserved, no payment. At most 20 OPEN per store (service check). Never deleted. The cart is patient data: ids and a typed walk-in name; never logged.';
comment on function pharmacy.guard_held_bill_write() is
  'Held bill guard: starts OPEN; tenant, unit, store, holder and creation stamp never change; a row that is not OPEN is final. SQLSTATE 23514.';
