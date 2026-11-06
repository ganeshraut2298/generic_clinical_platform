-- SAH-4 · platform.clinic_profile : the real clinic record, written once at "Create my clinic"
-- (setup/complete), one row per tenant. Also read and updated later by SAH-7 (clinic settings).
-- Separate from platform.tenant: tenant holds identity/plan/slug/brand used by auth and billing;
-- clinic_profile holds the descriptive fields the wizard and SAH-7 collect. tenant.brand mirrors
-- clinic_profile.brand after setup/complete so login and the shell can read brand without a join.
create table if not exists platform.clinic_profile (
  tenant_id       uuid primary key
                  constraint fk_clinic_profile_tenant references platform.tenant (id),
  name            text not null
                  constraint ck_clinic_profile_name check (char_length(btrim(name)) between 1 and 160),
  name_local      text
                  constraint ck_clinic_profile_name_local check (name_local is null or char_length(name_local) <= 160),
  address         text
                  constraint ck_clinic_profile_address check (address is null or char_length(address) <= 400),
  city            text not null
                  constraint ck_clinic_profile_city check (char_length(btrim(city)) between 1 and 120),
  pin             char(6)
                  constraint ck_clinic_profile_pin check (pin is null or pin ~ '^[0-9]{6}$'),
  phone           text
                  constraint ck_clinic_profile_phone check (phone is null or char_length(phone) <= 20),
  registration_no text
                  constraint ck_clinic_profile_registration_no check (registration_no is null or char_length(registration_no) <= 80),
  specialty_code  text
                  constraint ck_clinic_profile_specialty_code check (specialty_code in ('gp', 'dental', 'paeds', 'homeo')),
  brand           text not null default 'blue'
                  constraint ck_clinic_profile_brand check (brand in ('blue', 'teal', 'green', 'maroon', 'custom')),
  default_language text not null default 'en'
                  constraint ck_clinic_profile_default_language check (default_language in ('en', 'mr', 'hi')),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz,
  version         bigint not null default 0
);

alter table platform.clinic_profile enable row level security;
alter table platform.clinic_profile force row level security;

create policy p_clinic_profile_tenant on platform.clinic_profile
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- Never deleted: a clinic's profile is edited, not removed; it follows the tenant's lifecycle.
revoke all on platform.clinic_profile from sahaj_app;
revoke all on platform.clinic_profile from public;
grant select, insert, update on platform.clinic_profile to sahaj_app;

comment on table platform.clinic_profile is
  'One row per tenant: the real clinic record written at setup/complete (SAH-4) and edited by SAH-7. Not written during the wizard steps themselves; see platform.setup_progress.answers_json for in-progress answers.';
