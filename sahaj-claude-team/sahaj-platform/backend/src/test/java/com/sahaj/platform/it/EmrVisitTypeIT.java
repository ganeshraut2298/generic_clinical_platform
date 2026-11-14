package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-52-2 and the Visit changes of 0.3.0: the doctor changes the OPD visit type from the EMR
 * ({@code PATCH /visits/{id}/visit-type}: New, Follow-up, Revisit, and Emergency with a triage level since SAH-67-4, see
 * EmrEmergencyVisitTypeIT), the Queue card shows the
 * new type and carries {@code emrRecordId} and {@code emrStatus}, one audit entry records the old and the new type, and the
 * database accepts REVISIT on a visit but not on an appointment. Test plan: docs/test-plans/SAH-53.md.
 */
@OnTestDatabase
class EmrVisitTypeIT extends EmrSupport {

    private static String url(Object visitId) {
        return VISITS + "/" + visitId + "/visit-type";
    }

    private static String body(String type) {
        return "{\"visitType\":\"" + type + "\"}";
    }

    private JsonNode onBoard(Clinic clinic, String visitId) throws Exception {
        for (JsonNode v : get(VISITS + "/today", clinic.owner())) {
            if (visitId.equals(v.get("id").asText())) {
                return v;
            }
        }
        throw new AssertionError("visit " + visitId + " is not on the board");
    }

    @Test
    void theDoctorChangesTheTypeOfTheirOwnVisitAndTheQueueCardTheRecordHeaderAndOneAuditEntryShowIt() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        JsonNode visit = issueVisit(clinic, doctor.membershipId());
        String id = visit.get("id").asText();
        assertThat(visit.get("visitType").asText()).isEqualTo("NEW");
        JsonNode revisit = ok(HttpMethod.PATCH, url(id), doctor.token(clinic), body("REVISIT"), 200);
        assertThat(revisit.get("visitType").asText()).isEqualTo("REVISIT");
        assertThat(revisit.get("id").asText()).isEqualTo(id);
        assertThat(revisit.get("tokenNo").asInt()).isEqualTo(visit.get("tokenNo").asInt());
        assertThat(revisit.get("stage").asText()).as("the stage and the fee are not touched").isEqualTo(visit.get("stage").asText());
        assertThat(revisit.get("feePaise").asLong()).isEqualTo(visit.get("feePaise").asLong());
        assertThat(revisit.get("emrStatus").asText()).isEqualTo("NONE");
        assertThat(revisit.get("emrRecordId").isNull()).isTrue();
        assertThat(onBoard(clinic, id).get("visitType").asText()).isEqualTo("REVISIT");
        assertThat(row(clinic, "select visit_type from opd.visit where id = ?::uuid", id)).containsExactly("REVISIT");
        assertThat(openVisit(doctor.token(clinic), id, 201).get("record").get("header").get("visitType").asText()).isEqualTo("REVISIT");
        // all three values can be set, in any order
        assertThat(ok(HttpMethod.PATCH, url(id), doctor.token(clinic), body("FOLLOW_UP"), 200).get("visitType").asText()).isEqualTo("FOLLOW_UP");
        assertThat(ok(HttpMethod.PATCH, url(id), doctor.token(clinic), body("NEW"), 200).get("visitType").asText()).isEqualTo("NEW");
        List<List<String>> audits = auditRows(clinic, "EMR_VISIT_TYPE_CHANGED");
        assertThat(audits).hasSize(3);
        assertThat(audits.get(0).get(1)).contains("NEW");
        assertThat(audits.get(0).get(2)).contains("REVISIT");
        assertThat(audits.get(1).get(1)).contains("REVISIT");
        assertThat(audits.get(1).get(2)).contains("FOLLOW_UP");
        assertThat(audits.get(0).get(3)).isEqualTo(doctor.userId().toString());
        for (List<String> a : audits) {
            assertThat(a.get(1) + a.get(2)).doesNotContain("Ramesh").doesNotContain("Kulkarni");
        }
        // the card of the opened visit carries the record id and status
        JsonNode card = onBoard(clinic, id);
        assertThat(card.get("emrRecordId").asText()).isNotBlank();
        assertThat(card.get("emrStatus").asText()).isEqualTo("DRAFT");
    }

    @Test
    void theSameTypeAsNowAnswers200AndWritesNothing() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        String id = issueVisit(clinic, doctor.membershipId()).get("id").asText();
        long version = Long.parseLong(row(clinic, "select version::text from opd.visit where id = ?::uuid", id).get(0));
        assertThat(ok(HttpMethod.PATCH, url(id), doctor.token(clinic), body("NEW"), 200).get("visitType").asText()).isEqualTo("NEW");
        assertThat(count(clinic, "select count(*) from platform.audit_event where action = 'EMR_VISIT_TYPE_CHANGED'")).isZero();
        assertThat(Long.parseLong(row(clinic, "select version::text from opd.visit where id = ?::uuid", id).get(0))).isEqualTo(version);
    }

    @Test
    void emergencyNeedsATriageLevelAndAnyOtherValueOrBodyIs400() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        String id = issueVisit(clinic, doctor.membershipId()).get("id").asText();
        // SAH-67-4 (ADR-025 D11): Emergency is available now but needs a level of the clinic's triage scale
        JsonNode problem = problemOf(HttpMethod.PATCH, url(id), doctor.token(clinic), body("EMERGENCY"), 400, "validation");
        assertThat(problem.toString()).contains("Choose a triage level");
        for (String bad : new String[] {body("WALK_IN"), body("new"), body(""), "{\"visitType\":null}", "{}", "{\"visitType\":1}",
                "{\"visitType\":\"REVISIT\",\"stage\":\"DONE\"}", "{\"visitType\":\"REVISIT\",\"feePaise\":0}"}) {
            JsonNode p = problem(call(HttpMethod.PATCH, url(id), doctor.token(clinic), bad), 400);
            assertThat(p.get("type").asText()).as(bad).isIn(PROBLEM_BASE + "validation", PROBLEM_BASE + "malformed-request");
        }
        problemOf(HttpMethod.PATCH, url(id), doctor.token(clinic), "", 400, "malformed-request");
        assertThat(row(clinic, "select visit_type from opd.visit where id = ?::uuid", id)).containsExactly("NEW");
        assertThat(count(clinic, "select count(*) from platform.audit_event where action = 'EMR_VISIT_TYPE_CHANGED'")).isZero();
    }

    @Test
    void onlyTheDoctorOfTheVisitOrAnOwnerWithDoctorDetailsCanChangeTheTypeAndFrontDeskSetsItAtRegistrationOnly() throws Exception {
        Clinic clinic = newClinic(true, false);
        Staff doctor = addDoctor(clinic, "General physician");
        Staff other = addDoctor(clinic, "Dentist");
        String id = issueVisit(clinic, doctor.membershipId()).get("id").asText();
        problemOf(HttpMethod.PATCH, url(id), other.token(clinic), body("REVISIT"), 403, "not-own-patient");
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(clinic, role);
            problemOf(HttpMethod.PATCH, url(id), staff.token(clinic), body("REVISIT"), 403, "forbidden");
            problemOf(HttpMethod.PATCH, url(id), staff.tokenClaiming(clinic, "DOCTOR"), body("REVISIT"), 403, "forbidden");
        }
        problemOf(HttpMethod.PATCH, url(id), anonymous(), body("REVISIT"), 401, "unauthorized");
        problemOf(HttpMethod.PATCH, url(id), clinic.owner(), body("REVISIT"), 403, "doctor-details-missing");
        assertThat(row(clinic, "select visit_type from opd.visit where id = ?::uuid", id)).containsExactly("NEW");
        ownerWithDoctorDetails(clinic, "General physician", "MBBS", "REG-" + shortId());
        assertThat(ok(HttpMethod.PATCH, url(id), clinic.owner(), body("REVISIT"), 200).get("visitType").asText()).isEqualTo("REVISIT");
        // front desk registers the visit: POST /visits still gives New or Follow-up and takes no visit type
        UUID patient = newPatient(clinic);
        JsonNode registered = ok(HttpMethod.POST, VISITS, clinic.as("FRONT_DESK"),
                "{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\"" + doctor.membershipId() + "\"}", 201);
        assertThat(registered.get("visitType").asText()).isIn("NEW", "FOLLOW_UP");
    }

    @Test
    void anotherClinicsVisitAndAnUnknownVisitAreNotFound() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        Staff doctorA = addDoctor(a, "General physician");
        Staff doctorB = addDoctor(b, "General physician");
        String idA = issueVisit(a, doctorA.membershipId()).get("id").asText();
        assertThat(call(HttpMethod.PATCH, url(idA), doctorB.token(b), body("REVISIT")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(call(HttpMethod.PATCH, url(UUID.randomUUID()), doctorB.token(b), body("REVISIT")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(row(a, "select visit_type from opd.visit where id = ?::uuid", idA)).containsExactly("NEW");
    }

    @Test
    void theDatabaseAcceptsRevisitOnAVisitRefusesOtherValuesAndKeepsRevisitOutOfAppointments() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        String id = issueVisit(clinic, doctor.membershipId()).get("id").asText();
        db().inTenant(clinic.tenantId(), c -> {
            expectOk(c, "update opd.visit set visit_type = 'REVISIT' where id = ?::uuid", id);
            expectOk(c, "update opd.visit set visit_type = 'FOLLOW_UP' where id = ?::uuid", id);
            expectOk(c, "update opd.visit set visit_type = 'NEW' where id = ?::uuid", id);
            assertThat(expectError(c, "update opd.visit set visit_type = 'WALKIN' where id = ?::uuid", id).state()).isEqualTo("23514");
            // EMERGENCY is in the type list since V2026_12_12_1050 but needs a triage level (ck_visit_triage_emergency): still refused alone
            assertThat(expectError(c, "update opd.visit set visit_type = 'EMERGENCY' where id = ?::uuid", id).state()).isEqualTo("23514");
            return null;
        });
        List<String> visitChecks = globalRows("select pg_get_constraintdef(oid) from pg_constraint where conrelid = 'opd.visit'::regclass "
                + "and pg_get_constraintdef(oid) ilike '%visit_type%'").stream().map(r -> r.get(0)).toList();
        assertThat(String.join(" ", visitChecks)).contains("REVISIT").contains("FOLLOW_UP").contains("NEW");
        List<String> appointmentChecks = globalRows("select pg_get_constraintdef(oid) from pg_constraint where conrelid = 'opd.appointment'::regclass "
                + "and pg_get_constraintdef(oid) ilike '%visit_type%'").stream().map(r -> r.get(0)).toList();
        assertThat(appointmentChecks).isNotEmpty();
        assertThat(String.join(" ", appointmentChecks)).doesNotContain("REVISIT");
        assertThat(globalRows("select count(*) from pg_constraint where conrelid = 'opd.visit'::regclass and conname = 'uq_visit_tenant_id'")
                .get(0).get(0)).isEqualTo("1");
        assertThat(globalRows("select count(*) from pg_constraint where conrelid = 'opd.emr_record'::regclass and contype = 'f' "
                + "and confrelid = 'opd.visit'::regclass").get(0).get(0)).as("a record points at a visit of its own clinic").isNotEqualTo("0");
    }
}
