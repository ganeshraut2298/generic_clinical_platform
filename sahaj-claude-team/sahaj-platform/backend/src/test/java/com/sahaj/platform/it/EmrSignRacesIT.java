package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * SAH-51-2 and SAH-51-4: two sign requests at the same moment on the real database (ADR-025 D1 step 2: the record row lock
 * serialises them). Different keys: exactly one 200 and one 409 emr-record-signed, one signature, one follow-up, one audit
 * entry. The same key and body: both 200, one of them a replay, still one of everything. Test plan:
 * docs/test-plans/SAH-51-68.md (T-51-4.4, T-51-2.5).
 */
@OnTestDatabase
class EmrSignRacesIT extends EmrSignSupport {

    private List<MockHttpServletResponse> signTwice(Rec rec, String firstKey, String secondKey) throws Exception {
        String body = signBody(rec.revision(), "{}");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
        try {
            for (String key : new String[] {firstKey, secondKey}) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    return callKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), body, key).andReturn().getResponse();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<MockHttpServletResponse> out = new ArrayList<>();
            for (Future<MockHttpServletResponse> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoSignsWithDifferentKeysAtTheSameMomentGiveOneSignatureAndOne409AlreadySigned() throws Exception {
        Rec rec = chooseInterval(readyRec(), "D7");
        List<MockHttpServletResponse> answers = signTwice(rec, key(), key());
        List<Integer> statuses = new ArrayList<>();
        for (MockHttpServletResponse r : answers) {
            statuses.add(r.getStatus());
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        for (MockHttpServletResponse r : answers) {
            if (r.getStatus() == 409) {
                JsonNode problem = objectMapper.readTree(r.getContentAsString());
                assertThat(problem.get("type").asText()).isEqualTo(PROBLEM_BASE + "emr-record-signed");
                assertThat(problem.get("detail").asText()).startsWith("Already signed by Dr.");
            }
        }
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("SIGNED");
        assertThat(auditCount(rec.clinic(), "EMR_RECORD_SIGNED")).isOne();
        assertThat(followUpRows(rec.clinic(), rec.visitId())).isOne();
        assertThat(count(rec.clinic(), "select count(*) from opd.appointment")).isLessThanOrEqualTo(1);
    }

    @Test
    void theSameKeyAndBodyAtTheSameMomentGiveTwo200sOneOfThemAReplayAndStillOneOfEverything() throws Exception {
        Rec rec = chooseInterval(readyRec(), "D3");
        String key = key();
        List<MockHttpServletResponse> answers = signTwice(rec, key, key);
        int replays = 0;
        for (MockHttpServletResponse r : answers) {
            assertThat(r.getStatus()).isEqualTo(200);
            replays += objectMapper.readTree(r.getContentAsString()).get("replayed").asBoolean() ? 1 : 0;
        }
        assertThat(replays).isOne();
        assertThat(auditCount(rec.clinic(), "EMR_RECORD_SIGNED")).isOne();
        assertThat(followUpRows(rec.clinic(), rec.visitId())).isOne();
        assertThat(signColumns(rec.clinic(), rec.id()).get(3)).isEqualTo(key);
    }

    /**
     * Code review 2 (ADR-025 D1 step 5): the sign takes the visit row before it writes the follow-up, as the card move does, so
     * the two cannot wait for each other (SQLSTATE 40P01 would be a 500). Run on several records to give the interleaving a
     * chance; the clinic's follow-up step is on by default, so "Consult done" also makes the follow-up for the visit.
     */
    @Test
    void aSignAndAConsultDoneCardMoveAtTheSameMomentNeitherDeadlockNorAnswer500AndLeaveOneFollowUpAndOneSignature() throws Exception {
        for (int round = 0; round < 3; round++) {
            Rec rec = chooseInterval(readyRec(), "D7");
            String sign = signBody(rec.revision(), "{}");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            try {
                Future<MockHttpServletResponse> signing = pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    return callKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), sign, key()).andReturn().getResponse();
                });
                Future<MockHttpServletResponse> moving = pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    return callKeyed(HttpMethod.PATCH, VISITS + "/" + rec.visitId() + "/stage", rec.clinic().owner(),
                            "{\"stage\":\"BILLING\"}", null).andReturn().getResponse();
                });
                ready.await(10, TimeUnit.SECONDS);
                go.countDown();
                int signStatus = signing.get(90, TimeUnit.SECONDS).getStatus();
                int moveStatus = moving.get(90, TimeUnit.SECONDS).getStatus();
                assertThat(signStatus).as("round " + round + ": the sign").isEqualTo(200);
                assertThat(moveStatus).as("round " + round + ": the card move is never a server error or a deadlock").isLessThan(500);
            } finally {
                pool.shutdownNow();
            }
            assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("SIGNED");
            assertThat(auditCount(rec.clinic(), "EMR_RECORD_SIGNED")).isOne();
            assertThat(followUpRows(rec.clinic(), rec.visitId())).as("one follow-up for the visit, whoever made it").isOne();
            assertThat(boardVisit(rec.clinic(), rec.visitId()).get("stage").asText()).isNotEqualTo("WITH_DOCTOR");
        }
    }

    @Test
    void aSignAndASaveAtTheSameMomentLeaveEitherASignedRecordWithTheOldValuesOrARefusedSign() throws Exception {
        Rec rec = readyRec();
        String sign = signBody(rec.revision(), "{}");
        String save = draftBody(rec.revision(), "ASSESSMENT", null, sectionValues("hpi", "{\"narrative\":\"typed at the same time\"}")).toString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MockHttpServletResponse> signing = pool.submit(() -> callKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), sign, key())
                    .andReturn().getResponse());
            Future<MockHttpServletResponse> saving = pool.submit(() -> callKeyed(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/draft", rec.token(), save, null)
                    .andReturn().getResponse());
            int signStatus = signing.get(60, TimeUnit.SECONDS).getStatus();
            int saveStatus = saving.get(60, TimeUnit.SECONDS).getStatus();
            assertThat(signStatus).isIn(200, 409);
            assertThat(saveStatus).isIn(200, 409);
            assertThat(signStatus == 200 && saveStatus == 200).as("a save after the sign is refused, a save before it makes the revision old").isFalse();
            // whatever the order, a SIGNED record is never changed after the signature: the save either came first (then the sign
            // answered 409 emr-record-changed) or it was refused as signed
            if (signStatus == 200) {
                assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("SIGNED");
            } else {
                assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("DRAFT");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(auditCount(rec.clinic(), "EMR_RECORD_SIGNED")).isLessThanOrEqualTo(1);
    }
}
