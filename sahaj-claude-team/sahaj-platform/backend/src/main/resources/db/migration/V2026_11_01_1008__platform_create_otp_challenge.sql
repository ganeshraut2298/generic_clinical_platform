-- SAH-3 · platform.otp_challenge : one row per one-time code sent to a mobile number.
-- No tenant column: OTP verification happens before any tenant is known or chosen, and the
-- same mobile can be mid-signup for a brand new clinic. No row level security for the same
-- reason; there is no tenant to key a policy on. code_hash only, the code itself is never stored.
create table if not exists platform.otp_challenge (
  id           uuid primary key default gen_random_uuid(),
  mobile       char(10) not null
               constraint ck_otp_challenge_mobile check (mobile ~ '^[6-9][0-9]{9}$'),
  code_hash    text not null
               constraint ck_otp_challenge_code_hash check (char_length(code_hash) between 1 and 200),
  expires_at   timestamptz not null,
  failed_count integer not null default 0
               constraint ck_otp_challenge_failed_count check (failed_count >= 0),
  locked_until timestamptz,
  last_sent_at timestamptz not null default now(),
  consumed_at  timestamptz,
  created_at   timestamptz not null default now()
);

-- Serves "the current challenge for this mobile" (request, verify, resend, lock check):
-- always the newest row for a mobile.
create index if not exists ix_otp_challenge_mobile_created on platform.otp_challenge (mobile, created_at desc);

revoke all on platform.otp_challenge from sahaj_app;
revoke all on platform.otp_challenge from public;
grant select, insert, update on platform.otp_challenge to sahaj_app;
-- No delete for the app: rows are cleaned up by a retention job run as sahaj_migrator, not here.

comment on table platform.otp_challenge is
  'One-time code challenges for mobile sign-up and log-in. Never store the code itself, only code_hash. Never log the mobile.';
