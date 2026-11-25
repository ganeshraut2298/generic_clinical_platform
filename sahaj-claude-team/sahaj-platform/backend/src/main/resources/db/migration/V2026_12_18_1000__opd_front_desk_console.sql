-- SAH-90..93 · OPD front desk console (ADR-032). All additive: no drop, rename or rewrite.
-- See docs/database/SAH-90-front-desk-console.md.

-- 1. Per-doctor booking cut-off override (SAH-91-2). Null = use opd.booking_rules.close_before_hours.
alter table opd.doctor_slot_template
  add column if not exists close_before_hours integer
    constraint ck_doctor_slot_template_close_before_hours check (close_before_hours between 0 and 48);
comment on column opd.doctor_slot_template.close_before_hours is
  'Per-doctor booking cut-off in hours; null means the clinic value in opd.booking_rules (SAH-91-2).';

-- 2. Follow-up marked "not needed" (SAH-93-1). Both columns set together or both null.
alter table opd.follow_up
  add column if not exists not_needed_at timestamptz,
  add column if not exists not_needed_by uuid;
do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'ck_follow_up_not_needed_pair') then
    alter table opd.follow_up
      add constraint ck_follow_up_not_needed_pair
      check ((not_needed_at is null) = (not_needed_by is null));
  end if;
end $$;
comment on column opd.follow_up.not_needed_at is
  'When the front desk marked this follow-up as not needed; set together with not_needed_by (SAH-93-1). Row is never deleted.';

-- 3. "To book" list (SAH-90-5): open follow-ups by due date.
create index if not exists ix_follow_up_tenant_due_to_book
  on opd.follow_up (tenant_id, due_date)
  where returned_visit_id is null and appointment_id is null and not_needed_at is null;

-- 4. Consultation start and end (SAH-92-3). No backfill; null for older visits.
alter table opd.visit
  add column if not exists consult_started_at timestamptz,
  add column if not exists consult_ended_at timestamptz;
do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'ck_visit_consult_order') then
    alter table opd.visit
      add constraint ck_visit_consult_order
      check (consult_ended_at is null or consult_started_at is null or consult_ended_at >= consult_started_at);
  end if;
end $$;
comment on column opd.visit.consult_started_at is 'Set once when the visit enters WITH_DOCTOR by any path (SAH-92-3).';
comment on column opd.visit.consult_ended_at is 'Set once when the visit leaves WITH_DOCTOR forward (SAH-92-3).';

-- 5. Token allocation for a caller-supplied day (SAH-92-1). Same row-lock body as the zero-arg version.
-- Plain CREATE (not OR REPLACE): this is a new overload with a different signature, not a redefinition of the zero-arg function, so
-- MigrationFunctionGuardrailTest (which matches "create or replace function <name>" regardless of arity) does not treat it as a second definition.
create function opd.next_token_no(p_token_date date)
returns integer
language plpgsql
as $$
declare
  v_tenant uuid := platform.current_tenant();
  v_seq    integer;
begin
  insert into opd.visit_token_counter (tenant_id, token_date, next_value)
  values (v_tenant, p_token_date, 1)
  on conflict (tenant_id, token_date) do update
    set next_value = opd.visit_token_counter.next_value + 1,
        updated_at = now()
  returning next_value into v_seq;

  return v_seq;
end;
$$;
revoke all on function opd.next_token_no(date) from public;
grant execute on function opd.next_token_no(date) to sahaj_app;
comment on function opd.next_token_no(date) is
  'Next token number for the current tenant on the given day, starting at 1 (SAH-92-1). The application passes today in Asia/Kolkata and inserts opd.visit.token_date explicitly with the same date, in the same transaction. Row lock via INSERT ... ON CONFLICT on opd.visit_token_counter. Security invoker, so RLS applies.';

-- 6. All-doctor date-range reads on the board and snapshot (SAH-90).
create index if not exists ix_appointment_tenant_date_active
  on opd.appointment (tenant_id, appt_date)
  where status <> 'CANCELLED';

-- 7. opd.appointment.version already exists (bigint not null default 0): no change.
