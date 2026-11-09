-- SAH-29 · IPD packages, read-only slice (DEC-030, docs/architecture/SAH-29-ipd-packages.md section 3). Forward-only, additive:
-- one new tenant table (ipd.package) and three nullable columns on ipd.admission (the package copy). Nothing existing is
-- dropped, renamed, retyped or backfilled; existing admissions read as "no package".
-- The app (sahaj_app) can only READ packages and the copy. Packages are set by hand in sahaj_dev / sahaj_test as the
-- table owner (see docs/database/SAH-29.md); the create / attach path is future work (OQ-29-1, OQ-29-2).
-- Money is integer paise. Seeds nothing (OQ-29-3).

-- 1. ipd.package
create table if not exists ipd.package (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null default platform.current_tenant()
                 constraint fk_package_tenant references platform.tenant (id),
  name           text not null
                 constraint ck_package_name check (char_length(name) between 1 and 80 and btrim(name) = name),
  price_paise    bigint not null
                 constraint ck_package_price check (price_paise between 0 and 100000000 and price_paise % 100 = 0),
  included_days  smallint not null
                 constraint ck_package_included_days check (included_days between 0 and 60),
  active         boolean not null default true,
  -- clock_timestamp(), not now(): rows added in one transaction keep their insert order in the list
  created_at     timestamptz not null default clock_timestamp(),
  -- null while packages are set through the database (no app user); required once OQ-29-1 adds a write path
  created_by     uuid,
  version        bigint not null default 0,
  -- target for a later composite FK (package_id on the admission, OQ-29-2)
  constraint uq_package_tenant_id unique (tenant_id, id)
);

-- A clinic cannot have two packages whose names differ only by case.
create unique index if not exists ux_package_tenant_lower_name on ipd.package (tenant_id, lower(name));
-- The Packages list: one clinic's rows in created_at, id order.
create index if not exists ix_package_tenant_created_at on ipd.package (tenant_id, created_at, id);

-- 2. RLS (ENABLE and FORCE). SELECT policy only: no insert, update or delete policy exists for any role.
alter table ipd.package enable row level security;
alter table ipd.package force row level security;

create policy p_package_select on ipd.package
  for select to sahaj_app
  using (tenant_id = platform.current_tenant());

-- 3. Grants. Schema default privileges give sahaj_app every command, so take everything away first. SELECT only.
revoke all on ipd.package from sahaj_app;
revoke all on ipd.package from public;
grant select on ipd.package to sahaj_app;

-- Packages are never deleted (active = false retires one). Truncate refused for every role, the owner included.
create trigger trg_package_no_truncate
  before truncate on ipd.package
  for each statement execute function platform.deny_audit_mutation();

-- 4. The package copy on the admission (AC2): a copy, not a link, so editing or switching off an ipd.package row never
-- changes an attached bill. No default, no backfill, no new grant: sahaj_app keeps its existing table-level SELECT and its
-- column-level INSERT / UPDATE lists, which do not name these columns, so the app can read but never write them.
alter table ipd.admission
  add column if not exists package_name        text
    constraint ck_admission_package_name check (char_length(package_name) between 1 and 80),
  add column if not exists package_price_paise bigint
    constraint ck_admission_package_price check (package_price_paise between 0 and 100000000 and package_price_paise % 100 = 0),
  add column if not exists package_days        smallint
    constraint ck_admission_package_days check (package_days between 0 and 60);

alter table ipd.admission
  add constraint ck_admission_package_all_or_none check (
    (package_name is null and package_price_paise is null and package_days is null)
    or (package_name is not null and package_price_paise is not null and package_days is not null));

-- 5. Set-once guard (architect's recommendation). Once the copy is set it cannot be changed or cleared, and it cannot be
-- set on a DISCHARGED admission (a bill already shown to a family, or a frozen final bill, must not move under a careless
-- database edit). It fires for every role, the owner included. SQLSTATE 42501, as the other ipd guards.
-- SECURITY INVOKER, fixed search_path, reads only the OLD / NEW rows (no table access).
create or replace function ipd.guard_admission_package()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'UPDATE'
     and (old.package_name is not null or old.package_price_paise is not null or old.package_days is not null) then
    -- already set: any change or clear is refused (the trigger WHEN clause only fires when a package column differs)
    raise exception 'admission package is set once and cannot be changed or cleared'
      using errcode = 'insufficient_privilege';
  end if;
  if new.status = 'DISCHARGED' then
    raise exception 'a package cannot be set on a discharged admission'
      using errcode = 'insufficient_privilege';
  end if;
  return new;
end;
$$;

revoke all on function ipd.guard_admission_package() from public;
revoke all on function ipd.guard_admission_package() from sahaj_app;

-- Two triggers: a WHEN clause cannot see tg_op, and an INSERT trigger cannot reference OLD.
-- Insert: fires only when a package is given. Update: fires only when a package column actually changes.
drop trigger if exists trg_admission_package_set_once_insert on ipd.admission;
create trigger trg_admission_package_set_once_insert
  before insert on ipd.admission
  for each row
  when ((new.package_name, new.package_price_paise, new.package_days) is distinct from (null, null, null))
  execute function ipd.guard_admission_package();

drop trigger if exists trg_admission_package_set_once on ipd.admission;
create trigger trg_admission_package_set_once
  before update of package_name, package_price_paise, package_days on ipd.admission
  for each row
  when ((new.package_name, new.package_price_paise, new.package_days)
        is distinct from (old.package_name, old.package_price_paise, old.package_days))
  execute function ipd.guard_admission_package();

comment on table ipd.package is 'IPD package list (SAH-29, DEC-030): name, price in paise (whole rupees) and included days (0 = day-care) per clinic. Read-only for the app (select grant and policy only); set by hand in sahaj_dev / sahaj_test until OQ-29-1 builds the write path. Never deleted (active = false retires a row); truncate refused by trigger. Holds no patient data.';
comment on column ipd.package.created_by is 'User id; null while packages are set through the database (no app user).';
comment on column ipd.package.active is 'The list shows active rows only (P2). Rows are never deleted.';
comment on column ipd.admission.package_name is 'SAH-29 copy of the package name at attach time (all three package columns set or none). Not writable by the app role; set once (trg_admission_package_set_once).';
comment on column ipd.admission.package_price_paise is 'SAH-29 copy of the package price in paise (whole rupees). See package_name.';
comment on column ipd.admission.package_days is 'SAH-29 copy of the package included days (0 = day-care). See package_name.';
comment on function ipd.guard_admission_package() is 'SAH-29 guard: the package copy on ipd.admission is set once (no change, no clear) and never on a DISCHARGED admission. SQLSTATE 42501.';
comment on index ipd.ux_package_tenant_lower_name is 'SAH-29: package name unique per clinic ignoring case.';
