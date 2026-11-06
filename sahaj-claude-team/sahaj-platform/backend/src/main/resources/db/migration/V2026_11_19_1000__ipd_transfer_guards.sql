-- SAH-26 security review M1 and L1. Forward-only.
-- M1: the database now refuses two ADMITTED admissions in one bed, and any change of ipd.admission.bed_id that is
--     not a recorded bed transfer. L1: ward names are unique even when zero-width joiners are added or removed.

-- 0. Pre-flight: a clear error (counts only, no names or ids) if existing data would break a new index.
do $$
declare
  v_beds  bigint;
  v_wards bigint;
begin
  select count(*) into v_beds from (
    select 1 from ipd.admission where status = 'ADMITTED' group by tenant_id, bed_id having count(*) > 1) d;
  if v_beds > 0 then
    raise exception 'SAH-26 guard: % bed(s) hold more than one ADMITTED admission; fix the data first', v_beds;
  end if;
  select count(*) into v_wards from (
    select 1 from ipd.ward group by tenant_id, lower(translate(name, U&'\200C\200D', '')) having count(*) > 1) d;
  if v_wards > 0 then
    raise exception 'SAH-26 guard: % ward name(s) differ only by zero-width joiners; rename them first', v_wards;
  end if;
end
$$;

-- 1. One ADMITTED admission per bed (a transfer moves the admission, so the old bed is then free of ADMITTED rows).
-- Also serves "which admission is in this bed" for ADMITTED rows. Existing ix_admission_tenant_bed stays (history check).
create unique index if not exists ux_admission_tenant_bed_admitted
  on ipd.admission (tenant_id, bed_id) where status = 'ADMITTED';

-- 2. bed_id of an admission changes only inside a bed transfer: both rows ADMITTED and a matching ipd.bed_transfer row
-- written by the same transaction (transferred_at defaults to now() = transaction start; sahaj_app cannot set it).
-- SECURITY INVOKER: the select on ipd.bed_transfer runs as the caller, so RLS applies (same tenant only).
-- Fixed search_path; every object is schema-qualified. Admit is an insert and discharge does not touch bed_id,
-- so neither fires this trigger.
create or replace function ipd.guard_admission_bed_change()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if new.bed_id is distinct from old.bed_id then
    if not (old.status = 'ADMITTED' and new.status = 'ADMITTED'
            and exists (select 1
                        from ipd.bed_transfer t
                        where t.tenant_id = new.tenant_id
                          and t.admission_id = new.id
                          and t.from_bed_id = old.bed_id
                          and t.to_bed_id = new.bed_id
                          and t.transferred_at = now())) then
      raise exception 'bed change refused'
        using errcode = 'insufficient_privilege';
    end if;
  end if;
  return new;
end;
$$;

-- Trigger functions are not executed through EXECUTE privilege at fire time; nobody needs to call this directly.
revoke all on function ipd.guard_admission_bed_change() from public;
revoke all on function ipd.guard_admission_bed_change() from sahaj_app;

drop trigger if exists trg_admission_guard_bed_change on ipd.admission;
create trigger trg_admission_guard_bed_change
  before update of bed_id on ipd.admission
  for each row
  when (new.bed_id is distinct from old.bed_id)
  execute function ipd.guard_admission_bed_change();

-- 3. Ward names that differ only by U+200C / U+200D are duplicates. ux_ward_tenant_lower_name (SAH-22) stays.
create unique index if not exists ux_ward_tenant_name_nojoiners
  on ipd.ward (tenant_id, lower(translate(name, U&'\200C\200D', '')));

comment on function ipd.guard_admission_bed_change() is 'SAH-26 guard: ipd.admission.bed_id changes only inside a recorded bed transfer (both rows ADMITTED, matching ipd.bed_transfer row from the same transaction). SQLSTATE 42501.';
comment on index ipd.ux_admission_tenant_bed_admitted is 'SAH-26 guard: at most one ADMITTED admission per bed.';
comment on index ipd.ux_ward_tenant_name_nojoiners is 'SAH-26 guard: ward name unique ignoring case and zero-width joiners (U+200C, U+200D).';
