package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-33 AC8 to AC13, AC15, AC18, AC20, AC21: Acknowledge, Close escalation (Record response) and Call senior over HTTP on
 * the real {@code sahaj_test} database. Each action from Open and from Acknowledged, the stale version and the state
 * answers (409 escalation-changed and escalation-state with the exact texts), the replay of a lost answer by the same
 * person, the wrong roles (also a role changed in the membership while the token still says the old one), another
 * clinic's escalation, a discharged patient, IPD Off and Read-only, every 400 of the request bodies, the times the
 * database stamps, the clinical event and the audit row written by each action. Test plan: docs/test-plans/SAH-33.md.
 */
@OnTestDatabase
class SafetyActionsIT extends SafetyTestSupport {

    private static final String OFF_TEXT = "In-patients (IPD) is not switched on for your clinic.";
    private static final String PLAN = "Oxygen up to 6 L, repeat vitals in 30 minutes";

    /** A hospital with an owner, a doctor and a nurse of their own, and one admitted patient with an Open escalation (EWS 3). */
    private record Ward(Clinic clinic, Staff owner, Staff doctor, Staff nurse, Adm adm, String esc) {
    }

    private Ward ward() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitNamed(clinic, "Meera Kulkarni " + uniqueMobile().substring(5));
        String esc = raise(clinic, adm, medium3());
        return new Ward(clinic, ownerStaff(clinic), staff(clinic, "DOCTOR"), staff(clinic, "NURSE"), adm, esc);
    }

    /** Another admitted patient of the clinic with the vitals saved, so that it has an Open escalation. */
    private Adm another(Ward w, java.util.Map<String, Object> vitals) throws Exception {
        Adm adm = admitOne(w.clinic());
        save(w.clinic(), adm.id(), vitals);
        return adm;
    }

    // ---------------- Acknowledge ----------------

    @Test
    void aDoctorAcknowledgesAnOpenEscalationAndTheRowEventAndAuditAreWritten() throws Exception {
        Ward w = ward();
        Instant before = dbNow(w.clinic());
        JsonNode result = ackOk(w.doctor().auth(), w.esc(), 0);
        Instant after = dbNow(w.clinic());

        assertThat(result.get("replayed").asBoolean()).isFalse();
        assertThat(instant(result.get("asOf"))).isBetween(before, after);
        JsonNode row = result.get("escalation");
        assertThat(row.get("id").asText()).isEqualTo(w.esc());
        assertThat(row.get("admissionId").asText()).isEqualTo(w.adm().id());
        assertThat(row.get("status").asText()).isEqualTo("ACK");
        assertThat(row.get("version").asLong()).isOne();
        assertThat(row.get("acknowledgedByName").asText()).isEqualTo(w.doctor().name());
        assertThat(row.get("patientName").asText()).isEqualTo(w.adm().name());
        assertThat(row.get("bedCode").asText()).isEqualTo(w.adm().bedCode());
        assertThat(row.get("ewsScore").asInt()).isEqualTo(3);
        assertThat(row.get("ewsRisk").asText()).isEqualTo("MEDIUM");
        assertThat(row.get("pastTarget").asBoolean()).as("acknowledging stops the overdue state").isFalse();
        assertThat(row.get("canAcknowledge").asBoolean()).isFalse();
        assertThat(row.get("canRespond").asBoolean()).isTrue();
        assertThat(row.get("canCallSenior").asBoolean()).isFalse();
        assertThat(row.get("response").isNull()).isTrue();
        assertThat(row.get("acknowledgedAt").isNull()).isFalse();

        Esc e = esc(w.clinic(), w.esc());
        assertThat(e.status()).isEqualTo("ACK");
        assertThat(e.ackBy()).isEqualTo(w.doctor().userId().toString());
        assertThat(e.version()).isOne();
        assertThat(e.hasClosedAt()).isFalse();
        // the time is the database clock of the write, never a value sent
        Instant ackAt = instantOf(w.clinic(), "select acknowledged_at from ipd.ews_escalation where id = ?::uuid", w.esc());
        assertThat(ackAt).isBetween(before.minusSeconds(1), after.plusSeconds(1));
        assertThat(instant(row.get("acknowledgedAt"))).isEqualTo(ackAt);

        assertThat(escEvents(w.clinic(), w.adm().id()).get(0)).isEqualTo("Acknowledged escalation after 0 min");
        assertThat(values(w.clinic(), "select actor::text from ipd.clinical_event where admission_id = ?::uuid "
                + "and text like 'Acknowledged%'", w.adm().id())).containsExactly(w.doctor().userId().toString());
        List<String[]> audit = audit(w.clinic(), "IPD_ESCALATION_ACKNOWLEDGED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)[0]).isEqualTo("IPD_EWS_ESCALATION");
        assertThat(audit.get(0)[1]).isEqualTo(w.esc());
        assertThat(audit.get(0)[4]).isEqualTo(w.doctor().userId().toString());
        JsonNode after2 = objectMapper.readTree(audit.get(0)[3]);
        assertThat(after2.get("escalationId").asText()).isEqualTo(w.esc());
        assertThat(after2.get("admissionId").asText()).isEqualTo(w.adm().id());
        assertThat(after2.get("ewsScore").asInt()).isEqualTo(3);
        assertThat(after2.get("ewsRisk").asText()).isEqualTo("MEDIUM");
        assertThat(after2.get("minutesToAcknowledge").asInt()).isZero();
    }

    @Test
    void theOwnerMayAcknowledgeToo() throws Exception {
        Ward w = ward();
        JsonNode row = ackOk(w.owner().auth(), w.esc(), 0).get("escalation");
        assertThat(row.get("status").asText()).isEqualTo("ACK");
        assertThat(row.get("acknowledgedByName").asText()).isEqualTo(w.owner().name());
        assertThat(esc(w.clinic(), w.esc()).ackBy()).isEqualTo(w.owner().userId().toString());
        // the old token style of the earlier tests (token role DOCTOR, membership OWNER) is accepted as well
        Adm second = another(w, high7());
        String id = openEscalationId(w.clinic(), second.id());
        assertThat(ackOk(w.clinic().as("DOCTOR"), id, 0).get("escalation").get("status").asText()).isEqualTo("ACK");
    }

    @Test
    void theAcknowledgeMinutesAreWholeMinutesFromTheStartOfTheResponseClockToTheAcknowledgement() throws Exception {
        Ward w = ward();
        Adm late = admitOne(w.clinic());
        // raised 7 minutes 30 seconds ago, re-opened 2 minutes 30 seconds ago: the clock started at the re-open
        String id = insertEscalation(w.clinic(), late.id(), 7, "HIGH", Instant.now().minusSeconds(450),
                Instant.now().minusSeconds(150), null);
        ackOk(w.doctor().auth(), id, 0);
        assertThat(escEvents(w.clinic(), late.id()).get(0)).isEqualTo("Acknowledged escalation after 2 min");
        Adm old = admitOne(w.clinic());
        String oldId = insertEscalation(w.clinic(), old.id(), 7, "HIGH", Instant.now().minusSeconds(21 * 60 + 30), null, null);
        ackOk(w.doctor().auth(), oldId, 0);
        assertThat(escEvents(w.clinic(), old.id()).get(0)).isEqualTo("Acknowledged escalation after 21 min");
        assertThat(objectMapper.readTree(audit(w.clinic(), "IPD_ESCALATION_ACKNOWLEDGED").get(1)[3])
                .get("minutesToAcknowledge").asInt()).isEqualTo(21);
    }

    // ---------------- Close escalation ----------------

    @Test
    void aDoctorClosesAnOpenEscalationWithEachOfTheFiveActionsAndIsAlsoTheAcknowledgingDoctor() throws Exception {
        Ward w = ward();
        for (int i = 0; i < ACTIONS.length; i++) {
            Adm adm = i == 0 ? w.adm() : another(w, medium3());
            String id = i == 0 ? w.esc() : openEscalationId(w.clinic(), adm.id());
            String code = ACTIONS[i][0];
            String label = ACTIONS[i][1];
            String plan = PLAN + " " + i;
            Instant before = dbNow(w.clinic());
            JsonNode result = respondOk(w.doctor().auth(), id, 0, code, plan);
            Instant after = dbNow(w.clinic());

            assertThat(result.get("replayed").asBoolean()).isFalse();
            JsonNode row = result.get("escalation");
            assertThat(row.get("status").asText()).as(code).isEqualTo("DONE");
            assertThat(row.get("version").asLong()).isOne();
            assertThat(row.get("response").get("action").asText()).isEqualTo(code);
            assertThat(row.get("response").get("actionLabel").asText()).isEqualTo(label);
            assertThat(row.get("response").get("note").asText()).isEqualTo(plan);
            assertThat(row.get("response").get("closedByName").asText()).isEqualTo(w.doctor().name());
            assertThat(row.get("acknowledgedByName").asText()).as("closed from Open: the closer is the acknowledger")
                    .isEqualTo(w.doctor().name());
            assertThat(row.get("pastTarget").asBoolean()).isFalse();
            assertThat(row.get("canAcknowledge").asBoolean()).isFalse();
            assertThat(row.get("canRespond").asBoolean()).isFalse();
            assertThat(row.get("canCallSenior").asBoolean()).isFalse();

            Esc e = esc(w.clinic(), id);
            assertThat(e.status()).isEqualTo("DONE");
            assertThat(e.action()).isEqualTo(code);
            assertThat(e.note()).isEqualTo(plan);
            assertThat(e.closedBy()).isEqualTo(w.doctor().userId().toString());
            assertThat(e.ackBy()).isEqualTo(w.doctor().userId().toString());
            assertThat(values(w.clinic(), "select (acknowledged_at = closed_at)::text from ipd.ews_escalation "
                    + "where id = ?::uuid", id)).containsExactly("true");
            Instant closedAt = instantOf(w.clinic(), "select closed_at from ipd.ews_escalation where id = ?::uuid", id);
            assertThat(closedAt).isBetween(before.minusSeconds(1), after.plusSeconds(1));
            assertThat(instant(row.get("response").get("closedAt"))).isEqualTo(closedAt);

            // the event holds the action only; a close from Open writes no "Acknowledged" event
            List<String> events = escEvents(w.clinic(), adm.id());
            assertThat(events.get(0)).isEqualTo("Escalation closed: " + label);
            assertThat(events).hasSize(2);
            assertThat(events.stream().noneMatch(t -> t.startsWith("Acknowledged"))).isTrue();
            assertThat(String.join("|", eventLines(w.clinic(), adm.id()))).doesNotContain(plan).doesNotContain(w.adm().name());
        }
        List<String[]> audit = audit(w.clinic(), "IPD_ESCALATION_CLOSED");
        assertThat(audit).hasSize(ACTIONS.length);
        JsonNode first = objectMapper.readTree(audit.get(0)[3]);
        assertThat(first.get("action").asText()).isEqualTo("BEDSIDE_REVIEW");
        assertThat(first.get("statusBefore").asText()).isEqualTo("OPEN");
        assertThat(first.get("ewsScore").asInt()).isEqualTo(3);
        assertThat(audit.get(0)[1]).isEqualTo(w.esc());
        assertThat(audit.get(0)[4]).isEqualTo(w.doctor().userId().toString());
        assertThat(audit.get(0)[3]).doesNotContain(PLAN).doesNotContain(w.adm().name());
        assertThat(audit(w.clinic(), "IPD_ESCALATION_ACKNOWLEDGED")).as("no acknowledge audit for a close from Open").isEmpty();
    }

    @Test
    void aClosedRowKeepsTheAcknowledgingDoctorWhenAnotherDoctorClosesAnAcknowledgedEscalation() throws Exception {
        Ward w = ward();
        ackOk(w.doctor().auth(), w.esc(), 0);
        Instant ackAt = instantOf(w.clinic(), "select acknowledged_at from ipd.ews_escalation where id = ?::uuid", w.esc());
        JsonNode row = respondOk(w.owner().auth(), w.esc(), 1, "TREATMENT_CHANGED", PLAN).get("escalation");
        assertThat(row.get("version").asLong()).isEqualTo(2);
        assertThat(row.get("acknowledgedByName").asText()).as("the mockup shows the acknowledging doctor").isEqualTo(w.doctor().name());
        assertThat(row.get("response").get("closedByName").asText()).isEqualTo(w.owner().name());
        Esc e = esc(w.clinic(), w.esc());
        assertThat(e.ackBy()).isEqualTo(w.doctor().userId().toString());
        assertThat(e.closedBy()).isEqualTo(w.owner().userId().toString());
        assertThat(instantOf(w.clinic(), "select acknowledged_at from ipd.ews_escalation where id = ?::uuid", w.esc()))
                .isEqualTo(ackAt);
        assertThat(escEvents(w.clinic(), w.adm().id()).subList(0, 2))
                .containsExactly("Escalation closed: Treatment changed", "Acknowledged escalation after 0 min");
        JsonNode audit = objectMapper.readTree(audit(w.clinic(), "IPD_ESCALATION_CLOSED").get(0)[3]);
        assertThat(audit.get("statusBefore").asText()).isEqualTo("ACK");
        assertThat(audit.get("action").asText()).isEqualTo("TREATMENT_CHANGED");
    }

    // ---------------- Call senior ----------------

    @Test
    void aNurseCallsTheSeniorDoctorEachTapOnFreshDataIsOneCallAndTheVersionDoesNotMove() throws Exception {
        Ward w = ward();
        Staff second = staff(w.clinic(), "NURSE"); // added before the first call: a second nurse for the second call (R4 throttle)
        JsonNode first = callOk(w.nurse().auth(), w.esc(), 0, 0);
        assertThat(first.get("replayed").asBoolean()).isFalse();
        JsonNode row = first.get("escalation");
        assertThat(row.get("seniorCalls").asInt()).isOne();
        assertThat(row.get("version").asLong()).as("a call is not a change that makes a doctor's screen stale").isZero();
        assertThat(row.get("status").asText()).isEqualTo("OPEN");
        assertThat(row.get("canCallSenior").asBoolean()).as("still Open: the nurse may call again").isTrue();
        assertThat(row.get("canAcknowledge").asBoolean()).isFalse();
        assertThat(escEvents(w.clinic(), w.adm().id()).get(0)).isEqualTo("Called the senior doctor: escalation not seen after 0 min");
        assertThat(values(w.clinic(), "select actor::text from ipd.clinical_event where text like 'Called the senior%' "
                + "and admission_id = ?::uuid", w.adm().id())).containsExactly(w.nurse().userId().toString());
        List<String[]> audit = audit(w.clinic(), "IPD_ESCALATION_SENIOR_CALLED");
        assertThat(audit).hasSize(1);
        JsonNode after = objectMapper.readTree(audit.get(0)[3]);
        assertThat(after.get("seniorCalls").asInt()).isOne();
        assertThat(after.get("ewsScore").asInt()).isEqualTo(3);
        assertThat(audit.get(0)[4]).isEqualTo(w.nurse().userId().toString());

        // the same nurse tapping again at once is a throttled tap (ADR-007 R4: one entry per user per escalation per
        // 60 seconds): 200 replayed, nothing written
        Side afterFirst = side(w.clinic());
        JsonNode throttled = callOk(w.nurse().auth(), w.esc(), 0, 1);
        assertThat(throttled.get("replayed").asBoolean()).isTrue();
        assertThat(throttled.get("escalation").get("seniorCalls").asInt()).isOne();
        assertThat(side(w.clinic())).isEqualTo(afterFirst);

        // another nurse taps on the reloaded board: the count she saw is now 1, so this is a second call
        JsonNode secondCall = callOk(second.auth(), w.esc(), 0, 1);
        assertThat(secondCall.get("replayed").asBoolean()).isFalse();
        assertThat(secondCall.get("escalation").get("seniorCalls").asInt()).isEqualTo(2);
        assertThat(esc(w.clinic(), w.esc()).seniorCalls()).isEqualTo(2);
        assertThat(audit(w.clinic(), "IPD_ESCALATION_SENIOR_CALLED")).hasSize(2);

        // a doctor who opened the board before the calls can still acknowledge: the calls did not change the version
        JsonNode acked = ackOk(w.doctor().auth(), w.esc(), 0);
        assertThat(acked.get("escalation").get("status").asText()).isEqualTo("ACK");
        assertThat(acked.get("escalation").get("seniorCalls").asInt()).isEqualTo(2);
        assertThat(acked.get("escalation").get("version").asLong()).isOne();
    }

    @Test
    void theSeniorCallMinutesAreTheWaitingMinutesFromTheStartOfTheResponseClockOnTheDatabaseClock() throws Exception {
        Ward w = ward();
        Adm adm = admitOne(w.clinic());
        String id = insertEscalation(w.clinic(), adm.id(), 7, "HIGH", Instant.now().minusSeconds(3600),
                Instant.now().minusSeconds(17 * 60 + 20), null);
        callOk(w.nurse().auth(), id, 0, 0);
        assertThat(escEvents(w.clinic(), adm.id()).get(0)).isEqualTo("Called the senior doctor: escalation not seen after 17 min");
    }

    // ---------------- stale versions and states ----------------

    @Test
    void aStaleVersionIs409EscalationChangedWithTheCurrentScoreAndNothingIsWritten() throws Exception {
        Ward w = ward();
        save(w.clinic(), w.adm().id(), medium6()); // EWS 3 -> 6, version 0 -> 1
        Side before = side(w.clinic());
        String detail = "The score is now EWS 6 (medium). Review it and try again.";
        for (var attempt : List.of(ack(w.doctor().auth(), w.esc(), 0), respond(w.doctor().auth(), w.esc(), 0, "NO_CHANGE", PLAN),
                callSenior(w.nurse().auth(), w.esc(), 0, 0), ack(w.doctor().auth(), w.esc(), 99),
                ack(w.doctor().auth(), w.esc(), 2))) {
            JsonNode p = conflict(attempt, "escalation-changed");
            assertThat(p.get("detail").asText()).isEqualTo(detail);
            assertThat(p.toString()).doesNotContain(w.adm().name()).doesNotContain(PLAN);
        }
        assertThat(side(w.clinic())).isEqualTo(before);
        assertThat(esc(w.clinic(), w.esc()).status()).isEqualTo("OPEN");
        // with the version the doctor can see now, the same actions work
        assertThat(ackOk(w.doctor().auth(), w.esc(), 1).get("escalation").get("status").asText()).isEqualTo("ACK");
    }

    @Test
    void aReopenedEscalationMakesAnEarlierVersionStaleAndTheMessageNamesTheNewScore() throws Exception {
        Ward w = ward();
        ackOk(w.doctor().auth(), w.esc(), 0); // version 1
        save(w.clinic(), w.adm().id(), high7()); // MEDIUM -> HIGH: re-opened, version 2
        assertThat(openEscalation(w.clinic(), w.adm().id())).isEqualTo("7|HIGH|15|OPEN|2");
        Side before = side(w.clinic());
        JsonNode p = conflict(respond(w.doctor().auth(), w.esc(), 1, "NO_CHANGE", PLAN), "escalation-changed");
        assertThat(p.get("detail").asText()).isEqualTo("The score is now EWS 7 (high). Review it and try again.");
        assertThat(side(w.clinic())).as("a response is never recorded against a score the doctor did not see").isEqualTo(before);
        JsonNode again = ackOk(w.owner().auth(), w.esc(), 2);
        assertThat(again.get("escalation").get("status").asText()).isEqualTo("ACK");
        assertThat(again.get("escalation").get("version").asLong()).isEqualTo(3);
    }

    @Test
    void aStateThatCannotTakeTheActionIs409EscalationStateBeforeAStaleVersionIsLookedAt() throws Exception {
        Ward w = ward();
        ackOk(w.doctor().auth(), w.esc(), 0);
        Side acked = side(w.clinic());
        String by = w.doctor().name() + " already acknowledged this escalation.";
        // Acknowledged by the doctor: the owner's acknowledge and the nurse's call are state answers, with any version
        for (var attempt : List.of(ack(w.owner().auth(), w.esc(), 0), ack(w.owner().auth(), w.esc(), 1),
                ack(w.owner().auth(), w.esc(), 77), callSenior(w.nurse().auth(), w.esc(), 0, 0),
                callSenior(w.nurse().auth(), w.esc(), 1, 0), callSenior(w.nurse().auth(), w.esc(), 5, 5))) {
            JsonNode p = conflict(attempt, "escalation-state");
            assertThat(p.get("detail").asText()).isEqualTo(by);
        }
        // the doctor's own repeat with a version that is not "sent + 1" is not a replay either
        assertThat(conflict(ack(w.doctor().auth(), w.esc(), 5), "escalation-state").get("detail").asText()).isEqualTo(by);
        // a Close that carries an older version, by someone who did not acknowledge: changed, naming the doctor
        JsonNode changed = conflict(respond(w.owner().auth(), w.esc(), 0, "NO_CHANGE", PLAN), "escalation-changed");
        assertThat(changed.get("detail").asText()).isEqualTo(by);
        assertThat(side(w.clinic())).isEqualTo(acked);

        respondOk(w.doctor().auth(), w.esc(), 1, "NO_CHANGE", PLAN);
        Side closed = side(w.clinic());
        for (var attempt : List.of(ack(w.owner().auth(), w.esc(), 0), ack(w.owner().auth(), w.esc(), 2),
                ack(w.doctor().auth(), w.esc(), 1), callSenior(w.nurse().auth(), w.esc(), 2, 0),
                respond(w.owner().auth(), w.esc(), 2, "NO_CHANGE", PLAN), respond(w.owner().auth(), w.esc(), 0, "NO_CHANGE", PLAN),
                respond(w.doctor().auth(), w.esc(), 2, "NO_CHANGE", PLAN + " changed"))) {
            assertThat(conflict(attempt, "escalation-state").get("detail").asText()).isEqualTo(ALREADY_CLOSED);
        }
        assertThat(side(w.clinic())).as("a closed escalation is never edited").isEqualTo(closed);
        assertThat(esc(w.clinic(), w.esc()).note()).isEqualTo(PLAN);
    }

    // ---------------- replays ----------------

    @Test
    void theSamePersonRepeatingAnActionAfterALostAnswerGets200ReplayedAndNothingIsWrittenTwice() throws Exception {
        Ward w = ward();
        // acknowledge
        JsonNode first = ackOk(w.doctor().auth(), w.esc(), 0);
        Side afterFirst = side(w.clinic());
        JsonNode again = ackOk(w.doctor().auth(), w.esc(), 0);
        assertThat(again.get("replayed").asBoolean()).isTrue();
        assertThat(again.get("escalation").get("status").asText()).isEqualTo("ACK");
        assertThat(again.get("escalation").get("version").asLong()).isOne();
        assertThat(again.get("escalation").get("acknowledgedAt")).isEqualTo(first.get("escalation").get("acknowledgedAt"));
        assertThat(side(w.clinic())).as("no event, no audit row, no access row").isEqualTo(afterFirst);
        // someone else repeating the same request is not a replay
        conflict(ack(w.owner().auth(), w.esc(), 0), "escalation-state");

        // close
        respondOk(w.doctor().auth(), w.esc(), 1, "BEDSIDE_REVIEW", PLAN);
        Side afterClose = side(w.clinic());
        JsonNode closedAgain = respondOk(w.doctor().auth(), w.esc(), 1, "BEDSIDE_REVIEW", PLAN);
        assertThat(closedAgain.get("replayed").asBoolean()).isTrue();
        assertThat(closedAgain.get("escalation").get("status").asText()).isEqualTo("DONE");
        assertThat(closedAgain.get("escalation").get("response").get("note").asText()).isEqualTo(PLAN);
        assertThat(side(w.clinic())).isEqualTo(afterClose);
        // the same note with surrounding blanks is the same stored plan; a different plan, action or person is not
        assertThat(respondOk(w.doctor().auth(), w.esc(), 1, "BEDSIDE_REVIEW", "  " + PLAN + "\n").get("replayed").asBoolean()).isTrue();
        conflict(respond(w.doctor().auth(), w.esc(), 1, "BEDSIDE_REVIEW", PLAN + "!"), "escalation-state");
        conflict(respond(w.doctor().auth(), w.esc(), 1, "NO_CHANGE", PLAN), "escalation-state");
        conflict(respond(w.owner().auth(), w.esc(), 1, "BEDSIDE_REVIEW", PLAN), "escalation-state");
        assertThat(side(w.clinic())).isEqualTo(afterClose);
    }

    @Test
    void aSeniorCallRepeatedWithTheCountAlreadyRaisedIsAReplayAndAnotherNurseWithinTheSameRefreshIsSeenButNotCountedAgain()
            throws Exception {
        Ward w = ward();
        Staff other = staff(w.clinic(), "NURSE");
        callOk(w.nurse().auth(), w.esc(), 0, 0);
        Side after = side(w.clinic());
        JsonNode replay = callOk(w.nurse().auth(), w.esc(), 0, 0);
        assertThat(replay.get("replayed").asBoolean()).as("her own call, answer lost").isTrue();
        assertThat(replay.get("escalation").get("seniorCalls").asInt()).isOne();
        assertThat(side(w.clinic())).isEqualTo(after);

        // ADR-007 R4 (security review L3): a second nurse who had not seen the first call is answered 200 replayed, the
        // count stays 1, and one ALREADY_CALLED row and one fixed-text event record that she saw it; no audit row
        JsonNode sameRefresh = callOk(other.auth(), w.esc(), 0, 0);
        assertThat(sameRefresh.get("replayed").asBoolean()).isTrue();
        assertThat(sameRefresh.get("escalation").get("seniorCalls").asInt()).isOne();
        assertThat(esc(w.clinic(), w.esc()).seniorCalls()).isOne();
        assertThat(esc(w.clinic(), w.esc()).version()).isZero();
        Side now = side(w.clinic());
        assertThat(now.events()).isEqualTo(after.events() + 1);
        assertThat(now.audits()).as("no audit row for a call that was not made").isEqualTo(after.audits());
        assertThat(now.access()).isEqualTo(after.access());
        assertThat(escEvents(w.clinic(), w.adm().id()).get(0))
                .isEqualTo("Senior doctor already called · seen by another team member · no new call recorded");
        assertThat(values(w.clinic(), "select actor::text from ipd.clinical_event where text like 'Senior doctor already called%' "
                + "and admission_id = ?::uuid", w.adm().id())).containsExactly(other.userId().toString());
        assertThat(column(w.clinic(), "select called_by::text || '|' || outcome || '|' || coalesce(call_no::text, '-') "
                + "from ipd.ews_senior_call where escalation_id = ?::uuid order by called_at", w.esc()))
                .containsExactly(w.nurse().userId() + "|CALLED|1", other.userId() + "|ALREADY_CALLED|-");
        // a stale version is changed, even when the count differs
        save(w.clinic(), w.adm().id(), medium6());
        conflict(callSenior(w.nurse().auth(), w.esc(), 0, 1), "escalation-changed");
    }

    // ---------------- roles ----------------

    @Test
    void everyRoleOutsideTheSetOfEachActionIs403ForbiddenBeforeTheBodyAndNothingIsWritten() throws Exception {
        Ward w = ward();
        Side before = side(w.clinic());
        for (String role : NON_DOCTOR_ROLES) {
            RequestPostProcessor token = w.clinic().as(role);
            assertType(problem(ack(token, w.esc(), 0), 403), "forbidden");
            assertType(problem(respond(token, w.esc(), 0, "NO_CHANGE", PLAN), 403), "forbidden");
            // the role is checked before the body and before the escalation is looked up
            assertType(problem(call(HttpMethod.POST, ackUrl(w.esc()), token, "{}"), 403), "forbidden");
            assertType(problem(call(HttpMethod.POST, respondUrl(UUID.randomUUID()), token, "{\"version\":\"x\"}"), 403), "forbidden");
        }
        for (String role : NON_NURSE_ROLES) {
            RequestPostProcessor token = w.clinic().as(role);
            assertType(problem(callSenior(token, w.esc(), 0, 0), 403), "forbidden");
            assertType(problem(call(HttpMethod.POST, callUrl(w.esc()), token, "{}"), 403), "forbidden");
        }
        // real people: a nurse cannot acknowledge, a doctor and the owner cannot call the senior
        assertType(problem(ack(w.nurse().auth(), w.esc(), 0), 403), "forbidden");
        assertType(problem(respond(w.nurse().auth(), w.esc(), 0, "NO_CHANGE", PLAN), 403), "forbidden");
        assertType(problem(callSenior(w.doctor().auth(), w.esc(), 0, 0), 403), "forbidden");
        assertType(problem(callSenior(w.owner().auth(), w.esc(), 0, 0), 403), "forbidden");
        assertType(problem(ack(noSession(), w.esc(), 0), 401), "unauthorized");
        assertType(problem(callSenior(noSession(), w.esc(), 0, 0), 401), "unauthorized");
        assertThat(side(w.clinic())).isEqualTo(before);
    }

    @Test
    void aRoleChangedInTheMembershipIsRefusedAtOnceWhileTheTokenStillSaysTheOldRole() throws Exception {
        Ward w = ward();
        Staff doctor = w.doctor();
        Staff nurse = w.nurse();
        Side before = side(w.clinic());
        try {
            for (String demoted : new String[] {"NURSE", "FRONT_DESK", "BILLING"}) {
                setMembershipRole(w.clinic(), doctor.userId(), demoted);
                assertType(problem(ack(doctor.auth(), w.esc(), 0), 403), "forbidden");
                assertType(problem(respond(doctor.auth(), w.esc(), 0, "NO_CHANGE", PLAN), 403), "forbidden");
                assertThat(side(w.clinic())).as("doctor demoted to " + demoted).isEqualTo(before);
            }
            for (String changed : new String[] {"DOCTOR", "FRONT_DESK"}) {
                setMembershipRole(w.clinic(), nurse.userId(), changed);
                assertType(problem(callSenior(nurse.auth(), w.esc(), 0, 0), 403), "forbidden");
                assertThat(side(w.clinic())).as("nurse changed to " + changed).isEqualTo(before);
            }
            // a deactivated or removed person is refused at once too
            setMembershipRole(w.clinic(), nurse.userId(), "NURSE");
            setMembershipRole(w.clinic(), doctor.userId(), "DOCTOR");
            for (String status : new String[] {"INACTIVE", "REMOVED"}) {
                setMembershipStatus(w.clinic().tenantId(), doctor.userId(), status);
                setMembershipStatus(w.clinic().tenantId(), nurse.userId(), status);
                assertType(problem(ack(doctor.auth(), w.esc(), 0), 403), "forbidden");
                assertType(problem(callSenior(nurse.auth(), w.esc(), 0, 0), 403), "forbidden");
            }
            assertThat(side(w.clinic())).isEqualTo(before);
        } finally {
            setMembershipStatus(w.clinic().tenantId(), doctor.userId(), "ACTIVE");
            setMembershipStatus(w.clinic().tenantId(), nurse.userId(), "ACTIVE");
            setMembershipRole(w.clinic(), doctor.userId(), "DOCTOR");
            setMembershipRole(w.clinic(), nurse.userId(), "NURSE");
        }
        // restored: both work again (positive control)
        assertThat(callOk(nurse.auth(), w.esc(), 0, 0).get("escalation").get("seniorCalls").asInt()).isOne();
        assertThat(ackOk(doctor.auth(), w.esc(), 0).get("escalation").get("status").asText()).isEqualTo("ACK");
    }

    // ---------------- other clinics, discharged patients, IPD Off, Read-only ----------------

    @Test
    void anotherClinicsEscalationIs404ForEveryActionAndNothingIsWrittenOrRevealed() throws Exception {
        Ward a = ward();
        Ward b = ward();
        Side beforeA = side(a.clinic());
        Side beforeB = side(b.clinic());
        for (var attempt : List.of(ack(b.doctor().auth(), a.esc(), 0), ack(b.owner().auth(), a.esc(), 0),
                respond(b.doctor().auth(), a.esc(), 0, "NO_CHANGE", PLAN), callSenior(b.nurse().auth(), a.esc(), 0, 0),
                ack(b.doctor().auth(), UUID.randomUUID(), 0), respond(b.owner().auth(), UUID.randomUUID(), 0, "NO_CHANGE", PLAN),
                callSenior(b.nurse().auth(), UUID.randomUUID(), 0, 0))) {
            JsonNode p = problem(attempt, 404);
            assertType(p, "not-found");
            assertThat(p.toString()).doesNotContain(a.adm().name());
        }
        // a path that is not a UUID
        assertThat(problem(call(HttpMethod.POST, IPD + "/escalations/not-a-uuid/acknowledge", b.owner().auth(), "{\"version\":0}"), 400)
                .get("title")).isNotNull();
        assertThat(side(a.clinic())).isEqualTo(beforeA);
        assertThat(side(b.clinic())).isEqualTo(beforeB);
        assertThat(esc(a.clinic(), a.esc()).status()).isEqualTo("OPEN");
        // a good body and an unknown id is a 404, a bad body with an unknown id is a 400 (the body is checked first)
        assertType(problem(call(HttpMethod.POST, ackUrl(UUID.randomUUID()), b.owner().auth(), "{}"), 400), "validation");
    }

    @Test
    void aDischargedPatientsEscalationIs404ForEveryActionEvenWhenItIsClosedAtDischarge() throws Exception {
        Ward w = ward();
        sign(w.clinic(), w.adm().id(), "lama", "general");
        dischargeNow(w.clinic(), w.adm().id(), "lama", "general", "UPI");
        assertThat(esc(w.clinic(), w.esc()).status()).isEqualTo("DONE");
        Side before = side(w.clinic());
        for (var attempt : List.of(ack(w.doctor().auth(), w.esc(), 0), ack(w.doctor().auth(), w.esc(), 1),
                respond(w.owner().auth(), w.esc(), 0, "NO_CHANGE", PLAN), respond(w.owner().auth(), w.esc(), 1, "NO_CHANGE", PLAN),
                callSenior(w.nurse().auth(), w.esc(), 0, 0))) {
            JsonNode p = problem(attempt, 404);
            assertType(p, "not-found");
        }
        assertThat(side(w.clinic())).isEqualTo(before);
    }

    @Test
    void whenIpdIsOffEveryActionIsModuleOffForTheAllowedRolesAndForbiddenForTheOthers() throws Exception {
        Clinic off = newClinic(); // no trial: IPD is Off
        UUID id = UUID.randomUUID();
        for (String role : new String[] {"OWNER", "DOCTOR"}) {
            JsonNode p = problem(ack(off.as(role), id, 0), 403);
            assertType(p, "module-off");
            assertThat(p.get("detail").asText()).isEqualTo(OFF_TEXT);
            assertType(problem(respond(off.as(role), id, 0, "NO_CHANGE", PLAN), 403), "module-off");
        }
        assertType(problem(callSenior(off.as("NURSE"), id, 0, 0), 403), "module-off");
        // the role comes first
        assertType(problem(ack(off.as("NURSE"), id, 0), 403), "forbidden");
        assertType(problem(callSenior(off.as("DOCTOR"), id, 0, 0), 403), "forbidden");
        assertThat(rows(off, "ews_escalation")).isZero();
    }

    @Test
    void inReadOnlyTheThreeActionsStillWorkBecauseTheyRecordCareOwedToAnAdmittedPatient() throws Exception {
        Ward w = ward();
        Adm second = another(w, high7());
        String secondId = openEscalationId(w.clinic(), second.id());
        Adm third = another(w, high7());
        String thirdId = openEscalationId(w.clinic(), third.id());
        expireTrial(w.clinic());
        // saving vitals is blocked, the actions are not
        assertType(problem(saveCall(w.clinic().owner(), w.adm().id(), newKey(low())), 409), "ipd-readonly");
        assertThat(callOk(w.nurse().auth(), w.esc(), 0, 0).get("escalation").get("seniorCalls").asInt()).isOne();
        assertThat(ackOk(w.doctor().auth(), w.esc(), 0).get("escalation").get("status").asText()).isEqualTo("ACK");
        assertThat(respondOk(w.owner().auth(), secondId, 0, "MOVED_ICU_HDU", PLAN).get("escalation").get("status").asText())
                .isEqualTo("DONE");
        JsonNode board = boardOf(w.doctor().auth());
        assertThat(board.get("state").asText()).isEqualTo("readonly");
        assertThat(escRow(board, thirdId).get("canAcknowledge").asBoolean()).isTrue();
        assertThat(escRow(board, thirdId).get("canRespond").asBoolean()).isTrue();
        assertThat(boardOf(w.nurse().auth()).get("escalations").get(0).get("canCallSenior").asBoolean()).isTrue();
    }

    // ---------------- 400: the request bodies ----------------

    private void assertField(RequestPostProcessor auth, String url, String body, String field, String message)
            throws Exception {
        JsonNode p = problem(call(HttpMethod.POST, url, auth, body), 400);
        assertType(p, "validation");
        assertThat(fieldNames(p)).as(body).contains(field);
        assertThat(fieldMessage(p, field)).as(body).isEqualTo(message);
    }

    @Test
    void aVersionThatIsNotAWholeNonNegativeJsonNumberIs400OnTheVersionFieldForTheThreeActions() throws Exception {
        Ward w = ward();
        Side before = side(w.clinic());
        String[] versions = {"null", "\"0\"", "\"3\"", "0.5", "3.0", "1e2", "-1", "true", "[]", "{}", "99999999999999999999", "\"\""};
        for (String v : versions) {
            for (String[] target : new String[][] {{ackUrl(w.esc()), "{\"version\":" + v + "}", "doctor"},
                    {respondUrl(w.esc()), "{\"version\":" + v + ",\"action\":\"NO_CHANGE\",\"note\":\"Plan\"}", "doctor"},
                    {callUrl(w.esc()), "{\"version\":" + v + ",\"seniorCalls\":0}", "nurse"}}) {
                RequestPostProcessor auth = "doctor".equals(target[2]) ? w.doctor().auth() : w.nurse().auth();
                JsonNode p = problem(call(HttpMethod.POST, target[0], auth, target[1]), 400);
                assertType(p, "validation");
                assertThat(fieldMessage(p, "version")).as(target[1]).isEqualTo(RELOAD);
            }
        }
        // missing fields, an empty body and text that is not JSON
        assertField(w.doctor().auth(), ackUrl(w.esc()), "{}", "version", RELOAD);
        assertField(w.nurse().auth(), callUrl(w.esc()), "{\"version\":0}", "seniorCalls", RELOAD);
        assertField(w.nurse().auth(), callUrl(w.esc()), "{\"version\":0,\"seniorCalls\":-1}", "seniorCalls", RELOAD);
        assertField(w.nurse().auth(), callUrl(w.esc()), "{\"version\":0,\"seniorCalls\":\"0\"}", "seniorCalls", RELOAD);
        assertField(w.nurse().auth(), callUrl(w.esc()), "{\"version\":0,\"seniorCalls\":1.5}", "seniorCalls", RELOAD);
        assertField(w.nurse().auth(), callUrl(w.esc()), "{\"version\":0,\"seniorCalls\":3000000000}", "seniorCalls", RELOAD);
        for (String bad : new String[] {"", "not json", "[", "\"text\""}) {
            assertThat(problem(call(HttpMethod.POST, ackUrl(w.esc()), w.doctor().auth(), bad), 400).get("title")).isNotNull();
        }
        assertThat(side(w.clinic())).isEqualTo(before);
        // the largest version a long can hold is a well-formed number: stale, not invalid
        conflict(ack(w.doctor().auth(), w.esc(), Long.MAX_VALUE), "escalation-changed");
    }

    @Test
    void theResponseBodyIsCheckedFieldByFieldAndNothingIsSavedOrEchoed() throws Exception {
        Ward w = ward();
        Side before = side(w.clinic());
        // action
        for (String action : new String[] {null, "", "bogus", "CLOSED_AT_DISCHARGE", "Reviewed at the bedside", "bedside review"}) {
            JsonNode p = problem(respond(w.doctor().auth(), w.esc(), 0, action, PLAN), 400);
            assertThat(fieldNames(p)).as(String.valueOf(action)).containsExactly("action");
            assertThat(fieldMessage(p, "action")).isEqualTo(CHOOSE_ACTION);
            assertThat(p.get("detail").asText()).isEqualTo(CHOOSE_ACTION);
        }
        // the plan
        String secret = "SECRETPLAN-" + UUID.randomUUID();
        Object[][] notes = {{null, PLAN_REQUIRED}, {"", PLAN_REQUIRED}, {"   ", PLAN_REQUIRED}, {"\n\t \r\n", PLAN_REQUIRED},
                {"a".repeat(1001), PLAN_LONG}, {"x".repeat(1001) + secret, PLAN_LONG}, {"😀".repeat(1001), PLAN_LONG},
                {"a".repeat(4001), PLAN_LONG}, {" ".repeat(4001), PLAN_LONG},
                {secret + "\u0007", PLAN_CHARS}, {secret + "\u0000", PLAN_CHARS}, {secret + "‮", PLAN_CHARS},
                {secret + "⁦", PLAN_CHARS}, {secret + "\u001B[31m", PLAN_CHARS}, {secret + "\u0085", PLAN_CHARS}};
        for (Object[] n : notes) {
            JsonNode p = problem(respond(w.doctor().auth(), w.esc(), 0, "NO_CHANGE", (String) n[0]), 400);
            assertThat(fieldNames(p)).as(String.valueOf(n[0]).length() + "").containsExactly("note");
            assertThat(fieldMessage(p, "note")).isEqualTo(n[1]);
            assertThat(p.toString()).as("the typed plan is never echoed").doesNotContain(secret);
        }
        // every field error together, in the order version, action, note; the detail is the first message
        JsonNode all = problem(call(HttpMethod.POST, respondUrl(w.esc()), w.doctor().auth(),
                "{\"version\":\"x\",\"action\":\"bogus\",\"note\":\"\"}"), 400);
        assertThat(fieldNames(all)).containsExactly("version", "action", "note");
        assertThat(all.get("detail").asText()).isEqualTo(RELOAD);
        JsonNode noAction = problem(call(HttpMethod.POST, respondUrl(w.esc()), w.doctor().auth(), "{\"version\":0}"), 400);
        assertThat(fieldNames(noAction)).containsExactly("action", "note");
        assertThat(side(w.clinic())).isEqualTo(before);
        assertThat(esc(w.clinic(), w.esc()).status()).isEqualTo("OPEN");
    }

    @Test
    void thePlanIsStoredAfterTheRulesNfcLineBreaksTabsAndStrippingAndTheEdgesOfTheLengthAreAccepted() throws Exception {
        Ward w = ward();
        Object[][] cases = {
                {"a".repeat(1000), "a".repeat(1000)},
                {"😀".repeat(1000), "😀".repeat(1000)},
                {"x", "x"},
                {" ".repeat(3000) + "x" + " ".repeat(500), "x"},
                {"Café\tO2\r\nrepeat\rvitals\n\nnow ", "Café O2\nrepeat\nvitals\n\nnow"},
                {"Oxygen · 6 L – repeat हिंदी", "Oxygen · 6 L – repeat हिंदी"}};
        for (Object[] c : cases) {
            Adm adm = another(w, medium3());
            String id = openEscalationId(w.clinic(), adm.id());
            JsonNode row = respondOk(w.doctor().auth(), id, 0, "NO_CHANGE", (String) c[0]).get("escalation");
            assertThat(row.get("response").get("note").asText()).isEqualTo(c[1]);
            assertThat(esc(w.clinic(), id).note()).isEqualTo(c[1]);
        }
    }

    @Test
    void aPlanOfOnlyNoBreakSpacesAndOtherSpaceSeparatorsIsEmptyAndRefusedWithTheSameMessage() throws Exception {
        // ADR-007 R6 (code review 3): String.strip() keeps U+00A0, U+2007 and U+202F; the server must treat every white space
        // and every space separator (category Zs) as blank. Nothing is saved, the escalation stays Open.
        Ward w = ward();
        Side before = side(w.clinic());
        String[] blanks = {"\u00A0", "\u2007", "\u202F", "\u3000", "\u1680", "\u2000", "\u2001", "\u2002", "\u2003", "\u2004",
                "\u2005", "\u2006", "\u2008", "\u2009", "\u200A", "\u205F",
                "\u00A0\u2007\u202F\u3000", "\u00A0".repeat(1500), "\u202F".repeat(3000),
                "\u00A0\n\u202F\r\n\u2007\t\u3000 ", " \u00A0 \u00A0 ", "\n\u00A0\n", "\u00A0 \u00A0\r\u00A0"};
        for (String blank : blanks) {
            JsonNode p = problem(respond(w.doctor().auth(), w.esc(), 0, "NO_CHANGE", blank), 400);
            assertThat(fieldNames(p)).as("U+" + Integer.toHexString(blank.codePointAt(0)) + " x" + blank.length()).containsExactly("note");
            assertThat(fieldMessage(p, "note")).isEqualTo(PLAN_REQUIRED);
        }
        assertThat(side(w.clinic())).isEqualTo(before);
        assertThat(esc(w.clinic(), w.esc()).status()).isEqualTo("OPEN");

        // a real word between the spaces is a plan: the ends are stripped, the inner no-break space is kept, and the
        // length is counted after stripping (a thousand spaces on each side do not count)
        Adm adm = another(w, medium3());
        String id = openEscalationId(w.clinic(), adm.id());
        String padded = "\u00A0".repeat(1000) + "O2\u00A0up" + "\u202F".repeat(1000);
        JsonNode row = respondOk(w.doctor().auth(), id, 0, "NO_CHANGE", padded).get("escalation");
        assertThat(row.get("response").get("note").asText()).isEqualTo("O2\u00A0up");
        assertThat(esc(w.clinic(), id).note()).isEqualTo("O2\u00A0up");
    }

    @Test
    void theBodyIsCheckedBeforeTheEscalationIsLookedUpAndAGoodBodyForAnUnknownIdIs404() throws Exception {
        Ward w = ward();
        UUID unknown = UUID.randomUUID();
        assertType(problem(call(HttpMethod.POST, ackUrl(unknown), w.doctor().auth(), "{}"), 400), "validation");
        assertType(problem(ack(w.doctor().auth(), unknown, 0), 404), "not-found");
        assertType(problem(respond(w.doctor().auth(), unknown, 0, "bogus", PLAN), 400), "validation");
        assertType(problem(respond(w.doctor().auth(), unknown, 0, "NO_CHANGE", PLAN), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, callUrl(unknown), w.nurse().auth(), "{\"version\":0}"), 400), "validation");
        assertType(problem(callSenior(w.nurse().auth(), unknown, 0, 0), 404), "not-found");
    }

    // ---------------- every statement is accepted by the trigger ----------------

    @Test
    void aWholeLifeOfEscalationsThroughTheApiNeverHitsTheTriggerOrACheck() throws Exception {
        // Every path through the services in one run: call, update inside Open, acknowledge, update inside Acknowledged,
        // re-open, acknowledge, a higher High score inside Acknowledged, close; a second escalation closed from Open; a
        // third closed at discharge. A trigger or check hit would be a 500.
        Ward w = ward();
        callOk(w.nurse().auth(), w.esc(), 0, 0);
        save(w.clinic(), w.adm().id(), medium5());                     // Open, 3 -> 5, version 1
        callOk(w.nurse().auth(), w.esc(), 1, 1);
        ackOk(w.doctor().auth(), w.esc(), 1);                          // Acknowledged, version 2
        save(w.clinic(), w.adm().id(), medium6());                     // inside Acknowledged, 5 -> 6, version 3
        assertThat(openEscalation(w.clinic(), w.adm().id())).isEqualTo("6|MEDIUM|60|ACK|3");
        save(w.clinic(), w.adm().id(), high7());                       // Medium -> High: re-opened, version 4
        assertThat(openEscalation(w.clinic(), w.adm().id())).isEqualTo("7|HIGH|15|OPEN|4");
        ackOk(w.owner().auth(), w.esc(), 4);                           // version 5
        save(w.clinic(), w.adm().id(), high14());                      // High -> higher High inside Acknowledged, version 6
        assertThat(openEscalation(w.clinic(), w.adm().id())).isEqualTo("14|HIGH|15|ACK|6");
        respondOk(w.owner().auth(), w.esc(), 6, "RAPID_RESPONSE", PLAN);
        assertThat(esc(w.clinic(), w.esc()).status()).isEqualTo("DONE");
        assertThat(esc(w.clinic(), w.esc()).version()).isEqualTo(7);

        save(w.clinic(), w.adm().id(), medium3());                     // a new escalation after the close
        String second = openEscalationId(w.clinic(), w.adm().id());
        assertThat(second).isNotEqualTo(w.esc());
        respondOk(w.doctor().auth(), second, 0, "NO_CHANGE", PLAN);
        save(w.clinic(), w.adm().id(), medium3());                     // and a third, closed by the discharge
        String third = openEscalationId(w.clinic(), w.adm().id());
        sign(w.clinic(), w.adm().id(), "referred", "general");
        dischargeNow(w.clinic(), w.adm().id(), "referred", "general", "UPI");
        assertThat(escalationCount(w.clinic(), w.adm().id())).isEqualTo(3);
        assertThat(openEscalationCount(w.clinic(), w.adm().id())).isZero();
        assertThat(esc(w.clinic(), third).action()).isEqualTo("CLOSED_AT_DISCHARGE");
    }
}
