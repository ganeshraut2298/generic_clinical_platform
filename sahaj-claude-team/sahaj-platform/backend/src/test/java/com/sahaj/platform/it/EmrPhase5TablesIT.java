package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The SQL of SAH-51, SAH-67 and SAH-68 on {@code sahaj_test} as {@code sahaj_app} (docs/database/SAH-51-68.md, QA cases 1 to 13):
 * the sign columns and their checks on {@code opd.emr_record}, the signed-record immutability trigger and its allow-list, the
 * edit hold columns, the append-only addendum table, the admission request table, the Emergency visit type and triage checks,
 * the chemotherapy order frozen with its signed record, the widened appointment, follow-up, bill item and patient columns,
 * the grants and the row level security of the three new tables. Every probe runs in a transaction that is rolled back.
 * The ipd tables (round note, Emergency case, admission) are in EmrIpdPhase5TablesIT. Test plan: docs/test-plans/SAH-51-68.md
 * (T-DB-x).
 */
@OnTestDatabase
class EmrPhase5TablesIT extends EmrSignSupport {

    private static final String HASH = "repeat('a', 64)";

    /** The sign as the application writes it: one update that also clears the hold and adds 1 to the revision. */
    private static final String SIGN = "update opd.emr_record set status = 'SIGNED', signed_at = now(), signed_by = ?, "
            + "signed_by_membership_id = ?, sign_idempotency_key = 'abcdefgh12', sign_request_hash = " + HASH + ", "
            + "sign_result = '{}'::jsonb, edit_hold_membership_id = null, edit_hold_user_id = null, edit_hold_expires_at = null, "
            + "revision = revision + 1, updated_at = now(), updated_by = ? where id = ?::uuid";

    private void sign(java.sql.Connection c, Rec rec) throws java.sql.SQLException {
        expectOk(c, SIGN, rec.doctor().userId(), rec.doctor().membershipId(), rec.doctor().userId(), rec.id());
    }

    private static String set(String assignments) {
        return "update opd.emr_record set " + assignments + " where id = ?::uuid";
    }

    // ================= 1 · the sign columns and checks =================

    @Test
    void aSignedRecordNeedsItsSignerMembershipItsKeyAndNoEditHoldAndTheDraftToSignedUpdateWorks() throws Exception {
        Rec rec = newRec();
        db().inTenant(rec.clinic().tenantId(), c -> {
            String signed = "status = 'SIGNED', signed_at = now(), signed_by = ?";
            // the hold taken at open is still set: SIGNED with a hold fails
            assertThat(expectError(c, set(signed + ", signed_by_membership_id = ?, sign_idempotency_key = 'abcdefgh12'"),
                    rec.doctor().userId(), rec.doctor().membershipId(), rec.id()).state()).as("SIGNED with the hold still set").isEqualTo("23514");
            expectOk(c, set("edit_hold_membership_id = null, edit_hold_user_id = null, edit_hold_expires_at = null"), rec.id());
            assertThat(expectError(c, set(signed + ", signed_by_membership_id = ?"), rec.doctor().userId(), rec.doctor().membershipId(), rec.id())
                    .state()).as("no sign key").isEqualTo("23514");
            assertThat(expectError(c, set(signed + ", sign_idempotency_key = 'abcdefgh12'"), rec.doctor().userId(), rec.id())
                    .state()).as("no signer membership").isEqualTo("23514");
            assertThat(expectError(c, set(signed), rec.doctor().userId(), rec.id()).state()).as("neither").isEqualTo("23514");
            sign(c, rec);
            assertThat(DbAccess.count(c, "select count(*) from opd.emr_record where id = ?::uuid and status = 'SIGNED'", rec.id())).isOne();
            return null;
        });
    }

    @Test
    void everyClinicalColumnOfASignedRecordIsFrozenWith42501AndOnlyThePrintChoicesAndTheUpdateStampCanChange() throws Exception {
        Rec rec = newRec();
        UUID user = rec.doctor().userId();
        UUID membership = rec.doctor().membershipId();
        db().inTenant(rec.clinic().tenantId(), c -> {
            sign(c, rec);
            String[] frozen = {"sections = '{\"k\": 1}'::jsonb", "revision = revision + 1", "admission_advised = not admission_advised",
                    "referral_line = 'Some line'", "ui_part = 'TREATMENT'", "ui_section_key = 'rx'", "status = 'DRAFT'",
                    "signed_at = now() + interval '1 day'", "signed_by = '" + UUID.randomUUID() + "'",
                    "signed_by_membership_id = '" + UUID.randomUUID() + "'", "sign_idempotency_key = 'zzzzzzzz12'",
                    "sign_request_hash = repeat('b', 64)", "sign_result = '{\"a\": 1}'::jsonb",
                    "patient_facts_fingerprint = repeat('b', 64)", "form_refs = '[]'::jsonb", "department_name = 'Changed'",
                    "edit_hold_membership_id = '" + membership + "', edit_hold_user_id = '" + user + "', edit_hold_expires_at = now()",
                    "patient_id = null", "screen_state = '{}'::jsonb"};
            for (String assignment : frozen) {
                SqlError e = expectError(c, set(assignment), rec.id());
                assertThat(e.state()).as(assignment).isEqualTo("42501");
                assertThat(e.message()).as(assignment).contains("immutable");
            }
            expectOk(c, set("print_language = 'MR'"), rec.id());
            expectOk(c, set("paper_size = 'A5'"), rec.id());
            expectOk(c, set("updated_at = now(), updated_by = '" + user + "'"), rec.id());
            assertThat(row0(c, "select print_language, paper_size, status, revision::text from opd.emr_record where id = ?::uuid", rec.id()))
                    .containsExactly("MR", "A5", "SIGNED", String.valueOf(rec.revision() + 1));
            return null;
        });
    }

    private static List<String> row0(java.sql.Connection c, String sql, Object... params) throws java.sql.SQLException {
        return firstRow(c, sql, params);
    }

    @Test
    void aSignedRecordCannotBeDeletedAndTheSignedStateSurvivesACommittedSign() throws Exception {
        Rec rec = newRec();
        db().inTenant(rec.clinic().tenantId(), c -> {
            sign(c, rec);
            assertThat(expectError(c, "delete from opd.emr_record where id = ?::uuid", rec.id()).state()).isEqualTo("42501");
            return null;
        });
        assertThat(statusOf(rec.clinic(), rec.id())).as("the probe was rolled back").isEqualTo("DRAFT");
    }

    @Test
    void thePatientOfAnOpdRecordCannotBeChangedAfterTheSignEvenFromNullToAValue() throws Exception {
        Rec rec = newRec();
        UUID other = newPatient(rec.clinic());
        db().inTenant(rec.clinic().tenantId(), c -> {
            sign(c, rec);
            assertThat(expectError(c, set("patient_id = '" + other + "'"), rec.id()).state()).isEqualTo("42501");
            return null;
        });
    }

    @Test
    void theEditHoldColumnsAreSetAllThreeOrNoneAndTheKeyHashFingerprintAndResultHaveTheirShapes() throws Exception {
        Rec rec = newRec();
        UUID user = rec.doctor().userId();
        UUID membership = rec.doctor().membershipId();
        db().inTenant(rec.clinic().tenantId(), c -> {
            expectOk(c, set("edit_hold_membership_id = null, edit_hold_user_id = null, edit_hold_expires_at = null"), rec.id());
            assertThat(expectError(c, set("edit_hold_membership_id = '" + membership + "'"), rec.id()).state()).as("one set").isEqualTo("23514");
            assertThat(expectError(c, set("edit_hold_membership_id = '" + membership + "', edit_hold_user_id = '" + user + "'"), rec.id())
                    .state()).as("two set").isEqualTo("23514");
            assertThat(expectError(c, set("edit_hold_expires_at = now()"), rec.id()).state()).isEqualTo("23514");
            expectOk(c, set("edit_hold_membership_id = '" + membership + "', edit_hold_user_id = '" + user
                    + "', edit_hold_expires_at = now() + interval '120 seconds'"), rec.id());
            for (String bad : new String[] {"sign_idempotency_key = 'short'", "sign_idempotency_key = 'has space in it'",
                    "sign_idempotency_key = repeat('k', 65)", "sign_request_hash = 'xyz'", "sign_request_hash = repeat('A', 64)",
                    "patient_facts_fingerprint = 'nothex'", "sign_result = '[]'::jsonb",
                    "sign_result = jsonb_build_object('k', repeat('x', 9000))"}) {
                assertThat(expectError(c, set(bad), rec.id()).state()).as(bad).isEqualTo("23514");
            }
            expectOk(c, set("sign_idempotency_key = repeat('k', 64), sign_request_hash = " + HASH + ", sign_result = '{\"a\": 1}'::jsonb, "
                    + "patient_facts_fingerprint = " + HASH), rec.id());
            return null;
        });
    }

    // ================= 2 · the referral line check (H22, QA case 14) =================

    @Test
    void aReferralLineWithoutAdmissionAdvisedIsRefusedOnAnOpdRecordAndAcceptedWithAdmissionAdvisedAndABlankOrLongLineIsRefused() throws Exception {
        Rec rec = newRec();
        db().inTenant(rec.clinic().tenantId(), c -> {
            SqlError e = expectError(c, set("referral_line = 'Refer to a hospital'"), rec.id());
            assertThat(e.state()).isEqualTo("23514");
            assertThat(e.message()).contains("ck_emr_record_referral_needs_advice");
            expectOk(c, set("admission_advised = true, referral_line = 'Refer to a hospital'"), rec.id());
            assertThat(expectError(c, set("referral_line = ''"), rec.id()).state()).isEqualTo("23514");
            assertThat(expectError(c, set("referral_line = repeat('x', 301)"), rec.id()).state()).isEqualTo("23514");
            expectOk(c, set("referral_line = repeat('x', 300)"), rec.id());
            return null;
        });
    }

    @Test
    void theReferralCheckKeepsTheOldRuleForOpdAndIsWiderOnlyForAnEmergencyAssessment() throws Exception {
        List<List<String>> def = globalRows("select pg_get_constraintdef(oid) from pg_constraint where conname = 'ck_emr_record_referral_needs_advice' "
                + "and conrelid = 'opd.emr_record'::regclass");
        assertThat(def).hasSize(1);
        assertThat(def.get(0).get(0)).contains("admission_advised").contains("referral_line IS NULL").contains("ER_ASSESSMENT");
    }

    // ================= 3 · the addendum table (append-only) =================

    private static final String ADD = "insert into opd.emr_record_addendum (record_id, record_created_at, reason, addendum_text, "
            + "created_by_membership_id, created_by_user_id, idempotency_key, request_hash) "
            + "select r.id, r.created_at, ?, ?, ?, ?, ?, " + "repeat('c', 64) from opd.emr_record r where r.id = ?::uuid";

    private void addendum(java.sql.Connection c, Rec rec, String reason, String text, String key) throws java.sql.SQLException {
        expectOk(c, ADD, reason, text, rec.doctor().membershipId(), rec.doctor().userId(), key, rec.id());
    }

    @Test
    void anAddendumInsertsForTheClinicAndCanNeverBeUpdatedDeletedOrTruncated() throws Exception {
        Rec rec = newRec();
        db().inTenant(rec.clinic().tenantId(), c -> {
            addendum(c, rec, "Reason", "Text", "key-000001");
            assertThat(expectError(c, "update opd.emr_record_addendum set reason = 'Changed'").state()).isEqualTo("42501");
            assertThat(expectError(c, "delete from opd.emr_record_addendum").state()).isEqualTo("42501");
            assertThat(expectError(c, "truncate opd.emr_record_addendum").state()).isEqualTo("42501");
            return null;
        });
    }

    @Test
    void anAddendumHasItsLimitsAndOneKeyPerClinicAndRefusesAnotherClinicsRecordOrAColumnItMayNotInsert() throws Exception {
        Rec rec = newRec();
        Rec foreign = newRec();
        String createdAt = row(rec.clinic(), "select created_at::text from opd.emr_record where id = ?::uuid", rec.id()).get(0);
        db().inTenant(rec.clinic().tenantId(), c -> {
            for (String[] bad : new String[][] {{" ", "Text"}, {"x".repeat(201), "Text"}, {"Reason", " "}, {"Reason", "y".repeat(4001)}}) {
                assertThat(expectError(c, ADD, bad[0], bad[1], rec.doctor().membershipId(), rec.doctor().userId(), "key-000002", rec.id()).state())
                        .as(bad[0].length() + "/" + bad[1].length()).isEqualTo("23514");
            }
            for (String key : new String[] {"short", "bad key with spaces", "k".repeat(65)}) {
                assertThat(expectError(c, ADD, "Reason", "Text", rec.doctor().membershipId(), rec.doctor().userId(), key, rec.id()).state())
                        .as(key).isEqualTo("23514");
            }
            expectOk(c, ADD, "x".repeat(200), "y".repeat(4000), rec.doctor().membershipId(), rec.doctor().userId(), "key-000003", rec.id());
            assertThat(expectError(c, ADD, "Reason", "Text", rec.doctor().membershipId(), rec.doctor().userId(), "key-000003", rec.id()).state())
                    .as("the same key twice in one clinic").isEqualTo("23505");
            assertThat(expectError(c, "insert into opd.emr_record_addendum (tenant_id, record_id, record_created_at, reason, addendum_text, "
                    + "created_by_membership_id, created_by_user_id, idempotency_key, request_hash) values (?::uuid, ?::uuid, ?::timestamptz, 'r', 't', ?, ?, "
                    + "'key-000004', " + HASH + ")", foreign.clinic().tenantId(), rec.id(), createdAt, rec.doctor().membershipId(),
                    rec.doctor().userId()).state()).as("tenant_id is not an insertable column").isEqualTo("42501");
            return null;
        });
        db().inTenant(foreign.clinic().tenantId(), c -> {
            assertThat(expectError(c, "insert into opd.emr_record_addendum (record_id, record_created_at, reason, addendum_text, "
                    + "created_by_membership_id, created_by_user_id, idempotency_key, request_hash) values (?::uuid, ?::timestamptz, 'r', 't', ?, ?, "
                    + "'key-000005', " + HASH + ")", rec.id(), createdAt, foreign.doctor().membershipId(), foreign.doctor().userId()).state())
                    .as("another clinic's record").isEqualTo("23503");
            return null;
        });
    }

    @Test
    void anotherClinicSeesNoAddendumAndNoClinicBoundSeesNone() throws Exception {
        Rec rec = newRec();
        Rec foreign = newRec();
        db().inTenant(rec.clinic().tenantId(), c -> {
            addendum(c, rec, "Reason", "Text", "key-000006");
            assertThat(DbAccess.count(c, "select count(*) from opd.emr_record_addendum")).isOne();
            return null;
        });
        commit(rec.clinic().tenantId(), ADD, "Reason", "Text", rec.doctor().membershipId(), rec.doctor().userId(), "key-000007", rec.id());
        assertThat(count(rec.clinic(), "select count(*) from opd.emr_record_addendum")).isOne();
        assertThat(count(foreign.clinic(), "select count(*) from opd.emr_record_addendum")).isZero();
        assertThat(globalCount("select count(*) from opd.emr_record_addendum")).as("no clinic bound").isZero();
    }

    // ================= 4 · the admission request table =================

    private static final String REQ = "insert into opd.emr_admission_request (record_id, record_created_at, encounter_type, opd_visit_id, er_case_id, "
            + "patient_id, requested_by_membership_id, admission_type, planned_stay_days, idempotency_key, request_hash, created_by) "
            + "select r.id, r.created_at, ?, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?::smallint, ?, repeat('d', 64), ? from opd.emr_record r where r.id = ?::uuid";

    private SqlError request(java.sql.Connection c, Rec rec, String type, UUID visit, UUID erCase, UUID patient, String admissionType,
                             Integer stay, String key, boolean expectOk) throws java.sql.SQLException {
        Object[] params = {type, visit, erCase, patient, rec.doctor().membershipId(), admissionType, stay, key, rec.doctor().userId(), rec.id()};
        if (expectOk) {
            expectOk(c, REQ, params);
            return null;
        }
        return expectError(c, REQ, params);
    }

    private void openRequest(java.sql.Connection c, Rec rec, String key) throws java.sql.SQLException {
        request(c, rec, "OPD_VISIT", UUID.fromString(rec.visitId()), null, rec.patientId(), "planned", 3, key, true);
    }

    @Test
    void anOpenRequestIsOnePerRecordAndANewOneIsAllowedOnceTheEarlierOneIsAdmitted() throws Exception {
        Rec rec = newRec();
        db().inTenant(rec.clinic().tenantId(), c -> {
            openRequest(c, rec, "req-000001");
            assertThat(request(c, rec, "OPD_VISIT", UUID.fromString(rec.visitId()), null, rec.patientId(), "planned", 3, "req-000002", false).state())
                    .as("a second OPEN request for the record").isEqualTo("23505");
            expectOk(c, "update opd.emr_admission_request set status = 'ADMITTED', admission_id = ?, admitted_at = now(), updated_at = now(), "
                    + "updated_by = ?", UUID.randomUUID(), rec.doctor().userId());
            openRequest(c, rec, "req-000003");
            return null;
        });
    }

    @Test
    void theRequestChecksRefuseABadShapeOfVisitCaseAndPatientAndAdmittedNeedsItsAdmission() throws Exception {
        Rec rec = newRec();
        UUID visit = UUID.fromString(rec.visitId());
        db().inTenant(rec.clinic().tenantId(), c -> {
            assertThat(request(c, rec, "OPD_VISIT", visit, UUID.randomUUID(), rec.patientId(), "planned", 3, "req-000011", false).state())
                    .as("a visit and a case").isEqualTo("23514");
            assertThat(request(c, rec, "OPD_VISIT", visit, null, null, "planned", 3, "req-000012", false).state()).as("an OPD request without a patient").isEqualTo("23514");
            assertThat(request(c, rec, "ER_ASSESSMENT", null, null, null, "emergency", null, "req-000014", false).state()).as("ER without a case").isEqualTo("23514");
            assertThat(request(c, rec, "WALK_IN", visit, null, rec.patientId(), "planned", 3, "req-000015", false).state()).isEqualTo("23514");
            assertThat(request(c, rec, "OPD_VISIT", visit, null, rec.patientId(), "maternity", 3, "req-000016", false).state()).isEqualTo("23514");
            assertThat(request(c, rec, "OPD_VISIT", visit, null, rec.patientId(), "planned", 0, "req-000017", false).state()).isEqualTo("23514");
            assertThat(request(c, rec, "OPD_VISIT", visit, null, rec.patientId(), "planned", 61, "req-000018", false).state()).isEqualTo("23514");
            assertThat(request(c, rec, "OPD_VISIT", visit, null, rec.patientId(), "planned", 3, "short", false).state()).isEqualTo("23514");
            request(c, rec, "ER_ASSESSMENT", null, UUID.randomUUID(), null, "emergency", null, "req-000019", true);
            expectOk(c, "update opd.emr_admission_request set status = status where encounter_type = 'ER_ASSESSMENT'");
            assertThat(expectError(c, "update opd.emr_admission_request set status = 'ADMITTED'").state()).as("ADMITTED needs the admission id").isEqualTo("23514");
            assertThat(expectError(c, "update opd.emr_admission_request set status = 'ADMITTED', admission_id = ?", UUID.randomUUID()).state())
                    .as("and the admitted time").isEqualTo("23514");
            return null;
        });
    }

    @Test
    void anAdmittedRequestNeverChangesAndNoRequestIsDeletedOrTruncatedAndTheAppCannotChangeItsFactsOrOtherClinicsRows() throws Exception {
        Rec rec = newRec();
        Rec foreign = newRec();
        db().inTenant(rec.clinic().tenantId(), c -> {
            openRequest(c, rec, "req-000021");
            assertThat(expectError(c, "update opd.emr_admission_request set note = 'Changed'").state()).as("note is not updatable").isEqualTo("42501");
            assertThat(expectError(c, "update opd.emr_admission_request set admission_type = 'daycare'").state()).isEqualTo("42501");
            expectOk(c, "update opd.emr_admission_request set status = 'ADMITTED', admission_id = ?, admitted_at = now(), updated_at = now(), updated_by = ?",
                    UUID.randomUUID(), rec.doctor().userId());
            assertThat(expectError(c, "update opd.emr_admission_request set updated_at = now()").state()).as("admitted never changes").isEqualTo("42501");
            assertThat(expectError(c, "delete from opd.emr_admission_request").state()).isEqualTo("42501");
            assertThat(expectError(c, "truncate opd.emr_admission_request").state()).isEqualTo("42501");
            return null;
        });
        commit(rec.clinic().tenantId(), REQ, "OPD_VISIT", UUID.fromString(rec.visitId()), null, rec.patientId(), rec.doctor().membershipId(), "planned", 3,
                "req-000022", rec.doctor().userId(), rec.id());
        assertThat(count(rec.clinic(), "select count(*) from opd.emr_admission_request")).isOne();
        assertThat(count(foreign.clinic(), "select count(*) from opd.emr_admission_request")).as("another clinic sees none").isZero();
        assertThat(globalCount("select count(*) from opd.emr_admission_request")).as("no clinic bound").isZero();
        String createdAt = row(rec.clinic(), "select created_at::text from opd.emr_record where id = ?::uuid", rec.id()).get(0);
        db().inTenant(foreign.clinic().tenantId(), c -> {
            assertThat(expectError(c, "insert into opd.emr_admission_request (record_id, record_created_at, encounter_type, opd_visit_id, patient_id, "
                    + "requested_by_membership_id, admission_type, idempotency_key, request_hash, created_by) values (?::uuid, ?::timestamptz, 'OPD_VISIT', ?::uuid, "
                    + "?::uuid, ?, 'planned', 'req-000023', " + HASH + ", ?)", rec.id(), createdAt, rec.visitId(), rec.patientId(), foreign.doctor().membershipId(),
                    foreign.doctor().userId()).state()).as("another clinic's record, visit and patient").isEqualTo("23503");
            return null;
        });
    }

    // ================= 5 · the Emergency visit type and triage =================

    @Test
    void theVisitTriageChecksTieALevelToTheEmergencyTypeAndToTheScale() throws Exception {
        Rec rec = newRec();
        String update = "update opd.visit set %s where id = ?::uuid";
        db().inTenant(rec.clinic().tenantId(), c -> {
            String id = rec.visitId();
            assertThat(expectError(c, String.format(update, "visit_type = 'EMERGENCY'"), id).state()).as("no level").isEqualTo("23514");
            assertThat(expectError(c, String.format(update, "triage_level = 2, triage_scale = 3"), id).state()).as("a level on a NEW visit").isEqualTo("23514");
            assertThat(expectError(c, String.format(update, "visit_type = 'EMERGENCY', triage_level = 2"), id).state()).as("a level and no scale").isEqualTo("23514");
            assertThat(expectError(c, String.format(update, "visit_type = 'EMERGENCY', triage_level = 4, triage_scale = 3"), id).state()).as("level above the scale").isEqualTo("23514");
            assertThat(expectError(c, String.format(update, "visit_type = 'EMERGENCY', triage_level = 2, triage_scale = 4"), id).state()).as("scale 4").isEqualTo("23514");
            assertThat(expectError(c, String.format(update, "visit_type = 'EMERGENCY', triage_level = 0, triage_scale = 3"), id).state()).isEqualTo("23514");
            assertThat(expectError(c, String.format(update, "visit_type = 'EMERGENCY', triage_level = 6, triage_scale = 5"), id).state()).isEqualTo("23514");
            expectOk(c, String.format(update, "visit_type = 'EMERGENCY', triage_level = 2, triage_scale = 3"), id);
            expectOk(c, String.format(update, "triage_level = 5, triage_scale = 5"), id);
            assertThat(expectError(c, String.format(update, "visit_type = 'NEW'"), id).state()).as("back to NEW must drop the level").isEqualTo("23514");
            expectOk(c, String.format(update, "visit_type = 'NEW', triage_level = null, triage_scale = null"), id);
            return null;
        });
    }

    // ================= 6 · the chemotherapy order frozen with its signed record =================

    @Test
    void aDraftChemoOrderOfASignedRecordIsFrozenWith42501AndOfAnUnsignedRecordItStillUpdates() throws Exception {
        Sheet sheet = passingSheet();
        Rec rec = sheet.rec();
        String orderId = orderId(sheet);
        db().inTenant(rec.clinic().tenantId(), c -> {
            expectOk(c, "update opd.chemo_order set revision = revision + 1 where id = ?::uuid", orderId);
            sign(c, rec);
            SqlError e = expectError(c, "update opd.chemo_order set revision = revision + 1 where id = ?::uuid", orderId);
            assertThat(e.state()).isEqualTo("42501");
            assertThat(e.message()).contains("signed EMR record");
            assertThat(expectError(c, "delete from opd.chemo_order where id = ?::uuid", orderId).state()).isEqualTo("42501");
            return null;
        });
    }

    // ================= 7 · appointment, follow-up, bill item, patient =================

    @Test
    void anAppointmentMayHaveTheEmrSourceAndAnUnknownSourceIsRefused() throws Exception {
        Rec rec = newRec();
        String insert = "insert into opd.appointment (patient_id, doctor_membership_id, appt_date, appt_time, visit_type, source, created_by) "
                + "values (?::uuid, ?::uuid, current_date + 5, '10:00', 'FOLLOW_UP', %s, ?::uuid)";
        db().inTenant(rec.clinic().tenantId(), c -> {
            expectOk(c, String.format(insert, "'EMR'"), rec.patientId(), rec.doctor().membershipId(), rec.doctor().userId());
            assertThat(expectError(c, String.format(insert, "'ROBOT'"), rec.patientId(), rec.doctor().membershipId(), rec.doctor().userId()).state())
                    .isEqualTo("23514");
            return null;
        });
        assertThat(globalRows("select pg_get_constraintdef(oid) from pg_constraint where conname = 'uq_appointment_tenant_id'")).hasSize(1);
    }

    @Test
    void aFollowUpLinksOnlyToAnAppointmentOfItsOwnClinicAndItsIntervalIsFromTheList() throws Exception {
        Rec rec = chooseInterval(readyRec(), "D7");
        signOk(rec);
        Rec foreign = newRec();
        commit(foreign.clinic().tenantId(), "insert into opd.appointment (patient_id, doctor_membership_id, appt_date, appt_time, visit_type, source, created_by) "
                + "values (?::uuid, ?::uuid, current_date + 5, '10:00', 'FOLLOW_UP', 'EMR', ?::uuid)", foreign.patientId(), foreign.doctor().membershipId(),
                foreign.doctor().userId());
        String foreignId = row(foreign.clinic(), "select id::text from opd.appointment").get(0);
        db().inTenant(rec.clinic().tenantId(), c -> {
            assertThat(expectError(c, "update opd.follow_up set appointment_id = ?::uuid", foreignId).state()).as("another clinic's appointment").isEqualTo("23503");
            expectOk(c, "update opd.follow_up set appointment_id = null");
            for (String interval : new String[] {"D3", "D7", "D14", "D15", "M1", "M3"}) {
                expectOk(c, "update opd.follow_up set interval_key = '" + interval + "'");
            }
            assertThat(expectError(c, "update opd.follow_up set interval_key = 'W2'").state()).isEqualTo("23514");
            return null;
        });
    }

    @Test
    void anEmrPlanBillLineMayHaveNoServiceAndALabelAndALongKeyAndTheOtherLinesKeepTheirRules() throws Exception {
        Rec rec = newRec();
        String service = serviceId(rec.clinic(), "Counter row", 80);
        ok(HttpMethod.POST, BILLING + "/bills/" + rec.visitId() + "/items", rec.clinic().owner(), "{\"serviceId\":\"" + service + "\",\"qty\":1}", 200);
        String bill = row(rec.clinic(), "select id::text from billing.bill").get(0);
        String insert = "insert into billing.bill_item (bill_id, service_id, name, amount_paise, qty, source_kind, source_key, label, created_by) "
                + "values (?::uuid, %s, 'Plan line', 15000, 1, %s, %s, %s, '" + rec.clinic().ownerUserId() + "')";
        db().inTenant(rec.clinic().tenantId(), c -> {
            expectOk(c, String.format(insert, "null", "'EMR_PLAN'", "'" + "p".repeat(70) + "'", "'Root canal'"), bill);
            assertThat(expectError(c, String.format(insert, "null", "null", "null", "null"), bill).state()).as("no service and not a plan line").isEqualTo("23514");
            assertThat(expectError(c, String.format(insert, "null", "'EMR'", "'k1'", "null"), bill).state()).as("no service on an EMR line").isEqualTo("23514");
            assertThat(expectError(c, String.format(insert, "'" + service + "'", "'EMR'", "'k2'", "'A label'"), bill).state()).as("a label on an EMR line").isEqualTo("23514");
            expectOk(c, String.format(insert, "'" + service + "'", "'EMR'", "'" + "q".repeat(41) + "'", "null"), bill);
            expectOk(c, String.format(insert, "'" + service + "'", "'EMR'", "'" + "r".repeat(80) + "'", "null"), bill);
            assertThat(expectError(c, String.format(insert, "'" + service + "'", "'EMR'", "'" + "s".repeat(81) + "'", "null"), bill).state()).isEqualTo("23514");
            assertThat(expectError(c, String.format(insert, "'" + service + "'", "'EMR_PLAN'", "'k3'", "'" + "l".repeat(121) + "'"), bill).state()).isEqualTo("23514");
            assertThat(expectError(c, String.format(insert, "null", "'EMR_PLAN'", "'" + "p".repeat(70) + "'", "'Again'"), bill).state())
                    .as("the same key twice on one bill").isEqualTo("23505");
            return null;
        });
    }

    @Test
    void thePatientAllergyStampIsBothOrNeither() throws Exception {
        Rec rec = newRec();
        db().inTenant(rec.clinic().tenantId(), c -> {
            assertThat(expectError(c, "update patient.patient set allergy_recorded_at = now() where id = ?::uuid", rec.patientId()).state()).isEqualTo("23514");
            assertThat(expectError(c, "update patient.patient set allergy_recorded_by = ?::uuid where id = ?::uuid", rec.doctor().userId(), rec.patientId()).state())
                    .isEqualTo("23514");
            expectOk(c, "update patient.patient set allergy_recorded_at = now(), allergy_recorded_by = ?::uuid where id = ?::uuid", rec.doctor().userId(), rec.patientId());
            expectOk(c, "update patient.patient set allergy_recorded_at = null, allergy_recorded_by = null where id = ?::uuid", rec.patientId());
            return null;
        });
    }

    // ================= 8 · grants, row level security, triggers =================

    @Test
    void theGrantsOfTheNewTablesAndColumnsAreExactlyTheDesign() throws Exception {
        String[][] columns = {{"opd.emr_record_addendum", "reason", "UPDATE", "f"}, {"opd.emr_record_addendum", "reason", "INSERT", "t"},
                {"opd.emr_record_addendum", "tenant_id", "INSERT", "f"}, {"opd.emr_record_addendum", "id", "INSERT", "f"},
                {"opd.emr_admission_request", "status", "UPDATE", "t"}, {"opd.emr_admission_request", "admission_id", "UPDATE", "t"},
                {"opd.emr_admission_request", "note", "UPDATE", "f"}, {"opd.emr_admission_request", "status", "INSERT", "f"},
                {"opd.emr_admission_request", "idempotency_key", "INSERT", "t"}, {"opd.emr_record", "signed_by_membership_id", "UPDATE", "t"},
                {"opd.emr_record", "sign_result", "UPDATE", "t"}, {"opd.emr_record", "edit_hold_expires_at", "UPDATE", "t"},
                {"opd.emr_record", "patient_facts_fingerprint", "UPDATE", "t"}, {"opd.emr_record", "tenant_id", "UPDATE", "f"},
                {"ipd.round_note", "orders_placed", "INSERT", "t"}, {"ipd.round_note", "orders_placed", "UPDATE", "f"},
                {"ipd.emergency_case", "sent_to_opd_visit_id", "UPDATE", "t"}, {"ipd.admission", "admission_request_id", "INSERT", "t"},
                {"ipd.admission", "admission_request_id", "UPDATE", "f"}};
        for (String[] e : columns) {
            assertThat(globalRows("select has_column_privilege('sahaj_app', '" + e[0] + "', '" + e[1] + "', '" + e[2] + "')").get(0).get(0))
                    .as(e[0] + "." + e[1] + " " + e[2]).isEqualTo(e[3]);
        }
        for (String table : new String[] {"opd.emr_record_addendum", "opd.emr_admission_request", "ipd.round_note"}) {
            assertThat(globalRows("select has_table_privilege('sahaj_app', '" + table + "', 'SELECT')").get(0).get(0)).as(table).isEqualTo("t");
            for (String privilege : new String[] {"UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
                assertThat(globalRows("select has_table_privilege('sahaj_app', '" + table + "', '" + privilege + "')").get(0).get(0))
                        .as(table + " " + privilege + " (column grants do not count)").isEqualTo("f");
            }
            String schema = table.substring(0, table.indexOf('.'));
            String name = table.substring(table.indexOf('.') + 1);
            assertThat(globalCount("select count(*) from information_schema.role_table_grants where table_schema = '" + schema + "' and table_name = '"
                    + name + "' and grantee = 'PUBLIC'")).as(table + " nothing for PUBLIC").isZero();
        }
    }

    @Test
    void theThreeNewTablesHaveEnabledAndForcedRowLevelSecurityAndTheRightPolicies() throws Exception {
        String[][] tables = {{"opd", "emr_record_addendum", "a,r"}, {"opd", "emr_admission_request", "*"}, {"ipd", "round_note", "a,r"}};
        for (String[] t : tables) {
            List<String> state = globalRows("select c.relrowsecurity::text, c.relforcerowsecurity::text, (select string_agg(p.polcmd::text, ',' order by p.polcmd::text) "
                    + "from pg_policy p where p.polrelid = c.oid) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = '"
                    + t[0] + "' and c.relname = '" + t[1] + "'").get(0);
            assertThat(state.get(0)).as(t[1] + " enabled").isEqualTo("true");
            assertThat(state.get(1)).as(t[1] + " forced").isEqualTo("true");
            assertThat(state.get(2)).as(t[1] + " policy commands: append-only tables have select and insert only").isEqualTo(t[2]);
            assertThat(globalCount("select count(*) from information_schema.columns where table_schema = '" + t[0] + "' and table_name = '" + t[1]
                    + "' and column_name = 'tenant_id' and is_nullable = 'NO'")).as(t[1] + " tenant_id not null").isOne();
        }
    }

    @Test
    void theGuardTriggersAreThereByNameAndEnabled() throws Exception {
        String[][] triggers = {{"opd.emr_record", "trg_emr_record_signed_guard"}, {"opd.emr_record_addendum", "trg_emr_record_addendum_no_update_delete"},
                {"opd.emr_record_addendum", "trg_emr_record_addendum_no_truncate"}, {"opd.emr_admission_request", "trg_emr_admission_request_guard"},
                {"opd.emr_admission_request", "trg_emr_admission_request_no_truncate"}, {"ipd.round_note", "trg_round_note_no_update_delete"},
                {"ipd.round_note", "trg_round_note_no_truncate"}, {"opd.chemo_order", "trg_chemo_order_immutable"},
                {"opd.emr_record", "trg_emr_record_insert_draft"}};
        for (String[] t : triggers) {
            assertThat(globalRows("select tgenabled::text from pg_trigger where tgrelid = '" + t[0] + "'::regclass and tgname = '" + t[1] + "' and not tgisinternal"))
                    .as(t[1]).hasSize(1).first().isEqualTo(List.of("O"));
        }
        assertThat(globalCount("select count(*) from pg_trigger t join pg_class c on c.oid = t.tgrelid where t.tgname = 'trg_emr_record_signed_guard' "
                + "and c.relname like 'emr\\_record\\_%' and c.relkind = 'r' and not t.tgisinternal"))
                .as("the guard is on every monthly partition and the default one").isGreaterThanOrEqualTo(25);
        assertThat(globalCount("select count(*) from pg_trigger t join pg_class c on c.oid = t.tgrelid where t.tgname = 'trg_emr_record_insert_draft' "
                + "and c.relname like 'emr\\_record\\_%' and c.relkind = 'r' and not t.tgisinternal"))
                .as("the insert trigger is cloned onto every partition too").isGreaterThanOrEqualTo(25);
    }

    // ================= 8 · a new record is always a DRAFT (QA case 15, security I2) =================

    /** emr_record insert of an Emergency assessment with the given status column list and values (the sign columns optional). */
    private static String emrInsert(String statusColumns, String statusValues, String createdAt) {
        return "insert into opd.emr_record (encounter_type, opd_visit_id, ipd_admission_id, round_date, er_case_id, patient_id, "
                + "opened_by_membership_id, department_key, department_name, starter_key, layout_kind, department_version_id, "
                + "starter_layout_version, choice_source, " + statusColumns + "sections, referral_line, admission_advised, created_by"
                + (createdAt == null ? "" : ", created_at") + ") values ('ER_ASSESSMENT', null, null, null, ?, null, ?, 'gm', 'General Medicine', "
                + "'gm', 'STARTER_LAYOUT', null, 1, 'CLINIC_DEFAULT', " + statusValues + "'{}'::jsonb, null, false, ?"
                + (createdAt == null ? "" : ", " + createdAt) + ")";
    }

    @Test
    void aSignedInsertIsRefusedWith23514AndTheDraftOnlyMessageWhateverElseItCarries() throws Exception {
        Clinic clinic = newClinic();
        UUID actor = UUID.randomUUID();
        UUID signer = clinic.ownerUserId();
        db().inTenant(clinic.tenantId(), c -> {
            String[][] signed = {
                    {"status, ", "'SIGNED', "},
                    {"status, signed_at, signed_by, ", "'SIGNED', now(), '" + signer + "', "},
                    {"status, signed_at, signed_by, signed_by_membership_id, sign_idempotency_key, sign_request_hash, sign_result, ",
                            "'SIGNED', now(), '" + signer + "', '" + actor + "', 'abcdefgh12', " + HASH + ", '{}'::jsonb, "}};
            for (String[] s : signed) {
                SqlError e = expectError(c, emrInsert(s[0], s[1], null), UUID.randomUUID(), actor, actor);
                assertThat(e.state()).as(s[0]).isEqualTo("23514");
                assertThat(e.message()).as(s[0]).contains("must be inserted as DRAFT");
            }
            // an OPD visit or IPD round shaped row is refused by the same trigger when it says SIGNED (status is checked before the keys)
            SqlError opd = expectError(c, "insert into opd.emr_record (encounter_type, opd_visit_id, ipd_admission_id, round_date, er_case_id, patient_id, "
                    + "opened_by_membership_id, department_key, department_name, starter_key, layout_kind, department_version_id, starter_layout_version, "
                    + "choice_source, status, signed_at, signed_by, sections, referral_line, admission_advised, created_by) values ('OPD_VISIT', ?, null, null, "
                    + "null, ?, ?, 'gm', 'General Medicine', 'gm', 'STARTER_LAYOUT', null, 1, 'CLINIC_DEFAULT', 'SIGNED', now(), ?, '{}'::jsonb, null, false, ?)",
                    UUID.randomUUID(), UUID.randomUUID(), actor, signer, actor);
            assertThat(opd.state()).isIn("23514", "23503");
            return null;
        });
    }

    @Test
    void anInsertWithNoStatusIsADraftAndTheDraftToSignedUpdateStillWorks() throws Exception {
        Clinic clinic = newClinic();
        UUID actor = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        db().inTenant(clinic.tenantId(), c -> {
            expectOk(c, emrInsert("", "", null), id, actor, actor);
            assertThat(row0(c, "select status from opd.emr_record where er_case_id = ?::uuid", id)).containsExactly("DRAFT");
            expectOk(c, emrInsert("status, ", "'DRAFT', ", null), UUID.randomUUID(), actor, actor);
            // the one way to SIGNED: the update (all sign columns, hold clear)
            expectOk(c, "update opd.emr_record set status = 'SIGNED', signed_at = now(), signed_by = ?, signed_by_membership_id = ?, "
                    + "sign_idempotency_key = 'abcdefgh12', sign_request_hash = " + HASH + ", sign_result = '{}'::jsonb, "
                    + "edit_hold_membership_id = null, edit_hold_user_id = null, edit_hold_expires_at = null where er_case_id = ?::uuid",
                    clinic.ownerUserId(), actor, id);
            assertThat(row0(c, "select status from opd.emr_record where er_case_id = ?::uuid", id)).containsExactly("SIGNED");
            return null;
        });
    }

    @Test
    void theInsertRefusalHoldsOnTheMonthPartitionOfTodayAndOnTheDefaultPartitionAndTheFunctionHasNoExecuteForSahajApp() throws Exception {
        Clinic clinic = newClinic();
        UUID actor = UUID.randomUUID();
        db().inTenant(clinic.tenantId(), c -> {
            // routed to the current month partition, and to the default partition (a month no partition covers)
            for (String createdAt : new String[] {"now()", "timestamptz '2099-01-15 10:00:00+05:30'"}) {
                SqlError e = expectError(c, emrInsert("status, ", "'SIGNED', ", createdAt), UUID.randomUUID(), actor, actor);
                assertThat(e.state()).as(createdAt).isEqualTo("23514");
                assertThat(e.message()).as(createdAt).contains("must be inserted as DRAFT");
                expectOk(c, emrInsert("", "", createdAt), UUID.randomUUID(), actor, actor);
            }
            return null;
        });
        assertThat(globalRows("select has_function_privilege('sahaj_app', 'opd.require_draft_emr_record_insert()', 'EXECUTE')").get(0).get(0))
                .as("sahaj_app cannot call the insert guard function directly").isEqualTo("f");
        assertThat(globalRows("select has_function_privilege('sahaj_app', 'opd.guard_signed_emr_record()', 'EXECUTE')").get(0).get(0))
                .as("nor the signed-record guard function").isEqualTo("f");
        assertThat(globalCount("select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'opd' "
                + "and p.proname = 'require_draft_emr_record_insert' and p.proconfig::text like '%search_path=pg_catalog%'"))
                .as("the function pins its search_path").isOne();
    }

    @Test
    void theCheckConstraintOnTheSignedColumnsIsReachedByTheSignUpdateWhenTheTimeOrTheSignerIsMissing() throws Exception {
        // ck_emr_record_signed (SAH-53) ties signed_at and signed_by to SIGNED; it can no longer be reached by an insert (case 15)
        Rec rec = newRec();
        db().inTenant(rec.clinic().tenantId(), c -> {
            expectOk(c, set("edit_hold_membership_id = null, edit_hold_user_id = null, edit_hold_expires_at = null"), rec.id());
            String tail = ", signed_by_membership_id = ?, sign_idempotency_key = 'abcdefgh12'";
            SqlError noTime = expectError(c, set("status = 'SIGNED', signed_by = ?" + tail), rec.doctor().userId(), rec.doctor().membershipId(), rec.id());
            assertThat(noTime.state()).as("SIGNED without a time").isEqualTo("23514");
            assertThat(noTime.message()).contains("\"ck_emr_record_signed\"");
            SqlError noSigner = expectError(c, set("status = 'SIGNED', signed_at = now()" + tail.replace("?", "'" + rec.doctor().membershipId() + "'")), rec.id());
            assertThat(noSigner.state()).as("SIGNED without a signer").isEqualTo("23514");
            assertThat(noSigner.message()).contains("\"ck_emr_record_signed\"");
            sign(c, rec);
            return null;
        });
    }

    // ================= 9 · the dates of the sign are the database's =================

    @Test
    void theSignedAtOfARealSignIsTheDatabaseTransactionTimeAndNotInTheFuture() throws Exception {
        Rec rec = readyRec();
        signOk(rec);
        List<String> times = row(rec.clinic(), "select (signed_at <= now())::text, (signed_at > now() - interval '5 minutes')::text from opd.emr_record where id = ?::uuid", rec.id());
        assertThat(times).containsExactly("true", "true");
        assertThat(record(rec.token(), rec.id()).get("signedAt").asText()).isNotBlank();
    }

}
