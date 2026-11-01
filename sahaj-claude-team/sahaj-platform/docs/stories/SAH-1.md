# SAH-1 · Project foundation
Mockup screen: Foundation (no screen)
Module: platform

## 1. Story
As the Sahaj team, I want the backend, frontend, tenant isolation and audit foundation in place, so that every later story builds on a boundary that holds.

Replaces the earlier "Project scaffold" and "Tenancy, users, roles and audit" stories. The scaffold code already exists on `feature/SAH-1-project-scaffold`; this story extends it.

## Acceptance criteria
1. Given a fresh checkout and the databases created, when I run `node scripts/validate.mjs all`, then it passes.
2. Given two tenants, when tenant B's user requests tenant A's data by id, then the API returns 404 and no row leaks (integration test as `sahaj_app`).
3. Given any change to a tenant row, when it is saved, then an `audit_event` row records who, what, when and the tenant.
4. Given the backend packages, when the Spring Modulith test runs, then `platform, patient, opd, ipd, hra, billing` pass and a cross-module internal reference fails.
5. Given the Angular app, when lint, unit tests and the production build run, then they pass, and the theme uses `docs/design/tokens.md` (light default, dark supported, brand colours blue, teal, green, maroon).
6. Given the app runs, when `GET /api/v1/platform/health` is called with no login, then it returns 200 and `{"status":"UP"}`.
7. Given any API error, then the response is RFC 9457 `application/problem+json`.
8. Given a request with no tenant bound, when it reads a tenant table, then it returns no rows (fail closed).
9. Given the app and Flyway, then the app connects as `sahaj_app` and Flyway as `sahaj_migrator`; profile `dev` uses `sahaj_dev` and `test` uses `sahaj_test`.
10. Given a client sends a tenant id in a body, header or query, then it is ignored; the tenant comes only from the logged-in user.

## 3. Technical design
- Reuse ADR-002 D1–D4 and D7 where they do not conflict with this file: tenant = organization, `FORCE ROW LEVEL SECURITY`, policies verified as `sahaj_app`, audit table has `SELECT, INSERT` only.
- ADR-002 D5 (opaque sessions) is replaced by SAH-3's Spring Security JWT (human decision, 1 Nov 2026).
- Tables, schema `platform`:
  - `tenant`: `id uuid pk`, `name`, `slug unique null`, `plan text`, `plan_ends_at`, `status`, `brand`, `created_at`. RLS: `id = current tenant`.
  - `app_user`: `id`, `mobile char(10) unique`, `full_name`, `created_at`. Global person, no `tenant_id`, no password column. No RLS; reached only through platform services.
  - `membership`: `id`, `tenant_id`, `user_id`, `role`, `status` (ACTIVE, INACTIVE, REMOVED), `last_login_at`, unique (`tenant_id`, `user_id`). RLS.
  - `audit_event`: `id`, `tenant_id`, `actor_user_id`, `action`, `entity_type`, `entity_id`, `before_json`, `after_json`, `at`. RLS, no UPDATE or DELETE grant. Never store patient names, mobiles or OTP in clear; mask mobile to the last 4 digits.
- Migration names: `V<YYYY>_<MM>_<DD>_<HHMM>__platform_<what>.sql`. `validate db` requires RLS on every table in a file that mentions `tenant_id`, so put `app_user` in its own file.
- Tenant binding: `SET LOCAL app.tenant_id` at transaction start, from the authenticated principal only. Use one aspect or transaction hook. Two datasources: Hikari as `sahaj_app`, Flyway as `sahaj_migrator`.
- Security filter chain: everything denied by default; only `/health` and `/public/**`, `/auth/**` (later stories) open.
- Audit port: `com.sahaj.platform.api.AuditLog.record(...)`, called in the same transaction.
- SAH-1 has no login yet, so integration tests build the principal with a test security helper. The by-id test uses a test-only controller under `src/test`.
- Frontend: `frontend/src/styles/_tokens.scss`, custom M3 theme, standalone components, `ProblemDetail` HTTP error interceptor. Figtree and Noto Sans Devanagari fonts.
- Config comes from environment variables. `.env.example` is currently deleted in the working tree; backend-dev lists needed variables in the Status log for the human.

## 4. API
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/api/v1/platform/health` | public | none | `{status}` | none |

Contract: `docs/api/platform.yaml`.

## 5. Tasks
### Tasks — Backend
- Add web, security, JDBC or JPA, Flyway, PostgreSQL, validation and test dependencies. Note any library beyond Spring Boot starters and Flyway in the Status log.
- Migrations for the four tables with RLS. Grants for `sahaj_app`.
- Tenant binding, fail-closed behaviour, deny-by-default security, RFC 9457 handler, health endpoint, audit port.
- Integration tests on `sahaj_test` for criteria 2, 3, 6, 7, 8, 9, 10. Keep `ModularityTests` passing.

### Tasks — Frontend
- Token SCSS, Material theme, light and dark, four brand colours by `data-brand` on the root element.
- Error interceptor, API base URL config, folder layout (`core`, `shared`, `features`).
- Add Playwright with one smoke test that the app loads. A new dev dependency is allowed for this story.
- Lint, unit tests and build pass.

## 6. Out of scope, Open questions
Out of scope: login, screens, business data.
Open questions:
- `validate.mjs` line 28 still passes `--browsers=ChromeHeadless` (DEC-002, human edit not applied). The frontend gate may fail until it is changed.

## 7. Status log
