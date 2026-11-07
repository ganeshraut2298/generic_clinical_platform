# SAH-35 · Database design: Medicine round on the Safety board

Decision record: ADR-012 (D1, D12 and "Data needed"). Mockup screen: IPD → Safety tab of Beds and admissions (tile "Doses due or overdue", panel "Medicine round").

**Result: no migration.** No table, column, constraint, grant, trigger, policy or index is added. The conditional index `ix_medicine_order_tenant_active` is **not** added (decision below). The latest migration stays `V2026_11_29_1000__ipd_mar_dose_administration.sql`. Because no migration was added, `node scripts/validate.mjs db` was not run (as instructed).

## Status of the plan check: NOT MEASURED

The check on realistic volume (150 ADMITTED and 20,000 DISCHARGED admissions, 3,000 orders of which 600 ACTIVE, 2 million dose rows, other clinics' rows, as `sahaj_app` with `app.tenant_id` set, inside one transaction ended with ROLLBACK) **could not be run**. The environment denied `psql` for the db-engineer (two attempts: a command that read the example env file, and a passwordless connection test to `sahaj_test` as `sahaj_migrator`). As instructed, no workaround was tried: the `.env` passwords were not read, no other client was used, nothing was created, changed or cleaned in `sahaj_dev` or `sahaj_test`.

Everything below under "Expected plans" is reasoning from the existing indexes and the PostgreSQL 16 planner, **not a measurement**. The tech lead or QA must run the statements in "How to measure" (or give the db-engineer a permitted way to connect) before this is treated as verified. The decision on the index does not depend on a measurement being a surprise: the reasons given are structural, and the note says what result would reverse it.

## Tables touched

Read only, nothing written by this story. All three are tenant tables with RLS enabled and forced (`tenant_id = platform.current_tenant()`), unchanged.

| Table | Index used | Used for |
|---|---|---|
| `ipd.admission` | `ix_admission_tenant_status_admitted_at (tenant_id, status, admitted_at)` | ADMITTED admissions of the clinic (about 150; the 20,000 DISCHARGED are not read) |
| `ipd.medicine_order` | `ix_medicine_order_tenant_admission_ordered (tenant_id, admission_id, ordered_at, id)` or the partial unique `ux_medicine_order_tenant_admission_drug_live (tenant_id, admission_id, drug_key) where status in ('PENDING','ACTIVE')` | orders of those admissions, `status = 'ACTIVE'` |
| `ipd.dose_administration` | `ix_dose_administration_tenant_admission_slot (tenant_id, admission_id, slot_at)` | today's records of those admissions |

## The two reads (statement shapes for EXPLAIN)

Q1, orders of admitted patients (the join to bed and the drug catalogue are small and by primary key; the java-developer may add them):

```sql
select o.id, o.admission_id, o.drug_key, o.frequency_key, o.verified_at, a.patient_id
  from ipd.admission a
  join ipd.medicine_order o
    on o.tenant_id = a.tenant_id and o.admission_id = a.id
 where a.tenant_id = :t and a.status = 'ADMITTED' and o.status = 'ACTIVE';
```

Q2, today's records (array form; the join form is an alternative):

```sql
select order_id, slot_at, outcome
  from ipd.dose_administration
 where tenant_id = :t
   and admission_id = any(:admittedIds)
   and slot_at >= :dayStart and slot_at < :dayEnd;
```

## Expected plans (reasoned, not measured)

**Q1.** `ix_admission_tenant_status_admitted_at` index scan on `(tenant_id = t, status = 'ADMITTED')`: about 150 rows. Then a nested loop with one index probe per admission on `ix_medicine_order_tenant_admission_ordered` (leading columns `tenant_id, admission_id` bound by the join) with filter `status = 'ACTIVE'`; or the planner may choose the partial unique index `ux_medicine_order_tenant_admission_drug_live`, because `status = 'ACTIVE'` implies its predicate `status in ('PENDING','ACTIVE')`. Rows read: 150 probes, each touching only the orders of one admission (3 to 5 live plus the stopped ones of that stay), so a few hundred to low thousands of index entries and heap rows for 600 returned. No sequential scan of `ipd.medicine_order` is expected while statistics are current (the table holds other clinics' rows, and the tenant and admission predicates are selective). Expected time: a few milliseconds, well under 50 ms.

**Q2.** One index scan on `ix_dose_administration_tenant_admission_slot` with all three index conditions: `tenant_id = t`, `admission_id = any(...)`, `slot_at` range. PostgreSQL 16 b-tree handles the array with one descent per element (about 150 descents), each reading the 1 to 3 dose rows of the day of that admission (about 1,500 rows in all) in index order. No sequential scan and no bitmap on a 2-million-row table. Expected time: under 10 ms with a warm cache. The clinic-wide `slot_at` range alone (about 150,000 rows a day across all clinics) is never the access path because `admission_id` is bound; this is why no `(tenant_id, slot_at)` index is wanted.

Join form of Q2 (join to ADMITTED admissions instead of an id array): also expected to plan as nested loop admission (index) → dose rows (admission index with the `slot_at` range). Risk: with stale statistics the planner could prefer a hash join with a scan on the day range. The array form avoids this. **Recommendation to the java-developer: use the array form** (`admission_id = any(?)` with a `uuid[]` parameter, ids taken from Q1's admission list).

### What would make this check fail, and what to do

| Observation in a real plan | Meaning | Action |
|---|---|---|
| Q1 shows `Rows Removed by Filter` in the hundreds of thousands on `medicine_order`, or a seq scan | statistics stale or plan wrong | `analyze ipd.medicine_order` first; if it persists, report to the db-engineer |
| Q1 reads many STOPPED rows per probe (heap fetches much larger than 600 returned rows) and the planner did not pick the partial unique index | long stays with many stopped orders | add `ix_medicine_order_tenant_active (tenant_id, admission_id) where status = 'ACTIVE'` as a new migration with the next free version after `V2026_11_29_1000` |
| Q2 shows a seq scan or a bitmap scan over the day range of the whole table | join form chosen, or array not bound | use the array form; report if it persists |

## Decision on the index: not added

1. A live-order partial index already exists: `ux_medicine_order_tenant_admission_drug_live` covers exactly PENDING and ACTIVE rows keyed `(tenant_id, admission_id, drug_key)`. The planner can use it for `status = 'ACTIVE'` and then never reads STOPPED or REJECTED rows. A second partial index on a subset of it (ACTIVE only) adds an index entry per order write (every verify, every stop moves the row in or out) for no new access path.
2. Even without it, the probe is per admission, not per clinic: it reads only the orders of that stay. STOPPED rows of discharged admissions (the bulk of history) are never reached because Q1 starts from ADMITTED admissions.
3. The architect's rule was to add it only if the plan reads many STOPPED rows. That was not observed, because nothing could be measured; with no evidence and a structural argument against, the write cost on a table that grows by about 16 million orders a year is not justified. If a measurement shows the row 2 case above, add the index then (a small, additive, forward-only migration; `create index concurrently` is not needed while the table is small, but would be in its own non-transactional migration after go-live).
4. No `(tenant_id, slot_at)` index on `ipd.dose_administration` (as instructed and as ADR-012 "Data needed" item 2).

## How to measure (for whoever has database access)

Roles: generate and measure with a role that may insert into the three tables and can set `app.tenant_id`; the plan itself must be taken as `sahaj_app` (`set local role sahaj_app` after the data is inserted, in the same transaction, so RLS applies). Everything in one transaction, then `rollback`, so `sahaj_test` and `sahaj_dev` keep no rows.

1. `begin;` Insert, for tenant A: 20,150 admissions (150 ADMITTED, 20,000 DISCHARGED) over a few hundred beds; 3,000 `ipd.medicine_order` rows of which 600 ACTIVE on ADMITTED admissions (the rest STOPPED, REJECTED or on discharged admissions); 2,000,000 `ipd.dose_administration` rows over a year, about 1,500 with `slot_at` today (IST day). Add the same shape for 20 other tenants at lower volume. Use `generate_series`. The dose and order guard triggers and the append-only triggers refuse many shapes; the documented approach in `docs/database/SAH-32.md` (insert as the table owner, respecting the constraints) applies, and `set local session_replication_role = replica` is only available to a superuser. Not tested here.
2. `analyze ipd.admission, ipd.medicine_order, ipd.dose_administration;` (inside the transaction; autovacuum has not seen the new rows).
3. `set local role sahaj_app; select set_config('app.tenant_id', '<tenant A uuid>', true);`
4. `explain (analyze, buffers)` for Q1 and for Q2 (with `:admittedIds` as `array(select id from ipd.admission where status = 'ADMITTED')`).
5. `rollback;` Check afterwards that the three tables have their previous row counts.

Pass: index scans only on the three tables, no `Seq Scan` on `ipd.dose_administration` or `ipd.medicine_order`, total execution time under 50 ms for each query, `Buffers` shared hit in the low thousands.

## Tenant scope, rows, retention, compatibility

- Tenant scope: unchanged. Both reads run as `sahaj_app` with the tenant from the session; RLS hides other clinics' rows. The array of admission ids comes from Q1 (already tenant-scoped), and `tenant_id = :t` is still written in the statement so the index prefix is used.
- Expected rows: nothing new is stored (ADR-012 D12). Per answer: about 150 admissions, 600 orders and 1,500 dose rows read for a 150-bed hospital, every 60 seconds per open Safety screen.
- Retention: unchanged, nothing deleted.
- Partitioning: `ipd.dose_administration` partition plan by `slot_at` (SAH-32 note, ADR-011 D13) is unaffected; Q2 always carries a `slot_at` range and would prune to one partition.
- Backward compatibility: no schema change; the previous app version runs unchanged.

## QA list for the db part

1. Catalogue: no new migration file after `V2026_11_29_1000`; `\d ipd.medicine_order` has no index named `ix_medicine_order_tenant_active`; `ipd.dose_administration` has no index on `(tenant_id, slot_at)`.
2. The four existing indexes named in "Tables touched" exist with the columns shown, and `ux_medicine_order_tenant_admission_drug_live` is partial on `status in ('PENDING','ACTIVE')`.
3. Plan Q1 (about 150 ADMITTED, 20,000 DISCHARGED, 600 ACTIVE orders, `analyze`, as `sahaj_app` with the tenant set): no seq scan on `ipd.medicine_order` or `ipd.admission`, index scans only, execution under 50 ms. Run it in a transaction that ends with ROLLBACK.
4. Plan Q2 (about 2 million dose rows, other clinics' rows, `analyze`): `ix_dose_administration_tenant_admission_slot` with all three index conditions visible (`admission_id = ANY`, `slot_at` range), no seq scan, under 50 ms.
5. Tenant isolation of the reads: with tenant A set, Q1 and Q2 return none of tenant B's orders or doses even when B's admission ids are put in `:admittedIds`; with no tenant set, both fail or return nothing (never rows).
6. Discharged admissions are never in Q1's result; STOPPED, PENDING and REJECTED orders are never in Q1's result; an ACTIVE order of an ADMITTED admission with a dose recorded today appears in Q1 and its dose in Q2.
7. Day range: a dose with `slot_at` at 00:00 IST today is in Q2, one at 23:59 IST yesterday is not, one at 00:00 IST tomorrow is not (half-open range on the IST day).
8. Invariant for the Java tests, not the database: `dosesDue + dosesLate` equals the sum of the MAR tab counts at the same `asOf`.

## Notes for the human

- The plan check is outstanding (psql denied to the db-engineer). Until a measurement exists, "index scans only, under 50 ms" is an expectation, not a result.
- Nothing in this story needs a go-live runbook item beyond those already in the SAH-32 note.
