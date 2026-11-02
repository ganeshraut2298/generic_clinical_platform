# Database design · SAH-1 (foundation) and SAH-2 (home page enquiry)

Module: platform · Schema: `platform` · Author: db-engineer · Date: 1 Nov 2026
Sources: `docs/stories/SAH-1.md` section 3, `docs/stories/SAH-2.md` section 3, `docs/architecture/adr/ADR-002-tenancy-identity-and-audit.md` (D1-D4, D7; D5 superseded), `docs/api/platform.yaml`.

Status: SQL written, **not yet executed** against any database (no database access for this agent). This includes migrations 1006 and 1007.

## Migrations (apply in this order)

| File | Creates |
| --- | --- |
| `V2026_11_01_1000__platform_current_tenant_function.sql` | `platform.current_tenant()` |
| `V2026_11_01_1001__platform_create_tenant.sql` | `platform.tenant` |
| `V2026_11_01_1002__platform_create_app_user.sql` | `platform.app_user` |
| `V2026_11_01_1003__platform_create_membership.sql` | `platform.membership` |
| `V2026_11_01_1004__platform_create_audit_event.sql` | `platform.audit_event`, `platform.deny_audit_mutation()`, two triggers |
| `V2026_11_01_1005__platform_create_sales_enquiry.sql` | `platform.sales_enquiry` (SAH-2) |

| `V2026_11_01_1006__platform_revoke_app_access_to_flyway_history.sql` | no new object; `revoke all` on `platform.flyway_schema_history` from `sahaj_app` and `public` (security M3, code finding 8) |
| `V2026_11_01_1007__platform_restrict_audit_event_insert_columns.sql` | no new object; replaces table-level INSERT on `platform.audit_event` with a column-level INSERT (security L3) |

1006 and 1007 are new files. 1000 to 1005 are untouched (forward-only, even though they have not run anywhere yet).

Table names are singular because the stories name them so (the rule file says plural; the stories win).

## Tenant binding and fail-closed

`platform.current_tenant()` returns `nullif(current_setting('app.tenant_id', true), '')::uuid`.
- Unset setting gives `NULL`; `col = NULL` is never true, so a tenant table returns no rows and rejects inserts.
- `nullif(..., '')` is needed because after a transaction that used `SET LOCAL`, PostgreSQL leaves the setting as an empty string on that pooled connection.
- No policy contains a `coalesce` or an "unset means all" escape.
- The app must run `SET LOCAL app.tenant_id = '<uuid>'` inside every transaction (ADR-002 D2).

Grants: the schema default privileges (`scripts/db/schemas.sql`) give `sahaj_app` SELECT, INSERT, UPDATE and DELETE on every new table. Each migration therefore does `revoke all ... from sahaj_app` and then grants only what is listed below.

## Tables

### platform.tenant (RLS, FORCE)
| Column | Type | Null | Default / check |
| --- | --- | --- | --- |
| id | uuid | no | pk, `gen_random_uuid()` |
| name | text | no | 1 to 200 chars after trim |
| slug | text | yes | unique `uq_tenant_slug`; `^[a-z0-9][a-z0-9-]{2,23}$` (same as the contract) |
| plan | text | no | `'FREE'`; length 1 to 40, no value list |
| plan_ends_at | timestamptz | yes | |
| status | text | no | `'ACTIVE'`; length 1 to 40, no value list |
| brand | text | no | `'blue'`; one of blue, teal, green, maroon, custom (the contract `Brand`) |
| created_at | timestamptz | no | `now()` |
| updated_at | timestamptz | yes | |
| version | bigint | no | 0 |

- Policy `p_tenant_self` (FOR ALL to `sahaj_app`): `id = platform.current_tenant()` for USING and WITH CHECK.
- Grants to `sahaj_app`: SELECT, INSERT, UPDATE. No DELETE (a tenant is closed through `status`).
- To create a tenant the app must first bind `app.tenant_id` to the new id, then insert. That is for SAH-4 to do inside the setup transaction.
- Rows: one per clinic. 10,000 OPD + 100 IPD at target. Never deleted.

### platform.app_user (no RLS, no tenant column)
| Column | Type | Null | Default / check |
| --- | --- | --- | --- |
| id | uuid | no | pk |
| mobile | char(10) | no | unique `uq_app_user_mobile`; `^[6-9][0-9]{9}$` |
| full_name | text | no | 1 to 200 chars after trim |
| created_at | timestamptz | no | `now()` |
| updated_at | timestamptz | yes | |
| version | bigint | no | 0 |

- No password column. Grants: SELECT, INSERT, UPDATE. No DELETE.
- It has no row level security by decision (global person), so any `sahaj_app` session can read all rows. Access only through platform services; never expose a list endpoint.
- Rows: roughly 5 to 15 people per clinic, so under 200,000 at target. Never deleted.

### platform.membership (RLS, FORCE)
| Column | Type | Null | Default / check |
| --- | --- | --- | --- |
| id | uuid | no | pk |
| tenant_id | uuid | no | `platform.current_tenant()`; fk `fk_membership_tenant` to `platform.tenant(id)` |
| user_id | uuid | no | fk `fk_membership_user` to `platform.app_user(id)` |
| role | text | no | OWNER, DOCTOR, VISITING_DOCTOR, NURSE, FRONT_DESK, BILLING, PHARMACIST (the contract `Role`) |
| status | text | no | `'ACTIVE'`; ACTIVE, INACTIVE, REMOVED |
| last_login_at | timestamptz | yes | |
| created_at, updated_at, version | | | as above |

- Keys and indexes: `uq_membership_tenant_user (tenant_id, user_id)` serves the clinic team list and the duplicate check; `ix_membership_user (user_id)` serves the foreign key.
- Policy `p_membership_tenant` (FOR ALL): `tenant_id = platform.current_tenant()`, USING and WITH CHECK.
- Grants: SELECT, INSERT, UPDATE. No DELETE (status REMOVED keeps history).
- Rows: about 5 to 15 per clinic. Never deleted.

### platform.audit_event (RLS, FORCE, append-only)
| Column | Type | Null | Default / check |
| --- | --- | --- | --- |
| id | uuid | no | pk |
| tenant_id | uuid | no | `platform.current_tenant()`; fk to `platform.tenant(id)` |
| actor_user_id | uuid | yes | no foreign key (null for system actions) |
| action | text | no | 1 to 100 chars |
| entity_type | text | no | 1 to 100 chars |
| entity_id | uuid | yes | opaque, no foreign key |
| before_json | jsonb | yes | |
| after_json | jsonb | yes | |
| at | timestamptz | no | `now()` |

- Indexes: `ix_audit_event_tenant_at (tenant_id, at desc)` for the tenant's recent trail; `ix_audit_event_tenant_entity (tenant_id, entity_type, entity_id, at desc)` for the history of one record.
- Policies: `p_audit_event_select` (FOR SELECT) and `p_audit_event_insert` (FOR INSERT), both on `tenant_id = platform.current_tenant()`. There is no UPDATE, DELETE or ALL policy.
- Grants: `sahaj_app` SELECT (table level) and INSERT on six columns only; `revoke all` from `sahaj_app` and `public` first. See "Column-level INSERT on audit_event" below.
- Triggers `trg_audit_event_no_update_delete` and `trg_audit_event_no_truncate` raise an error for every role, including `sahaj_migrator`. Three layers: privileges, no policy, trigger.
- Never put patient names, mobile numbers or OTPs in the JSON columns; mask a mobile to its last 4 digits. The database cannot check this; the audit port must.
- Rows: not partitioned (see decisions). Estimate 2,000 to 20,000 per clinic per year while only configuration is audited; this rises sharply when clinical modules arrive. Plan monthly partitioning before 50 million rows.
- Retention: never deleted by the app. Retention period is a legal decision still open with the human.

### platform.sales_enquiry (no RLS, no tenant column)
| Column | Type | Null | Default / check |
| --- | --- | --- | --- |
| id | uuid | no | pk, `gen_random_uuid()` |
| name | text | no | 1 to 120 chars after trim |
| clinic_city | text | yes | at most 200 chars (contract) |
| mobile | varchar(10) | no | `^[6-9][0-9]{9}$` |
| best_time | text | yes | MORNING, AFTERNOON, AFTER_OPD |
| source | text | no | CONTACT_SALES, BOOK_DEMO |
| created_at | timestamptz | no | `now()` |

- Grant to `sahaj_app`: **INSERT only**. No SELECT, UPDATE or DELETE. Sales staff read it through a migrator or admin connection, outside the app.
- Rows: a few per day at most; rate limited to 5 per IP per hour. Mobile is personal data; never log it.
- Retention: proposed 12 months then delete by an operator job run as `sahaj_migrator`. Not decided; human to confirm.

## Flyway history table: no access for sahaj_app (V2026_11_01_1006)

Why: the default privileges in `scripts/db/schemas.sql` give `sahaj_app` SELECT, INSERT, UPDATE and DELETE on every table `sahaj_migrator` creates in a module schema. That covers the Flyway history table, so a compromised app could edit or delete migration records.

Where the table is: `backend/src/main/resources/application.yml` sets `spring.flyway.schemas: platform` and `default-schema: platform`; neither `application-dev.yml` nor `application-test.yml` sets a `table` name. So the table is `platform.flyway_schema_history`.

Change: `revoke all on platform.flyway_schema_history from sahaj_app` (and from `public`, a no-op kept for safety). Flyway creates the table before it runs the first migration, so it exists when this script runs. Flyway writes to it as `sahaj_migrator`, its owner, which this revoke does not affect. `flyway clean` in profile `test` drops and recreates the table; default privileges give `sahaj_app` access again, and the migration revokes it again on the next run, so the end state is the same.

The app never reads the table (Flyway does, as the migrator). Expected privileges for `sahaj_app` on it: none of SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER.

## Column-level INSERT on audit_event (V2026_11_01_1007)

Why: with table-level INSERT the app could set `at`, `id` and `tenant_id` itself (for example back-date an event). Now it may write only the columns that backend `JdbcAuditLog` actually inserts.

Granted: `insert (actor_user_id, action, entity_type, entity_id, before_json, after_json)`.
- `actor_user_id` stays insertable on purpose. The app supplies it from the logged-in user (`JdbcAuditLog` binds `principal.userId()`), and it has no database default.
- Not granted: `id` (default `gen_random_uuid()`), `tenant_id` (default `platform.current_tenant()`, still checked by `p_audit_event_insert`), `at` (default `now()`). A column default runs without any INSERT privilege on that column, so the app's insert statement, which omits these three, keeps working.
- `SELECT` is unchanged (table level), so reads and `RETURNING` still work. UPDATE, DELETE and TRUNCATE are still refused by privilege, by the lack of a policy, and by the triggers.
- Residual limit: `actor_user_id` is not checked against the logged-in user by the database; it is still app-supplied. The `at` column is now server time only.

Effects on existing tests (for QA, not changed here):
- An insert that names `tenant_id` or `at` as `sahaj_app` now fails with SQLSTATE 42501 `permission denied for table audit_event` (column privilege), before row level security is reached. `AuditTrailIT.tenantBCannotWriteAnAuditRowForTenantA` names `tenant_id` and asserts the message contains "row-level security"; it will now see "permission denied". Either assert the new message there, or keep the cross-tenant forgery check by inserting through a statement that omits `tenant_id` while bound to another tenant.
- `has_table_privilege('sahaj_app','platform.audit_event','INSERT')` is now false (it is true only for a table-level grant). `RowLevelSecurityCatalogIT.sahajAppHasExactlyTheDocumentedPrivilegesOnEachPlatformTable` expects `SELECT, INSERT` for audit_event; change it to `SELECT` at table level and add the column checks below.

## Decision: INSERT only and RETURNING

An `INSERT` needs only the INSERT privilege. A statement that uses `RETURNING`, `ON CONFLICT`, or a JPA `merge` reads the table back and then needs SELECT, and without it fails with "permission denied for table sales_enquiry".

Decision: keep **INSERT only**, no SELECT (not even on `id`), because the table holds visitor names and mobiles and the app has no reason to read them. So the Java code must:
1. Generate the UUID in the application and insert with `persist` (not `merge`). With Spring Data JPA that means a `@GeneratedValue(strategy = GenerationType.UUID)` id, or an entity that implements `Persistable` and returns `isNew() = true`.
2. Not use `RETURNING`, `ON CONFLICT` or `save()` on an entity with a preset id and no version column (that triggers a `select` first).
3. Return the id it generated itself in the `201 {id}` response.

If java-developer finds this too awkward, the fallback is a new migration `grant select (id) on platform.sales_enquiry to sahaj_app;` (column level, exposes only random ids). Ask the db-engineer; do not edit the existing file.

## Decisions and open points

1. **audit_event is not partitioned.** ADR-002 D7 asked for monthly partitions on its two old audit tables; SAH-1 replaces them with one simple table. Partitioning needs the partition key in the primary key and a job that creates future partitions, and volume in phase 1 is small. The cost is a table rewrite later. If the tech lead wants partitioning from day one, tell me before this is merged and I will change the file (not yet run anywhere).
2. **No value list on `tenant.plan` and `tenant.status`.** Neither the stories nor the contract name the values. Defaults are `FREE` and `ACTIVE`. A check can be added later without a data change once SAH-3 and SAH-4 fix them.
3. **`app_user.mobile` is `char(10)`** as the story says. Hibernate `ddl-auto: validate` expects `@JdbcTypeCode(SqlTypes.CHAR)` on that field, or it reports a type mismatch.
4. **Cross-tenant lookups are not possible for `sahaj_app`.** Under FORCE row level security even the function owner is filtered. SAH-3 will need to find a user's memberships and a clinic by slug before any tenant is bound (the contract has `CHOOSE_CLINIC`), and SAH-4 needs a slug availability check across tenants. None of these can read `membership` or `tenant` today. Options for the tech lead: a second SELECT policy keyed on another bound setting (for example `app.user_id`, set after the one-time code is verified), or a narrow policy for slug lookup. Needs an ADR decision before SAH-3; nothing is built here. The unique index on `tenant.slug` still guarantees no duplicate.
5. **Extra columns** beyond the story lists: `updated_at` and `version` on tenant, app_user and membership (optimistic locking, per rules). `created_by` and `updated_by` are left out to keep to the story; add if wanted.
6. Column `audit_event.at` uses a keyword-like name. It is a non-reserved word in PostgreSQL and works unquoted.

## Backward compatibility

First migrations for the schema; nothing earlier exists. All additive, no drops or renames. The previous app version does not use the database.

## Verification (to run when Flyway is on the classpath)

As `sahaj_app` against `sahaj_test`:
- With no `app.tenant_id` set, `select count(*)` on tenant, membership and audit_event returns 0, and an insert fails.
- Tenant A cannot see or insert a row for tenant B on membership and audit_event.
- `update` and `delete` on audit_event fail (privilege).
- `insert ... returning id` into sales_enquiry fails; a plain insert succeeds.
- `sahaj_app` has no privilege of any kind on `platform.flyway_schema_history` (`has_table_privilege` false for all seven, and `select count(*) from platform.flyway_schema_history` fails with 42501). The Flyway run itself succeeded (the context starts).
- On `platform.audit_event`, `has_column_privilege('sahaj_app', ..., 'INSERT')` is true for exactly `actor_user_id, action, entity_type, entity_id, before_json, after_json`, and false for `id, tenant_id, at`. `has_table_privilege(..., 'INSERT')` is false; SELECT is true.
- `insert into platform.audit_event (action, entity_type, at) values (...)` as `sahaj_app` fails with 42501 (permission denied).
- The normal audit insert from `JdbcAuditLog` (actor, action, entity type, entity id, before, after) succeeds, and the stored `at` is within a second of `now()` and the stored `tenant_id` is the bound tenant.
- `update`, `delete`, `truncate` on audit_event still fail.
