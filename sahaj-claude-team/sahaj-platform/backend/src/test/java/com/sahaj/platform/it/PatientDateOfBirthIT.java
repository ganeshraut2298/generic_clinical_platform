package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sahaj.platform.support.LogCapture;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SAH-73 optional patient date of birth through the real Patients endpoints on {@code sahaj_test} (ADR-024, DEC-045,
 * {@code docs/api/patient.yaml} 0.2.0): create and PATCH in their three states, the four refusal messages, the age
 * worked out on every read (a stale stored age is never used), the unit of the age text at its edges, the merge rule,
 * who sees the date, the chips and the duplicate check by effective years, the audit event without the value, and no
 * date in any log. The application clock cannot move in an integration test, so every date is worked out from today in
 * India; the fixed-day vectors (29 Feb, 2 years, 3 months, 2 weeks) are the unit tests of {@code PatientAge}. Test plan:
 * docs/test-plans/SAH-73.md.
 */
@OnTestDatabase
class PatientDateOfBirthIT extends EmrSupport {

    private static final String PATIENTS = "/api/v1/patient/patients";
    private static final String[] DATE_ROLES = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK"};
    private static final String[] NO_WRITE_ROLES = {"NURSE", "DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"};

    private static final String FUTURE = "Date of birth cannot be in the future.";
    private static final String TOO_OLD = "Date of birth must be within the last 120 years.";
    private static final String INVALID = "Enter a valid date of birth.";
    private static final String MISMATCH = "Age does not match the date of birth.";
    private static final String AGE_RANGE = "Enter an age between 0 and 120";
    private static final String CHANGED = "PATIENT_DATE_OF_BIRTH_CHANGED";

    // ---------------- small helpers ----------------

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    /** Completed years by plain calendar arithmetic (not Period): a 29 Feb birthday is on 1 Mar in other years. */
    private static int yearsBetween(LocalDate dob, LocalDate asOf) {
        int years = asOf.getYear() - dob.getYear();
        boolean birthdayPassed = asOf.getMonthValue() > dob.getMonthValue()
                || (asOf.getMonthValue() == dob.getMonthValue() && asOf.getDayOfMonth() >= dob.getDayOfMonth());
        return birthdayPassed ? years : years - 1;
    }

    private ObjectNode base(String name, String sex, String mobile) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("sex", sex);
        body.put("mobile", mobile);
        return body;
    }

    /** A valid registration body with an optional date and an optional age (both may be null). */
    private ObjectNode registration(String name, LocalDate dob, Integer age) {
        ObjectNode body = base(name, "F", uniqueMobile());
        if (dob != null) {
            body.put("dateOfBirth", dob.toString());
        }
        if (age != null) {
            body.put("age", age);
        }
        return body;
    }

    private JsonNode register(Clinic clinic, String name, LocalDate dob, Integer age) throws Exception {
        return ok(HttpMethod.POST, PATIENTS, clinic.as("FRONT_DESK"), registration(name, dob, age).toString(), 201);
    }

    private JsonNode get(Clinic clinic, String role, String id) throws Exception {
        return ok(HttpMethod.GET, PATIENTS + "/" + id, clinic.as(role), null, 200);
    }

    private JsonNode patch(Clinic clinic, String id, String body) throws Exception {
        return ok(HttpMethod.PATCH, PATIENTS + "/" + id, clinic.as("FRONT_DESK"), body, 200);
    }

    private JsonNode refusePatch(Clinic clinic, String id, String body) throws Exception {
        return problem(call(HttpMethod.PATCH, PATIENTS + "/" + id, clinic.as("FRONT_DESK"), body), 400);
    }

    private JsonNode refuseRegister(Clinic clinic, ObjectNode body) throws Exception {
        return problem(call(HttpMethod.POST, PATIENTS, clinic.as("FRONT_DESK"), body.toString()), 400);
    }

    private static String messageOn(JsonNode problem, String field) {
        for (JsonNode e : problem.get("fieldErrors")) {
            if (field.equals(e.get("field").asText())) {
                return e.get("message").asText();
            }
        }
        return null;
    }

    private static void assertAge(JsonNode patient, int years, String unit, int value, boolean fromDate) {
        assertThat(patient.get("age").asInt()).as("age (effective completed years)").isEqualTo(years);
        assertThat(patient.get("ageDetail").get("unit").asText()).as("unit").isEqualTo(unit);
        assertThat(patient.get("ageDetail").get("value").asInt()).as("value").isEqualTo(value);
        assertThat(patient.get("ageDetail").get("fromDateOfBirth").asBoolean()).as("fromDateOfBirth").isEqualTo(fromDate);
    }

    private static boolean dateIsNull(JsonNode patient) {
        return patient.has("dateOfBirth") && patient.get("dateOfBirth").isNull();
    }

    private long total(Clinic clinic, String query) throws Exception {
        return ok(HttpMethod.GET, PATIENTS + query, clinic.owner(), null, 200).get("total").asLong();
    }

    private List<String> names(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("name").asText()));
        return out;
    }

    private List<String> chip(Clinic clinic, String filter) throws Exception {
        return names(ok(HttpMethod.GET, PATIENTS + "?filter=" + filter, clinic.owner(), null, 200));
    }

    private List<List<String>> auditRows(Clinic clinic, String action, String patientId) throws SQLException {
        return rows(clinic, "select coalesce(before_json::text, ''), coalesce(after_json::text, ''), coalesce(actor_user_id::text, '') "
                + "from platform.audit_event where action = ? and entity_id = ?::uuid order by at, id", action, patientId);
    }

    private String allAuditText(Clinic clinic) throws SQLException {
        return row(clinic, "select coalesce(string_agg(action || ' ' || coalesce(before_json::text, '') || ' ' || coalesce(after_json::text, ''), E'\n'), '') from platform.audit_event").get(0);
    }

    private long changedEvents(Clinic clinic, String patientId) throws SQLException {
        return auditRows(clinic, CHANGED, patientId).size();
    }

    private String storedAge(Clinic clinic, String patientId) throws SQLException {
        return row(clinic, "select age::text from patient.patient where id = ?::uuid", patientId).get(0);
    }

    /** What the table holds, as the app reads it (the stored age is only a fallback snapshot, ADR-024 D4). */
    private void makeStoredAgeStale(Clinic clinic, String patientId, int age) throws SQLException {
        commit(clinic.tenantId(), "update patient.patient set age = ? where id = ?::uuid", age, patientId);
    }

    // ---------------- SAH-73-1 create: the three states ----------------

    @Test
    void registeringWithADateOnlyStoresTheDateAndWorksTheAgeOutFromIt() throws Exception {
        Clinic clinic = newClinic();
        LocalDate dob = today().minusYears(34);
        JsonNode created = register(clinic, "Dated Adult", dob, null);
        assertAge(created, 34, "YEARS", 34, true);
        assertThat(created.get("dateOfBirth").asText()).isEqualTo(dob.toString());
        String id = created.get("id").asText();
        for (String role : DATE_ROLES) {
            JsonNode got = get(clinic, role, id);
            assertThat(got.get("dateOfBirth").asText()).as(role).isEqualTo(dob.toString());
            assertAge(got, 34, "YEARS", 34, true);
        }
        assertThat(storedAge(clinic, id)).as("the stored age is the write-time snapshot of the completed years").isEqualTo("34");
    }

    @Test
    void aChildOfSeventeenMonthsReadsInMonthsAfterSaving() throws Exception {
        Clinic clinic = newClinic();
        JsonNode created = register(clinic, "Month Child", today().minusMonths(17), null);
        assertAge(created, 1, "MONTHS", 17, true);
        assertAge(get(clinic, "NURSE", created.get("id").asText()), 1, "MONTHS", 17, true);
    }

    @Test
    void registeringWithAnAgeOnlyKeepsTheAgeAndHasNoDate() throws Exception {
        Clinic clinic = newClinic();
        JsonNode created = register(clinic, "Undated Adult", null, 34);
        assertAge(created, 34, "YEARS", 34, false);
        assertThat(dateIsNull(created)).as("owner and front desk see dateOfBirth: null (not recorded)").isTrue();
        assertThat(dateIsNull(get(clinic, "DOCTOR", created.get("id").asText()))).isTrue();
        assertThat(storedAge(clinic, created.get("id").asText())).isEqualTo("34");
    }

    @Test
    void aDateOfNullWithAnAgeIsTheSameAsNoDate() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode body = registration("Null Date", null, 41);
        body.putNull("dateOfBirth");
        JsonNode created = ok(HttpMethod.POST, PATIENTS, clinic.as("FRONT_DESK"), body.toString(), 201);
        assertAge(created, 41, "YEARS", 41, false);
        assertThat(dateIsNull(created)).isTrue();
    }

    @Test
    void aMatchingDateAndAgeAreBothAcceptedAndADifferentAgeIsRefusedOnAge() throws Exception {
        Clinic clinic = newClinic();
        LocalDate dob = today().minusYears(34);
        ok(HttpMethod.POST, PATIENTS, clinic.as("FRONT_DESK"), registration("Both Match", dob, 34).toString(), 201);
        for (int wrong : new int[] {33, 35, 0, 120}) {
            JsonNode p = refuseRegister(clinic, registration("Both Differ " + wrong, dob, wrong));
            assertThat(messageOn(p, "age")).as("age " + wrong).isEqualTo(MISMATCH);
            assertThat(messageOn(p, "dateOfBirth")).isNull();
        }
        assertThat(total(clinic, "")).as("only the matching one was saved").isEqualTo(1);
    }

    @Test
    void registeringWithNeitherADateNorAnAgeIsRefusedOnAgeAndSavesNothing() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode none = registration("No Age", null, null);
        assertThat(messageOn(refuseRegister(clinic, none), "age")).isEqualTo(AGE_RANGE);
        ObjectNode nullDate = registration("No Age", null, null);
        nullDate.putNull("dateOfBirth");
        assertThat(messageOn(refuseRegister(clinic, nullDate), "age")).isEqualTo(AGE_RANGE);
        for (int bad : new int[] {-1, 121}) {
            ObjectNode out = registration("Bad Age", null, bad);
            assertThat(messageOn(refuseRegister(clinic, out), "age")).as("age " + bad).isEqualTo(AGE_RANGE);
        }
        assertThat(total(clinic, "")).isZero();
    }

    // ---------------- SAH-73-1 validation messages ----------------

    @Test
    void aDateInTheFutureIsRefusedButTodayIsAcceptedAsANewborn() throws Exception {
        Clinic clinic = newClinic();
        for (int days : new int[] {1, 2, 400}) {
            JsonNode p = refuseRegister(clinic, registration("Future " + days, today().plusDays(days), null));
            assertThat(messageOn(p, "dateOfBirth")).as("+" + days + " days").isEqualTo(FUTURE);
        }
        assertThat(total(clinic, "")).isZero();
        JsonNode newborn = register(clinic, "Newborn Today", today(), null);
        assertAge(newborn, 0, "DAYS", 0, true);
    }

    @Test
    void aDateMoreThanOneHundredTwentyYearsBackIsRefusedAndTheLastDayThatStillCountsIsAccepted() throws Exception {
        Clinic clinic = newClinic();
        for (LocalDate tooOld : new LocalDate[] {today().minusYears(121), today().minusYears(121).minusDays(1),
                LocalDate.of(1899, 12, 31), LocalDate.of(1900, 1, 1)}) {
            JsonNode p = refuseRegister(clinic, registration("Too Old " + tooOld, tooOld, null));
            assertThat(messageOn(p, "dateOfBirth")).as(tooOld.toString()).isEqualTo(TOO_OLD);
        }
        // exactly 120 completed years, and one day short of 121: both are 120 and are accepted
        assertAge(register(clinic, "Exactly 120", today().minusYears(120), null), 120, "YEARS", 120, true);
        assertAge(register(clinic, "Almost 121", today().minusYears(121).plusDays(1), null), 120, "YEARS", 120, true);
        assertThat(total(clinic, "")).isEqualTo(2);
    }

    @Test
    void aDateThatDoesNotExistOrIsNotADateIsRefusedWithoutEchoingIt() throws Exception {
        Clinic clinic = newClinic();
        List<com.fasterxml.jackson.databind.JsonNode> bad = List.of(
                TextNode.valueOf("2026-02-31"), TextNode.valueOf("zxqnotadate"), TextNode.valueOf("13-11-2020"),
                TextNode.valueOf("2020/11/13"), TextNode.valueOf("2020-1-5"), TextNode.valueOf(""),
                TextNode.valueOf(" 2020-01-01"), TextNode.valueOf("2020-01-01T00:00:00"), TextNode.valueOf("2021-13-01"),
                TextNode.valueOf("2021-00-10"), IntNode.valueOf(20200101), BooleanNode.TRUE, objectMapper.createObjectNode(),
                objectMapper.createArrayNode());
        for (JsonNode value : bad) {
            ObjectNode body = registration("Bad Date", null, null);
            body.set("dateOfBirth", value);
            JsonNode p = refuseRegister(clinic, body);
            assertThat(messageOn(p, "dateOfBirth")).as(value.toString()).isEqualTo(INVALID);
            if (value.isTextual() && value.asText().length() > 3) {
                assertThat(p.toString()).as("the refusal does not repeat " + value).doesNotContain(value.asText());
            }
        }
        // an invalid date with a valid age is still a 400 (the date is not silently dropped)
        ObjectNode withAge = registration("Bad Date With Age", null, 30);
        withAge.put("dateOfBirth", "2026-02-31");
        assertThat(messageOn(refuseRegister(clinic, withAge), "dateOfBirth")).isEqualTo(INVALID);
        assertThat(total(clinic, "")).isZero();
    }

    // ---------------- SAH-73-2 PATCH: absent, a date, explicit null ----------------

    @Test
    void aPatchWithoutTheDateLeavesItAloneAndWritesNoDateEvent() throws Exception {
        Clinic clinic = newClinic();
        LocalDate dob = today().minusYears(8);
        JsonNode p = register(clinic, "Keep My Date", dob, null);
        String id = p.get("id").asText();
        JsonNode after = patch(clinic, id, "{\"name\":\"Keep My Date Renamed\"}");
        assertThat(after.get("dateOfBirth").asText()).isEqualTo(dob.toString());
        assertAge(after, 8, "YEARS", 8, true);
        assertThat(changedEvents(clinic, id)).isZero();
        // an empty patch changes nothing at all
        assertThat(patch(clinic, id, "{}").get("dateOfBirth").asText()).isEqualTo(dob.toString());
    }

    @Test
    void addingADateToAnOlderRecordMakesTheAgeFollowTheDateAndAuditsOneSetEvent() throws Exception {
        Clinic clinic = newClinic();
        JsonNode p = register(clinic, "Add A Date", null, 5);
        String id = p.get("id").asText();
        LocalDate dob = LocalDate.of(2020, 3, 4);
        JsonNode after = patch(clinic, id, "{\"dateOfBirth\":\"" + dob + "\"}");
        int years = yearsBetween(dob, today());
        assertAge(after, years, years >= 2 ? "YEARS" : "MONTHS", years, true);
        assertThat(after.get("dateOfBirth").asText()).isEqualTo(dob.toString());
        assertThat(storedAge(clinic, id)).isEqualTo(String.valueOf(years));
        List<List<String>> events = auditRows(clinic, CHANGED, id);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get(1)).contains("\"change\"").contains("SET").contains("dateOfBirth");
        assertThat(events.get(0).get(2)).as("who").isEqualTo(clinic.ownerUserId().toString());
    }

    @Test
    void correctingTheDateChangesTheAgeAndAuditsOneChangedEventAndTheSameDateAgainWritesNone() throws Exception {
        Clinic clinic = newClinic();
        String id = register(clinic, "Correct Me", today().minusYears(5), null).get("id").asText();
        JsonNode corrected = patch(clinic, id, "{\"dateOfBirth\":\"" + today().minusYears(9) + "\"}");
        assertAge(corrected, 9, "YEARS", 9, true);
        assertThat(changedEvents(clinic, id)).isEqualTo(1);
        assertThat(auditRows(clinic, CHANGED, id).get(0).get(1)).contains("CHANGED");
        patch(clinic, id, "{\"dateOfBirth\":\"" + today().minusYears(9) + "\"}");
        assertThat(changedEvents(clinic, id)).as("the same date again is not a change").isEqualTo(1);
    }

    @Test
    void clearingTheDateWithAnExplicitNullKeepsTheWorkedOutAgeAndAuditsOneClearedEvent() throws Exception {
        Clinic clinic = newClinic();
        String id = register(clinic, "Clear Me", today().minusYears(8), null).get("id").asText();
        JsonNode cleared = patch(clinic, id, "{\"dateOfBirth\":null}");
        assertThat(dateIsNull(cleared)).isTrue();
        assertAge(cleared, 8, "YEARS", 8, false);
        assertThat(storedAge(clinic, id)).as("the record is never left without an age").isEqualTo("8");
        List<List<String>> events = auditRows(clinic, CHANGED, id);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get(1)).contains("CLEARED").contains("\"dateOfBirthRecorded\": false");
        // the age no longer changes by itself: a second read is the same stored number
        assertAge(get(clinic, "OWNER", id), 8, "YEARS", 8, false);
    }

    @Test
    void clearingWithATypedAgeKeepsThatAgeAndClearingAnUndatedRecordIsANoOp() throws Exception {
        Clinic clinic = newClinic();
        String id = register(clinic, "Clear With Age", today().minusYears(8), null).get("id").asText();
        JsonNode cleared = patch(clinic, id, "{\"dateOfBirth\":null,\"age\":9}");
        assertAge(cleared, 9, "YEARS", 9, false);
        assertThat(changedEvents(clinic, id)).isEqualTo(1);
        patch(clinic, id, "{\"dateOfBirth\":null}");
        assertThat(changedEvents(clinic, id)).as("nothing to clear").isEqualTo(1);
        JsonNode undated = register(clinic, "Never Had A Date", null, 20);
        assertAge(patch(clinic, undated.get("id").asText(), "{\"dateOfBirth\":null}"), 20, "YEARS", 20, false);
        assertThat(changedEvents(clinic, undated.get("id").asText())).isZero();
    }

    @Test
    void anAgeSentWhileTheRecordKeepsItsDateMustMatchAndAMatchIsANoOp() throws Exception {
        Clinic clinic = newClinic();
        String id = register(clinic, "Keep Date Age", today().minusYears(8), null).get("id").asText();
        assertThat(messageOn(refusePatch(clinic, id, "{\"age\":9}"), "age")).isEqualTo(MISMATCH);
        assertThat(messageOn(refusePatch(clinic, id, "{\"age\":7}"), "age")).isEqualTo(MISMATCH);
        assertAge(patch(clinic, id, "{\"age\":8}"), 8, "YEARS", 8, true);
        assertThat(changedEvents(clinic, id)).isZero();
        // a date and an age together: they must agree
        assertThat(messageOn(refusePatch(clinic, id, "{\"dateOfBirth\":\"" + today().minusYears(3) + "\",\"age\":4}"), "age"))
                .isEqualTo(MISMATCH);
        assertAge(patch(clinic, id, "{\"dateOfBirth\":\"" + today().minusYears(3) + "\",\"age\":3}"), 3, "YEARS", 3, true);
    }

    @Test
    void aPatchWithAnInvalidDateIsRefusedWithTheSameMessagesAndSavesNothing() throws Exception {
        Clinic clinic = newClinic();
        LocalDate dob = today().minusYears(8);
        String id = register(clinic, "Refuse Edits", dob, null).get("id").asText();
        assertThat(messageOn(refusePatch(clinic, id, "{\"dateOfBirth\":\"" + today().plusDays(1) + "\"}"), "dateOfBirth")).isEqualTo(FUTURE);
        assertThat(messageOn(refusePatch(clinic, id, "{\"dateOfBirth\":\"" + today().minusYears(121) + "\"}"), "dateOfBirth")).isEqualTo(TOO_OLD);
        for (String bad : new String[] {"\"2026-02-31\"", "\"zxqnotadate\"", "12345", "true", "\"\""}) {
            assertThat(messageOn(refusePatch(clinic, id, "{\"dateOfBirth\":" + bad + "}"), "dateOfBirth")).as(bad).isEqualTo(INVALID);
        }
        assertThat(get(clinic, "OWNER", id).get("dateOfBirth").asText()).isEqualTo(dob.toString());
        assertThat(changedEvents(clinic, id)).isZero();
    }

    @Test
    void aDateOnlyChangeWritesNoUpdatedEventButANameChangeDoes() throws Exception {
        Clinic clinic = newClinic();
        String id = register(clinic, "Event Split", null, 6).get("id").asText();
        patch(clinic, id, "{\"dateOfBirth\":\"" + today().minusYears(6) + "\"}");
        assertThat(auditRows(clinic, "PATIENT_UPDATED", id)).as("date-only change: PATIENT_UPDATED is not written").isEmpty();
        assertThat(changedEvents(clinic, id)).isEqualTo(1);
        patch(clinic, id, "{\"name\":\"Event Split Renamed\"}");
        assertThat(auditRows(clinic, "PATIENT_UPDATED", id)).hasSize(1);
        assertThat(changedEvents(clinic, id)).as("a name change is not a date event").isEqualTo(1);
        // both together: one of each
        patch(clinic, id, "{\"name\":\"Event Split Again\",\"dateOfBirth\":\"" + today().minusYears(7) + "\"}");
        assertThat(auditRows(clinic, "PATIENT_UPDATED", id)).hasSize(2);
        assertThat(changedEvents(clinic, id)).isEqualTo(2);
    }

    @Test
    void onlyOwnerAndFrontDeskMayWriteTheDateAndAnotherClinicsPatientIs404() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        LocalDate dob = today().minusYears(8);
        String id = register(a, "Role Check", dob, null).get("id").asText();
        String body = "{\"dateOfBirth\":\"" + today().minusYears(9) + "\"}";
        for (String role : NO_WRITE_ROLES) {
            problem(call(HttpMethod.PATCH, PATIENTS + "/" + id, a.as(role), body), 403);
            problem(call(HttpMethod.POST, PATIENTS, a.as(role), registration("Nope " + role, dob, null).toString()), 403);
        }
        call(HttpMethod.PATCH, PATIENTS + "/" + id, anonymous(), body).andExpect(status().isUnauthorized());
        call(HttpMethod.POST, PATIENTS, anonymous(), registration("Nobody", dob, null).toString()).andExpect(status().isUnauthorized());
        problem(call(HttpMethod.PATCH, PATIENTS + "/" + id, b.owner(), body), 404);
        problem(call(HttpMethod.PATCH, PATIENTS + "/" + id, b.owner(), "{\"dateOfBirth\":null}"), 404);
        problem(call(HttpMethod.GET, PATIENTS + "/" + id, b.owner(), null), 404);
        assertThat(get(a, "OWNER", id).get("dateOfBirth").asText()).as("nothing changed").isEqualTo(dob.toString());
        assertThat(changedEvents(a, id)).isZero();
        // the owner and the front desk may write
        assertThat(ok(HttpMethod.PATCH, PATIENTS + "/" + id, a.owner(), body, 200).get("age").asInt()).isEqualTo(9);
    }

    @Test
    void theClinicComesFromTheLoggedInUserAndNeverFromTheRequestBody() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        ObjectNode body = registration("Tenant From Session", today().minusYears(4), null);
        body.put("tenantId", b.tenantId().toString());
        JsonNode created = ok(HttpMethod.POST, PATIENTS, a.owner(), body.toString(), 201);
        assertThat(get(a, "OWNER", created.get("id").asText()).get("dateOfBirth").asText()).isNotBlank();
        problem(call(HttpMethod.GET, PATIENTS + "/" + created.get("id").asText(), b.owner(), null), 404);
        assertThat(total(b, "")).isZero();
    }

    // ---------------- BR73-4: the age is worked out on every read ----------------

    @Test
    void aStaleStoredAgeIsNeverUsedWhileADateExistsAndTheNextWriteRefreshesIt() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        ObjectNode body = base("Stale Age", "M", mobile);
        body.put("dateOfBirth", today().minusYears(10).toString());
        String id = ok(HttpMethod.POST, PATIENTS, clinic.owner(), body.toString(), 201).get("id").asText();
        makeStoredAgeStale(clinic, id, 99);
        assertThat(storedAge(clinic, id)).isEqualTo("99");
        assertAge(get(clinic, "OWNER", id), 10, "YEARS", 10, true);
        JsonNode inList = ok(HttpMethod.GET, PATIENTS + "?q=" + mobile, clinic.owner(), null, 200).get("items").get(0);
        assertThat(inList.get("age").asInt()).as("list row").isEqualTo(10);
        assertThat(inList.get("ageDetail").get("value").asInt()).isEqualTo(10);
        patch(clinic, id, "{\"name\":\"Stale Age Renamed\"}");
        assertThat(storedAge(clinic, id)).as("the write refreshed the fallback snapshot").isEqualTo("10");
    }

    @Test
    void anUndatedPatientKeepsTheStoredAgeAsIs() throws Exception {
        Clinic clinic = newClinic();
        String id = register(clinic, "Undated Fixed", null, 34).get("id").asText();
        makeStoredAgeStale(clinic, id, 50);
        assertAge(get(clinic, "OWNER", id), 50, "YEARS", 50, false);
    }

    // ---------------- BR73-7: the unit of the age text at its edges ----------------

    @Test
    void theAgeUnitChangesAtTwoYearsThreeMonthsAndTwoWeeks() throws Exception {
        Clinic clinic = newClinic();
        LocalDate t = today();
        // 2 years exactly: YEARS 2 ; one day short of 2 years: 23 completed months
        assertAge(register(clinic, "Edge 2y", t.minusYears(2), null), 2, "YEARS", 2, true);
        JsonNode shortOfTwo = register(clinic, "Edge 2y minus 1d", t.minusYears(2).plusDays(1), null);
        assertAge(shortOfTwo, 1, "MONTHS", 23, true);
        // 3 months exactly: MONTHS 3 ; one day short: WEEKS
        assertAge(register(clinic, "Edge 3m", t.minusMonths(3), null), 0, "MONTHS", 3, true);
        LocalDate shortOfThreeMonths = t.minusMonths(3).plusDays(1);
        assertAge(register(clinic, "Edge 3m minus 1d", shortOfThreeMonths, null), 0, "WEEKS",
                (int) (ChronoUnit.DAYS.between(shortOfThreeMonths, t) / 7), true);
        // 14 days: WEEKS 2 ; 13 days: DAYS 13
        assertAge(register(clinic, "Edge 14d", t.minusDays(14), null), 0, "WEEKS", 2, true);
        assertAge(register(clinic, "Edge 13d", t.minusDays(13), null), 0, "DAYS", 13, true);
        assertAge(register(clinic, "Edge 9d", t.minusDays(9), null), 0, "DAYS", 9, true);
        assertAge(register(clinic, "Edge 10 weeks", t.minusWeeks(10), null), 0, "WEEKS", 10, true);
        assertAge(register(clinic, "Edge 1d", t.minusDays(1), null), 0, "DAYS", 1, true);
        assertAge(register(clinic, "Edge 0d", t, null), 0, "DAYS", 0, true);
    }

    @Test
    void aTwentyNinthOfFebruaryBirthdayIsOnTheFirstOfMarchInOtherYears() throws Exception {
        Clinic clinic = newClinic();
        LocalDate dob = LocalDate.of(2020, 2, 29);
        int expected = yearsBetween(dob, today());
        JsonNode created = register(clinic, "Leap Child", dob, null);
        assertAge(created, expected, "YEARS", expected, true);
        assertAge(get(clinic, "OWNER", created.get("id").asText()), expected, "YEARS", expected, true);
        // the same child sent with the right age is accepted and with one year more is refused
        ok(HttpMethod.POST, PATIENTS, clinic.owner(), registration("Leap Child Two", dob, expected).toString(), 201);
        assertThat(messageOn(refuseRegister(clinic, registration("Leap Child Three", dob, expected + 1)), "age")).isEqualTo(MISMATCH);
    }

    // ---------------- BR73-10: chips, search, duplicate ----------------

    @Test
    void theChildrenChipIsUnderThirteenForDatedAndUndatedPatientsAndTheOldUnderEighteenRuleIsGone() throws Exception {
        Clinic clinic = newClinic();
        LocalDate t = today();
        register(clinic, "Kid Undated 12", null, 12);
        register(clinic, "Kid Dated 12y11m", t.minusYears(12).minusMonths(11), null);
        register(clinic, "Kid Turns 13 Tomorrow", t.minusYears(13).plusDays(1), null);
        register(clinic, "Kid Newborn", t, null);
        register(clinic, "Not Kid Undated 13", null, 13);
        register(clinic, "Not Kid Undated 17", null, 17);
        register(clinic, "Not Kid Dated 13 Today", t.minusYears(13), null);
        register(clinic, "Not Kid Dated 16", t.minusYears(16), null);
        register(clinic, "Not Kid Adult", null, 40);
        assertThat(chip(clinic, "kids")).containsExactlyInAnyOrder("Kid Undated 12", "Kid Dated 12y11m", "Kid Turns 13 Tomorrow", "Kid Newborn");
        assertThat(total(clinic, "?filter=kids")).isEqualTo(4);
        assertThat(total(clinic, "?filter=all")).isEqualTo(9);
    }

    @Test
    void theChildrenChipFollowsACorrectedDateAndAChildWhoTurnsThirteenLeavesIt() throws Exception {
        Clinic clinic = newClinic();
        String id = register(clinic, "Chip Mover", null, 12).get("id").asText();
        assertThat(chip(clinic, "kids")).containsExactly("Chip Mover");
        patch(clinic, id, "{\"dateOfBirth\":\"" + today().minusYears(13) + "\"}");
        assertThat(chip(clinic, "kids")).as("13 today: out of Children that day").isEmpty();
        patch(clinic, id, "{\"dateOfBirth\":\"" + today().minusYears(13).plusDays(1) + "\"}");
        assertThat(chip(clinic, "kids")).containsExactly("Chip Mover");
    }

    @Test
    void theSixtyPlusChipUsesTheDateWhenThereIsOneAndTheStoredAgeOtherwise() throws Exception {
        Clinic clinic = newClinic();
        LocalDate t = today();
        register(clinic, "Senior Undated 60", null, 60);
        register(clinic, "Senior Dated 60 Today", t.minusYears(60), null);
        register(clinic, "Senior Dated 75", t.minusYears(75), null);
        register(clinic, "Not Senior Undated 59", null, 59);
        register(clinic, "Not Senior Dated 59y364d", t.minusYears(60).plusDays(1), null);
        register(clinic, "Not Senior Child", t.minusYears(5), null);
        assertThat(chip(clinic, "seniors")).containsExactlyInAnyOrder("Senior Undated 60", "Senior Dated 60 Today", "Senior Dated 75");
        assertThat(total(clinic, "?filter=seniors")).isEqualTo(3);
        // a 29 Feb birthday goes through the same date bound
        LocalDate leapSenior = LocalDate.of(1964, 2, 29);
        LocalDate leapChild = LocalDate.of(2016, 2, 29);
        register(clinic, "Leap Senior", leapSenior, null);
        register(clinic, "Leap Child", leapChild, null);
        assertThat(chip(clinic, "seniors").contains("Leap Senior")).isEqualTo(yearsBetween(leapSenior, t) >= 60);
        assertThat(chip(clinic, "kids").contains("Leap Child")).isEqualTo(yearsBetween(leapChild, t) < 13);
    }

    @Test
    void searchNeverMatchesADateOfBirthAndNoListOrFamilyRowCarriesIt() throws Exception {
        Clinic clinic = newClinic();
        LocalDate dob = today().minusYears(12).minusDays(33);
        String mobile = uniqueMobile();
        ObjectNode parent = base("Search Parent", "F", mobile);
        parent.put("age", 40);
        ok(HttpMethod.POST, PATIENTS, clinic.owner(), parent.toString(), 201);
        ObjectNode child = base("Search Child", "M", mobile);
        child.put("dateOfBirth", dob.toString());
        JsonNode created = ok(HttpMethod.POST, PATIENTS, clinic.owner(), child.toString(), 201);
        String childId = created.get("id").asText();
        String iso = dob.toString();
        String indian = String.format("%02d/%02d/%d", dob.getDayOfMonth(), dob.getMonthValue(), dob.getYear());
        for (String q : new String[] {iso, indian, iso.replace("-", "")}) {
            assertThat(total(clinic, "?q=" + enc(q))).as("search for " + q).isZero();
        }
        for (String role : DATE_ROLES) {
            for (String path : new String[] {"", "?filter=kids", "?filter=all&page=0", "?q=search"}) {
                JsonNode page = ok(HttpMethod.GET, PATIENTS + path, clinic.as(role), null, 200);
                assertThat(page.toString()).as(role + " " + path).doesNotContain(iso).doesNotContain("dateOfBirth");
                page.get("items").forEach(i -> assertThat(i.has("dateOfBirth")).isFalse());
            }
            JsonNode family = ok(HttpMethod.GET, PATIENTS + "/" + childId + "/family", clinic.as(role), null, 200);
            assertThat(family.toString()).as(role + " family").doesNotContain(iso).doesNotContain("dateOfBirth");
            assertThat(family.size()).isEqualTo(1);
            assertThat(family.get(0).get("ageDetail")).isNotNull();
        }
        // the parent's family view shows the child by the worked-out age text
        JsonNode parentId = ok(HttpMethod.GET, PATIENTS + "?q=" + enc("Search Parent"), clinic.owner(), null, 200).get("items").get(0);
        JsonNode family = ok(HttpMethod.GET, PATIENTS + "/" + parentId.get("id").asText() + "/family", clinic.owner(), null, 200);
        assertThat(family.get(0).get("age").asInt()).isEqualTo(12);
        assertThat(family.get(0).get("ageDetail").get("unit").asText()).isEqualTo("YEARS");
        assertThat(family.get(0).get("ageDetail").get("fromDateOfBirth").asBoolean()).isTrue();
    }

    @Test
    void aFamilyRowOfAnInfantReadsInMonths() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        ObjectNode parent = base("Infant Parent", "F", mobile);
        parent.put("age", 30);
        String parentId = ok(HttpMethod.POST, PATIENTS, clinic.owner(), parent.toString(), 201).get("id").asText();
        ObjectNode infant = base("Infant Child", "F", mobile);
        infant.put("dateOfBirth", today().minusMonths(18).toString());
        ok(HttpMethod.POST, PATIENTS, clinic.owner(), infant.toString(), 201);
        JsonNode family = ok(HttpMethod.GET, PATIENTS + "/" + parentId + "/family", clinic.owner(), null, 200);
        assertThat(family.get(0).get("age").asInt()).isEqualTo(1);
        assertThat(family.get(0).get("ageDetail").get("unit").asText()).isEqualTo("MONTHS");
        assertThat(family.get(0).get("ageDetail").get("value").asInt()).isEqualTo(18);
        JsonNode inList = ok(HttpMethod.GET, PATIENTS + "?q=" + enc("Infant Child"), clinic.owner(), null, 200).get("items").get(0);
        assertThat(inList.get("ageDetail").get("unit").asText()).isEqualTo("MONTHS");
    }

    @Test
    void theDuplicateCheckComparesTheEffectiveYearsNeverTheDates() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        ObjectNode first = base("Ramesh Kulkarni", "M", mobile);
        first.put("age", 45);
        String firstId = ok(HttpMethod.POST, PATIENTS, clinic.owner(), first.toString(), 201).get("id").asText();
        // a dated patient of effective 47 years (45 + 2): within two years, flagged
        ObjectNode within = base("Ramesh Kulkarni", "M", mobile);
        within.put("dateOfBirth", today().minusYears(47).toString());
        JsonNode flagged = ok(HttpMethod.POST, PATIENTS, clinic.owner(), within.toString(), 201);
        assertThat(flagged.get("possibleDuplicateOfId").asText()).isEqualTo(firstId);
        // 48 years: three apart, not flagged
        ObjectNode far = base("Ramesh Kulkarni", "M", mobile);
        far.put("dateOfBirth", today().minusYears(48).toString());
        JsonNode notFlagged = ok(HttpMethod.POST, PATIENTS, clinic.owner(), far.toString(), 201);
        assertThat(notFlagged.get("possibleDuplicateOfId") == null || notFlagged.get("possibleDuplicateOfId").isNull()).isTrue();
        // a different name with the same years is not flagged either
        ObjectNode other = base("Sita Verma", "F", mobile);
        other.put("dateOfBirth", today().minusYears(45).toString());
        JsonNode otherName = ok(HttpMethod.POST, PATIENTS, clinic.owner(), other.toString(), 201);
        assertThat(otherName.get("possibleDuplicateOfId") == null || otherName.get("possibleDuplicateOfId").isNull()).isTrue();
    }

    @Test
    void theDuplicateCheckUsesTheWorkedOutYearsOfTheOlderRecordNotItsStaleStoredAge() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        ObjectNode first = base("Meera Joshi", "F", mobile);
        first.put("dateOfBirth", today().minusYears(10).toString());
        String firstId = ok(HttpMethod.POST, PATIENTS, clinic.owner(), first.toString(), 201).get("id").asText();
        makeStoredAgeStale(clinic, firstId, 40);
        ObjectNode second = base("Meera Joshi", "F", mobile);
        second.put("age", 11);
        JsonNode flagged = ok(HttpMethod.POST, PATIENTS, clinic.owner(), second.toString(), 201);
        assertThat(flagged.get("possibleDuplicateOfId").asText()).as("10 and 11 are within two years; 40 and 11 are not").isEqualTo(firstId);
    }

    // ---------------- BR73-11: merge ----------------

    private String merge(Clinic clinic, String keepId, String mergeId, JsonNode[] answer) throws Exception {
        answer[0] = ok(HttpMethod.POST, PATIENTS + "/merge", clinic.as("FRONT_DESK"),
                "{\"keepId\":\"" + keepId + "\",\"mergeId\":\"" + mergeId + "\"}", 200);
        return answer[0].get("id").asText();
    }

    @Test
    void mergeCopiesTheDateWhenTheKeptRecordHasNoneRefreshesItsAgeAndAuditsASetEvent() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        ObjectNode keep = base("Merge Keep", "M", mobile);
        keep.put("age", 7);
        String keepId = ok(HttpMethod.POST, PATIENTS, clinic.owner(), keep.toString(), 201).get("id").asText();
        LocalDate dob = today().minusYears(9);
        ObjectNode other = base("Merge Other", "M", mobile);
        other.put("dateOfBirth", dob.toString());
        String mergeId = ok(HttpMethod.POST, PATIENTS, clinic.owner(), other.toString(), 201).get("id").asText();
        JsonNode[] answer = new JsonNode[1];
        merge(clinic, keepId, mergeId, answer);
        assertThat(answer[0].get("datesOfBirthDiffered").asBoolean()).isFalse();
        assertThat(answer[0].get("dateOfBirth").asText()).isEqualTo(dob.toString());
        assertAge(answer[0], 9, "YEARS", 9, true);
        assertThat(storedAge(clinic, keepId)).as("refreshed from the date, not the merged record's typed age").isEqualTo("9");
        assertThat(get(clinic, "OWNER", keepId).get("dateOfBirth").asText()).isEqualTo(dob.toString());
        List<List<String>> events = auditRows(clinic, CHANGED, keepId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get(1)).contains("SET");
    }

    @Test
    void mergeKeepsTheKeptDateAndSaysSoWhenTheTwoDatesDiffer() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        LocalDate keptDate = today().minusYears(9);
        ObjectNode keep = base("Differ Keep", "F", mobile);
        keep.put("dateOfBirth", keptDate.toString());
        String keepId = ok(HttpMethod.POST, PATIENTS, clinic.owner(), keep.toString(), 201).get("id").asText();
        ObjectNode other = base("Differ Other", "F", mobile);
        other.put("dateOfBirth", today().minusYears(11).toString());
        String mergeId = ok(HttpMethod.POST, PATIENTS, clinic.owner(), other.toString(), 201).get("id").asText();
        JsonNode[] answer = new JsonNode[1];
        merge(clinic, keepId, mergeId, answer);
        assertThat(answer[0].get("datesOfBirthDiffered").asBoolean()).isTrue();
        assertThat(answer[0].get("dateOfBirth").asText()).isEqualTo(keptDate.toString());
        assertAge(answer[0], 9, "YEARS", 9, true);
        assertThat(auditRows(clinic, CHANGED, keepId)).as("the date did not change").isEmpty();
    }

    @Test
    void mergeAnswerAlwaysHasDatesOfBirthDifferedAndOtherAnswersNeverDo() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        ObjectNode a = base("Shape Keep", "M", mobile);
        a.put("age", 30);
        JsonNode createdA = ok(HttpMethod.POST, PATIENTS, clinic.owner(), a.toString(), 201);
        ObjectNode b = base("Shape Other", "M", mobile);
        b.put("age", 31);
        JsonNode createdB = ok(HttpMethod.POST, PATIENTS, clinic.owner(), b.toString(), 201);
        assertThat(createdA.has("datesOfBirthDiffered")).isFalse();
        assertThat(get(clinic, "OWNER", createdA.get("id").asText()).has("datesOfBirthDiffered")).isFalse();
        JsonNode[] answer = new JsonNode[1];
        merge(clinic, createdA.get("id").asText(), createdB.get("id").asText(), answer);
        assertThat(answer[0].has("datesOfBirthDiffered")).as("no dates on either side: still false, never missing").isTrue();
        assertThat(answer[0].get("datesOfBirthDiffered").asBoolean()).isFalse();
        assertThat(dateIsNull(answer[0])).isTrue();
        assertThat(answer[0].get("age").asInt()).as("SAH-8 behaviour: the merged record's age").isEqualTo(31);
    }

    @Test
    void mergeKeepsTheKeptDateWhenOnlyTheKeptRecordHasOneAndWhenBothHaveTheSameDate() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        LocalDate dob = today().minusYears(6);
        ObjectNode keep = base("Only Keep", "M", mobile);
        keep.put("dateOfBirth", dob.toString());
        String keepId = ok(HttpMethod.POST, PATIENTS, clinic.owner(), keep.toString(), 201).get("id").asText();
        ObjectNode other = base("Only Other", "M", mobile);
        other.put("age", 6);
        String otherId = ok(HttpMethod.POST, PATIENTS, clinic.owner(), other.toString(), 201).get("id").asText();
        JsonNode[] answer = new JsonNode[1];
        merge(clinic, keepId, otherId, answer);
        assertThat(answer[0].get("datesOfBirthDiffered").asBoolean()).isFalse();
        assertThat(answer[0].get("dateOfBirth").asText()).isEqualTo(dob.toString());
        // same date on both sides
        ObjectNode k2 = base("Same Keep", "F", mobile);
        k2.put("dateOfBirth", dob.toString());
        String k2Id = ok(HttpMethod.POST, PATIENTS, clinic.owner(), k2.toString(), 201).get("id").asText();
        ObjectNode o2 = base("Same Other", "F", mobile);
        o2.put("dateOfBirth", dob.toString());
        String o2Id = ok(HttpMethod.POST, PATIENTS, clinic.owner(), o2.toString(), 201).get("id").asText();
        merge(clinic, k2Id, o2Id, answer);
        assertThat(answer[0].get("datesOfBirthDiffered").asBoolean()).isFalse();
        assertThat(auditRows(clinic, CHANGED, k2Id)).isEmpty();
    }

    @Test
    void mergeIsForOwnerAndFrontDeskOnlyAndNotAcrossClinics() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        String x = register(a, "Merge Role X", today().minusYears(3), null).get("id").asText();
        String y = register(a, "Merge Role Y", today().minusYears(3), null).get("id").asText();
        String z = register(b, "Merge Role Z", today().minusYears(3), null).get("id").asText();
        for (String role : NO_WRITE_ROLES) {
            problem(call(HttpMethod.POST, PATIENTS + "/merge", a.as(role), "{\"keepId\":\"" + x + "\",\"mergeId\":\"" + y + "\"}"), 403);
        }
        problem(call(HttpMethod.POST, PATIENTS + "/merge", a.owner(), "{\"keepId\":\"" + x + "\",\"mergeId\":\"" + z + "\"}"), 404);
    }

    // ---------------- BR73-12: who sees the date ----------------

    @Test
    void theDateIsPresentForTheFiveRolesAndNullWhenNotRecorded() throws Exception {
        Clinic clinic = newClinic();
        LocalDate dob = today().minusYears(11).minusDays(5);
        String dated = register(clinic, "Role Dated", dob, null).get("id").asText();
        String undated = register(clinic, "Role Undated", null, 11).get("id").asText();
        for (String role : DATE_ROLES) {
            assertThat(get(clinic, role, dated).get("dateOfBirth").asText()).as(role).isEqualTo(dob.toString());
            JsonNode none = get(clinic, role, undated);
            assertThat(none.has("dateOfBirth")).as(role + " has the property").isTrue();
            assertThat(none.get("dateOfBirth").isNull()).as(role + " null when not recorded").isTrue();
        }
    }

    /**
     * ADR-024 D3 and DEC-049 answer 2 (patient.yaml 0.2.1, closes DEC-013 item 14): BILLING and PHARMACIST read the
     * patient (200) and get the property ABSENT (not null) with {@code age} and {@code ageDetail}; COUNSELLOR may not
     * read at all (403, and no date in the body). The date is absent for these two roles on the detail, the list and
     * the family answers, for a dated patient and for an undated one.
     */
    @Test
    void billingAndPharmacistReadWithTheDateAbsentAndCounsellorGets403() throws Exception {
        Clinic clinic = newClinic();
        LocalDate dob = today().minusYears(11).minusDays(5);
        String mobile = uniqueMobile();
        ObjectNode datedBody = base("Hidden From Billing", "F", mobile);
        datedBody.put("dateOfBirth", dob.toString());
        String id = ok(HttpMethod.POST, PATIENTS, clinic.as("FRONT_DESK"), datedBody.toString(), 201).get("id").asText();
        ObjectNode undatedBody = base("Undated Sibling", "M", mobile);
        undatedBody.put("age", 11);
        String undatedId = ok(HttpMethod.POST, PATIENTS, clinic.as("FRONT_DESK"), undatedBody.toString(), 201).get("id").asText();
        for (String role : new String[] {"BILLING", "PHARMACIST"}) {
            JsonNode detail = get(clinic, role, id);
            assertThat(detail.toString()).as(role).doesNotContain(dob.toString());
            assertThat(detail.has("dateOfBirth")).as(role + ": absent, not null").isFalse();
            assertThat(detail.get("age").asInt()).isEqualTo(11);
            assertAge(detail, 11, "YEARS", 11, true);
            JsonNode undated = get(clinic, role, undatedId);
            assertThat(undated.has("dateOfBirth")).as(role + " undated: absent, not null").isFalse();
            assertAge(undated, 11, "YEARS", 11, false);
            JsonNode page = ok(HttpMethod.GET, PATIENTS, clinic.as(role), null, 200);
            assertThat(page.toString()).as(role + " list").doesNotContain(dob.toString()).doesNotContain("dateOfBirth");
            page.get("items").forEach(i -> assertThat(i.has("dateOfBirth")).isFalse());
            JsonNode family = ok(HttpMethod.GET, PATIENTS + "/" + id + "/family", clinic.as(role), null, 200);
            assertThat(family.size()).isEqualTo(1);
            assertThat(family.toString()).as(role + " family").doesNotContain(dob.toString()).doesNotContain("dateOfBirth");
            assertThat(family.get(0).get("ageDetail")).isNotNull();
        }
        MvcResult counsellor = call(HttpMethod.GET, PATIENTS + "/" + id, clinic.as("COUNSELLOR"), null).andReturn();
        assertThat(counsellor.getResponse().getStatus()).as("COUNSELLOR may not read").isEqualTo(403);
        assertThat(counsellor.getResponse().getContentAsString()).doesNotContain(dob.toString()).doesNotContain("dateOfBirth");
        problem(call(HttpMethod.GET, PATIENTS, clinic.as("COUNSELLOR"), null), 403);
        problem(call(HttpMethod.GET, PATIENTS + "/" + id + "/family", clinic.as("COUNSELLOR"), null), 403);
    }

    @Test
    void aCustomRoleOutsideTheAllowListNeverReceivesTheDate() throws Exception {
        Clinic clinic = newClinic();
        LocalDate dob = today().minusYears(11).minusDays(5);
        String id = register(clinic, "Hidden From Others", dob, null).get("id").asText();
        MvcResult r = call(HttpMethod.GET, PATIENTS + "/" + id, clinic.as("RECEPTION_TRAINEE"), null).andReturn();
        assertThat(r.getResponse().getStatus()).as("an unknown role is default deny").isIn(401, 403);
        assertThat(r.getResponse().getContentAsString()).doesNotContain(dob.toString());
    }

    // ---------------- other modules: effective years in the queue and on bills ----------------

    @Test
    void theQueueCardAndTheBillShowTheEffectiveCompletedYearsAndNoDate() throws Exception {
        Clinic clinic = newClinic();
        LocalDate dob = today().minusMonths(30); // 2 years and 6 months
        String patientId = register(clinic, "Queue Child", dob, null).get("id").asText();
        makeStoredAgeStale(clinic, patientId, 77);
        JsonNode visit = ok(HttpMethod.POST, VISITS, clinic.owner(),
                "{\"patientId\":\"" + patientId + "\",\"doctorMembershipId\":\"" + clinic.doctorMembershipId() + "\"}", 201);
        assertThat(visit.get("age").asInt()).as("token answer").isEqualTo(2);
        String visitId = visit.get("id").asText();
        JsonNode onBoard = null;
        for (JsonNode v : ok(HttpMethod.GET, VISITS + "/today", clinic.owner(), null, 200)) {
            if (visitId.equals(v.get("id").asText())) {
                onBoard = v;
            }
        }
        assertThat(onBoard).as("the visit is on the board").isNotNull();
        assertThat(onBoard.get("age").asInt()).as("queue card").isEqualTo(2);
        assertThat(onBoard.toString()).doesNotContain(dob.toString());
        String stage = visit.get("stage").asText();
        while (!"BILLING".equals(stage)) {
            String next = "VITALS".equals(stage) ? "WITH_DOCTOR" : "BILLING";
            ok(HttpMethod.PATCH, VISITS + "/" + visitId + "/stage", clinic.owner(), "{\"stage\":\"" + next + "\"}", 200);
            stage = next;
        }
        for (String role : new String[] {"OWNER", "FRONT_DESK", "BILLING", "PHARMACIST"}) {
            JsonNode bill = ok(HttpMethod.GET, "/api/v1/billing/bills/" + visitId, clinic.as(role), null, 200);
            assertThat(bill.get("age").asInt()).as("bill for " + role).isEqualTo(2);
            assertThat(bill.toString()).as("bill for " + role).doesNotContain(dob.toString()).doesNotContain("dateOfBirth");
        }
    }

    // ---------------- audit: no value, no name, no mobile ----------------

    @Test
    void theRegistrationAuditHoldsNoNameMobileAreaAllergyOrDateAndSaysWhetherADateWasRecorded() throws Exception {
        Clinic clinic = newClinic();
        LocalDate dob = today().minusYears(6).minusDays(40);
        String mobile = uniqueMobile();
        ObjectNode body = base("Zxqaudit Patient", "F", mobile);
        body.put("dateOfBirth", dob.toString());
        body.put("area", "Zxqauditarea");
        body.put("allergy", "Zxqauditallergy");
        String id = ok(HttpMethod.POST, PATIENTS, clinic.owner(), body.toString(), 201).get("id").asText();
        List<List<String>> rows = auditRows(clinic, "PATIENT_REGISTERED", id);
        assertThat(rows).hasSize(1);
        JsonNode after = objectMapper.readTree(rows.get(0).get(1));
        assertThat(after.get("dateOfBirthRecorded").asBoolean()).isTrue();
        assertThat(after.has("age")).as("a dated patient's snapshot carries no age").isFalse();
        for (String key : new String[] {"name", "mobile", "area", "allergy", "chronic", "dateOfBirth"}) {
            assertThat(after.has(key)).as("no key " + key).isFalse();
        }
        assertThat(after.get("changedFields").toString()).contains("dateOfBirth");
        String all = rows.get(0).get(0) + rows.get(0).get(1);
        for (String leak : new String[] {"Zxqaudit", "Zxqauditarea", "Zxqauditallergy", mobile, mobile.substring(6), dob.toString(),
                String.valueOf(dob.getYear()) + "-" + String.format("%02d", dob.getMonthValue())}) {
            assertThat(all).as("audit holds " + leak).doesNotContain(leak);
        }
        // an undated registration still carries the typed age and says no date was recorded
        ObjectNode undated = base("Zxqaudit Undated", "M", uniqueMobile());
        undated.put("age", 33);
        String undatedId = ok(HttpMethod.POST, PATIENTS, clinic.owner(), undated.toString(), 201).get("id").asText();
        JsonNode undatedAfter = objectMapper.readTree(auditRows(clinic, "PATIENT_REGISTERED", undatedId).get(0).get(1));
        assertThat(undatedAfter.get("dateOfBirthRecorded").asBoolean()).isFalse();
        assertThat(undatedAfter.get("age").asInt()).isEqualTo(33);
        assertThat(undatedAfter.has("name")).isFalse();
        assertThat(undatedAfter.has("mobile")).isFalse();
    }

    @Test
    void theUpdateAndMergeAuditHoldNoNameMobileOrAreaValueAndNoDate() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        ObjectNode keep = base("Zxqkeep Person", "M", mobile);
        keep.put("age", 40);
        keep.put("area", "Zxqkeeparea");
        String keepId = ok(HttpMethod.POST, PATIENTS, clinic.owner(), keep.toString(), 201).get("id").asText();
        patch(clinic, keepId, "{\"name\":\"Zxqrenamed Person\",\"area\":\"Zxqnewarea\",\"allergy\":\"Zxqallergy\"}");
        List<List<String>> updated = auditRows(clinic, "PATIENT_UPDATED", keepId);
        assertThat(updated).hasSize(1);
        JsonNode before = objectMapper.readTree(updated.get(0).get(0));
        JsonNode after = objectMapper.readTree(updated.get(0).get(1));
        for (JsonNode snapshot : new JsonNode[] {before, after}) {
            for (String key : new String[] {"name", "mobile", "area", "allergy", "dateOfBirth"}) {
                assertThat(snapshot.has(key)).as("no key " + key).isFalse();
            }
        }
        assertThat(after.get("changedFields").toString()).contains("name").contains("area").contains("allergy");
        assertThat(updated.get(0).get(0) + updated.get(0).get(1)).doesNotContain("Zxq").doesNotContain(mobile);
        // a merge, with a date moving across
        LocalDate dob = today().minusYears(40).minusDays(3);
        ObjectNode other = base("Zxqother Person", "M", mobile);
        other.put("dateOfBirth", dob.toString());
        String otherId = ok(HttpMethod.POST, PATIENTS, clinic.owner(), other.toString(), 201).get("id").asText();
        ok(HttpMethod.POST, PATIENTS + "/merge", clinic.owner(), "{\"keepId\":\"" + keepId + "\",\"mergeId\":\"" + otherId + "\"}", 200);
        for (String id : new String[] {keepId, otherId}) {
            for (List<String> row : auditRows(clinic, "PATIENT_MERGED", id)) {
                assertThat(row.get(0) + row.get(1)).doesNotContain("Zxq").doesNotContain(mobile).doesNotContain(dob.toString());
            }
        }
    }

    @Test
    void theDateEventHoldsTheFieldNameAndTheKindOfChangeNeverTheDateOrAnAge() throws Exception {
        Clinic clinic = newClinic();
        LocalDate first = today().minusYears(7).minusDays(11);
        LocalDate second = today().minusYears(9).minusDays(2);
        String id = register(clinic, "Event Content", null, 7).get("id").asText();
        patch(clinic, id, "{\"dateOfBirth\":\"" + first + "\"}");
        patch(clinic, id, "{\"dateOfBirth\":\"" + second + "\"}");
        patch(clinic, id, "{\"dateOfBirth\":null}");
        List<List<String>> events = auditRows(clinic, CHANGED, id);
        assertThat(events).hasSize(3);
        String[] kinds = {"SET", "CHANGED", "CLEARED"};
        boolean[] recordedAfter = {true, true, false};
        for (int i = 0; i < 3; i++) {
            JsonNode before = objectMapper.readTree(events.get(i).get(0));
            JsonNode after = objectMapper.readTree(events.get(i).get(1));
            assertThat(after.get("field").asText()).isEqualTo("dateOfBirth");
            assertThat(after.get("change").asText()).isEqualTo(kinds[i]);
            assertThat(after.get("dateOfBirthRecorded").asBoolean()).isEqualTo(recordedAfter[i]);
            assertThat(before.get("dateOfBirthRecorded").asBoolean()).isEqualTo(i > 0);
            assertThat(after.has("age")).as("an age with a known change day narrows the date").isFalse();
            assertThat(before.has("age")).isFalse();
            assertThat(events.get(i).get(0) + events.get(i).get(1)).doesNotContain(first.toString()).doesNotContain(second.toString())
                    .doesNotContain(String.valueOf(first.getYear())).doesNotContain(String.valueOf(second.getYear()));
            assertThat(events.get(i).get(2)).isEqualTo(clinic.ownerUserId().toString());
        }
    }

    @Test
    void auditEventsOfOneClinicAreInvisibleToAnotherClinic() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        String id = register(a, "Audit Isolation", null, 7).get("id").asText();
        patch(a, id, "{\"dateOfBirth\":\"" + today().minusYears(7) + "\"}");
        assertThat(changedEvents(a, id)).isEqualTo(1);
        assertThat(changedEvents(b, id)).as("tenant B sees none of tenant A's events").isZero();
    }

    // ---------------- privacy: never in a log, never in a refusal ----------------

    private static String logText(List<ILoggingEvent> events) {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : events) {
            if (event.getLoggerName().startsWith("org.springframework.test.")) {
                continue; // MockMvc's own debug lines print the MockHttpServletRequest, which exists only in a test
            }
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(event.getThrowableProxy().getMessage()).append('\n');
            }
            text.append(event.getMDCPropertyMap()).append('\n');
        }
        return text.toString();
    }

    @Test
    void noLogLineHoldsADateOfBirthOrAnUnreadableValueAcrossEveryKindOfCall() throws Exception {
        Clinic clinic = newClinic();
        LocalDate dob = LocalDate.of(1991, 7, 23);
        LocalDate second = LocalDate.of(2019, 11, 2);
        StringBuilder answers = new StringBuilder();
        String logs;
        try (LogCapture capture = new LogCapture()) {
            JsonNode created = register(clinic, "Zxqlog Patient", dob, null);
            answers.append(created);
            String id = created.get("id").asText();
            answers.append(patch(clinic, id, "{\"dateOfBirth\":\"" + second + "\"}"));
            answers.append(patch(clinic, id, "{\"dateOfBirth\":null}"));
            answers.append(get(clinic, "OWNER", id));
            answers.append(ok(HttpMethod.GET, PATIENTS + "?filter=kids", clinic.owner(), null, 200));
            answers.append(ok(HttpMethod.GET, PATIENTS + "/" + id + "/family", clinic.owner(), null, 200));
            // refusals: the unreadable text and the dates are in the request only
            ObjectNode unreadable = registration("Zxqlog Refused", null, null);
            unreadable.put("dateOfBirth", "zxqnotadate-1991-07-23");
            answers.append(refuseRegister(clinic, unreadable));
            answers.append(refuseRegister(clinic, registration("Zxqlog Future", today().plusDays(30), null)));
            answers.append(refuseRegister(clinic, registration("Zxqlog Old", LocalDate.of(1850, 1, 1), null)));
            answers.append(refuseRegister(clinic, registration("Zxqlog Mismatch", dob, 20)));
            answers.append(refusePatch(clinic, id, "{\"dateOfBirth\":\"2026-02-31\"}"));
            // two records merged, one of them dated
            JsonNode other = register(clinic, "Zxqlog Other", second, null);
            answers.append(ok(HttpMethod.POST, PATIENTS + "/merge", clinic.owner(),
                    "{\"keepId\":\"" + id + "\",\"mergeId\":\"" + other.get("id").asText() + "\"}", 200));
            logs = logText(capture.events());
        }
        assertThat(logs).as("the capture saw the patient requests").contains("/api/v1/patient/patients");
        for (String secret : new String[] {"1991-07-23", "2019-11-02", "zxqnotadate", "2026-02-31", "Zxqlog", "1850-01-01"}) {
            assertThat(logs).as("a log line holds " + secret).doesNotContain(secret);
        }
        String refusals = answers.toString();
        // the refusal bodies are among the answers: none repeats what was typed
        assertThat(refusals).doesNotContain("zxqnotadate");
        assertThat(allAuditText(clinic)).doesNotContain("1991-07-23").doesNotContain("2019-11-02").doesNotContain("Zxqlog");
    }

    // ---------------- consent: the date adds no new question ----------------

    @Test
    void aRecordsConsentAnswerWithADateIsRecordedAndTheDateAddsNoConsentQuestion() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode body = registration("Consent With Date", today().minusYears(3), null);
        ArrayNode consents = body.putArray("consents");
        consents.addObject().put("kind", "RECORDS").put("given", true);
        JsonNode created = ok(HttpMethod.POST, PATIENTS, clinic.owner(), body.toString(), 201);
        JsonNode state = ok(HttpMethod.GET, PATIENTS + "/" + created.get("id").asText() + "/consents", clinic.owner(), null, 200);
        assertThat(state.get("records").get("given").asBoolean()).isTrue();
        assertThat(state.get("promotional") == null || state.get("promotional").isNull()).as("no extra consent was asked").isTrue();
        assertThat(state.toString()).doesNotContainIgnoringCase("birth");
    }

    @Test
    void aFamilyMemberRegisteredWithADateJoinsTheSameMobile() throws Exception {
        Clinic clinic = newClinic();
        String mobile = uniqueMobile();
        ObjectNode parent = base("Family Parent", "F", mobile);
        parent.put("age", 33);
        String parentId = ok(HttpMethod.POST, PATIENTS, clinic.as("FRONT_DESK"), parent.toString(), 201).get("id").asText();
        ObjectNode child = base("Family Child", "M", mobile);
        child.put("dateOfBirth", today().minusYears(4).toString());
        String childId = ok(HttpMethod.POST, PATIENTS, clinic.as("FRONT_DESK"), child.toString(), 201).get("id").asText();
        JsonNode family = ok(HttpMethod.GET, PATIENTS + "/" + parentId + "/family", clinic.owner(), null, 200);
        assertThat(family.findValuesAsText("id")).containsExactly(childId);
    }

    @Test
    void theNewIdsAreNotMixedUpAcrossTwoClinicsWithTheSameDateAndMobile() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        String mobile = uniqueMobile();
        LocalDate dob = today().minusYears(5);
        ObjectNode bodyA = base("Same Child", "M", mobile);
        bodyA.put("dateOfBirth", dob.toString());
        JsonNode inA = ok(HttpMethod.POST, PATIENTS, a.owner(), bodyA.toString(), 201);
        JsonNode inB = ok(HttpMethod.POST, PATIENTS, b.owner(), bodyA.toString(), 201);
        assertThat(inB.get("possibleDuplicateOfId") == null || inB.get("possibleDuplicateOfId").isNull()).isTrue();
        assertThat(UUID.fromString(inA.get("id").asText())).isNotEqualTo(UUID.fromString(inB.get("id").asText()));
        assertThat(ok(HttpMethod.GET, PATIENTS + "/" + inB.get("id").asText() + "/family", b.owner(), null, 200).size()).isZero();
        problem(call(HttpMethod.GET, PATIENTS + "/" + inA.get("id").asText() + "/family", b.owner(), null), 404);
    }
}
