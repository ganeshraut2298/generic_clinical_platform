package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The rule "never log patient names, mobile numbers, ABHA numbers or clinical notes" for everything SAH-49 to SAH-71 added, on the
 * real application: the specialty values (vitals, tooth chart, dental plan text, chaperone name, surgery procedure and slot,
 * oncology labs, doses and drugs), the chemotherapy order and its second check, the checker's list and summary (the patient's
 * name is on that screen and nowhere else), the visit templates, the course links and the OT booking, with successes and every
 * kind of refusal. No log line, no refusal body and no audit entry holds the patient's name or allergy, a typed procedure, a
 * template name, a chaperone name, a slot text, a dose or a drug name. Made-up marker words make a leak easy to see. Test
 * plan: docs/test-plans/SAH-49-71.md.
 */
@OnTestDatabase
class EmrPhase4PrivacyIT extends EmrSpecialtySupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String PATIENT = "Zxqpatient Surnamefield";
    private static final String ALLERGY = "Zxqallergytext";
    private static final String PROCEDURE = "Zxqprocedure";
    private static final String CHAPERONE = "Zxqchaperone";
    private static final String TEMPLATE = "Zxqtemplate";
    private static final String SLOT = "Zxqslot";
    private static final String FINDING = "Zxqfinding";
    private static final String[] SECRETS = {"Zxqpatient", PATIENT, ALLERGY, PROCEDURE, CHAPERONE, TEMPLATE, SLOT, FINDING};

    private static void assertClean(String what, String text) {
        for (String secret : SECRETS) {
            assertThat(text).as(what + " holds " + secret).doesNotContain(secret);
        }
    }

    @Test
    void noLogLineRefusalBodyOrAuditEntryOfTheSpecialtyScreensTheOrderTheSecondCheckTheTemplatesOrTheBookingHoldsPatientOrClinicalText() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Staff nurse = addNonDoctor(clinic, "NURSE");
        UUID patient = patientWith(clinic, PATIENT, ALLERGY, null);
        StringBuilder errors = new StringBuilder();
        String logged;
        try (LogCapture logs = new LogCapture()) {
            // oncology: assessment, order, name the checker, the checker reads and confirms, send
            Rec onco = oncoAssessed(specialtyRec(clinic, doctor, patient, "onco"));
            JsonNode order = saveOrder(onco, passingOrder(0));
            errors.append(problemOf(HttpMethod.PUT, orderUrl(onco), onco.token(), orderBody(order.get("revision").asLong(), "NO_SUCH_" + PROCEDURE, 100, ""), 400, "validation"));
            errors.append(problemOf(HttpMethod.PUT, orderUrl(onco), onco.token(), passingOrder(99), 409, "chemo-order-changed"));
            Sheet named = nameChecker(new Sheet(onco, order), nurse);
            errors.append(problemOf(HttpMethod.POST, orderUrl(onco) + "/send", onco.token(), sendBody(named), 409, "second-check-not-confirmed"));
            JsonNode list = get(CHEMO_CHECKS, nurse.token(clinic));
            assertThat(list.toString()).as("the checker's screen shows the patient").contains("Zxqpatient");
            JsonNode summary = summary(clinic, nurse, orderId(named));
            assertThat(summary.toString()).contains("Zxqpatient");
            errors.append(problemOf(HttpMethod.POST, CHEMO_CHECKS + "/" + orderId(named) + "/confirm", nurse.token(clinic), confirmBody("0".repeat(64)), 409, "chemo-order-changed"));
            errors.append(problemOf(HttpMethod.GET, CHEMO_CHECKS + "/" + orderId(named), onco.token(), null, 403, "checker-is-prescriber"));
            post(CHEMO_CHECKS + "/" + orderId(named) + "/confirm", nurse.token(clinic), confirmBody(summary.get("fingerprint").asText()), 200);
            post(orderUrl(onco) + "/send", onco.token(), sendBody(reread(named)), 200);
            // dental: typed procedure, chart, consent
            Rec dental = specialtyRec(clinic, doctor, newPatient(clinic), "dental");
            Rec plan = save(dental, spSection("tx_plan", "DENTAL_PLAN", "{\"rows\":[{\"id\":\"" + uuid() + "\",\"tooth\":16,\"procedureKey\":null,\"procedureText\":\"" + PROCEDURE
                    + "\",\"phase\":1,\"status\":\"PLANNED\",\"sittingsPlanned\":1,\"currentSitting\":1,\"typedEstimatePaise\":100000}],\"signEstimate\":true}"));
            errors.append(refuseSave(plan, 400, "validation", spSection("tx_plan", "DENTAL_PLAN", "{\"rows\":[{\"id\":\"" + uuid() + "\",\"tooth\":99,\"procedureKey\":null,\"procedureText\":\""
                    + PROCEDURE + "\",\"phase\":1,\"status\":\"PLANNED\",\"sittingsPlanned\":1,\"currentSitting\":1}]}")));
            okKeyed(HttpMethod.POST, COURSES, plan.token(), "{\"fromRecordId\":\"" + plan.id() + "\",\"kind\":\"DENTAL\",\"fromPlan\":true}", key(), 201);
            // obstetrics: the chaperone
            Rec og = saveScreen(specialtyRec(clinic, doctor, newPatient(clinic), "og"), "{\"mode\":\"GYNAE\"}");
            save(og, spSection("gyn_exam", "GYNAE_EXAM", "{\"perAbdomen\":\"" + FINDING + "\",\"chaperoneName\":\"" + CHAPERONE + "\",\"chaperoneDeclined\":false}"));
            // general medicine: an abnormal finding, a bad value, a template
            Rec gm = specialtyRec(clinic, doctor, newPatient(clinic), "gm");
            Rec exam = save(gm, spSection("exam", "EXAM_GENERAL", "{\"signs\":{},\"systems\":{\"general\":{\"state\":\"ABNORMAL\",\"text\":\"" + FINDING + "\"}}}"));
            errors.append(refuseSave(exam, 400, "validation", spSection("vitals", "VITALS_LOG", "{\"entries\":[{\"id\":\"" + uuid() + "\",\"bpSystolic\":999}]}")));
            post(VISIT_TEMPLATES, doctor.token(clinic), "{\"name\":\"" + TEMPLATE + "\",\"subLine\":null,\"items\":{\"complaints\":[{\"itemKey\":null,\"text\":\"" + FINDING + "\",\"newToList\":true}]}}", 201);
            errors.append(problemOf(HttpMethod.POST, VISIT_TEMPLATES, doctor.token(clinic), "{\"name\":\"" + TEMPLATE.toUpperCase() + "\",\"subLine\":null,\"items\":{}}", 400, "validation"));
            // surgery: the booking
            Rec surgery = specialtyRec(clinic, doctor, newPatient(clinic), "surgery");
            String otDate = LocalDate.now(IST).plusDays(4).toString();
            Rec ready = save(surgery, spSection("sx_plan", "SURGERY_PLAN", "{\"procedureKey\":null,\"procedureText\":\"" + PROCEDURE + "\",\"anaesthesia\":\"GA\",\"admission\":\"DAY_CARE\",\"otDate\":\""
                    + otDate + "\",\"slotText\":\"" + SLOT + "\",\"payer\":\"SELF\",\"typedPackagePaise\":6000000}"), spSection("sx_consent", "CONSENT_CHECKLIST",
                    "{\"items\":[{\"key\":\"PROCEDURE_RISKS\",\"confirmed\":true},{\"key\":\"CONVERSION_TO_OPEN\",\"confirmed\":true},{\"key\":\"ANAESTHESIA\",\"confirmed\":true},"
                            + "{\"key\":\"BLOOD_TRANSFUSION\",\"confirmed\":true},{\"key\":\"PHOTOS_VIDEO\",\"confirmed\":true}]}"),
                    oneField("diagnosis", "provisional_diagnosis", listValue("DIAGNOSIS", typedItem(FINDING))));
            okKeyed(HttpMethod.POST, RECORDS + "/" + ready.id() + "/surgery-booking", ready.token(), "{\"revision\":" + ready.revision() + "}", key(), 200);
            errors.append(problemKeyed(HttpMethod.POST, RECORDS + "/" + ready.id() + "/surgery-booking", ready.token(), "{\"revision\":" + ready.revision() + "}", key(), 409, "emr-record-changed"));
            logged = logs.allText();
        }
        assertClean("a log line", logged);
        assertClean("a refusal body", errors.toString());
        assertClean("an audit entry", allAuditText(clinic));
        assertThat(allAuditText(clinic)).as("the flow wrote its audits").contains("CHEMO_ORDER_SENT").contains("EMR_SURGERY_BOOKED").contains("EMR_VISIT_TEMPLATE_CREATED");
        assertThat(logged).as("no dose or drug name either").doesNotContain("paclitaxel").doesNotContain("Paclitaxel");
    }
}
