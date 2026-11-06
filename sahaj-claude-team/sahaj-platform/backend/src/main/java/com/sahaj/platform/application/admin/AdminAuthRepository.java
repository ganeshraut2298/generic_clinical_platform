package com.sahaj.platform.application.admin;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The database functions of ADM-1 (docs/database/ADM-1.md), one method per function. Every method
 * is its own transaction that commits unless it throws, so a recorded failure, a lock or a revoked
 * session is kept. Database errors that the sign-in flow must tell apart arrive as
 * {@link AdminDbStateException}. No method takes a plain password, secret, token or code: secrets are
 * ciphertext, tokens and codes are keyed hashes.
 */
public interface AdminAuthRepository {

    /** The one audit summary the database accepts besides the standard text (ADR-003 D5). */
    String SUMMARY_DEV_BYPASS = "dev bypass";

    /** The active admin with this (normalised) email, if any. */
    Optional<LoginLookup> loginLookup(String email);

    /** The end of the lock for this email key, or empty when it is not locked. */
    Optional<Instant> throttleStatus(String keyHash);

    /** Records one failed step. {@code keyHash} may be null when {@code preAuthHash} is given. */
    FailureResult authFailed(String keyHash, UUID adminId, String preAuthHash, String step, String ip,
                              String requestId);

    Instant preAuthCreate(UUID adminId, String keyHash, String tokenHash, String purpose, String ip);

    Optional<PreAuth> preAuthGet(String tokenHash);

    void totpSetPending(String tokenHash, byte[] secretEnc, int keyVersion);

    /**
     * {@code totpStep} is null only under the dev bypass (no replay check). {@code summary} is the audit
     * summary; the only accepted value is {@link #SUMMARY_DEV_BYPASS} (method TOTP), null keeps the standard text.
     */
    SessionRow signIn(String tokenHash, String method, Long totpStep, String recoveryHash, String refreshHash,
                       String ip, String requestId, String summary);

    SessionRow completeEnrolment(String tokenHash, Long totpStep, List<String> recoveryHashes, String refreshHash,
                                  String ip, String requestId);

    /** Per-request check of a signed-in session; moves the idle clock when true. */
    boolean sessionTouch(UUID sessionId, UUID adminId);

    /** Empty for an unknown, dead or reused token. A reuse revokes the session and that is committed. */
    Optional<RotatedSession> sessionRotate(String oldHash, String newHash);

    /** Needs the admin binding of the current request. */
    void signOut();

    /** Needs the admin binding of the current request. */
    Optional<AdminIdentity> me();

    record LoginLookup(UUID adminId, String passwordHash, boolean totpEnrolled) {
        @Override
        public String toString() {
            return "LoginLookup[adminId=" + adminId + "]";
        }
    }

    record FailureResult(Instant lockedUntil, boolean preAuthConsumed) {
    }

    record PreAuth(UUID preAuthId, UUID adminId, String purpose, byte[] totpSecretEnc, Integer totpKeyVersion,
                   byte[] pendingSecretEnc, Integer pendingKeyVersion, Long totpLastUsedStep, String email,
                   String throttleKey) {
        @Override
        public String toString() {
            return "PreAuth[adminId=" + adminId + ", purpose=" + purpose + "]";
        }
    }

    record SessionRow(UUID sessionId, UUID adminId, String fullName) {
        @Override
        public String toString() {
            return "SessionRow[sessionId=" + sessionId + ", adminId=" + adminId + "]";
        }
    }

    /** A rotated session and how it was signed in: {@code TOTP}, {@code RECOVERY_CODE} or {@code ENROL}. */
    record RotatedSession(SessionRow session, String authMethod) {
        @Override
        public String toString() {
            return "RotatedSession[sessionId=" + session.sessionId() + ", authMethod=" + authMethod + "]";
        }
    }
}
