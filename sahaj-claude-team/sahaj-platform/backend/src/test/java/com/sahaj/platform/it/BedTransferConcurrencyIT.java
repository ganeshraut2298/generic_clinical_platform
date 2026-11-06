package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * SAH-26 moves that happen at the same time, on the real {@code sahaj_test} database: two people moving two patients
 * to the same bed, a double click, patients moving into each other's beds (no deadlock), and the row lock on the
 * admission taken by the real {@code select id, status from ipd.admission ... for update} statement. Test plan: docs/test-plans/SAH-26.md.
 */
@OnTestDatabase
class BedTransferConcurrencyIT extends BedTransferSupport {

    private record Resp(int status, String body) {
    }

    private Callable<Resp> moveTask(CountDownLatch start, Clinic clinic, String admissionId, UUID toBed) {
        return () -> {
            if (start != null) {
                start.await();
            }
            var r = mvc.perform(post(transferUrl(admissionId)).with(clinic.owner()).contentType(MediaType.APPLICATION_JSON)
                    .content(moveBody(toBed, null))).andReturn().getResponse();
            return new Resp(r.getStatus(), r.getContentAsString());
        };
    }

    /** Starts all moves at the same moment and collects the answers; fails if one does not finish in a minute. */
    private List<Resp> parallel(Clinic clinic, String[] admissionIds, UUID[] targets) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(admissionIds.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Resp>> futures = new ArrayList<>();
            for (int i = 0; i < admissionIds.length; i++) {
                futures.add(pool.submit(moveTask(start, clinic, admissionIds[i], targets[i])));
            }
            start.countDown();
            List<Resp> out = new ArrayList<>();
            for (Future<Resp> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertBedTaken(Resp r) throws Exception {
        assertThat(r.status()).as(r.body()).isEqualTo(409);
        JsonNode p = objectMapper.readTree(r.body());
        assertThat(p.get("type").asText()).endsWith("bed-not-free");
        assertThat(p.get("detail").asText()).isEqualTo(BED_TAKEN);
    }

    // ---------------- two people, one bed ----------------

    @Test
    void twoPatientsMovedToTheSameFreeBedAtOnceGiveExactlyOne200AndOne409BedNotFree() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        for (int round = 1; round <= 4; round++) {
            String a = quickAdmit(clinic).get("id").asText();
            String b = quickAdmit(clinic).get("id").asText();
            UUID bedA = bedOf(clinic, a);
            UUID bedB = bedOf(clinic, b);
            UUID target = newBed(clinic, "gen");
            long before = transferRows(clinic);

            List<Resp> answers = parallel(clinic, new String[] {a, b}, new UUID[] {target, target});

            List<Integer> statuses = List.of(answers.get(0).status(), answers.get(1).status());
            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(200, 409);
            int winner = answers.get(0).status() == 200 ? 0 : 1;
            assertBedTaken(answers.get(1 - winner));
            String winnerId = winner == 0 ? a : b;
            String loserId = winner == 0 ? b : a;
            assertThat(bedOf(clinic, winnerId)).isEqualTo(target);
            assertThat(bedOf(clinic, loserId)).isEqualTo(winner == 0 ? bedB : bedA);
            assertThat(bedStatus(clinic, target)).isEqualTo("occ");
            assertThat(bedStatus(clinic, winner == 0 ? bedA : bedB)).as("winner's old bed").isEqualTo("clean");
            assertThat(bedStatus(clinic, winner == 0 ? bedB : bedA)).as("loser's bed untouched").isEqualTo("occ");
            assertThat(transferRows(clinic)).isEqualTo(before + 1);
            assertThat(count(clinic, "select count(*) from ipd.bed_transfer where admission_id = ?::uuid", loserId)).isZero();
        }
        assertThat(audits(clinic, "IPD_BED_TRANSFER")).isEqualTo(4);
    }

    @Test
    void fourPatientsMovedToTheSameBedAtOnceGiveOne200AndThree409() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        String[] ids = new String[4];
        UUID[] originals = new UUID[4];
        for (int i = 0; i < 4; i++) {
            ids[i] = quickAdmit(clinic).get("id").asText();
            originals[i] = bedOf(clinic, ids[i]);
        }
        UUID target = newBed(clinic, "gen");
        List<Resp> answers = parallel(clinic, ids, new UUID[] {target, target, target, target});
        int winners = 0;
        for (Resp r : answers) {
            if (r.status() == 200) {
                winners++;
            } else {
                assertBedTaken(r);
            }
        }
        assertThat(winners).isOne();
        assertThat(transferRows(clinic)).isOne();
        assertThat(count(clinic, "select count(*) from ipd.admission where bed_id = ?", target)).isOne();
        assertThat(count(clinic, "select count(*) from ipd.bed where status = 'clean'")).isOne();
        assertThat(count(clinic, "select count(*) from ipd.bed where status = 'occ'")).isEqualTo(4);
        assertThat(audits(clinic, "IPD_BED_TRANSFER")).isOne();
    }

    @Test
    void aDoubleSubmitOfTheSameMoveSucceedsOnceAndTheSecondIsRefusedWithNothingWrittenTwice() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        for (int round = 1; round <= 3; round++) {
            String id = quickAdmit(clinic).get("id").asText();
            UUID from = bedOf(clinic, id);
            UUID target = newBed(clinic, "gen");
            long before = transferRows(clinic);

            List<Resp> answers = parallel(clinic, new String[] {id, id}, new UUID[] {target, target});

            assertThat(List.of(answers.get(0).status(), answers.get(1).status())).as("round " + round)
                    .contains(200).doesNotContain(500);
            int ok = 0;
            for (Resp r : answers) {
                if (r.status() == 200) {
                    ok++;
                    continue;
                }
                // AC 8 says 409 bed-not-free. The second request waits for the clinic lock, then finds the patient
                // already in the bed, so the server answers the same-bed check first: 400 on toBedId.
                JsonNode p = objectMapper.readTree(r.body());
                boolean bedNotFree = r.status() == 409 && p.get("type").asText().endsWith("bed-not-free");
                boolean alreadyThere = r.status() == 400 && "toBedId".equals(p.get("fieldErrors").get(0).get("field").asText())
                        && SAME_BED.equals(p.get("fieldErrors").get(0).get("message").asText());
                assertThat(bedNotFree || alreadyThere).as("second answer: " + r).isTrue();
            }
            assertThat(ok).isOne();
            assertThat(bedOf(clinic, id)).isEqualTo(target);
            assertThat(bedStatus(clinic, from)).isEqualTo("clean");
            assertThat(bedStatus(clinic, target)).isEqualTo("occ");
            assertThat(transferRows(clinic)).isEqualTo(before + 1);
        }
    }

    // ---------------- no deadlock ----------------

    @Test
    void twoPatientsMovingIntoEachOthersBedsNeverDeadlockAndEveryAnswerIsA409() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        String a = quickAdmit(clinic).get("id").asText();
        String b = quickAdmit(clinic).get("id").asText();
        UUID bedA = bedOf(clinic, a);
        UUID bedB = bedOf(clinic, b);
        for (int i = 0; i < 8; i++) {
            List<Resp> answers = parallel(clinic, new String[] {a, b}, new UUID[] {bedB, bedA});
            for (Resp r : answers) {
                assertBedTaken(r);
            }
        }
        assertThat(bedOf(clinic, a)).isEqualTo(bedA);
        assertThat(bedOf(clinic, b)).isEqualTo(bedB);
        assertThat(transferRows(clinic)).isZero();

        // one of them goes to a free bed while the other asks for the first one's bed: the second can only get a 409
        // (the bed is Occupied or Cleaning), whichever request runs first
        UUID free = newBed(clinic, "gen");
        List<Resp> answers = parallel(clinic, new String[] {a, b}, new UUID[] {free, bedA});
        assertThat(answers.get(0).status()).as(answers.get(0).body()).isEqualTo(200);
        assertBedTaken(answers.get(1));
        assertThat(bedOf(clinic, a)).isEqualTo(free);
        assertThat(bedOf(clinic, b)).isEqualTo(bedB);
        assertThat(bedStatus(clinic, bedA)).isEqualTo("clean");
        assertThat(transferRows(clinic)).isOne();
    }

    // ---------------- the real row lock ----------------

    private static void bindTenant(Connection c, UUID tenantId) throws SQLException {
        try (var bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
            bind.setString(1, tenantId.toString());
            bind.execute();
        }
    }

    /**
     * True when some session of this database is waiting on a lock in the bare admission lock statement
     * ({@code select id, status from ipd.admission ... for update}; the code review M2 split the lock from the read).
     */
    private boolean someoneWaitsOnTheAdmissionRowLock() throws SQLException {
        return db().asApp(c -> DbAccess.count(c, "select count(*) from pg_stat_activity where datname = current_database() "
                + "and wait_event_type = 'Lock' and pid <> pg_backend_pid() "
                + "and query like '%from ipd.admission%for update%'")) > 0;
    }

    @Test
    void theMoveWaitsOnTheAdmissionRowLockAndRechecksTheStateAfterTheLockIsReleased() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        String id = quickAdmit(clinic).get("id").asText();
        UUID from = bedOf(clinic, id);
        UUID target = newBed(clinic, "gen");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection hold = db().app()) {
            hold.setAutoCommit(false);
            bindTenant(hold, clinic.tenantId());
            // another session (a discharge in progress) holds the admission row
            DbAccess.execute(hold, "select id from ipd.admission where id = ?::uuid for update", id);

            Future<Resp> move = pool.submit(moveTask(null, clinic, id, target));
            boolean waiting = false;
            for (int i = 0; i < 100 && !waiting && !move.isDone(); i++) {
                waiting = someoneWaitsOnTheAdmissionRowLock();
                if (!waiting) {
                    Thread.sleep(100);
                }
            }
            assertThat(move.isDone()).as("the move must wait for the admission row lock").isFalse();
            assertThat(waiting).as("the real 'select id, status from ipd.admission ... for update' statement is waiting on the lock").isTrue();
            Thread.sleep(500);
            assertThat(move.isDone()).isFalse();

            // the discharge commits; the move then re-reads the locked row and sees the patient is not admitted
            DbAccess.execute(hold, "update ipd.admission set status = 'DISCHARGED' where id = ?::uuid", id);
            hold.commit();
            Resp r = move.get(30, TimeUnit.SECONDS);
            assertThat(r.status()).as(r.body()).isEqualTo(409);
            JsonNode p = objectMapper.readTree(r.body());
            assertThat(p.get("type").asText()).endsWith("not-admitted");
            assertThat(p.get("detail").asText()).isEqualTo(NOT_ADMITTED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(transferRows(clinic)).isZero();
        assertThat(bedOf(clinic, id)).isEqualTo(from);
        assertThat(bedStatus(clinic, target)).isEqualTo("free");
        assertThat(bedStatus(clinic, from)).isEqualTo("occ");
        assertThat(audits(clinic, "IPD_BED_TRANSFER")).isZero();
    }

    @Test
    void whenTheOtherSessionOnlyReleasesTheLockTheWaitingMoveGoesThrough() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        String id = quickAdmit(clinic).get("id").asText();
        UUID from = bedOf(clinic, id);
        UUID target = newBed(clinic, "gen");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection hold = db().app()) {
            hold.setAutoCommit(false);
            bindTenant(hold, clinic.tenantId());
            DbAccess.execute(hold, "select id from ipd.admission where id = ?::uuid for update", id);
            Future<Resp> move = pool.submit(moveTask(null, clinic, id, target));
            Thread.sleep(800);
            assertThat(move.isDone()).isFalse();
            hold.rollback();
            Resp r = move.get(30, TimeUnit.SECONDS);
            assertThat(r.status()).as(r.body()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(bedOf(clinic, id)).isEqualTo(target);
        assertThat(bedStatus(clinic, from)).isEqualTo("clean");
        assertThat(transferRows(clinic)).isOne();
    }
}
