-- SAH-38 (security finding S38-1, ADR-014 "Data needed" 1(c), tightened 7 Nov 2026): a colleague-caused lock needs proof.
-- Forward-only. One statement: create or replace of platform.guard_staff_pin_update(). No data change, no drop, no table,
-- column, grant, revoke or trigger change (create or replace keeps the function's owner, ACL and the trigger binding).
-- The earlier migrations V2026_11_28_1100, V2026_12_01_1000 and V2026_12_02_1000 are not edited.
-- Only the lock branch with new.lock_by_colleagues = true changes. Before: any fifth-try lock could claim
-- lock_by_colleagues = true. Now the guard accepts it only when
--   (1) old.run_has_colleague_try is true (a colleague typed an earlier try of this run), or
--   (2) a WRONG row of this tenant and this membership exists in platform.staff_pin_colleague_event with
--       at >= transaction_timestamp() (the app inserts the WRONG row before the lock update, in the same transaction).
-- Otherwise SQLSTATE 23514, message "staff pin lock caused by colleagues needs a colleague try".
-- Row level security: the guard runs as the invoker (sahaj_app, SECURITY INVOKER). The select policy
-- p_staff_pin_colleague_event_select limits the read to tenant_id = platform.current_tenant(); the query also names
-- tenant_id = old.tenant_id and membership_id = old.membership_id, so a row of another tenant or another membership never
-- counts. Names are schema-qualified because search_path is pg_catalog only (transaction_timestamp() lives in pg_catalog).
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
        -- S38-1: proof of a colleague try. Either the run already has one, or the app wrote a WRONG row for this
        -- membership in this transaction (event.at is clock_timestamp(), never earlier than transaction_timestamp()).
        -- The read runs as the invoker under RLS (tenant = platform.current_tenant()) and is also fenced by the old row.
        if not old.run_has_colleague_try
           and not exists (
             select 1
               from platform.staff_pin_colleague_event e
              where e.tenant_id = old.tenant_id
                and e.membership_id = old.membership_id
                and e.kind = 'WRONG'
                and e.at >= pg_catalog.transaction_timestamp()
           ) then
          raise exception 'staff pin lock caused by colleagues needs a colleague try'
            using errcode = 'check_violation';
        end if;
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

comment on function platform.guard_staff_pin_update() is 'SAH-31 guard (ADR-010 D5, D7, D8) as amended by SAH-38 (ADR-014 D6): the PIN lockout state machine including colleague-caused locks, version + 1 on every change, times from clock_timestamp(). A colleague-caused lock needs proof: old.run_has_colleague_try, or a WRONG event row of the same membership written in this transaction (S38-1). SQLSTATE 23514.';
