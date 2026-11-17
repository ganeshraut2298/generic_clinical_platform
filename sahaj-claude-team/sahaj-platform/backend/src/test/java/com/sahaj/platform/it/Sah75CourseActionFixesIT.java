package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * DEC-054 / SAH-75 review fixes on the course actions ({@code POST /api/v1/opd/emr/courses/{id}/sittings/{id}/...}) on the
 * real database: rebook and reschedule take a date up to today plus 2 years and refuse one day more with 400 on
 * {@code plannedDate}; mark done with a null or missing body is 400 with a problem-details body (never 500).
 * Test plan: docs/test-plans/DEC-054-backend-gates-2026-11-15.md.
 */
@OnTestDatabase
class Sah75CourseActionFixesIT extends EmrPhase3Support {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static String sitting(String date) {
        return "{\"addOptionKey\":\"opd_sitting\",\"plannedDate\":\"" + date + "\",\"chargePaise\":100000}";
    }

    private JsonNode courseOf(Rec rec, LocalDate date) throws Exception {
        return okKeyed(HttpMethod.POST, COURSES, rec.token(), "{\"fromRecordId\":\"" + rec.id() + "\",\"kind\":\"DENTAL\",\"sittings\":["
                + sitting(date.toString()) + "]}", key(), 201);
    }

    private static String path(JsonNode course, String action) {
        return COURSES + "/" + course.get("id").asText() + "/sittings/" + course.get("sittings").get(0).get("id").asText() + "/" + action;
    }

    private static String dateBody(JsonNode course, LocalDate date) {
        return "{\"revision\":" + course.get("revision").asLong() + ",\"plannedDate\":\"" + date + "\"}";
    }

    private static String revBody(JsonNode course) {
        return "{\"revision\":" + course.get("revision").asLong() + "}";
    }

    private JsonNode fresh(Rec rec, JsonNode course) throws Exception {
        return get(COURSES + "/" + course.get("id").asText(), rec.token());
    }

    private void assertPlannedDateRefused(JsonNode problem) {
        assertThat(problem.get("type").asText()).isEqualTo(PROBLEM_BASE + "validation");
        assertThat(problem.get("fieldErrors").get(0).get("field").asText()).isEqualTo("plannedDate");
    }

    @Test
    void rescheduleAcceptsExactlyTwoYearsAheadAndRefusesOneDayMoreOrAPastDateWith400OnPlannedDate() throws Exception {
        Rec rec = newRec();
        LocalDate today = LocalDate.now(IST);
        JsonNode course = courseOf(rec, today.plusDays(3));
        String url = path(course, "reschedule");
        assertPlannedDateRefused(problem(call(HttpMethod.POST, url, rec.token(), dateBody(course, today.plusYears(2).plusDays(1))), 400));
        assertPlannedDateRefused(problem(call(HttpMethod.POST, url, rec.token(), dateBody(course, today.minusDays(1))), 400));
        assertThat(fresh(rec, course).get("sittings").get(0).get("plannedDate").asText()).as("a refusal changes nothing")
                .isEqualTo(today.plusDays(3).toString());
        assertThat(auditCount(rec.clinic(), "COURSE_SITTING_RESCHEDULED")).isZero();
        JsonNode moved = post(url, rec.token(), dateBody(course, today.plusYears(2)), 200);
        assertThat(moved.get("sittings").get(0).get("plannedDate").asText()).isEqualTo(today.plusYears(2).toString());
        assertThat(moved.get("sittings").get(0).get("status").asText()).isEqualTo("PLANNED");
        assertThat(auditCount(rec.clinic(), "COURSE_SITTING_RESCHEDULED")).isOne();
    }

    @Test
    void rebookAcceptsExactlyTwoYearsAheadAndRefusesOneDayMoreWith400OnPlannedDate() throws Exception {
        Rec rec = newRec();
        LocalDate today = LocalDate.now(IST);
        JsonNode course = courseOf(rec, today);
        assertThat(course.get("sittings").get(0).get("status").asText()).isEqualTo("TODAY");
        JsonNode missed = post(path(course, "missed"), rec.token(), revBody(course), 200);
        assertThat(missed.get("sittings").get(0).get("status").asText()).isEqualTo("MISSED");
        String url = path(missed, "rebook");
        assertPlannedDateRefused(problem(call(HttpMethod.POST, url, rec.token(), dateBody(missed, today.plusYears(2).plusDays(1))), 400));
        assertThat(fresh(rec, course).get("sittings").get(0).get("status").asText()).as("still missed after the refusal").isEqualTo("MISSED");
        assertThat(auditCount(rec.clinic(), "COURSE_SITTING_REBOOKED")).isZero();
        JsonNode rebooked = post(url, rec.token(), dateBody(missed, today.plusYears(2)), 200);
        assertThat(rebooked.get("sittings").get(0).get("status").asText()).isEqualTo("PLANNED");
        assertThat(rebooked.get("sittings").get(0).get("plannedDate").asText()).isEqualTo(today.plusYears(2).toString());
        assertThat(auditCount(rec.clinic(), "COURSE_SITTING_REBOOKED")).isOne();
    }

    @Test
    void aMissingOrNullDateIsAlso400OnPlannedDate() throws Exception {
        Rec rec = newRec();
        JsonNode course = courseOf(rec, LocalDate.now(IST).plusDays(3));
        String url = path(course, "reschedule");
        assertPlannedDateRefused(problem(call(HttpMethod.POST, url, rec.token(), "{\"revision\":" + course.get("revision").asLong() + "}"), 400));
        assertPlannedDateRefused(problem(call(HttpMethod.POST, url, rec.token(),
                "{\"revision\":" + course.get("revision").asLong() + ",\"plannedDate\":null}"), 400));
    }

    @Test
    void markDoneWithANullOrEmptyBodyIs400WithAProblemDetailsBodyNot500() throws Exception {
        Rec rec = newRec();
        JsonNode course = courseOf(rec, LocalDate.now(IST));
        String url = path(course, "mark-done");
        for (String body : new String[] {"null", "", null}) {
            JsonNode problem = problem(call(HttpMethod.POST, url, rec.token(), body), 400);
            assertThat(problem.get("type").asText()).as("body " + body).startsWith(PROBLEM_BASE);
            assertThat(problem.get("status").asInt()).isEqualTo(400);
        }
        assertThat(fresh(rec, course).get("sittings").get(0).get("status").asText()).as("nothing was marked").isEqualTo("TODAY");
        assertThat(auditCount(rec.clinic(), "COURSE_SITTING_MARKED_DONE")).isZero();
        JsonNode done = post(url, rec.token(), revBody(course), 200);
        assertThat(done.get("sittings").get(0).get("status").asText()).isEqualTo("DONE");
    }

    @Test
    void anotherClinicCannotRebookRescheduleOrMarkDoneThisSitting() throws Exception {
        Rec rec = newRec();
        Rec other = newRec();
        LocalDate today = LocalDate.now(IST);
        JsonNode course = courseOf(rec, today);
        for (String action : new String[] {"reschedule", "rebook"}) {
            assertThat(call(HttpMethod.POST, path(course, action), other.token(), dateBody(course, today.plusDays(5))).andReturn().getResponse().getStatus())
                    .as(action).isEqualTo(404);
        }
        assertThat(call(HttpMethod.POST, path(course, "mark-done"), other.token(), revBody(course)).andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }
}
