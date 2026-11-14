-- SAH-75 (ADR-026 D2, proof 3) · Before / after checksum of every TENANT table, to show that the SAH-75 migrations
-- (V2026_12_14_1000 to V2026_12_14_1060) changed no clinic, patient, visit, billing or admission row.
--
-- READ ONLY: only SELECT statements; the session is set to read-only first, so any write is refused.
-- Run it as the PostgreSQL superuser (so row level security does not hide any clinic's rows), on sahaj_dev, with the application
-- stopped, once BEFORE the migrations are applied and once AFTER (start the application once so Flyway runs, then stop it):
--
--   psql -h localhost -U postgres -d sahaj_dev -A -F '|' -f docs/database/SAH-75-seed-checksum.sql -o before.txt
--   ... apply the migrations ...
--   psql -h localhost -U postgres -d sahaj_dev -A -F '|' -f docs/database/SAH-75-seed-checksum.sql -o after.txt
--   diff before.txt after.txt
--
-- Part 1 (tenant tables) must be IDENTICAL in before.txt and after.txt: same table list, same row count, same checksum.
--   * Tables of schemas opd, patient, billing and ipd that have a tenant_id column (partition children are folded into their parent).
--   * checksum = md5 of all rows, each row as canonical jsonb text, sorted by that text (independent of physical order).
--   * opd.treatment_course and opd.treatment_course_sitting gain columns in SAH-75; the new columns are removed from the row text
--     before hashing, so the checksum compares exactly the columns that existed before.
--   * opd.emr_cancer_journey is new and is skipped (it does not exist before).
-- Part 2 (global reference tables) is EXPECTED to differ: sections, fields, options, placements and layouts grow (see docs/database/SAH-75.md).
--   Version 1 of every starter layout must have the same checksum before and after.
-- Part 3 (after only; it fails on a database that is not migrated yet, which is expected) shows that every old row holds the defaults
--   of the new columns: all counts must be 0 (or the stated value).
-- Do not log or share the output outside the team: it contains hashes only, no data, but the table names show the clinic's modules.

set default_transaction_read_only = on;
\qecho '--- PART 1: tenant tables (must be identical before and after) ---'

with t as (
  select n.nspname as schema_name, c.relname as table_name
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
   where n.nspname in ('opd', 'patient', 'billing', 'ipd')
     and c.relkind in ('r', 'p')
     and not c.relispartition
     and c.relname <> 'emr_cancer_journey'
     and exists (select 1 from pg_attribute a
                  where a.attrelid = c.oid and a.attname = 'tenant_id' and a.attnum > 0 and not a.attisdropped)
), spec as (
  select schema_name, table_name,
         case table_name
           when 'treatment_course' then '{estimate_signed}'
           when 'treatment_course_sitting' then '{missed_at,missed_by_membership_id,removed_at,removed_by_membership_id,done_source,done_by_membership_id,regimen_key}'
           else '{}'
         end as excluded_keys
    from t
)
select schema_name || '.' || table_name as tenant_table,
       (xpath('/row/n/text()', query_to_xml(
          format('select count(*) as n from %I.%I', schema_name, table_name), false, true, '')))[1]::text::bigint as row_count,
       (xpath('/row/h/text()', query_to_xml(
          format('select coalesce(md5(string_agg(j, %L order by j)), %L) as h from (select (to_jsonb(r) - %L::text[])::text as j from %I.%I r) s',
                 '|', 'empty', excluded_keys, schema_name, table_name), false, true, '')))[1]::text as checksum
  from spec
 order by 1;

\qecho '--- PART 2: global reference tables (expected to differ; layout version 1 must not) ---'

select 'opd.emr_section' as global_table, count(*) as row_count, md5(string_agg(s::text, '|' order by s.section_key)) as checksum from opd.emr_section s
union all
select 'opd.emr_section_field', count(*), md5(string_agg(f.section_key || '/' || f.field_key || '/' || f.label || '/' || f.field_type, '|' order by f.section_key, f.field_key)) from opd.emr_section_field f
union all
select 'opd.emr_section_field_option', count(*), md5(string_agg(o.section_key || '/' || o.field_key || '/' || o.option_key || '/' || o.label, '|' order by o.section_key, o.field_key, o.option_key)) from opd.emr_section_field_option o
union all
select 'opd.emr_starter_department_section', count(*), md5(string_agg(p::text, '|' order by p.starter_key, p.section_key)) from opd.emr_starter_department_section p
union all
select 'opd.emr_starter_layout (version 1 only)', count(*), md5(string_agg(l.starter_key || ':' || l.layout::text, '|' order by l.starter_key)) from opd.emr_starter_layout l where l.layout_version = 1
union all
select 'opd.emr_starter_layout (all versions)', count(*), md5(string_agg(l.starter_key || ':' || l.layout_version || ':' || l.layout::text, '|' order by l.starter_key, l.layout_version)) from opd.emr_starter_layout l
order by 1;

select starter_key, current_layout_version from opd.emr_starter_department order by position;

\qecho '--- PART 3 (after only): old rows hold the defaults of the new columns; every count must be 0 ---'

select 'treatment_course with estimate_signed' as check_name, count(*) as must_be_zero
  from opd.treatment_course where estimate_signed
union all
select 'sittings with a missed, removed, done_source, done_by or regimen value', count(*)
  from opd.treatment_course_sitting
 where missed_at is not null or missed_by_membership_id is not null or removed_at is not null or removed_by_membership_id is not null
    or done_source is not null or done_by_membership_id is not null or regimen_key is not null
union all
select 'cancer journey rows', count(*) from opd.emr_cancer_journey
union all
select 'library fields not DEFAULT presentation outside the new Dental sections and the two Gynae sections', count(*)
  from opd.emr_section_field
 where presentation <> 'DEFAULT'
   and section_key not in ('dental_medhx', 'dental_hx', 'dental_pain', 'dental_extraoral', 'dental_intraoral', 'dental_xray',
                           'menstrual', 'contraception')
union all
select 'library options with is_alert outside the new sections', count(*)
  from opd.emr_section_field_option
 where is_alert
   and section_key not in ('dental_medhx', 'dental_hx', 'dental_pain', 'dental_extraoral', 'dental_intraoral', 'dental_xray')
union all
select 'library options with a hint outside contraception.method', count(*)
  from opd.emr_section_field_option
 where hint is not null and (section_key, field_key) <> ('contraception', 'method')
union all
select 'starter departments not at version 2 (13 expected at 2, og and onco at 3): rows that break that rule', count(*)
  from opd.emr_starter_department
 where current_layout_version <> case when starter_key in ('og', 'onco') then 3 else 2 end;

-- Stated values after the 0.7.1 follow-up (V2026_12_14_1040 to V2026_12_14_1060): sections 107, fields 335, options 244, options with a hint 6,
-- starter layout rows 32 (15 + 15 + 2). Part 2 above shows the counts; the next query shows the hint and layout counts.
select (select count(*) from opd.emr_section_field_option where hint is not null) as options_with_hint_expect_6,
       (select count(*) from opd.emr_starter_layout) as layout_rows_expect_32,
       (select count(*) from opd.emr_starter_layout where layout_version = 3) as layout_v3_rows_expect_2;
