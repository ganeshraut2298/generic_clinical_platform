package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-33 review fix R1 (ADR-007, code review item 2) on the real {@code sahaj_test} database: {@code SafetyCounts.changeToken}
 * is one figure on the board, the summary and the banner of the admitted list; it is worked out here from the rule (the
 * escalations counted in {@code open} sorted by id, "id:version" lines, SHA-256, first 16 bytes, base64url) and not read
 * from the application; it changes when an escalation is raised, acknowledged, closed, re-opened or its score rises, it does
 * not change on a senior call or when only minutes pass, and it catches the case the counts miss (one overdue escalation
 * closes while another becomes overdue, the counts stay equal). Test plan: docs/test-plans/SAH-33.md.
 */
@OnTestDatabase
class SafetyChangeTokenIT extends SafetyTestSupport {

    private record Ward(Clinic clinic, Staff owner, Staff doctor, Staff nurse) {
    }

    private Ward ward() throws Exception {
        Clinic clinic = hospital();
        return new Ward(clinic, ownerStaff(clinic), staff(clinic, "DOCTOR"), staff(clinic, "NURSE"));
    }

    /** The three places the token is served for one reader: board, summary, banner of the admitted list. */
    private List<String> tokens(Ward w, Staff s) throws Exception {
        List<String> out = new ArrayList<>();
        out.add(boardOf(s.auth()).get("counts").get("changeToken").asText());
        out.add(summaryOf(s.auth()).get("changeToken").asText());
        out.add(ok(HttpMethod.GET, ADMISSIONS, s.auth(), null, 200).get("safety").get("counts").get("changeToken").asText());
        return out;
    }

    private String token(Ward w) throws Exception {
        List<String> all = tokens(w, w.owner());
        assertThat(all).as("board, summary and banner carry one token").containsOnly(all.get(0));
        return all.get(0);
    }

    @Test
    void aClinicWithNothingToWatchHasTheTokenOfTheEmptyText() throws Exception {
        Ward w = ward();
        admitOne(w.clinic()); // an admitted patient with no escalation
        assertThat(token(w)).isEqualTo(EMPTY_TOKEN).isEqualTo(expectedChangeToken(w.clinic()));
        assertThat(token(w)).matches("[A-Za-z0-9_-]{22}");
    }

    @Test
    void theBoardTheSummaryAndTheBannerCarryTheSameTokenForEveryReaderAndItIsTheOneWorkedOutFromTheRule() throws Exception {
        Ward w = ward();
        Adm a = admitOne(w.clinic());
        Adm b = admitOne(w.clinic());
        Adm c = admitOne(w.clinic());
        raise(w.clinic(), a, medium3());
        raise(w.clinic(), b, high7());
        String expected = expectedChangeToken(w.clinic());
        assertThat(expected).isNotEqualTo(EMPTY_TOKEN);
        for (Staff s : new Staff[] {w.owner(), w.doctor(), w.nurse()}) {
            assertThat(tokens(w, s)).as(s.role()).containsOnly(expected);
        }
        // a third patient without an escalation, and a closed escalation, are not in the token
        String closedOne = raise(w.clinic(), c, medium3());
        respondOk(w.doctor().auth(), closedOne, 0, "NO_CHANGE", "Reviewed, repeat vitals in 1 hour");
        assertThat(tokens(w, w.nurse())).containsOnly(expectedChangeToken(w.clinic()));
        assertThat(token(w)).as("a closed escalation is not in the token: raising and closing one changes nothing").isEqualTo(expected);
    }

    @Test
    void theTokenChangesWhenAnEscalationIsRaisedAcknowledgedReopenedScoreRaisedAndClosed() throws Exception {
        Ward w = ward();
        Adm a = admitOne(w.clinic());
        Adm b = admitOne(w.clinic());
        List<String> seen = new ArrayList<>();
        seen.add(token(w));

        String ea = raise(w.clinic(), a, medium3());               // raised
        seen.add(token(w));
        raise(w.clinic(), b, medium3());                           // a second one raised
        seen.add(token(w));
        ackOk(w.doctor().auth(), ea, 0);                           // acknowledged (version 1)
        seen.add(token(w));
        save(w.clinic(), a.id(), newKeyed(medium5()));             // score rises inside the same risk (version 2)
        seen.add(token(w));
        save(w.clinic(), a.id(), newKeyed(high7()));               // Medium to High: re-opened (version 3)
        seen.add(token(w));
        respondOk(w.doctor().auth(), ea, 3, "TREATMENT_CHANGED", "Oxygen up, repeat vitals in 30 minutes");
        seen.add(token(w));                                        // closed

        assertThat(seen).as("every one of the seven states has its own token").doesNotHaveDuplicates();
        assertThat(seen.get(0)).isEqualTo(EMPTY_TOKEN);
        // the token always equals the rule applied to the database at that moment
        assertThat(seen.get(seen.size() - 1)).isEqualTo(expectedChangeToken(w.clinic()));
    }

    @Test
    void aSeniorCallAndTheMinutesPassingDoNotChangeTheToken() throws Exception {
        Ward w = ward();
        Adm a = admitOne(w.clinic());
        String id = raise(w.clinic(), a, medium3());
        String before = token(w);
        Staff other = staff(w.clinic(), "NURSE");
        callOk(w.nurse().auth(), id, 0, 0);                                    // a call: count 1, version unchanged
        assertThat(token(w)).isEqualTo(before);
        callOk(other.auth(), id, 0, 0);                                        // another nurse: "already called" entry
        assertThat(token(w)).isEqualTo(before);
        callOk(w.nurse().auth(), id, 0, 1);                                    // a throttled tap
        assertThat(token(w)).isEqualTo(before);
        assertThat(esc(w.clinic(), id).version()).isZero();
        // reading again later gives the same token (nothing in it depends on the time)
        assertThat(token(w)).isEqualTo(before);
    }

    @Test
    void aRefusedActionLeavesTheTokenAsItWas() throws Exception {
        Ward w = ward();
        Adm a = admitOne(w.clinic());
        String id = raise(w.clinic(), a, medium3());
        ackOk(w.doctor().auth(), id, 0);
        String before = token(w);
        conflict(ack(w.owner().auth(), id, 1), "escalation-state");
        conflict(respond(w.doctor().auth(), id, 0, "NO_CHANGE", "Plan"), "escalation-changed");
        assertThat(token(w)).isEqualTo(before);
    }

    @Test
    void theTokenSeesWhatTheCountsMissWhenOneOverdueEscalationClosesAndAnotherBecomesOverdue() throws Exception {
        Ward w = ward();
        Adm a = admitOne(w.clinic());
        Adm b = admitOne(w.clinic());
        Adm c = admitOne(w.clinic());
        String first = openEscalationAgo(w.clinic(), a, 7, "HIGH", 40 * 60);   // overdue
        openEscalationAgo(w.clinic(), b, 3, "MEDIUM", 60);                      // not overdue
        JsonNode one = ok(HttpMethod.GET, ADMISSIONS, w.owner().auth(), null, 200).get("safety");
        assertThat(one.get("counts").get("open").asInt()).isEqualTo(2);
        assertThat(one.get("counts").get("pastTarget").asInt()).isOne();
        assertThat(one.get("pastTarget").get(0).get("escalationId").asText()).isEqualTo(first);
        String tokenOne = one.get("counts").get("changeToken").asText();

        // the first one is closed and another becomes overdue: the counts are the same, the banner would name another patient
        respondOk(w.doctor().auth(), first, 0, "BEDSIDE_REVIEW", "Seen, repeat vitals in 30 minutes");
        String third = openEscalationAgo(w.clinic(), c, 9, "HIGH", 30 * 60);
        JsonNode two = ok(HttpMethod.GET, ADMISSIONS, w.owner().auth(), null, 200).get("safety");
        assertThat(two.get("counts").get("open").asInt()).as("same open count").isEqualTo(2);
        assertThat(two.get("counts").get("pastTarget").asInt()).as("same past target count").isOne();
        assertThat(two.get("pastTarget").get(0).get("escalationId").asText()).isEqualTo(third);
        assertThat(two.get("counts").get("changeToken").asText()).as("only the token tells them apart").isNotEqualTo(tokenOne);
        assertThat(two.get("counts").get("changeToken").asText()).isEqualTo(expectedChangeToken(w.clinic()));
    }

    @Test
    void anOverdueScoreThatRisesChangesTheTokenWhileTheCountsStayTheSame() throws Exception {
        Ward w = ward();
        Adm a = admitOne(w.clinic());
        // an escalation raised 40 minutes ago (written by the table owner: the application cannot write a time), score 7, overdue
        String id = openEscalationAgo(w.clinic(), a, 7, "HIGH", 40 * 60);
        JsonNode before = summaryOf(w.owner().auth());
        assertThat(before.get("pastTarget").asInt()).isOne();
        save(w.clinic(), a.id(), newKeyed(high14()));                  // score rises inside High: version + 1
        JsonNode after = summaryOf(w.owner().auth());
        assertThat(after.get("open").asInt()).isEqualTo(before.get("open").asInt());
        assertThat(after.get("pastTarget").asInt()).isEqualTo(before.get("pastTarget").asInt()).isOne();
        assertThat(after.get("changeToken").asText()).isNotEqualTo(before.get("changeToken").asText());
        assertThat(esc(w.clinic(), id).version()).isOne();
    }

    @Test
    void aPatientWhoLeavesTheWardLeavesTheTokenAndAnotherClinicsEscalationsAreNeverIn() throws Exception {
        Ward w = ward();
        Ward other = ward();
        Adm a = admitOne(w.clinic());
        Adm mine = admitOne(w.clinic());
        Adm theirs = admitOne(other.clinic());
        raise(w.clinic(), mine, medium3());
        String alone = token(w);
        raise(other.clinic(), theirs, high7());
        assertThat(token(w)).as("another clinic's escalation is not in this clinic's token").isEqualTo(alone);
        assertThat(token(other)).isNotEqualTo(alone);

        String leaving = raise(w.clinic(), a, high7());
        String both = token(w);
        assertThat(both).isNotEqualTo(alone);
        sign(w.clinic(), a.id(), "lama", "general");
        // the LAMA discharge closes the escalation at discharge: the patient and the escalation leave the token
        dischargeNow(w.clinic(), a.id(), "lama", "general", "UPI");
        assertThat(esc(w.clinic(), leaving).status()).isEqualTo("DONE");
        assertThat(token(w)).isEqualTo(alone);
    }

    @Test
    void theTokenHoldsNoNameScoreOrIdentifier() throws Exception {
        Ward w = ward();
        Adm a = admitNamed(w.clinic(), "Kavita Joshi " + uniqueMobile().substring(5));
        String id = raise(w.clinic(), a, high7());
        String token = token(w);
        assertThat(token).hasSize(22).doesNotContain(id).doesNotContain("Kavita");
        assertThat(boardOf(w.owner().auth()).get("counts").size()).as("open, pastTarget, dosesDue, dosesLate, changeToken, asOf (SAH-35)").isEqualTo(6);
    }

    /** The vitals body with a new client key, so that it is a new save and not a replay. */
    private static java.util.Map<String, Object> newKeyed(java.util.Map<String, Object> body) {
        return newKey(body);
    }
}
