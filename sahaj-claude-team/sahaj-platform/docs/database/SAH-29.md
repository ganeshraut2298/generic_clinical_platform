# SAH-29 · Database design: IPD packages, read-only slice

Migration (forward-only, additive; no earlier migration is edited, nothing dropped, renamed, retyped or backfilled): `V2026_12_05_1000__ipd_packages.sql` (latest before it: `V2026_12_04_1000__ipd_card_setup.sql`; the SAH-34 migration is planned as `V2026_12_06_1000`). Spec: `docs/architecture/SAH-29-ipd-packages.md` section 3. Decision: DEC-030 (read-only slice; create and attach are future work, OQ-29-1 and OQ-29-2). Mockup: Clinic setup → Admission and deposits, panel "Packages" (`cfgIpAdm`, `S.ipd.pkgs`); the running bill of the patient sheet (`openIp`, `ipLines`).

One new tenant table `ipd.package`, three new nullable columns on `ipd.admission`, one guard function with two triggers. No existing column, index, grant, policy, function or trigger changes.

## 1. ipd.package

| Column | Type | Null | Default | Rule |
|---|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` | primary key |
| `tenant_id` | uuid | no | `platform.current_tenant()` | `fk_package_tenant` → `platform.tenant (id)` |
| `name` | text | no | | `ck_package_name`: `char_length(name) between 1 and 80` and `btrim(name) = name` |
| `price_paise` | bigint | no | | `ck_package_price`: 0 to 100000000 and `% 100 = 0` (whole rupees, "₹45,000") |
| `included_days` | smallint | no | | `ck_package_included_days`: 0 to 60 (0 = day-care) |
| `active` | boolean | no | `true` | the list shows active rows only (P2); rows are never deleted |
| `created_at` | timestamptz | no | `clock_timestamp()` | list order; `clock_timestamp()` so rows added in one transaction keep their insert order |
| `created_by` | uuid | yes | | user id; null while packages are set through the database (no app user) |
| `version` | bigint | no | `0` | for the later write endpoints |

`updated_at` and `updated_by` are not added: nothing updates a row in this slice (they come with OQ-29-1). The table seeds nothing (OQ-29-3).

Keys and indexes:

| Name | Columns | Serves |
|---|---|---|
| primary key `package_pkey` | `id` | row identity |
| `uq_package_tenant_id` (unique) | `(tenant_id, id)` | target of a later composite FK (`package_id` on the admission, OQ-29-2); no FK uses it yet |
| `ux_package_tenant_lower_name` (unique) | `(tenant_id, lower(name))` | one package name per clinic ignoring case; backstop for a later "name already used" 409. Not used by the read |
| `ix_package_tenant_created_at` | `(tenant_id, created_at, id)` | the Packages list: `where active order by created_at, id` for one clinic. `active` is a filter on the few rows read (a clinic has a handful of packages) |

No other index. No composite FK is needed: the table points at no other tenant table (`tenant-fk-survey.md`); the key to `platform.tenant` is to a global table, which is allowed.

Trigger `trg_package_no_truncate` (before truncate, shared `platform.deny_audit_mutation()`, SQLSTATE 42501 for every role, the owner included). The message reads "ipd.package is append-only (TRUNCATE refused)"; the table is not append-only (the owner may update it), the message is the shared one.

## 2. ipd.admission (three nullable columns, the package copy)

| Column | Type | Null | Default | Rule |
|---|---|---|---|---|
| `package_name` | text | yes | none | `ck_admission_package_name`: 1 to 80 characters |
| `package_price_paise` | bigint | yes | none | `ck_admission_package_price`: 0 to 100000000 and `% 100 = 0` |
| `package_days` | smallint | yes | none | `ck_admission_package_days`: 0 to 60 |

- `ck_admission_package_all_or_none`: the three are all null or all not null.
- A copy, not a link: no reference to `ipd.package` (AC2). Changing or switching off a package row never changes an attached bill. `package_id` is left to OQ-29-2.
- Existing rows read as "no package" (all null). `add column` with no default is a catalogue-only change in PostgreSQL 16 (no table rewrite, no long lock beyond a brief `ACCESS EXCLUSIVE` for the DDL). The `add constraint` check scans the table once; the check holds for every existing row (all null), and the table is small (under 100 IPD hospitals).
- Grants: unchanged. `sahaj_app` has table-level SELECT and column-level INSERT / UPDATE lists that do not name these columns (V2026_11_12). The app can therefore read the copy and can never write it: an insert or update naming a package column fails with SQLSTATE 42501.

### Set-once guard

Function `ipd.guard_admission_package()` (SECURITY INVOKER, `search_path = pg_catalog`, no table access, execute revoked from public and `sahaj_app`) with two triggers, both `before ... for each row` and firing for every role including the owner:

| Trigger | Fires | Refuses (42501) |
|---|---|---|
| `trg_admission_package_set_once_insert` | on insert, only when a package column is not null | `status = 'DISCHARGED'` ("a package cannot be set on a discharged admission") |
| `trg_admission_package_set_once` | on update of a package column, only when the three-column value changes | a change or a clear when the old copy was set ("admission package is set once and cannot be changed or cleared"); setting it when the new `status = 'DISCHARGED'` |

Setting the same values again is not a change: nothing fires. A normal discharge by the app (status, discharged_at, version) does not name a package column, so the trigger never fires on it. Two triggers are used because a `WHEN` clause cannot see the operation and an insert trigger cannot read `OLD`.

## 3. Tenant scope, RLS and grants

- `ipd.package`: `tenant_id` not null; ENABLE and FORCE row level security. One policy `p_package_select` `for select to sahaj_app using (tenant_id = platform.current_tenant())`. There is no insert, update or delete policy, so with FORCE the table is read-only for everyone who is subject to RLS.
- Grants: `revoke all` from `sahaj_app` and from public, then `grant select ... to sahaj_app`. No insert, update, delete, truncate, references or trigger.
- Another clinic's packages are invisible to the app (RLS). The admission copy follows the existing `p_admission_tenant` policy.

## 4. How a hand-set package is inserted (dev and test only)

The app cannot (no grant, no policy). `sahaj_migrator` owns the tables but is subject to FORCE RLS and no policy names it, so a plain insert as the migrator inserts nothing: it fails the (absent) policy check with a row level security error. The project pattern (as `insertSignoffAt` in `DischargeSupport`) is a test helper that connects as the migrator and, in one transaction:

```sql
begin;
set local lock_timeout = '30s';
alter table ipd.package no force row level security;
insert into ipd.package (tenant_id, name, price_paise, included_days)
values ('<tenant uuid>', 'Knee replacement', 4500000, 5);
alter table ipd.package force row level security;
commit;   -- roll back on any error, so FORCE is never left off
```

To attach the copy to an ADMITTED admission, the same way on `ipd.admission` (the table owner is the only role that may write the package columns):

```sql
alter table ipd.admission no force row level security;
update ipd.admission
   set package_name = 'Knee replacement', package_price_paise = 4500000, package_days = 5
 where tenant_id = '<tenant uuid>' and id = '<admission uuid>' and status = 'ADMITTED';
alter table ipd.admission force row level security;
```

Rules the helper must respect: all three columns together; once set, a change fails (42501), so set it before the test reads the bill, and choose a different admission to test another package; setting on a DISCHARGED admission fails (42501). `no force` takes a brief `ACCESS EXCLUSIVE` lock on the table, so run the helper outside a test that holds a transaction on the same table, and make sure `force` is restored in a `finally`. In `sahaj_dev` a developer does the same through the owner login. In the shared `sahaj_test` such a helper should insert rows in its own tenant and may leave them (packages are never deleted by the app); the unique name index is per tenant, so use a unique name per test run or a fresh tenant.

Not for real clinics (DEC-030 P1): no packages in a live database until create and attach are built.

## 5. Queries for the java-developer

All run as `sahaj_app` with `app.tenant_id` bound (RLS adds the tenant filter).

Package list (GET /packages, active rows only, not paged):
```sql
select id, name, price_paise, included_days
from ipd.package
where active
order by created_at, id;
```
The admission copy rides on the existing admission reads: add `package_name, package_price_paise, package_days` to the admission select used by the sheet, the Admitted list, the board, the tiles and discharge. A null `package_name` means no package (the check guarantees the other two are null too). JPA: map the three as nullable columns, `insertable = false, updatable = false`, so Hibernate never names them in an insert or update (a named column would fail with 42501 for `sahaj_app`).

## 6. Expected rows, size, retention, growth

- `ipd.package`: a handful per IPD clinic (the mockup shows three). Per tenant per year: 0 to 10 rows. 100 IPD hospitals: under 1,000 rows for the whole platform for years. OPD-only clinics: none. Row about 150 bytes. No partitioning (far below 50 million).
- `ipd.admission` package columns: null for most rows today. A package admission adds about 30 bytes. Admission growth is unchanged.
- Retention: package rows are never deleted (`active = false` retires one; truncate refused). The copy on the admission lives as long as the admission (clinical and billing: never deleted). No patient data in `ipd.package`; the copy holds a package name and price, not patient data.

## 7. Backward compatibility

Additive. The previous app version does not know the table or the columns, never names them, and runs unchanged against the new schema (its admission inserts and updates use explicit column lists, and `ddl-auto=validate` ignores extra columns). Rolling back the app leaves one unused table and three unused null columns. The new app needs the migration first (Flyway runs at start-up as `sahaj_migrator`). One side effect for an old app version: none, because the set-once triggers fire only when a package column is given or changed.

## 8. Error mapping

| Database signal | Cause | Map to |
|---|---|---|
| 42501 on a write naming a package column or `ipd.package` as `sahaj_app` | code bug (the app has no write path in this slice) | 500 |
| 42501 "admission package is set once ..." / "a package cannot be set on a discharged admission" | hand edit in the database | not reachable from the app |
| 23514 on `ck_package_*` or `ck_admission_package_*` | bad hand-set data | not reachable from the app |
| 23505 on `ux_package_tenant_lower_name` | duplicate name, hand edit | not reachable from the app; becomes a 409 only when OQ-29-1 is built |

## 9. Expected query plans

How these were produced: **expected plans, not run** (no `psql` for this role; the Maven/Flyway route only applies migrations). Statements are exact so qa-engineer or the java-developer can run them as `sahaj_app` with `begin; set local app.tenant_id = '<uuid>'; set local role sahaj_app;` against a seeded `sahaj_test` and replace this section with the real output. With a handful of rows the planner may choose a sequential scan plus a sort for the package list; the shape below holds once `analyze` has run on realistic data or with `set enable_seqscan = off`.

### Package list

```sql
explain (analyze, buffers)
select id, name, price_paise, included_days
from ipd.package where active order by created_at, id;
```
Expected:
```
Index Scan using ix_package_tenant_created_at on package
  Index Cond: (tenant_id = platform.current_tenant())
  Filter: active
```
One range probe on the clinic's prefix, rows already in `(created_at, id)` order, no sort. At most a few dozen rows, so the cost is trivial whichever plan is chosen. Writing it as a partial index on `active` was considered and rejected: not worth a second index for a table this small.

### Bill read (the admission row with its package copy)

```sql
explain (analyze, buffers)
select id, status, admission_type, payer, bed_id, admitted_at, discharged_at,
       package_name, package_price_paise, package_days
from ipd.admission where id = '<admission uuid>';
```
Expected:
```
Index Scan using admission_pkey on admission
  Index Cond: (id = '<admission uuid>')
  Filter: (tenant_id = platform.current_tenant())
```
(or `uq_admission_tenant_id` on `(tenant_id, id)`; either is one probe.) The three new columns live in the same heap tuple, so the bill read, the Admitted list (`ix_admission_tenant_status_admitted_at`, `status = 'ADMITTED'`, already ordered by `admitted_at`) and the board add no index, no probe and no join: the plans are unchanged from SAH-25 and SAH-26. The package branch of `RunningBill` reads no table of its own (it never reads `ipd.bed_transfer`, so it saves that read for a package admission). The extra charge lines still come from `ix_admission_charge_tenant_admission` on `(tenant_id, admission_id)` exactly as before.

## 10. Verification

See the handoff: `node scripts/validate.mjs db` (file-name rule, "tenant_id tables have row level security" rule, then the backend tests clean `sahaj_test` and apply every migration from scratch), run after the backend lock is free.
