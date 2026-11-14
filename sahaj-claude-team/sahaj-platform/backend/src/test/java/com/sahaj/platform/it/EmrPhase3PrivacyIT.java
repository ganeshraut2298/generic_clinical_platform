package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.platform.support.LogCapture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The rule "never log patient names, mobile numbers, ABHA numbers or clinical notes" for everything SAH-48 to SAH-59 added, on
 * the real application: the component values (complaints, prescription, body map), the allergy check, copy, print choices and
 * the case paper, the history and its search, the forms, the treatment course, its payments and receipts, with successes and
 * every kind of refusal. No log line (as configured, and at DEBUG apart from Spring's own MockMvc harness lines), no refusal
 * body and no audit entry holds the patient's name or allergy, a typed complaint, medicine, pin note, form answer, course
 * name, note or item, a search text, or the name of an unknown field. Made-up marker words make a leak easy to see. Test
 * plan: docs/test-plans/SAH-48-59.md.
 */
@OnTestDatabase
class EmrPhase3PrivacyIT extends EmrPhase3Support {

    private static final String PATIENT = "Zxqpatient Surnamefield";
    private static final String ALLERGY = "Zxqallergytext";
    private static final String COMPLAINT = "Zxqcomplaint";
    private static final String RXNAME = "Zxqrxname";
    private static final String PINNOTE = "Zxqpinnote";
    private static final String FORMTEXT = "Zxqformtext";
    private static final String COURSE = "Zxqcoursename";
    private static final String COURSENOTE = "Zxqcoursenote";
    private static final String SITTINGITEM = "Zxqsittingitem";
    private static final String SEARCH = "zxqsearch";
    private static final String UNKNOWN = "zxqunknownfield";
    private static final String BAD = "Zxqbadvalue";
    private static final String[] SECRETS = {"Zxqpatient", PATIENT, ALLERGY, COMPLAINT, RXNAME, PINNOTE, FORMTEXT, COURSE, COURSENOTE, SITTINGITEM,
            SEARCH, UNKNOWN, BAD};

    private record Fixture(Clinic clinic, Staff doctor, Staff nurse, UUID patient) {
    }

    private record Bodies(StringBuilder all, StringBuilder errors) {

        void ok(JsonNode body) {
            all.append(body);
        }

        void error(JsonNode problem) {
            all.append(problem);
            errors.append(problem);
        }
    }

    private Fixture fixture() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Staff nurse = addNonDoctor(clinic, "NURSE");
        UUID patient = patientWith(clinic, PATIENT, ALLERGY, null);
        return new Fixture(clinic, doctor, nurse, patient);
    }

    private void refused(Bodies bodies, HttpMethod method, String path, RequestPostProcessor who, String body, int status) throws Exception {
        bodies.error(problem(call(method, path, who, body), status));
    }

    private void refusedKeyed(Bodies bodies, String path, RequestPostProcessor who, String body, String key, int status) throws Exception {
        bodies.error(problem(callKeyed(HttpMethod.POST, path, who, body, key), status));
    }

    /** Every kind of phase 3 call, successes and refusals. */
    private Bodies calls(Fixture f) throws Exception {
        Clinic clinic = f.clinic();
        Staff doctor = f.doctor();
        Bodies bodies = new Bodies(new StringBuilder(), new StringBuilder());
        showProcedures(clinic);
        String service = serviceId(clinic, "Dressing", 150);
        Rec first = startRecord(clinic, doctor, f.patient());
        ObjectNode accident = pin(1, "ROAD_ACCIDENT", false);
        accident.put("note", PINNOTE);
        // component values: lists, prescription, allergy, services, body map (derm), with refusals
        Rec saved = save(first, complaintsSection(typedItem(COMPLAINT)), allergySection("SULFA", "typed:" + ALLERGY),
                rxSection(rxRow(medicineId(SULFA_MEDICINE), SULFA_MEDICINE, "1-0-1", 5, "AFTER_FOOD"), typedRx(RXNAME)),
                oneField("procedures", "procedure", listValue("SERVICES", keyedItem(service, "Dressing"))));
        bodies.ok(saved.record());
        refused(bodies, HttpMethod.PUT, RECORDS + "/" + saved.id() + "/draft", saved.token(),
                draftBody(saved.revision(), "ASSESSMENT", null, complaintsSection(typedItem(COMPLAINT), typedItem(COMPLAINT.toLowerCase()))).toString(), 400);
        refused(bodies, HttpMethod.PUT, RECORDS + "/" + saved.id() + "/draft", saved.token(),
                draftBody(saved.revision(), "ASSESSMENT", null, sectionValues("complaints", "{\"" + UNKNOWN + "\":\"" + BAD + "\"}")).toString(), 400);
        refused(bodies, HttpMethod.PUT, RECORDS + "/" + saved.id() + "/draft", saved.token(),
                draftBody(saved.revision(), "ASSESSMENT", null, sectionValues("rx", "{\"dose_frequency\":\"" + BAD + "\"}")).toString(), 400);
        ObjectNode whatsapp = typedItem(COMPLAINT + " advice");
        whatsapp.put("attr2", "WHATSAPP");
        refused(bodies, HttpMethod.PUT, RECORDS + "/" + saved.id() + "/draft", saved.token(),
                draftBody(saved.revision(), "ASSESSMENT", null, oneField("advice", "advice", listValue("ADVICE", whatsapp))).toString(), 400);
        refused(bodies, HttpMethod.PUT, RECORDS + "/" + saved.id() + "/draft", f.nurse().token(clinic),
                draftBody(saved.revision(), "ASSESSMENT", null, complaintsSection(typedItem(COMPLAINT))).toString(), 403);
        Rec derm = switchDepartment(startRecord(clinic, doctor, f.patient()), "derm");
        Rec pinned = save(derm, oneField("lesion_map", "body_map", bodyMap(false, accident)));
        bodies.ok(pinned.record());
        // changing the cause of a pin is allowed (SAH-57-2: the flag follows what is sent, otherwise as sent); not a refusal
        bodies.ok(saveRecordDraft(pinned.token(), pinned.id(),
                draftBody(pinned.revision(), "ASSESSMENT", null, oneField("lesion_map", "body_map", bodyMap(false, pin(1, "FALL", false)))), 200));
        // medicines, favourites, allergy classes, print choices, the case paper
        bodies.ok(get(EMR_MEDICINES + "?q=" + SEARCH, doctor.token(clinic)));
        bodies.ok(get(FAVOURITES, doctor.token(clinic)));
        bodies.ok(get(ALLERGY_CLASSES, doctor.token(clinic)));
        refused(bodies, HttpMethod.GET, EMR_MEDICINES + "?q=" + SEARCH.repeat(20), doctor.token(clinic), null, 400);
        refused(bodies, HttpMethod.GET, EMR_MEDICINES, f.nurse().token(clinic), null, 403);
        bodies.ok(put(RECORDS + "/" + saved.id() + "/print-preferences", saved.token(), "{\"language\":\"MR\",\"paperSize\":\"A5\"}", 200));
        refused(bodies, HttpMethod.PUT, RECORDS + "/" + saved.id() + "/print-preferences", saved.token(), "{\"language\":\"" + BAD + "\",\"paperSize\":\"A4\"}", 400);
        bodies.ok(get(RECORDS + "/" + saved.id() + "/case-paper", saved.token()));
        refused(bodies, HttpMethod.GET, RECORDS + "/" + saved.id() + "/case-paper", f.nurse().token(clinic), null, 403);
        // history, search, details, key facts, copy
        signRecord(clinic, saved.id(), 2);
        Rec today = startRecord(clinic, doctor, f.patient());
        bodies.ok(get(RECORDS + "/" + today.id() + "/past-visits", today.token()));
        bodies.ok(get(RECORDS + "/" + today.id() + "/past-visits?q=" + SEARCH, today.token()));
        bodies.ok(get(RECORDS + "/" + today.id() + "/past-visits?q=" + COMPLAINT.toLowerCase(), today.token()));
        bodies.ok(get(RECORDS + "/" + today.id() + "/past-visits/OPD/" + saved.id(), today.token()));
        bodies.ok(get(RECORDS + "/" + today.id() + "/past-records/" + saved.id(), today.token()));
        bodies.ok(get(RECORDS + "/" + today.id() + "/key-facts", today.token()));
        refused(bodies, HttpMethod.GET, RECORDS + "/" + today.id() + "/past-visits?q=" + SEARCH.repeat(20), today.token(), null, 400);
        refused(bodies, HttpMethod.GET, RECORDS + "/" + today.id() + "/past-visits/OPD/" + UUID.randomUUID(), today.token(), null, 404);
        refused(bodies, HttpMethod.GET, RECORDS + "/" + today.id() + "/past-visits", f.nurse().token(clinic), null, 403);
        bodies.ok(post(RECORDS + "/" + today.id() + "/copy-items", today.token(), "{\"source\":\"LAST_SIGNED_PRESCRIPTION\",\"what\":\"MEDICINES\"}", 200));
        refused(bodies, HttpMethod.POST, RECORDS + "/" + today.id() + "/copy-items", today.token(), "{\"source\":\"LAST_SIGNED_PRESCRIPTION\",\"what\":\"MEDICINES\",\"" + UNKNOWN + "\":1}", 400);
        // forms
        JsonNode form = createForm(clinic, "untitled", "Privacy form");
        bodies.ok(form);
        ObjectNode draft = formDraftBody(form);
        ((ObjectNode) draft.get("settings")).put("approvalRequired", false);
        draft.putArray("fields").add(formField("note", "LONG_TEXT", "Note"));
        JsonNode saveForm = saveFormDraft(clinic, form, draft, 200);
        JsonNode live = formAction(clinic, saveForm, "publish", 200);
        bodies.ok(live);
        refused(bodies, HttpMethod.PUT, FORMS + "/" + live.get("formKey").asText() + "/draft", clinic.owner(), draft.put(UNKNOWN, BAD).toString(), 400);
        draft.remove(UNKNOWN); // a body with an unknown property is a 400 before the role is looked at; the 403 is asked with a valid body
        refused(bodies, HttpMethod.PUT, FORMS + "/" + live.get("formKey").asText() + "/draft", doctor.token(clinic), draft.toString(), 403);
        Rec withForm = startRecord(clinic, doctor, f.patient());
        Rec filled = save(withForm, sectionValues(live.get("formKey").asText(), "{\"note\":\"" + FORMTEXT + "\"}"));
        bodies.ok(filled.record());
        refused(bodies, HttpMethod.PUT, RECORDS + "/" + filled.id() + "/draft", filled.token(),
                draftBody(filled.revision(), "ASSESSMENT", null, sectionValues(live.get("formKey").asText(), "{\"note\":5,\"" + UNKNOWN + "\":\"" + BAD + "\"}")).toString(), 400);
        // the course and its money
        String body = "{\"fromRecordId\":\"" + filled.id() + "\",\"kind\":\"DENTAL\",\"name\":\"" + COURSE + "\",\"note\":\"" + COURSENOTE + "\",\"sittings\":["
                + "{\"addOptionKey\":\"opd_sitting\",\"chargePaise\":100000,\"items\":[\"" + SITTINGITEM + "\"]}]}";
        JsonNode course = okKeyed(HttpMethod.POST, COURSES, filled.token(), body, key(), 201);
        bodies.ok(course);
        refusedKeyed(bodies, COURSES, filled.token(), body.replace("\"opd_sitting\"", "\"" + UNKNOWN + "\""), key(), 400);
        refusedKeyed(bodies, COURSES, f.nurse().token(clinic), body, key(), 403);
        bodies.ok(get(COURSES + "?patientId=" + f.patient(), doctor.token(clinic)));
        bodies.ok(okKeyed(HttpMethod.POST, COURSES + "/" + course.get("id").asText() + "/sittings", filled.token(),
                "{\"revision\":0,\"addOptionKey\":\"opd_sitting\"}", key(), 201));
        bodies.ok(put(COURSES + "/" + course.get("id").asText() + "/sittings/" + course.get("sittings").get(0).get("id").asText(), filled.token(),
                "{\"revision\":1,\"encounterKind\":\"OPD\",\"plannedDate\":null,\"items\":[\"" + SITTINGITEM + "\"],\"chargePaise\":100000,\"note\":\"" + COURSENOTE + "\"}", 200));
        refused(bodies, HttpMethod.PUT, COURSES + "/" + course.get("id").asText() + "/sittings/" + course.get("sittings").get(0).get("id").asText(), filled.token(),
                "{\"revision\":9,\"encounterKind\":\"OPD\",\"plannedDate\":null,\"items\":[\"" + SITTINGITEM + "\"],\"chargePaise\":100000,\"note\":\"" + COURSENOTE + "\"}", 409);
        JsonNode paid = collect(clinic, course, "ADVANCE", 100000, "CASH");
        bodies.ok(paid);
        bodies.ok(account(clinic, course));
        bodies.ok(get(BILLING + "/courses/" + course.get("id").asText() + "/receipts/1", clinic.owner()));
        refusedKeyed(bodies, paymentsPath(course), clinic.owner(), paymentBody("ADVANCE", 150, "CASH", false, null).replace("}", ",\"" + UNKNOWN + "\":\"" + BAD + "\"}"), key(), 400);
        refusedKeyed(bodies, paymentsPath(course), f.nurse().token(clinic), paymentBody("ADVANCE", 100000, "CASH", false, null), key(), 403);
        refused(bodies, HttpMethod.GET, BILLING + "/courses/" + course.get("id").asText() + "/receipts/99", clinic.owner(), null, 404);
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
        for (String secret : SECRETS) {
            assertThat(logs).as("log holds '" + secret + "'").doesNotContain(secret);
        }
        for (String leak : new String[] {SEARCH, UNKNOWN, BAD, "Zxqpatient", ALLERGY, COMPLAINT, RXNAME, PINNOTE, FORMTEXT, COURSE, COURSENOTE, SITTINGITEM}) {
            assertThat(bodies.errors().toString()).as("a refusal body echoes '" + leak + "'").doesNotContain(leak);
        }
        StringBuilder audits = new StringBuilder();
        rows(f.clinic(), "select action || ' ' || coalesce(before_json::text, '') || ' ' || coalesce(after_json::text, '') from platform.audit_event "
                + "where action like 'EMR\\_%' or action like 'COURSE\\_%' or action like 'TREATMENT\\_%' or action like 'BILL\\_%'").forEach(r -> audits.append(r.get(0)).append('\n'));
        assertThat(audits.toString()).contains("EMR_RECORD_DRAFT_SAVED").contains("EMR_MLC_FLAG_CHANGED")
                .contains("EMR_ITEMS_COPIED").contains("EMR_FORM_CREATED").contains("TREATMENT_COURSE_CREATED").contains("COURSE_PAYMENT_RECORDED")
                .contains("BILL_ITEM_ADDED_FROM_EMR");
        for (String secret : SECRETS) {
            assertThat(audits.toString()).as("audit holds '" + secret + "'").doesNotContain(secret);
        }
    }

    @Test
    void noLogLineAtTheConfiguredLevelsHoldsAPatientNameAClinicalTextASearchTextOrAnUnknownFieldNameAndNoRefusalBodyEchoesThem() throws Exception {
        Fixture f = fixture();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        Bodies bodies;
        try {
            bodies = calls(f);
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
        assertNoSecrets(text(List.copyOf(appender.list)), bodies, f);
    }

    @Test
    void noLogLineAtDebugLevelHoldsThemApartFromTheTestHarness() throws Exception {
        Fixture f = fixture();
        Bodies bodies;
        String logs;
        try (LogCapture capture = new LogCapture()) {
            bodies = calls(f);
            logs = text(capture.events(), "org.springframework.test.");
        }
        assertNoSecrets(logs, bodies, f);
    }

    @Test
    void thePatientNameAndTheClinicalTextAreOnTheScreenAnswersAndInTheRecordOnlyNeverInAnotherRoleOrClinic() throws Exception {
        Fixture f = fixture();
        Rec rec = startRecord(f.clinic(), f.doctor(), f.patient());
        Rec saved = save(rec, complaintsSection(typedItem(COMPLAINT)));
        assertThat(get(RECORDS + "/" + saved.id() + "/case-paper", saved.token()).toString()).contains(PATIENT);
        // another role and another clinic never get the record or the paper
        problemOf(HttpMethod.GET, RECORDS + "/" + saved.id(), f.nurse().token(f.clinic()), null, 403, "forbidden");
        problemOf(HttpMethod.GET, RECORDS + "/" + saved.id() + "/case-paper", f.nurse().token(f.clinic()), null, 403, "forbidden");
        Clinic other = newClinic();
        Staff outsider = addDoctor(other, "General physician");
        String leak = problemOf(HttpMethod.GET, RECORDS + "/" + saved.id() + "/case-paper", outsider.token(other), null, 404, "record-not-found").toString()
                + problemOf(HttpMethod.GET, RECORDS + "/" + saved.id() + "/past-visits", outsider.token(other), null, 404, "record-not-found")
                + problemOf(HttpMethod.GET, RECORDS + "/" + saved.id() + "/key-facts", outsider.token(other), null, 404, "record-not-found");
        assertThat(leak).doesNotContain("Zxqpatient").doesNotContain(COMPLAINT).doesNotContain(ALLERGY);
        // the audit of the save holds the section keys only
        List<List<String>> audits = auditRows(f.clinic(), "EMR_RECORD_DRAFT_SAVED");
        assertThat(audits.get(0).get(1) + audits.get(0).get(2)).doesNotContain(COMPLAINT).doesNotContain("Zxqpatient");
    }
}
