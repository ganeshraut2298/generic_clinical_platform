-- SAH-1 · closes security review L3 (the app could set the audit timestamp).
-- V2026_11_01_1004 granted sahaj_app INSERT on the whole table, so the app could write its own `at`
-- (back-dated or future events), `id` or `tenant_id`. Replace the table-level INSERT with a column-level one.
--
-- Columns the app writes (backend JdbcAuditLog INSERT):
--   actor_user_id, action, entity_type, entity_id, before_json, after_json
-- actor_user_id MUST stay insertable: the app supplies it from the logged-in user.
-- Columns left out, filled by their column defaults, which need no INSERT privilege:
--   id         default gen_random_uuid()
--   tenant_id  default platform.current_tenant()   (the RLS insert policy still checks it)
--   at         default now()
-- SELECT is untouched (table-level), so reads and RETURNING keep working.
-- UPDATE, DELETE and TRUNCATE stay refused (no privilege, no policy, triggers).
revoke insert on platform.audit_event from sahaj_app;
grant insert (actor_user_id, action, entity_type, entity_id, before_json, after_json)
  on platform.audit_event to sahaj_app;
