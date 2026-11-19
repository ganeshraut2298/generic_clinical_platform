-- SAH-78 / SAH-79 review round 4 hardening (DEC-080, DEC-061 programme). Forward migration. No applied migration is edited.
-- Findings: security L11, L14, I11; code review m-3, m-4, m-5, m-6, n-1. Every item is small and additive. No row is changed or removed.
--
--   1. L11  pharmacy.next_document_no(text): reads the unit row FOR SHARE (replaces the body of 1710, which is the union of 1220, 1280, 1380).
--   2. L14  ledger BEFORE INSERT trigger: a Schedule X movement by a person needs a second signer.
--   3. I11a approval_request cannot become APPROVED without an APPROVED decision for every level that applies.
--   4. I11b approval_rule_set points only forward (the version number never goes down).
--   5. m-3  ck_vendor_payable_settled: OPEN needs credited_paise < amount_paise (or amount_paise = 0).
--   6. m-4  indent: a second guard for forward moves only after APPROVED (the 1500 guard is not replaced).
--   7. m-5  store: INACTIVE refused while any stock_balance row of the store has qty > 0 (the 1010 guard is not replaced).
--   8. m-6  index (tenant_id, store_id, occurred_at) on stock_ledger.
--   n-1: the two changed checks (m-3) use NOT VALID, then validate, as in 1230.
--
-- Only one function is replaced here (next_document_no, item 1). Every other function is new. The guardrail for repeated function bodies
-- (DEC-080 I15) must list next_document_no with this file as the final one.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. L11: pharmacy.next_document_no(text) reads the unit row FOR SHARE.
--    A series save (NumberingService) holds the unit row FOR UPDATE and then checks "a number of this financial year was issued".
--    An issue now takes the same row FOR SHARE first: a save waits for an issue in flight, and an issue waits for a save in flight, so
--    the save always sees a number that is issued but not yet committed (it waits for the commit), and an issue never runs on a series
--    that is half way through a change. Two issues do not wait for each other here (SHARE with SHARE); they still queue on the counter row
--    exactly as before.
--    Body = 1710 (which is 1220 + 1280 + 1380), with only the lock added. Files that defined it before: V2026_12_17_1220 (first body),
--    V2026_12_17_1280 (last_issued_at), V2026_12_17_1380 (ten types), V2026_12_17_1710 (union). Signature, privileges, last_issued_at and
--    the ten document types are unchanged. sahaj_app can lock the row: it has select and update on pharmacy.unit and the update policy.
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
  -- L11 (1720): FOR SHARE, so a series save (which holds this row FOR UPDATE) and an issue in flight wait for each other.
  select u.id into v_unit from pharmacy.unit u where u.tenant_id = v_tenant for share;
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
  'Next sequence number for the bound tenant, its unit, a document type and the current April-March financial year (IST). Reads the unit row FOR SHARE (L11: a series save holds it FOR UPDATE). Sets last_issued_at. Ten document types. Final body (V2026_12_17_1720) = 1220 + 1280 + 1380 + 1710 + the share lock; a later replacement must carry all of these.';

-- ======================================================================================================================
-- 2. L14: a Schedule X movement by a person needs a second signer.
--    Fires for a USER line without a signer only (the WHEN clause), then looks the product up under the caller's row level security
--    (tenant of the line) and reads the schedule: an own product has it on the row, an adopted product on the global catalogue
--    (pharmacy.product.schedule is null for ADOPTED; the check ck_product_kind_fields). SYSTEM lines (the expiry quarantine) and the
--    transaction types that are not a person handling stock (opening stock posting, approval-driven holds, releases) are not on the list.
--    The service remains the first line (pharmacist role, PIN); this is the backstop for a service that skips it.
--    Row trigger on the partitioned parent: PostgreSQL clones it to every existing and future monthly partition (as the 1330 deny trigger).
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
       'GRN', 'STOCK_ADJUSTMENT', 'INDENT_DISPATCH', 'INDENT_RECEIPT_ACCEPTED', 'INDENT_RECEIPT_REJECTED', 'RETURN_ACCEPTED',
       'STORE_RETURN', 'DAMAGE_QUARANTINE', 'DAMAGE_SEND_TO_CS', 'VENDOR_RETURN', 'TRANSFER_DISPATCH', 'TRANSFER_RECEIPT_ACCEPTED',
       'TRANSFER_RECEIPT_REJECTED', 'WARD_CONSUMPTION', 'WARD_CONSUMPTION_PATIENT', 'STOCK_AUDIT_ADJUSTMENT']::text[]) then
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

drop trigger if exists trg_stock_ledger_schedule_x on pharmacy.stock_ledger;
create trigger trg_stock_ledger_schedule_x before insert on pharmacy.stock_ledger
  for each row when (new.actor_kind = 'USER' and new.second_signer_membership_id is null)
  execute function pharmacy.guard_stock_ledger_schedule_x();

comment on function pharmacy.guard_stock_ledger_schedule_x() is
  'L14 (V2026_12_17_1720): a ledger line by a person (actor_kind USER) on a person-driven transaction type for a Schedule X product must carry second_signer_membership_id. Schedule from the own product row or, for an adopted product, the global catalogue. SQLSTATE 23514.';

-- ======================================================================================================================
-- 3. I11a: an approval request cannot become APPROVED without its decisions.
--    Every level the request applies to (approval_request_level.applies) needs an APPROVED decision row. A request with no level rows
--    is not refused (the service always writes them at submit; a request written without them, for example by a direct test insert,
--    passes as before). Both tables are insert-only, so a row seen here can never disappear: no lock is needed. A decision that another
--    open transaction has not committed is not seen, which fails safe (the move to APPROVED is refused and can be retried).
--    A new trigger function; guard_approval_request_update (1360) is not replaced.
-- ======================================================================================================================
create or replace function pharmacy.guard_approval_request_approve()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if old.status = 'PENDING' and new.status = 'APPROVED' then
    if exists (select 1
                 from pharmacy.approval_request_level l
                where l.tenant_id = old.tenant_id and l.request_id = old.id and l.applies
                  and not exists (select 1
                                    from pharmacy.approval_decision d
                                   where d.tenant_id = l.tenant_id and d.request_id = l.request_id
                                     and d.level_no = l.level_no and d.decision = 'APPROVED')) then
      raise exception 'approval request needs an approved decision for each level that applies' using errcode = 'check_violation';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_approval_request_approve() from public;
revoke all on function pharmacy.guard_approval_request_approve() from sahaj_app;

drop trigger if exists trg_approval_request_guard_approve on pharmacy.approval_request;
create trigger trg_approval_request_guard_approve before update on pharmacy.approval_request
  for each row when (old.status = 'PENDING' and new.status = 'APPROVED')
  execute function pharmacy.guard_approval_request_approve();

comment on function pharmacy.guard_approval_request_approve() is
  'I11 (V2026_12_17_1720): PENDING to APPROVED needs an APPROVED approval_decision for every approval_request_level with applies = true. SQLSTATE 23514.';

-- ======================================================================================================================
-- 4. I11b: the rule-set pointer only moves forward.
--    approval_rule_set.current_version_id may change only to a version of the same unit with a higher version_no. Versions are
--    insert-only, so the comparison needs no lock. A new trigger function (no earlier trigger exists on this table).
-- ======================================================================================================================
create or replace function pharmacy.guard_approval_rule_set_forward()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_old_no integer;
  v_new_no integer;
begin
  select v.version_no into v_old_no
    from pharmacy.approval_rule_version v
   where v.tenant_id = old.tenant_id and v.id = old.current_version_id;
  select v.version_no into v_new_no
    from pharmacy.approval_rule_version v
   where v.tenant_id = new.tenant_id and v.id = new.current_version_id and v.unit_id = new.unit_id;
  if v_new_no is null or v_old_no is null or v_new_no <= v_old_no then
    raise exception 'approval rule set can only move to a newer version' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_approval_rule_set_forward() from public;
revoke all on function pharmacy.guard_approval_rule_set_forward() from sahaj_app;

drop trigger if exists trg_approval_rule_set_guard_forward on pharmacy.approval_rule_set;
create trigger trg_approval_rule_set_guard_forward before update on pharmacy.approval_rule_set
  for each row when (new.current_version_id is distinct from old.current_version_id)
  execute function pharmacy.guard_approval_rule_set_forward();

comment on function pharmacy.guard_approval_rule_set_forward() is
  'I11 (V2026_12_17_1720): the approval_rule_set pointer moves only to a higher version_no of the same unit. SQLSTATE 23514.';

-- ======================================================================================================================
-- 5. m-3 and n-1: ck_vendor_payable_settled. OPEN needs something left to credit (credited < amount), or an amount of 0.
--    NOT VALID first (enforced for every new or changed row at once, no scan in the ALTER), then validated in a guarded block as 1230.
--    The table is empty on a database built from scratch; on a database that has rows with OPEN and credited = amount the validation
--    fails, a WARNING is raised and the constraint stays NOT VALID until the owner of the row fixes it.
-- ======================================================================================================================
alter table pharmacy.vendor_payable drop constraint if exists ck_vendor_payable_settled;
alter table pharmacy.vendor_payable
  add constraint ck_vendor_payable_settled check (
    (status = 'SETTLED_BY_CREDIT' and credited_paise = amount_paise)
    or (status = 'OPEN' and (credited_paise < amount_paise or amount_paise = 0))) not valid;

do $$
begin
  alter table pharmacy.vendor_payable validate constraint ck_vendor_payable_settled;
exception when check_violation then
  raise warning 'pharmacy.vendor_payable has an OPEN row that is fully credited; ck_vendor_payable_settled stays NOT VALID (new rows are checked). Settle the row, then validate the constraint by hand.';
end;
$$;

comment on constraint ck_vendor_payable_settled on pharmacy.vendor_payable is
  'SETTLED_BY_CREDIT exactly when credited_paise = amount_paise; OPEN while credited_paise < amount_paise (or the invoice is 0). V2026_12_17_1720 (m-3) tightened OPEN, which used to allow a fully credited row.';

-- ======================================================================================================================
-- 6. m-4: indent moves are forward only once the indent is APPROVED.
--    The 1500 guard (guard_indent_write) is not replaced. This second guard refuses the backward and the jumping moves it still allowed.
--    Contract (docs/api/pharmacy.yaml 0.3.2): dispatch needs APPROVED or PARTLY_DISPATCHED and gives PARTLY_DISPATCHED (back-order open)
--    or DISPATCHED; a receipt gives RECEIVED; the return acceptance may give RECEIVED or CLOSED; cancel is only from WAITING_APPROVAL or
--    APPROVED with nothing dispatched. Allowed from here:
--      APPROVED           -> PARTLY_DISPATCHED, DISPATCHED, CANCELLED
--      PARTLY_DISPATCHED  -> DISPATCHED, RECEIVED, CLOSED
--      DISPATCHED         -> RECEIVED, CLOSED
--      RECEIVED           -> CLOSED
--    Not allowed: anything back to APPROVED or DISPATCHED from a later state, RECEIVED back to PARTLY_DISPATCHED (the design note of
--    SAH-79-inventory section on indents allowed it for a back-order dispatched after a receipt; the contract allows a dispatch only
--    from APPROVED or PARTLY_DISPATCHED, so the contract wins and the note is corrected), a cancel from PARTLY_DISPATCHED (the contract
--    has none), and APPROVED straight to RECEIVED or CLOSED. Same message as the 1500 guard. States that are final, and the moves
--    before APPROVED, stay with the 1500 guard.
-- ======================================================================================================================
create or replace function pharmacy.guard_indent_forward()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if not (
       (old.status = 'APPROVED' and new.status in ('PARTLY_DISPATCHED', 'DISPATCHED', 'CANCELLED'))
    or (old.status = 'PARTLY_DISPATCHED' and new.status in ('DISPATCHED', 'RECEIVED', 'CLOSED'))
    or (old.status = 'DISPATCHED' and new.status in ('RECEIVED', 'CLOSED'))
    or (old.status = 'RECEIVED' and new.status = 'CLOSED')) then
    raise exception 'indent cannot go from % to %', old.status, new.status using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_indent_forward() from public;
revoke all on function pharmacy.guard_indent_forward() from sahaj_app;

drop trigger if exists trg_indent_guard_forward on pharmacy.indent;
create trigger trg_indent_guard_forward before update on pharmacy.indent
  for each row when (old.status in ('APPROVED', 'PARTLY_DISPATCHED', 'DISPATCHED', 'RECEIVED') and new.status is distinct from old.status)
  execute function pharmacy.guard_indent_forward();

comment on function pharmacy.guard_indent_forward() is
  'm-4 (V2026_12_17_1720): after APPROVED an indent moves forward only: APPROVED to PARTLY_DISPATCHED, DISPATCHED or CANCELLED; PARTLY_DISPATCHED to DISPATCHED, RECEIVED or CLOSED; DISPATCHED to RECEIVED or CLOSED; RECEIVED to CLOSED. Works with guard_indent_write (1500). SQLSTATE 23514.';

-- ======================================================================================================================
-- 7. m-5: a store cannot be switched off while it holds stock.
--    Any stock_balance row of the store with qty > 0, in any state (AVAILABLE, IN_TRANSIT, PENDING_RETURN, QUARANTINE), refuses the
--    move to INACTIVE. A new trigger function; guard_store_update (1010) is not replaced, and the service rule on has_stock stays.
--    The posting service locks the store row (shared) before it writes a balance, so a posting racing a switch-off is ordered by the
--    service; a foreign key alone would not do it (FOR KEY SHARE does not conflict with this update).
--    Reads stock_balance under the caller's row level security (tenant), served by ix_stock_balance_sweep (tenant_id, store_id, state) where qty > 0.
-- ======================================================================================================================
create or replace function pharmacy.guard_store_inactive_stock()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if exists (select 1 from pharmacy.stock_balance b
              where b.tenant_id = old.tenant_id and b.store_id = old.id and b.qty > 0) then
    raise exception 'store still holds stock' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_store_inactive_stock() from public;
revoke all on function pharmacy.guard_store_inactive_stock() from sahaj_app;

drop trigger if exists trg_store_guard_inactive_stock on pharmacy.store;
create trigger trg_store_guard_inactive_stock before update of status on pharmacy.store
  for each row when (new.status = 'INACTIVE' and old.status is distinct from 'INACTIVE')
  execute function pharmacy.guard_store_inactive_stock();

comment on function pharmacy.guard_store_inactive_stock() is
  'm-5 (V2026_12_17_1720): a store cannot become INACTIVE while any of its stock_balance rows has qty > 0 (any state, including in transit, pending return and quarantine). SQLSTATE 23514.';

-- ======================================================================================================================
-- 8. m-6: the ledger of one store between two dates ("stock ledger of a store" report, day book).
--    On the partitioned parent: PostgreSQL builds it on every partition and on every partition made later (create_ledger_partitions).
--    The table is empty on a database built from scratch, so a plain CREATE INDEX is cheap; a partitioned table cannot use CONCURRENTLY.
-- ======================================================================================================================
create index if not exists ix_stock_ledger_store_time on pharmacy.stock_ledger (tenant_id, store_id, occurred_at);
