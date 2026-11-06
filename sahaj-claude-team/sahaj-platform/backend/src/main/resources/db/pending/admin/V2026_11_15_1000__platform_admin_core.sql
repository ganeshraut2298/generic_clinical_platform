-- ADM-1 · Platform admin identity, sign-in state and admin audit (ADR-003 D1, D2, D3, D7; DEC-012). Forward-only.
-- Six platform-scope tables in schema platform. None carries tenant_id; none has row level security:
-- privileges are the control. sahaj_app gets NO privilege on any of them; the app reaches them only through
-- the SECURITY DEFINER functions of V2026_11_15_1010, which are owned by sahaj_admin_definer.

-- 0. Role check. The role is created by a human (DEC-012: scripts/setup/create-database.mjs and scripts/db/init.sql).
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'sahaj_admin_definer') then
    raise exception 'Role sahaj_admin_definer does not exist. Create the role first (see DEC-012 and ADR-003 D4): NOLOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT, and GRANT sahaj_admin_definer TO sahaj_migrator.';
  end if;
  if exists (select 1 from pg_roles where rolname = 'sahaj_admin_definer'
              and (rolcanlogin or rolsuper or rolbypassrls)) then
    raise exception 'Role sahaj_admin_definer must be NOLOGIN NOSUPERUSER NOBYPASSRLS (ADR-003 D4).';
  end if;
  if not pg_has_role(current_user, 'sahaj_admin_definer', 'member') then
    raise exception 'The migration user must be a member of sahaj_admin_definer: GRANT sahaj_admin_definer TO sahaj_migrator (see DEC-012).';
  end if;
end
$$;

-- The definer role needs to see the schema. (CREATE on it is granted and taken back inside V2026_11_15_1010 only.)
grant usage on schema platform to sahaj_admin_definer;

-- 1. platform.platform_admin : a Sahaj employee. Never deleted; switched off with active = false.
create table if not exists platform.platform_admin (
  id                  uuid primary key default gen_random_uuid(),
  email               text not null
                      constraint ck_platform_admin_email check (
                        email = lower(btrim(email))
                        and char_length(email) between 3 and 254
                        and email ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
  full_name           text not null
                      constraint ck_platform_admin_full_name check (
                        full_name = btrim(full_name) and char_length(full_name) between 1 and 120),
  password_hash       text not null
                      constraint ck_platform_admin_password_hash check (
                        password_hash ~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$'),
  password_changed_at timestamptz not null default now(),
  -- AES-256-GCM: 12-byte nonce + 20-byte secret + 16-byte tag = 48 bytes (64 allows a longer secret later).
  totp_secret_enc     bytea
                      constraint ck_platform_admin_totp_secret_len check (octet_length(totp_secret_enc) between 48 and 64),
  totp_key_version    smallint
                      constraint ck_platform_admin_totp_key_version check (totp_key_version >= 1),
  totp_enrolled_at    timestamptz,
  totp_last_used_step bigint,
  active              boolean not null default true,
  last_sign_in_at     timestamptz,
  created_at          timestamptz not null default now(),
  created_by          uuid,
  updated_at          timestamptz,
  updated_by          uuid,
  version             bigint not null default 0,
  constraint uq_platform_admin_email unique (email),
  constraint ck_platform_admin_totp_all_or_none check (
    (totp_secret_enc is null) = (totp_key_version is null)
    and (totp_secret_enc is null) = (totp_enrolled_at is null))
);

-- 2. platform.platform_admin_recovery_code : 10 single-use codes per admin, stored only as HMAC-SHA256 hex.
create table if not exists platform.platform_admin_recovery_code (
  id         uuid primary key default gen_random_uuid(),
  admin_id   uuid not null
             constraint fk_platform_admin_recovery_code_admin references platform.platform_admin (id),
  code_hash  text not null
             constraint ck_platform_admin_recovery_code_hash check (code_hash ~ '^[0-9a-f]{64}$'),
  created_at timestamptz not null default now(),
  used_at    timestamptz,
  constraint uq_platform_admin_recovery_code_hash unique (code_hash)
);

-- Serves: "does this admin still have an unused code" and the replace-the-set at re-enrolment.
create index if not exists ix_platform_admin_recovery_code_unused
  on platform.platform_admin_recovery_code (admin_id) where used_at is null;

-- 3. platform.platform_admin_preauth : the 5-minute, single-use token between password and code.
create table if not exists platform.platform_admin_preauth (
  id                  uuid primary key default gen_random_uuid(),
  admin_id            uuid not null
                      constraint fk_platform_admin_preauth_admin references platform.platform_admin (id),
  token_hash          text not null
                      constraint ck_platform_admin_preauth_token_hash check (token_hash ~ '^[0-9a-f]{64}$'),
  throttle_key        text not null
                      constraint ck_platform_admin_preauth_throttle_key check (throttle_key ~ '^[0-9a-f]{64}$'),
  purpose             text not null
                      constraint ck_platform_admin_preauth_purpose check (purpose in ('TOTP', 'ENROL')),
  pending_secret_enc  bytea
                      constraint ck_platform_admin_preauth_pending_len check (octet_length(pending_secret_enc) between 48 and 64),
  pending_key_version smallint
                      constraint ck_platform_admin_preauth_pending_ver check (pending_key_version >= 1),
  attempts            smallint not null default 0
                      constraint ck_platform_admin_preauth_attempts check (attempts between 0 and 5),
  created_at          timestamptz not null default now(),
  expires_at          timestamptz not null,
  consumed_at         timestamptz,
  ip                  inet,
  constraint uq_platform_admin_preauth_token_hash unique (token_hash),
  constraint ck_platform_admin_preauth_pending_enrol check (
    pending_secret_enc is null or purpose = 'ENROL'),
  constraint ck_platform_admin_preauth_pending_pair check (
    (pending_secret_enc is null) = (pending_key_version is null)),
  constraint ck_platform_admin_preauth_expiry check (expires_at > created_at)
);

-- Serves the purge job (rows 1 day after expiry) and "live pre-auth rows of one admin".
create index if not exists ix_platform_admin_preauth_expires on platform.platform_admin_preauth (expires_at);
create index if not exists ix_platform_admin_preauth_admin on platform.platform_admin_preauth (admin_id);

-- 4. platform.platform_admin_auth_throttle : failed-step counter per HMAC of the typed email. Rows exist for ANY
--    typed email, known or not, so the table is not an admin list and a lock reveals nothing.
create table if not exists platform.platform_admin_auth_throttle (
  key_hash          text primary key
                    constraint ck_platform_admin_auth_throttle_key check (key_hash ~ '^[0-9a-f]{64}$'),
  failures          smallint not null default 0
                    constraint ck_platform_admin_auth_throttle_failures check (failures between 0 and 5),
  window_started_at timestamptz not null,
  locked_until      timestamptz,
  updated_at        timestamptz not null
);

-- Serves the purge job (rows idle for 1 day and not locked).
create index if not exists ix_platform_admin_auth_throttle_updated on platform.platform_admin_auth_throttle (updated_at);

-- 5. platform.platform_admin_session : one row per admin sign-in; refresh token stored as HMAC-SHA256 hex.
create table if not exists platform.platform_admin_session (
  id                    uuid primary key default gen_random_uuid(),
  admin_id              uuid not null
                        constraint fk_platform_admin_session_admin references platform.platform_admin (id),
  refresh_token_hash    text not null
                        constraint ck_platform_admin_session_refresh_hash check (refresh_token_hash ~ '^[0-9a-f]{64}$'),
  previous_refresh_hash text
                        constraint ck_platform_admin_session_previous_hash check (previous_refresh_hash ~ '^[0-9a-f]{64}$'),
  auth_method           text not null
                        constraint ck_platform_admin_session_method check (auth_method in ('TOTP', 'RECOVERY_CODE', 'ENROL')),
  created_at            timestamptz not null default now(),
  last_seen_at          timestamptz not null default now(),
  absolute_expires_at   timestamptz not null,
  revoked_at            timestamptz,
  revoked_reason        text
                        constraint ck_platform_admin_session_revoked_reason check (
                          revoked_reason in ('SIGN_OUT', 'REUSE', 'ADMIN_OFF', 'EXPIRED')),
  ip                    inet,
  constraint uq_platform_admin_session_refresh_hash unique (refresh_token_hash),
  constraint ck_platform_admin_session_revoked_pair check ((revoked_at is null) = (revoked_reason is null)),
  constraint ck_platform_admin_session_absolute check (absolute_expires_at > created_at)
);

-- Serves "sessions of one admin, newest first" (support, purge).
create index if not exists ix_platform_admin_session_admin_created
  on platform.platform_admin_session (admin_id, created_at desc);
-- Serves refresh-token reuse detection (the presented token is the previous one of some session).
create index if not exists ix_platform_admin_session_previous_hash
  on platform.platform_admin_session (previous_refresh_hash) where previous_refresh_hash is not null;

-- 6. platform.platform_admin_audit : append-only. Not tenant-owned: target_tenant_id is deliberately not named
--    tenant_id, because no RLS policy governs this table.
--    Never stored here: a typed email, password, code, secret, token, recovery code or search text.
create table if not exists platform.platform_admin_audit (
  id                uuid primary key default gen_random_uuid(),
  occurred_at       timestamptz not null default now(),
  kind              text not null
                    constraint ck_platform_admin_audit_kind check (kind in ('AUTH', 'ACCESS', 'ACTION')),
  action            text not null
                    constraint ck_platform_admin_audit_action check (char_length(action) between 1 and 60),
  auth_step         text
                    constraint ck_platform_admin_audit_auth_step check (
                      auth_step in ('PASSWORD', 'TOTP', 'RECOVERY_CODE', 'ENROL')),
  admin_id          uuid
                    constraint fk_platform_admin_audit_admin references platform.platform_admin (id),
  admin_name        text
                    constraint ck_platform_admin_audit_admin_name check (char_length(admin_name) <= 120),
  target_tenant_id  uuid
                    constraint fk_platform_admin_audit_tenant references platform.tenant (id),
  summary           text not null
                    constraint ck_platform_admin_audit_summary check (char_length(summary) <= 300),
  reason            text
                    constraint ck_platform_admin_audit_reason check (char_length(reason) between 10 and 500),
  ticket            text
                    constraint ck_platform_admin_audit_ticket check (char_length(ticket) <= 20),
  before_json       jsonb,
  after_json        jsonb,
  outcome           text not null default 'APPLIED'
                    constraint ck_platform_admin_audit_outcome check (outcome in ('APPLIED', 'REFUSED')),
  refusal_code      text
                    constraint ck_platform_admin_audit_refusal_code check (char_length(refusal_code) <= 60),
  ip                inet,
  request_id        text
                    constraint ck_platform_admin_audit_request_id check (char_length(request_id) <= 100),
  constraint ck_platform_admin_audit_auth_step_kind check (auth_step is null or kind = 'AUTH'),
  constraint ck_platform_admin_audit_refusal_outcome check (refusal_code is null or outcome = 'REFUSED')
);

-- Serves "activity for one clinic, newest first" (ADM-3).
create index if not exists ix_platform_admin_audit_tenant_at
  on platform.platform_admin_audit (target_tenant_id, occurred_at desc) where target_tenant_id is not null;
-- Serves the future Audit log page (newest first).
create index if not exists ix_platform_admin_audit_at on platform.platform_admin_audit (occurred_at desc);
-- Serves "what did this admin do".
create index if not exists ix_platform_admin_audit_admin_at
  on platform.platform_admin_audit (admin_id, occurred_at desc) where admin_id is not null;

-- Append-only, third layer (privileges and the missing grants are the first two): refuse UPDATE, DELETE and
-- TRUNCATE for every role, the owner included.
create trigger trg_platform_admin_audit_no_update_delete
  before update or delete on platform.platform_admin_audit
  for each row execute function platform.deny_audit_mutation();

create trigger trg_platform_admin_audit_no_truncate
  before truncate on platform.platform_admin_audit
  for each statement execute function platform.deny_audit_mutation();

-- 7. Privileges. The schema's default privileges give sahaj_app all four commands on new tables, so take everything
--    away from sahaj_app and PUBLIC first; sahaj_app then has no privilege at all on these six tables.
revoke all on platform.platform_admin               from sahaj_app, public;
revoke all on platform.platform_admin_recovery_code from sahaj_app, public;
revoke all on platform.platform_admin_preauth       from sahaj_app, public;
revoke all on platform.platform_admin_auth_throttle from sahaj_app, public;
revoke all on platform.platform_admin_session       from sahaj_app, public;
revoke all on platform.platform_admin_audit         from sahaj_app, public;

-- Exactly what the sign-in functions need, nothing more. No INSERT and no UPDATE (password_hash) on platform_admin:
-- admins and passwords are set only by the migrator (dev seed, human provisioning).
grant select (id, email, full_name, password_hash, totp_secret_enc, totp_key_version, totp_enrolled_at,
              totp_last_used_step, active)
  on platform.platform_admin to sahaj_admin_definer;
grant update (totp_secret_enc, totp_key_version, totp_enrolled_at, totp_last_used_step, last_sign_in_at, updated_at)
  on platform.platform_admin to sahaj_admin_definer;

grant select, insert, delete on platform.platform_admin_recovery_code to sahaj_admin_definer;
grant update (used_at)       on platform.platform_admin_recovery_code to sahaj_admin_definer;

grant select, insert, update         on platform.platform_admin_preauth       to sahaj_admin_definer;
grant select, insert, update, delete on platform.platform_admin_auth_throttle to sahaj_admin_definer;
grant select, insert, update         on platform.platform_admin_session       to sahaj_admin_definer;
grant select, insert                 on platform.platform_admin_audit         to sahaj_admin_definer;

comment on table platform.platform_admin is
  'ADM-1: a Sahaj employee who signs in to the admin console with work email, password (BCrypt) and an authenticator app. No tenant_id, no RLS; sahaj_app has no privilege. Never deleted: active = false switches an admin off. TOTP secret is AES-256-GCM ciphertext only.';
comment on table platform.platform_admin_recovery_code is
  'ADM-1: single-use recovery codes (HMAC-SHA256 hex only), 10 per enrolled admin. No privilege for sahaj_app.';
comment on table platform.platform_admin_preauth is
  'ADM-1: 5-minute single-use token issued after a correct password (HMAC-SHA256 hex only); holds the pending TOTP secret during enrolment. No privilege for sahaj_app. Purge 1 day after expiry.';
comment on table platform.platform_admin_auth_throttle is
  'ADM-1: failed sign-in steps per HMAC of the typed email (any email, known or not); 5 failures in 15 minutes lock for 15 minutes. No privilege for sahaj_app. Purge when idle for 1 day and not locked.';
comment on table platform.platform_admin_session is
  'ADM-1: one row per admin sign-in; refresh token as HMAC-SHA256 hex, with the previous one kept for reuse detection. No privilege for sahaj_app. Purge 30 days after the session ended.';
comment on table platform.platform_admin_audit is
  'ADM-1: append-only audit of admin sign-in, access and actions. No privilege for sahaj_app; sahaj_admin_definer has INSERT and SELECT only; update, delete and truncate are refused by trigger for every role. Never holds a typed email, password, code, secret, token or search text. Retention 7 years.';
