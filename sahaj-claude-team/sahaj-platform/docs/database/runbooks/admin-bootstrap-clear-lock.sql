-- SAH-42 / ADR-018 Revision 3 (OQ-42-14) · Runbook: clear a bootstrap-registration lockout by hand.
-- STATUS: written by reading only. NOT YET EXECUTED; the first run is the QA gate.
-- RUN BY A HUMAN, NOT BY FLYWAY. Not a migration, not a callback.
--
--   psql -v ON_ERROR_STOP=1 -h localhost -U sahaj_migrator -d sahaj_dev -f docs/database/runbooks/admin-bootstrap-clear-lock.sql
--
-- Password: let psql ask for it, or set PGPASSWORD in the shell session, or use ~/.pgpass. Never type it on the command
-- line and never put it in this file. Use -d sahaj_test for the test database. The application may keep running.
--
-- When: you typed a wrong PIN five times (or a local process did), the page says "Registration is locked" and you do not
-- want to wait the 60 minutes. The ticket you may hold is cancelled too: unlock again with the PIN afterwards. Also use
-- it after a server failure on the 5th try (the try is counted and the lock stays even when the PIN was right).
--
-- What it does, in ONE transaction (any error rolls everything back; psql with ON_ERROR_STOP stops at the first error):
--   1. Refuses to run outside sahaj_dev / sahaj_test, or when the state table does not exist yet.
--   2. Waits at most 10 seconds for the state row (a claim or a judge in progress holds it for an instant), then sets
--      failures = 0, locked_until = null, clears the three ticket columns and (Revision 3) the two lease columns
--      try_id and try_expires_at. Nothing else in the row changes. A try that is being judged at that moment then
--      finds no lease: its judge call raises SB004 (the caller answers a bodyless 500) and the try stays uncounted,
--      which is what clearing by hand means.
--   3. Writes ONE append-only audit row: kind AUTH, action ADMIN_BOOTSTRAP_LOCK_CLEARED, outcome APPLIED, summary
--      "Bootstrap registration lock cleared by hand". No admin id, no address, no request id, no PIN, no value.
--   4. Checks itself: raises (and rolls back) if the row is not clear afterwards.
--
-- Idempotent: when the row is already clear (count 0, no lock, no ticket, no lease) it changes nothing and writes NO audit
-- row, so a second run leaves no trace. It never deletes a row and never touches platform_admin or any other table.
-- It does not reopen registration after an admin exists: once any admin exists both bootstrap calls answer the empty 404
-- whatever this row says.
-- Platform scope only. Touches no tenant table and sets no app.tenant_id. Prints counts and the database name only.
-- Design note: docs/database/SAH-42.md

-- ON_ERROR_STOP check (same construction as dev-tools-sample-admin-cleanup.sql). psql always defines the variable (value
-- off unless set), so test its VALUE, not whether it is defined.
\set sahaj_onerror_ok off
\if :{?ON_ERROR_STOP}
  \if :ON_ERROR_STOP
    \set sahaj_onerror_ok on
  \endif
\endif
\if :sahaj_onerror_ok
\else
  \echo 'Stopped: run this file with psql -v ON_ERROR_STOP=1 so that an error cannot be followed by later steps.'
  \quit
\endif
\unset sahaj_onerror_ok

begin;

-- Never wait for ever behind a running unlock. In ADR-018 Revision 3 the unlock holds the row only for the short claim
-- (count first); the PIN compare runs afterwards, outside any transaction, so a wait here is brief.
set local lock_timeout = '10s';

-- 1. Pre-flight.
do $pre$
begin
  if current_database() !~ '^sahaj_(dev|test)$' then
    raise exception 'Refused: database "%" is not a Sahaj dev or test database (sahaj_dev, sahaj_test). The bootstrap registration exists only there (ADR-018).', current_database();
  end if;
  if to_regclass('platform.platform_admin_bootstrap_state') is null then
    raise exception 'Nothing to clear: platform.platform_admin_bootstrap_state does not exist in database "%". Start the backend once with -Pdev-tools and the bootstrap switch on so that the dev-tools callback creates it.', current_database();
  end if;
  if to_regclass('platform.platform_admin_audit') is null then
    raise exception 'platform.platform_admin_audit is missing: activate the parked admin migrations first (DEC-016).';
  end if;
  -- Revision 3: a state table made by an earlier run has no lease columns until the dev-tools callback has run once more.
  if (select count(*)
        from pg_attribute a
       where a.attrelid = 'platform.platform_admin_bootstrap_state'::regclass
         and a.attname in ('try_id', 'try_expires_at')
         and not a.attisdropped) <> 2 then
    raise exception 'platform.platform_admin_bootstrap_state has no lease columns (try_id, try_expires_at) in database "%". Start the backend once with -Pdev-tools and the bootstrap switch on so that the dev-tools callback adds them, then run this again.', current_database();
  end if;
end
$pre$;

-- 2 and 3. Clear and audit, together.
do $clear$
declare
  v_db         text := current_database();
  v_failures   smallint;
  v_locked     timestamptz;
  v_has_ticket boolean;
  v_has_lease  boolean;
begin
  select s.failures, s.locked_until, (s.ticket_hash is not null), (s.try_id is not null)
    into v_failures, v_locked, v_has_ticket, v_has_lease
    from platform.platform_admin_bootstrap_state s
   where s.id = 1
     for update;

  if not found then
    raise notice 'No bootstrap state row yet in database "%": nothing to clear.', v_db;
    return;
  end if;

  if v_failures = 0 and v_locked is null and not v_has_ticket and not v_has_lease then
    raise notice 'The bootstrap registration is not locked and holds no ticket or try in database "%": nothing changed.', v_db;
    return;
  end if;

  update platform.platform_admin_bootstrap_state s
     set failures = 0,
         locked_until = null,
         ticket_hash = null,
         binding_hash = null,
         ticket_expires_at = null,
         try_id = null,
         try_expires_at = null,
         updated_at = clock_timestamp()
   where s.id = 1;

  insert into platform.platform_admin_audit
    (kind, action, summary, outcome)
  values
    ('AUTH', 'ADMIN_BOOTSTRAP_LOCK_CLEARED', 'Bootstrap registration lock cleared by hand', 'APPLIED');

  raise notice 'Bootstrap registration lock cleared in database "%" (was: % wrong PIN(s), %locked, %ticket, %try being judged).',
    v_db, v_failures,
    case when v_locked is null then 'not ' else '' end,
    case when v_has_ticket then 'with a ' else 'no ' end,
    case when v_has_lease then 'a ' else 'no ' end;
end
$clear$;

-- 4. Self-check. Raising rolls the whole run back.
do $check$
declare
  v_db text := current_database();
begin
  if exists (select 1
               from platform.platform_admin_bootstrap_state s
              where s.id = 1
                and (s.failures <> 0 or s.locked_until is not null or s.ticket_hash is not null
                     or s.binding_hash is not null or s.ticket_expires_at is not null
                     or s.try_id is not null or s.try_expires_at is not null)) then
    raise exception 'Clear-lock failed: the bootstrap state row is not clear in database "%".', v_db;
  end if;
  raise notice 'Clear-lock check passed for database "%".', v_db;
end
$check$;

commit;
