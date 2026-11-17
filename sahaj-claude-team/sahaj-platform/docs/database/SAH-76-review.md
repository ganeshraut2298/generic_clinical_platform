# SAH-76 review round · database design

Source: `docs/decisions/DEC-057-sah-76-review-safety-findings.md` (human answered Option A on all three, 16 Nov 2026), `docs/reviews/SAH-76-security.md` M1 and M2.
Migration (one file, forward-only): `backend/src/main/resources/db/migration/V2026_12_16_1000__ipd_review_safety_guards.sql`. It is dated above the latest existing migration (`V2026_12_15_1000`), as the security audit (#17) asks. Earlier migrations are not edited.
Base design note: `docs/database/SAH-76.md`. Module: ipd only.

## 0. Safety summary
- **No data change, nothing dropped.** No UPDATE, no DELETE, no ALTER of an existing table, no constraint added or removed. The only DDL on existing objects is `create or replace function` on three guard functions (same names, same triggers, same SQLSTATE 23514).
- Old rows stay valid: triggers fire on new inserts and updates only. Old doses with reason `WRISTBAND_DAMAGED` or an OVERRIDE with no `identity_scan` still read, because `ck_dose_administration_override_reason` and `ck_dose_administration_identity_scan_by_outcome` are unchanged.
- Backward compatibility: the previous app version breaks only where the rules now bite: an owner who verifies or queries their own change (guards), a GIVEN dose without a wristband scan, and an override with reason `WRISTBAND_DAMAGED` or with a medicine scan. Ship backend and migration together.

## 1. Self-verification bar (security M2, DEC-057 item 2)
| Function (trigger unchanged) | New rule | Error (SQLSTATE 23514) |
| --- | --- | --- |
| `ipd.guard_medicine_order_version_update` (`trg_medicine_order_version_guard_update`) | a verify (`verified_by` null to a value) is refused when `verified_by` equals the head's `ordered_by` **or** the `created_by` of the version row being verified | `medicine order version cannot be verified by the person who ordered or changed it` |
| `ipd.guard_medicine_order_query_insert` (`trg_medicine_order_query_guard_insert`) | a query is refused when `asked_by` equals the head's `ordered_by` **or** the `created_by` of the current version row | `medicine order query cannot be asked by the person who ordered or changed it` |
Old messages (`...by the person who ordered it`) are gone; no test or Java code matched on them (searched). The Java service raises 403 `self-verification` first; these are the backstop, so the app maps a 23514 whose message contains "ordered or changed it" to the same 403 if it ever reaches the database.

## 2. `ipd.dose_scan_stop` (security M1, DEC-057 item 1)
Why a table: the event log has no order or slot columns and `ipd` may not read `platform.audit_event` (module boundary).

| Column | Type | Null | Default | Check or key |
| --- | --- | --- | --- | --- |
| `id` | uuid | no | `gen_random_uuid()` | primary key |
| `tenant_id` | uuid | no | `platform.current_tenant()` | FK `platform.tenant (id)`; not insertable (default only, policy still checks it) |
| `admission_id` | uuid | no | | FK `(tenant_id, admission_id)` to `ipd.admission` |
| `order_id` | uuid | no | | FK `(tenant_id, order_id, admission_id)` to `ipd.medicine_order` (same target as the dose table: the order belongs to the admission) |
| `slot_at` | timestamptz | no | | the slot the nurse was giving; for a PRN the slot minute the dose would carry (the app ignores the slot for PRN) |
| `check_kind` | text | no | | `ck_dose_scan_stop_check_kind`: `WRISTBAND`, `MEDICINE` |
| `actor` | uuid | no | | the user who scanned; no FK (same as `recorded_by`) |
| `stopped_at` | timestamptz | no | `clock_timestamp()` | database time; not insertable |

- Index `ix_dose_scan_stop_tenant_order_actor_stopped (tenant_id, order_id, actor, stopped_at desc)`. Query served: "did this actor have a stop on this order in the last 15 minutes" (`where tenant_id = ? and order_id = ? and actor = ? and stopped_at >= now - 15 min`, optionally `and slot_at = ?`). It also serves the order FK from the child side. The admission FK has no own index (admissions are never deleted; the table is small).
- **RLS:** enabled and forced. `p_dose_scan_stop_select` and `p_dose_scan_stop_insert` for `sahaj_app` on `tenant_id = platform.current_tenant()` (using for select, with check for insert). No update or delete policy.
- **Grants (`sahaj_app` only):** `select` on the table; `insert (admission_id, order_id, slot_at, check_kind, actor)`. All other privileges revoked from `sahaj_app` and `public` (default privileges would otherwise give four commands).
- **Triggers:** `trg_dose_scan_stop_no_update_delete` (before update or delete, row) and `trg_dose_scan_stop_no_truncate` (before truncate, statement), both `platform.deny_audit_mutation()`, SQLSTATE 42501 "platform.audit_event is append-only (<op> refused)". There is no insert guard (the foreign keys carry the integrity).
- **Rows:** a stop is a failed scan, about 1 to 2 percent of GIVEN attempts: planning 1,500 to 3,000 a day at full use, about 1 million a year across all clinics. Per 100-bed hospital: a few thousand a year. No partitioning (far under 50 million).
- **Retention:** never deleted (a medication-safety record: shows a stop happened before an override). Holds ids and a kind only; no patient name, mobile, scanned text or clinical text.

## 3. Dose insert backstop (`ipd.guard_dose_administration_insert`, trigger `trg_dose_administration_guard_insert`)
Same body as the SAH-76 migration plus three rules for a GIVEN dose (SQLSTATE 23514, message contains "dose"), checked after the scan-method rule and before `undo_until` is stamped:
| Rule | Message |
| --- | --- |
| `identity_scan` is required | `given dose needs the wristband scan` |
| `override_reason_key = 'WRISTBAND_DAMAGED'` is refused | `dose override reason wristband damaged is no longer allowed` |
| `scan_method = 'OVERRIDE'` and `medicine_scan` not null is refused | `dose with a manual override has no medicine scan` |
The database does **not** enforce "no override within 15 minutes of a stop"; that rule stays in the service, reading `ipd.dose_scan_stop`.

## 4. Notes for java-developer
- Write a stop row in its own transaction, committed before the 409 or 400 is returned, otherwise the rollback of the failed request deletes the evidence (`REQUIRES_NEW` or write then throw after commit). The `insert ... (admission_id, order_id, slot_at, check_kind, actor)` column list is the only one allowed.
- Names: table `ipd.dose_scan_stop`; kinds `WRISTBAND`, `MEDICINE`; read with `stopped_at >= clock_timestamp() - interval '15 minutes'` (database clock; do not mix with the app clock).
- Service check order and 403 `self-verification` are in the contract; the database messages above are the backstop only.

## 5. For QA
- `RowLevelSecurityCatalogIT`: add `ipd.dose_scan_stop` (enabled, forced, policies `p_dose_scan_stop_select|r|sahaj_app` and `p_dose_scan_stop_insert|a|sahaj_app`, no update or delete policy; follow the format used for `ipd.dose_correction`). Count of tenant tables with FORCE goes up by one.
- `Sah76MigrationIT` and `Sah76DoseDbIT`: grants (select and the five-column insert only; `tenant_id`, `id`, `stopped_at` not insertable), update, delete and truncate refused (42501), cross-tenant insert refused by the policy, insert with an order of another admission refused (23503), `check_kind` outside the list refused (23514).
- Truncate tests that list the referencing tables (`DoseTableIT` around line 432 and 496, `MedicineOrderTableIT` around line 493) must add `ipd.dose_scan_stop` for any `truncate` of `ipd.medicine_order` or `ipd.admission` (a foreign key now points at them).
- Guard tests: verify by the order's author and by the version's author (an OWNER who changed the order) refused 23514; a verify by a third person passes; same two cases for a query. Dose: GIVEN with `scan_method = 'SCAN'` or `'OVERRIDE'` and no `identity_scan` refused; override reason `WRISTBAND_DAMAGED` refused; OVERRIDE with a `medicine_scan` refused; OVERRIDE with a wristband scan and no medicine scan accepted. Existing fixtures that insert an OVERRIDE dose without `identity_scan` will now fail and need the wristband value.
