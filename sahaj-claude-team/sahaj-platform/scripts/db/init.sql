-- Sahaj database bootstrap. Run once as the PostgreSQL superuser (postgres):
--   node scripts/setup/create-database.mjs        (reads passwords from .env)
-- Creates two roles, two databases (dev and test) and one schema per module.
-- Tables are NOT created here: they come from Flyway migrations written by the db-engineer agent.
\set ON_ERROR_STOP on

-- Roles. sahaj_migrator owns the schema (Flyway runs as it).
-- sahaj_app is what the Spring Boot app uses: not owner, not superuser, cannot bypass row-level security.
SELECT format('CREATE ROLE sahaj_migrator LOGIN PASSWORD %L', :'migrator_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sahaj_migrator') \gexec
SELECT format('CREATE ROLE sahaj_app LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD %L', :'app_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sahaj_app') \gexec
ALTER ROLE sahaj_migrator WITH LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD :'migrator_password';
ALTER ROLE sahaj_app WITH LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD :'app_password';

SELECT 'CREATE DATABASE sahaj_dev OWNER sahaj_migrator ENCODING ''UTF8'' TEMPLATE template0'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'sahaj_dev') \gexec
SELECT 'CREATE DATABASE sahaj_test OWNER sahaj_migrator ENCODING ''UTF8'' TEMPLATE template0'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'sahaj_test') \gexec
ALTER DATABASE sahaj_dev SET timezone TO 'UTC';
ALTER DATABASE sahaj_test SET timezone TO 'UTC';

\connect sahaj_dev
\ir schemas.sql
\connect sahaj_test
\ir schemas.sql
