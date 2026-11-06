-- SAH-31 (ADR-010, DEC-017 Q1): platform.staff_pin, the personal 4-digit staff PIN. One row per membership (a person in
-- one clinic). The row holds only a salted BCrypt hash of an HMAC with the server pepper, never the PIN, plus the lockout
-- counters. Forward-only and additive: no existing table changes, and the previous app version never touches the table.
-- The decisions (D1 to D14) are in docs/architecture/adr/ADR-010-platform-staff-pin.md; the design note is
-- docs/database/SAH-31-pin.md.

-- 1. The table. Tenant-owned. No delete: the row lives as long as the membership; a reset sets pin_hash to null.
create table if not exists platform.staff_pin (
  membership_id          uuid not null
                         constraint pk_staff_pin primary key,
  tenant_id              uuid not null default platform.current_tenant()
                         constraint fk_staff_pin_tenant references platform.tenant (id),
  pin_hash               text
                         constraint ck_staff_pin_hash_format
                         check (pin_hash ~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$'),
  pepper_version         smallint not null default 1
                         constraint ck_staff_pin_pepper_version check (pepper_version >= 1),
  failed_count           smallint not null default 0
                         constraint ck_staff_pin_failed_count check (failed_count between 0 and 4),
  locked_until           timestamptz,
  lock_count             smallint not null default 0
                         constraint ck_staff_pin_lock_count check (lock_count between 0 and 2),
  lock_window_started_at timestamptz,
  set_at                 timestamptz,
  not_set_reason         text
                         constraint ck_staff_pin_not_set_reason check (not_set_reason in ('RESET_BY_OWNER', 'TOO_MANY_LOCKS')),
  reset_at               timestamptz,
  reset_by               uuid,
  created_at             timestamptz not null default now(),
  created_by             uuid,
  updated_at             timestamptz,
  updated_by             uuid,
  version                bigint not null default 0,
  -- a membership of another clinic can never be named: both columns must match one membership row
  constraint fk_staff_pin_membership foreign key (tenant_id, membership_id)
    references platform.membership (tenant_id, id),
  -- tenant-first unique index (also the lookup for the team list and the checklist count)
  constraint uq_staff_pin_tenant_membership unique (tenant_id, membership_id),
  -- the lock window exists exactly when at least one lock was counted
  constraint ck_staff_pin_lock_window check ((lock_count = 0) = (lock_window_started_at is null)),
  -- the time of the current PIN exists exactly when a PIN is set
  constraint ck_staff_pin_set_at check ((pin_hash is null) = (set_at is null)),
  -- a reason is kept only while the PIN is off
  constraint ck_staff_pin_reason_only_when_off check (pin_hash is null or not_set_reason is null),
  constraint ck_staff_pin_reset_pair check ((reset_at is null) = (reset_by is null)),
  -- with no PIN there is nothing to count or lock
  constraint ck_staff_pin_cleared_when_off check (
    pin_hash is not null or (failed_count = 0 and locked_until is null and lock_count = 0))
);

-- 2. Row level security (ENABLE and FORCE): select, insert and update for sahaj_app. No delete policy.
alter table platform.staff_pin enable row level security;
alter table platform.staff_pin force row level security;
create policy p_staff_pin_select on platform.staff_pin
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_staff_pin_insert on platform.staff_pin
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_staff_pin_update on platform.staff_pin
  for update to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- 3. Grants (sahaj_app). Insert: tenant_id (default platform.current_tenant()), failed_count, lock_count, version,
-- created_at and the reset columns keep their defaults. Update: only the columns a transition changes; no delete, no
-- truncate. membership_id, tenant_id and created_* can never be updated.
revoke all on platform.staff_pin from sahaj_app;
revoke all on platform.staff_pin from public;
grant select on platform.staff_pin to sahaj_app;
grant insert (membership_id, pin_hash, pepper_version, set_at, created_by) on platform.staff_pin to sahaj_app;
grant update (pin_hash, pepper_version, failed_count, locked_until, lock_count, lock_window_started_at, set_at,
              not_set_reason, reset_at, reset_by, updated_at, updated_by, version)
  on platform.staff_pin to sahaj_app;

-- 4. No delete and no truncate for any role, the owner included (SQLSTATE 42501). Same shared function as the append-only
-- tables; its message reads "platform.staff_pin is append-only (DELETE refused)".
create trigger trg_staff_pin_no_delete
  before delete on platform.staff_pin
  for each row execute function platform.deny_audit_mutation();
create trigger trg_staff_pin_no_truncate
  before truncate on platform.staff_pin
  for each statement execute function platform.deny_audit_mutation();

-- 5. Insert guard: a row is created only together with a PIN (a reset or a switch-off needs an existing row), and set_at
-- is the database time, whatever the caller sent. SECURITY INVOKER, fixed search_path, SQLSTATE 23514, messages contain
-- "staff pin".
create or replace function platform.guard_staff_pin_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if new.pin_hash is null then
    raise exception 'staff pin row is created only together with a PIN'
      using errcode = 'check_violation';
  end if;
  new.set_at := clock_timestamp();
  return new;
end;
$$;
revoke all on function platform.guard_staff_pin_insert() from public;
revoke all on function platform.guard_staff_pin_insert() from sahaj_app;
create trigger trg_staff_pin_guard_insert
  before insert on platform.staff_pin
  for each row execute function platform.guard_staff_pin_insert();

-- 6. Update guard (ADR-010 D5, D7, D8): the lockout state machine. SECURITY INVOKER, fixed search_path, SQLSTATE 23514,
-- every message contains "staff pin". Every update is exactly one of these (the table checks run after the trigger):
--   SET or CHANGE a PIN  pin_hash is new (or was null): counters, lock window and locked_until cleared; reset_at/reset_by
--                        kept; trigger stamps set_at.
--   OWNER RESET          pin_hash -> null, not_set_reason RESET_BY_OWNER, reset_by set; trigger stamps reset_at. Any
--                        earlier state (set or locked). A row that is already off is refused (nothing to reset).
--   THIRD LOCK           pin_hash -> null, not_set_reason TOO_MANY_LOCKS, counters cleared. Only from failed_count 4,
--                        lock_count 2 inside the 24-hour window, not currently locked.
--   WRONG TRY            same pin_hash; failed_count + 1 (the check caps it at 4); not while locked.
--   RIGHT PIN            same pin_hash; failed_count back to 0 and nothing else; not while locked.
--   LOCK (5th wrong)     same pin_hash; from failed_count 4: failed_count 0 and lock_count + 1 (1 when lock_count is 0
--                        or the window started 24 hours ago or more); trigger stamps locked_until = now + 15 minutes
--                        and the window start (a new window, or the old one kept). The 3rd lock in a window must be the
--                        switch-off above, not a lock.
-- Always: version = old + 1; id, tenant, created_* never change; updated_at = database time; pepper_version never goes
-- down and changes only with a new PIN. All times are clock_timestamp(), never a value the caller sent.
create or replace function platform.guard_staff_pin_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_now     timestamptz := clock_timestamp();
  v_restart boolean;
  v_expected integer;
begin
  if new.membership_id is distinct from old.membership_id
     or new.tenant_id is distinct from old.tenant_id
     or new.created_at is distinct from old.created_at
     or new.created_by is distinct from old.created_by then
    raise exception 'staff pin identity cannot change'
      using errcode = 'check_violation';
  end if;

  if new.version is distinct from old.version + 1 then
    raise exception 'staff pin version must go up by exactly one with every change'
      using errcode = 'check_violation';
  end if;

  if new.pepper_version < old.pepper_version then
    raise exception 'staff pin pepper version cannot go down'
      using errcode = 'check_violation';
  end if;

  if new.pin_hash is null then
    -- switch-off: owner reset, or the third lock
    if old.pin_hash is null then
      raise exception 'staff pin is already off (update refused)'
        using errcode = 'check_violation';
    end if;
    if new.pepper_version is distinct from old.pepper_version then
      raise exception 'staff pin pepper version changes only with a new PIN'
        using errcode = 'check_violation';
    end if;
    if new.not_set_reason = 'RESET_BY_OWNER' then
      if new.reset_by is null then
        raise exception 'staff pin reset needs the resetting user'
          using errcode = 'check_violation';
      end if;
      new.reset_at := v_now;
    elsif new.not_set_reason = 'TOO_MANY_LOCKS' then
      if old.failed_count <> 4
         or old.lock_count <> 2
         or old.lock_window_started_at is null
         or old.lock_window_started_at <= v_now - interval '24 hours'
         or (old.locked_until is not null and old.locked_until > v_now) then
        raise exception 'staff pin is switched off after locks only at the third lock within 24 hours'
          using errcode = 'check_violation';
      end if;
      if new.reset_at is distinct from old.reset_at or new.reset_by is distinct from old.reset_by then
        raise exception 'staff pin reset record cannot change when locks switch the PIN off'
          using errcode = 'check_violation';
      end if;
    else
      raise exception 'staff pin switch-off needs a reason'
        using errcode = 'check_violation';
    end if;

  elsif old.pin_hash is null or new.pin_hash is distinct from old.pin_hash then
    -- a new PIN (first, after a switch-off, or a change): everything counted starts again
    if new.failed_count <> 0 or new.locked_until is not null
       or new.lock_count <> 0 or new.lock_window_started_at is not null then
      raise exception 'staff pin counters must be cleared when a new PIN is set'
        using errcode = 'check_violation';
    end if;
    if new.reset_at is distinct from old.reset_at or new.reset_by is distinct from old.reset_by then
      raise exception 'staff pin reset record is kept when a new PIN is set'
        using errcode = 'check_violation';
    end if;
    new.set_at := v_now;

  else
    -- the same PIN: only the counters move
    if new.pepper_version is distinct from old.pepper_version
       or new.set_at is distinct from old.set_at
       or new.not_set_reason is distinct from old.not_set_reason
       or new.reset_at is distinct from old.reset_at
       or new.reset_by is distinct from old.reset_by then
      raise exception 'staff pin fields other than the counters cannot change with the same PIN'
        using errcode = 'check_violation';
    end if;
    if old.locked_until is not null and old.locked_until > v_now then
      raise exception 'staff pin is locked (update refused)'
        using errcode = 'check_violation';
    end if;

    if new.lock_count is distinct from old.lock_count
       or (new.locked_until is not null and new.locked_until is distinct from old.locked_until) then
      -- the fifth wrong try locks the PIN
      if old.failed_count <> 4 or new.failed_count <> 0 then
        raise exception 'staff pin locks only after the fifth wrong try'
          using errcode = 'check_violation';
      end if;
      v_restart := old.lock_count = 0 or old.lock_window_started_at <= v_now - interval '24 hours';
      if not v_restart and old.lock_count >= 2 then
        raise exception 'staff pin third lock within 24 hours must switch the PIN off'
          using errcode = 'check_violation';
      end if;
      v_expected := case when v_restart then 1 else old.lock_count + 1 end;
      if new.lock_count <> v_expected then
        raise exception 'staff pin lock count must go up by one inside the window, or restart at one'
          using errcode = 'check_violation';
      end if;
      new.locked_until := v_now + interval '15 minutes';
      new.lock_window_started_at := case when v_restart then v_now else old.lock_window_started_at end;
    else
      -- a wrong try (+1) or a right PIN (back to 0); an expired locked_until may be cleared or kept
      if new.lock_window_started_at is distinct from old.lock_window_started_at then
        raise exception 'staff pin lock window cannot change without a lock'
          using errcode = 'check_violation';
      end if;
      if new.failed_count <> old.failed_count + 1 and new.failed_count <> 0 then
        raise exception 'staff pin failed count goes up by one or back to zero'
          using errcode = 'check_violation';
      end if;
    end if;
  end if;

  new.updated_at := v_now;
  return new;
end;
$$;
revoke all on function platform.guard_staff_pin_update() from public;
revoke all on function platform.guard_staff_pin_update() from sahaj_app;
create trigger trg_staff_pin_guard_update
  before update on platform.staff_pin
  for each row execute function platform.guard_staff_pin_update();

-- 7. Comments.
comment on table platform.staff_pin is 'Personal staff PIN (ADR-010). pin_hash = BCrypt of an HMAC with SAHAJ_PIN_PEPPER; never the PIN. Checked only against the named person.';
comment on column platform.staff_pin.membership_id is 'The person in one clinic (platform.membership.id). One PIN per membership; the composite foreign key (tenant_id, membership_id) keeps it in the same clinic.';
comment on column platform.staff_pin.pin_hash is 'BCrypt (strength 12) of base64(HMAC-SHA256(pepper, "sahaj-staff-pin-v1|tenant|membership|pin")). Null while the PIN is off (an owner reset or the third lock nulls it; a row is never created without a PIN). Never exported, never logged.';
comment on column platform.staff_pin.pepper_version is 'Version of SAHAJ_PIN_PEPPER used for pin_hash (1 in phase 1). Rotation is not designed (ADR-010 Q6).';
comment on column platform.staff_pin.failed_count is 'Wrong tries in a row, 0 to 4. The fifth wrong try becomes a lock and resets this to 0.';
comment on column platform.staff_pin.locked_until is 'End of the 15-minute lock (database clock, stamped by the update guard). A past value means not locked.';
comment on column platform.staff_pin.lock_count is 'Locks counted inside the 24-hour window, 0 to 2. The third lock switches the PIN off instead (not_set_reason TOO_MANY_LOCKS) and this returns to 0.';
comment on column platform.staff_pin.lock_window_started_at is 'Start of the 24-hour window of the first counted lock (stamped by the update guard). Null exactly when lock_count = 0.';
comment on column platform.staff_pin.set_at is 'When the current PIN was set (database clock, stamped by the guards). Null exactly when pin_hash is null.';
comment on column platform.staff_pin.not_set_reason is 'Why the PIN is off: RESET_BY_OWNER or TOO_MANY_LOCKS. Null while a PIN is set.';
comment on column platform.staff_pin.reset_at is 'When the clinic owner last reset this PIN (database clock, stamped by the guard). Kept when a new PIN is set.';
comment on column platform.staff_pin.reset_by is 'User id of the owner who last reset this PIN. Set together with reset_at.';
comment on function platform.guard_staff_pin_insert() is 'SAH-31 guard: a staff_pin row is created only with a PIN; set_at is the database clock. SQLSTATE 23514.';
comment on function platform.guard_staff_pin_update() is 'SAH-31 guard (ADR-010 D5, D7, D8): the PIN lockout state machine, version + 1 on every change, times from clock_timestamp(). SQLSTATE 23514.';
