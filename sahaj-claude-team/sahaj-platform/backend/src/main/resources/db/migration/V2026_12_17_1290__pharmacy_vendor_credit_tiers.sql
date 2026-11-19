-- SAH-78 hardening, DEC-078 item 5 and DEC-075 (two vendor credit tiers). Forward migration; V2026_12_17_1200 is NOT edited.
-- Contract 0.2.1 puts damagedCreditPercent and expiredCreditPercent on the vendor create, update and read shapes of SAH-78, so the
-- SAH-78 vendor code of the next run reads and writes them. They therefore belong here and not in WP-E (the WP-E tables only read them).
-- Both are nullable: null means the standard return_credit_pct applies. Existing rows get null (no rewrite; metadata-only change).
-- Grants are table-level (select, insert, update to sahaj_app), so the new columns are covered. RLS is unchanged.

set local lock_timeout = '30s';

alter table pharmacy.vendor
  add column if not exists damaged_credit_pct numeric(5,2),
  add column if not exists expired_credit_pct numeric(5,2);

do $$
begin
  if not exists (select 1 from pg_catalog.pg_constraint
                  where conname = 'ck_vendor_damaged_credit' and conrelid = 'pharmacy.vendor'::regclass) then
    alter table pharmacy.vendor
      add constraint ck_vendor_damaged_credit check (damaged_credit_pct between 0 and 100);
  end if;
  if not exists (select 1 from pg_catalog.pg_constraint
                  where conname = 'ck_vendor_expired_credit' and conrelid = 'pharmacy.vendor'::regclass) then
    alter table pharmacy.vendor
      add constraint ck_vendor_expired_credit check (expired_credit_pct between 0 and 100);
  end if;
end
$$;

comment on column pharmacy.vendor.damaged_credit_pct is
  'Credit level 0 to 100 for a vendor-return line whose reason is damaged on receipt (SAH-79 WP-E). Null: return_credit_pct applies.';
comment on column pharmacy.vendor.expired_credit_pct is
  'Credit level 0 to 100 for a vendor-return line whose batch is expired (SAH-79 WP-E). Null: return_credit_pct applies.';
