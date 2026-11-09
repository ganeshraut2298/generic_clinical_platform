# SAH-37 · Database design: IPD patient card setup

Migration (forward-only, additive; no earlier migration is edited, nothing dropped, renamed or rewritten): `V2026_12_04_1000__ipd_card_setup.sql` (latest before it: `V2026_12_03_1000__platform_staff_pin_lock_by_colleagues_proof.sql`). Decision record: ADR-015 (D1, D5, D9, D10, "Data needed"); design note `docs/architecture/SAH-37-patient-card-setup.md`; open points DEC-028 (defaults applied; none changes the stored shape). Mockup screens: Clinic setup → Patient card (screen-map row 29: `cfgIpCard`, `pcBar`, "Published versions"); IPD → Admitted (`pcCard`, "Customize card").

Two new tenant tables in schema `ipd`. No existing table, index, grant, function or trigger changes. No patient data in either table.

## 1. ipd.card_setup_version (append-only)

One row per publish: version 2, 3, ... of a clinic. Version 1 is the Sahaj standard setup built in code (`CardSetupDefaults.standard()`); it is never stored, so a clinic that never published has no row. The live setup is the row with the highest `version`.

| Column | Type | Null | Default | Rule |
|---|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` | primary key |
| `tenant_id` | uuid | no | `platform.current_tenant()` | `fk_card_setup_version_tenant` → `platform.tenant (id)` |
| `version` | integer | no | | `ck_card_setup_version_version`: `>= 2` |
| `setup` | jsonb | no | | `ck_card_setup_version_setup`: `jsonb_typeof(setup) = 'object'` and `octet_length(setup::text) <= 16384` |
| `is_standard` | boolean | no | | the published setup equals the Sahaj standard setup |
| `changed_parts` | text[] | no | | `ck_card_setup_version_changed_parts`: `changed_parts <@ array['layout','sections','fields','quickActions']` (an unknown value, or a null element, is refused; the empty array passes) |
| `published_by` | uuid | no | | signed-in user id (IPD actor convention, no key); name resolved at read time through `ActorDirectoryPort` |
| `published_at` | timestamptz | no | `now()` | database clock |

Keys and indexes:
| Name | Columns | Serves |
|---|---|---|
| primary key `card_setup_version_pkey` | `id` | row identity only |
| `uq_card_setup_version_tenant_version` (unique) | `(tenant_id, version)` | R7 "live setup of this clinic" (`order by version desc limit 1`, backward index scan, one probe); "next version = max + 1" under the clinic advisory lock; the versions list, newest first, `limit 50`; the backstop for a double publish (23505 → 409 `card-setup-changed`) |

No other index. No composite foreign key: the table points at no other tenant table (`tenant-fk-survey.md`); the key to `platform.tenant` is to a global table, which is allowed.

Triggers (SQLSTATE 42501, every role, the owner included, message "platform.audit_event is append-only (UPDATE refused)" from the shared `platform.deny_audit_mutation()`): `trg_card_setup_version_no_update_delete` (before update or delete, each row), `trg_card_setup_version_no_truncate` (before truncate).

## 2. ipd.card_setup_draft (one row per clinic)

| Column | Type | Null | Default | Rule |
|---|---|---|---|---|
| `tenant_id` | uuid | no | `platform.current_tenant()` | primary key `card_setup_draft_pkey` (at most one draft per clinic); `fk_card_setup_draft_tenant` → `platform.tenant (id)` |
| `setup` | jsonb | no | | `ck_card_setup_draft_setup`: same check as the version table |
| `base_version` | integer | no | | `ck_card_setup_draft_base_version`: `>= 1` (1 = the standard setup, which has no row, so no key to the version table) |
| `revision` | bigint | no | | `ck_card_setup_draft_revision`: `>= 1`; bumped by every Save draft; the editor's `draftRevision` |
| `saved_by` | uuid | no | | user id |
| `saved_at` | timestamptz | no | `now()` | the app writes `now()` again on each replace (granted in the update list) |

Indexes: the primary key `(tenant_id)` is the only access path (read, replace, delete the clinic's single row). No other index.

Trigger: `trg_card_setup_draft_no_truncate` (before truncate, `platform.deny_audit_mutation()`, 42501). Update and delete are allowed (Save draft replaces, Discard and Publish delete).

## 3. Tenant scope and RLS

Both tables: `tenant_id` not null; ENABLE and FORCE row level security; every policy is `to sahaj_app` and `tenant_id = platform.current_tenant()` (the project pattern; with no tenant bound nothing is visible and an insert fails).
- `ipd.card_setup_version`: `p_card_setup_version_select`, `p_card_setup_version_insert` (with check). No update or delete policy.
- `ipd.card_setup_draft`: `p_card_setup_draft_select`, `_insert` (with check), `_update` (using and with check, so a row cannot be moved to another clinic), `_delete`.

Another clinic's setup, draft and versions are invisible and cannot be written. A write with a `tenant_id` of another clinic fails the check (42501 / RLS violation); the app never names `tenant_id` (column default).

## 4. Grants (`sahaj_app`)

`revoke all` from `sahaj_app` and from public first (schema default privileges give the app everything), then:

| Table | Grant |
|---|---|
| `ipd.card_setup_version` | `select`; `insert (version, setup, is_standard, changed_parts, published_by)`. No update, delete, truncate, references, trigger. |
| `ipd.card_setup_draft` | `select`; `insert (setup, base_version, revision, saved_by)`; `update (setup, base_version, revision, saved_by, saved_at)`; `delete`. No truncate, references, trigger. |

Not grantable to the app (42501 if named in an insert): `id`, `tenant_id`, `published_at` on the version table; `tenant_id` on the draft table is insert-defaulted and not updatable; `saved_at` takes its default on insert.

## 5. Expected rows, size, retention, growth

- Version: a clinic publishes a few times a year. Per tenant per year: about 2 to 10 rows; at most a few dozen over the life of the account. 100 IPD hospitals: under 5,000 rows for the whole platform for years. OPD-only clinics: no rows. Each row about 3 KB (jsonb, 16 KB hard cap). No partitioning (far below the 50 million rows rule).
- Draft: 0 or 1 row per clinic; about 3 KB.
- Retention: versions are kept for the life of the clinic account and never deleted (configuration history, like `platform.audit_event`; the append-only triggers enforce it). Closing a subscription does not delete them. The draft lives until Publish or Discard. The setup holds no patient data, so no patient-data retention rule applies. Owner-typed labels are inside `setup`; they are not logged.
- Clinical and billing "never hard-delete" rule: not applicable to the draft (it is configuration, not clinical or billing data); the version table is append-only anyway.

## 6. Backward compatibility

Additive. The previous app version does not know the two tables and runs unchanged against the new schema. Rolling back the app leaves two unused tables. The new app needs the migration first (Flyway runs at start-up as `sahaj_migrator`).

## 7. Error mapping for the java-developer

| Database signal | Cause | Map to |
|---|---|---|
| 23505 on `uq_card_setup_version_tenant_version` | double publish / lost the version race (the clinic advisory lock should prevent it) | 409 `card-setup-changed` ("The card setup was changed by someone else. Reload to see it."), never 500 |
| 23505 on `card_setup_draft_pkey` | two first-time Save drafts raced (the lock should prevent it) | 409 `card-setup-changed` |
| 23514 on `ck_card_setup_*_setup` | setup is not an object or over 16 KB: the validator should have returned 400 first | code bug → 500 (do not map to 409) |
| 23514 on `ck_card_setup_version_changed_parts`, `ck_card_setup_version_version`, `ck_card_setup_draft_base_version`, `ck_card_setup_draft_revision` | code bug | 500 |
| 42501 `append-only (UPDATE refused)` / `(DELETE refused)` / `(TRUNCATE refused)` | code bug | 500 |

The 409 for a stale `baseVersion`, a stale `draftRevision` or a draft that is gone is decided in Java under the clinic advisory lock (ADR-015 D5); the database only backs the version number. The tokens are not database constraints.

Notes:
- `revision` restarts at 1 after Discard or Publish (the row is gone). An editor holding `draftRevision = 1` from a draft that was discarded and recreated at revision 1 by someone else would not be refused by the revision test alone. Java may close this by keeping `base_version` in the comparison (a recreated draft usually has a different base) or by accepting the risk (two owners in the same minute; the last Save draft still goes through the lock). Not a database matter; stated so the java-developer decides on purpose.
- `jsonb` stores keys sorted and without duplicates, so Java must compare the canonical form (ADR-015 D3), never the stored text.
- Existing integration tests that list the `ipd` tables or their grants (for example a catalogue of tenant tables) need these two names added; that is test code, owned by the java-developer and qa-engineer.

## 8. Query plans (SAH-37 ADR-015 "Data needed" item 5)

How these were produced: **expected plans, not run.** No role available to this task allows running `EXPLAIN` on `sahaj_test` (no `psql`; the Maven/Flyway route only applies migrations). The statements below are exact so qa-engineer or the java-developer can run them as `sahaj_app` with `set local app.tenant_id = '<uuid>'` against a seeded `sahaj_test` (300 admitted admissions, 5 to 20 vitals sets each) and replace this section with the real output. Both new tables are tiny, so the planner can pick a plain scan on an empty or one-row table; the plan shapes below hold once `analyze` has run on realistic data or with `set enable_seqscan = off` for the small tables.

Setup common to all: `begin; set local app.tenant_id = '<tenant uuid>'; set local role sahaj_app;` (RLS adds `tenant_id = platform.current_tenant()` to every statement; `current_tenant()` is `stable`, so the planner folds it into the index condition).

### R7 · live card setup (latest version)

```sql
explain (analyze, buffers)
select version, setup, is_standard, changed_parts, published_by, published_at
from ipd.card_setup_version
order by version desc
limit 1;
```
Expected:
```
Limit
  -> Index Scan Backward using uq_card_setup_version_tenant_version on card_setup_version
       Index Cond: (tenant_id = platform.current_tenant())
```
One probe of the unique index, one heap fetch, no sort: `(tenant_id, version)` read backwards gives the highest version first. Cost independent of the number of versions (a clinic has a few dozen at most). The draft read is `select ... from ipd.card_setup_draft` = `Index Scan using card_setup_draft_pkey`, `Index Cond: (tenant_id = platform.current_tenant())`, one row.

### ADR-013 R4 with the five readings and `recorded_by` added

```sql
explain (analyze, buffers)
select a.id as admission_id,
       v.ews_score, v.ews_risk, v.recorded_at, v.recorded_by,
       v.bp_systolic, v.bp_diastolic, v.pulse, v.spo2, v.temp_c, v.rr
from ipd.admission a
cross join lateral (
  select ews_score, ews_risk, recorded_at, recorded_by,
         bp_systolic, bp_diastolic, pulse, spo2, temp_c, rr
  from ipd.vitals v
  where v.tenant_id = a.tenant_id and v.admission_id = a.id
  order by v.recorded_at desc, v.id desc
  limit 1
) v
where a.status = 'ADMITTED';
```
Expected:
```
Nested Loop
  -> Index Scan using ix_admission_tenant_status_admitted_at on admission a
       Index Cond: ((tenant_id = platform.current_tenant()) AND (status = 'ADMITTED'))
  -> Limit
       -> Incremental Sort (Sort Key: recorded_at DESC, id DESC; Presorted Key: recorded_at)
            -> Index Scan using ix_vitals_tenant_admission_recorded on vitals v
                 Index Cond: ((tenant_id = a.tenant_id) AND (admission_id = a.id))
```
One index probe per admitted admission (loops = number of admissions; the Limit stops after the newest row and any rows with the same `recorded_at`). Adding the five readings and `recorded_by` does not change the plan: the index `(tenant_id, admission_id, recorded_at desc)` is not covering today either (it carries no `ews_score`, `ews_risk`), so R4 already reads the heap row of the newest set; the extra columns are in that same heap tuple (about 30 more bytes). If the planner shows a full `Sort` instead of `Incremental Sort` (small row counts), it is still one probe per admission. ADR-013's conditional include-index stays not needed (ADR-015 D10); if it is ever added it would have to include the six new columns, which is the reason not to.

### R1 with `reason` added

```sql
explain (analyze, buffers)
select a.id, a.reason, a.admitted_at, a.bed_id /* ... the existing R1 columns ... */
from ipd.admission a
join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id
join ipd.ward w on w.tenant_id = b.tenant_id and w.id = b.ward_id
left join ipd.room r on r.tenant_id = b.tenant_id and r.id = b.room_id
where a.status = 'ADMITTED'
order by a.admitted_at;
```
Expected: unchanged from today: `Index Scan using ix_admission_tenant_status_admitted_at on admission a` with `Index Cond: ((tenant_id = platform.current_tenant()) AND (status = 'ADMITTED'))` (already ordered by `admitted_at`, no sort), then the bed, ward and room joins on their primary keys (nested loop or hash on the small ward and room tables). `reason` (`text`, 1 to 200 characters, `ck_admission_reason`) lives in the admission heap row that R1 already fetches for the other columns, so it adds no index, no probe and no join; at most a few bytes more per row.

## 9. Verification

See the handoff: `node scripts/validate.mjs db` (file-name rule, "tenant_id tables have row level security" rule, then the backend tests clean `sahaj_test` and apply every migration from scratch).
