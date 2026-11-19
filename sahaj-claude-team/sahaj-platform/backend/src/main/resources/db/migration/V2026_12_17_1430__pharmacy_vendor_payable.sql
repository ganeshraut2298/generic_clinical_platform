-- SAH-79 WP-E (stories 79-7 and 79-8), migration E4. ADR-029 P10 "Vendor payable" (OQ-79-5 option A, G-19), "Data needed" WP-E.
-- New objects only: tables pharmacy.vendor_payable and pharmacy.vendor_payable_entry (append only), guard function and triggers.
-- Depends on E2 (goods_receipt) and E3 (debit_note). No payment, no ageing report, no accounts export in this release; billing is untouched.
-- Tenant tables, forced row level security, no delete grant. The payable is created at GRN posting; a credit note reduces payables
-- oldest due first (the debit note's own receipt first when it came from one); credit above what is open stays unapplied on the debit
-- note credit row (E3), never as a negative payable.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.vendor_payable: one per posted receipt
-- ======================================================================================================================
create table if not exists pharmacy.vendor_payable (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null default platform.current_tenant()
                      constraint fk_vendor_payable_tenant references platform.tenant (id),
  unit_id           uuid not null,
  vendor_id         uuid not null,
  goods_receipt_id  uuid not null,
  -- Copies of the receipt at posting, so the payable reads on its own.
  invoice_no        text not null
                      constraint ck_vendor_payable_invoice_no check (char_length(invoice_no) between 1 and 30),
  invoice_date      date not null,
  -- The typed invoice total (OQ-79-9b).
  amount_paise      bigint not null
                      constraint ck_vendor_payable_amount check (amount_paise between 0 and 99999999900),
  -- Invoice date + credit days (credit days of the receipt).
  due_date          date not null,
  credited_paise    bigint not null default 0
                      constraint ck_vendor_payable_credited check (credited_paise >= 0),
  status            text not null default 'OPEN'
                      constraint ck_vendor_payable_status check (status in ('OPEN', 'SETTLED_BY_CREDIT')),
  created_at        timestamptz not null default now(),
  created_by        uuid not null,
  updated_at        timestamptz,
  updated_by        uuid,
  version           bigint not null default 0,
  constraint uq_vendor_payable_tenant_id unique (tenant_id, id),
  -- One payable per posted receipt (a second post of the same receipt cannot create another).
  constraint uq_vendor_payable_receipt unique (tenant_id, goods_receipt_id),
  constraint fk_vendor_payable_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_vendor_payable_vendor foreign key (tenant_id, vendor_id) references pharmacy.vendor (tenant_id, id),
  constraint fk_vendor_payable_receipt foreign key (tenant_id, goods_receipt_id) references pharmacy.goods_receipt (tenant_id, id),
  -- Credit never exceeds the invoice; status follows it.
  constraint ck_vendor_payable_credit_within check (credited_paise <= amount_paise),
  constraint ck_vendor_payable_settled check (
    (status = 'SETTLED_BY_CREDIT' and credited_paise = amount_paise) or (status = 'OPEN' and credited_paise <= amount_paise))
);

-- Open payables of a vendor, oldest due first: where a credit note is applied (query: tenant, vendor, status OPEN, order by due_date)
-- and the vendor FK. The unique key on the receipt serves "the payable of this receipt".
create index if not exists ix_vendor_payable_open on pharmacy.vendor_payable (tenant_id, vendor_id, due_date) where status = 'OPEN';

alter table pharmacy.vendor_payable enable row level security;
alter table pharmacy.vendor_payable force row level security;
create policy p_vendor_payable_select on pharmacy.vendor_payable for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_vendor_payable_insert on pharmacy.vendor_payable for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_vendor_payable_update on pharmacy.vendor_payable for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.vendor_payable from sahaj_app;
revoke all on pharmacy.vendor_payable from public;
grant select, insert, update on pharmacy.vendor_payable to sahaj_app;

-- Only credited_paise, status and the bookkeeping columns move; credit never goes down; SETTLED_BY_CREDIT is final.
create or replace function pharmacy.guard_vendor_payable_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if old.status = 'SETTLED_BY_CREDIT' then
    raise exception 'vendor payable is final (%)', old.status using errcode = 'check_violation';
  end if;
  if (pg_catalog.to_jsonb(new) - 'credited_paise' - 'status' - 'updated_at' - 'updated_by' - 'version')
     is distinct from (pg_catalog.to_jsonb(old) - 'credited_paise' - 'status' - 'updated_at' - 'updated_by' - 'version') then
    raise exception 'vendor payable identity cannot change' using errcode = 'check_violation';
  end if;
  if new.credited_paise < old.credited_paise then
    raise exception 'vendor payable credit cannot go down' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_vendor_payable_update() from public;
revoke all on function pharmacy.guard_vendor_payable_update() from sahaj_app;
create trigger trg_vendor_payable_guard_update before update on pharmacy.vendor_payable
  for each row execute function pharmacy.guard_vendor_payable_update();

comment on table pharmacy.vendor_payable is
  'What the unit owes a vendor for one posted receipt (SAH-79-7, OQ-79-5 option A, G-19): invoice total, due date, credit applied. No payment is recorded in this release. Never deleted; SETTLED_BY_CREDIT is final.';

-- ======================================================================================================================
-- 2. pharmacy.vendor_payable_entry: append only (INVOICE +, CREDIT_NOTE -)
-- ======================================================================================================================
create table if not exists pharmacy.vendor_payable_entry (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null default platform.current_tenant()
                       constraint fk_vendor_payable_entry_tenant references platform.tenant (id),
  unit_id            uuid not null,
  payable_id         uuid not null,
  entry_type         text not null
                       constraint ck_vendor_payable_entry_type check (entry_type in ('INVOICE', 'CREDIT_NOTE')),
  -- INVOICE positive (or zero for a zero-value invoice); CREDIT_NOTE negative.
  amount_paise       bigint not null,
  -- What is still open on the payable after this entry; never negative (no negative payable).
  open_after_paise   bigint not null
                       constraint ck_vendor_payable_entry_open_after check (open_after_paise >= 0),
  -- The debit note a credit came from (CREDIT_NOTE only).
  debit_note_id      uuid,
  occurred_at        timestamptz not null default clock_timestamp(),
  created_by         uuid not null,
  constraint fk_vendor_payable_entry_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_vendor_payable_entry_payable foreign key (tenant_id, payable_id) references pharmacy.vendor_payable (tenant_id, id),
  constraint fk_vendor_payable_entry_note foreign key (tenant_id, debit_note_id) references pharmacy.debit_note (tenant_id, id),
  constraint ck_vendor_payable_entry_shape check (
    (entry_type = 'INVOICE' and amount_paise >= 0 and debit_note_id is null)
    or (entry_type = 'CREDIT_NOTE' and amount_paise < 0 and debit_note_id is not null))
);

-- One INVOICE entry per payable; one CREDIT_NOTE entry per payable and debit note.
create unique index if not exists uq_vendor_payable_entry_invoice on pharmacy.vendor_payable_entry (tenant_id, payable_id)
  where entry_type = 'INVOICE';
create unique index if not exists uq_vendor_payable_entry_credit on pharmacy.vendor_payable_entry (tenant_id, payable_id, debit_note_id)
  where entry_type = 'CREDIT_NOTE';
-- Entries of a payable in time order (payable card) is served by the INVOICE/CREDIT keys plus a sort of a handful of rows; the credits
-- of one debit note ("payable effect" on the note screen, and the debit note FK):
create index if not exists ix_vendor_payable_entry_note on pharmacy.vendor_payable_entry (tenant_id, debit_note_id) where debit_note_id is not null;

alter table pharmacy.vendor_payable_entry enable row level security;
alter table pharmacy.vendor_payable_entry force row level security;
create policy p_vendor_payable_entry_select on pharmacy.vendor_payable_entry for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_vendor_payable_entry_insert on pharmacy.vendor_payable_entry for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.vendor_payable_entry from sahaj_app;
revoke all on pharmacy.vendor_payable_entry from public;
grant select, insert on pharmacy.vendor_payable_entry to sahaj_app;

create trigger trg_vendor_payable_entry_no_update_delete before update or delete on pharmacy.vendor_payable_entry
  for each row execute function pharmacy.deny_append_only_mutation();
create trigger trg_vendor_payable_entry_no_truncate before truncate on pharmacy.vendor_payable_entry
  for each statement execute function pharmacy.deny_append_only_mutation();

comment on table pharmacy.vendor_payable_entry is
  'Append-only movements of a vendor payable (SAH-79, ADR-029 P10): INVOICE (+) when the receipt is posted, CREDIT_NOTE (-) with the debit note when a credit note is applied. open_after_paise is never negative. Select and insert only; triggers refuse update, delete and truncate (SQLSTATE 42501). Never deleted.';
