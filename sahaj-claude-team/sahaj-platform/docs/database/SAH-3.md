# Database design · SAH-3 (sign up and log in with mobile one-time code)

Module: platform · Schema: `platform` · Author: db-engineer · Date: 1 Nov 2026
Sources: `docs/stories/SAH-3.md` section 3, `docs/decisions/DEC-007-three-agent-mode.md` (login lookup default), SAH-1 migrations 1000-1007 (`platform.current_tenant()`, `platform.tenant`, `platform.app_user`, `platform.membership`).

Status: SQL written, **not yet executed** against any database (no database access for this agent).

## Migrations (apply after 1000-1007, in this order)

| File | Creates |
| --- | --- |
| `V2026_11_01_1008__platform_create_otp_challenge.sql` | `platform.otp_challenge` |
| `V2026_11_01_1009__platform_create_refresh_token.sql` | `platform.refresh_token` |
| `V2026_11_01_1010__platform_create_consent_record.sql` | `platform.consent_record` |
| `V2026_11_01_1011__platform_membership_user_lookup_policy.sql` | `platform.current_user_id()`, policy `p_membership_self_read` on `platform.membership` |
| `V2026_11_01_1012__platform_refresh_token_user_lookup_policy.sql` | policies `p_refresh_token_self_read`, `p_refresh_token_self_revoke` on `platform.refresh_token` |

1000-1007 are untouched (forward-only). 1008-1012 are new files.

## Tables

### platform.otp_challenge (no RLS, no tenant column)
| Column | Type | Null | Default / check |
| --- | --- | --- | --- |
| id | uuid | no | pk, `gen_random_uuid()` |
| mobile | char(10) | no | `^[6-9][0-9]{9}$` (not unique: history of challenges per mobile) |
| code_hash | text | no | 1 to 200 chars; the app's hash of the 6-digit code, never the code |
| expires_at | timestamptz | no | |
| failed_count | integer | no | default 0; `>= 0` |
| locked_until | timestamptz | yes | set after 5 wrong codes (AC3), 15 minutes ahead |
| last_sent_at | timestamptz | no | `now()`; drives the 30-second resend throttle |
| consumed_at | timestamptz | yes | set once verify succeeds; a consumed or expired row is never reused |
| created_at | timestamptz | no | `now()` |

- Index `ix_otp_challenge_mobile_created (mobile, created_at desc)`: serves "the current challenge for this mobile" on request, resend, verify and lock checks — always the newest row.
- No tenant_id, no row level security: verify runs before any tenant exists; there is nothing to key a tenant policy on. Not multi-tenant data by nature (keyed on a mobile number, not a clinic).
- Grants to `sahaj_app`: SELECT, INSERT, UPDATE. No DELETE — a retention/cleanup job run as `sahaj_migrator` removes old rows, not the app.
- Rows: one per OTP send. At most a handful per mobile per sign-in attempt; cleaned up well before volume matters. Never contains the code itself, only its hash — do not log `code_hash` or `mobile`.

### platform.refresh_token (RLS, FORCE)
| Column | Type | Null | Default / check |
| --- | --- | --- | --- |
| id | uuid | no | pk |
| tenant_id | uuid | no | default `platform.current_tenant()`; fk `fk_refresh_token_tenant` to `platform.tenant(id)` |
| user_id | uuid | no | fk `fk_refresh_token_user` to `platform.app_user(id)` |
| token_hash | text | no | unique `uq_refresh_token_token_hash`; 1 to 200 chars; the app's hash of the opaque token, never the token |
| expires_at | timestamptz | no | |
| revoked_at | timestamptz | yes | set on logout, rotation, or logout-all |
| created_at | timestamptz | no | `now()` |

- Index `ix_refresh_token_tenant_user (tenant_id, user_id)`: serves "every live token for this user in this tenant" — logout, logout-all, and the membership-deactivation check on each request.
- Policy `p_refresh_token_tenant` (FOR ALL, same shape as `platform.membership`): `tenant_id = platform.current_tenant()` for USING and WITH CHECK. A refresh token is only ever created or read once a tenant is selected (after `AuthResult.next = APP`), so this is the same pattern as every other tenant-owned table — no special-casing needed here, unlike `otp_challenge`.
- Grants to `sahaj_app`: SELECT, INSERT, UPDATE. No DELETE — a used, rotated or revoked token keeps its row (`revoked_at` set) so rotation-reuse can still be detected and reported; a retention job removes long-expired rows later.
- Rows: a handful of live tokens per user per device at any time; grows with logins, shrinks with expiry/cleanup. Never deleted by the app.
- **Resolved (was flagged, now fixed in 1012)**: AC15 ("every refresh token for the user stops working") requires revoking across **every** tenant the user belongs to in one call, not just the currently bound tenant. `p_refresh_token_tenant` alone cannot do that (no tenant is bound for the other tenants' rows). `V2026_11_01_1012__platform_refresh_token_user_lookup_policy.sql` adds two additional permissive policies, same shape as `p_membership_self_read` (1011), keyed on `platform.current_user_id()`:
  - `p_refresh_token_self_read` (FOR SELECT): find every live token for the user across all tenants.
  - `p_refresh_token_self_revoke` (FOR UPDATE, `using` and `with check` both `user_id = platform.current_user_id()`): set `revoked_at` on those rows. UPDATE needed in addition to SELECT because logout-all's job is to revoke, not just list.
  No DELETE policy was added — `sahaj_app` has no DELETE grant on this table at all (unchanged from 1009), so a DELETE policy would be inert; revocation is always by setting `revoked_at`, never by removing the row (reuse-detection history is kept, per the existing retention note below).
  `p_refresh_token_tenant` (FOR ALL) is unchanged and still governs normal per-request reads and all INSERT, exactly as before. PostgreSQL ORs permissive policies for the same command/role, so after 1012: SELECT is allowed when tenant matches OR user matches; UPDATE is allowed when tenant matches OR user matches; INSERT is governed by the tenant policy alone (no self-insert policy was added — a token is only ever created within a bound tenant, so there was no gap to close there).
  Existing index `ix_refresh_token_tenant_user (tenant_id, user_id)` still serves `user_id` lookups reasonably well as its second key column; no new index was added since the existing table is small per user (a handful of live tokens).

### platform.consent_record (no RLS, no tenant column)
| Column | Type | Null | Default / check |
| --- | --- | --- | --- |
| id | uuid | no | pk |
| user_id | uuid | no | fk `fk_consent_record_user` to `platform.app_user(id)` |
| terms_version | text | no | 1 to 40 chars (e.g. `v0.1`, per the human decision on placeholder legal text) |
| privacy_version | text | no | 1 to 40 chars |
| at | timestamptz | no | `now()` |
| source_ip | inet | yes | |

- Index `ix_consent_record_user_at (user_id, at desc)`: serves a compliance lookup of a user's consent history.
- No tenant_id, no row level security: consent is recorded at sign-up, before a tenant exists, and is keyed on the person, not the clinic.
- Grants to `sahaj_app`: SELECT, INSERT. No UPDATE, no DELETE — a consent record is a legal fact, immutable once written (append-only by omission of grants; no extra trigger was asked for and none was added).
- Rows: one per sign-up (AC12). Never deleted. Retention: indefinite, this is the compliance record itself.

## The login-lookup default (DEC-007): platform.current_user_id() and p_membership_self_read

Problem: SAH-3's verify step confirms the one-time code and must then decide `AuthResult.next` — `SETUP` (brand new user+tenant), `APP` (exactly one membership), `CHOOSE_CLINIC` (more than one), or `NOT_REGISTERED` — all **before any tenant is chosen or bound**. `platform.membership` is RLS-protected by `p_membership_tenant`, keyed on `platform.current_tenant()`, which is NULL until a tenant is bound; fail-closed means that lookup returns zero rows today, for every user, which is exactly the gap SAH-1 (section 164, point 4) flagged and left for SAH-3 to close.

Fix, adopted in DEC-007 without further sign-off: once OTP verification succeeds, the application binds `app.user_id` (`SET LOCAL`, server-derived from the verified `app_user.id`, **never from the request body**) for the rest of that transaction, before any tenant is bound. A new helper function, named the same way as `platform.current_tenant()`:

```sql
create function platform.current_user_id() returns uuid language sql stable as $$
  select nullif(current_setting('app.user_id', true), '')::uuid
$$;
```

and one additional **permissive SELECT** policy on `platform.membership`:

```sql
create policy p_membership_self_read on platform.membership
  for select to sahaj_app
  using (user_id = platform.current_user_id());
```

Why this is safe:
- PostgreSQL ORs multiple permissive policies that apply to the same command and role. `p_membership_tenant` is `FOR ALL` (covers SELECT, INSERT, UPDATE); this new policy is `FOR SELECT` only. So after this migration, a SELECT is allowed when **either** the row's `tenant_id` matches the bound tenant **or** the row's `user_id` matches the bound user. INSERT, UPDATE and DELETE are governed by `p_membership_tenant` alone, unchanged — this migration grants no new write path, as required.
- It cannot be used to read another user's rows: the `using` clause is an exact `user_id = platform.current_user_id()` match, and `current_user_id()` only ever holds the one id the application bound after that user's own OTP succeeded. There is no "unset means all" branch (same fail-closed shape as `current_tenant()`): with `app.user_id` unset, the function returns NULL and `user_id = NULL` is never true.
- No existing grant needed changing: `sahaj_app` already has SELECT on `platform.membership` from SAH-1 (1003); this migration only adds a second policy that said SELECT privilege is now evaluated against.

Existing index `ix_membership_user (user_id)` (SAH-1, 1003) already serves this policy's filter; no new index was needed.

## Decisions and open points

1. **refresh_token is tenant-scoped by default (`p_refresh_token_tenant`), plus a narrow user_id-scoped SELECT/UPDATE policy (`p_refresh_token_self_read`/`p_refresh_token_self_revoke`, 1012) so logout-all can revoke across every tenant the user belongs to**, per AC15. See the resolved note under `platform.refresh_token` above.
2. **otp_challenge and consent_record have no uniqueness constraint tying them together or to app_user beyond consent_record's foreign key.** `otp_challenge.mobile` is not unique and not a foreign key to `app_user.mobile`, on purpose: an OTP can be requested for a mobile that has no `app_user` row yet (new clinic sign-up). The application, not the database, matches a verified mobile to an existing or new `app_user`.
3. **No partitioning.** All three new tables are small/short-lived relative to the 50 million row planning threshold (otp_challenge and refresh_token churn and get cleaned up; consent_record grows at one row per sign-up).
4. **Retention**: `otp_challenge` — proposed short-lived (clean up well after `expires_at`/`locked_until` pass), by a job run as `sahaj_migrator`, not decided with the human. `refresh_token` — keep revoked/expired rows for a reuse-detection window, then clean up the same way, not decided. `consent_record` — never deleted (legal record).
5. **Backward compatibility**: additive only, no drops or renames of 1000-1007. The previous app version (before SAH-3) never read these three tables or the new policy, so it is unaffected.

## Verification (to run when Flyway is on the classpath)

- With no `app.tenant_id` and no `app.user_id` set, `select count(*)` on `membership` returns 0 (unchanged from SAH-1).
- With `app.user_id` set to a real user's id and no `app.tenant_id` set, `select * from platform.membership` returns exactly that user's own rows across every tenant they belong to, and no other user's rows.
- With `app.user_id` set to user A's id, a row belonging to user B is not returned, and user A cannot `insert`/`update`/`delete` any membership row without `app.tenant_id` also being correctly bound (the `FOR ALL` tenant policy still gates writes).
- `otp_challenge`: `sahaj_app` can select/insert/update, cannot delete; no tenant setting affects visibility (no RLS).
- `refresh_token`: same tenant-isolation behaviour as `membership`/`audit_event` from SAH-1 for normal requests — tenant A cannot see or write tenant B's rows via `p_refresh_token_tenant`. Additionally, with `app.user_id` set to user A's id and no `app.tenant_id` bound, `select` returns every live/revoked token row for user A across every tenant, and `update ... set revoked_at = now() where user_id = <A>` revokes all of them in one statement (AC15); user B's rows are never visible or updatable under user A's `app.user_id`. INSERT still requires a bound `app.tenant_id` (no self-insert policy was added).
- `consent_record`: `sahaj_app` can select/insert; update and delete fail with 42501 (permission denied).
