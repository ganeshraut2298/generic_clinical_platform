package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-29 AC 2 and AC 6 on the stored shape, by SQL on the real {@code sahaj_test} database: forced row level security
 * and its single read policy, the app role's grants on {@code ipd.package} and on the three admission package columns
 * (column-level), the check constraints, the unique name, the truncate refusal and the set-once guard (change, clear,
 * a package on a DISCHARGED admission by update and by insert). Test plan: docs/test-plans/SAH-29.md.
 *
 * <p>Probes run as the table owner with the table unforced and are rolled back ({@link #asOwner}); the few committed
 * writes go through {@link PackageSupport#attachPackage}.
 */
@OnTestDatabase
class PackageTableIT extends PackageSupport {

    private static final String INSERT_PACKAGE = "insert into ipd.package (tenant_id, name, price_paise, included_days) "
            + "values (?, ?, ?, ?)";
    private static final List<String> COPY_COLUMNS = List.of("package_name", "package_price_paise", "package_days");

    /** The SQLSTATE and message of the error a statement raises on an open owner connection (fails when it succeeds). */
    private record Refused(String state, String message) {
    }

    private Refused ownerRefuses(String table, UnitSql sql) throws SQLException {
        return asOwner(table, false, c -> {
            DbAccess.execute(c, "savepoint probe");
            try {
                sql.run(c);
            } catch (SQLException e) {
                Refused refused = new Refused(e.getSQLState(), e.getMessage());
                DbAccess.execute(c, "rollback to savepoint probe");
                return refused;
            }
            throw new AssertionError("the statement was accepted");
        });
    }

    @FunctionalInterface
    private interface UnitSql {
        void run(Connection c) throws SQLException;
    }

    private static void insertPackageRow(Connection c, Clinic clinic, String name, long paise, int days) throws SQLException {
        DbAccess.execute(c, INSERT_PACKAGE, clinic.tenantId(), name, paise, days);
    }

    // ---------------- row level security and the grants of the app role ----------------

    /** A boolean read as text: JDBC gives t and f. */
    private String flag(String sql) throws SQLException {
        String v = ownerText(sql);
        return "t".equals(v) ? "true" : "f".equals(v) ? "false" : v;
    }

    @Test
    void thePackageTableHasForcedRowLevelSecurityAndOnlyOneReadPolicyForTheApp() throws Exception {
        assertThat(ownerText("select c.relrowsecurity || ':' || c.relforcerowsecurity from pg_class c "
                + "join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'ipd' and c.relname = 'package'"))
                .isEqualTo("true:true");
        assertThat(ownerText("select string_agg(p.polname || ':' || p.polcmd::text || ':' || "
                + "array_to_string(array(select r.rolname from pg_roles r where r.oid = any(p.polroles)), ','), ',' "
                + "order by p.polname) from pg_policy p join pg_class c on c.oid = p.polrelid "
                + "join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'ipd' and c.relname = 'package'"))
                .isEqualTo("p_package_select:r:sahaj_app");
        assertThat(ownerText("select pg_get_expr(p.polqual, p.polrelid) from pg_policy p where p.polname = 'p_package_select'"))
                .contains("tenant_id").contains("current_tenant");
        assertThat(ownerText("select count(*) from information_schema.columns where table_schema = 'ipd' "
                + "and table_name = 'package' and column_name = 'tenant_id' and is_nullable = 'NO'")).isEqualTo("1");
    }

    @Test
    void theAppRoleCanOnlySelectFromThePackageTableNotInsertUpdateDeleteTruncateOrReference() throws Exception {
        for (String privilege : new String[] {"INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
            assertThat(flag("select has_table_privilege('sahaj_app', 'ipd.package', '" + privilege + "')"))
                    .as(privilege).isEqualTo("false");
        }
        assertThat(flag("select has_table_privilege('sahaj_app', 'ipd.package', 'SELECT')")).isEqualTo("true");
        for (String column : new String[] {"id", "tenant_id", "name", "price_paise", "included_days", "active",
                "created_at", "created_by", "version"}) {
            for (String privilege : new String[] {"INSERT", "UPDATE"}) {
                assertThat(flag("select has_column_privilege('sahaj_app', 'ipd.package', '" + column + "', '"
                        + privilege + "')")).as(column + " " + privilege).isEqualTo("false");
            }
        }
        assertThat(ownerText("select count(*) from information_schema.role_table_grants where table_schema = 'ipd' "
                + "and table_name = 'package' and grantee = 'PUBLIC'")).as("nothing granted to public").isEqualTo("0");
        assertThat(ownerText("select string_agg(privilege_type, ',' order by privilege_type) from "
                + "information_schema.role_table_grants where table_schema = 'ipd' and table_name = 'package' "
                + "and grantee = 'sahaj_app'")).isEqualTo("SELECT");
    }

    @Test
    void theAppCannotInsertUpdateOrDeleteAPackageEvenWithItsOwnClinicBound() throws Exception {
        Clinic clinic = hospital();
        UUID id = insertPackage(clinic, "Appendicectomy", 45_000, 3);

        assertThat(deniedMessage(clinic, INSERT_PACKAGE, clinic.tenantId(), "Own package", 100_000L, 1))
                .contains("permission denied");
        assertThat(deniedMessage(clinic, "insert into ipd.package (name, price_paise, included_days) values (?, ?, ?)",
                "Own package", 100_000L, 1)).as("not even without naming the tenant").contains("permission denied");
        assertThat(deniedMessage(clinic, "update ipd.package set price_paise = 100 where id = ?", id))
                .contains("permission denied");
        assertThat(deniedMessage(clinic, "update ipd.package set active = false where id = ?", id))
                .contains("permission denied");
        assertThat(deniedMessage(clinic, "delete from ipd.package where id = ?", id)).contains("permission denied");
        assertThat(deniedMessage(clinic, "truncate ipd.package")).contains("permission denied");
        assertThat(packageNames(packages(clinic))).containsExactly("Appendicectomy");
        assertThat(packages(clinic).get("items").get(0).get("pricePaise").asLong()).isEqualTo(4_500_000L);
    }

    @Test
    void packagesAreInvisibleWithNoClinicBoundAndAnotherClinicsRowCannotBeNamed() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        UUID idA = insertPackage(a, "Clinic A package", 45_000, 3);
        long unbound = db().asApp(c -> DbAccess.count(c, "select count(*) from ipd.package"));
        assertThat(unbound).as("no tenant bound").isZero();
        assertThat(count(b, "select count(*) from ipd.package where id = ?", idA)).isZero();
        assertThat(count(a, "select count(*) from ipd.package where id = ?", idA)).isOne();

        // even the table owner is held by FORCE row level security: no policy names it, so a plain insert is refused
        Refused refused = ownerRefusesPlain(a);
        assertThat(refused.state()).isEqualTo("42501");
        assertThat(refused.message()).contains("row-level security");
    }

    private Refused ownerRefusesPlain(Clinic clinic) throws SQLException {
        try (Connection c = db().migrator()) {
            c.setAutoCommit(false);
            try {
                insertPackageRow(c, clinic, "Forced out", 100_000L, 1);
            } catch (SQLException e) {
                return new Refused(e.getSQLState(), e.getMessage());
            } finally {
                c.rollback();
            }
        }
        throw new AssertionError("the insert was accepted with row level security forced");
    }

    // ---------------- check constraints and the unique name ----------------

    @Test
    void theCheckConstraintsOfThePackageTableRefuseBadRowsAndAcceptTheBoundaries() throws Exception {
        Clinic clinic = hospital();
        String[][] bad = {
                {"ck_package_name", "", "100000", "1"},
                {"ck_package_name", "x".repeat(81), "100000", "1"},
                {"ck_package_name", " leading space", "100000", "1"},
                {"ck_package_name", "trailing space ", "100000", "1"},
                {"ck_package_price", "Negative", "-100", "1"},
                {"ck_package_price", "Half rupee", "150", "1"},
                {"ck_package_price", "Just over", "100000100", "1"},
                {"ck_package_included_days", "Minus", "100000", "-1"},
                {"ck_package_included_days", "Too long", "100000", "61"}};
        for (String[] row : bad) {
            Refused r = ownerRefuses("ipd.package", c -> insertPackageRow(c, clinic, row[1], Long.parseLong(row[2]),
                    Integer.parseInt(row[3])));
            assertThat(r.state()).as(row[0] + " " + row[1]).isEqualTo("23514");
            assertThat(r.message()).as(row[1]).contains(row[0]);
        }
        // the edges are accepted (rolled back)
        asOwner("ipd.package", false, c -> {
            insertPackageRow(c, clinic, "x", 0, 0);
            insertPackageRow(c, clinic, "y".repeat(80), 100_000_000L, 60);
            return null;
        });
    }

    @Test
    void aClinicCannotHaveTwoPackagesWhoseNamesDifferOnlyByCaseButTwoClinicsCan() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        insertPackage(a, "Appendicectomy", 45_000, 3);
        Refused same = ownerRefuses("ipd.package", c -> insertPackageRow(c, a, "Appendicectomy", 100_000L, 1));
        assertThat(same.state()).isEqualTo("23505");
        assertThat(same.message()).contains("ux_package_tenant_lower_name");
        Refused cased = ownerRefuses("ipd.package", c -> insertPackageRow(c, a, "APPENDICECTOMY", 100_000L, 1));
        assertThat(cased.state()).isEqualTo("23505");
        // the other clinic may use the name
        insertPackage(b, "Appendicectomy", 30_000, 2);
        assertThat(packageNames(packages(b))).containsExactly("Appendicectomy");
    }

    @Test
    void thePackageTableCannotBeTruncatedEvenByItsOwner() throws Exception {
        Refused r = ownerRefuses("ipd.package", c -> DbAccess.execute(c, "truncate ipd.package"));
        assertThat(r.state()).isEqualTo("42501");
        assertThat(r.message()).contains("TRUNCATE");
    }

    // ---------------- the three admission columns: grants ----------------

    @Test
    void theAppCanReadButNeverInsertOrUpdateTheThreePackageColumnsOfAnAdmission() throws Exception {
        for (String column : COPY_COLUMNS) {
            assertThat(flag("select has_column_privilege('sahaj_app', 'ipd.admission', '" + column + "', 'SELECT')"))
                    .as(column + " select").isEqualTo("true");
            for (String privilege : new String[] {"INSERT", "UPDATE"}) {
                assertThat(flag("select has_column_privilege('sahaj_app', 'ipd.admission', '" + column + "', '"
                        + privilege + "')")).as(column + " " + privilege).isEqualTo("false");
            }
        }
        // control: the grants it does have are unchanged (status, version, discharged_at are updated by a discharge)
        assertThat(flag("select has_column_privilege('sahaj_app', 'ipd.admission', 'status', 'UPDATE')")).isEqualTo("true");
    }

    @Test
    void anAppUpdateOrInsertNamingAPackageColumnIsRefusedAndTheAdmissionStaysWithoutAPackage() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        long version = admissionVersion(clinic, id);

        assertThat(deniedMessage(clinic, "update ipd.admission set package_name = 'Appendicectomy', "
                + "package_price_paise = 4500000, package_days = 3 where id = ?::uuid", id)).contains("permission denied");
        for (String column : COPY_COLUMNS) {
            String value = "package_name".equals(column) ? "'x'" : "100";
            assertThat(deniedMessage(clinic, "update ipd.admission set " + column + " = " + value + " where id = ?::uuid", id))
                    .as(column).contains("permission denied");
            assertThat(deniedMessage(clinic, "update ipd.admission set " + column + " = null where id = ?::uuid", id))
                    .as(column + " clear").contains("permission denied");
        }
        assertThat(deniedMessage(clinic, "insert into ipd.admission (patient_id, ip_number, bed_id, doctor_membership_id, "
                + "admission_type, consent_signed, admitted_by, package_name, package_price_paise, package_days) "
                + "select patient_id, 'IPX-APP', bed_id, doctor_membership_id, admission_type, true, admitted_by, "
                + "'Appendicectomy', 4500000, 3 from ipd.admission where id = ?::uuid", id)).contains("permission denied");
        assertThat(storedCopy(clinic, id)).isEqualTo("none");
        assertThat(admissionVersion(clinic, id)).isEqualTo(version);
        assertThat(detail(clinic, id).get("package").isNull()).isTrue();

        // control: an allowed column is accepted by the same app (rolled back), so the refusal above is the grant
        db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, "update ipd.admission set version = version + 1 where id = ?::uuid", id);
            return null;
        });
    }

    // ---------------- the three admission columns: checks ----------------

    @Test
    void theCopyIsAllOrNoneAndEachColumnIsRangeChecked() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        String[][] bad = {
                {"ck_admission_package_all_or_none", "package_name = 'Appendicectomy'"},
                {"ck_admission_package_all_or_none", "package_price_paise = 4500000"},
                {"ck_admission_package_all_or_none", "package_days = 3"},
                {"ck_admission_package_all_or_none", "package_name = 'Appendicectomy', package_price_paise = 4500000"},
                {"ck_admission_package_name", "package_name = '', package_price_paise = 4500000, package_days = 3"},
                {"ck_admission_package_name", "package_name = '" + "n".repeat(81) + "', package_price_paise = 4500000, package_days = 3"},
                {"ck_admission_package_price", "package_name = 'A', package_price_paise = 4500050, package_days = 3"},
                {"ck_admission_package_price", "package_name = 'A', package_price_paise = -100, package_days = 3"},
                {"ck_admission_package_price", "package_name = 'A', package_price_paise = 100000100, package_days = 3"},
                {"ck_admission_package_days", "package_name = 'A', package_price_paise = 4500000, package_days = 61"},
                {"ck_admission_package_days", "package_name = 'A', package_price_paise = 4500000, package_days = -1"}};
        for (String[] row : bad) {
            Refused r = ownerRefuses("ipd.admission", c -> DbAccess.execute(c, "update ipd.admission set " + row[1]
                    + " where id = ?::uuid and tenant_id = ?", id, clinic.tenantId()));
            assertThat(r.state()).as(row[1]).isEqualTo("23514");
            assertThat(r.message()).as(row[1]).contains(row[0]);
        }
        // the edges are accepted (rolled back): free package for 0 days, the largest price for 60 days
        asOwner("ipd.admission", false, c -> {
            assertThat(updateCopy(c, clinic, id, "A", 0, 0)).isOne();
            return null;
        });
        asOwner("ipd.admission", false, c -> {
            assertThat(updateCopy(c, clinic, id, "n".repeat(80), 1_000_000, 60)).isOne();
            return null;
        });
        assertThat(storedCopy(clinic, id)).isEqualTo("none");
    }

    // ---------------- the set-once guard ----------------

    @Test
    void onceSetTheCopyCannotBeChangedOrClearedByAnyRoleButSettingTheSameValueAgainIsHarmless() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        attachPackage(clinic, id, "Appendicectomy", 45_000, 3);
        assertThat(storedCopy(clinic, id)).isEqualTo("Appendicectomy|4500000|3");

        String[] changes = {
                "package_name = 'Other'",
                "package_price_paise = 5000000",
                "package_days = 4",
                "package_name = 'Other', package_price_paise = 100, package_days = 1",
                "package_name = null, package_price_paise = null, package_days = null"};
        for (String change : changes) {
            Refused r = ownerRefuses("ipd.admission", c -> DbAccess.execute(c, "update ipd.admission set " + change
                    + " where id = ?::uuid and tenant_id = ?", id, clinic.tenantId()));
            assertThat(r.state()).as(change).isEqualTo("42501");
            assertThat(r.message()).as(change).contains("set once and cannot be changed or cleared");
        }
        // the same values again: nothing changes, nothing fires
        asOwner("ipd.admission", false, c -> {
            assertThat(updateCopy(c, clinic, id, "Appendicectomy", 45_000, 3)).isOne();
            return null;
        });
        assertThat(storedCopy(clinic, id)).as("unchanged after every refused edit").isEqualTo("Appendicectomy|4500000|3");
        assertThat(detail(clinic, id).get("package").get("name").asText()).isEqualTo("Appendicectomy");
    }

    @Test
    void aPackageCannotBeSetOnADischargedAdmissionByUpdateAndADischargeOfAPackageAdmissionIsNotBlocked() throws Exception {
        Clinic clinic = hospital();
        String plain = quickAdmit(clinic).get("id").asText();
        signAndDischarge(clinic, plain);
        Refused r = ownerRefuses("ipd.admission", c -> updateCopy(c, clinic, plain, "Appendicectomy", 45_000, 3));
        assertThat(r.state()).isEqualTo("42501");
        assertThat(r.message()).contains("a package cannot be set on a discharged admission");
        assertThat(storedCopy(clinic, plain)).isEqualTo("none");

        // the normal discharge of an admission that has a package names no package column: the guard stays quiet
        String withPackage = quickAdmit(clinic).get("id").asText();
        attachPackage(clinic, withPackage, "Appendicectomy", 45_000, 3);
        assertThat(signAndDischarge(clinic, withPackage).get("finalBill")).isNotNull();
        assertThat(values(clinic, "select status from ipd.admission where id = ?::uuid", withPackage)).containsExactly("DISCHARGED");
        assertThat(storedCopy(clinic, withPackage)).isEqualTo("Appendicectomy|4500000|3");
    }

    @Test
    void aDischargedAdmissionCannotBeInsertedWithAPackageButAnAdmittedOneCan() throws Exception {
        Clinic clinic = hospital();
        String source = quickAdmit(clinic).get("id").asText();
        signAndDischarge(clinic, source);
        UUID otherPatient = newPatient(clinic);
        String insert = "insert into ipd.admission (tenant_id, patient_id, ip_number, bed_id, doctor_membership_id, "
                + "admission_type, consent_signed, status, admitted_by, discharged_at, package_name, package_price_paise, "
                + "package_days) select tenant_id, %s, ?::text, bed_id, doctor_membership_id, admission_type, consent_signed, "
                + "?::text, admitted_by, %s, ?::text, ?::bigint, ?::smallint from ipd.admission where id = ?::uuid";
        String discharged = insert.formatted("patient_id", "now()");
        String admitted = insert.formatted("'" + otherPatient + "'::uuid", "null::timestamptz");

        // control: the same insert without a package is accepted, so the refusal below is the package guard
        asOwner("ipd.admission", false, c -> {
            DbAccess.execute(c, discharged, "IPX-" + UUID.randomUUID().toString().substring(0, 8), "DISCHARGED", null, null, null, source);
            return null;
        });
        Refused r = ownerRefuses("ipd.admission", c -> DbAccess.execute(c, discharged, "IPX-" + UUID.randomUUID().toString().substring(0, 8),
                "DISCHARGED", "Appendicectomy", 4_500_000L, 3, source));
        assertThat(r.state()).isEqualTo("42501");
        assertThat(r.message()).contains("a package cannot be set on a discharged admission");

        // an ADMITTED row with a package is the other control (rolled back)
        asOwner("ipd.admission", false, c -> {
            DbAccess.execute(c, admitted, "IPX-" + UUID.randomUUID().toString().substring(0, 8), "ADMITTED", "Appendicectomy", 4_500_000L, 3, source);
            assertThat(DbAccess.count(c, "select count(*) from ipd.admission where package_name = 'Appendicectomy' "
                    + "and tenant_id = ?", clinic.tenantId())).isOne();
            return null;
        });
        assertThat(count(clinic, "select count(*) from ipd.admission where package_name is not null")).isZero();
    }

    @Test
    void theGuardFunctionCannotBeCalledByTheAppAndHoldsNoTableAccess() throws Exception {
        assertThat(flag("select has_function_privilege('sahaj_app', 'ipd.guard_admission_package()', 'EXECUTE')"))
                .isEqualTo("false");
        assertThat(ownerText("select p.prosecdef::text || ':' || coalesce(array_to_string(p.proconfig, ','), '') "
                + "from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                + "where n.nspname = 'ipd' and p.proname = 'guard_admission_package'"))
                .as("security invoker and a fixed search path").isEqualTo("false:search_path=pg_catalog");
        assertThat(ownerText("select string_agg(t.tgname, ',' order by t.tgname) from pg_trigger t "
                + "join pg_class c on c.oid = t.tgrelid join pg_namespace n on n.oid = c.relnamespace "
                + "where n.nspname = 'ipd' and c.relname = 'admission' and t.tgname like 'trg_admission_package%'"))
                .isEqualTo("trg_admission_package_set_once,trg_admission_package_set_once_insert");
    }

    @Test
    void theAppCannotRunTheGuardDirectlyToLearnAnything() throws Exception {
        assertThatThrownBy(() -> db().asApp(c -> {
            DbAccess.execute(c, "select ipd.guard_admission_package()");
            return null;
        })).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
    }

}
