package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpMethod;

/**
 * Shared set-up of the SAH-29 integration tests (read-only packages, DEC-030). The app role can neither insert a
 * package nor set the package copy on an admission (that is the point of the slice), so a test sets them as the table
 * owner {@code sahaj_migrator}: in ONE transaction {@code NO FORCE ROW LEVEL SECURITY}, the write, {@code FORCE ROW
 * LEVEL SECURITY} again (docs/database/SAH-29.md section 4, the same pattern as {@code backdate} and
 * {@code insertSignoffAt}). DDL is transactional, so other sessions never see the table unforced and an error rolls
 * everything back.
 *
 * <p>Expected bills are worked out here from the story (AC 3), never read from the application. Default rates
 * (paise): General bed 80,000 (multiplier 1), Private 250,000 (1.5), ICU 500,000 (2), Semi-private 150,000 (1.25).
 */
abstract class PackageSupport extends DischargeSupport {

    static final String PACKAGES = IPD + "/packages";
    static final String MODULES_ENTITLEMENT = "platform.module_entitlement";

    /** The fixed small text of a package row lives in the client; the API sends only these four fields. */
    static final List<String> PACKAGE_FIELDS = List.of("id", "name", "pricePaise", "includedDays");

    // ---------------- package rows, set as the table owner ----------------

    /** Work on one open owner connection with row level security of the table switched off. */
    @FunctionalInterface
    interface OwnerWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    /**
     * Runs the work as the table owner with {@code table} NOT forced, then forces it again. When {@code commit} is
     * false the transaction is rolled back (a probe); an error always rolls back, so FORCE is never left off.
     */
    protected <T> T asOwner(String table, boolean commit, OwnerWork<T> work) throws SQLException {
        try (Connection c = db().migrator()) {
            c.setAutoCommit(false);
            try {
                DbAccess.execute(c, "set local lock_timeout = '30s'");
                DbAccess.execute(c, "alter table " + table + " no force row level security");
                T result = work.apply(c);
                DbAccess.execute(c, "alter table " + table + " force row level security");
                if (commit) {
                    c.commit();
                } else {
                    c.rollback();
                }
                return result;
            } catch (SQLException | RuntimeException | AssertionError e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Inserts one active package (committed) and returns its id. Price in rupees (whole). */
    protected UUID insertPackage(Clinic clinic, String name, long rupees, int includedDays) throws SQLException {
        return insertPackages(clinic, new Object[] {name, rupees, includedDays, true}).get(0);
    }

    /**
     * Inserts packages in one transaction, in the order given ({@code clock_timestamp()} keeps that order). Each row is
     * {name, rupees, includedDays, active}.
     */
    protected List<UUID> insertPackages(Clinic clinic, Object[]... rowsToInsert) throws SQLException {
        return asOwner("ipd.package", true, c -> {
            List<UUID> ids = new ArrayList<>();
            for (Object[] r : rowsToInsert) {
                try (PreparedStatement ps = c.prepareStatement("insert into ipd.package "
                        + "(tenant_id, name, price_paise, included_days, active) values (?, ?, ?, ?, ?) returning id")) {
                    ps.setObject(1, clinic.tenantId());
                    ps.setString(2, (String) r[0]);
                    ps.setLong(3, (Long) r[1] * 100);
                    ps.setInt(4, (Integer) r[2]);
                    ps.setBoolean(5, (Boolean) r[3]);
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        ids.add(rs.getObject(1, UUID.class));
                    }
                }
            }
            return ids;
        });
    }

    /** Inserts a package with an exact id and creation time (list order checks). */
    protected void insertPackageAt(Clinic clinic, UUID id, String name, Instant createdAt) throws SQLException {
        asOwner("ipd.package", true, c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into ipd.package "
                    + "(id, tenant_id, name, price_paise, included_days, created_at) values (?, ?, ?, 1000000, 2, ?)")) {
                ps.setObject(1, id);
                ps.setObject(2, clinic.tenantId());
                ps.setString(3, name);
                ps.setTimestamp(4, Timestamp.from(createdAt));
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            return null;
        });
    }

    /** Changes a package row the way a database edit would (price, switch off); the attached copies must not move. */
    protected void editPackage(Clinic clinic, UUID id, long rupees, boolean active) throws SQLException {
        asOwner("ipd.package", true, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "update ipd.package set price_paise = ?, active = ? where id = ? and tenant_id = ?")) {
                ps.setLong(1, rupees * 100);
                ps.setBoolean(2, active);
                ps.setObject(3, id);
                ps.setObject(4, clinic.tenantId());
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            return null;
        });
    }

    /** The package list as a client sees it. */
    protected JsonNode packages(Clinic clinic) throws Exception {
        return ok(HttpMethod.GET, PACKAGES, clinic.owner(), null, 200);
    }

    protected static List<String> packageNames(JsonNode list) {
        List<String> names = new ArrayList<>();
        list.get("items").forEach(i -> names.add(i.get("name").asText()));
        return names;
    }

    // ---------------- the package copy on an admission ----------------

    /** Sets the package copy on an ADMITTED admission (committed, as the table owner). Price in rupees. */
    protected void attachPackage(Clinic clinic, String admissionId, String name, long rupees, int days)
            throws SQLException {
        asOwner("ipd.admission", true, c -> {
            assertThat(updateCopy(c, clinic, admissionId, name, rupees, days)).as("attached rows").isEqualTo(1);
            return null;
        });
    }

    /** The UPDATE that sets the copy; executed on an open owner connection. Returns the rows changed. */
    protected static int updateCopy(Connection c, Clinic clinic, String admissionId, String name, long rupees,
                                    int days) throws SQLException {
        try (PreparedStatement u = c.prepareStatement("update ipd.admission set package_name = ?, "
                + "package_price_paise = ?, package_days = ? where id = ?::uuid and tenant_id = ?")) {
            u.setString(1, name);
            u.setLong(2, rupees * 100);
            u.setInt(3, days);
            u.setString(4, admissionId);
            u.setObject(5, clinic.tenantId());
            return u.executeUpdate();
        }
    }

    /** The stored copy of an admission as "name|paise|days", or "none". Read as the app with the tenant bound. */
    protected String storedCopy(Clinic clinic, String admissionId) throws SQLException {
        List<String> v = values(clinic, "select coalesce(package_name || '|' || package_price_paise || '|' || package_days, "
                + "'none') from ipd.admission where id = ?::uuid", admissionId);
        return v.get(0);
    }

    /** Admits a Self patient on a new bed of the category at the bill day and attaches the package. */
    protected JsonNode admitWithPackage(Clinic clinic, String category, int billDays, String name, long rupees,
                                        int packageDays) throws Exception {
        JsonNode a = admitAtDay(clinic, category, billDays, "midnight");
        attachPackage(clinic, a.get("id").asText(), name, rupees, packageDays);
        return a;
    }

    // ---------------- expected package bill lines (written from AC 3) ----------------

    protected static L packageLine(String name, long rupees, int days) {
        return new L("Package · " + name + " (bed, nursing and surgeon for " + days + " days)", rupees * 100, 1, false);
    }

    /** The bed line beyond the package; none when no day is beyond. */
    protected static L[] beyond(String category, int days) {
        if (days <= 0) {
            return new L[0];
        }
        return new L[] {new L("Bed · " + NAME.get(category) + " beyond the package", BED.get(category), days, false)};
    }

    protected static L extra(String service, long unitPaise, int qty) {
        return new L(service + " · not in package", unitPaise, qty, false);
    }

    /** Inserts an extra charge row with an exact base price (the app may write unit_paise); committed as the app. */
    protected void addChargeRow(Clinic clinic, String admissionId, String key, String serviceName, long basePaise,
                                int qty) throws SQLException {
        // the app may not write qty (V2026_11_16: always 1), so a quantity of 2 is two rows
        for (int i = 0; i < qty; i++) {
            commitAsTenant(clinic.tenantId(), "insert into ipd.admission_charge "
                    + "(admission_id, service_key, service_name, unit_paise, added_by) values (?::uuid, ?, ?, ?, ?)",
                    admissionId, key, serviceName, basePaise, clinic.ownerUserId());
        }
    }

    /** Sets the module state of a clinic's IPD as the app does (state off, trial, on or readonly). */
    protected void setIpdState(Clinic clinic, String state) throws SQLException {
        commitAsTenant(clinic.tenantId(), "update " + MODULES_ENTITLEMENT + " set ipd_state = ?", state);
    }
}
