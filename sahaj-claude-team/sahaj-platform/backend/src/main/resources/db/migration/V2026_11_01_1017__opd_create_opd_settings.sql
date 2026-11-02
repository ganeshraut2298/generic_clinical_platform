-- SAH-4 · opd.opd_settings : how a clinic's OPD flow runs, one row per tenant.
-- Story design: "opd_settings is written through the opd.api port in schema opd" — the setup
-- wizard's step 4 answers are staged in platform.setup_progress.answers_json like every other
-- step, and only written here, through the opd module's own API port, inside the
-- setup/complete transaction (same module-boundary rule as every other cross-module call: no
-- foreign key into opd from platform; platform calls opd.api, which writes its own schema).
create table if not exists opd.opd_settings (
  tenant_id       uuid primary key
                  constraint fk_opd_settings_tenant references platform.tenant (id),
  fee_timing      text not null default 'before'
                  constraint ck_opd_settings_fee_timing check (fee_timing in ('before', 'after', 'split')),
  queue_on        boolean not null default true,
  vitals_on       boolean not null default false,
  follow_up_days  integer not null default 7
                  constraint ck_opd_settings_follow_up_days check (follow_up_days between 1 and 30),
  follow_up_fee   text not null default 'free'
                  constraint ck_opd_settings_follow_up_fee check (follow_up_fee in ('free', 'half', 'full')),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz,
  version         bigint not null default 0
);

alter table opd.opd_settings enable row level security;
alter table opd.opd_settings force row level security;

create policy p_opd_settings_tenant on opd.opd_settings
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- Never deleted: edited in place (here and later by SAH-7-style clinic settings).
revoke all on opd.opd_settings from sahaj_app;
revoke all on opd.opd_settings from public;
grant select, insert, update on opd.opd_settings to sahaj_app;

comment on table opd.opd_settings is
  'One row per tenant: fee timing, token queue on/off, nurse vitals on/off, and follow-up window/fee, set by the SAH-4 wizard step 4 and editable later. Column names match the SAH-4 story design (fee_timing, queue_on, vitals_on, follow_up_days, follow_up_fee), not the SetupOpd API field names (feeTiming, queue, vitals, followUpDays, followUpFee) — java-developer maps between the two.';
