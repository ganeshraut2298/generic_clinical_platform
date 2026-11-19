-- SAH-78 WP-C (story 78-8), migration C1 of 8. ADR-028 "Data needed": pharmacy.vendor, pharmacy.vendor_store, and the foreign key
-- pharmacy.product.preferred_vendor_id (the column was created in B3). All new tables, tenant tables with forced row level security,
-- no delete grant. "Blocked" (licence expired) is computed from licence_valid_till in IST at read time, never stored.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.vendor
-- ======================================================================================================================
create table if not exists pharmacy.vendor (
  id                          uuid primary key default gen_random_uuid(),
  tenant_id                   uuid not null default platform.current_tenant()
                                constraint fk_vendor_tenant references platform.tenant (id),
  unit_id                     uuid not null,
  name                        text not null
                                constraint ck_vendor_name check (char_length(name) between 1 and 120 and name = btrim(name)),
  gstin                       text
                                constraint ck_vendor_gstin check (gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'),
  licence_required            boolean not null default false,
  licence_no                  text
                                constraint ck_vendor_licence_no check (char_length(licence_no) between 1 and 40),
  licence_valid_till          date,
  contact_name                text
                                constraint ck_vendor_contact_name check (char_length(contact_name) between 1 and 80),
  contact_phone               text
                                constraint ck_vendor_contact_phone check (contact_phone ~ '^[0-9]{10}$'),
  credit_days                 integer not null default 0
                                constraint ck_vendor_credit_days check (credit_days between 0 and 365),
  return_days_before_expiry   integer
                                constraint ck_vendor_return_days check (return_days_before_expiry between 0 and 365),
  return_credit_pct           numeric(5,2)
                                constraint ck_vendor_return_credit check (return_credit_pct between 0 and 100),
  status                      text not null default 'ACTIVE'
                                constraint ck_vendor_status check (status in ('ACTIVE', 'INACTIVE')),
  client_key                  uuid,
  created_at                  timestamptz not null default now(),
  created_by                  uuid not null,
  updated_at                  timestamptz,
  updated_by                  uuid,
  version                     bigint not null default 0,
  constraint uq_vendor_tenant_id unique (tenant_id, id),
  constraint fk_vendor_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  -- A vendor that needs a licence has its number and expiry date.
  constraint ck_vendor_licence check (not licence_required or (licence_no is not null and licence_valid_till is not null))
);

-- Vendor name is unique in the unit ignoring case (also the list sort and the duplicate refusal).
create unique index if not exists uq_vendor_name on pharmacy.vendor (tenant_id, unit_id, lower(name));
-- Create replay: the same caller sending the same clientKey twice.
create unique index if not exists uq_vendor_client_key on pharmacy.vendor (tenant_id, created_by, client_key)
  where client_key is not null;
-- Vendor list by status; licence-expiry worklist (PO block) reads active vendors with a date.
create index if not exists ix_vendor_unit_status on pharmacy.vendor (tenant_id, unit_id, status);

alter table pharmacy.vendor enable row level security;
alter table pharmacy.vendor force row level security;
create policy p_vendor_select on pharmacy.vendor for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_vendor_insert on pharmacy.vendor for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_vendor_update on pharmacy.vendor for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
-- No delete grant and no delete policy: vendors are switched off by status, never removed.
revoke all on pharmacy.vendor from sahaj_app;
revoke all on pharmacy.vendor from public;
grant select, insert, update on pharmacy.vendor to sahaj_app;

comment on table pharmacy.vendor is
  'Vendor master of a unit (SAH-78-8). Never deleted; switched off by status. The contact phone is a personal-data field: never logged. Blocked (licence expired) is computed from licence_valid_till in IST.';

-- ======================================================================================================================
-- 2. pharmacy.vendor_store: the stores a vendor supplies to. Edits flip active (no delete grant), so a store removed from a vendor
--    keeps its row with active = false.
-- ======================================================================================================================
create table if not exists pharmacy.vendor_store (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null default platform.current_tenant()
                 constraint fk_vendor_store_tenant references platform.tenant (id),
  unit_id      uuid not null,
  vendor_id    uuid not null,
  store_id     uuid not null,
  active       boolean not null default true,
  created_at   timestamptz not null default now(),
  created_by   uuid not null,
  updated_at   timestamptz,
  updated_by   uuid,
  version      bigint not null default 0,
  constraint uq_vendor_store unique (tenant_id, vendor_id, store_id),
  constraint fk_vendor_store_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_vendor_store_vendor foreign key (tenant_id, vendor_id) references pharmacy.vendor (tenant_id, id),
  constraint fk_vendor_store_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id)
);

-- Vendors that supply a store (store detail, PO vendor pick in Phase 2). The vendor side is the unique key.
create index if not exists ix_vendor_store_store on pharmacy.vendor_store (tenant_id, store_id) where active;

alter table pharmacy.vendor_store enable row level security;
alter table pharmacy.vendor_store force row level security;
create policy p_vendor_store_select on pharmacy.vendor_store for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_vendor_store_insert on pharmacy.vendor_store for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_vendor_store_update on pharmacy.vendor_store for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.vendor_store from sahaj_app;
revoke all on pharmacy.vendor_store from public;
grant select, insert, update on pharmacy.vendor_store to sahaj_app;

comment on table pharmacy.vendor_store is
  'Stores a vendor supplies to (SAH-78-8). Upserted with an active flag; never deleted.';

-- ======================================================================================================================
-- 3. Preferred vendor on the product (same-tenant composite key; a null vendor is not checked). Adds a constraint to the table created
--    in migration B3 of this release; no existing row can violate it (the column is null everywhere).
-- ======================================================================================================================
alter table pharmacy.product
  add constraint fk_product_preferred_vendor foreign key (tenant_id, preferred_vendor_id)
    references pharmacy.vendor (tenant_id, id);

-- "Products" count of a vendor and the "Preferred vendor needs review" note when the vendor is switched off or blocked.
create index if not exists ix_product_preferred_vendor on pharmacy.product (tenant_id, preferred_vendor_id)
  where preferred_vendor_id is not null;
