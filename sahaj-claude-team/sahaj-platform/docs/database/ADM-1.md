> **PARKED: the two migrations are not active.** They are in `backend/src/main/resources/db/pending/admin/` (a folder Flyway does not scan) until the human creates role `sahaj_admin_definer` (DEC-012). To activate: move the two `V2026_11_15_*` files back into `backend/src/main/resources/db/migration/` (and keep their versions) once the role exists, then run `node scripts/validate.mjs db`. The dev-seed callback is not configured as a Flyway location anywhere yet, so it stays in `db/dev-seed/`.

# ADM-1 · Database design: platform admin sign-in

Source: ADR-003 (revision 2) D1 to D5, D7, "Data needed"; DEC-011; DEC-012. Contract: `docs/api/admin.yaml`.
Migrations (forward-only, schema `platform`):
- `V2026_11_15_1000__platform_admin_core.sql`: role check, six tables, indexes, audit triggers, privileges.
- `V2026_11_15_1010__platform_admin_auth_functions.sql`: 13 SECURITY DEFINER functions, owner, grants, self-check.
- Dev only, not a migration: `backend/src/main/resources/db/dev-seed/afterMigrate__dev_seed_platform_admin.sql`.

## Prerequisite (human, DEC-012)
Role `sahaj_admin_definer` (`NOLOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT`) and `GRANT sahaj_admin_definer TO sahaj_migrator`.
Both migrations stop with a clear message if the role is missing, has a wrong attribute, or the migration user is not a
member. Nothing else is needed from the human: the migration itself grants `USAGE` on schema `platform` to the role, and
grants and then takes back `CREATE` on `platform` around the function ownership change (PostgreSQL requires `CREATE` on the
schema for the new owner). The role owns functions only, never a table.

## Tables (none has `tenant_id` or RLS; privileges are the control)
Hash columns are `text` with `check (~ '^[0-9a-f]{64}$')` (HMAC-SHA256 hex) instead of `char(64)`: same guarantee, and
no `bpchar`/`text` comparison that could stop an index being used.

| Table | Key columns | Constraints and indexes (query served) |
|---|---|---|
| `platform_admin` | `id`, `email`, `full_name`, `password_hash` (BCrypt), `password_changed_at`, `totp_secret_enc` bytea (48 to 64 bytes), `totp_key_version`, `totp_enrolled_at`, `totp_last_used_step`, `active`, `last_sign_in_at`, `created_*`, `updated_*`, `version` | `uq_platform_admin_email` unique (email is checked to be lower case and trimmed, so it is unique case-insensitively; serves the sign-in lookup); TOTP secret, key version and enrolled time are all null or all set; email, name and BCrypt shape checks |
| `platform_admin_recovery_code` | `id`, `admin_id` fk, `code_hash`, `created_at`, `used_at` | `uq_..._hash` unique (serves "use this code"); partial index `(admin_id) where used_at is null` |
| `platform_admin_preauth` | `id`, `admin_id` fk, `token_hash`, `throttle_key`, `purpose` TOTP/ENROL, `pending_secret_enc`, `pending_key_version`, `attempts` 0..5, `created_at`, `expires_at`, `consumed_at`, `ip` | `uq_..._token_hash` (serves token lookup); `(expires_at)` serves the purge job; `(admin_id)`; pending secret only for ENROL |
| `platform_admin_auth_throttle` | `key_hash` pk (HMAC of normalised email), `failures` 0..5, `window_started_at`, `locked_until`, `updated_at` | pk serves the lock check; `(updated_at)` serves the purge job. Rows exist for any typed email |
| `platform_admin_session` | `id`, `admin_id` fk, `refresh_token_hash`, `previous_refresh_hash`, `auth_method` TOTP/RECOVERY_CODE/ENROL, `created_at`, `last_seen_at`, `absolute_expires_at`, `revoked_at`, `revoked_reason` SIGN_OUT/REUSE/ADMIN_OFF/EXPIRED, `ip` | `uq_..._refresh_hash` (refresh lookup); `(admin_id, created_at desc)`; partial `(previous_refresh_hash)` (reuse detection); revoked time and reason both set or both null |
| `platform_admin_audit` | `id`, `occurred_at`, `kind` AUTH/ACCESS/ACTION, `action`, `auth_step` PASSWORD/TOTP/RECOVERY_CODE/ENROL, `admin_id` fk, `admin_name`, `target_tenant_id` fk `platform.tenant`, `summary` (300), `reason` (10 to 500), `ticket` (20), `before_json`, `after_json`, `outcome` APPLIED/REFUSED, `refusal_code`, `ip`, `request_id` | `(target_tenant_id, occurred_at desc)` partial, `(occurred_at desc)`, `(admin_id, occurred_at desc)` partial; `auth_step` only for AUTH; `refusal_code` only when REFUSED |

## Privileges (exact)
- `sahaj_app` and `PUBLIC`: nothing on all six tables. The schema's default privileges would grant `sahaj_app` all four commands, so the migration revokes them explicitly.
- `sahaj_admin_definer`: `platform_admin` `SELECT (id, email, full_name, password_hash, totp_secret_enc, totp_key_version, totp_enrolled_at, totp_last_used_step, active)` and `UPDATE (totp_secret_enc, totp_key_version, totp_enrolled_at, totp_last_used_step, last_sign_in_at, updated_at)`; no INSERT, no password update. Recovery code `SELECT, INSERT, DELETE, UPDATE (used_at)`. Pre-auth and session `SELECT, INSERT, UPDATE`. Throttle `SELECT, INSERT, UPDATE, DELETE`. Audit `SELECT, INSERT`.
- Audit is append-only for every role: triggers `BEFORE UPDATE OR DELETE` (row) and `BEFORE TRUNCATE` call `platform.deny_audit_mutation()`.
- Admins and passwords are written only by the migrator (dev seed callback, human provisioning).

## Functions (all `SECURITY DEFINER`, owner `sahaj_admin_definer`, `search_path = pg_catalog, pg_temp`, EXECUTE only for `sahaj_app`)
`admin_require_session`, `admin_login_lookup`, `admin_throttle_status`, `admin_auth_failed`, `admin_preauth_create`, `admin_preauth_get`, `admin_totp_set_pending`, `admin_sign_in`, `admin_complete_enrolment`, `admin_session_touch`, `admin_session_rotate`, `admin_sign_out`, `admin_me`. Pre-auth functions refuse a transaction bound to a tenant. `admin_sign_out` and `admin_me` start with the guard `admin_require_session()` (needs `app.admin_id` and `app.admin_session_id`, live session, active admin, no tenant bound). The migration ends with a self-check that fails it if any function is not definer-owned, not SECURITY DEFINER with a fixed search_path, or executable by PUBLIC.

Differences from the ADR signatures (for the java-developer):
- `admin_auth_failed`: `p_key_hash` may be null when `p_preauth_hash` is given; the key is then read from the pre-auth row (step 2 has no email).
- `admin_sign_in`: `p_totp_step` may be null only for the dev bypass (no replay check, step not stored). New last parameter `p_summary text default null` (5 Nov): the audit summary of the `ADMIN_SIGNED_IN` row; the only accepted value is `dev bypass` (ADR-003 D5) and only with method TOTP, anything else raises 22023; null keeps the standard text. The old 7-argument call still works.
- `admin_session_rotate` returns `(session_id, admin_id, full_name, auth_method)` (auth_method added 5 Nov: the method the session was created with, so a refreshed token keeps its recovery-code `amr`), no row on unknown, dead or reused token. On reuse it revokes the session and keeps that write, so the caller must commit, not roll back, on an empty result.
- `admin_totp_set_pending` returns void and is silent when the row is not eligible; re-read with `admin_preauth_get`.
- `admin_preauth_get` returns `(preauth_id, admin_id, purpose, totp_secret_enc, totp_key_version, pending_secret_enc, pending_key_version, totp_last_used_step, email, throttle_key)` (last two added 5 Nov). `email` is the admin's own stored work email, for the otpauth account name; `throttle_key` is the HMAC the pre-auth row was issued for, so `totp/verify` can call `admin_throttle_status` before accepting a correct code. The function writes nothing, so neither value reaches the audit.
- Status 5 Nov: these three changes were edited in place in the parked file (never run) and have not been executed; they were checked by reading only.
- `admin_session_touch` also marks an idle, expired or switched-off session ended (EXPIRED, ADMIN_OFF) when it returns false.
- Errors: SQLSTATE `42501` no live admin session or a clinic session is bound; `SA001` pre-auth token unknown, expired or used; `SA002` TOTP step replay; `SA003` recovery code unknown or used; `SA004` admin not found, off or not eligible; `SA005` recovery set is not 10 distinct hashes; `SA006` no pending secret; `SA007` already enrolled; `22023` bad parameter.
- Audit rows hold no email, password, code, secret, token or search text. `ADMIN_SIGN_IN_FAILED` is written with `outcome REFUSED` and a fixed refusal code, and the admin id only when the email is an admin's.
- Not built here (ADM-2/3): clinic list, clinic detail, module and plan functions; grants on `platform.tenant`, `clinic_profile`, `membership`, `ipd.*` for the definer.

## Tenant scope and RLS
None of the six tables is tenant data, so there is no RLS and no `tenant_id`. `target_tenant_id` on the audit is a plain nullable reference. No policy of any existing table changes; a clinic session sees exactly what it saw before.

## Dev seed
Callback `afterMigrate__dev_seed_platform_admin.sql` in `db/dev-seed` (location added to `spring.flyway.locations` only in `application-dev.yml`). Needs placeholders `sahaj_admin_seed_email`, `sahaj_admin_seed_name`, `sahaj_admin_seed_password_hash`, always defined in dev (empty means seed nothing). Upsert by email: re-activates, refreshes name and hash, keeps TOTP enrolment; clears all throttle rows (dev only). The hash is computed in Java, never in SQL.

## Expected rows and retention
- `platform_admin`: a handful (under 20). Kept forever; switched off, never deleted.
- `platform_admin_recovery_code`: 10 per enrolled admin; kept while the admin exists.
- `platform_admin_preauth`: one per password-correct sign-in; purge 1 day after expiry (job as migrator, later). Under 1,000 a year.
- `platform_admin_auth_throttle`: one per distinct typed email; purge 1 day idle and not locked (job later). Bounded by the sign-in rate limits.
- `platform_admin_session`: one per sign-in; purge 30 days after it ended (job later). Under 5,000 a year.
- `platform_admin_audit`: a few per sign-in plus ADM-2/3 access and action rows; well under 1 million a year. Kept 7 years, no delete path, no partitioning needed.

## Backward compatibility
Additive: six new tables and thirteen new functions; no existing table, policy or function changes. The previous app version ignores them.
Operational note: any environment without `sahaj_admin_definer` fails on this migration by design; create the role first.
