-- SAH-3 · platform.refresh_token : one row per issued refresh token, opaque and hashed.
-- Tenant-owned: a refresh token is issued only after a tenant is selected, so it is created
-- and read with app.tenant_id already bound, the same as every other tenant table.
create table if not exists platform.refresh_token (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid not null default platform.current_tenant()
             constraint fk_refresh_token_tenant references platform.tenant (id),
  user_id    uuid not null
             constraint fk_refresh_token_user references platform.app_user (id),
  token_hash text not null
             constraint uq_refresh_token_token_hash unique
             constraint ck_refresh_token_token_hash check (char_length(token_hash) between 1 and 200),
  expires_at timestamptz not null,
  revoked_at timestamptz,
  created_at timestamptz not null default now()
);

-- Serves "every live token for this user in this tenant" (logout, membership deactivation
-- checks) and the tenant-first access pattern every other policy here relies on.
create index if not exists ix_refresh_token_tenant_user on platform.refresh_token (tenant_id, user_id);

alter table platform.refresh_token enable row level security;
alter table platform.refresh_token force row level security;

create policy p_refresh_token_tenant on platform.refresh_token
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

revoke all on platform.refresh_token from sahaj_app;
revoke all on platform.refresh_token from public;
grant select, insert, update on platform.refresh_token to sahaj_app;
-- No delete: a used, rotated or revoked token keeps its row (revoked_at) so reuse of a
-- rotated-out token can still be detected and reported.

comment on table platform.refresh_token is
  'Hashed, rotated refresh tokens, one per issued cookie. Tenant-scoped like every other session artifact; logout-all revokes rows by user_id within the bound tenant.';
