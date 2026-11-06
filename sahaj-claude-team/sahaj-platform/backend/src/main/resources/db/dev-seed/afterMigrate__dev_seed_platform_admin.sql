-- ADM-1 · Dev-only dummy platform admin (ADR-003 D5). NOT a migration and NOT in db/migration.
-- Loaded only when application-dev.yml adds classpath:db/dev-seed to spring.flyway.locations; runs as sahaj_migrator
-- after every Flyway run. The Java side (a @Profile("dev") customizer) reads SAHAJ_ADMIN_SEED_EMAIL, _NAME and
-- _PASSWORD from the environment, checks the password policy, hashes the password with BCrypt strength 12 in Java and
-- passes ONLY these three Flyway placeholders (always defined in dev; empty means "seed nothing"):
--   sahaj_admin_seed_email, sahaj_admin_seed_name, sahaj_admin_seed_password_hash
-- The plain password never reaches the database. Values sit in dollar-quoted strings, so quotes cannot break out.
-- Idempotent: re-running changes nothing except re-activating the admin and refreshing name and hash. The TOTP
-- enrolment columns are untouched on conflict (an enrolled developer stays enrolled) and null on first insert.

insert into platform.platform_admin (email, full_name, password_hash)
select lower(btrim($sq$${sahaj_admin_seed_email}$sq$)),
       coalesce(nullif(btrim($sq$${sahaj_admin_seed_name}$sq$), ''), 'Dev admin'),
       $sq$${sahaj_admin_seed_password_hash}$sq$
 where btrim($sq$${sahaj_admin_seed_email}$sq$) <> ''
   and $sq$${sahaj_admin_seed_password_hash}$sq$ <> ''
on conflict (email) do update
  set active              = true,
      full_name           = excluded.full_name,
      password_changed_at = case when platform.platform_admin.password_hash <> excluded.password_hash
                                 then now() else platform.platform_admin.password_changed_at end,
      password_hash       = excluded.password_hash,
      updated_at          = now();

-- A developer who locked themselves out can restart to get in again (dev only; throttle keys are keyed hashes, so
-- every lock is cleared).
delete from platform.platform_admin_auth_throttle
 where btrim($sq$${sahaj_admin_seed_email}$sq$) <> ''
   and $sq$${sahaj_admin_seed_password_hash}$sq$ <> '';
