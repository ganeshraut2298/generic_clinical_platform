-- SAH-78 / SAH-79 merge fix (QA defect D-1, DEC-061 programme). Forward migration. No applied migration is edited.
--
-- WHY: Flyway applies migrations in version order, and a function that several migrations replace takes its body from the HIGHEST
-- version. Two functions ended up with a body that lost earlier changes:
--   1. pharmacy.guard_opening_stock_import_update(): V2026_12_17_1350 (posting, written for SAH-79 WP-D) was built on the body of
--      V2026_12_17_1260 and so it undid V2026_12_17_1270 (DEC-076 DRAFT -> REJECTED; DEC-077 item 2 / security I8: the counters
--      line_rows_written and line_request_count never go down in DRAFT and are frozen from WAITING_APPROVAL on). Effect: reject of a
--      draft answered 500, and a counter could go down.
--   2. pharmacy.next_document_no(text): V2026_12_17_1380 (ten document types) was built on the body of V2026_12_17_1220 and so it undid
--      V2026_12_17_1280 (DEC-078 item 4: the upsert sets document_counter.last_issued_at). Effect: last_issued_at stayed null for every
--      number issued after 1380, so coalesce(last_issued_at, updated_at) still worked, but the explicit value was lost.
-- This file replaces both with the UNION of every earlier change. Bodies are the body of 1270 (which already contained the 1350 posting
-- rules, so it is the union of 1100, 1260, 1270 and 1350) and the body of 1380 plus the last_issued_at columns of 1280.
-- Same search_path (pg_catalog), SECURITY INVOKER, same privileges as before. The trigger trg_opening_stock_import_guard_update
-- (V2026_12_17_1100) is not touched: create or replace keeps it attached.
--
-- Safe on a database that has not yet applied 1270 or 1280 (out-of-order case): the two columns the functions read or write are added
-- here too (add column if not exists), so neither function can fail on a missing column. Everything else from 1270 (the relaxed
-- ck_opening_stock_import_submitted, the store-active insert trigger) and 1290 is NOT repeated here; see the design note
-- SAH-78-pharmacy-masters section 11 for how to cope on such a database.
-- Nothing in this file changes or removes a row.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 0. Columns the two functions use (no-ops where 1270 and 1280 already ran)
-- ======================================================================================================================
alter table pharmacy.opening_stock_import
  add column if not exists line_rows_written integer not null default 0;

do $$
begin
  if not exists (select 1 from pg_catalog.pg_constraint
                  where conname = 'ck_opening_stock_import_line_rows_written'
                    and conrelid = 'pharmacy.opening_stock_import'::regclass) then
    alter table pharmacy.opening_stock_import
      add constraint ck_opening_stock_import_line_rows_written check (line_rows_written >= 0);
  end if;
end
$$;

alter table pharmacy.document_counter
  add column if not exists last_issued_at timestamptz;

-- ======================================================================================================================
-- 1. pharmacy.guard_opening_stock_import_update(): union of 1100, 1260, 1270 and 1350
--    From 1100: final states, allowed transitions, identity frozen, a waiting import keeps its totals.
--    From 1260: line_request_count is frozen once WAITING_APPROVAL.
--    From 1270: DRAFT -> REJECTED allowed (DEC-076); in DRAFT neither counter goes down ('opening stock import line counter cannot go
--               down'); line_rows_written is frozen once WAITING_APPROVAL (DEC-077 item 2, I8).
--    From 1350: one posting update on an APPROVED row (posted_at and posted_by together, nothing else but the bookkeeping columns),
--               a posted import is final, DRAFT and WAITING_APPROVAL cannot carry a posting.
--    All messages unchanged, SQLSTATE 23514.
-- ======================================================================================================================
create or replace function pharmacy.guard_opening_stock_import_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if old.status in ('REJECTED', 'NOT_ACCEPTED', 'REPLACED') then
    raise exception 'opening stock import is final (%)', old.status using errcode = 'check_violation';
  end if;
  if old.status = 'APPROVED' then
    -- The one allowed update: posting. Everything except the posting and bookkeeping columns must stay as it was
    -- (the whole row is compared, so line_rows_written and line_request_count are frozen here too).
    if old.posted_at is not null then
      raise exception 'opening stock import is final (APPROVED, posted)' using errcode = 'check_violation';
    end if;
    if new.posted_at is null or new.posted_by is null then
      raise exception 'opening stock import is final (APPROVED)' using errcode = 'check_violation';
    end if;
    if (pg_catalog.to_jsonb(new) - 'posted_at' - 'posted_by' - 'updated_at' - 'updated_by' - 'version')
       is distinct from (pg_catalog.to_jsonb(old) - 'posted_at' - 'posted_by' - 'updated_at' - 'updated_by' - 'version') then
      raise exception 'opening stock import can change only posted_at and posted_by when it is posted' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  -- DRAFT and WAITING_APPROVAL cannot carry a posting.
  if new.posted_at is not null or new.posted_by is not null then
    raise exception 'opening stock import can be posted only after approval' using errcode = 'check_violation';
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.store_id is distinct from old.store_id or new.uploaded_by is distinct from old.uploaded_by
     or new.client_key is distinct from old.client_key or new.row_count is distinct from old.row_count
     or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
    raise exception 'opening stock import identity cannot change' using errcode = 'check_violation';
  end if;
  if old.status = 'DRAFT' then
    -- DEC-076: DRAFT -> REJECTED allowed.
    if new.status not in ('DRAFT', 'NOT_ACCEPTED', 'WAITING_APPROVAL', 'REPLACED', 'REJECTED') then
      raise exception 'opening stock import cannot go from draft to %', new.status using errcode = 'check_violation';
    end if;
    -- I8: a counter never goes down in DRAFT (this also covers the update that leaves DRAFT).
    if new.line_rows_written < old.line_rows_written or new.line_request_count < old.line_request_count then
      raise exception 'opening stock import line counter cannot go down' using errcode = 'check_violation';
    end if;
  else  -- WAITING_APPROVAL
    if new.status not in ('APPROVED', 'REJECTED', 'REPLACED') then
      raise exception 'opening stock import waiting for approval can only be approved, rejected or replaced' using errcode = 'check_violation';
    end if;
    if new.line_count is distinct from old.line_count or new.total_quantity is distinct from old.total_quantity
       or new.total_value_paise is distinct from old.total_value_paise or new.error_count is distinct from old.error_count
       or new.warning_count is distinct from old.warning_count or new.submitted_at is distinct from old.submitted_at
       or new.line_request_count is distinct from old.line_request_count
       or new.line_rows_written is distinct from old.line_rows_written then
      raise exception 'opening stock import totals are fixed once submitted' using errcode = 'check_violation';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_opening_stock_import_update() from public;
revoke all on function pharmacy.guard_opening_stock_import_update() from sahaj_app;

comment on function pharmacy.guard_opening_stock_import_update() is
  'Opening-stock import update guard. Merged body (V2026_12_17_1710) = 1100 + 1260 + 1270 + 1350: final states, DRAFT to REJECTED (DEC-076), counters never down in DRAFT and frozen once waiting (I8), one posting update on APPROVED. A later replacement must carry all of these. SQLSTATE 23514.';

-- ======================================================================================================================
-- 2. pharmacy.next_document_no(text): union of 1220, 1280 and 1380
--    From 1220: tenant from platform.current_tenant(), one unit per tenant, series restart rule (default: only BILL restarts),
--               fiscal year April to March in IST, upsert into document_counter, returns the number just issued.
--    From 1280: the upsert also sets last_issued_at (insert and update branch).
--    From 1380: the ten document types.
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
  'Next sequence number for the bound tenant, its unit, a document type and the current April-March financial year (IST). Sets last_issued_at. Ten document types. Merged body (V2026_12_17_1710) = 1220 + 1280 + 1380; a later replacement must carry all of these.';
