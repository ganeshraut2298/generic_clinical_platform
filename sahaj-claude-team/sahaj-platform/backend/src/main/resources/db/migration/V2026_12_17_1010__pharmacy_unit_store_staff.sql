-- SAH-78 WP-A (stories 78-1 to 78-4), migration A2 of 8. ADR-028 D2 and D4, "Data needed".
-- Tables: pharmacy.unit, pharmacy.store, pharmacy.store_staff, pharmacy.staff_preference. All four are new, all are tenant tables
-- with forced row level security. Nothing is dropped, renamed or updated. The schema pharmacy is created by
-- scripts/db/schemas.sql (create-database.mjs), not here; this file fails if it is missing.
-- Money, rates and approval rules are not in this file.

set local lock_timeout = '30s';

grant usage on schema pharmacy to sahaj_app;   -- same as scripts/db/schemas.sql; idempotent, in case the script was run before the schema was added

-- ======================================================================================================================
-- 1. pharmacy.unit: exactly one per tenant in this release (unique (tenant_id)); a later multi-unit release drops that constraint.
--    Created lazily by the first master write. unit_id is in no RLS policy (ADR-028 D2).
-- ======================================================================================================================
create table if not exists pharmacy.unit (
  id                        uuid primary key default gen_random_uuid(),
  tenant_id                 uuid not null default platform.current_tenant()
                              constraint fk_unit_tenant references platform.tenant (id),
  code                      text not null
                              constraint ck_unit_code check (code ~ '^[A-Z0-9]{3}$'),
  gstin                     text
                              constraint ck_unit_gstin check (gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'),
  drug_licence_no           text
                              constraint ck_unit_drug_licence_no check (char_length(drug_licence_no) between 1 and 40),
  drug_licence_valid_till   date,
  integration_mode          text not null default 'INTEGRATED'
                              constraint ck_unit_integration_mode check (integration_mode in ('INTEGRATED', 'STANDALONE')),
  own_product_next_no       integer not null default 1
                              constraint ck_unit_own_product_next_no check (own_product_next_no >= 1),
  created_at                timestamptz not null default now(),
  created_by                uuid not null,
  updated_at                timestamptz,
  updated_by                uuid,
  version                   bigint not null default 0,
  constraint uq_unit_tenant unique (tenant_id),
  constraint uq_unit_tenant_id unique (tenant_id, id)
);

alter table pharmacy.unit enable row level security;
alter table pharmacy.unit force row level security;
create policy p_unit_select on pharmacy.unit for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_unit_insert on pharmacy.unit for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_unit_update on pharmacy.unit for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
-- No delete grant and no delete policy: a unit is never removed.
revoke all on pharmacy.unit from sahaj_app;
revoke all on pharmacy.unit from public;
grant select, insert, update on pharmacy.unit to sahaj_app;

comment on table pharmacy.unit is
  'Pharmacy and Inventory unit settings (SAH-78, ADR-028 D2). One row per tenant, created lazily by the first master write. Never deleted.';
comment on column pharmacy.unit.code is
  'Three characters A-Z 0-9, derived once from the clinic web address when the row is created; read-only. Used in own-product codes U-<code>-nnnn.';
comment on column pharmacy.unit.own_product_next_no is
  'Next own-product number. Taken with UPDATE ... RETURNING in the transaction that inserts the product (no gaps on rollback).';

-- ======================================================================================================================
-- 2. pharmacy.store
-- ======================================================================================================================
create table if not exists pharmacy.store (
  id                     uuid primary key default gen_random_uuid(),
  tenant_id              uuid not null default platform.current_tenant()
                           constraint fk_store_tenant references platform.tenant (id),
  unit_id                uuid not null,
  name                   text not null
                           constraint ck_store_name check (char_length(name) between 1 and 60 and name = btrim(name)),
  code                   text not null
                           constraint ck_store_code check (code ~ '^[A-Z0-9-]{1,10}$'),
  type                   text not null
                           constraint ck_store_type check (type in ('CENTRAL', 'PHARMACY', 'SUB_STORE', 'WARD')),
  issues_from_store_id   uuid,
  sale_allowed           boolean not null default false,
  consumption_allowed    boolean not null default false,
  has_stock              boolean not null default false,
  status                 text not null default 'ACTIVE'
                           constraint ck_store_status check (status in ('ACTIVE', 'INACTIVE')),
  created_at             timestamptz not null default now(),
  created_by             uuid not null,
  updated_at             timestamptz,
  updated_by             uuid,
  version                bigint not null default 0,
  constraint uq_store_tenant_id unique (tenant_id, id),
  constraint fk_store_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_store_issues_from foreign key (tenant_id, issues_from_store_id) references pharmacy.store (tenant_id, id),
  -- The Central Store issues from nobody; every other store issues from a store (depth one is a service check).
  constraint ck_store_issues_from check ((type = 'CENTRAL') = (issues_from_store_id is null)),
  constraint ck_store_issues_from_not_self check (issues_from_store_id is null or issues_from_store_id <> id)
);

-- Store list and duplicate checks: name and code unique per unit ignoring case (inactive stores keep their name and code).
create unique index if not exists uq_store_name on pharmacy.store (tenant_id, unit_id, lower(name));
create unique index if not exists uq_store_code on pharmacy.store (tenant_id, unit_id, lower(code));
-- At most one ACTIVE Central Store per unit.
create unique index if not exists uq_store_one_active_central on pharmacy.store (tenant_id, unit_id)
  where type = 'CENTRAL' and status = 'ACTIVE';

alter table pharmacy.store enable row level security;
alter table pharmacy.store force row level security;
create policy p_store_select on pharmacy.store for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_store_insert on pharmacy.store for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_store_update on pharmacy.store for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.store from sahaj_app;
revoke all on pharmacy.store from public;
grant select, insert, update on pharmacy.store to sahaj_app;

-- Defence in depth for the rule "once a store has stock its type and code are locked" and "has_stock never goes back to false".
-- Fires on update only; the approval of opening stock sets has_stock (ADR-028 D12). Switch-off of a store with stock stays a service rule.
create or replace function pharmacy.guard_store_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if old.has_stock and not new.has_stock then
    raise exception 'store stock flag cannot be cleared' using errcode = 'check_violation';
  end if;
  if old.has_stock and (new.type is distinct from old.type or new.code is distinct from old.code) then
    raise exception 'store type and code are locked once the store has stock' using errcode = 'check_violation';
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id then
    raise exception 'store cannot move to another tenant or unit' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_store_update() from public;
revoke all on function pharmacy.guard_store_update() from sahaj_app;
create trigger trg_store_guard_update before update on pharmacy.store
  for each row execute function pharmacy.guard_store_update();

comment on table pharmacy.store is
  'Store of a pharmacy unit (SAH-78-3): CENTRAL, PHARMACY, SUB_STORE or WARD. Never deleted; switched off by status. type and code are locked once has_stock is true.';
comment on column pharmacy.store.issues_from_store_id is
  'The store this store is supplied from. Null exactly for the Central Store. Depth one (non-central stores issue from the Central Store) is a service check.';

-- ======================================================================================================================
-- 3. pharmacy.store_staff: who is mapped to which store, with which store role (SAH-78-4). membership_id is an opaque uuid (no
--    foreign key across modules); the team member is checked through TeamMemberPort.
-- ======================================================================================================================
create table if not exists pharmacy.store_staff (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null default platform.current_tenant()
                     constraint fk_store_staff_tenant references platform.tenant (id),
  unit_id          uuid not null,
  store_id         uuid not null,
  membership_id    uuid not null,
  store_role       text not null
                     constraint ck_store_staff_role check (store_role in ('STORE_USER', 'CENTRAL_STORE_USER', 'APPROVER', 'DELEGATE')),
  delegate_from    date,
  delegate_to      date,
  status           text not null default 'ACTIVE'
                     constraint ck_store_staff_status check (status in ('ACTIVE', 'REMOVED')),
  created_at       timestamptz not null default now(),
  created_by       uuid not null,
  updated_at       timestamptz,
  updated_by       uuid,
  version          bigint not null default 0,
  -- A re-map re-activates the same row (status back to ACTIVE), so history of the pair is one row.
  constraint uq_store_staff_member unique (tenant_id, store_id, membership_id),
  constraint fk_store_staff_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_store_staff_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  -- Dates exactly for a DELEGATE: both set, to >= from, at most 90 days (OQ-78-15, DEC-064 R2-9); else both null.
  constraint ck_store_staff_delegate_dates check (
    (store_role = 'DELEGATE' and delegate_from is not null and delegate_to is not null
       and delegate_to >= delegate_from and delegate_to - delegate_from <= 90)
    or (store_role <> 'DELEGATE' and delegate_from is null and delegate_to is null))
);

-- At most one ACTIVE HOD (APPROVER) and one ACTIVE DELEGATE per store.
create unique index if not exists uq_store_staff_one_active_approver on pharmacy.store_staff (tenant_id, store_id)
  where store_role = 'APPROVER' and status = 'ACTIVE';
create unique index if not exists uq_store_staff_one_active_delegate on pharmacy.store_staff (tenant_id, store_id)
  where store_role = 'DELEGATE' and status = 'ACTIVE';
-- StoreAccessGuard, the menu port and GET /context: "active mappings of this member".
create index if not exists ix_store_staff_member_active on pharmacy.store_staff (tenant_id, membership_id)
  where status = 'ACTIVE';

alter table pharmacy.store_staff enable row level security;
alter table pharmacy.store_staff force row level security;
create policy p_store_staff_select on pharmacy.store_staff for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_store_staff_insert on pharmacy.store_staff for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_store_staff_update on pharmacy.store_staff for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.store_staff from sahaj_app;
revoke all on pharmacy.store_staff from public;
grant select, insert, update on pharmacy.store_staff to sahaj_app;

comment on table pharmacy.store_staff is
  'Store mapping of a team member with a store role (SAH-78-4, ADR-028 D4). Never deleted; REMOVED rows are re-activated by a re-map. Allowed store role per team role, and Central-Store-only for CENTRAL_STORE_USER, are service checks.';

-- ======================================================================================================================
-- 4. pharmacy.staff_preference: the last working store of a member, for convenience only (not audited).
-- ======================================================================================================================
create table if not exists pharmacy.staff_preference (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null default platform.current_tenant()
                    constraint fk_staff_preference_tenant references platform.tenant (id),
  unit_id         uuid not null,
  membership_id   uuid not null,
  last_store_id   uuid not null,
  created_at      timestamptz not null default now(),
  created_by      uuid not null,
  updated_at      timestamptz,
  updated_by      uuid,
  version         bigint not null default 0,
  constraint uq_staff_preference_member unique (tenant_id, membership_id),
  constraint fk_staff_preference_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_staff_preference_store foreign key (tenant_id, last_store_id) references pharmacy.store (tenant_id, id)
);

alter table pharmacy.staff_preference enable row level security;
alter table pharmacy.staff_preference force row level security;
create policy p_staff_preference_select on pharmacy.staff_preference for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_staff_preference_insert on pharmacy.staff_preference for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_staff_preference_update on pharmacy.staff_preference for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.staff_preference from sahaj_app;
revoke all on pharmacy.staff_preference from public;
grant select, insert, update on pharmacy.staff_preference to sahaj_app;

comment on table pharmacy.staff_preference is
  'Last working store per member (SAH-78-2). Convenience only; the server never trusts it for access. One row per member, updated in place, never deleted.';
