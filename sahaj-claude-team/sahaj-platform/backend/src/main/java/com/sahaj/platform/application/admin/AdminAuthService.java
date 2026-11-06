package com.sahaj.platform.application.admin;

import com.sahaj.platform.api.InvalidInputException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin sign-in (ADR-003 D2): email and password give a pre-auth token, the authenticator code (or
 * a recovery code) gives the session, and a rotating refresh cookie keeps it alive. Rules in
 * Java, narrow checked primitives in the database ({@link AdminAuthRepository}).
 *
 * <p>This class is not {@code @Transactional}: every repository call commits on its own, which is
 * what keeps a recorded failure, a lock or a revoked session when the call then ends in a refusal.
 * Never logs an email, password, code, secret, hash or token; the admin id is the only identifier
 * that appears.
 */
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    public static final int PRE_AUTH_SECONDS = 300;
    public static final Duration SESSION_COOKIE_LIFE = Duration.ofHours(12);
    public static final String ISSUER = "Sahaj admin";
    private static final List<String> AMR_AUTHENTICATOR = List.of("pwd", "otp");
    private static final List<String> AMR_RECOVERY = List.of("pwd", "rcode");
    private static final String PURPOSE_TOTP = "TOTP";
    private static final String PURPOSE_ENROL = "ENROL";
    private static final String METHOD_RECOVERY_CODE = "RECOVERY_CODE";

    private final AdminAuthRepository repository;
    private final AdminTokenService tokens;
    private final AdminTokenHasher hasher;
    private final AdminPasswordHasher passwords;
    private final TotpSecretCipher cipher;
    private final AdminSignInPolicies.TotpDevBypass devBypass;
    private final AdminSignInPolicies.LoginLimiter loginLimiter;
    private final SecureRandom random;
    private final Clock clock;

    public AdminAuthService(AdminAuthRepository repository, AdminTokenService tokens, AdminTokenHasher hasher,
                             AdminPasswordHasher passwords, TotpSecretCipher cipher,
                             AdminSignInPolicies.TotpDevBypass devBypass,
                             AdminSignInPolicies.LoginLimiter loginLimiter,
                             SecureRandom random, Clock clock) {
        this.repository = repository;
        this.tokens = tokens;
        this.hasher = hasher;
        this.passwords = passwords;
        this.cipher = cipher;
        this.devBypass = devBypass;
        this.loginLimiter = loginLimiter;
        this.random = random;
        this.clock = clock;
    }

    /** Where a request came from, for the audit rows. The address is the servlet remote address only. */
    public record ClientContext(String remoteAddress, String requestId) {
    }

    public enum Next { TOTP, ENROL }

    public record LoginResult(Next next, String preAuthToken, int expiresInSeconds) {
        @Override
        public String toString() {
            return "LoginResult[next=" + next + "]";
        }
    }

    public record Enrolment(String secretKey, String otpauthUri, String accountName, String issuer) {
        @Override
        public String toString() {
            return "Enrolment[]";
        }
    }

    public record SignedIn(String accessToken, int expiresInSeconds, AdminIdentity admin,
                           List<String> recoveryCodes, String refreshToken) {
        @Override
        public String toString() {
            return "SignedIn[adminId=" + admin.id() + "]";
        }
    }

    // ------------------------------------------------------------------ step 1

    public LoginResult login(String rawEmail, String password, ClientContext ctx) {
        String email = normaliseEmail(rawEmail);
        String keyHash = hasher.emailKey(email);

        long wait = loginLimiter.retryAfterSecondsIfRefused(keyHash);
        if (wait > 0) {
            throw new AdminAuthException(AdminAuthException.Kind.RATE_LIMITED, wait);
        }
        Optional<Instant> locked = repository.throttleStatus(keyHash);
        if (locked.isPresent()) {
            throw locked(locked.get());
        }

        Optional<AdminAuthRepository.LoginLookup> admin = repository.loginLookup(email);
        boolean correct;
        if (admin.isPresent()) {
            correct = passwords.matches(password, admin.get().passwordHash());
        } else {
            passwords.dummyMatch(password);
            correct = false;
        }
        if (!correct) {
            AdminAuthRepository.FailureResult failure = repository.authFailed(keyHash,
                    admin.map(AdminAuthRepository.LoginLookup::adminId).orElse(null), null, "PASSWORD",
                    ctx.remoteAddress(), ctx.requestId());
            if (failure.lockedUntil() != null) {
                throw locked(failure.lockedUntil());
            }
            throw new AdminAuthException(AdminAuthException.Kind.CREDENTIALS_INVALID);
        }

        AdminAuthRepository.LoginLookup found = admin.get();
        String purpose = found.totpEnrolled() ? PURPOSE_TOTP : PURPOSE_ENROL;
        String token = newToken();
        String tokenHash = hasher.preAuth(token);
        try {
            repository.preAuthCreate(found.adminId(), keyHash, tokenHash, purpose, ctx.remoteAddress());
        } catch (AdminDbStateException ex) {
            // The admin was switched off, or enrolled, between the lookup and this call.
            throw new AdminAuthException(AdminAuthException.Kind.CREDENTIALS_INVALID);
        }
        return new LoginResult(found.totpEnrolled() ? Next.TOTP : Next.ENROL, token, PRE_AUTH_SECONDS);
    }

    // ------------------------------------------------------------------ step 2a

    public Enrolment enrol(String preAuthToken) {
        String tokenHash = hasher.preAuth(preAuthToken);
        AdminAuthRepository.PreAuth row = repository.preAuthGet(tokenHash)
                .orElseThrow(() -> new AdminAuthException(AdminAuthException.Kind.PREAUTH_EXPIRED));
        if (PURPOSE_TOTP.equals(row.purpose())) {
            throw new AdminAuthException(AdminAuthException.Kind.TOTP_ALREADY_ENROLLED);
        }

        if (row.pendingSecretEnc() == null) {
            byte[] fresh = TotpSecrets.newSecret(random);
            repository.totpSetPending(tokenHash,
                    cipher.encrypt(fresh, row.adminId(), TotpSecretCipher.CURRENT_KEY_VERSION),
                    TotpSecretCipher.CURRENT_KEY_VERSION);
            // Another call may have stored a secret first; always read back what is stored.
            row = repository.preAuthGet(tokenHash)
                    .orElseThrow(() -> new AdminAuthException(AdminAuthException.Kind.PREAUTH_EXPIRED));
        }
        if (row.pendingSecretEnc() == null || row.pendingKeyVersion() == null) {
            throw new AdminAuthException(AdminAuthException.Kind.PREAUTH_EXPIRED);
        }
        byte[] secret = cipher.decrypt(row.pendingSecretEnc(), row.adminId(), row.pendingKeyVersion());
        String email = row.email();
        TotpEnrolmentText text = TotpEnrolmentText.of(email, secret);
        return new Enrolment(text.secretKey(), text.otpauthUri(), email, ISSUER);
    }

    // ------------------------------------------------------------------ step 2b

    /** Exactly one of {@code code} and {@code recoveryCode} is given. */
    public SignedIn verify(String preAuthToken, String code, String recoveryCode, ClientContext ctx) {
        if ((code == null) == (recoveryCode == null)) {
            throw new InvalidInputException("code", "Send either the code or a recovery code.");
        }
        String tokenHash = hasher.preAuth(preAuthToken);
        AdminAuthRepository.PreAuth row = repository.preAuthGet(tokenHash)
                .orElseThrow(() -> new AdminAuthException(AdminAuthException.Kind.PREAUTH_EXPIRED));
        refuseWhileLocked(row);

        if (PURPOSE_ENROL.equals(row.purpose())) {
            if (recoveryCode != null) {
                throw new InvalidInputException("recoveryCode", "A recovery code cannot be used for the first sign-in.");
            }
            return confirmEnrolment(tokenHash, row, code, ctx);
        }
        return recoveryCode != null
                ? signInWithRecoveryCode(tokenHash, row, recoveryCode, ctx)
                : signInWithCode(tokenHash, row, code, ctx);
    }

    private SignedIn confirmEnrolment(String tokenHash, AdminAuthRepository.PreAuth row, String code,
                                      ClientContext ctx) {
        if (row.pendingSecretEnc() == null || row.pendingKeyVersion() == null) {
            throw new AdminAuthException(AdminAuthException.Kind.TOTP_ENROLMENT_NOT_STARTED);
        }
        Long step = null;
        boolean bypassed = devBypass.appliesTo(ctx.remoteAddress());
        if (!bypassed) {
            byte[] secret = cipher.decrypt(row.pendingSecretEnc(), row.adminId(), row.pendingKeyVersion());
            step = Totp.matchStep(secret, code, clock.instant(), null);
            if (step == null) {
                throw failed("TOTP", row, tokenHash, ctx);
            }
        }
        List<String> codes = RecoveryCodes.generate(random);
        List<String> hashes = codes.stream().map(c -> hasher.recovery(RecoveryCodes.normalise(c))).toList();
        String refresh = newToken();
        AdminAuthRepository.SessionRow session;
        try {
            session = repository.completeEnrolment(tokenHash, step, hashes, hasher.refresh(refresh),
                    ctx.remoteAddress(), ctx.requestId());
        } catch (AdminDbStateException ex) {
            throw translateSignInState(ex);
        }
        return signedIn(session, AMR_AUTHENTICATOR, codes, refresh, bypassed);
    }

    private SignedIn signInWithCode(String tokenHash, AdminAuthRepository.PreAuth row, String code,
                                    ClientContext ctx) {
        Long step = null;
        boolean bypassed = devBypass.appliesTo(ctx.remoteAddress());
        if (!bypassed) {
            if (row.totpSecretEnc() == null || row.totpKeyVersion() == null) {
                throw new AdminAuthException(AdminAuthException.Kind.PREAUTH_EXPIRED);
            }
            byte[] secret = cipher.decrypt(row.totpSecretEnc(), row.adminId(), row.totpKeyVersion());
            step = Totp.matchStep(secret, code, clock.instant(), row.totpLastUsedStep());
            if (step == null) {
                throw failed("TOTP", row, tokenHash, ctx);
            }
        }
        String refresh = newToken();
        AdminAuthRepository.SessionRow session;
        try {
            session = repository.signIn(tokenHash, "TOTP", step, null, hasher.refresh(refresh),
                    ctx.remoteAddress(), ctx.requestId(), bypassed ? AdminAuthRepository.SUMMARY_DEV_BYPASS : null);
        } catch (AdminDbStateException ex) {
            if (ex.state() == AdminDbStateException.State.TOTP_REPLAY) {
                throw failed("TOTP", row, tokenHash, ctx);
            }
            throw translateSignInState(ex);
        }
        return signedIn(session, AMR_AUTHENTICATOR, null, refresh, bypassed);
    }

    private SignedIn signInWithRecoveryCode(String tokenHash, AdminAuthRepository.PreAuth row, String recoveryCode,
                                            ClientContext ctx) {
        String normalised = RecoveryCodes.normalise(recoveryCode);
        if (!RecoveryCodes.isWellFormed(normalised)) {
            throw failed("RECOVERY_CODE", row, tokenHash, ctx);
        }
        String refresh = newToken();
        AdminAuthRepository.SessionRow session;
        try {
            session = repository.signIn(tokenHash, "RECOVERY_CODE", null, hasher.recovery(normalised),
                    hasher.refresh(refresh), ctx.remoteAddress(), ctx.requestId(), null);
        } catch (AdminDbStateException ex) {
            if (ex.state() == AdminDbStateException.State.RECOVERY_INVALID) {
                throw failed("RECOVERY_CODE", row, tokenHash, ctx);
            }
            throw translateSignInState(ex);
        }
        return signedIn(session, AMR_RECOVERY, null, refresh, false);
    }

    /** A locked email refuses even a correct code; nothing is recorded for the refusal. */
    private void refuseWhileLocked(AdminAuthRepository.PreAuth row) {
        if (row.throttleKey() == null) {
            return;
        }
        Optional<Instant> until = repository.throttleStatus(row.throttleKey());
        if (until.isPresent()) {
            throw locked(until.get());
        }
    }

    /** Records the failed step (committed) and returns the refusal to throw. */
    private AdminAuthException failed(String step, AdminAuthRepository.PreAuth row, String tokenHash,
                                      ClientContext ctx) {
        AdminAuthRepository.FailureResult result;
        try {
            result = repository.authFailed(null, row.adminId(), tokenHash, step, ctx.remoteAddress(),
                    ctx.requestId());
        } catch (AdminDbStateException ex) {
            return new AdminAuthException(AdminAuthException.Kind.PREAUTH_EXPIRED);
        }
        if (result.preAuthConsumed()) {
            return new AdminAuthException(AdminAuthException.Kind.PREAUTH_EXPIRED);
        }
        if (result.lockedUntil() != null) {
            return locked(result.lockedUntil());
        }
        return new AdminAuthException(AdminAuthException.Kind.TOTP_INVALID);
    }

    private static AdminAuthException translateSignInState(AdminDbStateException ex) {
        switch (ex.state()) {
            case PREAUTH_INVALID:
            case BAD_PARAMETER:
                return new AdminAuthException(AdminAuthException.Kind.PREAUTH_EXPIRED);
            case ADMIN_UNAVAILABLE:
                return new AdminAuthException(AdminAuthException.Kind.CREDENTIALS_INVALID);
            case NO_PENDING_SECRET:
                return new AdminAuthException(AdminAuthException.Kind.TOTP_ENROLMENT_NOT_STARTED);
            case ALREADY_ENROLLED:
                return new AdminAuthException(AdminAuthException.Kind.TOTP_ALREADY_ENROLLED);
            default:
                throw ex;
        }
    }

    private SignedIn signedIn(AdminAuthRepository.SessionRow session, List<String> amr, List<String> recoveryCodes,
                              String refreshToken, boolean bypassed) {
        AdminTokenService.AccessToken access = tokens.issue(session.adminId(), session.sessionId(), amr);
        if (bypassed) {
            log.warn("Admin signed in with the dev authenticator bypass adminId={}", session.adminId());
        } else {
            log.info("Admin signed in adminId={}", session.adminId());
        }
        return new SignedIn(access.token(), access.expiresInSeconds(),
                new AdminIdentity(session.adminId(), session.fullName()), recoveryCodes, refreshToken);
    }

    // ------------------------------------------------------------------ session

    /** Rotates the refresh cookie and issues a new access token. */
    public SignedIn refresh(String presentedRefreshToken) {
        if (presentedRefreshToken == null || presentedRefreshToken.isBlank()) {
            throw new AdminAuthException(AdminAuthException.Kind.SESSION_ENDED);
        }
        String next = newToken();
        // An empty result also means "reuse detected, session revoked"; that write is committed already.
        AdminAuthRepository.RotatedSession rotated = repository
                .sessionRotate(hasher.refresh(presentedRefreshToken), hasher.refresh(next))
                .orElseThrow(() -> new AdminAuthException(AdminAuthException.Kind.SESSION_ENDED));
        AdminAuthRepository.SessionRow session = rotated.session();
        // A session opened with a recovery code keeps its weaker amr when the access token is renewed.
        List<String> amr = METHOD_RECOVERY_CODE.equals(rotated.authMethod()) ? AMR_RECOVERY : AMR_AUTHENTICATOR;
        AdminTokenService.AccessToken access = tokens.issue(session.adminId(), session.sessionId(), amr);
        return new SignedIn(access.token(), access.expiresInSeconds(),
                new AdminIdentity(session.adminId(), session.fullName()), null, next);
    }

    /** Needs the admin binding of the current request. */
    public void signOut() {
        try {
            repository.signOut();
        } catch (AdminDbStateException ex) {
            if (ex.state() == AdminDbStateException.State.NO_SESSION) {
                throw new AdminAuthException(AdminAuthException.Kind.SESSION_ENDED);
            }
            throw ex;
        }
    }

    /** Needs the admin binding of the current request. */
    public AdminIdentity me() {
        try {
            return repository.me().orElseThrow(() -> new AdminAuthException(AdminAuthException.Kind.SESSION_ENDED));
        } catch (AdminDbStateException ex) {
            if (ex.state() == AdminDbStateException.State.NO_SESSION) {
                throw new AdminAuthException(AdminAuthException.Kind.SESSION_ENDED);
            }
            throw ex;
        }
    }

    // ------------------------------------------------------------------ helpers

    private AdminAuthException locked(Instant until) {
        long seconds = Math.max(1, Duration.between(clock.instant(), until).toSeconds());
        return new AdminAuthException(AdminAuthException.Kind.SIGN_IN_LOCKED, seconds);
    }

    static String normaliseEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** 256 random bits as Base64url without padding: 43 characters. */
    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
