-- SAH-80 WP-L (stories 80-18, 80-19), migration L1. ADR-030 S5 "Sales return" and "Data needed" (WP-L).
-- New tables: pharmacy.sales_return, pharmacy.sales_return_line; guards pharmacy.guard_sales_return_write() and
-- pharmacy.guard_sales_return_line_write(). Then the three foreign keys WP-I left open (credit_note, refund, customer_account_entry point at
-- sales_return) and sales_return.credit_note_no -> credit_note.
-- Tenant tables, forced row level security, no delete grant: a return is final by status, never deleted. The status path follows contract
-- 0.3.x SalesReturnStatus: WAITING_PHARMACIST, WAITING_APPROVAL, APPROVED, COMPLETED, REJECTED.
-- The refund the starter or the pharmacist chose (mode and amount, up to 5 rows) is kept on the return as a small jsonb array until the
-- return completes; the real refund rows are written to pharmacy.refund when it completes. The ledger lines (SALES_RETURN, reference = the
-- credit note number), reversing H1 and Schedule X rows, returned_qty on the bill lines and the account entries are written by the service
-- in the same transaction (ADR-030 S5).
-- No patient data on these tables (ids, quantities, money, reason keys; the note is a short staff note, never logged).

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.sales_return
-- ======================================================================================================================
create table if not exists pharmacy.sales_return (
  id                       uuid primary key default gen_random_uuid(),
  tenant_id                uuid not null default platform.current_tenant()
                             constraint fk_sales_return_tenant references platform.tenant (id),
  unit_id                  uuid not null,
  store_id                 uuid not null,
  bill_id                  uuid not null,
  status                   text not null
                             constraint ck_sales_return_status check (status in
                               ('WAITING_PHARMACIST', 'WAITING_APPROVAL', 'APPROVED', 'COMPLETED', 'REJECTED')),
  -- List sales-return.
  reason_key               text not null
                             constraint ck_sales_return_reason check (reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  note                     varchar(300)
                             constraint ck_sales_return_note check (char_length(btrim(note)) between 1 and 300),
  started_by               uuid not null,
  started_at               timestamptz not null default now(),
  accepted_by              uuid,
  accepted_at              timestamptz,
  completed_by             uuid,
  completed_at             timestamptz,
  -- The engine request (subject SALES_RETURN) when the refund total is above the rule amount.
  approval_request_id      uuid,
  refund_total_paise       bigint not null default 0
                             constraint ck_sales_return_refund_total check (refund_total_paise >= 0),
  -- Chosen refund per mode: [{"mode":"CASH","amountPaise":3070}], at most 5, until the return completes.
  refunds                  jsonb not null default '[]'::jsonb
                             constraint ck_sales_return_refunds check (jsonb_typeof(refunds) = 'array' and jsonb_array_length(refunds) <= 5),
  credit_note_no           varchar(24),
  created_at               timestamptz not null default now(),
  updated_at               timestamptz,
  updated_by               uuid,
  version                  bigint not null default 0,
  constraint uq_sales_return_tenant_id unique (tenant_id, id),
  constraint fk_sales_return_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_sales_return_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_sales_return_bill foreign key (tenant_id, bill_id) references pharmacy.bill (tenant_id, id),
  constraint fk_sales_return_approval foreign key (tenant_id, approval_request_id) references pharmacy.approval_request (tenant_id, id),
  -- Deferred to commit: credit_note.sales_return_id points back at this row, so a return may be inserted COMPLETED with its credit note
  -- number first and the credit_note row second, in the same transaction.
  constraint fk_sales_return_credit_note foreign key (tenant_id, credit_note_no) references pharmacy.credit_note (tenant_id, credit_note_no)
    deferrable initially deferred,
  -- A return is COMPLETED exactly when it has its credit note number and the completing person and time.
  constraint ck_sales_return_completed_shape check (
    (status = 'COMPLETED') = (credit_note_no is not null and completed_by is not null and completed_at is not null)
    or (status <> 'COMPLETED' and credit_note_no is null and completed_by is null and completed_at is null)),
  constraint ck_sales_return_accepted_pair check ((accepted_by is null) = (accepted_at is null))
);

-- One credit note number belongs to one return.
create unique index if not exists uq_sales_return_credit_note on pharmacy.sales_return (tenant_id, credit_note_no) where credit_note_no is not null;
-- The return list of a store (newest first, filter on status).
create index if not exists ix_sales_return_store_started on pharmacy.sales_return (tenant_id, store_id, started_at desc);
-- The returns of a bill (the returnable quantity, the return window check) and the bill foreign key.
create index if not exists ix_sales_return_bill on pharmacy.sales_return (tenant_id, bill_id);
-- Returns still waiting (the pharmacist's worklist, the mode guard, the command centre).
create index if not exists ix_sales_return_open on pharmacy.sales_return (tenant_id, store_id, status)
  where status in ('WAITING_PHARMACIST', 'WAITING_APPROVAL', 'APPROVED');
-- Completed returns by date (the sales-returns report, command-centre card).
create index if not exists ix_sales_return_completed on pharmacy.sales_return (tenant_id, store_id, completed_at desc) where completed_at is not null;
create index if not exists ix_sales_return_approval on pharmacy.sales_return (tenant_id, approval_request_id) where approval_request_id is not null;

alter table pharmacy.sales_return enable row level security;
alter table pharmacy.sales_return force row level security;
create policy p_sales_return_select on pharmacy.sales_return for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_sales_return_insert on pharmacy.sales_return for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_sales_return_update on pharmacy.sales_return for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.sales_return from sahaj_app;
revoke all on pharmacy.sales_return from public;
grant select, insert, update on pharmacy.sales_return to sahaj_app;

-- Guard: identity fixed; COMPLETED and REJECTED are final; the status moves forward only.
--   WAITING_PHARMACIST -> WAITING_APPROVAL, COMPLETED, REJECTED;  WAITING_APPROVAL -> APPROVED, REJECTED, COMPLETED;  APPROVED -> COMPLETED.
create or replace function pharmacy.guard_sales_return_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'INSERT' then
    if new.status not in ('WAITING_PHARMACIST', 'WAITING_APPROVAL', 'COMPLETED') then
      raise exception 'sales return cannot start as %', new.status using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.store_id is distinct from old.store_id or new.bill_id is distinct from old.bill_id
     or new.reason_key is distinct from old.reason_key or new.started_by is distinct from old.started_by
     or new.started_at is distinct from old.started_at then
    raise exception 'sales return identity cannot change' using errcode = 'check_violation';
  end if;
  if old.status in ('COMPLETED', 'REJECTED') then
    raise exception 'sales return is final (%)', old.status using errcode = 'check_violation';
  end if;
  if new.status is distinct from old.status and not (
       (old.status = 'WAITING_PHARMACIST' and new.status in ('WAITING_APPROVAL', 'COMPLETED', 'REJECTED'))
    or (old.status = 'WAITING_APPROVAL' and new.status in ('APPROVED', 'REJECTED', 'COMPLETED'))
    or (old.status = 'APPROVED' and new.status = 'COMPLETED')) then
    raise exception 'sales return cannot go from % to %', old.status, new.status using errcode = 'check_violation';
  end if;
  if old.accepted_by is not null and (new.accepted_by is distinct from old.accepted_by or new.accepted_at is distinct from old.accepted_at) then
    raise exception 'sales return acceptance is set once' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_sales_return_write() from public;
revoke all on function pharmacy.guard_sales_return_write() from sahaj_app;

create trigger trg_sales_return_guard_write before insert or update on pharmacy.sales_return
  for each row execute function pharmacy.guard_sales_return_write();

comment on table pharmacy.sales_return is
  'Sales return against one bill (SAH-80-18, ADR-030 S5): WAITING_PHARMACIST, WAITING_APPROVAL, APPROVED, COMPLETED, REJECTED. COMPLETED carries the credit note number. Never deleted. The refund per mode is a jsonb array until completion; the refund rows are written at completion. No patient data.';
comment on function pharmacy.guard_sales_return_write() is
  'Sales return guard: starts WAITING_PHARMACIST, WAITING_APPROVAL or COMPLETED; tenant, unit, store, bill, reason, starter and start time never change; COMPLETED and REJECTED are final; allowed moves WAITING_PHARMACIST to WAITING_APPROVAL, COMPLETED or REJECTED, WAITING_APPROVAL to APPROVED, REJECTED or COMPLETED, APPROVED to COMPLETED; acceptance is set once. SQLSTATE 23514.';

-- ======================================================================================================================
-- 2. pharmacy.sales_return_line
-- ======================================================================================================================
create table if not exists pharmacy.sales_return_line (
  id                            uuid primary key default gen_random_uuid(),
  tenant_id                     uuid not null default platform.current_tenant()
                                  constraint fk_sales_return_line_tenant references platform.tenant (id),
  unit_id                       uuid not null,
  sales_return_id               uuid not null,
  bill_line_id                  uuid not null,
  product_id                    uuid not null,
  batch_id                      uuid not null,
  qty                           integer not null
                                  constraint ck_sales_return_line_qty check (qty between 1 and 999999),
  -- List return-condition: .sealed, .opened, .damaged.
  condition_key                 text not null
                                  constraint ck_sales_return_line_condition check (condition_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  -- The pharmacist's decision on a doubtful line; null until decided.
  decision                      text
                                  constraint ck_sales_return_line_decision check (decision in ('ACCEPT', 'ACCEPT_QUARANTINE', 'REFUSE')),
  destination                   text not null default 'PENDING'
                                  constraint ck_sales_return_line_destination check (destination in ('AVAILABLE', 'QUARANTINE', 'REFUSED', 'PENDING')),
  refund_paise                  bigint not null default 0
                                  constraint ck_sales_return_line_refund check (refund_paise >= 0),
  taxable_paise                 bigint not null default 0
                                  constraint ck_sales_return_line_taxable check (taxable_paise >= 0),
  tax_paise                     bigint not null default 0
                                  constraint ck_sales_return_line_tax check (tax_paise >= 0),
  second_signer_membership_id   uuid,
  created_at                    timestamptz not null default now(),
  created_by                    uuid not null,
  constraint uq_sales_return_line_tenant_id unique (tenant_id, id),
  constraint uq_sales_return_line_bill_line unique (tenant_id, sales_return_id, bill_line_id),
  constraint fk_sales_return_line_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_sales_return_line_return foreign key (tenant_id, sales_return_id) references pharmacy.sales_return (tenant_id, id),
  constraint fk_sales_return_line_bill_line foreign key (tenant_id, bill_line_id) references pharmacy.bill_line (tenant_id, id),
  constraint fk_sales_return_line_batch foreign key (tenant_id, batch_id, product_id) references pharmacy.batch (tenant_id, id, product_id),
  constraint ck_sales_return_line_tax_identity check (taxable_paise + tax_paise = refund_paise),
  constraint ck_sales_return_line_signer_differs check (second_signer_membership_id is null or second_signer_membership_id <> created_by)
);

-- Returns of one bill line (the returnable quantity across returns) and the batch foreign key.
create index if not exists ix_sales_return_line_bill_line on pharmacy.sales_return_line (tenant_id, bill_line_id);
create index if not exists ix_sales_return_line_batch on pharmacy.sales_return_line (tenant_id, batch_id);
-- The lines of a return are served by uq_sales_return_line_bill_line (tenant_id, sales_return_id, bill_line_id).

alter table pharmacy.sales_return_line enable row level security;
alter table pharmacy.sales_return_line force row level security;
create policy p_sales_return_line_select on pharmacy.sales_return_line for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_sales_return_line_insert on pharmacy.sales_return_line for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_sales_return_line_update on pharmacy.sales_return_line for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.sales_return_line from sahaj_app;
revoke all on pharmacy.sales_return_line from public;
grant select, insert, update on pharmacy.sales_return_line to sahaj_app;

-- Guard: the identity of a line (return, bill line, product, batch, quantity, condition, creator) never changes; the decision, destination,
-- refund split and signer change only while the return is not final.
create or replace function pharmacy.guard_sales_return_line_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_status text;
begin
  if tg_op = 'UPDATE' then
    if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
       or new.sales_return_id is distinct from old.sales_return_id or new.bill_line_id is distinct from old.bill_line_id
       or new.product_id is distinct from old.product_id or new.batch_id is distinct from old.batch_id
       or new.qty is distinct from old.qty or new.condition_key is distinct from old.condition_key
       or new.created_by is distinct from old.created_by or new.created_at is distinct from old.created_at then
      raise exception 'sales return line identity cannot change' using errcode = 'check_violation';
    end if;
  end if;
  select r.status into v_status from pharmacy.sales_return r where r.tenant_id = new.tenant_id and r.id = new.sales_return_id;
  if tg_op = 'UPDATE' and v_status in ('COMPLETED', 'REJECTED') then
    raise exception 'sales return line is final (%)', v_status using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_sales_return_line_write() from public;
revoke all on function pharmacy.guard_sales_return_line_write() from sahaj_app;

create trigger trg_sales_return_line_guard_write before insert or update on pharmacy.sales_return_line
  for each row execute function pharmacy.guard_sales_return_line_write();

comment on table pharmacy.sales_return_line is
  'One bill line (batch) being returned (SAH-80-18, ADR-030 S5): quantity, condition, the pharmacist decision, destination, and the refund with its taxable and tax parts. Decision fields change only until the return is final. Never deleted.';
comment on function pharmacy.guard_sales_return_line_write() is
  'Sales return line guard: identity never changes; no update once the return is COMPLETED or REJECTED. The status is read under the row level security of the caller. SQLSTATE 23514.';

-- ======================================================================================================================
-- 3. Links left open by WP-I (tables are new in this release, so no NOT VALID is needed).
-- ======================================================================================================================
alter table pharmacy.credit_note drop constraint if exists fk_credit_note_sales_return;
alter table pharmacy.credit_note add constraint fk_credit_note_sales_return
  foreign key (tenant_id, sales_return_id) references pharmacy.sales_return (tenant_id, id);
alter table pharmacy.refund drop constraint if exists fk_refund_sales_return;
alter table pharmacy.refund add constraint fk_refund_sales_return
  foreign key (tenant_id, sales_return_id) references pharmacy.sales_return (tenant_id, id);
alter table pharmacy.customer_account_entry drop constraint if exists fk_customer_account_entry_sales_return;
alter table pharmacy.customer_account_entry add constraint fk_customer_account_entry_sales_return
  foreign key (tenant_id, sales_return_id) references pharmacy.sales_return (tenant_id, id);
create index if not exists ix_credit_note_sales_return on pharmacy.credit_note (tenant_id, sales_return_id) where sales_return_id is not null;
create index if not exists ix_refund_sales_return on pharmacy.refund (tenant_id, sales_return_id) where sales_return_id is not null;
create index if not exists ix_customer_account_entry_sales_return on pharmacy.customer_account_entry (tenant_id, sales_return_id) where sales_return_id is not null;
