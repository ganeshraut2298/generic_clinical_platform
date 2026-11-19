-- SAH-79 WP-D (stories 79-1 to 79-5, common), migration D6. ADR-029 P9 (new document series), P11 (Phase 2 reason lists), OQ-79-14,
-- OQ-79-15, OQ-79-27, A-5. Additive; no data is changed or removed.
-- 1. Widen four checks by replacing them with the old list plus the new values (the way V2026_12_17_1000 widened the role check):
--    document_series.doc_type and document_counter.doc_type (+ DEBIT_NOTE, STOCK_AUDIT, STORE_RETURN, DAMAGE_REPORT, STOCK_ADJUSTMENT),
--    reason_default.list_key and reason.list_key (+ 7 lists). Every old value stays valid, so no row can fail the new check.
-- 2. pharmacy.next_document_no(text): create or replace with the same body and the wider list. ADR-029 says "the counter function is
--    unchanged", but its type list is written inside the function, so it must be widened too, or DN, AUD, RET, DMG and ADJ would be refused
--    with "unknown document type". Signature, privileges, search_path and behaviour are the same.
-- 3. Seeds the Phase 2 global reason_default rows. A check on reason_default_state keeps the fixed reason "Short / not received" on.
-- The series defaults (DN-, AUD-, RET-, DMG-, ADJ-, 4 digits, no financial-year part, no restart) are served by the application until a
-- row is saved (lazy, like the five SAH-78 defaults); no document_series row is inserted here.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. Wider checks
-- ======================================================================================================================
alter table pharmacy.document_series drop constraint if exists ck_document_series_doc_type;
alter table pharmacy.document_series add constraint ck_document_series_doc_type check (doc_type in
  ('PURCHASE_ORDER', 'GRN', 'INDENT', 'TRANSFER', 'BILL', 'DEBIT_NOTE', 'STOCK_AUDIT', 'STORE_RETURN', 'DAMAGE_REPORT', 'STOCK_ADJUSTMENT'));

alter table pharmacy.document_counter drop constraint if exists ck_document_counter_doc_type;
alter table pharmacy.document_counter add constraint ck_document_counter_doc_type check (doc_type in
  ('PURCHASE_ORDER', 'GRN', 'INDENT', 'TRANSFER', 'BILL', 'DEBIT_NOTE', 'STOCK_AUDIT', 'STORE_RETURN', 'DAMAGE_REPORT', 'STOCK_ADJUSTMENT'));

alter table pharmacy.reason_default drop constraint if exists ck_reason_default_list;
alter table pharmacy.reason_default add constraint ck_reason_default_list check (list_key in
  ('store-return', 'sales-return', 'damage', 'stock-adjustment',
   'receipt-reject', 'cs-change', 'approval-reject', 'transfer', 'consumption-purpose', 'dispute', 'vendor-return'));

alter table pharmacy.reason drop constraint if exists ck_reason_list;
alter table pharmacy.reason add constraint ck_reason_list check (list_key in
  ('store-return', 'sales-return', 'damage', 'stock-adjustment',
   'receipt-reject', 'cs-change', 'approval-reject', 'transfer', 'consumption-purpose', 'dispute', 'vendor-return'));

-- ======================================================================================================================
-- 2. next_document_no with the wider list (body as in V2026_12_17_1220 apart from the list)
-- ======================================================================================================================
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
  if p_doc_type is null or p_doc_type not in
       ('PURCHASE_ORDER', 'GRN', 'INDENT', 'TRANSFER', 'BILL', 'DEBIT_NOTE', 'STOCK_AUDIT', 'STORE_RETURN', 'DAMAGE_REPORT', 'STOCK_ADJUSTMENT') then
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

-- ======================================================================================================================
-- 3. Seeds (global reference rows, every environment). Labels from ADR-029 P11 and the P&I mockup chips; keys are stable and lower case.
--    dispute and vendor-return have "Other" only (A-5): the owner adds the clinic's own reasons on the Reason lists screen.
-- ======================================================================================================================
insert into pharmacy.reason_default (reason_key, list_key, label, sort) values
  ('receipt-reject.damaged-in-transit',        'receipt-reject',      'Damaged in transit',              1),
  ('receipt-reject.wrong-item',                'receipt-reject',      'Wrong item',                      2),
  ('receipt-reject.near-expiry',               'receipt-reject',      'Near expiry / expired',           3),
  ('receipt-reject.excess-quantity',           'receipt-reject',      'Excess quantity',                 4),
  ('receipt-reject.short',                     'receipt-reject',      'Short / not received',            5),
  ('cs-change.out-of-stock',                   'cs-change',           'Out of stock',                    1),
  ('cs-change.near-expiry-held',               'cs-change',           'Near expiry held',                2),
  ('cs-change.pack-or-size-change',            'cs-change',           'Pack or size change',             3),
  ('cs-change.other',                          'cs-change',           'Other',                           4),
  ('approval-reject.not-needed',               'approval-reject',     'Not needed',                      1),
  ('approval-reject.quantity-too-high',        'approval-reject',     'Quantity too high',               2),
  ('approval-reject.wrong-item',               'approval-reject',     'Wrong item',                      3),
  ('approval-reject.other',                    'approval-reject',     'Other',                           4),
  ('transfer.non-moving-here',                 'transfer',            'Non-moving here, needed there',   1),
  ('transfer.urgent-shortage',                 'transfer',            'Urgent shortage',                 2),
  ('transfer.store-closure',                   'transfer',            'Store closure',                   3),
  ('consumption-purpose.ward-general-use',     'consumption-purpose', 'Ward general use',                1),
  ('consumption-purpose.procedure-non-billable','consumption-purpose','Procedure (non-billable)',        2),
  ('consumption-purpose.cleaning-infection-control','consumption-purpose','Cleaning / infection control',3),
  ('dispute.other',                            'dispute',             'Other',                           1),
  ('vendor-return.other',                      'vendor-return',       'Other',                           1),
  ('stock-adjustment.count-mismatch',          'stock-adjustment',    'Count mismatch',                  4),
  ('stock-adjustment.theft-or-loss',           'stock-adjustment',    'Theft or loss',                   5)
on conflict (reason_key) do nothing;

-- The reason "Short / not received" drives the shortage path and cannot be switched off (P11). Backstop for the service rule: a unit can
-- never hold a state row that turns it off. No row can violate this today (the key is new).
alter table pharmacy.reason_default_state drop constraint if exists ck_reason_default_state_fixed;
alter table pharmacy.reason_default_state add constraint ck_reason_default_state_fixed
  check (active or reason_key <> 'receipt-reject.short');

comment on constraint ck_reason_default_state_fixed on pharmacy.reason_default_state is
  'SAH-79 (ADR-029 P11): the fixed reason receipt-reject.short cannot be switched off.';
