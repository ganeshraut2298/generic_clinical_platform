-- SAH-21 · Leave requests (HRA add-on, part 2).
-- membership_id references platform.membership logically only (no cross-schema FK);
-- the app validates the member. decided_by likewise.

create table if not exists hra.leave_request (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_leave_request_tenant references platform.tenant (id),
  membership_id uuid not null,
  leave_type    text not null
                constraint ck_leave_request_type check (leave_type in ('CL', 'SL', 'EL')),
  from_date     date not null,
  to_date       date not null,
  reason        text
                constraint ck_leave_request_reason check (reason is null or char_length(reason) <= 200),
  status        text not null default 'pending'
                constraint ck_leave_request_status check (status in ('pending', 'approved', 'declined')),
  created_at    timestamptz not null default now(),
  created_by    uuid not null,
  decided_at    timestamptz,
  decided_by    uuid,
  version       bigint not null default 0,
  constraint ck_leave_request_dates check (to_date >= from_date),
  constraint ck_leave_request_span check (to_date - from_date <= 60),
  constraint ck_leave_request_decided check (
    (status = 'pending' and decided_at is null and decided_by is null)
    or (status <> 'pending' and decided_at is not null and decided_by is not null))
);

-- Overlap check, own-requests list and balance per member.
create index if not exists ix_leave_request_tenant_member_from
  on hra.leave_request (tenant_id, membership_id, from_date);
-- Pending count tile and approved-leave lookups by status.
create index if not exists ix_leave_request_tenant_status
  on hra.leave_request (tenant_id, status);

alter table hra.leave_request enable row level security;
alter table hra.leave_request force row level security;

create policy p_leave_request_tenant on hra.leave_request
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

revoke all on hra.leave_request from sahaj_app;
revoke all on hra.leave_request from public;
grant select, insert, update on hra.leave_request to sahaj_app;

comment on table hra.leave_request is
  'Leave request (SAH-21): CL/SL/EL, inclusive date range, pending until the owner approves or declines; decided rows are immutable in the app. Never deleted. Reason is free text: never logged or audited.';
