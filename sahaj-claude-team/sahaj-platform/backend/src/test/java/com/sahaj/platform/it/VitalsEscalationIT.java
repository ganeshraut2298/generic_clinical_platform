package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SAH-30 AC7, AC8, AC9: the escalation rules over HTTP on the real {@code sahaj_test} database. A Low score raises
 * nothing; a Medium or High score raises one escalation with the target 1 hour or 15 minutes; a higher score replaces
 * the score, risk and target of the open one (the first raise time stays, the version goes up); an equal or lower
 * score changes nothing; a Low score never closes it; a closed escalation is followed by a new row; the database
 * allows only one open escalation per admission. The exact words of the toast and of the event log. Test plan:
 * docs/test-plans/SAH-30.md.
 */
@OnTestDatabase
class VitalsEscalationIT extends VitalsSupport {

    private static String outcome(JsonNode saved) {
        return saved.get("escalation").get("outcome").asText();
    }

    private static JsonNode open(JsonNode saved) {
        return saved.get("escalation").get("open");
    }

    /** The toast the contract tells the client to build from the answer. */
    private static String toast(JsonNode saved) {
        String name = saved.get("patientName").asText();
        int score = saved.get("saved").get("ewsScore").asInt();
        String risk = saved.get("saved").get("ewsRisk").asText();
        JsonNode open = open(saved);
        if ("LOW".equals(risk) || open.isNull()) {
            return "Vitals saved for " + name + " " + DOT + " early warning " + score + " (" + risk.toLowerCase() + ")";
        }
        return "Escalation raised for " + name + " " + DOT + " EWS " + score + " " + DOT + " doctor review within "
                + (open.get("targetMinutes").asInt() == 15 ? "15 min" : "1 hour");
    }

    // ---------------- AC7: Low ----------------

    @Test
    void aLowScoreRaisesNoEscalationAndTheToastSaysVitalsSaved() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        JsonNode saved = save(clinic, adm.id(), low());
        assertThat(saved.get("saved").get("ewsScore").asInt()).isZero();
        assertThat(saved.get("saved").get("ewsRisk").asText()).isEqualTo("LOW");
        assertThat(outcome(saved)).isEqualTo("NONE");
        assertThat(open(saved).isNull()).isTrue();
        assertThat(toast(saved)).isEqualTo("Vitals saved for " + adm.name() + " · early warning 0 (low)");
        assertThat(escalationCount(clinic, adm.id())).isZero();
        assertThat(eventLines(clinic, adm.id())).containsExactly("info|Recorded vitals " + DOT + " EWS 0 (low)");
    }

    @Test
    void aLowScoreAboveZeroIsStillLowBelowFiveWithNoParameterAtThree() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        // SpO2 93 (2) + RR 22 (2) = 4: Low
        JsonNode saved = save(clinic, adm.id(), vit("120/80", "80", "93", "37.0", "22"));
        assertThat(saved.get("saved").get("ewsScore").asInt()).isEqualTo(4);
        assertThat(saved.get("saved").get("ewsRisk").asText()).isEqualTo("LOW");
        assertThat(outcome(saved)).isEqualTo("NONE");
        assertThat(toast(saved)).endsWith("early warning 4 (low)");
        assertThat(escalationCount(clinic, adm.id())).isZero();
    }

    // ---------------- AC8: Medium and High raise ----------------

    @Test
    void aMediumScoreRaisesAnEscalationWithOneHourAndTheExactToastAndEvents() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        JsonNode saved = save(clinic, adm.id(), medium3());
        assertThat(saved.get("saved").get("ewsScore").asInt()).isEqualTo(3);
        assertThat(saved.get("saved").get("ewsRisk").asText()).isEqualTo("MEDIUM");
        assertThat(outcome(saved)).isEqualTo("RAISED");
        JsonNode open = open(saved);
        assertThat(open.get("ewsScore").asInt()).isEqualTo(3);
        assertThat(open.get("ewsRisk").asText()).isEqualTo("MEDIUM");
        assertThat(open.get("targetMinutes").asInt()).isEqualTo(60);
        assertThat(open.get("status").asText()).isEqualTo("OPEN");
        assertThat(instant(open.get("raisedAt"))).isEqualTo(instant(saved.get("saved").get("recordedAt")));
        assertThat(toast(saved)).isEqualTo("Escalation raised for " + adm.name() + " · EWS 3 · doctor review within 1 hour");
        assertThat(toast(saved)).doesNotContainIgnoringCase("duty doctor").doesNotContainIgnoringCase("alerted");

        assertThat(openEscalation(clinic, adm.id())).isEqualTo("3|MEDIUM|60|OPEN|0");
        assertThat(escalationColumn(clinic, adm.id(), "raised_by")).isEqualTo(clinic.ownerUserId().toString());
        assertThat(open.get("id").asText()).isEqualTo(openEscalationId(clinic, adm.id()));
        // the log, newest first: the escalation line is above "Recorded vitals"
        assertThat(eventLines(clinic, adm.id())).containsExactly(
                "esc|Raised early warning escalation " + DOT + " EWS 3 (medium) " + DOT + " doctor review within 1 hour",
                "info|Recorded vitals " + DOT + " EWS 3 (medium)");
    }

    @Test
    void aMediumScoreFromTheTotalAloneIsMediumToo() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        JsonNode saved = save(clinic, adm.id(), medium5());
        assertThat(saved.get("saved").get("ewsScore").asInt()).isEqualTo(5);
        assertThat(saved.get("saved").get("ewsRisk").asText()).isEqualTo("MEDIUM");
        assertThat(outcome(saved)).isEqualTo("RAISED");
        assertThat(open(saved).get("targetMinutes").asInt()).isEqualTo(60);
    }

    @Test
    void aHighScoreRaisesAnEscalationWithFifteenMinutesAndTheExactToastAndEvents() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        JsonNode saved = save(clinic, adm.id(), high7());
        assertThat(saved.get("saved").get("ewsScore").asInt()).isEqualTo(7);
        assertThat(saved.get("saved").get("ewsRisk").asText()).isEqualTo("HIGH");
        assertThat(outcome(saved)).isEqualTo("RAISED");
        assertThat(open(saved).get("targetMinutes").asInt()).isEqualTo(15);
        assertThat(toast(saved)).isEqualTo("Escalation raised for " + adm.name() + " · EWS 7 · doctor review within 15 min");
        assertThat(openEscalation(clinic, adm.id())).isEqualTo("7|HIGH|15|OPEN|0");
        assertThat(eventLines(clinic, adm.id())).containsExactly(
                "esc|Raised early warning escalation " + DOT + " EWS 7 (high) " + DOT + " doctor review within 15 min",
                "info|Recorded vitals " + DOT + " EWS 7 (high)");
    }

    // ---------------- AC8: a second save ----------------

    @Test
    void aHigherScoreUpdatesTheOpenEscalationKeepingTheFirstRaiseAndAddingOneToTheVersion() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        JsonNode first = save(clinic, adm.id(), medium3());
        String escalationId = openEscalationId(clinic, adm.id());
        String raisedAt = escalationColumn(clinic, adm.id(), "raised_at");

        JsonNode second = save(clinic, adm.id(), medium6());
        assertThat(outcome(second)).isEqualTo("UPDATED");
        assertThat(open(second).get("id").asText()).isEqualTo(escalationId);
        assertThat(open(second).get("ewsScore").asInt()).isEqualTo(6);
        assertThat(open(second).get("ewsRisk").asText()).isEqualTo("MEDIUM");
        assertThat(open(second).get("targetMinutes").asInt()).isEqualTo(60);
        assertThat(instant(open(second).get("raisedAt"))).isEqualTo(instant(first.get("saved").get("recordedAt")));
        assertThat(toast(second)).isEqualTo("Escalation raised for " + adm.name() + " · EWS 6 · doctor review within 1 hour");
        assertThat(openEscalation(clinic, adm.id())).isEqualTo("6|MEDIUM|60|OPEN|1");
        assertThat(escalationColumn(clinic, adm.id(), "raised_at")).isEqualTo(raisedAt);
        assertThat(escalationColumn(clinic, adm.id(), "raised_by")).isEqualTo(clinic.ownerUserId().toString());
        assertThat(escalationCount(clinic, adm.id())).isOne();

        // Medium becomes High: the risk and the target change with the score
        JsonNode third = save(clinic, adm.id(), high7());
        assertThat(outcome(third)).isEqualTo("UPDATED");
        assertThat(open(third).get("ewsRisk").asText()).isEqualTo("HIGH");
        assertThat(open(third).get("targetMinutes").asInt()).isEqualTo(15);
        assertThat(toast(third)).endsWith("EWS 7 · doctor review within 15 min");
        assertThat(openEscalation(clinic, adm.id())).isEqualTo("7|HIGH|15|OPEN|2");
        assertThat(escalationColumn(clinic, adm.id(), "raised_at")).isEqualTo(raisedAt);
        assertThat(escalationCount(clinic, adm.id())).isOne();

        assertThat(eventLines(clinic, adm.id())).containsExactly(
                "esc|Escalation updated " + DOT + " EWS now 7 (high)",
                "info|Recorded vitals " + DOT + " EWS 7 (high)",
                "esc|Escalation updated " + DOT + " EWS now 6 (medium)",
                "info|Recorded vitals " + DOT + " EWS 6 (medium)",
                "esc|Raised early warning escalation " + DOT + " EWS 3 (medium) " + DOT + " doctor review within 1 hour",
                "info|Recorded vitals " + DOT + " EWS 3 (medium)");
    }

    @Test
    void anEqualOrLowerScoreChangesNothingAndANoneOutcomeStillReturnsTheOpenEscalation() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        save(clinic, adm.id(), high7());
        String before = openEscalation(clinic, adm.id());
        assertThat(before).isEqualTo("7|HIGH|15|OPEN|0");
        List<String> eventsBefore = eventLines(clinic, adm.id());

        int expectedEvents = eventsBefore.size();
        // equal, lower Medium, lower High... each adds only the "Recorded vitals" line
        for (java.util.Map<String, Object> body : List.of(high7(), medium6(), medium3(), low(), high7())) {
            JsonNode saved = save(clinic, adm.id(), body);
            assertThat(outcome(saved)).isEqualTo("NONE");
            assertThat(open(saved).get("ewsScore").asInt()).isEqualTo(7);
            assertThat(open(saved).get("ewsRisk").asText()).isEqualTo("HIGH");
            assertThat(open(saved).get("targetMinutes").asInt()).isEqualTo(15);
            assertThat(openEscalation(clinic, adm.id())).isEqualTo(before);
            expectedEvents++;
            assertThat(eventCount(clinic, adm.id())).isEqualTo(expectedEvents);
        }
        assertThat(eventLines(clinic, adm.id()).stream().filter(l -> l.startsWith("esc|")).count()).isOne();
        assertThat(vitalsCount(clinic, adm.id())).isEqualTo(6);
        assertThat(escalationCount(clinic, adm.id())).isOne();
    }

    @Test
    void aLowScoreAfterAMediumNeverClosesTheEscalation() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        save(clinic, adm.id(), medium3());
        String id = openEscalationId(clinic, adm.id());
        for (int i = 0; i < 3; i++) {
            JsonNode saved = save(clinic, adm.id(), low());
            assertThat(outcome(saved)).isEqualTo("NONE");
            assertThat(open(saved).get("id").asText()).isEqualTo(id);
            assertThat(toast(saved)).startsWith("Vitals saved for ");
        }
        assertThat(openEscalation(clinic, adm.id())).isEqualTo("3|MEDIUM|60|OPEN|0");
        assertThat(escalationColumn(clinic, adm.id(), "(closed_at is null)")).isEqualTo("true");
    }

    @Test
    void anAcknowledgedEscalationKeepsItsAcknowledgementWhenTheScoreRisesInsideTheSameRisk() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        save(clinic, adm.id(), medium3());
        acknowledgeEscalation(clinic, adm.id());
        assertThat(openEscalation(clinic, adm.id())).isEqualTo("3|MEDIUM|60|ACK|1");

        JsonNode same = save(clinic, adm.id(), medium3());
        assertThat(outcome(same)).isEqualTo("NONE");
        assertThat(open(same).get("status").asText()).isEqualTo("ACK");

        // 3 to 6, both Medium: the score is updated, the status stays Acknowledged (SAH-33 AC12)
        JsonNode higher = save(clinic, adm.id(), medium6());
        assertThat(outcome(higher)).isEqualTo("UPDATED");
        assertThat(open(higher).get("status").asText()).isEqualTo("ACK");
        assertThat(openEscalation(clinic, adm.id())).isEqualTo("6|MEDIUM|60|ACK|2");
        assertThat(escalationColumn(clinic, adm.id(), "(acknowledged_by is not null and acknowledged_at is not null)"))
                .isEqualTo("true");
        assertThat(escalationColumn(clinic, adm.id(), "(reopened_at is null)")).isEqualTo("true");
        assertThat(eventLines(clinic, adm.id()).get(0)).isEqualTo("esc|Escalation updated " + DOT + " EWS now 6 (medium)");
        assertThat(escalationCount(clinic, adm.id())).isOne();
    }

    @Test
    void aRiskRiseFromMediumToHighReopensAnAcknowledgedEscalationClearsTheAcknowledgementAndRestartsTheClock()
            throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        save(clinic, adm.id(), medium3());
        String id = openEscalationId(clinic, adm.id());
        String raisedAt = escalationColumn(clinic, adm.id(), "raised_at");
        acknowledgeEscalation(clinic, adm.id());
        assertThat(openEscalation(clinic, adm.id())).isEqualTo("3|MEDIUM|60|ACK|1");
        assertThat(escalationColumn(clinic, adm.id(), "(reopened_at is null)")).isEqualTo("true");

        JsonNode reopened = save(clinic, adm.id(), high7());
        assertThat(outcome(reopened)).isEqualTo("REOPENED");
        assertThat(open(reopened).get("id").asText()).isEqualTo(id);
        assertThat(open(reopened).get("status").asText()).isEqualTo("OPEN");
        assertThat(open(reopened).get("ewsScore").asInt()).isEqualTo(7);
        assertThat(open(reopened).get("ewsRisk").asText()).isEqualTo("HIGH");
        assertThat(open(reopened).get("targetMinutes").asInt()).isEqualTo(15);
        // the SAH-30 toast is unchanged (AC22)
        assertThat(toast(reopened)).isEqualTo("Escalation raised for " + adm.name() + " · EWS 7 · doctor review within 15 min");
        assertThat(openEscalation(clinic, adm.id())).isEqualTo("7|HIGH|15|OPEN|2");
        assertThat(escalationColumn(clinic, adm.id(), "(acknowledged_by is null and acknowledged_at is null)")).isEqualTo("true");
        assertThat(escalationColumn(clinic, adm.id(), "(reopened_at is not null)")).isEqualTo("true");
        assertThat(escalationColumn(clinic, adm.id(), "raised_at")).as("the first raise stays on the record").isEqualTo(raisedAt);
        // reopened_at is the database time of the save, after the first raise, and is the new start of the clock
        Instant reopenedAt = instantOf(clinic, "select reopened_at from ipd.ews_escalation where id = ?::uuid", id);
        assertThat(reopenedAt).isAfter(instantOf(clinic, "select raised_at from ipd.ews_escalation where id = ?::uuid", id));
        assertThat(reopenedAt).isEqualTo(instantOf(clinic, "select recorded_at from ipd.vitals where admission_id = ?::uuid "
                + "order by recorded_at desc limit 1", adm.id()));
        assertThat(eventLines(clinic, adm.id()).get(0))
                .isEqualTo("esc|Escalation re-opened " + DOT + " EWS now 7 (high) " + DOT + " doctor review within 15 min");
        List<String[]> audit = audit(clinic, "IPD_ESCALATION_REOPENED");
        assertThat(audit).hasSize(1);
        JsonNode after = objectMapper.readTree(audit.get(0)[3]);
        assertThat(after.get("previousScore").asInt()).isEqualTo(3);
        assertThat(after.get("previousRisk").asText()).isEqualTo("MEDIUM");
        assertThat(after.get("ewsScore").asInt()).isEqualTo(7);
        assertThat(objectMapper.readTree(audit(clinic, "IPD_VITALS_RECORDED").get(1)[3]).get("escalationOutcome").asText())
                .isEqualTo("REOPENED");
        assertThat(escalationCount(clinic, adm.id())).isOne();
    }

    @Test
    void aClosedEscalationIsFollowedByANewRowWhenTheNextScoreIsMediumOrHigh() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        save(clinic, adm.id(), high7());
        String firstId = openEscalationId(clinic, adm.id());
        closeEscalation(clinic, adm.id());
        assertThat(openEscalationCount(clinic, adm.id())).isZero();

        // a Low score after the close raises nothing and has no open escalation to show
        JsonNode low = save(clinic, adm.id(), low());
        assertThat(outcome(low)).isEqualTo("NONE");
        assertThat(open(low).isNull()).isTrue();

        // a Medium score after the close is a new escalation, not an update of the closed one
        JsonNode medium = save(clinic, adm.id(), medium3());
        assertThat(outcome(medium)).isEqualTo("RAISED");
        assertThat(open(medium).get("id").asText()).isNotEqualTo(firstId);
        assertThat(open(medium).get("targetMinutes").asInt()).isEqualTo(60);
        assertThat(escalationCount(clinic, adm.id())).isEqualTo(2);
        assertThat(openEscalationCount(clinic, adm.id())).isOne();
        // the closed row is untouched
        assertThat(values(clinic, "select ews_score || '|' || ews_risk || '|' || target_minutes || '|' || status || '|' || version "
                + "from ipd.ews_escalation where id = ?::uuid", firstId).get(0)).isEqualTo("7|HIGH|15|DONE|1");
    }

    @Test
    void anotherAdmissionOfTheSameClinicHasItsOwnEscalation() throws Exception {
        Clinic clinic = hospital();
        Adm a = admitOne(clinic);
        Adm b = admitOne(clinic);
        assertThat(outcome(save(clinic, a.id(), medium3()))).isEqualTo("RAISED");
        assertThat(outcome(save(clinic, b.id(), high7()))).isEqualTo("RAISED");
        assertThat(openEscalation(clinic, a.id())).isEqualTo("3|MEDIUM|60|OPEN|0");
        assertThat(openEscalation(clinic, b.id())).isEqualTo("7|HIGH|15|OPEN|0");
        assertThat(rows(clinic, "ews_escalation")).isEqualTo(2);
    }

    // ---------------- AC9: the log ----------------

    @Test
    void theEventLogHoldsFixedTextOnlyWithTheUserAndNeverANameOrANote() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitNamed(clinic, "Meera Kulkarni " + uniqueMobile().substring(5));
        String note = "ZXQ-" + java.util.UUID.randomUUID();
        save(clinic, adm.id(), noted(high7(), note));
        save(clinic, adm.id(), noted(high14(), note + " again"));
        List<String> lines = eventLines(clinic, adm.id());
        assertThat(lines).hasSize(4);
        for (String line : lines) {
            assertThat(line).doesNotContain("Meera").doesNotContain(adm.mobile()).doesNotContain("ZXQ").doesNotContain("120/80");
            assertThat(line.length()).isLessThanOrEqualTo(300 + 5);
        }
        assertThat(values(clinic, "select count(*) from ipd.clinical_event where admission_id = ?::uuid and actor = ?",
                adm.id(), clinic.ownerUserId()).get(0)).isEqualTo("4");
        assertThat(values(clinic, "select count(distinct kind) from ipd.clinical_event where admission_id = ?::uuid", adm.id())
                .get(0)).isEqualTo("2");
        assertThat(lines.get(0)).isEqualTo("esc|Escalation updated " + DOT + " EWS now 14 (high)");
    }

    @Test
    void theAuditRowNamesTheEscalationOutcomeOfEachSave() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        save(clinic, adm.id(), low());
        save(clinic, adm.id(), medium3());
        save(clinic, adm.id(), high7());
        save(clinic, adm.id(), medium3());
        List<String[]> audit = audit(clinic, "IPD_VITALS_RECORDED");
        assertThat(audit).hasSize(4);
        List<String> outcomes = audit.stream().map(a -> {
            try {
                return objectMapper.readTree(a[3]).get("escalationOutcome").asText();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }).toList();
        assertThat(outcomes).containsExactly("NONE", "RAISED", "UPDATED", "NONE");
        JsonNode raised = objectMapper.readTree(audit.get(1)[3]);
        assertThat(raised.get("escalationId").asText()).isEqualTo(openEscalationId(clinic, adm.id()));
        JsonNode none = objectMapper.readTree(audit.get(0)[3]).path("escalationId");
        assertThat(none.isNull() || none.isMissingNode()).isTrue();
    }
}
