-- SAH-80 WP-I (stories 80-2, 80-4), migration I3. ADR-030 S5 "Customer account" and "Data needed" (WP-I).
-- New table pharmacy.customer and the guard pharmacy.guard_customer_update(). Tenant table, forced row level security, no delete grant.
-- The append-only account ledger pharmacy.customer_account_entry is in V2026_12_17_1850 (it points at bill).
-- Patient data: WALK_IN holds a typed name, phone and allergy note; PATIENT holds the patient id only (the name is read through patient.api).
-- patient_id is an opaque uuid (ADR-030 data conventions: no cross-module foreign key). Never logged, never in the ledger or audit text.

set local lock_timeout = '30s';

create table if not exists pharmacy.customer (
  id                         uuid primary key default gen_random_uuid(),
  tenant_id                  uuid not null default platform.current_tenant()
                               constraint fk_customer_tenant references platform.tenant (id),
  unit_id                    uuid not null,
  kind                       text not null
                               constraint ck_customer_kind check (kind in ('WALK_IN', 'PATIENT')),
  patient_id                 uuid,
  name                       varchar(80)
                               constraint ck_customer_name check (char_length(btrim(name)) between 1 and 80),
  phone                      char(10)
                               constraint ck_customer_phone check (phone ~ '^[0-9]{10}$'),
  allergy_note               varchar(200)
                               constraint ck_customer_allergy_note check (char_length(btrim(allergy_note)) between 1 and 200),
  credit_limit_paise         bigint not null default 0
                               constraint ck_customer_credit_limit check (credit_limit_paise >= 0),
  due_paise                  bigint not null default 0
                               constraint ck_customer_due check (due_paise >= 0),
  credit_note_balance_paise  bigint not null default 0
                               constraint ck_customer_cn_balance check (credit_note_balance_paise >= 0),
  status                     text not null default 'ACTIVE'
                               constraint ck_customer_status check (status in ('ACTIVE', 'INACTIVE')),
  created_at                 timestamptz not null default now(),
  created_by                 uuid not null,
  updated_at                 timestamptz,
  updated_by                 uuid,
  version                    bigint not null default 0,
  constraint uq_customer_tenant_id unique (tenant_id, id),
  constraint fk_customer_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  -- WALK_IN: a typed name and no patient. PATIENT: the patient id only; name, phone and allergy note stay in the patient module.
  constraint ck_customer_shape check (
    (kind = 'WALK_IN' and patient_id is null and name is not null)
    or (kind = 'PATIENT' and patient_id is not null and name is null and phone is null and allergy_note is null))
);

-- One customer row per patient per unit (a patient is picked again and again; the row carries the account).
create unique index if not exists uq_customer_patient on pharmacy.customer (tenant_id, unit_id, patient_id) where patient_id is not null;
-- Customer search by phone and by name (POS "Customer" picker, udhar list).
create index if not exists ix_customer_phone on pharmacy.customer (tenant_id, unit_id, phone) where phone is not null;
create index if not exists ix_customer_name on pharmacy.customer (tenant_id, unit_id, lower(name)) where name is not null;
-- The udhar list: customers who owe, largest first.
create index if not exists ix_customer_due on pharmacy.customer (tenant_id, unit_id, due_paise desc) where due_paise > 0;

alter table pharmacy.customer enable row level security;
alter table pharmacy.customer force row level security;
create policy p_customer_select on pharmacy.customer for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_customer_insert on pharmacy.customer for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_customer_update on pharmacy.customer for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.customer from sahaj_app;
revoke all on pharmacy.customer from public;
grant select, insert, update on pharmacy.customer to sahaj_app;

-- Identity cannot change after insert (a walk-in never becomes a patient customer; a link to a patient is recorded on the bill, SAH-80-3).
create or replace function pharmacy.guard_customer_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if new.tenant_id is distinct from old.tenant_id
     or new.unit_id is distinct from old.unit_id
     or new.kind is distinct from old.kind
     or new.patient_id is distinct from old.patient_id then
    raise exception 'customer identity cannot change' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_customer_update() from public;
revoke all on function pharmacy.guard_customer_update() from sahaj_app;

create trigger trg_customer_guard_update before update on pharmacy.customer
  for each row execute function pharmacy.guard_customer_update();

comment on table pharmacy.customer is
  'Customer account of the pharmacy (SAH-80, ADR-030 S5): WALK_IN (typed name, phone, allergy note) or PATIENT (patient id only). Carries the credit limit, the udhar due and the credit note balance; every change to the money is paired with an append-only customer_account_entry (service rule: the customer row is locked first, lock step 3). Never deleted; INACTIVE hides it. Patient data: never logged, never in the ledger or audit text.';
comment on column pharmacy.customer.phone is
  'Ten digits, typed at the counter. Shown masked, never logged.';
comment on column pharmacy.customer.allergy_note is
  'Standalone mode only: shown as "Allergy note (not checked)" (ADR-030 S9). Never logged, never in audit or activity text.';
comment on function pharmacy.guard_customer_update() is
  'Backstop: tenant, unit, kind and patient_id of a customer cannot change. SQLSTATE 23514.';
