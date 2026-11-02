-- SAH-7 · platform.clinic_holiday : days a clinic is closed, or open for one session only.
-- Shown on Clinic profile > Timings, leave and holidays. Added and removed by the owner; no edit
-- (the API has POST and DELETE only). A repeated date for the same clinic is refused (409).
-- The column is holiday_date because "date" is a type name. API field: Holiday.date.
create table if not exists platform.clinic_holiday (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_clinic_holiday_tenant references platform.tenant (id),
  holiday_date  date not null,
  name          text not null
                constraint ck_clinic_holiday_name check (char_length(btrim(name)) between 1 and 120),
  type          text not null
                constraint ck_clinic_holiday_type check (type in ('CLOSED', 'MORNING_ONLY', 'EVENING_ONLY')),
  created_at    timestamptz not null default now(),
  created_by    uuid not null,
  constraint uq_clinic_holiday_tenant_date unique (tenant_id, holiday_date)
);

-- uq_clinic_holiday_tenant_date also serves "list this clinic's holidays in date order".

alter table platform.clinic_holiday enable row level security;
alter table platform.clinic_holiday force row level security;

create policy p_clinic_holiday_tenant on platform.clinic_holiday
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- Configuration, not clinical or billing: the owner may remove a holiday. audit_event keeps the trail.
revoke all on platform.clinic_holiday from sahaj_app;
revoke all on platform.clinic_holiday from public;
grant select, insert, delete on platform.clinic_holiday to sahaj_app;

comment on table platform.clinic_holiday is
  'Clinic holidays and one-session days (SAH-7). Unique per clinic and date. Removal is a real delete; the audit trail is in platform.audit_event.';
