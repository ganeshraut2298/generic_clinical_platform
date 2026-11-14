package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The Dental "Complete and send to billing" as the Dental screen does it, on the real database (SAH-69-3 with SAH-51 sign;
 * ADR-025 D1 and D7; docs/api/opd.yaml 0.6.2 sign, finish-check and {@code choices.dental.nextInterval}: D3, D7, D14, M1 or
 * null, "D7 when absent, null when every row is done"; DEC-048 H7, H13, H28). One doctor, one patient, one visit on the queue
 * at the doctor stage; the record takes the Dental layout, the five required sections are filled (complaints, diagnosis,
 * prescription, prescription format, tooth chart), the treatment plan is saved with rows Planned and then re-saved with rows
 * marked Done (the server stamps the day), the consent and lab work sections are saved, the finish check is asked and the sign
 * is sent with exactly {@code {"revision":n,"choices":{"dental":{"nextInterval":null}}}} and an Idempotency-Key. Nothing is
 * relaxed: the clinic keeps the starter Dental layout. Throwaway data only.
 */
@OnTestDatabase
class EmrDentalSendToBillingIT extends EmrSignSupport {

    private static final String NULL_INTERVAL = "{\"dental\":{\"nextInterval\":null}}";
    private static final long ROOT_CANAL_PAISE = 100000L;

    /** A Dental visit: the record, the plan row ids and the clinic. */
    private record Dental(Rec rec, String pricedId, String noAmountId, String openId) {
    }

    // ---------------- the screen's values ----------------

    private static String chart() {
        return "{\"numbering\":\"FDI\",\"dentition\":\"ADULT\",\"showNumbers\":true,\"teeth\":[{\"tooth\":16,\"condition\":\"CARIES\","
                + "\"surfaces\":[\"O\",\"D\"],\"mobility\":0},{\"tooth\":21,\"condition\":\"CARIES\",\"surfaces\":[\"M\"],\"mobility\":0}],"
                + "\"shade\":\"A2\",\"gum\":\"PINK\",\"allHealthyConfirmed\":false}";
    }

    private static String planRow(String id, int tooth, String key, String text, int sittings, Long typedPaise, String status) {
        return "{\"id\":\"" + id + "\",\"tooth\":" + tooth + ",\"procedureKey\":" + (key == null ? "null" : "\"" + key + "\"")
                + ",\"procedureText\":\"" + text + "\",\"phase\":1,\"status\":\"" + status + "\",\"sittingsPlanned\":" + sittings
                + ",\"currentSitting\":1" + (typedPaise == null ? "" : ",\"typedEstimatePaise\":" + typedPaise) + "}";
    }

    private static String plan(String rows, boolean signEstimate) {
        return "{\"rows\":[" + rows + "]" + (signEstimate ? ",\"signEstimate\":true" : "") + "}";
    }

    private static String fromPlanBody(Rec rec) {
        return "{\"fromRecordId\":\"" + rec.id() + "\",\"kind\":\"DENTAL\",\"fromPlan\":true}";
    }

    /**
     * Opens a Dental visit and fills it like the screen. Row A (Root canal, tooth 16, Rs 1,000) and row B (Scaling, tooth 21,
     * no amount) are marked Done today; row C (Composite filling, tooth 26, 2 sittings) stays Planned when {@code openRow}.
     * With {@code course} the course is built from the plan before the rows are marked Done ("Book next sitting" needs it).
     */
    private Dental dentalVisit(boolean openRow, boolean course) throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Dentist");
        Rec rec = startRecord(clinic, doctor, newPatient(clinic));
        if (!"dental".equals(rec.record().get("layout").get("starterKey").asText())) {
            rec = switchDepartment(rec, "dental");
        }
        assertThat(rec.record().get("layout").get("starterKey").asText()).as("the Dental layout").isEqualTo("dental");
        String a = uuid();
        String b = uuid();
        String c = uuid();
        rec = fillRequired(rec);
        rec = save(rec, spSection("dental_chart", "TOOTH_CHART", chart()));
        String openRowJson = openRow ? "," + planRow(c, 26, "COMPOSITE_FILLING", "Composite filling", 2, 60000L, "PLANNED") : "";
        rec = save(rec, spSection("tx_plan", "DENTAL_PLAN", plan(planRow(a, 16, "ROOT_CANAL", "Root canal", 1, ROOT_CANAL_PAISE, "PLANNED") + ","
                + planRow(b, 21, null, "Scaling", 1, null, "PLANNED") + openRowJson, true)));
        if (course) {
            okKeyed(HttpMethod.POST, COURSES, rec.token(), fromPlanBody(rec), key(), 201);
            rec = rec.with(current(rec));
        }
        rec = save(rec, spSection("tx_plan", "DENTAL_PLAN", plan(planRow(a, 16, "ROOT_CANAL", "Root canal", 1, ROOT_CANAL_PAISE, "DONE") + ","
                + planRow(b, 21, null, "Scaling", 1, null, "DONE") + openRowJson, false)));
        rec = save(rec, spSection("consent", "CONSENT_CHECKLIST", "{\"items\":[{\"key\":\"" + a + "\",\"formLabel\":\"Root canal consent\",\"confirmed\":true}]}"));
        rec = save(rec, spSection("dental_lab", "LAB_WORK", "{\"items\":[{\"id\":\"" + uuid() + "\",\"planRowId\":null,\"tooth\":16,\"item\":\"CROWN\","
                + "\"labName\":\"Test lab\",\"dueDate\":\"" + today().plusDays(10) + "\"}]}"));
        assertThat(valueOf(rec.record(), "tx_plan", "specialty").get("rows").get(0).get("doneOn").asText()).as("the server stamped the day").isEqualTo(today().toString());
        return new Dental(rec, a, b, openRow ? c : null);
    }

    private JsonNode readyCheck(Dental d, String choices) throws Exception {
        JsonNode check = check(d.rec(), choices);
        assertThat(check.get("ready").asBoolean()).as("ready; blockers: " + check.get("blockers")).isTrue();
        return check;
    }

    private JsonNode signAs(Dental d, String choices, int expected) throws Exception {
        return okKeyed(HttpMethod.POST, signPath(d.rec().id()), d.rec().token(), signBody(d.rec().revision(), choices), key(), expected);
    }

    // ---------------- what the sign leaves behind ----------------

    private List<List<String>> planLines(Clinic clinic) throws Exception {
        return rows(clinic, "select source_key, label, amount_paise::text from billing.bill_item where source_kind = 'EMR_PLAN' order by source_key");
    }

    private List<JsonNode> planLinesOnBill(Clinic clinic, String visitId) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode item : ok(HttpMethod.GET, BILLING + "/bills/" + visitId, clinic.owner(), null, 200).get("items")) {
            if ("EMR_PLAN".equals(item.get("source").asText())) {
                out.add(item);
            }
        }
        return out;
    }

    /** Everything the dental sign does that does not depend on the next sitting. */
    private void assertSentToBilling(Dental d, JsonNode check, JsonNode result) throws Exception {
        Rec rec = d.rec();
        Clinic clinic = rec.clinic();
        assertThat(result.get("replayed").asBoolean()).isFalse();
        assertThat(result.get("record").get("status").asText()).isEqualTo("SIGNED");
        assertThat(statusOf(clinic, rec.id())).isEqualTo("SIGNED");
        JsonNode outcome = result.get("outcome");
        // the card moved to the stage the check said (the next stage after consultation in the clinic's workflow)
        assertThat(outcome.get("stage").get("outcome").asText()).isEqualTo("MOVED");
        String to = outcome.get("stage").get("to").asText();
        assertThat(to).isNotEqualTo("WITH_DOCTOR");
        if (check != null) {
            assertThat(to).as("the stage the check announced").isEqualTo(check.get("effects").get("stage").get("to").asText());
        }
        JsonNode card = boardVisit(clinic, rec.visitId());
        assertThat(card.get("stage").asText()).isEqualTo(to);
        assertThat(card.get("emrStatus").asText()).isEqualTo("SIGNED");
        // one plan bill line for the done row with an amount, none for the row without
        assertThat(outcome.get("billLines").asInt()).as("only the row with an amount is billed").isEqualTo(1);
        assertThat(outcome.get("billTotalPaise").asLong()).isEqualTo(ROOT_CANAL_PAISE);
        List<List<String>> lines = planLines(clinic);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).containsExactly("plan:" + d.pricedId() + ":1", "Root canal · tooth 16", String.valueOf(ROOT_CANAL_PAISE));
        assertThat(count(clinic, "select count(*) from billing.bill_item where label like 'Scaling%'")).as("no line for the no-amount row").isZero();
        List<JsonNode> onBill = planLinesOnBill(clinic, rec.visitId());
        assertThat(onBill).as("the billing counter shows the plan line").hasSize(1);
        assertThat(onBill.get(0).get("amountPaise").asLong()).isEqualTo(ROOT_CANAL_PAISE);
        assertThat(auditCount(clinic, "BILL_ITEM_ADDED_FROM_EMR_PLAN")).isOne();
        // prints
        assertThat(texts(outcome.get("prints"), null)).containsExactly("PRESCRIPTION");
        // audit: one entry, no clinical text
        List<List<String>> audits = auditRows(clinic, "EMR_RECORD_SIGNED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get(0)).isEqualTo("EMR_RECORD");
        assertThat(audits.get(0).get(2)).doesNotContain("Root canal").doesNotContain("Scaling").doesNotContain("Composite").doesNotContain("Viral fever");
        assertThat(audits.get(0).get(3)).isEqualTo(rec.doctor().userId().toString());
        // another clinic sees none of it
        Clinic other = newClinic();
        assertThat(count(other, "select count(*) from billing.bill_item where source_kind = 'EMR_PLAN'")).isZero();
    }

    private void assertFollowUpIn(Dental d, JsonNode result, LocalDate due) throws Exception {
        Clinic clinic = d.rec().clinic();
        JsonNode followUp = result.get("outcome").get("followUp");
        assertThat(followUp.isNull()).as("a follow-up for the next sitting").isFalse();
        assertThat(followUp.get("dueDate").asText()).isEqualTo(due.toString());
        assertThat(followUpRows(clinic, d.rec().visitId())).as("never two follow-ups for one visit").isOne();
        if (followUp.get("appointmentDate").isNull()) {
            assertThat(followUp.get("noFreeSlot").asBoolean()).isTrue();
            assertThat(count(clinic, "select count(*) from opd.appointment where source = 'EMR'")).isZero();
        } else {
            assertThat(followUp.get("appointmentDate").asText()).isGreaterThanOrEqualTo(due.toString());
            assertThat(count(clinic, "select count(*) from opd.appointment where source = 'EMR'")).isOne();
        }
    }

    // ================= the main flow, explicit null =================

    @Test
    void explicitNullNextIntervalWithAnOpenRowSignsMovesTheCardAndAddsOnlyThePricedDoneRowAsABillLineAndBooksTheDefaultSevenDays() throws Exception {
        Dental d = dentalVisit(true, false);
        Rec rec = d.rec();
        JsonNode check = readyCheck(d, NULL_INTERVAL);
        JsonNode dental = check.get("effects").get("profile");
        assertThat(dental.get("profile").asText()).isEqualTo("dental");
        assertThat(dental.get("doneRows")).hasSize(2);
        for (JsonNode doneRow : dental.get("doneRows")) {
            if (d.noAmountId().equals(doneRow.get("rowId").asText())) {
                assertThat(doneRow.get("amountPaise").isNull()).as("Not in tariff").isTrue();
            } else {
                assertThat(doneRow.get("amountPaise").asLong()).isEqualTo(ROOT_CANAL_PAISE);
            }
        }
        assertThat(dental.get("allRowsDone").asBoolean()).isFalse();
        assertThat(dental.get("carriedRowCount").asInt()).isOne();
        assertThat(dental.get("nextSittingInterval").asText()).as("null with an open row means the default").isEqualTo("D7");
        assertThat(dental.get("nextSittingDate").asText()).isEqualTo(today().plusDays(7).toString());
        assertThat(planLines(rec.clinic())).as("a check bills nothing").isEmpty();

        // exactly the body of the screen
        String body = signBody(rec.revision(), NULL_INTERVAL);
        assertThat(body).isEqualTo("{\"revision\":" + rec.revision() + ",\"choices\":{\"dental\":{\"nextInterval\":null}}}");
        JsonNode result = okKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), body, key(), 200);

        assertSentToBilling(d, check, result);
        assertFollowUpIn(d, result, today().plusDays(7));
        assertThat(row(rec.clinic(), "select due_date::text from opd.follow_up where source_visit_id = ?::uuid", rec.visitId()).get(0)).isEqualTo(today().plusDays(7).toString());
    }

    @Test
    void anAbsentNextIntervalAndAnEmptyDentalBlockBehaveAsTheSevenDayDefault() throws Exception {
        for (String choices : new String[] {"{}", "{\"dental\":{}}"}) {
            Dental d = dentalVisit(true, false);
            JsonNode check = readyCheck(d, choices);
            assertThat(check.get("effects").get("profile").get("nextSittingInterval").asText()).as(choices).isEqualTo("D7");
            JsonNode result = signAs(d, choices, 200);
            assertSentToBilling(d, check, result);
            assertFollowUpIn(d, result, today().plusDays(7));
        }
    }

    @Test
    void everyChoiceOfTheNextSittingMakesTheFollowUpOfThatInterval() throws Exception {
        String[] intervals = {"D3", "D7", "D14", "M1"};
        LocalDate[] due = {today().plusDays(3), today().plusDays(7), today().plusDays(14), today().plusMonths(1)};
        for (int i = 0; i < intervals.length; i++) {
            Dental d = dentalVisit(true, false);
            String choices = "{\"dental\":{\"nextInterval\":\"" + intervals[i] + "\"}}";
            JsonNode check = readyCheck(d, choices);
            assertThat(check.get("effects").get("profile").get("nextSittingInterval").asText()).isEqualTo(intervals[i]);
            JsonNode result = signAs(d, choices, 200);
            assertSentToBilling(d, check, result);
            assertFollowUpIn(d, result, due[i]);
        }
    }

    // ================= every row done =================

    @Test
    void whenEveryRowIsDoneNullSignsSendsTheLinesAndBooksNoNextSittingAndNoAppointment() throws Exception {
        Dental d = dentalVisit(false, false);
        JsonNode check = readyCheck(d, NULL_INTERVAL);
        JsonNode dental = check.get("effects").get("profile");
        assertThat(dental.get("allRowsDone").asBoolean()).isTrue();
        assertThat(dental.get("carriedRowCount").asInt()).isZero();
        assertThat(dental.get("nextSittingInterval").isNull()).isTrue();
        assertThat(dental.get("nextSittingDate").isNull()).isTrue();
        assertThat(dental.get("nextSittingSlot").isNull()).isTrue();
        JsonNode result = signAs(d, NULL_INTERVAL, 200);
        assertSentToBilling(d, check, result);
        assertThat(result.get("outcome").get("followUp").isNull()).as("no next sitting, no follow-up from the dental choice").isTrue();
        assertThat(count(d.rec().clinic(), "select count(*) from opd.appointment where source = 'EMR'")).isZero();
        assertThat(result.get("outcome").path("course").hasNonNull("nextSittingId")).as("no next sitting").isFalse();
    }

    @Test
    void whenEveryRowIsDoneAnIntervalInTheBodyIsIgnoredAndNothingIsBookedForIt() throws Exception {
        Dental d = dentalVisit(false, false);
        JsonNode result = signAs(d, "{\"dental\":{\"nextInterval\":\"D7\"}}", 200);
        assertSentToBilling(d, null, result);
        assertThat(result.get("outcome").get("followUp").isNull()).isTrue();
        assertThat(count(d.rec().clinic(), "select count(*) from opd.appointment where source = 'EMR'")).isZero();
    }

    // ================= book the next sitting (a course from the plan) =================

    @Test
    void withACourseFromThePlanTheOpenRowIsCarriedIntoAPlannedSittingOnTheNextSittingDate() throws Exception {
        Dental d = dentalVisit(true, true);
        Clinic clinic = d.rec().clinic();
        long sittingsBefore = count(clinic, "select count(*) from opd.treatment_course_sitting");
        JsonNode check = readyCheck(d, "{\"dental\":{\"nextInterval\":\"D14\"}}");
        JsonNode result = signAs(d, "{\"dental\":{\"nextInterval\":\"D14\"}}", 200);
        assertSentToBilling(d, check, result);
        assertFollowUpIn(d, result, today().plusDays(14));
        JsonNode course = result.get("outcome").get("course");
        assertThat(course.isNull()).as("the next sitting is in the outcome").isFalse();
        assertThat(course.get("nextSittingId").isNull()).isFalse();
        assertThat(count(clinic, "select count(*) from opd.treatment_course_sitting")).isEqualTo(sittingsBefore + 1);
        List<String> sitting = row(clinic, "select status, planned_date::text, items::text from opd.treatment_course_sitting where id = ?::uuid",
                course.get("nextSittingId").asText());
        assertThat(sitting.get(0)).isEqualTo("PLANNED");
        assertThat(sitting.get(1)).isEqualTo(today().plusDays(14).toString());
        assertThat(sitting.get(2)).contains("Composite filling").contains("sitting 2 of 2");
    }

    @Test
    void theSameKeyReplaysTheFirstAnswerWithOneBillLineOneFollowUpOneAppointmentAndOneAuditWhenThePlanHasNoCourse() throws Exception {
        replayChecks(dentalVisit(true, false));
    }

    @Test
    void theSameKeyReplaysTheFirstAnswerWithOneBillLineOneSittingOneFollowUpOneAppointmentAndOneAuditWhenThePlanHasACourse() throws Exception {
        replayChecks(dentalVisit(true, true));
    }

    private void replayChecks(Dental d) throws Exception {
        Rec rec = d.rec();
        Clinic clinic = rec.clinic();
        String key = key();
        String body = signBody(rec.revision(), NULL_INTERVAL);
        JsonNode first = okKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), body, key, 200);
        long sittings = count(clinic, "select count(*) from opd.treatment_course_sitting");
        long appointments = count(clinic, "select count(*) from opd.appointment");
        JsonNode second = okKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), body, key, 200);
        assertThat(first.get("replayed").asBoolean()).isFalse();
        assertThat(second.get("replayed").asBoolean()).isTrue();
        assertThat(second.get("outcome")).isEqualTo(first.get("outcome"));
        assertThat(texts(second.get("outcome").get("prints"), null)).containsExactly("PRESCRIPTION");
        assertThat(planLines(clinic)).hasSize(1);
        assertThat(auditCount(clinic, "EMR_RECORD_SIGNED")).isOne();
        assertThat(auditCount(clinic, "BILL_ITEM_ADDED_FROM_EMR_PLAN")).isOne();
        assertThat(followUpRows(clinic, rec.visitId())).isOne();
        assertThat(count(clinic, "select count(*) from opd.treatment_course_sitting")).isEqualTo(sittings);
        assertThat(count(clinic, "select count(*) from opd.appointment")).isEqualTo(appointments);
        // the same key with another body is a conflict, a new key on the signed record is "already signed"
        signRefused(rec, rec.token(), signBody(rec.revision() + 3, NULL_INTERVAL), key, 409, "idempotency-conflict");
        JsonNode signed = signRefused(rec, rec.token(), body, key(), 409, "emr-record-signed");
        assertThat(signed.get("detail").asText()).startsWith("Already signed by Dr.");
        assertThat(planLines(clinic)).hasSize(1);
    }

    // ================= refusals the screen can hit =================

    @Test
    void aStaleRevisionIs409EmrRecordChangedAndNothingIsSentToBilling() throws Exception {
        Dental d = dentalVisit(true, false);
        Rec rec = d.rec();
        // another edit saved after the screen read the record (the hidden History of present illness is not used: it is off in Dental)
        Rec changed = save(rec, spSection("dental_lab", "LAB_WORK", "{\"items\":[{\"id\":\"" + uuid() + "\",\"planRowId\":null,\"tooth\":21,\"item\":\"BRIDGE\","
                + "\"labName\":\"Another lab\",\"dueDate\":\"" + today().plusDays(12) + "\"}]}"));
        assertThat(changed.revision()).isGreaterThan(rec.revision());
        signRefused(rec, rec.token(), signBody(rec.revision(), NULL_INTERVAL), key(), 409, "emr-record-changed");
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("DRAFT");
        assertThat(planLines(rec.clinic())).isEmpty();
        assertThat(boardVisit(rec.clinic(), rec.visitId()).get("stage").asText()).isEqualTo("WITH_DOCTOR");
        assertThat(auditCount(rec.clinic(), "EMR_RECORD_SIGNED")).isZero();
        // with the fresh revision the same choices sign
        JsonNode result = okKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), signBody(changed.revision(), NULL_INTERVAL), key(), 200);
        assertThat(result.get("outcome").get("billLines").asInt()).isOne();
    }

    @Test
    void aRecordThatIsNotReadyIs409EmrNotReadyWithTheBlockersAndNoLineIsAddedAndTheCardStaysWithTheDoctor() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Dentist");
        Rec rec = startRecord(clinic, doctor, newPatient(clinic));
        if (!"dental".equals(rec.record().get("layout").get("starterKey").asText())) {
            rec = switchDepartment(rec, "dental");
        }
        // a done row but the tooth chart and the other required sections are not filled
        rec = save(rec, spSection("tx_plan", "DENTAL_PLAN", plan(planRow(uuid(), 16, "ROOT_CANAL", "Root canal", 1, ROOT_CANAL_PAISE, "DONE"), false)));
        long audits = count(clinic, "select count(*) from platform.audit_event");
        JsonNode problem = signRefused(rec, rec.token(), signBody(rec.revision(), NULL_INTERVAL), key(), 409, "emr-not-ready");
        assertThat(problem.get("blockers").isArray()).isTrue();
        assertThat(texts(problem.get("blockers"), "key")).contains("STILL_NEEDED");
        assertThat(statusOf(clinic, rec.id())).isEqualTo("DRAFT");
        assertThat(planLines(clinic)).isEmpty();
        assertThat(boardVisit(clinic, rec.visitId()).get("stage").asText()).isEqualTo("WITH_DOCTOR");
        assertThat(count(clinic, "select count(*) from platform.audit_event")).isEqualTo(audits);
        assertProblemMentionsNoClinicalText(problem);
        assertThat(problem.toString()).doesNotContain("Root canal");
    }

    @Test
    void aNextIntervalNotOnTheListAndAPhysioChoiceOnADentalVisitAre400ValidationAndNothingIsSigned() throws Exception {
        Dental d = dentalVisit(true, false);
        Rec rec = d.rec();
        JsonNode problem = signRefused(rec, rec.token(), signBody(rec.revision(), "{\"dental\":{\"nextInterval\":\"D5\"}}"), key(), 400, "validation");
        assertThat(problem.toString()).contains("choices.dental.nextInterval");
        signRefused(rec, rec.token(), signBody(rec.revision(), "{\"physio\":{\"nextSlot\":null}}"), key(), 400, "validation");
        signRefused(rec, rec.token(), signBody(rec.revision(), "{\"dental\":{\"nextInterval\":\"M3\"}}"), key(), 400, "validation");
        problemKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), signBody(rec.revision(), NULL_INTERVAL), null, 400, "validation");
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("DRAFT");
        assertThat(planLines(rec.clinic())).isEmpty();
    }

    @Test
    void aVisitAlreadyMovedToBillingStillSignsAndTheDoneRowsAreAddedToItsBill() throws Exception {
        Dental d = dentalVisit(true, false);
        Rec rec = d.rec();
        ok(HttpMethod.PATCH, VISITS + "/" + rec.visitId() + "/stage", rec.clinic().owner(), "{\"stage\":\"BILLING\"}", 200);
        JsonNode result = signAs(d, NULL_INTERVAL, 200);
        assertThat(result.get("outcome").get("stage").get("outcome").asText()).isEqualTo("ALREADY_MOVED");
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("SIGNED");
        assertThat(boardVisit(rec.clinic(), rec.visitId()).get("stage").asText()).isEqualTo("BILLING");
        assertThat(planLines(rec.clinic())).hasSize(1);
        assertThat(result.get("outcome").get("billLines").asInt()).isOne();
        assertThat(planLinesOnBill(rec.clinic(), rec.visitId())).hasSize(1);
    }

    @Test
    void aPaidBillStillGetsTheDoneRowLinesAndTheSignCompletes() throws Exception {
        Dental d = dentalVisit(true, false);
        Rec rec = d.rec();
        Clinic clinic = rec.clinic();
        // a bill is paid at the billing counter: the card is there when the patient has paid
        ok(HttpMethod.PATCH, VISITS + "/" + rec.visitId() + "/stage", clinic.owner(), "{\"stage\":\"BILLING\"}", 200);
        String counter = serviceId(clinic, "Counter test", 80);
        ok(HttpMethod.POST, BILLING + "/bills/" + rec.visitId() + "/items", clinic.owner(), "{\"serviceId\":\"" + counter + "\",\"qty\":1}", 200);
        JsonNode bill = ok(HttpMethod.GET, BILLING + "/bills/" + rec.visitId(), clinic.owner(), null, 200);
        long before = bill.get("subtotalPaise").asLong();
        assertThat(before).isPositive();
        ok(HttpMethod.POST, BILLING + "/bills/" + rec.visitId() + "/payments", clinic.owner(), "{\"mode\":\"CASH\",\"amountPaise\":" + before + "}", 200);
        JsonNode check = readyCheck(d, NULL_INTERVAL);
        JsonNode result = signAs(d, NULL_INTERVAL, 200);
        assertThat(statusOf(clinic, rec.id())).isEqualTo("SIGNED");
        assertThat(result.get("outcome").get("billLines").asInt()).isOne();
        assertThat(planLines(clinic)).hasSize(1);
        JsonNode after = ok(HttpMethod.GET, BILLING + "/bills/" + rec.visitId(), clinic.owner(), null, 200);
        assertThat(after.get("subtotalPaise").asLong()).as("the plan line is added to the paid bill (H13)").isEqualTo(before + ROOT_CANAL_PAISE);
        assertThat(after.get("balancePaise").asLong()).isEqualTo(ROOT_CANAL_PAISE);
        assertThat(check.get("effects").get("profile").get("doneRows")).hasSize(2);
        assertThat(result.get("outcome").get("stage").get("outcome").asText()).isEqualTo("ALREADY_MOVED");
    }

    @Test
    void nurseFrontDeskBillingPharmacistAndCounsellorGet403AnotherDoctorAnotherClinicAndNoLoginAreRefusedAndNothingIsSent() throws Exception {
        Dental d = dentalVisit(true, false);
        Rec rec = d.rec();
        String body = signBody(rec.revision(), NULL_INTERVAL);
        for (String role : NOT_DOCTOR_ROLES) {
            signRefused(rec, addNonDoctor(rec.clinic(), role).token(rec.clinic()), body, key(), 403, "forbidden");
        }
        signRefused(rec, addDoctor(rec.clinic(), "Dentist").token(rec.clinic()), body, key(), 403, "not-own-patient");
        signRefused(rec, newRec().token(), body, key(), 404, "record-not-found");
        signRefused(rec, anonymous(), body, key(), 401, "unauthorized");
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("DRAFT");
        assertThat(planLines(rec.clinic())).isEmpty();
    }
}
