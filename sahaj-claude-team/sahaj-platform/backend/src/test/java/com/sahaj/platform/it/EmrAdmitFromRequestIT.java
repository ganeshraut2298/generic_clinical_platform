package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-67-3 completed: the admit roles turn a doctor's admission request into an admission (ipd.yaml 0.3.0
 * {@code POST /admissions} with {@code admissionRequestId}, ADR-025 D10): the request closes with the admission id, every
 * other open request of the patient closes with it, the visit record shows Admitted with the IP number, the consultation can
 * still be signed, and a patient who is already admitted is refused with 409 already-admitted. Test plan:
 * docs/test-plans/SAH-51-68.md (T-67-3.x).
 */
@OnTestDatabase
class EmrAdmitFromRequestIT extends EmrIpdSupport {

    private static final String REQUEST_BODY = "{\"admissionType\":\"planned\",\"plannedStayDays\":4}";

    private record Visit(Clinic clinic, IpdBillingSupport.Person person, RequestPostProcessor doctor, JsonNode record) {

        String id() {
            return record.get("id").asText();
        }
    }

    private Visit visit(Clinic clinic, IpdBillingSupport.Person person, RequestPostProcessor doctor) throws Exception {
        String visitId = visitOf(clinic, person.id());
        JsonNode opened = openRecord(doctor, "OPD_VISIT", visitId, 201).get("record");
        return new Visit(clinic, person, doctor, fillTyped(doctor, opened));
    }

    private Visit visit() throws Exception {
        Clinic clinic = hospital();
        return visit(clinic, person(clinic, "Test Admit Person"), admittingDoctor(clinic));
    }

    private String request(Visit v) throws Exception {
        return okKeyed(HttpMethod.POST, EMR_RECORDS + "/" + v.id() + "/admission-request", v.doctor(), REQUEST_BODY, idemKey(), 201)
                .get("id").asText();
    }

    private JsonNode admit(Visit v, Object requestId) throws Exception {
        return ok(HttpMethod.POST, ADMISSIONS, v.clinic().owner(), js(admitBodyFor(v.clinic(), v.person().id(), requestId)), 201);
    }

    private List<String> requestRow(Clinic clinic, String requestId) throws Exception {
        return values(clinic, "select status, admission_id::text from opd.emr_admission_request where id = ?::uuid", UUID.fromString(requestId));
    }

    // ---------------- completing a request ----------------

    @Test
    void theOwnerAdmitsFromTheRequestAndTheRequestClosesWithTheAdmissionTheRecordShowsAdmittedAndTheListIsEmpty() throws Exception {
        Visit v = visit();
        String requestId = request(v);
        assertThat(ok(HttpMethod.GET, IPD_REQUESTS, v.clinic().owner(), null, 200).get("items")).hasSize(1);
        JsonNode admission = admit(v, requestId);
        assertThat(requestRow(v.clinic(), requestId)).containsExactly("ADMITTED", admission.get("id").asText());
        assertThat(values(v.clinic(), "select admission_request_id::text from ipd.admission where id = ?::uuid",
                UUID.fromString(admission.get("id").asText())).get(0)).isEqualTo(requestId);
        assertThat(ok(HttpMethod.GET, IPD_REQUESTS, v.clinic().owner(), null, 200).get("items")).isEmpty();
        JsonNode state = getRecord(v.doctor(), v.id()).get("admission");
        assertThat(state.get("state").asText()).isEqualTo("ADMITTED");
        assertThat(state.get("admissionId").asText()).isEqualTo(admission.get("id").asText());
        assertThat(state.get("ipNumber").asText()).isEqualTo(admission.get("ipNumber").asText());
    }

    @Test
    void admissionDoesNotEndTheConsultationTheDoctorStillSignsTheVisit() throws Exception {
        Visit v = visit();
        admit(v, request(v));
        JsonNode view = getRecord(v.doctor(), v.id());
        assertThat(view.get("status").asText()).isEqualTo("DRAFT");
        JsonNode result = sign(v.doctor(), view, "{}");
        assertThat(result.get("record").get("status").asText()).isEqualTo("SIGNED");
        assertThat(result.get("record").get("admission").get("state").asText()).isEqualTo("ADMITTED");
    }

    @Test
    void admittingThePatientWithoutNamingTheRequestStillClosesEveryOpenRequestOfThatPatient() throws Exception {
        Visit first = visit();
        Visit second = visit(first.clinic(), first.person(), first.doctor());
        String r1 = request(first);
        String r2 = request(second);
        admit(first, null);
        assertThat(requestRow(first.clinic(), r1).get(0)).isEqualTo("ADMITTED");
        assertThat(requestRow(first.clinic(), r2).get(0)).isEqualTo("ADMITTED");
        assertThat(ok(HttpMethod.GET, IPD_REQUESTS, first.clinic().owner(), null, 200).get("items")).isEmpty();
    }

    @Test
    void nurseAndFrontDeskMayCompleteTheAdmissionAndADoctorWhoIsNotTheOwnerMayNot() throws Exception {
        Visit v = visit();
        String requestId = request(v);
        Map<String, Object> body = admitBodyFor(v.clinic(), v.person().id(), requestId);
        problemOf(HttpMethod.POST, ADMISSIONS, v.doctor(), js(body), 403, "forbidden");
        assertThat(requestRow(v.clinic(), requestId).get(0)).isEqualTo("OPEN");
        Pn nurse = newPn(v.clinic(), "NURSE");
        JsonNode admission = ok(HttpMethod.POST, ADMISSIONS, nurse.auth(), js(body), 201);
        assertThat(requestRow(v.clinic(), requestId)).containsExactly("ADMITTED", admission.get("id").asText());
    }

    // ---------------- refusals ----------------

    @Test
    void aRequestThatIsUnknownOrBelongsToAnotherPatientIs409AndNoAdmissionIsMade() throws Exception {
        Visit v = visit();
        String requestId = request(v);
        long admissions = count(v.clinic(), "select count(*) from ipd.admission");
        IpdBillingSupport.Person other = person(v.clinic(), "Test Other Person");
        problemOf(HttpMethod.POST, ADMISSIONS, v.clinic().owner(), js(admitBodyFor(v.clinic(), other.id(), requestId)), 409,
                "admission-request-not-available");
        problemOf(HttpMethod.POST, ADMISSIONS, v.clinic().owner(), js(admitBodyFor(v.clinic(), v.person().id(), UUID.randomUUID())), 409,
                "admission-request-not-available");
        assertThat(count(v.clinic(), "select count(*) from ipd.admission")).isEqualTo(admissions);
        assertThat(requestRow(v.clinic(), requestId).get(0)).isEqualTo("OPEN");
    }

    @Test
    void anotherClinicsRequestIsNotUsableAndNotListed() throws Exception {
        Visit v = visit();
        String requestId = request(v);
        Visit foreign = visit();
        problemOf(HttpMethod.POST, ADMISSIONS, foreign.clinic().owner(), js(admitBodyFor(foreign.clinic(), foreign.person().id(), requestId)), 409,
                "admission-request-not-available");
        assertThat(ok(HttpMethod.GET, IPD_REQUESTS, foreign.clinic().owner(), null, 200).get("items")).isEmpty();
        assertThat(requestRow(v.clinic(), requestId).get(0)).isEqualTo("OPEN");
    }

    @Test
    void aPatientWhoIsAlreadyAdmittedIs409AlreadyAdmittedWithTheIpNumberAndThePrefillAndTheRecordSayAlreadyAdmitted() throws Exception {
        Visit v = visit();
        JsonNode admission = admit(v, null);
        JsonNode problem = problemKeyed(HttpMethod.POST, EMR_RECORDS + "/" + v.id() + "/admission-request", v.doctor(), REQUEST_BODY,
                idemKey(), 409, "already-admitted");
        assertThat(problem.get("ipNumber").asText()).isEqualTo(admission.get("ipNumber").asText());
        assertThat(problem.get("detail").asText()).startsWith("Already admitted");
        JsonNode prefill = ok(HttpMethod.GET, EMR_RECORDS + "/" + v.id() + "/admission-prefill", v.doctor(), null, 200);
        assertThat(prefill.get("openAdmission").get("admissionId").asText()).isEqualTo(admission.get("id").asText());
        assertThat(getRecord(v.doctor(), v.id()).get("admission").get("state").asText()).isEqualTo("ALREADY_ADMITTED");
        assertThat(count(v.clinic(), "select count(*) from opd.emr_admission_request")).isZero();
    }

    @Test
    void theFreshNameAndMobileOfThePrefillAreForTheScreenOnlyAndNeverInTheAuditOrTheAdmissionAudit() throws Exception {
        Visit v = visit();
        String requestId = request(v);
        admit(v, requestId);
        for (String action : new String[] {"EMR_ADMISSION_REQUESTED", "IPD_ADMITTED"}) {
            for (String[] a : audit(v.clinic(), action)) {
                assertThat(a[3]).as(action).doesNotContain("Test Admit Person").doesNotContain(v.person().mobile());
            }
        }
    }
}
