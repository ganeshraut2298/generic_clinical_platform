package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.domain.OtpChallenge;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class OtpServiceTest {

    private static final String MOBILE = "9123457410";

    private OtpChallengeRepository repository;
    private OtpSender sender;
    private PasswordEncoder passwordEncoder;
    private Clock clock;
    private Instant now;
    private OtpService service;

    @BeforeEach
    void setUp() {
        repository = mock(OtpChallengeRepository.class);
        sender = mock(OtpSender.class);
        passwordEncoder = mock(PasswordEncoder.class);
        now = Instant.parse("2026-11-01T10:00:00Z");
        clock = Clock.fixed(now, ZoneOffset.UTC);
        service = new OtpService(repository, sender, passwordEncoder, new SecureRandom(), clock);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
    }

    @Test
    void sendsASixDigitCodeAndReturnsTheResendCountdown() {
        when(repository.findLatestByMobile(MOBILE)).thenReturn(Optional.empty());

        int resendAfter = service.request(MOBILE);

        assertThat(resendAfter).isEqualTo(30);
        verify(sender).send(eq(MOBILE), argThatSixDigits());
        verify(repository).insert(eq(MOBILE), eq("hashed"), eq(now.plus(OtpService.CODE_TTL)), eq(now));
    }

    @Test
    void refusesAResendBeforeThirtySeconds() {
        OtpChallenge recent = challenge(now.minusSeconds(10), 0, null, null);
        when(repository.findLatestByMobile(MOBILE)).thenReturn(Optional.of(recent));

        assertThatThrownBy(() -> service.request(MOBILE))
                .isInstanceOf(OtpResendTooSoonException.class)
                .satisfies(ex -> assertThat(((OtpResendTooSoonException) ex).retryAfterSeconds()).isEqualTo(20));
        verify(sender, never()).send(any(), any());
    }

    @Test
    void refusesARequestWhileLocked() {
        OtpChallenge locked = challenge(now.minusSeconds(100), 5, now.plus(Duration.ofMinutes(10)), null);
        when(repository.findLatestByMobile(MOBILE)).thenReturn(Optional.of(locked));

        assertThatThrownBy(() -> service.request(MOBILE)).isInstanceOf(OtpLockedException.class);
        verify(sender, never()).send(any(), any());
    }

    @Test
    void verifyAcceptsTheRightCodeAndMarksTheChallengeConsumed() {
        UUID id = UUID.randomUUID();
        OtpChallenge challenge = new OtpChallenge(id, MOBILE, "hashed", now.plus(Duration.ofMinutes(4)), 0, null, now, null);
        when(repository.findLatestByMobile(MOBILE)).thenReturn(Optional.of(challenge));
        when(passwordEncoder.matches("123456", "hashed")).thenReturn(true);

        service.verify(MOBILE, "123456");

        verify(repository).markConsumed(id, now);
    }

    @Test
    void verifyRefusesAnExpiredCode() {
        OtpChallenge expired = challenge(now.minus(OtpService.CODE_TTL).minusSeconds(1), 0, null, null);
        when(repository.findLatestByMobile(MOBILE)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.verify(MOBILE, "123456")).isInstanceOf(OtpInvalidException.class);
    }

    @Test
    void verifyRefusesAnAlreadyConsumedCode() {
        OtpChallenge consumed = challenge(now, 0, null, now);
        when(repository.findLatestByMobile(MOBILE)).thenReturn(Optional.of(consumed));

        assertThatThrownBy(() -> service.verify(MOBILE, "123456")).isInstanceOf(OtpInvalidException.class);
    }

    @Test
    void fiveWrongCodesLockTheMobileForFifteenMinutes() {
        UUID id = UUID.randomUUID();
        OtpChallenge challenge = new OtpChallenge(id, MOBILE, "hashed", now.plus(Duration.ofMinutes(4)), 4, null, now, null);
        when(repository.findLatestByMobile(MOBILE)).thenReturn(Optional.of(challenge));
        when(passwordEncoder.matches("000000", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> service.verify(MOBILE, "000000")).isInstanceOf(OtpLockedException.class);
        verify(repository).recordFailedAttempt(id, 5, now.plus(OtpService.LOCK_DURATION));
    }

    @Test
    void aWrongCodeBelowTheLimitStaysUnlocked() {
        UUID id = UUID.randomUUID();
        OtpChallenge challenge = new OtpChallenge(id, MOBILE, "hashed", now.plus(Duration.ofMinutes(4)), 1, null, now, null);
        when(repository.findLatestByMobile(MOBILE)).thenReturn(Optional.of(challenge));
        when(passwordEncoder.matches("000000", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> service.verify(MOBILE, "000000")).isInstanceOf(OtpInvalidException.class);
        verify(repository).recordFailedAttempt(id, 2, null);
    }

    @Test
    void verifyWithNoChallengeAtAllIsInvalid() {
        when(repository.findLatestByMobile(MOBILE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(MOBILE, "123456")).isInstanceOf(OtpInvalidException.class);
    }

    /** Mocks cannot see a rollback; against a real database a rolled-back wrong code never counted. */
    @Test
    void wrongCodeAndLockDoNotRollBackTheFailedAttemptCount() throws NoSuchMethodException {
        org.springframework.transaction.annotation.Transactional transactional = OtpService.class
                .getMethod("verify", String.class, String.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor()).contains(OtpInvalidException.class, OtpLockedException.class);
    }

    private OtpChallenge challenge(Instant lastSentAt, int failedCount, Instant lockedUntil, Instant consumedAt) {
        return new OtpChallenge(UUID.randomUUID(), MOBILE, "hashed", now.plus(Duration.ofMinutes(4)), failedCount,
                lockedUntil, lastSentAt, consumedAt);
    }

    private static String argThatSixDigits() {
        return org.mockito.ArgumentMatchers.argThat(code -> code != null && code.matches("[0-9]{6}"));
    }
}
