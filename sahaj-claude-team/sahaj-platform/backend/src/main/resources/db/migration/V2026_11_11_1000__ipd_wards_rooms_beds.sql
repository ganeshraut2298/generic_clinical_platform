-- SAH-22 · IPD setup: wards, rooms, beds, bed categories, config.
-- Same-schema FKs are composite (tenant_id, id) so a row can never point at another tenant's parent.

create table if not exists ipd.ward (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid not null default platform.current_tenant()
             constraint fk_ward_tenant references platform.tenant (id),
  name       text not null
             constraint ck_ward_name check (char_length(name) between 1 and 80),
  station    text not null default 'Nursing station 1'
             constraint ck_ward_station check (char_length(station) between 1 and 60),
  created_at timestamptz not null default now(),
  created_by uuid not null,
  constraint uq_ward_tenant_id unique (tenant_id, id)
);
create unique index if not exists ux_ward_tenant_lower_name on ipd.ward (tenant_id, lower(name));

create table if not exists ipd.room (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid not null default platform.current_tenant()
             constraint fk_room_tenant references platform.tenant (id),
  ward_id    uuid not null,
  name       text not null
             constraint ck_room_name check (char_length(name) between 1 and 40),
  created_at timestamptz not null default now(),
  created_by uuid not null,
  constraint uq_room_tenant_id unique (tenant_id, id),
  constraint fk_room_ward foreign key (tenant_id, ward_id) references ipd.ward (tenant_id, id)
);
-- Rooms of a ward (layout, next room number).
create index if not exists ix_room_tenant_ward on ipd.room (tenant_id, ward_id);

create table if not exists ipd.bed (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null default platform.current_tenant()
               constraint fk_bed_tenant references platform.tenant (id),
  ward_id      uuid not null,
  room_id      uuid,
  code         text not null
               constraint ck_bed_code check (char_length(code) between 1 and 20),
  category_key text not null default 'gen'
               constraint ck_bed_category check (category_key in ('gen', 'semi', 'pvt', 'icu')),
  status       text not null default 'free'
               constraint ck_bed_status check (status in ('free', 'occ', 'clean', 'blk', 'res')),
  created_at   timestamptz not null default now(),
  created_by   uuid not null,
  version      bigint not null default 0,
  constraint uq_bed_tenant_code unique (tenant_id, code),
  constraint fk_bed_ward foreign key (tenant_id, ward_id) references ipd.ward (tenant_id, id),
  -- room_id null: MATCH SIMPLE skips the check
  constraint fk_bed_room foreign key (tenant_id, room_id) references ipd.room (tenant_id, id)
);
-- Beds of a ward and of a room; bed count per category on the charges page.
create index if not exists ix_bed_tenant_ward on ipd.bed (tenant_id, ward_id);
create index if not exists ix_bed_tenant_room on ipd.bed (tenant_id, room_id) where room_id is not null;
create index if not exists ix_bed_tenant_category on ipd.bed (tenant_id, category_key);

create table if not exists ipd.bed_category (
  tenant_id    uuid not null default platform.current_tenant()
               constraint fk_bed_category_tenant references platform.tenant (id),
  key          text not null
               constraint ck_bed_category_key check (key in ('gen', 'semi', 'pvt', 'icu')),
  rate_paise   bigint not null
               constraint ck_bed_category_rate check (rate_paise >= 0),
  multiplier   numeric(4,2) not null
               constraint ck_bed_category_multiplier check (multiplier between 0.5 and 5),
  updated_at   timestamptz,
  updated_by   uuid,
  primary key (tenant_id, key)
);

create table if not exists ipd.config (
  tenant_id     uuid primary key default platform.current_tenant()
                constraint fk_ipd_config_tenant references platform.tenant (id),
  room_level    boolean not null default true,
  bed_day_cut   text not null default 'midnight'
                constraint ck_ipd_config_bed_day_cut check (bed_day_cut in ('midnight', '24h')),
  nursing_paise bigint not null default 30000
                constraint ck_ipd_config_nursing check (nursing_paise >= 0),
  visit_paise   bigint not null default 50000
                constraint ck_ipd_config_visit check (visit_paise >= 0),
  updated_at    timestamptz,
  updated_by    uuid
);

alter table ipd.ward enable row level security;
alter table ipd.ward force row level security;
alter table ipd.room enable row level security;
alter table ipd.room force row level security;
alter table ipd.bed enable row level security;
alter table ipd.bed force row level security;
alter table ipd.bed_category enable row level security;
alter table ipd.bed_category force row level security;
alter table ipd.config enable row level security;
alter table ipd.config force row level security;

create policy p_ward_tenant on ipd.ward
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());
create policy p_room_tenant on ipd.room
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());
create policy p_bed_tenant on ipd.bed
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());
create policy p_bed_category_tenant on ipd.bed_category
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());
create policy p_ipd_config_tenant on ipd.config
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

revoke all on ipd.ward, ipd.room, ipd.bed, ipd.bed_category, ipd.config from sahaj_app;
revoke all on ipd.ward, ipd.room, ipd.bed, ipd.bed_category, ipd.config from public;
grant select, insert, update on ipd.ward, ipd.room, ipd.bed_category, ipd.config to sahaj_app;
-- Real delete of a bed only while beds carry no history (SAH-22); later stories replace it by a guard.
grant select, insert, update, delete on ipd.bed to sahaj_app;

comment on table ipd.ward is 'IPD ward (SAH-22). Never deleted or renamed in phase 1.';
comment on table ipd.room is 'IPD room inside a ward (SAH-22). Never deleted.';
comment on table ipd.bed is 'IPD bed (SAH-22). Status free only until SAH-23; delete allowed only while no history.';
comment on table ipd.bed_category is 'Per-tenant bed charge and service-rate multiplier (SAH-22); lazy, absent rows use defaults.';
comment on table ipd.config is 'Per-tenant IPD settings (room level, bed-day cut, nursing and visit charges) (SAH-22); lazy.';
