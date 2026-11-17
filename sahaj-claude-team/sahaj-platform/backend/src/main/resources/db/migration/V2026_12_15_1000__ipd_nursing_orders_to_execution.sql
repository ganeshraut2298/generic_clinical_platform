-- SAH-76 · IPD nursing integration, Sprints 1-2 (ADR-027 "Data needed" items 1 to 17, DEC-055). Forward-only, one migration.
-- Design note: docs/database/SAH-76.md. No earlier migration is edited.
--
-- What it does, in order:
--   A. global reference: ipd.drug gets time_critical, dose_units, route_key (demo content, OQ-76-8); new ipd.nursing_profile_template
--      (2 starter rows) and ipd.drug_code (shipped empty). Both new tables are immutable by trigger.
--   B. tenant configuration: seven ipd.config columns, new ipd.nursing_profile, ipd.ward.nursing_profile_key, ipd.vitals.due_interval_min.
--   C. orders: ipd.medicine_order widened (statuses QUERIED and ON_HOLD, current_version, priority, stop_reason), new
--      ipd.medicine_order_version (one row per version; every existing order gets version 1), ipd.order_hold, ipd.order_notice,
--      ipd.medicine_order_query, ipd.pharmacist_review.
--   D. doses: ipd.dose_administration widened (attempt, version, kind, scan method, override, before-verification, undo window),
--      new append-only ipd.dose_correction.
--
-- NO DATA IS LOST OR REWRITTEN. No existing tenant row is updated (no UPDATE of ipd.medicine_order, ipd.dose_administration,
-- ipd.vitals, ipd.ward or ipd.config). Every new column on a tenant table is nullable or has a constant default (stored without
-- a table rewrite). The only INSERT into a tenant table of rows derived from old rows is version 1 for every existing medicine
-- order (section C3). The only UPDATEs are on the 14 global ipd.drug rows (no tenant_id).
-- Nothing is dropped or renamed except the FIVE constraints that this design must replace, listed here so the reviewer can see
-- them. Each is dropped and re-added under the SAME NAME in this one transaction, each is only LOOSENED or WIDENED (every
-- existing row still satisfies the new form), and no data is touched:
--   1. ck_medicine_order_status            (adds QUERIED, ON_HOLD)
--   2. ck_medicine_order_fields_by_status  (ACTIVE may have no verifier when the version needs no verification)
--   3. ck_dose_administration_reason       (adds VOMITED, OTHER)
--   4. ck_dose_administration_identity_scan_by_outcome (the scan may be null for a manual override)
--   5. uq_dose_administration_order_slot   (becomes unique (tenant_id, order_id, slot_at, attempt): a new give after an error)
-- Also: ALTER COLUMN frequency_key DROP NOT NULL on ipd.medicine_order (a relaxation, see C1). The narrower unique index
-- ux_medicine_order_tenant_admission_drug_live and the pending-only queue index are KEPT (redundant, harmless); a later release
-- may drop them (expand and contract).

set local lock_timeout = '30s';

-- ======================================================================================================================
-- A. Global reference tables (no tenant_id)
-- ======================================================================================================================

-- A1. ipd.drug: time_critical, dose_units, route_key. 14 global rows; no tenant row is touched.
alter table ipd.drug
  add column if not exists time_critical boolean not null default false,
  add column if not exists dose_units text[] not null default '{}',
  add column if not exists route_key text;

-- Demo content for sign-off (OQ-76-8, Q3). Not clinically validated. Units and routes follow the lists in section A3 (checks below).
update ipd.drug set route_key = case route
    when 'Oral'        then 'ORAL'
    when 'IV'          then 'IV'
    when 'IV infusion' then 'IV_INFUSION'
    when 'IM'          then 'IM'
    when 'SC'          then 'SC'
  end
 where route_key is null;
update ipd.drug set dose_units = case key
    when 'pcm'   then array['TABLET', 'MG']
    when 'amox'  then array['TABLET', 'MG']
    when 'cotri' then array['TABLET', 'MG']
    when 'asp'   then array['TABLET', 'MG']
    when 'ibu'   then array['TABLET', 'MG']
    when 'met'   then array['TABLET', 'MG']
    when 'panto' then array['ML', 'MG']
    when 'ondan' then array['ML', 'MG']
    when 'ceft'  then array['ML', 'MG', 'G']
    when 'pip'   then array['ML', 'MG', 'G']
    when 'ns'    then array['ML']
    when 'kcl'   then array['ML']
    when 'hep'   then array['IU']
    when 'ins'   then array['UNIT']
    else '{}'::text[]
  end
 where cardinality(dose_units) = 0;

-- Fails loudly (route_key null) if a drug had a route the list does not know.
alter table ipd.drug alter column route_key set not null;
alter table ipd.drug
  add constraint ck_drug_route_key check (route_key in
    ('ORAL', 'IV', 'IV_INFUSION', 'IM', 'SC', 'SUBLINGUAL', 'INHALED', 'TOPICAL', 'RECTAL', 'NASOGASTRIC')),
  add constraint ck_drug_dose_units check (dose_units <@ array['TABLET', 'CAPSULE', 'MG', 'G', 'MCG', 'ML', 'IU', 'UNIT', 'MMOL']::text[]);

-- A2. ipd.nursing_profile_template: global starter ward profiles (ADR-027 D8). Data, not code.
create table if not exists ipd.nursing_profile_template (
  key                       text primary key
                            constraint ck_nursing_profile_template_key check (key ~ '^[a-z0-9-]{2,40}$'),
  name                      text not null
                            constraint uq_nursing_profile_template_name unique
                            constraint ck_nursing_profile_template_name check (char_length(name) between 1 and 60),
  med_verification_required boolean not null,
  vitals_every_min          integer not null
                            constraint ck_nursing_profile_template_vitals check (vitals_every_min between 15 and 1440),
  sort_order                smallint not null
                            constraint uq_nursing_profile_template_sort unique
                            constraint ck_nursing_profile_template_sort check (sort_order > 0)
);
insert into ipd.nursing_profile_template (key, name, med_verification_required, vitals_every_min, sort_order) values
  ('icu',          'ICU',          true,  60, 1),
  ('general-ward', 'General ward', true, 240, 2)
on conflict (key) do nothing;

-- A3. ipd.drug_code: medicine barcode / GTIN codes (ADR-027 D9). Shipped EMPTY; codes are loaded by a later reviewed migration.
create table if not exists ipd.drug_code (
  code      text primary key
            constraint ck_drug_code_code check (code ~ '^[A-Z0-9-]{4,64}$'),
  drug_key  text not null
            constraint fk_drug_code_drug references ipd.drug (key),
  source    text not null
            constraint ck_drug_code_source check (source ~ '^[A-Z_]{1,20}$')
);
create index if not exists ix_drug_code_drug on ipd.drug_code (drug_key);

alter table ipd.nursing_profile_template enable row level security;
alter table ipd.nursing_profile_template force row level security;
alter table ipd.drug_code enable row level security;
alter table ipd.drug_code force row level security;
create policy p_nursing_profile_template_select on ipd.nursing_profile_template for select to sahaj_app using (true);
create policy p_nursing_profile_template_migrator on ipd.nursing_profile_template for all to sahaj_migrator using (true) with check (true);
create policy p_drug_code_select on ipd.drug_code for select to sahaj_app using (true);
create policy p_drug_code_migrator on ipd.drug_code for all to sahaj_migrator using (true) with check (true);

revoke all on ipd.nursing_profile_template, ipd.drug_code from sahaj_app;
revoke all on ipd.nursing_profile_template, ipd.drug_code from public;
grant select on ipd.nursing_profile_template, ipd.drug_code to sahaj_app;

-- Immutable seed tables: insert allowed (a later migration adds rows), update, delete and truncate refused for every role.
create trigger trg_nursing_profile_template_no_update_delete
  before update or delete on ipd.nursing_profile_template
  for each row execute function platform.deny_audit_mutation();
create trigger trg_nursing_profile_template_no_truncate
  before truncate on ipd.nursing_profile_template
  for each statement execute function platform.deny_audit_mutation();
create trigger trg_drug_code_no_update_delete
  before update or delete on ipd.drug_code
  for each row execute function platform.deny_audit_mutation();
create trigger trg_drug_code_no_truncate
  before truncate on ipd.drug_code
  for each statement execute function platform.deny_audit_mutation();

-- ======================================================================================================================
-- B. Tenant configuration: clinic settings, ward profiles, vitals interval
-- ======================================================================================================================

-- B1. ipd.config (lazy row per clinic): the seven nursing settings. Table-level grants of the earlier migration cover new columns.
alter table ipd.config
  add column if not exists med_verification_required boolean not null default true,
  add column if not exists dose_window_before_min integer not null default 60
    constraint ck_ipd_config_dose_window_before check (dose_window_before_min between 1 and 180),
  add column if not exists dose_window_after_min integer not null default 60
    constraint ck_ipd_config_dose_window_after check (dose_window_after_min between 1 and 180),
  add column if not exists stat_window_min integer not null default 15
    constraint ck_ipd_config_stat_window check (stat_window_min between 1 and 120),
  add column if not exists unack_flag_min integer not null default 30
    constraint ck_ipd_config_unack_flag check (unack_flag_min between 1 and 240),
  add column if not exists unack_flag_stat_min integer not null default 5
    constraint ck_ipd_config_unack_flag_stat check (unack_flag_stat_min between 1 and 60),
  add column if not exists undo_seconds integer not null default 10
    constraint ck_ipd_config_undo_seconds check (undo_seconds between 1 and 10);

-- B2. ipd.nursing_profile: a clinic's ward profiles (tenant).
create table if not exists ipd.nursing_profile (
  id                        uuid primary key default gen_random_uuid(),
  tenant_id                 uuid not null default platform.current_tenant()
                            constraint fk_nursing_profile_tenant references platform.tenant (id),
  key                       text not null
                            constraint ck_nursing_profile_key check (key ~ '^[a-z0-9-]{2,40}$'),
  name                      text not null
                            constraint ck_nursing_profile_name check (char_length(name) between 1 and 60),
  med_verification_required boolean not null,
  vitals_every_min          integer not null
                            constraint ck_nursing_profile_vitals check (vitals_every_min between 15 and 1440),
  dose_window_before_min    integer
                            constraint ck_nursing_profile_window_before check (dose_window_before_min between 1 and 180),
  dose_window_after_min     integer
                            constraint ck_nursing_profile_window_after check (dose_window_after_min between 1 and 180),
  sort_order                smallint not null default 100
                            constraint ck_nursing_profile_sort check (sort_order > 0),
  created_at                timestamptz not null default now(),
  updated_at                timestamptz,
  updated_by                uuid,
  constraint uq_nursing_profile_tenant_key unique (tenant_id, key),
  constraint ck_nursing_profile_updated_pair check ((updated_at is null) = (updated_by is null))
);
-- Names are unique per clinic, case-insensitive. The unique key above serves the lookup by key and the ward foreign key.
create unique index if not exists ux_nursing_profile_tenant_name on ipd.nursing_profile (tenant_id, lower(name));

alter table ipd.nursing_profile enable row level security;
alter table ipd.nursing_profile force row level security;
create policy p_nursing_profile_select on ipd.nursing_profile
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_nursing_profile_insert on ipd.nursing_profile
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_nursing_profile_update on ipd.nursing_profile
  for update to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());
revoke all on ipd.nursing_profile from sahaj_app;
revoke all on ipd.nursing_profile from public;
grant select on ipd.nursing_profile to sahaj_app;
grant insert (key, name, med_verification_required, vitals_every_min, dose_window_before_min, dose_window_after_min, sort_order)
  on ipd.nursing_profile to sahaj_app;
grant update (name, med_verification_required, vitals_every_min, dose_window_before_min, dose_window_after_min, sort_order,
              updated_at, updated_by)
  on ipd.nursing_profile to sahaj_app;
-- No delete grant (a profile in use must not vanish).

-- At most 20 profiles per clinic: backstop behind the clinic IPD advisory lock taken by the app (SQLSTATE 23514).
create or replace function ipd.guard_nursing_profile_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if (select pg_catalog.count(*) from ipd.nursing_profile p where p.tenant_id = new.tenant_id) >= 20 then
    raise exception 'nursing profile limit of 20 per clinic reached'
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_nursing_profile_insert() from public;
revoke all on function ipd.guard_nursing_profile_insert() from sahaj_app;
create trigger trg_nursing_profile_guard_insert
  before insert on ipd.nursing_profile
  for each row execute function ipd.guard_nursing_profile_insert();

-- B3. ipd.ward.nursing_profile_key (nullable; null = the clinic settings apply).
alter table ipd.ward add column if not exists nursing_profile_key text;
alter table ipd.ward
  add constraint fk_ward_nursing_profile foreign key (tenant_id, nursing_profile_key)
    references ipd.nursing_profile (tenant_id, key);
create index if not exists ix_ward_tenant_nursing_profile on ipd.ward (tenant_id, nursing_profile_key)
  where nursing_profile_key is not null;

-- B4. ipd.vitals.due_interval_min: the interval in force when the set was saved (null on old rows = 240). Vitals stay append-only.
alter table ipd.vitals
  add column if not exists due_interval_min integer
    constraint ck_vitals_due_interval check (due_interval_min between 15 and 1440);
grant insert (due_interval_min) on ipd.vitals to sahaj_app;

-- ======================================================================================================================
-- C. Orders: head, versions, holds, notices, queries, reviews
-- ======================================================================================================================

-- C1. ipd.medicine_order (head). New columns have constant defaults: no rewrite, existing rows read as version 1, ROUTINE.
alter table ipd.medicine_order
  add column if not exists current_version integer not null default 1
    constraint ck_medicine_order_current_version check (current_version >= 1),
  add column if not exists priority text not null default 'ROUTINE'
    constraint ck_medicine_order_priority check (priority in ('ROUTINE', 'URGENT', 'STAT')),
  add column if not exists stop_reason text
    constraint ck_medicine_order_stop_reason check (stop_reason is null or char_length(stop_reason) between 1 and 200);

-- A PRN or STAT (ONCE) order has no frequency: the head keeps the frequency of version 1 (built readers), null when there is none.
-- The truth per version is ipd.medicine_order_version.frequency_key. Existing rows keep their value.
alter table ipd.medicine_order alter column frequency_key drop not null;

-- Replacement 1: status list. Replacement 2: the fields per status (ACTIVE and ON_HOLD need not carry a verifier: version NOT_REQUIRED;
-- a changed version in PENDING may still carry the earlier version's verified pair, the version row is the truth). The old forms
-- are strictly narrower, so every existing row satisfies the new ones.
alter table ipd.medicine_order drop constraint ck_medicine_order_status;
alter table ipd.medicine_order
  add constraint ck_medicine_order_status
  check (status in ('PENDING', 'QUERIED', 'ACTIVE', 'ON_HOLD', 'REJECTED', 'STOPPED'));
alter table ipd.medicine_order drop constraint ck_medicine_order_fields_by_status;
alter table ipd.medicine_order
  add constraint ck_medicine_order_fields_by_status check (
       (status in ('PENDING', 'QUERIED', 'ACTIVE', 'ON_HOLD') and rejected_by is null and stopped_by is null)
    or (status = 'REJECTED' and rejected_by is not null and stopped_by is null)
    or (status = 'STOPPED'  and stopped_by is not null and rejected_by is null));

-- Duplicate backstop widened to the four live statuses. The earlier narrower index stays (implied by this one).
create unique index if not exists ux_medicine_order_tenant_admission_drug_live4
  on ipd.medicine_order (tenant_id, admission_id, drug_key) where status in ('PENDING', 'QUERIED', 'ACTIVE', 'ON_HOLD');
-- Pharmacist queue with queried orders (keyset, oldest first), its counts and change token.
create index if not exists ix_medicine_order_tenant_pending_queried
  on ipd.medicine_order (tenant_id, ordered_at, id) where status in ('PENDING', 'QUERIED');

-- Grants: new orders may be placed ACTIVE (version NOT_REQUIRED) and with a priority; the app updates the new columns.
grant insert (status, priority) on ipd.medicine_order to sahaj_app;
grant update (current_version, priority, stop_reason) on ipd.medicine_order to sahaj_app;

-- C2. ipd.medicine_order_version: one row per version of an order (ADR-027 D1). Append-only except two one-way stamps.
create table if not exists ipd.medicine_order_version (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null default platform.current_tenant()
                   constraint fk_medicine_order_version_tenant references platform.tenant (id),
  order_id         uuid not null,
  version_no       integer not null
                   constraint ck_medicine_order_version_no check (version_no >= 1),
  kind             text not null
                   constraint ck_medicine_order_version_kind check (kind in ('SCHEDULED', 'PRN', 'ONCE')),
  frequency_key    text
                   constraint ck_medicine_order_version_frequency check (frequency_key in ('od', 'bd', 'tds', 'q8')),
  dose_amount      numeric(10, 3)
                   constraint ck_medicine_order_version_dose_amount check (dose_amount > 0),
  dose_unit        text
                   constraint ck_medicine_order_version_dose_unit
                   check (dose_unit in ('TABLET', 'CAPSULE', 'MG', 'G', 'MCG', 'ML', 'IU', 'UNIT', 'MMOL')),
  route_key        text not null
                   constraint ck_medicine_order_version_route
                   check (route_key in ('ORAL', 'IV', 'IV_INFUSION', 'IM', 'SC', 'SUBLINGUAL', 'INHALED', 'TOPICAL', 'RECTAL', 'NASOGASTRIC')),
  start_at         timestamptz not null,
  end_at           timestamptz,
  indication       text
                   constraint ck_medicine_order_version_indication check (char_length(indication) between 1 and 200),
  priority         text not null
                   constraint ck_medicine_order_version_priority check (priority in ('ROUTINE', 'URGENT', 'STAT')),
  min_interval_min integer
                   constraint ck_medicine_order_version_min_interval check (min_interval_min > 0),
  max_per_24h      integer
                   constraint ck_medicine_order_version_max_per_24h check (max_per_24h > 0),
  change_reason    text
                   constraint ck_medicine_order_version_change_reason check (char_length(change_reason) between 1 and 200),
  created_by       uuid not null,
  created_at       timestamptz not null default clock_timestamp(),
  superseded_at    timestamptz,
  verification     text not null
                   constraint ck_medicine_order_version_verification check (verification in ('REQUIRED', 'NOT_REQUIRED')),
  verified_by      uuid,
  verified_at      timestamptz,
  constraint uq_medicine_order_version_order_no unique (tenant_id, order_id, version_no),
  constraint fk_medicine_order_version_order foreign key (tenant_id, order_id)
    references ipd.medicine_order (tenant_id, id),
  -- SCHEDULED has a frequency; PRN and ONCE do not
  constraint ck_medicine_order_version_kind_frequency check ((kind = 'SCHEDULED') = (frequency_key is not null)),
  -- dose and unit are both set or both null (null only for built rows and orders placed through opd.api)
  constraint ck_medicine_order_version_dose_pair check ((dose_amount is null) = (dose_unit is null)),
  constraint ck_medicine_order_version_end check (end_at is null or end_at > start_at),
  -- PRN: a limit pair and an indication; others: none. STAT is always a one-time (ONCE) version.
  constraint ck_medicine_order_version_prn
    check ((kind = 'PRN') = (min_interval_min is not null) and (kind = 'PRN') = (max_per_24h is not null)),
  constraint ck_medicine_order_version_prn_indication check (kind <> 'PRN' or indication is not null),
  constraint ck_medicine_order_version_stat_once check (priority <> 'STAT' or kind = 'ONCE'),
  -- a reason exists exactly for a change (version 2 onwards)
  constraint ck_medicine_order_version_reason_by_no check ((version_no = 1) = (change_reason is null)),
  constraint ck_medicine_order_version_verified_pair check ((verified_by is null) = (verified_at is null)),
  constraint ck_medicine_order_version_verified_required check (verification = 'REQUIRED' or verified_by is null),
  constraint ck_medicine_order_version_verified_at check (verified_at is null or verified_at >= created_at),
  constraint ck_medicine_order_version_superseded_at check (superseded_at is null or superseded_at >= created_at)
);
-- The unique constraint serves "versions of one order in order" (timeline, EMR row, diff) and the foreign keys of dose, notice,
-- query and review. A set read for many admissions goes through ix_medicine_order_tenant_admission_ordered then this index.

-- C3. Backfill: version 1 for every existing order (ADR-027 D1): SCHEDULED, the built frequency, dose and unit null ("as in the
-- medicine name"), the drug's route, start = ordered_at, until stopped, ROUTINE, verification REQUIRED with the built verified pair.
-- Inserts only; the head rows are not changed. The migrator is the owner and FORCE RLS has no policy for it on the head table, so
-- FORCE is lifted on the head for this one read and restored at once, in this transaction. The version table has no policy yet
-- and no trigger yet (both are created below), so this insert is neither filtered nor guarded.
alter table ipd.medicine_order no force row level security;
insert into ipd.medicine_order_version
  (tenant_id, order_id, version_no, kind, frequency_key, dose_amount, dose_unit, route_key, start_at, end_at, indication,
   priority, min_interval_min, max_per_24h, change_reason, created_by, created_at, superseded_at, verification, verified_by, verified_at)
select o.tenant_id, o.id, 1, 'SCHEDULED', o.frequency_key, null, null, d.route_key, o.ordered_at, null, null,
       'ROUTINE', null, null, null, o.ordered_by, o.ordered_at, null, 'REQUIRED', o.verified_by, o.verified_at
  from ipd.medicine_order o
  join ipd.drug d on d.key = o.drug_key
 where not exists (select 1 from ipd.medicine_order_version v where v.tenant_id = o.tenant_id and v.order_id = o.id);
alter table ipd.medicine_order force row level security;

alter table ipd.medicine_order_version enable row level security;
alter table ipd.medicine_order_version force row level security;
create policy p_medicine_order_version_select on ipd.medicine_order_version
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_medicine_order_version_insert on ipd.medicine_order_version
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_medicine_order_version_update on ipd.medicine_order_version
  for update to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());
revoke all on ipd.medicine_order_version from sahaj_app;
revoke all on ipd.medicine_order_version from public;
grant select on ipd.medicine_order_version to sahaj_app;
grant insert (order_id, version_no, kind, frequency_key, dose_amount, dose_unit, route_key, start_at, end_at, indication, priority,
              min_interval_min, max_per_24h, change_reason, created_by, verification)
  on ipd.medicine_order_version to sahaj_app;
-- superseded_at and verified_at are stamped by the guard trigger from clock_timestamp(); the app sends superseded_at (any non-null
-- value) and verified_by.
grant update (superseded_at, verified_by) on ipd.medicine_order_version to sahaj_app;

create trigger trg_medicine_order_version_no_delete
  before delete on ipd.medicine_order_version
  for each row execute function platform.deny_audit_mutation();
create trigger trg_medicine_order_version_no_truncate
  before truncate on ipd.medicine_order_version
  for each statement execute function platform.deny_audit_mutation();

-- Insert guard: next number only; the head is not final and not on hold (a change of an ON_HOLD order is refused); the patient is
-- admitted; no verification on insert (it is an update). A missing or invisible head is left to the foreign key (23503).
create or replace function ipd.guard_medicine_order_version_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_status text;
  v_cur    integer;
  v_adm_id uuid;
  v_adm    text;
  v_max    integer;
begin
  select o.status, o.current_version, o.admission_id into v_status, v_cur, v_adm_id
    from ipd.medicine_order o
   where o.tenant_id = new.tenant_id and o.id = new.order_id;
  if not found then
    return new;
  end if;
  select coalesce(pg_catalog.max(v.version_no), 0) into v_max
    from ipd.medicine_order_version v
   where v.tenant_id = new.tenant_id and v.order_id = new.order_id;
  if new.version_no <> v_max + 1 then
    raise exception 'medicine order version must be the next number'
      using errcode = 'check_violation';
  end if;
  if new.version_no = 1 then
    if v_status not in ('PENDING', 'ACTIVE') then
      raise exception 'first medicine order version only belongs to a new order'
        using errcode = 'check_violation';
    end if;
  else
    if v_status not in ('PENDING', 'QUERIED', 'ACTIVE') or new.version_no <> v_cur + 1 then
      raise exception 'medicine order version can be added only to a pending, queried or active order'
        using errcode = 'check_violation';
    end if;
  end if;
  if new.verified_by is not null or new.verified_at is not null then
    raise exception 'medicine order version is verified by an update, not at insert'
      using errcode = 'check_violation';
  end if;
  select a.status into v_adm
    from ipd.admission a
   where a.tenant_id = new.tenant_id and a.id = v_adm_id;
  if v_adm is distinct from 'ADMITTED' then
    raise exception 'medicine order version can be added only while the patient is admitted'
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_medicine_order_version_insert() from public;
revoke all on function ipd.guard_medicine_order_version_insert() from sahaj_app;
create trigger trg_medicine_order_version_guard_insert
  before insert on ipd.medicine_order_version
  for each row execute function ipd.guard_medicine_order_version_insert();

-- Update guard: only two one-way stamps. superseded_at (null to a value, once; stamped from clock_timestamp()) and the verify pair
-- (null to a value, once, REQUIRED only, never the person who placed the order; verified_at stamped). Everything else frozen.
create or replace function ipd.guard_medicine_order_version_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_ordered_by uuid;
  v_at         timestamptz := clock_timestamp();
begin
  if (pg_catalog.to_jsonb(new) - 'superseded_at' - 'verified_by' - 'verified_at')
     is distinct from (pg_catalog.to_jsonb(old) - 'superseded_at' - 'verified_by' - 'verified_at') then
    raise exception 'medicine order version cannot be edited'
      using errcode = 'check_violation';
  end if;
  if old.superseded_at is not null and new.superseded_at is distinct from old.superseded_at then
    raise exception 'medicine order version is already replaced'
      using errcode = 'check_violation';
  end if;
  if old.superseded_at is null and new.superseded_at is not null then
    new.superseded_at := v_at;
  end if;
  if old.verified_by is not null and (new.verified_by is distinct from old.verified_by
                                      or new.verified_at is distinct from old.verified_at) then
    raise exception 'medicine order version is already verified'
      using errcode = 'check_violation';
  end if;
  if old.verified_by is null and new.verified_by is not null then
    if old.verification <> 'REQUIRED' then
      raise exception 'medicine order version needs no verification'
        using errcode = 'check_violation';
    end if;
    select o.ordered_by into v_ordered_by
      from ipd.medicine_order o
     where o.tenant_id = new.tenant_id and o.id = new.order_id;
    if new.verified_by = v_ordered_by then
      raise exception 'medicine order version cannot be verified by the person who ordered it'
        using errcode = 'check_violation';
    end if;
    new.verified_at := v_at;
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_medicine_order_version_update() from public;
revoke all on function ipd.guard_medicine_order_version_update() from sahaj_app;
create trigger trg_medicine_order_version_guard_update
  before update on ipd.medicine_order_version
  for each row execute function ipd.guard_medicine_order_version_update();

-- C4. Head guards, replaced in place (create or replace; same trigger names as before).
-- Insert: a new order is PENDING, or ACTIVE when its version needs no verification (checked at commit by the deferred trigger
-- below); current_version is 1; the patient is admitted.
create or replace function ipd.guard_medicine_order_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_adm text;
begin
  if new.status not in ('PENDING', 'ACTIVE') then
    raise exception 'medicine order must start as pending or active'
      using errcode = 'check_violation';
  end if;
  if new.current_version <> 1 then
    raise exception 'medicine order must start at version 1'
      using errcode = 'check_violation';
  end if;
  select a.status into v_adm
    from ipd.admission a
   where a.tenant_id = new.tenant_id and a.id = new.admission_id;
  if found and v_adm <> 'ADMITTED' then
    raise exception 'medicine order can be placed only for an admitted patient'
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;

-- Update (ADR-027 D1 table). row counter "version" + 1 on every update; "current_version" + 1 only with a change.
--   change (current_version + 1):  PENDING, QUERIED, ACTIVE -> PENDING or ACTIVE
--   otherwise:  PENDING -> ACTIVE (verify), REJECTED, QUERIED, STOPPED;  QUERIED -> PENDING (reply), REJECTED, STOPPED;
--               ACTIVE -> ON_HOLD, STOPPED;  ON_HOLD -> ACTIVE (resume), STOPPED.   REJECTED and STOPPED are final.
-- QUERIED -> REJECTED is allowed because a query can be closed by a reject (ADR-027 D6, closed_by REJECT).
-- A stop needs stop_reason; the verified pair changes only in a verify (PENDING -> ACTIVE, same version) and is stamped here.
create or replace function ipd.guard_medicine_order_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_adm     text;
  v_at      timestamptz := clock_timestamp();
  v_changed boolean;
  v_verify  boolean;
begin
  if old.status in ('REJECTED', 'STOPPED') then
    raise exception 'medicine order is closed (update refused)'
      using errcode = 'check_violation';
  end if;

  if new.id is distinct from old.id
     or new.tenant_id is distinct from old.tenant_id
     or new.admission_id is distinct from old.admission_id
     or new.client_key is distinct from old.client_key
     or new.drug_key is distinct from old.drug_key
     or new.frequency_key is distinct from old.frequency_key
     or new.ordered_by is distinct from old.ordered_by
     or new.ordered_at is distinct from old.ordered_at
     or new.override_reason_key is distinct from old.override_reason_key
     or new.warnings is distinct from old.warnings then
    raise exception 'medicine order identity and order fields cannot change'
      using errcode = 'check_violation';
  end if;

  if new.version <> old.version + 1 then
    raise exception 'medicine order version must go up by exactly one with every update'
      using errcode = 'check_violation';
  end if;

  if new.current_version = old.current_version + 1 then
    v_changed := true;
  elsif new.current_version = old.current_version then
    v_changed := false;
  else
    raise exception 'medicine order version number must go up by exactly one with a change'
      using errcode = 'check_violation';
  end if;

  if v_changed then
    if not (old.status in ('PENDING', 'QUERIED', 'ACTIVE') and new.status in ('PENDING', 'ACTIVE')) then
      raise exception 'medicine order change is not allowed in this status'
        using errcode = 'check_violation';
    end if;
  elsif not ((old.status = 'PENDING' and new.status in ('ACTIVE', 'REJECTED', 'QUERIED', 'STOPPED'))
          or (old.status = 'QUERIED' and new.status in ('PENDING', 'REJECTED', 'STOPPED'))
          or (old.status = 'ACTIVE'  and new.status in ('ON_HOLD', 'STOPPED'))
          or (old.status = 'ON_HOLD' and new.status in ('ACTIVE', 'STOPPED'))) then
    raise exception 'medicine order status change is not allowed'
      using errcode = 'check_violation';
  end if;

  select a.status into v_adm
    from ipd.admission a
   where a.tenant_id = new.tenant_id and a.id = new.admission_id;
  if v_adm is distinct from 'ADMITTED' then
    raise exception 'medicine order can change only while the patient is admitted'
      using errcode = 'check_violation';
  end if;

  v_verify := (not v_changed) and old.status = 'PENDING' and new.status = 'ACTIVE';
  if not v_verify
     and (new.verified_by is distinct from old.verified_by or new.verified_at is distinct from old.verified_at) then
    raise exception 'medicine order verification is recorded only when it is verified'
      using errcode = 'check_violation';
  end if;
  if new.status <> 'REJECTED'
     and (new.rejected_by is distinct from old.rejected_by
          or new.rejected_at is distinct from old.rejected_at
          or new.reject_reason_key is distinct from old.reject_reason_key) then
    raise exception 'medicine order rejection is recorded only when it is rejected'
      using errcode = 'check_violation';
  end if;
  if new.status <> 'STOPPED'
     and (new.stopped_by is distinct from old.stopped_by
          or new.stopped_at is distinct from old.stopped_at
          or new.stop_reason is distinct from old.stop_reason) then
    raise exception 'medicine order stop is recorded only when it is stopped'
      using errcode = 'check_violation';
  end if;

  if v_verify then
    if new.verified_by is null then
      raise exception 'medicine order needs a verifying user to become active'
        using errcode = 'check_violation';
    end if;
    new.verified_at := v_at;
  elsif new.status = 'REJECTED' then
    if new.rejected_by is null or new.reject_reason_key is null then
      raise exception 'medicine order needs a rejecting user and a reason to be rejected'
        using errcode = 'check_violation';
    end if;
    new.rejected_at := v_at;
  elsif new.status = 'STOPPED' then
    if new.stopped_by is null or new.stop_reason is null then
      raise exception 'medicine order needs a stopping user and a reason to be stopped'
        using errcode = 'check_violation';
    end if;
    new.stopped_at := v_at;
  end if;
  return new;
end;
$$;

-- Deferred consistency check (at commit): the head points at an existing version, copies its priority, and an ACTIVE or ON_HOLD
-- order has a version that is verified or needs no verification; a PENDING or QUERIED order has a REQUIRED, unverified version.
-- Lets the app write the version and the head in any order inside one transaction.
create or replace function ipd.check_medicine_order_current_version()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_priority     text;
  v_verification text;
  v_verified_by  uuid;
begin
  select v.priority, v.verification, v.verified_by into v_priority, v_verification, v_verified_by
    from ipd.medicine_order_version v
   where v.tenant_id = new.tenant_id and v.order_id = new.id and v.version_no = new.current_version;
  if not found then
    raise exception 'medicine order points at a version that does not exist'
      using errcode = 'check_violation';
  end if;
  if new.priority <> v_priority then
    raise exception 'medicine order priority must equal the priority of its current version'
      using errcode = 'check_violation';
  end if;
  if new.status in ('ACTIVE', 'ON_HOLD') and v_verification = 'REQUIRED' and v_verified_by is null then
    raise exception 'active medicine order needs a verified version'
      using errcode = 'check_violation';
  end if;
  if new.status in ('PENDING', 'QUERIED') and (v_verification <> 'REQUIRED' or v_verified_by is not null) then
    raise exception 'pending medicine order needs an unverified version that requires verification'
      using errcode = 'check_violation';
  end if;
  return null;
end;
$$;
revoke all on function ipd.check_medicine_order_current_version() from public;
revoke all on function ipd.check_medicine_order_current_version() from sahaj_app;
create constraint trigger trg_medicine_order_check_current_version
  after insert or update on ipd.medicine_order
  deferrable initially deferred
  for each row execute function ipd.check_medicine_order_current_version();

-- C5. ipd.order_hold: hold and resume intervals (ADR-027 D4). Typed order reference so lab, nursing and monitoring orders can add
-- one nullable column each (check: exactly one set).
create table if not exists ipd.order_hold (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null default platform.current_tenant()
                    constraint fk_order_hold_tenant references platform.tenant (id),
  order_kind        text not null default 'MEDICINE'
                    constraint ck_order_hold_kind check (order_kind = 'MEDICINE'),
  medicine_order_id uuid,
  held_at           timestamptz not null default clock_timestamp(),
  held_by           uuid not null,
  hold_reason       text not null
                    constraint ck_order_hold_reason check (char_length(hold_reason) between 1 and 200),
  resumed_at        timestamptz,
  resumed_by        uuid,
  constraint fk_order_hold_medicine_order foreign key (tenant_id, medicine_order_id)
    references ipd.medicine_order (tenant_id, id),
  constraint ck_order_hold_one_order check (num_nonnulls(medicine_order_id) = 1),
  constraint ck_order_hold_resumed_pair check ((resumed_at is null) = (resumed_by is null)),
  constraint ck_order_hold_resumed_at check (resumed_at is null or resumed_at >= held_at)
);
create unique index if not exists ux_order_hold_open_medicine
  on ipd.order_hold (tenant_id, medicine_order_id) where resumed_at is null and medicine_order_id is not null;
-- Hold intervals of the orders of an admission (set read for the timeline; join through the order).
create index if not exists ix_order_hold_tenant_medicine_held
  on ipd.order_hold (tenant_id, medicine_order_id, held_at);

alter table ipd.order_hold enable row level security;
alter table ipd.order_hold force row level security;
create policy p_order_hold_select on ipd.order_hold
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_order_hold_insert on ipd.order_hold
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_order_hold_update on ipd.order_hold
  for update to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());
revoke all on ipd.order_hold from sahaj_app;
revoke all on ipd.order_hold from public;
grant select on ipd.order_hold to sahaj_app;
grant insert (medicine_order_id, held_by, hold_reason) on ipd.order_hold to sahaj_app;
grant update (resumed_by) on ipd.order_hold to sahaj_app;   -- resumed_at is stamped by the guard trigger

create trigger trg_order_hold_no_delete
  before delete on ipd.order_hold
  for each row execute function platform.deny_audit_mutation();
create trigger trg_order_hold_no_truncate
  before truncate on ipd.order_hold
  for each statement execute function platform.deny_audit_mutation();

create or replace function ipd.guard_order_hold_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_status text;
begin
  select o.status into v_status
    from ipd.medicine_order o
   where o.tenant_id = new.tenant_id and o.id = new.medicine_order_id;
  if found and v_status not in ('ACTIVE', 'ON_HOLD') then
    raise exception 'order hold can be placed only on an active order'
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_order_hold_insert() from public;
revoke all on function ipd.guard_order_hold_insert() from sahaj_app;
create trigger trg_order_hold_guard_insert
  before insert on ipd.order_hold
  for each row execute function ipd.guard_order_hold_insert();

create or replace function ipd.guard_order_hold_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if (pg_catalog.to_jsonb(new) - 'resumed_at' - 'resumed_by') is distinct from (pg_catalog.to_jsonb(old) - 'resumed_at' - 'resumed_by') then
    raise exception 'order hold cannot be edited'
      using errcode = 'check_violation';
  end if;
  if old.resumed_at is not null then
    raise exception 'order hold is already resumed'
      using errcode = 'check_violation';
  end if;
  if new.resumed_by is null then
    raise exception 'order hold needs the resuming user'
      using errcode = 'check_violation';
  end if;
  new.resumed_at := clock_timestamp();
  return new;
end;
$$;
revoke all on function ipd.guard_order_hold_update() from public;
revoke all on function ipd.guard_order_hold_update() from sahaj_app;
create trigger trg_order_hold_guard_update
  before update on ipd.order_hold
  for each row execute function ipd.guard_order_hold_update();

-- C6. ipd.order_notice: nurse order notices (the inbox) and their acknowledgement (ADR-027 D5).
create table if not exists ipd.order_notice (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null default platform.current_tenant()
                    constraint fk_order_notice_tenant references platform.tenant (id),
  admission_id      uuid not null,
  order_kind        text not null default 'MEDICINE'
                    constraint ck_order_notice_order_kind check (order_kind = 'MEDICINE'),
  medicine_order_id uuid,
  order_version     integer not null
                    constraint ck_order_notice_order_version check (order_version >= 1),
  kind              text not null
                    constraint ck_order_notice_kind check (kind in ('NEW', 'CHANGED', 'HELD', 'RESUMED', 'STOPPED')),
  priority          text not null
                    constraint ck_order_notice_priority check (priority in ('ROUTINE', 'URGENT', 'STAT')),
  created_by        uuid not null,
  created_at        timestamptz not null default clock_timestamp(),
  acknowledged_by   uuid,
  acknowledged_at   timestamptz,
  acknowledged_via  text
                    constraint ck_order_notice_via check (acknowledged_via in ('TAP', 'DOSE')),
  constraint fk_order_notice_order foreign key (tenant_id, medicine_order_id, admission_id)
    references ipd.medicine_order (tenant_id, id, admission_id),
  constraint fk_order_notice_version foreign key (tenant_id, medicine_order_id, order_version)
    references ipd.medicine_order_version (tenant_id, order_id, version_no),
  constraint ck_order_notice_one_order check (num_nonnulls(medicine_order_id) = 1),
  constraint ck_order_notice_ack_triple
    check ((acknowledged_by is null) = (acknowledged_at is null) and (acknowledged_by is null) = (acknowledged_via is null)),
  constraint ck_order_notice_ack_at check (acknowledged_at is null or acknowledged_at >= created_at)
);
-- Badges and the summary poll: open notices of an admission, oldest first.
create index if not exists ix_order_notice_tenant_admission_open
  on ipd.order_notice (tenant_id, admission_id, created_at) where acknowledged_at is null;
-- Orders tab: all notices of an admission, newest first.
create index if not exists ix_order_notice_tenant_admission_created
  on ipd.order_notice (tenant_id, admission_id, created_at desc, id);
-- Acknowledge by dose and the foreign key to the order.
create index if not exists ix_order_notice_tenant_medicine_order
  on ipd.order_notice (tenant_id, medicine_order_id);

alter table ipd.order_notice enable row level security;
alter table ipd.order_notice force row level security;
create policy p_order_notice_select on ipd.order_notice
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_order_notice_insert on ipd.order_notice
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_order_notice_update on ipd.order_notice
  for update to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());
revoke all on ipd.order_notice from sahaj_app;
revoke all on ipd.order_notice from public;
grant select on ipd.order_notice to sahaj_app;
grant insert (admission_id, medicine_order_id, order_version, kind, priority, created_by) on ipd.order_notice to sahaj_app;
grant update (acknowledged_by, acknowledged_via) on ipd.order_notice to sahaj_app;   -- acknowledged_at is stamped by the guard

create trigger trg_order_notice_no_delete
  before delete on ipd.order_notice
  for each row execute function platform.deny_audit_mutation();
create trigger trg_order_notice_no_truncate
  before truncate on ipd.order_notice
  for each statement execute function platform.deny_audit_mutation();

create or replace function ipd.guard_order_notice_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if (pg_catalog.to_jsonb(new) - 'acknowledged_by' - 'acknowledged_at' - 'acknowledged_via')
     is distinct from (pg_catalog.to_jsonb(old) - 'acknowledged_by' - 'acknowledged_at' - 'acknowledged_via') then
    raise exception 'order notice cannot be edited'
      using errcode = 'check_violation';
  end if;
  if old.acknowledged_at is not null then
    raise exception 'order notice is already acknowledged'
      using errcode = 'check_violation';
  end if;
  if new.acknowledged_by is null or new.acknowledged_via is null then
    raise exception 'order notice needs the acknowledging user and how'
      using errcode = 'check_violation';
  end if;
  new.acknowledged_at := clock_timestamp();
  return new;
end;
$$;
revoke all on function ipd.guard_order_notice_update() from public;
revoke all on function ipd.guard_order_notice_update() from sahaj_app;
create trigger trg_order_notice_guard_update
  before update on ipd.order_notice
  for each row execute function ipd.guard_order_notice_update();

-- C7. ipd.medicine_order_query: the pharmacist's question to the doctor and the reply (ADR-027 D6). Clinical text: never logged.
create table if not exists ipd.medicine_order_query (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_medicine_order_query_tenant references platform.tenant (id),
  order_id      uuid not null,
  order_version integer not null
                constraint ck_medicine_order_query_version check (order_version >= 1),
  question      text not null
                constraint ck_medicine_order_query_question check (char_length(question) between 1 and 300),
  asked_by      uuid not null,
  asked_at      timestamptz not null default clock_timestamp(),
  reply         text
                constraint ck_medicine_order_query_reply check (char_length(reply) between 1 and 300),
  replied_by    uuid,
  replied_at    timestamptz,
  closed_by     text
                constraint ck_medicine_order_query_closed_by check (closed_by in ('REPLY', 'CHANGE', 'STOP', 'REJECT')),
  closed_at     timestamptz,
  constraint fk_medicine_order_query_order foreign key (tenant_id, order_id)
    references ipd.medicine_order (tenant_id, id),
  constraint fk_medicine_order_query_version foreign key (tenant_id, order_id, order_version)
    references ipd.medicine_order_version (tenant_id, order_id, version_no),
  constraint ck_medicine_order_query_reply_triple
    check ((reply is null) = (replied_by is null) and (reply is null) = (replied_at is null)),
  constraint ck_medicine_order_query_closed_pair check ((closed_by is null) = (closed_at is null)),
  constraint ck_medicine_order_query_reply_closes check ((closed_by = 'REPLY') = (reply is not null)),
  constraint ck_medicine_order_query_closed_at check (closed_at is null or closed_at >= asked_at)
);
create unique index if not exists ux_medicine_order_query_open
  on ipd.medicine_order_query (tenant_id, order_id) where closed_at is null;
-- Queries of the orders of an admission (EMR row, queue row): newest first.
create index if not exists ix_medicine_order_query_tenant_order_asked
  on ipd.medicine_order_query (tenant_id, order_id, asked_at desc);

alter table ipd.medicine_order_query enable row level security;
alter table ipd.medicine_order_query force row level security;
create policy p_medicine_order_query_select on ipd.medicine_order_query
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_medicine_order_query_insert on ipd.medicine_order_query
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_medicine_order_query_update on ipd.medicine_order_query
  for update to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());
revoke all on ipd.medicine_order_query from sahaj_app;
revoke all on ipd.medicine_order_query from public;
grant select on ipd.medicine_order_query to sahaj_app;
grant insert (order_id, order_version, question, asked_by) on ipd.medicine_order_query to sahaj_app;
grant update (reply, replied_by, closed_by) on ipd.medicine_order_query to sahaj_app;   -- replied_at, closed_at stamped by the guard

create trigger trg_medicine_order_query_no_delete
  before delete on ipd.medicine_order_query
  for each row execute function platform.deny_audit_mutation();
create trigger trg_medicine_order_query_no_truncate
  before truncate on ipd.medicine_order_query
  for each statement execute function platform.deny_audit_mutation();

-- Insert: the asker is never the orderer (verify rule, Q6); the order is PENDING or QUERIED at its current version.
create or replace function ipd.guard_medicine_order_query_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_status     text;
  v_ordered_by uuid;
  v_cur        integer;
begin
  select o.status, o.ordered_by, o.current_version into v_status, v_ordered_by, v_cur
    from ipd.medicine_order o
   where o.tenant_id = new.tenant_id and o.id = new.order_id;
  if not found then
    return new;
  end if;
  if v_status not in ('PENDING', 'QUERIED') then
    raise exception 'medicine order query applies only to an order in the pharmacist check'
      using errcode = 'check_violation';
  end if;
  if new.asked_by = v_ordered_by then
    raise exception 'medicine order query cannot be asked by the person who ordered it'
      using errcode = 'check_violation';
  end if;
  if new.order_version <> v_cur then
    raise exception 'medicine order query must name the current version'
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_medicine_order_query_insert() from public;
revoke all on function ipd.guard_medicine_order_query_insert() from sahaj_app;
create trigger trg_medicine_order_query_guard_insert
  before insert on ipd.medicine_order_query
  for each row execute function ipd.guard_medicine_order_query_insert();

-- Update: close once. A reply closes it as REPLY; a change, stop or reject closes it without a reply. Times are stamped here.
create or replace function ipd.guard_medicine_order_query_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_at timestamptz := clock_timestamp();
begin
  if (pg_catalog.to_jsonb(new) - 'reply' - 'replied_by' - 'replied_at' - 'closed_by' - 'closed_at')
     is distinct from (pg_catalog.to_jsonb(old) - 'reply' - 'replied_by' - 'replied_at' - 'closed_by' - 'closed_at') then
    raise exception 'medicine order query cannot be edited'
      using errcode = 'check_violation';
  end if;
  if old.closed_at is not null then
    raise exception 'medicine order query is already closed'
      using errcode = 'check_violation';
  end if;
  if new.closed_by is null then
    raise exception 'medicine order query can only be closed'
      using errcode = 'check_violation';
  end if;
  if new.reply is not null then
    new.replied_at := v_at;
  end if;
  new.closed_at := v_at;
  return new;
end;
$$;
revoke all on function ipd.guard_medicine_order_query_update() from public;
revoke all on function ipd.guard_medicine_order_query_update() from sahaj_app;
create trigger trg_medicine_order_query_guard_update
  before update on ipd.medicine_order_query
  for each row execute function ipd.guard_medicine_order_query_update();

-- ======================================================================================================================
-- D. Doses: widened record, corrections, pharmacist review
-- ======================================================================================================================

-- D1. ipd.dose_administration (append-only, unchanged triggers). Every new column is nullable or has a constant default: old rows
-- read as attempt 1, version 1, SCHEDULED, not before verification, no scan method (a built GIVEN dose was a scan). Not rewritten.
alter table ipd.dose_administration
  add column if not exists attempt smallint not null default 1
    constraint ck_dose_administration_attempt check (attempt >= 1),
  add column if not exists supersedes_dose_id uuid,
  add column if not exists order_version integer not null default 1
    constraint ck_dose_administration_order_version check (order_version >= 1),
  add column if not exists dose_kind text not null default 'SCHEDULED'
    constraint ck_dose_administration_dose_kind check (dose_kind in ('SCHEDULED', 'PRN', 'ONCE')),
  add column if not exists scan_method text
    constraint ck_dose_administration_scan_method check (scan_method in ('SCAN', 'OVERRIDE')),
  add column if not exists override_reason_key text
    constraint ck_dose_administration_override_reason
    check (override_reason_key in ('WRISTBAND_DAMAGED', 'NO_BARCODE', 'SCANNER_DOWN', 'EMERGENCY')),
  add column if not exists override_note text
    constraint ck_dose_administration_override_note check (char_length(btrim(override_note)) between 4 and 200),
  add column if not exists medicine_scan text
    constraint ck_dose_administration_medicine_scan check (char_length(medicine_scan) between 1 and 64),
  add column if not exists before_verification boolean not null default false,
  add column if not exists undo_until timestamptz,
  add column if not exists not_given_note text
    constraint ck_dose_administration_not_given_note check (char_length(btrim(not_given_note)) between 4 and 200);

-- Targets of the new foreign keys (this table, corrections, reviews, the version).
alter table ipd.dose_administration add constraint uq_dose_administration_tenant_id unique (tenant_id, id);
alter table ipd.dose_administration
  add constraint uq_dose_administration_tenant_supersedes unique (tenant_id, supersedes_dose_id),
  add constraint fk_dose_administration_supersedes foreign key (tenant_id, supersedes_dose_id)
    references ipd.dose_administration (tenant_id, id),
  add constraint fk_dose_administration_version foreign key (tenant_id, order_id, order_version)
    references ipd.medicine_order_version (tenant_id, order_id, version_no),
  add constraint ck_dose_administration_attempt_supersedes check ((attempt = 1) = (supersedes_dose_id is null)),
  -- scan fields exist for GIVEN only (null on old rows); an override carries its reason and note
  add constraint ck_dose_administration_scan_method_given check (scan_method is null or outcome = 'GIVEN'),
  add constraint ck_dose_administration_override_reason_by_method check ((scan_method = 'OVERRIDE') = (override_reason_key is not null)),
  add constraint ck_dose_administration_override_note_by_method check ((scan_method = 'OVERRIDE') = (override_note is not null)),
  add constraint ck_dose_administration_medicine_scan_given check (medicine_scan is null or outcome = 'GIVEN'),
  add constraint ck_dose_administration_undo_until_given check (undo_until is null or outcome = 'GIVEN'),
  add constraint ck_dose_administration_not_given_note_outcome check (not_given_note is null or outcome <> 'GIVEN'),
  add constraint ck_dose_administration_other_needs_note check (reason_key is distinct from 'OTHER' or not_given_note is not null);

-- Replacement 4: not-given reasons add VOMITED and OTHER (both HELD).
alter table ipd.dose_administration drop constraint ck_dose_administration_reason;
alter table ipd.dose_administration
  add constraint ck_dose_administration_reason
  check (reason_key is null or reason_key in
         ('PATIENT_REFUSED', 'HELD_DOCTOR', 'NIL_BY_MOUTH', 'NOT_AVAILABLE', 'AWAY_FROM_WARD', 'VOMITED', 'OTHER'));
-- Replacement 5: the wristband scan may be null for a manual override (the check still requires it otherwise).
alter table ipd.dose_administration drop constraint ck_dose_administration_identity_scan_by_outcome;
alter table ipd.dose_administration
  add constraint ck_dose_administration_identity_scan_by_outcome check (
       (outcome <> 'GIVEN' and identity_scan is null)
    or (outcome = 'GIVEN' and (identity_scan is not null or scan_method = 'OVERRIDE')));
-- Replacement 6: a slot has at most one record per attempt (a new give after an error is attempt + 1). Same constraint name.
alter table ipd.dose_administration drop constraint uq_dose_administration_order_slot;
alter table ipd.dose_administration
  add constraint uq_dose_administration_order_slot unique (tenant_id, order_id, slot_at, attempt);

-- Quality data: overrides by clinic and time.
create index if not exists ix_dose_administration_override
  on ipd.dose_administration (tenant_id, scan_method, recorded_at) where scan_method = 'OVERRIDE';

grant insert (attempt, supersedes_dose_id, order_version, dose_kind, scan_method, override_reason_key, override_note,
              medicine_scan, before_verification, not_given_note)
  on ipd.dose_administration to sahaj_app;   -- undo_until is stamped by the guard trigger, not insertable

-- D2. ipd.dose_correction: undo and "recorded in error" (ADR-027 D3). Append-only: select and insert only.
create table if not exists ipd.dose_correction (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null default platform.current_tenant()
               constraint fk_dose_correction_tenant references platform.tenant (id),
  dose_id      uuid not null,
  kind         text not null
               constraint ck_dose_correction_kind check (kind in ('UNDO', 'IN_ERROR')),
  reason_key   text
               constraint ck_dose_correction_reason
               check (reason_key in ('NOT_GIVEN_ACTUALLY', 'WRONG_PATIENT', 'WRONG_TIME', 'DUPLICATE')),
  corrected_by uuid not null,
  corrected_at timestamptz not null default clock_timestamp(),
  client_key   uuid not null,
  constraint uq_dose_correction_dose unique (tenant_id, dose_id),
  constraint uq_dose_correction_client_key unique (tenant_id, client_key),
  constraint fk_dose_correction_dose foreign key (tenant_id, dose_id)
    references ipd.dose_administration (tenant_id, id),
  constraint ck_dose_correction_reason_by_kind check ((kind = 'IN_ERROR') = (reason_key is not null))
);

alter table ipd.dose_correction enable row level security;
alter table ipd.dose_correction force row level security;
create policy p_dose_correction_select on ipd.dose_correction
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_dose_correction_insert on ipd.dose_correction
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on ipd.dose_correction from sahaj_app;
revoke all on ipd.dose_correction from public;
grant select on ipd.dose_correction to sahaj_app;
grant insert (dose_id, kind, reason_key, corrected_by, client_key) on ipd.dose_correction to sahaj_app;

create trigger trg_dose_correction_no_update_delete
  before update or delete on ipd.dose_correction
  for each row execute function platform.deny_audit_mutation();
create trigger trg_dose_correction_no_truncate
  before truncate on ipd.dose_correction
  for each statement execute function platform.deny_audit_mutation();

-- Insert guard: only a GIVEN dose. UNDO: by the recorder, and the database clock is not after undo_until (an old dose has none: no
-- undo). IN_ERROR: only while the IST date of the database clock equals the IST date of the slot. A missing or invisible dose is left
-- to the foreign key (23503).
create or replace function ipd.guard_dose_correction_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_outcome     text;
  v_recorded_by uuid;
  v_slot_at     timestamptz;
  v_undo_until  timestamptz;
  v_now         timestamptz := clock_timestamp();
begin
  select d.outcome, d.recorded_by, d.slot_at, d.undo_until into v_outcome, v_recorded_by, v_slot_at, v_undo_until
    from ipd.dose_administration d
   where d.tenant_id = new.tenant_id and d.id = new.dose_id;
  if not found then
    return new;
  end if;
  if v_outcome <> 'GIVEN' then
    raise exception 'dose correction applies only to a given dose'
      using errcode = 'check_violation';
  end if;
  if new.kind = 'UNDO' then
    if new.corrected_by <> v_recorded_by then
      raise exception 'dose can be undone only by the person who recorded it'
        using errcode = 'check_violation';
    end if;
    if v_undo_until is null or v_now > v_undo_until then
      raise exception 'dose undo window has closed'
        using errcode = 'check_violation';
    end if;
  else
    if pg_catalog.timezone('Asia/Kolkata', v_now)::date <> pg_catalog.timezone('Asia/Kolkata', v_slot_at)::date then
      raise exception 'dose can be marked recorded in error only on the day of the slot'
        using errcode = 'check_violation';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_dose_correction_insert() from public;
revoke all on function ipd.guard_dose_correction_insert() from sahaj_app;
create trigger trg_dose_correction_guard_insert
  before insert on ipd.dose_correction
  for each row execute function ipd.guard_dose_correction_insert();

-- D3. Dose insert guard, replaced in place (same trigger name). Adds: order ACTIVE, or PENDING only for a STAT (ONCE) order with no
-- live given dose yet (then before_verification must be true); order_version = current version; dose_kind = the version's kind;
-- the attempt rule (attempt n > 1 follows the corrected attempt n - 1 of the same slot); a GIVEN dose names its scan method; the
-- undo window is stamped here from the clinic setting in force (undo_until = recorded_at + undo_seconds, default 10).
create or replace function ipd.guard_dose_administration_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_order_status text;
  v_cur_version  integer;
  v_order_prio   text;
  v_drug_alert   boolean;
  v_kind         text;
  v_adm_status   text;
  v_prev_order   uuid;
  v_prev_slot    timestamptz;
  v_prev_attempt smallint;
  v_undo_secs    integer;
begin
  select o.status, o.current_version, o.priority, (d.high_alert is not null)
    into v_order_status, v_cur_version, v_order_prio, v_drug_alert
    from ipd.medicine_order o
    join ipd.drug d on d.key = o.drug_key
   where o.tenant_id = new.tenant_id and o.id = new.order_id and o.admission_id = new.admission_id;
  if not found then
    return new;
  end if;

  select v.kind into v_kind
    from ipd.medicine_order_version v
   where v.tenant_id = new.tenant_id and v.order_id = new.order_id and v.version_no = v_cur_version;

  if v_order_status = 'ACTIVE' then
    if new.before_verification then
      raise exception 'dose of an active order is not before verification'
        using errcode = 'check_violation';
    end if;
  elsif v_order_status = 'PENDING' and v_kind = 'ONCE' and v_order_prio = 'STAT' then
    if not new.before_verification then
      raise exception 'dose before verification must be marked before verification'
        using errcode = 'check_violation';
    end if;
    if new.outcome = 'GIVEN' and exists (
         select 1 from ipd.dose_administration g
          where g.tenant_id = new.tenant_id and g.order_id = new.order_id and g.outcome = 'GIVEN'
            and not exists (select 1 from ipd.dose_correction c where c.tenant_id = g.tenant_id and c.dose_id = g.id)) then
      raise exception 'dose of a STAT order before verification can be given only once'
        using errcode = 'check_violation';
    end if;
  else
    raise exception 'dose can be recorded only for an active medicine order'
      using errcode = 'check_violation';
  end if;

  if new.order_version <> v_cur_version then
    raise exception 'dose must be recorded on the current version of the order'
      using errcode = 'check_violation';
  end if;
  if new.dose_kind is distinct from v_kind then
    raise exception 'dose kind does not match the order version'
      using errcode = 'check_violation';
  end if;

  select a.status into v_adm_status
    from ipd.admission a
   where a.tenant_id = new.tenant_id and a.id = new.admission_id;
  if not found or v_adm_status <> 'ADMITTED' then
    raise exception 'dose can be recorded only while the patient is admitted'
      using errcode = 'check_violation';
  end if;
  if new.high_alert is distinct from v_drug_alert then
    raise exception 'dose high-alert flag does not match the medicine'
      using errcode = 'check_violation';
  end if;

  if new.attempt > 1 then
    select p.order_id, p.slot_at, p.attempt into v_prev_order, v_prev_slot, v_prev_attempt
      from ipd.dose_administration p
     where p.tenant_id = new.tenant_id and p.id = new.supersedes_dose_id;
    if not found or v_prev_order <> new.order_id or v_prev_slot <> new.slot_at or v_prev_attempt <> new.attempt - 1 then
      raise exception 'dose attempt must follow the earlier attempt of the same slot'
        using errcode = 'check_violation';
    end if;
    if not exists (select 1 from ipd.dose_correction c where c.tenant_id = new.tenant_id and c.dose_id = new.supersedes_dose_id) then
      raise exception 'dose can follow an earlier attempt only after that attempt was corrected'
        using errcode = 'check_violation';
    end if;
  end if;

  if new.outcome = 'GIVEN' then
    if new.scan_method is null then
      raise exception 'given dose needs a scan method'
        using errcode = 'check_violation';
    end if;
    select c.undo_seconds into v_undo_secs from ipd.config c where c.tenant_id = new.tenant_id;
    new.undo_until := new.recorded_at + pg_catalog.make_interval(secs => coalesce(v_undo_secs, 10));
  end if;
  return new;
end;
$$;

-- D4. ipd.pharmacist_review: review after a dose given before verification (ADR-027 D6). Not a dose state.
create table if not exists ipd.pharmacist_review (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_pharmacist_review_tenant references platform.tenant (id),
  order_id      uuid not null,
  order_version integer not null
                constraint ck_pharmacist_review_version check (order_version >= 1),
  dose_id       uuid not null,
  created_at    timestamptz not null default clock_timestamp(),
  closed_at     timestamptz,
  closed_by     uuid,
  outcome       text
                constraint ck_pharmacist_review_outcome check (outcome in ('REVIEWED', 'VERIFIED', 'REJECTED', 'DOSE_UNDONE')),
  constraint uq_pharmacist_review_dose unique (tenant_id, dose_id),
  constraint fk_pharmacist_review_order foreign key (tenant_id, order_id)
    references ipd.medicine_order (tenant_id, id),
  constraint fk_pharmacist_review_version foreign key (tenant_id, order_id, order_version)
    references ipd.medicine_order_version (tenant_id, order_id, version_no),
  constraint fk_pharmacist_review_dose foreign key (tenant_id, dose_id)
    references ipd.dose_administration (tenant_id, id),
  constraint ck_pharmacist_review_closed_triple
    check ((closed_at is null) = (outcome is null) and (closed_at is null) = (closed_by is null)),
  constraint ck_pharmacist_review_closed_at check (closed_at is null or closed_at >= created_at)
);
-- Open reviews, oldest first (queue and Safety tab number).
create index if not exists ix_pharmacist_review_tenant_open
  on ipd.pharmacist_review (tenant_id, created_at, id) where closed_at is null;
-- Close by order (a verify or reject closes the order's open reviews).
create index if not exists ix_pharmacist_review_tenant_order
  on ipd.pharmacist_review (tenant_id, order_id);

alter table ipd.pharmacist_review enable row level security;
alter table ipd.pharmacist_review force row level security;
create policy p_pharmacist_review_select on ipd.pharmacist_review
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_pharmacist_review_insert on ipd.pharmacist_review
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_pharmacist_review_update on ipd.pharmacist_review
  for update to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());
revoke all on ipd.pharmacist_review from sahaj_app;
revoke all on ipd.pharmacist_review from public;
grant select on ipd.pharmacist_review to sahaj_app;
grant insert (order_id, order_version, dose_id) on ipd.pharmacist_review to sahaj_app;
grant update (closed_by, outcome) on ipd.pharmacist_review to sahaj_app;   -- closed_at is stamped by the guard

create trigger trg_pharmacist_review_no_delete
  before delete on ipd.pharmacist_review
  for each row execute function platform.deny_audit_mutation();
create trigger trg_pharmacist_review_no_truncate
  before truncate on ipd.pharmacist_review
  for each statement execute function platform.deny_audit_mutation();

-- Insert: the dose is a GIVEN dose before verification, of the same order and version.
create or replace function ipd.guard_pharmacist_review_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_outcome text;
  v_before  boolean;
  v_order   uuid;
  v_version integer;
begin
  select d.outcome, d.before_verification, d.order_id, d.order_version into v_outcome, v_before, v_order, v_version
    from ipd.dose_administration d
   where d.tenant_id = new.tenant_id and d.id = new.dose_id;
  if not found then
    return new;
  end if;
  if v_outcome <> 'GIVEN' or not v_before or v_order <> new.order_id or v_version <> new.order_version then
    raise exception 'pharmacist review belongs to a dose given before verification, on its own order and version'
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_pharmacist_review_insert() from public;
revoke all on function ipd.guard_pharmacist_review_insert() from sahaj_app;
create trigger trg_pharmacist_review_guard_insert
  before insert on ipd.pharmacist_review
  for each row execute function ipd.guard_pharmacist_review_insert();

-- Update: close once (closed_by and outcome from the app; closed_at stamped here).
create or replace function ipd.guard_pharmacist_review_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if (pg_catalog.to_jsonb(new) - 'closed_at' - 'closed_by' - 'outcome')
     is distinct from (pg_catalog.to_jsonb(old) - 'closed_at' - 'closed_by' - 'outcome') then
    raise exception 'pharmacist review cannot be edited'
      using errcode = 'check_violation';
  end if;
  if old.closed_at is not null then
    raise exception 'pharmacist review is already closed'
      using errcode = 'check_violation';
  end if;
  if new.closed_by is null or new.outcome is null then
    raise exception 'pharmacist review needs the closing user and an outcome'
      using errcode = 'check_violation';
  end if;
  new.closed_at := clock_timestamp();
  return new;
end;
$$;
revoke all on function ipd.guard_pharmacist_review_update() from public;
revoke all on function ipd.guard_pharmacist_review_update() from sahaj_app;
create trigger trg_pharmacist_review_guard_update
  before update on ipd.pharmacist_review
  for each row execute function ipd.guard_pharmacist_review_update();

-- ======================================================================================================================
-- E. Comments
-- ======================================================================================================================
comment on column ipd.drug.time_critical is 'SAH-76 (ADR-027 D8): the medicine is time-critical (quality data on late doses). None shipped; a reviewed migration sets it (BR-17).';
comment on column ipd.drug.dose_units is 'SAH-76 (OQ-76-8, Q3): dose units a doctor may pick for this medicine, from the unit list. DEMO content, not clinically reviewed.';
comment on column ipd.drug.route_key is 'SAH-76: route key from the route list (ORAL, IV, IV_INFUSION, IM, SC, SUBLINGUAL, INHALED, TOPICAL, RECTAL, NASOGASTRIC); maps the built route text. DEMO content.';
comment on table ipd.nursing_profile_template is 'SAH-76 (ADR-027 D8): global starter ward profiles (ICU, General ward). A clinic''s ipd.nursing_profile rows are copied from these on its first profile write. Immutable by trigger (insert only, by a later migration); select-only for sahaj_app. Demo content for sign-off.';
comment on table ipd.drug_code is 'SAH-76 (ADR-027 D9): medicine barcode codes (GTIN or hospital) per drug, normalised upper case. Global, shipped EMPTY (go-live condition Q1); loaded by a reviewed migration. Immutable by trigger; select-only for sahaj_app.';
comment on table ipd.nursing_profile is 'SAH-76 (ADR-027 D8): a clinic''s ward profile (medicine verification on or off, vitals interval, optional dose windows). At most 20 per clinic (trigger). Configuration: update allowed, no delete grant. Names unique per clinic, case-insensitive.';
comment on column ipd.ward.nursing_profile_key is 'SAH-76: the ward profile in force for the ward (null = clinic settings). Foreign key (tenant_id, nursing_profile_key).';
comment on column ipd.vitals.due_interval_min is 'SAH-76 (ADR-027 D10): the vitals interval in minutes in force when this set was saved; null on rows saved before SAH-76 (= 240). Next vitals are due at recorded_at + this value.';
comment on column ipd.config.med_verification_required is 'SAH-76: clinic default for the pharmacist check of new and changed medicine orders (a ward profile overrides it). Takes effect for later orders only.';
comment on column ipd.config.undo_seconds is 'SAH-76: the 10-second undo window (1 to 10). Stamped on each dose as undo_until, so a later change never reopens a past window.';
comment on column ipd.medicine_order.current_version is 'SAH-76 (ADR-027 D1): the version number users see as "v2". Grows by 1 with a change (guard trigger); the row of that number exists in ipd.medicine_order_version (checked at commit). Not the row counter "version".';
comment on column ipd.medicine_order.priority is 'SAH-76: priority of the current version (ROUTINE, URGENT, STAT), copied for the queue; equals the current version''s priority (checked at commit).';
comment on column ipd.medicine_order.stop_reason is 'SAH-76: the stop reason (1 to 200 characters), required for a new stop (guard). Null on orders stopped before SAH-76. Clinical text: never logged or audited.';
comment on column ipd.medicine_order.frequency_key is 'Frequency of version 1 (od, bd, tds, q8); null for a PRN or STAT (ONCE) order. The per-version truth is ipd.medicine_order_version.frequency_key. Never changes.';
comment on column ipd.medicine_order.verified_by is 'Last pharmacist who verified any version of this order (the version row holds the verification of each version). Null when no version needed verification.';
comment on table ipd.medicine_order_version is 'SAH-76 (ADR-027 D1): one row per version of a medicine order. Written in the same transaction as the head change. Never edited except two one-way stamps (superseded_at; verified_by with verified_at, REQUIRED only), never deleted. Doses, notices, queries and reviews reference a version. About 1.3 rows per order.';
comment on column ipd.medicine_order_version.dose_amount is 'Dose per administration; null with dose_unit null = "as in the medicine name" (orders before SAH-76 and orders placed through opd.api).';
comment on column ipd.medicine_order_version.indication is 'Clinical text (1 to 200; required for PRN): never logged, audited or put in events.';
comment on column ipd.medicine_order_version.change_reason is 'Clinical text (1 to 200); null exactly on version 1.';
comment on column ipd.medicine_order_version.superseded_at is 'Database time the next version was made; stamped by the guard trigger, once.';
comment on column ipd.medicine_order_version.verification is 'REQUIRED (pharmacist check) or NOT_REQUIRED (ward profile Off at signing or change). Decided once, then stored.';
comment on table ipd.order_hold is 'SAH-76 (ADR-027 D4): hold and resume of an order. One open hold per order (partial unique). order_kind plus one typed order column so later order kinds add a nullable column. Never deleted; only the resume pair is updated, once.';
comment on column ipd.order_hold.hold_reason is 'Clinical text (1 to 200): never logged or audited.';
comment on table ipd.order_notice is 'SAH-76 (ADR-027 D5): a nurse order notice (NEW, CHANGED, HELD, RESUMED, STOPPED) and its acknowledgement (TAP or DOSE). Written in the action''s transaction. Never deleted; only the acknowledged triple is set, once. About 1 to 3 rows per order plus changes.';
comment on table ipd.medicine_order_query is 'SAH-76 (ADR-027 D6): the pharmacist''s question and the doctor''s reply. One open query per order. Clinical text: never logged, audited or put in events. Never deleted; closed once.';
comment on table ipd.pharmacist_review is 'SAH-76 (ADR-027 D6): review after a dose given before verification. One per such dose. Closed once with an outcome. Never deleted.';
comment on table ipd.dose_correction is 'SAH-76 (ADR-027 D3): undo (within undo_until, by the recorder) and recorded in error (same IST day) of a GIVEN dose. Append-only: select and insert only; triggers refuse update, delete, truncate. One per dose.';
comment on column ipd.dose_administration.attempt is 'SAH-76: 1 for the first record of a slot; n + 1 after the earlier attempt was corrected (supersedes_dose_id names it).';
comment on column ipd.dose_administration.order_version is 'SAH-76: the order version the dose was given under; equals the current version at insert (guard). Old rows read 1.';
comment on column ipd.dose_administration.dose_kind is 'SAH-76: SCHEDULED, PRN or ONCE; equals the kind of the order version (guard). For PRN, slot_at is the database minute of recording.';
comment on column ipd.dose_administration.scan_method is 'SAH-76: SCAN or OVERRIDE for a GIVEN dose; null on rows before SAH-76 (read as SCAN) and for not-given outcomes. The guard requires it for a new GIVEN dose.';
comment on column ipd.dose_administration.override_note is 'Clinical text (4 to 200), set exactly for OVERRIDE: never logged or audited.';
comment on column ipd.dose_administration.medicine_scan is 'SAH-76: the normalised medicine code that matched (1 to 64), GIVEN only. Never logged with a mismatch unless it is a code of ipd.drug_code.';
comment on column ipd.dose_administration.before_verification is 'SAH-76: true when the dose was recorded while the STAT order was still in the pharmacist check (guard: only then).';
comment on column ipd.dose_administration.undo_until is 'SAH-76: end of the undo window, stamped by the guard as recorded_at + ipd.config.undo_seconds (default 10). GIVEN only; null on old rows (no undo).';
comment on column ipd.dose_administration.not_given_note is 'SAH-76: clinical text (4 to 200); required when reason_key is OTHER. Never logged or audited.';
comment on function ipd.guard_medicine_order_update() is 'SAH-31, replaced by SAH-76 (ADR-027 D1): the order state machine with QUERIED and ON_HOLD, change (current_version + 1), stop reason, row counter +1 on every update. SQLSTATE 23514, message contains "medicine order".';
comment on function ipd.guard_dose_administration_insert() is 'SAH-32, replaced by SAH-76 (ADR-027 D3, D7): order ACTIVE (or PENDING for a STAT before verification), current version, kind, attempt rule, scan method, undo window stamp. SQLSTATE 23514, message contains "dose".';
comment on function ipd.check_medicine_order_current_version() is 'SAH-76: deferred (commit-time) check that the head points at an existing version with the same priority and a consistent verification.';
