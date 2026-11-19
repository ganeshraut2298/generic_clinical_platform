-- SAH-79 WP-E (story 79-8), migration E3. ADR-029 P10 "Debit note", N1 (vendor credit levels), P7 (Schedule X), OQ-79-11, OQ-79-12, OQ-79-13,
-- "Data needed" WP-E. Depends on E1 (guard_central_store_insert) and E2 (goods_receipt, goods_receipt_line).
-- New objects only: tables pharmacy.debit_note, pharmacy.debit_note_line, pharmacy.debit_note_credit with guard functions and triggers.
-- Tenant tables, forced row level security, no delete grant. Lines removed from a draft are flagged removed (E1 convention).
-- The stock effects (PENDING_RETURN hold, VENDOR_RETURN, release) are ledger lines written by the service; the vendor payable is E4.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.debit_note (header)
-- ======================================================================================================================
create table if not exists pharmacy.debit_note (
  id                           uuid primary key default gen_random_uuid(),
  tenant_id                    uuid not null default platform.current_tenant()
                                 constraint fk_debit_note_tenant references platform.tenant (id),
  unit_id                      uuid not null,
  store_id                     uuid not null,
  vendor_id                    uuid not null,
  -- DN number at first submit (ADR-029 P9); null in a draft and in a discarded draft.
  doc_seq                      bigint
                                 constraint ck_debit_note_doc_seq check (doc_seq >= 1),
  doc_no                       text
                                 constraint ck_debit_note_doc_no check (char_length(doc_no) between 1 and 16),
  status                       text not null default 'DRAFT'
                                 constraint ck_debit_note_status check (status in
                                   ('DRAFT', 'WAITING_APPROVAL', 'REJECTED', 'CREATED', 'PICKED', 'HANDED_OVER',
                                    'CREDIT_NOTE_RECEIVED', 'CANCELLED', 'DISCARDED')),
  -- The receipt this draft was prepared from (short-billed lines and ticked rate differences); null otherwise.
  prepared_from_grn_id         uuid,
  -- List vendor-return (optional). A key ending in .other needs a note of 4 to 300 characters: service rule.
  reason_key                   text
                                 constraint ck_debit_note_reason check (reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  note                         text
                                 constraint ck_debit_note_note check (char_length(note) between 1 and 300),
  credit_total_paise           bigint not null default 0
                                 constraint ck_debit_note_credit_total check (credit_total_paise >= 0),
  gst_reversal_paise           bigint not null default 0
                                 constraint ck_debit_note_gst_reversal check (gst_reversal_paise >= 0),
  approval_request_id          uuid,
  picked_at                    timestamptz,
  picked_by                    uuid,
  -- "Handed to vendor rep" (representative name 1 to 80; Schedule X second signature).
  representative_name          text
                                 constraint ck_debit_note_representative check (char_length(representative_name) between 1 and 80),
  handed_over_at               timestamptz,
  handed_over_by               uuid,
  second_signer_membership_id  uuid,
  created_at                   timestamptz not null default now(),
  created_by                   uuid not null,
  updated_at                   timestamptz,
  updated_by                   uuid,
  version                      bigint not null default 0,
  constraint uq_debit_note_tenant_id unique (tenant_id, id),
  constraint uq_debit_note_doc_no unique (tenant_id, unit_id, doc_no),
  constraint uq_debit_note_doc_seq unique (tenant_id, unit_id, doc_seq),
  constraint fk_debit_note_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_debit_note_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_debit_note_vendor foreign key (tenant_id, vendor_id) references pharmacy.vendor (tenant_id, id),
  constraint fk_debit_note_grn foreign key (tenant_id, prepared_from_grn_id) references pharmacy.goods_receipt (tenant_id, id),
  constraint fk_debit_note_request foreign key (tenant_id, approval_request_id) references pharmacy.approval_request (tenant_id, id),
  constraint ck_debit_note_number check ((doc_no is null) = (doc_seq is null)),
  constraint ck_debit_note_numbered check (status in ('DRAFT', 'DISCARDED') or doc_no is not null),
  constraint ck_debit_note_picked check ((picked_at is null) = (picked_by is null)),
  -- Hand-over: time, person and representative together; present from HANDED_OVER on.
  constraint ck_debit_note_handed_over check (
    ((handed_over_at is null) = (handed_over_by is null)) and ((handed_over_at is null) = (representative_name is null))),
  constraint ck_debit_note_handed_over_status check (
    status not in ('HANDED_OVER', 'CREDIT_NOTE_RECEIVED') or handed_over_at is not null),
  constraint ck_debit_note_not_handed_over check (
    handed_over_at is null or status in ('HANDED_OVER', 'CREDIT_NOTE_RECEIVED')),
  -- Schedule X: the second signer is never the person who handed the goods over.
  constraint ck_debit_note_signer_differs check (second_signer_membership_id is null or second_signer_membership_id <> handed_over_by)
);

-- Debit notes of a store by status, newest first (list, "debit-notes-pending" report: EXPLAIN first, section 13.7).
create index if not exists ix_debit_note_store_status on pharmacy.debit_note (tenant_id, store_id, status, created_at desc);
-- Debit notes of one vendor (eligible-for-return quantities on other open notes, vendor screen) and the vendor FK.
create index if not exists ix_debit_note_vendor on pharmacy.debit_note (tenant_id, vendor_id, status);
-- The draft prepared from a receipt, and the child side of the receipt FK.
create index if not exists ix_debit_note_grn on pharmacy.debit_note (tenant_id, prepared_from_grn_id) where prepared_from_grn_id is not null;

alter table pharmacy.debit_note enable row level security;
alter table pharmacy.debit_note force row level security;
create policy p_debit_note_select on pharmacy.debit_note for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_debit_note_insert on pharmacy.debit_note for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_debit_note_update on pharmacy.debit_note for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.debit_note from sahaj_app;
revoke all on pharmacy.debit_note from public;
grant select, insert, update on pharmacy.debit_note to sahaj_app;

create trigger trg_debit_note_central_store before insert on pharmacy.debit_note
  for each row execute function pharmacy.guard_central_store_insert();

-- Insert: DRAFT only. Update: REJECTED, CREDIT_NOTE_RECEIVED, CANCELLED, DISCARDED are final; only listed moves; identity never changes;
-- vendor, reason, note and totals change only while DRAFT (or in the update that leaves it); the picking and hand-over data are set once.
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
    or (old.status = 'HANDED_OVER' and new.status = 'CREDIT_NOTE_RECEIVED')) then
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
create trigger trg_debit_note_guard_write before insert or update on pharmacy.debit_note
  for each row execute function pharmacy.guard_debit_note_write();

comment on table pharmacy.debit_note is
  'Debit note to a vendor for returned stock or short supply (SAH-79-8, ADR-029 P10). DN number at first submit. Units are held (PENDING_RETURN) from CREATED until hand-over or cancel. Never deleted; REJECTED, CREDIT_NOTE_RECEIVED, CANCELLED and DISCARDED are final.';
comment on column pharmacy.debit_note.representative_name is
  'Name of the vendor representative who took the goods (1 to 80 characters). A person name of a vendor employee: never logged.';

-- ======================================================================================================================
-- 2. pharmacy.debit_note_line
-- ======================================================================================================================
create table if not exists pharmacy.debit_note_line (
  id                       uuid primary key default gen_random_uuid(),
  tenant_id                uuid not null default platform.current_tenant()
                             constraint fk_debit_note_line_tenant references platform.tenant (id),
  unit_id                  uuid not null,
  debit_note_id            uuid not null,
  sort                     integer not null
                             constraint ck_debit_note_line_sort check (sort between 1 and 1000),
  removed                  boolean not null default false,
  line_type                text not null
                             constraint ck_debit_note_line_type check (line_type in ('STOCK_RETURN', 'SHORT_SUPPLY')),
  product_id               uuid not null,
  -- STOCK_RETURN: the batch and the state its units come from. SHORT_SUPPLY: a receipt line, no stock.
  batch_id                 uuid,
  source_state             text
                             constraint ck_debit_note_line_source_state check (source_state in ('QUARANTINE', 'AVAILABLE')),
  goods_receipt_line_id    uuid,
  qty                      integer not null
                             constraint ck_debit_note_line_qty check (qty between 1 and 999999),
  -- Rate per issue unit (the batch purchase rate; for a short supply the receipt line rate).
  rate                     numeric(14,4) not null
                             constraint ck_debit_note_line_rate check (rate >= 0),
  -- Starts from the vendor tier (returnCreditPercent, damagedCreditPercent, expiredCreditPercent: N1); editable.
  credit_percent           numeric(5,2) not null
                             constraint ck_debit_note_line_credit_percent check (credit_percent between 0 and 100),
  credit_percent_changed   boolean not null default false,
  credit_paise             bigint not null default 0
                             constraint ck_debit_note_line_credit check (credit_paise >= 0),
  gst_rate                 numeric(5,2) not null default 0
                             constraint ck_debit_note_line_gst_rate check (gst_rate between 0 and 100),
  gst_reversal_paise       bigint not null default 0
                             constraint ck_debit_note_line_gst_reversal check (gst_reversal_paise >= 0),
  created_at               timestamptz not null default now(),
  created_by               uuid not null,
  updated_at               timestamptz,
  updated_by               uuid,
  version                  bigint not null default 0,
  constraint uq_debit_note_line_tenant_id unique (tenant_id, id),
  constraint fk_debit_note_line_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_debit_note_line_note foreign key (tenant_id, debit_note_id) references pharmacy.debit_note (tenant_id, id),
  constraint fk_debit_note_line_product foreign key (tenant_id, product_id) references pharmacy.product (tenant_id, id),
  -- MATCH SIMPLE: a null batch_id is not checked; when set, the batch must belong to the line's product.
  constraint fk_debit_note_line_batch foreign key (tenant_id, batch_id, product_id) references pharmacy.batch (tenant_id, id, product_id),
  constraint fk_debit_note_line_grn_line foreign key (tenant_id, goods_receipt_line_id) references pharmacy.goods_receipt_line (tenant_id, id),
  constraint ck_debit_note_line_shape check (
    (line_type = 'STOCK_RETURN' and batch_id is not null and source_state is not null)
    or (line_type = 'SHORT_SUPPLY' and batch_id is null and source_state is null and goods_receipt_line_id is not null))
);

-- One live stock-return line per batch and source state in a note; one live short-supply line per receipt line in a note.
create unique index if not exists uq_debit_note_line_stock on pharmacy.debit_note_line (tenant_id, debit_note_id, batch_id, source_state)
  where line_type = 'STOCK_RETURN' and not removed;
create unique index if not exists uq_debit_note_line_short on pharmacy.debit_note_line (tenant_id, debit_note_id, goods_receipt_line_id)
  where line_type = 'SHORT_SUPPLY' and not removed;
-- The lines of one note in screen order (note screen, pick list).
create index if not exists ix_debit_note_line_note on pharmacy.debit_note_line (tenant_id, debit_note_id, sort);
-- "Units of this batch already on another open debit note" (eligibility): by batch and state.
create index if not exists ix_debit_note_line_batch on pharmacy.debit_note_line (tenant_id, batch_id, source_state)
  where batch_id is not null and not removed;
-- "Short supply not yet on a debit note": by receipt line (and the child side of the receipt-line FK).
create index if not exists ix_debit_note_line_grn_line on pharmacy.debit_note_line (tenant_id, goods_receipt_line_id)
  where goods_receipt_line_id is not null and not removed;
-- The product FK child side.
create index if not exists ix_debit_note_line_product on pharmacy.debit_note_line (tenant_id, product_id);

alter table pharmacy.debit_note_line enable row level security;
alter table pharmacy.debit_note_line force row level security;
create policy p_debit_note_line_select on pharmacy.debit_note_line for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_debit_note_line_insert on pharmacy.debit_note_line for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_debit_note_line_update on pharmacy.debit_note_line for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.debit_note_line from sahaj_app;
revoke all on pharmacy.debit_note_line from public;
grant select, insert, update on pharmacy.debit_note_line to sahaj_app;

-- Insert and every change only while the parent note is a DRAFT (write the lines before the header leaves DRAFT); a removed line stays
-- removed. The parent is read without a lock (the header lock is held by the service, ADR-029 P5 step 3).
create or replace function pharmacy.guard_debit_note_line_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_parent text;
begin
  select d.status into v_parent from pharmacy.debit_note d where d.tenant_id = new.tenant_id and d.id = new.debit_note_id;
  if v_parent is not null and v_parent <> 'DRAFT' then
    raise exception 'debit note lines can change only while the note is a draft' using errcode = 'check_violation';
  end if;
  if tg_op = 'UPDATE' then
    if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
       or new.debit_note_id is distinct from old.debit_note_id or new.created_at is distinct from old.created_at
       or new.created_by is distinct from old.created_by then
      raise exception 'debit note line identity cannot change' using errcode = 'check_violation';
    end if;
    if old.removed and not new.removed then
      raise exception 'debit note line stays removed' using errcode = 'check_violation';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_debit_note_line_write() from public;
revoke all on function pharmacy.guard_debit_note_line_write() from sahaj_app;
create trigger trg_debit_note_line_guard_write before insert or update on pharmacy.debit_note_line
  for each row execute function pharmacy.guard_debit_note_line_write();

comment on table pharmacy.debit_note_line is
  'Line of a debit note: STOCK_RETURN (a batch and the state its units come from) or SHORT_SUPPLY (a receipt line, no stock) (SAH-79-8). Credit percent starts from the vendor tier and may be edited (credit_percent_changed). Changes only while the note is a DRAFT; a removed line is flagged, never deleted.';

-- ======================================================================================================================
-- 3. pharmacy.debit_note_credit: the vendor's credit note for one debit note (insert only; one per debit note).
-- ======================================================================================================================
create table if not exists pharmacy.debit_note_credit (
  id                      uuid primary key default gen_random_uuid(),
  tenant_id               uuid not null default platform.current_tenant()
                            constraint fk_debit_note_credit_tenant references platform.tenant (id),
  unit_id                 uuid not null,
  debit_note_id           uuid not null,
  credit_note_no          text not null
                            constraint ck_debit_note_credit_no check (char_length(credit_note_no) between 1 and 30 and char_length(btrim(credit_note_no)) >= 1),
  credit_note_date        date not null,
  amount_paise            bigint not null
                            constraint ck_debit_note_credit_amount check (amount_paise between 1 and 99999999900),
  -- amount received minus the debit note's credit total (+ more credit than asked, - less); shown as "difference".
  difference_paise        bigint not null,
  -- How much of the credit was applied to open payables of the vendor, and how much stayed unapplied (no negative payable, ADR-029 P10).
  applied_paise           bigint not null default 0
                            constraint ck_debit_note_credit_applied check (applied_paise >= 0),
  unapplied_credit_paise  bigint not null default 0
                            constraint ck_debit_note_credit_unapplied check (unapplied_credit_paise >= 0),
  created_at              timestamptz not null default now(),
  created_by              uuid not null,
  constraint uq_debit_note_credit_note unique (tenant_id, debit_note_id),
  constraint uq_debit_note_credit_tenant_id unique (tenant_id, id),
  constraint fk_debit_note_credit_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_debit_note_credit_note foreign key (tenant_id, debit_note_id) references pharmacy.debit_note (tenant_id, id),
  constraint ck_debit_note_credit_split check (applied_paise + unapplied_credit_paise = amount_paise)
);

alter table pharmacy.debit_note_credit enable row level security;
alter table pharmacy.debit_note_credit force row level security;
create policy p_debit_note_credit_select on pharmacy.debit_note_credit for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_debit_note_credit_insert on pharmacy.debit_note_credit for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.debit_note_credit from sahaj_app;
revoke all on pharmacy.debit_note_credit from public;
grant select, insert on pharmacy.debit_note_credit to sahaj_app;

-- A credit note cannot be recorded for a debit note that was not handed over (read without a lock; the header lock is held by the service).
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
    raise exception 'credit note can be recorded only after hand-over' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_debit_note_credit_insert() from public;
revoke all on function pharmacy.guard_debit_note_credit_insert() from sahaj_app;
create trigger trg_debit_note_credit_guard_insert before insert on pharmacy.debit_note_credit
  for each row execute function pharmacy.guard_debit_note_credit_insert();

comment on table pharmacy.debit_note_credit is
  'The vendor credit note recorded against one debit note (SAH-79-8): number, date, amount, difference and how much reduced vendor payables. Select and insert only; one per debit note (uq_debit_note_credit_note). Never deleted.';
