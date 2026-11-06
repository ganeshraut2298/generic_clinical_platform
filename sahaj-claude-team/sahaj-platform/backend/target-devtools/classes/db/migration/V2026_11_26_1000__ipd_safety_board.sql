-- SAH-33 · IPD Safety board: escalation state machine in the database (ADR-007 D1, D2). Forward-only; no earlier migration is edited.
-- ipd.ews_escalation is small (under 1 million rows for 100 hospitals). Nothing is deleted.
-- Checks that a legacy row could violate are added NOT VALID: enforced for every new and changed row, existing rows are
-- not scanned. No real database holds such rows (before SAH-33 only the table owner could close or acknowledge an
-- escalation and nothing in the app did), but sahaj_test holds DONE/ACK rows left by the SAH-30 test helper
-- VitalsSupport.closeEscalation (status DONE with no response_action / closed_by). A fresh environment behaves the same.
-- To validate later, clean the test data, then: alter table ipd.ews_escalation validate constraint <name>.
-- Checks that no existing row can violate (response_action codes, response_note length, reopened_at) stay validated.

-- 1. New columns (both nullable; the SAH-30 app version never sets them).
alter table ipd.ews_escalation
  add column if not exists closed_by    uuid,
  add column if not exists reopened_at  timestamptz;

-- 2. Checks (ADR-007 D1). Names sort after ck_ews_escalation_score* and ck_ews_escalation_risk_target, so a bad score or
-- risk is still reported by its own constraint.
alter table ipd.ews_escalation
  add constraint ck_ews_escalation_response_action
    check (response_action is null or response_action in
           ('BEDSIDE_REVIEW', 'TREATMENT_CHANGED', 'MOVED_ICU_HDU', 'RAPID_RESPONSE', 'NO_CHANGE', 'CLOSED_AT_DISCHARGE'));
-- DONE exactly when an action is recorded, and exactly when a closing user is recorded. NOT VALID (see the header).
alter table ipd.ews_escalation
  add constraint ck_ews_escalation_done_action check ((status = 'DONE') = (response_action is not null)) not valid;
alter table ipd.ews_escalation
  add constraint ck_ews_escalation_done_closed_by check ((status = 'DONE') = (closed_by is not null)) not valid;
-- The plan text: 1..1000 characters when present, present exactly when DONE with a doctor action.
alter table ipd.ews_escalation
  add constraint ck_ews_escalation_response_note check (response_note is null or char_length(response_note) between 1 and 1000);
alter table ipd.ews_escalation
  add constraint ck_ews_escalation_response_note_done
    check ((response_note is not null) = (status = 'DONE' and response_action is distinct from 'CLOSED_AT_DISCHARGE'))
    not valid;
-- Acknowledgement agrees with the status: OPEN has none, ACK has one, DONE has one unless it was closed at discharge.
alter table ipd.ews_escalation
  add constraint ck_ews_escalation_ack_by_status
    check ((status <> 'OPEN' or acknowledged_by is null)
       and (status <> 'ACK' or acknowledged_by is not null)
       and (status <> 'DONE' or response_action = 'CLOSED_AT_DISCHARGE' or acknowledged_by is not null))
    not valid;
alter table ipd.ews_escalation
  add constraint ck_ews_escalation_reopened check (reopened_at is null or reopened_at >= raised_at);

-- 3. Index: closed and open escalations of the admitted patients on the board, newest raise order per admission.
create index if not exists ix_ews_escalation_tenant_admission
  on ipd.ews_escalation (tenant_id, admission_id, raised_at);

-- 4. Grant: the Safety board writes status, acknowledgement, response, senior calls and the closing user. NOT granted:
-- acknowledged_at, closed_at, reopened_at (the guard trigger stamps them from the database clock), raised_at, raised_by.
-- The existing update grant (ews_score, ews_risk, target_minutes, version) stays. A BEFORE UPDATE trigger may set columns
-- that the caller has no update privilege on.
grant update (status, acknowledged_by, response_action, response_note, senior_calls, closed_by)
  on ipd.ews_escalation to sahaj_app;

-- 5. Guard: the escalation state machine (ADR-007 D2). Backstop for the Java class EscalationState. SECURITY INVOKER, fixed
-- search_path, SQLSTATE 23514 (check_violation); every message contains "escalation". Replaces the SAH-30 guard
-- (V2026_11_23_1000, V2026_11_25_1000); its three rules (score, risk, version never fall; DONE is final) are kept, in the
-- same order, so their messages are unchanged.
--   OPEN -> OPEN  a score update (never lower) or one senior call alone
--   OPEN -> ACK   acknowledgement set, score/risk/target unchanged;               trigger stamps acknowledged_at
--   OPEN -> DONE  doctor action (admission ADMITTED, note set, acknowledged_by = closed_by) or CLOSED_AT_DISCHARGE
--                 (admission DISCHARGED, no note, acknowledgement kept);           trigger stamps closed_at (and acknowledged_at for a doctor action)
--   ACK  -> ACK   score update inside the same risk, acknowledgement unchanged
--   ACK  -> OPEN  only MEDIUM -> HIGH with a higher score and acknowledged_by cleared; trigger clears acknowledged_at, stamps reopened_at
--   ACK  -> DONE  as OPEN -> DONE, acknowledgement kept
--   DONE -> anything: refused.  Anything else: refused.
-- version: exactly old + 1 when status, score, risk, target, acknowledged_by, response_action, response_note or closed_by
-- changes; unchanged otherwise (a senior call does not change it). senior_calls: only + 1, only OPEN -> OPEN, only alone.
-- The admission read (DISCHARGED / ADMITTED) is a plain select in the caller's transaction: it takes no lock and runs under
-- the caller's row level security (a role that cannot see the admission is refused).
create or replace function ipd.guard_ews_escalation_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_tracked boolean;
  v_adm     text;
  v_at      timestamptz := clock_timestamp();
begin
  if old.status = 'DONE' then
    raise exception 'early warning escalation is closed (update refused)'
      using errcode = 'check_violation';
  end if;
  if new.ews_score < old.ews_score then
    raise exception 'early warning escalation score cannot go down'
      using errcode = 'check_violation';
  end if;
  if old.ews_risk = 'HIGH' and new.ews_risk <> 'HIGH' then
    raise exception 'early warning escalation risk cannot go down'
      using errcode = 'check_violation';
  end if;
  if new.version < old.version then
    raise exception 'early warning escalation version cannot go down'
      using errcode = 'check_violation';
  end if;

  if new.id is distinct from old.id
     or new.tenant_id is distinct from old.tenant_id
     or new.admission_id is distinct from old.admission_id
     or new.raised_at is distinct from old.raised_at
     or new.raised_by is distinct from old.raised_by then
    raise exception 'early warning escalation identity and raise fields cannot change'
      using errcode = 'check_violation';
  end if;

  -- version: exactly + 1 with a tracked change, unchanged without one
  v_tracked := new.status is distinct from old.status
            or new.ews_score is distinct from old.ews_score
            or new.ews_risk is distinct from old.ews_risk
            or new.target_minutes is distinct from old.target_minutes
            or new.acknowledged_by is distinct from old.acknowledged_by
            or new.response_action is distinct from old.response_action
            or new.response_note is distinct from old.response_note
            or new.closed_by is distinct from old.closed_by;
  if v_tracked and new.version <> old.version + 1 then
    raise exception 'early warning escalation version must go up by exactly one with a change'
      using errcode = 'check_violation';
  end if;
  if not v_tracked and new.version <> old.version then
    raise exception 'early warning escalation version changes only together with a change'
      using errcode = 'check_violation';
  end if;

  -- senior calls: +1, while OPEN, alone
  if new.senior_calls is distinct from old.senior_calls
     and (old.status <> 'OPEN' or new.status <> 'OPEN' or new.senior_calls <> old.senior_calls + 1 or v_tracked) then
    raise exception 'early warning escalation senior calls go up by one, on an open escalation, with no other change'
      using errcode = 'check_violation';
  end if;

  -- the response is recorded only when the escalation is closed
  if new.status <> 'DONE'
     and (new.response_action is distinct from old.response_action
          or new.response_note is distinct from old.response_note
          or new.closed_by is distinct from old.closed_by) then
    raise exception 'early warning escalation response is recorded only when it is closed'
      using errcode = 'check_violation';
  end if;

  if old.status = 'OPEN' and new.status = 'OPEN' then
    if new.acknowledged_by is distinct from old.acknowledged_by then
      raise exception 'early warning escalation cannot be acknowledged without moving to acknowledged'
        using errcode = 'check_violation';
    end if;

  elsif old.status = 'OPEN' and new.status = 'ACK' then
    if old.acknowledged_by is not null or new.acknowledged_by is null then
      raise exception 'early warning escalation acknowledgement needs a user, once'
        using errcode = 'check_violation';
    end if;
    if new.ews_score <> old.ews_score or new.ews_risk <> old.ews_risk or new.target_minutes <> old.target_minutes then
      raise exception 'early warning escalation score cannot change while it is acknowledged'
        using errcode = 'check_violation';
    end if;
    new.acknowledged_at := v_at;

  elsif old.status = 'ACK' and new.status = 'ACK' then
    if new.acknowledged_by is distinct from old.acknowledged_by then
      raise exception 'early warning escalation acknowledgement cannot change'
        using errcode = 'check_violation';
    end if;
    if new.ews_risk <> old.ews_risk then
      raise exception 'early warning escalation risk rise needs a re-open'
        using errcode = 'check_violation';
    end if;

  elsif old.status = 'ACK' and new.status = 'OPEN' then
    if not (old.ews_risk = 'MEDIUM' and new.ews_risk = 'HIGH' and new.ews_score > old.ews_score
            and new.acknowledged_by is null) then
      raise exception 'early warning escalation re-opens only when the risk rises from medium to high'
        using errcode = 'check_violation';
    end if;
    new.acknowledged_at := null;
    new.reopened_at := now();

  elsif new.status = 'DONE' then
    if new.response_action is null or new.closed_by is null then
      raise exception 'early warning escalation needs an action and a closing user to be closed'
        using errcode = 'check_violation';
    end if;
    if new.ews_score <> old.ews_score or new.ews_risk <> old.ews_risk or new.target_minutes <> old.target_minutes then
      raise exception 'early warning escalation score cannot change when it is closed'
        using errcode = 'check_violation';
    end if;
    select a.status into v_adm
      from ipd.admission a
     where a.tenant_id = new.tenant_id and a.id = new.admission_id;
    if new.response_action = 'CLOSED_AT_DISCHARGE' then
      if v_adm is distinct from 'DISCHARGED' then
        raise exception 'early warning escalation can be closed at discharge only for a discharged admission'
          using errcode = 'check_violation';
      end if;
      if new.response_note is not null or new.acknowledged_by is distinct from old.acknowledged_by then
        raise exception 'early warning escalation closed at discharge has no note and keeps its acknowledgement'
          using errcode = 'check_violation';
      end if;
      new.acknowledged_at := old.acknowledged_at;
    else
      if v_adm is distinct from 'ADMITTED' then
        raise exception 'early warning escalation can get a doctor response only for an admitted patient'
          using errcode = 'check_violation';
      end if;
      if new.response_note is null then
        raise exception 'early warning escalation response needs a note'
          using errcode = 'check_violation';
      end if;
      if old.status = 'OPEN' then
        if new.acknowledged_by is distinct from new.closed_by then
          raise exception 'early warning escalation closed from open is acknowledged by the closing user'
            using errcode = 'check_violation';
        end if;
        new.acknowledged_at := v_at;
      elsif new.acknowledged_by is distinct from old.acknowledged_by then
        raise exception 'early warning escalation acknowledgement cannot change'
          using errcode = 'check_violation';
      end if;
    end if;
    new.closed_at := v_at;

  else
    raise exception 'early warning escalation status change is not allowed'
      using errcode = 'check_violation';
  end if;

  -- the three times belong to the database: the branches above overwrite them on the transitions that own them
  if new.acknowledged_at is distinct from old.acknowledged_at
     and not (old.status = 'OPEN' and new.status in ('ACK', 'DONE'))
     and not (old.status = 'ACK' and new.status = 'OPEN') then
    raise exception 'early warning escalation acknowledged_at is set by the database'
      using errcode = 'check_violation';
  end if;
  if new.closed_at is distinct from old.closed_at and new.status <> 'DONE' then
    raise exception 'early warning escalation closed_at is set by the database'
      using errcode = 'check_violation';
  end if;
  if new.reopened_at is distinct from old.reopened_at and not (old.status = 'ACK' and new.status = 'OPEN') then
    raise exception 'early warning escalation reopened_at is set by the database'
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_ews_escalation_update() from public;
revoke all on function ipd.guard_ews_escalation_update() from sahaj_app;

-- 6. Comments.
comment on function ipd.guard_ews_escalation_update() is 'SAH-33 guard (ADR-007 D2): escalation state machine. OPEN->ACK, OPEN/ACK->DONE (doctor action while the admission is ADMITTED, CLOSED_AT_DISCHARGE while DISCHARGED), ACK->OPEN only on MEDIUM->HIGH, DONE final; score, risk and version never fall; version +1 exactly on a tracked change; senior_calls +1 only while OPEN and alone; stamps acknowledged_at, closed_at, reopened_at from the database clock. SQLSTATE 23514.';
comment on table ipd.ews_escalation is 'IPD early warning escalation (SAH-30, Safety board SAH-33). At most one not-DONE row per admission (partial unique index). Never deleted (privilege and trigger). Score is 3 or more and HIGH exactly when the score is 7 or more (checks). sahaj_app may update ews_score, ews_risk, target_minutes, version, status, acknowledged_by, response_action, response_note, senior_calls and closed_by; acknowledged_at, closed_at and reopened_at are stamped by the guard trigger from the database clock. The response clock starts at coalesce(reopened_at, raised_at). response_note is clinical text: shown to OWNER, DOCTOR and NURSE only, never logged or put in the event log. No message is sent to anyone in phase 1.';
comment on column ipd.ews_escalation.status is 'OPEN (not seen), ACK (a doctor is reviewing), DONE (closed). Transitions only through the guard trigger.';
comment on column ipd.ews_escalation.acknowledged_by is 'User id of the acknowledging doctor (IPD actor convention). Null while OPEN; kept when closed at discharge from ACK; cleared when a risk rise re-opens the escalation.';
comment on column ipd.ews_escalation.acknowledged_at is 'Database clock time of the acknowledgement (trigger-stamped, not granted to sahaj_app). A doctor close from OPEN sets it to the closing time.';
comment on column ipd.ews_escalation.response_action is 'BEDSIDE_REVIEW, TREATMENT_CHANGED, MOVED_ICU_HDU, RAPID_RESPONSE, NO_CHANGE (doctor) or CLOSED_AT_DISCHARGE (system, written by the discharge only). Set exactly when DONE.';
comment on column ipd.ews_escalation.response_note is 'Plan and next vitals, 1 to 1000 characters: clinical text. Present exactly when DONE with a doctor action; null for CLOSED_AT_DISCHARGE.';
comment on column ipd.ews_escalation.senior_calls is 'Times a nurse recorded a call to the senior doctor while OPEN. Records the call only: nothing is sent in phase 1. Does not change version.';
comment on column ipd.ews_escalation.closed_at is 'Database clock time of the close (trigger-stamped, not granted to sahaj_app).';
comment on column ipd.ews_escalation.closed_by is 'SAH-33: user id that closed the escalation (the doctor, or the discharging user for CLOSED_AT_DISCHARGE). Set exactly when DONE.';
comment on column ipd.ews_escalation.reopened_at is 'SAH-33: set only when an ACK escalation is re-opened by a MEDIUM to HIGH rise (trigger-stamped, transaction time of the vitals save). The response clock start is coalesce(reopened_at, raised_at); raised_at and raised_by never change.';
comment on column ipd.ews_escalation.version is 'Optimistic-lock counter. Rises by exactly 1 with every change to status, score, risk, target, acknowledgement, response or closing user; a senior call leaves it unchanged (guard trigger).';
