-- SAH-78 hardening after security round 3 (DEC-076, DEC-077). Forward migration; V2026_12_17_1260 and V2026_12_17_1350 are NOT edited.
-- 1. New counter opening_stock_import.line_rows_written (DEC-077 item 2, I7, L8(b)): rows written (inserted or changed) while the import
--    is a DRAFT. The service caps it at 2 x row_count under the import row lock. The old line_request_count stays (unused; no rename,
--    no drop). Additive: existing rows get 0 (fast default). Grants are table-level, so the new column is covered.
-- 2. Guard trigger (DEC-077 item 2, I8): in DRAFT neither counter may go down; once the import has left DRAFT both are frozen.
-- 3. DEC-076: an owner may reject a DRAFT. The transition trigger now allows DRAFT -> REJECTED, and ck_opening_stock_import_submitted
--    no longer demands submitted_at for a rejected draft (see below). ck_opening_stock_import_decision and
--    ck_opening_stock_import_approver_differs are kept as they are.
-- 4. DEC-077 item 1 (L9): insert trigger, an import may not be created for a store that is not ACTIVE.
-- Nothing is rewritten: no existing row changes.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. The new counter
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

comment on column pharmacy.opening_stock_import.line_rows_written is
  'Line rows written (inserted or changed; an unchanged resend counts 0) for this import while it is a DRAFT (SAH-78-7, DEC-077 item 2). Limit 2 x row_count, checked by the service under the import row lock. Never goes down in DRAFT; frozen once the import leaves DRAFT.';
comment on column pharmacy.opening_stock_import.line_request_count is
  'Not used since DEC-077 (replaced by line_rows_written). Kept, not renamed or dropped. Same freeze rules as before, plus it never goes down in DRAFT.';

-- ======================================================================================================================
-- 2. ck_opening_stock_import_submitted: a rejected DRAFT never had a submit time.
--    Old: status in (DRAFT, NOT_ACCEPTED, REPLACED) or submitted_at is not null   (so REJECTED needed submitted_at)
--    New: status not in (WAITING_APPROVAL, APPROVED) or submitted_at is not null  (REJECTED may or may not have it)
--    Every old row passes: the new check is weaker for REJECTED and equal for the other five states.
-- ======================================================================================================================
alter table pharmacy.opening_stock_import drop constraint if exists ck_opening_stock_import_submitted;
alter table pharmacy.opening_stock_import add constraint ck_opening_stock_import_submitted check (
  status not in ('WAITING_APPROVAL', 'APPROVED') or submitted_at is not null);

-- ======================================================================================================================
-- 3. Guard: body of V2026_12_17_1350 plus (a) DRAFT -> REJECTED allowed, (b) counters never go down in DRAFT, frozen afterwards.
--    Same search_path, not SECURITY DEFINER, execute revoked from public and sahaj_app. Messages that existed are unchanged.
--    New message: 'opening stock import line counter cannot go down' (SQLSTATE 23514).
--    A DRAFT -> REJECTED row keeps whatever submitted_at it had (null); the check above allows it; ck_..._decision still requires
--    decided_by and decided_at, ck_..._reject still requires the reason (and a note for OTHER).
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
    -- DEC-076: DRAFT -> REJECTED added.
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
-- The trigger trg_opening_stock_import_guard_update (V2026_12_17_1100) already calls this function; create or replace keeps it attached.

-- ======================================================================================================================
-- 4. Backstop for L9: an import cannot be created for a store that is not ACTIVE.
--    The service locks the store row (for update) before it inserts. This trigger reads the store row "for share", so even a statement
--    that skipped the service check cannot slip in between a switch-off and its commit: the switch-off update (for no key update)
--    and this share lock conflict, so one waits for the other. Runs as the caller; sahaj_app can select and update store (RLS applies).
--    An unknown store or a store of another tenant is left to the foreign key (23503).
-- ======================================================================================================================
create or replace function pharmacy.guard_opening_stock_import_store_active()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_status text;
begin
  select s.status into v_status
    from pharmacy.store s
   where s.tenant_id = new.tenant_id and s.id = new.store_id
     for share;
  if v_status is not null and v_status <> 'ACTIVE' then
    raise exception 'opening stock import needs an active store' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_opening_stock_import_store_active() from public;
revoke all on function pharmacy.guard_opening_stock_import_store_active() from sahaj_app;

drop trigger if exists trg_opening_stock_import_store_active on pharmacy.opening_stock_import;
create trigger trg_opening_stock_import_store_active before insert on pharmacy.opening_stock_import
  for each row execute function pharmacy.guard_opening_stock_import_store_active();

comment on function pharmacy.guard_opening_stock_import_store_active() is
  'SAH-78 security L9, DEC-077: an opening-stock import cannot be inserted for a store that is not ACTIVE. SQLSTATE 23514. Takes the store row FOR SHARE.';
