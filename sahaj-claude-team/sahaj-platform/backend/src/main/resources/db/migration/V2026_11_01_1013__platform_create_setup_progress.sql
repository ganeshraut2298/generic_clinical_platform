-- SAH-4 · platform.setup_progress : the clinic owner's answers while running the setup wizard.
-- One row per tenant, created as soon as a tenant exists (sign-up, SAH-3), kept until setup is
-- completed. AC2 (resume at the step I left) reads this row; completed_at NULL means "mid-setup",
-- which is the signal login (SAH-3) and /me (SAH-5) use to send the owner back to /setup.
create table if not exists platform.setup_progress (
  tenant_id     uuid primary key
                constraint fk_setup_progress_tenant references platform.tenant (id),
  current_step  integer not null default 1
                constraint ck_setup_progress_current_step check (current_step between 1 and 5),
  answers_json  jsonb not null default '{}'::jsonb,
  completed_at  timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz,
  version       bigint not null default 0
);

-- No separate tenant-first index needed: tenant_id is the primary key.

alter table platform.setup_progress enable row level security;
alter table platform.setup_progress force row level security;

create policy p_setup_progress_tenant on platform.setup_progress
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- Never deleted: a finished wizard's answers stay as history (and as the resume row would be
-- pointless to remove); "done" is completed_at, not row absence.
revoke all on platform.setup_progress from sahaj_app;
revoke all on platform.setup_progress from public;
grant select, insert, update on platform.setup_progress to sahaj_app;

comment on table platform.setup_progress is
  'One row per tenant: wizard step, saved answers (answers_json), and completed_at. completed_at IS NULL is the "owner is mid-setup" signal for login and /me.';
comment on column platform.setup_progress.answers_json is
  'Raw per-step answers (SetupClinic, SetupSpecialty, SetupTeam, SetupOpd, SetupLook shapes from docs/api/platform.yaml), schema owned by the application, not validated by the database beyond being a JSON object.';
