-- SAH-25 · IPD extra charges on the running bill. Forward-only.
-- Append-only like ipd.deposit. Money is integer paise. Same-schema FK is composite (tenant_id, id).

create table if not exists ipd.admission_charge (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null default platform.current_tenant()
               constraint fk_admission_charge_tenant references platform.tenant (id),
  admission_id uuid not null,
  service_key  text not null
               constraint ck_admission_charge_service_key check (service_key in
                 ('visit', 'ivfluid', 'injection', 'cbc', 'xray', 'oxygen', 'dressing', 'ecg', 'nebulisation', 'physio')),
  service_name text not null
               constraint ck_admission_charge_service_name check (char_length(service_name) between 1 and 100),
  unit_paise   bigint not null
               constraint ck_admission_charge_unit check (unit_paise >= 0 and unit_paise <= 100000000),
  qty          integer not null default 1
               constraint ck_admission_charge_qty check (qty between 1 and 50),
  added_at     timestamptz not null default now(),
  added_by     uuid not null,
  constraint fk_admission_charge_admission foreign key (tenant_id, admission_id) references ipd.admission (tenant_id, id)
);
-- Charges of an admission (running bill, count per service).
create index if not exists ix_admission_charge_tenant_admission on ipd.admission_charge (tenant_id, admission_id);

alter table ipd.admission_charge enable row level security;
alter table ipd.admission_charge force row level security;

create policy p_admission_charge_select on ipd.admission_charge
  for select to sahaj_app
  using (tenant_id = platform.current_tenant());
create policy p_admission_charge_insert on ipd.admission_charge
  for insert to sahaj_app
  with check (tenant_id = platform.current_tenant());

revoke all on ipd.admission_charge from sahaj_app;
revoke all on ipd.admission_charge from public;
grant select on ipd.admission_charge to sahaj_app;
grant insert (admission_id, service_key, service_name, unit_paise, qty, added_by) on ipd.admission_charge to sahaj_app;

create trigger trg_admission_charge_no_update_delete
  before update or delete on ipd.admission_charge
  for each row execute function platform.deny_audit_mutation();
create trigger trg_admission_charge_no_truncate
  before truncate on ipd.admission_charge
  for each statement execute function platform.deny_audit_mutation();

comment on table ipd.admission_charge is 'IPD extra charge added to the running bill (SAH-25). Append-only: no update, delete or truncate (privilege and trigger). Reversal comes with discharge/billing.';
