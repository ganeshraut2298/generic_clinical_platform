-- SAH-13: Front-desk appointments workspace
-- Creates session_config, appointment_slot, booking_rules, visit tables

\set ON_ERROR_STOP on

-- 1. session_config: Doctor session configuration
create table if not exists opd.session_config (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references platform.tenant(id),
  doctor_id           uuid not null references platform.team_member(id),
  session_code        text not null check (session_code in ('MORNING', 'EVENING')),
  start_time          time not null,
  end_time            time not null,
  slot_minutes        int not null default 15 check (slot_minutes in (10, 15, 20, 30)),
  is_active           boolean not null default true,
  created_at          timestamptz not null default now(),
  created_by          uuid not null references platform.app_user(id),
  updated_at          timestamptz,
  updated_by          uuid references platform.app_user(id),
  version             bigint not null default 0
);

create unique index if not exists uq_session_config_tenant_doctor_session
  on opd.session_config (tenant_id, doctor_id, session_code);

create index if not exists ix_session_config_tenant_doctor
  on opd.session_config (tenant_id, doctor_id);

create index if not exists ix_session_config_tenant_active
  on opd.session_config (tenant_id, is_active);

alter table opd.session_config enable row level security;
alter table opd.session_config force row level security;
create policy p_session_config_tenant on opd.session_config
  using (tenant_id = current_setting('app.tenant_id')::uuid)
  with check (tenant_id = current_setting('app.tenant_id')::uuid);

grant select, insert, update, delete on opd.session_config to sahaj_app;

-- 2. appointment_slot: Individual time slots per day per doctor per session
create table if not exists opd.appointment_slot (
  id                      uuid primary key default gen_random_uuid(),
  tenant_id               uuid not null references platform.tenant(id),
  session_config_id       uuid not null references opd.session_config(id),
  doctor_id               uuid not null references platform.team_member(id),
  slot_date               date not null,
  session_code            text not null check (session_code in ('MORNING', 'EVENING')),
  start_time              time not null,
  end_time                time not null,
  state                   text not null default 'FREE'
                            check (state in ('FREE', 'BOOKED', 'ARRIVED', 'NO_SHOW', 'WALK_IN', 'PAST', 'BLOCKED')),
  patient_id              uuid references patient.patient(id),
  visit_id                uuid references opd.visit(id),
  is_walkin_quota         boolean not null default false,
  notes                   text,
  created_at              timestamptz not null default now(),
  created_by              uuid references platform.app_user(id),
  updated_at              timestamptz,
  updated_by              uuid references platform.app_user(id),
  version                 bigint not null default 0
);

create unique index if not exists uq_appt_slot_tenant_config_date_time
  on opd.appointment_slot (tenant_id, session_config_id, slot_date, start_time);

create index if not exists ix_appt_slot_tenant_date
  on opd.appointment_slot (tenant_id, slot_date);

create index if not exists ix_appt_slot_tenant_doctor_date
  on opd.appointment_slot (tenant_id, doctor_id, slot_date);

create index if not exists ix_appt_slot_tenant_patient
  on opd.appointment_slot (tenant_id, patient_id);

create index if not exists ix_appt_slot_tenant_state
  on opd.appointment_slot (tenant_id, state);

create index if not exists ix_appt_slot_tenant_walkin
  on opd.appointment_slot (tenant_id, is_walkin_quota, slot_date);

alter table opd.appointment_slot enable row level security;
alter table opd.appointment_slot force row level security;
create policy p_appt_slot_tenant on opd.appointment_slot
  using (tenant_id = current_setting('app.tenant_id')::uuid)
  with check (tenant_id = current_setting('app.tenant_id')::uuid);

grant select, insert, update, delete on opd.appointment_slot to sahaj_app;

-- 3. booking_rules: Clinic-wide booking rules (one row per tenant)
create table if not exists opd.booking_rules (
  id                          uuid primary key default gen_random_uuid(),
  tenant_id                   uuid not null unique references platform.tenant(id),
  max_advance_days            int not null default 30 check (max_advance_days between 1 and 90),
  overbook_allowed            boolean not null default false,
  same_day_cutoff             time not null default '10:00',
  walkin_quota_per_session    int not null default 2 check (walkin_quota_per_session between 0 and 10),
  slot_duration_minutes       int not null default 15 check (slot_duration_minutes in (10, 15, 20, 30)),
  min_gap_minutes             int not null default 0 check (min_gap_minutes between 0 and 30),
  created_at                  timestamptz not null default now(),
  created_by                  uuid not null references platform.app_user(id),
  updated_at                  timestamptz,
  updated_by                  uuid references platform.app_user(id),
  version                     bigint not null default 0
);

alter table opd.booking_rules enable row level security;
alter table opd.booking_rules force row level security;
create policy p_booking_rules_tenant on opd.booking_rules
  using (tenant_id = current_setting('app.tenant_id')::uuid)
  with check (tenant_id = current_setting('app.tenant_id')::uuid);

grant select, insert, update, delete on opd.booking_rules to sahaj_app;

-- Insert default rules for existing tenants
insert into opd.booking_rules (tenant_id, created_by)
select id, (select id from platform.app_user where platform.app_user.tenant_id = platform.tenant.id limit 1)
from platform.tenant
where not exists (select 1 from opd.booking_rules br where br.tenant_id = platform.tenant.id)
on conflict (tenant_id) do nothing;

-- 4. visit: Patient visit (created on arrival, links to queue/consultation)
create table if not exists opd.visit (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references platform.tenant(id),
  slot_id             uuid not null unique references opd.appointment_slot(id),
  patient_id          uuid not null references patient.patient(id),
  doctor_id           uuid not null references platform.team_member(id),
  token_number        int not null,
  status              text not null default 'WAITING'
                        check (status in ('WAITING', 'IN_CONSULT', 'DONE', 'CANCELLED')),
  arrived_at          timestamptz not null default now(),
  started_at          timestamptz,
  completed_at        timestamptz,
  created_at          timestamptz not null default now(),
  created_by          uuid not null references platform.app_user(id),
  updated_at          timestamptz,
  updated_by          uuid references platform.app_user(id),
  version             bigint not null default 0
);

create unique index if not exists uq_visit_tenant_token_date
  on opd.visit (tenant_id, token_number, (arrived_at::date));

create index if not exists ix_visit_tenant_patient
  on opd.visit (tenant_id, patient_id);

create index if not exists ix_visit_tenant_doctor_date
  on opd.visit (tenant_id, doctor_id, arrived_at);

create index if not exists ix_visit_tenant_status
  on opd.visit (tenant_id, status);

alter table opd.visit enable row level security;
alter table opd.visit force row level security;
create policy p_visit_tenant on opd.visit
  using (tenant_id = current_setting('app.tenant_id')::uuid)
  with check (tenant_id = current_setting('app.tenant_id')::uuid);

grant select, insert, update, delete on opd.visit to sahaj_app;

-- Function to generate slots for a date range (called by application scheduler)
create or replace function opd.generate_slots_for_date_range(
  p_tenant_id uuid,
  p_start_date date,
  p_end_date date,
  p_created_by uuid
) returns void
language plpgsql
security definer
set search_path = opd, platform, patient, pg_catalog
as $$
declare
  sc record;
  d date;
  slot_start time;
  slot_end time;
  walkin_count int;
  walkin_quota int;
  br record;
begin
  -- Get booking rules for walk-in quota
  select * into br from opd.booking_rules where tenant_id = p_tenant_id;
  walkin_quota := coalesce(br.walkin_quota_per_session, 2);

  for sc in
    select * from opd.session_config
    where tenant_id = p_tenant_id and is_active = true
  loop
    for d in
      select generate_series(p_start_date, p_end_date, interval '1 day')::date as dt
    loop
      walkin_count := 0;
      slot_start := sc.start_time;
      while slot_start < sc.end_time loop
        slot_end := (slot_start + (sc.slot_minutes || ' minutes')::interval)::time;
        if slot_end > sc.end_time then
          slot_end := sc.end_time;
        end if;

        insert into opd.appointment_slot (
          tenant_id, session_config_id, doctor_id, slot_date,
          session_code, start_time, end_time, state,
          is_walkin_quota, created_by
        )
        select
          p_tenant_id, sc.id, sc.doctor_id, d.dt,
          sc.session_code, slot_start, slot_end, 'FREE',
          (walkin_count < walkin_quota), p_created_by
        where not exists (
          select 1 from opd.appointment_slot a
          where a.tenant_id = p_tenant_id
            and a.session_config_id = sc.id
            and a.slot_date = d.dt
            and a.start_time = slot_start
        );

        if walkin_count < walkin_quota then
          walkin_count := walkin_count + 1;
        end if;

        slot_start := slot_end;
      end loop;
    end loop;
  end loop;
end;
$$;

grant execute on function opd.generate_slots_for_date_range(uuid, date, date, uuid) to sahaj_app;

-- Function to mark past slots
create or replace function opd.mark_past_slots(p_tenant_id uuid) returns int
language plpgsql
security definer
set search_path = opd, pg_catalog
as $$
declare
  updated_count int;
begin
  update opd.appointment_slot
  set state = 'PAST', updated_at = now(), updated_by = current_setting('app.current_user_id', true)::uuid
  where tenant_id = p_tenant_id
    and slot_date < current_date
    and state = 'FREE'
    returning 1 into updated_count;

  return coalesce(updated_count, 0);
end;
$$;

grant execute on function opd.mark_past_slots(uuid) to sahaj_app;

-- Function to get next token number for today
create or replace function opd.next_token_number(p_tenant_id uuid) returns int
language plpgsql
security definer
set search_path = opd, pg_catalog
as $$
declare
  next_num int;
begin
  select coalesce(max(token_number), 0) + 1
  into next_num
  from opd.visit
  where tenant_id = p_tenant_id
    and arrived_at::date = current_date;

  return next_num;
end;
$$;

grant execute on function opd.next_token_number(uuid) to sahaj_app;

-- Trigger to maintain version on update
create or replace function opd.tg_bump_version()
returns trigger
language plpgsql
as $$
begin
  new.version := old.version + 1;
  new.updated_at := now();
  return new;
end;
$$;

create trigger trg_session_config_version
before update on opd.session_config
for each row execute function opd.tg_bump_version();

create trigger trg_appt_slot_version
before update on opd.appointment_slot
for each row execute function opd.tg_bump_version();

create trigger trg_booking_rules_version
before update on opd.booking_rules
for each row execute function opd.tg_bump_version();

create trigger trg_visit_version
before update on opd.visit
for each row execute function opd.tg_bump_version();