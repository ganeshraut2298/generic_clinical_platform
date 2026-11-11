package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-34 ADR-017 D2 and design note 1.6 on the real {@code sahaj_test} database: the scope of a write is decided after the
 * admission row is locked, on data that cannot change under it. A transfer that moves the patient out of the nurse's ward and a
 * vitals save of that nurse that are in the lock queue together give the answer of the order in which they got the row (a second
 * connection holds the row so that the order is forced, the technique of {@link VitalsConcurrencyIT}); a grant that ends while a
 * dose waits for the row is refused when the row is free, with nothing written. Test plan: docs/test-plans/SAH-34.md.
 */
@OnTestDatabase
@WithPinPepper
class ScopeRaceIT extends ScopeSupport {

    /**
     * Code review finding 5: the grant ends this long after it is inserted (was 10 s). The dose request must reach the admission
     * row lock before that, which takes well under a second on a quiet machine; 30 s leaves a loaded gate machine room, and the
     * test fails loudly (it does not pass vacuously) if the dose still queued too late. The test lasts about this long.
     */
    private static final long GRANT_ENDS_IN_SECONDS = 30;

    /**
     * Any session waiting on a lock in a statement that touches the admission table. The lock-waiter match no longer depends on
     * the clause order or on the words "update" or "for no key update" of the repository's SQL (finding 5); the holder in this
     * test is the only other session that has the row.
     */
    private static final String ADMISSION_LOCK_ANY = "%ipd.admission%";

    @Test
    void aTransferOutOfTheNursesWardThatWinsTheLockRefusesTheVitalsSaveQueuedBehindItAndNothingIsWritten() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        setRules(clinic, false, "same");
        UUID target = bedIn(clinic, h.wardB());
        long vitalsBefore = vitalsCount(clinic, h.inA().id());
        List<Resp> answers = ordered(clinic, h.inA().id(),
                new Req(HttpMethod.POST, transferUrl(h.inA().id()), clinic.owner(), moveBody(target, null)),
                new Req(HttpMethod.POST, vitalsUrl(h.inA().id()), h.nurseA().auth(), js(low())));
        assertThat(answers.get(0).status()).as(answers.get(0).body()).isEqualTo(200);
        assertThat(answers.get(1).status()).as(answers.get(1).body()).isEqualTo(403);
        JsonNode p = answers.get(1).json(objectMapper);
        assertThat(p.get("type").asText()).endsWith("outside-scope");
        assertThat(p.get("detail").asText()).isEqualTo(OUTSIDE);
        assertThat(vitalsCount(clinic, h.inA().id())).as("the refused save wrote nothing").isEqualTo(vitalsBefore);
        assertThat(noteCount(clinic, h.inA().id())).isZero();
        assertThat(count(clinic, "select count(*) from ipd.clinical_event where admission_id = ?::uuid and text like 'Recorded vitals%'", h.inA().id())).isZero();
        assertThat(escalationCount(clinic, h.inA().id())).isZero();
        assertThat(bedOf(clinic, h.inA().id())).isEqualTo(target);
        // the next request of the nurse is refused as well
        outside(call(HttpMethod.GET, ADMISSIONS + "/" + h.inA().id(), h.nurseA().auth(), null));
    }

    @Test
    void aVitalsSaveThatWinsTheLockIsSavedAndTheTransferBehindItStillSucceeds() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        setRules(clinic, false, "same");
        UUID target = bedIn(clinic, h.wardB());
        List<Resp> answers = ordered(clinic, h.inA().id(),
                new Req(HttpMethod.POST, vitalsUrl(h.inA().id()), h.nurseA().auth(), js(low())),
                new Req(HttpMethod.POST, transferUrl(h.inA().id()), clinic.owner(), moveBody(target, null)));
        assertThat(answers.get(0).status()).as(answers.get(0).body()).isEqualTo(201);
        assertThat(answers.get(1).status()).as(answers.get(1).body()).isEqualTo(200);
        assertThat(vitalsCount(clinic, h.inA().id())).isOne();
        assertThat(bedOf(clinic, h.inA().id())).isEqualTo(target);
        outside(call(HttpMethod.POST, vitalsUrl(h.inA().id()), h.nurseA().auth(), js(low())));
        assertThat(vitalsCount(clinic, h.inA().id())).isOne();
    }

    @Test
    void aNursesOwnTransferOutOfHerWardWhileHerColleagueSavesVitalsInTheSameWardGivesTheColleagueTheRefusal() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        setRules(clinic, false, "same");
        Pn colleague = newPn(clinic, "NURSE");
        assign(clinic, h.wardA(), h.nurseA(), colleague);
        UUID target = bedIn(clinic, h.wardB());
        List<Resp> answers = ordered(clinic, h.inA().id(),
                new Req(HttpMethod.POST, transferUrl(h.inA().id()), h.nurseA().auth(), moveBody(target, null)),
                new Req(HttpMethod.POST, vitalsUrl(h.inA().id()), colleague.auth(), js(low())));
        assertThat(answers.get(0).status()).as("a write that starts in scope returns its normal answer: " + answers.get(0).body()).isEqualTo(200);
        assertThat(answers.get(1).status()).as(answers.get(1).body()).isEqualTo(403);
        assertThat(answers.get(1).type(objectMapper)).isEqualTo("outside-scope");
        assertThat(vitalsCount(clinic, h.inA().id())).isZero();
    }

    // ---------------- a grant that ends while a dose waits ----------------

    private Resp doseAfterWaiting(Full f, RequestPostProcessor auth, String grantId, long waitUntilEndedMillis) throws Exception {
        Clinic clinic = f.h().clinic();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = holdAdmissionLock(clinic, f.h().inB().id())) {
            Future<Resp> dose = pool.submit(() -> {
                var res = call(HttpMethod.POST, dosesUrl(f.orderId()), auth, js(notGiven(ist(8, 0), "NIL_BY_MOUTH"))).andReturn().getResponse();
                return new Resp(res.getStatus(), res.getContentAsString());
            });
            assertThat(awaitLockWaiters(ADMISSION_LOCK_ANY, 1, 60_000)).as("the dose waits on the admission row").isTrue();
            assertThat(dose.isDone()).isFalse();
            if (grantId != null) {
                Instant ends = instantOf(clinic, "select expires_at from ipd.emergency_access_grant where id = ?::uuid", grantId);
                // the race is only a race if the dose queued while the grant was still live: fail loudly, never pass vacuously
                assertThat(dbNow(clinic)).as("the dose queued before the grant ended (a loaded machine needs a wider margin)")
                        .isBefore(ends);
                while (dbNow(clinic).isBefore(ends.plusMillis(waitUntilEndedMillis))) {
                    Thread.sleep(100);
                }
            }
            holder.rollback();
            return dose.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aGrantThatEndsWhileTheDoseWaitsForTheAdmissionRowIsRefusedWhenTheRowIsFreeAndNothingIsWritten() throws Exception {
        Full f = full();
        Clinic clinic = f.h().clinic();
        Pn nurse = f.h().nurseA();
        String grant = insertGrantEndingIn(clinic, nurse, f.h().inB().id(), GRANT_ENDS_IN_SECONDS);
        String written = written(clinic);
        Resp answer = doseAfterWaiting(f, nurse.auth(), grant, 300);
        assertThat(answer.status()).as(answer.body()).isEqualTo(403);
        assertThat(answer.type(objectMapper)).isEqualTo("outside-scope");
        assertThat(answer.detail(objectMapper)).isEqualTo(OUTSIDE);
        assertThat(answer.body()).doesNotContain(f.h().inB().name());
        assertThat(doseRows(clinic, f.h().inB().id())).isZero();
        assertThat(denyEvents(clinic, f.h().inB().id()) + giveEvents(clinic, f.h().inB().id())).isZero();
        assertThat(written(clinic)).isEqualTo(written);
    }

    @Test
    void theSameDoseWithAGrantThatStillRunsIsRecordedSoTheRefusalAboveIsAboutTheExpiryAlone() throws Exception {
        Full f = full();
        Clinic clinic = f.h().clinic();
        Pn nurse = f.h().nurseA();
        insertGrantEndingIn(clinic, nurse, f.h().inB().id(), 600);
        Resp answer = doseAfterWaiting(f, nurse.auth(), null, 0);
        assertThat(answer.status()).as(answer.body()).isEqualTo(201);
        assertThat(doseRows(clinic, f.h().inB().id())).isOne();
    }

    @Test
    void aWardAssignmentRemovedWhileAVitalsSaveWaitsIsAppliedAtTheNextRequestAndTheWaitingSaveFinishes() throws Exception {
        // OQ-34-5 / design 1.6 item 2: an assignment change applies "at the next request"; a write already in the queue keeps the
        // scope it started with. This pins the accepted behaviour: no 500, no half-written save.
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        List<Resp> answers = ordered(clinic, h.inA().id(),
                new Req(HttpMethod.POST, vitalsUrl(h.inA().id()), h.nurseA().auth(), js(low())));
        assertThat(answers.get(0).status()).isEqualTo(201);
        ok(HttpMethod.PUT, WARD_ASSIGNMENTS + "/" + h.wardA(), clinic.owner(), "{\"membershipIds\":[]}", 200);
        outside(call(HttpMethod.POST, vitalsUrl(h.inA().id()), h.nurseA().auth(), js(low())));
        assertThat(vitalsCount(clinic, h.inA().id())).isOne();
    }
}
