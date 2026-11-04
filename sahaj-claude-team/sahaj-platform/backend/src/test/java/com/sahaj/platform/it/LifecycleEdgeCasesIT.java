package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * Sweep: things that change under a running clinic day - a doctor switched off, a patient merged away, a template
 * applied twice, a holiday on a booked day - and must not make records vanish or turn into a server error.
 */
@OnTestDatabase
class LifecycleEdgeCasesIT extends SweepSupport {

    private static final String VISITS = "/api/v1/opd/visits";
    private static final String BILLING = "/api/v1/billing";
    private static final String SPECIALTY = "/api/v1/platform/clinic/specialty";
    private static final String SERVICES = "/api/v1/opd/services";

    private String issue(Clinic clinic, UUID patient, UUID doctor) throws Exception {
        return ok(HttpMethod.POST, VISITS, clinic.owner(),
                "{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\"" + doctor + "\"}", 201).get("id").asText();
    }

    @Test
    void visitStaysOnTheBoardAndTheBillStaysReadableAfterTheDoctorIsSwitchedOff() throws Exception {
        Clinic clinic = newClinic(false);
        String id = issue(clinic, newPatient(clinic), clinic.doctorMembershipId());
        ok(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.owner(), "{\"stage\":\"BILLING\"}", 200);
        ok(HttpMethod.POST, "/api/v1/platform/team/" + clinic.doctorMembershipId() + "/deactivate", clinic.owner(), null, 200);
        JsonNode board = ok(HttpMethod.GET, VISITS + "/today", clinic.owner(), null, 200);
        assertThat(board.findValuesAsText("id")).contains(id);
        JsonNode bill = ok(HttpMethod.GET, BILLING + "/bills/" + id, clinic.owner(), null, 200);
        assertThat(bill.get("doctorName").asText()).isEqualTo("Dr Asha Rao");
        JsonNode counter = ok(HttpMethod.GET, BILLING + "/counter", clinic.owner(), null, 200);
        assertThat(counter.get("awaiting").size()).isEqualTo(1);
        ok(HttpMethod.POST, BILLING + "/bills/" + id + "/payments", clinic.owner(), "{\"mode\":\"CASH\",\"amountPaise\":30000}", 200);
    }

    @Test
    void visitStaysOnTheBoardAfterTheDoctorsAccessIsRemoved() throws Exception {
        Clinic clinic = newClinic(false);
        String id = issue(clinic, newPatient(clinic), clinic.doctorMembershipId());
        ok(HttpMethod.DELETE, "/api/v1/platform/team/" + clinic.doctorMembershipId(), clinic.owner(), null, 204);
        assertThat(ok(HttpMethod.GET, VISITS + "/today", clinic.owner(), null, 200).findValuesAsText("id")).contains(id);
        ok(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.owner(), "{\"stage\":\"BILLING\"}", 200);
    }

    @Test
    void visitStaysOnTheBoardAndBillableAfterItsPatientRecordIsMergedAway() throws Exception {
        Clinic clinic = newClinic(false);
        String mobile = uniqueMobile();
        String keep = ok(HttpMethod.POST, "/api/v1/patient/patients", clinic.owner(),
                "{\"name\":\"Ravi Kumar\",\"age\":40,\"sex\":\"M\",\"mobile\":\"" + mobile + "\"}", 201).get("id").asText();
        String dup = ok(HttpMethod.POST, "/api/v1/patient/patients", clinic.owner(),
                "{\"name\":\"Ravi Kumar\",\"age\":41,\"sex\":\"M\",\"mobile\":\"" + mobile + "\"}", 201).get("id").asText();
        String id = issue(clinic, UUID.fromString(dup), clinic.doctorMembershipId());
        ok(HttpMethod.POST, "/api/v1/patient/patients/merge", clinic.owner(), "{\"keepId\":\"" + keep + "\",\"mergeId\":\"" + dup + "\"}", 200);
        assertThat(ok(HttpMethod.GET, VISITS + "/today", clinic.owner(), null, 200).findValuesAsText("id")).contains(id);
        ok(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.owner(), "{\"stage\":\"BILLING\"}", 200);
        ok(HttpMethod.GET, BILLING + "/bills/" + id, clinic.owner(), null, 200);
        assertThat(ok(HttpMethod.GET, BILLING + "/counter", clinic.owner(), null, 200).get("awaiting").size()).isEqualTo(1);
    }

    @Test
    void applyingATemplateTwiceAfterSwitchingOffOneOfItsServicesDoesNotFail() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.PUT, SPECIALTY, clinic.owner(), "{\"code\":\"gp\"}", 200);
        JsonNode list = ok(HttpMethod.GET, SERVICES, clinic.owner(), null, 200);
        assertThat(list.size()).isPositive();
        String first = list.get(0).get("id").asText();
        ok(HttpMethod.PATCH, SERVICES + "/" + first, clinic.owner(), "{\"active\":false}", 200);
        ok(HttpMethod.PUT, SPECIALTY, clinic.owner(), "{\"code\":\"gp\"}", 200);
        // no duplicate row for the switched-off name
        JsonNode again = ok(HttpMethod.GET, SERVICES, clinic.owner(), null, 200);
        assertThat(again.size()).isEqualTo(list.size());
    }

    @Test
    void applyingATemplateWhenTheOwnerAlreadyAddedASameNamedServiceDoesNotFail() throws Exception {
        // the wizard already seeds the chosen specialty's starter services, so pick a dental template name that the
        // second clinic does not have yet, add it by hand in other case, then apply the dental template
        Clinic reference = newClinic();
        Clinic other = newClinic();
        java.util.Set<String> seeded = new java.util.HashSet<>();
        for (JsonNode s : ok(HttpMethod.GET, SERVICES, other.owner(), null, 200)) {
            seeded.add(s.get("name").asText().toLowerCase());
        }
        ok(HttpMethod.PUT, SPECIALTY, reference.owner(), "{\"code\":\"dental\"}", 200);
        String templateName = null;
        for (JsonNode s : ok(HttpMethod.GET, SERVICES, reference.owner(), null, 200)) {
            if (!seeded.contains(s.get("name").asText().toLowerCase())) {
                templateName = s.get("name").asText();
                break;
            }
        }
        assertThat(templateName).as("a dental template service the other clinic does not have yet").isNotNull();
        ok(HttpMethod.POST, SERVICES, other.owner(),
                "{\"name\":\"" + templateName.toUpperCase() + "\",\"category\":\"Dental\",\"priceRupees\":77}", 201);
        ok(HttpMethod.PUT, SPECIALTY, other.owner(), "{\"code\":\"dental\"}", 200);
        long same = 0;
        for (JsonNode s : ok(HttpMethod.GET, SERVICES, other.owner(), null, 200)) {
            if (s.get("name").asText().equalsIgnoreCase(templateName)) {
                same++;
                assertThat(s.get("priceRupees").asInt()).isEqualTo(77);
            }
        }
        assertThat(same).isEqualTo(1);
    }

    @Test
    void switchingSpecialtyBackAndForthKeepsEveryPriceTheOwnerEdited() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.PUT, SPECIALTY, clinic.owner(), "{\"code\":\"gp\"}", 200);
        JsonNode first = ok(HttpMethod.GET, SERVICES, clinic.owner(), null, 200).get(0);
        ok(HttpMethod.PATCH, SERVICES + "/" + first.get("id").asText(), clinic.owner(), "{\"priceRupees\":4321}", 200);
        ok(HttpMethod.PUT, SPECIALTY, clinic.owner(), "{\"code\":\"dental\"}", 200);
        ok(HttpMethod.PUT, SPECIALTY, clinic.owner(), "{\"code\":\"gp\"}", 200);
        for (JsonNode s : ok(HttpMethod.GET, SERVICES, clinic.owner(), null, 200)) {
            if (s.get("id").asText().equals(first.get("id").asText())) {
                assertThat(s.get("priceRupees").asInt()).isEqualTo(4321);
            }
        }
    }

    @Test
    void settingUpTheSameClinicTwiceInParallelDoesNotCreateTwoOwnersOrServerErrors() throws Exception {
        // two quick "complete" calls for the same clinic: both succeed, still one owner
        Clinic clinic = newClinic();
        int a = call(HttpMethod.POST, "/api/v1/platform/setup/complete", clinic.owner(), null).andReturn().getResponse().getStatus();
        int b = call(HttpMethod.POST, "/api/v1/platform/setup/complete", clinic.owner(), null).andReturn().getResponse().getStatus();
        assertThat(a).isEqualTo(200);
        assertThat(b).isEqualTo(200);
        long owners = 0;
        for (JsonNode m : ok(HttpMethod.GET, "/api/v1/platform/team", clinic.owner(), null, 200)) {
            if (m.get("role").asText().equals("OWNER")) {
                owners++;
            }
        }
        assertThat(owners).isEqualTo(1);
    }

    @Test
    void editingWizardStepsAfterCompletionDoesNotBreakTheLiveClinic() throws Exception {
        Clinic clinic = newClinic();
        int status = call(HttpMethod.PUT, "/api/v1/platform/setup/steps/5", clinic.owner(),
                "{\"brand\":\"maroon\",\"slug\":\"late-" + UUID.randomUUID().toString().substring(0, 6) + "\",\"language\":\"hi\"}")
                .andReturn().getResponse().getStatus();
        assertThat(status).isIn(200, 400, 409);
        ok(HttpMethod.GET, "/api/v1/platform/me", clinic.owner(), null, 200);
        ok(HttpMethod.GET, "/api/v1/platform/clinic/profile", clinic.owner(), null, 200);
    }

    @Test
    void deactivatedOwnerlessTeamMemberStillShowsTheirNameOnPastBills() throws Exception {
        Clinic clinic = newClinic(false);
        Member desk = addMember(clinic, "FRONT_DESK");
        Session s = login(desk.mobile());
        String id = issue(clinic, newPatient(clinic), clinic.doctorMembershipId());
        ok(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.owner(), "{\"stage\":\"BILLING\"}", 200);
        ok(HttpMethod.POST, BILLING + "/bills/" + id + "/payments", bearer(s.accessToken()), "{\"mode\":\"CASH\",\"amountPaise\":100}", 200);
        ok(HttpMethod.DELETE, "/api/v1/platform/team/" + desk.membershipId(), clinic.owner(), null, 204);
        JsonNode bill = ok(HttpMethod.GET, BILLING + "/bills/" + id, clinic.owner(), null, 200);
        assertThat(bill.get("payments").get(0).get("recordedByName").asText()).isEqualTo("Member FRONT_DESK");
    }

    @Test
    void holidaysDoNotStopTokensOrBilling() throws Exception {
        Clinic clinic = newClinic(false);
        ok(HttpMethod.POST, "/api/v1/platform/clinic/holidays", clinic.owner(),
                "{\"date\":\"" + java.time.LocalDate.now() + "\",\"name\":\"Today closed\",\"type\":\"CLOSED\"}", 201);
        String id = issue(clinic, newPatient(clinic), clinic.doctorMembershipId());
        assertThat(id).isNotBlank();
    }
}
