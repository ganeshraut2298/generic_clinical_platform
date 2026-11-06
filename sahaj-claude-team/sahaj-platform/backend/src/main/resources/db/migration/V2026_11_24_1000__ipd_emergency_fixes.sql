-- SAH-28 fix pass (code review 2 and 9, security M1(a) and I1). Forward-only; V2026_11_22_1000 is applied and is not edited.

-- 1. One clock for the arrival and the triage history. now() is the transaction start, taken before the clinic advisory
-- lock is acquired, so two racing writes could be stored out of order. clock_timestamp() is read when the row is written,
-- which is after the lock. Neither column is insertable or updatable by sahaj_app, so this changes defaults only.
-- Back-dating in tests is an UPDATE as the table owner and is unaffected.
alter table ipd.emergency_case alter column arrived_at set default clock_timestamp();
alter table ipd.emergency_triage_event alter column recorded_at set default clock_timestamp();

-- 2. Security M1(a): the mobile number (often a relative's) is cleared in the same UPDATE that closes the case.
-- 2a. One-off data statement for cases that are already closed (unreleased branch: only dev/test rows exist). Safe because
-- nothing reads mobile of a closed case (the queue shows open cases only; the Admit hand-off reads it while open) and
-- the statement is idempotent. It runs as the table owner: the guard is switched off and FORCE RLS lifted for this one
-- transaction (policies are for sahaj_app only, so the owner would otherwise see no rows), then both are restored.
set local lock_timeout = '30s';
alter table ipd.emergency_case no force row level security;
alter table ipd.emergency_case disable trigger trg_emergency_case_guard_update;
update ipd.emergency_case
   set mobile = null
 where status in ('ADMITTED', 'RELEASED', 'REFERRED') and mobile is not null;
alter table ipd.emergency_case enable trigger trg_emergency_case_guard_update;
alter table ipd.emergency_case force row level security;

-- 2b. Column grant: sahaj_app may update mobile (the guard below allows only the clear on closing).
grant update (mobile) on ipd.emergency_case to sahaj_app;

-- 2c. Guard: a closed case still refuses every update. On an open case, mobile may change only to null and only in the
-- same UPDATE that sets a closing status (ADMITTED, RELEASED, REFERRED). Any other change of mobile is refused.
-- SECURITY INVOKER, fixed search_path, SQLSTATE 23514 for every refusal.
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
  if new.mobile is distinct from old.mobile
     and (new.mobile is not null or new.status not in ('ADMITTED', 'RELEASED', 'REFERRED')) then
    raise exception 'emergency case mobile can only be cleared when the case is closed'
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_emergency_case_update() from public;
revoke all on function ipd.guard_emergency_case_update() from sahaj_app;

-- 3. Info I1: patient_id is set only together with ADMITTED (the existing ck_emergency_case_admitted already requires it
-- for ADMITTED). Plain add: the table is small and Java only sets patient_id in the admit update.
alter table ipd.emergency_case
  add constraint ck_emergency_case_patient_admitted check (patient_id is null or status = 'ADMITTED');

comment on function ipd.guard_emergency_case_update() is 'SAH-28 guard: a closed emergency case (ADMITTED, RELEASED, REFERRED) is never updated; SEEN never returns to WAITING; mobile changes only to null in the update that closes the case. SQLSTATE 23514.';
comment on column ipd.emergency_case.mobile is 'SAH-28: personal data, kept only while the case is open; cleared in the update that closes it (guard enforced). Never logged.';
