-- SAH-1 · platform.app_user : a person, identified by a 10-digit mobile number.
-- Global: one person can belong to several clinics through platform.membership.
-- No password column, ever (sign-in is a one-time code). No row level security by design;
-- reached only through platform services. The mobile number is personal data: never log it.
create table if not exists platform.app_user (
  id         uuid primary key default gen_random_uuid(),
  mobile     char(10) not null
             constraint uq_app_user_mobile unique
             constraint ck_app_user_mobile check (mobile ~ '^[6-9][0-9]{9}$'),
  full_name  text not null
             constraint ck_app_user_full_name check (char_length(btrim(full_name)) between 1 and 200),
  created_at timestamptz not null default now(),
  updated_at timestamptz,
  version    bigint not null default 0
);

-- A person is never deleted (past entries keep their name); no DELETE for the app.
revoke all on platform.app_user from sahaj_app;
grant select, insert, update on platform.app_user to sahaj_app;
