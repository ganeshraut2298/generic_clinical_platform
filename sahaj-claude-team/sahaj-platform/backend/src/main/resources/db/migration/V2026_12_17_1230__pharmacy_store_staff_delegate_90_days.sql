-- SAH-78 WP-A review fix round 1 (security L3, DEC-068 Q1 answered A). Forward migration; V2026_12_17_1010 is NOT edited.
-- A delegate period is at most 90 calendar days counting both the first and the last day, so delegate_to - delegate_from <= 89.
-- V2026_12_17_1010 allowed <= 90, which is 91 calendar days. Only the check constraint ck_store_staff_delegate_dates on the new,
-- unmerged table pharmacy.store_staff is replaced (same name); no column, no index, no policy, no row is touched.
--
-- Safety for databases that already hold rows (sahaj_dev, sahaj_test, shared with another session): the new check is added NOT VALID
-- (enforced for every new or changed row at once, existing rows are not scanned in the ALTER) and then validated inside a guarded
-- block. If an old row has a 90-day gap (91 calendar days) the validation fails, a WARNING is raised, the migration still succeeds and
-- the constraint stays NOT VALID. No data is ever updated or deleted here: the owner of that row fixes it by hand and runs
--   alter table pharmacy.store_staff validate constraint ck_store_staff_delegate_dates;
-- On a database that is built from scratch the table is empty and the constraint is validated.

set local lock_timeout = '30s';

alter table pharmacy.store_staff drop constraint if exists ck_store_staff_delegate_dates;

alter table pharmacy.store_staff
  add constraint ck_store_staff_delegate_dates check (
    (store_role = 'DELEGATE' and delegate_from is not null and delegate_to is not null
       and delegate_to >= delegate_from and delegate_to - delegate_from <= 89)
    or (store_role <> 'DELEGATE' and delegate_from is null and delegate_to is null)) not valid;

do $$
begin
  alter table pharmacy.store_staff validate constraint ck_store_staff_delegate_dates;
exception when check_violation then
  raise warning 'pharmacy.store_staff has a delegate row longer than 90 calendar days; ck_store_staff_delegate_dates stays NOT VALID (new rows are checked). Fix the row, then validate the constraint by hand.';
end;
$$;

comment on constraint ck_store_staff_delegate_dates on pharmacy.store_staff is
  'A DELEGATE has both dates, delegate_to >= delegate_from and at most 90 calendar days counting both ends (delegate_to - delegate_from <= 89, DEC-068 Q1); any other role has both dates null.';
