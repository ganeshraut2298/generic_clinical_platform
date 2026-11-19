-- SAH-79 (DEC-061 programme; DEC-080 L14 follow-up; DEC-081; ADR-029 P7 final list and N3). Forward migration. No applied migration is edited.
--   1. L14: pharmacy.guard_stock_ledger_schedule_x() takes the final person-driven list of ADR-029 P7 (17 types; SHORTAGE_FOUND is new
--      on the list, the rest were already there in 1720).
--   2. stock_audit.second_signer_membership_id: the signer of a Schedule X variance posted at level 2 (the approver of the level-2 decision),
--      set once at approve-and-post; a check against the raiser, a guard rule against every counter of the audit.
--   3. Checked, nothing to add: goods_receipt_line.purchase_order_line_id (1410) and debit_note_line.goods_receipt_line_id (1420) already exist
--      as nullable columns with composite tenant foreign keys (fk_goods_receipt_line_po_line, fk_debit_note_line_grn_line).
-- Functions replaced here (each from the latest earlier body, only the stated change added):
--   pharmacy.guard_stock_ledger_schedule_x()  files before: V2026_12_17_1720 (only one). Change: the array.
--   pharmacy.guard_stock_audit_write()        files before: V2026_12_17_1700 (only one). Change: the second-signer rules.
-- The guardrail for repeated function bodies (DEC-080 I15) lists guard_stock_ledger_schedule_x with 1730 as the final file and the snippet
-- "second signer"; guard_stock_audit_write with 1730 and the snippet "second signer".

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. L14: the final list.
--    Body = 1720 (the trigger trg_stock_ledger_schedule_x of 1720 stays: it is BEFORE INSERT, FOR EACH ROW, when actor_kind = 'USER' and
--    second_signer_membership_id is null; create or replace keeps it). Only the array changes.
--    On the list (17): GRN, STOCK_ADJUSTMENT, VENDOR_RETURN, INDENT_DISPATCH, INDENT_RECEIPT_ACCEPTED, INDENT_RECEIPT_REJECTED,
--    TRANSFER_DISPATCH, TRANSFER_RECEIPT_ACCEPTED, TRANSFER_RECEIPT_REJECTED, RETURN_ACCEPTED, SHORTAGE_FOUND, STORE_RETURN,
--    DAMAGE_QUARANTINE, DAMAGE_SEND_TO_CS, WARD_CONSUMPTION, WARD_CONSUMPTION_PATIENT, STOCK_AUDIT_ADJUSTMENT.
--    Not on the list (system or approval driven): OPENING_STOCK, EXPIRY_QUARANTINE, VENDOR_RETURN_HOLD, VENDOR_RETURN_RELEASE,
--    SHORTAGE_WRITE_OFF, WRITE_OFF, DAMAGE_RELEASE. SAH-80 types are added by the SAH-80 migration that widens ck_stock_ledger_txn_type.
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
       'STOCK_AUDIT_ADJUSTMENT']::text[]) then
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
  'L14: a ledger line by a person (actor_kind USER) on a person-driven transaction type (ADR-029 P7 final list, 17 types) for a Schedule X product must carry second_signer_membership_id. Schedule from the own product row or, for an adopted product, the global catalogue. SQLSTATE 23514. Final body V2026_12_17_1730 (list); first body V2026_12_17_1720. A new person-driven type is added here, to the contract and to ADR-029 P7 in one change.';

-- ======================================================================================================================
-- 2. stock_audit.second_signer_membership_id
--    A Schedule X variance is posted at the final approval (level 2), often after the person who handled the stock (the raiser) left the
--    screen. The posting copies the signer from the document (ADR-029 P7, N3). The signer is the approver of the level-2 decision. The
--    ledger lines of these rows keep the raiser (submitted_by) as actor_membership_id, so the signer must never be the raiser
--    (ck_stock_ledger_signer_differs on the ledger would refuse it anyway), and must not be a person who counted a line of the audit.
--    Membership id as an opaque uuid, no foreign key to platform (as every other SAH-79 actor and signer column).
-- ======================================================================================================================
alter table pharmacy.stock_audit add column if not exists second_signer_membership_id uuid;

alter table pharmacy.stock_audit drop constraint if exists ck_stock_audit_signer_differs;
-- NOT VALID first (no scan in the ALTER), then validated at once: every existing row has a null signer, so validation cannot fail.
alter table pharmacy.stock_audit
  add constraint ck_stock_audit_signer_differs check (
    second_signer_membership_id is null
    or (submitted_by is not null and second_signer_membership_id <> submitted_by)) not valid;
alter table pharmacy.stock_audit validate constraint ck_stock_audit_signer_differs;

comment on column pharmacy.stock_audit.second_signer_membership_id is
  'Membership id of the second signer of a Schedule X variance posted at approve-and-post (the approver of the level-2 decision). Set once, only while the audit is WAITING_REVIEW or WAITING_APPROVAL; never the person who submitted the count and never a person who counted a line (guard_stock_audit_write). Copied to the STOCK_AUDIT_ADJUSTMENT ledger lines. Null when no Schedule X line was posted.';

-- Guard: body of 1700 (the only earlier version) plus the second-signer rules (marked 1730).
create or replace function pharmacy.guard_stock_audit_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'INSERT' then
    if new.status <> 'COUNTING' then
      raise exception 'stock audit must start counting' using errcode = 'check_violation';
    end if;
    -- 1730: the signer is taken at approve-and-post, never at the start.
    if new.second_signer_membership_id is not null then
      raise exception 'stock audit second signer is set when the variance is posted' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if old.status = 'CLOSED' then
    raise exception 'stock audit is final (%)', old.status using errcode = 'check_violation';
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.store_id is distinct from old.store_id or new.scope is distinct from old.scope
     or new.doc_seq is distinct from old.doc_seq or new.doc_no is distinct from old.doc_no
     or new.started_at is distinct from old.started_at or new.created_by is distinct from old.created_by then
    raise exception 'stock audit identity cannot change' using errcode = 'check_violation';
  end if;
  if new.status is distinct from old.status and not (
       (old.status in ('COUNTING', 'RECOUNT') and new.status = 'WAITING_REVIEW')
    or (old.status = 'WAITING_REVIEW' and new.status in ('RECOUNT', 'WAITING_APPROVAL', 'CLOSED'))
    or (old.status = 'WAITING_APPROVAL' and new.status in ('CLOSED', 'COUNTING', 'RECOUNT'))) then
    raise exception 'stock audit cannot go from % to %', old.status, new.status using errcode = 'check_violation';
  end if;
  if old.submitted_at is not null and new.submitted_at is null then
    raise exception 'stock audit submission cannot be cleared' using errcode = 'check_violation';
  end if;
  -- 1730: second signer. Set once; only at the review or the approval; never a person who counted a line of this audit.
  if new.second_signer_membership_id is distinct from old.second_signer_membership_id then
    if old.second_signer_membership_id is not null then
      raise exception 'stock audit second signer is set once' using errcode = 'check_violation';
    end if;
    if old.status not in ('WAITING_REVIEW', 'WAITING_APPROVAL') then
      raise exception 'stock audit second signer is set when the variance is posted' using errcode = 'check_violation';
    end if;
    if exists (select 1 from pharmacy.stock_audit_line l
                where l.tenant_id = new.tenant_id and l.stock_audit_id = new.id
                  and l.counted_by = new.second_signer_membership_id) then
      raise exception 'stock audit second signer cannot be a person who counted' using errcode = 'check_violation';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_stock_audit_write() from public;
revoke all on function pharmacy.guard_stock_audit_write() from sahaj_app;

comment on function pharmacy.guard_stock_audit_write() is
  'Stock audit header guard. V2026_12_17_1700: start counting, CLOSED final, identity, status moves, submission never cleared. V2026_12_17_1730 adds the second signer: null at insert, set once, only from WAITING_REVIEW or WAITING_APPROVAL, never a person who counted a line. SQLSTATE 23514.';
