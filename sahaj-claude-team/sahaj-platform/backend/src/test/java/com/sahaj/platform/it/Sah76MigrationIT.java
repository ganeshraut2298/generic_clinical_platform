package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * SAH-76 migration V2026_12_15_1000__ipd_nursing_orders_to_execution (docs/database/SAH-76.md, DEC-056 option A).
 * Merge condition of DEC-056: FORCE row level security is back on ipd.medicine_order and the version 1 rows match the orders.
 *
 * <p>Why the backfill is proved by an invariant and not by seeding an "old" order: after the migration the head guard and the
 * deferred commit trigger refuse an order without its version row, and the old constraints are gone, so a pre-migration row can
 * no longer be created on a migrated schema. The same facts are therefore asserted on whatever orders sahaj_test holds (every
 * one of them was either backfilled or written with its version): no order without version 1, and the backfill statement of
 * the migration finds nothing left to insert. Everything runs in a rolled-back transaction.
 */
@OnTestDatabase
class Sah76MigrationIT extends BaseIntegrationTest {

    private static final List<String> NEW_TENANT_TABLES = List.of("ipd.nursing_profile", "ipd.medicine_order_version",
            "ipd.order_hold", "ipd.order_notice", "ipd.medicine_order_query", "ipd.pharmacist_review", "ipd.dose_correction",
            "ipd.dose_scan_stop"); // the last one came with the review-round migration (DEC-057 item 1)
    private static final List<String> NEW_GLOBAL_TABLES = List.of("ipd.nursing_profile_template", "ipd.drug_code");

    @Test
    void forceRowLevelSecurityIsOnForTheTenNewTablesAndBackOnForTheMedicineOrderHead() throws Exception {
        List<String> all = new ArrayList<>(NEW_TENANT_TABLES);
        all.addAll(NEW_GLOBAL_TABLES);
        assertThat(all).hasSize(10);
        all.addAll(List.of("ipd.medicine_order", "ipd.dose_administration", "ipd.ward", "ipd.vitals", "ipd.config", "ipd.drug"));
        List<String> weak = new ArrayList<>();
        db().asApp(c -> {
            for (String t : all) {
                String state = DbAccess.text(c, "select c.relrowsecurity::text || '/' || c.relforcerowsecurity::text || '/' || "
                        + "(select count(*) from pg_policy p where p.polrelid = c.oid)::text from pg_class c where c.oid = ?::regclass", t);
                if (!state.startsWith("true/true/") || state.endsWith("/0")) {
                    weak.add(t + " " + state);
                }
            }
            return null;
        });
        assertThat(weak).as("tables without enabled and forced RLS and a policy").isEmpty();
    }

    @Test
    void forceStaysOnForTheHeadAfterTheMigrationWhenReadByTheMigratorToo() throws Exception {
        // relforcerowsecurity is the flag the migration lifts and restores; read it as the owner, a different session from the app's.
        db().asMigrator(c -> {
            assertThat(DbAccess.text(c, "select relforcerowsecurity::text from pg_class where oid = 'ipd.medicine_order'::regclass"))
                    .isEqualTo("true");
            // under FORCE the owner sees no row of a tenant table without a bound tenant (no migrator policy on the head)
            assertThat(DbAccess.count(c, "select count(*) from ipd.medicine_order")).isZero();
            return null;
        });
    }

    @Test
    void everyExistingOrderHasVersionOneAndTheMigrationBackfillHasNothingLeftToInsert() throws Exception {
        db().asMigrator(c -> {
            // Rolled back with the transaction: lift FORCE only to count across tenants, exactly as the migration did.
            DbAccess.execute(c, "alter table ipd.medicine_order no force row level security");
            DbAccess.execute(c, "alter table ipd.medicine_order_version no force row level security");
            long orders = DbAccess.count(c, "select count(*) from ipd.medicine_order");
            long versionOnes = DbAccess.count(c, "select count(*) from ipd.medicine_order_version where version_no = 1");
            long ordersWithVersionOne = DbAccess.count(c, "select count(*) from ipd.medicine_order o where exists (select 1 from "
                    + "ipd.medicine_order_version v where v.tenant_id = o.tenant_id and v.order_id = o.id and v.version_no = 1)");
            System.out.println("SAH-76 backfill proof: orders=" + orders + " version1=" + versionOnes);
            assertThat(ordersWithVersionOne).as("orders with a version 1 row").isEqualTo(orders);
            assertThat(versionOnes).as("one version 1 per order").isEqualTo(orders);
            // the statement of the migration (section C3), run again: nothing is left to insert
            long inserted = DbAccess.count(c, """
                    with ins as (
                      insert into ipd.medicine_order_version
                        (tenant_id, order_id, version_no, kind, frequency_key, dose_amount, dose_unit, route_key, start_at, end_at, indication,
                         priority, min_interval_min, max_per_24h, change_reason, created_by, created_at, superseded_at, verification, verified_by, verified_at)
                      select o.tenant_id, o.id, 1, 'SCHEDULED', o.frequency_key, null, null, d.route_key, o.ordered_at, null, null,
                             'ROUTINE', null, null, null, o.ordered_by, o.ordered_at, null, 'REQUIRED', o.verified_by, o.verified_at
                        from ipd.medicine_order o join ipd.drug d on d.key = o.drug_key
                       where not exists (select 1 from ipd.medicine_order_version v where v.tenant_id = o.tenant_id and v.order_id = o.id)
                      returning 1)
                    select count(*) from ins
                    """);
            assertThat(inserted).as("rows the backfill would still insert").isZero();
            // head and version agree where the head keeps its original frequency; no head carries a version beyond its rows
            assertThat(DbAccess.count(c, "select count(*) from ipd.medicine_order o join ipd.medicine_order_version v on v.tenant_id = o.tenant_id "
                    + "and v.order_id = o.id and v.version_no = 1 where v.kind = 'SCHEDULED' and v.frequency_key is distinct from o.frequency_key"))
                    .as("version 1 frequency differs from the head").isZero();
            assertThat(DbAccess.count(c, "select count(*) from ipd.medicine_order o where not exists (select 1 from ipd.medicine_order_version v "
                    + "where v.tenant_id = o.tenant_id and v.order_id = o.id and v.version_no = o.current_version)"))
                    .as("heads whose current version row is missing").isZero();
            return null;
        });
    }

    @Test
    void oldDoseRowsStayValidAndReadAsAttemptOneVersionOneScheduled() throws Exception {
        db().asMigrator(c -> {
            DbAccess.execute(c, "alter table ipd.dose_administration no force row level security");
            assertThat(DbAccess.count(c, "select count(*) from ipd.dose_administration where scan_method is null and recorded_at < (select installed_on from "
                    + "platform.flyway_schema_history where version = '2026.12.15.1000') and "
                    + "(attempt <> 1 or order_version <> 1 or dose_kind <> 'SCHEDULED' or undo_until is not null or supersedes_dose_id is not null "
                    + "or before_verification)")).as("old doses rewritten or read differently").isZero();
            return null;
        });
        db().asMigrator(c -> {
            assertThat(DbAccess.count(c, "select count(*) from pg_constraint where conrelid in ('ipd.medicine_order'::regclass, "
                    + "'ipd.dose_administration'::regclass, 'ipd.medicine_order_version'::regclass) and not convalidated"))
                    .as("constraints not validated against existing rows").isZero();
            return null;
        });
    }

    @Test
    void theFiveReCreatedConstraintsKeepTheirNamesAndOnlyWidenWhileTheOldValuesStayAllowed() throws Exception {
        db().asMigrator(c -> {
            String status = DbAccess.text(c, "select pg_get_constraintdef(oid) from pg_constraint where conname = 'ck_medicine_order_status' "
                    + "and conrelid = 'ipd.medicine_order'::regclass");
            assertThat(status).contains("PENDING", "ACTIVE", "REJECTED", "STOPPED", "QUERIED", "ON_HOLD");
            String reason = DbAccess.text(c, "select pg_get_constraintdef(oid) from pg_constraint where conname = 'ck_dose_administration_reason' "
                    + "and conrelid = 'ipd.dose_administration'::regclass");
            assertThat(reason).contains("VOMITED", "OTHER");
            String slot = DbAccess.text(c, "select pg_get_constraintdef(oid) from pg_constraint where conname = 'uq_dose_administration_order_slot' "
                    + "and conrelid = 'ipd.dose_administration'::regclass");
            assertThat(slot).contains("UNIQUE").contains("attempt").contains("slot_at").contains("order_id").contains("tenant_id");
            assertThat(DbAccess.count(c, "select count(*) from pg_constraint where conname in ('ck_medicine_order_fields_by_status', "
                    + "'ck_dose_administration_identity_scan_by_outcome') and conrelid in ('ipd.medicine_order'::regclass, "
                    + "'ipd.dose_administration'::regclass)")).isEqualTo(2);
            assertThat(DbAccess.text(c, "select is_nullable from information_schema.columns where table_schema = 'ipd' "
                    + "and table_name = 'medicine_order' and column_name = 'frequency_key'")).isEqualTo("YES");
            // the old narrower indexes are kept (design note 0)
            assertThat(DbAccess.count(c, "select count(*) from pg_indexes where schemaname = 'ipd' and indexname in "
                    + "('ux_medicine_order_tenant_admission_drug_live', 'ux_medicine_order_tenant_admission_drug_live4', "
                    + "'ix_medicine_order_tenant_pending_queried')")).isEqualTo(3);
            return null;
        });
    }

    @Test
    void everyTriggerOfTheDesignNoteSection3Exists() throws Exception {
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        expected.put("ipd.dose_administration", Set.of("trg_dose_administration_no_update_delete", "trg_dose_administration_no_truncate",
                "trg_dose_administration_guard_insert"));
        expected.put("ipd.medicine_order", Set.of("trg_medicine_order_no_delete", "trg_medicine_order_no_truncate",
                "trg_medicine_order_guard_insert", "trg_medicine_order_guard_update", "trg_medicine_order_check_current_version"));
        expected.put("ipd.medicine_order_version", Set.of("trg_medicine_order_version_no_delete", "trg_medicine_order_version_no_truncate",
                "trg_medicine_order_version_guard_insert", "trg_medicine_order_version_guard_update"));
        expected.put("ipd.order_hold", Set.of("trg_order_hold_no_delete", "trg_order_hold_no_truncate", "trg_order_hold_guard_insert",
                "trg_order_hold_guard_update"));
        expected.put("ipd.order_notice", Set.of("trg_order_notice_no_delete", "trg_order_notice_no_truncate", "trg_order_notice_guard_update"));
        expected.put("ipd.medicine_order_query", Set.of("trg_medicine_order_query_no_delete", "trg_medicine_order_query_no_truncate",
                "trg_medicine_order_query_guard_insert", "trg_medicine_order_query_guard_update"));
        expected.put("ipd.pharmacist_review", Set.of("trg_pharmacist_review_no_delete", "trg_pharmacist_review_no_truncate",
                "trg_pharmacist_review_guard_insert", "trg_pharmacist_review_guard_update"));
        expected.put("ipd.dose_correction", Set.of("trg_dose_correction_no_update_delete", "trg_dose_correction_no_truncate",
                "trg_dose_correction_guard_insert"));
        expected.put("ipd.dose_scan_stop", Set.of("trg_dose_scan_stop_no_update_delete", "trg_dose_scan_stop_no_truncate"));
        expected.put("ipd.nursing_profile", Set.of("trg_nursing_profile_guard_insert"));
        expected.put("ipd.nursing_profile_template", Set.of("trg_nursing_profile_template_no_update_delete",
                "trg_nursing_profile_template_no_truncate"));
        expected.put("ipd.drug_code", Set.of("trg_drug_code_no_update_delete", "trg_drug_code_no_truncate"));
        List<String> problems = new ArrayList<>();
        db().asApp(c -> {
            for (var e : expected.entrySet()) {
                Set<String> found = new TreeSet<>();
                try (var ps = c.prepareStatement("select tgname from pg_trigger where tgrelid = ?::regclass and not tgisinternal")) {
                    ps.setString(1, e.getKey());
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) {
                            found.add(rs.getString(1));
                        }
                    }
                }
                if (!found.containsAll(e.getValue())) {
                    Set<String> missing = new TreeSet<>(e.getValue());
                    missing.removeAll(found);
                    problems.add(e.getKey() + " missing " + missing);
                }
            }
            // the commit-time check is a deferrable constraint trigger, initially deferred
            assertThat(DbAccess.text(c, "select (tgdeferrable and tginitdeferred and tgconstraint <> 0)::text from pg_trigger "
                    + "where tgname = 'trg_medicine_order_check_current_version' and tgrelid = 'ipd.medicine_order'::regclass")).isEqualTo("true");
            return null;
        });
        assertThat(problems).isEmpty();
    }

    @Test
    void theGlobalReferenceDataIsSeededAndTheCodeListShipsEmpty() throws Exception {
        db().asApp(c -> {
            assertThat(DbAccess.count(c, "select count(*) from ipd.drug where route_key is null or cardinality(dose_units) = 0")).isZero();
            assertThat(DbAccess.count(c, "select count(*) from ipd.drug")).isEqualTo(14);
            assertThat(DbAccess.text(c, "select string_agg(key || ':' || name || ':' || med_verification_required || ':' || vitals_every_min, ',' order by sort_order) "
                    + "from ipd.nursing_profile_template")).isEqualTo("icu:ICU:true:60,general-ward:General ward:true:240");
            assertThat(DbAccess.count(c, "select count(*) from ipd.drug_code")).isZero();
            return null;
        });
    }
}
