package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.api.PinCheck;
import com.sahaj.platform.api.PinCheck.NotSetReason;
import com.sahaj.platform.api.PinCheck.Outcome;
import com.sahaj.platform.api.StaffPinException;
import com.sahaj.platform.api.StaffPinPort;
import com.sahaj.platform.support.LogCapture;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SAH-31 the PIN check that IPD verify calls ({@code platform.api.StaffPinPort}, ADR-010 D5, D6, D9, D13) on the real
 * {@code sahaj_test} database: wrong tries are counted and committed, the fifth locks for 15 minutes, a right PIN while
 * locked counts nothing, the second lock counts up, the third lock within 24 hours switches the PIN off, two parallel
 * wrong tries are both counted, the check refuses to run inside a transaction, the rate limit, the tenant. States the
 * application cannot reach (a lock already over, a window older than 24 hours) are written by the table owner with
 * the guard switched off ({@link PinTestSupport#forcePinRow}). Test plan: docs/test-plans/SAH-31.md.
 */
@OnTestDatabase
@WithPinPepper
class StaffPinCheckIT extends PinTestSupport {

    @Autowired
    private StaffPinPort port;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static String wrongFor(String right) {
        return "9306".equals(right) ? "9307" : "9306";
    }

    /** A check with a fresh allowance (the 10-per-5-minutes limit is tested on its own). */
    private PinCheck check(Pn p, String pin) {
        freshPinAllowance(p);
        return port.check(p.clinic().tenantId(), p.userId(), pin);
    }

    /** A person of a fresh hospital with a PIN set through the real endpoints. */
    private record WithPin(Pn p, String pin) {
    }

    private WithPin withPin(Clinic clinic, String role) throws Exception {
        Pn p = newPn(clinic, role);
        String pin = goodPin(p);
        setPin(p, pin);
        return new WithPin(p, pin);
    }

    private void wrongTries(WithPin w, int n) {
        for (int i = 0; i < n; i++) {
            PinCheck r = check(w.p(), wrongFor(w.pin()));
            assertThat(r.outcome()).isEqualTo(Outcome.WRONG);
        }
    }

    // ---------------- ok, not set ----------------

    @Test
    void aRightPinIsOkWithFiveTriesLeftAndWritesNothingWhenThereWereNoWrongTries() throws Exception {
        WithPin w = withPin(hospital(), "PHARMACIST");
        Clinic clinic = w.p().clinic();
        PinRow before = pinRow(clinic, w.p().membershipId());
        PinCheck r = check(w.p(), w.pin());
        assertThat(r.outcome()).isEqualTo(Outcome.OK);
        assertThat(r.triesLeft()).isEqualTo(5);
        assertThat(r.isOk()).isTrue();
        r.requireOk();
        assertThat(pinRow(clinic, w.p().membershipId())).isEqualTo(before);
        assertThat(r.toString()).doesNotContain(w.pin()).contains("OK");
    }

    @Test
    void aPersonWithoutAPinAndAPersonWithoutAMembershipBothGetNotSetAndNothingIsWritten() throws Exception {
        Clinic clinic = hospital();
        Pn never = newPn(clinic, "PHARMACIST");
        PinCheck r = check(never, "4827");
        assertThat(r.outcome()).isEqualTo(Outcome.NOT_SET);
        assertThat(r.notSetReason()).isEqualTo(NotSetReason.NEVER_SET);
        assertThatThrownBy(r::requireOk).isInstanceOfSatisfying(StaffPinException.class, e -> {
            assertThat(e.slug()).isEqualTo("pin-not-set");
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.getMessage()).isEqualTo(PIN_NEVER);
        });
        assertThat(pinRow(clinic, never.membershipId()).exists()).isFalse();

        WithPin w = withPin(clinic, "NURSE");
        setMembershipStatus(clinic.tenantId(), w.p().userId(), "INACTIVE");
        assertThat(check(w.p(), w.pin()).outcome()).as("an inactive membership is never a PIN").isEqualTo(Outcome.NOT_SET);
        assertThat(pinRow(clinic, w.p().membershipId()).failed()).isZero();
        // a user with no membership in the clinic at all
        PinCheck stranger = port.check(clinic.tenantId(), UUID.randomUUID(), "4827");
        assertThat(stranger.outcome()).isEqualTo(Outcome.NOT_SET);
    }

    @Test
    void theOutcomesMapToTheProblemTypesAndExactTextsOfAdr010D11() {
        assertProblem(new PinCheck(Outcome.WRONG, 4, null, 0), "wrong-pin", 403, "That PIN is not right. 4 tries left.");
        assertProblem(new PinCheck(Outcome.WRONG, 1, null, 0), "wrong-pin", 403, "That PIN is not right. 1 try left.");
        assertProblem(new PinCheck(Outcome.NOT_SET, 0, null, 0, NotSetReason.RESET_BY_OWNER), "pin-not-set", 409,
                PIN_RESET_TEXT);
        assertProblem(new PinCheck(Outcome.NOT_SET, 0, null, 0, NotSetReason.TOO_MANY_LOCKS), "pin-not-set", 409,
                PIN_SWITCHED_OFF);
        assertProblem(new PinCheck(Outcome.RATE_LIMITED, 0, null, 42), "rate-limit", 429, PIN_RATE);
        assertProblem(new PinCheck(Outcome.UNAVAILABLE, 0, null, 0), "pin-unavailable", 503, PIN_UNAVAILABLE);
    }

    private static void assertProblem(PinCheck check, String slug, int status, String detail) {
        assertThatThrownBy(check::requireOk).isInstanceOfSatisfying(StaffPinException.class, e -> {
            assertThat(e.slug()).isEqualTo(slug);
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.getMessage()).isEqualTo(detail);
        });
    }

    // ---------------- counting and the first lock ----------------

    @Test
    void wrongTriesAreCountedOneToFourWithTriesLeftAndARightPinResetsTheCountButNotTheLockCount() throws Exception {
        WithPin w = withPin(hospital(), "PHARMACIST");
        Clinic clinic = w.p().clinic();
        for (int n = 1; n <= 4; n++) {
            PinCheck r = check(w.p(), wrongFor(w.pin()));
            assertThat(r.outcome()).isEqualTo(Outcome.WRONG);
            assertThat(r.triesLeft()).isEqualTo(5 - n);
            assertThat(pinRow(clinic, w.p().membershipId()).failed()).isEqualTo(n);
            if (n == 4) {
                assertThatThrownBy(r::requireOk).hasMessage("That PIN is not right. 1 try left.");
            }
        }
        JsonNode status = pinStatus(w.p());
        assertThat(status.get("state").asText()).isEqualTo("SET");
        assertThat(status.get("triesLeft").asInt()).isEqualTo(1);
        long version = pinRow(clinic, w.p().membershipId()).version();
        assertThat(check(w.p(), w.pin()).outcome()).isEqualTo(Outcome.OK);
        PinRow after = pinRow(clinic, w.p().membershipId());
        assertThat(after.failed()).isZero();
        assertThat(after.lockCount()).isZero();
        assertThat(after.version()).isEqualTo(version + 1);
        assertThat(audit(clinic, "PLATFORM_PIN_LOCKED")).as("single wrong tries are not audited").isEmpty();
    }

    @Test
    void theFifthWrongTryLocksForFifteenMinutesAuditsOnceAndEvenTheRightPinCountsNothingWhileLocked() throws Exception {
        WithPin w = withPin(hospital(), "PHARMACIST");
        Clinic clinic = w.p().clinic();
        wrongTries(w, 4);
        Instant before = Instant.now();
        PinCheck fifth = check(w.p(), wrongFor(w.pin()));
        assertThat(fifth.outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(fifth.lockedUntil()).isBetween(before.plus(Duration.ofMinutes(14)), Instant.now().plus(Duration.ofMinutes(16)));
        assertThat(fifth.retryAfterSeconds()).isBetween(800L, 900L);
        assertThatThrownBy(fifth::requireOk).isInstanceOfSatisfying(StaffPinException.class, e -> {
            assertThat(e.slug()).isEqualTo("pin-locked");
            assertThat(e.status()).isEqualTo(403);
            assertThat(e.getMessage()).startsWith("Your PIN is locked after 5 wrong tries. Try again at ")
                    .satisfiesAnyOf(m -> assertThat(m).endsWith(" AM."), m -> assertThat(m).endsWith(" PM."));
            assertThat(e.retryAfterSeconds()).isPositive();
        });
        PinRow locked = pinRow(clinic, w.p().membershipId());
        assertThat(locked.failed()).isZero();
        assertThat(locked.lockCount()).isOne();
        assertThat(locked.lockedNow()).isTrue();
        assertThat(locked.hasWindow()).isTrue();
        assertThat(audit(clinic, "PLATFORM_PIN_LOCKED")).hasSize(1);
        String[] row = audit(clinic, "PLATFORM_PIN_LOCKED").get(0);
        assertThat(row[0]).isEqualTo("STAFF_PIN");
        assertThat(row[1]).isEqualTo(w.p().membershipId().toString());
        assertThat(row[2]).contains("SET");
        assertThat(row[3]).contains("LOCKED").contains("lockedUntil").contains("\"lockCount\"")
                .doesNotContain(locked.hash());
        assertThat(hasBarePin(row[3], w.pin())).isFalse();
        JsonNode status = pinStatus(w.p());
        assertThat(status.get("state").asText()).isEqualTo("LOCKED");
        assertThat(status.get("lockedUntil").isNull()).isFalse();
        assertThat(status.get("triesLeft").asInt()).isZero();

        // while locked: even the right PIN answers LOCKED, nothing is counted, nothing is written
        PinRow frozen = pinRow(clinic, w.p().membershipId());
        for (String pin : new String[] {w.pin(), wrongFor(w.pin()), w.pin()}) {
            PinCheck r = check(w.p(), pin);
            assertThat(r.outcome()).isEqualTo(Outcome.LOCKED);
            assertThat(r.lockedUntil()).isNotNull();
        }
        assertThat(pinRow(clinic, w.p().membershipId())).isEqualTo(frozen);
        assertThat(audit(clinic, "PLATFORM_PIN_LOCKED")).hasSize(1);
    }

    @Test
    void afterTheLockEndsTheRightPinIsOkAgainAndAWrongTryCountsFromOne() throws Exception {
        WithPin w = withPin(hospital(), "PHARMACIST");
        Clinic clinic = w.p().clinic();
        wrongTries(w, 4);
        assertThat(check(w.p(), wrongFor(w.pin())).outcome()).isEqualTo(Outcome.LOCKED);
        lockEnded(w.p());
        assertThat(pinStatus(w.p()).get("state").asText()).isEqualTo("SET");
        assertThat(check(w.p(), wrongFor(w.pin())).outcome()).isEqualTo(Outcome.WRONG);
        assertThat(pinRow(clinic, w.p().membershipId()).failed()).isOne();
        assertThat(check(w.p(), w.pin()).outcome()).isEqualTo(Outcome.OK);
        PinRow row = pinRow(clinic, w.p().membershipId());
        assertThat(row.failed()).isZero();
        assertThat(row.lockCount()).as("a right PIN does not clear the lock count").isOne();
    }

    // ---------------- second and third lock ----------------

    private String windowOf(Clinic clinic, UUID membershipId) throws Exception {
        return values(clinic, "select coalesce(lock_window_started_at::text, '') from platform.staff_pin "
                + "where membership_id = ?::uuid", membershipId.toString()).get(0);
    }

    @Test
    void theSecondLockInsideTheWindowCountsUpAndKeepsTheWindowStart() throws Exception {
        WithPin w = withPin(hospital(), "PHARMACIST");
        Clinic clinic = w.p().clinic();
        wrongTries(w, 4);
        assertThat(check(w.p(), wrongFor(w.pin())).outcome()).isEqualTo(Outcome.LOCKED);
        String window = windowOf(clinic, w.p().membershipId());
        assertThat(window).isNotEmpty();
        lockEnded(w.p());
        wrongTries(w, 4);
        PinCheck second = check(w.p(), wrongFor(w.pin()));
        assertThat(second.outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(second.lockedUntil()).isAfter(Instant.now().plus(Duration.ofMinutes(14)));
        PinRow row = pinRow(clinic, w.p().membershipId());
        assertThat(row.lockCount()).isEqualTo(2);
        assertThat(row.failed()).isZero();
        assertThat(windowOf(clinic, w.p().membershipId())).isEqualTo(window);
        assertThat(audit(clinic, "PLATFORM_PIN_LOCKED")).hasSize(2);
        assertThat(audit(clinic, "PLATFORM_PIN_CLEARED_AFTER_LOCKS")).isEmpty();
    }

    @Test
    void theThirdLockWithin24HoursSwitchesThePinOffWithTheClearedAuditAndAnswersNotSetTooManyLocksNeverLocked()
            throws Exception {
        WithPin w = withPin(hospital(), "PHARMACIST");
        Clinic clinic = w.p().clinic();
        String originalHash = pinRow(clinic, w.p().membershipId()).hash();
        for (int lock = 1; lock <= 2; lock++) {
            wrongTries(w, 4);
            assertThat(check(w.p(), wrongFor(w.pin())).outcome()).isEqualTo(Outcome.LOCKED);
            lockEnded(w.p());
        }
        wrongTries(w, 4);
        PinCheck third = check(w.p(), wrongFor(w.pin()));
        assertThat(third.outcome()).isEqualTo(Outcome.NOT_SET);
        assertThat(third.notSetReason()).isEqualTo(NotSetReason.TOO_MANY_LOCKS);
        assertThatThrownBy(third::requireOk).isInstanceOfSatisfying(StaffPinException.class, e -> {
            assertThat(e.slug()).isEqualTo("pin-not-set");
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.getMessage()).isEqualTo(PIN_SWITCHED_OFF);
        });
        PinRow off = pinRow(clinic, w.p().membershipId());
        assertThat(off.hasPin()).isFalse();
        assertThat(off.hasSetAt()).isFalse();
        assertThat(off.reason()).isEqualTo("TOO_MANY_LOCKS");
        assertThat(off.failed()).isZero();
        assertThat(off.lockCount()).isZero();
        assertThat(off.lockedNow()).isFalse();
        assertThat(off.hasWindow()).isFalse();
        assertThat(audit(clinic, "PLATFORM_PIN_LOCKED")).hasSize(2);
        List<String[]> cleared = audit(clinic, "PLATFORM_PIN_CLEARED_AFTER_LOCKS");
        assertThat(cleared).hasSize(1);
        assertThat(cleared.get(0)[3]).contains("NOT_SET").contains("TOO_MANY_LOCKS").doesNotContain(originalHash);
        // every later try (even with the right PIN) is the same until a new PIN is set
        assertThat(check(w.p(), w.pin()).notSetReason()).isEqualTo(NotSetReason.TOO_MANY_LOCKS);
        JsonNode status = pinStatus(w.p());
        assertThat(status.get("state").asText()).isEqualTo("NOT_SET");
        assertThat(status.get("notSetReason").asText()).isEqualTo("TOO_MANY_LOCKS");
        // a new PIN with a code switches it on again with clean counters
        String fresh = otherPin(w.p(), w.pin());
        setPin(w.p(), fresh);
        PinRow back = pinRow(clinic, w.p().membershipId());
        assertThat(back.hasPin()).isTrue();
        assertThat(back.reason()).isEmpty();
        assertThat(back.lockCount()).isZero();
        assertThat(check(w.p(), fresh).outcome()).isEqualTo(Outcome.OK);
        assertThat(check(w.p(), w.pin()).outcome()).isEqualTo(Outcome.WRONG);
    }

    @Test
    void aWindowOlderThan24HoursRestartsTheLockCountAtOneInsteadOfSwitchingOff() throws Exception {
        WithPin w = withPin(hospital(), "PHARMACIST");
        Clinic clinic = w.p().clinic();
        forceCounters(w.p(), 0, 2);
        windowStartedHoursAgo(w.p(), 25);
        wrongTries(w, 4);
        PinCheck r = check(w.p(), wrongFor(w.pin()));
        assertThat(r.outcome()).isEqualTo(Outcome.LOCKED);
        PinRow row = pinRow(clinic, w.p().membershipId());
        assertThat(row.hasPin()).isTrue();
        assertThat(row.lockCount()).isOne();
        assertThat(values(clinic, "select (lock_window_started_at > clock_timestamp() - interval '1 minute')::text from "
                + "platform.staff_pin where membership_id = ?::uuid", w.p().membershipId().toString()).get(0))
                .as("a new window").isEqualTo("true");
        assertThat(audit(clinic, "PLATFORM_PIN_CLEARED_AFTER_LOCKS")).isEmpty();
    }

    @Test
    void aWindowJustUnder24HoursStillSwitchesOffOnTheThirdLock() throws Exception {
        WithPin w = withPin(hospital(), "PHARMACIST");
        forceCounters(w.p(), 0, 2);
        windowStartedHoursAgo(w.p(), 23);
        wrongTries(w, 4);
        assertThat(check(w.p(), wrongFor(w.pin())).notSetReason()).isEqualTo(NotSetReason.TOO_MANY_LOCKS);
    }

    @Test
    void changingThePinWhileLockedWithACodeClearsTheLockAndTheOldPinIsWrongAfterwards() throws Exception {
        WithPin w = withPin(hospital(), "PHARMACIST");
        Clinic clinic = w.p().clinic();
        wrongTries(w, 4);
        assertThat(check(w.p(), wrongFor(w.pin())).outcome()).isEqualTo(Outcome.LOCKED);
        String fresh = otherPin(w.p(), w.pin());
        setPin(w.p(), fresh);
        PinRow row = pinRow(clinic, w.p().membershipId());
        assertThat(row.lockedNow()).isFalse();
        assertThat(row.lockCount()).isZero();
        assertThat(row.hasWindow()).isFalse();
        assertThat(check(w.p(), w.pin()).outcome()).isEqualTo(Outcome.WRONG);
        assertThat(check(w.p(), fresh).outcome()).isEqualTo(Outcome.OK);
        assertThat(audit(clinic, "PLATFORM_PIN_CHANGED")).hasSize(1);
        String[] changed = audit(clinic, "PLATFORM_PIN_CHANGED").get(0);
        assertThat(changed[2]).contains("LOCKED");
    }

    // ---------------- parallel, transaction, rate limit ----------------

    @Test
    void twoParallelWrongTriesAreBothCountedBecauseTheSecondWaitsForTheRowLock() throws Exception {
        WithPin w = withPin(hospital(), "PHARMACIST");
        Clinic clinic = w.p().clinic();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<PinCheck> task = () -> {
                start.await();
                return check(w.p(), wrongFor(w.pin()));
            };
            Future<PinCheck> a = pool.submit(task);
            Future<PinCheck> b = pool.submit(task);
            start.countDown();
            List<Integer> left = new ArrayList<>();
            for (Future<PinCheck> f : List.of(a, b)) {
                PinCheck r = f.get(60, TimeUnit.SECONDS);
                assertThat(r.outcome()).isEqualTo(Outcome.WRONG);
                left.add(r.triesLeft());
            }
            assertThat(left).containsExactlyInAnyOrder(4, 3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(pinRow(clinic, w.p().membershipId()).failed()).isEqualTo(2);
    }

    @Test
    void eightParallelWrongTriesLockOnceAndTheLaterOnesAnswerLockedWithoutCounting() throws Exception {
        WithPin w = withPin(hospital(), "PHARMACIST");
        Clinic clinic = w.p().clinic();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<PinCheck>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return check(w.p(), wrongFor(w.pin()));
                }));
            }
            start.countDown();
            int wrong = 0;
            int locked = 0;
            for (Future<PinCheck> f : futures) {
                PinCheck r = f.get(120, TimeUnit.SECONDS);
                if (r.outcome() == Outcome.WRONG) {
                    wrong++;
                } else if (r.outcome() == Outcome.LOCKED) {
                    locked++;
                } else {
                    throw new AssertionError("unexpected " + r);
                }
            }
            assertThat(wrong).isEqualTo(4);
            assertThat(locked).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
        PinRow row = pinRow(clinic, w.p().membershipId());
        assertThat(row.lockCount()).isOne();
        assertThat(row.failed()).isZero();
        assertThat(audit(clinic, "PLATFORM_PIN_LOCKED")).hasSize(1);
    }

    @Test
    void theCheckRefusesToRunInsideATransactionAndTouchesNothing() throws Exception {
        WithPin w = withPin(hospital(), "PHARMACIST");
        Clinic clinic = w.p().clinic();
        PinRow before = pinRow(clinic, w.p().membershipId());
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.execute(status -> check(w.p(), wrongFor(w.pin()))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside any transaction");
        assertThat(pinRow(clinic, w.p().membershipId())).isEqualTo(before);
        assertThatThrownBy(() -> port.check(null, w.p().userId(), "4827")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------- the limits (review fix pass: failures only, a bucket for saves of their own) ----------------

    @Test
    void tenFailedChecksInFiveMinutesAreAllowedWhateverTheirResultAndTheEleventhCheckIsRateLimitedBeforeTheComparison()
            throws Exception {
        // wrong, locked and not-set results all count as failures
        WithPin w = withPin(hospital(), "PHARMACIST");
        Clinic clinic = w.p().clinic();
        freshPinAllowance(w.p());
        for (int i = 0; i < 5; i++) {
            assertThat(port.check(clinic.tenantId(), w.p().userId(), wrongFor(w.pin())).outcome())
                    .isEqualTo(i < 4 ? Outcome.WRONG : Outcome.LOCKED);
        }
        for (int i = 0; i < 5; i++) {
            // the right PIN while locked: answered LOCKED, counts nothing in the database, but it is not an OK result
            assertThat(port.check(clinic.tenantId(), w.p().userId(), w.pin()).outcome()).isEqualTo(Outcome.LOCKED);
        }
        PinRow before = pinRow(clinic, w.p().membershipId());
        assertThat(before.lockedNow()).isTrue();
        PinCheck limited = port.check(clinic.tenantId(), w.p().userId(), w.pin());
        assertThat(limited.outcome()).isEqualTo(Outcome.RATE_LIMITED);
        assertThat(limited.retryAfterSeconds()).isPositive().isLessThanOrEqualTo(300);
        assertThatThrownBy(limited::requireOk).isInstanceOfSatisfying(StaffPinException.class, e -> {
            assertThat(e.slug()).isEqualTo("rate-limit");
            assertThat(e.status()).isEqualTo(429);
            assertThat(e.retryAfterSeconds()).isPositive();
        });
        // the comparison was not run: the row (counters, version) is exactly as it was
        assertThat(pinRow(clinic, w.p().membershipId())).isEqualTo(before);
        // the lock ends and the person is still limited (the allowance is time, not state)
        lockEnded(w.p());
        assertThat(port.check(clinic.tenantId(), w.p().userId(), w.pin()).outcome()).isEqualTo(Outcome.RATE_LIMITED);
        // another person is unaffected
        assertThat(check(newPn(clinic, "NURSE"), "4827").outcome()).isEqualTo(Outcome.NOT_SET);
    }

    @Test
    void tenNotSetChecksCountAsFailuresToo() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        freshPinAllowance(p);
        for (int i = 0; i < 10; i++) {
            assertThat(port.check(clinic.tenantId(), p.userId(), "4827").outcome()).isEqualTo(Outcome.NOT_SET);
        }
        PinCheck limited = port.check(clinic.tenantId(), p.userId(), "4827");
        assertThat(limited.outcome()).isEqualTo(Outcome.RATE_LIMITED);
        assertThat(limited.retryAfterSeconds()).isPositive();
        assertThat(pinRow(clinic, p.membershipId()).exists()).isFalse();
    }

    @Test
    void rightPinsNeverUseTheAllowanceTwelveVerifiesInARowAreAllOkAndAWrongTryAfterThemIsStillAnswered() throws Exception {
        WithPin w = withPin(hospital(), "PHARMACIST");
        Clinic clinic = w.p().clinic();
        freshPinAllowance(w.p());
        for (int i = 0; i < 12; i++) {
            assertThat(port.check(clinic.tenantId(), w.p().userId(), w.pin()).outcome()).as("check " + (i + 1))
                    .isEqualTo(Outcome.OK);
        }
        assertThat(port.check(clinic.tenantId(), w.p().userId(), wrongFor(w.pin())).outcome()).isEqualTo(Outcome.WRONG);
        assertThat(pinRow(clinic, w.p().membershipId()).failed()).isOne();
    }

    @Test
    void savingAPinHasABucketOfItsOwnTenSavesAreAllowedThenTheEleventhIs429AndChecksAreNotAffectedAndTheOtherWayRound()
            throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        freshPinAllowance(p);
        for (int i = 0; i < 10; i++) {
            assertType(problem(putPin(p, null, null, null), 400), "validation");
        }
        JsonNode limited = problem(putPin(p, null, null, null), 429);
        assertType(limited, "rate-limit");
        assertThat(limited.get("retryAfterSeconds").asInt()).isPositive();
        // the saves did not use the allowance of the checks
        assertThat(port.check(clinic.tenantId(), p.userId(), "4827").outcome()).isEqualTo(Outcome.NOT_SET);

        // and ten failed checks do not use the allowance of the saves
        Pn q = newPn(clinic, "NURSE");
        freshPinAllowance(q);
        for (int i = 0; i < 10; i++) {
            assertThat(port.check(clinic.tenantId(), q.userId(), "4827").outcome()).isEqualTo(Outcome.NOT_SET);
        }
        assertThat(port.check(clinic.tenantId(), q.userId(), "4827").outcome()).isEqualTo(Outcome.RATE_LIMITED);
        assertType(problem(putPin(q, null, null, null), 400), "validation");
    }

    // ---------------- tenant, copied hash, logs ----------------

    @Test
    void aUserOfOneClinicCheckedInAnotherClinicIsNotSetAndTheOriginalRowIsUntouched() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        WithPin w = withPin(a, "PHARMACIST");
        PinRow before = pinRow(a, w.p().membershipId());
        PinCheck r = port.check(b.tenantId(), w.p().userId(), w.pin());
        assertThat(r.outcome()).isEqualTo(Outcome.NOT_SET);
        assertThat(pinRow(a, w.p().membershipId())).isEqualTo(before);
        assertThat(pinRow(a, w.p().membershipId()).hasPin()).isTrue();
    }

    @Test
    void aHashCopiedFromAnotherPersonsRowNeverMatchesBecauseTheMembershipIsPartOfTheHashInput() throws Exception {
        Clinic clinic = hospital();
        Pn a = newPn(clinic, "PHARMACIST");
        Pn b = newPn(clinic, "DOCTOR");
        String pin = "4827";
        if (a.mobile().contains(pin) || b.mobile().contains(pin)) {
            pin = "7391";
        }
        setPin(a, pin);
        setPin(b, pin);
        forcePinRow(b, "pin_hash = (select pin_hash from platform.staff_pin where membership_id = '"
                + a.membershipId() + "'::uuid)");
        assertThat(check(a, pin).outcome()).isEqualTo(Outcome.OK);
        assertThat(check(b, pin).outcome()).as("the copied hash").isEqualTo(Outcome.WRONG);
    }

    @Test
    void noPinHashPepperOrMobileIsInAnyLogLineOfTheChecks() throws Exception {
        WithPin w = withPin(hospital(), "PHARMACIST");
        String hash = pinRow(w.p().clinic(), w.p().membershipId()).hash();
        String wrong = wrongFor(w.pin());
        try (LogCapture logs = new LogCapture()) {
            check(w.p(), wrong);
            check(w.p(), w.pin());
            wrongTries(w, 4);
            check(w.p(), wrong); // the lock
            check(w.p(), w.pin());
            String text = logs.allText();
            assertThat(text).contains("staff-pin-check").doesNotContain(PEPPER).doesNotContain(hash)
                    .doesNotContain(hash.substring(7, 27)).doesNotContain(w.p().mobile());
            assertThat(java.util.regex.Pattern.compile("(?<![0-9A-Za-z.:\\-])(" + w.pin() + "|" + wrong
                    + ")(?![0-9A-Za-z.:\\-])").matcher(text).find()).as("a PIN in a log line").isFalse();
        }
        assertThat(allAuditText(w.p().clinic())).doesNotContain(hash).doesNotContain(PEPPER)
                .doesNotContain(w.p().mobile());
    }

    @Test
    @SuppressWarnings("unused")
    void theStatusAfterOwnerResetAndSwitchOffIsNotSetWithTheMatchingReasonForTheCheck() throws Exception {
        WithPin w = withPin(hospital(), "PHARMACIST");
        resetPin(w.p().clinic(), w.p().membershipId());
        PinCheck r = check(w.p(), w.pin());
        assertThat(r.outcome()).isEqualTo(Outcome.NOT_SET);
        assertThat(r.notSetReason()).isEqualTo(NotSetReason.RESET_BY_OWNER);
        assertThatThrownBy(r::requireOk).hasMessage(PIN_RESET_TEXT);
        ok(HttpMethod.GET, ME_PIN, w.p().auth(), null, 200);
    }
}
