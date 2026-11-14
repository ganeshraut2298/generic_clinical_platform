package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The stored shape of the SAH-49, 60 to 66 and 69 to 71 reference data and the visit template table, by SQL on the real
 * {@code sahaj_test} database as {@code sahaj_app} (cases S-80 to S-88, S-100 and S-101 of docs/database/SAH-49-71.md): the six
 * global reference tables (counts, immutability, no clinic column, SELECT only, checks), the reserved field key, the new
 * record column {@code screen_state}, the visit template table and the seeded medicines. The chemotherapy order table has its own
 * class, {@link ChemoOrderTableIT}. Writes that must be accepted prove that a constraint, not a missing fixture, is the
 * reason for a refusal; they run in a transaction that is always rolled back. Every seeded value is "value from the EMR
 * mockup, accepted by the human in DEC-039, not clinically reviewed". Test plan: docs/test-plans/SAH-49-71.md.
 */
@OnTestDatabase
class EmrPhase4TablesIT extends EmrPhase3Support {

    private static final String[] GLOBAL_TABLES = {"emr_immunization_group", "emr_immunization_vaccine", "emr_growth_band", "emr_milestone",
            "emr_joint_item", "emr_chemo_protocol"};
    private static final String ITEMS = "'{\"schemaVersion\":1,\"complaints\":[]}'::jsonb";

    private String stateIn(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, "savepoint probe");
            try {
                DbAccess.execute(c, sql, params);
            } catch (SQLException e) {
                DbAccess.execute(c, "rollback to savepoint probe");
                return e.getSQLState();
            }
            DbAccess.execute(c, "rollback to savepoint probe");
            return "ACCEPTED";
        });
    }

    private String migratorState(String sql, Object... params) throws SQLException {
        return db().asMigrator(c -> {
            DbAccess.execute(c, "savepoint probe");
            try {
                DbAccess.execute(c, sql, params);
            } catch (SQLException e) {
                DbAccess.execute(c, "rollback to savepoint probe");
                return e.getSQLState();
            }
            DbAccess.execute(c, "rollback to savepoint probe");
            return "ACCEPTED";
        });
    }

    // ================= S-80 · the seeds =================

    @Test
    void theImmunisationScheduleHasNineGroupsInTheDrawnOrderWithTheirWindowsAndThirtyVaccinesThreeSixFiveFiveThreeOneOneThreeThree() throws Exception {
        assertThat(globalRows("select group_key from opd.emr_immunization_group order by position").stream().map(r -> r.get(0)).toList())
                .containsExactly("birth", "week_6", "week_10", "week_14", "month_6_9", "month_9", "month_12", "month_15", "month_16_18");
        assertThat(globalRows("select group_key, window_from_value::text, window_from_unit, window_to_value::text, window_to_unit from opd.emr_immunization_group order by position"))
                .containsExactly(List.of("birth", "0", "DAYS", "6", "WEEKS"), List.of("week_6", "6", "WEEKS", "10", "WEEKS"),
                        List.of("week_10", "10", "WEEKS", "14", "WEEKS"), List.of("week_14", "14", "WEEKS", "6", "MONTHS"),
                        List.of("month_6_9", "6", "MONTHS", "9", "MONTHS"), List.of("month_9", "9", "MONTHS", "12", "MONTHS"),
                        List.of("month_12", "12", "MONTHS", "15", "MONTHS"), List.of("month_15", "15", "MONTHS", "16", "MONTHS"),
                        List.of("month_16_18", "16", "MONTHS", "19", "MONTHS"));
        assertThat(globalCount("select count(*) from opd.emr_immunization_vaccine")).isEqualTo(30);
        assertThat(globalRows("select g.group_key, count(v.vaccine_key)::text from opd.emr_immunization_group g left join opd.emr_immunization_vaccine v "
                + "on v.group_key = g.group_key group by g.group_key, g.position order by g.position").stream().map(r -> r.get(1)).toList())
                .containsExactly("3", "6", "5", "5", "3", "1", "1", "3", "3");
        assertThat(globalCount("select count(*) from opd.emr_immunization_vaccine where vaccine_key in ('bcg', 'opv_0', 'hep_b_1', 'dtwp_1', 'typhoid_conj', 'dtwp_booster_1', 'varicella_1')"))
                .isEqualTo(7);
        assertThat(globalRows("select vaccine_key from opd.emr_immunization_vaccine where group_key = 'month_15' order by position").stream().map(r -> r.get(0)).toList())
                .as("Varicella 1 sits in the group that ends before 18 months, so it is overdue for the 18-month child as drawn").contains("varicella_1");
    }

    @Test
    void theGrowthBandsAreTheFiveWeightRowsOfEighteenMonthsOnlyAndNeverALengthOrHeadCircumferenceRowBecauseOfH8() throws Exception {
        assertThat(globalCount("select count(*) from opd.emr_growth_band")).isEqualTo(5);
        assertThat(globalCount("select count(*) from opd.emr_growth_band where measure = 'WEIGHT' and sex is null and age_months_from = 18 and age_months_to = 18")).isEqualTo(5);
        assertThat(globalCount("select count(*) from opd.emr_growth_band where measure in ('LENGTH', 'HEAD_CIRC')"))
                .as("DEC-044 H8: the centile notes for length and head circumference are hidden until real WHO tables exist, so no row is seeded").isZero();
        assertThat(globalRows("select label, coalesce(upper_exclusive::text, 'none'), alert::text from opd.emr_growth_band order by position"))
                .containsExactly(List.of("Below 3rd centile", "8.6", "true"), List.of("~15th centile", "9.8", "false"), List.of("~25–50th centile", "11", "false"),
                        List.of("~50–97th centile", "13.7", "false"), List.of("Above 97th centile", "none", "true"));
    }

    @Test
    void theMilestonesJointItemsAndChemotherapyProtocolsAreSeededAsDrawn() throws Exception {
        assertThat(globalRows("select milestone_key, domain from opd.emr_milestone where age_band_months = 18 order by position"))
                .containsExactly(List.of("walks_alone", "GROSS_MOTOR"), List.of("climbs_stairs_with_help", "GROSS_MOTOR"), List.of("scribbles_with_crayon", "FINE_MOTOR"),
                        List.of("feeds_self_with_spoon", "SOCIAL"), List.of("points_to_show_interest", "SOCIAL"), List.of("says_6_or_more_words", "LANGUAGE"));
        assertThat(globalCount("select count(*) from opd.emr_milestone")).isEqualTo(6);
        assertThat(globalRows("select kind, count(*)::text from opd.emr_joint_item where joint_key = 'knee' group by kind order by kind"))
                .containsExactly(List.of("MMT", "2"), List.of("ROM", "3"), List.of("SPECIAL_TEST", "6"));
        assertThat(globalCount("select count(*) from opd.emr_joint_item")).isEqualTo(11);
        assertThat(globalRows("select item_key, normal_from_deg::text, normal_to_deg::text from opd.emr_joint_item where kind = 'ROM' order by position"))
                .containsExactly(List.of("flexion", "0", "135"), List.of("extension", "0", "0"), List.of("flexion_passive", "0", "135"));
        assertThat(globalRows("select protocol_key, interval_days::text, emetic_risk, suggested_on_change::text, position::text from opd.emr_chemo_protocol order by position"))
                .containsExactly(List.of("AC", "21", "HIGH", "false", "1"), List.of("PACLI_3W", "21", "LOW", "true", "2"),
                        List.of("PACLI_WEEKLY", "7", "LOW", "false", "3"), List.of("TC", "21", "MODERATE", "false", "4"));
        assertThat(globalRows("select jsonb_path_query_first(definition, '$.drugs[*] ? (@.drugKey == \"doxorubicin\").dosePerM2')::text, "
                + "jsonb_path_query_first(definition, '$.drugs[*] ? (@.drugKey == \"doxorubicin\").vialStepMg')::text from opd.emr_chemo_protocol where protocol_key = 'AC'").get(0))
                .containsExactly("60", "5");
        assertThat(globalCount("select count(*) from opd.emr_chemo_protocol where jsonb_path_exists(definition, '$.drugs[*] ? (@.vesicant == true)')"))
                .as("only AC holds a vesicant (doxorubicin), as drawn").isEqualTo(1);
        assertThat(globalCount("select count(*) from opd.emr_chemo_protocol where jsonb_path_exists(definition, '$.drugs[*] ? (@.anthracycline == true)')")).isEqualTo(1);
    }

    @Test
    void theTenSeededMedicinesHaveNoAllergyClassAndNoSavedPrescriptionDefaultsAndTheOlderRowsAreUntouched() throws Exception {
        String[] names = {"Paracetamol syrup 120 mg / 5 ml", "Paracetamol syrup 250 mg / 5 ml", "Ibuprofen syrup 100 mg / 5 ml", "Ferrous ascorbate 100 mg",
                "Calcium 500 mg + Vitamin D3", "Ondansetron 8 mg", "Dexamethasone 4 mg", "Pegfilgrastim 6 mg", "Benzydamine mouthwash", "Loperamide 2 mg"};
        for (String name : names) {
            List<String> row = globalRows("select id::text, allergy_class, default_frequency, default_days::text, default_when from opd.medicine where name = ?", name).get(0);
            assertThat(row.get(0)).as(name + " id = md5 of the lower-case name").isEqualTo(
                    globalRows("select md5('opd.medicine:' || lower(?))::uuid::text", name).get(0).get(0));
            assertThat(row.subList(1, 5)).as(name).containsExactly(null, null, null, null);
        }
        assertThat(globalRows("select form from opd.medicine where name = 'Loperamide 2 mg'").get(0).get(0)).isEqualTo("Capsule");
        assertThat(globalCount("select count(*) from opd.medicine")).isEqualTo(63);
        assertThat(globalCount("select count(*) from opd.medicine where allergy_class is not null")).as("unchanged by this migration").isEqualTo(4);
        assertThat(globalCount("select count(*) from opd.medicine where default_frequency is not null")).as("unchanged by this migration").isEqualTo(7);
        assertThat(globalCount("select count(*) from opd.medicine where name in ('Folic acid 5 mg', 'Pantoprazole 40 mg')")).as("left as they were").isEqualTo(2);
    }

    // ================= S-81, S-82 · SELECT only, no clinic, immutable protocols =================

    @Test
    void theSixGlobalTablesAreSelectOnlyForTheAppHaveNoClinicColumnNoRowLevelSecurityAndNothingForPublic() throws Exception {
        for (String table : GLOBAL_TABLES) {
            for (String sql : new String[] {"insert into opd." + table + " default values", "update opd." + table + " set created_at = created_at",
                    "delete from opd." + table, "truncate opd." + table}) {
                SqlError err = db().asApp(c -> expectError(c, sql));
                assertThat(err.state()).as(sql).isEqualTo("42501");
            }
            assertThat(globalRows("select has_table_privilege('sahaj_app', 'opd." + table + "', 'SELECT')").get(0).get(0)).as(table).isEqualTo("t");
            for (String privilege : new String[] {"INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
                assertThat(globalRows("select has_table_privilege('sahaj_app', 'opd." + table + "', '" + privilege + "')").get(0).get(0)).as(table + " " + privilege).isEqualTo("f");
            }
            assertThat(globalCount("select count(*) from information_schema.columns where table_schema = 'opd' and table_name = '" + table + "' and column_name = 'tenant_id'"))
                    .as(table + " has no clinic column").isZero();
            assertThat(globalCount("select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'opd' and c.relname = '" + table + "' and c.relrowsecurity"))
                    .as(table + " has no row level security").isZero();
            assertThat(globalCount("select count(*) from information_schema.role_table_grants where table_schema = 'opd' and table_name = '" + table + "' and grantee = 'PUBLIC'")).isZero();
            assertThat(globalRows("select obj_description('opd." + table + "'::regclass, 'pg_class')").get(0).get(0)).as(table + " carries the tag").contains("not clinically reviewed");
            assertThat(globalCount("select count(*) from opd." + table)).as("readable with no clinic bound").isPositive();
        }
    }

    @Test
    void theChemotherapyProtocolsCannotBeUpdatedDeletedOrTruncatedByAnyRoleAndANewKeyCanBeAdded() throws Exception {
        db().asMigrator(c -> {
            assertThat(expectError(c, "update opd.emr_chemo_protocol set label = 'Changed' where protocol_key = 'AC'").message()).contains("immutable");
            assertThat(expectError(c, "delete from opd.emr_chemo_protocol where protocol_key = 'AC'").message()).contains("immutable");
            assertThat(expectError(c, "truncate opd.emr_chemo_protocol").message()).contains("immutable");
            expectOk(c, "insert into opd.emr_chemo_protocol (protocol_key, label, sub_line, interval_days, emetic_risk, position, definition) values "
                    + "('QA_PROBE', 'Probe', 'Probe line', 14, 'LOW', 99, '{\"schemaVersion\":1,\"drugs\":[],\"premeds\":[]}'::jsonb)");
            assertThat(expectError(c, "update opd.emr_chemo_protocol set label = 'Changed' where protocol_key = 'QA_PROBE'").message())
                    .as("a new row is immutable from the moment it exists").contains("immutable");
            return null;
        });
        SqlError app = db().asApp(c -> expectError(c, "update opd.emr_chemo_protocol set label = 'Changed' where protocol_key = 'AC'"));
        assertThat(app.state()).isEqualTo("42501");
        assertThat(globalRows("select label from opd.emr_chemo_protocol where protocol_key = 'AC'").get(0).get(0)).isEqualTo("AC");
        assertThat(globalCount("select count(*) from opd.emr_chemo_protocol")).as("the probe was rolled back").isEqualTo(4);
    }

    // ================= S-83 to S-86 · checks of the reference tables =================

    @Test
    void anImmunisationWindowMustStartBeforeItEndsInDaysAndUseKnownUnitsAndNoNegativeStart() throws Exception {
        String insert = "insert into opd.emr_immunization_group (group_key, label, position, window_from_value, window_from_unit, window_to_value, window_to_unit) values ('qa_group', 'Probe', 99, %s)";
        assertThat(migratorState(String.format(insert, "10, 'WEEKS', 2, 'MONTHS'"))).as("10 weeks = 70 days is not before 2 months = 60 days").isEqualTo("23514");
        assertThat(migratorState(String.format(insert, "6, 'WEEKS', 6, 'WEEKS'"))).as("equal ends").isEqualTo("23514");
        assertThat(migratorState(String.format(insert, "-1, 'DAYS', 6, 'WEEKS'"))).isEqualTo("23514");
        assertThat(migratorState(String.format(insert, "0, 'YEARS', 6, 'WEEKS'"))).isEqualTo("23514");
        assertThat(migratorState(String.format(insert, "0, 'DAYS', 6, 'YEARS'"))).isEqualTo("23514");
        assertThat(migratorState(String.format(insert, "0, 'DAYS', 0, 'DAYS'"))).isEqualTo("23514");
        assertThat(migratorState(String.format(insert, "0, 'DAYS', 6, 'WEEKS'"))).isEqualTo("ACCEPTED");
        assertThat(migratorState("insert into opd.emr_immunization_group (group_key, label, position, window_from_value, window_from_unit, window_to_value, window_to_unit) "
                + "values ('qa_group', 'Probe', 1, 0, 'DAYS', 6, 'WEEKS')")).as("position is unique").isEqualTo("23505");
    }

    @Test
    void aVaccineNeedsAKnownGroupAUniquePlaceInItAndALowerCaseKey() throws Exception {
        String insert = "insert into opd.emr_immunization_vaccine (vaccine_key, group_key, label, position) values (%s)";
        assertThat(migratorState(String.format(insert, "'qa_vaccine', 'no_such_group', 'Probe', 1"))).isEqualTo("23503");
        assertThat(migratorState(String.format(insert, "'qa_vaccine', 'birth', 'Probe', 1"))).as("duplicate (group, position)").isEqualTo("23505");
        assertThat(migratorState(String.format(insert, "'bcg', 'birth', 'Probe', 50"))).as("duplicate key").isEqualTo("23505");
        assertThat(migratorState(String.format(insert, "'Bcg2', 'birth', 'Probe', 50"))).as("upper case").isEqualTo("23514");
        assertThat(migratorState(String.format(insert, "'1bcg', 'birth', 'Probe', 50"))).isEqualTo("23514");
        assertThat(migratorState(String.format(insert, "'qa_vaccine', 'birth', 'Probe', 50"))).isEqualTo("ACCEPTED");
    }

    @Test
    void aGrowthBandIsUniquePerMeasureSexAgeAndPositionEvenWithANullSexAndKeepsItsAgeOrderMeasureAndUpperLimit() throws Exception {
        String insert = "insert into opd.emr_growth_band (measure, sex, age_months_from, age_months_to, upper_exclusive, label, position) values (%s)";
        assertThat(migratorState(String.format(insert, "'WEIGHT', null, 18, 18, 9, 'Probe', 1"))).as("nulls are not distinct").isEqualTo("23505");
        assertThat(migratorState(String.format(insert, "'WEIGHT', 'F', 18, 18, 9, 'Probe', 1"))).as("another sex is another row").isEqualTo("ACCEPTED");
        assertThat(migratorState(String.format(insert, "'WEIGHT', null, 20, 19, 9, 'Probe', 1"))).as("to before from").isEqualTo("23514");
        assertThat(migratorState(String.format(insert, "'BMI', null, 18, 18, 9, 'Probe', 9"))).isEqualTo("23514");
        assertThat(migratorState(String.format(insert, "'WEIGHT', null, 18, 18, 0, 'Probe', 9"))).as("upper 0").isEqualTo("23514");
        assertThat(migratorState(String.format(insert, "'WEIGHT', null, 18, 300, 5, 'Probe', 9"))).as("age over 228").isEqualTo("23514");
        assertThat(migratorState(String.format(insert, "'LENGTH', null, 18, 18, 80, 'Probe', 1"))).as("the table accepts length rows for a later WHO migration").isEqualTo("ACCEPTED");
    }

    @Test
    void aRomJointItemNeedsBothNormalsInOrderAndOtherKindsHaveNone() throws Exception {
        String insert = "insert into opd.emr_joint_item (joint_key, kind, item_key, label, normal_from_deg, normal_to_deg, position) values (%s)";
        assertThat(migratorState(String.format(insert, "'knee', 'ROM', 'qa_item', 'Probe', null, null, 50"))).as("ROM without normals").isEqualTo("23514");
        assertThat(migratorState(String.format(insert, "'knee', 'ROM', 'qa_item', 'Probe', 90, 10, 50"))).as("from above to").isEqualTo("23514");
        assertThat(migratorState(String.format(insert, "'knee', 'MMT', 'qa_item', 'Probe', 0, 5, 50"))).as("MMT with normals").isEqualTo("23514");
        assertThat(migratorState(String.format(insert, "'knee', 'X', 'qa_item', 'Probe', null, null, 50"))).as("unknown kind").isEqualTo("23514");
        assertThat(migratorState(String.format(insert, "'knee', 'ROM', 'qa_item', 'Probe', 0, 90, 50"))).isEqualTo("ACCEPTED");
        assertThat(migratorState(String.format(insert, "'knee', 'MMT', 'qa_item', 'Probe', null, null, 50"))).isEqualTo("ACCEPTED");
    }

    // ================= S-87 · the reserved key =================

    @Test
    void noLibraryFieldMayUseTheReservedKeySpecialtyAndNoSeededFieldDoes() throws Exception {
        assertThat(globalCount("select count(*) from opd.emr_section_field where field_key = 'specialty'")).isZero();
        assertThat(globalCount("select count(*) from opd.emr_section_field")).as("313 seeded fields, all pass the new check").isEqualTo(313);
        assertThat(migratorState("insert into opd.emr_section_field (section_key, field_key, position, label, field_type) values ('func_scales', 'specialty', 90, 'Probe', 'TEXT')"))
                .as("ck_emr_section_field_key_not_reserved").isEqualTo("23514");
        assertThat(migratorState("insert into opd.emr_section_field (section_key, field_key, position, label, field_type) values ('func_scales', 'specialty_x', 90, 'Probe', 'TEXT')"))
                .as("only the exact key is reserved").isEqualTo("ACCEPTED");
    }

    // ================= S-88 · screen_state =================

    @Test
    void theRecordScreenStateIsNullOrASmallObjectWithASchemaVersionAndTheAppMayUpdateItButNotCreatedAt() throws Exception {
        Rec rec = newRec();
        Clinic clinic = rec.clinic();
        assertThat(row(clinic, "select screen_state::text from opd.emr_record where id = ?::uuid", rec.id())).containsExactly((String) null);
        String update = "update opd.emr_record set %s where id = ?::uuid";
        for (String bad : new String[] {"screen_state = '[]'::jsonb", "screen_state = '{\"mode\":\"ANC\"}'::jsonb", "screen_state = '{\"schemaVersion\":\"1\"}'::jsonb",
                "screen_state = jsonb_build_object('schemaVersion', 1, 'pad', repeat('x', 9000))", "screen_state = 'null'::jsonb"}) {
            assertThat(stateIn(clinic, String.format(update, bad), rec.id())).as(bad).isEqualTo("23514");
        }
        for (String good : new String[] {"screen_state = null", "screen_state = '{\"schemaVersion\":1,\"profile\":\"og\",\"mode\":\"GYNAE\"}'::jsonb"}) {
            assertThat(stateIn(clinic, String.format(update, good), rec.id())).as(good).isEqualTo("ACCEPTED");
        }
        assertThat(stateIn(clinic, String.format(update, "created_at = now()"), rec.id())).isEqualTo("42501");
    }

    @Test
    void everyOneOfTheTwentySixRecordRelationsStillHasOneRowLevelSecurityPolicyAndTheNewUniqueKeyExistsOnTheParent() throws Exception {
        assertThat(globalCount("select count(*) from pg_constraint where conrelid = 'opd.emr_record'::regclass and conname = 'uq_emr_record_tenant_id_created_at'")).isEqualTo(1);
        assertThat(globalCount("select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace left join pg_inherits i on i.inhrelid = c.oid "
                + "where n.nspname = 'opd' and (c.relname = 'emr_record' or i.inhparent = 'opd.emr_record'::regclass)")).as("the parent and its partitions").isEqualTo(26);
        List<List<String>> weak = globalRows("select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace left join pg_inherits i on i.inhrelid = c.oid "
                + "where n.nspname = 'opd' and (c.relname = 'emr_record' or i.inhparent = 'opd.emr_record'::regclass) "
                + "and (not c.relrowsecurity or not c.relforcerowsecurity or (select count(*) from pg_policy p where p.polrelid = c.oid) <> 1)");
        assertThat(weak).isEmpty();
    }

    // ================= S-100 · visit templates =================

    private String templateInsert(Clinic clinic, String name, String items) {
        return "insert into opd.emr_visit_template (name, items, created_by_membership_id, created_by) values ('" + name + "', " + items + ", '"
                + UUID.randomUUID() + "', '" + clinic.ownerUserId() + "')";
    }

    @Test
    void aVisitTemplateNameIsUniqueInTheClinicIgnoringCaseAndAnotherClinicMayUseTheSameNameAndTheChecksHold() throws Exception {
        Rec a = newRec();
        Rec b = newRec();
        commit(a.clinic().tenantId(), templateInsert(a.clinic(), "Fever visit", ITEMS));
        assertThat(stateIn(a.clinic(), templateInsert(a.clinic(), "FEVER VISIT", ITEMS))).as("another letter case").isEqualTo("23505");
        assertThat(stateIn(b.clinic(), templateInsert(b.clinic(), "Fever visit", ITEMS))).as("another clinic").isEqualTo("ACCEPTED");
        assertThat(stateIn(a.clinic(), templateInsert(a.clinic(), "n".repeat(61), ITEMS))).as("61 characters").isEqualTo("23514");
        assertThat(stateIn(a.clinic(), templateInsert(a.clinic(), "n".repeat(60), ITEMS))).as("60 characters").isEqualTo("ACCEPTED");
        assertThat(stateIn(a.clinic(), templateInsert(a.clinic(), "  ", ITEMS))).isEqualTo("23514");
        assertThat(stateIn(a.clinic(), templateInsert(a.clinic(), "No version", "'{\"complaints\":[]}'::jsonb"))).as("items without schemaVersion").isEqualTo("23514");
        assertThat(stateIn(a.clinic(), templateInsert(a.clinic(), "A list", "'[]'::jsonb"))).isEqualTo("23514");
        assertThat(stateIn(a.clinic(), templateInsert(a.clinic(), "Too big", "jsonb_build_object('schemaVersion', 1, 'pad', repeat('x', 33000))"))).as("over 32 KB").isEqualTo("23514");
        assertThat(stateIn(a.clinic(), "insert into opd.emr_visit_template (name, sub_line, items, created_by_membership_id, created_by) values ('Sub', '" + "s".repeat(121) + "', "
                + ITEMS + ", '" + UUID.randomUUID() + "', '" + a.clinic().ownerUserId() + "')")).as("sub line over 120").isEqualTo("23514");
    }

    @Test
    void theAppMayOnlySwitchATemplateOffNeverRenameOrRewriteOrDeleteItAndAnotherClinicSeesNone() throws Exception {
        Rec a = newRec();
        Rec b = newRec();
        commit(a.clinic().tenantId(), templateInsert(a.clinic(), "Review visit", ITEMS));
        assertThat(stateIn(a.clinic(), "update opd.emr_visit_template set active = false, updated_at = now()")).isEqualTo("ACCEPTED");
        assertThat(stateIn(a.clinic(), "update opd.emr_visit_template set name = 'Renamed'")).isEqualTo("42501");
        assertThat(stateIn(a.clinic(), "update opd.emr_visit_template set items = '{\"schemaVersion\":1}'::jsonb")).isEqualTo("42501");
        assertThat(stateIn(a.clinic(), "delete from opd.emr_visit_template")).isEqualTo("42501");
        assertThat(count(a.clinic(), "select count(*) from opd.emr_visit_template")).isEqualTo(1);
        assertThat(count(b.clinic(), "select count(*) from opd.emr_visit_template")).as("another clinic sees none").isZero();
        assertThat(globalCount("select count(*) from opd.emr_visit_template")).as("no clinic bound sees none").isZero();
        assertThat(stateIn(b.clinic(), "insert into opd.emr_visit_template (tenant_id, name, items, created_by_membership_id, created_by) values ('" + a.clinic().tenantId()
                + "', 'Cross', " + ITEMS + ", '" + UUID.randomUUID() + "', '" + b.clinic().ownerUserId() + "')")).as("a row for another clinic").isIn("42501", "23503");
    }

    // ================= S-101 · backward compatibility =================

    @Test
    void theSevenPhase4MigrationFilesWereAppliedInOrderWithoutFailure() throws Exception {
        List<List<String>> applied = db().asMigrator(c -> allRows(c, "select version, success::text from platform.flyway_schema_history where version like '2026.12.11.%' order by installed_rank"));
        assertThat(applied.stream().map(r -> r.get(0)).toList()).containsExactly("2026.12.11.1000", "2026.12.11.1010", "2026.12.11.1020", "2026.12.11.1030",
                "2026.12.11.1100", "2026.12.11.1110", "2026.12.11.1120");
        applied.forEach(r -> assertThat(r.get(1)).as(r.get(0)).isEqualTo("true"));
        long failed = db().asMigrator(c -> DbAccess.count(c, "select count(*) from platform.flyway_schema_history where not success"));
        assertThat(failed).isZero();
    }
}
