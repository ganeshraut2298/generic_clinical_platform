# Database design · SAH-5 (app shell)

Module: platform · Author: db-engineer · Date: 1 Nov 2026
Sources: `docs/stories/SAH-5.md` section 3, `docs/api/platform.yaml` (`Me`, `Preferences`), SAH-1/SAH-3 migrations 1000-1012.

Status: SQL written and applied — `node scripts/validate.mjs db` ran Flyway against `sahaj_test`
cleanly through this migration (1018); see "Validation" below.

## Migration

| File | Creates |
| --- | --- |
| `V2026_11_01_1018__platform_create_user_preference.sql` | `platform.user_preference` |

Applied after 1000-1017 (SAH-4's migrations). 1000-1012 are untouched (forward-only).

## Table

### platform.user_preference (RLS, FORCE, composite key)
| Column | Type | Null | Default / check |
| --- | --- | --- | --- |
| tenant_id | uuid | no | default `platform.current_tenant()`; fk to `platform.tenant(id)` |
| user_id | uuid | no | fk to `platform.app_user(id)` |
| language | text | no | default 'en'; en/mr/hi |
| theme | text | no | default 'light'; light/dark |
| menu_mode | text | no | default 'advanced'; simple/advanced |
| sidebar_collapsed | boolean | no | default false |
| created_at, updated_at, version | | | standard |

Primary key: `(tenant_id, user_id)` — a person's preference can differ per clinic they belong to
(`platform.membership` is already many-tenants-per-user), so the row is keyed on the pair, not
on `user_id` alone.

- Policy `p_user_preference_self` (FOR ALL): `tenant_id = platform.current_tenant() AND user_id = platform.current_user_id()`. This is **tighter** than the usual tenant-only policy (e.g. `platform.membership`): preferences are private to the person, not shared clinic data, so no other user in the same tenant — not even the owner — can read or write another user's row through `sahaj_app`.
- This requires the backend to bind **both** `app.tenant_id` and `app.user_id` (`SET LOCAL`, `platform.current_user_id()` from SAH-3/DEC-007) on **every** authenticated request, not only right after OTP verify as SAH-3 first used `app.user_id`. If `/me` or `/me/preferences` only sets `app.tenant_id`, every query against this table returns zero rows and every write is rejected (fail-closed, same shape as every other policy here) — this is a real wiring requirement for java-developer, not optional.
- Grants: SELECT, INSERT, UPDATE. No DELETE (a user's preference row for a clinic is edited, not removed; an inactive/removed membership just stops reading it).
- No separate tenant-first index needed beyond the primary key, which already starts with `tenant_id` and serves both "this tenant's rows" and "this (tenant, user) row" lookups.
- Rows: one per (tenant, user) pair, so roughly 5-15 per tenant at steady state — same order as `membership`. Never deleted.

## Nothing else from SAH-5's design needed a table

- Menu/role mapping (`NavigationPolicy`, the role→menu-keys table in the story) is static application logic, not data — no table.
- Module on/off (OPD on; IPD, HRA off) is a phase-1 constant in code, not configurable per tenant yet — no table. If/when modules become per-tenant toggles, that is a new migration against `platform.tenant` or a new `platform.tenant_module` table; out of scope here.
- i18n label files (`en`, `mr`, `hi`) are static JSON shipped with the frontend, not database rows.
- `Me.clinic.setupComplete` / `setupPercent` read `platform.setup_progress` (SAH-4, `V2026_11_01_1013`), not a SAH-5 table — see `docs/database/SAH-4.md`, "For java-developer: the login/mid-setup signal".

## Decisions and open points

1. **Composite primary key, not a surrogate `id`.** Deviates from the usual `id uuid primary key` pattern used elsewhere in this schema, because the natural key `(tenant_id, user_id)` is exactly what every query needs (`/me` always asks "this user, in this tenant") and a surrogate id would only add an extra unique index for no benefit. If this proves awkward for JPA (`@IdClass`/`@EmbeddedId`), tell me and I will add a surrogate `id` plus a `uq_user_preference_tenant_user` unique constraint in a new migration instead — not changing this one.
2. **No partitioning, no retention concern.** One row per (tenant, user), far under any threshold.
3. **Backward compatibility**: additive only; no change to 1000-1017.

## Verification (to run when Flyway is on the classpath)

- With no `app.tenant_id`/`app.user_id` set, `select` returns 0 rows; insert fails.
- With `app.tenant_id` set to tenant A and `app.user_id` set to user X (a member of A), `select`/`update` reaches only the `(A, X)` row.
- With `app.tenant_id` set to tenant A but `app.user_id` unset (or set to a different user Y), the `(A, X)` row is not visible and not updatable — confirms the policy needs both, not just the tenant match.
- `delete` fails with 42501 (no grant).

## Validation

`node scripts/validate.mjs db` result: the migration-file lint and Flyway apply (through 1018,
including this table) both succeeded. The one test failure the run surfaced
(`ModularityTests.verifiesModularStructure`) is unrelated to this migration — see
`docs/database/SAH-4.md`, "Validation", for details; it is a backend Java boundary issue in the
`opd` module, flagged for java-developer.
