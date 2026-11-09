# ADM-2 and ADM-3 · Admin directory, clinic detail, modules, plan and activity: design note

Stories: `docs/stories/ADM-2.md` (2a, 2b), `docs/stories/ADM-3.md` (3a to 3d).
Mockup screens: Sahaj admin portal: `orgDirPage`, `aClinic`, `aModules`, `ADM_KIND.plan` / `aActHTML`, `aAuditRows`. Owner side: `cfgModules`, which is unchanged.
Module: platform (admin packages). ipd gives one count through `platform.api.IpdOccupancyPort`.
Decisions: ADR-003 rev 2 (frame), **ADR-016** (this design), DEC-012, DEC-029 (defaults and NEEDS HUMAN). Contract: `docs/api/admin.yaml` 0.4.0, tags ADM-2 and ADM-3.
Precondition (human): the role `sahaj_admin_definer` exists, and the ADM-1 core migrations parked in `backend/src/main/resources/db/pending/admin/` have been moved into `db/migration` and renumbered (DEC-016 H-DEV-5). Until then every migration below stays parked.

Facts checked on 9 Nov 2026:
- `platform.tenant (id, name, slug, plan text default 'FREE', plan_ends_at, status text default 'ACTIVE', created_at)`. Plan values in use: `FREE`, `PRO`.
- `platform.clinic_profile (tenant_id pk, name, city not null, specialty_code in gp|dental|paeds|homeo)`. There is no state column.
- `platform.membership (tenant_id, user_id, role, status ACTIVE|INACTIVE|REMOVED, last_login_at, created_at)` and `platform.app_user (id, mobile char(10), full_name)`.
- `platform.setup_progress (tenant_id pk, current_step, answers_json, completed_at)`.
- `platform.module_entitlement (tenant_id pk, ipd_state, ipd_trial_ends_at, hra_on, bed_limit 5..300, ipd_requested_at, updated_at, updated_by, version)`. The row is created lazily. Its defaults are off / false / 25.
- `platform.admin_require_session()` and `platform.platform_admin_audit` exist in the parked ADM-1 migrations.
- `RequestBodySizeLimitFilter` runs before the admin token filter, so the admin body cap is 4 KB.
- `platform.yaml` `POST /modules/ipd-trial` is already marked `deprecated: true` with the ADM-3 note. No contract change is needed for ADM-3d.

## 1. Endpoints (`/api/v1/admin`, role `PLATFORM_ADMIN`)

| Method | Path | Story | Request | Response | Errors |
|---|---|---|---|---|---|
| POST | `/clinics/search` | 2a | `ClinicSearch` | `ClinicPage` | 400, 401, 403, 413, 503 |
| GET | `/clinics/{id}` | 2b | none | `ClinicDetail` | 400, 401, 403, 404, 503 |
| PATCH | `/clinics/{id}/modules` | 3a | `ModulesChange` | `ClinicDetail` | 400, 401, 403, 404, 409, 413, 503 |
| PUT | `/clinics/{id}/plan` | 3b | `PlanChange` | `ClinicDetail` | 400, 401, 403, 404, 413, 503 |
| GET | `/clinics/{id}/activity` | 3c | `?page&size` | `ActivityPage` | 400, 401, 403, 404, 503 |

ADM-3d needs no new endpoint. The java-developer sets `sahaj.modules.owner-ipd-trial=false` in `application-dev.yml`.

## 2. Search semantics (`ClinicSearch`, function `admin_list_clinics`)

Words are split on white space (at most 8 words). Matching is case-insensitive. Text is escaped for `ILIKE` inside the function.

| `field` | Matches when (mockup `odMatch`) |
|---|---|
| `all` | every word is inside the name, code (slug), city or owner name, OR the digits of `q` (4 or more) are inside the owner's mobile |
| `name` | `q` is inside the name |
| `code` | the slug starts with `q` |
| `mobile` | the digits of `q` (non-digits removed, 4 to 10 digits, otherwise 400) are inside the owner's mobile |
| `city` | `q` is inside the city |

Name = `clinic_profile.name` when present, otherwise `tenant.name`. Owner = the earliest-created `ACTIVE` membership with role `OWNER`.

Filters: `status`, `plan`, `specialty` and `city` (exact city text) allow several values. Values inside one filter are OR. Different filters are AND. `signedWithinDays` matches `tenant.created_at >= now() - n days`.

Facets: each facet counts the rows that match the search, `signedWithinDays` and every other filter, but not the facet itself. Options are sorted by count (descending), then A–Z. Selected values are always included, even with count 0. The city facet has at most 500 options.

Sort:
- `name`: name A–Z, then id.
- `active`: `lastActiveAt` descending, never-signed-in last, then name.
- `new`: `created_at` descending.
- `relevance`: only for `field=all` with `q`, otherwise it acts as `name`. The score follows the mockup `orgRank`: code equals `q` 100; name starts with `q` 80; a word in the name starts with `q` 62; mobile digits match 55; city starts with `q` 50; every word found 46 (several words) or 40; plus 2 when status is ACTIVE. Ties sort by name. The score only orders the rows. It never removes a row that matched (story AC 4 defines matching).

Paging: `page` 0..10000, `size` 25 | 50 | 100. A page past the end returns `items: []` with the real total. `totalAll` counts every organization.

Derived values (unchanged from ADR-003):
- `lastActiveAt` = the latest `last_login_at` over `ACTIVE` memberships.
- `status` = `SUSPENDED` when `tenant.status <> 'ACTIVE'`; else `INACTIVE` when there is no sign-in or it is older than 7 days; else `ACTIVE`.
- `health` = `SUSPENDED`, else `INACTIVE`, else `NEEDS_HELP` when `setup_progress.completed_at` is null, else `HEALTHY`. This rule is interim (DEC-029 NH-1).
- `setupComplete` = `completed_at is not null`.
- `ownerMobileLast5` = `right(mobile, 5)`, computed in SQL. The full number never leaves the function.

Performance target: 10,000 tenants, p95 under 1 s. One CTE builds the matched set once (tenant ⨝ profile ⨝ owner ⨝ last login ⨝ entitlement). Facets come from that CTE with `count(*) filter (…)` per value. The page uses `LIMIT/OFFSET` with a window total. Indexes: `membership (tenant_id, role, status, created_at)` and `membership (tenant_id) include (last_login_at) where status = 'ACTIVE'`. The db-engineer checks these with `EXPLAIN` on 10,000 generated tenants in `sahaj_test` only.

## 3. Modules (3a), plan (3b) and activity (3c): flow

Modules: the order and the refusal are in ADR-016 D4.
- Trial end = Java `now + 30 days` (`ModuleEntitlements`).
- Toast texts are frontend, from the mockup.
- 409 detail: "{n} patients are admitted in {clinic name}. IPD stays {current effective state label in lower case} until they are discharged." The word "patients" stays as drawn, even for 1.

Plan:
- Lock `tenant` `FOR UPDATE`.
- The same plan → return the detail, with no row written.
- Otherwise update `plan`, `updated_at = now()`, `version + 1` and write `PLAN_CHANGED`.
- The function checks again that the trimmed reason is 10..500 characters and the ticket matches `^[A-Za-z0-9-]{1,20}$`. Failures raise 22023, which Java maps to 400.
- Interim per DEC-029 NH-4 and NH-5.

Activity: APPLIED `ACTION` rows only (ADR-016 D5), newest first, `size` 1..100 (UI 20).
- `reason` = the stored reason, plus " (ticket {T})" when a ticket was given.
- `approval` = "—".
- `adminRoleLabel` = "Platform admin".

## 4. Data needed (for the db-engineer; parked until the role exists)

No new table and no new tenant-owned table. Schemas `platform` and `ipd`. Every function follows ADR-003 D4 rules 1 to 7:
- `SECURITY DEFINER`, owner `sahaj_admin_definer`;
- `SET search_path = pg_catalog, pg_temp`, every object schema-qualified;
- no `EXECUTE`;
- the guard first;
- `REVOKE EXECUTE … FROM PUBLIC`, then `GRANT EXECUTE … TO sahaj_app`, except `admin_clinic_json`;
- the audit row inside the function.

Versioned migrations only (`V…__platform_admin_directory_functions.sql`, `V…__ipd_admin_admitted_count.sql`), with the role-exists check that ADM-1 uses.

### 4.1 Grants and policies `TO sahaj_admin_definer` (as ADR-003 "Policies and grants")

| Table | Grant | Policy (only `TO sahaj_admin_definer`) |
|---|---|---|
| `platform.tenant` | `SELECT (id, name, slug, plan, plan_ends_at, status, created_at)`, `UPDATE (plan, updated_at, version)` | `FOR SELECT USING (true)`; `FOR UPDATE USING (true) WITH CHECK (true)` |
| `platform.clinic_profile` | `SELECT (tenant_id, name, city, specialty_code)` | `FOR SELECT USING (true)` |
| `platform.membership` | `SELECT (tenant_id, user_id, role, status, last_login_at, created_at)` | `FOR SELECT USING (true)` |
| `platform.app_user` (no RLS) | `SELECT (id, full_name, mobile)` | none |
| `platform.setup_progress` | `SELECT (tenant_id, completed_at)` (narrower than ADR-003: `current_step` is not used) | `FOR SELECT USING (true)` |
| `platform.module_entitlement` | `SELECT`, `INSERT (tenant_id)`, `UPDATE (ipd_state, ipd_trial_ends_at, hra_on, bed_limit, updated_at, version)` (column-limited; `ipd_requested_at` and `updated_by` are never written by an admin) | `FOR SELECT USING (true)`; `FOR INSERT WITH CHECK (true)`; `FOR UPDATE USING (true) WITH CHECK (true)` |
| `ipd.admission` | `SELECT (tenant_id, status)`, never `reason` or any patient column | `FOR SELECT USING (true)` |
| `platform.platform_admin_audit` | already `SELECT, INSERT` (ADM-1) | none |
| `platform.platform_admin` | already `SELECT (id, full_name, active …)` (ADM-1) | none |

No `sahaj_app` policy is changed. A FOR UPDATE row lock needs `UPDATE` on at least one column: the tenant and entitlement grants above cover it.

### 4.2 Functions (signatures; all return SQL `NULL` for an unknown tenant and then write nothing)

```
platform.admin_clinic_json(p_tenant uuid) returns jsonb            -- internal; EXECUTE revoked from PUBLIC and sahaj_app
  ClinicDetail shape without modules.admittedNow; codes only, no labels.

platform.admin_list_clinics(
  p_q text,                 -- trimmed raw text or null; escaped inside; never stored
  p_field text,             -- all|name|code|mobile|city
  p_status text[], p_plan text[], p_specialty text[], p_city text[],
  p_signed_within_days int, -- null|30|90|365
  p_sort text,              -- relevance|name|active|new
  p_limit int, p_offset int,-- limit in (25,50,100), offset <= 10000*100
  p_ip inet, p_request_id text) returns jsonb   -- {items[], total, totalAll, facets{status,plan,specialty,city}}
  audit ACCESS CLINICS_LISTED, after_json per ADR-016 D8 (hasQuery, field, filters, cityCount, total)

platform.admin_get_clinic(p_tenant uuid, p_ip inet, p_request_id text) returns jsonb
  audit ACCESS CLINIC_VIEWED

platform.admin_lock_modules(p_tenant uuid)
  returns table (ipd_state text, ipd_trial_ends_at timestamptz, hra_on boolean, bed_limit int)
  inserts (tenant_id) on conflict do nothing, then SELECT … FOR UPDATE; no audit (ADR-016 D3)

platform.admin_set_modules(
  p_tenant uuid,
  p_ipd_state text, p_ipd_trial_ends_at timestamptz,   -- null = unchanged; trial_ends_at set iff state = 'trial'
  p_hra_on boolean, p_bed_limit int,                   -- null = unchanged; bed_limit 5..300, multiple of 5
  p_refusal_code text, p_admitted_now int,             -- both null, or 'ipd-patients-admitted' with n >= 1 and p_ipd_state in (off, readonly)
  p_ip inet, p_request_id text) returns jsonb          -- admin_clinic_json after the change
  APPLIED: one ACTION MODULES_CHANGED row per field whose value differs, summary from fixed CASE texts, reason 'Module entitlement'
  REFUSED: one row, summary 'IPD set to {label}', outcome REFUSED, refusal_code, after_json {"admittedNow": n}; nothing updated
  sets updated_at = now(), version = version + 1 when anything changes

platform.admin_set_plan(p_tenant uuid, p_plan text, p_reason text, p_ticket text, p_ip inet, p_request_id text) returns jsonb
  p_plan in ('FREE','PRO'); reason btrim 10..500; ticket null or ^[A-Za-z0-9-]{1,20}$; same plan = no write, no audit
  audit ACTION PLAN_CHANGED with before/after, reason, ticket

platform.admin_list_clinic_activity(p_tenant uuid, p_limit int, p_offset int, p_ip inet, p_request_id text) returns jsonb
  {items[{id, at, adminName, summary, reason, ticket, outcome}], total}; kind ACTION, outcome APPLIED, target_tenant_id = p_tenant
  audit ACCESS CLINIC_ACTIVITY_VIEWED

ipd.admin_admitted_count(p_tenant uuid) returns integer
  guard first; count(*) from ipd.admission where tenant_id = p_tenant and status = 'ADMITTED' (interim, DEC-029 NH-6); no audit
```

SQLSTATEs: `42501` (no live admin session, or a clinic is bound) and `22023` (bad parameter). Java maps them to 401 and 400.

Optional (ADR-003): `platform.tenant` `check (plan in ('FREE','PRO'))` added `NOT VALID`, then `VALIDATE`. **Hold it until NH-4 is answered**, because the plan names may change.

### 4.3 Tenant scope and retention

- Tenant-owned rows read: tenant, profile, membership, setup progress, entitlement and admission status, only through the functions above.
- Written: `tenant.plan` and `module_entitlement`, only through the functions above.
- Audit: `platform_admin_audit`, append-only, 7 years, no delete path.
- No patient table is granted at all.

## 5. Notes for other owners

- **java-developer**:
  - `POST /clinics/search` controller.
  - The admin directory, modules and plan services: one `@Transactional` call each. The refusal is a normal return, so the REFUSED row commits (ADR-016 D4).
  - `IpdOccupancyPort` in `platform.api`; its implementation in `com.sahaj.ipd`.
  - Labels.
  - Pass raw trimmed `q` (do not escape it).
  - Never log request bodies or query strings on `/api/v1/admin/**`.
  - 42501 → 401, 22023 → 400.
  - `owner-ipd-trial=false` in `application-dev.yml`.
- **IPD developers**: read the entitlement `FOR SHARE` in the admit transaction (ADR-003 D6; DEC-029 NH-7).
- **angular-developer**:
  - Keep the list state in memory, not in the URL.
  - Refused rows are not in the Activity tab.
  - The error, retry and not-found texts wait for NH-2 and NH-8.
- **qa-engineer**:
  - Search text absent from logs, audit and URLs.
  - Facet counts.
  - 4 KB cap.
  - REFUSED row persists after the 409.
  - No-op writes nothing.
  - `admin_clinic_json` not executable as `sahaj_app`.
  - A clinic session still sees only its own tenant.
