package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
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
 * SAH-30 things that happen at the same moment, on the real {@code sahaj_test} database: two or many saves on one
 * admission end with exactly one open escalation (the admission row lock is the guard), a save racing a discharge
 * ends 409 not-admitted or completes first (both orders forced by a held lock and polled in pg_stat_activity), a save
 * waiting for a bed transfer is never a false 404, the same client key sent several times at once stores one record, and
 * saves on different admissions never wait for each other. No answer is a 500. Test plan: docs/test-plans/SAH-30.md.
 */
@OnTestDatabase
class VitalsConcurrencyIT extends VitalsSupport {

    private record Resp(int status, String body) {
    }

    private record Req(HttpMethod method, String url, RequestPostProcessor auth, String body) {
    }

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

    private JsonNode body(Resp r) throws Exception {
        return objectMapper.readTree(r.body());
    }

    private Req saveReq(Clinic clinic, String admissionId, Map<String, Object> body) throws Exception {
        return new Req(HttpMethod.POST, vitalsUrl(admissionId), clinic.owner(), js(body));
    }

    private long eventsOfKind(Clinic clinic, String admissionId, String kind) throws Exception {
        return count(clinic, "select count(*) from ipd.clinical_event where admission_id = ?::uuid and kind = ?", admissionId, kind);
    }

    // ---------------- one admission ----------------

    @Test
    void twoSimultaneousMediumSavesOfOneAdmissionEndWithExactlyOneEscalationRow() throws Exception {
        Clinic clinic = hospital();
        for (int round = 0; round < 5; round++) {
            Adm adm = admitOne(clinic);
            List<Resp> answers = parallel(saveReq(clinic, adm.id(), medium3()), saveReq(clinic, adm.id(), medium3()));
            List<String> outcomes = new ArrayList<>();
            for (Resp r : answers) {
                assertThat(r.status()).as("round " + round).isEqualTo(201);
                outcomes.add(body(r).get("escalation").get("outcome").asText());
            }
            assertThat(outcomes).as("round " + round).containsExactlyInAnyOrder("RAISED", "NONE");
            assertThat(escalationCount(clinic, adm.id())).as("round " + round).isOne();
            assertThat(openEscalationCount(clinic, adm.id())).isOne();
            assertThat(openEscalation(clinic, adm.id())).isEqualTo("3|MEDIUM|60|OPEN|0");
            assertThat(vitalsCount(clinic, adm.id())).isEqualTo(2);
            assertThat(eventsOfKind(clinic, adm.id(), "info")).isEqualTo(2);
            assertThat(eventsOfKind(clinic, adm.id(), "esc")).isOne();
        }
        assertThat(vitalsAudits(clinic)).isEqualTo(10);
    }

    @Test
    void eightSimultaneousSavesOfMixedScoresLeaveOneOpenEscalationAtTheHighestScoreAndEveryChangeIsCounted() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        List<Map<String, Object>> bodies = List.of(low(), medium3(), medium5(), medium6(), high7(), medium3(), high14(), low());
        Req[] reqs = new Req[bodies.size()];
        for (int i = 0; i < reqs.length; i++) {
            reqs[i] = saveReq(clinic, adm.id(), bodies.get(i));
        }
        List<Resp> answers = parallel(reqs);
        int raised = 0;
        int updated = 0;
        for (Resp r : answers) {
            assertThat(r.status()).isEqualTo(201);
            String outcome = body(r).get("escalation").get("outcome").asText();
            raised += outcome.equals("RAISED") ? 1 : 0;
            updated += outcome.equals("UPDATED") ? 1 : 0;
        }
        assertThat(raised).isOne();
        assertThat(escalationCount(clinic, adm.id())).isOne();
        assertThat(openEscalationCount(clinic, adm.id())).isOne();
        assertThat(vitalsCount(clinic, adm.id())).isEqualTo(8);
        assertThat(eventsOfKind(clinic, adm.id(), "info")).isEqualTo(8);
        assertThat(eventsOfKind(clinic, adm.id(), "esc")).isEqualTo(raised + updated);
        // the saves ran one after the other, so the final state is the highest score, with one version per update
        assertThat(openEscalation(clinic, adm.id())).isEqualTo("14|HIGH|15|OPEN|" + updated);
        assertThat(vitalsAudits(clinic)).isEqualTo(8);
    }

    @Test
    void theSameClientKeySentFiveTimesAtOnceStoresOneRecordAndAnswersOneCreatedAndFourReplays() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        Map<String, Object> body = noted(medium3(), "Sent five times");
        Req same = saveReq(clinic, adm.id(), body);
        List<Resp> answers = parallel(same, same, same, same, same);
        int created = 0;
        int replays = 0;
        TreeSet<String> ids = new TreeSet<>();
        for (Resp r : answers) {
            created += r.status() == 201 ? 1 : 0;
            replays += r.status() == 200 ? 1 : 0;
            ids.add(body(r).get("saved").get("vitalsId").asText());
        }
        assertThat(created).isOne();
        assertThat(replays).isEqualTo(4);
        assertThat(ids).hasSize(1);
        assertThat(vitalsCount(clinic, adm.id())).isOne();
        assertThat(noteCount(clinic, adm.id())).isOne();
        assertThat(eventCount(clinic, adm.id())).isEqualTo(2);
        assertThat(escalationCount(clinic, adm.id())).isOne();
        assertThat(vitalsAudits(clinic)).isOne();
    }

    // ---------------- a save against a discharge ----------------

    /**
     * Opens a connection as {@code sahaj_app} for the clinic and takes the row lock of one admission with the given
     * clause ({@code for update} or {@code for no key update}), as another transaction would. Close it to release.
     */
    private Connection holdAdmissionLock(Clinic clinic, String admissionId, String clause) throws Exception {
        Connection holder = db().app();
        holder.setAutoCommit(false);
        try (PreparedStatement bind = holder.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
            bind.setString(1, clinic.tenantId().toString());
            bind.execute();
        }
        try (PreparedStatement lock = holder.prepareStatement("select id from ipd.admission where id = ?::uuid " + clause)) {
            lock.setString(1, admissionId);
            assertThat(lock.executeQuery().next()).isTrue();
        }
        return holder;
    }

    private Req dischargeReq(Clinic clinic, Adm adm) throws Exception {
        return dischargeReq(clinic, adm, "normal");
    }

    private Req dischargeReq(Clinic clinic, Adm adm, String type) throws Exception {
        sign(clinic, adm.id(), type, "general");
        JsonNode sheet = sheet(clinic, adm.id());
        return new Req(HttpMethod.POST, dischargeUrl(adm.id()), clinic.owner(),
                dischargeBody(type, "general", totalOf(sheet), depositsOf(sheet), "UPI"));
    }

    @Test
    void aSaveQueuedBehindADischargeIsRefused409NotAdmittedAndWritesNothing() throws Exception {
        // Deterministic (review M8): a second connection holds the admission row; the discharge is started and seen
        // waiting in pg_stat_activity; only then is the save started and seen waiting; then the lock is released. The
        // discharge is first in the lock queue, so it commits first and the save re-checks the status under the lock.
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        Req discharge = dischargeReq(clinic, adm);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = holdAdmissionLock(clinic, adm.id(), "for update")) {
            Future<Resp> discharged = pool.submit(task(oneShot(), discharge));
            assertThat(awaitLockWaiters(ADMISSION_LOCK_UPDATE, 1, 30_000)).as("the discharge waits on the admission row").isTrue();
            Future<Resp> saving = pool.submit(task(oneShot(), saveReq(clinic, adm.id(), noted(medium3(), "Queued behind"))));
            assertThat(awaitLockWaiters(ADMISSION_LOCK_NO_KEY, 1, 30_000)).as("the save waits on the admission row").isTrue();
            assertThat(discharged.isDone()).isFalse();
            assertThat(saving.isDone()).isFalse();
            holder.rollback();

            Resp dischargeAnswer = discharged.get(60, TimeUnit.SECONDS);
            Resp saveAnswer = saving.get(60, TimeUnit.SECONDS);
            assertThat(dischargeAnswer.status()).as(dischargeAnswer.body()).isEqualTo(200);
            assertThat(saveAnswer.status()).as(saveAnswer.body()).isEqualTo(409);
            assertThat(body(saveAnswer).get("type").asText()).endsWith("not-admitted");
            assertThat(body(saveAnswer).get("detail").asText()).isEqualTo(VITALS_NOT_ADMITTED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(values(clinic, "select status from ipd.admission where id = ?::uuid", adm.id())).containsExactly("DISCHARGED");
        assertThat(vitalsCount(clinic, adm.id())).isZero();
        assertThat(noteCount(clinic, adm.id())).isZero();
        assertThat(eventCount(clinic, adm.id())).isZero();
        assertThat(escalationCount(clinic, adm.id())).isZero();
        assertThat(vitalsAudits(clinic)).isZero();
    }

    @Test
    void aSaveQueuedAheadOfALamaDischargeRaisesTheEscalationAfterTheSignOffSoTheDischargeIsRefusedUntilTheDoctorSignsAgain()
            throws Exception {
        // SAH-33 R2 (ADR-007): the doctor signed LAMA before the save raised the escalation, so the escalation started
        // after the sign-off. The save is first in the lock queue and commits first; the LAMA discharge behind it is
        // refused 409 escalation-open (the new text) and writes nothing. A new sign-off after the save covers the
        // escalation, and the same discharge then completes and closes it as "Closed at discharge". (A Normal discharge
        // is covered by SafetyConcurrencyIT.)
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        Req discharge = dischargeReq(clinic, adm, "lama");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = holdAdmissionLock(clinic, adm.id(), "for update")) {
            Future<Resp> saving = pool.submit(task(oneShot(), saveReq(clinic, adm.id(), noted(medium3(), "First in line"))));
            assertThat(awaitLockWaiters(ADMISSION_LOCK_NO_KEY, 1, 30_000)).as("the save waits on the admission row").isTrue();
            Future<Resp> discharged = pool.submit(task(oneShot(), discharge));
            assertThat(awaitLockWaiters(ADMISSION_LOCK_UPDATE, 1, 30_000)).as("the discharge waits on the admission row").isTrue();
            holder.rollback();

            Resp saveAnswer = saving.get(60, TimeUnit.SECONDS);
            Resp dischargeAnswer = discharged.get(60, TimeUnit.SECONDS);
            assertThat(saveAnswer.status()).as(saveAnswer.body()).isEqualTo(201);
            assertThat(body(saveAnswer).get("escalation").get("outcome").asText()).isEqualTo("RAISED");
            assertThat(dischargeAnswer.status()).as(dischargeAnswer.body()).isEqualTo(409);
            assertThat(body(dischargeAnswer).get("type").asText()).endsWith("escalation-open");
            assertThat(body(dischargeAnswer).get("detail").asText()).isEqualTo("This patient has an early warning escalation "
                    + "that started after the doctor signed the summary. A doctor must record the response or sign the summary again.");
        } finally {
            pool.shutdownNow();
        }
        assertThat(values(clinic, "select status from ipd.admission where id = ?::uuid", adm.id())).containsExactly("ADMITTED");
        assertThat(openEscalationCount(clinic, adm.id())).as("the refused discharge closed nothing").isOne();
        assertThat(rows(clinic, "discharge")).isZero();

        // the doctor signs again after the save; the same discharge completes in the same call
        Req again = dischargeReq(clinic, adm, "lama");
        JsonNode done = ok(HttpMethod.POST, again.url(), clinic.owner(), again.body(), 200);
        assertThat(done.get("id").asText()).isEqualTo(adm.id());
        assertThat(values(clinic, "select status from ipd.admission where id = ?::uuid", adm.id())).containsExactly("DISCHARGED");
        assertThat(vitalsCount(clinic, adm.id())).isOne();
        assertThat(noteCount(clinic, adm.id())).isOne();
        assertThat(escalationCount(clinic, adm.id())).isOne();
        assertThat(openEscalationCount(clinic, adm.id())).as("closed in the discharge transaction").isZero();
        assertThat(values(clinic, "select status || '|' || response_action from ipd.ews_escalation where admission_id = ?::uuid",
                adm.id())).containsExactly("DONE|CLOSED_AT_DISCHARGE");
        assertThat(vitalsAudits(clinic)).isOne();
    }

    // ---------------- a save against a bed transfer (code review M2) ----------------

    @Test
    void aSaveWaitingForABedTransferIsNeverAFalse404AndShowsTheNewBed() throws Exception {
        // The transfer locks the admission row and then its transfer history; a second connection holds an exclusive
        // lock on ipd.bed_transfer, so the transfer is stopped with the admission row locked. The save then waits for
        // that row. When the transfer commits, the bed of the admission has changed: with the old joined lock query
        // the save re-checked the new admission row against the old bed row and got a 404.
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        Adm adm = admitOne(clinic);
        UUID from = bedOf(clinic, adm.id());
        UUID target = newBed(clinic, "gen");
        String targetCode = values(clinic, "select code from ipd.bed where id = ?", target).get(0);
        assertThat(targetCode).isNotEqualTo(adm.bedCode());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection tableLock = db().migrator()) {
            tableLock.setAutoCommit(false);
            DbAccess.execute(tableLock, "lock table ipd.bed_transfer in access exclusive mode");
            Future<Resp> moving = pool.submit(() -> {
                var r = mvc.perform(request(HttpMethod.POST, URI.create(transferUrl(adm.id()))).with(clinic.owner())
                        .contentType(MediaType.APPLICATION_JSON).content(moveBody(target, null))).andReturn().getResponse();
                return new Resp(r.getStatus(), r.getContentAsString());
            });
            assertThat(awaitLockWaiters("%from ipd.bed_transfer%", 1, 30_000))
                    .as("the transfer waits for its history with the admission row locked").isTrue();
            Future<Resp> saving = pool.submit(task(oneShot(), saveReq(clinic, adm.id(), noted(medium3(), "During the move"))));
            assertThat(awaitLockWaiters(ADMISSION_LOCK_NO_KEY, 1, 30_000)).as("the save waits on the admission row").isTrue();
            assertThat(saving.isDone()).isFalse();
            tableLock.rollback();

            Resp moveAnswer = moving.get(60, TimeUnit.SECONDS);
            Resp saveAnswer = saving.get(60, TimeUnit.SECONDS);
            assertThat(moveAnswer.status()).as(moveAnswer.body()).isEqualTo(200);
            assertThat(saveAnswer.status()).as("never a false 404: " + saveAnswer.body()).isEqualTo(201);
            JsonNode saved = body(saveAnswer);
            assertThat(saved.get("bedCode").asText()).as("the save saw the bed after the move").isEqualTo(targetCode);
            assertThat(saved.get("status").asText()).isEqualTo("ADMITTED");
            assertThat(saved.get("escalation").get("outcome").asText()).isEqualTo("RAISED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(bedOf(clinic, adm.id())).isEqualTo(target);
        assertThat(bedOf(clinic, adm.id())).isNotEqualTo(from);
        assertThat(vitalsCount(clinic, adm.id())).isOne();
        assertThat(noteCount(clinic, adm.id())).isOne();
        assertThat(openEscalationCount(clinic, adm.id())).isOne();
        assertThat(vitalsAudits(clinic)).isOne();
    }

    @Test
    void savesAndTransfersStartedTogetherNeverGiveA404OrAServerError() throws Exception {
        // Not ordered by the test: the real race, several rounds. A save ends 201 (the patient stays admitted).
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        for (int round = 0; round < 4; round++) {
            Adm adm = admitOne(clinic);
            UUID target = newBed(clinic, "gen");
            Req move = new Req(HttpMethod.POST, transferUrl(adm.id()), clinic.owner(), moveBody(target, null));
            List<Resp> answers = parallel(saveReq(clinic, adm.id(), medium3()), move, saveReq(clinic, adm.id(), low()));
            assertThat(answers.get(1).status()).as("round " + round + " move " + answers.get(1).body()).isEqualTo(200);
            for (int i : new int[] {0, 2}) {
                assertThat(answers.get(i).status()).as("round " + round + " save " + answers.get(i).body()).isEqualTo(201);
            }
            assertThat(bedOf(clinic, adm.id())).isEqualTo(target);
            assertThat(vitalsCount(clinic, adm.id())).isEqualTo(2);
            assertThat(openEscalationCount(clinic, adm.id())).isOne();
        }
    }

    // ---------------- different admissions ----------------

    @Test
    void aSaveWaitsForTheLockOfItsOwnAdmissionOnlyAndSavesAndReadsOfOtherAdmissionsAreNotBlocked() throws Exception {
        Clinic clinic = hospital();
        Adm a = admitOne(clinic);
        Adm b = admitOne(clinic);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = db().app()) {
            holder.setAutoCommit(false);
            try (PreparedStatement bind = holder.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                bind.setString(1, clinic.tenantId().toString());
                bind.execute();
            }
            // another transaction holds the row lock of admission A, as a concurrent save would
            try (PreparedStatement lock = holder.prepareStatement("select id from ipd.admission where id = ?::uuid for no key update")) {
                lock.setString(1, a.id());
                assertThat(lock.executeQuery().next()).isTrue();
            }
            Future<Resp> blocked = pool.submit(task(oneShot(), saveReq(clinic, a.id(), medium3())));
            // poll the database until the save is really waiting on the lock (no fixed sleep)
            assertThat(awaitLockWaiters(ADMISSION_LOCK_NO_KEY, 1, 30_000)).as("the save is waiting on the admission row").isTrue();
            assertThat(blocked.isDone()).as("the save of the locked admission waits").isFalse();

            // other admissions of the same clinic: save, read, admit and the sheet of A itself are not blocked
            long started = System.nanoTime();
            JsonNode other = save(clinic, b.id(), high7());
            assertThat(other.get("saved").get("replayed").asBoolean()).isFalse();
            assertThat(nurseSheet(clinic, b.id()).get("latest").isNull()).isFalse();
            assertThat(nurseSheet(clinic, a.id()).get("latest").isNull()).as("a read takes no lock").isTrue();
            Adm c = admitOne(clinic);
            assertThat(nurseSheet(clinic, c.id()).get("canRecord").asBoolean()).isTrue();
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).as("seconds taken meanwhile").isLessThan(20);
            assertThat(blocked.isDone()).as("still waiting").isFalse();

            holder.rollback();
            Resp done = blocked.get(60, TimeUnit.SECONDS);
            assertThat(done.status()).isEqualTo(201);
            assertThat(body(done).get("escalation").get("outcome").asText()).isEqualTo("RAISED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(vitalsCount(clinic, a.id())).isOne();
        assertThat(vitalsCount(clinic, b.id())).isOne();
        assertThat(escalationCount(clinic, a.id())).isOne();
        assertThat(escalationCount(clinic, b.id())).isOne();
    }

    @Test
    void savesOnManyAdmissionsAtOnceAllCompleteAndEachHasItsOwnEscalation() throws Exception {
        Clinic clinic = hospital();
        List<Adm> admissions = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            admissions.add(admitOne(clinic));
        }
        Req[] reqs = new Req[admissions.size()];
        for (int i = 0; i < reqs.length; i++) {
            reqs[i] = saveReq(clinic, admissions.get(i).id(), i % 2 == 0 ? medium3() : high7());
        }
        List<Resp> answers = parallel(reqs);
        for (int i = 0; i < answers.size(); i++) {
            assertThat(answers.get(i).status()).isEqualTo(201);
            assertThat(body(answers.get(i)).get("escalation").get("outcome").asText()).isEqualTo("RAISED");
            assertThat(openEscalation(clinic, admissions.get(i).id())).isEqualTo(i % 2 == 0 ? "3|MEDIUM|60|OPEN|0" : "7|HIGH|15|OPEN|0");
        }
        assertThat(rows(clinic, "ews_escalation")).isEqualTo(6);
        assertThat(rows(clinic, "vitals")).isEqualTo(6);
    }

    private static CountDownLatch oneShot() {
        return new CountDownLatch(0);
    }
}
