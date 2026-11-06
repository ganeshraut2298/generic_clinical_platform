package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-33 AC14 (security review SAH-30 L4, ADR-007 D6) on the real {@code sahaj_test} database: a Normal discharge is refused
 * with 409 escalation-open while an escalation is Open or Acknowledged, after the summary check and before the bill
 * check, and writes nothing; Death, LAMA and Referred close the escalation in the discharge transaction as "Closed at
 * discharge" with the row, the event text and the audit row of the contract, keeping the acknowledgement as it was; a
 * bed transfer is never refused; a failure of the close rolls the whole discharge back. Test plan: docs/test-plans/SAH-33.md.
 */
@OnTestDatabase
class SafetyDischargeIT extends SafetyTestSupport {

    private static final String PLAN = "Oxygen up to 6 L, repeat vitals in 30 minutes";

    private record Ward(Clinic clinic, Staff owner, Staff doctor) {
    }

    private Ward ward() throws Exception {
        Clinic clinic = hospital();
        return new Ward(clinic, ownerStaff(clinic), staff(clinic, "DOCTOR"));
    }

    // ---------------- Normal: refused ----------------

    @Test
    void aNormalDischargeIsRefusedWhileTheEscalationIsOpenOrAcknowledgedAndWritesNothing() throws Exception {
        Ward w = ward();
        Adm adm = admitOne(w.clinic());
        String id = raise(w.clinic(), adm, medium3());
        sign(w.clinic(), adm.id(), "normal", "general");
        JsonNode sheet = sheet(w.clinic(), adm.id());
        String body = dischargeBody("normal", "general", totalOf(sheet), depositsOf(sheet), "UPI");
        Written written = written(w.clinic(), adm.id());
        Side side = side(w.clinic());

        for (boolean acknowledged : new boolean[] {false, true}) {
            if (acknowledged) {
                ackOk(w.doctor().auth(), id, 0);
                written = written(w.clinic(), adm.id());
                side = side(w.clinic());
            }
            JsonNode p = problem(postDischarge(w.clinic().owner(), adm.id(), body), 409);
            assertType(p, "escalation-open");
            assertThat(p.get("detail").asText()).isEqualTo(ESCALATION_OPEN);
            assertThat(p.toString()).doesNotContain(adm.name());
            assertThat(written(w.clinic(), adm.id())).as("acknowledged " + acknowledged).isEqualTo(written);
            assertThat(side(w.clinic())).isEqualTo(side);
            assertThat(values(w.clinic(), "select status from ipd.admission where id = ?::uuid", adm.id())).containsExactly("ADMITTED");
            assertThat(esc(w.clinic(), id).status()).isEqualTo(acknowledged ? "ACK" : "OPEN");
        }
        // the patient stays on the board, with the bed taken
        assertThat(rowIds(boardOf(w.owner().auth()))).contains(id);

        // a doctor records the response: the same request now completes the discharge, and nothing is closed "at discharge"
        respondOk(w.doctor().auth(), id, 1, "TREATMENT_CHANGED", PLAN);
        JsonNode done = ok(HttpMethod.POST, dischargeUrl(adm.id()), w.clinic().owner(), body, 200);
        assertThat(done.get("id").asText()).isEqualTo(adm.id());
        assertThat(values(w.clinic(), "select status from ipd.admission where id = ?::uuid", adm.id())).containsExactly("DISCHARGED");
        assertThat(esc(w.clinic(), id).action()).isEqualTo("TREATMENT_CHANGED");
        assertThat(audit(w.clinic(), "IPD_ESCALATION_CLOSED_AT_DISCHARGE")).isEmpty();
        assertThat(audit(w.clinic(), "IPD_DISCHARGED")).hasSize(1);
    }

    @Test
    void theEscalationCheckComesAfterTheSignedSummaryAndBeforeTheBillCheckAndTheSignedTypeDecidesTheRule() throws Exception {
        Ward w = ward();
        Adm adm = admitOne(w.clinic());
        String id = raise(w.clinic(), adm, high7());
        // not signed yet: the summary check answers first
        JsonNode unsigned = problem(postDischarge(w.clinic().owner(), adm.id(),
                dischargeBody("normal", "general", 7L, DEPOSIT, "UPI")), 409);
        assertType(unsigned, "summary-not-signed");
        assertThat(unsigned.get("detail").asText()).isEqualTo(SIGN_FIRST);

        sign(w.clinic(), adm.id(), "normal", "general");
        JsonNode sheet = sheet(w.clinic(), adm.id());
        // a wrong total with an open escalation: the escalation is answered, not the bill
        assertType(problem(postDischarge(w.clinic().owner(), adm.id(),
                dischargeBody("normal", "general", totalOf(sheet) + 1, depositsOf(sheet), "UPI")), 409), "escalation-open");
        // the type must be the signed one: asking for LAMA to dodge the rule is the summary check, and nothing is closed
        JsonNode other = problem(postDischarge(w.clinic().owner(), adm.id(),
                dischargeBody("lama", "general", totalOf(sheet), depositsOf(sheet), "UPI")), 409);
        assertType(other, "summary-not-signed");
        assertThat(esc(w.clinic(), id).status()).isEqualTo("OPEN");
        // once the escalation is closed, the bill check is the next one
        respondOk(w.doctor().auth(), id, 0, "NO_CHANGE", PLAN);
        assertType(problem(postDischarge(w.clinic().owner(), adm.id(),
                dischargeBody("normal", "general", totalOf(sheet) + 1, depositsOf(sheet), "UPI")), 409), "bill-changed");
        assertThat(values(w.clinic(), "select status from ipd.admission where id = ?::uuid", adm.id())).containsExactly("ADMITTED");
    }

    @Test
    void aFrontDeskDischargeOfTheSameKindIsRefusedToo() throws Exception {
        Ward w = ward();
        Staff front = staff(w.clinic(), "FRONT_DESK");
        JsonNode admitted = admitGeneral(w.clinic(), 7); // 7 bill days: more than the deposit, so no refund is needed
        String adm = admitted.get("id").asText();
        save(w.clinic(), adm, medium3());
        sign(w.clinic(), adm, "normal", "general");
        JsonNode sheet = sheet(w.clinic(), adm);
        JsonNode p = problem(postDischarge(front.auth(), adm, dischargeBody("normal", "general", totalOf(sheet), depositsOf(sheet), "UPI")), 409);
        assertType(p, "escalation-open");
        assertThat(values(w.clinic(), "select status from ipd.admission where id = ?::uuid", adm)).containsExactly("ADMITTED");
    }

    // ---------------- Death, LAMA, Referred: closed at discharge ----------------

    @Test
    void deathLamaAndReferredCloseTheEscalationAtDischargeFromOpenAndFromAcknowledgedWithRowEventAndAudit() throws Exception {
        Ward w = ward();
        String[][] types = {{"death", "death"}, {"lama", "on request (lama)"}, {"referred", "referred"}};
        for (String[] type : types) {
            for (boolean fromAck : new boolean[] {false, true}) {
                String label = type[0] + (fromAck ? " from Acknowledged" : " from Open");
                Adm adm = admitOne(w.clinic());
                String id = raise(w.clinic(), adm, fromAck ? high7() : medium3());
                if (fromAck) {
                    ackOk(w.doctor().auth(), id, 0);
                }
                long version = esc(w.clinic(), id).version();
                sign(w.clinic(), adm.id(), type[0], "general");
                dischargeNow(w.clinic(), adm.id(), type[0], "general", "UPI");
                assertThat(values(w.clinic(), "select status from ipd.admission where id = ?::uuid", adm.id())).as(label)
                        .containsExactly("DISCHARGED");

                Esc e = esc(w.clinic(), id);
                assertThat(e.status()).as(label).isEqualTo("DONE");
                assertThat(e.action()).isEqualTo("CLOSED_AT_DISCHARGE");
                assertThat(e.note()).as("no plan is invented").isEmpty();
                assertThat(e.closedBy()).as("closed by the discharging user").isEqualTo(w.owner().userId().toString());
                assertThat(e.hasClosedAt()).isTrue();
                assertThat(e.version()).isEqualTo(version + 1);
                assertThat(e.hasReopenedAt()).isFalse();
                // the acknowledgement stays as it was: nobody from Open, the doctor and the time from Acknowledged
                assertThat(e.ackBy()).as(label).isEqualTo(fromAck ? w.doctor().userId().toString() : "");
                assertThat(e.hasAckAt()).as(label).isEqualTo(fromAck);

                assertThat(escEvents(w.clinic(), adm.id()).get(0)).as(label)
                        .isEqualTo("Escalation closed at discharge (" + type[1] + ") · no doctor response recorded");
                assertThat(values(w.clinic(), "select actor::text from ipd.clinical_event where admission_id = ?::uuid "
                        + "and text like 'Escalation closed at discharge%'", adm.id())).containsExactly(w.owner().userId().toString());

                List<String[]> audit = audit(w.clinic(), "IPD_ESCALATION_CLOSED_AT_DISCHARGE");
                String[] row = audit.stream().filter(a -> id.equals(a[1])).findFirst().orElseThrow();
                assertThat(row[0]).isEqualTo("IPD_EWS_ESCALATION");
                assertThat(row[4]).isEqualTo(w.owner().userId().toString());
                JsonNode after = objectMapper.readTree(row[3]);
                assertThat(after.get("escalationId").asText()).isEqualTo(id);
                assertThat(after.get("admissionId").asText()).isEqualTo(adm.id());
                assertThat(after.get("ewsScore").asInt()).isEqualTo(fromAck ? 7 : 3);
                assertThat(after.get("ewsRisk").asText()).isEqualTo(fromAck ? "HIGH" : "MEDIUM");
                assertThat(after.get("statusBefore").asText()).isEqualTo(fromAck ? "ACK" : "OPEN");
                assertThat(after.get("dischargeType").asText()).isEqualTo(type[0]);
                assertThat(row[3]).doesNotContain(adm.name());
                // the discharge audit row is unchanged and the escalation has left the board with the patient
                assertThat(audit(w.clinic(), "IPD_DISCHARGED").stream().anyMatch(a -> a[1].equals(adm.id()))).isTrue();
                assertThat(rowIds(boardOf(w.owner().auth()))).doesNotContain(id);
            }
        }
        assertThat(boardOf(w.owner().auth()).get("escalations")).isEmpty();
        assertThat(summaryOf(w.owner().auth()).get("open").asInt()).isZero();
        assertThat(audit(w.clinic(), "IPD_ESCALATION_CLOSED_AT_DISCHARGE")).hasSize(6);
    }

    @Test
    void aFrontDeskPersonWhoDischargesIsTheClosingUserAndAnEscalationClosedAtDischargeIsNeverEditedAfterwards() throws Exception {
        Ward w = ward();
        Staff front = staff(w.clinic(), "FRONT_DESK");
        JsonNode admitted = admitGeneral(w.clinic(), 7);
        String adm = admitted.get("id").asText();
        String id = raise(w.clinic(), new Adm(adm, null, "x", "x", "x"), high7());
        sign(w.clinic(), adm, "lama", "general");
        JsonNode sheet = sheet(w.clinic(), adm);
        ok(HttpMethod.POST, dischargeUrl(adm), front.auth(), dischargeBody("lama", "general", totalOf(sheet), depositsOf(sheet), "UPI"), 200);
        Esc e = esc(w.clinic(), id);
        assertThat(e.closedBy()).isEqualTo(front.userId().toString());
        assertThat(e.ackBy()).isEmpty();
        assertThat(e.action()).isEqualTo("CLOSED_AT_DISCHARGE");
        // no action works on it any more
        assertType(problem(ack(w.doctor().auth(), id, e.version()), 404), "not-found");
        assertType(problem(respond(w.doctor().auth(), id, e.version(), "NO_CHANGE", PLAN), 404), "not-found");
        // the database refuses any change too
        assertThat(appErr(w.clinic(), "update ipd.ews_escalation set response_note = 'x', version = version + 1 where id = ?::uuid", id)
                .state()).isEqualTo("23514");
    }

    // ---------------- a bed transfer is not a discharge ----------------

    @Test
    void aBedTransferIsNeverRefusedForAnEscalationAndTheRowShowsTheNewBed() throws Exception {
        Ward w = ward();
        setRules(w.clinic(), false, "same");
        Adm adm = admitOne(w.clinic());
        String id = raise(w.clinic(), adm, high7());
        ackOk(w.doctor().auth(), id, 0);
        UUID target = newBed(w.clinic(), "gen");
        String code = values(w.clinic(), "select code from ipd.bed where id = ?", target).get(0);
        moveOk(w.clinic(), adm.id(), target);
        Esc e = esc(w.clinic(), id);
        assertThat(e.status()).isEqualTo("ACK");
        assertThat(e.version()).isOne();
        JsonNode row = escRow(boardOf(w.owner().auth()), id);
        assertThat(row.get("bedCode").asText()).isEqualTo(code).isNotEqualTo(adm.bedCode());
        assertThat(row.get("status").asText()).isEqualTo("ACK");
    }

    // ---------------- a failure rolls the whole discharge back ----------------

    @Test
    void aFailureWhileClosingTheEscalationAtDischargeRollsBackTheWholeDischargeAndARetryCompletes() throws Exception {
        Ward w = ward();
        for (String[] failing : new String[][] {{"ipd.clinical_event", "new.text like 'Escalation closed at discharge%'"},
                {"platform.audit_event", "new.action = 'IPD_ESCALATION_CLOSED_AT_DISCHARGE'"}}) {
            Adm adm = admitOne(w.clinic());
            String id = raise(w.clinic(), adm, medium3());
            ackOk(w.doctor().auth(), id, 0);
            sign(w.clinic(), adm.id(), "lama", "general");
            JsonNode sheet = sheet(w.clinic(), adm.id());
            String body = dischargeBody("lama", "general", totalOf(sheet), depositsOf(sheet), "UPI");
            Written before = written(w.clinic(), adm.id());
            Side side = side(w.clinic());
            failingInsert(w.clinic(), failing[0], failing[1], () -> {
                JsonNode p = problem(postDischarge(w.clinic().owner(), adm.id(), body), 500);
                assertThat(p.toString()).doesNotContain("forced failure").doesNotContain(adm.name());
            });
            assertThat(written(w.clinic(), adm.id())).as(failing[0]).isEqualTo(before);
            assertThat(side(w.clinic())).isEqualTo(side);
            assertThat(values(w.clinic(), "select status from ipd.admission where id = ?::uuid", adm.id())).containsExactly("ADMITTED");
            Esc e = esc(w.clinic(), id);
            assertThat(e.status()).isEqualTo("ACK");
            assertThat(e.version()).isOne();
            // the retry with the same request completes
            ok(HttpMethod.POST, dischargeUrl(adm.id()), w.clinic().owner(), body, 200);
            assertThat(esc(w.clinic(), id).action()).isEqualTo("CLOSED_AT_DISCHARGE");
        }
    }

    @Test
    void afterTheDischargeANewVitalsSaveIs409NotAdmittedAndRaisesNothing() throws Exception {
        Ward w = ward();
        Adm adm = admitOne(w.clinic());
        raise(w.clinic(), adm, medium3());
        sign(w.clinic(), adm.id(), "referred", "general");
        dischargeNow(w.clinic(), adm.id(), "referred", "general", "UPI");
        JsonNode p = problem(saveCall(w.clinic().owner(), adm.id(), newKey(high7())), 409);
        assertType(p, "not-admitted");
        assertThat(escalationCount(w.clinic(), adm.id())).isOne();
        assertThat(openEscalationCount(w.clinic(), adm.id())).isZero();
    }
}
