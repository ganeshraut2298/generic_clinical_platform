-- SAH-79 WP-D (story 79-1), migration D3. ADR-029 P5 "Opening stock posting", "Data needed" D3, OQ-79-30.
-- Additive: two nullable columns on pharmacy.opening_stock_import (posted_at, posted_by), one check, and create or replace of
-- pharmacy.guard_opening_stock_import_update() (same approach as V2026_12_17_1260). Existing rows keep both columns null
-- (nothing posted before this release). Grants are table-level (select, insert, update), so the new columns are covered.
-- The guard keeps every old rule (final states, transitions, frozen identity, frozen totals once submitted) and adds one: an APPROVED
-- import that is not yet posted may be updated once to set posted_at and posted_by (and the bookkeeping columns updated_at,
-- updated_by, version), and nothing else; a posted import is final.

set local lock_timeout = '30s';

alter table pharmacy.opening_stock_import
  add column if not exists posted_at timestamptz,
  add column if not exists posted_by uuid;

do $$
begin
  if not exists (select 1 from pg_catalog.pg_constraint
                  where conname = 'ck_opening_stock_import_posted'
                    and conrelid = 'pharmacy.opening_stock_import'::regclass) then
    -- Both set or both null; set only on an APPROVED import.
    alter table pharmacy.opening_stock_import
      add constraint ck_opening_stock_import_posted check (
        (posted_at is null) = (posted_by is null) and (posted_at is null or status = 'APPROVED'));
  end if;
end
$$;

comment on column pharmacy.opening_stock_import.posted_at is
  'When the approved import was posted to the stock ledger (SAH-79-1). Set once, in the same transaction as the ledger lines; null until then. A posted import is final.';
comment on column pharmacy.opening_stock_import.posted_by is
  'Membership id (opaque uuid, as uploaded_by and decided_by) of the person who posted. Set together with posted_at.';

-- Same body as V2026_12_17_1260 plus the posting update on an APPROVED row. Messages that existed keep their wording and SQLSTATE 23514.
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
    -- The one allowed update: posting. Everything except the posting and bookkeeping columns must stay as it was.
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
