package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The stored shape of the SAH-48 to SAH-59 tables, by SQL on the real {@code sahaj_test} database as {@code sahaj_app} (cases
 * S1 to S23 of docs/database/SAH-48-59.md): the two new global tables (allergy classes, starter forms), the medicine defaults,
 * the three new record columns, the four opd tables (form, form version, course, sitting) and the two counters, the billing
 * tables (course payment and credit transfer append-only, receipt counter, EMR bill-line columns), tenant isolation on all
 * eight new clinic tables, privileges and the Flyway history. The ipd columns (S19, S20) are in {@link EmrCourseAdmissionIT}.
 * Writes that must be accepted prove that a constraint, not a missing fixture, is the reason for a refusal; they run in a
 * transaction that is always rolled back. Test plan: docs/test-plans/SAH-48-59.md.
 */
@OnTestDatabase
class EmrPhase3TablesIT extends EmrPhase3Support {

    private static final String[] NEW_CLINIC_TABLES = {"opd.emr_form", "opd.emr_form_version", "opd.treatment_course_counter", "opd.treatment_course",
            "opd.treatment_course_sitting", "billing.receipt_counter", "billing.course_payment", "billing.course_credit_transfer"};
    private static final String DEF = "'{\"schemaVersion\":1,\"fields\":[]}'::jsonb";

    /** The SQLSTATE of the statement, or ACCEPTED; always rolled back. */
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

    private String formInsert(Clinic clinic, String key, String name) {
        return "insert into opd.emr_form (form_key, name, created_by, draft_definition) values ('" + key + "', '" + name + "', '"
                + clinic.ownerUserId() + "', " + DEF + ")";
    }

    // ================= S1 to S7 · the global tables and the medicine defaults =================

    @Test
    void theAllergyClassTableHoldsExactlyTheTwoDrawnClassesAndCoTrimoxazoleIsSeededWithItsIdFromItsName() throws Exception {
        assertThat(globalRows("select class_key, label, position::text from opd.emr_allergy_class order by position"))
                .containsExactly(List.of("SULFA", "Sulfa drugs", "1"), List.of("PENICILLIN", "Penicillin", "2"));
        List<String> cotri = globalRows("select id::text, form, schedule, allergy_class, default_frequency, default_days::text, default_when "
                + "from opd.medicine where name = 'Co-trimoxazole 960 mg'").get(0);
        assertThat(cotri).containsExactly(globalRows("select md5('opd.medicine:' || lower('Co-trimoxazole 960 mg'))::uuid::text").get(0).get(0),
                "Tablet", "1-0-1", "SULFA", "1-0-1", "5", "AFTER_FOOD");
        assertThat(globalRows("select string_agg(name, ',' order by name) from opd.medicine where allergy_class = 'PENICILLIN'").get(0).get(0))
                .isEqualTo("Amoxicillin 125 mg/5 ml,Amoxicillin 500 mg,Amoxicillin 500 mg + Clavulanic acid 125 mg");
        assertThat(globalCount("select count(*) from opd.medicine where allergy_class is not null")).isEqualTo(4);
        assertThat(globalRows("select string_agg(name || ':' || default_frequency || '/' || default_days || '/' || default_when, ';' order by name) "
                + "from opd.medicine where default_frequency is not null").get(0).get(0)).isEqualTo(
                "Amlodipine 5 mg:1-0-0/30/AFTER_FOOD;Atorvastatin 10 mg:0-0-1/30/BEDTIME;Cetirizine 10 mg:0-0-1/5/BEDTIME;"
                        + "Co-trimoxazole 960 mg:1-0-1/5/AFTER_FOOD;Pantoprazole 40 mg:1-0-0/14/MORNING_EMPTY_STOMACH;"
                        + "Paracetamol 650 mg:1-0-1/3/AFTER_FOOD;Telmisartan 40 mg:1-0-0/30/AFTER_FOOD");
        assertThat(globalCount("select count(*) from opd.medicine where name = 'Metformin 500 mg' and default_frequency is not null")).isZero();
    }

    @Test
    void theMedicineDefaultsAreAllSetOrAllNullAndStayInsideTheirLists() throws Exception {
        db().asMigrator(c -> {
            assertThat(expectError(c, "update opd.medicine set default_days = 3 where name = 'Metformin 500 mg'").state()).isEqualTo("23514");
            assertThat(expectError(c, "update opd.medicine set default_frequency = '1-0-1' where name = 'Metformin 500 mg'").state()).isEqualTo("23514");
            for (String days : new String[] {"0", "366", "-1"}) {
                assertThat(expectError(c, "update opd.medicine set default_frequency = '1-0-1', default_days = " + days + ", default_when = 'AFTER_FOOD' "
                        + "where name = 'Metformin 500 mg'").state()).as(days).isEqualTo("23514");
            }
            assertThat(expectError(c, "update opd.medicine set default_frequency = '2-2-2', default_days = 3, default_when = 'AFTER_FOOD' where name = 'Metformin 500 mg'").state()).isEqualTo("23514");
            assertThat(expectError(c, "update opd.medicine set default_frequency = '1-0-1', default_days = 3, default_when = 'WITH_MILK' where name = 'Metformin 500 mg'").state()).isEqualTo("23514");
            assertThat(expectError(c, "update opd.medicine set allergy_class = 'LATEX' where name = 'Metformin 500 mg'").state()).as("a class that does not exist").isEqualTo("23503");
            expectOk(c, "update opd.medicine set default_frequency = '1-0-1', default_days = 365, default_when = 'BEDTIME' where name = 'Metformin 500 mg'");
            expectOk(c, "update opd.medicine set default_frequency = '1-0-1', default_days = 1, default_when = 'BEDTIME' where name = 'Metformin 500 mg'");
            return null;
        });
        assertThat(db().asApp(c -> expectError(c, "update opd.medicine set default_days = 3 where name = 'Metformin 500 mg'")).state()).isEqualTo("42501");
    }

    @Test
    void theTwoNewGlobalTablesAreSelectOnlyForTheAppHaveNoClinicColumnNoRowLevelSecurityAndTheStarterFormsAreImmutableEvenForTheOwner() throws Exception {
        for (String table : new String[] {"emr_allergy_class", "emr_form_starter"}) {
            for (String sql : new String[] {"insert into opd." + table + " default values", "update opd." + table + " set created_at = created_at",
                    "delete from opd." + table, "truncate opd." + table}) {
                SqlError err = db().asApp(c -> expectError(c, sql));
                assertThat(err.state()).as(sql).isEqualTo("42501");
            }
            assertThat(globalRows("select has_table_privilege('sahaj_app', 'opd." + table + "', 'SELECT')").get(0).get(0)).isEqualTo("t");
            for (String privilege : new String[] {"INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
                assertThat(globalRows("select has_table_privilege('sahaj_app', 'opd." + table + "', '" + privilege + "')").get(0).get(0)).as(table + " " + privilege).isEqualTo("f");
            }
            assertThat(globalCount("select count(*) from information_schema.columns where table_schema = 'opd' and table_name = '" + table + "' and column_name = 'tenant_id'")).isZero();
            assertThat(globalCount("select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'opd' and c.relname = '" + table + "' and c.relrowsecurity")).isZero();
            assertThat(globalCount("select count(*) from information_schema.role_table_grants where table_schema = 'opd' and table_name = '" + table + "' and grantee = 'PUBLIC'")).isZero();
            assertThat(globalRows("select obj_description('opd." + table + "'::regclass, 'pg_class')").get(0).get(0)).contains("not clinically reviewed");
        }
        assertThat(globalRows("select starter_key from opd.emr_form_starter order by position").stream().map(r -> r.get(0)).toList())
                .containsExactly("diabetic_foot", "gcs", "morse", "mlc_injury", "pre_anaesthetic", "untitled");
        assertThat(globalRows("select string_agg(position::text, ',' order by position) from opd.emr_form_starter").get(0).get(0)).isEqualTo("1,2,3,4,5,6");
        assertThat(globalCount("select count(*) from opd.emr_form_starter where jsonb_typeof(definition -> 'fields') <> 'array' or jsonb_typeof(definition -> 'schemaVersion') <> 'number'")).isZero();
        assertThat(globalCount("select count(*) from opd.emr_form_starter where octet_length(definition::text) > 262144")).isZero();
        db().asMigrator(c -> {
            assertThat(expectError(c, "update opd.emr_form_starter set name = name").message()).contains("immutable");
            assertThat(expectError(c, "delete from opd.emr_form_starter").message()).contains("immutable");
            assertThat(expectError(c, "truncate opd.emr_form_starter").message()).as("refused: by the trigger, or first by a foreign key that references the table").matches("(?s).*(immutable|referenced in a foreign key).*");
            expectOk(c, "insert into opd.emr_form_starter (starter_key, name, description, suited_for, position, definition) values ('qa_probe', 'Probe', 'One line', 'Any', 99, "
                    + DEF + ")");
            assertThat(expectError(c, "update opd.emr_form_starter set name = 'Changed' where starter_key = 'qa_probe'").message()).contains("immutable");
            assertThat(expectError(c, "insert into opd.emr_form_starter (starter_key, name, description, suited_for, position, definition) values ('Bad Key', 'P', 'd', 's', 98, " + DEF + ")").state()).isEqualTo("23514");
            assertThat(expectError(c, "insert into opd.emr_form_starter (starter_key, name, description, suited_for, position, definition) values ('qa_two', 'P', 'd', 's', 99, " + DEF + ")").state()).as("position is unique").isEqualTo("23505");
            assertThat(expectError(c, "insert into opd.emr_form_starter (starter_key, name, description, suited_for, position, definition) values ('qa_three', 'P', 'd', 's', 97, '[]'::jsonb)").state()).as("a definition is an object").isEqualTo("23514");
            return null;
        });
        assertThat(globalCount("select count(*) from opd.emr_form_starter")).as("the probes were rolled back").isEqualTo(6);
    }

    @Test
    void theImmutabilityAndAppendOnlyTriggersAreThereByNameAndEnabled() throws Exception {
        String[][] triggers = {{"opd.emr_form_starter", "trg_emr_form_starter_immutable"}, {"opd.emr_form_starter", "trg_emr_form_starter_no_truncate"},
                {"opd.emr_form_version", "trg_emr_form_version_immutable"}, {"opd.emr_form_version", "trg_emr_form_version_no_truncate"},
                {"billing.course_payment", "trg_course_payment_no_update_delete"}, {"billing.course_payment", "trg_course_payment_no_truncate"},
                {"billing.course_credit_transfer", "trg_course_credit_transfer_no_update_delete"}, {"billing.course_credit_transfer", "trg_course_credit_transfer_no_truncate"}};
        for (String[] t : triggers) {
            assertThat(globalRows("select tgenabled::text from pg_trigger where tgrelid = '" + t[0] + "'::regclass and tgname = '" + t[1] + "'").stream().map(r -> r.get(0)).toList())
                    .as(t[0] + " " + t[1]).containsExactly("O");
        }
        for (String table : new String[] {"opd.emr_form_version", "billing.course_payment", "billing.course_credit_transfer"}) {
            SqlError err = db().asMigrator(c -> expectError(c, "truncate " + table));
            assertThat(err.message()).as(table + " cannot be truncated even by the table owner").isNotBlank();
        }
    }

    // ================= S8 · the new columns of the record =================

    @Test
    void theRecordHasThePrintAndFormReferenceColumnsWithTheirDefaultsAndChecksAndTheAppMayUpdateOnlyThoseThree() throws Exception {
        Rec rec = newRec();
        Clinic clinic = rec.clinic();
        assertThat(row(clinic, "select print_language, paper_size, form_refs::text from opd.emr_record where id = ?::uuid", rec.id())).containsExactly(null, null, "[]");
        String update = "update opd.emr_record set %s where id = ?::uuid";
        for (String bad : new String[] {"print_language = 'FR'", "print_language = 'mr'", "paper_size = 'A3'", "paper_size = 'a4'", "form_refs = '{}'::jsonb", "form_refs = 'null'::jsonb",
                "form_refs = jsonb_build_array(repeat('x', 17000))"}) {
            assertThat(stateIn(clinic, String.format(update, bad), rec.id())).as(bad).isEqualTo("23514");
        }
        for (String good : new String[] {"print_language = 'EN'", "print_language = 'MR'", "print_language = 'HI'", "paper_size = 'A4'", "paper_size = 'A5'",
                "form_refs = '[]'::jsonb", "form_refs = '[{\"formKey\":\"f_abcdefghij\",\"versionNo\":1}]'::jsonb", "print_language = null, paper_size = null"}) {
            assertThat(stateIn(clinic, String.format(update, good), rec.id())).as(good).isEqualTo("ACCEPTED");
        }
        assertThat(stateIn(clinic, String.format(update, "created_at = now()"), rec.id())).as("created_at is still not updatable").isEqualTo("42501");
        assertThat(stateIn(clinic, String.format(update, "form_refs = null"), rec.id())).isEqualTo("23502");
    }

    // ================= S9, S10 · the form =================

    @Test
    void aFormKeyHasThePatternANameIsUniqueInTheClinicIgnoringCaseAndAnotherClinicMayUseTheSameName() throws Exception {
        Rec a = newRec();
        Rec b = newRec();
        commit(a.clinic().tenantId(), formInsert(a.clinic(), "f_abcdefghij", "Wound review"));
        assertThat(stateIn(a.clinic(), formInsert(a.clinic(), "f_ABC", "Another form"))).isEqualTo("23514");
        assertThat(stateIn(a.clinic(), formInsert(a.clinic(), "f_abcdefghi1", "Another form"))).as("1 is not base32").isEqualTo("23514");
        assertThat(stateIn(a.clinic(), formInsert(a.clinic(), "g_abcdefghij", "Another form"))).isEqualTo("23514");
        assertThat(stateIn(a.clinic(), formInsert(a.clinic(), "f_abcdefghij", "Another form"))).as("a form key is unique in the clinic").isEqualTo("23505");
        assertThat(stateIn(a.clinic(), formInsert(a.clinic(), "f_klmnopqrst", "WOUND REVIEW"))).as("the same name in another letter case").isEqualTo("23505");
        assertThat(stateIn(a.clinic(), formInsert(a.clinic(), "f_klmnopqrst", "  "))).isEqualTo("23514");
        assertThat(stateIn(a.clinic(), formInsert(a.clinic(), "f_klmnopqrst", "n".repeat(81)))).isEqualTo("23514");
        assertThat(stateIn(b.clinic(), formInsert(b.clinic(), "f_abcdefghij", "Wound review"))).as("another clinic: same key and same name").isEqualTo("ACCEPTED");
    }

    @Test
    void theStateChecksOfAFormKeepTheDraftVersionNextToTheLiveOneTheStateAndTheDefinitionTogetherAndTheKeyWithItsHash() throws Exception {
        Rec rec = newRec();
        Clinic c = rec.clinic();
        String base = "insert into opd.emr_form (form_key, name, created_by, %s) values ('f_abcdefghij', 'State form', '" + c.ownerUserId() + "', %s)";
        String[][] bad = {
                {"draft_definition, live_version_no, draft_version_no", DEF + ", 1, 3"},
                {"draft_definition, draft_state", DEF + ", 'NONE'"},
                {"draft_state", "'NONE'"},
                {"draft_definition, draft_state", DEF + ", 'SENT_BACK'"},
                {"draft_definition, draft_state, sent_back_reason", DEF + ", 'DRAFT', 'A reason'"},
                {"draft_definition, draft_state", DEF + ", 'WAITING_APPROVAL'"},
                {"draft_definition, idempotency_key", DEF + ", 'abcdefgh1234'"},
                {"draft_definition, request_hash", DEF + ", 'abcdefabcdefabcdefabcdef'"},
                {"draft_definition, idempotency_key, request_hash", DEF + ", 'short', 'abcdefabcdefabcdefabcdef'"},
                {"draft_definition, submitted_at", DEF + ", now()"},
                {"draft_definition, status", DEF + ", 'DELETED'"},
                {"draft_definition, revision", DEF + ", -1"},
                {"draft_definition", "'[]'::jsonb"},
                {"draft_definition", "'{\"schemaVersion\":\"1\",\"fields\":[]}'::jsonb"},
                {"draft_definition", "'{\"schemaVersion\":1,\"fields\":{}}'::jsonb"},
                {"draft_definition, draft_state, live_version_no, draft_version_no", "null, 'NONE', 0, 1"}};
        for (String[] b : bad) {
            assertThat(stateIn(c, String.format(base, b[0], b[1]))).as(b[0] + " = " + b[1]).isEqualTo("23514");
        }
        String[][] good = {
                {"draft_definition", DEF}, {"draft_definition, live_version_no, draft_version_no", DEF + ", 2, 3"},
                {"draft_definition, draft_state, sent_back_reason", DEF + ", 'SENT_BACK', 'Add the eye score'"},
                {"draft_definition, draft_state, submitted_at, submitted_by", DEF + ", 'WAITING_APPROVAL', now(), '" + c.ownerUserId() + "'"},
                {"draft_definition, idempotency_key, request_hash", DEF + ", 'abcdefgh1234', 'abcdefabcdefabcdefabcdef'"},
                {"draft_state, live_version_no, draft_version_no", "'NONE', 1, 2"}};
        for (String[] g : good) {
            assertThat(stateIn(c, String.format(base, g[0], g[1]))).as(g[0]).isEqualTo("ACCEPTED");
        }
        // the same idempotency key twice in a clinic
        commit(c.tenantId(), String.format(base, "draft_definition, idempotency_key, request_hash", DEF + ", 'abcdefgh1234', 'abcdefabcdefabcdefabcdef'"));
        assertThat(stateIn(c, "insert into opd.emr_form (form_key, name, created_by, draft_definition, idempotency_key, request_hash) values ('f_klmnopqrst', 'Other', '"
                + c.ownerUserId() + "', " + DEF + ", 'abcdefgh1234', 'abcdefabcdefabcdefabcdef')")).isEqualTo("23505");
    }

    @Test
    void aFormVersionIsInsertOnlyKeyedByFormAndNumberAndNeverPointsAtAnotherClinicsForm() throws Exception {
        Rec a = newRec();
        Rec b = newRec();
        commit(a.clinic().tenantId(), formInsert(a.clinic(), "f_abcdefghij", "Version form"));
        commit(b.clinic().tenantId(), formInsert(b.clinic(), "f_abcdefghij", "Their form"));
        String formA = row(a.clinic(), "select id::text from opd.emr_form").get(0);
        String formB = row(b.clinic(), "select id::text from opd.emr_form").get(0);
        String insert = "insert into opd.emr_form_version (form_id, version_no, definition, published_by) values (?::uuid, ?, " + DEF + ", '" + a.clinic().ownerUserId() + "')";
        assertThat(stateIn(a.clinic(), insert, formA, 1)).isEqualTo("ACCEPTED");
        assertThat(stateIn(a.clinic(), insert, formA, 0)).isEqualTo("23514");
        assertThat(stateIn(a.clinic(), insert, formB, 1)).as("the other clinic's form").isEqualTo("23503");
        assertThat(stateIn(a.clinic(), insert, UUID.randomUUID().toString(), 1)).isEqualTo("23503");
        assertThat(stateIn(a.clinic(), "insert into opd.emr_form_version (form_id, version_no, definition, published_by, approved_by) values (?::uuid, 1, " + DEF + ", '"
                + a.clinic().ownerUserId() + "', '" + a.clinic().ownerUserId() + "')", formA)).as("approved by without approved at").isEqualTo("23514");
        assertThat(stateIn(a.clinic(), "insert into opd.emr_form_version (form_id, version_no, definition, published_by) values (?::uuid, 1, '{\"fields\":[]}'::jsonb, '"
                + a.clinic().ownerUserId() + "')", formA)).as("no schema version").isEqualTo("23514");
        commit(a.clinic().tenantId(), insert, formA, 1);
        assertThat(stateIn(a.clinic(), insert, formA, 1)).as("a version number is used once per form").isEqualTo("23505");
        assertThat(stateIn(a.clinic(), "update opd.emr_form_version set definition = '{}'::jsonb")).isEqualTo("42501");
        assertThat(stateIn(a.clinic(), "delete from opd.emr_form_version")).isEqualTo("42501");
        assertThat(stateIn(a.clinic(), "truncate opd.emr_form_version")).isEqualTo("42501");
    }

    // ================= S12 to S14, S13 · course, sitting and the counter =================

    @Test
    void aCourseKindHasItsOwnUnitANumberIsUniqueInTheClinicAndAKeyNeedsItsHash() throws Exception {
        Rec a = newRec();
        Rec b = newRec();
        UUID doctor = a.doctor().membershipId();
        String insert = "insert into opd.treatment_course (course_no, patient_id, kind, unit, name, doctor_membership_id, started_on, created_by%s) values (?, ?::uuid, ?, ?, 'Course', ?::uuid, current_date, ?::uuid%s)";
        String plain = String.format(insert, "", "");
        String owner = a.clinic().ownerUserId().toString();
        assertThat(stateIn(a.clinic(), plain, 1, a.patientId().toString(), "DENTAL", "SITTING", doctor.toString(), owner)).isEqualTo("ACCEPTED");
        assertThat(stateIn(a.clinic(), plain, 1, a.patientId().toString(), "DENTAL", "VISIT", doctor.toString(), owner)).as("dental with a visit").isEqualTo("23514");
        assertThat(stateIn(a.clinic(), plain, 1, a.patientId().toString(), "ANC", "SITTING", doctor.toString(), owner)).isEqualTo("23514");
        assertThat(stateIn(a.clinic(), plain, 1, a.patientId().toString(), "TEETH", "SITTING", doctor.toString(), owner)).isEqualTo("23514");
        assertThat(stateIn(a.clinic(), plain, 0, a.patientId().toString(), "DENTAL", "SITTING", doctor.toString(), owner)).isEqualTo("23514");
        for (String[] pair : new String[][] {{"ANC", "VISIT"}, {"CHEMO", "CYCLE"}, {"SURGERY", "STEP"}, {"REHAB", "SESSION"}}) {
            assertThat(stateIn(a.clinic(), plain, 2, a.patientId().toString(), pair[0], pair[1], doctor.toString(), owner)).as(pair[0]).isEqualTo("ACCEPTED");
        }
        commit(a.clinic().tenantId(), plain, 1, a.patientId().toString(), "DENTAL", "SITTING", doctor.toString(), owner);
        assertThat(stateIn(a.clinic(), plain, 1, a.patientId().toString(), "ANC", "VISIT", doctor.toString(), owner)).as("the number is used once in the clinic").isEqualTo("23505");
        assertThat(stateIn(b.clinic(), plain, 1, b.patientId().toString(), "DENTAL", "SITTING", b.doctor().membershipId().toString(), b.clinic().ownerUserId().toString()))
                .as("the same number in another clinic").isEqualTo("ACCEPTED");
        assertThat(stateIn(b.clinic(), plain, 5, a.patientId().toString(), "DENTAL", "SITTING", b.doctor().membershipId().toString(), b.clinic().ownerUserId().toString()))
                .as("a patient of another clinic").isEqualTo("23503");
        String keyed = String.format(insert, ", idempotency_key, request_hash", ", 'abcdefgh1234', 'abcdefabcdefabcdefabcdef'");
        commit(a.clinic().tenantId(), keyed, 7, a.patientId().toString(), "DENTAL", "SITTING", doctor.toString(), owner);
        assertThat(stateIn(a.clinic(), keyed, 8, a.patientId().toString(), "DENTAL", "SITTING", doctor.toString(), owner)).as("the same key twice").isEqualTo("23505");
        assertThat(stateIn(a.clinic(), String.format(insert, ", idempotency_key", ", 'abcdefgh1234'"), 9, a.patientId().toString(), "DENTAL", "SITTING", doctor.toString(), owner))
                .as("a key without its hash").isEqualTo("23514");
        assertThat(stateIn(a.clinic(), String.format(insert, ", status", ", 'DONE'"), 9, a.patientId().toString(), "DENTAL", "SITTING", doctor.toString(), owner)).isEqualTo("23514");
        assertThat(stateIn(a.clinic(), "update opd.treatment_course set status = 'DONE'")).as("only active exists in phase 3").isEqualTo("23514");
        assertThat(stateIn(a.clinic(), "update opd.treatment_course set name = 'Renamed'")).as("a course has no edit endpoint and no name grant").isEqualTo("42501");
        assertThat(stateIn(a.clinic(), "update opd.treatment_course set revision = revision + 1")).isEqualTo("ACCEPTED");
    }

    @Test
    void aSittingKeepsItsEncounterKindAdmissionFlagAndLinksConsistentAndItsItemsInsideTheLimits() throws Exception {
        Rec rec = newRec();
        JsonNode course = newCourse(rec, "DENTAL", "[" + sittingJson("opd_sitting", 100000L, null) + "]");
        Rec other = newRec();
        Clinic c = rec.clinic();
        String courseId = course.get("id").asText();
        String insert = "insert into opd.treatment_course_sitting (course_id, sitting_no, encounter_kind, add_option_key, %s created_by) values (?::uuid, ?, ?, 'OPD_SITTING', %s '" + c.ownerUserId() + "')";
        String plain = String.format(insert, "", "");
        assertThat(stateIn(c, plain, courseId, 2, "OPD")).isEqualTo("ACCEPTED");
        assertThat(stateIn(c, String.format(insert, "admission_requested,", "true,"), courseId, 2, "OPD")).as("OPD with a request").isEqualTo("23514");
        assertThat(stateIn(c, plain, courseId, 2, "DAY_CARE")).as("day care without the request").isEqualTo("23514");
        assertThat(stateIn(c, String.format(insert, "admission_requested,", "true,"), courseId, 2, "DAY_CARE")).isEqualTo("ACCEPTED");
        assertThat(stateIn(c, String.format(insert, "admission_requested,", "true,"), courseId, 2, "IPD")).isEqualTo("ACCEPTED");
        assertThat(stateIn(c, plain, courseId, 2, "HOSPITAL")).isEqualTo("23514");
        assertThat(stateIn(c, plain, courseId, 1, "OPD")).as("sitting number twice in a course").isEqualTo("23505");
        assertThat(stateIn(c, plain, courseId, 0, "OPD")).isEqualTo("23514");
        assertThat(stateIn(c, String.format(insert, "opd_visit_id,", "'" + rec.visitId() + "',"), courseId, 2, "OPD")).isEqualTo("ACCEPTED");
        assertThat(stateIn(c, String.format(insert, "admission_requested, opd_visit_id,", "true, '" + rec.visitId() + "',"), courseId, 2, "DAY_CARE")).as("a visit on a day-care sitting").isEqualTo("23514");
        assertThat(stateIn(c, String.format(insert, "ipd_admission_id,", "gen_random_uuid(),"), courseId, 2, "OPD")).as("an admission on an OPD sitting").isEqualTo("23514");
        assertThat(stateIn(c, String.format(insert, "admission_requested, ipd_admission_id,", "true, gen_random_uuid(),"), courseId, 2, "IPD")).isEqualTo("ACCEPTED");
        assertThat(stateIn(c, String.format(insert, "opd_visit_id,", "'" + other.visitId() + "',"), courseId, 2, "OPD")).as("a visit of another clinic").isEqualTo("23503");
        assertThat(stateIn(c, String.format(insert, "charge_paise,", "-1,"), courseId, 2, "OPD")).isEqualTo("23514");
        assertThat(stateIn(c, String.format(insert, "charge_paise,", "10000000001,"), courseId, 2, "OPD")).isEqualTo("23514");
        assertThat(stateIn(c, String.format(insert, "charge_paise,", "10000000000,"), courseId, 2, "OPD")).isEqualTo("ACCEPTED");
        assertThat(stateIn(c, String.format(insert, "items,", "array[" + "'i',".repeat(20) + "'i'],"), courseId, 2, "OPD")).as("21 items").isEqualTo("23514");
        assertThat(stateIn(c, String.format(insert, "items,", "array[" + "'i',".repeat(19) + "'i'],"), courseId, 2, "OPD")).as("20 items").isEqualTo("ACCEPTED");
        assertThat(stateIn(c, String.format(insert, "items,", "array['']::text[],"), courseId, 2, "OPD")).as("an empty item").isEqualTo("23514");
        assertThat(stateIn(c, String.format(insert, "items,", "array['" + "i".repeat(201) + "'],"), courseId, 2, "OPD")).isEqualTo("23514");
        assertThat(stateIn(c, String.format(insert, "note,", "'" + "n".repeat(301) + "',"), courseId, 2, "OPD")).isEqualTo("23514");
        assertThat(stateIn(c, "insert into opd.treatment_course_sitting (course_id, sitting_no, encounter_kind, add_option_key, created_by) values (?::uuid, 2, 'OPD', 'opd_sitting', '" + c.ownerUserId() + "')", courseId))
                .as("the option key is stored in capitals").isEqualTo("23514");
        assertThat(stateIn(c, plain, UUID.randomUUID().toString(), 2, "OPD")).as("a course that is not there").isEqualTo("23503");
        // the same visit on two sittings is refused: the real sitting already holds nothing, so link it twice
        String sitting1 = course.get("sittings").get(0).get("id").asText();
        commit(c.tenantId(), "update opd.treatment_course_sitting set opd_visit_id = ?::uuid where id = ?::uuid", rec.visitId(), sitting1);
        assertThat(stateIn(c, String.format(insert, "opd_visit_id,", "'" + rec.visitId() + "',"), courseId, 2, "OPD")).as("one visit, one sitting").isEqualTo("23505");
        // the update grant covers the working columns only
        assertThat(stateIn(c, "update opd.treatment_course_sitting set sitting_no = 9")).isEqualTo("42501");
        assertThat(stateIn(c, "update opd.treatment_course_sitting set course_id = gen_random_uuid()")).isEqualTo("42501");
        assertThat(stateIn(c, "update opd.treatment_course_sitting set done_at = now(), charge_paise = 5")).isEqualTo("ACCEPTED");
        assertThat(stateIn(c, "delete from opd.treatment_course_sitting")).isEqualTo("42501");
    }

    @Test
    void theCourseNumberAndTheReceiptNumberUpsertsOfTwoSessionsGiveOneAndTwoNeverTheSameNumber() throws Exception {
        Rec rec = newRec();
        Clinic clinic = rec.clinic();
        String courseSql = "insert into opd.treatment_course_counter (last_no) values (1) on conflict (tenant_id) do update set last_no = opd.treatment_course_counter.last_no + 1 returning last_no";
        String receiptSql = "insert into billing.receipt_counter (last_no) values (1) on conflict (tenant_id) do update set last_no = billing.receipt_counter.last_no + 1, updated_at = now() returning last_no";
        for (String sql : new String[] {courseSql, receiptSql}) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch go = new CountDownLatch(1);
                Callable<String> next = () -> {
                    go.await();
                    try (Connection c = db().app()) {
                        c.setAutoCommit(false);
                        try (java.sql.PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                            bind.setString(1, clinic.tenantId().toString());
                            bind.execute();
                        }
                        String number = allRows(c, sql).get(0).get(0);
                        Thread.sleep(50);
                        c.commit();
                        return number;
                    }
                };
                Future<String> one = pool.submit(next);
                Future<String> two = pool.submit(next);
                go.countDown();
                assertThat(List.of(one.get(), two.get())).as(sql).containsExactlyInAnyOrder("1", "2");
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(stateIn(clinic, "update opd.treatment_course_counter set last_no = -1")).isEqualTo("23514");
        assertThat(stateIn(clinic, "update billing.receipt_counter set last_no = -1")).isEqualTo("23514");
        assertThat(stateIn(clinic, "delete from opd.treatment_course_counter")).isEqualTo("42501");
        assertThat(stateIn(clinic, "delete from billing.receipt_counter")).isEqualTo("42501");
    }

    // ================= S15 to S17 · billing =================

    private String paymentInsert(String receipt, String type, String amount, String sitting, String key, Rec rec) {
        return "insert into billing.course_payment (course_id, patient_id, receipt_no, type, mode, amount_paise, against_sitting_id, against_label, idempotency_key, request_hash, recorded_by) "
                + "values (gen_random_uuid(), '" + rec.patientId() + "', " + receipt + ", '" + type + "', 'CASH', " + amount + ", " + sitting + ", 'Against', '" + key
                + "', 'abcdefabcdefabcdefabcdef', '" + rec.clinic().ownerUserId() + "')";
    }

    @Test
    void aCoursePaymentIsWholeRupeesWithItsReceiptNumberAndKeyAndIsAppendOnlyForTheAppAndTheOwner() throws Exception {
        Rec a = newRec();
        Rec b = newRec();
        Clinic c = a.clinic();
        assertThat(stateIn(c, paymentInsert("1", "ADVANCE", "10000", "null", "key-aaaa-0001", a))).isEqualTo("ACCEPTED");
        for (String amount : new String[] {"150", "0", "-100", "99", "10000000100", "100.5"}) {
            assertThat(stateIn(c, paymentInsert("1", "ADVANCE", amount, "null", "key-aaaa-0001", a))).as(amount).isIn("23514", "22P02");
        }
        assertThat(stateIn(c, paymentInsert("1", "ADVANCE", "100", "null", "key-aaaa-0001", a))).as("the minimum is one rupee").isEqualTo("ACCEPTED");
        assertThat(stateIn(c, paymentInsert("1", "ADVANCE", "10000000000", "null", "key-aaaa-0001", a))).as("the maximum is Rs 10 crore").isEqualTo("ACCEPTED");
        assertThat(stateIn(c, paymentInsert("1", "IPD_DEPOSIT", "10000", "null", "key-aaaa-0001", a))).as("an IPD deposit names its sitting").isEqualTo("23514");
        assertThat(stateIn(c, paymentInsert("1", "IPD_DEPOSIT", "10000", "gen_random_uuid()", "key-aaaa-0001", a))).isEqualTo("ACCEPTED");
        assertThat(stateIn(c, paymentInsert("1", "REFUND", "10000", "null", "key-aaaa-0001", a))).isEqualTo("23514");
        assertThat(stateIn(c, paymentInsert("0", "ADVANCE", "10000", "null", "key-aaaa-0001", a))).isEqualTo("23514");
        assertThat(stateIn(c, paymentInsert("1", "ADVANCE", "10000", "null", "short", a))).as("a key of fewer than 8 characters").isEqualTo("23514");
        assertThat(stateIn(c, paymentInsert("1", "ADVANCE", "10000", "null", "has space in it", a))).isEqualTo("23514");
        assertThat(stateIn(c, paymentInsert("1", "ADVANCE", "10000", "null", "k".repeat(65), a))).isEqualTo("23514");
        assertThat(stateIn(c, paymentInsert("1", "ADVANCE", "10000", "null", "key-aaaa-0001", b))).as("a patient of another clinic").isEqualTo("23503");
        commit(c.tenantId(), paymentInsert("1", "ADVANCE", "10000", "null", "key-aaaa-0001", a));
        assertThat(stateIn(c, paymentInsert("1", "ADVANCE", "10000", "null", "key-aaaa-0002", a))).as("the receipt number is used once per clinic").isEqualTo("23505");
        assertThat(stateIn(c, paymentInsert("2", "ADVANCE", "10000", "null", "key-aaaa-0001", a))).as("the key is used once per clinic").isEqualTo("23505");
        assertThat(stateIn(b.clinic(), paymentInsert("1", "ADVANCE", "10000", "null", "key-aaaa-0001", b))).as("another clinic: same receipt number and same key").isEqualTo("ACCEPTED");
        assertThat(stateIn(c, "insert into billing.course_payment (course_id, patient_id, receipt_no, type, mode, amount_paise, against_label, idempotency_key, request_hash, recorded_by) values "
                + "(gen_random_uuid(), '" + a.patientId() + "', 5, 'ADVANCE', 'CHEQUE', 10000, 'x', 'key-aaaa-0005', 'abcdefabcdefabcdefabcdef', '" + c.ownerUserId() + "')")).isEqualTo("23514");
        // append only
        assertThat(stateIn(c, "update billing.course_payment set amount_paise = 100")).isEqualTo("42501");
        assertThat(stateIn(c, "delete from billing.course_payment")).isEqualTo("42501");
        assertThat(stateIn(c, "truncate billing.course_payment")).isEqualTo("42501");
        assertThat(db().asMigrator(cn -> expectError(cn, "truncate billing.course_payment")).message()).as("the table owner cannot truncate either").isNotBlank();
        assertThat(stateIn(c, "update billing.course_payment set recorded_at = now()")).isEqualTo("42501");
    }

    @Test
    void aCreditTransferIsOneRowPerAdmissionAndPerSittingUpToTenLakhAndAppendOnly() throws Exception {
        Rec a = newRec();
        Clinic c = a.clinic();
        String insert = "insert into billing.course_credit_transfer (course_id, sitting_id, admission_id, amount_paise, transferred_by) values (gen_random_uuid(), %s, %s, %s, '" + c.ownerUserId() + "')";
        assertThat(stateIn(c, String.format(insert, "gen_random_uuid()", "gen_random_uuid()", "10000"))).isEqualTo("ACCEPTED");
        for (String amount : new String[] {"150", "0", "-100", "100000100"}) {
            assertThat(stateIn(c, String.format(insert, "gen_random_uuid()", "gen_random_uuid()", amount))).as(amount).isEqualTo("23514");
        }
        assertThat(stateIn(c, String.format(insert, "gen_random_uuid()", "gen_random_uuid()", "100000000"))).as("exactly Rs 10,00,000").isEqualTo("ACCEPTED");
        UUID admission = UUID.randomUUID();
        UUID sitting = UUID.randomUUID();
        commit(c.tenantId(), String.format(insert, "'" + sitting + "'", "'" + admission + "'", "10000"));
        assertThat(stateIn(c, String.format(insert, "gen_random_uuid()", "'" + admission + "'", "10000"))).as("one transfer per admission").isEqualTo("23505");
        assertThat(stateIn(c, String.format(insert, "'" + sitting + "'", "gen_random_uuid()", "10000"))).as("one transfer per sitting").isEqualTo("23505");
        assertThat(stateIn(c, "update billing.course_credit_transfer set amount_paise = 100")).isEqualTo("42501");
        assertThat(stateIn(c, "delete from billing.course_credit_transfer")).isEqualTo("42501");
        assertThat(stateIn(c, "truncate billing.course_credit_transfer")).isEqualTo("42501");
    }

    // ================= S18 · the EMR bill-line columns =================

    @Test
    void anEmrBillLineHasBothSourceColumnsOrNeitherAndOneSourceKeyPerBillAndTheAppUpdatesItsQuantityAndRemovesIt() throws Exception {
        Rec rec = newRec();
        Clinic c = rec.clinic();
        String service = serviceId(c, "Counter test", 80);
        Staff doctor = rec.doctor();
        ok(org.springframework.http.HttpMethod.POST, BILLING + "/bills/" + rec.visitId() + "/items", c.owner(), "{\"serviceId\":\"" + service + "\",\"qty\":1}", 200);
        JsonNode second = issueVisit(c, doctor.membershipId());
        ok(org.springframework.http.HttpMethod.POST, BILLING + "/bills/" + second.get("id").asText() + "/items", c.owner(), "{\"serviceId\":\"" + service + "\",\"qty\":1}", 200);
        List<String> bills = new ArrayList<>();
        rows(c, "select id::text from billing.bill order by id").forEach(r -> bills.add(r.get(0)));
        assertThat(bills).hasSize(2);
        // V2026_12_12_1200 (ck_bill_item_service_or_plan): a line without a service is only an EMR_PLAN line, so these lines name the service
        String insert = "insert into billing.bill_item (bill_id, service_id, name, amount_paise, qty, source_kind, source_key, created_by) values (?::uuid, '" + service + "', 'Dressing', 15000, 1, %s, %s, '" + c.ownerUserId() + "')";
        assertThat(stateIn(c, String.format(insert, "'EMR'", "null"), bills.get(0))).as("a kind with no key").isEqualTo("23514");
        assertThat(stateIn(c, String.format(insert, "null", "'k1'"), bills.get(0))).as("a key with no kind").isEqualTo("23514");
        assertThat(stateIn(c, String.format(insert, "'X'", "'k1'"), bills.get(0))).isEqualTo("23514");
        assertThat(stateIn(c, String.format(insert, "'EMR'", "''"), bills.get(0))).isEqualTo("23514");
        // SAH-51-68 (V2026_12_12_1200): the key may be 80 characters long now (EMR_PLAN keys are longer), 81 is refused
        assertThat(stateIn(c, String.format(insert, "'EMR'", "'" + "k".repeat(41) + "'"), bills.get(0))).isEqualTo("ACCEPTED");
        assertThat(stateIn(c, String.format(insert, "'EMR'", "'" + "k".repeat(81) + "'"), bills.get(0))).isEqualTo("23514");
        assertThat(stateIn(c, String.format(insert, "'EMR'", "'" + "k".repeat(40) + "'"), bills.get(0))).isEqualTo("ACCEPTED");
        commit(c.tenantId(), String.format(insert, "'EMR'", "'k1'"), bills.get(0));
        assertThat(stateIn(c, String.format(insert, "'EMR'", "'k1'"), bills.get(0))).as("the same item twice on one bill").isEqualTo("23505");
        assertThat(stateIn(c, String.format(insert, "'EMR'", "'k1'"), bills.get(1))).as("the same key on another bill").isEqualTo("ACCEPTED");
        assertThat(stateIn(c, String.format(insert, "null", "null"), bills.get(0))).as("a counter line twice is fine").isEqualTo("ACCEPTED");
        commit(c.tenantId(), String.format(insert, "null", "null"), bills.get(0));
        assertThat(stateIn(c, String.format(insert, "null", "null"), bills.get(0))).isEqualTo("ACCEPTED");
        // the counter line made by the API is a counter line: both columns null
        assertThat(count(c, "select count(*) from billing.bill_item where source_kind is null and source_key is null and service_id is not null")).isEqualTo(3);
        // the app updates the quantity and deletes an EMR line; it cannot take the key off
        assertThat(stateIn(c, "update billing.bill_item set qty = 3 where source_key = 'k1'")).isEqualTo("ACCEPTED");
        assertThat(stateIn(c, "update billing.bill_item set qty = 21 where source_key = 'k1'")).isEqualTo("23514");
        assertThat(stateIn(c, "delete from billing.bill_item where source_key = 'k1'")).isEqualTo("ACCEPTED");
        assertThat(stateIn(c, "update billing.bill_item set source_kind = null where source_key = 'k1'")).as("one column alone breaks the pair").isEqualTo("23514");
    }

    // ================= S21, S22 · tenant isolation, privileges, catalog =================

    @Test
    void everyNewClinicTableIsInvisibleToAnotherClinicAndToNoClinicAtAllAndRefusesAnInsertForAnotherClinic() throws Exception {
        Rec a = newRec();
        Rec b = newRec();
        // rows in every one of the eight tables of clinic A
        JsonNode form = draftForm(a.clinic());
        JsonNode course = threeSittingCourse(a);
        collect(a.clinic(), course, "ADVANCE", 100000, "CASH");
        commit(a.clinic().tenantId(), "insert into billing.course_credit_transfer (course_id, sitting_id, admission_id, amount_paise, transferred_by) values "
                + "(gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 10000, '" + a.clinic().ownerUserId() + "')");
        assertThat(form.get("liveVersionNo").asInt()).isEqualTo(1);
        for (String table : NEW_CLINIC_TABLES) {
            assertThat(count(a.clinic(), "select count(*) from " + table)).as("clinic A has rows in " + table).isPositive();
            assertThat(count(b.clinic(), "select count(*) from " + table)).as("clinic B sees none of " + table).isZero();
            assertThat(globalCount("select count(*) from " + table)).as("no clinic bound: none of " + table).isZero();
        }
        // an insert that names clinic A while clinic B is bound is refused by the policy
        String foreign = a.clinic().tenantId().toString();
        assertThat(stateIn(b.clinic(), "insert into opd.emr_form (tenant_id, form_key, name, created_by, draft_definition) values ('" + foreign + "', 'f_zzzzzzzzzz', 'X', '"
                + b.clinic().ownerUserId() + "', " + DEF + ")")).isEqualTo("42501");
        assertThat(stateIn(b.clinic(), "insert into opd.treatment_course_counter (tenant_id, last_no) values ('" + foreign + "', 1)")).isEqualTo("42501");
        assertThat(stateIn(b.clinic(), "insert into billing.receipt_counter (tenant_id, last_no) values ('" + foreign + "', 1)")).isEqualTo("42501");
        assertThat(stateIn(b.clinic(), "insert into billing.course_credit_transfer (tenant_id, course_id, sitting_id, admission_id, amount_paise, transferred_by) values ('" + foreign
                + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 10000, '" + b.clinic().ownerUserId() + "')")).isEqualTo("42501");
        assertThat(stateIn(b.clinic(), paymentInsert("1", "ADVANCE", "10000", "null", "key-bbbb-0001", b).replace("insert into billing.course_payment (",
                "insert into billing.course_payment (tenant_id, ").replace("values (", "values ('" + foreign + "', "))).isEqualTo("42501");
        // and an update or delete from clinic B reaches none of A's rows
        long updated = db().inTenant(b.clinic().tenantId(), c -> DbAccess.count(c, "with u as (update opd.emr_form set name = 'Taken' returning 1) select count(*) from u"));
        long deleted = db().inTenant(b.clinic().tenantId(), c -> DbAccess.count(c, "with d as (delete from billing.bill_item returning 1) select count(*) from d"));
        assertThat(updated).isZero();
        assertThat(deleted).isZero();
        assertThat(row(a.clinic(), "select name from opd.emr_form").get(0)).isNotEqualTo("Taken");
    }

    private JsonNode draftForm(Clinic clinic) throws Exception {
        return liveSimpleForm(clinic, "Isolation form", formField("note", "LONG_TEXT", "Note"));
    }

    @Test
    void everyNewClinicTableHasEnabledAndForcedRowLevelSecurityItsPoliciesATenantColumnWithADefaultAndNoDeleteOrTruncateForTheApp() throws Exception {
        for (String table : NEW_CLINIC_TABLES) {
            String schema = table.substring(0, table.indexOf('.'));
            String name = table.substring(table.indexOf('.') + 1);
            assertThat(globalRows("select relrowsecurity::text || ':' || relforcerowsecurity::text from pg_class where oid = '" + table + "'::regclass").get(0).get(0)).as(table).isEqualTo("true:true");
            long policies = globalCount("select count(*) from pg_policy where polrelid = '" + table + "'::regclass");
            boolean split = table.endsWith("emr_form_version") || table.endsWith("course_payment") || table.endsWith("course_credit_transfer");
            assertThat(policies).as(table + " policies").isEqualTo(split ? 2 : 1);
            assertThat(globalRows("select is_nullable || ':' || coalesce(column_default, '') from information_schema.columns where table_schema = '" + schema + "' and table_name = '" + name
                    + "' and column_name = 'tenant_id'").get(0).get(0)).as(table).isEqualTo("NO:platform.current_tenant()");
            assertThat(globalRows("select count(*)::text from pg_constraint where conrelid = '" + table + "'::regclass and contype = 'f' and confrelid = 'platform.tenant'::regclass").get(0).get(0)).as(table + " tenant foreign key").isEqualTo("1");
            for (String privilege : new String[] {"DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
                assertThat(globalRows("select has_table_privilege('sahaj_app', '" + table + "', '" + privilege + "')").get(0).get(0)).as(table + " " + privilege).isEqualTo("f");
            }
            assertThat(globalRows("select has_table_privilege('public', '" + table + "', 'SELECT')").get(0).get(0)).as(table + " public").isEqualTo("f");
            assertThat(globalRows("select obj_description('" + table + "'::regclass, 'pg_class') is not null").get(0).get(0)).as(table + " comment").isEqualTo("t");
            long tenantIndexes = globalCount("select count(*) from pg_index i join pg_attribute a on a.attrelid = i.indrelid and a.attnum = i.indkey[0] where i.indrelid = '" + table
                    + "'::regclass and a.attname = 'tenant_id'");
            assertThat(tenantIndexes).as(table + " has an index that starts with tenant_id").isPositive();
        }
    }

    @Test
    void theColumnGrantsOfTheNewClinicTablesAreExactlyTheWorkingColumnsAndTheAppendOnlyTablesCanNeverBeUpdated() throws Exception {
        assertThat(updatable("opd.emr_form")).containsExactlyInAnyOrder("name", "status", "live_version_no", "draft_version_no", "draft_state", "draft_definition",
                "sent_back_reason", "submitted_at", "submitted_by", "revision", "updated_at", "updated_by");
        assertThat(updatable("opd.treatment_course")).containsExactlyInAnyOrder("status", "revision", "updated_at", "updated_by");
        assertThat(updatable("opd.treatment_course_sitting")).containsExactlyInAnyOrder("encounter_kind", "add_option_key", "planned_date", "items", "charge_paise", "note",
                "opd_visit_id", "ipd_admission_id", "admission_requested", "done_at", "updated_at", "updated_by");
        for (String table : new String[] {"opd.emr_form_version", "billing.course_payment", "billing.course_credit_transfer"}) {
            assertThat(updatable(table)).as(table).isEmpty();
            assertThat(globalRows("select has_table_privilege('sahaj_app', '" + table + "', 'UPDATE')").get(0).get(0)).isEqualTo("f");
            assertThat(globalRows("select has_table_privilege('sahaj_app', '" + table + "', 'INSERT')").get(0).get(0)).isEqualTo("t");
        }
        for (String table : new String[] {"opd.treatment_course_counter", "billing.receipt_counter"}) {
            assertThat(globalRows("select has_table_privilege('sahaj_app', '" + table + "', 'UPDATE')").get(0).get(0)).as(table).isEqualTo("t");
        }
        assertThat(globalRows("select has_column_privilege('sahaj_app', 'billing.bill_item', 'qty', 'UPDATE')").get(0).get(0)).isEqualTo("t");
        assertThat(globalRows("select has_table_privilege('sahaj_app', 'billing.bill_item', 'DELETE')").get(0).get(0)).isEqualTo("t");
        assertThat(globalRows("select has_column_privilege('sahaj_app', 'ipd.admission', 'treatment_course_id', 'INSERT')").get(0).get(0)).isEqualTo("t");
        assertThat(globalRows("select has_column_privilege('sahaj_app', 'ipd.admission', 'treatment_course_id', 'UPDATE')").get(0).get(0)).isEqualTo("f");
        assertThat(globalRows("select has_column_privilege('sahaj_app', 'ipd.deposit', 'source', 'INSERT')").get(0).get(0)).isEqualTo("t");
        assertThat(globalRows("select has_column_privilege('sahaj_app', 'ipd.deposit', 'course_credit_transfer_id', 'UPDATE')").get(0).get(0)).isEqualTo("f");
    }

    private List<String> updatable(String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        String schema = table.substring(0, table.indexOf('.'));
        String name = table.substring(table.indexOf('.') + 1);
        for (List<String> r : globalRows("select column_name from information_schema.columns where table_schema = '" + schema + "' and table_name = '" + name + "'")) {
            if ("t".equals(globalRows("select has_column_privilege('sahaj_app', '" + table + "', '" + r.get(0) + "', 'UPDATE')").get(0).get(0))) {
                columns.add(r.get(0));
            }
        }
        return columns;
    }

    @Test
    void theEightPhase3MigrationFilesWereAppliedInOrderWithoutFailureOnTopOfTheEarlierHistory() throws Exception {
        List<List<String>> applied = db().asMigrator(c -> allRows(c, "select version, success::text, (checksum is not null)::text from platform.flyway_schema_history "
                + "where version like '2026.12.10.%' order by installed_rank"));
        assertThat(applied.stream().map(r -> r.get(0)).toList()).containsExactly("2026.12.10.1000", "2026.12.10.1010", "2026.12.10.1020", "2026.12.10.1100",
                "2026.12.10.1110", "2026.12.10.1120", "2026.12.10.1200", "2026.12.10.1300");
        for (List<String> r : applied) {
            assertThat(r.get(1)).as(r.get(0)).isEqualTo("true");
            assertThat(r.get(2)).isEqualTo("true");
        }
        long failed = db().asMigrator(c -> DbAccess.count(c, "select count(*) from platform.flyway_schema_history where not success"));
        long before = db().asMigrator(c -> DbAccess.count(c, "select count(*) from platform.flyway_schema_history where version = '2026.12.09.1000'"));
        assertThat(failed).isZero();
        assertThat(before).isEqualTo(1);
    }

    // ================= S22 (Correction V2026_12_13_1010, DEC-051 Q1) · starter score rules use "kind" =================

    /**
     * docs/database/SAH-48-59.md, check S22 of the correction note: after V2026_12_13_1010 no starter score rule has the key
     * "type", the diabetic foot starter has its two rules under "kind", the immutable trigger is enabled again, and the shipped
     * starter still cannot be changed by the app or by the table owner (note: the numbering S22 of the original table is the
     * privilege check, covered above; this is the S22 of the correction).
     */
    @Test
    void s22TheStarterScoreRulesUseKindTheMigrationRanAndTheStarterTableIsStillImmutable() throws Exception {
        assertThat(globalCount("select count(*) from opd.emr_form_starter s, jsonb_array_elements(s.definition -> 'fields') f, "
                + "jsonb_array_elements(case when jsonb_typeof(f -> 'score' -> 'rules') = 'array' then f -> 'score' -> 'rules' else '[]'::jsonb end) r "
                + "where jsonb_exists(r, 'type')")).as("no score rule keeps the key type").isZero();
        assertThat(globalCount("select count(*) from opd.emr_form_starter s, jsonb_array_elements(s.definition -> 'fields') f, "
                + "jsonb_array_elements(case when jsonb_typeof(f -> 'score' -> 'rules') = 'array' then f -> 'score' -> 'rules' else '[]'::jsonb end) r "
                + "where not jsonb_exists(r, 'kind')")).as("every score rule has a kind").isZero();
        List<List<String>> rules = globalRows("select r ->> 'kind' from opd.emr_form_starter s, jsonb_array_elements(s.definition -> 'fields') f, "
                + "jsonb_array_elements(case when jsonb_typeof(f -> 'score' -> 'rules') = 'array' then f -> 'score' -> 'rules' else '[]'::jsonb end) r "
                + "where s.starter_key = 'diabetic_foot'");
        assertThat(rules.stream().map(r -> r.get(0)).toList()).as("the diabetic foot rules keep their order")
                .containsExactly("NUMBER_BELOW", "CHOSEN_COUNT_BELOW");
        assertThat(globalRows("select tgenabled::text from pg_trigger where tgrelid = 'opd.emr_form_starter'::regclass "
                + "and tgname = 'trg_emr_form_starter_immutable'").get(0).get(0)).as("the trigger is enabled again").isEqualTo("O");
        List<List<String>> history = db().asMigrator(c -> allRows(c, "select success::text from platform.flyway_schema_history where version = '2026.12.13.1010'"));
        assertThat(history).as("the migration is in the Flyway history and succeeded").hasSize(1);
        assertThat(history.get(0).get(0)).isEqualTo("true");
        db().asMigrator(c -> {
            assertThat(expectError(c, "update opd.emr_form_starter set name = name").message()).contains("immutable");
            return null;
        });
        db().asApp(c -> {
            assertThat(expectError(c, "update opd.emr_form_starter set name = name").state()).isEqualTo("42501");
            return null;
        });
    }
}
