package com.sahaj.platform.application.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.InvalidInputException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The sign-in rules of ADR-003 D2 with the database replaced by a mock: no user enumeration,
 * lockout, pre-auth handling, TOTP and recovery code checks, enrolment, refresh and the dev bypass.
 */
class AdminAuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final AdminAuthService.ClientContext CTX = new AdminAuthService.ClientContext("203.0.113.9", "req-1");
    private static final String TOKEN = "A".repeat(43);
    private static final String EMAIL = "dev.admin@example.test";

    private final UUID adminId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private final AdminTokenHasher hasher = new AdminTokenHasher("an-admin-secret-with-more-than-32-chars");
    private final TotpSecretCipher cipher = new TotpSecretCipher(new byte[32], new SecureRandom());
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicBoolean bypass = new AtomicBoolean(false);
    private final AtomicLong rateLimitWait = new AtomicLong(0);

    private AdminAuthRepository repository;
    private AdminTokenService tokens;
    private AdminPasswordHasher passwords;
    private AdminAuthService service;

    @BeforeEach
    void setUp() {
        repository = mock(AdminAuthRepository.class);
        tokens = mock(AdminTokenService.class);
        passwords = mock(AdminPasswordHasher.class);
        when(tokens.issue(any(), any(), any())).thenReturn(new AdminTokenService.AccessToken("access-token", 600));
        when(repository.throttleStatus(anyString())).thenReturn(Optional.empty());
        service = new AdminAuthService(repository, tokens, hasher, passwords, cipher, address -> bypass.get(),
                key -> rateLimitWait.get(), new SecureRandom(), clock);
    }

    private AdminAuthRepository.PreAuth totpRow(Long lastUsedStep) {
        return new AdminAuthRepository.PreAuth(UUID.randomUUID(), adminId, "TOTP",
                cipher.encrypt(secret, adminId, 1), 1, null, null, lastUsedStep, EMAIL, hasher.emailKey(EMAIL));
    }

    private AdminAuthRepository.PreAuth enrolRow(byte[] pendingEnc) {
        return new AdminAuthRepository.PreAuth(UUID.randomUUID(), adminId, "ENROL", null, null, pendingEnc,
                pendingEnc == null ? null : 1, null, EMAIL, hasher.emailKey(EMAIL));
    }

    private AdminAuthRepository.RotatedSession rotated(String method) {
        return new AdminAuthRepository.RotatedSession(
                new AdminAuthRepository.SessionRow(sessionId, adminId, "Dev admin"), method);
    }

    private String currentCode() {
        return Totp.generate(secret, Totp.stepAt(NOW), 6);
    }

    // ---------------------------------------------------------------- login

    @Test
    void unknownEmailRunsTheDummyHashAndAnswersTheSameAsAWrongPassword() {
        when(repository.loginLookup("nobody@example.test")).thenReturn(Optional.empty());
        when(repository.authFailed(anyString(), isNull(), isNull(), eq("PASSWORD"), anyString(), anyString()))
                .thenReturn(new AdminAuthRepository.FailureResult(null, false));

        assertThatThrownBy(() -> service.login("  Nobody@Example.test ", "whatever-password", CTX))
                .isInstanceOfSatisfying(AdminAuthException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.CREDENTIALS_INVALID));

        verify(passwords).dummyMatch("whatever-password");
        verify(passwords, never()).matches(anyString(), anyString());
        verify(repository, never()).preAuthCreate(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void wrongPasswordForARealAdminIsTheSameRefusalAndRecordsTheAdminId() {
        when(repository.loginLookup("dev.admin@example.test"))
                .thenReturn(Optional.of(new AdminAuthRepository.LoginLookup(adminId, "$2a$12$hash", true)));
        when(passwords.matches("wrong", "$2a$12$hash")).thenReturn(false);
        when(repository.authFailed(anyString(), eq(adminId), isNull(), eq("PASSWORD"), anyString(), anyString()))
                .thenReturn(new AdminAuthRepository.FailureResult(null, false));

        assertThatThrownBy(() -> service.login("dev.admin@example.test", "wrong", CTX))
                .isInstanceOfSatisfying(AdminAuthException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.CREDENTIALS_INVALID));

        verify(passwords, never()).dummyMatch(anyString());
    }

    @Test
    void theEmailIsTrimmedAndLowerCasedBeforeEveryUse() {
        when(repository.loginLookup(anyString())).thenReturn(Optional.empty());
        when(repository.authFailed(anyString(), any(), any(), anyString(), any(), any()))
                .thenReturn(new AdminAuthRepository.FailureResult(null, false));

        assertThatThrownBy(() -> service.login("  DEV.Admin@Example.TEST  ", "x", CTX))
                .isInstanceOf(AdminAuthException.class);

        verify(repository).loginLookup("dev.admin@example.test");
        verify(repository).throttleStatus(hasher.emailKey("dev.admin@example.test"));
    }

    @Test
    void theFifthFailureAnswersLockedWithTheRemainingSeconds() {
        when(repository.loginLookup(anyString())).thenReturn(Optional.empty());
        when(repository.authFailed(anyString(), any(), any(), eq("PASSWORD"), any(), any()))
                .thenReturn(new AdminAuthRepository.FailureResult(NOW.plusSeconds(900), false));

        assertThatThrownBy(() -> service.login("x@example.test", "bad", CTX))
                .isInstanceOfSatisfying(AdminAuthException.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.SIGN_IN_LOCKED);
                    assertThat(ex.retryAfterSeconds()).isEqualTo(900);
                });
    }

    @Test
    void aLockedEmailIsRefusedBeforeAnyPasswordWorkWhetherOrNotItBelongsToAnAdmin() {
        when(repository.throttleStatus(hasher.emailKey("x@example.test"))).thenReturn(Optional.of(NOW.plusSeconds(600)));

        assertThatThrownBy(() -> service.login("x@example.test", "bad", CTX))
                .isInstanceOfSatisfying(AdminAuthException.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.SIGN_IN_LOCKED);
                    assertThat(ex.retryAfterSeconds()).isEqualTo(600);
                });
        verify(repository, never()).loginLookup(anyString());
    }

    @Test
    void theLoginRateLimitPerEmailAnswers429BeforeAnythingElse() {
        rateLimitWait.set(120);

        assertThatThrownBy(() -> service.login("x@example.test", "bad", CTX))
                .isInstanceOfSatisfying(AdminAuthException.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.RATE_LIMITED);
                    assertThat(ex.retryAfterSeconds()).isEqualTo(120);
                });
        verify(repository, never()).throttleStatus(anyString());
    }

    @Test
    void correctPasswordForAnEnrolledAdminIssuesATotpPreAuthToken() {
        when(repository.loginLookup("dev.admin@example.test"))
                .thenReturn(Optional.of(new AdminAuthRepository.LoginLookup(adminId, "$2a$12$hash", true)));
        when(passwords.matches("right-password", "$2a$12$hash")).thenReturn(true);

        AdminAuthService.LoginResult result = service.login("dev.admin@example.test", "right-password", CTX);

        assertThat(result.next()).isEqualTo(AdminAuthService.Next.TOTP);
        assertThat(result.expiresInSeconds()).isEqualTo(300);
        assertThat(result.preAuthToken()).matches("^[A-Za-z0-9_-]{43}$");
        ArgumentCaptor<String> tokenHash = ArgumentCaptor.forClass(String.class);
        verify(repository).preAuthCreate(eq(adminId), eq(hasher.emailKey("dev.admin@example.test")),
                tokenHash.capture(), eq("TOTP"), eq("203.0.113.9"));
        assertThat(tokenHash.getValue()).isEqualTo(hasher.preAuth(result.preAuthToken()))
                .isNotEqualTo(result.preAuthToken());
    }

    @Test
    void correctPasswordForAnAdminWithoutAuthenticatorSaysEnrol() {
        when(repository.loginLookup("dev.admin@example.test"))
                .thenReturn(Optional.of(new AdminAuthRepository.LoginLookup(adminId, "$2a$12$hash", false)));
        when(passwords.matches("right-password", "$2a$12$hash")).thenReturn(true);

        AdminAuthService.LoginResult result = service.login("dev.admin@example.test", "right-password", CTX);

        assertThat(result.next()).isEqualTo(AdminAuthService.Next.ENROL);
        verify(repository).preAuthCreate(eq(adminId), anyString(), anyString(), eq("ENROL"), anyString());
    }

    @Test
    void twoLoginsGiveTwoDifferentTokens() {
        when(repository.loginLookup(anyString()))
                .thenReturn(Optional.of(new AdminAuthRepository.LoginLookup(adminId, "h", true)));
        when(passwords.matches(anyString(), anyString())).thenReturn(true);
        String first = service.login("a@example.test", "pw", CTX).preAuthToken();
        String second = service.login("a@example.test", "pw", CTX).preAuthToken();
        assertThat(first).isNotEqualTo(second);
    }

    // ---------------------------------------------------------------- verify with an authenticator code

    @Test
    void aCorrectCodeSignsInWithTheMatchedStepAndNoRecoveryCodes() {
        when(repository.preAuthGet(hasher.preAuth(TOKEN))).thenReturn(Optional.of(totpRow(null)));
        when(repository.signIn(anyString(), eq("TOTP"), any(), isNull(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new AdminAuthRepository.SessionRow(sessionId, adminId, "Dev admin"));

        AdminAuthService.SignedIn result = service.verify(TOKEN, currentCode(), null, CTX);

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.expiresInSeconds()).isEqualTo(600);
        assertThat(result.admin().name()).isEqualTo("Dev admin");
        assertThat(result.recoveryCodes()).isNull();
        assertThat(result.refreshToken()).matches("^[A-Za-z0-9_-]{43}$");
        ArgumentCaptor<Long> step = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> refreshHash = ArgumentCaptor.forClass(String.class);
        verify(repository).signIn(eq(hasher.preAuth(TOKEN)), eq("TOTP"), step.capture(), isNull(),
                refreshHash.capture(), eq("203.0.113.9"), eq("req-1"), isNull());
        assertThat(step.getValue()).isEqualTo(Totp.stepAt(NOW));
        assertThat(refreshHash.getValue()).isEqualTo(hasher.refresh(result.refreshToken()));
        verify(tokens).issue(eq(adminId), eq(sessionId), eq(List.of("pwd", "otp")));
    }

    @Test
    void aLockedEmailRefusesEvenACorrectCodeAndRecordsNothing() {
        AdminAuthRepository.PreAuth row = totpRow(null);
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(row));
        when(repository.throttleStatus(row.throttleKey())).thenReturn(Optional.of(NOW.plusSeconds(600)));

        assertThatThrownBy(() -> service.verify(TOKEN, currentCode(), null, CTX))
                .isInstanceOfSatisfying(AdminAuthException.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.SIGN_IN_LOCKED);
                    assertThat(ex.retryAfterSeconds()).isEqualTo(600);
                });

        verify(repository).throttleStatus(hasher.emailKey(EMAIL));
        verify(repository, never()).signIn(any(), any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).authFailed(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aLockedEmailRefusesARecoveryCodeAndTheEnrolmentConfirmationToo() {
        AdminAuthRepository.PreAuth row = totpRow(null);
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(row));
        when(repository.throttleStatus(row.throttleKey())).thenReturn(Optional.of(NOW.plusSeconds(60)));

        assertThatThrownBy(() -> service.verify(TOKEN, null, "abcd-efgh-2345-67ab", CTX))
                .isInstanceOfSatisfying(AdminAuthException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.SIGN_IN_LOCKED));
        verify(repository, never()).signIn(any(), any(), any(), any(), any(), any(), any(), any());

        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(enrolRow(cipher.encrypt(secret, adminId, 1))));
        assertThatThrownBy(() -> service.verify(TOKEN, currentCode(), null, CTX))
                .isInstanceOfSatisfying(AdminAuthException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.SIGN_IN_LOCKED));
        verify(repository, never()).completeEnrolment(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aWrongCodeIsRecordedAndAnswers401TotpInvalid() {
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(totpRow(null)));
        String valid = currentCode();
        String wrong = valid.equals("000000") ? "000001" : "000000";
        when(repository.authFailed(isNull(), eq(adminId), eq(hasher.preAuth(TOKEN)), eq("TOTP"), anyString(), anyString()))
                .thenReturn(new AdminAuthRepository.FailureResult(null, false));

        assertThatThrownBy(() -> service.verify(TOKEN, wrong, null, CTX))
                .isInstanceOfSatisfying(AdminAuthException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.TOTP_INVALID));
        verify(repository, never()).signIn(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theFifthWrongCodeConsumesThePreAuthTokenAndSaysStartAgain() {
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(totpRow(null)));
        when(repository.authFailed(isNull(), any(), anyString(), eq("TOTP"), any(), any()))
                .thenReturn(new AdminAuthRepository.FailureResult(null, true));

        assertThatThrownBy(() -> service.verify(TOKEN, "000000".equals(currentCode()) ? "000001" : "000000", null, CTX))
                .isInstanceOfSatisfying(AdminAuthException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.PREAUTH_EXPIRED));
    }

    @Test
    void aWrongCodeThatReachesTheEmailLockAnswers423() {
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(totpRow(null)));
        when(repository.authFailed(isNull(), any(), anyString(), eq("TOTP"), any(), any()))
                .thenReturn(new AdminAuthRepository.FailureResult(NOW.plusSeconds(900), false));

        assertThatThrownBy(() -> service.verify(TOKEN, "000000".equals(currentCode()) ? "000001" : "000000", null, CTX))
                .isInstanceOfSatisfying(AdminAuthException.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.SIGN_IN_LOCKED);
                    assertThat(ex.retryAfterSeconds()).isEqualTo(900);
                });
    }

    @Test
    void anUnknownExpiredOrUsedPreAuthTokenAnswersPreAuthExpired() {
        when(repository.preAuthGet(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(TOKEN, "123456", null, CTX))
                .isInstanceOfSatisfying(AdminAuthException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.PREAUTH_EXPIRED));
    }

    @Test
    void aCodeAtOrBeforeTheLastUsedStepIsRefusedBeforeTheDatabaseIsAsked() {
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(totpRow(Totp.stepAt(NOW))));
        when(repository.authFailed(isNull(), any(), anyString(), eq("TOTP"), any(), any()))
                .thenReturn(new AdminAuthRepository.FailureResult(null, false));

        assertThatThrownBy(() -> service.verify(TOKEN, currentCode(), null, CTX))
                .isInstanceOfSatisfying(AdminAuthException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.TOTP_INVALID));
        verify(repository, never()).signIn(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aReplayRacingPastTheJavaCheckIsRefusedByTheDatabaseAndCounted() {
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(totpRow(null)));
        when(repository.signIn(anyString(), eq("TOTP"), any(), any(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new AdminDbStateException(AdminDbStateException.State.TOTP_REPLAY));
        when(repository.authFailed(isNull(), any(), anyString(), eq("TOTP"), any(), any()))
                .thenReturn(new AdminAuthRepository.FailureResult(null, false));

        assertThatThrownBy(() -> service.verify(TOKEN, currentCode(), null, CTX))
                .isInstanceOfSatisfying(AdminAuthException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.TOTP_INVALID));
        verify(repository).authFailed(isNull(), eq(adminId), eq(hasher.preAuth(TOKEN)), eq("TOTP"), anyString(), anyString());
    }

    @Test
    void anAdminSwitchedOffBetweenTheStepsIsTheCredentialsRefusal() {
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(totpRow(null)));
        when(repository.signIn(anyString(), eq("TOTP"), any(), any(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new AdminDbStateException(AdminDbStateException.State.ADMIN_UNAVAILABLE));

        assertThatThrownBy(() -> service.verify(TOKEN, currentCode(), null, CTX))
                .isInstanceOfSatisfying(AdminAuthException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.CREDENTIALS_INVALID));
    }

    @Test
    void bothOrNeitherOfCodeAndRecoveryCodeIsA400() {
        assertThatThrownBy(() -> service.verify(TOKEN, null, null, CTX)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.verify(TOKEN, "123456", "ABCD-EFGH-2345-67AB", CTX))
                .isInstanceOf(InvalidInputException.class);
        verify(repository, never()).preAuthGet(anyString());
    }

    // ---------------------------------------------------------------- recovery codes

    @Test
    void aRecoveryCodeSignsInWithTheRecoveryMethodAndAmr() {
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(totpRow(null)));
        when(repository.signIn(anyString(), eq("RECOVERY_CODE"), isNull(), anyString(), anyString(), anyString(),
                anyString(), isNull())).thenReturn(new AdminAuthRepository.SessionRow(sessionId, adminId, "Dev admin"));

        service.verify(TOKEN, null, "abcd-efgh-2345-67ab", CTX);

        verify(repository).signIn(eq(hasher.preAuth(TOKEN)), eq("RECOVERY_CODE"), isNull(),
                eq(hasher.recovery("ABCDEFGH234567AB")), anyString(), anyString(), anyString(), isNull());
        verify(tokens).issue(eq(adminId), eq(sessionId), eq(List.of("pwd", "rcode")));
    }

    @Test
    void anUnknownOrUsedRecoveryCodeIsCountedAsARecoveryCodeFailure() {
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(totpRow(null)));
        when(repository.signIn(anyString(), eq("RECOVERY_CODE"), any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new AdminDbStateException(AdminDbStateException.State.RECOVERY_INVALID));
        when(repository.authFailed(isNull(), any(), anyString(), eq("RECOVERY_CODE"), any(), any()))
                .thenReturn(new AdminAuthRepository.FailureResult(null, false));

        assertThatThrownBy(() -> service.verify(TOKEN, null, "ABCD-EFGH-2345-67AB", CTX))
                .isInstanceOfSatisfying(AdminAuthException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.TOTP_INVALID));
    }

    @Test
    void aRecoveryCodeIsNeverAcceptedUnderTheDevBypass() {
        bypass.set(true);
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(totpRow(null)));
        when(repository.signIn(anyString(), eq("RECOVERY_CODE"), any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new AdminDbStateException(AdminDbStateException.State.RECOVERY_INVALID));
        when(repository.authFailed(isNull(), any(), anyString(), eq("RECOVERY_CODE"), any(), any()))
                .thenReturn(new AdminAuthRepository.FailureResult(null, false));

        assertThatThrownBy(() -> service.verify(TOKEN, null, "ABCD-EFGH-2345-67AB", CTX))
                .isInstanceOf(AdminAuthException.class);
    }

    @Test
    void aRecoveryCodeForTheFirstSignInIsA400() {
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(enrolRow(null)));

        assertThatThrownBy(() -> service.verify(TOKEN, null, "ABCD-EFGH-2345-67AB", CTX))
                .isInstanceOf(InvalidInputException.class);
    }

    // ---------------------------------------------------------------- dev bypass

    @Test
    void underTheBypassAnySixDigitsSignInWithoutAReplayStep() {
        bypass.set(true);
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(totpRow(null)));
        when(repository.signIn(anyString(), eq("TOTP"), isNull(), isNull(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new AdminAuthRepository.SessionRow(sessionId, adminId, "Dev admin"));

        AdminAuthService.SignedIn result = service.verify(TOKEN, "000000".equals(currentCode()) ? "000001" : "000000",
                null, CTX);

        assertThat(result.accessToken()).isEqualTo("access-token");
        verify(repository, never()).authFailed(any(), any(), any(), any(), any(), any());
        verify(repository).signIn(anyString(), eq("TOTP"), isNull(), isNull(), anyString(), anyString(), anyString(),
                eq("dev bypass"));
    }

    @Test
    void theDevBypassSummaryIsSentOnlyOnTheBypassTotpPath() {
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(totpRow(null)));
        when(repository.signIn(anyString(), anyString(), any(), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new AdminAuthRepository.SessionRow(sessionId, adminId, "Dev admin"));

        service.verify(TOKEN, currentCode(), null, CTX);
        service.verify(TOKEN, null, "abcd-efgh-2345-67ab", CTX);
        bypass.set(true);
        service.verify(TOKEN, "000000", null, CTX);

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(repository, org.mockito.Mockito.times(3)).signIn(anyString(), anyString(), any(), any(), anyString(),
                anyString(), anyString(), summary.capture());
        assertThat(summary.getAllValues()).containsExactly(null, null, "dev bypass");
    }

    @Test
    void withoutTheBypassTheSameCodeIsRefused() {
        bypass.set(false);
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(totpRow(null)));
        when(repository.authFailed(isNull(), any(), anyString(), eq("TOTP"), any(), any()))
                .thenReturn(new AdminAuthRepository.FailureResult(null, false));

        assertThatThrownBy(() -> service.verify(TOKEN, "000000".equals(currentCode()) ? "000001" : "000000", null, CTX))
                .isInstanceOf(AdminAuthException.class);
    }

    // ---------------------------------------------------------------- enrolment

    @Test
    void enrolCreatesAndStoresAnEncryptedPendingSecretAndReturnsItOnce() {
        AtomicBoolean stored = new AtomicBoolean(false);
        byte[][] enc = new byte[1][];
        when(repository.preAuthGet(anyString())).thenAnswer(invocation ->
                Optional.of(stored.get() ? enrolRow(enc[0]) : enrolRow(null)));
        org.mockito.Mockito.doAnswer(invocation -> {
            enc[0] = invocation.getArgument(1);
            stored.set(true);
            return null;
        }).when(repository).totpSetPending(anyString(), any(byte[].class), anyInt());

        AdminAuthService.Enrolment enrolment = service.enrol(TOKEN);

        assertThat(enrolment.secretKey()).matches("^[A-Z2-7]{4}( [A-Z2-7]{4}){7}$");
        assertThat(enrolment.accountName()).isEqualTo("dev.admin@example.test");
        assertThat(enrolment.issuer()).isEqualTo("Sahaj admin");
        String base32 = enrolment.secretKey().replace(" ", "");
        assertThat(enrolment.otpauthUri()).isEqualTo("otpauth://totp/Sahaj%20admin:dev.admin%40example.test?secret="
                + base32 + "&issuer=Sahaj%20admin&algorithm=SHA1&digits=6&period=30");
        // What was stored decrypts (for this admin) to the same secret the admin sees.
        assertThat(Base32.encode(cipher.decrypt(enc[0], adminId, 1))).isEqualTo(base32);
        assertThat(enc[0]).hasSize(48);
    }

    @Test
    void enrolNamesTheAccountFromTheEmailOfThePreAuthRowNotFromAnyMemory() {
        byte[] pending = cipher.encrypt(secret, adminId, 1);
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(new AdminAuthRepository.PreAuth(
                UUID.randomUUID(), adminId, "ENROL", null, null, pending, 1, null, "Other.Person@clinic.test",
                hasher.emailKey("other.person@clinic.test"))));

        AdminAuthService.Enrolment enrolment = service.enrol(TOKEN);

        assertThat(enrolment.accountName()).isEqualTo("Other.Person@clinic.test");
        assertThat(enrolment.otpauthUri()).startsWith("otpauth://totp/Sahaj%20admin:Other.Person%40clinic.test?secret=");
    }

    @Test
    void enrolAgainReturnsTheSamePendingSecretWithoutStoringANewOne() {
        byte[] pending = cipher.encrypt(secret, adminId, 1);
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(enrolRow(pending)));

        AdminAuthService.Enrolment enrolment = service.enrol(TOKEN);

        assertThat(enrolment.secretKey().replace(" ", "")).isEqualTo(Base32.encode(secret));
        verify(repository, never()).totpSetPending(anyString(), any(byte[].class), anyInt());
    }

    @Test
    void enrolForANormalSignInTokenIsTotpAlreadyEnrolled() {
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(totpRow(null)));

        assertThatThrownBy(() -> service.enrol(TOKEN))
                .isInstanceOfSatisfying(AdminAuthException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.TOTP_ALREADY_ENROLLED));
    }

    @Test
    void enrolWithAnUnknownTokenIsPreAuthExpired() {
        when(repository.preAuthGet(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.enrol(TOKEN))
                .isInstanceOfSatisfying(AdminAuthException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.PREAUTH_EXPIRED));
    }

    @Test
    void theFirstCorrectCodeCompletesEnrolmentAndReturnsTenRecoveryCodesOnce() {
        byte[] pending = cipher.encrypt(secret, adminId, 1);
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(enrolRow(pending)));
        when(repository.completeEnrolment(anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new AdminAuthRepository.SessionRow(sessionId, adminId, "Dev admin"));

        AdminAuthService.SignedIn result = service.verify(TOKEN, currentCode(), null, CTX);

        assertThat(result.recoveryCodes()).hasSize(10)
                .allMatch(code -> code.matches("^[A-Z2-7]{4}(-[A-Z2-7]{4}){3}$"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> hashes = ArgumentCaptor.forClass(List.class);
        verify(repository).completeEnrolment(eq(hasher.preAuth(TOKEN)), eq(Totp.stepAt(NOW)), hashes.capture(),
                eq(hasher.refresh(result.refreshToken())), eq("203.0.113.9"), eq("req-1"));
        assertThat(hashes.getValue()).hasSize(10).doesNotHaveDuplicates().allMatch(h -> h.matches("^[0-9a-f]{64}$"));
        // Only the keyed hash of each shown code is stored.
        assertThat(hashes.getValue()).containsExactlyInAnyOrderElementsOf(result.recoveryCodes().stream()
                .map(code -> hasher.recovery(RecoveryCodes.normalise(code))).toList());
        verify(tokens).issue(eq(adminId), eq(sessionId), eq(List.of("pwd", "otp")));
    }

    @Test
    void aWrongCodeAtEnrolmentIsCountedAndDoesNotEnrol() {
        byte[] pending = cipher.encrypt(secret, adminId, 1);
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(enrolRow(pending)));
        when(repository.authFailed(isNull(), any(), anyString(), eq("TOTP"), any(), any()))
                .thenReturn(new AdminAuthRepository.FailureResult(null, false));

        assertThatThrownBy(() -> service.verify(TOKEN, "000000".equals(currentCode()) ? "000001" : "000000", null, CTX))
                .isInstanceOfSatisfying(AdminAuthException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.TOTP_INVALID));
        verify(repository, never()).completeEnrolment(any(), any(), any(), any(), any(), any());
    }

    @Test
    void verifyBeforeEnrolIsTotpEnrolmentNotStarted() {
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(enrolRow(null)));

        assertThatThrownBy(() -> service.verify(TOKEN, "123456", null, CTX))
                .isInstanceOfSatisfying(AdminAuthException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.TOTP_ENROLMENT_NOT_STARTED));
    }

    @Test
    void theBypassConfirmsEnrolmentWithAnySixDigitsAndStoresNoStep() {
        bypass.set(true);
        byte[] pending = cipher.encrypt(secret, adminId, 1);
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(enrolRow(pending)));
        when(repository.completeEnrolment(anyString(), isNull(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new AdminAuthRepository.SessionRow(sessionId, adminId, "Dev admin"));

        AdminAuthService.SignedIn result = service.verify(TOKEN, "000000".equals(currentCode()) ? "000001" : "000000",
                null, CTX);

        assertThat(result.recoveryCodes()).hasSize(10);
    }

    // ---------------------------------------------------------------- refresh, sign-out, me

    @Test
    void refreshRotatesTheTokenAndIssuesANewAccessToken() {
        when(repository.sessionRotate(anyString(), anyString()))
                .thenReturn(Optional.of(rotated("TOTP")));

        AdminAuthService.SignedIn result = service.refresh("old-token-value");

        assertThat(result.recoveryCodes()).isNull();
        assertThat(result.refreshToken()).isNotEqualTo("old-token-value").matches("^[A-Za-z0-9_-]{43}$");
        verify(repository).sessionRotate(hasher.refresh("old-token-value"), hasher.refresh(result.refreshToken()));
        verify(tokens).issue(eq(adminId), eq(sessionId), eq(List.of("pwd", "otp")));
    }

    @Test
    void refreshKeepsTheRecoveryAmrForASessionOpenedWithARecoveryCode() {
        when(repository.sessionRotate(anyString(), anyString())).thenReturn(Optional.of(rotated("RECOVERY_CODE")));

        service.refresh("old-token-value");

        verify(tokens).issue(eq(adminId), eq(sessionId), eq(List.of("pwd", "rcode")));
    }

    @Test
    void refreshGivesTheAuthenticatorAmrForAnEnrolmentSession() {
        when(repository.sessionRotate(anyString(), anyString())).thenReturn(Optional.of(rotated("ENROL")));

        service.refresh("old-token-value");

        verify(tokens).issue(eq(adminId), eq(sessionId), eq(List.of("pwd", "otp")));
    }

    @Test
    void anEmptyRotationMeansSessionEndedWhetherUnknownDeadOrReused() {
        when(repository.sessionRotate(anyString(), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("reused-token"))
                .isInstanceOfSatisfying(AdminAuthException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.SESSION_ENDED));
    }

    @Test
    void aMissingCookieIsSessionEndedWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> service.refresh(null)).isInstanceOf(AdminAuthException.class);
        assertThatThrownBy(() -> service.refresh("  ")).isInstanceOf(AdminAuthException.class);
        verify(repository, never()).sessionRotate(any(), any());
    }

    @Test
    void signOutWithoutALiveSessionIsSessionEnded() {
        org.mockito.Mockito.doThrow(new AdminDbStateException(AdminDbStateException.State.NO_SESSION))
                .when(repository).signOut();

        assertThatThrownBy(() -> service.signOut())
                .isInstanceOfSatisfying(AdminAuthException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(AdminAuthException.Kind.SESSION_ENDED));
    }

    @Test
    void meReturnsTheAdminOrSessionEnded() {
        when(repository.me()).thenReturn(Optional.of(new AdminIdentity(adminId, "Dev admin")));
        assertThat(service.me().name()).isEqualTo("Dev admin");

        when(repository.me()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.me()).isInstanceOf(AdminAuthException.class);
    }

    // ---------------------------------------------------------------- no secrets in text

    @Test
    void resultsPrintNoSecret() {
        when(repository.preAuthGet(anyString())).thenReturn(Optional.of(totpRow(null)));
        when(repository.signIn(anyString(), eq("TOTP"), any(), isNull(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new AdminAuthRepository.SessionRow(sessionId, adminId, "Dev admin"));
        AdminAuthService.SignedIn result = service.verify(TOKEN, currentCode(), null, CTX);

        assertThat(result.toString()).doesNotContain(result.accessToken()).doesNotContain(result.refreshToken());
        assertThat(new AdminAuthService.LoginResult(AdminAuthService.Next.TOTP, TOKEN, 300).toString())
                .doesNotContain(TOKEN);
    }
}
