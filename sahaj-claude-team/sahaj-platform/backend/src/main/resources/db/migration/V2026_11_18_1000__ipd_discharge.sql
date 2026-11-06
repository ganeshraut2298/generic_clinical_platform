-- SAH-27 · IPD discharge, final bill, Discharge setup. Forward-only.
-- Money is integer paise. Same-schema FKs are composite (tenant_id, id). New tables are append-only like
-- ipd.deposit / ipd.admission_charge / ipd.bed_transfer. The opd.follow_up change is in the next file (opd owns it).

-- 1. Discharge setup on ipd.config (lazy row; defaults apply to existing rows).
-- ipd.config already has table-level select, insert, update for sahaj_app (SAH-22): the new columns are covered.
alter table ipd.config
  add column if not exists discharge_template_key text
    constraint ck_ipd_config_discharge_template check (discharge_template_key in ('general', 'surgical', 'maternity', 'daycare')),
  add column if not exists discharge_chain boolean not null default true,
  add column if not exists followup_days smallint not null default 7
    constraint ck_ipd_config_followup_days check (followup_days between 1 and 30);

-- 2. ipd.discharge_signoff (append-only): the doctor's "summary checked and signed" tick.
create table if not exists ipd.discharge_signoff (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null default platform.current_tenant()
                 constraint fk_discharge_signoff_tenant references platform.tenant (id),
  admission_id   uuid not null,
  discharge_type text not null
                 constraint ck_discharge_signoff_type check (discharge_type in ('normal', 'lama', 'referred', 'death')),
  template_key   text not null
                 constraint ck_discharge_signoff_template check (template_key in ('general', 'surgical', 'maternity', 'daycare')),
  signed_by      uuid not null,
  signed_at      timestamptz not null default now(),
  constraint uq_discharge_signoff_tenant_id unique (tenant_id, id),
  -- lets ipd.discharge prove the sign-off belongs to the same admission
  constraint uq_discharge_signoff_tenant_id_admission unique (tenant_id, id, admission_id),
  constraint fk_discharge_signoff_admission foreign key (tenant_id, admission_id) references ipd.admission (tenant_id, id)
);
-- Latest sign-off of an admission (sheet, discharge check, batched load for the list).
create index if not exists ix_discharge_signoff_tenant_admission
  on ipd.discharge_signoff (tenant_id, admission_id, signed_at desc);
-- "Going home today": sign-offs of the IST day.
create index if not exists ix_discharge_signoff_tenant_signed_at
  on ipd.discharge_signoff (tenant_id, signed_at);

-- 3. ipd.discharge (insert only): what the discharge recorded. One per admission.
create table if not exists ipd.discharge (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null default platform.current_tenant()
                 constraint fk_discharge_tenant references platform.tenant (id),
  admission_id   uuid not null,
  signoff_id     uuid not null,
  discharge_type text not null
                 constraint ck_discharge_type check (discharge_type in ('normal', 'lama', 'referred', 'death')),
  template_key   text not null
                 constraint ck_discharge_template check (template_key in ('general', 'surgical', 'maternity', 'daycare')),
  signed_by      uuid not null,
  signed_at      timestamptz not null,
  followup_days  smallint not null
                 constraint ck_discharge_followup_days check (followup_days between 1 and 30),
  followup_due   date,
  -- opaque id of the opd.follow_up row (no FK across modules); kept for tracing
  followup_id    uuid,
  completed_by   uuid not null,
  completed_at   timestamptz not null default now(),
  constraint uq_discharge_tenant_admission unique (tenant_id, admission_id),
  constraint fk_discharge_admission foreign key (tenant_id, admission_id) references ipd.admission (tenant_id, id),
  constraint fk_discharge_signoff foreign key (tenant_id, signoff_id, admission_id)
    references ipd.discharge_signoff (tenant_id, id, admission_id),
  constraint ck_discharge_followup_due check ((discharge_type = 'death') = (followup_due is null)),
  constraint ck_discharge_followup_id check (discharge_type <> 'death' or followup_id is null)
);
-- Discharged today (tile) and discharges in a date range.
create index if not exists ix_discharge_tenant_completed_at on ipd.discharge (tenant_id, completed_at);

-- 4. ipd.final_bill (append-only): the bill frozen at discharge with its settlement. One per admission.
create table if not exists ipd.final_bill (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null default platform.current_tenant()
                 constraint fk_final_bill_tenant references platform.tenant (id),
  admission_id   uuid not null,
  total_paise    bigint not null
                 constraint ck_final_bill_total check (total_paise >= 0),
  deposits_paise bigint not null
                 constraint ck_final_bill_deposits check (deposits_paise >= 0),
  -- positive: collected from the family; negative: refunded; 0: settled
  balance_paise  bigint not null,
  settle_mode    text
                 constraint ck_final_bill_settle_mode check (settle_mode in ('Cash', 'UPI', 'Card')),
  created_by     uuid not null,
  created_at     timestamptz not null default now(),
  bill_days      integer not null
                 constraint ck_final_bill_bill_days check (bill_days >= 1),
  bed_day_cut    text not null
                 constraint ck_final_bill_bed_day_cut check (bed_day_cut in ('midnight', '24h')),
  category_key   text not null
                 constraint ck_final_bill_category check (category_key in ('gen', 'semi', 'pvt', 'icu')),
  multiplier     numeric(4,2) not null
                 constraint ck_final_bill_multiplier check (multiplier between 0.5 and 5),
  constraint uq_final_bill_tenant_id unique (tenant_id, id),
  constraint uq_final_bill_tenant_admission unique (tenant_id, admission_id),
  constraint fk_final_bill_admission foreign key (tenant_id, admission_id) references ipd.admission (tenant_id, id),
  constraint ck_final_bill_balance check (balance_paise = total_paise - deposits_paise),
  constraint ck_final_bill_settle check ((balance_paise = 0) = (settle_mode is null))
);
-- 5. ipd.final_bill_line (append-only): the bill lines as they were at discharge.
create table if not exists ipd.final_bill_line (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null default platform.current_tenant()
                 constraint fk_final_bill_line_tenant references platform.tenant (id),
  final_bill_id  uuid not null,
  line_no        integer not null
                 constraint ck_final_bill_line_no check (line_no >= 1),
  label          text not null
                 constraint ck_final_bill_line_label check (char_length(label) between 1 and 200),
  qty            integer not null
                 constraint ck_final_bill_line_qty check (qty >= 0),
  unit_paise     bigint not null
                 constraint ck_final_bill_line_unit check (unit_paise >= 0),
  amount_paise   bigint not null,
  segment        boolean not null default false,
  constraint uq_final_bill_line_no unique (tenant_id, final_bill_id, line_no),
  constraint fk_final_bill_line_bill foreign key (tenant_id, final_bill_id) references ipd.final_bill (tenant_id, id),
  constraint ck_final_bill_line_amount check (amount_paise = unit_paise * qty)
);

-- 6. RLS: select and insert policies only (no update or delete policy).
alter table ipd.discharge_signoff enable row level security;
alter table ipd.discharge_signoff force row level security;
alter table ipd.discharge enable row level security;
alter table ipd.discharge force row level security;
alter table ipd.final_bill enable row level security;
alter table ipd.final_bill force row level security;
alter table ipd.final_bill_line enable row level security;
alter table ipd.final_bill_line force row level security;

create policy p_discharge_signoff_select on ipd.discharge_signoff
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_discharge_signoff_insert on ipd.discharge_signoff
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_discharge_select on ipd.discharge
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_discharge_insert on ipd.discharge
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_final_bill_select on ipd.final_bill
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_final_bill_insert on ipd.final_bill
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_final_bill_line_select on ipd.final_bill_line
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_final_bill_line_insert on ipd.final_bill_line
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());

-- 7. Grants: select, plus column-level insert so id, tenant_id and defaulted timestamps keep their defaults.
revoke all on ipd.discharge_signoff, ipd.discharge, ipd.final_bill, ipd.final_bill_line from sahaj_app;
revoke all on ipd.discharge_signoff, ipd.discharge, ipd.final_bill, ipd.final_bill_line from public;

grant select on ipd.discharge_signoff, ipd.discharge, ipd.final_bill, ipd.final_bill_line to sahaj_app;
grant insert (admission_id, discharge_type, template_key, signed_by) on ipd.discharge_signoff to sahaj_app;
grant insert (admission_id, signoff_id, discharge_type, template_key, signed_by, signed_at, followup_days,
              followup_due, followup_id, completed_by) on ipd.discharge to sahaj_app;
grant insert (admission_id, total_paise, deposits_paise, balance_paise, settle_mode, created_by, bill_days,
              bed_day_cut, category_key, multiplier) on ipd.final_bill to sahaj_app;
grant insert (final_bill_id, line_no, label, qty, unit_paise, amount_paise, segment) on ipd.final_bill_line to sahaj_app;

-- 8. Append-only triggers (update, delete, truncate denied even for a role that gets a privilege later).
create trigger trg_discharge_signoff_no_update_delete
  before update or delete on ipd.discharge_signoff
  for each row execute function platform.deny_audit_mutation();
create trigger trg_discharge_signoff_no_truncate
  before truncate on ipd.discharge_signoff
  for each statement execute function platform.deny_audit_mutation();
create trigger trg_discharge_no_update_delete
  before update or delete on ipd.discharge
  for each row execute function platform.deny_audit_mutation();
create trigger trg_discharge_no_truncate
  before truncate on ipd.discharge
  for each statement execute function platform.deny_audit_mutation();
create trigger trg_final_bill_no_update_delete
  before update or delete on ipd.final_bill
  for each row execute function platform.deny_audit_mutation();
create trigger trg_final_bill_no_truncate
  before truncate on ipd.final_bill
  for each statement execute function platform.deny_audit_mutation();
create trigger trg_final_bill_line_no_update_delete
  before update or delete on ipd.final_bill_line
  for each row execute function platform.deny_audit_mutation();
create trigger trg_final_bill_line_no_truncate
  before truncate on ipd.final_bill_line
  for each statement execute function platform.deny_audit_mutation();

-- Grants on existing tables: none needed. sahaj_app already holds update (status, discharged_at, version) on
-- ipd.admission (SAH-23) and table-level select, insert, update, delete on ipd.bed (SAH-22), which cover
-- ADMITTED -> DISCHARGED and bed occ -> clean.

comment on table ipd.discharge_signoff is 'IPD discharge summary sign-off (SAH-27). Append-only: no update, delete or truncate (privilege and trigger). The latest row of an admission applies to a discharge only when type and template match.';
comment on table ipd.discharge is 'IPD discharge record (SAH-27). Insert only, one per admission. followup_due and followup_id are null for death. followup_id is an opaque opd.follow_up id (no FK).';
comment on table ipd.final_bill is 'IPD final bill frozen at discharge, with its settlement (SAH-27). Append-only, one per admission, never recomputed. balance_paise: positive collected, negative refunded, 0 settled. Billing data: never deleted.';
comment on table ipd.final_bill_line is 'Lines of ipd.final_bill (SAH-27), in bill order. Append-only. segment = line of a bed the patient was moved away from (SAH-26).';
comment on column ipd.config.discharge_template_key is 'SAH-27: summary template chosen in Discharge setup; null = none chosen.';
comment on column ipd.config.discharge_chain is 'SAH-27: true = the doctor signs the summary before the discharge can be completed.';
comment on column ipd.config.followup_days is 'SAH-27: days after discharge for the OPD follow-up (1 to 30).';
