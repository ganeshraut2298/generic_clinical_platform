-- SAH-20 · Staff roster, publish marker and daily attendance (HRA add-on, part 1).
-- membership_id references platform.membership logically only: project rule allows cross-schema
-- foreign keys only to platform.tenant and patient.patient; the app validates the member.

-- hra.roster_shift : one row per member and day; absent row means Off.
create table if not exists hra.roster_shift (
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_roster_shift_tenant references platform.tenant (id),
  membership_id uuid not null,
  work_date     date not null,
  shift         char(1) not null
                constraint ck_roster_shift_shift check (shift in ('M', 'E', 'N', 'X')),
  updated_at    timestamptz not null default now(),
  updated_by    uuid not null,
  constraint pk_roster_shift primary key (tenant_id, membership_id, work_date)
);
-- The PK (tenant first) serves the 7-day roster read: tenant + date range.
create index if not exists ix_roster_shift_tenant_date
  on hra.roster_shift (tenant_id, work_date);

alter table hra.roster_shift enable row level security;
alter table hra.roster_shift force row level security;

create policy p_roster_shift_tenant on hra.roster_shift
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

revoke all on hra.roster_shift from sahaj_app;
revoke all on hra.roster_shift from public;
grant select, insert, update, delete on hra.roster_shift to sahaj_app;

comment on table hra.roster_shift is
  'Weekly roster cell (SAH-20): shift M morning, E evening, N night, X off for a team member on a date. Absent row = Off. Scheduling data, deletable.';

-- hra.roster_state : lazy per-clinic publish marker.
create table if not exists hra.roster_state (
  tenant_id    uuid primary key default platform.current_tenant()
               constraint fk_roster_state_tenant references platform.tenant (id),
  published_at timestamptz,
  published_by uuid
);

alter table hra.roster_state enable row level security;
alter table hra.roster_state force row level security;

create policy p_roster_state_tenant on hra.roster_state
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

revoke all on hra.roster_state from sahaj_app;
revoke all on hra.roster_state from public;
grant select, insert, update on hra.roster_state to sahaj_app;

comment on table hra.roster_state is
  'Roster publish marker (SAH-20): one row per clinic once published; absent = not published. Never deleted.';

-- hra.attendance : one row per member and IST day; same-day changes update the row, no deletes.
create table if not exists hra.attendance (
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_attendance_tenant references platform.tenant (id),
  membership_id uuid not null,
  work_date     date not null,
  status        text not null
                constraint ck_attendance_status check (status in ('in', 'late', 'abs')),
  marked_time   time,
  marked_by     uuid not null,
  marked_at     timestamptz not null default now(),
  version       bigint not null default 0,
  constraint pk_attendance primary key (tenant_id, membership_id, work_date),
  constraint ck_attendance_marked_time check (
    (status = 'abs' and marked_time is null) or (status in ('in', 'late') and marked_time is not null))
);
-- Today's sheet reads tenant + date.
create index if not exists ix_attendance_tenant_date
  on hra.attendance (tenant_id, work_date);

alter table hra.attendance enable row level security;
alter table hra.attendance force row level security;

create policy p_attendance_tenant on hra.attendance
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

revoke all on hra.attendance from sahaj_app;
revoke all on hra.attendance from public;
grant select, insert, update on hra.attendance to sahaj_app;

comment on table hra.attendance is
  'Daily attendance (SAH-20): status in/late/abs per team member and IST date; marked_time is IST wall clock (null for abs). Same-day updates allowed; never deleted. Ids only, no personal data.';
