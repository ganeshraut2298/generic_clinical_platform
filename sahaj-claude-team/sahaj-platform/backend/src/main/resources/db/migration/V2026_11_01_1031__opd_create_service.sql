-- SAH-7 · opd.service : the clinic's price list (Fees and billing). Billed later from the visit.
-- A service is never deleted: "remove" sets active = false, so bills made earlier keep their history.
-- The name is unique per clinic ignoring case, active or not, so a removed service is brought back
-- (active = true) instead of being added twice. A duplicate name returns 409.
-- price_paise: 0 or more here; the app requires more than 0 when a service is added (AC13).
create table if not exists opd.service (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null default platform.current_tenant()
              constraint fk_service_tenant references platform.tenant (id),
  name        text not null
              constraint ck_service_name check (char_length(btrim(name)) between 1 and 120),
  category    text not null
              constraint ck_service_category check (category in
                ('Consultation', 'Procedure', 'Nursing', 'Lab', 'Radiology', 'Dental', 'Package')),
  price_paise bigint not null
              constraint ck_service_price check (price_paise >= 0),
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  created_by  uuid not null,
  updated_at  timestamptz,
  updated_by  uuid,
  version     bigint not null default 0
);

-- Duplicate guard and, by its order, the fee list (all of a clinic's services sorted by name).
create unique index if not exists uq_service_tenant_name on opd.service (tenant_id, lower(name));
-- Billing picker: active services only.
create index if not exists ix_service_tenant_active on opd.service (tenant_id, lower(name)) where active;

alter table opd.service enable row level security;
alter table opd.service force row level security;

create policy p_service_tenant on opd.service
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

revoke all on opd.service from sahaj_app;
revoke all on opd.service from public;
grant select, insert, update on opd.service to sahaj_app;

comment on table opd.service is
  'Clinic price list (SAH-7). Soft-deleted with active = false; never hard-deleted, so past bills stay intact. Name unique per clinic, case-insensitive.';
