package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SAH-33 review fix R5 (ADR-007, security review L5, OQ-33-13): the board returns the escalations that are Open or
 * Acknowledged and the closed ones whose {@code closed_at} is less than 24 hours before {@code asOf} (database clock).
 * Older closed rows are not on the board; they stay in the table and in the clinical audit trail ("Escalation closed:
 * ..."), and the counts, the change token and the banner never counted closed rows.
 *
 * <p>Time: the closing time is stamped by the database trigger and a closed row is final, so the test moves a closed row
 * back in time as the table owner with the guard trigger switched off inside one transaction
 * ({@link #ageClosedEscalation}); the edges are tested 10 minutes either side of 24 hours. Test plan: docs/test-plans/SAH-33.md.
 */
@OnTestDatabase
class SafetyWindowIT extends SafetyTestSupport {

    private static final String PLAN = "Oxygen up to 6 L, repeat vitals in 30 minutes";
    private static final long HOURS_24 = 24 * 60;

    private record Ward(Clinic clinic, Staff owner, Staff doctor, Staff nurse) {
    }

    private Ward ward() throws Exception {
        Clinic clinic = hospital();
        return new Ward(clinic, ownerStaff(clinic), staff(clinic, "DOCTOR"), staff(clinic, "NURSE"));
    }

    private String closedEscalation(Ward w, Adm adm) throws Exception {
        String id = raise(w.clinic(), adm, medium3());
        respondOk(w.doctor().auth(), id, 0, "NO_CHANGE", PLAN);
        return id;
    }

    @Test
    void aClosedEscalationStaysOnTheBoardFor24HoursAndThenOnlyInTheAuditTrail() throws Exception {
        Ward w = ward();
        Adm old = admitOne(w.clinic());
        Adm edgeIn = admitOne(w.clinic());
        Adm fresh = admitOne(w.clinic());
        Adm stillOpen = admitOne(w.clinic());
        String oldId = closedEscalation(w, old);
        String inId = closedEscalation(w, edgeIn);
        String freshId = closedEscalation(w, fresh);
        String openId = raise(w.clinic(), stillOpen, high7());
        assertThat(rowIds(boardOf(w.owner().auth()))).containsExactlyInAnyOrder(oldId, inId, freshId, openId);

        ageClosedEscalation(w.clinic(), oldId, HOURS_24 + 10);   // 24 h 10 min ago: out
        ageClosedEscalation(w.clinic(), inId, HOURS_24 - 10);    // 23 h 50 min ago: still in

        for (Staff s : new Staff[] {w.owner(), w.doctor(), w.nurse()}) {
            JsonNode board = boardOf(s.auth());
            assertThat(rowIds(board)).as(s.role()).containsExactlyInAnyOrder(inId, freshId, openId).doesNotContain(oldId);
            assertThat(escRow(board, oldId)).isNull();
            // the closed row that stays still carries its action, its plan and its closer
            JsonNode row = escRow(board, inId);
            assertThat(row.get("status").asText()).isEqualTo("DONE");
            assertThat(row.get("response").get("note").asText()).isEqualTo(PLAN);
            assertThat(row.get("response").get("action").asText()).isEqualTo("NO_CHANGE");
            // the order: not closed first, closed rows after
            assertThat(board.get("escalations").get(0).get("id").asText()).isEqualTo(openId);
        }
        // closed rows never counted: the counts and the token ignore both, aged or not
        JsonNode board = boardOf(w.owner().auth());
        assertThat(board.get("counts").get("open").asInt()).isOne();
        assertThat(summaryOf(w.owner().auth()).get("open").asInt()).isOne();
        assertThat(board.get("counts").get("changeToken").asText()).isEqualTo(expectedChangeToken(w.clinic()));

        // the old row is not deleted: it is in the table and its closing entry is in the clinical audit trail
        assertThat(esc(w.clinic(), oldId).status()).isEqualTo("DONE");
        assertThat(esc(w.clinic(), oldId).note()).isEqualTo(PLAN);
        assertThat(values(w.clinic(), "select (closed_at < now() - interval '24 hours')::text from ipd.ews_escalation where id = ?::uuid", oldId))
                .containsExactly("true");
        for (Staff s : new Staff[] {w.owner(), w.doctor()}) {
            JsonNode events = eventsOf(s.auth(), "limit=50");
            List<String> texts = new ArrayList<>();
            events.get("items").forEach(i -> texts.add(i.get("text").asText()));
            assertThat(texts.stream().filter(t -> t.equals("Escalation closed: Reviewed, no change needed")).count())
                    .as(s.role() + " sees the closing entry of every closed escalation, aged or not").isEqualTo(3);
        }
        // the nurse sees the entries of admitted patients as before
        JsonNode nurse = eventsOf(w.nurse().auth(), "limit=50");
        assertThat(nurse.get("items").size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void theWindowIsMeasuredOnTheClosingTimeAndNotOnTheRaiseTimeAndAnOpenEscalationIsNeverDropped() throws Exception {
        Ward w = ward();
        Adm a = admitOne(w.clinic());
        Adm b = admitOne(w.clinic());
        // raised more than 24 hours ago but closed a minute ago: stays
        String longOpen = closedEscalation(w, a);
        ageClosedEscalation(w.clinic(), longOpen, 1, 30 * 60);
        assertThat(values(w.clinic(), "select (raised_at < now() - interval '24 hours')::text from ipd.ews_escalation where id = ?::uuid",
                longOpen)).containsExactly("true");
        assertThat(rowIds(boardOf(w.owner().auth()))).contains(longOpen);

        // an escalation still Open after 30 hours is on the board (an owner-written raise time), and so is an Acknowledged one
        String open30h = insertEscalation(w.clinic(), b.id(), 7, "HIGH", java.time.Instant.now().minusSeconds(30 * 3600), null, null);
        JsonNode board = boardOf(w.owner().auth());
        assertThat(rowIds(board)).contains(open30h);
        assertThat(escRow(board, open30h).get("status").asText()).isEqualTo("OPEN");
        ackOk(w.doctor().auth(), open30h, 0);
        assertThat(escRow(boardOf(w.owner().auth()), open30h).get("status").asText()).isEqualTo("ACK");
    }

    @Test
    void whenThePatientIsDischargedAllHisRowsLeaveTheBoardWithinTheWindowToo() throws Exception {
        Ward w = ward();
        Adm adm = admitOne(w.clinic());
        String id = closedEscalation(w, adm);
        assertThat(rowIds(boardOf(w.owner().auth()))).containsExactly(id);
        sign(w.clinic(), adm.id(), "normal", "general");
        dischargeNow(w.clinic(), adm.id(), "normal", "general", "UPI");
        assertThat(rowIds(boardOf(w.owner().auth()))).isEmpty();
        assertThat(esc(w.clinic(), id).status()).isEqualTo("DONE");
    }

    @Test
    void anotherClinicsAgedRowsDoNotAppearAndTheWindowIsPerClinic() throws Exception {
        Ward a = ward();
        Ward b = ward();
        String inA = closedEscalation(a, admitOne(a.clinic()));
        String inB = closedEscalation(b, admitOne(b.clinic()));
        ageClosedEscalation(b.clinic(), inB, HOURS_24 + 60);
        assertThat(rowIds(boardOf(a.owner().auth()))).containsExactly(inA);
        assertThat(rowIds(boardOf(b.owner().auth()))).isEmpty();
    }
}
