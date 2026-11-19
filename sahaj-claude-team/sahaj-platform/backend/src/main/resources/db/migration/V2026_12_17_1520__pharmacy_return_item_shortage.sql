-- SAH-79 WP-F (stories 79-11 and 79-12), migration F3. ADR-029 P12 "Return items", "Shortages", P7 (Schedule X), OQ-79-18, G-17,
-- "Data needed" WP-F.
-- New objects only: tables pharmacy.return_item, pharmacy.return_item_note, pharmacy.shortage with guard functions and triggers.
-- Depends on F2 (dispatch_line). A return item is a rejected, returned or damaged quantity held as PENDING_RETURN until the accepting store
-- takes it back; a shortage is stock that never reached the receiving store and stays IN_TRANSIT until settled.
-- WP-G adds store_return and damage_report: return_item.store_return_id and damage_report_id are plain uuid columns now and get their
-- foreign keys in the WP-G migration. Tenant tables, forced row level security, no delete grant.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.return_item
-- ======================================================================================================================
create table if not exists pharmacy.return_item (
  id                            uuid primary key default gen_random_uuid(),
  tenant_id                     uuid not null default platform.current_tenant()
                                  constraint fk_return_item_tenant references platform.tenant (id),
  unit_id                       uuid not null,
  -- The number shown: the dispatch reference, or the RET or DMG number.
  doc_no                        text not null
                                  constraint ck_return_item_doc_no check (char_length(doc_no) between 1 and 40),
  item_type                     text not null
                                  constraint ck_return_item_type check (item_type in
                                    ('INDENT_REJECTION', 'TRANSFER_REJECTION', 'RETURN_TO_CS', 'DAMAGED')),
  -- Source: a rejected dispatch line, or (WP-G) a store return or a damage report. The WP-G foreign keys are added later.
  dispatch_line_id              uuid,
  store_return_id               uuid,
  damage_report_id              uuid,
  -- The store holding the units as PENDING_RETURN, and the store that decides to take them back.
  holding_store_id              uuid not null,
  accepting_store_id            uuid not null,
  product_id                    uuid not null,
  batch_id                      uuid not null,
  qty                           integer not null
                                  constraint ck_return_item_qty check (qty between 1 and 999999),
  -- Lists receipt-reject, store-return or damage (by type).
  reason_key                    text not null
                                  constraint ck_return_item_reason check (reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  status                        text not null default 'WAITING'
                                  constraint ck_return_item_status check (status in
                                    ('WAITING', 'DISPUTED', 'ACCEPTED_SALEABLE', 'ACCEPTED_QUARANTINE', 'WAITING_HOD')),
  -- What the accepting store is offered by default (QUARANTINE when the batch is expired or the units were damaged), Schedule X flag.
  default_put_back_into         text not null
                                  constraint ck_return_item_default_put_back check (default_put_back_into in ('SALEABLE', 'QUARANTINE')),
  schedule_x                    boolean not null default false,
  raised_by                     uuid not null,
  raised_at                     timestamptz not null default now(),
  -- A dispute (list dispute) by the accepting store: who, when, why. Kept after a reply returns the item to WAITING.
  dispute_reason_key            text
                                  constraint ck_return_item_dispute_reason check (dispute_reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  dispute_by                    uuid,
  dispute_at                    timestamptz,
  -- The acceptance: who, when, the Schedule X signer, and the result note.
  accepted_by                   uuid,
  accepted_at                   timestamptz,
  second_signer_membership_id   uuid,
  result_note                   text
                                  constraint ck_return_item_result_note check (char_length(result_note) between 1 and 300),
  created_at                    timestamptz not null default now(),
  created_by                    uuid not null,
  updated_at                    timestamptz,
  updated_by                    uuid,
  version                       bigint not null default 0,
  constraint uq_return_item_tenant_id unique (tenant_id, id),
  constraint fk_return_item_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_return_item_dispatch_line foreign key (tenant_id, dispatch_line_id) references pharmacy.dispatch_line (tenant_id, id),
  constraint fk_return_item_holding_store foreign key (tenant_id, holding_store_id) references pharmacy.store (tenant_id, id),
  constraint fk_return_item_accepting_store foreign key (tenant_id, accepting_store_id) references pharmacy.store (tenant_id, id),
  constraint fk_return_item_batch foreign key (tenant_id, batch_id, product_id) references pharmacy.batch (tenant_id, id, product_id),
  constraint ck_return_item_stores_differ check (holding_store_id <> accepting_store_id),
  constraint ck_return_item_raiser check (created_by = raised_by),
  -- The source belongs to the type.
  constraint ck_return_item_source check (
    (item_type in ('INDENT_REJECTION', 'TRANSFER_REJECTION') and dispatch_line_id is not null
       and store_return_id is null and damage_report_id is null)
    or (item_type = 'RETURN_TO_CS' and store_return_id is not null and dispatch_line_id is null and damage_report_id is null)
    or (item_type = 'DAMAGED' and damage_report_id is not null and dispatch_line_id is null and store_return_id is null)),
  constraint ck_return_item_dispute check (
    ((dispute_reason_key is null) = (dispute_by is null)) and ((dispute_by is null) = (dispute_at is null))),
  constraint ck_return_item_disputed check (status <> 'DISPUTED' or dispute_at is not null),
  -- Acceptance data exists exactly when the item is accepted; the status says where the units went.
  constraint ck_return_item_accepted check (
    (status in ('ACCEPTED_SALEABLE', 'ACCEPTED_QUARANTINE')) = (accepted_at is not null and accepted_by is not null)
    and (accepted_at is null) = (accepted_by is null)),
  constraint ck_return_item_signer_differs check (second_signer_membership_id is null or second_signer_membership_id <> accepted_by)
);

-- One return item per rejected dispatch line.
create unique index if not exists uq_return_item_dispatch_line on pharmacy.return_item (tenant_id, dispatch_line_id)
  where dispatch_line_id is not null;
-- The accepting store's "Waiting for action" list (what to accept or answer), oldest first.
create index if not exists ix_return_item_accepting on pharmacy.return_item (tenant_id, accepting_store_id, raised_at)
  where status in ('WAITING', 'DISPUTED', 'WAITING_HOD');
-- The holding store's list of what it returned (all statuses), newest first, and the holding-store FK.
create index if not exists ix_return_item_holding on pharmacy.return_item (tenant_id, holding_store_id, raised_at desc);
-- Return items of a batch and the batch FK.
create index if not exists ix_return_item_batch on pharmacy.return_item (tenant_id, batch_id);

alter table pharmacy.return_item enable row level security;
alter table pharmacy.return_item force row level security;
create policy p_return_item_select on pharmacy.return_item for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_return_item_insert on pharmacy.return_item for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_return_item_update on pharmacy.return_item for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.return_item from sahaj_app;
revoke all on pharmacy.return_item from public;
grant select, insert, update on pharmacy.return_item to sahaj_app;

-- Insert: WAITING (or WAITING_HOD for a damaged item still with the HOD). Update: ACCEPTED_* are final; WAITING_HOD to WAITING; WAITING to
-- DISPUTED or accepted; DISPUTED back to WAITING (a reply) or accepted. What the item is (type, source, stores, batch, quantity, reason,
-- raiser) never changes; a dispute and an acceptance are set once.
create or replace function pharmacy.guard_return_item_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'INSERT' then
    if new.status not in ('WAITING', 'WAITING_HOD') then
      raise exception 'return item must start as waiting' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if old.status in ('ACCEPTED_SALEABLE', 'ACCEPTED_QUARANTINE') then
    raise exception 'return item is final (%)', old.status using errcode = 'check_violation';
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.doc_no is distinct from old.doc_no or new.item_type is distinct from old.item_type
     or new.dispatch_line_id is distinct from old.dispatch_line_id or new.store_return_id is distinct from old.store_return_id
     or new.damage_report_id is distinct from old.damage_report_id
     or new.holding_store_id is distinct from old.holding_store_id or new.accepting_store_id is distinct from old.accepting_store_id
     or new.product_id is distinct from old.product_id or new.batch_id is distinct from old.batch_id
     or new.qty is distinct from old.qty or new.reason_key is distinct from old.reason_key
     or new.default_put_back_into is distinct from old.default_put_back_into or new.schedule_x is distinct from old.schedule_x
     or new.raised_by is distinct from old.raised_by or new.raised_at is distinct from old.raised_at
     or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
    raise exception 'return item identity cannot change' using errcode = 'check_violation';
  end if;
  if new.status is distinct from old.status and not (
       (old.status = 'WAITING_HOD' and new.status = 'WAITING')
    or (old.status = 'WAITING' and new.status in ('DISPUTED', 'ACCEPTED_SALEABLE', 'ACCEPTED_QUARANTINE'))
    or (old.status = 'DISPUTED' and new.status in ('WAITING', 'ACCEPTED_SALEABLE', 'ACCEPTED_QUARANTINE'))) then
    raise exception 'return item cannot go from % to %', old.status, new.status using errcode = 'check_violation';
  end if;
  if old.dispute_at is not null and (new.dispute_reason_key is distinct from old.dispute_reason_key
       or new.dispute_by is distinct from old.dispute_by or new.dispute_at is distinct from old.dispute_at) then
    -- A second dispute would overwrite the first: the notes table keeps the conversation instead.
    raise exception 'return item dispute is set once' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_return_item_write() from public;
revoke all on function pharmacy.guard_return_item_write() from sahaj_app;
create trigger trg_return_item_guard_write before insert or update on pharmacy.return_item
  for each row execute function pharmacy.guard_return_item_write();

comment on table pharmacy.return_item is
  'Quantity returned, rejected or damaged, held as PENDING_RETURN at the holding store until the accepting store takes it back as saleable or quarantine, or disputes it (SAH-79-11 to 79-14, ADR-029 P12). Accepting is whole-item. Never deleted; the two ACCEPTED statuses are final.';

-- ======================================================================================================================
-- 2. pharmacy.return_item_note: the conversation on an item (reply notes). Insert only.
-- ======================================================================================================================
create table if not exists pharmacy.return_item_note (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null default platform.current_tenant()
                    constraint fk_return_item_note_tenant references platform.tenant (id),
  unit_id         uuid not null,
  return_item_id  uuid not null,
  note            text not null
                    constraint ck_return_item_note_note check (char_length(note) between 1 and 300),
  -- Membership id (opaque uuid) of the writer.
  created_by      uuid not null,
  created_at      timestamptz not null default now(),
  constraint fk_return_item_note_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_return_item_note_item foreign key (tenant_id, return_item_id) references pharmacy.return_item (tenant_id, id)
);

-- The notes of one item in time order (the item screen) and the item FK.
create index if not exists ix_return_item_note_item on pharmacy.return_item_note (tenant_id, return_item_id, created_at);

alter table pharmacy.return_item_note enable row level security;
alter table pharmacy.return_item_note force row level security;
create policy p_return_item_note_select on pharmacy.return_item_note for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_return_item_note_insert on pharmacy.return_item_note for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.return_item_note from sahaj_app;
revoke all on pharmacy.return_item_note from public;
grant select, insert on pharmacy.return_item_note to sahaj_app;

-- No note on an accepted item (read without a lock; the service holds the item lock).
create or replace function pharmacy.guard_return_item_note_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_status text;
begin
  select r.status into v_status from pharmacy.return_item r where r.tenant_id = new.tenant_id and r.id = new.return_item_id;
  if v_status in ('ACCEPTED_SALEABLE', 'ACCEPTED_QUARANTINE') then
    raise exception 'return item is final (%)', v_status using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_return_item_note_insert() from public;
revoke all on function pharmacy.guard_return_item_note_insert() from sahaj_app;
create trigger trg_return_item_note_guard_insert before insert on pharmacy.return_item_note
  for each row execute function pharmacy.guard_return_item_note_insert();

comment on table pharmacy.return_item_note is
  'Reply notes between the two stores on a return item (SAH-79-12). Select and insert only; no note after the item is accepted. A short staff note, never logged. Never deleted.';

-- ======================================================================================================================
-- 3. pharmacy.shortage: units dispatched but "Short / not received". Not a return: nothing comes back to the shelf.
-- ======================================================================================================================
create table if not exists pharmacy.shortage (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null default platform.current_tenant()
                          constraint fk_shortage_tenant references platform.tenant (id),
  unit_id               uuid not null,
  dispatch_line_id      uuid not null,
  -- The store that should have received them (units stay IN_TRANSIT there), and the store that settles (the Central Store, or the sending
  -- store of a transfer).
  receiving_store_id    uuid not null,
  settling_store_id     uuid not null,
  product_id            uuid not null,
  batch_id              uuid not null,
  qty                   integer not null
                          constraint ck_shortage_qty check (qty between 1 and 999999),
  status                text not null default 'OPEN'
                          constraint ck_shortage_status check (status in ('OPEN', 'CLAIM_OPEN', 'WAITING_WRITE_OFF', 'FOUND', 'WRITTEN_OFF')),
  -- The transit-loss write-off request (engine rule WRITE_OFF, rule store = the settling store); null until one is raised and at level 0.
  approval_request_id   uuid,
  result_note           text
                          constraint ck_shortage_result_note check (char_length(result_note) between 1 and 300),
  second_signer_membership_id uuid,
  settled_by            uuid,
  settled_at            timestamptz,
  raised_by             uuid not null,
  created_at            timestamptz not null default now(),
  created_by            uuid not null,
  updated_at            timestamptz,
  updated_by            uuid,
  version               bigint not null default 0,
  constraint uq_shortage_tenant_id unique (tenant_id, id),
  -- One shortage per dispatch line (a line carries one reason).
  constraint uq_shortage_dispatch_line unique (tenant_id, dispatch_line_id),
  constraint fk_shortage_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_shortage_dispatch_line foreign key (tenant_id, dispatch_line_id) references pharmacy.dispatch_line (tenant_id, id),
  constraint fk_shortage_receiving_store foreign key (tenant_id, receiving_store_id) references pharmacy.store (tenant_id, id),
  constraint fk_shortage_settling_store foreign key (tenant_id, settling_store_id) references pharmacy.store (tenant_id, id),
  constraint fk_shortage_batch foreign key (tenant_id, batch_id, product_id) references pharmacy.batch (tenant_id, id, product_id),
  constraint fk_shortage_request foreign key (tenant_id, approval_request_id) references pharmacy.approval_request (tenant_id, id),
  constraint ck_shortage_stores_differ check (receiving_store_id <> settling_store_id),
  constraint ck_shortage_raiser check (created_by = raised_by),
  constraint ck_shortage_signer_differs check (second_signer_membership_id is null or second_signer_membership_id <> settled_by),
  -- Who settled it and when: exactly for FOUND and WRITTEN_OFF.
  constraint ck_shortage_settled check (
    (status in ('FOUND', 'WRITTEN_OFF')) = (settled_at is not null and settled_by is not null)
    and (settled_at is null) = (settled_by is null))
);

-- The settling store's open shortages (to investigate), oldest first.
create index if not exists ix_shortage_settling on pharmacy.shortage (tenant_id, settling_store_id, created_at)
  where status in ('OPEN', 'CLAIM_OPEN', 'WAITING_WRITE_OFF');
-- The receiving store's shortages, newest first, and the receiving-store FK.
create index if not exists ix_shortage_receiving on pharmacy.shortage (tenant_id, receiving_store_id, created_at desc);
-- The batch FK child side.
create index if not exists ix_shortage_batch on pharmacy.shortage (tenant_id, batch_id);

alter table pharmacy.shortage enable row level security;
alter table pharmacy.shortage force row level security;
create policy p_shortage_select on pharmacy.shortage for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_shortage_insert on pharmacy.shortage for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_shortage_update on pharmacy.shortage for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.shortage from sahaj_app;
revoke all on pharmacy.shortage from public;
grant select, insert, update on pharmacy.shortage to sahaj_app;

-- Insert: OPEN only. Update: FOUND and WRITTEN_OFF are final; OPEN to CLAIM_OPEN, WAITING_WRITE_OFF, FOUND or WRITTEN_OFF (level 0);
-- CLAIM_OPEN to WAITING_WRITE_OFF, FOUND or WRITTEN_OFF; WAITING_WRITE_OFF to WRITTEN_OFF or back to OPEN (the write-off was rejected).
-- What is short (line, stores, product, batch, quantity) never changes.
create or replace function pharmacy.guard_shortage_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'INSERT' then
    if new.status <> 'OPEN' then
      raise exception 'shortage must start open' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if old.status in ('FOUND', 'WRITTEN_OFF') then
    raise exception 'shortage is final (%)', old.status using errcode = 'check_violation';
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.dispatch_line_id is distinct from old.dispatch_line_id or new.receiving_store_id is distinct from old.receiving_store_id
     or new.settling_store_id is distinct from old.settling_store_id or new.product_id is distinct from old.product_id
     or new.batch_id is distinct from old.batch_id or new.qty is distinct from old.qty
     or new.raised_by is distinct from old.raised_by or new.created_at is distinct from old.created_at
     or new.created_by is distinct from old.created_by then
    raise exception 'shortage identity cannot change' using errcode = 'check_violation';
  end if;
  if new.status is distinct from old.status and not (
       (old.status = 'OPEN' and new.status in ('CLAIM_OPEN', 'WAITING_WRITE_OFF', 'FOUND', 'WRITTEN_OFF'))
    or (old.status = 'CLAIM_OPEN' and new.status in ('WAITING_WRITE_OFF', 'FOUND', 'WRITTEN_OFF'))
    or (old.status = 'WAITING_WRITE_OFF' and new.status in ('OPEN', 'WRITTEN_OFF'))) then
    raise exception 'shortage cannot go from % to %', old.status, new.status using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_shortage_write() from public;
revoke all on function pharmacy.guard_shortage_write() from sahaj_app;
create trigger trg_shortage_guard_write before insert or update on pharmacy.shortage
  for each row execute function pharmacy.guard_shortage_write();

comment on table pharmacy.shortage is
  'Units dispatched but never received ("Short / not received", SAH-79-12, OQ-79-18): not a return, nothing comes back to the shelf. Stays IN_TRANSIT at the receiving store until found, written off (approval) or claimed from the carrier. Never deleted; FOUND and WRITTEN_OFF are final.';
