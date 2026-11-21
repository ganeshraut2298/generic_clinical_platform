-- SAH-80 WP-K (story 80-12), migration K2. ADR-030 "Data needed" (WP-K), BR-25, DEC-074 OQ-80-34.
-- New tables: pharmacy.product_link (the pharmacy product that an OPD medicine or an IPD drug stands for; one row per source item) and
-- pharmacy.product_link_history (append-only: every set or change). The source item ids are opaque uuids (no cross-module foreign key);
-- the product is a pharmacy product (composite tenant foreign key). Tenant tables, forced row level security, no delete grant.
-- No patient data.

set local lock_timeout = '30s';

create table if not exists pharmacy.product_link (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null default platform.current_tenant()
                     constraint fk_product_link_tenant references platform.tenant (id),
  unit_id          uuid not null,
  source_kind      text not null
                     constraint ck_product_link_source_kind check (source_kind in ('OPD_MEDICINE', 'IPD_DRUG')),
  source_item_id   uuid not null,
  product_id       uuid not null,
  set_by           uuid not null,
  set_at           timestamptz not null default now(),
  created_at       timestamptz not null default now(),
  created_by       uuid not null,
  updated_at       timestamptz,
  updated_by       uuid,
  version          bigint not null default 0,
  constraint uq_product_link_source unique (tenant_id, unit_id, source_kind, source_item_id),
  constraint fk_product_link_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_product_link_product foreign key (tenant_id, product_id) references pharmacy.product (tenant_id, id)
);

-- "Which source items point at this product" (product screen, a product that is switched off).
create index if not exists ix_product_link_product on pharmacy.product_link (tenant_id, product_id);

alter table pharmacy.product_link enable row level security;
alter table pharmacy.product_link force row level security;
create policy p_product_link_select on pharmacy.product_link for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_product_link_insert on pharmacy.product_link for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_product_link_update on pharmacy.product_link for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.product_link from sahaj_app;
revoke all on pharmacy.product_link from public;
grant select, insert, update on pharmacy.product_link to sahaj_app;

-- The source of a link never changes (a different source item is a different row); only the product, set_by, set_at and version do.
create or replace function pharmacy.guard_product_link_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.source_kind is distinct from old.source_kind or new.source_item_id is distinct from old.source_item_id then
    raise exception 'product link source cannot change' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_product_link_update() from public;
revoke all on function pharmacy.guard_product_link_update() from sahaj_app;

create trigger trg_product_link_guard_update before update on pharmacy.product_link
  for each row execute function pharmacy.guard_product_link_update();

comment on table pharmacy.product_link is
  'Pharmacy-owned link from an OPD medicine or an IPD drug (opaque source id, no foreign key) to a pharmacy product (SAH-80-12, BR-25, DEC-074 OQ-80-34). One row per source item per unit; the product can be changed (version, history). Never deleted.';
comment on function pharmacy.guard_product_link_update() is
  'Product link guard: tenant, unit, source kind and source item never change. SQLSTATE 23514.';

create table if not exists pharmacy.product_link_history (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null default platform.current_tenant()
                        constraint fk_product_link_history_tenant references platform.tenant (id),
  unit_id             uuid not null,
  source_kind         text not null
                        constraint ck_product_link_history_source_kind check (source_kind in ('OPD_MEDICINE', 'IPD_DRUG')),
  source_item_id      uuid not null,
  -- The product before (null for the first link) and after the change.
  old_product_id      uuid,
  new_product_id      uuid not null,
  changed_by          uuid not null,
  changed_at          timestamptz not null default now(),
  constraint fk_product_link_history_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_product_link_history_old foreign key (tenant_id, old_product_id) references pharmacy.product (tenant_id, id),
  constraint fk_product_link_history_new foreign key (tenant_id, new_product_id) references pharmacy.product (tenant_id, id),
  constraint ck_product_link_history_changed check (old_product_id is null or old_product_id <> new_product_id)
);

-- The history of one source item, newest first.
create index if not exists ix_product_link_history_source on pharmacy.product_link_history (tenant_id, unit_id, source_kind, source_item_id, changed_at desc);

alter table pharmacy.product_link_history enable row level security;
alter table pharmacy.product_link_history force row level security;
create policy p_product_link_history_select on pharmacy.product_link_history for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_product_link_history_insert on pharmacy.product_link_history for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.product_link_history from sahaj_app;
revoke all on pharmacy.product_link_history from public;
grant select, insert on pharmacy.product_link_history to sahaj_app;

create trigger trg_product_link_history_no_update_delete before update or delete on pharmacy.product_link_history
  for each row execute function pharmacy.deny_append_only_mutation();
create trigger trg_product_link_history_no_truncate before truncate on pharmacy.product_link_history
  for each statement execute function pharmacy.deny_append_only_mutation();

comment on table pharmacy.product_link_history is
  'Every set or change of a product link (SAH-80-12): old and new product, who, when. Append-only (select and insert; triggers refuse update, delete, truncate). Never deleted.';
