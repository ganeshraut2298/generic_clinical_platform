# SAH-76 migration test plan (V2026_12_15_1000__ipd_nursing_orders_to_execution)

Scope: the migration only (database design `docs/database/SAH-76.md`, decision DEC-056 option A). API and screen tests of SAH-76 come in a later plan. Database: `sahaj_test` only.
Merge condition (DEC-056): FORCE row level security is back on `ipd.medicine_order`, and version 1 rows match the existing orders.

| ID | Check | Test | Where |
| --- | --- | --- | --- |
| M1 | Flyway applies the migration on sahaj_test (syntax, logic) | `node scripts/validate.mjs db` (context start of every IT) | validate |
| M2 | ENABLE + FORCE RLS and at least one policy on the 7 new tenant tables, 2 new global tables, and on medicine_order, dose_administration, ward, vitals, config, drug | `Sah76MigrationIT.forceRowLevelSecurityIsOnForTheNineNewTablesAndBackOnForTheMedicineOrderHead` | IT |
| M3 | FORCE flag on medicine_order read by the owner in a separate session; owner sees no rows without a policy | `forceStaysOnForTheHeadAfterTheMigrationWhenReadByTheMigratorToo` | IT |
| M4 | Every order has version 1; count of version 1 = count of orders; the backfill statement run again inserts 0 rows; version 1 frequency equals the head; every head's current version exists | `everyExistingOrderHasVersionOneAndTheMigrationBackfillHasNothingLeftToInsert` | IT |
| M5 | Old doses (no scan method) read as attempt 1, version 1, SCHEDULED, no undo window; all constraints validated | `oldDoseRowsStayValidAndReadAsAttemptOneVersionOneScheduled` | IT |
| M6 | The five re-created constraints keep their names and widen only (old statuses still allowed, QUERIED/ON_HOLD, VOMITED/OTHER, slot unique with attempt); `frequency_key` nullable; old indexes kept | `theFiveReCreatedConstraintsKeepTheirNamesAndOnlyWidenWhileTheOldValuesStayAllowed` | IT |
| M7 | All triggers of design note section 3 exist; the commit-time check is deferrable and initially deferred | `everyTriggerOfTheDesignNoteSection3Exists` | IT |
| M8 | 14 drugs have route and units; two profile templates seeded (ICU, General ward); drug_code empty | `theGlobalReferenceDataIsSeededAndTheCodeListShipsEmpty` | IT |
| M9 | Catalogue test knows the new tables and names (`p_<table>_select`, `p_<table>_migrator`) | `RowLevelSecurityCatalogIT` (updated) | IT |

Limitation (M4): after this migration a pre-migration order cannot be created (the old constraints and guards are replaced and a head without a version is refused at commit). The proof therefore is the invariant plus the idempotent backfill on the orders that sahaj_test holds. If sahaj_test holds no order the counts are 0 and the proof is empty; the IT prints `SAH-76 backfill proof: orders=<n> version1=<n>`. For a non-empty proof, run it on a sahaj_test restored from before the migration with orders in it (DEC-056 asks for "a copy with existing orders").

Negative, role and tenant cases for the new tables (insert refusals, other tenant invisible, 403) belong to the API stories and come with the java-developer code.
