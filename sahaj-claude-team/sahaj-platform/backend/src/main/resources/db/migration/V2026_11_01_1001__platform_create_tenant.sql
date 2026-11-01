-- SAH-1 · platform.tenant : one row per clinic or hospital (the organization).
-- Row level security: a session sees and changes only the row whose id is the bound tenant.
create table if not exists platform.tenant (
  id           uuid primary key default gen_random_uuid(),
  name         text not null
               constraint ck_tenant_name check (char_length(btrim(name)) between 1 and 200),
  slug         text
               constraint uq_tenant_slug unique
               constraint ck_tenant_slug check (slug ~ '^[a-z0-9][a-z0-9-]{2,23}$'),
  plan         text not null default 'FREE'
               constraint ck_tenant_plan check (char_length(plan) between 1 and 40),
  plan_ends_at timestamptz,
  status       text not null default 'ACTIVE'
               constraint ck_tenant_status check (char_length(status) between 1 and 40),
  brand        text not null default 'blue'
               constraint ck_tenant_brand check (brand in ('blue', 'teal', 'green', 'maroon', 'custom')),
  created_at   timestamptz not null default now(),
  updated_at   timestamptz,
  version      bigint not null default 0
);

alter table platform.tenant enable row level security;
alter table platform.tenant force row level security;

create policy p_tenant_self on platform.tenant
  for all to sahaj_app
  using (id = platform.current_tenant())
  with check (id = platform.current_tenant());

-- A tenant is never deleted: it is suspended or closed through status.
revoke all on platform.tenant from sahaj_app;
grant select, insert, update on platform.tenant to sahaj_app;
