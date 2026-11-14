package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
 * SAH-43 / SAH-53 stored shape of the clinic EMR tables (test cases S-30 to S-39 of docs/database/SAH-43-44.md for the
 * usage table and S-60 to S-67 of docs/database/SAH-53.md for the department, version, doctor and record tables), by SQL
 * on the real {@code sahaj_test} database as {@code sahaj_app}: forced row level security with its policies on the
 * tables and on every record partition, the column-level grants, the checks, the unique keys, the composite keys that
 * keep a row inside its clinic, the partitions, two clinics that cannot see, change or delete each other's rows, and
 * what the usage table can hold. Throwaway clinics (a tenant row made through the test probe); rows that must outlive a
 * statement are committed under that tenant id. Test plan: docs/test-plans/SAH-53.md.
 */
@OnTestDatabase
class EmrClinicTablesIT extends EmrSupport {

    private static final String[] CLINIC_TABLES = {"emr_item_usage", "emr_department", "emr_department_version",
            "emr_department_doctor", "emr_record"};

    private static final String INSERT_USAGE = "insert into opd.emr_item_usage (doctor_membership_id, master, item_key, "
            + "last_used_at) values (?, ?, ?, now())";
    private static final String INSERT_DEPT = "insert into opd.emr_department (department_key, name, initials, colour, "
            + "starter_key, source_kind, created_by) values (?, ?, 'DX', '#1D5FA8', ?, ?, ?)";
    private static final String INSERT_VERSION = "insert into opd.emr_department_version (department_id, version_no, layout, "
            + "published_by) values (?, ?, '{\"schemaVersion\":1,\"sections\":[]}'::jsonb, ?)";
    private static final String INSERT_DOCTOR = "insert into opd.emr_department_doctor (department_id, doctor_membership_id, "
            + "created_by) values (?, ?, ?)";
    /** An Emergency record on the starter layout: needs no patient and no visit, so it is valid on its own. */
    private static final String INSERT_ER_RECORD = "insert into opd.emr_record (encounter_type, er_case_id, "
            + "opened_by_membership_id, department_key, department_name, starter_key, layout_kind, starter_layout_version, "
            + "choice_source, created_by) values ('ER_ASSESSMENT', ?, ?, 'gm', 'General Medicine', 'gm', 'STARTER_LAYOUT', 1, "
            + "'CLINIC_DEFAULT', ?)";

    private UUID tenant() throws Exception {
        UUID id = UUID.randomUUID();
        createTenant(id, UUID.randomUUID(), uniqueName("Emr table clinic"));
        return id;
    }

    private static String uuidText() {
        return UUID.randomUUID().toString();
    }

    private Clinic clinicOf(UUID tenantId) {
        return new Clinic(tenantId, UUID.randomUUID(), UUID.randomUUID());
    }

    /** One valid row for each table, written under the given clinic (tenant column from the default). Committed. */
    private UUID commitOneRowOfEach(UUID tenantId) throws SQLException {
        UUID actor = UUID.randomUUID();
        commit(tenantId, INSERT_USAGE, UUID.randomUUID(), "complaints", uuidText());
        commit(tenantId, INSERT_DEPT, "gm", "General Medicine", "gm", "STARTER", actor);
        UUID departmentId = UUID.fromString(row(clinicOf(tenantId), "select id::text from opd.emr_department").get(0));
        commit(tenantId, INSERT_VERSION, departmentId, 1, actor);
        commit(tenantId, INSERT_DOCTOR, departmentId, UUID.randomUUID(), actor);
        commit(tenantId, INSERT_ER_RECORD, UUID.randomUUID(), actor, actor);
        return departmentId;
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

    private long count(String sql) throws SQLException {
        return globalCount(sql);
    }

    private static List<String> partitionNames() {
        List<String> names = new ArrayList<>();
        int year = 2026;
        int month = 11;
        for (int i = 0; i < 24; i++) {
            names.add(String.format("emr_record_%d_%02d", year, month));
            if (++month == 13) {
                month = 1;
                year++;
            }
        }
        names.add("emr_record_default");
        return names;
    }

    // ---------------- S-30, S-60 row level security and grants ----------------

    @Test
    void everyClinicTableHasEnabledAndForcedRowLevelSecurityAndItsTenantPolicyForTheApp() throws Exception {
        for (String table : CLINIC_TABLES) {
            assertThat(globalRows("select c.relrowsecurity::text || ':' || c.relforcerowsecurity::text from pg_class c "
                    + "join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'opd' and c.relname = '" + table + "'")
                    .get(0).get(0)).as(table).isEqualTo("true:true");
            List<List<String>> policies = globalRows("select p.polname, p.polcmd::text, "
                    + "array_to_string(array(select r.rolname from pg_roles r where r.oid = any(p.polroles)), ','), "
                    + "pg_get_expr(p.polqual, p.polrelid), pg_get_expr(p.polwithcheck, p.polrelid) from pg_policy p "
                    + "join pg_class c on c.oid = p.polrelid join pg_namespace n on n.oid = c.relnamespace "
                    + "where n.nspname = 'opd' and c.relname = '" + table + "' order by p.polname");
            if (table.equals("emr_department_version")) {
                assertThat(policies).as(table + " has a select and an insert policy only").hasSize(2);
                assertThat(policies.stream().map(p -> p.get(1)).toList()).containsExactlyInAnyOrder("r", "a");
            } else {
                assertThat(policies).as(table + " policies").hasSize(1);
                assertThat(policies.get(0).get(0)).isEqualTo("p_" + table + "_tenant");
                assertThat(policies.get(0).get(1)).as("applies to every command").isEqualTo("*");
                assertThat(policies.get(0).get(4)).as("with check").contains("tenant_id").contains("current_tenant");
            }
            for (List<String> policy : policies) {
                assertThat(policy.get(2)).isEqualTo("sahaj_app");
                String expression = policy.get(3) != null ? policy.get(3) : policy.get(4);
                assertThat(expression).contains("tenant_id").contains("current_tenant");
            }
            assertThat(count("select count(*) from information_schema.columns where table_schema = 'opd' and table_name = '"
                    + table + "' and column_name = 'tenant_id' and is_nullable = 'NO'")).isEqualTo(1);
            assertThat(count("select count(*) from pg_constraint k join pg_class c on c.oid = k.conrelid "
                    + "join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'opd' and c.relname = '" + table
                    + "' and k.contype = 'f' and k.confrelid = 'platform.tenant'::regclass")).as(table + " tenant foreign key")
                    .isEqualTo(1);
        }
    }

    @Test
    void everyMonthlyPartitionOfTheRecordTableAndTheDefaultOneHaveForcedRowLevelSecurityAndNoPrivilegeForTheApp() throws Exception {
        List<String> migrationMonths = partitionNames();
        List<String> found = new ArrayList<>();
        globalRows("select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid join pg_class p on p.oid = i.inhparent "
                + "join pg_namespace n on n.oid = p.relnamespace where n.nspname = 'opd' and p.relname = 'emr_record' order by 1")
                .forEach(r -> found.add(r.get(0)));
        // DEC-049 item 3: the afterMigrate upkeep adds months as the clock moves, so the number is not fixed. The 24 months of
        // the migration (Nov 2026 to Oct 2028) and the default one are always there; every other child is a month, nothing else.
        assertThat(found).as("the 24 monthly partitions of the migration and the default one are there").containsAll(migrationMonths);
        assertThat(found).as("every partition is the default or a month named emr_record_YYYY_MM")
                .allMatch(n -> n.equals("emr_record_default") || n.matches("emr_record_[0-9]{4}_(0[1-9]|1[0-2])"));
        List<String> names = found;
        for (String partition : names) {
            assertThat(globalRows("select c.relrowsecurity::text || ':' || c.relforcerowsecurity::text from pg_class c "
                    + "join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'opd' and c.relname = '" + partition + "'")
                    .get(0).get(0)).as(partition).isEqualTo("true:true");
            assertThat(count("select count(*) from pg_policy p join pg_class c on c.oid = p.polrelid where c.relname = '"
                    + partition + "'")).as(partition + " policy").isEqualTo(1);
            for (String privilege : new String[] {"SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE"}) {
                assertThat(globalRows("select has_table_privilege('sahaj_app', 'opd." + partition + "', '" + privilege + "')")
                        .get(0).get(0)).as(partition + " " + privilege).isEqualTo("f");
            }
            assertThat(count("select count(*) from information_schema.role_table_grants where table_schema = 'opd' "
                    + "and table_name = '" + partition + "' and grantee = 'PUBLIC'")).isZero();
        }
    }

    @Test
    void theGrantsAreExactlyTheOnesOfTheDesignNoDeleteAnywhereAndColumnLevelUpdates() throws Exception {
        // {table, SELECT, INSERT, UPDATE on the whole table, DELETE}
        String[][] tables = {{"emr_item_usage", "t", "t", "t", "f"}, {"emr_department", "t", "t", "f", "f"},
                {"emr_department_version", "t", "t", "f", "f"}, {"emr_department_doctor", "t", "t", "f", "f"},
                {"emr_record", "t", "t", "f", "f"}};
        for (String[] e : tables) {
            String[] privileges = {"SELECT", "INSERT", "UPDATE", "DELETE"};
            for (int i = 0; i < privileges.length; i++) {
                assertThat(globalRows("select has_table_privilege('sahaj_app', 'opd." + e[0] + "', '" + privileges[i] + "')")
                        .get(0).get(0)).as(e[0] + " " + privileges[i]).isEqualTo(e[i + 1]);
            }
            for (String privilege : new String[] {"TRUNCATE", "REFERENCES", "TRIGGER"}) {
                assertThat(globalRows("select has_table_privilege('sahaj_app', 'opd." + e[0] + "', '" + privilege + "')")
                        .get(0).get(0)).as(e[0] + " " + privilege).isEqualTo("f");
            }
            assertThat(count("select count(*) from information_schema.role_table_grants where table_schema = 'opd' "
                    + "and table_name = '" + e[0] + "' and grantee = 'PUBLIC'")).as("nothing granted to PUBLIC").isZero();
        }
        assertThat(updatableColumns("emr_department")).containsExactlyInAnyOrder("draft_layout", "draft_saved_at",
                "draft_saved_by", "published_version", "revision", "updated_at", "updated_by");
        assertThat(updatableColumns("emr_record")).containsExactlyInAnyOrder("patient_id", "department_key",
                "department_name", "starter_key", "layout_kind", "department_version_id", "starter_layout_version",
                "choice_source", "status", "signed_at", "signed_by", "sections", "schema_version", "ui_part", "ui_section_key",
                "admission_advised", "referral_line", "revision", "updated_at", "updated_by",
                // SAH-48-59 (docs/database/SAH-48-59.md): the print choices and the form references
                "print_language", "paper_size", "form_refs",
                // SAH-49-71 (docs/database/SAH-49-71.md, file 1100): the visit mode and the Oncology decision
                "screen_state",
                // SAH-51-68 (docs/database/SAH-51-68.md, file V2026_12_12_1000): the sign, the edit hold and the patient facts fingerprint
                "signed_by_membership_id", "sign_idempotency_key", "sign_request_hash", "sign_result", "edit_hold_membership_id",
                "edit_hold_user_id", "edit_hold_expires_at", "patient_facts_fingerprint");
        assertThat(updatableColumns("emr_department_version")).isEmpty();
        assertThat(updatableColumns("emr_department_doctor")).isEmpty();
    }

    private List<String> updatableColumns(String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        globalRows("select column_name from information_schema.columns where table_schema = 'opd' and table_name = '" + table + "'")
                .forEach(r -> {
                    try {
                        if ("t".equals(globalRows("select has_column_privilege('sahaj_app', 'opd." + table + "', '" + r.get(0)
                                + "', 'UPDATE')").get(0).get(0))) {
                            columns.add(r.get(0));
                        }
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                });
        return columns;
    }

    // ---------------- S-31, S-60 two clinics, no clinic ----------------

    @Test
    void withNoClinicBoundNothingIsVisibleAndNothingCanBeInserted() throws Exception {
        UUID a = tenant();
        commitOneRowOfEach(a);
        for (String table : CLINIC_TABLES) {
            assertThat(globalCount("select count(*) from opd." + table)).as(table).isZero();
        }
        SqlError usage = db().asApp(c -> expectError(c, INSERT_USAGE, UUID.randomUUID(), "complaints", uuidText()));
        assertThat(usage.state()).isIn("42501", "23502");
        SqlError dept = db().asApp(c -> expectError(c, INSERT_DEPT, "gm", "General Medicine", "gm", "STARTER", UUID.randomUUID()));
        assertThat(dept.state()).isIn("42501", "23502");
        SqlError record = db().asApp(c -> expectError(c, INSERT_ER_RECORD, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        assertThat(record.state()).isIn("42501", "23502");
        // naming a real clinic does not help either
        SqlError named = db().asApp(c -> expectError(c, "insert into opd.emr_department (tenant_id, department_key, name, initials, "
                + "colour, starter_key, source_kind, created_by) values (?, 'dental', 'Dental', 'DN', '#0B6E69', 'dental', "
                + "'STARTER', ?)", a, UUID.randomUUID()));
        assertThat(named.state()).isEqualTo("42501");
        assertThat(named.message()).contains("row-level security");
    }

    @Test
    void oneClinicCannotReadChangeDeleteOrInsertIntoAnotherClinicsRowsInAnyClinicTableOrPartition() throws Exception {
        UUID a = tenant();
        UUID b = tenant();
        UUID departmentOfA = commitOneRowOfEach(a);
        for (String table : CLINIC_TABLES) {
            assertThat(countIn(a, table)).as(table + " seen by its own clinic").isEqualTo(1);
            assertThat(countIn(b, table)).as(table + " seen by the other clinic").isZero();
        }
        assertThat(updateCountIn(b, "update opd.emr_item_usage set use_count = 99")).isZero();
        assertThat(updateCountIn(b, "update opd.emr_department set revision = 99")).isZero();
        assertThat(updateCountIn(b, "update opd.emr_record set revision = 99, sections = '{\"x\":1}'::jsonb")).isZero();
        // A's rows are exactly as they were
        assertThat(row(clinicOf(a), "select use_count::text from opd.emr_item_usage")).containsExactly("1");
        assertThat(row(clinicOf(a), "select revision::text from opd.emr_department")).containsExactly("0");
        assertThat(row(clinicOf(a), "select revision::text || sections::text from opd.emr_record")).containsExactly("0{}");
        // B cannot write a row that belongs to A
        UUID actor = UUID.randomUUID();
        for (Object[] attempt : new Object[][] {
                {"insert into opd.emr_item_usage (tenant_id, doctor_membership_id, master, item_key, last_used_at) "
                        + "values (?, ?, 'complaints', '" + uuidText() + "', now())", a, actor},
                {"insert into opd.emr_department (tenant_id, department_key, name, initials, colour, starter_key, source_kind, "
                        + "created_by) values (?, 'dental', 'Dental', 'DN', '#0B6E69', 'dental', 'STARTER', ?)", a, actor},
                {"insert into opd.emr_department_version (tenant_id, department_id, version_no, layout, published_by) values "
                        + "(?, '" + departmentOfA + "', 2, '{\"schemaVersion\":1,\"sections\":[]}'::jsonb, ?)", a, actor},
                {"insert into opd.emr_department_doctor (tenant_id, department_id, doctor_membership_id, created_by) values "
                        + "(?, '" + departmentOfA + "', gen_random_uuid(), ?)", a, actor},
                {"insert into opd.emr_record (tenant_id, encounter_type, er_case_id, opened_by_membership_id, department_key, "
                        + "department_name, starter_key, layout_kind, starter_layout_version, choice_source, created_by) values "
                        + "(?, 'ER_ASSESSMENT', gen_random_uuid(), ?, 'gm', 'General Medicine', 'gm', 'STARTER_LAYOUT', 1, "
                        + "'CLINIC_DEFAULT', ?)", a, actor, actor}}) {
            Object[] params = java.util.Arrays.copyOfRange(attempt, 1, attempt.length);
            SqlError err = db().inTenant(b, c -> expectError(c, (String) attempt[0], params));
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

    // ---------------- S-61 department ----------------

    @Test
    void aDepartmentIsUniquePerClinicByKeyAndByNameIgnoringCaseAndItsChecksHold() throws Exception {
        UUID t = tenant();
        UUID actor = UUID.randomUUID();
        db().inTenant(t, c -> {
            expectOk(c, INSERT_DEPT, "gm", "General Medicine", "gm", "STARTER", actor);
            assertThat(expectError(c, INSERT_DEPT, "gm", "Another name", "gm", "STARTER", actor).state()).as("same key").isEqualTo("23505");
            assertThat(expectError(c, INSERT_DEPT, "d_abcdefghij", "GENERAL medicine", "gm", "STARTER", actor).state())
                    .as("same name, other letter case").isEqualTo("23505");
            // a department added from a starter has a server-made key and keeps the starter
            expectOk(c, INSERT_DEPT, "d_abcdefghij", "General Medicine 2", "gm", "STARTER", actor);
            // the starter-backed row must carry its own starter key as key
            assertThat(expectError(c, INSERT_DEPT, "dental", "Dental x", "gm", "STARTER", actor).state()).isEqualTo("23514");
            assertThat(expectError(c, INSERT_DEPT, "gm", "No starter", null, "STARTER", actor).state()).isEqualTo("23514");
            // a blank department has no starter and a server-made key
            assertThat(expectError(c, INSERT_DEPT, "gm", "Blank with starter key", null, "BLANK", actor).state()).isEqualTo("23514");
            assertThat(expectError(c, INSERT_DEPT, "d_abcde", "Blank short key", null, "BLANK", actor).state()).isEqualTo("23514");
            assertThat(expectError(c, INSERT_DEPT, "d_abcdefghij", "Blank with starter", "gm", "BLANK", actor).state()).isEqualTo("23514");
            expectOk(c, INSERT_DEPT, "d_klmnopqrst", "Blank department", null, "BLANK", actor);
            // a copy names its source
            assertThat(expectError(c, INSERT_DEPT, "d_uvwxyz2345", "Copy without source", null, "COPY", actor).state()).isEqualTo("23514");
            expectOk(c, "insert into opd.emr_department (department_key, name, initials, colour, source_kind, copied_from_key, created_by) "
                    + "values ('d_uvwxyz2345', 'Copy department', 'CD', '#1D5FA8', 'COPY', 'gm', ?)", actor);
            assertThat(expectError(c, "insert into opd.emr_department (department_key, name, initials, colour, starter_key, source_kind, created_by) "
                    + "values ('d_aaaaaaaaaa', 'Bad source kind', 'BS', '#1D5FA8', null, 'IMPORT', ?)", actor).state()).isEqualTo("23514");
            return null;
        });
    }

    @Test
    void aDepartmentRefusesBadInitialsColourRoomsDraftsAndCounters() throws Exception {
        UUID t = tenant();
        UUID actor = UUID.randomUUID();
        String base = "insert into opd.emr_department (department_key, name, initials, colour, starter_key, source_kind, %s created_by) "
                + "values ('d_qqqqqqqqqq', 'Checks %s', %s, %s, 'gm', 'STARTER', %s ?)";
        db().inTenant(t, c -> {
            assertThat(expectError(c, base.formatted("", "a", "'abcd'", "'#1D5FA8'", ""), actor).state()).as("initials abcd").isEqualTo("23514");
            assertThat(expectError(c, base.formatted("", "b", "'ab'", "'#1D5FA8'", ""), actor).state()).as("lower case initials").isEqualTo("23514");
            assertThat(expectError(c, base.formatted("", "c", "'AB'", "'#abc'", ""), actor).state()).as("colour #abc").isEqualTo("23514");
            assertThat(expectError(c, base.formatted("", "d", "'AB'", "'1D5FA8'", ""), actor).state()).isEqualTo("23514");
            // room labels: at most 10, 1 to 40 characters, different ignoring case
            assertThat(expectError(c, base.formatted("room_labels,", "e", "'AB'", "'#1D5FA8'", "array['1','2','3','4','5','6','7','8','9','10','11'],"), actor)
                    .state()).as("11 rooms").isEqualTo("23514");
            assertThat(expectError(c, base.formatted("room_labels,", "f", "'AB'", "'#1D5FA8'", "array['Room 1','ROOM 1'],"), actor).state())
                    .as("duplicate ignoring case").isEqualTo("23514");
            assertThat(expectError(c, base.formatted("room_labels,", "g", "'AB'", "'#1D5FA8'", "array[''],"), actor).state()).as("empty").isEqualTo("23514");
            assertThat(expectError(c, base.formatted("room_labels,", "h", "'AB'", "'#1D5FA8'", "array['" + "r".repeat(41) + "'],"), actor).state())
                    .as("41 characters").isEqualTo("23514");
            expectOk(c, base.formatted("room_labels,", "i", "'AB'", "'#1D5FA8'", "array['" + "r".repeat(40) + "','Room 2'],"), actor);
            // draft: needs a sections array, and the three draft columns are all set or all null
            assertThat(expectError(c, base.replace("d_qqqqqqqqqq", "d_rrrrrrrrrr").formatted("draft_layout, draft_saved_at, draft_saved_by,", "j",
                    "'AB'", "'#1D5FA8'", "'{\"schemaVersion\":1}'::jsonb, now(), gen_random_uuid(),"), actor).state()).as("draft with no sections").isEqualTo("23514");
            assertThat(expectError(c, base.replace("d_qqqqqqqqqq", "d_ssssssssss").formatted("draft_layout,", "k", "'AB'", "'#1D5FA8'",
                    "'{\"schemaVersion\":1,\"sections\":[]}'::jsonb,"), actor).state()).as("draft without a time and an author").isEqualTo("23514");
            assertThat(expectError(c, base.replace("d_qqqqqqqqqq", "d_tttttttttt").formatted("published_version,", "l", "'AB'", "'#1D5FA8'", "-1,"), actor)
                    .state()).isEqualTo("23514");
            assertThat(expectError(c, base.replace("d_qqqqqqqqqq", "d_uuuuuuuuuu").formatted("revision,", "m", "'AB'", "'#1D5FA8'", "-1,"), actor)
                    .state()).isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_department (department_key, name, initials, colour, starter_key, source_kind, created_by) "
                    + "values ('Gm', 'Bad key', 'AB', '#1D5FA8', 'gm', 'STARTER', ?)", actor).state()).as("key pattern").isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_department (department_key, name, initials, colour, starter_key, source_kind, created_by) "
                    + "values ('gm', '   ', 'AB', '#1D5FA8', 'gm', 'STARTER', ?)", actor).state()).as("blank name").isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_department (department_key, name, initials, colour, starter_key, source_kind, created_by) "
                    + "values ('gm', ?, 'AB', '#1D5FA8', 'gm', 'STARTER', ?)", "n".repeat(61), actor).state()).as("61 characters").isEqualTo("23514");
            return null;
        });
    }

    @Test
    void theAppCanChangeOnlyTheDraftTheVersionNumberTheRevisionAndTheStampsOfADepartmentAndNeverDeleteOne() throws Exception {
        UUID t = tenant();
        commit(t, INSERT_DEPT, "gm", "General Medicine", "gm", "STARTER", UUID.randomUUID());
        db().inTenant(t, c -> {
            for (String refused : new String[] {"name = 'Renamed'", "department_key = 'dental'", "initials = 'ZZ'", "colour = '#000000'",
                    "source_kind = 'BLANK'", "room_labels = array['x']", "created_at = now()", "created_by = gen_random_uuid()",
                    "tenant_id = gen_random_uuid()", "id = gen_random_uuid()"}) {
                assertThat(expectError(c, "update opd.emr_department set " + refused).state()).as(refused).isEqualTo("42501");
            }
            expectOk(c, "update opd.emr_department set draft_layout = '{\"schemaVersion\":1,\"sections\":[]}'::jsonb, draft_saved_at = now(), "
                    + "draft_saved_by = gen_random_uuid(), revision = revision + 1, updated_at = now(), updated_by = gen_random_uuid()");
            expectOk(c, "update opd.emr_department set published_version = published_version + 1, draft_layout = null, "
                    + "draft_saved_at = null, draft_saved_by = null");
            SqlError delete = expectError(c, "delete from opd.emr_department");
            assertThat(delete.state()).isEqualTo("42501");
            assertThat(delete.message()).contains("permission denied");
            return null;
        });
    }

    @Test
    void twoFirstInsertsOfTheSameDepartmentAtTheSameMomentOneSucceedsAndOneGetsAUniqueViolation() throws Exception {
        UUID t = tenant();
        try (Connection first = db().app()) {
            first.setAutoCommit(false);
            bind(first, t);
            DbAccess.execute(first, INSERT_DEPT, "gm", "General Medicine", "gm", "STARTER", UUID.randomUUID());
            CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
                try (Connection c = db().app()) {
                    c.setAutoCommit(false);
                    bind(c, t);
                    DbAccess.execute(c, INSERT_DEPT, "gm", "General Medicine", "gm", "STARTER", UUID.randomUUID());
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
                        + "and wait_event_type = 'Lock' and pid <> pg_backend_pid() and query like '%emr_department%'")) >= 1;
                if (!waiting) {
                    Thread.sleep(25);
                }
            }
            assertThat(waiting).as("the second insert waits for the first").isTrue();
            first.commit();
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo("23505");
        }
        assertThat(countIn(t, "emr_department")).isEqualTo(1);
    }

    private static void bind(Connection c, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
            ps.setString(1, tenantId.toString());
            ps.execute();
        }
    }

    // ---------------- S-62 version ----------------

    @Test
    void aPublishedVersionIsInsertOnlyNumberedOncePerDepartmentAndPointsOnlyAtOwnClinicsDepartment() throws Exception {
        UUID a = tenant();
        UUID b = tenant();
        UUID departmentOfA = commitOneRowOfEach(a);
        UUID actor = UUID.randomUUID();
        db().inTenant(a, c -> {
            expectOk(c, INSERT_VERSION, departmentOfA, 2, actor);
            assertThat(expectError(c, INSERT_VERSION, departmentOfA, 1, actor).state()).as("duplicate number").isEqualTo("23505");
            assertThat(expectError(c, INSERT_VERSION, departmentOfA, 0, actor).state()).isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_department_version (department_id, version_no, layout, published_by) "
                    + "values (?, 9, '{\"sections\":[]}'::jsonb, ?)", departmentOfA, actor).state()).as("no schemaVersion").isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_department_version (department_id, version_no, layout, published_by) "
                    + "values (?, 9, '[]'::jsonb, ?)", departmentOfA, actor).state()).as("not an object").isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_department_version (department_id, version_no, layout, published_by) "
                    + "values (?, 9, '{\"schemaVersion\":1,\"sections\":[]}'::jsonb, null)", departmentOfA).state()).isEqualTo("23502");
            assertThat(expectError(c, INSERT_VERSION, UUID.randomUUID(), 1, actor).state()).as("unknown department").isEqualTo("23503");
            for (String sql : new String[] {"update opd.emr_department_version set version_no = 7", "update opd.emr_department_version set layout = layout",
                    "delete from opd.emr_department_version", "truncate opd.emr_department_version"}) {
                assertThat(expectError(c, sql).state()).as(sql).isEqualTo("42501");
            }
            return null;
        });
        // clinic B cannot attach a version to A's department (the composite key includes the clinic)
        SqlError other = db().inTenant(b, c -> expectError(c, INSERT_VERSION, departmentOfA, 5, actor));
        assertThat(other.state()).isEqualTo("23503");
    }

    // ---------------- S-63 doctor ----------------

    @Test
    void aDepartmentDoctorIsInsertOnlyOncePerDepartmentAndNeverAcrossClinics() throws Exception {
        UUID a = tenant();
        UUID b = tenant();
        UUID departmentOfA = commitOneRowOfEach(a);
        UUID doctor = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        db().inTenant(a, c -> {
            expectOk(c, INSERT_DOCTOR, departmentOfA, doctor, actor);
            assertThat(expectError(c, INSERT_DOCTOR, departmentOfA, doctor, actor).state()).as("duplicate").isEqualTo("23505");
            assertThat(expectError(c, "update opd.emr_department_doctor set created_by = gen_random_uuid()").state()).isEqualTo("42501");
            assertThat(expectError(c, "delete from opd.emr_department_doctor").state()).isEqualTo("42501");
            return null;
        });
        assertThat(db().inTenant(b, c -> expectError(c, INSERT_DOCTOR, departmentOfA, doctor, actor)).state()).isEqualTo("23503");
    }

    // ---------------- S-64 record ----------------

    @Test
    void eachEncounterTypeAcceptsOnlyItsOwnIdsAndTheLayoutReferenceMustMatchItsKind() throws Exception {
        UUID t = tenant();
        UUID actor = UUID.randomUUID();
        String head = "insert into opd.emr_record (encounter_type, opd_visit_id, ipd_admission_id, round_date, er_case_id, patient_id, "
                + "opened_by_membership_id, department_key, department_name, starter_key, layout_kind, department_version_id, "
                + "starter_layout_version, choice_source, status, signed_at, signed_by, sections, referral_line, admission_advised, created_by) "
                + "values (";
        String starter = "'gm', 'General Medicine', 'gm', 'STARTER_LAYOUT', null, 1, 'CLINIC_DEFAULT'";
        db().inTenant(t, c -> {
            UUID id = UUID.randomUUID();
            // Emergency: its own case id, no patient needed
            expectOk(c, head + "'ER_ASSESSMENT', null, null, null, ?, null, ?, " + starter + ", 'DRAFT', null, null, '{}'::jsonb, null, false, ?)", id, actor, actor);
            // OPD with an Emergency case id, IPD without a round date, OPD without a patient: refused
            assertThat(expectError(c, head + "'OPD_VISIT', ?, null, null, ?, ?, ?, " + starter + ", 'DRAFT', null, null, '{}'::jsonb, null, false, ?)",
                    id, id, id, actor, actor).state()).as("OPD with er_case_id").isEqualTo("23514");
            assertThat(expectError(c, head + "'OPD_VISIT', ?, null, null, null, null, ?, " + starter + ", 'DRAFT', null, null, '{}'::jsonb, null, false, ?)",
                    id, actor, actor).state()).as("OPD without a patient").isEqualTo("23514");
            assertThat(expectError(c, head + "'IPD_ROUND', null, ?, null, null, ?, ?, " + starter + ", 'DRAFT', null, null, '{}'::jsonb, null, false, ?)",
                    id, id, actor, actor).state()).as("IPD without a round date").isEqualTo("23514");
            assertThat(expectError(c, head + "'ER_ASSESSMENT', ?, null, null, ?, null, ?, " + starter + ", 'DRAFT', null, null, '{}'::jsonb, null, false, ?)",
                    id, id, actor, actor).state()).as("ER with a visit id").isEqualTo("23514");
            assertThat(expectError(c, head + "'WALK_IN', null, null, null, ?, null, ?, " + starter + ", 'DRAFT', null, null, '{}'::jsonb, null, false, ?)",
                    id, actor, actor).state()).as("unknown encounter type").isEqualTo("23514");
            // layout kind and its reference
            assertThat(expectError(c, head + "'ER_ASSESSMENT', null, null, null, ?, null, ?, 'gm', 'General Medicine', 'gm', 'STARTER_LAYOUT', null, null, "
                    + "'CLINIC_DEFAULT', 'DRAFT', null, null, '{}'::jsonb, null, false, ?)", UUID.randomUUID(), actor, actor).state())
                    .as("starter layout without a version").isEqualTo("23514");
            assertThat(expectError(c, head + "'ER_ASSESSMENT', null, null, null, ?, null, ?, 'gm', 'General Medicine', 'gm', 'DEPARTMENT_VERSION', null, 1, "
                    + "'CLINIC_DEFAULT', 'DRAFT', null, null, '{}'::jsonb, null, false, ?)", UUID.randomUUID(), actor, actor).state())
                    .as("department version kind without a version id").isEqualTo("23514");
            assertThat(expectError(c, head + "'ER_ASSESSMENT', null, null, null, ?, null, ?, 'gm', 'General Medicine', 'gm', 'STARTER_LAYOUT', null, 1, "
                    + "'SOMEONE', 'DRAFT', null, null, '{}'::jsonb, null, false, ?)", UUID.randomUUID(), actor, actor).state())
                    .as("unknown choice source").isEqualTo("23514");
            // signed state, referral line and the sections document
            // Security I2 (13 Nov 2026): a SIGNED insert is now refused by trg_emr_record_insert_draft (BEFORE INSERT, 23514), before the
            // check constraints run; ck_emr_record_signed is proved through the sign update in EmrPhase5TablesIT
            SqlError signedInsert = expectError(c, head + "'ER_ASSESSMENT', null, null, null, ?, null, ?, " + starter + ", 'SIGNED', null, null, '{}'::jsonb, null, false, ?)",
                    UUID.randomUUID(), actor, actor);
            assertThat(signedInsert.state()).as("a new record cannot be inserted as SIGNED").isEqualTo("23514");
            assertThat(signedInsert.message()).as("refused by the insert trigger, not by ck_emr_record_signed").contains("must be inserted as DRAFT")
                    .doesNotContain("ck_emr_record_signed");
            // DEC-048 H22 (13 Nov 2026): a referral line without "admission advised" is now allowed on an Emergency assessment (a refer-out);
            // OPD and IPD records keep the old rule (EmrPhase5TablesIT)
            expectOk(c, head + "'ER_ASSESSMENT', null, null, null, ?, null, ?, " + starter + ", 'DRAFT', null, null, '{}'::jsonb, 'Refer to a hospital', false, ?)",
                    UUID.randomUUID(), actor, actor);
            assertThat(expectError(c, head + "'ER_ASSESSMENT', null, null, null, ?, null, ?, " + starter + ", 'DRAFT', null, null, '[]'::jsonb, null, false, ?)",
                    UUID.randomUUID(), actor, actor).state()).as("sections must be an object").isEqualTo("23514");
            return null;
        });
    }

    // ---------------- S-65 record keys ----------------

    @Test
    void aRecordCannotPointAtAnotherClinicsPatientVisitDepartmentVersionOrAnUnknownStarterLayout() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        UUID patientOfA = newPatient(a);
        JsonNode published = publishAsIs(a, "gm");
        assertThat(published.get("outcome").asText()).isEqualTo("PUBLISHED");
        UUID departmentOfA = UUID.fromString(row(a, "select id::text from opd.emr_department where department_key = 'gm'").get(0));
        UUID versionOfA = UUID.fromString(row(a, "select id::text from opd.emr_department_version where department_id = ?::uuid", departmentOfA).get(0));
        UUID actor = UUID.randomUUID();
        String opd = "insert into opd.emr_record (encounter_type, opd_visit_id, patient_id, opened_by_membership_id, department_key, "
                + "department_name, starter_key, layout_kind, starter_layout_version, choice_source, created_by) values "
                + "('OPD_VISIT', gen_random_uuid(), ?, ?, 'gm', 'General Medicine', 'gm', 'STARTER_LAYOUT', 1, 'CLINIC_DEFAULT', ?)";
        assertThat(db().inTenant(b.tenantId(), c -> expectError(c, opd, patientOfA, actor, actor)).state())
                .as("patient of another clinic").isEqualTo("23503");
        // the visit key (added with the 9 Dec migration): a visit that does not exist for this clinic
        UUID ownPatient = newPatient(b);
        assertThat(db().inTenant(b.tenantId(), c -> expectError(c, opd, ownPatient, actor, actor)).state())
                .as("visit that is not this clinic's").isEqualTo("23503");
        // department version of another clinic
        String onVersion = "insert into opd.emr_record (encounter_type, er_case_id, opened_by_membership_id, department_key, department_name, "
                + "layout_kind, department_version_id, choice_source, created_by) values ('ER_ASSESSMENT', gen_random_uuid(), ?, 'gm', "
                + "'General Medicine', 'DEPARTMENT_VERSION', ?, 'CLINIC_DEFAULT', ?)";
        assertThat(db().inTenant(b.tenantId(), c -> expectError(c, onVersion, actor, versionOfA, actor)).state())
                .as("version of another clinic").isEqualTo("23503");
        db().inTenant(a.tenantId(), c -> {
            expectOk(c, onVersion, actor, versionOfA, actor);
            return null;
        });
        // a starter layout that does not exist
        assertThat(db().inTenant(b.tenantId(), c -> expectError(c, "insert into opd.emr_record (encounter_type, er_case_id, opened_by_membership_id, "
                + "department_key, department_name, starter_key, layout_kind, starter_layout_version, choice_source, created_by) values "
                + "('ER_ASSESSMENT', gen_random_uuid(), ?, 'gm', 'General Medicine', 'gm', 'STARTER_LAYOUT', 99, 'CLINIC_DEFAULT', ?)", actor, actor))
                .state()).isEqualTo("23503");
    }

    // ---------------- S-66 record grants ----------------

    @Test
    void theAppChangesOnlyTheEditableColumnsOfARecordAndNeverDeletesOneOrReadsAPartitionDirectly() throws Exception {
        UUID t = tenant();
        commit(t, INSERT_ER_RECORD, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        db().inTenant(t, c -> {
            for (String refused : new String[] {"created_at = now() + interval '1 day'", "tenant_id = gen_random_uuid()",
                    "encounter_type = 'OPD_VISIT'", "opd_visit_id = gen_random_uuid()", "er_case_id = gen_random_uuid()", "id = gen_random_uuid()",
                    "opened_by_membership_id = gen_random_uuid()", "created_by = gen_random_uuid()"}) {
                assertThat(expectError(c, "update opd.emr_record set " + refused).state()).as(refused).isEqualTo("42501");
            }
            expectOk(c, "update opd.emr_record set sections = '{\"complaints\":{}}'::jsonb, revision = revision + 1, updated_at = now(), "
                    + "updated_by = gen_random_uuid(), ui_part = 'TREATMENT', ui_section_key = 'rx'");
            assertThat(DbAccess.text(c, "select revision::text from opd.emr_record")).isEqualTo("1");
            SqlError delete = expectError(c, "delete from opd.emr_record");
            assertThat(delete.state()).isEqualTo("42501");
            assertThat(delete.message()).contains("permission denied");
            SqlError partition = expectError(c, "select count(*) from opd.emr_record_2026_11");
            assertThat(partition.state()).isEqualTo("42501");
            return null;
        });
    }

    // ---------------- S-67 partitioning ----------------

    @Test
    void aRecordLandsInThePartitionOfItsMonthAndOneOutsideTheTwentyFourMonthsLandsInTheDefaultPartition() throws Exception {
        UUID t = tenant();
        UUID actor = UUID.randomUUID();
        String insert = "insert into opd.emr_record (encounter_type, er_case_id, opened_by_membership_id, department_key, department_name, "
                + "starter_key, layout_kind, starter_layout_version, choice_source, created_at, created_by) values ('ER_ASSESSMENT', ?, ?, 'gm', "
                + "'General Medicine', 'gm', 'STARTER_LAYOUT', 1, 'CLINIC_DEFAULT', ?::timestamptz, ?)";
        UUID december = UUID.randomUUID();
        UUID farFuture = UUID.randomUUID();
        UUID november = UUID.randomUUID();
        // DEC-049: the upkeep keeps 12 months ahead of today, so "outside the partitions" is worked out from today
        // (70 months ahead, beyond the 60 the upkeep function can reach), not a fixed year that the clock overtakes.
        String farMonth = beyondTheUpkeepMonth() + "-15 10:00:00+00";
        db().inTenant(t, c -> {
            DbAccess.execute(c, insert, december, actor, "2026-12-15 10:00:00+00", actor);
            DbAccess.execute(c, insert, farFuture, actor, farMonth, actor);
            DbAccess.execute(c, insert, november, actor, "2026-11-30 23:59:59+00", actor);
            assertThat(DbAccess.text(c, "select tableoid::regclass::text from opd.emr_record where er_case_id = ?", december))
                    .endsWith("emr_record_2026_12");
            assertThat(DbAccess.text(c, "select tableoid::regclass::text from opd.emr_record where er_case_id = ?", farFuture))
                    .endsWith("emr_record_default");
            assertThat(DbAccess.text(c, "select tableoid::regclass::text from opd.emr_record where er_case_id = ?", november))
                    .endsWith("emr_record_2026_11");
            return null;
        });
    }

    @Test
    void thePartitionHelperIsIdempotentCreatesSafePartitionsAndCannotBeRunByTheApp() throws Exception {
        assertThat(globalRows("select has_function_privilege('sahaj_app', 'opd.emr_record_ensure_partitions(date,integer)', 'EXECUTE')")
                .get(0).get(0)).isEqualTo("f");
        assertThat(db().asApp(c -> expectError(c, "select opd.emr_record_ensure_partitions(date '2030-01-01', 1)")).state()).isEqualTo("42501");
        // A month beyond the upkeep (today + 70 months), so the afterMigrate callback can never have made it already (DEC-049).
        String month = beyondTheUpkeepMonth();
        String partition = "emr_record_" + month.replace('-', '_');
        db().asMigrator(c -> {
            assertThat(DbAccess.count(c, "select opd.emr_record_ensure_partitions(date '2026-11-01', 24)")).as("already there").isZero();
            assertThat(DbAccess.count(c, "select opd.emr_record_ensure_partitions(date '" + month + "-01', 1)")).as("a new month").isEqualTo(1);
            assertThat(DbAccess.count(c, "select opd.emr_record_ensure_partitions(date '" + month + "-01', 1)")).as("and not twice").isZero();
            assertThat(DbAccess.text(c, "select relrowsecurity::text || ':' || relforcerowsecurity::text from pg_class where relname = '" + partition + "'"))
                    .isEqualTo("true:true");
            assertThat(DbAccess.text(c, "select has_table_privilege('sahaj_app', 'opd." + partition + "', 'SELECT')::text")).isEqualTo("false");
            return null;
        });
    }

    /** The month (yyyy-MM) 70 months after the current UTC month: past the 60 months the upkeep function can ever make. */
    static String beyondTheUpkeepMonth() {
        return java.time.YearMonth.now(java.time.ZoneOffset.UTC).plusMonths(70).toString();
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
    void theUsageAndDepartmentTablesHoldNoPatientVisitOrFreeTextColumn() throws Exception {
        String[][] expected = {
                {"emr_item_usage", "tenant_id,doctor_membership_id,master,item_key,use_count,last_used_at,created_at,updated_at"},
                {"emr_department_doctor", "tenant_id,department_id,doctor_membership_id,created_at,created_by"},
                {"emr_department_version", "id,tenant_id,department_id,version_no,layout,published_at,published_by"}};
        for (String[] e : expected) {
            List<String> columns = new ArrayList<>();
            globalRows("select column_name from information_schema.columns where table_schema = 'opd' and table_name = '"
                    + e[0] + "' order by ordinal_position").forEach(r -> columns.add(r.get(0)));
            assertThat(String.join(",", columns)).as(e[0]).isEqualTo(e[1]);
            assertThat(columns).doesNotContain("patient_id", "visit_id", "note", "text", "name", "mobile", "diagnosis");
        }
        List<String> department = new ArrayList<>();
        globalRows("select column_name from information_schema.columns where table_schema = 'opd' and table_name = 'emr_department'")
                .forEach(r -> department.add(r.get(0)));
        assertThat(department).doesNotContain("patient_id", "visit_id", "mobile", "diagnosis", "doctor_name", "registration_no");
    }
}
