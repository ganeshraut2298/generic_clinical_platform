package com.sahaj.platform.devtools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.platform.application.admin.AdminPasswordHasher;
import com.sahaj.platform.devtools.bootstrap.BootstrapBodies.RegisterBody;
import com.sahaj.platform.devtools.bootstrap.BootstrapBodies.UnlockBody;
import com.sahaj.platform.devtools.bootstrap.BootstrapRepository.Claim;
import com.sahaj.platform.devtools.bootstrap.BootstrapRepository.ClaimOutcome;
import com.sahaj.platform.devtools.bootstrap.BootstrapRepository.Failure;
import com.sahaj.platform.devtools.bootstrap.BootstrapRepository.RegisterReply;
import com.sahaj.platform.devtools.bootstrap.BootstrapRepository.RegisterResult;
import com.sahaj.platform.devtools.bootstrap.BootstrapRepository.Status;
import com.sahaj.platform.devtools.bootstrap.BootstrapRepository.TicketState;
import com.sahaj.platform.web.Problems;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The rules of ADR-018 D4 to D6 (Revision 3, "count first, judge after") with the database and the BCrypt compare
 * faked. A recording transaction manager and one shared event list prove the order: the claim commits before the PIN is
 * compared, the compare runs outside any transaction, the judge is its own transaction, and a failed judge or commit
 * never produces an answer. Throwaway values only.
 */
class BootstrapServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final String SECRET = "a-throwaway-admin-secret-for-tests-only-123";
    private static final String TICKET = "T".repeat(43);
    private static final String BINDING = "C".repeat(43);

    private final BootstrapRepository repository = mock(BootstrapRepository.class);
    private final BootstrapPinVerifier verifier = mock(BootstrapPinVerifier.class);
    private final BootstrapTicketHasher ticketHasher = new BootstrapTicketHasher(SECRET);
    private final List<String> events = new ArrayList<>();
    private final RecordingTransactionManager transactions = new RecordingTransactionManager(events);
    private final MovingClock clock = new MovingClock(NOW);
    private final List<Duration> pauses = new ArrayList<>();
    private BootstrapService service;

    /** Writes "tx", "tx read-only", "commit" and "rollback" into the shared event list; one commit can be made to fail. */
    static final class RecordingTransactionManager implements PlatformTransactionManager {
        private final List<String> events;
        private int commits;
        private int failCommitNumber = -1;

        RecordingTransactionManager(List<String> events) {
            this.events = events;
        }

        void failCommit(int number) {
            failCommitNumber = number;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            events.add(definition.isReadOnly() ? "tx read-only" : "tx");
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
            commits++;
            if (commits == failCommitNumber) {
                events.add("commit failed");
                throw new TransactionSystemException("commit failed");
            }
            events.add("commit");
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
            events.add("rollback");
        }
    }

    /** A clock the test (and the fake pause) moves. */
    static final class MovingClock extends Clock {
        private Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        service = new BootstrapService(repository, verifier, ticketHasher, new AdminPasswordHasher(new SecureRandom()),
                new SecureRandom(), clock, transactions, duration -> {
                    pauses.add(duration);
                    clock.advance(duration);
                });
    }

    private static UnlockBody pin(String value) {
        return new UnlockBody(true, value);
    }

    private static TicketState state(Status status) {
        return new TicketState(status, null);
    }

    private static RegisterReply reply(RegisterResult result) {
        return new RegisterReply(result, null);
    }

    /** The claim answers these in turn (the last one repeats) and notes "begin" in the event list. */
    private void claims(Claim first, Claim... more) {
        Deque<Claim> queue = new ArrayDeque<>(List.of(first));
        queue.addAll(List.of(more));
        when(repository.begin(any())).thenAnswer(invocation -> {
            events.add("begin");
            return queue.size() > 1 ? queue.poll() : queue.peek();
        });
    }

    private void counted(int failures) {
        claims(new Claim(ClaimOutcome.COUNTED, failures, null));
    }

    private void compareGives(boolean correct) {
        when(verifier.matches(any())).thenAnswer(invocation -> {
            events.add("compare");
            return correct;
        });
    }

    private void judgeFailedGives(int failures, Instant lockedUntil) {
        when(repository.unlockFailed(any(), any(), any())).thenAnswer(invocation -> {
            events.add("judge-failed");
            return new Failure(failures, lockedUntil);
        });
    }

    // ================================================================== unlock: order (R3-U1)

    @Test
    void aWrongPinIsClaimedAndCommittedThenComparedOutsideAnyTransactionThenJudgedInItsOwnTransaction() {
        counted(2);
        compareGives(false);
        judgeFailedGives(2, null);

        BootstrapService.UnlockOutcome outcome = service.unlock(pin("654321"), "127.0.0.1", "req-1");

        assertThat(outcome.kind()).isEqualTo(BootstrapService.UnlockKind.WRONG_PIN);
        assertThat(outcome.triesLeft()).isEqualTo(3);
        assertThat(events).containsExactly("tx", "begin", "commit", "compare", "tx", "judge-failed", "commit");
        ArgumentCaptor<UUID> claimed = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> judged = ArgumentCaptor.forClass(UUID.class);
        InOrder order = inOrder(repository, verifier);
        order.verify(repository).begin(claimed.capture());
        order.verify(verifier).matches(any());
        order.verify(repository).unlockFailed(judged.capture(), eq("127.0.0.1"), eq("req-1"));
        assertThat(judged.getValue()).isEqualTo(claimed.getValue());
        verify(repository, never()).unlockSucceeded(any(), any(), any());
        verify(repository, never()).ticketStatus(any(), any());
    }

    @Test
    void everyUnlockMakesANewTryId() {
        counted(1);
        compareGives(false);
        judgeFailedGives(1, null);

        service.unlock(pin("654321"), "127.0.0.1", "req");
        service.unlock(pin("654321"), "127.0.0.1", "req");

        ArgumentCaptor<UUID> tries = ArgumentCaptor.forClass(UUID.class);
        verify(repository, times(2)).begin(tries.capture());
        assertThat(tries.getAllValues().get(0)).isNotEqualTo(tries.getAllValues().get(1));
    }

    // ================================================================== forced failures (R3-U2 to R3-U5)

    @Test
    void whenTheWrongPinJudgeFailsTheClaimStaysCommittedTheJudgeRollsBackAndNothingIsAnswered() {
        counted(1);
        compareGives(false);
        for (String sqlState : new String[] {"P0001", "SB004", "SB003", "55P03", "42501", "22023"}) {
            events.clear();
            org.mockito.Mockito.doAnswer(invocation -> {
                events.add("judge-failed");
                throw new BootstrapExceptions.Failed(sqlState);
            }).when(repository).unlockFailed(any(), any(), any());

            assertThatThrownBy(() -> service.unlock(pin("654321"), "127.0.0.1", "req")).as(sqlState)
                    .isInstanceOf(BootstrapExceptions.Failed.class);

            assertThat(events).as(sqlState)
                    .containsExactly("tx", "begin", "commit", "compare", "tx", "judge-failed", "rollback");
        }
    }

    @Test
    void aRawDriverFailureFromTheWrongPinJudgeIsNotAnAnswerEither() {
        counted(1);
        compareGives(false);
        when(repository.unlockFailed(any(), any(), any())).thenThrow(new DataIntegrityViolationException("row"));

        assertThatThrownBy(() -> service.unlock(pin("654321"), "127.0.0.1", "req"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aLockWaitOverTenSecondsInTheJudgeIsAFailureNotATooManyRequestsAnswer() {
        counted(1);
        compareGives(false);
        when(repository.unlockFailed(any(), any(), any())).thenThrow(new BootstrapExceptions.Busy());

        assertThatThrownBy(() -> service.unlock(pin("654321"), "127.0.0.1", "req"))
                .isInstanceOfSatisfying(BootstrapExceptions.Failed.class,
                        failed -> assertThat(failed.sqlState()).isEqualTo("55P03"));

        org.mockito.Mockito.doThrow(new BootstrapExceptions.Busy()).when(repository)
                .unlockSucceeded(any(), any(), any());
        compareGives(true);
        assertThatThrownBy(() -> service.unlock(pin("246813"), "127.0.0.1", "req"))
                .isInstanceOf(BootstrapExceptions.Failed.class);
    }

    @Test
    void anAdminThatAppearedBetweenTheClaimAndTheJudgeIsTheEmpty404InBothJudges() {
        counted(1);
        compareGives(false);
        when(repository.unlockFailed(any(), any(), any())).thenThrow(new BootstrapExceptions.Closed());
        assertThatThrownBy(() -> service.unlock(pin("654321"), "127.0.0.1", "req"))
                .isInstanceOf(BootstrapExceptions.Closed.class);

        compareGives(true);
        org.mockito.Mockito.doThrow(new BootstrapExceptions.Closed()).when(repository)
                .unlockSucceeded(any(), any(), any());
        assertThatThrownBy(() -> service.unlock(pin("246813"), "127.0.0.1", "req"))
                .isInstanceOf(BootstrapExceptions.Closed.class);
    }

    @Test
    void whenTheRightPinJudgeFailsThereIsNoTicketAndNoCookieOnlyTheFailure() {
        counted(1);
        compareGives(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            events.add("judge-ok");
            throw new BootstrapExceptions.Failed("SB004");
        }).when(repository).unlockSucceeded(any(), any(), any());

        assertThatThrownBy(() -> service.unlock(pin("246813"), "127.0.0.1", "req"))
                .isInstanceOf(BootstrapExceptions.Failed.class);

        assertThat(events).containsExactly("tx", "begin", "commit", "compare", "tx", "judge-ok", "rollback");
        verify(repository, never()).unlockFailed(any(), any(), any());
    }

    @Test
    void whenTheJudgeCommitFailsThereIsNoAnswerEither() {
        counted(2);
        compareGives(false);
        judgeFailedGives(2, null);
        transactions.failCommit(2);

        assertThatThrownBy(() -> service.unlock(pin("654321"), "127.0.0.1", "req"))
                .isInstanceOf(TransactionSystemException.class);
        assertThat(events).containsExactly("tx", "begin", "commit", "compare", "tx", "judge-failed", "commit failed");
    }

    @Test
    void whenTheVerifierThrowsTheTryStaysCountedAndNoJudgeRuns() {
        counted(3);
        when(verifier.matches(any())).thenAnswer(invocation -> {
            events.add("compare");
            throw new IllegalStateException("verifier broke");
        });

        assertThatThrownBy(() -> service.unlock(pin("654321"), "127.0.0.1", "req"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(events).containsExactly("tx", "begin", "commit", "compare");
        verify(repository, never()).unlockFailed(any(), any(), any());
        verify(repository, never()).unlockSucceeded(any(), any(), any());
    }

    @Test
    void whenTheClaimCommitFailsTheCompareNeverStarts() {
        counted(1);
        compareGives(true);
        transactions.failCommit(1);

        assertThatThrownBy(() -> service.unlock(pin("246813"), "127.0.0.1", "req"))
                .isInstanceOf(TransactionSystemException.class);

        assertThat(events).containsExactly("tx", "begin", "commit failed");
        verifyNoInteractions(verifier);
        verify(repository, never()).unlockFailed(any(), any(), any());
        verify(repository, never()).unlockSucceeded(any(), any(), any());
    }

    @Test
    void whenTheClaimFailsTheCompareNeverStartsAndThatTransactionRollsBack() {
        when(repository.begin(any())).thenAnswer(invocation -> {
            events.add("begin");
            throw new BootstrapExceptions.Failed("40001");
        });

        assertThatThrownBy(() -> service.unlock(pin("246813"), "127.0.0.1", "req"))
                .isInstanceOf(BootstrapExceptions.Failed.class);

        assertThat(events).containsExactly("tx", "begin", "rollback");
        verifyNoInteractions(verifier);
    }

    // ================================================================== busy turn (R3-U6)

    @Test
    void aBusyTurnIsRetriedEveryQuarterSecondForTenSecondsThenIsTooManyRequestsAndNeverCompared() {
        claims(new Claim(ClaimOutcome.BUSY, 2, null));

        assertThatThrownBy(() -> service.unlock(pin("654321"), "127.0.0.1", "req"))
                .isInstanceOf(BootstrapExceptions.Busy.class);

        // attempts at 0, 250, ... , 10,000 ms: 41, each its own transaction that commits (nothing was written)
        assertThat(events.stream().filter("tx"::equals).count()).isEqualTo(41);
        assertThat(events.stream().filter("commit"::equals).count()).isEqualTo(41);
        assertThat(events).doesNotContain("rollback");
        assertThat(pauses).hasSize(40).containsOnly(Duration.ofMillis(250));
        assertThat(clock.instant()).isEqualTo(NOW.plusSeconds(10));
        verifyNoInteractions(verifier);
        verify(repository, never()).unlockFailed(any(), any(), any());
    }

    @Test
    void aBusyTurnThatClearsWithinTenSecondsIsCountedAndJudgedAsUsual() {
        claims(new Claim(ClaimOutcome.BUSY, 1, null), new Claim(ClaimOutcome.BUSY, 1, null),
                new Claim(ClaimOutcome.COUNTED, 2, null));
        compareGives(false);
        judgeFailedGives(2, null);

        BootstrapService.UnlockOutcome outcome = service.unlock(pin("654321"), "127.0.0.1", "req");

        assertThat(outcome.kind()).isEqualTo(BootstrapService.UnlockKind.WRONG_PIN);
        assertThat(pauses).hasSize(2);
        assertThat(events).containsExactly("tx", "begin", "commit", "tx", "begin", "commit", "tx", "begin", "commit",
                "compare", "tx", "judge-failed", "commit");
    }

    @Test
    void aRowLockWaitOverTenSecondsInTheClaimIsTooManyRequestsAndNotCounted() {
        when(repository.begin(any())).thenAnswer(invocation -> {
            events.add("begin");
            throw new BootstrapExceptions.Busy();
        });

        assertThatThrownBy(() -> service.unlock(pin("654321"), "127.0.0.1", "req"))
                .isInstanceOf(BootstrapExceptions.Busy.class);

        assertThat(events).containsExactly("tx", "begin", "rollback");
        verifyNoInteractions(verifier);
    }

    @Test
    void anInterruptedWaitEndsTheCallAsAFailureAndKeepsTheInterruptFlag() {
        claims(new Claim(ClaimOutcome.BUSY, 1, null));
        BootstrapService interrupted = new BootstrapService(repository, verifier, ticketHasher,
                new AdminPasswordHasher(new SecureRandom()), new SecureRandom(), clock,
                transactions, duration -> {
                    throw new InterruptedException();
                });

        try {
            assertThatThrownBy(() -> interrupted.unlock(pin("654321"), "127.0.0.1", "req"))
                    .isInstanceOf(BootstrapExceptions.Failed.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    // ================================================================== closed and locked from the claim

    @Test
    void anAdminThatExistsIsClosedAndNothingIsComparedOrJudged() {
        claims(new Claim(ClaimOutcome.CLOSED, 0, null));

        BootstrapService.UnlockOutcome outcome = service.unlock(pin("123456"), "127.0.0.1", "req");

        assertThat(outcome.kind()).isEqualTo(BootstrapService.UnlockKind.CLOSED);
        assertThat(events).containsExactly("tx", "begin", "commit");
        verifyNoInteractions(verifier);
        verify(repository, never()).unlockFailed(any(), any(), any());
        verify(repository, never()).unlockSucceeded(any(), any(), any());
    }

    @Test
    void whileLockedEveryUnlockIsLockedWithTheSecondsLeftAndNothingIsComparedOrCounted() {
        claims(new Claim(ClaimOutcome.LOCKED, 5, NOW.plusSeconds(1800)));

        BootstrapService.UnlockOutcome outcome = service.unlock(pin("123456"), "127.0.0.1", "req");

        assertThat(outcome.kind()).isEqualTo(BootstrapService.UnlockKind.LOCKED);
        assertThat(outcome.retryAfterSeconds()).isEqualTo(1800);
        assertThat(outcome.lockedUntil()).isNull();
        assertThat(events).containsExactly("tx", "begin", "commit");
        verifyNoInteractions(verifier);
        verify(repository, never()).unlockFailed(any(), any(), any());
    }

    @Test
    void aLockedClaimWithoutAnEndTimeFailsClosed() {
        claims(new Claim(ClaimOutcome.LOCKED, 5, null));

        assertThatThrownBy(() -> service.unlock(pin("123456"), "127.0.0.1", "req"))
                .isInstanceOf(BootstrapExceptions.Failed.class);
        verifyNoInteractions(verifier);
    }

    @Test
    void theSecondsLeftAreRoundedUpAtLeastOneAndNeverOverAnHour() {
        assertThat(service.secondsLeft(NOW.plusMillis(1))).isEqualTo(1);
        assertThat(service.secondsLeft(NOW.plusMillis(1001))).isEqualTo(2);
        assertThat(service.secondsLeft(NOW.plusSeconds(60))).isEqualTo(60);
        assertThat(service.secondsLeft(NOW.minusSeconds(5))).isEqualTo(1);
        assertThat(service.secondsLeft(NOW.plusSeconds(3600))).isEqualTo(3600);
        assertThat(service.secondsLeft(NOW.plusSeconds(86400))).isEqualTo(3600);
    }

    /** The unlock answer repeats the 5 minutes of the SQL (ADR-018); a drift in the constant shows here (review N6). */
    @Test
    void theTicketAnswerIsTheFiveMinutesOfTheDatabaseThreeHundredSeconds() {
        assertThat(BootstrapService.TICKET_SECONDS).isEqualTo(300);
        assertThat(BootstrapService.ticketSeconds()).isEqualTo(300);
    }

    // ================================================================== blank PIN and unreadable body (R3-U7)

    @Test
    void aBlankOrMissingPinIsAPeekOnlyAndNeverClaimsComparesOrCounts() {
        when(repository.ticketStatus(null, null)).thenReturn(state(Status.INVALID));
        for (UnlockBody body : new UnlockBody[] {pin(""), pin("   "), pin("\t\n"), pin(null)}) {
            events.clear();

            BootstrapService.UnlockOutcome outcome = service.unlock(body, "127.0.0.1", "req");

            assertThat(outcome.kind()).isEqualTo(BootstrapService.UnlockKind.INVALID_BODY);
            assertThat(events).containsExactly("tx read-only", "commit");
        }
        verify(repository, never()).begin(any());
        verifyNoInteractions(verifier);
        verify(repository, never()).unlockFailed(any(), any(), any());
        verify(repository, never()).unlockSucceeded(any(), any(), any());
        verify(repository, times(4)).ticketStatus(null, null);
    }

    @Test
    void anUnreadableBodyIsAPeekOnlyAndABadRequestWhateverItHolds() {
        when(repository.ticketStatus(null, null)).thenReturn(state(Status.INVALID));
        for (UnlockBody body : new UnlockBody[] {new UnlockBody(false, null), new UnlockBody(false, "123456"),
                new UnlockBody(false, "")}) {
            BootstrapService.UnlockOutcome outcome = service.unlock(body, "127.0.0.1", "req");

            assertThat(outcome.kind()).isEqualTo(BootstrapService.UnlockKind.BAD_REQUEST);
            assertThat(outcome.triesLeft()).isZero();
        }
        verify(repository, never()).begin(any());
        verifyNoInteractions(verifier);
    }

    @Test
    void closedAndLockedAreAnsweredBeforeTheBodyIsLookedAtAndAValidTicketStateDoesNotMatterForABadBody() {
        UnlockBody blank = pin("");
        UnlockBody unreadable = new UnlockBody(false, null);

        when(repository.ticketStatus(null, null)).thenReturn(state(Status.CLOSED));
        assertThat(service.unlock(blank, "127.0.0.1", "req").kind()).isEqualTo(BootstrapService.UnlockKind.CLOSED);
        assertThat(service.unlock(unreadable, "127.0.0.1", "req").kind()).isEqualTo(BootstrapService.UnlockKind.CLOSED);

        when(repository.ticketStatus(null, null)).thenReturn(new TicketState(Status.LOCKED, NOW.plusSeconds(60)));
        BootstrapService.UnlockOutcome locked = service.unlock(blank, "127.0.0.1", "req");
        assertThat(locked.kind()).isEqualTo(BootstrapService.UnlockKind.LOCKED);
        assertThat(locked.retryAfterSeconds()).isEqualTo(60);
        assertThat(service.unlock(unreadable, "127.0.0.1", "req").kind()).isEqualTo(BootstrapService.UnlockKind.LOCKED);

        when(repository.ticketStatus(null, null)).thenReturn(state(Status.VALID));
        assertThat(service.unlock(blank, "127.0.0.1", "req").kind()).isEqualTo(BootstrapService.UnlockKind.INVALID_BODY);
        assertThat(service.unlock(unreadable, "127.0.0.1", "req").kind())
                .isEqualTo(BootstrapService.UnlockKind.BAD_REQUEST);
        verify(repository, never()).begin(any());
    }

    @Test
    void aFailedPeekIsAFailureNotAnAnswer() {
        when(repository.ticketStatus(null, null)).thenThrow(new BootstrapExceptions.Failed("57014"));

        assertThatThrownBy(() -> service.unlock(pin(""), "127.0.0.1", "req"))
                .isInstanceOf(BootstrapExceptions.Failed.class);
        assertThat(events).containsExactly("tx read-only", "rollback");
    }

    // ================================================================== what the judge returns (R3-U8 to R3-U10)

    @Test
    void triesLeftIsFiveMinusTheFailuresTheJudgeReturnedNotFailuresPlusOne() {
        counted(1);
        compareGives(false);
        for (int failures = 1; failures <= 4; failures++) {
            judgeFailedGives(failures, null);

            BootstrapService.UnlockOutcome outcome = service.unlock(pin("654321"), "127.0.0.1", "req");

            assertThat(outcome.kind()).as("failures " + failures).isEqualTo(BootstrapService.UnlockKind.WRONG_PIN);
            assertThat(outcome.triesLeft()).as("failures " + failures).isEqualTo(5 - failures);
        }
    }

    @Test
    void theFifthWrongPinIsLockedNowForAnHourWithTheEndTimeTheJudgeReturned() {
        claims(new Claim(ClaimOutcome.COUNTED, 5, NOW.plusSeconds(3600)));
        compareGives(false);
        judgeFailedGives(5, NOW.plusSeconds(3600));

        BootstrapService.UnlockOutcome outcome = service.unlock(pin("654321"), "127.0.0.1", "req");

        assertThat(outcome.kind()).isEqualTo(BootstrapService.UnlockKind.LOCKED_NOW);
        assertThat(outcome.retryAfterSeconds()).isEqualTo(3600);
        assertThat(outcome.lockedUntil()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(outcome.ticket()).isNull();
    }

    @Test
    void anyOtherPairFromTheWrongPinJudgeIsAFailureNeverAnAnswer() {
        counted(1);
        compareGives(false);
        Object[][] pairs = {{5, null}, {0, null}, {6, null}, {4, NOW.plusSeconds(3600)}, {1, NOW.plusSeconds(3600)},
                {0, NOW.plusSeconds(3600)}, {-1, null}};
        for (Object[] pair : pairs) {
            judgeFailedGives((Integer) pair[0], (Instant) pair[1]);

            assertThatThrownBy(() -> service.unlock(pin("654321"), "127.0.0.1", "req"))
                    .as(pair[0] + "/" + pair[1]).isInstanceOf(BootstrapExceptions.Failed.class);
        }
    }

    @Test
    void aLockAlreadyInForceIsLockedWithoutAnEndTimeSoItIsNeverLoggedAgain() {
        claims(new Claim(ClaimOutcome.LOCKED, 5, NOW.plusSeconds(1800)));

        BootstrapService.UnlockOutcome outcome = service.unlock(pin("654321"), "127.0.0.1", "req");

        assertThat(outcome.kind()).isEqualTo(BootstrapService.UnlockKind.LOCKED);
        assertThat(outcome.lockedUntil()).isNull();
    }

    @Test
    void aCorrectPinAsTheFifthTryStillUnlocksBecauseTheJudgeLiftsTheLockTheClaimSet() {
        claims(new Claim(ClaimOutcome.COUNTED, 5, NOW.plusSeconds(3600)));
        compareGives(true);

        BootstrapService.UnlockOutcome outcome = service.unlock(pin("246813"), "127.0.0.1", "req");

        assertThat(outcome.kind()).isEqualTo(BootstrapService.UnlockKind.UNLOCKED);
        verify(repository).unlockSucceeded(any(), any(), any());
        verify(repository, never()).unlockFailed(any(), any(), any());
    }

    @Test
    void theRightPinStoresOnlyTheKeyedHashesOfANewTicketAndBindingAndReturnsTheValues() {
        counted(1);
        compareGives(true);

        BootstrapService.UnlockOutcome outcome = service.unlock(pin("246813"), "127.0.0.1", "req");

        assertThat(outcome.kind()).isEqualTo(BootstrapService.UnlockKind.UNLOCKED);
        assertThat(outcome.ticket()).matches("[A-Za-z0-9_-]{43}");
        assertThat(outcome.binding()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(outcome.ticket());
        ArgumentCaptor<UUID> tryId = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> ticketHash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bindingHash = ArgumentCaptor.forClass(String.class);
        verify(repository).unlockSucceeded(tryId.capture(), ticketHash.capture(), bindingHash.capture());
        assertThat(ticketHash.getValue()).isEqualTo(ticketHasher.hash(outcome.ticket())).matches("[0-9a-f]{64}")
                .doesNotContain(outcome.ticket());
        assertThat(bindingHash.getValue()).isEqualTo(ticketHasher.hash(outcome.binding()));
        assertThat(events).containsExactly("tx", "begin", "commit", "compare", "tx", "commit");
        verify(repository, never()).unlockFailed(any(), any(), any());
        assertThat(outcome.toString()).isEqualTo("UnlockOutcome[kind=UNLOCKED]");
    }

    @Test
    void everyUnlockMakesNewTicketValues() {
        counted(1);
        compareGives(true);

        BootstrapService.UnlockOutcome first = service.unlock(pin("246813"), "127.0.0.1", "req");
        BootstrapService.UnlockOutcome second = service.unlock(pin("246813"), "127.0.0.1", "req");

        assertThat(first.ticket()).isNotEqualTo(second.ticket());
        assertThat(first.binding()).isNotEqualTo(second.binding());
    }

    @Test
    void aPinThatTheVerifierRefusesBecauseItIsTooLongIsCountedLikeAnyWrongPin() {
        counted(1);
        compareGives(false);
        judgeFailedGives(1, null);

        BootstrapService.UnlockOutcome outcome = service.unlock(pin("9".repeat(500)), "127.0.0.1", "req");

        assertThat(outcome.kind()).isEqualTo(BootstrapService.UnlockKind.WRONG_PIN);
        verify(repository).begin(any());
        verify(repository).unlockFailed(any(), any(), any());
    }

    @Test
    void theTypedCharactersAreClearedAfterTheCompareEvenWhenTheVerifierThrows() {
        counted(1);
        ArgumentCaptor<char[]> typed = ArgumentCaptor.forClass(char[].class);
        when(verifier.matches(typed.capture())).thenThrow(new IllegalStateException("verifier broke"));

        assertThatThrownBy(() -> service.unlock(pin("246813"), "127.0.0.1", "req"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(typed.getValue()).containsOnly('\0');
    }

    @Test
    void theServiceMethodsCarryTheTransactionRulesOfRevision3() throws Exception {
        Method unlock = method("unlock");
        org.springframework.transaction.annotation.Transactional unlockRule = unlock
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(unlockRule).as("unlock").isNotNull();
        assertThat(unlockRule.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.NEVER);

        Method register = method("register");
        org.springframework.transaction.annotation.Transactional registerRule = register
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(registerRule).as("register").isNotNull();
        assertThat(registerRule.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRED);
        assertThat(registerRule.rollbackFor()).isEmpty();
        assertThat(registerRule.noRollbackFor()).isEmpty();
        assertThat(registerRule.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.DEFAULT);
        assertThat(registerRule.readOnly()).isFalse();
    }

    private static Method method(String name) {
        for (Method candidate : BootstrapService.class.getDeclaredMethods()) {
            if (candidate.getName().equals(name)) {
                return candidate;
            }
        }
        throw new IllegalStateException(name);
    }

    // ================================================================== register

    private static RegisterBody form(String ticket, String name, String email, String password) {
        return new RegisterBody(true, ticket, name, email, password);
    }

    private static RegisterBody good() {
        return form(TICKET, "First admin", "First.Admin@Example.Test ", "a-throwaway-passphrase");
    }

    private void ticketIs(Status status) {
        when(repository.ticketStatus(anyString(), anyString())).thenReturn(state(status));
    }

    @Test
    void theTicketIsCheckedBeforeTheFormAndOnlyKeyedHashesAreSent() {
        ticketIs(Status.INVALID);

        BootstrapService.RegisterOutcome outcome = service.register(
                form(TICKET, "", "not an email", "short"), BINDING, "127.0.0.1", "req");

        assertThat(outcome.kind()).isEqualTo(BootstrapService.RegisterKind.TICKET_INVALID);
        verify(repository).ticketStatus(ticketHasher.hash(TICKET), ticketHasher.hash(BINDING));
        verify(repository, never()).register(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aMissingOrMalformedTicketOrBindingIsInvalidWithoutAnyRealValueSent() {
        when(repository.ticketStatus(any(), any())).thenReturn(state(Status.INVALID));
        RegisterBody noTicket = form(null, "A", "a@b.co", "a-throwaway-passphrase");
        RegisterBody shortTicket = form("short", "A", "a@b.co", "a-throwaway-passphrase");

        assertThat(service.register(noTicket, BINDING, "127.0.0.1", "req").kind())
                .isEqualTo(BootstrapService.RegisterKind.TICKET_INVALID);
        assertThat(service.register(shortTicket, BINDING, "127.0.0.1", "req").kind())
                .isEqualTo(BootstrapService.RegisterKind.TICKET_INVALID);
        assertThat(service.register(good(), null, "127.0.0.1", "req").kind())
                .isEqualTo(BootstrapService.RegisterKind.TICKET_INVALID);
        assertThat(service.register(good(), "bad cookie", "127.0.0.1", "req").kind())
                .isEqualTo(BootstrapService.RegisterKind.TICKET_INVALID);
        verify(repository, org.mockito.Mockito.times(4)).ticketStatus(null, null);
    }

    @Test
    void closedIsTheEmpty404Outcome() {
        ticketIs(Status.CLOSED);

        assertThat(service.register(good(), BINDING, "127.0.0.1", "req").kind())
                .isEqualTo(BootstrapService.RegisterKind.CLOSED);
        verify(repository, never()).register(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void lockedUsesTheLockTimeTheDatabaseReturnedAndNeverCallsBegin() {
        when(repository.ticketStatus(anyString(), anyString()))
                .thenReturn(new TicketState(Status.LOCKED, NOW.plusSeconds(600)));

        BootstrapService.RegisterOutcome outcome = service.register(good(), BINDING, "127.0.0.1", "req");

        assertThat(outcome.kind()).isEqualTo(BootstrapService.RegisterKind.LOCKED);
        assertThat(outcome.retryAfterSeconds()).isEqualTo(600);
        verify(repository, never()).begin(any());
    }

    @Test
    void aLockedAnswerWithoutAnEndTimeFailsClosedAsABodyless500() {
        when(repository.ticketStatus(anyString(), anyString())).thenReturn(new TicketState(Status.LOCKED, null));

        assertThatThrownBy(() -> service.register(good(), BINDING, "127.0.0.1", "req"))
                .isInstanceOf(BootstrapExceptions.Failed.class);
        verify(repository, never()).begin(any());
    }

    @Test
    void aBadFormIsFieldErrorsAndTheTicketIsNotSpent() {
        ticketIs(Status.VALID);

        BootstrapService.RegisterOutcome email = service.register(
                form(TICKET, "First admin", "not-an-email", "a-throwaway-passphrase"), BINDING, "127.0.0.1", "req");
        BootstrapService.RegisterOutcome password = service.register(
                form(TICKET, "First admin", "first.admin@example.test", "short"), BINDING, "127.0.0.1", "req");
        BootstrapService.RegisterOutcome containsEmailName = service.register(
                form(TICKET, "First admin", "first.admin@example.test", "xx-first.admin-xx-123"), BINDING,
                "127.0.0.1", "req");
        BootstrapService.RegisterOutcome blankName = service.register(
                form(TICKET, "   ", "first.admin@example.test", "a-throwaway-passphrase"), BINDING, "127.0.0.1", "req");
        BootstrapService.RegisterOutcome longName = service.register(
                form(TICKET, "N".repeat(121), "first.admin@example.test", "a-throwaway-passphrase"), BINDING,
                "127.0.0.1", "req");

        assertThat(fields(email)).containsExactly("email");
        assertThat(fields(password)).containsExactly("password");
        assertThat(fields(containsEmailName)).containsExactly("password");
        assertThat(fields(blankName)).containsExactly("fullName");
        assertThat(fields(longName)).containsExactly("fullName");
        assertThat(email.kind()).isEqualTo(BootstrapService.RegisterKind.INVALID_FORM);
        verify(repository, never()).register(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aBadNameAndABadEmailAreBothReported() {
        ticketIs(Status.VALID);

        BootstrapService.RegisterOutcome outcome = service.register(
                form(TICKET, "", "nope", "a-throwaway-passphrase"), BINDING, "127.0.0.1", "req");

        assertThat(fields(outcome)).containsExactlyInAnyOrder("fullName", "email");
    }

    @Test
    void fieldErrorsNeverEchoWhatWasSubmitted() {
        ticketIs(Status.VALID);

        BootstrapService.RegisterOutcome outcome = service.register(
                form(TICKET, "Some Name", "weird-email-value", "tiny-pw"), BINDING, "127.0.0.1", "req");

        assertThat(outcome.fieldErrors().toString()).doesNotContain("weird-email-value").doesNotContain("tiny-pw")
                .doesNotContain("Some Name");
    }

    @Test
    void anUnreadableBodyIsABadRequestOnlyAfterTheTicketPassedAndTheTicketIsNotSpent() {
        ticketIs(Status.VALID);

        BootstrapService.RegisterOutcome outcome = service.register(
                new RegisterBody(false, TICKET, "A", "a@b.co", "a-throwaway-passphrase"), BINDING, "127.0.0.1", "req");

        assertThat(outcome.kind()).isEqualTo(BootstrapService.RegisterKind.BAD_REQUEST);
        assertThat(outcome.fieldErrors()).isEmpty();
        verify(repository, never()).register(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anUnreadableBodyWithoutAValidTicketIsTheTicketAnswerNotABadRequest() {
        when(repository.ticketStatus(any(), any())).thenReturn(state(Status.INVALID));

        BootstrapService.RegisterOutcome outcome = service.register(
                new RegisterBody(false, null, null, null, null), BINDING, "127.0.0.1", "req");

        assertThat(outcome.kind()).isEqualTo(BootstrapService.RegisterKind.TICKET_INVALID);
    }

    @Test
    void missingAndNullFieldsAreFormErrorsAsIfEmptyAndTheTicketIsNotSpent() {
        ticketIs(Status.VALID);

        BootstrapService.RegisterOutcome none = service.register(
                new RegisterBody(true, TICKET, null, null, null), BINDING, "127.0.0.1", "req");
        BootstrapService.RegisterOutcome noPassword = service.register(
                new RegisterBody(true, TICKET, "First admin", "first.admin@example.test", null), BINDING,
                "127.0.0.1", "req");

        assertThat(none.kind()).isEqualTo(BootstrapService.RegisterKind.INVALID_FORM);
        assertThat(fields(none)).containsExactlyInAnyOrder("fullName", "email");
        assertThat(noPassword.kind()).isEqualTo(BootstrapService.RegisterKind.INVALID_FORM);
        assertThat(fields(noPassword)).containsExactly("password");
        verify(repository, never()).register(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theFieldErrorTextsAreTheFixedStoryTexts() {
        ticketIs(Status.VALID);

        BootstrapService.RegisterOutcome outcome = service.register(
                form(TICKET, "", "nope", "a-throwaway-passphrase"), BINDING, "127.0.0.1", "req");

        assertThat(outcome.fieldErrors()).extracting(Problems.FieldError::message)
                .containsExactlyInAnyOrder("Enter the full name.", "Enter a valid email.");
    }

    @Test
    void aGoodFormCreatesTheAdminAndCallsNothingAfterTheRegisterFunction() {
        ticketIs(Status.VALID);
        when(repository.register(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(reply(RegisterResult.CREATED));

        BootstrapService.RegisterOutcome outcome = service.register(good(), BINDING, "127.0.0.1", "req-9");

        assertThat(outcome.kind()).isEqualTo(BootstrapService.RegisterKind.CREATED);
        ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> email = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(repository).register(eq(ticketHasher.hash(TICKET)), eq(ticketHasher.hash(BINDING)), id.capture(),
                email.capture(), name.capture(), hash.capture(), eq("127.0.0.1"), eq("req-9"));
        assertThat(email.getValue()).isEqualTo("first.admin@example.test");
        assertThat(name.getValue()).isEqualTo("First admin");
        assertThat(hash.getValue()).startsWith("$2a$12$").doesNotContain("a-throwaway-passphrase");
        assertThat(outcome.adminId()).isEqualTo(id.getValue());
        // Revision 5: the register function returning CREATED is the last database step; no call follows it.
        InOrder order = inOrder(repository);
        order.verify(repository).ticketStatus(any(), any());
        order.verify(repository).register(any(), any(), any(), any(), any(), any(), any(), any());
        order.verifyNoMoreInteractions();
        assertThat(outcome.toString()).isEqualTo("RegisterOutcome[kind=CREATED]");
    }

    @Test
    void theAdminCreatedHasNoTenantAndNoPasswordInAnyCall() {
        ticketIs(Status.VALID);
        when(repository.register(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(reply(RegisterResult.CREATED));

        service.register(good(), BINDING, "127.0.0.1", "req");

        // The repository interface has no tenant parameter at all: the function creates the admin without one.
        assertThat(List.of(BootstrapRepository.class.getDeclaredMethods()).stream()
                .map(Method::toString).filter(text -> text.toLowerCase().contains("tenant")).toList()).isEmpty();
    }

    @Test
    void whenTheDatabaseSaysClosedLockedOrInvalidAtRegisterTimeNothingIsIssued() {
        ticketIs(Status.VALID);
        when(repository.ticketStatus(anyString(), anyString())).thenReturn(state(Status.VALID));

        when(repository.register(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(reply(RegisterResult.CLOSED));
        assertThat(service.register(good(), BINDING, "127.0.0.1", "req").kind())
                .isEqualTo(BootstrapService.RegisterKind.CLOSED);

        when(repository.register(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(new RegisterReply(RegisterResult.LOCKED, NOW.plusSeconds(100)));
        BootstrapService.RegisterOutcome locked = service.register(good(), BINDING, "127.0.0.1", "req");
        assertThat(locked.kind()).isEqualTo(BootstrapService.RegisterKind.LOCKED);
        assertThat(locked.retryAfterSeconds()).isEqualTo(100);

        when(repository.register(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(reply(RegisterResult.INVALID));
        assertThat(service.register(good(), BINDING, "127.0.0.1", "req").kind())
                .isEqualTo(BootstrapService.RegisterKind.TICKET_INVALID);

    }

    /**
     * Revision 5: after CREATED only the commit follows. The service has no transaction of its own here (the Spring proxy
     * adds it, see BootstrapTransactionProxyTest), so the commit is modelled with a template over the recording manager.
     */
    @Test
    void aCommitThatThrowsAfterCreatedGivesTheFailurePathAndCallsNothingElse() {
        ticketIs(Status.VALID);
        when(repository.register(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(reply(RegisterResult.CREATED));
        transactions.failCommit(1);

        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(
                status -> service.register(good(), BINDING, "127.0.0.1", "req")))
                .isInstanceOf(TransactionSystemException.class);

        assertThat(events).containsExactly("tx", "commit failed");
        verify(repository, times(1)).register(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aDatabaseFailureAtRegisterPropagates() {
        ticketIs(Status.VALID);
        when(repository.register(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BootstrapExceptions.Busy());

        assertThatThrownBy(() -> service.register(good(), BINDING, "127.0.0.1", "req"))
                .isInstanceOf(BootstrapExceptions.Busy.class);
    }

    private static List<String> fields(BootstrapService.RegisterOutcome outcome) {
        return outcome.fieldErrors().stream().map(Problems.FieldError::field).toList();
    }

    // ================================================================== control characters (R3-U13)

    @Test
    void aControlCharacterOrALoneSurrogateInTheNameIsAFieldErrorWithTheFixedTextAndNoRepositoryCall() {
        ticketIs(Status.VALID);
        String[] names = {"A\u0000B", "\u0000First admin", "First admin\u0000", "A\u001FB", "A\u007FB", "A\u0085B",
                "A\u009FB", "A\tB", "A\nB", "A\rB", "A\uD800B", "A\uDC00B", "First admin\uD83D", "\u001FFirst admin"};
        for (String name : names) {
            BootstrapService.RegisterOutcome outcome = service.register(
                    form(TICKET, name, "first.admin@example.test", "a-throwaway-passphrase"), BINDING, "127.0.0.1", "req");

            assertThat(outcome.kind()).as(escape(name)).isEqualTo(BootstrapService.RegisterKind.INVALID_FORM);
            assertThat(fields(outcome)).as(escape(name)).containsExactly("fullName");
            assertThat(outcome.fieldErrors().get(0).message()).isEqualTo("Enter the full name.");
        }
        verify(repository, never()).register(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aControlCharacterOrALoneSurrogateInTheEmailIsAnEmailErrorAndThePasswordIsNotJudged() {
        ticketIs(Status.VALID);
        String[] emails = {"first\u0000.admin@example.test", "\u0000first.admin@example.test",
                "first.admin@example.test\u0000", "first\u001F.admin@example.test", "first\u007F.admin@example.test",
                "first\u0085.admin@example.test", "first\u0009.admin@example.test", "first\uD800.admin@example.test",
                "first.admin@example.test\uDC00"};
        for (String email : emails) {
            // the password is bad on purpose: only the email is reported
            BootstrapService.RegisterOutcome outcome = service.register(
                    form(TICKET, "First admin", email, "short"), BINDING, "127.0.0.1", "req");

            assertThat(outcome.kind()).as(escape(email)).isEqualTo(BootstrapService.RegisterKind.INVALID_FORM);
            assertThat(fields(outcome)).as(escape(email)).containsExactly("email");
            assertThat(outcome.fieldErrors().get(0).message()).isEqualTo("Enter a valid email.");
        }
        verify(repository, never()).register(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aBadNameAndABadEmailWithControlCharactersAreBothReportedAndNothingIs500() {
        ticketIs(Status.VALID);

        BootstrapService.RegisterOutcome outcome = service.register(
                form(TICKET, "\u0000", "a\u0000@b.co", "a-throwaway-passphrase"), BINDING, "127.0.0.1", "req");

        assertThat(fields(outcome)).containsExactly("fullName", "email");
    }

    @Test
    void aValidSurrogatePairAndOrdinaryNonAsciiLettersAreAccepted() {
        ticketIs(Status.VALID);
        when(repository.register(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(reply(RegisterResult.CREATED));

        BootstrapService.RegisterOutcome outcome = service.register(
                form(TICKET, "  Ana 😀 José  ", "first.admin@example.test", "a-throwaway-passphrase"),
                BINDING, "127.0.0.1", "req");

        assertThat(outcome.kind()).isEqualTo(BootstrapService.RegisterKind.CREATED);
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(repository).register(any(), any(), any(), any(), name.capture(), any(), any(), any());
        assertThat(name.getValue()).isEqualTo("Ana 😀 José");
    }

    @Test
    void thePasswordIsNotCheckedForControlCharacters() {
        ticketIs(Status.VALID);
        when(repository.register(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(reply(RegisterResult.CREATED));

        BootstrapService.RegisterOutcome outcome = service.register(
                form(TICKET, "First admin", "first.admin@example.test", "a-throwaway\u0000passphrase"), BINDING,
                "127.0.0.1", "req");

        assertThat(outcome.kind()).isEqualTo(BootstrapService.RegisterKind.CREATED);
    }

    @Test
    void theControlCharacterRuleIsExactlyCategoryCcAndUnpairedSurrogates() {
        for (int c = 0; c <= 0x9F; c++) {
            boolean control = c <= 0x1F || c >= 0x7F;
            assertThat(BootstrapService.hasControlCharacters("x" + (char) c + "y")).as("U+%04X", c).isEqualTo(control);
        }
        assertThat(BootstrapService.hasControlCharacters("plain text, 12 Nov 2026")).isFalse();
        assertThat(BootstrapService.hasControlCharacters("")).isFalse();
        assertThat(BootstrapService.hasControlCharacters("😀")).isFalse();
        assertThat(BootstrapService.hasControlCharacters("\uD83D")).isTrue();
        assertThat(BootstrapService.hasControlCharacters("\uDE00\uD83D")).isTrue();
    }

    /** Shows control characters as escapes in an assertion message. */
    private static String escape(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            out.append(c < 0x20 || c >= 0x7F ? "U+" + Integer.toHexString(c) : String.valueOf(c));
        }
        return out.toString();
    }
}
