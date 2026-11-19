-- SAH-79 WP-G (story 79-15), migration G2. ADR-029 P12 "Transfer", P9 (TRF number at creation: no draft), OQ-79-20, "Data needed" WP-G.
-- New objects only: tables pharmacy.transfer and pharmacy.transfer_line with guard functions and triggers, plus the foreign keys that tie
-- dispatch.transfer_id and dispatch_line.transfer_line_id (migration F2, plain uuid columns until now) to them. Depends on F2.
-- Tenant tables, forced row level security, no delete grant. A transfer is created and submitted in one step (no draft, no edit): its lines
-- never change. The stock moves (AVAILABLE to IN_TRANSIT at dispatch, IN_TRANSIT to AVAILABLE at receipt) are ledger lines written by the
-- service through the shared dispatch (F2).

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.transfer
-- ======================================================================================================================
create table if not exists pharmacy.transfer (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null default platform.current_tenant()
                          constraint fk_transfer_tenant references platform.tenant (id),
  unit_id               uuid not null,
  doc_seq               bigint not null
                          constraint ck_transfer_doc_seq check (doc_seq >= 1),
  doc_no                text not null
                          constraint ck_transfer_doc_no check (char_length(doc_no) between 1 and 16),
  from_store_id         uuid not null,
  to_store_id           uuid not null,
  -- List transfer; a .other key needs a note: service rule.
  reason_key            text not null
                          constraint ck_transfer_reason check (reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  note                  text
                          constraint ck_transfer_note check (char_length(note) between 1 and 300),
  status                text not null default 'WAITING_APPROVAL'
                          constraint ck_transfer_status check (status in
                            ('WAITING_APPROVAL', 'APPROVED', 'IN_TRANSIT', 'RECEIVED', 'REJECTED', 'CANCELLED')),
  approval_request_id   uuid,
  -- Sum of the line values at the batch purchase rate, in paise (the amount the approval rule tests).
  total_value_paise     bigint not null default 0
                          constraint ck_transfer_total_value check (total_value_paise >= 0),
  created_at            timestamptz not null default now(),
  -- Membership id (opaque uuid) of the person who raised it.
  created_by            uuid not null,
  updated_at            timestamptz,
  updated_by            uuid,
  version               bigint not null default 0,
  constraint uq_transfer_tenant_id unique (tenant_id, id),
  constraint uq_transfer_doc_no unique (tenant_id, unit_id, doc_no),
  constraint uq_transfer_doc_seq unique (tenant_id, unit_id, doc_seq),
  constraint fk_transfer_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_transfer_from_store foreign key (tenant_id, from_store_id) references pharmacy.store (tenant_id, id),
  constraint fk_transfer_to_store foreign key (tenant_id, to_store_id) references pharmacy.store (tenant_id, id),
  constraint fk_transfer_request foreign key (tenant_id, approval_request_id) references pharmacy.approval_request (tenant_id, id),
  constraint ck_transfer_stores_differ check (from_store_id <> to_store_id),
  -- A rejection always comes from an approval request.
  constraint ck_transfer_rejected check (status <> 'REJECTED' or approval_request_id is not null)
);

-- The sending store's list (status, newest first; "Not received" marks use IN_TRANSIT rows) and the from-store FK.
create index if not exists ix_transfer_from_store on pharmacy.transfer (tenant_id, from_store_id, status, created_at desc);
-- The receiving store's list, same shape, and the to-store FK.
create index if not exists ix_transfer_to_store on pharmacy.transfer (tenant_id, to_store_id, status, created_at desc);

alter table pharmacy.transfer enable row level security;
alter table pharmacy.transfer force row level security;
create policy p_transfer_select on pharmacy.transfer for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_transfer_insert on pharmacy.transfer for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_transfer_update on pharmacy.transfer for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.transfer from sahaj_app;
revoke all on pharmacy.transfer from public;
grant select, insert, update on pharmacy.transfer to sahaj_app;

-- Insert: WAITING_APPROVAL, APPROVED or IN_TRANSIT (level 0 applies and dispatches at once); the receiving store is never the Central Store
-- (OQ-79-20; read as the caller, a missing store is left to the foreign key). Update: RECEIVED, REJECTED and CANCELLED are final; only
-- listed moves; stores, reason, note, total and number never change.
create or replace function pharmacy.guard_transfer_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_type text;
begin
  if tg_op = 'INSERT' then
    if new.status not in ('WAITING_APPROVAL', 'APPROVED', 'IN_TRANSIT') then
      raise exception 'transfer must start waiting, approved or in transit' using errcode = 'check_violation';
    end if;
    select s.type into v_type from pharmacy.store s where s.tenant_id = new.tenant_id and s.id = new.to_store_id;
    if v_type = 'CENTRAL' then
      raise exception 'transfer cannot go to the central store' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if old.status in ('RECEIVED', 'REJECTED', 'CANCELLED') then
    raise exception 'transfer is final (%)', old.status using errcode = 'check_violation';
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.doc_seq is distinct from old.doc_seq or new.doc_no is distinct from old.doc_no
     or new.from_store_id is distinct from old.from_store_id or new.to_store_id is distinct from old.to_store_id
     or new.reason_key is distinct from old.reason_key or new.note is distinct from old.note
     or new.total_value_paise is distinct from old.total_value_paise
     or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
    raise exception 'transfer cannot change once saved' using errcode = 'check_violation';
  end if;
  if new.status is distinct from old.status and not (
       (old.status = 'WAITING_APPROVAL' and new.status in ('APPROVED', 'IN_TRANSIT', 'REJECTED', 'CANCELLED'))
    or (old.status = 'APPROVED' and new.status in ('IN_TRANSIT', 'CANCELLED'))
    or (old.status = 'IN_TRANSIT' and new.status = 'RECEIVED')) then
    raise exception 'transfer cannot go from % to %', old.status, new.status using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_transfer_write() from public;
revoke all on function pharmacy.guard_transfer_write() from sahaj_app;
create trigger trg_transfer_guard_write before insert or update on pharmacy.transfer
  for each row execute function pharmacy.guard_transfer_write();

comment on table pharmacy.transfer is
  'Transfer of stock between two stores of the unit, never to the Central Store (SAH-79-15, ADR-029 P12). TRF number at creation; created and submitted in one step. One dispatch of all lines (pharmacy.dispatch, source TRANSFER). Never deleted; RECEIVED, REJECTED and CANCELLED are final. No patient data.';

-- ======================================================================================================================
-- 2. pharmacy.transfer_line: what was asked, per product. Insert only (a transfer has no edit). The batches picked by FEFO are the
--    dispatch lines of the transfer's dispatch.
-- ======================================================================================================================
create table if not exists pharmacy.transfer_line (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null default platform.current_tenant()
                   constraint fk_transfer_line_tenant references platform.tenant (id),
  unit_id        uuid not null,
  transfer_id    uuid not null,
  product_id     uuid not null,
  quantity       integer not null
                   constraint ck_transfer_line_quantity check (quantity between 1 and 999999),
  -- Value at the batch purchase rates of the FEFO preview, in paise (0 when no rate).
  value_paise    bigint not null default 0
                   constraint ck_transfer_line_value check (value_paise >= 0),
  created_at     timestamptz not null default now(),
  created_by     uuid not null,
  constraint uq_transfer_line_tenant_id unique (tenant_id, id),
  constraint uq_transfer_line_product unique (tenant_id, transfer_id, product_id),
  constraint fk_transfer_line_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_transfer_line_transfer foreign key (tenant_id, transfer_id) references pharmacy.transfer (tenant_id, id),
  constraint fk_transfer_line_product foreign key (tenant_id, product_id) references pharmacy.product (tenant_id, id)
);

-- The lines of one transfer are served by the unique key (tenant_id, transfer_id, product_id). Transfers containing a product (product
-- screen, transfer register by product) and the product FK:
create index if not exists ix_transfer_line_product on pharmacy.transfer_line (tenant_id, product_id);

alter table pharmacy.transfer_line enable row level security;
alter table pharmacy.transfer_line force row level security;
create policy p_transfer_line_select on pharmacy.transfer_line for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_transfer_line_insert on pharmacy.transfer_line for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.transfer_line from sahaj_app;
revoke all on pharmacy.transfer_line from public;
grant select, insert on pharmacy.transfer_line to sahaj_app;

comment on table pharmacy.transfer_line is
  'Product and quantity asked in a transfer (SAH-79-15). Select and insert only: a transfer has no edit. Never deleted.';

-- ======================================================================================================================
-- 3. Tie the shared dispatch (F2) to transfers. dispatch.transfer_id and dispatch_line.transfer_line_id were plain uuid columns; every
--    existing row has them null (WP-G is the first writer), so no row can violate the new foreign keys.
-- ======================================================================================================================
do $$
begin
  if not exists (select 1 from pg_catalog.pg_constraint
                  where conname = 'fk_dispatch_transfer' and conrelid = 'pharmacy.dispatch'::regclass) then
    alter table pharmacy.dispatch
      add constraint fk_dispatch_transfer foreign key (tenant_id, transfer_id) references pharmacy.transfer (tenant_id, id);
  end if;
  if not exists (select 1 from pg_catalog.pg_constraint
                  where conname = 'fk_dispatch_line_transfer_line' and conrelid = 'pharmacy.dispatch_line'::regclass) then
    alter table pharmacy.dispatch_line
      add constraint fk_dispatch_line_transfer_line foreign key (tenant_id, transfer_line_id) references pharmacy.transfer_line (tenant_id, id);
  end if;
end
$$;

-- Dispatch lines of a transfer line (the transfer screen's received and rejected columns) and the transfer-line FK child side.
create index if not exists ix_dispatch_line_transfer_line on pharmacy.dispatch_line (tenant_id, transfer_line_id)
  where transfer_line_id is not null;
