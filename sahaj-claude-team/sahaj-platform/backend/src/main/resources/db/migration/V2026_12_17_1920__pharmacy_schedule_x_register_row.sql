-- SAH-80 WP-J (story 80-10), migration J3. ADR-030 S8, "Data needed" (WP-J).
-- New table pharmacy.schedule_x_register_row: sale rows of the Schedule X / NDPS register, one ENTRY per Schedule X line of a bill, an IPD issue
-- or a ward return, and REVERSAL rows for a cancel or a sales return. Append-only (select and insert; triggers refuse update, delete,
-- truncate), never deleted. The balance register (opening, received, issued, book, physical) is a query over stock_ledger and the stock
-- audit lines; there is no table for it (ADR-030 S8, "No table for the balance register").
-- The prescription card is required: there is no override for Schedule X (OQ-80-18), so prescriber, registration number and prescription date
-- are not null. The two signers are different people (check). The source document is a type, id, number and line id, not a foreign key,
-- because it is a bill, an IPD issue (WP-M) or a ward return (WP-M).
-- Patient data: patient name and age, prescriber. Never logged, never in ledger, audit or activity text.

set local lock_timeout = '30s';

create table if not exists pharmacy.schedule_x_register_row (
  id                            uuid primary key default gen_random_uuid(),
  tenant_id                     uuid not null default platform.current_tenant()
                                  constraint fk_schedule_x_register_row_tenant references platform.tenant (id),
  unit_id                       uuid not null,
  store_id                      uuid not null,
  occurred_at                   timestamptz not null default now(),
  kind                          text not null
                                  constraint ck_schedule_x_register_row_kind check (kind in ('ENTRY', 'REVERSAL')),
  reverses_row_id               uuid,
  reversal_reason               text
                                  constraint ck_schedule_x_register_row_reversal_reason check (reversal_reason in ('CANCELLED', 'RETURNED')),
  -- The document behind the row: BILL, IPD_ISSUE, WARD_RETURN or SALES_RETURN; its id, its number (bill, slip or credit note) and line.
  source_type                   text not null
                                  constraint ck_schedule_x_register_row_source_type check (source_type in
                                    ('BILL', 'IPD_ISSUE', 'WARD_RETURN', 'SALES_RETURN')),
  source_id                     uuid not null,
  source_no                     varchar(24) not null
                                  constraint ck_schedule_x_register_row_source_no check (char_length(btrim(source_no)) between 1 and 24),
  source_line_id                uuid,
  patient_id                    uuid,
  patient_name                  varchar(80) not null
                                  constraint ck_schedule_x_register_row_patient_name check (char_length(btrim(patient_name)) between 1 and 80),
  patient_age                   integer not null
                                  constraint ck_schedule_x_register_row_patient_age check (patient_age between 0 and 130),
  prescriber_name               varchar(80) not null
                                  constraint ck_schedule_x_register_row_prescriber_name check (char_length(btrim(prescriber_name)) between 1 and 80),
  prescriber_reg_no             varchar(40) not null
                                  constraint ck_schedule_x_register_row_prescriber_reg_no check (char_length(btrim(prescriber_reg_no)) between 1 and 40),
  prescription_date             date not null,
  product_id                    uuid not null,
  batch_id                      uuid not null,
  -- Positive on an ENTRY, negative on a REVERSAL.
  qty                           integer not null
                                  constraint ck_schedule_x_register_row_qty check (qty <> 0 and qty between -999999 and 999999),
  first_signer_membership_id    uuid not null,
  second_signer_membership_id   uuid not null,
  constraint uq_schedule_x_register_row_tenant_id unique (tenant_id, id),
  constraint fk_schedule_x_register_row_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_schedule_x_register_row_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_schedule_x_register_row_batch foreign key (tenant_id, batch_id, product_id) references pharmacy.batch (tenant_id, id, product_id),
  constraint fk_schedule_x_register_row_reverses foreign key (tenant_id, reverses_row_id) references pharmacy.schedule_x_register_row (tenant_id, id),
  constraint ck_schedule_x_register_row_signers_differ check (second_signer_membership_id <> first_signer_membership_id),
  constraint ck_schedule_x_register_row_shape check (
    (kind = 'ENTRY' and qty > 0 and reverses_row_id is null and reversal_reason is null)
    or (kind = 'REVERSAL' and qty < 0 and reverses_row_id is not null and reversal_reason is not null))
);

-- The register page of a store, newest first.
create index if not exists ix_schedule_x_register_row_store on pharmacy.schedule_x_register_row (tenant_id, store_id, occurred_at desc);
-- One drug over a date range (the schedule-x-register report by product) and the batch foreign key.
create index if not exists ix_schedule_x_register_row_product on pharmacy.schedule_x_register_row (tenant_id, product_id, occurred_at);
-- The rows of a source document (cancel and return find the entries to reverse).
create index if not exists ix_schedule_x_register_row_source on pharmacy.schedule_x_register_row (tenant_id, source_type, source_id);
create index if not exists ix_schedule_x_register_row_reverses on pharmacy.schedule_x_register_row (tenant_id, reverses_row_id) where reverses_row_id is not null;

alter table pharmacy.schedule_x_register_row enable row level security;
alter table pharmacy.schedule_x_register_row force row level security;
create policy p_schedule_x_register_row_select on pharmacy.schedule_x_register_row for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_schedule_x_register_row_insert on pharmacy.schedule_x_register_row for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.schedule_x_register_row from sahaj_app;
revoke all on pharmacy.schedule_x_register_row from public;
grant select, insert on pharmacy.schedule_x_register_row to sahaj_app;

create trigger trg_schedule_x_register_row_no_update_delete before update or delete on pharmacy.schedule_x_register_row
  for each row execute function pharmacy.deny_append_only_mutation();
create trigger trg_schedule_x_register_row_no_truncate before truncate on pharmacy.schedule_x_register_row
  for each statement execute function pharmacy.deny_append_only_mutation();

comment on table pharmacy.schedule_x_register_row is
  'Schedule X / NDPS register, sale rows (SAH-80, ADR-030 S8): one ENTRY per Schedule X line of a bill, IPD issue or ward return, REVERSAL rows (negative quantity) for a cancel or sales return. Patient name and age, prescriber, prescription date, drug, batch, quantity, first and second signer (different people). Append-only, never deleted. The balance register is a query, not a table. Patient data: never logged, never in ledger, audit or activity text.';
