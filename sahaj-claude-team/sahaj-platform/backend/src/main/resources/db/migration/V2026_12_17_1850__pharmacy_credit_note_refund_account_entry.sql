-- SAH-80 WP-I (stories 80-4, 80-5, 80-18), migration I6. ADR-030 S5, "Data needed" (WP-I: customer_account_entry, refund).
-- New tables: pharmacy.credit_note, pharmacy.refund, pharmacy.customer_account_entry. All three are append-only (select and insert; triggers
-- refuse update, delete and truncate), tenant tables with forced row level security. Never deleted.
-- pharmacy.credit_note is not in the ADR "Data needed" list (the ADR keeps the number on refund, bill and sales_return). It is added here,
-- conservatively, because a CREDIT_NOTE number is issued by two sources (a cancelled bill's refund and a sales return) and a gapless,
-- unique number needs one table that both write to. Deviation recorded in docs/database/SAH-80-sales.md.
-- sales_return_id on credit_note, refund and customer_account_entry is a plain uuid here; WP-L (V2026_12_17_2000) adds the foreign keys.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.credit_note: the number issued by next_document_no('CREDIT_NOTE').
-- ======================================================================================================================
create table if not exists pharmacy.credit_note (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null default platform.current_tenant()
                       constraint fk_credit_note_tenant references platform.tenant (id),
  unit_id            uuid not null,
  store_id           uuid not null,
  credit_note_no     varchar(24) not null
                       constraint ck_credit_note_no check (char_length(btrim(credit_note_no)) between 1 and 24),
  fiscal_year        integer not null
                       constraint ck_credit_note_fiscal_year check (fiscal_year = 0 or fiscal_year between 2000 and 2999),
  source_kind        text not null
                       constraint ck_credit_note_source check (source_kind in ('CANCELLED_BILL', 'SALES_RETURN')),
  bill_id            uuid not null,
  sales_return_id    uuid,
  amount_paise       bigint not null
                       constraint ck_credit_note_amount check (amount_paise > 0),
  issued_at          timestamptz not null default now(),
  issued_by          uuid not null,
  constraint uq_credit_note_tenant_id unique (tenant_id, id),
  constraint uq_credit_note_number unique (tenant_id, credit_note_no),
  constraint fk_credit_note_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_credit_note_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_credit_note_bill foreign key (tenant_id, bill_id) references pharmacy.bill (tenant_id, id),
  constraint ck_credit_note_source_shape check ((source_kind = 'SALES_RETURN') = (sales_return_id is not null))
);

create unique index if not exists uq_credit_note_no_lower on pharmacy.credit_note (tenant_id, unit_id, lower(credit_note_no));
-- The credit notes of a bill (bill card, return window check) and the GST register by date.
create index if not exists ix_credit_note_bill on pharmacy.credit_note (tenant_id, bill_id);
create index if not exists ix_credit_note_store_issued on pharmacy.credit_note (tenant_id, store_id, issued_at desc);

alter table pharmacy.credit_note enable row level security;
alter table pharmacy.credit_note force row level security;
create policy p_credit_note_select on pharmacy.credit_note for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_credit_note_insert on pharmacy.credit_note for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.credit_note from sahaj_app;
revoke all on pharmacy.credit_note from public;
grant select, insert on pharmacy.credit_note to sahaj_app;

create trigger trg_credit_note_no_update_delete before update or delete on pharmacy.credit_note
  for each row execute function pharmacy.deny_append_only_mutation();
create trigger trg_credit_note_no_truncate before truncate on pharmacy.credit_note
  for each statement execute function pharmacy.deny_append_only_mutation();

comment on table pharmacy.credit_note is
  'Credit note numbers issued by the pharmacy (SAH-80, ADR-030 S5): one per cancelled-bill refund or completed sales return, taken with next_document_no(''CREDIT_NOTE'') after the balance locks. Append-only, never deleted (legal series, G-22). No patient data.';

-- ======================================================================================================================
-- 2. pharmacy.refund: money given back, one row per mode. Source: a cancelled bill (settled later) or a sales return.
-- ======================================================================================================================
create table if not exists pharmacy.refund (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null default platform.current_tenant()
                          constraint fk_refund_tenant references platform.tenant (id),
  unit_id               uuid not null,
  store_id              uuid not null,
  source_kind           text not null
                          constraint ck_refund_source check (source_kind in ('CANCELLED_BILL', 'SALES_RETURN')),
  bill_id               uuid not null,
  sales_return_id       uuid,
  mode                  text not null
                          constraint ck_refund_mode check (mode in ('CASH', 'UPI', 'CARD', 'CREDIT_NOTE', 'UDHAR')),
  amount_paise          bigint not null
                          constraint ck_refund_amount check (amount_paise > 0),
  credit_note_no        varchar(24) not null,
  actor_membership_id   uuid not null,
  refunded_at           timestamptz not null default now(),
  constraint uq_refund_mode unique (tenant_id, source_kind, bill_id, credit_note_no, mode),
  constraint fk_refund_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_refund_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_refund_bill foreign key (tenant_id, bill_id) references pharmacy.bill (tenant_id, id),
  constraint fk_refund_credit_note foreign key (tenant_id, credit_note_no) references pharmacy.credit_note (tenant_id, credit_note_no),
  constraint ck_refund_source_shape check ((source_kind = 'SALES_RETURN') = (sales_return_id is not null))
);

-- Refunds of a bill (bill card) and the day close: refunds of a person in a period.
create index if not exists ix_refund_bill on pharmacy.refund (tenant_id, bill_id);
create index if not exists ix_refund_actor on pharmacy.refund (tenant_id, store_id, actor_membership_id, refunded_at);

alter table pharmacy.refund enable row level security;
alter table pharmacy.refund force row level security;
create policy p_refund_select on pharmacy.refund for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_refund_insert on pharmacy.refund for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.refund from sahaj_app;
revoke all on pharmacy.refund from public;
grant select, insert on pharmacy.refund to sahaj_app;

create trigger trg_refund_no_update_delete before update or delete on pharmacy.refund
  for each row execute function pharmacy.deny_append_only_mutation();
create trigger trg_refund_no_truncate before truncate on pharmacy.refund
  for each statement execute function pharmacy.deny_append_only_mutation();

comment on table pharmacy.refund is
  'Refund rows (SAH-80, ADR-030 S5): money given back for a cancelled bill (settled with a credit note number) or a sales return, one row per mode. Append-only, never deleted. No patient data. The mode rules (allowed modes of the unit, original mode) are service checks.';

-- ======================================================================================================================
-- 3. pharmacy.customer_account_entry: the udhar ledger of a customer. Append-only; each row carries the running due and credit note
--    balance after it, so the account can be read without a sum. The customer row is locked first (lock step 3) and updated in the same
--    transaction; the service keeps customer.due_paise and credit_note_balance_paise equal to the last entry (QA test).
-- ======================================================================================================================
create table if not exists pharmacy.customer_account_entry (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null default platform.current_tenant()
                          constraint fk_customer_account_entry_tenant references platform.tenant (id),
  unit_id               uuid not null,
  customer_id           uuid not null,
  store_id              uuid not null,
  kind                  text not null
                          constraint ck_customer_account_entry_kind check (kind in
                            ('CREDIT_BILL', 'PAYMENT', 'CANCELLED_BILL', 'RETURN_TO_UDHAR', 'CREDIT_NOTE_ISSUED', 'CREDIT_NOTE_USED')),
  amount_paise          bigint not null
                          constraint ck_customer_account_entry_amount check (amount_paise > 0),
  due_after_paise       bigint not null
                          constraint ck_customer_account_entry_due_after check (due_after_paise >= 0),
  cn_balance_after_paise bigint not null
                          constraint ck_customer_account_entry_cn_after check (cn_balance_after_paise >= 0),
  bill_id               uuid,
  sales_return_id       uuid,
  -- PAYMENT only: how the customer paid the due.
  mode                  text
                          constraint ck_customer_account_entry_mode check (mode in ('CASH', 'UPI', 'CARD')),
  actor_membership_id   uuid not null,
  occurred_at           timestamptz not null default now(),
  constraint fk_customer_account_entry_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_customer_account_entry_customer foreign key (tenant_id, customer_id) references pharmacy.customer (tenant_id, id),
  constraint fk_customer_account_entry_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_customer_account_entry_bill foreign key (tenant_id, bill_id) references pharmacy.bill (tenant_id, id),
  constraint ck_customer_account_entry_mode_shape check ((kind = 'PAYMENT') = (mode is not null)),
  constraint ck_customer_account_entry_bill_shape check (
    kind not in ('CREDIT_BILL', 'CANCELLED_BILL', 'CREDIT_NOTE_USED') or bill_id is not null)
);

-- Account statement of a customer, newest first; the "over 60 days" ageing reads the same index.
create index if not exists ix_customer_account_entry_customer on pharmacy.customer_account_entry (tenant_id, customer_id, occurred_at desc);
-- Day close: udhar payments received by a person in a period.
create index if not exists ix_customer_account_entry_actor on pharmacy.customer_account_entry (tenant_id, store_id, actor_membership_id, occurred_at);

alter table pharmacy.customer_account_entry enable row level security;
alter table pharmacy.customer_account_entry force row level security;
create policy p_customer_account_entry_select on pharmacy.customer_account_entry for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_customer_account_entry_insert on pharmacy.customer_account_entry for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.customer_account_entry from sahaj_app;
revoke all on pharmacy.customer_account_entry from public;
grant select, insert on pharmacy.customer_account_entry to sahaj_app;

create trigger trg_customer_account_entry_no_update_delete before update or delete on pharmacy.customer_account_entry
  for each row execute function pharmacy.deny_append_only_mutation();
create trigger trg_customer_account_entry_no_truncate before truncate on pharmacy.customer_account_entry
  for each statement execute function pharmacy.deny_append_only_mutation();

comment on table pharmacy.customer_account_entry is
  'Udhar and credit note ledger of a customer (SAH-80, ADR-030 S5): CREDIT_BILL, PAYMENT, CANCELLED_BILL, RETURN_TO_UDHAR, CREDIT_NOTE_ISSUED, CREDIT_NOTE_USED, each with the due and credit note balance after it. Append-only, never deleted. No names; the customer is an id.';
