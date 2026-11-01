-- SAH-1 · platform.current_tenant()
-- The one place that reads the tenant bound for this transaction (SET LOCAL by the application).
-- Fails closed: when the setting is unset the function returns NULL, "x = NULL" is never true,
-- so a policy built on it shows no rows and accepts no inserts.
-- nullif(..., '') matters: after a transaction that used SET LOCAL, PostgreSQL leaves the custom
-- setting as an empty string on that connection, and '' cannot be cast to uuid.
create or replace function platform.current_tenant()
returns uuid
language sql
stable
as $$
  select nullif(current_setting('app.tenant_id', true), '')::uuid
$$;

comment on function platform.current_tenant() is
  'Tenant bound for this transaction, or NULL when none is bound. Used by every row level security policy.';
