-- SAH-23 · IPD admission and deposits. Forward-only.
-- Money is integer paise. Same-schema FKs are composite (tenant_id, id).

-- 1. Deposit rules on ipd.config (lazy row; defaults apply to existing rows).
alter table ipd.config
  add column if not exists dep_planned_paise bigint not null default 1000000
    constraint ck_ipd_config_dep_planned check (dep_planned_paise between 0 and 100000000),
  add column if not exists dep_emergency_paise bigint not null default 500000
    constraint ck_ipd_config_dep_emergency check (dep_emergency_paise between 0 and 100000000),
  add column if not exists dep_daycare_paise bigint not null default 300000
    constraint ck_ipd_config_dep_daycare check (dep_daycare_paise between 0 and 100000000),
  add column if not exists dep_maternity_paise bigint not null default 1500000
    constraint ck_ipd_config_dep_maternity check (dep_maternity_paise between 0 and 100000000),
  add column if not exists cat_add_gen_paise bigint not null default 0
    constraint ck_ipd_config_cat_add_gen check (cat_add_gen_paise between 0 and 100000000),
  add column if not exists cat_add_semi_paise bigint not null default 200000
    constraint ck_ipd_config_cat_add_semi check (cat_add_semi_paise between 0 and 100000000),
  add column if not exists cat_add_pvt_paise bigint not null default 500000
    constraint ck_ipd_config_cat_add_pvt check (cat_add_pvt_paise between 0 and 100000000),
  add column if not exists cat_add_icu_paise bigint not null default 1000000
    constraint ck_ipd_config_cat_add_icu check (cat_add_icu_paise between 0 and 100000000),
  add column if not exists emergency_without_deposit boolean not null default true,
  add column if not exists deposit_alert_percent integer not null default 80
    constraint ck_ipd_config_deposit_alert check (deposit_alert_percent between 50 and 100 and deposit_alert_percent % 5 = 0),
  add column if not exists deposit_rules_confirmed boolean not null default false;

-- 2. Composite-FK target on ipd.bed (bed had only unique (tenant_id, code)).
alter table ipd.bed add constraint uq_bed_tenant_id unique (tenant_id, id);

-- 3. ipd.admission
create table if not exists ipd.admission (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null default platform.current_tenant()
                       constraint fk_admission_tenant references platform.tenant (id),
  patient_id           uuid not null
                       constraint fk_admission_patient references patient.patient (id),
  ip_number            text not null
                       constraint ck_admission_ip_number check (char_length(ip_number) between 1 and 30),
  bed_id               uuid not null,
  doctor_membership_id uuid not null,
  admission_type       text not null
                       constraint ck_admission_type check (admission_type in ('planned', 'emergency', 'daycare', 'maternity')),
  source               text not null default 'DIRECT'
                       constraint ck_admission_source check (source in ('DIRECT', 'OPD', 'EMERGENCY')),
  payer                text not null default 'Self'
                       constraint ck_admission_payer check (payer in ('Self', 'Insurance', 'Corporate')),
  reason               text not null default 'Under evaluation'
                       constraint ck_admission_reason check (char_length(reason) between 1 and 200),
  consent_signed       boolean not null
                       constraint ck_admission_consent check (consent_signed),
  status               text not null default 'ADMITTED'
                       constraint ck_admission_status check (status in ('ADMITTED', 'DISCHARGED')),
  admitted_at          timestamptz not null default now(),
  admitted_by          uuid not null,
  discharged_at        timestamptz,
  version              bigint not null default 0,
  constraint uq_admission_tenant_id unique (tenant_id, id),
  constraint uq_admission_tenant_ip_number unique (tenant_id, ip_number),
  -- ON DELETE NO ACTION: a bed that ever had an admission cannot be deleted (bed has a DELETE grant until later stories).
  constraint fk_admission_bed foreign key (tenant_id, bed_id) references ipd.bed (tenant_id, id)
);
-- One current admission per patient.
create unique index if not exists ux_admission_tenant_patient_admitted
  on ipd.admission (tenant_id, patient_id) where status = 'ADMITTED';
-- Admitted list, newest first.
create index if not exists ix_admission_tenant_status_admitted_at on ipd.admission (tenant_id, status, admitted_at);
-- Bed history check and bed lookup.
create index if not exists ix_admission_tenant_bed on ipd.admission (tenant_id, bed_id);

-- 4. ipd.deposit (append-only)
create table if not exists ipd.deposit (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null default platform.current_tenant()
               constraint fk_deposit_tenant references platform.tenant (id),
  admission_id uuid not null,
  amount_paise bigint not null
               constraint ck_deposit_amount check (amount_paise > 0 and amount_paise <= 100000000),
  mode         text not null
               constraint ck_deposit_mode check (mode in ('Cash', 'UPI', 'Card')),
  collected_at timestamptz not null default now(),
  collected_by uuid not null,
  constraint fk_deposit_admission foreign key (tenant_id, admission_id) references ipd.admission (tenant_id, id)
);
-- Deposits of an admission (detail, totals).
create index if not exists ix_deposit_tenant_admission on ipd.deposit (tenant_id, admission_id, collected_at);

-- 5. ipd.ip_sequence (per clinic and financial year, e.g. fy = '26-27')
create table if not exists ipd.ip_sequence (
  tenant_id uuid not null default platform.current_tenant()
            constraint fk_ip_sequence_tenant references platform.tenant (id),
  fy        text not null
            constraint ck_ip_sequence_fy check (fy ~ '^[0-9]{2}-[0-9]{2}$'),
  last_no   integer not null
            constraint ck_ip_sequence_last_no check (last_no >= 0),
  primary key (tenant_id, fy)
);

-- 6. RLS
alter table ipd.admission enable row level security;
alter table ipd.admission force row level security;
alter table ipd.deposit enable row level security;
alter table ipd.deposit force row level security;
alter table ipd.ip_sequence enable row level security;
alter table ipd.ip_sequence force row level security;

create policy p_admission_tenant on ipd.admission
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());
create policy p_deposit_select on ipd.deposit
  for select to sahaj_app
  using (tenant_id = platform.current_tenant());
create policy p_deposit_insert on ipd.deposit
  for insert to sahaj_app
  with check (tenant_id = platform.current_tenant());
create policy p_ip_sequence_tenant on ipd.ip_sequence
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- 7. Grants (minimal; column-level insert so id, tenant_id and timestamps keep their defaults)
revoke all on ipd.admission, ipd.deposit, ipd.ip_sequence from sahaj_app;
revoke all on ipd.admission, ipd.deposit, ipd.ip_sequence from public;

grant select on ipd.admission to sahaj_app;
grant insert (patient_id, ip_number, bed_id, doctor_membership_id, admission_type, source, payer, reason,
              consent_signed, status, admitted_by) on ipd.admission to sahaj_app;
grant update (status, discharged_at, version) on ipd.admission to sahaj_app;

grant select on ipd.deposit to sahaj_app;
grant insert (admission_id, amount_paise, mode, collected_by) on ipd.deposit to sahaj_app;

grant select, insert, update on ipd.ip_sequence to sahaj_app;

create trigger trg_deposit_no_update_delete
  before update or delete on ipd.deposit
  for each row execute function platform.deny_audit_mutation();
create trigger trg_deposit_no_truncate
  before truncate on ipd.deposit
  for each statement execute function platform.deny_audit_mutation();

comment on table ipd.admission is 'IPD admission (SAH-23). Never deleted; discharge is a status. Reason is clinical text: never logged.';
comment on table ipd.deposit is 'IPD deposit received (SAH-23). Append-only: no update, delete or truncate (privilege and trigger).';
comment on table ipd.ip_sequence is 'Running IP number per clinic and financial year (SAH-23); gaps allowed, duplicates never.';
