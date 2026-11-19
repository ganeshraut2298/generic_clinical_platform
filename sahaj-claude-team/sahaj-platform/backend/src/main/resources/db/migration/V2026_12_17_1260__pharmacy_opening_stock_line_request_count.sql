-- SAH-78 WP-B (story 78-7), migration B5. Security finding L8(b): contract docs/api/pharmacy.yaml 0.1.3 and ADR-028 Revision 5 (R5-3).
-- Each opening-stock import accepts at most 2 x ceil(row_count / 200) line requests answered 200; the next one is 409 import-state.
-- The service reads and increments the counter while it holds the import row lock (select ... for update), in the same transaction
-- as the line writes. Additive: existing rows get 0. No table is created, no grant changes (grants on this table are table-level:
-- select, insert, update to sahaj_app, so the new column is covered).
-- Only the update guard function changes (create or replace, same search_path and same revoked execute as in migration B3): a waiting
-- import now keeps its line_request_count like its other totals. A DRAFT import may change it freely.

set local lock_timeout = '30s';

alter table pharmacy.opening_stock_import
  add column if not exists line_request_count integer not null default 0;

do $$
begin
  if not exists (select 1 from pg_catalog.pg_constraint
                  where conname = 'ck_opening_stock_import_line_request_count'
                    and conrelid = 'pharmacy.opening_stock_import'::regclass) then
    alter table pharmacy.opening_stock_import
      add constraint ck_opening_stock_import_line_request_count check (line_request_count >= 0);
  end if;
end
$$;

comment on column pharmacy.opening_stock_import.line_request_count is
  'Line requests already accepted (answered 200) for this import while it is a DRAFT (SAH-78-7, ADR-028 R5-3). Limit 2 x ceil(row_count / 200), checked by the service under the import row lock. Frozen once the import leaves DRAFT.';

-- Same body as migration B3 (V2026_12_17_1100) plus one test: line_request_count is fixed once submitted.
create or replace function pharmacy.guard_opening_stock_import_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if old.status in ('APPROVED', 'REJECTED', 'NOT_ACCEPTED', 'REPLACED') then
    raise exception 'opening stock import is final (%)', old.status using errcode = 'check_violation';
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.store_id is distinct from old.store_id or new.uploaded_by is distinct from old.uploaded_by
     or new.client_key is distinct from old.client_key or new.row_count is distinct from old.row_count
     or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
    raise exception 'opening stock import identity cannot change' using errcode = 'check_violation';
  end if;
  if old.status = 'DRAFT' then
    if new.status not in ('DRAFT', 'NOT_ACCEPTED', 'WAITING_APPROVAL', 'REPLACED') then
      raise exception 'opening stock import cannot go from draft to %', new.status using errcode = 'check_violation';
    end if;
  else  -- WAITING_APPROVAL
    if new.status not in ('APPROVED', 'REJECTED', 'REPLACED') then
      raise exception 'opening stock import waiting for approval can only be approved, rejected or replaced' using errcode = 'check_violation';
    end if;
    if new.line_count is distinct from old.line_count or new.total_quantity is distinct from old.total_quantity
       or new.total_value_paise is distinct from old.total_value_paise or new.error_count is distinct from old.error_count
       or new.warning_count is distinct from old.warning_count or new.submitted_at is distinct from old.submitted_at
       or new.line_request_count is distinct from old.line_request_count then
      raise exception 'opening stock import totals are fixed once submitted' using errcode = 'check_violation';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_opening_stock_import_update() from public;
revoke all on function pharmacy.guard_opening_stock_import_update() from sahaj_app;
