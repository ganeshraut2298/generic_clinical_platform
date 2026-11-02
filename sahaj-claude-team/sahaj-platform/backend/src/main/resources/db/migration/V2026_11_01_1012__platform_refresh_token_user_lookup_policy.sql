-- SAH-3 AC15 (logout on all devices): a user can belong to more than one tenant,
-- and p_refresh_token_tenant alone can only revoke tokens within the currently
-- bound tenant. Add the same shape of additional permissive policy used for
-- platform.membership (V2026_11_01_1011), but for SELECT and UPDATE, so logout-all
-- can find and revoke (set revoked_at) every refresh token for the user across
-- every tenant they belong to, in one statement, keyed on platform.current_user_id().
-- The existing tenant-scoped policy p_refresh_token_tenant is unchanged and still
-- governs normal per-request reads/writes and all INSERT/DELETE (DELETE is not
-- granted to sahaj_app at all, unchanged from V2026_11_01_1009).

create policy p_refresh_token_self_read on platform.refresh_token
  for select to sahaj_app
  using (user_id = platform.current_user_id());

create policy p_refresh_token_self_revoke on platform.refresh_token
  for update to sahaj_app
  using (user_id = platform.current_user_id())
  with check (user_id = platform.current_user_id());
