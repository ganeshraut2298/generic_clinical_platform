-- SAH-79 round 5 hardening (DEC-082; java WP-E deviations 1 and 5; security L16; code review m-7). Additive. Nothing here edits an applied file.
--   1. A debit note with no STOCK_RETURN line (short supply only) may go CREATED or PICKED -> CREDIT_NOTE_RECEIVED, and may take a credit
--      insert in those states. Three places refused it: the status guard, the credit insert guard (both replaced below) and the check
--      ck_debit_note_handed_over_status (relaxed below; it demanded a hand-over time for CREDIT_NOTE_RECEIVED, which a note with no goods never has).
--   2. goods_receipt APPROVED -> DISCARDED directly (the contract allows "DRAFT or APPROVED" on the discard operation).
--      purchase_order SENT -> CLOSED_SHORT is NOT added: the contract (pharmacyClosePurchaseOrderShort) says "PARTLY_RECEIVED only (409 doc-state)".
--   3. L16: ck_stock_ledger_system_types on the ledger parent.
--   4. m-7: a corrected comment on the store switch-off guard (see section 4; the comment in 1720 lines 268 to 272 is not edited).
-- Function bodies are taken from the latest definition: guard_debit_note_write and guard_debit_note_credit_insert from
-- V2026_12_17_1420 (the only file that defines them); guard_goods_receipt_write from V2026_12_17_1410 (the only file that defines it).
-- Each carries every line of that body plus the one rule named here. Messages, search_path = pg_catalog and revokes are unchanged.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1a. ck_debit_note_handed_over_status: HANDED_OVER needs the hand-over time; CREDIT_NOTE_RECEIVED does not (a short-supply-only note).
--     A weaker check than before, so every existing row passes it (validate cannot fail). The rule that CREDIT_NOTE_RECEIVED after
--     CREATED or PICKED needs a note with no live STOCK_RETURN line is in guard_debit_note_write (a check cannot read another table).
--     From HANDED_OVER the hand-over data exists and is set once (guard), so a note with goods still has it.
-- ======================================================================================================================
alter table pharmacy.debit_note drop constraint if exists ck_debit_note_handed_over_status;
alter table pharmacy.debit_note
  add constraint ck_debit_note_handed_over_status check (status <> 'HANDED_OVER' or handed_over_at is not null) not valid;
alter table pharmacy.debit_note validate constraint ck_debit_note_handed_over_status;

comment on constraint ck_debit_note_handed_over_status on pharmacy.debit_note is
  'A HANDED_OVER note has its hand-over time. CREDIT_NOTE_RECEIVED has it too unless the note has no STOCK_RETURN line (short supply only, V2026_12_17_1740); guard_debit_note_write keeps that rule.';

-- ======================================================================================================================
-- 1b. guard_debit_note_write: body of 1420 plus one move: CREATED or PICKED -> CREDIT_NOTE_RECEIVED when the note has no live
--     STOCK_RETURN line. A note with goods still needs HANDED_OVER first. Same message as before for every refused move.
--     The lines of a note are frozen once it leaves DRAFT (guard_debit_note_line_write), so the read is stable. Read as the caller (RLS),
--     served by ix_debit_note_line_note (tenant_id, debit_note_id, sort).
-- ======================================================================================================================
create or replace function pharmacy.guard_debit_note_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'INSERT' then
    if new.status <> 'DRAFT' then
      raise exception 'debit note must start as a draft' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if old.status in ('REJECTED', 'CREDIT_NOTE_RECEIVED', 'CANCELLED', 'DISCARDED') then
    raise exception 'debit note is final (%)', old.status using errcode = 'check_violation';
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.store_id is distinct from old.store_id or new.prepared_from_grn_id is distinct from old.prepared_from_grn_id
     or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by
     or (old.doc_no is not null and new.doc_no is distinct from old.doc_no)
     or (old.doc_seq is not null and new.doc_seq is distinct from old.doc_seq) then
    raise exception 'debit note identity cannot change' using errcode = 'check_violation';
  end if;
  if new.status is distinct from old.status and not (
       (old.status = 'DRAFT' and new.status in ('WAITING_APPROVAL', 'CREATED', 'DISCARDED'))
    or (old.status = 'WAITING_APPROVAL' and new.status in ('CREATED', 'REJECTED', 'CANCELLED'))
    or (old.status = 'CREATED' and new.status in ('PICKED', 'HANDED_OVER', 'CANCELLED'))
    or (old.status = 'PICKED' and new.status in ('HANDED_OVER', 'CANCELLED'))
    or (old.status = 'HANDED_OVER' and new.status = 'CREDIT_NOTE_RECEIVED')
    -- Short supply only (V2026_12_17_1740): no STOCK_RETURN line means no goods to hand over, so the credit note may follow CREATED or PICKED.
    or (old.status in ('CREATED', 'PICKED') and new.status = 'CREDIT_NOTE_RECEIVED'
        and not exists (select 1 from pharmacy.debit_note_line l
                         where l.tenant_id = old.tenant_id and l.debit_note_id = old.id
                           and l.line_type = 'STOCK_RETURN' and not l.removed))) then
    raise exception 'debit note cannot go from % to %', old.status, new.status using errcode = 'check_violation';
  end if;
  if old.status <> 'DRAFT' and (
       new.vendor_id is distinct from old.vendor_id or new.reason_key is distinct from old.reason_key
       or new.note is distinct from old.note or new.credit_total_paise is distinct from old.credit_total_paise
       or new.gst_reversal_paise is distinct from old.gst_reversal_paise) then
    raise exception 'debit note cannot change once submitted' using errcode = 'check_violation';
  end if;
  if (old.picked_at is not null and (new.picked_at is distinct from old.picked_at or new.picked_by is distinct from old.picked_by))
     or (old.handed_over_at is not null and (new.handed_over_at is distinct from old.handed_over_at
         or new.handed_over_by is distinct from old.handed_over_by
         or new.representative_name is distinct from old.representative_name
         or new.second_signer_membership_id is distinct from old.second_signer_membership_id)) then
    raise exception 'debit note picking and hand-over data are set once' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_debit_note_write() from public;
revoke all on function pharmacy.guard_debit_note_write() from sahaj_app;

comment on function pharmacy.guard_debit_note_write() is
  'Debit note status and data guard (1420). V2026_12_17_1740 adds: CREATED or PICKED to CREDIT_NOTE_RECEIVED when the note has no live STOCK_RETURN line (short supply only). SQLSTATE 23514.';

-- ======================================================================================================================
-- 1c. guard_debit_note_credit_insert: body of 1420 plus one rule: a credit note is also accepted for a CREATED or PICKED note that has no
--     live STOCK_RETURN line. A note with goods still needs HANDED_OVER (or CREDIT_NOTE_RECEIVED). Same message.
-- ======================================================================================================================
create or replace function pharmacy.guard_debit_note_credit_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_status text;
begin
  select d.status into v_status from pharmacy.debit_note d where d.tenant_id = new.tenant_id and d.id = new.debit_note_id;
  if v_status is not null and v_status not in ('HANDED_OVER', 'CREDIT_NOTE_RECEIVED') then
    -- Short supply only (V2026_12_17_1740): CREATED or PICKED is fine when no STOCK_RETURN line is live.
    if v_status in ('CREATED', 'PICKED')
       and not exists (select 1 from pharmacy.debit_note_line l
                        where l.tenant_id = new.tenant_id and l.debit_note_id = new.debit_note_id
                          and l.line_type = 'STOCK_RETURN' and not l.removed) then
      return new;
    end if;
    raise exception 'credit note can be recorded only after hand-over' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_debit_note_credit_insert() from public;
revoke all on function pharmacy.guard_debit_note_credit_insert() from sahaj_app;

comment on function pharmacy.guard_debit_note_credit_insert() is
  'A credit note is recorded only for a HANDED_OVER (or CREDIT_NOTE_RECEIVED) debit note (1420); V2026_12_17_1740 also accepts CREATED or PICKED when the note has no live STOCK_RETURN line (short supply only). SQLSTATE 23514.';

-- ======================================================================================================================
-- 2. guard_goods_receipt_write: body of 1410 plus one move: APPROVED -> DISCARDED directly (contract: the discard operation takes a
--    DRAFT or an APPROVED, unposted receipt). Before, an approved receipt went APPROVED -> DRAFT -> DISCARDED. Still allowed as before:
--    APPROVED -> POSTED and APPROVED -> DRAFT. Not allowed: POSTED or DISCARDED to anything (final), so discarding a posted receipt
--    stays refused. WAITING_APPROVAL -> DISCARDED is allowed by the 1410 guard and stays; the contract refuses it in the service (409 doc-state).
--    purchase_order SENT -> CLOSED_SHORT is not added: the contract allows close-short from PARTLY_RECEIVED only. guard_purchase_order_write
--    (1400) is therefore not replaced here.
-- ======================================================================================================================
create or replace function pharmacy.guard_goods_receipt_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'INSERT' then
    if new.status <> 'DRAFT' then
      raise exception 'goods receipt must start as a draft' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if old.status = 'DISCARDED' then
    raise exception 'goods receipt is final (%)', old.status using errcode = 'check_violation';
  end if;
  if old.status = 'POSTED' then
    if (pg_catalog.to_jsonb(new) - 'updated_at' - 'updated_by' - 'version')
       is distinct from (pg_catalog.to_jsonb(old) - 'updated_at' - 'updated_by' - 'version') then
      raise exception 'goods receipt is final (%)', old.status using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.store_id is distinct from old.store_id or new.purchase_order_id is distinct from old.purchase_order_id
     or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
    raise exception 'goods receipt identity cannot change' using errcode = 'check_violation';
  end if;
  if new.status is distinct from old.status and not (
       (old.status = 'DRAFT' and new.status in ('WAITING_APPROVAL', 'POSTED', 'DISCARDED'))
    or (old.status = 'WAITING_APPROVAL' and new.status in ('APPROVED', 'DRAFT', 'DISCARDED'))
    or (old.status = 'APPROVED' and new.status in ('POSTED', 'DRAFT', 'DISCARDED'))) then
    raise exception 'goods receipt cannot go from % to %', old.status, new.status using errcode = 'check_violation';
  end if;
  if old.status <> 'DRAFT' and (
       new.vendor_id is distinct from old.vendor_id or new.invoice_no is distinct from old.invoice_no
       or new.invoice_date is distinct from old.invoice_date or new.credit_days is distinct from old.credit_days
       or new.invoice_total_paise is distinct from old.invoice_total_paise) then
    raise exception 'goods receipt cannot be edited once it has been sent for approval' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_goods_receipt_write() from public;
revoke all on function pharmacy.guard_goods_receipt_write() from sahaj_app;

comment on function pharmacy.guard_goods_receipt_write() is
  'Goods receipt guard (1410). V2026_12_17_1740 adds APPROVED to DISCARDED directly. POSTED and DISCARDED are final. SQLSTATE 23514.';

-- ======================================================================================================================
-- 3. L16: a ledger line with no person is allowed only for the system types. Today the only one is EXPIRY_QUARANTINE (the sweep).
--    The Schedule X trigger fires only for actor_kind = 'USER', and actor_kind is SYSTEM whenever the actor membership is null
--    (ck_stock_ledger_actor), so a person-driven type written with no actor would skip the signer rule. This closes it: SYSTEM is
--    accepted for EXPIRY_QUARANTINE only. A new system type needs a forward migration that widens this check.
--    On the partitioned parent: ALTER TABLE ... ADD CONSTRAINT (a check, not ONLY) is applied to every existing partition (NOT VALID there
--    too) and VALIDATE CONSTRAINT on the parent validates the partitions. A partition made later (create_ledger_partitions uses
--    "create table ... partition of") copies the parent's check constraints, so it carries the check from its first row.
--    NOT VALID then a guarded validate (the 1230 pattern): if an old SYSTEM row of another type exists, a WARNING is raised, the migration
--    still succeeds, and the check stays NOT VALID but still refuses every new row. The ledger is append-only: nothing is updated here.
-- ======================================================================================================================
alter table pharmacy.stock_ledger drop constraint if exists ck_stock_ledger_system_types;
alter table pharmacy.stock_ledger
  add constraint ck_stock_ledger_system_types check (actor_kind = 'USER' or txn_type = 'EXPIRY_QUARANTINE') not valid;

do $$
begin
  alter table pharmacy.stock_ledger validate constraint ck_stock_ledger_system_types;
exception when check_violation then
  raise warning 'pharmacy.stock_ledger has a SYSTEM line of a type other than EXPIRY_QUARANTINE; ck_stock_ledger_system_types stays NOT VALID (new rows are checked). Review the rows, then validate the constraint by hand.';
end;
$$;

comment on constraint ck_stock_ledger_system_types on pharmacy.stock_ledger is
  'A SYSTEM ledger line (no person) is allowed only for EXPIRY_QUARANTINE, so the Schedule X signer rule (trigger, USER lines) cannot be skipped by a null actor (security L16, V2026_12_17_1740). A new system type widens this check in a new migration.';

-- ======================================================================================================================
-- 4. m-7: correction of the comment in V2026_12_17_1720, section 7 (lines 268 to 272), which says "the posting service locks the store row
--    (shared) before it writes a balance". It does not: StockPosting takes no store lock. The applied file is not edited; this is the
--    correct statement, and the catalogue comment below carries it.
--      Serialised against a switch-off: the write services (per the code review of round 5) take the store row FOR UPDATE and re-read its
--        status before they call StockPosting, and a switch-off takes the same row FOR UPDATE. The two cannot interleave.
--      Not serialised (by design: the approval request is locked first, ADR-029 P5): the approval handlers that post when the last level
--        approves (onApproved), and the read sweep that quarantines expired stock. They post without the store lock.
--      Residual: a switch-off that races an approval-driven posting or the sweep can commit an INACTIVE store that then holds stock, because
--        the trigger reads a snapshot and the balance insert takes only a key-share lock on the store (FOR KEY SHARE does not conflict with
--        the status update). Stock is not lost: the balances and the ledger are intact and the store can be switched on again. The service
--        rule has_stock refuses most switch-offs first. Closing it needs the store FOR SHARE ahead of the request lock (a lock-order change
--        for the architect), not a database change.
-- ======================================================================================================================
comment on function pharmacy.guard_store_inactive_stock() is
  'm-5 (V2026_12_17_1720): a store cannot become INACTIVE while any of its stock_balance rows has qty > 0 (any state). SQLSTATE 23514. Corrected by V2026_12_17_1740 (m-7): StockPosting takes no store lock. The write services lock the store FOR UPDATE and so are ordered against a switch-off; the approval handlers and the expiry sweep post without it (the request is locked first). Residual: a switch-off racing such a posting can leave an INACTIVE store holding stock, because this trigger reads a snapshot.';
