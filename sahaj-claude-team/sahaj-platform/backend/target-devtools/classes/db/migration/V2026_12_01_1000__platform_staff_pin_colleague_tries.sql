-- SAH-38 (HARD-3, ADR-014 D2, D6 and "Data needed"): colleague PIN hardening. Forward-only and additive.
--   1. platform.staff_pin gets two flags (who typed the run of wrong tries, and whether the lock was caused by
--      colleagues). Every existing row takes the default false; no row is rewritten or lost.
--   2. platform.guard_staff_pin_update() is replaced (create or replace) with every SAH-31 rule kept plus rules (a) to (g).
--   3. New append-only table platform.staff_pin_colleague_event: the durable store of wrong colleague tries, colleague
--      locks, "right after wrong" markers and the PIN owner's "Got it" markers. Ids, a kind and times only.
-- Design note: docs/database/SAH-38.md. The earlier migration V2026_11_28_1100__platform_staff_pin.sql is not edited.

-- 1. platform.staff_pin: two columns and three checks (the table is small: one row per membership).
alter table platform.staff_pin
  add column if not exists run_has_colleague_try boolean not null default false,
  add column if not exists lock_by_colleagues    boolean not null default false;

alter table platform.staff_pin
  -- a colleague try can only sit in a run that has at least one counted wrong try
  add constraint ck_staff_pin_run_colleague
    check ((not run_has_colleague_try) or failed_count > 0),
  -- a colleague-caused lock exists only while locked_until holds the end of that lock
  add constraint ck_staff_pin_lock_by_colleagues
    check ((not lock_by_colleagues) or locked_until is not null),
  -- with no PIN there is no run and no lock cause
  add constraint ck_staff_pin_colleague_flags_when_off
    check (pin_hash is not null or (not run_has_colleague_try and not lock_by_colleagues));

-- Grants: the two flags are updatable by the app (in addition to the 13 columns of SAH-31). Insert grant unchanged
-- (both take their default false on a first PIN).
grant update (run_has_colleague_try, lock_by_colleagues) on platform.staff_pin to sahaj_app;

-- 2. Update guard (ADR-010 D5, D7, D8 as amended by ADR-014 D6). SECURITY INVOKER, fixed search_path, SQLSTATE 23514,
-- every message contains "staff pin". Kinds of update (the table checks run after the trigger):
--   SET or CHANGE a PIN  as SAH-31; both flags must be false.
--   OWNER RESET          as SAH-31; both flags must be false.
--   THIRD LOCK           as SAH-31; only when the old run had no colleague try (old.run_has_colleague_try = false);
--                        both flags must be false.
--   WRONG TRY            as SAH-31; run_has_colleague_try may go false -> true or stay, never true -> false;
--                        lock_by_colleagues unchanged, except true -> false when the same update clears an expired
--                        locked_until to null.
--   RIGHT PIN            as SAH-31; failed_count 0 means run_has_colleague_try false; lock flag as for a wrong try.
--   LOCK (5th wrong)     as SAH-31 when lock_by_colleagues is false (and the old run had no colleague try); when
--                        lock_by_colleagues is true (the old run had a colleague try, or the 5th try is a colleague
--                        check): lock_count and lock_window_started_at unchanged, no switch-off rule, locked_until =
--                        now + 15 minutes. Old run_has_colleague_try true forces lock_by_colleagues true.
-- Always: version = old + 1; id, tenant, created_* never change; updated_at = database time; times are clock_timestamp().
create or replace function platform.guard_staff_pin_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_now      timestamptz := clock_timestamp();
  v_restart  boolean;
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

  -- rule (b): with no wrong tries counted there is no run, so no colleague try in it
  if new.failed_count = 0 and new.run_has_colleague_try then
    raise exception 'staff pin colleague-try flag must be false when no wrong tries are counted'
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
      -- rule (d): a run with a colleague try can never switch the PIN off (it is a colleague-caused lock)
      if old.run_has_colleague_try then
        raise exception 'staff pin is not switched off after a run with a colleague try'
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
    -- rule (e): a switched-off PIN has neither flag
    if new.run_has_colleague_try or new.lock_by_colleagues then
      raise exception 'staff pin colleague flags must be false when the PIN is switched off'
        using errcode = 'check_violation';
    end if;

  elsif old.pin_hash is null or new.pin_hash is distinct from old.pin_hash then
    -- a new PIN (first, after a switch-off, or a change): everything counted starts again
    if new.failed_count <> 0 or new.locked_until is not null
       or new.lock_count <> 0 or new.lock_window_started_at is not null then
      raise exception 'staff pin counters must be cleared when a new PIN is set'
        using errcode = 'check_violation';
    end if;
    -- rule (e): a new PIN starts with neither flag
    if new.run_has_colleague_try or new.lock_by_colleagues then
      raise exception 'staff pin colleague flags must be false when a new PIN is set'
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
      if new.lock_by_colleagues then
        -- rule (c): a lock caused by colleagues never moves the lock count or the window, and never switches off
        if new.lock_count is distinct from old.lock_count
           or new.lock_window_started_at is distinct from old.lock_window_started_at then
          raise exception 'staff pin lock caused by colleagues keeps the lock count and the lock window'
            using errcode = 'check_violation';
        end if;
        new.locked_until := v_now + interval '15 minutes';
      else
        -- rule (c): a run with a colleague try must be marked as a colleague-caused lock
        if old.run_has_colleague_try then
          raise exception 'staff pin lock after a run with a colleague try must be marked as caused by colleagues'
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
      end if;
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
      -- rule (a): the colleague-try flag may be set or kept during the run, and falls only when the run ends
      if old.run_has_colleague_try and not new.run_has_colleague_try and new.failed_count <> 0 then
        raise exception 'staff pin colleague-try flag cannot be cleared inside a run of wrong tries'
          using errcode = 'check_violation';
      end if;
      -- rule (f): the lock cause is unchanged, except when the same update clears an expired locked_until
      if new.lock_by_colleagues is distinct from old.lock_by_colleagues
         and not (old.lock_by_colleagues and new.locked_until is null) then
        raise exception 'staff pin lock cause changes only with a lock or when an expired lock is cleared'
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

-- 3. platform.staff_pin_colleague_event: tenant-owned, append-only. One row per relevant event about one person's PIN
-- (membership_id = the PIN's owner). No updated_* and no version, as platform.audit_event.
create table if not exists platform.staff_pin_colleague_event (
  id               uuid not null default gen_random_uuid()
                   constraint pk_staff_pin_colleague_event primary key,
  tenant_id        uuid not null default platform.current_tenant()
                   constraint fk_staff_pin_colleague_event_tenant references platform.tenant (id),
  membership_id    uuid not null,
  by_membership_id uuid,
  kind             text not null
                   constraint ck_staff_pin_colleague_event_kind
                   check (kind in ('WRONG', 'LOCKED', 'RIGHT_AFTER_WRONG', 'NOTICE_SEEN')),
  seen_through     timestamptz,
  at               timestamptz not null default clock_timestamp(),
  created_by       uuid not null,
  -- both ids must belong to the same clinic as the row
  constraint fk_staff_pin_colleague_event_pin foreign key (tenant_id, membership_id)
    references platform.staff_pin (tenant_id, membership_id),
  constraint fk_staff_pin_colleague_event_by foreign key (tenant_id, by_membership_id)
    references platform.membership (tenant_id, id),
  -- the caller is named exactly for the kinds that come from a caller's try
  constraint ck_staff_pin_colleague_event_by_kind
    check ((kind in ('WRONG', 'RIGHT_AFTER_WRONG')) = (by_membership_id is not null)),
  constraint ck_staff_pin_colleague_event_by_not_self
    check (by_membership_id is null or by_membership_id <> membership_id),
  -- the "Got it" marker carries the newest try the notice showed
  constraint ck_staff_pin_colleague_event_seen_kind
    check ((kind = 'NOTICE_SEEN') = (seen_through is not null))
);

-- Indexes (all start with tenant_id).
-- pair cap: the pair's WRONG rows since the window start and the pair's latest RIGHT_AFTER_WRONG
create index if not exists ix_staff_pin_colleague_event_pair
  on platform.staff_pin_colleague_event (tenant_id, membership_id, by_membership_id, at);
-- notice count, latest NOTICE_SEEN, owner history of one person; child side of fk_staff_pin_colleague_event_pin
create index if not exists ix_staff_pin_colleague_event_member
  on platform.staff_pin_colleague_event (tenant_id, membership_id, at);
-- Team page: grouped WRONG counts of the last 7 days for the whole clinic
create index if not exists ix_staff_pin_colleague_event_wrong_7d
  on platform.staff_pin_colleague_event (tenant_id, at) where kind = 'WRONG';

-- Row level security (ENABLE and FORCE): select and insert only. No update or delete policy.
alter table platform.staff_pin_colleague_event enable row level security;
alter table platform.staff_pin_colleague_event force row level security;
create policy p_staff_pin_colleague_event_select on platform.staff_pin_colleague_event
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_staff_pin_colleague_event_insert on platform.staff_pin_colleague_event
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());

-- Grants: select, and insert of five columns. id, tenant_id and at always take their defaults. No update, delete,
-- truncate.
revoke all on platform.staff_pin_colleague_event from sahaj_app;
revoke all on platform.staff_pin_colleague_event from public;
grant select on platform.staff_pin_colleague_event to sahaj_app;
grant insert (membership_id, by_membership_id, kind, seen_through, created_by)
  on platform.staff_pin_colleague_event to sahaj_app;

-- Append-only for every role, the owner included (SQLSTATE 42501, message "... is append-only (UPDATE refused)").
create trigger trg_staff_pin_colleague_event_no_update_delete
  before update or delete on platform.staff_pin_colleague_event
  for each row execute function platform.deny_audit_mutation();
create trigger trg_staff_pin_colleague_event_no_truncate
  before truncate on platform.staff_pin_colleague_event
  for each statement execute function platform.deny_audit_mutation();

-- Insert guard: at is the database clock; the "Got it" marker can never lie in the future. SECURITY INVOKER, fixed
-- search_path, SQLSTATE 23514, message contains "colleague event".
create or replace function platform.guard_staff_pin_colleague_event_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_now timestamptz := clock_timestamp();
begin
  new.at := v_now;
  if new.kind = 'NOTICE_SEEN' then
    if new.seen_through is null then
      raise exception 'colleague event NOTICE_SEEN needs seen_through'
        using errcode = 'check_violation';
    end if;
    new.seen_through := least(new.seen_through, v_now);
  end if;
  return new;
end;
$$;
revoke all on function platform.guard_staff_pin_colleague_event_insert() from public;
revoke all on function platform.guard_staff_pin_colleague_event_insert() from sahaj_app;
create trigger trg_staff_pin_colleague_event_guard_insert
  before insert on platform.staff_pin_colleague_event
  for each row execute function platform.guard_staff_pin_colleague_event_insert();

-- 4. Comments.
comment on column platform.staff_pin.run_has_colleague_try is 'ADR-014 D6: true when at least one wrong try in the current run (failed_count > 0) came from a colleague check. False whenever failed_count = 0, and always while the PIN is off. Set only by a colleague wrong try.';
comment on column platform.staff_pin.lock_by_colleagues is 'ADR-014 D6: true when the current or last lock was caused by a run with a colleague try. Such a lock keeps lock_count and the window and never switches the PIN off. Cleared by a new PIN, an owner reset, a switch-off, the next own-run lock, or when an expired locked_until is cleared.';
comment on table platform.staff_pin_colleague_event is 'ADR-014 D2: append-only events about one person''s PIN (membership_id = the PIN''s owner): WRONG (a colleague typed a wrong PIN), LOCKED (a lock caused by colleagues), RIGHT_AFTER_WRONG (clears the pair), NOTICE_SEEN (the owner tapped Got it). Ids, a kind and times only; never a PIN, hash, name or mobile number. Rare rows (about 2.6 million a year at the extreme planning figure); no partitioning. Kept (no delete grant); a purge of rows older than 90 days by sahaj_migrator is allowed but not built.';
comment on column platform.staff_pin_colleague_event.membership_id is 'The PIN owner (platform.staff_pin.membership_id); the composite foreign key keeps it in the same clinic.';
comment on column platform.staff_pin_colleague_event.by_membership_id is 'The colleague who typed the PIN (WRONG, RIGHT_AFTER_WRONG); null for LOCKED and NOTICE_SEEN. Must belong to the same clinic.';
comment on column platform.staff_pin_colleague_event.seen_through is 'NOTICE_SEEN only: the newest try the notice showed, capped at the database clock by the insert guard.';
comment on column platform.staff_pin_colleague_event.at is 'Database clock (clock_timestamp()), stamped by the insert guard; the app cannot supply it.';
comment on column platform.staff_pin_colleague_event.created_by is 'User id of the signed-in session that wrote the row (no foreign key, user-id convention).';
comment on function platform.guard_staff_pin_colleague_event_insert() is 'SAH-38 guard: at = clock_timestamp(); NOTICE_SEEN seen_through capped at the database clock. SQLSTATE 23514.';
comment on function platform.guard_staff_pin_update() is 'SAH-31 guard (ADR-010 D5, D7, D8) as amended by SAH-38 (ADR-014 D6): the PIN lockout state machine including colleague-caused locks, version + 1 on every change, times from clock_timestamp(). SQLSTATE 23514.';
