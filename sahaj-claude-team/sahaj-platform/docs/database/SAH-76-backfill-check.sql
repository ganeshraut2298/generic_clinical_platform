-- SAH-76 (DEC-056 option A) · Proof on a database that held orders BEFORE the migration
-- (V2026_12_15_1000__ipd_nursing_orders_to_execution.sql): (a) FORCE row level security is back on, (b) version 1 was backfilled.
--
-- READ ONLY: SELECT only, session set to read-only first. Output is counts, names and PASS/FAIL only: no patient data.
-- Run on sahaj_dev AFTER the dev server has applied the migration (stop the server afterwards), as the PostgreSQL superuser:
--
--   psql -h localhost -U postgres -d sahaj_dev -A -F '|' -f docs/database/SAH-76-backfill-check.sql
--
-- Why the superuser: the tables are FORCE row level security, so even the owner sahaj_migrator is filtered by the policies and would
-- see no tenant rows (no app.tenant_id is set). A superuser bypasses row level security, so check 2 counts every clinic's rows.
-- Do NOT run it as sahaj_app: checks 2 and 3 would then wrongly report zero rows or fail.
-- Every line starting PASS or FAIL is one check; the whole run is good when no line starts with FAIL.
-- Check 6 is information only (INFO).

set default_transaction_read_only = on;

\qecho '--- 1. Row level security: enabled and FORCED on the 9 new tables, ipd.medicine_order and ipd.dose_administration ---'
with expected(tbl) as (
  values ('ipd.medicine_order'), ('ipd.dose_administration'),
         ('ipd.medicine_order_version'), ('ipd.order_hold'), ('ipd.order_notice'), ('ipd.medicine_order_query'),
         ('ipd.pharmacist_review'), ('ipd.dose_correction'), ('ipd.nursing_profile'),
         ('ipd.nursing_profile_template'), ('ipd.drug_code')
)
select case when c.oid is not null and c.relrowsecurity and c.relforcerowsecurity then 'PASS' else 'FAIL' end as result,
       e.tbl as table_name,
       coalesce(c.relrowsecurity::text, 'missing') as rls_enabled,
       coalesce(c.relforcerowsecurity::text, 'missing') as rls_forced
  from expected e
  left join pg_class c on c.oid = to_regclass(e.tbl)
 order by e.tbl;

\qecho '--- 2. Version-1 backfill: orders versus version-1 rows, per clinic (tenant ids are random uuids; counts only) ---'
select case when o.n = coalesce(v.n, 0) then 'PASS' else 'FAIL' end as result,
       o.tenant_id, o.n as orders, coalesce(v.n, 0) as version_1_rows
  from (select tenant_id, count(*) as n from ipd.medicine_order group by tenant_id) o
  left join (select tenant_id, count(distinct order_id) as n from ipd.medicine_order_version
              where version_no = 1 group by tenant_id) v using (tenant_id)
 order by o.tenant_id;

select case when (select count(*) from ipd.medicine_order)
               = (select count(distinct (tenant_id, order_id)) from ipd.medicine_order_version where version_no = 1)
            then 'PASS' else 'FAIL' end as result,
       'all clinics together' as scope,
       (select count(*) from ipd.medicine_order) as orders,
       (select count(distinct (tenant_id, order_id)) from ipd.medicine_order_version where version_no = 1) as version_1_rows;

-- Version rows that point at no order (must be none).
select case when count(*) = 0 then 'PASS' else 'FAIL' end as result,
       'version rows without an order head' as check_name, count(*) as n
  from ipd.medicine_order_version v
 where not exists (select 1 from ipd.medicine_order o where o.tenant_id = v.tenant_id and o.id = v.order_id);

\qecho '--- 3. Heads whose current_version row is missing (must be 0) ---'
select case when count(*) = 0 then 'PASS' else 'FAIL' end as result,
       'orders with no row for current_version' as check_name, count(*) as n
  from ipd.medicine_order o
 where not exists (select 1 from ipd.medicine_order_version v
                    where v.tenant_id = o.tenant_id and v.order_id = o.id and v.version_no = o.current_version);

\qecho '--- 4. The 5 re-created constraints exist, with the right type ---'
with expected(conrelid, conname, contype) as (
  values ('ipd.medicine_order',      'ck_medicine_order_status',                        'c'),
         ('ipd.medicine_order',      'ck_medicine_order_fields_by_status',              'c'),
         ('ipd.dose_administration', 'ck_dose_administration_reason',                   'c'),
         ('ipd.dose_administration', 'ck_dose_administration_identity_scan_by_outcome', 'c'),
         ('ipd.dose_administration', 'uq_dose_administration_order_slot',               'u')
)
select case when k.oid is not null and k.convalidated then 'PASS' else 'FAIL' end as result,
       e.conname as constraint_name, e.conrelid as table_name,
       case when k.oid is null then 'missing' when k.convalidated then 'validated' else 'not validated' end as state
  from expected e
  left join pg_constraint k on k.conrelid = to_regclass(e.conrelid) and k.conname = e.conname and k.contype = e.contype
 order by e.conrelid, e.conname;

-- The slot unique must now be on (tenant_id, order_id, slot_at, attempt).
select case when pg_get_constraintdef(k.oid) like 'UNIQUE (tenant_id, order_id, slot_at, attempt)%' then 'PASS' else 'FAIL' end as result,
       k.conname as constraint_name, pg_get_constraintdef(k.oid) as definition
  from pg_constraint k
 where k.conrelid = to_regclass('ipd.dose_administration') and k.conname = 'uq_dose_administration_order_slot';

\qecho '--- 5. The 14 global drugs have route_key (and dose_units) ---'
select case when count(*) = 14 and count(*) filter (where route_key is not null and dose_units is not null) = 14
            then 'PASS' else 'FAIL' end as result,
       count(*) as drugs, count(*) filter (where route_key is not null) as with_route_key,
       count(*) filter (where dose_units is not null) as with_dose_units
  from ipd.drug;

\qecho '--- 6. Old dose rows (information): GIVEN doses with scan_method null are read as SCAN and are not undoable ---'
select 'INFO' as result,
       count(*) filter (where outcome = 'GIVEN' and scan_method is null) as given_scan_method_null,
       count(*) filter (where outcome = 'GIVEN' and scan_method is null and undo_until is null) as given_scan_null_and_not_undoable,
       count(*) filter (where outcome <> 'GIVEN' and scan_method is not null) as not_given_with_scan_method_must_be_0,
       count(*) as all_dose_rows
  from ipd.dose_administration;
