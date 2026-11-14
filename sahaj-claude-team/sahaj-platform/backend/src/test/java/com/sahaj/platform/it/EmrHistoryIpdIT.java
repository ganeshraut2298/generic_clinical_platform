package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.asUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-50 (ADR-022 D10), the IPD half: the patient's admissions in "Past visits" are read through ipd with the caller's scope.
 * An owner or a doctor sees every admission of the patient (their IPD scope is every patient); a visiting doctor has no IPD
 * access until story 34D, so every admission is left out of the rows, the counts and the search, and "some entries are
 * hidden" is true; the details of an admission the caller may not see are 404. Real database as {@code sahaj_app}.
 * Test plan: docs/test-plans/SAH-48-59.md.
 */
@OnTestDatabase
class EmrHistoryIpdIT extends IpdBillingSupport {

    private static final String EMR = "/api/v1/opd/emr";
    private static final String VISITS = "/api/v1/opd/visits";

    private RequestPostProcessor token(Clinic clinic, UUID membershipId, String role) throws Exception {
        UUID userId = UUID.fromString(values(clinic, "select user_id::text from platform.membership where id = ?::uuid", membershipId).get(0));
        return asUser(clinic.tenantId(), userId, role);
    }

    private record Opened(String visitId, String recordId, RequestPostProcessor token) {
    }

    private Opened openOpd(Clinic clinic, UUID patientId, UUID membershipId, String role) throws Exception {
        RequestPostProcessor token = token(clinic, membershipId, role);
        JsonNode visit = ok(HttpMethod.POST, VISITS, clinic.owner(),
                "{\"patientId\":\"" + patientId + "\",\"doctorMembershipId\":\"" + membershipId + "\"}", 201);
        JsonNode opened = ok(HttpMethod.POST, EMR + "/records/open", token,
                "{\"encounterType\":\"OPD_VISIT\",\"encounterId\":\"" + visit.get("id").asText() + "\"}", 201);
        return new Opened(visit.get("id").asText(), opened.get("record").get("id").asText(), token);
    }

    private JsonNode history(Opened opened, String query) throws Exception {
        return ok(HttpMethod.GET, EMR + "/records/" + opened.recordId() + "/past-visits" + (query == null ? "" : "?" + query), opened.token(), null, 200);
    }

    private String admit(Clinic clinic, UUID patientId, String reason, String type) throws Exception {
        Map<String, Object> m = body(patientId, clinic.doctorMembershipId(), newBed(clinic, "gen"));
        m.put("reason", reason);
        m.put("admissionType", type);
        return admitOk(clinic, m).get("id").asText();
    }

    @Test
    void aDoctorSeesThePatientsAdmissionsInTheListTheCountsAndTheSearchWithTitlePlaceAndDate() throws Exception {
        Clinic clinic = hospital();
        Person patient = person(clinic, "Mohan Joshi");
        String admission = admit(clinic, patient.id(), "Chest infection", "planned");
        String dayCare = admit(clinic, patient.id(), "Dressing change", "daycare");
        admit(clinic, newPatient(clinic), "Somebody else's admission", "planned");
        Opened opened = openOpd(clinic, patient.id(), clinic.doctorMembershipId(), "DOCTOR");
        JsonNode page = history(opened, null);
        assertThat(page.get("counts").get("ipd").asInt()).isEqualTo(2);
        assertThat(page.get("counts").get("opd").asInt()).isZero();
        assertThat(page.get("someHidden").asBoolean()).isFalse();
        assertThat(page.get("items")).hasSize(2);
        JsonNode row = null;
        JsonNode daycare = null;
        for (JsonNode item : page.get("items")) {
            assertThat(item.get("kind").asText()).as("the IPD chip").isEqualTo("IPD");
            assertThat(item.get("signed").asBoolean()).isTrue();
            assertThat(item.get("date").asText()).isEqualTo(istToday().toString());
            if (item.get("id").asText().equals(admission)) {
                row = item;
            }
            if (item.get("id").asText().equals(dayCare)) {
                daycare = item;
            }
        }
        assertThat(row.get("title").asText()).isEqualTo("Chest infection");
        assertThat(row.get("place").asText()).startsWith("Dr Asha Rao · General ward bed ");
        assertThat(daycare.get("title").asText()).isEqualTo("Dressing change · day care");
        // the type filter and the search
        assertThat(history(opened, "type=IPD").get("items")).hasSize(2);
        assertThat(history(opened, "type=OPD").get("items")).isEmpty();
        assertThat(history(opened, "q=chest").get("items")).hasSize(1);
        assertThat(history(opened, "q=" + EmrSupport.enc("DAY CARE")).get("items")).hasSize(1);
        assertThat(history(opened, "q=general+ward".replace("+", "%20")).get("items")).as("the ward is part of the place").hasSize(2);
        assertThat(history(opened, "q=somebody").get("items")).as("another patient's admission").isEmpty();
        // the details of an admission: dates and the reason; the page opens the discharge summary of the admission itself
        JsonNode detail = ok(HttpMethod.GET, EMR + "/records/" + opened.recordId() + "/past-visits/IPD/" + admission, opened.token(), null, 200);
        assertThat(detail.get("kind").asText()).isEqualTo("IPD");
        assertThat(detail.get("signed").asBoolean()).isTrue();
        assertThat(detail.get("open").get("target").asText()).isEqualTo("ADMISSION");
        assertThat(detail.get("open").get("id").asText()).isEqualTo(admission);
        assertThat(detail.get("canCopyMedicines").asBoolean()).isFalse();
        assertThat(detail.get("canRepeatTests").asBoolean()).isFalse();
        boolean reason = false;
        boolean admitted = false;
        for (JsonNode line : detail.get("lines")) {
            reason |= "Reason for admission".equals(line.get("label").asText()) && "Chest infection".equals(line.get("value").asText());
            admitted |= "Admitted".equals(line.get("label").asText());
        }
        assertThat(reason && admitted).isTrue();
        // an admission of another patient is not a past visit of this one
        String stranger = admit(clinic, newPatient(clinic), "Stranger admission", "planned");
        problem(call(HttpMethod.GET, EMR + "/records/" + opened.recordId() + "/past-visits/IPD/" + stranger, opened.token(), null), 404);
        problem(call(HttpMethod.GET, EMR + "/records/" + opened.recordId() + "/past-visits/OPD/" + admission, opened.token(), null), 404);
    }

    @Test
    void aVisitingDoctorHasNoIpdAccessSoEveryAdmissionIsLeftOutOfRowsCountsAndSearchAndSomeEntriesAreHiddenIsTrue() throws Exception {
        Clinic clinic = hospital();
        Person patient = person(clinic, "Mohan Joshi");
        String admission = admit(clinic, patient.id(), "Chest infection", "planned");
        Member visiting = addMember(clinic, "VISITING_DOCTOR");
        Opened opened = openOpd(clinic, patient.id(), visiting.membershipId(), "VISITING_DOCTOR");
        long ipdAuditsBefore = ipdAudits(clinic);
        long accessBefore = recordAccess(clinic, patient.id());
        JsonNode page = history(opened, null);
        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("counts").get("ipd").asInt()).as("hidden admissions are not even counted").isZero();
        assertThat(page.get("someHidden").asBoolean()).isTrue();
        assertThat(history(opened, "type=IPD").get("items")).isEmpty();
        assertThat(history(opened, "type=IPD").get("someHidden").asBoolean()).isTrue();
        assertThat(history(opened, "q=chest").get("items")).as("a search never finds a hidden entry").isEmpty();
        assertThat(history(opened, "q=chest").get("counts").get("ipd").asInt()).isZero();
        problem(call(HttpMethod.GET, EMR + "/records/" + opened.recordId() + "/past-visits/IPD/" + admission, opened.token(), null), 404);
        // the doctor of the same clinic still sees it: the rule is the caller's, not the patient's
        Opened doctor = openOpd(clinic, patient.id(), clinic.doctorMembershipId(), "DOCTOR");
        assertThat(history(doctor, null).get("counts").get("ipd").asInt()).isEqualTo(1);
        assertThat(history(doctor, null).get("someHidden").asBoolean()).isFalse();
        // looking at the history breaks no glass: it opens no break-glass grant and writes no IPD record access
        assertThat(ipdAudits(clinic)).isEqualTo(ipdAuditsBefore);
        assertThat(count(clinic, "select count(*) from ipd.emergency_access_grant")).isZero();
        assertThat(recordAccess(clinic, patient.id())).isGreaterThanOrEqualTo(accessBefore);
    }

    @Test
    void theOwnerAlsoSeesEveryAdmissionAndAnotherClinicsAdmissionsOfTheSameNameNeverAppear() throws Exception {
        Clinic clinic = hospital();
        Person patient = person(clinic, "Mohan Joshi");
        admit(clinic, patient.id(), "Chest infection", "planned");
        Opened byOwner = openOpd(clinic, patient.id(), clinic.doctorMembershipId(), "DOCTOR");
        // the owner (with doctor details) reads the history of the doctor's visit
        JsonNode asOwner = ok(HttpMethod.GET, EMR + "/records/" + byOwner.recordId() + "/past-visits", clinic.owner(), null, 200);
        assertThat(asOwner.get("counts").get("ipd").asInt()).isEqualTo(1);
        Clinic other = hospital();
        Person same = person(other, "Mohan Joshi");
        admit(other, same.id(), "Foreign admission", "planned");
        assertThat(history(byOwner, null).toString()).doesNotContain("Foreign admission");
        assertThat(history(byOwner, "q=foreign").get("items")).isEmpty();
    }

    @Test
    void aClinicWithoutAnyAdmissionHasAnEmptyIpdSideAndNothingHidden() throws Exception {
        Clinic clinic = newClinic();
        Person patient = person(clinic, "Mohan Joshi");
        Opened opened = openOpd(clinic, patient.id(), clinic.doctorMembershipId(), "DOCTOR");
        JsonNode page = history(opened, null);
        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("counts").get("ipd").asInt()).isZero();
    }
}
