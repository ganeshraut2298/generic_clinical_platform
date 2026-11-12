package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-44-3 on the real database: how the EMR opens in a clinic (section panel, right panel first, default part).
 * Defaults until the first save, owner-only saves, the version guard, the closed body (the default part cannot be
 * sent), the audit entry per changed setting, and two clinics. Test plan: docs/test-plans/SAH-43-44.md.
 */
@OnTestDatabase
class EmrSettingsIT extends EmrSupport {

    private List<String> stored(Clinic clinic) throws Exception {
        return row(clinic, "select section_panel, right_panel_first, default_part, version::text, created_by::text "
                + "from opd.emr_settings");
    }

    // ---------------- read ----------------

    @Test
    void aClinicWithNoSavedSettingsGetsTheDefaultsAndEveryRoleCanRead() throws Exception {
        Clinic clinic = newClinic();
        for (String role : ALL_ROLES) {
            JsonNode settings = get(SETTINGS, clinic.as(role));
            assertThat(settings.get("sectionPanel").asText()).isEqualTo("COLLAPSED");
            assertThat(settings.get("rightPanelFirst").asText()).isEqualTo("CASE_PAPER");
            assertThat(settings.get("defaultPart").asText()).isEqualTo("ASSESSMENT");
            assertThat(settings.get("version").asLong()).isZero();
            assertThat(settings.get("canEdit").asBoolean()).as(role).isEqualTo("OWNER".equals(role));
        }
        assertThat(stored(clinic)).as("reading never creates the row").isEmpty();
        // real people too, and a token that only claims OWNER is not enough for canEdit
        Staff nurse = addNonDoctor(clinic, "NURSE");
        assertThat(get(SETTINGS, nurse.token(clinic)).get("canEdit").asBoolean()).isFalse();
        assertThat(get(SETTINGS, nurse.tokenClaiming(clinic, "OWNER")).get("canEdit").asBoolean()).isFalse();
        problemOf(HttpMethod.GET, SETTINGS, anonymous(), null, 401, "unauthorized");
    }

    // ---------------- save ----------------

    @Test
    void theOwnerSavesTheSectionPanelAndTheRightPanelAndGetsThemBackOnTheNextRead() throws Exception {
        Clinic clinic = newClinic();
        JsonNode saved = put(SETTINGS, clinic.owner(), settingsBody("OPEN", "PAST_VISITS", 0), 200);
        assertThat(saved.get("sectionPanel").asText()).isEqualTo("OPEN");
        assertThat(saved.get("rightPanelFirst").asText()).isEqualTo("PAST_VISITS");
        assertThat(saved.get("defaultPart").asText()).isEqualTo("ASSESSMENT");
        assertThat(saved.get("version").asLong()).as("the first save stores version 1").isEqualTo(1);
        assertThat(saved.get("canEdit").asBoolean()).isTrue();
        JsonNode read = get(SETTINGS, clinic.as("FRONT_DESK"));
        assertThat(read.get("sectionPanel").asText()).isEqualTo("OPEN");
        assertThat(read.get("rightPanelFirst").asText()).isEqualTo("PAST_VISITS");
        assertThat(read.get("version").asLong()).isEqualTo(1);
        assertThat(stored(clinic)).containsExactly("OPEN", "PAST_VISITS", "ASSESSMENT", "1", clinic.ownerUserId().toString());

        JsonNode second = put(SETTINGS, clinic.owner(), settingsBody("COLLAPSED", "CASE_PAPER", 1), 200);
        assertThat(second.get("sectionPanel").asText()).isEqualTo("COLLAPSED");
        assertThat(second.get("rightPanelFirst").asText()).isEqualTo("CASE_PAPER");
        assertThat(second.get("version").asLong()).isEqualTo(2);
        assertThat(count(clinic, "select count(*) from opd.emr_settings")).as("one row per clinic").isEqualTo(1);
    }

    @Test
    void everyChangeWritesOneAuditEntryWithTheBeforeAndAfterOfOnlyTheSettingsThatChanged() throws Exception {
        Clinic clinic = newClinic();
        put(SETTINGS, clinic.owner(), settingsBody("OPEN", "CASE_PAPER", 0), 200);
        put(SETTINGS, clinic.owner(), settingsBody("OPEN", "PAST_VISITS", 1), 200);
        put(SETTINGS, clinic.owner(), settingsBody("COLLAPSED", "CASE_PAPER", 2), 200);
        List<List<String>> audit = auditRows(clinic, "EMR_SETTINGS_UPDATED");
        assertThat(audit).hasSize(3);
        JsonNode b1 = objectMapper.readTree(audit.get(0).get(1));
        JsonNode a1 = objectMapper.readTree(audit.get(0).get(2));
        assertThat(audit.get(0).get(0)).isEqualTo("EMR_SETTINGS");
        assertThat(b1.size()).isEqualTo(1);
        assertThat(b1.get("sectionPanel").asText()).isEqualTo("COLLAPSED");
        assertThat(a1.size()).isEqualTo(1);
        assertThat(a1.get("sectionPanel").asText()).isEqualTo("OPEN");
        JsonNode b2 = objectMapper.readTree(audit.get(1).get(1));
        JsonNode a2 = objectMapper.readTree(audit.get(1).get(2));
        assertThat(b2.size()).isEqualTo(1);
        assertThat(b2.get("rightPanelFirst").asText()).isEqualTo("CASE_PAPER");
        assertThat(a2.get("rightPanelFirst").asText()).isEqualTo("PAST_VISITS");
        JsonNode b3 = objectMapper.readTree(audit.get(2).get(1));
        assertThat(b3.size()).as("both changed in one save: both are recorded").isEqualTo(2);
        assertThat(audit.get(0).get(3)).isEqualTo(clinic.ownerUserId().toString());
    }

    @Test
    void aSaveThatChangesNothingAnswers200WritesNoAuditAndKeepsTheVersion() throws Exception {
        Clinic clinic = newClinic();
        JsonNode sameAsDefaults = put(SETTINGS, clinic.owner(), settingsBody("COLLAPSED", "CASE_PAPER", 0), 200);
        assertThat(sameAsDefaults.get("version").asLong()).isZero();
        assertThat(stored(clinic)).as("no row is created for a save equal to the defaults").isEmpty();
        assertThat(emrAudits(clinic)).isZero();

        put(SETTINGS, clinic.owner(), settingsBody("OPEN", "CASE_PAPER", 0), 200);
        JsonNode again = put(SETTINGS, clinic.owner(), settingsBody("OPEN", "CASE_PAPER", 1), 200);
        assertThat(again.get("version").asLong()).isEqualTo(1);
        assertThat(auditRows(clinic, "EMR_SETTINGS_UPDATED")).hasSize(1);
    }

    @Test
    void aStaleVersionIs409EvenWhenNothingWouldChangeAndNothingIsApplied() throws Exception {
        Clinic clinic = newClinic();
        JsonNode problem = problemOf(HttpMethod.PUT, SETTINGS, clinic.owner(), settingsBody("OPEN", "CASE_PAPER", 3), 409,
                "emr-setup-changed");
        assertThat(problem.get("detail").asText()).isEqualTo("Someone else changed this setting. Reload and try again.");
        problemOf(HttpMethod.PUT, SETTINGS, clinic.owner(), settingsBody("COLLAPSED", "CASE_PAPER", 3), 409, "emr-setup-changed");
        assertThat(stored(clinic)).isEmpty();

        put(SETTINGS, clinic.owner(), settingsBody("OPEN", "CASE_PAPER", 0), 200);
        problemOf(HttpMethod.PUT, SETTINGS, clinic.owner(), settingsBody("COLLAPSED", "PAST_VISITS", 0), 409, "emr-setup-changed");
        problemOf(HttpMethod.PUT, SETTINGS, clinic.owner(), settingsBody("OPEN", "CASE_PAPER", 0), 409, "emr-setup-changed");
        assertThat(stored(clinic).subList(0, 4)).containsExactly("OPEN", "CASE_PAPER", "ASSESSMENT", "1");
        assertThat(auditRows(clinic, "EMR_SETTINGS_UPDATED")).hasSize(1);
    }

    @Test
    void twoOwnersSavingFirstAtTheSameMomentOneWinsAndTheOtherGets409NotAServerError() throws Exception {
        for (int round = 0; round < 3; round++) {
            Clinic clinic = newClinic();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            String[] bodies = {settingsBody("OPEN", "CASE_PAPER", 0), settingsBody("COLLAPSED", "PAST_VISITS", 0)};
            for (String body : bodies) {
                results.add(pool.submit(() -> {
                    start.await();
                    return call(HttpMethod.PUT, SETTINGS, clinic.owner(), body).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            pool.shutdown();
            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(200, 409);
            assertThat(count(clinic, "select count(*) from opd.emr_settings")).isEqualTo(1);
            assertThat(auditRows(clinic, "EMR_SETTINGS_UPDATED")).hasSize(1);
        }
    }

    // ---------------- invalid input ----------------

    @Test
    void anyOtherValueIsA400WithTheFieldNamedAndNothingChanges() throws Exception {
        Clinic clinic = newClinic();
        String[][] bad = {{"HALF", "CASE_PAPER", "sectionPanel"}, {"open", "CASE_PAPER", "sectionPanel"},
                {"", "CASE_PAPER", "sectionPanel"}, {"OPEN", "TOP", "rightPanelFirst"},
                {"OPEN", "case_paper", "rightPanelFirst"}, {"OPEN", "", "rightPanelFirst"}};
        for (String[] b : bad) {
            JsonNode problem = problemOf(HttpMethod.PUT, SETTINGS, clinic.owner(), settingsBody(b[0], b[1], 0), 400, "validation");
            assertThat(texts(problem.get("fieldErrors"), "field")).containsExactly(b[2]);
        }
        JsonNode both = problemOf(HttpMethod.PUT, SETTINGS, clinic.owner(), settingsBody("HALF", "TOP", 0), 400, "validation");
        assertThat(texts(both.get("fieldErrors"), "field")).containsExactlyInAnyOrder("sectionPanel", "rightPanelFirst");
        assertThat(texts(problemOf(HttpMethod.PUT, SETTINGS, clinic.owner(), settingsBody("OPEN", "CASE_PAPER", -1), 400,
                "validation").get("fieldErrors"), "field")).containsExactly("version");
        assertThat(texts(problemOf(HttpMethod.PUT, SETTINGS, clinic.owner(), "{}", 400, "validation").get("fieldErrors"),
                "field")).containsExactlyInAnyOrder("sectionPanel", "rightPanelFirst", "version");
        assertThat(texts(problemOf(HttpMethod.PUT, SETTINGS, clinic.owner(),
                "{\"sectionPanel\":null,\"rightPanelFirst\":null,\"version\":null}", 400, "validation").get("fieldErrors"),
                "field")).containsExactlyInAnyOrder("sectionPanel", "rightPanelFirst", "version");
        assertThat(texts(problemOf(HttpMethod.PUT, SETTINGS, clinic.owner(),
                "{\"sectionPanel\":\"OPEN\",\"rightPanelFirst\":\"CASE_PAPER\",\"version\":\"x\"}", 400, "validation")
                .get("fieldErrors"), "field")).containsExactly("version");
        assertThat(stored(clinic)).isEmpty();
        assertThat(emrAudits(clinic)).isZero();
    }

    @Test
    void theDefaultPartCannotBeChangedOrEvenSentAndNoUnknownFieldIsAccepted() throws Exception {
        Clinic clinic = newClinic();
        Clinic other = newClinic();
        for (String extra : new String[] {"\"defaultPart\":\"TREATMENT\"", "\"defaultPart\":\"ASSESSMENT\"",
                "\"defaultPart\":null", "\"tenantId\":\"" + other.tenantId() + "\"", "\"paperSize\":\"A4\"",
                "\"language\":\"mr\"", "\"zxqvfield\":true"}) {
            String body = "{\"sectionPanel\":\"OPEN\",\"rightPanelFirst\":\"PAST_VISITS\",\"version\":0," + extra + "}";
            JsonNode problem = problemOf(HttpMethod.PUT, SETTINGS, clinic.owner(), body, 400, "validation");
            assertThat(problem.toString()).doesNotContain("zxqvfield");
        }
        assertThat(stored(clinic)).isEmpty();
        assertThat(stored(other)).isEmpty();
        assertThat(get(SETTINGS, clinic.owner()).get("defaultPart").asText()).isEqualTo("ASSESSMENT");
        // the case paper settings of SAH-7 (paper size, language) are not copied here: the response has no such field
        JsonNode keys = get(SETTINGS, clinic.owner());
        assertThat(keys.has("paperSize")).isFalse();
        assertThat(keys.has("language")).isFalse();
    }

    @Test
    void aBodyOverSixtyFourKilobytesIs413() throws Exception {
        Clinic clinic = newClinic();
        String big = "{\"sectionPanel\":\"OPEN\",\"rightPanelFirst\":\"CASE_PAPER\",\"version\":0,\"pad\":\"" + "x".repeat(70_000) + "\"}";
        problemOf(HttpMethod.PUT, SETTINGS, clinic.owner(), big, 413, "payload-too-large");
        assertThat(stored(clinic)).isEmpty();
    }

    @Test
    void anEmptyOrBrokenBodyIsA400NotAServerError() throws Exception {
        Clinic clinic = newClinic();
        for (String body : new String[] {"", "{", "plain text"}) {
            assertThat(call(HttpMethod.PUT, SETTINGS, clinic.owner(), body).andReturn().getResponse().getStatus())
                    .as("body '" + body + "'").isEqualTo(400);
        }
    }

    // ---------------- who may save ----------------

    @Test
    void everyRoleButTheOwnerIsRefusedWith403AndNothingChanges() throws Exception {
        Clinic clinic = newClinic();
        List<Staff> people = new ArrayList<>();
        people.add(addDoctor(clinic, "General practice"));
        people.add(addStaff(clinic, "VISITING_DOCTOR", "Dr Visiting", "Dental", "BDS", "REG-" + shortId()));
        for (String role : NOT_DOCTOR_ROLES) {
            people.add(addNonDoctor(clinic, role));
        }
        for (Staff who : people) {
            problemOf(HttpMethod.PUT, SETTINGS, who.token(clinic), settingsBody("OPEN", "PAST_VISITS", 0), 403, "forbidden");
            problemOf(HttpMethod.PUT, SETTINGS, who.tokenClaiming(clinic, "OWNER"), settingsBody("OPEN", "PAST_VISITS", 0), 403, "forbidden");
        }
        for (String role : NON_OWNER_ROLES) {
            problemOf(HttpMethod.PUT, SETTINGS, clinic.as(role), settingsBody("OPEN", "PAST_VISITS", 0), 403, "forbidden");
        }
        problemOf(HttpMethod.PUT, SETTINGS, anonymous(), settingsBody("OPEN", "PAST_VISITS", 0), 401, "unauthorized");
        assertThat(stored(clinic)).isEmpty();
        assertThat(emrAudits(clinic)).isZero();
    }

    // ---------------- two clinics ----------------

    @Test
    void oneClinicsSettingsAreNeverSeenOrChangedByAnotherClinic() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        put(SETTINGS, a.owner(), settingsBody("OPEN", "PAST_VISITS", 0), 200);
        JsonNode seenByB = get(SETTINGS, b.owner());
        assertThat(seenByB.get("sectionPanel").asText()).isEqualTo("COLLAPSED");
        assertThat(seenByB.get("rightPanelFirst").asText()).isEqualTo("CASE_PAPER");
        assertThat(seenByB.get("version").asLong()).isZero();
        // A's version 1 is stale for B, whose version is 0
        problemOf(HttpMethod.PUT, SETTINGS, b.owner(), settingsBody("OPEN", "CASE_PAPER", 1), 409, "emr-setup-changed");
        JsonNode saved = put(SETTINGS, b.owner(), settingsBody("OPEN", "CASE_PAPER", 0), 200);
        assertThat(saved.get("rightPanelFirst").asText()).isEqualTo("CASE_PAPER");
        assertThat(get(SETTINGS, a.owner()).get("rightPanelFirst").asText()).isEqualTo("PAST_VISITS");
        assertThat(stored(a).get(1)).isEqualTo("PAST_VISITS");
        assertThat(stored(b).get(1)).isEqualTo("CASE_PAPER");
        assertThat(globalCount("select count(*) from opd.emr_settings")).as("no clinic bound sees nothing").isZero();
    }
}
