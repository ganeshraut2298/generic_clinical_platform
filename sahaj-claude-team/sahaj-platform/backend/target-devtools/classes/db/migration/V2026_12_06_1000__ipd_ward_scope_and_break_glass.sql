-- SAH-34 · IPD ward scoping and break-glass emergency access, data part (ADR-017 D1, D4, D5, D7; design note section 7).
-- Forward-only and additive: two new tables, one new column with a constant default on ipd.config, one new nullable column
-- with a check and a composite key on ipd.clinical_event. No existing row is updated, nothing is dropped or renamed.
--   34A  ipd.ward_assignment        standing nurse-to-ward assignments (rows are ended, never deleted)
--   34A  ipd.config.nurse_ward_scope clinic switch, off for every clinic at deploy
--   34E  ipd.emergency_access_grant  one row per break-glass grant, 30 minutes on the database clock
--   34F  (same table)                the owner's review is the only change a grant can ever get
--   34E  ipd.clinical_event.grant_id the "Reviewed emergency access" line finds the grantee through this
-- Cross-module references (membership_id, user_id, assigned_by, removed_by, reviewed_by) are logical: no foreign key
-- (docs/database/tenant-fk-survey.md; IPD actor convention: user ids). Same-schema keys are composite (tenant_id, x_id).

-- 1. ipd.ward_assignment
create table if not exists ipd.ward_assignment (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_ward_assignment_tenant references platform.tenant (id),
  ward_id       uuid not null,
  membership_id uuid not null,
  assigned_at   timestamptz not null default clock_timestamp(),
  assigned_by   uuid not null,
  removed_at    timestamptz,
  removed_by    uuid,
  version       bigint not null default 0,
  constraint fk_ward_assignment_ward foreign key (tenant_id, ward_id) references ipd.ward (tenant_id, id),
  constraint ck_ward_assignment_removed check ((removed_at is null) = (removed_by is null)),
  constraint ck_ward_assignment_removed_order check (removed_at is null or removed_at >= assigned_at)
);
-- One live row per nurse per ward (backstop behind the clinic IPD advisory lock). Also serves "live nurses of a ward"
-- and "every live assignment of the clinic" (leading columns tenant_id, ward_id).
create unique index if not exists ux_ward_assignment_live
  on ipd.ward_assignment (tenant_id, ward_id, membership_id) where removed_at is null;
-- Scope build: the live wards of one membership, index-only (ward_id is included).
create index if not exists ix_ward_assignment_member_live
  on ipd.ward_assignment (tenant_id, membership_id) include (ward_id) where removed_at is null;

-- 2. ipd.config.nurse_ward_scope: constant default, no table rewrite on PostgreSQL 16, every clinic starts off.
-- ipd.config has table-level select, insert, update for sahaj_app (SAH-22): the new column is covered.
alter table ipd.config add column if not exists nurse_ward_scope boolean not null default false;

-- 3. ipd.emergency_access_grant
create table if not exists ipd.emergency_access_grant (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_emergency_access_grant_tenant references platform.tenant (id),
  admission_id  uuid not null,
  -- the bed at the time of the grant (the owner's panel shows this bed)
  bed_id        uuid not null,
  user_id       uuid not null,
  membership_id uuid not null,
  role          text not null
                constraint ck_emergency_access_grant_role check (role in ('NURSE', 'VISITING_DOCTOR')),
  reason_key    text not null
                constraint ck_emergency_access_grant_reason check (reason_key in ('DETERIORATING', 'COVERING_BREAK', 'DUTY_DOCTOR_ASKED')),
  -- stamped by the BEFORE INSERT trigger; not insertable by the app
  opened_at     timestamptz not null,
  expires_at    timestamptz not null,
  reviewed_by   uuid,
  reviewed_at   timestamptz,
  version       bigint not null default 0,
  constraint uq_emergency_access_grant_tenant_id unique (tenant_id, id),
  constraint fk_emergency_access_grant_admission foreign key (tenant_id, admission_id) references ipd.admission (tenant_id, id),
  constraint fk_emergency_access_grant_bed foreign key (tenant_id, bed_id) references ipd.bed (tenant_id, id),
  constraint ck_emergency_access_grant_expiry check (expires_at = opened_at + interval '30 minutes'),
  constraint ck_emergency_access_grant_reviewed check ((reviewed_by is null) = (reviewed_at is null)),
  constraint ck_emergency_access_grant_not_self_review check (reviewed_by is null or reviewed_by <> user_id),
  constraint ck_emergency_access_grant_reviewed_order check (reviewed_at is null or reviewed_at >= opened_at)
);
-- Scope build (and the locked recheck): live grants of one membership, newest expiry first; admission_id and role are
-- carried so the probe is index-only.
create index if not exists ix_emergency_access_grant_member_expires
  on ipd.emergency_access_grant (tenant_id, membership_id, expires_at desc) include (admission_id, role);
-- Owner's review: the count, the first page and the keyset (opened_at, id) of unreviewed grants. Shrinks as grants are reviewed.
create index if not exists ix_emergency_access_grant_unreviewed
  on ipd.emergency_access_grant (tenant_id, opened_at, id) where reviewed_at is null;

-- 4. RLS (ENABLE and FORCE). Select, insert and update policies; no delete policy.
alter table ipd.ward_assignment enable row level security;
alter table ipd.ward_assignment force row level security;
alter table ipd.emergency_access_grant enable row level security;
alter table ipd.emergency_access_grant force row level security;

create policy p_ward_assignment_select on ipd.ward_assignment
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_ward_assignment_insert on ipd.ward_assignment
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_ward_assignment_update on ipd.ward_assignment
  for update to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

create policy p_emergency_access_grant_select on ipd.emergency_access_grant
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_emergency_access_grant_insert on ipd.emergency_access_grant
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_emergency_access_grant_update on ipd.emergency_access_grant
  for update to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- 5. Grants. Schema default privileges give sahaj_app every command, so take everything away first.
-- Column-level insert and update: id, tenant_id, assigned_at, opened_at, expires_at and reviewed_at are never written
-- by the app (defaults and triggers own them). No delete, truncate, references or trigger privilege.
revoke all on ipd.ward_assignment, ipd.emergency_access_grant from sahaj_app;
revoke all on ipd.ward_assignment, ipd.emergency_access_grant from public;

grant select on ipd.ward_assignment to sahaj_app;
grant insert (ward_id, membership_id, assigned_by) on ipd.ward_assignment to sahaj_app;
grant update (removed_at, removed_by, version) on ipd.ward_assignment to sahaj_app;

grant select on ipd.emergency_access_grant to sahaj_app;
grant insert (admission_id, bed_id, user_id, membership_id, role, reason_key) on ipd.emergency_access_grant to sahaj_app;
grant update (reviewed_by, version) on ipd.emergency_access_grant to sahaj_app;

-- 6. Triggers. All functions: SECURITY INVOKER, fixed search_path, no table read, revoked from public and sahaj_app.
-- 6a. ward_assignment: an assignment is ended once and never changes again.
-- SQLSTATE 23514 (check_violation), constraint name in the error for the Java mapping.
create or replace function ipd.guard_ward_assignment_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if new.id is distinct from old.id
     or new.tenant_id is distinct from old.tenant_id
     or new.ward_id is distinct from old.ward_id
     or new.membership_id is distinct from old.membership_id
     or new.assigned_at is distinct from old.assigned_at
     or new.assigned_by is distinct from old.assigned_by then
    raise exception 'ward assignment is fixed: only the end of the assignment can be recorded'
      using errcode = 'check_violation', constraint = 'trg_ward_assignment_guard_update';
  end if;
  if old.removed_at is not null or old.removed_by is not null then
    raise exception 'ward assignment is already ended (update refused)'
      using errcode = 'check_violation', constraint = 'trg_ward_assignment_guard_update';
  end if;
  if new.removed_by is null then
    raise exception 'ward assignment update must end the assignment (removed_by is required)'
      using errcode = 'check_violation', constraint = 'trg_ward_assignment_guard_update';
  end if;
  -- the end time is the database clock, whatever the caller sent
  new.removed_at := clock_timestamp();
  return new;
end;
$$;
revoke all on function ipd.guard_ward_assignment_update() from public;
revoke all on function ipd.guard_ward_assignment_update() from sahaj_app;

create trigger trg_ward_assignment_guard_update
  before update on ipd.ward_assignment
  for each row execute function ipd.guard_ward_assignment_update();
create trigger trg_ward_assignment_no_delete
  before delete on ipd.ward_assignment
  for each row execute function platform.deny_audit_mutation();
create trigger trg_ward_assignment_no_truncate
  before truncate on ipd.ward_assignment
  for each statement execute function platform.deny_audit_mutation();

-- 6b. emergency_access_grant: the database clock sets the 30 minutes; a new grant is never already reviewed.
create or replace function ipd.stamp_emergency_access_grant()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if new.reviewed_by is not null or new.reviewed_at is not null then
    raise exception 'emergency access grant cannot be created already reviewed'
      using errcode = 'check_violation', constraint = 'trg_emergency_access_grant_stamp';
  end if;
  new.opened_at := clock_timestamp();
  new.expires_at := new.opened_at + interval '30 minutes';
  return new;
end;
$$;
revoke all on function ipd.stamp_emergency_access_grant() from public;
revoke all on function ipd.stamp_emergency_access_grant() from sahaj_app;

create trigger trg_emergency_access_grant_stamp
  before insert on ipd.emergency_access_grant
  for each row execute function ipd.stamp_emergency_access_grant();

-- 6c. emergency_access_grant: the review is the only change, made once, never by the grantee, never cleared.
-- SQLSTATE 23514. reviewed_at is the database clock, whatever the caller sent.
create or replace function ipd.guard_emergency_access_grant_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if new.id is distinct from old.id
     or new.tenant_id is distinct from old.tenant_id
     or new.admission_id is distinct from old.admission_id
     or new.bed_id is distinct from old.bed_id
     or new.user_id is distinct from old.user_id
     or new.membership_id is distinct from old.membership_id
     or new.role is distinct from old.role
     or new.reason_key is distinct from old.reason_key
     or new.opened_at is distinct from old.opened_at
     or new.expires_at is distinct from old.expires_at then
    raise exception 'emergency access grant is fixed: only the owner review can be recorded'
      using errcode = 'check_violation', constraint = 'trg_emergency_access_grant_guard_update';
  end if;
  if old.reviewed_by is not null or old.reviewed_at is not null then
    raise exception 'emergency access grant is already reviewed (update refused)'
      using errcode = 'check_violation', constraint = 'trg_emergency_access_grant_guard_update';
  end if;
  if new.reviewed_by is null then
    raise exception 'emergency access grant update must record the review (reviewed_by is required)'
      using errcode = 'check_violation', constraint = 'trg_emergency_access_grant_guard_update';
  end if;
  if new.reviewed_by = old.user_id then
    raise exception 'the person who opened emergency access cannot review it'
      using errcode = 'check_violation', constraint = 'ck_emergency_access_grant_not_self_review';
  end if;
  new.reviewed_at := clock_timestamp();
  return new;
end;
$$;
revoke all on function ipd.guard_emergency_access_grant_update() from public;
revoke all on function ipd.guard_emergency_access_grant_update() from sahaj_app;

create trigger trg_emergency_access_grant_guard_update
  before update on ipd.emergency_access_grant
  for each row execute function ipd.guard_emergency_access_grant_update();
create trigger trg_emergency_access_grant_no_delete
  before delete on ipd.emergency_access_grant
  for each row execute function platform.deny_audit_mutation();
create trigger trg_emergency_access_grant_no_truncate
  before truncate on ipd.emergency_access_grant
  for each statement execute function platform.deny_audit_mutation();

-- 7. ipd.clinical_event.grant_id (nullable, bg events only). Every existing row has grant_id null, so both constraints
-- validate without change. match simple: a null grant_id skips the key check. Column-level insert grant is additive.
-- Kept last so the table-level lock on ipd.clinical_event is taken as late as possible in this transaction.
alter table ipd.clinical_event add column if not exists grant_id uuid;
alter table ipd.clinical_event
  add constraint ck_clinical_event_grant check (grant_id is null or kind = 'bg');
alter table ipd.clinical_event
  add constraint fk_clinical_event_grant foreign key (tenant_id, grant_id)
  references ipd.emergency_access_grant (tenant_id, id) match simple;
grant insert (grant_id) on ipd.clinical_event to sahaj_app;

comment on table ipd.ward_assignment is 'IPD standing nurse-to-ward assignment (SAH-34 34A, ADR-017 D7). Many nurses per ward, many wards per nurse. One live row per nurse per ward (partial unique index). Ended, never deleted: removed_at is stamped by the guard trigger from the database clock, once; delete and truncate refused (trigger, SQLSTATE 42501). membership_id has no foreign key (cross-module): the Java service checks it through TeamMemberPort. Holds ids only: no name. Read at scope-build time only when ipd.config.nurse_ward_scope is on.';
comment on column ipd.ward_assignment.membership_id is 'platform.membership id of a NURSE (logical reference, no foreign key).';
comment on column ipd.ward_assignment.assigned_by is 'Signed-in user id (IPD actor convention).';
comment on column ipd.ward_assignment.removed_by is 'Signed-in user id (IPD actor convention). Set together with removed_at, once.';
comment on column ipd.config.nurse_ward_scope is 'SAH-34 34A: when true, a NURSE sees only the patients of her live ward assignments (and of her active emergency access grants). Default false: every clinic starts with all patients visible. Changed by PUT /ward-scope (OWNER).';
comment on table ipd.emergency_access_grant is 'IPD break-glass emergency access (SAH-34 34E/34F, ADR-017 D4, D5). One row per grant: 30 minutes on the database clock (opened_at and expires_at are stamped by the BEFORE INSERT trigger; the app cannot write them). No extension, no early end. The only change a grant can get is the owner review, once, never by the grantee (guard trigger; reviewed_at stamped by the database); delete and truncate refused (trigger, SQLSTATE 42501). Holds ids and fixed keys only: no name, note or free text. Part of the in-patient access trail: never deleted in phase 1.';
comment on column ipd.emergency_access_grant.bed_id is 'Bed of the admission at the time of the grant (the owner panel shows this bed even after a transfer).';
comment on column ipd.emergency_access_grant.user_id is 'Grantee signed-in user id (IPD actor convention); created_by of this row.';
comment on column ipd.emergency_access_grant.membership_id is 'Grantee platform.membership id (logical reference, no foreign key). The scope build matches on this.';
comment on column ipd.emergency_access_grant.role is 'Grantee membership role at the time of the grant: NURSE (phase 1) or VISITING_DOCTOR (34D, later). A grant counts only for the same role.';
comment on column ipd.emergency_access_grant.reason_key is 'DETERIORATING, COVERING_BREAK or DUTY_DOCTOR_ASKED. Labels live in Java (EmergencyAccessReason).';
comment on column ipd.emergency_access_grant.opened_at is 'Database clock at insert (clock_timestamp()); the row''s created_at.';
comment on column ipd.emergency_access_grant.expires_at is 'opened_at + 30 minutes (check and trigger). A grant is live while expires_at > clock_timestamp() and the admission is ADMITTED.';
comment on column ipd.emergency_access_grant.reviewed_by is 'Owner user id; set once, never equal to user_id.';
comment on column ipd.emergency_access_grant.reviewed_at is 'Database clock when reviewed_by was set (guard trigger).';
comment on column ipd.clinical_event.grant_id is 'SAH-34: the emergency access grant a bg event belongs to (opened or reviewed). Null for every other event. The trail reads the grantee name through this key at display time; no name is stored.';
