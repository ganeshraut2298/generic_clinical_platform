package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-75 database cases 9 (sitting checks) and 11 (cancer journey append-only and row level security) of
 * docs/database/SAH-75.md on {@code sahaj_test}, as {@code sahaj_app} with the clinic bound (the migrator owns the tables but
 * the policies name only the app). Every probe is rolled back to a savepoint; the rows that must exist for the next probe are
 * committed in fresh clinics made by the existing helpers. Throwaway data only. Test plan: docs/test-plans/SAH-75.md.
 */
@OnTestDatabase
class Sah75TenantTablesIT extends EmrPhase3Support {

    private String state(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, "savepoint probe");
            try {
                DbAccess.execute(c, sql, params);
            } catch (SQLException e) {
                DbAccess.execute(c, "rollback to savepoint probe");
                String name = e.getMessage() == null ? "" : e.getMessage();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("constraint \"([a-z0-9_]+)\"").matcher(name);
                return e.getSQLState() + (m.find() ? "|" + m.group(1) : "");
            }
            DbAccess.execute(c, "rollback to savepoint probe");
            return "ACCEPTED";
        });
    }

    // ================= case 9: the sitting checks =================

    private static final String SITTING = "insert into opd.treatment_course_sitting (course_id, sitting_no, encounter_kind, add_option_key, %s created_by) "
            + "values (?::uuid, 2, 'OPD', 'OPD_SITTING', %s ?::uuid)";

    private String sittingInsert(Clinic c, String courseId, String columns, String values) throws SQLException {
        return state(c, String.format(SITTING, columns, values), courseId, c.ownerUserId().toString());
    }

    @Test
    void theSittingStateChecksRefuseHalfPairsWrongSourcesAndImpossibleCombinationsAndAcceptTheLegalOnesIncludingTheOldShape() throws Exception {
        Rec rec = newRec();
        Clinic c = rec.clinic();
        JsonNode course = newCourse(rec, "DENTAL", "[" + sittingJson("opd_sitting", 100000L, null) + "]");
        String courseId = course.get("id").asText();
        String m = "gen_random_uuid()";
        String pairMissed = "ck_treatment_course_sitting_missed_pair";
        String pairRemoved = "ck_treatment_course_sitting_removed_pair";
        // old shape: none of the new columns
        assertThat(sittingInsert(c, courseId, "", "")).isEqualTo("ACCEPTED");
        assertThat(sittingInsert(c, courseId, "missed_at,", "now(),")).isEqualTo("23514|" + pairMissed);
        assertThat(sittingInsert(c, courseId, "missed_by_membership_id,", m + ",")).isEqualTo("23514|" + pairMissed);
        assertThat(sittingInsert(c, courseId, "removed_at,", "now(),")).isEqualTo("23514|" + pairRemoved);
        assertThat(sittingInsert(c, courseId, "removed_by_membership_id,", m + ",")).isEqualTo("23514|" + pairRemoved);
        assertThat(sittingInsert(c, courseId, "done_at, done_source,", "now(), 'X',")).isEqualTo("23514|ck_treatment_course_sitting_done_source");
        assertThat(sittingInsert(c, courseId, "done_source,", "'SIGN',")).isEqualTo("23514|ck_treatment_course_sitting_done_source_needs_done");
        assertThat(sittingInsert(c, courseId, "done_by_membership_id,", m + ",")).isEqualTo("23514|ck_treatment_course_sitting_done_by_needs_done");
        assertThat(sittingInsert(c, courseId, "done_at, done_source,", "now(), 'MARK_DONE',")).isEqualTo("23514|ck_treatment_course_sitting_mark_done_by");
        assertThat(sittingInsert(c, courseId, "done_at, done_source, done_by_membership_id,", "now(), 'MARK_DONE', " + m + ",")).isEqualTo("ACCEPTED");
        assertThat(sittingInsert(c, courseId, "done_at, done_source,", "now(), 'SIGN',")).as("SIGN needs no person").isEqualTo("ACCEPTED");
        assertThat(sittingInsert(c, courseId, "removed_at, removed_by_membership_id, done_at,", "now(), " + m + ", now(),"))
                .isEqualTo("23514|ck_treatment_course_sitting_removed_not_done");
        assertThat(sittingInsert(c, courseId, "missed_at, missed_by_membership_id, done_at,", "now(), " + m + ", now(),"))
                .isEqualTo("23514|ck_treatment_course_sitting_missed_state");
        assertThat(sittingInsert(c, courseId, "missed_at, missed_by_membership_id, removed_at, removed_by_membership_id,", "now(), " + m + ", now(), " + m + ","))
                .isEqualTo("23514|ck_treatment_course_sitting_missed_state");
        assertThat(sittingInsert(c, courseId, "missed_at, missed_by_membership_id,", "now(), " + m + ",")).as("missed alone").isEqualTo("ACCEPTED");
        assertThat(sittingInsert(c, courseId, "removed_at, removed_by_membership_id,", "now(), " + m + ",")).as("removed alone").isEqualTo("ACCEPTED");
        // regimen key: the library key passes, an unknown key fails the foreign key
        assertThat(sittingInsert(c, courseId, "regimen_key,", "'NOPE',")).isEqualTo("23503|fk_treatment_course_sitting_regimen");
        assertThat(sittingInsert(c, courseId, "regimen_key,", "'AC',")).isEqualTo("ACCEPTED");
        // clearing missed (rebook) passes; clearing half of the pair fails
        commit(c.tenantId(), String.format(SITTING, "missed_at, missed_by_membership_id,", "now(), gen_random_uuid(),"), courseId, c.ownerUserId().toString());
        String sittingNo2 = "update opd.treatment_course_sitting set %s where sitting_no = 2";
        assertThat(state(c, String.format(sittingNo2, "missed_at = null, missed_by_membership_id = null"))).as("rebook").isEqualTo("ACCEPTED");
        assertThat(state(c, String.format(sittingNo2, "missed_at = null"))).isEqualTo("23514|" + pairMissed);
        // the old-shape sitting of the course made by the endpoint: new columns read null, estimate_signed false
        assertThat(row(c, "select done_source, missed_at::text, removed_at::text, regimen_key, done_by_membership_id::text from opd.treatment_course_sitting where sitting_no = 1"))
                .isEqualTo(java.util.Arrays.asList(null, null, null, null, null));
        assertThat(row(c, "select estimate_signed::text from opd.treatment_course where id = ?::uuid", courseId).get(0)).isEqualTo("false");
    }

    @Test
    void anotherClinicCannotSeeTheSittingsOfTheFirstAndCannotAddOneToItsCourse() throws Exception {
        Rec a = newRec();
        JsonNode course = newCourse(a, "DENTAL", "[" + sittingJson("opd_sitting", 100000L, null) + "]");
        Rec b = newRec();
        assertThat(count(b.clinic(), "select count(*) from opd.treatment_course_sitting where course_id = ?::uuid", course.get("id").asText())).isZero();
        assertThat(sittingInsert(b.clinic(), course.get("id").asText(), "", "")).as("a course of another clinic").isEqualTo("23503|fk_treatment_course_sitting_course");
    }

    // ================= case 11: the cancer journey =================

    private static final String JOURNEY = "insert into opd.emr_cancer_journey (patient_id, version, %s recorded_by_membership_id, recorded_by_user_id) "
            + "values (?::uuid, ?, %s gen_random_uuid(), gen_random_uuid())";

    private String journey(Clinic c, UUID patient, int version, String columns, String values) throws SQLException {
        return state(c, String.format(JOURNEY, columns, values), patient.toString(), version);
    }

    private void saveJourney(Clinic c, UUID patient, int version) throws SQLException {
        commit(c.tenantId(), String.format(JOURNEY, "stage_group, intent,", "'IIIA', 'CURATIVE',"), patient.toString(), version);
    }

    @Test
    void theJourneyAppendsVersionsRefusesADuplicateVersionAndEveryValueOutsideItsListOrLimitAndAcceptsTheLimits() throws Exception {
        Clinic a = newClinic();
        UUID patient = newPatient(a);
        saveJourney(a, patient, 1);
        saveJourney(a, patient, 2);
        assertThat(rows(a, "select version::text from opd.emr_cancer_journey where patient_id = ?::uuid order by version desc", patient.toString()))
                .containsExactly(List.of("2"), List.of("1"));
        assertThat(journey(a, patient, 2, "", "")).as("a racing save of the same version").isEqualTo("23505|uq_emr_cancer_journey_tenant_patient_version");
        assertThat(journey(a, patient, 3, "", "")).as("every value is optional").isEqualTo("ACCEPTED");
        assertThat(journey(a, patient, 0, "", "")).isEqualTo("23514|ck_emr_cancer_journey_version");
        assertThat(journey(a, patient, 3, "ki67_pct,", "101,")).isEqualTo("23514|ck_emr_cancer_journey_ki67_pct");
        assertThat(journey(a, patient, 3, "ki67_pct,", "-1,")).isEqualTo("23514|ck_emr_cancer_journey_ki67_pct");
        assertThat(journey(a, patient, 3, "ki67_pct,", "0,")).isEqualTo("ACCEPTED");
        assertThat(journey(a, patient, 3, "ki67_pct,", "100,")).isEqualTo("ACCEPTED");
        assertThat(journey(a, patient, 3, "line_no,", "0,")).startsWith("23514|");
        assertThat(journey(a, patient, 3, "line_no,", "11,")).startsWith("23514|");
        assertThat(journey(a, patient, 3, "line_no,", "1,")).isEqualTo("ACCEPTED");
        assertThat(journey(a, patient, 3, "line_no,", "10,")).isEqualTo("ACCEPTED");
        assertThat(journey(a, patient, 3, "stage_group,", "'V',")).startsWith("23514|");
        assertThat(journey(a, patient, 3, "intent,", "'X',")).startsWith("23514|");
        assertThat(journey(a, patient, 3, "setting,", "'X',")).startsWith("23514|");
        assertThat(journey(a, patient, 3, "tnm_text,", "'" + "t".repeat(41) + "',")).startsWith("23514|");
        assertThat(journey(a, patient, 3, "tnm_text,", "'',")).as("an empty value is stored as null, never as an empty string").startsWith("23514|");
        assertThat(journey(a, patient, 3, "tnm_text,", "'" + "t".repeat(40) + "',")).isEqualTo("ACCEPTED");
        assertThat(journey(a, patient, 3, "markers_text,", "'" + "m".repeat(81) + "',")).startsWith("23514|");
        assertThat(journey(a, patient, 3, "genetics_text,", "'" + "g".repeat(61) + "',")).startsWith("23514|");
        for (String stage : new String[] {"0", "I", "IA", "IB", "IC", "II", "IIA", "IIB", "IIC", "III", "IIIA", "IIIB", "IIIC", "IV", "IVA", "IVB", "IVC"}) {
            assertThat(journey(a, patient, 3, "stage_group,", "'" + stage + "',")).as(stage).isEqualTo("ACCEPTED");
        }
        for (String intent : new String[] {"CURATIVE", "PALLIATIVE"}) {
            assertThat(journey(a, patient, 3, "intent,", "'" + intent + "',")).isEqualTo("ACCEPTED");
        }
        for (String setting : new String[] {"NEOADJUVANT", "ADJUVANT", "DEFINITIVE", "ADVANCED"}) {
            assertThat(journey(a, patient, 3, "setting,", "'" + setting + "',")).isEqualTo("ACCEPTED");
        }
    }

    @Test
    void theJourneyIsAppendOnlyForTheAppAndTheTriggerRefusesTruncateForTheOwnerAndTheIdTenantAndTimeCannotBeInserted() throws Exception {
        Clinic a = newClinic();
        UUID patient = newPatient(a);
        saveJourney(a, patient, 1);
        assertThat(state(a, "update opd.emr_cancer_journey set stage_group = 'IV'")).isEqualTo("42501");
        assertThat(state(a, "delete from opd.emr_cancer_journey")).isEqualTo("42501");
        assertThat(state(a, "truncate opd.emr_cancer_journey")).isEqualTo("42501");
        SqlError owner = db().asMigrator(c -> expectError(c, "truncate opd.emr_cancer_journey"));
        assertThat(owner.state()).as("the trigger refuses the owner too").isEqualTo("42501");
        assertThat(owner.message()).contains("TRUNCATE refused");
        for (String column : new String[] {"id, ", "tenant_id, ", "recorded_at, "}) {
            String value = column.startsWith("recorded_at") ? "now(), " : column.startsWith("id") ? "gen_random_uuid(), " : "gen_random_uuid(), ";
            assertThat(state(a, "insert into opd.emr_cancer_journey (" + column + "patient_id, version, recorded_by_membership_id, recorded_by_user_id) values ("
                    + value + "?::uuid, 9, gen_random_uuid(), gen_random_uuid())", patient.toString())).as(column).isEqualTo("42501");
        }
        assertThat(globalRows("select c.relrowsecurity::text, c.relforcerowsecurity::text from pg_class c where c.oid = 'opd.emr_cancer_journey'::regclass").get(0))
                .containsExactly("true", "true");
        assertThat(globalRows("select polname, polcmd::text from pg_policy where polrelid = 'opd.emr_cancer_journey'::regclass order by polname"))
                .containsExactly(List.of("p_emr_cancer_journey_insert", "a"), List.of("p_emr_cancer_journey_select", "r"));
        assertThat(globalRows("select tgname from pg_trigger where tgrelid = 'opd.emr_cancer_journey'::regclass and not tgisinternal order by tgname").stream().map(r -> r.get(0)).toList())
                .containsExactly("trg_emr_cancer_journey_no_truncate", "trg_emr_cancer_journey_no_update_delete");
    }

    @Test
    void anotherClinicSeesNoJourneyRowAndNoClinicBoundSeesNoneAndAPatientOfAnotherClinicIsRefused() throws Exception {
        Clinic a = newClinic();
        UUID patientA = newPatient(a);
        saveJourney(a, patientA, 1);
        Clinic b = newClinic();
        UUID patientB = newPatient(b);
        assertThat(count(a, "select count(*) from opd.emr_cancer_journey")).isEqualTo(1);
        assertThat(count(b, "select count(*) from opd.emr_cancer_journey")).as("clinic B sees none of A's rows").isZero();
        assertThat(count(b, "select count(*) from opd.emr_cancer_journey where patient_id = ?::uuid", patientA.toString())).isZero();
        long unbound = db().asApp(c -> DbAccess.count(c, "select count(*) from opd.emr_cancer_journey"));
        assertThat(unbound).as("no clinic bound").isZero();
        assertThat(journey(b, patientA, 1, "", "")).as("a patient of another clinic").isEqualTo("23503|fk_emr_cancer_journey_patient");
        assertThat(journey(b, patientB, 1, "", "")).as("B's own patient").isEqualTo("ACCEPTED");
    }
}
