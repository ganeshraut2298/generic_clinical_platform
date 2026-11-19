-- SAH-78 WP-C (story 78-9), migration C2 of 8. ADR-028 D7 "Approval engine shape": the RULES only (the runtime is Phase 2).
-- Tables: pharmacy.approval_rule_version, pharmacy.approval_rule, pharmacy.approval_rule_level (all three immutable: select and
-- insert only, no update, no delete) and pharmacy.approval_rule_set (one row per unit pointing at the current version).
-- Every save writes a new version. No rule is stored before the first save (the service serves defaults and a "review these rules"
-- banner). Sahaj ships no rupee amounts (DEC-062 Q4). Amounts are bigint paise; percentages numeric(5,2).

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.approval_rule_version: immutable. high_value_threshold_paise is the "high-value item" amount used by the condition
--    HIGH_VALUE_ITEM (null = not set).
-- ======================================================================================================================
create table if not exists pharmacy.approval_rule_version (
  id                          uuid primary key default gen_random_uuid(),
  tenant_id                   uuid not null default platform.current_tenant()
                                constraint fk_approval_rule_version_tenant references platform.tenant (id),
  unit_id                     uuid not null,
  version_no                  integer not null
                                constraint ck_approval_rule_version_no check (version_no >= 1),
  preset                      text not null
                                constraint ck_approval_rule_version_preset check (preset in ('LEAN', 'STANDARD', 'STRICT', 'CUSTOM')),
  high_value_threshold_paise  bigint
                                constraint ck_approval_rule_version_high_value check (high_value_threshold_paise between 0 and 99999999900),
  created_at                  timestamptz not null default now(),
  created_by                  uuid not null,
  constraint uq_approval_rule_version_no unique (tenant_id, unit_id, version_no),
  constraint uq_approval_rule_version_tenant_id unique (tenant_id, id),
  constraint fk_approval_rule_version_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id)
);

alter table pharmacy.approval_rule_version enable row level security;
alter table pharmacy.approval_rule_version force row level security;
create policy p_approval_rule_version_select on pharmacy.approval_rule_version for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_approval_rule_version_insert on pharmacy.approval_rule_version for insert to sahaj_app with check (tenant_id = platform.current_tenant());
-- Immutable: select and insert only. No update and no delete grant or policy.
revoke all on pharmacy.approval_rule_version from sahaj_app;
revoke all on pharmacy.approval_rule_version from public;
grant select, insert on pharmacy.approval_rule_version to sahaj_app;

comment on table pharmacy.approval_rule_version is
  'Immutable version of the approval rules of a unit (SAH-78-9, ADR-028 D7). Numbered 1, 2, ... per unit. Kept for good (an approval request in Phase 2 snapshots the version it was raised under).';

-- ======================================================================================================================
-- 2. pharmacy.approval_rule: one per transaction type per version
-- ======================================================================================================================
create table if not exists pharmacy.approval_rule (
  id                              uuid primary key default gen_random_uuid(),
  tenant_id                       uuid not null default platform.current_tenant()
                                    constraint fk_approval_rule_tenant references platform.tenant (id),
  unit_id                         uuid not null,
  version_id                      uuid not null,
  transaction_type                text not null
                                    constraint ck_approval_rule_type check (transaction_type in
                                      ('PURCHASE_ORDER', 'GRN_RATE_RISE', 'VENDOR_RETURN', 'INDENT', 'STOCK_TRANSFER',
                                       'WRITE_OFF', 'STOCK_ADJUSTMENT', 'SALE_DISCOUNT', 'SALES_RETURN')),
  escalate_after_hours            integer
                                    constraint ck_approval_rule_escalate_hours check (escalate_after_hours between 1 and 168),
  urgent_escalate_after_minutes   integer
                                    constraint ck_approval_rule_urgent_minutes check (urgent_escalate_after_minutes between 5 and 1440),
  created_at                      timestamptz not null default now(),
  created_by                      uuid not null,
  constraint uq_approval_rule_type unique (tenant_id, version_id, transaction_type),
  constraint uq_approval_rule_tenant_id unique (tenant_id, id),
  constraint fk_approval_rule_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_approval_rule_version foreign key (tenant_id, version_id) references pharmacy.approval_rule_version (tenant_id, id),
  -- The urgent escalation time belongs to the indent only.
  constraint ck_approval_rule_urgent_indent_only check (urgent_escalate_after_minutes is null or transaction_type = 'INDENT')
);

alter table pharmacy.approval_rule enable row level security;
alter table pharmacy.approval_rule force row level security;
create policy p_approval_rule_select on pharmacy.approval_rule for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_approval_rule_insert on pharmacy.approval_rule for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.approval_rule from sahaj_app;
revoke all on pharmacy.approval_rule from public;
grant select, insert on pharmacy.approval_rule to sahaj_app;

comment on table pharmacy.approval_rule is
  'Rule of one transaction type inside an immutable version (SAH-78-9). Select and insert only.';

-- ======================================================================================================================
-- 3. pharmacy.approval_rule_level: level 1 or 2 of a rule, with one condition
-- ======================================================================================================================
create table if not exists pharmacy.approval_rule_level (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null default platform.current_tenant()
                     constraint fk_approval_rule_level_tenant references platform.tenant (id),
  unit_id          uuid not null,
  rule_id          uuid not null,
  level_no         smallint not null
                     constraint ck_approval_rule_level_no check (level_no in (1, 2)),
  approver_kind    text not null
                     constraint ck_approval_rule_level_approver check (approver_kind in ('STORE_APPROVER', 'OWNER')),
  condition_kind   text not null
                     constraint ck_approval_rule_level_condition check (condition_kind in
                       ('ALWAYS', 'ABOVE_AMOUNT', 'ABOVE_PERCENT', 'SCHEDULE_X_ITEM', 'HIGH_VALUE_ITEM', 'URGENT_PRIORITY', 'STORE_TYPE')),
  amount_paise     bigint
                     constraint ck_approval_rule_level_amount check (amount_paise between 0 and 99999999900),
  percent          numeric(5,2)
                     constraint ck_approval_rule_level_percent check (percent between 0 and 100),
  store_types      text[]
                     constraint ck_approval_rule_level_store_types check (
                       store_types is null
                       or (cardinality(store_types) between 1 and 4
                           and store_types <@ array['CENTRAL', 'PHARMACY', 'SUB_STORE', 'WARD']::text[])),
  created_at       timestamptz not null default now(),
  created_by       uuid not null,
  constraint uq_approval_rule_level unique (tenant_id, rule_id, level_no),
  constraint fk_approval_rule_level_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_approval_rule_level_rule foreign key (tenant_id, rule_id) references pharmacy.approval_rule (tenant_id, id),
  -- The value of a condition is present exactly for its kind.
  constraint ck_approval_rule_level_amount_kind check ((condition_kind = 'ABOVE_AMOUNT') = (amount_paise is not null)),
  constraint ck_approval_rule_level_percent_kind check ((condition_kind = 'ABOVE_PERCENT') = (percent is not null)),
  constraint ck_approval_rule_level_store_types_kind check ((condition_kind = 'STORE_TYPE') = (store_types is not null))
);

alter table pharmacy.approval_rule_level enable row level security;
alter table pharmacy.approval_rule_level force row level security;
create policy p_approval_rule_level_select on pharmacy.approval_rule_level for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_approval_rule_level_insert on pharmacy.approval_rule_level for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.approval_rule_level from sahaj_app;
revoke all on pharmacy.approval_rule_level from public;
grant select, insert on pharmacy.approval_rule_level to sahaj_app;

comment on table pharmacy.approval_rule_level is
  'Level 1 or 2 of an approval rule with one condition (SAH-78-9). Amounts in paise. Select and insert only. ABOVE_PERCENT is used by SALE_DISCOUNT and URGENT_PRIORITY by INDENT (service checks).';

-- ======================================================================================================================
-- 4. pharmacy.approval_rule_set: one row per unit, pointing at the current version. The only updatable table of the four.
-- ======================================================================================================================
create table if not exists pharmacy.approval_rule_set (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null default platform.current_tenant()
                         constraint fk_approval_rule_set_tenant references platform.tenant (id),
  unit_id              uuid not null,
  current_version_id   uuid not null,
  created_at           timestamptz not null default now(),
  created_by           uuid not null,
  updated_at           timestamptz,
  updated_by           uuid,
  version              bigint not null default 0,
  constraint uq_approval_rule_set_unit unique (tenant_id, unit_id),
  constraint fk_approval_rule_set_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_approval_rule_set_version foreign key (tenant_id, current_version_id)
    references pharmacy.approval_rule_version (tenant_id, id)
);

alter table pharmacy.approval_rule_set enable row level security;
alter table pharmacy.approval_rule_set force row level security;
create policy p_approval_rule_set_select on pharmacy.approval_rule_set for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_approval_rule_set_insert on pharmacy.approval_rule_set for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_approval_rule_set_update on pharmacy.approval_rule_set for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.approval_rule_set from sahaj_app;
revoke all on pharmacy.approval_rule_set from public;
grant select, insert, update on pharmacy.approval_rule_set to sahaj_app;

comment on table pharmacy.approval_rule_set is
  'Pointer to the current approval rule version of a unit (SAH-78-9). Created lazily with version 1; updated on every save. Never deleted.';
