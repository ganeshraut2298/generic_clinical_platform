-- SAH-1 · platform.audit_event : append-only trail of who changed what, when, in which tenant.
-- Never store patient names, mobile numbers or OTPs here; mask a mobile to its last 4 digits.
-- No foreign key from entity_id: it is an opaque reference that may point into any module.
create table if not exists platform.audit_event (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_audit_event_tenant references platform.tenant (id),
  actor_user_id uuid,
  action        text not null
                constraint ck_audit_event_action check (char_length(action) between 1 and 100),
  entity_type   text not null
                constraint ck_audit_event_entity_type check (char_length(entity_type) between 1 and 100),
  entity_id     uuid,
  before_json   jsonb,
  after_json    jsonb,
  at            timestamptz not null default now()
);

create index if not exists ix_audit_event_tenant_at on platform.audit_event (tenant_id, at desc);
create index if not exists ix_audit_event_tenant_entity on platform.audit_event (tenant_id, entity_type, entity_id, at desc);

alter table platform.audit_event enable row level security;
alter table platform.audit_event force row level security;

-- One SELECT policy and one INSERT policy. No UPDATE, DELETE or ALL policy exists, so under
-- row level security those commands are denied even if a privilege is granted by mistake.
create policy p_audit_event_select on platform.audit_event
  for select to sahaj_app
  using (tenant_id = platform.current_tenant());

create policy p_audit_event_insert on platform.audit_event
  for insert to sahaj_app
  with check (tenant_id = platform.current_tenant());

-- Privileges: schema default privileges give sahaj_app all four commands, so take everything away first.
revoke all on platform.audit_event from sahaj_app;
revoke all on platform.audit_event from public;
grant select, insert on platform.audit_event to sahaj_app;

-- Third layer: a trigger that refuses UPDATE, DELETE and TRUNCATE for every role, the owner included.
create or replace function platform.deny_audit_mutation()
returns trigger
language plpgsql
as $$
begin
  raise exception 'platform.audit_event is append-only (% refused)', tg_op
    using errcode = 'insufficient_privilege';
end;
$$;

create trigger trg_audit_event_no_update_delete
  before update or delete on platform.audit_event
  for each row execute function platform.deny_audit_mutation();

create trigger trg_audit_event_no_truncate
  before truncate on platform.audit_event
  for each statement execute function platform.deny_audit_mutation();
