package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * SAH-43 / SAH-53 stored shape of the global EMR tables (the ten of SAH-53; the two of SAH-48-59 are in EmrPhase3TablesIT) (test cases S-50 to S-57 of docs/database/SAH-53.md and
 * S-01 to S-09 of docs/database/SAH-43-44.md for the masters), by SQL on the real {@code sahaj_test} database: seed
 * counts, the starting values of the story lists, the read-only grant of the app role, no tenant column, the check
 * constraints, unique names, stable ids, the immutable starter layout (also for the table owner), the aliases and the
 * "Starter list, not clinically reviewed" comments. Writes that must work (to prove a constraint is the reason for a
 * refusal) run as the table owner in a transaction that is always rolled back. Test plan: docs/test-plans/SAH-53.md.
 */
@OnTestDatabase
class EmrGlobalTablesIT extends EmrSupport {

    private static final String[] GLOBAL_TABLES = {"emr_complaint", "emr_investigation", "emr_advice", "emr_section",
            "emr_section_field", "emr_section_field_option", "emr_starter_department", "emr_starter_department_section",
            "emr_starter_layout", "emr_department_alias"};
    private static final String[] MASTER_TABLES = {"emr_complaint", "emr_investigation", "emr_advice"};

    /** Per starter: the number of sections in its ordered list (SAH-53-1, 341 in all). */
    private static final Map<String, Integer> PLACEMENTS = new LinkedHashMap<>();

    static {
        PLACEMENTS.put("gm", 19);
        PLACEMENTS.put("dental", 24);
        PLACEMENTS.put("og", 25);
        PLACEMENTS.put("paeds", 23);
        PLACEMENTS.put("physio", 27);
        PLACEMENTS.put("ortho", 22);
        PLACEMENTS.put("eye", 22);
        PLACEMENTS.put("ent", 22);
        PLACEMENTS.put("derm", 20);
        PLACEMENTS.put("diab", 22);
        PLACEMENTS.put("cardio", 21);
        PLACEMENTS.put("ayush", 21);
        PLACEMENTS.put("onco", 27);
        PLACEMENTS.put("surgery", 26);
        PLACEMENTS.put("psych", 20);
    }

    private long count(String sql, Object... params) throws SQLException {
        return globalCount(sql, params);
    }

    private Set<String> keys(String sql, Object... params) throws SQLException {
        Set<String> out = new TreeSet<>();
        globalRows(sql, params).forEach(r -> out.add(r.get(0)));
        return out;
    }

    private static Set<String> setOf(String... keys) {
        return new TreeSet<>(Set.of(keys));
    }

    // ---------------- S-50 seed counts, S-20 the older tables ----------------

    @Test
    void theSeedHasTheCountsOfTheDesignNote() throws Exception {
        assertThat(count("select count(*) from opd.emr_complaint")).isEqualTo(59);
        assertThat(count("select count(*) from opd.emr_investigation")).isEqualTo(46);
        assertThat(count("select count(*) from opd.emr_advice")).isEqualTo(50);
        assertThat(count("select count(*) from opd.emr_section")).isEqualTo(100);
        assertThat(count("select count(*) from opd.emr_section where kind = 'COMMON'")).isEqualTo(14);
        assertThat(count("select count(*) from opd.emr_section where kind = 'SPECIALTY'")).isEqualTo(78);
        assertThat(count("select count(*) from opd.emr_section where kind = 'COMPLIANCE'")).isEqualTo(8);
        assertThat(count("select count(*) from opd.emr_section_field")).isEqualTo(313);
        assertThat(count("select count(*) from opd.emr_section_field_option")).isEqualTo(136);
        assertThat(count("select count(*) from opd.emr_starter_department")).isEqualTo(15);
        assertThat(count("select count(*) from opd.emr_starter_department_section")).isEqualTo(341);
        assertThat(count("select count(*) from opd.emr_department_alias")).isEqualTo(54);
        assertThat(count("select count(*) from opd.emr_starter_layout")).isEqualTo(15);
        assertThat(count("select count(*) from opd.emr_starter_layout where layout_version = 1")).isEqualTo(15);
        assertThat(count("select count(*) from opd.emr_starter_department where current_layout_version = 1")).isEqualTo(15);
    }

    @Test
    void theFifteenStartersAreTheMockupDepartmentsInOrderWithTheirSectionCounts() throws Exception {
        List<List<String>> rows = globalRows("select starter_key, name, initials, colour, position from opd.emr_starter_department order by position");
        assertThat(rows.stream().map(r -> r.get(0)).toList()).containsExactly(STARTER_KEYS);
        assertThat(rows.stream().map(r -> r.get(1)).toList()).containsExactly("General Medicine", "Dental",
                "Obstetrics & Gynaecology", "Paediatrics", "Physiotherapy", "Orthopaedics", "Ophthalmology", "ENT",
                "Dermatology & Cosmetology", "Diabetology", "Cardiology", "AYUSH (Ayurveda)", "Medical Oncology",
                "General Surgery", "Psychiatry & Counselling");
        rows.forEach(r -> {
            assertThat(r.get(2)).matches("^[A-Z0-9]{1,3}$");
            assertThat(r.get(3)).matches("^#[0-9A-F]{6}$");
        });
        for (Map.Entry<String, Integer> e : PLACEMENTS.entrySet()) {
            assertThat(count("select count(*) from opd.emr_starter_department_section where starter_key = ?", e.getKey()))
                    .as(e.getKey()).isEqualTo((long) e.getValue());
            // the order is 1..n with no gap
            assertThat(count("select count(*) from opd.emr_starter_department_section where starter_key = ? and position between 1 and ?",
                    e.getKey(), e.getValue())).as(e.getKey() + " positions").isEqualTo((long) e.getValue());
        }
        assertThat(PLACEMENTS.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(341);
        assertThat(count("select count(*) from opd.emr_section s where not exists (select 1 from "
                + "opd.emr_starter_department_section p where p.section_key = s.section_key)"))
                .as("every library section is used by at least one department").isZero();
    }

    @Test
    void theDefaultOrderIsTheFourteenCommonSectionsThenSpecialtyThenTheThreeComplianceSections() throws Exception {
        List<String> common = new ArrayList<>();
        globalRows("select section_key from opd.emr_section where kind = 'COMMON' order by position")
                .forEach(r -> common.add(r.get(0)));
        assertThat(common).hasSize(14).startsWith("vitals", "complaints", "hpi", "history", "allergy", "exam");
        assertThat(common).endsWith("referral", "attachments");
        for (String starter : STARTER_KEYS) {
            List<String> order = new ArrayList<>();
            globalRows("select section_key from opd.emr_starter_department_section where starter_key = ? order by position",
                    starter).forEach(r -> order.add(r.get(0)));
            assertThat(order.subList(0, 14)).as(starter + " starts with the common sections").containsExactlyElementsOf(common);
            List<String> tail = order.subList(order.size() - ("og".equals(starter) ? 4 : 3), order.size());
            assertThat(tail).as(starter + " ends with the compliance sections")
                    .containsExactlyElementsOf("og".equals(starter) ? List.of("abha", "consent", "rxformat", "pcpndt")
                            : List.of("abha", "consent", "rxformat"));
            // the sections between are specialty sections
            for (String key : order.subList(14, order.size() - ("og".equals(starter) ? 4 : 3))) {
                assertThat(globalRows("select kind from opd.emr_section where section_key = ?", key).get(0).get(0))
                        .as(starter + "/" + key + " (a consent or scheme section of the library is COMPLIANCE by ADR-020 yet sits among the specialty sections)")
                        .isIn("SPECIALTY", "COMPLIANCE");
            }
        }
    }

    @Test
    void theOlderStarterListsOfSah7AreUnchanged() throws Exception {
        assertThat(count("select count(*) from opd.diagnosis")).isEqualTo(53);
        assertThat(count("select count(*) from opd.medicine")).as("52 of SAH-7, Co-trimoxazole 960 mg of SAH-48 and 10 of SAH-49-71 (V2026_12_11_1020)").isEqualTo(63);
        assertThat(count("select count(*) from platform.specialty_template")).isPositive();
        assertThat(count("select count(*) from information_schema.tables where table_schema = 'platform' "
                + "and table_name like 'emr%'")).isZero();
    }

    @Test
    void theRetiredTablesOfTheWithdrawnTemplateModelAreGone() throws Exception {
        for (String table : new String[] {"emr_template", "emr_template_section", "emr_template_option",
                "emr_specialty_alias", "emr_template_setup", "emr_template_override", "emr_settings"}) {
            assertThat(count("select count(*) from information_schema.tables where table_schema = 'opd' and table_name = ?",
                    table)).as(table + " must not exist").isZero();
        }
        assertThat(count("select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                + "where n.nspname = 'opd' and p.proname = 'deny_emr_template_change'")).isZero();
        assertThat(count("select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                + "where n.nspname = 'opd' and p.proname = 'deny_emr_immutable_change'")).isEqualTo(1);
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

    // ---------------- S-51 the starting values of the story ----------------

    @Test
    void theStartingValuesAreSeededAsDrawnInTheStoryLists() throws Exception {
        String shown = "from opd.emr_starter_department_section where ";
        assertThat(keys("select distinct section_key " + shown + "required")).as("Required at start (12 keys)")
                .isEqualTo(setOf("complaints", "diagnosis", "rx", "rxformat", "dental_chart", "pcpndt", "anc", "pain", "va",
                        "immunization", "growth", "lesion_map"));
        assertThat(keys("select distinct section_key " + shown + "print")).as("Print on prescription (12 keys)")
                .isEqualTo(setOf("vitals", "complaints", "diagnosis", "rx", "advice", "followup", "investigations",
                        "refraction", "tx_plan", "hep", "anc", "immunization"));
        assertThat(keys("select distinct section_key " + shown + "copy_from_last")).as("Copy from last visit (10 keys)")
                .isEqualTo(setOf("history", "allergy", "obstetric", "menstrual", "birth", "dental_chart", "chronic",
                        "immunization", "growth", "prakriti"));
        assertThat(keys("select distinct section_key " + shown + "part = 'TREATMENT'")).as("Part 2 Treatment (39 keys)")
                .hasSize(39).contains("diagnosis", "rx", "rxformat", "dental_chart", "sx_postop", "onc_scheme", "consent");
        assertThat(keys("select distinct section_key " + shown + "who_nurse")).as("Nurse / assistant")
                .isEqualTo(setOf("vitals", "complaints", "history", "allergy", "attachments", "abha", "growth", "va",
                        "refraction", "glucose", "iop", "audiometry"));
        assertThat(keys("select distinct section_key " + shown + "who_reception")).as("Reception")
                .isEqualTo(setOf("abha", "consent"));
        assertThat(count("select count(*) " + shown + "not who_doctor")).as("Doctor on every section").isZero();
        assertThat(count("select count(*) " + shown + "collapsed")).as("nothing starts collapsed").isZero();
    }

    @Test
    void theSectionsOffAtStartAreTheListedOnesAndConsentStartsOnlyForTheSixConsentDepartments() throws Exception {
        Map<String, Set<String>> hidden = new LinkedHashMap<>();
        hidden.put("gm", setOf("procedures", "referral", "consent"));
        hidden.put("dental", setOf("exam", "hpi", "referral"));
        hidden.put("og", setOf("referral"));
        hidden.put("paeds", setOf("referral", "procedures", "consent"));
        hidden.put("physio", setOf("rx", "investigations", "exam", "procedures", "consent"));
        hidden.put("ortho", setOf());
        hidden.put("eye", setOf("exam", "consent"));
        hidden.put("ent", setOf("exam", "consent"));
        hidden.put("derm", setOf("exam"));
        hidden.put("diab", setOf("consent"));
        hidden.put("cardio", setOf("consent"));
        hidden.put("ayush", setOf("investigations", "consent"));
        hidden.put("onco", setOf("procedures", "referral", "exam"));
        hidden.put("surgery", setOf());
        hidden.put("psych", setOf("exam", "procedures", "consent"));
        for (Map.Entry<String, Set<String>> e : hidden.entrySet()) {
            assertThat(keys("select section_key from opd.emr_starter_department_section where starter_key = ? and not shown",
                    e.getKey())).as("off at start in " + e.getKey()).isEqualTo(e.getValue());
        }
        assertThat(keys("select starter_key from opd.emr_starter_department_section where section_key = 'consent' and shown"))
                .isEqualTo(setOf("dental", "og", "ortho", "derm", "onco", "surgery"));
        assertThat(keys("select starter_key from opd.emr_starter_department where consent_flag"))
                .isEqualTo(setOf("dental", "og", "ortho", "derm", "onco", "surgery"));
        assertThat(count("select count(*) from opd.emr_starter_department_section where not shown")).isEqualTo(30);
        // a hidden section is never required, and only PCPNDT Form F in og is a placement lock
        assertThat(count("select count(*) from opd.emr_starter_department_section where required and not shown")).isZero();
        assertThat(globalRows("select starter_key || '/' || section_key from opd.emr_starter_department_section where always_shown"))
                .hasSize(1).first().isEqualTo(List.of("og/pcpndt"));
        assertThat(keys("select section_key from opd.emr_section where always_shown")).isEqualTo(setOf("allergy"));
        assertThat(count("select count(*) from opd.emr_starter_department_section where section_key = 'allergy' and not shown"))
                .isZero();
        // the one departure from the mockup counts, written down in docs/database/SAH-53.md: physiotherapy Rx is off and not required
        assertThat(globalRows("select shown::text || required::text from opd.emr_starter_department_section "
                + "where starter_key = 'physio' and section_key = 'rx'").get(0).get(0)).isEqualTo("falsefalse");
    }

    @Test
    void everyFieldHasALabelATypeOfTheFixedListAMetaTextAndARequiredFlagAndEverySectionHasAField() throws Exception {
        assertThat(count("select count(distinct field_type) from opd.emr_section_field")).isEqualTo(30);
        assertThat(count("select count(*) from opd.emr_section s where not exists (select 1 from opd.emr_section_field f "
                + "where f.section_key = s.section_key)")).isZero();
        assertThat(count("select count(*) from opd.emr_section_field where btrim(label) = '' or meta is null")).isZero();
        assertThat(count("select count(*) from opd.emr_section_field where required")).isEqualTo(73);
        db().asMigrator(c -> {
            SqlError err = expectError(c, "insert into opd.emr_section_field (section_key, field_key, position, label, field_type) "
                    + "values ('vitals', 'qa_free', 99, 'Qa free', 'FREE_TEXT')");
            assertThat(err.state()).isEqualTo("23514");
            return null;
        });
        // a section the mockup gives no field list carries the three default fields: Findings (rich text), Template
        // (dropdown) and Attachments (file)
        List<List<String>> fallback = globalRows("select f.section_key from opd.emr_section_field f group by f.section_key "
                + "having count(*) = 3 and bool_and(case f.field_key when 'findings' then f.label = 'Findings' and f.field_type = 'RICH_TEXT' "
                + "when 'template' then f.label = 'Template' and f.field_type = 'DROPDOWN' "
                + "when 'attachments' then f.label = 'Attachments' and f.field_type = 'FILE' else false end)");
        assertThat(fallback.size()).as("sections with the default fields").isBetween(30, 60);
        // the vitals section of the mockup
        assertThat(globalRows("select string_agg(label, ',' order by position) from opd.emr_section_field where section_key = 'vitals'")
                .get(0).get(0)).startsWith("BP systolic,BP diastolic,Pulse");
    }

    // ---------------- S-53 the None option ----------------

    @Test
    void everyRequiredMultiSelectOrChecklistFieldWithOptionsHasOneNoneOptionExceptTheEightListedChoiceFields() throws Exception {
        assertThat(count("select count(*) from opd.emr_section_field f where f.required and f.field_type in "
                + "('MULTI_SELECT', 'CHECKLIST') and exists (select 1 from opd.emr_section_field_option o where "
                + "o.section_key = f.section_key and o.field_key = f.field_key) and (select count(*) from "
                + "opd.emr_section_field_option o where o.section_key = f.section_key and o.field_key = f.field_key "
                + "and o.is_none) <> 1")).as("required multi-select without exactly one None").isZero();
        assertThat(globalRows("select o.section_key || '.' || o.field_key from opd.emr_section_field_option o "
                + "where o.is_none order by 1").stream().map(r -> r.get(0)).toList())
                .containsExactly("modalities.modality", "sx_fitness.pre_op_tests");
        for (String field : new String[] {"dental_chart.numbering", "pain.scale", "mmt.grade", "onc_ecog.ecog",
                "onc_regimen.dose_level", "sx_fitness.asa_grade", "sx_fitness.pac", "sx_plan.anaesthesia"}) {
            String[] parts = field.split("\\.");
            assertThat(count("select count(*) from opd.emr_section_field_option where section_key = ? and field_key = ? and is_none",
                    parts[0], parts[1])).as(field + " has no None option").isZero();
        }
        db().asMigrator(c -> {
            // a second None for a field is refused by the partial unique index
            assertThat(expectError(c, "insert into opd.emr_section_field_option (section_key, field_key, option_key, position, label, is_none) "
                    + "values ('modalities', 'modality', 'nothing', 99, 'Nothing', true)").state()).isEqualTo("23505");
            return null;
        });
    }

    // ---------------- S-54 option sources ----------------

    @Test
    void theOptionsSourceRulesHoldInTheStoredData() throws Exception {
        assertThat(count("select count(*) from opd.emr_section_field where (options_source = 'MASTER') <> (options_master is not null)"))
                .isZero();
        assertThat(count("select count(*) from opd.emr_section_field f where f.options_source = 'FIELD' and not exists "
                + "(select 1 from opd.emr_section_field_option o where o.section_key = f.section_key and o.field_key = f.field_key)"))
                .as("every FIELD-source field has options").isZero();
        assertThat(count("select count(*) from opd.emr_section_field f where f.options_source <> 'FIELD' and exists "
                + "(select 1 from opd.emr_section_field_option o where o.section_key = f.section_key and o.field_key = f.field_key)"))
                .as("options only where the source is FIELD").isZero();
        assertThat(count("select count(distinct section_key || '.' || field_key) from opd.emr_section_field_option")).isEqualTo(37);
        db().asMigrator(c -> {
            assertThat(expectError(c, "insert into opd.emr_section_field (section_key, field_key, position, label, field_type, options_source) "
                    + "values ('vitals', 'qa_a', 98, 'Qa a', 'TEXT', 'MASTER')").state()).isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_section_field (section_key, field_key, position, label, field_type, options_master) "
                    + "values ('vitals', 'qa_b', 99, 'Qa b', 'TEXT', 'complaints')").state()).isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_section_field (section_key, field_key, position, label, field_type, min_value, max_value) "
                    + "values ('vitals', 'qa_c', 97, 'Qa c', 'NUMBER', 5, 1)").state()).isEqualTo("23514");
            return null;
        });
    }

    // ---------------- S-52, S-03 grants and no tenant column ----------------

    @Test
    void theAppCanSelectEveryGlobalTableWithNoClinicBound() throws Exception {
        for (String table : GLOBAL_TABLES) {
            assertThat(count("select count(*) from opd." + table)).as(table).isPositive();
        }
    }

    @Test
    void theAppCannotInsertUpdateDeleteOrTruncateAnyGlobalTable() throws Exception {
        for (String table : GLOBAL_TABLES) {
            String firstColumn = globalRows("select column_name from information_schema.columns where table_schema = 'opd' "
                    + "and table_name = '" + table + "' order by ordinal_position limit 1").get(0).get(0);
            for (String sql : new String[] {"insert into opd." + table + " default values",
                    "update opd." + table + " set " + firstColumn + " = " + firstColumn, "delete from opd." + table,
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
    void noGlobalTableHasATenantColumnOrRowLevelSecurity() throws Exception {
        for (String table : GLOBAL_TABLES) {
            assertThat(count("select count(*) from information_schema.columns where table_schema = 'opd' "
                    + "and table_name = '" + table + "' and column_name = 'tenant_id'")).as(table).isZero();
            assertThat(count("select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                    + "where n.nspname = 'opd' and c.relname = '" + table + "' and c.relrowsecurity")).as(table
                    + " has no row level security by design (ADR-019 D2, ADR-020 D2)").isZero();
        }
    }

    // ---------------- S-55 the starter layout is immutable ----------------

    @Test
    void aStarterLayoutCannotBeUpdatedDeletedOrTruncatedByAnyRoleAndANewVersionIsNewRows() throws Exception {
        db().asMigrator(c -> {
            for (String sql : new String[] {"update opd.emr_starter_layout set layout = layout where starter_key = 'gm'",
                    "delete from opd.emr_starter_layout where starter_key = 'gm'"}) {
                SqlError err = expectError(c, sql);
                assertThat(err.state()).as(sql).isEqualTo("42501");
                assertThat(err.message()).as(sql).contains("immutable");
            }
            // truncate is refused too: by the trigger, or earlier because the table is referenced by a foreign key
            assertThat(expectError(c, "truncate opd.emr_starter_layout").state()).isIn("42501", "0A000");
            return null;
        });
        db().asApp(c -> {
            assertThat(expectError(c, "update opd.emr_starter_layout set layout = layout").state()).isEqualTo("42501");
            assertThat(expectError(c, "delete from opd.emr_starter_layout").state()).isEqualTo("42501");
            return null;
        });
        // version 2 can be added next to version 1, and the starter can point at it in the same transaction (deferred key)
        db().asMigrator(c -> {
            expectOk(c, "insert into opd.emr_starter_layout (starter_key, layout_version, layout) "
                    + "select starter_key, 2, layout from opd.emr_starter_layout where starter_key = 'gm' and layout_version = 1");
            expectOk(c, "update opd.emr_starter_department set current_layout_version = 2 where starter_key = 'gm'");
            assertThat(DbAccess.count(c, "select count(*) from opd.emr_starter_layout where starter_key = 'gm'")).isEqualTo(2);
            expectOk(c, "set constraints all immediate");
            // a layout version that does not exist cannot be pointed at
            assertThat(expectError(c, "update opd.emr_starter_department set current_layout_version = 3 where starter_key = 'gm'")
                    .state()).isEqualTo("23503");
            return null;
        });
        assertThat(count("select count(*) from opd.emr_starter_layout where starter_key = 'gm'")).as("rolled back").isEqualTo(1);
    }

    @Test
    void theFifteenLayoutDocumentsMatchWhatAQueryOverTheTablesProduces() throws Exception {
        for (String starter : STARTER_KEYS) {
            List<List<String>> doc = globalRows("select s ->> 'key', s ->> 'part', s ->> 'shown', s ->> 'required', s ->> 'print', "
                    + "s ->> 'copy', s ->> 'collapsed', s ->> 'hideLocked', jsonb_array_length(s -> 'fields')::text "
                    + "from opd.emr_starter_layout l, jsonb_array_elements(l.layout -> 'sections') with ordinality as t(s, n) "
                    + "where l.starter_key = ? and l.layout_version = 1 order by t.n", starter);
            List<List<String>> tables = globalRows("select p.section_key, p.part, p.shown::text, p.required::text, p.print::text, "
                    + "p.copy_from_last::text, p.collapsed::text, (p.always_shown or s.always_shown)::text, "
                    + "(select count(*) from opd.emr_section_field f where f.section_key = p.section_key)::text "
                    + "from opd.emr_starter_department_section p join opd.emr_section s on s.section_key = p.section_key "
                    + "where p.starter_key = ? order by p.position", starter);
            assertThat(doc).as("layout document of " + starter).isEqualTo(tables);
        }
        // hideLocked is true for Allergies everywhere and for PCPNDT Form F in og only
        assertThat(count("select count(*) from opd.emr_starter_layout l, jsonb_array_elements(l.layout -> 'sections') s "
                + "where s ->> 'key' = 'allergy' and (s ->> 'hideLocked')::boolean")).isEqualTo(15);
        assertThat(count("select count(*) from opd.emr_starter_layout l, jsonb_array_elements(l.layout -> 'sections') s "
                + "where (s ->> 'hideLocked')::boolean and s ->> 'key' <> 'allergy'")).isEqualTo(1);
        assertThat(count("select count(*) from opd.emr_starter_layout l where jsonb_typeof(l.layout -> 'schemaVersion') <> 'number'"))
                .isZero();
    }

    // ---------------- S-56 aliases ----------------

    @Test
    void onlyTheNormalisedFormOfAnAliasCanBeStoredAndNoAliasMentionsHomeopathy() throws Exception {
        assertThat(count("select count(*) from opd.emr_department_alias where alias_normalised <> "
                + "lower(btrim(regexp_replace(alias_normalised, '\\s+', ' ', 'g')))")).isZero();
        assertThat(count("select count(*) from opd.emr_department_alias where alias_normalised like '%homeo%'")).isZero();
        assertThat(count("select count(*) from opd.emr_department_alias where starter_key = 'gm' and alias_normalised = 'general physician'"))
                .isEqualTo(1);
        db().asMigrator(c -> {
            for (String bad : new String[] {"Dental", " dental", "dental  surgeon"}) {
                assertThat(expectError(c, "insert into opd.emr_department_alias (alias_normalised, starter_key) values (?, 'dental')", bad)
                        .state()).as("'" + bad + "'").isEqualTo("23514");
            }
            assertThat(expectError(c, "insert into opd.emr_department_alias (alias_normalised, starter_key) values ('qa alias', 'homeo')")
                    .state()).isIn("23503", "23514");
            expectOk(c, "insert into opd.emr_department_alias (alias_normalised, starter_key) values ('qa alias', 'dental')");
            return null;
        });
    }

    // ---------------- S-04, S-05, S-57 the three masters ----------------

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
    void theNameHintAndOrderChecksOfTheMastersAcceptTheFifteenStarterKeysAndRefuseTheRetiredOnes() throws Exception {
        for (String table : MASTER_TABLES) {
            db().asMigrator(c -> {
                for (String bad : new String[] {"", "   ", "x".repeat(201)}) {
                    assertThat(expectError(c, "insert into opd." + table + " (name) values (?)", bad).state())
                            .as(table + " name length " + bad.length()).isEqualTo("23514");
                }
                expectOk(c, "insert into opd." + table + " (name) values (?)", "y".repeat(200));
                for (String refused : new String[] {"ayur", "gp", "homeo", "GM", "dental "}) {
                    assertThat(expectError(c, "insert into opd." + table + " (name, specialty_hint) values ('Qa hint', ?)", refused)
                            .state()).as(table + " hint '" + refused + "'").isEqualTo("23514");
                }
                assertThat(expectError(c, "insert into opd." + table + " (name, sort_order) values ('Qa order', -1)").state())
                        .isEqualTo("23514");
                assertThat(expectError(c, "insert into opd." + table + " (name) values (null)").state()).isEqualTo("23502");
                int i = 0;
                expectOk(c, "insert into opd." + table + " (name, specialty_hint) values (?, null)", "Qa hint accepted null");
                for (String hint : STARTER_KEYS) {
                    expectOk(c, "insert into opd." + table + " (name, specialty_hint) values (?, ?)", "Qa hint accepted " + i++, hint);
                }
                return null;
            });
        }
    }

    @Test
    void theThreeFormerHomeopathyComplaintsNowHaveNoHintAndNoMasterRowCarriesTheRetiredKeys() throws Exception {
        for (String table : MASTER_TABLES) {
            assertThat(count("select count(*) from opd." + table + " where specialty_hint in ('gp', 'homeo')")).as(table).isZero();
        }
        assertThat(count("select count(*) from opd.emr_complaint where specialty_hint is not null and specialty_hint not in "
                + "('gm', 'dental', 'og', 'paeds', 'physio', 'ortho', 'eye', 'ent', 'derm', 'diab', 'cardio', 'ayush', 'onco', 'surgery', 'psych')"))
                .isZero();
    }

    @Test
    void theAdviceMastersMarathiAndHindiNamesAreEmptyInTheSeedAndLimitedToOneToTwoHundredCharacters() throws Exception {
        assertThat(count("select count(*) from opd.emr_advice where name_mr is not null or name_hi is not null")).isZero();
        db().asMigrator(c -> {
            for (String column : new String[] {"name_mr", "name_hi"}) {
                for (String bad : new String[] {"", "x".repeat(201)}) {
                    assertThat(expectError(c, "insert into opd.emr_advice (name, " + column + ") values ('Qa lang " + column + "', ?)", bad)
                            .state()).as(column + " length " + bad.length()).isEqualTo("23514");
                }
                expectOk(c, "insert into opd.emr_advice (name, " + column + ") values ('Qa lang ok " + column + "', ?)", "x".repeat(200));
            }
            return null;
        });
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

    @Test
    void noSeededTextLooksLikeAPatientNameOrMobileNumber() throws Exception {
        assertThat(count("select count(*) from opd.emr_section where name ~ '[0-9]{10}' or description ~ '[0-9]{10}'")).isZero();
        assertThat(count("select count(*) from opd.emr_section_field where label ~ '[0-9]{10}' or meta ~ '[0-9]{10}'")).isZero();
        assertThat(count("select count(*) from opd.emr_starter_department where name ~ '[0-9]{10}' or description ~ '[0-9]{10}'")).isZero();
    }
}
