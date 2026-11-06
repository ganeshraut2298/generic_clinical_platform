-- SAH-1 · platform.membership : a person's role in one clinic.
-- Tenant-owned; row level security fails closed when no tenant is bound.
create table if not exists platform.membership (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_membership_tenant references platform.tenant (id),
  user_id       uuid not null
                constraint fk_membership_user references platform.app_user (id),
  role          text not null
                constraint ck_membership_role check (role in
                  ('OWNER', 'DOCTOR', 'VISITING_DOCTOR', 'NURSE', 'FRONT_DESK', 'BILLING', 'PHARMACIST')),
  status        text not null default 'ACTIVE'
                constraint ck_membership_status check (status in ('ACTIVE', 'INACTIVE', 'REMOVED')),
  last_login_at timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz,
  version       bigint not null default 0,
  constraint uq_membership_tenant_user unique (tenant_id, user_id)
);

-- uq_membership_tenant_user also serves "list the team of this clinic" (starts with tenant_id).
create index if not exists ix_membership_user on platform.membership (user_id);

alter table platform.membership enable row level security;
alter table platform.membership force row level security;

create policy p_membership_tenant on platform.membership
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- Never deleted: status REMOVED keeps history.
revoke all on platform.membership from sahaj_app;
grant select, insert, update on platform.membership to sahaj_app;
