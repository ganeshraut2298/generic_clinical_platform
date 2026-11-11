# SAH-34 · Database design: IPD ward scoping and break-glass (data part: 34A data, 34B, 34E, 34F)

Migration (forward-only, additive; no earlier migration is edited): `V2026_12_06_1000__ipd_ward_scope_and_break_glass.sql` (latest before it: `V2026_12_04_1000`; `V2026_12_05_1000` is the SAH-29 packages migration). Source: `docs/architecture/SAH-34-ward-scoping-and-break-glass.md` sections 4 to 7, ADR-017 D1, D4, D5, D7, DEC-031. Mockup screens: IPD → Admitted (`pcscope`, `sfbg`, `sfbgok`, `sfFlags`), IPD → Safety (`bgRow`, `sfbgrev`, `kic` `bg`). There is no nurse-to-ward screen in the mockup; the ward-assignment table is built ahead of it (human go-ahead 9 Nov 2026, "lets start"; the 34A screen is still missing).

Changes: two new tables (`ipd.ward_assignment`, `ipd.emergency_access_grant`), one new column on `ipd.config`, one new column with a check and a key on `ipd.clinical_event`. No existing row is updated; nothing is dropped, renamed or re-granted.

## 1. ipd.ward_assignment (34A)
| Column | Type | Null | Default / rule |
|---|---|---|---|
| id | uuid | no | `gen_random_uuid()`, primary key |
| tenant_id | uuid | no | `platform.current_tenant()`; FK `fk_ward_assignment_tenant` to `platform.tenant` |
| ward_id | uuid | no | composite FK `fk_ward_assignment_ward (tenant_id, ward_id)` to `ipd.ward (tenant_id, id)` |
| membership_id | uuid | no | `platform.membership` id of a NURSE. **No FK** (cross-module); the service checks it through `TeamMemberPort` |
| assigned_at | timestamptz | no | `clock_timestamp()`; not insertable by the app |
| assigned_by | uuid | no | user id (IPD actor convention) |
| removed_at | timestamptz | yes | stamped by the guard trigger, once; `ck_ward_assignment_removed_order` (`>= assigned_at`) |
| removed_by | uuid | yes | user id; `ck_ward_assignment_removed` `(removed_at is null) = (removed_by is null)` |
| version | bigint | no | 0 |

Indexes:
| Index | Serves |
|---|---|
| `ux_ward_assignment_live` unique `(tenant_id, ward_id, membership_id) where removed_at is null` | one live row per nurse per ward (23505 backstop behind the clinic IPD advisory lock); "live nurses of one ward" and "all live assignments of the clinic" for GET `/ward-assignments` (leading columns `tenant_id, ward_id`); the diff in PUT `/ward-assignments/{wardId}` |
| `ix_ward_assignment_member_live` `(tenant_id, membership_id) include (ward_id) where removed_at is null` | the scope build: `select ward_id from ipd.ward_assignment where membership_id = :m and removed_at is null`, index-only. Also the "active NURSE without a live ward" check for PUT `/ward-scope` (anti-join per nurse) |

The partial indexes hold only live rows, so ended rows (history) never cost the scope build anything.

Triggers:
- `trg_ward_assignment_guard_update` (before update): `id`, `tenant_id`, `ward_id`, `membership_id`, `assigned_at`, `assigned_by` never change; an ended row (`removed_at` or `removed_by` set) never changes again; an update must set `removed_by` (so the only possible update is "end it"); `removed_at` is overwritten with `clock_timestamp()` whatever the app sent. SQLSTATE 23514 (`check_violation`), constraint name `trg_ward_assignment_guard_update` in the error.
- `trg_ward_assignment_no_delete`, `trg_ward_assignment_no_truncate`: `platform.deny_audit_mutation()`, SQLSTATE 42501, for every role.

## 2. ipd.config.nurse_ward_scope (34A)
`boolean not null default false`. A constant default needs no rewrite and no lock beyond the instant `ALTER` on PostgreSQL 16; every existing and future lazy row starts off, and a clinic with no `ipd.config` row is also off (Java rule). Grants of `ipd.config` are table-level `select, insert, update` for `sahaj_app` (V2026_11_11_1000, never narrowed since), so the column is covered; no grant statement is needed. No delete.

## 3. ipd.emergency_access_grant (34E, 34F)
| Column | Type | Null | Default / rule |
|---|---|---|---|
| id | uuid | no | `gen_random_uuid()`, primary key; `uq_emergency_access_grant_tenant_id (tenant_id, id)` is the target of the event FK |
| tenant_id | uuid | no | `platform.current_tenant()`; FK to `platform.tenant` |
| admission_id | uuid | no | composite FK to `ipd.admission (tenant_id, id)` |
| bed_id | uuid | no | composite FK to `ipd.bed (tenant_id, id)`; the bed at the time of the grant |
| user_id | uuid | no | grantee user (created_by of the row); no FK |
| membership_id | uuid | no | grantee membership; no FK |
| role | text | no | `ck_emergency_access_grant_role` in `NURSE`, `VISITING_DOCTOR` |
| reason_key | text | no | `ck_emergency_access_grant_reason` in `DETERIORATING`, `COVERING_BREAK`, `DUTY_DOCTOR_ASKED` |
| opened_at | timestamptz | no | stamped by the BEFORE INSERT trigger with `clock_timestamp()`; not insertable by the app |
| expires_at | timestamptz | no | trigger: `opened_at + interval '30 minutes'`; `ck_emergency_access_grant_expiry` keeps `expires_at = opened_at + interval '30 minutes'` |
| reviewed_by | uuid | yes | `ck_emergency_access_grant_not_self_review` (`reviewed_by <> user_id`) |
| reviewed_at | timestamptz | yes | stamped by the guard trigger; `ck_emergency_access_grant_reviewed` `(reviewed_by is null) = (reviewed_at is null)`; `ck_emergency_access_grant_reviewed_order` (`>= opened_at`) |
| version | bigint | no | 0 |

No name, note or free text is stored. Names are read at display time.

Indexes:
| Index | Serves |
|---|---|
| `ix_emergency_access_grant_member_expires` `(tenant_id, membership_id, expires_at desc) include (admission_id, role)` | the scope build's grant probe (`membership_id = :m and role = :role and expires_at > clock_timestamp()`, joined to an ADMITTED admission by primary key) and the same probe re-run under the admission lock for writes. Index-only. The exact test `expires_at > clock_timestamp()` is volatile and runs as a filter; the range in the index comes from the stable pre-filter `expires_at > now() - interval '1 minute'` that both statements must also carry (section 10.3, F-1). Without it the probe reads every grant the member ever opened |
| `ix_emergency_access_grant_unreviewed` `(tenant_id, opened_at, id) where reviewed_at is null` | the owner's unreviewed count (tile, tab number), the first page (oldest first, 25 rows) and the keyset `(opened_at, id) > (:cursor)`. The partial index shrinks as grants are reviewed |

Not indexed, on purpose: `admission_id` and `bed_id` (no read goes grant-to-admission by admission; the panel and the event page reach the grant by primary key), and `clinical_event.grant_id` (the event page joins the grant by primary key; grants are never deleted, so the referenced-side key check never runs). A bed delete (allowed only while a bed has no history) runs one key check on this table; a bed that has a grant has an admission, so the delete is refused earlier by `fk_admission_bed`. At about 365,000 rows a year a missing index there costs nothing worth a second write on every insert.

Triggers (all functions SECURITY INVOKER, `search_path = pg_catalog`, execute revoked from public and `sahaj_app`):
- `trg_emergency_access_grant_stamp` (before insert): refuses a row that arrives already reviewed; sets `opened_at := clock_timestamp()` and `expires_at := opened_at + 30 minutes`, whatever was sent.
- `trg_emergency_access_grant_guard_update` (before update): only the review can change. Refuses a change to any other column; refuses a second review (`reviewed_by` or `reviewed_at` already set); an update must set `reviewed_by`; refuses `reviewed_by = user_id` (message "the person who opened emergency access cannot review it", constraint name `ck_emergency_access_grant_not_self_review`); overwrites `reviewed_at := clock_timestamp()`. SQLSTATE 23514.
- `trg_emergency_access_grant_no_delete`, `trg_emergency_access_grant_no_truncate`: `platform.deny_audit_mutation()`, SQLSTATE 42501, for every role.

## 4. ipd.clinical_event.grant_id (34E, 34F)
- `grant_id uuid null` (metadata-only add).
- `ck_clinical_event_grant check (grant_id is null or kind = 'bg')`.
- `fk_clinical_event_grant foreign key (tenant_id, grant_id) references ipd.emergency_access_grant (tenant_id, id) match simple` (a null `grant_id` skips the check).
- `grant insert (grant_id) on ipd.clinical_event to sahaj_app` (column grants are additive; `admission_id, kind, text, actor` stay).
- The append-only triggers and the select grant stay. Every existing row has `grant_id` null, so both constraints validate without a violation.
- **Lock note:** adding the column takes `ACCESS EXCLUSIVE` on `ipd.clinical_event` until the migration commits, and the two constraints scan the table once while it is held (they are not `NOT VALID`, as the brief asked for one migration). The table is the fastest-growing IPD table after vitals and doses. If it holds more than about 5 million rows on the day this is released, the tech lead should split it: keep the column and add both constraints `NOT VALID` here, and run `validate constraint` in a later migration (only `SHARE UPDATE EXCLUSIVE`). At the current size of every dev and test database this is milliseconds.

## 5. Tenant scope and RLS
Both new tables: `tenant_id not null` (default `platform.current_tenant()`), FK to `platform.tenant`, ENABLE and FORCE row level security, policies for `sahaj_app` on `tenant_id = platform.current_tenant()`: `p_ward_assignment_select|insert|update` and `p_emergency_access_grant_select|insert|update` (insert `with check`, update `using` and `with check`, so a row cannot be moved to another clinic). No delete policy. Every same-schema key is composite on `(tenant_id, x_id)`, so a row can never point at another clinic's ward, bed or admission. `membership_id`, `user_id`, `assigned_by`, `removed_by` and `reviewed_by` have no key (cross-module / global); they are never taken from a request body.

## 6. Grants to sahaj_app (all other privileges revoked first)
| Table | select | insert (columns) | update (columns) | delete, truncate |
|---|---|---|---|---|
| `ipd.ward_assignment` | yes | `ward_id, membership_id, assigned_by` | `removed_at, removed_by, version` | none (+ trigger) |
| `ipd.emergency_access_grant` | yes | `admission_id, bed_id, user_id, membership_id, role, reason_key` | `reviewed_by, version` | none (+ trigger) |
| `ipd.clinical_event` | yes | `admission_id, kind, text, actor` + **`grant_id`** | none | none (+ trigger) |
| `ipd.config` | yes | table-level (unchanged) | table-level (unchanged) | none |

Not granted (SQLSTATE 42501 if named): `id`, `tenant_id`, `assigned_at`, `opened_at`, `expires_at`, `reviewed_at`. `removed_at` is in the update grant as the architect's note lists it, but the trigger overwrites whatever is sent, so Java may leave it out of its `UPDATE`.

## 7. Errors the java-developer will see
| Situation | Error | Map to |
|---|---|---|
| second live row of the same nurse and ward | 23505 on `ux_ward_assignment_live` | not reachable under the clinic advisory lock; 500 if hit (code bug) |
| insert with a ward of another clinic | 23503 on `fk_ward_assignment_ward` | 404 (the service already read the ward under RLS; a hit is a bug) |
| grant for an unknown admission or bed | 23503 on `fk_emergency_access_grant_admission` / `_bed` | not reachable (admission locked first); 500 |
| grant with a reason or role outside the list | 23514 on `ck_emergency_access_grant_reason` / `_role` | 400 if it ever reaches the database (the service validates first); 500 otherwise |
| second review, change of a reviewed grant, review without `reviewed_by`, any other column changed | 23514, constraint `trg_emergency_access_grant_guard_update` | the service decides 409 `already-reviewed` from `update ... where reviewed_at is null` returning 0 rows; this error is a backstop (500) |
| review by the grantee | 23514, constraint `ck_emergency_access_grant_not_self_review` | the service answers 403 `forbidden` "You cannot review your own emergency access." first (design note 5, step 3); this error is a backstop (500) |
| ended assignment updated again, or a fixed column changed | 23514, constraint `trg_ward_assignment_guard_update` | backstop (500); the service diff never touches an ended row |
| update of `opened_at`, `expires_at`, `reviewed_at`, insert of `opened_at` or `expires_at` by `sahaj_app` | 42501 (column privilege) | 500, code bug |
| any delete or truncate of either table | 42501 (`insufficient_privilege`) from the trigger or the missing grant | 500, code bug |
| `bg` event with `grant_id` but another kind | 23514 on `ck_clinical_event_grant` | 500, code bug |
| `grant_id` of another clinic's grant | 23503 on `fk_clinical_event_grant` | 500, code bug |

Note: the guarded review `update ... set reviewed_by = :me, version = version + 1 where id = :id and reviewed_at is null` must be a statement the grant allows: it names only `reviewed_by` and `version`. Do not map the entity with Hibernate's default "update every column" (it would name `opened_at`, `expires_at` and be refused with 42501); use `@DynamicUpdate` or a native/JPQL update. Same for `ward_assignment` (`removed_by`, `version`, optionally `removed_at`).

**The grant clock (for the scope build and the locked recheck):** compare `expires_at > clock_timestamp()` in SQL, never `expires_at` against a Java `Instant.now()` and never `now()` as the liveness test. `now()` is the transaction start; the check must be the live database clock, both at request start and again under the admission lock. A grant row is live for `[opened_at, opened_at + 30 min)`. An index cannot hold "not expired", because `now()` is not immutable; that is why the duplicate-grant rule is the admission row lock, not a unique index.

**Testing hint for qa-engineer:** `sahaj_app` cannot create an expired grant (the trigger stamps the clock). To test expiry, either wait for it, or as `sahaj_migrator` in one transaction run `alter table ipd.emergency_access_grant disable trigger trg_emergency_access_grant_stamp`, insert with both times set (the expiry check still holds), then re-enable the trigger (or roll back).

## 8. Rows, growth, retention
| Table | Rows per clinic | Platform-wide | Growth | Retention |
|---|---|---|---|---|
| `ipd.ward_assignment` live rows | OPD clinics: 0 (the table is for IPD hospitals). An IPD hospital: about 26 wards x 4 nurses, up to about 120 live | about 12,000 live (100 hospitals) | a few changes a month per hospital; ended rows stay | never deleted (ended, not deleted) |
| `ipd.emergency_access_grant` | about 10 a day per hospital, about 3,650 a year | about 365,000 a year, 3.7 million in 10 years | linear | never deleted in phase 1 (in-patient access trail; the retention period is the human's) |
| `ipd.config` | 1 (lazy) | unchanged | | |

Both are far below the 50 million partitioning line; no partitioning. `ipd.clinical_event` grows by two `bg` rows per grant (open and review), about 730,000 a year platform-wide, small against its existing volume.

## 9. Backward compatibility
The previous app version runs against the new schema: it never names the new tables or columns. `ipd.config.nurse_ward_scope` has a default and `clinical_event.grant_id` is nullable, so the old inserts still work (the old `insert (admission_id, kind, text, actor)` is still granted). With the switch off for every clinic, nothing changes for any user at deploy. Rolling back the app needs no schema change. Nothing existing is altered, so no stop condition was met.


## 10. Query plans (design note section 7.5; real plans, updated 10 Nov 2026)

**How these were produced.** The qa-engineer ran `ScopePlansIT` on 10 Nov 2026 as `sahaj_app` with `app.tenant_id` set, on `sahaj_test`: a hospital of 26 wards, 300 admitted patients (the bed limit), 36,000 clinical events (120 per patient over 30 days), 3,655 grants of which 5 unreviewed, one nurse with two live wards and one live grant, tables analysed. The statements are the ones the repositories send. Source: `docs/test-plans/SAH-34.md` section 4; raw output with both planner settings in `backend/target/sah34-explain.txt` (rewritten by each run). I did not run anything for this update. The earlier expected plans were replaced by these.

| Id | Statement | Real plan | Time | Verdict |
| --- | --- | --- | --- | --- |
| S1 | scope build, wards (`select ward_id ... where membership_id = :m and removed_at is null`) | `Index Only Scan using ix_ward_assignment_member_live` (Index Cond tenant, membership), Heap Fetches 2 | 0.09 to 0.10 ms | as designed |
| S2 | scope build, grants (`LIVE_GRANTS`), nurse with 5 grants | `Nested Loop`: `Index Only Scan using ix_emergency_access_grant_member_expires` (Index Cond tenant + membership only; `role` and `expires_at > clock_timestamp()` are a Filter, 4 rows removed), then `Index Scan using uq_admission_tenant_id` (Filter status) | 0.20 to 0.22 ms | works; **F-1** (the note said the range was an index condition; it is not) |
| S2b | same, nurse with 3,650 old grants | `Bitmap Index Scan` of 3,650 index entries, 90 to 92 heap blocks, Filter removes all 3,650 | 1.4 to 2.3 ms | **F-1**, fix in 10.3 |
| N | narrow admitted-set read (`ScopeGuard.visibleAdmissionIds`, 300 rows) | `Hash Join`: `Bitmap Heap Scan on admission` via **`ux_admission_tenant_bed_admitted`** (Recheck tenant_id and status, 300 rows, 8 blocks) joined to `Bitmap Heap Scan on bed` via `ix_bed_tenant_category` (300 rows) | 0.5 to 1.2 ms (1.6 ms with seqscan off) | acceptable. The earlier text named `ix_admission_tenant_status_admitted_at`; the planner prefers the partial unique index on `(tenant_id, bed_id) where status = 'ADMITTED'` because it is smaller and covers the join key. Both are correct for this read; no index change |
| C1 | `admitted` count | `Index Only Scan using ux_admission_tenant_bed_admitted` (Heap Fetches 300: the test table was not vacuumed; a vacuumed table shows 0) | 0.3 to 0.9 ms | as designed (same index as N) |
| R4 | clinical events, nurse with 2 wards and 1 grant, limit 51 | in the QA table: `Index Scan using ix_clinical_event_tenant_at` (no sort), admission and bed by key, grant by key, 639 events read for 51 rows, 13 ms. **In the output file on disk the same statement shows the other shape:** `Bitmap Heap Scan` of all 36,000 events, hash joins, top-N sort, 28.6 ms (51.7 ms with seqscan off) | 13 to 29 ms | accepted pattern of SAH-33, but the planner flips between the two shapes; see F-2 |
| R4b | same, nurse with no ward and no grant (own entries only) | same ordered index scan; 951 events read, 142,308 to 284,138 join-filter removals (each scanned event is checked against the 300 admissions) | 28 to 34 ms | **F-2** |
| R4c | same, OWNER (no predicate; as before SAH-34) | `Index Scan using ix_clinical_event_tenant_at`, 51 rows read | 1.9 to 3.9 ms | baseline |
| U1 | unreviewed count | `Index Only Scan using ix_emergency_access_grant_unreviewed` | 0.10 to 0.25 ms | as designed |
| U2 | first page, limit 26 | `Index Scan using ix_emergency_access_grant_unreviewed`, no Sort; bed and admission by key | 0.27 to 0.31 ms | as designed |
| U2b | keyset page | same, row comparison as Index Cond | 0.27 to 0.45 ms | as designed |
| U2c | unreviewed ids (change token) | partial index scan | 0.11 to 0.2 ms | as designed |
| U3 | review (`MARK_REVIEWED`) | `Index Scan using uq_emergency_access_grant_tenant_id` (Filter `reviewed_at is null`); the guard trigger is 1.1 ms of 1.8 ms in the file, 2.0 to 2.6 ms of 3.2 to 3.9 ms in the QA table | 1.6 to 3.9 ms | as designed. The plan uses `uq_..._tenant_id`, not the primary key: same columns plus `id`, no difference |

Times differ between runs of the same plan (warm cache, a cold read of 151 blocks in one R4 run); the shapes are what counts. One more number from the same output file: `GET /clinical-events?limit=50` for the scoped nurse took 200 ms best-of-5 through MockMvc at this volume, against 5 ms for the owner and 28 ms for the statement alone in `explain`. That gap of about 170 ms was not explained by the plans above (the explain runs use literal values; the app sends bound parameters). **Explained and fixed on 10 Nov 2026: see 10.5 (a text-literal array cast inside `any()`).** The R4 rows above were taken before the fix and show the old statement text; QA re-runs them against the new `SCOPED_NURSE`.

**Not applicable, on purpose (design note 1.5, ADR-017):** R1 (admitted list with the ward and grant predicate), R2 (escalations with the predicate), R3 (doses due with the predicate) and C2 (other-wards past-target count). The lists read the unscoped admitted set once (statement N) and filter in memory; the other-wards count is the clinic count minus the in-scope count in the same snapshot. No statement carries `ward_id = any(:wards) or a.id = any(:grants)` except the clinical-event page (R4). Revisit trigger (design note 1.5): one clinic's ADMITTED set above about 2,000 rows, or p95 of `GET /admissions` or `GET /safety` above 300 ms for a scoped nurse; then R1 to R3 become the plans.

### 10.1 No index added in this migration beyond the four it created
No sequential scan of a large table in any plan. Two items below (F-1, F-2) needed action; the Java parts of both are now done (10 Nov 2026, see 10.3 and 10.4), and only F-2 has an index, which stays a proposal for later with its build trigger. 10.5 records the cause of the 170 ms request time.

### 10.2 F-3: the text
Corrected above (the admitted-set read uses `ux_admission_tenant_bed_admitted`; S2's `expires_at` is a filter, not an index condition; U3 uses the `tenant_id, id` unique index). The design note section 1.5 says "an index scan of `ix_admission_tenant_status_admitted_at`" for the narrow read; that line is the architect's to correct (`docs/architecture/`, not mine).

### 10.3 F-1: the grants probe reads every grant a member ever opened
**Status (10 Nov 2026): Java part done.** The java-developer added the bound to `LIVE_GRANTS` and `HAS_LIVE_GRANT`; the unit test now asserts the exact `clock_timestamp()` test plus exactly one `now()`. No index change was needed or made. Still open for qa-engineer: the `ScopePlansIT` S2b check below (Index Cond on `expires_at`, zero entries read), which is an expectation until it is re-run.

**Cause.** `expires_at > clock_timestamp()` is volatile, so PostgreSQL cannot use it as an index range; it is a Filter. The probe reads all index entries of `(tenant_id, membership_id)`: 5 for a nurse with five grants (0.2 ms), 3,650 for a nurse with a year of daily grants (1.4 to 2.3 ms), 365 more a year for as long as the person works there, on every scoped request and on every scoped write (`HAS_LIVE_GRANT` under the admission lock).

**QA's suggestion is right.** Add the implied, stable bound `g.expires_at > now() - interval '1 minute'` next to the exact test. `now()` is stable (the start of the transaction), so the planner uses it as an Index Cond on the third key column of `ix_emergency_access_grant_member_expires (tenant_id, membership_id, expires_at desc)`, and the scan stops at the live entries.

**Why it cannot change the result.**
- The bound is ANDed with the exact test; it can only remove rows. It never adds one.
- A row that passes the exact test has `expires_at > clock_timestamp()`. `clock_timestamp()` is read during the statement, `now()` at the start of the transaction, so `clock_timestamp() >= now()` and therefore `expires_at > now()`, which is stricter than the bound. Every live row passes the bound. The `- interval '1 minute'` is a margin for a system clock that is stepped backwards (NTP), so the bound stays weaker than the exact test even then.
- Under the admission lock the transaction may have started before the lock was granted, so `now()` is older and the bound weaker still: still a pure pre-filter. The same holds inside the REPEATABLE READ transaction of the lists.
- The database-clock rule stays exact: the decision is still `g.expires_at > clock_timestamp()`, evaluated in SQL at the moment of the statement, and `expires_at` is still never compared with a Java `Instant`. `now()` appears only in the bound.

**The SQL change for the java-developer** (`JdbcWardScopeRepository.LIVE_GRANTS` and `HAS_LIVE_GRANT`; add one line to each, keep every other condition):
```
 where g.tenant_id = :tenantId and g.membership_id = :membershipId and g.role = :role
   and g.expires_at > now() - interval '1 minute'   -- pre-filter for the index range; implied by the next line
   and g.expires_at > clock_timestamp()             -- the exact, authoritative test (database clock)
   and a.status = 'ADMITTED'
```
(`HAS_LIVE_GRANT` also keeps `and g.admission_id = :admissionId`.) Put a short comment in the Java text block saying the first line is only a pre-filter and must never replace the second.

**Test changes that follow.** `JdbcWardScopeRepositoryTest` line 18 asserts `.doesNotContain("now()")`; that guard exists to stop `now()` being used as the liveness test. Replace it with: the SQL contains `g.expires_at > clock_timestamp()`, contains exactly one `now()`, and that one is the text `g.expires_at > now() - interval '1 minute'`. Add to `ScopePlansIT` (qa-engineer) that S2b's plan shows `expires_at > (now() - ...)` in the Index Cond and reads 0 index entries, and keep the expiry ITs (they prove the 30-minute edge on the database clock) unchanged.

**Index change: none.** The existing index already has `expires_at` as the third key (descending) and holds `admission_id, role` as included columns, so the probe stays index-only. Expected after the change (not run): S2b shows `Index Only Scan using ix_emergency_access_grant_member_expires`, Index Cond `tenant_id, membership_id, expires_at > (now() - '00:01:00')`, Filter `role` and `clock_timestamp()`, zero rows removed, about 0.1 ms whatever the age of the member's history. S2 unchanged at about 0.2 ms.

### 10.4 F-2: `GET /clinical-events` for a scoped nurse
**Status (10 Nov 2026): Java part done; the index stays a proposal.** The java-developer implemented part 1 below: a nurse with no ward and no grant gets the `ACTOR_ONLY` page (`e.actor = :nurse` and nothing else; unit test `JdbcClinicalEventRepositoryScopeTest`). The cost-per-scanned-event figures in this section describe the statement before that change and before the fix in 10.5. The index (part 2) is **not built**; its migration text and its build trigger (a, b, c below) are unchanged. The trigger question "why does the endpoint take 200 ms" is answered in 10.5: it was the array cast, not the statement shape, so trigger (b) had not fired for that reason.

**Finding.** The predicate is `e.actor = :nurse or (a.status = 'ADMITTED' and (b.ward_id = any(:wards) or a.id = any(:grants)))`, with `a` and `b` left-joined. The plan walks `ix_clinical_event_tenant_at` backwards and checks each event against the 300 admissions. A nurse with no ward and no grant gets only her own entries, found one scanned event at a time: 951 events scanned for 51 rows, 28 to 34 ms, and about 0.03 ms per scanned event because of that join check.

**What is worse than the QA table shows (calculation, not a measurement).** The scan stops after 51 matches. A nurse whose own entries are 5% of the log scans about 1,000 events at any log size. A nurse with fewer than 51 own entries (a new nurse, or one who only reads) scans the whole log: 36,000 events x about 0.03 ms is about 1 s now and about 30 s at a million events. R4b did not measure that nurse. QA should add that case (R4d: scoped nurse, no ward, no grant, no entries of her own) to `ScopePlansIT`.

**An actor index alone does nothing for the current SQL.** The `or` mixes a column of `clinical_event` with columns of the left-joined tables, so PostgreSQL cannot use `(tenant_id, actor, ...)` for it. The cure has two parts.

1. **Java, no schema change, now (recommended to the java-developer):** when the scope has no wards and no grants, the predicate is exactly `e.actor = :nurse` (an empty `any('{}')` is false, so the right side of the `or` is false). Send that and nothing else: `where e.tenant_id = :tenantId and e.actor = :nurse [and keyset] order by e.at desc, e.seq desc limit :limit`, with the same left joins for the display columns, which then run only on the 51 returned rows. That removes the 300-row check per scanned event (expected 10 to 30 times cheaper per event; not run). It returns the same rows as the current statement. A nurse with wards or grants keeps the current statement.
2. **Index, when the trigger fires:** with the shortcut in place, an actor index makes the empty-scope case read about 51 entries whatever the log size.

**Decision: do not build the index now; the migration text is ready below.** Reasons: the table is the fastest-growing IPD table after vitals and doses and an index adds a write to every event insert (SAH-33 already declined it for that reason); the Java shortcut removes most of the cost with no schema change; a scoped nurse without a ward is a configuration gap that an owner closes, not a steady state. **Build it when either holds:** (a) one clinic's `ipd.clinical_event` reaches about 1,000,000 rows (about 2.3 years of the QA volume of 36,000 events a month for a 300-bed hospital), or (b) the p95 of `GET /clinical-events` for a scoped nurse exceeds 300 ms in a real hospital (the same line as design note 1.5), or (c) the R4d case above measures over 100 ms before then. The 200 ms request time was investigated and explained (10.5); it is not a reason to build the index. After the fix, QA measures the scoped-nurse request again (about 10 ms or less is expected) and R4d; the triggers apply to those new numbers.

**Index size and cost (estimate).** Key `(tenant_id uuid, actor uuid, at timestamptz, seq bigint)`: about 56 bytes an entry, about 25 MB a year for a 300-bed hospital (440,000 events), about 56 MB per million events. One extra index insert per event. Platform-wide at that volume 100 hospitals add about 44 million events a year, so `ipd.clinical_event` approaches the 50 million line within about a year of full rollout: plan the monthly partitioning of this table together with this index (an index on a partitioned table is created per partition, then attached to the parent; `concurrently` is not allowed on the parent itself).

**Migration text (a proposal; there is no file for it in `backend/src/main/resources/db/migration/`).** File name when it is built: `V<YYYY>_<MM>_<DD>_<HHMM>__ipd_clinical_event_actor_index.sql`, dated after the latest migration at that time.
```sql
-- IPD: actor index for the clinical-event log of a nurse with no ward and no grant (SAH-34 F-2).
-- This file holds only statements that cannot run in a transaction. Do not add any other statement to it
-- (a comment on the index, a grant, an analyze): Flyway refuses a script that mixes the two kinds.
drop index concurrently if exists ipd.ix_clinical_event_tenant_actor_at;   -- clears an INVALID leftover of an earlier failed run
create index concurrently if not exists ix_clinical_event_tenant_actor_at
  on ipd.clinical_event (tenant_id, actor, at desc, seq desc);
```
**How it runs under this project's Flyway setup.** `application.yml` sets only `locations`, `schemas`, `default-schema`, `create-schemas: false`, `clean-disabled: true` for Flyway; there is no `mixed` or `group` setting and no script configuration file anywhere, so every migration runs inside a transaction today. Flyway (the 11.x line that Spring Boot 3.5.16 manages) reads a PostgreSQL script, detects `create index concurrently` and `drop index concurrently`, and then runs the whole script outside a transaction. Therefore:
- the file may contain only those two statements (the `comment on index` is left out for that reason);
- no setting needs to change;
- if Flyway did not detect it (the migration fails with "cannot run inside a transaction block"), the fallback is a script configuration file next to the migration, `V<...>__ipd_clinical_event_actor_index.sql.conf`, containing `executeInTransaction=false`; I could not confirm which Flyway edition honours this file, so it is a fallback, not the plan;
- **precedent: none.** `V2026_11_25_1000`, `V2026_11_29_1000` and `V2026_12_02_1000` contain the word "concurrently" only in comments (V2026_12_02 records the choice of a plain `create index` on a small table). This would be the first migration to run one, so the first run must be proved: `node scripts/validate.mjs db` applies every migration from scratch to an empty `sahaj_test`, which proves the file runs, but not the lock behaviour on a big table; run it once on a copy with realistic volume before a release that carries it;
- `sahaj_migrator` owns the table, which is what `create index` needs;
- a concurrent build waits for transactions that started before it and does not block writers; it takes seconds at 1,000,000 rows, but Flyway runs at application start, so a rolling deploy waits for it. A failed build leaves an INVALID index (`pg_index.indisvalid = false`); the leading `drop index concurrently if exists` is there so a repaired re-run starts clean (`if not exists` alone would silently skip the broken one).

**Plan to expect afterwards (not run):** for `e.actor = :nurse` with the Java shortcut, `Limit` over `Index Scan using ix_clinical_event_tenant_actor_at` (Index Cond tenant, actor), no Sort, about 51 entries, left joins by key on the returned rows, under 5 ms.

### 10.5 The unexplained 170 to 200 ms on `GET /clinical-events` (cause and fix, 10 Nov 2026)
**Symptom.** For a scoped nurse the endpoint took 170 to 200 ms more than the same statement in `explain` (200 ms through MockMvc against 5 ms for the owner and 28 ms for the statement alone). The plan did not change between the two; the difference was in how the parameter was sent.

**Cause.** The list of wards was bound as a text literal and cast in SQL: `b.ward_id = any(cast(:wards as uuid[]))`. A cast from a text parameter to `uuid[]` is a stable function call, not a constant the planner folds, and inside the join filter it was evaluated again for every row of the event log that the filter touched. With 36,000 rows that is 225 to 230 ms; the same statement with the array built once takes about 9 ms. The `explain` runs used literal arrays, which the planner folds at plan time, so they never showed it. The cost grows with the number of rows scanned, not with the number of wards.

**Evidence** (probe by the java-developer, 36,000 events, ten runs each, milliseconds; no database was touched for this note):

| Variant | Typical time | Source |
| --- | --- | --- |
| A. `= any(cast(:w as uuid[]))`, bound as text (the old statement) | 222 to 283 ms | `backend/target/sah34-probe2.txt` |
| B. `in (select unnest(cast(:w as uuid[])))`, bound as text | 8.3 to 9.8 ms | `sah34-probe2.txt` |
| C. a real `UUID[]` parameter (`any(?)`) | 6.1 to 6.8 ms (first run 10.3) | `sah34-probe2.txt` |
| D. the cast in a CTE cross-joined | 225 to 257 ms (no help) | `sah34-probe2.txt` |
| E. `in (...)` list expansion | 5.9 to 6.6 ms | `sah34-probe2.txt` |
| first probe, text cast vs `UUID[]`, `plan_cache_mode` auto and `force_generic_plan` | 237 to 324 ms against 6.5 to 6.9 ms; the plan cache setting makes no difference | `backend/target/sah34-probe.txt` |

**Fix (done by the java-developer).** `JdbcClinicalEventRepository.SCOPED_NURSE` now reads `b.ward_id in (select unnest(cast(:wards as uuid[])))`, and the same for `:grants`. The cast is then inside an uncorrelated subquery: PostgreSQL evaluates it once as an InitPlan and hashes the result. The rows returned are the same as before (checked by the identical-rows IT that QA adds). Expected request time for the scoped nurse: about 10 ms or less (to be confirmed by `ScopePlansIT`).

**The same pattern is still in two other statements. Not changed, not measured yet:**
- `JdbcMedicineRoundRepository.DOSES_BETWEEN`: `d.admission_id = any(cast(:admissionIds as uuid[]))` over `ipd.dose_administration`, a table that grows faster than the event log (the MAR). Here the cost would be per dose row scanned, so it is the one to measure first: QA or the db-engineer on a realistic dose table (a 300-bed hospital for 30 days). Fix when needed is the same as above (`in (select unnest(cast(:admissionIds as uuid[])))`) or a typed array.
- `JdbcWardScopeRepository.GRANTEES`: `id = any(cast(:ids as uuid[]))` over `ipd.emergency_access_grant`. Narrower: it reads at most one page of ids (the list limit) through `(tenant_id, id)`, so the cast runs over a few rows. Low risk; change it the next time the file is touched.

**Rule for the design notes (applies to every story).** *Bind uuid arrays through `unnest` or a typed array, not a text literal cast inside `any()`.* In Spring `JdbcClient` or `NamedParameterJdbcTemplate` that means either `in (select unnest(cast(:ids as uuid[])))` or passing a `java.util.UUID[]` (a real array parameter) to `= any(:ids)`. Never `= any(cast(:ids as uuid[]))` with a string. Design-note plans must be taken with the parameter shape the application sends: a literal array in `explain` can hide this cost. Reviewers: flag any `any(cast(` in a statement that runs over a table of more than a few thousand rows.

### 10.6 Verdict
Three changes done in Java, no schema change: F-1 (one extra line in two statements), F-2 part 1 (`ACTOR_ONLY` for no ward and no grant), and the array fix of 10.5 (`SCOPED_NURSE`). One index held back with a written migration and a build trigger (F-2 part 2). Open: re-run `ScopePlansIT` against the new statements, and measure `DOSES_BETWEEN`. R4 remains the only plan that grows with history, as noted in SAH-33.

## 11. Checks run
`node scripts/validate.mjs db` on 9 Nov 2026 (file-name and RLS check, then `mvn verify`, which cleans `sahaj_test` and applies every migration from scratch, this one included): `validate db: PASSED`, exit code 0. The 10 Nov 2026 updates to sections 3 and 10 (including 10.5, the array-cast cause) are documents only: no migration was added or changed, and no database, psql, Maven or validate command was run for it. The plans in section 10 are the qa-engineer's real plans from `ScopePlansIT`; the plans marked "not run" after F-1 and F-2 are expectations to be confirmed by re-running it.
