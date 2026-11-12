package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.application.emr.EmrMaster;
import com.sahaj.platform.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-43-3, SAH-44-4 and the EMR rules of "never log" (BR26) on the real application: while the EMR endpoints answer
 * successes and every kind of refusal, no log line (any level) holds the doctor's name, degree, registration number or
 * specialty text, the text typed in a search, the name of an unknown request field, or an item the doctor used. The
 * made-up marker words below make a leak easy to see. Test plan: docs/test-plans/SAH-43-44.md.
 */
@OnTestDatabase
class EmrLogPrivacyIT extends EmrSupport {

    private static final String NAME = "Dr Zxqvname Kulkarni";
    private static final String DEGREE = "ZxqvDegree MD";
    private static final String REGISTRATION = "ZXQREG99-77";
    private static final String SPECIALTY = "Zxqvspecialty";
    private static final String TYPED = "zxqvsearch";
    private static final String FIELD = "zxqvfield";

    @Test
    void noLogLineHoldsTheDoctorsDetailsTheSearchTextOrAnUnknownFieldNameAndNoErrorBodyEchoesThem() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addStaff(clinic, "DOCTOR", NAME, SPECIALTY, DEGREE, REGISTRATION);
        Staff nurse = addNonDoctor(clinic, "NURSE");
        String complaint = itemKeys(EmrMaster.COMPLAINTS, 1).get(0);
        StringBuilder bodies = new StringBuilder();
        String logs;
        try (LogCapture capture = new LogCapture()) {
            // the doctor's own details (the name is on this screen, never in a log)
            JsonNode me = get(ME, doctor.token(clinic));
            assertThat(me.get("name").asText()).isEqualTo(NAME);
            assertThat(me.get("registrationNo").asText()).isEqualTo(REGISTRATION);

            // searches with a made-up word, successes and refusals
            bodies.append(get(MASTERS + "complaints?q=" + TYPED, doctor.token(clinic)));
            bodies.append(get(MASTERS + "advice?q=" + TYPED + "&specialty=gp", nurse.token(clinic)));
            bodies.append(get(DIAGNOSES + "?q=" + TYPED, doctor.token(clinic)));
            bodies.append(get(MEDICINES + "?q=" + TYPED, doctor.token(clinic)));
            bodies.append(problem(call(HttpMethod.GET, MASTERS + "complaints?q=" + TYPED.repeat(20), doctor.token(clinic), null), 400));
            bodies.append(problem(call(HttpMethod.GET, MASTERS + "complaints?q=" + TYPED + "&specialty=" + TYPED, doctor.token(clinic), null), 400));
            bodies.append(problem(call(HttpMethod.GET, MASTERS + TYPED, doctor.token(clinic), null), 400));
            bodies.append(problem(call(HttpMethod.GET, QUICK + TYPED, doctor.token(clinic), null), 400));

            // quick options, the recorder and the refusals
            recordVisit(clinic, doctor.membershipId(), java.time.Instant.now(), ref(EmrMaster.COMPLAINTS, complaint));
            bodies.append(get(QUICK + "complaints?limit=5", doctor.token(clinic)));
            bodies.append(problem(call(HttpMethod.GET, QUICK + "complaints", nurse.token(clinic), null), 403));
            bodies.append(problem(call(HttpMethod.GET, ME, nurse.token(clinic), null), 403));

            // templates, overrides, reset, settings: successes, 400, 403, 404, 409
            bodies.append(get(TEMPLATES + "/gp", doctor.token(clinic)));
            bodies.append(put(overrideUrl("gp", "local_exam"), clinic.owner(), overrideBody(false, false, 0), 200));
            bodies.append(problem(call(HttpMethod.PUT, overrideUrl("gp", "local_exam"), clinic.owner(), overrideBody(false, false, 0)), 409));
            bodies.append(problem(call(HttpMethod.PUT, overrideUrl("gp", "prescription"), clinic.owner(), overrideBody(false, false, 1)), 400));
            bodies.append(problem(call(HttpMethod.PUT, overrideUrl("gp", "local_exam"), clinic.owner(),
                    "{\"shown\":true,\"required\":false,\"revision\":1,\"" + FIELD + "\":\"" + TYPED + "\"}"), 400));
            bodies.append(problem(call(HttpMethod.PUT, overrideUrl("gp", FIELD + "_x"), clinic.owner(), overrideBody(false, false, 1)), 404));
            bodies.append(problem(call(HttpMethod.PUT, overrideUrl("gp", "local_exam"), doctor.token(clinic), overrideBody(false, false, 1)), 403));
            bodies.append(post(resetUrl("gp"), clinic.owner(), "{\"revision\":1}", 200));
            bodies.append(put(SETTINGS, clinic.owner(), settingsBody("OPEN", "PAST_VISITS", 0), 200));
            bodies.append(problem(call(HttpMethod.PUT, SETTINGS, clinic.owner(),
                    "{\"sectionPanel\":\"OPEN\",\"rightPanelFirst\":\"CASE_PAPER\",\"version\":1,\"" + FIELD + "\":1}"), 400));
            bodies.append(problem(call(HttpMethod.PUT, SETTINGS, nurse.token(clinic), settingsBody("OPEN", "CASE_PAPER", 1)), 403));
            bodies.append(problem(call(HttpMethod.POST, MASTERS + "complaints", clinic.owner(), "{\"name\":\"" + TYPED + "\"}"), 403));
            logs = capture.allText();
        }
        assertThat(logs).as("the capture saw the EMR requests").contains("/api/v1/opd/emr");
        for (String secret : new String[] {"Zxqvname", NAME, DEGREE, REGISTRATION, "ZXQREG99", SPECIALTY, TYPED, FIELD,
                complaint}) {
            assertThat(logs).as("log holds '" + secret + "'").doesNotContain(secret);
        }
        for (String leak : new String[] {TYPED, FIELD, "Zxqvname", REGISTRATION}) {
            assertThat(bodies.toString()).as("a response body echoes '" + leak + "'").doesNotContain(leak);
        }
    }

    @Test
    void theResponsesOfTheOtherRolesNeverCarryTheDoctorsRegistrationNumber() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addStaff(clinic, "DOCTOR", NAME, "Dental", DEGREE, REGISTRATION);
        StringBuilder all = new StringBuilder();
        for (String role : ALL_ROLES) {
            all.append(get(TEMPLATES, clinic.as(role))).append(get(SETTINGS, clinic.as(role)))
                    .append(get(MASTERS + "complaints", clinic.as(role)));
        }
        all.append(get(TEMPLATES + "/dental", doctor.token(clinic))).append(get(QUICK + "advice", doctor.token(clinic)));
        assertThat(all.toString()).doesNotContain(REGISTRATION).doesNotContain("Zxqvname").doesNotContain(DEGREE);
    }
}
