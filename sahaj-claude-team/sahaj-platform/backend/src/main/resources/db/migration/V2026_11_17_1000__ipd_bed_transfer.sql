-- SAH-26 · IPD bed transfer and bed transfer rules. Forward-only.
-- Append-only transfer log like ipd.deposit / ipd.admission_charge. Same-schema FKs are composite (tenant_id, id).

-- 1. Transfer rules on ipd.config (lazy row; defaults apply to existing rows).
-- ipd.config already has table-level select, insert, update for sahaj_app (SAH-22), so the PUT /transfer-rules upsert
-- needs no new grant: the new columns are covered.
alter table ipd.config
  add column if not exists transfer_upgrade_owner_only boolean not null default true,
  add column if not exists new_rate_from text not null default 'same'
    constraint ck_ipd_config_new_rate_from check (new_rate_from in ('same', 'next'));

-- 2. ipd.bed_transfer (append-only)
create table if not exists ipd.bed_transfer (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null default platform.current_tenant()
                    constraint fk_bed_transfer_tenant references platform.tenant (id),
  admission_id      uuid not null,
  from_bed_id       uuid not null,
  to_bed_id         uuid not null,
  from_category_key text not null
                    constraint ck_bed_transfer_from_category check (from_category_key in ('gen', 'semi', 'pvt', 'icu')),
  to_category_key   text not null
                    constraint ck_bed_transfer_to_category check (to_category_key in ('gen', 'semi', 'pvt', 'icu')),
  segment_days      integer not null
                    constraint ck_bed_transfer_segment_days check (segment_days >= 0),
  new_rate_from     text not null
                    constraint ck_bed_transfer_new_rate_from check (new_rate_from in ('same', 'next')),
  reason            text
                    constraint ck_bed_transfer_reason check (reason is null or reason in (
                      'Needs closer monitoring', 'Moving to a step-down bed', 'Patient or family request', 'Isolation')),
  transferred_at    timestamptz not null default now(),
  transferred_by    uuid not null,
  constraint ck_bed_transfer_different_beds check (from_bed_id <> to_bed_id),
  constraint fk_bed_transfer_admission foreign key (tenant_id, admission_id) references ipd.admission (tenant_id, id),
  constraint fk_bed_transfer_from_bed foreign key (tenant_id, from_bed_id) references ipd.bed (tenant_id, id),
  constraint fk_bed_transfer_to_bed foreign key (tenant_id, to_bed_id) references ipd.bed (tenant_id, id)
);
-- Transfers of an admission in time order (running bill segments; batched load for the list, board and tiles).
create index if not exists ix_bed_transfer_tenant_admission
  on ipd.bed_transfer (tenant_id, admission_id, transferred_at);

alter table ipd.bed_transfer enable row level security;
alter table ipd.bed_transfer force row level security;

create policy p_bed_transfer_select on ipd.bed_transfer
  for select to sahaj_app
  using (tenant_id = platform.current_tenant());
create policy p_bed_transfer_insert on ipd.bed_transfer
  for insert to sahaj_app
  with check (tenant_id = platform.current_tenant());

revoke all on ipd.bed_transfer from sahaj_app;
revoke all on ipd.bed_transfer from public;
grant select on ipd.bed_transfer to sahaj_app;
-- id, tenant_id and transferred_at keep their defaults.
grant insert (admission_id, from_bed_id, to_bed_id, from_category_key, to_category_key, segment_days,
              new_rate_from, reason, transferred_by) on ipd.bed_transfer to sahaj_app;

create trigger trg_bed_transfer_no_update_delete
  before update or delete on ipd.bed_transfer
  for each row execute function platform.deny_audit_mutation();
create trigger trg_bed_transfer_no_truncate
  before truncate on ipd.bed_transfer
  for each statement execute function platform.deny_audit_mutation();

-- 3. Moving an admission to another bed. status, discharged_at and version were granted in SAH-23.
-- ipd.bed already has table-level select, insert, update, delete (SAH-22), so the old bed -> clean and the new
-- bed -> occ updates in the same transaction need no new grant.
grant update (bed_id) on ipd.admission to sahaj_app;

comment on table ipd.bed_transfer is 'IPD bed transfer (SAH-26). Append-only: no update, delete or truncate (privilege and trigger). segment_days is the bill days of the old category, fixed at transfer time. reason is optional free-list text: never logged.';
comment on column ipd.config.transfer_upgrade_owner_only is 'SAH-26: true = only the owner may move a patient to a higher bed category.';
comment on column ipd.config.new_rate_from is 'SAH-26: same = new bed rate from the transfer day; next = from the next day.';
