-- SAH-19 · Plan and modules.
-- platform.module_entitlement : one row per tenant, lazy-created by the app on first write (no seed row).

create table if not exists platform.module_entitlement (
  tenant_id         uuid primary key default platform.current_tenant()
                      constraint fk_module_entitlement_tenant references platform.tenant (id),
  ipd_state         text not null default 'off'
                      constraint ck_module_entitlement_ipd_state check (ipd_state in ('off','trial','on','readonly')),
  ipd_trial_ends_at timestamptz,
  hra_on            boolean not null default false,
  bed_limit         integer not null default 25
                      constraint ck_module_entitlement_bed_limit check (bed_limit between 5 and 300),
  ipd_requested_at  timestamptz,
  updated_at        timestamptz,
  updated_by        uuid,
  version           bigint not null default 0
);

alter table platform.module_entitlement enable row level security;
alter table platform.module_entitlement force row level security;

create policy p_module_entitlement_tenant on platform.module_entitlement
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- No delete: entitlements are changed, never removed.
revoke all on platform.module_entitlement from sahaj_app;
revoke all on platform.module_entitlement from public;
grant select, insert, update on platform.module_entitlement to sahaj_app;

comment on table platform.module_entitlement is
  'Per-clinic module entitlement (SAH-19): IPD state (off/trial/on/readonly), trial end, HRA flag, bed limit, IPD request time. Lazy: no row until the first write; the app serves defaults (ipd off, hra off, 25 beds) when absent. Never deleted.';
