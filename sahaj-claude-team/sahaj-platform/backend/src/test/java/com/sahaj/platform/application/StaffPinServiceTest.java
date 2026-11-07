package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.ColleaguePinCheck;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.PinCheck;
import com.sahaj.platform.api.PinCheck.NotSetReason;
import com.sahaj.platform.api.PinCheck.Outcome;
import com.sahaj.platform.api.StaffPinException;
import com.sahaj.platform.application.StaffPinOps.ColleagueResult;
import com.sahaj.platform.application.StaffPinOps.Own;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.PinState;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** SAH-31, ADR-010 D6, D7, D9, D13: the order of every PIN operation and the problems it answers. */
class StaffPinServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final String MOBILE = "9822077410";
    private static final String JWT_SECRET = "a-jwt-secret-that-is-at-least-32-characters-long";

    private StaffPinOps ops;
    private StaffPinLimits limits;
    private OtpService otpService;
    private AuthenticationScope scope;
    private StaffPinService service;
    private PinStatusView savedView;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ops = mock(StaffPinOps.class);
        limits = mock(StaffPinLimits.class);
        otpService = mock(OtpService.class);
        scope = mock(AuthenticationScope.class);
        when(scope.runAsTenant(any(), any(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(2)).get());
        service = serviceWith(available());
        savedView = new PinStatusView(true, PinState.SET, null, null, 5, null, "••••••7410");
        when(ops.readOwn(TENANT, USER)).thenReturn(Optional.of(new Own(MEMBER, MOBILE, null)));
        when(ops.statusOf(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer(call ->
                new PinStatusView(call.getArgument(1), PinState.NOT_SET, null, null, null, null, "••••••7410"));
        when(ops.commitSet(TENANT, USER, "4831")).thenReturn(savedView);
    }

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static StaffPinHasher available() {
        byte[] pepper = new byte[32];
        new SecureRandom().nextBytes(pepper);
        return StaffPinHasher.create(Base64.getEncoder().encodeToString(pepper), JWT_SECRET, 4);
    }

    private StaffPinService serviceWith(StaffPinHasher hasher) {
        return new StaffPinService(ops, hasher, limits, otpService, scope);
    }

    private static PinSetInput input(String code, String newPin, String confirmPin) {
        return new PinSetInput(code, newPin, confirmPin);
    }

    private static List<FieldMessage> fieldErrors(Throwable thrown) {
        return ((InvalidFieldsException) thrown).errors();
    }

    // ---- check (StaffPinPort) ----

    @Test
    void checkRefusesToRunInsideAnActiveTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> service.check(TENANT, USER, "4831")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ops, limits, scope);
    }

    @Test
    void checkRunsInItsOwnTransactionForTheCallersClinicAndPerson() {
        PinCheck ok = new PinCheck(Outcome.OK, 5, null, 0);
        when(ops.check(TENANT, USER, "4831")).thenReturn(ok);

        assertThat(service.check(TENANT, USER, "4831")).isSameAs(ok);

        verify(scope).runAsTenant(org.mockito.ArgumentMatchers.eq(USER), org.mockito.ArgumentMatchers.eq(TENANT), any());
        verify(ops).check(TENANT, USER, "4831");
    }

    @Test
    void checkLooksAtTheFailedCheckLimitBeforeAnyHash() {
        when(limits.pinCheckWait(TENANT, USER)).thenReturn(180L);

        PinCheck result = service.check(TENANT, USER, "4831");

        assertThat(result.outcome()).isEqualTo(Outcome.RATE_LIMITED);
        assertThat(result.retryAfterSeconds()).isEqualTo(180L);
        verifyNoInteractions(ops, scope);
    }

    @Test
    void aRightPinNeverCountsTowardTheFailedCheckLimit() {
        when(ops.check(TENANT, USER, "4831")).thenReturn(new PinCheck(Outcome.OK, 5, null, 0));

        service.check(TENANT, USER, "4831");

        verify(limits, never()).pinCheckFailed(any(), any());
        verify(limits, never()).pinSetWait(any(), any());
    }

    @Test
    void aWrongPinCountsOnceAfterTheLimitWasLookedAtBeforeComparing() {
        when(ops.check(TENANT, USER, "0000")).thenReturn(new PinCheck(Outcome.WRONG, 4, null, 0));

        service.check(TENANT, USER, "0000");

        InOrder order = inOrder(limits, ops);
        order.verify(limits).pinCheckWait(TENANT, USER);
        order.verify(ops).check(TENANT, USER, "0000");
        order.verify(limits).pinCheckFailed(TENANT, USER);
        verify(limits, never()).pinSetWait(any(), any());
    }

    @Test
    void aLockedOrNotSetAnswerCountsAsAFailedCheckToo() {
        when(ops.check(TENANT, USER, "1111")).thenReturn(new PinCheck(Outcome.LOCKED, 0, null, 600));
        service.check(TENANT, USER, "1111");
        when(ops.check(TENANT, USER, "2222")).thenReturn(new PinCheck(Outcome.NOT_SET, 0, null, 0));
        service.check(TENANT, USER, "2222");

        verify(limits, org.mockito.Mockito.times(2)).pinCheckFailed(TENANT, USER);
    }

    @Test
    void anUnavailableAnswerDoesNotCountBecauseNothingWasCompared() {
        service = serviceWith(StaffPinHasher.create(null, JWT_SECRET, 4));

        assertThat(service.check(TENANT, USER, "1111").outcome()).isEqualTo(Outcome.UNAVAILABLE);

        verify(limits, never()).pinCheckFailed(any(), any());
    }

    @Test
    void aRefusedCheckBecauseOfTheLimitDoesNotCountAgain() {
        when(limits.pinCheckWait(TENANT, USER)).thenReturn(30L);

        service.check(TENANT, USER, "0000");

        verify(limits, never()).pinCheckFailed(any(), any());
    }

    @Test
    void checkFailsClosedWhenThePepperIsMissing() {
        service = serviceWith(StaffPinHasher.create(null, JWT_SECRET, 4));

        assertThat(service.check(TENANT, USER, "4831").outcome()).isEqualTo(Outcome.UNAVAILABLE);
        verifyNoInteractions(ops, scope);
    }

    @Test
    void checkRequiresBothIds() {
        assertThatThrownBy(() -> service.check(null, USER, "4831")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.check(TENANT, null, "4831")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checkOutcomesBecomeTheD11ProblemsThroughRequireOk() {
        when(ops.check(TENANT, USER, "0000")).thenReturn(new PinCheck(Outcome.WRONG, 3, null, 0));

        assertThatThrownBy(() -> service.check(TENANT, USER, "0000").requireOk())
                .isInstanceOfSatisfying(StaffPinException.class, ex -> {
                    assertThat(ex.slug()).isEqualTo("wrong-pin");
                    assertThat(ex.getMessage()).isEqualTo("That PIN is not right. 3 tries left.");
                });
    }

    // ---- checkColleague (StaffPinPort, SAH-32, ADR-011 D7; SAH-38, ADR-014 D3, D5, D7) ----

    private static final UUID COLLEAGUE_MEMBER = UUID.randomUUID();
    private static final UUID COLLEAGUE_USER = UUID.randomUUID();
    private static final String COLLEAGUE_PIN = "4821";

    private void opsAnswers(PinCheck check, boolean wrongTry) {
        when(ops.checkColleague(TENANT, USER, COLLEAGUE_MEMBER, MembershipRole.NURSE, COLLEAGUE_PIN))
                .thenReturn(new ColleagueResult(check, check.isOk() ? COLLEAGUE_USER : null, wrongTry));
    }

    private void opsThrows(RuntimeException failure) {
        org.mockito.Mockito.doThrow(failure).when(ops)
                .checkColleague(TENANT, USER, COLLEAGUE_MEMBER, MembershipRole.NURSE, COLLEAGUE_PIN);
    }

    private ColleaguePinCheck checkColleague() {
        return service.checkColleague(TENANT, USER, COLLEAGUE_MEMBER, "NURSE", COLLEAGUE_PIN);
    }

    @Test
    void checkColleagueRefusesToRunInsideAnActiveTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(this::checkColleague).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ops, limits, scope);
    }

    @Test
    void checkColleagueRequiresTheClinicTheCallerAndAKnownRole() {
        assertThatThrownBy(() -> service.checkColleague(null, USER, COLLEAGUE_MEMBER, "NURSE", "4821"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.checkColleague(TENANT, null, COLLEAGUE_MEMBER, "NURSE", "4821"))
                .isInstanceOf(IllegalArgumentException.class);
        for (String role : new String[] {null, "", "  ", "nurse", "SURGEON"}) {
            assertThatThrownBy(() -> service.checkColleague(TENANT, USER, COLLEAGUE_MEMBER, role, "4821"))
                    .as("[%s]", role).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(ops, limits, scope);
    }

    @Test
    void theCallersOwnLimitIsLookedAtFirstAndStopsTheTryBeforeAnyHashOrRead() {
        when(limits.colleagueCheckWait(TENANT, USER)).thenReturn(540L);

        ColleaguePinCheck result = checkColleague();

        assertThat(result.outcome()).isEqualTo(Outcome.RATE_LIMITED);
        assertThat(result.pin().retryAfterSeconds()).isEqualTo(540L);
        verifyNoInteractions(ops, scope);
        verify(limits, never()).pinCheckWait(any(), any());
        verify(limits, never()).colleagueCheckFailed(any(), any());
        assertThatThrownBy(result::requireOk).isInstanceOfSatisfying(StaffPinException.class, ex -> {
            assertThat(ex.slug()).isEqualTo("rate-limit");
            assertThat(ex.retryAfterSeconds()).isEqualTo(540L);
        });
    }

    @Test
    void aMissingPepperFailsClosedWith503AndCountsNothing() {
        service = serviceWith(StaffPinHasher.create(null, JWT_SECRET, 4));

        ColleaguePinCheck result = checkColleague();

        assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        verifyNoInteractions(ops, scope);
        verify(limits, never()).colleagueCheckFailed(any(), any());
        assertThatThrownBy(result::requireOk).isInstanceOfSatisfying(StaffPinException.class,
                ex -> assertThat(ex.slug()).isEqualTo("pin-unavailable"));
    }

    @Test
    void theCallersLimitComesBeforeThePepperLikeTheOwnCheck() {
        when(limits.colleagueCheckWait(TENANT, USER)).thenReturn(30L);
        service = serviceWith(StaffPinHasher.create(null, JWT_SECRET, 4));

        assertThat(checkColleague().outcome()).isEqualTo(Outcome.RATE_LIMITED);
    }

    @Test
    void anIneligibleColleagueIsAnsweredByTheTransactionAndNothingIsCounted() {
        // another clinic's id, an inactive member, another role, the caller: the transaction decides, all look the same
        opsAnswers(new PinCheck(Outcome.NOT_ELIGIBLE, 0, null, 0), false);

        ColleaguePinCheck result = checkColleague();

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_ELIGIBLE);
        assertThat(result.colleagueUserId()).isNull();
        verify(limits, never()).colleagueCheckFailed(any(), any());
        assertThatThrownBy(result::requireOk).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void theColleaguesOwnFailedCheckLimitIsNeverReadOrFilled() {
        // ADR-014 D7: other people's wrong tries must not make anyone who names the colleague get a 429
        opsAnswers(new PinCheck(Outcome.WRONG, 3, null, 0), true);
        checkColleague();
        opsAnswers(new PinCheck(Outcome.LOCKED, 0, Instant.parse("2026-11-06T05:50:00Z"), 900), true);
        checkColleague();
        opsAnswers(new PinCheck(Outcome.OK, 5, null, 0), false);
        checkColleague();

        verify(limits, never()).pinCheckWait(any(), any());
        verify(limits, never()).pinCheckFailed(any(), any());
        verify(ops, never()).readOwn(any(), any());
    }

    @Test
    void aRightColleaguePinReturnsTheColleaguesUserIdAndNeverUsesAllowance() {
        opsAnswers(new PinCheck(Outcome.OK, 5, null, 0), false);

        ColleaguePinCheck result = checkColleague();

        assertThat(result.requireOk()).isEqualTo(COLLEAGUE_USER);
        verify(scope, org.mockito.Mockito.times(1)).runAsTenant(org.mockito.ArgumentMatchers.eq(USER),
                org.mockito.ArgumentMatchers.eq(TENANT), any());
        verify(limits, never()).colleagueCheckFailed(any(), any());
    }

    @Test
    void aWrongColleaguePinCountsOnceOnTheCallersInMemoryLimitAfterLookingAtIt() {
        opsAnswers(new PinCheck(Outcome.WRONG, 3, null, 0), true);

        ColleaguePinCheck result = checkColleague();

        assertThat(result.outcome()).isEqualTo(Outcome.WRONG);
        InOrder order = inOrder(limits, ops);
        order.verify(limits).colleagueCheckWait(TENANT, USER);
        order.verify(ops).checkColleague(TENANT, USER, COLLEAGUE_MEMBER, MembershipRole.NURSE, COLLEAGUE_PIN);
        order.verify(limits).colleagueCheckFailed(TENANT, USER);
        assertThatThrownBy(result::requireOk).isInstanceOfSatisfying(StaffPinException.class, ex -> {
            assertThat(ex.slug()).isEqualTo("wrong-pin");
            assertThat(ex.getMessage()).isEqualTo("That PIN is not right.");
        });
    }

    @Test
    void theWrongTryThatLocksCountsToo() {
        opsAnswers(new PinCheck(Outcome.LOCKED, 0, Instant.parse("2026-11-06T05:50:00Z"), 900), true);
        checkColleague();

        verify(limits, org.mockito.Mockito.times(1)).colleagueCheckFailed(TENANT, USER);
    }

    @Test
    void aLockedOrNotSetColleagueAnswersWithoutCountingAnyAllowance() {
        opsAnswers(new PinCheck(Outcome.LOCKED, 0, Instant.parse("2026-11-06T05:50:00Z"), 600), false);
        assertThat(checkColleague().outcome()).isEqualTo(Outcome.LOCKED);
        opsAnswers(new PinCheck(Outcome.NOT_SET, 0, null, 0, NotSetReason.RESET_BY_OWNER), false);
        assertThat(checkColleague().outcome()).isEqualTo(Outcome.NOT_SET);

        verify(limits, never()).colleagueCheckFailed(any(), any());
    }

    @Test
    void thePairLimitAnswers429ColleaguePinLimitWithNoRetryTimeAndCountsNothing() {
        opsAnswers(new PinCheck(Outcome.PAIR_LIMIT, 0, null, 0), false);

        ColleaguePinCheck result = checkColleague();

        assertThat(result.outcome()).isEqualTo(Outcome.PAIR_LIMIT);
        verify(limits, never()).colleagueCheckFailed(any(), any());
        assertThatThrownBy(result::requireOk).isInstanceOfSatisfying(StaffPinException.class, ex -> {
            assertThat(ex.slug()).isEqualTo("colleague-pin-limit");
            assertThat(ex.status()).isEqualTo(429);
            assertThat(ex.title()).isEqualTo("Too many wrong PIN tries");
            assertThat(ex.getMessage()).isEqualTo("Too many wrong PIN tries for this nurse. Choose another nurse.");
            assertThat(ex.retryAfterSeconds()).isZero();
        });
    }

    @Test
    void aMissingPepperInsideTheTransactionPassesThroughUncounted() {
        opsAnswers(new PinCheck(Outcome.UNAVAILABLE, 0, null, 0), false);
        assertThat(checkColleague().outcome()).isEqualTo(Outcome.UNAVAILABLE);

        verify(limits, never()).colleagueCheckFailed(any(), any());
    }

    @Test
    void aDatabaseTimeoutOrOutageFailsClosedWith503AndCountsNothing() {
        RuntimeException[] failures = {
            new org.springframework.dao.CannotAcquireLockException("lock timeout"),
            new org.springframework.dao.QueryTimeoutException("statement timeout"),
            new org.springframework.dao.DataAccessResourceFailureException("connection lost"),
            new org.springframework.transaction.CannotCreateTransactionException("no connection")
        };
        for (RuntimeException failure : failures) {
            opsThrows(failure);

            ColleaguePinCheck result = checkColleague();

            assertThat(result.outcome()).as(failure.getClass().getSimpleName()).isEqualTo(Outcome.CHECK_FAILED);
            assertThat(result.colleagueUserId()).isNull();
            assertThatThrownBy(result::requireOk).isInstanceOfSatisfying(StaffPinException.class, ex -> {
                assertThat(ex.slug()).isEqualTo("pin-check-failed");
                assertThat(ex.status()).isEqualTo(503);
                assertThat(ex.title()).isEqualTo("PIN check failed");
                assertThat(ex.getMessage()).isEqualTo("We could not check the PIN. Try again.");
                assertThat(ex.retryAfterSeconds()).isZero();
            });
        }
        verify(limits, never()).colleagueCheckFailed(any(), any());
    }

    @Test
    void aConstraintPermissionOrSqlRefusalIsACodeFaultAndIsNotTurnedIntoAnAnswerAboutThePin() {
        RuntimeException[] faults = {
            new org.springframework.dao.DataIntegrityViolationException("23514"),
            new org.springframework.dao.DuplicateKeyException("23505"),
            new org.springframework.dao.PermissionDeniedDataAccessException("42501", null),
            new org.springframework.jdbc.BadSqlGrammarException("task", "sql", new java.sql.SQLException("42601")),
            new org.springframework.dao.InvalidDataAccessApiUsageException("misuse"),
            new IllegalStateException("The staff PIN row disappeared during a lock")
        };
        for (RuntimeException fault : faults) {
            opsThrows(fault);

            assertThatThrownBy(this::checkColleague).as(fault.getClass().getSimpleName()).isSameAs(fault);
        }
        verify(limits, never()).colleagueCheckFailed(any(), any());
    }

    @Test
    void theCallersParallelTriesRunOneAfterAnotherSoABurstCannotPassTheLimit() throws Exception {
        AtomicBoolean counted = new AtomicBoolean();
        when(limits.colleagueCheckWait(TENANT, USER)).thenAnswer(call -> counted.get() ? 600L : 0L);
        org.mockito.Mockito.doAnswer(call -> {
            counted.set(true);
            return null;
        }).when(limits).colleagueCheckFailed(TENANT, USER);
        CountDownLatch insideOps = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ops.checkColleague(TENANT, USER, COLLEAGUE_MEMBER, MembershipRole.NURSE, COLLEAGUE_PIN))
                .thenAnswer(call -> {
                    insideOps.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return new ColleagueResult(new PinCheck(Outcome.WRONG, 4, null, 0), null, true);
                });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ColleaguePinCheck> first = pool.submit(this::checkColleague);
            assertThat(insideOps.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ColleaguePinCheck> second = pool.submit(this::checkColleague);
            Thread.sleep(150);
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.WRONG);
            assertThat(second.get(5, TimeUnit.SECONDS).outcome())
                    .as("the second try looked at the limit only after the first was counted")
                    .isEqualTo(Outcome.RATE_LIMITED);
            verify(ops, org.mockito.Mockito.times(1)).checkColleague(TENANT, USER, COLLEAGUE_MEMBER,
                    MembershipRole.NURSE, COLLEAGUE_PIN);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void checkColleagueNeverPutsThePinOrAnIdIntoTheResultText() {
        opsAnswers(new PinCheck(Outcome.WRONG, 3, null, 0), true);

        ColleaguePinCheck result = checkColleague();

        assertThat(result.toString()).doesNotContain(COLLEAGUE_PIN).doesNotContain(COLLEAGUE_USER.toString())
                .doesNotContain(COLLEAGUE_MEMBER.toString());
    }

    // ---- status ----

    @Test
    void theStatusSaysWhetherPinsAreAvailable() {
        assertThat(service.status(TENANT, USER).available()).isTrue();
        service = serviceWith(StaffPinHasher.create("", JWT_SECRET, 4));
        assertThat(service.status(TENANT, USER).available()).isFalse();
    }

    @Test
    void theStatusNeedsAnActiveMembership() {
        when(ops.readOwn(TENANT, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.status(TENANT, USER)).isInstanceOf(ForbiddenException.class);
    }

    // ---- send code ----

    @Test
    void sendingACodeSendsItToTheOwnMobileReadOnTheServer() {
        when(otpService.request(MOBILE)).thenReturn(30);

        PinCodeSentView sent = service.sendCode(TENANT, USER);

        assertThat(sent.resendAfterSeconds()).isEqualTo(30);
        assertThat(sent.mobileMasked()).isEqualTo("••••••7410").doesNotContain("9822");
        verify(otpService).request(MOBILE);
    }

    @Test
    void noCodeIsSentWhenThePinCannotBeSaved() {
        service = serviceWith(StaffPinHasher.create(null, JWT_SECRET, 4));

        assertThatThrownBy(() -> service.sendCode(TENANT, USER)).isInstanceOfSatisfying(StaffPinException.class,
                ex -> assertThat(ex.slug()).isEqualTo("pin-unavailable"));
        verifyNoInteractions(otpService);
    }

    @Test
    void moreThanFiveCodeRequestsAnHourAnswerRateLimit() {
        when(limits.codeRequestWait(TENANT, USER)).thenReturn(1800L);

        assertThatThrownBy(() -> service.sendCode(TENANT, USER)).isInstanceOfSatisfying(StaffPinException.class, ex -> {
            assertThat(ex.slug()).isEqualTo("rate-limit");
            assertThat(ex.retryAfterSeconds()).isEqualTo(1800L);
        });
        verifyNoInteractions(otpService);
    }

    @Test
    void theSignInCodeRulesStillApplyToTheCodeRequest() {
        when(otpService.request(MOBILE)).thenThrow(new OtpResendTooSoonException(Duration.ofSeconds(12)));
        assertThatThrownBy(() -> service.sendCode(TENANT, USER)).isInstanceOf(OtpResendTooSoonException.class);

        doThrow(new OtpLockedException(Duration.ofMinutes(15))).when(otpService).request(MOBILE);
        assertThatThrownBy(() -> service.sendCode(TENANT, USER)).isInstanceOf(OtpLockedException.class);
    }

    @Test
    void aCodeIsNotSentToSomeoneWithoutAnActiveMembership() {
        when(ops.readOwn(TENANT, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendCode(TENANT, USER)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(otpService);
    }

    // ---- set ----

    @Test
    void aGoodRequestChecksTheCodeThenSavesInThatOrder() {
        PinStatusView result = service.set(TENANT, USER, input("493017", "4831", "4831"));

        assertThat(result).isSameAs(savedView);
        InOrder order = inOrder(limits, ops, otpService);
        order.verify(limits).pinSetWait(TENANT, USER);
        order.verify(ops).readOwn(TENANT, USER);
        order.verify(otpService).verify(MOBILE, "493017");
        order.verify(ops).commitSet(TENANT, USER, "4831");
    }

    @Test
    void theRateLimitComesBeforeEverythingElse() {
        when(limits.pinSetWait(TENANT, USER)).thenReturn(90L);
        service = serviceWith(StaffPinHasher.create(null, JWT_SECRET, 4));

        assertThatThrownBy(() -> service.set(TENANT, USER, input("493017", "4831", "4831")))
                .isInstanceOfSatisfying(StaffPinException.class, ex -> {
                    assertThat(ex.slug()).as("429 before 503").isEqualTo("rate-limit");
                    assertThat(ex.retryAfterSeconds()).isEqualTo(90L);
                });
        verifyNoInteractions(ops, otpService);
    }

    @Test
    void aMissingPepperIs503AndTheCodeIsNotUsedUp() {
        service = serviceWith(StaffPinHasher.create("  ", JWT_SECRET, 4));

        assertThatThrownBy(() -> service.set(TENANT, USER, input("493017", "4831", "4831")))
                .isInstanceOfSatisfying(StaffPinException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("pin-unavailable"));
        verifyNoInteractions(otpService);
        verify(ops, never()).commitSet(any(), any(), anyString());
    }

    @Test
    void everyFieldErrorIsAnsweredTogetherInTheOrderCodeNewPinConfirmPin() {
        assertThatThrownBy(() -> service.set(TENANT, USER, input("12", "12a", "9")))
                .isInstanceOf(InvalidFieldsException.class)
                .satisfies(ex -> assertThat(fieldErrors(ex)).containsExactly(
                        new FieldMessage("code", "Enter the 6-digit code."),
                        new FieldMessage("newPin", "Enter a 4-digit PIN."),
                        new FieldMessage("confirmPin", "The two PINs do not match.")));
        verifyNoInteractions(otpService);
        verify(ops, never()).commitSet(any(), any(), anyString());
    }

    @Test
    void missingFieldsAreFormatErrorsNotCrashes() {
        assertThatThrownBy(() -> service.set(TENANT, USER, input(null, null, null)))
                .satisfies(ex -> assertThat(fieldErrors(ex)).containsExactly(
                        new FieldMessage("code", "Enter the 6-digit code."),
                        new FieldMessage("newPin", "Enter a 4-digit PIN.")));
        assertThatThrownBy(() -> service.set(TENANT, USER, input("493017", "4831", null)))
                .satisfies(ex -> assertThat(fieldErrors(ex))
                        .containsExactly(new FieldMessage("confirmPin", "The two PINs do not match.")));
    }

    @Test
    void aWeakPinIsRefusedBeforeTheCodeIsUsedUp() {
        for (String weak : new String[] {"1111", "1234", "4321", "1212", "1122", "1987", "2580", "0852", "9822"}) {
            assertThatThrownBy(() -> service.set(TENANT, USER, input("493017", weak, weak)))
                    .satisfies(ex -> assertThat(fieldErrors(ex)).as(weak).containsExactly(
                            new FieldMessage("newPin", "This PIN is too easy to guess. Choose another one.")));
        }
        verifyNoInteractions(otpService);
    }

    @Test
    void theWeakRuleReadsTheOwnMobileOnTheServer() {
        // 7741 is part of the own number 9822077410, so it is refused for this person only
        assertThatThrownBy(() -> service.set(TENANT, USER, input("493017", "7741", "7741")))
                .satisfies(ex -> assertThat(fieldErrors(ex).get(0).field()).isEqualTo("newPin"));
        when(ops.readOwn(TENANT, USER)).thenReturn(Optional.of(new Own(MEMBER, "9123456780", null)));
        when(ops.commitSet(TENANT, USER, "7741")).thenReturn(savedView);

        assertThat(service.set(TENANT, USER, input("493017", "7741", "7741"))).isSameAs(savedView);
    }

    @Test
    void aWrongOrExpiredOrMissingCodeIsA400FieldErrorOnCodeNeverA401() {
        String[][] cases = {
            {"No code was sent to this number.", "Send a code first."},
            {"That code has expired. Request a new one.", "That code has expired. Request a new one."},
            {"That code is not right.", "That code is not right."}};
        for (String[] oneCase : cases) {
            doThrow(new OtpInvalidException(oneCase[0])).when(otpService).verify(MOBILE, "493017");

            assertThatThrownBy(() -> service.set(TENANT, USER, input("493017", "4831", "4831")))
                    .isInstanceOf(InvalidFieldsException.class)
                    .satisfies(ex -> assertThat(fieldErrors(ex))
                            .containsExactly(new FieldMessage("code", oneCase[1])));
        }
        verify(ops, never()).commitSet(any(), any(), anyString());
    }

    @Test
    void fiveWrongCodesStayA423LockNotA400() {
        doThrow(new OtpLockedException(Duration.ofMinutes(15))).when(otpService).verify(MOBILE, "493017");

        assertThatThrownBy(() -> service.set(TENANT, USER, input("493017", "4831", "4831")))
                .isInstanceOf(OtpLockedException.class);
        verify(ops, never()).commitSet(any(), any(), anyString());
    }

    @Test
    void twoFirstSavesAtTheSameMomentLeaveOneA409ConcurrentChange() {
        when(ops.commitSet(TENANT, USER, "4831")).thenThrow(new DuplicateKeyException("pk_staff_pin"));

        assertThatThrownBy(() -> service.set(TENANT, USER, input("493017", "4831", "4831")))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.problemType()).isEqualTo("concurrent-change"));
    }

    @Test
    void settingNeedsAnActiveMembership() {
        when(ops.readOwn(TENANT, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.set(TENANT, USER, input("493017", "4831", "4831")))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(otpService);
    }

    @Test
    void noMessageOrToStringEchoesThePinOrTheCode() {
        assertThat(input("493017", "4831", "4831").toString()).doesNotContain("4831").doesNotContain("493017");
        assertThatThrownBy(() -> service.set(TENANT, USER, input("493017", "1111", "2222")))
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).doesNotContain("1111").doesNotContain("2222").doesNotContain("493017");
                    assertThat(fieldErrors(ex).toString()).doesNotContain("1111").doesNotContain("2222");
                });
    }
}
