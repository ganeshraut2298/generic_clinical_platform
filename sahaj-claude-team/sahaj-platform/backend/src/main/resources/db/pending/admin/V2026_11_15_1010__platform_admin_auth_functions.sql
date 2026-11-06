-- ADM-1 · Admin sign-in functions (ADR-003 D4). Forward-only.
-- Every function: SECURITY DEFINER, owned by sahaj_admin_definer, SET search_path = pg_catalog, pg_temp, every object
-- schema-qualified, no dynamic SQL, EXECUTE revoked from PUBLIC and granted to sahaj_app. No function receives a plain
-- password, TOTP secret, token or recovery code: secrets arrive as ciphertext, tokens and codes as HMAC-SHA256 hex.
-- Failures the Java side must tell apart use these SQLSTATEs:
--   42501 no live admin session / clinic session bound      SA001 pre-auth token unknown, expired or used
--   SA002 TOTP step already used (replay)                   SA003 recovery code unknown or already used
--   SA004 admin not found, switched off or not eligible     SA005 recovery-code set is not 10 distinct hashes
--   SA006 no pending TOTP secret on the enrolment token     SA007 admin is already enrolled
--   22023 bad parameter (hash format, purpose, step)
-- Numbers: pre-auth 5 minutes and 5 attempts; throttle 5 failures in 15 minutes, locked 15 minutes;
-- session idle 30 minutes, absolute 12 hours.

do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'sahaj_admin_definer') then
    raise exception 'Role sahaj_admin_definer does not exist. Create the role first (see DEC-012 and ADR-003 D4).';
  end if;
  if not pg_has_role(current_user, 'sahaj_admin_definer', 'member') then
    raise exception 'The migration user must be a member of sahaj_admin_definer: GRANT sahaj_admin_definer TO sahaj_migrator (see DEC-012).';
  end if;
end
$$;

-- ALTER FUNCTION ... OWNER TO needs CREATE on the schema for the new owner. Granted here, taken back at the end.
grant create on schema platform to sahaj_admin_definer;

-- 1. Guard. Raises 42501 unless the transaction is bound to a live admin session (and not to a clinic).
create or replace function platform.admin_require_session()
returns uuid
language plpgsql
stable
security definer
set search_path = pg_catalog, pg_temp
as $$
declare
  v_admin uuid;
  v_sid   uuid;
begin
  if platform.current_tenant() is not null then
    raise exception 'admin session required' using errcode = '42501';
  end if;
  begin
    v_admin := nullif(current_setting('app.admin_id', true), '')::uuid;
    v_sid   := nullif(current_setting('app.admin_session_id', true), '')::uuid;
  exception when invalid_text_representation then
    raise exception 'admin session required' using errcode = '42501';
  end;
  if v_admin is null or v_sid is null then
    raise exception 'admin session required' using errcode = '42501';
  end if;
  perform 1
     from platform.platform_admin_session s
     join platform.platform_admin a on a.id = s.admin_id
    where s.id = v_sid
      and s.admin_id = v_admin
      and s.revoked_at is null
      and a.active
      and s.last_seen_at > now() - interval '30 minutes'
      and s.absolute_expires_at > now();
  if not found then
    raise exception 'admin session required' using errcode = '42501';
  end if;
  return v_admin;
end
$$;

-- 2. Pre-auth: the active admin with this email, or no row. Returns the BCrypt hash for the Java-side check.
create or replace function platform.admin_login_lookup(p_email text)
returns table (admin_id uuid, password_hash text, totp_enrolled boolean)
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
#variable_conflict use_column
begin
  if platform.current_tenant() is not null then
    raise exception 'admin function in a clinic session' using errcode = '42501';
  end if;
  if p_email is null or char_length(p_email) > 254 then
    return;
  end if;
  return query
    select a.id, a.password_hash, (a.totp_enrolled_at is not null)
      from platform.platform_admin a
     where a.email = lower(btrim(p_email))
       and a.active;
end
$$;

-- 3. Pre-auth: is this email key locked? Returns the lock end, or null.
create or replace function platform.admin_throttle_status(p_key_hash text)
returns timestamptz
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
declare
  v_locked timestamptz;
begin
  if platform.current_tenant() is not null then
    raise exception 'admin function in a clinic session' using errcode = '42501';
  end if;
  if p_key_hash is null or p_key_hash !~ '^[0-9a-f]{64}$' then
    raise exception 'invalid key hash' using errcode = '22023';
  end if;
  select t.locked_until into v_locked
    from platform.platform_admin_auth_throttle t
   where t.key_hash = p_key_hash
     and t.locked_until > now();
  return v_locked;
end
$$;

-- 4. Pre-auth: record one failed step. p_key_hash may be null when p_preauth_hash is given (the key is then taken
--    from the pre-auth row). Counts toward the email lock (5 in 15 minutes locks 15 minutes) and, when a pre-auth
--    token is given, toward its 5 attempts. Writes AUTH ADMIN_SIGN_IN_FAILED; never the typed email.
create or replace function platform.admin_auth_failed(
  p_key_hash     text,
  p_admin_id     uuid,
  p_preauth_hash text,
  p_step         text,
  p_ip           inet,
  p_request_id   text)
returns table (locked_until timestamptz, preauth_consumed boolean)
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
#variable_conflict use_column
declare
  v_key       text := p_key_hash;
  v_pre_key   text;
  v_pre_admin uuid;
  v_consumed  boolean := false;
  v_failures  smallint;
  v_window    timestamptz;
  v_locked    timestamptz;
  v_admin     uuid;
  v_name      text;
  v_just_locked boolean := false;
begin
  if platform.current_tenant() is not null then
    raise exception 'admin function in a clinic session' using errcode = '42501';
  end if;
  if p_step is null or p_step not in ('PASSWORD', 'TOTP', 'RECOVERY_CODE') then
    raise exception 'invalid step' using errcode = '22023';
  end if;
  if p_key_hash is not null and p_key_hash !~ '^[0-9a-f]{64}$' then
    raise exception 'invalid key hash' using errcode = '22023';
  end if;

  if p_preauth_hash is not null then
    if p_preauth_hash !~ '^[0-9a-f]{64}$' then
      raise exception 'invalid token hash' using errcode = '22023';
    end if;
    update platform.platform_admin_preauth p
       set attempts    = p.attempts + 1,
           consumed_at = case when p.attempts + 1 >= 5 then now() else p.consumed_at end
     where p.token_hash = p_preauth_hash
       and p.consumed_at is null
       and p.expires_at > now()
    returning p.throttle_key, (p.attempts >= 5), p.admin_id
      into v_pre_key, v_consumed, v_pre_admin;
  end if;

  v_key := coalesce(v_key, v_pre_key);
  if v_key is null then
    raise exception 'throttle key required' using errcode = '22023';
  end if;

  -- Only an id that really is an admin is written to the audit (a forged id must not fail the foreign key).
  select a.id, a.full_name into v_admin, v_name
    from platform.platform_admin a
   where a.id = coalesce(p_admin_id, v_pre_admin);

  insert into platform.platform_admin_auth_throttle (key_hash, failures, window_started_at, updated_at)
  values (v_key, 0, now(), now())
  on conflict (key_hash) do nothing;

  select t.failures, t.window_started_at, t.locked_until
    into v_failures, v_window, v_locked
    from platform.platform_admin_auth_throttle t
   where t.key_hash = v_key
     for update;

  if v_locked is not null and v_locked > now() then
    null; -- already locked: the lock is not extended
  else
    if v_locked is not null or v_window < now() - interval '15 minutes' then
      v_failures := 0;
      v_window   := now();
      v_locked   := null;
    end if;
    v_failures := v_failures + 1;
    if v_failures >= 5 then
      v_locked := now() + interval '15 minutes';
      v_just_locked := true;
    end if;
    update platform.platform_admin_auth_throttle t
       set failures = v_failures,
           window_started_at = v_window,
           locked_until = v_locked,
           updated_at = now()
     where t.key_hash = v_key;
  end if;

  insert into platform.platform_admin_audit
    (kind, action, auth_step, admin_id, admin_name, summary, outcome, refusal_code, ip, request_id)
  values
    ('AUTH', 'ADMIN_SIGN_IN_FAILED', p_step, v_admin, v_name,
     'Sign-in step failed (' || lower(replace(p_step, '_', ' ')) || ')'
       || case when v_just_locked then '; sign-in locked for 15 minutes' else '' end,
     'REFUSED',
     case p_step when 'PASSWORD' then 'admin-credentials-invalid'
                 when 'TOTP' then 'totp-invalid'
                 else 'recovery-code-invalid' end,
     p_ip, p_request_id);

  return query select (case when v_locked > now() then v_locked else null end), coalesce(v_consumed, false);
end
$$;

-- 5. Pre-auth: issue the 5-minute single-use token after a correct password. Purpose must match the enrolment state.
create or replace function platform.admin_preauth_create(
  p_admin_id   uuid,
  p_key_hash   text,
  p_token_hash text,
  p_purpose    text,
  p_ip         inet)
returns timestamptz
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
declare
  v_enrolled boolean;
  v_expires  timestamptz;
begin
  if platform.current_tenant() is not null then
    raise exception 'admin function in a clinic session' using errcode = '42501';
  end if;
  if p_key_hash is null or p_key_hash !~ '^[0-9a-f]{64}$'
     or p_token_hash is null or p_token_hash !~ '^[0-9a-f]{64}$' then
    raise exception 'invalid hash' using errcode = '22023';
  end if;
  if p_purpose is null or p_purpose not in ('TOTP', 'ENROL') then
    raise exception 'invalid purpose' using errcode = '22023';
  end if;
  select (a.totp_enrolled_at is not null) into v_enrolled
    from platform.platform_admin a
   where a.id = p_admin_id and a.active;
  if not found then
    raise exception 'admin not available' using errcode = 'SA004';
  end if;
  if (p_purpose = 'TOTP' and not v_enrolled) or (p_purpose = 'ENROL' and v_enrolled) then
    raise exception 'purpose does not match enrolment state' using errcode = '22023';
  end if;
  insert into platform.platform_admin_preauth (admin_id, token_hash, throttle_key, purpose, expires_at, ip)
  values (p_admin_id, p_token_hash, p_key_hash, p_purpose, now() + interval '5 minutes', p_ip)
  returning expires_at into v_expires;
  return v_expires;
end
$$;

-- 6. Pre-auth: the live pre-auth row and its admin (ciphertext for Java to decrypt), or no row.
--    email is the admin's own work email (for the otpauth account name); throttle_key is the HMAC of the email the
--    pre-auth row was issued for (so Java can check an existing email lock before it accepts a correct code).
--    This function writes nothing, so neither value reaches the audit.
create or replace function platform.admin_preauth_get(p_token_hash text)
returns table (
  preauth_id          uuid,
  admin_id            uuid,
  purpose             text,
  totp_secret_enc     bytea,
  totp_key_version    smallint,
  pending_secret_enc  bytea,
  pending_key_version smallint,
  totp_last_used_step bigint,
  email               text,
  throttle_key        text)
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
#variable_conflict use_column
begin
  if platform.current_tenant() is not null then
    raise exception 'admin function in a clinic session' using errcode = '42501';
  end if;
  if p_token_hash is null or p_token_hash !~ '^[0-9a-f]{64}$' then
    return;
  end if;
  return query
    select p.id, p.admin_id, p.purpose, a.totp_secret_enc, a.totp_key_version,
           p.pending_secret_enc, p.pending_key_version, a.totp_last_used_step,
           a.email, p.throttle_key
      from platform.platform_admin_preauth p
      join platform.platform_admin a on a.id = p.admin_id
     where p.token_hash = p_token_hash
       and p.consumed_at is null
       and p.expires_at > now()
       and a.active;
end
$$;

-- 7. Pre-auth: store the pending (encrypted) secret on an ENROL pre-auth row, only if none yet. Silent when the row is
--    not eligible; Java re-reads with admin_preauth_get.
create or replace function platform.admin_totp_set_pending(
  p_token_hash  text,
  p_secret_enc  bytea,
  p_key_version smallint)
returns void
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
begin
  if platform.current_tenant() is not null then
    raise exception 'admin function in a clinic session' using errcode = '42501';
  end if;
  if p_token_hash is null or p_token_hash !~ '^[0-9a-f]{64}$'
     or p_secret_enc is null or octet_length(p_secret_enc) not between 48 and 64
     or p_key_version is null or p_key_version < 1 then
    raise exception 'invalid parameter' using errcode = '22023';
  end if;
  update platform.platform_admin_preauth p
     set pending_secret_enc = p_secret_enc,
         pending_key_version = p_key_version
   where p.token_hash = p_token_hash
     and p.purpose = 'ENROL'
     and p.consumed_at is null
     and p.expires_at > now()
     and p.pending_secret_enc is null;
end
$$;

-- 8. Pre-auth: finish a normal sign-in (purpose TOTP). Consumes the pre-auth row, enforces TOTP replay (step must be
--    greater than the last used step) or marks the recovery code used, creates the session, clears the throttle.
--    p_totp_step may be null ONLY for the dev bypass (no replay check, step not stored); Java passes a real step otherwise.
--    p_summary (optional, default null) is the audit summary of the ADMIN_SIGNED_IN row. The only accepted value is
--    'dev bypass' (ADR-003 D5), and only with method TOTP; anything else raises 22023. Null keeps the standard text.
create or replace function platform.admin_sign_in(
  p_token_hash    text,
  p_method        text,
  p_totp_step     bigint,
  p_recovery_hash text,
  p_refresh_hash  text,
  p_ip            inet,
  p_request_id    text,
  p_summary       text default null)
returns table (session_id uuid, admin_id uuid, full_name text)
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
#variable_conflict use_column
declare
  v_pre_id  uuid;
  v_admin   uuid;
  v_key     text;
  v_name    text;
  v_last    bigint;
  v_session uuid;
begin
  if platform.current_tenant() is not null then
    raise exception 'admin function in a clinic session' using errcode = '42501';
  end if;
  if p_method is null or p_method not in ('TOTP', 'RECOVERY_CODE') then
    raise exception 'invalid method' using errcode = '22023';
  end if;
  if p_token_hash is null or p_token_hash !~ '^[0-9a-f]{64}$'
     or p_refresh_hash is null or p_refresh_hash !~ '^[0-9a-f]{64}$'
     or (p_method = 'RECOVERY_CODE' and (p_recovery_hash is null or p_recovery_hash !~ '^[0-9a-f]{64}$')) then
    raise exception 'invalid hash' using errcode = '22023';
  end if;
  if p_summary is not null and (p_summary <> 'dev bypass' or p_method <> 'TOTP') then
    raise exception 'invalid summary' using errcode = '22023';
  end if;

  select p.id, p.admin_id, p.throttle_key
    into v_pre_id, v_admin, v_key
    from platform.platform_admin_preauth p
   where p.token_hash = p_token_hash
     and p.purpose = 'TOTP'
     and p.consumed_at is null
     and p.expires_at > now()
     for update;
  if not found then
    raise exception 'pre-auth token not valid' using errcode = 'SA001';
  end if;

  select a.full_name, a.totp_last_used_step
    into v_name, v_last
    from platform.platform_admin a
   where a.id = v_admin and a.active and a.totp_enrolled_at is not null
     for update;
  if not found then
    raise exception 'admin not available' using errcode = 'SA004';
  end if;

  if p_method = 'TOTP' then
    if p_totp_step is not null then
      if v_last is not null and p_totp_step <= v_last then
        raise exception 'code already used' using errcode = 'SA002';
      end if;
      update platform.platform_admin a
         set totp_last_used_step = p_totp_step
       where a.id = v_admin;
    end if;
  else
    update platform.platform_admin_recovery_code r
       set used_at = now()
     where r.admin_id = v_admin
       and r.code_hash = p_recovery_hash
       and r.used_at is null;
    if not found then
      raise exception 'recovery code not valid' using errcode = 'SA003';
    end if;
  end if;

  update platform.platform_admin_preauth p set consumed_at = now() where p.id = v_pre_id;

  update platform.platform_admin a
     set last_sign_in_at = now(), updated_at = now()
   where a.id = v_admin;

  insert into platform.platform_admin_session
    (admin_id, refresh_token_hash, auth_method, absolute_expires_at, ip)
  values
    (v_admin, p_refresh_hash, p_method, now() + interval '12 hours', p_ip)
  returning id into v_session;

  delete from platform.platform_admin_auth_throttle t where t.key_hash = v_key;

  if p_method = 'RECOVERY_CODE' then
    insert into platform.platform_admin_audit
      (kind, action, auth_step, admin_id, admin_name, summary, ip, request_id)
    values
      ('AUTH', 'ADMIN_RECOVERY_CODE_USED', 'RECOVERY_CODE', v_admin, v_name,
       'Recovery code used', p_ip, p_request_id);
  end if;
  insert into platform.platform_admin_audit
    (kind, action, auth_step, admin_id, admin_name, summary, ip, request_id)
  values
    ('AUTH', 'ADMIN_SIGNED_IN', p_method, v_admin, v_name,
     coalesce(p_summary,
              case when p_method = 'TOTP' then 'Signed in with authenticator code'
                   else 'Signed in with recovery code' end),
     p_ip, p_request_id);

  return query select v_session, v_admin, v_name;
end
$$;

-- 9. Pre-auth: first sign-in. Consumes an ENROL pre-auth row, moves the pending secret to the admin, replaces the
--    recovery-code set with the 10 given hashes, creates the session, clears the throttle.
create or replace function platform.admin_complete_enrolment(
  p_token_hash      text,
  p_totp_step       bigint,
  p_recovery_hashes text[],
  p_refresh_hash    text,
  p_ip              inet,
  p_request_id      text)
returns table (session_id uuid, admin_id uuid, full_name text)
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
#variable_conflict use_column
declare
  v_pre_id   uuid;
  v_admin    uuid;
  v_key      text;
  v_secret   bytea;
  v_version  smallint;
  v_name     text;
  v_active   boolean;
  v_enrolled timestamptz;
  v_session  uuid;
begin
  if platform.current_tenant() is not null then
    raise exception 'admin function in a clinic session' using errcode = '42501';
  end if;
  if p_token_hash is null or p_token_hash !~ '^[0-9a-f]{64}$'
     or p_refresh_hash is null or p_refresh_hash !~ '^[0-9a-f]{64}$' then
    raise exception 'invalid hash' using errcode = '22023';
  end if;
  if p_recovery_hashes is null
     or cardinality(p_recovery_hashes) <> 10
     or (select count(distinct h) from unnest(p_recovery_hashes) as h where h ~ '^[0-9a-f]{64}$') <> 10 then
    raise exception 'ten distinct recovery code hashes required' using errcode = 'SA005';
  end if;

  select p.id, p.admin_id, p.throttle_key, p.pending_secret_enc, p.pending_key_version
    into v_pre_id, v_admin, v_key, v_secret, v_version
    from platform.platform_admin_preauth p
   where p.token_hash = p_token_hash
     and p.purpose = 'ENROL'
     and p.consumed_at is null
     and p.expires_at > now()
     for update;
  if not found then
    raise exception 'pre-auth token not valid' using errcode = 'SA001';
  end if;
  if v_secret is null then
    raise exception 'no pending authenticator secret' using errcode = 'SA006';
  end if;

  select a.full_name, a.active, a.totp_enrolled_at
    into v_name, v_active, v_enrolled
    from platform.platform_admin a
   where a.id = v_admin
     for update;
  if not found or not v_active then
    raise exception 'admin not available' using errcode = 'SA004';
  end if;
  if v_enrolled is not null then
    raise exception 'admin already enrolled' using errcode = 'SA007';
  end if;

  update platform.platform_admin a
     set totp_secret_enc     = v_secret,
         totp_key_version    = v_version,
         totp_enrolled_at    = now(),
         totp_last_used_step = p_totp_step,
         last_sign_in_at     = now(),
         updated_at          = now()
   where a.id = v_admin;

  delete from platform.platform_admin_recovery_code r where r.admin_id = v_admin;
  insert into platform.platform_admin_recovery_code (admin_id, code_hash)
  select v_admin, h from unnest(p_recovery_hashes) as h;

  update platform.platform_admin_preauth p set consumed_at = now() where p.id = v_pre_id;

  insert into platform.platform_admin_session
    (admin_id, refresh_token_hash, auth_method, absolute_expires_at, ip)
  values
    (v_admin, p_refresh_hash, 'ENROL', now() + interval '12 hours', p_ip)
  returning id into v_session;

  delete from platform.platform_admin_auth_throttle t where t.key_hash = v_key;

  insert into platform.platform_admin_audit
    (kind, action, auth_step, admin_id, admin_name, summary, ip, request_id)
  values
    ('AUTH', 'ADMIN_TOTP_ENROLLED', 'ENROL', v_admin, v_name, 'Authenticator app enrolled', p_ip, p_request_id),
    ('AUTH', 'ADMIN_SIGNED_IN', 'ENROL', v_admin, v_name, 'Signed in after enrolling the authenticator app', p_ip, p_request_id);

  return query select v_session, v_admin, v_name;
end
$$;

-- 10. Pre-auth (called by the JWT filter before any binding): is this session live? Moves last_seen_at when it is.
--     When it is not, marks an idle, expired or switched-off session as ended (reason EXPIRED or ADMIN_OFF).
create or replace function platform.admin_session_touch(p_session uuid, p_admin uuid)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
begin
  if platform.current_tenant() is not null then
    raise exception 'admin function in a clinic session' using errcode = '42501';
  end if;
  update platform.platform_admin_session s
     set last_seen_at = now()
    from platform.platform_admin a
   where s.id = p_session
     and s.admin_id = p_admin
     and a.id = s.admin_id
     and a.active
     and s.revoked_at is null
     and s.last_seen_at > now() - interval '30 minutes'
     and s.absolute_expires_at > now();
  if found then
    return true;
  end if;
  update platform.platform_admin_session s
     set revoked_at = now(),
         revoked_reason = case when not a.active then 'ADMIN_OFF' else 'EXPIRED' end
    from platform.platform_admin a
   where s.id = p_session
     and s.admin_id = p_admin
     and a.id = s.admin_id
     and s.revoked_at is null
     and (not a.active
          or s.last_seen_at <= now() - interval '30 minutes'
          or s.absolute_expires_at <= now());
  return false;
end
$$;

-- 11. Pre-auth (cookie refresh): swap the refresh token hash. The old hash is kept as previous_refresh_hash; presenting
--     a previous hash again revokes the whole session (reason REUSE) and writes AUTH ADMIN_SESSION_REVOKED.
--     Returns the session on success (with the auth_method it was created with: TOTP, RECOVERY_CODE or ENROL, which a
--     refresh keeps), no row otherwise. Java must NOT roll back on an empty result (the revoke is kept).
create or replace function platform.admin_session_rotate(p_old_hash text, p_new_hash text)
returns table (session_id uuid, admin_id uuid, full_name text, auth_method text)
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
#variable_conflict use_column
declare
  v_sid     uuid;
  v_admin   uuid;
  v_name    text;
  v_method  text;
  v_active  boolean;
  v_revoked timestamptz;
  v_last    timestamptz;
  v_abs     timestamptz;
  v_ip      inet;
begin
  if platform.current_tenant() is not null then
    raise exception 'admin function in a clinic session' using errcode = '42501';
  end if;
  if p_old_hash is null or p_old_hash !~ '^[0-9a-f]{64}$'
     or p_new_hash is null or p_new_hash !~ '^[0-9a-f]{64}$' then
    raise exception 'invalid hash' using errcode = '22023';
  end if;

  select s.id, s.admin_id, a.full_name, s.auth_method, a.active, s.revoked_at, s.last_seen_at, s.absolute_expires_at
    into v_sid, v_admin, v_name, v_method, v_active, v_revoked, v_last, v_abs
    from platform.platform_admin_session s
    join platform.platform_admin a on a.id = s.admin_id
   where s.refresh_token_hash = p_old_hash
     for update of s;

  if found then
    if v_revoked is not null then
      return;
    end if;
    if not v_active then
      update platform.platform_admin_session s
         set revoked_at = now(), revoked_reason = 'ADMIN_OFF' where s.id = v_sid;
      return;
    end if;
    if v_last <= now() - interval '30 minutes' or v_abs <= now() then
      update platform.platform_admin_session s
         set revoked_at = now(), revoked_reason = 'EXPIRED' where s.id = v_sid;
      return;
    end if;
    update platform.platform_admin_session s
       set previous_refresh_hash = p_old_hash,
           refresh_token_hash    = p_new_hash,
           last_seen_at          = now()
     where s.id = v_sid;
    return query select v_sid, v_admin, v_name, v_method;
    return;
  end if;

  -- Not a current token. If it is the previous token of a live session, somebody replayed it: end the session.
  select s.id, s.admin_id, a.full_name, s.revoked_at, s.ip
    into v_sid, v_admin, v_name, v_revoked, v_ip
    from platform.platform_admin_session s
    join platform.platform_admin a on a.id = s.admin_id
   where s.previous_refresh_hash = p_old_hash
     for update of s;
  if found and v_revoked is null then
    update platform.platform_admin_session s
       set revoked_at = now(), revoked_reason = 'REUSE' where s.id = v_sid;
    insert into platform.platform_admin_audit
      (kind, action, admin_id, admin_name, summary, ip)
    values
      ('AUTH', 'ADMIN_SESSION_REVOKED', v_admin, v_name, 'Refresh token reused; session ended', v_ip);
  end if;
  return;
end
$$;

-- 12. Bound session: sign out. Revokes the session named by app.admin_session_id and writes AUTH ADMIN_SIGNED_OUT.
create or replace function platform.admin_sign_out()
returns void
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
declare
  v_admin uuid;
  v_sid   uuid;
  v_name  text;
  v_ip    inet;
begin
  v_admin := platform.admin_require_session();
  v_sid := nullif(current_setting('app.admin_session_id', true), '')::uuid;
  update platform.platform_admin_session s
     set revoked_at = now(), revoked_reason = 'SIGN_OUT'
   where s.id = v_sid and s.admin_id = v_admin and s.revoked_at is null
  returning s.ip into v_ip;
  select a.full_name into v_name from platform.platform_admin a where a.id = v_admin;
  insert into platform.platform_admin_audit
    (kind, action, admin_id, admin_name, summary, ip)
  values
    ('AUTH', 'ADMIN_SIGNED_OUT', v_admin, v_name, 'Signed out', v_ip);
end
$$;

-- 13. Bound session: who am I (id and name).
create or replace function platform.admin_me()
returns table (admin_id uuid, full_name text)
language plpgsql
stable
security definer
set search_path = pg_catalog, pg_temp
as $$
#variable_conflict use_column
declare
  v_admin uuid;
begin
  v_admin := platform.admin_require_session();
  return query
    select a.id, a.full_name
      from platform.platform_admin a
     where a.id = v_admin and a.active;
end
$$;

-- 14. Privileges and owner for every ADM-1 function (one list, one place).
do $$
declare
  r record;
begin
  for r in
    select p.oid::regprocedure as sig
      from pg_proc p
      join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'platform'
       and p.proname = any (array[
         'admin_require_session', 'admin_login_lookup', 'admin_throttle_status', 'admin_auth_failed',
         'admin_preauth_create', 'admin_preauth_get', 'admin_totp_set_pending', 'admin_sign_in',
         'admin_complete_enrolment', 'admin_session_touch', 'admin_session_rotate', 'admin_sign_out', 'admin_me'])
  loop
    execute format('revoke all on function %s from public', r.sig);
    execute format('grant execute on function %s to sahaj_app', r.sig);
    execute format('alter function %s owner to sahaj_admin_definer', r.sig);
  end loop;
end
$$;

revoke create on schema platform from sahaj_admin_definer;

-- 15. Self-check: all 13 functions exist, are owned by the definer, are SECURITY DEFINER with a fixed search_path, and
--     are not executable by PUBLIC. Fails the migration (and rolls it back) if not.
do $$
declare
  v_bad integer;
  v_count integer;
begin
  select count(*) into v_count
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'platform' and p.proname like 'admin\_%' and p.proname not like 'admin\_list%';
  if v_count <> 13 then
    raise exception 'expected 13 admin functions in schema platform, found %', v_count;
  end if;
  select count(*) into v_bad
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'platform' and p.proname like 'admin\_%'
     and (pg_get_userbyid(p.proowner) <> 'sahaj_admin_definer'
          or not p.prosecdef
          or p.proconfig is null
          or not exists (select 1 from unnest(p.proconfig) c where c like 'search_path=%pg_catalog%pg_temp%')
          or exists (select 1
                       from aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) x
                      where x.grantee = 0 and x.privilege_type = 'EXECUTE'));
  if v_bad <> 0 then
    raise exception '% admin function(s) are not owned by sahaj_admin_definer, not SECURITY DEFINER with a fixed search_path, or executable by PUBLIC', v_bad;
  end if;
end
$$;
