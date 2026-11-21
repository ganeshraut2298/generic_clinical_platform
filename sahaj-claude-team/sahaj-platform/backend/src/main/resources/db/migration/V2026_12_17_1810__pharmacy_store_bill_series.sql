-- SAH-80 WP-I (story 80-2, 80-5), migration I2. ADR-030 S3 "Series per store" and "Data needed" (WP-I).
-- New objects only: tables pharmacy.store_bill_series and pharmacy.store_bill_counter, function pharmacy.next_store_bill_no(uuid) and the
-- guard pharmacy.guard_store_bill_counter_update(). SAH-78's document_series and document_counter are not touched; a store without its own
-- series uses the unit's BILL series through next_document_no('BILL').
-- Prefix uniqueness across the unit series and every store series is a service check under the lock of the unit's BILL document_series
-- row (ADR-030 S3). The database only keeps the store series apart from each other (uq_store_bill_series_prefix).
-- The series lock of DEC-078 item 4 ("a number of this financial year was issued") is a service check on store_bill_counter.last_issued_at.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.store_bill_series: one optional series per store. Lazy like document_series: no row means "use the unit's BILL series".
-- ======================================================================================================================
create table if not exists pharmacy.store_bill_series (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null default platform.current_tenant()
                         constraint fk_store_bill_series_tenant references platform.tenant (id),
  unit_id              uuid not null,
  store_id             uuid not null,
  prefix               text not null
                         constraint ck_store_bill_series_prefix check (prefix ~ '^[A-Za-z0-9/-]{1,6}$'),
  financial_year_part  boolean not null default false,
  sequence_length      integer not null
                         constraint ck_store_bill_series_sequence_length check (sequence_length between 4 and 8),
  restarts_each_fy     boolean not null default false,
  created_at           timestamptz not null default now(),
  created_by           uuid not null,
  updated_at           timestamptz,
  updated_by           uuid,
  version              bigint not null default 0,
  constraint uq_store_bill_series_store unique (tenant_id, store_id),
  constraint uq_store_bill_series_tenant_id unique (tenant_id, id),
  constraint fk_store_bill_series_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_store_bill_series_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint ck_store_bill_series_restart_needs_fy check (not restarts_each_fy or financial_year_part),
  constraint ck_store_bill_series_rendered_length check (
    char_length(prefix) + case when financial_year_part then 6 else 0 end + sequence_length <= 16)
);

-- No two store series of a unit share a prefix, ignoring case. Also the lookup "all series of the unit" for the service prefix check.
create unique index if not exists uq_store_bill_series_prefix on pharmacy.store_bill_series (tenant_id, unit_id, lower(prefix));

alter table pharmacy.store_bill_series enable row level security;
alter table pharmacy.store_bill_series force row level security;
create policy p_store_bill_series_select on pharmacy.store_bill_series for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_store_bill_series_insert on pharmacy.store_bill_series for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_store_bill_series_update on pharmacy.store_bill_series for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.store_bill_series from sahaj_app;
revoke all on pharmacy.store_bill_series from public;
grant select, insert, update on pharmacy.store_bill_series to sahaj_app;

comment on table pharmacy.store_bill_series is
  'Own bill number series of a store (SAH-80-2, ADR-030 S3). Optional; a store without a row uses the unit BILL series. Kept when the store is switched off so a prefix is never reused. Never deleted. At most one row per store.';

-- ======================================================================================================================
-- 2. pharmacy.store_bill_counter: next number per store and financial year. Written only by next_store_bill_no().
--    fiscal_year is the calendar year in which the April-March year starts, or 0 when the series never restarts.
-- ======================================================================================================================
create table if not exists pharmacy.store_bill_counter (
  tenant_id        uuid not null default platform.current_tenant()
                     constraint fk_store_bill_counter_tenant references platform.tenant (id),
  unit_id          uuid not null,
  store_id         uuid not null,
  fiscal_year      integer not null
                     constraint ck_store_bill_counter_fiscal_year check (fiscal_year = 0 or fiscal_year between 2000 and 2999),
  next_value       bigint not null
                     constraint ck_store_bill_counter_next_value check (next_value >= 2),
  updated_at       timestamptz not null default now(),
  last_issued_at   timestamptz,
  constraint pk_store_bill_counter primary key (tenant_id, unit_id, store_id, fiscal_year),
  constraint fk_store_bill_counter_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_store_bill_counter_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id)
);

alter table pharmacy.store_bill_counter enable row level security;
alter table pharmacy.store_bill_counter force row level security;
create policy p_store_bill_counter_select on pharmacy.store_bill_counter for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_store_bill_counter_insert on pharmacy.store_bill_counter for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_store_bill_counter_update on pharmacy.store_bill_counter for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
-- No delete: a counter row is kept so a number is never reused.
revoke all on pharmacy.store_bill_counter from sahaj_app;
revoke all on pharmacy.store_bill_counter from public;
grant select, insert, update on pharmacy.store_bill_counter to sahaj_app;

-- Backstop as for document_counter (1240): the key cannot change and next_value only goes up.
create or replace function pharmacy.guard_store_bill_counter_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if new.tenant_id is distinct from old.tenant_id
     or new.unit_id is distinct from old.unit_id
     or new.store_id is distinct from old.store_id
     or new.fiscal_year is distinct from old.fiscal_year then
    raise exception 'store bill counter key cannot change' using errcode = 'check_violation';
  end if;
  if new.next_value <= old.next_value then
    raise exception 'store bill counter can only go up' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_store_bill_counter_update() from public;
revoke all on function pharmacy.guard_store_bill_counter_update() from sahaj_app;

drop trigger if exists trg_store_bill_counter_guard_update on pharmacy.store_bill_counter;
create trigger trg_store_bill_counter_guard_update before update on pharmacy.store_bill_counter
  for each row execute function pharmacy.guard_store_bill_counter_update();

comment on table pharmacy.store_bill_counter is
  'Next bill number per store and financial year (SAH-80-2, ADR-030 S3). Written only by pharmacy.next_store_bill_no(). Never deleted. A handful of rows per store per year.';
comment on function pharmacy.guard_store_bill_counter_update() is
  'Backstop for pharmacy.store_bill_counter: the key (tenant, unit, store, fiscal_year) cannot change and next_value must strictly increase. SQLSTATE 23514.';

-- ======================================================================================================================
-- 3. pharmacy.next_store_bill_no(store_id): same contract as next_document_no. SECURITY INVOKER (default), so it runs as sahaj_app under
--    the row level security above. Call it last in the sale transaction, after the balance locks (ADR-030 S3).
--    SQLSTATE 22023 for a null store, 42501 when no tenant is bound, P0002 when the tenant has no unit or the store has no own series.
--    The store itself is checked by the foreign key of the counter and the row level security of the series (a store of another tenant
--    has no series visible here).
-- ======================================================================================================================
create or replace function pharmacy.next_store_bill_no(p_store_id uuid)
returns bigint
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_tenant    uuid := platform.current_tenant();
  v_unit      uuid;
  v_restarts  boolean;
  v_ist       timestamp := (pg_catalog.now() at time zone 'Asia/Kolkata');
  v_fy        integer;
  v_no        bigint;
begin
  if p_store_id is null then
    raise exception 'store is required' using errcode = 'invalid_parameter_value';
  end if;
  if v_tenant is null then
    raise exception 'no tenant bound' using errcode = 'insufficient_privilege';
  end if;
  -- FOR SHARE on the unit row, as next_document_no (L11): a series save holds it FOR UPDATE.
  select u.id into v_unit from pharmacy.unit u where u.tenant_id = v_tenant for share;
  if v_unit is null then
    raise exception 'pharmacy unit is not set up for this tenant' using errcode = 'no_data_found';
  end if;
  select s.restarts_each_fy into v_restarts
    from pharmacy.store_bill_series s
   where s.tenant_id = v_tenant and s.unit_id = v_unit and s.store_id = p_store_id;
  if not found then
    raise exception 'store has no own bill series' using errcode = 'no_data_found';
  end if;
  if v_restarts then
    v_fy := pg_catalog.date_part('year', v_ist)::integer - case when pg_catalog.date_part('month', v_ist) < 4 then 1 else 0 end;
  else
    v_fy := 0;
  end if;
  insert into pharmacy.store_bill_counter as c (tenant_id, unit_id, store_id, fiscal_year, next_value, updated_at, last_issued_at)
  values (v_tenant, v_unit, p_store_id, v_fy, 2, pg_catalog.now(), pg_catalog.now())
  on conflict (tenant_id, unit_id, store_id, fiscal_year) do update
    set next_value = c.next_value + 1,
        updated_at = pg_catalog.now(),
        last_issued_at = pg_catalog.now()
  returning c.next_value - 1 into v_no;
  return v_no;
end;
$$;
revoke all on function pharmacy.next_store_bill_no(uuid) from public;
grant execute on function pharmacy.next_store_bill_no(uuid) to sahaj_app;

comment on function pharmacy.next_store_bill_no(uuid) is
  'Next bill sequence number of a store that has its own series (SAH-80, ADR-030 S3), for the current April-March financial year (IST) or 0 when the series never restarts. Returns the number, not the rendered text. Raises P0002 when the store has no own series: the caller then uses next_document_no(''BILL''). Call last in the sale transaction, after the balance locks; the counter row lock is held until commit, a rollback gives the number back (gapless).';
