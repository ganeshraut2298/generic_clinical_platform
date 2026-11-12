package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * SAH-43 / SAH-44 stored shape of the four clinic tables (test cases S-30 to S-39 of docs/database/SAH-43-44.md), by
 * SQL on the real {@code sahaj_test} database as {@code sahaj_app}: forced row level security with one policy, the
 * grants (no delete on setup, settings and usage; no truncate anywhere), the checks, the unique keys, two clinics that
 * cannot see, change or delete each other's rows, and what the usage table can hold. Throwaway clinics (a tenant row
 * made through the test probe); rows that must outlive a statement are committed under a random tenant id.
 * Test plan: docs/test-plans/SAH-43-44.md.
 */
@OnTestDatabase
class EmrClinicTablesIT extends EmrSupport {

    private static final String[] CLINIC_TABLES = {"emr_template_setup", "emr_template_override", "emr_settings",
            "emr_item_usage"};

    private static final String INSERT_SETUP = "insert into opd.emr_template_setup (template_key, created_by) values ('gp', ?)";
    private static final String INSERT_OVERRIDE = "insert into opd.emr_template_override (template_key, section_key, shown, "
            + "required, created_by) values (?, ?, ?, ?, ?)";
    private static final String INSERT_SETTINGS = "insert into opd.emr_settings (created_by) values (?)";
    private static final String INSERT_USAGE = "insert into opd.emr_item_usage (doctor_membership_id, master, item_key, "
            + "last_used_at) values (?, ?, ?, now())";

    private UUID tenant() throws Exception {
        UUID id = UUID.randomUUID();
        createTenant(id, UUID.randomUUID(), uniqueName("Emr table clinic"));
        return id;
    }

    private static String uuidText() {
        return UUID.randomUUID().toString();
    }

    /** One valid row for each table, written under the given clinic (tenant column from the default). Committed. */
    private void commitOneRowOfEach(UUID tenantId) throws SQLException {
        UUID actor = UUID.randomUUID();
        commit(tenantId, INSERT_SETUP, actor);
        commit(tenantId, INSERT_OVERRIDE, "gp", "local_exam", false, false, actor);
        commit(tenantId, INSERT_SETTINGS, actor);
        commit(tenantId, INSERT_USAGE, UUID.randomUUID(), "complaints", uuidText());
    }

    private long countIn(UUID tenantId, String table) throws SQLException {
        return db().inTenant(tenantId, c -> DbAccess.count(c, "select count(*) from opd." + table));
    }

    private int updateCountIn(UUID tenantId, String sql, Object... params) throws SQLException {
        return db().inTenant(tenantId, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                return ps.executeUpdate();
            }
        });
    }

    // ---------------- S-30 row level security and grants ----------------

    @Test
    void everyClinicTableHasEnabledAndForcedRowLevelSecurityAndExactlyOneTenantPolicyForTheApp() throws Exception {
        for (String table : CLINIC_TABLES) {
            assertThat(globalRows("select c.relrowsecurity::text || ':' || c.relforcerowsecurity::text from pg_class c "
                    + "join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'opd' and c.relname = '" + table + "'")
                    .get(0).get(0)).as(table).isEqualTo("true:true");
            List<List<String>> policies = globalRows("select p.polname, p.polcmd::text, "
                    + "array_to_string(array(select r.rolname from pg_roles r where r.oid = any(p.polroles)), ','), "
                    + "pg_get_expr(p.polqual, p.polrelid), pg_get_expr(p.polwithcheck, p.polrelid) from pg_policy p "
                    + "join pg_class c on c.oid = p.polrelid join pg_namespace n on n.oid = c.relnamespace "
                    + "where n.nspname = 'opd' and c.relname = '" + table + "'");
            assertThat(policies).as(table + " policies").hasSize(1);
            List<String> policy = policies.get(0);
            assertThat(policy.get(0)).isEqualTo("p_" + table + "_tenant");
            assertThat(policy.get(1)).as("applies to every command").isEqualTo("*");
            assertThat(policy.get(2)).isEqualTo("sahaj_app");
            assertThat(policy.get(3)).contains("tenant_id").contains("current_tenant");
            assertThat(policy.get(4)).as("with check").contains("tenant_id").contains("current_tenant");
            assertThat(count("select count(*) from information_schema.columns where table_schema = 'opd' and table_name = '"
                    + table + "' and column_name = 'tenant_id' and is_nullable = 'NO'")).isEqualTo(1);
            assertThat(count("select count(*) from pg_constraint k join pg_class c on c.oid = k.conrelid "
                    + "join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'opd' and c.relname = '" + table
                    + "' and k.contype = 'f' and k.confrelid = 'platform.tenant'::regclass")).as(table + " tenant foreign key")
                    .isEqualTo(1);
        }
    }

    private long count(String sql) throws SQLException {
        return globalCount(sql);
    }

    @Test
    void theGrantsAreSelectInsertUpdateAndDeleteOnlyOnTheOverrideTable() throws Exception {
        for (String table : CLINIC_TABLES) {
            boolean deleteAllowed = table.equals("emr_template_override");
            String[][] expected = {{"SELECT", "t"}, {"INSERT", "t"}, {"UPDATE", "t"}, {"DELETE", deleteAllowed ? "t" : "f"},
                    {"TRUNCATE", "f"}, {"REFERENCES", "f"}, {"TRIGGER", "f"}};
            for (String[] e : expected) {
                assertThat(globalRows("select has_table_privilege('sahaj_app', 'opd." + table + "', '" + e[0] + "')").get(0).get(0))
                        .as(table + " " + e[0]).isEqualTo(e[1]);
            }
            assertThat(count("select count(*) from information_schema.role_table_grants where table_schema = 'opd' "
                    + "and table_name = '" + table + "' and grantee = 'PUBLIC'")).as("nothing granted to PUBLIC").isZero();
        }
    }

    // ---------------- S-31 two clinics, no clinic ----------------

    @Test
    void withNoClinicBoundNothingIsVisibleAndNothingCanBeInserted() throws Exception {
        UUID a = tenant();
        commitOneRowOfEach(a);
        for (String table : CLINIC_TABLES) {
            assertThat(globalCount("select count(*) from opd." + table)).as(table).isZero();
        }
        for (String[] insert : new String[][] {{INSERT_SETUP}, {INSERT_SETTINGS}}) {
            SqlError err = db().asApp(c -> expectError(c, insert[0], UUID.randomUUID()));
            assertThat(err.state()).as(insert[0]).isIn("42501", "23502");
        }
        // naming a real clinic does not help either
        SqlError named = db().asApp(c -> expectError(c, "insert into opd.emr_template_setup (tenant_id, template_key, created_by) "
                + "values (?, 'dental', ?)", a, UUID.randomUUID()));
        assertThat(named.state()).isEqualTo("42501");
        assertThat(named.message()).contains("row-level security");
    }

    @Test
    void oneClinicCannotReadChangeDeleteOrInsertIntoAnotherClinicsRowsInAnyOfTheFourTables() throws Exception {
        UUID a = tenant();
        UUID b = tenant();
        commitOneRowOfEach(a);
        for (String table : CLINIC_TABLES) {
            assertThat(countIn(a, table)).as(table + " seen by its own clinic").isEqualTo(1);
            assertThat(countIn(b, table)).as(table + " seen by the other clinic").isZero();
        }
        assertThat(updateCountIn(b, "update opd.emr_template_setup set revision = 99")).isZero();
        assertThat(updateCountIn(b, "update opd.emr_template_override set shown = true")).isZero();
        assertThat(updateCountIn(b, "update opd.emr_settings set section_panel = 'OPEN'")).isZero();
        assertThat(updateCountIn(b, "update opd.emr_item_usage set use_count = 99")).isZero();
        assertThat(updateCountIn(b, "delete from opd.emr_template_override")).isZero();
        // A's rows are exactly as they were
        assertThat(row(clinicOf(a), "select revision::text from opd.emr_template_setup")).containsExactly("0");
        assertThat(row(clinicOf(a), "select shown::text from opd.emr_template_override")).containsExactly("false");
        assertThat(row(clinicOf(a), "select section_panel from opd.emr_settings")).containsExactly("COLLAPSED");
        assertThat(row(clinicOf(a), "select use_count::text from opd.emr_item_usage")).containsExactly("1");
        // B cannot write a row that belongs to A
        UUID actor = UUID.randomUUID();
        for (Object[] attempt : new Object[][] {
                {"insert into opd.emr_template_setup (tenant_id, template_key, created_by) values (?, 'dental', ?)", a, actor},
                {"insert into opd.emr_template_override (tenant_id, template_key, section_key, shown, required, created_by) "
                        + "values (?, 'gp', 'services', false, false, ?)", a, actor},
                {"insert into opd.emr_settings (tenant_id, created_by) values (?, ?)", a, actor},
                {"insert into opd.emr_item_usage (tenant_id, doctor_membership_id, master, item_key, last_used_at) "
                        + "values (?, ?, 'complaints', '" + uuidText() + "', now())", a, actor}}) {
            SqlError err = db().inTenant(b, c -> expectError(c, (String) attempt[0], attempt[1], attempt[2]));
            assertThat(err.state()).as((String) attempt[0]).isEqualTo("42501");
            assertThat(err.message()).contains("row-level security");
        }
        // B has its own, independent rows, even for the same keys
        commitOneRowOfEach(b);
        for (String table : CLINIC_TABLES) {
            assertThat(countIn(b, table)).as(table).isEqualTo(1);
            assertThat(countIn(a, table)).as(table).isEqualTo(1);
        }
    }

    private Clinic clinicOf(UUID tenantId) {
        return new Clinic(tenantId, UUID.randomUUID(), UUID.randomUUID());
    }

    // ---------------- S-32, S-33 override ----------------

    @Test
    void anOverrideThatIsHiddenAndRequiredIsRefusedAndTheOtherThreeCombinationsAreAccepted() throws Exception {
        UUID t = tenant();
        UUID actor = UUID.randomUUID();
        db().inTenant(t, c -> {
            SqlError hiddenRequired = expectError(c, INSERT_OVERRIDE, "gp", "local_exam", false, true, actor);
            assertThat(hiddenRequired.state()).isEqualTo("23514");
            assertThat(hiddenRequired.message()).contains("ck_emr_template_override_hidden_not_required");
            expectOk(c, INSERT_OVERRIDE, "gp", "local_exam", false, false, actor);
            expectOk(c, INSERT_OVERRIDE, "gp", "vitals", true, true, actor);
            expectOk(c, INSERT_OVERRIDE, "gp", "services", true, false, actor);
            assertThat(DbAccess.count(c, "select count(*) from opd.emr_template_override")).isEqualTo(3);
            assertThat(expectError(c, INSERT_OVERRIDE, "gp", "services", true, true, actor).state()).as("duplicate").isEqualTo("23505");
            assertThat(expectError(c, INSERT_OVERRIDE, "ayur", "local_exam", true, false, actor).state()).isEqualTo("23514");
            assertThat(expectError(c, INSERT_OVERRIDE, "gp", "Bad Key", true, false, actor).state()).isEqualTo("23514");
            assertThat(expectError(c, INSERT_OVERRIDE, "gp", "a", true, false, actor).state()).isEqualTo("23514");
            assertThat(expectError(c, INSERT_OVERRIDE, "gp", "other_section", true, false, null).state()).as("created_by").isEqualTo("23502");
            // an update to hidden + required is refused too
            assertThat(expectError(c, "update opd.emr_template_override set required = true where section_key = 'local_exam'").state())
                    .isEqualTo("23514");
            // a reset deletes
            try (PreparedStatement ps = c.prepareStatement("delete from opd.emr_template_override where template_key = 'gp'")) {
                assertThat(ps.executeUpdate()).isEqualTo(3);
            }
            return null;
        });
    }

    @Test
    void anOverrideForASectionThatIsInNoTemplateVersionIsAcceptedBecauseItSurvivesVersionChanges() throws Exception {
        UUID t = tenant();
        db().inTenant(t, c -> {
            expectOk(c, INSERT_OVERRIDE, "gp", "retired_section", false, false, UUID.randomUUID());
            return null;
        });
    }

    // ---------------- S-34 setup ----------------

    @Test
    void theSetupRowIsUniquePerClinicAndTemplateAndTheRevisionNeverGoesBelowZero() throws Exception {
        UUID t = tenant();
        UUID actor = UUID.randomUUID();
        db().inTenant(t, c -> {
            expectOk(c, INSERT_SETUP, actor);
            assertThat(expectError(c, INSERT_SETUP, actor).state()).isEqualTo("23505");
            expectOk(c, "insert into opd.emr_template_setup (template_key, created_by) values ('dental', ?)", actor);
            assertThat(expectError(c, "insert into opd.emr_template_setup (template_key, created_by) values ('ayur', ?)", actor).state())
                    .isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_template_setup (template_key, revision, created_by) values ('paeds', -1, ?)", actor)
                    .state()).isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_template_setup (template_key, created_by) values ('homeo', null)").state())
                    .isEqualTo("23502");
            expectOk(c, "update opd.emr_template_setup set revision = revision + 1 where template_key = 'gp'");
            assertThat(DbAccess.count(c, "select revision from opd.emr_template_setup where template_key = 'gp'")).isEqualTo(1);
            assertThat(expectError(c, "update opd.emr_template_setup set revision = -1 where template_key = 'gp'").state())
                    .isEqualTo("23514");
            return null;
        });
    }

    @Test
    void twoFirstInsertsAtTheSameMomentOneSucceedsAndOneGetsAUniqueViolation() throws Exception {
        UUID t = tenant();
        try (Connection first = db().app()) {
            first.setAutoCommit(false);
            bind(first, t);
            DbAccess.execute(first, INSERT_SETUP, UUID.randomUUID());
            CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
                try (Connection c = db().app()) {
                    c.setAutoCommit(false);
                    bind(c, t);
                    DbAccess.execute(c, INSERT_SETUP, UUID.randomUUID());
                    c.commit();
                    return "inserted";
                } catch (SQLException e) {
                    return e.getSQLState();
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            boolean waiting = false;
            while (System.nanoTime() < deadline && !waiting) {
                waiting = db().asApp(c -> DbAccess.count(c, "select count(*) from pg_stat_activity where datname = current_database() "
                        + "and wait_event_type = 'Lock' and pid <> pg_backend_pid() and query like '%emr_template_setup%'")) >= 1;
                if (!waiting) {
                    Thread.sleep(25);
                }
            }
            assertThat(waiting).as("the second insert waits for the first").isTrue();
            first.commit();
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo("23505");
        }
        assertThat(countIn(t, "emr_template_setup")).isEqualTo(1);
    }

    private static void bind(Connection c, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
            ps.setString(1, tenantId.toString());
            ps.execute();
        }
    }

    // ---------------- S-35 settings ----------------

    @Test
    void theSettingsRowDefaultsAndRefusesOtherValuesAndASecondRowForTheSameClinic() throws Exception {
        UUID t = tenant();
        UUID actor = UUID.randomUUID();
        db().inTenant(t, c -> {
            List<String> defaults = allRows(c, "insert into opd.emr_settings (created_by) values (?) returning section_panel, "
                    + "right_panel_first, default_part, version::text", actor).get(0);
            assertThat(defaults).containsExactly("COLLAPSED", "CASE_PAPER", "ASSESSMENT", "0");
            assertThat(expectError(c, INSERT_SETTINGS, actor).state()).as("second row for the clinic").isEqualTo("23505");
            return null;
        });
        UUID other = tenant();
        db().inTenant(other, c -> {
            assertThat(expectError(c, "insert into opd.emr_settings (section_panel, created_by) values ('HALF', ?)", actor).state()).isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_settings (right_panel_first, created_by) values ('TOP', ?)", actor).state()).isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_settings (default_part, created_by) values ('TREATMENT', ?)", actor).state()).isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_settings (version, created_by) values (-1, ?)", actor).state()).isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_settings (created_by) values (null)").state()).isEqualTo("23502");
            expectOk(c, "insert into opd.emr_settings (section_panel, right_panel_first, created_by) values ('OPEN', 'PAST_VISITS', ?)", actor);
            assertThat(expectError(c, "update opd.emr_settings set default_part = 'TREATMENT'").state()).isEqualTo("23514");
            return null;
        });
    }

    // ---------------- S-36, S-37, S-39 usage ----------------

    @Test
    void theUsageTableRefusesFreeTextWrongShapesAndCountsBelowOne() throws Exception {
        UUID t = tenant();
        UUID doctor = UUID.randomUUID();
        db().inTenant(t, c -> {
            for (String bad : new String[] {"", "Fever", "viral fever", uuidText().toUpperCase(), uuidText() + "0", "J06.9",
                    "x".repeat(41), "not-a-uuid"}) {
                assertThat(expectError(c, INSERT_USAGE, doctor, "complaints", bad).state()).as("complaints '" + bad + "'")
                        .isEqualTo("23514");
            }
            for (String bad : new String[] {"", "Fever", uuidText(), "j06.9", "J6", "J06.99999", "J06.", "x".repeat(41), "1A00"}) {
                assertThat(expectError(c, INSERT_USAGE, doctor, "diagnoses", bad).state()).as("diagnoses '" + bad + "'")
                        .isEqualTo("23514");
            }
            assertThat(expectError(c, INSERT_USAGE, doctor, "allergies", uuidText()).state()).isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_item_usage (doctor_membership_id, master, item_key, use_count, last_used_at) "
                    + "values (?, 'complaints', ?, 0, now())", doctor, uuidText()).state()).isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_item_usage (doctor_membership_id, master, item_key, last_used_at) "
                    + "values (?, 'complaints', ?, null)", doctor, uuidText()).state()).isEqualTo("23502");
            for (String master : new String[] {"complaints", "investigations", "advice", "medicines"}) {
                expectOk(c, INSERT_USAGE, doctor, master, uuidText());
            }
            for (String code : new String[] {"J06.9", "R50.9", "A09", "E11.65", "Z00.0"}) {
                expectOk(c, INSERT_USAGE, doctor, "diagnoses", code);
            }
            return null;
        });
    }

    @Test
    void theUsageUpsertCountsEachUseKeepsTheLaterTimeAndGivesEachDoctorItsOwnRowButNothingCanBeDeleted() throws Exception {
        UUID t = tenant();
        UUID doctor = UUID.randomUUID();
        UUID otherDoctor = UUID.randomUUID();
        String item = uuidText();
        Instant later = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant earlier = later.minus(2, ChronoUnit.DAYS);
        String upsert = "insert into opd.emr_item_usage (doctor_membership_id, master, item_key, last_used_at) values (?, 'complaints', ?, ?) "
                + "on conflict (tenant_id, doctor_membership_id, master, item_key) do update set "
                + "use_count = opd.emr_item_usage.use_count + 1, "
                + "last_used_at = greatest(opd.emr_item_usage.last_used_at, excluded.last_used_at), updated_at = now()";
        db().inTenant(t, c -> {
            DbAccess.execute(c, upsert, doctor, item, java.sql.Timestamp.from(later));
            DbAccess.execute(c, upsert, doctor, item, java.sql.Timestamp.from(earlier));
            DbAccess.execute(c, upsert, otherDoctor, item, java.sql.Timestamp.from(earlier));
            List<String> mine = firstRow(c, "select use_count::text, last_used_at = ? from opd.emr_item_usage where doctor_membership_id = ?",
                    java.sql.Timestamp.from(later), doctor);
            assertThat(mine.get(0)).as("two uses counted").isEqualTo("2");
            assertThat(mine.get(1)).as("the later time is kept").isEqualTo("t");
            assertThat(DbAccess.count(c, "select use_count from opd.emr_item_usage where doctor_membership_id = ?", otherDoctor))
                    .as("another doctor has a row of their own").isEqualTo(1);
            SqlError delete = expectError(c, "delete from opd.emr_item_usage");
            assertThat(delete.state()).isEqualTo("42501");
            assertThat(delete.message()).contains("permission denied");
            SqlError truncate = expectError(c, "truncate opd.emr_item_usage");
            assertThat(truncate.state()).isEqualTo("42501");
            return null;
        });
    }

    @Test
    void noColumnOfTheClinicTablesCanHoldAPatientAVisitOrFreeText() throws Exception {
        String[][] expected = {
                {"emr_template_setup", "tenant_id,template_key,revision,created_at,created_by,updated_at,updated_by"},
                {"emr_template_override", "tenant_id,template_key,section_key,shown,required,created_at,created_by,updated_at,updated_by"},
                {"emr_settings", "tenant_id,section_panel,right_panel_first,default_part,created_at,created_by,updated_at,updated_by,version"},
                {"emr_item_usage", "tenant_id,doctor_membership_id,master,item_key,use_count,last_used_at,created_at,updated_at"}};
        for (String[] e : expected) {
            List<String> columns = new ArrayList<>();
            globalRows("select column_name from information_schema.columns where table_schema = 'opd' and table_name = '"
                    + e[0] + "' order by ordinal_position").forEach(r -> columns.add(r.get(0)));
            assertThat(String.join(",", columns)).as(e[0]).isEqualTo(e[1]);
            assertThat(columns).doesNotContain("patient_id", "visit_id", "note", "text", "name", "mobile", "diagnosis");
        }
    }
}
