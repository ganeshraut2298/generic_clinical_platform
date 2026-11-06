-- SAH-28 · IPD emergency queue, triage and Emergency setup (ADR-005). Forward-only.
-- Same-schema FKs are composite (tenant_id, id). The case table is mutable only through column-level grants and a
-- state guard; the triage log is append-only like ipd.deposit / ipd.bed_transfer. Nothing here is ever deleted.

-- 1. Helper for the triage-target checks (a CHECK cannot hold a subquery). Immutable, fixed search_path.
-- True when the array is one-dimensional, starts at index 1, has exactly p_count elements, none null, each 0..240 and a
-- multiple of 5.
create or replace function ipd.triage_targets_ok(p_targets smallint[], p_count integer)
returns boolean
language sql
immutable
parallel safe
set search_path = pg_catalog
as $$
  select p_targets is not null
     and pg_catalog.array_ndims(p_targets) = 1
     and pg_catalog.array_lower(p_targets, 1) = 1
     and pg_catalog.array_length(p_targets, 1) = p_count
     and not exists (select 1
                     from pg_catalog.unnest(p_targets) as t(v)
                     where t.v is null or t.v < 0 or t.v > 240 or t.v % 5 <> 0)
$$;

-- 2. Emergency setup on ipd.config (lazy row; defaults apply to existing rows).
-- ipd.config already has table-level select, insert, update for sahaj_app (SAH-22): the new columns are covered.
alter table ipd.config
  add column if not exists triage_scale smallint not null default 3
    constraint ck_ipd_config_triage_scale check (triage_scale in (3, 5)),
  add column if not exists triage_targets_3 smallint[] not null default '{5,30,120}'::smallint[]
    constraint ck_ipd_config_triage_targets_3 check (ipd.triage_targets_ok(triage_targets_3, 3)),
  add column if not exists triage_targets_5 smallint[] not null default '{0,10,30,60,120}'::smallint[]
    constraint ck_ipd_config_triage_targets_5 check (ipd.triage_targets_ok(triage_targets_5, 5)),
  add column if not exists er_bays smallint not null default 2
    constraint ck_ipd_config_er_bays check (er_bays between 0 and 10),
  add column if not exists unknown_patient_registration boolean not null default true;

-- 3. ipd.emergency_case: one row per arrival in the emergency room. Personal data is typed at the desk; patient_id
-- stays null until the case is admitted (OQ-28-1).
create table if not exists ipd.emergency_case (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null default platform.current_tenant()
                       constraint fk_emergency_case_tenant references platform.tenant (id),
  patient_id           uuid,
  display_name         text not null
                       constraint ck_emergency_case_display_name check (char_length(display_name) between 1 and 80
                                                                       and btrim(display_name) <> ''),
  age                  smallint
                       constraint ck_emergency_case_age check (age between 0 and 120),
  sex                  text not null
                       constraint ck_emergency_case_sex check (sex in ('M', 'F', 'O')),
  mobile               text
                       constraint ck_emergency_case_mobile check (mobile ~ '^[6-9][0-9]{9}$'),
  complaint            text not null
                       constraint ck_emergency_case_complaint check (char_length(complaint) between 1 and 200
                                                                    and btrim(complaint) <> ''),
  identity_pending     boolean not null default false,
  level                smallint not null
                       constraint ck_emergency_case_level check (level between 1 and 5),
  bay                  text
                       constraint ck_emergency_case_bay check (bay ~ '^ER-([1-9]|10)$'),
  status               text not null default 'WAITING'
                       constraint ck_emergency_case_status check (status in ('WAITING', 'SEEN', 'ADMITTED', 'RELEASED', 'REFERRED')),
  arrived_at           timestamptz not null default now(),
  registered_by        uuid not null,
  seen_at              timestamptz,
  seen_by              uuid,
  closed_at            timestamptz,
  closed_by            uuid,
  admission_id         uuid,
  client_key           uuid not null,
  version              integer not null default 0,
  constraint uq_emergency_case_tenant_id unique (tenant_id, id),
  constraint uq_emergency_case_tenant_client_key unique (tenant_id, client_key),
  constraint fk_emergency_case_patient foreign key (tenant_id, patient_id) references patient.patient (tenant_id, id),
  constraint fk_emergency_case_admission foreign key (tenant_id, admission_id) references ipd.admission (tenant_id, id),
  -- seen_at and seen_by are set together; SEEN needs them, WAITING has none (a closed case keeps them if it was seen)
  constraint ck_emergency_case_seen_pair check ((seen_at is null) = (seen_by is null)),
  constraint ck_emergency_case_seen check (status <> 'SEEN' or seen_at is not null),
  constraint ck_emergency_case_waiting_unseen check (status <> 'WAITING' or seen_at is null),
  -- open cases (WAITING, SEEN) have no closure; closed cases (ADMITTED, RELEASED, REFERRED) have both
  constraint ck_emergency_case_closed check (
    case when status in ('WAITING', 'SEEN') then closed_at is null and closed_by is null
         else closed_at is not null and closed_by is not null end),
  -- ADMITTED means admission and patient are linked; only an ADMITTED case has an admission
  constraint ck_emergency_case_admitted check (
    (status = 'ADMITTED') = (admission_id is not null)
    and (status <> 'ADMITTED' or patient_id is not null))
);
-- The queue (open cases of one clinic, ordered by level then arrival in Java), the tile counts and the menu badge.
create index if not exists ix_emergency_case_tenant_status_arrived
  on ipd.emergency_case (tenant_id, status, arrived_at);
-- A bay is held by at most one open case (two simultaneous red arrivals never share a bay). Closed rows keep the bay as history.
create unique index if not exists ux_emergency_case_tenant_bay_open
  on ipd.emergency_case (tenant_id, bay) where bay is not null and status in ('WAITING', 'SEEN');
-- An admission is the target of at most one case; also serves "case of this admission".
create unique index if not exists ux_emergency_case_tenant_admission
  on ipd.emergency_case (tenant_id, admission_id) where admission_id is not null;

-- 4. ipd.emergency_triage_event (append-only): registered level, each re-triage and each scale-change mapping.
create table if not exists ipd.emergency_triage_event (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_emergency_triage_event_tenant references platform.tenant (id),
  case_id       uuid not null,
  from_level    smallint
                constraint ck_emergency_triage_event_from_level check (from_level between 1 and 5),
  level         smallint not null
                constraint ck_emergency_triage_event_level check (level between 1 and 5),
  scale         smallint not null
                constraint ck_emergency_triage_event_scale check (scale in (3, 5)),
  reason        text not null
                constraint ck_emergency_triage_event_reason check (reason in ('REGISTERED', 'RETRIAGED', 'SCALE_CHANGED')),
  recorded_at   timestamptz not null default now(),
  recorded_by   uuid not null,
  constraint uq_emergency_triage_event_tenant_id unique (tenant_id, id),
  constraint fk_emergency_triage_event_case foreign key (tenant_id, case_id) references ipd.emergency_case (tenant_id, id),
  constraint ck_emergency_triage_event_level_scale check (level <= scale),
  constraint ck_emergency_triage_event_registered check ((reason = 'REGISTERED') = (from_level is null))
);
-- Level history of one case, oldest first.
create index if not exists ix_emergency_triage_event_tenant_case_recorded
  on ipd.emergency_triage_event (tenant_id, case_id, recorded_at);

-- 5. RLS (ENABLE and FORCE). The case table has select, insert and update policies and no delete policy.
alter table ipd.emergency_case enable row level security;
alter table ipd.emergency_case force row level security;
alter table ipd.emergency_triage_event enable row level security;
alter table ipd.emergency_triage_event force row level security;

create policy p_emergency_case_select on ipd.emergency_case
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_emergency_case_insert on ipd.emergency_case
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_emergency_case_update on ipd.emergency_case
  for update to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());
create policy p_emergency_triage_event_select on ipd.emergency_triage_event
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_emergency_triage_event_insert on ipd.emergency_triage_event
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());

-- 6. Grants. Column-level insert so id, tenant_id, status, arrived_at and version keep their defaults (a case always
-- starts WAITING, version 0, at the database clock). bay is insert-only: no update grant, so a bay never moves.
-- patient_id is not insertable: it is set only when the case is admitted. No delete.
revoke all on ipd.emergency_case, ipd.emergency_triage_event from sahaj_app;
revoke all on ipd.emergency_case, ipd.emergency_triage_event from public;

grant select on ipd.emergency_case to sahaj_app;
grant insert (display_name, age, sex, mobile, complaint, identity_pending, level, bay, registered_by, client_key)
  on ipd.emergency_case to sahaj_app;
grant update (level, status, seen_at, seen_by, closed_at, closed_by, admission_id, patient_id, version)
  on ipd.emergency_case to sahaj_app;

grant select on ipd.emergency_triage_event to sahaj_app;
grant insert (case_id, from_level, level, scale, reason, recorded_by) on ipd.emergency_triage_event to sahaj_app;

-- 7. Triggers.
-- 7a. Case: no delete and no truncate (privilege and trigger), through the shared denial function.
create trigger trg_emergency_case_no_delete
  before delete on ipd.emergency_case
  for each row execute function platform.deny_audit_mutation();
create trigger trg_emergency_case_no_truncate
  before truncate on ipd.emergency_case
  for each statement execute function platform.deny_audit_mutation();

-- 7b. Case: state guard (backstop for the Java checks). A closed case (ADMITTED, RELEASED, REFERRED) never changes
-- again, and a SEEN case never goes back to WAITING. SECURITY INVOKER, fixed search_path. SQLSTATE 23514.
create or replace function ipd.guard_emergency_case_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if old.status in ('ADMITTED', 'RELEASED', 'REFERRED') then
    raise exception 'emergency case is closed (update refused)'
      using errcode = 'check_violation';
  end if;
  if old.status = 'SEEN' and new.status = 'WAITING' then
    raise exception 'emergency case cannot go back to waiting'
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_emergency_case_update() from public;
revoke all on function ipd.guard_emergency_case_update() from sahaj_app;

create trigger trg_emergency_case_guard_update
  before update on ipd.emergency_case
  for each row execute function ipd.guard_emergency_case_update();

-- 7c. Triage log: append-only (update, delete, truncate denied even for a role that gets a privilege later).
create trigger trg_emergency_triage_event_no_update_delete
  before update or delete on ipd.emergency_triage_event
  for each row execute function platform.deny_audit_mutation();
create trigger trg_emergency_triage_event_no_truncate
  before truncate on ipd.emergency_triage_event
  for each statement execute function platform.deny_audit_mutation();

-- Grants on existing tables: none needed. sahaj_app already holds insert (..., source, ...) on ipd.admission (SAH-23), so
-- the admit hand-off inserts source EMERGENCY. The linking only updates ipd.emergency_case. The bed guard
-- trg_admission_guard_bed_change (V2026_11_19) fires only on an update of ipd.admission.bed_id, which an admit
-- (an insert) never does.

comment on function ipd.triage_targets_ok(smallint[], integer) is 'SAH-28: true when the array has exactly p_count elements (1-dimensional, none null), each 0..240 and a multiple of 5.';
comment on function ipd.guard_emergency_case_update() is 'SAH-28 guard: a closed emergency case (ADMITTED, RELEASED, REFERRED) is never updated, and SEEN never returns to WAITING. SQLSTATE 23514.';
comment on table ipd.emergency_case is 'IPD emergency arrival and queue state (SAH-28). Never deleted (privilege and trigger). display_name, age, mobile and complaint are personal data shown on screen only: never logged, never in audit text. bay is insert-only (no update grant) and stays on closed rows as history; it is held only while the case is WAITING or SEEN. patient_id and admission_id are set only when ADMITTED.';
comment on table ipd.emergency_triage_event is 'Level history of an emergency case (SAH-28). Append-only: no update, delete or truncate (privilege and trigger). REGISTERED has no from_level; SCALE_CHANGED rows come from a change of the triage scale in Emergency setup.';
comment on column ipd.emergency_case.client_key is 'SAH-28: client-generated key; a repeat of the same key returns the first case (no double registration).';
comment on column ipd.config.triage_scale is 'SAH-28: 3 (colours) or 5 (levels).';
comment on column ipd.config.triage_targets_3 is 'SAH-28: target minutes to see a doctor per level for the 3-colour scale (3 values, 0..240, step 5, 0 = now).';
comment on column ipd.config.triage_targets_5 is 'SAH-28: target minutes per level for the 5-level scale (5 values, 0..240, step 5, 0 = now).';
comment on column ipd.config.er_bays is 'SAH-28: number of emergency bays, 0..10 (ER-1 to ER-n).';
comment on column ipd.config.unknown_patient_registration is 'SAH-28: allow "Name not known yet" at quick registration.';
