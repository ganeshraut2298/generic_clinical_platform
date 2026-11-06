package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-33 AC17, AC21, DEC-013 item 11, DEC-015 on the real {@code sahaj_test} database: no endpoint of the story writes a
 * patient record-access row; the audit rows hold exactly the fields of ADR-007 D11 and never the plan, a name or a vital
 * value; the clinical event text is one of the fixed templates; the application log across every action, refusal and a
 * forced database failure holds no patient name, mobile number, plan or vital value; a problem body never echoes them.
 * Test plan: docs/test-plans/SAH-33.md.
 */
@OnTestDatabase
class SafetyPrivacyIT extends SafetyTestSupport {

    private static final String PLAN = "ZXQ-PLAN-" + UUID.randomUUID();

    private record Ward(Clinic clinic, Staff owner, Staff doctor, Staff nurse) {
    }

    private Ward ward() throws Exception {
        Clinic clinic = hospital();
        return new Ward(clinic, ownerStaff(clinic), staff(clinic, "DOCTOR"), staff(clinic, "NURSE"));
    }

    /** A patient whose name, mobile, note and vitals are easy to search for. */
    private record Secret(Adm adm, String note) {
        List<String> everything() {
            return List.of(adm.name(), adm.mobile(), note, PLAN, "137/83", "36.7");
        }
    }

    private Secret secret(Clinic clinic, String first) throws Exception {
        Adm adm = admitNamed(clinic, first + " Wadkar" + uniqueMobile().substring(5));
        return new Secret(adm, "ZXQ-NOTE-" + UUID.randomUUID());
    }

    private java.util.Map<String, Object> mediumSecret(Secret s) {
        return noted(vit("137/83", "95", "91", "36.7", "22"), s.note()); // 3 + 2 + 1 = 6, Medium
    }

    private java.util.Map<String, Object> highSecret(Secret s) {
        return noted(vit("137/83", "115", "91", "36.7", "22"), s.note()); // 7, High
    }

    // ---------------- the record-access log ----------------

    @Test
    void noEndpointOfTheStoryWritesAPatientRecordAccessRowNotEvenARefusalOrAReplay() throws Exception {
        Ward w = ward();
        Staff front = staff(w.clinic(), "FRONT_DESK");
        Secret s1 = secret(w.clinic(), "Asha");
        Secret s2 = secret(w.clinic(), "Ravi");
        String e1 = raise(w.clinic(), s1.adm(), mediumSecret(s1));
        String e2 = raise(w.clinic(), s2.adm(), highSecret(s2));
        long access = count(w.clinic(), "select count(*) from patient.record_access");

        for (Staff st : new Staff[] {w.owner(), w.doctor(), w.nurse()}) {
            boardOf(st.auth());
            summaryOf(st.auth());
            eventsOf(st.auth(), null);
            eventsOf(st.auth(), "limit=50");
            ok(HttpMethod.GET, ADMISSIONS, st.auth(), null, 200); // the admitted list with the Safety part
        }
        callOk(w.nurse().auth(), e1, 0, 0);
        callOk(w.nurse().auth(), e1, 0, 0);                 // a replay
        ackOk(w.doctor().auth(), e1, 0);
        ackOk(w.doctor().auth(), e1, 0);                    // a replay
        save(w.clinic(), s1.adm().id(), highSecret(s1));      // a vitals save that re-opens it (a new POST writes none)
        respondOk(w.owner().auth(), e2, 0, "RAPID_RESPONSE", PLAN);
        respondOk(w.owner().auth(), e2, 0, "RAPID_RESPONSE", PLAN); // a replay
        // refusals
        problem(ack(w.nurse().auth(), e1, 0), 403);
        problem(callSenior(w.doctor().auth(), e1, 0, 0), 403);
        problem(call(HttpMethod.GET, SAFETY, front.auth(), null), 403);
        problem(ack(w.doctor().auth(), UUID.randomUUID(), 0), 404);
        problem(ack(w.doctor().auth(), e2, 0), 409);
        problem(call(HttpMethod.POST, ackUrl(e1), w.doctor().auth(), "{}"), 400);
        problem(call(HttpMethod.GET, EVENTS + "?limit=0", w.owner().auth(), null), 400);
        Clinic other = hospital();
        problem(ack(other.owner(), e1, 0), 404);

        assertThat(count(w.clinic(), "select count(*) from patient.record_access")).isEqualTo(access);
        assertThat(count(other, "select count(*) from patient.record_access")).isZero();
    }

    // ---------------------------- audit rows ----------------------------

    @Test
    @SuppressWarnings("unchecked")
    void theFiveAuditEventsHoldExactlyTheFieldsOfTheDesignAndNeverThePlanANameOrAVitalValue() throws Exception {
        Ward w = ward();
        Secret s = secret(w.clinic(), "Meera");
        String id = raise(w.clinic(), s.adm(), mediumSecret(s));
        callOk(w.nurse().auth(), id, 0, 0);
        ackOk(w.doctor().auth(), id, 0);
        save(w.clinic(), s.adm().id(), highSecret(s));                        // re-opened
        long v = esc(w.clinic(), id).version();
        respondOk(w.doctor().auth(), id, v, "TREATMENT_CHANGED", PLAN);       // closed from Open
        Secret d = secret(w.clinic(), "Kavita");
        String ed = raise(w.clinic(), d.adm(), mediumSecret(d));
        ackOk(w.owner().auth(), ed, 0);
        sign(w.clinic(), d.adm().id(), "death", "general");
        dischargeNow(w.clinic(), d.adm().id(), "death", "general", "UPI");   // closed at discharge, from Acknowledged
        Secret acked = secret(w.clinic(), "Sunil");
        String ea = raise(w.clinic(), acked.adm(), mediumSecret(acked));
        ackOk(w.doctor().auth(), ea, 0);
        respondOk(w.owner().auth(), ea, 1, "NO_CHANGE", PLAN);                // closed from Acknowledged

        Object[][] expected = {
                {"IPD_ESCALATION_ACKNOWLEDGED", Set.of("escalationId", "admissionId", "ewsScore", "ewsRisk", "minutesToAcknowledge")},
                {"IPD_ESCALATION_CLOSED", Set.of("escalationId", "admissionId", "ewsScore", "ewsRisk", "action", "statusBefore")},
                {"IPD_ESCALATION_SENIOR_CALLED", Set.of("escalationId", "admissionId", "ewsScore", "ewsRisk", "seniorCalls")},
                {"IPD_ESCALATION_REOPENED", Set.of("escalationId", "admissionId", "ewsScore", "ewsRisk", "previousScore", "previousRisk")},
                {"IPD_ESCALATION_CLOSED_AT_DISCHARGE", Set.of("escalationId", "admissionId", "ewsScore", "ewsRisk", "statusBefore",
                        "dischargeType")}};
        List<Secret> secrets = List.of(s, d, acked);
        for (Object[] e : expected) {
            List<String[]> rows = audit(w.clinic(), (String) e[0]);
            assertThat(rows).as((String) e[0]).isNotEmpty();
            for (String[] row : rows) {
                assertThat(row[0]).isEqualTo("IPD_EWS_ESCALATION");
                assertThat(row[2]).as("no before image").isNull();
                JsonNode after = objectMapper.readTree(row[3]);
                Set<String> fields = new TreeSet<>();
                after.fieldNames().forEachRemaining(fields::add);
                assertThat(fields).as((String) e[0]).isEqualTo(new TreeSet<>((Set<String>) e[1]));
                assertThat(after.get("escalationId").asText()).isEqualTo(row[1]);
                for (Secret secret : secrets) {
                    for (String text : secret.everything()) {
                        assertThat(row[3]).as((String) e[0] + " holds " + text).doesNotContain(text);
                    }
                }
                assertThat(row[3]).doesNotContain("Wadkar").doesNotContain(w.doctor().name()).doesNotContain(w.nurse().name());
            }
        }
        assertThat(audit(w.clinic(), "IPD_ESCALATION_CLOSED")).hasSize(2);
        assertThat(audit(w.clinic(), "IPD_ESCALATION_CLOSED").stream()
                .map(r -> { try { return objectMapper.readTree(r[3]).get("statusBefore").asText(); } catch (Exception ex) { throw new AssertionError(ex); } })
                .toList()).containsExactlyInAnyOrder("OPEN", "ACK");
        // the vitals audit row of the re-opening save carries the new outcome and no value or note either
        List<String[]> saves = audit(w.clinic(), "IPD_VITALS_RECORDED");
        assertThat(saves.stream().map(r -> r[3]).anyMatch(a -> a.contains("REOPENED"))).isTrue();
        for (String[] row : saves) {
            assertThat(row[3]).doesNotContain(s.note()).doesNotContain("137/83").doesNotContain("36.7").doesNotContain("Wadkar");
        }
    }

    // ---------------------------- event text ----------------------------

    @Test
    void everyClinicalEventTextIsOneOfTheFixedTemplatesAndHoldsNoNameNoteOrPlan() throws Exception {
        Ward w = ward();
        Secret s = secret(w.clinic(), "Mohan");
        String id = raise(w.clinic(), s.adm(), mediumSecret(s));
        callOk(w.nurse().auth(), id, 0, 0);
        ackOk(w.doctor().auth(), id, 0);
        save(w.clinic(), s.adm().id(), highSecret(s));
        long v = esc(w.clinic(), id).version();
        ackOk(w.owner().auth(), id, v);
        save(w.clinic(), s.adm().id(), noted(high14(), s.note()));          // a higher High score inside Acknowledged: updated
        respondOk(w.doctor().auth(), id, esc(w.clinic(), id).version(), "MOVED_ICU_HDU", PLAN);
        Secret l = secret(w.clinic(), "Lata");
        raise(w.clinic(), l.adm(), mediumSecret(l));
        sign(w.clinic(), l.adm().id(), "lama", "general");
        dischargeNow(w.clinic(), l.adm().id(), "lama", "general", "UPI");
        Secret f = secret(w.clinic(), "Farid");
        raise(w.clinic(), f.adm(), highSecret(f));
        respondOk(w.owner().auth(), openEscalationId(w.clinic(), f.adm().id()), 0, "RAPID_RESPONSE", PLAN);

        String label = "(Reviewed at the bedside|Treatment changed|Moved to ICU or HDU|Rapid response team called|Reviewed, no change needed)";
        List<Pattern> templates = List.of(
                Pattern.compile("info\\|Recorded vitals · EWS \\d+ \\((low|medium|high)\\)"),
                Pattern.compile("esc\\|Raised early warning escalation · EWS \\d+ \\((medium|high)\\) · doctor review within (15 min|1 hour)"),
                Pattern.compile("esc\\|Escalation updated · EWS now \\d+ \\((medium|high)\\)"),
                Pattern.compile("esc\\|Acknowledged escalation after \\d+ min"),
                Pattern.compile("esc\\|Escalation closed: " + label),
                Pattern.compile("esc\\|Called the senior doctor: escalation not seen after \\d+ min"),
                Pattern.compile("esc\\|Escalation re-opened · EWS now \\d+ \\(high\\) · doctor review within 15 min"),
                Pattern.compile("esc\\|Escalation closed at discharge \\((death|on request \\(lama\\)|referred)\\) · no doctor response recorded"));
        List<String> all = db().inTenant(w.clinic().tenantId(), c -> {
            List<String> out = new ArrayList<>();
            try (var ps = c.prepareStatement("select kind || '|' || text from ipd.clinical_event");
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
        assertThat(all.size()).isGreaterThan(12);
        for (String line : all) {
            assertThat(templates.stream().anyMatch(p -> p.matcher(line).matches())).as("a fixed template: " + line).isTrue();
            for (Secret secret : List.of(s, l, f)) {
                for (String text : secret.everything()) {
                    assertThat(line).doesNotContain(text);
                }
            }
        }
        // every one of the story's templates has been written at least once in this run
        for (Pattern p : templates.subList(2, templates.size())) {
            assertThat(all.stream().anyMatch(line -> p.matcher(line).matches())).as(p.pattern()).isTrue();
        }
    }

    // ---------------------------- the application log ----------------------------

    @Test
    void theApplicationLogAcrossEveryActionRefusalAndAForcedFailureHoldsNoNameMobilePlanOrVitalValue() throws Exception {
        Ward w = ward();
        Secret s = secret(w.clinic(), "Geeta");
        Secret t = secret(w.clinic(), "Hari");
        String e1 = raise(w.clinic(), s.adm(), mediumSecret(s));
        String e2 = raise(w.clinic(), t.adm(), highSecret(t));
        List<String> bodies = new ArrayList<>();
        try (LogCapture logs = new LogCapture()) {
            // reads: the answers do hold names, the log must not
            for (Staff st : new Staff[] {w.owner(), w.doctor(), w.nurse()}) {
                boardOf(st.auth());
                eventsOf(st.auth(), "limit=50");
                summaryOf(st.auth());
                ok(HttpMethod.GET, ADMISSIONS, st.auth(), null, 200);
            }
            // the actions, a replay, a re-open
            callOk(w.nurse().auth(), e1, 0, 0);
            ackOk(w.doctor().auth(), e1, 0);
            ackOk(w.doctor().auth(), e1, 0);
            save(w.clinic(), s.adm().id(), highSecret(s));
            respondOk(w.owner().auth(), e2, 0, "BEDSIDE_REVIEW", PLAN);
            respondOk(w.owner().auth(), e2, 0, "BEDSIDE_REVIEW", PLAN);
            // refusals, every kind; the plan is typed in each of them
            String badPlan = PLAN + "\u0007";
            bodies.add(problem(respond(w.doctor().auth(), e1, 0, "NO_CHANGE", badPlan), 400).toString());
            bodies.add(problem(respond(w.doctor().auth(), e1, 0, "NO_CHANGE", PLAN + "x".repeat(1100)), 400).toString());
            bodies.add(problem(respond(w.doctor().auth(), e1, 1, "NO_CHANGE", PLAN), 409).toString());      // changed: re-opened
            bodies.add(problem(respond(w.doctor().auth(), e2, 1, "NO_CHANGE", PLAN + " again"), 409).toString()); // closed
            bodies.add(problem(ack(w.nurse().auth(), e1, 2), 403).toString());
            bodies.add(problem(callSenior(w.doctor().auth(), e1, 2, 0), 403).toString());
            bodies.add(problem(ack(w.doctor().auth(), UUID.randomUUID(), 0), 404).toString());
            bodies.add(problem(call(HttpMethod.POST, ackUrl(e1), w.doctor().auth(), "{\"version\":\"" + PLAN + "\"}"), 400).toString());
            bodies.add(problem(call(HttpMethod.GET, EVENTS + "?cursor=" + "bad-cursor", w.owner().auth(), null), 400).toString());
            Clinic other = hospital();
            bodies.add(problem(ack(other.owner(), e1, 0), 404).toString());
            // a discharge refused and one closing the escalation at discharge
            sign(w.clinic(), s.adm().id(), "normal", "general");
            JsonNode sheet = sheet(w.clinic(), s.adm().id());
            bodies.add(problem(postDischarge(w.clinic().owner(), s.adm().id(),
                    dischargeBody("normal", "general", totalOf(sheet), depositsOf(sheet), "UPI")), 409).toString());
            // a forced failure while closing: the 500 body and the log hold none of it
            failingInsert(w.clinic(), "ipd.clinical_event", "new.text like 'Escalation closed%'", () ->
                    bodies.add(problem(respond(w.owner().auth(), e1, esc(w.clinic(), e1).version(), "NO_CHANGE", PLAN), 500).toString()));
            String text = logs.allText();
            assertThat(text).isNotBlank();
            for (Secret secret : List.of(s, t)) {
                for (String forbidden : secret.everything()) {
                    assertThat(text).as("the log holds " + forbidden).doesNotContain(forbidden);
                }
            }
            assertThat(text).doesNotContain("Wadkar").doesNotContain(PLAN).doesNotContain("ZXQ-PLAN");
            assertThat(text).doesNotContain(w.doctor().name()).doesNotContain(w.nurse().name());
        }
        // and the problem bodies never echo what was typed or who the patient is
        assertThat(bodies).isNotEmpty();
        for (String body : bodies) {
            for (Secret secret : List.of(s, t)) {
                for (String forbidden : secret.everything()) {
                    assertThat(body).doesNotContain(forbidden);
                }
            }
            assertThat(body).doesNotContain("Wadkar").doesNotContain("ZXQ-PLAN");
        }
    }

    // ---------------------------- the plan stays with the escalation ----------------------------

    @Test
    void thePlanIsStoredOnlyOnTheEscalationRowAndNowhereElseInTheDatabase() throws Exception {
        Ward w = ward();
        Secret s = secret(w.clinic(), "Nita");
        String id = raise(w.clinic(), s.adm(), mediumSecret(s));
        respondOk(w.doctor().auth(), id, 0, "NO_CHANGE", PLAN);
        assertThat(esc(w.clinic(), id).note()).isEqualTo(PLAN);
        assertThat(count(w.clinic(), "select count(*) from ipd.clinical_event where text like ?", "%ZXQ%")).isZero();
        assertThat(count(w.clinic(), "select count(*) from platform.audit_event where after_json::text like ? "
                + "or coalesce(before_json::text, '') like ?", "%ZXQ-PLAN%", "%ZXQ-PLAN%")).isZero();
        assertThat(count(w.clinic(), "select count(*) from ipd.nursing_note where note like ?", "%ZXQ-PLAN%")).isZero();
        assertThat(count(w.clinic(), "select count(*) from ipd.ews_escalation where response_note like ?", "%ZXQ-PLAN%")).isOne();
    }
}
