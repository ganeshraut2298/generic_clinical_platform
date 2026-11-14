package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-67-4 and SAH-68-2 (visit type fee) on the real database (docs/api/opd.yaml 0.6.1, ADR-025 D11, D14, H11): the doctor
 * marks an OPD visit as Emergency with a triage level of the clinic's scale (the SAH-28 default when the clinic has none, also
 * with IPD off), the card carries the level, changing back drops the level (kept in the audit), no ER case is made, and the fee
 * of the visit is worked out again while its bill has no payment. Test plan: docs/test-plans/SAH-51-68.md (T-67-4.x, T-68-2.x).
 */
@OnTestDatabase
class EmrEmergencyVisitTypeIT extends EmrSignSupport {

    private static final String TRIAGE = EMR + "/triage-levels";

    private static String url(Object visitId) {
        return VISITS + "/" + visitId + "/visit-type";
    }

    private static String emergency(Integer level) {
        return "{\"visitType\":\"EMERGENCY\"" + (level == null ? "" : ",\"triageLevel\":" + level) + "}";
    }

    private static String type(String type) {
        return "{\"visitType\":\"" + type + "\"}";
    }

    private record Ctx(Clinic clinic, Staff doctor, String visitId) {
    }

    private Ctx ctx() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        return new Ctx(clinic, doctor, issueVisit(clinic, doctor.membershipId()).get("id").asText());
    }

    // ---------------- the levels ----------------

    @Test
    void aClinicWithNoEmergencySettingsGetsTheThreeColourDefaultAndItWorksWithIpdOff() throws Exception {
        Ctx c = ctx();
        JsonNode scale = get(TRIAGE, c.doctor().token(c.clinic()));
        assertThat(scale.get("scale").asInt()).isEqualTo(3);
        assertThat(scale.get("clinicDefault").asBoolean()).isTrue();
        assertThat(scale.get("levels")).hasSize(3);
        List<Integer> levels = new java.util.ArrayList<>();
        for (JsonNode l : scale.get("levels")) {
            levels.add(l.get("level").asInt());
            assertThat(l.get("name").asText()).isNotBlank();
            assertThat(l.get("tone").asText()).isNotBlank();
        }
        assertThat(levels).containsExactly(1, 2, 3);
    }

    @Test
    void onlyTheDoctorsAndTheOwnerWithDoctorDetailsReadTheLevels() throws Exception {
        Ctx c = ctx();
        for (String role : NOT_DOCTOR_ROLES) {
            problemOf(HttpMethod.GET, TRIAGE, addNonDoctor(c.clinic(), role).token(c.clinic()), null, 403, "forbidden");
        }
        problemOf(HttpMethod.GET, TRIAGE, anonymous(), null, 401, "unauthorized");
        problemOf(HttpMethod.GET, TRIAGE, c.clinic().owner(), null, 403, "doctor-details-missing");
    }

    // ---------------- Emergency with a level ----------------

    @Test
    void emergencyWithALevelIsStoredOnTheCardAndAuditedAndNoErCaseIsMade() throws Exception {
        Ctx c = ctx();
        JsonNode visit = ok(HttpMethod.PATCH, url(c.visitId()), c.doctor().token(c.clinic()), emergency(2), 200);
        assertThat(visit.get("visitType").asText()).isEqualTo("EMERGENCY");
        assertThat(visit.get("triageLevel").asInt()).isEqualTo(2);
        assertThat(visit.get("triageLevelName").asText()).isNotBlank();
        assertThat(visit.get("triageTone").asText()).isNotBlank();
        JsonNode card = boardVisit(c.clinic(), c.visitId());
        assertThat(card.get("visitType").asText()).isEqualTo("EMERGENCY");
        assertThat(card.get("triageLevel").asInt()).isEqualTo(2);
        assertThat(row(c.clinic(), "select triage_level::text, triage_scale::text from opd.visit where id = ?::uuid", c.visitId()))
                .containsExactly("2", "3");
        assertThat(count(c.clinic(), "select count(*) from ipd.emergency_case")).as("no ER case (OQ-43-25)").isZero();
        List<List<String>> audits = auditRows(c.clinic(), "EMR_VISIT_TYPE_CHANGED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get(2).replace(" ", "")).contains("\"visitType\":\"EMERGENCY\"").contains("\"triageLevel\":2");
        assertThat(audits.get(0).get(1) + audits.get(0).get(2)).doesNotContain("Ramesh").doesNotContain("Kulkarni");
    }

    @Test
    void aMissingOrUnknownLevelIs400ChooseATriageLevelAndALevelOnAnotherTypeIs400() throws Exception {
        Ctx c = ctx();
        for (String body : new String[] {emergency(null), emergency(0), emergency(4), emergency(-1), "{\"visitType\":\"EMERGENCY\",\"triageLevel\":null}"}) {
            JsonNode problem = problemOf(HttpMethod.PATCH, url(c.visitId()), c.doctor().token(c.clinic()), body, 400, "validation");
            assertThat(problem.toString()).contains("Choose a triage level");
        }
        JsonNode other = problemOf(HttpMethod.PATCH, url(c.visitId()), c.doctor().token(c.clinic()),
                "{\"visitType\":\"REVISIT\",\"triageLevel\":2}", 400, "validation");
        assertThat(other.toString()).contains("A triage level is only for the Emergency type.");
        assertThat(row(c.clinic(), "select visit_type, triage_level::text from opd.visit where id = ?::uuid", c.visitId()))
                .containsExactly("NEW", null);
        assertThat(count(c.clinic(), "select count(*) from platform.audit_event where action = 'EMR_VISIT_TYPE_CHANGED'")).isZero();
    }

    @Test
    void changingBackDropsTheLevelFromTheCardAndKeepsItInTheAudit() throws Exception {
        Ctx c = ctx();
        ok(HttpMethod.PATCH, url(c.visitId()), c.doctor().token(c.clinic()), emergency(1), 200);
        JsonNode back = ok(HttpMethod.PATCH, url(c.visitId()), c.doctor().token(c.clinic()), type("NEW"), 200);
        assertThat(back.get("visitType").asText()).isEqualTo("NEW");
        assertThat(back.get("triageLevel").isNull()).isTrue();
        assertThat(row(c.clinic(), "select triage_level::text, triage_scale::text from opd.visit where id = ?::uuid", c.visitId()))
                .containsExactly(null, null);
        List<List<String>> audits = auditRows(c.clinic(), "EMR_VISIT_TYPE_CHANGED");
        assertThat(audits).hasSize(2);
        assertThat(audits.get(1).get(1).replace(" ", "")).contains("\"triageLevel\":1").contains("\"visitType\":\"EMERGENCY\"");
        assertThat(audits.get(1).get(2).replace(" ", "")).contains("\"visitType\":\"NEW\"");
    }

    @Test
    void theSameEmergencyAndLevelAgainIs200AndWritesNothingAndAnotherLevelIsAChange() throws Exception {
        Ctx c = ctx();
        ok(HttpMethod.PATCH, url(c.visitId()), c.doctor().token(c.clinic()), emergency(2), 200);
        ok(HttpMethod.PATCH, url(c.visitId()), c.doctor().token(c.clinic()), emergency(2), 200);
        assertThat(auditRows(c.clinic(), "EMR_VISIT_TYPE_CHANGED")).hasSize(1);
        ok(HttpMethod.PATCH, url(c.visitId()), c.doctor().token(c.clinic()), emergency(1), 200);
        assertThat(auditRows(c.clinic(), "EMR_VISIT_TYPE_CHANGED")).hasSize(2);
    }

    @Test
    void anEmergencyVisitIsOnTheQueueAndItsRecordHeaderCarriesTheTypeAndASignedRecordRefusesTheChange() throws Exception {
        Ctx c = ctx();
        ok(HttpMethod.PATCH, url(c.visitId()), c.doctor().token(c.clinic()), emergency(3), 200);
        JsonNode record = openVisit(c.doctor().token(c.clinic()), c.visitId(), 201).get("record");
        assertThat(record.get("header").get("visitType").asText()).isEqualTo("EMERGENCY");
        Rec rec = new Rec(c.clinic(), c.doctor(), UUID.randomUUID(), boardVisit(c.clinic(), c.visitId()), record);
        Rec filled = fillRequired(rec);
        signOk(filled);
        problemOf(HttpMethod.PATCH, url(c.visitId()), c.doctor().token(c.clinic()), type("NEW"), 409, "emr-record-signed");
        assertThat(row(c.clinic(), "select visit_type, triage_level::text from opd.visit where id = ?::uuid", c.visitId()))
                .containsExactly("EMERGENCY", "3");
    }

    @Test
    void frontDeskAndTheOtherRolesCannotSetEmergencyAndAnotherDoctorIs403NotOwnPatient() throws Exception {
        Ctx c = ctx();
        Staff other = addDoctor(c.clinic(), "Dentist");
        problemOf(HttpMethod.PATCH, url(c.visitId()), other.token(c.clinic()), emergency(2), 403, "not-own-patient");
        for (String role : NOT_DOCTOR_ROLES) {
            problemOf(HttpMethod.PATCH, url(c.visitId()), addNonDoctor(c.clinic(), role).token(c.clinic()), emergency(2), 403, "forbidden");
        }
        problemOf(HttpMethod.PATCH, url(c.visitId()), anonymous(), emergency(2), 401, "unauthorized");
        Ctx foreign = ctx();
        assertThat(call(HttpMethod.PATCH, url(c.visitId()), foreign.doctor().token(foreign.clinic()), emergency(2)).andReturn().getResponse().getStatus())
                .isEqualTo(404);
        assertThat(row(c.clinic(), "select visit_type from opd.visit where id = ?::uuid", c.visitId())).containsExactly("NEW");
    }

    // ---------------- the fee of the visit (H11) ----------------

    @Test
    void whileTheBillHasNoPaymentANewTypeWorksTheFeeOutAgainAndAPaidBillKeepsTheFeeAndSaysAlreadyBilled() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        UUID patient = newPatient(clinic);
        ok(HttpMethod.POST, VISITS, clinic.owner(), "{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\"" + doctor.membershipId() + "\"}", 201);
        // the second visit of the same patient and doctor inside the follow-up window is a follow-up at the clinic's follow-up fee
        JsonNode second = ok(HttpMethod.POST, VISITS, clinic.owner(),
                "{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\"" + doctor.membershipId() + "\"}", 201);
        String id = second.get("id").asText();
        assertThat(second.get("visitType").asText()).isEqualTo("FOLLOW_UP");
        long followUpFee = second.get("feePaise").asLong();
        JsonNode asNew = ok(HttpMethod.PATCH, url(id), doctor.token(clinic), type("NEW"), 200);
        assertThat(asNew.get("feePaise").asLong()).as("the doctor's full fee again").isEqualTo(25000L);
        assertThat(asNew.get("feeChange").get("outcome").asText()).isEqualTo(followUpFee == 25000L ? "UNCHANGED" : "UPDATED");
        assertThat(row(clinic, "select fee_paise::text from opd.visit where id = ?::uuid", id)).containsExactly("25000");
        // a payment on the bill: the type changes, the fee does not
        ok(HttpMethod.POST, BILLING + "/bills/" + id + "/payments", clinic.owner(), "{\"mode\":\"CASH\",\"amountPaise\":25000}", 200);
        JsonNode paid = ok(HttpMethod.PATCH, url(id), doctor.token(clinic), type("FOLLOW_UP"), 200);
        assertThat(paid.get("visitType").asText()).isEqualTo("FOLLOW_UP");
        assertThat(paid.get("feePaise").asLong()).isEqualTo(25000L);
        assertThat(paid.get("feeChange").get("outcome").asText()).isEqualTo("ALREADY_BILLED");
        List<List<String>> audits = auditRows(clinic, "EMR_VISIT_TYPE_CHANGED");
        assertThat(audits.get(audits.size() - 1).get(2)).contains("25000");
    }

    @Test
    void emergencyGetsTheFullFeeAndNeverTheFollowUpFee() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        UUID patient = newPatient(clinic);
        ok(HttpMethod.POST, VISITS, clinic.owner(), "{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\"" + doctor.membershipId() + "\"}", 201);
        String id = ok(HttpMethod.POST, VISITS, clinic.owner(),
                "{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\"" + doctor.membershipId() + "\"}", 201).get("id").asText();
        JsonNode emergency = ok(HttpMethod.PATCH, url(id), doctor.token(clinic), emergency(2), 200);
        assertThat(emergency.get("feePaise").asLong()).isEqualTo(25000L);
    }
}
