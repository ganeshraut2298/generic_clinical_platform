-- SAH-53 / DEC-049 item 3 · Automatic monthly partition upkeep for opd.emr_record.
-- Additive only: two new functions. No table, column, data or privilege on an existing object changes.
-- Design note: docs/database/SAH-53.md "Partition upkeep".
--
-- 1. opd.emr_record_upkeep_partitions(p_months_ahead)  run by sahaj_migrator from the Flyway callback afterMigrate
--    (docs/database/SAH-53-partition-upkeep-callback.sql, placed in backend/src/main/resources/db/guard by the
--    java-developer), so it runs on EVERY migrate in dev, test and prod. A versioned migration would run once only.
--    It makes the current UTC month and the next p_months_ahead months when they are missing. It never fails a deploy
--    for a month it cannot make: that month is skipped and reported (RAISE WARNING, which Flyway writes to the log, and a
--    result row).
-- 2. opd.emr_record_future_partition_count()  read only, for the application (sahaj_app): how many monthly partitions
--    exist after the current UTC month. The application logs a warning when it is under 3.

-- ---------------------------------------------------------------------------------------------
-- 1. Upkeep
-- ---------------------------------------------------------------------------------------------
-- lock_timeout (a function setting, restored when the function ends): creating a partition needs a strong lock on the
-- parent table; the step waits at most 10 seconds and then skips the month instead of holding up the deploy. The next
-- migrate tries again.
create or replace function opd.emr_record_upkeep_partitions(p_months_ahead integer default 12)
returns table (partition_month date, outcome text)
language plpgsql
set lock_timeout = '10s'
as $$
declare
  v_first date := date_trunc('month', now() at time zone 'UTC')::date;
  m       date;
  part    text;
begin
  if p_months_ahead is null or p_months_ahead < 0 or p_months_ahead > 60 then
    raise exception 'p_months_ahead must be between 0 and 60' using errcode = '22023';
  end if;

  for i in 0 .. p_months_ahead loop
    m := (v_first + make_interval(months => i))::date;
    part := 'emr_record_' || to_char(m, 'YYYY_MM');

    -- Nothing to do (the usual case on every start): no lock is taken.
    if to_regclass(format('opd.%I', part)) is not null then
      continue;
    end if;

    begin
      -- Creates the partition with row level security enabled and forced, its policy, and no privilege for sahaj_app
      -- or PUBLIC. The sub-transaction of this block undoes every part of it when the create fails.
      perform opd.emr_record_ensure_partitions(m, 1);
      partition_month := m;
      outcome := 'created';
      return next;
    exception
      when check_violation then
        -- The default partition already holds rows of this month (a missed month): PostgreSQL refuses the new partition.
        -- Move those rows first (a human decision); the month is reported on every migrate until then.
        raise warning 'opd.emr_record: partition % not created, the default partition holds rows of that month', part;
        partition_month := m;
        outcome := 'skipped_default_partition_has_rows';
        return next;
      when lock_not_available then
        raise warning 'opd.emr_record: partition % not created, the table is busy; it is retried on the next migrate', part;
        partition_month := m;
        outcome := 'skipped_lock_timeout';
        return next;
    end;
  end loop;
end;
$$;

revoke all on function opd.emr_record_upkeep_partitions(integer) from public;
revoke all on function opd.emr_record_upkeep_partitions(integer) from sahaj_app;

-- ---------------------------------------------------------------------------------------------
-- 2. Read-only count for the application
-- ---------------------------------------------------------------------------------------------
-- Counts the attached monthly partitions (name emr_record_YYYY_MM, made by the function above) that start after the current
-- UTC month. The default partition is not counted. Reads the catalog only: no row of opd.emr_record, no clinic data.
-- SECURITY DEFINER with a pinned search_path (pg_catalog first, pg_temp last); every object is schema-qualified.
create or replace function opd.emr_record_future_partition_count()
returns integer
language sql
stable
security definer
set search_path = pg_catalog, pg_temp
as $$
  select count(*)::integer
    from pg_catalog.pg_inherits i
    join pg_catalog.pg_class c on c.oid = i.inhrelid
   where i.inhparent = 'opd.emr_record'::pg_catalog.regclass
     and c.relname ~ '^emr_record_[0-9]{4}_(0[1-9]|1[0-2])$'
     and pg_catalog.to_date(pg_catalog.substr(c.relname::pg_catalog.text, 12), 'YYYY_MM')
           > pg_catalog.date_trunc('month', pg_catalog.now() at time zone 'UTC')::pg_catalog.date
$$;

revoke all on function opd.emr_record_future_partition_count() from public;
grant execute on function opd.emr_record_future_partition_count() to sahaj_app;

comment on function opd.emr_record_upkeep_partitions(integer) is
  'SAH-53 / DEC-049: makes the missing monthly partitions of opd.emr_record from the current UTC month to p_months_ahead months later (default 12), via opd.emr_record_ensure_partitions. Idempotent. A month the default partition already holds rows for, or that is locked for more than 10 seconds, is skipped with a warning and returned as a row; it never fails. Run by Flyway afterMigrate as sahaj_migrator. Not executable by sahaj_app.';
comment on function opd.emr_record_future_partition_count() is
  'SAH-53 / DEC-049: number of monthly partitions of opd.emr_record that start after the current UTC month (the default partition is not counted). Read-only, SECURITY DEFINER, search_path pinned, executable by sahaj_app only. The application logs a warning when it is under 3.';
