package com.sahaj.platform.devtools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Code review finding 10 and R3-U11: the transaction rules of {@link BootstrapService} proven through the REAL Spring
 * proxy and a recording transaction manager that applies Spring's own propagation rules (it extends
 * {@link AbstractPlatformTransactionManager}), not through a reflection check of the annotation. No database.
 *
 * <p>What is proven: every normal outcome COMMITS (wrong PIN, the 5th wrong PIN, locked, closed, invalid ticket); only an
 * exception rolls back; a failed commit after CREATED fails the call (nothing is created); the PIN compare runs outside any
 * transaction, after the claim's commit; and unlock called inside a transaction fails at once, before any call.
 */
class BootstrapTransactionProxyTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final String TICKET = "T".repeat(43);
    private static final String BINDING = "C".repeat(43);

    /** Records "begin", "commit" and "rollback" as Spring decides them, with the same list the mocks write to. */
    static final class RecordingManager extends AbstractPlatformTransactionManager {
        private static final long serialVersionUID = 1L;
        private final transient List<String> events;
        private boolean active;
        private boolean failCommit;

        RecordingManager(List<String> events) {
            this.events = events;
        }

        void failNextCommit() {
            failCommit = true;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return active;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            active = true;
            events.add(definition.isReadOnly() ? "begin read-only" : "begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            active = false;
            if (failCommit) {
                failCommit = false;
                events.add("commit failed");
                throw new TransactionSystemException("commit failed");
            }
            events.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            active = false;
            events.add("rollback");
        }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            events.add("rollback-only");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Wiring {
        @Bean
        EventLog events() {
            return new EventLog(Collections.synchronizedList(new ArrayList<>()));
        }

        @Bean
        PlatformTransactionManager transactionManager(EventLog events) {
            return new RecordingManager(events.list());
        }

        @Bean
        BootstrapRepository repository() {
            return mock(BootstrapRepository.class);
        }

        @Bean
        BootstrapPinVerifier verifier() {
            return mock(BootstrapPinVerifier.class);
        }

        @Bean
        BootstrapService service(BootstrapRepository repository, BootstrapPinVerifier verifier,
                                 PlatformTransactionManager manager) {
            String secret = "a-throwaway-admin-secret-for-tests-only-123";
            Ticker ticker = new Ticker();
            return new BootstrapService(repository, verifier, new BootstrapTicketHasher(secret),
                    new AdminPasswordHasher(new SecureRandom()), new SecureRandom(), ticker, manager,
                    ticker::advance);
        }
    }

    /** The shared list of what happened, wrapped so Spring does not read it as a collection of beans. */
    record EventLog(List<String> list) {
    }

    /** A clock the fake pause moves, so a busy turn can run out without sleeping. */
    static final class Ticker extends Clock {
        private Instant now = NOW;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }
    }

    /** What a test gets: the proxied service, the mocks and the shared event list. */
    private record Context(BootstrapService service, BootstrapRepository repository, BootstrapPinVerifier verifier,
                           List<String> events,
                           PlatformTransactionManager manager) {
    }

    private interface Check {
        void run(Context context) throws Exception;
    }

    private static void withService(Check check) {
        new ApplicationContextRunner().withUserConfiguration(Wiring.class).run(context -> {
            assertThat(context).hasNotFailed();
            BootstrapService service = context.getBean(BootstrapService.class);
            assertThat(AopUtils.isAopProxy(service)).as("the real Spring proxy").isTrue();
            check.run(new Context(service, context.getBean(BootstrapRepository.class),
                    context.getBean(BootstrapPinVerifier.class),
                    context.getBean(EventLog.class).list(), context.getBean(PlatformTransactionManager.class)));
        });
    }

    private static UnlockBody pin() {
        return new UnlockBody(true, "246813");
    }

    private static void stubEvents(Context c, boolean pinCorrect) {
        when(c.repository().begin(any())).thenAnswer(invocation -> {
            c.events().add("repo:begin");
            return new Claim(ClaimOutcome.COUNTED, 1, null);
        });
        when(c.verifier().matches(any())).thenAnswer(invocation -> {
            c.events().add("compare");
            return pinCorrect;
        });
    }

    // ---------------------------------------------------------------- unlock

    @Test
    void aWrongPinCommitsTheClaimThenComparesOutsideAnyTransactionThenCommitsTheJudge() {
        withService(c -> {
            stubEvents(c, false);
            when(c.repository().unlockFailed(any(), anyString(), anyString())).thenAnswer(invocation -> {
                c.events().add("repo:judge");
                return new Failure(1, null);
            });

            BootstrapService.UnlockOutcome outcome = c.service().unlock(pin(), "127.0.0.1", "req");

            assertThat(outcome.kind()).isEqualTo(BootstrapService.UnlockKind.WRONG_PIN);
            assertThat(c.events()).containsExactly("begin", "repo:begin", "commit", "compare", "begin", "repo:judge",
                    "commit");
        });
    }

    @Test
    void theFifthWrongPinCommitsBothTransactionsAndSoDoesALockAlreadyInForce() {
        withService(c -> {
            stubEvents(c, false);
            when(c.repository().unlockFailed(any(), anyString(), anyString()))
                    .thenReturn(new Failure(5, NOW.plusSeconds(3600)));

            assertThat(c.service().unlock(pin(), "127.0.0.1", "req").kind())
                    .isEqualTo(BootstrapService.UnlockKind.LOCKED_NOW);
            assertThat(c.events()).containsExactly("begin", "repo:begin", "commit", "compare", "begin", "commit");

            c.events().clear();
            when(c.repository().begin(any())).thenAnswer(invocation -> {
                c.events().add("repo:begin");
                return new Claim(ClaimOutcome.LOCKED, 5, NOW.plusSeconds(60));
            });
            c.events().clear();

            assertThat(c.service().unlock(pin(), "127.0.0.1", "req").kind())
                    .isEqualTo(BootstrapService.UnlockKind.LOCKED);
            assertThat(c.events()).containsExactly("begin", "repo:begin", "commit");
        });
    }

    @Test
    void closedAndABadBodyPeekCommitAndABusyTurnCommitsEachAttemptThenAnswersBusy() {
        withService(c -> {
            when(c.repository().begin(any())).thenReturn(new Claim(ClaimOutcome.CLOSED, 0, null));
            assertThat(c.service().unlock(pin(), "127.0.0.1", "req").kind())
                    .isEqualTo(BootstrapService.UnlockKind.CLOSED);
            assertThat(c.events()).containsExactly("begin", "commit");

            c.events().clear();
            when(c.repository().ticketStatus(null, null)).thenReturn(new TicketState(Status.INVALID, null));
            assertThat(c.service().unlock(new UnlockBody(true, ""), "127.0.0.1", "req").kind())
                    .isEqualTo(BootstrapService.UnlockKind.INVALID_BODY);
            assertThat(c.events()).containsExactly("begin read-only", "commit");

            c.events().clear();
            when(c.repository().begin(any())).thenReturn(new Claim(ClaimOutcome.BUSY, 1, null));
            assertThatThrownBy(() -> c.service().unlock(pin(), "127.0.0.1", "req"))
                    .isInstanceOf(BootstrapExceptions.Busy.class);
            assertThat(c.events()).doesNotContain("rollback").doesNotContain("compare");
            assertThat(c.events().stream().filter("commit"::equals).count()).isEqualTo(41);
        });
    }

    @Test
    void aFailedJudgeRollsBackThatTransactionOnlyAndTheClaimHasAlreadyCommitted() {
        withService(c -> {
            stubEvents(c, false);
            when(c.repository().unlockFailed(any(), anyString(), anyString()))
                    .thenThrow(new BootstrapExceptions.Failed("SB004"));

            assertThatThrownBy(() -> c.service().unlock(pin(), "127.0.0.1", "req"))
                    .isInstanceOf(BootstrapExceptions.Failed.class);

            assertThat(c.events()).containsExactly("begin", "repo:begin", "commit", "compare", "begin", "rollback");
        });
    }

    @Test
    void aClosedAnswerFromTheJudgeRollsBackTheJudgeAndIsStillTheEmpty404Exception() {
        withService(c -> {
            stubEvents(c, true);
            org.mockito.Mockito.doThrow(new BootstrapExceptions.Closed()).when(c.repository())
                    .unlockSucceeded(any(), anyString(), anyString());

            assertThatThrownBy(() -> c.service().unlock(pin(), "127.0.0.1", "req"))
                    .isInstanceOf(BootstrapExceptions.Closed.class);

            assertThat(c.events()).containsExactly("begin", "repo:begin", "commit", "compare", "begin", "rollback");
        });
    }

    /** R3-U11. */
    @Test
    void unlockCalledInsideAnOpenTransactionFailsAtOnceBeforeAnyDatabaseCallOrCompare() {
        withService(c -> {
            stubEvents(c, false);

            assertThatThrownBy(() -> new TransactionTemplate(c.manager()).execute(
                    status -> c.service().unlock(pin(), "127.0.0.1", "req")))
                    .isInstanceOf(IllegalTransactionStateException.class);

            verify(c.repository(), never()).begin(any());
            verify(c.repository(), never()).ticketStatus(any(), any());
            verify(c.verifier(), never()).matches(any());
            assertThat(c.events()).doesNotContain("repo:begin").doesNotContain("compare");
        });
    }

    // ---------------------------------------------------------------- register

    private static RegisterBody form() {
        return new RegisterBody(true, TICKET, "First admin", "first.admin@example.test", "a-throwaway-passphrase");
    }

    private static void ticketIs(Context c, Status status, Instant lockedUntil) {
        when(c.repository().ticketStatus(anyString(), anyString())).thenReturn(new TicketState(status, lockedUntil));
    }

    @Test
    void registerIsOneTransactionThatCommitsOnCreatedAndOnEveryReturnedOutcome() {
        withService(c -> {
            ticketIs(c, Status.VALID, null);
            when(c.repository().register(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new RegisterReply(RegisterResult.CREATED, null));

            assertThat(c.service().register(form(), BINDING, "127.0.0.1", "req").kind())
                    .isEqualTo(BootstrapService.RegisterKind.CREATED);
            assertThat(c.events()).containsExactly("begin", "commit");

            c.events().clear();
            ticketIs(c, Status.CLOSED, null);
            assertThat(c.service().register(form(), BINDING, "127.0.0.1", "req").kind())
                    .isEqualTo(BootstrapService.RegisterKind.CLOSED);
            ticketIs(c, Status.LOCKED, NOW.plusSeconds(60));
            assertThat(c.service().register(form(), BINDING, "127.0.0.1", "req").kind())
                    .isEqualTo(BootstrapService.RegisterKind.LOCKED);
            ticketIs(c, Status.INVALID, null);
            assertThat(c.service().register(form(), BINDING, "127.0.0.1", "req").kind())
                    .isEqualTo(BootstrapService.RegisterKind.TICKET_INVALID);
            ticketIs(c, Status.VALID, null);
            assertThat(c.service().register(new RegisterBody(true, TICKET, "", "x", "y"), BINDING, "127.0.0.1", "req")
                    .kind()).isEqualTo(BootstrapService.RegisterKind.INVALID_FORM);
            assertThat(c.events()).containsExactly("begin", "commit", "begin", "commit", "begin", "commit", "begin",
                    "commit");
            verify(c.repository(), never()).begin(any());
        });
    }

    @Test
    void registerRollsBackOnClosedAndBusy() {
        withService(c -> {
            ticketIs(c, Status.VALID, null);

            when(c.repository().register(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new BootstrapExceptions.Closed());
            assertThatThrownBy(() -> c.service().register(form(), BINDING, "127.0.0.1", "req"))
                    .isInstanceOf(BootstrapExceptions.Closed.class);
            assertThat(c.events()).containsExactly("begin", "rollback");

            c.events().clear();
            org.mockito.Mockito.doThrow(new BootstrapExceptions.Busy()).when(c.repository())
                    .register(any(), any(), any(), any(), any(), any(), any(), any());
            assertThatThrownBy(() -> c.service().register(form(), BINDING, "127.0.0.1", "req"))
                    .isInstanceOf(BootstrapExceptions.Busy.class);
            assertThat(c.events()).containsExactly("begin", "rollback");
        });
    }

    /** ADR-018 Revision 5: after CREATED only the commit follows; if it fails the call fails and nothing is committed. */
    @Test
    void aCommitThatFailsAfterCreatedFailsTheCallAndNothingIsCommitted() {
        withService(c -> {
            ticketIs(c, Status.VALID, null);
            when(c.repository().register(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new RegisterReply(RegisterResult.CREATED, null));
            ((RecordingManager) c.manager()).failNextCommit();

            assertThatThrownBy(() -> c.service().register(form(), BINDING, "127.0.0.1", "req"))
                    .isInstanceOf(TransactionSystemException.class);

            assertThat(c.events()).containsExactly("begin", "commit failed");
            verify(c.repository()).register(any(), any(), any(), any(), any(), any(), any(), any());
        });
    }
}
