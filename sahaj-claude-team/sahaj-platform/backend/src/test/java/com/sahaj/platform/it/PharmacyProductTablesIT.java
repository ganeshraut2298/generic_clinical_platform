package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-78 WP-B database checks (design note docs/database/SAH-78-pharmacy-masters.md section 9): the five new tenant tables
 * {@code product}, {@code product_store_setting}, {@code opening_stock_import}, {@code opening_stock_line} and
 * {@code opening_stock_line_issue}, as {@code sahaj_app}. Cross-tenant isolation (invisible, unchangeable, not writable, nothing
 * visible with no tenant bound), the constraints and the guard triggers that stand behind the service. The generic checks (forced
 * row level security, exactly the select, insert and update policies, no delete or truncate grant) are in
 * {@code RowLevelSecurityCatalogIT} and {@code PharmacyTablesIT}. The import state rules that the API reaches are proved in
 * {@link PharmacyOpeningStockIT}. Test plan: docs/test-plans/SAH-78.md (T78-DB-16 and following).
 */
@OnTestDatabase
class PharmacyProductTablesIT extends Sah78BSupport {

    private static final String[] NEW_TABLES = {"product", "product_store_setting", "opening_stock_import", "opening_stock_line",
        "opening_stock_line_issue"};

    /** A clinic with a Central Store, an adopted and an own product, one saved store setting and one draft import with a line and an issue. */
    private record World(Clinic clinic, UUID unit, UUID store, UUID adopted, UUID own, UUID importId, UUID lineId) {
    }

    private World world() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode central = centralStore(clinic);
        JsonNode adopted = adopt(clinic, "G-000103");
        JsonNode own = createOwn(clinic, ownBody("Table test product"));
        Map<String, Object> local = localBody(adopted);
        settings(local, setting(idOf(central), 10, "A-1"));
        ok(HttpMethod.PUT, localUrl(adopted.get("productId").asText()), clinic.owner(), js(local), 200);
        JsonNode started = startImport(clinic.owner(), idOf(central), 1);
        addRows(clinic.owner(), idOf(central), idOf(started), rows(row(1, "CS", "G-999999", "TB1", farExpiry(), "10", "5", "1")));
        UUID unit = UUID.fromString(values(clinic, "select id::text from pharmacy.unit").get(0));
        UUID line = UUID.fromString(values(clinic, "select id::text from pharmacy.opening_stock_line").get(0));
        return new World(clinic, unit, UUID.fromString(idOf(central)), UUID.fromString(adopted.get("productId").asText()),
                UUID.fromString(own.get("productId").asText()), UUID.fromString(idOf(started)), line);
    }

    private void expect(UUID tenant, String sqlState, String messagePart, Work work) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db().inTenant(tenant, c -> {
            work.run(c);
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo(sqlState);
            assertThat(e.getMessage()).contains(messagePart);
        });
    }

    @FunctionalInterface
    private interface Work {
        void run(Connection c) throws SQLException;
    }

    private static void sql(Connection c, String sql, Object... params) throws SQLException {
        DbAccess.execute(c, sql, params);
    }

    // ---------------- row level security ----------------

    @Test
    void anotherTenantsRowsOfTheFiveNewTablesAreInvisibleUnchangeableAndNothingShowsWithNoTenantBound() throws Exception {
        World a = world();
        World b = world();
        UUID tenantA = a.clinic().tenantId();
        UUID tenantB = b.clinic().tenantId();
        for (String table : NEW_TABLES) {
            long ownA = db().inTenant(tenantA, c -> DbAccess.count(c, "select count(*) from pharmacy." + table));
            long ownB = db().inTenant(tenantB, c -> DbAccess.count(c, "select count(*) from pharmacy." + table));
            assertThat(ownA).as(table + " rows of A").isPositive();
            assertThat(ownB).as(table + " rows of B").isPositive();
            List<UUID> idsOfA = new ArrayList<>();
            db().inTenant(tenantA, c -> {
                try (var st = c.createStatement(); var rs = st.executeQuery("select id::text from pharmacy." + table)) {
                    while (rs.next()) {
                        idsOfA.add(UUID.fromString(rs.getString(1)));
                    }
                }
                return null;
            });
            assertThat(idsOfA).hasSize((int) ownA);
            db().inTenant(tenantB, c -> {
                assertThat(DbAccess.count(c, "select count(*) from pharmacy." + table + " where tenant_id = ?::uuid", tenantA.toString()))
                        .as(table + " rows of A seen by B").isZero();
                assertThat(DbAccess.count(c, "select count(*) from pharmacy." + table + " where id = any (?::uuid[])",
                        c.createArrayOf("uuid", idsOfA.toArray()))).as(table + " by id").isZero();
                assertThat(DbAccess.count(c, "select count(*) from pharmacy." + table)).isEqualTo(ownB);
                try (var ps = c.prepareStatement("update pharmacy." + table + " set tenant_id = tenant_id where tenant_id = ?::uuid")) {
                    ps.setString(1, tenantA.toString());
                    assertThat(ps.executeUpdate()).as(table + " rows of A changed by B").isZero();
                } catch (SQLException refusedByGuard) {
                    // a guard trigger may refuse before the policy hides the row; either way nothing of A changed
                    assertThat(refusedByGuard.getSQLState()).isEqualTo("23514");
                }
                return null;
            });
            long unbound = db().asApp(c -> DbAccess.count(c, "select count(*) from pharmacy." + table));
            assertThat(unbound).as(table + " with no tenant bound (fails closed)").isZero();
        }
    }

    @Test
    void anInsertForAnotherTenantIsRefusedAndAnInsertPointingAtAnotherTenantsUnitOrProductIsRefusedByTheCompositeKeys() throws Exception {
        World a = world();
        World b = world();
        UUID tenantA = a.clinic().tenantId();
        UUID tenantB = b.clinic().tenantId();
        // tenant B bound, tenant A's id in the row: the row level security check refuses it
        expect(tenantB, "42501", "row-level security", c -> sql(c, "insert into pharmacy.product (tenant_id, unit_id, kind, catalogue_product_id, created_by) "
                + "values (?::uuid, ?::uuid, 'ADOPTED', (select id from pharmacy.catalogue_product where code = 'G-000105'), ?::uuid)",
                tenantA.toString(), a.unit().toString(), b.clinic().ownerUserId().toString()));
        expect(tenantB, "42501", "row-level security", c -> sql(c, "insert into pharmacy.product_store_setting (tenant_id, unit_id, product_id, store_id, reorder_level, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 1, ?::uuid)", tenantA.toString(), a.unit().toString(), a.adopted().toString(),
                a.store().toString(), b.clinic().ownerUserId().toString()));
        expect(tenantB, "42501", "row-level security", c -> sql(c, "insert into pharmacy.opening_stock_import (tenant_id, unit_id, store_id, row_count, uploaded_by, client_key, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, 1, ?::uuid, ?::uuid, ?::uuid)", tenantA.toString(), a.unit().toString(), a.store().toString(),
                b.clinic().ownerUserId().toString(), UUID.randomUUID().toString(), b.clinic().ownerUserId().toString()));
        // own tenant id, but pointing at the other tenant's unit, product, store or import: the same-tenant composite foreign key refuses it
        expect(tenantB, "23503", "fk_product_unit", c -> sql(c, "insert into pharmacy.product (tenant_id, unit_id, kind, catalogue_product_id, created_by) "
                + "values (?::uuid, ?::uuid, 'ADOPTED', (select id from pharmacy.catalogue_product where code = 'G-000105'), ?::uuid)",
                tenantB.toString(), a.unit().toString(), b.clinic().ownerUserId().toString()));
        expect(tenantB, "23503", "fk_product_store_setting_product", c -> sql(c, "insert into pharmacy.product_store_setting "
                + "(tenant_id, unit_id, product_id, store_id, reorder_level, created_by) values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 1, ?::uuid)",
                tenantB.toString(), b.unit().toString(), a.adopted().toString(), b.store().toString(), b.clinic().ownerUserId().toString()));
        expect(tenantB, "23503", "fk_product_store_setting_store", c -> sql(c, "insert into pharmacy.product_store_setting "
                + "(tenant_id, unit_id, product_id, store_id, reorder_level, created_by) values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 1, ?::uuid)",
                tenantB.toString(), b.unit().toString(), b.adopted().toString(), a.store().toString(), b.clinic().ownerUserId().toString()));
        expect(tenantB, "23503", "fk_opening_stock_import_store", c -> sql(c, "insert into pharmacy.opening_stock_import "
                + "(tenant_id, unit_id, store_id, row_count, uploaded_by, client_key, created_by) values (?::uuid, ?::uuid, ?::uuid, 1, ?::uuid, ?::uuid, ?::uuid)",
                tenantB.toString(), b.unit().toString(), a.store().toString(), b.clinic().ownerUserId().toString(), UUID.randomUUID().toString(),
                b.clinic().ownerUserId().toString()));
        // a line of tenant B in tenant A's import: the import is not visible to B, so the draft guard refuses it
        expect(tenantB, "23514", "draft", c -> sql(c, "insert into pharmacy.opening_stock_line (tenant_id, unit_id, import_id, row_no, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, 1, ?::uuid)", tenantB.toString(), b.unit().toString(), a.importId().toString(),
                b.clinic().ownerUserId().toString()));
        assertThat(count(a.clinic(), "select count(*) from pharmacy.product")).as("A is unchanged").isEqualTo(2);
        assertThat(count(b.clinic(), "select count(*) from pharmacy.product")).isEqualTo(2);
    }

    // ---------------- product ----------------

    @Test
    void anAdoptedRowHoldsNoGlobalFieldAnOwnRowHoldsAllOfThemAndTheTwoKindsAreExclusive() throws Exception {
        World w = world();
        UUID t = w.clinic().tenantId();
        String user = w.clinic().ownerUserId().toString();
        String catalogue = "(select id from pharmacy.catalogue_product where code = 'G-000107')";
        // adopted with a global field copied in, or without the link: refused
        expect(t, "23514", "ck_product_kind_fields", c -> sql(c, "insert into pharmacy.product (tenant_id, unit_id, kind, catalogue_product_id, name, created_by) "
                + "values (?::uuid, ?::uuid, 'ADOPTED', " + catalogue + ", 'Copied name', ?::uuid)", t.toString(), w.unit().toString(), user));
        expect(t, "23514", "ck_product_kind_fields", c -> sql(c, "insert into pharmacy.product (tenant_id, unit_id, kind, created_by) "
                + "values (?::uuid, ?::uuid, 'ADOPTED', ?::uuid)", t.toString(), w.unit().toString(), user));
        expect(t, "23514", "ck_product_kind_fields", c -> sql(c, "insert into pharmacy.product (tenant_id, unit_id, kind, catalogue_product_id, made_in_house, created_by) "
                + "values (?::uuid, ?::uuid, 'ADOPTED', " + catalogue + ", true, ?::uuid)", t.toString(), w.unit().toString(), user));
        // own with a catalogue link, or with a field missing: refused
        String ownColumns = "(tenant_id, unit_id, kind, code, name, salt, strength, form, made_in_house, hsn, gst_rate, schedule, ndps, purchase_unit, issue_unit, "
                + "conversion, dup_key, created_by";
        String ownValues = "?::uuid, ?::uuid, 'OWN', 'U-ZZZ-0001', 'Name', 'Salt', '5 mg', 'tablet', true, '3004', 12, 'NONE', false, 'Box', 'piece', 10, 'salt|5mg|tablet', ?::uuid";
        expect(t, "23514", "ck_product_kind_fields", c -> sql(c, "insert into pharmacy.product " + ownColumns + ", catalogue_product_id) values (" + ownValues
                + ", " + catalogue + ")", t.toString(), w.unit().toString(), user));
        expect(t, "23514", "ck_product_kind_fields", c -> sql(c, "insert into pharmacy.product (tenant_id, unit_id, kind, code, name, salt, strength, form, made_in_house, hsn, "
                + "gst_rate, schedule, ndps, purchase_unit, issue_unit, conversion, created_by) values (?::uuid, ?::uuid, 'OWN', 'U-ZZZ-0001', 'Name', 'Salt', '5 mg', "
                + "'tablet', true, '3004', 12, 'NONE', false, 'Box', 'piece', 10, ?::uuid)", t.toString(), w.unit().toString(), user)); // no dup_key
        expect(t, "23514", "ck_product_kind_fields", c -> sql(c, "insert into pharmacy.product " + ownColumns + ") values (" + ownValues.replace("true", "false") + ")",
                t.toString(), w.unit().toString(), user)); // not made in-house and no manufacturer
        // limits
        for (String[] bad : new String[][] {{"code", "'U-ZZZ-1'", "ck_product_code"}, {"code", "'X-ZZZ-0001'", "ck_product_code"}, {"hsn", "'123'", "ck_product_hsn"},
            {"gst_rate", "101", "ck_product_gst_rate"}, {"schedule", "'Y'", "ck_product_schedule"}, {"conversion", "0", "ck_product_conversion"},
            {"conversion", "10001", "ck_product_conversion"}}) {
            String values = ownValues.replace("'U-ZZZ-0001'", "'U-ZZZ-0002'");
            String replaced = switch (bad[0]) {
                case "code" -> values.replace("'U-ZZZ-0002'", bad[1]);
                case "hsn" -> values.replace("'3004'", bad[1]);
                case "gst_rate" -> values.replace(", 12,", ", " + bad[1] + ",");
                case "schedule" -> values.replace("'NONE'", bad[1]);
                default -> values.replace(", 10, 'salt", ", " + bad[1] + ", 'salt");
            };
            expect(t, "23514", bad[2], c -> sql(c, "insert into pharmacy.product " + ownColumns + ") values (" + replaced + ")", t.toString(), w.unit().toString(), user));
        }
        // a correct own row is accepted, and a second with the same code is refused
        db().inTenant(t, c -> {
            sql(c, "insert into pharmacy.product " + ownColumns + ") values (" + ownValues + ")", t.toString(), w.unit().toString(), user);
            return null;
        });
        expect(t, "23505", "uq_product_code", c -> {
            sql(c, "insert into pharmacy.product " + ownColumns + ") values (" + ownValues + ")", t.toString(), w.unit().toString(), user);
            sql(c, "insert into pharmacy.product " + ownColumns + ") values (" + ownValues + ")", t.toString(), w.unit().toString(), user);
        });
    }

    @Test
    void adoptingTheSameCatalogueProductTwiceIsRefusedAndOneActiveProductHoldsABarcodeCaseInsensitively() throws Exception {
        World w = world();
        UUID t = w.clinic().tenantId();
        String user = w.clinic().ownerUserId().toString();
        expect(t, "23505", "uq_product_adopted", c -> sql(c, "insert into pharmacy.product (tenant_id, unit_id, kind, catalogue_product_id, created_by) "
                + "values (?::uuid, ?::uuid, 'ADOPTED', (select id from pharmacy.catalogue_product where code = 'G-000103'), ?::uuid)",
                t.toString(), w.unit().toString(), user));
        // another clinic may adopt it (the key is per tenant and unit)
        World other = world();
        assertThat(count(other.clinic(), "select count(*) from pharmacy.product where catalogue_product_id = (select id from pharmacy.catalogue_product where code = 'G-000103')")).isOne();
        // barcode: one ACTIVE product per barcode, any case; an INACTIVE product does not hold it; null barcodes never collide
        commitAsTenant(t, "update pharmacy.product set barcode = 'BC-ONE' where id = ?::uuid", w.adopted().toString());
        expect(t, "23505", "uq_product_barcode", c -> sql(c, "update pharmacy.product set barcode = 'bc-one' where id = ?::uuid", w.own().toString()));
        db().inTenant(t, c -> {
            sql(c, "update pharmacy.product set status = 'INACTIVE' where id = ?::uuid", w.adopted().toString());
            sql(c, "update pharmacy.product set barcode = 'bc-one' where id = ?::uuid", w.own().toString());
            return null;
        });
        expect(t, "23505", "uq_product_barcode", c -> {
            sql(c, "update pharmacy.product set status = 'INACTIVE' where id = ?::uuid", w.adopted().toString());
            sql(c, "update pharmacy.product set barcode = 'bc-one' where id = ?::uuid", w.own().toString());
            sql(c, "update pharmacy.product set status = 'ACTIVE' where id = ?::uuid", w.adopted().toString());
        });
        expect(t, "23514", "ck_product_barcode", c -> sql(c, "update pharmacy.product set barcode = 'bad barcode' where id = ?::uuid", w.own().toString()));
        expect(t, "23514", "ck_product_status", c -> sql(c, "update pharmacy.product set status = 'GONE' where id = ?::uuid", w.own().toString()));
    }

    @Test
    void theProductGuardKeepsKindLinkCodeAndUnitFixedAndTheStockFlagOneWayAndLocksTheUnitsOnceItHasStock() throws Exception {
        World w = world();
        UUID t = w.clinic().tenantId();
        String own = w.own().toString();
        for (String change : new String[] {"kind = 'ADOPTED'", "code = 'U-ZZZ-9999'", "catalogue_product_id = (select id from pharmacy.catalogue_product where code = 'G-000101')"}) {
            expect(t, "23514", "cannot change", c -> sql(c, "update pharmacy.product set " + change + " where id = ?::uuid", own));
        }
        expect(t, "23514", "cannot change", c -> sql(c, "update pharmacy.product set code = 'U-ZZZ-9999' where id = ?::uuid", w.adopted().toString()));
        commitAsTenant(t, "update pharmacy.product set has_stock = true where id = ?::uuid", own);
        expect(t, "23514", "stock flag cannot be cleared", c -> sql(c, "update pharmacy.product set has_stock = false where id = ?::uuid", own));
        for (String change : new String[] {"purchase_unit = 'Pack'", "issue_unit = 'strip'", "conversion = 99"}) {
            expect(t, "23514", "locked once the product has stock", c -> sql(c, "update pharmacy.product set " + change + " where id = ?::uuid", own));
        }
        // the other fields stay changeable; the unlocked adopted product (no stock) too
        db().inTenant(t, c -> {
            sql(c, "update pharmacy.product set name = 'Renamed', hsn = '3005', alias = 'x', returnable = false where id = ?::uuid", own);
            sql(c, "update pharmacy.product set alias = 'y', has_stock = true where id = ?::uuid", w.adopted().toString());
            return null;
        });
    }

    // ---------------- product_store_setting ----------------

    @Test
    void thereIsOneSettingPerProductAndStoreWithTheReorderRangeAndRackLengthChecked() throws Exception {
        World w = world();
        UUID t = w.clinic().tenantId();
        String user = w.clinic().ownerUserId().toString();
        assertThat(count(w.clinic(), "select count(*) from pharmacy.product_store_setting")).isOne();
        expect(t, "23505", "uq_product_store_setting", c -> sql(c, "insert into pharmacy.product_store_setting (tenant_id, unit_id, product_id, store_id, reorder_level, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 5, ?::uuid)", t.toString(), w.unit().toString(), w.adopted().toString(), w.store().toString(), user));
        for (String[] bad : new String[][] {{"reorder_level", "-1", "ck_product_store_setting_reorder"}, {"reorder_level", "1000000", "ck_product_store_setting_reorder"}}) {
            expect(t, "23514", bad[2], c -> sql(c, "update pharmacy.product_store_setting set " + bad[0] + " = " + bad[1] + " where product_id = ?::uuid", w.adopted().toString()));
        }
        expect(t, "23514", "ck_product_store_setting_rack", c -> sql(c, "update pharmacy.product_store_setting set rack = ? where product_id = ?::uuid", "R".repeat(31), w.adopted().toString()));
        expect(t, "23514", "ck_product_store_setting_rack", c -> sql(c, "update pharmacy.product_store_setting set rack = '' where product_id = ?::uuid", w.adopted().toString()));
        db().inTenant(t, c -> {
            sql(c, "update pharmacy.product_store_setting set reorder_level = 999999, rack = ? where product_id = ?::uuid", "R".repeat(30), w.adopted().toString());
            sql(c, "update pharmacy.product_store_setting set reorder_level = 0 where product_id = ?::uuid", w.adopted().toString());
            return null;
        });
    }

    // ---------------- opening stock ----------------

    @Test
    void theImportTableChecksRowCountUploaderRejectionReplacementAndTheClientKeyPerUploader() throws Exception {
        World w = world();
        UUID t = w.clinic().tenantId();
        String uploader = UUID.randomUUID().toString();
        String insert = "insert into pharmacy.opening_stock_import (tenant_id, unit_id, store_id, row_count, uploaded_by, client_key, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, ?::uuid)";
        for (int bad : new int[] {0, 5001, -1}) {
            expect(t, "23514", "ck_opening_stock_import_row_count", c -> sql(c, insert, t.toString(), w.unit().toString(), w.store().toString(), bad, uploader,
                    UUID.randomUUID().toString(), uploader));
        }
        // created_by is the uploader
        expect(t, "23514", "ck_opening_stock_import_uploader", c -> sql(c, insert, t.toString(), w.unit().toString(), w.store().toString(), 5000, uploader,
                UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        // one client key per uploader: the same uploader twice is refused, another uploader may use the same key
        UUID key = UUID.randomUUID();
        db().inTenant(t, c -> {
            sql(c, insert, t.toString(), w.unit().toString(), w.store().toString(), 5000, uploader, key.toString(), uploader);
            String other = UUID.randomUUID().toString();
            sql(c, insert, t.toString(), w.unit().toString(), w.store().toString(), 1, other, key.toString(), other);
            return null;
        });
        expect(t, "23505", "uq_opening_stock_import_client_key", c -> {
            sql(c, insert, t.toString(), w.unit().toString(), w.store().toString(), 1, uploader, key.toString(), uploader);
            sql(c, insert, t.toString(), w.unit().toString(), w.store().toString(), 1, uploader, key.toString(), uploader);
        });
        // REPLACED needs the import that replaced it, and the other states must not carry one
        expect(t, "23514", "ck_opening_stock_import_replaced", c -> sql(c, "update pharmacy.opening_stock_import set status = 'REPLACED' where id = ?::uuid", w.importId().toString()));
        // a rejection carries a reason, and OTHER a note of at least 4 characters; no other status carries a reason
        String member = UUID.randomUUID().toString();
        expect(t, "23514", "ck_opening_stock_import_reject", c -> {
            sql(c, "update pharmacy.opening_stock_import set status = 'WAITING_APPROVAL', submitted_at = now() where id = ?::uuid", w.importId().toString());
            sql(c, "update pharmacy.opening_stock_import set status = 'REJECTED', decided_by = ?::uuid, decided_at = now(), reject_reason_key = 'OTHER', reject_note = 'abc' "
                    + "where id = ?::uuid", member, w.importId().toString());
        });
        expect(t, "23514", "ck_opening_stock_import_reject", c -> {
            sql(c, "update pharmacy.opening_stock_import set status = 'WAITING_APPROVAL', submitted_at = now() where id = ?::uuid", w.importId().toString());
            sql(c, "update pharmacy.opening_stock_import set status = 'REJECTED', decided_by = ?::uuid, decided_at = now() where id = ?::uuid", member, w.importId().toString());
        });
        expect(t, "23514", "ck_opening_stock_import_reject", c -> sql(c, "update pharmacy.opening_stock_import set reject_reason_key = 'WRONG_FILE' where id = ?::uuid", w.importId().toString()));
        // decided_by and decided_at go together, only for APPROVED and REJECTED
        expect(t, "23514", "ck_opening_stock_import_decision", c -> sql(c, "update pharmacy.opening_stock_import set decided_by = ?::uuid where id = ?::uuid", member, w.importId().toString()));
        // submitted_at is set once the import has left DRAFT, NOT_ACCEPTED and REPLACED
        expect(t, "23514", "ck_opening_stock_import_submitted", c -> sql(c, "update pharmacy.opening_stock_import set status = 'WAITING_APPROVAL' where id = ?::uuid", w.importId().toString()));
        // totals are never negative
        expect(t, "23514", "ck_opening_stock_import_total_value", c -> sql(c, "update pharmacy.opening_stock_import set total_value_paise = -1 where id = ?::uuid", w.importId().toString()));
    }

    @Test
    void theLineAndIssueTablesCheckRowNumberAmountsBatchAndTheIssueVocabularyAndKeepOneLinePerRow() throws Exception {
        World w = world();
        UUID t = w.clinic().tenantId();
        String user = w.clinic().ownerUserId().toString();
        String member = UUID.randomUUID().toString();
        String insertLine = "insert into pharmacy.opening_stock_line (tenant_id, unit_id, import_id, row_no, created_by) values (?::uuid, ?::uuid, ?::uuid, ?, ?::uuid)";
        // the import of the world has one row (row_count 1); the draft guard refuses a row number beyond it, the table a row number of 0
        expect(t, "23514", "ck_opening_stock_line_row_no", c -> sql(c, insertLine, t.toString(), w.unit().toString(), w.importId().toString(), 0, member));
        expect(t, "23514", "beyond the import row count", c -> sql(c, insertLine, t.toString(), w.unit().toString(), w.importId().toString(), 2, member));
        expect(t, "23505", "uq_opening_stock_line_row", c -> sql(c, insertLine, t.toString(), w.unit().toString(), w.importId().toString(), 1, member));
        String line = w.lineId().toString();
        expect(t, "23514", "ck_opening_stock_line_qty", c -> sql(c, "update pharmacy.opening_stock_line set qty = 0 where id = ?::uuid", line));
        expect(t, "23514", "ck_opening_stock_line_mrp", c -> sql(c, "update pharmacy.opening_stock_line set mrp = -1 where id = ?::uuid", line));
        expect(t, "23514", "ck_opening_stock_line_rate", c -> sql(c, "update pharmacy.opening_stock_line set purchase_rate = -0.01 where id = ?::uuid", line));
        expect(t, "23514", "ck_opening_stock_line_value", c -> sql(c, "update pharmacy.opening_stock_line set value_paise = -1 where id = ?::uuid", line));
        expect(t, "23514", "ck_opening_stock_line_batch_no", c -> sql(c, "update pharmacy.opening_stock_line set batch_no = ? where id = ?::uuid", "b".repeat(21), line));
        expect(t, "23514", "ck_opening_stock_line_batch_raw", c -> sql(c, "update pharmacy.opening_stock_line set batch_raw = ? where id = ?::uuid", "b".repeat(41), line));
        // the largest values are accepted
        db().inTenant(t, c -> {
            sql(c, "update pharmacy.opening_stock_line set qty = 999999, mrp = 9999999.9999, purchase_rate = 9999999.9999, value_paise = 999999900000000, batch_no = ? where id = ?::uuid",
                    "b".repeat(20), line);
            return null;
        });
        // issues: vocabulary of field, code and severity
        String insertIssue = "insert into pharmacy.opening_stock_line_issue (tenant_id, unit_id, line_id, field, code, severity, created_by) values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?::uuid)";
        expect(t, "23514", "ck_opening_stock_line_issue_field", c -> sql(c, insertIssue, t.toString(), w.unit().toString(), line, "notes", "batch-invalid", "ERROR", user));
        expect(t, "23514", "ck_opening_stock_line_issue_code", c -> sql(c, insertIssue, t.toString(), w.unit().toString(), line, "batch", "made-up", "ERROR", user));
        expect(t, "23514", "ck_opening_stock_line_issue_severity", c -> sql(c, insertIssue, t.toString(), w.unit().toString(), line, "batch", "batch-invalid", "INFO", user));
        // every code of the vocabulary is accepted (one transaction, rolled back)
        db().inTenant(t, c -> {
            for (String code : new String[] {"store-mismatch", "product-unknown", "batch-invalid", "batch-repeated", "expiry-invalid", "expiry-past", "mrp-invalid",
                "rate-invalid", "quantity-invalid", "rate-above-mrp"}) {
                sql(c, insertIssue, t.toString(), w.unit().toString(), line, "batch", code, "ERROR", user);
            }
            // the only change an issue allows is "superseded", false to true, once
            sql(c, "update pharmacy.opening_stock_line_issue set superseded = true where line_id = ?::uuid and code = 'batch-invalid'", line);
            return null;
        });
        expect(t, "23514", "can only be marked superseded", c -> {
            sql(c, insertIssue, t.toString(), w.unit().toString(), line, "batch", "batch-invalid", "ERROR", user);
            sql(c, "update pharmacy.opening_stock_line_issue set superseded = true where line_id = ?::uuid and code = 'batch-invalid'", line);
            sql(c, "update pharmacy.opening_stock_line_issue set superseded = false where line_id = ?::uuid and code = 'batch-invalid'", line);
        });
        expect(t, "23514", "can only be marked superseded", c -> {
            sql(c, insertIssue, t.toString(), w.unit().toString(), line, "batch", "rate-invalid", "ERROR", user);
            sql(c, "update pharmacy.opening_stock_line_issue set code = 'expiry-past', superseded = true where line_id = ?::uuid and code = 'rate-invalid'", line);
        });
    }
}
