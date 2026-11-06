package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;

/**
 * SAH-28 AC12, personal data on the real {@code sahaj_test} database: the audit rows of the whole emergency life of a
 * case (register, seen, re-triage, release, refer, admit, setup change) hold ids, levels, bays, scale and times only;
 * neither they nor the captured application log nor a refusal body ever hold the name, mobile, age or complaint; the
 * queue never carries the mobile; reading the queue writes no patient access row; the triage log has no personal
 * column. Test plan: docs/test-plans/SAH-28.md.
 */
@OnTestDatabase
@ExtendWith(OutputCaptureExtension.class)
class EmergencyPrivacyIT extends EmergencySupport {

    private static final Set<String> FORBIDDEN_KEYS = Set.of("name", "displayName", "mobile", "age", "sex", "complaint",
            "identityPending", "patientName", "reason");

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new HashSet<>();
        if (node != null && !node.isNull()) {
            node.fieldNames().forEachRemaining(keys::add);
        }
        return keys;
    }

    private record Secret(String name, String mobile, String complaint) {
        List<String> all() {
            return List.of(name, mobile, complaint);
        }
    }

    private Secret secret(String first) {
        return new Secret(first + " Wadkar" + uniqueMobile().substring(5), uniqueMobile(), "ZXQ-" + UUID.randomUUID());
    }

    private Map<String, Object> bodyOf(Secret s, int level) {
        Map<String, Object> m = caseBody(s.name(), level);
        m.put("mobile", s.mobile());
        m.put("complaint", s.complaint());
        m.put("age", 71);
        return m;
    }

    @Test
    void theAuditRowsOfTheWholeLifeOfACaseHoldIdsLevelsBaysScaleAndTimesNeverTheNameMobileAgeOrComplaint(CapturedOutput output)
            throws Exception {
        Clinic clinic = hospital();
        Secret s1 = secret("Zubin");
        Secret s2 = secret("Meera");
        Secret s3 = secret("Kabir");
        Secret s4 = secret("Farah");
        Secret s5 = secret("Ishaan");
        List<String> responses = new ArrayList<>();

        String c1 = register(clinic, bodyOf(s1, 1)).get("actedCase").get("id").asText();
        String c2 = register(clinic, bodyOf(s2, 2)).get("actedCase").get("id").asText();
        String c3 = register(clinic, bodyOf(s3, 3)).get("actedCase").get("id").asText();
        String c4 = register(clinic, bodyOf(s4, 2)).get("actedCase").get("id").asText();
        Map<String, Object> unknown = unknownBody("M", 71, 2);
        unknown.put("mobile", s5.mobile());
        unknown.put("complaint", s5.complaint());
        String c5 = register(clinic, unknown).get("actedCase").get("id").asText();

        responses.add(seen(clinic, c1).toString());
        responses.add(retriage(clinic, c1, 2).toString());
        responses.add(release(clinic, c2).toString());
        responses.add(refer(clinic, c3).toString());
        UUID patient = person(clinic, "Real Patient " + uniqueMobile().substring(6)).id();
        ok(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(admitBody(clinic, patient, newBed(clinic, "gen"), c4)), 201);
        responses.add(setScale(clinic, 5).toString());
        responses.add(queue(clinic).toString());
        responses.add(ok(HttpMethod.GET, ER_SUMMARY, clinic.owner(), null, 200).toString());
        responses.add(ok(HttpMethod.GET, ADMISSIONS, clinic.owner(), null, 200).toString());

        int answered = responses.size();
        // refusals that carry the secrets in their body
        Map<String, Object> badMobile = bodyOf(s1, 2);
        badMobile.put("mobile", "12345");
        responses.add(problem(registerCall(clinic.owner(), badMobile), 400).toString());
        Map<String, Object> longComplaint = bodyOf(s1, 2);
        longComplaint.put("complaint", s1.complaint() + "x".repeat(200));
        responses.add(problem(registerCall(clinic.owner(), longComplaint), 400).toString());
        Map<String, Object> badAge = bodyOf(s1, 2);
        badAge.put("age", "seventy-one");
        responses.add(problem(registerCall(clinic.owner(), badAge), 400).toString());
        responses.add(problem(act(clinic.owner(), c2, "seen"), 409).toString());
        responses.add(problem(act(clinic.as("FRONT_DESK"), c1, "release"), 403).toString());
        responses.add(problem(act(clinic.owner(), UUID.randomUUID(), "refer"), 404).toString());
        responses.add(problem(admitCall(clinic.owner(), admitBody(clinic, patient, newBed(clinic, "gen"), c3)), 409).toString());

        // security review M3: opening the Admit hand-off of two open cases (it shows name, mobile and complaint, which the
        // answer must carry) writes IPD_ER_HANDOFF_OPENED rows that hold the case id only. The answers are not added to
        // `responses`: they legitimately hold the typed values.
        JsonNode handOne = optionsFor(clinic, "OWNER", c1).get("emergencyCase");
        assertThat(handOne.get("mobile").asText()).as("an open case's mobile is in the hand-off").isEqualTo(s1.mobile());
        assertThat(handOne.get("displayName").asText()).isEqualTo(s1.name());
        optionsFor(clinic, "NURSE", c5);
        String closedAnswer = call(HttpMethod.GET, OPTIONS + "?emergencyCaseId=" + c2, clinic.owner(), null).andReturn()
                .getResponse().getContentAsString();
        assertThat(closedAnswer).as("a closed case's hand-off is refused without the typed values")
                .doesNotContain(s2.mobile()).doesNotContain(s2.name()).doesNotContain(s2.complaint());

        // the audit rows: exact key sets
        Map<String, Set<String>> afterKeys = Map.of(
                "IPD_ER_HANDOFF_OPENED", Set.of("caseId"),
                "IPD_ER_REGISTERED", Set.of("caseId", "level", "scale", "bay", "arrivedAt"),
                "IPD_ER_SEEN", Set.of("caseId", "level", "seenAt"),
                "IPD_ER_RETRIAGED", Set.of("caseId", "level", "scale", "at"),
                "IPD_ER_RELEASED", Set.of("caseId", "level", "bay", "closedAt"),
                "IPD_ER_REFERRED", Set.of("caseId", "level", "bay", "closedAt"),
                "IPD_ER_ADMITTED", Set.of("caseId", "admissionId", "level", "bay", "closedAt"),
                "IPD_ER_SETTINGS_UPDATED", Set.of("scale", "targets3", "targets5", "bays", "unknownPatientRegistration", "remappedCases"));
        int rows = 0;
        for (Map.Entry<String, Set<String>> e : afterKeys.entrySet()) {
            List<String[]> audit = audit(clinic, e.getKey());
            assertThat(audit).as(e.getKey()).isNotEmpty();
            for (String[] row : audit) {
                rows++;
                Set<String> after = keys(objectMapper.readTree(row[3]));
                assertThat(after).as(e.getKey() + " after").isSubsetOf(e.getValue());
                assertThat(after).as(e.getKey() + " after").doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
                if (row[2] != null) {
                    assertThat(keys(objectMapper.readTree(row[2]))).as(e.getKey() + " before").doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
                }
                assertThat(row[4]).as(e.getKey() + " actor").isEqualTo(clinic.ownerUserId().toString());
            }
        }
        assertThat(rows).isGreaterThanOrEqualTo(8);
        assertThat(audits(clinic, "IPD_ER_REGISTERED")).isEqualTo(5);
        List<String[]> opened = audit(clinic, "IPD_ER_HANDOFF_OPENED");
        assertThat(opened).as("two 200 hand-offs, none for the refused one").hasSize(2);
        assertThat(opened.stream().map(r -> r[1]).toList()).containsExactlyInAnyOrder(c1, c5);
        for (String[] row : opened) {
            assertThat(keys(objectMapper.readTree(row[3]))).as("hand-off audit data").containsExactly("caseId");
            assertThat(row[2]).as("hand-off audit before").isNull();
        }
        // the whole audit trail of the clinic holds none of the typed values
        String trail = String.join("\n", values(clinic, "select coalesce(string_agg(action || ' ' || entity_type || ' ' || coalesce(entity_id::text, '') "
                + "|| ' ' || coalesce(before_json::text, '') || ' ' || coalesce(after_json::text, ''), E'\\n'), '') from platform.audit_event"));
        for (Secret s : List.of(s1, s2, s3, s4, s5)) {
            for (String value : s.all()) {
                assertThat(trail).as("audit trail holds " + value).doesNotContain(value);
            }
        }
        assertThat(trail).doesNotContain("Unknown male").doesNotContain("Chest pain");

        // nothing answered holds a mobile; the queue and the actions never carry it
        for (String body : responses) {
            for (Secret s : List.of(s1, s2, s3, s4, s5)) {
                assertThat(body).as("an answer holds the mobile").doesNotContain(s.mobile());
            }
        }
        for (String body : responses.subList(0, answered)) {
            assertThat(body).as("the queue and the actions never carry a mobile field").doesNotContain("\"mobile\"");
        }
        // a refusal does not echo what was typed
        for (String body : responses.subList(responses.size() - 7, responses.size())) {
            for (Secret s : List.of(s1, s2, s3, s4, s5)) {
                assertThat(body).doesNotContain(s.name()).doesNotContain(s.complaint());
            }
        }

        // the application log holds none of the typed values
        String log = output.getAll();
        for (Secret s : List.of(s1, s2, s3, s4, s5)) {
            for (String value : s.all()) {
                assertThat(log).as("the log holds " + value).doesNotContain(value);
            }
        }
        assertThat(log).doesNotContain("seventy-one");
        // the unknown patient keeps the typed identity on the case only; the cases are all still readable by SQL for the clinic
        assertThat(col(clinic, c5, "mobile")).isEqualTo(s5.mobile());
        assertThat(col(clinic, c1, "mobile")).as("an open case keeps its mobile").isEqualTo(s1.mobile());
        // security review M1(a): the cases that closed through the API (released, referred, admitted) hold no mobile
        assertThat(col(clinic, c2, "mobile")).as("released").isNull();
        assertThat(col(clinic, c3, "mobile")).as("referred").isNull();
        assertThat(col(clinic, c4, "mobile")).as("admitted").isNull();
    }

    @Test
    void theTriageLogHasNoPersonalColumnAndItsRowsHoldIdsLevelsScaleAndTimesOnly() throws Exception {
        Clinic clinic = hospital();
        String id = reg(clinic, "Triage Log Name " + uniqueMobile().substring(5), 2).get("id").asText();
        retriage(clinic, id, 1);
        assertThat(ownerText("select string_agg(column_name, ',' order by column_name) from information_schema.columns "
                + "where table_schema = 'ipd' and table_name = 'emergency_triage_event'"))
                .isEqualTo("case_id,from_level,id,level,reason,recorded_at,recorded_by,scale,tenant_id");
        assertThat(values(clinic, "select string_agg(e::text, ' ') from ipd.emergency_triage_event e")).hasSize(1);
        assertThat(values(clinic, "select string_agg(e::text, ' ') from ipd.emergency_triage_event e").get(0)).doesNotContain("Triage Log Name");
    }

    @Test
    void theHandOffWithTheMobileGoesToTheAdmitRolesOnlyAndTheCardsOfTheQueueNeverCarryIt() throws Exception {
        Clinic clinic = hospital();
        newBed(clinic, "gen");
        Secret s = secret("Tara");
        String id = register(clinic, bodyOf(s, 2)).get("actedCase").get("id").asText();
        for (String role : ALL_ROLES) {
            if (in(ER_ROLES, role)) {
                assertThat(queueAs(clinic, role).toString()).as(role).doesNotContain(s.mobile());
            }
            if (in(ADMITTERS, role)) {
                assertThat(optionsFor(clinic, role, id).get("emergencyCase").get("mobile").asText()).isEqualTo(s.mobile());
            } else {
                String body = call(HttpMethod.GET, OPTIONS + "?emergencyCaseId=" + id, clinic.as(role), null).andReturn().getResponse().getContentAsString();
                assertThat(body).as(role).doesNotContain(s.mobile()).doesNotContain(s.complaint());
            }
        }
    }

    @Test
    void readingTheQueueTheSummaryAndTheTilesWritesNoPatientAccessRowAndNoAuditRow() throws Exception {
        Clinic clinic = hospital();
        reg(clinic, "Asha Patil", 2);
        Written before = written(clinic);
        for (int i = 0; i < 3; i++) {
            for (String role : ER_ROLES) {
                queueAs(clinic, role);
                ok(HttpMethod.GET, ER_SUMMARY, clinic.as(role), null, 200);
                ok(HttpMethod.GET, ER_SETTINGS, clinic.as(role), null, 200);
                ok(HttpMethod.GET, ADMISSIONS, clinic.as(role), null, 200);
            }
        }
        assertThat(written(clinic)).isEqualTo(before);
        assertThat(count(clinic, "select count(*) from patient.record_access")).isZero();
    }

    private static boolean in(String[] roles, String role) {
        return java.util.Arrays.asList(roles).contains(role);
    }
}
