package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The ipd SQL of SAH-67 on {@code sahaj_test} as {@code sahaj_app} (docs/database/SAH-51-68.md, QA cases 6, 8 and 13): the
 * insert-only round note table (one note per admission and day and per record), the Emergency case status SENT_TO_OPD with its
 * patient, visit and closing columns and the guard that freezes a closed case, and the admission request link. Every probe
 * runs in a rolled-back transaction. Test plan: docs/test-plans/SAH-51-68.md (T-DB-x).
 */
@OnTestDatabase
class EmrIpdPhase5TablesIT extends EmrIpdSupport {

    private static final String NOTE = "insert into ipd.round_note (admission_id, round_date, emr_record_id, signed_by_user_id, "
            + "signed_by_membership_id, signed_at, orders_placed, investigations_advised) values (?::uuid, ?::date, ?::uuid, ?::uuid, ?::uuid, now(), ?, ?)";

    private static Object[] note(String admission, String date, UUID record, Clinic clinic, int orders, String investigations) {
        return new Object[] {admission, date, record, clinic.ownerUserId(), clinic.doctorMembershipId(), orders, investigations};
    }

    private static void expect(java.sql.Connection c, String state, String as, String sql, Object... params) throws java.sql.SQLException {
        assertThat(EmrSupport.expectError(c, sql, params).state()).as(as).isEqualTo(state);
    }

    // ================= 6 · the round note =================

    @Test
    void aRoundNoteIsOnePerAdmissionAndDayAndOnePerRecordAndItsFieldsHaveTheirLimits() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        String today = todayIst().toString();
        db().inTenant(clinic.tenantId(), c -> {
            EmrSupport.expectOk(c, NOTE, note(adm.id(), today, UUID.randomUUID(), clinic, 2, "CBC; X-ray"));
            expect(c, "23505", "the same admission and day", NOTE, note(adm.id(), today, UUID.randomUUID(), clinic, 0, null));
            expect(c, "23505", "the same record on another day", NOTE, note(adm.id(), todayIst().minusDays(1).toString(),
                    UUID.fromString(EmrSupport.firstRow(c, "select emr_record_id::text from ipd.round_note").get(0)), clinic, 0, null));
            EmrSupport.expectOk(c, NOTE, note(adm.id(), todayIst().minusDays(1).toString(), UUID.randomUUID(), clinic, 0, null));
            expect(c, "23514", "negative order count", NOTE, note(adm.id(), todayIst().minusDays(2).toString(), UUID.randomUUID(), clinic, -1, null));
            expect(c, "23514", "empty investigations", NOTE, note(adm.id(), todayIst().minusDays(2).toString(), UUID.randomUUID(), clinic, 0, "  "));
            expect(c, "23514", "investigations over 1,000", NOTE, note(adm.id(), todayIst().minusDays(2).toString(), UUID.randomUUID(), clinic, 0, "x".repeat(1001)));
            EmrSupport.expectOk(c, NOTE, note(adm.id(), todayIst().minusDays(3).toString(), UUID.randomUUID(), clinic, 0, "x".repeat(1000)));
            return null;
        });
    }

    @Test
    void aRoundNoteIsNeverUpdatedDeletedOrTruncatedAndNeedsAnAdmissionOfItsOwnClinic() throws Exception {
        Clinic clinic = hospital();
        Clinic other = hospital();
        Adm adm = admitOne(clinic);
        Adm foreign = admitOne(other);
        db().inTenant(clinic.tenantId(), c -> {
            EmrSupport.expectOk(c, NOTE, note(adm.id(), todayIst().toString(), UUID.randomUUID(), clinic, 1, null));
            expect(c, "42501", "update", "update ipd.round_note set orders_placed = 5");
            expect(c, "42501", "delete", "delete from ipd.round_note");
            expect(c, "42501", "truncate", "truncate ipd.round_note");
            expect(c, "23503", "another clinic's admission", NOTE, note(foreign.id(), todayIst().toString(), UUID.randomUUID(), clinic, 0, null));
            return null;
        });
    }

    @Test
    void anotherClinicSeesNoRoundNoteAndNoClinicBoundSeesNone() throws Exception {
        Clinic clinic = hospital();
        Clinic other = hospital();
        Adm adm = admitOne(clinic);
        commitNote(clinic, adm);
        assertThat(count(clinic, "select count(*) from ipd.round_note")).isOne();
        assertThat(count(other, "select count(*) from ipd.round_note")).isZero();
        long unbound = db().asApp(c -> DbAccess.count(c, "select count(*) from ipd.round_note"));
        assertThat(unbound).as("no clinic bound").isZero();
    }

    private void commitNote(Clinic clinic, Adm adm) throws Exception {
        try (java.sql.Connection c = db().app()) {
            c.setAutoCommit(false);
            try (java.sql.PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, clinic.tenantId().toString());
                bind.execute();
            }
            DbAccess.execute(c, NOTE, note(adm.id(), todayIst().toString(), UUID.randomUUID(), clinic, 0, null));
            c.commit();
        }
    }

    // ================= 8 · the Emergency case SENT_TO_OPD =================

    private static final String SENT = "update ipd.emergency_case set status = 'SENT_TO_OPD', closed_at = now(), closed_by = ?::uuid, mobile = null, "
            + "patient_id = ?::uuid, sent_to_opd_visit_id = ?::uuid where id = ?::uuid";

    private String caseWithMobile(Clinic clinic, String name) throws Exception {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("clientKey", UUID.randomUUID().toString());
        m.put("identityUnknown", false);
        m.put("name", name);
        m.put("age", 40);
        m.put("sex", "F");
        m.put("mobile", "9876543210");
        m.put("complaint", "Chest pain for 1 hour");
        m.put("level", 2);
        return ok(HttpMethod.POST, ER_CASES_PATH, clinic.owner(), js(m), 201).get("actedCase").get("id").asText();
    }

    @Test
    void sentToOpdNeedsThePatientTheVisitAndTheClosingColumnsAndOnlyThatStatusHoldsAVisitId() throws Exception {
        Clinic clinic = hospital();
        String id = caseOf(clinic, "Test Case One", 3);
        UUID patient = newPatient(clinic);
        UUID visit = UUID.randomUUID();
        db().inTenant(clinic.tenantId(), c -> {
            String base = "update ipd.emergency_case set status = 'SENT_TO_OPD', closed_at = now(), closed_by = ?::uuid";
            expect(c, "23514", "no patient and no visit", base + " where id = ?::uuid", clinic.ownerUserId(), id);
            expect(c, "23514", "no visit", base + ", patient_id = ?::uuid where id = ?::uuid", clinic.ownerUserId(), patient, id);
            expect(c, "23514", "no patient", base + ", sent_to_opd_visit_id = ?::uuid where id = ?::uuid", clinic.ownerUserId(), visit, id);
            expect(c, "23514", "no closing time", "update ipd.emergency_case set status = 'SENT_TO_OPD', closed_by = ?::uuid, patient_id = ?::uuid, "
                    + "sent_to_opd_visit_id = ?::uuid where id = ?::uuid", clinic.ownerUserId(), patient, visit, id);
            expect(c, "23514", "a visit id on a waiting case", "update ipd.emergency_case set sent_to_opd_visit_id = ?::uuid where id = ?::uuid", visit, id);
            expect(c, "23514", "a patient on a released case", "update ipd.emergency_case set status = 'RELEASED', closed_at = now(), closed_by = ?::uuid, "
                    + "patient_id = ?::uuid where id = ?::uuid", clinic.ownerUserId(), patient, id);
            EmrSupport.expectOk(c, SENT, clinic.ownerUserId(), patient, visit, id);
            assertThat(EmrSupport.firstRow(c, "select status, patient_id::text, sent_to_opd_visit_id::text from ipd.emergency_case where id = ?::uuid", id))
                    .containsExactly("SENT_TO_OPD", patient.toString(), visit.toString());
            return null;
        });
    }

    @Test
    void aSentToOpdCaseNeverUpdatesAgainAndTheNumberIsClearedByTheClosingUpdateAndNeverSetToAnotherNumber() throws Exception {
        Clinic clinic = hospital();
        String withMobile = caseWithMobile(clinic, "Test Case Two");
        UUID patient = newPatient(clinic);
        UUID visit = UUID.randomUUID();
        db().inTenant(clinic.tenantId(), c -> {
            expect(c, "23514", "closing with another number", "update ipd.emergency_case set status = 'SENT_TO_OPD', closed_at = now(), closed_by = ?::uuid, "
                    + "patient_id = ?::uuid, sent_to_opd_visit_id = ?::uuid, mobile = '9123456780' where id = ?::uuid", clinic.ownerUserId(), patient, visit, withMobile);
            EmrSupport.expectOk(c, SENT, clinic.ownerUserId(), patient, visit, withMobile);
            assertThat(EmrSupport.firstRow(c, "select (mobile is null)::text from ipd.emergency_case where id = ?::uuid", withMobile)).containsExactly("true");
            for (String set : new String[] {"level = 1", "mobile = null", "mobile = '9876543210'", "version = version + 1"}) {
                SqlProbe p = new SqlProbe(EmrSupport.expectError(c, "update ipd.emergency_case set " + set + " where id = ?::uuid", withMobile));
                assertThat(p.state()).as(set).isEqualTo("23514");
                assertThat(p.message()).as(set).contains("emergency case is closed");
            }
            expect(c, "42501", "delete", "delete from ipd.emergency_case where id = ?::uuid", withMobile);
            return null;
        });
    }

    /** The four closes, each as the SET clause and its parameters (no mobile in it), for a case of the clinic. */
    private static String closeSet(String status) {
        return switch (status) {
            case "SENT_TO_OPD" -> "status = 'SENT_TO_OPD', closed_at = now(), closed_by = ?::uuid, patient_id = ?::uuid, sent_to_opd_visit_id = ?::uuid";
            case "ADMITTED" -> "status = 'ADMITTED', closed_at = now(), closed_by = ?::uuid, admission_id = ?::uuid, patient_id = ?::uuid";
            default -> "status = '" + status + "', closed_at = now(), closed_by = ?::uuid";
        };
    }

    private static Object[] closeParams(String status, Clinic clinic, Adm adm, UUID patient, String id) {
        return switch (status) {
            case "SENT_TO_OPD" -> new Object[] {clinic.ownerUserId(), patient, UUID.randomUUID(), id};
            case "ADMITTED" -> new Object[] {clinic.ownerUserId(), adm.id(), adm.patientId(), id};
            default -> new Object[] {clinic.ownerUserId(), id};
        };
    }

    // SAH-68 QA F-4 (design note case 8): the update that closes a case must clear the mobile in the same update
    @Test
    void aCloseToSentToOpdAdmittedReleasedOrReferredThatKeepsAnUnchangedMobileIsRefusedAndACloseOfACaseWithNoMobileSucceeds() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        Adm adm2 = admitOne(clinic);
        UUID patient = newPatient(clinic);
        String[] statuses = {"SENT_TO_OPD", "ADMITTED", "RELEASED", "REFERRED"};
        String[] withMobile = new String[statuses.length];
        String[] noMobile = new String[statuses.length];
        for (int i = 0; i < statuses.length; i++) {
            withMobile[i] = caseWithMobile(clinic, "Test Case Keep " + i);
            noMobile[i] = caseOf(clinic, "Test Case Null " + i, 3);
        }
        db().inTenant(clinic.tenantId(), c -> {
            for (int i = 0; i < statuses.length; i++) {
                String status = statuses[i];
                String set = closeSet(status);
                assertThat(EmrSupport.firstRow(c, "select mobile from ipd.emergency_case where id = ?::uuid", withMobile[i]).get(0))
                        .as("the open case holds its mobile").isNotNull();
                // the close leaves the mobile untouched (not null, not changed): refused by the guard
                SqlProbe kept = new SqlProbe(EmrSupport.expectError(c, "update ipd.emergency_case set " + set + " where id = ?::uuid",
                        closeParams(status, clinic, adm, patient, withMobile[i])));
                assertThat(kept.state()).as(status + " keeping the mobile").isEqualTo("23514");
                assertThat(kept.message()).as(status).contains("must be cleared in the update that closes the case");
                // a close that sets the mobile to the value it already has is also a close that keeps it
                SqlProbe same = new SqlProbe(EmrSupport.expectError(c, "update ipd.emergency_case set " + set + ", mobile = mobile where id = ?::uuid",
                        closeParams(status, clinic, adm, patient, withMobile[i])));
                assertThat(same.state()).as(status + " mobile = mobile").isEqualTo("23514");
                assertThat(same.message()).as(status + " mobile = mobile").contains("must be cleared in the update that closes the case");
                // the close with the mobile cleared in the same update succeeds, and the case is closed with no number
                EmrSupport.expectOk(c, "update ipd.emergency_case set " + set + ", mobile = null where id = ?::uuid",
                        closeParams(status, clinic, adm, patient, withMobile[i]));
                assertThat(EmrSupport.firstRow(c, "select status, (mobile is null)::text from ipd.emergency_case where id = ?::uuid", withMobile[i]))
                        .containsExactly(status, "true");
                // a close of a case whose mobile is already null succeeds without touching the column
                EmrSupport.expectOk(c, "update ipd.emergency_case set " + set + " where id = ?::uuid",
                        closeParams(status, clinic, adm2, patient, noMobile[i]));
                assertThat(EmrSupport.firstRow(c, "select status from ipd.emergency_case where id = ?::uuid", noMobile[i])).containsExactly(status);
            }
            return null;
        });
    }

    // SAH-68 QA F-6 (design note case 1 and 2): the signed-record allow-list is print_language, paper_size, updated_at,
    // updated_by, and patient_id from null to a value on an ER_ASSESSMENT; sections, revision and status stay frozen
    @Test
    void aSignedEmergencyRecordTakesItsPatientOnceAndEveryOtherClinicalColumnStaysFrozen() throws Exception {
        Clinic clinic = hospital();
        String caseId = caseOf(clinic, "Test Case Link", 3);
        String recordId = openRecord(admittingDoctor(clinic), "ER_ASSESSMENT", caseId, 201).get("record").get("id").asText();
        UUID patient = newPatient(clinic);
        UUID other = newPatient(clinic);
        db().inTenant(clinic.tenantId(), c -> {
            assertThat(EmrSupport.firstRow(c, "select patient_id::text from opd.emr_record where id = ?::uuid", recordId).get(0)).as("no patient before").isNull();
            EmrSupport.expectOk(c, "update opd.emr_record set status = 'SIGNED', signed_at = now(), signed_by = ?::uuid, signed_by_membership_id = ?::uuid, "
                    + "sign_idempotency_key = 'abcdefgh12', sign_request_hash = repeat('a', 64), sign_result = '{}'::jsonb, edit_hold_membership_id = null, "
                    + "edit_hold_user_id = null, edit_hold_expires_at = null, revision = revision + 1, updated_at = now(), updated_by = ?::uuid where id = ?::uuid",
                    clinic.ownerUserId(), clinic.doctorMembershipId(), clinic.ownerUserId(), recordId);
            List<String> frozen = EmrSupport.firstRow(c, "select status, revision::text, sections::text from opd.emr_record where id = ?::uuid", recordId);
            for (String assignment : new String[] {"sections = '{\"k\": 1}'::jsonb", "revision = revision + 1", "status = 'DRAFT'"}) {
                expect(c, "42501", assignment + " on a signed ER record", "update opd.emr_record set " + assignment + " where id = ?::uuid", recordId);
                expect(c, "42501", assignment + " together with the patient link", "update opd.emr_record set patient_id = ?::uuid, " + assignment
                        + " where id = ?::uuid", patient, recordId);
            }
            // the link: null to a value works once, with the print choices and the update stamp beside it
            EmrSupport.expectOk(c, "update opd.emr_record set patient_id = ?::uuid, print_language = 'HI', paper_size = 'A5', updated_at = now(), "
                    + "updated_by = ?::uuid where id = ?::uuid", patient, clinic.ownerUserId(), recordId);
            assertThat(EmrSupport.firstRow(c, "select patient_id::text, print_language, paper_size from opd.emr_record where id = ?::uuid", recordId))
                    .containsExactly(patient.toString(), "HI", "A5");
            assertThat(EmrSupport.firstRow(c, "select status, revision::text, sections::text from opd.emr_record where id = ?::uuid", recordId))
                    .as("status, revision and sections did not move").isEqualTo(frozen);
            expect(c, "42501", "a second change of the patient", "update opd.emr_record set patient_id = ?::uuid where id = ?::uuid", other, recordId);
            expect(c, "42501", "the patient back to null", "update opd.emr_record set patient_id = null where id = ?::uuid", recordId);
            return null;
        });
    }

    @Test
    void theCaseStatusListAndThePatientRuleStillRefuseUnknownStatusesAndAPatientOnAReleasedCase() throws Exception {
        Clinic clinic = hospital();
        String id = caseOf(clinic, "Test Case Three", 3);
        db().inTenant(clinic.tenantId(), c -> {
            expect(c, "23514", "an unknown status", "update ipd.emergency_case set status = 'GONE', closed_at = now(), closed_by = ?::uuid where id = ?::uuid",
                    clinic.ownerUserId(), id);
            String definition = EmrSupport.firstRow(c, "select pg_get_constraintdef(oid) from pg_constraint where conname = 'ck_emergency_case_patient_admitted' "
                    + "and conrelid = 'ipd.emergency_case'::regclass").get(0);
            assertThat(definition).contains("ADMITTED").contains("SENT_TO_OPD").contains("patient_id IS NULL");
            assertThat(DbAccess.count(c, "select count(*) from ipd.emergency_case where patient_id is not null and status not in ('ADMITTED', 'SENT_TO_OPD')")).isZero();
            return null;
        });
    }

    // ================= 13 · the admission request link =================

    @Test
    void anAdmissionCanBeCreatedWithAnEmrRequestIdButNeverUpdatedToOneAndOneAdmissionPerRequest() throws Exception {
        List<List<String>> index = db().asApp(c -> EmrSupport.allRows(c, "select indexdef from pg_indexes where schemaname = 'ipd' and tablename = 'admission' "
                + "and indexname = 'uq_admission_tenant_admission_request'"));
        assertThat(index).hasSize(1);
        assertThat(index.get(0).get(0)).containsIgnoringCase("unique").contains("admission_request_id").containsIgnoringCase("where");
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        db().inTenant(clinic.tenantId(), c -> {
            expect(c, "42501", "update", "update ipd.admission set admission_request_id = ?::uuid where id = ?::uuid", UUID.randomUUID(), adm.id());
            return null;
        });
        assertThat(count(clinic, "select count(*) from ipd.admission where admission_request_id is null")).as("an admission with no request has a null id").isOne();
    }

    /** The state and the message of a refused statement. */
    private record SqlProbe(EmrSupport.SqlError error) {

        String state() {
            return error.state();
        }

        String message() {
            return error.message();
        }
    }
}
