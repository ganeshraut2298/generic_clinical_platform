-- SAH-80 WP-I (stories 80-1 to 80-5), migration I1. ADR-030 "Data needed" (WP-I touch-ups), S2, S3, S10, TP-10. Additive; no row is changed or removed.
--   1. pharmacy.store_staff.store_role: the check also accepts CASHIER (the old four stay valid).
--   2. pharmacy.unit: four nullable or defaulted columns (return window, refund modes, Schedule X licence number and validity).
--   3. pharmacy.product.ndps: nothing to do. The column exists since V2026_12_17_1100 (boolean, null for ADOPTED, not null for OWN through
--      ck_product_kind_fields). "Writable for own products" is a service rule (contract 0.3.x), not a database change.
--   4. document_series.doc_type and document_counter.doc_type: + CREDIT_NOTE, IPD_ISSUE_SLIP.
--   5. pharmacy.next_document_no(text): replaced from its latest body (V2026_12_17_1720). Only the two new types are added to the list, and
--      the default "restarts each financial year" is also true for CREDIT_NOTE (ADR-030 S3: CN/26-27/000001 restarts), like BILL.
--   6. reason_default.list_key and reason.list_key: + nine sale lists; their seed rows (34 global rows); the fixed return-condition keys
--      cannot be switched off (check on reason_default_state replaced: receipt-reject.short stays).
--   7. stock_ledger.txn_type: + COUNTER_SALE, COUNTER_SALE_CANCELLED, SALES_RETURN, IPD_ISSUE, WARD_RETURN_ACCEPTED, WARD_RETURN_WASTAGE.
--   8. pharmacy.guard_stock_ledger_schedule_x(): replaced from its latest body (V2026_12_17_1730). Only the array changes: the six SAH-80
--      types are person-driven and join the list (23 types). The trigger itself (1720) stays.
-- Functions replaced here (each from the latest earlier body, only the stated change added):
--   pharmacy.next_document_no(text)            files before: 1220, 1280, 1380, 1710, 1720 (latest). Change: two types, CREDIT_NOTE restarts.
--   pharmacy.guard_stock_ledger_schedule_x()   files before: 1720, 1730 (latest). Change: the array (+ 6 types).

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. CASHIER store role (ADR-030 S2). Which team roles may hold it, and "only at an ACTIVE sale-allowed store that is not the Central
--    Store", are service rules (as for the other roles); the database only widens the list.
-- ======================================================================================================================
alter table pharmacy.store_staff drop constraint if exists ck_store_staff_role;
alter table pharmacy.store_staff add constraint ck_store_staff_role check (store_role in
  ('STORE_USER', 'CENTRAL_STORE_USER', 'APPROVER', 'DELEGATE', 'CASHIER'));

-- ======================================================================================================================
-- 2. Unit sale settings (80-1, OQ-80-12, OQ-80-18). All nullable or empty by default: a unit that sets nothing has no return window
--    ("not set: no return"), no refund mode ticked and no Schedule X licence.
-- ======================================================================================================================
alter table pharmacy.unit add column if not exists return_window_days integer
  constraint ck_unit_return_window_days check (return_window_days between 1 and 365);
alter table pharmacy.unit add column if not exists refund_modes text[] not null default '{}'
  constraint ck_unit_refund_modes check (
    refund_modes <@ array['ORIGINAL_MODE', 'CASH', 'CREDIT_NOTE', 'UDHAR']::text[]
    and pg_catalog.cardinality(refund_modes) <= 4);
alter table pharmacy.unit add column if not exists schedule_x_licence_no varchar(40)
  constraint ck_unit_schedule_x_licence_no check (char_length(btrim(schedule_x_licence_no)) between 1 and 40);
alter table pharmacy.unit add column if not exists schedule_x_licence_valid_till date;

comment on column pharmacy.unit.return_window_days is
  'SAH-80-1: days after the bill within which a sales return is accepted (1 to 365). Null = not set = no sales return. Counted in IST days by the service.';
comment on column pharmacy.unit.refund_modes is
  'SAH-80-1: refund modes the unit allows (ORIGINAL_MODE, CASH, CREDIT_NOTE, UDHAR). Empty until the owner ticks them. Duplicates are refused by the service (uniqueItems).';
comment on column pharmacy.unit.schedule_x_licence_no is
  'SAH-80-1, SAH-80-18: Schedule X / NDPS licence number (up to 40 characters). Shown on the compliance screen and the licence reminders.';

-- ======================================================================================================================
-- 4. Document types: CREDIT_NOTE ("CN/", FY part, 6 digits, restarts) and IPD_ISSUE_SLIP ("IS-", 4 digits). Defaults are served by the
--    application until a series row is saved (lazy), as for every other type; no document_series row is inserted here.
-- ======================================================================================================================
alter table pharmacy.document_series drop constraint if exists ck_document_series_doc_type;
alter table pharmacy.document_series add constraint ck_document_series_doc_type check (doc_type in
  ('PURCHASE_ORDER', 'GRN', 'INDENT', 'TRANSFER', 'BILL', 'DEBIT_NOTE', 'STOCK_AUDIT', 'STORE_RETURN', 'DAMAGE_REPORT', 'STOCK_ADJUSTMENT',
   'CREDIT_NOTE', 'IPD_ISSUE_SLIP'));

alter table pharmacy.document_counter drop constraint if exists ck_document_counter_doc_type;
alter table pharmacy.document_counter add constraint ck_document_counter_doc_type check (doc_type in
  ('PURCHASE_ORDER', 'GRN', 'INDENT', 'TRANSFER', 'BILL', 'DEBIT_NOTE', 'STOCK_AUDIT', 'STORE_RETURN', 'DAMAGE_REPORT', 'STOCK_ADJUSTMENT',
   'CREDIT_NOTE', 'IPD_ISSUE_SLIP'));

-- ======================================================================================================================
-- 5. next_document_no(text): body of V2026_12_17_1720 (= 1220 + 1280 + 1380 + 1710 + the unit FOR SHARE lock) with two changes:
--    the type list has CREDIT_NOTE and IPD_ISSUE_SLIP, and the default restart is true for BILL and CREDIT_NOTE.
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
       ('PURCHASE_ORDER', 'GRN', 'INDENT', 'TRANSFER', 'BILL', 'DEBIT_NOTE', 'STOCK_AUDIT', 'STORE_RETURN', 'DAMAGE_REPORT', 'STOCK_ADJUSTMENT',
        'CREDIT_NOTE', 'IPD_ISSUE_SLIP') then
    raise exception 'unknown document type' using errcode = 'invalid_parameter_value';
  end if;
  if v_tenant is null then
    raise exception 'no tenant bound' using errcode = 'insufficient_privilege';
  end if;
  -- L11 (1720): FOR SHARE, so a series save (which holds this row FOR UPDATE) and an issue in flight wait for each other.
  select u.id into v_unit from pharmacy.unit u where u.tenant_id = v_tenant for share;
  if v_unit is null then
    raise exception 'pharmacy unit is not set up for this tenant' using errcode = 'no_data_found';
  end if;
  select s.restarts_each_fy into v_restarts
    from pharmacy.document_series s
   where s.tenant_id = v_tenant and s.unit_id = v_unit and s.doc_type = p_doc_type;
  if not found then
    -- 1800: the served default of BILL and CREDIT_NOTE restarts each financial year (CN/26-27/000001).
    v_restarts := (p_doc_type in ('BILL', 'CREDIT_NOTE'));
  end if;
  if v_restarts then
    v_fy := pg_catalog.date_part('year', v_ist)::integer - case when pg_catalog.date_part('month', v_ist) < 4 then 1 else 0 end;
  else
    v_fy := 0;
  end if;
  insert into pharmacy.document_counter as c (tenant_id, unit_id, doc_type, fiscal_year, next_value, updated_at, last_issued_at)
  values (v_tenant, v_unit, p_doc_type, v_fy, 2, pg_catalog.now(), pg_catalog.now())
  on conflict (tenant_id, unit_id, doc_type, fiscal_year) do update
    set next_value = c.next_value + 1,
        updated_at = pg_catalog.now(),
        last_issued_at = pg_catalog.now()
  returning c.next_value - 1 into v_no;
  return v_no;
end;
$$;
revoke all on function pharmacy.next_document_no(text) from public;
grant execute on function pharmacy.next_document_no(text) to sahaj_app;

comment on function pharmacy.next_document_no(text) is
  'Next sequence number for the bound tenant, its unit, a document type and the current April-March financial year (IST). Reads the unit row FOR SHARE (L11). Sets last_issued_at. Twelve document types. The default restart (no series row saved) is true for BILL and CREDIT_NOTE. Final body (V2026_12_17_1800) = 1220 + 1280 + 1380 + 1710 + 1720 + the two SAH-80 types; a later replacement must carry all of these.';

-- ======================================================================================================================
-- 6. Reason lists: nine new lists (ADR-030 S10). Global rows, editable by the unit through reason and reason_default_state.
-- ======================================================================================================================
alter table pharmacy.reason_default drop constraint if exists ck_reason_default_list;
alter table pharmacy.reason_default add constraint ck_reason_default_list check (list_key in
  ('store-return', 'sales-return', 'damage', 'stock-adjustment',
   'receipt-reject', 'cs-change', 'approval-reject', 'transfer', 'consumption-purpose', 'dispute', 'vendor-return',
   'substitution', 'allergy-override', 'h1-override', 'return-condition', 'cancel-bill', 'ward-return', 'rx-closed', 'clearance',
   'day-close-difference'));

alter table pharmacy.reason drop constraint if exists ck_reason_list;
alter table pharmacy.reason add constraint ck_reason_list check (list_key in
  ('store-return', 'sales-return', 'damage', 'stock-adjustment',
   'receipt-reject', 'cs-change', 'approval-reject', 'transfer', 'consumption-purpose', 'dispute', 'vendor-return',
   'substitution', 'allergy-override', 'h1-override', 'return-condition', 'cancel-bill', 'ward-return', 'rx-closed', 'clearance',
   'day-close-difference'));

insert into pharmacy.reason_default (reason_key, list_key, label, sort) values
  ('substitution.out-of-stock',                  'substitution',         'Out of stock',                        1),
  ('substitution.patient-preference',            'substitution',         'Patient preference',                  2),
  ('substitution.cheaper-equivalent',            'substitution',         'Cheaper equivalent',                  3),
  ('substitution.prescriber-advice',             'substitution',         'Prescriber advice',                   4),
  ('substitution.other',                         'substitution',         'Other',                               5),
  ('allergy-override.prescriber-confirmed',      'allergy-override',     'Prescriber confirmed',                1),
  ('allergy-override.taken-safely-before',       'allergy-override',     'Patient has taken it safely before',  2),
  ('allergy-override.entry-is-wrong',            'allergy-override',     'Allergy entry is wrong',              3),
  ('allergy-override.other',                     'allergy-override',     'Other',                               4),
  ('h1-override.prescriber-confirmed',           'h1-override',          'Prescriber confirmed',                1),
  ('h1-override.taken-safely-before',            'h1-override',          'Patient has taken it safely before',  2),
  ('h1-override.entry-is-wrong',                 'h1-override',          'Allergy entry is wrong',              3),
  ('h1-override.other',                          'h1-override',          'Other',                               4),
  ('return-condition.sealed',                    'return-condition',     'Sealed',                              1),
  ('return-condition.opened',                    'return-condition',     'Strip cut or opened',                 2),
  ('return-condition.damaged',                   'return-condition',     'Damaged',                             3),
  ('cancel-bill.wrong-item',                     'cancel-bill',          'Wrong item',                          1),
  ('cancel-bill.wrong-quantity',                 'cancel-bill',          'Wrong quantity',                      2),
  ('cancel-bill.customer-changed-mind',          'cancel-bill',          'Customer changed mind',               3),
  ('cancel-bill.other',                          'cancel-bill',          'Other',                               4),
  ('ward-return.medicine-stopped',               'ward-return',          'Medicine stopped',                    1),
  ('ward-return.discharge',                      'ward-return',          'Discharge',                           2),
  ('ward-return.not-used',                       'ward-return',          'Not used',                            3),
  ('rx-closed.patient-declined',                 'rx-closed',            'Patient declined',                    1),
  ('rx-closed.out-of-stock',                     'rx-closed',            'Out of stock',                        2),
  ('rx-closed.doctor-changed-the-order',         'rx-closed',            'Doctor changed the order',            3),
  ('rx-closed.other',                            'rx-closed',            'Other',                               4),
  ('clearance.returns-will-come-later',          'clearance',            'Returns will come later',             1),
  ('clearance.nothing-to-return',                'clearance',            'Nothing to return',                   2),
  ('clearance.other',                            'clearance',            'Other',                               3),
  ('day-close-difference.short-change-given',    'day-close-difference', 'Short change given',                  1),
  ('day-close-difference.entry-error',           'day-close-difference', 'Entry error',                         2),
  ('day-close-difference.refund-not-recorded',   'day-close-difference', 'Refund not recorded',                 3),
  ('day-close-difference.other',                 'day-close-difference', 'Other',                               4)
on conflict (reason_key) do nothing;

-- The three return-condition keys route stock (Sealed to Available; opened or damaged to the pharmacist's decision), so they cannot be
-- switched off, like receipt-reject.short (1380). The check keeps that rule and adds the three. No row can violate it (the keys are new).
alter table pharmacy.reason_default_state drop constraint if exists ck_reason_default_state_fixed;
alter table pharmacy.reason_default_state add constraint ck_reason_default_state_fixed
  check (active or reason_key not in ('receipt-reject.short', 'return-condition.sealed', 'return-condition.opened', 'return-condition.damaged'));

comment on constraint ck_reason_default_state_fixed on pharmacy.reason_default_state is
  'Fixed reasons cannot be switched off: receipt-reject.short (SAH-79, ADR-029 P11) and return-condition.sealed, .opened, .damaged (SAH-80, ADR-030 S10, open point O-13).';

-- ======================================================================================================================
-- 7. Ledger transaction types (the six sale values of contract 0.3.0). The old 24 stay valid, so no row can fail the new check.
--    Dropping and adding a check on the partitioned parent applies to every monthly partition (the constraint is inherited).
-- ======================================================================================================================
alter table pharmacy.stock_ledger drop constraint if exists ck_stock_ledger_txn_type;
alter table pharmacy.stock_ledger add constraint ck_stock_ledger_txn_type check (txn_type in
  ('OPENING_STOCK', 'GRN', 'EXPIRY_QUARANTINE', 'STOCK_ADJUSTMENT', 'VENDOR_RETURN_HOLD',
   'VENDOR_RETURN_RELEASE', 'VENDOR_RETURN', 'INDENT_DISPATCH', 'INDENT_RECEIPT_ACCEPTED',
   'INDENT_RECEIPT_REJECTED', 'RETURN_ACCEPTED', 'SHORTAGE_FOUND', 'SHORTAGE_WRITE_OFF',
   'STORE_RETURN', 'DAMAGE_QUARANTINE', 'DAMAGE_RELEASE', 'DAMAGE_SEND_TO_CS', 'WRITE_OFF',
   'TRANSFER_DISPATCH', 'TRANSFER_RECEIPT_ACCEPTED', 'TRANSFER_RECEIPT_REJECTED',
   'WARD_CONSUMPTION', 'WARD_CONSUMPTION_PATIENT', 'STOCK_AUDIT_ADJUSTMENT',
   'COUNTER_SALE', 'COUNTER_SALE_CANCELLED', 'SALES_RETURN', 'IPD_ISSUE', 'WARD_RETURN_ACCEPTED', 'WARD_RETURN_WASTAGE'));

-- ======================================================================================================================
-- 8. L14 list: body of V2026_12_17_1730 (the trigger trg_stock_ledger_schedule_x of 1720 stays); only the array grows by the six
--    SAH-80 types. A sale, a cancel, a sales return, a ward issue, an accepted ward return and a wastage decision are each a person
--    handling stock (ADR-030 S8; DEC-081 c rule: person-driven types are on the list).
-- ======================================================================================================================
create or replace function pharmacy.guard_stock_ledger_schedule_x()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_schedule text;
begin
  if new.txn_type = any (array[
       'GRN', 'STOCK_ADJUSTMENT', 'VENDOR_RETURN', 'INDENT_DISPATCH', 'INDENT_RECEIPT_ACCEPTED', 'INDENT_RECEIPT_REJECTED',
       'TRANSFER_DISPATCH', 'TRANSFER_RECEIPT_ACCEPTED', 'TRANSFER_RECEIPT_REJECTED', 'RETURN_ACCEPTED', 'SHORTAGE_FOUND',
       'STORE_RETURN', 'DAMAGE_QUARANTINE', 'DAMAGE_SEND_TO_CS', 'WARD_CONSUMPTION', 'WARD_CONSUMPTION_PATIENT',
       'STOCK_AUDIT_ADJUSTMENT',
       'COUNTER_SALE', 'COUNTER_SALE_CANCELLED', 'SALES_RETURN', 'IPD_ISSUE', 'WARD_RETURN_ACCEPTED', 'WARD_RETURN_WASTAGE']::text[]) then
    select coalesce(p.schedule, c.schedule) into v_schedule
      from pharmacy.product p
      left join pharmacy.catalogue_product c on c.id = p.catalogue_product_id
     where p.tenant_id = new.tenant_id and p.id = new.product_id;
    if v_schedule = 'X' then
      raise exception 'schedule X movement needs a second signer' using errcode = 'check_violation';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_stock_ledger_schedule_x() from public;
revoke all on function pharmacy.guard_stock_ledger_schedule_x() from sahaj_app;

comment on function pharmacy.guard_stock_ledger_schedule_x() is
  'L14: a ledger line by a person (actor_kind USER) on a person-driven transaction type (ADR-029 P7 final list, 17 types, plus the six SAH-80 types COUNTER_SALE, COUNTER_SALE_CANCELLED, SALES_RETURN, IPD_ISSUE, WARD_RETURN_ACCEPTED, WARD_RETURN_WASTAGE; 23 types) for a Schedule X product must carry second_signer_membership_id. Schedule from the own product row or, for an adopted product, the global catalogue. SQLSTATE 23514. Final body V2026_12_17_1800 (list); earlier bodies 1720 and 1730. COUNTER_SALE_CANCELLED is on the list (DEC-093 item 2): a cancel of a Schedule X sale is a person handling stock and carries the signer. A new person-driven type is added here, to the contract and to ADR-029 P7 in one change.';
