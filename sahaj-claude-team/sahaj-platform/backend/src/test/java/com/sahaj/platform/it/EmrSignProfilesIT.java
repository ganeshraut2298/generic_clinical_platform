package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * What the profile contributors do inside the sign, on the real database (docs/api/opd.yaml 0.6.2, ADR-025 D7, DEC-048 H28,
 * code review 7 and 8): the Dental rows done since the day the record was opened become one bill line each (a row with no
 * amount makes none), and the Physiotherapy next session is booked only on a real free slot of the doctor (off the grid, in the
 * past or reserved for walk-ins is 400 {@code choices.physio.nextSlot} and nothing is kept). Test plan:
 * docs/test-plans/SAH-51-68.md (T-62-4.x, T-63-3.x).
 *
 * <p>The department layouts are published with nothing required but the sections the test fills, so a record is ready without
 * every specialty section being filled: these tests are about the sign's effects, not about readiness.
 */
@OnTestDatabase
class EmrSignProfilesIT extends EmrSignSupport {

    private static final String SLOTS = "/api/v1/opd/doctor-slots/";

    /** Publishes the clinic's layout of this department with these sections the only required ones. */
    private void relax(Clinic clinic, String department, String... stillRequired) throws Exception {
        Set<String> keep = Set.of(stillRequired);
        ObjectNode body = layoutInput(department(clinic, department));
        for (JsonNode section : body.get("sections")) {
            ((ObjectNode) section).put("required", keep.contains(section.get("key").asText()));
        }
        publish(clinic, department, body, 200);
    }

    private void assertReady(Rec rec) throws Exception {
        JsonNode check = check(rec);
        assertThat(check.get("ready").asBoolean()).as("the record is ready; blockers: " + check.get("blockers")).isTrue();
    }

    private static String planRow(String id, int tooth, String key, String text, Long typedPaise, String status) {
        return "{\"id\":\"" + id + "\",\"tooth\":" + tooth + ",\"procedureKey\":" + (key == null ? "null" : "\"" + key + "\"")
                + ",\"procedureText\":\"" + text + "\",\"phase\":1,\"status\":\"" + status + "\",\"sittingsPlanned\":1,\"currentSitting\":1"
                + (typedPaise == null ? "" : ",\"typedEstimatePaise\":" + typedPaise) + "}";
    }

    // ================= Dental: the done rows (DEC-048 H28) =================

    /** A ready Dental record whose plan has these rows, all saved as sent. */
    private Rec dentalRec(String rowsJson) throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        relax(clinic, "dental", "complaints", "diagnosis", "rx", "rxformat");
        Rec rec = specialtyRec(clinic, doctor, newPatient(clinic), "dental");
        rec = fillRequired(rec);
        rec = save(rec, spSection("tx_plan", "DENTAL_PLAN", "{\"rows\":[" + rowsJson + "]}"));
        assertReady(rec);
        return rec;
    }

    private List<List<String>> planLines(Clinic clinic) throws Exception {
        return db().inTenant(clinic.tenantId(), c -> EmrSupport.allRows(c,
                "select source_key, label, source_kind from billing.bill_item where source_kind = 'EMR_PLAN' order by source_key"));
    }

    @Test
    void aDentalRowDoneSinceTheDayTheRecordWasOpenedIsOneBillLineAndARowWithNoAmountMakesNoBillLine() throws Exception {
        String priced = uuid();
        String noAmount = uuid();
        Rec rec = dentalRec(planRow(priced, 16, null, "Root canal", 100000L, "DONE") + ","
                + planRow(noAmount, 21, null, "Scaling", null, "DONE"));
        JsonNode outcome = signOk(rec).get("outcome");
        assertThat(outcome.get("billLines").asInt()).as("only the row with an amount is billed").isEqualTo(1);
        assertThat(outcome.get("billTotalPaise").asLong()).isEqualTo(100000L);
        List<List<String>> lines = planLines(rec.clinic());
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get(0)).isEqualTo("plan:" + priced + ":1");
        assertThat(lines.get(0).get(1)).isEqualTo("Root canal · tooth 16");
        assertThat(lines.get(0).get(2)).isEqualTo("EMR_PLAN");
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("SIGNED");
        assertThat(count(rec.clinic(), "select count(*) from billing.bill_item where label like 'Scaling%'")).as("no line for the no-amount row").isZero();
    }

    @Test
    void theFinishCheckShowsTheNoAmountRowWithANullAmountAndOnlyThePricedRowAsABillLine() throws Exception {
        String priced = uuid();
        String noAmount = uuid();
        Rec rec = dentalRec(planRow(priced, 16, null, "Root canal", 100000L, "DONE") + ","
                + planRow(noAmount, 21, null, "Scaling", null, "DONE"));
        JsonNode dental = check(rec).get("effects").get("profile");
        assertThat(dental.get("profile").asText()).isEqualTo("dental");
        JsonNode done = dental.get("doneRows");
        assertThat(done).hasSize(2);
        for (JsonNode row : done) {
            if (noAmount.equals(row.get("rowId").asText())) {
                assertThat(row.get("amountPaise").isNull()).as("Not in tariff").isTrue();
            } else {
                assertThat(row.get("amountPaise").asLong()).isEqualTo(100000L);
            }
        }
        assertThat(planLines(rec.clinic())).as("a check bills nothing").isEmpty();
    }

    @Test
    void aRowMarkedDoneBeforeTheDayTheRecordWasOpenedIsNotBilledAndTheSameKeyReplaysWithoutASecondLine() throws Exception {
        String before = uuid();
        Rec rec = dentalRec(planRow(before, 16, null, "Root canal", 100000L, "DONE"));
        // the server stamped doneOn with today; move it to a day before the record was opened (shape-independent rewrite)
        commit(rec.clinic().tenantId(), "update opd.emr_record set sections = regexp_replace(sections::text, '\"doneOn\": \"[0-9-]+\"', "
                + "'\"doneOn\": \"2020-01-01\"', 'g')::jsonb where id = ?::uuid", rec.id());
        JsonNode outcome = signOk(rec).get("outcome");
        assertThat(outcome.get("billLines").asInt()).as("done before the record was opened: not today's treatment").isZero();
        assertThat(planLines(rec.clinic())).isEmpty();
        // a row done today with the same key replays: one line, however often the sign is repeated
        String today = uuid();
        Rec second = dentalRec(planRow(today, 11, null, "Filling", 50000L, "DONE"));
        String key = key();
        signWithKey(second, key, "{}", 200);
        JsonNode again = signWithKey(second, key, "{}", 200);
        assertThat(again.get("replayed").asBoolean()).isTrue();
        assertThat(planLines(second.clinic())).hasSize(1);
        assertThat(count(second.clinic(), "select count(*) from billing.bill_item where source_kind = 'EMR_PLAN' and source_key = 'plan:" + today + ":1'")).isOne();
    }

    // ================= Physiotherapy: the next session's slot (code review 8) =================

    private Rec physioRec(Clinic clinic, Staff doctor) throws Exception {
        relax(clinic, "physio", "complaints", "diagnosis", "rxformat");
        Rec rec = specialtyRec(clinic, doctor, newPatient(clinic), "physio");
        return save(rec, sectionValues("complaints", COMPLAINTS_JSON), sectionValues("diagnosis", DIAGNOSIS_JSON),
                sectionValues("rxformat", RXFORMAT_JSON));
    }

    private static String slot(LocalDate date, String time) {
        return "{\"physio\":{\"nextSlot\":{\"date\":\"" + date + "\",\"time\":\"" + time + "\"}}}";
    }

    private List<String> nextSitting(Clinic clinic, JsonNode course) throws Exception {
        return row(clinic, "select planned_date::text, (opd_visit_id is null)::text from opd.treatment_course_sitting where course_id = ?::uuid and sitting_no = 2",
                course.get("id").asText());
    }

    @Test
    void aNextSlotThatIsPastOffTheGridOutsideTheSessionOrReservedForWalkInsIs400AndNothingIsKeptAndABookableSlotIsBooked() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        // 30-minute slots from 09:00 to 13:00 on every day, every 4th slot kept for walk-ins: 09:00 09:30 10:00 [10:30] ...
        ok(HttpMethod.PUT, SLOTS + doctor.membershipId(), clinic.owner(), "{\"slotMinutes\":30,\"workingDays\":[0,1,2,3,4,5,6],"
                + "\"walkInEveryNth\":4,\"maxOnlinePerDay\":10,\"sessions\":[{\"startTime\":\"09:00\",\"endTime\":\"13:00\"}]}", 200);
        Rec rec = physioRec(clinic, doctor);
        JsonNode course = newCourse(rec, "REHAB", "[" + sittingJson("extra_session", 50000L, null) + "," + sittingJson("extra_session", 50000L, null) + "]");
        String first = course.get("sittings").get(0).get("id").asText();
        post(COURSES + "/" + course.get("id").asText() + "/sittings/" + first + "/link-visit", rec.token(),
                "{\"revision\":" + course.get("revision").asLong() + ",\"visitId\":\"" + rec.visitId() + "\"}", 200);
        assertReady(rec);
        List<String> planned = nextSitting(clinic, course);
        LocalDate day = today().plusDays(3);
        String[][] refused = {{today().minusDays(1).toString(), "09:00", "in the past"}, {day.toString(), "09:10", "off the grid"},
                {day.toString(), "15:00", "outside the session"}, {day.toString(), "10:30", "reserved for walk-ins"},
                {day.toString(), "9am", "not a time"}};
        for (String[] r : refused) {
            JsonNode problem = signRefused(rec, rec.token(), signBody(rec.revision(), slot(LocalDate.parse(r[0]), r[1])), key(), 400, "validation");
            assertThat(problem.toString()).as(r[2]).contains("choices.physio.nextSlot");
            assertThat(statusOf(clinic, rec.id())).as(r[2]).isEqualTo("DRAFT");
            assertThat(count(clinic, "select count(*) from opd.appointment where source = 'EMR'")).as(r[2] + ": no appointment").isZero();
            assertThat(nextSitting(clinic, course)).as(r[2] + ": the next session keeps its date").isEqualTo(planned);
        }
        JsonNode booked = okKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), signBody(rec.revision(), slot(day, "09:00")), key(), 200);
        assertThat(booked.get("record").get("status").asText()).isEqualTo("SIGNED");
        assertThat(count(clinic, "select count(*) from opd.appointment where source = 'EMR' and appt_date = ?::date and appt_time = '09:00'", day.toString())).isOne();
        assertThat(nextSitting(clinic, course).get(0)).as("the next session is planned on the chosen day").isEqualTo(day.toString());
    }

    @Test
    void aDateAloneWithNoTimeKeepsTheDateOnTheNextSessionAndBooksNothing() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Rec rec = physioRec(clinic, doctor);
        JsonNode course = newCourse(rec, "REHAB", "[" + sittingJson("extra_session", 50000L, null) + "," + sittingJson("extra_session", 50000L, null) + "]");
        post(COURSES + "/" + course.get("id").asText() + "/sittings/" + course.get("sittings").get(0).get("id").asText() + "/link-visit", rec.token(),
                "{\"revision\":" + course.get("revision").asLong() + ",\"visitId\":\"" + rec.visitId() + "\"}", 200);
        LocalDate day = today().plusDays(5);
        okKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), signBody(rec.revision(),
                "{\"physio\":{\"nextSlot\":{\"date\":\"" + day + "\",\"time\":null}}}"), key(), 200);
        assertThat(nextSitting(clinic, course).get(0)).isEqualTo(day.toString());
        assertThat(count(clinic, "select count(*) from opd.appointment where source = 'EMR'")).isZero();
    }

    @Test
    void aNextSlotBodyCannotNameADoctorBecauseTheSlotIsCheckedAgainstTheVisitsOwnDoctor() throws Exception {
        // the body has no doctor field and unknown fields are refused (closed body)
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Rec rec = physioRec(clinic, doctor);
        JsonNode course = newCourse(rec, "REHAB", "[" + sittingJson("extra_session", 50000L, null) + "," + sittingJson("extra_session", 50000L, null) + "]");
        post(COURSES + "/" + course.get("id").asText() + "/sittings/" + course.get("sittings").get(0).get("id").asText() + "/link-visit", rec.token(),
                "{\"revision\":" + course.get("revision").asLong() + ",\"visitId\":\"" + rec.visitId() + "\"}", 200);
        String forged = "{\"physio\":{\"nextSlot\":{\"date\":\"" + today().plusDays(2) + "\",\"time\":\"09:00\",\"doctorMembershipId\":\""
                + UUID.randomUUID() + "\"}}}";
        problemKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), signBody(rec.revision(), forged), key(), 400, "validation");
        assertThat(statusOf(clinic, rec.id())).isEqualTo("DRAFT");
    }
}
