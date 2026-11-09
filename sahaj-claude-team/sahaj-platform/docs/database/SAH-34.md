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
| `ix_emergency_access_grant_member_expires` `(tenant_id, membership_id, expires_at desc) include (admission_id, role)` | the scope build's grant probe (`membership_id = :m and role = :role and expires_at > clock_timestamp()`, joined to an ADMITTED admission by primary key) and the same probe re-run under the admission lock for writes. Index-only; the range `expires_at > now` is the first entries of the descending scan |
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

**The grant clock (for the scope build and the locked recheck):** compare `expires_at > clock_timestamp()` in SQL, never `expires_at` against a Java `Instant.now()` and never `now()`. `now()` is the transaction start; the check must be the live database clock, both at request start and again under the admission lock. A grant row is live for `[opened_at, opened_at + 30 min)`. An index cannot hold "not expired", because `now()` is not immutable; that is why the duplicate-grant rule is the admission row lock, not a unique index.

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

## 10. Query plans (design note section 7.5)

**How these were produced: expected plans, not run.** No `psql` is allowed for this task and the backend lock was held by the java-developer's run, so a new migration could not be applied to `sahaj_test` while its tests ran. The statements below are exact so qa-engineer or the java-developer can run them as `sahaj_app` against a seeded `sahaj_test` (300 admitted admissions, 26 wards, about 12 beds per ward, 40 nurses, 20 live grants of which 5 unreviewed) and replace this section with real output. Setup common to all: `begin; set local app.tenant_id = '<tenant uuid>'; set local role sahaj_app; analyze;` (RLS adds `tenant_id = platform.current_tenant()`; the function is `stable`, so the planner folds it into the index condition). On an empty or one-row table the planner may choose a plain scan; the shapes hold after `analyze` on realistic data, or with `set enable_seqscan = off` for the small tables.

Bind values: `:m` a NURSE membership, `:wards` the uuid array from probe S1, `:grants` the uuid array from probe S2.

**S1 · scope build, wards** (0 or 1 per request, NURSE with the switch on only)
```
select ward_id from ipd.ward_assignment where membership_id = :m and removed_at is null;
```
Expected: `Index Only Scan using ix_ward_assignment_member_live on ward_assignment (Index Cond: tenant_id = current_tenant AND membership_id = :m)`, Heap Fetches 0 after vacuum (a few on a fresh table). 1 probe, 1 to 5 rows, well under 1 ms.

**S2 · scope build, grants** (every scoped caller)
```
select g.admission_id, g.expires_at
from ipd.emergency_access_grant g
join ipd.admission a on a.tenant_id = g.tenant_id and a.id = g.admission_id
where g.membership_id = :m and g.role = 'NURSE' and g.expires_at > clock_timestamp() and a.status = 'ADMITTED';
```
Expected: `Nested Loop`; outer `Index Only Scan using ix_emergency_access_grant_member_expires` (Index Cond: tenant_id, membership_id, `expires_at > clock_timestamp()`; Filter: role), inner `Index Scan using uq_admission_tenant_id` (Filter: status). `clock_timestamp()` is volatile, so it is evaluated per row, not folded: with the descending index the scan reads the newest entries of the membership and the range is a filter on the index tuple; a member has a few grants a month, so it reads under 10 entries. Under the admission lock the guard re-runs this same probe, narrowed to the one admission.

**R1 · Admitted list with the predicate** (`GET /admissions`, scope WARDS)
```
select a.id, a.bed_id, b.code, b.ward_id, a.doctor_membership_id, a.admitted_at
from ipd.admission a join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id
where a.status = 'ADMITTED' and (b.ward_id = any(:wards) or a.id = any(:grants))
order by a.admitted_at, a.id;
```
Expected: `Sort` over `Nested Loop` (or `Hash Join` for 300 rows): `Index Scan using ix_admission_tenant_status_admitted_at` (Index Cond tenant_id, status = 'ADMITTED'; 300 rows), join to bed by `uq_bed_tenant_id` (or a hash of the clinic's beds from `ix_bed_tenant_ward`), `Join Filter: ward_id = ANY(...) OR a.id = ANY(...)`. The predicate is a filter on rows already read, not an extra access path: the cost is the unscoped list read (300 admission rows, 300 bed probes), unchanged by the predicate; rows removed by the filter are the out-of-scope ones. Under 5 ms at 300 admitted. This is acceptable because the admitted set per hospital is bounded (hundreds), not the history.

**R2 · Escalations with the predicate** (Safety board, NURSE)
```
select e.id, e.admission_id, e.status, e.raised_at
from ipd.ews_escalation e
join ipd.admission a on a.tenant_id = e.tenant_id and a.id = e.admission_id
join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id
where e.status <> 'DONE' and a.status = 'ADMITTED' and (b.ward_id = any(:wards) or a.id = any(:grants));
```
Expected: `Index Scan using ux_ews_escalation_tenant_admission_open` (partial, `status <> 'DONE'`; Index Cond: tenant_id) giving the open escalations of the clinic (a handful), nested loops to `uq_admission_tenant_id` and `uq_bed_tenant_id`, the predicate as a join filter. Cost proportional to open escalations, not to history.

**R3 · Doses due with the predicate** (medicine round, NURSE): the round reads the ACTIVE orders of admitted patients in scope and the doses recorded in the window.
```
select o.id, o.admission_id from ipd.medicine_order o
join ipd.admission a on a.tenant_id = o.tenant_id and a.id = o.admission_id
join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id
where o.status = 'ACTIVE' and a.status = 'ADMITTED' and (b.ward_id = any(:wards) or a.id = any(:grants));
```
Expected: admitted admissions by `ix_admission_tenant_status_admitted_at`, bed by key, then `Index Scan using ix_medicine_order_tenant_admission_ordered` (tenant_id, admission_id) per kept admission; the predicate removes admissions before the order probe because the filter sits on the admission/bed join, so a nurse with 2 wards probes about 25 admissions, not 300. Doses: `Index Scan using ix_dose_administration_tenant_admission_slot` per kept admission, bounded by the slot window. No new index.

**R4 · Clinical events with the predicate** (`GET /clinical-events`, NURSE: own entries plus entries of admissions in scope)
```
select ce.id, ce.at, ce.seq, ce.kind from ipd.clinical_event ce
left join ipd.admission a on a.tenant_id = ce.tenant_id and a.id = ce.admission_id
left join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id
where ce.actor = :user or b.ward_id = any(:wards) or ce.admission_id = any(:grants)
order by ce.at desc, ce.seq desc limit 50;
```
Expected: `Limit` over `Nested Loop Left Join` over `Index Scan Backward using ix_clinical_event_tenant_at` (no sort; the order is the index order), with the predicate as a join filter. Cost depends on how fast 50 qualifying rows are found: a nurse's wards hold a share of events (about 1/26 per ward plus her own), so the backward scan may read a few hundred to a few thousand rows before the limit. This is the case SAH-33 already accepted for the nurse filter (see `docs/database/SAH-33.md`, "Index NOT added: ix_clinical_event_tenant_actor_at"); the same revisit trigger applies (more than about 1,000 rows filtered per page on a real hospital: then add the actor index `(tenant_id, actor, at desc, seq desc)` in its own `create index concurrently` migration, or read per admitted admission in scope through the existing `ix_clinical_event_tenant_admission_at`). No index added now.

**C1 · `admitted` count** (`scope.admitted`, every role)
```
select count(*) from ipd.admission where status = 'ADMITTED';
```
Expected: `Aggregate` over `Index Only Scan using ix_admission_tenant_status_admitted_at` (Index Cond: tenant_id, status = 'ADMITTED'): 300 index entries, no heap rows loaded into the answer, under 1 ms.

**C2 · other-wards past-target count** (`otherWardsPastTarget`, NURSE)
```
select count(*) from ipd.ews_escalation e
join ipd.admission a on a.tenant_id = e.tenant_id and a.id = e.admission_id
where e.status = 'OPEN' and a.status = 'ADMITTED'
  and e.raised_at + make_interval(mins => e.target_minutes) < clock_timestamp();
```
(minus the in-scope ones, counted by the same statement with the predicate, in the same snapshot.) Expected: as R2 without the predicate: partial unique index scan of the open escalations of the clinic, `uq_admission_tenant_id` probe each, `Aggregate`. A handful of rows.

**U1 · unreviewed count** (owner tile and tab number)
```
select count(*) from ipd.emergency_access_grant where reviewed_at is null;
```
Expected: `Aggregate` over `Index Only Scan using ix_emergency_access_grant_unreviewed` (Index Cond: tenant_id). The partial index holds only unreviewed grants, so it is as small as the work waiting (normally under 50 entries).

**U2 · unreviewed first page and keyset** (owner panel, 25 rows)
```
select id, admission_id, bed_id, membership_id, role, reason_key, opened_at, expires_at
from ipd.emergency_access_grant
where reviewed_at is null and (opened_at, id) > (:cursor_at, :cursor_id)
order by opened_at, id limit 26;
```
Expected: `Limit` over `Index Scan using ix_emergency_access_grant_unreviewed` (Index Cond: tenant_id and the row comparison on `(opened_at, id)`), no `Sort`; reads 26 entries and their heap rows. The first page has no cursor predicate. If the planner shows an `Index Only Scan` it is because of the visibility map; the extra columns come from the heap.

**U3 · review**
```
update ipd.emergency_access_grant set reviewed_by = :me, version = version + 1 where id = :id and reviewed_at is null;
```
Expected: `Update` over `Index Scan using emergency_access_grant_pkey` (RLS adds the tenant filter). One row or none. The partial index entry is removed by the update (HOT is not possible because `reviewed_at` is in the index predicate, which is the intent).

Verdict: no sequential scan on a large table, no new index beyond the four in this migration. The only plan that can grow with history is R4 (clinical events), inherited from SAH-33 with a stated trigger to revisit.

## 11. Checks run
`node scripts/validate.mjs db` on 9 Nov 2026 (file-name and RLS check, then `mvn verify`, which cleans `sahaj_test` and applies every migration from scratch, this one included): `validate db: PASSED`, exit code 0. The plans in section 10 were not run (expected plans only).
