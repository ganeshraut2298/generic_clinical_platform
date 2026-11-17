package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * DEC-054 / SAH-75 review fix: sending a chemotherapy order sets the regimen of the linked cycle once and bumps the course
 * revision once (not twice, and not on a retry). Test plan: docs/test-plans/DEC-054-backend-gates-2026-11-15.md.
 */
@OnTestDatabase
class Sah75ChemoSendRevisionIT extends EmrSpecialtySupport {

    @Test
    void sendingBumpsTheCourseRevisionExactlyOnceSetsTheRegimenAndARetryBumpsNothing() throws Exception {
        Rec rec = oncoAssessed(newOnco());
        JsonNode course = newCourse(rec, "CHEMO", "[" + sittingJson("review_opd", 100000L, null) + "]");
        String courseUrl = COURSES + "/" + course.get("id").asText();
        String sittingId = course.get("sittings").get(0).get("id").asText();
        JsonNode linked = post(courseUrl + "/sittings/" + sittingId + "/link-visit", rec.token(),
                "{\"revision\":" + course.get("revision").asLong() + ",\"visitId\":\"" + rec.visitId() + "\"}", 200);
        assertThat(linked.get("sittings").get(0).get("regimenKey").isNull()).isTrue();

        Staff nurse = addNonDoctor(rec.clinic(), "NURSE");
        Sheet sheet = new Sheet(rec, saveOrder(rec, passingOrder(0)));
        assertThat(row(rec.clinic(), "select course_id::text from opd.chemo_order where id = ?::uuid", orderId(sheet))).containsExactly(course.get("id").asText());
        Sheet named = nameChecker(sheet, nurse);
        Sheet confirmed = confirmBySignIn(named, nurse);
        long before = get(courseUrl, rec.token()).get("revision").asLong();

        JsonNode sent = post(orderUrl(rec) + "/send", rec.token(), sendBody(confirmed), 200);
        assertThat(sent.get("status").asText()).isEqualTo("SENT");
        JsonNode after = get(courseUrl, rec.token());
        assertThat(after.get("revision").asLong()).as("one bump for the regimen, not two").isEqualTo(before + 1);
        assertThat(after.get("sittings").get(0).get("regimenKey").asText()).isEqualTo("PACLI_3W");

        post(orderUrl(rec) + "/send", rec.token(), sendBody(new Sheet(rec, sent)), 200);
        assertThat(get(courseUrl, rec.token()).get("revision").asLong()).as("a retry bumps nothing").isEqualTo(before + 1);
    }

    @Test
    void aCycleThatAlreadyHasARegimenIsLeftAloneAndTheRevisionDoesNotMove() throws Exception {
        Rec rec = oncoAssessed(newOnco());
        JsonNode course = newCourse(rec, "CHEMO", "[" + sittingJson("review_opd", 100000L, null) + "]");
        String courseUrl = COURSES + "/" + course.get("id").asText();
        String sittingId = course.get("sittings").get(0).get("id").asText();
        post(courseUrl + "/sittings/" + sittingId + "/link-visit", rec.token(),
                "{\"revision\":" + course.get("revision").asLong() + ",\"visitId\":\"" + rec.visitId() + "\"}", 200);
        commit(rec.clinic().tenantId(), "update opd.treatment_course_sitting set regimen_key = 'PACLI_WEEKLY' where id = ?::uuid", sittingId);
        Staff nurse = addNonDoctor(rec.clinic(), "NURSE");
        Sheet confirmed = confirmBySignIn(nameChecker(new Sheet(rec, saveOrder(rec, passingOrder(0))), nurse), nurse);
        long before = get(courseUrl, rec.token()).get("revision").asLong();
        assertThat(post(orderUrl(rec) + "/send", rec.token(), sendBody(confirmed), 200).get("status").asText()).isEqualTo("SENT");
        JsonNode after = get(courseUrl, rec.token());
        assertThat(after.get("revision").asLong()).isEqualTo(before);
        assertThat(after.get("sittings").get(0).get("regimenKey").asText()).isEqualTo("PACLI_WEEKLY");
    }
}
