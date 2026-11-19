-- SAH-79 WP-H (story 79-17), migration H1. ADR-029 P12 "Stock audit" (blind count), P9 (AUD number at start), P6 (STOCK_ADJUSTMENT request,
-- subject STOCK_AUDIT), P7 (Schedule X), OQ-79-26, BR-30, "Data needed" WP-H.
-- New objects only: tables pharmacy.stock_audit and pharmacy.stock_audit_line with guard functions and triggers, and the foreign key that ties
-- consumption.stock_audit_line_id (migration G3, a plain uuid until now) to the line. Depends on G3. Tenant tables, forced row level security,
-- no delete grant. The adjustment ledger lines (STOCK_AUDIT_ADJUSTMENT) are written by the posting service, not here.
-- Blind count (BR-30): the counter's operations never answer a system quantity. book_qty, book_ledger_id and book_occurred_at are on the line
-- (the table is one for the app role), so hiding them is the service's rule and a QA test: the count-sheet reads select only the counting columns.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.stock_audit (header)
-- ======================================================================================================================
create table if not exists pharmacy.stock_audit (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null default platform.current_tenant()
                          constraint fk_stock_audit_tenant references platform.tenant (id),
  unit_id               uuid not null,
  -- AUD number, taken at start (no draft).
  doc_seq               bigint not null
                          constraint ck_stock_audit_doc_seq check (doc_seq >= 1),
  doc_no                text not null
                          constraint ck_stock_audit_doc_no check (char_length(doc_no) between 1 and 16),
  store_id              uuid not null,
  scope                 text not null
                          constraint ck_stock_audit_scope check (scope in ('ALL_AVAILABLE', 'PRODUCTS')),
  status                text not null default 'COUNTING'
                          constraint ck_stock_audit_status check (status in
                            ('COUNTING', 'RECOUNT', 'WAITING_REVIEW', 'WAITING_APPROVAL', 'CLOSED')),
  -- Who submitted the count (the raiser of the approval request) and when. The reviewer is recorded as the level-1 decision of the request.
  submitted_by          uuid,
  submitted_at          timestamptz,
  approval_request_id   uuid,
  closed_at             timestamptz,
  started_at            timestamptz not null default now(),
  -- Membership id (opaque uuid) of the person who started it.
  created_by            uuid not null,
  updated_at            timestamptz,
  updated_by            uuid,
  version               bigint not null default 0,
  constraint uq_stock_audit_tenant_id unique (tenant_id, id),
  constraint uq_stock_audit_doc_no unique (tenant_id, unit_id, doc_no),
  constraint uq_stock_audit_doc_seq unique (tenant_id, unit_id, doc_seq),
  constraint fk_stock_audit_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_stock_audit_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_stock_audit_request foreign key (tenant_id, approval_request_id) references pharmacy.approval_request (tenant_id, id),
  constraint ck_stock_audit_submitted check ((submitted_at is null) = (submitted_by is null)),
  constraint ck_stock_audit_submitted_status check (status not in ('WAITING_REVIEW', 'WAITING_APPROVAL') or submitted_at is not null),
  constraint ck_stock_audit_closed check ((status = 'CLOSED') = (closed_at is not null))
);

-- One open audit per store (ADR-029 P12). RECOUNT is open too: the contract has it as a status, so it is in the list with the three of the ADR.
create unique index if not exists uq_stock_audit_one_open on pharmacy.stock_audit (tenant_id, store_id)
  where status in ('COUNTING', 'RECOUNT', 'WAITING_REVIEW', 'WAITING_APPROVAL');
-- The audits of a store, newest first (the audit list) and the store FK.
create index if not exists ix_stock_audit_store on pharmacy.stock_audit (tenant_id, store_id, started_at desc);

alter table pharmacy.stock_audit enable row level security;
alter table pharmacy.stock_audit force row level security;
create policy p_stock_audit_select on pharmacy.stock_audit for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_stock_audit_insert on pharmacy.stock_audit for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_stock_audit_update on pharmacy.stock_audit for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.stock_audit from sahaj_app;
revoke all on pharmacy.stock_audit from public;
grant select, insert, update on pharmacy.stock_audit to sahaj_app;

-- Insert: COUNTING only. Update: CLOSED is final; only listed moves; identity never changes; the submission is set by the first submit and
-- may be set again by a later submit after a recount (a resubmission), but never cleared.
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
  return new;
end;
$$;
revoke all on function pharmacy.guard_stock_audit_write() from public;
revoke all on function pharmacy.guard_stock_audit_write() from sahaj_app;
create trigger trg_stock_audit_guard_write before insert or update on pharmacy.stock_audit
  for each row execute function pharmacy.guard_stock_audit_write();

comment on table pharmacy.stock_audit is
  'Stock audit (count) of one store (SAH-79-17, ADR-029 P12): AUD number at start, one open audit per store, blind count, review, approval and posting of the variances. Never deleted; CLOSED is final. No patient data.';

-- ======================================================================================================================
-- 2. pharmacy.stock_audit_line: one batch to count
-- ======================================================================================================================
create table if not exists pharmacy.stock_audit_line (
  id                         uuid primary key default gen_random_uuid(),
  tenant_id                  uuid not null default platform.current_tenant()
                               constraint fk_stock_audit_line_tenant references platform.tenant (id),
  unit_id                    uuid not null,
  stock_audit_id             uuid not null,
  product_id                 uuid not null,
  batch_id                   uuid not null,
  -- COUNTING SIDE (what a counter may read and write): the count, or "not counted" with a reason (list stock-adjustment or the count list).
  counted_qty                integer
                               constraint ck_stock_audit_line_counted check (counted_qty between 0 and 999999),
  not_counted                boolean not null default false,
  not_counted_reason_key     text
                               constraint ck_stock_audit_line_not_counted_reason check (not_counted_reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  counted_by                 uuid,
  counted_at                 timestamptz,
  -- Asked by the reviewer; cleared when the line is counted again.
  recount_requested          boolean not null default false,
  -- BOOK SIDE (never shown to a counter): fixed at submit (the AVAILABLE balance at that moment) with the last ledger line of that store,
  -- batch and state and its time ("stock moved since the count" compares later lines). May go down by "post as consumption instead".
  book_qty                   integer
                               constraint ck_stock_audit_line_book check (book_qty >= 0),
  book_ledger_id             uuid,
  book_occurred_at           timestamptz,
  -- REVIEW SIDE: the reason chosen for a variance (list stock-adjustment) and a note.
  variance_reason_key        text
                               constraint ck_stock_audit_line_variance_reason check (variance_reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  variance_note              text
                               constraint ck_stock_audit_line_variance_note check (char_length(variance_note) between 1 and 300),
  -- Set once when the adjustment line was posted for this row (a refused line stays unposted and goes back to recount).
  posted_at                  timestamptz,
  created_at                 timestamptz not null default now(),
  created_by                 uuid not null,
  updated_at                 timestamptz,
  updated_by                 uuid,
  version                    bigint not null default 0,
  constraint uq_stock_audit_line_tenant_id unique (tenant_id, id),
  -- One line per batch in an audit; this key also serves "the lines of an audit".
  constraint uq_stock_audit_line_batch unique (tenant_id, stock_audit_id, batch_id),
  constraint fk_stock_audit_line_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_stock_audit_line_audit foreign key (tenant_id, stock_audit_id) references pharmacy.stock_audit (tenant_id, id),
  constraint fk_stock_audit_line_batch foreign key (tenant_id, batch_id, product_id) references pharmacy.batch (tenant_id, id, product_id),
  constraint ck_stock_audit_line_counted_by check ((counted_by is null) = (counted_at is null)),
  -- A line is either counted or "not counted" (with a reason), never both.
  constraint ck_stock_audit_line_not_counted check (
    (not not_counted and not_counted_reason_key is null) or (not_counted and counted_qty is null)),
  constraint ck_stock_audit_line_book_set check ((book_qty is null) = (book_occurred_at is null))
);

-- Audit lines of a batch (batch history: "was it counted") and the batch FK.
create index if not exists ix_stock_audit_line_batch on pharmacy.stock_audit_line (tenant_id, batch_id);

alter table pharmacy.stock_audit_line enable row level security;
alter table pharmacy.stock_audit_line force row level security;
create policy p_stock_audit_line_select on pharmacy.stock_audit_line for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_stock_audit_line_insert on pharmacy.stock_audit_line for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_stock_audit_line_update on pharmacy.stock_audit_line for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.stock_audit_line from sahaj_app;
revoke all on pharmacy.stock_audit_line from public;
grant select, insert, update on pharmacy.stock_audit_line to sahaj_app;

-- Insert: only while the audit is COUNTING (the lines are made at start). Update: nothing once the audit is CLOSED; identity (audit, product,
-- batch) never changes; the counting columns change only while the audit is COUNTING or RECOUNT; the book side is fixed at submit (while the
-- audit is COUNTING or RECOUNT, being submitted) and afterwards only goes down (consumption posted instead); posted_at is set once.
-- The parent is read without a lock (the audit header is locked by the service, ADR-029 P5 step 3).
create or replace function pharmacy.guard_stock_audit_line_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_parent text;
begin
  select a.status into v_parent from pharmacy.stock_audit a where a.tenant_id = new.tenant_id and a.id = new.stock_audit_id;
  if tg_op = 'INSERT' then
    if v_parent is not null and v_parent <> 'COUNTING' then
      raise exception 'stock audit lines can be added only while the audit is counting' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if v_parent = 'CLOSED' then
    raise exception 'stock audit is final (%)', v_parent using errcode = 'check_violation';
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.stock_audit_id is distinct from old.stock_audit_id or new.product_id is distinct from old.product_id
     or new.batch_id is distinct from old.batch_id or new.created_at is distinct from old.created_at
     or new.created_by is distinct from old.created_by then
    raise exception 'stock audit line identity cannot change' using errcode = 'check_violation';
  end if;
  if v_parent not in ('COUNTING', 'RECOUNT') and (
       new.counted_qty is distinct from old.counted_qty or new.not_counted is distinct from old.not_counted
       or new.not_counted_reason_key is distinct from old.not_counted_reason_key
       or new.counted_by is distinct from old.counted_by or new.counted_at is distinct from old.counted_at) then
    raise exception 'stock audit counts can change only while the audit is counting' using errcode = 'check_violation';
  end if;
  -- The book side is fixed at submit (it may be fixed again by a later submit after a recount, while the audit is COUNTING or RECOUNT);
  -- from the review on it may only go down (consumption posted instead).
  if old.book_qty is not null and v_parent not in ('COUNTING', 'RECOUNT')
     and (new.book_qty is null or new.book_qty > old.book_qty
       or new.book_ledger_id is distinct from old.book_ledger_id or new.book_occurred_at is distinct from old.book_occurred_at) then
    raise exception 'stock audit book quantity is fixed at submit' using errcode = 'check_violation';
  end if;
  if old.posted_at is not null and new.posted_at is distinct from old.posted_at then
    raise exception 'stock audit line posting is set once' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_stock_audit_line_write() from public;
revoke all on function pharmacy.guard_stock_audit_line_write() from sahaj_app;
create trigger trg_stock_audit_line_guard_write before insert or update on pharmacy.stock_audit_line
  for each row execute function pharmacy.guard_stock_audit_line_write();

comment on table pharmacy.stock_audit_line is
  'One batch to count in a stock audit (SAH-79-17). Counting side, book side (fixed at submit, never shown to a counter: BR-30) and review side on one row. Counts change only while the audit is counting or in recount; the book quantity only goes down after submit. Never deleted.';
comment on column pharmacy.stock_audit_line.book_qty is
  'System quantity (AVAILABLE) fixed at submit. BLIND COUNT: never selected by a counter operation (count-sheet, counts) and never in a schema those return.';

-- ======================================================================================================================
-- 3. Tie consumption (G3) to the audit line it corrects. The column existed as a plain uuid; every existing row has it null.
-- ======================================================================================================================
do $$
begin
  if not exists (select 1 from pg_catalog.pg_constraint
                  where conname = 'fk_consumption_stock_audit_line' and conrelid = 'pharmacy.consumption'::regclass) then
    alter table pharmacy.consumption
      add constraint fk_consumption_stock_audit_line foreign key (tenant_id, stock_audit_line_id)
        references pharmacy.stock_audit_line (tenant_id, id);
  end if;
end
$$;

-- Consumption entries that correct an audit line ("stock moved since the count" excludes them) and the audit-line FK child side.
create index if not exists ix_consumption_stock_audit_line on pharmacy.consumption (tenant_id, stock_audit_line_id)
  where stock_audit_line_id is not null;
