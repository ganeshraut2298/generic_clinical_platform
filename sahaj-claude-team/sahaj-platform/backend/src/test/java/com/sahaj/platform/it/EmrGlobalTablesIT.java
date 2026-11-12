package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SAH-43 / SAH-44 stored shape of the seven global tables (test cases S-01 to S-21 of docs/database/SAH-43-44.md), by
 * SQL on the real {@code sahaj_test} database: seed counts, the read-only grant of the app role, no tenant column,
 * the check constraints, unique names, stable ids, the immutability trigger (also for the table owner), the section
 * and option rules, the aliases, and the "Starter list, not clinically reviewed" comments. Writes that must work
 * (to prove a constraint is the reason for a refusal) run as the table owner in a transaction that is always rolled
 * back. Test plan: docs/test-plans/SAH-43-44.md.
 */
@OnTestDatabase
class EmrGlobalTablesIT extends EmrSupport {

    private static final String[] GLOBAL_TABLES = {"emr_complaint", "emr_investigation", "emr_advice", "emr_template",
            "emr_template_section", "emr_template_option", "emr_specialty_alias"};
    private static final String[] MASTER_TABLES = {"emr_complaint", "emr_investigation", "emr_advice"};
    private static final String[] TEMPLATE_TABLES = {"emr_template", "emr_template_section", "emr_template_option"};

    private long count(String sql, Object... params) throws SQLException {
        return globalCount(sql, params);
    }

    // ---------------- S-01 seed counts, S-20 the older tables ----------------

    @Test
    void theSeedHasTheCountsOfTheDesignNote() throws Exception {
        assertThat(count("select count(*) from opd.emr_complaint")).isEqualTo(59);
        assertThat(count("select count(*) from opd.emr_investigation")).isEqualTo(46);
        assertThat(count("select count(*) from opd.emr_advice")).isEqualTo(50);
        assertThat(count("select count(*) from opd.emr_template")).isEqualTo(4);
        assertThat(count("select count(*) from opd.emr_template_section")).isEqualTo(38);
        assertThat(count("select count(*) from opd.emr_template_option")).isEqualTo(45);
        assertThat(count("select count(*) from opd.emr_specialty_alias")).isEqualTo(8);
    }

    @Test
    void theOlderStarterListsOfSah7AreUnchanged() throws Exception {
        assertThat(count("select count(*) from opd.diagnosis")).isEqualTo(53);
        assertThat(count("select count(*) from opd.medicine")).isEqualTo(52);
        assertThat(count("select count(*) from platform.specialty_template")).isPositive();
        assertThat(count("select count(*) from information_schema.tables where table_schema = 'platform' "
                + "and table_name like 'emr%'")).isZero();
    }

    /** SAH-43-4: each diagnosis has exactly one ICD-10 code of the enforced pattern, and codes are unique. */
    @Test
    void everyDiagnosisHasOneIcd10CodeOfTheEnforcedPatternAndCodesAreUnique() throws Exception {
        assertThat(count("select count(*) from opd.diagnosis where code !~ '^[A-Z][0-9]{2}(\\.[0-9A-Z]{1,4})?$'")).isZero();
        assertThat(count("select count(*) - count(distinct code) from opd.diagnosis")).isZero();
        assertThat(count("select count(*) from opd.diagnosis where name is null or btrim(name) = ''")).isZero();
        assertThat(count("select count(*) from information_schema.columns where table_schema = 'opd' "
                + "and table_name = 'diagnosis' and column_name like '%code%'")).as("one code column").isEqualTo(1);
    }

    // ---------------- S-02, S-03 grants and no tenant column ----------------

    @Test
    void theAppCanSelectEveryGlobalTableWithNoClinicBound() throws Exception {
        for (String table : GLOBAL_TABLES) {
            assertThat(count("select count(*) from opd." + table)).as(table).isPositive();
        }
    }

    @Test
    void theAppCannotInsertUpdateDeleteOrTruncateAnyGlobalTable() throws Exception {
        for (String table : GLOBAL_TABLES) {
            for (String sql : new String[] {"insert into opd." + table + " default values",
                    "update opd." + table + " set created_at = now()", "delete from opd." + table,
                    "truncate opd." + table}) {
                SqlError err = db().asApp(c -> expectError(c, sql));
                assertThat(err.state()).as(sql).isEqualTo("42501");
                assertThat(err.message()).as(sql).contains("permission denied");
            }
            for (String privilege : new String[] {"INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
                assertThat(globalRows("select has_table_privilege('sahaj_app', 'opd." + table + "', '" + privilege + "')")
                        .get(0).get(0)).as(table + " " + privilege).isEqualTo("f");
            }
            assertThat(globalRows("select has_table_privilege('sahaj_app', 'opd." + table + "', 'SELECT')").get(0).get(0))
                    .isEqualTo("t");
            for (List<String> column : globalRows("select column_name from information_schema.columns "
                    + "where table_schema = 'opd' and table_name = '" + table + "'")) {
                for (String privilege : new String[] {"INSERT", "UPDATE"}) {
                    assertThat(globalRows("select has_column_privilege('sahaj_app', 'opd." + table + "', '"
                            + column.get(0) + "', '" + privilege + "')").get(0).get(0))
                            .as(table + "." + column.get(0) + " " + privilege).isEqualTo("f");
                }
            }
            assertThat(count("select count(*) from information_schema.role_table_grants where table_schema = 'opd' "
                    + "and table_name = '" + table + "' and grantee = 'PUBLIC'")).as("nothing granted to PUBLIC").isZero();
            assertThat(globalRows("select string_agg(privilege_type, ',' order by privilege_type) from "
                    + "information_schema.role_table_grants where table_schema = 'opd' and table_name = '" + table
                    + "' and grantee = 'sahaj_app'").get(0).get(0)).as(table + " grants of sahaj_app").isEqualTo("SELECT");
        }
    }

    @Test
    void noGlobalTableHasATenantColumn() throws Exception {
        for (String table : GLOBAL_TABLES) {
            assertThat(count("select count(*) from information_schema.columns where table_schema = 'opd' "
                    + "and table_name = '" + table + "' and column_name = 'tenant_id'")).as(table).isZero();
            assertThat(count("select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                    + "where n.nspname = 'opd' and c.relname = '" + table + "' and c.relrowsecurity")).as(table
                    + " has no row level security by design (ADR-019 D2)").isZero();
        }
    }

    // ---------------- S-04, S-05 the three masters ----------------

    @Test
    void aNameRepeatedIgnoringLetterCaseIsRefusedButTheSameNameInTwoMastersIsAllowed() throws Exception {
        for (String table : MASTER_TABLES) {
            String existing = globalRows("select name from opd." + table + " order by sort_order limit 1").get(0).get(0);
            for (String variant : new String[] {existing.toLowerCase(), existing.toUpperCase(), existing}) {
                SqlError err = db().asMigrator(c -> expectError(c, "insert into opd." + table + " (name) values (?)", variant));
                assertThat(err.state()).as(table + " '" + variant + "'").isEqualTo("23505");
            }
        }
        db().asMigrator(c -> {
            for (String table : MASTER_TABLES) {
                expectOk(c, "insert into opd." + table + " (name) values ('Qa shared name across masters')");
            }
            return null;
        });
    }

    @Test
    void theNameHintAndOrderChecksOfTheMasters() throws Exception {
        for (String table : MASTER_TABLES) {
            db().asMigrator(c -> {
                for (String bad : new String[] {"", "   ", "x".repeat(201)}) {
                    assertThat(expectError(c, "insert into opd." + table + " (name) values (?)", bad).state())
                            .as(table + " name length " + bad.length()).isEqualTo("23514");
                }
                expectOk(c, "insert into opd." + table + " (name) values (?)", "y".repeat(200));
                assertThat(expectError(c, "insert into opd." + table + " (name, specialty_hint) values ('Qa hint', 'ayur')").state())
                        .isEqualTo("23514");
                assertThat(expectError(c, "insert into opd." + table + " (name, sort_order) values ('Qa order', -1)").state())
                        .isEqualTo("23514");
                assertThat(expectError(c, "insert into opd." + table + " (name) values (null)").state()).isEqualTo("23502");
                int i = 0;
                for (String hint : new String[] {null, "gp", "dental", "paeds", "homeo"}) {
                    expectOk(c, "insert into opd." + table + " (name, specialty_hint) values (?, ?)", "Qa hint accepted " + i++, hint);
                }
                return null;
            });
        }
    }

    // ---------------- S-06 prefix search and its index ----------------

    @Test
    void thePrefixSearchFindsTheStartOfANameAndEscapedWildcardsMatchLiterally() throws Exception {
        List<List<String>> found = globalRows("select name from opd.emr_complaint where lower(name) like 'cou%' escape '\\'");
        assertThat(found).isNotEmpty();
        found.forEach(r -> assertThat(r.get(0).toLowerCase()).startsWith("cou"));
        for (String pattern : new String[] {"\\%%", "\\_%", "\\\\%", "c\\%%", "c\\_u%"}) {
            for (String table : MASTER_TABLES) {
                assertThat(count("select count(*) from opd." + table + " where lower(name) like ? escape '\\'", pattern))
                        .as(table + " " + pattern).isZero();
            }
        }
        // an unescaped wildcard would have matched: the escape is what keeps it literal
        assertThat(count("select count(*) from opd.emr_complaint where lower(name) like 'c_u%'")).isPositive();
        assertThat(count("select count(*) from opd.emr_complaint where lower(name) like 'c_u%' escape '\\'")).isPositive();
        assertThat(count("select count(*) from opd.emr_complaint where lower(name) like 'c\\_u%' escape '\\'")).isZero();
    }

    @Test
    void thePrefixSearchOnEachMasterCanUseItsNameIndex() throws Exception {
        String[][] cases = {{"opd.emr_complaint", "uq_emr_complaint_name_lower"},
                {"opd.emr_investigation", "uq_emr_investigation_name_lower"}, {"opd.emr_advice", "uq_emr_advice_name_lower"},
                {"opd.diagnosis", "ix_diagnosis_name_lower"}};
        for (String[] c : cases) {
            List<String> plan = db().asApp(conn -> {
                DbAccess.execute(conn, "set local enable_seqscan = off");
                List<String> lines = new ArrayList<>();
                allRows(conn, "explain select 1 from " + c[0] + " where lower(name) like 'ab%' escape '\\'")
                        .forEach(r -> lines.add(r.get(0)));
                return lines;
            });
            assertThat(String.join("\n", plan)).as(c[0]).contains(c[1]);
        }
    }

    // ---------------- S-07, S-08 content that is promised ----------------

    @Test
    void theThirteenMockupInvestigationsArePresentWithTheirExactNames() throws Exception {
        for (String name : new String[] {"Complete blood count (CBC)", "ESR", "Random blood sugar", "HbA1c", "Lipid profile",
                "Liver function test", "Kidney function test", "TSH", "Dengue NS1", "Widal", "Urine routine",
                "X-ray chest PA", "ECG"}) {
            assertThat(count("select count(*) from opd.emr_investigation where name = ?", name)).as(name).isEqualTo(1);
        }
    }

    @Test
    void theIdsAreStableFromTheNameSoEveryEnvironmentHasTheSameId() throws Exception {
        for (String table : MASTER_TABLES) {
            assertThat(count("select count(*) from opd." + table + " where id <> md5('opd." + table + ":' || lower(name))::uuid"))
                    .as(table).isZero();
        }
    }

    // ---------------- S-09 starter list comments ----------------

    @Test
    void everySeededEmrTableSaysItIsAStarterListNotClinicallyReviewed() throws Exception {
        for (String table : GLOBAL_TABLES) {
            String comment = globalRows("select obj_description('opd." + table + "'::regclass, 'pg_class')").get(0).get(0);
            assertThat(comment).as(table).contains("Starter list, not clinically reviewed");
        }
    }

    // ---------------- S-10, S-11, S-12 the templates ----------------

    @Test
    void theFourTemplatesAreVersionOneWithTheNamesAndCountsOfTheStory() throws Exception {
        String[][] expected = {{"gp", "General practice", "11", "6"}, {"dental", "Dental", "11", "7"},
                {"paeds", "Paediatrics", "9", "4"}, {"homeo", "Homeopathy", "7", "6"}};
        for (String[] e : expected) {
            List<String> row = globalRows("select t.name, t.version::text, count(s.*)::text, "
                    + "count(*) filter (where s.required_default)::text, max(s.position)::text, count(distinct s.position)::text "
                    + "from opd.emr_template t join opd.emr_template_section s on s.template_key = t.template_key and s.version = t.version "
                    + "where t.template_key = ? group by t.name, t.version", e[0]).get(0);
            assertThat(row.get(0)).isEqualTo(e[1]);
            assertThat(row.get(1)).as(e[0] + " version").isEqualTo("1");
            assertThat(row.get(2)).as(e[0] + " sections").isEqualTo(e[2]);
            assertThat(row.get(3)).as(e[0] + " required by default").isEqualTo(e[3]);
            assertThat(row.get(4)).as(e[0] + " positions run 1 to n without gaps").isEqualTo(e[2]);
            assertThat(row.get(5)).isEqualTo(e[2]);
        }
        assertThat(count("select count(*) from opd.emr_template_section")).isEqualTo(38);
    }

    @Test
    void everySectionMatchesTheStoryTableRowByRow() throws Exception {
        for (String key : TEMPLATE_KEYS) {
            String[][] expected = EmrTemplatesReadIT.TABLE_FOR(key);
            List<List<String>> stored = globalRows("select section_key, label, part, required_default::text, editor_type, "
                    + "options_source || case when options_master is null then '' else ':' || options_master end "
                    + "from opd.emr_template_section where template_key = ? and version = 1 order by position", key);
            assertThat(stored).as(key).hasSize(expected.length);
            for (int i = 0; i < expected.length; i++) {
                List<String> row = stored.get(i);
                assertThat(row.get(0)).as(key + " #" + (i + 1) + " key").isEqualTo(expected[i][0]);
                assertThat(row.get(1)).as(key + "/" + expected[i][0] + " label").isEqualTo(expected[i][1]);
                assertThat(row.get(2)).as(key + "/" + expected[i][0] + " part").isEqualTo(expected[i][2]);
                assertThat(row.get(3)).as(key + "/" + expected[i][0] + " required").isEqualTo(
                        expected[i][3].equals("true") ? "true" : "false");
                assertThat(row.get(4)).as(key + "/" + expected[i][0] + " editor").isEqualTo(expected[i][4]);
                assertThat(row.get(5)).as(key + "/" + expected[i][0] + " options").isEqualTo(expected[i][5]);
            }
        }
    }

    @Test
    void exactlySixSectionsAreNotHideable() throws Exception {
        List<String> fixed = new ArrayList<>();
        globalRows("select template_key || '/' || section_key from opd.emr_template_section where not hideable")
                .forEach(r -> fixed.add(r.get(0)));
        assertThat(fixed).containsExactlyInAnyOrder(EmrTemplatesReadIT.NOT_HIDEABLE);
        assertThat(count("select count(*) from opd.emr_template_section where hideable")).isEqualTo(32);
    }

    // ---------------- S-13, S-14, S-15 rules for sections and options ----------------

    private static final String NEW_TEMPLATE = "insert into opd.emr_template (template_key, version, name) values ('gp', 2, 'General practice v2')";
    private static final String SECTION = "insert into opd.emr_template_section (template_key, version, section_key, position, label, "
            + "part, editor_type, icon_key, options_source, options_master) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static Object[] section(String key, int position, String label, String part, String editor, String icon,
                                    String source, String master) {
        return new Object[] {"gp", 2, key, position, label, part, editor, icon, source, master};
    }

    @Test
    void aSectionThatBreaksTheRulesCannotBeStored() throws Exception {
        db().asMigrator(c -> {
            DbAccess.execute(c, NEW_TEMPLATE);
            expectOk(c, SECTION, section("test_one", 1, "Test", "ASSESSMENT", "CHIP_LIST", null, "TEMPLATE", null));
            Object[][] bad = {
                    section("t_editor", 2, "Test", "ASSESSMENT", "FREE_TEXT", null, "TEMPLATE", null),
                    section("t_part", 2, "Test", "OTHER", "CHIP_LIST", null, "TEMPLATE", null),
                    section("t_source", 2, "Test", "ASSESSMENT", "CHIP_LIST", null, "X", null),
                    section("t_master_on_template", 2, "Test", "ASSESSMENT", "CHIP_LIST", null, "TEMPLATE", "complaints"),
                    section("t_no_master", 2, "Test", "ASSESSMENT", "COMPLAINTS", null, "MASTER", null),
                    section("t_bad_master", 2, "Test", "ASSESSMENT", "COMPLAINTS", null, "MASTER", "allergies"),
                    section("t_label", 2, "x".repeat(61), "ASSESSMENT", "CHIP_LIST", null, "TEMPLATE", null),
                    section("t_icon", 2, "Test", "ASSESSMENT", "CHIP_LIST", "Bad Icon", "TEMPLATE", null),
                    section("t_position", 0, "Test", "ASSESSMENT", "CHIP_LIST", null, "TEMPLATE", null),
                    section("Bad-Key", 2, "Test", "ASSESSMENT", "CHIP_LIST", null, "TEMPLATE", null),
                    section("a", 2, "Test", "ASSESSMENT", "CHIP_LIST", null, "TEMPLATE", null)};
            for (Object[] row : bad) {
                assertThat(expectError(c, SECTION, row).state()).as("section " + row[2]).isEqualTo("23514");
            }
            // all eleven editor types are accepted, and an icon key is optional
            String[] editors = {"COMPLAINTS", "VITALS_STEPPERS", "SIGNS", "SYSTEMS", "DIAGNOSIS", "PRESCRIPTION", "TOOTH_CHART",
                    "TREATMENT_PLAN", "SERVICES", "ADVICE", "CHIP_LIST"};
            int position = 10;
            for (String editor : editors) {
                expectOk(c, SECTION, section("ed_" + editor.toLowerCase(), position++, "Test", "TREATMENT", editor, "star-1", "NONE", null));
            }
            // duplicates
            assertThat(expectError(c, SECTION, section("test_one", 50, "Dup key", "ASSESSMENT", "CHIP_LIST", null, "TEMPLATE", null)).state())
                    .as("same section key in one version").isEqualTo("23505");
            assertThat(expectError(c, SECTION, section("other_key", 1, "Dup position", "ASSESSMENT", "CHIP_LIST", null, "TEMPLATE", null)).state())
                    .as("same position in one version").isEqualTo("23505");
            // a section of a template version that does not exist, and an option of a section that does not exist
            assertThat(expectError(c, SECTION, new Object[] {"gp", 99, "orphan", 1, "Test", "ASSESSMENT", "CHIP_LIST", null, "TEMPLATE", null}).state())
                    .isEqualTo("23503");
            assertThat(expectError(c, "insert into opd.emr_template_option (template_key, version, section_key, option_key, position, label) "
                    + "values ('gp', 2, 'no_such_section', 'opt', 1, 'Option')").state()).isEqualTo("23503");
            return null;
        });
    }

    @Test
    void anOptionThatBreaksTheRulesCannotBeStoredAndOnlyOneNoneIsAllowedPerSection() throws Exception {
        db().asMigrator(c -> {
            DbAccess.execute(c, NEW_TEMPLATE);
            DbAccess.execute(c, SECTION, section("chips", 1, "Chips", "ASSESSMENT", "CHIP_LIST", null, "TEMPLATE", null));
            String option = "insert into opd.emr_template_option (template_key, version, section_key, option_key, position, label, style, is_none) "
                    + "values ('gp', 2, 'chips', ?, ?, ?, ?, ?)";
            expectOk(c, option, "red_one", 1, "Red one", "ALERT", false);
            expectOk(c, option, "none", 2, "None", "NORMAL", true);
            assertThat(expectError(c, option, "second_none", 3, "Nothing abnormal", "NORMAL", true).state())
                    .as("a second None in one section").isEqualTo("23505");
            assertThat(expectError(c, option, "bad_style", 3, "Bad", "RED", false).state()).isEqualTo("23514");
            assertThat(expectError(c, option, "Bad-Key", 3, "Bad", "NORMAL", false).state()).isEqualTo("23514");
            assertThat(expectError(c, option, "long_label", 3, "x".repeat(81), "NORMAL", false).state()).isEqualTo("23514");
            assertThat(expectError(c, option, "red_one", 4, "Same key", "NORMAL", false).state()).isEqualTo("23505");
            assertThat(expectError(c, option, "same_position", 1, "Same position", "NORMAL", false).state()).isEqualTo("23505");
            assertThat(expectError(c, option, "zero_position", 0, "Zero", "NORMAL", false).state()).isEqualTo("23514");
            return null;
        });
    }

    // ---------------- S-16 immutability ----------------

    @Test
    void aShippedTemplateVersionCannotBeUpdatedDeletedOrTruncatedEvenByTheOwner() throws Exception {
        for (String table : TEMPLATE_TABLES) {
            long before = count("select count(*) from opd." + table);
            for (String sql : new String[] {"update opd." + table + " set created_at = now()", "delete from opd." + table,
                    "truncate opd." + table + " cascade"}) {
                SqlError err = db().asMigrator(c -> expectError(c, sql));
                assertThat(err.state()).as(sql).isEqualTo("42501");
                assertThat(err.message()).as(sql).contains("immutable");
            }
            // a plain truncate is refused too: by the trigger, or first by the foreign key that points at the table
            SqlError plain = db().asMigrator(c -> expectError(c, "truncate opd." + table));
            assertThat(plain.state()).as("plain truncate of " + table).isIn("42501", "0A000");
            // the owner cannot work around it with a where clause either
            SqlError narrow = db().asMigrator(c -> expectError(c, "delete from opd." + table + " where created_at is not null"));
            assertThat(narrow.message()).contains("immutable");
            assertThat(count("select count(*) from opd." + table)).as(table + " rows unchanged").isEqualTo(before);
        }
        // the three tables only; the aliases may be fixed by a later migration
        db().asMigrator(c -> {
            expectOk(c, "update opd.emr_specialty_alias set created_at = now()");
            return null;
        });
    }

    @Test
    void aNewVersionIsNewRowsAndTheOldVersionStaysAsItWas() throws Exception {
        db().asMigrator(c -> {
            long oldSections = DbAccess.count(c, "select count(*) from opd.emr_template_section where template_key = 'gp' and version = 1");
            long oldOptions = DbAccess.count(c, "select count(*) from opd.emr_template_option where template_key = 'gp' and version = 1");
            DbAccess.execute(c, NEW_TEMPLATE);
            DbAccess.execute(c, SECTION, section("only_section", 1, "Only section", "ASSESSMENT", "CHIP_LIST", null, "TEMPLATE", null));
            DbAccess.execute(c, "insert into opd.emr_template_option (template_key, version, section_key, option_key, position, label) "
                    + "values ('gp', 2, 'only_section', 'opt', 1, 'Option')");
            assertThat(DbAccess.count(c, "select max(version) from opd.emr_template where template_key = 'gp'"))
                    .as("the newest version per key").isEqualTo(2);
            assertThat(DbAccess.count(c, "select count(*) from opd.emr_template_section where template_key = 'gp' and version = 1"))
                    .isEqualTo(oldSections);
            assertThat(DbAccess.count(c, "select count(*) from opd.emr_template_option where template_key = 'gp' and version = 1"))
                    .isEqualTo(oldOptions);
            assertThat(DbAccess.count(c, "select count(*) from opd.emr_template_section where template_key = 'gp' and version = 2"))
                    .isEqualTo(1);
            return null;
        });
        assertThat(count("select max(version) from opd.emr_template where template_key = 'gp'")).as("rolled back").isEqualTo(1);
    }

    // ---------------- S-17, S-18 options ----------------

    @Test
    void everyTemplateSectionHasChipsAndEveryRequiredChipListHasExactlyOneNoneOption() throws Exception {
        assertThat(count("select count(*) from opd.emr_template_section s where s.options_source = 'TEMPLATE' and not exists "
                + "(select 1 from opd.emr_template_option o where o.template_key = s.template_key and o.version = s.version "
                + "and o.section_key = s.section_key)")).as("TEMPLATE sections without a chip").isZero();
        List<List<String>> required = globalRows("select s.template_key || '/' || s.section_key, "
                + "(select count(*) from opd.emr_template_option o where o.template_key = s.template_key and o.version = s.version "
                + "and o.section_key = s.section_key and o.is_none)::text from opd.emr_template_section s "
                + "where s.editor_type = 'CHIP_LIST' and s.required_default order by 1");
        assertThat(required.stream().map(r -> r.get(0)).toList()).containsExactlyInAnyOrder("dental/medical_alerts",
                "dental/intra_oral_exam", "homeo/case_history", "homeo/mentals_generals");
        required.forEach(r -> assertThat(r.get(1)).as(r.get(0)).isEqualTo("1"));
        assertThat(count("select count(*) from opd.emr_template_section s where s.options_source <> 'TEMPLATE' and exists "
                + "(select 1 from opd.emr_template_option o where o.template_key = s.template_key and o.version = s.version "
                + "and o.section_key = s.section_key)")).as("chips only on TEMPLATE sections").isZero();
    }

    @Test
    void theGeneralExaminationAndSystemChipsAreInTheStoryOrderAndAllergyChipsAreRed() throws Exception {
        for (String template : new String[] {"gp", "paeds"}) {
            List<String> signs = new ArrayList<>();
            globalRows("select option_key from opd.emr_template_option where template_key = ? and section_key = 'general_exam' "
                    + "order by position", template).forEach(r -> signs.add(r.get(0)));
            assertThat(signs).as(template).containsExactly("pallor", "icterus", "cyanosis", "clubbing", "lymph_nodes",
                    "oedema", "dehydration");
        }
        List<String> systems = new ArrayList<>();
        globalRows("select option_key from opd.emr_template_option where template_key = 'gp' and section_key = 'systemic_exam' "
                + "order by position").forEach(r -> systems.add(r.get(0)));
        assertThat(systems).containsExactly("cvs", "rs", "cns", "abdomen");
        assertThat(count("select count(*) from opd.emr_template_option where template_key = 'gp' "
                + "and section_key = 'history_allergies' and style = 'ALERT'")).isPositive();
        assertThat(count("select count(*) from opd.emr_template_option where template_key = 'dental' "
                + "and section_key = 'medical_alerts' and style = 'ALERT'")).isPositive();
    }

    // ---------------- S-19, S-21 aliases and specialties without a template ----------------

    @Test
    void theEightAliasesMapAsTheDecisionSaysAndOnlyTheNormalisedFormCanBeStored() throws Exception {
        String[][] expected = {{"general physician", "gp"}, {"general practice", "gp"}, {"dental surgeon", "dental"},
                {"dental", "dental"}, {"paediatrician", "paeds"}, {"paediatrics", "paeds"}, {"homeopath", "homeo"},
                {"homeopathy", "homeo"}};
        for (String[] e : expected) {
            assertThat(globalRows("select template_key from opd.emr_specialty_alias where alias_normalised = ?", e[0]).get(0).get(0))
                    .as(e[0]).isEqualTo(e[1]);
        }
        db().asMigrator(c -> {
            for (String bad : new String[] {"Dental", " dental", "dental ", "dental  surgeon", "", "x".repeat(81), "GENERAL"}) {
                assertThat(expectError(c, "insert into opd.emr_specialty_alias (alias_normalised, template_key) values (?, 'dental')", bad).state())
                        .as("alias '" + bad + "'").isEqualTo("23514");
            }
            assertThat(expectError(c, "insert into opd.emr_specialty_alias (alias_normalised, template_key) values ('ayurveda', 'ayur')").state())
                    .isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_specialty_alias (alias_normalised, template_key) values ('dental', 'dental')").state())
                    .as("duplicate alias").isEqualTo("23505");
            expectOk(c, "insert into opd.emr_specialty_alias (alias_normalised, template_key) values ('dentist', 'dental')");
            return null;
        });
    }

    @Test
    void specialtiesWithoutAnEmrTemplateHaveNeitherATemplateNorAnAlias() throws Exception {
        assertThat(count("select count(*) from opd.emr_template where template_key not in ('gp', 'dental', 'paeds', 'homeo')")).isZero();
        assertThat(count("select count(*) from opd.emr_specialty_alias where template_key not in ('gp', 'dental', 'paeds', 'homeo')")).isZero();
        for (String alias : new String[] {"ayurveda", "physiotherapy", "dermatology", "gynaecology", "ent", "oncology", "surgery"}) {
            assertThat(count("select count(*) from opd.emr_specialty_alias where alias_normalised like ?", alias + "%")).as(alias).isZero();
        }
        db().asMigrator(c -> {
            for (String key : new String[] {"ayur", "physio", "derma", "gynae", "ent", "oncology", "surgery"}) {
                assertThat(expectError(c, "insert into opd.emr_template (template_key, version, name) values (?, 1, 'x')", key).state())
                        .as(key).isEqualTo("23514");
            }
            return null;
        });
    }
}
