-- SAH-79 WP-D, migration D2d. ADR-029 P15 (partition upkeep without a job or an extension).
-- 1. pharmacy.create_ledger_partitions(p_from_month, p_months): makes the missing monthly partitions of pharmacy.stock_ledger, each with
--    forced row level security, its two policies, no privilege for sahaj_app or PUBLIC, and its own statement-level TRUNCATE deny trigger.
--    Idempotent (an existing month is skipped). SECURITY INVOKER; executable by sahaj_migrator (the owner) only.
--    The row-level update/delete deny trigger is cloned from the parent by PostgreSQL when the partition is created.
-- 2. Calls it for Nov 2026 through Mar 2030 (41 months, to the end of financial year 2029-30). NO default partition: a month without a
--    partition fails the insert loudly instead of collecting rows that would later block creating that month.
-- Month boundaries are IST (+05:30), written with an explicit offset so the bounds do not depend on any session time zone.
-- Before April 2030 a forward migration must call this function again (the app warns from October 2029; ADR-029 P15).

set local lock_timeout = '30s';

create or replace function pharmacy.create_ledger_partitions(p_from_month date, p_months integer)
returns integer
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_first  date;
  m        date;
  part     text;
  made     integer := 0;
begin
  if p_from_month is null or p_months is null or p_months < 1 or p_months > 120 then
    raise exception 'p_from_month is required and p_months must be between 1 and 120' using errcode = 'invalid_parameter_value';
  end if;
  v_first := p_from_month - (pg_catalog.date_part('day', p_from_month)::integer - 1);
  for i in 0 .. p_months - 1 loop
    m := (v_first + pg_catalog.make_interval(months => i))::date;
    part := 'stock_ledger_y' || pg_catalog.to_char(m, 'YYYY') || 'm' || pg_catalog.to_char(m, 'MM');
    if pg_catalog.to_regclass(pg_catalog.format('pharmacy.%I', part)) is null then
      execute pg_catalog.format('create table pharmacy.%I partition of pharmacy.stock_ledger for values from (%L) to (%L)',
                                part,
                                pg_catalog.to_char(m, 'YYYY-MM-DD') || ' 00:00:00+05:30',
                                pg_catalog.to_char((m + interval '1 month')::date, 'YYYY-MM-DD') || ' 00:00:00+05:30');
      execute pg_catalog.format('alter table pharmacy.%I enable row level security', part);
      execute pg_catalog.format('alter table pharmacy.%I force row level security', part);
      execute pg_catalog.format('create policy %I on pharmacy.%I for select to sahaj_app using (tenant_id = platform.current_tenant())',
                                'p_' || part || '_select', part);
      execute pg_catalog.format('create policy %I on pharmacy.%I for insert to sahaj_app with check (tenant_id = platform.current_tenant())',
                                'p_' || part || '_insert', part);
      execute pg_catalog.format('revoke all on pharmacy.%I from sahaj_app', part);
      execute pg_catalog.format('revoke all on pharmacy.%I from public', part);
      -- Statement-level triggers are not inherited, so the partition gets its own (a direct TRUNCATE of a partition is refused too).
      if not exists (select 1 from pg_catalog.pg_trigger t
                      where t.tgrelid = pg_catalog.to_regclass(pg_catalog.format('pharmacy.%I', part))
                        and t.tgname = 'trg_stock_ledger_no_truncate') then
        execute pg_catalog.format('create trigger trg_stock_ledger_no_truncate before truncate on pharmacy.%I for each statement execute function pharmacy.deny_append_only_mutation()', part);
      end if;
      made := made + 1;
    end if;
  end loop;
  return made;
end;
$$;
revoke all on function pharmacy.create_ledger_partitions(date, integer) from public;
revoke all on function pharmacy.create_ledger_partitions(date, integer) from sahaj_app;

comment on function pharmacy.create_ledger_partitions(date, integer) is
  'SAH-79 (ADR-029 P15): makes the missing monthly partitions of pharmacy.stock_ledger from the month of p_from_month for p_months months (1 to 120), IST month bounds, with forced RLS, select and insert policies, no grant for sahaj_app, and the TRUNCATE deny trigger. Returns how many were made. Idempotent. Not executable by sahaj_app. Called by a forward migration before April 2030.';

-- Nov 2026 to Mar 2030 inclusive: 2 + 12 + 12 + 12 + 3 = 41 months.
select pharmacy.create_ledger_partitions(date '2026-11-01', 41);
