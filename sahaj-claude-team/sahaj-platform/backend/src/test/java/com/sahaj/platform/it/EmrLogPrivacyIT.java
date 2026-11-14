package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrMaster;
import com.sahaj.platform.support.LogCapture;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;

/**
 * SAH-43-3, SAH-53-5, SAH-46-3, SAH-47 and the EMR rules of "never log" (BR26, BR45-5) on the real application: while the
 * EMR endpoints answer successes and every kind of refusal, no log line holds the doctor's name, degree, registration
 * number or specialty text, the patient's name or allergy text, the clinical text typed into a section, the referral
 * line, the text typed in a search, the name of an unknown request field, or an item the doctor used; no error body echoes
 * what was typed; and no audit entry holds a patient name or a value of a section. The made-up marker words make a leak
 * easy to see.
 *
 * <p>Two views of the log are checked. "As configured" is what the application writes with its own log levels (the
 * level a deployment starts with). "At debug" raises the root level to DEBUG, the strictest view, as the other privacy
 * tests of this project do ({@link LogCapture}); the debug lines of Spring's MockMvc test harness (logger names
 * starting {@code org.springframework.test.}) are left out, because they print the MockHttpServletRequest, which exists
 * only in a test. Test plan: docs/test-plans/SAH-53.md.
 */
@OnTestDatabase
class EmrLogPrivacyIT extends EmrSupport {

    private static final String NAME = "Dr Zxqvname Kulkarni";
    private static final String DEGREE = "ZxqvDegree MD";
    private static final String REGISTRATION = "ZXQREG99-77";
    private static final String SPECIALTY = "Zxqvspecialty";
    private static final String TYPED = "zxqvsearch";
    private static final String FIELD = "zxqvfield";
    private static final String PATIENT = "Zxqpatient Surnamefield";
    private static final String ALLERGY = "Zxqallergytext";
    private static final String CLINICAL = "Zxqclinicalnote";
    private static final String REFERRAL = "Zxqreferralline";
    private static final String BADVALUE = "Zxqbadvalue";

    private record Fixture(Clinic clinic, Staff doctor, Staff otherDoctor, Staff nurse, UUID visitId, String complaint) {
    }

    private Fixture fixture() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addStaff(clinic, "DOCTOR", NAME, SPECIALTY, DEGREE, REGISTRATION);
        Staff other = addDoctor(clinic, "Dental");
        Staff nurse = addNonDoctor(clinic, "NURSE");
        String mobile = uniqueMobile();
        JsonNode patient = ok(HttpMethod.POST, "/api/v1/patient/patients", clinic.owner(),
                "{\"name\":\"" + PATIENT + "\",\"age\":44,\"sex\":\"F\",\"mobile\":\"" + mobile
                        + "\",\"area\":\"Shivaji Nagar\",\"language\":\"mr\",\"allergy\":\"" + ALLERGY + "\"}", 201);
        JsonNode visit = ok(HttpMethod.POST, VISITS, clinic.owner(),
                "{\"patientId\":\"" + patient.get("id").asText() + "\",\"doctorMembershipId\":\"" + doctor.membershipId() + "\"}", 201);
        return new Fixture(clinic, doctor, other, nurse, UUID.fromString(visit.get("id").asText()),
                itemKeys(EmrMaster.COMPLAINTS, 1).get(0));
    }

    /** What the calls answered: successes and the bodies of the refusals (the echo check reads only the refusals). */
    private record Bodies(StringBuilder all, StringBuilder errors) {

        void ok(JsonNode body) {
            all.append(body);
        }

        void error(JsonNode problem) {
            all.append(problem);
            errors.append(problem);
        }
    }

    private void refused(Bodies bodies, HttpMethod method, String path, org.springframework.test.web.servlet.request.RequestPostProcessor who,
                         String body, int status) throws Exception {
        bodies.error(problem(call(method, path, who, body), status));
    }

    /** Every kind of EMR call, successes and refusals. */
    private Bodies emrCalls(Fixture f) throws Exception {
        Clinic clinic = f.clinic();
        Staff doctor = f.doctor();
        Staff nurse = f.nurse();
        Bodies bodies = new Bodies(new StringBuilder(), new StringBuilder());
        // the doctor's own details (the name is on this screen, never in a log)
        JsonNode me = get(ME, doctor.token(clinic));
        assertThat(me.get("name").asText()).isEqualTo(NAME);
        assertThat(me.get("registrationNo").asText()).isEqualTo(REGISTRATION);
        bodies.ok(me);

        // searches with a made-up word, successes and refusals
        bodies.ok(get(MASTERS + "complaints?q=" + TYPED, doctor.token(clinic)));
        bodies.ok(get(MASTERS + "advice?q=" + TYPED + "&specialty=gm", nurse.token(clinic)));
        bodies.ok(get(DIAGNOSES + "?q=" + TYPED, doctor.token(clinic)));
        bodies.ok(get(MEDICINES + "?q=" + TYPED, doctor.token(clinic)));
        refused(bodies, HttpMethod.GET, MASTERS + "complaints?q=" + TYPED.repeat(20), doctor.token(clinic), null, 400);
        refused(bodies, HttpMethod.GET, MASTERS + "complaints?q=" + TYPED + "&specialty=" + TYPED, doctor.token(clinic), null, 400);
        refused(bodies, HttpMethod.GET, MASTERS + TYPED, doctor.token(clinic), null, 400);
        refused(bodies, HttpMethod.GET, QUICK + TYPED, doctor.token(clinic), null, 400);

        // quick options, the recorder and the refusals
        recordVisit(clinic, doctor.membershipId(), Instant.now(), ref(EmrMaster.COMPLAINTS, f.complaint()));
        bodies.ok(get(QUICK + "complaints?limit=5", doctor.token(clinic)));
        refused(bodies, HttpMethod.GET, QUICK + "complaints", nurse.token(clinic), null, 403);
        refused(bodies, HttpMethod.GET, ME, nurse.token(clinic), null, 403);

        // library, starters and departments: successes, 400, 403, 404, 409
        bodies.ok(get(LIBRARY, doctor.token(clinic)));
        bodies.ok(get(STARTERS + "/gm", doctor.token(clinic)));
        refused(bodies, HttpMethod.GET, STARTERS + "/" + FIELD, doctor.token(clinic), null, 404);
        refused(bodies, HttpMethod.GET, LIBRARY + "/" + FIELD, doctor.token(clinic), null, 404);
        refused(bodies, HttpMethod.POST, LIBRARY, clinic.owner(), "{\"name\":\"" + TYPED + "\"}", 403);
        bodies.ok(get(DEPARTMENTS, doctor.token(clinic)));
        JsonNode view = get(DEPARTMENTS + "/gm", clinic.owner());
        ObjectNode body = layoutInput(view);
        setShown(body, "chronic", false, false);
        bodies.ok(saveDraft(clinic, "gm", body, 200));
        refused(bodies, HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), body.toString(), 409);
        ObjectNode fresh = layoutInput(get(DEPARTMENTS + "/gm", clinic.owner()));
        setShown(fresh, "rx", false, true);
        refused(bodies, HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), fresh.toString(), 400);
        ObjectNode withField = layoutInput(get(DEPARTMENTS + "/gm", clinic.owner()));
        withField.put(FIELD, TYPED);
        refused(bodies, HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), withField.toString(), 400);
        ObjectNode hideAllergy = layoutInput(get(DEPARTMENTS + "/gm", clinic.owner()));
        setShown(hideAllergy, "allergy", false, false);
        refused(bodies, HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), hideAllergy.toString(), 400);
        refused(bodies, HttpMethod.PUT, DEPARTMENTS + "/gm/draft", doctor.token(clinic), body.toString(), 403);
        refused(bodies, HttpMethod.POST, DEPARTMENTS + "/gm/publish", nurse.token(clinic), body.toString(), 403);
        refused(bodies, HttpMethod.GET, DEPARTMENTS + "/" + FIELD, clinic.owner(), null, 404);
        bodies.ok(publish(clinic, "gm", layoutInput(get(DEPARTMENTS + "/gm", clinic.owner())), 200));
        bodies.ok(get(DEPARTMENTS + "/gm/layout", nurse.token(clinic)));
        bodies.ok(get(DEPARTMENTS + "/gm/versions/1", nurse.token(clinic)));
        bodies.ok(get(DEPARTMENTS + "/gm/reset-target", nurse.token(clinic)));
        bodies.ok(get(DOCTOR_OPTIONS, clinic.owner()));
        refused(bodies, HttpMethod.POST, DEPARTMENTS, clinic.owner(),
                createBody("Zxq department", BLANK_SOURCE, List.of(), List.of(), true).replaceFirst("^\\{", "{\"" + FIELD + "\":1,"), 400);
        refused(bodies, HttpMethod.POST, DEPARTMENTS, clinic.owner(), createBody("General Medicine", BLANK_SOURCE, List.of(), List.of(), true), 400);

        // the record of the visit: open, read, save, refuse
        JsonNode opened = openVisit(doctor.token(clinic), f.visitId(), 201);
        bodies.ok(opened);
        String id = opened.get("record").get("id").asText();
        bodies.ok(openVisit(doctor.token(clinic), f.visitId(), 200));
        bodies.ok(record(doctor.token(clinic), id));
        long revision = opened.get("record").get("revision").asLong();
        JsonNode saved = saveRecordDraft(doctor.token(clinic), id, draftBody(revision, "ASSESSMENT", "complaints",
                sectionValues("complaints", "{\"notes\":\"" + CLINICAL + "\"}")), 200);
        bodies.ok(saved);
        revision = saved.get("revision").asLong();
        refused(bodies, HttpMethod.PUT, RECORDS + "/" + id + "/draft", doctor.token(clinic), draftBody(revision, "ASSESSMENT", "complaints",
                sectionValues("complaints", "{\"duration\":\"" + BADVALUE + "\"}")).toString(), 400);
        refused(bodies, HttpMethod.PUT, RECORDS + "/" + id + "/draft", doctor.token(clinic), draftBody(revision, "ASSESSMENT", "complaints",
                sectionValues("complaints", "{\"" + FIELD + "\":\"" + BADVALUE + "\"}")).toString(), 400);
        refused(bodies, HttpMethod.PUT, RECORDS + "/" + id + "/draft", doctor.token(clinic), draftBody(revision, "ASSESSMENT", FIELD,
                sectionValues(FIELD, "{\"x\":\"" + BADVALUE + "\"}")).toString(), 400);
        refused(bodies, HttpMethod.PUT, RECORDS + "/" + id + "/draft", doctor.token(clinic), draftBody(revision + 5, "ASSESSMENT", "complaints",
                sectionValues("complaints", "{\"notes\":\"" + CLINICAL + "\"}")).toString(), 409);
        refused(bodies, HttpMethod.PUT, RECORDS + "/" + id + "/draft", doctor.token(clinic),
                "{\"revision\":" + revision + ",\"uiState\":{\"part\":\"ASSESSMENT\",\"sectionKey\":null},\"sections\":[],\"" + FIELD + "\":\"" + BADVALUE + "\"}", 400);
        JsonNode advised = post(RECORDS + "/" + id + "/admission-advice", doctor.token(clinic),
                "{\"revision\":" + revision + ",\"referralLine\":\"" + REFERRAL + "\"}", 200);
        bodies.ok(advised);
        refused(bodies, HttpMethod.POST, RECORDS + "/" + id + "/admission-advice", doctor.token(clinic),
                "{\"revision\":" + revision + ",\"referralLine\":\"" + REFERRAL + "x".repeat(300) + "\"}", 400);
        refused(bodies, HttpMethod.GET, RECORDS + "/" + id, f.otherDoctor().token(clinic), null, 403);
        refused(bodies, HttpMethod.GET, RECORDS + "/" + id, nurse.token(clinic), null, 403);
        refused(bodies, HttpMethod.GET, RECORDS + "/" + UUID.randomUUID(), doctor.token(clinic), null, 404);
        refused(bodies, HttpMethod.POST, OPEN, f.otherDoctor().token(clinic), openBody("OPD_VISIT", f.visitId()), 403);
        refused(bodies, HttpMethod.POST, OPEN, doctor.token(clinic), openBody("OPD_VISIT", UUID.randomUUID()), 404);
        refused(bodies, HttpMethod.POST, OPEN, doctor.token(clinic), "{\"encounterType\":\"" + FIELD + "\",\"encounterId\":\"" + f.visitId() + "\"}", 400);
        bodies.ok(put(RECORDS + "/" + id + "/department", doctor.token(clinic),
                "{\"departmentKey\":\"dental\",\"revision\":" + advised.get("revision").asLong() + "}", 200));
        refused(bodies, HttpMethod.PUT, RECORDS + "/" + id + "/department", doctor.token(clinic),
                "{\"departmentKey\":\"" + FIELD + "\",\"revision\":0}", 404);
        // the visit type
        refused(bodies, HttpMethod.PATCH, VISITS + "/" + f.visitId() + "/visit-type", doctor.token(clinic), "{\"visitType\":\"EMERGENCY\"}", 400);
        refused(bodies, HttpMethod.PATCH, VISITS + "/" + f.visitId() + "/visit-type", nurse.token(clinic), "{\"visitType\":\"REVISIT\"}", 403);
        bodies.ok(ok(HttpMethod.PATCH, VISITS + "/" + f.visitId() + "/visit-type", doctor.token(clinic), "{\"visitType\":\"REVISIT\"}", 200));
        return bodies;
    }

    private static String text(List<ILoggingEvent> events, String... leaveOutLoggerPrefixes) {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : events) {
            boolean skip = false;
            for (String prefix : leaveOutLoggerPrefixes) {
                skip |= event.getLoggerName().startsWith(prefix);
            }
            if (skip) {
                continue;
            }
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(event.getThrowableProxy().getMessage()).append('\n');
            }
            text.append(event.getMDCPropertyMap()).append('\n');
        }
        return text.toString();
    }

    private void assertNoSecrets(String logs, Bodies bodies, Fixture f) throws Exception {
        assertThat(logs).as("the capture saw the EMR requests").contains("/api/v1/opd/emr");
        for (String secret : new String[] {"Zxqvname", NAME, DEGREE, REGISTRATION, "ZXQREG99", SPECIALTY, TYPED, FIELD, f.complaint(),
                "Zxqpatient", PATIENT, ALLERGY, CLINICAL, REFERRAL, BADVALUE}) {
            assertThat(logs).as("log holds '" + secret + "'").doesNotContain(secret);
        }
        for (String leak : new String[] {TYPED, FIELD, "Zxqvname", REGISTRATION, "Zxqpatient", ALLERGY, CLINICAL, REFERRAL, BADVALUE}) {
            assertThat(bodies.errors().toString()).as("a refusal body echoes '" + leak + "'").doesNotContain(leak);
        }
        // the audit entries hold ids, section keys and setting values, never a patient or a clinical value
        StringBuilder audits = new StringBuilder();
        rows(f.clinic(), "select action || ' ' || coalesce(before_json::text, '') || ' ' || coalesce(after_json::text, '') "
                + "from platform.audit_event where action like 'EMR\\_%'").forEach(r -> audits.append(r.get(0)).append('\n'));
        assertThat(audits.toString()).contains("EMR_RECORD_OPENED").contains("EMR_RECORD_DRAFT_SAVED").contains("EMR_ADMISSION_ADVISED")
                .contains("EMR_DEPARTMENT_DRAFT_SAVED").contains("EMR_DEPARTMENT_PUBLISHED");
        for (String secret : new String[] {"Zxqpatient", PATIENT, ALLERGY, CLINICAL, REFERRAL, BADVALUE, "Zxqvname", REGISTRATION}) {
            assertThat(audits.toString()).as("audit holds '" + secret + "'").doesNotContain(secret);
        }
    }

    @Test
    void noLogLineAtTheConfiguredLevelsHoldsTheDoctorsDetailsThePatientTheClinicalTextOrAnUnknownFieldNameAndNoErrorBodyEchoesThem()
            throws Exception {
        Fixture f = fixture();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        Bodies bodies;
        try {
            bodies = emrCalls(f);
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
        assertNoSecrets(text(List.copyOf(appender.list)), bodies, f);
    }

    @Test
    void noLogLineAtDebugLevelHoldsTheDoctorsDetailsThePatientTheClinicalTextOrAnUnknownFieldNameApartFromTheTestHarness()
            throws Exception {
        Fixture f = fixture();
        Bodies bodies;
        String logs;
        try (LogCapture capture = new LogCapture()) {
            bodies = emrCalls(f);
            logs = text(capture.events(), "org.springframework.test.");
        }
        assertNoSecrets(logs, bodies, f);
    }

    @Test
    void theResponsesOfTheOtherRolesNeverCarryTheDoctorsRegistrationNumber() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addStaff(clinic, "DOCTOR", NAME, "Dental", DEGREE, REGISTRATION);
        StringBuilder all = new StringBuilder();
        for (String role : ALL_ROLES) {
            all.append(get(LIBRARY, clinic.as(role))).append(get(STARTERS, clinic.as(role))).append(get(DEPARTMENTS, clinic.as(role)))
                    .append(get(DEPARTMENTS + "/dental", clinic.as(role))).append(get(DEPARTMENTS + "/dental/layout", clinic.as(role)))
                    .append(get(MASTERS + "complaints", clinic.as(role)));
        }
        all.append(get(DEPARTMENTS + "/dental", doctor.token(clinic))).append(get(QUICK + "advice", doctor.token(clinic)));
        assertThat(all.toString()).doesNotContain(REGISTRATION).doesNotContain("Zxqvname").doesNotContain(DEGREE);
    }
}
