-- SAH-79 WP-F (stories 79-10 to 79-12), migration F2. ADR-029 P12 "Shared dispatch and receipt", P7 (Schedule X), DEC-063 (one receipt per
-- dispatch), OQ-79-2, "Data needed" WP-F ("put both values in the check now").
-- New objects only: tables pharmacy.dispatch, pharmacy.dispatch_line, pharmacy.dispatch_receipt_line with guard functions and triggers.
-- Depends on F1 (indent, indent_line). An indent dispatch and a transfer dispatch are the same record: source_type INDENT or TRANSFER.
-- The transfer tables arrive in WP-G: dispatch.transfer_id and dispatch_line.transfer_line_id are plain uuid columns now and get their
-- foreign keys in the WP-G migration. Tenant tables, forced row level security, no delete grant. Dispatch lines and receipt lines are
-- insert-only for the application: what was sent and what was received never changes.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.dispatch (header)
-- ======================================================================================================================
create table if not exists pharmacy.dispatch (
  id                              uuid primary key default gen_random_uuid(),
  tenant_id                       uuid not null default platform.current_tenant()
                                    constraint fk_dispatch_tenant references platform.tenant (id),
  unit_id                         uuid not null,
  source_type                     text not null
                                    constraint ck_dispatch_source_type check (source_type in ('INDENT', 'TRANSFER')),
  indent_id                       uuid,
  -- Foreign key to pharmacy.transfer is added by WP-G.
  transfer_id                     uuid,
  -- "<indent no> / <n>": n counts the dispatches of one indent (1, 2, ...). A transfer has one dispatch and no counter.
  dispatch_seq                    integer
                                    constraint ck_dispatch_seq check (dispatch_seq >= 1),
  -- The text shown and printed ("IND-6012 / 1", "TRF-0438"), kept as issued so a series edit never changes it.
  reference                       text not null
                                    constraint ck_dispatch_reference check (char_length(reference) between 1 and 40),
  from_store_id                   uuid not null,
  to_store_id                     uuid not null,
  status                          text not null default 'IN_TRANSIT'
                                    constraint ck_dispatch_status check (status in ('IN_TRANSIT', 'PARTLY_RECEIVED', 'RECEIVED')),
  -- Membership ids (opaque uuids). The dispatcher is the ledger actor; a Schedule X second signer is never the dispatcher.
  dispatched_by                   uuid not null,
  dispatched_at                   timestamptz not null default now(),
  second_signer_membership_id     uuid,
  -- The receipt (one per dispatch, DEC-063): who, when, and the Schedule X signer of the receiving side. Null while IN_TRANSIT.
  received_by                     uuid,
  received_at                     timestamptz,
  receipt_second_signer_membership_id uuid,
  rejected_note                   text
                                    constraint ck_dispatch_rejected_note check (char_length(rejected_note) between 1 and 300),
  short_note                      text
                                    constraint ck_dispatch_short_note check (char_length(short_note) between 1 and 300),
  created_at                      timestamptz not null default now(),
  created_by                      uuid not null,
  updated_at                      timestamptz,
  updated_by                      uuid,
  version                         bigint not null default 0,
  constraint uq_dispatch_tenant_id unique (tenant_id, id),
  constraint uq_dispatch_reference unique (tenant_id, unit_id, reference),
  constraint fk_dispatch_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_dispatch_indent foreign key (tenant_id, indent_id) references pharmacy.indent (tenant_id, id),
  constraint fk_dispatch_from_store foreign key (tenant_id, from_store_id) references pharmacy.store (tenant_id, id),
  constraint fk_dispatch_to_store foreign key (tenant_id, to_store_id) references pharmacy.store (tenant_id, id),
  constraint ck_dispatch_stores_differ check (from_store_id <> to_store_id),
  -- Exactly one source, with its counter for an indent.
  constraint ck_dispatch_source check (
    (source_type = 'INDENT' and indent_id is not null and transfer_id is null and dispatch_seq is not null)
    or (source_type = 'TRANSFER' and transfer_id is not null and indent_id is null and dispatch_seq is null)),
  constraint ck_dispatch_dispatcher check (created_by = dispatched_by),
  constraint ck_dispatch_signer_differs check (second_signer_membership_id is null or second_signer_membership_id <> dispatched_by),
  constraint ck_dispatch_receipt check (
    (received_at is null) = (received_by is null) and (status = 'IN_TRANSIT') = (received_at is null)),
  constraint ck_dispatch_receipt_signer_differs check (
    receipt_second_signer_membership_id is null or receipt_second_signer_membership_id <> received_by)
);

-- "<n>th dispatch of an indent": one counter value per indent (the number of the next dispatch is max + 1, read under the indent lock).
create unique index if not exists uq_dispatch_indent_seq on pharmacy.dispatch (tenant_id, indent_id, dispatch_seq) where source_type = 'INDENT';
-- One dispatch per transfer.
create unique index if not exists uq_dispatch_transfer on pharmacy.dispatch (tenant_id, transfer_id) where source_type = 'TRANSFER';
-- The receiving store's list: what is on its way or waiting (status, newest first), and the to-store FK.
create index if not exists ix_dispatch_to_store on pharmacy.dispatch (tenant_id, to_store_id, status, dispatched_at desc);
-- The sending store's list of what it sent, newest first, and the from-store FK.
create index if not exists ix_dispatch_from_store on pharmacy.dispatch (tenant_id, from_store_id, dispatched_at desc);

alter table pharmacy.dispatch enable row level security;
alter table pharmacy.dispatch force row level security;
create policy p_dispatch_select on pharmacy.dispatch for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_dispatch_insert on pharmacy.dispatch for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_dispatch_update on pharmacy.dispatch for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.dispatch from sahaj_app;
revoke all on pharmacy.dispatch from public;
grant select, insert, update on pharmacy.dispatch to sahaj_app;

-- Insert: IN_TRANSIT only, no receipt data. Update: RECEIVED is final; IN_TRANSIT to PARTLY_RECEIVED or RECEIVED, PARTLY_RECEIVED to RECEIVED;
-- what was dispatched (source, stores, reference, dispatcher, time, signer) never changes; only the receipt columns and notes move.
create or replace function pharmacy.guard_dispatch_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'INSERT' then
    if new.status <> 'IN_TRANSIT' then
      raise exception 'dispatch must start in transit' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if old.status = 'RECEIVED' then
    raise exception 'dispatch is final (%)', old.status using errcode = 'check_violation';
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.source_type is distinct from old.source_type or new.indent_id is distinct from old.indent_id
     or new.transfer_id is distinct from old.transfer_id or new.dispatch_seq is distinct from old.dispatch_seq
     or new.reference is distinct from old.reference or new.from_store_id is distinct from old.from_store_id
     or new.to_store_id is distinct from old.to_store_id or new.dispatched_by is distinct from old.dispatched_by
     or new.dispatched_at is distinct from old.dispatched_at
     or new.second_signer_membership_id is distinct from old.second_signer_membership_id
     or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
    raise exception 'dispatch identity cannot change' using errcode = 'check_violation';
  end if;
  if new.status is distinct from old.status and not (
       (old.status = 'IN_TRANSIT' and new.status in ('PARTLY_RECEIVED', 'RECEIVED'))
    or (old.status = 'PARTLY_RECEIVED' and new.status = 'RECEIVED')) then
    raise exception 'dispatch cannot go from % to %', old.status, new.status using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_dispatch_write() from public;
revoke all on function pharmacy.guard_dispatch_write() from sahaj_app;
create trigger trg_dispatch_guard_write before insert or update on pharmacy.dispatch
  for each row execute function pharmacy.guard_dispatch_write();

comment on table pharmacy.dispatch is
  'One dispatch of stock from one store to another: an indent dispatch or a transfer (SAH-79-10 to 79-12, ADR-029 P12). Stock goes AVAILABLE to IN_TRANSIT at dispatch; one receipt per dispatch (DEC-063). Never deleted; RECEIVED is final.';

-- ======================================================================================================================
-- 2. pharmacy.dispatch_line: what was sent, per batch. Insert only.
-- ======================================================================================================================
create table if not exists pharmacy.dispatch_line (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null default platform.current_tenant()
                          constraint fk_dispatch_line_tenant references platform.tenant (id),
  unit_id               uuid not null,
  dispatch_id           uuid not null,
  indent_line_id        uuid,
  -- Foreign key to the transfer line is added by WP-G.
  transfer_line_id      uuid,
  -- The product sent (the requested one, or the replacement), and the batch it was picked from (FEFO).
  product_id            uuid not null,
  batch_id              uuid not null,
  dispatched_qty        integer not null
                          constraint ck_dispatch_line_qty check (dispatched_qty between 1 and 999999),
  replaced_by_cs        boolean not null default false,
  -- List cs-change; a .other key needs a note: service rule.
  change_reason_key     text
                          constraint ck_dispatch_line_change_reason check (change_reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  change_note           text
                          constraint ck_dispatch_line_change_note check (char_length(change_note) between 1 and 300),
  -- Quantity of the indent line left as a back order at this dispatch.
  back_ordered_qty      integer not null default 0
                          constraint ck_dispatch_line_back_order check (back_ordered_qty >= 0),
  schedule_x            boolean not null default false,
  created_at            timestamptz not null default now(),
  created_by            uuid not null,
  constraint uq_dispatch_line_tenant_id unique (tenant_id, id),
  -- Target of the receipt line's foreign key: the receipt copies the dispatch quantity and the dispatch id.
  constraint uq_dispatch_line_receipt_target unique (tenant_id, id, dispatch_id, dispatched_qty),
  constraint fk_dispatch_line_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_dispatch_line_dispatch foreign key (tenant_id, dispatch_id) references pharmacy.dispatch (tenant_id, id),
  constraint fk_dispatch_line_indent_line foreign key (tenant_id, indent_line_id) references pharmacy.indent_line (tenant_id, id),
  constraint fk_dispatch_line_batch foreign key (tenant_id, batch_id, product_id) references pharmacy.batch (tenant_id, id, product_id),
  -- Exactly one source line; a replacement belongs to an indent line.
  constraint ck_dispatch_line_source check ((indent_line_id is not null) <> (transfer_line_id is not null)),
  constraint ck_dispatch_line_replaced check (not replaced_by_cs or indent_line_id is not null)
);

-- One line per batch per source line in a dispatch.
create unique index if not exists uq_dispatch_line_batch on pharmacy.dispatch_line (tenant_id, dispatch_id, batch_id, coalesce(indent_line_id, transfer_line_id));
-- The lines of one dispatch (the dispatch screen, the receipt).
create index if not exists ix_dispatch_line_dispatch on pharmacy.dispatch_line (tenant_id, dispatch_id);
-- Dispatches of an indent line (balance and chips of the indent) and the indent-line FK.
create index if not exists ix_dispatch_line_indent_line on pharmacy.dispatch_line (tenant_id, indent_line_id) where indent_line_id is not null;
-- In-transit units of a batch and the batch FK.
create index if not exists ix_dispatch_line_batch on pharmacy.dispatch_line (tenant_id, batch_id);

alter table pharmacy.dispatch_line enable row level security;
alter table pharmacy.dispatch_line force row level security;
create policy p_dispatch_line_select on pharmacy.dispatch_line for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_dispatch_line_insert on pharmacy.dispatch_line for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.dispatch_line from sahaj_app;
revoke all on pharmacy.dispatch_line from public;
grant select, insert on pharmacy.dispatch_line to sahaj_app;

-- Lines are added only while the dispatch is IN_TRANSIT (the header is inserted first, in the same transaction).
create or replace function pharmacy.guard_dispatch_line_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_parent text;
begin
  select d.status into v_parent from pharmacy.dispatch d where d.tenant_id = new.tenant_id and d.id = new.dispatch_id;
  if v_parent is not null and v_parent <> 'IN_TRANSIT' then
    raise exception 'dispatch lines can be added only while the dispatch is in transit' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_dispatch_line_insert() from public;
revoke all on function pharmacy.guard_dispatch_line_insert() from sahaj_app;
create trigger trg_dispatch_line_guard_insert before insert on pharmacy.dispatch_line
  for each row execute function pharmacy.guard_dispatch_line_insert();

comment on table pharmacy.dispatch_line is
  'One batch sent in a dispatch (SAH-79-10 to 79-12): product (or its replacement), batch, quantity in issue units, back order left, Schedule X flag. Select and insert only: what was sent never changes. Never deleted.';

-- ======================================================================================================================
-- 3. pharmacy.dispatch_receipt_line: what the receiving store did with each dispatch line, exactly once. Insert only.
--    "check (accepted + rejected = dispatched)": the dispatched quantity is copied on the row and tied to the line by the foreign key.
--    A Schedule X line left by a non-pharmacist ("skipped") has no row until a pharmacist receives it.
-- ======================================================================================================================
create table if not exists pharmacy.dispatch_receipt_line (
  id                           uuid primary key default gen_random_uuid(),
  tenant_id                    uuid not null default platform.current_tenant()
                                 constraint fk_dispatch_receipt_line_tenant references platform.tenant (id),
  unit_id                      uuid not null,
  dispatch_id                  uuid not null,
  dispatch_line_id             uuid not null,
  dispatched_qty               integer not null,
  accepted_qty                 integer not null
                                 constraint ck_dispatch_receipt_line_accepted check (accepted_qty >= 0),
  rejected_qty                 integer not null
                                 constraint ck_dispatch_receipt_line_rejected check (rejected_qty >= 0),
  -- List receipt-reject; present exactly when something was rejected. receipt-reject.short means "not received" (a shortage, not a return).
  reject_reason_key            text
                                 constraint ck_dispatch_receipt_line_reason check (reject_reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  note                         text
                                 constraint ck_dispatch_receipt_line_note check (char_length(note) between 1 and 300),
  received_by                  uuid not null,
  received_at                  timestamptz not null default now(),
  second_signer_membership_id  uuid,
  constraint uq_dispatch_receipt_line_line unique (tenant_id, dispatch_line_id),
  constraint fk_dispatch_receipt_line_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_dispatch_receipt_line_line foreign key (tenant_id, dispatch_line_id, dispatch_id, dispatched_qty)
    references pharmacy.dispatch_line (tenant_id, id, dispatch_id, dispatched_qty),
  constraint ck_dispatch_receipt_line_sum check (accepted_qty + rejected_qty = dispatched_qty),
  constraint ck_dispatch_receipt_line_reason_needed check ((rejected_qty > 0) = (reject_reason_key is not null)),
  constraint ck_dispatch_receipt_line_signer_differs check (second_signer_membership_id is null or second_signer_membership_id <> received_by)
);

-- Receipt lines of one dispatch (the dispatch screen's accepted and rejected columns).
create index if not exists ix_dispatch_receipt_line_dispatch on pharmacy.dispatch_receipt_line (tenant_id, dispatch_id);

alter table pharmacy.dispatch_receipt_line enable row level security;
alter table pharmacy.dispatch_receipt_line force row level security;
create policy p_dispatch_receipt_line_select on pharmacy.dispatch_receipt_line for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_dispatch_receipt_line_insert on pharmacy.dispatch_receipt_line for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.dispatch_receipt_line from sahaj_app;
revoke all on pharmacy.dispatch_receipt_line from public;
grant select, insert on pharmacy.dispatch_receipt_line to sahaj_app;

-- A line is received only while the dispatch is IN_TRANSIT or PARTLY_RECEIVED (insert the receipt lines, then update the header).
create or replace function pharmacy.guard_dispatch_receipt_line_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_parent text;
begin
  select d.status into v_parent from pharmacy.dispatch d where d.tenant_id = new.tenant_id and d.id = new.dispatch_id;
  if v_parent is not null and v_parent not in ('IN_TRANSIT', 'PARTLY_RECEIVED') then
    raise exception 'dispatch is already received' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_dispatch_receipt_line_insert() from public;
revoke all on function pharmacy.guard_dispatch_receipt_line_insert() from sahaj_app;
create trigger trg_dispatch_receipt_line_guard_insert before insert on pharmacy.dispatch_receipt_line
  for each row execute function pharmacy.guard_dispatch_receipt_line_insert();

comment on table pharmacy.dispatch_receipt_line is
  'What the receiving store did with one dispatch line: accepted plus rejected equals dispatched; a reason when something is rejected (SAH-79-11, ADR-029 P12). One row per dispatch line (uq_dispatch_receipt_line_line): each line is received exactly once. Select and insert only. Never deleted.';
