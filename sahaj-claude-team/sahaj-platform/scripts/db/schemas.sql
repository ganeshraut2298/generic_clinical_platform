-- One schema per module, owned by sahaj_migrator; sahaj_app gets data access only.
\set ON_ERROR_STOP on
CREATE EXTENSION IF NOT EXISTS pgcrypto;          -- gen_random_uuid(), digest()
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
DO $$
DECLARE s text;
BEGIN
  FOREACH s IN ARRAY ARRAY['platform','patient','opd','ipd','hra','billing'] LOOP
    EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION sahaj_migrator', s);
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO sahaj_app', s);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE sahaj_migrator IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sahaj_app', s);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE sahaj_migrator IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO sahaj_app', s);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE sahaj_migrator IN SCHEMA %I GRANT EXECUTE ON FUNCTIONS TO sahaj_app', s);
  END LOOP;
END $$;
GRANT CONNECT ON DATABASE :"DBNAME" TO sahaj_app;
