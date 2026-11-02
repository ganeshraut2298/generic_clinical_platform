-- SAH-9 · opd.visit_token_counter + opd.next_token_no()
-- Token numbers reset every day, per tenant. Same pattern as SAH-8's
-- patient.patient_uhid_counter / patient.next_uhid(), but keyed on (tenant_id, token_date)
-- instead of just tenant_id, since a new "sequence" starts each day. One row per tenant per day
-- that has issued at least one token; old day rows are simply never touched again (no cleanup
-- needed, a handful of bigint rows per tenant per year).
create table if not exists opd.visit_token_counter (
  tenant_id   uuid not null
              constraint fk_visit_token_counter_tenant references platform.tenant (id),
  token_date  date not null,
  next_value  integer not null default 1
              constraint ck_visit_token_counter_next_value check (next_value > 0),
  updated_at  timestamptz not null default now(),
  constraint pk_visit_token_counter primary key (tenant_id, token_date)
);

alter table opd.visit_token_counter enable row level security;
alter table opd.visit_token_counter force row level security;

create policy p_visit_token_counter_tenant on opd.visit_token_counter
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- No delete: a day's counter row is kept forever so a token number is never reused for that day.
revoke all on opd.visit_token_counter from sahaj_app;
revoke all on opd.visit_token_counter from public;
grant select, insert, update on opd.visit_token_counter to sahaj_app;

comment on table opd.visit_token_counter is
  'One row per tenant per day: the next token number to allocate (SAH-9). Written only by opd.next_token_no(). Never deleted.';

-- Allocates the next token number for the tenant bound to this transaction, for current_date.
-- Volatile (it writes), SECURITY INVOKER (default): runs as sahaj_app and is still subject to the
-- RLS policy above, so it can never allocate a token for a tenant other than the one bound to the
-- transaction. Unlike patient.next_uhid(), this is NOT wired as a column DEFAULT on opd.visit,
-- because the token also needs to be stamped onto token_date consistently with created_at (see
-- the next migration) and the application needs the allocated number before building the Visit
-- response; the service layer calls opd.next_token_no() explicitly inside the same transaction
-- that inserts the opd.visit row (documented in docs/database/SAH-9.md).
create or replace function opd.next_token_no()
returns integer
language plpgsql
as $$
declare
  v_tenant uuid := platform.current_tenant();
  v_seq    integer;
begin
  insert into opd.visit_token_counter (tenant_id, token_date, next_value)
  values (v_tenant, current_date, 1)
  on conflict (tenant_id, token_date) do update
    set next_value = opd.visit_token_counter.next_value + 1,
        updated_at = now()
  returning next_value into v_seq;

  return v_seq;
end;
$$;

comment on function opd.next_token_no() is
  'Next token number for the current tenant and current_date, starting at 1 each day (SAH-9). Atomic per tenant-day via the row lock taken by the INSERT ... ON CONFLICT on opd.visit_token_counter. Must be called explicitly by the application inside the same transaction that inserts opd.visit (not a column default) so the allocated number is available for the response and matches the row''s token_date.';
