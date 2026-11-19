-- SAH-79 WP-D (story 79-5), migration D5. ADR-029 P3 (corrections are new ledger lines), P6, P9 (numbers), P7 (second signer),
-- "Data needed" D5, OQ-79-27.
-- New table pharmacy.stock_adjustment: a manual correction of one AVAILABLE batch of one store, from book quantity to physical quantity.
-- Tenant table, forced row level security, no delete grant. Level 0 (no approval level applies) saves it as POSTED; otherwise it is saved
-- as WAITING_APPROVAL and the approval posts it. The ledger lines are written by the posting service, not here.

set local lock_timeout = '30s';

create table if not exists pharmacy.stock_adjustment (
  id                            uuid primary key default gen_random_uuid(),
  tenant_id                     uuid not null default platform.current_tenant()
                                  constraint fk_stock_adjustment_tenant references platform.tenant (id),
  unit_id                       uuid not null,
  -- ADJ number: doc_seq is the value from pharmacy.next_document_no('STOCK_ADJUSTMENT'); doc_no is the text rendered at issue
  -- (prefix and padding), kept so a later series edit never changes an issued number (ADR-029 P9).
  doc_seq                       bigint not null
                                  constraint ck_stock_adjustment_doc_seq check (doc_seq >= 1),
  doc_no                        text not null
                                  constraint ck_stock_adjustment_doc_no check (char_length(doc_no) between 1 and 16),
  store_id                      uuid not null,
  product_id                    uuid not null,
  batch_id                      uuid not null,
  -- The AVAILABLE quantity the screen showed (bookQtySeen), verified equal to the balance under lock at save.
  book_qty                      integer not null
                                  constraint ck_stock_adjustment_book_qty check (book_qty between 0 and 999999),
  physical_qty                  integer not null
                                  constraint ck_stock_adjustment_physical_qty check (physical_qty between 0 and 999999),
  -- "+n" or "-n"; computed, so it cannot disagree with the two quantities.
  difference                    integer generated always as (physical_qty - book_qty) stored,
  -- |difference| x purchase rate per issue unit, without GST, rounded half up to the paisa (BR-17). Zero when the batch has no rate.
  value_paise                   bigint not null
                                  constraint ck_stock_adjustment_value check (value_paise >= 0),
  reason_key                    text not null
                                  constraint ck_stock_adjustment_reason check (reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  note                          text
                                  constraint ck_stock_adjustment_note check (char_length(note) between 1 and 300),
  -- The ledger line this adjustment corrects, if any (the new lines carry reverses_ledger_id). No foreign key: the ledger is partitioned.
  corrects_ledger_id            uuid,
  second_signer_membership_id   uuid,
  status                        text not null
                                  constraint ck_stock_adjustment_status check (status in ('WAITING_APPROVAL', 'POSTED', 'REJECTED')),
  -- The approval request that decided it; null at level 0.
  approval_request_id           uuid,
  posted_at                     timestamptz,
  created_at                    timestamptz not null default now(),
  -- The raiser's membership id (opaque uuid).
  created_by                    uuid not null,
  updated_at                    timestamptz,
  updated_by                    uuid,
  version                       bigint not null default 0,
  constraint uq_stock_adjustment_tenant_id unique (tenant_id, id),
  constraint uq_stock_adjustment_doc_no unique (tenant_id, unit_id, doc_no),
  constraint uq_stock_adjustment_doc_seq unique (tenant_id, unit_id, doc_seq),
  constraint fk_stock_adjustment_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_stock_adjustment_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_stock_adjustment_batch foreign key (tenant_id, batch_id, product_id) references pharmacy.batch (tenant_id, id, product_id),
  constraint fk_stock_adjustment_request foreign key (tenant_id, approval_request_id) references pharmacy.approval_request (tenant_id, id),
  -- "No change to post": an adjustment always changes the quantity.
  constraint ck_stock_adjustment_changes check (physical_qty <> book_qty),
  -- Schedule X: the second signer is never the person who raised it.
  constraint ck_stock_adjustment_signer_differs check (second_signer_membership_id is null or second_signer_membership_id <> created_by),
  -- posted_at is set exactly when POSTED.
  constraint ck_stock_adjustment_posted check ((status = 'POSTED') = (posted_at is not null)),
  -- A rejection always comes from an approval request.
  constraint ck_stock_adjustment_rejected check (status <> 'REJECTED' or approval_request_id is not null)
);

-- "An adjustment of this batch in this store is already waiting" (409 adjustment-waiting).
create unique index if not exists uq_stock_adjustment_one_waiting on pharmacy.stock_adjustment (tenant_id, batch_id, store_id)
  where status = 'WAITING_APPROVAL';
-- Adjustments of one store, newest first (the list; status filter is applied on the few rows of one store).
create index if not exists ix_stock_adjustment_store on pharmacy.stock_adjustment (tenant_id, store_id, created_at desc);
-- Adjustments of one batch (batch history; also the child side of the composite foreign key to the batch).
create index if not exists ix_stock_adjustment_batch on pharmacy.stock_adjustment (tenant_id, batch_id);

alter table pharmacy.stock_adjustment enable row level security;
alter table pharmacy.stock_adjustment force row level security;
create policy p_stock_adjustment_select on pharmacy.stock_adjustment for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_stock_adjustment_insert on pharmacy.stock_adjustment for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_stock_adjustment_update on pharmacy.stock_adjustment for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.stock_adjustment from sahaj_app;
revoke all on pharmacy.stock_adjustment from public;
grant select, insert, update on pharmacy.stock_adjustment to sahaj_app;

-- Insert: WAITING_APPROVAL or POSTED (level 0) only; a rejection is a later update. Update: only WAITING_APPROVAL moves, to POSTED or
-- REJECTED (or stays, to link its approval request); POSTED and REJECTED are final; what was adjusted and why never changes.
create or replace function pharmacy.guard_stock_adjustment_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'INSERT' then
    if new.status = 'REJECTED' then
      raise exception 'stock adjustment cannot start as rejected' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if old.status <> 'WAITING_APPROVAL' then
    raise exception 'stock adjustment is final (%)', old.status using errcode = 'check_violation';
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.doc_seq is distinct from old.doc_seq or new.doc_no is distinct from old.doc_no
     or new.store_id is distinct from old.store_id or new.product_id is distinct from old.product_id
     or new.batch_id is distinct from old.batch_id or new.book_qty is distinct from old.book_qty
     or new.physical_qty is distinct from old.physical_qty or new.value_paise is distinct from old.value_paise
     or new.reason_key is distinct from old.reason_key or new.note is distinct from old.note
     or new.corrects_ledger_id is distinct from old.corrects_ledger_id
     or new.second_signer_membership_id is distinct from old.second_signer_membership_id
     or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
    raise exception 'stock adjustment cannot change once saved' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_stock_adjustment_write() from public;
revoke all on function pharmacy.guard_stock_adjustment_write() from sahaj_app;
create trigger trg_stock_adjustment_guard_write before insert or update on pharmacy.stock_adjustment
  for each row execute function pharmacy.guard_stock_adjustment_write();

comment on table pharmacy.stock_adjustment is
  'Manual stock adjustment of one AVAILABLE batch of one store, book to physical quantity (SAH-79-5, OQ-79-27). ADJ number. Level 0 saves it POSTED; otherwise WAITING_APPROVAL and the approval posts it. The ledger lines (STOCK_ADJUSTMENT, with reverses_ledger_id when it corrects a line) are written by the posting service. Never deleted; POSTED and REJECTED are final. No patient data.';
comment on column pharmacy.stock_adjustment.created_by is
  'Membership id (opaque uuid) of the person who raised it; the raiser is the ledger actor, the approver is recorded as approved_by on the lines.';
comment on column pharmacy.stock_adjustment.value_paise is
  'Absolute value of the difference at the batch purchase rate per issue unit, without GST, in paise (BR-17, the amount the approval rule tests).';
