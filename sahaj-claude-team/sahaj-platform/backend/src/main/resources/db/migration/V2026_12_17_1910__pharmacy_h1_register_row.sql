-- SAH-80 WP-J (stories 80-8 to 80-10), migration J2. ADR-030 S7 (DEC-074 OQ-80-17, binding), "Data needed" (WP-J).
-- New table pharmacy.h1_register_row: the Schedule H1 register, one ENTRY row per H1 bill line, REVERSAL rows (negative quantity) for a cancel
-- or a sales return. Append-only: select and insert only, triggers refuse update, delete and truncate. Never deleted: kept until the human
-- sets a legal period (G-11, G-23).
-- Patient data: patient name, age and address (address and age are asked only on a bill with an H1 line and stored only here), prescriber
-- name and registration number, prescription date. Tenant isolated; never logged, never in the ledger, audit or activity text; read only
-- through the register endpoints (each page audited) and the audited CSV.

set local lock_timeout = '30s';

create table if not exists pharmacy.h1_register_row (
  id                            uuid primary key default gen_random_uuid(),
  tenant_id                     uuid not null default platform.current_tenant()
                                  constraint fk_h1_register_row_tenant references platform.tenant (id),
  unit_id                       uuid not null,
  store_id                      uuid not null,
  occurred_at                   timestamptz not null default now(),
  kind                          text not null
                                  constraint ck_h1_register_row_kind check (kind in ('ENTRY', 'REVERSAL')),
  reverses_row_id               uuid,
  reversal_reason               text
                                  constraint ck_h1_register_row_reversal_reason check (reversal_reason in ('CANCELLED', 'RETURNED')),
  -- The credit note number of a RETURNED reversal; the bill number is always on the row.
  reversal_ref                  varchar(24)
                                  constraint ck_h1_register_row_reversal_ref check (char_length(btrim(reversal_ref)) between 1 and 24),
  bill_id                       uuid not null,
  bill_line_id                  uuid not null,
  bill_no                       varchar(16) not null
                                  constraint ck_h1_register_row_bill_no check (char_length(btrim(bill_no)) between 1 and 16),
  patient_id                    uuid,
  patient_name                  varchar(80) not null
                                  constraint ck_h1_register_row_patient_name check (char_length(btrim(patient_name)) between 1 and 80),
  patient_age                   integer not null
                                  constraint ck_h1_register_row_patient_age check (patient_age between 0 and 130),
  patient_address               varchar(200) not null
                                  constraint ck_h1_register_row_patient_address check (char_length(btrim(patient_address)) between 1 and 200),
  prescriber_name               varchar(80)
                                  constraint ck_h1_register_row_prescriber_name check (char_length(btrim(prescriber_name)) between 1 and 80),
  prescriber_reg_no             varchar(40)
                                  constraint ck_h1_register_row_prescriber_reg_no check (char_length(btrim(prescriber_reg_no)) between 1 and 40),
  prescription_date             date,
  rx_source                     text
                                  constraint ck_h1_register_row_rx_source check (rx_source in ('E_RX', 'TYPED')),
  rx_record_id                  uuid,
  -- The drug supplied and, when substituted, the product the prescription named.
  product_id                    uuid not null,
  prescribed_product_id         uuid,
  batch_id                      uuid not null,
  -- Positive on an ENTRY, negative on a REVERSAL.
  qty                           integer not null
                                  constraint ck_h1_register_row_qty check (qty <> 0 and qty between -999999 and 999999),
  pharmacist_membership_id      uuid not null,
  -- A missing prescriber was overridden by a pharmacist (reason key only; the note stays on the bill line).
  override_reason_key           text
                                  constraint ck_h1_register_row_override check (override_reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  constraint uq_h1_register_row_tenant_id unique (tenant_id, id),
  constraint fk_h1_register_row_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_h1_register_row_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_h1_register_row_bill foreign key (tenant_id, bill_id) references pharmacy.bill (tenant_id, id),
  constraint fk_h1_register_row_bill_line foreign key (tenant_id, bill_line_id) references pharmacy.bill_line (tenant_id, id),
  constraint fk_h1_register_row_batch foreign key (tenant_id, batch_id, product_id) references pharmacy.batch (tenant_id, id, product_id),
  constraint fk_h1_register_row_prescribed_product foreign key (tenant_id, prescribed_product_id) references pharmacy.product (tenant_id, id),
  constraint fk_h1_register_row_reverses foreign key (tenant_id, reverses_row_id) references pharmacy.h1_register_row (tenant_id, id),
  constraint ck_h1_register_row_entry_shape check (
    (kind = 'ENTRY' and qty > 0 and reverses_row_id is null and reversal_reason is null and reversal_ref is null)
    or (kind = 'REVERSAL' and qty < 0 and reverses_row_id is not null and reversal_reason is not null
        and (reversal_reason = 'RETURNED') = (reversal_ref is not null))),
  -- Prescriber, registration number and prescription date are all present, unless a pharmacist overrode a missing prescriber.
  constraint ck_h1_register_row_prescriber check (
    override_reason_key is not null
    or (prescriber_name is not null and prescriber_reg_no is not null and prescription_date is not null)),
  constraint ck_h1_register_row_rx_record check (rx_record_id is null or rx_source = 'E_RX')
);

-- The register page of a store, newest first (filters on date).
create index if not exists ix_h1_register_row_store on pharmacy.h1_register_row (tenant_id, store_id, occurred_at desc);
-- One drug over a date range (the h1-register report by product).
create index if not exists ix_h1_register_row_product on pharmacy.h1_register_row (tenant_id, product_id, occurred_at);
-- The rows of a bill (cancel and return write the reversals; bill card) and the batch foreign key.
create index if not exists ix_h1_register_row_bill on pharmacy.h1_register_row (tenant_id, bill_id);
-- The reversals of a row.
create index if not exists ix_h1_register_row_reverses on pharmacy.h1_register_row (tenant_id, reverses_row_id) where reverses_row_id is not null;

alter table pharmacy.h1_register_row enable row level security;
alter table pharmacy.h1_register_row force row level security;
create policy p_h1_register_row_select on pharmacy.h1_register_row for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_h1_register_row_insert on pharmacy.h1_register_row for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.h1_register_row from sahaj_app;
revoke all on pharmacy.h1_register_row from public;
grant select, insert on pharmacy.h1_register_row to sahaj_app;

create trigger trg_h1_register_row_no_update_delete before update or delete on pharmacy.h1_register_row
  for each row execute function pharmacy.deny_append_only_mutation();
create trigger trg_h1_register_row_no_truncate before truncate on pharmacy.h1_register_row
  for each statement execute function pharmacy.deny_append_only_mutation();

comment on table pharmacy.h1_register_row is
  'Schedule H1 register (SAH-80, ADR-030 S7, DEC-074 OQ-80-17): one ENTRY per H1 bill line with patient name, age and address, prescriber, prescription date, drug, batch, quantity and pharmacist; REVERSAL rows (negative quantity, reverses_row_id, reason CANCELLED or RETURNED) for a cancel or return. Append-only, never deleted (kept until the human sets a period, G-11, G-23). Patient data: never logged, never in ledger, audit or activity text.';
comment on column pharmacy.h1_register_row.patient_address is
  'Typed on the card for every H1 bill (open point O-15; the patient port has no address). Stored only here. Never logged.';
comment on column pharmacy.h1_register_row.override_reason_key is
  'Reason key of the pharmacist override for a missing prescriber (list h1-override). The note stays on the bill line.';
