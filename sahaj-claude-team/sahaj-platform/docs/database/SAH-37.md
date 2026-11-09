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
| `revision` | bigint | no | | `ck_card_setup_draft_revision`: `>= 1`; an opaque clock-based token (section 7), about 1.79e15 today, well inside bigint; the editor's `draftRevision` |
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
- `revision` is an opaque clock-based token (DEC-028 addendum, option A, no schema change): `max(microseconds since 1970, previous revision + 1)`. Today it is about 1.79e15, which fits `bigint` and the check `revision >= 1`. It never repeats for a clinic: the clock moves forward and the plus one covers two saves in the same microsecond or a clock step back. Because the value does not restart after Discard or Publish (the row is gone), an editor holding the revision of a discarded draft can never match a draft recreated by someone else. The clinic advisory lock makes writers take turns, so reading the previous revision and writing the new one are not interleaved. Clients treat the number as opaque (no arithmetic, no display).
- Fix pass (java-developer): a Save draft whose setup equals the stored draft writes nothing and keeps the revision (`saved_at` and `saved_by` unchanged). The error mapping above is unchanged: 23505 on the version key or the draft key still maps to 409 `card-setup-changed`.
- The column comment on `ipd.card_setup_draft.revision` in `V2026_12_04_1000` ("Bumped by every Save draft; ...") is slightly stale: the value is now a clock-based token and an equal save does not bump it. Accepted: a new migration is not worth a comment change, and the applied migration is not edited. This note is the correct description.
- `jsonb` stores keys sorted and without duplicates, so Java must compare the canonical form (ADR-015 D3), never the stored text.
- Existing integration tests that list the `ipd` tables or their grants (for example a catalogue of tenant tables) need these two names added; that is test code, owned by the java-developer and qa-engineer.

## 8. Query plans (SAH-37 ADR-015 "Data needed" item 5)

How these were produced: **measured.** QA ran `CardSetupPlansIT` as `sahaj_app` with `app.tenant_id` set on `sahaj_test`: one clinic with 300 admitted admissions, 3,979 vitals rows (5 to 20 per admission), 2 published versions and 1 draft, after `analyze`. The full `explain (analyze, buffers)` output is in `docs/test-plans/SAH-37.md`, section "Query plans (docs/database/SAH-37.md section 8), real output". The statements below are the ones that were run. My earlier "expected plan" shapes are replaced by the measured results; where the planner chose differently from my guess, the measured plan is stated.

Measured execution times (default settings; the plans did not change with `enable_seqscan = off`):

| Query | Execution time | Sequential scan |
|---|---|---|
| R7 live setup (latest version) | 0.092 ms | no |
| Versions list, limit 50 | 0.115 ms | no |
| Draft read | 0.177 ms | no |
| R4 latest readings of the admitted (300 loops) | 3.1 ms (3.096) | none on vitals |
| R1 admitted rows with placement | 4.4 ms (4.415) | none on admission |

Setup common to all: `begin; set local app.tenant_id = '<tenant uuid>'; set local role sahaj_app;` (RLS adds `tenant_id = platform.current_tenant()` to every statement; `current_tenant()` is `stable`, so the planner folds it into the index condition).

### R7 · live card setup (latest version)

```sql
explain (analyze, buffers)
select version, setup, is_standard, changed_parts, published_by, published_at
from ipd.card_setup_version
order by version desc
limit 1;
```
Measured (0.092 ms): index scan backward on the unique version index, no sort.
```
Limit
  -> Index Scan Backward using uq_card_setup_version_tenant_version on card_setup_version
       Index Cond: (tenant_id = platform.current_tenant())
```
One probe of the unique index, one heap fetch, no sort: `(tenant_id, version)` read backwards gives the highest version first. Cost independent of the number of versions (a clinic has a few dozen at most). The draft read (`select ... from ipd.card_setup_draft`) measured 0.177 ms with `Index Scan using card_setup_draft_pkey`, one row. The versions list (`limit 50`) measured 0.115 ms: a Bitmap Index Scan on the same unique index, then a Sort of 2 rows.

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
Measured (3.1 ms, 300 admitted admissions): a Bitmap Heap Scan on `admission` through `ux_admission_tenant_patient_admitted` (300 rows), then for each admission one Index Scan using `ix_vitals_tenant_admission_recorded` (loops = 300, Incremental Sort, Limit 1). No sequential scan on `vitals`. The planner chose the bitmap scan on that admission index, not the `ix_admission_tenant_status_admitted_at` scan I guessed; both are tenant-scoped index access of the same order of cost.
```
Nested Loop
  -> Bitmap Heap Scan on admission a (via ux_admission_tenant_patient_admitted, 300 rows)
  -> Limit
       -> Incremental Sort (Sort Key: recorded_at DESC, id DESC; Presorted Key: recorded_at)
            -> Index Scan using ix_vitals_tenant_admission_recorded on vitals v   (loops=300)
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
Measured (4.4 ms, 300 rows): Hash Join of `admission`, `bed`, `ward` and `room`, with Bitmap Index Scans on `ux_admission_tenant_patient_admitted`, `ix_bed_tenant_ward`, `uq_ward_tenant_id` and `uq_room_tenant_id`; the deposit sum is a per-row Index Scan on `ix_deposit_tenant_admission` (loops = 300); then a Sort by `admitted_at`. No sequential scan on `admission`. This differs from my earlier guess (an ordered index scan with no sort, joins on primary keys): at 300 rows the planner prefers hash joins and a Sort, which is fine. `reason` (`text`, 1 to 200 characters, `ck_admission_reason`) lives in the admission heap row that R1 already fetches, so it adds no index, no probe and no join.

## 9. schema_version of the setup document (audit gap G-5)

Migration (forward-only, additive): `V2026_12_07_1000__ipd_card_setup_schema_version.sql`. `.claude/rules/postgresql.md` requires a `schema_version` for jsonb documents; the two tables of V2026_12_04_1000 had none.

| Table | Column | Type | Null | Default | Rule |
|---|---|---|---|---|---|
| `ipd.card_setup_version` | `schema_version` | smallint | no | `1` | `ck_card_setup_version_schema_version`: `>= 1` |
| `ipd.card_setup_draft` | `schema_version` | smallint | no | `1` | `ck_card_setup_draft_schema_version`: `>= 1` |

- **What version 1 means.** The shape of `setup` written by SAH-37: `layout`, `cardsPerRow`, `sections`, `fields`, `quickActions`, as validated by `CardSetupValidator`. Rows that exist before the migration get 1 (the constant default is stored in the catalogue, no table rewrite). This is not the same number as `version` (the publish counter, 2, 3, ...).
- **Grants** (additive; nothing revoked): `ipd.card_setup_version` insert list gains `schema_version`; `ipd.card_setup_draft` insert and update lists gain `schema_version`. The version table has no update grant, and its before update/delete trigger still refuses any change, so `schema_version` of a published version never changes. The no-truncate triggers and all RLS policies are untouched; adding a column fires no trigger.
- **Write path (java-developer).** Name `schema_version` explicitly (value 1) in the version insert and in the draft insert and update column lists. The database default of 1 only protects a writer that does not name the column.
- **Read path, unknown version.** The code knows version 1 only. A stored row (published version) with a `schema_version` higher than the code knows is not interpreted: the reader answers the Sahaj standard setup with `fallback: true`, exactly as for an unreadable row (ADR-015 repair-on-read). The row is not changed or deleted. This lets a rolled-back app version stay safe after a newer app has written a newer shape. A draft with an unknown higher version is treated the same way (not interpreted); that choice is the java-developer's, as the contract defines no separate draft behaviour.
- **Adding a version 2 later.** Write a new reader that converts version 1 to the new shape in memory, and write new rows with 2. Old rows are never rewritten (versions are append-only). The check `>= 1` has no upper bound on purpose, so a new shape needs no migration.
- **Backward compatibility.** The previous app version does not name the column; the default 1 applies and it runs unchanged. Rows per tenant and retention: unchanged (section 5); the column adds 2 bytes per row.
- **Table comments.** The existing table comments are left as they are (replacing them would change existing metadata); the meaning of the column is in the column comments.

## 10. Verification

See the handoff: `node scripts/validate.mjs db` (file-name rule, "tenant_id tables have row level security" rule, then the backend tests clean `sahaj_test` and apply every migration from scratch).
