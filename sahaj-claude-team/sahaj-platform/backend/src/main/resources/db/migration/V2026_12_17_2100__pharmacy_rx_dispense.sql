-- SAH-80 WP-K (stories 80-11, 80-12), migration K1. ADR-030 S14 "OPD dispense state" (OQ-80-24, OQ-80-29), "Data needed" (WP-K).
-- New tables: pharmacy.rx_dispense (one row per store and OPD record, with the token of the IST day and the claim) and
-- pharmacy.rx_dispense_line (typed quantity, picked, supplied, remaining, closed reason); guards pharmacy.guard_rx_dispense_write() and
-- pharmacy.guard_rx_dispense_line_write(). Tables only: the OPD port (OpdPrescriptionSource) is a no-op bean; nothing in the opd schema is
-- touched, and rx_record_id and source ids are opaque uuids (no cross-module foreign key). "Dispensed" is pharmacy's own state (OQ-80-29).
-- The queue read creates the row and the token for a newly seen record (the only write of that read). The token is taken by the service
-- under the store row lock (max token of the day + 1); unique (tenant, store, token_day, token) is the backstop.
-- Patient data: patient_id (an id) only. No name is stored here; the name is read through patient.api and never logged.
-- Tenant tables, forced row level security, no delete grant. Never deleted.

set local lock_timeout = '30s';

create table if not exists pharmacy.rx_dispense (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null default platform.current_tenant()
                         constraint fk_rx_dispense_tenant references platform.tenant (id),
  unit_id              uuid not null,
  store_id             uuid not null,
  rx_record_id         uuid not null,
  token_day            date not null,
  token                integer not null
                         constraint ck_rx_dispense_token check (token >= 1),
  status               text not null default 'NEW'
                         constraint ck_rx_dispense_status check (status in
                           ('NEW', 'PICKING', 'PICKED', 'SUBSTITUTE_OK', 'NEEDS_ACTION', 'PART_SUPPLIED', 'HANDED_OVER', 'CLOSED')),
  claimed_by           uuid,
  claimed_at           timestamptz,
  patient_id           uuid,
  created_at           timestamptz not null default now(),
  created_by           uuid not null,
  updated_at           timestamptz,
  updated_by           uuid,
  version              bigint not null default 0,
  constraint uq_rx_dispense_tenant_id unique (tenant_id, id),
  constraint uq_rx_dispense_record unique (tenant_id, store_id, rx_record_id),
  constraint uq_rx_dispense_token unique (tenant_id, store_id, token_day, token),
  constraint fk_rx_dispense_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_rx_dispense_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint ck_rx_dispense_claim_pair check ((claimed_by is null) = (claimed_at is null))
);

-- The queue of a store for an IST day is served by uq_rx_dispense_token (tenant_id, store_id, token_day, token).
-- Open prescriptions (not handed over or closed), and the mode guard "Part supplied items exist".
create index if not exists ix_rx_dispense_open on pharmacy.rx_dispense (tenant_id, store_id, status)
  where status not in ('HANDED_OVER', 'CLOSED');

alter table pharmacy.rx_dispense enable row level security;
alter table pharmacy.rx_dispense force row level security;
create policy p_rx_dispense_select on pharmacy.rx_dispense for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_rx_dispense_insert on pharmacy.rx_dispense for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_rx_dispense_update on pharmacy.rx_dispense for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.rx_dispense from sahaj_app;
revoke all on pharmacy.rx_dispense from public;
grant select, insert, update on pharmacy.rx_dispense to sahaj_app;

-- Guard: identity (tenant, unit, store, record, token day, token, creator) never changes; HANDED_OVER and CLOSED are final.
create or replace function pharmacy.guard_rx_dispense_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'UPDATE' then
    if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
       or new.store_id is distinct from old.store_id or new.rx_record_id is distinct from old.rx_record_id
       or new.token_day is distinct from old.token_day or new.token is distinct from old.token
       or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
      raise exception 'prescription dispense identity cannot change' using errcode = 'check_violation';
    end if;
    if old.status in ('HANDED_OVER', 'CLOSED') then
      raise exception 'prescription dispense is final (%)', old.status using errcode = 'check_violation';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_rx_dispense_write() from public;
revoke all on function pharmacy.guard_rx_dispense_write() from sahaj_app;

create trigger trg_rx_dispense_guard_write before insert or update on pharmacy.rx_dispense
  for each row execute function pharmacy.guard_rx_dispense_write();

comment on table pharmacy.rx_dispense is
  'Pharmacy-owned dispense state of one signed OPD prescription at one store (SAH-80-11, ADR-030 S14): token of the IST day, status, claim. Created by the queue read for a newly seen record. Never deleted. Holds the patient id only, no name.';
comment on column pharmacy.rx_dispense.rx_record_id is
  'Opaque id of the signed OPD record (no foreign key, another module).';
comment on function pharmacy.guard_rx_dispense_write() is
  'Dispense row guard: tenant, unit, store, record, token day, token and creation stamp never change; HANDED_OVER and CLOSED are final. SQLSTATE 23514.';

create table if not exists pharmacy.rx_dispense_line (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null default platform.current_tenant()
                         constraint fk_rx_dispense_line_tenant references platform.tenant (id),
  unit_id              uuid not null,
  rx_dispense_id       uuid not null,
  -- The line of the prescription (RxLine.lineKey) and, when the doctor chose from the OPD medicine master, its id (opaque).
  line_key             varchar(40) not null
                         constraint ck_rx_dispense_line_key check (char_length(btrim(line_key)) between 1 and 40),
  source_item_id       uuid,
  -- The quantity the pharmacist typed for the prescribed duration; the prescription has none.
  qty                  integer
                         constraint ck_rx_dispense_line_qty check (qty between 1 and 999999),
  picked_by            uuid,
  picked_at            timestamptz,
  supplied_qty         integer not null default 0
                         constraint ck_rx_dispense_line_supplied check (supplied_qty >= 0),
  remaining_qty        integer
                         constraint ck_rx_dispense_line_remaining check (remaining_qty >= 0),
  closed_reason_key    text
                         constraint ck_rx_dispense_line_closed_reason check (closed_reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  created_at           timestamptz not null default now(),
  created_by           uuid not null,
  updated_at           timestamptz,
  updated_by           uuid,
  version              bigint not null default 0,
  constraint uq_rx_dispense_line_key unique (tenant_id, rx_dispense_id, line_key),
  constraint fk_rx_dispense_line_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_rx_dispense_line_dispense foreign key (tenant_id, rx_dispense_id) references pharmacy.rx_dispense (tenant_id, id),
  constraint ck_rx_dispense_line_picked_pair check ((picked_by is null) = (picked_at is null)),
  constraint ck_rx_dispense_line_supplied_within_qty check (qty is null or supplied_qty <= qty)
);
-- The lines of a prescription are served by uq_rx_dispense_line_key (tenant_id, rx_dispense_id, line_key).

alter table pharmacy.rx_dispense_line enable row level security;
alter table pharmacy.rx_dispense_line force row level security;
create policy p_rx_dispense_line_select on pharmacy.rx_dispense_line for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_rx_dispense_line_insert on pharmacy.rx_dispense_line for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_rx_dispense_line_update on pharmacy.rx_dispense_line for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.rx_dispense_line from sahaj_app;
revoke all on pharmacy.rx_dispense_line from public;
grant select, insert, update on pharmacy.rx_dispense_line to sahaj_app;

-- Guard: identity never changes. The supplied quantity may go down (a cancelled bill gives the quantity back; the service decides).
create or replace function pharmacy.guard_rx_dispense_line_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'UPDATE' then
    if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
       or new.rx_dispense_id is distinct from old.rx_dispense_id or new.line_key is distinct from old.line_key
       or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
      raise exception 'prescription dispense line identity cannot change' using errcode = 'check_violation';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_rx_dispense_line_write() from public;
revoke all on function pharmacy.guard_rx_dispense_line_write() from sahaj_app;

create trigger trg_rx_dispense_line_guard_write before insert or update on pharmacy.rx_dispense_line
  for each row execute function pharmacy.guard_rx_dispense_line_write();

comment on table pharmacy.rx_dispense_line is
  'Dispense state of one prescription line (SAH-80-11, 80-12): typed quantity, picked, supplied, remaining, closed reason (list rx-closed). Never deleted. No names, no medicine text (the line key and the opaque source id only).';
comment on function pharmacy.guard_rx_dispense_line_write() is
  'Dispense line guard: tenant, unit, prescription, line key and creation stamp never change. SQLSTATE 23514.';
