-- SAH-18 · Alerts and escalation rules (storage only; the rule engine is a later release).
-- opd.alert_rule       : one row per rule "if when_step is cond then then_action".
-- opd.alert_rule_state : marker that the starter rules were materialised, so deleting all rules
--                        does not bring them back. Starter rules are written by the app on the first write.
create table if not exists opd.alert_rule (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null default platform.current_tenant()
               constraint fk_alert_rule_tenant references platform.tenant (id),
  when_step    text not null
               constraint ck_alert_rule_when_step check (char_length(when_step) between 1 and 120),
  cond         text not null
               constraint ck_alert_rule_cond check (char_length(cond) between 1 and 120),
  then_action  text not null
               constraint ck_alert_rule_then_action check (char_length(then_action) between 1 and 120),
  enabled      boolean not null default true,
  created_at   timestamptz not null default now(),
  created_by   uuid not null,
  updated_at   timestamptz,
  updated_by   uuid,
  version      bigint not null default 0,
  constraint uq_alert_rule_tenant_rule unique (tenant_id, when_step, cond, then_action)
);
-- The unique constraint's index (tenant_id first) serves the tenant list and the duplicate check.
create index if not exists ix_alert_rule_tenant_created
  on opd.alert_rule (tenant_id, created_at);

alter table opd.alert_rule enable row level security;
alter table opd.alert_rule force row level security;

create policy p_alert_rule_tenant on opd.alert_rule
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- Configuration, not clinical or billing data: the owner may delete a rule (audited by the app).
revoke all on opd.alert_rule from sahaj_app;
revoke all on opd.alert_rule from public;
grant select, insert, update, delete on opd.alert_rule to sahaj_app;

comment on table opd.alert_rule is
  'Clinic alert and escalation rules (SAH-18): when_step + cond -> then_action, enabled flag. Values validated by the API against the builder and suggestion lists. Not evaluated yet.';

create table if not exists opd.alert_rule_state (
  tenant_id  uuid primary key default platform.current_tenant()
             constraint fk_alert_rule_state_tenant references platform.tenant (id),
  seeded_at  timestamptz not null default now()
);

alter table opd.alert_rule_state enable row level security;
alter table opd.alert_rule_state force row level security;

create policy p_alert_rule_state_tenant on opd.alert_rule_state
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- Marker is written once and kept.
revoke all on opd.alert_rule_state from sahaj_app;
revoke all on opd.alert_rule_state from public;
grant select, insert on opd.alert_rule_state to sahaj_app;

comment on table opd.alert_rule_state is
  'One row per tenant once the five starter alert rules were saved (SAH-18); stops them returning after the owner deletes all rules.';
