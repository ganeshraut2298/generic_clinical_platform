package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-78 database checks (docs/database/SAH-78-pharmacy-masters.md section 9) that belong to WP-A tables and to the
 * additive platform changes: cross-tenant row level security as sahaj_app on {@code unit}, {@code store}, {@code store_staff}
 * and {@code staff_preference}; no delete or truncate for the application; the table constraints and the store guard trigger;
 * the widened role check; the sample catalogue seed. The checks of the WP-B and WP-C tables (opening stock, vendor, approval
 * rules, numbering) are written with those packages (see docs/test-plans/SAH-78.md).
 */
@OnTestDatabase
class PharmacyTablesIT extends Sah78Support {

    private static final String[] WP_A_TABLES = {"unit", "store", "store_staff", "staff_preference"};

    /** A clinic with a unit (saved settings), a Central Store, a Pharmacy, a mapped nurse and a remembered working store. */
    private record World(Clinic clinic, UUID unitId, UUID centralId, UUID pharmacyId, UUID nurseMembership) {
    }

    private World world() throws Exception {
        Clinic clinic = pharmacyClinic();
        ok(HttpMethod.PUT, UNIT, clinic.owner(), "{\"version\":0,\"gstin\":\"" + GSTIN_OK + "\",\"drugLicenceNo\":null,\"drugLicenceValidTill\":null,\"integrationMode\":\"INTEGRATED\"}", 200);
        JsonNode[] stores = centralAndPharmacy(clinic);
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        Pn nurse = crew(clinic, "NURSE");
        mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        mapStaff(clinic, idOf(ward), nurse, "STORE_USER");
        ok(HttpMethod.PUT, WORKING_STORE, nurse.auth(), "{\"storeId\":\"" + idOf(stores[1]) + "\"}", 200);
        UUID unit = UUID.fromString(values(clinic, "select id::text from pharmacy.unit").get(0));
        return new World(clinic, unit, UUID.fromString(idOf(stores[0])), UUID.fromString(idOf(stores[1])), nurse.membershipId());
    }

    private SQLException failing(UUID tenantId, DbAccess.SqlWork<Object> work) throws SQLException {
        try {
            db().inTenant(tenantId, work);
        } catch (SQLException e) {
            return e;
        }
        throw new AssertionError("the statement was expected to fail");
    }

    private void expect(UUID tenantId, String sqlState, String messagePart, DbAccess.SqlWork<Object> work) throws SQLException {
        SQLException e = failing(tenantId, work);
        assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo(sqlState);
        assertThat(e.getMessage()).contains(messagePart);
    }

    private static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }

    private void insertStore(Connection c, World w, String name, String code, String type, UUID issuesFrom, String status) throws SQLException {
        update(c, "insert into pharmacy.store (tenant_id, unit_id, name, code, type, issues_from_store_id, status, created_by) "
                + "values (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?, ?::uuid)", w.clinic().tenantId().toString(), w.unitId().toString(),
                name, code, type, issuesFrom == null ? null : issuesFrom.toString(), status, w.clinic().ownerUserId().toString());
    }

    private void insertStaff(Connection c, World w, UUID store, UUID member, String role, String from, String to, String status) throws SQLException {
        update(c, "insert into pharmacy.store_staff (tenant_id, unit_id, store_id, membership_id, store_role, delegate_from, delegate_to, "
                + "status, created_by) values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?::date, ?::date, ?, ?::uuid)",
                w.clinic().tenantId().toString(), w.unitId().toString(), store.toString(), member.toString(), role, from, to, status,
                w.clinic().ownerUserId().toString());
    }

    // ---------------- row level security ----------------

    @Test
    void anotherTenantsRowsOfUnitStoreStaffAndPreferenceAreInvisibleUnchangeableAndCannotBeWrittenAsSahajApp() throws Exception {
        World a = world();
        World b = world();
        UUID tenantA = a.clinic().tenantId();
        UUID tenantB = b.clinic().tenantId();
        for (String table : WP_A_TABLES) {
            long ownA = db().inTenant(tenantA, c -> DbAccess.count(c, "select count(*) from pharmacy." + table));
            long ownB = db().inTenant(tenantB, c -> DbAccess.count(c, "select count(*) from pharmacy." + table));
            assertThat(ownA).as(table + " rows of A").isPositive();
            assertThat(ownB).as(table + " rows of B").isPositive();
            List<String> idsOfA = new ArrayList<>();
            db().inTenant(tenantA, c -> {
                try (var st = c.createStatement(); var rs = st.executeQuery("select id::text from pharmacy." + table)) {
                    while (rs.next()) {
                        idsOfA.add(rs.getString(1));
                    }
                }
                return null;
            });
            assertThat(idsOfA).hasSize((int) ownA);
            db().inTenant(tenantB, c -> {
                assertThat(DbAccess.count(c, "select count(*) from pharmacy." + table + " where tenant_id = ?::uuid", tenantA.toString()))
                        .as(table + " rows of A seen by B").isZero();
                assertThat(DbAccess.count(c, "select count(*) from pharmacy." + table + " where tenant_id = ?::uuid", tenantB.toString()))
                        .isEqualTo(ownB);
                assertThat(DbAccess.count(c, "select count(*) from pharmacy." + table + " where id = any (?::uuid[])",
                        c.createArrayOf("uuid", idsOfA.stream().map(UUID::fromString).toArray()))).as(table + " by id").isZero();
                assertThat(update(c, "update pharmacy." + table + " set version = version + 1 where tenant_id = ?::uuid", tenantA.toString()))
                        .as(table + " rows of A changed by B").isZero();
                return null;
            });
            // nothing was bound: the app sees no row at all (fails closed)
            long unbound = db().asApp(c -> DbAccess.count(c, "select count(*) from pharmacy." + table));
            assertThat(unbound).as(table + " with no tenant").isZero();
        }
        // A's data is intact
        assertThat(values(a.clinic(), "select version::text from pharmacy.unit").get(0)).isEqualTo("1");
    }

    @Test
    void anInsertForAnotherTenantIsRefusedByRowLevelSecurityAndAnInsertPointingAtAnotherTenantsUnitOrStoreIsRefusedByTheForeignKey() throws Exception {
        World a = world();
        World b = world();
        UUID tenantA = a.clinic().tenantId();
        UUID tenantB = b.clinic().tenantId();
        String rls = "row-level security";
        // B bound, row says tenant A
        expect(tenantB, "42501", rls, c -> update(c, "insert into pharmacy.unit (tenant_id, code, created_by) values (?::uuid, 'ZZZ', ?::uuid)",
                tenantA.toString(), b.clinic().ownerUserId().toString()));
        expect(tenantB, "42501", rls, c -> {
            insertStore(c, new World(a.clinic(), a.unitId(), null, null, null), "Intruder", "INT", "CENTRAL", null, "ACTIVE");
            return null;
        });
        expect(tenantB, "42501", rls, c -> {
            insertStaff(c, a, a.pharmacyId(), UUID.randomUUID(), "STORE_USER", null, null, "ACTIVE");
            return null;
        });
        expect(tenantB, "42501", rls, c -> update(c, "insert into pharmacy.staff_preference (tenant_id, unit_id, membership_id, last_store_id, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid)", tenantA.toString(), a.unitId().toString(), UUID.randomUUID().toString(),
                a.pharmacyId().toString(), a.clinic().ownerUserId().toString()));
        // B's own tenant id, A's unit or store: the same-tenant composite foreign key refuses it
        expect(tenantB, "23503", "fk_store_unit", c -> update(c, "insert into pharmacy.store (unit_id, name, code, type, created_by) "
                + "values (?::uuid, 'Borrowed', 'BRW', 'CENTRAL', ?::uuid)", a.unitId().toString(), b.clinic().ownerUserId().toString()));
        expect(tenantB, "23503", "fk_store_staff_store", c -> update(c, "insert into pharmacy.store_staff (unit_id, store_id, membership_id, store_role, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, 'STORE_USER', ?::uuid)", b.unitId().toString(), a.pharmacyId().toString(),
                UUID.randomUUID().toString(), b.clinic().ownerUserId().toString()));
        expect(tenantB, "23503", "fk_staff_preference_store", c -> update(c, "insert into pharmacy.staff_preference (unit_id, membership_id, last_store_id, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid)", b.unitId().toString(), UUID.randomUUID().toString(), a.pharmacyId().toString(),
                b.clinic().ownerUserId().toString()));
        // with no tenant bound at all nothing can be inserted either
        assertThatThrownBy(() -> db().asApp(c -> update(c, "insert into pharmacy.unit (tenant_id, code, created_by) values (?::uuid, 'ZZZ', ?::uuid)",
                tenantA.toString(), a.clinic().ownerUserId().toString()))).isInstanceOf(SQLException.class);
        assertThat(pharmacyRows(a.clinic(), "store")).isEqualTo(3);
        assertThat(pharmacyRows(b.clinic(), "store")).isEqualTo(3);
    }

    @Test
    void theApplicationCanNeverDeleteOrTruncateAndHasNoSuchGrantOnAnyPharmacyTable() throws Exception {
        World w = world();
        for (String table : WP_A_TABLES) {
            expect(w.clinic().tenantId(), "42501", "permission denied", c -> update(c, "delete from pharmacy." + table));
            expect(w.clinic().tenantId(), "42501", "permission denied", c -> {
                try (var st = c.createStatement()) {
                    st.execute("truncate pharmacy." + table);
                }
                return null;
            });
        }
        Set<String> immutable = Set.of("approval_rule_version", "approval_rule", "approval_rule_level");
        Set<String> global = Set.of("catalogue_product", "catalogue_barcode", "reason_default");
        db().asApp(c -> {
            List<String> tables = new ArrayList<>();
            try (var st = c.createStatement(); var rs = st.executeQuery("select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                    + "where n.nspname = 'pharmacy' and c.relkind in ('r','p') order by 1")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            // the 23 tables of SAH-78 are still there; SAH-79 adds more (WP-D: 9 and 41 ledger partitions, WP-E to WP-H: purchase orders, receipts,
            // indents, dispatches, transfers and so on). Only the 23 keep the exact insert and update rule below; every pharmacy table keeps the
            // universal ones: no delete, truncate, references or trigger privilege and nothing for PUBLIC.
            List<String> sah78 = List.of("unit", "store", "store_staff", "staff_preference", "product", "product_store_setting", "opening_stock_import", "opening_stock_line",
                    "opening_stock_line_issue", "vendor", "vendor_store", "approval_rule_version", "approval_rule", "approval_rule_level", "approval_rule_set", "document_series",
                    "document_counter", "reason", "reason_default_state", "print_template_setting", "catalogue_product", "catalogue_barcode", "reason_default");
            assertThat(tables).as("the 23 tables of SAH-78 are in the pharmacy schema").containsAll(sah78);
            assertThat(tables.size()).as("more tables came with SAH-79").isGreaterThanOrEqualTo(23);
            for (String t : tables) {
                String full = "pharmacy." + t;
                for (String privilege : List.of("DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                    assertThat(DbAccess.text(c, "select has_table_privilege('sahaj_app', ?, ?)::text", full, privilege)).as(full + " " + privilege).isEqualTo("false");
                }
                if (!sah78.contains(t)) {
                    assertThat(DbAccess.count(c, "select count(*) from pg_class cl, aclexplode(cl.relacl) a where cl.oid = ?::regclass and a.grantee = 0", full))
                            .as(full + " grants to PUBLIC").isZero();
                    continue;
                }
                assertThat(DbAccess.text(c, "select has_table_privilege('sahaj_app', ?, 'SELECT')::text", full)).as(full + " SELECT").isEqualTo("true");
                boolean writable = !global.contains(t);
                assertThat(DbAccess.text(c, "select has_table_privilege('sahaj_app', ?, 'INSERT')::text", full)).as(full + " INSERT")
                        .isEqualTo(String.valueOf(writable));
                assertThat(DbAccess.text(c, "select has_table_privilege('sahaj_app', ?, 'UPDATE')::text", full)).as(full + " UPDATE")
                        .isEqualTo(String.valueOf(writable && !immutable.contains(t)));
                assertThat(DbAccess.count(c, "select count(*) from pg_class cl, aclexplode(cl.relacl) a where cl.oid = ?::regclass and a.grantee = 0", full))
                        .as(full + " grants to PUBLIC").isZero();
            }
            return null;
        });
    }

    // ---------------- constraints of the WP-A tables ----------------

    @Test
    void theUnitTableHoldsOneRowPerTenantAndChecksCodeGstinLicenceAndMode() throws Exception {
        World w = world();
        UUID t = w.clinic().tenantId();
        expect(t, "23505", "uq_unit_tenant", c -> update(c, "insert into pharmacy.unit (code, created_by) values ('ABC', ?::uuid)", w.clinic().ownerUserId().toString()));
        expect(t, "23514", "ck_unit_code", c -> update(c, "update pharmacy.unit set code = 'ab1'"));
        expect(t, "23514", "ck_unit_code", c -> update(c, "update pharmacy.unit set code = 'ABCD'"));
        expect(t, "23514", "ck_unit_gstin", c -> update(c, "update pharmacy.unit set gstin = '27AAAAA0000A1Z'"));
        expect(t, "23514", "ck_unit_gstin", c -> update(c, "update pharmacy.unit set gstin = '27aaaaa0000a1z5'"));
        expect(t, "23514", "ck_unit_drug_licence_no", c -> update(c, "update pharmacy.unit set drug_licence_no = ''"));
        expect(t, "23514", "ck_unit_drug_licence_no", c -> update(c, "update pharmacy.unit set drug_licence_no = repeat('L', 41)"));
        expect(t, "23514", "ck_unit_integration_mode", c -> update(c, "update pharmacy.unit set integration_mode = 'HYBRID'"));
        expect(t, "23514", "ck_unit_own_product_next_no", c -> update(c, "update pharmacy.unit set own_product_next_no = 0"));
        db().inTenant(t, c -> {
            assertThat(update(c, "update pharmacy.unit set drug_licence_no = repeat('L', 40), gstin = '27AAAAA0000A1Z5'")).isOne();
            return null;
        });
    }

    @Test
    void theStoreTableKeepsOneActiveCentralStoreTheIssuesFromRuleAndNameAndCodeUniquenessIgnoringCase() throws Exception {
        World w = world();
        UUID t = w.clinic().tenantId();
        expect(t, "23505", "uq_store_one_active_central", c -> {
            insertStore(c, w, "Second Central", "CS2", "CENTRAL", null, "ACTIVE");
            return null;
        });
        expect(t, "23514", "ck_store_issues_from", c -> {
            insertStore(c, w, "Central With Source", "CWS", "CENTRAL", w.centralId(), "INACTIVE");
            return null;
        });
        expect(t, "23514", "ck_store_issues_from", c -> {
            insertStore(c, w, "Ward Without Source", "WWS", "WARD", null, "ACTIVE");
            return null;
        });
        expect(t, "23514", "ck_store_issues_from_not_self", c -> update(c, "update pharmacy.store set issues_from_store_id = id where id = ?::uuid", w.pharmacyId().toString()));
        expect(t, "23505", "uq_store_name", c -> {
            insertStore(c, w, "MAIN PHARMACY", "NEWCODE", "PHARMACY", w.centralId(), "ACTIVE");
            return null;
        });
        expect(t, "23505", "uq_store_code", c -> {
            insertStore(c, w, "A New Name", "pharm".toUpperCase(), "PHARMACY", w.centralId(), "ACTIVE");
            return null;
        });
        expect(t, "23514", "ck_store_name", c -> update(c, "update pharmacy.store set name = ' padded ' where id = ?::uuid", w.pharmacyId().toString()));
        expect(t, "23514", "ck_store_name", c -> update(c, "update pharmacy.store set name = repeat('N', 61) where id = ?::uuid", w.pharmacyId().toString()));
        expect(t, "23514", "ck_store_code", c -> update(c, "update pharmacy.store set code = 'lower' where id = ?::uuid", w.pharmacyId().toString()));
        expect(t, "23514", "ck_store_code", c -> update(c, "update pharmacy.store set code = 'ELEVENCHARS' where id = ?::uuid", w.pharmacyId().toString()));
        expect(t, "23514", "ck_store_type", c -> update(c, "update pharmacy.store set type = 'KIOSK' where id = ?::uuid", w.pharmacyId().toString()));
        expect(t, "23514", "ck_store_status", c -> update(c, "update pharmacy.store set status = 'GONE' where id = ?::uuid", w.pharmacyId().toString()));
        // a switched-off Central Store frees the "one active Central Store" slot (history is kept)
        db().inTenant(t, c -> {
            update(c, "update pharmacy.store set status = 'INACTIVE' where type <> 'CENTRAL'");
            update(c, "update pharmacy.store set status = 'INACTIVE' where type = 'CENTRAL'");
            insertStore(c, w, "Central Store Two", "CS2", "CENTRAL", null, "ACTIVE");
            assertThat(DbAccess.count(c, "select count(*) from pharmacy.store where type = 'CENTRAL'")).isEqualTo(2);
            return null;
        });
    }

    @Test
    void theStoreGuardTriggerKeepsHasStockOneWayAndFreezesTypeAndCodeOnceTheStoreHasStock() throws Exception {
        World w = world();
        UUID t = w.clinic().tenantId();
        String id = w.pharmacyId().toString();
        // type and code can change while there is no stock
        db().inTenant(t, c -> {
            assertThat(update(c, "update pharmacy.store set type = 'SUB_STORE', code = 'SUB1' where id = ?::uuid", id)).isOne();
            return null;
        });
        commitAsTenant(t, "update pharmacy.store set has_stock = true where id = ?::uuid", id);
        expect(t, "23514", "store stock flag cannot be cleared", c -> update(c, "update pharmacy.store set has_stock = false where id = ?::uuid", id));
        expect(t, "23514", "store type and code are locked", c -> update(c, "update pharmacy.store set type = 'WARD' where id = ?::uuid", id));
        expect(t, "23514", "store type and code are locked", c -> update(c, "update pharmacy.store set code = 'OTHER' where id = ?::uuid", id));
        db().inTenant(t, c -> {
            assertThat(update(c, "update pharmacy.store set name = 'Renamed with stock', sale_allowed = true where id = ?::uuid", id)).isOne();
            return null;
        });
    }

    @Test
    void theStoreStaffTableKeepsOneRowPerPersonAndStoreOneActiveHodAndOneActiveDelegateAndTheDelegateDateRules() throws Exception {
        World w = world();
        UUID t = w.clinic().tenantId();
        UUID store = w.centralId();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        expect(t, "23505", "uq_store_staff_member", c -> {
            insertStaff(c, w, w.pharmacyId(), w.nurseMembership(), "STORE_USER", null, null, "ACTIVE");
            return null;
        });
        expect(t, "23505", "uq_store_staff_one_active_approver", c -> {
            insertStaff(c, w, store, p1, "APPROVER", null, null, "ACTIVE");
            insertStaff(c, w, store, p2, "APPROVER", null, null, "ACTIVE");
            return null;
        });
        expect(t, "23505", "uq_store_staff_one_active_delegate", c -> {
            insertStaff(c, w, store, p1, "DELEGATE", "2026-11-18", "2026-11-20", "ACTIVE");
            insertStaff(c, w, store, p2, "DELEGATE", "2026-11-18", "2026-11-20", "ACTIVE");
            return null;
        });
        // a REMOVED approver does not block a new one
        db().inTenant(t, c -> {
            insertStaff(c, w, store, p1, "APPROVER", null, null, "REMOVED");
            insertStaff(c, w, store, p2, "APPROVER", null, null, "ACTIVE");
            return null;
        });
        String delegateCheck = "ck_store_staff_delegate_dates";
        expect(t, "23514", delegateCheck, c -> {
            insertStaff(c, w, store, p3, "DELEGATE", null, null, "ACTIVE");
            return null;
        });
        expect(t, "23514", delegateCheck, c -> {
            insertStaff(c, w, store, p3, "DELEGATE", "2026-11-20", "2026-11-18", "ACTIVE");
            return null;
        });
        expect(t, "23514", delegateCheck, c -> {
            insertStaff(c, w, store, p3, "DELEGATE", "2026-11-18", "2027-02-17", "ACTIVE"); // 91 days
            return null;
        });
        expect(t, "23514", delegateCheck, c -> {
            insertStaff(c, w, store, p3, "STORE_USER", "2026-11-18", "2026-11-20", "ACTIVE");
            return null;
        });
        expect(t, "23514", delegateCheck, c -> {
            insertStaff(c, w, store, p3, "DELEGATE", "2026-11-18", null, "ACTIVE");
            return null;
        });
        expect(t, "23514", delegateCheck, c -> {
            insertStaff(c, w, store, p3, "DELEGATE", "2026-11-18", "2027-02-16", "ACTIVE"); // 90 days apart = 91 counting both ends
            return null;
        });
        db().inTenant(t, c -> {
            insertStaff(c, w, store, p3, "DELEGATE", "2026-11-18", "2027-02-15", "ACTIVE"); // 89 days apart = 90 counting both ends
            return null;
        });
        expect(t, "23514", "ck_store_staff_role", c -> {
            insertStaff(c, w, store, p3, "PHARMACIST", null, null, "ACTIVE");
            return null;
        });
        expect(t, "23514", "ck_store_staff_status", c -> {
            insertStaff(c, w, store, p3, "STORE_USER", null, null, "GONE");
            return null;
        });
    }

    @Test
    void theStaffPreferenceHoldsOneRowPerPersonAndTheStoreMustBeOfTheSameTenant() throws Exception {
        World w = world();
        UUID t = w.clinic().tenantId();
        // the world already remembered a working store for the nurse
        assertThat(pharmacyRows(w.clinic(), "staff_preference")).isOne();
        expect(t, "23505", "uq_staff_preference_member", c -> update(c, "insert into pharmacy.staff_preference (unit_id, membership_id, last_store_id, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid)", w.unitId().toString(), w.nurseMembership().toString(), w.centralId().toString(),
                w.clinic().ownerUserId().toString()));
        expect(t, "23503", "fk_staff_preference_store", c -> update(c, "insert into pharmacy.staff_preference (unit_id, membership_id, last_store_id, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid)", w.unitId().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                w.clinic().ownerUserId().toString()));
    }

    // ---------------- the additive platform changes ----------------

    @Test
    void theWidenedRoleCheckAcceptsStoreStaffAndEveryOldRoleAndRefusesAnythingElse() throws Exception {
        Clinic clinic = newClinic();
        for (String role : new String[] {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR", "STORE_STAFF"}) {
            UUID user = signUp(uniqueMobile()).userId();
            db().inTenant(clinic.tenantId(), c -> {
                assertThat(update(c, "insert into platform.membership (tenant_id, user_id, role, display_name) values (?::uuid, ?::uuid, ?, 'Role probe')",
                        clinic.tenantId().toString(), user.toString(), role)).as(role).isOne();
                return null;
            });
        }
        UUID user = signUp(uniqueMobile()).userId();
        expect(clinic.tenantId(), "23514", "ck_membership_role", c -> update(c,
                "insert into platform.membership (tenant_id, user_id, role, display_name) values (?::uuid, ?::uuid, 'STORE_KEEPER', 'Bad role')",
                clinic.tenantId().toString(), user.toString()));
    }

    @Test
    void thePharmacyColumnIsNotNullWithDefaultFalseAndAClinicWithoutARowOrWithAFreshRowReadsOff() throws Exception {
        db().asApp(c -> {
            assertThat(DbAccess.text(c, "select is_nullable || '/' || column_default from information_schema.columns where table_schema = 'platform' "
                    + "and table_name = 'module_entitlement' and column_name = 'pharmacy_on'")).isEqualTo("NO/false");
            return null;
        });
        Clinic clinic = newClinic();
        commitAsTenant(clinic.tenantId(), "insert into platform.module_entitlement (tenant_id, updated_by) values (?::uuid, ?::uuid) "
                + "on conflict (tenant_id) do nothing", clinic.tenantId().toString(), clinic.ownerUserId().toString());
        assertThat(values(clinic, "select pharmacy_on::text from platform.module_entitlement").get(0)).isEqualTo("false");
        assertThat(ok(HttpMethod.GET, ME, clinic.owner(), null, 200).get("menu").toString()).doesNotContain("pharmacy");
    }

    @Test
    void theTwelveSah78MigrationsAreTheFirstTwelveInOrderNoneFailedAndTheChecksumsOf1000To1260AreTheFilesAsTheyAre() throws Exception {
        // 1000..1250 are the eleven migrations of WP-A, the catalogue and review round 1 (1250 = B4, one index); 1260 (B5, review round 2, DEC-071) adds line_request_count
        String[] expected = {"1000", "1010", "1020", "1030", "1100", "1200", "1210", "1220", "1230", "1240", "1250", "1260"};
        java.util.Map<String, Integer> fileChecksums = new java.util.HashMap<>();
        for (String v : expected) {
            fileChecksums.put(v, flywayChecksumOf("V2026_12_17_" + v));
        }
        db().asMigrator(c -> {
            List<String> versions = new ArrayList<>();
            java.util.Map<String, Integer> checksums = new java.util.HashMap<>();
            try (var st = c.createStatement(); var rs = st.executeQuery("select version, success, checksum from platform.flyway_schema_history "
                    + "where version like '2026.12.17.%' order by installed_rank")) {
                while (rs.next()) {
                    assertThat(rs.getBoolean(2)).as("success of " + rs.getString(1)).isTrue();
                    versions.add(rs.getString(1));
                    checksums.put(rs.getString(1).substring("2026.12.17.".length()), rs.getInt(3));
                }
            }
            List<String> want = new ArrayList<>();
            for (String v : expected) {
                want.add("2026.12.17." + v);
            }
            assertThat(versions).as("the 12 of SAH-78 are applied first in version order (SAH-79 WP-D 1300 to 1390 and the 1270 to 1299 hardening follow, see PharmacyWpDPostingDocsIT)")
                    .startsWith(want.toArray(new String[0]));
            assertThat(DbAccess.count(c, "select count(*) from platform.flyway_schema_history where success = false")).isZero();
            assertThat(versions).as("at least the twelve of SAH-78").hasSizeGreaterThanOrEqualTo(12);
            // a Flyway checksum is the CRC-32 of the file read line by line; an applied migration that was edited would differ
            for (String v : expected) {
                assertThat(checksums.get(v)).as("checksum of V2026_12_17_" + v + " equals its file").isEqualTo(fileChecksums.get(v));
            }
            return null;
        });
    }

    @Test
    void migration1250AddsTheLowerCaseBarcodeIndexAndTheTenantProductBarcodeKeyIsStillThere() throws Exception {
        db().asMigrator(c -> {
            String def = DbAccess.text(c, "select indexdef from pg_indexes where schemaname = 'pharmacy' and tablename = 'catalogue_barcode' "
                    + "and indexname = 'ix_catalogue_barcode_lower'");
            assertThat(def).isNotNull().contains("lower(").contains("barcode");
            assertThat(DbAccess.count(c, "select count(*) from pg_indexes where schemaname = 'pharmacy' and indexname = 'uq_product_barcode'")).isOne();
            return null;
        });
    }

    /** The Flyway checksum of a SQL migration found on the class path by its file-name prefix. */
    private int flywayChecksumOf(String prefix) throws Exception {
        var resources = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/" + prefix + "__*.sql");
        assertThat(resources).as("file " + prefix).hasSize(1);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(resources[0].getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first && line.startsWith("﻿")) {
                    line = line.substring(1);
                }
                first = false;
                crc.update(line.getBytes(StandardCharsets.UTF_8));
            }
        }
        return (int) crc.getValue();
    }

    // ---------------- the sample catalogue seed ----------------

    @Test
    void withTheTestProfilePlaceholderTrueTheCatalogueHoldsThirtySampleProductsAndSixBarcodesAllMarkedSample() throws Exception {
        assertThat(env.getProperty("spring.flyway.placeholders.sahajSampleData")).isEqualTo("true");
        db().asApp(c -> {
            assertThat(DbAccess.count(c, "select count(*) from pharmacy.catalogue_product")).isEqualTo(30);
            assertThat(DbAccess.count(c, "select count(*) from pharmacy.catalogue_product where sample and active")).isEqualTo(30);
            assertThat(DbAccess.count(c, "select count(*) from pharmacy.catalogue_barcode")).isEqualTo(6);
            assertThat(DbAccess.count(c, "select count(distinct code) from pharmacy.catalogue_product where code ~ '^G-[0-9]{6}$'")).isEqualTo(30);
            assertThat(DbAccess.count(c, "select count(*) from pharmacy.catalogue_product where manufacturer is not null and manufacturer not like '%(fake)%'"))
                    .as("manufacturer names are made up").isZero();
            assertThat(DbAccess.count(c, "select count(*) from pharmacy.catalogue_product where dup_key <> lower(dup_key) or dup_key !~ '^[^|]+[|][^|]+[|][^|]+$'"))
                    .as("duplicate key format salt|strength|form, lower case").isZero();
            assertThat(DbAccess.text(c, "select dup_key from pharmacy.catalogue_product where code = 'G-000101'")).isEqualTo("ceftriaxone|1000mg|injection");
            assertThat(DbAccess.count(c, "select count(*) from pharmacy.reason_default")).as("36 seeded reasons in every environment (13 of SAH-78 and 23 of SAH-79 migration 1380)").isEqualTo(36);
            return null;
        });
    }

    @Test
    void theSeedScriptInsertsNothingWhenThePlaceholderIsNotExactlyTrue() throws Exception {
        String sql;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("db/migration/V2026_12_17_1030__pharmacy_catalogue_sample_seed.sql")) {
            assertThat(in).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(sql).contains("${sahajSampleData}");
        for (String value : new String[] {"false", "TRUE", "", "yes", "1"}) {
            String script = sql.replace("${sahajSampleData}", value);
            db().asMigrator(c -> {
                long before = DbAccess.count(c, "select count(*) from pharmacy.catalogue_product");
                try (var st = c.createStatement()) {
                    st.execute(script);
                }
                assertThat(DbAccess.count(c, "select count(*) from pharmacy.catalogue_product")).as("placeholder '" + value + "'").isEqualTo(before);
                assertThat(DbAccess.count(c, "select count(*) from pharmacy.catalogue_barcode")).isEqualTo(6);
                return null;
            });
        }
    }

    // ---------------- the global tables ----------------

    @Test
    void theGlobalTablesAreReadableWithNoTenantAndRefuseEveryWriteFromTheApplicationAndDeleteAndTruncateFromTheMigratorToo() throws Exception {
        for (String table : new String[] {"catalogue_product", "catalogue_barcode", "reason_default"}) {
            for (String write : new String[] {"delete from pharmacy." + table, "truncate pharmacy." + table,
                "update pharmacy." + table + " set " + (table.equals("catalogue_barcode") ? "barcode = barcode" : table.equals("reason_default") ? "label = label" : "name = name")}) {
                assertThatThrownBy(() -> db().asApp(c -> {
                    try (var st = c.createStatement()) {
                        st.execute(write);
                    }
                    return null;
                })).as("as sahaj_app: " + write).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
            }
            for (String write : new String[] {"delete from pharmacy." + table, "truncate pharmacy." + table}) {
                assertThatThrownBy(() -> db().asMigrator(c -> {
                    try (var st = c.createStatement()) {
                        st.execute(write);
                    }
                    return null;
                })).as("as sahaj_migrator: " + write).isInstanceOfSatisfying(SQLException.class, e -> {
                    // a truncate of a table that other tables reference is refused by PostgreSQL itself (0A000) before the trigger can fire;
                    // every other delete or truncate reaches the trigger of the design note (42501, "never deleted")
                    if (write.startsWith("truncate") && "0A000".equals(e.getSQLState())) {
                        assertThat(e.getMessage()).contains("foreign key");
                    } else {
                        assertThat(e.getSQLState()).isEqualTo("42501");
                        assertThat(e.getMessage()).contains("never deleted");
                    }
                });
            }
        }
        // the migrator maintains the content: an update is allowed (rolled back)
        db().asMigrator(c -> {
            assertThat(update(c, "update pharmacy.catalogue_product set active = active where code = 'G-000101'")).isOne();
            return null;
        });
        // and the application cannot insert a product even for itself
        assertThatThrownBy(() -> db().asApp(c -> update(c, "insert into pharmacy.reason_default (reason_key, list_key, label, sort) values ('x.y', 'damage', 'Hacked', 99)")))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }
}
