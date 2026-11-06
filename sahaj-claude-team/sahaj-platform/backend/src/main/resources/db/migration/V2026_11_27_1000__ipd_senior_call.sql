-- SAH-33 review fix pass 2 (ADR-007 R4): ipd.ews_senior_call, one append-only row per accepted "Call senior" entry.
-- It makes the replay, the 60-second throttle and "who called" exact per user without reading the clinical event
-- log by its text. Forward-only; additive; the previous app version never touches the table.
-- No change to ipd.ews_escalation columns (senior_calls stays smallint; the cap at 32,767 is in Java) and the four
-- NOT VALID checks of V2026_11_26_1000 stay as they are.

-- 1. The composite FK target. id is already the primary key, so (tenant_id, id) is unique by construction; the
-- constraint is what a composite foreign key needs (same pattern as ipd.admission).
alter table ipd.ews_escalation
  add constraint uq_ews_escalation_tenant_id unique (tenant_id, id);

-- 2. The table. No updated_* or version columns: rows are never changed (IPD append-only convention).
create table if not exists ipd.ews_senior_call (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null default platform.current_tenant()
                 constraint fk_ews_senior_call_tenant references platform.tenant (id),
  escalation_id  uuid not null,
  called_by      uuid not null,
  outcome        text not null
                 constraint ck_ews_senior_call_outcome check (outcome in ('CALLED', 'ALREADY_CALLED')),
  call_no        smallint
                 constraint ck_ews_senior_call_call_no check (call_no between 1 and 32767),
  called_at      timestamptz not null default clock_timestamp(),
  constraint fk_ews_senior_call_escalation foreign key (tenant_id, escalation_id)
    references ipd.ews_escalation (tenant_id, id),
  -- a CALLED row carries the count after the call; an ALREADY_CALLED row carries none
  constraint ck_ews_senior_call_call_no_outcome check ((outcome = 'CALLED') = (call_no is not null))
);

-- Throttle and replay read: "this user's rows on this escalation, newest first" (called_at > clock_timestamp() - 60 s,
-- and CALLED rows with call_no above the sent count).
create index if not exists ix_ews_senior_call_tenant_escalation_user
  on ipd.ews_senior_call (tenant_id, escalation_id, called_by, called_at desc);
-- Last defence behind the admission lock: one CALLED row per count of an escalation (a second = 23505, a code bug).
create unique index if not exists ux_ews_senior_call_tenant_escalation_call_no
  on ipd.ews_senior_call (tenant_id, escalation_id, call_no) where outcome = 'CALLED';

-- 3. RLS (ENABLE and FORCE). Select and insert policies only, for sahaj_app. No update, delete or truncate policy.
alter table ipd.ews_senior_call enable row level security;
alter table ipd.ews_senior_call force row level security;
create policy p_ews_senior_call_select on ipd.ews_senior_call
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_ews_senior_call_insert on ipd.ews_senior_call
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());

-- 4. Grants. Column-level insert: id, tenant_id and called_at keep their defaults (the database clock is the call time
-- and cannot be back-dated). No update, no delete.
revoke all on ipd.ews_senior_call from sahaj_app;
revoke all on ipd.ews_senior_call from public;
grant select on ipd.ews_senior_call to sahaj_app;
grant insert (escalation_id, called_by, outcome, call_no) on ipd.ews_senior_call to sahaj_app;

-- 5. Append-only for every role, the owner included (SQLSTATE 42501, message "... is append-only (UPDATE refused)").
create trigger trg_ews_senior_call_no_update_delete
  before update or delete on ipd.ews_senior_call
  for each row execute function platform.deny_audit_mutation();
create trigger trg_ews_senior_call_no_truncate
  before truncate on ipd.ews_senior_call
  for each statement execute function platform.deny_audit_mutation();

-- 6. Comments.
comment on table ipd.ews_senior_call is 'IPD senior-call entries of an early warning escalation (SAH-33, ADR-007 R4). Append-only: no update, delete or truncate (privilege and trigger). One row per accepted entry: CALLED (call_no = senior_calls after the call) or ALREADY_CALLED (the caller saw that another team member had called). Used for the replay, the 60-second throttle per user and "who called". Never deleted (in-patient record). Holds no name and no text. No message is sent to anyone in phase 1.';
comment on column ipd.ews_senior_call.called_by is 'Signed-in user id (IPD actor convention), not the membership id.';
comment on column ipd.ews_senior_call.outcome is 'CALLED: the caller recorded a call (senior_calls + 1). ALREADY_CALLED: another user had already called; nothing counted.';
comment on column ipd.ews_senior_call.call_no is 'The escalation senior_calls count after this call (1 to 32767). Null for ALREADY_CALLED.';
comment on column ipd.ews_senior_call.called_at is 'Database clock (clock_timestamp), not insertable by the app. The throttle compares it with clock_timestamp() - 60 seconds.';
