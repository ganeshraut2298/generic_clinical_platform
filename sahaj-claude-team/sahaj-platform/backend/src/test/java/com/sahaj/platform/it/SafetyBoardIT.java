package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-33 AC1 to AC7, AC16 (counts), AC18, AC19 and the discharge sheet flag, on the real {@code sahaj_test} database:
 * who may read the board, the summary and the audit trail; the order of the rows; the counts, which must be the same
 * figure everywhere; {@code asOf} on the database clock and every minute count taken against it; the overdue rule at its
 * edges (a High escalation at 14:15 is not past target, at 15:20 it is; Medium at 59:15 and 60:20; an Acknowledged or
 * closed one never; a re-opened one from the re-open); which role sees the plan text and the buttons; the Safety part of
 * the admitted list per role; the open escalation flag of the discharge sheet; another clinic's data. Test plan:
 * docs/test-plans/SAH-33.md.
 */
@OnTestDatabase
class SafetyBoardIT extends SafetyTestSupport {

    private static final String OFF_TEXT = "In-patients (IPD) is not switched on for your clinic.";
    private static final String PLAN = "Oxygen up to 6 L, repeat vitals in 30 minutes";

    private record Ward(Clinic clinic, Staff owner, Staff doctor, Staff nurse) {
    }

    private Ward ward() throws Exception {
        Clinic clinic = hospital();
        return new Ward(clinic, ownerStaff(clinic), staff(clinic, "DOCTOR"), staff(clinic, "NURSE"));
    }

    // ---------------- who may read ----------------

    @Test
    void ownerDoctorAndNurseReadTheBoardTheSummaryAndTheAuditTrailAndEveryOtherRoleIs403() throws Exception {
        Ward w = ward();
        Staff front = staff(w.clinic(), "FRONT_DESK");
        Adm adm = admitOne(w.clinic());
        raise(w.clinic(), adm, medium3());
        Side before = side(w.clinic());
        for (Staff s : new Staff[] {w.owner(), w.doctor(), w.nurse()}) {
            assertThat(boardOf(s.auth()).get("escalations")).as(s.role()).hasSize(1);
            assertThat(summaryOf(s.auth()).get("open").asInt()).as(s.role()).isOne();
            assertThat(eventsOf(s.auth(), null).get("items")).as(s.role()).isNotEmpty();
        }
        for (String role : NON_BOARD_ROLES) {
            for (String url : new String[] {SAFETY, SUMMARY, EVENTS}) {
                JsonNode p = problem(call(HttpMethod.GET, url, w.clinic().as(role), null), 403);
                assertType(p, "forbidden");
                assertThat(p.toString()).as(role + " " + url).doesNotContain(adm.name());
            }
        }
        // a real front desk person and a real pharmacist have no more than the token role says
        assertType(problem(call(HttpMethod.GET, SAFETY, front.auth(), null), 403), "forbidden");
        for (String url : new String[] {SAFETY, SUMMARY, EVENTS}) {
            assertType(problem(call(HttpMethod.GET, url, noSession(), null), 401), "unauthorized");
        }
        // reading writes nothing: no event, no audit row, no record-access row
        assertThat(side(w.clinic())).isEqualTo(before);
    }

    @Test
    void whenIpdIsOffTheThreeReadsAreModuleOffForTheAllowedRolesAndForbiddenForTheOthers() throws Exception {
        Clinic off = newClinic();
        for (String role : BOARD_ROLES) {
            for (String url : new String[] {SAFETY, SUMMARY, EVENTS}) {
                JsonNode p = problem(call(HttpMethod.GET, url, off.as(role), null), 403);
                assertType(p, "module-off");
                assertThat(p.get("detail").asText()).isEqualTo(OFF_TEXT);
            }
        }
        assertType(problem(call(HttpMethod.GET, SAFETY, off.as("FRONT_DESK"), null), 403), "forbidden");
    }

    @Test
    void aRoleChangedInTheMembershipIsRefusedAtOnceByTheThreeReadsWhileTheTokenStillSaysDoctor() throws Exception {
        Ward w = ward();
        Staff doctor = w.doctor();
        try {
            for (String demoted : new String[] {"FRONT_DESK", "BILLING"}) {
                setMembershipRole(w.clinic(), doctor.userId(), demoted);
                for (String url : new String[] {SAFETY, SUMMARY, EVENTS}) {
                    assertType(problem(call(HttpMethod.GET, url, doctor.auth(), null), 403), "forbidden");
                }
            }
            for (String status : new String[] {"INACTIVE", "REMOVED"}) {
                setMembershipRole(w.clinic(), doctor.userId(), "DOCTOR");
                setMembershipStatus(w.clinic().tenantId(), doctor.userId(), status);
                for (String url : new String[] {SAFETY, SUMMARY, EVENTS}) {
                    assertType(problem(call(HttpMethod.GET, url, doctor.auth(), null), 403), "forbidden");
                }
            }
        } finally {
            setMembershipStatus(w.clinic().tenantId(), doctor.userId(), "ACTIVE");
            setMembershipRole(w.clinic(), doctor.userId(), "DOCTOR");
        }
        assertThat(boardOf(doctor.auth()).get("view").asText()).isEqualTo("DOCTOR");
    }

    // ---------------- the shape of the board ----------------

    @Test
    void theViewTheSecondTileAndTheFiveActionsFollowTheMembershipRoleAndTheStateIsShown() throws Exception {
        Ward w = ward();
        admitOne(w.clinic());
        admitOne(w.clinic());
        for (Staff s : new Staff[] {w.owner(), w.doctor()}) {
            JsonNode board = boardOf(s.auth());
            assertThat(board.get("view").asText()).as(s.role()).isEqualTo("DOCTOR");
            assertThat(board.get("yourPatients").isNull()).as(s.role()).isTrue();
            assertThat(board.get("state").asText()).isEqualTo("trial");
        }
        JsonNode nurse = boardOf(w.nurse().auth());
        assertThat(nurse.get("view").asText()).isEqualTo("NURSE");
        assertThat(nurse.get("yourPatients").asInt()).as("every admitted patient until wards exist").isEqualTo(2);
        List<String> keys = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        nurse.get("responseActions").forEach(a -> {
            keys.add(a.get("key").asText());
            labels.add(a.get("label").asText());
        });
        assertThat(keys).containsExactly("BEDSIDE_REVIEW", "TREATMENT_CHANGED", "MOVED_ICU_HDU", "RAPID_RESPONSE", "NO_CHANGE");
        assertThat(labels).containsExactly("Reviewed at the bedside", "Treatment changed", "Moved to ICU or HDU",
                "Rapid response team called", "Reviewed, no change needed");
        assertThat(nurse.get("escalations")).isEmpty();
        assertThat(nurse.get("counts").get("open").asInt()).isZero();
        assertThat(nurse.get("counts").get("pastTarget").asInt()).isZero();
        // the number of admitted patients follows a discharge
        Adm third = admitOne(w.clinic());
        assertThat(boardOf(w.nurse().auth()).get("yourPatients").asInt()).isEqualTo(3);
        signAndDischarge(w.clinic(), third.id());
        assertThat(boardOf(w.nurse().auth()).get("yourPatients").asInt()).isEqualTo(2);
        expireTrial(w.clinic());
        assertThat(boardOf(w.doctor().auth()).get("state").asText()).isEqualTo("readonly");
    }

    @Test
    void theButtonsFollowTheStatusAndBothRolesAndTheOnlyRolesThatSeeThePlanAreOwnerDoctorAndNurse() throws Exception {
        Ward w = ward();
        Adm open = admitNamed(w.clinic(), "Asha Patil " + uniqueMobile().substring(5));
        Adm ack = admitOne(w.clinic());
        Adm done = admitOne(w.clinic());
        String openId = raise(w.clinic(), open, medium3());
        String ackId = raise(w.clinic(), ack, high7());
        String doneId = raise(w.clinic(), done, high14());
        ackOk(w.doctor().auth(), ackId, 0);
        respondOk(w.doctor().auth(), doneId, 0, "TREATMENT_CHANGED", PLAN);

        // the doctor's view and the owner's view
        for (Staff s : new Staff[] {w.doctor(), w.owner()}) {
            JsonNode board = boardOf(s.auth());
            assertFlags(escRow(board, openId), true, true, false, s.role() + " open");
            assertFlags(escRow(board, ackId), false, true, false, s.role() + " acknowledged");
            assertFlags(escRow(board, doneId), false, false, false, s.role() + " closed");
            assertThat(escRow(board, doneId).get("response").get("note").asText()).as(s.role()).isEqualTo(PLAN);
            assertThat(escRow(board, openId).get("response").isNull()).isTrue();
        }
        // the nurse's view: only Call senior on an Open row, and the plan is shown to her
        JsonNode nurse = boardOf(w.nurse().auth());
        assertFlags(escRow(nurse, openId), false, false, true, "nurse open");
        assertFlags(escRow(nurse, ackId), false, false, false, "nurse acknowledged");
        assertFlags(escRow(nurse, doneId), false, false, false, "nurse closed");
        assertThat(escRow(nurse, doneId).get("response").get("note").asText()).isEqualTo(PLAN);
        assertThat(escRow(nurse, ackId).get("acknowledgedByName").asText()).isEqualTo(w.doctor().name());
        assertThat(escRow(nurse, openId).get("acknowledgedByName").isNull()).isTrue();
        assertThat(escRow(nurse, openId).get("patientName").asText()).isEqualTo(open.name());
        assertThat(escRow(nurse, openId).get("bedCode").asText()).isEqualTo(open.bedCode());
        assertThat(escRow(nurse, openId).get("raisedByName").asText()).isEqualTo(w.owner().name());

        // a token that disagrees with the membership gets no button: the shown button is never refused for the role
        assertFlags(escRow(boardOf(w.clinic().as("NURSE")), openId), false, false, false, "token nurse, membership owner");
        assertFlags(escRow(boardOf(w.clinic().as("DOCTOR")), openId), true, true, false, "token doctor, membership owner");
        try {
            setMembershipRole(w.clinic(), w.doctor().userId(), "NURSE");
            JsonNode asNurse = boardOf(w.doctor().auth());
            assertThat(asNurse.get("view").asText()).isEqualTo("NURSE");
            assertFlags(escRow(asNurse, openId), false, false, false, "token doctor, membership nurse");
        } finally {
            setMembershipRole(w.clinic(), w.doctor().userId(), "DOCTOR");
        }
        // no other role has the board, so none sees the plan: the admitted list and the discharge sheet carry no plan text
        Staff front = staff(w.clinic(), "FRONT_DESK");
        String list = ok(HttpMethod.GET, ADMISSIONS, front.auth(), null, 200).toString();
        assertThat(list).doesNotContain(PLAN).doesNotContain("ewsScore").doesNotContain("pastTarget");
        String sheet = ok(HttpMethod.GET, dischargeUrl(done.id()), front.auth(), null, 200).toString();
        assertThat(sheet).doesNotContain(PLAN).doesNotContain("ewsScore");
    }

    private static void assertFlags(JsonNode row, boolean acknowledge, boolean respond, boolean callSenior, String label) {
        assertThat(row).as(label).isNotNull();
        assertThat(row.get("canAcknowledge").asBoolean()).as(label + " canAcknowledge").isEqualTo(acknowledge);
        assertThat(row.get("canRespond").asBoolean()).as(label + " canRespond").isEqualTo(respond);
        assertThat(row.get("canCallSenior").asBoolean()).as(label + " canCallSenior").isEqualTo(callSenior);
    }

    // ---------------- order and counts ----------------

    @Test
    void rowsAreNotClosedFirstThenHigherScoreThenEarlierClockStartThenTheIdAndTheCountsAreTheSameFigureEverywhere()
            throws Exception {
        Ward w = ward();
        Clinic c = w.clinic();
        Instant now = Instant.now();
        Adm a1 = admitOne(c);
        String e1 = raise(c, a1, medium3());                                              // 3, Open, new
        Adm a2 = admitOne(c);
        String e2 = openEscalationAgo(c, a2, 7, "HIGH", 10 * 60);                          // 7, Open, 10 min
        Adm a3 = admitOne(c);
        String e3 = openEscalationAgo(c, a3, 7, "HIGH", 20 * 60);                          // 7, Open, 20 min: past target
        Adm a4 = admitOne(c);
        String e4 = insertEscalation(c, a4.id(), 6, "MEDIUM", now.minusSeconds(30 * 60), null, c.ownerUserId()); // 6, Acknowledged
        Adm a5 = admitOne(c);
        String e5 = raise(c, a5, high14());                                                // 14, Open, new
        Adm a8 = admitOne(c);
        Adm a9 = admitOne(c);
        Instant same = now.minusSeconds(300);
        String e8 = insertEscalation(c, a8.id(), 8, "HIGH", same, null, null);             // a tie: score and clock start
        String e9 = insertEscalation(c, a9.id(), 8, "HIGH", same, null, null);
        Adm a10 = admitOne(c);
        String e10 = openEscalationAgo(c, a10, 5, "MEDIUM", 61 * 60);                      // 5, Open, 61 min: past target
        Adm a6 = admitOne(c);
        String e6 = raise(c, a6, high7());
        respondOk(w.doctor().auth(), e6, 0, "NO_CHANGE", PLAN);                            // closed, 7
        Adm a7 = admitOne(c);
        String e7 = raise(c, a7, medium3());
        respondOk(w.doctor().auth(), e7, 0, "NO_CHANGE", PLAN);                            // closed, 3

        // expected from the contract: not closed first; score high to low; earlier clock start; id in java.util.UUID order
        List<String> tie = new ArrayList<>(List.of(e8, e9));
        tie.sort((x, y) -> UUID.fromString(x).compareTo(UUID.fromString(y)));
        List<String> expected = new ArrayList<>();
        expected.add(e5);
        expected.addAll(tie);
        expected.addAll(List.of(e3, e2, e4, e10, e1));
        expected.addAll(List.of(e6, e7));

        for (Staff s : new Staff[] {w.owner(), w.doctor(), w.nurse()}) {
            JsonNode board = boardOf(s.auth());
            assertThat(rowIds(board)).as(s.role()).containsExactlyElementsOf(expected);
            long notClosed = rowIds(board).size() - 2;
            assertThat(board.get("counts").get("open").asLong()).isEqualTo(notClosed).isEqualTo(8);
            long overdue = 0;
            for (JsonNode r : board.get("escalations")) {
                overdue += r.get("pastTarget").asBoolean() ? 1 : 0;
            }
            assertThat(overdue).isEqualTo(2);
            assertThat(board.get("counts").get("pastTarget").asLong()).isEqualTo(overdue);
            assertThat(escRow(board, e3).get("pastTarget").asBoolean()).isTrue();
            assertThat(escRow(board, e10).get("pastTarget").asBoolean()).isTrue();
            assertThat(escRow(board, e2).get("pastTarget").asBoolean()).isFalse();
            JsonNode summary = summaryOf(s.auth());
            assertThat(summary.get("open")).isEqualTo(board.get("counts").get("open"));
            assertThat(summary.get("pastTarget")).isEqualTo(board.get("counts").get("pastTarget"));
            // the admitted list: the tab count, the banner items in board order
            JsonNode safety = ok(HttpMethod.GET, ADMISSIONS, s.auth(), null, 200).get("safety");
            assertThat(safety.get("counts").get("open").asInt()).isEqualTo(8);
            assertThat(safety.get("counts").get("pastTarget").asInt()).isEqualTo(2);
            List<String> banner = new ArrayList<>();
            safety.get("pastTarget").forEach(i -> banner.add(i.get("escalationId").asText()));
            assertThat(banner).containsExactly(e3, e10);
            assertThat(safety.get("pastTarget").get(0).get("patientName").asText()).isEqualTo(a3.name());
            assertThat(safety.get("pastTarget").get(0).get("ewsScore").asInt()).isEqualTo(7);
            assertThat(safety.get("pastTarget").get(0).get("admissionId").asText()).isEqualTo(a3.id());
        }
    }

    @Test
    void theSummaryHoldsCountsOnlyAndNoNameScoreOrText() throws Exception {
        Ward w = ward();
        Adm adm = admitNamed(w.clinic(), "Kavita Joshi " + uniqueMobile().substring(5));
        openEscalationAgo(w.clinic(), adm, 9, "HIGH", 40 * 60);
        JsonNode summary = summaryOf(w.nurse().auth());
        List<String> fields = new ArrayList<>();
        summary.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("open", "pastTarget", "dosesDue", "dosesLate", "changeToken", "asOf");   // SAH-35: numbers only; the two dose figures carry no name, medicine or bed
        assertThat(summary.get("changeToken").asText()).as("an opaque token of 22 base64url characters (ADR-007 R1)")
                .matches("[A-Za-z0-9_-]{22}");
        assertThat(summary.get("open").asInt()).isOne();
        assertThat(summary.get("pastTarget").asInt()).isOne();
        assertThat(summary.toString()).doesNotContain("Kavita");
    }

    @Test
    void closedRowsStayOnTheBoardWhileThePatientIsAdmittedAndLeaveWithTheDischarge() throws Exception {
        Ward w = ward();
        Adm adm = admitOne(w.clinic());
        Adm stays = admitOne(w.clinic());
        String id = raise(w.clinic(), adm, high7());
        String keep = raise(w.clinic(), stays, medium3());
        respondOk(w.doctor().auth(), id, 0, "NO_CHANGE", PLAN);
        JsonNode board = boardOf(w.owner().auth());
        assertThat(statuses(board)).containsExactly("OPEN", "DONE");
        assertThat(board.get("counts").get("open").asInt()).isOne();
        // a lama discharge closes any open escalation; the patient leaves the board with all his rows
        sign(w.clinic(), adm.id(), "lama", "general");
        dischargeNow(w.clinic(), adm.id(), "lama", "general", "UPI");
        JsonNode after = boardOf(w.owner().auth());
        assertThat(rowIds(after)).containsExactly(keep);
        assertThat(summaryOf(w.owner().auth()).get("open").asInt()).isOne();
    }

    // ---------------- the database clock and the overdue edges ----------------

    @Test
    void asOfIsTheDatabaseClockOfTheAnswerAndEveryMinuteCountIsTakenAgainstIt() throws Exception {
        Ward w = ward();
        Adm adm = admitOne(w.clinic());
        Instant raised = Instant.now().minusSeconds(25 * 60 + 40);
        Instant reopened = Instant.now().minusSeconds(7 * 60 + 15);
        String id = insertEscalation(w.clinic(), adm.id(), 7, "HIGH", raised, reopened, null);
        for (Staff s : new Staff[] {w.owner(), w.nurse()}) {
            Instant before = dbNow(w.clinic());
            JsonNode board = boardOf(s.auth());
            Instant after = dbNow(w.clinic());
            Instant asOf = instant(board.get("asOf"));
            assertThat(asOf).isBetween(before, after);
            assertThat(instant(board.get("counts").get("asOf"))).isEqualTo(asOf);
            JsonNode row = escRow(board, id);
            assertThat(row.get("minutesSinceRaised").asLong()).isEqualTo(Duration.between(instant(row.get("raisedAt")), asOf).toMinutes());
            assertThat(row.get("waitingMinutes").asLong()).isEqualTo(Duration.between(instant(row.get("reopenedAt")), asOf).toMinutes());
            assertThat(row.get("minutesSinceRaised").asInt()).isEqualTo(25);
            assertThat(row.get("waitingMinutes").asInt()).isEqualTo(7);
            Instant sumAt = instant(summaryOf(s.auth()).get("asOf"));
            assertThat(sumAt).isBetween(before, dbNow(w.clinic()));
        }
        Instant listBefore = dbNow(w.clinic());
        Instant listAt = instant(ok(HttpMethod.GET, ADMISSIONS, w.owner().auth(), null, 200).get("safety").get("counts").get("asOf"));
        assertThat(listAt).isBetween(listBefore, dbNow(w.clinic()));
    }

    @Test
    void anOpenEscalationIsPastTargetOnlyAfterItsTargetOnTheServerClockAndNeverWhenAcknowledgedOrClosed() throws Exception {
        Ward w = ward();
        Clinic c = w.clinic();
        record Case(String label, int score, String risk, long seconds, Long reopenedSecondsAgo, boolean ack, boolean past,
                    int waiting) {
        }
        List<Case> cases = List.of(
                new Case("High 14:15 is not past", 7, "HIGH", 14 * 60 + 15, null, false, false, 14),
                new Case("High 15:20 is past", 7, "HIGH", 15 * 60 + 20, null, false, true, 15),
                new Case("High 1:00 is not past", 9, "HIGH", 60, null, false, false, 1),
                new Case("Medium 59:15 is not past", 4, "MEDIUM", 59 * 60 + 15, null, false, false, 59),
                new Case("Medium 60:20 is past", 4, "MEDIUM", 60 * 60 + 20, null, false, true, 60),
                new Case("Medium 16 min is not past (the High target does not apply)", 4, "MEDIUM", 16 * 60, null, false, false, 16),
                new Case("High acknowledged long ago is never past", 7, "HIGH", 3 * 3600, null, true, false, 180),
                new Case("Re-opened 5 min ago after 3 hours is not past", 7, "HIGH", 3 * 3600, 5 * 60L, false, false, 5),
                new Case("Re-opened 15:20 ago is past", 7, "HIGH", 3 * 3600, 15 * 60 + 20L, false, true, 15));
        List<String> ids = new ArrayList<>();
        List<Adm> admitted = new ArrayList<>();
        for (int i = 0; i <= cases.size(); i++) {
            admitted.add(admitOne(c)); // the admissions first, so that the times below are read close to the request
        }
        for (int i = 0; i < cases.size(); i++) {
            Case k = cases.get(i);
            Adm adm = admitted.get(i);
            Instant raised = Instant.now().minusSeconds(k.seconds());
            Instant reopened = k.reopenedSecondsAgo() == null ? null : Instant.now().minusSeconds(k.reopenedSecondsAgo());
            ids.add(insertEscalation(c, adm.id(), k.score(), k.risk(), raised, reopened, k.ack() ? c.ownerUserId() : null));
        }
        // a closed one, long overdue before it was closed
        Adm closed = admitted.get(cases.size());
        String closedId = insertEscalation(c, closed.id(), 7, "HIGH", Instant.now().minusSeconds(4 * 3600), null, null);
        closeEscalation(c, closed.id());

        for (Staff s : new Staff[] {w.doctor(), w.nurse()}) {
            JsonNode board = boardOf(s.auth());
            int past = 0;
            for (int i = 0; i < cases.size(); i++) {
                JsonNode row = escRow(board, ids.get(i));
                assertThat(row.get("pastTarget").asBoolean()).as(s.role() + ": " + cases.get(i).label()).isEqualTo(cases.get(i).past());
                assertThat(row.get("waitingMinutes").asInt()).as(cases.get(i).label()).isEqualTo(cases.get(i).waiting());
                past += cases.get(i).past() ? 1 : 0;
            }
            assertThat(escRow(board, closedId).get("pastTarget").asBoolean()).as("closed is never past target").isFalse();
            assertThat(escRow(board, closedId).get("status").asText()).isEqualTo("DONE");
            assertThat(board.get("counts").get("pastTarget").asInt()).isEqualTo(past);
            assertThat(summaryOf(s.auth()).get("pastTarget").asInt()).isEqualTo(past);
        }
    }

    @Test
    void acknowledgingStopsTheOverdueStateAtOnceAndClosingRemovesTheEscalationFromTheCounts() throws Exception {
        Ward w = ward();
        Adm adm = admitOne(w.clinic());
        String id = openEscalationAgo(w.clinic(), adm, 8, "HIGH", 30 * 60);
        assertThat(boardOf(w.doctor().auth()).get("counts").get("pastTarget").asInt()).isOne();
        assertThat(escRow(boardOf(w.nurse().auth()), id).get("canCallSenior").asBoolean()).isTrue();
        ackOk(w.doctor().auth(), id, 0);
        JsonNode board = boardOf(w.doctor().auth());
        assertThat(board.get("counts").get("pastTarget").asInt()).isZero();
        assertThat(board.get("counts").get("open").asInt()).isOne();
        assertThat(escRow(board, id).get("pastTarget").asBoolean()).isFalse();
        assertThat(summaryOf(w.nurse().auth()).get("pastTarget").asInt()).isZero();
        assertThat(ok(HttpMethod.GET, ADMISSIONS, w.nurse().auth(), null, 200).get("safety").get("pastTarget")).isEmpty();
        respondOk(w.doctor().auth(), id, 1, "NO_CHANGE", PLAN);
        assertThat(boardOf(w.doctor().auth()).get("counts").get("open").asInt()).isZero();
        assertThat(ok(HttpMethod.GET, ADMISSIONS, w.nurse().auth(), null, 200).get("safety").get("counts").get("open").asInt()).isZero();
    }

    // ---------------- the admitted list per role and the discharge sheet ----------------

    @Test
    void theSafetyPartOfTheAdmittedListIsForOwnerDoctorAndNurseAndNullForTheOtherRolesThatMayListPatients() throws Exception {
        Ward w = ward();
        Adm adm = admitOne(w.clinic());
        openEscalationAgo(w.clinic(), adm, 8, "HIGH", 30 * 60);
        for (Staff s : new Staff[] {w.owner(), w.doctor(), w.nurse()}) {
            JsonNode safety = ok(HttpMethod.GET, ADMISSIONS, s.auth(), null, 200).get("safety");
            assertThat(safety.isNull()).as(s.role()).isFalse();
            assertThat(safety.get("counts").get("open").asInt()).isOne();
            assertThat(safety.get("pastTarget")).hasSize(1);
        }
        Staff front = staff(w.clinic(), "FRONT_DESK");
        JsonNode frontList = ok(HttpMethod.GET, ADMISSIONS, front.auth(), null, 200);
        assertThat(frontList.get("safety").isNull()).as("FRONT_DESK gets no score").isTrue();
        assertThat(frontList.toString()).doesNotContain("\"ewsScore\"").doesNotContain("\"escalationId\"");
        // the roles that may not list the admitted patients at all
        for (String role : new String[] {"BILLING", "PHARMACIST", "VISITING_DOCTOR"}) {
            assertType(problem(call(HttpMethod.GET, ADMISSIONS, w.clinic().as(role), null), 403), "forbidden");
        }
        // the token says DOCTOR but the membership says FRONT_DESK: the list is allowed, the safety part is not
        try {
            setMembershipRole(w.clinic(), w.doctor().userId(), "FRONT_DESK");
            assertThat(ok(HttpMethod.GET, ADMISSIONS, w.doctor().auth(), null, 200).get("safety").isNull()).isTrue();
            setMembershipRole(w.clinic(), w.doctor().userId(), "NURSE");
            assertThat(ok(HttpMethod.GET, ADMISSIONS, w.doctor().auth(), null, 200).get("safety").isNull()).isFalse();
        } finally {
            setMembershipRole(w.clinic(), w.doctor().userId(), "DOCTOR");
        }
        // a token role outside the three has no safety part even when the membership is the owner's
        assertThat(ok(HttpMethod.GET, ADMISSIONS, w.clinic().as("FRONT_DESK"), null, 200).get("safety").isNull()).isTrue();
        // IPD Read-only: the part is still there
        expireTrial(w.clinic());
        assertThat(ok(HttpMethod.GET, ADMISSIONS, w.nurse().auth(), null, 200).get("safety").get("counts").get("open").asInt()).isOne();
    }

    @Test
    void theDischargeSheetSaysWhetherAnEscalationIsNotClosedAsAPlainFlagForEveryRoleThatOpensIt() throws Exception {
        Ward w = ward();
        Staff front = staff(w.clinic(), "FRONT_DESK");
        Adm none = admitOne(w.clinic());
        Adm open = admitOne(w.clinic());
        Adm ack = admitOne(w.clinic());
        Adm closed = admitOne(w.clinic());
        String openId = raise(w.clinic(), open, medium3());
        String ackId = raise(w.clinic(), ack, high7());
        String closedId = raise(w.clinic(), closed, high7());
        ackOk(w.doctor().auth(), ackId, 0);
        respondOk(w.doctor().auth(), closedId, 0, "NO_CHANGE", PLAN);
        assertThat(openId).isNotNull();
        for (RequestPostProcessor auth : new RequestPostProcessor[] {w.owner().auth(), front.auth()}) {
            assertThat(ok(HttpMethod.GET, dischargeUrl(none.id()), auth, null, 200).get("openEscalation").asBoolean()).isFalse();
            assertThat(ok(HttpMethod.GET, dischargeUrl(open.id()), auth, null, 200).get("openEscalation").asBoolean()).isTrue();
            assertThat(ok(HttpMethod.GET, dischargeUrl(ack.id()), auth, null, 200).get("openEscalation").asBoolean()).isTrue();
            assertThat(ok(HttpMethod.GET, dischargeUrl(closed.id()), auth, null, 200).get("openEscalation").asBoolean()).isFalse();
        }
        // a flag and nothing else: no score, no risk, no id of an escalation
        String sheet = ok(HttpMethod.GET, dischargeUrl(open.id()), front.auth(), null, 200).toString();
        assertThat(sheet).doesNotContain("ewsScore").doesNotContain("ewsRisk").doesNotContain(openId);
    }

    // ---------------- other clinics ----------------

    @Test
    void anotherClinicsBoardCountsAndSummaryNeverShowThisClinicsRows() throws Exception {
        Ward a = ward();
        Ward b = ward();
        Adm adm = admitNamed(a.clinic(), "Sunita Deshmukh " + uniqueMobile().substring(5));
        String id = raise(a.clinic(), adm, high7());
        respondOk(a.doctor().auth(), raise(a.clinic(), admitOne(a.clinic()), medium3()), 0, "NO_CHANGE", PLAN);
        openEscalationAgo(a.clinic(), admitOne(a.clinic()), 9, "HIGH", 40 * 60);

        assertThat(boardOf(a.doctor().auth()).get("escalations")).hasSize(3);
        for (Staff s : new Staff[] {b.owner(), b.doctor(), b.nurse()}) {
            JsonNode board = boardOf(s.auth());
            assertThat(board.get("escalations")).as(s.role()).isEmpty();
            assertThat(board.get("counts").get("open").asInt()).isZero();
            assertThat(board.get("counts").get("pastTarget").asInt()).isZero();
            assertThat(summaryOf(s.auth()).get("open").asInt()).isZero();
            assertThat(eventsOf(s.auth(), null).get("items")).isEmpty();
            assertThat(board.toString()).doesNotContain(adm.name()).doesNotContain(PLAN).doesNotContain(id);
            assertThat(ok(HttpMethod.GET, ADMISSIONS, s.auth(), null, 200).get("safety").get("counts").get("open").asInt()).isZero();
        }
        // and the other way round
        Adm theirs = admitOne(b.clinic());
        raise(b.clinic(), theirs, medium3());
        assertThat(boardOf(a.owner().auth()).get("escalations")).hasSize(3);
        assertThat(rowIds(boardOf(b.owner().auth()))).doesNotContainAnyElementsOf(rowIds(boardOf(a.owner().auth())));
        assertThat(rowIds(boardOf(a.owner().auth())).stream().collect(Collectors.toSet())).hasSize(3);
    }
}
