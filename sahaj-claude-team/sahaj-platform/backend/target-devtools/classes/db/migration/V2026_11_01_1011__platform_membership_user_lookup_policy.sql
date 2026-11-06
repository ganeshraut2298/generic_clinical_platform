-- SAH-3 · the login-lookup default (DEC-007): platform.current_user_id() and a narrow,
-- additional SELECT policy on platform.membership.
--
-- Problem: SAH-3's verify step runs after a one-time code is confirmed but before any tenant
-- is chosen or bound, yet it must find which clinics this user belongs to (AuthResult
-- CHOOSE_CLINIC) and whether the mobile is registered at all (AuthResult NOT_REGISTERED).
-- Under platform.current_tenant()'s fail-closed policy that lookup returns no rows, because
-- no tenant is bound yet.
--
-- Fix, decided in DEC-007: once OTP succeeds, the app binds app.user_id (SET LOCAL, never from
-- the request body) to the verified user, before any tenant is chosen. A second, additional
-- permissive SELECT policy then lets that session read its own membership rows by user_id.
create or replace function platform.current_user_id()
returns uuid
language sql
stable
as $$
  select nullif(current_setting('app.user_id', true), '')::uuid
$$;

comment on function platform.current_user_id() is
  'Verified user bound for this transaction after OTP success (SET LOCAL app.user_id), or NULL when none is bound. Used only by the membership login-lookup policy.';

-- PostgreSQL evaluates multiple permissive policies on the same command and role with OR, so a
-- membership row is now readable when EITHER p_membership_tenant's tenant match holds OR this
-- user_id match holds. p_membership_tenant is FOR ALL and is untouched: INSERT, UPDATE and
-- DELETE still go only through it, keyed on tenant_id. This migration adds no privilege and no
-- write path; a user can read their own rows and no one else's.
create policy p_membership_self_read on platform.membership
  for select to sahaj_app
  using (user_id = platform.current_user_id());

comment on policy p_membership_self_read on platform.membership is
  'DEC-007 login-lookup default: a verified, not-yet-tenant-bound session may read only its own membership rows, by user_id. Grants no INSERT, UPDATE or DELETE access.';
