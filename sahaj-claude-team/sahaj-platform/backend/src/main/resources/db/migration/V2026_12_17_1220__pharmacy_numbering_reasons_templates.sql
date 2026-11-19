-- SAH-78 WP-C (story 78-10), migration C3 of 8. ADR-028 D8 (numbering), "Data needed" (reasons, print templates).
-- Tables: pharmacy.document_series, pharmacy.document_counter, pharmacy.reason_default (GLOBAL, seeded in every environment),
-- pharmacy.reason, pharmacy.reason_default_state, pharmacy.print_template_setting. Function: pharmacy.next_document_no(text).
-- All new. Tenant tables have forced row level security and no delete grant. reason_default follows the ipd.drug pattern
-- (see migration B1). Phase 1 takes no document number: the function exists for SAH-79 and has no HTTP operation.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.document_series: how a document type is numbered. Lazy: until a row is saved the service serves the defaults
--    (PO-0001, GRN-0001, IND-0001, TRF-0001, SB/26-27/000001).
-- ======================================================================================================================
create table if not exists pharmacy.document_series (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null default platform.current_tenant()
                         constraint fk_document_series_tenant references platform.tenant (id),
  unit_id              uuid not null,
  doc_type             text not null
                         constraint ck_document_series_doc_type check (doc_type in ('PURCHASE_ORDER', 'GRN', 'INDENT', 'TRANSFER', 'BILL')),
  prefix               text not null
                         constraint ck_document_series_prefix check (prefix ~ '^[A-Za-z0-9/-]{1,6}$'),
  financial_year_part  boolean not null default false,
  sequence_length      integer not null
                         constraint ck_document_series_sequence_length check (sequence_length between 4 and 8),
  restarts_each_fy     boolean not null default false,
  created_at           timestamptz not null default now(),
  created_by           uuid not null,
  updated_at           timestamptz,
  updated_by           uuid,
  version              bigint not null default 0,
  constraint uq_document_series_type unique (tenant_id, unit_id, doc_type),
  constraint fk_document_series_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  -- Restarting each 1 April needs the financial year in the number, so numbers stay unique.
  constraint ck_document_series_restart_needs_fy check (not restarts_each_fy or financial_year_part),
  -- Rendered number: prefix + "26-27/" (6 characters) when the financial-year part is on + the sequence; at most 16 (DEC-062 Q4).
  constraint ck_document_series_rendered_length check (
    char_length(prefix) + case when financial_year_part then 6 else 0 end + sequence_length <= 16)
);

-- No two document types of a unit share a prefix, ignoring case (R2-10; avoids identical numbers across types).
create unique index if not exists uq_document_series_prefix on pharmacy.document_series (tenant_id, unit_id, lower(prefix));

alter table pharmacy.document_series enable row level security;
alter table pharmacy.document_series force row level security;
create policy p_document_series_select on pharmacy.document_series for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_document_series_insert on pharmacy.document_series for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_document_series_update on pharmacy.document_series for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.document_series from sahaj_app;
revoke all on pharmacy.document_series from public;
grant select, insert, update on pharmacy.document_series to sahaj_app;

comment on table pharmacy.document_series is
  'Numbering series per document type of a unit (SAH-78-10, ADR-028 D8). Lazy: defaults are served until saved. Never deleted. Editing after numbers are taken is a SAH-79 rule.';

-- ======================================================================================================================
-- 2. pharmacy.document_counter + pharmacy.next_document_no(): gapless for committed documents, race-free.
--    next_value is the NEXT number to hand out. fiscal_year is the calendar year in which the April-March year starts
--    (2026 for 1 Apr 2026 to 31 Mar 2027), or 0 when the series never restarts.
-- ======================================================================================================================
create table if not exists pharmacy.document_counter (
  tenant_id    uuid not null default platform.current_tenant()
                 constraint fk_document_counter_tenant references platform.tenant (id),
  unit_id      uuid not null,
  doc_type     text not null
                 constraint ck_document_counter_doc_type check (doc_type in ('PURCHASE_ORDER', 'GRN', 'INDENT', 'TRANSFER', 'BILL')),
  fiscal_year  integer not null
                 constraint ck_document_counter_fiscal_year check (fiscal_year = 0 or fiscal_year between 2000 and 2999),
  next_value   bigint not null
                 constraint ck_document_counter_next_value check (next_value >= 2),
  updated_at   timestamptz not null default now(),
  constraint pk_document_counter primary key (tenant_id, unit_id, doc_type, fiscal_year),
  constraint fk_document_counter_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id)
);

alter table pharmacy.document_counter enable row level security;
alter table pharmacy.document_counter force row level security;
create policy p_document_counter_select on pharmacy.document_counter for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_document_counter_insert on pharmacy.document_counter for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_document_counter_update on pharmacy.document_counter for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
-- No delete: a counter row is kept so a number is never reused.
revoke all on pharmacy.document_counter from sahaj_app;
revoke all on pharmacy.document_counter from public;
grant select, insert, update on pharmacy.document_counter to sahaj_app;

comment on table pharmacy.document_counter is
  'Next document number per tenant, unit, document type and financial year (SAH-78-10, ADR-028 D8). Written only by pharmacy.next_document_no(). Never deleted. At most a handful of rows per unit per year.';

-- Returns the sequence number to put on the document (1, 2, 3 ...), not the rendered text (the application renders prefix, year and
-- padding from pharmacy.document_series). Call it inside the transaction that inserts the document: the row lock taken by
-- INSERT ... ON CONFLICT DO UPDATE serialises the series, and a rollback gives the number back (no gap); a cancelled document keeps its number.
-- SECURITY INVOKER (default): runs as sahaj_app under the row level security above, so it can only touch the bound tenant.
-- Fiscal year: April to March, IST. A series that never restarts uses fiscal_year 0. When no series row is saved yet, the default applies
-- (only BILL restarts each year). SQLSTATE 22023 for an unknown type, 42501 when no tenant is bound, P0002 when the tenant has no unit yet.
create or replace function pharmacy.next_document_no(p_doc_type text)
returns bigint
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_tenant    uuid := platform.current_tenant();
  v_unit      uuid;
  v_restarts  boolean;
  v_ist       timestamp := (pg_catalog.now() at time zone 'Asia/Kolkata');
  v_fy        integer;
  v_no        bigint;
begin
  if p_doc_type is null or p_doc_type not in ('PURCHASE_ORDER', 'GRN', 'INDENT', 'TRANSFER', 'BILL') then
    raise exception 'unknown document type' using errcode = 'invalid_parameter_value';
  end if;
  if v_tenant is null then
    raise exception 'no tenant bound' using errcode = 'insufficient_privilege';
  end if;
  select u.id into v_unit from pharmacy.unit u where u.tenant_id = v_tenant;
  if v_unit is null then
    raise exception 'pharmacy unit is not set up for this tenant' using errcode = 'no_data_found';
  end if;
  select s.restarts_each_fy into v_restarts
    from pharmacy.document_series s
   where s.tenant_id = v_tenant and s.unit_id = v_unit and s.doc_type = p_doc_type;
  if not found then
    v_restarts := (p_doc_type = 'BILL');
  end if;
  if v_restarts then
    v_fy := pg_catalog.date_part('year', v_ist)::integer - case when pg_catalog.date_part('month', v_ist) < 4 then 1 else 0 end;
  else
    v_fy := 0;
  end if;
  insert into pharmacy.document_counter as c (tenant_id, unit_id, doc_type, fiscal_year, next_value, updated_at)
  values (v_tenant, v_unit, p_doc_type, v_fy, 2, pg_catalog.now())
  on conflict (tenant_id, unit_id, doc_type, fiscal_year) do update
    set next_value = c.next_value + 1,
        updated_at = pg_catalog.now()
  returning c.next_value - 1 into v_no;
  return v_no;
end;
$$;
revoke all on function pharmacy.next_document_no(text) from public;
grant execute on function pharmacy.next_document_no(text) to sahaj_app;

comment on function pharmacy.next_document_no(text) is
  'Next sequence number for the bound tenant, its unit, a document type and the current April-March financial year (IST). Call in the transaction that inserts the document (SAH-78-10, ADR-028 D8). No HTTP operation in Phase 1.';

-- ======================================================================================================================
-- 3. pharmacy.reason_default: GLOBAL reference rows, the 13 reasons of the four lists (story 78-10, OQ-78-32). Present for every
--    unit in every environment. ipd.drug pattern: forced row level security, select-only for sahaj_app, delete and truncate refused,
--    changed by migration only. No tenant column.
-- ======================================================================================================================
create table if not exists pharmacy.reason_default (
  reason_key   text primary key
                 constraint ck_reason_default_key check (reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  list_key     text not null
                 constraint ck_reason_default_list check (list_key in ('store-return', 'sales-return', 'damage', 'stock-adjustment')),
  label        text not null
                 constraint ck_reason_default_label check (char_length(label) between 1 and 40),
  sort         integer not null
                 constraint ck_reason_default_sort check (sort >= 1),
  created_at   timestamptz not null default now(),
  constraint uq_reason_default_sort unique (list_key, sort),
  constraint uq_reason_default_label unique (list_key, label)
);

alter table pharmacy.reason_default enable row level security;
alter table pharmacy.reason_default force row level security;
create policy p_reason_default_select on pharmacy.reason_default for select to sahaj_app using (true);
create policy p_reason_default_migrator on pharmacy.reason_default for all to sahaj_migrator using (true) with check (true);
revoke all on pharmacy.reason_default from sahaj_app;
revoke all on pharmacy.reason_default from public;
grant select on pharmacy.reason_default to sahaj_app;

create trigger trg_reason_default_no_delete before delete on pharmacy.reason_default
  for each row execute function pharmacy.deny_reference_delete();
create trigger trg_reason_default_no_truncate before truncate on pharmacy.reason_default
  for each statement execute function pharmacy.deny_reference_delete();

comment on table pharmacy.reason_default is
  'Seeded reasons of the four reason lists (store-return, sales-return, damage, stock-adjustment), global reference data (SAH-78-10). Select-only for sahaj_app, changed by migration only, never deleted. A clinic switches one off in pharmacy.reason_default_state.';

-- Reference content, all environments (wording from mockups 04, 11 and 13, story 78-10).
insert into pharmacy.reason_default (reason_key, list_key, label, sort) values
  ('store-return.excess-stock',               'store-return',     'Excess stock',                  1),
  ('store-return.near-expiry',                'store-return',     'Near expiry',                   2),
  ('store-return.not-needed',                 'store-return',     'Not needed (order stopped)',    3),
  ('sales-return.doctor-changed-medicine',    'sales-return',     'Doctor changed medicine',       1),
  ('sales-return.excess-bought',              'sales-return',     'Excess bought',                 2),
  ('sales-return.wrong-item-given',           'sales-return',     'Wrong item given',              3),
  ('sales-return.adverse-reaction',           'sales-return',     'Adverse reaction',              4),
  ('damage.damaged-leaking',                  'damage',           'Damaged / leaking',             1),
  ('damage.expired',                          'damage',           'Expired',                       2),
  ('damage.cold-chain-break',                 'damage',           'Cold-chain break',              3),
  ('stock-adjustment.consumption-not-entered','stock-adjustment', 'Consumption not entered',       1),
  ('stock-adjustment.breakage',               'stock-adjustment', 'Breakage',                      2),
  ('stock-adjustment.unknown',                'stock-adjustment', 'Unknown',                       3)
on conflict (reason_key) do nothing;

-- ======================================================================================================================
-- 4. pharmacy.reason: the unit's own reasons added to a list (chips at the end of the list). Switched off by active, never deleted.
-- ======================================================================================================================
create table if not exists pharmacy.reason (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null default platform.current_tenant()
                 constraint fk_reason_tenant references platform.tenant (id),
  unit_id      uuid not null,
  list_key     text not null
                 constraint ck_reason_list check (list_key in ('store-return', 'sales-return', 'damage', 'stock-adjustment')),
  reason_key   text not null
                 constraint ck_reason_key check (reason_key ~ '^c-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
  label        text not null
                 constraint ck_reason_label check (char_length(label) between 1 and 40 and label = btrim(label)),
  active       boolean not null default true,
  sort         integer not null
                 constraint ck_reason_sort check (sort >= 1),
  created_at   timestamptz not null default now(),
  created_by   uuid not null,
  updated_at   timestamptz,
  updated_by   uuid,
  version      bigint not null default 0,
  constraint uq_reason_key unique (tenant_id, unit_id, reason_key),
  constraint fk_reason_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id)
);

-- A label appears once per list ignoring case (the service also refuses a label equal to a default label of the same list).
create unique index if not exists uq_reason_label on pharmacy.reason (tenant_id, unit_id, list_key, lower(label));

alter table pharmacy.reason enable row level security;
alter table pharmacy.reason force row level security;
create policy p_reason_select on pharmacy.reason for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_reason_insert on pharmacy.reason for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_reason_update on pharmacy.reason for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.reason from sahaj_app;
revoke all on pharmacy.reason from public;
grant select, insert, update on pharmacy.reason to sahaj_app;

comment on table pharmacy.reason is
  'Reasons a unit added to one of the four reason lists (SAH-78-10). Never deleted; switched off by active. The last active Damage reason cannot be switched off (service rule).';

-- ======================================================================================================================
-- 5. pharmacy.reason_default_state: a unit switching a seeded reason off or on. Lazy: a missing row means active.
-- ======================================================================================================================
create table if not exists pharmacy.reason_default_state (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null default platform.current_tenant()
                 constraint fk_reason_default_state_tenant references platform.tenant (id),
  unit_id      uuid not null,
  reason_key   text not null
                 constraint fk_reason_default_state_reason references pharmacy.reason_default (reason_key),
  active       boolean not null,
  created_at   timestamptz not null default now(),
  created_by   uuid not null,
  updated_at   timestamptz,
  updated_by   uuid,
  version      bigint not null default 0,
  constraint uq_reason_default_state unique (tenant_id, unit_id, reason_key),
  constraint fk_reason_default_state_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id)
);

alter table pharmacy.reason_default_state enable row level security;
alter table pharmacy.reason_default_state force row level security;
create policy p_reason_default_state_select on pharmacy.reason_default_state for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_reason_default_state_insert on pharmacy.reason_default_state for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_reason_default_state_update on pharmacy.reason_default_state for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.reason_default_state from sahaj_app;
revoke all on pharmacy.reason_default_state from public;
grant select, insert, update on pharmacy.reason_default_state to sahaj_app;

comment on table pharmacy.reason_default_state is
  'Per unit switch of a seeded reason (SAH-78-10). Lazy: no row means active. Updated in place, never deleted.';

-- ======================================================================================================================
-- 6. pharmacy.print_template_setting: header lines, footer note and paper size per printed document type. Settings only; the
--    preview is the browser print view (no PDF library, no file storage). Lazy.
-- ======================================================================================================================
create table if not exists pharmacy.print_template_setting (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null default platform.current_tenant()
                   constraint fk_print_template_setting_tenant references platform.tenant (id),
  unit_id        uuid not null,
  doc_type       text not null
                   constraint ck_print_template_setting_doc_type check (doc_type in ('INVOICE', 'ISSUE_SLIP', 'DEBIT_NOTE')),
  header_line_1  text constraint ck_print_template_setting_h1 check (char_length(btrim(header_line_1)) between 1 and 80),
  header_line_2  text constraint ck_print_template_setting_h2 check (char_length(btrim(header_line_2)) between 1 and 80),
  header_line_3  text constraint ck_print_template_setting_h3 check (char_length(btrim(header_line_3)) between 1 and 80),
  footer_note    text constraint ck_print_template_setting_footer check (char_length(footer_note) between 1 and 200),
  paper_size     text not null default 'A4'
                   constraint ck_print_template_setting_paper check (paper_size in ('A4', 'A5')),
  created_at     timestamptz not null default now(),
  created_by     uuid not null,
  updated_at     timestamptz,
  updated_by     uuid,
  version        bigint not null default 0,
  constraint uq_print_template_setting unique (tenant_id, unit_id, doc_type),
  constraint fk_print_template_setting_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  -- Header lines fill from the top: line 2 needs line 1, line 3 needs line 2.
  constraint ck_print_template_setting_header_order check (
    (header_line_2 is null or header_line_1 is not null) and (header_line_3 is null or header_line_2 is not null))
);

alter table pharmacy.print_template_setting enable row level security;
alter table pharmacy.print_template_setting force row level security;
create policy p_print_template_setting_select on pharmacy.print_template_setting for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_print_template_setting_insert on pharmacy.print_template_setting for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_print_template_setting_update on pharmacy.print_template_setting for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.print_template_setting from sahaj_app;
revoke all on pharmacy.print_template_setting from public;
grant select, insert, update on pharmacy.print_template_setting to sahaj_app;

comment on table pharmacy.print_template_setting is
  'Print template settings per document type (INVOICE, ISSUE_SLIP, DEBIT_NOTE) of a unit (SAH-78-10). Lazy, one row per type, updated in place, never deleted. Fixed fields are in code.';
