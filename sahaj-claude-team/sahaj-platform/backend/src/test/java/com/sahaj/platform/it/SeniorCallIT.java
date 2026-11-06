package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SAH-33 review fix R4 (ADR-007, security review L2 and L3, code review item 11) over HTTP on the real {@code sahaj_test}
 * database: Call senior with the actor-tied replay, the "already called" entry, the 60-second throttle per user and
 * escalation, the stored-lower-than-sent refusal and the cap at 32,767.
 *
 * <p>Time: the application cannot write a time and {@code ipd.ews_senior_call} is append-only, so an entry from the past
 * cannot be made by waiting for 60 seconds or by updating a row. The test seam is the table owner: a past entry is
 * <em>inserted</em> with a chosen {@code called_at} (the insert is allowed; the table owner has no row level security
 * switched on for the statement), and the stored count of the escalation is set with the guard trigger switched off inside
 * the same transaction. The request then runs through the whole real stack and the throttle is read against the database
 * clock. Entries are placed a safe 10 seconds either side of the 60-second limit. Test plan: docs/test-plans/SAH-33.md.
 */
@OnTestDatabase
class SeniorCallIT extends SafetyTestSupport {

    private static final String CAP_TEXT = "This escalation cannot record more senior calls. Phone the senior doctor now.";
    private static final String SEEN = "Senior doctor already called · seen by another team member · no new call recorded";

    private record Ward(Clinic clinic, Staff doctor, Staff nurse, Staff second, Staff third, Adm adm, String esc) {
    }

    private Ward ward() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        String esc = raise(clinic, adm, medium3());
        // the free plan allows five users: the owner (who may acknowledge and close like a doctor), the clinic's own doctor and
        // three nurses
        return new Ward(clinic, ownerStaff(clinic), staff(clinic, "NURSE"), staff(clinic, "NURSE"), staff(clinic, "NURSE"), adm, esc);
    }

    private long calls(Ward w) throws Exception {
        return count(w.clinic(), "select count(*) from ipd.ews_senior_call where escalation_id = '" + w.esc() + "'::uuid");
    }

    private List<String> callRows(Ward w) throws Exception {
        return column(w.clinic(), "select called_by::text || '|' || outcome || '|' || coalesce(call_no::text, '-') "
                + "from ipd.ews_senior_call where escalation_id = ?::uuid order by called_at, id", w.esc());
    }

    private static Instant ago(long seconds) {
        return Instant.now().minusSeconds(seconds);
    }

    // ---------------- the throttle ----------------

    @Test
    void theSameNurseTappingAgainWithinSixtySecondsIsAnsweredLikeAReplayAndNothingIsWritten() throws Exception {
        Ward w = ward();
        Instant before = dbNow(w.clinic());
        JsonNode first = callOk(w.nurse().auth(), w.esc(), 0, 0);
        Instant after = dbNow(w.clinic());
        assertThat(first.get("replayed").asBoolean()).isFalse();
        assertThat(callRows(w)).containsExactly(w.nurse().userId() + "|CALLED|1");
        Instant calledAt = instantOf(w.clinic(), "select called_at from ipd.ews_senior_call where escalation_id = ?::uuid", w.esc());
        assertThat(calledAt).as("called_at is the database clock").isBetween(before.minusMillis(5), after.plusMillis(5));

        Side side = side(w.clinic());
        // fresh data (the count she saw is now 1), or the old count: both are the same throttled tap
        for (int sent : new int[] {1, 0}) {
            JsonNode tap = callOk(w.nurse().auth(), w.esc(), 0, sent);
            assertThat(tap.get("replayed").asBoolean()).as("sent " + sent).isTrue();
            assertThat(tap.get("escalation").get("seniorCalls").asInt()).isOne();
            assertThat(side(w.clinic())).as("sent " + sent).isEqualTo(side);
            assertThat(calls(w)).isOne();
        }
        assertThat(audit(w.clinic(), "IPD_ESCALATION_SENIOR_CALLED")).hasSize(1);
    }

    @Test
    void theThrottleIsPerUserAndPerEscalationAndTheWindowEdgeIsSixtySecondsOnTheDatabaseClock() throws Exception {
        Ward w = ward();
        // two earlier calls by two nurses: number 1 seventy seconds ago, number 2 fifty seconds ago
        insertSeniorCall(w.clinic(), w.esc(), w.second().userId(), "CALLED", 1, ago(70));
        insertSeniorCall(w.clinic(), w.esc(), w.nurse().userId(), "CALLED", 2, ago(50));
        forceSeniorCalls(w.clinic(), w.esc(), 2);

        Side side = side(w.clinic());
        // 50 seconds old: inside the window, throttled
        JsonNode inside = callOk(w.nurse().auth(), w.esc(), 0, 2);
        assertThat(inside.get("replayed").asBoolean()).isTrue();
        assertThat(side(w.clinic())).isEqualTo(side);
        assertThat(esc(w.clinic(), w.esc()).seniorCalls()).isEqualTo(2);

        // a third nurse has no entry: her call is made (the throttle is per user)
        JsonNode third = callOk(w.third().auth(), w.esc(), 0, 2);
        assertThat(third.get("replayed").asBoolean()).isFalse();
        assertThat(esc(w.clinic(), w.esc()).seniorCalls()).isEqualTo(3);

        // 70 seconds old: outside the window, the call is made
        JsonNode outside = callOk(w.second().auth(), w.esc(), 0, 3);
        assertThat(outside.get("replayed").asBoolean()).isFalse();
        assertThat(outside.get("escalation").get("seniorCalls").asInt()).isEqualTo(4);
        assertThat(callRows(w)).hasSize(4).endsWith(w.second().userId() + "|CALLED|4");
        assertThat(audit(w.clinic(), "IPD_ESCALATION_SENIOR_CALLED")).hasSize(2);

        // the throttle is per escalation: an entry on this one does not throttle her on another admission
        Adm other = admitOne(w.clinic());
        String otherEsc = raise(w.clinic(), other, medium3());
        JsonNode elsewhere = callOk(w.nurse().auth(), otherEsc, 0, 0);
        assertThat(elsewhere.get("replayed").asBoolean()).isFalse();
    }

    @Test
    void aSeenEntryIsThrottledToo() throws Exception {
        Ward w = ward();
        callOk(w.nurse().auth(), w.esc(), 0, 0);                           // CALLED by the first nurse
        JsonNode seen = callOk(w.second().auth(), w.esc(), 0, 0);          // she had not seen it: ALREADY_CALLED
        assertThat(seen.get("replayed").asBoolean()).isTrue();
        Side side = side(w.clinic());
        // her next taps within 60 seconds, with the old or the new count, write nothing
        for (int sent : new int[] {0, 1}) {
            assertThat(callOk(w.second().auth(), w.esc(), 0, sent).get("replayed").asBoolean()).isTrue();
        }
        assertThat(side(w.clinic())).isEqualTo(side);
        assertThat(callRows(w)).containsExactly(w.nurse().userId() + "|CALLED|1", w.second().userId() + "|ALREADY_CALLED|-");
    }

    @Test
    void aSeenEntryOlderThanSixtySecondsIsNotThrottledAndWritesAnotherSeenEntry() throws Exception {
        Ward w = ward();
        callOk(w.nurse().auth(), w.esc(), 0, 0);
        insertSeniorCall(w.clinic(), w.esc(), w.second().userId(), "ALREADY_CALLED", null, ago(70));
        JsonNode seen = callOk(w.second().auth(), w.esc(), 0, 0);
        assertThat(seen.get("replayed").asBoolean()).isTrue();
        assertThat(callRows(w)).hasSize(3).endsWith(w.second().userId() + "|ALREADY_CALLED|-");
        assertThat(escEvents(w.clinic(), w.adm().id()).get(0)).isEqualTo(SEEN);
        assertThat(esc(w.clinic(), w.esc()).seniorCalls()).isOne();
    }

    // ---------------- the replay of her own call ----------------

    @Test
    void herOwnCallAboveTheSentCountIsAReplayEvenAfterTheWindowAndAnotherNursesCallAboveItIsASeenEntry() throws Exception {
        Ward w = ward();
        // the first nurse called (number 1) two minutes ago, the second nurse called (number 2) ninety seconds ago
        insertSeniorCall(w.clinic(), w.esc(), w.nurse().userId(), "CALLED", 1, ago(120));
        insertSeniorCall(w.clinic(), w.esc(), w.second().userId(), "CALLED", 2, ago(90));
        forceSeniorCalls(w.clinic(), w.esc(), 2);
        Side side = side(w.clinic());

        // she sends 0: her own call number 1 is above it, so the answer to her lost call is repeated, nothing is written
        JsonNode own = callOk(w.nurse().auth(), w.esc(), 0, 0);
        assertThat(own.get("replayed").asBoolean()).isTrue();
        assertThat(own.get("escalation").get("seniorCalls").asInt()).isEqualTo(2);
        assertThat(side(w.clinic())).as("her own call is not written again").isEqualTo(side);

        // she sends 1: her own number is not above 1, the call above it is the other nurse's: a seen entry
        JsonNode seen = callOk(w.nurse().auth(), w.esc(), 0, 1);
        assertThat(seen.get("replayed").asBoolean()).isTrue();
        Side after = side(w.clinic());
        assertThat(after.events()).isEqualTo(side.events() + 1);
        assertThat(after.audits()).isEqualTo(side.audits());
        assertThat(callRows(w)).hasSize(3).endsWith(w.nurse().userId() + "|ALREADY_CALLED|-");

        // a third nurse who sees nothing of it sends 0: also a seen entry, never a second call
        JsonNode third = callOk(w.third().auth(), w.esc(), 0, 0);
        assertThat(third.get("replayed").asBoolean()).isTrue();
        assertThat(esc(w.clinic(), w.esc()).seniorCalls()).isEqualTo(2);
        assertThat(esc(w.clinic(), w.esc()).version()).isZero();
        assertThat(callRows(w)).hasSize(4).endsWith(w.third().userId() + "|ALREADY_CALLED|-");
    }

    @Test
    void aSeenEntryHasTheFixedTextNoNameAndTheNurseAsActorAndNoAuditRow() throws Exception {
        Ward w = ward();
        callOk(w.nurse().auth(), w.esc(), 0, 0);
        int audits = audit(w.clinic(), "IPD_ESCALATION_SENIOR_CALLED").size();
        callOk(w.second().auth(), w.esc(), 0, 0);
        assertThat(escEvents(w.clinic(), w.adm().id()).get(0)).isEqualTo(SEEN);
        assertThat(escEvents(w.clinic(), w.adm().id()).get(0)).doesNotContain(w.nurse().name()).doesNotContain(w.second().name());
        assertThat(values(w.clinic(), "select kind || '|' || actor::text from ipd.clinical_event where text = ? and admission_id = ?::uuid",
                SEEN, w.adm().id())).containsExactly("esc|" + w.second().userId());
        assertThat(audit(w.clinic(), "IPD_ESCALATION_SENIOR_CALLED")).hasSize(audits);
    }

    // ---------------- a sent count above the stored one ----------------

    @Test
    void aSentCountAboveTheStoredCountIs409EscalationChangedAndNothingIsWritten() throws Exception {
        Ward w = ward();
        Side side = side(w.clinic());
        for (int sent : new int[] {1, 2, 5, 32_767}) {
            JsonNode p = conflict(callSenior(w.nurse().auth(), w.esc(), 0, sent), "escalation-changed");
            assertThat(p.get("detail").asText()).as("sent " + sent).isEqualTo("The score is now EWS 3 (medium). Review it and try again.");
            assertThat(side(w.clinic())).as("sent " + sent).isEqualTo(side);
        }
        assertThat(calls(w)).isZero();

        // after a real call (stored 1), a sent 2 is just as impossible
        callOk(w.nurse().auth(), w.esc(), 0, 0);
        Side afterCall = side(w.clinic());
        conflict(callSenior(w.second().auth(), w.esc(), 0, 2), "escalation-changed");
        assertThat(side(w.clinic())).isEqualTo(afterCall);
        // and the nurse who made the call is refused before the throttle looks at her entry
        conflict(callSenior(w.nurse().auth(), w.esc(), 0, 2), "escalation-changed");
        assertThat(side(w.clinic())).isEqualTo(afterCall);
        // a negative or non-integer count is a 400 as before
        problem(callSenior(w.second().auth(), w.esc(), 0, -1), 400);
        problem(callSenior(w.second().auth(), w.esc(), 0, "\"1\""), 400);
        assertThat(side(w.clinic())).isEqualTo(afterCall);
    }

    // ---------------- the cap ----------------

    @Test
    void atTheCapOf32767CallsTheNextCallIs409EscalationStateAndNothingIsWritten() throws Exception {
        Ward w = ward();
        forceSeniorCalls(w.clinic(), w.esc(), 32_766);

        // 32,767 is the last count the smallint holds: this call is accepted
        JsonNode last = callOk(w.nurse().auth(), w.esc(), 0, 32_766);
        assertThat(last.get("replayed").asBoolean()).isFalse();
        assertThat(last.get("escalation").get("seniorCalls").asInt()).isEqualTo(32_767);
        assertThat(callRows(w)).containsExactly(w.nurse().userId() + "|CALLED|32767");

        // the next nurse sends 32,767 (equal): the cap answer, nothing written, no 500
        Side side = side(w.clinic());
        JsonNode p = conflict(callSenior(w.second().auth(), w.esc(), 0, 32_767), "escalation-state");
        assertThat(p.get("detail").asText()).isEqualTo(CAP_TEXT);
        assertThat(side(w.clinic())).isEqualTo(side);
        assertThat(esc(w.clinic(), w.esc()).seniorCalls()).isEqualTo(32_767);

        // the throttle is looked at before the cap: the nurse who made the last call is answered like a replay
        assertThat(callOk(w.nurse().auth(), w.esc(), 0, 32_767).get("replayed").asBoolean()).isTrue();
        assertThat(side(w.clinic())).isEqualTo(side);
        // a stale count below the cap is a seen entry, as for any count
        assertThat(callOk(w.second().auth(), w.esc(), 0, 32_000).get("replayed").asBoolean()).isTrue();
        assertThat(callRows(w)).hasSize(2).endsWith(w.second().userId() + "|ALREADY_CALLED|-");

        // the doctor can still work with the escalation: the cap is only about calls
        ackOk(w.doctor().auth(), w.esc(), 0);
        assertThat(esc(w.clinic(), w.esc()).status()).isEqualTo("ACK");
        assertThat(esc(w.clinic(), w.esc()).seniorCalls()).isEqualTo(32_767);
    }

    // ---------------- states and roles stay as they were ----------------

    @Test
    void anAcknowledgedOrClosedEscalationStillRefusesTheCallBeforeTheThrottleOrTheCountLooksAtIt() throws Exception {
        Ward w = ward();
        callOk(w.nurse().auth(), w.esc(), 0, 0);
        ackOk(w.doctor().auth(), w.esc(), 0);
        Side side = side(w.clinic());
        assertThat(conflict(callSenior(w.nurse().auth(), w.esc(), 1, 1), "escalation-state").get("detail").asText())
                .endsWith("already acknowledged this escalation.");
        assertThat(conflict(callSenior(w.second().auth(), w.esc(), 1, 0), "escalation-state").get("detail").asText())
                .endsWith("already acknowledged this escalation.");
        respondOk(w.doctor().auth(), w.esc(), 1, "NO_CHANGE", "Reviewed, repeat vitals in 1 hour");
        Side closed = side(w.clinic());
        assertThat(conflict(callSenior(w.nurse().auth(), w.esc(), 2, 1), "escalation-state").get("detail").asText())
                .isEqualTo(ALREADY_CLOSED);
        assertThat(side(w.clinic())).isEqualTo(closed);
    }

    @Test
    void aStaleVersionStillComesFirstAndAnotherClinicsEscalationIs404() throws Exception {
        Ward w = ward();
        save(w.clinic(), w.adm().id(), medium6());                         // version 1
        Side side = side(w.clinic());
        conflict(callSenior(w.nurse().auth(), w.esc(), 0, 0), "escalation-changed");
        assertThat(side(w.clinic())).isEqualTo(side);

        Ward other = ward();
        assertType(problem(callSenior(other.nurse().auth(), w.esc(), 1, 0), 404), "not-found");
        assertThat(calls(w)).isZero();
    }
}
