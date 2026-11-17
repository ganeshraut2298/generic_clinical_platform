# DEC-056 · SAH-76 migration: constraint re-creation, version-1 backfill and a short RLS switch-off

Date: 15 Nov 2026. Status: NEEDS DECISION. Raised by: tech lead, from the db-engineer's hand-back.
Migration: `backend/src/main/resources/db/migration/V2026_12_15_1000__ipd_nursing_orders_to_execution.sql`. Design note: `docs/database/SAH-76.md`.
Not yet run against any database (Maven was busy). Blocked: java-developer and angular-developer work packages WP1-WP4 that use the new tables; QA.

## What the migration does that the rules call destructive or sensitive
1. Drops and re-adds five constraints under the same name in one transaction. Each only loosens or widens: `ck_medicine_order_status` (adds QUERIED, ON_HOLD), `ck_medicine_order_fields_by_status` (ACTIVE order may have no verifier), `ck_dose_administration_reason` (adds VOMITED, OTHER), `ck_dose_administration_identity_scan_by_outcome` (scan may be null on override), `uq_dose_administration_order_slot` (unique on tenant, order, slot_at, attempt). Old narrower unique and queue indexes are kept.
2. `ipd.medicine_order.frequency_key` becomes nullable (PRN and STAT have no frequency). Not named in ADR-027.
3. Data written on existing rows: version 1 inserted for every existing order. To read them it runs `NO FORCE ROW LEVEL SECURITY` on `ipd.medicine_order` and restores `FORCE` in the same transaction. No existing tenant row is updated.
4. Global seed: 14 `ipd.drug` rows get `route_key` and `dose_units`; two nursing profile templates seeded.
5. Old GIVEN doses keep `scan_method` null (read as SCAN) and `undo_until` null (cannot be undone), to avoid an UPDATE on the append-only table.

## Options
- **A (recommended): accept as written.** ADR-027 items 1 and 7 need 1 to 2; item 3 is a one-time, same-transaction switch-off that migrations already run as owner (`sahaj_migrator`), and the app role is never affected. Condition: before merge QA runs the migration on a copy with existing orders and proves FORCE is back on (RowLevelSecurityCatalogIT) and the counts match. Pro: fastest. Con: touches the RLS setting of one table inside a migration.
- **B: avoid the RLS switch-off.** Insert version 1 with a `SECURITY DEFINER`-free approach, i.e. loop per tenant with `set_config('app.tenant_id', ...)` as the migrator under FORCE. Pro: RLS never off. Con: slower to write, more code in the migration; one more db-engineer round.
- **C: no backfill.** Existing orders get no version 1; Java treats "no version row" as version 1 read from the head. Pro: no data written. Con: two read paths forever; contradicts ADR-027 D14.

## Recommendation
A, with the QA proof as a merge condition. B if you do not accept any RLS switch-off.

## Other points the db-engineer raised (no decision needed, recorded)
Partitioning plan at about 30 million dose rows must rebuild the new foreign keys (ADR-011 D13 assumed none); QUERIED to REJECTED allowed by the database (a superset of ADR D1; Java decides the rule); verifier must not equal the order's `ordered_by`; commit-time check on the order head. Existing ITs that assert exact constraint/column sets on `medicine_order` and `dose_administration`, and RowLevelSecurityCatalogIT (9 new tables, 2 global), need updating by QA.

## Answer (human, chat, 15 Nov 2026)
**Option A: accept as written.** Merge condition: QA proves on a copy with existing orders that FORCE row-level security is back on and the version-1 counts match (RowLevelSecurityCatalogIT plus a backfill IT).
