-- SAH-8 · patient.patient_uhid_counter + patient.next_uhid()
-- UHID format is "<prefix>-%05d" (story default prefix "PT"; no per-tenant prefix convention
-- exists yet in platform.clinic_profile, so every tenant uses "PT" until a future story adds one).
-- A native PostgreSQL sequence is per-database, not per-tenant, and CREATE SEQUENCE cannot be run
-- once per tenant at signup time from inside a normal request transaction (DDL, needs its own
-- privileges, and 10,000 clinics would mean 10,000 sequences to manage). Instead this is one
-- narrow counter table with one row per tenant, and a single statement that allocates the next
-- number with INSERT ... ON CONFLICT DO UPDATE ... RETURNING, which takes a row lock on that
-- tenant's counter row and is therefore race-safe under concurrent registrations for the same
-- tenant (two different tenants never block each other).
create table if not exists patient.patient_uhid_counter (
  tenant_id   uuid primary key
              constraint fk_patient_uhid_counter_tenant references platform.tenant (id),
  next_value  bigint not null default 1
              constraint ck_patient_uhid_counter_next_value check (next_value > 0),
  updated_at  timestamptz not null default now()
);

alter table patient.patient_uhid_counter enable row level security;
alter table patient.patient_uhid_counter force row level security;

create policy p_patient_uhid_counter_tenant on patient.patient_uhid_counter
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- No delete: a counter is never removed once a tenant has registered a patient, so a UHID is
-- never reused even if every patient for that tenant were (hypothetically) removed later.
revoke all on patient.patient_uhid_counter from sahaj_app;
revoke all on patient.patient_uhid_counter from public;
grant select, insert, update on patient.patient_uhid_counter to sahaj_app;

comment on table patient.patient_uhid_counter is
  'One row per tenant: the next UHID sequence number to allocate (SAH-8). Written only by patient.next_uhid(). Never deleted.';

-- Allocates and formats the next UHID for the tenant bound to this transaction. Volatile (it
-- writes), SECURITY INVOKER (default): runs as sahaj_app, which already has the grants above and
-- is still subject to the RLS policy, so a function call can never allocate a number for a tenant
-- other than the one bound to the transaction.
create or replace function patient.next_uhid()
returns text
language plpgsql
as $$
declare
  v_tenant uuid := platform.current_tenant();
  v_seq    bigint;
begin
  insert into patient.patient_uhid_counter (tenant_id, next_value)
  values (v_tenant, 1)
  on conflict (tenant_id) do update
    set next_value = patient.patient_uhid_counter.next_value + 1,
        updated_at = now()
  returning next_value into v_seq;

  return 'PT-' || lpad(v_seq::text, 5, '0');
end;
$$;

comment on function patient.next_uhid() is
  'Next UHID for the current tenant, formatted PT-00001, PT-00002, ... (SAH-8). Atomic per tenant via the row lock taken by the INSERT ... ON CONFLICT on patient.patient_uhid_counter.';
