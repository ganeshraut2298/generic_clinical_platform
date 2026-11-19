-- SAH-79 WP-F (stories 79-9 to 79-11), migration F1. ADR-029 P12 "Indent", P9 (numbers, drafts, history), P8 (store capabilities),
-- "Data needed" WP-F, OQ-79-16 (replacement), OQ-79-21, DEC-063.
-- New objects only: tables pharmacy.indent and pharmacy.indent_line with guard functions and triggers. Depends on WP-D only (not on WP-E).
-- Tenant tables, forced row level security, no delete grant. A line taken off a draft is flagged removed (WP-E convention, design note
-- section 13). Dispatch, receipt, return items and shortages are in F2 and F3.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.indent (header)
-- ======================================================================================================================
create table if not exists pharmacy.indent (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null default platform.current_tenant()
                          constraint fk_indent_tenant references platform.tenant (id),
  unit_id               uuid not null,
  -- The requesting store, and the store that issues to it (store.issues_from_store_id, normally the Central Store).
  from_store_id         uuid not null,
  to_store_id           uuid not null,
  -- IND number at first submit (ADR-029 P9). Null in a draft and in a discarded draft.
  doc_seq               bigint
                          constraint ck_indent_doc_seq check (doc_seq >= 1),
  doc_no                text
                          constraint ck_indent_doc_no check (char_length(doc_no) between 1 and 16),
  priority              text not null default 'ROUTINE'
                          constraint ck_indent_priority check (priority in ('ROUTINE', 'URGENT')),
  status                text not null default 'DRAFT'
                          constraint ck_indent_status check (status in
                            ('DRAFT', 'WAITING_APPROVAL', 'APPROVED', 'PARTLY_DISPATCHED', 'DISPATCHED', 'RECEIVED', 'CLOSED',
                             'REJECTED', 'CANCELLED', 'DISCARDED')),
  approval_request_id   uuid,
  -- Set at submit; the "age" of the list counts from here.
  raised_at             timestamptz,
  -- Cancellation: a reason of list approval-reject (A-4), and a note.
  cancel_reason_key     text
                          constraint ck_indent_cancel_reason check (cancel_reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  cancel_note           text
                          constraint ck_indent_cancel_note check (char_length(cancel_note) between 1 and 300),
  created_at            timestamptz not null default now(),
  -- Membership id (opaque uuid) of the person who raised it.
  created_by            uuid not null,
  updated_at            timestamptz,
  updated_by            uuid,
  version               bigint not null default 0,
  constraint uq_indent_tenant_id unique (tenant_id, id),
  constraint uq_indent_doc_no unique (tenant_id, unit_id, doc_no),
  constraint uq_indent_doc_seq unique (tenant_id, unit_id, doc_seq),
  constraint fk_indent_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_indent_from_store foreign key (tenant_id, from_store_id) references pharmacy.store (tenant_id, id),
  constraint fk_indent_to_store foreign key (tenant_id, to_store_id) references pharmacy.store (tenant_id, id),
  constraint fk_indent_request foreign key (tenant_id, approval_request_id) references pharmacy.approval_request (tenant_id, id),
  constraint ck_indent_stores_differ check (from_store_id <> to_store_id),
  constraint ck_indent_number check ((doc_no is null) = (doc_seq is null)),
  constraint ck_indent_numbered check (status in ('DRAFT', 'DISCARDED') or doc_no is not null),
  -- raised_at exists from submit on (every status but DRAFT and DISCARDED).
  constraint ck_indent_raised check (status in ('DRAFT', 'DISCARDED') or raised_at is not null),
  -- A cancellation reason exists only on a cancelled indent.
  constraint ck_indent_cancel check ((status = 'CANCELLED') or (cancel_reason_key is null and cancel_note is null))
);

-- The requesting store's list (status filter, newest first) and the tiles.
create index if not exists ix_indent_from_store on pharmacy.indent (tenant_id, from_store_id, status, created_at desc);
-- The issuing store's inbox: indents to dispatch (APPROVED, PARTLY_DISPATCHED), by priority and age; also the child side of the to-store FK.
create index if not exists ix_indent_to_store on pharmacy.indent (tenant_id, to_store_id, status, raised_at)
  where status in ('APPROVED', 'PARTLY_DISPATCHED', 'DISPATCHED');

alter table pharmacy.indent enable row level security;
alter table pharmacy.indent force row level security;
create policy p_indent_select on pharmacy.indent for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_indent_insert on pharmacy.indent for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_indent_update on pharmacy.indent for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.indent from sahaj_app;
revoke all on pharmacy.indent from public;
grant select, insert, update on pharmacy.indent to sahaj_app;

-- Insert: DRAFT only. Update: REJECTED, CANCELLED, DISCARDED, CLOSED are final; only listed moves; identity and number never change;
-- the priority changes only while DRAFT (or in the update that leaves it).
create or replace function pharmacy.guard_indent_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'INSERT' then
    if new.status <> 'DRAFT' then
      raise exception 'indent must start as a draft' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if old.status in ('REJECTED', 'CANCELLED', 'DISCARDED', 'CLOSED') then
    raise exception 'indent is final (%)', old.status using errcode = 'check_violation';
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.from_store_id is distinct from old.from_store_id or new.to_store_id is distinct from old.to_store_id
     or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by
     or (old.doc_no is not null and new.doc_no is distinct from old.doc_no)
     or (old.doc_seq is not null and new.doc_seq is distinct from old.doc_seq)
     or (old.raised_at is not null and new.raised_at is distinct from old.raised_at) then
    raise exception 'indent identity cannot change' using errcode = 'check_violation';
  end if;
  if new.status is distinct from old.status and not (
       (old.status = 'DRAFT' and new.status in ('WAITING_APPROVAL', 'APPROVED', 'DISCARDED'))
    or (old.status = 'WAITING_APPROVAL' and new.status in ('APPROVED', 'REJECTED', 'CANCELLED'))
    or (old.status in ('APPROVED', 'PARTLY_DISPATCHED', 'DISPATCHED', 'RECEIVED')
        and new.status in ('APPROVED', 'PARTLY_DISPATCHED', 'DISPATCHED', 'RECEIVED', 'CLOSED'))
    or (old.status = 'APPROVED' and new.status = 'CANCELLED')) then
    raise exception 'indent cannot go from % to %', old.status, new.status using errcode = 'check_violation';
  end if;
  if old.status <> 'DRAFT' and new.priority is distinct from old.priority then
    raise exception 'indent priority cannot change once submitted' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_indent_write() from public;
revoke all on function pharmacy.guard_indent_write() from sahaj_app;
create trigger trg_indent_guard_write before insert or update on pharmacy.indent
  for each row execute function pharmacy.guard_indent_write();

comment on table pharmacy.indent is
  'Indent: a store asks the store that issues to it for stock (SAH-79-9 to 79-11, ADR-029 P12). IND number at first submit. Never deleted; REJECTED, CANCELLED, DISCARDED and CLOSED are final. The receipt chips and the status text are computed. No patient data.';

-- ======================================================================================================================
-- 2. pharmacy.indent_line
-- ======================================================================================================================
create table if not exists pharmacy.indent_line (
  id                       uuid primary key default gen_random_uuid(),
  tenant_id                uuid not null default platform.current_tenant()
                             constraint fk_indent_line_tenant references platform.tenant (id),
  unit_id                  uuid not null,
  indent_id                uuid not null,
  product_id               uuid not null,
  sort                     integer not null
                             constraint ck_indent_line_sort check (sort between 1 and 1000),
  removed                  boolean not null default false,
  requested_qty            integer not null
                             constraint ck_indent_line_requested check (requested_qty between 1 and 999999),
  -- Set by the approval (level 0: equal to requested; HOD may change it with a remark). Null until then.
  approved_qty             integer
                             constraint ck_indent_line_approved check (approved_qty between 0 and 999999),
  hod_remark_reason_key    text
                             constraint ck_indent_line_hod_remark check (hod_remark_reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  -- Running totals in issue units; each only goes up. dispatched + closed <= approved; received + rejected + short <= dispatched.
  dispatched_qty           integer not null default 0
                             constraint ck_indent_line_dispatched check (dispatched_qty >= 0),
  received_qty             integer not null default 0
                             constraint ck_indent_line_received check (received_qty >= 0),
  rejected_qty             integer not null default 0
                             constraint ck_indent_line_rejected check (rejected_qty >= 0),
  short_qty                integer not null default 0
                             constraint ck_indent_line_short check (short_qty >= 0),
  closed_qty               integer not null default 0
                             constraint ck_indent_line_closed check (closed_qty >= 0),
  -- What the issuing store chose for the unmet balance, and why it closed it (list cs-change).
  balance_action           text
                             constraint ck_indent_line_balance_action check (balance_action in ('BACK_ORDER', 'CLOSE')),
  close_reason_key         text
                             constraint ck_indent_line_close_reason check (close_reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  -- The product the issuing store sent instead (same salt and schedule: service rule, OQ-79-16), and why (list cs-change).
  replacement_product_id   uuid,
  replacement_reason_key   text
                             constraint ck_indent_line_replacement_reason check (replacement_reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  created_at               timestamptz not null default now(),
  created_by               uuid not null,
  updated_at               timestamptz,
  updated_by               uuid,
  version                  bigint not null default 0,
  constraint uq_indent_line_tenant_id unique (tenant_id, id),
  constraint fk_indent_line_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_indent_line_indent foreign key (tenant_id, indent_id) references pharmacy.indent (tenant_id, id),
  constraint fk_indent_line_product foreign key (tenant_id, product_id) references pharmacy.product (tenant_id, id),
  constraint fk_indent_line_replacement foreign key (tenant_id, replacement_product_id) references pharmacy.product (tenant_id, id),
  constraint ck_indent_line_dispatched_needs_approval check (approved_qty is not null or (dispatched_qty = 0 and closed_qty = 0)),
  constraint ck_indent_line_within_approved check (dispatched_qty + closed_qty <= coalesce(approved_qty, 0)),
  constraint ck_indent_line_within_dispatched check (received_qty + rejected_qty + short_qty <= dispatched_qty),
  constraint ck_indent_line_replacement check ((replacement_product_id is null) = (replacement_reason_key is null)),
  constraint ck_indent_line_removed check (not removed or dispatched_qty = 0)
);

-- One live line per product per indent.
create unique index if not exists uq_indent_line_product on pharmacy.indent_line (tenant_id, indent_id, product_id) where not removed;
-- The lines of one indent in screen order.
create index if not exists ix_indent_line_indent on pharmacy.indent_line (tenant_id, indent_id, sort);
-- Open indent quantities of a product (requesting stores' pending demand, "back order" counts) and the product FK.
create index if not exists ix_indent_line_product on pharmacy.indent_line (tenant_id, product_id) where not removed;
-- The replacement product FK child side (rare; only rows that have one).
create index if not exists ix_indent_line_replacement on pharmacy.indent_line (tenant_id, replacement_product_id)
  where replacement_product_id is not null;

alter table pharmacy.indent_line enable row level security;
alter table pharmacy.indent_line force row level security;
create policy p_indent_line_select on pharmacy.indent_line for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_indent_line_insert on pharmacy.indent_line for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_indent_line_update on pharmacy.indent_line for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.indent_line from sahaj_app;
revoke all on pharmacy.indent_line from public;
grant select, insert, update on pharmacy.indent_line to sahaj_app;

-- Insert, product, sort, requested quantity and removal only while the parent is a DRAFT. The approved quantity and the HOD remark change
-- only before anything is dispatched on the line and while the parent is DRAFT, WAITING_APPROVAL or APPROVED. The running totals only
-- go up. A removed line stays removed. The parent is read without a lock (the service holds the header lock, ADR-029 P5 step 3).
create or replace function pharmacy.guard_indent_line_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_parent text;
begin
  select i.status into v_parent from pharmacy.indent i where i.tenant_id = new.tenant_id and i.id = new.indent_id;
  if tg_op = 'INSERT' then
    if v_parent is not null and v_parent <> 'DRAFT' then
      raise exception 'indent lines can change only while the indent is a draft' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.indent_id is distinct from old.indent_id or new.created_at is distinct from old.created_at
     or new.created_by is distinct from old.created_by then
    raise exception 'indent line identity cannot change' using errcode = 'check_violation';
  end if;
  if old.removed and not new.removed then
    raise exception 'indent line stays removed' using errcode = 'check_violation';
  end if;
  if new.dispatched_qty < old.dispatched_qty or new.received_qty < old.received_qty or new.rejected_qty < old.rejected_qty
     or new.short_qty < old.short_qty or new.closed_qty < old.closed_qty then
    raise exception 'indent line quantities cannot go down' using errcode = 'check_violation';
  end if;
  if v_parent <> 'DRAFT' and (new.product_id is distinct from old.product_id or new.sort is distinct from old.sort
       or new.requested_qty is distinct from old.requested_qty or new.removed is distinct from old.removed) then
    raise exception 'indent lines can change only while the indent is a draft' using errcode = 'check_violation';
  end if;
  if (new.approved_qty is distinct from old.approved_qty or new.hod_remark_reason_key is distinct from old.hod_remark_reason_key)
     and (old.dispatched_qty > 0 or v_parent not in ('DRAFT', 'WAITING_APPROVAL', 'APPROVED')) then
    raise exception 'indent line approved quantity cannot change after dispatch' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_indent_line_write() from public;
revoke all on function pharmacy.guard_indent_line_write() from sahaj_app;
create trigger trg_indent_line_guard_write before insert or update on pharmacy.indent_line
  for each row execute function pharmacy.guard_indent_line_write();

comment on table pharmacy.indent_line is
  'Line of an indent: requested, approved (HOD may change it with a remark), dispatched, received, rejected, short and closed quantities in issue units, the balance action and the replacement product (SAH-79-9 to 79-11). Totals only go up. Product and requested quantity change only while the indent is a DRAFT. A removed line is flagged, never deleted.';
