-- SAH-79 WP-D (story 79-2), migration D4. ADR-029 P6 (approval engine runtime), "Data needed" D4, ADR-028 D7.
-- New tables: pharmacy.approval_request, pharmacy.approval_request_level (copy of the rule kept at submission), pharmacy.approval_decision
-- (append-only). Tenant tables with forced row level security, no delete grant. The rule tables of SAH-78 are not changed.
-- Fixed rules held by the database: nobody approves their own request (decided_by <> raised_by); one decision per level; the same person
-- cannot approve both levels; one open request per document; a decided request is final; decisions are append-only.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.approval_request
-- ======================================================================================================================
create table if not exists pharmacy.approval_request (
  id                       uuid primary key default gen_random_uuid(),
  tenant_id                uuid not null default platform.current_tenant()
                             constraint fk_approval_request_tenant references platform.tenant (id),
  unit_id                  uuid not null,
  transaction_type         text not null
                             constraint ck_approval_request_type check (transaction_type in
                               ('PURCHASE_ORDER', 'GRN_RATE_RISE', 'VENDOR_RETURN', 'INDENT', 'STOCK_TRANSFER',
                                'WRITE_OFF', 'STOCK_ADJUSTMENT', 'SALE_DISCOUNT', 'SALES_RETURN')),
  subject_type             text not null
                             constraint ck_approval_request_subject_type check (subject_type in
                               ('PURCHASE_ORDER', 'GOODS_RECEIPT', 'DEBIT_NOTE', 'INDENT', 'TRANSFER', 'DAMAGE_REPORT',
                                'SHORTAGE', 'STOCK_ADJUSTMENT', 'STOCK_AUDIT')),
  -- The document (no foreign key: it is one of several tables, chosen by subject_type).
  subject_id               uuid not null,
  -- The number shown; null for a receipt waiting for rate-rise approval (numbered at posting).
  subject_no               text
                             constraint ck_approval_request_subject_no check (char_length(subject_no) between 1 and 40),
  -- The store whose HOD is the "store approver" (BR-14).
  rule_store_id            uuid not null,
  raised_by                uuid not null,
  raised_at                timestamptz not null default now(),
  -- The rule version used (BR-12); a later rule change does not touch this request.
  rule_version_id          uuid not null,
  amount_paise             bigint not null
                             constraint ck_approval_request_amount check (amount_paise >= 0),
  has_schedule_x           boolean not null default false,
  has_high_value_item      boolean not null default false,
  urgent                   boolean not null default false,
  -- GRN rate rise only. Not capped at 100: a rate can more than double (numeric(9,2): 0 to 9,999,999.99).
  max_rate_rise_percent    numeric(9,2)
                             constraint ck_approval_request_rise check (max_rate_rise_percent >= 0),
  -- Resolved at submit: urgent minutes for an urgent indent, else hours x 60, else null (no escalation).
  escalate_after_minutes   integer
                             constraint ck_approval_request_escalate check (escalate_after_minutes > 0),
  current_level            smallint
                             constraint ck_approval_request_current_level check (current_level in (1, 2)),
  -- When the request reached its current level (escalation of level 2 counts from here); set by the service, starts at raised_at.
  level_entered_at         timestamptz not null default now(),
  status                   text not null default 'PENDING'
                             constraint ck_approval_request_status check (status in ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
  created_at               timestamptz not null default now(),
  created_by               uuid not null,
  updated_at               timestamptz,
  updated_by               uuid,
  version                  bigint not null default 0,
  constraint uq_approval_request_tenant_id unique (tenant_id, id),
  constraint fk_approval_request_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_approval_request_store foreign key (tenant_id, rule_store_id) references pharmacy.store (tenant_id, id),
  constraint fk_approval_request_rule_version foreign key (tenant_id, rule_version_id)
    references pharmacy.approval_rule_version (tenant_id, id),
  -- A level is current exactly while the request is pending.
  constraint ck_approval_request_level_pending check ((status = 'PENDING') = (current_level is not null)),
  constraint ck_approval_request_raiser check (created_by = raised_by)
);

-- One open request per document.
create unique index if not exists uq_approval_request_one_pending on pharmacy.approval_request (tenant_id, subject_type, subject_id)
  where status = 'PENDING';
-- Inbox: pending requests of the unit by rule store (the approver set is worked out in the service per store).
create index if not exists ix_approval_request_inbox on pharmacy.approval_request (tenant_id, status, rule_store_id);
-- The request of a document (document screens, history, "which request decided this").
create index if not exists ix_approval_request_subject on pharmacy.approval_request (tenant_id, subject_type, subject_id);

alter table pharmacy.approval_request enable row level security;
alter table pharmacy.approval_request force row level security;
create policy p_approval_request_select on pharmacy.approval_request for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_approval_request_insert on pharmacy.approval_request for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_approval_request_update on pharmacy.approval_request for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.approval_request from sahaj_app;
revoke all on pharmacy.approval_request from public;
grant select, insert, update on pharmacy.approval_request to sahaj_app;

-- A decided request is final; what the request is about never changes; the level only goes up; withdrawal and decisions leave PENDING once.
create or replace function pharmacy.guard_approval_request_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if old.status <> 'PENDING' then
    raise exception 'approval request is final (%)', old.status using errcode = 'check_violation';
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.transaction_type is distinct from old.transaction_type or new.subject_type is distinct from old.subject_type
     or new.subject_id is distinct from old.subject_id or new.rule_store_id is distinct from old.rule_store_id
     or new.raised_by is distinct from old.raised_by or new.raised_at is distinct from old.raised_at
     or new.rule_version_id is distinct from old.rule_version_id or new.amount_paise is distinct from old.amount_paise
     or new.has_schedule_x is distinct from old.has_schedule_x or new.has_high_value_item is distinct from old.has_high_value_item
     or new.urgent is distinct from old.urgent or new.max_rate_rise_percent is distinct from old.max_rate_rise_percent
     or new.escalate_after_minutes is distinct from old.escalate_after_minutes
     or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
    raise exception 'approval request identity cannot change' using errcode = 'check_violation';
  end if;
  if new.status = 'PENDING' and new.current_level < old.current_level then
    raise exception 'approval request level cannot go back' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_approval_request_update() from public;
revoke all on function pharmacy.guard_approval_request_update() from sahaj_app;
create trigger trg_approval_request_guard_update before update on pharmacy.approval_request
  for each row execute function pharmacy.guard_approval_request_update();

comment on table pharmacy.approval_request is
  'One approval request for one document (SAH-79-2, ADR-029 P6): the rule version, amount and flags kept at submission, the current level and the status. At most one PENDING request per document. Select, insert, update; a decided request is final (trigger); never deleted. If no rule level applies no request is written (level 0). Who may decide now is worked out on every read from store mapping and team roles, never stored per person. No patient data.';
comment on column pharmacy.approval_request.raised_by is
  'Membership id (opaque uuid) of the person who submitted the document. Never approves it (BR-13): see approval_decision.';
comment on column pharmacy.approval_request.amount_paise is
  'BR-17 / OQ-79-36: purchase order = total including GST; other types = sum of quantity x purchase rate per issue unit, without GST; GRN rate rise = value of the lines whose rate rose.';
comment on column pharmacy.approval_request.level_entered_at is
  'When the request reached current_level. Escalation (BR-16) is computed at read time from raised_at (level 1) or this time (level 2) plus escalate_after_minutes; no job.';

-- ======================================================================================================================
-- 2. pharmacy.approval_request_level: the copy of the rule's levels kept at submission (BR-12). Select and insert only.
-- ======================================================================================================================
create table if not exists pharmacy.approval_request_level (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null default platform.current_tenant()
                     constraint fk_approval_request_level_tenant references platform.tenant (id),
  unit_id          uuid not null,
  request_id       uuid not null,
  level_no         smallint not null
                     constraint ck_approval_request_level_no check (level_no in (1, 2)),
  approver_kind    text not null
                     constraint ck_approval_request_level_approver check (approver_kind in ('STORE_APPROVER', 'OWNER')),
  condition_kind   text not null
                     constraint ck_approval_request_level_condition check (condition_kind in
                       ('ALWAYS', 'ABOVE_AMOUNT', 'ABOVE_PERCENT', 'SCHEDULE_X_ITEM', 'HIGH_VALUE_ITEM', 'URGENT_PRIORITY', 'STORE_TYPE')),
  amount_paise     bigint
                     constraint ck_approval_request_level_amount check (amount_paise between 0 and 99999999900),
  percent          numeric(5,2)
                     constraint ck_approval_request_level_percent check (percent between 0 and 100),
  store_types      text[]
                     constraint ck_approval_request_level_store_types check (
                       store_types is null
                       or (cardinality(store_types) between 1 and 4
                           and store_types <@ array['CENTRAL', 'PHARMACY', 'SUB_STORE', 'WARD']::text[])),
  -- The condition's result at submit (a level that did not apply is kept so the screen can show why).
  applies          boolean not null,
  -- The "Rule" column of the inbox and the document, for example "PO above Rs 50,000", "Rate up more than 5%", "Sending store HOD".
  rule_text        text not null
                     constraint ck_approval_request_level_rule_text check (char_length(rule_text) between 1 and 120),
  created_at       timestamptz not null default now(),
  created_by       uuid not null,
  constraint uq_approval_request_level unique (tenant_id, request_id, level_no),
  constraint fk_approval_request_level_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_approval_request_level_request foreign key (tenant_id, request_id) references pharmacy.approval_request (tenant_id, id),
  constraint ck_approval_request_level_amount_kind check ((condition_kind = 'ABOVE_AMOUNT') = (amount_paise is not null)),
  constraint ck_approval_request_level_percent_kind check ((condition_kind = 'ABOVE_PERCENT') = (percent is not null)),
  constraint ck_approval_request_level_store_types_kind check ((condition_kind = 'STORE_TYPE') = (store_types is not null))
);

alter table pharmacy.approval_request_level enable row level security;
alter table pharmacy.approval_request_level force row level security;
create policy p_approval_request_level_select on pharmacy.approval_request_level for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_approval_request_level_insert on pharmacy.approval_request_level for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.approval_request_level from sahaj_app;
revoke all on pharmacy.approval_request_level from public;
grant select, insert on pharmacy.approval_request_level to sahaj_app;

comment on table pharmacy.approval_request_level is
  'Levels of the rule as they were when the request was submitted (SAH-79-2, BR-12), with whether each applied and its rule text. Select and insert only, never deleted. A rule change later does not touch these rows.';

-- ======================================================================================================================
-- 3. pharmacy.approval_decision: append-only. The fixed rule BR-13 is a check on the row.
-- ======================================================================================================================
create table if not exists pharmacy.approval_decision (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null default platform.current_tenant()
                  constraint fk_approval_decision_tenant references platform.tenant (id),
  unit_id       uuid not null,
  request_id    uuid not null,
  level_no      smallint not null
                  constraint ck_approval_decision_level check (level_no in (1, 2)),
  decision      text not null
                  constraint ck_approval_decision_decision check (decision in ('APPROVED', 'REJECTED')),
  decided_by    uuid not null,
  -- A copy of the request's raised_by, so the "not your own request" check is local to the row; a trigger keeps it equal to the request's.
  raised_by     uuid not null,
  acting_as     text not null
                  constraint ck_approval_decision_acting_as check (acting_as in ('HOD', 'DELEGATE', 'OWNER', 'ESCALATED')),
  reason_key    text
                  constraint ck_approval_decision_reason check (reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  note          text
                  constraint ck_approval_decision_note check (char_length(note) between 1 and 300),
  decided_at    timestamptz not null default clock_timestamp(),
  constraint fk_approval_decision_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_approval_decision_request foreign key (tenant_id, request_id) references pharmacy.approval_request (tenant_id, id),
  -- Fixed rule (BR-13): nobody decides their own request.
  constraint ck_approval_decision_not_self check (decided_by <> raised_by),
  -- A rejection always carries a reason (list approval-reject).
  constraint ck_approval_decision_reject_reason check (decision <> 'REJECTED' or reason_key is not null),
  -- One final decision per level.
  constraint uq_approval_decision_level unique (tenant_id, request_id, level_no)
);

-- The same person cannot approve both levels (OQ-79-28).
create unique index if not exists uq_approval_decision_one_approval_each on pharmacy.approval_decision (tenant_id, request_id, decided_by)
  where decision = 'APPROVED';

alter table pharmacy.approval_decision enable row level security;
alter table pharmacy.approval_decision force row level security;
create policy p_approval_decision_select on pharmacy.approval_decision for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_approval_decision_insert on pharmacy.approval_decision for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.approval_decision from sahaj_app;
revoke all on pharmacy.approval_decision from public;
grant select, insert on pharmacy.approval_decision to sahaj_app;

create trigger trg_approval_decision_no_update_delete before update or delete on pharmacy.approval_decision
  for each row execute function pharmacy.deny_append_only_mutation();
create trigger trg_approval_decision_no_truncate before truncate on pharmacy.approval_decision
  for each statement execute function pharmacy.deny_append_only_mutation();

-- raised_by on a decision must be the request's raised_by (else the copy could hide a self-approval). A request that is missing or
-- not visible is left to the foreign key (23503). Does not read the request's status or level: the service locks the request and checks them.
create or replace function pharmacy.guard_approval_decision_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_raised_by uuid;
begin
  select r.raised_by into v_raised_by
    from pharmacy.approval_request r
   where r.tenant_id = new.tenant_id and r.id = new.request_id;
  if found and v_raised_by is distinct from new.raised_by then
    raise exception 'approval decision raised_by must match the request' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_approval_decision_insert() from public;
revoke all on function pharmacy.guard_approval_decision_insert() from sahaj_app;
create trigger trg_approval_decision_guard_insert before insert on pharmacy.approval_decision
  for each row execute function pharmacy.guard_approval_decision_insert();

comment on table pharmacy.approval_decision is
  'Decisions on approval requests (SAH-79-2, ADR-029 P6). Append-only: select and insert only; triggers refuse update, delete and truncate; never deleted. Held by the database: decided_by <> raised_by (BR-13), one decision per request and level, one APPROVED per person per request (OQ-79-28), a rejection has a reason. decided_by and raised_by are membership ids (opaque uuids).';
comment on column pharmacy.approval_decision.acting_as is
  'Capacity: HOD, DELEGATE, OWNER, or ESCALATED (an owner deciding an item escalated past its HOD).';
comment on column pharmacy.approval_decision.decided_at is
  'Database clock at the insert (clock_timestamp).';
