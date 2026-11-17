package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * DEC-054 / SAH-75 review fixes on {@code PUT /api/v1/opd/emr/records/{recordId}/cancer-journey}: a NUL or tab in a one-line
 * text is 400 (never 500), and a save that changes nothing returns the latest version and writes no row and no audit.
 * Test plan: docs/test-plans/DEC-054-backend-gates-2026-11-15.md.
 */
@OnTestDatabase
class Sah75CancerJourneyFixesIT extends EmrSpecialtySupport {

    private static String url(Rec rec) {
        return RECORDS + "/" + rec.id() + "/cancer-journey";
    }

    private long rows(Rec rec) throws Exception {
        return count(rec.clinic(), "select count(*) from opd.emr_cancer_journey");
    }

    @Test
    void aNulOrTabInTnmTextIs400WithFieldErrorsNotAnInternalError() throws Exception {
        Rec rec = newOnco();
        for (String bad : new String[] {"T2\\u0000N1", "T2\\tN1", "T2\\nN1", "T2\\u001fN1"}) {
            JsonNode problem = problem(call(HttpMethod.PUT, url(rec), rec.token(), "{\"expectedVersion\":0,\"tnmText\":\"" + bad + "\"}"), 400);
            assertThat(problem.get("type").asText()).as(bad).isEqualTo(PROBLEM_BASE + "validation");
            assertThat(problem.get("fieldErrors").get(0).get("field").asText()).isEqualTo("tnmText");
        }
        assertThat(rows(rec)).as("nothing stored").isZero();
        assertThat(auditCount(rec.clinic(), "ONC_JOURNEY_UPDATED")).isZero();
        // a plain text still saves
        JsonNode saved = put(url(rec), rec.token(), "{\"expectedVersion\":0,\"tnmText\":\"T2 N1 M0\"}", 200);
        assertThat(saved.get("version").asInt()).isOne();
    }

    @Test
    void aSaveThatChangesNothingReturnsTheLatestVersionAndWritesNoRowAndNoAudit() throws Exception {
        Rec rec = newOnco();
        JsonNode first = put(url(rec), rec.token(), "{\"expectedVersion\":0,\"tnmText\":\"T2 N1 M0\",\"ki67Pct\":30}", 200);
        assertThat(first.get("version").asInt()).isOne();
        assertThat(rows(rec)).isOne();
        assertThat(auditCount(rec.clinic(), "ONC_JOURNEY_UPDATED")).isOne();
        // the same values again (and a blank-to-same, and an omitted field): no change
        JsonNode same = put(url(rec), rec.token(), "{\"expectedVersion\":1,\"tnmText\":\"T2 N1 M0\",\"ki67Pct\":30}", 200);
        assertThat(same.get("version").asInt()).as("the latest version, not a new one").isOne();
        assertThat(same.get("tnmText").asText()).isEqualTo("T2 N1 M0");
        JsonNode omitted = put(url(rec), rec.token(), "{\"expectedVersion\":1}", 200);
        assertThat(omitted.get("version").asInt()).isOne();
        assertThat(rows(rec)).as("no new row").isOne();
        assertThat(auditCount(rec.clinic(), "ONC_JOURNEY_UPDATED")).as("no new audit").isOne();
        // a real change is version 2
        JsonNode changed = put(url(rec), rec.token(), "{\"expectedVersion\":1,\"ki67Pct\":40}", 200);
        assertThat(changed.get("version").asInt()).isEqualTo(2);
        assertThat(rows(rec)).isEqualTo(2);
        assertThat(auditCount(rec.clinic(), "ONC_JOURNEY_UPDATED")).isEqualTo(2);
    }

    @Test
    void aStaleVersionIs409JourneyChangedAndAnotherClinicsDoctorGets404() throws Exception {
        Rec rec = newOnco();
        put(url(rec), rec.token(), "{\"expectedVersion\":0,\"ki67Pct\":10}", 200);
        problemOf(HttpMethod.PUT, url(rec), rec.token(), "{\"expectedVersion\":0,\"ki67Pct\":11}", 409, "journey-changed");
        Rec other = newOnco();
        assertThat(call(HttpMethod.PUT, url(rec), other.token(), "{\"expectedVersion\":1,\"ki67Pct\":12}").andReturn().getResponse().getStatus()).isEqualTo(404);
    }
}
