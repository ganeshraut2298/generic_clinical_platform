-- SAH-78 hardening, DEC-078 item 4 (series locking). Forward migration; V2026_12_17_1220 and V2026_12_17_1380 are NOT edited.
-- 1. document_counter.last_issued_at timestamptz (nullable): the time the last number of that counter row was issued.
-- 2. pharmacy.next_document_no(text): create or replace; same signature, same ten document types, same search_path, same privileges
--    (execute for sahaj_app only), same behaviour and same returned value, plus one more column in the upsert (last_issued_at).
-- 3. How the service decides "a number of the current financial year has been issued" (for 409 series-locked):
--      the series is locked when a counter row exists for (tenant, unit, doc_type) and
--      coalesce(last_issued_at, updated_at) >= the start of the current IST financial year (1 April 00:00 IST).
--    For a series that restarts each year the row of the current fiscal_year exists exactly when a number of this year was issued; for a
--    series that never restarts (fiscal_year = 0) the single row exists for ever, so the time test is what makes it "this year".
--    Rows written before this migration have last_issued_at null. No backfill (a rewrite is not allowed and the guard trigger refuses an
--    update that does not raise next_value): the function has always set updated_at = now() on every issue and nothing else writes the
--    counter, so coalesce(last_issued_at, updated_at) is the same instant for old rows.
-- Nothing in this file changes or removes a row.

set local lock_timeout = '30s';

alter table pharmacy.document_counter
  add column if not exists last_issued_at timestamptz;

comment on column pharmacy.document_counter.last_issued_at is
  'When pharmacy.next_document_no() last issued a number from this counter row (DEC-078 item 4). Null only on rows issued before this column existed; read coalesce(last_issued_at, updated_at). Used to lock a series once a number of the current financial year has been issued.';

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
