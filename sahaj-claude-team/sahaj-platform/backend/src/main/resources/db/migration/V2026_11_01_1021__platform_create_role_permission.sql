-- SAH-6 · platform.role_permission : a clinic's own changes to "Who can do what".
-- Tenant-owned; row level security fails closed when no tenant is bound.
-- Only cells the owner has changed are stored; every other cell falls back to
-- platform.role_permission_default through platform.effective_role_permissions().
create table if not exists platform.role_permission (
  tenant_id  uuid not null default platform.current_tenant()
             constraint fk_role_permission_tenant references platform.tenant (id),
  role       text not null
             constraint ck_role_permission_role check (role in
               ('OWNER', 'DOCTOR', 'VISITING_DOCTOR', 'NURSE', 'FRONT_DESK', 'BILLING', 'PHARMACIST', 'COUNSELLOR')),
  permission text not null
             constraint ck_role_permission_permission check (permission in
               ('token', 'vitals', 'collect', 'cancel', 'refund', 'revenue', 'export')),
  allowed    boolean not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz,
  updated_by uuid,
  version    bigint not null default 0,
  -- The primary key starts with tenant_id: it serves "every override of this clinic".
  constraint pk_role_permission primary key (tenant_id, role, permission),
  -- The owner column is locked on in the mockup.
  constraint ck_role_permission_owner_locked check (role <> 'OWNER' or allowed)
);

alter table platform.role_permission enable row level security;
alter table platform.role_permission force row level security;

create policy p_role_permission_tenant on platform.role_permission
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- A cell is flipped with update (or insert ... on conflict do update), never deleted.
revoke all on platform.role_permission from sahaj_app;
revoke all on platform.role_permission from public;
grant select, insert, update on platform.role_permission to sahaj_app;

-- The full matrix for the bound clinic: defaults, overridden by this clinic's rows.
-- Runs as the caller, so row level security applies. With no clinic bound it returns no rows.
create or replace function platform.effective_role_permissions()
returns table (role text, permission text, allowed boolean)
language sql
stable
as $$
  select d.role, d.permission, coalesce(rp.allowed, d.allowed)
  from platform.role_permission_default d
  left join platform.role_permission rp
         on rp.tenant_id = platform.current_tenant()
        and rp.role = d.role
        and rp.permission = d.permission
  where platform.current_tenant() is not null
  order by d.role, d.permission
$$;

comment on table platform.role_permission is
  'Per-clinic overrides of the role by permission matrix. Missing cell = platform.role_permission_default. OWNER is locked on.';
comment on function platform.effective_role_permissions() is
  'Role by permission matrix for the bound clinic (56 rows): defaults merged with platform.role_permission. Empty when no clinic is bound.';
