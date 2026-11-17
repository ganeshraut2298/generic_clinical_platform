package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * SAH-75 database cases 1 to 4, 6, 12, 13 and 15 of docs/database/SAH-75.md (static and read-only checks of the global
 * seed on {@code sahaj_test}): the allow-list of tables the seven migrations may write, the catalogue proof that no
 * tenant table can change the global tables (ADR-026 D2 proofs 1 and 2), the new columns, the dental, Gynae and
 * contraception library, the starter layout documents (pinned hashes of version 1 and 2), the option hint and the
 * comments. Nothing here writes to a table that stays changed: writes that must be refused or accepted run in a
 * transaction that is always rolled back. Test plan: docs/test-plans/SAH-75.md.
 */
@OnTestDatabase
class Sah75GlobalSeedIT extends EmrSupport {

    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");
    private static final List<String> SIX = List.of("opd.emr_section", "opd.emr_section_field", "opd.emr_section_field_option",
            "opd.emr_starter_department", "opd.emr_starter_department_section", "opd.emr_starter_layout");
    private static final String SIX_IN_SQL = "('emr_section', 'emr_section_field', 'emr_section_field_option', 'emr_starter_department', "
            + "'emr_starter_department_section', 'emr_starter_layout')";

    // ================= case 1: the static allow-list (ADR-026 D2 proof 1) =================

    /** The code of a migration with comments and string literals blanked; the bodies of dollar-quoted blocks apart. */
    private record Parsed(String code, List<String> dollarBodies) {
    }

    private static Parsed parse(String file) throws IOException {
        String sql = Files.readString(MIGRATIONS.resolve(file), StandardCharsets.UTF_8);
        StringBuilder code = new StringBuilder();
        List<String> bodies = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char ch = sql.charAt(i);
            if (ch == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (ch == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (ch == '\'') {
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    i++;
                }
                i++;
                code.append("''");
            } else if (ch == '$') {
                Matcher tag = Pattern.compile("\\G\\$([A-Za-z_]*)\\$").matcher(sql).region(i, n);
                if (tag.find()) {
                    String close = "$" + tag.group(1) + "$";
                    int end = sql.indexOf(close, tag.end());
                    int stop = end < 0 ? n : end;
                    bodies.add(strip(sql.substring(tag.end(), stop)));
                    code.append("$$");
                    i = end < 0 ? n : end + close.length();
                } else {
                    code.append(ch);
                    i++;
                }
            } else {
                code.append(ch);
                i++;
            }
        }
        return new Parsed(code.toString().toLowerCase().replaceAll("\\s+", " "), bodies);
    }

    /** A dollar-quoted body with its comments and strings blanked (the inner text is plain SQL or plpgsql). */
    private static String strip(String body) {
        return body.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ").replaceAll("'(?:[^']|'')*'", "''")
                .toLowerCase().replaceAll("\\s+", " ");
    }

    private static final Pattern NAME = Pattern.compile("[a-z_][a-z0-9_.]*");
    private static final Pattern INSERT = Pattern.compile("\\binsert into (" + NAME + ")");
    private static final Pattern DELETE = Pattern.compile("\\bdelete from (" + NAME + ")");
    private static final Pattern UPDATE = Pattern.compile("\\bupdate (" + NAME + ")(?: (?:as )?[a-z_][a-z0-9_]*)? set\\b");
    private static final Pattern TRUNCATE = Pattern.compile("(?<!before )\\btruncate (?:table )?(" + NAME + ")");
    private static final Pattern ALTER = Pattern.compile("\\balter table (?:only |if exists )*(" + NAME + ")");

    private static Set<String> targets(Pattern pattern, String code) {
        Set<String> out = new TreeSet<>();
        Matcher m = pattern.matcher(code);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static Set<String> writeTargets(Parsed p) {
        Set<String> out = new TreeSet<>();
        for (Pattern pattern : List.of(INSERT, DELETE, UPDATE, TRUNCATE)) {
            out.addAll(targets(pattern, p.code()));
        }
        return out;
    }

    @Test
    void everyWriteOfTheSixGlobalSeedMigrationsTargetsOnlyTheSixGlobalTablesAndTheTemporaryOrderTable() throws Exception {
        Set<String> allowed = new TreeSet<>(SIX);
        allowed.add("tmp_sah75_order");
        for (String file : List.of("V2026_12_14_1000__opd_emr_library_chips_and_dental_sections.sql",
                "V2026_12_14_1010__opd_emr_starter_defaults_v2.sql", "V2026_12_14_1040__opd_emr_option_hint.sql",
                "V2026_12_14_1050__opd_emr_library_gynae_menstrual_contraception.sql",
                "V2026_12_14_1060__opd_emr_starter_layout_v3_og_onco.sql")) {
            Parsed p = parse(file);
            assertThat(writeTargets(p)).as(file + " writes").isSubsetOf(allowed);
            assertThat(p.dollarBodies()).as(file + " dollar-quoted blocks only select and raise").allSatisfy(body -> {
                assertThat(body).doesNotContainPattern("\\binsert into\\b").doesNotContainPattern("\\bdelete from\\b")
                        .doesNotContainPattern("\\bupdate [a-z_.]+ set\\b").doesNotContainPattern("\\btruncate\\b");
            });
        }
    }

    @Test
    void theAlterTableStatementsOfTheGlobalSeedMigrationsNameOnlyTheFieldAndOptionTables() throws Exception {
        Set<String> fieldAndOption = Set.of("opd.emr_section_field", "opd.emr_section_field_option");
        assertThat(targets(ALTER, parse("V2026_12_14_1000__opd_emr_library_chips_and_dental_sections.sql").code())).isSubsetOf(fieldAndOption);
        assertThat(targets(ALTER, parse("V2026_12_14_1010__opd_emr_starter_defaults_v2.sql").code())).isSubsetOf(fieldAndOption);
        assertThat(targets(ALTER, parse("V2026_12_14_1040__opd_emr_option_hint.sql").code())).containsExactly("opd.emr_section_field_option");
        assertThat(targets(ALTER, parse("V2026_12_14_1050__opd_emr_library_gynae_menstrual_contraception.sql").code())).isEmpty();
        assertThat(targets(ALTER, parse("V2026_12_14_1060__opd_emr_starter_layout_v3_og_onco.sql").code())).isEmpty();
    }

    @Test
    void theTwoTenantMigrationsAndTheOptionHintMigrationHaveNoInsertUpdateDeleteOrTruncateAtAll() throws Exception {
        for (String file : List.of("V2026_12_14_1020__opd_treatment_course_actions.sql", "V2026_12_14_1030__opd_emr_cancer_journey.sql",
                "V2026_12_14_1040__opd_emr_option_hint.sql")) {
            Parsed p = parse(file);
            assertThat(writeTargets(p)).as(file + " has no write statement").isEmpty();
            assertThat(p.dollarBodies()).as(file).allSatisfy(body -> assertThat(body).doesNotContainPattern(
                    "\\b(insert into|delete from|truncate)\\b").doesNotContainPattern("\\bupdate [a-z_.]+ set\\b"));
        }
    }

    @Test
    void theSevenMigrationsWriteNoTenantTableAndTheFollowUpPairsWriteOnlyWhatTheDesignNoteSays() throws Exception {
        assertThat(writeTargets(parse("V2026_12_14_1050__opd_emr_library_gynae_menstrual_contraception.sql")))
                .containsExactlyInAnyOrder("opd.emr_section_field", "opd.emr_section_field_option");
        assertThat(writeTargets(parse("V2026_12_14_1060__opd_emr_starter_layout_v3_og_onco.sql")))
                .containsExactlyInAnyOrder("opd.emr_starter_department_section", "opd.emr_starter_layout", "opd.emr_starter_department");
    }

    // ================= case 2: the catalogue (ADR-026 D2 proof 2) =================

    @Test
    void everyForeignKeyToTheSixGlobalTablesIsNoActionOrRestrictAndTheOnlyTenantTableReferenceIsTheRecordsStarterLayout() throws Exception {
        List<List<String>> fks = globalRows("select con.conrelid::regclass::text, con.conname, con.confrelid::regclass::text, con.confupdtype::text, con.confdeltype::text "
                + "from pg_constraint con where con.contype = 'f' and con.confrelid in (select c.oid from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                + "where n.nspname = 'opd' and c.relname in " + SIX_IN_SQL + ") and con.conparentid = 0 order by 1, 2");
        assertThat(fks).as("the six tables are referenced by foreign keys").isNotEmpty();
        for (List<String> fk : fks) {
            assertThat(fk.get(3)).as(fk.get(1) + " on update").isIn("a", "r");
            assertThat(fk.get(4)).as(fk.get(1) + " on delete").isIn("a", "r");
        }
        List<List<String>> fromTenantTables = globalRows("select con.conrelid::regclass::text, con.conname, con.confrelid::regclass::text from pg_constraint con "
                + "where con.contype = 'f' and con.conparentid = 0 and con.confrelid in (select c.oid from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                + "where n.nspname = 'opd' and c.relname in " + SIX_IN_SQL + ") and exists (select 1 from pg_attribute a where a.attrelid = con.conrelid "
                + "and a.attname = 'tenant_id' and not a.attisdropped) order by 1, 2");
        assertThat(fromTenantTables).as("tenant tables that point at a global seed table").containsExactly(
                List.of("opd.emr_record", "fk_emr_record_starter_layout", "opd.emr_starter_layout"));
    }

    @Test
    void theSixGlobalTablesHaveOnlyTheTwoEnabledLayoutImmutabilityTriggersNoRulesAndTheTriggerFunctionWritesNoTable() throws Exception {
        List<List<String>> triggers = globalRows("select c.relname, t.tgname, t.tgenabled::text from pg_trigger t join pg_class c on c.oid = t.tgrelid "
                + "join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'opd' and c.relname in " + SIX_IN_SQL
                + " and not t.tgisinternal order by 2");
        assertThat(triggers).containsExactly(List.of("emr_starter_layout", "trg_emr_starter_layout_immutable", "O"),
                List.of("emr_starter_layout", "trg_emr_starter_layout_no_truncate", "O"));
        assertThat(globalCount("select count(*) from pg_rewrite r join pg_class c on c.oid = r.ev_class join pg_namespace n on n.oid = c.relnamespace "
                + "where n.nspname = 'opd' and c.relname in " + SIX_IN_SQL + " and r.rulename <> '_RETURN'")).as("no rule").isZero();
        String body = globalRows("select prosrc from pg_proc where oid = 'opd.deny_emr_immutable_change'::regproc").get(0).get(0);
        assertThat(strip(body)).doesNotContainPattern("\\binsert\\b").doesNotContainPattern("\\bupdate\\b").doesNotContainPattern("\\bdelete\\b");
    }

    // ================= case 3 and 12: the columns and their comments =================

    @Test
    void presentationAndIsAlertAreNotNullWithTheirDefaultsAndTheCheckRefusesAnUnknownPresentationAndEveryOldRowKeepsTheDefault() throws Exception {
        assertThat(globalRows("select data_type, is_nullable, column_default from information_schema.columns where table_schema = 'opd' "
                + "and table_name = 'emr_section_field' and column_name = 'presentation'").get(0))
                .satisfies(r -> {
                    assertThat(r.get(0)).isEqualTo("text");
                    assertThat(r.get(1)).isEqualTo("NO");
                    assertThat(r.get(2)).contains("'DEFAULT'");
                });
        assertThat(globalRows("select data_type, is_nullable, column_default from information_schema.columns where table_schema = 'opd' "
                + "and table_name = 'emr_section_field_option' and column_name = 'is_alert'").get(0))
                .containsExactly("boolean", "NO", "false");
        SqlError refused = db().asMigrator(c -> expectError(c, "update opd.emr_section_field set presentation = 'X' where section_key = 'local_exam'"));
        assertThat(refused.state()).isEqualTo("23514");
        assertThat(refused.message()).contains("ck_emr_section_field_presentation");
        assertThat(globalCount("select count(*) from opd.emr_section_field where presentation not in ('DEFAULT', 'CHIPS')")).isZero();
        // the 313 older fields and 136 older options: no field is CHIPS and no option is an alert outside the new dental sections,
        // except the five Gynae fields the 0.7.1 follow-up (V2026_12_14_1050) made chips on purpose
        assertThat(globalRows("select section_key || '.' || field_key from opd.emr_section_field where presentation = 'CHIPS' "
                + "and section_key not in ('local_exam', 'dental_medhx', 'dental_hx', 'dental_pain', 'dental_extraoral', 'dental_intraoral', 'dental_xray') order by 1")
                .stream().map(r -> r.get(0)).toList())
                .containsExactly("contraception.method", "menstrual.cycle", "menstrual.cycle_length", "menstrual.dysmenorrhoea", "menstrual.flow");
        assertThat(globalCount("select count(*) from opd.emr_section_field_option where is_alert and section_key not in "
                + "('dental_medhx', 'dental_hx', 'dental_intraoral')")).as("older options keep is_alert false").isZero();
        assertThat(globalCount("select count(*) from opd.emr_section_field where presentation = 'CHIPS'")).as("19 dental, 4 menstrual, 1 contraception").isEqualTo(24);
    }

    @Test
    void theNewColumnsAndTables75HasMakeCarryTheirCommentsWithTheCaseNumberOrTheNeverLoggedWarning() throws Exception {
        String field = globalRows("select col_description('opd.emr_section_field'::regclass, (select attnum from pg_attribute where attrelid = "
                + "'opd.emr_section_field'::regclass and attname = 'presentation'))").get(0).get(0);
        String alert = globalRows("select col_description('opd.emr_section_field_option'::regclass, (select attnum from pg_attribute where attrelid = "
                + "'opd.emr_section_field_option'::regclass and attname = 'is_alert'))").get(0).get(0);
        String hint = globalRows("select col_description('opd.emr_section_field_option'::regclass, (select attnum from pg_attribute where attrelid = "
                + "'opd.emr_section_field_option'::regclass and attname = 'hint'))").get(0).get(0);
        String journey = globalRows("select obj_description('opd.emr_cancer_journey'::regclass, 'pg_class')").get(0).get(0);
        assertThat(field).contains("SAH-75");
        assertThat(alert).contains("SAH-75");
        assertThat(hint).contains("SAH-75");
        assertThat(journey).contains("never logged");
        for (String column : new String[] {"missed_at", "removed_at", "done_source", "regimen_key"}) {
            String comment = globalRows("select col_description('opd.treatment_course_sitting'::regclass, (select attnum from pg_attribute where attrelid = "
                    + "'opd.treatment_course_sitting'::regclass and attname = ?))", column).get(0).get(0);
            assertThat(comment).as(column).contains("SAH-75");
        }
        assertThat(globalRows("select col_description('opd.treatment_course'::regclass, (select attnum from pg_attribute where attrelid = "
                + "'opd.treatment_course'::regclass and attname = 'estimate_signed'))").get(0).get(0)).contains("SAH-75");
        assertThat(globalRows("select col_description('opd.treatment_course_sitting'::regclass, (select attnum from pg_attribute where attrelid = 'opd.treatment_course_sitting'::regclass and attname = 'done_by_membership_id'))").get(0).get(0))
                .as("done_by_membership_id has a comment (it names no case number)").isNotBlank();
    }

    // ================= case 4: the library =================

    private Map<String, Integer> fieldCounts() throws SQLException {
        Map<String, Integer> out = new LinkedHashMap<>();
        globalRows("select f.section_key || '.' || f.field_key, count(o.option_key)::text from opd.emr_section_field f left join opd.emr_section_field_option o "
                + "on o.section_key = f.section_key and o.field_key = f.field_key where f.section_key in "
                + "('local_exam', 'dental_medhx', 'dental_hx', 'dental_pain', 'dental_extraoral', 'dental_intraoral', 'dental_xray') "
                + "group by f.section_key, f.field_key, f.position order by f.section_key, f.position")
                .forEach(r -> out.put(r.get(0), Integer.parseInt(r.get(1))));
        return out;
    }

    @Test
    void theSevenNewLibrarySectionsHaveTheFieldsAndOptionCountsOfTheDesignNoteAndTheLibraryTotalsAreOneHundredSevenThreeThirtyFiveTwoFortyFour() throws Exception {
        assertThat(globalRows("select section_key, kind, position::text from opd.emr_section where section_key in "
                + "('local_exam', 'dental_medhx', 'dental_hx', 'dental_pain', 'dental_extraoral', 'dental_intraoral', 'dental_xray') order by position").stream()
                .map(r -> r.get(0) + "/" + r.get(1) + "/" + r.get(2)).toList())
                .containsExactly("local_exam/SPECIALTY/101", "dental_medhx/SPECIALTY/102", "dental_hx/SPECIALTY/103", "dental_pain/SPECIALTY/104",
                        "dental_extraoral/SPECIALTY/105", "dental_intraoral/SPECIALTY/106", "dental_xray/SPECIALTY/107");
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("local_exam.body_map", 0);
        expected.put("dental_medhx.conditions", 9);
        expected.put("dental_medhx.medicines", 6);
        expected.put("dental_medhx.allergies", 5);
        expected.put("dental_hx.last_dental_visit", 4);
        expected.put("dental_hx.brushing", 3);
        expected.put("dental_hx.habits", 7);
        expected.put("dental_pain.type", 7);
        expected.put("dental_pain.score", 11);
        expected.put("dental_pain.since", 4);
        expected.put("dental_extraoral.face", 2);
        expected.put("dental_extraoral.tmj", 4);
        expected.put("dental_extraoral.lymph_nodes", 3);
        expected.put("dental_extraoral.mouth_opening", 2);
        expected.put("dental_intraoral.oral_hygiene", 3);
        expected.put("dental_intraoral.gingiva", 4);
        expected.put("dental_intraoral.calculus", 4);
        expected.put("dental_intraoral.occlusion", 3);
        expected.put("dental_intraoral.soft_tissue", 5);
        expected.put("dental_xray.images", 5);
        assertThat(fieldCounts()).isEqualTo(expected);
        assertThat(globalRows("select count(*)::text from opd.emr_section_field_option where section_key in ('dental_medhx', 'dental_hx', 'dental_pain', "
                + "'dental_extraoral', 'dental_intraoral', 'dental_xray')").get(0).get(0)).isEqualTo("91");
        assertThat(globalCount("select count(*) from opd.emr_section")).isEqualTo(107);
        assertThat(globalCount("select count(*) from opd.emr_section_field")).isEqualTo(335);
        assertThat(globalCount("select count(*) from opd.emr_section_field_option")).isEqualTo(244);
        assertThat(globalCount("select count(*) from opd.emr_section_field where field_key = 'specialty'")).as("reserved key").isZero();
        assertThat(globalRows("select option_key from opd.emr_section_field_option where section_key = 'dental_pain' and field_key = 'score' order by position")
                .stream().map(r -> r.get(0)).toList()).containsExactly("o0", "o1", "o2", "o3", "o4", "o5", "o6", "o7", "o8", "o9", "o10");
        assertThat(globalRows("select option_key from opd.emr_section_field_option where section_key = 'dental_intraoral' and field_key = 'calculus' order by position")
                .stream().map(r -> r.get(0)).toList()).containsExactly("none", "plus_1", "plus_2", "plus_3");
        assertThat(globalRows("select option_key from opd.emr_section_field_option where section_key = 'dental_xray' and field_key = 'images' order by position")
                .stream().map(r -> r.get(0)).toList()).containsExactly("iopa", "opg", "bitewing", "cbct", "intra_oral_photos");
    }

    @Test
    void everyDentalChipFieldIsAChipsFieldWithFieldOptionsAndNoFreeTextAndTheNoneAndAlertOptionsAreExactlyThoseOfTheDesignNote() throws Exception {
        List<String> chipFields = List.of("dental_medhx.conditions", "dental_medhx.medicines", "dental_medhx.allergies", "dental_hx.habits", "dental_pain.type",
                "dental_intraoral.soft_tissue", "dental_xray.images");
        for (String key : chipFields) {
            String[] k = key.split("\\.");
            assertThat(globalRows("select presentation, options_source, allow_free_text::text from opd.emr_section_field where section_key = ? and field_key = ?", k[0], k[1]).get(0))
                    .as(key).containsExactly("CHIPS", "FIELD", "false");
        }
        for (String f : new String[] {"conditions", "medicines", "allergies"}) {
            assertThat(globalCount("select count(*) from opd.emr_section_field_option where section_key = 'dental_medhx' and field_key = ? and is_none", f)).as(f).isEqualTo(1);
            assertThat(globalCount("select count(*) from opd.emr_section_field_option where section_key = 'dental_medhx' and field_key = ? and not is_none and not is_alert", f))
                    .as(f + " every other option is an alert").isZero();
        }
        assertThat(globalRows("select option_key from opd.emr_section_field_option where is_none and section_key = 'dental_intraoral' and field_key = 'soft_tissue'")
                .stream().map(r -> r.get(0)).toList()).containsExactly("normal");
        assertThat(globalCount("select count(*) from opd.emr_section_field_option where section_key in ('dental_hx', 'dental_pain', 'dental_extraoral', 'dental_xray') and is_none"))
                .as("no none option outside the medical history and soft tissue").isZero();
        assertThat(globalRows("select section_key || '.' || field_key || '.' || option_key from opd.emr_section_field_option where is_alert and section_key = 'dental_intraoral' order by 1")
                .stream().map(r -> r.get(0)).toList()).containsExactly("dental_intraoral.soft_tissue.red_patch", "dental_intraoral.soft_tissue.tongue_coating",
                "dental_intraoral.soft_tissue.ulcer", "dental_intraoral.soft_tissue.white_patch");
        assertThat(globalCount("select count(*) from opd.emr_section_field_option where section_key = 'dental_hx' and field_key = 'habits' and is_alert")).isEqualTo(7);
        assertThat(globalCount("select count(*) from opd.emr_section_field_option where is_alert")).as("17 medical, 7 habits, 4 soft tissue")
                .isEqualTo(17 + 7 + 4);
        assertThat(globalCount("select count(*) from opd.emr_section_field_option where is_alert and section_key = 'dental_medhx'")).isEqualTo(17);
    }

    @Test
    void theAppCannotInsertUpdateOrDeleteTheThreeLibraryTables() throws Exception {
        for (String table : new String[] {"emr_section", "emr_section_field", "emr_section_field_option"}) {
            for (String sql : new String[] {"insert into opd." + table + " default values", "update opd." + table + " set created_at = created_at",
                    "delete from opd." + table}) {
                assertThat(db().asApp(c -> expectError(c, sql)).state()).as(sql).isEqualTo("42501");
            }
        }
    }

    // ================= case 13: the option hint =================

    @Test
    void theOptionHintCheckRefusesEmptyAndSixtyOneCharactersAcceptsNullOneAndSixtyAndOnlyTheSixContraceptionOptionsHaveAHint() throws Exception {
        String insert = "insert into opd.emr_section_field_option (section_key, field_key, option_key, position, label, hint) values ('contraception', 'method', 'zz_probe', 99, 'Probe', ?)";
        db().asMigrator(c -> {
            SqlError empty = expectError(c, insert, "");
            assertThat(empty.state()).isEqualTo("23514");
            assertThat(empty.message()).contains("ck_emr_section_field_option_hint");
            assertThat(expectError(c, insert, "h".repeat(61)).state()).isEqualTo("23514");
            expectOk(c, insert, "h".repeat(60));
            expectOk(c, insert.replace("'zz_probe'", "'zz_probe2'").replace("99", "98"), "h");
            expectOk(c, insert.replace("'zz_probe'", "'zz_probe3'").replace("99", "97"), (Object) null);
            return null;
        });
        assertThat(globalRows("select option_key, hint from opd.emr_section_field_option where hint is not null order by section_key, field_key, position"))
                .containsExactly(List.of("none", "Not using"), List.of("condoms", "Barrier"), List.of("ocp", "Daily pill"), List.of("cu_t", "IUCD, 5–10 years"),
                        List.of("injectable", "Every 3 months"), List.of("sterilisation", "Permanent"));
        assertThat(globalCount("select count(*) from opd.emr_section_field_option where hint is not null and not (section_key = 'contraception' and field_key = 'method')")).isZero();
        for (String sql : new String[] {"insert into opd.emr_section_field_option (section_key) values ('x')", "update opd.emr_section_field_option set hint = 'x'"}) {
            assertThat(db().asApp(c -> expectError(c, sql)).state()).as(sql).isEqualTo("42501");
        }
    }

    // ================= Gynae library (case 14, in full) =================

    @Test
    void theGynaeMenstrualAndContraceptionFieldsAreTheSevenPointTwoTableInOrderWithTheirTypesPresentationAndOptions() throws Exception {
        assertThat(globalRows("select field_key, label, field_type, presentation, required::text from opd.emr_section_field where section_key = 'menstrual' order by position"))
                .containsExactly(List.of("lmp", "LMP", "DATE", "DEFAULT", "true"), List.of("cycle", "Cycle", "SINGLE_CHOICE", "CHIPS", "false"),
                        List.of("cycle_length", "Cycle length", "SINGLE_CHOICE", "CHIPS", "false"), List.of("flow", "Flow", "SINGLE_CHOICE", "CHIPS", "false"),
                        List.of("dysmenorrhoea", "Pain", "SINGLE_CHOICE", "CHIPS", "false"), List.of("age_at_menarche", "Age at menarche", "NUMBER", "DEFAULT", "false"));
        Map<String, List<String>> options = new TreeMap<>();
        options.put("cycle", List.of("regular", "irregular"));
        options.put("cycle_length", List.of("under_21_d", "o21_27_d", "o28_30_d", "o31_35_d", "over_35_d"));
        options.put("flow", List.of("scanty", "normal", "heavy", "with_clots"));
        options.put("dysmenorrhoea", List.of("none", "mild", "severe"));
        for (Map.Entry<String, List<String>> e : options.entrySet()) {
            assertThat(globalRows("select option_key from opd.emr_section_field_option where section_key = 'menstrual' and field_key = ? order by position", e.getKey())
                    .stream().map(r -> r.get(0)).toList()).as(e.getKey()).isEqualTo(e.getValue());
            assertThat(globalRows("select options_source, coalesce(options_master, 'null'), allow_free_text::text from opd.emr_section_field where section_key = 'menstrual' and field_key = ?", e.getKey()).get(0))
                    .as(e.getKey()).containsExactly("FIELD", "null", "false");
        }
        assertThat(globalRows("select label from opd.emr_section_field_option where section_key = 'menstrual' and field_key = 'cycle_length' order by position").stream().map(r -> r.get(0)).toList())
                .containsExactly("< 21 d", "21–27 d", "28–30 d", "31–35 d", "> 35 d");
        assertThat(globalRows("select field_key from opd.emr_section_field where section_key = 'contraception' order by position").stream().map(r -> r.get(0)).toList())
                .containsExactly("method", "findings", "template", "attachments");
        assertThat(globalRows("select option_key from opd.emr_section_field_option where section_key = 'contraception' and field_key = 'method' order by position").stream().map(r -> r.get(0)).toList())
                .containsExactly("none", "condoms", "ocp", "cu_t", "injectable", "sterilisation");
        assertThat(globalCount("select count(*) from opd.emr_section_field_option where section_key in ('menstrual', 'contraception') and (is_none or is_alert)")).isZero();
    }

    // ================= case 6 and 15: the starter layout documents =================

    @Test
    void thirtyTwoLayoutRowsHoldFifteenVersionOneFifteenVersionTwoAndTwoVersionThreeAndNoOlderDocumentKnowsPresentationAlertOrHint() throws Exception {
        assertThat(globalRows("select layout_version::text, count(*)::text from opd.emr_starter_layout group by layout_version order by layout_version"))
                .containsExactly(List.of("1", "15"), List.of("2", "15"), List.of("3", "2"));
        assertThat(globalRows("select starter_key, current_layout_version::text from opd.emr_starter_department order by starter_key").stream()
                .map(r -> r.get(0) + "=" + r.get(1)).toList()).allSatisfy(s -> assertThat(s).isEqualTo(
                (s.startsWith("og=") || s.startsWith("onco=")) ? s.substring(0, s.indexOf('=') + 1) + "3" : s.substring(0, s.indexOf('=') + 1) + "2"));
        assertThat(globalCount("select count(*) from opd.emr_starter_layout where layout_version = 1 and (layout::text like '%\"presentation\"%' or layout::text like '%\"alert\"%' "
                + "or layout::text like '%\"hint\"%')")).as("version 1 has none of the new keys").isZero();
        assertThat(globalCount("select count(*) from opd.emr_starter_layout where layout_version = 2 and layout::text like '%\"hint\"%'")).as("version 2 has no hint").isZero();
        assertThat(globalCount("select count(*) from opd.emr_starter_layout where layout_version = 2 and layout::text not like '%\"presentation\"%'")).as("version 2 carries presentation").isZero();
        assertThat(globalCount("select count(*) from opd.emr_starter_layout where layout_version = 3 and starter_key = 'onco' and layout::text like '%\"hint\"%'")).as("onco v3 has no hint").isZero();
        assertThat(globalRows("select count(*)::text from jsonb_array_elements((select layout from opd.emr_starter_layout where starter_key = 'og' and layout_version = 3) -> 'sections') s, "
                + "jsonb_array_elements(s -> 'fields') f, jsonb_array_elements(coalesce(f -> 'options', '[]'::jsonb)) o where jsonb_exists(o, 'hint')").get(0).get(0))
                .as("og v3 holds six hinted options").isEqualTo("6");
        assertThat(globalCount("select count(*) from opd.emr_starter_layout where layout_version = 1 and starter_key in ('gm', 'dental', 'og', 'surgery', 'onco') and "
                + "layout::text like '%\"local_exam\"%'")).as("version 1 never had local_exam").isZero();
    }

    @Test
    void theStarterLayoutDocumentsOfVersionOneAndTwoAreByteForByteTheOnesPinnedHereAndTheCurrentLayoutListsTheSectionsInPlacementOrder() throws Exception {
        Map<String, String> actual = new TreeMap<>();
        globalRows("select starter_key || '/v' || layout_version, md5(layout::text) from opd.emr_starter_layout where layout_version in (1, 2) order by 1")
                .forEach(r -> actual.put(r.get(0), r.get(1)));
        assertThat(actual).as("pinned md5 of 30 layout documents (copy of the migrated state; rows are immutable by trigger)").isEqualTo(Sah75LayoutHashes.V1_V2);
        // the sections array of each current layout equals the starter's placements in position order, and so does version 2 of every starter but onco
        for (String starter : STARTER_KEYS) {
            String placements = globalRows("select string_agg(section_key, ',' order by position) from opd.emr_starter_department_section where starter_key = ?", starter).get(0).get(0);
            String current = globalRows("select string_agg(s ->> 'key', ',' order by ord) from opd.emr_starter_layout l, jsonb_array_elements(l.layout -> 'sections') with ordinality as t(s, ord) "
                    + "where l.starter_key = ? and l.layout_version = (select current_layout_version from opd.emr_starter_department where starter_key = ?)", starter, starter).get(0).get(0);
            assertThat(current).as(starter + " current layout").isEqualTo(placements);
            if (!"onco".equals(starter)) {
                String v2 = globalRows("select string_agg(s ->> 'key', ',' order by ord) from opd.emr_starter_layout l, jsonb_array_elements(l.layout -> 'sections') with ordinality as t(s, ord) "
                        + "where l.starter_key = ? and l.layout_version = 2", starter).get(0).get(0);
                assertThat(v2).as(starter + " v2").isEqualTo(placements);
            }
        }
    }

    @Test
    void theOncoVersionThreeOnlyReordersTheFourCardsAndOgVersionThreeOnlyChangesMenstrualAndContraception() throws Exception {
        String onco2 = globalRows("select string_agg(s ->> 'key', ',' order by ord) from opd.emr_starter_layout l, jsonb_array_elements(l.layout -> 'sections') with ordinality as t(s, ord) "
                + "where l.starter_key = 'onco' and l.layout_version = 2").get(0).get(0);
        String onco3 = globalRows("select string_agg(s ->> 'key', ',' order by ord) from opd.emr_starter_layout l, jsonb_array_elements(l.layout -> 'sections') with ordinality as t(s, ord) "
                + "where l.starter_key = 'onco' and l.layout_version = 3").get(0).get(0);
        assertThat(onco2).contains("onc_ecog,onc_tox,onc_bsa,onc_labs");
        assertThat(onco3).contains("onc_tox,onc_bsa,onc_labs,onc_ecog");
        assertThat(globalRows("select s ->> 'key', ord::text from jsonb_array_elements((select layout from opd.emr_starter_layout where starter_key = 'onco' and layout_version = 3) -> 'sections') "
                + "with ordinality as t(s, ord) where s ->> 'key' in ('onc_tox', 'onc_bsa', 'onc_labs', 'onc_ecog') order by ord"))
                .containsExactly(List.of("onc_tox", "17"), List.of("onc_bsa", "18"), List.of("onc_labs", "19"), List.of("onc_ecog", "20"));
        // every other onco section is equal between version 2 and version 3 (same position, same document)
        assertThat(globalCount("select count(*) from opd.emr_starter_layout a join opd.emr_starter_layout b on a.starter_key = b.starter_key and a.layout_version = 2 and b.layout_version = 3 "
                + "where a.starter_key = 'onco' and (select jsonb_agg(s order by s ->> 'key') from jsonb_array_elements(a.layout -> 'sections') s where s ->> 'key' not in "
                + "('onc_tox', 'onc_bsa', 'onc_labs', 'onc_ecog')) = (select jsonb_agg(s order by s ->> 'key') from jsonb_array_elements(b.layout -> 'sections') s where s ->> 'key' not in "
                + "('onc_tox', 'onc_bsa', 'onc_labs', 'onc_ecog'))")).isEqualTo(1);
        assertThat(globalRows("select s ->> 'key' from jsonb_array_elements((select layout from opd.emr_starter_layout where starter_key = 'og' and layout_version = 3) -> 'sections') s "
                + "where s -> 'fields' @> '[{\"key\": \"method\"}]'::jsonb").stream().map(r -> r.get(0)).toList()).containsExactly("contraception");
        assertThat(globalRows("select string_agg(f ->> 'key', ',' order by ord) from jsonb_array_elements((select layout from opd.emr_starter_layout where starter_key = 'og' and layout_version = 3) -> 'sections') s, "
                + "jsonb_array_elements(s -> 'fields') with ordinality as t(f, ord) where s ->> 'key' = 'menstrual'").get(0).get(0)).isEqualTo("lmp,cycle,cycle_length,flow,dysmenorrhoea,age_at_menarche");
    }
}
