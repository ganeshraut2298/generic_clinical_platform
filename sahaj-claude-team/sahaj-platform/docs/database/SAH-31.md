# SAH-31 · Database design: IPD medicine orders, safety checks and the pharmacist check

Migration (forward-only; no earlier migration is edited, nothing dropped or renamed): `V2026_11_28_1000__ipd_medicine_orders.sql` (latest before it: `V2026_11_27_1000`). Decision records: ADR-009 ("Data needed", D1 to D5, D10, D12), DEC-017 (personal staff PIN; pharmacist = OWNER + PHARMACIST). Mockup screens: EMR sheet (`sfOrdBlock`, `sfCheck`, `sfstop`) and IPD → Safety, panel "Pharmacist check" (`pendRow`, `sfver`, `sfrej`).

Three new tables, three new functions (one check helper, two guards), four triggers on the order table. `ipd.clinical_event`, `ipd.admission` and every other table are unchanged. **`platform.staff_pin` is NOT in this migration** (the solution-architect designs it; it will be a separate later migration). Until it exists the java-developer's port fails closed (verify answers 409 `pin-not-set`).

Demo content: the 14 medicines, their classes, high-alert flags, the 3 interactions and the 4 override and 4 reject reasons come from the mockup (`SF_DRUGS`, `SF_INTER`, `SF_OVR`, `sfrej`). They are **not clinically validated (OQ-31-1, blocking for go-live)**. A clinic cannot edit them; a change is a new migration.

## ipd.drug (global reference)
No `tenant_id`. `sahaj_app`: `select` only.

| Column | Type | Null | Default | Check |
|---|---|---|---|---|
| `key` | text | no | | primary key; `ck_drug_key`: `^[a-z0-9_]{1,20}$` |
| `name` | text | no | | `uq_drug_name` unique; `ck_drug_name`: 1 to 80 characters |
| `route` | text | no | | `ck_drug_route`: 1 to 20 characters (Oral, IV, SC, IV infusion) |
| `classes` | text[] | no | `'{}'` | `ck_drug_classes`: `classes <@ {cephalosporin, penicillin, sulfa, antiplatelet, nsaid, anticoag, insulin, potassium}` |
| `high_alert` | text | yes | | `ck_drug_high_alert`: null or 1 to 40 characters |
| `sort_order` | smallint | no | | `uq_drug_sort_order` unique; `ck_drug_sort_order` > 0 |

Seed (select order = mockup order): 1 `pcm` Tab. Paracetamol 650 mg · Oral · {} ; 2 `panto` Inj. Pantoprazole 40 mg · IV ; 3 `ondan` Inj. Ondansetron 4 mg · IV ; 4 `ns` IV Normal saline 500 ml · IV ; 5 `ceft` Inj. Ceftriaxone 1 g · IV · {cephalosporin} ; 6 `amox` Tab. Amoxicillin + clavulanate 625 mg · Oral · {penicillin} ; 7 `pip` Inj. Piperacillin + tazobactam 4.5 g · IV · {penicillin} ; 8 `cotri` Tab. Cotrimoxazole 960 mg · Oral · {sulfa} ; 9 `asp` Tab. Aspirin 75 mg · Oral · {antiplatelet} ; 10 `ibu` Tab. Ibuprofen 400 mg · Oral · {nsaid} ; 11 `met` Tab. Metformin 500 mg · Oral ; 12 `hep` Inj. Heparin 5,000 IU · SC · {anticoag} · Anticoagulant ; 13 `ins` Inj. Regular insulin, sliding scale · SC · {insulin} · Insulin ; 14 `kcl` Inj. Potassium chloride 20 mmol in 500 ml · IV infusion · {potassium} · Concentrated electrolyte.

## ipd.drug_interaction (global reference)
| Column | Type | Null | Check |
|---|---|---|---|
| `class_a`, `class_b` | text | no | primary key `pk_drug_interaction (class_a, class_b)`; `ck_drug_interaction_order`: `class_a < class_b` (a pair is stored once, matched in both directions); `ck_drug_interaction_classes`: both in the class list |
| `severity` | text | no | `MAJOR` or `MODERATE` |
| `text` | text | no | 1 to 200 characters |
| `sort_order` | smallint | no | unique, > 0 |

Seed: (anticoag, antiplatelet, MAJOR, "Bleeding risk: heparin with aspirin", 1); (anticoag, nsaid, MAJOR, "Bleeding risk: heparin with an NSAID", 2); (antiplatelet, nsaid, MODERATE, "Stomach bleeding risk: aspirin with an NSAID", 3).

### RLS on the two reference tables (deviation from ADR-009 "no RLS", forced by the repository gates)
Both reference tables get ENABLE and FORCE RLS with a non-tenant policy: `p_drug_select` / `p_drug_interaction_select` (`for select to sahaj_app using (true)`) and `p_drug_migrator` / `p_drug_interaction_migrator` (`for all to sahaj_migrator using (true) with check (true)`; the migrator has no BYPASSRLS and FORCE applies to the owner, so later content migrations still work). Why: `scripts/validate.mjs db` (human-owned) fails a migration file that mentions `tenant_id` and creates a table without `enable row level security`, and `RowLevelSecurityCatalogIT.noTableOutsideTheDocumentedExceptionsIsWithoutRowLevelSecurity` fails any table that is not enabled and forced unless allow-listed. The app's effect is the same as `opd.medicine`: it reads every row and can change none (no insert, update or delete privilege; 42501). The seed runs before RLS is enabled.
**Change needed from the qa-engineer:** `RowLevelSecurityCatalogIT.everyPolicyAppliesToSahajAppOnlyAndIsBuiltOnTheBoundTenantWithNoEscape` requires every policy to be `to sahaj_app` and to use `platform.current_tenant()`; the four reference policies break that on purpose. Exempt `ipd.drug` and `ipd.drug_interaction` there (a small allow-list for global catalogue tables, with a test that their policies are exactly select-for-sahaj_app and all-for-sahaj_migrator, both `using (true)`). Until then that one test fails. Alternative the human may prefer (needs a `validate.mjs` exception and the QA allow-list `NO_RLS_BY_DESIGN`): no RLS on the catalogue, as `opd.medicine`; that would be a new forward migration, so it was not done here.

## ipd.medicine_order (tenant table)
| Column | Type | Null | Default | Rule |
|---|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` | primary key |
| `tenant_id` | uuid | no | `platform.current_tenant()` | FK `fk_medicine_order_tenant` → `platform.tenant` |
| `admission_id` | uuid | no | | composite FK `fk_medicine_order_admission (tenant_id, admission_id)` → `ipd.admission (tenant_id, id)` |
| `client_key` | uuid | no | | `uq_medicine_order_tenant_client_key (tenant_id, client_key)` |
| `drug_key` | text | no | | FK `fk_medicine_order_drug` → `ipd.drug (key)` |
| `frequency_key` | text | no | | `ck_medicine_order_frequency`: od, bd, tds, q8 |
| `status` | text | no | `'PENDING'` | `ck_medicine_order_status`: PENDING, ACTIVE, REJECTED, STOPPED |
| `ordered_by` | uuid | no | | signed-in user id (IPD actor convention) |
| `ordered_at` | timestamptz | no | `now()` | database transaction time, not insertable by the app |
| `override_reason_key` | text | yes | | `ck_medicine_order_override_reason`: BENEFIT_MONITORED, SPECIALIST_ADVISED, ALLERGY_HISTORY_CHECKED, CARDIOLOGY_STENT |
| `warnings` | text[] | no | `'{}'` | `ck_medicine_order_warnings_count` (at most 10); `ck_medicine_order_warnings_text` (`ipd.medicine_warnings_ok`: one-dimensional, each element 1 to 300 characters, no null); `ck_medicine_order_override_warnings`: `(cardinality(warnings) > 0) = (override_reason_key is not null)` |
| `verified_by`, `verified_at` | uuid, timestamptz | yes | | pair (`ck_medicine_order_verified_pair`); `ck_medicine_order_self_verify`: `verified_by is null or verified_by <> ordered_by`; `ck_medicine_order_verified_at`: `>= ordered_at` |
| `rejected_by`, `rejected_at`, `reject_reason_key` | uuid, timestamptz, text | yes | | all three together (`ck_medicine_order_rejected_triple`); `ck_medicine_order_reject_reason`: ALLERGY_CONFLICT, INTERACTION_RISK, WRONG_DOSE, DUPLICATE_THERAPY; `ck_medicine_order_rejected_at`: `>= ordered_at` |
| `stopped_by`, `stopped_at` | uuid, timestamptz | yes | | pair (`ck_medicine_order_stopped_pair`); `ck_medicine_order_stopped_at`: `>= ordered_at` and `>= verified_at` when set |
| `version` | bigint | no | `0` | `ck_medicine_order_version` >= 0; the guard makes it exactly +1 per status change |

`ck_medicine_order_fields_by_status`: PENDING → verified, rejected and stopped fields all null; ACTIVE → `verified_by` set, rejected and stopped null; REJECTED → `rejected_by` set, verified and stopped null; STOPPED → `stopped_by` set, rejected null (verified pair set when it was ACTIVE, null when stopped from PENDING). Also `uq_medicine_order_tenant_id (tenant_id, id)` (SAH-32 MAR rows will reference it). No `created_*` or `updated_*` columns: `ordered_by/at` and the three transition stamps carry who and when (ADR-009).

### Indexes and the query each serves
| Index | Serves |
|---|---|
| `uq_medicine_order_tenant_id`, primary key | order by id (stop, verify, reject: lookup, then re-read under the admission lock); composite FK target |
| `uq_medicine_order_tenant_client_key (tenant_id, client_key)` | replay lookup of a retried order |
| `ux_medicine_order_tenant_admission_drug_live (tenant_id, admission_id, drug_key) where status in ('PENDING','ACTIVE')` | duplicate backstop (second insert = 23505 = code bug, 500, rollback) and the "live orders of this admission" read of the check |
| `ix_medicine_order_tenant_admission_ordered (tenant_id, admission_id, ordered_at, id)` | EMR list in chart order; the composite FK from the child side (leading columns `tenant_id, admission_id`) |
| `ix_medicine_order_tenant_pending (tenant_id, ordered_at, id) where status = 'PENDING'` | pharmacist queue (keyset, oldest first), its count and the change token; joined to ADMITTED admissions in the query |

No index on `drug_key`: the target table has 14 rows, is never deleted from, and no query filters orders by drug across admissions.

### Tenant scope and RLS
`tenant_id` not null; ENABLE and FORCE RLS; policies `p_medicine_order_select`, `p_medicine_order_insert`, `p_medicine_order_update` (all `to sahaj_app`, `tenant_id = platform.current_tenant()`; the update policy has `with check`, so a row cannot be moved to another clinic). No delete policy.

### Grants (`sahaj_app`)
- `select`.
- `insert (admission_id, client_key, drug_key, frequency_key, ordered_by, override_reason_key, warnings)`. `id`, `tenant_id`, `status` (always PENDING), `ordered_at` (database clock) and `version` keep their defaults; the other columns cannot be inserted (42501).
- `update (status, verified_by, rejected_by, reject_reason_key, stopped_by, version)`. Not granted (42501 if named): `id`, `tenant_id`, `admission_id`, `client_key`, `drug_key`, `frequency_key`, `ordered_by`, `ordered_at`, `override_reason_key`, `warnings`, `verified_at`, `rejected_at`, `stopped_at`. The trigger stamps the three times (a BEFORE trigger may write columns the caller has no privilege on).
- No delete, no truncate. `public` has nothing.

### Triggers (all SECURITY INVOKER, `search_path = pg_catalog`, functions revoked from public and `sahaj_app`)
- `trg_medicine_order_no_delete`, `trg_medicine_order_no_truncate` → `platform.deny_audit_mutation()`: 42501 for every role, the owner included ("... is append-only (DELETE refused)").
- `trg_medicine_order_guard_insert` → `ipd.guard_medicine_order_insert()` (SQLSTATE 23514): status must be PENDING ("medicine order must start as pending"); the admission, when visible, must be ADMITTED ("medicine order can be placed only for an admitted patient"). An admission that is missing or invisible (another clinic's, under RLS) is left to the composite foreign key (23503), so a cross-clinic order gives 23503, not a misleading 23514. The read takes no lock; the app holds the admission row `for no key update` (ADR-009 D10), and the FK check takes only a key-share lock on it.
- `trg_medicine_order_guard_update` → `ipd.guard_medicine_order_update()` (SQLSTATE 23514, every message contains "medicine order"). Rule order:
  1. old status REJECTED or STOPPED: refused ("is closed").
  2. `id, tenant_id, admission_id, client_key, drug_key, frequency_key, ordered_by, ordered_at, override_reason_key, warnings` changed: refused.
  3. Pair not in {PENDING→ACTIVE, PENDING→REJECTED, PENDING→STOPPED, ACTIVE→STOPPED}: refused ("status change is not allowed"); this includes an update that keeps the status.
  4. `version <> old.version + 1`: refused.
  5. Admission not ADMITTED, or not visible (fail closed): refused ("only while the patient is admitted").
  6. Fields of another transition changed: refused (`verified_by/at` unless the new status is ACTIVE; `rejected_by/at/reject_reason_key` unless REJECTED; `stopped_by/at` unless STOPPED).
  7. The transition's own fields must be present (`verified_by`; `rejected_by` and `reject_reason_key`; `stopped_by`), and the trigger stamps `verified_at`, `rejected_at` or `stopped_at` with `clock_timestamp()` (any value sent is overwritten).

| From → to | Statement for the java-developer |
|---|---|
| (new) | `insert into ipd.medicine_order (admission_id, client_key, drug_key, frequency_key, ordered_by, override_reason_key, warnings) values (...)`. Do not send `id`, `tenant_id`, `status`, `ordered_at`, `version`. `warnings` is `text[]`; `override_reason_key` null and `warnings` `'{}'` when there was no warning. |
| PENDING → ACTIVE | `update ... set status = 'ACTIVE', verified_by = :user, version = version + 1 where tenant_id = :t and id = :id and status = 'PENDING'` (`:user <> ordered_by` or 23514 from `ck_medicine_order_self_verify`) |
| PENDING → REJECTED | `set status = 'REJECTED', rejected_by = :user, reject_reason_key = :reason, version = version + 1 ... and status = 'PENDING'` |
| PENDING or ACTIVE → STOPPED | `set status = 'STOPPED', stopped_by = :user, version = version + 1 ... and status in ('PENDING','ACTIVE')` |

Never send `verified_at`, `rejected_at`, `stopped_at` (42501). `version` is bigint: use `getLong`. A 23505 on `ux_medicine_order_tenant_admission_drug_live` or `uq_medicine_order_tenant_client_key`, a 23514 or a 23503 is a code bug (500, full rollback, never swallowed): the lock and the duplicate rule in Java answer first.

## Expected rows, partitioning, retention
- `ipd.drug` 14 rows, `ipd.drug_interaction` 3 rows. Global, changed by migration only.
- `ipd.medicine_order`: 100 hospitals × 150 beds × 1 to 3 new orders per bed-day = 15,000 to 45,000 rows a day, 5 to 16 million a year in total (50,000 to 160,000 per hospital per year). Row size about 250 bytes plus warnings (usually empty), five indexes entries. Below the 50 million row mark for about three years; no partitioning in phase 1. Plan: no foreign key will point at it except SAH-32 MAR rows via `uq_medicine_order_tenant_id`; if partitioning is needed, `ordered_at` range partitioning would need the key to include it, so decide before SAH-32 adds the MAR FK (revisit at 30 million rows).
- **Restated volumes (ADR-009 D12):** each EMR opening writes one `ipd.clinical_event` row (kind view) and one `patient.record_access` row. At 10 to 20 openings per patient-day: 150,000 to 300,000 rows a day across 100 hospitals in each of the two tables (55 to 110 million a year each). That is the fastest-growing IPD data, ahead of `ipd.vitals`. The ADR-006 D2 partition procedure for `ipd.clinical_event` (range on `at`, per quarter; no foreign key points at it) must therefore start at about 20 million rows, in practice within the first year of full use. `patient.record_access` (patient module) needs its own plan; both are outside this migration and are noted for the solution-architect and tech lead.
- Retention: medicine orders are in-patient record; never deleted (no delete privilege, delete and truncate triggers, no delete policy). Retention period is the human's (ADR-006 Q10). Reference tables: never deleted by the app.
- Privacy: `warnings` is clinical text, shown on the pharmacist sheet and EMR only; never logged, audited or put in `ipd.clinical_event.text`. The table holds no patient name and no allergy text.

## Backward compatibility
Additive: three new tables, helper and guard functions. The previous app version never touches them and runs unchanged against the new schema. Rollback before any order exists: a new forward migration that drops the three tables and functions (nothing in this migration is referenced by earlier tables).

## For the qa-engineer / existing tests
`RowLevelSecurityCatalogIT.everyPolicyApplies...` needs the exemption above; no other change. Any test that counts the update-privileged columns or lists all `ipd` tables is unaffected by design; the new tables are only added.

## QA test list (real database, as `sahaj_app` and as the table owner)
Positive:
1. Seed: `ipd.drug` has exactly 14 rows with the keys, names, routes, classes, high-alert flags and sort order above (`order by sort_order`); `ipd.drug_interaction` has exactly the 3 rows in order; as `sahaj_app` both are readable with or without the tenant setting (policy `using (true)`).
2. Insert as `sahaj_app` of the seven granted columns: `status = 'PENDING'`, `version = 0`, `ordered_at` within a second of `now()`, `tenant_id` defaulted; a row with warnings and a reason; a row with neither.
3. Every transition with the statements above: PENDING→ACTIVE, PENDING→REJECTED, PENDING→STOPPED, ACTIVE→STOPPED: `version` +1, the stamped time is the database clock (not a value sent; send none), earlier stamps kept (`verified_at` unchanged after ACTIVE→STOPPED, `stopped_at >= verified_at`).
4. A drug can be ordered again after the first order is REJECTED or STOPPED; two orders of the same drug in two admissions of one clinic are fine.
Negative (each leaves the row unchanged):
5. Update of a REJECTED or STOPPED order, `version = version + 1` alone included: 23514 "medicine order is closed" (app and owner).
6. Disallowed pairs: ACTIVE→PENDING, ACTIVE→REJECTED, ACTIVE→ACTIVE, PENDING→PENDING, PENDING→PENDING with `version + 1`: 23514 "status change is not allowed". `status = 'GONE'`: 23514 (guard first; the status check is the second line).
7. Version: `+0`, `+2`, lower, `3000000000` on a transition: 23514.
8. Self-verification: `verified_by = ordered_by` as the app: 23514 `ck_medicine_order_self_verify`; as the owner too. Verified by a different user succeeds.
9. Missing transition fields: ACTIVE with `verified_by` null, REJECTED with a null `rejected_by` or `reject_reason_key`, STOPPED with `stopped_by` null: 23514. An unknown reject reason: 23514 `ck_medicine_order_reject_reason`.
10. Fields of another transition: `stopped_by` on a reject, `rejected_by` on a verify, `verified_by` on a stop from PENDING: 23514.
11. Admission not ADMITTED: insert of an order for a DISCHARGED admission: 23514 ("only for an admitted patient"); PENDING→ACTIVE/REJECTED/STOPPED and ACTIVE→STOPPED after discharge: 23514 ("only while the patient is admitted"). The discharged admission's orders keep their status.
12. Insert with `status = 'ACTIVE'` as the owner: 23514 "must start as pending". Insert as the app naming `status`, `ordered_at`, `version`, `verified_by`, `id` or `tenant_id`: 42501.
13. Columns outside the update grant as the app: `verified_at`, `rejected_at`, `stopped_at`, `ordered_at`, `ordered_by`, `drug_key`, `warnings`, `override_reason_key`, `admission_id`, `client_key`, `frequency_key`, `id`, `tenant_id`: 42501. As the owner: identity and order fields (`drug_key`, `warnings`, `ordered_by`, ...): 23514 "identity and order fields cannot change"; a direct `verified_at` on a non-ACTIVE transition: 23514.
14. Checks (insert as the owner with the trigger or the value in range): `frequency_key = 'qd'`, `override_reason_key = 'OTHER'`: 23514; warnings with a reason null, no warnings with a reason set: 23514 `ck_medicine_order_override_warnings`; 11 warnings: 23514 `_warnings_count`; an empty-string, 301-character or null warning element: 23514 `_warnings_text` (300 characters accepted); a two-dimensional array: 23514.
15. `drug_key = 'zzz'`: 23503 `fk_medicine_order_drug`. Admission of another clinic (tenant A session, tenant B admission id) or a missing admission: 23503 `fk_medicine_order_admission` (not 23514).
16. Uniqueness: same `(tenant_id, client_key)` twice: 23505 `uq_medicine_order_tenant_client_key` (same key in another clinic is fine). Second PENDING or ACTIVE order of the same drug in one admission: 23505 `ux_medicine_order_tenant_admission_drug_live` (PENDING then ACTIVE of the first one still counts as live; after STOPPED or REJECTED a new one is accepted).
17. Delete and truncate as `sahaj_app`: 42501 (privilege); as the owner: 42501 from the trigger ("append-only"); rows unchanged.
18. RLS: tenant A cannot select, update or see tenant B's orders; an update of tenant B's order affects 0 rows; an insert or update with another `tenant_id` is refused; with no `app.tenant_id` set nothing is visible. Reference tables: `sahaj_app` insert, update, delete on `ipd.drug` and `ipd.drug_interaction`: 42501.
19. Catalog: RLS enabled and forced on `ipd.medicine_order` with exactly the three policies (select, insert, update); the two reference tables enabled and forced with exactly the four non-tenant policies of the RLS section; `sahaj_app` update privileges on `ipd.medicine_order` are exactly the six columns, insert exactly the seven, no delete or truncate; the triggers `trg_medicine_order_no_delete`, `_no_truncate`, `_guard_insert`, `_guard_update` are enabled; both guard functions revoked from public and `sahaj_app`; both guard functions have `search_path=pg_catalog` in `proconfig`; the four indexes and `uq_medicine_order_tenant_id` exist; the partial indexes have the `where` clauses above.
20. Query plans (with about 100,000 rows over several admissions and `analyze`): the queue query (`where tenant_id = ? and status = 'PENDING' order by ordered_at, id limit 51`) uses `ix_medicine_order_tenant_pending` with no sort; the EMR list uses `ix_medicine_order_tenant_admission_ordered`.
21. Races (two connections, admission row lock as in ADR-009 D10): two orders of the same drug at once (one inserted, one blocked by the duplicate rule in Java; with the lock skipped, the second insert is 23505); verify vs stop; verify vs reject; two verifies by two users (second `where status = 'PENDING'` matches 0 rows, trigger never reached); order vs discharge (discharge first: insert gives 23514).

## Amendment 6 Nov 2026 (review R7)
The header text of `V2026_11_28_1000__ipd_medicine_orders.sql` (line 9) and its stored table comments on `ipd.drug` and `ipd.drug_interaction` say "no row level security". That is superseded: section 3b of the same migration enabled and forced RLS on both tables (see "RLS on the two reference tables" above). The applied file is not edited (Flyway checksum). The comment-only migration `V2026_11_28_1200__ipd_drug_comments_rls.sql` restates both table comments (global reference data, no `tenant_id`, RLS enabled and forced, select only for `sahaj_app`, all for `sahaj_migrator`, content changed by migrations only). No schema, data, grant or policy change.
