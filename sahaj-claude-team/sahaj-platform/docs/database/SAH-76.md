# SAH-76 · IPD nursing integration, Sprints 1-2 · database design

Source: ADR-027 "Data needed" items 1 to 17 and D1 to D15, `docs/api/ipd.yaml` 0.4.0, stories `docs/stories/SAH-76.md`. Module: ipd only (no cross-module foreign key except `platform.tenant`).
Migration (one file, forward-only, nothing earlier edited): `backend/src/main/resources/db/migration/V2026_12_15_1000__ipd_nursing_orders_to_execution.sql`.
Demo content tag: "unit, route and code lists and per-drug units are demo content for sign-off, not clinically reviewed (OQ-76-8, Q3)".

## 0. Safety summary
- No existing tenant row is updated (no UPDATE on `medicine_order`, `dose_administration`, `vitals`, `ward`, `config`). New columns on tenant tables are nullable or constant-default (no rewrite).
- The only rows derived from old tenant rows: **version 1 for every existing medicine order** (INSERT into the new version table; the head is untouched). The migrator has no RLS policy on the head, so `FORCE` is lifted on `ipd.medicine_order` for that one read and restored in the same transaction.
- Only UPDATEs: the 14 global `ipd.drug` rows (`route_key`, `dose_units`).
- **Five constraints are dropped and re-added under the same name, all only loosened or widened** (no data touched): `ck_medicine_order_status`, `ck_medicine_order_fields_by_status`, `ck_dose_administration_reason`, `ck_dose_administration_identity_scan_by_outcome`, `uq_dose_administration_order_slot` (now includes `attempt`). Plus `ALTER COLUMN frequency_key DROP NOT NULL` on `ipd.medicine_order`. These cannot be done additively; they are what ADR-027 items 1 and 7 ask for. The old narrower unique index `ux_medicine_order_tenant_admission_drug_live` and the pending-only queue index are **kept** (redundant; drop in a later release).
- Backward compatibility: the previous app version breaks on the stop path (a stop now needs `stop_reason`, guard 23514), on `uq_dose_administration_order_slot` semantics only if it relied on one row per slot (it does not break), and on PRN or ONCE orders it cannot create. Ship backend, frontend and migration together (ADR-027 consequences).

## 1. Tables and columns

### 1.1 `ipd.drug` (global, alter) and two new global tables
| Table | Column | Type | Null | Default | Check |
| --- | --- | --- | --- | --- | --- |
| `drug` | `time_critical` | boolean | no | false | none shipped |
| `drug` | `dose_units` | text[] | no | `'{}'` | `ck_drug_dose_units` elements in unit list |
| `drug` | `route_key` | text | no (after update) | | `ck_drug_route_key` in route list |
| `nursing_profile_template` | `key` pk, `name` (unique), `med_verification_required`, `vitals_every_min` 15-1440, `sort_order` | | | | seeds `icu` / "ICU" / true / 60 / 1 and `general-ward` / "General ward" / true / 240 / 2 |
| `drug_code` | `code` pk (`^[A-Z0-9-]{4,64}$`), `drug_key` FK `ipd.drug`, `source` (`^[A-Z_]{1,20}$`) | | | | **shipped empty** |

Unit list: TABLET, CAPSULE, MG, G, MCG, ML, IU, UNIT, MMOL. Route list: ORAL, IV, IV_INFUSION, IM, SC, SUBLINGUAL, INHALED, TOPICAL, RECTAL, NASOGASTRIC. Per-drug units (demo): tablets (pcm, amox, cotri, asp, ibu, met) TABLET, MG; panto, ondan ML, MG; ceft, pip ML, MG, G; ns, kcl ML; hep IU; ins UNIT. Route map: Oral to ORAL, IV to IV, IV infusion to IV_INFUSION, SC to SC.
Both new tables: RLS enabled and forced, `p_<t>_select` (sahaj_app, select) and `p_<t>_migrator` (all); `sahaj_app` gets select only; immutable by trigger (insert only, by a later migration).

### 1.2 `ipd.config` (alter, tenant, lazy row)
`med_verification_required` bool true; `dose_window_before_min` 60 (1-180); `dose_window_after_min` 60 (1-180); `stat_window_min` 15 (1-120); `unack_flag_min` 30 (1-240); `unack_flag_stat_min` 5 (1-60); `undo_seconds` 10 (1-10). All not null with the defaults. Table-level grants of the earlier migration cover them.

### 1.3 `ipd.nursing_profile` (new, tenant)
`id`, `tenant_id`, `key` (`^[a-z0-9-]{2,40}$`), `name` (1-60), `med_verification_required`, `vitals_every_min` (15-1440), `dose_window_before_min`, `dose_window_after_min` (null or 1-180), `sort_order` (default 100), `created_at`, `updated_at`, `updated_by` (pair). Unique `(tenant_id, key)` (serves the ward FK); unique `(tenant_id, lower(name))`. Cap of 20 per clinic by trigger. Grants: select; insert and update on the listed columns; **no delete**. RLS select, insert, update.
`ipd.ward.nursing_profile_key` text null, FK `(tenant_id, nursing_profile_key)` to the profile; partial index for the FK child side.
`ipd.vitals.due_interval_min` int null (15-1440); insert grant added; vitals stay append-only.

### 1.4 `ipd.medicine_order` (head, alter)
New: `current_version` int not null default 1 (>= 1), `priority` text not null default ROUTINE (ROUTINE, URGENT, STAT), `stop_reason` text null (1-200). `status` now PENDING, QUERIED, ACTIVE, ON_HOLD, REJECTED, STOPPED. `frequency_key` is now **nullable** (PRN and STAT orders have none; version 1's frequency, per-version truth is on the version row). The head `verified_by/at` means "last verification of any version"; the version row is the truth per version.
Indexes added: `ux_medicine_order_tenant_admission_drug_live4` (unique, PENDING, QUERIED, ACTIVE, ON_HOLD; duplicate backstop) and `ix_medicine_order_tenant_pending_queried` (queue, keyset, partial PENDING and QUERIED).
Grants added: insert `status, priority`; update `current_version, priority, stop_reason` (verified_at, rejected_at, stopped_at stay trigger-stamped).

### 1.5 `ipd.medicine_order_version` (new, tenant, never deleted)
`id`, `tenant_id`, `order_id`, `version_no` (>= 1), `kind` (SCHEDULED, PRN, ONCE), `frequency_key` (od, bd, tds, q8; set iff SCHEDULED), `dose_amount` numeric(10,3) > 0 and `dose_unit` (unit list; both null or both set), `route_key` (route list), `start_at`, `end_at` (> start), `indication` (1-200; required for PRN), `priority`, `min_interval_min` and `max_per_24h` (> 0; set iff PRN), `change_reason` (1-200; null iff version 1), `created_by`, `created_at` (clock), `superseded_at`, `verification` (REQUIRED, NOT_REQUIRED), `verified_by`, `verified_at` (pair; REQUIRED only). Extra check: `priority = STAT` implies `kind = ONCE`.
Keys: pk `id`; unique `(tenant_id, order_id, version_no)` (the timeline read and the target of the foreign keys of dose, notice, query, review); FK `(tenant_id, order_id)` to the head.
Grants: select; insert of everything except `id`, `tenant_id`, `created_at`, `superseded_at`, `verified_*`; update `superseded_at, verified_by` only.

### 1.6 `ipd.order_hold`, `ipd.order_notice`, `ipd.medicine_order_query`, `ipd.pharmacist_review` (new, tenant)
| Table | Columns | Keys and indexes (query served) |
| --- | --- | --- |
| `order_hold` | `order_kind` (default MEDICINE, check = MEDICINE), `medicine_order_id`, `held_at` (clock), `held_by`, `hold_reason` (1-200), `resumed_at`, `resumed_by` (pair) | check exactly one order column set; partial unique one open hold per order; `ix_order_hold_tenant_medicine_held (tenant_id, medicine_order_id, held_at)` (timeline) |
| `order_notice` | `admission_id`, `order_kind`, `medicine_order_id`, `order_version`, `kind` (NEW, CHANGED, HELD, RESUMED, STOPPED), `priority`, `created_by`, `created_at`, `acknowledged_by/at/via` (TAP, DOSE; triple set together) | FK `(tenant_id, medicine_order_id, admission_id)` to the head (same admission) and `(tenant_id, medicine_order_id, order_version)` to the version; partial `(tenant_id, admission_id, created_at) where acknowledged_at is null` (badges, summary); `(tenant_id, admission_id, created_at desc, id)` (Orders tab); `(tenant_id, medicine_order_id)` (ack by dose) |
| `medicine_order_query` | `order_id`, `order_version`, `question` (1-300), `asked_by`, `asked_at`, `reply` (1-300), `replied_by`, `replied_at`, `closed_by` (REPLY, CHANGE, STOP, REJECT), `closed_at` | check: reply set iff closed_by = REPLY; partial unique one open query per order; `(tenant_id, order_id, asked_at desc)` |
| `pharmacist_review` | `order_id`, `order_version`, `dose_id` (unique), `created_at`, `closed_at`, `closed_by`, `outcome` (REVIEWED, VERIFIED, REJECTED, DOSE_UNDONE; set together) | partial `(tenant_id, created_at, id) where closed_at is null` (queue, Safety number); `(tenant_id, order_id)` (close on verify or reject) |

Grants: select; insert of the business columns; update only the stamp columns (hold: `resumed_by`; notice: `acknowledged_by, acknowledged_via`; query: `reply, replied_by, closed_by`; review: `closed_by, outcome`). The `*_at` of a stamp is set by the guard trigger from `clock_timestamp()`. No delete grant anywhere.

### 1.7 `ipd.dose_administration` (alter, still append-only)
New: `attempt` smallint default 1; `supersedes_dose_id` (unique per clinic, FK self); `order_version` int default 1 (FK to the version row); `dose_kind` default SCHEDULED; `scan_method` (SCAN, OVERRIDE, null on old rows); `override_reason_key` and `override_note` (set iff OVERRIDE); `medicine_scan` (1-64); `before_verification` bool default false; `undo_until` (**stamped by the insert trigger** as `recorded_at + ipd.config.undo_seconds`, default 10; not insertable); `not_given_note` (4-200; required for reason OTHER). Reasons add VOMITED, OTHER (both HELD). `identity_scan` may be null for OVERRIDE. New unique `(tenant_id, id)` (target of FKs). Unique `(tenant_id, order_id, slot_at, attempt)` under the **old name** `uq_dose_administration_order_slot`. Index `ix_dose_administration_override (tenant_id, scan_method, recorded_at) where scan_method = 'OVERRIDE'`.
Old rows are not rewritten: they read as attempt 1, version 1, SCHEDULED, no scan method (read as SCAN), no undo window (cannot be undone; can be marked in error the same IST day). The guard, not a check, requires `scan_method` on a new GIVEN dose.
Insert grant adds all new columns except `undo_until`.

### 1.8 `ipd.dose_correction` (new, append-only)
`dose_id` (unique per clinic, FK), `kind` (UNDO, IN_ERROR), `reason_key` (set iff IN_ERROR: NOT_GIVEN_ACTUALLY, WRONG_PATIENT, WRONG_TIME, DUPLICATE), `corrected_by`, `corrected_at` (clock), `client_key` (unique per clinic). Select and insert only.

## 2. Rules in the database (guard triggers, SQLSTATE 23514 `check_violation`, messages contain "medicine order", "dose", etc.)
- **Head update** (replaced in place): row counter `version` +1 on every update; `current_version` +1 only for a change (PENDING, QUERIED, ACTIVE to PENDING or ACTIVE). Without a change: PENDING to ACTIVE (verify), REJECTED, QUERIED, STOPPED; QUERIED to PENDING (reply), REJECTED, STOPPED; ACTIVE to ON_HOLD, STOPPED; ON_HOLD to ACTIVE (resume), STOPPED. REJECTED and STOPPED are final. A stop needs `stopped_by` and `stop_reason`. `verified_at` is stamped only in a verify. Identity and order fields frozen as built. **QUERIED to REJECTED is allowed** (a query is closable by REJECT, ADR D6; the D1 table lists reject for PENDING only: java-developer decides the rule, the database is the superset).
- **Head insert**: PENDING or ACTIVE, `current_version` 1, admission ADMITTED.
- **Deferred constraint trigger on the head (checked at commit)**: the version `current_version` exists; head priority equals its priority; ACTIVE or ON_HOLD needs a version that is verified or NOT_REQUIRED; PENDING or QUERIED needs an unverified REQUIRED version. So java may write head and version in either order in one transaction.
- **Version**: insert only as the next number; head PENDING or ACTIVE for v1, PENDING, QUERIED or ACTIVE and `version_no = current_version + 1` after (ON_HOLD refused); admission ADMITTED; no verifier at insert. Update only the two stamps once; verifier never the head's `ordered_by`; everything else frozen.
- **Hold**: insert only for an ACTIVE or ON_HOLD order; update sets the resume pair once (`resumed_at` stamped).
- **Notice**: update sets the acknowledged triple once (`acknowledged_at` stamped).
- **Query**: insert only for PENDING or QUERIED at the current version, asker is not the orderer; update closes once (`replied_at` and `closed_at` stamped; closing without a reply leaves `reply` null).
- **Review**: insert only for a GIVEN dose with `before_verification`, same order and version; update closes once (`closed_at` stamped).
- **Dose insert** (replaced in place): order ACTIVE (`before_verification` false), or PENDING only for a STAT ONCE current version (`before_verification` true; a second live GIVEN is refused); `order_version` = current; `dose_kind` = the version kind; admission ADMITTED; high-alert as built; attempt n > 1 must follow the corrected attempt n-1 of the same order and slot; GIVEN needs `scan_method`; `undo_until` stamped.
- **Correction insert**: dose must be GIVEN; UNDO by the recorder with the database clock not after `undo_until`; IN_ERROR only while the IST date of the clock equals the IST date of `slot_at`. Role rules (any NURSE, OWNER) stay in Java.
- **Profile insert**: at most 20 per clinic.

## 3. Triggers (names, for QA allow-lists)
| Table | Triggers |
| --- | --- |
| `ipd.dose_administration` | unchanged: `trg_dose_administration_no_update_delete`, `trg_dose_administration_no_truncate`, `trg_dose_administration_guard_insert` (function replaced) |
| `ipd.medicine_order` | unchanged `trg_medicine_order_no_delete`, `trg_medicine_order_no_truncate`, `trg_medicine_order_guard_insert`, `trg_medicine_order_guard_update` (functions replaced); **new** `trg_medicine_order_check_current_version` (constraint trigger, deferrable initially deferred) |
| `ipd.medicine_order_version` | `trg_medicine_order_version_no_delete`, `_no_truncate`, `_guard_insert`, `_guard_update` |
| `ipd.order_hold` | `trg_order_hold_no_delete`, `_no_truncate`, `_guard_insert`, `_guard_update` |
| `ipd.order_notice` | `trg_order_notice_no_delete`, `_no_truncate`, `_guard_update` |
| `ipd.medicine_order_query` | `trg_medicine_order_query_no_delete`, `_no_truncate`, `_guard_insert`, `_guard_update` |
| `ipd.pharmacist_review` | `trg_pharmacist_review_no_delete`, `_no_truncate`, `_guard_insert`, `_guard_update` |
| `ipd.dose_correction` (append-only) | `trg_dose_correction_no_update_delete`, `trg_dose_correction_no_truncate`, `trg_dose_correction_guard_insert` |
| `ipd.nursing_profile` | `trg_nursing_profile_guard_insert` |
| `ipd.nursing_profile_template` (global) | `trg_nursing_profile_template_no_update_delete`, `trg_nursing_profile_template_no_truncate` |
| `ipd.drug_code` (global) | `trg_drug_code_no_update_delete`, `trg_drug_code_no_truncate` |
`deny_audit_mutation()` raises 42501 "platform.audit_event is append-only (<op> refused)" (existing function, message unchanged).

## 4. RLS
Every new tenant table: ENABLE and FORCE, policies `p_<table>_select|insert|update` for `sahaj_app` on `tenant_id = platform.current_tenant()` (select and insert only for `dose_correction`; select, insert, update for `nursing_profile`). The two new global tables follow the `ipd.drug` pattern (`p_<t>_select` for sahaj_app `using (true)`, `p_<t>_migrator` for sahaj_migrator). RowLevelSecurityCatalogIT needs the nine new tables and its global list extended by `ipd.nursing_profile_template`, `ipd.drug_code`.

## 5. Expected volumes and retention (ADR-027 D15)
| Table | Rows per year, all clinics (planning) | Retention |
| --- | --- | --- |
| `medicine_order_version` | about 1.3 per order, 5 to 20 million orders: 6.5 to 26 million | never deleted |
| `order_notice` | 20,000 to 100,000 a day: 7 to 36 million | never deleted |
| `order_hold`, `medicine_order_query`, `pharmacist_review`, `dose_correction` | under 5% of orders or doses each (about 1 million or less) | never deleted |
| `nursing_profile` | up to 20 per clinic | configuration, no delete in this set |
| `dose_administration` | unchanged scale (about 150,000 a day at full use) | never deleted |
Per tenant: a 100-bed hospital is about 50,000 to 200,000 orders and 65,000 to 260,000 versions a year. No partitioning needed now (all under 50 million in total). **Partition plan impact (action for the tech lead):** ADR-011 D13 said nothing references `ipd.dose_administration` by foreign key. ADR-027 items 6 and 8 add foreign keys from `dose_correction`, `pharmacist_review` and a self-reference. At the 30-million-row conversion those foreign keys must be rebuilt with `slot_at` in the key or replaced by trigger checks. The migration comment on the table is not edited (forward-only); this note is the record.

## 6. Notes for java-developer
- Write order in one transaction: new order = insert head (current_version 1, status PENDING or ACTIVE, priority) then version 1; change = insert version n+1, update version n `superseded_at` (any non-null value, the trigger stamps it), update head (`current_version`, `status`, `priority`, `version` + 1). Verify = update version `verified_by`, then head `status` ACTIVE with `verified_by` and `version` + 1.
- `undo_until`, `verified_at`, `superseded_at`, `resumed_at`, `acknowledged_at`, `replied_at`, `closed_at` are trigger-stamped; read them back with `returning`.
- Old doses have `scan_method` null (treat as SCAN) and `undo_until` null (not undoable).
- The head `stopped_at/rejected_at` stamps remain trigger-set. Old stopped orders have `stop_reason` null.
- Catch the dose unique by the unchanged constraint name `uq_dose_administration_order_slot`.

## 7. How to prove the migration on a database that held orders (DEC-056 option A merge condition)
`sahaj_test` is rebuilt from scratch and holds no pre-migration orders, so the proof runs on `sahaj_dev` with `docs/database/SAH-76-backfill-check.sql` (SELECT only, read-only session, counts only).
1. Make sure `sahaj_dev` has orders and has NOT yet had `V2026_12_15_1000` applied (check `flyway_schema_history`). Do not reset it.
2. Start the dev server once so Flyway applies the migration, then stop the server.
3. Run as the PostgreSQL superuser (the tables are FORCE row level security, so even `sahaj_migrator` would see no tenant rows):
   `psql -h localhost -U postgres -d sahaj_dev -A -F '|' -f docs/database/SAH-76-backfill-check.sql`
4. Good when no line starts with `FAIL`. Checks: (1) enabled and forced RLS on the 9 new tables, `ipd.medicine_order` and `ipd.dose_administration`; (2) orders equal version-1 rows per clinic and in total; (3) no head without its `current_version` row; (4) the 5 re-created constraints exist and are validated, and the slot unique is on `(tenant_id, order_id, slot_at, attempt)`; (5) the 14 global drugs have `route_key`; (6) information only: old GIVEN doses with null `scan_method`.
5. Paste the output into the QA test plan. Do not share it outside the team (tenant ids are shown).
