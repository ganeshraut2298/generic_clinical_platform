package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-33 AC15 and ADR-007 D3 on the real {@code sahaj_test} database: things that happen at the same moment. The orders
 * are forced, not hoped for: a second connection holds the admission row, the requests are started one after the other
 * and each is seen waiting in {@code pg_stat_activity}, then the lock is released, so the first in the queue runs first.
 * Two acknowledges, a double tap, two closes, two calls, an acknowledge against a re-opening vitals save (both orders),
 * a close against a discharge (three orders), a call against a close (both orders), a discharge against a vitals save
 * (four orders), and a free-for-all that must end without a server error or a second open escalation. Test plan:
 * docs/test-plans/SAH-33.md.
 */
@OnTestDatabase
class SafetyConcurrencyIT extends SafetyTestSupport {

    private static final String PLAN = "Oxygen up to 6 L, repeat vitals in 30 minutes";

    private record Resp(int status, String body) {
        JsonNode json(com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
            return mapper.readTree(body);
        }
    }

    private record Req(HttpMethod method, String url, RequestPostProcessor auth, String body) {
    }

    private record Ward(Clinic clinic, Staff owner, Staff doctor, Staff nurse, Adm adm, String esc) {
    }

    private Ward ward(java.util.Map<String, Object> vitals) throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitNamed(clinic, "Meera Kulkarni " + uniqueMobile().substring(5));
        String esc = vitals == null ? null : raise(clinic, adm, vitals);
        return new Ward(clinic, ownerStaff(clinic), staff(clinic, "DOCTOR"), staff(clinic, "NURSE"), adm, esc);
    }

    // ---------------- plumbing ----------------

    private Callable<Resp> task(CountDownLatch start, Req r) {
        return () -> {
            start.await();
            var builder = request(r.method(), URI.create(r.url())).with(r.auth());
            if (r.body() != null) {
                builder.contentType(MediaType.APPLICATION_JSON).content(r.body());
            }
            var res = mvc.perform(builder).andReturn().getResponse();
            return new Resp(res.getStatus(), res.getContentAsString());
        };
    }

    private static CountDownLatch oneShot() {
        return new CountDownLatch(0);
    }

    /** Starts all requests at the same moment; no answer may be a server error. */
    private List<Resp> parallel(Req... requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Resp>> futures = new ArrayList<>();
            for (Req r : requests) {
                futures.add(pool.submit(task(start, r)));
            }
            start.countDown();
            List<Resp> out = new ArrayList<>();
            for (Future<Resp> f : futures) {
                Resp resp = f.get(90, TimeUnit.SECONDS);
                assertThat(resp.status()).as("no answer is a server error: " + resp.body()).isLessThan(500);
                out.add(resp);
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private Connection holdAdmissionLock(Clinic clinic, String admissionId) throws Exception {
        Connection holder = db().app();
        holder.setAutoCommit(false);
        try (PreparedStatement bind = holder.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
            bind.setString(1, clinic.tenantId().toString());
            bind.execute();
        }
        try (PreparedStatement lock = holder.prepareStatement("select id from ipd.admission where id = ?::uuid for update")) {
            lock.setString(1, admissionId);
            assertThat(lock.executeQuery().next()).isTrue();
        }
        return holder;
    }

    /**
     * Runs the requests in the given order: the admission row is held by another connection, each request is started
     * and seen waiting on the admission row before the next one starts, then the lock is released. The first in the
     * queue gets the row first. Answers come back in request order.
     */
    private List<Resp> ordered(Clinic clinic, String admissionId, Req... reqs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(reqs.length);
        List<Future<Resp>> futures = new ArrayList<>();
        try (Connection holder = holdAdmissionLock(clinic, admissionId)) {
            for (int i = 0; i < reqs.length; i++) {
                futures.add(pool.submit(task(oneShot(), reqs[i])));
                assertThat(awaitLockWaiters("%from ipd.admission%update%", i + 1, 30_000))
                        .as("request " + (i + 1) + " waits on the admission row").isTrue();
            }
            for (Future<Resp> f : futures) {
                assertThat(f.isDone()).isFalse();
            }
            holder.rollback();
            List<Resp> out = new ArrayList<>();
            for (Future<Resp> f : futures) {
                Resp r = f.get(60, TimeUnit.SECONDS);
                assertThat(r.status()).as("no server error: " + r.body()).isLessThan(500);
                out.add(r);
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private Req ackReq(Staff who, String id, long version) {
        return new Req(HttpMethod.POST, ackUrl(id), who.auth(), "{\"version\":" + version + "}");
    }

    private Req respondReq(Staff who, String id, long version) throws Exception {
        return new Req(HttpMethod.POST, respondUrl(id), who.auth(), respondJson(version, "NO_CHANGE", PLAN));
    }

    private Req callReq(Staff who, String id, long version, int calls) {
        return new Req(HttpMethod.POST, callUrl(id), who.auth(), "{\"version\":" + version + ",\"seniorCalls\":" + calls + "}");
    }

    private Req saveReq(Clinic clinic, String admissionId, java.util.Map<String, Object> body) throws Exception {
        return new Req(HttpMethod.POST, vitalsUrl(admissionId), clinic.owner(), js(body));
    }

    private Req dischargeReq(Clinic clinic, Adm adm, String type) throws Exception {
        sign(clinic, adm.id(), type, "general");
        JsonNode sheet = sheet(clinic, adm.id());
        return new Req(HttpMethod.POST, dischargeUrl(adm.id()), clinic.owner(),
                dischargeBody(type, "general", totalOf(sheet), depositsOf(sheet), "UPI"));
    }

    private static String type(Resp r, com.fasterxml.jackson.databind.ObjectMapper m) throws Exception {
        String t = m.readTree(r.body()).get("type").asText();
        return t.substring(t.lastIndexOf('/') + 1);
    }

    private String detail(Resp r) throws Exception {
        return objectMapper.readTree(r.body()).get("detail").asText();
    }

    private String admissionStatus(Ward w) throws Exception {
        return values(w.clinic(), "select status from ipd.admission where id = ?::uuid", w.adm().id()).get(0);
    }

    // ---------------- acknowledge against acknowledge ----------------

    @Test
    void twoDoctorsAcknowledgingAtTheSameMomentGiveOneSuccessAndOneEscalationStateWithTheWinnersName() throws Exception {
        Ward w = ward(medium3());
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), ackReq(w.doctor(), w.esc(), 0), ackReq(w.owner(), w.esc(), 0));
        assertThat(answers.get(0).status()).isEqualTo(200);
        assertThat(answers.get(0).json(objectMapper).get("replayed").asBoolean()).isFalse();
        assertThat(answers.get(1).status()).isEqualTo(409);
        assertThat(type(answers.get(1), objectMapper)).isEqualTo("escalation-state");
        assertThat(detail(answers.get(1))).isEqualTo(w.doctor().name() + " already acknowledged this escalation.");
        Esc e = esc(w.clinic(), w.esc());
        assertThat(e.status()).isEqualTo("ACK");
        assertThat(e.version()).isOne();
        assertThat(e.ackBy()).isEqualTo(w.doctor().userId().toString());
        assertThat(escEvents(w.clinic(), w.adm().id()).stream().filter(t -> t.startsWith("Acknowledged")).count()).isOne();
        assertThat(audit(w.clinic(), "IPD_ESCALATION_ACKNOWLEDGED")).hasSize(1);
    }

    @Test
    void repeatedFreeRacesOfTwoAcknowledgesAlwaysEndWithExactlyOneWinner() throws Exception {
        Ward w = ward(null);
        for (int round = 0; round < 6; round++) {
            Adm adm = admitOne(w.clinic());
            String id = raise(w.clinic(), adm, round % 2 == 0 ? medium3() : high7());
            List<Resp> answers = parallel(ackReq(w.doctor(), id, 0), ackReq(w.owner(), id, 0));
            long ok = answers.stream().filter(r -> r.status() == 200).count();
            long conflict = answers.stream().filter(r -> r.status() == 409).count();
            assertThat(ok).as("round " + round).isOne();
            assertThat(conflict).as("round " + round).isOne();
            for (Resp r : answers) {
                if (r.status() == 409) {
                    assertThat(type(r, objectMapper)).isEqualTo("escalation-state");
                    assertThat(detail(r)).endsWith(" already acknowledged this escalation.");
                }
            }
            Esc e = esc(w.clinic(), id);
            assertThat(e.status()).isEqualTo("ACK");
            assertThat(e.version()).isOne();
            assertThat(audit(w.clinic(), "IPD_ESCALATION_ACKNOWLEDGED").stream().filter(a -> a[1].equals(id)).count()).isOne();
        }
    }

    @Test
    void aDoubleTapByTheSameDoctorIsOneWriteAndOneReplay() throws Exception {
        Ward w = ward(medium3());
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), ackReq(w.doctor(), w.esc(), 0), ackReq(w.doctor(), w.esc(), 0));
        assertThat(answers.get(0).status()).isEqualTo(200);
        assertThat(answers.get(1).status()).isEqualTo(200);
        assertThat(answers.get(0).json(objectMapper).get("replayed").asBoolean()).isFalse();
        assertThat(answers.get(1).json(objectMapper).get("replayed").asBoolean()).isTrue();
        assertThat(esc(w.clinic(), w.esc()).version()).isOne();
        assertThat(audit(w.clinic(), "IPD_ESCALATION_ACKNOWLEDGED")).hasSize(1);
        assertThat(escEvents(w.clinic(), w.adm().id()).stream().filter(t -> t.startsWith("Acknowledged")).count()).isOne();
    }

    @Test
    void twoClosesAtTheSameMomentGiveOneCloseAndOneAlreadyClosedAndTwoNursesCallingWithTheSameCountCountOnce() throws Exception {
        Ward w = ward(medium3());
        List<Resp> closes = ordered(w.clinic(), w.adm().id(), respondReq(w.doctor(), w.esc(), 0), respondReq(w.owner(), w.esc(), 0));
        assertThat(closes.get(0).status()).isEqualTo(200);
        assertThat(closes.get(1).status()).isEqualTo(409);
        assertThat(type(closes.get(1), objectMapper)).isEqualTo("escalation-state");
        assertThat(detail(closes.get(1))).isEqualTo(ALREADY_CLOSED);
        assertThat(esc(w.clinic(), w.esc()).closedBy()).isEqualTo(w.doctor().userId().toString());
        assertThat(audit(w.clinic(), "IPD_ESCALATION_CLOSED")).hasSize(1);
        assertThat(escEvents(w.clinic(), w.adm().id()).stream().filter(t -> t.startsWith("Escalation closed")).count()).isOne();

        Ward n = ward(medium3());
        Staff other = staff(n.clinic(), "NURSE");
        List<Resp> calls = ordered(n.clinic(), n.adm().id(), callReq(n.nurse(), n.esc(), 0, 0), callReq(other, n.esc(), 0, 0));
        assertThat(calls.get(0).json(objectMapper).get("replayed").asBoolean()).isFalse();
        assertThat(calls.get(1).json(objectMapper).get("replayed").asBoolean()).isTrue();
        assertThat(esc(n.clinic(), n.esc()).seniorCalls()).isOne();
        assertThat(audit(n.clinic(), "IPD_ESCALATION_SENIOR_CALLED")).hasSize(1);
    }

    @Test
    void aDoubleTapByTheSameNurseIsOneCallAndOneThrottledReplayAndTwoNursesAtOnceAreOneCallAndOneSeenEntry() throws Exception {
        // ADR-007 R4: the throttle is read under the admission lock, so two taps of one nurse serialise
        Ward w = ward(medium3());
        List<Resp> taps = ordered(w.clinic(), w.adm().id(), callReq(w.nurse(), w.esc(), 0, 0), callReq(w.nurse(), w.esc(), 0, 0));
        assertThat(taps.get(0).status()).isEqualTo(200);
        assertThat(taps.get(1).status()).isEqualTo(200);
        assertThat(taps.get(0).json(objectMapper).get("replayed").asBoolean()).isFalse();
        assertThat(taps.get(1).json(objectMapper).get("replayed").asBoolean()).isTrue();
        assertThat(esc(w.clinic(), w.esc()).seniorCalls()).isOne();
        assertThat(audit(w.clinic(), "IPD_ESCALATION_SENIOR_CALLED")).hasSize(1);
        assertThat(values(w.clinic(), "select outcome from ipd.ews_senior_call where escalation_id = ?::uuid", w.esc()))
                .containsExactly("CALLED");

        Ward n = ward(medium3());
        Staff other = staff(n.clinic(), "NURSE");
        List<Resp> two = ordered(n.clinic(), n.adm().id(), callReq(n.nurse(), n.esc(), 0, 0), callReq(other, n.esc(), 0, 0));
        assertThat(two.get(0).json(objectMapper).get("replayed").asBoolean()).isFalse();
        assertThat(two.get(1).json(objectMapper).get("replayed").asBoolean()).isTrue();
        assertThat(esc(n.clinic(), n.esc()).seniorCalls()).isOne();
        assertThat(audit(n.clinic(), "IPD_ESCALATION_SENIOR_CALLED")).hasSize(1);
        assertThat(column(n.clinic(), "select outcome from ipd.ews_senior_call where escalation_id = ?::uuid order by called_at",
                n.esc())).containsExactly("CALLED", "ALREADY_CALLED");
        assertThat(escEvents(n.clinic(), n.adm().id()).stream().filter(t -> t.startsWith("Senior doctor already called")).count())
                .isOne();
        // both taps of the second pair came back without a server error and the second nurse is the actor of the seen entry
        assertThat(values(n.clinic(), "select called_by::text from ipd.ews_senior_call where outcome = 'ALREADY_CALLED' "
                + "and escalation_id = ?::uuid", n.esc())).containsExactly(other.userId().toString());
    }

    // ---------------- acknowledge against a re-opening vitals save ----------------

    @Test
    void anAcknowledgeFirstThenAHigherRiskSaveReopensItWithTheAcknowledgementClearedAndRestartedClock() throws Exception {
        Ward w = ward(medium3());
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), ackReq(w.doctor(), w.esc(), 0), saveReq(w.clinic(), w.adm().id(), high7()));
        assertThat(answers.get(0).status()).isEqualTo(200);
        assertThat(answers.get(1).status()).isEqualTo(201);
        assertThat(answers.get(1).json(objectMapper).get("escalation").get("outcome").asText()).isEqualTo("REOPENED");
        assertThat(openEscalation(w.clinic(), w.adm().id())).isEqualTo("7|HIGH|15|OPEN|2");
        assertThat(escalationColumn(w.clinic(), w.adm().id(), "(acknowledged_by is null and acknowledged_at is null)")).isEqualTo("true");
        assertThat(escalationColumn(w.clinic(), w.adm().id(), "(reopened_at is not null)")).isEqualTo("true");
        List<String> events = escEvents(w.clinic(), w.adm().id());
        assertThat(events.get(0)).startsWith("Escalation re-opened");
        assertThat(events.get(1)).startsWith("Acknowledged escalation after");
        assertThat(audit(w.clinic(), "IPD_ESCALATION_ACKNOWLEDGED")).hasSize(1);
        assertThat(audit(w.clinic(), "IPD_ESCALATION_REOPENED")).hasSize(1);
        // the board shows it as Not seen again with the Acknowledge button
        JsonNode row = escRow(boardOf(w.doctor().auth()), w.esc());
        assertThat(row.get("status").asText()).isEqualTo("OPEN");
        assertThat(row.get("canAcknowledge").asBoolean()).isTrue();
        assertThat(row.get("acknowledgedByName").isNull()).isTrue();
    }

    @Test
    void aSaveFirstThenAnAcknowledgeWithTheOldVersionIsEscalationChangedAndNothingIsAcknowledged() throws Exception {
        Ward w = ward(medium3());
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), saveReq(w.clinic(), w.adm().id(), medium6()), ackReq(w.doctor(), w.esc(), 0));
        assertThat(answers.get(0).status()).isEqualTo(201);
        assertThat(answers.get(1).status()).isEqualTo(409);
        assertThat(type(answers.get(1), objectMapper)).isEqualTo("escalation-changed");
        assertThat(detail(answers.get(1))).isEqualTo("The score is now EWS 6 (medium). Review it and try again.");
        assertThat(openEscalation(w.clinic(), w.adm().id())).isEqualTo("6|MEDIUM|60|OPEN|1");
        assertThat(audit(w.clinic(), "IPD_ESCALATION_ACKNOWLEDGED")).isEmpty();
    }

    @Test
    void aReopeningSaveFirstThenACloseWithTheVersionOfTheAcknowledgedScreenIsEscalationChanged() throws Exception {
        Ward w = ward(medium3());
        ackOk(w.doctor().auth(), w.esc(), 0);
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), saveReq(w.clinic(), w.adm().id(), high7()), respondReq(w.owner(), w.esc(), 1));
        assertThat(answers.get(0).json(objectMapper).get("escalation").get("outcome").asText()).isEqualTo("REOPENED");
        assertThat(answers.get(1).status()).isEqualTo(409);
        assertThat(type(answers.get(1), objectMapper)).isEqualTo("escalation-changed");
        assertThat(detail(answers.get(1))).isEqualTo("The score is now EWS 7 (high). Review it and try again.");
        assertThat(esc(w.clinic(), w.esc()).status()).isEqualTo("OPEN");
        assertThat(audit(w.clinic(), "IPD_ESCALATION_CLOSED")).isEmpty();
    }

    // ---------------- close against discharge ----------------

    @Test
    void aCloseFirstThenANormalDischargeBothSucceedAndNothingIsClosedAtDischarge() throws Exception {
        Ward w = ward(medium3());
        Req discharge = dischargeReq(w.clinic(), w.adm(), "normal");
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), respondReq(w.doctor(), w.esc(), 0), discharge);
        assertThat(answers.get(0).status()).isEqualTo(200);
        assertThat(answers.get(1).status()).as(answers.get(1).body()).isEqualTo(200);
        assertThat(admissionStatus(w)).isEqualTo("DISCHARGED");
        assertThat(esc(w.clinic(), w.esc()).action()).isEqualTo("NO_CHANGE");
        assertThat(audit(w.clinic(), "IPD_ESCALATION_CLOSED_AT_DISCHARGE")).isEmpty();
    }

    @Test
    void aLamaDischargeFirstClosesTheEscalationAndTheCloseBehindItIs404() throws Exception {
        Ward w = ward(medium3());
        Req discharge = dischargeReq(w.clinic(), w.adm(), "lama");
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), discharge, respondReq(w.doctor(), w.esc(), 0));
        assertThat(answers.get(0).status()).as(answers.get(0).body()).isEqualTo(200);
        assertThat(answers.get(1).status()).isEqualTo(404);
        assertThat(type(answers.get(1), objectMapper)).isEqualTo("not-found");
        assertThat(admissionStatus(w)).isEqualTo("DISCHARGED");
        Esc e = esc(w.clinic(), w.esc());
        assertThat(e.action()).isEqualTo("CLOSED_AT_DISCHARGE");
        assertThat(e.note()).isEmpty();
        assertThat(audit(w.clinic(), "IPD_ESCALATION_CLOSED")).isEmpty();
    }

    @Test
    void aNormalDischargeFirstIsRefusedAndTheCloseBehindItSucceedsBecauseTheRefusalReleasedTheRow() throws Exception {
        Ward w = ward(medium3());
        Req discharge = dischargeReq(w.clinic(), w.adm(), "normal");
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), discharge, respondReq(w.doctor(), w.esc(), 0));
        assertThat(answers.get(0).status()).isEqualTo(409);
        assertThat(type(answers.get(0), objectMapper)).isEqualTo("escalation-open");
        assertThat(answers.get(1).status()).isEqualTo(200);
        assertThat(admissionStatus(w)).isEqualTo("ADMITTED");
        assertThat(esc(w.clinic(), w.esc()).status()).isEqualTo("DONE");
    }

    // ---------------- call senior against close ----------------

    @Test
    void aCloseFirstThenACallSeniorIsEscalationStateAlreadyClosedAndNothingIsRecorded() throws Exception {
        Ward w = ward(medium3());
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), respondReq(w.doctor(), w.esc(), 0), callReq(w.nurse(), w.esc(), 0, 0));
        assertThat(answers.get(0).status()).isEqualTo(200);
        assertThat(answers.get(1).status()).isEqualTo(409);
        assertThat(type(answers.get(1), objectMapper)).isEqualTo("escalation-state");
        assertThat(detail(answers.get(1))).isEqualTo(ALREADY_CLOSED);
        assertThat(esc(w.clinic(), w.esc()).seniorCalls()).isZero();
        assertThat(audit(w.clinic(), "IPD_ESCALATION_SENIOR_CALLED")).isEmpty();
    }

    @Test
    void aCallSeniorFirstThenACloseWithTheSameVersionStillSucceedsBecauseACallDoesNotChangeTheVersion() throws Exception {
        Ward w = ward(medium3());
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), callReq(w.nurse(), w.esc(), 0, 0), respondReq(w.doctor(), w.esc(), 0));
        assertThat(answers.get(0).status()).isEqualTo(200);
        assertThat(answers.get(1).status()).isEqualTo(200);
        Esc e = esc(w.clinic(), w.esc());
        assertThat(e.status()).isEqualTo("DONE");
        assertThat(e.seniorCalls()).isOne();
        assertThat(e.version()).isOne();
        // the closed row shows the call that was made before the close
        assertThat(escRow(boardOf(w.owner().auth()), w.esc()).get("seniorCalls").asInt()).isOne();
    }

    // ---------------- discharge against a vitals save ----------------

    @Test
    void aSaveThatRaisesAnEscalationFirstMakesTheNormalDischargeBehindItRefusedSoTheCheckCannotBeBypassedByTheRace()
            throws Exception {
        Ward w = ward(null);
        Req discharge = dischargeReq(w.clinic(), w.adm(), "normal");
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), saveReq(w.clinic(), w.adm().id(), medium3()), discharge);
        assertThat(answers.get(0).status()).isEqualTo(201);
        assertThat(answers.get(0).json(objectMapper).get("escalation").get("outcome").asText()).isEqualTo("RAISED");
        assertThat(answers.get(1).status()).isEqualTo(409);
        assertThat(type(answers.get(1), objectMapper)).isEqualTo("escalation-open");
        assertThat(detail(answers.get(1))).isEqualTo(ESCALATION_OPEN);
        assertThat(admissionStatus(w)).isEqualTo("ADMITTED");
        assertThat(openEscalationCount(w.clinic(), w.adm().id())).isOne();
    }

    @Test
    void aNormalDischargeFirstWithNoEscalationCompletesAndTheSaveBehindItIs409NotAdmittedWithNothingRaised() throws Exception {
        Ward w = ward(null);
        Req discharge = dischargeReq(w.clinic(), w.adm(), "normal");
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), discharge, saveReq(w.clinic(), w.adm().id(), high7()));
        assertThat(answers.get(0).status()).as(answers.get(0).body()).isEqualTo(200);
        assertThat(answers.get(1).status()).isEqualTo(409);
        assertThat(type(answers.get(1), objectMapper)).isEqualTo("not-admitted");
        assertThat(escalationCount(w.clinic(), w.adm().id())).isZero();
        assertThat(vitalsCount(w.clinic(), w.adm().id())).isZero();
        assertThat(admissionStatus(w)).isEqualTo("DISCHARGED");
    }

    @Test
    void aLamaDischargeFirstClosesTheEscalationAndTheSaveBehindItIs409NotAdmittedAndRaisesNoSecondOne() throws Exception {
        Ward w = ward(medium3());
        Req discharge = dischargeReq(w.clinic(), w.adm(), "lama");
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), discharge, saveReq(w.clinic(), w.adm().id(), high7()));
        assertThat(answers.get(0).status()).as(answers.get(0).body()).isEqualTo(200);
        assertThat(answers.get(1).status()).isEqualTo(409);
        assertThat(type(answers.get(1), objectMapper)).isEqualTo("not-admitted");
        assertThat(escalationCount(w.clinic(), w.adm().id())).isOne();
        assertThat(openEscalationCount(w.clinic(), w.adm().id())).isZero();
        assertThat(esc(w.clinic(), w.esc()).action()).isEqualTo("CLOSED_AT_DISCHARGE");
        assertThat(esc(w.clinic(), w.esc()).score()).as("the late save did not touch it").isEqualTo(3);
    }

    @Test
    void aRefusedNormalDischargeFirstLeavesTheRowToTheSaveBehindItWhichUpdatesTheEscalation() throws Exception {
        Ward w = ward(medium3());
        Req discharge = dischargeReq(w.clinic(), w.adm(), "normal");
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), discharge, saveReq(w.clinic(), w.adm().id(), high7()));
        assertThat(answers.get(0).status()).isEqualTo(409);
        assertThat(type(answers.get(0), objectMapper)).isEqualTo("escalation-open");
        assertThat(answers.get(1).status()).isEqualTo(201);
        assertThat(answers.get(1).json(objectMapper).get("escalation").get("outcome").asText()).isEqualTo("UPDATED");
        assertThat(openEscalation(w.clinic(), w.adm().id())).isEqualTo("7|HIGH|15|OPEN|1");
        assertThat(admissionStatus(w)).isEqualTo("ADMITTED");
    }

    // ---------------- the free-for-all ----------------

    @Test
    void manyDifferentRequestsAtOnceOnOneAdmissionNeverGiveAServerErrorOrASecondOpenEscalation() throws Exception {
        Ward w = ward(null);
        for (int round = 0; round < 4; round++) {
            Adm adm = admitOne(w.clinic());
            String id = raise(w.clinic(), adm, medium3());
            List<Resp> answers = parallel(ackReq(w.doctor(), id, 0), ackReq(w.owner(), id, 0), respondReq(w.doctor(), id, 0),
                    respondReq(w.owner(), id, 1), callReq(w.nurse(), id, 0, 0), callReq(w.nurse(), id, 0, 0),
                    saveReq(w.clinic(), adm.id(), high7()), saveReq(w.clinic(), adm.id(), medium6()));
            for (Resp r : answers) {
                assertThat(r.status()).as(r.body()).isIn(200, 201, 409);
                if (r.status() == 409) {
                    assertThat(type(r, objectMapper)).isIn("escalation-state", "escalation-changed");
                }
            }
            assertThat(openEscalationCount(w.clinic(), adm.id())).as("round " + round).isLessThanOrEqualTo(1);
            assertThat(escalationCount(w.clinic(), adm.id())).as("a save after a close may raise a new one").isBetween(1L, 2L);
            Esc e = esc(w.clinic(), id);
            assertThat(e.status()).isIn("OPEN", "ACK", "DONE");
            if (e.status().equals("DONE")) {
                assertThat(e.action()).isEqualTo("NO_CHANGE");
                assertThat(e.note()).isEqualTo(PLAN);
                assertThat(e.closedBy()).isNotEmpty();
                assertThat(e.ackBy()).isNotEmpty();
            }
            // one entry per write, never a duplicate: at most one acknowledge and one close in the log
            List<String> events = escEvents(w.clinic(), adm.id());
            assertThat(events.stream().filter(t -> t.startsWith("Acknowledged")).count()).isLessThanOrEqualTo(2);
            assertThat(events.stream().filter(t -> t.startsWith("Escalation closed")).count()).isLessThanOrEqualTo(1);
            assertThat(audit(w.clinic(), "IPD_ESCALATION_CLOSED").stream().filter(a -> a[1].equals(id)).count()).isLessThanOrEqualTo(1);
        }
    }
}
